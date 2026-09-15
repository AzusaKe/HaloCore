package network.azusake.halo.core.runtime;

import network.azusake.halo.api.v2.PreviewAnchorContext;
import network.azusake.halo.api.v2.PreviewAnchorPose;

/** Host-owned lexical draw scope; close with try-with-resources. Providers use the public anchor API. */
public interface PreviewAnchorScope extends AutoCloseable {
    enum Fallback { POSED, RENDERED }
    PreviewAnchorContext context();
    boolean hasFallback(Fallback kind);
    /** First submission per fallback kind wins; RENDERED takes precedence over POSED. */
    boolean submitFallback(PreviewAnchorPose pose, Fallback kind);
    /** Provider > rendered fallback > posed fallback, or null. Does not cache across draws. */
    PreviewAnchorPose resolved();
    /** Reverse nesting order on the owning thread; idempotent. Still required after host invalidation. */
    @Override void close();
}
