package network.azusake.halo.core.runtime;

import com.google.gson.JsonParser;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.nio.charset.StandardCharsets;
import network.azusake.halo.api.v2.*;
import network.azusake.halo.core.*;
import network.azusake.halo.core.render.*;
import network.azusake.halo.data.*;
import network.azusake.halo.json.HaloDefinitionDeserializer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RuntimePipelineTest {
    private final UUID entity=UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final AtomicLong clock=new AtomicLong(10_000);
    private final Identifier id=new Identifier("halo:test");
    private HaloDefinition definition() {
        return parse("""
            {"id":"halo:test","hide_on_sleep":true,
             "positioning":{"offset":[0,0.5,0],"scale":1},
             "layers":[{"id":"ring","primitive":{"type":"billboard","texture":"halo:test.png","size":[1,1]}}]}
            """);
    }
    private static HaloDefinition parse(String json) {
        return new HaloDefinitionDeserializer().deserialize(JsonParser.parseString(json),HaloDefinition.class,null);
    }
    private FrameScene scene(double x,boolean sleeping,boolean loaded,long world) {
        var pos=new Vec3d(x,64,0);
        var anchor=new AnchorPose(new AnchorVec3(x,65.6,0),new AnchorRotation(0,0,0,1));
        var sample=new FrameScene.EntitySample(entity,1,pos,true,sleeping,false,anchor,anchor);
        return new FrameScene(world,clock.get(),clock.get()*1_000_000,
            new FrameScene.CameraSample(new Vec3d(x,64,3),new Vec3d(0,1,0),new Vec3d(1,0,0)),
            loaded?Map.of(entity,sample):Map.of(),new float[]{1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1},p->.5f,t->true);
    }
    @Test void inputToGeometryIsDeterministicAndStableAtLargeCoordinates() {
        ClientRuntime near=new ClientRuntime(clock::get),far=new ClientRuntime(clock::get);
        near.definitions(Map.of(id,definition()));far.definitions(Map.of(id,definition()));
        near.attach(entity,id,false);far.attach(entity,id,false);
        var a=near.render(scene(0,false,true,1));var b=far.render(scene(29_000_000,false,true,1));
        assertEquals(1,a.size());assertEquals(4,a.get(0).vertices().size());
        for(int i=0;i<4;i++) {
            var av=a.get(0).vertices().get(i);var bv=b.get(0).vertices().get(i);
            assertEquals(av.x(),bv.x(),1e-5);assertEquals(av.y(),bv.y(),1e-5);assertEquals(av.z(),bv.z(),1e-5);
        }
        assertEquals(a,near.render(scene(0,false,true,1)));
    }
    @Test void sleepUnloadMissingResourcesAndWorldSwitchNeverRevokeOwnership() {
        ClientRuntime client=new ClientRuntime(clock::get);client.attach(entity,id,false);
        assertTrue(client.render(scene(0,false,true,1)).isEmpty());
        assertEquals(id,client.assignments().get(entity));
        client.definitions(Map.of(id,definition()));
        assertFalse(client.render(scene(0,false,true,1)).isEmpty());
        assertTrue(client.render(scene(0,true,true,1)).isEmpty());
        assertFalse(client.render(scene(0,false,true,1)).isEmpty());
        assertTrue(client.render(scene(0,false,false,1)).isEmpty());
        assertFalse(client.render(scene(500,false,true,2)).isEmpty());
        assertEquals(id,client.assignments().get(entity));
    }
    @Test void missingDefinitionWarnsImmediatelyAndRecoversWithoutReattaching() {
        clock.set(0);
        List<Identifier> warnings=new ArrayList<>();
        ClientRuntime client=new ClientRuntime(clock::get,warnings::add);
        client.attach(entity,id,false);
        var instance=client.getInstance(entity);
        assertTrue(client.render(scene(0,false,true,1)).isEmpty());
        assertEquals(List.of(id),warnings);
        assertEquals(id,client.assignments().get(entity));
        assertTrue(instance.isActive());

        client.definitions(Map.of(id,definition()));
        clock.set(30_000);
        assertFalse(client.render(scene(0,false,true,1)).isEmpty());
        assertSame(instance,client.getInstance(entity));
        assertEquals(List.of(id),warnings);

        client.definitions(Map.of());
        assertTrue(client.render(scene(0,false,true,1)).isEmpty());
        assertEquals(List.of(id,id),warnings);
        assertEquals(id,client.assignments().get(entity));
    }
    @Test void missingDefinitionWarningsAreThrottledAcrossFramesAndEntitiesUsingFrameTime() {
        clock.set(0);
        List<Identifier> warnings=new ArrayList<>();
        ClientRuntime client=new ClientRuntime(() -> 99_000,warnings::add);
        UUID other=UUID.fromString("00000000-0000-0000-0000-000000000002");
        Identifier otherId=new Identifier("halo:other_missing");
        client.attach(entity,id,false);
        client.attach(other,otherId,false);
        for(long millis:new long[]{0,1,16,1_000,29_999,30_000}) {
            clock.set(millis);
            FrameScene frame=scene(0,false,true,1);
            var sample=frame.entities().get(entity);
            var second=new FrameScene.EntitySample(other,2,sample.position(),true,false,false,
                sample.anchor(),sample.fallbackAnchor());
            client.render(new FrameScene(frame.worldToken(),frame.timeMillis(),frame.frameNanos(),
                frame.camera(),Map.of(entity,sample,other,second),frame.rootTransform(),frame.lights(),frame.textures()));
            assertEquals(millis<30_000?1:2,warnings.size());
        }
        assertTrue(warnings.stream().allMatch(warning -> warning.equals(id) || warning.equals(otherId)));
        assertEquals(Map.of(entity,id,other,otherId),client.assignments());
    }
    @Test void missingDefinitionWarningsWaitForLoadedEntitiesAndResetWithWorldOrConnection() {
        List<Identifier> warnings=new ArrayList<>();
        ClientRuntime client=new ClientRuntime(clock::get,warnings::add);
        client.attach(entity,id,false);
        client.render(scene(0,false,false,1));
        assertTrue(warnings.isEmpty());
        client.render(scene(0,false,true,1));
        assertEquals(List.of(id),warnings);
        client.unload(entity);
        client.render(scene(0,false,true,1));
        assertEquals(1,warnings.size());
        client.render(scene(0,false,true,2));
        assertEquals(2,warnings.size());
        client.clear();
        client.render(scene(0,false,true,2));
        assertEquals(2,warnings.size());
        client.attach(entity,id,false);
        client.render(scene(0,false,true,2));
        assertEquals(List.of(id,id,id),warnings);
    }
    @Test void serverAndTwoClientsDoNotShareAnimationInstancesOrOwnershipStorage() {
        Map<UUID,Identifier> disk=new HashMap<>();
        ClientRuntime a=new ClientRuntime(clock::get),b=new ClientRuntime(clock::get);
        a.definitions(Map.of(id,definition()));b.definitions(Map.of(id,definition()));
        ServerRuntime server=server(disk,new ServerRuntime.Updates() {
            public void attach(UUID uuid,Identifier def){a.attach(uuid,def,true);b.attach(uuid,def,true);}
            public void remove(UUID uuid,Identifier def){a.hide(uuid,def);b.hide(uuid,def);}
        });
        server.show(entity,id);assertEquals(id,disk.get(entity));assertNotSame(a.getInstance(entity),b.getInstance(entity));
        a.render(scene(0,false,true,1));b.render(scene(0,false,true,1));
        server.hide(entity);assertFalse(disk.containsKey(entity));assertNull(server.get(entity));
        assertNotNull(a.getInstance(entity));assertEquals(HaloTransitionState.ENDING,a.getInstance(entity).getTransitionState());
        assertTrue(a.render(scene(0,false,true,1)).isEmpty());assertTrue(b.render(scene(0,false,true,1)).isEmpty());
        assertNull(a.getInstance(entity));assertNull(b.getInstance(entity));
    }
    @Test void playerDeathRestoresOnceWhileMobDeathRemovesTheDurableRecord() {
        Map<UUID,Identifier> disk=new HashMap<>();List<String> sent=new ArrayList<>();
        ServerRuntime server=server(disk,new ServerRuntime.Updates(){public void attach(UUID u,Identifier d){sent.add("attach");}public void remove(UUID u,Identifier d){sent.add("hide");}});
        server.show(entity,id);server.died(entity,true);assertEquals(id,disk.get(entity));
        server.restore(entity);server.restore(entity);assertEquals(List.of("attach","attach"),sent);
        server.unload(entity);assertEquals(id,disk.get(entity));
        server.died(entity,false);assertFalse(disk.containsKey(entity));assertNull(server.restore(entity));
    }
    @Test void hideDuringStartupUsesTheLastRenderedGroupValuesAndCompletes() throws Exception {
        String json=new String(Objects.requireNonNull(getClass().getResourceAsStream("/definitions/ring_default.json")).readAllBytes(),StandardCharsets.UTF_8);
        HaloDefinition def=parse(json);ClientRuntime client=new ClientRuntime(clock::get);
        client.definitions(Map.of(def.id(),def));client.attach(entity,def.id(),true);
        clock.addAndGet(200);assertFalse(client.render(scene(0,false,true,1)).isEmpty());
        var rendered=client.renderer().readLastRenderState(entity);assertNotNull(rendered);assertFalse(rendered.groups().isEmpty());
        client.hide(entity,def.id());var ending=client.getInstance(entity);
        assertEquals(rendered.groups(),ending.getHideVisuals());
        long start=ending.getTransitionStartTime();clock.addAndGet(10);client.hide(entity,def.id());assertEquals(start,ending.getTransitionStartTime());
        clock.addAndGet(1000);assertTrue(client.render(scene(0,false,true,1)).isEmpty());assertNull(client.getInstance(entity));
    }
    @Test void builtInFixturesGenerateFiniteOrderedGeometryWithoutGameClasses() throws Exception {
        for(String file:List.of("ring_default","ring_test","ring_group_test","ring_glow_test","ring_face_camera_test","ring_alpha_test","hud")) {
            HaloDefinition def=parse(new String(Objects.requireNonNull(getClass().getResourceAsStream("/definitions/"+file+".json")).readAllBytes(),StandardCharsets.UTF_8));
            ClientRuntime client=new ClientRuntime(clock::get);client.definitions(Map.of(def.id(),def));client.attach(entity,def.id(),false);
            var batches=client.render(scene(0,false,true,1));assertFalse(batches.isEmpty(),file);
            for(var batch:batches)for(var vertex:batch.vertices())assertTrue(Float.isFinite(vertex.x())&&Float.isFinite(vertex.y())&&Float.isFinite(vertex.z()),file);
        }
    }
    private ServerRuntime server(Map<UUID,Identifier> disk,ServerRuntime.Updates updates) {
        return new ServerRuntime(new ServerRuntime.OwnershipStore(){public Identifier get(UUID u){return disk.get(u);}public void set(UUID u,Identifier d){disk.put(u,d);}public void remove(UUID u){disk.remove(u);}},updates);
    }
}
