package dev.wicklen.chronicles.client;

import dev.wicklen.chronicles.annotation.AnnotationService;
import dev.wicklen.chronicles.core.annotation.DiscoveryAnnotation;
import dev.wicklen.chronicles.core.discovery.DiscoveryKey;
import dev.wicklen.chronicles.core.discovery.DiscoveryKind;
import dev.wicklen.chronicles.net.AnnotationPagePayload;
import dev.wicklen.chronicles.net.AnnotationRequestPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import java.util.*;

/** Personal connection-scoped projection; only matching requests/owner/world update this cache. */
public final class AnnotationClientState {
    private static final Map<DiscoveryKey,DiscoveryAnnotation> VALUES=new LinkedHashMap<>(128,0.75f,true) {
        @Override protected boolean removeEldestEntry(Map.Entry<DiscoveryKey,DiscoveryAnnotation> eldest){return size()>512;}
    };
    private static final JournalRequestGate<AnnotationRequestPayload> REQUESTS=new JournalRequestGate<>();
    private static UUID worldId;
    private static long revision;
    private static long retryAt;
    private static AnnotationService.Status status=AnnotationService.Status.OK;
    private static boolean registered;
    public static void register() {
        if(registered)return;registered=true;
        ClientPlayNetworking.registerGlobalReceiver(AnnotationPagePayload.TYPE,(payload,context)-> {
            var connection=context.client().getConnection();
            context.client().execute(()-> {
                var player=context.client().player;
                if(connection==null||connection!=context.client().getConnection()||player==null||!payload.ownerId().equals(player.getUUID()))return;
                if(worldId!=null&&!worldId.equals(payload.worldId()))return;
                if(!REQUESTS.accept(payload.requestId()))return;
                worldId=payload.worldId();status=payload.status();
                if(status!=AnnotationService.Status.OK)retryAt=System.currentTimeMillis()+2000;
                payload.annotations().forEach(value->VALUES.put(value.key(),value));revision++;
            });
        });
    }
    public static DiscoveryAnnotation lookup(DiscoveryKey key){return VALUES.get(key);}
    public static long revision(){return revision;}
    public static boolean busy(){return REQUESTS.busy();}
    public static AnnotationService.Status status(){return status;}
    public static void request(Collection<DiscoveryKey> keys){read(keys,false);}
    public static void refresh(Collection<DiscoveryKey> keys){read(keys,true);}
    private static void read(Collection<DiscoveryKey> keys,boolean force) {
        if(busy()||System.currentTimeMillis()<retryAt||!ClientPlayNetworking.canSend(AnnotationRequestPayload.TYPE))return;
        var query=keys.stream().filter(k->k.kind()!=DiscoveryKind.BIOME).filter(k->force||!VALUES.containsKey(k)).distinct().limit(64).toList();
        if(query.isEmpty())return;
        var payload=newRequest(AnnotationRequestPayload.Action.READ,query,0,"","");
        REQUESTS.offer(payload.requestId(),payload);
    }
    private static AnnotationRequestPayload newRequest(AnnotationRequestPayload.Action action,List<DiscoveryKey> keys,long expected,String label,String note) {
        return new AnnotationRequestPayload(UUID.randomUUID(),action,worldId,keys,expected,label,note);
    }
    public static boolean save(DiscoveryKey key,long expectedRevision,String label,String note) {
        if(busy()||worldId==null||!ClientPlayNetworking.canSend(AnnotationRequestPayload.TYPE))return false;
        var payload=newRequest(AnnotationRequestPayload.Action.SAVE,List.of(key),expectedRevision,label,note);
        status=AnnotationService.Status.OK;
        boolean accepted=REQUESTS.offer(payload.requestId(),payload);if(accepted)revision++;return accepted;
    }
    public static void tick(){if(REQUESTS.tick(8000)){status=AnnotationService.Status.UNAVAILABLE;revision++;}}
    public static void clear(){VALUES.clear();worldId=null;retryAt=0;REQUESTS.clear();status=AnnotationService.Status.OK;revision++;}
    private AnnotationClientState(){}
}
