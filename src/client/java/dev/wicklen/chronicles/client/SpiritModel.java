package dev.wicklen.chronicles.client;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;

/** Layered lantern wisp. Shell, inner light and face share one pose and blink clock. */
final class SpiritModel extends EntityModel<SpiritRenderState> {
    enum Layer { SHELL, CORE, EYES }
    private final ModelPart body;

    SpiritModel(Layer layer) {
        super(createLayer(layer).bakeRoot());
        body = root.getChild("body");
    }

    private static LayerDefinition createLayer(Layer layer) {
        var mesh = new MeshDefinition();
        var body = mesh.getRoot().addOrReplaceChild("body", CubeListBuilder.create(), PartPose.offset(0, 18, 0));
        if (layer == Layer.EYES) {
            body.addOrReplaceChild("eyes", CubeListBuilder.create()
                .addBox(-1.85f, -1.8f, -2.72f, 1f, 1.65f, 0.18f)
                .addBox(0.85f, -1.8f, -2.72f, 1f, 1.65f, 0.18f), PartPose.ZERO);
        } else if (layer == Layer.CORE) {
            body.addOrReplaceChild("light", CubeListBuilder.create()
                .addBox(-1.35f, -2.8f, -1.35f, 2.7f, 3.8f, 2.7f)
                .addBox(-0.75f, 1, -0.75f, 1.5f, 2.2f, 1.5f), PartPose.ZERO);
            body.addOrReplaceChild("spark", CubeListBuilder.create()
                .addBox(-0.55f, -0.55f, -0.55f, 1.1f, 1.1f, 1.1f), PartPose.offset(0, -6.6f, 0));
        } else {
            body.addOrReplaceChild("head", CubeListBuilder.create()
                .addBox(-2.1f, -4.1f, -2.1f, 4.2f, 1.1f, 4.2f)
                .addBox(-2.7f, -3, -2.6f, 5.4f, 3.9f, 5.2f)
                .addBox(-2.1f, 0.9f, -2f, 4.2f, 1.7f, 4f), PartPose.ZERO);
            var left = body.addOrReplaceChild("left_fin", CubeListBuilder.create()
                .addBox(0, -0.35f, -0.9f, 2.7f, 0.7f, 2.3f), PartPose.offset(2.3f, 0.15f, 0));
            left.addOrReplaceChild("tip", CubeListBuilder.create()
                .addBox(0, -0.2f, -0.5f, 1.8f, 0.4f, 1.5f), PartPose.offset(2.4f, 0, 0));
            var right = body.addOrReplaceChild("right_fin", CubeListBuilder.create()
                .addBox(-2.7f, -0.35f, -0.9f, 2.7f, 0.7f, 2.3f), PartPose.offset(-2.3f, 0.15f, 0));
            right.addOrReplaceChild("tip", CubeListBuilder.create()
                .addBox(-1.8f, -0.2f, -0.5f, 1.8f, 0.4f, 1.5f), PartPose.offset(-2.4f, 0, 0));
            var tail = body.addOrReplaceChild("tail", CubeListBuilder.create()
                .addBox(-1.4f, 0, -1.3f, 2.8f, 2.5f, 2.6f), PartPose.offset(0, 2.3f, 0));
            var tip = tail.addOrReplaceChild("tip", CubeListBuilder.create()
                .addBox(-0.8f, 0, -0.75f, 1.6f, 2.8f, 1.5f), PartPose.offset(0, 2.1f, 0));
            tip.addOrReplaceChild("wisp", CubeListBuilder.create()
                .addBox(-0.35f, 0, -0.3f, 0.7f, 2.1f, 0.6f), PartPose.offset(0, 2.4f, 0));
            body.addOrReplaceChild("crown", CubeListBuilder.create()
                .addBox(-3.2f, 0, -3.2f, 6.4f, 0.4f, 0.4f)
                .addBox(-3.2f, 0, 2.8f, 6.4f, 0.4f, 0.4f)
                .addBox(-3.2f, 0, -2.8f, 0.4f, 0.4f, 5.6f)
                .addBox(2.8f, 0, -2.8f, 0.4f, 0.4f, 5.6f), PartPose.offset(0, -6.6f, 0));
        }
        return LayerDefinition.create(mesh, 32, 32);
    }

    @Override
    public void setupAnim(SpiritRenderState state) {
        super.setupAnim(state);
        float time = state.ageInTicks;
        body.y += (float) Math.sin(time * 0.075f) * 0.35f;
        body.zRot = (float) Math.sin(time * 0.04f) * 0.065f;
        if (body.hasChild("eyes")) {
            float blink = time % 137;
            body.getChild("eyes").yScale = blink < 4 ? 0.12f + Math.abs(blink - 2) * 0.44f : 1f;
            return;
        }
        if (body.hasChild("light")) {
            float breath = 1 + (float) Math.sin(time * 0.07f) * 0.06f;
            body.getChild("light").xScale = breath;
            body.getChild("light").zScale = breath;
            var spark = body.getChild("spark");
            spark.y += (float) Math.sin(time * 0.06f) * 0.4f;
            spark.yRot = time * 0.015f;
            spark.zRot = 0.785f;
            return;
        }
        float flutter = (float) Math.sin(time * 0.12f) * 0.22f;
        body.getChild("left_fin").zRot = -0.3f + flutter;
        body.getChild("right_fin").zRot = 0.3f - flutter;
        body.getChild("left_fin").getChild("tip").zRot = -0.15f + flutter * 0.6f;
        body.getChild("right_fin").getChild("tip").zRot = 0.15f - flutter * 0.6f;
        var tail = body.getChild("tail");
        tail.xRot = 0.2f + (float) Math.sin(time * 0.1f) * 0.18f;
        tail.getChild("tip").xRot = 0.25f + (float) Math.sin(time * 0.1f - 0.7f) * 0.25f;
        tail.getChild("tip").getChild("wisp").xRot = 0.2f + (float) Math.sin(time * 0.1f - 1.4f) * 0.3f;
        tail.zRot = (float) Math.sin(time * 0.065f) * 0.12f;
        body.getChild("crown").yRot = time * 0.015f;
        body.getChild("crown").y += (float) Math.sin(time * 0.06f) * 0.4f;
    }
}
