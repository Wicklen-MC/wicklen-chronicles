package dev.wicklen.chronicles.net;

import com.google.gson.Gson;
import dev.wicklen.chronicles.WicklenChronicles;
import dev.wicklen.chronicles.core.story.StorySnapshot;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.Objects;
import java.util.UUID;

/** One personal pending plan and at most five recorded outcomes. */
public record StoryPagePayload(UUID requestId, StorySnapshot story, String feedback, boolean error)
        implements CustomPacketPayload {
    private static final Gson JSON = new Gson();
    public static final Type<StoryPagePayload> TYPE = new Type<>(
        Identifier.fromNamespaceAndPath(WicklenChronicles.MOD_ID, "story_page"));
    public static final StreamCodec<RegistryFriendlyByteBuf, StoryPagePayload> CODEC = new StreamCodec<>() {
        @Override
        public StoryPagePayload decode(RegistryFriendlyByteBuf buf) {
            if (buf.readVarInt() != JournalRequestPayload.PROTOCOL)
                throw new IllegalArgumentException("Unsupported story protocol");
            UUID requestId = buf.readUUID();
            String json = buf.readUtf(24_000);
            return new StoryPagePayload(requestId, json.isEmpty() ? null : JSON.fromJson(json, StorySnapshot.class),
                buf.readUtf(500), buf.readBoolean());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, StoryPagePayload value) {
            buf.writeVarInt(JournalRequestPayload.PROTOCOL);
            buf.writeUUID(value.requestId());
            buf.writeUtf(value.story() == null ? "" : JSON.toJson(value.story()), 24_000);
            buf.writeUtf(value.feedback(), 500);
            buf.writeBoolean(value.error());
        }
    };

    public StoryPagePayload {
        Objects.requireNonNull(requestId);
        Objects.requireNonNull(feedback);
        if (feedback.length() > 500 || story != null && story.history().size() > 5)
            throw new IllegalArgumentException("Story response bounds");
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
