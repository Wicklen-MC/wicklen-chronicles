package dev.wicklen.chronicles.net;

import dev.wicklen.chronicles.WicklenChronicles;
import dev.wicklen.chronicles.core.discovery.DiscoveryEntry;
import dev.wicklen.chronicles.core.discovery.DiscoveryKind;
import dev.wicklen.chronicles.exploration.ExplorationService;
import dev.wicklen.chronicles.research.ResearchService;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

final class ResearchNetwork {
    private static final Map<UUID, Long> LAST_REQUEST = new HashMap<>();

    static void register() {
        ResearchService.register();
        PayloadTypeRegistry.serverboundPlay().register(ResearchRequestPayload.TYPE, ResearchRequestPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ResearchPagePayload.TYPE, ResearchPagePayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ResearchRequestPayload.TYPE, (request, context) ->
            context.server().execute(() -> handle(context.player(), request)));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> LAST_REQUEST.remove(handler.player.getUUID()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> LAST_REQUEST.clear());
    }

    private static void handle(ServerPlayer player, ResearchRequestPayload request) {
        if (player.level().getServer().getPlayerList().getPlayer(player.getUUID()) != player
                || !player.connection.isAcceptingMessages()) return;
        if (!ServerPlayNetworking.canSend(player, ResearchPagePayload.TYPE)) return;
        Long old = LAST_REQUEST.put(player.getUUID(), System.nanoTime());
        if (old != null && System.nanoTime() - old < 150_000_000L) {
            ServerPlayNetworking.send(player, new ResearchPagePayload(request.requestId(), null,
                "journal.wicklen_chronicles.wait", true));
            return;
        }
        String notice = "";
        boolean error = false;
        try {
            if (request.action() == ResearchRequestPayload.Action.CLAIM) {
                var result = ResearchService.claim(player, UUID.fromString(request.expectedRewardId()));
                error = result != ResearchService.ClaimResult.CLAIMED;
                notice = switch (result) {
                    case CLAIMED -> "research.claimed";
                    case FULL -> "inventory_full";
                    case SAVE_FAILED -> "unavailable";
                    case REJECTED -> "research.rejected";
                };
            }
            var research = ResearchService.view(player);
            var snapshot = ExplorationService.snapshot(player);
            String dimension = player.level().dimension().identifier().toString();
            var known = snapshot.entries().stream().filter(entry -> entry.key().kind() == DiscoveryKind.PLACE
                && entry.position().dimension().equals(dimension)).toList();
            var nearest = known.stream().sorted(Comparator.comparingDouble(entry -> {
                double dx = entry.position().x() - player.getX();
                double dz = entry.position().z() - player.getZ();
                return dx * dx + dz * dz;
            })).limit(32).map(entry -> new DiscoveryEntry(entry.key(), entry.title(),
                entry.summary().substring(0, Math.min(256, entry.summary().length())), entry.position(),
                entry.firstObservedAt(), null)).toList();
            var data = new ResearchPagePayload.Data(snapshot.worldId(), player.getUUID(), research.progress(), nearest, known.size());
            ServerPlayNetworking.send(player, new ResearchPagePayload(request.requestId(), data,
                notice.isEmpty() ? "" : "journal.wicklen_chronicles." + notice, error));
        } catch (IllegalStateException | IllegalArgumentException failure) {
            WicklenChronicles.LOGGER.warn("Research/map page unavailable: {}", failure.toString());
            ServerPlayNetworking.send(player, new ResearchPagePayload(request.requestId(), null,
                "journal.wicklen_chronicles.unavailable", true));
        }
    }

    private ResearchNetwork() {}
}
