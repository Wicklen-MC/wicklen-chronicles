package dev.wicklen.chronicles.encounter;

import dev.wicklen.chronicles.ai.WorldStorageContext;
import dev.wicklen.chronicles.core.discovery.DiscoveryKey;
import dev.wicklen.chronicles.core.encounter.*;
import dev.wicklen.chronicles.core.quest.RewardDefinition;
import dev.wicklen.chronicles.inventory.InventoryDelivery;
import dev.wicklen.chronicles.station.PlaceManifest;
import dev.wicklen.chronicles.station.StationService;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Explicit-consent encounters. Optional executors never own the mod's reward ledger. */
public final class EncounterService {
    public static final int MAX_PARTY = 4;
    static final int RETURN_GRACE_TICKS = 100;
    private static final long LOBBY_TIME = 5 * 60_000L;
    private static final long EXECUTION_TIME = 8 * 60_000L;
    private static final String RELIC = "wicklen_chronicles:wayfarer_lantern";
    private static final Logger LOGGER = LoggerFactory.getLogger("wicklen_chronicles.encounter");
    private static RuntimeState current;
    private static boolean registered;

    public record ActionResult(boolean success, String message, UUID encounterId) {}
    public record View(UUID id, DiscoveryKey place, EncounterMode mode, String status, boolean leader,
                       List<UUID> participants, List<EncounterReward> rewards) {
        public View { participants = List.copyOf(participants); rewards = List.copyOf(rewards); }
    }

    private EncounterService() {}

