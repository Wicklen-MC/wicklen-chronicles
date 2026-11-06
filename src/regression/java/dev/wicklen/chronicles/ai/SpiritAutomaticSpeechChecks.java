package dev.wicklen.chronicles.ai;

import dev.wicklen.chronicles.SpiritProactive;
import dev.wicklen.chronicles.net.SpiritSpeechPreferencesC2SPayload;
import java.nio.file.*;
import java.util.*;
import static dev.wicklen.chronicles.SpiritProactive.Cause;

/** Offline, deterministic clocks: no provider, client or game world needed. */
public final class SpiritAutomaticSpeechChecks {
    public static void main(String[] args) throws Exception {
        preferences();
        sustainedEvents();
        require(SpiritAi.groundingRules().contains("не поздравляй с ачивками"), "unsolicited achievement praise is explicitly excluded from generated speech");
        System.out.println("PASS: automatic speech defaults, persistent interval/opt-out, owner/world isolation, fresh sustained causes and no idle/stale replay");
    }

    private static void preferences() throws Exception {
        Path root=Files.createTempDirectory("wicklen-auto-speech-");
        Path a=root.resolve("world-a"), b=root.resolve("world-b");
        UUID one=UUID.randomUUID(), two=UUID.randomUUID();
        try {
            WorldStorageContext.open(a);
            Path file=WorldStorageContext.directory().resolve("speech-preferences.json");
            Path oldPrefs=WorldStorageContext.directory().resolve("prefs.json");
            Files.writeString(oldPrefs,"[\""+one+"\"]");
            String legacy=Files.readString(oldPrefs);
            require(SpiritSpeechPreferences.current(one).equals(new SpiritSpeechPreferences.Preferences(true,10)), "older clients receive a ten-minute default");
            require(!Files.exists(file), "reading the default does not write a preference record");
            require(SpiritSpeechPreferences.reserveAutomatic(one,0), "first automatic event reserves its own clock");
            require(!SpiritSpeechPreferences.automaticAllowed(one,599_999), "a second automatic source cannot bypass the default interval");
            require(SpiritSpeechPreferences.automaticAllowed(one,600_000), "the exact ten-minute boundary is eligible");
            long revision=SpiritSpeechPreferences.revision(one);
            require(SpiritSpeechPreferences.updateActor(one,new SpiritSpeechPreferences.Preferences(false,2)), "actor can disable automatic speech");
            require(!SpiritSpeechPreferences.automaticAllowed(one,3_600_000), "disabled automatic speech stays disabled even after its interval");
            require(!SpiritSpeechPreferences.deliveryAllowed(one,revision), "a response already in flight is invalidated by opt-out");
            require(SpiritSpeechPreferences.updateActor(one,new SpiritSpeechPreferences.Preferences(true,2)), "actor can re-enable automatic speech");
            require(!SpiritSpeechPreferences.deliveryAllowed(one,revision), "toggling back on cannot resurrect an old response");
            require(!SpiritSpeechPreferences.automaticAllowed(one,119_999) && SpiritSpeechPreferences.automaticAllowed(one,120_000), "changing the interval preserves the previous attempt time");
            require(SpiritSpeechPreferences.reserveAutomatic(one,120_000), "new eligible automatic attempt is persisted");
            require(SpiritSpeechPreferences.automaticAllowed(two,120_001), "another player has an independent interval");
            require(Files.readString(oldPrefs).equals(legacy), "speech settings never replace the old hidden-spirit preference set");
            WorldStorageContext.flush(); WorldStorageContext.close(); WorldStorageContext.open(a);
            require(SpiritSpeechPreferences.current(one).minIntervalMinutes()==2 && !SpiritSpeechPreferences.automaticAllowed(one,120_001), "reconnect/restart cannot reset the last automatic attempt");
            require(SpiritSpeechPreferences.updateActor(one,new SpiritSpeechPreferences.Preferences(true,30)), "maximum user interval is accepted");
            require(!SpiritSpeechPreferences.automaticAllowed(one,1_919_999) && SpiritSpeechPreferences.automaticAllowed(one,1_920_000), "the thirty-minute interval shares the same saved clock");
            Path backup=file.resolveSibling("speech.fixture-backup"); Files.move(file,backup); Files.createDirectory(file);
            try {
                require(!SpiritSpeechPreferences.reserveAutomatic(one,1_920_000), "an unsaved automatic reservation never sends a line");
                require(SpiritSpeechPreferences.automaticAllowed(one,1_920_000), "failed reservation restores the previous clock");
            } finally { Files.delete(file); Files.move(backup,file); Files.deleteIfExists(file.resolveSibling("speech-preferences.json.tmp")); }
            WorldStorageContext.flush(); WorldStorageContext.close(); WorldStorageContext.open(b);
            require(SpiritSpeechPreferences.current(one).minIntervalMinutes()==10 && SpiritSpeechPreferences.automaticAllowed(one,1), "the same player in a different world does not inherit another world's timing");
            WorldStorageContext.close();
            Path broken=b.resolve("wicklen-chronicles/speech-preferences.json");Files.writeString(broken,"{broken settings");
            WorldStorageContext.open(b);
            require(!SpiritSpeechPreferences.current(one).automaticEnabled() && !SpiritSpeechPreferences.reserveAutomatic(one,1000), "a corrupt optional speech store fails quiet");
            require(!SpiritSpeechPreferences.updateActor(one,new SpiritSpeechPreferences.Preferences(true,10)) && Files.readString(broken).equals("{broken settings"), "corrupt preferences are preserved instead of being overwritten by a client update");
        } finally {
            WorldStorageContext.close();
            try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
        }
        for(int invalid:new int[]{-1,0,1,31,Integer.MAX_VALUE}) {
            try { new SpiritSpeechPreferencesC2SPayload(true,invalid); }
            catch(IllegalArgumentException expected){continue;}
            throw new AssertionError("Out-of-range preference packet was accepted");
        }
    }

