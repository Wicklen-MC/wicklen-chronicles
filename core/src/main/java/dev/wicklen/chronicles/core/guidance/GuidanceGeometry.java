package dev.wicklen.chronicles.core.guidance;

import dev.wicklen.chronicles.core.discovery.DiscoveryPosition;
import dev.wicklen.chronicles.core.field.FirstLightState;
import java.util.*;

/** Java 17 geometry. Bearings are clockwise from north; Minecraft yaw 0 faces south. */
public final class GuidanceGeometry {
    public record Vector(double horizontalDistance, double bearingDegrees, double relativeDegrees) {}
    public record Point(double x, double z) {}
    public static Vector navigation(double playerX, double playerZ, double yaw, double targetX, double targetZ) {
        finite(playerX, playerZ, yaw, targetX, targetZ);
        double dx = targetX - playerX, dz = targetZ - playerZ, distance = Math.hypot(dx, dz);
        double heading = normalize(yaw + 180);
        double bearing = distance < 0.001 ? heading : normalize(Math.toDegrees(Math.atan2(dx, -dz)));
        return new Vector(distance, bearing, wrap(bearing - heading));
    }
    /** Aim at the broad authored area / a separated field point; no terrain lookup or supposed hidden structure. */
    public static Point survey(FirstLightState.Area area, DiscoveryPosition source, List<DiscoveryPosition> marks,
                               double playerX, double playerZ) {
        finite(playerX, playerZ);
        if (marks.size() > 1 || !area.dimension().equals(source.dimension())) throw new IllegalArgumentException("Survey guidance scope");
        if (inside(area, playerX, playerZ) && distanceSq(playerX, playerZ, source.x(), source.z()) >= 32 * 32
                && marks.stream().allMatch(mark -> distanceSq(playerX, playerZ, mark.x(), mark.z()) >= 48 * 48))
            return new Point(playerX, playerZ); // inside the eligible field area: the action belongs in the journal
        if (marks.isEmpty()) return new Point(clamp(playerX, area.minX() + 12, area.maxX() - 12), clamp(playerZ, area.minZ() + 12, area.maxZ() - 12));
        var first = marks.get(0);
        if (!area.contains(first)) throw new IllegalArgumentException("Foreign field mark");
        double cx = ((double) area.minX() + area.maxX()) / 2, cz = ((double) area.minZ() + area.maxZ()) / 2;
        Point best = null; double score = Double.POSITIVE_INFINITY;
        for (int i = 0; i < 8; i++) {
            double angle = i * Math.PI / 4;
            double x = clamp(first.x() + Math.cos(angle) * 64, area.minX() + 12, area.maxX() - 12);
            double z = clamp(first.z() + Math.sin(angle) * 64, area.minZ() + 12, area.maxZ() - 12);
            if (distanceSq(x, z, first.x(), first.z()) < 48 * 48 || distanceSq(x, z, source.x(), source.z()) < 32 * 32) continue;
            double candidate = distanceSq(x, z, cx, cz);
            if (candidate < score) { score = candidate; best = new Point(x, z); }
        }
        if (best == null) throw new IllegalArgumentException("No separated survey point within the authored area");
        return best;
    }
    private static boolean inside(FirstLightState.Area area, double x, double z) { return x >= area.minX() && x <= area.maxX() && z >= area.minZ() && z <= area.maxZ(); }
    private static double distanceSq(double x, double z, double bx, double bz) { double dx = x - bx, dz = z - bz; return dx * dx + dz * dz; }
    private static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
    private static double normalize(double angle) { return (angle % 360 + 360) % 360; }
    private static double wrap(double angle) { return (angle + 540) % 360 - 180; }
    private static void finite(double... values) { for (double value : values) if (!Double.isFinite(value)) throw new IllegalArgumentException("Non-finite navigation geometry"); }
    private GuidanceGeometry() {}
}
