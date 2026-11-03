package dev.redzumi.spirit.ai.llm;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI-совместимый провайдер (POST {baseUrl}/chat/completions).
 * Подходит для DeepSeek, OpenAI, Groq, Together, локальных прокси и т.п.
 */
public final class OpenAiCompatibleProvider implements LlmProvider {
    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10)).build();

    private final String id;
    private final String baseUrl;
    private final String model;
    private final String apiKey; // nullable

    public OpenAiCompatibleProvider(String id, String baseUrl, String model, String apiKey) {
        this.id = id;
        this.baseUrl = baseUrl;
        this.model = model;
        this.apiKey = apiKey;
    }

    @Override
    public String id() { return id; }

    @Override
    public CompletableFuture<String> complete(LlmRequest request) {
        JsonArray messages = new JsonArray();
        if (request.system() != null) messages.add(msg("system", request.system()));
        for (LlmMessage m : request.messages()) messages.add(msg(m.role(), m.content()));

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.add("messages", messages);
        body.addProperty("max_tokens", request.maxTokens());
        body.addProperty("temperature", request.temperature());

        var builder = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/chat/completions"))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json");
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }

        return HTTP.sendAsync(builder.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body))).build(),
                HttpResponse.BodyHandlers.ofString())
            .orTimeout(25, TimeUnit.SECONDS)
            .thenApply(resp -> {
                if (resp.statusCode() != 200) {
                    throw new RuntimeException(id + " HTTP " + resp.statusCode() + ": " + resp.body().lines().limit(2).reduce("", String::concat));
                }
                return GSON.fromJson(resp.body(), JsonObject.class)
                    .getAsJsonArray("choices").get(0).getAsJsonObject()
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
