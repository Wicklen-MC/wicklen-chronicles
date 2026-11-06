package dev.wicklen.chronicles.client;

import dev.wicklen.chronicles.item.ChroniclesItems;
import dev.wicklen.chronicles.net.JournalRequestPayload;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;

/** Instructions live on the objects that players use, as well as in the journal. */
final class ChroniclesTooltips {
    private ChroniclesTooltips() {}

    static void register() {
        ItemTooltipCallback.EVENT.register((stack, context, flags, lines) -> {
            String item;
            if (stack.is(ChroniclesItems.FIELD_NOTEBOOK)) item = "notebook";
            else if (stack.is(ChroniclesItems.WAYFARER_LANTERN)) item = "lantern";
            else if (stack.is(Items.SPYGLASS) && ClientPlayNetworking.canSend(JournalRequestPayload.TYPE)) item = "spyglass";
            else return;
            lines.add(Component.translatable("wicklen_chronicles.help." + item + ".action").withStyle(ChatFormatting.GOLD));
            lines.add(Component.translatable("wicklen_chronicles.help." + item + ".detail").withStyle(ChatFormatting.GRAY));
            lines.add(Component.translatable("wicklen_chronicles.help." + item + ".result").withStyle(ChatFormatting.GRAY));
        });
    }
}
