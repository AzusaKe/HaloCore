package network.azusake.halo.anchor;

import java.util.UUID;
import network.azusake.halo.api.v2.*;
import network.azusake.halo.core.runtime.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UnifiedAnchorSourceTest {
    private static final UUID WEARER = new UUID(0, 7);
    private static final AnchorVec3 POSITION = new AnchorVec3(100, 60, 200);
    private static final AnchorRotation ROTATION = new AnchorRotation(0, 0, 0, 1);
    private static final AnchorPose WORLD_HEAD = new AnchorPose(new AnchorVec3(100, 62, 200), ROTATION);
    private static final PreviewAnchorPose PREVIEW_HEAD = new PreviewAnchorPose(0, 2, 0, ROTATION);
    private static final PreviewAnchorPose FALLBACK = new PreviewAnchorPose(0, 1.5, 0, ROTATION);
    private static final float[] ROOT = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};

    @Test void worldOnlySourceLeavesPreviewFallbackAndOtherProvidersUntouched() {
        Object world = beginWorld();
        try (var worldOnly = HaloAnchorApi.register("single:world");
             var previewOnly = HaloAnchorApi.register("single:preview")) {
            assertTrue(worldOnly.submit(WEARER, WORLD_HEAD));
            try (var view = new PreviewAnchorHost().open(WEARER, 1, ROOT)) {
                assertNull(view.resolved()); // No automatic copy of the world head.
                view.submitFallback(FALLBACK, PreviewAnchorScope.Fallback.RENDERED);
                assertEquals(FALLBACK, view.resolved());
                assertTrue(previewOnly.submitPreview(view.context(), PREVIEW_HEAD));
                worldOnly.close();
                assertEquals(PREVIEW_HEAD, view.resolved());
                assertNull(AnchorCaptureCoordinator.resolve(WEARER, 1, world, POSITION));
            }
        } finally { endWorld(); }
    }

    @Test void previewOnlySourceAndItsCloseDoNotChangeWorldCaptures() {
        Object world = beginWorld();
        try (var worldOnly = HaloAnchorApi.register("isolated:world");
             var previewOnly = HaloAnchorApi.register("isolated:preview")) {
            assertTrue(worldOnly.submit(WEARER, WORLD_HEAD));
            try (var view = new PreviewAnchorHost().open(WEARER, 1, ROOT)) {
                view.submitFallback(FALLBACK, PreviewAnchorScope.Fallback.RENDERED);
                assertTrue(previewOnly.submitPreview(view.context(), PREVIEW_HEAD));
                assertEquals(WORLD_HEAD, AnchorCaptureCoordinator.resolve(WEARER, 1, world, POSITION));
                previewOnly.close();
                assertEquals(FALLBACK, view.resolved());
                assertFalse(previewOnly.submitPreview(view.context(), PREVIEW_HEAD));
            }
            assertEquals(WORLD_HEAD, AnchorCaptureCoordinator.resolve(WEARER, 1, world, POSITION));
            assertTrue(worldOnly.submit(WEARER, WORLD_HEAD));
        } finally { endWorld(); }
    }

    @Test void oneHandleCanSupplyBothSpacesAndCloseInvalidatesOnlyThatHandle() {
        Object world = beginWorld();
        try (var source = HaloAnchorApi.register("shared:model")) {
            assertThrows(IllegalStateException.class, () -> HaloAnchorApi.register("shared:model"));
            assertTrue(source.submit(WEARER, WORLD_HEAD));
            try (var view = new PreviewAnchorHost().open(WEARER, 1, ROOT)) {
                assertNull(view.resolved());
                assertTrue(source.submitPreview(view.context(), PREVIEW_HEAD));
                assertEquals(WORLD_HEAD, AnchorCaptureCoordinator.resolve(WEARER, 1, world, POSITION));
                source.close();
                assertNull(view.resolved());
                assertNull(AnchorCaptureCoordinator.resolve(WEARER, 1, world, POSITION));
                try (var replacement = HaloAnchorApi.register("shared:model")) {
                    assertTrue(replacement.submitPreview(view.context(), FALLBACK));
                    source.close(); // The old handle cannot close the replacement.
                    assertEquals(FALLBACK, view.resolved());
                }
            }
            assertFalse(source.submit(WEARER, WORLD_HEAD));
        } finally { endWorld(); }
    }

    private static Object beginWorld() {
        Object world = new Object();
        AnchorCaptureCoordinator.beginFrame(world);
        AnchorCaptureCoordinator.beginEntityRender(WEARER, 1, world, POSITION, true);
        return world;
    }
    private static void endWorld() {
        AnchorCaptureCoordinator.endEntityRender();
        AnchorCaptureCoordinator.clearCaptures();
    }
}
