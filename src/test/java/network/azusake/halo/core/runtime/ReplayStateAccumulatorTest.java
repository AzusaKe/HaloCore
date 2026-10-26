package network.azusake.halo.core.runtime;

import java.util.*;
import network.azusake.halo.api.v2.replay.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReplayStateAccumulatorTest {
    @Test void trimBaselineKeepsAgeAcrossRemovedTimeEvenWithoutAnEventInsideTheCut(){
        UUID wearer=UUID.randomUUID();var state=new ReplayStateAccumulator();
        var visual=new ReplayVisualState("halo:test",2_000,ReplayVisualState.Transition.STARTING,500,.2,true,false,false,false,Map.of());
        var ui=new ReplayUiState(1,true,wearer,"wearer");
        state.restore(new ReplaySnapshot(3,Map.of(wearer,"halo:test"),Map.of(wearer,visual),ui),10_000);
        var atEnd=state.snapshotAt(15_000);
        assertEquals(7_000,atEnd.visuals().get(wearer).ageNanos());
        assertEquals(5_500,atEnd.visuals().get(wearer).transitionAgeNanos());assertEquals(ui,atEnd.ui());
        var copy=new ReplayStateAccumulator();copy.restore(atEnd,0);
        assertEquals(8_000,copy.snapshotAt(1_000).visuals().get(wearer).ageNanos());
    }
    @Test void foldsRemovalShutdownAndUiCloseWithoutRetainingAnEarlierAssignment(){
        UUID wearer=UUID.randomUUID();var state=new ReplayStateAccumulator();
        state.restore(new ReplaySnapshot(1,Map.of(wearer,"halo:test"),Map.of(),new ReplayUiState(1,true,wearer,"wearer")),0);
        var ending=new ReplayVisualState("halo:test",1_000,ReplayVisualState.Transition.ENDING,0,.3,true,false,false,false,Map.of());
        state.apply(new ReplayEvent.WearerChanged(2,wearer,null,ending),100);
        state.apply(new ReplayEvent.UiChanged(3,new ReplayUiState(1,false,null,"")),150);
        var snapshot=state.snapshotAt(200);assertTrue(snapshot.assignments().isEmpty());assertFalse(snapshot.ui().open());
        assertEquals(100,snapshot.visuals().get(wearer).transitionAgeNanos());
        assertEquals(1_100,snapshot.visuals().get(wearer).ageNanos());
    }
}
