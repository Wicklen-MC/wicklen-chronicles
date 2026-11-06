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
    private static int centerX, centerZ, span = 256;
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
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
    }
    public static void activate() { lastActive = System.currentTimeMillis(); }
    public static void refresh() { dirty = true; activate(); }
    public static void center() { following = true; dirty = true; activate(); }
    public static void focus(int x, int z, int requestedSpan) {
        var view = new MapViewport(Math.clamp(x, -MapViewport.BLOCK_LIMIT, MapViewport.BLOCK_LIMIT),
                Math.clamp(z, -MapViewport.BLOCK_LIMIT, MapViewport.BLOCK_LIMIT), requestedSpan);
        centerX = view.centerX(); centerZ = view.centerZ(); span = view.span(); following = false; dirty = true; activate();
    }
    public static void pan(int dx, int dz) {
        if (page != null && following) { centerX = page.viewport().centerX(); centerZ = page.viewport().centerZ(); }
        following = false;
        centerX = Math.clamp((long) centerX + dx, -MapViewport.BLOCK_LIMIT, MapViewport.BLOCK_LIMIT);
        centerZ = Math.clamp((long) centerZ + dz, -MapViewport.BLOCK_LIMIT, MapViewport.BLOCK_LIMIT);
        dirty = true; activate();
    }
    public static void zoom(boolean inward) { span = inward ? Math.max(128, span / 2) : Math.min(1024, span * 2); dirty = true; activate(); }
    public static int span() { return span; }
    public static boolean following() { return following; }
    public static boolean busy() { return pending != null; }
    public static long revision() { return revision; }
    public static TerrainMapPagePayload page() { return page; }
    public static String notice() { return notice; }
    public static MapViewport viewport() { return page == null ? desired() : page.viewport(); }
    private static MapViewport desired() { return new MapViewport(centerX, centerZ, span); }
    private static void tick() {
        var client = Minecraft.getInstance(); long now = System.currentTimeMillis();
        if (client.player == null || client.level == null || now - lastActive > 800) return;
        String activeDimension = client.level.dimension().identifier().toString();
        if (!dimension.isEmpty() && !dimension.equals(activeDimension)) {
            page = null; pending = null; dimension = activeDimension; following = true; dirty = true; TerrainMapRenderer.clear();
        }
        if (following) {
            centerX = Math.clamp((long) Math.floor(client.player.getX()), -MapViewport.BLOCK_LIMIT, MapViewport.BLOCK_LIMIT);
            centerZ = Math.clamp((long) Math.floor(client.player.getZ()), -MapViewport.BLOCK_LIMIT, MapViewport.BLOCK_LIMIT);
        }
        if (pending != null && now - pendingAt > 6000) { pending = null; notice = "journal.wicklen_chronicles.terrain.timeout"; }
        if (pending != null || now - lastRequest < 350 || !dirty && page != null && now - lastRequest < 1500) return;
        if (!ClientPlayNetworking.canSend(TerrainMapRequestPayload.TYPE)) { notice = "journal.wicklen_chronicles.terrain.unsupported"; return; }
        pending = UUID.randomUUID(); pendingAt = lastRequest = now; dirty = false;
        ClientPlayNetworking.send(new TerrainMapRequestPayload(pending, worldId, desired()));
    }
    public static void clear() {
        page = null; worldId = null; sessionId = null; pending = null; dimension = "";
        centerX = centerZ = 0; span = 256; following = dirty = true; lastActive = lastRequest = 0; notice = ""; revision++;
        TerrainMapRenderer.clear();
    }
    private TerrainMapClientState() {}
}
