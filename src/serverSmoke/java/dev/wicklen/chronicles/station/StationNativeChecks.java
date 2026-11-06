package dev.wicklen.chronicles.station;

import com.google.gson.Gson;
import com.mojang.authlib.GameProfile;
import dev.wicklen.chronicles.ai.WorldStorageContext;
import dev.wicklen.chronicles.core.discovery.DiscoveryPosition;
import dev.wicklen.chronicles.core.discovery.Observation;
import dev.wicklen.chronicles.exploration.DiscoveryRepository;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/** Requires the opt-in isolated flat station fixture, never a normal server world. */
public final class StationNativeChecks {
    private record Probe(UUID worldId, StationSeed seed, PlaceManifest.Point marker) {}
    private static PlaceManifest fixtureCourtyard;

    /** Test-only bridge for optional real-Gateway checks in this same isolated run. */
    public static PlaceManifest courtyardForCombat() {
        if (fixtureCourtyard == null) throw new IllegalStateException("Station fixture has not verified a courtyard");
        return fixtureCourtyard;
    }

    public static void verify(MinecraftServer server) throws Exception {
        if (!Boolean.getBoolean("wicklen.chronicles.smoke.verifyStations"))
            throw new IllegalStateException("Station native checks require the explicit isolated fixture flag");
        var level = server.overworld();
        fixtureCourtyard = null;
        var places = new ArrayList<PlaceManifest>();
        var seen = EnumSet.noneOf(StationVariant.class);
        StationSeed firstSeed = null;
        for (int ring = 0; ring < 8 && seen.size() < StationVariant.values().length; ring++) {
            for (int x = 0; x <= ring; x++) for (int z = 0; z <= ring; z++) {
                if (x != ring && z != ring) continue;
                // A separate window leaves earlier smoke/player geometry probes untouched.
                var chunk = level.getChunk(32 + x, 32 + z); // explicit new chunks, native decoration runs here
                var seed = chunk.getAttached(StationAttachments.STATION);
                if (seed == null) continue;
                var manifest = StationService.index(level, chunk).orElseThrow();
                require(manifest.equals(seed.manifest(WorldStorageContext.worldId(), "minecraft:overworld")), "manifest matches chunk generation seed");
                require(StationService.index(level, chunk).orElseThrow().equals(manifest), "reindex never changes placed state");
                verifyLayout(level, seed, manifest);
                places.add(manifest);
                seen.add(seed.variant());
                if (seed.variant() == StationVariant.UNDEAD_COURTYARD) fixtureCourtyard = manifest;
                if (firstSeed == null) firstSeed = seed;
            }
        }
        require(seen.size() == StationVariant.values().length, "native new-chunk fixture must generate all three variants; found " + seen);
        var place = places.getFirst();
        var player = new ServerPlayer(server, level, new GameProfile(UUID.randomUUID(), "StationProbe"), ClientInformation.createDefault());
        player.setPos(place.safeExit().x() + 0.5, place.safeExit().y(), place.safeExit().z() + 0.5);
        try {
            require(StationService.knownStation(player, place.key()).isEmpty(), "unexplored station cannot be selected by ID");
            require(StationService.knownCourtyards(player).isEmpty(), "batch selector excludes every unknown courtyard");
            var entry = new Observation(place.key(), place.variant().title(), place.variant().summary(),
                    new DiscoveryPosition(place.dimension(), place.safeExit().x(), place.safeExit().y(), place.safeExit().z()), System.currentTimeMillis());
            new DiscoveryRepository().observe(player.getUUID(), entry);
            require(StationService.knownStation(player, place.key()).orElseThrow().equals(place), "personal discovery permits selecting the known station");
            require(place.arena() == null ? StationService.knownCourtyards(player).isEmpty()
                    : StationService.knownCourtyards(player).equals(List.of(place)), "batch selector exposes only personally known courtyards");
        } finally { player.discard(); }

        BlockPos unchanged = place.anchor().blockPos();
        var before = level.getBlockState(unchanged);
        require(!new WaystationFeature().place(level, level.getChunkSource().getGenerator(), RandomSource.create(4), unchanged),
                "feature refuses retrofitting a live ServerLevel");
        require(level.getBlockState(unchanged).equals(before), "refused live placement changes no geometry");

        var probeFile = WorldStorageContext.directory().resolve("station-smoke-probe.json");
        var gson = new Gson();
        if (Files.exists(probeFile)) {
            var probe = gson.fromJson(Files.readString(probeFile), Probe.class);
            require(probe.worldId().equals(WorldStorageContext.worldId()), "reopen preserves station world identity");
            var chunk = level.getChunk(probe.seed().minX() >> 4, probe.seed().minZ() >> 4);
            require(probe.seed().equals(chunk.getAttached(StationAttachments.STATION)), "chunk attachment survives a server restart");
            require(level.getBlockState(probe.marker().blockPos()).is(Blocks.DIAMOND_BLOCK), "chunk reload does not rebuild over a player's changed block");
        } else {
            var marker = firstSeed.point(0, 0, 0);
            level.setBlock(marker.blockPos(), Blocks.DIAMOND_BLOCK.defaultBlockState(), 3);
            Files.writeString(probeFile, gson.toJson(new Probe(WorldStorageContext.worldId(), firstSeed, marker)));
        }
        WorldStorageContext.flush();
        System.out.println("PASS: native new-chunk stations, three variants, personal selection, clear arena, no live retrofit, persisted chunk marker");
    }

    private static void verifyLayout(net.minecraft.server.level.ServerLevel level, StationSeed seed, PlaceManifest place) {
        for (int door : new int[]{2, 7, 12}) {
            require(level.getBlockState(seed.point(door, 1, 12).blockPos()).isAir()
                    && level.getBlockState(seed.point(door, 2, 12).blockPos()).isAir(), "room entrances remain passable");
        }
        var exit = place.safeExit().blockPos();
        require(level.getBlockState(exit).isAir() && level.getBlockState(exit.above()).isAir()
                && level.getBlockState(exit.below()).canOcclude(), "safe exit has footing and headroom");
        int loot = 0;
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            var pos = seed.point(x, 1, z).blockPos();
            if (level.getBlockEntity(pos) instanceof RandomizableContainerBlockEntity container && container.getLootTable() != null) loot++;
        }
        require(loot == 1, "station has one stable loot container");
        if (place.arena() != null) {
            var arena = place.arena();
            for (int x = arena.minX(); x <= arena.maxX(); x++) for (int z = arena.minZ(); z <= arena.maxZ(); z++) {
                require(level.getBlockState(new BlockPos(x, arena.minY() - 1, z)).canOcclude(), "arena has continuous solid footing");
                for (int y = arena.minY(); y <= arena.maxY(); y++)
                    require(level.getBlockState(new BlockPos(x, y, z)).isAir(), "arena volume is clear");
            }
        }
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
