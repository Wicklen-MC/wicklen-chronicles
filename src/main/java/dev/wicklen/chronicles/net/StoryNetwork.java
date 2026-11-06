package dev.wicklen.chronicles.net;

import dev.wicklen.chronicles.WicklenChronicles;
import dev.wicklen.chronicles.ai.WorldStorageContext;
import dev.wicklen.chronicles.story.StoryService;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Story requests are separate from fast journal paging; a long LLM call never blocks the atlas. */
final class StoryNetwork {
    private static final Map<UUID, Long> LAST_REQUEST = new HashMap<>();

    static void register() {
        PayloadTypeRegistry.serverboundPlay().register(StoryRequestPayload.TYPE, StoryRequestPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(StoryPagePayload.TYPE, StoryPagePayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(StoryRequestPayload.TYPE, (request, context) ->
            context.server().execute(() -> handle(context.player(), request)));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
            LAST_REQUEST.remove(handler.player.getUUID()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> LAST_REQUEST.clear());
    }

    private static void handle(ServerPlayer player, StoryRequestPayload request) {
        if (player.level().getServer().getPlayerList().getPlayer(player.getUUID()) != player
                || !player.connection.isAcceptingMessages()) return;
        if (!ServerPlayNetworking.canSend(player, StoryPagePayload.TYPE)) return;
        long now = System.nanoTime();
        Long previous = LAST_REQUEST.put(player.getUUID(), now);
        if (previous != null && now - previous < 150_000_000L) {
            reply(player, request, "", true);
            return;
        }
        try {
            StoryService.recover(player);
            switch (request.action()) {
                case READ -> reply(player, request, "", false);
                case REQUEST -> {
                    if (!player.isAlive() || player.isSpectator()) {
                        reply(player, request, "", true);
                        return;
                    }
                    var server = player.level().getServer();
                    UUID owner = player.getUUID();
                    UUID session = WorldStorageContext.sessionId();
                    var connection = player.connection;
                    StoryService.request(player, feedback -> {
                        if (!WorldStorageContext.matchesSession(session)) return;
                        var current = server.getPlayerList().getPlayer(owner);
                        if (current == null || current.connection != connection || !current.connection.isAcceptingMessages()) return;
                        boolean failed = StoryService.view(current).pending() == null;
                        reply(current, request, feedback, failed);
                    });
                }
                case CHOOSE -> {
                    if (!player.isAlive() || player.isSpectator()) {
                        reply(player, request, "", true);
                        return;
                    }
                    var result = StoryService.choose(player, UUID.fromString(request.expectedPlanId()), request.choiceId());
                    reply(player, request, result.message(), !result.success());
                }
            }
        } catch (IllegalStateException | IllegalArgumentException error) {
            WicklenChronicles.LOGGER.warn("Story journal unavailable: {}", error.toString());
            ServerPlayNetworking.send(player, new StoryPagePayload(request.requestId(), null, "", true));
        }
    }

    private static void reply(ServerPlayer player, StoryRequestPayload request, String feedback, boolean error) {
        if (!ServerPlayNetworking.canSend(player, StoryPagePayload.TYPE)) return;
        try {
            ServerPlayNetworking.send(player, new StoryPagePayload(request.requestId(), StoryService.view(player),
                feedback.substring(0, Math.min(500, feedback.length())), error));
        } catch (RuntimeException failure) {
            WicklenChronicles.LOGGER.warn("Story page unavailable: {}", failure.toString());
            ServerPlayNetworking.send(player, new StoryPagePayload(request.requestId(), null, "", true));
        }
    }

    private StoryNetwork() {}
}
