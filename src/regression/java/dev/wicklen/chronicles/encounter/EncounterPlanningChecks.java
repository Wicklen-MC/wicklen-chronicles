package dev.wicklen.chronicles.encounter;

import dev.wicklen.chronicles.core.encounter.*;
import java.util.List;
import java.util.UUID;

public final class EncounterPlanningChecks {
    public static void main(String[] args) {
        var member = new CombatantSnapshot(UUID.randomUUID(), 6, 15, 20, true, false, false);
        var plans = EncounterDirector.options(EncounterMode.NORMAL, List.of(member), RecentForm.EMPTY);
        require(EncounterPlanningService.decode("{\"strategyId\":\"melee\"}", plans).equals(plans.get(1)),
                "model selection resolves to the exact authored snapshot");
        for (String invalid : List.of("{\"strategyId\":\"invented\"}", "{\"strategyId\":\"steady\",\"damage\":9000}",
                "{\"strategyId\":null}", "{\"strategyId\":2}", "[]", "not JSON")) {
            try { EncounterPlanningService.decode(invalid, plans); }
            catch (RuntimeException expected) { continue; }
            throw new AssertionError("Untrusted combat configuration accepted");
        }
        String context = EncounterPlanningService.context(EncounterMode.NORMAL, List.of(member), RecentForm.EMPTY, plans);
        require(!context.contains(member.playerId().toString()) && !context.contains("playerId"),
                "external provider sees equipment capability, not player identities");
        require(EncounterPlanningService.prompt().contains("Never punish victories"), "soft difficulty intent reaches the model");
        System.out.println("Combat AI boundary: strict strategy allowlist, fixed numbers and anonymous equipment context passed.");
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
