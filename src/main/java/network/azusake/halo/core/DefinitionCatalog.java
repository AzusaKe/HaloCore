package network.azusake.halo.core;

import java.util.*;

/** A reload replaces one source atomically. Higher-priority sources override lower ones. */
public final class DefinitionCatalog<T> {
    private final NavigableMap<Integer,Map<Identifier,T>> sources = new TreeMap<>();
    private volatile Map<Identifier,T> snapshot=Map.of();
    public synchronized void replace(int priority,Map<Identifier,T> values) {
        sources.put(priority,Map.copyOf(values));
        Map<Identifier,T> merged=new LinkedHashMap<>();
        sources.values().forEach(merged::putAll);
        snapshot=Collections.unmodifiableMap(merged);
    }
    public Map<Identifier,T> snapshot() { return snapshot; }
}
