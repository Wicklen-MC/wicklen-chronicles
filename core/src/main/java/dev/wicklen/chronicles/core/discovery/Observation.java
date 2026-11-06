package dev.wicklen.chronicles.core.discovery;

import java.util.Objects;

/** Server-confirmed observation. Visibility, tool use and range are verified by the platform. */
public record Observation(DiscoveryKey key, String title, String summary,
                          DiscoveryPosition position, long observedAt) {
    public Observation {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(position, "position");
        DiscoveryValues.text(title, "title", 160, false);
        DiscoveryValues.text(summary, "summary", 2048, true);
        if (observedAt < 0) throw new IllegalArgumentException("Negative observation time");
    }
}
