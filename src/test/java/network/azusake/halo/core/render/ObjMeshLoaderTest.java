package network.azusake.halo.core.render;

import network.azusake.halo.core.Identifier;
import network.azusake.halo.core.Vec3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ObjMeshLoaderTest {
    @Test void blenderExportKeepsOriginHandednessAndUnequalAxes() throws Exception {
        // Exported by Blender 5.2 with forward=-Z/up=Y from an off-origin tetrahedron.
        // Blender (x,y,z) -> OBJ/Halo (x,z,-y), determinant +1, applied exactly once.
        try (var input = getClass().getResourceAsStream("/meshes/blender_coordinate_fixture.obj")) {
            assertNotNull(input);
            var mesh = ObjMeshLoader.parse(ID, new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(new Vec3d(.25,1,-3.5), mesh.minimum());
            assertEquals(new Vec3d(2.25,5,-.5), mesh.maximum());
            assertEquals(new Vec3d(1,1,1), mesh.scaleTo(new Vec3d(2,4,3)));
            int a=mesh.index(0), b=mesh.index(1), c=mesh.index(2);
            assertEquals(.25, mesh.x(a)); assertEquals(1, mesh.y(a)); assertEquals(-.5, mesh.z(a));
            assertEquals(-3.5, mesh.z(b)); assertEquals(2.25, mesh.x(c));
            // The Blender bottom face (-Z normal) becomes a Halo bottom face (-Y).
            float normalY=(mesh.z(b)-mesh.z(a))*(mesh.x(c)-mesh.x(a))
                -(mesh.x(b)-mesh.x(a))*(mesh.z(c)-mesh.z(a));
            assertEquals(-6,normalY);
            assertEquals(.125,mesh.u(a)); assertEquals(.75,mesh.v(a));
        }
    }

    @Test void exportedDemoFixtureLoadsAsUvSeamedThreeDimensionalMesh() throws Exception {
        try (var input = getClass().getResourceAsStream("/meshes/mesh_demo.obj")) {
            assertNotNull(input);
            var mesh = ObjMeshLoader.parse(ID, new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(288, mesh.triangleCount());
            assertTrue(mesh.vertexCount() > 144, "UV seam vertices must be preserved");
            var scale = mesh.scaleTo(new Vec3d(.9, .14, .9));
            assertTrue(scale.x > 0 && scale.y > 0 && scale.z > 0);
        }
    }
    private static final Identifier ID = new Identifier("pack:models/halo/test.obj");
    private static final String QUAD = """
        v 1 2 3
        v 3 2 3
        v 3 6 3
        v 1 6 3
        vt 0 0
        vt 1 0
        vt 1 1
        vt 0 1
        vn 0 0 1
        """;

    @Test void convexQuadSplitsAndOnlyReferencedVerticesDefineBounds() {
        var mesh = ObjMeshLoader.parse(ID, QUAD + "v 999 999 999\nf 1/1/1 2/2/1 3/3/1 4/4/1\n");
        assertEquals(2, mesh.triangleCount()); assertEquals(4, mesh.vertexCount());
        assertEquals(new Vec3d(1, 2, 3), mesh.minimum());
        assertEquals(new Vec3d(3, 6, 3), mesh.maximum());
        assertEquals(new Vec3d(.5, .25, 1), mesh.scaleTo(new Vec3d(1, 1, 0)));
        assertEquals(1, mesh.v(0)); assertEquals(0, mesh.v(2));
        assertArrayEquals(new int[]{0,1,2,0,2,3}, indices(mesh));
        assertThrows(IllegalArgumentException.class, () -> mesh.scaleTo(new Vec3d(1,1,1)));
    }

    @Test void negativeIndependentIndicesAndUvSeamsArePreserved() {
        var mesh = ObjMeshLoader.parse(ID, QUAD + """
            f -4/-1/-1 -3/-2/-1 -2/-3/-1
            f 1/1 3/3 4/4
            """);
        assertEquals(6, mesh.vertexCount()); assertEquals(2, mesh.triangleCount());
        assertEquals(mesh.x(0), mesh.x(3)); assertNotEquals(mesh.v(0), mesh.v(3));
        assertEquals(0, mesh.u(0)); assertEquals(0, mesh.v(0));
    }

    @Test void authoredAndGeneratedNormalsPreserveHardEdgesAndObjNegativeIndexSemantics() {
        String geometry = """
            v 0 0 0
            v 1 0 0
            v 0 1 0
            vt 0 0
            vt 1 0
            vt 0 1
            f 1/1 2/2 3/3
            vn 0 2 0
            f -3/-3/-1 -2/-2/-1 -1/-1/-1
            """;
        var mesh = ObjMeshLoader.parse(ID, geometry);
        assertEquals(6, mesh.vertexCount(), "different normals must split otherwise-identical corners");
        assertEquals(0, mesh.normalX(0), 1e-6); assertEquals(0, mesh.normalY(0), 1e-6);
        assertEquals(1, mesh.normalZ(0), 1e-6);
        assertEquals(0, mesh.normalX(3), 1e-6); assertEquals(1, mesh.normalY(3), 1e-6);
        assertEquals(0, mesh.normalZ(3), 1e-6);
    }

    @Test void indexedZeroNormalsFallBackPerFaceWithoutRemovingTheirObjIndexSlot() {
        var mesh = ObjMeshLoader.parse(ID, """
            v 0 0 0
            v 1 0 0
            v 0 1 0
            vt 0 0
            vt 1 0
            vt 0 1
            vn 0 0 0
            vn 0 -2 0
            f 1/1/1 2/2/1 3/3/1
            f 1/1/-1 2/2/-1 3/3/-1
            """);
        assertEquals(6, mesh.vertexCount());
        assertEquals(1, mesh.normalZ(0), 1e-6);
        assertEquals(-1, mesh.normalY(3), 1e-6);
    }

    @Test void commentsNamesBomAndContinuationDoNotChangeGeometry() {
        String text = "\uFEFF# export\r\n" + QUAD + "o thing\ng left right\ns 1\nmtllib unused.mtl\nusemtl ignored\n"
            + "f 1/1 2/2 \\\n 3/3 # done\n";
        assertEquals(1, ObjMeshLoader.parse(ID, text).triangleCount());
    }

    @Test void invalidFacesHaveResourceAndLineDiagnostics() {
        for (String face : new String[]{"f 0/1 2/2 3/3", "f 1 2 3", "f 1//1 2//1 3//1", "f 1/1/9 2/2/1 3/3/1",
                "f -8/1 2/2 3/3", "f 1/1 2/9 3/3", "f 1/1 2/2 3/3 4/4 1/1", "f 1/1 1/2 1/3"}) {
            var error = assertThrows(IllegalArgumentException.class, () -> ObjMeshLoader.parse(ID, QUAD + face));
            assertTrue(error.getMessage().startsWith(ID + ":10:"), error.getMessage());
        }
        assertThrows(IllegalArgumentException.class, () -> ObjMeshLoader.parse(ID, "v NaN 0 0"));
        assertThrows(IllegalArgumentException.class, () -> ObjMeshLoader.parse(ID, "v 1e99 0 0"));
        assertThrows(IllegalArgumentException.class, () -> ObjMeshLoader.parse(ID, ""));
        assertThrows(IllegalArgumentException.class, () -> ObjMeshLoader.parse(ID, QUAD + "curv 0 1 1 2"));
        assertThrows(IllegalArgumentException.class, () -> ObjMeshLoader.parse(ID, QUAD + "f 1/1 \\"));
    }

    @Test void concaveCrossedAndNonPlanarQuadsRequireExportTriangulation() {
        for (String positions : new String[]{
                "v 0 0 0\nv 2 0 0\nv .5 .5 0\nv 0 2 0\n",
                "v 0 0 0\nv 2 2 0\nv 2 0 0\nv 0 2 0\n",
                "v 0 0 0\nv 2 0 0\nv 2 2 0\nv 0 2 1\n"}) {
            assertThrows(IllegalArgumentException.class, () -> ObjMeshLoader.parse(ID,
                positions + "vt 0 0\nf 1/1 2/1 3/1 4/1"));
        }
    }

    @Test void meshOwnsItsArraysAndValidatesPublicInputs() {
        float[] p = {0,0,0, 1,0,0, 0,1,0}, uv = {0,0, 1,0, 0,1};
        int[] indices = {0,1,2};
        var mesh = new TriangleMesh(p, uv, indices);
        p[0] = 99; uv[0] = 99; indices[0] = 2;
        assertEquals(0, mesh.x(0)); assertEquals(0, mesh.u(0)); assertEquals(0, mesh.index(0));
        assertEquals(1, mesh.normalZ(0), 1e-6, "compatibility constructor generates normals");
        assertThrows(IllegalArgumentException.class, () -> new TriangleMesh(new float[]{Float.NaN,0,0}, new float[2], new int[]{0,0,0}));
        assertThrows(IllegalArgumentException.class, () -> mesh.scaleTo(new Vec3d(-1,1,0)));
        assertThrows(IllegalArgumentException.class, () -> mesh.scaleTo(new Vec3d(Double.NaN,1,0)));
    }
    private static int[] indices(TriangleMesh mesh) {
        int[] result = new int[mesh.triangleCount() * 3];
        for (int i = 0; i < result.length; i++) result[i] = mesh.index(i);
        return result;
    }
}
