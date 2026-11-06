package dev.wicklen.chronicles.net;

import dev.wicklen.chronicles.firstlight.*;
import dev.wicklen.chronicles.core.field.FirstLightState;
import dev.wicklen.chronicles.exploration.ExplorationService;
import net.fabricmc.fabric.api.networking.v1.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;

public final class FirstLightNetwork {
    private static final Map<UUID,Long> LAST=new HashMap<>();
    private FirstLightNetwork(){}
    public static void register(){
        FirstLightService.register();
        PayloadTypeRegistry.serverboundPlay().register(FirstLightRequestPayload.TYPE,FirstLightRequestPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(FirstLightPagePayload.TYPE,FirstLightPagePayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(FirstLightRequestPayload.TYPE,(request,context)->context.server().execute(()->handle(context.player(),request)));
        ServerPlayConnectionEvents.DISCONNECT.register((handler,server)->LAST.remove(handler.player.getUUID()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server->LAST.clear());
    }
    private static void handle(ServerPlayer player,FirstLightRequestPayload request){
        if(player.level().getServer().getPlayerList().getPlayer(player.getUUID())!=player || !player.connection.isAcceptingMessages() || !ServerPlayNetworking.canSend(player,FirstLightPagePayload.TYPE))return;
        long now=System.nanoTime();Long old=LAST.put(player.getUUID(),now);
        if(old!=null && now-old<150_000_000L){ServerPlayNetworking.send(player,new FirstLightPagePayload(request.requestId(),null,"journal.wicklen_chronicles.wait",true));return;}
        try{
            String feedback="";
            if(request.action()!=FirstLightRequestPayload.Action.READ){
                UUID expected=UUID.fromString(request.expectedThreadId());
                feedback=switch(request.action()){
                    case CHOOSE->FirstLightService.choose(player,expected,FirstLightState.Branch.valueOf(request.branch()));
                    case MARK->FirstLightService.mark(player,expected);
                    case CLAIM->FirstLightService.claim(player,expected);
                    default->"";
                };
            }
            var state=FirstLightService.view(player);String title="",motive="";
            if(state!=null && (request.action()==FirstLightRequestPayload.Action.READ || request.action()==FirstLightRequestPayload.Action.CHOOSE)){
                var source=ExplorationService.snapshot(player).entries().stream().filter(entry->entry.key().equals(state.source())).findFirst().orElse(null);
                if(source!=null){var narrative=FirstLightNarrationService.getOrRequest(player,source);if(narrative!=null){title=narrative.title();motive=narrative.motive();}}
            }
            var data=new FirstLightPagePayload.Data(player.getUUID(),state,FieldObservationService.count(player),title,motive);
            boolean error=Set.of("firstlight.rejected","firstlight.battle_unavailable","firstlight.surface","firstlight.mark_invalid","inventory_full","unavailable").contains(feedback);
            ServerPlayNetworking.send(player,new FirstLightPagePayload(request.requestId(),data,feedback.isEmpty()?"":"journal.wicklen_chronicles."+feedback,error));
        }catch(Exception error){org.slf4j.LoggerFactory.getLogger("wicklen_chronicles.firstlight").warn("First-light action deferred: {}",error.toString());ServerPlayNetworking.send(player,new FirstLightPagePayload(request.requestId(),null,"journal.wicklen_chronicles.unavailable",true));}
    }
}
