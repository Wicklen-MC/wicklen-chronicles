package dev.wicklen.chronicles.exploration;

import dev.wicklen.chronicles.ai.SpiritQuests;
import dev.wicklen.chronicles.ai.WorldStorageContext;
import dev.wicklen.chronicles.core.discovery.DiscoveryKey;
import dev.wicklen.chronicles.core.discovery.DiscoveryKind;
import dev.wicklen.chronicles.core.discovery.DiscoveryPosition;
import dev.wicklen.chronicles.core.discovery.DiscoverySnapshot;
import dev.wicklen.chronicles.core.discovery.Observation;
import dev.wicklen.chronicles.core.journal.JournalView;
import dev.wicklen.chronicles.core.journal.JournalViews;
import dev.wicklen.chronicles.entity.SpiritEntity;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

/** Server-authoritative observation and personal, paged journal entry point. */
public final class ExplorationService {
    public static final int REQUIRED_FOCUS_TICKS = 40;
    public static final double MAX_OBSERVATION_DISTANCE = 96;
    private static final Logger LOGGER = LoggerFactory.getLogger("wicklen_chronicles.exploration");
    private static final List<VisiblePlaceProvider> PLACE_PROVIDERS = new ArrayList<>(List.of(new VanillaPlaceObserver()));
    private static final List<BiConsumer<ServerPlayer, DiscoverySnapshot>> LISTENERS = new ArrayList<>();
    private static final List<ConfirmedListener> CONFIRMED = new ArrayList<>();
    @FunctionalInterface public interface ConfirmedListener {
        void confirmed(ServerPlayer player, Observation observation, DiscoveryPosition observerPosition);
    }
    private static RuntimeState current;
    private static boolean registered;

    private ExplorationService() {}

