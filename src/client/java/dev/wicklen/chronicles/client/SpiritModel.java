package dev.wicklen.chronicles.client;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;

/** Small cloth wanderer: an open hood, a layered cloak and two cloth streamers. */
final class SpiritModel extends EntityModel<SpiritRenderState> {
    enum Layer { CLOTH, LINING, PATCHES, STITCHES, EYES }
    private final ModelPart body;

    SpiritModel(Layer layer) {
        super(createLayer(layer).bakeRoot());
        body = root.getChild("body");
    }

    private static LayerDefinition createLayer(Layer layer) {
        var mesh = new MeshDefinition();
        var body = mesh.getRoot().addOrReplaceChild("body", CubeListBuilder.create(), PartPose.offset(0, 18, 0));
        var hood = body.addOrReplaceChild("hood", CubeListBuilder.create(), PartPose.ZERO);
        var front = body.addOrReplaceChild("front", CubeListBuilder.create(), PartPose.offset(0, -0.5f, -2.25f));
        var left = body.addOrReplaceChild("left_drape", CubeListBuilder.create(), PartPose.offset(-2.35f, -0.8f, 0));
        var right = body.addOrReplaceChild("right_drape", CubeListBuilder.create(), PartPose.offset(2.35f, -0.8f, 0));
        var back = body.addOrReplaceChild("back_drape", CubeListBuilder.create(), PartPose.offset(0, -0.7f, 2.1f));
        var ribbon = hood.addOrReplaceChild("ribbon", CubeListBuilder.create(), PartPose.offset(2.2f, -5.3f, 2.7f));
        var ribbonTip = ribbon.addOrReplaceChild("tip", CubeListBuilder.create(), PartPose.offset(0, 0, 3.8f));
        var shortRibbon = hood.addOrReplaceChild("short_ribbon", CubeListBuilder.create(), PartPose.offset(2.2f, -3.6f, 2.7f));
        var shortTip = shortRibbon.addOrReplaceChild("tip", CubeListBuilder.create(), PartPose.offset(0, 0, 3.4f));

        switch (layer) {
            case CLOTH -> {
                // Two sloping roof pieces meet over a genuinely open face, rather than a solid head cube.
                hood.addOrReplaceChild("roof_left", CubeListBuilder.create().texOffs(0, 0)
                    .addBox(-3.7f, -0.6f, -3.2f, 4, 1.1f, 6.2f),
                    PartPose.offsetAndRotation(0, -6.25f, -0.1f, 0, 0, -0.17f));
                hood.addOrReplaceChild("roof_right", CubeListBuilder.create().texOffs(0, 0)
                    .addBox(-0.3f, -0.6f, -3.2f, 4, 1.1f, 6.2f),
                    PartPose.offsetAndRotation(0, -6.25f, -0.1f, 0, 0, 0.17f));
                hood.addOrReplaceChild("hood_sides", CubeListBuilder.create().texOffs(2, 9)
                    .addBox(-3.7f, -5.6f, -3.3f, 1.1f, 4.8f, 6.2f)
                    .addBox(2.6f, -5.6f, -3.3f, 1.1f, 4.8f, 6.2f)
                    .addBox(-2.6f, -5.7f, 2.1f, 5.2f, 4.8f, 0.8f)
                    .addBox(-2.8f, -1.2f, -3.2f, 5.6f, 0.7f, 1.1f), PartPose.ZERO);
                front.addOrReplaceChild("cloth", CubeListBuilder.create().texOffs(0, 16)
                    .addBox(-1.65f, 0, -0.3f, 3.3f, 5.1f, 0.55f)
                    .addBox(-0.7f, 5, -0.3f, 1.3f, 1.1f, 0.55f), PartPose.ZERO);
                left.addOrReplaceChild("cloth", CubeListBuilder.create().texOffs(6, 13)
                    .addBox(-1.25f, 0, -2.4f, 2.6f, 5.1f, 0.5f)
                    .addBox(-0.45f, 0, -1.95f, 0.5f, 4.7f, 4.2f)
                    .addBox(-1.25f, 5, -2.4f, 1.2f, 0.8f, 0.5f), PartPose.ZERO);
                right.addOrReplaceChild("cloth", CubeListBuilder.create().texOffs(6, 13)
                    .addBox(-1.35f, 0, -2.4f, 2.6f, 4.6f, 0.5f)
                    .addBox(-0.05f, 0, -1.95f, 0.5f, 4.4f, 4.2f), PartPose.ZERO);
                back.addOrReplaceChild("cloth", CubeListBuilder.create().texOffs(0, 14)
                    .addBox(-2.5f, 0, -0.1f, 5, 4.8f, 0.5f)
                    .addBox(0.2f, 4.7f, -0.1f, 1.5f, 0.9f, 0.5f), PartPose.ZERO);
                shortRibbon.addOrReplaceChild("cloth", CubeListBuilder.create().texOffs(12, 0)
                    .addBox(-0.65f, -0.18f, 0, 1.3f, 0.36f, 3.6f), PartPose.ZERO);
                shortTip.addOrReplaceChild("cloth", CubeListBuilder.create().texOffs(12, 0)
                    .addBox(-0.65f, -0.18f, 0, 1.3f, 0.36f, 2.8f), PartPose.ZERO);
            }
            case LINING -> hood.addOrReplaceChild("face", CubeListBuilder.create()
                .addBox(-2.65f, -5.25f, -2.98f, 5.3f, 4.2f, 0.2f), PartPose.ZERO);
            case PATCHES -> {
                hood.addOrReplaceChild("patch", CubeListBuilder.create().texOffs(12, 8)
                    .addBox(2.61f, -5.35f, -3.34f, 1.1f, 2.2f, 0.06f)
                    .addBox(3.71f, -5.35f, -3.28f, 0.06f, 2.8f, 2.1f), PartPose.ZERO);
                ribbon.addOrReplaceChild("cloth", CubeListBuilder.create().texOffs(12, 0)
                    .addBox(-0.75f, -0.2f, 0, 1.5f, 0.4f, 4.1f), PartPose.ZERO);
                ribbonTip.addOrReplaceChild("cloth", CubeListBuilder.create().texOffs(12, 0)
                    .addBox(-0.75f, -0.2f, 0, 1.5f, 0.4f, 3.5f), PartPose.ZERO);
                front.addOrReplaceChild("patch", CubeListBuilder.create().texOffs(12, 8)
                    .addBox(0.65f, 2.8f, -0.35f, 0.9f, 1.4f, 0.06f), PartPose.ZERO);
                left.addOrReplaceChild("patch", CubeListBuilder.create().texOffs(12, 8)
                    .addBox(-1.25f, 4.45f, -2.45f, 1.3f, 0.6f, 0.06f), PartPose.ZERO);
            }
            case STITCHES -> {
                hood.addOrReplaceChild("stitches", CubeListBuilder.create()
                    .addBox(2.25f, -5.2f, -3.36f, 0.55f, 0.22f, 0.08f)
                    .addBox(2.25f, -4.3f, -3.36f, 0.55f, 0.22f, 0.08f)
                    .addBox(2.25f, -3.4f, -3.36f, 0.55f, 0.22f, 0.08f), PartPose.ZERO);
                front.addOrReplaceChild("stitches", CubeListBuilder.create()
                    .addBox(-0.15f, 0.4f, -0.37f, 0.2f, 3f, 0.08f)
                    .addBox(-0.5f, 0.7f, -0.38f, 0.9f, 0.2f, 0.08f)
                    .addBox(-0.5f, 1.8f, -0.38f, 0.9f, 0.2f, 0.08f)
                    .addBox(-0.5f, 2.9f, -0.38f, 0.9f, 0.2f, 0.08f), PartPose.ZERO);
            }
            case EYES -> hood.addOrReplaceChild("eyes", CubeListBuilder.create()
                .addBox(-1.65f, -4.1f, -3.12f, 0.85f, 1.45f, 0.12f)
                .addBox(0.8f, -4.1f, -3.12f, 0.85f, 1.45f, 0.12f), PartPose.ZERO);
        }
        return LayerDefinition.create(mesh, 32, 32);
    }

