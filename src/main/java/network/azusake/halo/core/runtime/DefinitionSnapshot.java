package network.azusake.halo.core.runtime;

import java.util.Map;
import java.util.Set;
import network.azusake.halo.core.Identifier;
import network.azusake.halo.data.HaloDefinition;

/** Opaque immutable definition generation. Parsed implementation types stay behind this boundary. */
public final class DefinitionSnapshot {
    private final Map<Identifier, HaloDefinition> definitions;

    DefinitionSnapshot(Map<Identifier, HaloDefinition> definitions) {
        this.definitions = Map.copyOf(definitions);
    }

    public Set<Identifier> ids() { return definitions.keySet(); }
    Map<Identifier, HaloDefinition> definitions() { return definitions; }
}
