package network.azusake.halo.api.v2.replay;

import java.util.UUID;
import java.util.Objects;

/** Logical scepter screen state. Closed states retain the session number for stale-close rejection. */
public record ReplayUiState(long session, boolean open, UUID target, String targetName) {
    public static final ReplayUiState CLOSED = new ReplayUiState(0, false, null, "");
    public ReplayUiState {
        if (session < 0) throw new IllegalArgumentException("negative UI session");
        Objects.requireNonNull(targetName);
        if (open) Objects.requireNonNull(target);
    }
}
