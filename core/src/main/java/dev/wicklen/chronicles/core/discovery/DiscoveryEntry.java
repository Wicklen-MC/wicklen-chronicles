package dev.wicklen.chronicles.core.discovery;

import java.util.Objects;

/** First-known personal facts. A null reward means this observation has no configured payment. */
public record DiscoveryEntry(DiscoveryKey key, String title, String summary,
                             DiscoveryPosition position, long firstObservedAt, DiscoveryReward reward) {
    public DiscoveryEntry {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(position, "position");
        DiscoveryValues.text(title, "title", 160, false);
        DiscoveryValues.text(summary, "summary", 2048, true);
        if (firstObservedAt < 0) throw new IllegalArgumentException("Negative observation time");
    }
}
