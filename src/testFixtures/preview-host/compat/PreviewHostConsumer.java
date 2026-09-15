package compat;

import external.PreviewProvider;
import java.util.UUID;
import network.azusake.halo.api.v2.HaloAnchorApi;
import network.azusake.halo.api.v2.PreviewAnchorPose;
import network.azusake.halo.api.v2.AnchorRotation;
import network.azusake.halo.core.runtime.PreviewAnchorHost;

/** Runs with only the two consumer outputs, the built core jar and the JDK. */
public final class PreviewHostConsumer {
    public static void main(String[] args) {
        var host=new PreviewAnchorHost(); var wearer=new UUID(0,1); var proxy=new UUID(0,2);
        var pose=new PreviewAnchorPose(.1,1.8,.3,new AnchorRotation(0,0,0,1));
        float[] root={30,0,0,0, 0,-30,0,0, 0,0,30,0, 100,200,300,1};
        try(var provider=new PreviewProvider()) {
            if(provider.capture(pose)) throw new AssertionError("Accepted outside preview");
            for(int renderer=0;renderer<2;renderer++) {
                root[0]=renderer==0?30:60;
                try(var outer=host.open(wearer,1,proxy,9,root)) {
                    var context=provider.current();
                    if(!context.wearer().equals(wearer) || context.renderedRuntimeId()!=9) throw new AssertionError("Proxy mapping");
                    PreviewAnchorHost.beginEntityRender(proxy,9);
                    if(!provider.capture(pose) || !pose.equals(outer.resolved())) throw new AssertionError("Capture");
                    try(var nested=host.open(wearer,1,root)) {
                        if(provider.submitRetained(context,pose)) throw new AssertionError("Suspended scope");
                        if(!provider.capture(pose)) throw new AssertionError("Nested capture");
                    }
                    PreviewAnchorHost.endEntityRender();
                    host.clear();
                    if(provider.submitRetained(context,pose)) throw new AssertionError("Expired scope");
                }
            }
        }
        if(HaloAnchorApi.isPreviewRendering()) throw new AssertionError("Leaked scope");
        System.out.println("External preview provider compiled independently and ran against the core jar + JDK only.");
    }
}
