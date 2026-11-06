package dev.wicklen.chronicles.station;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.wicklen.chronicles.core.discovery.DiscoveryKey;
import dev.wicklen.chronicles.core.discovery.DiscoveryKind;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** Chunk-persistent generation result. No world lookup, I/O or LLM work on a generator thread. */
public record StationSeed(int generationVersion, int minX, int floorY, int minZ,
                          int rotation, StationVariant variant) {
    public static final Codec<StationSeed> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.intRange(1, 1).fieldOf("generation_version").forGetter(StationSeed::generationVersion),
            Codec.INT.fieldOf("min_x").forGetter(StationSeed::minX),
            Codec.INT.fieldOf("floor_y").forGetter(StationSeed::floorY),
            Codec.INT.fieldOf("min_z").forGetter(StationSeed::minZ),
            Codec.intRange(0, 3).fieldOf("rotation").forGetter(StationSeed::rotation),
            Codec.STRING.xmap(StationVariant::valueOf, StationVariant::name).fieldOf("variant").forGetter(StationSeed::variant)
    ).apply(instance, StationSeed::new));

    public StationSeed {
        Objects.requireNonNull(variant);
        if (generationVersion != 1 || rotation < 0 || rotation > 3 || minX % 16 != 0 || minZ % 16 != 0)
            throw new IllegalArgumentException("Invalid station generation result");
    }

    public PlaceManifest.Point point(int x, int y, int z) {
        return switch (rotation) {
            case 0 -> new PlaceManifest.Point(minX + x, floorY + y, minZ + z);
            case 1 -> new PlaceManifest.Point(minX + 15 - z, floorY + y, minZ + x);
            case 2 -> new PlaceManifest.Point(minX + 15 - x, floorY + y, minZ + 15 - z);
            default -> new PlaceManifest.Point(minX + z, floorY + y, minZ + 15 - x);
        };
    }

    public PlaceManifest manifest(UUID worldId, String dimension) {
        String identity = worldId + "|" + dimension + "|" + minX + "|" + floorY + "|" + minZ;
        var key = new DiscoveryKey(DiscoveryKind.PLACE, "wicklen_chronicles:station/" +
                UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)));
        PlaceManifest.Bounds arena = null;
        if (variant == StationVariant.UNDEAD_COURTYARD) {
            var a = point(2, 1, 0); var b = point(13, 6, 11);
            arena = new PlaceManifest.Bounds(Math.min(a.x(), b.x()), a.y(), Math.min(a.z(), b.z()),
                    Math.max(a.x(), b.x()), b.y(), Math.max(a.z(), b.z()));
        }
        return new PlaceManifest(worldId, key, dimension, new PlaceManifest.Point(minX, floorY, minZ),
                point(7, 1, 5), point(0, 1, 5),
                new PlaceManifest.Bounds(minX, floorY, minZ, minX + 15, floorY + 6, minZ + 15),
                variant, generationVersion, arena);
    }
}
