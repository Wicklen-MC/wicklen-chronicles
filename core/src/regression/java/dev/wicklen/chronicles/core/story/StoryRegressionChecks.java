package dev.wicklen.chronicles.core.story;

import dev.wicklen.chronicles.core.discovery.DiscoveryKey;
import dev.wicklen.chronicles.core.discovery.DiscoveryKind;
import dev.wicklen.chronicles.core.discovery.DiscoveryPosition;
import dev.wicklen.chronicles.core.discovery.DiscoverySnapshot;
import dev.wicklen.chronicles.core.discovery.DiscoveryTransitions;
import dev.wicklen.chronicles.core.discovery.Observation;
import dev.wicklen.chronicles.core.quest.QuestDefinition;
import dev.wicklen.chronicles.core.quest.QuestTransitions;
import dev.wicklen.chronicles.core.quest.QuestType;
import dev.wicklen.chronicles.core.quest.RewardDefinition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class StoryRegressionChecks {
    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-000000000021");
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000022");
    private static final UUID PLAN = UUID.fromString("00000000-0000-0000-0000-000000000023");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000024");
    private static final DiscoveryKey SOURCE = new DiscoveryKey(DiscoveryKind.PLACE, "wicklen_chronicles:place/test");

    public static void main(String[] args) {
        authoredFallbackUsesTheSameAcceptancePath();
        staleAndForeignPlansAreRejected();
        capabilityPolicyIsAnAllowlist();
        pendingPlansCannotBeOverwritten();
        occupiedSlotAndInvalidChoicePreserveThePlan();
        choicesAreImmutableAcrossRestoreAndReplay();
        laterKnowledgeDoesNotRewriteAcceptedChoices();
        snapshotsAndPolicyAreImmutableAndValidated();
        System.out.println("Story core: 8 behavioral scenarios passed (typed plans, policy, occupied slots, stable outcomes and replay).");
    }

    private static void authoredFallbackUsesTheSameAcceptancePath() {
        var discoveries = discoveries();
        var proposed = plan(PLAN, discoveries.revision());
        var accepted = StoryTransitions.accept(StoryTransitions.empty(WORLD, PLAYER), discoveries, PLAYER, proposed, policy());
        require(accepted.outcome() == StoryTransitions.Outcome.ACCEPTED && accepted.changed(), "authored fallback needs no model call");
        require(accepted.quest() == null && accepted.snapshot().pending().equals(proposed), "proposing a story cannot issue a quest");
        require(accepted.snapshot().history().isEmpty(), "unselected story has no outcome");
    }

    private static void staleAndForeignPlansAreRejected() {
        var state = StoryTransitions.empty(WORLD, PLAYER);
        var discoveries = discoveries();
        require(StoryTransitions.accept(state, discoveries, PLAYER, plan(PLAN, 0), policy()).outcome()
                == StoryTransitions.Outcome.STALE_DISCOVERY_REVISION, "late plan cannot apply to changed knowledge");
        var own = plan(PLAN, discoveries.revision());
        var foreign = new StoryPlan(own.id(), WORLD, OTHER, own.expectedDiscoveryRevision(), own.source(), own.title(), own.motive(), own.choices());
        require(StoryTransitions.accept(state, discoveries, PLAYER, foreign, policy()).outcome()
                == StoryTransitions.Outcome.WRONG_OWNER, "foreign model result cannot apply");
        var foreignWorld = new StoryPlan(own.id(), OTHER, PLAYER, own.expectedDiscoveryRevision(), own.source(), own.title(), own.motive(), own.choices());
        require(StoryTransitions.accept(state, discoveries, PLAYER, foreignWorld, policy()).outcome()
                == StoryTransitions.Outcome.WRONG_WORLD, "another world cannot apply an async result");
        var hidden = new StoryPlan(own.id(), WORLD, PLAYER, own.expectedDiscoveryRevision(),
                new DiscoveryKey(DiscoveryKind.PLACE, "wicklen_chronicles:place/unknown"), own.title(), own.motive(), own.choices());
        require(StoryTransitions.accept(state, discoveries, PLAYER, hidden, policy()).outcome()
                == StoryTransitions.Outcome.UNKNOWN_DISCOVERY, "story cannot reveal an undiscovered place");
    }

    private static void capabilityPolicyIsAnAllowlist() {
        var discoveries = discoveries();
        var state = StoryTransitions.empty(WORLD, PLAYER);
        var own = plan(PLAN, discoveries.revision());
        var denied = new StoryPolicy(Map.of(QuestType.COLLECT, Set.of("minecraft:coal")), Set.of("minecraft:emerald"), 16, 2);
        require(StoryTransitions.accept(state, discoveries, PLAYER, own, denied).outcome()
                == StoryTransitions.Outcome.POLICY_REJECTED, "every offered action must be available before acceptance");
        require(!policy().accepts(new QuestDefinition("wicklen_chronicles:forged", 1, QuestType.COLLECT,
                "minecraft:coal", 3, new RewardDefinition("minecraft:diamond", 1))), "unlisted reward is rejected");
        require(!policy().accepts(new QuestDefinition("wicklen_chronicles:forged", 1, QuestType.COLLECT,
                "minecraft:coal", 3, new RewardDefinition("minecraft:emerald", 3))), "over-budget reward is rejected");
        require(!policy().accepts(new QuestDefinition("wicklen_chronicles:forged", 1, QuestType.COLLECT,
                "minecraft:coal", 17, new RewardDefinition("minecraft:emerald", 1))), "over-budget objective is rejected");
        require(!policy().accepts(new QuestDefinition("wicklen_chronicles:forged", 1, QuestType.SLAY,
                "minecraft:coal", 1, new RewardDefinition("minecraft:emerald", 1))), "target allowance is specific to action type");
    }

    private static void pendingPlansCannotBeOverwritten() {
        var accepted = accepted();
        var replacement = StoryTransitions.accept(accepted, discoveries(), PLAYER, plan(OTHER, 1), policy());
        require(replacement.outcome() == StoryTransitions.Outcome.PLAN_PENDING && replacement.snapshot() == accepted,
                "a new model response cannot replace accepted choices");
        require(StoryTransitions.accept(accepted, discoveries(), PLAYER, accepted.pending(), policy()).outcome()
                == StoryTransitions.Outcome.PLAN_PENDING, "duplicate response does not increase revision");
    }

    private static void occupiedSlotAndInvalidChoicePreserveThePlan() {
        var accepted = accepted();
        var busy = StoryTransitions.choose(accepted, discoveries(), PLAYER, PLAN, "repair", policy(), true, 100);
        require(busy.outcome() == StoryTransitions.Outcome.QUEST_SLOT_OCCUPIED && busy.snapshot() == accepted,
                "busy quest slot cannot consume a choice");
        require(StoryTransitions.choose(accepted, discoveries(), PLAYER, OTHER, "repair", policy(), false, 100).outcome()
                == StoryTransitions.Outcome.STALE_PLAN, "stale UI action cannot choose another plan");
        require(StoryTransitions.choose(accepted, discoveries(), PLAYER, PLAN, "forged", policy(), false, 100).outcome()
                == StoryTransitions.Outcome.UNKNOWN_CHOICE, "client cannot invent a choice");
        require(StoryTransitions.choose(accepted, discoveries(), OTHER, PLAN, "repair", policy(), false, 100).outcome()
                == StoryTransitions.Outcome.WRONG_OWNER, "another player cannot select a consequence");
        var lostCapability = new StoryPolicy(Map.of(QuestType.SLAY, Set.of("minecraft:zombie")), Set.of("minecraft:emerald"), 16, 2);
        require(StoryTransitions.choose(accepted, discoveries(), PLAYER, PLAN, "repair", lostCapability, false, 100).outcome()
                == StoryTransitions.Outcome.POLICY_REJECTED, "removed integration/target cannot issue an impossible quest");
    }

    private static void choicesAreImmutableAcrossRestoreAndReplay() {
        var selected = StoryTransitions.choose(accepted(), discoveries(), PLAYER, PLAN, "repair", policy(), false, 100);
        require(selected.outcome() == StoryTransitions.Outcome.CHOSEN && selected.changed(), "known choice issues a quest proposal");
        require(selected.snapshot().pending() == null && selected.snapshot().history().size() == 1, "choice is archived once");
        var outcome = selected.snapshot().history().get(0);
        require(outcome.quest().equals(selected.quest()) && outcome.consequence().equals("The workshop gets a supply task."),
                "history fixes the selected quest and visible consequence");
        require(selected.quest().id().equals(StoryTransitions.questId(WORLD, PLAYER, PLAN, "repair")), "quest identity is recoverable");
        var restored = new StorySnapshot(WORLD, PLAYER, selected.snapshot().revision(), null, selected.snapshot().history());
        var replay = StoryTransitions.choose(restored, discoveries(), PLAYER, PLAN, "fight", policy(), false, 200);
        require(replay.outcome() == StoryTransitions.Outcome.ALREADY_CHOSEN && replay.snapshot() == restored && replay.quest() == null,
                "replayed alternate choice cannot rewrite history or issue another quest");
        require(StoryTransitions.accept(restored, discoveries(), PLAYER, plan(PLAN, 1), policy()).outcome()
                == StoryTransitions.Outcome.ALREADY_CHOSEN, "accepted plan ID cannot be reused after completion");
        var later = StoryTransitions.accept(restored, discoveries(), PLAYER, plan(OTHER, 1), policy()).snapshot();
        var oldReplay = StoryTransitions.choose(later, discoveries(), PLAYER, PLAN, "fight", policy(), false, 300);
        require(oldReplay.snapshot() == later && later.pending().id().equals(OTHER), "old action cannot consume a newer pending plan");
    }

    private static void laterKnowledgeDoesNotRewriteAcceptedChoices() {
        var discoveries = DiscoveryTransitions.observe(discoveries(), PLAYER,
                new Observation(new DiscoveryKey(DiscoveryKind.BIOME, "minecraft:plains"), "Plains", "A new biome",
                        new DiscoveryPosition("minecraft:overworld", 100, 70, 100), 500), null).snapshot();
        var selected = StoryTransitions.choose(accepted(), discoveries, PLAYER, PLAN, "fight", policy(), false, 600);
        require(selected.outcome() == StoryTransitions.Outcome.CHOSEN, "unrelated later observation does not invalidate accepted choices");
    }

    private static void snapshotsAndPolicyAreImmutableAndValidated() {
        var choices = new ArrayList<>(plan(PLAN, 1).choices());
        var plan = new StoryPlan(PLAN, WORLD, PLAYER, 1, SOURCE, "Station", "Two possible routes", choices);
        choices.clear();
        require(plan.choices().size() == 2, "plan owns its choice list");
        var targetSet = new HashSet<>(Set.of("minecraft:coal"));
        var targetMap = new HashMap<QuestType, Set<String>>();
        targetMap.put(QuestType.COLLECT, targetSet);
        var policy = new StoryPolicy(targetMap, Set.of("minecraft:emerald"), 16, 2);
        targetSet.clear();
        targetMap.clear();
        require(policy.allowedTargets().get(QuestType.COLLECT).contains("minecraft:coal"), "policy deep-copies capability collections");
        expect(() -> new StoryPlan(PLAN, WORLD, PLAYER, 1, SOURCE, "Station", "Motive", List.of(plan.choices().get(0), plan.choices().get(0))));
        var selected = StoryTransitions.choose(accepted(), discoveries(), PLAYER, PLAN, "repair", policy(), false, 100).snapshot();
        expect(() -> new StorySnapshot(WORLD, PLAYER, selected.revision(), plan, selected.history()));
        expect(() -> new StorySnapshot(WORLD, PLAYER, selected.revision(), null,
                List.of(selected.history().get(0), selected.history().get(0))));
        var original = selected.history().get(0);
        var wrongQuest = QuestTransitions.issue(OTHER, PLAYER, original.quest().definition(), 100);
        expect(() -> new StorySnapshot(WORLD, PLAYER, 1, null,
                List.of(new StoryOutcome(PLAN, "repair", "Tampered quest identity", wrongQuest, 100))));
        var issued = original.quest();
        var wrongReward = new dev.wicklen.chronicles.core.quest.QuestInstance(issued.id(), issued.ownerId(), issued.definition(),
                issued.progress(), issued.issuedAt(), issued.status(), OTHER);
        expect(() -> new StorySnapshot(WORLD, PLAYER, 1, null,
                List.of(new StoryOutcome(PLAN, "repair", "Tampered reward identity", wrongReward, 100))));
    }

    private static StorySnapshot accepted() {
        return StoryTransitions.accept(StoryTransitions.empty(WORLD, PLAYER), discoveries(), PLAYER, plan(PLAN, 1), policy()).snapshot();
    }

    private static DiscoverySnapshot discoveries() {
        return DiscoveryTransitions.observe(DiscoveryTransitions.empty(WORLD, PLAYER), PLAYER,
                new Observation(SOURCE, "Forest station", "An observed workshop",
                        new DiscoveryPosition("minecraft:overworld", 10, 70, 20), 10), null).snapshot();
    }

    private static StoryPlan plan(UUID id, long expectedRevision) {
        var repair = new QuestDefinition("wicklen_chronicles:story/repair", 1, QuestType.COLLECT,
                "minecraft:coal", 3, new RewardDefinition("minecraft:emerald", 1));
        var fight = new QuestDefinition("wicklen_chronicles:story/fight", 1, QuestType.SLAY,
                "minecraft:zombie", 2, new RewardDefinition("minecraft:emerald", 2));
        return new StoryPlan(id, WORLD, PLAYER, expectedRevision, SOURCE, "Station supplies", "The station needs a practical first step.",
                List.of(new StoryChoice("repair", "Gather supplies", "The workshop gets a supply task.", repair),
                        new StoryChoice("fight", "Clear nearby threats", "The route gets a combat task.", fight)));
    }

    private static StoryPolicy policy() {
        return new StoryPolicy(Map.of(QuestType.COLLECT, Set.of("minecraft:coal"),
                QuestType.SLAY, Set.of("minecraft:zombie")), Set.of("minecraft:emerald"), 16, 2);
    }

    private static void expect(Runnable operation) {
        try { operation.run(); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Invalid story state was accepted");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
