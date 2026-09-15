package network.azusake.halo.core.runtime;

import network.azusake.halo.core.render.FrameOutput;
import network.azusake.halo.core.render.PreviewFrame;

/** One independent preview view; it never advances the owning client's world simulation. */
public interface PreviewSession extends AutoCloseable {
    /** Consumes the latest owning-client appearance frame. An unavailable wearer produces no draws. */
    FrameOutput render(PreviewFrame frame);
    /** Idempotent. Rendering a closed or world-invalidated session produces no draws. */
    @Override void close();
}
