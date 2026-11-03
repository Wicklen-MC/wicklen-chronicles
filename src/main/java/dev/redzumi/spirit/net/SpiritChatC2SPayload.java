package dev.redzumi.spirit.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import dev.redzumi.spirit.RedzSpirit;

/** C2S: сообщение из окна диалога с духом. */
public record SpiritChatC2SPayload(String text) implements CustomPacketPayload {

    public static final Type<SpiritChatC2SPayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath(RedzSpirit.MOD_ID, "spirit_chat"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SpiritChatC2SPayload> CODEC =
        StreamCodec.composite(ByteBufCodecs.STRING_UTF8, SpiritChatC2SPayload::text, SpiritChatC2SPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
