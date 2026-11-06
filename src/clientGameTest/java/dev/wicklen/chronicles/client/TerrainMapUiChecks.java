package dev.wicklen.chronicles.client;

import dev.wicklen.chronicles.ai.WorldStorageContext;
import dev.wicklen.chronicles.core.map.*;
import dev.wicklen.chronicles.terrain.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.LightLayer;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Real server visits and real blocks -> persisted snapshots -> packets -> native journal texture. */
public final class TerrainMapUiChecks {
    public static void verify(ClientGameTestContext context, TestSingleplayerContext world) {
        var server = world.getServer(); var connection = world.getConnection();
        var oldPosition = new AtomicReference<net.minecraft.world.phys.Vec3>();
        var oldMode = new AtomicReference<GameType>();
        var campKey = new dev.wicklen.chronicles.core.discovery.DiscoveryKey(
                dev.wicklen.chronicles.core.discovery.DiscoveryKind.PLACE, "wicklen_chronicles:test/terrain_camp");
        final int cx = 128, cz = 128, y = server.computeOnServer(s -> s.overworld().getMaxY() - 25);
        server.runOnServer(s -> {
            var player = connection.getServerPlayer(); oldPosition.set(player.position()); oldMode.set(player.gameMode());
            player.setGameMode(GameType.CREATIVE);
            var level = player.level();
            // Five explicit fixture chunks contain real topography; only three will be visited.
            for (int offset = 0; offset < 5; offset++) {
                level.getChunk(cx + offset, cz);
                for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                    int bx = (cx + offset) * 16 + x, bz = cz * 16 + z;
                    level.setBlock(new BlockPos(bx, y - 1, bz), Blocks.DIRT.defaultBlockState(), 3);
                    level.setBlock(new BlockPos(bx, y, bz), (z > 9 ? Blocks.WATER : Blocks.GRASS_BLOCK).defaultBlockState(), 3);
                }
                for (int dy = 1; dy <= 3; dy++) level.setBlock(new BlockPos((cx + offset) * 16 + 3, y + dy, cz * 16 + 3), Blocks.OAK_LOG.defaultBlockState(), 3);
                for (int dx = 1; dx <= 5; dx++) for (int dz = 1; dz <= 5; dz++)
                    level.setBlock(new BlockPos((cx + offset) * 16 + dx, y + 4, cz * 16 + dz), Blocks.OAK_LEAVES.defaultBlockState(), 3);
            }
            try {
                new dev.wicklen.chronicles.exploration.DiscoveryRepository().observe(player.getUUID(),
                        new dev.wicklen.chronicles.core.discovery.Observation(campKey, "Creek camp", "A field camp beside the water.",
                                new dev.wicklen.chronicles.core.discovery.DiscoveryPosition("minecraft:overworld", cx * 16 + 7, y + 1, cz * 16 + 7), System.currentTimeMillis()));
                require(dev.wicklen.chronicles.annotation.AnnotationService.save(player, WorldStorageContext.worldId(), campKey, 0,
                        "My creek camp", "Follow the water past the old trees.").status() == dev.wicklen.chronicles.annotation.AnnotationService.Status.OK,
                        "fixture place receives a personal alias independently from its canonical ID");
            } catch (java.io.IOException error) { throw new RuntimeException(error); }
            player.teleportTo(cx * 16 + 8.5, y + 1, cz * 16 + 7.5);
        });
        try {
            context.waitTicks(30);
            server.runOnServer(s -> {
                var player = connection.getServerPlayer();
                require(TerrainMapService.isSurface(player), "open native ground is surveyed");
                var position = player.position();
                player.setPos(cx * 16 + 4.5, y + 1, cz * 16 + 4.5);
                require(TerrainMapService.isSurface(player), "forest leaves are not an underground roof");
                player.setPos(cx * 16 + 8.5, y - 8, cz * 16 + 7.5);
                require(!TerrainMapService.isSurface(player), "underground travel does not chart the surface above it");
                player.setPos(position);
            });
            context.setScreen(SpiritDialogScreen::new);
            context.runOnClient(client -> ((SpiritDialogScreen) client.gui.screen()).open(SpiritDialogScreen.Tab.MAP));
            waitForCell(context, cx, cz);
            server.runOnServer(s -> connection.getServerPlayer().teleportTo((cx + 1) * 16 + 8.5, y + 1, cz * 16 + 7.5));
            context.waitTicks(20); waitForCell(context, cx + 1, cz);
            server.runOnServer(s -> connection.getServerPlayer().teleportTo((cx + 8) * 16 + 8.5, y + 1, cz * 16 + 7.5));
            context.waitTicks(30); waitForCell(context, cx + 8, cz);
            context.runOnClient(client -> TerrainMapClientState.focus((cx + 4) * 16 + 8, cz * 16 + 8, 256));
            context.waitFor(client -> !TerrainMapClientState.busy() && TerrainMapClientState.page() != null
                    && TerrainMapClientState.page().viewport().centerX() == (cx + 4) * 16 + 8);
            context.runOnClient(client -> {
                var page = TerrainMapClientState.page(); var pixels = page.pixels(); var view = page.viewport();
                require(colorAt(pixels, view, (cx + 4) * 16 + 8, cz * 16 + 8) == 0
                        && colorAt(pixels, view, (cx + 5) * 16 + 8, cz * 16 + 8) == 0,
                        "loaded but unvisited teleport gap stays opaque fog");
                var colors = new HashSet<Integer>(); for (byte pixel : pixels) if (pixel != 0) colors.add(pixel & 255);
                require(colors.size() >= 3, "native topography has water/ground/canopy/height shade colors");
            });
            // A short real return trip broadens the sketch, while the distant teleport gap remains unknown.
            server.runOnServer(s -> connection.getServerPlayer().teleportTo((cx + 1) * 16 + 8.5, y + 1, (cz + 3) * 16 + 7.5));
            context.waitTicks(40); waitForCell(context, cx + 1, cz + 3);
            context.runOnClient(client -> TerrainMapClientState.focus((cx + 1) * 16 + 8, (cz + 1) * 16 + 8, 128));
            context.waitFor(client -> TerrainMapClientState.page() != null && TerrainMapClientState.page().viewport().span() == 128
                    && !TerrainMapClientState.busy());
            context.waitFor(client -> AnnotationClientState.lookup(campKey) != null);
            context.waitTicks(4);
            context.clickScreenButton("My creek camp");context.waitTicks(3);
            context.takeScreenshot("terrain-map-real-ground-water-trees-fog");
            server.runOnServer(s -> {
                var level = s.overworld(); var store = new TerrainMapStore(WorldStorageContext.directory(), WorldStorageContext.worldId());
                try {
                    var start = new MapCell("minecraft:overworld", cx, cz);
                    require(!store.region(connection.getServerPlayer().getUUID(), start).chunks().isEmpty(), "real visit remains on disk independent from client cache");
                    require(store.region(UUID.randomUUID(), start).chunks().isEmpty(), "another player cannot inherit these terrain pixels");
                } catch (java.io.IOException error) { throw new RuntimeException(error); }
            });
            context.runOnClient(client -> { TerrainMapClientState.zoom(false); TerrainMapClientState.zoom(false); });
            context.waitFor(client -> TerrainMapClientState.page() != null && TerrainMapClientState.page().viewport().span() == 512);
            context.runOnClient(client -> TerrainMapClientState.pan(128, 0));
            context.waitFor(client -> !TerrainMapClientState.busy() && !TerrainMapClientState.following());
            context.runOnClient(client -> TerrainMapClientState.center());
            context.waitFor(client -> TerrainMapClientState.following() && !TerrainMapClientState.busy());
            System.out.println("PASS: personal native terrain map, real surface visits, water/trees/shading, strict loaded-unvisited fog, teleport gap and pan/zoom/center");
        } finally {
            context.setScreen(() -> null);
            server.runOnServer(s -> {
                var player = connection.getServerPlayer(); var previous = oldPosition.get();
                player.teleportTo(previous.x, previous.y, previous.z); player.setGameMode(oldMode.get());
            });
        }
    }
    private static void waitForCell(ClientGameTestContext context, int cx, int cz) {
        context.runOnClient(client -> { TerrainMapClientState.center(); TerrainMapClientState.refresh(); });
        context.waitFor(client -> {
            var page = TerrainMapClientState.page();
            return page != null && page.viewport().contains(cx * 16 + 8, cz * 16 + 8)
                    && colorAt(page.pixels(), page.viewport(), cx * 16 + 8, cz * 16 + 8) != 0;
        });
    }
    private static int colorAt(byte[] pixels, MapViewport view, int x, int z) {
        if (!view.contains(x, z)) return 0;
        return pixels[(int) (view.v(z) * 128) * 128 + (int) (view.u(x) * 128)] & 255;
    }
    private static void require(boolean result, String message) { if (!result) throw new AssertionError(message); }
}
