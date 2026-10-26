package compat;

import java.util.*;
import network.azusake.halo.api.v2.replay.*;

/** Compiled using only public API classes, run with the real core and JDK. */
public final class ReplayConsumer {
    public static void main(String[] args) {
        var empty=new ReplaySnapshot(0,Map.of(),Map.of(),ReplayUiState.CLOSED);
        if(!empty.assignments().isEmpty())throw new AssertionError();
        if(HaloReplayApi.openPlayback("fixture:player").isPresent())throw new AssertionError("unexpected host");
        if(HaloReplayApi.openRecording("fixture:recorder",event->{}).isPresent())throw new AssertionError("unexpected host");
        var time=new ReplayFrameTime(0,1,1_000_000_000,500_000_000,false);
        if(time.sourceNanos()!=2*time.outputNanos())throw new AssertionError();
        System.out.println("Replay consumer compiled against public v2 types and ran with core + JDK only.");
    }
}
