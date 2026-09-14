package network.azusake.halo.core.render;

import java.util.Objects;
import network.azusake.halo.core.Identifier;

/** Lightweight mesh draw command. Geometry remains in authored local space. */
public record MeshDraw(Identifier model, Identifier texture, float[] localToView,
                       boolean cull, boolean blend, boolean depthTest, boolean depthWrite,
                       float red, float green, float blue, float alpha,
                       boolean mirrored, MaterialState.Mesh material, LightSample light,
                       boolean directionalLighting) {
    public MeshDraw {
        Objects.requireNonNull(model);
        Objects.requireNonNull(texture);
        Objects.requireNonNull(material);
        Objects.requireNonNull(light);
        localToView = Objects.requireNonNull(localToView).clone();
        if (localToView.length != 16) throw new IllegalArgumentException("localToView must be a 4x4 matrix");
        for (float value : localToView) if (!Float.isFinite(value)) {
            throw new IllegalArgumentException("localToView contains a non-finite value");
        }
        for (float value : new float[]{red, green, blue, alpha}) if (!Float.isFinite(value)) {
            throw new IllegalArgumentException("Mesh color contains a non-finite value");
        }
    }

    /** Compatibility constructor for adapters predating normal-based directional lighting. */
    public MeshDraw(Identifier model, Identifier texture, float[] localToView,
                    boolean cull, boolean blend, boolean depthTest, boolean depthWrite,
                    float red, float green, float blue, float alpha,
                    boolean mirrored, MaterialState.Mesh material, LightSample light) {
        this(model, texture, localToView, cull, blend, depthTest, depthWrite,
            red, green, blue, alpha, mirrored, material, light, false);
    }

    /** Compatibility constructor for adapters predating native block/sky light samples. */
    public MeshDraw(Identifier model, Identifier texture, float[] localToView,
                    boolean cull, boolean blend, boolean depthTest, boolean depthWrite,
                    float red, float green, float blue, float alpha,
                    boolean mirrored, MaterialState.Mesh material) {
        this(model, texture, localToView, cull, blend, depthTest, depthWrite,
            red, green, blue, alpha, mirrored, material, LightSample.UNAVAILABLE, false);
    }

    @Override public float[] localToView() { return localToView.clone(); }
    /** Read one column-major matrix element without allocating a defensive copy. */
    public float transform(int index) { return localToView[index]; }
}
