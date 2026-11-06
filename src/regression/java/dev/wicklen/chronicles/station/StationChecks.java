package dev.wicklen.chronicles.station;

import com.google.gson.Gson;
import com.mojang.serialization.JsonOps;

import java.util.UUID;

/** Stable data/layout invariants without starting Minecraft. */
public final class StationChecks {
    public static void main(String[] args) {
        UUID world = UUID.randomUUID();
        for (var variant : StationVariant.values()) for (int rotation = 0; rotation < 4; rotation++) {
            var seed = new StationSeed(1, -32, 70, 48, rotation, variant);
            var place = seed.manifest(world, "minecraft:overworld");
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                var point = seed.point(x, 1, z);
                require(place.bounds().contains(point), "all modules remain inside a single chunk");
            }
            require(place.bounds().width() == 16 && place.bounds().depth() == 16, "bounded footprint");
            require(place.bounds().contains(place.anchor()) && place.bounds().contains(place.safeExit()), "anchors stay in footprint");
            if (variant == StationVariant.UNDEAD_COURTYARD) {
                var arena = place.arena();
                require(arena.width() == 12 && arena.depth() == 12, "rotation preserves 12x12 arena");
                require(arena.contains(place.anchor()) && !arena.contains(place.safeExit()), "start inside arena and safe exit outside");
                require(place.anchor().x() - arena.minX() >= 4 && arena.maxX() - place.anchor().x() >= 4
                        && place.anchor().z() - arena.minZ() >= 4 && arena.maxZ() - place.anchor().z() >= 4,
                        "gateway anchor has spawn clearance");
            } else require(place.arena() == null, "research/supply stations do not become forced combat arenas");
            require(seed.manifest(world, "minecraft:overworld").equals(place), "manifest is deterministic on repeated loads");
            require(!seed.manifest(UUID.randomUUID(), "minecraft:overworld").key().equals(place.key()), "world identity scopes a place");
            require(!seed.manifest(world, "minecraft:the_nether").key().equals(place.key()), "dimension scopes a place");
            var encoded = StationSeed.CODEC.encodeStart(JsonOps.INSTANCE, seed).getOrThrow();
            require(StationSeed.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow().equals(seed), "chunk attachment codec round trip");
            require(new Gson().fromJson(new Gson().toJson(place), PlaceManifest.class).equals(place), "manifest persistence round trip");
        }
        try { new StationSeed(1, 1, 70, 0, 0, StationVariant.OVERGROWN_RESEARCH); throw new AssertionError("misaligned footprint accepted"); }
        catch (IllegalArgumentException expected) { /* expected */ }
        System.out.println("PASS: 3 station variants x4 rotations, one-chunk bounds, clear arena anchors, deterministic IDs, chunk/manifest serialization");
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
