package dev.wicklen.chronicles.core.discovery;

import dev.wicklen.chronicles.core.quest.RewardDefinition;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Pure rules for facts already verified in the world. No world lookup, persistence or item delivery. */
public final class DiscoveryTransitions {
    private DiscoveryTransitions() {}

    public enum Outcome {
        RECORDED, ALREADY_KNOWN, WRONG_OWNER, REWARD_CLAIMED, ALREADY_CLAIMED, UNKNOWN_REWARD
    }

    public record Change(DiscoverySnapshot snapshot, Outcome outcome, DiscoveryEntry entry) {
        public Change {
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(outcome, "outcome");
            if ((outcome == Outcome.RECORDED || outcome == Outcome.ALREADY_KNOWN
                    || outcome == Outcome.REWARD_CLAIMED || outcome == Outcome.ALREADY_CLAIMED) && entry == null) {
                throw new IllegalArgumentException("Discovery outcome requires its entry");
            }
        }

        public boolean changed() {
            return outcome == Outcome.RECORDED || outcome == Outcome.REWARD_CLAIMED;
        }
    }

    public static DiscoverySnapshot empty(UUID worldId, UUID ownerId) {
        return new DiscoverySnapshot(worldId, ownerId, 0, List.of());
    }

    public static Change observe(DiscoverySnapshot snapshot, UUID actor, Observation observation,
                                 RewardDefinition rewardOrNull) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(observation, "observation");
        if (!snapshot.ownerId().equals(actor)) return new Change(snapshot, Outcome.WRONG_OWNER, null);
        for (var entry : snapshot.entries()) {
            if (entry.key().equals(observation.key())) return new Change(snapshot, Outcome.ALREADY_KNOWN, entry);
        }
        var reward = rewardOrNull == null ? null : new DiscoveryReward(
                rewardId(snapshot.worldId(), snapshot.ownerId(), observation.key()), rewardOrNull, false);
        var entry = new DiscoveryEntry(observation.key(), observation.title(), observation.summary(),
                observation.position(), observation.observedAt(), reward);
        var entries = new ArrayList<>(snapshot.entries());
        entries.add(entry);
        return new Change(new DiscoverySnapshot(snapshot.worldId(), snapshot.ownerId(),
                Math.incrementExact(snapshot.revision()), entries), Outcome.RECORDED, entry);
    }

    /** Call only after the platform successfully delivers the pending entitlement. */
    public static Change acknowledgeReward(DiscoverySnapshot snapshot, UUID actor, UUID expectedRewardId) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (!snapshot.ownerId().equals(actor)) return new Change(snapshot, Outcome.WRONG_OWNER, null);
        var entries = new ArrayList<>(snapshot.entries());
        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.get(i);
            var reward = entry.reward();
            if (reward == null || !reward.id().equals(expectedRewardId)) continue;
            if (reward.claimed()) return new Change(snapshot, Outcome.ALREADY_CLAIMED, entry);
            var updated = new DiscoveryEntry(entry.key(), entry.title(), entry.summary(), entry.position(),
                    entry.firstObservedAt(), new DiscoveryReward(reward.id(), reward.definition(), true));
            entries.set(i, updated);
            return new Change(new DiscoverySnapshot(snapshot.worldId(), snapshot.ownerId(),
                    Math.incrementExact(snapshot.revision()), entries), Outcome.REWARD_CLAIMED, updated);
        }
        return new Change(snapshot, Outcome.UNKNOWN_REWARD, null);
    }

    public static UUID rewardId(UUID worldId, UUID ownerId, DiscoveryKey key) {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(key, "key");
        return UUID.nameUUIDFromBytes(("wicklen:discovery:" + worldId + ":" + ownerId + ":" + key.kind()
                + ":" + key.id()).getBytes(StandardCharsets.UTF_8));
    }
}
