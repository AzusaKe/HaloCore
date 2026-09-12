package network.azusake.halo.core.runtime;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import network.azusake.halo.core.Identifier;

/** Server-owned client capability reports. Reports contain IDs, never authoritative definitions. */
public final class DefinitionReports {
    private final Map<UUID,Set<Identifier>> reports=new ConcurrentHashMap<>();
    public void put(UUID player,Set<Identifier> ids){reports.put(player,Set.copyOf(ids));}
    public void remove(UUID player){reports.remove(player);}
    public Set<Identifier> get(UUID player){return reports.getOrDefault(player,Set.of());}
    public Set<Identifier> all(){Set<Identifier> ids=new LinkedHashSet<>();reports.values().forEach(ids::addAll);return Set.copyOf(ids);}
    public void clear(){reports.clear();}
}
