package network.azusake.halo.api.v2;

import java.util.Objects;

/**
 * Final visual head centre in the preview scene, measured in blocks before sceneToView.
 * Rotation uses local +Y head-up and +Z head-forward. Contains no GUI pixel scale or physics.
 */
public record PreviewAnchorPose(double x, double y, double z, AnchorRotation rotation) {
    public PreviewAnchorPose {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
            throw new IllegalArgumentException("Preview head position must be finite");
        Objects.requireNonNull(rotation, "rotation");
    }
}
