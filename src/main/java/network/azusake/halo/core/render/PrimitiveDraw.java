package network.azusake.halo.core.render;

import java.util.ArrayList;
import java.util.Objects;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Ordered legacy-material command. Blending never implies mesh-style index sorting. */
public record PrimitiveDraw(PrimitiveGeometry geometry, float[] localToView,
                            DrawBatch state, float brightness, float[] normalToView) {
    public PrimitiveDraw(PrimitiveGeometry geometry, float[] localToView, DrawBatch state, float brightness) {
        this(geometry, localToView, state, brightness, normalMatrix(localToView));
    }
    public PrimitiveDraw {
        Objects.requireNonNull(geometry);
        Objects.requireNonNull(state);
        localToView = Objects.requireNonNull(localToView).clone();
        normalToView = Objects.requireNonNull(normalToView).clone();
        if (localToView.length != 16) throw new IllegalArgumentException("Expected a 4x4 transform");
        if (normalToView.length != 9) throw new IllegalArgumentException("Expected a 3x3 normal transform");
        if (!(state.material() instanceof MaterialState.Legacy) || !state.vertices().isEmpty())
            throw new IllegalArgumentException("Primitive state must be an empty legacy batch");
    }
    @Override public float[] localToView() { return localToView.clone(); }
    @Override public float[] normalToView() { return normalToView.clone(); }
    public float transform(int index) { return localToView[index]; }

    /** CPU fallback uses this completed frame, never a second simulation/render call. */
    public DrawBatch expand() {
        Matrix4f matrix = new Matrix4f().set(localToView);
        Matrix3f normalMatrix = new Matrix3f().set(normalToView);
        var vertices = new ArrayList<DrawBatch.Vertex>(geometry.cornerCount());
        TriangleMesh mesh = geometry.mesh();
        // Shared corners are immutable. Transform each source vertex once, but preserve
        // the exact triangle/quad stream (including the distinct UV seam vertices).
        DrawBatch.Vertex[] transformed = new DrawBatch.Vertex[mesh.vertexCount()];
        Vector3f position = new Vector3f(), normal = new Vector3f();
        for (int corner = 0; corner < geometry.cornerCount(); corner++) {
            int vertex = geometry.vertexAt(corner);
            if (transformed[vertex] != null) {
                vertices.add(transformed[vertex]);
                continue;
            }
            matrix.transformPosition(mesh.x(vertex), mesh.y(vertex), mesh.z(vertex), position);
            normalMatrix.transform(geometry.normal(vertex, 0), geometry.normal(vertex, 1), geometry.normal(vertex, 2), normal);
            float squared = normal.lengthSquared();
            if (squared > 1.0e-12f && Float.isFinite(squared)) normal.normalize();
            else normal.set(0, -1, 0);
            // Matches GeometryCollector.Builder.normal's final normalization.
            float length = (float)Math.sqrt(normal.x * normal.x + normal.y * normal.y + normal.z * normal.z);
            normal.div(length);
            var result = new DrawBatch.Vertex(position.x, position.y, position.z,
                state.textured() ? mesh.u(vertex) : 0, state.textured() ? mesh.v(vertex) : 0,
                brightness, brightness, brightness, 1, normal.x, normal.y, normal.z);
            transformed[vertex] = result;
            vertices.add(result);
        }
        return new DrawBatch(state.topology(), vertices, state.texture(), state.textured(), state.cull(),
            state.blend(), state.depthTest(), state.depthWrite(), state.red(), state.green(), state.blue(),
            state.alpha(), state.material(), state.light(), state.directionalLighting());
    }
    private static float[] normalMatrix(float[] transform) {
        Matrix3f normalMatrix = new Matrix3f(new Matrix4f().set(transform));
        float determinant = normalMatrix.determinant();
        if (Float.isFinite(determinant) && Math.abs(determinant) > 1.0e-8f) normalMatrix.invert().transpose();
        else normalMatrix.identity();
        return normalMatrix.get(new float[9]);
    }
}
