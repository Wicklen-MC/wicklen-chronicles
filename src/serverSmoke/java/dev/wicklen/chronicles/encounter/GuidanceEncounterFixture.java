package dev.wicklen.chronicles.encounter;

import dev.wicklen.chronicles.ai.WorldStorageContext;
import dev.wicklen.chronicles.core.discovery.*;
import dev.wicklen.chronicles.core.encounter.*;
import dev.wicklen.chronicles.core.quest.RewardDefinition;
import dev.wicklen.chronicles.station.*;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.UUID;

/** Isolated native pause fixture using the real participant ledger, without creating a portal. */
public final class GuidanceEncounterFixture {
    public static UUID start(ServerPlayer player, DiscoveryKey source) throws Exception {
        long now = System.currentTimeMillis();
        UUID id = UUID.randomUUID();
        var place = new PlaceManifest(WorldStorageContext.worldId(), source, "minecraft:overworld",
                new PlaceManifest.Point(0, 69, 0), new PlaceManifest.Point(7, 70, 5), new PlaceManifest.Point(0, 70, 5),
                new PlaceManifest.Bounds(0, 69, 0, 15, 75, 15), StationVariant.UNDEAD_COURTYARD, 1,
                new PlaceManifest.Bounds(2, 70, 0, 13, 75, 11));
        var prepared = EncounterTransitions.prepare(id, WorldStorageContext.worldId(), source,
                EncounterMode.NORMAL, List.of(player.getUUID()), new RewardDefinition("minecraft:paper", 1), now);
        var active = EncounterTransitions.start(prepared, player.getUUID(), id, now).instance();
        var session = new EncounterSession(id, player.getUUID(), place, EncounterMode.NORMAL, List.of(player.getUUID()),
                active, UUID.randomUUID(), now, now + 60_000);
        EncounterService.state(player.level().getServer()).ledger.put(session);
        return id;
    }

    public static void stop(ServerPlayer player, UUID id) throws Exception {
        var runtime = EncounterService.state(player.level().getServer());
        var session = runtime.find(id);
        var terminal = EncounterTransitions.finish(session.encounter(), id, EncounterStatus.ABORTED,
                System.currentTimeMillis()).instance();
        runtime.ledger.put(session.withEncounter(terminal, session.portalId(), session.deadlineAt()));
    }

    private GuidanceEncounterFixture() {}
}
