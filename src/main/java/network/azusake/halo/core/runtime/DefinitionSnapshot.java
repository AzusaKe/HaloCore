package network.azusake.halo.core.runtime;

import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import network.azusake.halo.core.Identifier;
import network.azusake.halo.data.HaloDefinition;
import network.azusake.halo.shape.HaloGroup;
import network.azusake.halo.shape.MeshPrimitive;

/** Opaque immutable definition generation. Parsed implementation types stay behind this boundary. */
public final class DefinitionSnapshot {
    private final Map<Identifier, HaloDefinition> definitions;
    private final AssetDependencies assets;

    DefinitionSnapshot(Map<Identifier, HaloDefinition> definitions) {
        this.definitions = Map.copyOf(definitions);
        var models = new LinkedHashSet<Identifier>();
        var textures = new LinkedHashSet<Identifier>();
        this.definitions.values().forEach(def -> def.model().groups().forEach(group -> collect(group, models, textures)));
        assets = new AssetDependencies(models, textures);
    }

    public Set<Identifier> ids() { return definitions.keySet(); }
    public AssetDependencies assets() { return assets; }
    public record AssetDependencies(Set<Identifier> models, Set<Identifier> textures) {
        public AssetDependencies { models = Set.copyOf(models); textures = Set.copyOf(textures); }
    }
    private static void collect(HaloGroup group, Set<Identifier> models, Set<Identifier> textures) {
        for (var primitive : group.primitives()) if (primitive instanceof MeshPrimitive mesh) {
            models.add(mesh.model()); textures.add(mesh.texture());
            if (mesh.material().mask() != null) textures.add(mesh.material().mask().texture());
        }
        group.children().forEach(child -> collect(child, models, textures));
    }
    Map<Identifier, HaloDefinition> definitions() { return definitions; }
}
