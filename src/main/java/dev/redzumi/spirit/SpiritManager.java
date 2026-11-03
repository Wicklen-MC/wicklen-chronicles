package dev.redzumi.spirit;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import dev.redzumi.spirit.entity.SpiritEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * Один дух на игрока: призывается при входе (если ещё нет), удаляется при выходе.
 * Мир не наполняется брошенными духами, а игрок получает духа без команд.
 */
public final class SpiritManager {
    private SpiritManager() {}

    public static void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
            server.execute(() -> ensureSpirit(handler.getPlayer())));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
            server.execute(() -> discardSpirits(handler.getPlayer().getScoreboardName(), server)));
    }

    /** Все духи игрока (по всем мирам). */
    public static List<SpiritEntity> findSpirits(String ownerName, MinecraftServer server) {
        List<SpiritEntity> found = new ArrayList<>();
        var everywhere = new net.minecraft.world.phys.AABB(-1e7, -1e7, -1e7, 1e7, 1e7, 1e7);
        for (ServerLevel level : server.getAllLevels()) {
            found.addAll(level.getEntities(RedzSpirit.SPIRIT, everywhere, s -> ownerName.equals(s.ownerName())));
        }
        return found;
    }

    public static void ensureSpirit(ServerPlayer player) {
        if (dev.redzumi.spirit.ai.SpiritPrefs.isHidden(player.getUUID())) return; // игрок спрятал духа
        MinecraftServer server = player.level().getServer();
        if (!findSpirits(player.getScoreboardName(), server).isEmpty()) return;
        if (!(player.level() instanceof ServerLevel level)) return;
        SpiritEntity spirit = RedzSpirit.SPIRIT.create(level, EntitySpawnReason.MOB_SUMMONED);
        if (spirit == null) return;
        spirit.setOwner(player);
        spirit.setPos(player.getX() + 1.5, player.getY() + 1.6, player.getZ());
        level.addFreshEntity(spirit);
        SpiritBubbles.spiritId(player.getUUID(), spirit.getId());
        RedzSpirit.LOGGER.info("[redz-spirit] spirit spawned for {}", player.getScoreboardName());
    }

    public static void discardSpirits(String ownerName, MinecraftServer server) {
        int removed = 0;
        for (SpiritEntity s : findSpirits(ownerName, server)) {
            SpiritBubbles.spiritId(s.getOwnerUuid(), null);
            s.discard();
            removed++;
        }
        if (removed > 0) RedzSpirit.LOGGER.info("[redz-spirit] discarded {} spirit(s) of {}", removed, ownerName);
    }
}
