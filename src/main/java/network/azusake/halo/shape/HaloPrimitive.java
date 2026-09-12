package network.azusake.halo.shape;

/**
 * A renderable primitive inside a {@link HaloGroup}.
 *
 * <p>Geometry and material rules are implemented in core; the host supplies resources.</p>
 */
public sealed interface HaloPrimitive permits BillboardPrimitive, RingPrimitive, MeshPrimitive {
}
