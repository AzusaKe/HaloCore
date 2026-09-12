package network.azusake.halo.core.runtime;

import network.azusake.halo.config.HaloConfig;
import network.azusake.halo.core.Vec3d;

/** Immutable message used by the integrated-server bridge; it is not a new network packet. */
public record RuntimeConfigSnapshot(double linearDamping, double angularDamping, double maxLinearDistance,
                                    double maxAngularDegrees, boolean angularMomentum, double momentumFactor,
                                    double maxMomentumDegrees, double scale, Vec3d offset, Vec3d rotationOffset) {
    public static RuntimeConfigSnapshot of(HaloConfig c) {
        return new RuntimeConfigSnapshot(c.getLinearDampingFactor(), c.getAngularDampingFactor(),
            c.getMaxLinearDistance(), c.getMaxAngularDegrees(), c.isAllowAngularMomentum(),
            c.getAngularMomentumFactor(), c.getMaxAngularMomentumDegrees(), c.getHaloScale(),
            c.getPositionOffset(), c.getRotationOffset());
    }

    public HaloConfig toConfig() {
        var c = new HaloConfig();
        c.setLinearDampingFactor(linearDamping); c.setAngularDampingFactor(angularDamping);
        c.setMaxLinearDistance(maxLinearDistance); c.setMaxAngularDegrees(maxAngularDegrees);
        c.setAllowAngularMomentum(angularMomentum); c.setAngularMomentumFactor(momentumFactor);
        c.setMaxAngularMomentumDegrees(maxMomentumDegrees); c.setHaloScale(scale);
        c.setPositionOffset(offset); c.setRotationOffset(rotationOffset);
        return c;
    }
}
