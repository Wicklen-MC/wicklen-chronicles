package dev.wicklen.chronicles.exploration;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.structures.JungleTemplePiece;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Test-only foreign registered structure with real blocks and native piece metadata. */
public final class WorldStructureFixture implements AutoCloseable {
    public final ServerLevel level;
    public final BlockPos target;
    private final Structure structure;
    private final net.minecraft.world.level.chunk.LevelChunk origin;
    private final StructureStart previous;
    private final Map<BlockPos, BlockState> blocks = new HashMap<>();

    public WorldStructureFixture(ServerPlayer player) {
        level = (ServerLevel) player.level();
        target = player.blockPosition().offset(0, 1, 10);
        structure = level.registryAccess().lookupOrThrow(Registries.STRUCTURE)
                .getValue(Identifier.parse("wicklen_observer_test:foreign_ruin"));
        if (structure == null) throw new AssertionError("Foreign test structure must be registered by the test resource pack");
        origin = level.getChunk(target.getX() >> 4, target.getZ() >> 4); // explicit fixture setup
        previous = origin.getStartForStructure(structure);
        var piece = new JungleTemplePiece(RandomSource.create(9041), target.getX(), target.getZ());
        var bounds = piece.getBoundingBox();
        piece.move(target.getX() - 2 - bounds.minX(), target.getY() - 1 - bounds.minY(), target.getZ() - 2 - bounds.minZ());
        origin.setStartForStructure(structure, new StructureStart(structure, origin.getPos(), 0, new PiecesContainer(List.of(piece))));
        set(target, Blocks.STONE_BRICKS.defaultBlockState());
        set(target.above(), Blocks.STONE_BRICKS.defaultBlockState());
    }

    public void set(BlockPos position, BlockState state) {
        blocks.putIfAbsent(position.immutable(), level.getBlockState(position));
        level.setBlock(position, state, 3);
    }

    public static void verify(ServerPlayer player) {
        var before = ExplorationService.snapshot(player);
        try (var fixture = new WorldStructureFixture(player)) {
            var observer = new StructurePlaceObserver();
            var known = observer.observe(fixture.level, player, fixture.target, 100).orElseThrow(() ->
                    new AssertionError("Visible foreign registered structure must be observable"));
            var repeated = observer.observe(fixture.level, player, fixture.target.above(), 200).orElseThrow();
            require(known.key().equals(repeated.key()), "different visible blocks of one structure share its stable place key");
            require(known.title().contains("Foreign Ruin"), "foreign registry name has a readable fallback without a hardcoded mod API");
            fixture.set(fixture.target, Blocks.STONE.defaultBlockState());
            require(observer.observe(fixture.level, player, fixture.target, 300).isEmpty(), "ordinary natural stone inside a piece box is not a discovery");
            fixture.set(fixture.target, Blocks.STONE_BRICKS.defaultBlockState());
            require(observer.observe(fixture.level, player, fixture.target.offset(100, 0, 0), 400).isEmpty(), "nearby unrelated terrain never becomes the structure");
            require(ExplorationService.snapshot(player).equals(before), "inspection does not grant discoveries or reveal a hidden marker");
        }
    }

    @Override public void close() {
        for (var entry : blocks.entrySet()) level.setBlock(entry.getKey(), entry.getValue(), 3);
        if (previous == null) {
            var starts = new HashMap<>(origin.getAllStarts());
            starts.remove(structure);
            origin.setAllStarts(starts);
        } else origin.setStartForStructure(structure, previous);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
