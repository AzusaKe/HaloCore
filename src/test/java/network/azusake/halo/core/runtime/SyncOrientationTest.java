package network.azusake.halo.core.runtime;

import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import network.azusake.halo.api.v2.*;
import network.azusake.halo.config.HaloConfig;
import network.azusake.halo.core.Identifier;
import network.azusake.halo.core.Vec3d;
import network.azusake.halo.core.render.*;
import network.azusake.halo.data.HaloDefinition;
import network.azusake.halo.json.HaloDefinitionDeserializer;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises actual world bodies and asymmetric geometry, independent of group transforms. */
class SyncOrientationTest {
    private static final UUID WEARER = new UUID(0, 1);
    private static final Identifier ID = new Identifier("halo:sync_test");
    private static final Vec3d OFFSET = new Vec3d(.2, .35, -.45);
    private static final List<Quaternionf> HEADS = List.of(new Quaternionf(),
        new Quaternionf().rotationYXZ(1.2f, -.6f, .4f),
        new Quaternionf().rotationYXZ(-2.4f, .9f, -1.1f),
        new Quaternionf().rotationYXZ(3.1f, -1.2f, 2.2f));
    private final AtomicLong time = new AtomicLong(10_000);
    private final FrameScene.CameraSample camera = new FrameScene.CameraSample(
        Vec3d.ZERO, new Vec3d(0, 1, 0), new Vec3d(1, 0, 0));

    @Test void firstCaptureUsesHeadLocalAxesForIdentitySeiaAndMultiAxisOffsets() {
        for (String angles : List.of("[0,0,0]", "[0,-90,180]", "[23,-61,47]")) {
            for (Vec3d offset : List.of(OFFSET, new Vec3d(0, 0, -.45), new Vec3d(0, 0, -.5))) {
                HaloDefinition def = definition("sync", angles, offset, "halo:test");
                for (Quaternionf head : HEADS) {
                    ClientRuntime client = client(def);
                    FrameOutput output = client.renderFrame(world(head, 0));
                    Quaternionf expected = new Quaternionf(head).mul(def.model().syncOffset()).mul(rest(offset));
                    assertRotation(expected, body(client));
                    assertAsymmetricGeometry(expected, client.bodyPoses().get(WEARER).position(), output);
                }
            }
        }
    }

    @Test void capturedRelativeRotationStaysFixedWhileTheHeadAndPositionMove() {
        HaloDefinition def = definition("sync", "[23,-61,47]", OFFSET, "halo:test");
        for (Quaternionf first : HEADS) {
            ClientRuntime client = client(def);
            client.renderFrame(world(first, 0));
            Quaternionf relative = new Quaternionf(first).conjugate().mul(body(client));
            for (int frame = 0; frame < 30; frame++) {
                time.addAndGet(new int[]{8, 16, 33, 50}[frame % 4]);
                Quaternionf head = new Quaternionf().rotationYXZ(frame * .17f, frame * -.03f, frame * .02f);
                client.renderFrame(world(head, frame * .1));
                assertRotation(relative, new Quaternionf(head).conjugate().mul(body(client)));
            }
        }
    }

    @Test void teleportAndChangedOrientationInputsRecaptureWithoutReplacingTheWearer() {
        HaloDefinition original = definition("sync", "[0,-90,180]", OFFSET, "halo:test");
        ClientRuntime client = client(original);
        var instance = client.getInstance(WEARER);
        long createdAt = instance.getCreatedAtTime();
        client.renderFrame(world(HEADS.get(1), 0));
        time.addAndGet(16);
        client.teleport(WEARER);
        client.renderFrame(world(HEADS.get(2), 3));
        assertRotation(new Quaternionf(HEADS.get(2)).mul(original.model().syncOffset()).mul(rest(OFFSET)), body(client));

        for (HaloDefinition changed : List.of(
            definition("sync", "[23,-61,47]", OFFSET, "halo:changed_texture"),
            definition("sync", "[23,-61,47]", new Vec3d(-.3, .1, -.6), "halo:changed_texture"),
            definition("free", "[0,0,0]", OFFSET, "halo:test"), original)) {
            client.definitions(Map.of(ID, changed));
            time.addAndGet(16);
            FrameScene input = world(HEADS.get(3), 4);
            FrameOutput actual = client.renderFrame(input);
            ClientRuntime fresh = client(changed);
            FrameOutput expected = fresh.renderFrame(input);
            assertRotation(body(fresh), body(client));
            assertEquals(expected.expandedBatches(VisualResources.EMPTY), actual.expandedBatches(VisualResources.EMPTY));
            assertSame(instance, client.getInstance(WEARER));
            assertEquals(createdAt, instance.getCreatedAtTime());
            assertEquals(ID, client.assignments().get(WEARER));
        }
    }

