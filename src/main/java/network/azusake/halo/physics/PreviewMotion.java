package network.azusake.halo.physics;

import java.util.Set;
import network.azusake.halo.config.HaloConfig;
import network.azusake.halo.core.render.PreviewFrame;
import network.azusake.halo.data.HaloDefinition;

/** One preview's physics. Geometry and shared appearance are deliberately outside this state. */
public final class PreviewMotion {
    private final AnchorFrameCalculator calculator = new AnchorFrameCalculator();
    private final FrameDelta clock = new FrameDelta();
    private AnchorFrame previous;
    private HaloDefinition definition;
    private long nanos;

    public AnchorFrame calculate(PreviewFrame input, HaloDefinition current, HaloConfig config) {
        if (definition != current || previous != null && input.frameNanos() < nanos) reset();
        definition = current;
        if (previous == null || input.frameNanos() != nanos) {
            previous = calculator.calculate(input.wearer(), previous == null, input.head(), input.head(),
                current, input.camera().position(), clock.advance(input.frameNanos()), config);
            nanos = input.frameNanos();
        }
        // Multiple submissions of a single view sample must not advance damping twice.
        // Camera/GUI changes only transform the already computed scene-space body.
        return new AnchorFrame(previous.worldPosition(), previous.worldPosition().subtract(input.camera().position()),
            previous.worldOrientation(), previous.worldForward(), previous.toHeadDirection(), previous.scale());
    }
    public void reset() {
        calculator.retainOnly(Set.of());
        clock.reset();
        previous = null;
        definition = null;
    }
}
