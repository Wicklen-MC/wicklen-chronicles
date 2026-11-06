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

    static UUID forceFirstMobEscape(ServerPlayer player,UUID encounterId,
            dev.wicklen.chronicles.station.PlaceManifest place) {
        try {
            var api=Class.forName("de.mrstilu2k6.gateway_of_doom.api.GatewayApi");
            var portals=(List<?>)api.getMethod("activeGatewaysBySourceId",String.class)
                .invoke(null,"wicklen_chronicles:encounter/"+encounterId);
            for(Object portal:portals) {
                var ids=(List<?>)portal.getClass().getMethod("trackedMobIds").invoke(portal);
                for(Object raw:ids) if(player.level().getEntity((UUID)raw) instanceof LivingEntity mob && mob.isAlive()) {
                    mob.teleportTo(place.arena().minX()-4.5,place.arena().minY(),place.anchor().z()+0.5);
                    return mob.getUUID();
                }
            }
            return null;
        } catch(ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    static void assertEffectiveZombie(ServerPlayer player,UUID mobId,
            dev.wicklen.chronicles.core.encounter.EncounterPlan plan) {
        var entity=player.level().getEntity(mobId);
        if(!(entity instanceof net.minecraft.world.entity.monster.zombie.Zombie zombie))
            throw new AssertionError("first authored wave must expose a real zombie for native attribute validation");
        double health=zombie.getMaxHealth();
        double damage=zombie.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
        if(Math.abs(health-20*plan.healthMultiplier())>0.11 || Math.abs(damage-3*plan.damageMultiplier())>0.11)
            throw new AssertionError("Gateway effective zombie attributes must match frozen gentle plan: health="+health
                +", expected="+(20*plan.healthMultiplier())+", damage="+damage+", expected="+(3*plan.damageMultiplier()));
        System.out.println("PASS: actual Gateway zombie attributes match authored plan HP="+health+" damage="+damage);
    }

    static boolean isAliveInside(ServerPlayer player,UUID mobId,
            dev.wicklen.chronicles.station.PlaceManifest place) {
        return player.level().getEntity(mobId) instanceof LivingEntity mob && mob.isAlive()
            && place.arena().contains(mob.blockPosition());
    }

    private GatewayWaveProbe() {}
}
