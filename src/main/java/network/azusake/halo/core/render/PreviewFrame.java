package network.azusake.halo.core.render;

import java.util.Objects;
import java.util.UUID;
import network.azusake.halo.api.v2.AnchorPose;

/**
 * A head in a host-defined, block-sized scene (+Y up, +Z head-forward).
 * camera.position is subtracted in scene space, then rootTransform maps the camera-relative
 * scene to view space, where more negative Z is farther away. GUI hosts normally use a zero
 * camera position and put pixel placement/scale in rootTransform.
 * Camera up/right are expressed in that resulting view space, including any host reflection.
 * Pixel placement and projection belong to the host, not to the head pose.
 * Animation uses the owning client's most recent appearance frame; the supplied clocks identify
 * this view sample. An opted-in physical session uses frameNanos for its independent motion.
 */
public record PreviewFrame(UUID wearer, int runtimeId, AnchorPose head,
                           FrameScene.CameraSample camera, float[] rootTransform,
                           long timeMillis, long frameNanos, LightSample light,
                           FrameScene.TextureLookup textures, VisualResources visuals, Projection projection) {
    public enum Projection { ORTHOGRAPHIC, PERSPECTIVE }
    public PreviewFrame(UUID wearer, int runtimeId, AnchorPose head, FrameScene.CameraSample camera,
                        float[] rootTransform, long timeMillis, long frameNanos, LightSample light,
                        FrameScene.TextureLookup textures, VisualResources visuals) {
        this(wearer, runtimeId, head, camera, rootTransform, timeMillis, frameNanos, light, textures,
            visuals, Projection.ORTHOGRAPHIC);
    }
    public PreviewFrame {
        Objects.requireNonNull(wearer);
        Objects.requireNonNull(head);
        Objects.requireNonNull(camera);
        Objects.requireNonNull(light);
        Objects.requireNonNull(textures);
        Objects.requireNonNull(visuals);
        Objects.requireNonNull(projection);
        rootTransform = Objects.requireNonNull(rootTransform).clone();
        if (rootTransform.length != 16) throw new IllegalArgumentException("Expected a 4x4 root transform");
        for (float value : rootTransform) if (!Float.isFinite(value))
            throw new IllegalArgumentException("Non-finite preview root transform");
    }
    @Override public float[] rootTransform() { return rootTransform.clone(); }
}
