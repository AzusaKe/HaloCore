package network.azusake.halo.core.runtime;

import java.util.*;
import network.azusake.halo.api.v2.replay.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReplayTimelineTest {
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
