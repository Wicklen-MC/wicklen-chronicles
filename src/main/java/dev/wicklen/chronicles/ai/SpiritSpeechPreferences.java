package dev.wicklen.chronicles.ai;

import com.google.gson.reflect.TypeToken;
import dev.wicklen.chronicles.net.SpiritSpeechPreferencesC2SPayload;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** World-owned settings and the last automatic attempt. Manual conversations never reserve this interval. */
public final class SpiritSpeechPreferences {
    public static final int DEFAULT_INTERVAL_MINUTES = 10;
    public record Preferences(boolean automaticEnabled, int minIntervalMinutes) {
        public Preferences {
            if (minIntervalMinutes < 2 || minIntervalMinutes > 30) throw new IllegalArgumentException("Automatic interval bounds");
        }
    }
    private record Saved(UUID worldId, UUID ownerId, Preferences preferences, long lastAutomaticAt, long revision) {
        private Saved {
            Objects.requireNonNull(worldId); Objects.requireNonNull(ownerId); Objects.requireNonNull(preferences);
            if (lastAutomaticAt < -1 || revision < 0) throw new IllegalArgumentException("Invalid automatic speech record");
        }
    }
    private record Pending(ServerPlayer actor, UUID session, Preferences preferences) {}
    private static final Preferences DEFAULT = new Preferences(true, DEFAULT_INTERVAL_MINUTES);
    private static final Preferences QUIET = new Preferences(false, DEFAULT_INTERVAL_MINUTES);
    private static final JsonStore<Map<String, Saved>> STORE = new JsonStore<>("speech-preferences.json", new TypeToken<>() {});
    private static final Map<UUID, Pending> PENDING = new HashMap<>();
    private static final Map<UUID, Long> LAST_APPLIED = new HashMap<>();
    private static boolean registered;

    private SpiritSpeechPreferences() {}

    public static void register() {
        if (registered) return;
        registered = true;
        PayloadTypeRegistry.serverboundPlay().register(SpiritSpeechPreferencesC2SPayload.TYPE, SpiritSpeechPreferencesC2SPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(SpiritSpeechPreferencesC2SPayload.TYPE, (packet, context) -> context.server().execute(() -> {
            ServerPlayer actor = context.player();
            if (context.server().getPlayerList().getPlayer(actor.getUUID()) != actor || !actor.connection.isAcceptingMessages()) return;
            PENDING.put(actor.getUUID(), new Pending(actor, WorldStorageContext.sessionId(),
                    new Preferences(packet.automaticEnabled(), packet.minIntervalMinutes())));
        }));
        ServerTickEvents.END_SERVER_TICK.register(SpiritSpeechPreferences::applyPending);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            PENDING.remove(handler.player.getUUID());
            LAST_APPLIED.remove(handler.player.getUUID());
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> { PENDING.clear(); LAST_APPLIED.clear(); });
    }

    /** Reading a legacy player's default does not insert a preference record or reset any clock. */
    public static Preferences current(UUID owner) {
        try { return saved(owner).preferences(); }
        catch (RuntimeException unavailable) { return QUIET; }
    }

    public static boolean update(ServerPlayer actor, boolean automaticEnabled, int minIntervalMinutes) {
        if (!actor.level().getServer().isSameThread()) throw new IllegalStateException("Speech preferences require the server thread");
        return updateActor(actor.getUUID(), new Preferences(automaticEnabled, minIntervalMinutes));
    }

    static boolean updateActor(UUID owner, Preferences preferences) {
        try {
            Saved old = saved(owner);
            if (old.preferences().equals(preferences)) return true;
            return save(owner, old, new Saved(old.worldId(), owner, preferences, old.lastAutomaticAt(), Math.addExact(old.revision(), 1)));
        } catch (RuntimeException unavailable) { return false; }
    }

    public static boolean automaticAllowed(UUID owner, long now) {
        try {
            Saved value = saved(owner);
            return eligible(effective(owner, value.preferences()), value.lastAutomaticAt(), now);
        } catch (RuntimeException unavailable) { return false; }
    }

    static boolean reserveAutomatic(UUID owner, long now) {
        try {
            Saved old = saved(owner);
            if (!eligible(effective(owner, old.preferences()), old.lastAutomaticAt(), now)) return false;
            return save(owner, old, new Saved(old.worldId(), owner, old.preferences(), now, old.revision()));
        } catch (RuntimeException unavailable) { return false; }
    }

    static long revision(UUID owner) {
        try { return saved(owner).revision(); }
        catch (RuntimeException unavailable) { return -1; }
    }

    static boolean deliveryAllowed(UUID owner, long expectedRevision) {
        try {
            Saved value = saved(owner);
            return value.revision() == expectedRevision && effective(owner, value.preferences()).automaticEnabled();
        } catch (RuntimeException unavailable) { return false; }
    }

    static boolean eligible(Preferences preferences, long lastAutomaticAt, long now) {
        return preferences.automaticEnabled() && now >= 0 && (lastAutomaticAt < 0
                || now >= lastAutomaticAt && now - lastAutomaticAt >= preferences.minIntervalMinutes() * 60_000L);
    }

    private static Saved saved(UUID owner) {
        Objects.requireNonNull(owner);
        Map<String,Saved> records = STORE.get(HashMap::new);
        Saved value = records.get(owner.toString());
        if (value == null) {
            if (records.containsKey(owner.toString())) throw new IllegalStateException("Null automatic speech preference; source preserved");
            return new Saved(WorldStorageContext.worldId(), owner, DEFAULT, -1, 0);
        }
        if (!value.worldId().equals(WorldStorageContext.worldId()) || !value.ownerId().equals(owner))
            throw new IllegalStateException("Automatic speech preference identity mismatch; source preserved");
        return value;
    }

    private static Preferences effective(UUID owner, Preferences persisted) {
        Pending pending = PENDING.get(owner);
        return pending != null && WorldStorageContext.matchesSession(pending.session()) ? pending.preferences() : persisted;
    }

    private static boolean save(UUID owner, Saved previous, Saved next) {
        Map<String, Saved> values = STORE.get(HashMap::new);
        Saved existed = values.put(owner.toString(), next);
        try { STORE.saveChecked(); return true; }
        catch (Exception failure) {
            if (existed == null) values.remove(owner.toString()); else values.put(owner.toString(), previous);
            LoggerFactory.getLogger("wicklen_chronicles.speech").warn("Automatic speech preference save deferred; source preserved: {}", failure.toString());
            return false;
        }
    }

    private static void applyPending(MinecraftServer server) {
        long now = System.nanoTime();
        for (var entry : new HashMap<>(PENDING).entrySet()) {
            Pending pending = entry.getValue(); ServerPlayer actor = pending.actor();
            if (!WorldStorageContext.matchesSession(pending.session()) || server.getPlayerList().getPlayer(actor.getUUID()) != actor
                    || !actor.connection.isAcceptingMessages()) { PENDING.remove(entry.getKey(), pending); continue; }
            Long last = LAST_APPLIED.get(entry.getKey());
            if (last != null && now - last < 200_000_000L) continue;
            // Coalesce a quick slider/button burst, keeping its final value instead of dropping it.
            updateActor(entry.getKey(), pending.preferences());
            LAST_APPLIED.put(entry.getKey(), now);
            PENDING.remove(entry.getKey(), pending);
        }
    }
}
