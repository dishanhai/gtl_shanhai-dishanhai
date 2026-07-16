package com.dishanhai.gt_shanhai.client.renderer.machine;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;

import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.client.renderer.machine.WorkableCasingMachineRenderer;

import com.gtladd.gtladditions.utils.CommonUtils;

import org.gtlcore.gtlcore.client.ClientUtil;
import org.gtlcore.gtlcore.utils.RenderUtil;

import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialOmegaEngineMachine;

import org.joml.Quaternionf;

import java.util.List;
import java.util.function.Consumer;

/**
 * 原始终焉引擎 TESR 渲染器
 * 鸿蒙微型宇宙写入 + 蒸汽时代轨道环
 */
public class PrimordialOmegaEngineRenderer extends AbstractRingRenderer {

    private static final ResourceLocation SPACE_MODEL = new ResourceLocation("gtlcore", "obj/space");
    private static final ResourceLocation STAR_MODEL = new ResourceLocation("gtlcore", "obj/star");
    private static final List<ResourceLocation> ORBIT_OBJECTS = List.of(
            new ResourceLocation("gtlcore", "obj/the_nether"),
            new ResourceLocation("gtlcore", "obj/overworld"),
            new ResourceLocation("gtlcore", "obj/the_end")
    );

    private static final int FIXED_COLOR = 0xFFFFFF;
    private static final float R = ((FIXED_COLOR >> 16) & 0xFF) / 255f;
    private static final float G = ((FIXED_COLOR >> 8) & 0xFF) / 255f;
    private static final float B = (FIXED_COLOR & 0xFF) / 255f;

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

        renderStar(smoothTick, poseStack, buffer);
        renderOrbitObjects(smoothTick, poseStack, buffer);
        renderOuterSpaceShell(poseStack, buffer);

        poseStack.popPose();
    }

    @Override
    protected void registerAdditionalModels(Consumer<ResourceLocation> registry) {
        registry.accept(SPACE_MODEL);
        registry.accept(STAR_MODEL);
        for (ResourceLocation obj : ORBIT_OBJECTS) {
            registry.accept(obj);
        }
    }

    // ========== 鸿蒙微型宇宙 ==========

    private static void renderStar(float smoothTick, PoseStack poseStack, MultiBufferSource buffer) {
        poseStack.pushPose();
        poseStack.scale(0.02f, 0.02f, 0.02f);
        poseStack.mulPose(new Quaternionf().fromAxisAngleDeg(0, 1, 1, smoothTick / 200f * 360f % 360f));
        ClientUtil.modelRenderer().renderModel(
                poseStack.last(),
                buffer.getBuffer(RenderType.solid()),
                null,
                ClientUtil.getBakedModel(STAR_MODEL),
                R, G, B, 15728880,
                net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY,
                net.minecraftforge.client.model.data.ModelData.EMPTY,
                RenderType.solid()
        );
        poseStack.popPose();
    }

    private void renderOrbitObjects(float smoothTick, PoseStack poseStack, MultiBufferSource buffer) {
        for (int i = 0; i < ORBIT_OBJECTS.size(); i++) {
            float scale = 0.007f + i * 0.003f;
            double distance = 100 + i * 160
                    + Math.sin(smoothTick / 100f * 1.5f / (i + 1) + 0.4) * 40;

            poseStack.pushPose();
            poseStack.scale(scale, scale, scale);
            poseStack.mulPose(new Quaternionf().fromAxisAngleDeg(1, 0, 1,
                    smoothTick / 100f * 1.5f / (i + 1) * 360f % 360f));
            poseStack.translate(distance, 0, 0);
            ClientUtil.modelRenderer().renderModel(
                    poseStack.last(),
                    buffer.getBuffer(RenderType.solid()),
                    null,
                    ClientUtil.getBakedModel(ORBIT_OBJECTS.get(i)),
                    R, G, B, 15728880,
                    net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY,
                    net.minecraftforge.client.model.data.ModelData.EMPTY,
                    RenderType.solid()
            );
            poseStack.popPose();
        }
    }

    private static void renderOuterSpaceShell(PoseStack poseStack, MultiBufferSource buffer) {
        float scale = 0.175f;
        poseStack.pushPose();
        poseStack.scale(scale, scale, scale);
        ClientUtil.modelRenderer().renderModel(
                poseStack.last(),
                buffer.getBuffer(RenderType.solid()),
                null,
                ClientUtil.getBakedModel(SPACE_MODEL),
                R, G, B, 15728880,
                net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY,
                net.minecraftforge.client.model.data.ModelData.EMPTY,
                RenderType.solid()
        );
        poseStack.popPose();
    }
}
