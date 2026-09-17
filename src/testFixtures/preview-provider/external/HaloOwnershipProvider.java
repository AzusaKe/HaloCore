package external;

import java.util.UUID;
import network.azusake.halo.api.v2.HaloApi;
import network.azusake.halo.api.v2.HaloSource;

/** Separately compiled ownership provider using only the public api.v2 jar. */
public final class HaloOwnershipProvider implements AutoCloseable {
    private final HaloSource source = HaloApi.registerSource("external:ownership_fixture", -10);
    public boolean equip(UUID entity, String definition) { return source.set(entity, definition); }
    public boolean unequip(UUID entity) { return source.clear(entity); }
    @Override public void close() { source.close(); }
}
