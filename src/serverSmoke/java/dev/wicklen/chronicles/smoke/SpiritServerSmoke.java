package dev.wicklen.chronicles.smoke;

import dev.wicklen.chronicles.ai.SpiritQuests;
import dev.wicklen.chronicles.ai.WorldStorageContext;
import dev.wicklen.chronicles.core.quest.QuestDefinition;
import dev.wicklen.chronicles.core.quest.QuestStatus;
import dev.wicklen.chronicles.core.quest.QuestType;
import dev.wicklen.chronicles.core.quest.RewardDefinition;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Test-only dedicated server entry point: no client classes or external services. */
public final class SpiritServerSmoke implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("wicklen_chronicles-server-smoke");
    private Path runDirectory;
    private Throwable failure;
    private boolean armed;
    private boolean finished;
    private int ticks;

    @Override
    public void onInitialize() {
        // Even if this source set is accidentally visible to another dev run,
        // it must never stop that server or modify its world for testing.
        if (!Boolean.getBoolean("wicklen_chronicles.serverSmoke")) return;
        String configuredDirectory = System.getProperty("wicklen_chronicles.serverSmoke.directory");
        if (configuredDirectory == null || configuredDirectory.isBlank()) {
            throw new IllegalStateException("Server smoke requires an explicit isolated run directory");
        }
        runDirectory = Path.of(configuredDirectory).toAbsolutePath().normalize();
        Path actual = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
        require(actual.equals(runDirectory), "smoke run directory must match its explicit isolated path");
        ServerLifecycleEvents.SERVER_STARTED.register(this::verifyStartedServer);
        ServerTickEvents.END_SERVER_TICK.register(this::finishAfterTicks);
    }

    private void verifyStartedServer(MinecraftServer server) {
        try {
            require(server.isDedicatedServer(), "a real dedicated server must be running");
            require(WorldStorageContext.isOpen(), "world storage must be open after SERVER_STARTED");
            UUID worldId = WorldStorageContext.worldId();
            require(worldId != null && WorldStorageContext.sessionId() != null, "world and runtime IDs must exist");

            Path worldDirectory = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            require(worldDirectory.startsWith(runDirectory) && !worldDirectory.equals(runDirectory),
                    "test world must live inside the isolated smoke run directory");
            Path storage = WorldStorageContext.directory().toAbsolutePath().normalize();
            require(storage.equals(worldDirectory.resolve("wicklen-chronicles")), "progress must be scoped to the active world");

            // A new dummy owner on each isolated run also permits rerunning the
            // smoke test without deleting its previously generated test world.
            UUID owner = UUID.randomUUID();
            var definition = new QuestDefinition("wicklen_chronicles:smoke/collection", 1,
                    QuestType.COLLECT, "minecraft:apple", 3,
                    new RewardDefinition("minecraft:emerald", 2));
            var quest = SpiritQuests.issue(owner, definition);
            require(quest != null && quest.status() == QuestStatus.ACTIVE, "quest adapter must issue the core quest");
            require(quest.instance().ownerId().equals(owner) && quest.instance().definition().equals(definition),
                    "adapter must preserve owner and accepted definition");
            require(SpiritQuests.active(owner).instance().equals(quest.instance()), "issued quest must remain readable");

            WorldStorageContext.flush();
            Path metadata = storage.resolve("world-metadata.json");
            Path ledger = storage.resolve("quest-ledger.json");
            require(Files.isRegularFile(metadata) && Files.readString(metadata).contains(worldId.toString()),
                    "world metadata must be persisted in the world directory");
            require(Files.isRegularFile(ledger), "quest ledger must be persisted in the world directory");
            String encoded = Files.readString(ledger);
            require(encoded.contains(owner.toString()) && encoded.contains(quest.id().toString())
                            && encoded.contains(quest.rewardId().toString()),
                    "persisted ledger must contain stable owner, quest and reward identities");
            verifyLegacyEntity(server);
            dev.wicklen.chronicles.exploration.ExplorationNativeChecks.prepare(server);
            if (Boolean.getBoolean("wicklen.chronicles.smoke.verifyBalance")) {
                server.overworld().getChunk(0, 0);
                var returned = new java.util.concurrent.atomic.AtomicInteger(Integer.MIN_VALUE);
                var succeeded = new java.util.concurrent.atomic.AtomicBoolean();
                var source = server.createCommandSourceStack().withLevel(server.overworld())
                    .withPosition(new net.minecraft.world.phys.Vec3(0.5, 100, 0.5)).withCallback((success, result) -> {
                    succeeded.set(success);
                    returned.set(result);
                });
                server.getCommands().performPrefixedCommand(source, "function wicklen_balance:test/migration");
                require(succeeded.get() && returned.get() == 1, "balance migration function must exist and return 1");
            }
        } catch (Throwable error) {
            failure = error;
            LOGGER.error("[Wicklen: Chronicles server smoke] FAIL during dedicated server checks", error);
        } finally {
            // Let SERVER_STARTED listeners finish and the server enter its tick
            // loop before requesting shutdown from its own thread.
            armed = true;
        }
    }

    private static void verifyLegacyEntity(MinecraftServer server) {
        var level = server.overworld();
        var type = dev.wicklen.chronicles.compat.LegacyCompatibility.legacySpiritType();
        var entity = type.create(level, net.minecraft.world.entity.EntitySpawnReason.LOAD);
        require(entity != null, "legacy entity registry ID must still create the companion");
        var output = net.minecraft.world.level.storage.TagValueOutput.createWithContext(
            net.minecraft.util.ProblemReporter.DISCARDING, server.registryAccess());
        require(entity.save(output), "legacy entity must serialize");
        var tag = output.buildResult();
        var loaded = net.minecraft.world.entity.EntityType.loadEntityRecursive(tag, level,
            new net.minecraft.world.entity.EntitySpawnRequest(net.minecraft.world.entity.EntitySpawnReason.LOAD, false),
            net.minecraft.world.entity.EntityProcessor.NOP);
        require(loaded instanceof dev.wicklen.chronicles.entity.SpiritEntity && loaded.getType() == type,
            "old saved entity ID must deserialize through the compatibility registry");
        entity.discard();
        loaded.discard();
    }

    private void finishAfterTicks(MinecraftServer server) {
        if (!armed || finished || ++ticks < 10) return;
        // A loaded LevelChunk can precede its asynchronous entity-ticking state.
        // Wait for the fixture's actual prerequisite, bounded to ten seconds.
        if (failure == null && !dev.wicklen.chronicles.exploration.ExplorationNativeChecks.ready(server) && ticks < 200) return;
        finished = true;
        try {
            if (failure == null) {
                try { dev.wicklen.chronicles.exploration.ExplorationNativeChecks.verify(server); }
                catch (Throwable error) {
                    failure = error;
                    LOGGER.error("[Wicklen: Chronicles server smoke] FAIL during warmed observation checks", error);
                }
            }
            if (failure == null) {
                try { dev.wicklen.chronicles.terrain.TerrainMapNativeChecks.verify(server); }
                catch (Throwable error) { failure=error; LOGGER.error("Terrain-map native sampling checks failed",error); }
            }
            if (failure == null) {
                try { dev.wicklen.chronicles.encounter.EncounterNativeChecks.verify(server); }
                catch (Throwable error) {
                    failure = error;
                    LOGGER.error("[Wicklen: Chronicles server smoke] FAIL during encounter lifecycle checks", error);
                }
            }
            if (failure == null) {
                try { dev.wicklen.chronicles.firstlight.FirstLightNativeChecks.verify(server); }
                catch (Throwable error) { failure=error; LOGGER.error("First-light persistence/delivery smoke failed",error); }
            }
            if (failure == null) {
                try { dev.wicklen.chronicles.story.StoryHandoffNativeChecks.verify(server); }
                catch (Throwable error) {
                    failure = error;
                    LOGGER.error("[Wicklen: Chronicles server smoke] FAIL during story handoff recovery checks", error);
                }
            }
            if (failure == null && Boolean.getBoolean("wicklen.chronicles.smoke.verifyStations")) {
                try { dev.wicklen.chronicles.station.StationNativeChecks.verify(server); }
                catch (Throwable error) {
                    failure = error;
                    LOGGER.error("[Wicklen: Chronicles server smoke] FAIL during station generation checks", error);
                }
            }
            if (failure == null && Boolean.getBoolean("wicklen.chronicles.smoke.verifyCombat")) {
                try {
                    CombatProfileChecks.verify();
                    dev.wicklen.chronicles.encounter.EncounterNativeChecks.verifyOptionalGateway(server);
                }
                catch (Throwable error) {
                    failure = error;
                    LOGGER.error("[Wicklen: Chronicles server smoke] FAIL during optional combat profile checks", error);
                }
            }
            if (failure == null && Boolean.getBoolean("wicklen.chronicles.smoke.verifyHostileMigration")) {
                try { HostileMigrationChecks.verify(server); }
                catch (Throwable error) {
                    failure = error;
                    LOGGER.error("[Wicklen: Chronicles server smoke] FAIL during optional migration fixture", error);
                }
            }
            if (failure == null) {
                try { dev.wicklen.chronicles.guidance.GuidanceNativeChecks.verify(server); }
                catch (Throwable error) { failure=error; LOGGER.error("Personal guidance native checks failed",error); }
            }
            if (failure == null) {
                try {
                    dev.wicklen.chronicles.ai.SpiritJourneyNativeChecks.verifyReload(server);
                    dev.wicklen.chronicles.ai.SpiritJourneyNativeChecks.verify(server);
                } catch (Throwable error) { failure=error; LOGGER.error("Companion journey memory native checks failed",error); }
            }
            String result = failure == null ? "PASS" : "FAIL " + failure;
            Files.writeString(runDirectory.resolve("smoke-result.txt"), result + System.lineSeparator());
            if (failure == null) LOGGER.info("[Wicklen: Chronicles server smoke] PASS: dedicated server, core quest and world-scoped persistence");
            else LOGGER.error("[Wicklen: Chronicles server smoke] FAIL: {}", failure.toString());
        } catch (IOException error) {
            LOGGER.error("[Wicklen: Chronicles server smoke] FAIL: cannot write result marker", error);
        } finally {
            server.halt(false);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
