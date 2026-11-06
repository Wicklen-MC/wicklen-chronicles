package dev.wicklen.chronicles.story;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.wicklen.chronicles.ai.llm.*;
import dev.wicklen.chronicles.core.discovery.*;
import dev.wicklen.chronicles.core.quest.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Explicit opt-in network check. Reads a private config without changing it or printing credentials. */
public final class LiveStoryProviderCheck {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("An explicit private config path is required");
        JsonObject config = new Gson().fromJson(Files.readString(Path.of(args[0])), JsonObject.class);
        String providerId = config.get("provider").getAsString();
        JsonObject settings = config.getAsJsonObject(providerId);
        if (settings == null) throw new IllegalArgumentException("Provider section is missing");
        String model = settings.get("model").getAsString();
        String baseUrl = settings.get("baseUrl").getAsString();
        String key = secret(settings);
        LlmProvider provider = providerId.equals("ollama") ? new OllamaProvider(baseUrl, model)
            : new OpenAiCompatibleProvider(providerId, baseUrl, model, key);
        UUID world = UUID.randomUUID(), owner = UUID.randomUUID();
        var observation = new Observation(new DiscoveryKey(DiscoveryKind.CREATURE, "minecraft:cow"),
            "Cow", "A cow observed in an open meadow with a spyglass.",
            new DiscoveryPosition("minecraft:overworld", 0, 70, 0), 1);
        var facts = DiscoveryTransitions.observe(DiscoveryTransitions.empty(world, owner), owner, observation, null).snapshot();
        var reward = new RewardDefinition("minecraft:emerald", 1);
        var goals = List.of(new StoryPlans.Goal("minecraft:paper", "Bring 4 paper for field notes",
                new QuestDefinition("wicklen_chronicles:live/notes", 1, QuestType.COLLECT, "minecraft:paper", 4, reward)),
            new StoryPlans.Goal("minecraft:wheat", "Bring 4 wheat for an expedition",
                new QuestDefinition("wicklen_chronicles:live/supplies", 1, QuestType.COLLECT, "minecraft:wheat", 4, reward)));
        String context = new Gson().toJson(Map.of("source", facts.entries().getFirst(), "goals", goals, "previousDecisions", List.of()));
        String response;
        try {
            response = provider.complete(new LlmRequest(StoryPlans.prompt("ru_ru"), List.of(LlmMessage.user(context)), 1024, 0.7, true))
                .get(35, TimeUnit.SECONDS);
        } catch (Exception failure) {
            Throwable cause = failure;
            while (cause.getCause() != null) cause = cause.getCause();
            var status = java.util.regex.Pattern.compile("HTTP (\\d{3})").matcher(String.valueOf(cause.getMessage()));
            throw new IllegalStateException("Live provider request failed: " + (status.find() ? status.group() : cause.getClass().getSimpleName()));
        }
        // Never print the raw provider response; pass it through the exact production boundary.
        Files.writeString(Path.of("response.private.txt"), response);
        var plan = StoryPlans.decode(response, UUID.randomUUID(), facts, facts.entries().getFirst(), goals);
        if (plan.choices().size() != 2) throw new AssertionError("Expected two validated choices");
        System.out.println("PASS: live " + providerId + " / " + model + "; two validated story choices, fixed goals/rewards, no fallback.");
    }

    private static String secret(JsonObject settings) {
        var value = settings.get("apiKey");
        if (value == null || value.isJsonNull()) return null;
        String key = value.getAsString();
        return key.startsWith("env:") ? System.getenv(key.substring(4)) : key;
    }
}
