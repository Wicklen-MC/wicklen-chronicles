package dev.wicklen.chronicles.ai;

import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

/** Filesystem regressions, without starting Minecraft or using external services. */
public final class WorldStorageChecks {
    public static void main(String[] args) throws Exception {
        Path temporary = Files.createTempDirectory("wicklen-world-storage-");
        try {
            worlds(temporary);
            legacy(temporary);
            invalidSources(temporary);
            ioFailure(temporary);
            invalidMetadata(temporary);
            namespaceMigration(temporary);
            System.out.println("PASS: world isolation/reopen, session guards, v0 migration/backup, legacy preservation, invalid data protection, I/O failure");
        } finally {
            WorldStorageContext.close();
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void worlds(Path root) throws Exception {
        var store = new JsonStore<Map<String, String>>("world-test.json", new TypeToken<>() {});
        expect(IllegalStateException.class, () -> store.get(HashMap::new), "no access before world open");
        Path first = root.resolve("first-world");
        Path second = root.resolve("second-world");
        WorldStorageContext.open(first);
        var firstId = WorldStorageContext.worldId();
        var firstSession = WorldStorageContext.sessionId();
        require(WorldStorageContext.matchesSession(firstSession), "active session accepts async results");
        store.get(HashMap::new).put("player", "first world");
        WorldStorageContext.beginStopping();
        require(!WorldStorageContext.matchesSession(firstSession), "stopping session rejects async results");
        require(WorldStorageContext.sessionId().equals(firstSession), "stopping retains storage identity for final flush");
        WorldStorageContext.flush();
        WorldStorageContext.close();
        require(!WorldStorageContext.matchesSession(firstSession), "closed session cannot apply late results");
        expect(IllegalStateException.class, () -> store.get(HashMap::new), "no access after close");

        WorldStorageContext.open(second);
        require(WorldStorageContext.matchesSession(WorldStorageContext.sessionId()), "new world accepts async after stopping previous world");
        require(!WorldStorageContext.worldId().equals(firstId), "different worlds have distinct IDs");
        require(store.get(HashMap::new).isEmpty(), "cached data does not leak into second world");
        store.get(HashMap::new).put("player", "second world");
        WorldStorageContext.flush();
        WorldStorageContext.close();

        WorldStorageContext.open(first);
        require(WorldStorageContext.worldId().equals(firstId), "world ID survives reopen");
        require(!WorldStorageContext.sessionId().equals(firstSession), "reopen creates a new session");
        require(store.get(HashMap::new).get("player").equals("first world"), "first world's progress survives");
        WorldStorageContext.close();

        // A store registered but never read must not replace a pre-existing source during shutdown.
        Path untouched = first.resolve("wicklen-chronicles/untouched.json");
        String original = "{\"preserve\":\"old data\"}";
        Files.writeString(untouched, original);
        new JsonStore<Map<String, String>>("untouched.json", new TypeToken<>() {});
        WorldStorageContext.open(first);
        WorldStorageContext.flush();
        WorldStorageContext.close();
        require(Files.readString(untouched).equals(original), "unused lifecycle flush preserves data");
    }

    private static void legacy(Path root) throws Exception {
        Path globals = dev.wicklen.chronicles.compat.LegacyCompatibility.legacyGlobalDirectory(root.resolve("legacy-global"));
        Files.createDirectories(globals);
        String global = "{\"player\":\"unassigned history\"}";
        Files.writeString(globals.resolve("memory.json"), global);
        Path world = root.resolve("migration-world");
        WorldStorageContext.open(world, globals);
        var store = new JsonStore<Map<String, String>>("memory.json", new TypeToken<>() {});
        require(store.get(HashMap::new).isEmpty(), "global history is never automatically assigned");
        require(Files.readString(globals.resolve("memory.json")).equals(global), "global source remains untouched");
        WorldStorageContext.close();

        Path source = world.resolve("wicklen-chronicles/memory.json");
        String old = "{\"player\":\"Наблюдение\"}";
        Files.writeString(source, old);
        WorldStorageContext.open(world);
        require(store.get(HashMap::new).get("player").equals("Наблюдение"), "selected-world v0 is readable");
        store.saveChecked();
        require(Files.readString(source.resolveSibling("memory.json.v0.bak")).equals(old), "v0 backup preserves original bytes");
        var envelope = JsonParser.parseString(Files.readString(source)).getAsJsonObject();
        require(envelope.get("schemaVersion").getAsInt() == 1, "migration records schema version");
        require(envelope.getAsJsonObject("data").get("player").getAsString().equals("Наблюдение"), "migration preserves UTF-8 data");
        require(!Files.exists(source.resolveSibling("memory.json.tmp")), "successful atomic save leaves no pending file");
        WorldStorageContext.close();
        WorldStorageContext.open(world);
        require(store.get(HashMap::new).containsKey("player"), "versioned data reloads");
        WorldStorageContext.close();
    }

    private static void invalidSources(Path root) throws Exception {
        Path directory = root.resolve("invalid-sources");
        Files.createDirectories(directory);
        String[] invalid = {
            "{\"schemaVersion\":2,\"data\":{\"player\":\"future\"}}",
            "{\"schemaVersion\":1.5,\"data\":{}}",
            "{\"schemaVersion\":\"one\",\"data\":{}}",
            "{\"schemaVersion\":1}",
            "{\"schemaVersion\":1,\"data\":null}",
            "{\"schemaVersion\":1,\"data\":",
            "null"
        };
        for (int index = 0; index < invalid.length; index++) {
            String name = "invalid-" + index + ".json";
            Path source = directory.resolve(name);
            Files.writeString(source, invalid[index]);
            var store = new JsonStore<Map<String, String>>(name, new TypeToken<>() {}, directory);
            expect(IllegalStateException.class, () -> store.get(HashMap::new), "invalid source is rejected");
            expect(IOException.class, store::saveChecked, "rejected source cannot be overwritten");
            require(Files.readString(source).equals(invalid[index]), "invalid source bytes preserved");
        }

        Path source = directory.resolve("backup-conflict.json");
        Files.writeString(source, "{\"player\":\"current\"}");
        Files.writeString(source.resolveSibling("backup-conflict.json.v0.bak"), "different previous backup");
        var store = new JsonStore<Map<String, String>>("backup-conflict.json", new TypeToken<>() {}, directory);
        store.get(HashMap::new);
        expect(IOException.class, store::saveChecked, "migration cannot overwrite a different backup");
        require(Files.readString(source).equals("{\"player\":\"current\"}"), "backup conflict preserves source");
    }

    private static void ioFailure(Path root) throws Exception {
        Path notDirectory = root.resolve("blocked-directory");
        Files.writeString(notDirectory, "a regular file");
        var store = new JsonStore<Map<String, String>>("progress.json", new TypeToken<>() {}, notDirectory);
        store.get(HashMap::new).put("player", "progress");
        expect(IOException.class, store::saveChecked, "write failure is visible to durable callers");
        require(Files.readString(notDirectory).equals("a regular file"), "failed save preserves blocking file");
    }

    private static void invalidMetadata(Path root) throws Exception {
        Path world = root.resolve("future-world");
        Path directory = world.resolve("wicklen-chronicles");
        Files.createDirectories(directory);
        Path metadata = directory.resolve("world-metadata.json");
        String source = "{\"schemaVersion\":2,\"worldId\":\"future-id\"}";
        Files.writeString(metadata, source);
        expect(IOException.class, () -> WorldStorageContext.open(world), "future world metadata rejects open");
        require(!WorldStorageContext.isOpen(), "failed open does not attach partial context");
        require(Files.readString(metadata).equals(source), "failed open preserves metadata");
    }

    private static void namespaceMigration(Path root) throws Exception {
        var legacyName = dev.wicklen.chronicles.compat.LegacyCompatibility.LEGACY_NAMESPACE;
        Path world = root.resolve("namespace-world");
        Path old = world.resolve(legacyName);
        Path current = world.resolve("wicklen-chronicles");
        Files.createDirectories(old);
        var worldId = java.util.UUID.randomUUID();
        String metadata = "{\"schemaVersion\":1,\"worldId\":\"" + worldId + "\"}";
        String memory = "{\"schemaVersion\":1,\"data\":{\"player\":\"remembered\"}}";
        Files.writeString(old.resolve("world-metadata.json"), metadata);
        Files.writeString(old.resolve("memory.json"), memory);
        Files.writeString(old.resolve("prefs.json"), "[\"a hidden companion\"]");
        WorldStorageContext.open(world);
        require(WorldStorageContext.directory().equals(current.toAbsolutePath()), "legacy world directory becomes the current path");
        require(WorldStorageContext.worldId().equals(worldId), "directory migration preserves world identity");
        require(Files.readString(current.resolve("memory.json")).equals(memory), "directory migration preserves data bytes");
        require(!Files.exists(old), "successful directory migration is one-time");
        WorldStorageContext.close();
        WorldStorageContext.open(world);
        require(WorldStorageContext.worldId().equals(worldId), "migrated world reopens with same identity");
        WorldStorageContext.close();

        Files.createDirectories(old);
        Files.writeString(old.resolve("memory.json"), "{\"player\":\"conflicting old source\"}");
        WorldStorageContext.open(world);
        require(WorldStorageContext.worldId().equals(worldId), "current directory wins a naming conflict");
        require(Files.readString(old.resolve("memory.json")).contains("conflicting"), "conflicting legacy source is preserved");
        WorldStorageContext.close();

        Path futureWorld = root.resolve("namespace-future-world");
        Path futureOld = futureWorld.resolve(legacyName);
        Files.createDirectories(futureOld);
        String future = "{\"schemaVersion\":9,\"data\":{}}";
        Files.writeString(futureOld.resolve("memory.json"), future);
        expect(IOException.class, () -> WorldStorageContext.open(futureWorld), "unknown legacy schema blocks directory migration");
        require(Files.readString(futureOld.resolve("memory.json")).equals(future), "unsupported source stays unchanged");
        require(!Files.exists(futureWorld.resolve("wicklen-chronicles")), "failed migration creates no current directory");

        Path config = root.resolve("namespace-config");
        Files.createDirectories(config);
        Path oldConfig = config.resolve(legacyName + ".json");
        String settings = "{\"provider\":\"ollama\",\"proactive\":false}";
        Files.writeString(oldConfig, settings);
        Path migrated = dev.wicklen.chronicles.compat.LegacyCompatibility.configurationFile(config);
        require(migrated.equals(config.resolve("wicklen-chronicles.json")), "config uses neutral filename");
        require(Files.readString(migrated).equals(settings) && !Files.exists(oldConfig), "config migration preserves settings bytes");
        Files.writeString(oldConfig, "{\"provider\":\"custom\"}");
        dev.wicklen.chronicles.compat.LegacyCompatibility.configurationFile(config);
        require(Files.readString(migrated).equals(settings) && Files.exists(oldConfig), "config conflict never overwrites either source");
        Path badConfig = root.resolve("namespace-bad-config");
        Files.createDirectories(badConfig);
        Files.writeString(badConfig.resolve(legacyName + ".json"), future);
        expect(IOException.class, () -> dev.wicklen.chronicles.compat.LegacyCompatibility.configurationFile(badConfig),
            "unknown config schema blocks migration");
        require(Files.readString(badConfig.resolve(legacyName + ".json")).equals(future), "unsupported config preserved");
        require(!Files.exists(badConfig.resolve("wicklen-chronicles.json")), "failed config migration creates no replacement");
    }

    @FunctionalInterface
    private interface ThrowingAction { void run() throws Exception; }

    private static void expect(Class<? extends Throwable> type, ThrowingAction action, String message) throws Exception {
        try { action.run(); }
        catch (Throwable failure) {
            if (type.isInstance(failure)) return;
            throw new AssertionError(message + ": unexpected " + failure, failure);
        }
        throw new AssertionError(message + ": no exception");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
