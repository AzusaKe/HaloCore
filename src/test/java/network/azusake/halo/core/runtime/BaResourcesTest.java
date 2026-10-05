package network.azusake.halo.core.runtime;

import java.util.*;
import network.azusake.halo.core.Identifier;
import network.azusake.halo.core.render.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BaResourcesTest {
    static final Identifier BODY=new Identifier("model:textures/a_b_body.png"),
        HAIR=new Identifier("model:textures/a_b_hair.png");
    static final String OBJ="v 0 0 0\nv 1 0 0\nv 0 1 0\nvt 0 0\nf 1/1 2/1 3/1";
    static DefinitionSnapshot definition() {
        var definitions=new DefinitionResources();
        String parts="";
        for(String type: List.of("body","arms","face","eyebrow","hair","halo")) {
            if(!parts.isEmpty()) parts+=",";
            String texture=type.equals("hair") ? HAIR.toString() : BODY.toString();
            parts+="{\"type\":\"mesh\",\"model\":\"model:meshes/a_b_"+type+".obj\",\"texture\":\""+texture+"\",\"preserve_proportions\":true}";
        }
        assertTrue(definitions.reload(1,List.of(new ResourceInput(new Identifier("model:test"),"test",
            "{\"id\":\"model:test\",\"layers\":[{\"primitives\":["+parts+"]}]}"))).isEmpty());
        return definitions.snapshot();
    }
    static VisualAssetLoader loader(long generation, Set<Identifier> present) {
        return new VisualAssetLoader(generation,new VisualAssetLoader.Source() {
            public String model(Identifier id) { return OBJ; }
            public VisualResources.TextureInfo texture(Identifier id) { return new VisualResources.TextureInfo(512,512,false); }
            public boolean exists(Identifier id) { return present.contains(id); }
        },problem -> fail(problem.toString()));
    }
    @Test void addingOnlyCompanionsActivatesWholeDefinitionAndRemovingThemRestoresLegacy() {
        var definitions=definition();
        var original=loader(1,Set.of()).load(definitions);
        assertTrue(original.baMaterials().isEmpty());
        var mask=BaMaterial.companion(HAIR,"mask"); var spec=BaMaterial.companion(HAIR,"spac");
        var enabled=loader(2,Set.of(mask,spec)).load(definitions);
        assertEquals(6,enabled.baMaterials().size());
        assertEquals(512,enabled.textures().get(spec).width());
        assertFalse(enabled.textures().get(spec).opaque());
        var hair=enabled.baMaterials().get(new BaMaterial.Key(new Identifier("model:meshes/a_b_hair.obj"),HAIR));
        assertEquals(spec,hair.spec()); assertEquals(mask,hair.mask());
        var face=enabled.baMaterials().get(new BaMaterial.Key(new Identifier("model:meshes/a_b_face.obj"),BODY));
        assertEquals(BaMaterial.Type.FACE,face.type()); assertNull(face.mask());
        var eyebrow=enabled.baMaterials().get(new BaMaterial.Key(new Identifier("model:meshes/a_b_eyebrow.obj"),BODY));
        assertEquals(BaMaterial.Type.UNLIT,eyebrow.type()); assertFalse(eyebrow.castsShadow());
        assertTrue(loader(3,Set.of()).load(definitions).baMaterials().isEmpty());
        assertEquals(2,enabled.generation()); assertTrue(original.baMaterials().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> enabled.baMaterials().clear());
        assertNull(new MaterialState.Mesh(null).ba());
        assertTrue(new VisualResources(0,Map.of(),Map.of()).baMaterials().isEmpty());
    }
    @Test void missingSpecLeavesMaskActiveAndOlderSourcesNeverOptIn() {
        var result=loader(1,Set.of(BaMaterial.companion(HAIR,"mask"))).load(definition());
        assertNull(result.baMaterials().get(new BaMaterial.Key(new Identifier("model:meshes/a_b_hair.obj"),HAIR)).spec());
        assertTrue(new VisualAssetLoader(2,new VisualAssetLoader.Source() {
            public String model(Identifier id) { return OBJ; }
            public VisualResources.TextureInfo texture(Identifier id) { return new VisualResources.TextureInfo(1,1,true); }
        },p -> fail()).load(definition()).baMaterials().isEmpty());
    }
}
