package network.azusake.halo.core.render;

import java.util.List;
import network.azusake.halo.core.Identifier;

/** Ordered draw command. Vertices are already transformed into the host's camera view space. */
public record DrawBatch(Topology topology, List<Vertex> vertices, Identifier texture,
                        boolean textured, boolean cull, boolean blend, boolean depthTest,
                        boolean depthWrite, float red, float green, float blue, float alpha) {
    public DrawBatch { vertices = List.copyOf(vertices); }
    public enum Topology { QUADS, TRIANGLES }
    public record Vertex(float x, float y, float z, float u, float v,
                         float red, float green, float blue, float alpha) {}
}
