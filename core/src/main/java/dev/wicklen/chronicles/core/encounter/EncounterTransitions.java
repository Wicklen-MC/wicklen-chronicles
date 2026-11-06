package dev.wicklen.chronicles.core.encounter;

import dev.wicklen.chronicles.core.discovery.DiscoveryKey;
import dev.wicklen.chronicles.core.quest.RewardDefinition;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Executor-independent encounter lifecycle; native callbacks and delivery remain platform concerns. */
public final class EncounterTransitions {
    private EncounterTransitions() {}

    public enum Outcome {
        STARTED, FINISHED, REWARD_CLAIMED, WRONG_PARTICIPANT, STALE_ID,
        NOT_PREPARED, NOT_ACTIVE, ALREADY_FINISHED, UNKNOWN_REWARD, ALREADY_CLAIMED, NO_REWARDS
    }

    public record Change(EncounterInstance instance, Outcome outcome) {
        public Change {
            Objects.requireNonNull(instance, "instance");
            Objects.requireNonNull(outcome, "outcome");
        }

        public boolean changed() {
            return outcome == Outcome.STARTED || outcome == Outcome.FINISHED || outcome == Outcome.REWARD_CLAIMED;
        }
    }

    public static EncounterInstance prepare(UUID id, UUID worldId, DiscoveryKey place, EncounterMode mode,
                                             List<UUID> participants, RewardDefinition reward, long now) {
        return new EncounterInstance(id, worldId, place, mode, participants, EncounterStatus.PREPARED,
                reward, List.of(), now, -1, -1);
    }

    public static Change start(EncounterInstance instance, UUID actor, UUID expectedId, long now) {
        Objects.requireNonNull(instance, "instance");
        if (!instance.id().equals(expectedId)) return new Change(instance, Outcome.STALE_ID);
        if (actor == null || !instance.participants().contains(actor)) return new Change(instance, Outcome.WRONG_PARTICIPANT);
        if (instance.terminal()) return new Change(instance, Outcome.ALREADY_FINISHED);
        if (instance.status() != EncounterStatus.PREPARED) return new Change(instance, Outcome.NOT_PREPARED);
        return new Change(new EncounterInstance(instance.id(), instance.worldId(), instance.place(), instance.mode(),
                instance.participants(), EncounterStatus.ACTIVE, instance.reward(), List.of(),
                instance.preparedAt(), now, -1), Outcome.STARTED);
    }

    /** Only the server's verified executor lifecycle calls this, never an untrusted client result. */
    public static Change finish(EncounterInstance instance, UUID expectedId, EncounterStatus terminalStatus, long now) {
        Objects.requireNonNull(instance, "instance");
        Objects.requireNonNull(terminalStatus, "terminalStatus");
        if (terminalStatus != EncounterStatus.SUCCESS && terminalStatus != EncounterStatus.FAILURE
                && terminalStatus != EncounterStatus.ABORTED) throw new IllegalArgumentException("Expected terminal encounter outcome");
        if (!instance.id().equals(expectedId)) return new Change(instance, Outcome.STALE_ID);
        if (instance.terminal()) return new Change(instance, Outcome.ALREADY_FINISHED);
        if (instance.status() != EncounterStatus.ACTIVE && terminalStatus != EncounterStatus.ABORTED) {
            return new Change(instance, Outcome.NOT_ACTIVE);
        }
        List<EncounterReward> rewards = terminalStatus == EncounterStatus.SUCCESS
                ? instance.participants().stream().map(owner -> new EncounterReward(
                    rewardId(instance.id(), instance.worldId(), instance.place(), owner), owner, instance.reward(), false)).toList()
                : List.of();
        return new Change(new EncounterInstance(instance.id(), instance.worldId(), instance.place(), instance.mode(),
                instance.participants(), terminalStatus, instance.reward(), rewards,
                instance.preparedAt(), instance.startedAt(), now), Outcome.FINISHED);
    }

    /** Acknowledges successful native delivery for that owner; it never inserts inventory items. */
    public static Change acknowledgeReward(EncounterInstance instance, UUID actor, UUID expectedRewardId) {
        Objects.requireNonNull(instance, "instance");
        if (actor == null || !instance.participants().contains(actor)) return new Change(instance, Outcome.WRONG_PARTICIPANT);
        if (instance.status() != EncounterStatus.SUCCESS) return new Change(instance, Outcome.NO_REWARDS);
        var rewards = new ArrayList<>(instance.rewards());
        for (int i = 0; i < rewards.size(); i++) {
            var grant = rewards.get(i);
            if (!grant.id().equals(expectedRewardId) || !grant.ownerId().equals(actor)) continue;
            if (grant.claimed()) return new Change(instance, Outcome.ALREADY_CLAIMED);
            rewards.set(i, new EncounterReward(grant.id(), grant.ownerId(), grant.definition(), true));
            return new Change(new EncounterInstance(instance.id(), instance.worldId(), instance.place(), instance.mode(),
                    instance.participants(), instance.status(), instance.reward(), rewards, instance.preparedAt(),
                    instance.startedAt(), instance.finishedAt()), Outcome.REWARD_CLAIMED);
        }
        return new Change(instance, Outcome.UNKNOWN_REWARD);
    }

    public static UUID rewardId(UUID encounterId, UUID worldId, DiscoveryKey place, UUID owner) {
        Objects.requireNonNull(encounterId, "encounterId");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(place, "place");
        Objects.requireNonNull(owner, "owner");
        return UUID.nameUUIDFromBytes(("wicklen:encounter-reward:" + worldId + ":" + encounterId + ":" + place.kind()
                + ":" + place.id() + ":" + owner).getBytes(StandardCharsets.UTF_8));
    }
}
