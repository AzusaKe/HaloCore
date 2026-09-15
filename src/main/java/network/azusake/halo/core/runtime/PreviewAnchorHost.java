package network.azusake.halo.core.runtime;

import java.util.UUID;
import network.azusake.halo.anchor.PreviewAnchorCoordinator;

/**
 * Platform-neutral host bridge, one per logical client. It owns capture lifetime, not physics.
 * Lazily binds to the thread of its first open; clear on disconnect/world replacement/disposal.
 */
public final class PreviewAnchorHost {
    private Thread owner;
    private synchronized void checkThread() {
        if (owner == null) owner = Thread.currentThread();
        else if (owner != Thread.currentThread()) throw new IllegalStateException("Preview host used on another thread");
    }
    public PreviewAnchorScope open(UUID wearer, int runtimeId, float[] sceneToView) {
        return open(wearer, runtimeId, wearer, runtimeId, sceneToView);
    }
    /** A UI proxy may have a different identity; appearance still belongs to the real wearer. */
    public PreviewAnchorScope open(UUID wearer, int runtimeId, UUID renderedEntity, int renderedRuntimeId, float[] sceneToView) {
        checkThread();
        return PreviewAnchorCoordinator.open(this, wearer, runtimeId, renderedEntity, renderedRuntimeId, sceneToView);
    }
    /** Invalidate captures immediately; callers still close lexical scopes to unwind render nesting. */
    public void clear() {
        checkThread();
        PreviewAnchorCoordinator.clear(this);
    }
    /** Bracket the platform entity dispatcher once; unrelated entity passes reject provider submissions. */
    public static void beginEntityRender(UUID uuid, int runtimeId) {
        PreviewAnchorCoordinator.beginEntityRender(uuid, runtimeId);
    }
    public static void endEntityRender() { PreviewAnchorCoordinator.endEntityRender(); }
}
