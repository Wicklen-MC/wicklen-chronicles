package dev.wicklen.chronicles.client;

import dev.wicklen.chronicles.WicklenChronicles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/** Tiny owned materials; visual details live in geometry and a deterministic coarse linen weave. */
final class SpiritMaterials {
    static final Identifier FABRIC = Identifier.fromNamespaceAndPath(WicklenChronicles.MOD_ID, "spirit_linen");
    static final Identifier FLAT = Identifier.fromNamespaceAndPath(WicklenChronicles.MOD_ID, "spirit_flat_material");
    static final int CLOTH = 0xFFE6DAC0;
    static final int LINING = 0xFF272529;
    static final int PATCH = 0xFF649A9B;
    static final int STITCH = 0xFF78543A;

    private SpiritMaterials() { }

    static void register() {
        var manager = Minecraft.getInstance().getTextureManager();
        var linen = new DynamicTexture("Wicklen spirit linen", 32, 32, false);
        for (int y = 0; y < 32; y++) for (int x = 0; x < 32; x++) {
            int shade = switch ((x * 3 + y * 5) % 11) {
                case 0 -> 229;
                case 1, 2 -> 241;
                default -> 251;
            };
            linen.getPixels().setPixel(x, y, 0xFF000000 | shade << 16 | shade << 8 | shade);
        }
        linen.upload();
        manager.register(FABRIC, linen);
        var flat = new DynamicTexture("Wicklen spirit flat material", 1, 1, false);
        flat.getPixels().setPixel(0, 0, 0xFFFFFFFF);
        flat.upload();
        manager.register(FLAT, flat);
        // TextureManager owns these images and closes replaced instances on renderer/resource reload.
    }
}
