package network.azusake.halo.api.v2.replay;

import java.util.Optional;
import java.util.function.Consumer;
import network.azusake.halo.core.runtime.ReplayHost;

/** Loader-neutral, optional replay entry point. All calls belong to the owning client thread. */
public final class HaloReplayApi {
    private HaloReplayApi() {}
    public static Optional<ReplayRecordingSession> openRecording(String backendId, Consumer<ReplayEvent> listener) {
        return ReplayHost.openRecording(backendId, listener);
    }
    public static Optional<ReplayPlaybackSession> openPlayback(String backendId) {
        return ReplayHost.openPlayback(backendId);
    }
}
