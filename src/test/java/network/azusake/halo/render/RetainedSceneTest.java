package network.azusake.halo.render;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import network.azusake.halo.api.v2.*;
import network.azusake.halo.core.*;
import network.azusake.halo.core.render.*;
import network.azusake.halo.core.runtime.*;
import org.joml.Matrix4f;
import org.joml.Matrix3f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RetainedSceneTest {
    @Test void tinyMeshNormalTransformsKeepTangentOrthogonalityAndInverseScale() {
        for (double size : new double[]{1e-3,1e-8,1e-16}) for (boolean mirror : new boolean[]{false,true}) {
            SceneDraw draw=scaledMesh(size,2*size,3*size,mirror);
            assertInverseNormal(draw);
            var normal=new Matrix3f().set(draw.normalToWorld()).transform(new Vector3f(1,-1,1)).normalize();
            var affine=new Matrix4f().set(draw.localToWorld());
            var tangentU=affine.transformDirection(new Vector3f(1,1,0)).normalize();
            var tangentV=affine.transformDirection(new Vector3f(0,1,1)).normalize();
            assertEquals(0,normal.dot(tangentU),2e-5f);
            assertEquals(0,normal.dot(tangentV),2e-5f);
        }
    }
    @Test void tinyPrimitiveNormalsKeepInverseScaleAndExplicitFacingDirection() {
        for (float size : new float[]{1e-3f,1e-8f,-1e-3f}) {
            var client=PrimitiveBackendTest.client(PrimitiveBackendTest.definitions(false,size),new AtomicLong(20000));
            var output=client.renderScene(PrimitiveBackendTest.frame(20016,0,id->true,PrimitiveRenderMode.CACHED,true));
            assertEquals(6,output.draws().size());
            for(int i=0;i<output.draws().size();i++) if(i!=1) assertInverseNormal(output.draws().get(i));
            // face_camera supplies a unit facing normal, independent of inverse scale.
            var facing=output.draws().get(1);
            var n=new Matrix3f().set(facing.normalToWorld()).transform(new Vector3f(0,-1,0));
            var axis=new Matrix4f().set(facing.localToWorld()).transformDirection(new Vector3f(0,-1,0)).normalize();
            assertEquals(1,n.length(),2e-5f);assertEquals(1,n.dot(axis),2e-5f);
        }
    }
    @Test void zeroScaleRemainsASingularCommandForBackendRejection() {
        SceneDraw draw=scaledMesh(0,2e-4,3e-4,false);
        assertEquals(0,new Matrix3f(new Matrix4f().set(draw.localToWorld())).determinant());
        for(float value:draw.normalToWorld()) assertTrue(Float.isFinite(value));
    }
    private static void assertInverseNormal(SceneDraw draw) {
        // The dual map must preserve normal/tangent pairing: A^T N = I.
        var paired=new Matrix3f(new Matrix4f().set(draw.localToWorld())).transpose()
            .mul(new Matrix3f().set(draw.normalToWorld())).get(new float[9]);
        for(int i=0;i<9;i++) assertEquals(i%4==0?1:0,paired[i],2e-5f);
    }
    private static SceneDraw scaledMesh(double x,double y,double z,boolean mirror) {
        var id=new Identifier("halo:test");var model=new Identifier("halo:mesh.obj");var tex=new Identifier("halo:mesh.png");
        var defs=new DefinitionResources();
        String json="""
            {"id":"halo:test","orientation_mode":"locked","layers":[{"rotation":[17,32,11],"scale":%s,
             "primitives":[{"type":"mesh","model":"halo:mesh.obj","texture":"halo:mesh.png","size":[%s,%s,%s]}]}]}
            """.formatted(mirror?-1:1,x,y,z);
        assertTrue(defs.reload(1,List.of(new ResourceInput(id,"test",json))).isEmpty());
        var mesh=ObjMeshLoader.parse(model,"v 0 0 0\nv 1 1 0\nv 0 1 1\nvt 0 0\nvt 1 0\nvt 0 1\nf 1/1 2/2 3/3");
        var assets=new VisualResources(2,Map.of(model,mesh),Map.of(tex,new VisualResources.TextureInfo(4,4,true)));
        var frame=PrimitiveBackendTest.frame(20016,0,t->true,PrimitiveRenderMode.CACHED,true);
        var input=new FrameScene(frame.worldToken(),frame.timeMillis(),frame.frameNanos(),frame.camera(),frame.entities(),
            frame.rootTransform(),frame.lights(),frame.textures(),assets,frame.lightmaps(),frame.primitiveMode());
        var output=PrimitiveBackendTest.client(defs.snapshot(),new AtomicLong(20000)).renderScene(input);
        assertEquals(1,output.draws().size());return output.draws().get(0);
    }
    @Test void absoluteOriginsKeepSubBlockPrecisionAndGeometryIdentity() {
        var definitions=PrimitiveBackendTest.definitions(false,1);
        var near=PrimitiveBackendTest.client(definitions,new AtomicLong(20000));
        var far=PrimitiveBackendTest.client(definitions,new AtomicLong(20000));
        double delta=29_000_000.123456;
        var a=near.renderScene(PrimitiveBackendTest.frame(20016,0,id->true,PrimitiveRenderMode.COMPATIBILITY,true));
        var b=far.renderScene(PrimitiveBackendTest.frame(20016,delta,id->true,PrimitiveRenderMode.COMPATIBILITY,true));
        assertEquals(6,a.draws().size()); assertEquals(a.draws().size(),b.draws().size());
        for(int i=0;i<a.draws().size();i++) {
            var x=a.draws().get(i);var y=b.draws().get(i);
            assertEquals(delta,y.origin().x-x.origin().x,1e-8);
            assertArrayEquals(x.localToWorld(),y.localToWorld(),2e-6f);
            assertSame(x.geometry(),y.geometry()); assertFalse(x.glowing());assertEquals(1,x.brightness());
            float before=x.transform(0);x.localToWorld()[0]=999;assertEquals(before,x.transform(0));
        }
        var next=near.renderScene(PrimitiveBackendTest.frame(20032,0,id->true,PrimitiveRenderMode.CACHED,true));
        for(int i=0;i<a.draws().size();i++)assertEquals(a.draws().get(i).key(),next.draws().get(i).key());
        near.definitions(PrimitiveBackendTest.definitions(false,1));
        var reload=near.renderScene(PrimitiveBackendTest.frame(20048,0,id->true,PrimitiveRenderMode.CACHED,true));
        assertNotEquals(a.draws().get(0).key(),reload.draws().get(0).key());
    }
    @Test void backendSwitchPreservesPoseAndSameSlotSurvivesMissingSibling() {
        var defs=PrimitiveBackendTest.definitions(true,-1);
        var client=PrimitiveBackendTest.client(defs,new AtomicLong(20000));
        var first=client.renderScene(PrimitiveBackendTest.frame(20016,0,id->true,PrimitiveRenderMode.CACHED,true));
        var poses=client.bodyPoses();
        var raster=client.renderFrame(PrimitiveBackendTest.frame(20016,0,id->true,PrimitiveRenderMode.CACHED,true));
        assertEquals(poses,client.bodyPoses());assertEquals(first.draws().size(),raster.primitiveDraws().size());
        for(int i=0;i<first.draws().size();i++) {
            assertTrue(first.draws().get(i).glowing());
            assertEquals(raster.primitiveDraws().get(i).brightness(),first.draws().get(i).brightness());
            assertSame(raster.primitiveDraws().get(i).geometry().mesh(),first.draws().get(i).geometry());
        }
        assertEquals(1,first.draws().get(3).key().part());
    }
    @Test void meshKeepsAuthoredGeometryAndMaskWithoutViewSorting() {
        var id=new Identifier("halo:test");var model=new Identifier("halo:mesh.obj");var tex=new Identifier("halo:mesh.png");
        var mask=new Identifier("halo:mask.png");var defs=new DefinitionResources();
        assertTrue(defs.reload(1,List.of(new ResourceInput(id,"test", """
            {"id":"halo:test","layers":[{"glowing":false,"primitives":[
            {"type":"mesh","model":"halo:mesh.obj","texture":"halo:mesh.png","size":[1,1,0],
             "material":{"effects":[{"type":"alpha_mask","texture":"halo:mask.png"}]}}]}]}
            """))).isEmpty());
        var mesh=ObjMeshLoader.parse(model,"v 1 2 0\nv 3 2 0\nv 1 6 0\nvt 0 0\nvt 1 0\nvt 0 1\nf 1/1 2/2 3/3");
        var assets=new VisualResources(2,Map.of(model,mesh),Map.of(tex,new VisualResources.TextureInfo(4,4,true),mask,new VisualResources.TextureInfo(7,3,true)));
        var input=PrimitiveBackendTest.frame(20016,0,t->true,PrimitiveRenderMode.COMPATIBILITY,true);
        input=new FrameScene(input.worldToken(),input.timeMillis(),input.frameNanos(),input.camera(),input.entities(),
            input.rootTransform(),input.lights(),input.textures(),assets,input.lightmaps(),input.primitiveMode());
        var output=PrimitiveBackendTest.client(defs.snapshot(),new AtomicLong(20000)).renderScene(input);
        assertEquals(1,output.draws().size());var draw=output.draws().get(0);
        assertSame(mesh,draw.geometry());assertEquals(SceneDraw.Kind.MESH,draw.kind());
        assertEquals(mask,((MaterialState.Mesh)draw.material()).mask().texture());assertEquals(0.5f,draw.transform(0));
    }
}
