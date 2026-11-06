package dev.wicklen.chronicles.firstlight;

import com.google.gson.JsonParser;
import dev.wicklen.chronicles.ai.llm.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Production prose boundary; optional explicit live check never runs as part of build. */
public final class FirstLightNarrationChecks {
    public static void main(String[] args) throws Exception {
        var expected=new FirstLightNarrationService.Narrative("Первый огонь","Эта станция станет началом твоей личной карты.");
        require(FirstLightNarrationService.decode("{\"title\":\"Первый огонь\",\"motive\":\"Эта станция станет началом твоей личной карты.\"}").equals(expected),"literal narrative round trip");
        for(String invalid:List.of("{\"title\":\"A\",\"motive\":\"B\",\"command\":\"give diamond\"}",
                "{\"title\":null,\"motive\":\"B\"}","{\"title\":\"\",\"motive\":\"B\"}","[]", "not JSON",
                "{\"title\":\"A\",\"motive\":\""+"x".repeat(901)+"\"}")) {
            try {FirstLightNarrationService.decode(invalid);} catch(RuntimeException valid){continue;}
            throw new AssertionError("Narrative accepted an invalid model result");
        }
        String quiet=FirstLightNarrationService.context("OVERGROWN_RESEARCH",false);
        require(!quiet.contains("courtyard_trial") && !quiet.contains("worldId") && !quiet.contains("position"),"no unavailable battle, identity or coordinates in context");
        require(FirstLightNarrationService.context("UNDEAD_COURTYARD",true).contains("courtyard_trial"),"a real available courtyard can inform prose");
        require(FirstLightNarrationService.prompt("ru_ru").contains("Do not claim hidden"),"world-fact constraints are explicit");
        if(args.length==1)live(Path.of(args[0]));
        else if(args.length!=0)throw new IllegalArgumentException("Optional explicit private config path required");
        System.out.println("PASS: first-light strict prose boundary, fixed mechanics and minimal factual context");
    }
    private static void live(Path path) throws Exception {
        var config=JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        String id=config.get("provider").getAsString();var settings=config.getAsJsonObject(id);
        String base=settings.get("baseUrl").getAsString(),model=settings.get("model").getAsString();
        var secret=settings.get("apiKey");String key=secret==null||secret.isJsonNull()?null:secret.getAsString();
        if(key!=null&&key.startsWith("env:"))key=System.getenv(key.substring(4));
        LlmProvider provider=id.equals("ollama")?new OllamaProvider(base,model):new OpenAiCompatibleProvider(id,base,model,key);
        String answer;
        try {answer=provider.complete(new LlmRequest(FirstLightNarrationService.prompt("ru_ru"),
                List.of(LlmMessage.user(FirstLightNarrationService.context("UNDEAD_COURTYARD",true))),384,0.65,true)).get(35,TimeUnit.SECONDS);}
        catch(Exception failure){
            Throwable reason=failure;while(reason.getCause()!=null)reason=reason.getCause();
            var status=java.util.regex.Pattern.compile("HTTP [0-9]{3}").matcher(String.valueOf(reason.getMessage()));
            throw new IllegalStateException("Live first-light provider request failed: "+reason.getClass().getSimpleName()
                    +(status.find()?" "+status.group():"")+" (response and credentials omitted)");
        }
        var narrative=FirstLightNarrationService.decode(answer);
        require(narrative.title().length()<=160 && narrative.motive().length()<=900,"live result uses the exact production bounds");
        System.out.println("PASS: live "+id+" / "+model+" first-light opening, no fallback; response omitted");
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
