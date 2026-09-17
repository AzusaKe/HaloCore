package compat;

import java.nio.IntBuffer;
import java.util.Arrays;
import network.azusake.halo.core.Identifier;
import network.azusake.halo.core.render.*;

/** Compiled against the frozen 2.3.1 writer, executed with only the current core jar. */
public final class LegacyMeshIndexConsumer {
    public static void main(String[] args) {
        var mesh = new TriangleMesh(new float[]{0,0,0, 1,0,0, 0,1,0}, new float[6], new int[]{2,0,1});
        var writer = new MeshIndexWriter(mesh);
        var id = new Identifier("halo:test");
        var draw = new MeshDraw(id, id, new float[]{1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1},
            true, true, true, false, 1,1,1,1, false, new MaterialState.Mesh(null));
        if (writer.mesh() != mesh || writer.indexCount() != 3) throw new AssertionError();
        var out = IntBuffer.allocate(3);
        writer.writeSourceOrder(out, false); check(out, 2,0,1);
        writer.writeExpandedSourceOrder(out, true); check(out, 0,2,1);
        writer.write(out, draw, true); check(out, 2,0,1);
        writer.writeExpanded(out, draw, true); check(out, 0,1,2);
        writer.write(out, draw, false); check(out, 2,0,1);
        writer.writeExpanded(out, draw, false); check(out, 0,1,2);
        System.out.println("Frozen 2.3.1 mesh writer caller linked against current core.");
    }
    private static void check(IntBuffer buffer, int... expected) {
        if (buffer.position() != 3 || !Arrays.equals(expected, buffer.array())) throw new AssertionError();
        buffer.clear();
    }
}
