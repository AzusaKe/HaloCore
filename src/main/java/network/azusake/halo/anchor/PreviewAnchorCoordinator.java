package network.azusake.halo.anchor;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import network.azusake.halo.api.v2.*;
import network.azusake.halo.core.runtime.PreviewAnchorHost;
import network.azusake.halo.core.runtime.PreviewAnchorScope;

/** Internal neutral coordinator. No world cache, game objects, resource loading or render callbacks. */
public final class PreviewAnchorCoordinator {
    private static final ThreadLocal<Deque<Scope>> SCOPES = new ThreadLocal<>();
    private static final AtomicLong IDS = new AtomicLong();
    private PreviewAnchorCoordinator() {}

    private static Scope top() {
        Deque<Scope> scopes = SCOPES.get();
        return scopes == null ? null : scopes.peek();
    }
    public static boolean isPreviewRendering() { return top() != null; }
    public static PreviewAnchorContext currentContext() {
        Scope scope = top();
        return scope != null && scope.context.isActive() ? scope.context : null;
    }
    public static PreviewAnchorScope open(PreviewAnchorHost host, UUID wearer, int runtimeId,
                                           UUID renderedEntity, int renderedRuntimeId, float[] matrix) {
        Scope scope = new Scope(host, wearer, runtimeId, renderedEntity, renderedRuntimeId, matrix);
        Deque<Scope> scopes = SCOPES.get();
        if (scopes == null) { scopes = new ArrayDeque<>(); SCOPES.set(scopes); }
        scopes.push(scope);
        return scope;
    }
    public static void beginEntityRender(UUID uuid, int runtimeId) {
        Scope scope = top();
        if (scope != null) scope.entities.push(new Entity(Objects.requireNonNull(uuid), runtimeId));
    }
    public static void endEntityRender() {
        Scope scope = top();
        if (scope != null && scope.entities.poll() == null) throw new IllegalStateException("Unbalanced preview entity scope");
    }
    public static void clear(PreviewAnchorHost host) {
        Deque<Scope> scopes = SCOPES.get();
        if (scopes == null) return;
        // Keep lexical nesting until finally/close unwinds it. Removing a live scope here could
        // send the remainder of its entity render into an outer preview or world scope.
        for (Scope scope : scopes) if (scope.host == host) scope.invalidate();
    }
    private record Entity(UUID uuid, int runtimeId) {}
    /** Owned by the shared API v2 source handle; no separate preview registration. */
    static final class Source {
        private volatile boolean closed;
        boolean submit(PreviewAnchorContext context, PreviewAnchorPose pose) {
            if (closed || pose == null || !(context instanceof Context current) || !current.isActive()) return false;
            Scope scope = current.scope;
            if (current.hasModelAnchor()) return false;
            scope.source = this;
            scope.model = pose;
            return true;
        }
        void close() {
            closed = true;
        }
    }
    private static final class Context implements PreviewAnchorContext {
        private final Scope scope;
        private final long id = IDS.incrementAndGet();
        private final UUID wearer;
        private final int runtimeId;
        private final Entity rendered;
        private final float[] matrix;
        Context(Scope scope, UUID wearer, int runtimeId, UUID renderedEntity, int renderedRuntimeId, float[] matrix) {
            this.scope = scope;
            this.wearer = Objects.requireNonNull(wearer);
            this.runtimeId = runtimeId;
            this.rendered = new Entity(Objects.requireNonNull(renderedEntity), renderedRuntimeId);
            this.matrix = Objects.requireNonNull(matrix).clone();
            if (matrix.length != 16) throw new IllegalArgumentException("Expected a 4x4 scene-to-view matrix");
            for (float value : matrix) if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite scene-to-view matrix");
        }
        @Override public UUID wearer() { return wearer; }
        @Override public int runtimeId() { return runtimeId; }
        @Override public UUID renderedEntity() { return rendered.uuid; }
        @Override public int renderedRuntimeId() { return rendered.runtimeId; }
        @Override public long renderId() { return id; }
        @Override public float[] sceneToView() { return matrix.clone(); }
        @Override public boolean isActive() {
            if (scope.closed || scope.invalidated || scope.owner != Thread.currentThread() || top() != scope) return false;
            Entity entity = scope.entities.peek();
            return entity == null || entity.equals(rendered);
        }
        @Override public boolean hasModelAnchor() {
            return isActive() && scope.source != null && !scope.source.closed;
        }
    }
    private static final class Scope implements PreviewAnchorScope {
        private final PreviewAnchorHost host;
        private final Thread owner = Thread.currentThread();
        private final Context context;
        private final Deque<Entity> entities = new ArrayDeque<>();
        private Source source;
        private PreviewAnchorPose model, rendered, posed;
        private boolean closed, invalidated;
        Scope(PreviewAnchorHost host, UUID wearer, int runtimeId, UUID renderedEntity, int renderedRuntimeId, float[] matrix) {
            this.host = Objects.requireNonNull(host);
            context = new Context(this, wearer, runtimeId, renderedEntity, renderedRuntimeId, matrix);
        }
        @Override public PreviewAnchorContext context() { return context; }
        @Override public boolean hasFallback(Fallback kind) {
            Objects.requireNonNull(kind);
            return context.isActive() && (kind == Fallback.RENDERED ? rendered != null : posed != null);
        }
        @Override public boolean submitFallback(PreviewAnchorPose pose, Fallback kind) {
            Objects.requireNonNull(kind);
            if (!context.isActive() || pose == null) return false;
            if (kind == Fallback.RENDERED) {
                if (rendered != null) return false;
                rendered = pose;
            } else {
                if (posed != null) return false;
                posed = pose;
            }
            return true;
        }
        @Override public PreviewAnchorPose resolved() {
            if (!context.isActive()) return null;
            return context.hasModelAnchor() ? model : rendered != null ? rendered : posed;
        }
        private void invalidate() {
            invalidated = true;
            source = null;
            model = rendered = posed = null;
        }
        @Override public void close() {
            if (owner != Thread.currentThread()) throw new IllegalStateException("Preview scope closed on another thread");
            if (closed) return;
            if (top() != this) throw new IllegalStateException("Preview scopes must close in reverse order");
            Deque<Scope> scopes = SCOPES.get();
            scopes.pop();
            invalidate();
            closed = true;
            entities.clear();
            if (scopes.isEmpty()) SCOPES.remove();
        }
    }
}
