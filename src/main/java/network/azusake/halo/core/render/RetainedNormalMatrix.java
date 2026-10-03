package network.azusake.halo.core.render;

import org.joml.Matrix3d;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/** Internal implementation helper, not an adapter API; legacy raster conventions remain unchanged. */
public final class RetainedNormalMatrix {
    private RetainedNormalMatrix() { }

    public static Matrix3f from(Matrix4f transform) {
        // A fixed float determinant cutoff mistakes small, invertible scales for
        // singular transforms. Double arithmetic also avoids cubic underflow.
        var normal=new Matrix3d(new Matrix3f(transform));
        double determinant=normal.determinant();
        if (!Double.isFinite(determinant)) throw new IllegalArgumentException("Non-finite retained normal transform");
        // Keep a finite placeholder so the backend can diagnose/reject the
        // singular placement itself; this is not an inverse normal matrix.
        if (determinant==0) return new Matrix3f();
        normal.invert().transpose();
        var result=new Matrix3f((float)normal.m00(),(float)normal.m01(),(float)normal.m02(),
            (float)normal.m10(),(float)normal.m11(),(float)normal.m12(),
            (float)normal.m20(),(float)normal.m21(),(float)normal.m22());
        if (!result.isFinite()) throw new IllegalArgumentException("Retained normal transform exceeds float range");
        return result;
    }
}
