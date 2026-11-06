package dev.wicklen.chronicles.research;

import dev.wicklen.chronicles.item.ChroniclesItems;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.Blocks;

/** Intercepts the notebook before the vanilla cartography table opens its own menu. */
public final class FieldCampEvents {
    private static boolean registered;
    private FieldCampEvents() {}
    public static void register() {
        if (registered) return;
        registered = true;
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (!player.getItemInHand(hand).is(ChroniclesItems.FIELD_NOTEBOOK)
                    || !level.getBlockState(hit.getBlockPos()).is(Blocks.CARTOGRAPHY_TABLE)) return InteractionResult.PASS;
            if (player instanceof ServerPlayer serverPlayer) {
                try { CampService.useDesk(serverPlayer, hit.getBlockPos()); }
                catch (IllegalStateException unavailable) {
                    serverPlayer.sendOverlayMessage(Component.translatable("field_camp.wicklen_chronicles.save_failed"));
                }
            }
            return InteractionResult.SUCCESS;
        });
    }
}
