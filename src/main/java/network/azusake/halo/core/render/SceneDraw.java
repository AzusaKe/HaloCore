package network.azusake.halo.core.render;

import java.util.Objects;
import java.util.UUID;
import network.azusake.halo.core.Identifier;
import network.azusake.halo.core.Vec3d;

/** Retained geometry placement: local offsets stay small, world translation stays double precision. */
public record SceneDraw(Key key, Kind kind, TriangleMesh geometry, Vec3d origin,
                        float[] localToWorld, float[] normalToWorld, Identifier texture,
                        boolean textured, boolean cull, boolean glowing, float brightness,
                        float alpha, MaterialState material) {
    public enum Kind { BILLBOARD, RING, MESH }
    public record Key(UUID wearer, int runtimeId, Identifier definition, long definitionRevision,
                      String groupPath, int primitive, int part) {
        public Key { Objects.requireNonNull(wearer); Objects.requireNonNull(definition); Objects.requireNonNull(groupPath); }
        public Key primary() { return part == 0 ? this : new Key(wearer,runtimeId,definition,definitionRevision,groupPath,primitive,0); }
    }
    public SceneDraw {
        Objects.requireNonNull(key); Objects.requireNonNull(kind); Objects.requireNonNull(geometry);
        Objects.requireNonNull(origin); Objects.requireNonNull(material);
        localToWorld = localToWorld.clone(); normalToWorld = normalToWorld.clone();
        if (localToWorld.length != 16 || normalToWorld.length != 9) throw new IllegalArgumentException("Expected affine/normal matrices");
    }
    @Override public float[] localToWorld() { return localToWorld.clone(); }
    @Override public float[] normalToWorld() { return normalToWorld.clone(); }
    public float transform(int i) { return localToWorld[i]; }
    public float normal(int i) { return normalToWorld[i]; }
    public double worldX() { return origin.x + localToWorld[12]; }
    public double worldY() { return origin.y + localToWorld[13]; }
    public double worldZ() { return origin.z + localToWorld[14]; }
}
