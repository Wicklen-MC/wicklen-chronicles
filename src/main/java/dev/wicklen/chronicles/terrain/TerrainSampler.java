package dev.wicklen.chronicles.terrain;

import dev.wicklen.chronicles.core.map.MapCell;
import dev.wicklen.chronicles.core.map.SurveyChunk;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

/** Server-thread-only, bounded surface sampler. It never requests a missing chunk or primes a heightmap. */
public final class TerrainSampler {
    public static final class Job {
        final MapCell cell;
        final long observedAt;
        final int[] heights = new int[64];
        final MapColor[] colors = new MapColor[64];
        int cursor;
        public Job(MapCell cell, long observedAt) { this.cell = cell; this.observedAt = observedAt; }
        public boolean complete() { return cursor == 64; }
        public MapCell cell() { return cell; }
        public SurveyChunk finish() {
            if (!complete()) throw new IllegalStateException("Incomplete terrain sample");
            var pixels = new byte[64];
            for (int z = 0; z < 8; z++) for (int x = 0; x < 8; x++) {
                int i = z * 8 + x;
                double slope = (heights[Math.min(7, z + 1) * 8 + x] - heights[Math.max(0, z - 1) * 8 + x]) * .6
                        + (heights[z * 8 + Math.min(7, x + 1)] - heights[z * 8 + Math.max(0, x - 1)]) * .3;
                var brightness = colors[i] == MapColor.WATER ? MapColor.Brightness.NORMAL
                        : slope > 1.2 ? MapColor.Brightness.HIGH : slope < -1.2 ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
                pixels[i] = colors[i].getPackedId(brightness);
            }
            return new SurveyChunk(cell, observedAt, pixels);
        }
    }
    /** Returns false if the chunk is absent; callers defer/drop, never generate it for a map. */
    public static boolean sampleColumn(ServerLevel level, Job job) {
        if (job.complete()) return true;
        var chunk = level.getChunkSource().getChunkNow(job.cell.chunkX(), job.cell.chunkZ());
        if (chunk == null || !chunk.hasPrimedHeightmap(Heightmap.Types.WORLD_SURFACE)) return false;
        int x = job.cell.chunkX() * 16 + job.cursor % 8 * 2 + 1;
        int z = job.cell.chunkZ() * 16 + job.cursor / 8 * 2 + 1;
        int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z); // 26.3 already returns top block Y
        MapColor color = MapColor.NONE;
        for (int depth = 0; depth < 8 && y >= level.getMinY(); depth++, y--) {
            var pos = new BlockPos(x, y, z);
            color = chunk.getBlockState(pos).getMapColor(chunk, pos);
            if (color != MapColor.NONE) break;
        }
        if (color == MapColor.NONE) color = MapColor.STONE;
        job.heights[job.cursor] = Math.max(level.getMinY(), y);
        job.colors[job.cursor++] = color;
        return true;
    }
    private TerrainSampler() {}
}
