package network.azusake.halo.api.v2.replay;

/** Exclusive client-thread playback controller. Container times are supplied separately from data. */
public interface ReplayPlaybackSession extends AutoCloseable {
    enum Mode { PLAYBACK, EXPORT_PREPARING, EXPORTING }
    void mode(Mode mode);
    /** Clear future state and install this baseline at its actual container time for the given seek epoch. */
    void restore(ReplaySnapshot snapshot, long sourceNanos, long epoch);
    /** False for a stale epoch or an already applied event. */
    boolean apply(ReplayEvent event, long sourceNanos, long epoch);
    /** Set before ticking/network application; repeated views reuse this sample. */
    void frame(ReplayFrameTime time);
    boolean isValid();
    @Override void close();
}
