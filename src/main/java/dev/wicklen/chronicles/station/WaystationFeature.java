package dev.wicklen.chronicles.station;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;

/** A small authored family placed by native new-chunk decoration, never by chunk-load retrofit. */
public record WaystationFeature() implements Feature {
    public static final MapCodec<WaystationFeature> CODEC = MapCodec.unit(new WaystationFeature());
    private static final TagKey<Block> FOUNDATION = TagKey.create(Registries.BLOCK,
            Identifier.fromNamespaceAndPath("wicklen_chronicles", "station_foundation"));

    @Override public MapCodec<? extends Feature> codec() { return CODEC; }

    @Override public boolean place(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin) {
        // Even /place feature cannot retrofit a live, already explored chunk.
        if (!(level instanceof WorldGenRegion region)) return false;
        int minX = Math.floorDiv(origin.getX(), 16) * 16;
        int minZ = Math.floorDiv(origin.getZ(), 16) * 16;
        if (region.getCenter().x() != minX >> 4 || region.getCenter().z() != minZ >> 4) return false;
        var chunk = region.getChunk(minX >> 4, minZ >> 4);
        if (chunk.hasAttached(StationAttachments.STATION)) return false;
        if (chunk.getAllStarts().values().stream().anyMatch(start -> start != null && start.isValid())
                || chunk.getAllReferences().values().stream().anyMatch(references -> !references.isEmpty())) return false;

        int[][] ground = new int[16][16];
        int minimum = Integer.MAX_VALUE;
        int maximum = Integer.MIN_VALUE;
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            int y = level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, minX + x, minZ + z) - 1;
            var pos = new BlockPos(minX + x, y, minZ + z);
            if (y < level.getMinY() + 2 || y + 7 >= level.getMaxY()
                    || !level.getBlockState(pos).is(FOUNDATION) || !level.getFluidState(pos.above()).isEmpty()
                    || !level.getBlockState(pos.below()).canOcclude() || !level.getBlockState(pos.below(2)).canOcclude()) return false;
            ground[x][z] = y;
            minimum = Math.min(minimum, y); maximum = Math.max(maximum, y);
            if (maximum - minimum > 3) return false;
        }
        // Check the whole write volume first; no partial station on rejection.
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = ground[x][z]; y <= maximum + 6; y++) {
            var pos = new BlockPos(minX + x, y, minZ + z);
            if (!region.ensureCanWrite(pos) || level.getBlockState(pos).hasBlockEntity()
                    || !level.getFluidState(pos).isEmpty()) return false;
        }
        var variants = StationVariant.values();
        var seed = new StationSeed(1, minX, maximum, minZ, random.nextInt(4), variants[random.nextInt(variants.length)]);
        build(level, random, seed, ground);
        chunk.setAttached(StationAttachments.STATION, seed);
        return true;
    }

    private static void build(WorldGenLevel level, RandomSource random, StationSeed seed, int[][] ground) {
        // The complete footprint stays inside this one generating chunk. Neighbouring old player builds are untouched.
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            int worldX = seed.minX() + x, worldZ = seed.minZ() + z;
            for (int y = ground[x][z] + 1; y < seed.floorY(); y++)
                level.setBlock(new BlockPos(worldX, y, worldZ), Blocks.COBBLESTONE.defaultBlockState(), 2);
            for (int y = 1; y <= 6; y++) put(level, seed, x, y, z, Blocks.AIR);
            Block floor;
            if (z >= 12 && x >= 1 && x <= 14) floor = Blocks.SPRUCE_PLANKS;
            else if (seed.variant() == StationVariant.UNDEAD_COURTYARD && x >= 2 && x <= 13 && z <= 11)
                floor = Blocks.STONE_BRICKS;
            else if ((x >= 6 && x <= 8) || z == 11) floor = random.nextBoolean() ? Blocks.COBBLESTONE : Blocks.MOSSY_COBBLESTONE;
            else floor = seed.variant() == StationVariant.OVERGROWN_RESEARCH && random.nextBoolean() ? Blocks.MOSS_BLOCK : Blocks.COARSE_DIRT;
            put(level, seed, x, 0, z, floor);
        }
        // Three small modules: archive / central goal / supplies. Two entrances remain open for choice and exit.
        room(level, random, seed, 1, 4, 2);
        room(level, random, seed, 5, 9, 7);
        room(level, random, seed, 10, 14, 12);
        put(level, seed, 2, 1, 14, Blocks.BOOKSHELF);
        put(level, seed, 6, 1, 14, Blocks.LECTERN);
        put(level, seed, 11, 1, 14, Blocks.CRAFTING_TABLE);
        for (int x : new int[]{1, 14}) {
            put(level, seed, x, 1, 2, Blocks.OAK_LOG);
            put(level, seed, x, 2, 2, Blocks.OAK_LOG);
            put(level, seed, x, 3, 2, Blocks.LANTERN);
        }
        if (seed.variant() == StationVariant.OVERGROWN_RESEARCH) {
            for (int x = 2; x <= 4; x++) for (int z = 3; z <= 7; z++) {
                if (random.nextInt(3) == 0) put(level, seed, x, 1, z, Blocks.MOSS_CARPET);
            }
            put(level, seed, 11, 1, 5, Blocks.COMPOSTER);
            put(level, seed, 12, 1, 5, Blocks.FLOWER_POT);
            loot(level, random, seed, 3, 14, "chests/village/village_cartographer");
        } else if (seed.variant() == StationVariant.RANSACKED_SUPPLIES) {
            put(level, seed, 3, 1, 5, Blocks.HAY_BLOCK);
            put(level, seed, 4, 1, 5, Blocks.OAK_SLAB);
            put(level, seed, 12, 1, 8, Blocks.COBBLESTONE_SLAB);
            put(level, seed, 14, 1, 13, Blocks.AIR);
            put(level, seed, 14, 2, 13, Blocks.AIR);
            loot(level, random, seed, 12, 14, "chests/village/village_weaponsmith");
        } else {
            // The entire rotated arena is flat and clear, including its centre anchor.
            loot(level, random, seed, 7, 14, "chests/simple_dungeon");
        }
    }

    private static void room(WorldGenLevel level, RandomSource random, StationSeed seed, int left, int right, int door) {
        for (int x = left; x <= right; x++) for (int z = 12; z <= 15; z++) {
            for (int y = 1; y <= 3; y++) {
                boolean wall = x == left || x == right || z == 12 || z == 15;
                if (!wall || (x == door && (z == 12 || z == 15) && y <= 2)) continue;
                put(level, seed, x, y, z, y == 1 ? Blocks.STONE_BRICKS : Blocks.OAK_PLANKS);
            }
            boolean broken = seed.variant() == StationVariant.RANSACKED_SUPPLIES && random.nextInt(3) == 0;
            if (!broken) put(level, seed, x, 4, z, Blocks.SPRUCE_SLAB);
        }
    }

    private static void loot(WorldGenLevel level, RandomSource random, StationSeed seed, int x, int z, String table) {
        var pos = seed.point(x, 1, z).blockPos();
        level.setBlock(pos, Blocks.BARREL.defaultBlockState(), 2);
        RandomizableContainer.setBlockEntityLootTable(level, random, pos,
                ResourceKey.create(Registries.LOOT_TABLE, Identifier.fromNamespaceAndPath("minecraft", table)));
    }

    private static void put(WorldGenLevel level, StationSeed seed, int x, int y, int z, Block block) {
        BlockState state = block.defaultBlockState();
        level.setBlock(seed.point(x, y, z).blockPos(), state, 2);
    }
}
