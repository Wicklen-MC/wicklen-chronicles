package dev.redzumi.spirit.ai.llm;

import dev.redzumi.spirit.RedzSpirit;
import dev.redzumi.spirit.ai.SpiritConfig;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Реестр провайдеров LLM. Основной источник — config/redzspirit.json
 * (см. SpiritConfig); env-переменные работают как override/фолбэк.
 *
 * Приоритет для deepseek: конфиг (с "env:"-ссылками) → legacy env
 * (SPIRIT_API_KEY/SPIRIT_MODEL/SPIRIT_BASE_URL).
 */
public final class LlmProviders {
    private static Map<String, LlmProvider> cached;
    private static LlmProvider active;
    private static boolean resolved;
    private LlmProviders() {}

    /** Собрать доступные пресеты из конфига и окружения. */
    public static synchronized Map<String, LlmProvider> presets() {
        if (cached != null) return cached;
        var cfg = SpiritConfig.get();
        var map = new LinkedHashMap<String, LlmProvider>();

        // DeepSeek (+ legacy SPIRIT_* env override)
        String deepseekKey = env("DEEPSEEK_API_KEY", SpiritConfig.resolveSecret(cfg.deepseek.apiKey));
        if (deepseekKey == null) deepseekKey = env("SPIRIT_API_KEY", null);
        if (deepseekKey != null) {
            map.put("deepseek", new OpenAiCompatibleProvider("deepseek",
                env("DEEPSEEK_BASE_URL", env("SPIRIT_BASE_URL", cfg.deepseek.baseUrl)),
                env("DEEPSEEK_MODEL", env("SPIRIT_MODEL", cfg.deepseek.model)),
                deepseekKey));
        }

        // OpenAI
        String openaiKey = env("OPENAI_API_KEY", SpiritConfig.resolveSecret(cfg.openai.apiKey));
        if (openaiKey != null) {
            map.put("openai", new OpenAiCompatibleProvider("openai",
                env("OPENAI_BASE_URL", cfg.openai.baseUrl), env("OPENAI_MODEL", cfg.openai.model), openaiKey));
        }

        // Ollama: локальный без ключа — по явному выбору
        if ("ollama".equals(cfg.provider) || "ollama".equals(env("SPIRIT_PROVIDER", null))) {
            map.put("ollama", new OllamaProvider(env("OLLAMA_BASE_URL", cfg.ollama.baseUrl), env("OLLAMA_MODEL", cfg.ollama.model)));
        }

        // произвольный OpenAI-совместимый endpoint
        String customUrl = env("CUSTOM_BASE_URL", cfg.custom.baseUrl);
        String customModel = env("CUSTOM_MODEL", cfg.custom.model);
        if (customUrl != null && !customUrl.isBlank() && customModel != null && !customModel.isBlank()) {
            map.put("custom", new OpenAiCompatibleProvider("custom",
                customUrl, customModel, env("CUSTOM_API_KEY", SpiritConfig.resolveSecret(cfg.custom.apiKey))));
        }

        cached = java.util.Collections.unmodifiableMap(map);
        return cached;
    }

    /** Активный провайдер или null, если ни один не настроен. */
    public static synchronized LlmProvider active() {
        if (resolved) return active;
        var presets = presets();
        String wanted = env("SPIRIT_PROVIDER", SpiritConfig.get().provider);
        var provider = presets.get(wanted);
        if (provider == null && !presets.isEmpty()) {
            provider = presets.values().iterator().next();
            RedzSpirit.LOGGER.warn("[redz-spirit] SPIRIT_PROVIDER={} недоступен, использую {}",
                wanted, provider.id());
        }
        active = provider;
        resolved = true;
        return active;
    }

    public static synchronized void reset() {
        cached = null;
        active = null;
        resolved = false;
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? def : v;
    }
}
