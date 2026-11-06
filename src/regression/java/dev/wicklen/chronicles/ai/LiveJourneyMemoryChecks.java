package dev.wicklen.chronicles.ai;

import com.google.gson.JsonParser;
import dev.wicklen.chronicles.ai.llm.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Explicit opt-in: one real provider request using fictional facts and the production grounding rules. */
public final class LiveJourneyMemoryChecks {
    public static void main(String[] args) throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("One explicit private config path is required");
        var config=JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject();
        String id=config.get("provider").getAsString();var settings=config.getAsJsonObject(id);
        if(settings==null)throw new IllegalArgumentException("Provider settings are missing");
        String base=settings.get("baseUrl").getAsString(),model=settings.get("model").getAsString();
        var secret=settings.get("apiKey");String key=secret==null||secret.isJsonNull()?null:secret.getAsString();
        if(key!=null&&key.startsWith("env:"))key=System.getenv(key.substring(4));
        LlmProvider provider=id.equals("ollama")?new OllamaProvider(base,model):new OpenAiCompatibleProvider(id,base,model,key);
        var facts=new LinkedHashMap<String,Object>();
        facts.put("confirmedDiscoveries",2);
        facts.put("personalDiscoveries",List.of(Map.of("kind","CREATURE","canonical","Cow","personalLabel","Луговые коровы",
                "personalNote","Личное название мирного стада возле нашего первого места наблюдения. Это всё ещё обычные коровы.")));
        facts.put("firstLight",Map.of("sourceCanonical","Recorded Waylight station","chosenPath","SURVEY","status","ACTIVE","progress",1,"required",2));
        facts.put("guidance",Map.of("state","active","kind","SURVEY","canonical","Broad survey area","personalLabel","Окрестности Первого огня","otherDimension",false));
        var context=SpiritJourneyContext.bounded(facts,"fictional-live-probe",false);
        String system="Ты — тёплый дух-компаньон Виклена. Отвечай по-русски, кратко, в 1–2 предложениях.\n"
                +SpiritAi.groundingRules()+"\n"+context.text();
        String answer;
        try{
            answer=provider.complete(new LlmRequest(system,List.of(LlmMessage.user("Что ты помнишь о Луговых коровах и закончен ли наш поход?")),200,0.2))
                    .get(35,TimeUnit.SECONDS);
        }catch(Exception failure){
            Throwable reason=failure;while(reason.getCause()!=null)reason=reason.getCause();
            var status=java.util.regex.Pattern.compile("HTTP [0-9]{3}").matcher(String.valueOf(reason.getMessage()));
            throw new IllegalStateException("Live journey memory provider request failed: "+reason.getClass().getSimpleName()
                    +(status.find()?" "+status.group():"")+" (response and credentials omitted)");
        }
        if(answer==null||answer.isBlank()||answer.length()>2000)throw new AssertionError("Expected a nonempty answer within the production dialogue bound");
        String visible=answer.replaceAll("[\\p{Cntrl}\\s]+"," ").strip();
        if(key!=null&&!key.isBlank())visible=visible.replace(key,"[credential omitted]");
        if(visible.length()>800)visible=visible.substring(0,800)+"…";
        boolean mentionsCows=answer.toLowerCase(Locale.ROOT).contains("коров")||answer.toLowerCase(Locale.ROOT).contains("лугов");
        System.out.println("PASS: one live grounded journey-memory request; bounded reply; alias/species mention="+mentionsCows+". Review wording for ACTIVE 1/2, not completed or rewarded:");
        System.out.println(visible);
    }
    private LiveJourneyMemoryChecks(){}
}
