package network.azusake.halo.shape;

import java.util.List;
import java.util.Objects;
import network.azusake.halo.animation.AnimationTerm;
import network.azusake.halo.core.Identifier;
import network.azusake.halo.core.Vec3d;
import network.azusake.halo.core.render.MaterialState;

/** Static mesh, transformed and animated by its enclosing group. */
public record MeshPrimitive(Identifier model, Identifier texture, Vec3d size, Material material,
                            boolean preserveProportions, double scale) implements HaloPrimitive {
    /** Compatibility constructor: fit each axis to size, as before. */
    public MeshPrimitive(Identifier model, Identifier texture, Vec3d size, Material material) {
        this(model, texture, size, material, false, 1);
    }
    public MeshPrimitive {
        Objects.requireNonNull(model); Objects.requireNonNull(texture);
        Objects.requireNonNull(material);
        if (!preserveProportions) Objects.requireNonNull(size);
        if (!Float.isFinite((float) scale) || scale < 0) {
            throw new IllegalArgumentException("mesh.scale must be a finite nonnegative number");
        }
        if (size != null) for (double axis : new double[]{size.x, size.y, size.z}) {
            if (!Float.isFinite((float) axis) || axis < 0) throw new IllegalArgumentException("mesh.size must contain finite nonnegative numbers");
        }
    }
    public record Material(boolean doubleSided, AlphaMask mask) {
        public static final Material DEFAULT = new Material(true, null);
    }
    public record AlphaMask(Identifier texture, MaterialState.MaskMode mode, float threshold,
                            List<AnimationTerm> offsetU, List<AnimationTerm> offsetV) {
        public AlphaMask {
            Objects.requireNonNull(texture); Objects.requireNonNull(mode);
            offsetU = List.copyOf(offsetU); offsetV = List.copyOf(offsetV);
            if (!Float.isFinite(threshold) || threshold < 0 || threshold > 1) {
                throw new IllegalArgumentException("alpha_mask.threshold must be in [0,1]");
            }
        }
        public MaterialState.AlphaMask evaluate(double time) {
            return new MaterialState.AlphaMask(texture, mode, threshold, offset(offsetU, time), offset(offsetV, time));
        }
        private static float offset(List<AnimationTerm> terms, double time) {
            double value = 0;
            for (var term : terms) value += term.evaluate(time);
            if (!Double.isFinite(value)) throw new IllegalArgumentException("Non-finite mask animation result");
            // Wrap in double precision before converting to the GPU's float uniforms.
            float wrapped = (float) (value - Math.floor(value));
            return wrapped == 1 ? 0 : wrapped;
        }
    }
}
