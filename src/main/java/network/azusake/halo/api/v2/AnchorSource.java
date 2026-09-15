package network.azusake.halo.api.v2;

import java.util.UUID;

/**
 * A render-time head-anchor source. Either render space may be used independently.
 *
 * <p>Call {@link #submit(UUID, AnchorPose)} only where the source has completed the effective
 * head transform for the entity currently being rendered. Halo rejects
 * submissions outside an accepted main-camera entity render scope.</p>
 */
public interface AnchorSource extends AutoCloseable {

    boolean submit(UUID entityUuid, AnchorPose pose);

    /**
     * Submit the actual preview head after model preparation. First valid model submission wins.
     * Returns false for closed sources, null poses or foreign/stale/suspended/wrong-thread contexts.
     * Does not submit to world storage, advance physics or initiate drawing.
     */
    boolean submitPreview(PreviewAnchorContext context, PreviewAnchorPose pose);

    /** Idempotent. Unregisters this source and invalidates its captures in both render spaces. */
    @Override
    void close();
}
