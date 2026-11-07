package dev.wicklen.chronicles.client;

import dev.wicklen.chronicles.SpiritManager;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import java.util.HashMap;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Close native photographs; only the test fixture pins the ordinary orbit briefly. */
final class SpiritModelVisualChecks {
    static void capture(ClientGameTestContext context, TestSingleplayerContext world) {
        var server = world.getServer();
        var connection = world.getConnection();
        var active = new AtomicBoolean(true);
        var color = new AtomicInteger(0xFFE97F);
        var yaw = new AtomicInteger(180);
        var horizontalOffset = new AtomicReference<>(0.0);
        var reload = new AtomicReference<java.util.concurrent.CompletableFuture<Void>>();
        context.runOnClient(client -> {
            client.getLanguageManager().setSelected("ru_ru");
            reload.set(client.reloadResourcePacks());
        });
        context.waitFor(client -> reload.get().isDone() && client.gui.overlay() == null);
        var id = server.computeOnServer(s -> SpiritManager.findSpirits(connection.getServerPlayer().getScoreboardName(), s).getFirst().getId());
        var previousBlocks = new HashMap<BlockPos, BlockState>();
        server.runOnServer(s -> {
            var player = connection.getServerPlayer();
            var ground = player.blockPosition().below();
            var props = new HashMap<BlockPos, BlockState>();
            for (int z = 3; z <= 8; z++) for (int x = -1; x <= 1; x++)
                props.put(ground.offset(x, 0, z), ((x + z) % 3 == 0 ? Blocks.MOSSY_COBBLESTONE : Blocks.COBBLESTONE).defaultBlockState());
            for (int tree : new int[]{-4, 4}) {
                var trunk = ground.offset(tree, 0, tree < 0 ? 6 : 7);
                for (int h = 4; h <= 6; h++) for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++)
                    if (Math.abs(dx) + Math.abs(dz) < 4)
                        props.put(trunk.offset(dx, h, dz), Blocks.OAK_LEAVES.defaultBlockState());
                for (int h = 1; h <= 4; h++) props.put(trunk.above(h), Blocks.OAK_LOG.defaultBlockState());
            }
            props.put(ground.offset(2, 1, 3), Blocks.POPPY.defaultBlockState());
            props.put(ground.offset(-2, 1, 4), Blocks.DANDELION.defaultBlockState());
            props.put(ground.offset(-2, 1, 2), Blocks.OAK_FENCE.defaultBlockState());
            props.put(ground.offset(-2, 2, 2), Blocks.LANTERN.defaultBlockState());
            props.forEach((pos, state) -> {
                previousBlocks.put(pos, player.level().getBlockState(pos));
                player.level().setBlock(pos, state, 3);
            });
        });
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            if (!active.get()) return;
            var player = s.getPlayerList().getPlayer(connection.getServerPlayer().getUUID());
            if (player == null) return;
            var entity = player.level().getEntity(id);
            if (!(entity instanceof dev.wicklen.chronicles.entity.SpiritEntity spirit)) return;
            spirit.snapTo(player.getX() + horizontalOffset.get(), player.getEyeY() - 0.35, player.getZ() + 2);
            spirit.setDeltaMovement(Vec3.ZERO);
            spirit.setYRot(yaw.get());
            spirit.setYBodyRot(yaw.get());
            spirit.setYHeadRot(yaw.get());
            spirit.setSpiritColor(color.get());
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!active.get() || client.level == null) return;
            if (client.level.getEntity(id) instanceof dev.wicklen.chronicles.entity.SpiritEntity spirit) {
                spirit.setYRot(yaw.get());
                spirit.yRotO = yaw.get();
                spirit.setYBodyRot(yaw.get());
                spirit.yBodyRotO = yaw.get();
                spirit.setYHeadRot(yaw.get());
                spirit.yHeadRotO = yaw.get();
                spirit.setSpiritColor(color.get());
            }
        });
        boolean hidden = context.computeOnClient(client -> client.gui.hud.isHidden());
        float oldYaw = context.computeOnClient(client -> client.player.getYRot());
        float oldPitch = context.computeOnClient(client -> client.player.getXRot());
        try {
            context.setScreen(() -> null);
            context.runOnClient(client -> { if (!client.gui.hud.isHidden()) client.gui.hud.toggle(); client.player.setYRot(0); client.player.setXRot(0); });
            for (String shot : new String[]{"spirit-front-day", "spirit-side-day", "spirit-back-day", "spirit-front-night"}) {
                yaw.set(shot.contains("side") ? 135 : shot.contains("back") ? 0 : 180);
                color.set(shot.contains("night") ? 0x9FE8FF : 0xFFE97F);
                server.runCommand(shot.contains("night") ? "time set midnight" : "time set day");
                connection.waitForClientboundPackets();
                context.waitTicks(10);
                context.takeScreenshot(TestScreenshotOptions.of(shot).disableCounterPrefix()
                    .withDestinationDir(Path.of("screenshots", "spirit-wanderer")).withSize(1280, 800));
            }
            if (Boolean.getBoolean("wicklen.spirit.video")) {
                server.runCommand("time set day");
                color.set(0xFFE97F);
                yaw.set(180);
                var lastSecond = new AtomicInteger(-1);
                NativeGameVideo.record(context, 24, seconds -> {
                    yaw.set(seconds < 4 ? 180 : seconds < 12 ? 180 + (int) ((seconds - 4) * 45) : 180);
                    int second = (int) seconds;
                    if (lastSecond.getAndSet(second) == second) return;
                    if (second == 13) { color.set(0x9FE8FF); server.runCommand("time set midnight"); }
                    if (second == 17) { color.set(0xFFE97F); server.runCommand("time set day"); }
                    if (second == 18) {
                        horizontalOffset.set(-0.65);
                        context.runOnClient(client -> {
                            if (client.gui.hud.isHidden()) client.gui.hud.toggle();
                            client.gui.hud.getChat().clearMessages(true);
                            client.gui.toastManager().clear();
                            SpiritSpeechHud.clear();
                        });
                        server.runOnServer(s -> dev.wicklen.chronicles.SpiritBubbles.speak(
                            connection.getServerPlayer(), "Я рядом. Давай посмотрим, что ждёт за поворотом."));
                    }
                });
            }
        } finally {
            active.set(false);
            server.runCommand("time set day");
            server.runOnServer(s -> previousBlocks.forEach((pos, state) -> connection.getServerPlayer().level().setBlock(pos, state, 3)));
            context.runOnClient(client -> { if (client.gui.hud.isHidden() != hidden) client.gui.hud.toggle(); client.player.setYRot(oldYaw); client.player.setXRot(oldPitch); });
        }
    }
}
