package dev.wicklen.chronicles.encounter;

import dev.wicklen.chronicles.core.encounter.EncounterInstance;
import dev.wicklen.chronicles.core.encounter.EncounterMode;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/** Optional public-API adapter. No optional mod class occurs in a compiled signature. */
final class GatewayBridge {
    private static final String PREFIX = "de.mrstilu2k6.gateway_of_doom.";
    private static volatile Consumer<Completion> completionListener = ignored -> {};
    private static boolean lifecycleRegistered;
    private Class<?> api;
    private String unavailable;

    record Completion(MinecraftServer server, String dimension, UUID portalId, String sourceId, String outcome) {}
    record OpenResult(UUID portalId, String failure) { boolean success() { return portalId != null; } }

    GatewayBridge(Consumer<Completion> listener) {
        completionListener = listener;
        if (!FabricLoader.getInstance().isModLoaded("gateway_of_doom")) {
            unavailable = "Gateway of Doom не установлен";
            return;
        }
        try {
            api = type("api.GatewayApi");
            if (api.getField("API_VERSION").getInt(null) < 1) throw new ReflectiveOperationException("Unsupported Gateway API");
            registerProfiles();
            registerLifecycle();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            unavailable = "Gateway API недоступен: " + rootCause(error).getClass().getSimpleName();
            api = null;
        }
    }

    boolean available() { return api != null && unavailable == null; }
    String unavailableReason() { return unavailable == null ? "Gateway недоступен" : unavailable; }
    static void clearListener() { completionListener = ignored -> {}; }

    static String sourceId(UUID encounterId) { return "wicklen_chronicles:encounter/" + encounterId; }

