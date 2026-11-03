package dev.redzumi.spirit;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import dev.redzumi.spirit.ai.SpiritAi;
import dev.redzumi.spirit.ai.SpiritRumors;
import dev.redzumi.spirit.ai.SpiritSenses;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Проактивность: дух сам заговаривает по ситуации.
 * Раз в 10 c на игрока оцениваются триггеры; каждый триггер — со своим
 * cooldown'ом, сверху общий LLM-троттлинг SpiritAi. Без ключа всё молчит.
 */
public final class SpiritProactive {
    private static final long CHECK_EVERY_TICKS = 200;           // 10 c
    private static final long TRIGGER_COOLDOWN_MS = 10 * 60_000; // один триггер не чаще 10 мин
    private static final long BORED_COOLDOWN_MS = 25 * 60_000;
    private static final int BORED_CHANCE_PER_MILLE = 12;        // ~1 раз в 2 ч при 10 c шаге

    private record PlayerWatch(String biome, boolean deep, boolean thunder, long lastRumorSeen) {}

    private static final Map<UUID, PlayerWatch> WATCH = new ConcurrentHashMap<>();
    private static final Map<String, Long> LAST_FIRED = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> LAST_ADV = new ConcurrentHashMap<>();
    private static final java.util.Random RND = new java.util.Random();

    /** Сколько достижений (без рецептов) выполнил игрок. */
    private static int countAdvancements(MinecraftServer server, ServerPlayer player) {
        int done = 0;
        try {
            var pa = player.getAdvancements();
            for (var holder : server.getAdvancements().getAllAdvancements()) {
                if (holder.id().getPath().startsWith("recipes/")) continue;
                if (pa.getOrStartProgress(holder).isDone()) done++;
            }
        } catch (Exception e) {
            return LAST_ADV.getOrDefault(player.getUUID(), 0);
        }
        return done;
    }

