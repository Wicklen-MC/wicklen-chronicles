package dev.wicklen.chronicles.core.story;

import dev.wicklen.chronicles.core.discovery.DiscoverySnapshot;
import dev.wicklen.chronicles.core.quest.QuestInstance;
import dev.wicklen.chronicles.core.quest.QuestTransitions;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Accepts typed proposals and choices; neither calls an LLM nor performs native effects. */
public final class StoryTransitions {
    private StoryTransitions() {}

    public enum Outcome {
        ACCEPTED, CHOSEN, WRONG_OWNER, WRONG_WORLD, STALE_DISCOVERY_REVISION,
        UNKNOWN_DISCOVERY, POLICY_REJECTED, PLAN_PENDING, STALE_PLAN, UNKNOWN_CHOICE,
        QUEST_SLOT_OCCUPIED, ALREADY_CHOSEN
    }

    public record Change(StorySnapshot snapshot, Outcome outcome, QuestInstance quest) {
        public Change {
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(outcome, "outcome");
            if ((outcome == Outcome.CHOSEN) != (quest != null)) {
                throw new IllegalArgumentException("Only an accepted choice issues a quest");
            }
        }

        public boolean changed() {
            return outcome == Outcome.ACCEPTED || outcome == Outcome.CHOSEN;
        }
    }

    public static StorySnapshot empty(UUID worldId, UUID ownerId) {
        return new StorySnapshot(worldId, ownerId, 0, null, List.of());
    }

    public static Change accept(StorySnapshot stories, DiscoverySnapshot discoveries, UUID actor,
                                StoryPlan plan, StoryPolicy policy) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(policy, "policy");
        Outcome context = context(stories, discoveries, actor);
        if (context != null) return rejected(stories, context);
        if (!plan.ownerId().equals(actor)) return rejected(stories, Outcome.WRONG_OWNER);
        if (!plan.worldId().equals(stories.worldId())) return rejected(stories, Outcome.WRONG_WORLD);
        if (alreadyChosen(stories, plan.id())) return rejected(stories, Outcome.ALREADY_CHOSEN);
        if (stories.pending() != null) return rejected(stories, Outcome.PLAN_PENDING);
        if (plan.expectedDiscoveryRevision() != discoveries.revision()) {
            return rejected(stories, Outcome.STALE_DISCOVERY_REVISION);
        }
        if (!known(discoveries, plan.source())) return rejected(stories, Outcome.UNKNOWN_DISCOVERY);
        if (plan.choices().stream().anyMatch(choice -> !policy.accepts(choice.quest()))) {
            return rejected(stories, Outcome.POLICY_REJECTED);
        }
        return new Change(new StorySnapshot(stories.worldId(), stories.ownerId(),
                Math.incrementExact(stories.revision()), plan, stories.history()), Outcome.ACCEPTED, null);
    }

    public static Change choose(StorySnapshot stories, DiscoverySnapshot discoveries, UUID actor,
                                UUID expectedPlanId, String choiceId, StoryPolicy policy,
                                boolean hasActiveQuest, long now) {
        Objects.requireNonNull(policy, "policy");
        if (now < 0) throw new IllegalArgumentException("Negative selection time");
        Outcome context = context(stories, discoveries, actor);
        if (context != null) return rejected(stories, context);
        if (alreadyChosen(stories, expectedPlanId)) return rejected(stories, Outcome.ALREADY_CHOSEN);
        var pending = stories.pending();
        if (pending == null || !pending.id().equals(expectedPlanId)) return rejected(stories, Outcome.STALE_PLAN);
        var choice = pending.choices().stream().filter(option -> option.id().equals(choiceId)).findFirst().orElse(null);
        if (choice == null) return rejected(stories, Outcome.UNKNOWN_CHOICE);
        if (hasActiveQuest) return rejected(stories, Outcome.QUEST_SLOT_OCCUPIED);
        if (!known(discoveries, pending.source())) return rejected(stories, Outcome.UNKNOWN_DISCOVERY);
        // Discovery revision is checked when accepting the asynchronous plan.
        // Unrelated observations later must not invalidate a player's accepted choices.
        if (!policy.accepts(choice.quest())) return rejected(stories, Outcome.POLICY_REJECTED);
        var quest = QuestTransitions.issue(questId(stories.worldId(), stories.ownerId(), pending.id(), choice.id()),
                actor, choice.quest(), now);
        var history = new ArrayList<>(stories.history());
        history.add(new StoryOutcome(pending.id(), choice.id(), choice.consequencePreview(), quest, now));
        return new Change(new StorySnapshot(stories.worldId(), stories.ownerId(),
                Math.incrementExact(stories.revision()), null, history), Outcome.CHOSEN, quest);
    }

    public static UUID questId(UUID worldId, UUID ownerId, UUID planId, String choiceId) {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(planId, "planId");
        StoryValues.choiceId(choiceId);
        return UUID.nameUUIDFromBytes(("wicklen:story-quest:" + worldId + ":" + ownerId + ":" + planId
                + ":" + choiceId).getBytes(StandardCharsets.UTF_8));
    }

    private static Outcome context(StorySnapshot stories, DiscoverySnapshot discoveries, UUID actor) {
        Objects.requireNonNull(stories, "stories");
        Objects.requireNonNull(discoveries, "discoveries");
        if (!stories.ownerId().equals(actor) || !discoveries.ownerId().equals(actor)) return Outcome.WRONG_OWNER;
        if (!stories.worldId().equals(discoveries.worldId())) return Outcome.WRONG_WORLD;
        return null;
    }

    private static boolean known(DiscoverySnapshot discoveries, dev.wicklen.chronicles.core.discovery.DiscoveryKey key) {
        return discoveries.entries().stream().anyMatch(entry -> entry.key().equals(key));
    }

    private static boolean alreadyChosen(StorySnapshot snapshot, UUID planId) {
        return snapshot.history().stream().anyMatch(outcome -> outcome.planId().equals(planId));
    }

    private static Change rejected(StorySnapshot snapshot, Outcome outcome) {
        return new Change(snapshot, outcome, null);
    }
}
