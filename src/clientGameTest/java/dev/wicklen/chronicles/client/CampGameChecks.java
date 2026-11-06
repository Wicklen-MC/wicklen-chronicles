package dev.wicklen.chronicles.client;

import dev.wicklen.chronicles.core.discovery.*;
import dev.wicklen.chronicles.exploration.DiscoveryRepository;
import dev.wicklen.chronicles.item.ChroniclesItems;
import dev.wicklen.chronicles.research.CampService;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.UUID;

/** Native inventory and block-interaction checks in the isolated client test world. */
public final class CampGameChecks {
    public static CampService.Camp verify(ServerPlayer player) {
        var inventory = player.getInventory();
        var before = new ArrayList<ItemStack>();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) before.add(inventory.getItem(slot).copy());
        var table = player.blockPosition().offset(2, 0, 0);
        var previousBlock = player.level().getBlockState(table);
        try {
            player.level().setBlock(table, Blocks.CARTOGRAPHY_TABLE.defaultBlockState(), 3);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ChroniclesItems.FIELD_NOTEBOOK));
            var hit = new BlockHitResult(Vec3.atCenterOf(table), Direction.UP, table, false);
            UseBlockCallback.EVENT.invoker().interact(player, player.level(), InteractionHand.MAIN_HAND, hit);
            var bound = CampService.view(player);
            require(bound != null && bound.desk().x() == table.getX(), "notebook binds the desk before vanilla table interaction");
            int cursor = bound.suppliedThrough();
            CampService.useDesk(player, table);
            require(CampService.view(player).suppliedThrough() == cursor, "binding does not create free resupply progress");
            // Confirmed server fixture facts isolate inventory/recovery behaviour from the separately tested scan input.
            var repository = new DiscoveryRepository();
            for (int i = 0; i < 3; i++) repository.observe(player.getUUID(), new Observation(
                new DiscoveryKey(DiscoveryKind.PLACE, "wicklen_chronicles:test/camp/" + UUID.randomUUID()),
                "Camp test observation", "Server fixture", bound.desk(), System.currentTimeMillis()));
            for (int slot = 0; slot < inventory.getNonEquipmentItems().size(); slot++)
                inventory.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ChroniclesItems.FIELD_NOTEBOOK));
            CampService.useDesk(player, table);
            require(CampService.view(player).suppliedThrough() == cursor && count(player, Items.TORCH) == 0,
                "full inventory retains field kit eligibility and drops no partial reward");
            inventory.clearContent();
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ChroniclesItems.FIELD_NOTEBOOK));
            CampService.useDesk(player, table);
            require(count(player, Items.TORCH) == 8 && count(player, Items.BREAD) == 4 && count(player, Items.PAPER) == 4,
                "returning to the desk delivers the complete advertised kit");
            require(CampService.view(player).suppliedThrough() == cursor + 3, "kit consumes only discovery progress");
            CampService.useDesk(player, table);
            require(count(player, Items.TORCH) == 8 && CampService.view(player).suppliedThrough() == cursor + 3,
                "repeating a desk interaction cannot duplicate a kit");
            return CampService.view(player);
        } catch (java.io.IOException failure) {
            throw new AssertionError("Could not prepare native camp fixture", failure);
        } finally {
            player.level().setBlock(table, previousBlock, 3);
            for (int slot = 0; slot < before.size(); slot++) inventory.setItem(slot, before.get(slot));
            inventory.setChanged();
            player.inventoryMenu.broadcastChanges();
        }
    }

    private static int count(ServerPlayer player, Item item) {
        int total = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            var stack = player.getInventory().getItem(slot);
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private CampGameChecks() {}
}
