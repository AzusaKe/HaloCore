package network.azusake.halo.core.render;

import org.joml.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BaOrientationTest {
    static float[] rotation(Matrix3f m) { return m.get(new float[9]); }
    @Test void movingCameraChangesViewBasisButNotObjectHelper() {
        var p=BaMaterial.defaults(BaMaterial.Type.HAIR);
        var identity=rotation(new Matrix3f());
        var a=BaOrientation.sample(identity,identity,p);
        Matrix3f camera=new Matrix3f().rotationY(.7f);
        var b=BaOrientation.sample(identity,rotation(camera),p);
        assertArrayEquals(a.objectDirection(),b.objectDirection(),1e-6f);
        Vector3f worldIncoming=new Vector3f(.2f,.3f,1).normalize();
        Vector3f first=new Matrix3f().set(a.viewToReference()).transform(new Vector3f(worldIncoming));
        Vector3f viewIncoming=new Matrix3f(camera).invert().transform(new Vector3f(worldIncoming));
        Vector3f second=new Matrix3f().set(b.viewToReference()).transform(viewIncoming);
        assertEquals(first.x,second.x,1e-6); assertEquals(first.y,second.y,1e-6); assertEquals(first.z,second.z,1e-6);
    }
    @Test void objectRotationMovesDriverAndScaleDoesNotChangeDirection() {
        var p=BaMaterial.defaults(BaMaterial.Type.HAIR);
        var camera=rotation(new Matrix3f()); var part=new Matrix3f().rotationY(.6f);
        var before=BaOrientation.sample(camera,camera,p); var after=BaOrientation.sample(rotation(part),camera,p);
        assertFalse(new Vector3f(before.objectDirection()).equals(new Vector3f(after.objectDirection()),1e-4f));
        var scaled=BaOrientation.sample(rotation(new Matrix3f(part).scale(2,3,4)),camera,p);
        assertArrayEquals(after.objectDirection(),scaled.objectDirection(),1e-5f);
        assertEquals(1,new Vector3f(after.objectDirection()).length(),1e-6);
        float[] leaked=after.objectDirection(); leaked[0]=100;
        assertNotEquals(100,after.objectDirection()[0]);
        assertThrows(IllegalArgumentException.class,()->BaOrientation.sample(rotation(new Matrix3f().zero()),camera,p));
    }
}
