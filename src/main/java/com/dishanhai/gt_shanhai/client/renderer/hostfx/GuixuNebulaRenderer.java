package com.dishanhai.gt_shanhai.client.renderer.hostfx;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

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
        if (!enabled) return;
        Matrix4f matrix = pose.last().pose();
        Matrix4f clip = new Matrix4f(RenderSystem.getProjectionMatrix()).mul(matrix);
        if (!HostFxVisibility.isVisible(clip, 0, 0, 0, GuixuNebulaField.BOUNDS_RADIUS)) return;
        boolean dynamicShader = GuixuNebulaShader.prepare(seconds, degraded);
        var layer = HostFxDraw.nebulaLayer();
        var buffers = MultiBufferSource.immediate(Tesselator.getInstance().getBuilder());
        float light = (0.82F + 0.18F * brightness) * (degraded ? 0.72F : 1F);
        drawVisibleSections(buffers.getBuffer(layer), matrix, pose.last().normal(), clip, camera,
                seconds, light, degraded, infall, horizon, dynamicShader);
        buffers.endBatch(layer);
    }

    static void drawVisibleSections(VertexConsumer vertices, Matrix4f matrix, Matrix3f normalMatrix,
                                    Matrix4f clip, float[] camera, double seconds, float light,
                                    boolean degraded, boolean infall, double horizon, boolean dynamicShader) {
        int segments = GuixuNebulaField.patchCount(true, degraded) / (2 * GuixuNebulaField.LAYER_COUNT);
        int stride = GuixuNebulaField.SEGMENTS_PER_ARM / segments;
        float fallbackPhase = dynamicShader ? 0 : GuixuNebulaField.materialPhase(seconds);
        var frame = new GuixuNebulaField.Frame(seconds, horizon);
        var a = new Section();
        var b = new Section();
        int layers = GuixuNebulaField.LAYER_COUNT + (infall ? 1 : 0);
        for (int cloudLayer = 0; cloudLayer < layers; cloudLayer++) {
            for (int arm = 0; arm < 2; arm++) {
                int start = arm * (GuixuNebulaField.SEGMENTS_PER_ARM + 1);
                a.sample(frame, start, cloudLayer, camera, light);
                for (int step = 1; step <= segments; step++) {
                    b.sample(frame, start + step * stride, cloudLayer, camera, light);
                    if ((a.alpha > 0 || b.alpha > 0) && visible(clip, a, b)) {
                        a.prepare(dynamicShader, fallbackPhase, normalMatrix);
                        b.prepare(dynamicShader, fallbackPhase, normalMatrix);
                        a.vertex(vertices, matrix, -1, 0);
                        b.vertex(vertices, matrix, -1, 0);
                        b.vertex(vertices, matrix, 1, 1);
                        a.vertex(vertices, matrix, 1, 1);
                    }
                    Section previous = a;
                    a = b;
                    b = previous;
                }
            }
        }
    }

    private static boolean visible(Matrix4f clip, Section a, Section b) {
        float x = (float) ((a.x + b.x) * 0.5), y = (float) ((a.y + b.y) * 0.5);
        float z = (float) ((a.z + b.z) * 0.5);
        double dx = Math.abs(a.x - b.x), dy = Math.abs(a.y - b.y), dz = Math.abs(a.z - b.z);
        // 可见性函数使用轴对齐包围盒。每轴都覆盖两端完整截面，云边仍能从屏外进入。
        float halfExtent = Math.max(a.width, b.width) + (float) (0.5 * Math.max(dx, Math.max(dy, dz)));
        return HostFxVisibility.isVisible(clip, x, y, z, halfExtent);
    }

    /** 相邻两片共用截面，沿脊线只更新一次可见截面的 UV、法线和两侧位置。 */
    private static final class Section extends GuixuNebulaField.Sample {
        private boolean prepared;
        private double edgeX, edgeY, edgeZ;
        private float nx, ny, nz;

        void sample(GuixuNebulaField.Frame frame, int index, int layer, float[] camera, float light) {
            if (layer == GuixuNebulaField.LAYER_COUNT) frame.sampleInfall(index, this);
            else frame.sample(index, layer, this);
            double dx = camera[0] - x, dy = camera[1] - y, dz = camera[2] - z;
            double distanceSquared = dx * dx + dy * dy + dz * dz;
            // 飞入云带时淡出。仅在 12 至 32 格的过渡区计算距离。
            float fade = distanceSquared >= 32 * 32 ? 1 : distanceSquared <= 12 * 12 ? 0
                    : (float) ((Math.sqrt(distanceSquared) - 12) / 20);
            alpha = alpha * light * fade;
            prepared = false;
        }

        void prepare(boolean dynamicShader, float fallbackPhase, Matrix3f normal) {
            if (prepared) return;
            if (!dynamicShader) u = GuixuNebulaField.fallbackMaterialU(u, fallbackPhase);
            edgeX = sideX * width;
            edgeY = sideY * width;
            edgeZ = sideZ * width;
            float x = (float) (tangentY * sideZ - tangentZ * sideY);
            float y = (float) (tangentZ * sideX - tangentX * sideZ);
            float z = (float) (tangentX * sideY - tangentY * sideX);
            nx = normal.m00() * x + normal.m10() * y + normal.m20() * z;
            ny = normal.m01() * x + normal.m11() * y + normal.m21() * z;
            nz = normal.m02() * x + normal.m12() * y + normal.m22() * z;
            prepared = true;
        }

        void vertex(VertexConsumer vertices, Matrix4f matrix, int sign, float v) {
            vertices.vertex(matrix, (float) (x + sign * edgeX), (float) (y + sign * edgeY), (float) (z + sign * edgeZ))
                    .uv(u, v)
                    .color(red, green, blue, alpha)
                    .normal(nx, ny, nz).endVertex();
        }
    }
}
