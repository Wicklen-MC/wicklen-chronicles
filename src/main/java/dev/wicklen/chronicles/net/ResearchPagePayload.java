package dev.wicklen.chronicles.net;

import com.google.gson.Gson;
import dev.wicklen.chronicles.WicklenChronicles;
import dev.wicklen.chronicles.core.discovery.DiscoveryEntry;
import dev.wicklen.chronicles.core.research.ResearchProgress;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Collections plus a bounded map of places this player already recorded in the current dimension. */
public record ResearchPagePayload(UUID requestId, Data data, String noticeKey, boolean error)
        implements CustomPacketPayload {
    private static final Gson JSON = new Gson();
    public record Data(UUID worldId, UUID ownerId, List<ResearchProgress> series,
                       List<DiscoveryEntry> places, int totalKnownPlaces) {
        public Data {
            Objects.requireNonNull(worldId);
            Objects.requireNonNull(ownerId);
            series = List.copyOf(series);
            places = List.copyOf(places);
            if (series.size() > 8 || places.size() > 32 || totalKnownPlaces < places.size())
                throw new IllegalArgumentException("Research/map read model bounds");
        }
    }
    public static final Type<ResearchPagePayload> TYPE = new Type<>(
        Identifier.fromNamespaceAndPath(WicklenChronicles.MOD_ID, "research_page"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ResearchPagePayload> CODEC = new StreamCodec<>() {
        @Override public ResearchPagePayload decode(RegistryFriendlyByteBuf buf) {
            if (buf.readVarInt() != JournalRequestPayload.PROTOCOL)
                throw new IllegalArgumentException("Unsupported research protocol");
            UUID id = buf.readUUID();
            String json = buf.readUtf(48_000);
            return new ResearchPagePayload(id, json.isEmpty() ? null : JSON.fromJson(json, Data.class),
                buf.readUtf(100), buf.readBoolean());
        }
        @Override public void encode(RegistryFriendlyByteBuf buf, ResearchPagePayload value) {
            buf.writeVarInt(JournalRequestPayload.PROTOCOL);
            buf.writeUUID(value.requestId());
            buf.writeUtf(value.data() == null ? "" : JSON.toJson(value.data()), 48_000);
            buf.writeUtf(value.noticeKey(), 100);
            buf.writeBoolean(value.error());
        }
    };
    public ResearchPagePayload {
        Objects.requireNonNull(requestId);
        Objects.requireNonNull(noticeKey);
        if (noticeKey.length() > 100) throw new IllegalArgumentException("Research notice bounds");
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
