package network.azusake.halo.api.v2;

import network.azusake.halo.anchor.AnchorCaptureCoordinator;
import network.azusake.halo.anchor.PreviewAnchorCoordinator;

/** Cross-loader head-anchor entry point for world and preview rendering. */
public final class HaloAnchorApi {

    private HaloAnchorApi() {
    }

    /** One unique lowercase namespace:path source, usable in either or both render spaces. */
    public static AnchorSource register(String sourceId) {
        return AnchorCaptureCoordinator.register(sourceId);
    }

    /** Null outside an eligible preview, including nested renders of unrelated entities. */
    public static PreviewAnchorContext currentPreviewContext() { return PreviewAnchorCoordinator.currentContext(); }

    /** Includes ineligible nested entities and invalidated scopes that are still unwinding. */
    public static boolean isPreviewRendering() { return PreviewAnchorCoordinator.isPreviewRendering(); }
}
