package dev.wicklen.chronicles.core.discovery;

import java.util.Objects;

/** A species/biome ID or a persistent individual place ID, never an ephemeral entity ID. */
public record DiscoveryKey(DiscoveryKind kind, String id) {
    public DiscoveryKey {
        Objects.requireNonNull(kind, "kind");
        DiscoveryValues.identifier(id, "discovery ID");
    }
}
