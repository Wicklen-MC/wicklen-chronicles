package dev.wicklen.chronicles.core.quest;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/**
 * Pure quest rules. The platform supplies verified observations and performs all
 * inventory/persistence effects. Returning a new state does not perform an effect.
 */
public final class QuestTransitions {
    private QuestTransitions() {}

    public enum Outcome {
        CHANGED, UNCHANGED, WRONG_OWNER, STALE_ID, NOT_READY,
        ALREADY_TURNED_IN, ALREADY_CLAIMED
    }

    public record Transition(QuestInstance quest, Outcome outcome) {
        public Transition {
            Objects.requireNonNull(quest, "quest");
            Objects.requireNonNull(outcome, "outcome");
        }

        public boolean accepted() {
            return outcome == Outcome.CHANGED;
        }
    }

    public static QuestInstance issue(UUID id, UUID owner, QuestDefinition definition, long issuedAt) {
        Objects.requireNonNull(id, "id");
        UUID rewardId = UUID.nameUUIDFromBytes((id + ":reward").getBytes(StandardCharsets.UTF_8));
        return new QuestInstance(id, owner, definition, 0, issuedAt, QuestStatus.ACTIVE, rewardId);
    }

    /** Collection is a current inventory observation, not a cumulative high score. */
    public static QuestInstance observeInventory(QuestInstance quest, int count) {
        if (count < 0) throw new IllegalArgumentException("Negative inventory count");
        if (!observing(quest) || quest.definition().type() != QuestType.COLLECT) return quest;
        return withProgress(quest, Math.min(count, quest.definition().required()));
    }

    public static QuestInstance observeVisit(QuestInstance quest, String biome) {
        Objects.requireNonNull(biome, "biome");
        if (!observing(quest) || quest.definition().type() != QuestType.VISIT
                || !quest.definition().target().equals(biome)) return quest;
        return withProgress(quest, 1);
    }

    /** Each invocation represents one server-confirmed kill. */
    public static QuestInstance observeKill(QuestInstance quest, String mob) {
        Objects.requireNonNull(mob, "mob");
        if (!observing(quest) || quest.definition().type() != QuestType.SLAY
                || !quest.definition().target().equals(mob)) return quest;
        if (quest.progress() == quest.definition().required()) return quest;
        return withProgress(quest, quest.progress() + 1);
    }

    /**
     * Proposes submission using a fresh inventory count for collection tasks.
     * The caller must execute material removal and persist the result coherently;
     * this method neither removes materials nor acknowledges reward delivery.
     */
    public static Transition turnIn(QuestInstance quest, UUID actor, UUID expectedQuestId,
                                    int availableItems) {
        Objects.requireNonNull(quest, "quest");
        if (!quest.ownerId().equals(actor)) return result(quest, Outcome.WRONG_OWNER);
        if (!quest.id().equals(expectedQuestId)) return result(quest, Outcome.STALE_ID);
        if (quest.status() == QuestStatus.REWARDED) return result(quest, Outcome.ALREADY_CLAIMED);
        if (quest.status() == QuestStatus.REWARD_PENDING) return result(quest, Outcome.ALREADY_TURNED_IN);
        QuestInstance current = quest.definition().type() == QuestType.COLLECT
                ? observeInventory(quest, availableItems) : quest;
        if (!current.readyForTurnIn()) return result(current, Outcome.NOT_READY);
        return result(withStatus(current, QuestStatus.REWARD_PENDING), Outcome.CHANGED);
    }

    /** Acknowledges a successful platform delivery; never gives an item itself. */
    public static Transition claim(QuestInstance quest, UUID actor, UUID expectedRewardId) {
        Objects.requireNonNull(quest, "quest");
        if (!quest.ownerId().equals(actor)) return result(quest, Outcome.WRONG_OWNER);
        if (!quest.rewardId().equals(expectedRewardId)) return result(quest, Outcome.STALE_ID);
        if (quest.status() == QuestStatus.REWARDED) return result(quest, Outcome.ALREADY_CLAIMED);
        if (!quest.pendingReward()) return result(quest, Outcome.NOT_READY);
        return result(withStatus(quest, QuestStatus.REWARDED), Outcome.CHANGED);
    }

    private static boolean observing(QuestInstance quest) {
        Objects.requireNonNull(quest, "quest");
        return quest.status() == QuestStatus.ACTIVE || quest.status() == QuestStatus.READY;
    }

    private static QuestInstance withProgress(QuestInstance quest, int progress) {
        if (progress == quest.progress()) return quest;
        QuestStatus status = progress >= quest.definition().required() ? QuestStatus.READY : QuestStatus.ACTIVE;
        return new QuestInstance(quest.id(), quest.ownerId(), quest.definition(), progress,
                quest.issuedAt(), status, quest.rewardId());
    }

    private static QuestInstance withStatus(QuestInstance quest, QuestStatus status) {
        return new QuestInstance(quest.id(), quest.ownerId(), quest.definition(), quest.progress(),
                quest.issuedAt(), status, quest.rewardId());
    }

    private static Transition result(QuestInstance quest, Outcome outcome) {
        return new Transition(quest, outcome);
    }
}
