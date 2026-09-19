package network.azusake.halo.core.render;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.joml.Matrix3f;
import org.joml.Vector3f;

/** Immutable frame: one ordered legacy-primitive representation, plus existing OBJ mesh commands. */
public record FrameOutput(long visualGeneration, List<DrawBatch> legacyBatches, List<MeshDraw> meshes, List<PrimitiveDraw> primitiveDraws) {
    public FrameOutput(long visualGeneration, List<DrawBatch> legacyBatches, List<MeshDraw> meshes) {
        this(visualGeneration, legacyBatches, meshes, List.of());
    }
    public FrameOutput {
        legacyBatches = List.copyOf(legacyBatches);
        meshes = List.copyOf(meshes);
        primitiveDraws = List.copyOf(primitiveDraws);
        if (!legacyBatches.isEmpty() && !primitiveDraws.isEmpty())
            throw new IllegalArgumentException("Choose one legacy primitive representation");
    }

    public static FrameOutput legacy(List<DrawBatch> batches) {
        return new FrameOutput(VisualResources.EMPTY.generation(), batches, List.of());
    }

    /** Compatibility expansion for adapters that have not implemented cached mesh submission. */
    public List<DrawBatch> expandedBatches(VisualResources resources) {
        return expandedBatches(resources, 0, 0, 1, 0);
    }

    /**
     * Compatibility expansion for a host whose GPU submission applies an additional view matrix.
     * The outer transform affects transparent index order only; expanded vertex coordinates remain
     * in the command's space because the host will still apply that matrix while drawing.
     */
    public List<DrawBatch> expandedBatches(VisualResources resources, float outerDepthX,
                                           float outerDepthY, float outerDepthZ,
                                           float outerDepthTranslation) {
        Objects.requireNonNull(resources);
        if (meshes.isEmpty() && primitiveDraws.isEmpty()) return legacyBatches;
        var result = new ArrayList<DrawBatch>(legacyBatches.size() + primitiveDraws.size() + meshes.size());
        result.addAll(legacyBatches);
        for (PrimitiveDraw draw : primitiveDraws) result.add(draw.expand());
        for (MeshDraw draw : meshes) {
            TriangleMesh mesh = resources.meshes().get(draw.model());
            if (mesh != null) result.add(expand(mesh, draw, outerDepthX, outerDepthY,
                outerDepthZ, outerDepthTranslation));
        }
        return List.copyOf(result);
    }

    private static DrawBatch expand(TriangleMesh mesh, MeshDraw draw, float outerDepthX,
                                    float outerDepthY, float outerDepthZ,
                                    float outerDepthTranslation) {
        DrawBatch.Vertex[] transformed = new DrawBatch.Vertex[mesh.vertexCount()];
        Matrix3f normalMatrix = normalMatrix(draw.localToView());
        for (int vertex = 0; vertex < transformed.length; vertex++) {
            float x = mesh.x(vertex), y = mesh.y(vertex), z = mesh.z(vertex);
            float tx = draw.transform(0) * x + draw.transform(4) * y + draw.transform(8) * z + draw.transform(12);
            float ty = draw.transform(1) * x + draw.transform(5) * y + draw.transform(9) * z + draw.transform(13);
            float tz = draw.transform(2) * x + draw.transform(6) * y + draw.transform(10) * z + draw.transform(14);
            Vector3f normal = normalMatrix.transform(
                new Vector3f(mesh.normalX(vertex), mesh.normalY(vertex), mesh.normalZ(vertex))).normalize();
            transformed[vertex] = new DrawBatch.Vertex(tx, ty, tz, mesh.u(vertex), mesh.v(vertex),
                draw.red(), draw.green(), draw.blue(), 1, normal.x, normal.y, normal.z);
        }
        var writer = new MeshIndexWriter(mesh);
        IntBuffer indices = IntBuffer.allocate(writer.indexCount());
        if (draw.blend()) {
            writer.prepareBackToFrontTransform(
                outerDepthX * draw.transform(0) + outerDepthY * draw.transform(1)
                    + outerDepthZ * draw.transform(2) + outerDepthTranslation * draw.transform(3),
                outerDepthX * draw.transform(4) + outerDepthY * draw.transform(5)
                    + outerDepthZ * draw.transform(6) + outerDepthTranslation * draw.transform(7),
                outerDepthX * draw.transform(8) + outerDepthY * draw.transform(9)
                    + outerDepthZ * draw.transform(10) + outerDepthTranslation * draw.transform(11),
                outerDepthX * draw.transform(12) + outerDepthY * draw.transform(13)
                    + outerDepthZ * draw.transform(14) + outerDepthTranslation * draw.transform(15));
            writer.writePrepared(indices, draw.mirrored());
        } else writer.write(indices, draw, draw.blend());
        indices.flip();
        var vertices = new ArrayList<DrawBatch.Vertex>(indices.remaining());
        while (indices.hasRemaining()) vertices.add(transformed[indices.get()]);
        return new DrawBatch(DrawBatch.Topology.TRIANGLES, vertices, draw.texture(), true,
            draw.cull(), draw.blend(), draw.depthTest(), draw.depthWrite(), 1, 1, 1, draw.alpha(),
            draw.material(), draw.light(), draw.directionalLighting());
    }

    private static Matrix3f normalMatrix(float[] transform) {
        Matrix3f matrix = new Matrix3f(new org.joml.Matrix4f().set(transform));
        float determinant = matrix.determinant();
        return Float.isFinite(determinant) && Math.abs(determinant) > 1.0e-8f
            ? matrix.invert().transpose() : new Matrix3f();
    }
}
