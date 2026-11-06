package dev.wicklen.chronicles.core.guidance;

import dev.wicklen.chronicles.core.discovery.*;
import dev.wicklen.chronicles.core.field.FirstLightState;

import java.util.List;
import java.util.UUID;

public final class GuidanceRegressionChecks {
    public static void main(String[] args) {
        UUID world = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        var initial = GuidanceIntent.initial(world, owner);
        require(initial.enabled() && initial.mode() == GuidanceIntent.Mode.FOLLOW_EXPEDITION, "default follows an existing expedition");
        var disabled = GuidanceTransitions.clear(initial, owner, world);
        require(!disabled.enabled() && disabled.revision() == 1, "clear explicitly disables the automatic target");
        require(GuidanceTransitions.clear(disabled, owner, world) == disabled, "repeated clear is idempotent");
        require(disabled.mode() == GuidanceIntent.Mode.DISABLED, "reading or a new fact cannot mutate a disabled immutable intention");
        var place = entry(DiscoveryKind.PLACE, "wicklen_chronicles:place/known", "minecraft:overworld");
        var creature = entry(DiscoveryKind.CREATURE, "minecraft:cow", "minecraft:overworld");
        var facts = new DiscoverySnapshot(world, owner, 2, List.of(place, creature));
        var pinned = GuidanceTransitions.pin(disabled, owner, world, facts, "minecraft:overworld", place.key().id());
        require(pinned.enabled() && pinned.placeId().equals(place.key().id()), "only the known canonical place ID is persisted");
        rejects(() -> GuidanceTransitions.pin(pinned, owner, world, facts, "minecraft:overworld", "minecraft:cow"), "species cannot become a place target");
        rejects(() -> GuidanceTransitions.pin(pinned, owner, world, facts, "minecraft:overworld", "wicklen_chronicles:place/hidden"), "unknown places cannot be pinned");
        rejects(() -> GuidanceTransitions.pin(pinned, owner, world, facts, "minecraft:the_nether", place.key().id()), "pin request cannot select another dimension");
        rejects(() -> GuidanceTransitions.clear(pinned, UUID.randomUUID(), world), "another owner cannot disable guidance");
        rejects(() -> GuidanceTransitions.follow(pinned, owner, UUID.randomUUID()), "another world cannot enable guidance");
        var otherFacts = new DiscoverySnapshot(world, UUID.randomUUID(), 1, List.of(place));
        rejects(() -> GuidanceTransitions.pin(pinned, owner, world, otherFacts, "minecraft:overworld", place.key().id()), "another player's confirmed place is not evidence");
        require(GuidanceTransitions.follow(pinned, owner, world).placeId().isEmpty(), "explicit expedition following replaces a pinned place");
        navigation();
        survey();
        rejects(() -> new GuidanceTarget("test", "minecraft:overworld", Double.NaN, 70, 0, "Known", "Known", GuidanceTarget.Kind.PLACE, false, true), "non-finite targets cannot cross a wire");
        System.out.println("PASS: guidance explicit opt-out, known-place privacy, world/owner/dimension gates, compass geometry and authored survey bounds");
    }

    private static void navigation() {
        var south = GuidanceGeometry.navigation(0, 0, 0, 0, 10);
        require(south.horizontalDistance() == 10 && south.bearingDegrees() == 180 && south.relativeDegrees() == 0, "Minecraft yaw zero points south");
        require(GuidanceGeometry.navigation(0, 0, 0, 10, 0).relativeDegrees() == -90, "east is left when facing south");
        require(GuidanceGeometry.navigation(0, 0, 90, -10, 0).relativeDegrees() == 0, "positive yaw faces west");
        require(GuidanceGeometry.navigation(0, 0, -90, 10, 0).relativeDegrees() == 0, "negative yaw faces east");
        require(GuidanceGeometry.navigation(0, 0, 180, 0, -10).relativeDegrees() == 0, "north wraps correctly");
        require(GuidanceGeometry.navigation(10, 10, 789, 10, 10).relativeDegrees() == 0, "arrival has no arbitrary spinning bearing");
        rejects(() -> GuidanceGeometry.navigation(0, Double.NaN, 0, 0, 0), "non-finite geometry is rejected");
    }

    private static void survey() {
        var area = new FirstLightState.Area("minecraft:overworld", 32, -96, 224, 96);
        var source = new DiscoveryPosition("minecraft:overworld", 0, 70, 0);
        var inside = GuidanceGeometry.survey(area, source, List.of(), 100, 10);
        require(inside.x() == 100 && inside.z() == 10, "inside the field area means ready to observe, not a fictional structure");
        for (int x : new int[]{32, 128, 224}) {
            for (int z : new int[]{-96, 0, 96}) {
                var first = new DiscoveryPosition("minecraft:overworld", x, 70, z);
                var next = GuidanceGeometry.survey(area, source, List.of(first), x, z);
                require(next.x() >= area.minX() && next.x() <= area.maxX() && next.z() >= area.minZ() && next.z() <= area.maxZ(), "next field direction remains inside the proposed area");
                require(Math.hypot(next.x() - x, next.z() - z) >= 48, "next field point is separated from the previous mark");
                require(Math.hypot(next.x(), next.z()) >= 32, "field direction does not lead back to the original source");
            }
        }
    }

    private static DiscoveryEntry entry(DiscoveryKind kind, String id, String dimension) {
        return new DiscoveryEntry(new DiscoveryKey(kind, id), "Known record", "Observed personally",
                new DiscoveryPosition(dimension, 10, 70, 20), 1, null);
    }

    private static void rejects(Runnable action, String message) {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (IllegalArgumentException expected) {
            // Expected boundary rejection.
        }
    }

    private static void require(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }
}
