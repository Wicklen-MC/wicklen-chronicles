package dev.wicklen.chronicles.core.discovery;

/** Plain values; coordinates reach a client only through that player's known entries. */
public record DiscoveryPosition(String dimension, int x, int y, int z) {
    public DiscoveryPosition {
        DiscoveryValues.identifier(dimension, "dimension ID");
    }
}
