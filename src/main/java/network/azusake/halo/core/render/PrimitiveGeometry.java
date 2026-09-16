package network.azusake.halo.core.render;

import java.util.Objects;
import network.azusake.halo.core.Identifier;

/** Immutable procedural geometry shared by CPU expansion and cached GPU submission. */
public final class PrimitiveGeometry {
    private final Identifier id;
    private final TriangleMesh mesh;
    private final DrawBatch.Topology topology;
    private final float[] normals;

    public PrimitiveGeometry(Identifier id, float[] positions, float[] uv, float[] normals,
                             int[] indices, DrawBatch.Topology topology) {
        this.id = Objects.requireNonNull(id);
        this.topology = Objects.requireNonNull(topology);
        this.normals = normals.clone();
        mesh = new TriangleMesh(positions, uv, normals, indices);
    }
    public Identifier id() { return id; }
    public TriangleMesh mesh() { return mesh; }
    public DrawBatch.Topology topology() { return topology; }
    public int cornerCount() { return topology == DrawBatch.Topology.QUADS ? 4 : mesh.triangleCount() * 3; }
    public int vertexAt(int corner) { return topology == DrawBatch.Topology.QUADS ? corner : mesh.index(corner); }
    /** Retain the original unnormalised circle samples until after the view transform. */
    public float normal(int vertex, int axis) { return normals[vertex * 3 + axis]; }
}
