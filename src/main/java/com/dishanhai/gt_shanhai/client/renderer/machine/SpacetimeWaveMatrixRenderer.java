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
import com.gtladd.gtladditions.client.RenderMode;
import com.gtladd.gtladditions.client.render.machine.antichrist.AntichristRenderProfile;
import com.gtladd.gtladditions.client.render.machine.antichrist.AntichristStarRenderer;
import com.gtladd.gtladditions.client.render.machine.antichrist.AntichristBeamRenderer;

import org.gtlcore.gtlcore.utils.RenderUtil;

import com.dishanhai.gt_shanhai.common.machine.spacetime.SpacetimeWaveMatrixMachine;

import java.util.function.Consumer;

/**
 * 终焉超时空波动矩阵 TESR 渲染器
 * 使用 add（gtladditions 3.2.1）的 Antichrist 新 API 渲染恒星与光束
 */
public class SpacetimeWaveMatrixRenderer extends AbstractRingRenderer {

    private static final ResourceLocation STAR_LAYER_0 = new ResourceLocation("gtladditions", "obj/star_layer_0");
    private static final ResourceLocation STAR_LAYER_2 = new ResourceLocation("gtladditions", "obj/star_layer_2");

    public SpacetimeWaveMatrixRenderer(ResourceLocation baseCasing, ResourceLocation workableModel) {
        super(baseCasing, workableModel);
    }

    @Override
    protected VertexBuffer[] getRingBuffers(MetaMachine machine) {
        return SpacetimeWaveMatrixRingBuffer.getRingBuffers();
    }

    @Override
    protected float getSmoothTick(MetaMachine machine, float partialTick) {
        if (machine instanceof SpacetimeWaveMatrixMachine swm && swm.getRecipeLogic().isWorking()) {
            return RenderUtil.getSmoothTick(swm, partialTick);
        }
        return 0f;
    }

    @Override
    protected void renderSpecialEffects(MetaMachine machine, BlockEntity blockEntity,
                                        float smoothTick, boolean isWorking,
                                        Direction facing,
                                        PoseStack poseStack, MultiBufferSource buffer) {
        if (!isWorking) return;

        var swm = (SpacetimeWaveMatrixMachine) machine;

        Vec3 centerPos = CommonUtils.INSTANCE.getRotatedRenderPosition(
                Direction.EAST, facing, -122.0, 0.0, 0.0);

        int packedColor = swm.getRgbFromTime();
        float r = ((packedColor >> 16) & 0xFF) / 255.0f;
        float g = ((packedColor >> 8) & 0xFF) / 255.0f;
        float b = (packedColor & 0xFF) / 255.0f;

        float radius = swm.getRadiusMultiplier();
        float starRadius = 0.175f * radius;

        // 使用 AntichristRenderer 3.2.1 新 API 渲染恒星与光束
        AntichristRenderProfile profile = new AntichristRenderProfile(
                smoothTick, true, facing, RenderMode.NORMAL, centerPos,
                r, g, b, starRadius, 0.0f
        );

        // 光束（从机器到恒星位置）
        AntichristBeamRenderer.INSTANCE.render(profile, poseStack, blockEntity);

        // 渲染恒星（不透明层 + 透明覆盖层）
        poseStack.pushPose();
        poseStack.translate(centerPos.x, centerPos.y, centerPos.z);

        AntichristStarRenderer.INSTANCE.renderOpaqueOriginalColor(profile, poseStack, STAR_LAYER_0);
        AntichristStarRenderer.INSTANCE.renderTransparent(profile, poseStack);

        poseStack.popPose();
    }

    @Override
    protected void registerAdditionalModels(Consumer<ResourceLocation> registry) {
        registry.accept(STAR_LAYER_0);
        registry.accept(STAR_LAYER_2);
    }
}