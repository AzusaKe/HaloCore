package network.azusake.halo.core;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import network.azusake.halo.anchor.AnchorPoseMath;
import network.azusake.halo.api.v2.*;

/** Model-independent render capture conversion. Matrices are column-major arrays of 16 floats. */
public final class CapturedModelMath {
    public static AnchorPose resolve(float[] captured,float[] view,Vec3d camera,Vec3d localCenter,Vec3d localForward,Vec3d localUp) {
        if(!finite(captured)||!finite(view)||camera==null||localCenter==null)return null;
        Matrix4f inverse=new Matrix4f().set(view).invert();
        if(!inverse.isFinite())return null;
        Matrix4f world=inverse.mul(new Matrix4f().set(captured));
        if(!world.isFinite())return null;
        Vector4f point=world.transform(new Vector4f((float)localCenter.x,(float)localCenter.y,(float)localCenter.z,1));
        Vec3d forward=direction(world,localForward),up=direction(world,localUp);
        if(forward.lengthSquared()<1e-12 || up.lengthSquared()<1e-12)return null;
        forward=forward.normalize();up=up.subtract(forward.multiply(up.dotProduct(forward)));
        if(up.lengthSquared()<1e-12)return null;
        try {
            return new AnchorPose(new AnchorVec3(camera.x+point.x,camera.y+point.y,camera.z+point.z),
                AnchorPoseMath.fromForwardUp(vector(forward),vector(up.normalize())));
        } catch(IllegalArgumentException ex) { return null; }
    }
    private static AnchorVec3 vector(Vec3d v){return new AnchorVec3(v.x,v.y,v.z);}
    private static Vec3d direction(Matrix4f matrix,Vec3d v){
        Vector4f o=matrix.transform(new Vector4f(0,0,0,1));
        Vector4f p=matrix.transform(new Vector4f((float)v.x,(float)v.y,(float)v.z,1));
        return new Vec3d(p.x-o.x,p.y-o.y,p.z-o.z);
    }
    public static boolean finite(float[] m){if(m==null||m.length!=16)return false;for(float f:m)if(!Float.isFinite(f))return false;return true;}
}
