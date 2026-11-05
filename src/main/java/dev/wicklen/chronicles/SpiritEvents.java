package dev.wicklen.chronicles;

import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import dev.wicklen.chronicles.ai.SpiritAi;

/** Direct conversation through normal chat: {@code @spirit <message>}. */
public final class SpiritEvents {
    private SpiritEvents() {}

    public static void register() {
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            String[] parts = message.signedContent().strip().split("\\s+", 2);
            if (parts.length == 2 && parts[0].equalsIgnoreCase("@spirit")) {
                SpiritAi.chat(sender.level().getServer(), sender, parts[1]);
            }
        });
    }
}
