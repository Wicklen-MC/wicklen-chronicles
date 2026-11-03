package dev.redzumi.spirit.ai;

import dev.redzumi.spirit.RedzSpirit;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import dev.redzumi.spirit.ai.llm.LlmMessage;
import dev.redzumi.spirit.ai.llm.LlmProvider;
import dev.redzumi.spirit.ai.llm.LlmProviders;
import dev.redzumi.spirit.ai.llm.LlmRequest;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * LLM-слой духа: провайдер выбирается абстракцией LlmProviders
 * (SPIRIT_PROVIDER=deepseek|openai|ollama|custom; по умолчанию deepseek).
 * Правила безопасности: ключ только из env; cooldown и лимиты;
 * ИИ никогда не выполняет команды — только текст в чат.
 *
 * Живость: память диалога (последние реплики), чувства (HP/биом/время/враги),
 * слухи сервера (последние события от SpiritRumors).
 */
public final class SpiritAi {
    private static long cooldownMs() { return SpiritConfig.get().cooldownMs; }
    private static int maxTurnsPerHour() { return SpiritConfig.get().maxTurnsPerHour; }
    // память диалога — в SpiritMemoryStore (8 пар, 7 дней, на диске)

    /** «Дух спит», если ни один провайдер не настроен (нет ключей/явного выбора). */
    public static boolean enabled() {
        return LlmProviders.active() != null;
    }

    // --- состояние на игрока: троттлинг ---
    private static final class PlayerState {
        long lastCall;
        final ArrayDeque<Long> recentTurns = new ArrayDeque<>();
    }

    private static final Map<UUID, PlayerState> STATES = new ConcurrentHashMap<>();

    private static boolean allow(UUID id) {
        var st = STATES.computeIfAbsent(id, k -> new PlayerState());
        long now = System.currentTimeMillis();
        if (now - st.lastCall < cooldownMs()) return false;
        st.recentTurns.removeIf(t -> now - t > TimeUnit.HOURS.toMillis(1));
        return st.recentTurns.size() < maxTurnsPerHour();
    }

    private static void mark(UUID id) {
        var st = STATES.get(id);
        if (st == null) return;
        st.recentTurns.add(System.currentTimeMillis());
        st.lastCall = System.currentTimeMillis();
    }

    /** Память диалога — постоянное хранилище (переживает рестарты). */
    private static List<SpiritMemoryStore.Turn> memory(UUID id) {
        return SpiritMemoryStore.turns(id);
    }

    private static void remember(UUID id, String user, String spirit) {
        SpiritMemoryStore.append(id, user, spirit);
    }

    /** Диалог: @дух <текст>. Ответ — текст в чат от имени духа. */
    public static void chat(MinecraftServer server, ServerPlayer player, String message) {
        call(server, player, message, true);
    }

    /** Реплика по событию мира (ночь/рассвет/полнолуние/смерть). */
    public static void narrate(MinecraftServer server, ServerPlayer player, String prompt) {
        call(server, player, prompt, false);
    }

    private static void call(MinecraftServer server, ServerPlayer player, String message, boolean isChat) {
        if (!enabled()) {
            if (isChat) reply(player, SpiritPersonas.of(player.getUUID()), "…дух спит. (LLM-провайдер не настроен)");
            return;
        }
        var persona = SpiritPersonas.of(player.getUUID());
        callInternal(server, player, message, isChat, text -> reply(player, persona, text));
    }

    /** Диалог из окна: ответ уходит в окно (S2C) и облачком, не в чат. */
    public static void chatWindow(MinecraftServer server, ServerPlayer player, String message,
                                  java.util.function.Consumer<String> responder) {
        if (!enabled()) {
            responder.accept("…дух спит. (LLM-провайдер не настроен)");
            return;
        }
        callInternal(server, player, message, true, text -> {
            responder.accept(text);
            var persona = SpiritPersonas.of(player.getUUID());
            dev.redzumi.spirit.SpiritBubbles.show(player, persona.color(), text);
        });
    }