    @Override
    public void setupAnim(SpiritRenderState state) {
        super.setupAnim(state);
        float t = state.ageInTicks;
        body.y += (float) Math.sin(t * 0.075f) * 0.3f;
        body.zRot = (float) Math.sin(t * 0.035f) * 0.035f;
        var hood = body.getChild("hood");
        hood.zRot = (float) Math.sin(t * 0.028f) * 0.04f;
        hood.xRot = (float) Math.sin(t * 0.04f) * 0.025f;
        body.getChild("front").xRot = -0.025f + (float) Math.sin(t * 0.065f) * 0.025f;
        body.getChild("left_drape").zRot = 0.18f + (float) Math.sin(t * 0.065f) * 0.045f;
        body.getChild("right_drape").zRot = -0.18f - (float) Math.sin(t * 0.065f + 0.7f) * 0.045f;
        body.getChild("back_drape").xRot = 0.1f + (float) Math.sin(t * 0.065f + 1.2f) * 0.045f;
        var ribbon = hood.getChild("ribbon");
        ribbon.xRot = 0.18f + (float) Math.sin(t * 0.08f) * 0.13f;
        ribbon.yRot = 0.7f + (float) Math.sin(t * 0.045f) * 0.09f;
        ribbon.getChild("tip").xRot = 0.05f + (float) Math.sin(t * 0.08f - 0.8f) * 0.2f;
        var shortRibbon = hood.getChild("short_ribbon");
        shortRibbon.xRot = 0.02f + (float) Math.sin(t * 0.08f + 0.9f) * 0.12f;
        shortRibbon.yRot = 0.5f + (float) Math.sin(t * 0.045f + 0.6f) * 0.08f;
        shortRibbon.getChild("tip").xRot = 0.1f + (float) Math.sin(t * 0.08f) * 0.18f;
        if (hood.hasChild("eyes")) {
            float blink = t % 137;
            var eyes = hood.getChild("eyes");
            eyes.yScale = blink < 4 ? 0.12f + Math.abs(blink - 2) * 0.44f : 1;
            // Keep the blink centred in the opening, rather than sliding up the face.
            eyes.y = -3.375f * (1 - eyes.yScale);
        }
    }
}
