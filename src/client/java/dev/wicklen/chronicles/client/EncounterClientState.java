package dev.wicklen.chronicles.client;

import dev.wicklen.chronicles.net.EncounterPagePayload;
import dev.wicklen.chronicles.net.EncounterRequestPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import java.util.UUID;

final class EncounterClientState {
    private static EncounterPagePayload response;
    private static final JournalRequestGate<EncounterRequestPayload> REQUESTS = new JournalRequestGate<>();
    private static long revision;
    private static EncounterRequestPayload.Action lastAction = EncounterRequestPayload.Action.READ;

    static EncounterPagePayload.Data data() { return response == null ? null : response.data(); }
    static EncounterPagePayload response() { return response; }
    static boolean busy() { return REQUESTS.busy(); }
    static long revision() { return revision; }
    static EncounterRequestPayload.Action lastAction() { return lastAction; }

    static void request(EncounterRequestPayload.Action action, String expectedId) {
        if (REQUESTS.busy()) return;
        if (!ClientPlayNetworking.canSend(EncounterRequestPayload.TYPE)) return;
        UUID requestId = UUID.randomUUID();
        lastAction = action;
        REQUESTS.offer(requestId, new EncounterRequestPayload(requestId, action, expectedId));
        revision++;
    }
    static void accept(EncounterPagePayload page) {
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
    private EncounterClientState() {}
}
