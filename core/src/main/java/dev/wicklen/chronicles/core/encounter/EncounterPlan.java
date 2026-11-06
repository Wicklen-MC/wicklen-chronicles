package dev.wicklen.chronicles.core.encounter;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Frozen authored plan. Models select one of these; they never supply executable mob configuration. */
public record EncounterPlan(String strategyId, List<WavePlan> waves, double healthMultiplier,
                            double damageMultiplier, int pauseTicks, int gearTier, String adjustment) {
    private static final Set<String> STRATEGIES = Set.of("steady", "melee", "breather");
    public EncounterPlan {
        Objects.requireNonNull(strategyId); Objects.requireNonNull(adjustment);
        waves = List.copyOf(waves);
        if (!STRATEGIES.contains(strategyId) || waves.size() != 2 || gearTier < 0 || gearTier > 2
                || !Double.isFinite(healthMultiplier) || healthMultiplier < 0.65 || healthMultiplier > 1.1
                || !Double.isFinite(damageMultiplier) || damageMultiplier < 0.5 || damageMultiplier > 0.95
                || pauseTicks < 40 || pauseTicks > 160 || adjustment.length() > 160)
            throw new IllegalArgumentException("Encounter plan exceeds gentle policy");
    }

    public int totalMobs() { return waves.stream().mapToInt(WavePlan::totalMobs).sum(); }

    public record WavePlan(List<MobGroup> mobs, int timeLimitSeconds) {
        public WavePlan {
            mobs = List.copyOf(mobs);
            if (mobs.isEmpty() || mobs.size() > 2 || timeLimitSeconds < 120 || timeLimitSeconds > 240
                    || mobs.stream().mapToInt(MobGroup::count).sum() > 8
                    || mobs.stream().map(MobGroup::entityId).distinct().count() != mobs.size())
                throw new IllegalArgumentException("Wave bounds");
        }
        public int totalMobs() { return mobs.stream().mapToInt(MobGroup::count).sum(); }
    }

    public record MobGroup(String entityId, int count) {
        public MobGroup {
            if (!Set.of("minecraft:zombie", "minecraft:skeleton").contains(entityId) || count < 1 || count > 8)
                throw new IllegalArgumentException("Unsupported encounter mob");
        }
    }
}
