package dev.wicklen.chronicles.client;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Read-only optional API inspection; wave completion always comes from real player-attributed mob deaths. */
final class GatewayWaveProbe {
    static void defeatSpawnedMobs(ServerPlayer player, UUID encounterId,
                                  Set<Integer> waves, Set<UUID> defeated) {
        try {
            var api = Class.forName("de.mrstilu2k6.gateway_of_doom.api.GatewayApi");
            var portals = (List<?>) api.getMethod("activeGatewaysBySourceId", String.class)
                .invoke(null, "wicklen_chronicles:encounter/" + encounterId);
            for (Object portal : portals) {
                var waveIndex = portal.getClass().getDeclaredField("currentWave");
                waveIndex.setAccessible(true);
                var mobIds = (List<?>) portal.getClass().getMethod("trackedMobIds").invoke(portal);
                for (Object rawId : List.copyOf(mobIds)) {
                    UUID id = (UUID) rawId;
                    if (player.level().getEntity(id) instanceof LivingEntity mob && mob.isAlive()) {
                        waves.add(waveIndex.getInt(portal));
                        mob.hurtServer(player.level(), player.damageSources().playerAttack(player), 10_000);
                        if (!mob.isAlive()) defeated.add(id);
                    }
                }
            }
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Verified Gateway API changed during native GUI test", error);
        }
    }

    private GatewayWaveProbe() {}
}
