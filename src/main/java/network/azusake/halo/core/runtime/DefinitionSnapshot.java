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
    private final Set<Identifier> legacyTextures;
    private network.azusake.halo.render.PrimitiveGeometries primitives;

    DefinitionSnapshot(Map<Identifier, HaloDefinition> definitions) {
        this.definitions = Map.copyOf(definitions);
        var models = new LinkedHashSet<Identifier>();
        var textures = new LinkedHashSet<Identifier>();
        this.definitions.values().forEach(def -> def.model().groups().forEach(group -> collect(group, models, textures)));
        assets = new AssetDependencies(models, textures);
        var legacy = new LinkedHashSet<Identifier>();
        this.definitions.values().forEach(def -> def.model().groups().forEach(group -> {
            collectLegacy(group, legacy);
        }));
        legacyTextures = Set.copyOf(legacy);
    }

    public Set<Identifier> legacyTextures() { return legacyTextures; }
    public Map<Identifier, network.azusake.halo.core.render.PrimitiveGeometry> primitiveGeometries() { return preparedPrimitives().snapshot(); }
    /** Client loading-stage preparation; dedicated servers never need to allocate drawing geometry. */
    synchronized network.azusake.halo.render.PrimitiveGeometries preparedPrimitives() {
        if (primitives == null) {
            var next = new network.azusake.halo.render.PrimitiveGeometries();
            definitions.values().forEach(def -> def.model().groups().forEach(next::prepare));
            primitives = next;
        }
        return primitives;
    }
    private static void collectLegacy(HaloGroup group, Set<Identifier> textures) {
        for (var primitive : group.primitives()) {
            if (primitive instanceof network.azusake.halo.shape.BillboardPrimitive billboard && billboard.texture() != null)
                textures.add(billboard.texture());
            else if (primitive instanceof network.azusake.halo.shape.RingPrimitive ring) {
                if (ring.outerTexture() != null) textures.add(ring.outerTexture());
                if (ring.innerTexture() != null) textures.add(ring.innerTexture());
            }
        }
        group.children().forEach(child -> collectLegacy(child, textures));
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
