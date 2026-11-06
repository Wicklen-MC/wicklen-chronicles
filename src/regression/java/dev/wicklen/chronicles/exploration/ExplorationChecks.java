package dev.wicklen.chronicles.exploration;

import dev.wicklen.chronicles.ai.WorldStorageContext;
import dev.wicklen.chronicles.core.discovery.DiscoveryKey;
import dev.wicklen.chronicles.core.discovery.DiscoveryKind;
import dev.wicklen.chronicles.core.discovery.DiscoveryPosition;
import dev.wicklen.chronicles.core.discovery.Observation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;

/** Offline boundary checks; no Minecraft bootstrap or external APIs. */
public final class ExplorationChecks {
    public static void main(String[] args) throws Exception {
        focus();
        Path directory = Files.createTempDirectory("wicklen-discovery-checks-");
        try { persistence(directory); }
        finally {
            WorldStorageContext.close();
            try (var paths = Files.walk(directory)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        System.out.println("PASS: continuous observation gates, personal/world discovery isolation, restart/deduplication, failed-save rollback, stale session rejection");
    }

    private static void focus() {
        var tracker = new ObservationTracker();
        for (int tick = 1; tick < 40; tick++)
            require(!tracker.sample("entity/one", tick, tick, true, true, false, 40), "incomplete dwell must not record");
        require(tracker.sample("entity/one", 40, 40, true, true, false, 40), "40 continuous samples complete observation");
        require(!tracker.sample("entity/two", 41, 41, true, true, false, 40), "switching creature resets dwell");
        require(!tracker.sample("entity/two", 42, 42, true, false, true, 40), "occlusion/range rejects even trusted external knowledge");
        require(!tracker.sample("entity/two", 43, 43, false, true, true, 40), "holding without using never scans");
        require(!tracker.sample("entity/two", 44, 0, true, true, true, 40), "client duration cannot substitute actual using ticks");
        require(tracker.sample("entity/two", 45, 1, true, true, true, 40), "confirmed Field Guide knowledge needs no second dwell");
        require(!tracker.sample("entity/two", 48, 4, true, true, false, 40) && tracker.focusedTicks() == 1,
                "missing samples restart continuity");
        require(!tracker.sample("entity/two", 49, 1, true, true, false, 40) && tracker.focusedTicks() == 1,
                "stop/reuse restarts continuity even when focus is unchanged");
    }

    private static Observation observation(String id) {
        return new Observation(new DiscoveryKey(DiscoveryKind.CREATURE, id), "Наблюдение", "Подтверждено сервером",
                new DiscoveryPosition("minecraft:overworld", 1, 70, 2), 1_000);
    }

    private static void persistence(Path directory) throws Exception {
        UUID player = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        Path first = directory.resolve("first");
        WorldStorageContext.open(first);
        UUID world = WorldStorageContext.worldId();
        var original = new DiscoveryRepository();
        require(original.observe(player, observation("minecraft:cow")).changed(), "first observation is persisted");
        require(!original.observe(player, observation("minecraft:cow")).changed(), "repeat has no revision/reward duplication");
        require(original.snapshot(player).revision() == 1 && original.snapshot(other).entries().isEmpty(), "knowledge is personal");
        Path source = WorldStorageContext.directory().resolve("discoveries.json");
        byte[] before = Files.readAllBytes(source);
        Path blocked = source.resolveSibling("discoveries.json.tmp");
        Files.createDirectory(blocked);
        try {
            original.observe(player, observation("minecraft:pig"));
            throw new AssertionError("failed persistence must be reported");
        } catch (IOException expected) {
            require(original.snapshot(player).entries().size() == 1, "failed save rolls back in-memory knowledge");
            require(java.util.Arrays.equals(before, Files.readAllBytes(source)), "failed save preserves disk data");
        }
        Files.delete(blocked);
        WorldStorageContext.flush();
        WorldStorageContext.close();
        WorldStorageContext.open(directory.resolve("second"));
        var second = new DiscoveryRepository();
        require(second.snapshot(player).entries().isEmpty(), "another world has no inherited knowledge");
        try { original.snapshot(player); throw new AssertionError("old runtime must reject new world"); }
        catch (IllegalStateException expected) { /* expected */ }
        WorldStorageContext.close();
        WorldStorageContext.open(first);
        var reopened = new DiscoveryRepository();
        require(reopened.snapshot(player).worldId().equals(world) && reopened.snapshot(player).entries().size() == 1,
                "restart preserves world identity and entries");
        require(!reopened.observe(player, observation("minecraft:cow")).changed(), "restart does not reset observations");
        WorldStorageContext.beginStopping();
        try { reopened.snapshot(player); throw new AssertionError("stopping runtime must reject new observations"); }
        catch (IllegalStateException expected) { /* expected */ }
        WorldStorageContext.flush();
        WorldStorageContext.close();
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
