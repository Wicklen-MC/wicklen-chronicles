package dev.wicklen.chronicles.client;

import dev.wicklen.chronicles.SpiritBubbles;
import dev.wicklen.chronicles.SpiritManager;
import dev.wicklen.chronicles.ai.SpiritConfig;
import dev.wicklen.chronicles.ai.SpiritQuests;
import dev.wicklen.chronicles.ai.SpiritMemoryStore;
import dev.wicklen.chronicles.ai.WorldStorageContext;
import dev.wicklen.chronicles.core.quest.QuestDefinition;
import dev.wicklen.chronicles.core.quest.QuestStatus;
import dev.wicklen.chronicles.core.quest.QuestType;
import dev.wicklen.chronicles.core.quest.RewardDefinition;
import java.util.UUID;
import dev.wicklen.chronicles.ai.llm.LlmProviders;
import dev.wicklen.chronicles.net.SpiritChatC2SPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import com.sun.net.httpserver.HttpServer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises real entity tracking, S2C bubbles and the G screen in an isolated test world. */
public final class SpiritClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave savedWorld;
        var pendingReward = new AtomicReference<UUID>();
        var worldId = new AtomicReference<UUID>();
        var sessionId = new AtomicReference<UUID>();
        var legacyEntitySave = new AtomicReference<net.minecraft.nbt.CompoundTag>();
        var chosenStory = new AtomicReference<UUID>();
        var camp = new AtomicReference<dev.wicklen.chronicles.research.CampService.Camp>();
        var researchReward = new AtomicReference<UUID>();
        var combatReward = new AtomicReference<UUID>();
        try (var world = context.worldBuilder().create()) {
            savedWorld = world.getWorldSave();
            var server = world.getServer();
            var connection = world.getConnection();
            connection.waitForChunksRender();
            connection.waitForClientboundPackets();
            int joinedSpirit = server.computeOnServer(mcServer -> SpiritManager.findSpirits(
                connection.getServerPlayer().getScoreboardName(), mcServer).getFirst().getId());
            context.runOnClient(client -> require(SpiritSpeechHud.size()>0 && BubbleStore.get(joinedSpirit)==null,
                "welcome uses the speech HUD rather than chat or duplicate 3D bubbles"));
            server.runCommand("time set day");
            server.runCommand("weather clear");
            server.runOnServer(mcServer -> {
                var player = connection.getServerPlayer();
                SpiritManager.ensureSpirit(player);
                var spirits = SpiritManager.findSpirits(player.getScoreboardName(), mcServer);
                require(spirits.size() == 1, "exactly one companion");
                var spirit = spirits.getFirst();
                spirit.snapTo(player.getX(), player.getY() + 1.2, player.getZ() + 3.1);
                spirit.setYRot(180);
                spirit.setYBodyRot(180);
                spirit.setYHeadRot(180);
                player.setYRot(0);
                player.setXRot(0);
                SpiritBubbles.speak(player, "Я здесь. Нажми G — поговорим!");
            });
            connection.waitForClientboundPackets();
            context.waitFor(client -> !DialogHistory.lines().isEmpty());
            verifyGhostInsideSolidBlocks(context, world);
            context.setScreen(SpiritDialogScreen::new);
            context.waitFor(client -> JournalClientState.view()!=null && !JournalClientState.busy());
            context.runOnClient(client -> require(JournalJourney.nextStep().equals("observe"),
                "a fresh journal opens on a concrete first observation, not empty conversation"));
            captureJournal(context,"journal-first-step");
            var languageReload=new AtomicReference<java.util.concurrent.CompletableFuture<Void>>();
            context.runOnClient(client->{
                client.getLanguageManager().setSelected("ru_ru");
                languageReload.set(client.reloadResourcePacks());
            });
            context.waitFor(client->languageReload.get().isDone() && client.gui.overlay()==null);
            context.waitTicks(4);
            context.setScreen(SpiritDialogScreen::new);
            captureJournal(context,"journal-first-step-ru");
            context.setScreen(() -> null);
            verifySpeechHud(context,world);
            verifySpyglassObservation(context, world);
            AnnotationUiChecks.verify(context, world);
            server.runOnServer(mcServer -> SpiritBubbles.speak(connection.getServerPlayer(), "Я здесь. Нажми G — откроем дневник!"));
            connection.waitForClientboundPackets();
            int entityId = server.computeOnServer(mcServer -> SpiritManager.findSpirits(
                connection.getServerPlayer().getScoreboardName(), mcServer).getFirst().getId());
            context.runOnClient(client -> {
                require(SpiritSpeechHud.size()>0 && BubbleStore.get(entityId)==null,"dedicated speech packet reaches the HUD without 3D duplication");
                client.player.setYRot(0);
                client.player.setXRot(0);
            });
            context.waitTicks(3);
            context.takeScreenshot("spirit-model-and-bubble");
            context.setScreen(SpiritDialogScreen::new);
            context.waitTicks(3);
            context.runOnClient(client -> require(client.gui.screen() instanceof SpiritDialogScreen, "G dialog opens"));
            captureJournal(context, "spirit-dialog");
            clickJournal(context, "tab.atlas");
            context.waitFor(client -> JournalClientState.view() != null && !JournalClientState.busy());
            context.runOnClient(client -> require(JournalClientState.view().ownerId().equals(client.player.getUUID()),
                "journal only contains the connected player's read model"));
            context.runOnClient(client -> require(JournalClientState.view().discoveries().stream()
                .anyMatch(entry -> entry.key().id().equals("minecraft:cow")), "real spyglass observation reaches atlas page"));
            captureJournal(context, "journal-atlas-discovery");
            clickJournal(context, "tab.tasks");
            context.waitFor(client -> !JournalClientState.busy() && JournalClientState.response() != null
                && JournalClientState.response().section() == dev.wicklen.chronicles.net.JournalRequestPayload.Section.TASKS);
            captureJournal(context, "journal-tasks-empty");
            clickJournal(context, "tab.conversation");
            verifyDialogue(context, world);
            context.setScreen(() -> null);
            // Readiness never consumes items. Hand-in and reward are explicit, durable actions.
            server.runOnServer(mcServer -> {
                var player = connection.getServerPlayer();
                var cfg = SpiritConfig.get();
                cfg.proactive = false;
                cfg.custom.baseUrl = null;
                cfg.provider = "deepseek";
                LlmProviders.reset();
                var definition = new QuestDefinition("wicklen_chronicles:test/apples", 1,
                    QuestType.COLLECT, "minecraft:apple", 3,
                    new RewardDefinition("minecraft:emerald", 2));
                require(SpiritQuests.issue(player.getUUID(), definition) != null, "deterministic quest issued");
                worldId.set(WorldStorageContext.worldId());
                sessionId.set(WorldStorageContext.sessionId());
            });
            server.runCommand("give @p minecraft:apple 3");
            server.waitFor(mcServer -> SpiritQuests.active(connection.getServerPlayer().getUUID()).status() == QuestStatus.READY, 300);
            context.waitTicks(45);
            server.runOnServer(mcServer -> {
                var player = connection.getServerPlayer();
                require(count(player, net.minecraft.world.item.Items.APPLE) == 3,
                    "ready quest does not automatically consume materials");
                require(!SpiritQuests.turnIn(player, UUID.randomUUID()).success(), "stale hand-in rejected");
                require(count(player, net.minecraft.world.item.Items.APPLE) == 3, "rejected action preserves materials");
            });
            context.setScreen(SpiritDialogScreen::new);
            context.waitFor(client -> JournalClientState.view()!=null && JournalClientState.view().activeQuest()!=null
                && JournalClientState.view().activeQuest().status()==QuestStatus.READY && !JournalClientState.busy());
            context.runOnClient(client->require(JournalJourney.nextStep().equals("submit"),"ready quest becomes the default next action"));
            captureJournal(context,"journey-ready-task");
            clickJournal(context, "tab.tasks");
            context.waitFor(client -> JournalClientState.view() != null && JournalClientState.view().activeQuest() != null
                && JournalClientState.view().activeQuest().status() == QuestStatus.READY && !JournalClientState.busy());
            captureJournal(context, "journal-task-ready");
            clickJournal(context, "hand_over");
            server.waitFor(mcServer -> SpiritQuests.active(connection.getServerPlayer().getUUID()).status() == QuestStatus.REWARD_PENDING);
            server.runOnServer(mcServer -> {
                var player = connection.getServerPlayer();
                var quest = SpiritQuests.active(player.getUUID());
                require(quest.status() == QuestStatus.REWARD_PENDING, "explicit command submits the quest");
                require(count(player, net.minecraft.world.item.Items.APPLE) == 0, "hand-in consumes exact materials");
                pendingReward.set(quest.rewardId());
                require(!SpiritQuests.turnIn(player, quest.id()).success(), "repeated hand-in rejected");
                for (int slot = 0; slot < player.getInventory().getNonEquipmentItems().size(); slot++)
                    player.getInventory().setItem(slot, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE, 64));
                require(!SpiritQuests.claim(player, quest.rewardId()).success(), "full inventory keeps reward pending");
                require(count(player, net.minecraft.world.item.Items.EMERALD) == 0, "no partial delivery with full inventory");
                require(SpiritQuests.active(player.getUUID()).status() == QuestStatus.REWARD_PENDING,
                    "failed delivery does not complete quest");
                player.getInventory().clearContent();
            });
            context.setScreen(() -> null);
            // Repeated summon and dismissal must keep the entity and bubble caches consistent.
            server.runCommand("execute as @p run spirit summon");
            server.runCommand("execute as @p run spirit summon");
            server.runOnServer(mcServer -> require(SpiritManager.findSpirits(
                connection.getServerPlayer().getScoreboardName(), mcServer).size() == 1, "summon is idempotent"));
            server.runCommand("execute as @p run spirit dismiss");
            server.runOnServer(mcServer -> require(SpiritManager.findSpirits(
                connection.getServerPlayer().getScoreboardName(), mcServer).isEmpty(), "dismiss removes the companion"));
            context.waitTicks(45);
            server.runOnServer(mcServer -> require(SpiritManager.findSpirits(
                connection.getServerPlayer().getScoreboardName(), mcServer).isEmpty(), "hidden companion stays dismissed"));
            server.runCommand("execute as @p run spirit summon");
            server.runCommand("tp @p 100 80 100");
            server.waitFor(mcServer -> SpiritManager.findSpirits(connection.getServerPlayer().getScoreboardName(), mcServer)
                .stream().anyMatch(spirit -> spirit.distanceToSqr(connection.getServerPlayer()) < 100));
            server.runCommand("execute as @p in minecraft:the_nether run tp @s 0 80 0");
            server.waitFor(mcServer -> {
                var player = connection.getServerPlayer();
                var spirits = SpiritManager.findSpirits(player.getScoreboardName(), mcServer);
                return spirits.size() == 1 && spirits.getFirst().level() == player.level();
            });
            server.runOnServer(mcServer -> SpiritBubbles.speak(connection.getServerPlayer(), "Даже здесь я рядом."));
            connection.waitForClientboundPackets();
            int netherSpirit = server.computeOnServer(mcServer -> SpiritManager.findSpirits(
                connection.getServerPlayer().getScoreboardName(), mcServer).getFirst().getId());
            context.runOnClient(client -> require(SpiritSpeechHud.texts().stream().anyMatch(t->t.equals("Даже здесь я рядом.")),"speech HUD survives dimension change"));
            // Continue long UI/inventory checks on the flat world's safe surface, not above random Nether lava.
            server.runCommand("execute as @p in minecraft:overworld run tp @s 0.5 -60 0.5");
            server.runOnServer(mcServer -> connection.getServerPlayer().setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO));
            server.runOnServer(mcServer -> {
                var player = connection.getServerPlayer();
                var legacy = dev.wicklen.chronicles.compat.LegacyCompatibility.legacySpiritType()
                    .create(player.level(), net.minecraft.world.entity.EntitySpawnReason.LOAD);
                require(legacy != null, "legacy entity type creates a companion");
                legacy.setOwner(player);
                var output = net.minecraft.world.level.storage.TagValueOutput.createWithContext(
                    net.minecraft.util.ProblemReporter.DISCARDING, mcServer.registryAccess());
                require(legacy.save(output), "legacy entity saves its old registry ID");
                legacyEntitySave.set(output.buildResult());
                legacy.discard();
            });
        }
        context.runOnClient(client->require(SpiritSpeechHud.size()==0,"disconnect clears current and queued speech"));
        try (var reopened = savedWorld.open()) {
            reopened.getServer().waitFor(mcServer -> SpiritManager.findSpirits(
                reopened.getConnection().getServerPlayer().getScoreboardName(), mcServer).size() == 1);
            reopened.getConnection().waitForClientboundPackets();
            context.waitFor(client -> !DialogHistory.lines().isEmpty());
            int spiritId = reopened.getServer().computeOnServer(mcServer -> SpiritManager.findSpirits(
                reopened.getConnection().getServerPlayer().getScoreboardName(), mcServer).getFirst().getId());
            context.runOnClient(client -> require(SpiritSpeechHud.size()>0 && BubbleStore.get(spiritId)==null,"fresh welcome speech after world reopen"));
            reopened.getServer().runOnServer(mcServer -> {
                var player = reopened.getConnection().getServerPlayer();
                require(worldId.get().equals(WorldStorageContext.worldId()), "world ID survives restart");
                require(!sessionId.get().equals(WorldStorageContext.sessionId()), "runtime session changes on reopen");
                var quest = SpiritQuests.active(player.getUUID());
                require(quest != null && quest.status() == QuestStatus.REWARD_PENDING,
                    "pending reward survives restart");
                require(pendingReward.get().equals(quest.rewardId()), "reward identity survives restart");
                // Companion entities normally disappear on disconnect. Load an
                // old serialized fixture after reopening instead of relying on
                // intentionally transient companions surviving disconnect.
                SpiritManager.discardSpirits(player.getScoreboardName(), mcServer);
                var loaded = net.minecraft.world.entity.EntityType.loadEntityRecursive(legacyEntitySave.get(), player.level(),
                    new net.minecraft.world.entity.EntitySpawnRequest(net.minecraft.world.entity.EntitySpawnReason.LOAD, false),
                    net.minecraft.world.entity.EntityProcessor.NOP);
                require(loaded instanceof dev.wicklen.chronicles.entity.SpiritEntity, "old saved entity deserializes after world reopen");
                var legacy = (dev.wicklen.chronicles.entity.SpiritEntity) loaded;
                require(legacy.getOwnerUuid().equals(player.getUUID()), "old saved owner UUID survives reopen");
                legacy.setPos(player.getX(), player.getY() + 1.6, player.getZ());
                require(((net.minecraft.server.level.ServerLevel) player.level()).addFreshEntity(legacy), "old companion can join reopened world");
                SpiritManager.ensureSpirit(player);
                require(SpiritManager.findSpirits(player.getScoreboardName(), mcServer).size() == 1,
                    "legacy and current types share the single-companion rule");
                require(SpiritManager.findSpirits(player.getScoreboardName(), mcServer).getFirst().getType()
                    == dev.wicklen.chronicles.compat.LegacyCompatibility.legacySpiritType(), "legacy companion is reused instead of duplicated");
            });
            context.setScreen(SpiritDialogScreen::new);
            clickJournal(context, "tab.tasks");
            context.waitFor(client -> JournalClientState.view() != null && JournalClientState.view().activeQuest() != null
                && JournalClientState.view().activeQuest().status() == QuestStatus.REWARD_PENDING && !JournalClientState.busy());
            captureJournal(context, "journal-pending-reward");
            clickJournal(context, "claim");
            reopened.getServer().waitFor(mcServer -> SpiritQuests.active(reopened.getConnection().getServerPlayer().getUUID()) == null);
            reopened.getServer().runOnServer(mcServer -> {
                var player = reopened.getConnection().getServerPlayer();
                require(SpiritQuests.active(player.getUUID()) == null, "successful delivery archives quest");
                require(count(player, net.minecraft.world.item.Items.EMERALD) == 2, "exact reward delivered");
                require(!SpiritQuests.claim(player, pendingReward.get()).success(), "replayed reward rejected");
                require(count(player, net.minecraft.world.item.Items.EMERALD) == 2, "replay gives no extra items");
            });
            clickJournal(context, "history");
            context.waitFor(client -> JournalClientState.view() != null && JournalClientState.view().totalHistory() > 0
                && JournalClientState.response().section() == dev.wicklen.chronicles.net.JournalRequestPayload.Section.HISTORY && !JournalClientState.busy());
            captureJournal(context, "journal-quest-history");
            chosenStory.set(verifyStoryChoice(context, reopened));
            completeChosenStoryQuest(context,reopened);
            verifyLantern(context, reopened);
            camp.set(reopened.getServer().computeOnServer(mcServer -> CampGameChecks.verify(reopened.getConnection().getServerPlayer())));
            researchReward.set(verifyResearchMap(context, reopened));
            if (Boolean.getBoolean("wicklen.chronicles.client.verifyCombat")) {
                require(net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("gateway_of_doom"),
                    "opt-in GUI combat requires the verified optional Gateway jar");
                combatReward.set(verifyEncounterCombat(context, reopened));
            } else {
                verifyEncounterUnavailable(context, reopened);
            }
            TerrainMapUiChecks.verify(context, reopened);
            context.setScreen(() -> null);
        }
        try (var reopened = savedWorld.open()) {
            reopened.getServer().runOnServer(mcServer -> {
                var player = reopened.getConnection().getServerPlayer();
                FirstLightUiChecks.verifyReload(player);
                var story = dev.wicklen.chronicles.story.StoryService.view(player);
                require(story.pending() == null && story.history().size() == 1, "story choice survives world restart exactly once");
                var outcome = story.history().getFirst();
                require(outcome.planId().equals(chosenStory.get()), "same story plan is preserved after restart");
                require(SpiritQuests.history(player.getUUID()).stream().anyMatch(q->q.id().equals(outcome.quest().id())
                    && q.status()==QuestStatus.REWARDED), "completed story quest and reward survive restart");
                require(dev.wicklen.chronicles.research.CampService.view(player).equals(camp.get()), "field desk and resupply cursor survive restart");
                require(dev.wicklen.chronicles.research.ResearchService.view(player).snapshot().grants().stream()
                    .anyMatch(grant -> grant.id().equals(researchReward.get()) && grant.claimed()), "research reward acknowledgement survives restart");
                if (combatReward.get() != null) {
                    require(dev.wicklen.chronicles.encounter.EncounterService.knownEncounters(player).stream()
                        .flatMap(view -> view.rewards().stream())
                        .anyMatch(reward -> reward.id().equals(combatReward.get()) && reward.claimed()),
                        "personal combat reward acknowledgement survives restart");
                    require(!dev.wicklen.chronicles.encounter.EncounterService.claim(player, combatReward.get()).success(),
                        "combat reward cannot be claimed again after restart");
                }
                require(dev.wicklen.chronicles.research.ResearchService.claim(player, researchReward.get())
                    == dev.wicklen.chronicles.research.ResearchService.ClaimResult.REJECTED, "research reward cannot be reclaimed after restart");
            });
        }
        FirstLightUiChecks.verifySurvey(context);
    }

    private static UUID verifyEncounterCombat(ClientGameTestContext context,
            net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext world) {
        var server = world.getServer();
        var connection = world.getConnection();
        context.setScreen(() -> null);
        server.runCommand("difficulty normal");
        server.runCommand("time set midnight");
        var place = server.computeOnServer(mcServer ->
            dev.wicklen.chronicles.station.CourtyardGameFixture.createUnobserved(connection.getServerPlayer()));
        var anchor = place.anchor();
        server.runCommand("gamemode survival @p");
        server.runCommand("tp @p " + (anchor.x() + 0.5) + " " + anchor.y() + " " + (anchor.z() + 0.5));
        server.runOnServer(mcServer -> {
            var player = connection.getServerPlayer();
            player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            player.setHealth(player.getMaxHealth());
            // Protect the automated observer while native hostile waves run; mob health/AI/spawning is unchanged.
            player.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                net.minecraft.world.effect.MobEffects.RESISTANCE, 2400, 4));
        });
        connection.waitForClientboundPackets();
        observeCourtyard(context,world,place);
        FirstLightUiChecks.offerAndChooseBattle(context,world);
        context.setScreen(SpiritDialogScreen::new);
        clickJournal(context, "tab.trials");
        context.waitFor(client -> EncounterClientState.data() != null && !EncounterClientState.busy()
            && EncounterClientState.data().courtyards().stream().anyMatch(court -> court.id().equals(place.key().id())));
        context.runOnClient(client -> require(EncounterClientState.data().gatewayAvailable(),
            "opt-in client advertises the actual optional executor"));
        captureJournal(context, "journal-trials-available");
        clickJournal(context, "encounter.normal");
        context.waitFor(client -> !EncounterClientState.busy() && EncounterClientState.data() != null
            && EncounterClientState.data().encounters().stream().anyMatch(row -> row.status().equals("LOBBY")));
        UUID encounter = context.computeOnClient(client -> EncounterClientState.data().encounters().stream()
            .filter(row -> row.status().equals("LOBBY")).findFirst().orElseThrow().id());
        server.runOnServer(mcServer -> {
            var view = dev.wicklen.chronicles.encounter.EncounterService.knownEncounters(connection.getServerPlayer())
                .stream().filter(row -> row.id().equals(encounter)).findFirst().orElseThrow();
            require(view.participants().equals(java.util.List.of(connection.getServerPlayer().getUUID())),
                "GUI preparation records one explicitly consenting participant");
            require(view.status().equals("LOBBY"), "prepare never starts the trial automatically");
        });
        captureJournal(context, "journal-trials-prepared");
        clickJournal(context, "encounter.start");
        context.waitFor(client -> !EncounterClientState.busy() && EncounterClientState.data() != null
            && EncounterClientState.data().encounters().stream().anyMatch(row -> row.id().equals(encounter)
                && row.status().equals("ACTIVE")));
        var frozen=server.computeOnServer(s->dev.wicklen.chronicles.encounter.EncounterService.frozenPlan(connection.getServerPlayer(),encounter));
        require(frozen!=null,"Normal Start persists its exact authored wave plan");
        var escapedMob=new AtomicReference<UUID>();
        server.waitFor(mcServer -> {
            UUID mob=GatewayWaveProbe.forceFirstMobEscape(connection.getServerPlayer(),encounter,place);
            if(mob!=null) {
                GatewayWaveProbe.assertEffectiveZombie(connection.getServerPlayer(),mob,frozen);
                escapedMob.set(mob);
            }
            return mob!=null;
        },200);
        context.waitTicks(40);
        server.runOnServer(mcServer -> {
            var player=connection.getServerPlayer();
            require(GatewayWaveProbe.isAliveInside(player,escapedMob.get(),place),
                "escaped wave mob keeps its identity and returns alive to the courtyard");
            require(dev.wicklen.chronicles.encounter.EncounterService.knownEncounters(player).stream()
                .anyMatch(row->row.id().equals(encounter)&&row.status().equals("ACTIVE")),
                "a moving/escaped mob never cancels the trial");
        });
        context.waitTicks(60);
        var waves = new java.util.HashSet<Integer>();
        var defeated = new java.util.HashSet<UUID>();
        server.waitFor(mcServer -> {
            var player = connection.getServerPlayer();
            GatewayWaveProbe.defeatSpawnedMobs(player, encounter, waves, defeated);
            var view = dev.wicklen.chronicles.encounter.EncounterService.knownEncounters(player).stream()
                .filter(row -> row.id().equals(encounter)).findFirst().orElseThrow();
            require(view.status().equals("ACTIVE") || view.status().equals("SUCCESS"),
                "native Gateway must complete rather than abort/fail: " + view.status());
            return view.status().equals("SUCCESS");
        }, 1400);
        require(waves.size()==frozen.waves().size() && defeated.size()==frozen.totalMobs(),
            "real NORMAL deaths match its frozen adaptive plan; waves="+waves+", deaths="+defeated.size()+", planned="+frozen.totalMobs());
        clickJournal(context, "refresh");
        context.waitFor(client -> !EncounterClientState.busy() && EncounterClientState.data() != null
            && EncounterClientState.data().encounters().stream().anyMatch(row -> row.id().equals(encounter)
                && row.status().equals("SUCCESS") && !row.rewards().isEmpty()));
        var reward = context.computeOnClient(client -> EncounterClientState.data().encounters().stream()
            .filter(row -> row.id().equals(encounter)).findFirst().orElseThrow().rewards().getFirst());
        require(!reward.claimed(), "successful Gateway creates an unclaimed personal entitlement");
        captureJournal(context, "journal-trials-complete");
        int before = server.computeOnServer(mcServer -> count(connection.getServerPlayer(),
            dev.wicklen.chronicles.item.ChroniclesItems.WAYFARER_LANTERN));
        clickJournal(context, "claim");
        context.waitFor(client -> !EncounterClientState.busy() && EncounterClientState.data() != null
            && EncounterClientState.data().encounters().stream().flatMap(row -> row.rewards().stream())
                .anyMatch(value -> value.id().equals(reward.id()) && value.claimed()));
        server.runOnServer(mcServer -> {
            var player = connection.getServerPlayer();
            require(count(player, dev.wicklen.chronicles.item.ChroniclesItems.WAYFARER_LANTERN) == before + 1,
                "GUI claim delivers exactly one real personal relic");
            require(!dev.wicklen.chronicles.encounter.EncounterService.claim(player, reward.id()).success(),
                "replayed combat claim cannot duplicate the relic");
            player.removeEffect(net.minecraft.world.effect.MobEffects.RESISTANCE);
        });
        captureJournal(context, "journal-trials-rewarded");
        useEarnedLantern(context,world);
        FirstLightUiChecks.verifyBattleReward(context,world);
        verifyEmpoweredFollowup(context,world,place,encounter);
        System.out.println("PASS: connected discovery/story/task/reward/courtyard/normal battle/earned lantern use/empowered battle cycle");
        return reward.id();
    }

    private static void completeChosenStoryQuest(ClientGameTestContext context,
            net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext world) {
        var server=world.getServer();var connection=world.getConnection();
        var quest=server.computeOnServer(s->SpiritQuests.active(connection.getServerPlayer().getUUID()).instance());
        require(quest!=null && quest.definition().type()==QuestType.COLLECT,"chosen story delivers a real collection task");
        int before=server.computeOnServer(s->count(connection.getServerPlayer(),net.minecraft.world.item.Items.EMERALD));
        server.runCommand("give @p "+quest.definition().target()+" "+quest.definition().required());
        server.waitFor(s->SpiritQuests.active(connection.getServerPlayer().getUUID()).status()==QuestStatus.READY,300);
        context.setScreen(SpiritDialogScreen::new);
        context.waitFor(client->!JournalClientState.busy() && JournalJourney.nextStep().equals("submit"));
        captureJournal(context,"cycle-story-task-ready");
        clickJournal(context,"journey.submit.action");
        context.waitFor(client->!JournalClientState.busy() && JournalJourney.nextStep().equals("reward"));
        clickJournal(context,"journey.reward.action");
        server.waitFor(s->SpiritQuests.active(connection.getServerPlayer().getUUID())==null);
        server.runOnServer(s->{
            var player=connection.getServerPlayer();
            require(SpiritQuests.history(player.getUUID()).stream().anyMatch(q->q.id().equals(quest.id())&&q.status()==QuestStatus.REWARDED),
                "the exact selected story task reaches the rewarded archive");
            require(count(player,net.minecraft.world.item.Items.EMERALD)==before+quest.definition().reward().count(),
                "story task reward is delivered by the explicit journal action");
        });
        context.waitFor(client->!JournalClientState.busy() && JournalClientState.view()!=null
            &&JournalClientState.view().activeQuest()==null);
        captureJournal(context,"cycle-story-task-rewarded");
    }

    private static void observeCourtyard(ClientGameTestContext context,
            net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext world,
            dev.wicklen.chronicles.station.PlaceManifest place) {
        var server=world.getServer();var connection=world.getConnection();
        server.runOnServer(s->{
            var player=connection.getServerPlayer();
            require(dev.wicklen.chronicles.exploration.ExplorationService.snapshot(player).entries().stream()
                .noneMatch(e->e.key().equals(place.key())),"courtyard starts genuinely unknown to this player");
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SPYGLASS));
            player.inventoryMenu.broadcastChanges();
        });
        connection.waitForClientboundPackets();
        context.getInput().lookAt(place.anchor().blockPos().below().east(2));
        context.getInput().holdKey(options->options.keyUse);
        try {
            context.waitTicks(15);
            context.takeScreenshot("cycle-courtyard-observation");
            server.waitFor(s->dev.wicklen.chronicles.exploration.ExplorationService.snapshot(connection.getServerPlayer()).entries().stream()
                .anyMatch(e->e.key().equals(place.key())),200);
        } finally { context.getInput().releaseKey(options->options.keyUse); }
        server.runOnServer(s->connection.getServerPlayer().stopUsingItem());
    }

    private static void useEarnedLantern(ClientGameTestContext context,
            net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext world) {
        var server=world.getServer();var connection=world.getConnection();
        context.setScreen(()->null);
        server.runOnServer(s->{
            var player=connection.getServerPlayer();
            var item=dev.wicklen.chronicles.item.ChroniclesItems.WAYFARER_LANTERN;
            int slot=-1;
            for(int i=0;i<player.getInventory().getNonEquipmentItems().size();i++)
                if(player.getInventory().getItem(i).is(item)){slot=i;break;}
            require(slot>=0,"actual earned relic is present in inventory");
            var earned=player.getInventory().getItem(slot).copy();
            player.getInventory().setItem(slot,player.getMainHandItem().copy());
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,earned);
            // Earlier isolated cooldown assertions must not masquerade as this earned item's activation.
            player.getCooldowns().removeCooldown(item.builtInRegistryHolder().key().identifier());
            player.removeEffect(net.minecraft.world.effect.MobEffects.ABSORPTION);
            player.removeEffect(net.minecraft.world.effect.MobEffects.SPEED);
            player.inventoryMenu.broadcastChanges();
        });
        connection.waitForClientboundPackets();
        context.getInput().holdKey(options->options.keyUse);
        try {
            server.waitFor(s->connection.getServerPlayer().hasEffect(net.minecraft.world.effect.MobEffects.ABSORPTION)
                &&connection.getServerPlayer().hasEffect(net.minecraft.world.effect.MobEffects.SPEED),100);
            context.takeScreenshot("cycle-earned-lantern-active");
        } finally {context.getInput().releaseKey(options->options.keyUse);}
        server.runOnServer(s->require(connection.getServerPlayer().getCooldowns().isOnCooldown(connection.getServerPlayer().getMainHandItem()),
            "earned relic activation applies its shared cooldown"));
    }

    private static void verifyEmpoweredFollowup(ClientGameTestContext context,
            net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext world,
            dev.wicklen.chronicles.station.PlaceManifest place,UUID previous) {
        var server=world.getServer();var connection=world.getConnection();
        // Return to the known courtyard before starting another fight; knockback may have moved the observer.
        server.runCommand("tp @p "+(place.anchor().x()+0.5)+" "+place.anchor().y()+" "+(place.anchor().z()+0.5));
        server.runOnServer(s->connection.getServerPlayer().setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO));
        // Creative is intentionally allowed for testing; this also catches the previous false mode gate.
        server.runCommand("gamemode creative @p");
        context.setScreen(SpiritDialogScreen::new);
        clickJournal(context,"tab.trials");
        context.waitFor(client->EncounterClientState.data()!=null&&!EncounterClientState.busy());
        clickJournal(context,"encounter.new");
        clickJournal(context,"encounter.empowered");
        context.waitFor(client->EncounterClientState.data()!=null&&!EncounterClientState.busy()
            &&EncounterClientState.data().encounters().stream().anyMatch(r->!r.id().equals(previous)&&r.status().equals("LOBBY")));
        UUID next=context.computeOnClient(client->EncounterClientState.data().encounters().stream()
            .filter(r->!r.id().equals(previous)&&r.status().equals("LOBBY")).findFirst().orElseThrow().id());
        captureJournal(context,"cycle-empowered-prepared");
        clickJournal(context,"encounter.start");
        context.waitFor(client->EncounterClientState.data()!=null&&!EncounterClientState.busy()
            && (EncounterClientState.response().error() || EncounterClientState.data().encounters().stream().anyMatch(r->r.id().equals(next)&&r.status().equals("ACTIVE"))));
        context.runOnClient(client->require(!EncounterClientState.response().error(),
            "Empowered start must explain any denial: "+EncounterClientState.response().feedback()));
        var frozen=server.computeOnServer(s->dev.wicklen.chronicles.encounter.EncounterService.frozenPlan(connection.getServerPlayer(),next));
        require(frozen!=null,"Empowered Start persists its exact authored wave plan");
        var waves=new java.util.HashSet<Integer>();var defeated=new java.util.HashSet<UUID>();
        server.waitFor(s->{
            var player=connection.getServerPlayer();
            GatewayWaveProbe.defeatSpawnedMobs(player,next,waves,defeated);
            var row=dev.wicklen.chronicles.encounter.EncounterService.knownEncounters(player).stream()
                .filter(r->r.id().equals(next)).findFirst().orElseThrow();
            require(row.status().equals("ACTIVE")||row.status().equals("SUCCESS"),"empowered follow-up remains executable in creative: "+row.status());
            return row.status().equals("SUCCESS");
        },1800);
        require(waves.size()==frozen.waves().size() && defeated.size()==frozen.totalMobs(),
            "real Empowered deaths match its frozen plan, without any fixed global mob count");
        clickJournal(context,"refresh");
        context.waitFor(client->EncounterClientState.data()!=null&&!EncounterClientState.busy()
            &&EncounterClientState.data().encounters().stream().anyMatch(r->r.id().equals(next)&&r.status().equals("SUCCESS")));
        captureJournal(context,"cycle-empowered-complete");
    }

    private static void verifyEncounterUnavailable(ClientGameTestContext context,
            net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext world) {
        clickJournal(context, "tab.trials");
        context.waitFor(client -> EncounterClientState.data() != null && !EncounterClientState.busy());
        context.runOnClient(client -> {
            var data = EncounterClientState.data();
            require(!data.gatewayAvailable(), "base client accurately reports the absent optional executor");
            require(data.courtyards().isEmpty(), "server does not advertise the camp fixtures as eligible trial courtyards");
            var names = java.util.Set.of(JournalTexts.tr("encounter.normal").getString(), JournalTexts.tr("encounter.empowered").getString());
            var buttons = client.gui.screen().children().stream()
                .filter(child -> child instanceof net.minecraft.client.gui.components.Button)
                .map(child -> (net.minecraft.client.gui.components.Button) child)
                .filter(button -> names.contains(button.getMessage().getString())).toList();
            require(buttons.size() == 2 && buttons.stream().noneMatch(button -> button.active),
                "both preparation buttons remain honestly disabled without Gateway");
        });
        world.getServer().runOnServer(mcServer -> require(dev.wicklen.chronicles.encounter.EncounterService
            .knownEncounters(world.getConnection().getServerPlayer()).isEmpty(), "reading the encounter page does not start a fight"));
        captureJournal(context, "journal-trials-unavailable");
    }

    private static void captureJournal(ClientGameTestContext context, String name) {
        context.runOnClient(client -> client.gui.toastManager().clear());
        context.waitTicks(2);
        context.takeScreenshot(name);
    }

    private static UUID verifyResearchMap(ClientGameTestContext context,
            net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext world) {
        var server = world.getServer();
        var connection = world.getConnection();
        clickJournal(context, "tab.map");
        context.waitFor(client -> ResearchClientState.data() != null && !ResearchClientState.busy());
        context.runOnClient(client -> {
            var data = ResearchClientState.data();
            require(data.ownerId().equals(client.player.getUUID()), "known-place map belongs to this player");
            require(data.totalKnownPlaces() == 3 && data.places().size() == 3,
                "map contains the three explicitly confirmed camp fixtures and no unknown locations");
            require(data.places().stream().allMatch(entry -> entry.key().kind()
                == dev.wicklen.chronicles.core.discovery.DiscoveryKind.PLACE), "map sends places only");
        });
        var knownPlace=context.computeOnClient(client -> ResearchClientState.data().places().getFirst());
        context.runOnClient(client -> TerrainMapClientState.focus(knownPlace.position().x(),knownPlace.position().z(),256));
        context.waitFor(client -> !TerrainMapClientState.busy() && TerrainMapClientState.page()!=null
            && TerrainMapClientState.page().viewport().centerX()==knownPlace.position().x());
        context.clickScreenButton(context.computeOnClient(client -> JournalTexts.discovery(knownPlace).getString()));
        context.waitTicks(3);
        captureJournal(context, "journal-known-place-map");
        clickJournal(context, "tab.collections");
        context.waitFor(client -> ResearchClientState.data() != null && !ResearchClientState.busy());
        context.clickScreenButton(context.computeOnClient(client -> JournalTexts.tr("research.waypoints").getString()));
        context.waitTicks(3);
        var grant = context.computeOnClient(client -> ResearchClientState.data().series().stream()
            .filter(progress -> progress.definition().id().equals("wicklen_chronicles:waypoints"))
            .findFirst().orElseThrow().grant());
        require(grant != null && !grant.claimed(), "two confirmed places create a durable one-time entitlement");
        var before = new java.util.ArrayList<net.minecraft.world.item.ItemStack>();
        server.runOnServer(mcServer -> {
            var player = connection.getServerPlayer();
            for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++)
                before.add(player.getInventory().getItem(slot).copy());
            for (int slot = 0; slot < player.getInventory().getNonEquipmentItems().size(); slot++)
                player.getInventory().setItem(slot, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE, 64));
        });
        try {
            clickJournal(context, "claim");
            context.waitFor(client -> !ResearchClientState.busy() && ResearchClientState.response().error());
            server.runOnServer(mcServer -> {
                var player = connection.getServerPlayer();
                require(count(player, net.minecraft.world.item.Items.TORCH) == 0, "full inventory receives no partial research reward");
                require(dev.wicklen.chronicles.research.ResearchService.view(player).snapshot().grants().stream()
                    .anyMatch(value -> value.id().equals(grant.id()) && !value.claimed()), "full inventory keeps research grant pending");
                player.getInventory().clearContent();
            });
            captureJournal(context, "journal-research-pending");
            clickJournal(context, "claim");
            context.waitFor(client -> !ResearchClientState.busy() && !ResearchClientState.response().error());
            server.runOnServer(mcServer -> {
                var player = connection.getServerPlayer();
                require(count(player, net.minecraft.world.item.Items.TORCH) == 12, "native journal action delivers the exact research reward");
                require(dev.wicklen.chronicles.research.ResearchService.claim(player, grant.id())
                    == dev.wicklen.chronicles.research.ResearchService.ClaimResult.REJECTED, "replayed research claim is rejected");
                require(count(player, net.minecraft.world.item.Items.TORCH) == 12, "replay grants no extra supplies");
            });
            captureJournal(context, "journal-research-complete");
        } finally {
            server.runOnServer(mcServer -> {
                var player = connection.getServerPlayer();
                for (int slot = 0; slot < before.size(); slot++) player.getInventory().setItem(slot, before.get(slot));
                player.getInventory().setChanged();
                player.inventoryMenu.broadcastChanges();
            });
        }
        return grant.id();
    }

    private static void verifyLantern(ClientGameTestContext context,
            net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext world) {
        context.runOnClient(client -> require(client.getResourceManager().getResource(
            net.minecraft.resources.Identifier.parse("wicklen_chronicles:items/wayfarer_lantern.json")).isPresent(),
            "wayfarer lantern has its real client item definition"));
        world.getServer().runOnServer(mcServer -> {
            var player = world.getConnection().getServerPlayer();
            var main = player.getMainHandItem().copy();
            var off = player.getOffhandItem().copy();
            try {
                var item = dev.wicklen.chronicles.item.ChroniclesItems.WAYFARER_LANTERN;
                var first = new net.minecraft.world.item.ItemStack(item);
                var copy = new net.minecraft.world.item.ItemStack(item);
                player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, first);
                require(item.use(player.level(), player, net.minecraft.world.InteractionHand.MAIN_HAND)
                    == net.minecraft.world.InteractionResult.SUCCESS, "lantern can be activated");
                require(player.hasEffect(net.minecraft.world.effect.MobEffects.ABSORPTION)
                    && player.hasEffect(net.minecraft.world.effect.MobEffects.SPEED), "lantern applies the declared defensive/mobility effects");
                require(player.getCooldowns().isOnCooldown(copy), "a second copy shares the same cooldown");
                player.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, copy);
                require(item.use(player.level(), player, net.minecraft.world.InteractionHand.OFF_HAND)
                    == net.minecraft.world.InteractionResult.FAIL, "swapping copies cannot bypass lantern cooldown");
            } finally {
                player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, main);
                player.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, off);
                player.removeEffect(net.minecraft.world.effect.MobEffects.ABSORPTION);
                player.removeEffect(net.minecraft.world.effect.MobEffects.SPEED);
            }
        });
    }

    private static void verifySpyglassObservation(ClientGameTestContext context,
            net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext world) {
        var server=world.getServer();var connection=world.getConnection();
        var oldHand=new AtomicReference<net.minecraft.world.item.ItemStack>();
        var cowId=new AtomicReference<UUID>();
        var target=new AtomicReference<net.minecraft.core.BlockPos>();
        server.runOnServer(mcServer->{
            var player=connection.getServerPlayer();
            var type = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                .getValue(net.minecraft.resources.Identifier.parse("minecraft:cow"));
            var cow = (net.minecraft.world.entity.Mob) type.create(player.level(),net.minecraft.world.entity.EntitySpawnReason.COMMAND);
            require(cow!=null,"cow fixture created");
            cow.setNoAi(true);cow.setNoGravity(true);
            cow.setPos(player.getX(),player.getEyeY()-0.8,player.getZ()+6);
            require(player.level().addFreshEntity(cow),"cow fixture joined world");
            cowId.set(cow.getUUID());target.set(cow.blockPosition().above());
            oldHand.set(player.getMainHandItem().copy());
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SPYGLASS));
            player.inventoryMenu.broadcastChanges();
        });
        try {
            connection.waitForClientboundPackets();
            context.getInput().lookAt(target.get());
            context.getInput().holdKey(options -> options.keyUse);
            context.waitTicks(15);
            context.takeScreenshot("spyglass-target-progress");
            server.waitFor(mcServer->dev.wicklen.chronicles.exploration.ExplorationService.snapshot(connection.getServerPlayer())
                .entries().stream().anyMatch(entry->entry.key().id().equals("minecraft:cow")),200);
        } finally {
            context.getInput().releaseKey(options -> options.keyUse);
            server.runOnServer(mcServer->{
                var player=connection.getServerPlayer();player.stopUsingItem();
                player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,oldHand.get());
                player.inventoryMenu.broadcastChanges();
                var cow=player.level().getEntity(cowId.get());if(cow!=null)cow.discard();
            });
        }
    }

    private static UUID verifyStoryChoice(ClientGameTestContext context,
            net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext world) {
        var server=world.getServer();var connection=world.getConnection();
        var requests = new AtomicInteger();
        var source = new AtomicReference<JsonObject>();
        HttpServer mock;
        try { mock = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); }
        catch (java.io.IOException error) { throw new RuntimeException(error); }
        mock.createContext("/chat/completions", exchange -> {
            var request = new Gson().fromJson(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), JsonObject.class);
            var messages = request.getAsJsonArray("messages");
            var contextData = new Gson().fromJson(messages.get(messages.size()-1).getAsJsonObject().get("content").getAsString(), JsonObject.class);
            source.set(contextData.getAsJsonObject("source"));
            var goals = contextData.getAsJsonArray("goals");
            var choices = new com.google.gson.JsonArray();
            for (int i = 0; i < 2; i++) {
                var choice = new JsonObject();
                choice.addProperty("id", i == 0 ? "records" : "supplies");
                choice.addProperty("label", i == 0 ? "Keep a field record" : "Prepare an expedition");
                choice.addProperty("consequencePreview", i == 0 ? "Your observation becomes a page in the field record."
                    : "You contribute supplies for the next expedition.");
                choice.addProperty("goalId", goals.get(i).getAsJsonObject().get("id").getAsString());
                choices.add(choice);
            }
            var plan = new JsonObject();
            plan.addProperty("title", "A quiet field");
            plan.addProperty("motive", "The cow you observed belongs in your field chronicle. Choose what to prepare next.");
            plan.add("choices", choices);
            var envelope = new JsonObject();
            var replies = new com.google.gson.JsonArray();
            var message = new JsonObject(); message.addProperty("content", new Gson().toJson(plan));
            var reply = new JsonObject(); reply.add("message", message); replies.add(reply); envelope.add("choices", replies);
            requests.incrementAndGet();
            byte[] body = new Gson().toJson(envelope).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        mock.start();
        server.runOnServer(mcServer -> {
            var cfg = SpiritConfig.get(); cfg.provider = "custom";
            cfg.custom.baseUrl = "http://127.0.0.1:" + mock.getAddress().getPort();
            cfg.custom.model = "mock-story"; cfg.custom.apiKey = null; cfg.proactive = false;
            LlmProviders.reset();
        });
        try {
        clickJournal(context,"stories");
        context.waitFor(client->StoryClientState.story()!=null&&!StoryClientState.busy());
        clickJournal(context,"story_request");
        context.waitFor(client->StoryClientState.story()!=null&&StoryClientState.story().pending()!=null&&!StoryClientState.busy());
        var plan=context.computeOnClient(client->StoryClientState.story().pending());
        require(requests.get() == 1 && plan.title().equals("A quiet field"), "typed story comes from the actual mock provider response");
        require(source.get().getAsJsonObject("key").get("id").getAsString().equals("minecraft:cow"),
            "LLM receives the real confirmed discovery");
        require(plan.choices().size()==2,"story offers exactly two declared choices");
        require(!plan.choices().get(0).consequencePreview().equals(plan.choices().get(1).consequencePreview()),"choices expose different consequences");
        captureJournal(context, "journal-story-choices");
        clickJournal(context,"story_choose");
        server.waitFor(mcServer->!dev.wicklen.chronicles.story.StoryService.view(connection.getServerPlayer()).history().isEmpty());
        server.runOnServer(mcServer->{
            var player=connection.getServerPlayer();
            var story=dev.wicklen.chronicles.story.StoryService.view(player);
            var outcome=story.history().getLast();
            require(story.pending()==null&&outcome.planId().equals(plan.id()),"chosen plan becomes durable history");
            require(outcome.choiceId().equals(plan.choices().getFirst().id()),"visible selected path is committed");
            require(SpiritQuests.active(player.getUUID()).id().equals(outcome.quest().id()),"story delivers the exact saved quest");
            require(!dev.wicklen.chronicles.story.StoryService.choose(player,plan.id(),outcome.choiceId()).success(),"replayed story choice gives no second quest");
        });
        context.waitFor(client->StoryClientState.story()!=null&&StoryClientState.story().pending()==null&&!StoryClientState.busy());
        captureJournal(context, "journal-story-recorded");
        return plan.id();
        } finally {
            mock.stop(0);
        }
    }

    private static void clickJournal(ClientGameTestContext context, String key) {
        context.waitTicks(5); // Native requests are throttled; human-sized separation also tests the visible button.
        String label = context.computeOnClient(client -> JournalTexts.tr(key).getString());
        context.waitFor(client -> client.gui.screen() instanceof SpiritDialogScreen);
        context.clickScreenButton(label);
        context.waitTicks(3);
    }

    private static void verifyGhostInsideSolidBlocks(ClientGameTestContext context,
            net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext world) {
        var server = world.getServer();
        var connection = world.getConnection();
        var oldBlocks = new java.util.HashMap<net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState>();
        var position = new AtomicReference<net.minecraft.world.phys.Vec3>();
        var health = new AtomicReference<Float>();
        var spiritId = new AtomicReference<UUID>();
        server.runOnServer(mcServer -> {
            var player = connection.getServerPlayer();
            var spirit = SpiritManager.findSpirits(player.getScoreboardName(), mcServer).getFirst();
            spiritId.set(spirit.getUUID());
            position.set(spirit.position());
            health.set(spirit.getHealth());
            var center = spirit.blockPosition();
            for (int dx=-1;dx<=1;dx++) for (int dy=-1;dy<=1;dy++) for (int dz=-1;dz<=1;dz++) {
                var pos = center.offset(dx,dy,dz).immutable();
                oldBlocks.put(pos,spirit.level().getBlockState(pos));
                spirit.level().setBlock(pos,net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(),3);
            }
            spirit.setPos(center.getX()+0.5,center.getY()+0.5,center.getZ()+0.5);
        });
        try {
            context.waitTicks(45);
            server.runOnServer(mcServer -> {
                var spirit = (dev.wicklen.chronicles.entity.SpiritEntity)
                    connection.getServerPlayer().level().getEntity(spiritId.get());
                require(spirit != null && spirit.isAlive(), "ghost survives solid blocks");
                require(spirit.noPhysics && spirit.isSilent(), "ghost remains silent and non-colliding after ticks");
                require(spirit.getHealth()==health.get(), "solid blocks do not cause suffocation damage");
            });
        } finally {
            server.runOnServer(mcServer -> {
                var level = connection.getServerPlayer().level();
                oldBlocks.forEach((pos,state) -> level.setBlock(pos,state,3));
                var entity = level.getEntity(spiritId.get());
                if (entity instanceof dev.wicklen.chronicles.entity.SpiritEntity spirit) {
                    spirit.setPos(position.get());
                    spirit.setHealth(health.get());
                }
            });
        }
    }

    private static void verifySpeechHud(ClientGameTestContext context,
            net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext world) {
        String spoken="Давай начнём с окрестностей: запишем, кого встретим, и отметим места, куда захочется вернуться.";
        context.runOnClient(client->{
            SpiritSpeechHud.clear();
            var queue=new SpiritSpeechQueue();
            queue.offer("Дух",0x86C9B0,"first",2500,1000);
            queue.advance(1000,true);
            require(queue.current()!=null && queue.current().opacity(1200)==1,"speech fades in within a bounded interval");
            for(int i=0;i<8;i++)queue.offer("Дух",0x86C9B0,"queued"+i,2500,1100);
            require(queue.size()==3 && queue.current().speech().text().equals("first"),"bounded queue never interrupts current speech");
            queue.advance(3501,true);
            require(queue.current()!=null && !queue.current().speech().text().equals("first"),"expired speech advances to the next card");
            queue.clear();queue.offer("Дух",0,"stale",2500,1000);queue.advance(32000,false);
            require(queue.size()==0,"stale speech is discarded while the HUD cannot be shown");
            for(int width:new int[]{320,427,640}) {
                var b=SpiritSpeechHud.bounds(width,240,4);
                require(b.x()+b.width()<width/2 && b.y()+b.height()<=240-92,"speech avoids crosshair, actionbar and hotbar at supported GUI scales");
            }
        });
        world.getServer().runOnServer(server->SpiritBubbles.speak(world.getConnection().getServerPlayer(),spoken));
        world.getConnection().waitForClientboundPackets();
        context.waitFor(client->SpiritSpeechHud.texts().contains(spoken));
        context.runOnClient(client->{
            require(DialogHistory.lines().stream().anyMatch(line->line.text().equals(spoken)),"full HUD speech is retained in journal history");
            require(!chatContains(client,spoken),"spoken companion lines are never copied into chat");
        });
        context.waitTicks(8);
        context.takeScreenshot(net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions.of("spirit-speech-hud").withSize(800,480));
        context.runOnClient(client->{client.gui.hud.toggle();require(!SpiritSpeechHud.displayAllowed(client),"Hide HUD hides the speech card");});
        context.takeScreenshot(net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions.of("spirit-speech-hud-hidden").withSize(800,480));
        context.runOnClient(client->client.gui.hud.toggle());
        context.setScreen(SpiritDialogScreen::new);
        context.runOnClient(client->require(!SpiritSpeechHud.displayAllowed(client),"journal and menus do not receive a duplicate HUD card"));
        context.setScreen(()->null);
        context.runOnClient(client->SpiritSpeechHud.clear());
    }
    private static boolean chatContains(net.minecraft.client.Minecraft client,String text) {
        try {
            var state=client.gui.hud.getChat().storeState();
            var field=state.getClass().getDeclaredField("messages");field.setAccessible(true);
            var messages=(java.util.List<?>)field.get(state);
            return messages.stream().map(message->((net.minecraft.client.multiplayer.chat.GuiMessage)message).content().getString())
                .anyMatch(content->content.contains(text));
        } catch(ReflectiveOperationException error) {throw new AssertionError("Could not inspect native chat state",error);}
    }

    private static void verifyDialogue(ClientGameTestContext context,
            net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext world) {
        var server = world.getServer();
        var connection = world.getConnection();
        var requests = new AtomicInteger();
        var responseStatus = new AtomicInteger(200);
        var firstReply = new CountDownLatch(1);
        var requestBody = new AtomicReference<JsonObject>();
        HttpServer mock;
        try { mock = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); }
        catch (java.io.IOException error) { throw new RuntimeException(error); }
        mock.createContext("/chat/completions", exchange -> {
            requestBody.set(new Gson().fromJson(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), JsonObject.class));
            int number = requests.incrementAndGet();
            try {
                if (number == 1 && !firstReply.await(10, TimeUnit.SECONDS)) throw new AssertionError("mock reply was never released");
            } catch (InterruptedException error) { throw new RuntimeException(error); }
            byte[] body = "{\"choices\":[{\"message\":{\"content\":\"Я слышу тебя. Я рядом!\"}}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(responseStatus.get(), body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        mock.start();
        try {
            server.runOnServer(mcServer -> {
                var cfg = SpiritConfig.get();
                cfg.provider = "custom";
                cfg.custom.baseUrl = "http://127.0.0.1:" + mock.getAddress().getPort();
                cfg.custom.model = "mock-model";
                cfg.custom.apiKey = null;
                cfg.maxTokens = 222;
                cfg.cooldownMs = 0;
                cfg.proactive = false;
                LlmProviders.reset();
            });
            int memoryBefore = server.computeOnServer(mcServer -> SpiritMemoryStore.turns(connection.getServerPlayer().getUUID()).size());
            context.runOnClient(client -> {
                ClientPlayNetworking.send(new SpiritChatC2SPayload("Ты здесь?"));
                ClientPlayNetworking.send(new SpiritChatC2SPayload("Второй вопрос подряд"));
            });
            context.waitFor(client -> requests.get() == 1);
            context.waitFor(client -> DialogHistory.lines().stream().anyMatch(line -> line.text().contains("предыдущий вопрос")));
            firstReply.countDown();
            context.waitFor(client -> DialogHistory.lines().stream().anyMatch(line -> line.text().equals("Я слышу тебя. Я рядом!")));
            captureJournal(context, "spirit-dialog-conversation");
            require(requests.get() == 1, "one in-flight HTTP call despite two C2S questions");
            require(requestBody.get().get("max_tokens").getAsInt() == 222, "configured token budget reaches LLM");
            server.runOnServer(mcServer -> {
                require(SpiritMemoryStore.turns(connection.getServerPlayer().getUUID()).size() == Math.min(8, memoryBefore + 1),
                    "only the answered question enters memory");
            });
            responseStatus.set(503);
            context.runOnClient(client -> ClientPlayNetworking.send(new SpiritChatC2SPayload("Проверка ошибки")));
            context.waitFor(client -> DialogHistory.lines().stream().anyMatch(line -> line.text().contains("улетел за мыслями")));
            server.runOnServer(mcServer -> require(SpiritMemoryStore.turns(connection.getServerPlayer().getUUID()).size() == Math.min(8, memoryBefore + 1),
                "HTTP errors must not pollute conversation memory"));
        } finally {
            firstReply.countDown();
            mock.stop(0);
        }
    }

    private static int count(net.minecraft.server.level.ServerPlayer player, net.minecraft.world.item.Item item) {
        int count = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            var stack = player.getInventory().getItem(slot);
            if (stack.is(item)) count += stack.getCount();
        }
        return count;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
