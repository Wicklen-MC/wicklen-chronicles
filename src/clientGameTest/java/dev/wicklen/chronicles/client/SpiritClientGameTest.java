package dev.wicklen.chronicles.client;

import dev.wicklen.chronicles.SpiritBubbles;
import dev.wicklen.chronicles.SpiritManager;
import dev.wicklen.chronicles.ai.SpiritConfig;
import dev.wicklen.chronicles.ai.SpiritQuests;
import dev.wicklen.chronicles.ai.SpiritMemoryStore;
import dev.wicklen.chronicles.ai.WorldStorageContext;
import dev.wicklen.chronicles.core.quest.QuestDefinition;
import dev.wicklen.chronicles.core.quest.QuestStatus;
import dev.wicklen.chronicles.core.quest.QuestType;
import dev.wicklen.chronicles.core.quest.RewardDefinition;
import java.util.UUID;
import dev.wicklen.chronicles.ai.llm.LlmProviders;
import dev.wicklen.chronicles.net.SpiritChatC2SPayload;
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
        var pendingReward = new AtomicReference<UUID>();
        var worldId = new AtomicReference<UUID>();
        var sessionId = new AtomicReference<UUID>();
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
            // Readiness never consumes items. Hand-in and reward are explicit, durable actions.
            server.runOnServer(mcServer -> {
                var player = connection.getServerPlayer();
                var cfg = SpiritConfig.get();
                cfg.proactive = false;
                cfg.custom.baseUrl = null;
                cfg.provider = "deepseek";
                LlmProviders.reset();
                var definition = new QuestDefinition("redzspirit:test/apples", 1,
                    QuestType.COLLECT, "minecraft:apple", 3,
                    new RewardDefinition("minecraft:emerald", 2));
                require(SpiritQuests.issue(player.getUUID(), definition) != null, "deterministic quest issued");
                worldId.set(WorldStorageContext.worldId());
                sessionId.set(WorldStorageContext.sessionId());
            });
            server.runCommand("give @p minecraft:apple 3");
            server.waitFor(mcServer -> SpiritQuests.active(connection.getServerPlayer().getUUID()).status() == QuestStatus.READY, 300);
            context.waitTicks(45);
            server.runOnServer(mcServer -> {
                var player = connection.getServerPlayer();
                require(count(player, net.minecraft.world.item.Items.APPLE) == 3,
                    "ready quest does not automatically consume materials");
                require(!SpiritQuests.turnIn(player, UUID.randomUUID()).success(), "stale hand-in rejected");
                require(count(player, net.minecraft.world.item.Items.APPLE) == 3, "rejected action preserves materials");
            });
            server.runCommand("execute as @p run spirit quest turnin");
            server.runOnServer(mcServer -> {
                var player = connection.getServerPlayer();
                var quest = SpiritQuests.active(player.getUUID());
                require(quest.status() == QuestStatus.REWARD_PENDING, "explicit command submits the quest");
                require(count(player, net.minecraft.world.item.Items.APPLE) == 0, "hand-in consumes exact materials");
                pendingReward.set(quest.rewardId());
                require(!SpiritQuests.turnIn(player, quest.id()).success(), "repeated hand-in rejected");
                for (int slot = 0; slot < player.getInventory().getNonEquipmentItems().size(); slot++)
                    player.getInventory().setItem(slot, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE, 64));
                require(!SpiritQuests.claim(player, quest.rewardId()).success(), "full inventory keeps reward pending");
                require(count(player, net.minecraft.world.item.Items.EMERALD) == 0, "no partial delivery with full inventory");
                require(SpiritQuests.active(player.getUUID()).status() == QuestStatus.REWARD_PENDING,
                    "failed delivery does not complete quest");
                player.getInventory().clearContent();
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
            reopened.getServer().runOnServer(mcServer -> {
                var player = reopened.getConnection().getServerPlayer();
                require(worldId.get().equals(WorldStorageContext.worldId()), "world ID survives restart");
                require(!sessionId.get().equals(WorldStorageContext.sessionId()), "runtime session changes on reopen");
                var quest = SpiritQuests.active(player.getUUID());
                require(quest != null && quest.status() == QuestStatus.REWARD_PENDING,
                    "pending reward survives restart");
                require(pendingReward.get().equals(quest.rewardId()), "reward identity survives restart");
            });
            reopened.getServer().runCommand("execute as @p run spirit quest claim");
            reopened.getServer().runOnServer(mcServer -> {
                var player = reopened.getConnection().getServerPlayer();
                require(SpiritQuests.active(player.getUUID()) == null, "successful delivery archives quest");
                require(count(player, net.minecraft.world.item.Items.EMERALD) == 2, "exact reward delivered");
                require(!SpiritQuests.claim(player, pendingReward.get()).success(), "replayed reward rejected");
                require(count(player, net.minecraft.world.item.Items.EMERALD) == 2, "replay gives no extra items");
            });
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

    private static int count(net.minecraft.server.level.ServerPlayer player, net.minecraft.world.item.Item item) {
        int count = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            var stack = player.getInventory().getItem(slot);
            if (stack.is(item)) count += stack.getCount();
        }
        return count;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
