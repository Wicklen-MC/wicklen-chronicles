package dev.wicklen.chronicles.core.map;

import java.util.HashMap;
import java.util.UUID;

public final class MapTransitions {
    public record Change(MapRegion region, boolean changed, boolean firstVisit) {}
    public static Change record(MapRegion region, UUID actor, SurveyChunk observed) {
        if (!region.ownerId().equals(actor)) throw new IllegalArgumentException("Wrong map owner");
        var cell = observed.cell();
        if (!region.dimension().equals(cell.dimension()) || region.regionX() != cell.regionX() || region.regionZ() != cell.regionZ())
            throw new IllegalArgumentException("Wrong map region");
        var previous = region.chunks().get(cell.index());
        var value = previous == null ? observed : new SurveyChunk(cell, previous.firstVisitedAt(), observed.colors());
        if (value.equals(previous)) return new Change(region, false, false);
        var cells = new HashMap<>(region.chunks()); cells.put(cell.index(), value);
        return new Change(new MapRegion(region.worldId(), region.ownerId(), region.dimension(), region.regionX(), region.regionZ(),
                Math.incrementExact(region.revision()), cells), true, previous == null);
    }
    private MapTransitions() {}
}
