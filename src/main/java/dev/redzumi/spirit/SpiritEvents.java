package dev.redzumi.spirit;

import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import dev.redzumi.spirit.ai.SpiritAi;

/** Триггеры: чат «@дух …», приветствие при входе. */
public final class SpiritEvents {
    private SpiritEvents() {}

    public static void register() {
        // @дух <текст> — диалог с духом
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            String raw = message.signedContent().trim();
            String lower = raw.toLowerCase();
            if (lower.startsWith("@дух") || lower.startsWith("@duh") || lower.startsWith("@spirit")) {
                String question = raw.replaceFirst("(?i)@\\S+\\s*", "").trim();
                if (!question.isEmpty()) {
                    SpiritAi.chat(sender.level().getServer(), sender, question);
                }
            }
        });

        // приветствие при входе (не тратим LLM: локальная реплика) + слух
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            var persona = dev.redzumi.spirit.ai.SpiritPersonas.of(player.getUUID());
            player.sendSystemMessage(
                Component.literal(persona.joinLine()).withStyle(s -> s.withColor(persona.color())));
            dev.redzumi.spirit.ai.SpiritRumors.add("Слух: " + player.getScoreboardName() + " вернулся в мир.");
        });
    }
}
