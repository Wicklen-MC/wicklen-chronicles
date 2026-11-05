package dev.redzumi.spirit.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.AABB;

/**
 * «Чувства» духа: срез состояния игрока и мира для промпта.
 * Одна компактная строка — не жжёт токены, но даёт живость.
 */
public final class SpiritSenses {
    private SpiritSenses() {}

    public static String describe(ServerPlayer player) {
        ServerLevel level = (ServerLevel) player.level();
        BlockPos pos = player.blockPosition();
        var sb = new StringBuilder();

        // тело
        sb.append(String.format("здоровье %.0f/%.0f", player.getHealth(), player.getMaxHealth()));
        int food = player.getFoodData().getFoodLevel();
        if (food <= 6) sb.append(", очень голоден");
        else if (food <= 12) sb.append(", проголодался");

        // где
        String biome = level.getBiome(pos).unwrapKey()
            .map(k -> k.identifier().getPath().replace('_', ' '))
            .orElse("неизвестно");
        String dim = level.dimension().identifier().getPath();
        sb.append(", биом ").append(biome);
        if (!dim.equals("overworld")) sb.append(" (").append(dim).append(")");
        if (pos.getY() < 0) sb.append(", глубоко под землёй (y=").append(pos.getY()).append(")");
        else if (pos.getY() < 40) sb.append(", в пещере (y=").append(pos.getY()).append(")");

        // время и небо
        if (dim.equals("overworld")) {
            long t = level.getOverworldClockTime() % 24000L;
            String phase = t < 1000 ? "утро" : t < 6000 ? "день" : t < 12000 ? "вечер"
                : t < 13000 ? "закат" : t < 23000 ? "ночь" : "предрассветье";
            sb.append(", ").append(phase);
            if (level.isThundering()) sb.append(", гроза");
            else if (level.isRaining()) sb.append(", дождь");
            int moon = (int) ((level.getOverworldClockTime() / 24000L) % 8L);
            if (moon == 0 && t >= 13000 && t < 23000) sb.append(", полнолуние");
        }

        // опасность рядом
        int hostiles = countHostiles(player);
        if (hostiles >= 5) sb.append(", вокруг много врагов (").append(hostiles).append(")");
        else if (hostiles > 0) sb.append(", рядом враги (").append(hostiles).append(")");

        // в руке — любопытная деталь
        var held = player.getMainHandItem();
        if (!held.isEmpty()) sb.append(", в руке ").append(held.getHoverName().getString());

        return sb.toString();
    }

    /** Полный контекст для промпта: чувства + питомцы + квест + слухи. */
    public static String context(ServerPlayer player) {
        var sb = new StringBuilder("Сейчас с игроком: ").append(describe(player)).append('.');
        var pets = SpiritMemorial.recent(player.getUUID(), 2);
        if (!pets.isEmpty()) sb.append(" Погибшие питомцы игрока (можно иногда тепло вспоминать): ")
            .append(String.join("; ", pets)).append('.');
        try {
            var quest = SpiritQuests.active(player.getUUID());
            if (quest != null) sb.append(" Активное задание, выданное тобой: ").append(quest.describe()).append('.');
        } catch (IllegalStateException unavailableQuest) {
            // Storage logs the preserved source once; dialogue can continue without this optional context.
        }
        var rumors = SpiritRumors.recent(3);
        if (!rumors.isEmpty()) sb.append(" Слухи на сервере: ").append(String.join(" ", rumors));
        return sb.toString();
    }

    /** Живых враждебных мобов рядом с игроком. */
    public static int countHostiles(ServerPlayer player) {
        return ((ServerLevel) player.level()).getEntities(
            net.minecraft.world.level.entity.EntityTypeTest.forClass(Monster.class),
            AABB.ofSize(player.position(), 32, 12, 32),
            e -> e.isAlive()).size();
    }

    /** Имя биома в позиции (для отслеживания смены). */
    public static String biomeName(ServerPlayer player) {
        ServerLevel level = (ServerLevel) player.level();
        return level.getBiome(player.blockPosition()).unwrapKey()
            .map(k -> k.identifier().toString()).orElse("unknown");
    }
}
