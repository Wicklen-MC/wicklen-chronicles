package dev.wicklen.chronicles.net;

import dev.wicklen.chronicles.WicklenChronicles;
import dev.wicklen.chronicles.annotation.AnnotationService;
import dev.wicklen.chronicles.core.annotation.DiscoveryAnnotation;
import dev.wicklen.chronicles.core.discovery.DiscoveryKey;
import dev.wicklen.chronicles.core.discovery.DiscoveryKind;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import java.util.*;

public record AnnotationPagePayload(UUID requestId,UUID worldId,UUID ownerId,List<DiscoveryAnnotation> annotations,AnnotationService.Status status) implements CustomPacketPayload {
    public AnnotationPagePayload {
        Objects.requireNonNull(requestId);Objects.requireNonNull(worldId);Objects.requireNonNull(ownerId);Objects.requireNonNull(status);annotations=List.copyOf(annotations);
        if(annotations.size()>64||annotations.stream().anyMatch(a->!a.worldId().equals(worldId)||!a.ownerId().equals(ownerId))
            ||annotations.stream().map(DiscoveryAnnotation::key).distinct().count()!=annotations.size())throw new IllegalArgumentException("Annotation page identity");
    }
    public static final Type<AnnotationPagePayload> TYPE=new Type<>(Identifier.fromNamespaceAndPath(WicklenChronicles.MOD_ID,"annotation_page"));
    public static final StreamCodec<RegistryFriendlyByteBuf,AnnotationPagePayload> CODEC=new StreamCodec<>() {
        public AnnotationPagePayload decode(RegistryFriendlyByteBuf b) {
            UUID request=b.readUUID(),world=b.readUUID(),owner=b.readUUID();var status=b.readEnum(AnnotationService.Status.class);
            int count=b.readVarInt();if(count<0||count>64)throw new IllegalArgumentException("Annotation page count");
            var values=new ArrayList<DiscoveryAnnotation>();
            for(int i=0;i<count;i++) {
                var key=new DiscoveryKey(b.readEnum(DiscoveryKind.class),b.readUtf(256));
                values.add(new DiscoveryAnnotation(world,owner,key,b.readUtf(48),b.readUtf(280),b.readVarLong()));
            }
            return new AnnotationPagePayload(request,world,owner,values,status);
        }
        public void encode(RegistryFriendlyByteBuf b,AnnotationPagePayload p) {
            b.writeUUID(p.requestId);b.writeUUID(p.worldId);b.writeUUID(p.ownerId);b.writeEnum(p.status);b.writeVarInt(p.annotations.size());
            for(var value:p.annotations) {
                b.writeEnum(value.key().kind());b.writeUtf(value.key().id(),256);b.writeUtf(value.label(),48);b.writeUtf(value.note(),280);b.writeVarLong(value.revision());
            }
        }
    };
    public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
