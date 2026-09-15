package network.azusake.halo.render;

import java.util.Map;
import network.azusake.halo.api.v2.AnchorRotation;
import network.azusake.halo.core.Vec3d;
import network.azusake.halo.data.HaloDefinition;
import network.azusake.halo.shape.HaloGroup;

/** Internal immutable evaluated appearance, independent of world/preview pose and geometry. */
public record HaloAppearance(HaloDefinition definition, long timeMillis, double animationTime,
                             Map<HaloGroup, Group> groups) {
    public HaloAppearance { groups = Map.copyOf(groups); }
    public record Group(Vec3d offset, AnchorRotation rotation, float scaleX, float scaleY, float scaleZ,
                        float alpha, float glow) {}
}
