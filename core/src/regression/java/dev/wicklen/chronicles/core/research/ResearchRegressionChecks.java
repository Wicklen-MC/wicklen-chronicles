package dev.wicklen.chronicles.core.research;

import dev.wicklen.chronicles.core.discovery.*;
import dev.wicklen.chronicles.core.quest.RewardDefinition;

import java.util.List;
import java.util.UUID;

public final class ResearchRegressionChecks {
    public static void main(String[] args) {
        UUID world = UUID.randomUUID(), owner = UUID.randomUUID(), other = UUID.randomUUID();
        var definition = new ResearchDefinition("wicklen_chronicles:research/creatures", 1, "Three creatures",
                DiscoveryKind.CREATURE, 3, new RewardDefinition("minecraft:emerald", 1));
        var series = ResearchTransitions.empty(world, owner);
        var discoveries = DiscoveryTransitions.empty(world, owner);
        for (int i = 0; i < 2; i++) discoveries = observe(discoveries, DiscoveryKind.CREATURE, i);
        discoveries = observe(discoveries, DiscoveryKind.PLACE, 0);
        require(ResearchTransitions.progress(series, discoveries, definition).count() == 2, "other kinds do not count toward creatures");
        require(ResearchTransitions.evaluate(series, owner, discoveries, definition).outcome()
                == ResearchTransitions.Outcome.NOT_READY, "incomplete series grants no reward");
        var repeated = observe(discoveries, DiscoveryKind.CREATURE, 0);
        require(repeated == discoveries, "repeated species does not add research progress");
        discoveries = observe(discoveries, DiscoveryKind.CREATURE, 2);
        var eligible = ResearchTransitions.evaluate(series, owner, discoveries, definition);
        require(eligible.changed() && eligible.outcome() == ResearchTransitions.Outcome.GRANTED, "three distinct creatures create one entitlement");
        require(!eligible.grant().claimed(), "completion is not item delivery");
        var restored = new ResearchSnapshot(world, owner, eligible.snapshot().revision(), eligible.snapshot().grants());
        var claimed = ResearchTransitions.acknowledgeReward(restored, owner, eligible.grant().id());
        require(claimed.changed() && claimed.grant().claimed(), "explicit delivery acknowledgement is persisted");
        require(!ResearchTransitions.acknowledgeReward(claimed.snapshot(), owner, eligible.grant().id()).changed(), "replayed claim cannot deliver again");
        require(ResearchTransitions.acknowledgeReward(restored, other, eligible.grant().id()).outcome()
                == ResearchTransitions.Outcome.WRONG_OWNER, "another player cannot claim series reward");
        var changedCatalog = new ResearchDefinition(definition.id(), 2, "Changed series", DiscoveryKind.PLACE, 2,
                new RewardDefinition("minecraft:diamond", 8));
        var replay = ResearchTransitions.evaluate(claimed.snapshot(), owner, discoveries, changedCatalog);
        require(!replay.changed() && replay.grant().definition().equals(definition), "catalog upgrade cannot reroll or repeat an accepted grant");
        var placeSeries = new ResearchDefinition("wicklen_chronicles:research/places", 1, "Two places",
                DiscoveryKind.PLACE, 2, new RewardDefinition("minecraft:torch", 8));
        require(!ResearchTransitions.progress(claimed.snapshot(), discoveries, placeSeries).ready(), "one place is insufficient for place series");
        discoveries = observe(discoveries, DiscoveryKind.PLACE, 1);
        require(ResearchTransitions.evaluate(claimed.snapshot(), owner, discoveries, placeSeries).changed(), "two different places complete their own series");
        require(ResearchTransitions.evaluate(series, owner, DiscoveryTransitions.empty(other, owner), definition).outcome()
                == ResearchTransitions.Outcome.WRONG_WORLD, "another world's discoveries cannot complete a series");
        require(!ResearchTransitions.rewardId(world, owner, definition.id()).equals(ResearchTransitions.rewardId(world, other, definition.id())),
                "personal series have personal grant IDs");
        System.out.println("Research core: distinct-kind counts, stable grants, restore/replay and catalog preservation passed.");
    }

    private static DiscoverySnapshot observe(DiscoverySnapshot state, DiscoveryKind kind, int index) {
        return DiscoveryTransitions.observe(state, state.ownerId(), new Observation(
                new DiscoveryKey(kind, "wicklen_chronicles:test/" + index), "Known observation", "Recorded personal fact",
                new DiscoveryPosition("minecraft:overworld", index, 70, 0), index), null).snapshot();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
