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
    private final Map<UUID,String> selectedSources = new HashMap<>();
    private final Map<UUID,Integer> selectedPriorities = new HashMap<>();
    public record Selection(String sourceId,int priority,Identifier definition) {}
    public ServerRuntime(OwnershipStore store,Updates updates) { this(store,updates,System::currentTimeMillis); }
    public ServerRuntime(OwnershipStore store,Updates updates,java.util.function.LongSupplier clock) { this.store=store;this.updates=updates;this.clock=clock; }
    public void show(UUID entity,Identifier definition) {
        store.set(entity,definition);createdAt.put(entity,clock.getAsLong());active.put(entity,definition);
        selectedSources.put(entity,"halo:legacy_world_data");selectedPriorities.put(entity,0);updates.attach(entity,definition);
    }
    public boolean hide(UUID entity) {
        Identifier definition=active.get(entity);
        if(definition==null) definition=store.get(entity);
        if(definition==null) return false;
        store.remove(entity); clearSourceSelection(entity); return true;
    }
    public Identifier restore(UUID entity) {
        if(active.containsKey(entity)) return active.get(entity);
        Identifier definition=store.get(entity);
        if(definition!=null) applySourceSelection(entity,"halo:legacy_world_data",0,definition);
        return definition;
    }
    public void unload(UUID entity) { active.remove(entity); createdAt.remove(entity); selectedSources.remove(entity); selectedPriorities.remove(entity); }
    public void died(UUID entity,boolean player) { unload(entity); if(!player)store.remove(entity); }
    public Identifier get(UUID entity) { return active.get(entity); }
    public Selection selection(UUID entity) {
        Identifier definition=active.get(entity); String source=selectedSources.get(entity); Integer priority=selectedPriorities.get(entity);
        return definition==null||source==null||priority==null?null:new Selection(source,priority,definition);
    }
    public void applySourceSelection(UUID entity,String sourceId,int priority,Identifier definition) {
        Objects.requireNonNull(entity);Objects.requireNonNull(sourceId);Objects.requireNonNull(definition);
        Identifier previous=active.put(entity,definition);selectedSources.put(entity,sourceId);selectedPriorities.put(entity,priority);
        if(definition.equals(previous))return;
        createdAt.put(entity,clock.getAsLong());updates.attach(entity,definition);
    }
    public void clearSourceSelection(UUID entity) {
        Identifier definition=active.remove(entity);createdAt.remove(entity);selectedSources.remove(entity);selectedPriorities.remove(entity);
        if(definition!=null)updates.remove(entity,definition);
    }
    public Map<UUID,Identifier> snapshot() { return Map.copyOf(active); }
}
