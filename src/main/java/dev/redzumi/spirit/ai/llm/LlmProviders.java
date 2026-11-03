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
    private LlmProviders() {}

    /** Собрать доступные пресеты из конфига и окружения. */
    public static Map<String, LlmProvider> presets() {
        var cfg = SpiritConfig.get();
        var map = new LinkedHashMap<String, LlmProvider>();

        // DeepSeek (+ legacy SPIRIT_* env override)
        String deepseekKey = SpiritConfig.resolveSecret(cfg.deepseek.apiKey);
        if (deepseekKey == null) deepseekKey = env("SPIRIT_API_KEY", null);
        if (deepseekKey != null) {
            map.put("deepseek", new OpenAiCompatibleProvider("deepseek",
                env("SPIRIT_BASE_URL", cfg.deepseek.baseUrl),
                env("SPIRIT_MODEL", cfg.deepseek.model),
                deepseekKey));
        }

        // OpenAI
        String openaiKey = SpiritConfig.resolveSecret(cfg.openai.apiKey);
        if (openaiKey != null) {
            map.put("openai", new OpenAiCompatibleProvider("openai",
                cfg.openai.baseUrl, cfg.openai.model, openaiKey));
        }

        // Ollama: локальный без ключа — по явному выбору
        if ("ollama".equals(cfg.provider) || "ollama".equals(env("SPIRIT_PROVIDER", null))) {
            map.put("ollama", new OllamaProvider(cfg.ollama.baseUrl, cfg.ollama.model));
        }

        // произвольный OpenAI-совместимый endpoint
        if (cfg.custom.baseUrl != null && !cfg.custom.baseUrl.isBlank()) {
            map.put("custom", new OpenAiCompatibleProvider("custom",
                cfg.custom.baseUrl, cfg.custom.model, SpiritConfig.resolveSecret(cfg.custom.apiKey)));
        }

        return map;
    }

    /** Активный провайдер или null, если ни один не настроен. */
    public static LlmProvider active() {
        var presets = presets();
        String wanted = env("SPIRIT_PROVIDER", SpiritConfig.get().provider);
        var provider = presets.get(wanted);
        if (provider == null && !presets.isEmpty()) {
            provider = presets.values().iterator().next();
            RedzSpirit.LOGGER.warn("[redz-spirit] SPIRIT_PROVIDER={} недоступен, использую {}",
                wanted, provider.id());
        }
        return provider;
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? def : v;
    }
}
