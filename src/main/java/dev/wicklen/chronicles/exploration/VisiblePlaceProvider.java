package dev.wicklen.chronicles.exploration;

import dev.wicklen.chronicles.core.discovery.Observation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * Optional server-owned place lookup. The viewed block has already passed the
 * loaded-chunk/range/raycast checks. Providers must check their saved manifest's
 * physical bounds and must not load/generate chunks or send unknown places.
 */
@FunctionalInterface
public interface VisiblePlaceProvider {
    Optional<Observation> observe(ServerLevel level, ServerPlayer player, BlockPos viewedBlock, long observedAt);
}
