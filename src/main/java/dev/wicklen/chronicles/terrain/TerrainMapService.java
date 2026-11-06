package dev.wicklen.chronicles.terrain;

import dev.wicklen.chronicles.WicklenChronicles;
import dev.wicklen.chronicles.ai.WorldStorageContext;
import dev.wicklen.chronicles.core.map.*;
import dev.wicklen.chronicles.net.TerrainMapPagePayload;
import dev.wicklen.chronicles.net.TerrainMapRequestPayload;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.levelgen.Heightmap;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

/** Personal map authority: physical surface visits -> loaded-only sampling -> checked IO -> bounded projection. */
public final class TerrainMapService {
    public static final int MAX_SAMPLE_QUEUE = 64, MAX_IO_QUEUE = 32, MAX_COLUMNS_PER_TICK = 64;
    private static final List<BiConsumer<ServerPlayer, MapCell>> LISTENERS = new ArrayList<>();
    private static Runtime current;
    private static boolean registered;
    private record OwnerCell(UUID owner, MapCell cell) {}
    private record Pending(OwnerCell key, MapCell source, TerrainSampler.Job sample) {}
    public static void register() {
        if (registered) return; registered = true;
        PayloadTypeRegistry.serverboundPlay().register(TerrainMapRequestPayload.TYPE, TerrainMapRequestPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(TerrainMapPagePayload.TYPE, TerrainMapPagePayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(TerrainMapRequestPayload.TYPE, (payload, context) ->
                context.server().execute(() -> request(context.player(), payload)));
        ServerTickEvents.END_SERVER_TICK.register(TerrainMapService::tick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            if (current != null && current.server == server) {
                UUID owner = handler.player.getUUID(); current.lastCells.remove(owner); current.lastRequest.remove(owner);
                current.inFlight.remove(owner); current.retryAt.remove(owner);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (current == null || current.server != server) return;
            current.accepting = false; current.io.shutdown();
            try { if (!current.io.awaitTermination(3, TimeUnit.SECONDS)) current.io.shutdownNow(); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); current.io.shutdownNow(); }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> { if (current != null && current.server == server) current = null; });
    }
    public static void onSurvey(BiConsumer<ServerPlayer, MapCell> listener) { LISTENERS.add(Objects.requireNonNull(listener)); }
    /** Surface, not "sky directly visible": forest leaves/branches must not behave like a cave. */
    public static boolean isSurface(ServerPlayer player) {
        if (!player.isAlive() || player.isSpectator() || player.level().dimension() != Level.OVERWORLD) return false;
        var level = player.level(); var pos = player.blockPosition();
        var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null || !chunk.hasPrimedHeightmap(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES)
                || level.getBrightness(LightLayer.SKY, pos) <= 0) return false;
        int localX = pos.getX() & 15, localZ = pos.getZ() & 15;
        int ground = Integer.MAX_VALUE;
        for (int dx : new int[]{-2, 0, 2}) for (int dz : new int[]{-2, 0, 2}) {
            int x = Math.clamp(localX + dx, 0, 15), z = Math.clamp(localZ + dz, 0, 15);
            ground = Math.min(ground, chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z));
        }
        return player.getY() >= ground - 6;
    }
    private static Runtime runtime(MinecraftServer server) {
        UUID session = WorldStorageContext.sessionId();
        if (!WorldStorageContext.matchesSession(session)) throw new IllegalStateException("Terrain map world is unavailable");
        if (current == null || current.server != server || !current.session.equals(session)) current = new Runtime(server, session);
        return current;
    }
    private static void tick(MinecraftServer server) {
        if (!WorldStorageContext.matchesSession(WorldStorageContext.sessionId())) return;
        var state = runtime(server); if (!state.accepting) return;
        for (var player : server.getPlayerList().getPlayers()) {
            if (!isSurface(player) || server.getTickCount() < state.retryAt.getOrDefault(player.getUUID(), 0L)) continue;
            var cell = new MapCell(player.level().dimension().identifier().toString(), player.chunkPosition().x(), player.chunkPosition().z());
            if (cell.equals(state.lastCells.get(player.getUUID()))) continue;
            UUID owner = player.getUUID(); boolean retry = false;
            state.lastCells.put(owner, cell);
            // A deliberate local survey brush, independent of render distance or DH: center first, then its loaded neighbours.
            for (int[] offset : BRUSH) {
                if (Math.abs((long) cell.chunkX() + offset[0]) > MapCell.LIMIT || Math.abs((long) cell.chunkZ() + offset[1]) > MapCell.LIMIT) continue;
                var neighbour = new MapCell(cell.dimension(), cell.chunkX() + offset[0], cell.chunkZ() + offset[1]);
                var chunk = player.level().getChunkSource().getChunkNow(neighbour.chunkX(), neighbour.chunkZ());
                if (chunk == null || !chunk.hasPrimedHeightmap(Heightmap.Types.WORLD_SURFACE)) { retry = true; continue; }
                var key = new OwnerCell(owner, neighbour);
                if (state.pending.contains(key)) {
                    if (offset[0] == 0 && offset[1] == 0) {
                        Pending promote = null;
                        for (var item : state.samples) if (item.key.equals(key)) { promote = item; break; }
                        if (promote != null) { state.samples.remove(promote); state.samples.addFirst(promote); }
                    }
                    continue;
                }
                if (state.pending.size() >= MAX_SAMPLE_QUEUE) { retry = true; continue; }
                state.pending.add(key);
                var pending = new Pending(key, cell, new TerrainSampler.Job(neighbour, System.currentTimeMillis()));
                if (offset[0] == 0 && offset[1] == 0) state.samples.addFirst(pending); else state.samples.addLast(pending);
            }
            if (retry) { state.lastCells.remove(owner, cell); state.retryAt.put(owner, (long) server.getTickCount() + 20); }
        }
        long deadline = System.nanoTime() + 500_000;
        int columns = 0;
        while (!state.samples.isEmpty() && columns < MAX_COLUMNS_PER_TICK && System.nanoTime() < deadline) {
            var job = state.samples.peekFirst();
            var level = server.overworld(); // first release has one explicit surface dimension
            if (!TerrainSampler.sampleColumn(level, job.sample)) {
                state.samples.removeFirst(); state.pending.remove(job.key);
                if (state.lastCells.remove(job.key.owner, job.source) || state.lastCells.remove(job.key.owner, job.key.cell))
                    state.retryAt.put(job.key.owner, (long) server.getTickCount() + 20);
                continue;
            }
            columns++;
            if (!job.sample.complete()) continue;
            state.samples.removeFirst(); var snapshot = job.sample.finish();
            try {
                state.io.execute(() -> {
                    try {
                        var result = state.store.record(job.key.owner, snapshot);
                        server.execute(() -> {
                            if (!active(state)) return;
                            state.pending.remove(job.key);
                            var owner = server.getPlayerList().getPlayer(job.key.owner);
                            if (result.firstVisit() && owner != null) for (var listener : LISTENERS) {
                                try { listener.accept(owner, snapshot.cell()); }
                                catch (RuntimeException error) { WicklenChronicles.LOGGER.warn("Survey listener failed after persistence: {}", error.toString()); }
                            }
                        });
                    } catch (Exception error) { server.execute(() -> failed(state, job.key, job.source, error)); }
                });
            } catch (RejectedExecutionException error) { failed(state, job.key, job.source, error); }
        }
    }
    private static final int[][] BRUSH = {{0, 0}, {-1, 0}, {1, 0}, {0, -1}, {0, 1}, {-1, -1}, {1, -1}, {-1, 1}, {1, 1}};
    private static void failed(Runtime state, OwnerCell key, MapCell source, Exception error) {
        if (!active(state)) return;
        state.pending.remove(key);
        if (state.lastCells.remove(key.owner, source) || state.lastCells.remove(key.owner, key.cell))
            state.retryAt.put(key.owner, (long) state.server.getTickCount() + 100);
        WicklenChronicles.LOGGER.warn("Terrain snapshot deferred; existing map preserved: {}", error.toString());
    }
    private static boolean active(Runtime state) { return current == state && state.accepting && WorldStorageContext.matchesSession(state.session); }
    private static void request(ServerPlayer player, TerrainMapRequestPayload request) {
        var server = player.level().getServer();
        if (server.getPlayerList().getPlayer(player.getUUID()) != player || !player.connection.isAcceptingMessages()
                || !ServerPlayNetworking.canSend(player, TerrainMapPagePayload.TYPE)) return;
        var state = runtime(server); UUID owner = player.getUUID(); String dimension = player.level().dimension().identifier().toString();
        long now = System.nanoTime(); Long previous = state.lastRequest.put(owner, now);
        if (!state.accepting || state.inFlight.contains(owner) || previous != null && now - previous < 250_000_000L) return;
        if (request.expectedWorldId() != null && !request.expectedWorldId().equals(state.world)) {
            send(player, state, request, dimension, new byte[16384], "terrain.stale", true); return;
        }
        if (!dimension.equals("minecraft:overworld")) { send(player, state, request, dimension, new byte[16384], "terrain.overworld_only", false); return; }
        state.inFlight.add(owner);
        try {
            state.io.execute(() -> {
                byte[] pixels; String notice = ""; boolean error = false;
                try { pixels = state.store.viewport(owner, dimension, request.viewport()); }
                catch (Exception failure) {
                    pixels = new byte[16384]; notice = "terrain.unavailable"; error = true;
                    WicklenChronicles.LOGGER.warn("Terrain viewport unavailable; source preserved: {}", failure.toString());
                }
                byte[] finalPixels = pixels; String finalNotice = notice; boolean finalError = error;
                server.execute(() -> {
                    if (!active(state)) return;
                    state.inFlight.remove(owner);
                    if (server.getPlayerList().getPlayer(owner) != player || !player.connection.isAcceptingMessages()
                            || !player.level().dimension().identifier().toString().equals(dimension)) return;
                    send(player, state, request, dimension, finalPixels, finalNotice, finalError);
                });
            });
        } catch (RejectedExecutionException error) { state.inFlight.remove(owner); send(player, state, request, dimension, new byte[16384], "terrain.busy", true); }
    }
    private static void send(ServerPlayer player, Runtime state, TerrainMapRequestPayload request, String dimension, byte[] pixels, String notice, boolean error) {
        ServerPlayNetworking.send(player, new TerrainMapPagePayload(request.requestId(), state.world, player.getUUID(), state.session,
                dimension, request.viewport(), pixels, notice.isEmpty() ? "" : "journal.wicklen_chronicles." + notice, error));
    }
    private static final class Runtime {
        final MinecraftServer server; final UUID session, world; final TerrainMapStore store;
        final ThreadPoolExecutor io = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(MAX_IO_QUEUE), runnable -> {
            var thread = new Thread(runnable, "wicklen-terrain-io"); thread.setDaemon(true); return thread;
        });
        final ArrayDeque<Pending> samples = new ArrayDeque<>(); final Set<OwnerCell> pending = new HashSet<>();
        final Map<UUID, MapCell> lastCells = new HashMap<>(); final Map<UUID, Long> retryAt = new HashMap<>(), lastRequest = new HashMap<>();
        final Set<UUID> inFlight = new HashSet<>(); boolean accepting = true;
        Runtime(MinecraftServer server, UUID session) {
            this.server = server; this.session = session; world = WorldStorageContext.worldId();
            store = new TerrainMapStore(WorldStorageContext.directory(), world);
        }
    }
    private TerrainMapService() {}
}
