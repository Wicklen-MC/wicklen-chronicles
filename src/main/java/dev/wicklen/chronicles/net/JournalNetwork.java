package dev.wicklen.chronicles.net;

import dev.wicklen.chronicles.WicklenChronicles;
import dev.wicklen.chronicles.ai.SpiritQuests;
import dev.wicklen.chronicles.exploration.ExplorationService;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Narrow server authority for journal reads and existing quest actions. */
public final class JournalNetwork {
    public static final int PAGE_SIZE = 5;
    private static final Map<UUID, Long> LAST_REQUEST = new HashMap<>();
    private static final long REQUEST_INTERVAL_NANOS = 150_000_000L;

    public static void register() {
        StoryNetwork.register();
        ResearchNetwork.register();
        EncounterNetwork.register();
        PayloadTypeRegistry.serverboundPlay().register(JournalRequestPayload.TYPE, JournalRequestPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(JournalPagePayload.TYPE, JournalPagePayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(JournalRequestPayload.TYPE, (request, context) ->
            context.server().execute(() -> handle(context.player(), request)));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> LAST_REQUEST.remove(handler.player.getUUID()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> clear());
    }

    public static void clear() { LAST_REQUEST.clear(); }

    private static void handle(ServerPlayer player, JournalRequestPayload request) {
        if (player.level().getServer().getPlayerList().getPlayer(player.getUUID()) != player
                || !player.connection.isAcceptingMessages()) return;
        if (!ServerPlayNetworking.canSend(player, JournalPagePayload.TYPE)) return;
        long now = System.nanoTime();
        Long previous = LAST_REQUEST.put(player.getUUID(), now);
        if (previous != null && now - previous < REQUEST_INTERVAL_NANOS) {
            send(player, new JournalPagePayload(request.requestId(), request.section(), request.page(), null,
                "journal.wicklen_chronicles.wait", "", true));
            return;
        }
        String notice = "";
        String detail = "";
        boolean error = false;
        try {
            if (!player.isAlive() || player.isSpectator()) {
                notice = "journal.wicklen_chronicles.action_unavailable";
                error = true;
            } else {
                switch (request.action()) {
                    case REFRESH -> {}
                    case TAKE_QUEST -> {
                        if (SpiritQuests.issue(player.getUUID()) == null) {
                            notice = "journal.wicklen_chronicles.already_active";
                            error = true;
                        } else notice = "journal.wicklen_chronicles.taken";
                    }
                    case TURN_IN, CLAIM_REWARD -> {
                        UUID expected;
                        try { expected = UUID.fromString(request.expectedId()); }
                        catch (IllegalArgumentException e) {
                            send(player, new JournalPagePayload(request.requestId(), request.section(), request.page(), null,
                                "journal.wicklen_chronicles.stale", "", true));
                            return;
                        }
                        var result = request.action() == JournalRequestPayload.Action.TURN_IN
                            ? SpiritQuests.turnIn(player, expected) : SpiritQuests.claim(player, expected);
                        error = !result.success();
                        if (!error) notice = request.action() == JournalRequestPayload.Action.TURN_IN
                            ? "journal.wicklen_chronicles.turned_in" : "journal.wicklen_chronicles.claimed";
                        else if (result.message().contains("места")) notice = "journal.wicklen_chronicles.inventory_full";
                        else if (result.message().contains("устаревшее")) notice = "journal.wicklen_chronicles.stale";
                        else if (result.message().contains("Условия")) notice = "journal.wicklen_chronicles.not_ready";
                        else {
                            notice = "journal.wicklen_chronicles.action_failed";
                            detail = result.message().substring(0, Math.min(500, result.message().length()));
                        }
                    }
                }
            }
            SpiritQuests.tickProgress(player);
            int offset = request.page() * PAGE_SIZE;
            var view = ExplorationService.journal(player,
                request.section() == JournalRequestPayload.Section.ATLAS ? offset : 0,
                request.section() == JournalRequestPayload.Section.HISTORY ? offset : 0, PAGE_SIZE);
            int actualPage = switch (request.section()) {
                case ATLAS -> view.discoveryOffset() / PAGE_SIZE;
                case HISTORY -> view.historyOffset() / PAGE_SIZE;
                case TASKS -> 0;
            };
            send(player, new JournalPagePayload(request.requestId(), request.section(), actualPage, view, notice, detail, error));
        } catch (IllegalStateException | IllegalArgumentException e) {
            WicklenChronicles.LOGGER.warn("[wicklen-chronicles] journal action/read unavailable: {}", e.toString());
            send(player, new JournalPagePayload(request.requestId(), request.section(), request.page(), null,
                "journal.wicklen_chronicles.unavailable", "", true));
        }
    }

    private static void send(ServerPlayer player, JournalPagePayload response) {
        ServerPlayNetworking.send(player, response);
    }

    private JournalNetwork() {}
}
