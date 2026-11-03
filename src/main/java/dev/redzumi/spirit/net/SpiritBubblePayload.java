package dev.redzumi.spirit.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import dev.redzumi.spirit.RedzSpirit;

/** S2C: показать «облачко» с текстом над духом игрока на ttl миллисекунд. */
public record SpiritBubblePayload(int entityId, String text, int color, long ttlMs)
        implements CustomPacketPayload {

    public static final Type<SpiritBubblePayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath(RedzSpirit.MOD_ID, "spirit_bubble"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SpiritBubblePayload> CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SpiritBubblePayload::entityId,
            ByteBufCodecs.STRING_UTF8, SpiritBubblePayload::text,
            ByteBufCodecs.INT, SpiritBubblePayload::color,
            ByteBufCodecs.VAR_LONG, SpiritBubblePayload::ttlMs,
            SpiritBubblePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
