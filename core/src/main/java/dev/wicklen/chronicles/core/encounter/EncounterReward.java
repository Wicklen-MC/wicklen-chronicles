package dev.wicklen.chronicles.core.encounter;

import dev.wicklen.chronicles.core.quest.RewardDefinition;

import java.util.Objects;
import java.util.UUID;

/** The mod owns each participant's entitlement independently of an encounter executor's loot. */
public record EncounterReward(UUID id, UUID ownerId, RewardDefinition definition, boolean claimed) {
    public EncounterReward {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(definition, "definition");
    }
}
