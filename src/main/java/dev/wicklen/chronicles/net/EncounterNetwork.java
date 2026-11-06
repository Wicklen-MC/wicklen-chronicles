package dev.wicklen.chronicles.net;

import dev.wicklen.chronicles.WicklenChronicles;
import dev.wicklen.chronicles.ai.WorldStorageContext;
import dev.wicklen.chronicles.core.discovery.DiscoveryKey;
import dev.wicklen.chronicles.core.discovery.DiscoveryKind;
import dev.wicklen.chronicles.core.discovery.DiscoveryPosition;
import dev.wicklen.chronicles.core.encounter.EncounterMode;
import dev.wicklen.chronicles.encounter.EncounterService;
import dev.wicklen.chronicles.exploration.ExplorationService;
import dev.wicklen.chronicles.station.StationService;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Server-derived eligible courtyards and personally consented encounters only. */
final class EncounterNetwork {
    private static final Map<UUID, Long> LAST_REQUEST = new HashMap<>();

    static void register() {
        PayloadTypeRegistry.serverboundPlay().register(EncounterRequestPayload.TYPE, EncounterRequestPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(EncounterPagePayload.TYPE, EncounterPagePayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(EncounterRequestPayload.TYPE, (request, context) ->
            context.server().execute(() -> handle(context.player(), request)));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> LAST_REQUEST.remove(handler.player.getUUID()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> LAST_REQUEST.clear());
    }

    private static void handle(ServerPlayer player, EncounterRequestPayload request) {
        if (player.level().getServer().getPlayerList().getPlayer(player.getUUID()) != player
                || !player.connection.isAcceptingMessages()) return;
        if (!ServerPlayNetworking.canSend(player, EncounterPagePayload.TYPE)) return;
        Long previous = LAST_REQUEST.put(player.getUUID(), System.nanoTime());
        if (previous != null && System.nanoTime() - previous < 150_000_000L) {
            ServerPlayNetworking.send(player, new EncounterPagePayload(request.requestId(), null, "", true));
            return;
        }
        String feedback = "";
        boolean error = false;
        try {
            if (request.action() != EncounterRequestPayload.Action.READ) {
                var result = switch (request.action()) {
                    case PREPARE_NORMAL, PREPARE_EMPOWERED -> EncounterService.prepare(player,
                        new DiscoveryKey(DiscoveryKind.PLACE, request.expectedId()),
                        request.action() == EncounterRequestPayload.Action.PREPARE_NORMAL ? EncounterMode.NORMAL : EncounterMode.EMPOWERED);
                    case START -> EncounterService.start(player, UUID.fromString(request.expectedId()));
                    case LEAVE -> EncounterService.leave(player, UUID.fromString(request.expectedId()));
                    case CLAIM -> EncounterService.claim(player, UUID.fromString(request.expectedId()));
                    case READ -> throw new IllegalStateException("Unexpected read action");
                };
                feedback = result.success() ? successMessage(player, request.action()) : result.message();
                error = !result.success();
            }
            var facts = ExplorationService.snapshot(player);
            var titles = new HashMap<String, String>();
            facts.entries().forEach(entry -> titles.put(entry.key().id(), entry.title()));
            var courtyards = StationService.knownCourtyards(player).stream().map(place ->
                new EncounterPagePayload.Courtyard(place.key().id(), titles.getOrDefault(place.key().id(), "Known courtyard"),
                    new DiscoveryPosition(place.dimension(), place.anchor().x(), place.anchor().y(), place.anchor().z()))).toList();
            var encounters = EncounterService.knownEncounters(player).stream().map(view -> new EncounterPagePayload.Row(
                view.id(), titles.getOrDefault(view.place().id(), "Known courtyard"), view.mode().name(), view.status(),
                view.leader(), view.participants().size(), view.rewards())).toList();
            var data = new EncounterPagePayload.Data(WorldStorageContext.worldId(), player.getUUID(),
                EncounterService.gatewayAvailable(player.level().getServer()), courtyards, encounters);
            ServerPlayNetworking.send(player, new EncounterPagePayload(request.requestId(), data,
                feedback.substring(0, Math.min(500, feedback.length())), error));
        } catch (IllegalStateException | IllegalArgumentException failure) {
            WicklenChronicles.LOGGER.warn("Encounter journal unavailable: {}", failure.toString());
            ServerPlayNetworking.send(player, new EncounterPagePayload(request.requestId(), null, "", true));
        }
    }

    private static String successMessage(ServerPlayer player, EncounterRequestPayload.Action action) {
        boolean ru = player.clientInformation().language().startsWith("ru");
        return switch (action) {
            case PREPARE_NORMAL, PREPARE_EMPOWERED -> ru
                ? "Подготовка сохранена. Испытание начнётся только после явного запуска." : "Preparation saved. The trial starts only when you explicitly begin it.";
            case START -> ru ? "Испытание началось." : "The trial has started.";
            case LEAVE -> ru ? "Встреча отменена без штрафа и награды." : "The encounter was cancelled without a penalty or reward.";
            case CLAIM -> ru ? "Реликвия получена." : "Relic collected.";
            case READ -> "";
        };
    }

    private EncounterNetwork() {}
}
