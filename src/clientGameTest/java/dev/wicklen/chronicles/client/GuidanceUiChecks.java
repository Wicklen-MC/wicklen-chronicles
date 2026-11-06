package dev.wicklen.chronicles.client;

import dev.wicklen.chronicles.ai.WorldStorageContext;
import dev.wicklen.chronicles.core.discovery.DiscoveryKey;
import dev.wicklen.chronicles.core.discovery.DiscoveryKind;
import dev.wicklen.chronicles.core.field.FirstLightState;
import dev.wicklen.chronicles.core.guidance.GuidanceGeometry;
import dev.wicklen.chronicles.core.guidance.GuidanceIntent;
import dev.wicklen.chronicles.core.guidance.GuidanceTarget;
import dev.wicklen.chronicles.exploration.ExplorationService;
import dev.wicklen.chronicles.firstlight.FirstLightService;
import dev.wicklen.chronicles.guidance.GuidanceService;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.UUID;

/** Uses the existing native terrain and expedition fixtures; never creates discovery or progress facts. */
public final class GuidanceUiChecks {
    private static UUID clearedWorld;
    private static UUID clearedOwner;

    /** Call at the end of TerrainMapUiChecks, while its recorded creek camp is still in the viewport. */
    public static void verify(ClientGameTestContext context, TestSingleplayerContext world) {
        var server = world.getServer();
        var player = server.computeOnServer(s -> world.getConnection().getServerPlayer());
        var key = new DiscoveryKey(DiscoveryKind.PLACE, "wicklen_chronicles:test/terrain_camp");
        var entry = server.computeOnServer(s -> ExplorationService.snapshot(player).entries().stream()
                .filter(value -> value.key().equals(key)).findFirst().orElseThrow());
        var originalFacts = server.computeOnServer(s -> ExplorationService.snapshot(player));
        context.waitFor(client -> GuidanceClientState.page() != null);
        openMap(context);
        context.waitFor(client -> ResearchClientState.data() != null && !ResearchClientState.busy());
        context.waitTicks(4);
        context.clickScreenButton("My creek camp");
        click(context, "guidance.follow_short");
        context.waitFor(client -> !GuidanceClientState.busy() && GuidanceClientState.target() != null
                && GuidanceClientState.target().id().equals(key.id()));
        context.runOnClient(client -> {
            require(GuidanceClientState.target().label().equals("My creek camp"), "guidance uses the owner's actual personal alias");
            var clipped = TerrainMapRenderer.clippedLine(-100, 0.5, 100, 0.5);
            require(clipped != null && Math.abs(clipped[0]) < 0.001 && Math.abs(clipped[2] - 1) < 0.001,
                    "very distant line endpoints are clipped before drawing");
        });
        context.takeScreenshot("guidance-known-place-map");
        compactScreenshot(context, "guidance-compact");
        context.runOnClient(client -> GuidanceClientState.pin("wicklen_chronicles:unknown_client_guidance_test"));
        context.waitFor(client -> !GuidanceClientState.busy() && GuidanceClientState.error());
        server.runOnServer(s -> require(GuidanceService.current(player).placeId().equals(key.id()),
                "rejected unknown pin preserves the valid personal intent"));
        context.runOnClient(client -> require(GuidanceClientState.target() != null && GuidanceClientState.target().id().equals(key.id()),
                "a rejected request never invents a marker"));

        var oldPosition = server.computeOnServer(s -> player.position());
        try {
            server.runOnServer(s -> {
                player.teleportTo(entry.position().x() + 10.5, entry.position().y(), entry.position().z() + 0.5);
                player.setDeltaMovement(Vec3.ZERO);
            });
            world.getConnection().waitForClientboundPackets();
            context.setScreen(() -> null);
            faceTarget(context);
            context.runOnClient(client -> require(GuidanceHud.displayAllowed(client), "guidance has a dedicated world HUD"));
            context.waitTicks(3);
            context.takeScreenshot("guidance-world-direction-card");
            context.runOnClient(client -> client.gui.hud.toggle());
            context.runOnClient(client -> require(!GuidanceHud.displayAllowed(client) && GuidanceHud.emitSparks(client) == 0,
                    "F1 hides both card and particles"));
            context.takeScreenshot("guidance-hidden-with-hud");
            context.runOnClient(client -> client.gui.hud.toggle());
            verifyVisibleSparks(context, world);
        } finally {
            server.runOnServer(s -> {
                player.teleportTo(oldPosition.x, oldPosition.y, oldPosition.z);
                player.setDeltaMovement(Vec3.ZERO);
            });
        }
        context.runOnClient(client -> GuidanceClientState.stop());
        context.waitFor(client -> !GuidanceClientState.busy() && !GuidanceClientState.enabled() && GuidanceClientState.target() == null);
        server.runOnServer(s -> {
            require(GuidanceService.current(player).mode() == GuidanceIntent.Mode.DISABLED, "explicit stop persists a disabled intent");
            require(ExplorationService.snapshot(player).equals(originalFacts), "guidance never edits canonical discoveries");
            clearedWorld = WorldStorageContext.worldId();
            clearedOwner = player.getUUID();
        });
        openMap(context);
        System.out.println("PASS: native personal pin/alias/map bearing, unknown rejection, HUD/F1, visible loaded sparks and explicit stop");
    }

