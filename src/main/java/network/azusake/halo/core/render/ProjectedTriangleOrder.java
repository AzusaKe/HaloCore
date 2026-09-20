package network.azusake.halo.core.render;

import java.util.Arrays;

/** Refines the center-depth order with visibility constraints at actual projected overlaps.
 * Workspace belongs to one MeshIndexWriter. No platform types or per-pair allocations.
 */
final class ProjectedTriangleOrder {
    // Bound pathological overlapping geometry; a frame must not do quadratic unbounded work.
    private static final int MAX_PAIRS = 262144;
    private final TriangleMesh mesh;
    private final double[] x, y, depth, bounds, areas, planes;
    private final boolean[] valid;
    private final long[] sweep;
    private final int[] heads, incoming, rank, heap, result;
    private int[] targets = new int[256], next = new int[256];
    private int edges, heapSize;
    // Spare slots also cover duplicate endpoints when clipping exactly along a shared edge.
    private final double[] clipX = new double[16], clipY = new double[16];
    private final double[] tempX = new double[16], tempY = new double[16];

    ProjectedTriangleOrder(TriangleMesh mesh) {
        this.mesh = mesh;
        int n = mesh.triangleCount();
        x = new double[n * 3]; y = new double[n * 3]; depth = new double[n * 3];
        bounds = new double[n * 4]; areas = new double[n]; planes = new double[n * 3];
        valid = new boolean[n]; sweep = new long[n];
        heads = new int[n]; incoming = new int[n]; rank = new int[n];
        heap = new int[n]; result = new int[n];
    }

    void refine(int[] order, float[] m, boolean perspective) {
        int n = order.length;
        Arrays.fill(heads, -1); Arrays.fill(incoming, 0);
        edges = 0; heapSize = 0;
        for (int t = 0; t < n; t++) {
            valid[t] = true;
            double minX = Double.POSITIVE_INFINITY, minY = minX;
            double maxX = Double.NEGATIVE_INFINITY, maxY = maxX;
            for (int k = 0; k < 3; k++) {
                int v = mesh.index(t * 3 + k), p = t * 3 + k;
                double vx = mesh.x(v), vy = mesh.y(v), vz = mesh.z(v);
                double px = m[0] * vx + m[4] * vy + m[8] * vz + m[12];
                double py = m[1] * vx + m[5] * vy + m[9] * vz + m[13];
                double pz = m[2] * vx + m[6] * vy + m[10] * vz + m[14];
                // Crossing the eye plane needs clipping before a finite screen-space order exists.
                if (perspective && pz >= -1e-6) valid[t] = false;
                x[p] = perspective ? px / -pz : px;
                y[p] = perspective ? py / -pz : py;
                depth[p] = perspective ? 1 / -pz : pz;
                if (!Double.isFinite(x[p]) || !Double.isFinite(y[p]) || !Double.isFinite(depth[p])) valid[t] = false;
                minX = Math.min(minX, x[p]); maxX = Math.max(maxX, x[p]);
                minY = Math.min(minY, y[p]); maxY = Math.max(maxY, y[p]);
            }
            int p = t * 3;
            areas[t] = cross(x[p + 1] - x[p], y[p + 1] - y[p], x[p + 2] - x[p], y[p + 2] - y[p]);
            if (areas[t] == 0) valid[t] = false;
            double du = depth[p + 1] - depth[p], dv = depth[p + 2] - depth[p];
            planes[p] = (du * (y[p + 2] - y[p]) - dv * (y[p + 1] - y[p])) / areas[t];
            planes[p + 1] = ((x[p + 1] - x[p]) * dv - (x[p + 2] - x[p]) * du) / areas[t];
            planes[p + 2] = depth[p] - planes[p] * x[p] - planes[p + 1] * y[p];
            bounds[t * 4] = minX; bounds[t * 4 + 1] = maxX;
            bounds[t * 4 + 2] = minY; bounds[t * 4 + 3] = maxY;
            int bits = Float.floatToIntBits((float) minX);
            int key = bits ^ ((bits >> 31) & 0x7fffffff);
            sweep[t] = ((long) key << 32) | (t & 0xffffffffL);
            rank[order[t]] = t;
        }
        Arrays.sort(sweep);
        int pairs = 0;
        for (int i = 0; i < n; i++) {
            int a = (int) sweep[i];
            if (!valid[a]) continue;
            for (int j = i + 1; j < n; j++) {
                int b = (int) sweep[j];
                // Float sweep keys can tie for distinct doubles; use float bounds conservatively.
                if ((float) bounds[b * 4] > (float) bounds[a * 4 + 1]) break;
                if (++pairs > MAX_PAIRS) return; // Keep the complete original order on budget exhaustion.
                if (!valid[b] || bounds[a * 4 + 2] >= bounds[b * 4 + 3]
                        || bounds[b * 4 + 2] >= bounds[a * 4 + 3]) continue;
                int relation = overlapOrder(a, b);
                if (relation < 0) edge(a, b);
                else if (relation > 0) edge(b, a);
            }
        }
        for (int t : order) if (incoming[t] == 0) push(t);
        for (int i = 0; i < n; i++) {
            // Intersecting/cyclic geometry cannot always be rendered by triangle reordering.
            // Preserve the old complete order rather than dropping faces or looping forever.
            if (heapSize == 0) return;
            int t = pop(); result[i] = t;
            for (int e = heads[t]; e >= 0; e = next[e]) if (--incoming[targets[e]] == 0) push(targets[e]);
        }
        System.arraycopy(result, 0, order, 0, n);
    }

