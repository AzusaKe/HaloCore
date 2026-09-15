package network.azusake.halo.core.runtime;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import network.azusake.halo.core.Identifier;
import network.azusake.halo.core.render.*;
import network.azusake.halo.config.HaloConfig;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AdapterContractTest {
    private static final Identifier ID = new Identifier("halo:test");
    private static ResourceInput resource(String json) { return new ResourceInput(ID, "test-pack", json); }

    @Test void resourceSourcesPublishCompleteGenerationsAndAllowRestoration() {
        var resources = new DefinitionResources();
        assertTrue(resources.reload(0, List.of(resource("{\"id\":\"halo:test\"}"))).isEmpty());
        var old = resources.snapshot();
        var problems = resources.reload(1, List.of(resource("broken-json")));
        assertEquals(1, problems.size()); assertEquals("test-pack", problems.get(0).source());
        assertEquals(Set.of(ID), resources.snapshot().ids());
        resources.reload(0, List.of());
        assertTrue(resources.snapshot().ids().isEmpty());
        assertEquals(Set.of(ID), old.ids());
        assertThrows(UnsupportedOperationException.class, () -> old.ids().clear());
    }

    @Test void duplicateAttachDoesNotRestartWhileReloadPreservesTheAnimationClock() {
        var clock = new AtomicLong(1000);
        var resources = new DefinitionResources();
        resources.reload(1, List.of(resource("{\"id\":\"halo:test\"}")));
        var client = new ClientRuntime(clock::get);
        var uuid = new UUID(0, 1);
        client.definitions(resources.snapshot()); client.attach(uuid, ID, true);
        var instance = client.getInstance(uuid);
        clock.addAndGet(200); client.attach(uuid, ID, true);
        assertSame(instance, client.getInstance(uuid));
        resources.reload(1, List.of()); client.definitions(resources.snapshot());
        assertEquals(1000, instance.getCreatedAtTime()); assertEquals(1000, instance.getTransitionStartTime());
        assertEquals(ID, client.assignments().get(uuid));
    }

    @Test void integratedConfigIsAnImmutableValueBetweenOwners() {
        var original = new HaloConfig(); original.setHaloScale(3);
        var snapshot = RuntimeConfigSnapshot.of(original);
        original.setHaloScale(4);
        var client = snapshot.toConfig(); client.setHaloScale(5);
        assertEquals(3, snapshot.scale()); assertEquals(4, original.getHaloScale());
    }

    @Test void stablePortSignaturesDoNotExposeMathOrJsonLibraries() {
        for (Class<?> type : List.of(ClientPort.class, ResourceInput.class, DefinitionSnapshot.class,
                PreviewPort.class, PreviewSession.class, PreviewOptions.class, PreviewFrame.class,
                BodyPose.class, FrameScene.class, FrameOutput.class, DrawBatch.class, MeshDraw.class,
                MeshIndexWriter.class, RuntimeConfigSnapshot.class,
                TriangleMesh.class, VisualResources.class, VisualResources.TextureInfo.class,
                MaterialState.class, MaterialState.Mesh.class, MaterialState.AlphaMask.class,
                LightSample.class, FrameScene.LightmapSampler.class,
                DefinitionSnapshot.AssetDependencies.class, VisualAssetLoader.class, VisualAssetLoader.Source.class,
                ServerRuntime.OwnershipStore.class, ServerRuntime.Updates.class)) {
            for (var method : type.getDeclaredMethods()) {
                if (!java.lang.reflect.Modifier.isPublic(method.getModifiers())) continue;
                var signature = method.toGenericString();
                for (String banned : List.of("net.minecraft", "net.fabricmc", "org.joml", "com.google.gson")) {
                    assertFalse(signature.contains(banned), signature);
                }
            }
        }
    }

    @Test void clientDeathAndUnloadUseDistinctReplicaPolicies() {
        var client = new ClientRuntime(() -> 1000L);
        var uuid = new UUID(0, 1);
        client.attach(uuid, ID, true);
        client.unload(uuid);
        assertEquals(ID, client.assignments().get(uuid));
        client.died(uuid, true);
        assertEquals(ID, client.assignments().get(uuid));
        client.died(uuid, false);
        assertFalse(client.assignments().containsKey(uuid));
        assertNull(client.getInstance(uuid));
    }
}
