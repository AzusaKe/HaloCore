package network.azusake.halo.core.runtime;

import java.util.Objects;
import network.azusake.halo.core.Identifier;

/** One UTF-8 resource as read by a host. Source identifies the pack for diagnostics. */
public record ResourceInput(Identifier resource, String source, String json) {
    public ResourceInput {
        Objects.requireNonNull(resource);
        Objects.requireNonNull(source);
        Objects.requireNonNull(json);
    }
}
