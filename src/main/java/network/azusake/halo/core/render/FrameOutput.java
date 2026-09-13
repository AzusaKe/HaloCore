package network.azusake.halo.core.render;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Immutable output for one frame: legacy immediate batches followed by ordered mesh commands. */
public record FrameOutput(long visualGeneration, List<DrawBatch> legacyBatches, List<MeshDraw> meshes) {
    public FrameOutput {
        legacyBatches = List.copyOf(legacyBatches);
        meshes = List.copyOf(meshes);
    }

    public static FrameOutput legacy(List<DrawBatch> batches) {
        return new FrameOutput(VisualResources.EMPTY.generation(), batches, List.of());
    }

    /** Compatibility expansion for adapters that have not implemented cached mesh submission. */
    public List<DrawBatch> expandedBatches(VisualResources resources) {
        Objects.requireNonNull(resources);
        if (meshes.isEmpty()) return legacyBatches;
        var result = new ArrayList<DrawBatch>(legacyBatches.size() + meshes.size());
        result.addAll(legacyBatches);
        for (MeshDraw draw : meshes) {
            TriangleMesh mesh = resources.meshes().get(draw.model());
            if (mesh != null) result.add(expand(mesh, draw));
        }
        return List.copyOf(result);
    }

    private static DrawBatch expand(TriangleMesh mesh, MeshDraw draw) {
        DrawBatch.Vertex[] transformed = new DrawBatch.Vertex[mesh.vertexCount()];
        for (int vertex = 0; vertex < transformed.length; vertex++) {
            float x = mesh.x(vertex), y = mesh.y(vertex), z = mesh.z(vertex);
            float tx = draw.transform(0) * x + draw.transform(4) * y + draw.transform(8) * z + draw.transform(12);
            float ty = draw.transform(1) * x + draw.transform(5) * y + draw.transform(9) * z + draw.transform(13);
            float tz = draw.transform(2) * x + draw.transform(6) * y + draw.transform(10) * z + draw.transform(14);
            transformed[vertex] = new DrawBatch.Vertex(tx, ty, tz, mesh.u(vertex), mesh.v(vertex),
                draw.red(), draw.green(), draw.blue(), 1);
        }
        var writer = new MeshIndexWriter(mesh);
        IntBuffer indices = IntBuffer.allocate(writer.indexCount());
        writer.write(indices, draw, draw.blend());
        indices.flip();
        var vertices = new ArrayList<DrawBatch.Vertex>(indices.remaining());
        while (indices.hasRemaining()) vertices.add(transformed[indices.get()]);
        return new DrawBatch(DrawBatch.Topology.TRIANGLES, vertices, draw.texture(), true,
            draw.cull(), draw.blend(), draw.depthTest(), draw.depthWrite(), 1, 1, 1, draw.alpha(), draw.material());
    }
}
