package dev.wicklen.chronicles.ai;

import com.google.gson.reflect.TypeToken;
import dev.wicklen.chronicles.WicklenChronicles;
import dev.wicklen.chronicles.core.quest.QuestDefinition;
import dev.wicklen.chronicles.core.quest.QuestInstance;
import dev.wicklen.chronicles.core.quest.QuestStatus;
import dev.wicklen.chronicles.core.quest.QuestTransitions;
import dev.wicklen.chronicles.core.quest.QuestType;
import dev.wicklen.chronicles.core.quest.RewardDefinition;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Minecraft adapter. Quest rules live in the dependency-free core. Server-thread only. */
public final class SpiritQuests {
    /** Compatibility/read model for the existing senses and dialogue. */
    public record Quest(QuestInstance instance) {
        public String type() { return instance.definition().type().name().toLowerCase(java.util.Locale.ROOT); }
        public String target() { return instance.definition().target(); }
        public int need() { return instance.definition().required(); }
        public int progress() { return instance.progress(); }
        public long issuedAt() { return instance.issuedAt(); }
        public UUID id() { return instance.id(); }
        public UUID rewardId() { return instance.rewardId(); }
        public QuestStatus status() { return instance.status(); }
        public boolean done() { return status() != QuestStatus.ACTIVE; }

        public String describe() {
            return switch (instance.definition().type()) {
                case COLLECT -> "принести %d× %s (%d/%d)".formatted(need(), pretty(target()), progress(), need());
                case VISIT -> "побывать в биоме %s (%d/%d)".formatted(pretty(target()), progress(), need());
                case SLAY -> "победить %d× %s (%d/%d)".formatted(need(), pretty(target()), progress(), need());
            };
        }

        public String instructions() {
            return switch (status()) {
                case ACTIVE -> "Задание выполняется.";
                case READY -> "Можно сдать: /spirit quest turnin";
                case REWARD_PENDING -> "Награда ждёт: /spirit quest claim";
                case REWARDED -> "Награда получена.";
            };
        }
    }

    public record Template(String type, String target, int minNeed, int maxNeed) {}
    public static final List<Template> TEMPLATES = List.of(
        new Template("collect", "minecraft:apple", 3, 6),
        new Template("collect", "minecraft:coal", 8, 16),
        new Template("collect", "minecraft:iron_ingot", 3, 8),
        new Template("collect", "minecraft:wheat", 8, 16),
        new Template("collect", "minecraft:feather", 4, 8),
        new Template("visit", "minecraft:desert", 1, 1),
        new Template("visit", "minecraft:taiga", 1, 1),
        new Template("visit", "minecraft:jungle", 1, 1),
        new Template("visit", "minecraft:swamp", 1, 1),
        new Template("visit", "minecraft:snowy_plains", 1, 1),
        new Template("slay", "minecraft:zombie", 5, 12),
        new Template("slay", "minecraft:skeleton", 5, 10),
        new Template("slay", "minecraft:spider", 4, 8),
        new Template("slay", "minecraft:creeper", 3, 6));

    private static final List<String> REWARDS = List.of(
        "minecraft:diamond", "minecraft:golden_apple", "minecraft:emerald", "minecraft:experience_bottle");
    private static final int REWARD_COUNT = 2;
    // A new name deliberately avoids misreading the previous, incompatible quests.json shape.
    // Ambiguous game-directory saves are preserved; they are never imported into every world.
    private static final JsonStore<Map<String, PlayerQuests>> STORE =
        new JsonStore<>("quest-ledger.json", new TypeToken<>() {});
    private static final java.util.Random RND = new java.util.Random();
    private static UUID failedObservationSession;

    /** Completed instances remain immutable history; an unclaimed reward blocks a new quest. */
    public record PlayerQuests(QuestInstance active, List<QuestInstance> history) {
        public PlayerQuests {
            history = List.copyOf(history);
            if (active != null && active.status() == QuestStatus.REWARDED)
                throw new IllegalArgumentException("Rewarded quest belongs in history");
            if (history.stream().anyMatch(q -> q.status() != QuestStatus.REWARDED))
                throw new IllegalArgumentException("History contains an unfinished quest");
            var questIds = new java.util.HashSet<UUID>();
            var rewardIds = new java.util.HashSet<UUID>();
            if (active != null) { questIds.add(active.id()); rewardIds.add(active.rewardId()); }
            for (var q : history) {
                if (!questIds.add(q.id()) || !rewardIds.add(q.rewardId()))
                    throw new IllegalArgumentException("Duplicate quest or reward identity");
            }
        }
    }
    public record ActionResult(boolean success, String message) {}

