package network.azusake.halo.render;

import java.util.LinkedHashMap;
import java.util.Map;
import network.azusake.halo.core.Identifier;
import network.azusake.halo.core.render.*;
import network.azusake.halo.shape.*;

/** Definition-owned geometry preparation. No per-entity or global cache. */
public final class PrimitiveGeometries {
    private final Map<Identifier, PrimitiveGeometry> geometries = new LinkedHashMap<>();
    private final Map<BillboardPrimitive, PrimitiveGeometry> billboards = new java.util.IdentityHashMap<>();
    private final Map<RingPrimitive, PrimitiveGeometry[]> rings = new java.util.IdentityHashMap<>();
    private final java.util.Set<HaloPrimitive> unavailable = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private PrimitiveGeometry facingQuad;

    public PrimitiveGeometry facingQuad() {
        if (facingQuad == null) facingQuad = billboard(new BillboardPrimitive(null, new org.joml.Vector2f(2, 2)));
        return facingQuad;
    }

    public PrimitiveGeometry billboard(BillboardPrimitive primitive) {
        if (unavailable.contains(primitive)) return null;
        PrimitiveGeometry existing = billboards.get(primitive);
        if (existing != null) return existing;
        var size = primitive.size();
        float hw = size.x / 2f, hd = size.y / 2f;
        var id = new Identifier("halo_generated:billboard/" + bits(hw) + "_" + bits(hd));
        PrimitiveGeometry prepared = geometries.computeIfAbsent(id, key -> new PrimitiveGeometry(key,
            new float[]{-hw,0,-hd, hw,0,-hd, hw,0,hd, -hw,0,hd},
            new float[]{0,1, 1,1, 1,0, 0,0}, new float[]{0,-1,0, 0,-1,0, 0,-1,0, 0,-1,0},
            new int[]{0,1,2, 2,3,0}, DrawBatch.Topology.QUADS));
        billboards.put(primitive, prepared);
        return prepared;
    }

    public PrimitiveGeometry ring(RingPrimitive primitive, boolean inner) {
        if (unavailable.contains(primitive)) return null;
        PrimitiveGeometry[] pair = rings.computeIfAbsent(primitive, ignored -> new PrimitiveGeometry[2]);
        int side = inner ? 1 : 0;
        if (pair[side] != null) return pair[side];
        var size = primitive.size();
        int segments = Math.max(3, primitive.segments());
        var id = new Identifier("halo_generated:ring/" + bits(size.x) + "_" + bits(size.y)
            + "_" + segments + (inner ? "_inner" : "_outer"));
        return pair[side] = geometries.computeIfAbsent(id, key -> ring(key, size.x, size.y / 2f, segments, inner));
    }

    public void prepare(HaloGroup group) {
        for (var primitive : group.primitives()) {
            try {
                if (primitive instanceof BillboardPrimitive billboard) {
                    billboard(billboard);
                    if (billboard.faceCamera()) facingQuad();
                } else if (primitive instanceof RingPrimitive ring) {
                    ring(ring, false);
                    ring(ring, true);
                }
            } catch (RuntimeException | OutOfMemoryError error) {
                // Legacy JSON accepted non-finite sizes. One unusable primitive must
                // not turn loading every definition into a fatal resource reload.
                unavailable.add(primitive);
                network.azusake.halo.core.Diagnostics.logger("halo").warn(
                    "Could not prepare legacy primitive {}; skipping until definition reload: {}", primitive, error.getMessage());
            }
        }
        group.children().forEach(this::prepare);
    }
    public Map<Identifier, PrimitiveGeometry> snapshot() { return Map.copyOf(geometries); }
    private static String bits(float value) { return Integer.toHexString(Float.floatToIntBits(value)); }

    private static PrimitiveGeometry ring(Identifier id, float radius, float halfWidth, int segments, boolean inner) {
        int vertices = Math.multiplyExact(Math.addExact(segments, 1), 2);
        float[] positions = new float[Math.multiplyExact(vertices, 3)];
        float[] normals = new float[positions.length];
        float[] uv = new float[Math.multiplyExact(vertices, 2)];
        int[] indices = new int[Math.multiplyExact(segments, 6)];
        for (int i = 0; i <= segments; i++) {
            int sample = i == segments ? 0 : i;
            float cos = (float)Math.cos(2.0 * Math.PI * sample / segments);
            float sin = (float)Math.sin(2.0 * Math.PI * sample / segments);
            for (int side = 0; side < 2; side++) {
                int vertex = i * 2 + side;
                positions[vertex * 3] = radius * cos;
                positions[vertex * 3 + 1] = side == 0 ? halfWidth : -halfWidth;
                positions[vertex * 3 + 2] = radius * sin;
                normals[vertex * 3] = inner ? -cos : cos;
                normals[vertex * 3 + 2] = inner ? -sin : sin;
                uv[vertex * 2] = (float)i / segments;
                uv[vertex * 2 + 1] = side;
            }
        }
        for (int i = 0; i < segments; i++) {
            int top = i * 2, bottom = top + 1, nextTop = top + 2, nextBottom = top + 3;
            int[] triangle = inner ? new int[]{bottom,nextBottom,top, top,nextBottom,nextTop}
                : new int[]{top,nextTop,bottom, bottom,nextTop,nextBottom};
            System.arraycopy(triangle, 0, indices, i * 6, 6);
        }
        return new PrimitiveGeometry(id, positions, uv, normals, indices, DrawBatch.Topology.TRIANGLES);
    }
}
