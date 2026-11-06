package dev.wicklen.chronicles.net;

import dev.wicklen.chronicles.WicklenChronicles;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import java.util.Objects;

/** A private screen-space utterance, independent of the companion's visibility or entity tracking. */
public record SpiritSpeechS2CPayload(String title, int color, String text, int ttlMs) implements CustomPacketPayload {
    public static final Type<SpiritSpeechS2CPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(WicklenChronicles.MOD_ID, "spirit_speech"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SpiritSpeechS2CPayload> CODEC = new StreamCodec<>() {
        @Override public SpiritSpeechS2CPayload decode(RegistryFriendlyByteBuf buffer) {
            return new SpiritSpeechS2CPayload(buffer.readUtf(80), buffer.readInt(), buffer.readUtf(2000), buffer.readVarInt());
        }
        @Override public void encode(RegistryFriendlyByteBuf buffer, SpiritSpeechS2CPayload value) {
            buffer.writeUtf(value.title(), 80);
            buffer.writeInt(value.color());
            buffer.writeUtf(value.text(), 2000);
            buffer.writeVarInt(value.ttlMs());
        }
    };
    public SpiritSpeechS2CPayload {
        Objects.requireNonNull(title); Objects.requireNonNull(text);
        if (title.isBlank() || title.length() > 80 || text.isBlank() || text.length() > 2000
                || ttlMs < 2500 || ttlMs > 12_000) throw new IllegalArgumentException("Spirit speech bounds");
        color &= 0xFFFFFF;
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
