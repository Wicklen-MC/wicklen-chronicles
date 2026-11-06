package dev.wicklen.chronicles.client;

import dev.wicklen.chronicles.core.story.StorySnapshot;
import dev.wicklen.chronicles.net.StoryPagePayload;
import dev.wicklen.chronicles.net.StoryRequestPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import java.util.UUID;

/** A separate pending request lets the player keep using the rest of the journal during an LLM call. */
final class StoryClientState {
    private static StorySnapshot story;
    private static final JournalRequestGate<StoryRequestPayload> REQUESTS = new JournalRequestGate<>();
    private static long revision;
    private static String feedback = "";
    private static boolean error;

    static StorySnapshot story() { return story; }
    static boolean busy() { return REQUESTS.busy(); }
    static long revision() { return revision; }
    static String feedback() { return feedback; }
    static boolean error() { return error; }

    static void request(StoryRequestPayload.Action action, String planId, String choiceId) {
        if (REQUESTS.busy()) return;
        if (!ClientPlayNetworking.canSend(StoryRequestPayload.TYPE)) {
            feedback = JournalTexts.tr("unsupported").getString();
            error = true;
            revision++;
            return;
        }
        UUID requestId = UUID.randomUUID();
        feedback = "";
        error = false;
        REQUESTS.offer(requestId, new StoryRequestPayload(requestId, action, planId, choiceId));
        revision++;
    }

    static void accept(StoryPagePayload page) {
        if (!REQUESTS.accept(page.requestId())) return;
        story = page.story();
        feedback = page.feedback();
        error = page.error();
        if (error && feedback.isBlank()) feedback = JournalTexts.tr("action_failed").getString();
        revision++;
    }

    static void tick() {
        if (REQUESTS.tick(35_000)) {
            feedback = JournalTexts.tr("timeout").getString();
            error = true;
            revision++;
        }
    }

    static void clear() {
        story = null;
        REQUESTS.clear();
        revision++;
        feedback = "";
        error = false;
    }

    private StoryClientState() {}
}
