package dev.wicklen.chronicles.net;

import com.google.gson.Gson;
import dev.wicklen.chronicles.WicklenChronicles;
import dev.wicklen.chronicles.core.journal.JournalView;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.Objects;
import java.util.UUID;

/** A bounded, recipient-filtered read model; Gson stays entirely in the platform adapter. */
public record JournalPagePayload(UUID requestId, JournalRequestPayload.Section section, int page,
                                 JournalView view, String noticeKey, String detail, boolean error)
        implements CustomPacketPayload {
    private static final Gson JSON = new Gson();
    private static final int MAX_JSON = 48_000;
    public static final Type<JournalPagePayload> TYPE = new Type<>(
        Identifier.fromNamespaceAndPath(WicklenChronicles.MOD_ID, "journal_page"));
    public static final StreamCodec<RegistryFriendlyByteBuf, JournalPagePayload> CODEC = new StreamCodec<>() {
        @Override public JournalPagePayload decode(RegistryFriendlyByteBuf buf) {
            if (buf.readVarInt() != JournalRequestPayload.PROTOCOL)
                throw new IllegalArgumentException("Unsupported journal protocol");
            UUID requestId = buf.readUUID();
            var section = buf.readEnum(JournalRequestPayload.Section.class);
            int page = buf.readVarInt();
            String data = buf.readUtf(MAX_JSON);
            return new JournalPagePayload(requestId, section, page,
                data.isEmpty() ? null : JSON.fromJson(data, JournalView.class),
                buf.readUtf(100), buf.readUtf(500), buf.readBoolean());
        }
        @Override public void encode(RegistryFriendlyByteBuf buf, JournalPagePayload value) {
            buf.writeVarInt(JournalRequestPayload.PROTOCOL);
            buf.writeUUID(value.requestId());
            buf.writeEnum(value.section());
            buf.writeVarInt(value.page());
            buf.writeUtf(value.view() == null ? "" : JSON.toJson(value.view()), MAX_JSON);
            buf.writeUtf(value.noticeKey(), 100);
            buf.writeUtf(value.detail(), 500);
            buf.writeBoolean(value.error());
        }
    };
    public JournalPagePayload {
        Objects.requireNonNull(requestId);
        Objects.requireNonNull(section);
        Objects.requireNonNull(noticeKey);
        Objects.requireNonNull(detail);
        if (page < 0 || page > 100_000 || noticeKey.length() > 100 || detail.length() > 500)
            throw new IllegalArgumentException("Journal response bounds");
        if (view != null && (view.discoveries().size() > JournalNetwork.PAGE_SIZE
                || view.history().size() > JournalNetwork.PAGE_SIZE))
            throw new IllegalArgumentException("Journal response page exceeds limit");
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
