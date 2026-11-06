package dev.wicklen.chronicles.ai;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import dev.wicklen.chronicles.ai.llm.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Explicit opt-in: one synthetic question, no player/world data sent to the configured provider. */
public final class LiveGameKnowledgeCheck {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Supply one private provider config path");
        var config = JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject();
        String id = config.get("provider").getAsString();
        var settings = config.getAsJsonObject(id);
        String base = settings.get("baseUrl").getAsString(), model = settings.get("model").getAsString();
        var secret = settings.get("apiKey");
        String key = secret == null || secret.isJsonNull() ? null : secret.getAsString();
        if (key != null && key.startsWith("env:")) key = System.getenv(key.substring(4));
        LlmProvider provider = id.equals("ollama") ? new OllamaProvider(base, model) : new OpenAiCompatibleProvider(id, base, model, key);
        String context = "Вымышленный тест: местоположение гнёзд неизвестно. Ванильная справка; это не факты о найденных объектах.\n"
            + new Gson().toJson(SpiritGameKnowledge.find("Где добыть мёд?", List.of()));
        String system = SpiritAi.systemPrompt(SpiritPersonas.ALL.get("firefly"), "Test traveller", "русский", context);
        String answer;
        try {
            answer = provider.complete(new LlmRequest(system, List.of(LlmMessage.user("Где добыть мёд и как его безопасно собрать?")), 240, 0.2)).get(35, TimeUnit.SECONDS);
        } catch (Exception failure) {
            Throwable reason = failure;
            while (reason.getCause() != null) reason = reason.getCause();
            throw new IllegalStateException("Live knowledge request failed: " + reason.getClass().getSimpleName() + " (provider response and credentials omitted)");
        }
        String lower = answer.toLowerCase(Locale.ROOT).replace('ё', 'е');
        if (answer.isBlank() || answer.length() > 2000 || !lower.matches("(?s).*(бутыл|пузыр|bottle).*")) throw new AssertionError("Review failed: missing bottle guidance");
        if (!lower.matches("(?s).*(кост|дым|campfire|smoke).*")) throw new AssertionError("Review failed: missing calm harvesting guidance");
        if (key != null && !key.isBlank()) answer = answer.replace(key, "[credential omitted]");
        System.out.println("PASS: one live vanilla-knowledge reply with the production system prompt; review wording below:");
        System.out.println(answer.replaceAll("[\\p{Cntrl}\\s]+", " ").strip());
    }
}
