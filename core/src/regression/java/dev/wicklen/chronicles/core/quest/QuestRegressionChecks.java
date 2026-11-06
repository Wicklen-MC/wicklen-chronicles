package dev.wicklen.chronicles.core.quest;

import java.util.UUID;

import static dev.wicklen.chronicles.core.quest.QuestTransitions.Outcome;

/** Behavioral checks executable with only a JDK; no game runtime or test library. */
public final class QuestRegressionChecks {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID QUEST = UUID.fromString("00000000-0000-0000-0000-000000000003");

    public static void main(String[] args) {
        collectionRequiresExplicitSubmission();
        deliveryRequiresItsOwnAcknowledgement();
        commandsAreBoundToOwnerAndInstance();
        observationsAreSpecificAndBounded();
        acceptedDefinitionsAndHistoryStayImmutable();
        invalidSnapshotsAreRejected();
        System.out.println("Quest core: 6 behavioral scenarios passed (no Minecraft or external services).");
        dev.wicklen.chronicles.core.discovery.DiscoveryRegressionChecks.main(args);
        dev.wicklen.chronicles.core.story.StoryRegressionChecks.main(args);
        dev.wicklen.chronicles.core.encounter.EncounterRegressionChecks.main(args);
        dev.wicklen.chronicles.core.encounter.EncounterDirectorChecks.main(args);
        dev.wicklen.chronicles.core.annotation.AnnotationChecks.main(args);
        dev.wicklen.chronicles.core.field.FirstLightChecks.main(args);
        dev.wicklen.chronicles.core.research.ResearchRegressionChecks.main(args);
    }

    private static void collectionRequiresExplicitSubmission() {
        var issued = issue(QuestType.COLLECT, "minecraft:apple", 3);
        var ready = QuestTransitions.observeInventory(issued, 9);
        require(ready.readyForTurnIn() && ready.progress() == 3, "enough items only makes task ready");
        require(!ready.pendingReward(), "tick never grants reward entitlement");
        var spent = QuestTransitions.observeInventory(ready, 1);
        require(spent.status() == QuestStatus.ACTIVE && spent.progress() == 1,
                "spending collected items removes readiness");
        var staleInventory = QuestTransitions.turnIn(ready, OWNER, QUEST, 0);
        require(staleInventory.outcome() == Outcome.NOT_READY && staleInventory.quest().progress() == 0,
                "submission rechecks current inventory even after a ready observation");
        var submitted = QuestTransitions.turnIn(issued, OWNER, QUEST, 3);
        require(submitted.accepted() && submitted.quest().pendingReward(),
                "fresh count permits explicit submission without waiting for another tick");
        var replay = QuestTransitions.turnIn(submitted.quest(), OWNER, QUEST, 3);
        require(!replay.accepted() && replay.outcome() == Outcome.ALREADY_TURNED_IN,
                "repeated submission cannot consume materials twice");
        require(QuestTransitions.observeInventory(submitted.quest(), 0).equals(submitted.quest()),
                "submitted materials no longer depend on inventory contents");
    }

    private static void deliveryRequiresItsOwnAcknowledgement() {
        var ready = QuestTransitions.observeVisit(issue(QuestType.VISIT, "minecraft:taiga", 1), "minecraft:taiga");
        require(QuestTransitions.claim(ready, OWNER, ready.rewardId()).outcome() == Outcome.NOT_READY,
                "ready work is not an already submitted reward");
        var pending = QuestTransitions.turnIn(ready, OWNER, QUEST, 0).quest();
        require(pending.status() == QuestStatus.REWARD_PENDING, "failed native delivery can retain pending state");
        var delivered = QuestTransitions.claim(pending, OWNER, pending.rewardId());
        require(delivered.accepted() && delivered.quest().status() == QuestStatus.REWARDED,
                "platform acknowledgement completes delivery");
        require(QuestTransitions.claim(delivered.quest(), OWNER, pending.rewardId()).outcome() == Outcome.ALREADY_CLAIMED,
                "duplicate delivery acknowledgement cannot grant another reward");
        require(QuestTransitions.turnIn(delivered.quest(), OWNER, QUEST, 0).outcome() == Outcome.ALREADY_CLAIMED,
                "completed history cannot be submitted again");
    }

