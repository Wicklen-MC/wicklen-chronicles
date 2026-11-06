package dev.wicklen.chronicles.encounter;

import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import dev.wicklen.chronicles.ai.WorldStorageContext;
import dev.wicklen.chronicles.core.discovery.DiscoveryKey;
import dev.wicklen.chronicles.core.discovery.DiscoveryKind;
import dev.wicklen.chronicles.core.encounter.*;
import dev.wicklen.chronicles.core.quest.RewardDefinition;
import dev.wicklen.chronicles.station.PlaceManifest;
import dev.wicklen.chronicles.station.StationVariant;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Isolated fixtures only. No production command can finish encounters or inject these sessions. */
public final class EncounterNativeChecks {
    private static final String PREFIX = "de.mrstilu2k6.gateway_of_doom.";
    private static final RewardDefinition RELIC = new RewardDefinition("wicklen_chronicles:wayfarer_lantern", 1);
    private EncounterNativeChecks() {}

    public static void verify(MinecraftServer server) throws Exception {
        require(Boolean.getBoolean("wicklen_chronicles.serverSmoke"), "encounter fixtures require isolated server smoke");
        var runtime = EncounterService.state(server);
        if (!FabricLoader.getInstance().isModLoaded("gateway_of_doom")) require(!runtime.bridge.available(), "missing optional executor is supported");
        var level = server.overworld();
        UUID owner = UUID.randomUUID(), id = UUID.randomUUID(), portal = UUID.randomUUID();
        var place = fixturePlace(id);
        long now = System.currentTimeMillis();
        var active = EncounterTransitions.start(EncounterTransitions.prepare(id, WorldStorageContext.worldId(), place.key(),
                EncounterMode.NORMAL, List.of(owner), RELIC, now), owner, id, now).instance();
        var session = new EncounterSession(id, owner, place, EncounterMode.NORMAL, List.of(owner), active, portal, now, now + 60_000);
        runtime.ledger.put(session);
        runtime.completion(new GatewayBridge.Completion(server, place.dimension(), UUID.randomUUID(), GatewayBridge.sourceId(id), "COMPLETED"));
        require(runtime.find(id).active(), "wrong portal identity cannot create completion rewards");
        runtime.completion(new GatewayBridge.Completion(server, "minecraft:the_nether", portal, GatewayBridge.sourceId(id), "COMPLETED"));
        require(runtime.find(id).active(), "wrong dimension cannot complete an encounter");
        runtime.completion(new GatewayBridge.Completion(server, place.dimension(), portal, GatewayBridge.sourceId(id), "FAILED"));
        require(runtime.find(id).encounter().status() == EncounterStatus.FAILURE, "matching executor callback persists failure");
        runtime.completion(new GatewayBridge.Completion(server, place.dimension(), portal, GatewayBridge.sourceId(id), "COMPLETED"));
        require(runtime.find(id).encounter().rewards().isEmpty(), "later completion cannot rewrite failed history");

        UUID interruptedId = UUID.randomUUID();
        var interrupted = EncounterTransitions.start(EncounterTransitions.prepare(interruptedId, WorldStorageContext.worldId(), place.key(),
                EncounterMode.NORMAL, List.of(owner), RELIC, now), owner, interruptedId, now).instance();
        runtime.ledger.put(new EncounterSession(interruptedId, owner, place, EncounterMode.NORMAL, List.of(owner), interrupted,
                UUID.randomUUID(), now, now + 60_000));
        runtime.recover();
        require(runtime.find(interruptedId).encounter().status() == EncounterStatus.ABORTED
                && runtime.find(interruptedId).encounter().rewards().isEmpty(), "restart recovery aborts active execution without an award");

        UUID rewardEncounter = UUID.randomUUID();
        var running = EncounterTransitions.start(EncounterTransitions.prepare(rewardEncounter, WorldStorageContext.worldId(), place.key(),
                EncounterMode.NORMAL, List.of(owner), RELIC, now), owner, rewardEncounter, now).instance();
        var success = EncounterTransitions.finish(running, rewardEncounter, EncounterStatus.SUCCESS, now).instance();
        runtime.ledger.put(new EncounterSession(rewardEncounter, owner, place, EncounterMode.NORMAL, List.of(owner), success,
                UUID.randomUUID(), now, now + 60_000));
        var player = new ServerPlayer(server, level, new GameProfile(owner, "EncounterProbe"), ClientInformation.createDefault());
        for (int slot = 0; slot < player.getInventory().getNonEquipmentItems().size(); slot++)
            player.getInventory().setItem(slot, new ItemStack(Items.STONE, 64));
        var grant = success.rewards().getFirst();
        require(!EncounterService.claim(player, grant.id()).success(), "full inventory cannot partially deliver encounter reward");
        require(!runtime.find(rewardEncounter).encounter().rewards().getFirst().claimed(), "full inventory keeps entitlement pending");
        player.getInventory().clearContent();
        require(EncounterService.claim(player, grant.id()).success(), "explicit claim delivers the authored relic");
        require(countRelics(player) == 1, "exactly one relic reaches the inventory");
        require(!EncounterService.claim(player, grant.id()).success() && countRelics(player) == 1, "duplicate claim gives no second relic");
        var encoded = JsonParser.parseString(Files.readString(WorldStorageContext.directory().resolve("encounters.json"))).getAsJsonObject();
        require(encoded.getAsJsonObject("data").has(rewardEncounter.toString()), "encounter result is saved in the active world");
        System.out.println("PASS: encounter optional absence, strict callbacks, restart abort, full inventory and one-time relic delivery");
    }

