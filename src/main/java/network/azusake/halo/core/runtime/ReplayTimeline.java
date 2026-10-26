package network.azusake.halo.core.runtime;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import network.azusake.halo.api.v2.replay.*;

/** Neutral indexed/streaming restoration. Containers supply timestamps and order, never library objects. */
public final class ReplayTimeline {
    private static final AtomicLong EPOCHS=new AtomicLong();
    public static long nextEpoch(){return EPOCHS.incrementAndGet();}
    public record Entry(long sourceNanos,long order,ReplaySnapshot snapshot,ReplayEvent event){
        public Entry{
            if(sourceNanos<0 || order<0 || (snapshot==null)==(event==null))throw new IllegalArgumentException("invalid timed replay record");
        }
    }
    private static final Comparator<Entry> ORDER=Comparator.comparingLong(Entry::sourceNanos).thenComparingLong(Entry::order);
    private final List<Entry> entries;
    private final Set<Object> seen=new HashSet<>();
    private boolean track,initialized;
    private long epoch,previous=-1;
    private int cursor;
    public ReplayTimeline(List<Entry> input){
        entries=new ArrayList<>(input.stream().sorted(ORDER).toList());
        for(var entry:entries){seen.add(key(entry));track|=entry.snapshot()!=null;}
    }
    private static Object key(Entry entry){return List.of(entry.sourceNanos(),entry.snapshot()!=null?entry.snapshot():entry.event());}
    public boolean hasTrack(){return track;}
    public boolean needsAdvance(long source){return !initialized || cursor<entries.size() && entries.get(cursor).sourceNanos()<=source;}
    public void append(Entry entry){
        if(!seen.add(key(entry)))return;
        track|=entry.snapshot()!=null;
        if(entry.sourceNanos()<previous || !entries.isEmpty() && entry.sourceNanos()<entries.get(entries.size()-1).sourceNanos()){
            entries.add(entry);entries.sort(ORDER);initialized=false;
        }else entries.add(entry);
    }
    public void advance(ReplayPlaybackSession playback,long source,long output,long sample,boolean seek){
        if(!initialized || seek || source<previous){
            epoch=nextEpoch();int baseline=-1;
            for(int i=0;i<entries.size() && entries.get(i).sourceNanos()<=source;i++)if(entries.get(i).snapshot()!=null)baseline=i;
            if(baseline<0){playback.restore(new ReplaySnapshot(0,Map.of(),Map.of(),ReplayUiState.CLOSED),0,epoch);cursor=0;}
            else{var entry=entries.get(baseline);playback.restore(entry.snapshot(),entry.sourceNanos(),epoch);cursor=baseline+1;}
            initialized=true;
        }
        while(cursor<entries.size() && entries.get(cursor).sourceNanos()<=source){
            var entry=entries.get(cursor++);
            if(entry.snapshot()!=null)playback.restore(entry.snapshot(),entry.sourceNanos(),epoch);
            else playback.apply(entry.event(),entry.sourceNanos(),epoch);
        }
        playback.frame(new ReplayFrameTime(epoch,sample,source,output,seek));previous=source;
    }
    public void reset(){initialized=false;previous=-1;cursor=0;}
}
