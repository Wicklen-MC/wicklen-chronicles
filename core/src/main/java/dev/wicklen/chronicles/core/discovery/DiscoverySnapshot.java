package dev.wicklen.chronicles.core.discovery;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable world/player-owned persistence boundary; accepted by record-aware serializers. */
public record DiscoverySnapshot(UUID worldId, UUID ownerId, long revision, List<DiscoveryEntry> entries) {
    public DiscoverySnapshot {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(ownerId, "ownerId");
        entries = List.copyOf(entries);
        if (revision < 0) throw new IllegalArgumentException("Negative discovery revision");
        var keys = new HashSet<DiscoveryKey>();
        var rewards = new HashSet<UUID>();
        for (var entry : entries) {
            if (!keys.add(entry.key())) throw new IllegalArgumentException("Duplicate discovery key");
            if (entry.reward() != null) {
                if (!rewards.add(entry.reward().id()) || !entry.reward().id().equals(
                        DiscoveryTransitions.rewardId(worldId, ownerId, entry.key()))) {
                    throw new IllegalArgumentException("Invalid discovery reward identity");
                }
            }
        }
    }
}
