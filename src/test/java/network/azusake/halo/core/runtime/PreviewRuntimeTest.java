package network.azusake.halo.core.runtime;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import network.azusake.halo.api.v2.*;
import network.azusake.halo.core.*;
import network.azusake.halo.core.render.*;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PreviewRuntimeTest {
    private static final UUID WEARER = new UUID(0, 1);
    private static final Identifier ID = new Identifier("halo:test"), MODEL = new Identifier("halo:test.obj"),
        TEX = new Identifier("halo:test.png"), MASK = new Identifier("halo:mask.png");
    private static final AnchorPose HEAD = pose(0, 0, 0, new Quaternionf());
    private static final FrameScene.CameraSample CAMERA = new FrameScene.CameraSample(new Vec3d(0, 0, 0),
        new Vec3d(0, 1, 0), new Vec3d(1, 0, 0));
    private static final String BILLBOARD = "{\"type\":\"billboard\",\"texture\":\"halo:test.png\",\"size\":[1,1]}";
    private static final String MIXED = """
        {"id":"parent","position":[0.2,0.1,0.3],"scale":1.2,
         "animation":{"alpha":[{"function":"linear","start":0.8,"speed":0}]},
         "children":[{"id":"child","rotation":[15,20,25],"glowing":false,"primitives":[
           {"type":"billboard","texture":"halo:test.png","size":[1,1]},
           {"type":"ring","texture":"halo:test.png","size":[0.5,0.1],"segments":12},
           {"type":"mesh","model":"halo:test.obj","texture":"halo:test.png","size":[1,1,0],
            "material":{"effects":[{"type":"alpha_mask","texture":"halo:mask.png",
              "uv_offset":{"u":[{"function":"linear","speed":0.25}]}}]}}
         ]}]}
        """;
    private static final VisualResources ASSETS = new VisualResources(1,
        Map.of(MODEL, ObjMeshLoader.parse(MODEL, "v 0 0 0\nv 1 0 0\nv 0 1 0\nvt 0 0\nvt 1 0\nvt 0 1\nf 1/1 2/2 3/3")),
        Map.of(TEX, new VisualResources.TextureInfo(4, 4, true), MASK, new VisualResources.TextureInfo(3, 5, true)));
    private final AtomicLong time = new AtomicLong(10_000);

    private ClientRuntime client(String extra, String groups) {
        var resources = new DefinitionResources();
        var problems = resources.reload(1, List.of(new ResourceInput(ID, "test", "{\"id\":\"halo:test\","
            + "\"positioning\":{\"offset\":[0,0.5,0],\"scale\":1}," + extra + "\"layers\":[" + groups + "]}")));
        assertTrue(problems.isEmpty(), problems.toString());
        var client = new ClientRuntime(time::get);
        client.definitions(resources.snapshot());
        client.attach(WEARER, ID, false);
        return client;
    }
    private FrameScene world(AnchorPose head, boolean sleeping, boolean invisible, boolean loaded, long world, VisualResources assets) {
        var p = head.position();
        var entity = new FrameScene.EntitySample(WEARER, 1, new Vec3d(p.x(), p.y(), p.z()),
            true, sleeping, invisible, head, head);
        return new FrameScene(world, time.get(), time.get() * 1_000_000, CAMERA,
            loaded ? Map.of(WEARER, entity) : Map.of(), new Matrix4f().get(new float[16]),
            position -> 1, texture -> true, assets, position -> LightSample.FULL_BRIGHT);
    }
    private FrameScene world() { return world(HEAD, false, false, true, 1, ASSETS); }
    private PreviewFrame preview(AnchorPose head, Matrix4f root, VisualResources assets) {
        return new PreviewFrame(WEARER, 1, head, CAMERA, root.get(new float[16]), time.get(),
            time.get() * 1_000_000, LightSample.FULL_BRIGHT, texture -> true, assets);
    }
    private PreviewFrame preview() { return preview(HEAD, new Matrix4f(), ASSETS); }
    private static AnchorPose pose(double x, double y, double z, Quaternionf q) {
        return new AnchorPose(new AnchorVec3(x, y, z), new AnchorRotation(q.x, q.y, q.z, q.w));
    }
    private static List<DrawBatch> expanded(FrameOutput output) { return output.expandedBatches(ASSETS); }
    private static boolean empty(FrameOutput output) { return output.legacyBatches().isEmpty() && output.meshes().isEmpty(); }

    @Test void allPrimitivesShareWorldGeometryAnimationLightingAndMasks() {
        var client = client("\"orientation_mode\":\"free\",\"animation\":{\"offset\":{\"x\":[{\"function\":\"linear\",\"speed\":0.1}]}},", MIXED);
        time.addAndGet(500);
        var world = client.renderFrame(world());
        assertEquals(1, world.meshes().size());
        assertTrue(world.legacyBatches().size() >= 2);
        try (var session = client.openPreview()) {
            var ui = session.render(preview());
            assertEquals(expanded(world), expanded(ui));
            assertEquals(.125f, ui.meshes().get(0).material().mask().offsetU());
            assertTrue(ui.meshes().get(0).directionalLighting());
            assertEquals(LightSample.FULL_BRIGHT, ui.meshes().get(0).light());
        }
    }

    @Test void previewsDoNotAdvanceWorldPhysicsSnapFlagsOrAppearanceClock() {
        var baseline = client("", MIXED);
        var extraViews = client("", MIXED);
        for (int frame = 0; frame < 12; frame++) {
            time.addAndGet(16);
            var sample = world(pose(frame * .3, 0, 0, new Quaternionf().rotateY(frame * .2f)), false, false, true, 1, ASSETS);
            assertEquals(expanded(baseline.renderFrame(sample)), expanded(extraViews.renderFrame(sample)));
            var bodies = extraViews.bodyPoses();
            var statuses = extraViews.diagnostics();
            extraViews.teleport(WEARER);
            baseline.teleport(WEARER);
            try (var first = extraViews.openPreview(); var second = extraViews.openPreview()) {
                var a = first.render(preview());
                for (int repeat = 0; repeat < 4; repeat++) second.render(preview(pose(100, 20, 30,
                    new Quaternionf().rotateXYZ(.8f, .6f, .3f)), new Matrix4f().scaling(30, -30, 30), ASSETS));
                assertEquals(expanded(a), expanded(first.render(preview())));
            }
            assertEquals(bodies, extraViews.bodyPoses());
            assertTrue(extraViews.getInstance(WEARER).isNeedsSnap());
            assertEquals(statuses.get(WEARER).createdAt(), extraViews.diagnostics().get(WEARER).createdAt());
        }
    }

    @Test void rigidMotionIsHeadLocalForEveryOrientationModeAndDoesNotDependOnFirstPose() {
        List<DrawBatch> expected = null;
        for (String mode : List.of("free", "locked", "sync")) {
            var client = client("\"orientation_mode\":\"" + mode + "\",", "{\"primitive\":" + BILLBOARD + "}");
            client.renderFrame(world());
            try (var session = client.openPreview()) {
                var rest = expanded(session.render(preview())).get(0).vertices();
                for (var rotation : List.of(new Quaternionf().rotateY(1.3f), new Quaternionf().rotateXYZ(.8f, -.7f, .4f))) {
                    var ui = expanded(session.render(preview(pose(2, 3, 4, rotation), new Matrix4f(), ASSETS)));
                    for (int index = 0; index < rest.size(); index++) {
                        var vertex = rest.get(index);
                        var target = rotation.transform(new Vector3f(vertex.x(), vertex.y(), vertex.z())).add(2, 3, 4);
                        var actual = ui.get(0).vertices().get(index);
                        assertEquals(target.x, actual.x(), 1e-5);
                        assertEquals(target.y, actual.y(), 1e-5);
                        assertEquals(target.z, actual.z(), 1e-5);
                    }
                }
                var result = expanded(session.render(preview()));
                if (expected == null) expected = result; else assertEquals(expected, result);
                try (var reopened = client.openPreview()) { assertEquals(result, expanded(reopened.render(preview()))); }
            }
        }
    }

    @Test void previewExistsOutsideWorldDrawDistanceAndNeedsMatchingWearerAndResources() {
        var client = client("", MIXED);
        assertTrue(empty(client.renderFrame(world(pose(20_000, 0, 0, new Quaternionf()), false, false, true, 1, ASSETS))));
        try (var session = client.openPreview()) {
            assertFalse(empty(session.render(preview())));
            assertTrue(empty(session.render(preview(HEAD, new Matrix4f(), VisualResources.EMPTY))));
            var p = preview();
            assertTrue(empty(session.render(new PreviewFrame(WEARER, 99, p.head(), p.camera(), p.rootTransform(),
                p.timeMillis(), p.frameNanos(), p.light(), p.textures(), p.visuals()))));
        }
    }

    @Test void transitionsAndHiddenStatesUseTheSameFrameAndNeverRestartOnOpening() {
        var client = client("\"hide_on_sleep\":true,\"display_in_invisible\":false,"
            + "\"startup\":{\"segments\":[{\"duration\":1,\"alpha\":{\"from\":0}}]},"
            + "\"shutdown\":{\"segments\":[{\"duration\":1,\"alpha\":{\"to\":0}}]},", MIXED);
        client.clear(); client.attach(WEARER, ID, true);
        for (long millis : new long[]{10_250, 10_750, 11_250}) {
            time.set(millis);
            var world = client.renderFrame(world());
            try (var session = client.openPreview()) { assertEquals(expanded(world), expanded(session.render(preview()))); }
        }
        client.hide(WEARER, ID);
        time.set(11_500);
        var ending = client.renderFrame(world());
        assertFalse(empty(ending));
        assertFalse(client.assignments().containsKey(WEARER));
        try (var session = client.openPreview()) { assertEquals(expanded(ending), expanded(session.render(preview()))); }
        time.set(12_500); client.renderFrame(world());
        try (var session = client.openPreview()) { assertTrue(empty(session.render(preview()))); }

        client.attach(WEARER, ID, false);
        client.renderFrame(world(HEAD, false, true, true, 1, ASSETS));
        time.addAndGet(1100); client.renderFrame(world(HEAD, false, true, true, 1, ASSETS));
        try (var session = client.openPreview()) { assertTrue(empty(session.render(preview()))); }
        client.renderFrame(world()); time.addAndGet(1100); client.renderFrame(world());
        try (var session = client.openPreview()) { assertFalse(empty(session.render(preview()))); }
        client.renderFrame(world(HEAD, true, false, true, 1, ASSETS));
        time.addAndGet(1100); client.renderFrame(world(HEAD, true, false, true, 1, ASSETS));
        try (var session = client.openPreview()) { assertTrue(empty(session.render(preview()))); }
    }

    @Test void missingDefinitionsAssetsUnloadWorldChangeAndCloseDoNotLeakCachedAppearance() {
        var client = client("", MIXED);
        client.renderFrame(world());
        var session = client.openPreview();
        var definitions = client.getInstance(WEARER);
        client.definitions(Map.of());
        assertTrue(empty(session.render(preview())));
        client.renderFrame(world());
        assertSame(definitions, client.getInstance(WEARER));
        assertTrue(client.assignments().containsKey(WEARER));
        var restored = client("", MIXED);
        client.definitions(Map.of(ID, restored.definition(ID).orElseThrow()));
        client.renderFrame(world());
        assertFalse(empty(session.render(preview())));
        client.renderFrame(world(HEAD, false, false, false, 1, ASSETS));
        assertTrue(empty(session.render(preview())));
        client.renderFrame(world(HEAD, false, false, true, 2, ASSETS));
        assertTrue(empty(session.render(preview())));
        session.close(); session.close();
        assertTrue(empty(session.render(preview())));
        try (var newSession = client.openPreview()) {
            assertFalse(empty(newSession.render(preview())));
            client.clear();
            assertTrue(empty(newSession.render(preview())));
        }
    }

    @Test void mirroredRootsKeepMeshWindingAndCameraFacingQuadsUsePreviewCamera() {
        var client = client("", MIXED + ",{\"primitive\":" + BILLBOARD.replace("\"type\":\"billboard\"", "\"type\":\"billboard\",\"face_camera\":true") + "}");
        client.renderFrame(world());
        try (var session = client.openPreview()) {
            var result = session.render(preview(HEAD, new Matrix4f().scale(30, -30, 30), ASSETS));
            assertTrue(result.meshes().get(0).mirrored());
            var quad = result.legacyBatches().get(result.legacyBatches().size() - 1).vertices();
            for (var vertex : quad) assertEquals(quad.get(0).z(), vertex.z(), 1e-5);
            var input = preview(); var copy = input.rootTransform(); copy[0] = 999;
            assertEquals(1, input.rootTransform()[0]);
        }
    }

    @Test void zeroOffsetAndRuntimeScaleRemainFiniteAndFollowTheHeadWithoutHistory() {
        var client = client("", "{\"primitive\":" + BILLBOARD + "}");
        var config = new network.azusake.halo.config.HaloConfig();
        config.setPositionOffset(new Vec3d(0, 0, 0)); config.setHaloScale(2);
        client.setConfig(config); client.renderFrame(world());
        try (var session = client.openPreview()) {
            var p = pose(2, 3, 4, new Quaternionf().rotateXYZ(.4f, .9f, -.7f));
            var vertices = expanded(session.render(preview(p, new Matrix4f(), ASSETS))).get(0).vertices();
            assertEquals(2, vertices.stream().mapToDouble(DrawBatch.Vertex::x).average().orElseThrow(), 1e-5);
            assertEquals(3, vertices.stream().mapToDouble(DrawBatch.Vertex::y).average().orElseThrow(), 1e-5);
            assertEquals(4, vertices.stream().mapToDouble(DrawBatch.Vertex::z).average().orElseThrow(), 1e-5);
            var a = vertices.get(0); var b = vertices.get(1);
            assertEquals(2, new Vector3f(a.x()-b.x(), a.y()-b.y(), a.z()-b.z()).length(), 1e-5);
        }
    }
}
