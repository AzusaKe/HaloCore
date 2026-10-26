package network.azusake.halo.core.runtime;

import java.util.*;
import java.util.function.Consumer;
import network.azusake.halo.api.v2.replay.*;
import network.azusake.halo.core.Identifier;
import network.azusake.halo.data.*;

/** One client-thread replay host. Platform I/O, screens and recorder APIs remain outside core. */
public final class ReplayHost implements AutoCloseable {
    private static ReplayHost bound;
    private final ClientRuntime runtime;
    private final Thread owner = Thread.currentThread();
    private final Consumer<ReplayUiState> uiSink;
    private final Consumer<String> diagnostic;
    private final Map<String, Recording> recordings = new LinkedHashMap<>();
    private final Map<UUID, VisualIdentity> observed = new HashMap<>();
    private Playback playback;
    private ReplayUiState ui = ReplayUiState.CLOSED;
    private long sequence, nextUiSession;
    private boolean closed, applying;

    public ReplayHost(ClientRuntime runtime, Consumer<ReplayUiState> uiSink, Consumer<String> diagnostic) {
        this.runtime = Objects.requireNonNull(runtime);
        this.uiSink = Objects.requireNonNull(uiSink);
        this.diagnostic = Objects.requireNonNull(diagnostic);
        if (bound != null) throw new IllegalStateException("a replay host is already bound");
        runtime.bindReplay(this);
        bound = this;
    }

    public static Optional<ReplayRecordingSession> openRecording(String backendId, Consumer<ReplayEvent> listener) {
        requireId(backendId); Objects.requireNonNull(listener);
        if (bound == null) return Optional.empty();
        var host = bound; host.check();
        if (host.playback != null) return Optional.empty();
        if (host.recordings.containsKey(backendId)) throw new IllegalStateException("backend already recording: " + backendId);
        var result = host.new Recording(backendId, listener);
        host.recordings.put(backendId, result);
        return Optional.of(result);
    }

    public static Optional<ReplayPlaybackSession> openPlayback(String backendId) {
        requireId(backendId);
        if (bound == null) return Optional.empty();
        var host = bound; host.check();
        if (host.playback != null) return Optional.empty();
        for (var recording : List.copyOf(host.recordings.values())) recording.close();
        host.playback = host.new Playback();
        return Optional.of(host.playback);
    }

    public boolean isPlayback() { return playback != null; }
    public boolean ownsState() { return playback != null && playback.track; }
    boolean blocksLiveMutations() { return ownsState() && !applying; }
    public long sourceNanos() { return playback == null ? 0 : playback.source; }
    long nowMillis() { return sourceNanos() / 1_000_000; }
    public ReplayPlaybackSession.Mode mode() {
        return playback == null ? ReplayPlaybackSession.Mode.PLAYBACK : playback.mode;
    }
    public ReplayFrameTime frameTime() { return playback == null ? null : playback.frame; }
    public ReplayUiState uiState() { check(); return ui; }

    /** Called only for real platform UI lifecycle changes, never when displaying playback UI. */
    public long uiOpened(UUID target, String name) {
        check();
        if (isPlayback()) return 0;
        ui = new ReplayUiState(++nextUiSession, true, target, name);
        emit(new ReplayEvent.UiChanged(++sequence, ui));
        return ui.session();
    }
    public void uiClosed(long session) {
        check();
        if (isPlayback() || !ui.open() || ui.session() != session) return;
        ui = new ReplayUiState(session, false, null, "");
        emit(new ReplayEvent.UiChanged(++sequence, ui));
    }

    void changed(UUID wearer) {
        if (isPlayback() || applying || closed || recordings.isEmpty()) return;
        observed.put(wearer, identity(runtime.getInstance(wearer)));
        String assignment = Optional.ofNullable(runtime.assignments().get(wearer)).map(Object::toString).orElse(null);
        emit(new ReplayEvent.WearerChanged(++sequence, wearer, assignment, visual(runtime.getInstance(wearer))));
    }
    void reset() {
        if (isPlayback() || applying || closed || recordings.isEmpty()) return;
        observed.clear(); ++sequence;
        emit(new ReplayEvent.Reset(snapshot()));
    }
    /** Observe lifecycle edges created by rendering, not the continuously advancing animation age. */
    void afterFrame() {
        if (isPlayback() || closed || recordings.isEmpty()) return;
        for (var inst : List.copyOf(runtime.getAllInstances())) {
            var current = identity(inst);
            if (!Objects.equals(current, observed.get(inst.getEntityUuid()))) changed(inst.getEntityUuid());
        }
    }
    private VisualIdentity identity(HaloInstance value) {
        return value == null ? null : new VisualIdentity(value.getDefinitionId().toString(), value.getCreatedAtTime(),
            value.getTransitionState(), value.getTransitionStartTime(), value.hasTransition(), value.isActive(),
            value.isHiddenByState(), value.isEntitySleeping(), value.isEntityInvisible());
    }
    private record VisualIdentity(String definition, long created, HaloTransitionState state, long started,
                                  boolean transition, boolean active, boolean hidden, boolean sleeping, boolean invisible) {}

