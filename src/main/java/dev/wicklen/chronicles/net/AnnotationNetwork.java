package dev.wicklen.chronicles.net;

import dev.wicklen.chronicles.ai.WorldStorageContext;
import dev.wicklen.chronicles.annotation.AnnotationService;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.*;
import java.util.*;

public final class AnnotationNetwork {
    private static final Map<UUID,Long> LAST=new HashMap<>();
    public static void register() {
        PayloadTypeRegistry.serverboundPlay().register(AnnotationRequestPayload.TYPE,AnnotationRequestPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(AnnotationPagePayload.TYPE,AnnotationPagePayload.CODEC);
        ServerPlayConnectionEvents.DISCONNECT.register((handler,server)->LAST.remove(handler.player.getUUID()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server->LAST.clear());
        ServerPlayNetworking.registerGlobalReceiver(AnnotationRequestPayload.TYPE,(request,context)->context.server().execute(()-> {
            var player=context.player();
            if(context.server().getPlayerList().getPlayer(player.getUUID())!=player || !player.connection.isAcceptingMessages()
                || !ServerPlayNetworking.canSend(player,AnnotationPagePayload.TYPE))return;
            long now=System.nanoTime();Long previous=LAST.put(player.getUUID(),now);
            var status=AnnotationService.Status.OK;java.util.List<dev.wicklen.chronicles.core.annotation.DiscoveryAnnotation> values=List.of();
            try {
                if(previous!=null&&now-previous<150_000_000L)status=AnnotationService.Status.REJECTED;
                else if(request.worldId()!=null&&!request.worldId().equals(WorldStorageContext.worldId()))status=AnnotationService.Status.REJECTED;
                else if(request.action()==AnnotationRequestPayload.Action.READ)values=AnnotationService.page(player,request.keys());
                else {var result=AnnotationService.save(player,request.worldId(),request.keys().getFirst(),request.expectedRevision(),request.label(),request.note());
                    status=result.status();if(result.annotation()!=null)values=List.of(result.annotation());}
            } catch(RuntimeException failure){status=AnnotationService.Status.UNAVAILABLE;}
            ServerPlayNetworking.send(player,new AnnotationPagePayload(request.requestId(),WorldStorageContext.worldId(),player.getUUID(),values,status));
        }));
    }
    private AnnotationNetwork(){}
}
