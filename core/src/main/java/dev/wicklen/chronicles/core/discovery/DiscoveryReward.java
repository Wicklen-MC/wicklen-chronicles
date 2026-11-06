package dev.wicklen.chronicles.core.discovery;

import dev.wicklen.chronicles.core.quest.RewardDefinition;

import java.util.Objects;
import java.util.UUID;

/** Stable entitlement and platform-delivery acknowledgement, not an inventory operation. */
public record DiscoveryReward(UUID id, RewardDefinition definition, boolean claimed) {
    public DiscoveryReward {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(definition, "definition");
    }
}
