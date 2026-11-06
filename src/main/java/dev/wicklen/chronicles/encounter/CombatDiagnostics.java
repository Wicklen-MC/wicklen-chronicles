package dev.wicklen.chronicles.encounter;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import dev.wicklen.chronicles.ai.JsonStore;
import dev.wicklen.chronicles.ai.WorldStorageContext;
import dev.wicklen.chronicles.core.encounter.CombatantSnapshot;
import dev.wicklen.chronicles.core.encounter.RecentForm;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.level.GameType;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.*;

/** Bounded diagnostic evidence; telemetry failure never changes a battle or its rewards. */
final class CombatDiagnostics {
    private static final Gson JSON = new Gson();
    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger("wicklen_chronicles.combat");
    private static final JsonStore<List<Result>> HISTORY = new JsonStore<>("combat-history.json", new TypeToken<>() {});
    private static final int HISTORY_LIMIT = 64;
    private static final long MAX_LOG_BYTES = 1_048_576;
    private final Map<UUID, Running> running = new HashMap<>();
    private boolean warned;

    record Gear(UUID playerId, String gameMode, String mainHand, String offHand, List<String> armorItems,
                double attackDamage, double armor, double maxHealth, int enchantmentLevels,
                boolean shield, boolean ranged, boolean debug) {
        Gear { armorItems = List.copyOf(armorItems); }
        CombatantSnapshot combatant() { return new CombatantSnapshot(playerId, attackDamage, armor, maxHealth, shield, ranged, debug); }
    }
    record Result(UUID encounterId, List<UUID> participants, long elapsedMillis, String outcome, String reason,
                  boolean skillEligible, int observedMobs, int returns, String strategyId) {
        Result { participants = List.copyOf(participants); }
    }
    private static final class Running {
        final Set<UUID> observed = new HashSet<>();
        Set<UUID> previous = Set.of();
        int wave;
        int returns;
        boolean debug;
    }

