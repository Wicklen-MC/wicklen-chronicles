package dev.wicklen.chronicles.net;

import dev.wicklen.chronicles.WicklenChronicles;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.Objects;
import java.util.UUID;

/** Intent only. The connection supplies the actor; no client progress/position is accepted. */
public record JournalRequestPayload(UUID requestId, Action action, Section section, int page, String expectedId)
        implements CustomPacketPayload {
    public static final int PROTOCOL = 1;
    public enum Action { REFRESH, TAKE_QUEST, TURN_IN, CLAIM_REWARD }
    public enum Section { ATLAS, TASKS, HISTORY }
    public static final Type<JournalRequestPayload> TYPE = new Type<>(
        Identifier.fromNamespaceAndPath(WicklenChronicles.MOD_ID, "journal_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, JournalRequestPayload> CODEC = new StreamCodec<>() {
        @Override public JournalRequestPayload decode(RegistryFriendlyByteBuf buf) {
            if (buf.readVarInt() != PROTOCOL) throw new IllegalArgumentException("Unsupported journal protocol");
            return new JournalRequestPayload(buf.readUUID(), buf.readEnum(Action.class),
                buf.readEnum(Section.class), buf.readVarInt(), buf.readUtf(36));
        }
        @Override public void encode(RegistryFriendlyByteBuf buf, JournalRequestPayload value) {
            buf.writeVarInt(PROTOCOL);
            buf.writeUUID(value.requestId());
            buf.writeEnum(value.action());
            buf.writeEnum(value.section());
            buf.writeVarInt(value.page());
            buf.writeUtf(value.expectedId(), 36);
        }
    };
    public JournalRequestPayload {
        Objects.requireNonNull(requestId);
        Objects.requireNonNull(action);
        Objects.requireNonNull(section);
        Objects.requireNonNull(expectedId);
        if (page < 0 || page > 100_000 || expectedId.length() > 36)
            throw new IllegalArgumentException("Journal request bounds");
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
