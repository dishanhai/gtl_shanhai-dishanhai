package com.dishanhai.gt_shanhai.client.renderer.hostfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** 山海：星芒與彎曲軌跡的短拖尾，共用既有加性層，一批提交、不寫深度。 */
public final class GuixuStarRenderer {
    private GuixuStarRenderer() {}

    /** 呼叫方已將 PoseStack 放到環心並套用機器朝向，相機也使用同一座標系。 */
    public static void render(PoseStack pose, float[] camera, double seconds, double surfaceRadius,
                              float density, float brightness, float jets) {
        var layer = HostFxDraw.beamLayer();
        var buffers = MultiBufferSource.immediate(Tesselator.getInstance().getBuilder());
        VertexConsumer vertices = buffers.getBuffer(layer);
        Matrix4f matrix = pose.last().pose();
        for (int i = 0; i < GuixuStarField.PARTICLE_COUNT; i++) {
            var star = GuixuStarField.sample(i, seconds, surfaceRadius);
            float visibility = Math.max(0.0F, Math.min(1.0F, (density * GuixuStarField.PARTICLE_COUNT - i) / 8.0F));
            float alpha = star.alpha() * visibility * brightness;
            if (alpha <= 0.001F) continue;
            Vector3f head = new Vector3f((float) star.headX(), (float) star.headY(), (float) star.headZ());
            Vector3f tail = new Vector3f((float) star.tailX(), (float) star.tailY(), (float) star.tailZ());
            Vector3f view = new Vector3f(camera[0], camera[1], camera[2]).sub(head);
            if (view.lengthSquared() < 1.0e-8F) view.set(0, 0, 1);
            view.normalize();
            Vector3f right = view.cross(0, 1, 0, new Vector3f());
            if (right.lengthSquared() < 1.0e-8F) right.set(1, 0, 0);
            right.normalize();
            Vector3f up = right.cross(view, new Vector3f()).normalize();
            float red = star.warm() ? 1.0F : 0.56F;
            float green = star.warm() ? 0.78F : 0.80F;
            float blue = star.warm() ? 0.48F : 1.0F;

            Vector3f width = new Vector3f(tail).sub(head).cross(view);
            if (width.lengthSquared() < 1.0e-8F) width.set(right);
            width.normalize().mul(star.size() * 0.38F);
            // 尾端透明，星點所在的前端明亮；拖尾跟隨軌跡而非固定朝向。
            vertex(vertices, matrix, new Vector3f(tail).add(width), red, green, blue, 0);
            vertex(vertices, matrix, new Vector3f(head).add(width), red, green, blue, alpha * 0.65F);
            vertex(vertices, matrix, new Vector3f(head).sub(width), red, green, blue, alpha * 0.65F);
            vertex(vertices, matrix, new Vector3f(tail).sub(width), red, green, blue, 0);
            glint(vertices, matrix, head, right, up, star.size() * 2.0F, star.size() * 0.22F,
                    red, green, blue, alpha * 0.65F);
            glint(vertices, matrix, head, right, up, star.size() * 0.22F, star.size() * 2.0F,
                    red, green, blue, alpha * 0.65F);
            glint(vertices, matrix, head, right, up, star.size() * 0.55F, star.size() * 0.55F,
                    1.0F, 0.96F, 0.90F, alpha);
        }
        if (jets > 0.001F) drawJets(vertices, matrix, seconds, surfaceRadius, jets);
        buffers.endBatch(layer);
    }

    /** 發電時的雙極噴流，使用同一批加性頂點，兩端淡出。 */
    private static void drawJets(VertexConsumer vertices, Matrix4f matrix, double seconds, double horizon, float strength) {
        for (int sign : new int[]{-1, 1}) {
            for (int segment = 0; segment < 12; segment++) {
                float t0 = segment / 12.0F, t1 = (segment + 1) / 12.0F;
                float r0 = 0.18F + 0.32F * (float) Math.sin(t0 * Math.PI);
                float r1 = 0.18F + 0.32F * (float) Math.sin(t1 * Math.PI);
                float y0 = sign * (float) (horizon + 0.5 + t0 * 28.0);
                float y1 = sign * (float) (horizon + 0.5 + t1 * 28.0);
                float a0 = jetAlpha(t0, seconds, strength), a1 = jetAlpha(t1, seconds, strength);
                for (int facet = 0; facet < 8; facet++) {
                    double p0 = facet * Math.PI / 4.0, p1 = (facet + 1) * Math.PI / 4.0;
                    jetVertex(vertices, matrix, p0, r0, y0, a0);
                    jetVertex(vertices, matrix, p1, r0, y0, a0);
                    jetVertex(vertices, matrix, p1, r1, y1, a1);
                    jetVertex(vertices, matrix, p0, r1, y1, a1);
                }
            }
        }
    }

    private static float jetAlpha(float t, double seconds, float strength) {
        return strength * 0.48F * (float) Math.sin(t * Math.PI)
                * (0.75F + 0.25F * (float) Math.sin(seconds * 6.0 - t * 18.0));
    }

    private static void jetVertex(VertexConsumer vertices, Matrix4f matrix, double angle, float radius, float y, float alpha) {
        vertices.vertex(matrix, (float) Math.cos(angle) * radius, y, (float) Math.sin(angle) * radius)
                .color(0.35F, 0.8F, 1.0F, alpha).endVertex();
    }

    private static void glint(VertexConsumer vertices, Matrix4f matrix, Vector3f center,
                              Vector3f right, Vector3f up, float horizontal, float vertical,
                              float red, float green, float blue, float alpha) {
        vertex(vertices, matrix, new Vector3f(center).fma(horizontal, right), red, green, blue, alpha);
        vertex(vertices, matrix, new Vector3f(center).fma(vertical, up), red, green, blue, alpha);
        vertex(vertices, matrix, new Vector3f(center).fma(-horizontal, right), red, green, blue, alpha);
        vertex(vertices, matrix, new Vector3f(center).fma(-vertical, up), red, green, blue, alpha);
    }

    private static void vertex(VertexConsumer vertices, Matrix4f matrix, Vector3f point,
                               float red, float green, float blue, float alpha) {
        vertices.vertex(matrix, point.x, point.y, point.z).color(red, green, blue, alpha).endVertex();
    }
}
