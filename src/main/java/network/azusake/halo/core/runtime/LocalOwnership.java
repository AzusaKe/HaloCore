package network.azusake.halo.core.runtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import network.azusake.halo.core.Identifier;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side halo state for the LOCAL phase (server without the mod).
 *
 * <p>Data is keyed by a host-supplied server address and survives disconnects. TextStore performs
 * external I/O; restoreInto updates the same client replica used by multiplayer messages.</p>
 */
public final class LocalOwnership {

    public interface TextStore {
        String read() throws IOException;
        void write(String text) throws IOException;
    }
    private final TextStore store;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Serialisation type for the JSON file format. */
    private static final Type STORAGE_TYPE = new TypeToken<Map<String, Map<String, String>>>() {}.getType();

    /**
     * Outer key: server identifier ({@code "host:port"}).
     * Inner map: entity UUID → halo definition ID.
     */
    private final ConcurrentHashMap<String, ConcurrentHashMap<UUID, Identifier>> serverHalos =
        new ConcurrentHashMap<>();

    private volatile boolean loaded = false;

    public LocalOwnership(TextStore store) { this.store=store; }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    /** Ensure data is loaded from disk.  Safe to call multiple times. */
    private void ensureLoaded() {
        if (loaded) return;
        synchronized (this) {
            if (loaded) return;
            load();
            loaded = true;
        }
    }

    /** Read the JSON file and populate the in-memory map. */
    private void load() {

        try {
            String raw = store.read();
            if(raw==null || raw.isBlank())return;
            // Gson's fromJson returns null for empty JSON objects; we tolerate that.
            Map<String, Map<String, String>> disk = GSON.fromJson(raw, STORAGE_TYPE);
            if (disk == null) return;

            for (var serverEntry : disk.entrySet()) {
                String serverKey = serverEntry.getKey();
                Map<String, String> uuidMap = serverEntry.getValue();
                if (uuidMap == null) continue;
                ConcurrentHashMap<UUID, Identifier> inner = new ConcurrentHashMap<>();
                for (var uuidEntry : uuidMap.entrySet()) {
                    try {
                        UUID uuid = UUID.fromString(uuidEntry.getKey());
                        Identifier defId = new Identifier(uuidEntry.getValue());
                        inner.put(uuid, defId);
                    } catch (IllegalArgumentException e) {
                        // skip malformed entries silently
                    }
                }
                if (!inner.isEmpty()) {
                    serverHalos.put(serverKey, inner);
                }
            }
        } catch (IOException | RuntimeException e) {
            // File is corrupt or unreadable — start fresh
            network.azusake.halo.core.Diagnostics.logger("halo").warn("Failed to load local ownership: {}", e.getMessage());
        }
    }

    /** Write the current in-memory map to disk. */
    private void save() {
        try {

            // Build a plain LinkedHashMap<String, Map<String, String>> for JSON
            Map<String, Map<String, String>> disk = new LinkedHashMap<>();
            for (var serverEntry : serverHalos.entrySet()) {
                Map<String, String> uuidMap = new LinkedHashMap<>();
                for (var uuidEntry : serverEntry.getValue().entrySet()) {
                    uuidMap.put(uuidEntry.getKey().toString(), uuidEntry.getValue().toString());
                }
                if (!uuidMap.isEmpty()) {
                    disk.put(serverEntry.getKey(), uuidMap);
                }
            }

            store.write(GSON.toJson(disk));
        } catch (IOException | RuntimeException e) {
            network.azusake.halo.core.Diagnostics.logger("halo").warn("Failed to save local ownership: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Record (or replace) a halo on the given entity for the given server.
     */
    public void showHalo(String serverKey, UUID entityUuid, Identifier defId) {
        ensureLoaded();
        serverHalos
            .computeIfAbsent(serverKey, k -> new ConcurrentHashMap<>())
            .put(entityUuid, defId);
        save();
    }

    /**
     * Remove the halo from the given entity for the given server.
     */
    public void hideHalo(String serverKey, UUID entityUuid) {
        ensureLoaded();
        ConcurrentHashMap<UUID, Identifier> map = serverHalos.get(serverKey);
        if (map != null) {
            map.remove(entityUuid);
        }
        save();
    }

    /**
     * Look up the halo definition ID for an entity on a specific server.
     *
     * @return the definition ID, or {@link Optional#empty()} if none is set
     */
    public Optional<Identifier> getHalo(String serverKey, UUID entityUuid) {
        ensureLoaded();
        ConcurrentHashMap<UUID, Identifier> map = serverHalos.get(serverKey);
        if (map == null) return Optional.empty();
        return Optional.ofNullable(map.get(entityUuid));
    }

    /**
     * Return the set of entity UUIDs that have halos on the given server.
     * Used by the render pipeline to discover locally-managed halos.
     *
     * @return unmodifiable set of UUIDs (may be empty, never null)
     */
    public void restoreInto(String serverKey, ClientPort client) {
        ensureLoaded();
        var records = serverHalos.get(serverKey);
        if (records == null) return;
        var existing = client.assignments();
        records.forEach((uuid, id) -> { if (!id.equals(existing.get(uuid))) client.attach(uuid, id, false); });
    }

    public Set<UUID> getHalosForServer(String serverKey) {
        ensureLoaded();
        ConcurrentHashMap<UUID, Identifier> map = serverHalos.get(serverKey);
        if (map == null) return Set.of();
        return Collections.unmodifiableSet(map.keySet());
    }

    /**
     * Remove all halo data for a server.  Called on disconnect.
     */
    public void clearServer(String serverKey) {
        ensureLoaded();
        serverHalos.remove(serverKey);
        save();
    }

    // ------------------------------------------------------------------
    // Key construction
    // ------------------------------------------------------------------

    /**
     * Build a stable server key from a connection's remote address.
     *
     * <p>Uses {@code hostString:port} instead of {@code InetSocketAddress.toString()}
     * because the latter produces different formats depending on how the address
     * was constructed (e.g. {@code "localhost/127.0.0.1:25565"} vs
     * {@code "/127.0.0.1:25565"} vs {@code "localhost:25565"}).</p>
     *
     * @param address the remote socket address from the connection
     * @return stable {@code "host:port"} key, or {@code null} if address is not an {@link InetSocketAddress}
     */
    public static String serverKeyFromAddress(SocketAddress address) {
        if (address instanceof InetSocketAddress inet) {
            return inet.getHostString() + ":" + inet.getPort();
        }
        return null;
    }
}
