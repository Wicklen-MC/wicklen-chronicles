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

    public static boolean ready(MinecraftServer server) {
        return server.overworld().isPositionEntityTicking(new BlockPos(4, 0, 12));
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
        require(level.addFreshEntity(target), "native ray fixture entity must be accepted by the level");
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
                    + ", entityLookup=" + level.getEntity(target.getId()) + ", ticking=" + level.isPositionEntityTicking(target.blockPosition())
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
            verifyVisiblePlaceHints(level, player, y);
            System.out.println("PASS: native spyglass geometry, place hint LOS/FOV/range, no implicit discoveries or chunk loading");
        } finally {
            level.setBlock(wall, before, 3);
            target.discard();
            player.discard();
            level.setChunkForced(0, 0, false);
        }
    }

    private static void verifyVisiblePlaceHints(net.minecraft.server.level.ServerLevel level, ServerPlayer player, int y) {
        level.getChunk(0, 1); // explicit fixture setup; the hints must not load any further chunk
        var bounds = new dev.wicklen.chronicles.station.PlaceManifest.Bounds(0, y, 8, 15, y + 6, 23);
        var place = new dev.wicklen.chronicles.station.PlaceManifest(
                dev.wicklen.chronicles.ai.WorldStorageContext.worldId(),
                new dev.wicklen.chronicles.core.discovery.DiscoveryKey(
                        dev.wicklen.chronicles.core.discovery.DiscoveryKind.PLACE, "wicklen_chronicles:test/visible_station"),
                "minecraft:overworld", new dev.wicklen.chronicles.station.PlaceManifest.Point(0, y, 8),
                new dev.wicklen.chronicles.station.PlaceManifest.Point(8, y + 1, 16),
                new dev.wicklen.chronicles.station.PlaceManifest.Point(0, y + 1, 16), bounds,
                dev.wicklen.chronicles.station.StationVariant.OVERGROWN_RESEARCH, 1, null);
        var before = new HashMap<BlockPos, net.minecraft.world.level.block.state.BlockState>();
        for (int x = 0; x < 16; x++) for (int z = 8; z < 24; z++) {
            var pos = new BlockPos(x, y, z);
            before.put(pos, level.getBlockState(pos));
            level.setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
        }
        try {
            player.setPos(8, y + 1, 0.5);
            player.setYRot(0); player.setYHeadRot(0); player.setXRot(0);
            long revision = ExplorationService.snapshot(player).revision();
            require(ObservationHints.visibleSurface(player, place).isPresent(), "visible nearby station has a surface hint before using a spyglass");
            require(ExplorationService.snapshot(player).revision() == revision, "seeing a hint does not record a discovery");
            player.setYRot(180);
            player.setYHeadRot(180);
            require(ObservationHints.visibleSurface(player, place).isEmpty(), "station behind player is not highlighted");
            player.setYRot(0);
            player.setYHeadRot(0);
            player.setPos(8, y + 1, -40);
            require(ObservationHints.visibleSurface(player, place).isEmpty(), "distant station is not highlighted");
            player.setPos(8, y + 1, 0.5);
            for (int x = 0; x < 16; x++) for (int dy = 0; dy < 8; dy++) {
                var pos = new BlockPos(x, y + dy, 4);
                before.putIfAbsent(pos, level.getBlockState(pos));
                level.setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
            }
            require(ObservationHints.visibleSurface(player, place).isEmpty(), "occluding wall suppresses every nearby station ray");
            require(ExplorationService.snapshot(player).revision() == revision, "occluded or visible hints never create records or map markers");
            int far = 1_000_000;
            var unloaded = new dev.wicklen.chronicles.station.StationSeed(1, far, y, far, 0,
                    dev.wicklen.chronicles.station.StationVariant.OVERGROWN_RESEARCH)
                    .manifest(place.worldId(), place.dimension());
            player.setPos(far + 8, y + 1, far - 5);
            require(level.getChunkSource().getChunkNow(far >> 4, far >> 4) == null, "unloaded hint fixture starts without a chunk");
            require(ObservationHints.visibleSurface(player, unloaded).isEmpty(), "unloaded station is not highlighted");
            require(dev.wicklen.chronicles.station.StationService.nearbyLoaded(player, 40).isEmpty(), "nearby hint lookup does not find an unloaded station");
            require(level.getChunkSource().getChunkNow(far >> 4, far >> 4) == null, "surface rays and nearby lookup never load a chunk");
        } finally { before.forEach((pos, state) -> level.setBlock(pos, state, 3)); }
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