    /** Ближайший спавнер в загруженных чанках (радиус 3): направление или null. */
    private static String scanSpawners(ServerLevel level, ServerPlayer player) {
        var pp = player.blockPosition();
        int pcx = pp.getX() >> 4, pcz = pp.getZ() >> 4;
        net.minecraft.core.BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                try {
                    var chunk = level.getChunk(pcx + dx, pcz + dz, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
                    if (chunk == null) continue;
                    for (var pos : chunk.getBlockEntitiesPos()) {
                        var be = level.getBlockEntity(pos);
                        if (be == null) continue;
                        var type = be.getType();
                        if (type != net.minecraft.world.level.block.entity.BlockEntityTypes.MOB_SPAWNER
                                && type != net.minecraft.world.level.block.entity.BlockEntityTypes.TRIAL_SPAWNER) continue;
                        double d = pos.distSqr(pp);
                        if (d < bestDist) { bestDist = d; best = pos; }
                    }
                } catch (Exception ignored) {}
            }
        }
        if (best == null) return null;
        double ddx = best.getX() - pp.getX(), ddz = best.getZ() - pp.getZ();
        String dir = Math.abs(ddx) > Math.abs(ddz) ? (ddx > 0 ? "к востоку" : "к западу")
                                                   : (ddz > 0 ? "к югу" : "к северу");
        return dir + (best.getY() < pp.getY() - 5 ? ", ниже тебя" : best.getY() > pp.getY() + 5 ? ", выше тебя" : "");
    }

    private SpiritProactive() {}

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(SpiritProactive::onTick);
    }

    private static void onTick(MinecraftServer server) {
        if (server.getTickCount() % CHECK_EVERY_TICKS != 0) return;
        if (!SpiritAi.enabled()) return;

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!(player.level() instanceof ServerLevel level)) continue;
            var watch = WATCH.computeIfAbsent(player.getUUID(),
                k -> new PlayerWatch(SpiritSenses.biomeName(player), player.getY() < 0,
                    level.isThundering(), SpiritRumors.stamp()));
            // в конце цикла WATCH обновится актуальными значениями (см. ниже)

            // --- опасность: мало HP и враги рядом ---
            int hostiles = SpiritSenses.countHostiles(player);
            if (player.getHealth() <= 6.0f && hostiles > 0
                    && ready(player, "danger", TRIGGER_COOLDOWN_MS)) {
                SpiritAi.narrate(server, player,
                    "У твоего игрока %.0f/%.0f здоровья и рядом %d врагов. Коротко предупреди его об опасности."
                        .formatted(player.getHealth(), player.getMaxHealth(), hostiles));
                continue; // одна реплика за проверку
            }

            // --- голод ---
            if (player.getFoodData().getFoodLevel() <= 3
                    && ready(player, "starving", TRIGGER_COOLDOWN_MS)) {
                SpiritAi.narrate(server, player,
                    "Твой игрок совсем голоден. Коротко и в своём характере посоветуй поесть.");
                continue;
            }

            // --- гроза началась ---
            boolean thunder = level.isThundering();
            if (thunder && !watch.thunder() && ready(player, "thunder", TRIGGER_COOLDOWN_MS)) {
                SpiritAi.narrate(server, player,
                    "Началась гроза. Одна короткая атмосферная реплика про грозу и молнии.");
                WATCH.put(player.getUUID(), new PlayerWatch(watch.biome(), watch.deep(), true, watch.lastRumorSeen()));
                continue;
            }

            // --- новый биом ---
            String biomeNow = SpiritSenses.biomeName(player);
            if (!biomeNow.equals(watch.biome()) && !biomeNow.equals("unknown")
                    && ready(player, "biome", TRIGGER_COOLDOWN_MS)) {
                String pretty = biomeNow.substring(biomeNow.indexOf(':') + 1).replace('_', ' ');
                SpiritAi.narrate(server, player,
                    "Игрок вошёл в новый биом: %s. Одна короткая живая реплика об этом месте.".formatted(pretty));
                continue; // WATCH обновится актуальными значениями в конце
            }

            // --- ушёл глубоко под землю ---
            boolean deep = player.getY() < 0;
            if (deep && !watch.deep() && ready(player, "deep", TRIGGER_COOLDOWN_MS)) {
                SpiritAi.narrate(server, player,
                    "Игрок спустился глубоко под землю (y=%d). Короткая реплика про глубину и темноту.".formatted((int) player.getY()));
                WATCH.put(player.getUUID(), new PlayerWatch(watch.biome(), true, watch.thunder(), watch.lastRumorSeen()));
                continue;
            }

            // --- свежий слух ---
            long stamp = SpiritRumors.stamp();
            if (stamp != watch.lastRumorSeen()
                    && ready(player, "rumor", TRIGGER_COOLDOWN_MS)) {
                var rumors = SpiritRumors.recent(1);
                if (!rumors.isEmpty()) {
                    SpiritAi.narrate(server, player,
                        "До тебя дошёл слух: «%s». Перескажи его игроку одной короткой фразой в своей манере."
                            .formatted(rumors.get(0)));
                    WATCH.put(player.getUUID(), new PlayerWatch(watch.biome(), watch.deep(), watch.thunder(), stamp));
                    continue;
                }
            }

            // --- скаут: чувствует спавнеры поблизости (редко) ---
            if (ready(player, "scout", 15 * 60_000)) {
                var found = scanSpawners(level, player);
                if (found != null) {
                    SpiritAi.narrate(server, player,
                        "Ты чувствуешь спавнер мобов %s от игрока (примерно y=%d). Одной короткой фразой поделись предчувствием, не выдавая точных координат."
                            .formatted(found, (int) player.getY()));
                    continue;
                }
            }

            // --- проводник: новые достижения игрока ---
            int advDone = countAdvancements(server, player);
            var lastAdv = LAST_ADV.getOrDefault(player.getUUID(), -1);
            if (lastAdv >= 0 && advDone > lastAdv
                    && ready(player, "guide", 5 * 60_000)) {
                SpiritAi.narrate(server, player,
                    "Игрок только что получил новое достижение (всего %d). Коротко поздравь и намекни, куда можно двигаться дальше."
                        .formatted(advDone));
            }
            LAST_ADV.put(player.getUUID(), advDone);

            // --- квесты: прогресс collect/visit ---
            dev.redzumi.spirit.ai.SpiritQuests.tickProgress(player);

            // --- скука: редкая спонтанная реплика ---
            if (RND.nextInt(1000) < BORED_CHANCE_PER_MILLE
                    && ready(player, "bored", BORED_COOLDOWN_MS)) {
                SpiritAi.narrate(server, player,
                    "Ничего особенного не происходит. Скажи одну короткую спонтанную фразу по ситуации вокруг игрока.");
            }

            // обновить слежение АКТУАЛЬНЫМИ значениями (ветки выше могли не отработать)
            WATCH.put(player.getUUID(), new PlayerWatch(
                biomeNow, player.getY() < 0, level.isThundering(), watch.lastRumorSeen()));
        }
    }

    /** true, если триггер у игрока вышел из cooldown'а (и отмечает срабатывание). */
    private static boolean ready(ServerPlayer player, String trigger, long cooldownMs) {
        String key = player.getUUID() + ":" + trigger;
        long now = System.currentTimeMillis();
        Long last = LAST_FIRED.get(key);
        if (last != null && now - last < cooldownMs) return false;
        LAST_FIRED.put(key, now);
        return true;
    }
}
