package network.azusake.halo.core.render;

import java.util.Objects;
import network.azusake.halo.core.Identifier;

/** Per-draw material semantics; never a platform shader name or GPU object. */
public sealed interface MaterialState permits MaterialState.Legacy, MaterialState.Mesh {
    MaterialState LEGACY = new Legacy();
    record Legacy() implements MaterialState {}
    /** Null mask means ordinary textured mesh rendering, with no alpha cutoff except zero. */
    record Mesh(AlphaMask mask) implements MaterialState {}
    enum MaskMode { LINEAR, STEP }
    record AlphaMask(Identifier texture, MaskMode mode, float threshold, float offsetU, float offsetV) {
        public AlphaMask {
            Objects.requireNonNull(texture); Objects.requireNonNull(mode);
            if (!Float.isFinite(threshold) || threshold < 0 || threshold > 1
                    || !Float.isFinite(offsetU) || !Float.isFinite(offsetV)) {
                throw new IllegalArgumentException("Invalid alpha mask parameters");
            }
        }
        /** Reference transfer function for hosts and deterministic tests; GPU sampling supplies red. */
        public float alpha(float red) {
            float gray = Math.max(0, Math.min(1, red));
            return mode == MaskMode.STEP ? (gray >= threshold ? 1 : 0) : gray;
        }
    }
}
