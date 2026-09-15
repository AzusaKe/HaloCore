package network.azusake.halo.core.runtime;

import java.util.*;
import network.azusake.halo.config.HaloConfig;
import network.azusake.halo.core.Identifier;
import network.azusake.halo.core.render.*;

/** Frozen ClientPort signature from core f8c35bf (2.1.2). Do not extend for newer features. */
public interface ClientPort {
    void definitions(DefinitionSnapshot snapshot);
    void setConfig(HaloConfig config);
    void attach(UUID entity, Identifier definition, boolean startup);
    void hide(UUID entity, Identifier definition);
    void replace(Map<UUID, Identifier> fullSnapshot);
    void unload(UUID entity);
    void died(UUID entity, boolean player);
    void teleport(UUID entity);
    void clear();
    Map<UUID, Identifier> assignments();
    List<DrawBatch> render(FrameScene frame);
    default FrameOutput renderFrame(FrameScene frame) {
        return new FrameOutput(frame.visuals().generation(), render(frame), List.of());
    }
    Map<UUID, BodyPose> bodyPoses();
}
