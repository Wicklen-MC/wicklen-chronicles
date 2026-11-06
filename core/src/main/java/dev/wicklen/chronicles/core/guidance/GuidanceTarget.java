package dev.wicklen.chronicles.core.guidance;

import dev.wicklen.chronicles.core.discovery.*;
import java.util.*;

/** A direction marker, never a generated route or a claim that the terrain is safe. */
public record GuidanceTarget(String id, String dimension, double x, double y, double z,
                             String label, String canonicalLabel, Kind kind, boolean otherDimension, boolean directionOnly) {
    public enum Kind { PLACE, SURVEY, ENCOUNTER }
    public GuidanceTarget {
        Objects.requireNonNull(kind);
        if (id == null || id.isBlank() || id.length() > 256 || !text(label, 160) || !text(canonicalLabel, 160)
                || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || Math.abs(x) > 30_000_000
                || Math.abs(z) > 30_000_000 || Math.abs(y) > 2048 || !directionOnly) throw new IllegalArgumentException("Guidance target bounds");
        new DiscoveryPosition(dimension, 0, 0, 0);
        if (dimension.length() > 128) throw new IllegalArgumentException("Guidance dimension bounds");
    }
    private static boolean text(String value, int limit) {
        return value != null && !value.isBlank() && value.length() <= limit && value.codePoints().noneMatch(Character::isISOControl);
    }
}
