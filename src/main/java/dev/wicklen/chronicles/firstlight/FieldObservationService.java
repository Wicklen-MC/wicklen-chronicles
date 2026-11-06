package dev.wicklen.chronicles.firstlight;

import com.google.gson.reflect.TypeToken;
import dev.wicklen.chronicles.ai.*;
import dev.wicklen.chronicles.core.field.FieldEvidence;
import dev.wicklen.chronicles.core.discovery.Observation;
import dev.wicklen.chronicles.exploration.ExplorationService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import org.slf4j.LoggerFactory;
import java.util.*;

/** Regional knowledge is separate from first-discovery/research reward entitlements. */
public final class FieldObservationService {
    private static final JsonStore<Map<String,List<FieldEvidence>>> STORE=new JsonStore<>("field-observations.json",new TypeToken<>(){});
    private static boolean registered;
    private static final List<java.util.function.BiConsumer<ServerPlayer,FieldEvidence>> NEW_KNOWLEDGE=new ArrayList<>();
    public static void onNewKnowledge(java.util.function.BiConsumer<ServerPlayer,FieldEvidence> listener){NEW_KNOWLEDGE.add(Objects.requireNonNull(listener));}
    private FieldObservationService() {}
    public static void register() {
        if(registered)return; registered=true;
        ExplorationService.onConfirmed((player,observation,observer)->record(player,observation));
    }
    public static int count(ServerPlayer player) {
        try{return entries(player).size();}
        catch(RuntimeException error){return 0;} // A damaged optional evidence projection must not block another branch's claim.
    }
    public static List<FieldEvidence> evidence(ServerPlayer player){return List.copyOf(entries(player));}
    private static List<FieldEvidence> entries(ServerPlayer player) {
        var value=STORE.get(HashMap::new).get(player.getUUID().toString());
        if(value==null)return List.of();
        if(value.size()>512 || value.stream().map(FieldEvidence::identity).distinct().count()!=value.size())throw new IllegalStateException("Invalid field evidence");
        return value;
    }
    static void record(ServerPlayer player,Observation observation) {
        try {
            var previous=entries(player); var evidence=FieldEvidence.of(observation);
            if(!FieldEvidence.addsKnowledge(previous,evidence))return;
            var next=new ArrayList<>(previous); next.add(evidence);
            while(next.size()>512)next.removeFirst();
            saveEvidence(player,next);
            for(var listener:NEW_KNOWLEDGE)try{listener.accept(player,evidence);}catch(RuntimeException error){LoggerFactory.getLogger("wicklen_chronicles.field").warn("Knowledge listener deferred after persistence: {}",error.toString());}
            if(previous.stream().anyMatch(value->value.key().equals(evidence.key())))
                player.sendOverlayMessage(Component.translatable("journal.wicklen_chronicles.observation.new_region"));
        }catch(Exception error){ LoggerFactory.getLogger("wicklen_chronicles.field").warn("Field evidence deferred; discovery remains saved: {}",error.toString()); }
    }
    /** Checked bounded history replacement, shared by recording and isolated persistence fixtures. */
    static void saveEvidence(ServerPlayer player,List<FieldEvidence> values) throws java.io.IOException {
        var next=List.copyOf(values);
        if(next.size()>512 || next.stream().map(FieldEvidence::identity).distinct().count()!=next.size())throw new IllegalArgumentException("Invalid field history replacement");
        var all=STORE.get(HashMap::new);var old=all.put(player.getUUID().toString(),next);
        try{STORE.saveChecked();}
        catch(java.io.IOException|RuntimeException failure){if(old==null)all.remove(player.getUUID().toString());else all.put(player.getUUID().toString(),old);throw failure;}
    }
}