    OpenResult open(EncounterInstance instance, UUID leader, ServerLevel level, Vec3 anchor) {
        if (!available()) return new OpenResult(null, unavailableReason());
        try {
            String profileId = profileId(instance.mode());
            String snapshot = authoredSnapshot(instance.mode(), instance.participants().size());
            var requestClass = type("api.GatewaySpawnRequest");
            var builder = requestClass.getMethod("builder", ServerLevel.class, Vec3.class, String.class)
                    .invoke(null, level, anchor, profileId);
            invoke(builder, "owner", new Class<?>[]{UUID.class}, leader);
            var source = type("api.GatewaySpawnRequest$Source");
            invoke(builder, "source", new Class<?>[]{source}, enumValue(source, "API"));
            invoke(builder, "sourceId", new Class<?>[]{String.class}, sourceId(instance.id()));
            var placement = type("api.GatewaySpawnRequest$Placement");
            invoke(builder, "placement", new Class<?>[]{placement}, enumValue(placement, "EXACT"));
            invoke(builder, "profileSnapshot", new Class<?>[]{String.class}, snapshot);
            invoke(builder, "respectNightRule", new Class<?>[]{boolean.class}, false);
            invoke(builder, "respectCoordinateBounds", new Class<?>[]{boolean.class}, false);
            invoke(builder, "respectPortalLimits", new Class<?>[]{boolean.class}, true);
            Object request = invoke(builder, "build");
            Object result = api.getMethod("open", requestClass).invoke(null, request);
            if (!Boolean.TRUE.equals(invoke(result, "succeeded"))) {
                return new OpenResult(null, "Gateway отказал в запуске: " + invoke(result, "failure"));
            }
            if (!(invoke(result, "portal") instanceof Entity portal) || portal.level() != level
                    || !profileId.equals(invoke(portal, "getProfileId"))
                    || !sourceId(instance.id()).equals(invoke(portal, "getSpawnSourceId"))
                    || !leader.equals(invoke(portal, "getOwnerId"))) {
                closeSource(instance.id());
                return new OpenResult(null, "Gateway вернул неподходящий портал");
            }
            return new OpenResult(portal.getUUID(), null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            closeSource(instance.id());
            return new OpenResult(null, "Gateway не запустился: " + rootCause(error).getClass().getSimpleName());
        }
    }

    List<UUID> trackedMobs(UUID portalId) {
        if (!available() || portalId == null) return List.of();
        try {
            Optional<?> found = (Optional<?>) api.getMethod("find", UUID.class).invoke(null, portalId);
            if (found.isEmpty()) return List.of();
            Object result = invoke(found.get(), "trackedMobIds");
            return ((List<?>) result).stream().map(UUID.class::cast).toList();
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new IllegalStateException("Cannot inspect Gateway wave", rootCause(error));
        }
    }

    boolean exists(UUID portalId) {
        if (!available() || portalId == null) return false;
        try { return ((Optional<?>) api.getMethod("find", UUID.class).invoke(null, portalId)).isPresent(); }
        catch (ReflectiveOperationException | RuntimeException error) { return false; }
    }

    void closeSource(UUID encounterId) {
        if (!available()) return;
        try {
            Object active = api.getMethod("activeGatewaysBySourceId", String.class).invoke(null, sourceId(encounterId));
            for (Object portal : List.copyOf((List<?>) active)) closePortal(portal);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Runtime will keep the encounter aborted; it never converts cleanup failure into a reward.
        }
    }

    void closeLoadedOwnedPortal(Entity entity) {
        if (!available() || !entity.getClass().getName().equals(PREFIX + "entity.GatewayPortalEntity")) return;
        try {
            String source = (String) invoke(entity, "getSpawnSourceId");
            if (source != null && source.startsWith("wicklen_chronicles:encounter/")) closePortal(entity);
        } catch (ReflectiveOperationException | RuntimeException ignored) {}
    }

    String sourceOf(Entity entity) {
        if (!available() || !entity.getClass().getName().equals(PREFIX + "entity.GatewayPortalEntity")) return null;
        try { return (String) invoke(entity, "getSpawnSourceId"); }
        catch (ReflectiveOperationException | RuntimeException ignored) { return null; }
    }

    private void closePortal(Object portal) throws ReflectiveOperationException {
        invoke(portal, "clearCurrentWaveMobs");
        invoke(portal, "forceClose");
    }

    private static synchronized void registerLifecycle() throws ReflectiveOperationException {
        if (lifecycleRegistered) return;
        Class<?> lifecycle = type("api.GatewayEvents$Lifecycle");
        Object listener = Proxy.newProxyInstance(lifecycle.getClassLoader(), new Class<?>[]{lifecycle}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "Wicklen Gateway lifecycle adapter";
                    default -> null;
                };
            }
            try {
                if (args != null && args.length == 2 && args[0] instanceof Entity portal
                        && portal.level() instanceof ServerLevel level) {
                    String source = (String) invoke(portal, "getSpawnSourceId");
                    if (source != null && source.startsWith("wicklen_chronicles:encounter/"))
                        completionListener.accept(new Completion(level.getServer(), level.dimension().identifier().toString(),
                            portal.getUUID(), source, ((Enum<?>) args[1]).name()));
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                org.slf4j.LoggerFactory.getLogger("wicklen_chronicles.encounter").warn(
                    "Optional Gateway lifecycle callback ignored after API mismatch", error);
            }
            return null;
        });
        type("api.GatewayEvents").getMethod("registerLifecycle", lifecycle).invoke(null, listener);
        lifecycleRegistered = true;
    }

    private static void registerProfiles() throws ReflectiveOperationException {
        Object global = type("config.GatewayConfig").getMethod("getInstance").invoke(null);
        for (EncounterMode mode : EncounterMode.values()) {
            if (Boolean.TRUE.equals(invoke(global, "hasProfile", new Class<?>[]{String.class}, profileId(mode)))) continue;
            var authored = authoredConfig(mode, 1);
            Object profile = invoke(authored, "getProfile", new Class<?>[]{String.class}, profileId(mode));
            invoke(global, "addProfile", new Class<?>[]{type("config.GatewayConfig$DifficultyConfig")}, profile);
        }
    }

    static String authoredSnapshot(EncounterMode mode, int participants) throws ReflectiveOperationException {
        return (String) invoke(authoredConfig(mode, participants), "exportProfileSnapshot", new Class<?>[]{String.class}, profileId(mode));
    }

    private static Object authoredConfig(EncounterMode mode, int participants) throws ReflectiveOperationException {
        Object config = type("config.GatewayConfig").getConstructor().newInstance();
        String id = profileId(mode);
        Object profile = invoke(config, "newProfile", new Class<?>[]{String.class, String.class, int.class},
                id, "Wicklen — " + (mode == EncounterMode.NORMAL ? "Courtyard" : "Empowered courtyard"), 0xD8A85B);
        if (!Boolean.TRUE.equals(invoke(config, "hasProfile", new Class<?>[]{String.class}, id))) {
            invoke(config, "addProfile", new Class<?>[]{type("config.GatewayConfig$DifficultyConfig")}, profile);
        }
        invoke(invoke(profile, "getBoss"), "setEnabled", new Class<?>[]{boolean.class}, false);
        invoke(profile, "clearRewards");
        invoke(profile, "clearDebuffs");
        invoke(profile, "setCompletionXp", new Class<?>[]{int.class}, 0);
        invoke(profile, "setWaveMobVanillaLootEnabled", new Class<?>[]{boolean.class}, false);
        invoke(profile, "setMobDropsEnabled", new Class<?>[]{boolean.class}, false);
        invoke(profile, "setChestDropsEnabled", new Class<?>[]{boolean.class}, false);
        invoke(profile, "setExtraEquipmentEnabled", new Class<?>[]{boolean.class}, false);
        invoke(profile, "setHealthBoostPercent", new Class<?>[]{double.class}, mode == EncounterMode.EMPOWERED ? 20.0 : 0.0);
        invoke(profile, "setDamageBoostPercent", new Class<?>[]{double.class}, mode == EncounterMode.EMPOWERED ? 10.0 : 0.0);
        invoke(profile, "setSpeedBoostPercent", new Class<?>[]{double.class}, 0.0);
        var waveMode = type("config.GatewayConfig$WaveMode");
        invoke(profile, "setWaveMode", new Class<?>[]{waveMode}, enumValue(waveMode, "FIXED"));
        @SuppressWarnings("unchecked") var waves = (List<Object>) invoke(profile, "getWaves");
        waves.clear();
        int party = Math.max(1, Math.min(4, participants));
        boolean empowered = mode == EncounterMode.EMPOWERED;
        var entry = type("config.GatewayConfig$WaveEntry").getConstructor(String.class, int.class);
        var wave = type("config.GatewayConfig$WaveData").getConstructor(List.class, int.class);
        waves.add(wave.newInstance(List.of(entry.newInstance("minecraft:zombie", (empowered ? 3 : 2) * party),
                entry.newInstance("minecraft:skeleton", party)), 180));
        waves.add(wave.newInstance(List.of(entry.newInstance("minecraft:zombie", (empowered ? 3 : 2) * party),
                entry.newInstance("minecraft:skeleton", (empowered ? 2 : 1) * party)), 180));
        return config;
    }

    static String profileId(EncounterMode mode) {
        return mode == EncounterMode.NORMAL ? "wicklen_courtyard_v1" : "wicklen_courtyard_empowered_v1";
    }

    private static Class<?> type(String suffix) throws ClassNotFoundException { return Class.forName(PREFIX + suffix); }
    private static Object invoke(Object target, String method) throws ReflectiveOperationException { return invoke(target, method, new Class<?>[0]); }
    private static Object invoke(Object target, String method, Class<?>[] signature, Object... args) throws ReflectiveOperationException {
        return target.getClass().getMethod(method, signature).invoke(target, args);
    }
    private static Object enumValue(Class<?> type, String name) {
        for (Object value : type.getEnumConstants()) if (((Enum<?>) value).name().equals(name)) return value;
        throw new IllegalArgumentException("Missing Gateway API enum " + name);
    }
    private static Throwable rootCause(Throwable error) {
        while (error instanceof InvocationTargetException && error.getCause() != null) error = error.getCause();
        return error;
    }
}