    private SpiritQuests() {}

    private static Map<String, PlayerQuests> ledger() {
        var directory = WorldStorageContext.directory();
        var legacy = directory.resolve("quests.json");
        if (!Files.exists(directory.resolve("quest-ledger.json")) && Files.exists(legacy)) {
            try {
                // Decode before loading a default store, so a bad source cannot become an empty ledger on shutdown.
                var converted = LegacyQuestMigration.read(legacy, WorldStorageContext.worldId(), REWARDS);
                var all = STORE.get(HashMap::new);
                all.putAll(converted);
                STORE.saveChecked();
                return all;
            } catch (IOException e) {
                throw new IllegalStateException("Legacy quest import failed; source preserved", e);
            }
        }
        return STORE.get(HashMap::new);
    }

    private static PlayerQuests state(UUID playerId) {
        var all = ledger();
        var state = all.get(playerId.toString());
        if (state == null) {
            if (all.containsKey(playerId.toString())) throw new IllegalStateException("Null saved player quest state");
            return new PlayerQuests(null, List.of());
        }
        if ((state.active() != null && !state.active().ownerId().equals(playerId))
                || state.history().stream().anyMatch(q -> !q.ownerId().equals(playerId)))
            throw new IllegalStateException("Quest owner does not match saved player");
        return state;
    }

    public static Quest active(UUID playerId) {
        var q = state(playerId).active();
        return q == null ? null : new Quest(q);
    }

    public static Quest issue(UUID playerId) {
        var t = TEMPLATES.get(RND.nextInt(TEMPLATES.size()));
        int need = t.minNeed() + RND.nextInt(t.maxNeed() - t.minNeed() + 1);
        var definition = new QuestDefinition("redzspirit:" + t.type() + "/" + t.target().replace(':', '/'),
            1, QuestType.valueOf(t.type().toUpperCase(java.util.Locale.ROOT)), t.target(), need,
            new RewardDefinition(REWARDS.get(RND.nextInt(REWARDS.size())), REWARD_COUNT));
        return issue(playerId, definition);
    }

    /** Trusted server entry for authored content and deterministic game checks. */
    public static Quest issue(UUID playerId, QuestDefinition definition) {
        if (!REWARDS.contains(definition.reward().itemId()) || definition.reward().count() > 64)
            throw new IllegalArgumentException("Reward is outside the adapter policy");
        var old = state(playerId);
        if (old.active() != null) return null;
        var q = QuestTransitions.issue(UUID.randomUUID(), playerId, definition, System.currentTimeMillis());
        if (!persist(playerId, old, new PlayerQuests(q, old.history())))
            throw new IllegalStateException("Не удалось сохранить новое задание. Попробуй позже.");
        return new Quest(q);
    }

    /** Observation changes readiness only. It never removes items or grants a reward. */
    public static void tickProgress(ServerPlayer player) {
        observeSafely(() -> {
            var old = state(player.getUUID());
            var q = old.active();
            if (q == null) return;
            var updated = switch (q.definition().type()) {
                case COLLECT -> QuestTransitions.observeInventory(q, countItem(player, q.definition().target()));
                case VISIT -> QuestTransitions.observeVisit(q, SpiritSenses.biomeName(player));
                case SLAY -> q;
            };
            updateObservation(player, old, updated);
        });
    }

    public static void onKill(ServerPlayer player, String mobId) {
        observeSafely(() -> {
            var old = state(player.getUUID());
            if (old.active() == null) return;
            updateObservation(player, old, QuestTransitions.observeKill(old.active(), mobId));
        });
    }

