package dev.redzumi.spirit.client;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Клиентское хранилище «облачков» над духами (entityId -> текст до истечения). */
public final class BubbleStore {
    public record Bubble(String text, int color, long expiresAt) {}

    private static final Map<Integer, Bubble> BUBBLES = new ConcurrentHashMap<>();

    private BubbleStore() {}

    public static void put(int entityId, String text, int color, long ttlMs) {
        BUBBLES.put(entityId, new Bubble(text, color, System.currentTimeMillis() + ttlMs));
    }

    /** Актуальное облачко или null. */
    public static Bubble get(int entityId) {
        Bubble b = BUBBLES.get(entityId);
        if (b == null) return null;
        if (System.currentTimeMillis() > b.expiresAt()) {
            BUBBLES.remove(entityId);
            return null;
        }
        return b;
    }

    public static void clear() {
        BUBBLES.clear();
    }
}
