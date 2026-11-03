package dev.redzumi.spirit.ai;

import com.google.gson.reflect.TypeToken;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Предпочтения игроков: «дух спрятан». Персистентно (redzspirit/prefs.json). */
public final class SpiritPrefs {
    private static final JsonStore<Set<String>> STORE =
        new JsonStore<>("prefs.json", new TypeToken<>() {});

    private SpiritPrefs() {}

    private static Set<String> hidden() {
        return STORE.get(ConcurrentHashMap::newKeySet);
    }

    public static boolean isHidden(UUID playerId) {
        return hidden().contains(playerId.toString());
    }

    public static void setHidden(UUID playerId, boolean hidden) {
        var set = hidden();
        if (hidden) set.add(playerId.toString()); else set.remove(playerId.toString());
        STORE.save();
    }
}
