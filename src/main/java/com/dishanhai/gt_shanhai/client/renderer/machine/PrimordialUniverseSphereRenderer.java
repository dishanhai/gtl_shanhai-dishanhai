package com.dishanhai.gt_shanhai.client.renderer.machine;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;

import org.joml.Quaternionf;

/**
 * 宇宙渲染器：鸿蒙微型宇宙。
 * 中心恒星 + 三颗行星轨道 + 外层星空壳，全部走 {@link PrimordialOmegaEngineModelBuffers} 的静态 VBO。
 */
final class PrimordialUniverseSphereRenderer {

    private PrimordialUniverseSphereRenderer() {}

    static void render(float smoothTick, Direction facing, PoseStack poseStack) {
        // 打开 GUI 时跳过：本路径直接改 RenderSystem 全局状态，与 GUI 绘制抢状态会串色
        if (Minecraft.getInstance().screen != null) return;

        VertexBuffer[] modelBuffers = PrimordialOmegaEngineModelBuffers.getBuffers();
        if (modelBuffers == null) return;

        Vec3 centerPos = PrimordialSphereAnchor.center(facing);

        poseStack.pushPose();
        poseStack.translate(centerPos.x, centerPos.y, centerPos.z);

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

    private static void renderStar(float smoothTick, PoseStack poseStack, VertexBuffer buffer) {
        poseStack.pushPose();
        poseStack.scale(0.02f, 0.02f, 0.02f);
        poseStack.mulPose(new Quaternionf().fromAxisAngleDeg(0, 1, 1, smoothTick / 200f * 360f % 360f));
        PrimordialOmegaEngineModelBuffers.draw(buffer, poseStack);
        poseStack.popPose();
    }

    private static void renderOrbitObjects(float smoothTick, PoseStack poseStack, VertexBuffer[] buffers) {
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
