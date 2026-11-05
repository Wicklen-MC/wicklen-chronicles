package dev.wicklen.chronicles.ai;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Слухи сервера: последние заметные события (смерти, приходы), общие для всех духов.
 * Маленькое кольцо в памяти — дух «слышал», что случилось у соседей.
 */
public final class SpiritRumors {
    private static final int MAX = 20;
    private static final long TTL_MS = 3_600_000; // час

    private record Rumor(long at, String text) {}

    private static final ArrayDeque<Rumor> RUMORS = new ArrayDeque<>();

    private SpiritRumors() {}
    public static synchronized void reset() { RUMORS.clear(); }

    public static synchronized void add(String text) {
        if (RUMORS.size() >= MAX) RUMORS.pollFirst();
        RUMORS.addLast(new Rumor(System.currentTimeMillis(), text));
    }

    /** Последние свежие слухи (новые первыми), до n штук. */
    public static synchronized List<String> recent(int n) {
        long now = System.currentTimeMillis();
        RUMORS.removeIf(r -> now - r.at() > TTL_MS);
        var out = new ArrayList<String>();
        var it = RUMORS.descendingIterator();
        while (it.hasNext() && out.size() < n) out.add(it.next().text());
        return out;
    }

    /** Метка «последний слух»: время добавления последнего (0, если слухов нет). */
    public static synchronized long stamp() {
        Rumor last = RUMORS.peekLast();
        return last == null ? 0 : last.at();
    }
}
