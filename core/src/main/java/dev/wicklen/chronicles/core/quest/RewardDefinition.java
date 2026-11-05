package dev.wicklen.chronicles.core.quest;

import java.util.Objects;

/** A content reference, never a native ItemStack or a command to execute. */
public record RewardDefinition(String itemId, int count) {
    public RewardDefinition {
        Objects.requireNonNull(itemId, "itemId");
        if (itemId.isBlank()) throw new IllegalArgumentException("Blank reward item ID");
        if (count < 1) throw new IllegalArgumentException("Reward count must be positive");
    }
}
