package dev.wicklen.chronicles.story;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import dev.wicklen.chronicles.WicklenChronicles;
import dev.wicklen.chronicles.ai.*;
import dev.wicklen.chronicles.ai.llm.*;
import dev.wicklen.chronicles.core.discovery.*;
import dev.wicklen.chronicles.core.quest.*;
import dev.wicklen.chronicles.core.story.*;
import dev.wicklen.chronicles.exploration.ExplorationService;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MobCategory;

import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;

/** Confirmed personal facts -> bounded plan -> explicit decision -> recoverable quest handoff. */
public final class StoryService {
    public record ActionResult(boolean success, String message) {}
    private static final Gson GSON = new Gson();
    private static final JsonStore<Map<String, StorySnapshot>> STORE = new JsonStore<>("stories.json", new TypeToken<>() {});
    private static final Set<UUID> IN_FLIGHT = new HashSet<>();
    private static final Map<UUID, Long> LAST_REQUEST = new HashMap<>();
    private static final Set<String> REWARDS = Set.of("minecraft:emerald", "minecraft:experience_bottle");
    private static final List<String> SUPPLIES = List.of("minecraft:paper", "minecraft:torch", "minecraft:coal", "minecraft:wheat");
    private StoryService() {}

    public static void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            try { recover(handler.player); }
            catch (RuntimeException error) { WicklenChronicles.LOGGER.warn("Story recovery deferred: {}", error.toString()); }
        });
    }

    public static void reset() { IN_FLIGHT.clear(); LAST_REQUEST.clear(); }

    private static StorySnapshot state(ServerPlayer player) {
        var all = STORE.get(HashMap::new);
        StorySnapshot value = all.get(player.getUUID().toString());
        if (value == null) {
            if (all.containsKey(player.getUUID().toString())) throw new IllegalStateException("Null story state");
            return StoryTransitions.empty(WorldStorageContext.worldId(), player.getUUID());
        }
        if (!value.worldId().equals(WorldStorageContext.worldId()) || !value.ownerId().equals(player.getUUID()))
            throw new IllegalStateException("Story belongs to another world or player");
        return value;
    }

    public static StorySnapshot view(ServerPlayer player) {
        var value = state(player);
        var history = value.history();
        return new StorySnapshot(value.worldId(), value.ownerId(), value.revision(), value.pending(),
            history.subList(Math.max(0, history.size() - 5), history.size()));
    }

    public static void request(ServerPlayer player, Consumer<String> feedback) {
        UUID owner = player.getUUID();
        var old = state(player);
        if (!recover(player)) {
            feedback.accept(tr(player, "Your previous task is waiting to be restored. Try again later.",
                "Предыдущее поручение ждёт восстановления. Попробуй позже."));
            return;
        }
        if (old.pending() != null) { feedback.accept(tr(player, "A story is already waiting for your choice.", "История уже ждёт твоего выбора.")); return; }
        if (SpiritQuests.active(owner) != null) { feedback.accept(tr(player, "Finish your current task first.", "Сначала заверши текущее поручение.")); return; }
        if (IN_FLIGHT.contains(owner)) { feedback.accept(tr(player, "The spirit is considering your discoveries.", "Дух обдумывает твои открытия.")); return; }
        long now = System.currentTimeMillis();
        if (now - LAST_REQUEST.getOrDefault(owner, 0L) < 60_000) {
            feedback.accept(tr(player, "Give the spirit a minute before asking again.", "Дай духу минуту перед новым запросом.")); return;
        }
        DiscoverySnapshot facts = ExplorationService.snapshot(player);
        if (facts.entries().isEmpty()) { feedback.accept(tr(player, "Observe a creature or place with your spyglass first.", "Сначала изучи существо или место через подзорную трубу.")); return; }
        DiscoveryEntry source = facts.entries().getLast();
        List<StoryPlans.Goal> goals = goals(player, source);
        StoryPolicy policy = policy(goals);
        UUID planId = UUID.randomUUID();
        LAST_REQUEST.put(owner, now);
        LlmProvider provider = LlmProviders.active();
        if (provider == null) {
            accept(player, fallback(player, planId, facts, source, goals), policy, feedback);
            return;
        }
        IN_FLIGHT.add(owner);
        UUID session = WorldStorageContext.sessionId();
        var connection = player.connection;
        var server = player.level().getServer();
        String language = player.clientInformation().language();
        String context = GSON.toJson(Map.of("source", source, "goals", goals,
            "previousDecisions", old.history().stream().skip(Math.max(0, old.history().size() - 3)).toList()));
        String system = StoryPlans.prompt(language);
        var request = new LlmRequest(system, List.of(LlmMessage.user(context)), 1024, 0.7, true);
        AiRequests.complete(provider, request).whenComplete((response, error) -> server.execute(() -> {
            if (!WorldStorageContext.matchesSession(session)) return;
            IN_FLIGHT.remove(owner);
            var current = server.getPlayerList().getPlayer(owner);
            if (current == null || current.connection != connection) return;
            StoryPlan plan;
            try {
                if (error != null) throw new IllegalArgumentException("Story provider unavailable", error);
                plan = StoryPlans.decode(response, planId, facts, source, goals);
            } catch (RuntimeException invalid) {
                WicklenChronicles.LOGGER.info("Story using authored fallback: {}", invalid.getMessage());
                plan = fallback(current, planId, facts, source, goals);
            }
            accept(current, plan, policy, feedback);
        }));
    }

    private static void accept(ServerPlayer player, StoryPlan plan, StoryPolicy policy, Consumer<String> feedback) {
        var old = state(player);
        var change = StoryTransitions.accept(old, ExplorationService.snapshot(player), player.getUUID(), plan, policy);
        if (!change.changed()) {
            feedback.accept(tr(player, "Your discoveries changed. Ask for a new story.", "Твои открытия изменились. Попроси новую историю.")); return;
        }
        if (!save(player.getUUID(), old, change.snapshot())) {
            feedback.accept(tr(player, "Could not save the story. Try again later.", "Не удалось сохранить историю. Попробуй позже.")); return;
        }
        feedback.accept(tr(player, "A story is ready. Choose your path in Tasks.", "История готова. Выбери путь в разделе дел."));
    }

    public static ActionResult choose(ServerPlayer player, UUID expectedPlanId, String choiceId) {
        var old = state(player);
        if (!recover(player)) return new ActionResult(false, tr(player,
            "Your previous task is waiting to be restored.", "Предыдущее поручение ждёт восстановления."));
        if (old.history().stream().anyMatch(outcome -> outcome.planId().equals(expectedPlanId))) {
            recover(player);
            return new ActionResult(false, tr(player, "This choice is already recorded.", "Этот выбор уже сохранён."));
        }
        var pending = old.pending();
        if (pending == null) return new ActionResult(false, tr(player, "No pending story.", "Нет истории, ожидающей выбора."));
        var goals = pending.choices().stream().filter(choice -> available(player, choice.quest()))
            .map(choice -> new StoryPlans.Goal(choice.id(), choice.label(), choice.quest())).toList();
        var change = StoryTransitions.choose(old, ExplorationService.snapshot(player), player.getUUID(), expectedPlanId,
            choiceId, policy(goals), SpiritQuests.active(player.getUUID()) != null, System.currentTimeMillis());
        if (!change.changed()) return new ActionResult(false, tr(player, "That choice is no longer available.", "Этот выбор сейчас недоступен."));
        if (!save(player.getUUID(), old, change.snapshot())) return new ActionResult(false, tr(player, "Could not save your choice.", "Не удалось сохранить выбор."));
        // Decision first, then idempotent adoption. A crash between stores is repaired on JOIN/open.
        if (!SpiritQuests.adoptIssued(player.getUUID(), change.quest()))
            return new ActionResult(false, tr(player, "Choice saved. Task delivery will retry when you reopen the journal.", "Выбор сохранён. Поручение восстановится при открытии дневника."));
        return new ActionResult(true, tr(player, "Your choice is recorded. The task is in your journal.", "Выбор сохранён. Поручение появилось в дневнике."));
    }

    public static boolean recover(ServerPlayer player) {
        var history = state(player).history();
        // Only the latest selected task may need delivery. Older ones never replace an active task.
        return history.isEmpty() || SpiritQuests.adoptIssued(player.getUUID(), history.getLast().quest());
    }

    private static boolean save(UUID owner, StorySnapshot previous, StorySnapshot next) {
        var all = STORE.get(HashMap::new);
        all.put(owner.toString(), next);
        try { STORE.saveChecked(); return true; }
        catch (IOException | RuntimeException failure) { all.put(owner.toString(), previous); return false; }
    }

    private static List<StoryPlans.Goal> goals(ServerPlayer player, DiscoveryEntry source) {
        var result = new ArrayList<StoryPlans.Goal>();
        var reward = new RewardDefinition("minecraft:emerald", 1);
        for (String item : SUPPLIES) result.add(new StoryPlans.Goal(item, "Bring 4 " + item,
            new QuestDefinition("wicklen_chronicles:story/supplies/" + item.substring(item.indexOf(':') + 1),
                1, QuestType.COLLECT, item, 4, reward)));
        if (source.key().kind() == DiscoveryKind.CREATURE) {
            var type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(source.key().id()));
            if (type != null && type.getCategory() == MobCategory.MONSTER) result.add(new StoryPlans.Goal("fight", "Defeat 3 " + source.key().id(),
                new QuestDefinition("wicklen_chronicles:story/defend", 1, QuestType.SLAY, source.key().id(), 3,
                    new RewardDefinition("minecraft:experience_bottle", 2))));
        }
        if (source.key().kind() == DiscoveryKind.BIOME) result.add(new StoryPlans.Goal("visit", "Visit the known biome " + source.key().id(),
            new QuestDefinition("wicklen_chronicles:story/revisit", 1, QuestType.VISIT, source.key().id(), 1, reward)));
        return List.copyOf(result);
    }

    private static StoryPolicy policy(List<StoryPlans.Goal> goals) {
        Map<QuestType, Set<String>> targets = new EnumMap<>(QuestType.class);
        for (var goal : goals) targets.computeIfAbsent(goal.quest().type(), ignored -> new HashSet<>()).add(goal.quest().target());
        return new StoryPolicy(targets, REWARDS, 16, 2);
    }

    private static boolean available(ServerPlayer player, QuestDefinition quest) {
        Identifier target = Identifier.parse(quest.target());
        return switch (quest.type()) {
            case COLLECT -> BuiltInRegistries.ITEM.containsKey(target);
            case SLAY -> BuiltInRegistries.ENTITY_TYPE.containsKey(target);
            case VISIT -> player.level().registryAccess()
                .lookupOrThrow(net.minecraft.core.registries.Registries.BIOME)
                .containsKey(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.BIOME, target));
        };
    }

    private static StoryPlan fallback(ServerPlayer player, UUID id, DiscoverySnapshot facts, DiscoveryEntry source, List<StoryPlans.Goal> goals) {
        var one = goals.getFirst();
        var two = goals.getLast();
        String title = tr(player, "A page for " + source.title(), "Страница о находке: " + source.title());
        return new StoryPlan(id, facts.worldId(), facts.ownerId(), facts.revision(), source.key(),
            title.substring(0, Math.min(160, title.length())),
            tr(player, "Your observation deserves a place in Wicklen's field chronicle. Choose how to help the next expedition.",
                "Твоё наблюдение стоит сохранить в полевой летописи Виклена. Выбери, как помочь следующей экспедиции."),
            List.of(new StoryChoice("records", tr(player, "Prepare field supplies", "Подготовить полевые припасы"),
                tr(player, one.description() + ". Receive one emerald.", one.description() + ". Награда — изумруд."), one.quest()),
                new StoryChoice("expedition", tr(player, "Support an expedition", "Помочь экспедиции"),
                    tr(player, two.description() + ". Reward is shown with the task.", two.description() + ". Награда указана в поручении."), two.quest())));
    }

    private static String tr(ServerPlayer player, String en, String ru) {
        return player.clientInformation().language().startsWith("ru") ? ru : en;
    }
}
