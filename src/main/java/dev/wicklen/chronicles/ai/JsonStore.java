package dev.wicklen.chronicles.ai;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * World-scoped JSON adapter. Values are owned by the server thread; synchronized
 * I/O does not make mutation of the returned collection thread-safe.
 * Default stores require an active WorldStorageContext. Explicit directories
 * are reserved for isolated tests/import tools, not global gameplay.
 */
public final class JsonStore<T> {
    private static final Gson GSON = new Gson();
    private static final Logger LOGGER = LoggerFactory.getLogger("wicklen_chronicles.storage");
    private static final int SCHEMA_VERSION = 1;
    private static final List<JsonStore<?>> STORES = new ArrayList<>();

    private final String fileName;
    private final TypeToken<T> type;
    private final Path explicitDirectory;
    private UUID sessionId;
    private T data;
    private boolean loaded;
    private boolean legacy;
    private IllegalStateException loadFailure;

    public JsonStore(String fileName, TypeToken<T> type) {
        this(fileName, type, null);
    }

    JsonStore(String fileName, TypeToken<T> type, Path directory) {
        if (fileName == null || fileName.isBlank() || !Path.of(fileName).getFileName().toString().equals(fileName)) {
            throw new IllegalArgumentException("Store filename must be a single path component");
        }
        this.fileName = fileName;
        this.type = Objects.requireNonNull(type);
        this.explicitDirectory = directory == null ? null : directory.toAbsolutePath().normalize();
        synchronized (STORES) { STORES.add(this); }
    }

    private Path file() {
        if (explicitDirectory != null) return explicitDirectory.resolve(fileName);
        var context = WorldStorageContext.requireSession();
        if (!context.sessionId().equals(sessionId)) {
            reset();
            sessionId = context.sessionId();
        }
        return context.directory().resolve(fileName);
    }

    /** Unsupported/corrupt data fails closed; it never becomes an empty save. */
    public synchronized T get(Supplier<T> def) {
        Path file = file();
        if (loadFailure != null) throw loadFailure;
        if (loaded) return data;
        try {
            if (Files.exists(file)) {
                JsonElement source = JsonParser.parseString(Files.readString(file));
                JsonElement payload = source;
                if (source.isJsonObject() && source.getAsJsonObject().has("schemaVersion")) {
                    JsonObject envelope = source.getAsJsonObject();
                    requireSchema(envelope, SCHEMA_VERSION);
                    if (!envelope.has("data")) throw new IOException("Missing data payload");
                    payload = envelope.get("data");
                } else {
                    legacy = true;
                }
                if (payload == null || payload.isJsonNull()) throw new IOException("Null store payload");
                data = Objects.requireNonNull(GSON.fromJson(payload, type.getType()), "Null decoded payload");
            } else {
                data = Objects.requireNonNull(def.get(), "Null default store value");
            }
            loaded = true;
            return data;
        } catch (Exception e) {
            loadFailure = new IllegalStateException("Cannot load " + file + "; source preserved", e);
            LOGGER.error("[Wicklen: Chronicles] {} load failed; source preserved: {}", file, e.toString());
            throw loadFailure;
        }
    }

    /** Compatibility path; actions requiring a reported result use saveChecked(). */
    public synchronized void save() {
        try { saveChecked(); }
        catch (IOException | IllegalStateException e) {
            LOGGER.warn("[Wicklen: Chronicles] {} save failed: {}", fileName, e.toString());
        }
    }

    /** Atomic file replacement, not a transaction with Minecraft's inventory. */
    public synchronized void saveChecked() throws IOException {
        Path file = file();
        if (loadFailure != null) throw new IOException("Refusing to overwrite unreadable " + file, loadFailure);
        if (!loaded) return;
        final String encoded;
        try {
            var envelope = new JsonObject();
            envelope.addProperty("schemaVersion", SCHEMA_VERSION);
            envelope.add("data", GSON.toJsonTree(data, type.getType()));
            encoded = GSON.toJson(envelope);
        } catch (RuntimeException e) {
            throw new IOException("Cannot encode " + file, e);
        }
        Files.createDirectories(file.getParent());
        if (legacy && Files.exists(file)) {
            Path backup = file.resolveSibling(fileName + ".v0.bak");
            if (!Files.exists(backup)) Files.copy(file, backup);
            else if (Files.mismatch(file, backup) != -1) {
                throw new IOException("Migration backup differs from source: " + backup);
            }
        }
        writeAtomically(file, encoded);
        legacy = false;
    }

    static void requireSchema(JsonObject object, int expected) throws IOException {
        try {
            var version = object.get("schemaVersion");
            if (version == null || !version.isJsonPrimitive() || !version.getAsJsonPrimitive().isNumber()
                || version.getAsBigDecimal().compareTo(java.math.BigDecimal.valueOf(expected)) != 0) {
                throw new IOException("Unsupported storage schemaVersion");
            }
        } catch (RuntimeException e) {
            throw new IOException("Invalid storage schemaVersion", e);
        }
    }

    static void writeAtomically(Path file, String encoded) throws IOException {
        Path pending = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(pending, encoded);
        try (var channel = FileChannel.open(pending, StandardOpenOption.WRITE)) { channel.force(true); }
        try { Files.move(pending, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException ignored) {
            Files.move(pending, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Flushes loaded world stores; fails if any store could not be saved. */
    public static void flushAll() throws IOException {
        IOException failure = null;
        for (var store : stores()) {
            if (store.explicitDirectory != null) continue;
            try { store.saveChecked(); }
            catch (IOException | IllegalStateException e) {
                if (failure == null) failure = new IOException("One or more world stores failed to save");
                failure.addSuppressed(e);
            }
        }
        if (failure != null) throw failure;
    }

    public static void resetAll() {
        for (var store : stores()) store.reset();
    }

    static void resetWorldStores() {
        for (var store : stores()) if (store.explicitDirectory == null) store.reset();
    }

    private static List<JsonStore<?>> stores() {
        synchronized (STORES) { return List.copyOf(STORES); }
    }

    private synchronized void reset() {
        loaded = false;
        legacy = false;
        data = null;
        loadFailure = null;
        sessionId = null;
    }
}