    /** -1: a behind b throughout their positive-area overlap; +1: b behind a. */
    private int overlapOrder(int a, int b) {
        int count = 3, ap = a * 3, bp = b * 3;
        // Most separated surfaces do not need polygon construction. A linear depth difference
        // with one sign across either whole triangle also has that sign across their overlap.
        int simple = planeOrder(a, b, a);
        if (simple == 0) simple = planeOrder(a, b, b);
        if (simple != 0) return separated(a, b) || separated(b, a) ? 0 : simple;
        System.arraycopy(x, ap, clipX, 0, 3); System.arraycopy(y, ap, clipY, 0, 3);
        double sign = Math.signum(areas[b]);
        for (int edge = 0; edge < 3 && count > 0; edge++) {
            int s = bp + edge, e = bp + (edge + 1) % 3, out = 0;
            double dx = x[e] - x[s], dy = y[e] - y[s];
            for (int i = 0; i < count; i++) {
                int j = (i + count - 1) % count;
                double d0 = sign * cross(dx, dy, clipX[j] - x[s], clipY[j] - y[s]);
                double d1 = sign * cross(dx, dy, clipX[i] - x[s], clipY[i] - y[s]);
                if ((d0 >= 0) != (d1 >= 0)) {
                    double f = d0 / (d0 - d1);
                    tempX[out] = clipX[j] + f * (clipX[i] - clipX[j]);
                    tempY[out++] = clipY[j] + f * (clipY[i] - clipY[j]);
                }
                if (d1 >= 0) { tempX[out] = clipX[i]; tempY[out++] = clipY[i]; }
            }
            count = out;
            System.arraycopy(tempX, 0, clipX, 0, count); System.arraycopy(tempY, 0, clipY, 0, count);
        }
        if (count < 3) return 0;
        double overlapArea = 0;
        for (int i = 1; i + 1 < count; i++) overlapArea += cross(clipX[i] - clipX[0], clipY[i] - clipY[0],
            clipX[i + 1] - clipX[0], clipY[i + 1] - clipY[0]);
        if (Math.abs(overlapArea) <= Math.min(Math.abs(areas[a]), Math.abs(areas[b])) * 1e-10) return 0;
        int relation = 0;
        for (int i = 0; i < count; i++) {
            double da = sample(a, clipX[i], clipY[i]), db = sample(b, clipX[i], clipY[i]);
            double tolerance = Math.max(Math.abs(da), Math.abs(db)) * 1e-9 + 1e-12;
            int current = da < db - tolerance ? -1 : da > db + tolerance ? 1 : 0;
            if (current != 0) {
                if (relation != 0 && relation != current) return 0;
                relation = current;
            }
        }
        return relation;
    }

    private double sample(int t, double px, double py) {
        int p = t * 3;
        return planes[p] * px + planes[p + 1] * py + planes[p + 2];
    }

    private int planeOrder(int a, int b, int domain) {
        int relation = 0;
        for (int i = domain * 3; i < domain * 3 + 3; i++) {
            double da = sample(a, x[i], y[i]), db = sample(b, x[i], y[i]);
            double tolerance = Math.max(Math.abs(da), Math.abs(db)) * 1e-9 + 1e-12;
            int sign = da < db - tolerance ? -1 : da > db + tolerance ? 1 : 0;
            if (sign == 0 || (relation != 0 && relation != sign)) return 0;
            relation = sign;
        }
        return relation;
    }

    private boolean separated(int a, int b) {
        int ap = a * 3, bp = b * 3;
        double sign = Math.signum(areas[a]);
        for (int k = 0; k < 3; k++) {
            int s = ap + k, e = ap + (k + 1) % 3;
            double dx = x[e] - x[s], dy = y[e] - y[s];
            boolean outside = true;
            for (int i = bp; i < bp + 3; i++)
                if (sign * cross(dx, dy, x[i] - x[s], y[i] - y[s]) > Math.abs(areas[a]) * 1e-10) {
                    outside = false; break;
                }
            if (outside) return true;
        }
        return false;
    }

    private static double cross(double ax, double ay, double bx, double by) { return ax * by - ay * bx; }

    private void edge(int from, int to) {
        if (edges == targets.length) {
            targets = Arrays.copyOf(targets, targets.length * 2); next = Arrays.copyOf(next, next.length * 2);
        }
        targets[edges] = to; next[edges] = heads[from]; heads[from] = edges++; incoming[to]++;
    }

    private void push(int t) {
        int i = heapSize++;
        while (i > 0) {
            int p = (i - 1) / 2;
            if (rank[heap[p]] <= rank[t]) break;
            heap[i] = heap[p]; i = p;
        }
        heap[i] = t;
    }

    private int pop() {
        int first = heap[0], last = heap[--heapSize], i = 0;
        while (i * 2 + 1 < heapSize) {
            int child = i * 2 + 1;
            if (child + 1 < heapSize && rank[heap[child + 1]] < rank[heap[child]]) child++;
            if (rank[last] <= rank[heap[child]]) break;
            heap[i] = heap[child]; i = child;
        }
        heap[i] = last;
        return first;
    }
}
