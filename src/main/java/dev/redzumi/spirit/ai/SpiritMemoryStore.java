package dev.redzumi.spirit.ai;

import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Память диалогов на диске: дух помнит разговоры прошлых сессий
 * (redzspirit/memory.json: uuid -> до 8 пар реплик, TTL 7 дней).
 * Сохранение — троттлится (30 c) и по остановке сервера.
 */
public final class SpiritMemoryStore {
    public record Turn(String user, String spirit) {}

    private record Entry(long touched, List<Turn> turns) {}

    private static final int MAX_TURNS = 8;
    private static final long TTL_MS = 7L * 24 * 3600 * 1000;
    private static final long SAVE_EVERY_MS = 30_000;

    private static final JsonStore<Map<String, Entry>> STORE =
        new JsonStore<>("memory.json", new TypeToken<>() {});
    private static volatile long lastSave;

    private SpiritMemoryStore() {}

    private static Map<String, Entry> mem() {
        return STORE.get(ConcurrentHashMap::new);
    }

    public static List<Turn> turns(UUID playerId) {
        Entry e = mem().get(playerId.toString());
        if (e == null) return List.of();
        if (System.currentTimeMillis() - e.touched() > TTL_MS) {
            mem().remove(playerId.toString());
            return List.of();
        }
        return e.turns();
    }

    public static void append(UUID playerId, String user, String spirit) {
        var turns = new ArrayList<>(mem().getOrDefault(playerId.toString(), new Entry(0, List.of())).turns());
        if (turns.size() >= MAX_TURNS) turns.remove(0);
        turns.add(new Turn(user, spirit));
        mem().put(playerId.toString(), new Entry(System.currentTimeMillis(), List.copyOf(turns)));
        saveThrottled();
    }

    /** Финальное сохранение — вешается на SERVER_STOPPING. */
    public static void flush() {
        STORE.save();
    }

    private static void saveThrottled() {
        long now = System.currentTimeMillis();
        if (now - lastSave < SAVE_EVERY_MS) return;
        lastSave = now;
        STORE.save();
    }
}
