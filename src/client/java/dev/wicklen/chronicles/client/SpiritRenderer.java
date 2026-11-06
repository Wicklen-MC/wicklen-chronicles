package dev.wicklen.chronicles.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.core.BlockPos;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import dev.wicklen.chronicles.WicklenChronicles;
import dev.wicklen.chronicles.entity.SpiritEntity;

/**
 * Рендерер духа: анимированный светлячок с глазами, плавниками и венчиком,
 * «облачко» реплики над духом (S2C SpiritBubblePayload -> BubbleStore).
 */
public class SpiritRenderer extends MobRenderer<SpiritEntity, SpiritRenderState, SpiritModel> {
    private static final Identifier TEXTURE =
        Identifier.fromNamespaceAndPath(WicklenChronicles.MOD_ID, "spirit_flat_material");
    private static final int WRAP_WIDTH = 170; // pixels, including wide Cyrillic glyphs
    private static final int MAX_BUBBLE_LINES = 6;
    private static final double BUBBLE_HEIGHT = 0.35;

    public SpiritRenderer(EntityRendererProvider.Context context) {
        super(context, new SpiritModel(SpiritModel.Layer.SHELL), 0);
        // Geometry supplies the details; a neutral procedural material preserves every persona's tint.
        // TextureManager owns and closes this texture, including a replaced renderer on reload.
        var material = new DynamicTexture("Wicklen spirit flat material", 1, 1, false);
        material.getPixels().setPixel(0, 0, 0xFFFFFFFF);
        material.upload();
        Minecraft.getInstance().getTextureManager().register(TEXTURE, material);
        addLayer(new RenderLayer<>(this) {
            private final SpiritModel core = new SpiritModel(SpiritModel.Layer.CORE);

            @Override
            public void submit(PoseStack pose, SubmitNodeCollector collector, int light,
                               SpiritRenderState state, float yRot, float xRot) {
                int color = 0xE0000000 | ((state.spiritColor & 0xFEFEFE) >>> 1) + 0x7F7F7F;
                // The inner light must be drawn before the shell writes its depth.
                collector.order(-1).submitModel(core, state, pose, RenderTypes.entityTranslucentEmissive(TEXTURE),
                    0xF000F0, OverlayTexture.NO_OVERLAY, color, null, 0);
            }
        });
        addLayer(new RenderLayer<>(this) {
            private final SpiritModel eyes = new SpiritModel(SpiritModel.Layer.EYES);

            @Override
            public void submit(PoseStack pose, SubmitNodeCollector collector, int light,
                               SpiritRenderState state, float yRot, float xRot) {
                collector.submitModel(eyes, state, pose, RenderTypes.entityCutout(TEXTURE),
                    0xF000F0, OverlayTexture.NO_OVERLAY, 0xFF10243A, null, 0);
            }
        });
    }

    @Override
    public SpiritRenderState createRenderState() {
        return new SpiritRenderState();
    }

    @Override
    public Identifier getTextureLocation(SpiritRenderState state) {
        return TEXTURE;
    }

    /** Дух всегда полупрозрачный. */
    @Override
    protected RenderType getRenderType(SpiritRenderState state, boolean visible, boolean invisibleToPlayer, boolean semiTransparent) {
        if (invisibleToPlayer) return null;
        return RenderTypes.entityTranslucentCull(getTextureLocation(state));
    }

    @Override
    protected int getModelTint(SpiritRenderState state) {
        return 0x98000000 | state.spiritColor;
    }

    @Override
    protected int getBlockLightLevel(SpiritEntity entity, BlockPos pos) {
        return 15;
    }

    @Override
    protected void scale(SpiritRenderState state, PoseStack pose) {
        float pulse = 1 + (float) Math.sin(state.ageInTicks * 0.07f) * 0.025f;
        pose.scale(pulse, pulse, pulse);
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
