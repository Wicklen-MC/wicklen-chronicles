package dev.wicklen.chronicles.client;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;

/** Small lantern-like wisp, with fins, a tapered tail and a floating crown. */
final class SpiritModel extends EntityModel<SpiritRenderState> {
    private final ModelPart body;

    SpiritModel(boolean eyesOnly) {
        super(createLayer(eyesOnly).bakeRoot());
        body = root.getChild("body");
    }

    private static LayerDefinition createLayer(boolean eyesOnly) {
        var mesh = new MeshDefinition();
        var body = mesh.getRoot().addOrReplaceChild("body", CubeListBuilder.create(), PartPose.offset(0, 18, 0));
        if (eyesOnly) {
            body.addOrReplaceChild("eyes", CubeListBuilder.create()
                .addBox(-2.15f, -1.9f, -3.08f, 1.1f, 1.7f, 0.35f)
                .addBox(1.05f, -1.9f, -3.08f, 1.1f, 1.7f, 0.35f), PartPose.ZERO);
        } else {
            body.addOrReplaceChild("head", CubeListBuilder.create()
                .addBox(-3, -4, -3, 6, 5, 6)
                .addBox(-2, 1, -2, 4, 2, 4), PartPose.ZERO);
            body.addOrReplaceChild("left_fin", CubeListBuilder.create()
                .addBox(0, -0.5f, -1, 4, 1, 3), PartPose.offset(2.5f, 0, 0));
            body.addOrReplaceChild("right_fin", CubeListBuilder.create()
                .addBox(-4, -0.5f, -1, 4, 1, 3), PartPose.offset(-2.5f, 0, 0));
            var tail = body.addOrReplaceChild("tail", CubeListBuilder.create()
                .addBox(-1.5f, 0, -1.5f, 3, 3, 3), PartPose.offset(0, 2, 0));
            tail.addOrReplaceChild("tip", CubeListBuilder.create()
                .addBox(-0.75f, 0, -0.75f, 1.5f, 3, 1.5f), PartPose.offset(0, 2.5f, 0));
            body.addOrReplaceChild("crown", CubeListBuilder.create()
                .addBox(-3.5f, 0, -3.5f, 7, 0.6f, 0.6f)
                .addBox(-3.5f, 0, 2.9f, 7, 0.6f, 0.6f)
                .addBox(-3.5f, 0, -2.9f, 0.6f, 0.6f, 5.8f)
                .addBox(2.9f, 0, -2.9f, 0.6f, 0.6f, 5.8f), PartPose.offset(0, -6, 0));
        }
        return LayerDefinition.create(mesh, 32, 32);
    }

    @Override
    public void setupAnim(SpiritRenderState state) {
        super.setupAnim(state);
        float time = state.ageInTicks;
        body.y += (float) Math.sin(time * 0.09f) * 0.3f;
        body.zRot = (float) Math.sin(time * 0.045f) * 0.06f;
        if (body.hasChild("eyes")) {
            body.getChild("eyes").yScale = time % 100 < 3 ? 0.15f : 1f;
            return;
        }
        float flutter = (float) Math.sin(time * 0.22f) * 0.3f;
        body.getChild("left_fin").zRot = -0.3f + flutter;
        body.getChild("right_fin").zRot = 0.3f - flutter;
        var tail = body.getChild("tail");
        tail.xRot = 0.2f + (float) Math.sin(time * 0.1f) * 0.18f;
        tail.getChild("tip").xRot = 0.25f + (float) Math.sin(time * 0.1f - 0.7f) * 0.25f;
        body.getChild("crown").yRot = time * 0.018f;
        body.getChild("crown").y += (float) Math.sin(time * 0.06f) * 0.4f;
    }
}
