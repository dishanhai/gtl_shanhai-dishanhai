package com.shanhai.client.renderer.machine;

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

// 🔴 gtladditions 的引用经【客户端侧唯一网关】转手：本文件不出现 com.gtladd 包名。
//    （旋转数学 RotatedRenderPosition 是本包内的本地实现，见该类注释的取证）
import com.shanhai.api.ShanhaiClockHook;
import com.shanhai.client.compat.GtlAddClientCompat;

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

    /**
     * 子类渲染特殊效果（环渲染之后、完全自定义）。
     * <p>
     * smoothTick 沿用「停机即 0」的哨兵语义（isWorking 就是由它推导的），所以它<b>不是</b>连续时钟：
     * 工作状态翻转时会在 0 与大数值之间硬跳。常驻可见的特效若把它当累积角度用会看到突兀的弹跳，
     * 这类子类应改用 partialTick 自行取连续时钟（{@code RenderUtil.getSmoothTick}）。
     */
    protected abstract void renderSpecialEffects(MetaMachine machine, BlockEntity blockEntity,
                                                  float smoothTick, boolean isWorking,
                                                  Direction facing, float partialTick,
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

        // 环方块隐藏/恢复（每帧，与修改矩阵一致）。
        //
        // ⚠️ 2026-09-21：这里的 `else` <b>不再</b>等于"立刻 restore"。
        //    `!isFormed()` 是抖动的（结构部件所在区块卸载 ⇒ GTCEu onPartUnload → UNLOAD_ERROR →
        //    每 4 tick 异步重查 ⇒ 本轮实测 12 次成型 / 6 次 restore，瞬时窗口最长 3.012 秒），
        //    而 restore 会真的写回一整片方块。所以去抖做在 GtlAddClientCompat.restoreRingsAt 内部：
        //    要求"连续未成型 ≥ shanhai-common.toml 的 ringRestoreDebounceTicks（默认 100 tick = 5 秒）"
        //    才执行。本调用点仍然每帧调用（O(1)：只是查表 + 比较 tick），去抖状态与台账一起按主机存在网关里。
        var clientLevel = Minecraft.getInstance().level;
        // 🔴 2026-10-18（缺陷「环特效忽隐忽现」）：绘制闸门改成"去抖后的成型态"。
        //    controller.isFormed() 本身是抖的（结构部件区块卸载 ⇒ onPartUnload ⇒ UNLOAD_ERROR ⇒
        //    每 4 tick 异步重查；上方注释记着实测 12 次成型 / 6 次 restore、瞬时窗口最长 3.012 秒），
        //    原样拿它当绘制闸门 ⇒ 一瞬未成型 = 环特效消失一帧再回来 = 用户说的"突然没了，然后又有了"。
        //    isFormedStable 的阈值与 restore 去抖同一个（默认 100 tick），计时源是
        //    level.getGameTime()（暂停时它会停走 ⇒ 暂停期间环不会"计时到点消失"）。
        boolean formedStable = controller.isFormed();
        if (clientLevel instanceof ClientLevel) {
            long posLong = blockEntity.getBlockPos().asLong();
            formedStable = GtlAddClientCompat.isFormedStable(clientLevel, posLong, formedStable);
            if (controller.isFormed()) {
                GtlAddClientCompat.hideRingsAt(clientLevel, posLong, facing);
            } else {
                // 内部自带"连续未成型 ≥ 阈值"的去抖；本帧只是喂给它一个"仍未成型"的采样。
                GtlAddClientCompat.restoreRingsAt(clientLevel, posLong, facing);
            }
        }

        if (!formedStable) return;

        // ④ 暂停期间冻结"连续渲染时钟"（缺陷「机器动画还在动」）。
        //    第一道在扩展（shanhai_add）的 gtlcore GlobalRenderClock mixin 上；这里是第二道：
        //    万一那条 mixin 因为 gtlcore 换版本静默失效，我们的机器环仍然冻得住。
        //    🔴 2026-10-18 拆分：时钟整块搬进扩展后，本体不再 import 它的类 ——
        //    这里只问本体自己的接缝 ShanhaiClockHook（没装扩展 ⇒ 原值透传，等价"时钟恒 LIVE"）。
        //    ⚠️ 这不是"全局钉 partialTick"——它只影响我们自己的环/恒星动画，玩家相机一点没碰。
        float smoothTick = ShanhaiClockHook.smoothTick(getSmoothTick(meta, partialTick));
        boolean isWorking = smoothTick > 0;

        // 轨道环
        renderAllRings(getRingBuffers(meta), facing, smoothTick, isWorking, poseStack);

        // 特殊效果（由子类自行处理 centerPos 定位）。
        // partialTick 在这里同样只被"我们自己的特效"消费 ⇒ 暂停时局部传 0（更窄的口子，不动全局 α）。
        float effectPartialTick = ShanhaiClockHook.partialTick(partialTick);
        renderSpecialEffects(meta, blockEntity, smoothTick, isWorking, facing, effectPartialTick, poseStack, buffer);
    }

    // ========== 轨道环渲染（共享） ==========

    public static void renderAllRings(VertexBuffer[] ringBuffers, Direction facing,
                                      float smoothTick, boolean isWorking, PoseStack poseStack) {
        if (ringBuffers == null || ringBuffers.length == 0) return;

        Vec3 centerPos = RotatedRenderPosition.rotate(
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
