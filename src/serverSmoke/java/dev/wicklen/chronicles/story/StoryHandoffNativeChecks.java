package dev.wicklen.chronicles.story;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import dev.wicklen.chronicles.ai.SpiritQuests;
import dev.wicklen.chronicles.ai.WorldStorageContext;
import dev.wicklen.chronicles.ai.llm.LlmProviders;
import dev.wicklen.chronicles.core.discovery.*;
import dev.wicklen.chronicles.core.quest.*;
import dev.wicklen.chronicles.core.story.*;
import dev.wicklen.chronicles.exploration.DiscoveryRepository;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Reproduces a committed story decision whose separate quest-store write failed. */
public final class StoryHandoffNativeChecks {
    private StoryHandoffNativeChecks() {}

    public static void verify(MinecraftServer server) throws Exception {
        require(Boolean.getBoolean("wicklen_chronicles.serverSmoke"), "handoff fixture requires isolated smoke");
        require(LlmProviders.active() == null, "handoff regression must not have an external provider configured");
        UUID owner = UUID.randomUUID(), planId = UUID.randomUUID();
        var player = new ServerPlayer(server, server.overworld(), new GameProfile(owner, "StoryHandoffProbe"), ClientInformation.createDefault());
        var observation = new Observation(new DiscoveryKey(DiscoveryKind.CREATURE, "minecraft:pig"), "Known pig", "Confirmed fixture observation",
                new DiscoveryPosition("minecraft:overworld", 0, 70, 0), System.currentTimeMillis());
        var facts = new DiscoveryRepository().observe(owner, observation).snapshot();
        var one = new QuestDefinition("wicklen_chronicles:test/handoff/paper", 1, QuestType.COLLECT,
                "minecraft:paper", 4, new RewardDefinition("minecraft:emerald", 1));
        var two = new QuestDefinition("wicklen_chronicles:test/handoff/torch", 1, QuestType.COLLECT,
                "minecraft:torch", 4, new RewardDefinition("minecraft:emerald", 1));
        var policy = new StoryPolicy(Map.of(QuestType.COLLECT, Set.of("minecraft:paper", "minecraft:torch")),
                Set.of("minecraft:emerald"), 16, 2);
        var plan = new StoryPlan(planId, facts.worldId(), owner, facts.revision(), observation.key(), "A committed decision",
                "A task must survive the gap between two stores.", List.of(
                    new StoryChoice("records", "Prepare records", "Bring four sheets of paper.", one),
                    new StoryChoice("expedition", "Prepare light", "Bring four torches.", two)));
        var accepted = StoryTransitions.accept(StoryTransitions.empty(facts.worldId(), owner), facts, owner, plan, policy).snapshot();
        var selected = StoryTransitions.choose(accepted, facts, owner, planId, "records", policy, false, System.currentTimeMillis());

        // The story store has not been read by this isolated fixture process yet.
        // Preserve other dummy owners, then model the real durable decision-first state.
        var stories = WorldStorageContext.directory().resolve("stories.json");
        JsonObject envelope;
        if (Files.exists(stories)) {
            envelope = JsonParser.parseString(Files.readString(stories)).getAsJsonObject();
            require(envelope.get("schemaVersion").getAsInt() == 1 && envelope.get("data").isJsonObject(), "known test story schema");
        } else {
            envelope = new JsonObject();
            envelope.addProperty("schemaVersion", 1);
            envelope.add("data", new JsonObject());
        }
        envelope.getAsJsonObject("data").add(owner.toString(), new Gson().toJsonTree(selected.snapshot()));
        Files.writeString(stories, new Gson().toJson(envelope));
        require(StoryService.view(player).history().getLast().quest().id().equals(selected.quest().id()), "committed outcome fixture loaded");
        require(SpiritQuests.active(owner) == null, "quest has not yet been adopted");

        var ledger = WorldStorageContext.directory().resolve("quest-ledger.json");
        var backup = ledger.resolveSibling("quest-ledger.handoff-fixture-" + owner + ".bak");
        Files.move(ledger, backup);
        try {
            Files.createDirectory(ledger);
            require(!StoryService.recover(player), "first adoption reports its I/O failure");
            var feedback = new ArrayList<String>();
            StoryService.request(player, feedback::add);
            require(feedback.size() == 1, "failed recovery provides immediate feedback without queuing a provider");
            var blocked = StoryService.view(player);
            require(blocked.pending() == null && blocked.history().size() == 1
                    && blocked.history().getFirst().planId().equals(planId), "failed handoff cannot permit a newer plan to skip the original task");
            require(SpiritQuests.active(owner) == null, "failed adoption leaves no unpersisted active task");
        } finally {
            // Only the directory created by this fixture is removed. Original
            // quest bytes and every other owner's records are restored unchanged.
            if (Files.isDirectory(ledger)) Files.delete(ledger);
            Files.move(backup, ledger);
            Files.deleteIfExists(ledger.resolveSibling("quest-ledger.json.tmp"));
        }

        require(StoryService.recover(player), "repair allows the original handoff to finish");
        var adopted = SpiritQuests.active(owner).instance();
        require(adopted.id().equals(selected.quest().id()) && adopted.definition().equals(selected.quest().definition())
                && adopted.rewardId().equals(selected.quest().rewardId()), "exact original quest and reward identities recovered");
        require(StoryService.recover(player) && SpiritQuests.active(owner).instance().equals(adopted), "duplicate recovery cannot reroll the adopted task");
        var persisted = JsonParser.parseString(Files.readString(ledger)).getAsJsonObject().getAsJsonObject("data")
                .getAsJsonObject(owner.toString()).getAsJsonObject("active");
        require(persisted.get("id").getAsString().equals(selected.quest().id().toString()), "recovered task is durably saved");
        System.out.println("PASS: story handoff write failure blocks newer plans, repair recovers exact task and reward once");
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