    /** Call after the real SURVEY choice, before either field mark; returns to the same active field page. */
    public static void verifyExpedition(ClientGameTestContext context, TestSingleplayerContext world) {
        var server = world.getServer();
        var player = server.computeOnServer(s -> world.getConnection().getServerPlayer());
        var before = server.computeOnServer(s -> FirstLightService.current(player));
        var facts = server.computeOnServer(s -> ExplorationService.snapshot(player));
        require(before != null && before.status() == FirstLightState.Status.ACTIVE
                && before.branch() == FirstLightState.Branch.SURVEY, "a real active survey owns the default bearing");
        context.waitFor(client -> GuidanceClientState.target() != null && GuidanceClientState.enabled()
                && GuidanceClientState.target().kind() == GuidanceTarget.Kind.SURVEY);
        var area = before.area();
        context.runOnClient(client -> TerrainMapClientState.focus((area.minX() + area.maxX()) / 2,
                (area.minZ() + area.maxZ()) / 2, 512));
        openMap(context);
        context.waitFor(client -> !TerrainMapClientState.busy() && TerrainMapClientState.page() != null
                && TerrainMapClientState.page().viewport().span() == 512);
        context.takeScreenshot("guidance-default-survey-map");
        context.setScreen(() -> null);
        faceTarget(context);
        context.waitTicks(3);
        context.takeScreenshot("guidance-default-survey-hud");
        openFirstLight(context);
        click(context, "guidance.stop");
        context.waitFor(client -> !GuidanceClientState.busy() && !GuidanceClientState.enabled());
        click(context, "guidance.follow_expedition");
        context.waitFor(client -> !GuidanceClientState.busy() && GuidanceClientState.target() != null
                && GuidanceClientState.target().kind() == GuidanceTarget.Kind.SURVEY);
        compactScreenshot(context, "guidance-survey-compact");
        server.runOnServer(s -> {
            require(FirstLightService.current(player).equals(before), "following/clearing never adds a field mark or claims a reward");
            require(ExplorationService.snapshot(player).equals(facts), "survey guidance reveals no extra discovery");
        });
        System.out.println("PASS: native default survey guidance, focused private map, compact field toggle and unchanged progress");
    }

    public static void verifyReload(ServerPlayer player) {
        if (clearedWorld == null || !clearedWorld.equals(WorldStorageContext.worldId()) || !player.getUUID().equals(clearedOwner)) {
            return;
        }
        require(GuidanceService.current(player).mode() == GuidanceIntent.Mode.DISABLED,
                "manual stop survives a real world reopen and READ does not turn it back on");
    }

