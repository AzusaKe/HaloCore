package network.azusake.halo.core.runtime;

/** Optional, additive client capability. Existing ClientPort implementations need not implement it. */
public interface PreviewPort {
    /** Opens a view owned by the calling client thread. Close it when the host view is disposed. */
    PreviewSession openPreview();
    /** Additive capability; older providers continue supporting the original rigid contract. */
    default PreviewSession openPreview(PreviewOptions options) {
        if (java.util.Objects.requireNonNull(options).physicsEnabled())
            throw new UnsupportedOperationException("This preview provider does not support physics");
        return openPreview();
    }
}
