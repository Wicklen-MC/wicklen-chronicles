package dev.wicklen.chronicles.ai;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.sun.net.httpserver.HttpServer;
import dev.wicklen.chronicles.ai.llm.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Offline regression checks; no running game or API credentials required. */
public final class RegressionChecks {
    public static void main(String[] args) throws Exception {
        limits();
        serverQueue();
        persistence();
        config();
        protocols();
        System.out.println("PASS: limits (including concurrent requests), persistence, config, OpenAI/Ollama HTTP protocols");
    }

    private static void limits() throws Exception {
        var limiter = new RequestLimiter();
        var id = UUID.randomUUID();
        require(limiter.acquire(id, 0, 100, 2) == RequestLimiter.Result.ACCEPTED, "first request");
        require(limiter.acquire(id, 200, 100, 2) == RequestLimiter.Result.BUSY, "pending HTTP blocks overlapping requests");
        limiter.complete(id); // failure releases the request but still consumes the budget
        require(limiter.acquire(id, 99, 100, 2) == RequestLimiter.Result.COOLDOWN, "cooldown begins at dispatch");
        require(limiter.acquire(id, 100, 100, 2) == RequestLimiter.Result.ACCEPTED, "cooldown boundary");
        limiter.complete(id);
        require(limiter.acquire(id, 300, 100, 2) == RequestLimiter.Result.HOURLY_LIMIT, "hourly cap");
        require(limiter.acquire(id, 3_600_000, 100, 2) == RequestLimiter.Result.ACCEPTED, "hourly window expires");
        limiter.clear();
        var start = new CountDownLatch(1);
        var accepted = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(12)) {
            var futures = new ArrayList<Future<?>>();
            for (int i = 0; i < 12; i++) futures.add(executor.submit(() -> {
                try { start.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
                if (limiter.acquire(id, 0, 0, 100) == RequestLimiter.Result.ACCEPTED) accepted.incrementAndGet();
            }));
            start.countDown();
            for (var future : futures) future.get(5, TimeUnit.SECONDS);
        }
        require(accepted.get() == 1, "only one concurrent request is admitted");
    }

    private static void persistence() throws Exception {
        var directory = Files.createTempDirectory("wicklen-store-");
        var file = directory.resolve("memory.json");
        Files.writeString(file, "{\"kept\":\"old memory\"}");
        var store = new JsonStore<Map<String, String>>("memory.json", new TypeToken<>() {}, directory);
        store.save();
        require(Files.readString(file).contains("old memory"), "stopping before first use preserves disk memory");
        var data = store.get(HashMap::new);
        data.put("new", "Новая реплика");
        store.save();
        var copy = new JsonStore<Map<String, String>>("memory.json", new TypeToken<>() {}, directory);
        require(copy.get(HashMap::new).get("new").equals("Новая реплика"), "UTF-8 persistence round trip");
        require(!Files.exists(directory.resolve("memory.json.tmp")), "atomic save leaves no pending file");
        JsonStore.resetAll();
        require(store.get(HashMap::new).containsKey("kept"), "store reload after server lifecycle reset");
        Files.delete(file);
        Files.deleteIfExists(directory.resolve("memory.json.v0.bak"));
        Files.delete(directory);
    }

    private static void serverQueue() throws Exception {
        var clock = new java.util.concurrent.atomic.AtomicLong();
        var queue = new LlmQueue(1, 1, 3, 20, clock::get);
        var first = new CompletableFuture<String>();
        var started = new CountDownLatch(1);
        var result = queue.submit(5, () -> { started.countDown(); return first; });
        require(started.await(5, TimeUnit.SECONDS), "first server request starts");
        var secondStarted = new java.util.concurrent.atomic.AtomicBoolean();
        var second = queue.submit(5, () -> { secondStarted.set(true); return CompletableFuture.completedFuture("second"); });
        require(!secondStarted.get(), "queued request waits for a free slot");
        require(queue.submit(5, () -> CompletableFuture.completedFuture("overflow")).isCompletedExceptionally(),
            "queue capacity is bounded");
        first.complete("first");
        require(result.get(5, TimeUnit.SECONDS).equals("first") && second.get(5, TimeUnit.SECONDS).equals("second"),
            "completion dispatches the waiting request");
        require(queue.submit(11, () -> CompletableFuture.completedFuture("over budget")).isCompletedExceptionally(),
            "maximum output tokens are reserved before dispatch");
        var failed = queue.submit(5, () -> CompletableFuture.failedFuture(new java.io.IOException("offline")));
        try { failed.get(5, TimeUnit.SECONDS); throw new AssertionError("failure must propagate"); }
        catch (ExecutionException expected) { /* transport failure still consumes an admitted request */ }
        require(queue.submit(1, () -> CompletableFuture.completedFuture("hourly overflow")).isCompletedExceptionally(),
            "failed requests also consume the shared hourly request budget");
        clock.set(3_600_000);
        require(queue.submit(5, () -> CompletableFuture.completedFuture("new hour")).get(5, TimeUnit.SECONDS).equals("new hour"),
            "server budget renews on the next hourly window");
        var pending = new CompletableFuture<String>();
        var pendingStarted = new CountDownLatch(1);
        var stale = queue.submit(5, () -> { pendingStarted.countDown(); return pending; });
        require(pendingStarted.await(5, TimeUnit.SECONDS), "pending request starts before close");
        queue.close();
        require(stale.isCompletedExceptionally(), "world close rejects its in-flight result");
        pending.complete("late response");
        require(queue.submit(1, () -> CompletableFuture.completedFuture("after close")).isCompletedExceptionally(),
            "closed world cannot submit a new request");
    }

