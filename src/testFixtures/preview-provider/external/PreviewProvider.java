package external;

import network.azusake.halo.api.v2.HaloAnchorApi;
import network.azusake.halo.api.v2.PreviewAnchorContext;
import network.azusake.halo.api.v2.PreviewAnchorPose;
import network.azusake.halo.api.v2.AnchorSource;

/** Separately compiled external provider. No host bridge, renderer, Minecraft or implementation imports. */
public final class PreviewProvider implements AutoCloseable {
    private final AnchorSource source = HaloAnchorApi.register("external:preview_fixture");
    public PreviewAnchorContext current() { return HaloAnchorApi.currentPreviewContext(); }
    public boolean capture(PreviewAnchorPose pose) { return source.submitPreview(current(), pose); }
    public boolean submitRetained(PreviewAnchorContext context, PreviewAnchorPose pose) { return source.submitPreview(context,pose); }
    @Override public void close() { source.close(); }
}
