package dev.wicklen.chronicles.client;

import dev.wicklen.chronicles.core.journal.JournalView;
import dev.wicklen.chronicles.net.JournalPagePayload;
import dev.wicklen.chronicles.net.JournalRequestPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import java.util.UUID;

/** Read-only cache for this connection. Server responses are never treated as local quest commands. */
public final class JournalClientState {
    private static JournalPagePayload response;
    private static final JournalRequestGate<JournalRequestPayload> REQUESTS = new JournalRequestGate<>();
    private static long revision;
    private static String localNotice = "";

    public static JournalView view() { return response == null ? null : response.view(); }
    public static JournalPagePayload response() { return response; }
    public static long revision() { return revision; }
    public static boolean busy() { return REQUESTS.busy(); }
    public static String localNotice() { return localNotice; }

    public static boolean request(JournalRequestPayload.Action action, JournalRequestPayload.Section section,
                                  int page, String expectedId) {
        if (REQUESTS.busy()) return false;
        if (!ClientPlayNetworking.canSend(JournalRequestPayload.TYPE)) {
            localNotice = "journal.wicklen_chronicles.unsupported";
            revision++;
            return false;
        }
        UUID requestId = UUID.randomUUID();
        localNotice = "";
        REQUESTS.offer(requestId, new JournalRequestPayload(requestId, action, section, page, expectedId));
        revision++;
        return true;
    }

    public static void accept(JournalPagePayload page) {
        if (!REQUESTS.accept(page.requestId())) return;
        response = page;
        localNotice = "";
        revision++;
    }

    public static void tick() {
        if (REQUESTS.tick(8_000)) {
            localNotice = "journal.wicklen_chronicles.timeout";
            revision++;
        }
    }

    public static void clear() {
        response = null;
        REQUESTS.clear();
        localNotice = "";
        revision++;
    }

    private JournalClientState() {}
}