    static Gear capture(ServerPlayer player) {
        var armor = List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);
        var stacks = new ArrayList<ItemStack>();
        stacks.add(player.getMainHandItem()); stacks.add(player.getOffhandItem());
        for (var slot : armor) stacks.add(player.getItemBySlot(slot));
        int levels = 0;
        for (var stack : stacks) for (var enchantment : stack.getEnchantments().entrySet())
            levels = Math.min(255, levels + Math.min(255, enchantment.getIntValue()));
        boolean shield = stacks.subList(0, 2).stream().anyMatch(stack -> stack.has(DataComponents.BLOCKS_ATTACKS));
        boolean ranged = stacks.subList(0, 2).stream().anyMatch(stack -> stack.getItem() instanceof ProjectileWeaponItem || stack.is(Items.TRIDENT));
        return new Gear(player.getUUID(), player.gameMode().getSerializedName(), itemId(stacks.get(0)), itemId(stacks.get(1)),
                armor.stream().map(slot -> itemId(player.getItemBySlot(slot))).toList(),
                finite(player.getAttributeValue(Attributes.ATTACK_DAMAGE), 256), finite(player.getArmorValue(), 80),
                finite(player.getMaxHealth(), 200), levels, shield, ranged, debug(player));
    }

    static boolean debug(ServerPlayer player) {
        return player.gameMode() == GameType.CREATIVE || player.gameMode() == GameType.SPECTATOR
                || player.getAbilities().invulnerable;
    }
    private static double finite(double value, double max) { return Double.isFinite(value) ? Math.clamp(value, 0, max) : 0; }
    private static String itemId(ItemStack stack) {
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        return id.length() <= 160 ? id : id.substring(0, 160);
    }

    RecentForm recentForm(List<CombatantSnapshot> party) {
        try {
            var members = party.stream().map(CombatantSnapshot::playerId).collect(java.util.stream.Collectors.toSet());
            var results = HISTORY.get(ArrayList::new);
            int wins = 0, failures = 0, considered = 0;
            for (int i = results.size() - 1; i >= 0 && considered < 8; i--) {
                var result = results.get(i);
                if (!result.skillEligible() || Collections.disjoint(result.participants(), members)) continue;
                if (result.outcome().equals("SUCCESS")) wins++;
                else if (result.outcome().equals("FAILURE") || result.reason().equals("DEATH") || result.reason().equals("EXECUTION_TIMEOUT")) failures++;
                else continue;
                considered++;
            }
            return new RecentForm(wins, failures);
        } catch (RuntimeException error) { warning(error); return new RecentForm(0, 0); }
    }

    void prepared(EncounterSession session, List<Gear> gear) { event("PREPARE", session, Map.of("party", gear)); }
    void started(EncounterSession session) {
        var value = new Running();
        value.debug = session.gear().stream().anyMatch(Gear::debug);
        running.put(session.id(), value);
        event("START", session, Map.of("party", session.gear(), "plan", session.plan()));
    }
    void observe(EncounterSession session, List<UUID> tracked, java.util.function.Function<UUID, ServerPlayer> players) {
        var stats = running.get(session.id());
        if (stats == null) return;
        for (UUID owner : session.consenting()) {
            var player = players.apply(owner);
            if (player != null && debug(player)) stats.debug = true;
        }
        var present = Set.copyOf(tracked);
        if (!present.isEmpty() && (stats.wave == 0 || (!stats.previous.isEmpty() && Collections.disjoint(stats.previous, present))
                || (stats.previous.isEmpty() && present.stream().anyMatch(id -> !stats.observed.contains(id))))) {
            int limit = session.plan() == null ? 2 : session.plan().waves().size();
            if (stats.wave < limit) {
                stats.wave++;
                event("WAVE", session, Map.of("wave", stats.wave, "trackedMobs", Math.min(32, present.size())));
            }
        }
        for (var id : present) if (stats.observed.size() < 64) stats.observed.add(id);
        stats.previous = present;
    }
    void returned(UUID encounterId) { var stats = running.get(encounterId); if (stats != null) stats.returns = Math.min(1_000_000, stats.returns + 1); }
    void finished(EncounterSession session, String reason, boolean debugNow) {
        var stats = running.remove(session.id());
        boolean debug = debugNow || session.gear().isEmpty() || session.gear().stream().anyMatch(Gear::debug) || stats != null && stats.debug;
        String outcome = session.encounter().status().name();
        long elapsed = session.encounter().startedAt() == 0 ? 0 : Math.max(0, System.currentTimeMillis() - session.encounter().startedAt());
        var result = new Result(session.id(), session.consenting(), elapsed, outcome, reason,
                !debug && session.encounter().startedAt() > 0, stats == null ? 0 : stats.observed.size(),
                stats == null ? 0 : stats.returns, session.plan() == null ? "legacy" : session.plan().strategyId());
        event(outcome.equals("ABORTED") ? "CANCEL" : "END", session, Map.of("result", result));
        try {
            var history = HISTORY.get(ArrayList::new);
            if (history.stream().anyMatch(previous -> previous.encounterId().equals(session.id()))) return;
            var previous = new ArrayList<>(history);
            history.add(result);
            while (history.size() > HISTORY_LIMIT) history.removeFirst();
            try { HISTORY.saveChecked(); }
            catch (Exception error) { history.clear(); history.addAll(previous); throw error; }
        } catch (Exception error) { warning(error); }
    }
    void rewarded(EncounterSession session, UUID owner, UUID rewardId) { event("REWARD", session, Map.of("owner", owner, "rewardId", rewardId)); }

    private void event(String type, EncounterSession session, Map<String, ?> details) {
        var event = new LinkedHashMap<String, Object>();
        event.put("event", type); event.put("time", System.currentTimeMillis()); event.put("encounter", session.id());
        event.put("mode", session.mode()); event.put("partySize", session.consenting().size()); event.putAll(details);
        String line = JSON.toJson(event);
        // Data comes only from bounded authored records, never chat, item NBT or an inventory dump.
        if (line.length() > 12_000) { warning(new IllegalStateException("Combat diagnostic exceeded its size limit")); return; }
        LOG.info("{}", line);
        try {
            var file = WorldStorageContext.directory().resolve("combat-events.jsonl");
            if (Files.exists(file) && Files.size(file) + line.length() * 4L > MAX_LOG_BYTES)
                Files.move(file, file.resolveSibling("combat-events.previous.jsonl"), StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(file, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception error) { warning(error); }
    }
    private void warning(Exception error) {
        if (!warned) { warned = true; LOG.warn("Combat diagnostics unavailable; gameplay and rewards remain unchanged: {}", error.toString()); }
    }
}
