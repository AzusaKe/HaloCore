package network.azusake.halo.core.render;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import network.azusake.halo.core.Identifier;

/** Dependency-free polygonal OBJ subset. Does not resolve MTL, files, URLs or executable statements. */
public final class ObjMeshLoader {
    public static final int MAX_TEXT_LENGTH = 16 * 1024 * 1024;
    public static final int MAX_ELEMENTS = 1_000_000;
    public static final int MAX_TRIANGLES = 250_000;
    private ObjMeshLoader() {}

    public static TriangleMesh parse(Identifier resource, String text) {
        if (text.length() > MAX_TEXT_LENGTH) throw problem(resource, 1, "OBJ exceeds 16 MiB text limit");
        var parser = new Parser(resource);
        try (var reader = new BufferedReader(new StringReader(text))) {
            String line;
            StringBuilder continued = new StringBuilder();
            int lineNumber = 0, firstLine = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (lineNumber == 1 && line.startsWith("\uFEFF")) line = line.substring(1);
                int comment = line.indexOf('#');
                if (comment >= 0) line = line.substring(0, comment);
                line = line.trim();
                if (continued.length() == 0) firstLine = lineNumber;
                if (line.endsWith("\\")) {
                    continued.append(line, 0, line.length() - 1).append(' ');
                    continue;
                }
                if (continued.length() != 0) { line = continued.append(line).toString(); continued.setLength(0); }
                parser.line(line, firstLine);
            }
            if (continued.length() != 0) throw problem(resource, firstLine, "Unterminated line continuation");
            if (parser.indices.isEmpty()) throw problem(resource, Math.max(1, lineNumber), "OBJ contains no textured faces");
            return parser.finish();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static IllegalArgumentException problem(Identifier resource, int line, String message) {
        return new IllegalArgumentException(resource + ":" + line + ": " + message);
    }

    private record Corner(int position, int uv) {}
    private static final class Parser {
        private final Identifier resource;
        private final List<float[]> positions = new ArrayList<>();
        private final List<float[]> uvs = new ArrayList<>();
        private int normalCount;
        private final Map<Corner, Integer> vertices = new HashMap<>();
        private final List<Corner> corners = new ArrayList<>();
        private final List<Integer> indices = new ArrayList<>();
        private Parser(Identifier resource) { this.resource = resource; }

        private void line(String line, int number) {
            if (line.isBlank()) return;
            try {
                String[] words = line.trim().split("\\s+");
                switch (words[0]) {
                    case "v" -> {
                        if (words.length != 4 && words.length != 5) throw new IllegalArgumentException("v requires x y z [w=1]");
                        if (words.length == 5 && finite(words[4]) != 1) throw new IllegalArgumentException("Only polygonal v with w=1 is supported");
                        positions.add(new float[]{finite(words[1]), finite(words[2]), finite(words[3])});
                    }
                    case "vt" -> {
                        if (words.length != 3 && words.length != 4) throw new IllegalArgumentException("vt requires u v [w=0]");
                        if (words.length == 4 && finite(words[3]) != 0) throw new IllegalArgumentException("Only two-dimensional UVs are supported");
                        float u = finite(words[1]), v = 1f - finite(words[2]);
                        if (!Float.isFinite(v)) throw new IllegalArgumentException("Unrepresentable UV");
                        uvs.add(new float[]{u, v});
                    }
                    case "vn" -> {
                        if (words.length != 4) throw new IllegalArgumentException("vn requires x y z");
                        for (int i = 1; i < 4; i++) finite(words[i]);
                        normalCount++; // Accepted and validated; v1 uses Halo's uniform brightness.
                    }
                    case "f" -> face(words);
                    case "o", "g", "s", "usemtl", "mtllib" -> { /* Names do not change the JSON material. */ }
                    default -> throw new IllegalArgumentException("Unsupported OBJ statement '" + words[0] + "'; export textured triangles");
                }
                if ((long) positions.size() + uvs.size() + normalCount > MAX_ELEMENTS
                        || indices.size() / 3 > MAX_TRIANGLES) throw new IllegalArgumentException("OBJ exceeds geometry limits");
            } catch (IllegalArgumentException ex) {
                throw problem(resource, number, ex.getMessage());
            }
        }

        private void face(String[] words) {
            int count = words.length - 1;
            if (count != 3 && count != 4) throw new IllegalArgumentException("Only triangles and planar convex quads are supported; triangulate on export");
            Corner[] face = new Corner[count];
            for (int i = 0; i < count; i++) {
                String[] reference = words[i + 1].split("/", -1);
                if (reference.length < 2 || reference.length > 3 || reference[1].isEmpty()) {
                    throw new IllegalArgumentException("Every face corner requires a UV (v/vt or v/vt/vn)");
                }
                int p = index(reference[0], positions.size()), uv = index(reference[1], uvs.size());
                if (reference.length == 3) index(reference[2], normalCount);
                face[i] = new Corner(p, uv);
            }
            validatePolygon(face);
            int[] mapped = new int[count];
            for (int i = 0; i < count; i++) {
                Corner corner = face[i];
                mapped[i] = vertices.computeIfAbsent(corner, key -> { corners.add(key); return corners.size() - 1; });
            }
            indices.add(mapped[0]); indices.add(mapped[1]); indices.add(mapped[2]);
            if (count == 4) { indices.add(mapped[0]); indices.add(mapped[2]); indices.add(mapped[3]); }
        }

        private void validatePolygon(Corner[] face) {
            double[][] p = new double[face.length][3];
            for (int i = 0; i < face.length; i++) {
                float[] v = positions.get(face[i].position());
                p[i] = new double[]{v[0], v[1], v[2]};
            }
            double[] normal = cross(subtract(p[1], p[0]), subtract(p[2], p[0]));
            double length = Math.sqrt(dot(normal, normal));
            double extent = 0;
            for (int i = 1; i < p.length; i++) extent = Math.max(extent, Math.sqrt(dot(subtract(p[i], p[0]), subtract(p[i], p[0]))));
            if (length <= extent * extent * 1e-10) throw new IllegalArgumentException("Degenerate face");
            if (face.length == 3) return;
            if (Math.abs(dot(normal, subtract(p[3], p[0]))) > length * extent * 1e-5) {
                throw new IllegalArgumentException("Non-planar quad; triangulate on export");
            }
            for (int i = 0; i < 4; i++) {
                double[] a = subtract(p[(i + 1) % 4], p[i]);
                double[] b = subtract(p[(i + 2) % 4], p[(i + 1) % 4]);
                if (dot(cross(a, b), normal) <= length * length * 1e-10) {
                    throw new IllegalArgumentException("Concave, crossing or degenerate quad; triangulate on export");
                }
            }
        }

        private TriangleMesh finish() {
            float[] p = new float[corners.size() * 3], uv = new float[corners.size() * 2];
            for (int i = 0; i < corners.size(); i++) {
                Corner corner = corners.get(i);
                System.arraycopy(positions.get(corner.position()), 0, p, i * 3, 3);
                System.arraycopy(uvs.get(corner.uv()), 0, uv, i * 2, 2);
            }
            int[] triangles = new int[indices.size()];
            for (int i = 0; i < triangles.length; i++) triangles[i] = indices.get(i);
            return new TriangleMesh(p, uv, triangles);
        }
    }

    private static float finite(String word) {
        float n = Float.parseFloat(word);
        if (!Float.isFinite(n)) throw new IllegalArgumentException("Non-finite number: " + word);
        return n;
    }
    private static int index(String word, int size) {
        int raw = Integer.parseInt(word);
        long index = raw < 0 ? (long) size + raw : (long) raw - 1;
        if (raw == 0 || index < 0 || index >= size) throw new IllegalArgumentException("OBJ index out of bounds: " + word);
        return (int) index;
    }
    private static double[] subtract(double[] a, double[] b) { return new double[]{a[0]-b[0], a[1]-b[1], a[2]-b[2]}; }
    private static double[] cross(double[] a, double[] b) { return new double[]{a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0]}; }
    private static double dot(double[] a, double[] b) { return a[0]*b[0]+a[1]*b[1]+a[2]*b[2]; }
}
