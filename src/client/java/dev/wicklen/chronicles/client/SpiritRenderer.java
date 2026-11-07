package dev.wicklen.chronicles.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import dev.wicklen.chronicles.entity.SpiritEntity;

/**
 * Рендерер духа: тканевый странник с открытым капюшоном и светящимися глазами,
 * «облачко» реплики над духом (S2C SpiritBubblePayload -> BubbleStore).
 */
public class SpiritRenderer extends MobRenderer<SpiritEntity, SpiritRenderState, SpiritModel> {
    private static final int WRAP_WIDTH = 170; // pixels, including wide Cyrillic glyphs
    private static final int MAX_BUBBLE_LINES = 6;
    private static final double BUBBLE_HEIGHT = 0.35;

    public SpiritRenderer(EntityRendererProvider.Context context) {
        super(context, new SpiritModel(SpiritModel.Layer.CLOTH), 0);
        SpiritMaterials.register();
        addClothLayer(SpiritModel.Layer.LINING, SpiritMaterials.FLAT, SpiritMaterials.LINING);
        addClothLayer(SpiritModel.Layer.PATCHES, SpiritMaterials.FABRIC, SpiritMaterials.PATCH);
        addClothLayer(SpiritModel.Layer.STITCHES, SpiritMaterials.FLAT, SpiritMaterials.STITCH);
        addLayer(new RenderLayer<>(this) {
            private final SpiritModel eyes = new SpiritModel(SpiritModel.Layer.EYES);

            @Override
            public void submit(PoseStack pose, SubmitNodeCollector collector, int light,
                               SpiritRenderState state, float yRot, float xRot) {
                if (state.isInvisibleToPlayer) return;
                collector.submitModel(eyes, state, pose, RenderTypes.entityTranslucentEmissive(SpiritMaterials.FLAT),
                    0xF000F0, OverlayTexture.NO_OVERLAY, 0xFF000000 | state.spiritColor, null, 0);
            }
        });
    }

    private void addClothLayer(SpiritModel.Layer kind, Identifier texture, int color) {
        addLayer(new RenderLayer<>(this) {
            private final SpiritModel layer = new SpiritModel(kind);

            @Override
            public void submit(PoseStack pose, SubmitNodeCollector collector, int light,
                               SpiritRenderState state, float yRot, float xRot) {
                if (state.isInvisibleToPlayer) return;
                collector.submitModel(layer, state, pose, RenderTypes.entityCutout(texture),
                    light, OverlayTexture.NO_OVERLAY, color, null, 0);
            }
        });
    }

    @Override
    public SpiritRenderState createRenderState() {
        return new SpiritRenderState();
    }

    @Override
    public Identifier getTextureLocation(SpiritRenderState state) {
        return SpiritMaterials.FABRIC;
    }

    @Override
    protected int getModelTint(SpiritRenderState state) {
        return SpiritMaterials.CLOTH;
    }

    @Override
    protected void scale(SpiritRenderState state, PoseStack pose) {
        pose.scale(0.85f, 0.85f, 0.85f);
    }

    @Override
    public void extractRenderState(SpiritEntity entity, SpiritRenderState state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);
        state.spiritColor = entity.spiritColor();

        var bubble = BubbleStore.get(entity.getId());
        if (bubble != null) {
            var lines = getFont().splitIgnoringLanguage(Component.literal(bubble.text()), WRAP_WIDTH);
            int count = Math.min(MAX_BUBBLE_LINES, lines.size());
            state.bubbleLines = new String[count];
            for (int i = 0; i < count; i++) {
                state.bubbleLines[i] = lines.get(i).getString();
            }
            if (lines.size() > count && count > 0) {
                state.bubbleLines[count - 1] = getFont().plainSubstrByWidth(state.bubbleLines[count - 1], WRAP_WIDTH - 12) + "…";
            }
            state.bubbleColor = bubble.color();
        } else {
            state.bubbleLines = new String[0];
        }
    }

    @Override
    public void submit(SpiritRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        super.submit(state, poseStack, collector, camera);
        // облачко: строки стеком над духом, верхняя строка выше
        for (int i = 0; i < state.bubbleLines.length; i++) {
            double y = state.boundingBoxHeight + BUBBLE_HEIGHT
                + (state.bubbleLines.length - 1 - i) * 0.28;
            collector.submitNameTag(
                poseStack,
                new Vec3(0.0, y, 0.0),
                0,
                Component.literal(state.bubbleLines[i]).withStyle(s -> s.withColor(state.bubbleColor)),
                true,
                state.lightCoords,
                camera);
        }
    }

}
