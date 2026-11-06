package dev.wicklen.chronicles.item;

import dev.wicklen.chronicles.research.CampService;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import java.util.function.Consumer;

public final class FieldNotebookItem extends Item {
    public FieldNotebookItem(Properties properties) { super(properties); }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (!context.getLevel().getBlockState(context.getClickedPos()).is(Blocks.CARTOGRAPHY_TABLE)) return InteractionResult.PASS;
        if (context.getPlayer() instanceof ServerPlayer player) {
            try { CampService.useDesk(player, context.getClickedPos()); }
            catch (IllegalStateException unavailable) {
                player.sendOverlayMessage(Component.translatable("field_camp.wicklen_chronicles.save_failed"));
            }
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag flag) {
        tooltip.accept(Component.translatable("item.wicklen_chronicles.field_notebook.hint"));
    }
}
