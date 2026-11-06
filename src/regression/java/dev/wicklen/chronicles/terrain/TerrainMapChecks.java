package dev.wicklen.chronicles.terrain;

import dev.wicklen.chronicles.core.map.*;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;

/** Offline IO boundaries: no game bootstrap, generation, external service or running world. */
public final class TerrainMapChecks {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("wicklen-terrain-checks-");
        try {
            UUID world = UUID.randomUUID(), owner = UUID.randomUUID(), other = UUID.randomUUID();
            var cell = new MapCell("minecraft:overworld", -1, -1); var store = new TerrainMapStore(root, world);
            require(store.region(owner, cell).chunks().isEmpty() && !Files.exists(root.resolve("terrain-map")), "reading unknown terrain creates no files");
            require(store.record(owner, sample(cell, 100, 44)).firstVisit(), "first snapshot is checked and persisted");
            require(store.region(other, cell).chunks().isEmpty(), "another player has no inherited map pixels");
            require(store.region(owner, new MapCell("minecraft:the_nether", -1, -1)).chunks().isEmpty(), "another dimension has no inherited pixels");
            Path file;
            try (var files = Files.walk(root)) { file = files.filter(path -> path.toString().endsWith(".wmap")).findFirst().orElseThrow(); }
            byte[] original = Files.readAllBytes(file); Path blocked = file.resolveSibling(file.getFileName() + ".tmp");
            Files.createDirectory(blocked);
            try { store.record(owner, sample(cell, 200, 48)); throw new AssertionError("failed replacement must be reported"); }
            catch (IOException expected) {
                require(store.region(owner, cell).chunks().get(255).color(0, 0) == 44, "failed IO never publishes the new snapshot");
                require(Arrays.equals(original, Files.readAllBytes(file)), "failed save preserves the previous disk snapshot");
            }
            Files.delete(blocked); store.record(owner, sample(cell, 200, 48));
            var reopen = new TerrainMapStore(root, world);
            var saved = reopen.region(owner, cell).chunks().get(255);
            require(saved.color(0, 0) == 48 && saved.firstVisitedAt() == 100, "reopen keeps terrain and original visit time");
            try { new TerrainMapStore(root, UUID.randomUUID()).region(owner, cell); throw new AssertionError("world mismatch must fail closed"); }
            catch (IOException expected) {}
            var image = reopen.viewport(owner, cell.dimension(), new MapViewport(0, 0, 256));
            require(image[56 * 128 + 56] == 48 && image[64 * 128 + 64] == 0, "negative-region pixels project correctly without hidden terrain");
            byte[] corrupt = Files.readAllBytes(file); corrupt[corrupt.length - 1] ^= 1; Files.write(file, corrupt);
            var malformed = new TerrainMapStore(root, world);
            try { malformed.region(owner, cell); throw new AssertionError("CRC mismatch must fail closed"); } catch (IOException expected) {}
            try { malformed.record(owner, sample(cell, 300, 52)); throw new AssertionError("unreadable source must not be replaced"); } catch (IOException expected) {}
            require(Arrays.equals(corrupt, Files.readAllBytes(file)), "malformed source remains available for repair");
            System.out.println("PASS: terrain regional persistence, world/player/dimension isolation, failed-save rollback, restart, corrupt-source preservation");
        } finally {
            try (var files = Files.walk(root)) { for (var file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file); }
        }
    }
    private static SurveyChunk sample(MapCell cell, long time, int color) { var colors = new byte[64]; Arrays.fill(colors, (byte) color); return new SurveyChunk(cell, time, colors); }
    private static void require(boolean result, String message) { if (!result) throw new AssertionError(message); }
}
