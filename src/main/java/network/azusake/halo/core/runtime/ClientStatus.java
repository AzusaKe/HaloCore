package network.azusake.halo.core.runtime;

import network.azusake.halo.core.Identifier;
import network.azusake.halo.data.HaloTransitionState;

/** Immutable diagnostic observation, never used as server authority. */
public record ClientStatus(Identifier definition, long createdAt, boolean active,
                           boolean needsSnap, HaloTransitionState transition) {}
