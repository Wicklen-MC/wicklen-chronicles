package dev.wicklen.chronicles.client;

import dev.wicklen.chronicles.annotation.AnnotationService;
import dev.wicklen.chronicles.ai.WorldStorageContext;
import dev.wicklen.chronicles.core.discovery.DiscoveryKey;
import dev.wicklen.chronicles.exploration.ExplorationService;
import dev.wicklen.chronicles.exploration.WorldStructureFixture;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import java.util.concurrent.atomic.AtomicReference;

/** Actual spyglass observation of a foreign registered structure, then personal naming/map/guidance. */
public final class WorldStructureUiChecks {
    public static void verify(ClientGameTestContext context, TestSingleplayerContext world) {
        var server = world.getServer();
        var connection = world.getConnection();
        var fixture = new AtomicReference<WorldStructureFixture>();
        var hand = new AtomicReference<ItemStack>();
        var key = new AtomicReference<DiscoveryKey>();
        var oldFlying = new AtomicReference<Boolean>();
        context.setScreen(() -> null);
        server.runOnServer(s -> {
            var player = connection.getServerPlayer();
            hand.set(player.getMainHandItem().copy());
            oldFlying.set(player.getAbilities().flying);
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
            player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            fixture.set(new WorldStructureFixture(player));
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.SPYGLASS));
            player.inventoryMenu.broadcastChanges();
        });
        try {
            connection.waitForClientboundPackets();
            context.waitTicks(5);
            context.getInput().lookAt(fixture.get().target);
            context.getInput().holdKey(o -> o.keyUse);
            try { context.waitTicks(50); }
            finally {
                context.getInput().releaseKey(o -> o.keyUse);
                server.runOnServer(s -> connection.getServerPlayer().stopUsingItem());
            }
            server.runOnServer(s -> {
                var player = connection.getServerPlayer();
                var entry = ExplorationService.snapshot(player).entries().stream()
                        .filter(e -> e.title().contains("Foreign Ruin")).findFirst()
                        .orElseThrow(() -> new AssertionError("Real native spyglass must record a foreign registered structure"));
                key.set(entry.key());
                var result = AnnotationService.save(player, WorldStorageContext.worldId(), entry.key(), 0,
                        "Старый архив", "Постройка из другого набора мира. Вернусь сюда с припасами.");
                require(result.status() == AnnotationService.Status.OK, "foreign structure accepts the same personal annotation as other known places");
            });
            context.setScreen(SpiritDialogScreen::new);
            context.runOnClient(c -> {
                AnnotationClientState.refresh(java.util.List.of(key.get()));
                ((SpiritDialogScreen)c.gui.screen()).open(SpiritDialogScreen.Tab.MAP);
                TerrainMapClientState.focus(fixture.get().target.getX(), fixture.get().target.getZ(), 128);
            });
            context.waitFor(c -> !ResearchClientState.busy() && ResearchClientState.data()!=null
                    && ResearchClientState.data().places().stream().anyMatch(e -> e.key().equals(key.get()))
                    && AnnotationClientState.lookup(key.get())!=null);
            context.waitTicks(4);
            context.clickScreenButton("Старый архив");
            context.waitTicks(4);
            context.clickScreenButton(context.computeOnClient(c -> JournalTexts.tr("guidance.follow_short").getString()));
            context.waitFor(c -> !GuidanceClientState.busy() && GuidanceClientState.target()!=null
                    && GuidanceClientState.target().id().equals(key.get().id()));
            context.waitTicks(4);
            context.takeScreenshot("world-structure-personal-name-map-guidance");
            context.setScreen(() -> null);
            context.waitTicks(4);
            context.takeScreenshot("world-structure-personal-guidance-hud");
            context.runOnClient(c -> GuidanceClientState.stop());
            context.waitFor(c -> !GuidanceClientState.busy() && !GuidanceClientState.enabled());
            System.out.println("PASS: real foreign structure spyglass -> personal name/note -> map marker -> native Follow -> guidance HUD");
        } finally {
            context.getInput().releaseKey(o -> o.keyUse);
            context.setScreen(() -> null);
            server.runOnServer(s -> {
                var player = connection.getServerPlayer();
                player.stopUsingItem();
                player.setItemInHand(InteractionHand.MAIN_HAND, hand.get());
                player.getAbilities().flying = oldFlying.get();
                player.onUpdateAbilities();
                fixture.get().close();
            });
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private WorldStructureUiChecks() {}
}
