package network.azusake.halo.core.render;

import java.util.List;
import network.azusake.halo.core.Identifier;

/** Ordered draw command. Vertices are already transformed into the host's camera view space. */
public record DrawBatch(Topology topology, List<Vertex> vertices, Identifier texture,
                        boolean textured, boolean cull, boolean blend, boolean depthTest,
                        boolean depthWrite, float red, float green, float blue, float alpha,
                        MaterialState material, LightSample light, boolean directionalLighting) {
    public DrawBatch {
        vertices = List.copyOf(vertices);
        java.util.Objects.requireNonNull(material);
        java.util.Objects.requireNonNull(light);
    }
    /** Compatibility constructor for adapters predating normal-based directional lighting. */
    public DrawBatch(Topology topology, List<Vertex> vertices, Identifier texture,
                     boolean textured, boolean cull, boolean blend, boolean depthTest,
                     boolean depthWrite, float red, float green, float blue, float alpha,
                     MaterialState material, LightSample light) {
        this(topology, vertices, texture, textured, cull, blend, depthTest, depthWrite,
            red, green, blue, alpha, material, light, false);
    }
    /** Compatibility constructor for adapters predating native block/sky light samples. */
    public DrawBatch(Topology topology, List<Vertex> vertices, Identifier texture,
                     boolean textured, boolean cull, boolean blend, boolean depthTest,
                     boolean depthWrite, float red, float green, float blue, float alpha,
                     MaterialState material) {
        this(topology, vertices, texture, textured, cull, blend, depthTest, depthWrite,
            red, green, blue, alpha, material, LightSample.UNAVAILABLE, false);
    }
    /** Compatibility constructor for the original billboard/ring adapters. */
    public DrawBatch(Topology topology, List<Vertex> vertices, Identifier texture,
                     boolean textured, boolean cull, boolean blend, boolean depthTest,
                     boolean depthWrite, float red, float green, float blue, float alpha) {
        this(topology, vertices, texture, textured, cull, blend, depthTest, depthWrite,
            red, green, blue, alpha, MaterialState.LEGACY, LightSample.UNAVAILABLE, false);
    }
    public enum Topology { QUADS, TRIANGLES }
    public record Vertex(float x, float y, float z, float u, float v,
                         float red, float green, float blue, float alpha,
                         float normalX, float normalY, float normalZ) {
        /** Compatibility constructor for geometry emitted before normals were part of the contract. */
        public Vertex(float x, float y, float z, float u, float v,
                      float red, float green, float blue, float alpha) {
            this(x, y, z, u, v, red, green, blue, alpha, 0, -1, 0);
        }
    }
}
