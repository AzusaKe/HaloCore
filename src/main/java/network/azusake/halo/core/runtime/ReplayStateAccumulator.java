package network.azusake.halo.core.runtime;

import java.util.*;
import network.azusake.halo.api.v2.replay.*;

/** Neutral state folding for container trimming. No definitions, game runtime or physical history are needed. */
public final class ReplayStateAccumulator {
    private final Map<UUID,String> assignments=new LinkedHashMap<>();
    private final Map<UUID,TimedVisual> visuals=new LinkedHashMap<>();
    private ReplayUiState ui=ReplayUiState.CLOSED;
    private long sequence,lastTime;
    private record TimedVisual(ReplayVisualState value,long source){}
    public void restore(ReplaySnapshot snapshot,long sourceNanos){
        if(sourceNanos<0)throw new IllegalArgumentException("negative source time");
        assignments.clear();assignments.putAll(snapshot.assignments());visuals.clear();
        snapshot.visuals().forEach((uuid,value)->visuals.put(uuid,new TimedVisual(value,sourceNanos)));
        ui=snapshot.ui();sequence=snapshot.sequence();lastTime=sourceNanos;
    }
    public void apply(ReplayEvent event,long sourceNanos){
        if(sourceNanos<lastTime)throw new IllegalArgumentException("out-of-order source time");
        if(event.sequence()<=sequence)return;
        if(event instanceof ReplayEvent.Reset reset){restore(reset.snapshot(),sourceNanos);return;}
        if(event instanceof ReplayEvent.WearerChanged change){
            if(change.assignment()==null)assignments.remove(change.wearer());else assignments.put(change.wearer(),change.assignment());
            if(change.visual()==null)visuals.remove(change.wearer());else visuals.put(change.wearer(),new TimedVisual(change.visual(),sourceNanos));
        }else if(event instanceof ReplayEvent.UiChanged change && change.ui().session()>=ui.session())ui=change.ui();
        sequence=event.sequence();lastTime=sourceNanos;
    }
    public ReplaySnapshot snapshotAt(long sourceNanos){
        if(sourceNanos<lastTime)throw new IllegalArgumentException("snapshot precedes accumulated state");
        var values=new LinkedHashMap<UUID,ReplayVisualState>();
        visuals.forEach((uuid,timed)->{
            var value=timed.value();long delta=sourceNanos-timed.source();
            values.put(uuid,new ReplayVisualState(value.definitionId(),Math.addExact(value.ageNanos(),delta),value.transition(),
                value.transitionAgeNanos()<0?-1:Math.addExact(value.transitionAgeNanos(),delta),value.frozenPhaseSeconds(),
                value.active(),value.hiddenByState(),value.sleeping(),value.invisible(),value.shutdownHead()));
        });
        return new ReplaySnapshot(sequence,assignments,values,ui);
    }
    public long lastSourceNanos(){return lastTime;}
}
