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
    @Test void authoredScalePreservesOffOriginGeometryAndIgnoresSizeBeforeParentTransform() {
        var renderer = new MeshGeometryRenderer(message -> fail(message));
        for (Vec3d size : new Vec3d[]{null, new Vec3d(100,0,7)}) {
            renderer.begin(resources(1,true,4));
            renderer.add(new MeshPrimitive(MODEL,TEX,size,MeshPrimitive.Material.DEFAULT,true,.5),
                new Matrix4f().translate(10,20,30).rotateY((float)Math.PI/2).scale(2),1,1,0);
            var vertices = renderer.finish(List.of()).get(0).vertices();
            // Authored (1,2,-1), local scale .5, then parent scale 2 and rotation/translation.
            assertEquals(9,vertices.get(0).x(),1e-5); assertEquals(22,vertices.get(0).y(),1e-5);
            assertEquals(29,vertices.get(0).z(),1e-5);
            assertEquals(27,vertices.get(1).z(),1e-5); assertEquals(26,vertices.get(2).y(),1e-5);
        }
        renderer.begin(resources(1,true,4));
        renderer.add(new MeshPrimitive(MODEL,TEX,new Vec3d(1,1,2),MeshPrimitive.Material.DEFAULT,false,100),
            new Matrix4f(),1,1,0);
        var fitted = renderer.finish(List.of()).get(0).vertices().get(0);
        assertEquals(.5,fitted.x()); assertEquals(.5,fitted.y()); assertEquals(-1,fitted.z());
    }

    @Test void authoredScaleHandlesPlanarGeometryAwayFromOriginAndZeroScale() {
        var plane = new TriangleMesh(new float[]{1,2,7, 3,2,7, 1,6,7},new float[]{0,0,1,0,0,1},new int[]{0,1,2});
        var assets = new VisualResources(1,Map.of(MODEL,plane),Map.of(TEX,new VisualResources.TextureInfo(4,4,true)));
        var renderer = new MeshGeometryRenderer(message -> fail(message));
        for (double scale : new double[]{1,.5,0}) {
            renderer.begin(assets);
            renderer.add(new MeshPrimitive(MODEL,TEX,null,MeshPrimitive.Material.DEFAULT,true,scale),new Matrix4f(),1,1,0);
            var vertex = renderer.finish(List.of()).get(0).vertices().get(0);
            assertEquals(scale,vertex.x()); assertEquals(2*scale,vertex.y()); assertEquals(7*scale,vertex.z());
        }
    }

    @Test void maskDimensionsAcceptOnlyUniformIntegerMultiplesInBothDirections() {
        var warnings = new ArrayList<String>(); var renderer = new MeshGeometryRenderer(warnings::add);
        int[][] dimensions = {{4,6},{8,12},{12,18},{2,3},{4,12},{8,6},{6,9},{3,2},{5,7}};
        for (int i=0;i<dimensions.length;i++) {
            int[] d = dimensions[i]; boolean valid = i<4;
            for (boolean reverse : new boolean[]{false,true}) {
                var a = new VisualResources.TextureInfo(4,6,true);
                var b = new VisualResources.TextureInfo(d[0],d[1],true);
                renderer.begin(new VisualResources(i*2+(reverse?1:0),Map.of(MODEL,MESH),Map.of(TEX,reverse?b:a,MASK,reverse?a:b)));
                renderer.add(primitive(mask(MaterialState.MaskMode.LINEAR)),new Matrix4f(),.75f,1,2);
                var batches = renderer.finish(List.of());
                assertEquals(valid?1:0,batches.size(),d[0]+"x"+d[1]+" reverse="+reverse);
                if (valid) {
                    var batch = batches.get(0);
                    assertEquals(.75f,batch.alpha()); assertTrue(batch.blend());
                    var state = ((MaterialState.Mesh)batch.material()).mask();
                    assertEquals(.5f,state.offsetU()); assertEquals(.5f,state.alpha(.5f));
                }
            }
        }
        assertEquals(10,warnings.size());
        assertTrue(warnings.get(0).contains("4x12")); assertTrue(warnings.get(0).contains("4x6"));
        // Compare dimensions without overflowing products or assuming power-of-two textures.
        var large = new VisualResources.TextureInfo(Integer.MAX_VALUE,Integer.MAX_VALUE,true);
        assertTrue(large.hasIntegralScaleWith(new VisualResources.TextureInfo(1,1,true)));
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