    private static void verifyVisibleSparks(ClientGameTestContext context, TestSingleplayerContext world) {
        var server = world.getServer();
        var player = server.computeOnServer(s -> world.getConnection().getServerPlayer());
        context.runOnClient(client -> {
            client.particleEngine.clearParticles();
            int loaded = client.level.getChunkSource().getLoadedChunksCount();
            require(GuidanceHud.emitSparks(client) > 0, "nearby visible loaded space receives real glimmers");
            require(client.level.getChunkSource().getLoadedChunksCount() == loaded, "particle candidates never request another chunk");
        });
        context.waitTicks(2);
        context.runOnClient(client -> require(java.util.regex.Pattern.compile("[1-9][0-9]*").matcher(client.particleEngine.countParticles()).find(),
                "native particle engine actually accepted the nearby guidance particles"));
        context.takeScreenshot("guidance-visible-local-glimmers");
        context.runOnClient(client -> {
            var hand = client.player.getMainHandItem().copy();
            client.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.SPYGLASS));
            client.player.startUsingItem(InteractionHand.MAIN_HAND);
            require(GuidanceHud.emitSparks(client) == 0, "spyglass use suppresses glimmers around the reticle");
            client.player.stopUsingItem();
            client.player.setItemInHand(InteractionHand.MAIN_HAND, hand);
        });
        var saved = new HashMap<BlockPos, BlockState>();
        server.runOnServer(s -> {
            var target = GuidanceService.readmodel(player).target();
            Vec3 direction = new Vec3(target.x() - player.getX(), 0, target.z() - player.getZ()).normalize();
            BlockPos center = BlockPos.containing(player.getEyePosition().add(direction.scale(1.8)));
            boolean xPlane = Math.abs(direction.x) >= Math.abs(direction.z);
            for (int cross = -3; cross <= 3; cross++) {
                for (int y = player.blockPosition().getY(); y <= player.blockPosition().getY() + 3; y++) {
                    BlockPos block = xPlane ? new BlockPos(center.getX(), y, center.getZ() + cross)
                            : new BlockPos(center.getX() + cross, y, center.getZ());
                    saved.put(block, player.level().getBlockState(block));
                    player.level().setBlock(block, Blocks.STONE.defaultBlockState(), 3);
                }
            }
        });
        try {
            world.getConnection().waitForClientboundPackets();
            context.waitTicks(2);
            context.runOnClient(client -> require(GuidanceHud.emitSparks(client) == 0, "a real solid wall blocks every guidance glimmer"));
        } finally {
            server.runOnServer(s -> saved.forEach((block, state) -> player.level().setBlock(block, state, 3)));
            world.getConnection().waitForClientboundPackets();
        }
    }

    private static void faceTarget(ClientGameTestContext context) {
        context.runOnClient(client -> {
            var target = GuidanceClientState.target();
            if (target != null) {
                var vector = GuidanceGeometry.navigation(client.player.getX(), client.player.getZ(), client.player.getYRot(), target.x(), target.z());
                client.player.setYRot((float) (vector.bearingDegrees() - 180));
                client.player.setXRot(8);
            }
        });
    }

    private static void openMap(ClientGameTestContext context) {
        if (!context.computeOnClient(client -> client.gui.screen() instanceof SpiritDialogScreen screen && screen.tab() == SpiritDialogScreen.Tab.MAP)) {
            context.setScreen(SpiritDialogScreen::new);
            context.runOnClient(client -> ((SpiritDialogScreen) client.gui.screen()).open(SpiritDialogScreen.Tab.MAP));
        }
        context.waitTicks(3);
    }

    private static void openFirstLight(ClientGameTestContext context) {
        context.setScreen(SpiritDialogScreen::new);
        context.runOnClient(client -> ((SpiritDialogScreen) client.gui.screen()).open(SpiritDialogScreen.Tab.TASKS));
        click(context, "firstlight.open");
    }

    private static void compactScreenshot(ClientGameTestContext context, String name) {
        context.runOnClient(client -> client.gui.screen().init(320, 240));
        context.waitTicks(2);
        context.takeScreenshot(TestScreenshotOptions.of(name).withSize(640, 480));
        context.runOnClient(client -> client.gui.screen().init(client.getWindow().getGuiScaledWidth(), client.getWindow().getGuiScaledHeight()));
        context.waitTicks(2);
    }

    private static void click(ClientGameTestContext context, String key) {
        context.waitTicks(5);
        context.clickScreenButton(context.computeOnClient(client -> JournalTexts.tr(key).getString()));
        context.waitTicks(3);
    }

    private static void require(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    private GuidanceUiChecks() {}
}