    private static void sustainedEvents() {
        var idle=new SpiritProactive.Tracker();
        var unchanged=signals("plains",false,false,"known");
        for(long now=0;now<=1_200_000;now+=5000)require(idle.sample(unchanged,now).isEmpty(), "idle time and unrelated statistics never generate a remark");
        var biome=new SpiritProactive.Tracker();biome.sample(unchanged,0);
        biome.sample(signals("forest",false,false,"known"),1000);
        require(biome.sample(signals("forest",false,false,"known"),30_999).isEmpty(), "a new biome needs thirty seconds of residence");
        require(biome.sample(signals("forest",false,false,"known"),31_000).orElseThrow().cause()==Cause.BIOME, "sustained biome residence has one fresh cause");
        require(biome.sample(signals("forest",false,false,"known"),631_000).isEmpty(), "discarding a cause during cooldown cannot replay it ten minutes later");
        var shortVisit=new SpiritProactive.Tracker();shortVisit.sample(unchanged,0);
        shortVisit.sample(signals("forest",false,false,"known"),1000);
        shortVisit.sample(unchanged,10_000);
        require(shortVisit.sample(unchanged,35_000).isEmpty(), "a short visit does not leave a stale entered-forest event");
        var progress=new SpiritProactive.Tracker();progress.sample(unchanged,0);
        progress.sample(signals("plains",false,false,"first"),1000);
        progress.sample(signals("plains",false,false,"latest"),5000);
        require(progress.sample(signals("plains",false,false,"latest"),14_999).isEmpty(), "personal progress waits for a short stable snapshot");
        var latest=progress.sample(signals("plains",false,false,"latest"),15_000).orElseThrow();
        require(latest.cause()==Cause.PERSONAL_PROGRESS && latest.subject().equals("latest"), "only the latest personal change becomes a cause");
        require(progress.sample(signals("plains",false,false,"latest"),615_000).isEmpty(), "blocked personal remarks are not queued for unlock");
        var simultaneous=new SpiritProactive.Tracker();simultaneous.sample(unchanged,0);
        simultaneous.sample(signals("forest",true,true,"new"),1000);
        require(simultaneous.sample(signals("forest",true,true,"new"),31_000).orElseThrow().cause()==Cause.DANGER, "ongoing danger wins over concurrent optional observations");
        require(simultaneous.sample(signals("forest",true,true,"new"),40_000).isEmpty(), "matured secondary causes do not become a delayed chatter queue");
    }
    private static SpiritProactive.Signals signals(String biome,boolean danger,boolean storm,String token){return new SpiritProactive.Signals(biome,danger,storm,token);}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private SpiritAutomaticSpeechChecks(){}
}
