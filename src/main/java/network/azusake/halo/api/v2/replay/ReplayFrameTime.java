package network.azusake.halo.api.v2.replay;

/** Source drives simulation, output identifies the exported sample. All durations are nanoseconds. */
public record ReplayFrameTime(long epoch, long sample, long sourceNanos, long outputNanos, boolean discontinuity) {
    public ReplayFrameTime {
        if (epoch < 0 || sample < 0 || sourceNanos < 0 || outputNanos < 0)
            throw new IllegalArgumentException("negative replay time or identity");
    }
}
