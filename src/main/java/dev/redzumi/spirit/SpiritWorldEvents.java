package dev.redzumi.spirit;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityCombatEvents;
import dev.redzumi.spirit.ai.SpiritAi;

/**
 * Триггеры мира: реплики духа в момент ночи/рассвета/полнолуния и при смерти владельца.
 * Каждое событие — LLM-реплика, с глобальным cooldown'ом, чтобы не жечь токены.
 */
public final class SpiritWorldEvents {
    private static final long EVENT_COOLDOWN_MS = 60_000;

    private SpiritWorldEvents() {}

    private static boolean wasNight;
    private static long lastEventAt;
    private static String lastPhase = "";

    public static void reset() { wasNight = false; lastEventAt = 0; lastPhase = ""; }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(SpiritWorldEvents::onTick);
        registerCombat();

        // смерть игрока — слух для всех духов + личный комментарий погибшему
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity.level() instanceof ServerLevel level
                    && entity instanceof net.minecraft.world.entity.TamableAnimal pet && pet.isTame()) {
                var ownerRef = pet.getOwnerReference();
                var owner = ownerRef == null ? null : ownerRef.getEntity(level, LivingEntity.class);
                if (owner instanceof ServerPlayer player) {
                    String description = pet.getDisplayName().getString();
                    dev.redzumi.spirit.ai.SpiritMemorial.add(player.getUUID(), description);
                    dev.redzumi.spirit.ai.SpiritRumors.add(
                        "Слух: у " + player.getScoreboardName() + " погиб питомец (" + description + ").");
                }
            }
            if (entity instanceof ServerPlayer player && player.level() instanceof ServerLevel level) {
                String deathMsg = source.getLocalizedDeathMessage(player).getString();
                dev.redzumi.spirit.ai.SpiritRumors.add("Слух: " + deathMsg + ".");

                MinecraftServer server = level.getServer();
                if (!dev.redzumi.spirit.ai.SpiritConfig.get().proactive || !SpiritAi.enabled()) return;
                SpiritAi.narrate(server, player,
                    "Твой игрок (%s) только что погиб: %s. Скажи ему одну краткую фразу соболезнования с лёгкой надеждой."
                        .formatted(player.getScoreboardName(), deathMsg));
            }
        });
    }

    private static void registerCombat() {
        // погиб питомец → памятник + слух; игрок убил моба → прогресс slay-квеста
        ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY.register((world, killer, killed, source) -> {
            // игрок убил моба — прогресс квеста slay
            if (killer instanceof ServerPlayer sp) {
                String mobId = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                    .getKey(killed.getType()).toString();
                dev.redzumi.spirit.ai.SpiritQuests.onKill(sp, mobId);
            }
        });
    }

    private static void onTick(MinecraftServer server) {
        // проверяем раз в 100 тиков (5 c)
        if (server.getTickCount() % 100 != 0) return;
        if (!dev.redzumi.spirit.ai.SpiritConfig.get().proactive) return;
        for (ServerLevel level : server.getAllLevels()) {
            if (!level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) continue;

            boolean night = level.isDarkOutside();
            if (night != wasNight) {
                wasNight = night;
                if (cooldownPassed() && !level.players().isEmpty()) {
                    markEvent();
                    long day = level.getOverworldClockTime() / 24000L;
                    String prompt;
                    if (night && day % 5 == 4) {
                        prompt = "Наступила ночь, и ты чувствуешь тяжёлый воздух: возможно, сегодня ночь орды. Одной короткой мрачной фразой предупреди игрока.";
                    } else if (night) {
                        prompt = "Наступила ночь. Скажи одну тревожную короткую фразу: ночью опасно.";
                    } else {
                        prompt = "Наступило утро. Скажи одну короткую бодрую фразу о новом дне.";
                    }
                    for (ServerPlayer p : level.players()) {
                        SpiritAi.narrate(server, p, prompt);
                    }
                    // рассвет — летописец пишет главу дня
                    if (!night) {
                        writeChronicle(server, day);
                    }
                }
            }

            // полнолуние — раз за лунный цикл
            if (night && moonPhase(level) == 0 && !lastPhase.equals("full") && !level.players().isEmpty()
                    && cooldownPassed()) {
                lastPhase = "full";
                markEvent();
                for (ServerPlayer p : level.players()) {
                    SpiritAi.narrate(server, p,
                        "Сегодня полнолуние. Скажи одну короткую загадочную фразу про луну и духов.");
                }
            } else if (moonPhase(level) != 0) {
                lastPhase = "";
            }
            break; // обрабатываем только верхний мир
        }
    }

    /** Ночной летописец: глава из слухов дня в redzspirit/chronicle/. */
    private static void writeChronicle(MinecraftServer server, long day) {
        var rumors = dev.redzumi.spirit.ai.SpiritRumors.recent(20);
        if (rumors.isEmpty() || !SpiritAi.enabled()) return;
        String prompt = """
            Напиши короткую главу летописи сервера (3–5 предложений, красиво, как хроника)
            по событиям дня %d. События: %s
            Заголовок первой строкой в формате «# День %d — …».""".formatted(day, String.join(" ", rumors), day);
        SpiritAi.chronicle(server, prompt, chapter -> {
            try {
                var dir = dev.redzumi.spirit.ai.WorldStorageContext.directory().resolve("chronicle");
                java.nio.file.Files.createDirectories(dir);
                java.nio.file.Files.writeString(dir.resolve("day-" + day + ".md"), chapter + "\n");
                RedzSpirit.LOGGER.info("[redz-spirit] chronicle day {} written", day);
            } catch (Exception e) {
                RedzSpirit.LOGGER.warn("[redz-spirit] chronicle failed: {}", e.toString());
            }
        });
    }

    private static boolean cooldownPassed() {
        return System.currentTimeMillis() - lastEventAt > EVENT_COOLDOWN_MS;
    }

    private static void markEvent() {
        lastEventAt = System.currentTimeMillis();
    }

    private static int moonPhase(ServerLevel level) {
        return (int) ((level.getOverworldClockTime() / 24000L) % 8L);
    }
}
