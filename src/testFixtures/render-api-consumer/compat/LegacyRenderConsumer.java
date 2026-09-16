package compat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import network.azusake.halo.api.v2.*;
import network.azusake.halo.core.Vec3d;
import network.azusake.halo.core.render.*;

/** Compiled against the frozen 2.3.0 record definitions, run against the new core only. */
public final class LegacyRenderConsumer {
    public static void main(String[] args) {
        float[] root={1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};
        var camera=new FrameScene.CameraSample(new Vec3d(0,0,0),new Vec3d(0,1,0),new Vec3d(1,0,0));
        var frame=new FrameScene(1,1,1,camera,Map.of(),root,p->1,t->true,VisualResources.EMPTY,p->LightSample.FULL_BRIGHT);
        new FrameScene(1,1,1,camera,Map.of(),root,p->1,t->true,VisualResources.EMPTY);
        new FrameScene(1,1,1,camera,Map.of(),root,p->1,t->true);
        var head=new AnchorPose(new AnchorVec3(0,0,0),new AnchorRotation(0,0,0,1));
        var preview=new PreviewFrame(new UUID(0,1),1,head,camera,root,1,1,LightSample.FULL_BRIGHT,t->true,VisualResources.EMPTY,PreviewFrame.Projection.PERSPECTIVE);
        new PreviewFrame(new UUID(0,1),1,head,camera,root,1,1,LightSample.FULL_BRIGHT,t->true,VisualResources.EMPTY);
        var output=new FrameOutput(1,List.of(),List.of());
        if(frame.worldToken()!=1 || preview.projection()!=PreviewFrame.Projection.PERSPECTIVE
                || !output.expandedBatches(VisualResources.EMPTY).isEmpty())throw new AssertionError();
        System.out.println("Frozen 2.3.0 frame/preview/output constructors linked against current core.");
    }
}
