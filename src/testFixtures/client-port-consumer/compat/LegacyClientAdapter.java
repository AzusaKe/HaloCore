package compat;

import java.util.*;
import network.azusake.halo.config.HaloConfig;
import network.azusake.halo.core.Identifier;
import network.azusake.halo.core.Vec3d;
import network.azusake.halo.core.render.*;
import network.azusake.halo.core.runtime.*;

/** Compiled against the frozen interface, then executed with only the newly built core jar. */
public final class LegacyClientAdapter implements ClientPort {
    private final ClientRuntime client = new ClientRuntime(() -> 1000L);
    public void definitions(DefinitionSnapshot v) { client.definitions(v); }
    public void setConfig(HaloConfig v) { client.setConfig(v); }
    public void attach(UUID u, Identifier d, boolean s) { client.attach(u,d,s); }
    public void hide(UUID u, Identifier d) { client.hide(u,d); }
    public void replace(Map<UUID,Identifier> v) { client.replace(v); }
    public void unload(UUID u) { client.unload(u); }
    public void died(UUID u, boolean p) { client.died(u,p); }
    public void teleport(UUID u) { client.teleport(u); }
    public void clear() { client.clear(); }
    public Map<UUID,Identifier> assignments() { return client.assignments(); }
    public List<DrawBatch> render(FrameScene v) { return client.render(v); }
    public Map<UUID,BodyPose> bodyPoses() { return client.bodyPoses(); }
    public static void main(String[] args) {
        ClientPort adapter = new LegacyClientAdapter();
        adapter.definitions(new DefinitionResources().snapshot());
        adapter.setConfig(new HaloConfig());
        UUID wearer = new UUID(0,1); Identifier id = new Identifier("halo:test");
        adapter.attach(wearer,id,false);
        var frame = new FrameScene(1,1000,1_000_000_000,
            new FrameScene.CameraSample(new Vec3d(0,0,0),new Vec3d(0,1,0),new Vec3d(1,0,0)),
            Map.of(),new float[]{1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1},p -> 1,t -> true);
        if (!adapter.renderFrame(frame).legacyBatches().isEmpty() || !adapter.assignments().get(wearer).equals(id))
            throw new AssertionError("Legacy render/ownership contract changed");
        adapter.teleport(wearer); adapter.died(wearer,true); adapter.clear();
        if (!adapter.assignments().isEmpty()) throw new AssertionError("Legacy clear failed");
        var disk=new HashMap<UUID,Identifier>();var events=new ArrayList<String>();
        var server=new ServerRuntime(new ServerRuntime.OwnershipStore(){
            public Identifier get(UUID u){return disk.get(u);} public void set(UUID u,Identifier d){disk.put(u,d);} public void remove(UUID u){disk.remove(u);}
        },new ServerRuntime.Updates(){
            public void attach(UUID u,Identifier d){events.add("attach");} public void remove(UUID u,Identifier d){events.add("remove");}
        });
        server.show(wearer,id);if(!id.equals(server.get(wearer))||!server.hide(wearer)||!events.equals(List.of("attach","remove")))
            throw new AssertionError("Legacy ServerRuntime ABI/behavior changed");
        System.out.println("Core 2.1.2 ClientPort adapter linked and ran against the current core jar.");
    }
}