    public static void verifyOptionalGateway(MinecraftServer server) throws Exception {
        require(Boolean.getBoolean("wicklen.chronicles.smoke.verifyCombat"), "real Gateway probe requires explicit isolated combat flag");
        var runtime = EncounterService.state(server);
        require(runtime.bridge.available(), "Gateway public API must initialize: " + runtime.bridge.unavailableReason());
        Class<?> configClass = Class.forName(PREFIX + "config.GatewayConfig");
        Object config = configClass.getMethod("getInstance").invoke(null);
        String before = (String) configClass.getMethod("exportToJson").invoke(config);
        for (EncounterMode mode : EncounterMode.values()) {
            require(Boolean.TRUE.equals(configClass.getMethod("hasProfile", String.class).invoke(config, GatewayBridge.profileId(mode))),
                    "reserved profile exists before the API evaluates its snapshot");
            String snapshot = GatewayBridge.authoredSnapshot(mode, 2);
            Object profile = configClass.getMethod("parseProfileSnapshot", String.class).invoke(config, snapshot);
            var profileClass = profile.getClass();
            require(((List<?>) profileClass.getMethod("getWaves").invoke(profile)).size() == 2, "authored snapshot uses exactly two waves");
            Object boss = profileClass.getMethod("getBoss").invoke(profile);
            require(Boolean.FALSE.equals(boss.getClass().getMethod("isEnabled").invoke(boss)), "authored encounter has no phased boss");
            require(((List<?>) profileClass.getMethod("getRewards").invoke(profile)).isEmpty(), "Gateway does not own item rewards");
            require(((Number) profileClass.getMethod("getCompletionXp").invoke(profile)).intValue() == 0, "Gateway completion XP disabled");
            require(Boolean.FALSE.equals(profileClass.getMethod("isWaveMobVanillaLootEnabled").invoke(profile)), "vanilla wave loot disabled");
        }
        var place = dev.wicklen.chronicles.station.StationNativeChecks.courtyardForCombat();
        var level = server.overworld();
        UUID owner = UUID.randomUUID(), id = UUID.randomUUID();
        long now = System.currentTimeMillis();
        var encounter = EncounterTransitions.start(EncounterTransitions.prepare(id, WorldStorageContext.worldId(), place.key(),
                EncounterMode.NORMAL, List.of(owner), RELIC, now), owner, id, now).instance();
        var session = new EncounterSession(id, owner, place, EncounterMode.NORMAL, List.of(owner), encounter, null,
                now, now + 60_000);
        runtime.ledger.put(session);
        var anchor = place.anchor();
        GatewayBridge.OpenResult opened = runtime.bridge.open(encounter, owner, level, new Vec3(anchor.x() + 0.5, anchor.y(), anchor.z() + 0.5));
        require(opened.success(), "real Gateway API opens at generated courtyard anchor: " + opened.failure());
        runtime.ledger.put(session.withEncounter(encounter, opened.portalId(), session.deadlineAt()));
        try {
            Class<?> api = Class.forName(PREFIX + "api.GatewayApi");
            var found = (Optional<?>) api.getMethod("find", UUID.class).invoke(null, opened.portalId());
            require(found.isPresent(), "real portal is registered in Gateway API");
            var portal = (Entity) found.orElseThrow();
            require(GatewayBridge.sourceId(id).equals(runtime.bridge.sourceOf(portal)), "actual portal carries our source identity");
            verifyEscapingMob(runtime, runtime.find(id), level);
            runtime.bridge.closeSource(id);
            var state = (Enum<?>) portal.getClass().getMethod("getGatewayState").invoke(portal);
            require(portal.isRemoved() || state.name().equals("CLOSING"), "our portal closes through the public API");
            require(((List<?>) portal.getClass().getMethod("trackedMobIds").invoke(portal)).isEmpty(), "closing leaves no owned wave mobs");
        } finally { runtime.abort(runtime.find(id), "Isolated Gateway API probe finished"); }
        String after = (String) configClass.getMethod("exportToJson").invoke(config);
        require(before.equals(after), "instance snapshots do not overwrite user/global Gateway profiles");
        var managed = JsonParser.parseString(after).getAsJsonObject();
        require(managed.get("schemaVersion").getAsInt() == 29, "managed Gateway export must use verified schema 29");
        var authored = new com.google.gson.JsonObject();
        var profiles = managed.getAsJsonObject("profiles");
        for (EncounterMode mode : EncounterMode.values()) {
            String profileId = GatewayBridge.profileId(mode);
            require(profiles.has(profileId), "authored profile must exist for managed export");
            authored.add(profileId, profiles.get(profileId).deepCopy());
        }
        managed.add("profiles", authored);
        var automatic = new com.google.gson.JsonObject();
        automatic.addProperty("enabled", false);
        automatic.add("rules", new com.google.gson.JsonArray());
        managed.add("automaticGateways", automatic);
        managed.addProperty("mobDropsEnabled", false);
        managed.addProperty("creativeAutomaticGatewaysEnabled", false);
        managed.addProperty("portalContentSetupComplete", true);
        var export = FabricLoader.getInstance().getGameDir().resolve("wicklen-gateway-profile.json");
        Files.writeString(export, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(managed));
        System.out.println("PASS: actual optional Gateway API profiles/snapshot/open/source/close with own rewards disabled");
    }

