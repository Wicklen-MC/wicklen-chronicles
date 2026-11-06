package dev.wicklen.chronicles.core.map;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** A bounded personal region; its 16x16 survey cells contain no unknown terrain. */
public record MapRegion(UUID worldId, UUID ownerId, String dimension, int regionX, int regionZ,
                        long revision, Map<Integer, SurveyChunk> chunks) {
    public MapRegion {
        Objects.requireNonNull(worldId); Objects.requireNonNull(ownerId);
        int minimumRegion = Math.floorDiv(-MapCell.LIMIT, 16), maximumRegion = Math.floorDiv(MapCell.LIMIT, 16);
        if (regionX < minimumRegion || regionZ < minimumRegion || regionX > maximumRegion || regionZ > maximumRegion)
            throw new IllegalArgumentException("Map region outside world bounds");
        new MapCell(dimension, Math.max(-MapCell.LIMIT, Math.min(MapCell.LIMIT, regionX * 16)),
                Math.max(-MapCell.LIMIT, Math.min(MapCell.LIMIT, regionZ * 16)));
        if (revision < 0 || chunks == null || chunks.size() > 256) throw new IllegalArgumentException("Map region bounds");
        chunks = Map.copyOf(chunks);
        for (var entry : chunks.entrySet()) {
            var cell = entry.getValue().cell();
            if (!cell.dimension().equals(dimension) || cell.regionX() != regionX || cell.regionZ() != regionZ
                    || entry.getKey() != cell.index()) throw new IllegalArgumentException("Foreign map cell in region");
        }
    }
    public static MapRegion empty(UUID world, UUID owner, MapCell cell) {
        return new MapRegion(world, owner, cell.dimension(), cell.regionX(), cell.regionZ(), 0, Map.of());
    }
}
