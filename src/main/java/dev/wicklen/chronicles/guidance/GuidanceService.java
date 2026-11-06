package dev.wicklen.chronicles.guidance;

import dev.wicklen.chronicles.WicklenChronicles;
import dev.wicklen.chronicles.ai.WorldStorageContext;
import dev.wicklen.chronicles.annotation.AnnotationService;
import dev.wicklen.chronicles.core.discovery.DiscoveryEntry;
import dev.wicklen.chronicles.core.discovery.DiscoveryKey;
import dev.wicklen.chronicles.core.discovery.DiscoveryKind;
import dev.wicklen.chronicles.core.field.FirstLightState;
import dev.wicklen.chronicles.core.guidance.*;
import dev.wicklen.chronicles.encounter.EncounterService;
import dev.wicklen.chronicles.exploration.ExplorationService;
import dev.wicklen.chronicles.firstlight.FirstLightService;
import dev.wicklen.chronicles.net.GuidanceC2SPayload;
import dev.wicklen.chronicles.net.GuidanceS2CPayload;
import dev.wicklen.chronicles.station.StationVariant;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Resolve one personal direction from saved intentions and confirmed facts. Never locates or loads terrain. */
public final class GuidanceService {
    public record View(GuidanceIntent intent, GuidanceTarget target, boolean enabled,
                       boolean paused, String noticeKey, boolean error) {}

    /** Safe narrative context: identity and coordinates intentionally are not fields of this projection. */
    public record Summary(String state, String kind, String label, String canonicalLabel,
                          boolean otherDimension) {}

    private static final long UPDATE_NANOS = 1_000_000_000L;
    private static final long ACTION_NANOS = 200_000_000L;
    private static final Map<UUID, Long> LAST_ACTION = new HashMap<>();
    private static final Map<UUID, Long> LAST_SENT = new HashMap<>();
    private static GuidanceRepository repository;
    private static UUID repositorySession;
    private static boolean registered;

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        PayloadTypeRegistry.serverboundPlay().register(GuidanceC2SPayload.TYPE, GuidanceC2SPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(GuidanceS2CPayload.TYPE, GuidanceS2CPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(GuidanceC2SPayload.TYPE, (request, context) ->
                context.server().execute(() -> handle(context.player(), request)));
        ServerTickEvents.END_SERVER_TICK.register(GuidanceService::tick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            LAST_ACTION.remove(handler.player.getUUID());
            LAST_SENT.remove(handler.player.getUUID());
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            LAST_ACTION.clear();
            LAST_SENT.clear();
            repository = null;
            repositorySession = null;
        });
    }

    private static GuidanceRepository repository() {
        UUID session = WorldStorageContext.sessionId();
        if (!WorldStorageContext.matchesSession(session)) {
            throw new IllegalStateException("Guidance world is not active");
        }
        if (repository == null || !Objects.equals(repositorySession, session)) {
            repository = new GuidanceRepository();
            repositorySession = session;
        }
        return repository;
    }

    public static GuidanceIntent current(ServerPlayer player) {
        requireServerThread(player);
        return repository().current(player.getUUID());
    }

    public static DiscoveryKey pinnedKey(ServerPlayer player) {
        GuidanceIntent intent = current(player);
        if (intent.mode() != GuidanceIntent.Mode.PIN_PLACE) {
            return null;
        }
        DiscoveryEntry entry = knownPlace(player, intent.placeId());
        return entry == null ? null : entry.key();
    }

    public static View readmodel(ServerPlayer player) {
        return readmodel(player, true);
    }

    private static View readmodel(ServerPlayer player, boolean reconcile) {
        GuidanceIntent intent = current(player);
        if (!intent.enabled()) {
            return new View(intent, null, false, false, notice("disabled"), false);
        }
        GuidanceTarget target = intent.mode() == GuidanceIntent.Mode.PIN_PLACE
                ? pinnedTarget(player, intent.placeId()) : expeditionTarget(player, reconcile);
        boolean paused = target != null && EncounterService.knownEncounters(player).stream()
                .anyMatch(encounter -> encounter.status().equals("ACTIVE")
                        && encounter.participants().contains(player.getUUID()));
        boolean surfaceRequired = target != null && target.kind() == GuidanceTarget.Kind.SURVEY
                && !target.otherDimension() && (!dev.wicklen.chronicles.terrain.TerrainMapService.isSurface(player)
                || !player.onGround() && !player.isCreative());
        String feedback = paused ? notice("paused")
                : target != null && target.otherDimension() ? notice("other_dimension")
                : surfaceRequired ? notice("surface_required")
                : target == null ? notice(intent.mode() == GuidanceIntent.Mode.PIN_PLACE ? "place_unavailable" : "no_expedition") : "";
        return new View(intent, target, true, paused, feedback, false);
    }

