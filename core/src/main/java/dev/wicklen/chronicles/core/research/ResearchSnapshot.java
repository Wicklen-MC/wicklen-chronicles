package dev.wicklen.chronicles.core.research;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record ResearchSnapshot(UUID worldId, UUID ownerId, long revision, List<ResearchGrant> grants) {
    public ResearchSnapshot {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(ownerId, "ownerId");
        grants = List.copyOf(grants);
        if (revision < 0) throw new IllegalArgumentException("Negative research revision");
        var series = new HashSet<String>();
        var ids = new HashSet<UUID>();
        for (var grant : grants) {
            if (!series.add(grant.definition().id()) || !ids.add(grant.id()) || !grant.id().equals(
                    ResearchTransitions.rewardId(worldId, ownerId, grant.definition().id()))) {
                throw new IllegalArgumentException("Invalid research entitlement identity");
            }
        }
    }
}
