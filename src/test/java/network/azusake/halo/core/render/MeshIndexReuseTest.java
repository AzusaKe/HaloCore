package network.azusake.halo.core.render;

import java.nio.IntBuffer;
import java.util.Random;
import network.azusake.halo.core.Identifier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MeshIndexReuseTest {
    private static final Identifier ID = new Identifier("halo:test");
    private static float[] identity() { return new float[]{1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1}; }
    private static MeshDraw draw(float[] m, boolean mirrored) {
        return new MeshDraw(ID, ID, m, true, true, true, false,
            1, 1, 1, 1, mirrored, new MaterialState.Mesh(null));
    }
    private static TriangleMesh roundingMesh() {
        float far = Math.nextDown(-1f);
        return new TriangleMesh(new float[]{0,0,-1, 1,0,-1, 0,1,-1, 0,0,far, 1,0,far, 0,1,far},
            new float[12], new int[]{0,1,2,3,4,5});
    }

    @Test void translationMustInvalidateEvenThoughMathematicalDepthDifferencesAreUnchanged() {
        var writer = new MeshIndexWriter(roundingMesh());
        float[] m = identity();
        var first = draw(m, false);
        long before = writer.prepareBackToFront(first);
        var output = IntBuffer.allocate(6);
        writer.write(output, first, true);
        assertArrayEquals(new int[]{3,4,5,0,1,2}, output.array());
        m[14] = -100;
        var translated = draw(m, false);
        assertNotEquals(before, writer.prepareBackToFront(translated));
        output.clear(); writer.write(output, translated, true);
        assertArrayEquals(new int[]{0,1,2,3,4,5}, output.array());
    }

    @Test void exactDepthKeyReusesAcrossLayoutsMirroringAndUnrelatedMatrixChanges() {
        var writer = new MeshIndexWriter(roundingMesh());
        float[] m = identity();
        long revision = writer.prepareBackToFront(draw(m, false));
        m[0] = -3; m[12] = 700; m[13] = -20;
        var mirrored = draw(m, true);
        assertEquals(revision, writer.prepareBackToFront(mirrored));
        var output = IntBuffer.allocate(6);
        writer.writeExpanded(output, mirrored, true);
        assertArrayEquals(new int[]{3,5,4,0,2,1}, output.array());
        output.clear(); writer.writeSourceOrder(output, false);
        output.clear(); writer.writeExpandedSourceOrder(output, true);
        assertEquals(revision, writer.prepareBackToFront(mirrored));
        m[2] = -0f;
        assertNotEquals(revision, writer.prepareBackToFront(draw(m, true)));
    }

    @Test void returningToAnEarlierInstanceDoesNotReuseItsOldRevision() {
        var writer = new MeshIndexWriter(roundingMesh());
        float[] a = identity(), b = identity(); b[10] = -1;
        long first = writer.prepareBackToFront(draw(a, false));
        long second = writer.prepareBackToFront(draw(b, false));
        long third = writer.prepareBackToFront(draw(a, false));
        assertNotEquals(first, second);
        assertNotEquals(first, third);
        assertEquals(third, writer.prepareBackToFront(draw(a, false)));
    }

    @Test void randomizedAndDegenerateTransformsMatchFrozen231IndicesExactly() {
        Random random = new Random(231);
        float[] positions = new float[180];
        for (int i = 0; i < positions.length; i++) positions[i] = random.nextFloat() * 20 - 10;
        int[] authored = new int[303];
        for (int i = 0; i < authored.length; i++) authored[i] = random.nextInt(60);
        var mesh = new TriangleMesh(positions, new float[120], authored);
        var baseline = new BaselineMeshIndexWriter(mesh);
        var current = new MeshIndexWriter(mesh);
        float[] m = identity();
        for (int iteration = 0; iteration < 1000; iteration++) {
            if (iteration % 3 != 0) {
                for (int i = 0; i < m.length; i++) m[i] = random.nextFloat() * 8 - 4;
            }
            if (iteration % 17 == 0) m[2] = m[6] = m[10] = 0;
            if (iteration % 19 == 0) m[14] = 29_000_000;
            if (iteration % 23 == 0) m[10] = Float.MAX_VALUE; // preserves overflow/NaN sorting too
            var command = draw(m, iteration % 2 == 0);
            for (boolean expanded : new boolean[]{false, true}) for (boolean sorted : new boolean[]{false, true}) {
                var expected = IntBuffer.allocate(authored.length + 3); expected.position(2);
                var actual = IntBuffer.allocate(authored.length + 3); actual.position(2);
                if (expanded) { baseline.writeExpanded(expected, command, sorted); current.writeExpanded(actual, command, sorted); }
                else { baseline.write(expected, command, sorted); current.write(actual, command, sorted); }
                assertEquals(expected.position(), actual.position());
                assertArrayEquals(expected.array(), actual.array(), "iteration " + iteration);
            }
        }
        assertThrows(IllegalArgumentException.class, () -> current.write(IntBuffer.allocate(1), draw(m, false), true));
        assertThrows(NullPointerException.class, () -> current.prepareBackToFront(null));
    }
}
