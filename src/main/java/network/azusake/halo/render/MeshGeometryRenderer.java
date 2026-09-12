package network.azusake.halo.render;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import network.azusake.halo.core.Vec3d;
import network.azusake.halo.core.render.DrawBatch;
import network.azusake.halo.core.render.MaterialState;
import network.azusake.halo.core.render.TriangleMesh;
import network.azusake.halo.core.render.VisualResources;
import network.azusake.halo.shape.MeshPrimitive;

/** Internal geometry stage: keep old primitives ordered, then opaque and depth-sorted mesh draws. */
public final class MeshGeometryRenderer {
    private record Pending(DrawBatch batch, float depth) {}
    private final Consumer<String> warning;
    private final Set<String> warned = new HashSet<>();
    private final List<DrawBatch> opaque = new ArrayList<>();
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
                if (!maskTexture.hasIntegralScaleWith(texture)) {
                    throw new IllegalArgumentException("alpha_mask " + effect.texture() + " (" + maskTexture.width() + "x" + maskTexture.height()
                        + ") and base texture " + primitive.texture() + " (" + texture.width() + "x" + texture.height()
                        + ") must have equal dimensions or the same integer scale factor on both axes");
                }
                mask = effect.evaluate(time);
            }
            boolean blend = alpha < 1 || !texture.opaque() || (mask != null && mask.mode() == MaterialState.MaskMode.LINEAR);
            Vec3d scale = primitive.preserveProportions()
                ? new Vec3d(primitive.scale(), primitive.scale(), primitive.scale()) : mesh.scaleTo(primitive.size());
            Matrix4f transform = new Matrix4f(parent).scale((float) scale.x, (float) scale.y, (float) scale.z);
            DrawBatch.Vertex[] vertices = new DrawBatch.Vertex[mesh.vertexCount()];
            var point = new Vector3f();
            for (int i = 0; i < vertices.length; i++) {
                transform.transformPosition(mesh.x(i), mesh.y(i), mesh.z(i), point);
                if (!Float.isFinite(point.x) || !Float.isFinite(point.y) || !Float.isFinite(point.z)) {
                    throw new IllegalArgumentException("Model transform produced non-finite vertices");
                }
                vertices[i] = new DrawBatch.Vertex(point.x, point.y, point.z, mesh.u(i), mesh.v(i), brightness, brightness, brightness, 1);
            }
            long[] order = new long[mesh.triangleCount()];
            for (int triangle = 0; triangle < order.length; triangle++) {
                int base = triangle * 3;
                float z = (float) (((double) vertices[mesh.index(base)].z() + vertices[mesh.index(base + 1)].z()
                    + vertices[mesh.index(base + 2)].z()) / 3);
                // Signed integer ordering equivalent to float ordering; low bits break ties by source order.
                int bits = Float.floatToIntBits(z);
                int key = bits ^ ((bits >> 31) & 0x7fffffff);
                order[triangle] = ((long) key << 32) | (triangle & 0xffffffffL);
            }
            if (blend) Arrays.sort(order); // Camera looks down -Z: more negative is farther away.
            var ordered = new ArrayList<DrawBatch.Vertex>(order.length * 3);
            boolean mirrored = transform.determinant() < 0;
            for (long entry : order) {
                int base = (int) entry * 3;
                ordered.add(vertices[mesh.index(base)]);
                ordered.add(vertices[mesh.index(base + (mirrored ? 2 : 1))]);
                ordered.add(vertices[mesh.index(base + (mirrored ? 1 : 2))]);
            }
            DrawBatch batch = new DrawBatch(DrawBatch.Topology.TRIANGLES, ordered, primitive.texture(), true,
                !primitive.material().doubleSided(), blend, true, !blend, 1, 1, 1, alpha, new MaterialState.Mesh(mask));
            if (blend) {
                Vec3d center = mesh.center();
                transform.transformPosition((float) center.x, (float) center.y, (float) center.z, point);
                translucent.add(new Pending(batch, point.z));
            } else opaque.add(batch);
        } catch (IllegalArgumentException ex) {
            String sizing = primitive.preserveProportions() ? "scale " + primitive.scale() : "size " + primitive.size();
            String message = primitive.model() + " (" + sizing + "): " + ex.getMessage();
            if (warned.add(message)) warning.accept(message);
        }
    }

    public List<DrawBatch> finish(List<DrawBatch> legacy) {
        if (opaque.isEmpty() && translucent.isEmpty()) return legacy;
        var result = new ArrayList<DrawBatch>(legacy.size() + opaque.size() + translucent.size());
        result.addAll(legacy); result.addAll(opaque);
        translucent.sort(Comparator.comparingDouble(Pending::depth));
        for (Pending pending : translucent) result.add(pending.batch());
        return List.copyOf(result);
    }
}
