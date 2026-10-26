package network.azusake.halo.api.v2.replay;

import java.util.Map;
import java.util.UUID;
import java.util.Objects;

/** Full replacement, including an empty ownership table and detached shutdown visuals. */
public record ReplaySnapshot(long sequence, Map<UUID, String> assignments,
                             Map<UUID, ReplayVisualState> visuals, ReplayUiState ui) {
    public ReplaySnapshot {
        if (sequence < 0) throw new IllegalArgumentException("negative sequence");
        assignments = Map.copyOf(assignments); visuals = Map.copyOf(visuals);
        Objects.requireNonNull(ui);
    }
}
