package dev.wicklen.chronicles.ai;

import dev.wicklen.chronicles.WicklenChronicles;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import dev.wicklen.chronicles.ai.llm.LlmMessage;
import dev.wicklen.chronicles.ai.llm.LlmProvider;
import dev.wicklen.chronicles.ai.llm.LlmProviders;
import dev.wicklen.chronicles.ai.llm.LlmRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

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

    private static final RequestLimiter LIMITER = new RequestLimiter();
    public static void reset() { LIMITER.clear(); }

    public static boolean canNarrate(ServerPlayer player) {
        return enabled() && LIMITER.check(player.getUUID(), System.currentTimeMillis(), cooldownMs(), maxTurnsPerHour())
            == RequestLimiter.Result.ACCEPTED;
    }

    /** Память диалога — постоянное хранилище (переживает рестарты). */
    private static List<SpiritMemoryStore.Turn> memory(UUID id) {
        return SpiritMemoryStore.turns(id);
    }

    private static void remember(UUID id, String user, String spirit) {
        SpiritMemoryStore.append(id, user, spirit);
    }

    /** Dialogue: {@code @spirit <message>}. The spirit replies in chat. */
    public static void chat(MinecraftServer server, ServerPlayer player, String message) {
        call(server, player, message, true);
    }

    /** Реплика по событию мира (ночь/рассвет/полнолуние/смерть). */
    public static void narrate(MinecraftServer server, ServerPlayer player, String prompt) {
        call(server, player, prompt, false);
    }

    private static void call(MinecraftServer server, ServerPlayer player, String message, boolean isChat) {
        if (!enabled()) {
            if (isChat) reply(player, "…дух спит. (LLM-провайдер не настроен)");
            return;
        }
        callInternal(server, player, message, isChat, text -> reply(player, text));
    }

    /** Диалог из окна: ответ уходит в окно (S2C) и облачком, не в чат. */
    public static void chatWindow(MinecraftServer server, ServerPlayer player, String message,
                                  java.util.function.Consumer<String> responder) {
        java.util.function.Consumer<String> deliver = text -> {
            responder.accept(text);
            var persona = SpiritPersonas.of(player.getUUID());
            dev.wicklen.chronicles.SpiritBubbles.show(player, persona.color(), text);
        };
        if (!enabled()) {
            deliver.accept("…дух спит. (LLM-провайдер не настроен)");
            return;
        }
        callInternal(server, player, message, true, deliver);
    }

    private static void callInternal(MinecraftServer server, ServerPlayer player, String message,
                                     boolean isChat, java.util.function.Consumer<String> responder) {
        UUID playerId = player.getUUID();
        var result = LIMITER.acquire(playerId, System.currentTimeMillis(), cooldownMs(), maxTurnsPerHour());
        if (result != RequestLimiter.Result.ACCEPTED) {
            if (isChat) responder.accept(switch (result) {
                case BUSY -> "Я ещё обдумываю предыдущий вопрос. Подожди немного…";
                case COOLDOWN -> "Дай мне несколько секунд собраться с мыслями…";
                default -> "Я устал. Давай продолжим чуть позже — лимит разговоров за час исчерпан.";
            });
            return;
        }
        var persona = SpiritPersonas.of(player.getUUID());

        try {
            String context = SpiritSenses.context(player);
            request(server, persona, player.getScoreboardName(), memory(playerId), context, message,
                language(player), SpiritConfig.get().maxTokens, text -> {
                    if (isChat) remember(playerId, message, text);
                    responder.accept(text);
                }, isChat ? responder : ignored -> {}, () -> LIMITER.complete(playerId),
                () -> {
                    var current = server.getPlayerList().getPlayer(playerId);
                    return current != null && current.connection == player.connection;
                });
        } catch (RuntimeException error) {
            LIMITER.complete(playerId);
            WicklenChronicles.LOGGER.warn("[Wicklen: Chronicles] context failed: {}", error.toString());
            if (isChat) responder.accept("Не могу собраться с мыслями. Попробуй чуть позже.");
        }
    }

    /** Летопись: раз в день глава из слухов (без игрока, длинный ответ). */
    public static void chronicle(MinecraftServer server, String prompt, java.util.function.Consumer<String> responder) {
        if (!enabled()) return;
        request(server, SpiritPersonas.ALL.get("owl"), "chronicle", List.of(), null, prompt,
            "русский", 700, responder, ignored -> {}, () -> {}, () -> true);
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
        request(server, SpiritPersonas.ALL.get("owl"), "console", List.of(), null, message, "русский",
            SpiritConfig.get().maxTokens, responder, responder, () -> {}, () -> true);
    }

    private static void request(MinecraftServer server, SpiritPersonas.Persona persona, String playerName,
                                List<SpiritMemoryStore.Turn> history, String context, String message,
                                String language, int maxTokens,
                                java.util.function.Consumer<String> responder, java.util.function.Consumer<String> errors,
                                Runnable finished, java.util.function.BooleanSupplier recipientOnline) {

        // A late response from a closed world must never touch another world's stores.
        UUID sessionId = WorldStorageContext.sessionId();
        if (!WorldStorageContext.matchesSession(sessionId)) {
            finished.run();
            return;
        }

        LlmProvider provider = LlmProviders.active();
        if (provider == null) {
            finished.run();
            errors.accept("…дух спит. (LLM-провайдер не настроен)");
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
        CompletableFuture<String> future;
        try {
            future = provider.complete(new LlmRequest(system, messages, maxTokens, cfg.temperature));
        } catch (RuntimeException error) {
            future = CompletableFuture.failedFuture(error);
        }
        future
            .whenComplete((content, err) -> server.execute(() -> {
                try {
                    if (!WorldStorageContext.matchesSession(sessionId)) return;
                    if (!recipientOnline.getAsBoolean()) return;
                    if (err != null || content == null || content.isBlank()) {
                        WicklenChronicles.LOGGER.warn("LLM call failed ({}): {}", provider.id(),
                            err == null ? "empty response" : err.toString());
                        errors.accept("…дух улетел за мыслями и не вернулся.");
                        return;
                    }
                    String text = content.length() > 2000 ? content.substring(0, 2000) + "…" : content;
                    WicklenChronicles.LOGGER.info("[Wicklen: Chronicles] answer via {} -> {}: {}", provider.id(), playerName, text);
                    responder.accept(text);
                } finally {
                    // A prior session must not release a new session's in-flight request.
                    if (WorldStorageContext.matchesSession(sessionId)) finished.run();
                }
            }));
    }

    private static void reply(ServerPlayer player, String text) {
        dev.wicklen.chronicles.SpiritBubbles.speak(player, text);
    }

    private SpiritAi() {}
}
