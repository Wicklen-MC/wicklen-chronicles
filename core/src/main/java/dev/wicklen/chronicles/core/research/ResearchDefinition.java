package dev.wicklen.chronicles.core.research;

import dev.wicklen.chronicles.core.discovery.DiscoveryKind;
import dev.wicklen.chronicles.core.quest.RewardDefinition;

import java.util.Objects;

public record ResearchDefinition(String id, int version, String title, DiscoveryKind kind,
                                  int requiredDistinct, RewardDefinition reward) {
    public ResearchDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(reward, "reward");
        if (id.length() > 192 || !id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || version < 1
                || title.isBlank() || title.length() > 160 || title.chars().anyMatch(Character::isISOControl)
                || (kind != DiscoveryKind.CREATURE && kind != DiscoveryKind.PLACE) || requiredDistinct < 1) {
            throw new IllegalArgumentException("Invalid research series definition");
        }
    }
}
