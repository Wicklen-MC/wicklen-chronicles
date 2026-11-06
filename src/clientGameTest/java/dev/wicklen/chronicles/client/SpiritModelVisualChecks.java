package dev.wicklen.chronicles.client;

import dev.wicklen.chronicles.SpiritManager;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.world.phys.Vec3;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Close native photographs; only the test fixture pins the ordinary orbit briefly. */
final class SpiritModelVisualChecks {
    static void capture(ClientGameTestContext context, TestSingleplayerContext world) {
        var server = world.getServer();
        var connection = world.getConnection();
        var active = new AtomicBoolean(true);
        var color = new AtomicInteger(0xFFE97F);
        var yaw = new AtomicInteger(180);
        var id = server.computeOnServer(s -> SpiritManager.findSpirits(connection.getServerPlayer().getScoreboardName(), s).getFirst().getId());
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            if (!active.get()) return;
            var player = s.getPlayerList().getPlayer(connection.getServerPlayer().getUUID());
            if (player == null) return;
            var entity = player.level().getEntity(id);
            if (!(entity instanceof dev.wicklen.chronicles.entity.SpiritEntity spirit)) return;
            spirit.snapTo(player.getX(), player.getEyeY() - 0.35, player.getZ() + 2);
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
            for (String shot : new String[]{"spirit-front-day", "spirit-side-day", "spirit-front-night"}) {
                yaw.set(shot.contains("side") ? 135 : 180);
                color.set(shot.contains("night") ? 0x9FE8FF : 0xFFE97F);
                server.runCommand(shot.contains("night") ? "time set midnight" : "time set day");
                connection.waitForClientboundPackets();
                context.waitTicks(10);
                context.takeScreenshot(TestScreenshotOptions.of(shot).disableCounterPrefix()
                    .withDestinationDir(Path.of("screenshots", "spirit-refresh")).withSize(1280, 800));
            }
        } finally {
            active.set(false);
            server.runCommand("time set day");
            context.runOnClient(client -> { if (client.gui.hud.isHidden() != hidden) client.gui.hud.toggle(); client.player.setYRot(oldYaw); client.player.setXRot(oldPitch); });
        }
    }
}
