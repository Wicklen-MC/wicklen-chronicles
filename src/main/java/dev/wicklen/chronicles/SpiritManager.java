package dev.wicklen.chronicles;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import dev.wicklen.chronicles.entity.SpiritEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

/**
 * Один дух на игрока: призывается при входе (если ещё нет), удаляется при выходе.
 * Мир не наполняется брошенными духами, а игрок получает духа без команд.
 */
public final class SpiritManager {
    private static final Map<UUID, SpiritEntity> ACTIVE = new HashMap<>();
    private static final Set<UUID> JOINING = new HashSet<>();
    private SpiritManager() {}

    public static void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
            JOINING.add(handler.getPlayer().getUUID()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            JOINING.remove(handler.getPlayer().getUUID());
            server.execute(() -> discardSpirits(handler.getPlayer().getScoreboardName(), server));
        });
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (!(entity instanceof SpiritEntity spirit)) return;
            var id = spirit.getOwnerUuid();
            var player = id == null ? null : level.getServer().getPlayerList().getPlayer(id);
            if (player == null || player.level() != level || dev.wicklen.chronicles.ai.SpiritPrefs.isHidden(id)) {
                spirit.discard();
                return;
            }
            var previous = ACTIVE.get(id);
            if (previous != null && previous != spirit && !previous.isRemoved()) {
                spirit.discard();
                return;
            }
            track(player, spirit);
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
            if (!(entity instanceof SpiritEntity spirit)) return;
            UUID id = spirit.getOwnerUuid();
            if (id != null && ACTIVE.remove(id, spirit)) SpiritBubbles.spiritId(id, null);
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            // JOIN runs before PlayerList contains the player. Wait for the end of the tick
            // so ENTITY_LOAD and the first bubble agree on the same live companion.
            for (UUID id : List.copyOf(JOINING)) {
                var player = server.getPlayerList().getPlayer(id);
                if (player == null) continue;
                JOINING.remove(id);
                ensureSpirit(player);
                if (!dev.wicklen.chronicles.ai.SpiritPrefs.isHidden(id)) {
                    SpiritBubbles.speak(player, dev.wicklen.chronicles.ai.SpiritPersonas.of(id).joinLine());
                }
                dev.wicklen.chronicles.ai.SpiritRumors.add("Слух: " + player.getScoreboardName() + " вернулся в мир.");
            }
            if (server.getTickCount() % 40 != 0) return;
            for (var player : server.getPlayerList().getPlayers()) ensureSpirit(player);
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            ACTIVE.clear();
            JOINING.clear();
            SpiritBubbles.clear();
        });
    }

    /** Все духи игрока (по всем мирам). */
    public static List<SpiritEntity> findSpirits(String ownerName, MinecraftServer server) {
        List<SpiritEntity> found = new ArrayList<>();
        var everywhere = new net.minecraft.world.phys.AABB(-1e7, -1e7, -1e7, 1e7, 1e7, 1e7);
        for (ServerLevel level : server.getAllLevels()) {
            for (var type : dev.wicklen.chronicles.compat.LegacyCompatibility.spiritTypes())
                found.addAll(level.getEntities(type, everywhere, s -> ownerName.equals(s.ownerName())));
        }
        return found;
    }

    public static void ensureSpirit(ServerPlayer player) {
        if (dev.wicklen.chronicles.ai.SpiritPrefs.isHidden(player.getUUID())) return; // игрок спрятал духа
        MinecraftServer server = player.level().getServer();
        var current = ACTIVE.get(player.getUUID());
        if (current != null && !current.isRemoved() && current.level() == player.level()) return;
        if (current != null) current.discard();
        current = null;
        for (var existing : findSpirits(player.getScoreboardName(), server)) {
            if (existing.level() == player.level() && current == null) {
                track(player, existing);
                current = existing;
            } else existing.discard();
        }
        if (current != null && !current.isRemoved() && current.level() == player.level()) return;
        if (!(player.level() instanceof ServerLevel level)) return;
        SpiritEntity spirit = WicklenChronicles.SPIRIT.create(level, EntitySpawnReason.MOB_SUMMONED);
        if (spirit == null) return;
        spirit.setOwner(player);
        spirit.setPos(player.getX() + SpiritEntity.ORBIT_RADIUS, player.getY() + 1.6, player.getZ());
        if (!level.addFreshEntity(spirit)) return;
        track(player, spirit);
        WicklenChronicles.LOGGER.info("[Wicklen: Chronicles] spirit spawned for {}", player.getScoreboardName());
    }

    private static void track(ServerPlayer player, SpiritEntity spirit) {
        spirit.setOwner(player);
        spirit.setSpiritColor(dev.wicklen.chronicles.ai.SpiritPersonas.of(player.getUUID()).color());
        ACTIVE.put(player.getUUID(), spirit);
        SpiritBubbles.spiritId(player.getUUID(), spirit.getId());
    }

    public static void refreshAppearance(ServerPlayer player) {
        var spirit = ACTIVE.get(player.getUUID());
        if (spirit != null) track(player, spirit);
    }

    public static int discardSpirits(String ownerName, MinecraftServer server) {
        int removed = 0;
        for (SpiritEntity s : findSpirits(ownerName, server)) {
            UUID id = s.getOwnerUuid();
            if (id != null) {
                SpiritBubbles.spiritId(id, null);
                ACTIVE.remove(id, s);
            }
            s.discard();
            removed++;
        }
        if (removed > 0) WicklenChronicles.LOGGER.info("[Wicklen: Chronicles] discarded {} spirit(s) of {}", removed, ownerName);
        return removed;
    }
}
