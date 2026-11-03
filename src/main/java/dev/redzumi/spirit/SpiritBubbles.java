package dev.redzumi.spirit;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import java.util.UUID;
import dev.redzumi.spirit.ai.SpiritAi;
import dev.redzumi.spirit.ai.SpiritPersonas;
import dev.redzumi.spirit.net.SpiritBubblePayload;
import dev.redzumi.spirit.net.SpiritChatC2SPayload;
import dev.redzumi.spirit.net.SpiritDialogS2CPayload;

/** Сетевой слой духа: облачка (S2C), окно диалога (C2S/S2C). */
public final class SpiritBubbles {
    private SpiritBubbles() {}

    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(SpiritBubblePayload.TYPE, SpiritBubblePayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(SpiritDialogS2CPayload.TYPE, SpiritDialogS2CPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SpiritChatC2SPayload.TYPE, SpiritChatC2SPayload.CODEC);

        // сообщение из окна диалога
        ServerPlayNetworking.registerGlobalReceiver(SpiritChatC2SPayload.TYPE, (payload, context) ->
            context.server().execute(() -> {
                ServerPlayer player = context.player();
                String text = payload.text().trim();
                if (text.isEmpty() || text.length() > 300) return;
                var persona = SpiritPersonas.of(player.getUUID());
                SpiritAi.chatWindow(context.server(), player, text, answer ->
                    ServerPlayNetworking.send(player,
                        new SpiritDialogS2CPayload(persona.title(), persona.color(), answer)));
            }));
    }

    private static final java.util.Map<UUID, Integer> SPIRIT_IDS = new java.util.concurrent.ConcurrentHashMap<>();

    /** Кэш id духа игрока: обновляется при спавне/удалении (SpiritManager). */
    public static void spiritId(java.util.UUID playerId, Integer entityId) {
        if (entityId == null) SPIRIT_IDS.remove(playerId); else SPIRIT_IDS.put(playerId, entityId);
    }

    /** Повесить текст над духом игрока (если дух есть). */
    public static void show(ServerPlayer player, int color, String text) {
        Integer id = SPIRIT_IDS.get(player.getUUID());
        if (id == null) return; // дух спрятан — носителя нет
        long ttl = Math.min(12_000, 4_000 + text.length() * 45L);
        ServerPlayNetworking.send(player, new SpiritBubblePayload(id, text, color, ttl));
    }
}
