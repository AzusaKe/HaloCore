package network.azusake.halo.core.runtime;

import java.util.*;
import network.azusake.halo.core.Identifier;

/** Frozen pre-source ServerRuntime ABI used only to compile the compatibility caller. */
public final class ServerRuntime {
    public interface OwnershipStore { Identifier get(UUID entity); void set(UUID entity,Identifier definition); void remove(UUID entity); }
    public interface Updates { void attach(UUID entity,Identifier definition); void remove(UUID entity,Identifier definition); }
    public ServerRuntime(OwnershipStore store,Updates updates) { throw new AssertionError(); }
    public ServerRuntime(OwnershipStore store,Updates updates,java.util.function.LongSupplier clock) { throw new AssertionError(); }
    public void show(UUID entity,Identifier definition) { throw new AssertionError(); }
    public boolean hide(UUID entity) { throw new AssertionError(); }
    public Identifier restore(UUID entity) { throw new AssertionError(); }
    public void unload(UUID entity) { throw new AssertionError(); }
    public void died(UUID entity,boolean player) { throw new AssertionError(); }
    public Identifier get(UUID entity) { throw new AssertionError(); }
    public Map<UUID,Identifier> snapshot() { throw new AssertionError(); }
    public long createdAt(UUID entity) { throw new AssertionError(); }
}
