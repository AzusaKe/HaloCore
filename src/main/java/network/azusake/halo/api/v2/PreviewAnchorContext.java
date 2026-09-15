package network.azusake.halo.api.v2;

import java.util.UUID;

/**
 * Opaque capability for one host render invocation, obtained from HaloAnchorApi.currentPreviewContext().
 * Do not implement this interface or retain it for later frames. A nested view temporarily suspends it.
 */
public interface PreviewAnchorContext {
    UUID wearer();
    int runtimeId();
    /** Actual model-render entity, which may be a UI proxy for the real wearer. */
    UUID renderedEntity();
    int renderedRuntimeId();
    /** Process-local render identity, distinct even for repeated views of the same wearer. */
    long renderId();
    /** Defensive copy of the column-major 4x4 scene-to-view matrix; excludes projection. */
    float[] sceneToView();
    /** True only on the owning thread while this is the current eligible entity/view scope. */
    boolean isActive();
    /** A live provider already supplied the model anchor; vanilla fallbacks do not count. */
    boolean hasModelAnchor();
}
