package dev.wicklen.chronicles.core.journal;

import dev.wicklen.chronicles.core.discovery.DiscoveryEntry;
import dev.wicklen.chronicles.core.discovery.DiscoverySnapshot;
import dev.wicklen.chronicles.core.quest.QuestInstance;
import dev.wicklen.chronicles.core.quest.QuestStatus;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class JournalViews {
    private JournalViews() {}

    public static JournalView create(DiscoverySnapshot snapshot, UUID actor, QuestInstance active,
                                     List<QuestInstance> history, int discoveryOffset,
                                     int historyOffset, int limit) {
        Objects.requireNonNull(snapshot, "snapshot");
        history = List.copyOf(history);
        if (!snapshot.ownerId().equals(actor)) throw new IllegalArgumentException("Journal belongs to another player");
        if (limit < 1 || limit > JournalView.MAX_PAGE_SIZE || discoveryOffset < 0 || historyOffset < 0) {
            throw new IllegalArgumentException("Invalid journal pagination");
        }
        // Validate the complete source before slicing. A foreign or unfinished
        // record must not be hidden by pagination and become visible later.
        var questIds = new HashSet<UUID>();
        var rewardIds = new HashSet<UUID>();
        if (active != null) {
            if (!active.ownerId().equals(actor) || active.status() == QuestStatus.REWARDED) {
                throw new IllegalArgumentException("Invalid active quest source");
            }
            questIds.add(active.id());
            rewardIds.add(active.rewardId());
        }
        for (var quest : history) {
            if (!quest.ownerId().equals(actor) || quest.status() != QuestStatus.REWARDED
                    || !questIds.add(quest.id()) || !rewardIds.add(quest.rewardId())) {
                throw new IllegalArgumentException("Invalid quest history source");
            }
        }
        var discoveries = snapshot.entries().stream().sorted(
                Comparator.comparingLong(DiscoveryEntry::firstObservedAt).reversed()
                        .thenComparing(entry -> entry.key().kind().name())
                        .thenComparing(entry -> entry.key().id())).toList();
        var completed = history.stream().sorted(Comparator.comparingLong(QuestInstance::issuedAt).reversed()
                .thenComparing(quest -> quest.id().toString())).toList();
        int discoveryStart = Math.min(discoveryOffset, discoveries.size());
        int historyStart = Math.min(historyOffset, completed.size());
        return new JournalView(snapshot.worldId(), snapshot.ownerId(), snapshot.revision(),
                discoveryStart, discoveries.size(), page(discoveries, discoveryStart, limit), active,
                historyStart, completed.size(), page(completed, historyStart, limit));
    }

    private static <T> List<T> page(List<T> source, int offset, int limit) {
        int end = offset + Math.min(limit, source.size() - offset);
        return source.subList(offset, end);
    }
}
