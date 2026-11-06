package dev.wicklen.chronicles.client;

import dev.wicklen.chronicles.core.guidance.GuidanceGeometry;
import dev.wicklen.chronicles.core.guidance.GuidanceTarget;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/** A direction card and rare nearby visible glimmers. Neither represents a computed walking route. */
public final class GuidanceHud {
    private static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};
    private static final DustParticleOptions GLIMMER = new DustParticleOptions(0xE9C56F, 0.65f);
    private static boolean registered;
    private static int ticks;

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath("wicklen_chronicles", "guidance"), GuidanceHud::render);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (++ticks % 40 == 0) {
                emitSparks(client);
            }
        });
    }

    static boolean displayAllowed(Minecraft client) {
        var page = GuidanceClientState.page();
        return client.player != null && client.level != null && client.gui.screen() == null
                && client.gui.overlay() == null && !client.gui.hud.isHidden()
                && page != null && page.enabled() && !page.paused() && page.target() != null;
    }

    static Component title(GuidanceTarget target) {
        return target.label().equals(target.canonicalLabel())
                ? JournalTexts.placeTitle(target.label()) : Component.literal(target.label());
    }

    private static void render(GuiGraphicsExtractor graphics, net.minecraft.client.DeltaTracker delta) {
        var client = Minecraft.getInstance();
        if (!displayAllowed(client)) {
            return;
        }
        var target = GuidanceClientState.target();
        int width = Math.min(260, Math.max(132, graphics.guiWidth() / 2 - 20));
        int x = 12;
        int y = 36;
        int color = target.kind() == GuidanceTarget.Kind.ENCOUNTER ? 0xFFD49D87 : 0xFFE8CD8C;
        graphics.fill(x, y, x + width, y + 24, 0xD92F2A34);
        graphics.fill(x, y, x + 2, y + 24, color);
        graphics.text(client.font, client.font.plainSubstrByWidth(title(target).getString(), width - 16),
                x + 8, y + 4, 0xFFF1E7D2, false);
        Component detail;
        if (target.otherDimension() || !target.dimension().equals(client.level.dimension().identifier().toString())) {
            detail = JournalTexts.tr("guidance.other_dimension");
        } else {
            var vector = GuidanceGeometry.navigation(client.player.getX(), client.player.getZ(), client.player.getYRot(), target.x(), target.z());
            int distance = (int) Math.round(vector.horizontalDistance());
            if (target.kind() == GuidanceTarget.Kind.SURVEY && GuidanceClientState.page().noticeKey().endsWith("guidance.surface_required")) {
                detail = JournalTexts.tr("guidance.surface_short");
            } else if (target.kind() != GuidanceTarget.Kind.SURVEY && distance <= 5 && Math.abs(client.player.getY() - target.y()) > 10) {
                detail = JournalTexts.tr(client.player.getY() < target.y() ? "guidance.above" : "guidance.below",
                        (int) Math.round(Math.abs(client.player.getY() - target.y())));
            } else if (distance <= 5) {
                detail = JournalTexts.tr(target.kind() == GuidanceTarget.Kind.SURVEY ? "guidance.field_ready" : "guidance.nearby");
            } else {
                int arrow = Math.floorMod((int) Math.floor((vector.relativeDegrees() + 22.5) / 45), 8);
                detail = JournalTexts.tr("guidance.vector", ARROWS[arrow], distance);
            }
        }
        graphics.text(client.font, client.font.plainSubstrByWidth(detail.getString(), width - 16),
                x + 8, y + 14, color, false);
    }

    static List<Vec3> sparkCandidates(Minecraft client) {
        if (!displayAllowed(client) || client.player.isUsingItem()
                || GuidanceClientState.page().noticeKey().endsWith("guidance.surface_required")) {
            return List.of();
        }
        var target = GuidanceClientState.target();
        if (target.otherDimension() || !target.dimension().equals(client.level.dimension().identifier().toString())
                || !client.level.getEntities(client.player, client.player.getBoundingBox().inflate(12),
                entity -> entity.isAlive() && entity.getType().getCategory() == MobCategory.MONSTER).isEmpty()) {
            return List.of();
        }
        Vec3 direction = new Vec3(target.x() - client.player.getX(), 0, target.z() - client.player.getZ());
        if (direction.horizontalDistanceSqr() < 64) {
            return List.of();
        }
        direction = direction.normalize();
        Vec3 eye = client.player.getEyePosition();
        var result = new ArrayList<Vec3>(3);
        for (double distance : new double[]{2.5, 4.0, 5.5}) {
            Vec3 point = new Vec3(client.player.getX() + direction.x * distance,
                    client.player.getY() + 0.25, client.player.getZ() + direction.z * distance);
            if (point.subtract(eye).normalize().dot(client.player.getViewVector(1)) < 0.55 || !loadedRay(client, eye, point)) {
                continue;
            }
            BlockPos block = BlockPos.containing(point);
            if (!client.level.getBlockState(block).getCollisionShape(client.level, block).isEmpty()
                    || !client.level.getFluidState(block).isEmpty()) {
                continue;
            }
            var hit = client.level.clip(new ClipContext(eye, point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, client.player));
            if (hit.getType() == HitResult.Type.MISS) {
                result.add(point);
            }
        }
        return List.copyOf(result);
    }

    private static boolean loadedRay(Minecraft client, Vec3 from, Vec3 to) {
        BlockPos start = BlockPos.containing(from);
        BlockPos end = BlockPos.containing(to);
        for (int x = Math.min(start.getX(), end.getX()) >> 4; x <= (Math.max(start.getX(), end.getX()) >> 4); x++) {
            for (int z = Math.min(start.getZ(), end.getZ()) >> 4; z <= (Math.max(start.getZ(), end.getZ()) >> 4); z++) {
                if (client.level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    static int emitSparks(Minecraft client) {
        var points = sparkCandidates(client);
        for (var point : points) {
            client.level.addParticle(GLIMMER, point.x, point.y, point.z, 0, 0.012, 0);
        }
        return points.size();
    }

    private GuidanceHud() {}
}
