package dev.wicklen.chronicles.core.encounter;

import java.util.Objects;
import java.util.UUID;

/** Adapter-supplied effective equipment attributes, independent of Minecraft's item APIs. */
public record CombatantSnapshot(UUID playerId, double attackDamage, double armor, double maxHealth,
                                boolean shield, boolean ranged, boolean debug) {
    public CombatantSnapshot {
        Objects.requireNonNull(playerId);
        if (!Double.isFinite(attackDamage) || attackDamage < 0 || attackDamage > 100_000
                || !Double.isFinite(armor) || armor < 0 || armor > 100_000
                || !Double.isFinite(maxHealth) || maxHealth <= 0 || maxHealth > 100_000)
            throw new IllegalArgumentException("Invalid combatant attributes");
    }
}