    @Test void runtimeOffsetOverrideRecapturesAtTheNewTarget() {
        HaloDefinition def = definition("sync", "[23,-61,47]", OFFSET, "halo:test");
        ClientRuntime client = client(def);
        client.renderFrame(world(HEADS.get(1), 0));
        Vec3d offset = new Vec3d(-.4, .25, -.2);
        HaloConfig config = new HaloConfig();
        config.setPositionOffset(offset);
        client.setConfig(config);
        time.addAndGet(16);
        client.renderFrame(world(HEADS.get(2), 3));
        assertRotation(new Quaternionf(HEADS.get(2)).mul(def.model().syncOffset()).mul(rest(offset)), body(client));
        Vector3f local = HEADS.get(2).transform(new Vector3f((float)-offset.x, (float)offset.y, (float)-offset.z));
        assertEquals(3 + local.x, client.bodyPoses().get(WEARER).position().x, 1e-5);
    }

    @Test void physicalAndRigidPreviewsMatchWorldAtRestAndPhysicalSessionsStayIndependent() {
        HaloDefinition def = definition("sync", "[0,-90,180]", OFFSET, "halo:test");
        for (Quaternionf head : HEADS) {
            ClientRuntime client = client(def);
            FrameOutput world = client.renderFrame(world(head, 0));
            try (var rigid = client.openPreview(); var physical = client.openPreview(PreviewOptions.PHYSICS);
                 var other = client.openPreview(PreviewOptions.PHYSICS)) {
                assertBatches(world, rigid.render(preview(head, 0)));
                assertBatches(world, physical.render(preview(head, 0)));
                other.render(preview(HEADS.get(2), 5));
                time.addAndGet(16);
                FrameOutput moved = client.renderFrame(world(HEADS.get(3), .5));
                assertBatches(moved, physical.render(preview(HEADS.get(3), .5)));
            }
        }
    }

    @Test void unrelatedDefinitionReloadKeepsWorldAndPreviewMotionAndAnimationClocks() {
        HaloDefinition def = definition("sync", "[23,-61,47]", OFFSET, "halo:test");
        ClientRuntime baseline = client(def), reloaded = client(def);
        baseline.renderFrame(world(HEADS.get(1), 0));
        reloaded.renderFrame(world(HEADS.get(1), 0));
        var instance = reloaded.getInstance(WEARER);
        try (var a = baseline.openPreview(PreviewOptions.PHYSICS); var b = reloaded.openPreview(PreviewOptions.PHYSICS)) {
            a.render(preview(HEADS.get(1), 0)); b.render(preview(HEADS.get(1), 0));
            for (int frame = 1; frame <= 3; frame++) {
                time.addAndGet(16);
                if (frame == 2) reloaded.definitions(Map.of(ID,
                    definition("sync", "[23,-61,47]", OFFSET, "halo:changed_texture")));
                baseline.renderFrame(world(HEADS.get(frame), frame * .3));
                reloaded.renderFrame(world(HEADS.get(frame), frame * .3));
                assertEquals(baseline.bodyPoses(), reloaded.bodyPoses());
                assertVertexPositions(a.render(preview(HEADS.get(frame), frame * .3)),
                    b.render(preview(HEADS.get(frame), frame * .3)));
            }
            assertSame(instance, reloaded.getInstance(WEARER));
        }
    }

    @Test void changedInputsResetPhysicalPreviewEvenForRepeatedTimestamp() {
        ClientRuntime client = client(definition("sync", "[0,-90,180]", OFFSET, "halo:test"));
        client.renderFrame(world(HEADS.get(1), 0));
        try (var physical = client.openPreview(PreviewOptions.PHYSICS)) {
            physical.render(preview(HEADS.get(1), 0));
            time.addAndGet(16);
            physical.render(preview(HEADS.get(2), .5));
            HaloDefinition changed = definition("sync", "[23,-61,47]", new Vec3d(-.3, .1, -.6), "halo:test");
            client.definitions(Map.of(ID, changed));
            FrameOutput world = client.renderFrame(world(HEADS.get(3), 1));
            try (var fresh = client.openPreview(PreviewOptions.PHYSICS)) {
                FrameOutput actual = physical.render(preview(HEADS.get(3), 1));
                assertBatches(fresh.render(preview(HEADS.get(3), 1)), actual);
                assertBatches(world, actual);
            }
        }
    }

    private ClientRuntime client(HaloDefinition def) {
        ClientRuntime client = new ClientRuntime(time::get);
        client.definitions(Map.of(ID, def)); client.attach(WEARER, ID, false);
        return client;
    }

    private static HaloDefinition definition(String mode, String angles, Vec3d offset, String texture) {
        String json = """
            {"id":"halo:sync_test","orientation_mode":"%s","sync_offset":%s,
             "positioning":{"offset":[%s,%s,%s]},
             "layers":[{"primitive":{"type":"billboard","texture":"%s","size":[0.8,0.3]}}]}
            """.formatted(mode, angles, offset.x, offset.y, offset.z, texture);
        return new HaloDefinitionDeserializer().deserialize(JsonParser.parseString(json), HaloDefinition.class, null);
    }

