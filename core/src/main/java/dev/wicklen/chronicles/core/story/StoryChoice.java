package dev.wicklen.chronicles.core.story;

import dev.wicklen.chronicles.core.quest.QuestDefinition;

import java.util.Objects;

public record StoryChoice(String id, String label, String consequencePreview, QuestDefinition quest) {
    public StoryChoice {
        StoryValues.choiceId(id);
        StoryValues.text(label, "choice label", 120, false);
        StoryValues.text(consequencePreview, "consequence preview", 512, true);
        Objects.requireNonNull(quest, "quest");
    }
}
