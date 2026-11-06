package dev.wicklen.chronicles.core.map;

/** A fixed-size texture covering a bounded, zoomable world window. */
public record MapViewport(int centerX, int centerZ, int span) {
    public static final int SIZE = 128;
    public static final int BLOCK_LIMIT = 29_999_000;
    public MapViewport {
        if (Math.abs((long) centerX) > BLOCK_LIMIT || Math.abs((long) centerZ) > BLOCK_LIMIT
                || span != 128 && span != 256 && span != 512 && span != 1024) throw new IllegalArgumentException("Map viewport bounds");
    }
    public int step() { return span / SIZE; }
    public int minX() { return Math.floorDiv(centerX - span / 2, step()) * step(); }
    public int minZ() { return Math.floorDiv(centerZ - span / 2, step()) * step(); }
    public boolean contains(double x, double z) { return x >= minX() && z >= minZ() && x < minX() + span && z < minZ() + span; }
    public double u(double x) { return (x - minX()) / span; }
    public double v(double z) { return (z - minZ()) / span; }
}
