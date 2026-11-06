package dev.wicklen.chronicles.core.encounter;

import dev.wicklen.chronicles.core.discovery.DiscoveryKey;
import dev.wicklen.chronicles.core.discovery.DiscoveryKind;
import dev.wicklen.chronicles.core.quest.RewardDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class EncounterRegressionChecks {
    private static final UUID ENCOUNTER = UUID.fromString("00000000-0000-0000-0000-000000000031");
    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-000000000032");
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000033");
    private static final UUID PARTNER = UUID.fromString("00000000-0000-0000-0000-000000000034");
    private static final UUID LATE_JOINER = UUID.fromString("00000000-0000-0000-0000-000000000035");
    private static final DiscoveryKey PLACE = new DiscoveryKey(DiscoveryKind.PLACE, "wicklen_chronicles:place/station");
    private static final RewardDefinition REWARD = new RewardDefinition("minecraft:emerald", 2);

    public static void main(String[] args) {
        explicitStartFreezesPartyAndMode();
        onlyStartedEncountersCanSucceed();
        completionIsFinalAndIdempotent();
        unsuccessfulOutcomesNeverGrantRewards();
        acknowledgementsArePerParticipantAndReplaySafe();
        restoredEntitlementsKeepTheirIdentity();
        worldsPlacesAndInstancesHaveDistinctGrants();
        invalidPersistedLifecycleIsRejected();
        System.out.println("Encounter core: 8 behavioral scenarios passed (frozen party, lifecycle, terminal outcomes and participant grants).");
    }

    private static void explicitStartFreezesPartyAndMode() {
        var party = new ArrayList<>(List.of(PLAYER, PARTNER));
        var prepared = EncounterTransitions.prepare(ENCOUNTER, WORLD, PLACE, EncounterMode.EMPOWERED, party, REWARD, 100);
        party.add(LATE_JOINER);
        require(prepared.participants().equals(List.of(PLAYER, PARTNER)), "late roster mutation cannot alter prepared scaling");
        require(prepared.status() == EncounterStatus.PREPARED && prepared.rewards().isEmpty(), "preparation does not launch or grant loot");
        var outsider = EncounterTransitions.start(prepared, LATE_JOINER, ENCOUNTER, 200);
        require(!outsider.changed() && outsider.outcome() == EncounterTransitions.Outcome.WRONG_PARTICIPANT,
                "late joiner cannot start the frozen group's encounter");
        var started = EncounterTransitions.start(prepared, PLAYER, ENCOUNTER, 200);
        require(started.changed() && started.instance().mode() == EncounterMode.EMPOWERED,
                "explicit member action starts the chosen mode");
        require(started.instance().participants().size() == 2, "starting cannot resample current server population");
        require(EncounterTransitions.start(started.instance(), PLAYER, ENCOUNTER, 201).outcome()
                == EncounterTransitions.Outcome.NOT_PREPARED, "duplicate start cannot launch a second executor");
    }

    private static void onlyStartedEncountersCanSucceed() {
        var prepared = prepared();
        require(EncounterTransitions.finish(prepared, ENCOUNTER, EncounterStatus.SUCCESS, 300).outcome()
                == EncounterTransitions.Outcome.NOT_ACTIVE, "prepared encounter cannot manufacture success rewards");
        require(EncounterTransitions.start(prepared, PLAYER, PARTNER, 200).outcome()
                == EncounterTransitions.Outcome.STALE_ID, "stale UI ID cannot start a different encounter");
        require(EncounterTransitions.finish(active(), PARTNER, EncounterStatus.SUCCESS, 300).outcome()
                == EncounterTransitions.Outcome.STALE_ID, "unmatched executor callback cannot finish another instance");
    }

    private static void completionIsFinalAndIdempotent() {
        var active = active();
        var success = EncounterTransitions.finish(active, ENCOUNTER, EncounterStatus.SUCCESS, 300);
        require(success.changed() && success.instance().rewards().size() == 2, "one pending grant per participant");
        require(success.instance().rewards().stream().noneMatch(EncounterReward::claimed), "executor completion is not native item delivery");
        var repeated = EncounterTransitions.finish(success.instance(), ENCOUNTER, EncounterStatus.SUCCESS, 400);
        require(repeated.instance() == success.instance() && !repeated.changed(), "duplicate completion cannot add another set of grants");
        var changedResult = EncounterTransitions.finish(success.instance(), ENCOUNTER, EncounterStatus.FAILURE, 400);
        require(changedResult.outcome() == EncounterTransitions.Outcome.ALREADY_FINISHED
                && changedResult.instance().status() == EncounterStatus.SUCCESS, "later callback cannot rewrite a terminal outcome");
    }

    private static void unsuccessfulOutcomesNeverGrantRewards() {
        var failure = EncounterTransitions.finish(active(), ENCOUNTER, EncounterStatus.FAILURE, 300).instance();
        require(failure.rewards().isEmpty(), "failure grants no completion entitlement");
        var aborted = EncounterTransitions.finish(prepared(), ENCOUNTER, EncounterStatus.ABORTED, 150).instance();
        require(aborted.terminal() && aborted.startedAt() == -1 && aborted.rewards().isEmpty(), "prepared encounter can cancel without starting");
        require(EncounterTransitions.finish(aborted, ENCOUNTER, EncounterStatus.SUCCESS, 300).outcome()
                == EncounterTransitions.Outcome.ALREADY_FINISHED, "cancelled instance cannot later report success");
        require(EncounterTransitions.acknowledgeReward(failure, PLAYER, PARTNER).outcome()
                == EncounterTransitions.Outcome.NO_REWARDS, "failed execution cannot authorize item delivery");
    }

    private static void acknowledgementsArePerParticipantAndReplaySafe() {
        var success = success();
        var own = success.rewards().stream().filter(reward -> reward.ownerId().equals(PLAYER)).findFirst().orElseThrow();
        require(EncounterTransitions.acknowledgeReward(success, PARTNER, own.id()).outcome()
                == EncounterTransitions.Outcome.UNKNOWN_REWARD, "another participant cannot take this owner's entitlement");
        require(EncounterTransitions.acknowledgeReward(success, LATE_JOINER, own.id()).outcome()
                == EncounterTransitions.Outcome.WRONG_PARTICIPANT, "late joiner is not added to reward roster");
        var acknowledged = EncounterTransitions.acknowledgeReward(success, PLAYER, own.id());
        require(acknowledged.changed() && acknowledged.instance().rewards().stream().filter(EncounterReward::claimed).count() == 1,
                "only the delivering owner's grant is acknowledged");
        require(EncounterTransitions.acknowledgeReward(acknowledged.instance(), PLAYER, own.id()).outcome()
                == EncounterTransitions.Outcome.ALREADY_CLAIMED, "reward replay is rejected");
        require(!success.rewards().get(0).claimed(), "acknowledgement does not mutate previous snapshots");
    }

    private static void restoredEntitlementsKeepTheirIdentity() {
        var success = success();
        var restored = new EncounterInstance(success.id(), success.worldId(), success.place(), success.mode(),
                new ArrayList<>(success.participants()), success.status(), success.reward(),
                new ArrayList<>(success.rewards()), success.preparedAt(), success.startedAt(), success.finishedAt());
        require(restored.rewards().equals(success.rewards()), "stable grant IDs survive serialization snapshot reconstruction");
        var own = restored.rewards().get(0);
        var claimed = EncounterTransitions.acknowledgeReward(restored, own.ownerId(), own.id()).instance();
        var restoredClaimed = new EncounterInstance(claimed.id(), claimed.worldId(), claimed.place(), claimed.mode(),
                claimed.participants(), claimed.status(), claimed.reward(), claimed.rewards(), claimed.preparedAt(),
                claimed.startedAt(), claimed.finishedAt());
        require(EncounterTransitions.acknowledgeReward(restoredClaimed, own.ownerId(), own.id()).outcome()
                == EncounterTransitions.Outcome.ALREADY_CLAIMED, "restore cannot reset an already delivered grant");
    }

    private static void worldsPlacesAndInstancesHaveDistinctGrants() {
        var own = EncounterTransitions.rewardId(ENCOUNTER, WORLD, PLACE, PLAYER);
        require(!own.equals(EncounterTransitions.rewardId(PARTNER, WORLD, PLACE, PLAYER)), "new instance has a new entitlement");
        require(!own.equals(EncounterTransitions.rewardId(ENCOUNTER, PARTNER, PLACE, PLAYER)), "world identity is part of grant identity");
        require(!own.equals(EncounterTransitions.rewardId(ENCOUNTER, WORLD,
                new DiscoveryKey(DiscoveryKind.PLACE, "wicklen_chronicles:place/other"), PLAYER)), "place identity is part of grant identity");
        require(!own.equals(EncounterTransitions.rewardId(ENCOUNTER, WORLD, PLACE, PARTNER)), "each participant owns a different grant");
    }

    private static void invalidPersistedLifecycleIsRejected() {
        expect(() -> EncounterTransitions.prepare(ENCOUNTER, WORLD, PLACE, EncounterMode.NORMAL, List.of(), REWARD, 100));
        expect(() -> EncounterTransitions.prepare(ENCOUNTER, WORLD, PLACE, EncounterMode.NORMAL, List.of(PLAYER, PLAYER), REWARD, 100));
        expect(() -> EncounterTransitions.prepare(ENCOUNTER, WORLD, new DiscoveryKey(DiscoveryKind.BIOME, "minecraft:plains"),
                EncounterMode.NORMAL, List.of(PLAYER), REWARD, 100));
        expect(() -> EncounterTransitions.start(prepared(), PLAYER, ENCOUNTER, 99));
        expect(() -> EncounterTransitions.finish(active(), ENCOUNTER, EncounterStatus.SUCCESS, 199));
        var success = success();
        expect(() -> new EncounterInstance(ENCOUNTER, WORLD, PLACE, EncounterMode.NORMAL, success.participants(),
                EncounterStatus.SUCCESS, REWARD, List.of(), 100, 200, 300));
        var forged = new ArrayList<>(success.rewards());
        var original = forged.get(0);
        forged.set(0, new EncounterReward(LATE_JOINER, original.ownerId(), original.definition(), false));
        expect(() -> new EncounterInstance(ENCOUNTER, WORLD, PLACE, EncounterMode.NORMAL, success.participants(),
                EncounterStatus.SUCCESS, REWARD, forged, 100, 200, 300));
    }

    private static EncounterInstance prepared() {
        return EncounterTransitions.prepare(ENCOUNTER, WORLD, PLACE, EncounterMode.NORMAL, List.of(PLAYER, PARTNER), REWARD, 100);
    }

    private static EncounterInstance active() {
        return EncounterTransitions.start(prepared(), PLAYER, ENCOUNTER, 200).instance();
    }

    private static EncounterInstance success() {
        return EncounterTransitions.finish(active(), ENCOUNTER, EncounterStatus.SUCCESS, 300).instance();
    }

    private static void expect(Runnable operation) {
        try { operation.run(); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Invalid encounter state was accepted");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
