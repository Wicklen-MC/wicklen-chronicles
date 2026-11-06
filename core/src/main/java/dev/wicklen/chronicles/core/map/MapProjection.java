package dev.wicklen.chronicles.core.map;

import java.util.function.Function;

/** Unknown pixels are opaque fog in the renderer; no unsurveyed sample is ever requested. */
public final class MapProjection {
    public static byte[] pixels(String dimension, MapViewport viewport, Function<MapCell, SurveyChunk> lookup) {
        var pixels = new byte[MapViewport.SIZE * MapViewport.SIZE];
        var counts = new int[256];
        for (int z = 0; z < MapViewport.SIZE; z++) for (int x = 0; x < MapViewport.SIZE; x++) {
            int bx = viewport.minX() + x * viewport.step(), bz = viewport.minZ() + z * viewport.step();
            var cell = new MapCell(dimension, Math.floorDiv(bx, 16), Math.floorDiv(bz, 16));
            var chunk = lookup.apply(cell);
            if (chunk == null) continue;
            if (!chunk.cell().equals(cell)) throw new IllegalArgumentException("Foreign projected map cell");
            if (viewport.step() <= 2) { pixels[z * MapViewport.SIZE + x] = chunk.color(Math.floorMod(bx, 16) / 2, Math.floorMod(bz, 16) / 2); continue; }
            // Every zoom step divides 16 and the origin is step-aligned: a pixel never spans two cells.
            java.util.Arrays.fill(counts, 0); int color = 0, count = 0;
            for (int dz = 0; dz < viewport.step(); dz += 2) for (int dx = 0; dx < viewport.step(); dx += 2) {
                int candidate = chunk.color(Math.floorMod(bx + dx, 16) / 2, Math.floorMod(bz + dz, 16) / 2) & 255;
                if (++counts[candidate] > count) { color = candidate; count = counts[candidate]; }
            }
            pixels[z * MapViewport.SIZE + x] = (byte) color;
        }
        return pixels;
    }
    private MapProjection() {}
}
