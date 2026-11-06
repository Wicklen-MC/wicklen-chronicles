package dev.wicklen.chronicles.net;

import dev.wicklen.chronicles.WicklenChronicles;
import dev.wicklen.chronicles.core.map.MapViewport;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import java.util.Objects;
import java.util.UUID;

/** A bounded viewport intention; the client never supplies owner, dimension or visited cells. */
public record TerrainMapRequestPayload(UUID requestId, UUID expectedWorldId, MapViewport viewport) implements CustomPacketPayload {
    public static final int PROTOCOL = 1;
    public static final Type<TerrainMapRequestPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(WicklenChronicles.MOD_ID, "terrain_map_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TerrainMapRequestPayload> CODEC = new StreamCodec<>() {
        public TerrainMapRequestPayload decode(RegistryFriendlyByteBuf buf) {
            if (buf.readVarInt() != PROTOCOL) throw new IllegalArgumentException("Unsupported terrain map protocol");
            UUID id = buf.readUUID(), world = buf.readBoolean() ? buf.readUUID() : null;
            return new TerrainMapRequestPayload(id, world, new MapViewport(buf.readInt(), buf.readInt(), buf.readVarInt()));
        }
        public void encode(RegistryFriendlyByteBuf buf, TerrainMapRequestPayload value) {
            buf.writeVarInt(PROTOCOL); buf.writeUUID(value.requestId); buf.writeBoolean(value.expectedWorldId != null);
            if (value.expectedWorldId != null) buf.writeUUID(value.expectedWorldId);
            buf.writeInt(value.viewport.centerX()); buf.writeInt(value.viewport.centerZ()); buf.writeVarInt(value.viewport.span());
        }
    };
    public TerrainMapRequestPayload { Objects.requireNonNull(requestId); Objects.requireNonNull(viewport); }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
