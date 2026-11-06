package dev.wicklen.chronicles.core.map;

import java.util.Arrays;
import java.util.Objects;

/** Immutable 8x8 map-palette snapshot; zero is reserved for fog, never a sampled pixel. */
public final class SurveyChunk {
    public static final int PIXELS = 64;
    private final MapCell cell;
    private final long firstVisitedAt;
    private final byte[] colors;
    public SurveyChunk(MapCell cell, long firstVisitedAt, byte[] colors) {
        this.cell = Objects.requireNonNull(cell);
        if (firstVisitedAt < 0 || colors == null || colors.length != PIXELS)
            throw new IllegalArgumentException("Invalid survey snapshot");
        for (byte color : colors) if ((color & 255) < 4) throw new IllegalArgumentException("Survey pixels must not contain fog");
        this.firstVisitedAt = firstVisitedAt;
        this.colors = colors.clone();
    }
    public MapCell cell() { return cell; }
    public long firstVisitedAt() { return firstVisitedAt; }
    public byte[] colors() { return colors.clone(); }
    public byte color(int x, int z) {
        if (x < 0 || z < 0 || x >= 8 || z >= 8) throw new IllegalArgumentException("Pixel outside cell");
        return colors[z * 8 + x];
    }
    @Override public boolean equals(Object value) {
        return value instanceof SurveyChunk other && cell.equals(other.cell) && firstVisitedAt == other.firstVisitedAt
                && Arrays.equals(colors, other.colors);
    }
    @Override public int hashCode() { return Objects.hash(cell, firstVisitedAt, Arrays.hashCode(colors)); }
}