    private ReplaySnapshot snapshot() {
        var assignments = new LinkedHashMap<UUID, String>();
        runtime.assignments().forEach((uuid, id) -> assignments.put(uuid, id.toString()));
        var visuals = new LinkedHashMap<UUID, ReplayVisualState>();
        runtime.getAllInstances().forEach(inst -> visuals.put(inst.getEntityUuid(), visual(inst)));
        return new ReplaySnapshot(sequence, assignments, visuals, ui);
    }
    private ReplayVisualState visual(HaloInstance inst) {
        if (inst == null) return null;
        var groups = new LinkedHashMap<String, ReplayGroupState>();
        inst.getHideVisuals().forEach((key, value) -> groups.put(key, new ReplayGroupState(
            vector(value.offset()), vector(value.scale()), value.alpha(), vector(value.rotation()))));
        long now = runtime.nowMillis();
        return new ReplayVisualState(inst.getDefinitionId().toString(), Math.max(0, now - inst.getCreatedAtTime()) * 1_000_000,
            ReplayVisualState.Transition.valueOf(inst.getTransitionState().name()),
            inst.hasTransition() ? Math.max(0, now - inst.getTransitionStartTime()) * 1_000_000 : -1,
            inst.getTransitionFreezeAnimTime(), inst.isActive(), inst.isHiddenByState(),
            inst.isEntitySleeping(), inst.isEntityInvisible(), groups);
    }
    private static List<Float> vector(float[] value) { return List.of(value[0], value[1], value[2]); }
    private static float[] array(List<Float> value) { return new float[]{value.get(0), value.get(1), value.get(2)}; }