    /** A bad optional ledger disables observation for this session, never the server tick. */
    private static void observeSafely(Runnable observation) {
        UUID session = WorldStorageContext.sessionId();
        if (session == null || session.equals(failedObservationSession)) return;
        try { observation.run(); }
        catch (IllegalStateException | IllegalArgumentException e) {
            failedObservationSession = session;
            WicklenChronicles.LOGGER.error("[Wicklen: Chronicles] quest observations disabled for this world session; source preserved: {}", e.toString());
        }
    }

    private static void updateObservation(ServerPlayer player, PlayerQuests old, QuestInstance updated) {
        if (updated.equals(old.active())) return;
        if (persist(player.getUUID(), old, new PlayerQuests(updated, old.history()))
                && old.active().status() != QuestStatus.READY && updated.status() == QuestStatus.READY)
            dev.wicklen.chronicles.SpiritBubbles.speak(player,
                "Условия выполнены. Сдать задание: /spirit quest turnin");
    }

    public static ActionResult turnIn(ServerPlayer player, UUID expectedQuestId) {
        var old = state(player.getUUID());
        var q = old.active();
        if (q == null) return failure("Нет активного задания. Получить: /spirit quest");
        var transition = QuestTransitions.turnIn(q, player.getUUID(), expectedQuestId,
            countItem(player, q.definition().target()));
        if (!transition.accepted()) {
            if (!transition.quest().equals(q))
                persist(player.getUUID(), old, new PlayerQuests(transition.quest(), old.history()));
            return rejected(transition.outcome());
        }
        var before = snapshotInventory(player);
        try {
            if (q.definition().type() == QuestType.COLLECT)
                removeItems(player, q.definition().target(), q.definition().required());
            if (!persist(player.getUUID(), old, new PlayerQuests(transition.quest(), old.history()))) {
                restoreInventory(player, before);
                syncInventory(player);
                return failure("Не удалось сохранить сдачу. Предметы оставлены у тебя; попробуй позже.");
            }
        } catch (RuntimeException e) {
            restoreInventory(player, before);
            syncInventory(player);
            throw e;
        }
        syncInventory(player);
        return new ActionResult(true, "Задание сдано. Получить награду: /spirit quest claim");
    }

    public static ActionResult claim(ServerPlayer player, UUID expectedRewardId) {
        var old = state(player.getUUID());
        var q = old.active();
        if (q == null) return failure("Нет ожидающей награды. Уже полученные награды повторно не выдаются.");
        var transition = QuestTransitions.claim(q, player.getUUID(), expectedRewardId);
        if (!transition.accepted()) return rejected(transition.outcome());
        var reward = q.definition().reward();
        var item = BuiltInRegistries.ITEM.get(Identifier.parse(reward.itemId()));
        if (item.isEmpty() || !REWARDS.contains(reward.itemId()))
            return failure("Предмет награды сейчас недоступен. Награда сохранена, сообщи администратору.");
        var stack = new ItemStack(item.get().value(), reward.count());
        var before = snapshotInventory(player);
        var planned = planInsertion(player, before, stack);
        if (planned == null)
            return failure("Недостаточно места в инвентаре. Освободи место: награда останется ждать.");
        var history = new ArrayList<>(old.history());
        history.add(transition.quest());
        try {
            restoreInventory(player, planned);
            if (!persist(player.getUUID(), old, new PlayerQuests(null, history))) {
                restoreInventory(player, before);
                syncInventory(player);
                return failure("Не удалось сохранить получение. Награда остаётся ждать; попробуй позже.");
            }
        } catch (RuntimeException e) {
            restoreInventory(player, before);
            syncInventory(player);
            throw e;
        }
        syncInventory(player);
        // MC player data and the ledger are separate save mechanisms. Rollback handles ordinary
        // failures; abrupt process/power loss between their disk writes is not an atomic transaction.
        WicklenChronicles.LOGGER.info("[Wicklen: Chronicles] quest {} reward {} claimed by {}", q.id(), q.rewardId(), player.getScoreboardName());
        return new ActionResult(true, "Награда получена: %d× %s. Новое задание: /spirit quest".formatted(
            reward.count(), stack.getHoverName().getString()));
    }

