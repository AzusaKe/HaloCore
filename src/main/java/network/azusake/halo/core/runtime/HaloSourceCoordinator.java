package network.azusake.halo.core.runtime;

import java.util.*;
import java.util.regex.Pattern;
import network.azusake.halo.api.v2.HaloSource;

/** Internal process-wide registration table. Candidate state belongs to the active host. */
public final class HaloSourceCoordinator {
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Map<String, SourceHandle> SOURCES = new LinkedHashMap<>();
    private static long nextOrder;
    private static HaloSourceHost host;

    private HaloSourceCoordinator() {}

    public static synchronized HaloSource register(String sourceId, int defaultPriority) {
        requireId(sourceId, "sourceId");
        SourceHandle existing = SOURCES.get(sourceId);
        if (existing != null && !existing.closed) {
            throw new IllegalStateException("halo source is already registered: " + sourceId);
        }
        SourceHandle handle = new SourceHandle(sourceId, defaultPriority, ++nextOrder);
        SOURCES.put(sourceId, handle);
        try {
            if (host != null) host.registered(handle.registration());
        } catch (RuntimeException ex) {
            SOURCES.remove(sourceId, handle);
            throw ex;
        }
        return handle;
    }

    static synchronized List<Registration> registrations() {
        return SOURCES.values().stream().filter(source -> !source.closed)
            .map(SourceHandle::registration).toList();
    }

    static synchronized void bind(HaloSourceHost value) {
        if (host != null && host != value) throw new IllegalStateException("a halo source host is already active");
        host = value;
    }

    static synchronized void unbind(HaloSourceHost value) {
        if (host == value) host = null;
    }

    private static synchronized boolean set(SourceHandle source, UUID entity, String definition) {
        Objects.requireNonNull(entity, "entityUuid");
        requireId(definition, "definitionId");
        return !source.closed && host != null && host.set(source.registration(), entity, definition);
    }

    private static synchronized boolean clear(SourceHandle source, UUID entity) {
        Objects.requireNonNull(entity, "entityUuid");
        return !source.closed && host != null && host.clear(source.registration(), entity);
    }

    private static synchronized int clearAll(SourceHandle source) {
        return !source.closed && host != null ? host.clearAll(source.registration()) : 0;
    }

    private static synchronized void close(SourceHandle source) {
        if (source.closed) return;
        if (host != null) host.closed(source.registration());
        source.closed = true;
        SOURCES.remove(source.sourceId, source);
    }

    private static void requireId(String value, String name) {
        if (value == null || !ID.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be a lowercase namespace:path identifier");
        }
    }

    public record Registration(String sourceId, int defaultPriority, long order) {}

    private static final class SourceHandle implements HaloSource {
        private final String sourceId;
        private final int defaultPriority;
        private final long order;
        private volatile boolean closed;

        private SourceHandle(String sourceId, int defaultPriority, long order) {
            this.sourceId = sourceId;
            this.defaultPriority = defaultPriority;
            this.order = order;
        }

        private Registration registration() { return new Registration(sourceId, defaultPriority, order); }
        @Override public String sourceId() { return sourceId; }
        @Override public int defaultPriority() { return defaultPriority; }
        @Override public boolean set(UUID entityUuid, String definitionId) {
            return HaloSourceCoordinator.set(this, entityUuid, definitionId);
        }
        @Override public boolean clear(UUID entityUuid) { return HaloSourceCoordinator.clear(this, entityUuid); }
        @Override public int clearAll() { return HaloSourceCoordinator.clearAll(this); }
        @Override public void close() { HaloSourceCoordinator.close(this); }
    }
}
