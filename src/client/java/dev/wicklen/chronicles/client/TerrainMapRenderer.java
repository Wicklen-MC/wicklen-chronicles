package dev.wicklen.chronicles.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.wicklen.chronicles.core.map.MapViewport;
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
    public static void draw(GuiGraphicsExtractor graphics, Rect rect, MapViewport view) {
        int x = rect.x, y = rect.y, s = rect.size;
        graphics.fill(x - 3, y - 3, x + s + 3, y + s + 3, 0xFF8C775C);
        graphics.fill(x - 1, y - 1, x + s + 1, y + s + 1, 0xFFE5D6B8);
        if (texture == null) graphics.fill(x, y, x + s, y + s, 0xFF665E51);
        else graphics.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, x, y, 0, 0, s, s, 128, 128, 128, 128);
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
    public static void zone(GuiGraphicsExtractor graphics, MapViewport view, Rect rect, String dimension,
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
    static void point(GuiGraphicsExtractor graphics, MapViewport view, Rect rect, double x, double z, int color) {
        if (!view.contains(x, z)) return;
        int px = rect.x + (int) Math.round(view.u(x) * rect.size), pz = rect.y + (int) Math.round(view.v(z) * rect.size);
        graphics.outline(px - 2, pz - 2, 5, 5, color);
    }
    public static void clear() {
        if (texture != null) { Minecraft.getInstance().getTextureManager().release(TEXTURE); texture = null; }
    }
    private TerrainMapRenderer() {}
}