    private static ActionResult rejected(QuestTransitions.Outcome outcome) {
        return failure(switch (outcome) {
            case WRONG_OWNER -> "Это задание принадлежит другому игроку.";
            case STALE_ID -> "Это устаревшее действие. Посмотреть текущее задание: /spirit quest";
            case NOT_READY -> "Условия ещё не выполнены. Для сдачи материалов они должны быть в инвентаре.";
            case ALREADY_TURNED_IN -> "Задание уже сдано. Посмотреть награду: /spirit quest";
            case ALREADY_CLAIMED -> "Эта награда уже получена.";
            default -> "Действие не изменило задание. Посмотреть состояние: /spirit quest";
        });
    }

    private static ActionResult failure(String message) { return new ActionResult(false, message); }

    private static boolean persist(UUID owner, PlayerQuests old, PlayerQuests updated) {
        var all = ledger();
        boolean existed = all.containsKey(owner.toString());
        all.put(owner.toString(), updated);
        try {
            STORE.saveChecked();
            return true;
        } catch (IOException | RuntimeException e) {
            if (existed) all.put(owner.toString(), old); else all.remove(owner.toString());
            WicklenChronicles.LOGGER.warn("[Wicklen: Chronicles] quest state save failed; action rolled back: {}", e.toString());
            return false;
        }
    }

    private static List<ItemStack> snapshotInventory(ServerPlayer player) {
        var result = new ArrayList<ItemStack>();
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++)
            result.add(player.getInventory().getItem(slot).copy());
        return result;
    }

    private static void restoreInventory(ServerPlayer player, List<ItemStack> snapshot) {
        for (int slot = 0; slot < snapshot.size(); slot++)
            player.getInventory().setItem(slot, snapshot.get(slot).copy());
    }

    /** Build a complete insertion before touching the real inventory; never drop leftovers. */
    private static List<ItemStack> planInsertion(ServerPlayer player, List<ItemStack> before, ItemStack reward) {
        var planned = before.stream().map(ItemStack::copy).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        int remaining = reward.getCount();
        int slots = player.getInventory().getNonEquipmentItems().size();
        for (int i = 0; i < slots && remaining > 0; i++) {
            var stack = planned.get(i);
            if (stack.isEmpty() || !ItemStack.isSameItemSameComponents(stack, reward)) continue;
            int room = Math.max(0, Math.min(stack.getMaxStackSize(), player.getInventory().getMaxStackSize(reward)) - stack.getCount());
            int add = Math.min(remaining, room);
            stack.grow(add);
            remaining -= add;
        }
        for (int i = 0; i < slots && remaining > 0; i++) {
            if (!planned.get(i).isEmpty() || !player.getInventory().canPlaceItem(i, reward)) continue;
            int add = Math.min(remaining, Math.min(reward.getMaxStackSize(), player.getInventory().getMaxStackSize(reward)));
            planned.set(i, reward.copyWithCount(add));
            remaining -= add;
        }
        return remaining == 0 ? planned : null;
    }

    private static void syncInventory(ServerPlayer player) {
        player.getInventory().setChanged();
        try {
            player.inventoryMenu.broadcastChanges();
            if (player.containerMenu != player.inventoryMenu) player.containerMenu.broadcastChanges();
        } catch (RuntimeException e) {
            // A display failure must never undo an already committed inventory/ledger mutation.
            WicklenChronicles.LOGGER.warn("[Wicklen: Chronicles] inventory display sync failed after quest action: {}", e.toString());
        }
    }

    private static int countItem(ServerPlayer player, String itemId) {
        int count = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            var stack = player.getInventory().getItem(slot);
            if (matches(stack, itemId)) count += stack.getCount();
        }
        return count;
    }

    private static void removeItems(ServerPlayer player, String itemId, int amount) {
        int remaining = amount;
        for (int slot = 0; slot < player.getInventory().getContainerSize() && remaining > 0; slot++) {
            var stack = player.getInventory().getItem(slot);
            if (!matches(stack, itemId)) continue;
            int take = Math.min(remaining, stack.getCount());
            stack.shrink(take);
            remaining -= take;
        }
        if (remaining != 0) throw new IllegalStateException("Inventory changed during quest hand-in");
    }

    private static boolean matches(ItemStack stack, String itemId) {
        return !stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(itemId);
    }

    private static String pretty(String id) {
        return id.substring(id.indexOf(':') + 1).replace('_', ' ');
    }
}
