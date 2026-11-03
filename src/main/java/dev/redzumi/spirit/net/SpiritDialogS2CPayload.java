package dev.redzumi.spirit.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import dev.redzumi.spirit.RedzSpirit;

/** S2C: строка ответа духа в окно диалога (заголовок персоны + цвет + текст). */
public record SpiritDialogS2CPayload(String title, int color, String text)
        implements CustomPacketPayload {

    public static final Type<SpiritDialogS2CPayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath(RedzSpirit.MOD_ID, "spirit_dialog"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SpiritDialogS2CPayload> CODEC =
        StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, SpiritDialogS2CPayload::title,
            ByteBufCodecs.INT, SpiritDialogS2CPayload::color,
            ByteBufCodecs.STRING_UTF8, SpiritDialogS2CPayload::text,
            SpiritDialogS2CPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
