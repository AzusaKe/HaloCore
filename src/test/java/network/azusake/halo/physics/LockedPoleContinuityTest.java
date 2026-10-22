package network.azusake.halo.physics;

import com.google.gson.JsonParser;
import java.util.UUID;
import network.azusake.halo.api.v2.*;
import network.azusake.halo.config.HaloConfig;
import network.azusake.halo.core.Vec3d;
import network.azusake.halo.data.HaloDefinition;
import network.azusake.halo.json.HaloDefinitionDeserializer;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LockedPoleContinuityTest {
    private static final UUID WEARER = new UUID(0, 1);

    @Test void smallSlowPitchAcrossStraightAheadDoesNotFlipAndRestKeepsFacingForward() {
        for (double height : new double[]{.3, .4}) {
            for (float yaw : new float[]{0, 70, 179, -100}) {
                AnchorFrameCalculator calculator = new AnchorFrameCalculator();
                HaloDefinition definition = definition(height);
                Vector3f previous = null;
                boolean first = true;
                for (float pitch : new float[]{-2, -1, -.5f, -.1f, 0, .1f, .5f, 1, 2, 1, 0, -1, -2}) {
                    Quaternionf head = AnchorFrameCalculator.buildHeadQuaternion(yaw, pitch, 0);
                    AnchorPose pose = pose(head);
                    AnchorFrame frame = null;
                    // Fully settle at every angle: this is not a transient lag or crossing the head.
                    for (int step = 0; step < 240; step++) {
                        frame = calculator.calculate(WEARER, first, pose, pose, definition, Vec3d.ZERO, .05, new HaloConfig());
                        first = false;
                    }
                    Quaternionf relative = new Quaternionf(head).conjugate().mul(frame.worldOrientation());
                    Vector3f forward = relative.transform(new Vector3f(0, 0, 1)).normalize();
                    assertTrue(forward.z > .999, "head-local forward="+forward+" yaw="+yaw+" pitch="+pitch);
                    if (previous != null) assertTrue(previous.dot(forward) > .999, "settled orientation flipped");
                    previous = forward;
                    assertEquals(height, frame.worldPosition().length(), 2e-6);
                }
            }
        }
    }

    @Test void subDegreePitchSequenceRemainsContinuousDuringDampingWithAndWithoutMomentum() {
        for (boolean momentum : new boolean[]{false, true}) {
            AnchorFrameCalculator calculator = new AnchorFrameCalculator();
            HaloConfig config = new HaloConfig(); config.setAllowAngularMomentum(momentum);
            Vector3f previous = null;
            double minimumDistance = Double.POSITIVE_INFINITY;
            for (int frame = 0; frame < 1800; frame++) {
                float pitch = (float)(2 * Math.sin(frame * .006));
                Quaternionf head = AnchorFrameCalculator.buildHeadQuaternion(70, pitch, 0);
                AnchorPose pose = pose(head);
                AnchorFrame result = calculator.calculate(WEARER, frame == 0, pose, pose,
                    definition(.4), Vec3d.ZERO, 1.0/60, config);
                Vector3f forward = new Quaternionf(head).conjugate().mul(result.worldOrientation())
                    .transform(new Vector3f(0, 0, 1)).normalize();
                if (previous != null) assertTrue(previous.dot(forward) > .999, "slow pitch introduced a spin jump");
                assertTrue(forward.z > .999, "polar forward changed sign: "+forward);
                previous = forward;
                minimumDistance = Math.min(minimumDistance, result.worldPosition().length());
            }
            assertTrue(minimumDistance > .39, "halo must remain above the head throughout the reproduction");
        }
    }

    @Test void polarReferenceFollowsHeadYawAndRollForWorldAndRigidPreview() {
        for (double height : new double[]{.3, .4, -.4, 0}) {
            HaloDefinition definition = definition(height);
            for (float yaw : new float[]{-170, 0, 75}) for (float roll : new float[]{-45, 0, 70}) {
                Quaternionf head = AnchorFrameCalculator.buildHeadQuaternion(yaw, 12, roll);
                AnchorPose pose = pose(head);
                AnchorFrame frame = new AnchorFrameCalculator().calculate(WEARER, true, pose, pose,
                    definition, Vec3d.ZERO, .05, new HaloConfig());
                Vector3f expected = head.transform(new Vector3f(0, 0, 1));
                Vector3f actual = frame.worldOrientation().transform(new Vector3f(0, 0, 1));
                assertTrue(expected.distance(actual) < 2e-5, "polar direction must use the head, not world up");
                AnchorFrame rigid = AnchorFrameCalculator.rigid(pose, definition, Vec3d.ZERO, new HaloConfig());
                assertTrue(expected.distance(rigid.worldOrientation().transform(new Vector3f(0, 0, 1))) < 2e-5);
            }
        }
    }

    @Test void tinyOffAxisOffsetsKeepThePolarReferenceAboveQuaternionNoise() {
        for (Vec3d offset : new Vec3d[]{new Vec3d(1e-8,.4,0), new Vec3d(0,.4,1e-8), new Vec3d(1e-7,.4,-1e-7)}) {
            for (float pitch : new float[]{-1, 0, 1}) {
                Quaternionf head = AnchorFrameCalculator.buildHeadQuaternion(70, pitch, 0);
                AnchorPose pose = pose(head);
                AnchorFrame frame = new AnchorFrameCalculator().calculate(WEARER, true, pose, pose,
                    definition(offset), Vec3d.ZERO, .05, new HaloConfig());
                Vector3f forward = new Quaternionf(head).conjugate().mul(frame.worldOrientation()).transform(new Vector3f(0,0,1));
                assertTrue(forward.z > .999, "near-polar normalization introduced an arbitrary spin");
            }
        }
    }

    private static HaloDefinition definition(double height) {
        return definition(new Vec3d(0,height,0));
    }

    private static HaloDefinition definition(Vec3d offset) {
        return new HaloDefinitionDeserializer().deserialize(JsonParser.parseString("""
            {"id":"halo:mesh_demo","orientation_mode":"locked",
             "positioning":{"offset":[%s,%s,%s]},"layers":[]}
            """.formatted(offset.x,offset.y,offset.z)), HaloDefinition.class, null);
    }

    private static AnchorPose pose(Quaternionf head) {
        return new AnchorPose(new AnchorVec3(0, 0, 0), new AnchorRotation(head.x, head.y, head.z, head.w));
    }
}
