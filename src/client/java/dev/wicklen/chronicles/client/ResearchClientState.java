package dev.wicklen.chronicles.client;

import dev.wicklen.chronicles.net.ResearchPagePayload;
import dev.wicklen.chronicles.net.ResearchRequestPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import java.util.UUID;

final class ResearchClientState {
    private static ResearchPagePayload response;
    private static final JournalRequestGate<ResearchRequestPayload> REQUESTS = new JournalRequestGate<>();
    private static long revision;

    static ResearchPagePayload.Data data() { return response == null ? null : response.data(); }
    static ResearchPagePayload response() { return response; }
    static boolean busy() { return REQUESTS.busy(); }
    static long revision() { return revision; }

    static void request(ResearchRequestPayload.Action action, String rewardId) {
        if (REQUESTS.busy()) return;
        if (!ClientPlayNetworking.canSend(ResearchRequestPayload.TYPE)) return;
        UUID requestId = UUID.randomUUID();
        REQUESTS.offer(requestId, new ResearchRequestPayload(requestId, action, rewardId));
        revision++;
    }
    static void accept(ResearchPagePayload page) {
        if (!REQUESTS.accept(page.requestId())) return;
        response = page;
        revision++;
    }
    static void tick() {
        if (REQUESTS.tick(8_000)) {
            revision++;
        }
    }
    static void clear() {
        response = null;
        REQUESTS.clear();
        revision++;
    }
    private ResearchClientState() {}
}
