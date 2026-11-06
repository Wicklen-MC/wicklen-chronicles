package dev.wicklen.chronicles.station;

import dev.wicklen.chronicles.core.discovery.DiscoveryPosition;
import dev.wicklen.chronicles.core.discovery.Observation;
import dev.wicklen.chronicles.exploration.DiscoveryRepository;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;

/** Explicit test fixture for GUI participation. Native generation and scanning have separate checks. */
public final class CourtyardGameFixture {
    public static PlaceManifest create(ServerPlayer player) {
        try {
            var level = player.level();
            var seed = new StationSeed(1, 64, -61, 64, 0, StationVariant.UNDEAD_COURTYARD);
            var chunk = level.getChunk(4, 4);
            int[][] ground = new int[16][16];
            for (int[] row : ground) java.util.Arrays.fill(row, seed.floorY());
            var build = WaystationFeature.class.getDeclaredMethod("build", WorldGenLevel.class,
                RandomSource.class, StationSeed.class, int[][].class);
            build.setAccessible(true);
            build.invoke(null, level, RandomSource.create(732719), seed, ground);
            chunk.setAttached(StationAttachments.STATION, seed);
            var place = StationService.index(level, chunk).orElseThrow();
            var anchor = place.anchor();
            new DiscoveryRepository().observe(player.getUUID(), new Observation(place.key(),
                place.variant().title(), "GUI combat fixture: already-known courtyard",
                new DiscoveryPosition(place.dimension(), anchor.x(), anchor.y(), anchor.z()),
                System.currentTimeMillis()));
            return place;
        } catch (ReflectiveOperationException | java.io.IOException error) {
            throw new AssertionError("Could not build native GUI courtyard fixture", error);
        }
    }

    private CourtyardGameFixture() {}
}
