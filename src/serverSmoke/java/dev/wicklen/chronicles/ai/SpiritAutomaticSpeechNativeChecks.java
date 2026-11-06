package dev.wicklen.chronicles.ai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.sun.net.httpserver.HttpServer;
import dev.wicklen.chronicles.ai.llm.LlmProviders;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** One isolated server, actual localhost HTTP: every automatic entry point shares the preference gate. */
public final class SpiritAutomaticSpeechNativeChecks {
    public static void verify(MinecraftServer server) throws Exception {
        require(Boolean.getBoolean("wicklen_chronicles.serverSmoke"), "automatic speech checks require an isolated smoke world");
        var requests = new LinkedBlockingQueue<JsonObject>();
        var mock = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mock.createContext("/chat/completions", exchange -> {
            JsonObject body = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            try {
                byte[] reply = "{\"choices\":[{\"message\":{\"content\":\"A short grounded reply.\"}}]}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, reply.length);
                exchange.getResponseBody().write(reply);
            } finally { exchange.close(); requests.offer(body); }
        });
        mock.start();
        var cfg = SpiritConfig.get();
        String oldProvider = cfg.provider, oldUrl = cfg.custom.baseUrl, oldModel = cfg.custom.model;
        boolean oldProactive = cfg.proactive;
        long oldCooldown = cfg.cooldownMs;
        try {
            cfg.provider = "custom";
            cfg.custom.baseUrl = "http://127.0.0.1:" + mock.getAddress().getPort();
            cfg.custom.model = "automatic-speech-local-probe";
            cfg.cooldownMs = 0;
            cfg.proactive = true;
            String override = System.getenv("CUSTOM_BASE_URL");
            require(override == null || override.isBlank() || override.equals(cfg.custom.baseUrl), "environment must not redirect the localhost-only speech test");
            LlmProviders.reset(); SpiritAi.reset();
            require(LlmProviders.active() != null && LlmProviders.active().id().equals("custom"), "speech tests must never use an external provider");
            var one = actor(server, "AutoProbeOne");
            var two = actor(server, "AutoProbeTwo");
            var three = actor(server, "AutoProbeThree");
            require(SpiritSpeechPreferences.update(one, true, 2), "first actor sets the minimum supported interval");
            SpiritMemoryStore.append(one.getUUID(), "I have 46 achievements", "Поздравляю с 46 достижениями!");
            SpiritAi.narrate(server, one, "A newly confirmed personal field observation is ready.");
            JsonObject automatic = requests.poll(5, TimeUnit.SECONDS);
            require(automatic != null && automatic.getAsJsonArray("messages").size() == 2,
                    "automatic speech never replays stale conversational achievement congratulations");
            require(!SpiritSpeechPreferences.automaticAllowed(one.getUUID(), System.currentTimeMillis()), "automatic dispatch reserves the persistent shared interval");
            long dispatchedRevision = SpiritSpeechPreferences.revision(one.getUUID());
            require(SpiritSpeechPreferences.update(one, false, 2), "automatic speech can be disabled while a reply is in flight");
            require(!SpiritSpeechPreferences.deliveryAllowed(one.getUUID(), dispatchedRevision), "the production late-response guard rejects the disabled reply before HUD/history delivery");
            SpiritAi.narrate(server, one, "A death/world event must obey the same opt-out.");
            require(requests.poll(200, TimeUnit.MILLISECONDS) == null, "disabled automatic world events send no provider request");
            // The transport has finished; clear only the isolated shared request fixture while its server callback is queued.
            SpiritAi.reset();
            SpiritAi.requestedAction(server, one, "The player explicitly asked for quest feedback.");
            require(requests.poll(5, TimeUnit.SECONDS) != null, "explicit quest feedback remains available with automatic speech disabled");
            require(SpiritSpeechPreferences.update(one, true, 2), "actor can re-enable automatic remarks");
            require(!SpiritSpeechPreferences.deliveryAllowed(one.getUUID(), dispatchedRevision), "re-enabling does not resurrect an old in-flight remark");
            SpiritAi.narrate(server, one, "A different automatic cause still shares the same interval.");
            require(requests.poll(200, TimeUnit.MILLISECONDS) == null, "changing automatic causes cannot bypass the shared interval");
            SpiritAi.reset();
            SpiritAi.chatWindow(server, one, "This is an explicit manual question.", ignored -> {});
            require(requests.poll(5, TimeUnit.SECONDS) != null, "manual dialogue is not delayed by the automatic ten-minute clock");
            require(!SpiritSpeechPreferences.automaticAllowed(one.getUUID(), System.currentTimeMillis()), "manual replies do not reset or consume a different automatic clock");
            SpiritAi.reset();
            SpiritAi.narrate(server, two, "Another player's own current observation.");
            require(requests.poll(5, TimeUnit.SECONDS) != null, "automatic frequency is personal rather than a global silence for everyone");
            cfg.proactive = false;
            SpiritAi.narrate(server, three, "Global automatic switch is disabled.");
            require(requests.poll(200, TimeUnit.MILLISECONDS) == null, "the global automatic setting gates WorldEvents as well as Proactive");
            SpiritAi.requestedAction(server, three, "An explicitly requested guide answer.");
            require(requests.poll(5, TimeUnit.SECONDS) != null, "the global automatic switch does not disable explicitly requested guide feedback");
            cfg.proactive = true;
            var stale = actor(server, "StaleProbe");
            SpiritAi.narrate(server, stale, "The triggering fact is no longer relevant.", () -> false);
            require(requests.poll(200, TimeUnit.MILLISECONDS) == null && SpiritSpeechPreferences.automaticAllowed(stale.getUUID(), System.currentTimeMillis()),
                    "a stale cause is dropped before provider dispatch and does not reserve a new interval");
            System.out.println("PASS: native automatic opt-out/shared interval, late-reply invalidation, manual chat/quest bypass, personal clocks and stale-cause rejection");
        } finally {
            mock.stop(0);
            cfg.provider = oldProvider; cfg.custom.baseUrl = oldUrl; cfg.custom.model = oldModel;
            cfg.proactive = oldProactive; cfg.cooldownMs = oldCooldown;
            LlmProviders.reset(); SpiritAi.reset();
        }
    }

    private static ServerPlayer actor(MinecraftServer server, String name) {
        return new ServerPlayer(server, server.overworld(), new GameProfile(UUID.randomUUID(), name), ClientInformation.createDefault());
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private SpiritAutomaticSpeechNativeChecks() {}
}
