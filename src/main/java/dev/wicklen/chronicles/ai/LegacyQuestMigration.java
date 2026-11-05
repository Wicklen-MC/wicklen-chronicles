package dev.wicklen.chronicles.ai;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import dev.wicklen.chronicles.core.quest.QuestDefinition;
import dev.wicklen.chronicles.core.quest.QuestTransitions;
import dev.wicklen.chronicles.core.quest.QuestType;
import dev.wicklen.chronicles.core.quest.RewardDefinition;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Explicit format conversion for a legacy file already assigned to one world. Never writes its source. */
final class LegacyQuestMigration {
    private record LegacyQuest(String type, String target, int need, int progress, long issuedAt) {}

    static Map<String, SpiritQuests.PlayerQuests> read(Path source, UUID worldId, List<String> rewards) throws IOException {
        try {
            var root = JsonParser.parseString(Files.readString(source));
            if (root.isJsonObject() && root.getAsJsonObject().has("schemaVersion")) {
                var envelope = root.getAsJsonObject();
                JsonStore.requireSchema(envelope, 1);
                root = envelope.get("data");
            }
            if (root == null || !root.isJsonObject()) throw new IOException("Legacy quests must be an object");
            Map<String, LegacyQuest> old = new Gson().fromJson(root, new TypeToken<Map<String, LegacyQuest>>() {}.getType());
            var converted = new HashMap<String, SpiritQuests.PlayerQuests>();
            for (var entry : old.entrySet()) {
                UUID owner = UUID.fromString(entry.getKey());
                var value = entry.getValue();
                if (value == null || value.progress() < 0 || value.progress() > value.need())
                    throw new IOException("Invalid legacy quest progress");
                QuestType type = QuestType.valueOf(value.type().toUpperCase(java.util.Locale.ROOT));
                UUID id = UUID.nameUUIDFromBytes((worldId + ":" + owner + ":" + value.issuedAt()
                    + ":" + value.type() + ":" + value.target()).getBytes(StandardCharsets.UTF_8));
                var reward = new RewardDefinition(rewards.get(Math.floorMod(id.hashCode(), rewards.size())), 2);
                var definition = new QuestDefinition("redzspirit:legacy/" + value.type(), 1,
                    type, value.target(), value.need(), reward);
                var quest = QuestTransitions.issue(id, owner, definition, value.issuedAt());
                quest = switch (type) {
                    case COLLECT -> QuestTransitions.observeInventory(quest, value.progress());
                    case VISIT -> value.progress() == 0 ? quest : QuestTransitions.observeVisit(quest, value.target());
                    case SLAY -> new dev.wicklen.chronicles.core.quest.QuestInstance(quest.id(), quest.ownerId(), quest.definition(),
                        value.progress(), quest.issuedAt(), value.progress() >= value.need()
                            ? dev.wicklen.chronicles.core.quest.QuestStatus.READY : dev.wicklen.chronicles.core.quest.QuestStatus.ACTIVE, quest.rewardId());
                };
                converted.put(owner.toString(), new SpiritQuests.PlayerQuests(quest, List.of()));
            }
            return converted;
        } catch (RuntimeException e) {
            throw new IOException("Invalid legacy quests; source preserved", e);
        }
    }

    private LegacyQuestMigration() {}
}
