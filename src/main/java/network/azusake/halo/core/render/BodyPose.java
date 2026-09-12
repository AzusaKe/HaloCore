package network.azusake.halo.core.render;

import network.azusake.halo.api.v2.AnchorRotation;
import network.azusake.halo.core.Vec3d;

/** World-space 6DOF before visual animation. Scale is deliberately separate. */
public record BodyPose(Vec3d position, AnchorRotation rotation, float visualScale) {}
