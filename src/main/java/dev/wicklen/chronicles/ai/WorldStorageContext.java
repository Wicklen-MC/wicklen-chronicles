package dev.wicklen.chronicles.ai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** One active server world per process, matching the existing static adapters. */
public final class WorldStorageContext {
    private static volatile Session session;

    record Session(Path directory, UUID worldId, UUID sessionId, boolean acceptingAsync) {}

    private WorldStorageContext() {}

    public static synchronized void open(Path worldDirectory) throws IOException {
        open(worldDirectory, null);
    }

    /** Legacy global data is detected for review, never assigned to a world automatically. */
    public static synchronized void open(Path worldDirectory, Path legacyDirectory) throws IOException {
        if (session != null) throw new IllegalStateException("A world storage context is already open");
        Path directory = worldDirectory.toAbsolutePath().normalize().resolve("redzspirit");
        Path metadata = directory.resolve("world-metadata.json");
        UUID worldId;
        if (Files.exists(metadata)) {
            try {
                JsonObject object = JsonParser.parseString(Files.readString(metadata)).getAsJsonObject();
                JsonStore.requireSchema(object, 1);
                worldId = UUID.fromString(object.get("worldId").getAsString());
            } catch (Exception e) {
                throw new IOException("Invalid world metadata; source preserved: " + metadata, e);
            }
        } else {
            Files.createDirectories(directory);
            worldId = UUID.randomUUID();
            var object = new JsonObject();
            object.addProperty("schemaVersion", 1);
            object.addProperty("worldId", worldId.toString());
            JsonStore.writeAtomically(metadata, object.toString());
        }
        if (legacyDirectory != null && !legacyDirectory.toAbsolutePath().normalize().equals(directory)
            && Files.isDirectory(legacyDirectory)) {
            try (var files = Files.list(legacyDirectory)) {
                if (files.anyMatch(path -> path.getFileName().toString().endsWith(".json"))) {
                    LoggerFactory.getLogger("redzspirit.storage").warn(
                        "[Wicklen: Chronicles] Legacy global data at {} was not imported into {}. Select the intended world before manual migration.",
                        legacyDirectory, directory);
                }
            }
        }
        JsonStore.resetWorldStores();
        session = new Session(directory, worldId, UUID.randomUUID(), true);
    }

    public static UUID worldId() { return requireSession().worldId(); }
    /** Null after close, allowing stale asynchronous callbacks to be rejected safely. */
    public static UUID sessionId() { Session current = session; return current == null ? null : current.sessionId(); }
    public static boolean matchesSession(UUID expected) {
        Session current = session;
        return expected != null && current != null && current.acceptingAsync() && expected.equals(current.sessionId());
    }
    public static Path directory() { return requireSession().directory(); }
    public static boolean isOpen() { return session != null; }

    static Session requireSession() {
        Session current = session;
        if (current == null) throw new IllegalStateException("World storage is not open");
        return current;
    }

    /** Reject late async results before the final flush, retaining access to world storage. */
    public static synchronized void beginStopping() {
        Session current = session;
        if (current != null)
            session = new Session(current.directory(), current.worldId(), current.sessionId(), false);
    }

    /** Call during SERVER_STOPPING while the context is still active. */
    public static void flush() throws IOException { JsonStore.flushAll(); }

    /** Call after async state is invalidated on SERVER_STOPPED; does not write unused stores. */
    public static synchronized void close() {
        session = null;
        JsonStore.resetWorldStores();
    }
}