    public static void register() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_SERVER_TICK.register(ExplorationService::tick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            if (current != null && current.server == server) {
                current.focus.remove(handler.player.getUUID());
                current.retryAfter.remove(handler.player.getUUID());
                current.confirmedFocus.remove(handler.player.getUUID());
                current.hints.remove(handler.player.getUUID());
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            if (current != null && current.server == server) current = null;
        });
    }

    /** Register stable definition/provider objects; server/world state belongs in their own runtime. */
    public static void registerPlaceProvider(VisiblePlaceProvider provider) {
        PLACE_PROVIDERS.addFirst(java.util.Objects.requireNonNull(provider));
    }

    /** Listener runs only after checked persistence, for this player's known snapshot. */
    public static void onChanged(BiConsumer<ServerPlayer, DiscoverySnapshot> listener) {
        LISTENERS.add(java.util.Objects.requireNonNull(listener));
    }
    /** Genuine focused observations, including familiar species; listeners cannot revoke a base discovery. */
    public static void onConfirmed(ConfirmedListener listener) { CONFIRMED.add(java.util.Objects.requireNonNull(listener)); }

    public static DiscoverySnapshot snapshot(ServerPlayer player) {
        var state = state(player.level().getServer());
        state.requireHealthy();
        return state.repository.snapshot(player.getUUID());
    }

    public static JournalView journal(ServerPlayer player, int discoveryOffset, int historyOffset, int limit) {
        var active = SpiritQuests.active(player.getUUID());
        return JournalViews.create(snapshot(player), player.getUUID(), active == null ? null : active.instance(),
                SpiritQuests.history(player.getUUID()), discoveryOffset, historyOffset, limit);
    }

    public static boolean fieldGuideAdapterAvailable(ServerPlayer player) {
        return state(player.level().getServer()).fieldGuide.available();
    }

    /** Package-scoped geometry probe for native server checks; it never records an entry. */
    static Observation inspectTarget(ServerPlayer player) {
        if (!player.isAlive() || player.isSpectator() || !player.isUsingItem() || !player.getUseItem().is(Items.SPYGLASS)) return null;
        var target = visibleTarget(player, state(player.level().getServer()));
        return target == null ? null : target.observation;
    }

    private static RuntimeState state(MinecraftServer server) {
        UUID session = WorldStorageContext.sessionId();
        if (server == null || !WorldStorageContext.matchesSession(session))
            throw new IllegalStateException("Exploration is unavailable outside an active world");
        if (current == null || current.server != server || !current.sessionId.equals(session))
            current = new RuntimeState(server, session);
        return current;
    }

    public static void tick(MinecraftServer server) {
        if (!WorldStorageContext.matchesSession(WorldStorageContext.sessionId())) return;
        var state = state(server);
        if (state.failure != null) return;
        for (var player : server.getPlayerList().getPlayers()) {
            var tracker = state.focus.computeIfAbsent(player.getUUID(), ignored -> new ObservationTracker());
            if (!player.isAlive() || player.isSpectator() || !player.isUsingItem() || !player.getUseItem().is(Items.SPYGLASS)) {
                tracker.reset();
                state.confirmedFocus.remove(player.getUUID());
                long tick = server.getTickCount();
                if (player.isAlive() && !player.isSpectator() && tick % 20 == 0
                        && tick >= state.retryAfter.getOrDefault(player.getUUID(), 0L)) {
                    try {
                        // Crosshair-only lookup also identifies supported vanilla places without zooming.
                        var target = visibleTarget(player, state, ObservationHints.NEARBY_DISTANCE);
                        if (target != null && target.observation.key().kind() == DiscoveryKind.PLACE) {
                            boolean known = state.repository.snapshot(player.getUUID()).entries().stream()
                                    .anyMatch(entry -> entry.key().equals(target.observation.key()));
                            state.hints.available(player, ObservationHints.title(target.observation, null), target.surface, known, tick);
                        } else state.hints.nearby(player, tick);
                    } catch (RuntimeException error) {
                        // Optional hints must not break the tick or mutate a failing discovery store.
                        state.retryAfter.put(player.getUUID(), tick + 100);
                        LOGGER.warn("Nearby observation hint deferred: {}", error.toString());
                    }
                }
                continue;
            }
            long tick = server.getTickCount();
            if (tick < state.retryAfter.getOrDefault(player.getUUID(), 0L)) {
                tracker.reset();
                continue;
            }
            try {
                var target = visibleTarget(player, state);
                if (target == null) {
                    tracker.reset();
                    state.confirmedFocus.remove(player.getUUID());
                    state.hints.invalid(player, tick);
                    continue;
                }
                boolean known = state.repository.snapshot(player.getUUID()).entries().stream()
                        .anyMatch(entry -> entry.key().equals(target.observation.key()));
                var observerPosition = position(player.level(), player.blockPosition());
                String focusKey = target.focusId + "|" + Math.floorDiv(target.observation.position().x(),128) + "|"
                        + Math.floorDiv(target.observation.position().z(),128) + "|" + Math.floorDiv(observerPosition.x(),128) + "|" + Math.floorDiv(observerPosition.z(),128);
                if (focusKey.equals(state.confirmedFocus.get(player.getUUID()))) {
                    tracker.reset();
                    state.hints.scan(player, target.observation, target.mob, target.surface, 100, true, tick);
                    continue;
                }
                boolean confirmedByFieldGuide = !known && target.mob != null && state.fieldGuide.confirms(player,
                        BuiltInRegistries.ENTITY_TYPE.getKey(target.mob.getType()), target.mob.getId());
                boolean ready = tracker.sample(target.focusId, tick, player.getTicksUsingItem(), true, true,
                        confirmedByFieldGuide, REQUIRED_FOCUS_TICKS);
                if (!ready) {
                    state.hints.scan(player, target.observation, target.mob, target.surface,
                            tracker.focusedTicks() * 100 / REQUIRED_FOCUS_TICKS, known, tick);
                    continue;
                }
                var change = state.repository.observe(player.getUUID(), target.observation);
                tracker.reset();
                state.confirmedFocus.put(player.getUUID(),focusKey);
                if (change.changed()) {
                    state.hints.recorded(player, target.observation, target.mob, target.surface, tick);
                    for (var listener : LISTENERS) {
                        try { listener.accept(player, change.snapshot()); }
                        catch (RuntimeException error) { LOGGER.warn("Discovery listener failed after persistence: {}", error.toString()); }
                    }
                }
                for (var listener : CONFIRMED) {
                    try { listener.confirmed(player,target.observation,observerPosition); }
                    catch (RuntimeException error) { LOGGER.warn("Field observation listener deferred; discovery preserved: {}",error.toString()); }
                }
            } catch (IOException | UncheckedIOException error) {
                tracker.reset();
                state.retryAfter.put(player.getUUID(), tick + 100);
                LOGGER.warn("Discovery save failed; observation rolled back: {}", error.toString());
                player.sendOverlayMessage(Component.translatable("journal.wicklen_chronicles.discovery_save_failed"));
            } catch (RuntimeException error) {
                state.failure = error;
                state.focus.clear();
                LOGGER.error("Exploration disabled for this session; existing data preserved", error);
                return;
            }
        }
    }

    private static Target visibleTarget(ServerPlayer player, RuntimeState state) {
        return visibleTarget(player, state, MAX_OBSERVATION_DISTANCE);
    }

    private static Target visibleTarget(ServerPlayer player, RuntimeState state, double maxDistance) {
        if (!(player.level() instanceof ServerLevel level)) return null;
        var from = player.getEyePosition();
        var to = from.add(player.getViewVector(1.0f).scale(maxDistance));
        var block = new LoadedBlockView(level).clip(new ClipContext(from, to,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        var visibleEnd = block.getType() == HitResult.Type.MISS ? to : block.getLocation();
        Mob nearest = null;
        double nearestDistance = from.distanceToSqr(visibleEnd);
        for (var entity : level.getEntities(player, new AABB(from, visibleEnd).inflate(1),
                entity -> entity instanceof Mob && !(entity instanceof SpiritEntity) && entity.isAlive() && !entity.isSpectator() && !entity.isInvisibleTo(player))) {
            var entityPos = entity.blockPosition();
            if (level.getChunkSource().getChunkNow(entityPos.getX() >> 4, entityPos.getZ() >> 4) == null) continue;
            var box = entity.getBoundingBox().inflate(Math.max(0.1, entity.getPickRadius()));
            var intersect = box.contains(from) ? java.util.Optional.of(from) : box.clip(from, visibleEnd);
            if (intersect.isEmpty()) continue;
            double distance = from.distanceToSqr(intersect.get());
            if (distance > nearestDistance) continue;
            nearestDistance = distance;
            nearest = (Mob) entity;
        }
        long time = System.currentTimeMillis();
        if (nearest != null) {
            var id = BuiltInRegistries.ENTITY_TYPE.getKey(nearest.getType());
            var pos = nearest.blockPosition();
            String title = nearest.getType().getDescription().getString();
            if (title.length() > 160) title = title.substring(0, 160);
            var observation = new Observation(new DiscoveryKey(DiscoveryKind.CREATURE, id.toString()), title,
                    "journal.wicklen_chronicles.creature_summary", position(level, pos), time);
            // A visible point on the near side of the mob, never a beacon above an occluding wall.
            var surface = from.add(player.getViewVector(1.0f).scale(Math.sqrt(nearestDistance))).subtract(player.getViewVector(1.0f).scale(0.08));
            return new Target(observation, level.dimension().identifier() + "|entity|" + nearest.getUUID(), nearest, surface);
        }
        if (block.getType() != HitResult.Type.BLOCK) return null;
        BlockPos viewed = block.getBlockPos();
        if (level.getChunkSource().getChunkNow(viewed.getX() >> 4, viewed.getZ() >> 4) == null) return null;
        for (var provider : PLACE_PROVIDERS) {
            if (state.failedProviders.contains(provider)) continue;
            try {
                var observation = provider.observe(level, player, viewed, time);
                if (observation.isEmpty()) continue;
                if (observation.get().key().kind() != DiscoveryKind.PLACE
                        || !observation.get().position().dimension().equals(level.dimension().identifier().toString()))
                    throw new IllegalArgumentException("Place provider returned a foreign dimension or kind");
                var surface = block.getLocation().subtract(player.getViewVector(1.0f).scale(0.08));
                return new Target(observation.get(), level.dimension().identifier() + "|" + observation.get().key().id(), null, surface);
            } catch (UncheckedIOException error) {
                // Metadata indexing can fail temporarily. Let the normal save
                // retry path handle it rather than disabling this provider.
                throw error;
            } catch (RuntimeException error) {
                state.failedProviders.add(provider);
                LOGGER.warn("Place observer disabled for this session: {}", error.toString());
            }
        }
        return null;
    }

    private static DiscoveryPosition position(ServerLevel level, BlockPos pos) {
        return new DiscoveryPosition(level.dimension().identifier().toString(), pos.getX(), pos.getY(), pos.getZ());
    }

    private record Target(Observation observation, String focusId, Mob mob, Vec3 surface) {}

    private static final class RuntimeState {
        final MinecraftServer server;
        final UUID sessionId;
        final DiscoveryRepository repository = new DiscoveryRepository();
        final FieldGuideBridge fieldGuide = new FieldGuideBridge();
        final ObservationHints hints = new ObservationHints();
        final Map<UUID, ObservationTracker> focus = new HashMap<>();
        final Map<UUID, String> confirmedFocus = new HashMap<>();
        final Map<UUID, Long> retryAfter = new HashMap<>();
        final Set<VisiblePlaceProvider> failedProviders = new HashSet<>();
        RuntimeException failure;

        RuntimeState(MinecraftServer server, UUID sessionId) { this.server = server; this.sessionId = sessionId; }
        void requireHealthy() {
            if (failure != null) throw new IllegalStateException("Exploration data is unavailable; contact an administrator", failure);
        }
    }
}