    private static void verifyEscapingMob(EncounterService.RuntimeState runtime, EncounterSession session,
                                         net.minecraft.server.level.ServerLevel level) {
        var type = BuiltInRegistries.ENTITY_TYPE.get(Identifier.parse("minecraft:zombie")).orElseThrow().value();
        var mob = (net.minecraft.world.entity.Mob) type.create(level, net.minecraft.world.entity.EntitySpawnReason.MOB_SUMMONED);
        require(mob != null, "native containment fixture creates a real hostile mob");
        var arena = session.place().arena();
        var anchor = session.place().anchor();
        mob.setPos(anchor.x() + 0.5, anchor.y(), anchor.z() + 0.5);
        level.addFreshEntity(mob);
        try {
            // Repeated knockback/AI escapes must preserve this mob and the encounter identity.
            runtime.containMob(session, level, mob);
            for (int attempt = 0; attempt < 10; attempt++) {
                mob.teleportTo(arena.minX() - 4.5, arena.minY(), anchor.z() + 0.5);
                mob.setDeltaMovement(-0.7, 0.2, 0);
                runtime.containMob(session, level, mob);
                require(arena.contains(mob.blockPosition()), "escaped wave mob is returned inside the courtyard");
                require(mob.getDeltaMovement().lengthSqr() == 0 && mob.isAlive(), "return clears knockback without killing the mob");
                require(level.noBlockCollision(mob, mob.getBoundingBox()), "return never puts a wave mob into a wall");
                require(runtime.find(session.id()).active(), "mob movement does not abort the live encounter");
            }
        } finally { mob.discard(); }
        System.out.println("PASS: native repeated mob escape returns safely without aborting the encounter");
    }

    private static PlaceManifest fixturePlace(UUID id) {
        return new PlaceManifest(WorldStorageContext.worldId(), new DiscoveryKey(DiscoveryKind.PLACE, "wicklen_chronicles:test/" + id),
                "minecraft:overworld", new PlaceManifest.Point(160, 150, 160), new PlaceManifest.Point(167, 151, 165),
                new PlaceManifest.Point(160, 151, 165), new PlaceManifest.Bounds(160, 150, 160, 175, 156, 175),
                StationVariant.UNDEAD_COURTYARD, 1, new PlaceManifest.Bounds(162, 151, 160, 173, 156, 171));
    }

    private static int countRelics(ServerPlayer player) {
        var item = BuiltInRegistries.ITEM.get(Identifier.parse(RELIC.itemId())).orElseThrow().value();
        int total = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            var stack = player.getInventory().getItem(i);
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
