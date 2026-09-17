package network.azusake.halo.api.v2;

import network.azusake.halo.core.runtime.HaloSourceCoordinator;

/** Loader-neutral entry point for server-authoritative halo ownership sources. */
public final class HaloApi {
    private HaloApi() {}

    /** Register one process-wide source. Actual candidates remain scoped to an active server host. */
    public static HaloSource registerSource(String sourceId, int defaultPriority) {
        return HaloSourceCoordinator.register(sourceId, defaultPriority);
    }
}
