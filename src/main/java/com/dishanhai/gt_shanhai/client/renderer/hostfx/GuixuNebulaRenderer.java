package com.dishanhai.gt_shanhai.client.renderer.hostfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.MultiBufferSource;
import org.joml.Matrix4f;
import org.joml.Matrix3f;

/** 山海：連續寬旋臂；彩色絲狀雲氣單批提交，不以重疊粒子堆亮度。 */
public final class GuixuNebulaRenderer {
    private GuixuNebulaRenderer() {}

    /** PoseStack 與相機皆已轉到主機環心座標。 */
    public static void render(PoseStack pose, float[] camera, double seconds, float brightness,
                              boolean enabled, boolean degraded) {
        render(pose, camera, seconds, brightness, enabled, degraded, false, 1);
    }

    public static void render(PoseStack pose, float[] camera, double seconds, float brightness,
                              boolean enabled, boolean degraded, boolean infall, double horizon) {
        int count = GuixuNebulaField.patchCount(enabled, degraded);
        if (count == 0) return;
        boolean dynamicShader = GuixuNebulaShader.prepare(seconds, degraded);
        var layer = HostFxDraw.nebulaLayer();
        var buffers = MultiBufferSource.immediate(Tesselator.getInstance().getBuilder());
        VertexConsumer vertices = buffers.getBuffer(layer);
        Matrix4f matrix = pose.last().pose();
        Matrix3f normalMatrix = pose.last().normal();
        Matrix4f clip = new Matrix4f(RenderSystem.getProjectionMatrix()).mul(matrix);
        float light = (0.82F + 0.18F * brightness) * (degraded ? 0.72F : 1F);
        int segments = count / (2 * GuixuNebulaField.LAYER_COUNT);
        int stride = GuixuNebulaField.SEGMENTS_PER_ARM / segments;
        for (int cloudLayer = 0; cloudLayer < GuixuNebulaField.LAYER_COUNT + (infall ? 1 : 0); cloudLayer++) for (int arm = 0; arm < 2; arm++) {
            int start = arm * (GuixuNebulaField.SEGMENTS_PER_ARM + 1);
            boolean stream = cloudLayer == GuixuNebulaField.LAYER_COUNT;
            var a = stream ? GuixuNebulaField.sampleInfall(start, seconds, horizon)
                    : GuixuNebulaField.sample(start, seconds, cloudLayer);
            for (int step = 1; step <= segments; step++) {
                var b = stream ? GuixuNebulaField.sampleInfall(start + step * stride, seconds, horizon)
                        : GuixuNebulaField.sample(start + step * stride, seconds, cloudLayer);
                float x = (float) ((a.x() + b.x()) * 0.5), y = (float) ((a.y() + b.y()) * 0.5);
                float z = (float) ((a.z() + b.z()) * 0.5);
                double dx = a.x() - b.x(), dy = a.y() - b.y(), dz = a.z() - b.z();
                float radius = Math.max(a.width(), b.width()) + (float) (0.5 * Math.sqrt(dx * dx + dy * dy + dz * dz));
                // 包含兩端截面全寬，中心在畫面外時仍保留可見雲邊。
                if (HostFxVisibility.isVisible(clip, x, y, z, radius)) {
                    float alphaA = a.alpha() * light * proximityFade(a, camera);
                    float alphaB = b.alpha() * light * proximityFade(b, camera);
                    vertex(vertices, matrix, normalMatrix, a, -1, 0, alphaA, dynamicShader, seconds);
                    vertex(vertices, matrix, normalMatrix, b, -1, 0, alphaB, dynamicShader, seconds);
                    vertex(vertices, matrix, normalMatrix, b, 1, 1, alphaB, dynamicShader, seconds);
                    vertex(vertices, matrix, normalMatrix, a, 1, 1, alphaA, dynamicShader, seconds);
                }
                a = b;
            }
        }
        buffers.endBatch(layer);
    }

    /** 飛入雲帶時柔和淡出，避免近處大面積覆蓋控制室或相機。 */
    private static float proximityFade(GuixuNebulaField.Knot knot, float[] camera) {
        double dx = camera[0] - knot.x(), dy = camera[1] - knot.y(), dz = camera[2] - knot.z();
        return (float) Math.max(0, Math.min(1, (Math.sqrt(dx * dx + dy * dy + dz * dz) - 12) / 20));
    }

    private static void vertex(VertexConsumer vertices, Matrix4f matrix, Matrix3f normalMatrix, GuixuNebulaField.Knot knot,
                               float sign, float v, float alpha, boolean dynamicShader, double seconds) {
        float width = knot.width() * sign;
        vertices.vertex(matrix, (float) (knot.x() + knot.sideX() * width), (float) (knot.y() + knot.sideY() * width),
                        (float) (knot.z() + knot.sideZ() * width))
                .uv(dynamicShader ? knot.u() : GuixuNebulaField.fallbackU(knot.u(), seconds), v)
                .color(knot.red(), knot.green(), knot.blue(), alpha)
                .normal(normalMatrix, (float) (knot.tangentY() * knot.sideZ() - knot.tangentZ() * knot.sideY()),
                        (float) (knot.tangentZ() * knot.sideX() - knot.tangentX() * knot.sideZ()),
                        (float) (knot.tangentX() * knot.sideY() - knot.tangentY() * knot.sideX())).endVertex();
    }
}
