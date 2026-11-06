package dev.wicklen.chronicles.guidance;

import com.mojang.authlib.GameProfile;
import dev.wicklen.chronicles.ai.WorldStorageContext;
import dev.wicklen.chronicles.annotation.AnnotationService;
import dev.wicklen.chronicles.core.discovery.*;
import dev.wicklen.chronicles.core.field.*;
import dev.wicklen.chronicles.core.guidance.*;
import dev.wicklen.chronicles.encounter.GuidanceEncounterFixture;
import dev.wicklen.chronicles.exploration.DiscoveryRepository;
import dev.wicklen.chronicles.firstlight.GuidanceFirstLightFixture;
import dev.wicklen.chronicles.net.*;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.level.Level;

import java.util.UUID;

public final class GuidanceNativeChecks {
    public static void verify(MinecraftServer server) throws Exception {
        require(Boolean.getBoolean("wicklen_chronicles.serverSmoke"), "guidance fixtures require an isolated smoke world");
        UUID owner = UUID.randomUUID();
        UUID world = WorldStorageContext.worldId();
        var profile = new GameProfile(owner, "GuidanceProbe");
        var player = new ServerPlayer(server, server.overworld(), profile, ClientInformation.createDefault());
        player.setPos(0.5, 70, 0.5);
        var discoveries = new DiscoveryRepository();
        var key = new DiscoveryKey(DiscoveryKind.PLACE, "wicklen_chronicles:test/guidance_known");
        var entry = new Observation(key, "Known station", "Personally recorded place",
                new DiscoveryPosition("minecraft:overworld", 9_000_000, 70, 9_000_000), 1);
        discoveries.observe(owner, entry);
        require(server.overworld().getChunkSource().getChunkNow(9_000_000 >> 4, 9_000_000 >> 4) == null,
                "far known-place fixture starts without loaded terrain");
        var initial = GuidanceService.readmodel(player);
        require(initial.enabled() && initial.target() == null, "default does not invent a target without an active expedition");
        GuidanceService.apply(player, world, GuidanceC2SPayload.Action.PIN_PLACE, key.id());
        var pinned = GuidanceService.readmodel(player);
        require(pinned.target() != null && pinned.target().id().equals(key.id()) && GuidanceService.pinnedKey(player).equals(key),
                "confirmed place produces the exact canonical target");
        require(server.overworld().getChunkSource().getChunkNow(9_000_000 >> 4, 9_000_000 >> 4) == null,
                "resolving a distant direction does not load terrain");
        require(AnnotationService.save(player, world, key, 0, "My river camp", "").status() == AnnotationService.Status.OK,
                "personal label fixture is saved");
        require(GuidanceService.readmodel(player).target().label().equals("My river camp"), "the target resolves the current personal label");
        rejected(() -> GuidanceService.apply(player, world, GuidanceC2SPayload.Action.PIN_PLACE,
                "wicklen_chronicles:test/hidden"), "unknown IDs cannot create a direction");
        var stranger = new ServerPlayer(server, server.overworld(), new GameProfile(UUID.randomUUID(), "GuidanceOther"), ClientInformation.createDefault());
        rejected(() -> GuidanceService.apply(stranger, world, GuidanceC2SPayload.Action.PIN_PLACE, key.id()),
                "another player's place is not knowledge");
        rejected(() -> GuidanceService.apply(player, UUID.randomUUID(), GuidanceC2SPayload.Action.CLEAR, ""),
                "stale-world actions cannot disable the current intention");
        var nether = new ServerPlayer(server, server.getLevel(Level.NETHER), profile, ClientInformation.createDefault());
        var foreign = GuidanceService.readmodel(nether);
        require(foreign.target().otherDimension() && foreign.noticeKey().endsWith("other_dimension"),
                "a dimension transition explicitly suspends the apparent route");
        rejected(() -> GuidanceService.apply(nether, world, GuidanceC2SPayload.Action.PIN_PLACE, key.id()),
                "another dimension cannot submit a new pin for this place");
        var offered = FirstLightTransitions.offer(world, owner, key, "Known station",
                new DiscoveryPosition("minecraft:overworld", 0, 70, 0), true);
        var active = FirstLightTransitions.choose(offered, offered.id(), FirstLightState.Branch.SURVEY, 10);
        GuidanceFirstLightFixture.put(player, active);
        GuidanceService.apply(player, world, GuidanceC2SPayload.Action.CLEAR, "");
        var disabled = GuidanceService.apply(player, world, GuidanceC2SPayload.Action.READ, "");
        require(!disabled.enabled() && disabled.target() == null, "READ and a newly active thread cannot undo explicit clear");
        var followed = GuidanceService.apply(player, world, GuidanceC2SPayload.Action.FOLLOW_EXPEDITION, "");
        require(followed.target().kind() == GuidanceTarget.Kind.SURVEY, "explicit follow resolves the broad authored survey area");
        var point = followed.target();
        require(point.x() >= active.area().minX() && point.x() <= active.area().maxX()
                && point.z() >= active.area().minZ() && point.z() <= active.area().maxZ(), "survey direction stays in the offered area");
        player.setPos(active.area().minX() + 64, server.overworld().getMinY() + 2, active.area().minZ() + 64);
        require(GuidanceService.readmodel(player).noticeKey().endsWith("surface_required"),
                "being horizontally inside the field area underground never claims that a field mark is ready");
        player.setPos(0.5, 70, 0.5);
        var one = FirstLightTransitions.mark(active, new DiscoveryPosition("minecraft:overworld",
                active.area().minX() + 24, 70, active.area().minZ() + 24), 20);
        GuidanceFirstLightFixture.put(player, one);
        var next = GuidanceService.readmodel(player).target();
        require(Math.hypot(next.x() - one.marks().getFirst().x(), next.z() - one.marks().getFirst().z()) >= 48,
                "the next field direction remains separated from the first mark");
        GuidanceIntent intentionBeforeFight = GuidanceService.current(player);
        UUID fight = GuidanceEncounterFixture.start(player, key);
        require(GuidanceService.readmodel(player).paused(), "a real ACTIVE participant ledger pauses navigation");
        require(GuidanceService.current(player).equals(intentionBeforeFight), "battle pause does not replace the saved intention");
        GuidanceEncounterFixture.stop(player, fight);
        require(!GuidanceService.readmodel(player).paused() && GuidanceService.readmodel(player).target() != null,
                "navigation resumes after the fight while its expedition is still active");
        var ready = FirstLightTransitions.mark(one, new DiscoveryPosition("minecraft:overworld",
                one.area().minX() + 110, 70, one.area().minZ() + 24), 30);
        GuidanceFirstLightFixture.put(player, ready);
        require(GuidanceService.readmodel(player).target() == null, "a READY expedition no longer generates a direction");
        var claimed = FirstLightTransitions.claim(ready, ready.id());
        GuidanceFirstLightFixture.put(player, claimed);
        require(GuidanceService.readmodel(player).target() == null, "a CLAIMED expedition cannot reactivate its previous target");
        verifyCodecs(server, pinned.target());
        System.out.println("PASS: native guidance personal pin/privacy, no terrain load, aliases, opt-out, dimension transition, field separation, battle pause/resume and completed-target removal");
    }

