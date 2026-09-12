package network.azusake.halo.core;
import java.util.List;
import org.joml.*;
public final class LocatorMath {
    public static float[] composeHeadMatrix(float[] root, List<BonePose> hierarchy) {
        Matrix4f rootMatrix=root==null?null:new Matrix4f().set(root);
        if (rootMatrix == null || hierarchy == null || hierarchy.isEmpty() || !isFinite(rootMatrix)) {
            return null;
        }

        Matrix4f matrix = new Matrix4f(rootMatrix);
        for (int i = 0; i < hierarchy.size(); i++) {
            BonePose bone = hierarchy.get(i);
            if (bone == null || !bone.isUsable()) {
                return null;
            }

            matrix.translate(-bone.positionX / 16f, bone.positionY / 16f, bone.positionZ / 16f);
            matrix.translate(bone.pivotX / 16f, bone.pivotY / 16f, bone.pivotZ / 16f);
            if (bone.rotationX != 0f || bone.rotationY != 0f || bone.rotationZ != 0f) {
                matrix.rotate(new Quaternionf().rotationZYX(
                    bone.rotationZ, bone.rotationY, bone.rotationX));
            }
            matrix.scale(bone.scaleX, bone.scaleY, bone.scaleZ);

            // prepMatrixForLocator intentionally remains at the final Head
            // pivot, while intermediate bones translate back out of theirs.
            if (i + 1 < hierarchy.size()) {
                matrix.translate(-bone.pivotX / 16f, -bone.pivotY / 16f, -bone.pivotZ / 16f);
            }
        }
        return isFinite(matrix) ? matrix.get(new float[16]) : null;
    }

    public static boolean isFinite(Matrix4f matrix) {
        return matrix != null
            && Float.isFinite(matrix.m00()) && Float.isFinite(matrix.m01())
            && Float.isFinite(matrix.m02()) && Float.isFinite(matrix.m03())
            && Float.isFinite(matrix.m10()) && Float.isFinite(matrix.m11())
            && Float.isFinite(matrix.m12()) && Float.isFinite(matrix.m13())
            && Float.isFinite(matrix.m20()) && Float.isFinite(matrix.m21())
            && Float.isFinite(matrix.m22()) && Float.isFinite(matrix.m23())
            && Float.isFinite(matrix.m30()) && Float.isFinite(matrix.m31())
            && Float.isFinite(matrix.m32()) && Float.isFinite(matrix.m33());
    }

    public record BonePose(
        float rotationX, float rotationY, float rotationZ,
        float positionX, float positionY, float positionZ,
        float scaleX, float scaleY, float scaleZ,
        float pivotX, float pivotY, float pivotZ
    ) {
        boolean isUsable() {
            return Float.isFinite(rotationX) && Float.isFinite(rotationY) && Float.isFinite(rotationZ)
                && Float.isFinite(positionX) && Float.isFinite(positionY) && Float.isFinite(positionZ)
                && Float.isFinite(scaleX) && Float.isFinite(scaleY) && Float.isFinite(scaleZ)
                && Float.isFinite(pivotX) && Float.isFinite(pivotY) && Float.isFinite(pivotZ)
                && scaleX != 0f && scaleY != 0f && scaleZ != 0f;
        }
    }

}
