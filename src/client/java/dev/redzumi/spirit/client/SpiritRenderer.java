package dev.redzumi.spirit.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import dev.redzumi.spirit.RedzSpirit;
import dev.redzumi.spirit.entity.SpiritEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * Рендерер духа: модель-куб (слой SLIME_OUTER), полупрозрачный тип со свечением-обводкой,
 * «облачко» реплики над духом (S2C SpiritBubblePayload -> BubbleStore).
 */
public class SpiritRenderer extends MobRenderer<SpiritEntity, SpiritRenderState, EntityModel<SpiritRenderState>> {
    private static final Identifier TEXTURE =
        Identifier.fromNamespaceAndPath(RedzSpirit.MOD_ID, "textures/entity/spirit.png");
    private static final int OUTLINE_COLOR = 0xFF9FE8FF;
    private static final int WRAP_WIDTH = 28;   // символов в строке облачка
    private static final double BUBBLE_HEIGHT = 1.1;

    public SpiritRenderer(EntityRendererProvider.Context context) {
        super(context, new SpiritModel(context.bakeLayer(net.minecraft.client.model.geom.ModelLayers.SLIME_OUTER)), 0.25f);
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
        return RenderTypes.entityTranslucentCull(getTextureLocation(state));
    }

    @Override
    public void extractRenderState(SpiritEntity entity, SpiritRenderState state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);
        state.outlineColor = OUTLINE_COLOR; // свечение сквозь стены (vanilla-пасс)

        var bubble = BubbleStore.get(entity.getId());
        if (bubble != null) {
            state.bubbleLines = wrap(bubble.text());
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
                false,
                state.lightCoords,
                camera);
        }
    }

    /** Разбить реплику на короткие строки по словам. */
    static String[] wrap(String text) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split("\\s+")) {
            if (line.length() + word.length() + 1 > WRAP_WIDTH && line.length() > 0) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) line.append(' ');
            line.append(word);
        }
        if (line.length() > 0) lines.add(line.toString());
        return lines.toArray(new String[0]);
    }

    /** Простая модель-обёртка над ванильным слоем куба. */
    static final class SpiritModel extends EntityModel<SpiritRenderState> {
        SpiritModel(ModelPart root) {
            super(root);
        }
    }
}
