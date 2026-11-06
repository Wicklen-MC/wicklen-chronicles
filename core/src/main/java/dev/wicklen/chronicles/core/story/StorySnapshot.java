package dev.wicklen.chronicles.core.story;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record StorySnapshot(UUID worldId, UUID ownerId, long revision, StoryPlan pending, List<StoryOutcome> history) {
    public StorySnapshot {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(ownerId, "ownerId");
        if (revision < 0) throw new IllegalArgumentException("Negative story revision");
        history = List.copyOf(history);
        var plans = new HashSet<UUID>();
        var quests = new HashSet<UUID>();
        var rewards = new HashSet<UUID>();
        if (pending != null) {
            if (!pending.worldId().equals(worldId) || !pending.ownerId().equals(ownerId)) {
                throw new IllegalArgumentException("Pending plan context does not match story owner/world");
            }
            plans.add(pending.id());
        }
        for (var outcome : history) {
            var quest = outcome.quest();
            var expectedReward = dev.wicklen.chronicles.core.quest.QuestTransitions.issue(
                    quest.id(), ownerId, quest.definition(), quest.issuedAt()).rewardId();
            if (!plans.add(outcome.planId()) || !outcome.quest().ownerId().equals(ownerId)
                    || !quests.add(outcome.quest().id()) || !rewards.add(outcome.quest().rewardId())
                    || !quest.rewardId().equals(expectedReward)
                    || !outcome.quest().id().equals(StoryTransitions.questId(worldId, ownerId,
                        outcome.planId(), outcome.choiceId()))) {
                throw new IllegalArgumentException("Invalid story history identity");
            }
        }
    }
}
