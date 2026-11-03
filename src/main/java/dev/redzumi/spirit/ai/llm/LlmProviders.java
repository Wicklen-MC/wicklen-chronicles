package dev.redzumi.spirit.ai.llm;

import dev.redzumi.spirit.RedzSpirit;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Реестр провайдеров LLM. У каждого провайдера — свой namespace env-переменных
 * (<PROVIDER>_API_KEY / _MODEL / _BASE_URL); активный выбирается SPIRIT_PROVIDER.
 *
 * DeepSeek дополнительно принимает старые SPIRIT_API_KEY/SPIRIT_MODEL/SPIRIT_BASE_URL
 * как alias (обратная совместимость).
 *
 * Добавление нового провайдера: реализовать LlmProvider и вписать пресет ниже.
 */
public final class LlmProviders {
    private LlmProviders() {}

    /** Собрать доступные пресеты из окружения. */
    public static Map<String, LlmProvider> presets() {
        var map = new LinkedHashMap<String, LlmProvider>();

        // DeepSeek: DEEPSEEK_* (alias SPIRIT_*)
        String deepseekKey = env("DEEPSEEK_API_KEY", env("SPIRIT_API_KEY", null));
        if (deepseekKey != null) {
            map.put("deepseek", new OpenAiCompatibleProvider("deepseek",
                env("DEEPSEEK_BASE_URL", env("SPIRIT_BASE_URL", "https://api.deepseek.com")),
                env("DEEPSEEK_MODEL", env("SPIRIT_MODEL", "deepseek-flash")),
                deepseekKey));
        }

        // OpenAI: OPENAI_*
        String openaiKey = env("OPENAI_API_KEY", null);
        if (openaiKey != null) {
            map.put("openai", new OpenAiCompatibleProvider("openai",
                env("OPENAI_BASE_URL", "https://api.openai.com/v1"),
                env("OPENAI_MODEL", "gpt-4o-mini"),
                openaiKey));
        }

        // Ollama: локальный, без ключа; только по явному выбору или заданному OLLAMA_BASE_URL
        if ("ollama".equals(env("SPIRIT_PROVIDER", null)) || env("OLLAMA_BASE_URL", null) != null) {
            map.put("ollama", new OllamaProvider(
                env("OLLAMA_BASE_URL", "http://127.0.0.1:11434"),
                env("OLLAMA_MODEL", "qwen2.5:3b")));
        }

        // произвольный OpenAI-совместимый endpoint: CUSTOM_*
        String customUrl = env("CUSTOM_BASE_URL", null);
        if (customUrl != null) {
            map.put("custom", new OpenAiCompatibleProvider("custom",
                customUrl,
                env("CUSTOM_MODEL", "model"),
                env("CUSTOM_API_KEY", null)));
        }

        return map;
    }

    /** Активный провайдер или null, если ни один не настроен. */
    public static LlmProvider active() {
        var presets = presets();
        String wanted = env("SPIRIT_PROVIDER", "deepseek");
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
