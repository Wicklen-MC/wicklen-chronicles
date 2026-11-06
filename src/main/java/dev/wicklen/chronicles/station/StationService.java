package dev.wicklen.chronicles.station;

import com.google.gson.reflect.TypeToken;
import dev.wicklen.chronicles.ai.JsonStore;
import dev.wicklen.chronicles.ai.WorldStorageContext;
import dev.wicklen.chronicles.core.discovery.DiscoveryKey;
import dev.wicklen.chronicles.core.discovery.DiscoveryPosition;
import dev.wicklen.chronicles.core.discovery.Observation;
import dev.wicklen.chronicles.exploration.ExplorationService;
import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.GenerationStep;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** Native worldgen registration and server-private manifest index. */
public final class StationService {
    private static final JsonStore<Map<String, PlaceManifest>> MANIFESTS =
            new JsonStore<>("station-manifests.json", new TypeToken<>() {});
    private static boolean registered;

    private StationService() {}

    public static void register() {
        if (registered) return;
        registered = true;
        StationAttachments.initialize();
        var id = Identifier.fromNamespaceAndPath("wicklen_chronicles", "waystation");
        Registry.register(BuiltInRegistries.FEATURE_TYPE, id, WaystationFeature.CODEC);
        BiomeModifications.addFeature(BiomeSelectors.foundInOverworld(), GenerationStep.Decoration.TOP_LAYER_MODIFICATION,
                ResourceKey.create(Registries.PLACED_FEATURE, id));
        ExplorationService.registerPlaceProvider((level, player, viewedBlock, time) -> {
            var chunk = level.getChunkSource().getChunkNow(viewedBlock.getX() >> 4, viewedBlock.getZ() >> 4);
            if (chunk == null) return Optional.empty();
            var manifest = index(level, chunk);
            if (manifest.isEmpty() || !manifest.get().bounds().contains(viewedBlock)) return Optional.empty();
            var place = manifest.get();
            var entrance = place.safeExit();
            return Optional.of(new Observation(place.key(), place.variant().title(), place.variant().summary(),
                    new DiscoveryPosition(place.dimension(), entrance.x(), entrance.y(), entrance.z()), time));
        });
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, newlyGenerated) -> {
            // C2ME may change completion threading. The immutable chunk seed is
            // enough until a later server-thread observation indexes it lazily.
            if (!level.getServer().isSameThread()) return;
            if (!WorldStorageContext.matchesSession(WorldStorageContext.sessionId())) return;
            try { index(level, chunk); }
            catch (RuntimeException error) {
                LoggerFactory.getLogger("wicklen_chronicles.station").warn(
                        "Station manifest indexing deferred; chunk geometry remains unchanged: {}", error.toString());
            }
        });
    }

    /** Only an already-discovered station in this player's current dimension can be selected. */
    public static Optional<PlaceManifest> knownStation(ServerPlayer player, DiscoveryKey key) {
        if (!player.level().getServer().isSameThread()) throw new IllegalStateException("Station lookup must run on the server thread");
        if (ExplorationService.snapshot(player).entries().stream().noneMatch(entry -> entry.key().equals(key))) return Optional.empty();
        var manifest = MANIFESTS.get(HashMap::new).get(key.id());
        if (manifest == null || !manifest.worldId().equals(WorldStorageContext.worldId())
                || !manifest.dimension().equals(player.level().dimension().identifier().toString())) return Optional.empty();
        return Optional.of(manifest);
    }

    /** Bounded combat-selector projection; never iterates personal history once per hidden manifest. */
    public static List<PlaceManifest> knownCourtyards(ServerPlayer player) {
        if (!player.level().getServer().isSameThread()) throw new IllegalStateException("Station lookup must run on the server thread");
        var keys = ExplorationService.snapshot(player).entries().stream().map(entry -> entry.key()).collect(Collectors.toSet());
        var worldId = WorldStorageContext.worldId();
        var dimension = player.level().dimension().identifier().toString();
        return MANIFESTS.get(HashMap::new).values().stream()
                .filter(place -> place.arena() != null && place.worldId().equals(worldId)
                        && place.dimension().equals(dimension) && keys.contains(place.key()))
                .sorted(Comparator.comparingDouble((PlaceManifest place) -> {
                    var point = place.anchor();
                    double dx = point.x() + 0.5 - player.getX();
                    double dy = point.y() - player.getY();
                    double dz = point.z() + 0.5 - player.getZ();
                    return dx * dx + dy * dy + dz * dz;
                }).thenComparing(place -> place.key().id()))
                .limit(32).toList();
    }

    static Optional<PlaceManifest> index(ServerLevel level, LevelChunk chunk) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Station indexing must run on the server thread");
        var seed = chunk.getAttached(StationAttachments.STATION);
        if (seed == null) return Optional.empty();
        var place = seed.manifest(WorldStorageContext.worldId(), level.dimension().identifier().toString());
        var all = MANIFESTS.get(HashMap::new);
        var previous = all.get(place.key().id());
        if (previous != null) {
            if (!previous.equals(place)) throw new IllegalStateException("Placed station manifest changed; refusing replacement");
            return Optional.of(previous);
        }
        all.put(place.key().id(), place);
        try { MANIFESTS.saveChecked(); }
        catch (IOException error) {
            all.remove(place.key().id());
            throw new UncheckedIOException(error);
        } catch (RuntimeException error) {
            all.remove(place.key().id());
            throw error;
        }
        return Optional.of(place);
    }
}
