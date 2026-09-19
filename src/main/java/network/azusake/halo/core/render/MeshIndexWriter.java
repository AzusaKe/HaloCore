package network.azusake.halo.core.render;

import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.Objects;

/**
 * Reusable triangle-index writer for one immutable mesh. This class is mutable and not thread-safe;
 * hosts should retain one writer per cached GPU mesh and use it on their render owner thread.
 */
public final class MeshIndexWriter {
    private final TriangleMesh mesh;
    private final float[] centers;
    private final int[] keys;
    private int[] order;
    private int[] scratch;
    private final int[] counts = new int[256];
    private boolean prepared;
    private int depthX, depthY, depthZ, depthTranslation;
    private long sortRevision;

    public MeshIndexWriter(TriangleMesh mesh) {
        this.mesh = Objects.requireNonNull(mesh);
        int triangles = mesh.triangleCount();
        centers = new float[triangles * 3];
        keys = new int[triangles];
        order = new int[triangles];
        scratch = new int[triangles];
        for (int triangle = 0; triangle < triangles; triangle++) {
            int base = triangle * 3;
            int a = mesh.index(base), b = mesh.index(base + 1), c = mesh.index(base + 2);
            centers[base] = (float) (((double) mesh.x(a) + mesh.x(b) + mesh.x(c)) / 3);
            centers[base + 1] = (float) (((double) mesh.y(a) + mesh.y(b) + mesh.y(c)) / 3);
            centers[base + 2] = (float) (((double) mesh.z(a) + mesh.z(b) + mesh.z(c)) / 3);
        }
    }

    public TriangleMesh mesh() { return mesh; }
    public int indexCount() { return mesh.triangleCount() * 3; }

    /**
     * Prepare the same stable triangle order used by {@link #write}. The returned revision is
     * local to this writer and changes whenever the depth transform is recomputed, even if the
     * resulting indices happen to be equal. It is not a content hash or an instance identifier.
     * Hosts may reuse an uploaded EBO only when its revision, vertex layout and mirrored winding
     * all match; CPU preparation alone does not mean the GPU contains that order.
     *
     * Translation participates in the exact key: float rounding can create depth ties after a
     * translation. Source-order writes do not invalidate this workspace. Owner-thread use only.
     */
    public long prepareBackToFront(MeshDraw draw) {
        Objects.requireNonNull(draw);
        return prepareBackToFront(draw.transform(2), draw.transform(6), draw.transform(10), draw.transform(14));
    }

    /**
     * Prepare ordering from the four depth-row coefficients of the actual local-to-view
     * transform used by a host draw call. This is useful when a platform keeps part of the
     * view transform outside the immutable {@link MeshDraw}. It does not allocate or modify
     * the command and keeps platform math types out of the stable adapter API.
     */
    public long prepareBackToFrontTransform(float xCoefficient, float yCoefficient,
                                            float zCoefficient, float translationCoefficient) {
        return prepareBackToFront(xCoefficient, yCoefficient, zCoefficient, translationCoefficient);
    }

    private long prepareBackToFront(float xCoefficient, float yCoefficient,
                                    float zCoefficient, float translationCoefficient) {
        int x = Float.floatToRawIntBits(xCoefficient);
        int y = Float.floatToRawIntBits(yCoefficient);
        int z = Float.floatToRawIntBits(zCoefficient);
        int translation = Float.floatToRawIntBits(translationCoefficient);
        if (prepared && x == depthX && y == depthY && z == depthZ && translation == depthTranslation)
            return sortRevision;
        int triangles = mesh.triangleCount();
        for (int triangle = 0; triangle < triangles; triangle++) order[triangle] = triangle;
        if (triangles > 1) sort(xCoefficient, yCoefficient, zCoefficient, translationCoefficient);
        depthX = x; depthY = y; depthZ = z; depthTranslation = translation;
        prepared = true;
        return ++sortRevision;
    }

    /** Write authored triangle order without touching the sorting workspace. */
    public void writeSourceOrder(IntBuffer destination, boolean mirrored) {
        requireCapacity(destination);
        for (int triangle = 0; triangle < mesh.triangleCount(); triangle++) {
            writeTriangle(destination, triangle, mirrored, false);
        }
    }

