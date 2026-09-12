package network.azusake.halo.core;

import java.util.Objects;

/** Resource identity; mirrors the existing namespace/path grammar and default namespace. */
public final class Identifier implements Comparable<Identifier> {
    private final String namespace;
    private final String path;
    public Identifier(String value) {
        this(value.contains(":") ? value.substring(0, value.indexOf(':')) : "minecraft",
            value.contains(":") ? value.substring(value.indexOf(':') + 1) : value);
    }
    public Identifier(String namespace, String path) {
        this.namespace = namespace.isEmpty() ? "minecraft" : namespace;
        this.path = Objects.requireNonNull(path);
        if (!this.namespace.matches("[a-z0-9_.-]+") || !path.matches("[a-z0-9/._-]*"))
            throw new IllegalArgumentException("Invalid resource identifier: " + namespace + ":" + path);
    }
    public static Identifier tryParse(String value) {
        try { return new Identifier(value); } catch (RuntimeException e) { return null; }
    }
    public String getNamespace() { return namespace; }
    public String getPath() { return path; }
    @Override public String toString() { return namespace + ":" + path; }
    @Override public boolean equals(Object other) {
        return other instanceof Identifier id && namespace.equals(id.namespace) && path.equals(id.path);
    }
    @Override public int hashCode() { return Objects.hash(namespace, path); }
    @Override public int compareTo(Identifier id) {
        int c = path.compareTo(id.path); return c == 0 ? namespace.compareTo(id.namespace) : c;
    }
}
