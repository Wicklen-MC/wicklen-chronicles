package dev.wicklen.chronicles.client;

import dev.wicklen.chronicles.core.guidance.GuidanceTarget;
import dev.wicklen.chronicles.net.GuidanceC2SPayload;
import dev.wicklen.chronicles.net.GuidanceS2CPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

import java.util.UUID;
import java.util.Objects;

/** A bounded connection-scoped projection. Guidance never samples or opens terrain cells. */
public final class GuidanceClientState {
    private record Action(GuidanceC2SPayload.Action kind, String placeId) {}

    private static GuidanceS2CPayload page;
    private static UUID worldId;
    private static UUID sessionId;
    private static UUID pending;
    private static Action queued;
    private static long sentAt;
    private static long lastSent;
    private static long revision;
    private static long highestServerRevision;
    private static long noticeUntil;
    private static String notice = "";
    private static boolean registered;
    private static boolean noticeError;

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        ClientPlayNetworking.registerGlobalReceiver(GuidanceS2CPayload.TYPE, (payload, context) -> {
            var connection = context.client().getConnection();
            context.client().execute(() -> {
                var client = context.client();
                if (connection == null || client.getConnection() != connection || client.player == null
                        || !payload.ownerId().equals(client.player.getUUID())) {
                    return;
                }
                UUID expectedWorld = worldId != null ? worldId
                        : JournalClientState.view() == null ? null : JournalClientState.view().worldId();
                if (expectedWorld != null && !expectedWorld.equals(payload.worldId())
                        || sessionId != null && !sessionId.equals(payload.sessionId())) {
                    return;
                }
                boolean push = payload.requestId().equals(GuidanceS2CPayload.PUSH_ID);
                if (push && sameView(page, payload)) {
                    return;
                }
                if (!push && !payload.requestId().equals(pending)) {
                    return;
                }
                if (!push) {
                    pending = null;
                }
                if (payload.revision() < highestServerRevision && !payload.error()) {
                    return;
                }
                worldId = payload.worldId();
                sessionId = payload.sessionId();
                highestServerRevision = Math.max(highestServerRevision, payload.revision());
                page = payload;
                if (payload.error()) {
                    notice = payload.noticeKey();
                    noticeError = true;
                    noticeUntil = System.currentTimeMillis() + 6000;
                } else if (!push || System.currentTimeMillis() >= noticeUntil) {
                    notice = payload.noticeKey();
                    noticeError = false;
                    noticeUntil = 0;
                }
                changed(client);
            });
        });
        ClientTickEvents.END_CLIENT_TICK.register(GuidanceClientState::tick);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> clear());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
            // Transport disconnect can run on Netty; GPU disposal and client state belong to the render thread.
            if (client.getConnection() == null || client.getConnection() == handler) clear();
        }));
        GuidanceHud.register();
    }

    private static boolean sameView(GuidanceS2CPayload first, GuidanceS2CPayload second) {
        return first != null && first.worldId().equals(second.worldId()) && first.ownerId().equals(second.ownerId())
                && first.sessionId().equals(second.sessionId()) && first.revision() == second.revision()
                && first.enabled() == second.enabled() && first.paused() == second.paused()
                && Objects.equals(first.target(), second.target()) && first.noticeKey().equals(second.noticeKey())
                && first.error() == second.error();
    }

    public static GuidanceS2CPayload page() {
        return page;
    }

    public static GuidanceTarget target() {
        return page == null || !page.enabled() ? null : page.target();
    }

    public static boolean enabled() {
        return page != null && page.enabled();
    }

    public static boolean busy() {
        return pending != null || queued != null;
    }

    public static long revision() {
        return revision;
    }

    public static String notice() {
        return notice;
    }

    public static boolean error() {
        return noticeError;
    }

    public static void pin(String placeId) {
        request(GuidanceC2SPayload.Action.PIN_PLACE, placeId);
    }

    public static void followExpedition() {
        request(GuidanceC2SPayload.Action.FOLLOW_EXPEDITION, "");
    }

    public static void stop() {
        request(GuidanceC2SPayload.Action.CLEAR, "");
    }

    public static void read() {
        request(GuidanceC2SPayload.Action.READ, "");
    }

    private static void request(GuidanceC2SPayload.Action action, String placeId) {
        if (!ClientPlayNetworking.canSend(GuidanceC2SPayload.TYPE)) {
            notice = "journal.wicklen_chronicles.guidance.unsupported";
            changed(Minecraft.getInstance());
            return;
        }
        if (action != GuidanceC2SPayload.Action.READ) {
            notice = "";
            noticeError = false;
            noticeUntil = 0;
        }
        // Keep only the newest unsent intention; an already dispatched action receives its own acknowledgement.
        queued = new Action(action, placeId);
        changed(Minecraft.getInstance());
    }

    private static void tick(Minecraft client) {
        if (client.player == null || client.level == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (noticeError && noticeUntil > 0 && now >= noticeUntil) {
            noticeError = false;
            noticeUntil = 0;
            notice = page == null ? "" : page.noticeKey();
            changed(client);
        }
        if (pending != null && now - sentAt > 8000) {
            pending = null;
            notice = "journal.wicklen_chronicles.guidance.timeout";
            noticeError = true;
            noticeUntil = now + 6000;
            changed(client);
        }
        if (queued != null && pending == null && now - lastSent >= 250) {
            Action action = queued;
            queued = null;
            pending = UUID.randomUUID();
            sentAt = lastSent = now;
            UUID expectedWorld = worldId != null ? worldId
                    : JournalClientState.view() == null ? null : JournalClientState.view().worldId();
            ClientPlayNetworking.send(new GuidanceC2SPayload(pending, expectedWorld, action.kind(), action.placeId()));
        }
        if (page == null && !busy() && now - lastSent >= 2000
                && ClientPlayNetworking.canSend(GuidanceC2SPayload.TYPE)) {
            queued = new Action(GuidanceC2SPayload.Action.READ, "");
        }
    }

    private static void changed(Minecraft client) {
        revision++;
        if (client.gui.screen() instanceof SpiritDialogScreen screen && screen.tab() != SpiritDialogScreen.Tab.CONVERSATION) {
            screen.changed();
        }
    }

    public static void clear() {
        page = null;
        worldId = sessionId = pending = null;
        queued = null;
        sentAt = lastSent = highestServerRevision = noticeUntil = 0;
        notice = "";
        noticeError = false;
        revision++;
    }

    private GuidanceClientState() {}
}
