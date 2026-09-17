package network.azusake.halo.core.runtime;

import java.util.*;
import network.azusake.halo.api.v2.HaloApi;
import network.azusake.halo.core.Identifier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HaloSourceApiTest {
    private static final UUID ENTITY = new UUID(0, 42);

    @Test void prioritiesFallbackAndSameDefinitionSourceSwitchAreDeterministic() {
        List<String> updates = new ArrayList<>();
        ServerRuntime runtime = runtime(updates);
        try (var low = HaloApi.registerSource("test:low", -10);
             var high = HaloApi.registerSource("test:high", 20);
             var host = new HaloSourceHost(runtime, Map.of())) {
            assertTrue(low.set(ENTITY, "test:low_halo"));
            assertEquals(List.of("+test:low_halo"), updates);
            assertTrue(high.set(ENTITY, "test:high_halo"));
            assertEquals(List.of("+test:low_halo", "+test:high_halo"), updates);
            assertEquals("test:high", runtime.selection(ENTITY).sourceId());

            updates.clear();
            assertTrue(low.set(ENTITY, "test:high_halo"));
            high.clear(ENTITY);
            assertTrue(updates.isEmpty(), "same definition fallback must not restart presentation");
            assertEquals("test:low", runtime.selection(ENTITY).sourceId());

            low.clear(ENTITY);
            assertEquals(List.of("-test:high_halo"), updates);
        }
    }

    @Test void firstCollisionDemotesAndSecondCollisionDisablesIncludingMinValue() {
        try (var first = HaloApi.registerSource("test:first", 20);
             var second = HaloApi.registerSource("test:second", 20);
             var third = HaloApi.registerSource("test:third", 20);
             var minA = HaloApi.registerSource("test:min_a", Integer.MIN_VALUE);
             var minB = HaloApi.registerSource("test:min_b", Integer.MIN_VALUE);
             var host = new HaloSourceHost(runtime(new ArrayList<>()), Map.of())) {
            var entries = host.priorities().entries();
            assertEquals(20, entries.get("test:first").effectivePriority());
            assertEquals(19, entries.get("test:second").effectivePriority());
            assertEquals(HaloSourceHost.PriorityStatus.AUTO_DEMOTED, entries.get("test:second").status());
            assertNull(entries.get("test:third").effectivePriority());
            assertEquals(HaloSourceHost.PriorityStatus.DISABLED_CONFLICT, entries.get("test:third").status());
            assertEquals("test:first", entries.get("test:third").conflictWith());
            assertEquals("test:second", entries.get("test:third").fallbackConflictWith());
            third.set(ENTITY, "test:disabled");
            assertTrue(host.candidates(ENTITY).stream().anyMatch(candidate -> candidate.sourceId().equals("test:third")
                && candidate.priority() == null
                && candidate.status() == HaloSourceHost.PriorityStatus.DISABLED_CONFLICT));
            assertEquals(Integer.MIN_VALUE, entries.get("test:min_a").effectivePriority());
            assertNull(entries.get("test:min_b").effectivePriority());
            assertEquals("test:min_a", entries.get("test:min_b").conflictWith());
            assertNull(entries.get("test:min_b").fallbackConflictWith());
            assertEquals(19, host.priorities().persistedPriorities().get("test:second"));
            second.close();
            assertEquals(HaloSourceHost.PriorityStatus.DISABLED_CONFLICT,
                host.priorities().entries().get("test:third").status(),
                "closing another source must not silently re-enable a conflict");
        }
    }

    @Test void candidatesAreSessionScopedAndInactiveEntitiesRecoverWithoutReSubmission() {
        var source = HaloApi.registerSource("test:lifecycle", 10);
        ServerRuntime firstRuntime = runtime(new ArrayList<>());
        try (var first = new HaloSourceHost(firstRuntime, Map.of())) {
            source.set(ENTITY, "test:ring");
            first.deactivate(ENTITY);
            assertNull(firstRuntime.get(ENTITY));
            first.activate(ENTITY);
            assertEquals(new Identifier("test:ring"), firstRuntime.get(ENTITY));
        }
        ServerRuntime secondRuntime = runtime(new ArrayList<>());
        try (var second = new HaloSourceHost(secondRuntime, Map.of())) {
            second.activate(ENTITY);
            assertNull(secondRuntime.get(ENTITY));
        } finally {
            source.close();
        }
    }

    @Test void runtimeReconfigurationAndClosingWinnerImmediatelyReconcile() {
        List<String> updates = new ArrayList<>();
        var low = HaloApi.registerSource("test:configured_low", -10);
        var high = HaloApi.registerSource("test:configured_high", 20);
        try (var host = new HaloSourceHost(runtime(updates), Map.of())) {
            low.set(ENTITY, "test:low"); high.set(ENTITY, "test:high"); updates.clear();
            host.reconfigure(Map.of("test:configured_low", 30, "test:configured_high", 20));
            assertEquals(List.of("+test:low"), updates);
            updates.clear(); low.close();
            assertEquals(List.of("+test:high"), updates);
        } finally {
            low.close(); high.close();
        }
    }

    @Test void registrationValidationAndClosedHandleAreSafe() {
        assertThrows(IllegalArgumentException.class, () -> HaloApi.registerSource("Not Valid", 0));
        var source = HaloApi.registerSource("test:unique", 0);
        assertThrows(IllegalStateException.class, () -> HaloApi.registerSource("test:unique", 0));
        assertThrows(IllegalArgumentException.class, () -> source.set(ENTITY, "Upper:invalid"));
        source.close(); source.close();
        assertFalse(source.set(ENTITY, "test:ring"));
        HaloApi.registerSource("test:unique", 0).close();
    }

    private static ServerRuntime runtime(List<String> updates) {
        Map<UUID, Identifier> disk = new HashMap<>();
        return new ServerRuntime(new ServerRuntime.OwnershipStore() {
            public Identifier get(UUID entity) { return disk.get(entity); }
            public void set(UUID entity, Identifier definition) { disk.put(entity, definition); }
            public void remove(UUID entity) { disk.remove(entity); }
        }, new ServerRuntime.Updates() {
            public void attach(UUID entity, Identifier definition) { updates.add("+" + definition); }
            public void remove(UUID entity, Identifier definition) { updates.add("-" + definition); }
        });
    }
}
