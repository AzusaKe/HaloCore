package network.azusake.halo.core.runtime;

import java.util.*;
import java.util.function.LongSupplier;
import java.util.function.Consumer;
import network.azusake.halo.core.Identifier;
import network.azusake.halo.core.render.*;
import network.azusake.halo.config.HaloConfig;
import network.azusake.halo.data.*;
import network.azusake.halo.render.IdlePhaseTracker;
import network.azusake.halo.render.SceneRenderer;

/** One logical client. Authority replicas outlive entity/render instances. Never shared with a server. */
public final class ClientRuntime implements ClientPort, PreviewPort, ScenePort {
    private final Map<UUID,Identifier> assignments = new LinkedHashMap<>();
    private final Map<UUID,HaloInstance> visuals = new LinkedHashMap<>();
    private final Map<UUID,Integer> entityIds = new HashMap<>();
    private final LongSupplier clock;
    private Long frameTime;
    private ReplayHost replay;
    void bindReplay(ReplayHost value) { replay = value; }
    public boolean isReplay() { return replay != null && replay.isPlayback(); }
    public network.azusake.halo.api.v2.replay.ReplayFrameTime replayFrameTime() {
        return replay == null ? null : replay.frameTime();
    }
    private long worldToken=Long.MIN_VALUE;
    private Map<Identifier,HaloDefinition> definitions=Map.of();
    private Map<Identifier,HaloDefinition> definitionInput;
    private HaloConfig config=new HaloConfig();
    private final SceneRenderer renderer;
    private network.azusake.halo.render.PrimitiveGeometries primitives = new network.azusake.halo.render.PrimitiveGeometries();
    public network.azusake.halo.render.PrimitiveGeometries primitiveGeometries() { return primitives; }
    private long definitionRevision;
    private long previewEpoch;
    private long visualGeneration = Long.MIN_VALUE;

