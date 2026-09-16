package network.azusake.halo.render;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import network.azusake.halo.api.v2.*;
import network.azusake.halo.core.*;
import network.azusake.halo.core.render.*;
import network.azusake.halo.core.runtime.*;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PrimitiveBackendTest {
    @Test void invalidLegacySizeCannotAbortPreparationOfOtherDefinitions() {
        var resources = new DefinitionResources();
        String json = "{\"id\":\"halo:test\",\"layers\":[{\"primitive\":{\"type\":\"billboard\","
            + "\"texture\":\"halo:outer.png\",\"size\":[\"NaN\",1]}}]}";
        assertTrue(resources.reload(1,List.of(new ResourceInput(ID,"test",json))).isEmpty());
        assertDoesNotThrow(() -> resources.snapshot().primitiveGeometries());
        var client=client(resources.snapshot(),new AtomicLong(20_000));
        assertTrue(client.renderFrame(frame(20_016,0,id -> true,PrimitiveRenderMode.CACHED,true)).primitiveDraws().isEmpty());
        client.definitions(definitions(true,1));
        assertFalse(client.renderFrame(frame(20_032,0,id -> true,PrimitiveRenderMode.CACHED,true)).primitiveDraws().isEmpty());
    }
    static final Identifier ID = new Identifier("halo:test");
    static final UUID WEARER = new UUID(0, 1);
    static final String PRIMITIVES = """
        {"type":"billboard","texture":"halo:outer.png","size":[1.3,0.7]},
        {"type":"billboard","texture":"halo:outer.png","size":[1.7,0.8],"face_camera":true},
        {"type":"ring","texture":"halo:outer.png","size":[0.8,0.2],"segments":7},
        {"type":"ring","outer_texture":"halo:outer.png","inner_texture":"halo:inner.png","size":[0.6,0.3],"segments":32}
        """;
    static DefinitionSnapshot definitions(boolean glow, float scale) {
        var resources = new DefinitionResources();
        String json = "{\"id\":\"halo:test\",\"orientation_mode\":\"locked\",\"layers\":["
            + "{\"id\":\"root\",\"glowing\":" + glow + ",\"rotation\":[17,32,11],\"position\":[0.2,0.4,-0.1],"
            + "\"scale\":" + scale + ",\"animation\":{\"alpha\":[{\"function\":\"linear\",\"start\":0.63,\"speed\":0}],"
            + "\"glow\":[{\"function\":\"linear\",\"start\":0.737,\"speed\":0}]},\"primitives\":[" + PRIMITIVES + "]}]}";
        assertTrue(resources.reload(1, List.of(new ResourceInput(ID, "test", json))).isEmpty());
        return resources.snapshot();
    }
    static ClientRuntime client(DefinitionSnapshot definitions, AtomicLong time) {
        var client = new ClientRuntime(time::get);
        client.definitions(definitions); client.attach(WEARER, ID, false); return client;
    }
    static FrameScene frame(long time, double x, FrameScene.TextureLookup textures, PrimitiveRenderMode mode, boolean light) {
        var pose = new AnchorPose(new AnchorVec3(x, 65.6, 0), new AnchorRotation(0,0,0,1));
        var entity = new FrameScene.EntitySample(WEARER, 1, new Vec3d(x,64,0), true,false,false,pose,pose);
        return new FrameScene(1,time,time*1_000_000,
            new FrameScene.CameraSample(new Vec3d(x+2,64,4), new Vec3d(0,1,0),new Vec3d(1,0,0)),
            Map.of(WEARER,entity),new Matrix4f().rotateY(.2f).scale(1.1f,.8f,1.3f).get(new float[16]),
            p -> .47f,textures,VisualResources.EMPTY,p -> light ? new LightSample(3,11) : LightSample.UNAVAILABLE,mode);
    }

    @Test void compatibilityAndCachedMatchFrozen230RendererAcrossMaterialsTransformsAndLargeCoordinates() {
        for (boolean glow : new boolean[]{false,true}) for (float scale : new float[]{1,-1,0})
            for (boolean light : new boolean[]{false,true}) for (double x : new double[]{0,29_000_000})
                for (int missing = 0; missing < 3; missing++) {
                    int unavailable = missing;
                    FrameScene.TextureLookup textures = id -> unavailable == 0 || unavailable == 1 && !id.toString().contains("inner");
                    var time = new AtomicLong(20_000);
                    var defs = definitions(glow, scale);
                    var baselineClient = client(defs,time);
                    var baseline = new BaselineSceneRenderer(baselineClient);
                    var compatibility = client(defs,time);
                    var cached = client(defs,time);
                    for (int sample = 0; sample < 3; sample++) {
                        time.addAndGet(16);
                        var scene = frame(time.get(),x,textures,PrimitiveRenderMode.COMPATIBILITY,light);
                        var expected = baseline.renderHalos(scene).legacyBatches();
                        var actual = compatibility.renderFrame(scene);
                        var indexed = cached.renderFrame(frame(time.get(),x,textures,PrimitiveRenderMode.CACHED,light));
                        assertFalse(expected.isEmpty()); assertTrue(actual.primitiveDraws().isEmpty());
                        assertTrue(indexed.legacyBatches().isEmpty()); assertFalse(indexed.primitiveDraws().isEmpty());
                        assertBatches(expected, actual.legacyBatches());
                        assertBatches(expected, indexed.expandedBatches(VisualResources.EMPTY));
                        assertEquals(baseline.bodyPoses(),compatibility.bodyPoses());
                        assertEquals(compatibility.bodyPoses(),cached.bodyPoses());
                    }
                }
    }

    @Test void geometryIsSharedAcrossMaterialsAndCommandsOwnTheirTransforms() {
        var cache = new PrimitiveGeometries();
        var a = new network.azusake.halo.shape.BillboardPrimitive(new Identifier("halo:a.png"),new org.joml.Vector2f(2,1));
        var b = new network.azusake.halo.shape.BillboardPrimitive(new Identifier("halo:b.png"),new org.joml.Vector2f(2,1));
        assertSame(cache.billboard(a),cache.billboard(b));
        assertThrows(UnsupportedOperationException.class,() -> cache.snapshot().clear());
        var frame = client(definitions(true,1),new AtomicLong(20_000))
            .renderFrame(frame(20_016,0,id -> true,PrimitiveRenderMode.CACHED,true));
        PrimitiveDraw command = frame.primitiveDraws().get(0);
        DrawBatch expected = command.expand();
        command.localToView()[12] = 999;
        command.normalToView()[0] = 999;
        assertEquals(expected,command.expand());
        assertThrows(IllegalArgumentException.class,() -> new FrameOutput(0,List.of(expected),List.of(),List.of(command)));
    }

    @Test void geometryDependenciesDeduplicateAndTextureFactsAreSampledOncePerInvocation() {
        var definitions = definitions(false,1);
        assertEquals(Set.of(new Identifier("halo:outer.png"),new Identifier("halo:inner.png")),definitions.legacyTextures());
        assertTrue(definitions.assets().models().isEmpty()); assertTrue(definitions.assets().textures().isEmpty());
        assertEquals(7,definitions.primitiveGeometries().size());
        var calls = new HashMap<Identifier,AtomicInteger>();
        FrameScene.TextureLookup lookup = id -> { calls.computeIfAbsent(id,k -> new AtomicInteger()).incrementAndGet(); return true; };
        var client = client(definitions,new AtomicLong(20_000));
        var first = client.renderFrame(frame(20_016,0,lookup,PrimitiveRenderMode.CACHED,true));
        calls.values().forEach(count -> assertEquals(1,count.get()));
        var second = client.renderFrame(frame(20_032,0,lookup,PrimitiveRenderMode.CACHED,true));
        calls.values().forEach(count -> assertEquals(2,count.get()));
        for (int i=0;i<first.primitiveDraws().size();i++)
            assertSame(first.primitiveDraws().get(i).geometry(),second.primitiveDraws().get(i).geometry());
        client.definitions(definitions(false,1));
        var reloaded = client.renderFrame(frame(20_048,0,lookup,PrimitiveRenderMode.CACHED,true));
        assertNotSame(first.primitiveDraws().get(0).geometry(),reloaded.primitiveDraws().get(0).geometry());
    }

    @Test void previewModeSwitchDoesNotAdvanceAppearanceOrResetMotionAndClosedViewsStayEmpty() {
        var time = new AtomicLong(20_000);
        var client = client(definitions(false,1),time);
        var world = frame(20_016,0,id -> true,PrimitiveRenderMode.COMPATIBILITY,true);
        client.renderFrame(world);
        var poses = client.bodyPoses();
        var head = world.entities().get(WEARER).anchor();
        var session = client.openPreview(PreviewOptions.PHYSICS);
        var plain = new PreviewFrame(WEARER,1,head,world.camera(),world.rootTransform(),20_016,20_016_000_000L,
            LightSample.FULL_BRIGHT,id -> true,VisualResources.EMPTY);
        var cached = new PreviewFrame(WEARER,1,head,world.camera(),world.rootTransform(),20_016,20_016_000_000L,
            LightSample.FULL_BRIGHT,id -> true,VisualResources.EMPTY,PreviewFrame.Projection.ORTHOGRAPHIC,PrimitiveRenderMode.CACHED);
        var a = session.render(plain);
        assertBatches(a.legacyBatches(),session.render(cached).expandedBatches(VisualResources.EMPTY));
        assertBatches(a.legacyBatches(),session.render(plain).legacyBatches());
        assertEquals(poses,client.bodyPoses()); assertEquals(ID,client.assignments().get(WEARER));
        session.close(); assertTrue(session.render(cached).primitiveDraws().isEmpty());
    }

    static void assertBatches(List<DrawBatch> expected, List<DrawBatch> actual) {
        assertEquals(expected.size(),actual.size());
        for (int i=0;i<expected.size();i++) {
            var a=expected.get(i); var b=actual.get(i);
            assertEquals(state(a),state(b),"batch "+i); assertEquals(a.vertices().size(),b.vertices().size());
            for (int j=0;j<a.vertices().size();j++) {
                var av=a.vertices().get(j); var bv=b.vertices().get(j);
                String at="batch "+i+" vertex "+j;
                assertEquals(av.x(),bv.x(),2e-6,at); assertEquals(av.y(),bv.y(),2e-6,at); assertEquals(av.z(),bv.z(),2e-6,at);
                assertEquals(av.u(),bv.u(),at); assertEquals(av.v(),bv.v(),at);
                assertEquals(av.red(),bv.red(),at); assertEquals(av.green(),bv.green(),at);
                assertEquals(av.blue(),bv.blue(),at); assertEquals(av.alpha(),bv.alpha(),at);
                if (a.directionalLighting()) {
                    assertEquals(av.normalX(),bv.normalX(),2e-6,at); assertEquals(av.normalY(),bv.normalY(),2e-6,at);
                    assertEquals(av.normalZ(),bv.normalZ(),2e-6,at);
                }
            }
        }
    }
    private static DrawBatch state(DrawBatch a) {
        return new DrawBatch(a.topology(),List.of(),a.texture(),a.textured(),a.cull(),a.blend(),a.depthTest(),a.depthWrite(),
            a.red(),a.green(),a.blue(),a.alpha(),a.material(),a.light(),a.directionalLighting());
    }
}
