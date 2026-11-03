package dev.redzumi.spirit.ai;

import com.google.gson.reflect.TypeToken;
import dev.redzumi.spirit.RedzSpirit;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Квестодатель: персональное задание от духа. Логика детерминирована
 * (LLM только озвучивает), награда — из безопасного whitelist'а.
 * redzspirit/quests.json: uuid -> активный квест.
 */
public final class SpiritQuests {
    public record Quest(String type, String target, int need, int progress, long issuedAt) {
        public String describe() {
            return switch (type) {
                case "collect" -> "принести %d× %s (%d/%d)".formatted(need, pretty(target), progress, need);
                case "visit" -> "побывать в биоме %s".formatted(pretty(target));
                case "slay" -> "победить %d× %s (%d/%d)".formatted(need, pretty(target), progress, need);
                default -> type + " " + target;
            };
        }
        public boolean done() {
            return "visit".equals(type) ? progress >= 1 : progress >= need;
        }
        private static String pretty(String id) {
            return id.substring(id.indexOf(':') + 1).replace('_', ' ');
        }
    }

    // --- шаблоны заданий ---
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

    /** Награды — только безопасные предметы из whitelist'а. */
    private static final List<String> REWARDS = List.of(
        "minecraft:diamond", "minecraft:golden_apple", "minecraft:emerald", "minecraft:experience_bottle");
    private static final int REWARD_COUNT = 2;

    private static final JsonStore<Map<String, Quest>> STORE =
        new JsonStore<>("quests.json", new TypeToken<>() {});
    private static final java.util.Random RND = new java.util.Random();

    private SpiritQuests() {}

    /** Активный квест игрока или null. */
    public static Quest active(UUID playerId) {
        return STORE.get(ConcurrentHashMap::new).get(playerId.toString());
    }

    /** Выдать новый квест (возвращает null, если уже есть активный). */
    public static Quest issue(UUID playerId) {
        var active = STORE.get(ConcurrentHashMap::new);
        if (active.containsKey(playerId.toString())) return null;
        var t = TEMPLATES.get(RND.nextInt(TEMPLATES.size()));
        int need = t.minNeed() + RND.nextInt(Math.max(1, t.maxNeed() - t.minNeed() + 1));
        var q = new Quest(t.type(), t.target(), need, 0, System.currentTimeMillis());
        active.put(playerId.toString(), q);
        STORE.save();
        return q;
    }

    /** Обновить прогресс; вернуть завершённый квест или null. */
    public static Quest update(UUID playerId, java.util.function.Function<Quest, Quest> updater) {
        var active = STORE.get(ConcurrentHashMap::new);
        var q = active.get(playerId.toString());
        if (q == null) return null;
        var updated = updater.apply(q);
        if (updated.done()) {
            active.remove(playerId.toString());
            STORE.save();
            return updated;
        }
        if (!updated.equals(q)) {
            active.put(playerId.toString(), updated);
            STORE.save();
        }
        return null;
    }

    /** Прогресс collect/visit — вызывается из тика. slay — из события убийства. */
    public static void tickProgress(ServerPlayer player) {
        var q = active(player.getUUID());
        if (q == null) return;
        switch (q.type()) {
            case "collect" -> {
                int count = countItem(player, q.target());
                if (count > q.progress()) {
                    var done = update(player.getUUID(), cur ->
                        new Quest(cur.type(), cur.target(), cur.need(), Math.min(cur.need(), count), cur.issuedAt()));
                    if (done != null) complete(player, done);
                }
            }
            case "visit" -> {
                String biome = SpiritSenses.biomeName(player);
                if (biome.equals(q.target())) {
                    var done = update(player.getUUID(), cur ->
                        new Quest(cur.type(), cur.target(), cur.need(), 1, cur.issuedAt()));
                    if (done != null) complete(player, done);
                }
            }
            default -> {}
        }
    }

    /** Убийство моба — прогресс slay-квестов. */
    public static void onKill(ServerPlayer player, String mobId) {
        var q = active(player.getUUID());
        if (q == null || !q.type().equals("slay") || !q.target().equals(mobId)) return;
        var done = update(player.getUUID(), cur ->
            new Quest(cur.type(), cur.target(), cur.need(), cur.progress() + 1, cur.issuedAt()));
        if (done != null) complete(player, done);
    }

    private static void complete(ServerPlayer player, Quest quest) {
        if ("collect".equals(quest.type())) {
            int remaining = quest.need();
            for (int i = 0; i < player.getInventory().getContainerSize() && remaining > 0; i++) {
                var stack = player.getInventory().getItem(i);
                if (!stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(quest.target())) {
                    int take = Math.min(remaining, stack.getCount());
                    stack.shrink(take);
                    remaining -= take;
                }
            }
            player.getInventory().setChanged();
        }
        // награда из whitelist'а
        var itemId = REWARDS.get(RND.nextInt(REWARDS.size()));
        var item = BuiltInRegistries.ITEM.get(Identifier.parse(itemId));
        item.ifPresent(ref -> {
            var stack = new ItemStack(ref.value(), REWARD_COUNT);
            if (!player.getInventory().add(stack)) player.spawnAtLocation((net.minecraft.server.level.ServerLevel) player.level(), stack);
        });

        // Completion and reward feedback also work while the LLM is offline.
        dev.redzumi.spirit.SpiritBubbles.speak(player,
            "Задание выполнено: «%s»! Твоя награда: %d× %s.".formatted(
                quest.describe(), REWARD_COUNT, item.map(ref -> new ItemStack(ref.value()).getHoverName().getString()).orElse(itemId)));
        RedzSpirit.LOGGER.info("[redz-spirit] quest done by {}: {}", player.getScoreboardName(), quest.describe());
    }

    private static int countItem(ServerPlayer player, String itemId) {
        int count = 0;
        var wanted = Identifier.parse(itemId);
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            var stack = inv.getItem(i);
            if (!stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(wanted)) {
                count += stack.getCount();
            }
        }
        return count;
    }

}
