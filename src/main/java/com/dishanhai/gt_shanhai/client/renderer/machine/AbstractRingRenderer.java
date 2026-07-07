package com.dishanhai.gt_shanhai.client.renderer.machine;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.platform.GlStateManager;

import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.client.renderer.machine.WorkableCasingMachineRenderer;

import com.gtladd.gtladditions.utils.CommonUtils;
import com.gtladd.gtladditions.utils.antichrist.ClientRingBlockHelper;

import org.joml.Quaternionf;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * 轨道环机器渲染器基类。
 * 统一处理环方块隐藏/恢复（每帧）和轨道环渲染，
 * 子类实现各自的特殊效果（恒星/光晕/微型宇宙等）。
 */
public abstract class AbstractRingRenderer extends WorkableCasingMachineRenderer {

    public AbstractRingRenderer(ResourceLocation baseCasing, ResourceLocation workableModel) {
        super(baseCasing, workableModel);
    }

    /** 子类提供环缓冲区 */
    protected abstract VertexBuffer[] getRingBuffers(MetaMachine machine);

    /** 子类渲染特殊效果（环渲染之后、完全自定义） */
    protected abstract void renderSpecialEffects(MetaMachine machine, BlockEntity blockEntity,
                                                  float smoothTick, boolean isWorking,
                                                  Direction facing,
                                                  PoseStack poseStack, MultiBufferSource buffer);

    /** 子类计算平滑 tick，用于环旋转动画 */
    protected abstract float getSmoothTick(MetaMachine machine, float partialTick);

    /** 子类注册额外模型 */
    protected abstract void registerAdditionalModels(Consumer<ResourceLocation> registry);

    // ========== TESR ==========

    @Override
    public boolean hasTESR(BlockEntity blockEntity) { return true; }

    @Override
    public boolean isGlobalRenderer(BlockEntity blockEntity) { return true; }

    @Override
    public int getViewDistance() { return 384; }

    @Override
    public void onAdditionalModel(Consumer<ResourceLocation> registry) {
        super.onAdditionalModel(registry);
        registerAdditionalModels(registry);
    }

    @Override
    public void render(BlockEntity blockEntity, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight, int packedOverlay) {
        MetaMachine meta = getMetaMachine(blockEntity);
        if (!(meta instanceof IMultiController controller)) return;

        Direction facing = meta.getFrontFacing();

        // 环方块隐藏/恢复（每帧，与修改矩阵一致）
        var clientLevel = Minecraft.getInstance().level;
        if (clientLevel instanceof ClientLevel) {
            long posLong = blockEntity.getBlockPos().asLong();
            if (controller.isFormed()) {
                ClientRingBlockHelper.INSTANCE.hideRingsAtPosition(clientLevel, posLong, facing);
            } else {
                ClientRingBlockHelper.INSTANCE.restoreRingsAtPosition(clientLevel, posLong, facing);
            }
        }

        if (!controller.isFormed()) return;

        float smoothTick = getSmoothTick(meta, partialTick);
        boolean isWorking = smoothTick > 0;

        // 轨道环
        renderAllRings(getRingBuffers(meta), facing, smoothTick, isWorking, poseStack);

        // 特殊效果（由子类自行处理 centerPos 定位）
        renderSpecialEffects(meta, blockEntity, smoothTick, isWorking, facing, poseStack, buffer);
    }

    // ========== 轨道环渲染（共享） ==========

    public static void renderAllRings(VertexBuffer[] ringBuffers, Direction facing,
                                      float smoothTick, boolean isWorking, PoseStack poseStack) {
        if (ringBuffers == null || ringBuffers.length == 0) return;

        Vec3 centerPos = CommonUtils.INSTANCE.getRotatedRenderPosition(
                Direction.EAST, facing, -122.0, 0.0, 0.0);

        RenderSystem.enableBlend();
        RenderSystem.enableDepthTest();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        RenderSystem.setShader(GameRenderer::getRendertypeSolidShader);
        RenderSystem.setShaderTexture(0, InventoryMenu.BLOCK_ATLAS);
        Minecraft.getInstance().gameRenderer.lightTexture().turnOnLightLayer();

        int ringIdx = 0;
        for (VertexBuffer buf : ringBuffers) {
            int idx = ringIdx++;
            poseStack.pushPose();
            poseStack.translate(centerPos.x, centerPos.y, centerPos.z);

            switch (facing) {
                case NORTH -> poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(270f));
                case SOUTH -> poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(90f));
                case WEST  -> poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(0f));
                case EAST  -> poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(180f));
            }

            if (isWorking) {
                float dir = (idx == 1) ? -1.0f : 1.0f;
                float speedFactor = 0.4f + idx * 0.4f;
                float baseAngle = idx * 120.0f;
                float angle = (smoothTick * speedFactor * 2.0f * dir + baseAngle) % 360.0f;
                poseStack.mulPose(new Quaternionf().fromAxisAngleDeg(1, 0, 0, angle));
            }

            buf.bind();
            buf.drawWithShader(poseStack.last().pose(), RenderSystem.getProjectionMatrix(),
                    Objects.requireNonNull(RenderSystem.getShader()));
            VertexBuffer.unbind();
            poseStack.popPose();
        }

        RenderSystem.disableDepthTest();
        RenderSystem.disableBlend();
    }

    // ========== 工具 ==========

    protected static MetaMachine getMetaMachine(BlockEntity blockEntity) {
        if (blockEntity instanceof IMachineBlockEntity) {
            return ((IMachineBlockEntity) blockEntity).getMetaMachine();
        }
        return null;
    }
}
