package dev.wicklen.chronicles.net;

import dev.wicklen.chronicles.WicklenChronicles;
import dev.wicklen.chronicles.core.discovery.DiscoveryKey;
import dev.wicklen.chronicles.core.discovery.DiscoveryKind;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import java.util.*;

public record AnnotationRequestPayload(UUID requestId,Action action,UUID worldId,List<DiscoveryKey> keys,long expectedRevision,String label,String note) implements CustomPacketPayload {
    public enum Action { READ,SAVE }
    public AnnotationRequestPayload {
        Objects.requireNonNull(requestId);Objects.requireNonNull(action);keys=List.copyOf(keys);Objects.requireNonNull(label);Objects.requireNonNull(note);
        if(keys.isEmpty()||keys.size()>64||expectedRevision<0||label.length()>48||note.length()>280
            ||(action==Action.SAVE&&(worldId==null||keys.size()!=1)))throw new IllegalArgumentException("Annotation request bounds");
    }
    public static final Type<AnnotationRequestPayload> TYPE=new Type<>(Identifier.fromNamespaceAndPath(WicklenChronicles.MOD_ID,"annotation_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf,AnnotationRequestPayload> CODEC=new StreamCodec<>() {
        public AnnotationRequestPayload decode(RegistryFriendlyByteBuf b) {
            UUID request=b.readUUID();Action action=b.readEnum(Action.class);UUID world=b.readBoolean()?b.readUUID():null;
            int count=b.readVarInt();if(count<1||count>64)throw new IllegalArgumentException("Annotation key count");
            var keys=new ArrayList<DiscoveryKey>();for(int i=0;i<count;i++)keys.add(new DiscoveryKey(b.readEnum(DiscoveryKind.class),b.readUtf(256)));
            return new AnnotationRequestPayload(request,action,world,keys,b.readVarLong(),b.readUtf(48),b.readUtf(280));
        }
        public void encode(RegistryFriendlyByteBuf b,AnnotationRequestPayload p) {
            b.writeUUID(p.requestId);b.writeEnum(p.action);b.writeBoolean(p.worldId!=null);if(p.worldId!=null)b.writeUUID(p.worldId);
            b.writeVarInt(p.keys.size());for(var key:p.keys){b.writeEnum(key.kind());b.writeUtf(key.id(),256);}
            b.writeVarLong(p.expectedRevision);b.writeUtf(p.label,48);b.writeUtf(p.note,280);
        }
    };
    public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
