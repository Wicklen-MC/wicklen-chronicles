package dev.redzumi.spirit.core.quest;

import java.util.Objects;
import java.util.UUID;

/** Immutable runtime state, safe to snapshot for storage or retain as history. */
public record QuestInstance(UUID id, UUID ownerId, QuestDefinition definition,
                            int progress, long issuedAt, QuestStatus status, UUID rewardId) {
    public QuestInstance {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(rewardId, "rewardId");
        if (issuedAt < 0) throw new IllegalArgumentException("Negative issue time");
        if (progress < 0 || progress > definition.required()) {
            throw new IllegalArgumentException("Progress outside definition bounds");
        }
        if ((status == QuestStatus.ACTIVE) != (progress < definition.required())) {
            throw new IllegalArgumentException("Status does not match progress");
        }
    }

    public boolean readyForTurnIn() {
        return status == QuestStatus.READY;
    }

    public boolean pendingReward() {
        return status == QuestStatus.REWARD_PENDING;
    }
}
