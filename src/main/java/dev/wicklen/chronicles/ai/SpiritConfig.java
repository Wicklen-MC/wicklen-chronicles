package dev.wicklen.chronicles.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.wicklen.chronicles.WicklenChronicles;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Конфиг мода: config/wicklen-chronicles.json (создаётся с дефолтами при первом запуске).
 *
 * Ключи: строка "env:NAME" разрешается в переменную окружения (рекомендуется —
 * ключ не хранится в файле); литерал используется как есть.
 * env-переменные по-прежнему работают и без конфига (обратная совместимость).
 */
public final class SpiritConfig {
    public static final class ProviderCfg {
        public String baseUrl;
        public String model;
        public String apiKey; // "env:NAME" или литерал

        public ProviderCfg(String baseUrl, String model, String apiKey) {
            this.baseUrl = baseUrl;
            this.model = model;
            this.apiKey = apiKey;
        }
    }

    public String provider = "deepseek";

    public ProviderCfg deepseek = new ProviderCfg(
        "https://api.deepseek.com", "deepseek-flash", "env:DEEPSEEK_API_KEY");
    public ProviderCfg openai = new ProviderCfg(
        "https://api.openai.com/v1", "gpt-4o-mini", "env:OPENAI_API_KEY");
    public ProviderCfg ollama = new ProviderCfg(
        "http://127.0.0.1:11434", "qwen2.5:3b", null);
    public ProviderCfg custom = new ProviderCfg(
        null, null, "env:CUSTOM_API_KEY");

    // лимиты и поведение
    public long cooldownMs = 15_000;
    public int maxTurnsPerHour = 30;
    public int maxTokens = 150;
    public double temperature = 0.8;
    public boolean proactive = true;
    public int aiParallelRequests = 2;
    public int aiQueueCapacity = 16;
    public int aiRequestsPerHour = 120;
    public int aiOutputTokensPerHour = 100_000;
    // Select a bounded combat variation during preparation; equipment-based fallback is always immediate.
    public boolean encounterAiPlanning = true;
    public boolean firstLightAiNarration = true;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static SpiritConfig instance;

    private SpiritConfig() {}

    public static synchronized SpiritConfig get() {
        if (instance != null) return instance;
        Path file;
        try {
            file = dev.wicklen.chronicles.compat.LegacyCompatibility.configurationFile(
                FabricLoader.getInstance().getConfigDir());
        } catch (java.io.IOException error) {
            WicklenChronicles.LOGGER.error("[Wicklen: Chronicles] Legacy config migration failed; source preserved: {}", error.toString());
            instance = new SpiritConfig();
            return instance;
        }
        try {
            if (Files.exists(file)) {
                instance = GSON.fromJson(Files.readString(file), SpiritConfig.class);
                if (instance != null) {
                    instance.normalize();
                    return instance;
                }
            }
        } catch (Exception e) {
            WicklenChronicles.LOGGER.warn("[Wicklen: Chronicles] config load failed, using defaults: {}", e.toString());
        }
        instance = new SpiritConfig();
        if (Files.exists(file)) return instance; // keep malformed user config available for repair
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(instance));
            WicklenChronicles.LOGGER.info("[Wicklen: Chronicles] default config written to {}", file);
        } catch (Exception e) {
            WicklenChronicles.LOGGER.warn("[Wicklen: Chronicles] config write failed: {}", e.toString());
        }
        return instance;
    }

    public static synchronized void reset() { instance = null; }

    /** JSON may explicitly contain null or values outside provider limits. */
    public void normalize() {
        var defaults = new SpiritConfig();
        if (provider == null || provider.isBlank()) provider = defaults.provider;
        if (deepseek == null) deepseek = defaults.deepseek;
        if (openai == null) openai = defaults.openai;
        if (ollama == null) ollama = defaults.ollama;
        if (custom == null) custom = defaults.custom;
        restoreDefaults(deepseek, defaults.deepseek);
        restoreDefaults(openai, defaults.openai);
        restoreDefaults(ollama, defaults.ollama);
        cooldownMs = Math.clamp(cooldownMs, 0, 3_600_000);
        maxTurnsPerHour = Math.clamp(maxTurnsPerHour, 1, 1000);
        maxTokens = Math.clamp(maxTokens, 32, 2048);
        temperature = Double.isFinite(temperature) ? Math.clamp(temperature, 0, 2) : defaults.temperature;
        aiParallelRequests = Math.clamp(aiParallelRequests, 1, 8);
        aiQueueCapacity = Math.clamp(aiQueueCapacity, 0, 64);
        aiRequestsPerHour = Math.clamp(aiRequestsPerHour, 1, 10_000);
        aiOutputTokensPerHour = Math.clamp(aiOutputTokensPerHour, 2048, 10_000_000);
    }

    private static void restoreDefaults(ProviderCfg cfg, ProviderCfg defaults) {
        if (cfg.baseUrl == null || cfg.baseUrl.isBlank()) cfg.baseUrl = defaults.baseUrl;
        if (cfg.model == null || cfg.model.isBlank()) cfg.model = defaults.model;
    }

    /** "env:NAME" -> getenv(NAME); литерал -> как есть; пустое -> null. */
    public static String resolveSecret(String value) {
        if (value == null || value.isBlank()) return null;
        if (value.startsWith("env:")) {
            String secret = System.getenv(value.substring(4));
            return secret == null || secret.isBlank() ? null : secret;
        }
        return value;
    }

    public ProviderCfg providerCfg(String name) {
        return switch (name) {
            case "openai" -> openai;
            case "ollama" -> ollama;
            case "custom" -> custom;
            default -> deepseek;
        };
    }
}
