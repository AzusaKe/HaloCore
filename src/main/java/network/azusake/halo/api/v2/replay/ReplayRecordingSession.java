package network.azusake.halo.api.v2.replay;

/** Client-thread subscription. Snapshot watermarks delimit all later events. */
public interface ReplayRecordingSession extends AutoCloseable {
    ReplaySnapshot snapshot();
    boolean isValid();
    @Override void close();
}
