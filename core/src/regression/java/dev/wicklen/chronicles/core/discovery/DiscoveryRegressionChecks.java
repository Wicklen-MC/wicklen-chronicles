package dev.wicklen.chronicles.core.discovery;

import dev.wicklen.chronicles.core.journal.JournalView;
import dev.wicklen.chronicles.core.journal.JournalViews;
import dev.wicklen.chronicles.core.quest.QuestDefinition;
import dev.wicklen.chronicles.core.quest.QuestInstance;
import dev.wicklen.chronicles.core.quest.QuestTransitions;
import dev.wicklen.chronicles.core.quest.QuestType;
import dev.wicklen.chronicles.core.quest.RewardDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class DiscoveryRegressionChecks {
    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000012");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000013");
    private static final RewardDefinition REWARD = new RewardDefinition("minecraft:emerald", 1);

    public static void main(String[] args) {
        repeatedObservationsPreserveAcceptedFacts();
        rewardAcknowledgementsAreStableAndIdempotent();
        ownershipAndWorldIdentityAreSeparate();
        snapshotsDoNotLeakMutableCollections();
        invalidPersistedValuesAreRejected();
        journalContainsOnlyPersonalKnownFacts();
        journalPagesAreBoundedAndStable();
        System.out.println("Discovery core: 7 behavioral scenarios passed (personal facts, rewards, immutable snapshots and journal pages).");
    }

    private static void repeatedObservationsPreserveAcceptedFacts() {
        var empty = DiscoveryTransitions.empty(WORLD, PLAYER);
        var first = DiscoveryTransitions.observe(empty, PLAYER, observation("minecraft:zombie", 10), REWARD);
        require(first.outcome() == DiscoveryTransitions.Outcome.RECORDED && first.changed(), "first observation records an entry");
        require(empty.entries().isEmpty() && first.snapshot().revision() == 1, "recording creates a new snapshot");
        var changed = new Observation(first.entry().key(), "A different name", "A different story",
                new DiscoveryPosition("minecraft:the_nether", 900, 50, 900), 999);
        var repeat = DiscoveryTransitions.observe(first.snapshot(), PLAYER, changed,
                new RewardDefinition("minecraft:diamond", 64));
        require(repeat.outcome() == DiscoveryTransitions.Outcome.ALREADY_KNOWN && !repeat.changed(), "repeat scan creates no entitlement");
        require(repeat.snapshot() == first.snapshot() && repeat.entry().equals(first.entry()),
                "repeat scan cannot rewrite accepted place/facts/reward or increase revision");
        var noReward = DiscoveryTransitions.observe(empty, PLAYER, observation("minecraft:spider", 11), null);
        require(noReward.entry().reward() == null, "not every observation requires a payment");
    }

    private static void rewardAcknowledgementsAreStableAndIdempotent() {
        var recorded = DiscoveryTransitions.observe(DiscoveryTransitions.empty(WORLD, PLAYER), PLAYER,
                observation("minecraft:zombie", 10), REWARD);
        var grant = recorded.entry().reward();
        require(!grant.claimed(), "recording leaves reward pending until platform delivery");
        var restored = new DiscoverySnapshot(WORLD, PLAYER, recorded.snapshot().revision(),
                new ArrayList<>(recorded.snapshot().entries()));
        var acknowledged = DiscoveryTransitions.acknowledgeReward(restored, PLAYER, grant.id());
        require(acknowledged.changed() && acknowledged.entry().reward().claimed(), "delivery acknowledgement survives snapshot restore");
        require(acknowledged.entry().reward().id().equals(grant.id()), "acknowledgement retains stable grant identity");
        var replay = DiscoveryTransitions.acknowledgeReward(acknowledged.snapshot(), PLAYER, grant.id());
        require(replay.outcome() == DiscoveryTransitions.Outcome.ALREADY_CLAIMED && !replay.changed(), "reward acknowledgement cannot repeat");
        require(replay.snapshot() == acknowledged.snapshot(), "replay cannot mutate history");
        var scanAgain = DiscoveryTransitions.observe(replay.snapshot(), PLAYER, observation("minecraft:zombie", 500), REWARD);
        require(scanAgain.entry().reward().claimed(), "rescanning a rewarded species never resets its entitlement");
        require(DiscoveryTransitions.acknowledgeReward(restored, PLAYER, OTHER).outcome()
                == DiscoveryTransitions.Outcome.UNKNOWN_REWARD, "unknown grant does not authorize delivery");
    }

    private static void ownershipAndWorldIdentityAreSeparate() {
        var own = DiscoveryTransitions.observe(DiscoveryTransitions.empty(WORLD, PLAYER), PLAYER,
                observation("minecraft:zombie", 10), REWARD);
        var denied = DiscoveryTransitions.observe(own.snapshot(), OTHER, observation("minecraft:spider", 11), REWARD);
        require(denied.outcome() == DiscoveryTransitions.Outcome.WRONG_OWNER && denied.entry() == null,
                "another player cannot mutate or read a private discovery through an action result");
        require(DiscoveryTransitions.acknowledgeReward(own.snapshot(), OTHER, own.entry().reward().id()).outcome()
                == DiscoveryTransitions.Outcome.WRONG_OWNER, "another player cannot acknowledge a grant");
        var secondPlayer = DiscoveryTransitions.observe(DiscoveryTransitions.empty(WORLD, OTHER), OTHER,
                observation("minecraft:zombie", 10), REWARD);
        var secondWorld = DiscoveryTransitions.observe(DiscoveryTransitions.empty(OTHER, PLAYER), PLAYER,
                observation("minecraft:zombie", 10), REWARD);
        require(!own.entry().reward().id().equals(secondPlayer.entry().reward().id()), "players have independent reward identities");
        require(!own.entry().reward().id().equals(secondWorld.entry().reward().id()), "worlds have independent reward identities");
        var biome = new Observation(new DiscoveryKey(DiscoveryKind.BIOME, "minecraft:zombie"), "Biome fixture", "Different kind",
                own.entry().position(), 20);
        var distinct = DiscoveryTransitions.observe(own.snapshot(), PLAYER, biome, null);
        require(distinct.snapshot().entries().size() == 2, "kind is part of discovery identity");
    }

    private static void snapshotsDoNotLeakMutableCollections() {
        var entry = DiscoveryTransitions.observe(DiscoveryTransitions.empty(WORLD, PLAYER), PLAYER,
                observation("minecraft:zombie", 10), REWARD).entry();
        var input = new ArrayList<>(List.of(entry));
        var snapshot = new DiscoverySnapshot(WORLD, PLAYER, 1, input);
        input.clear();
        require(snapshot.entries().size() == 1, "snapshot owns a defensive list copy");
        expect(UnsupportedOperationException.class, () -> snapshot.entries().clear());
        var view = JournalViews.create(snapshot, PLAYER, null, List.of(), 0, 0, 64);
        expect(UnsupportedOperationException.class, () -> view.discoveries().clear());
    }

    private static void invalidPersistedValuesAreRejected() {
        expect(IllegalArgumentException.class, () -> new DiscoveryKey(DiscoveryKind.CREATURE, "../secret"));
        expect(IllegalArgumentException.class, () -> new DiscoveryKey(DiscoveryKind.PLACE, "A namespace:place"));
        expect(IllegalArgumentException.class, () -> new DiscoveryPosition("overworld", 0, 0, 0));
        var first = DiscoveryTransitions.observe(DiscoveryTransitions.empty(WORLD, PLAYER), PLAYER,
                observation("minecraft:zombie", 10), REWARD);
        expect(IllegalArgumentException.class, () -> new DiscoverySnapshot(WORLD, PLAYER, -1, List.of()));
        expect(IllegalArgumentException.class, () -> new DiscoverySnapshot(WORLD, PLAYER, 2, List.of(first.entry(), first.entry())));
        var forged = new DiscoveryEntry(first.entry().key(), first.entry().title(), first.entry().summary(), first.entry().position(),
                first.entry().firstObservedAt(), new DiscoveryReward(OTHER, REWARD, false));
        expect(IllegalArgumentException.class, () -> new DiscoverySnapshot(WORLD, PLAYER, 1, List.of(forged)));
        expect(IllegalArgumentException.class, () -> new Observation(first.entry().key(), "x".repeat(161), "Summary", first.entry().position(), 10));
        expect(IllegalArgumentException.class, () -> new Observation(first.entry().key(), "Header\nspoof", "Summary", first.entry().position(), 10));
    }

    private static void journalContainsOnlyPersonalKnownFacts() {
        var own = DiscoveryTransitions.observe(DiscoveryTransitions.empty(WORLD, PLAYER), PLAYER,
                observation("minecraft:zombie", 10), null).snapshot();
        var anotherPlayer = DiscoveryTransitions.observe(DiscoveryTransitions.empty(WORLD, OTHER), OTHER,
                observation("minecraft:spider", 11), null).snapshot();
        var view = JournalViews.create(own, PLAYER, null, List.of(), 0, 0, 64);
        require(view.discoveries().equals(own.entries()), "read model is derived from personal facts only");
        require(view.discoveries().stream().noneMatch(entry -> entry.key().equals(anotherPlayer.entries().get(0).key())),
                "another player's coordinates/knowledge are not included");
        expect(IllegalArgumentException.class, () -> JournalViews.create(own, OTHER, null, List.of(), 0, 0, 64));
        var foreign = completedQuest(OTHER, OTHER, 10);
        expect(IllegalArgumentException.class, () -> JournalViews.create(own, PLAYER, null,
                List.of(completedQuest(PLAYER, WORLD, 20), foreign), 0, 0, 1));
        var completed = completedQuest(PLAYER, WORLD, 20);
        expect(IllegalArgumentException.class, () -> JournalViews.create(own, PLAYER, null,
                List.of(completed, completed), 0, 0, 1));
    }

    private static void journalPagesAreBoundedAndStable() {
        var state = DiscoveryTransitions.empty(WORLD, PLAYER);
        for (int i = 0; i < 65; i++) state = DiscoveryTransitions.observe(state, PLAYER,
                observation("wicklen_chronicles:place/" + i, i), null).snapshot();
        var first = JournalViews.create(state, PLAYER, null, List.of(), 0, 0, 64);
        var second = JournalViews.create(state, PLAYER, null, List.of(), 64, 0, 64);
        require(first.discoveries().size() == 64 && first.totalDiscoveries() == 65 && first.moreDiscoveries(), "page has explicit total and bound");
        require(second.discoveries().size() == 1 && !second.moreDiscoveries(), "last page contains remaining facts");
        require(first.discoveries().get(0).firstObservedAt() == 64 && second.discoveries().get(0).firstObservedAt() == 0,
                "newest entries precede old entries deterministically");
        var pastEnd = JournalViews.create(state, PLAYER, null, List.of(), 999, 999, 64);
        require(pastEnd.discoveries().isEmpty() && pastEnd.discoveryOffset() == 65, "past-end request is a valid empty page");
        final var finalState = state;
        expect(IllegalArgumentException.class, () -> JournalViews.create(finalState, PLAYER, null, List.of(), 0, 0, 65));
        expect(IllegalArgumentException.class, () -> new JournalView(WORLD, PLAYER, 0, 0, 1, finalState.entries(), null, 0, 0, List.of()));
    }

    private static Observation observation(String id, long time) {
        return new Observation(new DiscoveryKey(DiscoveryKind.CREATURE, id), "Observed creature", "Confirmed nearby observation",
                new DiscoveryPosition("minecraft:overworld", 10, 70, 20), time);
    }

    private static QuestInstance completedQuest(UUID owner, UUID id, long time) {
        var definition = new QuestDefinition("wicklen_chronicles:test", 1, QuestType.VISIT, "minecraft:plains", 1, REWARD);
        var quest = QuestTransitions.observeVisit(QuestTransitions.issue(id, owner, definition, time), "minecraft:plains");
        quest = QuestTransitions.turnIn(quest, owner, id, 0).quest();
        return QuestTransitions.claim(quest, owner, quest.rewardId()).quest();
    }

    private static void expect(Class<? extends Throwable> expected, Runnable operation) {
        try { operation.run(); }
        catch (Throwable error) {
            if (expected.isInstance(error)) return;
            throw new AssertionError("Unexpected failure", error);
        }
        throw new AssertionError("Invalid operation was accepted");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
