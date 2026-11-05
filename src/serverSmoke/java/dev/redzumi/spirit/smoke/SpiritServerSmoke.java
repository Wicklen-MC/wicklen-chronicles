package dev.redzumi.spirit.smoke;

import dev.redzumi.spirit.ai.SpiritQuests;
import dev.redzumi.spirit.ai.WorldStorageContext;
import dev.redzumi.spirit.core.quest.QuestDefinition;
import dev.redzumi.spirit.core.quest.QuestStatus;
import dev.redzumi.spirit.core.quest.QuestType;
import dev.redzumi.spirit.core.quest.RewardDefinition;
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
    private static final Logger LOGGER = LoggerFactory.getLogger("redzspirit-server-smoke");
    private Path runDirectory;
    private Throwable failure;
    private boolean armed;
    private boolean finished;
    private int ticks;

    @Override
    public void onInitialize() {
        // Even if this source set is accidentally visible to another dev run,
        // it must never stop that server or modify its world for testing.
        if (!Boolean.getBoolean("redzspirit.serverSmoke")) return;
        String configuredDirectory = System.getProperty("redzspirit.serverSmoke.directory");
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
            require(storage.equals(worldDirectory.resolve("redzspirit")), "progress must be scoped to the active world");

            // A new dummy owner on each isolated run also permits rerunning the
            // smoke test without deleting its previously generated test world.
            UUID owner = UUID.randomUUID();
            var definition = new QuestDefinition("redzspirit:smoke/collection", 1,
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
        } catch (Throwable error) {
            failure = error;
            LOGGER.error("[redzspirit-server-smoke] FAIL during dedicated server checks", error);
        } finally {
            // Let SERVER_STARTED listeners finish and the server enter its tick
            // loop before requesting shutdown from its own thread.
            armed = true;
        }
    }

    private void finishAfterTicks(MinecraftServer server) {
        if (!armed || finished || ++ticks < 2) return;
        finished = true;
        try {
            String result = failure == null ? "PASS" : "FAIL " + failure;
            Files.writeString(runDirectory.resolve("smoke-result.txt"), result + System.lineSeparator());
            if (failure == null) LOGGER.info("[redzspirit-server-smoke] PASS: dedicated server, core quest and world-scoped persistence");
            else LOGGER.error("[redzspirit-server-smoke] FAIL: {}", failure.toString());
        } catch (IOException error) {
            LOGGER.error("[redzspirit-server-smoke] FAIL: cannot write result marker", error);
        } finally {
            server.halt(false);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
