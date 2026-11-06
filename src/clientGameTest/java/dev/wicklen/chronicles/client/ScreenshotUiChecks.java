package dev.wicklen.chronicles.client;

import dev.wicklen.chronicles.SpiritManager;
import dev.wicklen.chronicles.item.ChroniclesItems;
import dev.wicklen.chronicles.research.CampService;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/** Opt-in documentation photographs of real native fixture states, never a production entrypoint. */
public final class ScreenshotUiChecks {
    private static final Map<String, String> SHOTS = Map.ofEntries(
            Map.entry("journal-first-step-ru", "journal-next"),
            Map.entry("spyglass-target-progress", "spyglass-observation"),
            Map.entry("annotation-record-personal-name", "personal-notes"),
            Map.entry("guidance-known-place-map", "terrain-map"),
            Map.entry("firstlight-offer-battle-or-survey", "first-light"),
            Map.entry("journal-trials-prepared", "courtyard-trial"),
            Map.entry("journal-settings-controls-compact", "journal-settings"),
            Map.entry("spirit-speech-hud", "spirit-speech"),
            Map.entry("cycle-earned-lantern-active", "wayfarer-lantern"));
    private static final Set<String> CAPTURED = new HashSet<>();

    private ScreenshotUiChecks() {}

    public static boolean enabled() { return Boolean.getBoolean("wicklen.docs.screenshots"); }

    public static void capture(ClientGameTestContext context, String source) {
        if (!enabled()) return;
        String name = SHOTS.get(source);
        if (name == null || !CAPTURED.add(name)) return;
        var previous = new AtomicReference<ClientUiSettings.Preferences>();
        var camera = new AtomicReference<TerrainMapCamera.View>();
        var pitch = new AtomicReference<Float>();
        context.runOnClient(client -> {
            previous.set(ClientUiSettings.snapshot());
            if (name.equals("wayfarer-lantern") && client.player != null) {
                pitch.set(client.player.getXRot());
                client.player.setXRot(8);
            }
            ClientUiSettings.update(ClientUiSettings.DEFAULTS);
            client.gui.toastManager().clear();
            client.gui.hud.getChat().clearMessages(true);
            if (name.equals("terrain-map")) {
                camera.set(TerrainMapClientState.displayViewport());
                var place = ResearchClientState.data().places().stream()
                        .filter(entry -> entry.key().id().endsWith("terrain_camp")).findFirst().orElseThrow();
                TerrainMapClientState.focus(place.position().x() + 16, place.position().z() + 16, 128);
            }
            if (client.gui.screen() != null) client.gui.screen().resize(
                    client.getWindow().getGuiScaledWidth(), client.getWindow().getGuiScaledHeight());
        });
        try {
            if (camera.get() != null) context.waitFor(client -> !TerrainMapClientState.busy()
                    && TerrainMapClientState.page() != null && TerrainMapClientState.page().viewport().span() == 128);
            context.waitTicks(3);
            photograph(context, name);
        } finally {
            context.runOnClient(client -> {
                ClientUiSettings.update(previous.get());
                if (pitch.get() != null && client.player != null) client.player.setXRot(pitch.get());
                if (camera.get() != null) {
                    var view = camera.get();
                    TerrainMapClientState.focus((int) Math.round(view.centerX()), (int) Math.round(view.centerZ()), view.span());
                }
                if (client.gui.screen() != null) client.gui.screen().resize(
                        client.getWindow().getGuiScaledWidth(), client.getWindow().getGuiScaledHeight());
            });
        }
    }

    /** Reinstates the already-bound native desk for a photo, then restores blocks, hands and position. */
    public static void fieldDesk(ClientGameTestContext context, TestSingleplayerContext world) {
        if (!enabled() || !CAPTURED.add("field-desk")) return;
        var previousBlocks = new HashMap<BlockPos, BlockState>();
        var position = new AtomicReference<Vec3>();
        var hand = new AtomicReference<ItemStack>();
        var rotation = new float[2];
        var spiritPosition = new AtomicReference<Vec3>();
        var desk = world.getServer().computeOnServer(server -> {
            var player = world.getConnection().getServerPlayer();
            return CampService.view(player).desk();
        });
        BlockPos table = new BlockPos(desk.x(), desk.y(), desk.z());
        var previousScreen = context.computeOnClient(client -> client.gui.screen());
        context.setScreen(() -> null);
        world.getServer().runCommand("time set day");
        world.getServer().runCommand("weather clear");
        world.getServer().runOnServer(server -> {
            var player = world.getConnection().getServerPlayer();
            position.set(player.position());
            hand.set(player.getMainHandItem().copy());
            rotation[0] = player.getYRot();
            rotation[1] = player.getXRot();
            Map<BlockPos, BlockState> props = Map.of(
                    table, Blocks.CARTOGRAPHY_TABLE.defaultBlockState(),
                    table.east(2), Blocks.OAK_FENCE.defaultBlockState(),
                    table.east(2).above(), Blocks.LANTERN.defaultBlockState(),
                    table.west(), Blocks.BARREL.defaultBlockState());
            props.forEach((pos, state) -> {
                previousBlocks.put(pos, player.level().getBlockState(pos));
                player.level().setBlock(pos, state, 3);
            });
            player.teleportTo(table.getX() - 2.5, table.getY(), table.getZ() - 3.5);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ChroniclesItems.FIELD_NOTEBOOK));
            player.inventoryMenu.broadcastChanges();
            var spirit = SpiritManager.findSpirits(player.getScoreboardName(), server).getFirst();
            spiritPosition.set(spirit.position());
            spirit.snapTo(table.getX() + 0.8, table.getY() + 1.8, table.getZ() + 0.9);
        });
        try {
            world.getConnection().waitForClientboundPackets();
            context.getInput().lookAt(table.above());
            context.runOnClient(client -> {
                client.gui.toastManager().clear();
                client.gui.hud.getChat().clearMessages(true);
                SpiritSpeechHud.clear();
            });
            context.waitTicks(5);
            photograph(context, "field-desk");
        } finally {
            world.getServer().runOnServer(server -> {
                var player = world.getConnection().getServerPlayer();
                previousBlocks.forEach((pos, state) -> player.level().setBlock(pos, state, 3));
                var old = position.get();
                player.teleportTo(old.x, old.y, old.z);
                player.setYRot(rotation[0]);
                player.setXRot(rotation[1]);
                player.setItemInHand(InteractionHand.MAIN_HAND, hand.get());
                player.inventoryMenu.broadcastChanges();
                var spirit = SpiritManager.findSpirits(player.getScoreboardName(), server).getFirst();
                spirit.setPos(spiritPosition.get());
            });
            context.setScreen(() -> previousScreen);
        }
    }

    private static void photograph(ClientGameTestContext context, String name) {
        context.takeScreenshot(TestScreenshotOptions.of(name).disableCounterPrefix()
                .withDestinationDir(Path.of("screenshots", "docs-0.4.2")).withSize(1280, 800));
        System.out.println("DOCS_SCREENSHOT: " + name + " native0.4.2 1280x800");
    }
}
