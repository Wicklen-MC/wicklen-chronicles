package dev.wicklen.chronicles.research;

import com.google.gson.reflect.TypeToken;
import dev.wicklen.chronicles.WicklenChronicles;
import dev.wicklen.chronicles.ai.JsonStore;
import dev.wicklen.chronicles.ai.WorldStorageContext;
import dev.wicklen.chronicles.core.discovery.DiscoveryKind;
import dev.wicklen.chronicles.core.discovery.DiscoverySnapshot;
import dev.wicklen.chronicles.core.quest.RewardDefinition;
import dev.wicklen.chronicles.core.research.ResearchDefinition;
import dev.wicklen.chronicles.core.research.ResearchProgress;
import dev.wicklen.chronicles.core.research.ResearchSnapshot;
import dev.wicklen.chronicles.core.research.ResearchTransitions;
import dev.wicklen.chronicles.exploration.ExplorationService;
import dev.wicklen.chronicles.inventory.InventoryDelivery;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Small one-time research milestones, derived only from personal confirmed discoveries. */
public final class ResearchService {
    public static final List<ResearchDefinition> SERIES = List.of(
        new ResearchDefinition("wicklen_chronicles:field_notes", 1,
            "journal.wicklen_chronicles.research.field_notes", DiscoveryKind.CREATURE, 3,
            new RewardDefinition("minecraft:paper", 8)),
        new ResearchDefinition("wicklen_chronicles:waypoints", 1,
            "journal.wicklen_chronicles.research.waypoints", DiscoveryKind.PLACE, 2,
            new RewardDefinition("minecraft:torch", 12)));
    private static final Set<String> ALLOWED_REWARDS = Set.of("minecraft:paper", "minecraft:torch");
    private static final JsonStore<Map<String, ResearchSnapshot>> STORE =
        new JsonStore<>("research.json", new TypeToken<>() {});
    private static boolean registered;

    public record View(ResearchSnapshot snapshot, List<ResearchProgress> progress) {
        public View { progress = List.copyOf(progress); }
    }
    public enum ClaimResult { CLAIMED, FULL, SAVE_FAILED, REJECTED }

    public static void register() {
        if (registered) return;
        registered = true;
        ExplorationService.onChanged((player, facts) -> {
            try { evaluate(player, facts); }
            catch (RuntimeException error) {
                WicklenChronicles.LOGGER.warn("Research milestone deferred; existing data preserved: {}", error.toString());
            }
        });
    }

    public static View view(ServerPlayer player) {
        DiscoverySnapshot facts = ExplorationService.snapshot(player);
        ResearchSnapshot current = evaluate(player, facts);
        return new View(current, SERIES.stream().map(definition ->
            ResearchTransitions.progress(current, facts, definition)).toList());
    }

    private static ResearchSnapshot state(ServerPlayer player) {
        var all = STORE.get(HashMap::new);
        var value = all.get(player.getUUID().toString());
        if (value == null) {
            if (all.containsKey(player.getUUID().toString())) throw new IllegalStateException("Null research state");
            return ResearchTransitions.empty(WorldStorageContext.worldId(), player.getUUID());
        }
        if (!value.worldId().equals(WorldStorageContext.worldId()) || !value.ownerId().equals(player.getUUID()))
            throw new IllegalStateException("Research belongs to another world/player");
        return value;
    }

    private static ResearchSnapshot evaluate(ServerPlayer player, DiscoverySnapshot facts) {
        ResearchSnapshot old = state(player);
        ResearchSnapshot updated = old;
        for (var definition : SERIES)
            updated = ResearchTransitions.evaluate(updated, player.getUUID(), facts, definition).snapshot();
        if (!updated.equals(old) && !save(player.getUUID(), old, updated))
            throw new IllegalStateException("Could not save research entitlement");
        return updated;
    }

    public static ClaimResult claim(ServerPlayer player, UUID expectedRewardId) {
        if (!player.isAlive() || player.isSpectator()) return ClaimResult.REJECTED;
        ResearchSnapshot old = view(player).snapshot();
        var grant = old.grants().stream().filter(value -> value.id().equals(expectedRewardId)).findFirst().orElse(null);
        if (grant == null || grant.claimed()) return ClaimResult.REJECTED;
        var change = ResearchTransitions.acknowledgeReward(old, player.getUUID(), expectedRewardId);
        if (change.outcome() != ResearchTransitions.Outcome.CLAIMED) return ClaimResult.REJECTED;
        var reward = grant.definition().reward();
        if (!ALLOWED_REWARDS.contains(reward.itemId()) || reward.count() > 64) return ClaimResult.REJECTED;
        var item = BuiltInRegistries.ITEM.get(Identifier.parse(reward.itemId()));
        if (item.isEmpty()) return ClaimResult.REJECTED;
        var delivery = InventoryDelivery.deliver(player, List.of(new ItemStack(item.get().value(), reward.count())),
            () -> save(player.getUUID(), old, change.snapshot()));
        return switch (delivery) {
            case DELIVERED -> ClaimResult.CLAIMED;
            case FULL -> ClaimResult.FULL;
            case SAVE_FAILED -> ClaimResult.SAVE_FAILED;
        };
    }

    private static boolean save(UUID owner, ResearchSnapshot old, ResearchSnapshot updated) {
        var all = STORE.get(HashMap::new);
        boolean existed = all.containsKey(owner.toString());
        all.put(owner.toString(), updated);
        try {
            STORE.saveChecked();
            return true;
        } catch (IOException | RuntimeException error) {
            if (existed) all.put(owner.toString(), old); else all.remove(owner.toString());
            WicklenChronicles.LOGGER.warn("Research save failed; action rolled back: {}", error.toString());
            return false;
        }
    }

    private ResearchService() {}
}
