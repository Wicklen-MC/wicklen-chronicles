package dev.wicklen.chronicles.research;

import com.google.gson.reflect.TypeToken;
import dev.wicklen.chronicles.ai.JsonStore;
import dev.wicklen.chronicles.ai.WorldStorageContext;
import dev.wicklen.chronicles.core.discovery.DiscoveryPosition;
import dev.wicklen.chronicles.exploration.ExplorationService;
import dev.wicklen.chronicles.inventory.InventoryDelivery;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** A cartography table becomes a personal field desk. New discoveries fund the next expedition. */
public final class CampService {
    public static final int DISCOVERIES_PER_KIT = 3;
    public record Camp(UUID worldId, UUID ownerId, DiscoveryPosition desk, int suppliedThrough) {
        public Camp {
            Objects.requireNonNull(worldId);
            Objects.requireNonNull(ownerId);
            Objects.requireNonNull(desk);
            if (suppliedThrough < 0) throw new IllegalArgumentException("Negative field desk progress");
        }
    }
    private static final JsonStore<Map<String, Camp>> STORE = new JsonStore<>("field-camps.json", new TypeToken<>() {});
    private CampService() {}

    public static Camp view(ServerPlayer player) {
        var all = STORE.get(HashMap::new);
        var camp = all.get(player.getUUID().toString());
        if (camp == null && all.containsKey(player.getUUID().toString())) throw new IllegalStateException("Null field camp");
        if (camp != null && (!camp.ownerId().equals(player.getUUID()) || !camp.worldId().equals(WorldStorageContext.worldId())))
            throw new IllegalStateException("Field camp belongs to another player or world");
        return camp;
    }

    /** Called only from a real server-side notebook use on a nearby cartography table. */
    public static boolean useDesk(ServerPlayer player, BlockPos pos) {
        if (!player.isAlive() || player.isSpectator() || player.distanceToSqr(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5) > 64
                || !player.level().getBlockState(pos).is(Blocks.CARTOGRAPHY_TABLE)) return false;
        var position = new DiscoveryPosition(player.level().dimension().identifier().toString(), pos.getX(), pos.getY(), pos.getZ());
        Camp old = view(player);
        int discoveries = ExplorationService.snapshot(player).entries().size();
        if (old == null || (!old.desk().equals(position) && player.isShiftKeyDown())) {
            var next = new Camp(WorldStorageContext.worldId(), player.getUUID(), position, old == null ? discoveries : old.suppliedThrough());
            boolean saved = save(player, old, next);
            tell(player, saved ? "bound" : "save_failed");
            return saved;
        }
        if (!old.desk().equals(position)) { tell(player, "move_hint"); return false; }
        int available = discoveries - old.suppliedThrough();
        if (available < DISCOVERIES_PER_KIT) {
            player.sendOverlayMessage(Component.translatable("field_camp.wicklen_chronicles.progress", Math.max(0, available), DISCOVERIES_PER_KIT));
            return true;
        }
        var next = new Camp(old.worldId(), old.ownerId(), old.desk(), old.suppliedThrough() + DISCOVERIES_PER_KIT);
        var result = InventoryDelivery.deliver(player, java.util.List.of(new ItemStack(Items.TORCH, 8),
            new ItemStack(Items.BREAD, 4), new ItemStack(Items.PAPER, 4)), () -> save(player, old, next));
        tell(player, switch (result) {
            case DELIVERED -> "supplied";
            case FULL -> "inventory_full";
            case SAVE_FAILED -> "save_failed";
        });
        return result == InventoryDelivery.Result.DELIVERED;
    }

    private static boolean save(ServerPlayer player, Camp previous, Camp next) {
        var all = STORE.get(HashMap::new);
        all.put(player.getUUID().toString(), next);
        try { STORE.saveChecked(); return true; }
        catch (IOException | RuntimeException failure) {
            if (previous == null) all.remove(player.getUUID().toString()); else all.put(player.getUUID().toString(), previous);
            return false;
        }
    }
    private static void tell(ServerPlayer player, String key) {
        player.sendOverlayMessage(Component.translatable("field_camp.wicklen_chronicles." + key));
    }
}
