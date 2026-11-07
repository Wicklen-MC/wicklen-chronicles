package dev.wicklen.chronicles.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.wicklen.chronicles.net.SpiritSpeechPreferencesC2SPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/** Local presentation preferences. Only the two effective automatic-speech preferences leave the client. */
public final class ClientUiSettings {
    public record Preferences(double journalScale, double speechScale, double speechOpacity,
                              boolean speechVisible, boolean automaticRemarksEnabled, int minIntervalMinutes,
                              double speechHorizontal, double speechVertical) {
        public Preferences {
            journalScale = bounded(journalScale, 0.65, 1.15, 0.85);
            speechScale = bounded(speechScale, 0.65, 1.25, 0.85);
            speechOpacity = bounded(speechOpacity, 0.25, 1, 0.9);
            minIntervalMinutes = Math.max(2, Math.min(30, minIntervalMinutes));
            speechHorizontal = bounded(speechHorizontal, 0, 1, 0);
            speechVertical = bounded(speechVertical, 0, 1, 1);
        }

        public Preferences(double journalScale, double speechScale, double speechOpacity,
                           boolean speechVisible, boolean automaticRemarksEnabled, int minIntervalMinutes) {
            this(journalScale, speechScale, speechOpacity, speechVisible, automaticRemarksEnabled,
                    minIntervalMinutes, 0, 1);
        }

        public boolean effectiveAutomatic() {
            return speechVisible && automaticRemarksEnabled;
        }
    }

    private record RemotePreferences(boolean automaticEnabled, int minIntervalMinutes) {}
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    public static final Preferences DEFAULTS = new Preferences(0.85, 0.85, 0.9, true, true, 10);
    private static Preferences current = DEFAULTS;
    private static RemotePreferences sent;
    private static boolean registered;
    private static boolean dirty;
    private static boolean saveFailed;
    private static long saveAfter;
    private static long sendAfter;

    public static void register() {
        if (registered) return;
        registered = true;
        reload();
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            long now = System.currentTimeMillis();
            if (dirty && now >= saveAfter) flush();
            if (client.player != null && now >= sendAfter) syncServer();
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            sent = null;
            sendAfter = System.currentTimeMillis() + 400;
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
            if (client.getConnection() == null || client.getConnection() == handler) {
                sent = null;
                flush();
            }
        }));
    }

    public static Preferences snapshot() { return current; }
    public static boolean saveFailed() { return saveFailed; }

    public static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("wicklen-chronicles-client.json");
    }

    public static void update(Preferences value) {
        Objects.requireNonNull(value);
        if (value.equals(current)) return;
        var before = current;
        current = value;
        dirty = true;
        saveAfter = System.currentTimeMillis() + 500;
        if (before.effectiveAutomatic() != value.effectiveAutomatic()
                || before.minIntervalMinutes() != value.minIntervalMinutes()) {
            // Sliders may update every pixel. Send the final state after the drag has settled.
            sendAfter = System.currentTimeMillis() + 400;
        }
        if (!value.speechVisible()) SpiritSpeechHud.clear();
    }

    public static void setJournalScale(double value) {
        var p = current;
        update(new Preferences(value, p.speechScale(), p.speechOpacity(), p.speechVisible(),
                p.automaticRemarksEnabled(), p.minIntervalMinutes(), p.speechHorizontal(), p.speechVertical()));
    }

    public static void reset() { update(DEFAULTS); }

    /** Invalid files remain intact; presentation falls back safely until an explicit user edit. */
    public static boolean reload() {
        if (!Files.exists(path())) {
            current = DEFAULTS;
            dirty = false;
            saveFailed = false;
            return true;
        }
        try {
            if (Files.size(path()) > 8192) throw new IOException("Oversized presentation preferences");
            var object = JSON.fromJson(Files.readString(path(), StandardCharsets.UTF_8), com.google.gson.JsonObject.class);
            if (object == null) throw new IOException("Empty presentation preferences");
            current = new Preferences(number(object, "journalScale", 0.85), number(object, "speechScale", 0.85),
                    number(object, "speechOpacity", 0.9), bool(object, "speechVisible", true),
                    bool(object, "automaticRemarksEnabled", true), (int) number(object, "minIntervalMinutes", 10),
                    number(object, "speechHorizontal", 0), number(object, "speechVertical", 1));
            dirty = false;
            saveFailed = false;
            if (!current.speechVisible()) SpiritSpeechHud.clear();
            return true;
        } catch (IOException | RuntimeException failure) {
            current = DEFAULTS;
            dirty = false;
            saveFailed = true;
            return false;
        }
    }

    /** Atomic local save; a failed write never replaces the previous complete file. */
    public static boolean flush() {
        if (!dirty) return !saveFailed;
        Path destination = path();
        Path temporary = null;
        try {
            Files.createDirectories(destination.getParent());
            temporary = Files.createTempFile(destination.getParent(), "wicklen-client-", ".tmp");
            String encoded = JSON.toJson(current) + "\n";
            Files.writeString(temporary, encoded, StandardCharsets.UTF_8);
            if (!Files.readString(temporary, StandardCharsets.UTF_8).equals(encoded)) throw new IOException("Incomplete write");
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = false;
            saveFailed = false;
            return true;
        } catch (IOException failure) {
            saveFailed = true;
            saveAfter = System.currentTimeMillis() + 5000;
            return false;
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) {}
        }
    }

    private static void syncServer() {
        var desired = new RemotePreferences(current.effectiveAutomatic(), current.minIntervalMinutes());
        if (desired.equals(sent) || !ClientPlayNetworking.canSend(SpiritSpeechPreferencesC2SPayload.TYPE)) return;
        ClientPlayNetworking.send(new SpiritSpeechPreferencesC2SPayload(desired.automaticEnabled(), desired.minIntervalMinutes()));
        sent = desired;
    }

    private static double number(com.google.gson.JsonObject object, String key, double fallback) {
        return object.has(key) ? object.get(key).getAsDouble() : fallback;
    }

    private static boolean bool(com.google.gson.JsonObject object, String key, boolean fallback) {
        return object.has(key) ? object.get(key).getAsBoolean() : fallback;
    }

    private static double bounded(double value, double minimum, double maximum, double fallback) {
        return Double.isFinite(value) ? Math.max(minimum, Math.min(maximum, value)) : fallback;
    }

    private ClientUiSettings() {}
}
