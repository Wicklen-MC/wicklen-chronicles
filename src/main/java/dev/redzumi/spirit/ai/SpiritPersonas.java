package dev.redzumi.spirit.ai;

import com.google.gson.reflect.TypeToken;
import dev.redzumi.spirit.RedzSpirit;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Персоны духа: характер -> промпт. Выбор на игрока, персистентно в redzspirit/personas.json. */
public final class SpiritPersonas {
    private SpiritPersonas() {}

    public record Persona(String id, String title, String character, String joinLine, int color) {}

    public static final Map<String, Persona> ALL = Map.of(
        "firefly", new Persona("firefly", "Светлячок", "робкий, влюблённый в свет и звёзды, чуть заикается от волнения",
            "✦ Светлячок-дух кружит рядом. Спроси: «@дух …»", 0xFFE97F),
        "fox", new Persona("fox", "Лиса", "хитрая плутовка, всё знает про слухи сервера, подтрунивает над игроком",
            "✦ Лисий дух крадётся рядом. Спроси: «@дух …»", 0xFFA040),
        "owl", new Persona("owl", "Сова", "мудрая и неспешная, говорит загадками и метафорами, любит луну",
            "✦ Совиный дух беззвучно сел на плечо. Спроси: «@дух …»", 0xB89FFF),
        "cat", new Persona("cat", "Кот", "ленивый саркастичный циник, втайне преданный",
            "✦ Кошачий дух потянулся и полетел за тобой. Спроси: «@дух …»", 0x9FE8FF));

    private static final JsonStore<Map<String, String>> STORE =
        new JsonStore<>("personas.json", new TypeToken<>() {});
    private static volatile long lastSave;

    public static Persona of(UUID playerId) {
        String id = STORE.get(ConcurrentHashMap::new).get(playerId.toString());
        Persona p = id == null ? null : ALL.get(id);
        return p != null ? p : ALL.get("firefly");
    }

    public static void set(UUID playerId, String personaId) {
        STORE.get(ConcurrentHashMap::new).put(playerId.toString(), personaId);
        STORE.save();
    }
}
