package dev.wicklen.chronicles.story;

import dev.wicklen.chronicles.core.discovery.*;
import dev.wicklen.chronicles.core.quest.*;
import java.util.List;
import java.util.UUID;

/** The JSON boundary may only select real server-authored actions. */
public final class StoryPlansChecks {
    public static void main(String[] args) {
        UUID owner = UUID.randomUUID(), world = UUID.randomUUID(), planId = UUID.randomUUID();
        var observation = new Observation(new DiscoveryKey(DiscoveryKind.CREATURE, "minecraft:zombie"),
            "Zombie", "Observed with a spyglass", new DiscoveryPosition("minecraft:overworld", 1, 70, 1), 1);
        var facts = DiscoveryTransitions.observe(DiscoveryTransitions.empty(world, owner), owner, observation, null).snapshot();
        var source = facts.entries().getFirst();
        var reward = new RewardDefinition("minecraft:emerald", 1);
        var paper = new QuestDefinition("wicklen_chronicles:test/paper", 1, QuestType.COLLECT, "minecraft:paper", 4, reward);
        var fight = new QuestDefinition("wicklen_chronicles:test/defend", 1, QuestType.SLAY, "minecraft:zombie", 3, reward);
        var goals = List.of(new StoryPlans.Goal("supplies", "Bring paper", paper), new StoryPlans.Goal("defend", "Defeat zombies", fight));
        String response = """
            {"title":"A field report","motive":"Record the creature or prepare a patrol.","choices":[
              {"id":"records","label":"Prepare notes","consequencePreview":"Bring paper for a field report.","goalId":"supplies"},
              {"id":"patrol","label":"Join the patrol","consequencePreview":"Defeat three observed zombies.","goalId":"defend"}]}
            """;
        var plan = StoryPlans.decode(response, planId, facts, source, goals);
        require(plan.worldId().equals(world) && plan.ownerId().equals(owner) && plan.source().equals(source.key()),
            "model cannot select a different owner, world or secret source");
        require(plan.choices().getFirst().quest().equals(paper) && plan.choices().getLast().quest().equals(fight),
            "goal IDs resolve to exact server definitions and rewards");
        reject(response.replace("\"supplies\"", "\"invented_goal\""), planId, facts, source, goals);
        reject(response.replace("\"goalId\":\"supplies\"", "\"goalId\":\"supplies\",\"command\":\"give @s diamond 64\""), planId, facts, source, goals);
        reject(response.replace("\"goalId\":\"defend\"", "\"goalId\":\"supplies\""), planId, facts, source, goals);
        reject(response.replace("\"id\":\"patrol\"", "\"id\":\"records\""), planId, facts, source, goals);
        require(StoryPlans.decode("```json\n" + response + "\n```", planId, facts, source, goals).equals(plan),
            "a JSON markdown fence does not change validated meaning");
        System.out.println("Story JSON boundary: allowed goals, strict fields, distinct choices and fixed rewards passed.");
    }

    private static void reject(String json, UUID id, DiscoverySnapshot facts, DiscoveryEntry source, List<StoryPlans.Goal> goals) {
        try { StoryPlans.decode(json, id, facts, source, goals); }
        catch (RuntimeException expected) { return; }
        throw new AssertionError("Invalid model plan was accepted");
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
