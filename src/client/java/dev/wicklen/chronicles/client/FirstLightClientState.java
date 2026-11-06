package dev.wicklen.chronicles.client;

import dev.wicklen.chronicles.net.*;
import net.fabricmc.fabric.api.client.networking.v1.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import java.util.UUID;

public final class FirstLightClientState {
    private static FirstLightPagePayload.Data data;
    private static final JournalRequestGate<FirstLightRequestPayload> REQUESTS=new JournalRequestGate<>();
    private static String feedback="";private static long revision,feedbackUntil;private static boolean registered,error;
    private static FirstLightRequestPayload.Action pendingAction=FirstLightRequestPayload.Action.READ;
    private FirstLightClientState(){}
    public static void register(){
        if(registered)return;registered=true;
        ClientPlayNetworking.registerGlobalReceiver(FirstLightPagePayload.TYPE,(page,context)->{
          var connection=context.client().getConnection();
          context.client().execute(()->{
            if(connection==null || connection!=context.client().getConnection() || context.client().player==null)return;
            if(page.data()!=null && !page.data().ownerId().equals(context.client().player.getUUID()))return;
            UUID expectedWorld=data!=null && data.thread()!=null?data.thread().worldId():JournalClientState.view()!=null?JournalClientState.view().worldId():null;
            if(page.data()!=null && page.data().thread()!=null && expectedWorld!=null && !expectedWorld.equals(page.data().thread().worldId()))return;
            if(!REQUESTS.accept(page.requestId()))return;
            if(page.data()!=null){
                var next=page.data();
                if(next.narrativeTitle().isBlank() && data!=null && data.thread()!=null && next.thread()!=null && next.thread().id().equals(data.thread().id()))
                    next=new FirstLightPagePayload.Data(next.ownerId(),next.thread(),next.evidenceCount(),data.narrativeTitle(),data.narrativeMotive());
                data=next;
            }
            if(page.error()){feedback=page.feedback();error=true;feedbackUntil=System.currentTimeMillis()+6000;}
            else if(pendingAction!=FirstLightRequestPayload.Action.READ || System.currentTimeMillis()>=feedbackUntil){feedback=page.feedback();error=false;feedbackUntil=0;}
            revision++;
            if(context.client().gui.screen() instanceof SpiritDialogScreen screen)screen.changed();
          });
        });
        ClientTickEvents.END_CLIENT_TICK.register(client->{if(REQUESTS.tick(10000)){feedback="journal.wicklen_chronicles.timeout";error=true;feedbackUntil=System.currentTimeMillis()+6000;revision++;}});
        ClientPlayConnectionEvents.DISCONNECT.register((handler,client)->{data=null;REQUESTS.clear();feedback="";error=false;feedbackUntil=0;revision++;});
    }
    public static FirstLightPagePayload.Data data(){return data;}
    public static boolean hasThread(){return data!=null && data.thread()!=null;}
    public static boolean busy(){return REQUESTS.busy();}
    public static long revision(){return revision;}
    public static String feedback(){return feedback;}
    public static boolean error(){return error;}
    public static void read(){request(FirstLightRequestPayload.Action.READ,"");}
    public static void request(FirstLightRequestPayload.Action action,String branch){
        if(REQUESTS.busy())return;
        if(!ClientPlayNetworking.canSend(FirstLightRequestPayload.TYPE)){feedback="journal.wicklen_chronicles.unsupported";error=true;revision++;return;}
        if(action!=FirstLightRequestPayload.Action.READ){feedback="";error=false;feedbackUntil=0;}
        pendingAction=action;
        UUID request=UUID.randomUUID();
        REQUESTS.offer(request,new FirstLightRequestPayload(request,action,hasThread()?data.thread().id().toString():"",branch));revision++;
    }
}
