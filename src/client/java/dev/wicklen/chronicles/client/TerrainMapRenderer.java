package dev.wicklen.chronicles.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.material.MapColor;

/** One tiny nearest-filtered texture, real map colors and opaque paper fog; no unknown terrain is uploaded. */
public final class TerrainMapRenderer {
    public record Rect(int x, int y, int size) {}
    private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath("wicklen_chronicles", "terrain_map/runtime");
    private static DynamicTexture texture;
    static void update(byte[] colors) {
        if (colors.length != 16384) throw new IllegalArgumentException("Terrain texture bounds");
        if (texture == null) {
            texture = new DynamicTexture("Wicklen personal atlas", 128, 128, false);
            Minecraft.getInstance().getTextureManager().register(TEXTURE, texture);
        }
        var image = texture.getPixels();
        for (int z = 0; z < 128; z++) for (int x = 0; x < 128; x++) {
            int packed = colors[z * 128 + x] & 255;
            int argb = packed == 0 ? ((x + z) % 11 == 0 ? 0xFF71685A : 0xFF665E51) : MapColor.getColorFromPackedId(packed);
            if ((argb >>> 24) == 0) argb = 0xFF665E51;
            image.setPixel(x, z, argb);
        }
        texture.upload();
    }
    public static void draw(GuiGraphicsExtractor graphics, Rect rect, TerrainMapCamera.View view) {
        int x = rect.x, y = rect.y, s = rect.size;
        graphics.fill(x - 3, y - 3, x + s + 3, y + s + 3, 0xFF8C775C);
        graphics.fill(x - 1, y - 1, x + s + 1, y + s + 1, 0xFFE5D6B8);
        // Only reproject the last authorized server image. Newly exposed screen area is opaque fog.
        graphics.fill(x, y, x + s, y + s, 0xFF665E51);
        var page = TerrainMapClientState.page();
        if (texture != null && page != null) {
            var sampled = page.viewport();
            double left = x + view.u(sampled.minX()) * s;
            double top = y + view.v(sampled.minZ()) * s;
            double size = (double) sampled.span() / view.span() * s;
            if (left < x + s && top < y + s && left + size > x && top + size > y) {
                int drawX = (int) Math.floor(left);
                int drawY = (int) Math.floor(top);
                int drawWidth = (int) Math.ceil(left + size) - drawX;
                int drawHeight = (int) Math.ceil(top + size) - drawY;
                graphics.enableScissor(x, y, x + s, y + s);
                graphics.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, drawX, drawY, 0, 0,
                        drawWidth, drawHeight, 128, 128, 128, 128);
                graphics.disableScissor();
            }
        }
        graphics.text(Minecraft.getInstance().font, "N", x + s / 2 - 3, y + 4, 0xFFFFF0CC, true);
        var player = Minecraft.getInstance().player;
        if (player != null && view.contains(player.getX(), player.getZ())) {
            float px = (float) (x + view.u(player.getX()) * s), pz = (float) (y + view.v(player.getZ()) * s);
            graphics.pose().pushMatrix(); graphics.pose().translate(px, pz);
            graphics.pose().rotate((float) Math.toRadians(player.getYRot() + 180));
            graphics.text(Minecraft.getInstance().font, "▲", -3, -4, 0xFFFAF1C7, true);
            graphics.pose().popMatrix();
        }
    }
    public static void zone(GuiGraphicsExtractor graphics, TerrainMapCamera.View view, Rect rect, String dimension,
                            int minX, int minZ, int maxX, int maxZ, int color) {
        var level = Minecraft.getInstance().level;
        if (level == null || !level.dimension().identifier().toString().equals(dimension)) return;
        int x1 = rect.x + (int) Math.floor(Math.clamp(view.u(minX), 0, 1) * rect.size);
        int x2 = rect.x + (int) Math.ceil(Math.clamp(view.u(maxX), 0, 1) * rect.size);
        int z1 = rect.y + (int) Math.floor(Math.clamp(view.v(minZ), 0, 1) * rect.size);
        int z2 = rect.y + (int) Math.ceil(Math.clamp(view.v(maxZ), 0, 1) * rect.size);
        if (x1 >= x2 || z1 >= z2) return;
        graphics.fill(x1, z1, x2, z2, color & 0x00FFFFFF | 0x22000000);
        graphics.outline(x1, z1, x2 - x1, z2 - z1, color | 0xFF000000);
    }
    static void point(GuiGraphicsExtractor graphics, TerrainMapCamera.View view, Rect rect, double x, double z, int color) {
        if (!view.contains(x, z)) return;
        int px = rect.x + (int) Math.round(view.u(x) * rect.size), pz = rect.y + (int) Math.round(view.v(z) * rect.size);
        graphics.outline(px - 2, pz - 2, 5, 5, color);
    }
    /** A clipped straight bearing above the existing fog; this never reads or changes map pixels. */
    static void guidance(GuiGraphicsExtractor graphics, TerrainMapCamera.View view, Rect rect) {
        var client = Minecraft.getInstance();
        var page = GuidanceClientState.page();
        var target = GuidanceClientState.target();
        if (page == null || page.paused() || target == null || client.player == null || client.level == null
                || target.otherDimension() || !target.dimension().equals(client.level.dimension().identifier().toString())) {
            return;
        }
        double[] line = clippedLine(view.u(client.player.getX()), view.v(client.player.getZ()), view.u(target.x()), view.v(target.z()));
        if (line == null) {
            return;
        }
        graphics.enableScissor(rect.x(), rect.y(), rect.x() + rect.size(), rect.y() + rect.size());
        double dx = (line[2] - line[0]) * rect.size();
        double dz = (line[3] - line[1]) * rect.size();
        int steps = Math.max(1, (int) Math.ceil(Math.hypot(dx, dz)));
        for (int step = 0; step <= steps; step += 5) {
            double fraction = (double) step / steps;
            int x = rect.x() + (int) Math.round(line[0] * rect.size() + dx * fraction);
            int z = rect.y() + (int) Math.round(line[1] * rect.size() + dz * fraction);
            graphics.fill(x, z, x + 2, z + 2, 0xFFF1CD7C);
        }
        int tx = rect.x() + (int) Math.round(line[2] * rect.size());
        int tz = rect.y() + (int) Math.round(line[3] * rect.size());
        graphics.outline(tx - 3, tz - 3, 7, 7, 0xFFFFE2A0);
        if (rect.size() >= 76) {
            String name = client.font.plainSubstrByWidth(GuidanceHud.title(target).getString(), Math.min(95, rect.size() - 8));
            int width = client.font.width(name);
            int lx = Math.clamp(tx + 6, rect.x() + 3, rect.x() + rect.size() - width - 3);
            int ly = Math.clamp(tz - 12, rect.y() + 3, rect.y() + rect.size() - 12);
            graphics.fill(lx - 2, ly - 2, lx + width + 2, ly + 10, 0xD92F2A34);
            graphics.text(client.font, name, lx, ly, 0xFFFFE9BC, false);
        }
        graphics.disableScissor();
    }

    /** Liang-Barsky clipping in normalized map coordinates, bounded even for very distant pins. */
    static double[] clippedLine(double x1, double z1, double x2, double z2) {
        double dx = x2 - x1;
        double dz = z2 - z1;
        double[] p = {-dx, dx, -dz, dz};
        double[] q = {x1, 1 - x1, z1, 1 - z1};
        double first = 0;
        double last = 1;
        for (int i = 0; i < p.length; i++) {
            if (Math.abs(p[i]) < 1.0e-12) {
                if (q[i] < 0) {
                    return null;
                }
            } else {
                double ratio = q[i] / p[i];
                if (p[i] < 0) {
                    first = Math.max(first, ratio);
                } else {
                    last = Math.min(last, ratio);
                }
                if (first > last) {
                    return null;
                }
            }
        }
        return new double[]{x1 + first * dx, z1 + first * dz, x1 + last * dx, z1 + last * dz};
    }

    public static void clear() {
        if (texture != null) { Minecraft.getInstance().getTextureManager().release(TEXTURE); texture = null; }
    }
    private TerrainMapRenderer() {}
}
