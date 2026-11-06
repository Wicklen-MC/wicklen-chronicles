package dev.wicklen.chronicles.core.map;

/** One personal surface-survey cell. Platform adapters map it to a 16x16 block chunk. */
public record MapCell(String dimension, int chunkX, int chunkZ) {
    public static final int LIMIT = 1_875_000;
    public MapCell {
        if (dimension == null || dimension.length() > 128 || !dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                || Math.abs((long) chunkX) > LIMIT || Math.abs((long) chunkZ) > LIMIT)
            throw new IllegalArgumentException("Invalid map cell");
    }
    public int regionX() { return Math.floorDiv(chunkX, 16); }
    public int regionZ() { return Math.floorDiv(chunkZ, 16); }
    public int index() { return Math.floorMod(chunkZ, 16) * 16 + Math.floorMod(chunkX, 16); }
}
