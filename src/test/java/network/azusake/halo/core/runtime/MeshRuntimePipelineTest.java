package network.azusake.halo.core.runtime;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import network.azusake.halo.api.v2.*;
import network.azusake.halo.core.*;
import network.azusake.halo.core.render.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MeshRuntimePipelineTest {
    private static final Identifier ID = new Identifier("halo:test"), MODEL = new Identifier("halo:mesh.obj"),
        TEX = new Identifier("halo:mesh.png"), MASK = new Identifier("halo:mask.png");
    private static final UUID ENTITY = new UUID(0,1);
    private static final String MESH = """
        {"type":"mesh","model":"halo:mesh.obj","texture":"halo:mesh.png","size":[1,1,0],
         "material":{"effects":[{"type":"alpha_mask","texture":"halo:mask.png",
         "uv_offset":{"u":[{"function":"linear","speed":0.25}]}}]}}
        """;
    private static final TriangleMesh GEOMETRY = ObjMeshLoader.parse(MODEL,
        "v 1 2 0\nv 3 2 0\nv 1 6 0\nvt 0 0\nvt 1 0\nvt 0 1\nf 1/1 2/2 3/3");
    private static final VisualResources ASSETS = new VisualResources(1, Map.of(MODEL,GEOMETRY),
        Map.of(TEX,new VisualResources.TextureInfo(4,4,true), MASK,new VisualResources.TextureInfo(4,4,true)));
    private final AtomicLong clock = new AtomicLong(10_000);

    private ClientRuntime client(String extra, String groups) {
        var definitions = new DefinitionResources();
        var problems = definitions.reload(1, List.of(new ResourceInput(ID,"pack",
            "{\"id\":\"halo:test\",\"positioning\":{\"offset\":[0,0.5,0],\"scale\":1}," + extra + "\"layers\":[" + groups + "]}")));
        assertTrue(problems.isEmpty(), problems.toString());
        var client = new ClientRuntime(clock::get); client.definitions(definitions.snapshot()); client.attach(ENTITY,ID,false);
        return client;
    }
    private FrameScene frame(double worldX, VisualResources assets, boolean loaded, boolean sleeping, boolean invisible, long world) {
        var anchor = new AnchorPose(new AnchorVec3(worldX,65.6,0),new AnchorRotation(0,0,0,1));
        var entity = new FrameScene.EntitySample(ENTITY,1,new Vec3d(worldX,64,0),true,sleeping,invisible,anchor,anchor);
        return new FrameScene(world,clock.get(),clock.get()*1_000_000,
            new FrameScene.CameraSample(new Vec3d(worldX,64,3),new Vec3d(0,1,0),new Vec3d(1,0,0)),
            loaded ? Map.of(ENTITY,entity) : Map.of(), new float[]{1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1},
            pos -> .4f, id -> true, assets);
    }
    private FrameScene frame(VisualResources assets) { return frame(0,assets,true,false,false,1); }
    private static String group(String mesh) { return "{\"primitive\":" + mesh + "}"; }
    private static MaterialState.AlphaMask mask(DrawBatch batch) { return ((MaterialState.Mesh)batch.material()).mask(); }

    @Test void hierarchyAnimationAndLightMultiplyExactlyOnceAndLargeCoordinatesRemainStable() {
        String extra = "\"animation\":{\"alpha\":[{\"function\":\"linear\",\"start\":0.5,\"speed\":0}],"
            + "\"glow\":[{\"function\":\"linear\",\"start\":0.5,\"speed\":0}]},";
        String groups = "{\"position\":[1,2,3],\"rotation\":[20,30,40],\"scale\":2,"
            + "\"animation\":{\"alpha\":[{\"function\":\"linear\",\"start\":0.8,\"speed\":0}]},"
            + "\"children\":[{\"position\":[0.1,0.2,0.3],\"animation\":{"
            + "\"alpha\":[{\"function\":\"linear\",\"start\":0.25,\"speed\":0}],"
            + "\"glow\":[{\"function\":\"linear\",\"start\":0.5,\"speed\":0}]},\"primitive\":" + MESH + "}]}";
        var near = client(extra,groups); var far = client(extra,groups);
        clock.addAndGet(500);
        var a = near.render(frame(ASSETS)).get(0);
        var b = far.render(frame(29_000_000,ASSETS,true,false,false,1)).get(0);
        assertEquals(.1f,a.alpha(),1e-6); assertEquals(.25f,a.vertices().get(0).red(),1e-6);
        assertEquals(.125f,mask(a).offsetU());
        for (int i=0;i<3;i++) {
            assertEquals(a.vertices().get(i).x(),b.vertices().get(i).x(),1e-5);
            assertEquals(a.vertices().get(i).y(),b.vertices().get(i).y(),1e-5);
            assertEquals(a.vertices().get(i).z(),b.vertices().get(i).z(),1e-5);
        }
        assertEquals(List.of(a),near.render(frame(ASSETS)));
        var ambient = client("", "{\"glowing\":false,\"primitive\":"+ MESH +"}");
        assertEquals(.4f,ambient.render(frame(ASSETS)).get(0).vertices().get(0).red());
    }

    @Test void startupAndShutdownFreezeMaskAtExistingIdlePhase() {
        String extra = """
            "startup":{"segments":[{"duration":1,"offset":{"from":[0,0,0]}}]},
            "shutdown":{"segments":[{"duration":1,"offset":{"to":[0,0,0]}}]},
            """;
        var client = client(extra,group(MESH));
        client.clear(); client.attach(ENTITY,ID,true);
        clock.set(10_500); assertEquals(0,mask(client.render(frame(ASSETS)).get(0)).offsetU());
        clock.set(11_500); assertEquals(.125f,mask(client.render(frame(ASSETS)).get(0)).offsetU());
        client.hide(ENTITY,ID);
        clock.set(11_750); assertEquals(.125f,mask(client.render(frame(ASSETS)).get(0)).offsetU());
        clock.set(12_600); assertTrue(client.render(frame(ASSETS)).isEmpty());
        assertNull(client.getInstance(ENTITY));
    }

    @Test void missingAssetsAndStateChangesPreserveOwnershipAndDoNotHideLegacySiblings() {
        String legacy = "{\"type\":\"billboard\",\"texture\":\"halo:old.png\",\"size\":[1,1]}";
        var client = client("\"hide_on_sleep\":true,",group(MESH)+","+group(legacy));
        var instance = client.getInstance(ENTITY);
        var missing = client.render(frame(VisualResources.EMPTY));
        assertEquals(1,missing.size()); assertEquals(MaterialState.LEGACY,missing.get(0).material());
        assertEquals(2,client.render(frame(ASSETS)).size()); assertSame(instance,client.getInstance(ENTITY));
        assertTrue(client.render(frame(0,ASSETS,true,true,false,1)).isEmpty());
        assertEquals(2,client.render(frame(ASSETS)).size());
        assertTrue(client.render(frame(0,ASSETS,true,false,true,1)).isEmpty());
        assertEquals(2,client.render(frame(ASSETS)).size());
        assertTrue(client.render(frame(0,ASSETS,false,false,false,1)).isEmpty());
        assertEquals(ID,client.assignments().get(ENTITY));
        assertEquals(2,client.render(frame(10_000,ASSETS,true,false,false,2)).size());
        assertEquals(ID,client.assignments().get(ENTITY));
    }

    @Test void coincidentHaloAndHeadOriginsRemainFiniteInEveryOrientationMode() {
        for (String mode : List.of("locked", "free", "sync")) {
            var definitions = new DefinitionResources();
            var errors = definitions.reload(1,List.of(new ResourceInput(ID,"pack",
                "{\"id\":\"halo:test\",\"orientation_mode\":\"" + mode + "\",\"layers\":[" + group(MESH)
                + ",{\"primitive\":{\"type\":\"billboard\",\"texture\":\"halo:old.png\",\"size\":[1,1]}}]}")));
            assertTrue(errors.isEmpty());
            var runtime = new ClientRuntime(clock::get); runtime.definitions(definitions.snapshot()); runtime.attach(ENTITY,ID,false);
            for (int i=0;i<3;i++) {
                clock.addAndGet(16);
                var draws = runtime.render(frame(ASSETS));
                assertEquals(2,draws.size(),mode);
                for (var batch : draws) for (var vertex : batch.vertices()) {
                    assertTrue(Float.isFinite(vertex.x()) && Float.isFinite(vertex.y()) && Float.isFinite(vertex.z()),mode);
                }
            }
        }
    }
}
