package network.azusake.halo.core.runtime;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import network.azusake.halo.core.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StorageAndLifecycleTest {
    @Test void reloadIsAtomicAndRemovingAnOverrideRevealsTheOtherSource() {
        DefinitionCatalog<String> catalog=new DefinitionCatalog<>();Identifier id=new Identifier("halo:test");
        catalog.replace(0,Map.of(id,"server"));Map<Identifier,String> old=catalog.snapshot();
        catalog.replace(1,Map.of(id,"client"));assertEquals("client",catalog.snapshot().get(id));
        catalog.replace(0,Map.of(id,"server2"));assertEquals("client",catalog.snapshot().get(id));
        catalog.replace(1,Map.of());assertEquals("server2",catalog.snapshot().get(id));assertEquals("server",old.get(id));
        assertThrows(UnsupportedOperationException.class,()->old.clear());
    }
    @Test void localRecordsRoundTripAndAreIsolatedByServer() {
        AtomicReference<String> disk=new AtomicReference<>();
        var store=new LocalOwnership.TextStore(){public String read(){return disk.get();}public void write(String text){disk.set(text);}};
        UUID uuid=UUID.randomUUID();Identifier id=new Identifier("halo:test");LocalOwnership first=new LocalOwnership(store);
        first.showHalo("a:25565",uuid,id);first.showHalo("b:25565",uuid,id);first.hideHalo("a:25565",uuid);
        LocalOwnership second=new LocalOwnership(store);assertTrue(second.getHalo("a:25565",uuid).isEmpty());assertEquals(id,second.getHalo("b:25565",uuid).orElseThrow());
        disk.set("malformed JSON");assertDoesNotThrow(()->new LocalOwnership(store).getHalosForServer("a:25565"));
    }
    @Test void discontinuityGraceAndUnloadAreDrivenByExplicitInputs() {
        TeleportTracker tracker=new TeleportTracker();UUID uuid=UUID.randomUUID();
        assertFalse(tracker.moved(uuid,Vec3d.ZERO));assertFalse(tracker.moved(uuid,new Vec3d(1000,0,0)));
        assertTrue(tracker.moved(uuid,new Vec3d(2000.01,0,0)));tracker.mark(uuid,100);
        tracker.expire(350);assertTrue(tracker.isRecent(uuid));tracker.expire(351);assertFalse(tracker.isRecent(uuid));
        tracker.remove(uuid);assertFalse(tracker.moved(uuid,Vec3d.ZERO));
    }
    @Test void handshakeAndScepterRulesRemainIndependentOfTransportAndItems() {
        ConnectionMode mode=new ConnectionMode();assertTrue(mode.intercept(false));assertFalse(mode.intercept(true));
        mode.hello();assertFalse(mode.intercept(false));mode.reset();assertTrue(mode.intercept(false));
        assertEquals(ScepterPolicy.Failure.SESSION_EXPIRED,ScepterPolicy.select(false,true,true,true));
        assertEquals(ScepterPolicy.Failure.NO_PERMISSION,ScepterPolicy.open(false,false,false));
        assertNull(ScepterPolicy.remove(true,true,true,false,36,true));
        assertEquals(ScepterPolicy.Failure.TOO_FAR,ScepterPolicy.remove(true,true,true,false,36.01,true));
        assertFalse(ScepterPolicy.sessionValid(true,false,true));
    }
}
