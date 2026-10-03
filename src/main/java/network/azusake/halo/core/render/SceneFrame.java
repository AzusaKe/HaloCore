package network.azusake.halo.core.render;

import java.util.List;

/** One completed world simulation sample, before platform visibility and acceleration preparation. */
public record SceneFrame(long worldToken, long visualGeneration, List<SceneDraw> draws) {
    public SceneFrame { draws = List.copyOf(draws); }
}
