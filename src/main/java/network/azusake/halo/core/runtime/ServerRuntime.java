package network.azusake.halo.core.runtime;

import java.util.*;
import network.azusake.halo.core.Identifier;

/** Server-thread ownership service. No animation instances, renderer or game objects. */
public final class ServerRuntime {
    public interface OwnershipStore {
        Identifier get(UUID entity);
        void set(UUID entity,Identifier definition);
        void remove(UUID entity);
    }
    public interface Updates {
        void attach(UUID entity,Identifier definition);
        void remove(UUID entity,Identifier definition);
    }
    private final java.util.function.LongSupplier clock;
    private final Map<UUID,Long> createdAt = new HashMap<>();
    public long createdAt(UUID entity) { return createdAt.getOrDefault(entity, 0L); }
    private final OwnershipStore store;
    private final Updates updates;
    private final Map<UUID,Identifier> active = new LinkedHashMap<>();
    public ServerRuntime(OwnershipStore store,Updates updates) { this(store,updates,System::currentTimeMillis); }
    public ServerRuntime(OwnershipStore store,Updates updates,java.util.function.LongSupplier clock) { this.store=store;this.updates=updates;this.clock=clock; }
    public void show(UUID entity,Identifier definition) {
        store.set(entity,definition); createdAt.put(entity,clock.getAsLong()); active.put(entity,definition); updates.attach(entity,definition);
    }
    public boolean hide(UUID entity) {
        createdAt.remove(entity);
        Identifier definition=active.remove(entity);
        if(definition==null) definition=store.get(entity);
        if(definition==null) return false;
        store.remove(entity); updates.remove(entity,definition); return true;
    }
    public Identifier restore(UUID entity) {
        if(active.containsKey(entity)) return active.get(entity);
        Identifier definition=store.get(entity);
        if(definition!=null) { createdAt.put(entity,clock.getAsLong()); active.put(entity,definition); updates.attach(entity,definition); }
        return definition;
    }
    public void unload(UUID entity) { active.remove(entity); createdAt.remove(entity); }
    public void died(UUID entity,boolean player) { unload(entity); if(!player)store.remove(entity); }
    public Identifier get(UUID entity) { return active.get(entity); }
    public Map<UUID,Identifier> snapshot() { return Map.copyOf(active); }
}
