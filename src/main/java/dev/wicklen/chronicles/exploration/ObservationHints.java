package dev.wicklen.chronicles.exploration;

import dev.wicklen.chronicles.core.discovery.Observation;
import dev.wicklen.chronicles.station.PlaceManifest;
import dev.wicklen.chronicles.station.StationService;
import dev.wicklen.chronicles.station.StationVariant;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Private native feedback, based on actual visible surfaces rather than hidden map markers. */
final class ObservationHints {
    static final double NEARBY_DISTANCE = 40;
    private final Map<UUID, Long> preserveUntil = new HashMap<>();
    private final Map<UUID, Long> nearbyNoticeAfter = new HashMap<>();

    void remove(UUID player) { preserveUntil.remove(player); nearbyNoticeAfter.remove(player); }

    void scan(ServerPlayer player, Observation observation, Mob mob, Vec3 surface, int percent, boolean known, long tick) {
        if (tick % 10 == 0) sparkle(player, surface, known);
        if (tick < preserveUntil.getOrDefault(player.getUUID(), 0L)) return;
        if (percent > 0 && percent <= 3 || tick % 10 == 0) {
            player.sendOverlayMessage(Component.translatable(known
                            ? "journal.wicklen_chronicles.scan.known" : "journal.wicklen_chronicles.scan.focus",
                    title(player, observation, mob), percent).withStyle(known ? ChatFormatting.GREEN : ChatFormatting.GOLD));
        }
    }

    void invalid(ServerPlayer player, long tick) {
        if (tick % 30 == 0 && tick >= preserveUntil.getOrDefault(player.getUUID(), 0L))
            player.sendOverlayMessage(Component.translatable("journal.wicklen_chronicles.scan.no_target")
                    .withStyle(ChatFormatting.GRAY));
    }

    void recorded(ServerPlayer player, Observation observation, Mob mob, Vec3 surface, long tick) {
        preserveUntil.put(player.getUUID(), tick + 50);
        sparkle(player, surface, true);
        player.sendOverlayMessage(Component.translatable("journal.wicklen_chronicles.scan.recorded", title(player, observation, mob))
                .withStyle(ChatFormatting.GREEN));
    }

    void nearby(ServerPlayer player, long tick) {
        if (tick % 20 != 0 || !player.isAlive() || player.isSpectator()) return;
        for (var place : StationService.nearbyLoaded(player, NEARBY_DISTANCE)) {
            var visible = visibleSurface(player, place);
            if (visible.isEmpty()) continue;
            boolean known = ExplorationService.snapshot(player).entries().stream().anyMatch(entry -> entry.key().equals(place.key()));
            available(player, stationTitle(place.variant()), visible.get(), known, tick);
            return; // one nearby, visible place; never a list of hidden coordinates
        }
    }

    void available(ServerPlayer player, Component title, Vec3 surface, boolean known, long tick) {
        sparkle(player, surface, known);
        if (!known && tick >= nearbyNoticeAfter.getOrDefault(player.getUUID(), 0L)
                && tick >= preserveUntil.getOrDefault(player.getUUID(), 0L)) {
            nearbyNoticeAfter.put(player.getUUID(), tick + 100);
            player.sendOverlayMessage(Component.translatable("journal.wicklen_chronicles.scan.nearby", title)
                    .withStyle(ChatFormatting.GOLD));
        }
    }

    /** At most nine loaded-only rays. A wall, an unloaded chunk or an empty ruin hides the hint. */
    static Optional<Vec3> visibleSurface(ServerPlayer player, PlaceManifest place) {
        if (!place.dimension().equals(player.level().dimension().identifier().toString())) return Optional.empty();
        var box = place.bounds();
        var eye = player.getEyePosition();
        double cx = (box.minX() + box.maxX() + 1) / 2.0;
        double cz = (box.minZ() + box.maxZ() + 1) / 2.0;
        var centerDirection = new Vec3(cx, box.minY() + 2, cz).subtract(eye);
        if (centerDirection.lengthSqr() > NEARBY_DISTANCE * NEARBY_DISTANCE
                || centerDirection.normalize().dot(player.getViewVector(1)) < 0.55) return Optional.empty();
        var view = new LoadedBlockView(player.level());
        double[] xs = {cx, box.minX() + 0.5, box.maxX() + 0.5};
        double[] zs = {cz, box.minZ() + 0.5, box.maxZ() + 0.5};
        for (double x : xs) for (double z : zs) {
            var end = new Vec3(x, box.minY() + 0.5, z);
            var hit = view.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
            if (hit.getType() != HitResult.Type.BLOCK || !box.contains(hit.getBlockPos())) continue;
            var chunk = player.level().getChunkSource().getChunkNow(hit.getBlockPos().getX() >> 4, hit.getBlockPos().getZ() >> 4);
            if (chunk == null) continue;
            return Optional.of(hit.getLocation().add(eye.subtract(hit.getLocation()).normalize().scale(0.08)));
        }
        return Optional.empty();
    }

    private static Component title(ServerPlayer player,Observation observation,Mob mob) {
        var canonical=title(observation,mob);
        try {
            var annotation=dev.wicklen.chronicles.annotation.AnnotationService.get(player,observation.key()).orElse(null);
            if(annotation!=null&&!annotation.label().isBlank())return Component.literal(annotation.label()).append(" · ").append(canonical);
        } catch(IllegalStateException ignored) { /* Personal metadata failure never disables observation. */ }
        return canonical;
    }

    static Component title(Observation observation, Mob mob) {
        if (mob != null) return mob.getType().getDescription();
        for (var variant : StationVariant.values())
            if (variant.title().equals(observation.title())) return stationTitle(variant);
        return Component.literal(observation.title());
    }

    private static Component stationTitle(StationVariant variant) {
        return Component.translatable("station.wicklen_chronicles." + variant.name().toLowerCase(Locale.ROOT) + ".title");
    }

    private static void sparkle(ServerPlayer player, Vec3 surface, boolean known) {
        // Addressed only to the observing player. No entity glowing, global particles or coordinates packet.
        player.level().sendParticles(player, known ? ParticleTypes.COMPOSTER : ParticleTypes.END_ROD,
                true, false, surface.x, surface.y, surface.z, 6, 0.18, 0.28, 0.18, 0.005);
    }
}
