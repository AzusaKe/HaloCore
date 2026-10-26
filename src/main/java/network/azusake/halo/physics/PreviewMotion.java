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
    private AnchorFrameCalculator.OrientationInputs orientationInputs;
    private long nanos;

    public AnchorFrame calculate(PreviewFrame input, HaloDefinition current, HaloConfig config) {
        return calculate(input,current,config,false);
    }
    /** Replay previews use the owning client's source clock without EMA or discarded source duration. */
    public AnchorFrame calculate(PreviewFrame input, HaloDefinition current, HaloConfig config, boolean replay) {
        var inputs = AnchorFrameCalculator.orientationInputs(current, config);
        if (orientationInputs != null && !orientationInputs.equals(inputs)
                || previous != null && input.frameNanos() < nanos) reset();
        orientationInputs = inputs;
        if (previous == null || input.frameNanos() != nanos) {
            double dt=replay?previous==null?0:Math.max(0,(input.frameNanos()-nanos)/1_000_000_000.0):clock.advance(input.frameNanos());
            if(replay){
                do{
                    double step=Math.min(dt,.05);
                    previous=calculator.calculate(input.wearer(),previous==null,input.head(),input.head(),current,input.camera().position(),step,config);
                    dt-=step;
                }while(dt>1e-9);
            }else previous = calculator.calculate(input.wearer(), previous == null, input.head(), input.head(),
                current, input.camera().position(), dt, config);
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
        orientationInputs = null;
    }
}
