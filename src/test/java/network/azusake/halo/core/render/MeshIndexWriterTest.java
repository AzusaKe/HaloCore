package network.azusake.halo.core.render;

import java.nio.IntBuffer;
import java.util.Arrays;
import network.azusake.halo.core.Identifier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MeshIndexWriterTest {
    private static final Identifier ID = new Identifier("halo:test");
    private static MeshDraw draw(float[] matrix, boolean mirrored) {
        return new MeshDraw(ID, ID, matrix, true, true, true, false,
            1, 1, 1, 1, mirrored, new MaterialState.Mesh(null));
    }
    private static float[] identity() {
        return new float[]{1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
    }

    @Test void writesSourceOrderAndReversesMirroredWinding() {
        var mesh = new TriangleMesh(new float[]{0,0,-1, 1,0,-1, 0,1,-1, 0,0,-2, 1,0,-2, 0,1,-2},
            new float[]{0,0,1,0,0,1, 0,0,1,0,0,1}, new int[]{0,1,2,3,4,5});
        var writer = new MeshIndexWriter(mesh);
        var normal = IntBuffer.allocate(6); writer.write(normal, draw(identity(), false), false);
        var mirrored = IntBuffer.allocate(6); writer.write(mirrored, draw(identity(), true), false);
        assertArrayEquals(new int[]{0,1,2,3,4,5}, normal.array());
        assertArrayEquals(new int[]{0,2,1,3,5,4}, mirrored.array());
    }

    @Test void writesTriangleCornerIndicesForExpandedVertexStreams() {
        var mesh = new TriangleMesh(new float[]{0,0,-1, 1,0,-1, 0,1,-1, 1,1,-2},
            new float[]{0,0,1,0,0,1,1,1}, new int[]{2,0,1, 1,3,2});
        var writer = new MeshIndexWriter(mesh);
        var normal = IntBuffer.allocate(6);
        writer.writeExpandedSourceOrder(normal, false);
        assertArrayEquals(new int[]{0,1,2,3,4,5}, normal.array());
        var mirrored = IntBuffer.allocate(6);
        writer.writeExpanded(mirrored, draw(identity(), true), false);
        assertArrayEquals(new int[]{0,2,1,3,5,4}, mirrored.array());
    }

    @Test void expandedVertexStreamsReuseTheSameStableTriangleSort() {
        var mesh = new TriangleMesh(new float[]{
            0,0,-1, 1,0,-1, 0,1,-1,
            0,0,-4, 1,0,-4, 0,1,-4},
            new float[]{0,0,1,0,0,1, 0,0,1,0,0,1}, new int[]{0,1,2,3,4,5});
        var writer = new MeshIndexWriter(mesh);
        var output = IntBuffer.allocate(6);
        writer.writeExpanded(output, draw(identity(), false), true);
        assertArrayEquals(new int[]{3,4,5,0,1,2}, output.array());
    }

    @Test void stableDepthSortMatchesFarToNearAndReusesWriter() {
        var mesh = new TriangleMesh(new float[]{
            0,0,-1, 1,0,-1, 0,1,-1,
            0,0,-4, 1,0,-4, 0,1,-4,
            0,0,-1, -1,0,-1, 0,-1,-1},
            new float[]{0,0,1,0,0,1, 0,0,1,0,0,1, 0,0,1,0,0,1}, new int[]{0,1,2,3,4,5,6,7,8});
        var writer = new MeshIndexWriter(mesh);
        var output = IntBuffer.allocate(9);
        writer.write(output, draw(identity(), false), true);
        assertArrayEquals(new int[]{3,4,5,0,1,2,6,7,8}, output.array());
        output.clear();
        float[] translated = identity(); translated[14] = 8;
        writer.write(output, draw(translated, false), true);
        assertArrayEquals(new int[]{3,4,5,0,1,2,6,7,8}, output.array());
        assertThrows(IllegalArgumentException.class,
            () -> writer.write(IntBuffer.allocate(8), draw(identity(), false), true));
    }

    @Test void matchesLegacyTriangleOrderingAcrossViewsAndRetainsPrimitiveWorkspace() throws Exception {
        var mesh = new TriangleMesh(new float[]{
            -3,0,-1, -2,0,-1, -3,1,-1,
             2,0,-4,  3,0,-4,  2,1,-4,
             0,3,-2,  1,3,-2,  0,4,-2,
             4,0,-1,  5,0,-1,  4,1,-1},
            new float[]{0,0,1,0,0,1, 0,0,1,0,0,1, 0,0,1,0,0,1, 0,0,1,0,0,1},
            new int[]{0,1,2,3,4,5,6,7,8,9,10,11});
        var writer = new MeshIndexWriter(mesh);
        var orderField = MeshIndexWriter.class.getDeclaredField("order");
        var scratchField = MeshIndexWriter.class.getDeclaredField("scratch");
        orderField.setAccessible(true); scratchField.setAccessible(true);
        Object orderWorkspace = orderField.get(writer), scratchWorkspace = scratchField.get(writer);
        float[][] views = {
            identity(),
            {0,0,-1,0, 0,1,0,0, 1,0,0,0, 0,0,0,1},
            {1,0,0,0, 0,0,1,0, 0,-1,0,0, 3,-2,7,1},
            {-1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,-5,1}
        };
        for (float[] view : views) for (boolean mirrored : new boolean[]{false, true}) {
            MeshDraw command = draw(view, mirrored);
            IntBuffer actual = IntBuffer.allocate(writer.indexCount());
            writer.write(actual, command, true);
            assertArrayEquals(legacyOrder(mesh, command), actual.array());
        }
        assertSame(orderWorkspace, orderField.get(writer));
        assertSame(scratchWorkspace, scratchField.get(writer));
    }

    private static int[] legacyOrder(TriangleMesh mesh, MeshDraw draw) {
        long[] order = new long[mesh.triangleCount()];
        for (int triangle = 0; triangle < order.length; triangle++) {
            int base = triangle * 3;
            double z = 0;
            for (int corner = 0; corner < 3; corner++) {
                int vertex = mesh.index(base + corner);
                z += draw.transform(2) * mesh.x(vertex) + draw.transform(6) * mesh.y(vertex)
                    + draw.transform(10) * mesh.z(vertex) + draw.transform(14);
            }
            int bits = Float.floatToIntBits((float) (z / 3));
            int key = bits ^ ((bits >> 31) & 0x7fffffff);
            order[triangle] = ((long) key << 32) | (triangle & 0xffffffffL);
        }
        Arrays.sort(order);
        int[] result = new int[mesh.triangleCount() * 3];
        int output = 0;
        for (long entry : order) {
            int base = (int) entry * 3;
            result[output++] = mesh.index(base);
            result[output++] = mesh.index(base + (draw.mirrored() ? 2 : 1));
            result[output++] = mesh.index(base + (draw.mirrored() ? 1 : 2));
        }
        return result;
    }
}
