package dev.wicklen.chronicles.client;

import java.util.ArrayList;
import java.util.List;

/** История окна диалога с духом (клиент, на сессию). */
public final class DialogHistory {
    public record Line(String title, int color, String text) {}

    private static final int MAX_LINES = 100;
    private static final List<Line> LINES = new ArrayList<>();

    private DialogHistory() {}

    public static synchronized void add(String title, int color, String text) {
        if (LINES.size() >= MAX_LINES) LINES.remove(0);
        LINES.add(new Line(title, color, text));
    }

    public static synchronized List<Line> lines() {
        return List.copyOf(LINES);
    }

    public static synchronized void clear() {
        LINES.clear();
    }
}
