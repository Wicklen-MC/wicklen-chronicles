package dev.wicklen.chronicles.ai;

import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Дух-памятник: погибшие приручённые питомцы игроков (redzspirit/memorial.json).
 * Дух иногда тепло вспоминает их в разговоре.
 */
public final class SpiritMemorial {
    private static final int MAX_PER_PLAYER = 5;

    private static final JsonStore<Map<String, List<String>>> STORE =
        new JsonStore<>("memorial.json", new TypeToken<>() {});

    private SpiritMemorial() {}

    private static Map<String, List<String>> mem() {
        return STORE.get(ConcurrentHashMap::new);
    }

    public static void add(UUID playerId, String description) {
        var list = mem().computeIfAbsent(playerId.toString(), k -> new ArrayList<>());
        list.add(description);
        while (list.size() > MAX_PER_PLAYER) list.remove(0);
        STORE.save();
    }

    public static List<String> recent(UUID playerId, int n) {
        var list = mem().getOrDefault(playerId.toString(), List.of());
        return List.copyOf(list.subList(Math.max(0, list.size() - n), list.size()));
    }
}