    public static void register() {
        if (registered) return;
        registered = true;
        EncounterCommands.register();
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try { state(server); }
            catch (RuntimeException error) { LOGGER.error("Encounters unavailable; source preserved", error); }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (current == null || current.server != server || !WorldStorageContext.matchesSession(current.sessionId)) return;
            current.tick();
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> abortFor(handler.getPlayer().getUUID(), server, "Участник вышел из игры"));
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayer player) abortFor(player.getUUID(), player.level().getServer(), "Участник погиб");
        });
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((victim, source, amount) -> {
            if (!(victim instanceof ServerPlayer player) || current == null || current.server != player.level().getServer()
                    || !WorldStorageContext.matchesSession(current.sessionId)) return true;
            if (source.getEntity() instanceof ServerPlayer attacker
                    && (current.inActiveParty(player.getUUID()) || current.inActiveParty(attacker.getUUID()))) return false;
            Entity attacker = source.getEntity();
            if (attacker != null) {
                UUID encounterId = current.mobOwners.get(attacker.getUUID());
                EncounterSession session = encounterId == null ? null : current.find(encounterId);
                if (session != null && session.active() && !session.consenting().contains(player.getUUID())) return false;
            }
            return true;
        });
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (current != null && current.server == level.getServer()) current.entityLoaded(entity);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (current != null && current.server == server) current.abortAll("Сервер останавливается");
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            if (current != null && current.server == server) { current = null; GatewayBridge.clearListener(); }
        });
    }

    public static boolean gatewayAvailable(MinecraftServer server) { return state(server).bridge.available(); }

    public static ActionResult prepare(ServerPlayer player, DiscoveryKey placeKey, EncounterMode mode) {
        return action(player, runtime -> {
            if (!runtime.bridge.available()) return fail(runtime.bridge.unavailableReason(), null);
            var place = StationService.knownStation(player, placeKey).orElse(null);
            if (place == null || place.arena() == null) return fail("Сначала лично изучи станцию с двором для испытания.", null);
            String invalid = validateMember(player, place, false);
            if (invalid != null) return fail(invalid, null);
            for (var existing : runtime.ledger.all().values()) {
                if (!existing.live()) continue;
                if (existing.consenting().contains(player.getUUID())) return fail("Ты уже участвуешь в подготовке или испытании: " + existing.id(), existing.id());
                if (existing.place().key().equals(placeKey)) return fail("На этом месте уже готовится или идёт испытание.", existing.id());
            }
            long now = System.currentTimeMillis();
            var session = new EncounterSession(UUID.randomUUID(), player.getUUID(), place, Objects.requireNonNull(mode),
                    List.of(player.getUUID()), null, null, now, now + LOBBY_TIME);
            runtime.ledger.put(session);
            return ok("Подготовка " + mode + ". Для друзей: /spirit encounter join " + session.id()
                    + ". Встаньте во дворе и запустите: /spirit encounter start " + session.id(), session.id());
        });
    }

    public static ActionResult join(ServerPlayer player, UUID encounterId) {
        return action(player, runtime -> {
            var session = runtime.find(encounterId);
            if (session == null || session.encounter() != null) return fail("Подготовка уже закончилась или недоступна.", encounterId);
            String invalid = validateMember(player, session.place(), false);
            if (invalid != null) return fail(invalid, encounterId);
            if (session.consenting().contains(player.getUUID())) return ok("Ты уже подтвердил участие.", encounterId);
            if (session.consenting().size() >= MAX_PARTY) return fail("В этой группе уже четыре участника.", encounterId);
            if (runtime.inAnyLiveSession(player.getUUID())) return fail("Сначала выйди из другой подготовки или испытания.", encounterId);
            var members = new ArrayList<>(session.consenting());
            members.add(player.getUUID());
            var joined = session.withConsent(members);
            runtime.ledger.put(joined);
            runtime.tell(joined, player.getScoreboardName() + " подтвердил участие. Состав до старта: " + members.size());
            return ok("Участие подтверждено. Испытание ещё не началось.", encounterId);
        });
    }

    public static ActionResult start(ServerPlayer player, UUID encounterId) {
        return action(player, runtime -> {
            var session = runtime.find(encounterId);
            if (session == null || !session.leader().equals(player.getUUID())) return fail("Запустить подготовку может её создатель.", encounterId);
            if (session.encounter() != null) return fail("Этот запуск уже состоялся или отменён.", encounterId);
            if (System.currentTimeMillis() > session.deadlineAt()) { runtime.abort(session, "Время подготовки истекло"); return fail("Подготовка истекла. Создай новую.", encounterId); }
            if (!runtime.bridge.available()) return fail(runtime.bridge.unavailableReason(), encounterId);
            String invalid = runtime.validateParty(session, true);
            if (invalid != null) return fail(invalid, encounterId);
            ServerLevel level = runtime.level(session);
            if (level == null || !validArena(level, session.place())) return fail("Двор или безопасный выход изменены: запуск недоступен.", encounterId);
            long now = Math.max(System.currentTimeMillis(), session.createdAt());
            var prepared = EncounterTransitions.prepare(session.id(), session.place().worldId(), session.place().key(), session.mode(),
                    session.consenting(), new RewardDefinition(RELIC, 1), now);
            var started = EncounterTransitions.start(prepared, player.getUUID(), encounterId, now);
            var frozen = session.withEncounter(started.instance(), null, now + EXECUTION_TIME);
            runtime.ledger.put(frozen);
            var anchor = session.place().anchor();
            var opened = runtime.bridge.open(started.instance(), session.leader(), level, new Vec3(anchor.x() + 0.5, anchor.y(), anchor.z() + 0.5));
            if (!opened.success()) {
                runtime.abort(frozen, "Gateway не запустился");
                return fail(opened.failure(), encounterId);
            }
            var running = frozen.withEncounter(frozen.encounter(), opened.portalId(), frozen.deadlineAt());
            try { runtime.ledger.put(running); }
            catch (IOException error) { runtime.abort(frozen, "Запуск не удалось сохранить"); throw error; }
            runtime.tell(running, "Испытание началось. Состав зафиксирован: " + running.consenting().size()
                    + ". Светящиеся искры отмечают двор. При выходе есть 5 секунд, чтобы вернуться. Смерть или выход из игры отменяют встречу без награды.");
            return ok("Испытание запущено: " + session.mode(), encounterId);
        });
    }

    public static ActionResult leave(ServerPlayer player, UUID encounterId) {
        return action(player, runtime -> {
            var session = runtime.find(encounterId);
            if (session == null || !session.consenting().contains(player.getUUID())) return fail("Ты не участник этой встречи.", encounterId);
            if (!session.live()) return fail("Встреча уже закончилась.", encounterId);
            if (session.encounter() == null && !session.leader().equals(player.getUUID())) {
                var members = new ArrayList<>(session.consenting()); members.remove(player.getUUID());
                runtime.ledger.put(session.withConsent(members));
                return ok("Ты вышел из подготовки.", encounterId);
            }
            runtime.abort(session, "Участник прекратил испытание");
            return ok("Встреча отменена. Штрафа и награды нет.", encounterId);
        });
    }

    public static ActionResult claim(ServerPlayer player, UUID rewardId) {
        return action(player, runtime -> {
            if (!player.isAlive() || player.isSpectator()) return fail("Получить реликвию можно после возрождения вне режима наблюдателя.", null);
            for (var session : List.copyOf(runtime.ledger.all().values())) {
                if (session.encounter() == null || !session.consenting().contains(player.getUUID())) continue;
                var change = EncounterTransitions.acknowledgeReward(session.encounter(), player.getUUID(), rewardId);
                if (change.outcome() == EncounterTransitions.Outcome.ALREADY_CLAIMED) return fail("Эта награда уже получена.", session.id());
                if (!change.changed()) continue;
                var reward = session.encounter().reward();
                if (!reward.itemId().equals(RELIC) || reward.count() != 1) return fail("Награда не соответствует серверному профилю.", session.id());
                var item = BuiltInRegistries.ITEM.get(Identifier.parse(reward.itemId())).orElse(null);
                if (item == null) return fail("Реликвия сейчас недоступна. Право на награду сохранено.", session.id());
                var delivery = InventoryDelivery.deliver(player, List.of(new ItemStack(item.value(), 1)), () -> {
                    try {
                        runtime.ledger.put(session.withEncounter(change.instance(), session.portalId(), session.deadlineAt()));
                        return true;
                    } catch (IOException error) { LOGGER.warn("Encounter reward save failed", error); return false; }
                });
                return switch (delivery) {
                    case DELIVERED -> ok("Реликвия получена. Её действие можно использовать из руки.", session.id());
                    case FULL -> fail("Освободи место в инвентаре: награда останется ждать.", session.id());
                    case SAVE_FAILED -> fail("Не удалось сохранить получение. Награда остаётся ждать.", session.id());
                };
            }
            return fail("Ожидающая личная награда не найдена.", null);
        });
    }

    public static List<View> knownEncounters(ServerPlayer player) {
        RuntimeState runtime = state(player.level().getServer());
        return runtime.ledger.all().values().stream().filter(session -> session.consenting().contains(player.getUUID()))
                .sorted(Comparator.comparingLong(EncounterSession::createdAt).reversed()).limit(32)
                .map(session -> new View(session.id(), session.place().key(), session.mode(),
                    session.encounter() == null ? "LOBBY" : session.encounter().status().name(), session.leader().equals(player.getUUID()),
                    session.consenting(), session.encounter() == null ? List.of() : session.encounter().rewards().stream()
                        .filter(reward -> reward.ownerId().equals(player.getUUID())).toList())).toList();
    }

    static RuntimeState state(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Encounters are server-thread only");
        var session = WorldStorageContext.sessionId();
        if (!WorldStorageContext.matchesSession(session)) throw new IllegalStateException("World is not active");
        if (current == null || current.server != server || !current.sessionId.equals(session)) {
            current = new RuntimeState(server, session, WorldStorageContext.worldId());
            current.recover();
        }
        if (current.failure != null) throw new IllegalStateException("Encounter save is unavailable", current.failure);
        return current;
    }

    private static ActionResult action(ServerPlayer player, Action action) {
        try { return action.run(state(player.level().getServer())); }
        catch (IOException | RuntimeException error) {
            LOGGER.warn("Encounter action unavailable; source preserved", error);
            return fail("Испытания сейчас недоступны. Данные сохранены; сообщи администратору.", null);
        }
    }

    @FunctionalInterface private interface Action { ActionResult run(RuntimeState runtime) throws IOException; }
    private static ActionResult ok(String message, UUID id) { return new ActionResult(true, message, id); }
    private static ActionResult fail(String message, UUID id) { return new ActionResult(false, message, id); }

    private static String validateMember(ServerPlayer player, PlaceManifest place, boolean arena) {
        if (!player.isAlive() || player.isSpectator()) return "Для участия нужно быть живым игроком вне режима наблюдателя.";
        if (!place.worldId().equals(WorldStorageContext.worldId())
                || !place.dimension().equals(player.level().dimension().identifier().toString())) return "Ты находишься в другом мире или измерении.";
        if (StationService.knownStation(player, place.key()).filter(place::equals).isEmpty()) return "Сначала лично изучи эту станцию.";
        if (!(arena ? place.arena() : place.bounds()).contains(player.blockPosition())) {
            return arena ? "Перед стартом все участники должны войти во двор испытания." : "Для подготовки нужно находиться на станции.";
        }
        return null;
    }

    private static boolean validArena(ServerLevel level, PlaceManifest place) {
        if (!level.getWorldBorder().isWithinBounds(place.anchor().blockPos())) return false;
        var arena = place.arena();
        if (arena == null || !level.hasChunkAt(new BlockPos(arena.minX(), arena.minY(), arena.minZ()))
                || !level.hasChunkAt(new BlockPos(arena.maxX(), arena.maxY(), arena.maxZ()))) return false;
        return safePoint(level, place.anchor().blockPos()) && safePoint(level, place.safeExit().blockPos());
    }

    private static boolean safePoint(ServerLevel level, BlockPos point) {
        return level.hasChunkAt(point) && level.getWorldBorder().isWithinBounds(point)
                && level.getBlockState(point.below()).isCollisionShapeFullBlock(level, point.below())
                && level.getBlockState(point).getCollisionShape(level, point).isEmpty()
                && level.getBlockState(point.above()).getCollisionShape(level, point.above()).isEmpty()
                && level.getFluidState(point).isEmpty() && level.getFluidState(point.above()).isEmpty();
    }

    private static void abortFor(UUID owner, MinecraftServer server, String reason) {
        if (current == null || current.server != server) return;
        try {
            for (var session : List.copyOf(current.ledger.all().values()))
                if (session.live() && session.consenting().contains(owner)) current.abort(session, reason);
        } catch (RuntimeException error) { current.failure = error; LOGGER.error("Encounter abort could not read saved state", error); }
    }

    static final class RuntimeState {
        final MinecraftServer server;
        final UUID sessionId;
        final EncounterLedger ledger;
        final GatewayBridge bridge;
        final Queue<GatewayBridge.Completion> completions = new ConcurrentLinkedQueue<>();
        final Set<UUID> aborting = new HashSet<>();
        final Map<UUID, UUID> mobOwners = new HashMap<>();
        final Map<UUID, Map<UUID, Integer>> outsideSince = new HashMap<>();
        final Map<UUID, Vec3> lastSafeMobPositions = new HashMap<>();
        final Map<UUID, Integer> retryMobReturnAt = new HashMap<>();
        Throwable failure;

        RuntimeState(MinecraftServer server, UUID sessionId, UUID worldId) {
            this.server = server; this.sessionId = sessionId;
            ledger = new EncounterLedger(worldId);
            bridge = new GatewayBridge(completions::add);
        }

        EncounterSession find(UUID id) { return id == null ? null : ledger.all().get(id.toString()); }
        boolean inAnyLiveSession(UUID owner) { return ledger.all().values().stream().anyMatch(session -> session.live() && session.consenting().contains(owner)); }
        boolean inActiveParty(UUID owner) {
            if (failure != null) return false;
            try { return ledger.all().values().stream().anyMatch(session -> session.active() && session.consenting().contains(owner)); }
            catch (RuntimeException error) { failure = error; LOGGER.error("Encounter damage guard disabled; saved source preserved", error); return false; }
        }
        ServerLevel level(EncounterSession session) { return server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(session.place().dimension()))); }

        void recover() {
            try {
                for (var session : List.copyOf(ledger.all().values())) {
                    if (session.live()) abort(session, "Предыдущий запуск прерван перезапуском сервера");
                    else bridge.closeSource(session.id());
                }
                // Spawn chunks can load entities before SERVER_STARTED, while
                // our runtime is not attached yet. Reconcile those directly as
                // well as future ENTITY_LOAD events; API active lists may lag.
                for (var level : server.getAllLevels()) {
                    var loaded = new ArrayList<Entity>();
                    level.getAllEntities().forEach(loaded::add);
                    for (var entity : loaded) entityLoaded(entity);
                }
            } catch (RuntimeException error) { failure = error; LOGGER.error("Encounter recovery stopped; source preserved", error); }
        }

        String validateParty(EncounterSession session, boolean arena) {
            for (UUID owner : session.consenting()) {
                var player = server.getPlayerList().getPlayer(owner);
                if (player == null) return "Участник группы не в игре.";
                String invalid = validateMember(player, session.place(), arena);
                if (invalid != null) return invalid;
            }
            if (arena) {
                for (var player : server.getPlayerList().getPlayers()) {
                    if (!player.isSpectator() && player.isAlive()
                            && player.level().dimension().identifier().toString().equals(session.place().dimension())
                            && session.place().arena().contains(player.blockPosition()) && !session.consenting().contains(player.getUUID())) {
                        return "Во дворе находится игрок без подтверждённого участия.";
                    }
                }
            }
            return null;
        }

        void tick() {
            if (failure != null) return;
            try {
                int pending = Math.min(completions.size(), 64);
                for (int i = 0; i < pending; i++) {
                    var event = completions.poll();
                    if (event != null) completion(event);
                }
                for (var session : List.copyOf(ledger.all().values())) {
                    if (!session.live()) continue;
                    if (aborting.contains(session.id()) || System.currentTimeMillis() > session.deadlineAt()) { abort(session, "Время подготовки или испытания истекло"); continue; }
                    if (session.encounter() == null) {
                        if (session.consenting().stream().anyMatch(owner -> server.getPlayerList().getPlayer(owner) == null)) abort(session, "Участник вышел из игры");
                        else if (server.getTickCount() % 20 == 0) showBoundary(session, level(session));
                        continue;
                    }
                    String invalid = validateActiveParty(session);
                    if (invalid != null) { abort(session, invalid); continue; }
                    if (session.portalId() == null || !bridge.exists(session.portalId())) { abort(session, "Портал испытания исчез"); continue; }
                    var level = level(session);
                    if (server.getTickCount() % 20 == 0) showBoundary(session, level);
                    for (UUID mobId : bridge.trackedMobs(session.portalId())) {
                        mobOwners.put(mobId, session.id());
                        var mob = level == null ? null : level.getEntity(mobId);
                        if (mob == null) continue;
                        mob.addTag(GatewayBridge.sourceId(session.id()));
                        containMob(session, level, mob);
                    }
                }
            } catch (RuntimeException error) {
                LOGGER.error("Encounter runtime stopped; aborting active encounters", error);
                abortAll("Ошибка исполнителя испытания");
                failure = error;
            }
        }

        /** Movement by the executor's AI must never be interpreted as a player's surrender. */
        void containMob(EncounterSession session, ServerLevel level, Entity entity) {
            if (!entity.isAlive() || level == null) return;
            var arena = session.place().arena();
            if (entity instanceof Mob mob) {
                var target = mob.getTarget();
                if (!(target instanceof ServerPlayer player) || !session.consenting().contains(player.getUUID())
                        || !arena.contains(player.blockPosition())) {
                    var nearest = session.consenting().stream().map(server.getPlayerList()::getPlayer)
                            .filter(player -> player != null && player.isAlive() && player.level() == level
                                    && arena.contains(player.blockPosition()))
                            .min(Comparator.comparingDouble(entity::distanceToSqr)).orElse(null);
                    mob.setTarget(nearest);
                }
            }
            if (insideInset(arena, entity.position()) && safeReturn(level, entity, entity.position())) {
                lastSafeMobPositions.put(entity.getUUID(), entity.position());
                retryMobReturnAt.remove(entity.getUUID());
                return;
            }
            if (arena.contains(entity.blockPosition())) return;
            if (server.getTickCount() < retryMobReturnAt.getOrDefault(entity.getUUID(), 0)) return;
            Vec3 destination = lastSafeMobPositions.get(entity.getUUID());
            if (destination == null || !insideInset(arena, destination) || !safeReturn(level, entity, destination)) {
                destination = returnPosition(session, level, entity);
            }
            if (destination == null) {
                // Terrain can be changed by players. Retry safely without putting a mob into a wall.
                retryMobReturnAt.put(entity.getUUID(), server.getTickCount() + 20);
                return;
            }
            if (entity instanceof Mob mob) mob.getNavigation().stop();
            entity.teleportTo(destination.x, destination.y, destination.z);
            entity.setDeltaMovement(Vec3.ZERO);
            entity.resetFallDistance();
            lastSafeMobPositions.put(entity.getUUID(), destination);
            retryMobReturnAt.remove(entity.getUUID());
        }

        private Vec3 returnPosition(EncounterSession session, ServerLevel level, Entity entity) {
            var arena = session.place().arena();
            var candidates = new ArrayList<Vec3>();
            for (int x = arena.minX() + 1; x < arena.maxX(); x++) {
                for (int z = arena.minZ() + 1; z < arena.maxZ(); z++) {
                    for (int y = arena.minY(); y <= arena.maxY(); y++) candidates.add(new Vec3(x + 0.5, y, z + 0.5));
                }
            }
            candidates.sort(Comparator.comparingDouble(point -> point.distanceToSqr(entity.position())));
            for (var point : candidates) if (safeReturn(level, entity, point)) return point;
            return null;
        }

        private static boolean insideInset(PlaceManifest.Bounds arena, Vec3 point) {
            return point.x >= arena.minX() + 1 && point.x < arena.maxX()
                    && point.z >= arena.minZ() + 1 && point.z < arena.maxZ()
                    && point.y >= arena.minY() && point.y <= arena.maxY();
        }

        private static boolean safeReturn(ServerLevel level, Entity entity, Vec3 point) {
            var feet = BlockPos.containing(point);
            return safePoint(level, feet) && level.noBlockCollision(entity,
                    entity.getBoundingBox().move(point.subtract(entity.position())));
        }

        String validateActiveParty(EncounterSession session) {
            int tick = server.getTickCount();
            var departed = outsideSince.computeIfAbsent(session.id(), ignored -> new HashMap<>());
            for (UUID owner : session.consenting()) {
                var player = server.getPlayerList().getPlayer(owner);
                if (player == null) return "Участник группы не в игре";
                if (!player.isAlive() || player.isSpectator()) return "Участник погиб или перешёл в режим наблюдателя";
                if (!session.place().dimension().equals(player.level().dimension().identifier().toString())) return "Участник покинул измерение испытания";
                if (session.place().arena().contains(player.blockPosition())) {
                    if (departed.remove(owner) != null) player.sendOverlayMessage(Component.literal("Ты снова во дворе — испытание продолжается"));
                    continue;
                }
                int elapsed = tick - departed.computeIfAbsent(owner, ignored -> tick);
                if (elapsed >= RETURN_GRACE_TICKS) return "Участник покинул двор испытания";
                if (elapsed % 20 == 0) player.sendOverlayMessage(Component.literal("Вернись за светящуюся границу: "
                        + (RETURN_GRACE_TICKS - elapsed + 19) / 20 + " с"));
            }
            return null;
        }

        private void showBoundary(EncounterSession session, ServerLevel level) {
            if (level == null) return;
            var arena = session.place().arena();
            for (UUID owner : session.consenting()) {
                var player = server.getPlayerList().getPlayer(owner);
                if (player == null || player.level() != level) continue;
                double y = arena.minY() + 0.12;
                for (int offset = 0; offset <= arena.width(); offset += 2) {
                    double x = arena.minX() + offset, z = arena.minZ() + offset;
                    boundaryParticle(level, player, x, y, arena.minZ());
                    boundaryParticle(level, player, x, y, arena.maxZ() + 1);
                    boundaryParticle(level, player, arena.minX(), y, z);
                    boundaryParticle(level, player, arena.maxX() + 1, y, z);
                }
            }
        }

        private static void boundaryParticle(ServerLevel level, ServerPlayer player, double x, double y, double z) {
            level.sendParticles(player, ParticleTypes.END_ROD, false, false, x, y, z, 1, 0, 0.02, 0, 0);
        }

        void completion(GatewayBridge.Completion event) {
            if (event.server() != server || !WorldStorageContext.matchesSession(sessionId) || event.sourceId() == null) return;
            var prefix = "wicklen_chronicles:encounter/";
            if (!event.sourceId().startsWith(prefix)) return;
            UUID id;
            try { id = UUID.fromString(event.sourceId().substring(prefix.length())); }
            catch (IllegalArgumentException invalid) { return; }
            var session = find(id);
            if (session == null || !session.active() || aborting.contains(id) || !event.portalId().equals(session.portalId())
                    || !event.dimension().equals(session.place().dimension()) || !session.place().worldId().equals(WorldStorageContext.worldId())) return;
            EncounterStatus status = switch (event.outcome()) {
                case "COMPLETED" -> EncounterStatus.SUCCESS;
                case "FAILED" -> EncounterStatus.FAILURE;
                default -> EncounterStatus.ABORTED;
            };
            if (status == EncounterStatus.SUCCESS && (System.currentTimeMillis() > session.deadlineAt()
                    || validateActiveParty(session) != null)) status = EncounterStatus.ABORTED;
            var change = EncounterTransitions.finish(session.encounter(), id, status,
                    Math.max(System.currentTimeMillis(), session.encounter().startedAt()));
            if (!change.changed()) return;
            try {
                ledger.put(session.withEncounter(change.instance(), session.portalId(), session.deadlineAt()));
                cleanup(session);
                if (status == EncounterStatus.SUCCESS) {
                    for (var grant : change.instance().rewards()) send(grant.ownerId(), "Испытание пройдено. Получить реликвию: /spirit encounter claim " + grant.id());
                } else tell(session, "Испытание закончено: " + status + ". Штрафа и награды нет.");
            } catch (IOException error) {
                completions.add(event);
                LOGGER.warn("Encounter outcome could not be saved; will retry", error);
            }
        }

        void abort(EncounterSession session, String reason) {
            if (!session.live()) { cleanup(session); return; }
            aborting.add(session.id());
            var instance = session.encounter() == null ? EncounterTransitions.prepare(session.id(), session.place().worldId(),
                    session.place().key(), session.mode(), session.consenting(), new RewardDefinition(RELIC, 1), session.createdAt()) : session.encounter();
            var ended = EncounterTransitions.finish(instance, session.id(), EncounterStatus.ABORTED,
                    Math.max(System.currentTimeMillis(), Math.max(instance.preparedAt(), instance.startedAt()))).instance();
            try {
                ledger.put(session.withEncounter(ended, session.portalId(), session.deadlineAt()));
                aborting.remove(session.id());
                tell(session, reason + ". Встреча отменена без штрафа и награды.");
            } catch (IOException | RuntimeException error) { LOGGER.warn("Encounter abort awaiting durable save", error); }
            cleanup(session);
        }

        void abortAll(String reason) {
            try { for (var session : List.copyOf(ledger.all().values())) if (session.live()) abort(session, reason); }
            catch (RuntimeException error) { LOGGER.error("Cannot enumerate encounter save; source preserved", error); }
        }

        void cleanup(EncounterSession session) {
            var level = level(session);
            var owned = mobOwners.entrySet().stream().filter(entry -> entry.getValue().equals(session.id())).map(Map.Entry::getKey).toList();
            if (level != null) {
                var area = area(session.place().bounds()).inflate(16);
                for (var entity : level.getEntities((Entity) null, area, entity -> entity.entityTags().contains(GatewayBridge.sourceId(session.id())))) entity.discard();
                for (UUID id : owned) { var entity = level.getEntity(id); if (entity != null) entity.discard(); }
                for (var projectile : level.getEntitiesOfClass(Projectile.class, area)) {
                    var owner = projectile.getOwner();
                    if (owner != null && owned.contains(owner.getUUID())) projectile.discard();
                }
                for (UUID owner : session.consenting()) {
                    var player = server.getPlayerList().getPlayer(owner);
                    var exit = session.place().safeExit();
                    if (player != null && player.isAlive() && player.level() == level && session.place().arena().contains(player.blockPosition())
                            && safePoint(level, exit.blockPos())) player.teleportTo(exit.x() + 0.5, exit.y(), exit.z() + 0.5);
                }
            }
            bridge.closeSource(session.id());
            outsideSince.remove(session.id());
            for (UUID id : owned) {
                mobOwners.remove(id);
                lastSafeMobPositions.remove(id);
                retryMobReturnAt.remove(id);
            }
        }

        void entityLoaded(Entity entity) {
            try {
                String source = bridge.sourceOf(entity);
                if (source != null && source.startsWith("wicklen_chronicles:encounter/")) {
                    UUID id = UUID.fromString(source.substring("wicklen_chronicles:encounter/".length()));
                    var session = find(id);
                    if (session == null || !session.active() || aborting.contains(id)
                            || !session.place().dimension().equals(entity.level().dimension().identifier().toString())
                            || (session.portalId() != null && !session.portalId().equals(entity.getUUID()))) bridge.closeLoadedOwnedPortal(entity);
                }
                for (String tag : entity.entityTags()) {
                    if (!tag.startsWith("wicklen_chronicles:encounter/")) continue;
                    UUID id = UUID.fromString(tag.substring("wicklen_chronicles:encounter/".length()));
                    var session = find(id);
                    if (session == null || !session.active() || aborting.contains(id)) entity.discard();
                }
            } catch (RuntimeException error) { LOGGER.warn("Encounter entity load check failed", error); }
        }

        void tell(EncounterSession session, String message) { for (UUID owner : session.consenting()) send(owner, message); }
        void send(UUID owner, String message) {
            var player = server.getPlayerList().getPlayer(owner);
            if (player != null) try { player.sendSystemMessage(Component.literal(message)); }
            catch (RuntimeException error) { LOGGER.warn("Encounter message delivery failed", error); }
        }
    }

    private static AABB area(PlaceManifest.Bounds bounds) {
        return new AABB(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX() + 1.0, bounds.maxY() + 1.0, bounds.maxZ() + 1.0);
    }
}
