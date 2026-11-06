package dev.wicklen.chronicles.core.research;

import dev.wicklen.chronicles.core.discovery.DiscoverySnapshot;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Small authored collections, counted from already confirmed personal discoveries. */
public final class ResearchTransitions {
    private ResearchTransitions() {}

    public enum Outcome { GRANTED, NOT_READY, ALREADY_GRANTED, WRONG_OWNER, WRONG_WORLD, CLAIMED, ALREADY_CLAIMED, UNKNOWN_REWARD }

    public record Change(ResearchSnapshot snapshot, Outcome outcome, ResearchGrant grant) {
        public Change {
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(outcome, "outcome");
            if ((outcome == Outcome.GRANTED || outcome == Outcome.ALREADY_GRANTED || outcome == Outcome.CLAIMED
                    || outcome == Outcome.ALREADY_CLAIMED) && grant == null) throw new IllegalArgumentException("Missing research grant");
        }
        public boolean changed() { return outcome == Outcome.GRANTED || outcome == Outcome.CLAIMED; }
    }

    public static ResearchSnapshot empty(UUID worldId, UUID ownerId) {
        return new ResearchSnapshot(worldId, ownerId, 0, List.of());
    }

    public static Change evaluate(ResearchSnapshot snapshot, UUID actor, DiscoverySnapshot discoveries, ResearchDefinition definition) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(discoveries, "discoveries");
        Objects.requireNonNull(definition, "definition");
        if (!snapshot.ownerId().equals(actor) || !discoveries.ownerId().equals(actor)) return new Change(snapshot, Outcome.WRONG_OWNER, null);
        if (!snapshot.worldId().equals(discoveries.worldId())) return new Change(snapshot, Outcome.WRONG_WORLD, null);
        var progress = progress(snapshot, discoveries, definition);
        if (progress.grant() != null) return new Change(snapshot, Outcome.ALREADY_GRANTED, progress.grant());
        if (!progress.ready()) return new Change(snapshot, Outcome.NOT_READY, null);
        var grant = new ResearchGrant(rewardId(snapshot.worldId(), snapshot.ownerId(), definition.id()), definition, false);
        var grants = new ArrayList<>(snapshot.grants());
        grants.add(grant);
        return new Change(new ResearchSnapshot(snapshot.worldId(), snapshot.ownerId(), Math.incrementExact(snapshot.revision()), grants),
                Outcome.GRANTED, grant);
    }

    public static Change acknowledgeReward(ResearchSnapshot snapshot, UUID actor, UUID rewardId) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (!snapshot.ownerId().equals(actor)) return new Change(snapshot, Outcome.WRONG_OWNER, null);
        var grants = new ArrayList<>(snapshot.grants());
        for (int i = 0; i < grants.size(); i++) {
            var grant = grants.get(i);
            if (!grant.id().equals(rewardId)) continue;
            if (grant.claimed()) return new Change(snapshot, Outcome.ALREADY_CLAIMED, grant);
            var claimed = new ResearchGrant(grant.id(), grant.definition(), true);
            grants.set(i, claimed);
            return new Change(new ResearchSnapshot(snapshot.worldId(), snapshot.ownerId(), Math.incrementExact(snapshot.revision()), grants),
                    Outcome.CLAIMED, claimed);
        }
        return new Change(snapshot, Outcome.UNKNOWN_REWARD, null);
    }

    public static ResearchProgress progress(ResearchSnapshot snapshot, DiscoverySnapshot discoveries, ResearchDefinition definition) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(discoveries, "discoveries");
        Objects.requireNonNull(definition, "definition");
        if (!snapshot.ownerId().equals(discoveries.ownerId()) || !snapshot.worldId().equals(discoveries.worldId())) {
            throw new IllegalArgumentException("Research progress belongs to another player or world");
        }
        var grant = snapshot.grants().stream().filter(value -> value.definition().id().equals(definition.id())).findFirst().orElse(null);
        var accepted = grant == null ? definition : grant.definition();
        int count = (int) Math.min(accepted.requiredDistinct(), discoveries.entries().stream()
                .filter(entry -> entry.key().kind() == accepted.kind()).map(entry -> entry.key()).distinct().count());
        return new ResearchProgress(accepted, count, grant);
    }

    public static UUID rewardId(UUID worldId, UUID ownerId, String seriesId) {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(seriesId, "seriesId");
        return UUID.nameUUIDFromBytes(("wicklen:research:" + worldId + ":" + ownerId + ":" + seriesId).getBytes(StandardCharsets.UTF_8));
    }
}
