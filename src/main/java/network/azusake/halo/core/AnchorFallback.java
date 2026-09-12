package network.azusake.halo.core;

import network.azusake.halo.anchor.AnchorPoseMath;
import network.azusake.halo.api.v2.*;
import network.azusake.halo.data.PoseAnchor;
import network.azusake.halo.physics.HeadFrameMath;

/** Fallback anchor policy from semantic entity facts; game pose enums never enter core. */
public final class AnchorFallback {
    public enum Pose { STANDING, CROUCHING, SWIMMING }
    public static String poseKey(boolean sleeping,boolean flying,boolean swimming,Pose pose,boolean sneaking,boolean grounded) {
        if(sleeping)return "sleeping";
        if(flying)return "fall_flying";
        if(swimming)return "swimming";
        if(pose==Pose.SWIMMING)return "crawling";
        if(grounded && (pose==Pose.CROUCHING || sneaking))return "sneaking";
        return "standing";
    }
    public static AnchorPose player(Vec3d feet,PoseAnchor pose,float yaw,float pitch,float roll) {
        var frame=HeadFrameMath.of(yaw,pitch,roll);
        Vec3d h=pose.headCenterVector();
        Vec3d center=feet.add(pose.pivot()).add(frame.right().multiply(h.x))
            .add(frame.headUp().multiply(h.y)).add(frame.forward().multiply(h.z));
        return at(center,yaw,pitch,roll);
    }
    public static AnchorPose entity(Vec3d feet,double height,float yaw,float pitch) { return at(feet.add(0,height*.85,0),yaw,pitch,0); }
    public static AnchorPose at(Vec3d position,float yaw,float pitch,float roll) {
        return new AnchorPose(new AnchorVec3(position.x,position.y,position.z),AnchorPoseMath.fromMinecraftYawPitchRoll(yaw,pitch,roll));
    }
}
