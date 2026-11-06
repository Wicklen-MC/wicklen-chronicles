package dev.wicklen.chronicles.core.story;

import dev.wicklen.chronicles.core.quest.QuestInstance;
import dev.wicklen.chronicles.core.quest.QuestStatus;

import java.util.Objects;
import java.util.UUID;

/** Immutable selected consequence and originally issued quest; actual quest progress lives separately. */
public record StoryOutcome(UUID planId, String choiceId, String consequence, QuestInstance quest, long selectedAt) {
    public StoryOutcome {
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(quest, "quest");
        StoryValues.choiceId(choiceId);
        StoryValues.text(consequence, "selected consequence", 512, true);
        if (selectedAt < 0 || selectedAt != quest.issuedAt() || quest.status() != QuestStatus.ACTIVE || quest.progress() != 0) {
            throw new IllegalArgumentException("Story history must retain the original issued quest snapshot");
        }
    }
}
