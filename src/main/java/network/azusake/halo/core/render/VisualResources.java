package network.azusake.halo.core.render;

import java.util.Map;
import network.azusake.halo.core.Identifier;

/** One immutable client resource snapshot. Missing entries are unavailable, never placeholder textures. */
public record VisualResources(long generation, Map<Identifier, TriangleMesh> meshes,
                              Map<Identifier, TextureInfo> textures) {
    public static final VisualResources EMPTY = new VisualResources(0, Map.of(), Map.of());
    public VisualResources { meshes = Map.copyOf(meshes); textures = Map.copyOf(textures); }
    /** opaque is true only when every decoded alpha sample is fully opaque. */
    public record TextureInfo(int width, int height, boolean opaque) {
        public TextureInfo {
            if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid texture dimensions");
        }
    }
}
