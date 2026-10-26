package network.azusake.halo.api.v2.replay;

import java.util.UUID;
import java.util.Objects;

/** An effective change; its time comes from the recording container, never from this value. */
public sealed interface ReplayEvent {
    long sequence();
    /** Null assignment removes ownership; null visual removes the presentation instance. */
    record WearerChanged(long sequence, UUID wearer, String assignment, ReplayVisualState visual) implements ReplayEvent {
        public WearerChanged { requireSequence(sequence); Objects.requireNonNull(wearer); }
    }
    record Reset(ReplaySnapshot snapshot) implements ReplayEvent {
        public Reset { Objects.requireNonNull(snapshot); }
        @Override public long sequence() { return snapshot.sequence(); }
    }
    record UiChanged(long sequence, ReplayUiState ui) implements ReplayEvent {
        public UiChanged { requireSequence(sequence); Objects.requireNonNull(ui); }
    }
    private static void requireSequence(long sequence) {
        if (sequence <= 0) throw new IllegalArgumentException("event sequence must be positive");
    }
}
