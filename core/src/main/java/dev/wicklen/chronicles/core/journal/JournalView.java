package dev.wicklen.chronicles.core.journal;

import dev.wicklen.chronicles.core.discovery.DiscoveryEntry;
import dev.wicklen.chronicles.core.quest.QuestInstance;
import dev.wicklen.chronicles.core.quest.QuestStatus;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Bounded personal read model. It contains no catalogue of undiscovered places. */
public record JournalView(UUID worldId, UUID ownerId, long discoveryRevision,
                          int discoveryOffset, int totalDiscoveries, List<DiscoveryEntry> discoveries,
                          QuestInstance activeQuest, int historyOffset, int totalHistory,
                          List<QuestInstance> history) {
    public static final int MAX_PAGE_SIZE = 64;

    public JournalView {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(ownerId, "ownerId");
        discoveries = List.copyOf(discoveries);
        history = List.copyOf(history);
        if (discoveryRevision < 0) throw new IllegalArgumentException("Negative discovery revision");
        validatePage(discoveryOffset, totalDiscoveries, discoveries.size());
        validatePage(historyOffset, totalHistory, history.size());
        var keys = new HashSet<>();
        for (var discovery : discoveries) {
            if (!keys.add(discovery.key())) throw new IllegalArgumentException("Duplicate journal discovery");
        }
        var questIds = new HashSet<UUID>();
        var rewardIds = new HashSet<UUID>();
        if (activeQuest != null) {
            if (!activeQuest.ownerId().equals(ownerId) || activeQuest.status() == QuestStatus.REWARDED) {
                throw new IllegalArgumentException("Invalid active journal quest");
            }
            questIds.add(activeQuest.id());
            rewardIds.add(activeQuest.rewardId());
        }
        for (var quest : history) {
            if (!quest.ownerId().equals(ownerId) || quest.status() != QuestStatus.REWARDED
                    || !questIds.add(quest.id()) || !rewardIds.add(quest.rewardId())) {
                throw new IllegalArgumentException("Invalid journal quest history");
            }
        }
    }

    public boolean moreDiscoveries() {
        return (long) discoveryOffset + discoveries.size() < totalDiscoveries;
    }

    public boolean moreHistory() {
        return (long) historyOffset + history.size() < totalHistory;
    }

    private static void validatePage(int offset, int total, int count) {
        if (offset < 0 || total < 0 || offset > total || count > MAX_PAGE_SIZE || (long) offset + count > total) {
            throw new IllegalArgumentException("Invalid journal page bounds");
        }
    }
}
