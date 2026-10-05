package network.azusake.halo.core.render;

import java.util.List;
import org.joml.*;

/** v1 direction contract: column-major rotations; model export B(x,y,z)->M(x,z,-y). */
public final class BaOrientation {
    private BaOrientation() {}
    public record Sample(float[] viewToReference,float[] objectDirection) {
        public Sample { viewToReference=viewToReference.clone(); objectDirection=objectDirection.clone(); }
        @Override public float[] viewToReference() { return viewToReference.clone(); }
        @Override public float[] objectDirection() { return objectDirection.clone(); }
    }
    /** Reconstruct part/world rotation from the exact inverse of the submitted view matrix. */
    public static Sample fromView(float[] modelToView,float[] viewToWorld,BaMaterial.Parameters p) {
        if(modelToView.length!=9 || viewToWorld.length!=9) throw new IllegalArgumentException("BA orientation needs 3x3 rotations");
        Matrix3f partToWorld=new Matrix3f().set(viewToWorld).mul(new Matrix3f().set(modelToView));
        return sample(partToWorld.get(new float[9]),viewToWorld,p);
    }
    public static Sample sample(float[] modelToWorld,float[] viewToWorld,BaMaterial.Parameters p) {
        if(modelToWorld.length!=9 || viewToWorld.length!=9) throw new IllegalArgumentException("BA orientation needs 3x3 rotations");
        Matrix3f export=new Matrix3f().rotationX(-(float)java.lang.Math.PI/2);
        Matrix3f inverseExport=new Matrix3f(export).transpose();
        Matrix3f specRotation=new Matrix3f().rotationY((float)java.lang.Math.PI/2);
        Matrix3f viewToReference=new Matrix3f(specRotation).mul(inverseExport).mul(new Matrix3f().set(viewToWorld));
        Matrix3f part=new Matrix3f().set(modelToWorld);
        for(int column=0;column<3;column++) {
            Vector3f axis=part.getColumn(column,new Vector3f());
            if(axis.lengthSquared()>1e-12f) axis.normalize();
            else throw new IllegalArgumentException("Degenerate BA orientation");
            part.setColumn(column,axis);
        }
        Vector3f r;
        List<Float> helper=p.values().get("SpecHelperRotation");
        if(helper!=null) {
            // Reconstruct the moving helper in Blender world axes, then reproduce its
            // WORLD_SPACE drivers (-rotX,-rotZ,-rotY), rather than rotate frozen Euler values.
            Matrix3f helperWorld=new Matrix3f(inverseExport).mul(part).mul(export)
                .mul(new Matrix3f().rotation(new Quaternionf(helper.get(0),helper.get(1),helper.get(2),helper.get(3))));
            Vector3f angles=helperWorld.getEulerAnglesZYX(new Vector3f());
            r=new Vector3f(-angles.x,-angles.z,-angles.y);
        } else {
            List<Float> rotation=p.vector("Rotation",List.of(0f,0f,0f));
            r=new Vector3f(rotation.get(0),rotation.get(1),rotation.get(2));
        }
        Vector3f direction=new Matrix3f(specRotation).mul(new Matrix3f().rotationZYX(r.z,r.y,r.x)).transform(new Vector3f(0,-1,0)).normalize();
        return new Sample(viewToReference.get(new float[9]),new float[]{direction.x,direction.y,direction.z});
    }
}
