package network.azusake.halo.api.v2.replay;

import java.util.Map;
import java.util.Objects;

/** Relative presentation progress at the containing packet's time. No wall-clock timestamps. */
public record ReplayVisualState(String definitionId, long ageNanos, Transition transition,
                                long transitionAgeNanos, double frozenPhaseSeconds,
                                boolean active, boolean hiddenByState, boolean sleeping, boolean invisible,
                                Map<String, ReplayGroupState> shutdownHead) {
    public enum Transition { NORMAL, STARTING, ENDING, NULL }
    public ReplayVisualState {
        Objects.requireNonNull(definitionId); Objects.requireNonNull(transition);
        if (ageNanos < 0 || transitionAgeNanos < -1 || !Double.isFinite(frozenPhaseSeconds))
            throw new IllegalArgumentException("invalid relative progress");
        shutdownHead = Map.copyOf(shutdownHead);
    }
}
