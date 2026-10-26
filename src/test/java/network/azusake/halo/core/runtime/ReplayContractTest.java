package network.azusake.halo.core.runtime;

import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import network.azusake.halo.api.v2.*;
import network.azusake.halo.api.v2.replay.*;
import network.azusake.halo.core.*;
import network.azusake.halo.core.render.*;
import network.azusake.halo.data.*;
import network.azusake.halo.json.HaloDefinitionDeserializer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReplayContractTest {
    private final UUID wearer=UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final AtomicLong wall=new AtomicLong(10_000);
    private final ClientRuntime runtime=new ClientRuntime(wall::get);
    private HaloDefinition definition() throws Exception {
        String json=new String(getClass().getResourceAsStream("/definitions/ring_default.json").readAllBytes(), StandardCharsets.UTF_8);
        return new HaloDefinitionDeserializer().deserialize(JsonParser.parseString(json), HaloDefinition.class,null);
    }
    private FrameScene scene(double x, double camera, boolean loaded) {
        var anchor=new AnchorPose(new AnchorVec3(x,65.6,0),new AnchorRotation(0,0,0,1));
        return new FrameScene(1,wall.get(),wall.get()*1_000_000,
            new FrameScene.CameraSample(new Vec3d(camera,64,3),new Vec3d(0,1,0),new Vec3d(1,0,0)),
            loaded?Map.of(wearer,new FrameScene.EntitySample(wearer,1,new Vec3d(x,64,0),true,false,false,anchor,anchor)):Map.of(),
            new float[]{1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1},p->1,t->true);
    }
    @Test void recordsEveryEffectiveChangeAndSnapshotWatermarkButNotHealthyUnload() {
        List<ReplayEvent> events=new ArrayList<>();
        try(var host=new ReplayHost(runtime,ui->{},fail -> fail(fail))) {
            var recording=HaloReplayApi.openRecording("test:recorder",events::add).orElseThrow();
            assertTrue(recording.snapshot().assignments().isEmpty());
            var id=new Identifier("halo:test");
            runtime.attach(wearer,id,true); runtime.attach(wearer,id,true);
            runtime.hide(wearer,id); runtime.hide(wearer,id);
            assertEquals(2,events.size());
            assertEquals(2,recording.snapshot().sequence());
            assertTrue(recording.snapshot().assignments().isEmpty());
            assertEquals(ReplayVisualState.Transition.ENDING,recording.snapshot().visuals().get(wearer).transition());
            runtime.attach(wearer,id,false);
            int count=events.size(); runtime.unload(wearer);
            assertEquals(count,events.size()); assertEquals(id,runtime.assignments().get(wearer));
            recording.close(); runtime.hide(wearer,id); assertEquals(count,events.size());
        }
    }
    @Test void snapshotRestoresStartupProgressAndZeroIsAValidTimeOrigin() throws Exception {
        var def=definition(); runtime.definitions(Map.of(def.id(),def));
        try(var host=new ReplayHost(runtime,ui->{},fail -> fail(fail))) {
            wall.set(0); runtime.attach(wearer,def.id(),true);
            wall.set(120); var expected=runtime.render(scene(0,0,true));
            var snapshot=HaloReplayApi.openRecording("test:recorder",event->{}).orElseThrow().snapshot();
            assertEquals(120_000_000,snapshot.visuals().get(wearer).transitionAgeNanos());
            var playback=HaloReplayApi.openPlayback("test:player").orElseThrow();
            playback.restore(snapshot,0,0);
            playback.frame(new ReplayFrameTime(0,0,0,0,true));
            wall.set(1_000_000);
            assertEquals(expected,runtime.render(scene(0,0,true)));
            assertTrue(runtime.getInstance(wearer).hasTransition());
            assertEquals(.12,runtime.getInstance(wearer).getTransitionElapsed(),1e-6);
        }
    }
    @Test void repeatedViewsAndPausedSourceDoNotAdvancePhysicsOrAnimation() throws Exception {
        var def=definition(); runtime.definitions(Map.of(def.id(),def)); runtime.attach(wearer,def.id(),false);
        try(var host=new ReplayHost(runtime,ui->{},fail -> fail(fail))) {
            var baseline=HaloReplayApi.openRecording("test:recorder",event->{}).orElseThrow().snapshot();
            var playback=HaloReplayApi.openPlayback("test:player").orElseThrow();
            playback.restore(baseline,0,0);
            playback.frame(new ReplayFrameTime(0,0,0,0,true)); runtime.render(scene(0,0,true));
            playback.frame(new ReplayFrameTime(0,1,50_000_000,25_000_000,false)); runtime.render(scene(1,0,true));
            var body=runtime.bodyPoses().get(wearer);
            double phase=runtime.renderer().appearance(wearer).animationTime();
            for(int i=0;i<20;i++) {
                wall.addAndGet(100_000); runtime.render(scene(1,20,true));
                assertEquals(body,runtime.bodyPoses().get(wearer));
                assertEquals(phase,runtime.renderer().appearance(wearer).animationTime());
            }
            playback.frame(new ReplayFrameTime(0,2,50_000_000,50_000_000,false));
            runtime.render(scene(1,-20,true)); assertEquals(body,runtime.bodyPoses().get(wearer));
        }
    }
    @Test void sourceRatherThanOutputOrWallTimeDrivesPlaybackSpeed() throws Exception {
        var def=definition(); runtime.definitions(Map.of(def.id(),def)); runtime.attach(wearer,def.id(),false);
        try(var host=new ReplayHost(runtime,ui->{},fail -> fail(fail))) {
            var snapshot=HaloReplayApi.openRecording("test:recorder",event->{}).orElseThrow().snapshot();
            var playback=HaloReplayApi.openPlayback("test:player").orElseThrow(); playback.restore(snapshot,0,0);
            playback.frame(new ReplayFrameTime(0,0,0,0,true)); runtime.render(scene(0,0,true));
            playback.frame(new ReplayFrameTime(0,1,1_000_000_000,500_000_000,false));
            wall.addAndGet(100_000); runtime.render(scene(0,0,true));
            assertEquals(1,runtime.renderer().appearance(wearer).animationTime(),1e-6);
            playback.mode(ReplayPlaybackSession.Mode.EXPORTING);
            assertEquals(1_000,runtime.nowMillis());
        }
    }
    @Test void physicalPreviewAlsoFreezesWithTheOwningReplaySource() throws Exception {
        var def=definition();runtime.definitions(Map.of(def.id(),def));runtime.attach(wearer,def.id(),false);
        try(var host=new ReplayHost(runtime,ui->{},message->fail(message))){
            var snapshot=HaloReplayApi.openRecording("test:recorder",event->{}).orElseThrow().snapshot();
            var playback=HaloReplayApi.openPlayback("test:player").orElseThrow();playback.restore(snapshot,0,0);
            playback.frame(new ReplayFrameTime(0,0,0,0,true));runtime.render(scene(0,0,true));
            var preview=runtime.openPreview(PreviewOptions.PHYSICS);
            var world=scene(0,0,true);
            var first=new PreviewFrame(wearer,1,new AnchorPose(new AnchorVec3(0,1,0),new AnchorRotation(0,0,0,1)),
                world.camera(),world.rootTransform(),0,0,LightSample.FULL_BRIGHT,t->true,VisualResources.EMPTY);
            var expected=preview.render(first);
            var later=new PreviewFrame(wearer,1,first.head(),first.camera(),first.rootTransform(),1_000_000,1_000_000_000_000L,
                first.light(),first.textures(),first.visuals());
            assertEquals(expected,preview.render(later));preview.close();
        }
    }
    @Test void endingSnapshotWithoutAssignmentAndDelayedEntityStillFinishes() throws Exception {
        var def=definition(); runtime.definitions(Map.of(def.id(),def)); runtime.attach(wearer,def.id(),true);
        wall.addAndGet(100); runtime.render(scene(0,0,true)); runtime.hide(wearer,def.id());
        wall.addAndGet(50); var expected=runtime.render(scene(0,0,true));
        try(var host=new ReplayHost(runtime,ui->{},fail -> fail(fail))) {
            var snapshot=HaloReplayApi.openRecording("test:recorder",event->{}).orElseThrow().snapshot();
            assertTrue(snapshot.assignments().isEmpty()); assertFalse(snapshot.visuals().isEmpty());
            var playback=HaloReplayApi.openPlayback("test:player").orElseThrow(); playback.restore(snapshot,0,0);
            playback.frame(new ReplayFrameTime(0,0,0,0,true));
            runtime.render(scene(0,0,false));
            assertEquals(expected,runtime.render(scene(0,0,true)));
            playback.frame(new ReplayFrameTime(0,1,5_000_000_000L,5_000_000_000L,false));
            assertTrue(runtime.render(scene(0,0,true)).isEmpty());
            assertTrue(runtime.assignments().isEmpty());
        }
    }
    @Test void emptyRestoreClearsFutureAndOldEpochCannotOverwriteNewSeek() {
        try(var host=new ReplayHost(runtime,ui->{},fail -> fail(fail))) {
            var playback=HaloReplayApi.openPlayback("test:player").orElseThrow();
            var baseline=new ReplaySnapshot(0,Map.of(wearer,"halo:test"),Map.of(),ReplayUiState.CLOSED);
            playback.restore(baseline,0,1);
            var event=new ReplayEvent.WearerChanged(1,wearer,"halo:other",null);
            assertTrue(playback.apply(event,10,1)); assertFalse(playback.apply(event,10,1));
            playback.restore(new ReplaySnapshot(0,Map.of(),Map.of(),ReplayUiState.CLOSED),0,2);
            assertFalse(playback.apply(event,10,1)); playback.restore(baseline,0,1);
            assertTrue(runtime.assignments().isEmpty());
            runtime.attach(wearer,new Identifier("halo:live"),false);
            assertTrue(runtime.assignments().isEmpty());
        }
    }
    @Test void uiSessionsRejectDelayedCloseAndPlaybackDoesNotRecordItself() {
        List<ReplayEvent> events=new ArrayList<>(); List<ReplayUiState> displayed=new ArrayList<>();
        try(var host=new ReplayHost(runtime,displayed::add,fail -> fail(fail))) {
            var recording=HaloReplayApi.openRecording("test:recorder",events::add).orElseThrow();
            long first=host.uiOpened(wearer,"first"), second=host.uiOpened(wearer,"second");
            host.uiClosed(first); assertTrue(host.uiState().open());
            host.uiClosed(second); host.uiClosed(second); assertEquals(3,events.size());
            var snapshot=recording.snapshot();
            var playback=HaloReplayApi.openPlayback("test:player").orElseThrow();
            assertFalse(recording.isValid()); assertTrue(HaloReplayApi.openPlayback("test:other").isEmpty());
            playback.restore(snapshot,0,0);
            host.uiOpened(wearer,"ignored"); assertEquals(3,events.size());
            playback.close(); playback.close(); assertFalse(playback.isValid());
            assertEquals(ReplayUiState.CLOSED,displayed.get(displayed.size()-1));
        }
    }
    @Test void invalidThreadAndBadListenerCannotCorruptOrdinaryOwnership() throws Exception {
        List<String> errors=new ArrayList<>();
        try(var host=new ReplayHost(runtime,ui->{},errors::add)) {
            HaloReplayApi.openRecording("test:bad",event->{throw new IllegalStateException("test failure");}).orElseThrow();
            runtime.attach(wearer,new Identifier("halo:test"),false);
            assertEquals(1,errors.size()); assertEquals(1,runtime.assignments().size());
            var caught=new java.util.concurrent.atomic.AtomicReference<Throwable>();
            var thread=new Thread(()->{try{HaloReplayApi.openPlayback("test:player");}catch(Throwable e){caught.set(e);}});
            thread.start(); thread.join(); assertInstanceOf(IllegalStateException.class,caught.get());
        }
        assertTrue(HaloReplayApi.openPlayback("test:player").isEmpty());
    }
    @Test void handlesRemainSafelyInspectableAndClosableAfterHostTeardown() {
        var host=new ReplayHost(runtime,ui->{},message->fail(message));
        var recording=HaloReplayApi.openRecording("test:recorder",event->{}).orElseThrow();
        host.close();assertFalse(recording.isValid());recording.close();
        var nextHost=new ReplayHost(runtime,ui->{},message->fail(message));
        var playback=HaloReplayApi.openPlayback("test:player").orElseThrow();
        nextHost.close();assertFalse(playback.isValid());playback.close();
    }
}
