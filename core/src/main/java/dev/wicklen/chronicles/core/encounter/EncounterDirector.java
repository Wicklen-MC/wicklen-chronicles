package dev.wicklen.chronicles.core.encounter;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** A forgiving equipment ceiling with authored variety, not an escalating skill tax. */
public final class EncounterDirector {
    private EncounterDirector() {}

    public static List<EncounterPlan> options(EncounterMode mode, List<CombatantSnapshot> party, RecentForm recent) {
        Objects.requireNonNull(mode); Objects.requireNonNull(recent);
        party = List.copyOf(party);
        if (party.isEmpty() || party.size() > 4 || party.stream().map(CombatantSnapshot::playerId).distinct().count() != party.size())
            throw new IllegalArgumentException("Party must contain one to four distinct players");
        // The least equipped participant sets the ceiling, so mixed parties can play together.
        int tier = party.stream().mapToInt(EncounterDirector::gearTier).min().orElse(0);
        boolean struggling = recent.failures() > 0 && recent.failures() >= recent.successes();
        int base = 2 + tier + Math.min(4, (party.size() - 1) * 2);
        if (struggling) base--;
        boolean empowered = mode == EncounterMode.EMPOWERED;
        if (empowered) base++;
        base = Math.max(1, Math.min(8, base));
        double health = Math.min(1.1, 0.8 + tier * 0.125 + (empowered ? 0.05 : 0) - (struggling ? 0.05 : 0));
        double damage = Math.min(0.95, 0.65 + tier * 0.125 + (empowered ? 0.05 : 0) - (struggling ? 0.05 : 0));
        String adjustment = "gear_tier_" + tier + (struggling ? ";recent_struggles" : ";equipment_only");
        var options = new ArrayList<EncounterPlan>();
        for (String strategy : List.of("steady", "melee", "breather")) {
            int count = Math.max(1, base - (strategy.equals("breather") ? 1 : 0));
            int ranged = strategy.equals("melee") || tier == 0 ? 0 : Math.min(count - 1, Math.min(party.size(), Math.max(1, count / 3)));
            var groups = new ArrayList<EncounterPlan.MobGroup>();
            groups.add(new EncounterPlan.MobGroup("minecraft:zombie", count - ranged));
            if (ranged > 0) groups.add(new EncounterPlan.MobGroup("minecraft:skeleton", ranged));
            var wave = new EncounterPlan.WavePlan(groups, 180);
            options.add(new EncounterPlan(strategy, List.of(wave, wave), health, damage,
                    strategy.equals("breather") ? 120 : 80, tier, adjustment));
        }
        return List.copyOf(options);
    }

    private static int gearTier(CombatantSnapshot member) {
        double offense = Math.max(member.attackDamage(), member.ranged() ? 5 : 0);
        if (offense >= 8 && member.armor() >= 16 || member.maxHealth() >= 30 && member.armor() >= 12 && offense >= 6) return 2;
        if (offense >= 5 && member.armor() >= 10) return 1;
        return 0;
    }
}
