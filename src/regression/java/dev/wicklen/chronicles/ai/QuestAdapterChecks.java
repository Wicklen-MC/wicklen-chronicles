package dev.wicklen.chronicles.ai;

import com.google.gson.reflect.TypeToken;
import dev.wicklen.chronicles.core.quest.QuestDefinition;
import dev.wicklen.chronicles.core.quest.QuestStatus;
import dev.wicklen.chronicles.core.quest.QuestTransitions;
import dev.wicklen.chronicles.core.quest.QuestType;
import dev.wicklen.chronicles.core.quest.RewardDefinition;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Persistence/read-model checks without bootstrapping Minecraft or contacting an LLM. */
public final class QuestAdapterChecks {
    public static void main(String[] args) throws Exception {
        var directory = Files.createTempDirectory("spirit-quest-adapter-");
        var owner = UUID.randomUUID();
        var definition = new QuestDefinition("wicklen_chronicles:test/collection", 1,
            QuestType.COLLECT, "minecraft:apple", 3, new RewardDefinition("minecraft:emerald", 2));
        var original = QuestTransitions.issue(UUID.randomUUID(), owner, definition, 1234L);
        var ready = QuestTransitions.observeInventory(original, 3);
        var pending = QuestTransitions.turnIn(ready, owner, original.id(), 3).quest();
        var delivered = QuestTransitions.claim(pending, owner, pending.rewardId()).quest();
        var type = new TypeToken<Map<String, SpiritQuests.PlayerQuests>>() {};
        var store = new JsonStore<>("quests.json", type, directory);
        var data = store.get(java.util.HashMap::new);
        data.put(owner.toString(), new SpiritQuests.PlayerQuests(pending, List.of()));
        store.saveChecked();
        var reread = new JsonStore<>("quests.json", type, directory).get(java.util.HashMap::new).get(owner.toString());
        require(reread.active().equals(pending), "pending quest survives serialization");
        require(reread.active().definition().reward().equals(definition.reward()), "reward does not reroll");
        require(reread.active().id().equals(original.id()) && reread.active().rewardId().equals(original.rewardId()),
            "quest and reward identities survive serialization");
        var view = new SpiritQuests.Quest(reread.active());
        require(view.type().equals("collect") && view.target().equals("minecraft:apple"), "compatibility view preserves target");
        require(view.need() == 3 && view.progress() == 3 && view.issuedAt() == 1234L, "compatibility view preserves progress");
        require(view.status() == QuestStatus.REWARD_PENDING && view.instructions().contains("/spirit quest claim"),
            "pending reward has an explicit action");
        require(!view.instructions().contains(view.rewardId().toString()), "normal instructions do not require copying UUIDs");
        data.put(owner.toString(), new SpiritQuests.PlayerQuests(null, List.of(delivered)));
        store.saveChecked();
        var completed = new JsonStore<>("quests.json", type, directory).get(java.util.HashMap::new).get(owner.toString());
        require(completed.active() == null && completed.history().equals(List.of(delivered)), "completed history survives restart");
        require(!QuestTransitions.claim(completed.history().getFirst(), owner, pending.rewardId()).accepted(),
            "persisted history cannot authorize duplicate reward");
        boolean invalidHistoryRejected = false;
        try { new SpiritQuests.PlayerQuests(null, List.of(pending)); }
        catch (IllegalArgumentException expected) { invalidHistoryRejected = true; }
        require(invalidHistoryRejected, "unfinished quests cannot masquerade as completed history");
        boolean duplicateRejected = false;
        try { new SpiritQuests.PlayerQuests(null, List.of(delivered, delivered)); }
        catch (IllegalArgumentException expected) { duplicateRejected = true; }
        require(duplicateRejected, "duplicate persisted reward identities are rejected");

        String malformed = Files.readString(directory.resolve("quests.json"))
            .replace("\"status\":\"REWARDED\"", "\"status\":\"ACTIVE\"");
        require(malformed.contains("\"status\":\"ACTIVE\""), "invalid persisted fixture was changed");
        var corruptFile = directory.resolve("corrupt-quest.json");
        Files.writeString(corruptFile, malformed);
        var corruptStore = new JsonStore<>("corrupt-quest.json", type, directory);
        boolean badRecordRejected = false;
        try { corruptStore.get(java.util.HashMap::new); }
        catch (IllegalStateException expected) { badRecordRejected = true; }
        require(badRecordRejected, "Gson cannot deserialize inconsistent persisted status/progress");
        boolean corruptSaveRejected = false;
        try { corruptStore.saveChecked(); }
        catch (java.io.IOException expected) { corruptSaveRejected = true; }
        require(corruptSaveRejected && Files.readString(corruptFile).equals(malformed),
            "failed record loading cannot overwrite corrupted source with defaults");

        var legacySource = directory.resolve("legacy-quests.json");
        String legacyJson = new com.google.gson.Gson().toJson(Map.of(owner.toString(), Map.of(
            "type", "collect", "target", "minecraft:apple", "need", 3, "progress", 2, "issuedAt", 1234)));
        Files.writeString(legacySource, legacyJson);
        var worldId = UUID.randomUUID();
        var imported = LegacyQuestMigration.read(legacySource, worldId, List.of("minecraft:emerald"));
        require(imported.equals(LegacyQuestMigration.read(legacySource, worldId, List.of("minecraft:emerald"))),
            "legacy import identity and reward are deterministic");
        require(imported.get(owner.toString()).active().progress() == 2, "legacy progress preserved");
        require(Files.readString(legacySource).equals(legacyJson), "migration source remains unchanged");
        require(!LegacyQuestMigration.read(legacySource, UUID.randomUUID(), List.of("minecraft:emerald"))
            .get(owner.toString()).active().id().equals(imported.get(owner.toString()).active().id()),
            "legacy instance identities are scoped to world");
        Files.writeString(legacySource, "{\"schemaVersion\":999,\"data\":{}}");
        boolean futureRejected = false;
        try { LegacyQuestMigration.read(legacySource, worldId, List.of("minecraft:emerald")); }
        catch (java.io.IOException expected) { futureRejected = true; }
        require(futureRejected, "unknown legacy schema fails closed");
        var adoptionWorld = directory.resolve("story-adoption-world");
        WorldStorageContext.open(adoptionWorld);
        var adoptionDirectory = WorldStorageContext.directory();
        try {
            require(SpiritQuests.adoptIssued(owner, original), "persisted story task is adopted");
            byte[] ledgerBytes = Files.readAllBytes(adoptionDirectory.resolve("quest-ledger.json"));
            require(SpiritQuests.adoptIssued(owner, original), "story handoff replay recognizes the same active quest");
            require(java.util.Arrays.equals(ledgerBytes, Files.readAllBytes(adoptionDirectory.resolve("quest-ledger.json"))),
                "handoff replay never rewrites or rerolls the active task");
            var other = QuestTransitions.issue(UUID.randomUUID(), owner, definition, 1235L);
            require(!SpiritQuests.adoptIssued(owner, other), "story recovery cannot overwrite another active task");
        } finally { WorldStorageContext.close(); }
        var finishedLedger = new JsonStore<>("quest-ledger.json", type, adoptionDirectory);
        finishedLedger.get(java.util.HashMap::new).put(owner.toString(), new SpiritQuests.PlayerQuests(null, List.of(delivered)));
        finishedLedger.saveChecked();
        WorldStorageContext.open(adoptionWorld);
        try {
            require(SpiritQuests.adoptIssued(owner, original), "recovered decision recognizes completed quest history");
            require(SpiritQuests.active(owner) == null && SpiritQuests.history(owner).equals(List.of(delivered)),
                "recovery after restart never reactivates a completed story task");
        } finally { WorldStorageContext.close(); }
        System.out.println("Quest adapter persistence/read-model checks passed.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
