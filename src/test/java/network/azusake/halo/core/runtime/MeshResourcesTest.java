package network.azusake.halo.core.runtime;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import network.azusake.halo.core.Identifier;
import network.azusake.halo.core.render.MaterialState;
import network.azusake.halo.core.render.VisualResources;
import network.azusake.halo.shape.MeshPrimitive;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MeshResourcesTest {
    private static final Identifier ID = new Identifier("halo:test"), MODEL = new Identifier("halo:models/test.obj"),
        TEXTURE = new Identifier("halo:test.png"), MASK = new Identifier("halo:mask.png");
    static final String PRIMITIVE = """
        {"type":"mesh","model":"halo:models/test.obj","texture":"halo:test.png","size":[1,1,0]}
        """.trim();

    @Test void defaultsAndBothPrimitiveFormsRetainHierarchyAndDependencies() {
        var resources = new DefinitionResources();
        String definition = "{\"id\":\"halo:test\",\"layers\":[{\"primitive\":" + PRIMITIVE
            + ",\"children\":[{\"primitives\":[" + PRIMITIVE + "]}]}]}";
        assertTrue(resources.reload(1, List.of(new ResourceInput(ID, "pack", definition))).isEmpty());
        var mesh = (MeshPrimitive) resources.legacyDefinitions().get(ID).model().groups().get(0).primitives().get(0);
        assertTrue(mesh.material().doubleSided()); assertNull(mesh.material().mask());
        assertEquals(Set.of(MODEL), resources.snapshot().assets().models());
        assertEquals(Set.of(TEXTURE), resources.snapshot().assets().textures());
        assertThrows(UnsupportedOperationException.class, () -> resources.snapshot().assets().models().clear());
    }

    @Test void maskParsesSharedTermsAndWrapsOffsetsWithoutClampingMotion() {
        var mesh = parse(withMaterial("""
            {"double_sided":false,"effects":[{"type":"alpha_mask","texture":"halo:mask.png","mode":"step",
            "uv_offset":{"u":[{"function":"linear","start":-0.25,"speed":0},{"function":"cos","A":1,"omega":0}],
            "v":[{"function":"sin","A":0.25,"omega":1}]}}]}
            """));
        var effect = mesh.material().mask().evaluate(.5);
        assertFalse(mesh.material().doubleSided());
        assertEquals(.75f, effect.offsetU()); assertEquals(.25f, effect.offsetV(), 1e-6);
        assertEquals(MaterialState.MaskMode.STEP, effect.mode());
        assertEquals(.5f, effect.threshold()); assertEquals(MASK, effect.texture());
        assertEquals(0, effect.alpha(.49f)); assertEquals(1, effect.alpha(.5f)); assertEquals(1, effect.alpha(1));
        var linear = parse(withMaterial("{\"effects\":[{\"type\":\"alpha_mask\",\"texture\":\"halo:mask.png\"}]}"))
            .material().mask().evaluate(0);
        assertEquals(0, linear.alpha(0)); assertEquals(.5f, linear.alpha(.5f)); assertEquals(1, linear.alpha(1));
    }

    @Test void malformedMeshDefinitionsAreIsolatedFromValidDefinitions() {
        for (String bad : List.of(PRIMITIVE.replace("[1,1,0]", "[1,1]"), PRIMITIVE.replace("[1,1,0]", "[-1,1,0]"),
                PRIMITIVE.replace("[1,1,0]", "[1e99,1,0]"), PRIMITIVE.replace("test.obj", "test.glb"),
                PRIMITIVE.replace("halo:models/test.obj", "halo:../test.obj"),
                withMaterial("{\"effects\":[{\"type\":\"other\"}]}"),
                withMaterial("{\"effects\":[{},{}]}"),
                withMaterial("{\"effects\":[{\"type\":\"alpha_mask\",\"texture\":\"halo:mask.png\",\"threshold\":1.1}]}"),
                withMaterial("{\"effects\":[{\"type\":\"alpha_mask\",\"texture\":\"halo:mask.png\",\"mode\":\"bad\"}]}"),
                withMaterial("{\"effects\":[{\"type\":\"alpha_mask\",\"texture\":\"halo:mask.png\",\"uv_offset\":{\"u\":[{\"function\":\"linear\",\"speed\":1e999}]}}]}"))) {
            var resources = new DefinitionResources();
            var errors = resources.reload(1, List.of(new ResourceInput(ID, "bad-pack", definition(bad)),
                new ResourceInput(new Identifier("halo:good"), "good-pack", "{\"id\":\"halo:good\"}")));
            assertEquals(1, errors.size(), bad); assertEquals("bad-pack", errors.get(0).source());
            assertEquals(Set.of(new Identifier("halo:good")), resources.snapshot().ids());
        }
    }

    @Test void modelCacheSharesGeometryAndCachesFailuresUntilNewGeneration() {
        var reads = new AtomicInteger(); var textureReads = new AtomicInteger(); var available = new AtomicBoolean(true);
        var errors = new ArrayList<VisualAssetLoader.Problem>();
        var source = new VisualAssetLoader.Source() {
            public String model(Identifier id) throws IOException {
                reads.incrementAndGet();
                if (!available.get()) throw new IOException("missing");
                return "v 0 0 0\nv 1 0 0\nv 0 1 0\nvt 0 0\nf 1/1 2/1 3/1";
            }
            public VisualResources.TextureInfo texture(Identifier id) { textureReads.incrementAndGet(); return new VisualResources.TextureInfo(4,4,true); }
        };
        var loader = new VisualAssetLoader(1, source, errors::add);
        var deps = new DefinitionSnapshot.AssetDependencies(Set.of(MODEL), Set.of(TEXTURE));
        var first = loader.load(deps); var again = loader.load(deps);
        assertSame(first.meshes().get(MODEL), again.meshes().get(MODEL));
        assertEquals(1, reads.get()); assertEquals(1, textureReads.get());
        var empty = loader.load(new DefinitionSnapshot.AssetDependencies(Set.of(), Set.of()));
        assertTrue(empty.meshes().isEmpty()); assertFalse(first.meshes().isEmpty());
        assertSame(first.meshes().get(MODEL), loader.load(deps).meshes().get(MODEL));
        available.set(false);
        var missing = new VisualAssetLoader(2, source, errors::add);
        assertTrue(missing.load(deps).meshes().isEmpty());
        available.set(true);
        assertTrue(missing.load(deps).meshes().isEmpty()); assertEquals(1, errors.size());
        assertFalse(new VisualAssetLoader(3, source, errors::add).load(deps).meshes().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> first.meshes().clear());
    }

    private static MeshPrimitive parse(String primitive) {
        var resources = new DefinitionResources();
        assertTrue(resources.reload(1, List.of(new ResourceInput(ID, "pack", definition(primitive)))).isEmpty());
        return (MeshPrimitive) resources.legacyDefinitions().get(ID).model().groups().get(0).primitives().get(0);
    }
    private static String definition(String primitive) { return "{\"id\":\"halo:test\",\"layers\":[{\"primitive\":" + primitive + "}]}"; }
    private static String withMaterial(String material) { return PRIMITIVE.substring(0, PRIMITIVE.length()-1) + ",\"material\":" + material + "}"; }
}