    private static void commandsAreBoundToOwnerAndInstance() {
        var ready = QuestTransitions.observeInventory(issue(QuestType.COLLECT, "minecraft:apple", 3), 3);
        require(QuestTransitions.turnIn(ready, OTHER, QUEST, 3).outcome() == Outcome.WRONG_OWNER,
                "another player cannot submit a quest");
        require(QuestTransitions.turnIn(ready, OWNER, OTHER, 3).outcome() == Outcome.STALE_ID,
                "old command cannot submit a replacement quest");
        var pending = QuestTransitions.turnIn(ready, OWNER, QUEST, 3).quest();
        require(QuestTransitions.claim(pending, OTHER, pending.rewardId()).outcome() == Outcome.WRONG_OWNER,
                "another player cannot acknowledge a reward");
        require(QuestTransitions.claim(pending, OWNER, OTHER).outcome() == Outcome.STALE_ID,
                "reward command is tied to its grant ID");
    }

    private static void observationsAreSpecificAndBounded() {
        var visit = issue(QuestType.VISIT, "minecraft:taiga", 1);
        require(QuestTransitions.observeVisit(visit, "minecraft:desert").equals(visit), "wrong biome does not count");
        var slay = issue(QuestType.SLAY, "minecraft:zombie", 2);
        require(QuestTransitions.observeKill(slay, "minecraft:skeleton").equals(slay), "wrong mob does not count");
        slay = QuestTransitions.observeKill(slay, "minecraft:zombie");
        slay = QuestTransitions.observeKill(slay, "minecraft:zombie");
        slay = QuestTransitions.observeKill(slay, "minecraft:zombie");
        require(slay.progress() == 2 && slay.readyForTurnIn(), "kills stop at required count without auto-completion");
        require(QuestTransitions.observeInventory(slay, 100).equals(slay), "inventory cannot advance a kill task");
        var maximum = new QuestInstance(QUEST, OWNER, definition(QuestType.SLAY, "minecraft:zombie", Integer.MAX_VALUE),
                Integer.MAX_VALUE, 0, QuestStatus.READY, slay.rewardId());
        require(QuestTransitions.observeKill(maximum, "minecraft:zombie").equals(maximum), "bounded kills do not overflow");
    }

    private static void acceptedDefinitionsAndHistoryStayImmutable() {
        var first = issue(QuestType.COLLECT, "minecraft:apple", 3);
        var updatedCatalogue = new QuestDefinition(first.definition().id(), 2, QuestType.COLLECT,
                "minecraft:coal", 16, new RewardDefinition("minecraft:emerald", 4));
        var pending = QuestTransitions.turnIn(first, OWNER, QUEST, 3).quest();
        var history = QuestTransitions.claim(pending, OWNER, pending.rewardId()).quest();
        require(first.progress() == 0 && first.status() == QuestStatus.ACTIVE, "old snapshots are not mutated");
        require(history.definition().version() == 1 && history.definition().target().equals("minecraft:apple"),
                "accepted quest retains its content version");
        require(!history.definition().equals(updatedCatalogue) && history.definition().reward().count() == 2,
                "catalogue replacement cannot change an accepted reward");
        require(first.rewardId().equals(pending.rewardId()) && first.rewardId().equals(history.rewardId()),
                "reward identity is stable through all transitions");
        var repeat = QuestTransitions.issue(OTHER, OWNER, first.definition(), 1);
        require(!repeat.id().equals(first.id()) && !repeat.rewardId().equals(first.rewardId()),
                "repeatable quest is a new instance, preserving previous history");
    }

    private static void invalidSnapshotsAreRejected() {
        expectInvalid(() -> definition(QuestType.VISIT, "minecraft:taiga", 2));
        expectInvalid(() -> new RewardDefinition("minecraft:apple", 0));
        var issued = issue(QuestType.COLLECT, "minecraft:apple", 3);
        expectInvalid(() -> new QuestInstance(QUEST, OWNER, issued.definition(), 3, 0, QuestStatus.ACTIVE, issued.rewardId()));
        expectInvalid(() -> new QuestInstance(QUEST, OWNER, issued.definition(), 1, 0, QuestStatus.REWARDED, issued.rewardId()));
        expectInvalid(() -> QuestTransitions.observeInventory(issued, -1));
    }

    private static QuestInstance issue(QuestType type, String target, int required) {
        return QuestTransitions.issue(QUEST, OWNER, definition(type, target, required), 0);
    }

    private static QuestDefinition definition(QuestType type, String target, int required) {
        return new QuestDefinition("wicklen_chronicles:test", 1, type, target, required,
                new RewardDefinition("minecraft:diamond", 2));
    }

    private static void expectInvalid(Runnable operation) {
        try {
            operation.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Invalid state was accepted");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
