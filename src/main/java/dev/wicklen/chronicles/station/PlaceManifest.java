package dev.wicklen.chronicles.station;

import dev.wicklen.chronicles.core.discovery.DiscoveryKey;
import dev.wicklen.chronicles.core.discovery.DiscoveryKind;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.Objects;
import java.util.UUID;

/** Stable server-owned identity/layout, independent from mutable blocks and personal knowledge. */
public record PlaceManifest(UUID worldId, DiscoveryKey key, String dimension,
                            Point start, Point anchor, Point safeExit, Bounds bounds,
                            StationVariant variant, int generationVersion, Bounds arena) {
    public PlaceManifest {
        Objects.requireNonNull(worldId); Objects.requireNonNull(key); Objects.requireNonNull(dimension);
        Objects.requireNonNull(start); Objects.requireNonNull(anchor); Objects.requireNonNull(safeExit);
        Objects.requireNonNull(bounds); Objects.requireNonNull(variant);
        if (key.kind() != DiscoveryKind.PLACE || generationVersion != 1
                || !bounds.contains(anchor) || !bounds.contains(safeExit))
            throw new IllegalArgumentException("Invalid station manifest");
        if ((variant == StationVariant.UNDEAD_COURTYARD) != (arena != null))
            throw new IllegalArgumentException("Only the courtyard variant defines a combat arena");
        if (arena != null && (arena.width() != 12 || arena.depth() != 12
                || !bounds.contains(new Point(arena.minX, arena.minY, arena.minZ))
                || !bounds.contains(new Point(arena.maxX, arena.maxY, arena.maxZ))))
            throw new IllegalArgumentException("Station arena must be a contained 12x12 area");
    }

    public record Point(int x, int y, int z) {
        public BlockPos blockPos() { return new BlockPos(x, y, z); }
    }

    public record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        public Bounds {
            if (minX > maxX || minY > maxY || minZ > maxZ) throw new IllegalArgumentException("Inverted station bounds");
        }
        public int width() { return maxX - minX + 1; }
        public int depth() { return maxZ - minZ + 1; }
        public boolean contains(Point point) {
            return point.x >= minX && point.x <= maxX && point.y >= minY && point.y <= maxY && point.z >= minZ && point.z <= maxZ;
        }
        public boolean contains(BlockPos point) { return contains(new Point(point.getX(), point.getY(), point.getZ())); }
        public BoundingBox box() { return new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ); }
    }
}
