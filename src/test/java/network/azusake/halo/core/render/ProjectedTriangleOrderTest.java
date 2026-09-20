package network.azusake.halo.core.render;

import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import network.azusake.halo.core.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProjectedTriangleOrderTest {
    private static final Identifier ID = new Identifier("halo:ring");

    private static TriangleMesh ring() throws Exception {
        try (var in = ProjectedTriangleOrderTest.class.getResourceAsStream("/meshes/translucent-ring.obj")) {
            return ObjMeshLoader.parse(ID, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test void obliqueRingMatchesIndependentRayVisibilityWhereCenterSortDoesNot() throws Exception {
        TriangleMesh mesh = ring();
        var writer = new MeshIndexWriter(mesh);
        int baselineErrors = 0;
        for (boolean perspective : new boolean[]{true, false}) {
          for (float distance : new float[]{1.3f, 2.2f}) for (float height : new float[]{-.27f, .05f, .27f, 1.1f}) {
            for (int angle = 0; angle < 8; angle++) {
                double yaw = .13 + angle * Math.PI / 4;
                float[] view = new Matrix4f().lookAt((float) (distance * Math.cos(yaw)), height,
                    (float) (distance * Math.sin(yaw)), 0, 0, 0, 0, 1, 0).get(new float[16]);
                writer.prepareBackToFrontTransform(view[2], view[6], view[10], view[14]);
                int[] oldOrder = ranks(writer);
                writer.prepareBackToFrontView(view, perspective);
                int[] newOrder = ranks(writer);
                int[] errors = visibilityErrors(mesh, view, perspective, oldOrder, newOrder);
                baselineErrors += errors[0];
                assertEquals(0, errors[1], "incorrect overlap at angle " + angle + ", perspective=" + perspective
                    + ", distance=" + distance + ", height=" + height);
            }
          }
        }
        assertTrue(baselineErrors > 0, "fixture must reproduce the original triangle-order defect");
        System.out.println("Ring visibility: center-sort inversions=" + baselineErrors + ", corrected=0");
    }

    @Test void fullViewCacheTracksLateralMovementProjectionAndLegacyTransitions() throws Exception {
        var writer = new MeshIndexWriter(ring());
        float[] view = new Matrix4f().translation(0, 0, -3).get(new float[16]);
        long first = writer.prepareBackToFrontView(view, true);
        assertEquals(first, writer.prepareBackToFrontView(view.clone(), true));
        writer.writeSourceOrder(IntBuffer.allocate(writer.indexCount()), false);
        assertEquals(first, writer.prepareBackToFrontView(view, true));
        view[12] = 2;
        long moved = writer.prepareBackToFrontView(view, true);
        assertNotEquals(first, moved);
        long parallel = writer.prepareBackToFrontView(view, false);
        assertNotEquals(moved, parallel);
        long legacy = writer.prepareBackToFrontTransform(0, 0, 1, -3);
        assertNotEquals(parallel, legacy);
        assertNotEquals(legacy, writer.prepareBackToFrontView(view, true));
        assertThrows(IllegalArgumentException.class, () -> writer.prepareBackToFrontView(new float[3], true));
    }

    @Test void expandedMirroredAndCpuFallbackConsumeIdenticalCorrectedOrder() throws Exception {
        TriangleMesh mesh = ring();
        float[] local = new Matrix4f().rotateY(.4f).scale(-1, 1, 1).get(new float[16]);
        float[] outer = new Matrix4f().lookAt(2.1f, .2f, 1.1f, 0, 0, 0, 0, 1, 0).get(new float[16]);
        float[] composed = new Matrix4f().set(outer).mul(new Matrix4f().set(local)).get(new float[16]);
        var writer = new MeshIndexWriter(mesh);
        writer.prepareBackToFrontView(composed, true);
        var unique = IntBuffer.allocate(writer.indexCount()); writer.writePrepared(unique, true);
        var expanded = IntBuffer.allocate(writer.indexCount()); writer.writeExpandedPrepared(expanded, true);
        for (int i = 0; i < unique.capacity(); i++) assertEquals(unique.get(i), mesh.index(expanded.get(i)));
        var draw = new MeshDraw(ID, ID, local, false, true, true, false, 1, 1, 1, .5f,
            true, new MaterialState.Mesh(null));
        var cpu = new FrameOutput(1, List.of(), List.of(draw)).expandedBatches(
            new VisualResources(1, Map.of(ID, mesh), Map.of()), outer, true).get(0);
        for (int i = 0; i < unique.capacity(); i++) {
            int v = unique.get(i);
            assertEquals(mesh.u(v), cpu.vertices().get(i).u());
            assertEquals(mesh.v(v), cpu.vertices().get(i).v());
        }
    }

    @Test void eyePlaneDegeneracyIntersectionsAndWorkBudgetKeepEveryTriangle() {
        float[] positions = {-1,-1,-2, 1,-1,-2, 0,1,-2, -1,-1,-3, 1,-1,-1, 0,1,0};
        int[] indices = new int[800 * 3];
        for (int i = 0; i < indices.length; i++) indices[i] = i % 6;
        var mesh = new TriangleMesh(positions, new float[12], indices);
        var writer = new MeshIndexWriter(mesh);
        for (boolean perspective : new boolean[]{true, false}) {
            writer.prepareBackToFrontView(new Matrix4f().get(new float[16]), perspective);
            int[] rank = ranks(writer);
            Arrays.sort(rank);
            for (int i = 0; i < rank.length; i++) assertEquals(i, rank[i]);
        }
        writer.prepareBackToFrontView(new Matrix4f().scale(0).get(new float[16]), true);
        assertEquals(800, ranks(writer).length);
    }

    private static int[] ranks(MeshIndexWriter writer) {
        var expanded = IntBuffer.allocate(writer.indexCount()); writer.writeExpandedPrepared(expanded, false);
        int[] rank = new int[writer.mesh().triangleCount()];
        for (int i = 0; i < rank.length; i++) rank[expanded.get(i * 3) / 3] = i;
        return rank;
    }

    // Independent ray/triangle reference: no screen polygon clipping, graph, or center comparison.
    private static int[] visibilityErrors(TriangleMesh mesh, float[] view, boolean perspective,
                                          int[] oldOrder, int[] newOrder) {
        int[] errors = new int[2];
        Vector3d[] vertices = new Vector3d[mesh.vertexCount()];
        org.joml.Matrix4d m = new org.joml.Matrix4d().set(new Matrix4f().set(view));
        for (int i = 0; i < vertices.length; i++) vertices[i] = m.transformPosition(new Vector3d(mesh.x(i), mesh.y(i), mesh.z(i)));
        double[] hits = new double[mesh.triangleCount()];
        for (int yi = 0; yi < 39; yi++) for (int xi = 0; xi < 89; xi++) {
            double px = (xi - 44.17) / 44 * (perspective ? .65 : 1.2);
            double py = (yi - 19.31) / 19 * (perspective ? .16 : .3);
            Vector3d origin = perspective ? new Vector3d() : new Vector3d(px, py, 0);
            Vector3d direction = perspective ? new Vector3d(px, py, -1) : new Vector3d(0, 0, -1);
            Arrays.fill(hits, Double.NaN);
            for (int t = 0; t < hits.length; t++) hits[t] = hit(origin, direction,
                vertices[mesh.index(t * 3)], vertices[mesh.index(t * 3 + 1)], vertices[mesh.index(t * 3 + 2)]);
            for (int a = 0; a < hits.length; a++) if (Double.isFinite(hits[a]))
                for (int b = a + 1; b < hits.length; b++) if (Double.isFinite(hits[b]) && Math.abs(hits[a] - hits[b]) > 1e-6) {
                    if ((hits[a] > hits[b]) != (oldOrder[a] < oldOrder[b])) errors[0]++;
                    if ((hits[a] > hits[b]) != (newOrder[a] < newOrder[b])) errors[1]++;
                }
        }
        return errors;
    }

    private static double hit(Vector3d origin, Vector3d direction, Vector3d a, Vector3d b, Vector3d c) {
        var e1 = new Vector3d(b).sub(a); var e2 = new Vector3d(c).sub(a);
        var p = new Vector3d(direction).cross(e2);
        double det = e1.dot(p);
        if (Math.abs(det) < 1e-12) return Double.NaN;
        var s = new Vector3d(origin).sub(a);
        double u = s.dot(p) / det;
        if (u <= 1e-7 || u >= 1 - 1e-7) return Double.NaN;
        var q = s.cross(e1);
        double v = direction.dot(q) / det;
        if (v <= 1e-7 || u + v >= 1 - 1e-7) return Double.NaN;
        double distance = e2.dot(q) / det;
        return distance > 0 ? distance : Double.NaN;
    }
}
