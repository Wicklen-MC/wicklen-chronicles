package dev.wicklen.chronicles.net;

import dev.wicklen.chronicles.WicklenChronicles;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.Objects;
import java.util.UUID;

/** No quest definition or consequence is accepted from the client. */
public record FirstLightRequestPayload(UUID requestId, Action action, String expectedThreadId, String branch)
        implements CustomPacketPayload {
    public enum Action { READ, CHOOSE, MARK, CLAIM }

    public static final Type<FirstLightRequestPayload> TYPE = new Type<>(
        Identifier.fromNamespaceAndPath(WicklenChronicles.MOD_ID, "firstlight_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, FirstLightRequestPayload> CODEC = new StreamCodec<>() {
        @Override
        public FirstLightRequestPayload decode(RegistryFriendlyByteBuf buf) {
            if (buf.readVarInt() != JournalRequestPayload.PROTOCOL)
                throw new IllegalArgumentException("Unsupported first-light protocol");
            return new FirstLightRequestPayload(buf.readUUID(), buf.readEnum(Action.class), buf.readUtf(36), buf.readUtf(64));
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, FirstLightRequestPayload value) {
            buf.writeVarInt(JournalRequestPayload.PROTOCOL);
            buf.writeUUID(value.requestId());
            buf.writeEnum(value.action());
            buf.writeUtf(value.expectedThreadId(), 36);
            buf.writeUtf(value.branch(), 64);
        }
    };

    public FirstLightRequestPayload {
        Objects.requireNonNull(requestId);
        Objects.requireNonNull(action);
        Objects.requireNonNull(expectedThreadId);
        Objects.requireNonNull(branch);
        if (expectedThreadId.length() > 36 || branch.length() > 64)
            throw new IllegalArgumentException("Story request bounds");
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
