package dev.wicklen.chronicles.core.encounter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class EncounterDirectorChecks {
    public static void main(String[] args) {
        var novice = member(1, 0, 20);
        var iron = member(6, 15, 20);
        var advanced = member(12, 20, 40);
        var novicePlan = EncounterDirector.options(EncounterMode.NORMAL, List.of(novice), RecentForm.EMPTY).get(0);
        var ironPlan = EncounterDirector.options(EncounterMode.NORMAL, List.of(iron), RecentForm.EMPTY).get(0);
        require(novicePlan.totalMobs() < ironPlan.totalMobs() && novicePlan.damageMultiplier() < ironPlan.damageMultiplier(),
                "unarmored newcomers face fewer and weaker opponents than equipped players");
        require(novicePlan.waves().stream().flatMap(w -> w.mobs().stream()).noneMatch(m -> m.entityId().endsWith("skeleton")),
                "newcomers do not begin under ranged pressure");
        require(EncounterDirector.options(EncounterMode.NORMAL, List.of(iron), new RecentForm(8, 0))
                .equals(EncounterDirector.options(EncounterMode.NORMAL, List.of(iron), RecentForm.EMPTY)),
                "winning does not secretly escalate the same equipment's difficulty");
        var relieved = EncounterDirector.options(EncounterMode.NORMAL, List.of(iron), new RecentForm(0, 2)).get(0);
        require(relieved.totalMobs() < ironPlan.totalMobs() && relieved.damageMultiplier() < ironPlan.damageMultiplier(),
                "recent failures reduce both crowd size and damage");
        var mixed = EncounterDirector.options(EncounterMode.NORMAL, List.of(novice, advanced), RecentForm.EMPTY).get(0);
        require(mixed.gearTier() == 0 && mixed.damageMultiplier() == novicePlan.damageMultiplier(),
                "a strong friend cannot force a fragile player into the highest equipment tier");
        for (var gear : List.of(novice, iron, advanced)) for (var mode : EncounterMode.values()) for (int size = 1; size <= 4; size++) {
            var party = new ArrayList<CombatantSnapshot>();
            for (int i = 0; i < size; i++) party.add(new CombatantSnapshot(UUID.randomUUID(), gear.attackDamage(),
                    gear.armor(), gear.maxHealth(), true, true, false));
            for (var form : List.of(RecentForm.EMPTY, new RecentForm(0, 8), new RecentForm(8, 0)))
                for (var plan : EncounterDirector.options(mode, party, form)) {
                    require(plan.damageMultiplier() <= 0.95 && plan.healthMultiplier() <= 1.1 && plan.pauseTicks() >= 80,
                            "all equipment, party and history combinations stay inside the gentle ceiling");
                    require(plan.waves().stream().allMatch(w -> w.totalMobs() >= 1 && w.totalMobs() <= 8), "small arena crowd cap");
                }
        }
        reject(() -> new CombatantSnapshot(UUID.randomUUID(), Double.NaN, 0, 20, false, false, false));
        reject(() -> new RecentForm(Integer.MAX_VALUE, Integer.MAX_VALUE));
        reject(() -> EncounterDirector.options(EncounterMode.NORMAL, List.of(novice, novice), RecentForm.EMPTY));
        reject(() -> new EncounterPlan.MobGroup("minecraft:wither", 1));
        reject(() -> new EncounterPlan.WavePlan(List.of(new EncounterPlan.MobGroup("minecraft:zombie", 8),
                new EncounterPlan.MobGroup("minecraft:skeleton", 1)), 180));
        System.out.println("Combat director: gentle equipment ceilings, mixed parties, relief, no win escalation and bounded authored variety passed.");
    }
    private static CombatantSnapshot member(double attack, double armor, double hp) {
        return new CombatantSnapshot(UUID.randomUUID(), attack, armor, hp, false, false, false);
    }
    private static void reject(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Unsafe director input accepted");
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
