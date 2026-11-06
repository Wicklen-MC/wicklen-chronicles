package dev.wicklen.chronicles.firstlight;

import com.google.gson.reflect.TypeToken;
import dev.wicklen.chronicles.ai.*;
import dev.wicklen.chronicles.core.discovery.*;
import dev.wicklen.chronicles.core.field.*;
import dev.wicklen.chronicles.encounter.EncounterService;
import dev.wicklen.chronicles.exploration.ExplorationService;
import dev.wicklen.chronicles.inventory.InventoryDelivery;
import dev.wicklen.chronicles.station.StationService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.network.chat.Component;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.util.*;
import static dev.wicklen.chronicles.core.field.FirstLightState.*;

public final class FirstLightService {
    private static final JsonStore<Map<String,FirstLightState>> STORE=new JsonStore<>("first-light.json",new TypeToken<>(){});
    private static boolean registered;
    private FirstLightService() {}
    public static void register() {
        if(registered)return; registered=true;
        FieldObservationService.register();
        FieldObservationService.onNewKnowledge((player,fact)->{
            try{var old=current(player);var next=view(player);if(old!=null && next!=old)notice(player,next);}catch(Exception error){deferred(error);}
        });
        ExplorationService.onConfirmed((player,observation,observer)->{
            try {
                var state=view(player); if(state==null)return;
                var next=surface(player)?FirstLightTransitions.mark(state,observer,System.currentTimeMillis()):state;
                if(next!=state){save(player,state,next);notice(player,next);}
            }catch(Exception error){deferred(error);}
        });
        EncounterService.onCompleted((player,completed)->{
            try {
                var state=current(player);if(state==null)return;
                var next=FirstLightTransitions.encounter(state,completed.place(),completed.id(),completed.finishedAt());
                if(next!=state){save(player,state,next);notice(player,next);}
            }catch(Exception error){deferred(error);}
        });
    }
    public static FirstLightState current(ServerPlayer player) {
        var all=STORE.get(HashMap::new);var value=all.get(player.getUUID().toString());
        if(value==null){if(all.containsKey(player.getUUID().toString()))throw new IllegalStateException("Null first-light state");return null;}
        if(!value.ownerId().equals(player.getUUID()) || !value.worldId().equals(WorldStorageContext.worldId()))throw new IllegalStateException("First light identity mismatch");
        return value;
    }
    public static FirstLightState view(ServerPlayer player) {
        var value=current(player);
        if(value!=null){
            if(value.status()==Status.ACTIVE && value.branch()==Branch.OBSERVE){
                var reconciled=value;
                for(var fact:FieldObservationService.evidence(player))reconciled=FirstLightTransitions.observe(reconciled,fact,System.currentTimeMillis());
                if(reconciled!=value)try{save(player,value,reconciled);}catch(IOException error){throw new IllegalStateException("First-light observation recovery deferred",error);}
                return reconciled;
            }
            if(value.status()==Status.ACTIVE && value.branch()==Branch.ENCOUNTER){
                var completed=EncounterService.completionSince(player,value.source(),value.acceptedAt()).orElse(null);
                if(completed!=null){
                    var reconciled=FirstLightTransitions.encounter(value,completed.place(),completed.id(),completed.finishedAt());
                    try{save(player,value,reconciled);}catch(IOException error){throw new IllegalStateException("First-light encounter recovery deferred",error);}
                    return reconciled;
                }
            }
            return value;
        }
        for(var entry:ExplorationService.snapshot(player).entries()) {
            var station=StationService.knownStation(player,entry.key()).orElse(null);if(station==null)continue;
            var anchor=station.anchor();
            value=FirstLightTransitions.offer(WorldStorageContext.worldId(),player.getUUID(),entry.key(),entry.title(),
                    new DiscoveryPosition(station.dimension(),anchor.x(),anchor.y(),anchor.z()),station.arena()!=null && EncounterService.gatewayAvailable(player.level().getServer()));
            try{save(player,null,value);}catch(IOException error){throw new IllegalStateException("First light save unavailable",error);}
            boolean ru=player.clientInformation().language().startsWith("ru");
            boolean battle=value.alternative()==Branch.ENCOUNTER;
            dev.wicklen.chronicles.SpiritBubbles.speak(player,ru
                    ?"Это станция Путевых огней. Можно разведать окрестности или "+(battle?"проверить двор":"собрать наблюдения о местных обитателях")+". Продолжение — в дневнике."
                    :"This is a Waylight station. We can survey the surroundings or "+(battle?"test this courtyard":"record the local wildlife")+". The next step is in your journal.");
            return value;
        }
        return null;
    }
    public static String choose(ServerPlayer player,UUID expected,Branch branch) throws IOException {
        var state=view(player); if(state==null)return "firstlight.need_station";
        if(branch==Branch.ENCOUNTER && (!EncounterService.gatewayAvailable(player.level().getServer())
                || StationService.knownStation(player,state.source()).isEmpty()))return "firstlight.battle_unavailable";
        var next=FirstLightTransitions.choose(state,expected,branch,System.currentTimeMillis());
        if(next==state)return "firstlight.rejected";save(player,state,next);return "firstlight.accepted";
    }
    public static String mark(ServerPlayer player,UUID expected) throws IOException {
        var state=current(player);if(state==null || !state.id().equals(expected))return "firstlight.rejected";
        if(!surface(player))return "firstlight.surface";
        var p=player.blockPosition();var next=FirstLightTransitions.mark(state,new DiscoveryPosition(player.level().dimension().identifier().toString(),p.getX(),p.getY(),p.getZ()),System.currentTimeMillis());
        if(next==state)return "firstlight.mark_invalid";save(player,state,next);return next.status()==Status.READY?"firstlight.ready":"firstlight.marked";
    }
    public static String claim(ServerPlayer player,UUID expected) {
        var state=current(player);if(state==null || !player.isAlive() || player.isSpectator())return "firstlight.rejected";
        var next=FirstLightTransitions.claim(state,expected);if(next==state)return "firstlight.rejected";
        var result=InventoryDelivery.deliver(player,List.of(new ItemStack(Items.TORCH,8),new ItemStack(Items.BREAD,4),new ItemStack(Items.PAPER,4)),()->{
            try{save(player,state,next);return true;}catch(IOException error){deferred(error);return false;}
        });
        return switch(result){case DELIVERED->"firstlight.claimed";case FULL->"inventory_full";case SAVE_FAILED->"unavailable";};
    }
    private static boolean surface(ServerPlayer player) {
        if(!player.isAlive() || player.isSpectator())return false;
        return dev.wicklen.chronicles.terrain.TerrainMapService.isSurface(player) && (player.onGround() || player.isCreative());
    }
    static void save(ServerPlayer player,FirstLightState previous,FirstLightState next) throws IOException {
        var all=STORE.get(HashMap::new);all.put(player.getUUID().toString(),next);
        try{STORE.saveChecked();}catch(IOException|RuntimeException error){if(previous==null)all.remove(player.getUUID().toString());else all.put(player.getUUID().toString(),previous);if(error instanceof IOException io)throw io;throw new IOException(error);}
    }
    private static void notice(ServerPlayer player,FirstLightState state){player.sendOverlayMessage(Component.translatable("journal.wicklen_chronicles."+(state.status()==Status.READY?"firstlight.ready":"firstlight.progress"),state.progress(),state.required()));}
    private static void deferred(Exception error){LoggerFactory.getLogger("wicklen_chronicles.firstlight").warn("First light deferred; ordinary exploration and quests remain saved: {}",error.toString());}
}