    private FrameScene world(Quaternionf head, double x) {
        AnchorPose pose = pose(head, x);
        var sample = new FrameScene.EntitySample(WEARER, 1, new Vec3d(x, 0, 0), true, false, false, pose, pose);
        return new FrameScene(1, time.get(), time.get() * 1_000_000, camera, Map.of(WEARER, sample),
            new Matrix4f().get(new float[16]), p -> 1, t -> true);
    }

    private PreviewFrame preview(Quaternionf head, double x) {
        return new PreviewFrame(WEARER, 1, pose(head, x), camera, new Matrix4f().get(new float[16]),
            time.get(), time.get() * 1_000_000, LightSample.FULL_BRIGHT, t -> true, VisualResources.EMPTY);
    }

    private static AnchorPose pose(Quaternionf head, double x) {
        return new AnchorPose(new AnchorVec3(x, 0, 0), new AnchorRotation(head.x, head.y, head.z, head.w));
    }

    // Independent geometric oracle: +Y is head-to-target radial, +Z points to the head-up pole.
    private static Quaternionf rest(Vec3d offset) {
        Vector3f y = new Vector3f((float)-offset.x, (float)offset.y, (float)-offset.z).normalize();
        Vector3f z = new Vector3f(0, 1, 0).sub(new Vector3f(y).mul(y.y)).normalize();
        Vector3f x = new Vector3f(y).cross(z).normalize();
        return new Quaternionf().setFromNormalized(new Matrix3f().setColumn(0, x).setColumn(1, y).setColumn(2, z));
    }

    private static Quaternionf body(ClientRuntime client) {
        var q = client.bodyPoses().get(WEARER).rotation();
        return new Quaternionf((float)q.x(), (float)q.y(), (float)q.z(), (float)q.w());
    }

    private static void assertRotation(Quaternionf expected, Quaternionf actual) {
        assertEquals(1, actual.lengthSquared(), 2e-5);
        for (Vector3f axis : List.of(new Vector3f(1, 0, 0), new Vector3f(0, 1, 0), new Vector3f(0, 0, 1))) {
            Vector3f e = expected.transform(new Vector3f(axis)), a = actual.transform(new Vector3f(axis));
            assertEquals(e.x, a.x, 2e-5); assertEquals(e.y, a.y, 2e-5); assertEquals(e.z, a.z, 2e-5);
        }
    }

    private static void assertAsymmetricGeometry(Quaternionf expected, Vec3d position, FrameOutput output) {
        var vertices = output.expandedBatches(VisualResources.EMPTY).get(0).vertices();
        assertEquals(4, vertices.size());
        // UVs identify each signed corner, so a 180-degree flip cannot pass by preserving the bounds.
        for (int index = 0; index < vertices.size(); index++) {
            var v = vertices.get(index);
            Vector3f local = new Quaternionf(expected).conjugate().transform(
                new Vector3f(v.x() - (float)position.x, v.y() - (float)position.y, v.z() - (float)position.z));
            assertEquals(0, local.y, 2e-5);
            assertEquals(v.u() == 0 ? -.4 : .4, local.x, 2e-5);
            assertEquals(v.v() == 0 ? .15 : -.15, local.z, 2e-5);
        }
    }

    private static void assertBatches(FrameOutput expected, FrameOutput actual) {
        assertVertexPositions(expected, actual);
        var a = expected.expandedBatches(VisualResources.EMPTY).get(0);
        var b = actual.expandedBatches(VisualResources.EMPTY).get(0);
        assertEquals(a.texture(), b.texture()); assertEquals(a.light(), b.light());
        for (int i = 0; i < a.vertices().size(); i++) {
            var av = a.vertices().get(i); var bv = b.vertices().get(i);
            assertEquals(av.u(), bv.u()); assertEquals(av.v(), bv.v());
            assertEquals(av.normalX(), bv.normalX(), 1e-5);
            assertEquals(av.normalY(), bv.normalY(), 1e-5);
            assertEquals(av.normalZ(), bv.normalZ(), 1e-5);
        }
    }

    private static void assertVertexPositions(FrameOutput expected, FrameOutput actual) {
        var a = expected.expandedBatches(VisualResources.EMPTY).get(0).vertices();
        var b = actual.expandedBatches(VisualResources.EMPTY).get(0).vertices();
        for (int i = 0; i < a.size(); i++) {
            assertEquals(a.get(i).x(), b.get(i).x(), 1e-5);
            assertEquals(a.get(i).y(), b.get(i).y(), 1e-5);
            assertEquals(a.get(i).z(), b.get(i).z(), 1e-5);
        }
    }
}
