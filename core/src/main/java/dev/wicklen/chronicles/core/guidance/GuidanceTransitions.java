package dev.wicklen.chronicles.core.guidance;

import dev.wicklen.chronicles.core.discovery.*;
import java.util.*;

/** Read-only projections never call these explicit, owner/world-bound intention changes. */
public final class GuidanceTransitions {
    public static GuidanceIntent follow(GuidanceIntent old, UUID actor, UUID expectedWorld) {
        requireOwner(old, actor, expectedWorld); return change(old, GuidanceIntent.Mode.FOLLOW_EXPEDITION, "");
    }
    public static GuidanceIntent clear(GuidanceIntent old, UUID actor, UUID expectedWorld) {
        requireOwner(old, actor, expectedWorld); return change(old, GuidanceIntent.Mode.DISABLED, "");
    }
    public static GuidanceIntent pin(GuidanceIntent old, UUID actor, UUID expectedWorld, DiscoverySnapshot discoveries,
                                     String dimension, String placeId) {
        requireOwner(old, actor, expectedWorld);
        if (!discoveries.worldId().equals(old.worldId()) || !discoveries.ownerId().equals(actor))
            throw new IllegalArgumentException("Foreign discovery projection");
        var key = new DiscoveryKey(DiscoveryKind.PLACE, placeId);
        if (discoveries.entries().stream().noneMatch(entry -> entry.key().equals(key) && entry.position().dimension().equals(dimension)))
            throw new IllegalArgumentException("Only a personally confirmed place in the current dimension can be pinned");
        return change(old, GuidanceIntent.Mode.PIN_PLACE, placeId);
    }
    private static GuidanceIntent change(GuidanceIntent old, GuidanceIntent.Mode mode, String placeId) {
        if (old.mode() == mode && old.placeId().equals(placeId)) return old;
        return new GuidanceIntent(old.worldId(), old.ownerId(), mode, placeId, Math.incrementExact(old.revision()));
    }
    private static void requireOwner(GuidanceIntent old, UUID actor, UUID expectedWorld) {
        if (!old.ownerId().equals(actor) || !old.worldId().equals(expectedWorld)) throw new IllegalArgumentException("Guidance world/owner mismatch");
    }
    private GuidanceTransitions() {}
}
