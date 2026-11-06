package dev.wicklen.chronicles.net;

import dev.wicklen.chronicles.WicklenChronicles;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import java.util.*;

public record GuidanceC2SPayload(UUID requestId, UUID expectedWorldId, Action action, String placeId) implements CustomPacketPayload {
    public enum Action { READ, PIN_PLACE, FOLLOW_EXPEDITION, CLEAR }
    public static final int PROTOCOL = 1;
    public static final Type<GuidanceC2SPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(WicklenChronicles.MOD_ID, "guidance_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, GuidanceC2SPayload> CODEC = new StreamCodec<>() {
        public GuidanceC2SPayload decode(RegistryFriendlyByteBuf buf) {
            if (buf.readVarInt() != PROTOCOL) throw new IllegalArgumentException("Guidance protocol");
            var id = buf.readUUID(); var world = buf.readBoolean() ? buf.readUUID() : null;
            int action = buf.readVarInt(); if (action < 0 || action >= Action.values().length) throw new IllegalArgumentException("Guidance action bounds");
            return new GuidanceC2SPayload(id, world, Action.values()[action], buf.readUtf(256));
        }
        public void encode(RegistryFriendlyByteBuf buf, GuidanceC2SPayload value) {
            buf.writeVarInt(PROTOCOL); buf.writeUUID(value.requestId); buf.writeBoolean(value.expectedWorldId != null);
            if (value.expectedWorldId != null) buf.writeUUID(value.expectedWorldId);
            buf.writeVarInt(value.action.ordinal()); buf.writeUtf(value.placeId, 256);
        }
    };
    public GuidanceC2SPayload {
        Objects.requireNonNull(requestId); Objects.requireNonNull(action); Objects.requireNonNull(placeId);
        if (requestId.equals(GuidanceS2CPayload.PUSH_ID) || placeId.length() > 256 || action != Action.PIN_PLACE && !placeId.isEmpty())
            throw new IllegalArgumentException("Guidance request bounds");
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
