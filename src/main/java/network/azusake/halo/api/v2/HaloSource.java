package network.azusake.halo.api.v2;

import java.util.UUID;

/**
 * A server-side source of persistent halo candidates.
 *
 * <p>Mutations are accepted only on the active logical server thread. A candidate remains until
 * it is cleared, this source is closed, the entity is permanently forgotten, or the server session
 * ends. Return values report whether the active host accepted a state change, not whether this
 * source won arbitration.</p>
 */
public interface HaloSource extends AutoCloseable {
    String sourceId();
    int defaultPriority();
    boolean set(UUID entityUuid, String definitionId);
    boolean clear(UUID entityUuid);
    int clearAll();

    /** Idempotent. Unregisters the source and removes its candidates from the active session. */
    @Override
    void close();
}
