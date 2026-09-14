package network.azusake.halo.core.render;

import java.util.Objects;
import network.azusake.halo.core.Vec3d;

/** Immutable indexed geometry in authored local coordinates. UVs use the host's top-left convention. */
public final class TriangleMesh {
    private final float[] positions;
    private final float[] uv;
    private final float[] normals;
    private final int[] indices;
    private final Vec3d minimum;
    private final Vec3d maximum;

    public TriangleMesh(float[] positions, float[] uv, int[] indices) {
        this(positions, uv, null, indices);
    }

    /**
     * Create a mesh with authored per-vertex normals. The compatibility
     * constructor above generates area-weighted normals for older callers.
     */
    public TriangleMesh(float[] positions, float[] uv, float[] normals, int[] indices) {
        this.positions = Objects.requireNonNull(positions).clone();
        this.uv = Objects.requireNonNull(uv).clone();
        this.indices = Objects.requireNonNull(indices).clone();
        if (positions.length == 0 || positions.length % 3 != 0
                || uv.length != positions.length / 3 * 2 || indices.length == 0 || indices.length % 3 != 0) {
            throw new IllegalArgumentException("Expected positions, UVs and triangle indices");
        }
        for (float n : this.positions) if (!Float.isFinite(n)) throw new IllegalArgumentException("Non-finite position");
        for (float n : this.uv) if (!Float.isFinite(n)) throw new IllegalArgumentException("Non-finite UV");
        this.normals = normals == null ? generateNormals(this.positions, this.indices) : normals.clone();
        if (this.normals.length != this.positions.length) throw new IllegalArgumentException("Expected one normal per vertex");
        normalizeNormals(this.normals);
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
    public float normalX(int vertex) { return normals[vertex * 3]; }
    public float normalY(int vertex) { return normals[vertex * 3 + 1]; }
    public float normalZ(int vertex) { return normals[vertex * 3 + 2]; }
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

    private static float[] generateNormals(float[] positions, int[] indices) {
        float[] result = new float[positions.length];
        int vertices = positions.length / 3;
        for (int corner = 0; corner + 2 < indices.length; corner += 3) {
            int a = indices[corner], b = indices[corner + 1], c = indices[corner + 2];
            if (a < 0 || a >= vertices || b < 0 || b >= vertices || c < 0 || c >= vertices) continue;
            float ax = positions[a * 3], ay = positions[a * 3 + 1], az = positions[a * 3 + 2];
            float abx = positions[b * 3] - ax, aby = positions[b * 3 + 1] - ay, abz = positions[b * 3 + 2] - az;
            float acx = positions[c * 3] - ax, acy = positions[c * 3 + 1] - ay, acz = positions[c * 3 + 2] - az;
            float nx = aby * acz - abz * acy;
            float ny = abz * acx - abx * acz;
            float nz = abx * acy - aby * acx;
            for (int vertex : new int[]{a, b, c}) {
                result[vertex * 3] += nx;
                result[vertex * 3 + 1] += ny;
                result[vertex * 3 + 2] += nz;
            }
        }
        return result;
    }

    private static void normalizeNormals(float[] normals) {
        for (int i = 0; i < normals.length; i += 3) {
            float x = normals[i], y = normals[i + 1], z = normals[i + 2];
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
                throw new IllegalArgumentException("Non-finite normal");
            }
            double length = Math.sqrt((double) x * x + (double) y * y + (double) z * z);
            if (!(length > 1.0e-8)) {
                normals[i] = 0; normals[i + 1] = -1; normals[i + 2] = 0;
            } else {
                normals[i] = (float) (x / length);
                normals[i + 1] = (float) (y / length);
                normals[i + 2] = (float) (z / length);
            }
        }
    }
}
