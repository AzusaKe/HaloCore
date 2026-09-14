package network.azusake.halo.core.render;

import java.util.List;
import network.azusake.halo.core.Identifier;

/** Ordered draw command. Vertices are already transformed into the host's camera view space. */
public record DrawBatch(Topology topology, List<Vertex> vertices, Identifier texture,
                        boolean textured, boolean cull, boolean blend, boolean depthTest,
                        boolean depthWrite, float red, float green, float blue, float alpha,
                        MaterialState material, LightSample light) {
    public DrawBatch {
        vertices = List.copyOf(vertices);
        java.util.Objects.requireNonNull(material);
        java.util.Objects.requireNonNull(light);
    }
    /** Compatibility constructor for adapters predating native block/sky light samples. */
    public DrawBatch(Topology topology, List<Vertex> vertices, Identifier texture,
                     boolean textured, boolean cull, boolean blend, boolean depthTest,
                     boolean depthWrite, float red, float green, float blue, float alpha,
                     MaterialState material) {
        this(topology, vertices, texture, textured, cull, blend, depthTest, depthWrite,
            red, green, blue, alpha, material, LightSample.UNAVAILABLE);
    }
    /** Compatibility constructor for the original billboard/ring adapters. */
    public DrawBatch(Topology topology, List<Vertex> vertices, Identifier texture,
                     boolean textured, boolean cull, boolean blend, boolean depthTest,
                     boolean depthWrite, float red, float green, float blue, float alpha) {
        this(topology, vertices, texture, textured, cull, blend, depthTest, depthWrite,
            red, green, blue, alpha, MaterialState.LEGACY, LightSample.UNAVAILABLE);
    }
    public enum Topology { QUADS, TRIANGLES }
    public record Vertex(float x, float y, float z, float u, float v,
                         float red, float green, float blue, float alpha) {}
}
