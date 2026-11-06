package dev.wicklen.chronicles.net;

import dev.wicklen.chronicles.WicklenChronicles;
import dev.wicklen.chronicles.core.map.MapCell;
import dev.wicklen.chronicles.core.map.MapViewport;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import java.util.Objects;
import java.util.UUID;

/** Exactly 128x128 palette pixels; zero means undiscovered, without hidden terrain behind its fog. */
public record TerrainMapPagePayload(UUID requestId, UUID worldId, UUID ownerId, UUID sessionId, String dimension,
                                   MapViewport viewport, byte[] pixels, String noticeKey, boolean error) implements CustomPacketPayload {
    public static final Type<TerrainMapPagePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(WicklenChronicles.MOD_ID, "terrain_map_page"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TerrainMapPagePayload> CODEC = new StreamCodec<>() {
        public TerrainMapPagePayload decode(RegistryFriendlyByteBuf buf) {
            if (buf.readVarInt() != TerrainMapRequestPayload.PROTOCOL) throw new IllegalArgumentException("Unsupported terrain map protocol");
            UUID id = buf.readUUID(), world = buf.readUUID(), owner = buf.readUUID(), session = buf.readUUID();
            String dimension = buf.readUtf(128); var viewport = new MapViewport(buf.readInt(), buf.readInt(), buf.readVarInt());
            return new TerrainMapPagePayload(id, world, owner, session, dimension, viewport,
                    buf.readByteArray(MapViewport.SIZE * MapViewport.SIZE), buf.readUtf(120), buf.readBoolean());
        }
        public void encode(RegistryFriendlyByteBuf buf, TerrainMapPagePayload value) {
            buf.writeVarInt(TerrainMapRequestPayload.PROTOCOL); buf.writeUUID(value.requestId); buf.writeUUID(value.worldId);
            buf.writeUUID(value.ownerId); buf.writeUUID(value.sessionId); buf.writeUtf(value.dimension, 128);
            buf.writeInt(value.viewport.centerX()); buf.writeInt(value.viewport.centerZ()); buf.writeVarInt(value.viewport.span());
            buf.writeByteArray(value.pixels); buf.writeUtf(value.noticeKey, 120); buf.writeBoolean(value.error);
        }
    };
    public TerrainMapPagePayload {
        Objects.requireNonNull(requestId); Objects.requireNonNull(worldId); Objects.requireNonNull(ownerId); Objects.requireNonNull(sessionId);
        Objects.requireNonNull(viewport); new MapCell(dimension, 0, 0);
        if (pixels == null || pixels.length != MapViewport.SIZE * MapViewport.SIZE || noticeKey == null || noticeKey.length() > 120)
            throw new IllegalArgumentException("Terrain page bounds");
        pixels = pixels.clone();
    }
    @Override public byte[] pixels() { return pixels.clone(); }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
