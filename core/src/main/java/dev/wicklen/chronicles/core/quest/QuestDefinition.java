package dev.wicklen.chronicles.core.quest;

import java.util.Objects;

/** The accepted content snapshot. Updating a catalogue cannot mutate this record. */
public record QuestDefinition(String id, int version, QuestType type, String target,
                              int required, RewardDefinition reward) {
    public QuestDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(reward, "reward");
        if (id.isBlank() || target.isBlank()) throw new IllegalArgumentException("Blank quest ID or target");
        if (version < 1) throw new IllegalArgumentException("Definition version must be positive");
        if (required < 1) throw new IllegalArgumentException("Required progress must be positive");
        if (type == QuestType.VISIT && required != 1) {
            throw new IllegalArgumentException("A visit records one discovery");
        }
    }
}
