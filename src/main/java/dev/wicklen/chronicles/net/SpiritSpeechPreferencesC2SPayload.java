package dev.wicklen.chronicles.net;

import dev.wicklen.chronicles.WicklenChronicles;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Settings for the authenticated sender only; the client cannot supply another player's identity. */
public record SpiritSpeechPreferencesC2SPayload(boolean automaticEnabled, int minIntervalMinutes)
        implements CustomPacketPayload {
    public static final Type<SpiritSpeechPreferencesC2SPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(WicklenChronicles.MOD_ID, "speech_preferences"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SpiritSpeechPreferencesC2SPayload> CODEC = new StreamCodec<>() {
        @Override public SpiritSpeechPreferencesC2SPayload decode(RegistryFriendlyByteBuf buffer) {
            if (buffer.readVarInt() != JournalRequestPayload.PROTOCOL) throw new IllegalArgumentException("Unsupported speech preferences protocol");
            return new SpiritSpeechPreferencesC2SPayload(buffer.readBoolean(), buffer.readVarInt());
        }
        @Override public void encode(RegistryFriendlyByteBuf buffer, SpiritSpeechPreferencesC2SPayload value) {
            buffer.writeVarInt(JournalRequestPayload.PROTOCOL);
            buffer.writeBoolean(value.automaticEnabled());
            buffer.writeVarInt(value.minIntervalMinutes());
        }
    };
    public SpiritSpeechPreferencesC2SPayload {
        if (minIntervalMinutes < 2 || minIntervalMinutes > 30) throw new IllegalArgumentException("Automatic speech interval must be 2..30 minutes");
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