    private static void callInternal(MinecraftServer server, ServerPlayer player, String message,
                                     boolean isChat, java.util.function.Consumer<String> responder) {
        if (!allow(player.getUUID())) return; // молча троттлим
        var persona = SpiritPersonas.of(player.getUUID());

        String context = SpiritSenses.context(player);

        request(server, persona, player.getScoreboardName(), memory(player.getUUID()), context, message,
            language(player), 150, text -> {
                mark(player.getUUID());
                if (isChat) remember(player.getUUID(), message, text);
                responder.accept(text);
            }, isChat);
    }

    /** Летопись: раз в день глава из слухов (без игрока, длинный ответ). */
    public static void chronicle(MinecraftServer server, String prompt, java.util.function.Consumer<String> responder) {
        if (!enabled()) return;
        request(server, SpiritPersonas.ALL.get("owl"), "chronicle", List.of(), null, prompt,
            "русский", 700, responder, false);
    }

    /** Язык ответа — по клиентской локали игрока. */
    static String language(ServerPlayer player) {
        try {
            String lang = player.clientInformation().language();
            return switch (lang) {
                case "ru_ru", "ru_ru_ru", "be_by", "uk_ua" -> "русский";
                case "en_us", "en_gb" -> "английский";
                case "de_de" -> "немецкий";
                case "fr_fr" -> "французский";
                case "es_es", "es_mx" -> "испанский";
                case "pl_pl" -> "польский";
                case "kk_kz" -> "казахский";
                case "zh_cn" -> "китайский (упрощённый)";
                default -> "русский"; // сервер русскоязычный по умолчанию
            };
        } catch (Exception e) {
            return "русский";
        }
    }

    /** Тест из консоли: /spirit admin test <текст> — ответ в консоль/админу. */
    public static void adminTest(MinecraftServer server, java.util.function.Consumer<String> responder, String message) {
        if (!enabled()) {
            responder.accept("LLM disabled: провайдер не настроен (SPIRIT_PROVIDER/SPIRIT_API_KEY)");
            return;
        }
        request(server, SpiritPersonas.ALL.get("owl"), "console", List.of(), null, message, "русский", 150, responder, true);
    }

    private static void request(MinecraftServer server, SpiritPersonas.Persona persona, String playerName,
                                List<SpiritMemoryStore.Turn> history, String context, String message,
                                String language, int maxTokens,
                                java.util.function.Consumer<String> responder, boolean reportErrors) {

        LlmProvider provider = LlmProviders.active();
        if (provider == null) {
            if (reportErrors) responder.accept("…дух спит. (LLM-провайдер не настроен)");
            return;
        }

        String system = """
            Ты — дух-компаньон игрока в Minecraft: летающий светящийся светлячок.
            Твой характер: %s. Оставайся в характере во всех репликах.
            Отвечай КРАТКО (1–2 предложения), живо и тепло, от первого лица.
            Ты не даёшь точных рецептов и не выполняешь команды; не выдумывай факты о мире.
            Ты помнишь недавний разговор и чувствуешь, что происходит вокруг твоего игрока.
            Отвечай на языке: %s.
            Игрок: %s.""".formatted(persona.character(), language, playerName);
        if (context != null) system += "\n" + context;

        var messages = new ArrayList<LlmMessage>();
        for (var t : history) {
            messages.add(LlmMessage.user(t.user()));
            messages.add(LlmMessage.assistant(t.spirit()));
        }
        messages.add(LlmMessage.user(message));

        var cfg = SpiritConfig.get();
        provider.complete(new LlmRequest(system, messages, maxTokens, cfg.temperature))
            .whenComplete((content, err) -> server.execute(() -> {
                if (err != null) {
                    RedzSpirit.LOGGER.warn("LLM call failed ({}): {}", provider.id(), err.toString());
                    if (reportErrors) responder.accept("…дух улетел за мыслями и не вернулся.");
                    return;
                }
                RedzSpirit.LOGGER.info("[redz-spirit] answer via {} -> {}: {}", provider.id(), playerName, content);
                responder.accept(content);
            }));
    }

    private static void reply(ServerPlayer player, SpiritPersonas.Persona persona, String text) {
        player.sendSystemMessage(
            Component.literal("✦ " + persona.title() + ": ").withStyle(s -> s.withColor(persona.color()))
                .append(Component.literal(text)));
        dev.redzumi.spirit.SpiritBubbles.show(player, persona.color(), text);
    }

    private SpiritAi() {}
}
