package dev.wicklen.chronicles.core.encounter;

/** Only recent eligible fights count. Successes never automatically raise difficulty. */
public record RecentForm(int successes, int failures) {
    public static final RecentForm EMPTY = new RecentForm(0, 0);
    public RecentForm {
        if (successes < 0 || failures < 0 || successes > 64 || failures > 64 || successes + failures > 64)
            throw new IllegalArgumentException("Recent encounter history bounds");
    }
}
