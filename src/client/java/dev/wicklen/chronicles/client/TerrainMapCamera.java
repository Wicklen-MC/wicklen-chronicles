package dev.wicklen.chronicles.client;

import dev.wicklen.chronicles.core.map.MapViewport;

/** Continuous local presentation; only the bounded integer wire viewport is requested. */
final class TerrainMapCamera {
    record View(double centerX, double centerZ, int span) {
        double minX() { return centerX - span / 2.0; }
        double minZ() { return centerZ - span / 2.0; }
        double u(double x) { return (x - minX()) / span; }
        double v(double z) { return (z - minZ()) / span; }
        boolean contains(double x, double z) {
            return x >= minX() && z >= minZ() && x < minX() + span && z < minZ() + span;
        }
    }

    private double centerX;
    private double centerZ;
    private int span = 256;

    View view() { return new View(centerX, centerZ, span); }

    MapViewport wire() {
        return new MapViewport((int) Math.round(centerX), (int) Math.round(centerZ), span);
    }

    void focus(int x, int z, int requestedSpan) {
        var bounded = new MapViewport((int) clamp(x), (int) clamp(z), requestedSpan);
        span = bounded.span();
        centerX = bounded.centerX();
        centerZ = bounded.centerZ();
    }

    void moveTo(double x, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(z)) return;
        centerX = clamp(x);
        centerZ = clamp(z);
    }

    void pan(double dx, double dz) { moveTo(centerX + dx, centerZ + dz); }

    boolean zoom(boolean inward, double u, double v) {
        if (!Double.isFinite(u) || !Double.isFinite(v)) return false;
        int next = inward ? Math.max(128, span / 2) : Math.min(1024, span * 2);
        if (next == span) return false;
        double boundedU = Math.max(0, Math.min(1, u));
        double boundedV = Math.max(0, Math.min(1, v));
        double anchorX = view().minX() + boundedU * span;
        double anchorZ = view().minZ() + boundedV * span;
        span = next;
        moveTo(anchorX + (0.5 - boundedU) * span, anchorZ + (0.5 - boundedV) * span);
        return true;
    }

    private static double clamp(double value) {
        return Math.max(-MapViewport.BLOCK_LIMIT, Math.min(MapViewport.BLOCK_LIMIT, value));
    }
}
