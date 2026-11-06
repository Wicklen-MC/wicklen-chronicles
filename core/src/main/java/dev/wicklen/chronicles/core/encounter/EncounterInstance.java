package dev.wicklen.chronicles.core.encounter;

import dev.wicklen.chronicles.core.discovery.DiscoveryKey;
import dev.wicklen.chronicles.core.discovery.DiscoveryKind;
import dev.wicklen.chronicles.core.quest.RewardDefinition;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Frozen party and reward snapshot. A time of -1 means that transition has not occurred. */
public record EncounterInstance(UUID id, UUID worldId, DiscoveryKey place, EncounterMode mode,
                                List<UUID> participants, EncounterStatus status, RewardDefinition reward,
                                List<EncounterReward> rewards, long preparedAt, long startedAt, long finishedAt) {
    public static final int MAX_PARTICIPANTS = 64;

    public EncounterInstance {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(place, "place");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(reward, "reward");
        participants = List.copyOf(participants);
        rewards = List.copyOf(rewards);
        var party = new HashSet<>(participants);
        if (place.kind() != DiscoveryKind.PLACE || participants.isEmpty() || participants.size() > MAX_PARTICIPANTS
                || party.size() != participants.size()) {
            throw new IllegalArgumentException("Invalid encounter place or frozen party");
        }
        if (preparedAt < 0) throw new IllegalArgumentException("Negative preparation time");
        switch (status) {
            case PREPARED -> {
                if (startedAt != -1 || finishedAt != -1) throw new IllegalArgumentException("Prepared encounter has execution timestamps");
            }
            case ACTIVE -> {
                if (startedAt < preparedAt || finishedAt != -1) throw new IllegalArgumentException("Invalid active timestamps");
            }
            case SUCCESS, FAILURE -> {
                if (startedAt < preparedAt || finishedAt < startedAt) throw new IllegalArgumentException("Invalid completion timestamps");
            }
            case ABORTED -> {
                if ((startedAt != -1 && startedAt < preparedAt) || finishedAt < Math.max(preparedAt, startedAt)) {
                    throw new IllegalArgumentException("Invalid abort timestamps");
                }
            }
        }
        if (status == EncounterStatus.SUCCESS) {
            var owners = new HashSet<UUID>();
            var grantIds = new HashSet<UUID>();
            for (var grant : rewards) {
                if (!party.contains(grant.ownerId()) || !owners.add(grant.ownerId()) || !grantIds.add(grant.id())
                        || !grant.definition().equals(reward) || !grant.id().equals(
                            EncounterTransitions.rewardId(id, worldId, place, grant.ownerId()))) {
                    throw new IllegalArgumentException("Invalid encounter entitlement");
                }
            }
            if (!owners.equals(party)) throw new IllegalArgumentException("Successful encounter needs one grant per participant");
        } else if (!rewards.isEmpty()) {
            throw new IllegalArgumentException("Unsuccessful encounter cannot grant rewards");
        }
    }

    public boolean terminal() {
        return status == EncounterStatus.SUCCESS || status == EncounterStatus.FAILURE || status == EncounterStatus.ABORTED;
    }
}
