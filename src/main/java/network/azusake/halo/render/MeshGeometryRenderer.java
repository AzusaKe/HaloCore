package network.azusake.halo.render;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.joml.Matrix4f;
import network.azusake.halo.core.Vec3d;
import network.azusake.halo.core.render.DrawBatch;
import network.azusake.halo.core.render.FrameOutput;
import network.azusake.halo.core.render.MaterialState;
import network.azusake.halo.core.render.MeshDraw;
import network.azusake.halo.core.render.TriangleMesh;
import network.azusake.halo.core.render.VisualResources;
import network.azusake.halo.shape.MeshPrimitive;

/** Internal geometry stage: keep old primitives ordered, then opaque and depth-sorted mesh draws. */
public final class MeshGeometryRenderer {
    private record Pending(MeshDraw draw, float depth) {}
    private final Consumer<String> warning;
    private final Set<String> warned = new HashSet<>();
    private final List<MeshDraw> opaque = new ArrayList<>();
    private final List<Pending> translucent = new ArrayList<>();
    private VisualResources resources = VisualResources.EMPTY;
    private long warningGeneration = Long.MIN_VALUE;

    public MeshGeometryRenderer(Consumer<String> warning) { this.warning = warning; }

    public void begin(VisualResources resources) {
        this.resources = resources;
        opaque.clear(); translucent.clear();
        if (warningGeneration != resources.generation()) {
            warningGeneration = resources.generation(); warned.clear();
        }
    }

    public void add(MeshPrimitive primitive, Matrix4f parent, float alpha, float brightness, double time) {
        if (alpha <= 0) return;
        TriangleMesh mesh = resources.meshes().get(primitive.model());
        var texture = resources.textures().get(primitive.texture());
        // Resource loading already reports unavailable model/texture entries once per generation.
        if (mesh == null || texture == null) return;
        try {
            var effect = primitive.material().mask();
            MaterialState.AlphaMask mask = null;
            if (effect != null) {
                var maskTexture = resources.textures().get(effect.texture());
                if (maskTexture == null) return;
                mask = effect.evaluate(time);
            }
            boolean blend = alpha < 1 || !texture.opaque() || (mask != null && mask.mode() == MaterialState.MaskMode.LINEAR);
            Vec3d scale = primitive.preserveProportions()
                ? new Vec3d(primitive.scale(), primitive.scale(), primitive.scale()) : mesh.scaleTo(primitive.size());
            Matrix4f transform = new Matrix4f(parent).scale((float) scale.x, (float) scale.y, (float) scale.z);
            float determinant = transform.determinant();
            if (!Float.isFinite(determinant)) throw new IllegalArgumentException("Model transform produced non-finite vertices");
            boolean mirrored = determinant < 0;
            float[] matrix = transform.get(new float[16]);
            validateBounds(mesh, matrix);
            MeshDraw draw = new MeshDraw(primitive.model(), primitive.texture(), matrix,
                !primitive.material().doubleSided(), blend, true, !blend,
                brightness, brightness, brightness, alpha, mirrored, new MaterialState.Mesh(mask));
            if (blend) {
                Vec3d center = mesh.center();
                float depth = matrix[2] * (float) center.x + matrix[6] * (float) center.y
                    + matrix[10] * (float) center.z + matrix[14];
                translucent.add(new Pending(draw, depth));
            } else opaque.add(draw);
        } catch (IllegalArgumentException ex) {
            String sizing = primitive.preserveProportions() ? "scale " + primitive.scale() : "size " + primitive.size();
            String message = primitive.model() + " (" + sizing + "): " + ex.getMessage();
            if (warned.add(message)) warning.accept(message);
        }
    }

    private static void validateBounds(TriangleMesh mesh, float[] m) {
        Vec3d min = mesh.minimum(), max = mesh.maximum();
        for (int xi = 0; xi < 2; xi++) for (int yi = 0; yi < 2; yi++)
            for (int zi = 0; zi < 2; zi++) {
                double x = xi == 0 ? min.x : max.x;
                double y = yi == 0 ? min.y : max.y;
                double z = zi == 0 ? min.z : max.z;
                float tx = m[0] * (float) x + m[4] * (float) y + m[8] * (float) z + m[12];
                float ty = m[1] * (float) x + m[5] * (float) y + m[9] * (float) z + m[13];
                float tz = m[2] * (float) x + m[6] * (float) y + m[10] * (float) z + m[14];
                if (!Float.isFinite(tx) || !Float.isFinite(ty) || !Float.isFinite(tz))
                    throw new IllegalArgumentException("Model transform produced non-finite vertices");
            }
    }

    public FrameOutput finish(List<DrawBatch> legacy) {
        var meshes = new ArrayList<MeshDraw>(opaque.size() + translucent.size());
        meshes.addAll(opaque);
        translucent.sort(Comparator.comparingDouble(Pending::depth));
        for (Pending pending : translucent) meshes.add(pending.draw());
        return new FrameOutput(resources.generation(), legacy, meshes);
    }
}
