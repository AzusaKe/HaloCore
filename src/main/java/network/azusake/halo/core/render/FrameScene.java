package network.azusake.halo.core.render;

import java.util.Map;
import java.util.UUID;
import network.azusake.halo.api.v2.AnchorPose;
import network.azusake.halo.core.Identifier;
import network.azusake.halo.core.Vec3d;

/** Immutable host input for one main-camera frame. Positions are doubles in world units. */
public record FrameScene(long worldToken, long timeMillis, long frameNanos,
                         CameraSample camera, Map<UUID, EntitySample> entities,
                         float[] rootTransform, LightSampler lights, TextureLookup textures,
                         VisualResources visuals, LightmapSampler lightmaps) {
    public FrameScene {
        entities = Map.copyOf(entities);
        rootTransform = rootTransform.clone();
        java.util.Objects.requireNonNull(visuals);
        java.util.Objects.requireNonNull(lightmaps);
    }
    /** Compatibility constructor for adapters that only provide pre-multiplied scalar brightness. */
    public FrameScene(long worldToken, long timeMillis, long frameNanos, CameraSample camera,
                      Map<UUID, EntitySample> entities, float[] rootTransform, LightSampler lights,
                      TextureLookup textures, VisualResources visuals) {
        this(worldToken, timeMillis, frameNanos, camera, entities, rootTransform, lights, textures,
            visuals, LightmapSampler.NONE);
    }
    public FrameScene(long worldToken, long timeMillis, long frameNanos, CameraSample camera,
                      Map<UUID, EntitySample> entities, float[] rootTransform, LightSampler lights,
                      TextureLookup textures) {
        this(worldToken, timeMillis, frameNanos, camera, entities, rootTransform, lights, textures,
            VisualResources.EMPTY, LightmapSampler.NONE);
    }
    @Override public float[] rootTransform() { return rootTransform.clone(); }
    public record CameraSample(Vec3d position, Vec3d up, Vec3d right) {
        public Vec3d getPos() { return position; }
    }
    public record EntitySample(UUID uuid, int runtimeId, Vec3d position,
                               boolean alive, boolean sleeping, boolean invisible,
                               AnchorPose anchor, AnchorPose fallbackAnchor) {
        public boolean isAlive() { return alive; }
        public boolean isSleeping() { return sleeping; }
        public boolean isInvisible() { return invisible; }
    }
    @FunctionalInterface public interface LightSampler { float brightness(Vec3d worldPosition); }
    @FunctionalInterface public interface LightmapSampler {
        LightmapSampler NONE = worldPosition -> LightSample.UNAVAILABLE;
        LightSample sample(Vec3d worldPosition);
    }
    @FunctionalInterface public interface TextureLookup { boolean exists(Identifier texture); }
}
