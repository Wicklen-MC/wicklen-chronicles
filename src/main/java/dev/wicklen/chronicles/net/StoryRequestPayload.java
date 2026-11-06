package dev.wicklen.chronicles.net;

import dev.wicklen.chronicles.WicklenChronicles;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.Objects;
import java.util.UUID;

/** No quest definition or consequence is accepted from the client. */
public record StoryRequestPayload(UUID requestId, Action action, String expectedPlanId, String choiceId)
        implements CustomPacketPayload {
    public enum Action { READ, REQUEST, CHOOSE }

    public static final Type<StoryRequestPayload> TYPE = new Type<>(
        Identifier.fromNamespaceAndPath(WicklenChronicles.MOD_ID, "story_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, StoryRequestPayload> CODEC = new StreamCodec<>() {
        @Override
        public StoryRequestPayload decode(RegistryFriendlyByteBuf buf) {
            if (buf.readVarInt() != JournalRequestPayload.PROTOCOL)
                throw new IllegalArgumentException("Unsupported story protocol");
            return new StoryRequestPayload(buf.readUUID(), buf.readEnum(Action.class), buf.readUtf(36), buf.readUtf(64));
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, StoryRequestPayload value) {
            buf.writeVarInt(JournalRequestPayload.PROTOCOL);
            buf.writeUUID(value.requestId());
            buf.writeEnum(value.action());
            buf.writeUtf(value.expectedPlanId(), 36);
            buf.writeUtf(value.choiceId(), 64);
        }
    };

    public StoryRequestPayload {
        Objects.requireNonNull(requestId);
        Objects.requireNonNull(action);
        Objects.requireNonNull(expectedPlanId);
        Objects.requireNonNull(choiceId);
        if (expectedPlanId.length() > 36 || choiceId.length() > 64)
            throw new IllegalArgumentException("Story request bounds");
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
