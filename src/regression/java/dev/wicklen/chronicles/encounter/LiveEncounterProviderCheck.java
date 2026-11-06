package dev.wicklen.chronicles.encounter;

import com.google.gson.JsonParser;
import dev.wicklen.chronicles.ai.llm.*;
import dev.wicklen.chronicles.core.encounter.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Explicit opt-in external check using anonymous capabilities and the exact production boundary. */
public final class LiveEncounterProviderCheck {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Explicit private config path required");
        var config = JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject();
        String id = config.get("provider").getAsString();
        var settings = config.getAsJsonObject(id);
        String model = settings.get("model").getAsString(), base = settings.get("baseUrl").getAsString();
        var secret = settings.get("apiKey");
        String key = secret == null || secret.isJsonNull() ? null : secret.getAsString();
        if (key != null && key.startsWith("env:")) key = System.getenv(key.substring(4));
        LlmProvider provider = id.equals("ollama") ? new OllamaProvider(base, model)
                : new OpenAiCompatibleProvider(id, base, model, key);
        var party = List.of(new CombatantSnapshot(UUID.randomUUID(), 6, 15, 20, true, false, false));
        var recent = new RecentForm(0, 2);
        var options = EncounterDirector.options(EncounterMode.NORMAL, party, recent);
        String response;
        try {
            response = provider.complete(new LlmRequest(EncounterPlanningService.prompt(),
                    List.of(LlmMessage.user(EncounterPlanningService.context(EncounterMode.NORMAL, party, recent, options))),
                    96, 0.35, true)).get(35, TimeUnit.SECONDS);
        } catch (Exception error) {
            Throwable cause = error;
            while (cause.getCause() != null) cause = cause.getCause();
            var status = java.util.regex.Pattern.compile("HTTP (\\d{3})").matcher(String.valueOf(cause.getMessage()));
            throw new IllegalStateException("Live combat provider failed: " + (status.find() ? status.group() : cause.getClass().getSimpleName()));
        }
        var selected = EncounterPlanningService.decode(response, options);
        if (selected.damageMultiplier() > 0.95 || selected.waves().stream().anyMatch(w -> w.totalMobs() > 8))
            throw new AssertionError("Gentle policy exceeded");
        System.out.println("PASS: live " + id + " / " + model + "; selected authored " + selected.strategyId()
                + "; anonymous equipment input, bounded waves, no fallback.");
    }
}
