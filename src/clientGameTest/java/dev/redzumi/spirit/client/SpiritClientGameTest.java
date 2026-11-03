package dev.redzumi.spirit.client;

import dev.redzumi.spirit.SpiritBubbles;
import dev.redzumi.spirit.SpiritManager;
import dev.redzumi.spirit.ai.SpiritConfig;
import dev.redzumi.spirit.ai.SpiritQuests;
import dev.redzumi.spirit.ai.SpiritMemoryStore;
import dev.redzumi.spirit.ai.llm.LlmProviders;
import dev.redzumi.spirit.net.SpiritChatC2SPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import com.sun.net.httpserver.HttpServer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises real entity tracking, S2C bubbles and the G screen in an isolated test world. */
public final class SpiritClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave savedWorld;
        try (var world = context.worldBuilder().create()) {
            savedWorld = world.getWorldSave();
            var server = world.getServer();
            var connection = world.getConnection();
            connection.waitForChunksRender();
            connection.waitForClientboundPackets();
            int joinedSpirit = server.computeOnServer(mcServer -> SpiritManager.findSpirits(
                connection.getServerPlayer().getScoreboardName(), mcServer).getFirst().getId());
            context.runOnClient(client -> require(BubbleStore.get(joinedSpirit) != null,
                "welcome bubble belongs to the active spirit, not a discarded JOIN entity"));
            server.runCommand("time set day");
            server.runCommand("weather clear");
            server.runOnServer(mcServer -> {
                var player = connection.getServerPlayer();
                SpiritManager.ensureSpirit(player);
                var spirits = SpiritManager.findSpirits(player.getScoreboardName(), mcServer);
                require(spirits.size() == 1, "exactly one companion");
                var spirit = spirits.getFirst();
                spirit.snapTo(player.getX(), player.getY() + 1.2, player.getZ() + 3.1);
                spirit.setYRot(180);
                spirit.setYBodyRot(180);
                spirit.setYHeadRot(180);
                player.setYRot(0);
                player.setXRot(0);
                SpiritBubbles.speak(player, "Я здесь. Нажми G — поговорим!");
            });
            connection.waitForClientboundPackets();
            context.waitFor(client -> !DialogHistory.lines().isEmpty());
            int entityId = server.computeOnServer(mcServer -> SpiritManager.findSpirits(
                connection.getServerPlayer().getScoreboardName(), mcServer).getFirst().getId());
            context.runOnClient(client -> {
                require(BubbleStore.get(entityId) != null, "bubble packet reaches client");
                client.player.setYRot(0);
                client.player.setXRot(0);
            });
            context.waitTicks(3);
            context.takeScreenshot("spirit-model-and-bubble");
            context.setScreen(SpiritDialogScreen::new);
            context.waitTicks(3);
            context.runOnClient(client -> require(client.gui.screen() instanceof SpiritDialogScreen, "G dialog opens"));
            context.takeScreenshot("spirit-dialog");
            verifyDialogue(context, world);
            context.setScreen(() -> null);
            // Quests remain active with proactive speech disabled and without a provider.
            server.runOnServer(mcServer -> {
                var player = connection.getServerPlayer();
                var cfg = SpiritConfig.get();
                cfg.proactive = false;
                cfg.custom.baseUrl = null;
                cfg.provider = "deepseek";
                LlmProviders.reset();
                if (SpiritQuests.active(player.getUUID()) == null) SpiritQuests.issue(player.getUUID());
                SpiritQuests.update(player.getUUID(), quest -> new SpiritQuests.Quest(
                    "collect", "minecraft:apple", 3, 0, System.currentTimeMillis()));
            });
            server.runCommand("give @p minecraft:apple 3");
            server.waitFor(mcServer -> SpiritQuests.active(connection.getServerPlayer().getUUID()) == null, 300);
            server.runOnServer(mcServer -> {
                var inventory = connection.getServerPlayer().getInventory();
                for (int i = 0; i < inventory.getContainerSize(); i++) {
                    require(!inventory.getItem(i).is(net.minecraft.world.item.Items.APPLE), "collect quest consumes the delivered items");
                }
            });
            // Repeated summon and dismissal must keep the entity and bubble caches consistent.
            server.runCommand("execute as @p run spirit summon");
            server.runCommand("execute as @p run spirit summon");
            server.runOnServer(mcServer -> require(SpiritManager.findSpirits(
                connection.getServerPlayer().getScoreboardName(), mcServer).size() == 1, "summon is idempotent"));
            server.runCommand("execute as @p run spirit dismiss");
            server.runOnServer(mcServer -> require(SpiritManager.findSpirits(
                connection.getServerPlayer().getScoreboardName(), mcServer).isEmpty(), "dismiss removes the companion"));
            context.waitTicks(45);
            server.runOnServer(mcServer -> require(SpiritManager.findSpirits(
                connection.getServerPlayer().getScoreboardName(), mcServer).isEmpty(), "hidden companion stays dismissed"));
            server.runCommand("execute as @p run spirit summon");
            server.runCommand("tp @p 100 80 100");
            server.waitFor(mcServer -> SpiritManager.findSpirits(connection.getServerPlayer().getScoreboardName(), mcServer)
                .stream().anyMatch(spirit -> spirit.distanceToSqr(connection.getServerPlayer()) < 100));
            server.runCommand("execute as @p in minecraft:the_nether run tp @s 0 80 0");
            server.waitFor(mcServer -> {
                var player = connection.getServerPlayer();
                var spirits = SpiritManager.findSpirits(player.getScoreboardName(), mcServer);
                return spirits.size() == 1 && spirits.getFirst().level() == player.level();
            });
            server.runOnServer(mcServer -> SpiritBubbles.speak(connection.getServerPlayer(), "Даже здесь я рядом."));
            connection.waitForClientboundPackets();
            int netherSpirit = server.computeOnServer(mcServer -> SpiritManager.findSpirits(
                connection.getServerPlayer().getScoreboardName(), mcServer).getFirst().getId());
            context.runOnClient(client -> require(BubbleStore.get(netherSpirit) != null, "bubble cache survives dimension change"));
        }
        try (var reopened = savedWorld.open()) {
            reopened.getServer().waitFor(mcServer -> SpiritManager.findSpirits(
                reopened.getConnection().getServerPlayer().getScoreboardName(), mcServer).size() == 1);
            reopened.getConnection().waitForClientboundPackets();
            context.waitFor(client -> !DialogHistory.lines().isEmpty());
            int spiritId = reopened.getServer().computeOnServer(mcServer -> SpiritManager.findSpirits(
                reopened.getConnection().getServerPlayer().getScoreboardName(), mcServer).getFirst().getId());
            context.runOnClient(client -> require(BubbleStore.get(spiritId) != null, "welcome bubble after world reopen"));
        }
    }

    private static void verifyDialogue(ClientGameTestContext context,
            net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext world) {
        var server = world.getServer();
        var connection = world.getConnection();
        var requests = new AtomicInteger();
        var responseStatus = new AtomicInteger(200);
        var firstReply = new CountDownLatch(1);
        var requestBody = new AtomicReference<JsonObject>();
        HttpServer mock;
        try { mock = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); }
        catch (java.io.IOException error) { throw new RuntimeException(error); }
        mock.createContext("/chat/completions", exchange -> {
            requestBody.set(new Gson().fromJson(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), JsonObject.class));
            int number = requests.incrementAndGet();
            try {
                if (number == 1 && !firstReply.await(10, TimeUnit.SECONDS)) throw new AssertionError("mock reply was never released");
            } catch (InterruptedException error) { throw new RuntimeException(error); }
            byte[] body = "{\"choices\":[{\"message\":{\"content\":\"Я слышу тебя. Я рядом!\"}}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(responseStatus.get(), body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        mock.start();
        try {
            server.runOnServer(mcServer -> {
                var cfg = SpiritConfig.get();
                cfg.provider = "custom";
                cfg.custom.baseUrl = "http://127.0.0.1:" + mock.getAddress().getPort();
                cfg.custom.model = "mock-model";
                cfg.custom.apiKey = null;
                cfg.maxTokens = 222;
                cfg.cooldownMs = 0;
                cfg.proactive = false;
                LlmProviders.reset();
            });
            int memoryBefore = server.computeOnServer(mcServer -> SpiritMemoryStore.turns(connection.getServerPlayer().getUUID()).size());
            context.runOnClient(client -> {
                ClientPlayNetworking.send(new SpiritChatC2SPayload("Ты здесь?"));
                ClientPlayNetworking.send(new SpiritChatC2SPayload("Второй вопрос подряд"));
            });
            context.waitFor(client -> requests.get() == 1);
            context.waitFor(client -> DialogHistory.lines().stream().anyMatch(line -> line.text().contains("предыдущий вопрос")));
            firstReply.countDown();
            context.waitFor(client -> DialogHistory.lines().stream().anyMatch(line -> line.text().equals("Я слышу тебя. Я рядом!")));
            context.takeScreenshot("spirit-dialog-conversation");
            require(requests.get() == 1, "one in-flight HTTP call despite two C2S questions");
            require(requestBody.get().get("max_tokens").getAsInt() == 222, "configured token budget reaches LLM");
            server.runOnServer(mcServer -> {
                require(SpiritMemoryStore.turns(connection.getServerPlayer().getUUID()).size() == Math.min(8, memoryBefore + 1),
                    "only the answered question enters memory");
            });
            responseStatus.set(503);
            context.runOnClient(client -> ClientPlayNetworking.send(new SpiritChatC2SPayload("Проверка ошибки")));
            context.waitFor(client -> DialogHistory.lines().stream().anyMatch(line -> line.text().contains("улетел за мыслями")));
            server.runOnServer(mcServer -> require(SpiritMemoryStore.turns(connection.getServerPlayer().getUUID()).size() == Math.min(8, memoryBefore + 1),
                "HTTP errors must not pollute conversation memory"));
        } finally {
            firstReply.countDown();
            mock.stop(0);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
