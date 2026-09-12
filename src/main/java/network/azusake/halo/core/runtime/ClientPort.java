package network.azusake.halo.core.runtime;

import java.util.*;
import network.azusake.halo.config.HaloConfig;
import network.azusake.halo.core.Identifier;
import network.azusake.halo.core.render.*;

/** Stable client adapter contract. All calls occur on the owning client's main thread. */
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
    Map<UUID, BodyPose> bodyPoses();
}