    private static void config() {
        var cfg = new Gson().fromJson("""
            {"deepseek":null,"openai":{"baseUrl":null,"model":null},"ollama":null,"custom":null,
             "provider":null,"cooldownMs":-1,"maxTurnsPerHour":-1,"maxTokens":-8,"temperature":900}
            """, SpiritConfig.class);
        cfg.normalize();
        require(cfg.deepseek != null && cfg.ollama != null && cfg.custom != null, "null provider sections");
        require(cfg.openai.model != null && cfg.openai.baseUrl != null, "null provider values");
        require(cfg.cooldownMs >= 0 && cfg.maxTurnsPerHour > 0 && cfg.maxTokens > 0 && cfg.temperature == 2, "invalid numeric settings");
    }

    private static void protocols() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var requests = new ConcurrentHashMap<String, JsonObject>();
        var auth = new ConcurrentHashMap<String, String>();
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            requests.put(path, new Gson().fromJson(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), JsonObject.class));
            auth.put(path, Objects.toString(exchange.getRequestHeaders().getFirst("Authorization"), ""));
            String response = switch (path) {
                case "/chat/completions" -> "{\"choices\":[{\"message\":{\"content\":\" Привет! \"}}]}";
                case "/api/chat" -> "{\"message\":{\"content\":\" Я рядом. \"}}";
                default -> "{\"error\":\"test failure\"}";
            };
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(path.startsWith("/fail") ? 503 : 200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort();
            var request = new LlmRequest("Характер", List.of(LlmMessage.user("Вопрос"), LlmMessage.assistant("Ответ")), 222, 0.4);
            var openai = new OpenAiCompatibleProvider("mock", url + "/", "mock-model", "test-key");
            require(openai.complete(request).get(5, TimeUnit.SECONDS).equals("Привет!"), "OpenAI response");
            var body = requests.get("/chat/completions");
            require(body.getAsJsonArray("messages").size() == 3, "messages encoded as array with system and history");
            require(body.get("max_tokens").getAsInt() == 222 && body.get("temperature").getAsDouble() == 0.4, "config reaches HTTP body");
            require(auth.get("/chat/completions").equals("Bearer test-key"), "authorization header");
            require(new OllamaProvider(url + "/", "local").complete(request).get(5, TimeUnit.SECONDS).equals("Я рядом."), "Ollama response");
            var local = requests.get("/api/chat");
            require(!local.get("stream").getAsBoolean() && local.getAsJsonObject("options").get("num_predict").getAsInt() == 222, "native Ollama protocol");
            var structured = new LlmRequest("Return JSON", List.of(LlmMessage.user("{}")), 1024, 0.7, true);
            new OpenAiCompatibleProvider("deepseek", url, "mock-model", "test-key").complete(structured).get(5, TimeUnit.SECONDS);
            var structuredBody = requests.get("/chat/completions");
            require(structuredBody.getAsJsonObject("response_format").get("type").getAsString().equals("json_object")
                    && structuredBody.getAsJsonObject("thinking").get("type").getAsString().equals("disabled"),
                "DeepSeek stories explicitly request non-thinking JSON output");
            new OllamaProvider(url, "local").complete(structured).get(5, TimeUnit.SECONDS);
            require(requests.get("/api/chat").get("format").getAsString().equals("json"), "Ollama stories request native JSON mode");
            try {
                new OpenAiCompatibleProvider("mock", url + "/fail", "mock", null).complete(request).get(5, TimeUnit.SECONDS);
                throw new AssertionError("HTTP errors must fail");
            } catch (ExecutionException expected) {
                require(expected.getCause().getMessage().contains("503"), "HTTP failure status preserved");
            }
        } finally { server.stop(0); }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
