package network.azusake.halo.core.render;

/**
 * Platform-neutral block/sky light levels sampled at one world position.
 * Values use the conventional inclusive 0..15 range. {@link #UNAVAILABLE}
 * lets older adapters retain the legacy pre-multiplied brightness path.
 */
public record LightSample(int block, int sky) {
    public static final LightSample UNAVAILABLE = new LightSample(-1, -1);
    public static final LightSample FULL_BRIGHT = new LightSample(15, 15);

    public LightSample {
        boolean unavailable = block == -1 && sky == -1;
        if (!unavailable && (block < 0 || block > 15 || sky < 0 || sky > 15)) {
            throw new IllegalArgumentException("Light levels must be in [0, 15]");
        }
    }

    public boolean available() {
        return block >= 0;
    }
}
