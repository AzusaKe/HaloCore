package network.azusake.halo.core.runtime;

import java.util.*;
import network.azusake.halo.api.v2.replay.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReplayTimelineTest {
    @Test void continuousPlaybackSkipsCoveredSnapshotsButSeekRestoresTheirPresentation(){
        UUID wearer=UUID.randomUUID();ClientRuntime runtime=new ClientRuntime(()->0);
        var visual=new ReplayVisualState("halo:test",0,ReplayVisualState.Transition.NORMAL,-1,0,true,false,false,false,Map.of());
        var initial=new ReplaySnapshot(0,Map.of(wearer,"halo:test"),Map.of(wearer,visual),ReplayUiState.CLOSED);
        var ui=new ReplayUiState(1,true,wearer,"wearer");
        var laterVisual=new ReplayVisualState("halo:test",2_000_000_000L,ReplayVisualState.Transition.NORMAL,-1,0,true,false,false,false,Map.of());
        var checkpoint=new ReplaySnapshot(1,initial.assignments(),Map.of(wearer,laterVisual),ui);
        var timeline=new ReplayTimeline(List.of(new ReplayTimeline.Entry(0,0,initial,null),
            new ReplayTimeline.Entry(1_000_000_000L,1,null,new ReplayEvent.UiChanged(1,ui)),
            new ReplayTimeline.Entry(2_000_000_000L,2,checkpoint,null)));
        try(var host=new ReplayHost(runtime,state->{},message->fail(message))){
            var playback=HaloReplayApi.openPlayback("test:player").orElseThrow();
            timeline.advance(playback,0,0,1,false);var original=runtime.getInstance(wearer);
            timeline.advance(playback,2_100_000_000L,1_050_000_000L,2,false);
            assertSame(original,runtime.getInstance(wearer),"a checkpoint covered by events must preserve the live simulation");
            assertEquals(ui,host.uiState());
            timeline.advance(playback,2_100_000_000L,1_050_000_000L,3,true);
            assertNotSame(original,runtime.getInstance(wearer),"a seek still restores its nearest checkpoint");
            assertEquals(2_100,runtime.nowMillis());
            assertEquals(0,runtime.getInstance(wearer).getCreatedAtTime());
        }
    }
    @Test void uncoveredSnapshotsStillRecoverMissingEventsAndFirstZeroSequenceState(){
        UUID wearer=UUID.randomUUID();ClientRuntime runtime=new ClientRuntime(()->0);
        var initial=new ReplaySnapshot(0,Map.of(wearer,"halo:first"),Map.of(),ReplayUiState.CLOSED);
        var recovery=new ReplaySnapshot(2,Map.of(wearer,"halo:recovered"),Map.of(),ReplayUiState.CLOSED);
        var timeline=new ReplayTimeline(List.of(new ReplayTimeline.Entry(100,0,initial,null),
            new ReplayTimeline.Entry(200,1,recovery,null),
            new ReplayTimeline.Entry(250,2,null,new ReplayEvent.WearerChanged(3,wearer,"halo:after",null))));
        try(var host=new ReplayHost(runtime,ui->{},message->fail(message))){
            var playback=HaloReplayApi.openPlayback("test:player").orElseThrow();
            timeline.advance(playback,0,0,1,false);assertTrue(runtime.assignments().isEmpty());
            timeline.advance(playback,100,50,2,false);assertEquals("halo:first",runtime.assignments().get(wearer).toString());
            timeline.advance(playback,200,100,3,false);assertEquals("halo:recovered",runtime.assignments().get(wearer).toString());
            timeline.advance(playback,250,125,4,false);assertEquals("halo:after",runtime.assignments().get(wearer).toString());
        }
    }
    @Test void sameTimeOrderingSeekAndLateStreamingRecordsShareOneRestorationRule(){
        UUID wearer=UUID.randomUUID();ClientRuntime runtime=new ClientRuntime(()->0);
        var timeline=new ReplayTimeline(List.of(new ReplayTimeline.Entry(0,0,new ReplaySnapshot(0,Map.of(),Map.of(),ReplayUiState.CLOSED),null),
            new ReplayTimeline.Entry(100,1,null,new ReplayEvent.WearerChanged(1,wearer,"halo:first",null)),
            new ReplayTimeline.Entry(100,2,null,new ReplayEvent.WearerChanged(2,wearer,"halo:second",null))));
        try(var host=new ReplayHost(runtime,ui->{},message->fail(message))){
            var playback=HaloReplayApi.openPlayback("test:player").orElseThrow();
            timeline.advance(playback,200,100,1,false);assertEquals("halo:second",runtime.assignments().get(wearer).toString());
            timeline.advance(playback,0,0,2,true);assertTrue(runtime.assignments().isEmpty());
            timeline.advance(playback,200,100,3,true);
            timeline.append(new ReplayTimeline.Entry(150,3,null,new ReplayEvent.WearerChanged(3,wearer,null,null)));
            assertTrue(timeline.needsAdvance(200));timeline.advance(playback,200,100,4,false);
            assertTrue(runtime.assignments().isEmpty());
        }
    }
}
