package dev.wicklen.chronicles.net;

import dev.wicklen.chronicles.WicklenChronicles;
import dev.wicklen.chronicles.core.guidance.GuidanceTarget;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import java.util.*;

public record GuidanceS2CPayload(UUID requestId, UUID worldId, UUID ownerId, UUID sessionId, long revision,
                                 boolean enabled, boolean paused, GuidanceTarget target, String noticeKey, boolean error) implements CustomPacketPayload {
    public static final UUID PUSH_ID = new UUID(0, 0);
    public static final Type<GuidanceS2CPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(WicklenChronicles.MOD_ID, "guidance_page"));
    public static final StreamCodec<RegistryFriendlyByteBuf, GuidanceS2CPayload> CODEC = new StreamCodec<>() {
        public GuidanceS2CPayload decode(RegistryFriendlyByteBuf buf) {
            if (buf.readVarInt() != GuidanceC2SPayload.PROTOCOL) throw new IllegalArgumentException("Guidance protocol");
            UUID id = buf.readUUID(), world = buf.readUUID(), owner = buf.readUUID(), session = buf.readUUID();
            long revision = buf.readVarLong(); boolean enabled = buf.readBoolean(), paused = buf.readBoolean();
            GuidanceTarget target = null;
            if (buf.readBoolean()) {
                String key = buf.readUtf(256), dimension = buf.readUtf(128);
                double x = buf.readDouble(), y = buf.readDouble(), z = buf.readDouble();
                String label = buf.readUtf(160), canonical = buf.readUtf(160);
                int kind = buf.readVarInt(); if (kind < 0 || kind >= GuidanceTarget.Kind.values().length) throw new IllegalArgumentException("Guidance kind bounds");
                target = new GuidanceTarget(key, dimension, x, y, z, label, canonical, GuidanceTarget.Kind.values()[kind], buf.readBoolean(), buf.readBoolean());
            }
            return new GuidanceS2CPayload(id, world, owner, session, revision, enabled, paused, target, buf.readUtf(120), buf.readBoolean());
        }
        public void encode(RegistryFriendlyByteBuf buf, GuidanceS2CPayload value) {
            buf.writeVarInt(GuidanceC2SPayload.PROTOCOL); buf.writeUUID(value.requestId); buf.writeUUID(value.worldId); buf.writeUUID(value.ownerId); buf.writeUUID(value.sessionId);
            buf.writeVarLong(value.revision); buf.writeBoolean(value.enabled); buf.writeBoolean(value.paused); buf.writeBoolean(value.target != null);
            if (value.target != null) {
                var t = value.target; buf.writeUtf(t.id(), 256); buf.writeUtf(t.dimension(), 128); buf.writeDouble(t.x()); buf.writeDouble(t.y()); buf.writeDouble(t.z());
                buf.writeUtf(t.label(), 160); buf.writeUtf(t.canonicalLabel(), 160); buf.writeVarInt(t.kind().ordinal()); buf.writeBoolean(t.otherDimension()); buf.writeBoolean(t.directionOnly());
            }
            buf.writeUtf(value.noticeKey, 120); buf.writeBoolean(value.error);
        }
    };
    public GuidanceS2CPayload {
        Objects.requireNonNull(requestId); Objects.requireNonNull(worldId); Objects.requireNonNull(ownerId); Objects.requireNonNull(sessionId);
        if (revision < 0 || noticeKey == null || noticeKey.length() > 120 || !enabled && (target != null || paused))
            throw new IllegalArgumentException("Guidance page bounds");
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
