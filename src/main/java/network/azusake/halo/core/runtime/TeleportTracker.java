package network.azusake.halo.core.runtime;

import java.util.*;
import network.azusake.halo.core.Vec3d;

/** Lifecycle discontinuity tracking, with the original 250 ms grace and 1000-unit safety net. */
public final class TeleportTracker {
    private final Map<UUID,Long> recent=new HashMap<>();
    private final Map<UUID,Vec3d> positions=new HashMap<>();
    public void mark(UUID uuid,long millis){recent.put(uuid,millis);}
    public boolean isRecent(UUID uuid){return recent.containsKey(uuid);}
    public void expire(long millis){recent.values().removeIf(t->millis-t>250);}
    public boolean moved(UUID uuid,Vec3d position){
        Vec3d previous=positions.put(uuid,position);
        return previous!=null && position.squaredDistanceTo(previous)>1_000_000;
    }
    public void remove(UUID uuid){recent.remove(uuid);positions.remove(uuid);}
    public void clear(){recent.clear();positions.clear();}
}
