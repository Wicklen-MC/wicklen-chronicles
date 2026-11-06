package dev.wicklen.chronicles.inventory;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Complete insertion or no change. The callback persists the entitlement acknowledgement. */
public final class InventoryDelivery {
    public enum Result { DELIVERED, FULL, SAVE_FAILED }
    private InventoryDelivery() {}

    public static Result deliver(ServerPlayer player, List<ItemStack> rewards, BooleanSupplier commit) {
        if (!player.level().getServer().isSameThread()) throw new IllegalStateException("Inventory delivery is server-thread only");
        var before = snapshot(player);
        var planned = before.stream().map(ItemStack::copy).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        for (ItemStack reward : rewards) {
            if (reward.isEmpty()) throw new IllegalArgumentException("Empty reward");
            int remaining = reward.getCount();
            int slots = player.getInventory().getNonEquipmentItems().size();
            for (int slot = 0; slot < slots && remaining > 0; slot++) {
                ItemStack stack = planned.get(slot);
                if (stack.isEmpty() || !ItemStack.isSameItemSameComponents(stack, reward)) continue;
                int room = Math.max(0, Math.min(stack.getMaxStackSize(), player.getInventory().getMaxStackSize(reward)) - stack.getCount());
                int add = Math.min(remaining, room);
                stack.grow(add);
                remaining -= add;
            }
            for (int slot = 0; slot < slots && remaining > 0; slot++) {
                if (!planned.get(slot).isEmpty() || !player.getInventory().canPlaceItem(slot, reward)) continue;
                int add = Math.min(remaining, Math.min(reward.getMaxStackSize(), player.getInventory().getMaxStackSize(reward)));
                planned.set(slot, reward.copyWithCount(add));
                remaining -= add;
            }
            if (remaining != 0) return Result.FULL;
        }
        try {
            restore(player, planned);
            if (!commit.getAsBoolean()) {
                restore(player, before);
                sync(player);
                return Result.SAVE_FAILED;
            }
        } catch (RuntimeException failure) {
            restore(player, before);
            sync(player);
            throw failure;
        }
        sync(player);
        return Result.DELIVERED;
    }

    private static List<ItemStack> snapshot(ServerPlayer player) {
        var copy = new ArrayList<ItemStack>();
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++)
            copy.add(player.getInventory().getItem(slot).copy());
        return copy;
    }
    private static void restore(ServerPlayer player, List<ItemStack> items) {
        for (int slot = 0; slot < items.size(); slot++) player.getInventory().setItem(slot, items.get(slot).copy());
    }
    private static void sync(ServerPlayer player) {
        player.getInventory().setChanged();
        player.inventoryMenu.broadcastChanges();
        if (player.containerMenu != player.inventoryMenu) player.containerMenu.broadcastChanges();
    }
}
