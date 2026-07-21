package com.dishanhai.gt_shanhai.client.renderer.machine;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;

import com.gregtechceu.gtceu.api.machine.MetaMachine;

import com.gtladd.gtladditions.utils.CommonUtils;

import org.gtlcore.gtlcore.utils.RenderUtil;

import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialOmegaEngineMachine;

import org.joml.Quaternionf;

import java.util.function.Consumer;

/**
 * 原始终焉引擎 TESR 渲染器
 * 鸿蒙微型宇宙写入 + 蒸汽时代轨道环
 */
public class PrimordialOmegaEngineRenderer extends AbstractRingRenderer {

    private static final ResourceLocation SPACE_MODEL = new ResourceLocation("gtlcore", "obj/space");
    private static final ResourceLocation STAR_MODEL = new ResourceLocation("gtlcore", "obj/star");

    public PrimordialOmegaEngineRenderer(ResourceLocation baseCasing, ResourceLocation workableModel) {
        super(baseCasing, workableModel);
    }

    @Override
    protected VertexBuffer[] getRingBuffers(MetaMachine machine) {
        return PrimordialOmegaEngineRingBuffer.getRingBuffers();
    }

    @Override
    protected float getSmoothTick(MetaMachine machine, float partialTick) {
        if (machine instanceof PrimordialOmegaEngineMachine poe && poe.getRecipeLogic().isWorking()) {
            return RenderUtil.getSmoothTick(poe, partialTick);
        }
        return 0f;
    }

    @Override
    protected void renderSpecialEffects(MetaMachine machine, BlockEntity blockEntity,
                                        float smoothTick, boolean isWorking,
                                        Direction facing,
                                        PoseStack poseStack, MultiBufferSource buffer) {
        if (Minecraft.getInstance().screen != null) return;

        Vec3 centerPos = CommonUtils.INSTANCE.getRotatedRenderPosition(
                Direction.EAST, facing, -122.0, 0.0, 0.0);

        poseStack.pushPose();
        poseStack.translate(centerPos.x, centerPos.y, centerPos.z);

        VertexBuffer[] modelBuffers = PrimordialOmegaEngineModelBuffers.getBuffers();
        if (modelBuffers == null) {
            poseStack.popPose();
            return;
        }

        PrimordialOmegaEngineModelBuffers.beginRender();
        try {
            renderStar(smoothTick, poseStack, modelBuffers[PrimordialOmegaEngineModelBuffers.STAR]);
            renderOrbitObjects(smoothTick, poseStack, modelBuffers);
            renderOuterSpaceShell(poseStack, modelBuffers[PrimordialOmegaEngineModelBuffers.SPACE]);
        } finally {
            PrimordialOmegaEngineModelBuffers.endRender();
        }

        poseStack.popPose();
    }

    @Override
    protected void registerAdditionalModels(Consumer<ResourceLocation> registry) {
        registry.accept(SPACE_MODEL);
        registry.accept(STAR_MODEL);
        registry.accept(new ResourceLocation("gtlcore", "obj/the_nether"));
        registry.accept(new ResourceLocation("gtlcore", "obj/overworld"));
        registry.accept(new ResourceLocation("gtlcore", "obj/the_end"));
    }

    // ========== 鸿蒙微型宇宙 ==========

    private static void renderStar(float smoothTick, PoseStack poseStack, VertexBuffer buffer) {
        poseStack.pushPose();
        poseStack.scale(0.02f, 0.02f, 0.02f);
        poseStack.mulPose(new Quaternionf().fromAxisAngleDeg(0, 1, 1, smoothTick / 200f * 360f % 360f));
        PrimordialOmegaEngineModelBuffers.draw(buffer, poseStack);
        poseStack.popPose();
    }

    private void renderOrbitObjects(float smoothTick, PoseStack poseStack, VertexBuffer[] buffers) {
        for (int i = 0; i < 3; i++) {
            float scale = 0.007f + i * 0.003f;
            double distance = 100 + i * 160
                    + Math.sin(smoothTick / 100f * 1.5f / (i + 1) + 0.4) * 40;

            poseStack.pushPose();
            poseStack.scale(scale, scale, scale);
            poseStack.mulPose(new Quaternionf().fromAxisAngleDeg(1, 0, 1,
                    smoothTick / 100f * 1.5f / (i + 1) * 360f % 360f));
            poseStack.translate(distance, 0, 0);
            PrimordialOmegaEngineModelBuffers.draw(
                    buffers[PrimordialOmegaEngineModelBuffers.ORBIT_START + i], poseStack);
            poseStack.popPose();
        }
    }

    private static void renderOuterSpaceShell(PoseStack poseStack, VertexBuffer buffer) {
        float scale = 0.175f;
        poseStack.pushPose();
        poseStack.scale(scale, scale, scale);
        PrimordialOmegaEngineModelBuffers.draw(buffer, poseStack);
        poseStack.popPose();
    }
}