    public static Summary narrative(ServerPlayer player) {
        try {
            View view = readmodel(player, false);
            GuidanceTarget target = view.target();
            String state = !view.enabled() ? "disabled" : view.paused() ? "paused" : target == null ? "none" : "active";
            return new Summary(state, target == null ? "" : target.kind().name(), target == null ? "" : target.label(),
                    target == null ? "" : target.canonicalLabel(), target != null && target.otherDimension());
        } catch (RuntimeException error) {
            return new Summary("unavailable", "", "", "", false);
        }
    }

    /** Trusted server API; the network handler separately validates the live connection and request rate. */
    public static View apply(ServerPlayer player, UUID expectedWorld, GuidanceC2SPayload.Action action, String placeId) throws IOException {
        GuidanceIntent old = current(player);
        if (action == GuidanceC2SPayload.Action.READ) {
            if (expectedWorld != null && !old.worldId().equals(expectedWorld)) {
                throw new IllegalArgumentException("Foreign guidance read");
            }
            return readmodel(player);
        }
        GuidanceIntent next = switch (action) {
            case CLEAR -> GuidanceTransitions.clear(old, player.getUUID(), expectedWorld);
            case FOLLOW_EXPEDITION -> GuidanceTransitions.follow(old, player.getUUID(), expectedWorld);
            case PIN_PLACE -> GuidanceTransitions.pin(old, player.getUUID(), expectedWorld,
                    ExplorationService.snapshot(player), player.level().dimension().identifier().toString(), placeId);
            case READ -> throw new IllegalStateException("Read was handled before mutations");
        };
        if (!next.equals(old)) {
            repository().save(player.getUUID(), old, next);
        }
        return readmodel(player);
    }

    private static DiscoveryEntry knownPlace(ServerPlayer player, String id) {
        return ExplorationService.snapshot(player).entries().stream()
                .filter(entry -> entry.key().kind() == DiscoveryKind.PLACE && entry.key().id().equals(id))
                .findFirst().orElse(null);
    }

    private static GuidanceTarget pinnedTarget(ServerPlayer player, String placeId) {
        DiscoveryEntry entry = knownPlace(player, placeId);
        if (entry == null) {
            return null;
        }
        var position = entry.position();
        String canonical = canonical(player, entry);
        return new GuidanceTarget(entry.key().id(), position.dimension(), position.x() + 0.5, position.y(), position.z() + 0.5,
                personalLabel(player, entry, canonical), canonical, GuidanceTarget.Kind.PLACE,
                !position.dimension().equals(player.level().dimension().identifier().toString()), true);
    }

    private static GuidanceTarget expeditionTarget(ServerPlayer player, boolean reconcile) {
        FirstLightState thread = FirstLightService.current(player);
        if (thread == null) {
            return null;
        }
        // Reconcile already-existing progress after a save handoff; never offer a new story from a guidance read.
        if (reconcile) {
            thread = FirstLightService.view(player);
        }
        if (thread.status() != FirstLightState.Status.ACTIVE
                || thread.branch() != FirstLightState.Branch.SURVEY && thread.branch() != FirstLightState.Branch.ENCOUNTER) {
            return null;
        }
        DiscoveryEntry source = knownPlace(player, thread.source().id());
        if (source == null) {
            return null;
        }
        String canonical = canonical(player, source);
        String label = personalLabel(player, source, canonical);
        String dimension = thread.sourcePosition().dimension();
        boolean otherDimension = !dimension.equals(player.level().dimension().identifier().toString());
        if (thread.branch() == FirstLightState.Branch.ENCOUNTER) {
            var point = thread.sourcePosition();
            return new GuidanceTarget(source.key().id(), dimension, point.x() + 0.5, point.y(), point.z() + 0.5,
                    label, canonical, GuidanceTarget.Kind.ENCOUNTER, otherDimension, true);
        }
        double playerX = otherDimension ? thread.sourcePosition().x() : player.getX();
        double playerZ = otherDimension ? thread.sourcePosition().z() : player.getZ();
        var point = GuidanceGeometry.survey(thread.area(), thread.sourcePosition(), thread.marks(), playerX, playerZ);
        boolean russian = russian(player);
        String areaLabel = russian ? "Область возле «" + label + "»" : "Survey near “" + label + "”";
        if (!thread.marks().isEmpty()) {
            areaLabel = russian ? "Следующая полевая отметка" : "Next field observation";
        }
        return new GuidanceTarget("wicklen_chronicles:survey/" + thread.id() + "/" + thread.marks().size(), dimension,
                point.x(), thread.sourcePosition().y(), point.z(),
                bounded(areaLabel), canonical, GuidanceTarget.Kind.SURVEY, otherDimension, true);
    }

