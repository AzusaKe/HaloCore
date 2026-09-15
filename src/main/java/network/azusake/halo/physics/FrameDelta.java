package network.azusake.halo.physics;

/** Shared world/preview frame-time clamp and EMA. Each simulation owns its clock. */
public final class FrameDelta {
    private long previous;
    private boolean first = true;
    private double smoothed = -1;

    public double advance(long nanos) {
        double raw = first ? 0 : Math.max(.001, Math.min((nanos - previous) / 1_000_000_000.0, .1));
        first = false;
        previous = nanos;
        smoothed = smoothed < 0 ? raw : smoothed * .8 + raw * .2;
        return smoothed;
    }
    public void reset() { first = true; smoothed = -1; }
}
