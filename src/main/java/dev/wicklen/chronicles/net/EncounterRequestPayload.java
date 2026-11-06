package dev.wicklen.chronicles.net;

import dev.wicklen.chronicles.WicklenChronicles;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.Objects;
import java.util.UUID;

public record EncounterRequestPayload(UUID requestId, Action action, String expectedId)
        implements CustomPacketPayload {
    public enum Action { READ, PREPARE_NORMAL, PREPARE_EMPOWERED, START, LEAVE, CLAIM }
    public static final Type<EncounterRequestPayload> TYPE = new Type<>(
        Identifier.fromNamespaceAndPath(WicklenChronicles.MOD_ID, "encounter_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, EncounterRequestPayload> CODEC = new StreamCodec<>() {
        @Override public EncounterRequestPayload decode(RegistryFriendlyByteBuf buf) {
            if (buf.readVarInt() != JournalRequestPayload.PROTOCOL)
                throw new IllegalArgumentException("Unsupported encounter protocol");
            return new EncounterRequestPayload(buf.readUUID(), buf.readEnum(Action.class), buf.readUtf(256));
        }
        @Override public void encode(RegistryFriendlyByteBuf buf, EncounterRequestPayload value) {
            buf.writeVarInt(JournalRequestPayload.PROTOCOL);
            buf.writeUUID(value.requestId());
            buf.writeEnum(value.action());
            buf.writeUtf(value.expectedId(), 256);
        }
    };
    public EncounterRequestPayload {
        Objects.requireNonNull(requestId);
        Objects.requireNonNull(action);
        Objects.requireNonNull(expectedId);
        if (expectedId.length() > 256) throw new IllegalArgumentException("Encounter request bounds");
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
