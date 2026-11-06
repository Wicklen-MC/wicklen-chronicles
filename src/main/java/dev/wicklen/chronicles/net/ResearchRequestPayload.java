package dev.wicklen.chronicles.net;

import dev.wicklen.chronicles.WicklenChronicles;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.Objects;
import java.util.UUID;

public record ResearchRequestPayload(UUID requestId, Action action, String expectedRewardId)
        implements CustomPacketPayload {
    public enum Action { READ, CLAIM }
    public static final Type<ResearchRequestPayload> TYPE = new Type<>(
        Identifier.fromNamespaceAndPath(WicklenChronicles.MOD_ID, "research_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ResearchRequestPayload> CODEC = new StreamCodec<>() {
        @Override public ResearchRequestPayload decode(RegistryFriendlyByteBuf buf) {
            if (buf.readVarInt() != JournalRequestPayload.PROTOCOL)
                throw new IllegalArgumentException("Unsupported research protocol");
            return new ResearchRequestPayload(buf.readUUID(), buf.readEnum(Action.class), buf.readUtf(36));
        }
        @Override public void encode(RegistryFriendlyByteBuf buf, ResearchRequestPayload value) {
            buf.writeVarInt(JournalRequestPayload.PROTOCOL);
            buf.writeUUID(value.requestId());
            buf.writeEnum(value.action());
            buf.writeUtf(value.expectedRewardId(), 36);
        }
    };
    public ResearchRequestPayload {
        Objects.requireNonNull(requestId);
        Objects.requireNonNull(action);
        Objects.requireNonNull(expectedRewardId);
        if (expectedRewardId.length() > 36) throw new IllegalArgumentException("Research request bounds");
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