    private static String personalLabel(ServerPlayer player, DiscoveryEntry entry, String canonical) {
        try {
            var annotation = AnnotationService.get(player, entry.key()).orElse(null);
            return annotation == null || annotation.label().isBlank() ? canonical : bounded(annotation.label());
        } catch (RuntimeException unavailable) {
            // Annotation metadata is optional; a confirmed canonical place remains a valid direction.
            return canonical;
        }
    }

    private static String canonical(ServerPlayer player, DiscoveryEntry entry) {
        if (!russian(player)) {
            for (StationVariant variant : StationVariant.values()) {
                if (variant.title().equals(entry.title())) {
                    return switch (variant) {
                        case OVERGROWN_RESEARCH -> "Overgrown research station";
                        case RANSACKED_SUPPLIES -> "Ransacked wayside storehouse";
                        case UNDEAD_COURTYARD -> "Abandoned station";
                    };
                }
            }
        }
        return bounded(entry.title());
    }

    private static String bounded(String text) {
        String clean = text.replaceAll("[\\p{Cntrl}§]", " ").strip();
        if (clean.isEmpty()) {
            return "Recorded place";
        }
        int end = Math.min(160, clean.length());
        if (end < clean.length() && Character.isHighSurrogate(clean.charAt(end - 1))) {
            end--;
        }
        return clean.substring(0, end);
    }

    private static boolean russian(ServerPlayer player) {
        return player.clientInformation().language().startsWith("ru");
    }

    private static String notice(String suffix) {
        return "journal.wicklen_chronicles.guidance." + suffix;
    }

    private static void requireServerThread(ServerPlayer player) {
        if (!player.level().getServer().isSameThread()) {
            throw new IllegalStateException("Guidance requires the server thread");
        }
    }

    private static void tick(MinecraftServer server) {
        if (server.getTickCount() % 20 != 0 || !WorldStorageContext.matchesSession(WorldStorageContext.sessionId())) {
            return;
        }
        long now = System.nanoTime();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (now - LAST_SENT.getOrDefault(player.getUUID(), 0L) >= UPDATE_NANOS
                    && ServerPlayNetworking.canSend(player, GuidanceS2CPayload.TYPE)) {
                send(player, GuidanceS2CPayload.PUSH_ID, "", false);
            }
        }
    }

    private static void handle(ServerPlayer player, GuidanceC2SPayload request) {
        if (player.level().getServer().getPlayerList().getPlayer(player.getUUID()) != player
                || !player.connection.isAcceptingMessages() || !ServerPlayNetworking.canSend(player, GuidanceS2CPayload.TYPE)) {
            return;
        }
        long now = System.nanoTime();
        Long previous = LAST_ACTION.put(player.getUUID(), now);
        if (previous != null && now - previous < ACTION_NANOS) {
            send(player, request.requestId(), notice("wait"), true);
            return;
        }
        String feedback = "";
        boolean error = false;
        try {
            apply(player, request.expectedWorldId(), request.action(), request.placeId());
        } catch (IllegalArgumentException rejected) {
            feedback = notice("rejected");
            error = true;
        } catch (IOException | RuntimeException unavailable) {
            feedback = notice("unavailable");
            error = true;
            WicklenChronicles.LOGGER.warn("Guidance action deferred; previous intention preserved: {}", unavailable.toString());
        }
        send(player, request.requestId(), feedback, error);
    }

    private static void send(ServerPlayer player, UUID requestId, String feedback, boolean error) {
        if (!WorldStorageContext.matchesSession(WorldStorageContext.sessionId()) || !player.connection.isAcceptingMessages()) {
            return;
        }
        try {
            View view = readmodel(player);
            ServerPlayNetworking.send(player, new GuidanceS2CPayload(requestId, view.intent().worldId(), player.getUUID(),
                    WorldStorageContext.sessionId(), view.intent().revision(), view.enabled(), view.paused(), view.target(),
                    feedback.isEmpty() ? view.noticeKey() : feedback, error || view.error()));
        } catch (RuntimeException unavailable) {
            ServerPlayNetworking.send(player, new GuidanceS2CPayload(requestId, WorldStorageContext.worldId(), player.getUUID(),
                    WorldStorageContext.sessionId(), 0, false, false, null, notice("unavailable"), true));
        }
        LAST_SENT.put(player.getUUID(), System.nanoTime());
    }

    private GuidanceService() {}
}