    @Override public PreviewSession openPreview() {
        return openPreview(PreviewOptions.RIGID);
    }
    @Override public PreviewSession openPreview(PreviewOptions options) {
        Objects.requireNonNull(options);
        final long epoch = previewEpoch;
        return new PreviewSession() {
            private SceneRenderer viewRenderer = new SceneRenderer(ClientRuntime.this);
            private final network.azusake.halo.physics.PreviewMotion motion = new network.azusake.halo.physics.PreviewMotion();
            private HaloInstance owner;
            private int runtimeId;
            @Override public boolean isValid() { return viewRenderer != null && epoch == previewEpoch; }
            @Override public void resetMotion() { motion.reset(); owner = null; }
            @Override public FrameOutput render(PreviewFrame frame) {
                Objects.requireNonNull(frame);
                if(isReplay())frame=new PreviewFrame(frame.wearer(),frame.runtimeId(),frame.head(),frame.camera(),
                    frame.rootTransform(),nowMillis(),replay.sourceNanos(),frame.light(),frame.textures(),frame.visuals(),frame.projection(),frame.primitiveMode());
                var appearance = renderer.appearance(frame.wearer());
                if (!isValid() || appearance == null
                        || !Objects.equals(entityIds.get(frame.wearer()), frame.runtimeId())
                        || visualGeneration != frame.visuals().generation()) {
                    resetMotion();
                    return new FrameOutput(frame.visuals().generation(), List.of(), List.of());
                }
                HaloInstance current = visuals.get(frame.wearer());
                if (owner != current || runtimeId != frame.runtimeId()) resetMotion();
                owner = current;
                runtimeId = frame.runtimeId();
                if (options.physicsEnabled())
                    return viewRenderer.renderPreview(frame, appearance,
                        motion.calculate(frame, appearance.definition(), config, isReplay()));
                return viewRenderer.renderPreview(frame, appearance);
            }
            @Override public void close() { resetMotion(); viewRenderer = null; }
        };
    }
    public ClientRuntime() { this(System::currentTimeMillis); }
    public ClientRuntime(LongSupplier clock) { this(clock, id -> {}); }
    /** The warning callback runs on the owning client thread; the host handles localized feedback. */
    public ClientRuntime(LongSupplier clock, Consumer<Identifier> missingDefinitionWarning) {
        this.clock=Objects.requireNonNull(clock);
        this.renderer=new SceneRenderer(this, Objects.requireNonNull(missingDefinitionWarning));
    }
    public long nowMillis() { return isReplay() ? replay.nowMillis() : frameTime == null ? clock.getAsLong() : frameTime; }
    public HaloConfig getConfig() { return config; }
    public void setConfig(HaloConfig value) { config=value.copy(); }
    public void definitions(DefinitionSnapshot snapshot) {
        if (definitionInput == snapshot.definitions()) return;
        installDefinitions(snapshot.definitions());
        primitives = snapshot.preparedPrimitives();
    }
    public Map<UUID,BodyPose> bodyPoses() { return renderer.bodyPoses(); }
    public void definitions(Map<Identifier,HaloDefinition> value) {
        if (definitionInput == value) return;
        installDefinitions(value);
        primitives = new network.azusake.halo.render.PrimitiveGeometries();
        value.values().forEach(def -> def.model().groups().forEach(primitives::prepare));
    }
    private void installDefinitions(Map<Identifier,HaloDefinition> value) {
        definitionInput=value;
        definitionRevision++;
        renderer.clearAppearances();
        definitions=Map.copyOf(value); visuals.values().forEach(HaloInstance::invalidateDefinition);
    }
    public Optional<HaloDefinition> definition(Identifier id) { return Optional.ofNullable(definitions.get(id)); }
    public void attach(UUID uuid,Identifier definition,boolean startup) {
        if (replay != null && replay.blocksLiveMutations()) return;
        if (attachState(uuid, definition, startup) && replay != null) replay.changed(uuid);
    }
    private boolean attachState(UUID uuid,Identifier definition,boolean startup) {
        if (definition.equals(assignments.get(uuid)) && visuals.containsKey(uuid)) return false;
        renderer.clearEntity(uuid);
        assignments.put(uuid,definition);
        HaloInstance inst=new HaloInstance(uuid,definition,this::nowMillis);
        if (startup) { inst.setTransitionState(HaloTransitionState.STARTING); inst.startTransition(0); }
        visuals.put(uuid,inst);
        return true;
    }
    public void replace(Map<UUID,Identifier> snapshot) {
        if (replay != null && replay.blocksLiveMutations()) return;
        clearState(); snapshot.forEach((uuid,id)->attachState(uuid,id,false));
        if (replay != null) replay.reset();
    }
    public void hide(UUID uuid,Identifier definition) {
        if (replay != null && replay.blocksLiveMutations()) return;
        boolean removed = assignments.remove(uuid) != null;
        HaloInstance inst=visuals.get(uuid);
        if (inst==null || inst.getTransitionState()==HaloTransitionState.ENDING) {
            if (removed && replay != null) replay.changed(uuid);
            return;
        }
        IdlePhaseTracker.RenderState state=renderer.readLastRenderState(uuid);
        HaloDefinition def=definitions.get(inst.getDefinitionId());
        double freeze=inst.currentAnimTime(def==null?null:def.startupAnimation().orElse(null));
        inst.setHiddenByState(false); inst.reactivate();
        inst.setTransitionState(HaloTransitionState.ENDING); inst.startTransition(freeze);
        if (state!=null && state.transitionActive() && !state.groups().isEmpty()) inst.setHideVisuals(state.groups());
        if (replay != null) replay.changed(uuid);
    }
    public void removeClientHalo(UUID uuid) { visuals.remove(uuid); renderer.clearEntity(uuid); }
    public void unload(UUID uuid) {
        if (isReplay()) { renderer.clearEntity(uuid); HaloInstance inst=visuals.get(uuid); if(inst!=null)inst.markNeedsSnap(); }
        else removeClientHalo(uuid);
        entityIds.remove(uuid);
    }
    public void died(UUID uuid, boolean player) {
        if (replay != null && replay.blocksLiveMutations()) { unload(uuid); return; }
        unload(uuid); if (!player && assignments.remove(uuid) != null && replay != null) replay.changed(uuid);
    }
    public void teleport(UUID uuid) { HaloInstance v=visuals.get(uuid); if(v!=null)v.markTeleported(); }
    public void clear() {
        if (replay != null && replay.blocksLiveMutations()) return;
        clearState(); if (replay != null) replay.reset();
    }
    private void clearState() { previewEpoch++; assignments.clear(); visuals.clear(); entityIds.clear(); renderer.clearWorld(); worldToken=Long.MIN_VALUE; }
    void installReplayAssignment(UUID uuid, Identifier id) {
        if (id == null) assignments.remove(uuid); else assignments.put(uuid, id);
    }
    void installReplayVisual(UUID uuid, HaloInstance inst) {
        renderer.clearEntity(uuid); visuals.put(uuid, inst);
    }
    void clearReplayVisuals() { visuals.clear(); renderer.clearWorld(); }
    void resetReplayMotion() { previewEpoch++; renderer.resetMotion(); visuals.values().forEach(HaloInstance::markNeedsSnap); }
    public HaloInstance getInstance(UUID uuid) { return visuals.get(uuid); }
    public Collection<HaloInstance> getAllInstances() { return visuals.values(); }
    public Map<UUID,HaloInstance> getActiveHalos() { return Map.copyOf(visuals); }
    public Map<UUID,Identifier> assignments() { return Map.copyOf(assignments); }
    public Map<UUID,ClientStatus> diagnostics() {
        var result = new LinkedHashMap<UUID,ClientStatus>();
        visuals.forEach((uuid, value) -> result.put(uuid, new ClientStatus(value.getDefinitionId(),
            value.getCreatedAtTime(), value.isActive(), value.isNeedsSnap(), value.getTransitionState())));
        return Map.copyOf(result);
    }
    public SceneRenderer renderer() { return renderer; }
    private FrameOutput produceFrame(FrameScene scene) { return produceFrame(scene, renderer::renderHalos); }
    @Override public SceneFrame renderScene(FrameScene scene) {
        return produceFrame(scene, input -> renderer.renderScene(input, definitionRevision));
    }
    private <T> T produceFrame(FrameScene scene, java.util.function.Function<FrameScene,T> render) {
        if (isReplay()) {
            scene = new FrameScene(scene.worldToken(), replay.nowMillis(), replay.sourceNanos(), scene.camera(),
                scene.entities(), scene.rootTransform(), scene.lights(), scene.textures(), scene.visuals(),
                scene.lightmaps(), scene.primitiveMode());
        }
        frameTime=scene.timeMillis();
        final FrameScene input=scene;
        try {
            if(worldToken!=scene.worldToken()) {
                if(worldToken!=Long.MIN_VALUE) {
                    previewEpoch++; if (!isReplay()) visuals.clear(); entityIds.clear(); renderer.clearWorld();
                }
                worldToken=scene.worldToken();
            }
            var gone = visuals.keySet().stream().filter(uuid -> !input.entities().containsKey(uuid)
                || !input.entities().get(uuid).alive()).toList();
            gone.forEach(this::unload);
            entityIds.keySet().retainAll(scene.entities().keySet());
            scene.entities().forEach((uuid, entity) -> {
                Integer previous = entityIds.put(uuid, entity.runtimeId());
                if (previous != null && previous != entity.runtimeId()) unload(uuid);
            });
            assignments.forEach((uuid,id)-> {
                if(input.entities().containsKey(uuid) && input.entities().get(uuid).alive()) visuals.computeIfAbsent(uuid,k->new HaloInstance(k,id,this::nowMillis));
            });
            visualGeneration=scene.visuals().generation();
            T output = render.apply(scene);
            if (replay != null) replay.afterFrame();
            return output;
        } finally { frameTime=null; }
    }
    @Override public FrameOutput renderFrame(FrameScene scene) { return produceFrame(scene); }
    @Override public List<DrawBatch> render(FrameScene scene) {
        return produceFrame(scene).expandedBatches(scene.visuals());
    }
    public HaloInstance getHaloInstance(UUID uuid) { return getInstance(uuid); }
    public void putClientHalo(UUID uuid,Identifier id) { attach(uuid,id,false); }
    public void putClientHalo(UUID uuid,Identifier id,HaloTransitionState state) { attach(uuid,id,state==HaloTransitionState.STARTING); }
    public void replaceAllClientHalos(Map<UUID,Identifier> values) { replace(values); }
    public void clearAllClientHalos() { clear(); }
    public int getActiveCount() { return visuals.size(); }
    public void forceRemoveHalo(UUID uuid) { removeClientHalo(uuid); }
}
