package dev.redzumi.spirit;

import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import dev.redzumi.spirit.ai.SpiritAi;

/** Прямое обращение через обычный чат: «@дух …». */
public final class SpiritEvents {
    private SpiritEvents() {}

    public static void register() {
        // @дух <текст> — диалог с духом
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            String raw = message.signedContent().trim();
            String lower = raw.toLowerCase(java.util.Locale.ROOT);
            if (lower.startsWith("@дух") || lower.startsWith("@duh") || lower.startsWith("@spirit")) {
                String question = raw.replaceFirst("(?i)@\\S+\\s*", "").trim();
                if (!question.isEmpty()) {
                    SpiritAi.chat(sender.level().getServer(), sender, question);
                }
            }
        });

    }
}
