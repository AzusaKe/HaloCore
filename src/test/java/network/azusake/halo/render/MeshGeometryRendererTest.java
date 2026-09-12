package network.azusake.halo.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import network.azusake.halo.animation.AnimationTerm;
import network.azusake.halo.core.Identifier;
import network.azusake.halo.core.Vec3d;
import network.azusake.halo.core.render.*;
import network.azusake.halo.shape.MeshPrimitive;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MeshGeometryRendererTest {
    private static final Identifier MODEL = new Identifier("halo:model.obj"), TEX = new Identifier("halo:base.png"), MASK = new Identifier("halo:mask.png");
    private static final TriangleMesh MESH = new TriangleMesh(
        new float[]{1,2,-1, 3,2,-1, 1,6,-1, 1,2,-3, 3,2,-3, 1,6,-3},
        new float[]{0,0, 1,0, 0,1, 0,0, 1,0, 0,1}, new int[]{0,1,2,3,4,5});
    private static MeshPrimitive primitive(MeshPrimitive.AlphaMask mask) {
        return new MeshPrimitive(MODEL, TEX, new Vec3d(1,1,2), new MeshPrimitive.Material(false, mask));
    }
    private static MeshPrimitive.AlphaMask mask(MaterialState.MaskMode mode) {
        return new MeshPrimitive.AlphaMask(MASK, mode, .5f, List.of(new AnimationTerm.Linear(.25)), List.of());
    }
    private static VisualResources resources(long generation, boolean opaque, int maskWidth) {
        return new VisualResources(generation, Map.of(MODEL, MESH), Map.of(TEX, new VisualResources.TextureInfo(4,4,opaque),
            MASK, new VisualResources.TextureInfo(maskWidth,4,false)));
    }
    @Test void sizePreservesOriginAndUsesParentMatrixExactlyOnce() {
        var renderer = new MeshGeometryRenderer(failMessage -> fail(failMessage));
        renderer.begin(resources(1,true,4));
        renderer.add(primitive(null), new Matrix4f().translate(10,20,30).rotateY((float)Math.PI/2), 1,.7f,0);
        var batch = renderer.finish(List.of()).get(0); var v = batch.vertices().get(0);
        assertEquals(9, v.x(),1e-5); assertEquals(20.5, v.y(),1e-5); assertEquals(29.5,v.z(),1e-5);
        assertEquals(.7f,v.red()); assertEquals(1,batch.alpha()); assertTrue(batch.depthWrite()); assertFalse(batch.blend()); assertTrue(batch.cull());
    }
    @Test void transparentInstancesAndTheirTrianglesAreDepthSortedWithoutReorderingLegacy() {
        var renderer = new MeshGeometryRenderer(failMessage -> fail(failMessage)); renderer.begin(resources(1,true,4));
        renderer.add(primitive(mask(MaterialState.MaskMode.LINEAR)), new Matrix4f().translate(0,0,5), .8f,1,1);
        renderer.add(primitive(mask(MaterialState.MaskMode.LINEAR)), new Matrix4f().translate(0,0,-5), .3f,1,2);
        renderer.add(primitive(null), new Matrix4f(), 1,1,0);
        var legacy = new DrawBatch(DrawBatch.Topology.QUADS, List.of(), TEX,true,false,true,true,true,1,1,1,1);
        var result = renderer.finish(List.of(legacy));
        assertSame(legacy,result.get(0)); assertTrue(result.get(1).depthWrite());
        var far = result.get(2); var near = result.get(3);
        assertEquals(.3f,far.alpha()); assertEquals(.8f,near.alpha());
        assertEquals(-8,far.vertices().get(0).z()); assertEquals(-6,far.vertices().get(3).z());
        assertEquals(.5f,((MaterialState.Mesh)far.material()).mask().offsetU());
        assertEquals(.25f,((MaterialState.Mesh)near.material()).mask().offsetU());
        assertTrue(far.blend()); assertFalse(far.depthWrite()); assertTrue(far.depthTest());
    }
    @Test void stepOnlyWritesDepthWhenTextureAndInheritedAlphaAreOpaque() {
        var renderer = new MeshGeometryRenderer(failMessage -> fail(failMessage)); renderer.begin(resources(1,true,4));
        renderer.add(primitive(mask(MaterialState.MaskMode.STEP)),new Matrix4f(),1,1,0);
        renderer.add(primitive(mask(MaterialState.MaskMode.STEP)),new Matrix4f(),.5f,1,0);
        var batches = renderer.finish(List.of()); assertTrue(batches.get(0).depthWrite()); assertFalse(batches.get(1).depthWrite());
        renderer.begin(resources(2,false,4)); renderer.add(primitive(null),new Matrix4f(),1,1,0);
        assertFalse(renderer.finish(List.of()).get(0).depthWrite());
        renderer.begin(resources(2,false,4)); renderer.add(primitive(null),new Matrix4f(),0,1,0);
        assertTrue(renderer.finish(List.of()).isEmpty());
    }
    @Test void malformedResourcesSkipOnlyMeshAndWarnOncePerGeneration() {
        var warnings = new ArrayList<String>(); var renderer = new MeshGeometryRenderer(warnings::add);
        for (int i=0;i<3;i++) {
            renderer.begin(resources(1,true,2)); renderer.add(primitive(mask(MaterialState.MaskMode.LINEAR)),new Matrix4f(),1,1,0);
            renderer.add(primitive(null),new Matrix4f(),1,1,0); assertEquals(1,renderer.finish(List.of()).size());
        }
        assertEquals(1,warnings.size());
        renderer.begin(resources(2,true,4)); renderer.add(primitive(mask(MaterialState.MaskMode.LINEAR)),new Matrix4f(),1,1,0);
        assertEquals(1,renderer.finish(List.of()).size());
        renderer.begin(VisualResources.EMPTY); renderer.add(primitive(null),new Matrix4f(),1,1,0);
        assertTrue(renderer.finish(List.of()).isEmpty());
    }
}
