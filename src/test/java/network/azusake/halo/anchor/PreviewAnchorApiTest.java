package network.azusake.halo.anchor;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import network.azusake.halo.api.v2.*;
import network.azusake.halo.core.runtime.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PreviewAnchorApiTest {
    private static final UUID WEARER = new UUID(0, 1), PET = new UUID(0, 2);
    private static final float[] ROOT = {30,0,0,0, 0,-30,0,0, 0,0,30,0, 100,200,0,1};
    private static final PreviewAnchorPose A = new PreviewAnchorPose(1,2,3,new AnchorRotation(0,0,0,1));
    private static final PreviewAnchorPose B = new PreviewAnchorPose(4,5,6,new AnchorRotation(0,1,0,0));

    @Test void modelOverridesFallbackAndFirstLiveProviderWins() {
        var host = new PreviewAnchorHost();
        try (var first = HaloAnchorApi.register("test:first"); var second = HaloAnchorApi.register("test:second");
             var scope = host.open(WEARER, 7, ROOT)) {
            var context = HaloAnchorApi.currentPreviewContext();
            assertSame(scope.context(), context);
            assertEquals(WEARER, context.wearer()); assertEquals(7, context.runtimeId());
            assertTrue(scope.submitFallback(A, PreviewAnchorScope.Fallback.POSED));
            assertFalse(scope.submitFallback(B, PreviewAnchorScope.Fallback.POSED));
            assertFalse(context.hasModelAnchor()); assertEquals(A, scope.resolved());
            assertTrue(scope.submitFallback(B, PreviewAnchorScope.Fallback.RENDERED));
            assertEquals(B, scope.resolved());
            assertTrue(first.submitPreview(context,A));
            assertTrue(context.hasModelAnchor());
            assertFalse(second.submitPreview(context,B)); assertFalse(first.submitPreview(context,B));
            assertEquals(A,scope.resolved());
            first.close(); first.close();
            assertFalse(context.hasModelAnchor()); assertEquals(B,scope.resolved());
            assertFalse(first.submitPreview(context,A));
            assertTrue(second.submitPreview(context,B));
        }
        assertNull(HaloAnchorApi.currentPreviewContext()); assertFalse(HaloAnchorApi.isPreviewRendering());
    }

    @Test void nestedViewsSameWearerAreIndependentAndRejectSuspendedOrStaleContexts() {
        var host = new PreviewAnchorHost();
        try (var source = HaloAnchorApi.register("test:nested")) {
            PreviewAnchorContext previous;
            try (var outer = host.open(WEARER, 1, ROOT)) {
                previous = outer.context(); assertTrue(source.submitPreview(previous,A));
                try (var inner = host.open(WEARER,1,ROOT)) {
                    assertNotEquals(previous.renderId(),inner.context().renderId());
                    assertFalse(previous.isActive()); assertFalse(source.submitPreview(previous,B));
                    assertNull(outer.resolved()); assertNull(inner.resolved());
                    assertTrue(source.submitPreview(inner.context(),B)); assertEquals(B,inner.resolved());
                    assertThrows(IllegalStateException.class,outer::close);
                }
                assertTrue(previous.isActive()); assertEquals(A,outer.resolved());
            }
            try (var next = host.open(WEARER,1,ROOT)) {
                assertNull(next.resolved()); assertFalse(source.submitPreview(previous,A));
            }
        }
    }

    @Test void entityScopesRejectPetsAndReusedRuntimeIdsWithoutLosingOuterCapture() {
        var host = new PreviewAnchorHost();
        try (var source = HaloAnchorApi.register("test:entities"); var scope=host.open(WEARER,1,ROOT)) {
            var context=scope.context();
            PreviewAnchorHost.beginEntityRender(WEARER,1);
            assertSame(context,HaloAnchorApi.currentPreviewContext());
            PreviewAnchorHost.beginEntityRender(PET,2);
            assertTrue(HaloAnchorApi.isPreviewRendering()); assertNull(HaloAnchorApi.currentPreviewContext());
            assertFalse(source.submitPreview(context,A));
            assertFalse(scope.submitFallback(A,PreviewAnchorScope.Fallback.RENDERED));
            PreviewAnchorHost.endEntityRender();
            PreviewAnchorHost.beginEntityRender(WEARER,2);
            assertFalse(source.submitPreview(context,A));
            PreviewAnchorHost.endEntityRender();
            assertTrue(source.submitPreview(context,A));
            PreviewAnchorHost.endEntityRender();
            assertEquals(A,scope.resolved());
        }
    }

    @Test void wrongThreadCannotReadSubmitCloseOrInvalidateTheOwningHost() throws Exception {
        var host = new PreviewAnchorHost();
        try (var source=HaloAnchorApi.register("test:thread"); var scope=host.open(WEARER,1,ROOT)) {
            var checked=new AtomicBoolean();
            var worker=new Thread(()->{
                try {
                    assertNull(HaloAnchorApi.currentPreviewContext()); assertFalse(HaloAnchorApi.isPreviewRendering());
                    assertFalse(scope.context().isActive()); assertFalse(source.submitPreview(scope.context(),A));
                    assertThrows(IllegalStateException.class,scope::close);
                    assertThrows(IllegalStateException.class,host::clear);
                    checked.set(true);
                } catch (AssertionError ignored) { }
            });
            worker.start(); worker.join(); assertTrue(checked.get());
            assertTrue(source.submitPreview(scope.context(),A));
        }
    }

    @Test void hostCanMapAProxyRenderEntityToARealWearer() {
        var host=new PreviewAnchorHost();
        try(var source=HaloAnchorApi.register("test:proxy"); var scope=host.open(WEARER,1,PET,9,ROOT)) {
            assertEquals(WEARER,scope.context().wearer()); assertEquals(1,scope.context().runtimeId());
            assertEquals(PET,scope.context().renderedEntity()); assertEquals(9,scope.context().renderedRuntimeId());
            PreviewAnchorHost.beginEntityRender(WEARER,1);
            assertFalse(source.submitPreview(scope.context(),A));
            PreviewAnchorHost.endEntityRender();
            PreviewAnchorHost.beginEntityRender(PET,9);
            assertTrue(source.submitPreview(scope.context(),A));
            PreviewAnchorHost.endEntityRender(); assertEquals(A,scope.resolved());
        }
    }

    @Test void hostInvalidationAndExceptionsReleaseOnlyTheirScopesWhileSourcesSurvive() {
        var firstHost=new PreviewAnchorHost(); var secondHost=new PreviewAnchorHost();
        try (var source=HaloAnchorApi.register("test:reset"); var outer=firstHost.open(WEARER,1,ROOT)) {
            try (var inner=secondHost.open(WEARER,1,ROOT)) {
                firstHost.clear();
                assertFalse(outer.context().isActive()); assertNull(outer.resolved());
                assertTrue(source.submitPreview(inner.context(),A));
                secondHost.clear(); assertFalse(source.submitPreview(inner.context(),B));
            }
            assertTrue(HaloAnchorApi.isPreviewRendering());
            assertNull(HaloAnchorApi.currentPreviewContext());
            assertThrows(IllegalStateException.class,()->{
                try(var scope=firstHost.open(WEARER,1,ROOT)) {
                    assertTrue(source.submitPreview(scope.context(),B)); throw new IllegalStateException("renderer failed");
                }
            });
            assertTrue(HaloAnchorApi.isPreviewRendering());
            assertNull(HaloAnchorApi.currentPreviewContext());
            try(var recovered=firstHost.open(WEARER,1,ROOT)) { assertTrue(source.submitPreview(recovered.context(),A)); }
        }
        assertFalse(HaloAnchorApi.isPreviewRendering());
    }

    @Test void invalidatingDuringEntityRenderKeepsRoutingInsideTheInvalidatedViewUntilClose() {
        var host=new PreviewAnchorHost();
        try(var source=HaloAnchorApi.register("test:invalidate-active")) {
            try(var scope=host.open(WEARER,1,ROOT)) {
                PreviewAnchorHost.beginEntityRender(WEARER,1);
                assertTrue(source.submitPreview(scope.context(),A));
                host.clear();
                assertNull(scope.resolved()); assertFalse(source.submitPreview(scope.context(),A));
                assertTrue(HaloAnchorApi.isPreviewRendering()); assertNull(HaloAnchorApi.currentPreviewContext());
                PreviewAnchorHost.endEntityRender();
            }
            assertFalse(HaloAnchorApi.isPreviewRendering());
        }
    }

    @Test void registrationAndInputValuesHaveExplicitValidationAndDefensiveCopies() {
        assertThrows(IllegalArgumentException.class,()->HaloAnchorApi.register("Not Valid"));
        try(var source=HaloAnchorApi.register("test:unique")) {
            assertThrows(IllegalStateException.class,()->HaloAnchorApi.register("test:unique"));
            assertFalse(source.submitPreview(null,A)); source.close();
            try(var replacement=HaloAnchorApi.register("test:unique")) { assertNotSame(source,replacement); }
        }
        assertThrows(IllegalArgumentException.class,()->new PreviewAnchorPose(Double.NaN,0,0,new AnchorRotation(0,0,0,1)));
        var host=new PreviewAnchorHost(); var root=ROOT.clone();
        try(var scope=host.open(WEARER,1,root)) {
            root[12]=999; var returned=scope.context().sceneToView(); returned[12]=888;
            assertArrayEquals(ROOT,scope.context().sceneToView());
        }
        assertThrows(IllegalArgumentException.class,()->host.open(WEARER,1,new float[15]));
        root[0]=Float.NaN;
        assertThrows(IllegalArgumentException.class,()->host.open(WEARER,1,root));
        assertFalse(HaloAnchorApi.isPreviewRendering());
    }

    @Test void previewCannotOverwriteAnEnclosingWorldApiV2Capture() {
        var host=new PreviewAnchorHost(); var world=new Object();
        var worldPose=new AnchorPose(new AnchorVec3(100,200,300),new AnchorRotation(0,0,0,1));
        var position=new AnchorVec3(100,199,300);
        AnchorCaptureCoordinator.beginFrame(world);
        try(var source=HaloAnchorApi.register("test:world-preview-isolation")) {
            AnchorCaptureCoordinator.beginEntityRender(WEARER,1,world,position,true);
            try {
                assertTrue(source.submit(WEARER,worldPose));
                try(var scope=host.open(WEARER,1,ROOT)) {
                    assertFalse(source.submit(WEARER,new AnchorPose(new AnchorVec3(0,0,0),worldPose.rotation())));
                }
                assertEquals(worldPose,AnchorCaptureCoordinator.resolve(WEARER,1,world,position));
                assertTrue(source.submit(WEARER,worldPose));
            } finally { AnchorCaptureCoordinator.endEntityRender(); AnchorCaptureCoordinator.clearCaptures(); }
        }
    }
}
