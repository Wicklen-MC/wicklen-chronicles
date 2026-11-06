package dev.wicklen.chronicles.exploration;

import dev.wicklen.chronicles.core.discovery.DiscoveryKey;
import dev.wicklen.chronicles.core.discovery.DiscoveryKind;
import dev.wicklen.chronicles.core.discovery.DiscoveryPosition;
import dev.wicklen.chronicles.core.discovery.Observation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import java.util.Locale;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Observes registered vanilla and modded structures from loaded starts and a visible built surface. */
final class StructurePlaceObserver implements VisiblePlaceProvider {
    private static final Map<String, String> TITLES = Map.ofEntries(
            Map.entry("desert_pyramid", "Пустынный храм"), Map.entry("jungle_pyramid", "Храм джунглей"),
            Map.entry("pillager_outpost", "Аванпост разбойников"), Map.entry("mansion", "Лесной особняк"),
            Map.entry("monument", "Океанский монумент"), Map.entry("ocean_ruin_cold", "Подводные руины"),
            Map.entry("ocean_ruin_warm", "Подводные руины"), Map.entry("shipwreck", "Затонувший корабль"),
            Map.entry("shipwreck_beached", "Корабль на берегу"), Map.entry("ancient_city", "Древний город"),
            Map.entry("stronghold", "Крепость"), Map.entry("mineshaft", "Заброшенная шахта"),
            Map.entry("mineshaft_mesa", "Заброшенная шахта"), Map.entry("trial_chambers", "Камеры испытаний"),
            Map.entry("fortress", "Крепость Незера"), Map.entry("bastion_remnant", "Развалины бастиона"),
            Map.entry("end_city", "Город Края"), Map.entry("igloo", "Иглу"),
            Map.entry("swamp_hut", "Болотная хижина"));

    @Override public Optional<Observation> observe(ServerLevel level, ServerPlayer player, BlockPos viewedBlock, long time) {
        var chunk = level.getChunkSource().getChunkNow(viewedBlock.getX() >> 4, viewedBlock.getZ() >> 4);
        if (chunk == null) return Optional.empty();
        var registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        var candidates = new ArrayList<StructureStart>();
        var seen = new HashSet<StructureStart>();
        for (var start : chunk.getAllStarts().values()) {
            if (start != null && start.isValid() && start.getStructure() != null && seen.add(start)) candidates.add(start);
        }
        int checked = 0;
        for (var entry : chunk.getAllReferences().entrySet()) {

            for (long reference : entry.getValue()) {
                if (++checked > 64) break;
                var origin = ChunkPos.unpack(reference);
                var loaded = level.getChunkSource().getChunkNow(origin.x(), origin.z());
                if (loaded == null) continue;
                var start = loaded.getStartForStructure(entry.getKey());
                if (start != null && start.isValid() && start.getStructure() != null && seen.add(start)) candidates.add(start);
            }
            if (checked > 64) break;
        }
        candidates.sort(Comparator.comparing(start -> String.valueOf(registry.getKey(start.getStructure()))));
        for (var start : candidates) {
            if (!start.isValid()) continue;
            var id = registry.getKey(start.getStructure());
            String name = title(id);
            if (name == null || start.getPieces().stream().noneMatch(piece -> piece.getBoundingBox().isInside(viewedBlock))) continue;
            if (!id.getNamespace().equals("minecraft") && !builtSurface(chunk.getBlockState(viewedBlock))) continue;
            String dimension = level.dimension().identifier().toString();
            String stable = dimension + "|" + id + "|" + start.getChunkPos().x() + "|" + start.getChunkPos().z();
            var key = new DiscoveryKey(DiscoveryKind.PLACE, "wicklen_chronicles:place/" +
                    UUID.nameUUIDFromBytes(stable.getBytes(StandardCharsets.UTF_8)));
            return Optional.of(new Observation(key, name,
                    "journal.wicklen_chronicles.structure.recorded_summary",
                    new DiscoveryPosition(dimension, viewedBlock.getX(), viewedBlock.getY(), viewedBlock.getZ()), time));
        }
        return Optional.empty();
    }

    /** Ordinary terrain inside a large piece's box is not sufficient evidence of seeing its building. */
    static boolean builtSurface(net.minecraft.world.level.block.state.BlockState state) {
        return !state.isAir() && state.getFluidState().isEmpty() && !state.canBeReplaced()
                && !state.is(BlockTags.DIRT) && !state.is(BlockTags.BASE_STONE_OVERWORLD)
                && !state.is(BlockTags.BASE_STONE_NETHER) && !state.is(BlockTags.SAND)
                && !state.is(BlockTags.LEAVES) && !state.is(BlockTags.LOGS)
                && !state.is(Blocks.END_STONE) && !state.is(Blocks.GRAVEL)
                && !state.is(Blocks.SNOW_BLOCK) && !state.is(Blocks.ICE) && !state.is(Blocks.PACKED_ICE);
    }

    private static String readable(String value) {
        var words = value.replace('_', ' ').replace('/', ' ').replace('-', ' ').split(" +");
        var result = new StringBuilder();
        for (var word : words) {
            if (word.isEmpty()) continue;
            if (!result.isEmpty()) result.append(' ');
            result.append(word.substring(0, 1).toUpperCase(Locale.ROOT)).append(word.substring(1));
        }
        return result.toString();
    }

    private static String title(net.minecraft.resources.Identifier id) {
        if (id == null) return null;
        if (id.getNamespace().equals("minecraft")) {
            if (id.getPath().startsWith("village_")) return "Деревня";
            if (id.getPath().equals("ruined_portal") || id.getPath().startsWith("ruined_portal_")) return "Разрушенный портал";
            if (TITLES.containsKey(id.getPath())) return TITLES.get(id.getPath());
        }
        String name = readable(id.getPath());
        if (!id.getNamespace().equals("minecraft")) name += " · " + readable(id.getNamespace());
        return name.length() > 160 ? name.substring(0, 160) : name;
    }
}
