package dev.wicklen.chronicles.client;

import dev.wicklen.chronicles.core.map.MapViewport;
import dev.wicklen.chronicles.net.TerrainMapPagePayload;
import dev.wicklen.chronicles.net.TerrainMapRequestPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.*;
import net.minecraft.client.Minecraft;
import java.util.*;

/** Connection/session-scoped projection; no world sampling, DH data or locally invented visited cells. */
public final class TerrainMapClientState {
    private static TerrainMapPagePayload page;
    private static UUID worldId, sessionId, pending;
    private static String dimension = "";
    private static final TerrainMapCamera CAMERA = new TerrainMapCamera();
    private static boolean following = true, dirty = true, registered;
    private static long lastActive, lastRequest, pendingAt, revision;
    private static String notice = "";
    public static void register() {
        if (registered) return; registered = true;
        ClientPlayNetworking.registerGlobalReceiver(TerrainMapPagePayload.TYPE, (payload, context) -> {
            var connection = context.client().getConnection();
            context.client().execute(() -> {
                var client = context.client();
                if (connection == null || connection != client.getConnection() || client.player == null || client.level == null
                        || !payload.ownerId().equals(client.player.getUUID()) || !payload.requestId().equals(pending)
                        || !payload.dimension().equals(client.level.dimension().identifier().toString())) return;
                pending = null;
                if (worldId != null && (!worldId.equals(payload.worldId()) || !sessionId.equals(payload.sessionId()))) {
                    notice = "journal.wicklen_chronicles.terrain.stale"; return;
                }
                worldId = payload.worldId(); sessionId = payload.sessionId(); dimension = payload.dimension();
                byte[] old = page == null ? null : page.pixels();
                if (old == null || !Arrays.equals(old, payload.pixels())) TerrainMapRenderer.update(payload.pixels());
                page = payload; notice = payload.noticeKey(); revision++;
                if (client.gui.screen() instanceof SpiritDialogScreen screen) screen.changed();
            });
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> clear());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
            // Transport disconnect can run on Netty; GPU disposal and client state belong to the render thread.
            if (client.getConnection() == null || client.getConnection() == handler) clear();
        }));
    }
    public static void activate() { lastActive = System.currentTimeMillis(); }
    public static void refresh() { dirty = true; activate(); }
    public static void center() { following = true; dirty = true; activate(); }
    public static void focus(int x, int z, int requestedSpan) {
        CAMERA.focus(x, z, requestedSpan);
        following = false;
        changed();
    }
    public static void pan(int dx, int dz) {
        CAMERA.pan(dx, dz);
        following = false;
        changed();
    }
    static void moveTo(double x, double z) {
        CAMERA.moveTo(x, z);
        following = false;
        changed();
    }
    public static void zoom(boolean inward) {
        if (CAMERA.zoom(inward, 0.5, 0.5)) changed();
    }
    static void zoomAt(boolean inward, double u, double v) {
        if (CAMERA.zoom(inward, u, v)) {
            following = false;
            changed();
        }
    }
    private static void changed() {
        dirty = true;
        revision++;
        activate();
    }
    public static int span() { return CAMERA.view().span(); }
    public static boolean following() { return following; }
    public static boolean busy() { return pending != null; }
    public static long revision() { return revision; }
    public static TerrainMapPagePayload page() { return page; }
    public static String notice() { return notice; }
    /** Optimistic viewport; pending responses never move the user's camera backwards. */
    public static MapViewport viewport() { return desired(); }
    static TerrainMapCamera.View displayViewport() { return CAMERA.view(); }
    private static MapViewport desired() { return CAMERA.wire(); }
    private static void tick() {
        var client = Minecraft.getInstance(); long now = System.currentTimeMillis();
        if (client.player == null || client.level == null || now - lastActive > 800) return;
        String activeDimension = client.level.dimension().identifier().toString();
        if (!dimension.isEmpty() && !dimension.equals(activeDimension)) {
            page = null; pending = null; dimension = activeDimension; following = true; dirty = true; TerrainMapRenderer.clear();
        }
        if (following) {
            CAMERA.moveTo(client.player.getX(), client.player.getZ());
        }
        if (pending != null && now - pendingAt > 6000) { pending = null; notice = "journal.wicklen_chronicles.terrain.timeout"; }
        if (pending != null || now - lastRequest < 350 || !dirty && page != null && now - lastRequest < 1500) return;
        if (!ClientPlayNetworking.canSend(TerrainMapRequestPayload.TYPE)) { notice = "journal.wicklen_chronicles.terrain.unsupported"; return; }
        pending = UUID.randomUUID(); pendingAt = lastRequest = now; dirty = false;
        ClientPlayNetworking.send(new TerrainMapRequestPayload(pending, worldId, desired()));
    }
    public static void clear() {
        page = null; worldId = null; sessionId = null; pending = null; dimension = "";
        CAMERA.focus(0, 0, 256); following = dirty = true; lastActive = lastRequest = pendingAt = 0; notice = ""; revision++;
        TerrainMapRenderer.clear();
    }
    private TerrainMapClientState() {}
}
