package network.azusake.halo.api.v2.replay;

import java.util.List;

/** A transition's evaluated group values; rotation is YXZ Euler degrees, offset is in blocks. */
public record ReplayGroupState(List<Float> offset, List<Float> scale, float alpha, List<Float> rotation) {
    public ReplayGroupState {
        offset = vector(offset); scale = vector(scale); rotation = vector(rotation);
        if (!Float.isFinite(alpha)) throw new IllegalArgumentException("non-finite alpha");
    }
    private static List<Float> vector(List<Float> value) {
        var copy = List.copyOf(value);
        if (copy.size() != 3 || copy.stream().anyMatch(v -> !Float.isFinite(v)))
            throw new IllegalArgumentException("expected three finite components");
        return copy;
    }
}
