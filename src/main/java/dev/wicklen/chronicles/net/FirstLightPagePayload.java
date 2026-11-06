package dev.wicklen.chronicles.net;

import com.google.gson.Gson;
import dev.wicklen.chronicles.core.field.FirstLightState;
import dev.wicklen.chronicles.WicklenChronicles;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import java.util.*;

public record FirstLightPagePayload(UUID requestId,Data data,String feedback,boolean error) implements CustomPacketPayload {
    public record Data(UUID ownerId,FirstLightState thread,int evidenceCount,String narrativeTitle,String narrativeMotive) {
        public Data {
            Objects.requireNonNull(ownerId);Objects.requireNonNull(narrativeTitle);Objects.requireNonNull(narrativeMotive);
            if(thread!=null && !thread.ownerId().equals(ownerId) || evidenceCount<0 || evidenceCount>512 || narrativeTitle.length()>160 || narrativeMotive.length()>900)throw new IllegalArgumentException("First-light projection bounds");
        }
    }
    private static final Gson JSON=new Gson();
    public static final Type<FirstLightPagePayload> TYPE=new Type<>(Identifier.fromNamespaceAndPath(WicklenChronicles.MOD_ID,"firstlight_page"));
    public static final StreamCodec<RegistryFriendlyByteBuf,FirstLightPagePayload> CODEC=new StreamCodec<>(){
        public FirstLightPagePayload decode(RegistryFriendlyByteBuf buf){if(buf.readVarInt()!=JournalRequestPayload.PROTOCOL)throw new IllegalArgumentException("First-light protocol");UUID id=buf.readUUID();String text=buf.readUtf(16000);return new FirstLightPagePayload(id,text.isEmpty()?null:JSON.fromJson(text,Data.class),buf.readUtf(160),buf.readBoolean());}
        public void encode(RegistryFriendlyByteBuf buf,FirstLightPagePayload value){buf.writeVarInt(JournalRequestPayload.PROTOCOL);buf.writeUUID(value.requestId());buf.writeUtf(value.data()==null?"":JSON.toJson(value.data()),16000);buf.writeUtf(value.feedback(),160);buf.writeBoolean(value.error());}
    };
    public FirstLightPagePayload{Objects.requireNonNull(requestId);Objects.requireNonNull(feedback);if(feedback.length()>160)throw new IllegalArgumentException("Feedback bounds");}
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
