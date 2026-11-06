package dev.wicklen.chronicles.exploration;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.structures.JungleTemplePiece;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;

/** Run only in the explicitly isolated native server smoke world. */
public final class ExplorationNativeChecks {
    public static void prepare(MinecraftServer server) {
        server.overworld().setChunkForced(0, 0, true);
        server.overworld().getChunk(0, 0);
    }

    public static void verify(MinecraftServer server) {
        var level = server.overworld();
        level.getChunk(0, 0); // explicit test setup; observation itself must never generate/load chunks
        var player = new ServerPlayer(server, level, new GameProfile(UUID.randomUUID(), "DiscoveryProbe"), ClientInformation.createDefault());
        int y = level.getMaxY() - 20;
        player.setPos(4.5, y, 4.5);
        player.setYRot(0);
        player.setYHeadRot(0);
        player.setXRot(0);
        var target = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.COMMAND);
        require(target != null, "test entity must exist");
        target.setNoAi(true);
        target.setNoGravity(true);
        target.snapTo(4.5, y, 12.5);
        level.addFreshEntity(target);
        BlockPos wall = new BlockPos(4, y + 1, 8);
        var before = level.getBlockState(wall);
        try {
            require(ExplorationService.inspectTarget(player) == null, "no spyglass means no observation");
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.SPYGLASS));
            require(ExplorationService.inspectTarget(player) == null, "holding a spyglass is insufficient");
            player.startUsingItem(InteractionHand.MAIN_HAND);
            var visible = ExplorationService.inspectTarget(player);
            require(visible != null && visible.key().id().equals("minecraft:zombie"), "server ray picks a visible creature; using="
                    + player.isUsingItem() + ", item=" + player.getUseItem() + ", chunk="
                    + level.getChunkSource().getChunkNow(0, 0) + ", entities="
                    + level.getEntities(player, target.getBoundingBox().inflate(2), entity -> true).size()
                    + ", eye=" + player.getEyePosition() + ", view=" + player.getViewVector(1.0f));
            level.setBlock(wall, Blocks.STONE.defaultBlockState(), 3);
            require(ExplorationService.inspectTarget(player) == null, "wall blocks observation");
            level.setBlock(wall, Blocks.AIR.defaultBlockState(), 3);
            target.setInvisible(true);
            require(ExplorationService.inspectTarget(player) == null, "invisible creature is not recorded");
            target.setInvisible(false);
            target.snapTo(4.5, y, 120.5);
            require(ExplorationService.inspectTarget(player) == null, "out-of-range target is not recorded");
            player.stopUsingItem();
            require(ExplorationService.inspectTarget(player) == null, "release ends observation");
            verifyPlace(level, player, y);
        } finally {
            level.setBlock(wall, before, 3);
            target.discard();
            player.discard();
            level.setChunkForced(0, 0, false);
        }
    }

    private static void verifyPlace(net.minecraft.server.level.ServerLevel level, ServerPlayer player, int y) {
        var piece = new JungleTemplePiece(RandomSource.create(7), 0, 0);
        piece.move(0, y - piece.getBoundingBox().minY(), 0);
        BlockPos viewed = piece.getBoundingBox().getCenter();
        var chunkPos = ChunkPos.containing(viewed);
        var chunk = level.getChunk(chunkPos.x(), chunkPos.z());
        var structure = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getValue(Identifier.parse("minecraft:jungle_pyramid"));
        var original = new HashMap<>(chunk.getAllStarts());
        var starts = new HashMap<>(original);
        starts.put(structure, new StructureStart(structure, chunkPos, 0, new PiecesContainer(List.of(piece))));
        chunk.setAllStarts(starts);
        try {
            var observer = new VanillaPlaceObserver();
            var first = observer.observe(level, player, viewed, 1_000).orElseThrow();
            var repeated = observer.observe(level, player, viewed, 2_000).orElseThrow();
            require(first.key().equals(repeated.key()), "place identity is stable across repeated observations");
            require(observer.observe(level, player, viewed.offset(0, 20, 0), 3_000).isEmpty(),
                    "nearby terrain outside structure pieces does not reveal a hidden place");
        } finally { chunk.setAllStarts(original); }
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