    private static void verifyCodecs(MinecraftServer server, GuidanceTarget target) {
        UUID world = WorldStorageContext.worldId();
        var request = new GuidanceC2SPayload(UUID.randomUUID(), world, GuidanceC2SPayload.Action.PIN_PLACE, target.id());
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), server.registryAccess());
        try {
            GuidanceC2SPayload.CODEC.encode(buffer, request);
            require(GuidanceC2SPayload.CODEC.decode(buffer).equals(request), "bounded guidance request round-trips through the native codec");
        } finally {
            buffer.release();
        }
        var page = new GuidanceS2CPayload(GuidanceS2CPayload.PUSH_ID, world, UUID.randomUUID(), WorldStorageContext.sessionId(),
                5, true, true, target, "journal.wicklen_chronicles.guidance.paused", false);
        buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), server.registryAccess());
        try {
            GuidanceS2CPayload.CODEC.encode(buffer, page);
            require(buffer.readableBytes() < 1200 && GuidanceS2CPayload.CODEC.decode(buffer).equals(page),
                    "guidance contains one bounded target and preserves session, owner and pause");
        } finally {
            buffer.release();
        }
    }

    private interface Action {
        void run() throws Exception;
    }

    private static void rejected(Action action, String message) throws Exception {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (IllegalArgumentException expected) {
            // Expected privacy or stale-world rejection.
        }
    }

    private static void require(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }
}
