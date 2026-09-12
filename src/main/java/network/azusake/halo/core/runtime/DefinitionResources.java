package network.azusake.halo.core.runtime;

import com.google.gson.JsonParser;
import java.util.*;
import network.azusake.halo.core.DefinitionCatalog;
import network.azusake.halo.core.Identifier;
import network.azusake.halo.data.HaloDefinition;
import network.azusake.halo.json.HaloDefinitionDeserializer;

/** Parse a complete source reload before atomically publishing a new generation. */
public final class DefinitionResources {
    public record Problem(Identifier resource, String source, String message) {}
    private final DefinitionCatalog<HaloDefinition> catalog = new DefinitionCatalog<>();
    private volatile DefinitionSnapshot snapshot = new DefinitionSnapshot(Map.of());

    public synchronized List<Problem> reload(int sourcePriority, Collection<ResourceInput> resources) {
        var parsed = new LinkedHashMap<Identifier, HaloDefinition>();
        var problems = new ArrayList<Problem>();
        var parser = new HaloDefinitionDeserializer();
        for (ResourceInput resource : resources) {
            try {
                var definition = parser.deserialize(JsonParser.parseString(resource.json()), HaloDefinition.class, null);
                parsed.put(definition.id(), definition);
            } catch (RuntimeException ex) {
                problems.add(new Problem(resource.resource(), resource.source(), String.valueOf(ex.getMessage())));
            }
        }
        catalog.replace(sourcePriority, parsed);
        snapshot = new DefinitionSnapshot(catalog.snapshot());
        return List.copyOf(problems);
    }

    public DefinitionSnapshot snapshot() { return snapshot; }

    /** Compatibility access for the original definition inspection commands. Not a platform port. */
    public Map<Identifier, HaloDefinition> legacyDefinitions() { return snapshot.definitions(); }
}
