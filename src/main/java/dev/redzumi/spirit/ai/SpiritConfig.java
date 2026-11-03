package dev.redzumi.spirit.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.redzumi.spirit.RedzSpirit;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Конфиг мода: config/redzspirit.json (создаётся с дефолтами при первом запуске).
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

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static SpiritConfig instance;

    private SpiritConfig() {}

    public static synchronized SpiritConfig get() {
        if (instance != null) return instance;
        Path file = file();
        try {
            if (Files.exists(file)) {
                instance = GSON.fromJson(Files.readString(file), SpiritConfig.class);
                if (instance != null) return instance;
            }
        } catch (Exception e) {
            RedzSpirit.LOGGER.warn("[redz-spirit] config load failed, using defaults: {}", e.toString());
        }
        instance = new SpiritConfig();
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(instance));
            RedzSpirit.LOGGER.info("[redz-spirit] default config written to {}", file);
        } catch (Exception e) {
            RedzSpirit.LOGGER.warn("[redz-spirit] config write failed: {}", e.toString());
        }
        return instance;
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("redzspirit.json");
    }

    /** "env:NAME" -> getenv(NAME); литерал -> как есть; пустое -> null. */
    public static String resolveSecret(String value) {
        if (value == null || value.isBlank()) return null;
        if (value.startsWith("env:")) return System.getenv(value.substring(4));
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
