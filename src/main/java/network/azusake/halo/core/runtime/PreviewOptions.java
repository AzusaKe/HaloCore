package network.azusake.halo.core.runtime;

/** Per-view motion choice. Parameters come from the same definition/runtime config as the world. */
public record PreviewOptions(boolean physicsEnabled) {
    public static final PreviewOptions RIGID = new PreviewOptions(false);
    public static final PreviewOptions PHYSICS = new PreviewOptions(true);
}