    private void restoreVisual(UUID wearer, ReplayVisualState state) {
        if (state == null) { runtime.removeClientHalo(wearer); return; }
        Identifier id = new Identifier(state.definitionId());
        var inst = new HaloInstance(wearer, id, runtime::nowMillis);
        inst.restorePresentation(state.ageNanos() / 1_000_000, HaloTransitionState.valueOf(state.transition().name()),
            state.transitionAgeNanos() < 0 ? -1 : state.transitionAgeNanos() / 1_000_000,
            state.frozenPhaseSeconds(), state.active(), state.hiddenByState());
        inst.setEntitySleeping(state.sleeping()); inst.setEntityInvisible(state.invisible());
        var groups = new LinkedHashMap<String, GroupVisualSnapshot>();
        state.shutdownHead().forEach((key, value) -> groups.put(key,
            new GroupVisualSnapshot(array(value.offset()), array(value.scale()), value.alpha(), array(value.rotation()))));
        inst.setHideVisuals(groups);
        runtime.installReplayVisual(wearer, inst);
        runtime.renderer().restoreVisibility(wearer, state.sleeping(), state.invisible());
    }
    private void emit(ReplayEvent event) {
        for (var recording : List.copyOf(recordings.values())) {
            try { recording.listener.accept(event); }
            catch (RuntimeException failure) {
                recording.close();
                diagnostic.accept("Replay recording subscription " + recording.id + " failed: " + failure);
            }
        }
    }
    /** Connection teardown; a recording world switch should use a snapshot instead. */
    public void disconnect() {
        check();
        if (playback != null) playback.close();
        for (var recording : List.copyOf(recordings.values())) recording.close();
        ui = ReplayUiState.CLOSED; observed.clear();
    }
    @Override public void close() {
        if (closed) return;
        check(); disconnect(); closed = true; runtime.bindReplay(null);
        if (bound == this) bound = null;
    }
    private void check() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("replay calls require the owning client thread");
        if (closed) throw new IllegalStateException("replay host is closed");
    }
    private static void requireId(String value) {
        if (value == null || !value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
            throw new IllegalArgumentException("backendId must be a lowercase namespace:path");
    }

    private final class Recording implements ReplayRecordingSession {
        private final String id;
        private final Consumer<ReplayEvent> listener;
        private boolean released;
        Recording(String id, Consumer<ReplayEvent> listener) { this.id = id; this.listener = listener; }
        @Override public ReplaySnapshot snapshot() {
            check(); if (released) throw new IllegalStateException("recording session is closed");
            return ReplayHost.this.snapshot();
        }
        @Override public boolean isValid() { check(); return !released; }
        @Override public void close() {
            check(); if (released) return; released = true; recordings.remove(id, this);
        }
    }

    private final class Playback implements ReplayPlaybackSession {
        private boolean released, track;
        private long source, epoch = -1, watermark = -1, lastEventTime = -1;
        private ReplayFrameTime frame;
        private Mode mode = Mode.PLAYBACK;
        private void requireActive() {
            check(); if (released) throw new IllegalStateException("playback session is closed");
        }
        @Override public void mode(Mode value) { requireActive(); mode = Objects.requireNonNull(value); uiSink.accept(ui); }
        @Override public void restore(ReplaySnapshot snapshot, long sourceNanos, long restoreEpoch) {
            requireActive(); Objects.requireNonNull(snapshot); requireTime(sourceNanos, restoreEpoch);
            if (restoreEpoch < epoch) return;
            if (restoreEpoch == epoch && snapshot.sequence() < watermark) return;
            // Validate identifiers before changing any live state.
            snapshot.assignments().values().forEach(Identifier::new);
            snapshot.visuals().values().forEach(value -> new Identifier(value.definitionId()));
            source = sourceNanos; epoch = restoreEpoch; frame = null; track = true;
            applying = true;
            try {
                var assignments = new LinkedHashMap<UUID, Identifier>();
                snapshot.assignments().forEach((uuid, id) -> assignments.put(uuid, new Identifier(id)));
                runtime.replace(assignments);
                // replace creates NORMAL instances; install only the recorded presentation set.
                runtime.clearReplayVisuals();
                snapshot.visuals().forEach(ReplayHost.this::restoreVisual);
                ui = snapshot.ui(); watermark = snapshot.sequence(); lastEventTime = sourceNanos;
            } finally { applying = false; }
            uiSink.accept(ui);
        }
        @Override public boolean apply(ReplayEvent event, long sourceNanos, long eventEpoch) {
            requireActive(); Objects.requireNonNull(event); requireTime(sourceNanos, eventEpoch);
            if (!track || eventEpoch != epoch || event.sequence() <= watermark) return false;
            if (sourceNanos < lastEventTime) throw new IllegalArgumentException("out-of-order replay event time");
            if (event instanceof ReplayEvent.Reset reset) {
                restore(reset.snapshot(), sourceNanos, eventEpoch);
                return true;
            }
            long savedSource = source; source = sourceNanos;
            applying = true;
            try {
                if (event instanceof ReplayEvent.WearerChanged change) {
                    Identifier assignment = change.assignment() == null ? null : new Identifier(change.assignment());
                    if (change.visual() != null) new Identifier(change.visual().definitionId());
                    runtime.installReplayAssignment(change.wearer(), assignment);
                    restoreVisual(change.wearer(), change.visual());
                } else if (event instanceof ReplayEvent.UiChanged change) {
                    if (change.ui().session() >= ui.session()) ui = change.ui();
                }
                watermark = event.sequence(); lastEventTime = sourceNanos;
            } finally { source = savedSource; applying = false; }
            uiSink.accept(ui);
            return true;
        }
        @Override public void frame(ReplayFrameTime time) {
            requireActive(); Objects.requireNonNull(time);
            if (time.epoch() < epoch) return;
            if (track && time.epoch() != epoch) throw new IllegalStateException("restore the new seek epoch before sampling");
            if (track && time.sourceNanos() < lastEventTime)
                throw new IllegalStateException("frame precedes its restored state");
            if (frame != null && time.epoch() == frame.epoch()) {
                if (time.sample() < frame.sample()) throw new IllegalArgumentException("sample identity moved backwards");
                if (time.sample() == frame.sample() && !time.equals(frame))
                    throw new IllegalArgumentException("a repeated sample must retain the same time");
                if (time.sourceNanos() < frame.sourceNanos()) throw new IllegalStateException("backward time requires snapshot restoration");
            }
            if (time.discontinuity() && (frame == null || !time.equals(frame))) runtime.resetReplayMotion();
            epoch = time.epoch(); source = time.sourceNanos(); frame = time;
        }
        @Override public boolean isValid() { check(); return !released; }
        @Override public void close() {
            check(); if (released) return;
            released = true;
            applying = true;
            try { runtime.clear(); } finally { applying = false; }
            ui = ReplayUiState.CLOSED; playback = null; observed.clear(); uiSink.accept(ui);
        }
        private void requireTime(long time, long generation) {
            if (time < 0 || generation < 0) throw new IllegalArgumentException("negative source time or epoch");
        }
    }
}
