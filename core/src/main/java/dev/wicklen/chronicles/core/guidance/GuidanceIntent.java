package dev.wicklen.chronicles.core.guidance;

import dev.wicklen.chronicles.core.discovery.*;
import java.util.*;

/** Persist only the player's choice; coordinates and names are resolved from existing personal facts. */
public record GuidanceIntent(UUID worldId, UUID ownerId, Mode mode, String placeId, long revision) {
    public enum Mode { FOLLOW_EXPEDITION, PIN_PLACE, DISABLED }
    public GuidanceIntent {
        Objects.requireNonNull(worldId); Objects.requireNonNull(ownerId); Objects.requireNonNull(mode); Objects.requireNonNull(placeId);
        if (revision < 0 || mode != Mode.PIN_PLACE && !placeId.isEmpty()) throw new IllegalArgumentException("Guidance intent bounds");
        if (mode == Mode.PIN_PLACE) new DiscoveryKey(DiscoveryKind.PLACE, placeId);
    }
    public static GuidanceIntent initial(UUID world, UUID owner) { return new GuidanceIntent(world, owner, Mode.FOLLOW_EXPEDITION, "", 0); }
    public boolean enabled() { return mode != Mode.DISABLED; }
}
