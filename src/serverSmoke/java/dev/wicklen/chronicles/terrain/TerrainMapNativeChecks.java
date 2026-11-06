package dev.wicklen.chronicles.terrain;

import dev.wicklen.chronicles.core.map.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.MapColor;
import java.util.*;

/** Isolated native geometry checks; setup loads only explicitly selected fixture chunks. */
public final class TerrainMapNativeChecks {
    public static void verify(MinecraftServer server) {
        var level = server.overworld();
        var absent = new MapCell("minecraft:overworld", 1_000_000, 1_000_000);
        require(level.getChunkSource().getChunkNow(absent.chunkX(), absent.chunkZ()) == null, "missing terrain fixture starts unloaded");
        require(!TerrainSampler.sampleColumn(level, new TerrainSampler.Job(absent, 1)), "sampler refuses missing chunks");
        require(level.getChunkSource().getChunkNow(absent.chunkX(), absent.chunkZ()) == null, "sampling never loads or generates a chunk");
        var chunk = level.getChunk(0, 0); // explicit native fixture setup
        int y = level.getMaxY() - 22; var old = new HashMap<BlockPos, net.minecraft.world.level.block.state.BlockState>();
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            var pos = new BlockPos(x, y, z); old.put(pos, level.getBlockState(pos));
            level.setBlock(pos, (x < 8 ? Blocks.GRASS_BLOCK : Blocks.WATER).defaultBlockState(), 3);
        }
        var canopy = new BlockPos(3, y + 3, 3); old.put(canopy, level.getBlockState(canopy));
        level.setBlock(canopy, Blocks.OAK_LEAVES.defaultBlockState(), 3);
        try {
            var job = new TerrainSampler.Job(new MapCell("minecraft:overworld", 0, 0), 1);
            for (int i = 0; i < 64; i++) require(TerrainSampler.sampleColumn(level, job), "native loaded terrain is sampled");
            var snapshot = job.finish();
            require((snapshot.color(6, 3) & 255) / 4 == (MapColor.WATER.getPackedId(MapColor.Brightness.NORMAL) & 255) / 4,
                    "native water appears blue rather than underground dirt");
            require(snapshot.color(0, 0) != snapshot.color(6, 3), "grass and water have real distinct block map colors");
            require(snapshot.color(1, 1) != snapshot.color(6, 3), "tree canopy is sampled from actual top blocks");
            System.out.println("PASS: native loaded-only terrain sampler, actual water/grass/canopy and no missing-chunk generation");
        } finally { old.forEach((pos, state) -> level.setBlock(pos, state, 3)); }
    }
    private static void require(boolean result, String message) { if (!result) throw new AssertionError(message); }
}
