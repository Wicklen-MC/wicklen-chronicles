package dev.redzumi.spirit.ai.llm;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Локальный Ollama (POST {baseUrl}/api/chat, stream=false).
 * Ключ не нужен; подходит для бесплатных фоновых реплик.
 */
public final class OllamaProvider implements LlmProvider {
    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5)).build();

    private final String baseUrl;
    private final String model;

    public OllamaProvider(String baseUrl, String model) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.model = model;
    }

    @Override
    public String id() { return "ollama"; }

    @Override
    public CompletableFuture<String> complete(LlmRequest request) {
        JsonArray messages = new JsonArray();
        if (request.system() != null) messages.add(msg("system", request.system()));
        for (LlmMessage m : request.messages()) messages.add(msg(m.role(), m.content()));

        JsonObject options = new JsonObject();
        options.addProperty("num_predict", request.maxTokens());
        options.addProperty("temperature", request.temperature());

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.add("messages", messages);
        body.addProperty("stream", false);
        body.add("options", options);

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/chat"))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
            .build();

        return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
            .orTimeout(35, TimeUnit.SECONDS)
            .thenApply(resp -> {
                if (resp.statusCode() != 200) {
                    throw new RuntimeException("ollama HTTP " + resp.statusCode());
                }
                return GSON.fromJson(resp.body(), JsonObject.class)
                    .getAsJsonObject("message").get("content").getAsString().trim();
            });
    }

    private static JsonObject msg(String role, String content) {
        JsonObject o = new JsonObject();
        o.addProperty("role", role);
        o.addProperty("content", content);
        return o;
    }
}
