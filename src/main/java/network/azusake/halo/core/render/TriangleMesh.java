package network.azusake.halo.core.render;

import java.util.Objects;
import network.azusake.halo.core.Vec3d;

/** Immutable indexed geometry in authored local coordinates. UVs use the host's top-left convention. */
public final class TriangleMesh {
    private final float[] positions;
    private final float[] uv;
    private final int[] indices;
    private final Vec3d minimum;
    private final Vec3d maximum;

    public TriangleMesh(float[] positions, float[] uv, int[] indices) {
        this.positions = Objects.requireNonNull(positions).clone();
        this.uv = Objects.requireNonNull(uv).clone();
        this.indices = Objects.requireNonNull(indices).clone();
        if (positions.length == 0 || positions.length % 3 != 0
                || uv.length != positions.length / 3 * 2 || indices.length == 0 || indices.length % 3 != 0) {
            throw new IllegalArgumentException("Expected positions, UVs and triangle indices");
        }
        for (float n : this.positions) if (!Float.isFinite(n)) throw new IllegalArgumentException("Non-finite position");
        for (float n : this.uv) if (!Float.isFinite(n)) throw new IllegalArgumentException("Non-finite UV");
        double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
        double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        for (int index : this.indices) {
            if (index < 0 || index >= vertexCount()) throw new IllegalArgumentException("Vertex index out of bounds");
            minX = Math.min(minX, x(index)); minY = Math.min(minY, y(index)); minZ = Math.min(minZ, z(index));
            maxX = Math.max(maxX, x(index)); maxY = Math.max(maxY, y(index)); maxZ = Math.max(maxZ, z(index));
        }
        minimum = new Vec3d(minX, minY, minZ);
        maximum = new Vec3d(maxX, maxY, maxZ);
    }

    public int vertexCount() { return positions.length / 3; }
    public int triangleCount() { return indices.length / 3; }
    public int index(int corner) { return indices[corner]; }
    public float x(int vertex) { return positions[vertex * 3]; }
    public float y(int vertex) { return positions[vertex * 3 + 1]; }
    public float z(int vertex) { return positions[vertex * 3 + 2]; }
    public float u(int vertex) { return uv[vertex * 2]; }
    public float v(int vertex) { return uv[vertex * 2 + 1]; }
    public Vec3d minimum() { return minimum; }
    public Vec3d maximum() { return maximum; }
    public Vec3d center() { return minimum.add(maximum).multiply(0.5); }

    /** Scale about the authored origin, never about the bounds' center. */
    public Vec3d scaleTo(Vec3d size) {
        return new Vec3d(axisScale(size.x, maximum.x - minimum.x),
            axisScale(size.y, maximum.y - minimum.y), axisScale(size.z, maximum.z - minimum.z));
    }

    private static double axisScale(double target, double extent) {
        if (!Double.isFinite(target) || target < 0) throw new IllegalArgumentException("size must be finite and nonnegative");
        if (extent == 0) {
            if (target != 0) throw new IllegalArgumentException("A zero-extent model axis requires size=0");
            return 1;
        }
        double scale = target / extent;
        if (!Float.isFinite((float) scale)) throw new IllegalArgumentException("Model size produces an unrepresentable scale");
        return scale;
    }
}