    /**
     * Write indices for a vertex stream expanded in authored triangle-corner order.
     * Hosts need this when their vertex-format adapter derives per-triangle attributes
     * while vertices are submitted (for example Iris entity tangents).
     */
    public void writeExpandedSourceOrder(IntBuffer destination, boolean mirrored) {
        requireCapacity(destination);
        for (int triangle = 0; triangle < mesh.triangleCount(); triangle++) {
            writeTriangle(destination, triangle, mirrored, true);
        }
    }

    /** Write source-order or stable back-to-front indices into caller-owned storage. */
    public void write(IntBuffer destination, MeshDraw draw, boolean backToFront) {
        requireCapacity(destination);
        Objects.requireNonNull(draw);
        if (!backToFront) {
            writeSourceOrder(destination, draw.mirrored());
            return;
        }
        prepareBackToFront(draw);
        for (int triangle : order) writeTriangle(destination, triangle, draw.mirrored(), false);
    }

    /** Write stable back-to-front indices for an expanded triangle-corner vertex stream. */
    public void writeExpanded(IntBuffer destination, MeshDraw draw, boolean backToFront) {
        requireCapacity(destination);
        Objects.requireNonNull(draw);
        if (!backToFront) {
            writeExpandedSourceOrder(destination, draw.mirrored());
            return;
        }
        prepareBackToFront(draw);
        for (int triangle : order) writeTriangle(destination, triangle, draw.mirrored(), true);
    }

    /** Write the ordering from the most recent successful prepare call. */
    public void writePrepared(IntBuffer destination, boolean mirrored) {
        requirePrepared(destination);
        for (int triangle : order) writeTriangle(destination, triangle, mirrored, false);
    }

    /** Write the prepared ordering for a triangle-corner-expanded vertex stream. */
    public void writeExpandedPrepared(IntBuffer destination, boolean mirrored) {
        requirePrepared(destination);
        for (int triangle : order) writeTriangle(destination, triangle, mirrored, true);
    }

    private void requirePrepared(IntBuffer destination) {
        requireCapacity(destination);
        if (!prepared) throw new IllegalStateException("Back-to-front order has not been prepared");
    }

    private void requireCapacity(IntBuffer destination) {
        Objects.requireNonNull(destination);
        if (destination.remaining() < indexCount()) throw new IllegalArgumentException("Insufficient index buffer capacity");
    }

    private void writeTriangle(IntBuffer destination, int triangle, boolean mirrored, boolean expanded) {
        int base = triangle * 3;
        destination.put(expanded ? base : mesh.index(base));
        destination.put(expanded ? base + (mirrored ? 2 : 1) : mesh.index(base + (mirrored ? 2 : 1)));
        destination.put(expanded ? base + (mirrored ? 1 : 2) : mesh.index(base + (mirrored ? 1 : 2)));
    }

    private void sort(float xCoefficient, float yCoefficient, float zCoefficient, float translationCoefficient) {
        int triangles = mesh.triangleCount();
        for (int triangle = 0; triangle < triangles; triangle++) {
            int base = triangle * 3;
            float z = xCoefficient * centers[base]
                + yCoefficient * centers[base + 1]
                + zCoefficient * centers[base + 2]
                + translationCoefficient;
            int bits = Float.floatToIntBits(z);
            int signedOrder = bits ^ ((bits >> 31) & 0x7fffffff);
            keys[triangle] = signedOrder ^ Integer.MIN_VALUE;
        }
        // Stable LSD radix sort. Natural source order therefore remains the tie-breaker.
        for (int shift = 0; shift < 32; shift += 8) {
            Arrays.fill(counts, 0);
            for (int triangle : order) counts[(keys[triangle] >>> shift) & 255]++;
            int offset = 0;
            for (int i = 0; i < counts.length; i++) {
                int count = counts[i]; counts[i] = offset; offset += count;
            }
            for (int triangle : order) scratch[counts[(keys[triangle] >>> shift) & 255]++] = triangle;
            int[] swap = order; order = scratch; scratch = swap;
        }
    }
}
