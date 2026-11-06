package dev.wicklen.chronicles.core.story;

import dev.wicklen.chronicles.core.quest.QuestDefinition;
import dev.wicklen.chronicles.core.quest.QuestType;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Server-supplied capabilities, never policy supplied by the model or client. */
public record StoryPolicy(Map<QuestType, Set<String>> allowedTargets, Set<String> allowedRewards,
                          int maxRequired, int maxRewardCount) {
    public StoryPolicy {
        Objects.requireNonNull(allowedTargets, "allowedTargets");
        var targets = new EnumMap<QuestType, Set<String>>(QuestType.class);
        allowedTargets.forEach((type, values) -> {
            Objects.requireNonNull(type, "quest type");
            var copy = Set.copyOf(values);
            if (copy.stream().anyMatch(value -> !StoryValues.identifier(value))) {
                throw new IllegalArgumentException("Invalid allowed target ID");
            }
            targets.put(type, copy);
        });
        allowedTargets = Map.copyOf(targets);
        allowedRewards = Set.copyOf(allowedRewards);
        if (allowedRewards.stream().anyMatch(value -> !StoryValues.identifier(value))) {
            throw new IllegalArgumentException("Invalid allowed reward ID");
        }
        if (maxRequired < 1 || maxRewardCount < 1 || maxRewardCount > 64) {
            throw new IllegalArgumentException("Invalid story policy bounds");
        }
    }

    public boolean accepts(QuestDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        return StoryValues.identifier(definition.id())
                && allowedTargets.getOrDefault(definition.type(), Set.of()).contains(definition.target())
                && definition.required() <= maxRequired
                && allowedRewards.contains(definition.reward().itemId())
                && definition.reward().count() <= maxRewardCount;
    }
}
