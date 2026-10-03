package network.azusake.halo.core.runtime;

import network.azusake.halo.core.render.FrameScene;
import network.azusake.halo.core.render.SceneFrame;

/** Optional retained-world backend. Call this OR renderFrame once per logical frame, never both. */
public interface ScenePort {
    /** Uses absolute world coordinates; the raster root transform is intentionally excluded. */
    SceneFrame renderScene(FrameScene scene);
}
