package com.dishanhai.gt_shanhai.client.renderer.hostfx;

// Author: dishanhai
import com.dishanhai.gt_shanhai.client.renderer.blackhole.BlackHoleLensSpec;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;

/** 山海：只包圍黑洞近旁的深空幕；隨視界半徑縮放，不延伸至歸墟內環及站體。 */
public final class GuixuDeepSpaceBackdrop {
    private GuixuDeepSpaceBackdrop() {}
    private static RenderType layer;
    public interface Sink { void vertex(float x, float y, float z, float alpha); }
    private static final int SEGMENTS = 64;
    private static final float[] X = new float[SEGMENTS + 1], Y = new float[SEGMENTS + 1];
    static {
        for (int i = 0; i < SEGMENTS; i++) {
            double angle = i * Math.TAU / SEGMENTS;
            X[i] = (float) Math.cos(angle);
            Y[i] = (float) Math.sin(angle);
        }
        X[SEGMENTS] = X[0];
        Y[SEGMENTS] = Y[0];
    }

    public static void emit(float[] camera, double horizon, Sink sink) {
        double length = cameraDistance(camera);
        float opacity = opacity(length, horizon);
        if (opacity <= 0) return;
        Matrix4f plane = backdropPlane(camera, horizon, length);
        Vector3f point = new Vector3f();
        drawPatches(null, opacity, (x, y, z, alpha) -> {
            plane.transformPosition(x, y, z, point);
            sink.vertex(point.x, point.y, point.z, alpha);
        });
    }

    private static Matrix4f backdropPlane(float[] camera, double horizon, double length) {
        double extent = GuixuStarField.environmentRadius(horizon);
        double vx = length > 1e-5 ? camera[0]/length : 0;
        double vy = length > 1e-5 ? camera[1]/length : 0;
        double vz = length > 1e-5 ? camera[2]/length : 1;
        double rightLength = Math.hypot(vx, vz);
        double rx = rightLength > 1e-5 ? vz/rightLength : 1, rz = rightLength > 1e-5 ? -vx/rightLength : 0;
        double ux = vy*rz, uy = vz*rx-vx*rz, uz = -vy*rx;
        double width = extent * .96, depth = -extent * .25;
        return new Matrix4f().set(
                (float) (rx * width), 0, (float) (rz * width), 0,
                (float) (ux * width), (float) (uy * width), (float) (uz * width), 0,
                (float) vx, (float) vy, (float) vz, 0,
                (float) (depth * vx), (float) (depth * vy), (float) (depth * vz), 1);
    }

    static void drawPatches(FrustumIntersection frustum, float opacity, Sink sink) {
        float inner = 0, innerAlpha = .997F * opacity;
        // Rings 0..7 have the same opacity. Their original outer polygon is
        // sufficient for the entire flat centre; retain every gradient stop.
        for (int ring = 7; ring <= 12; ring++) {
            float outer = ring / 12F;
            double fade = Math.clamp((ring / 12.0 - .62) / .38, 0, 1);
            float outerAlpha = (float) (.997 * (1 - fade * fade * (3 - 2 * fade))) * opacity;
            for (int first = 0; first < SEGMENTS; first += 8) {
                int last = first + 8;
                if (frustum != null) {
                    // Sine and cosine are monotone over each octant.
                    float x0 = Math.min(X[first], X[last]), x1 = Math.max(X[first], X[last]);
                    float y0 = Math.min(Y[first], Y[last]), y1 = Math.max(Y[first], Y[last]);
                    if (!frustum.testAab(Math.min(inner * x0, outer * x0), Math.min(inner * y0, outer * y0), 0,
                            Math.max(inner * x1, outer * x1), Math.max(inner * y1, outer * y1), 0)) continue;
                }
                for (int i = first; i < last; i++) {
                    sink.vertex(inner * X[i], inner * Y[i], 0, innerAlpha);
                    sink.vertex(outer * X[i], outer * Y[i], 0, outerAlpha);
                    sink.vertex(outer * X[i + 1], outer * Y[i + 1], 0, outerAlpha);
                    sink.vertex(inner * X[i + 1], inner * Y[i + 1], 0, innerAlpha);
                }
            }
            inner = outer;
            innerAlpha = outerAlpha;
        }
    }

    private static double cameraDistance(float[] camera) {
        return Math.sqrt(camera[0]*(double)camera[0] + camera[1]*(double)camera[1]
                + camera[2]*(double)camera[2]);
    }

    /** 與洞內透鏡共用進入判據；提前淡出，避免透鏡把黑幕當成世界背景取樣。 */
    private static float opacity(double distance, double horizon) {
        double threshold = BlackHoleLensSpec.insideThresholdBlocks(horizon / BlackHoleLensSpec.B_CRIT);
        double fade = Math.max(0, Math.min(1, (distance - threshold) / Math.max(2, horizon * .5)));
        return (float)(fade * fade * (3 - 2 * fade));
    }

    public static void render(PoseStack pose, float[] camera, double horizon) {
        double length = cameraDistance(camera);
        float opacity = opacity(length, horizon);
        if (opacity <= 0) return;
        Matrix4f matrix = new Matrix4f(pose.last().pose()).mul(backdropPlane(camera, horizon, length));
        var frustum = new FrustumIntersection(new Matrix4f(RenderSystem.getProjectionMatrix()).mul(matrix), false);
        int visibility = frustum.intersectAab(-1, -1, 0, 1, 1, 0);
        if (visibility >= 0) return;
        if (layer == null) layer = RenderType.create("shanhai_guixu_deep_space",
                DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS, 32768, false, false,
                RenderType.CompositeState.builder()
                        .setShaderState(new RenderStateShard.ShaderStateShard(GameRenderer::getPositionColorShader))
                        .setTransparencyState(new RenderStateShard.TransparencyStateShard("shanhai_deep_space_alpha", () -> {
                            RenderSystem.enableBlend(); RenderSystem.defaultBlendFunc();
                        }, RenderSystem::disableBlend))
                        .setDepthTestState(new RenderStateShard.DepthTestStateShard("lequal", GL11.GL_LEQUAL))
                        .setWriteMaskState(new RenderStateShard.WriteMaskStateShard(true, false))
                        .setCullState(new RenderStateShard.CullStateShard(false)).createCompositeState(false));
        var buffers = MultiBufferSource.immediate(Tesselator.getInstance().getBuilder());
        var vertices = buffers.getBuffer(layer);
        drawPatches(visibility == FrustumIntersection.INSIDE ? null : frustum, opacity,
                (x,y,z,alpha) -> vertices.vertex(matrix,x,y,z).color(0F,0F,0F,alpha).endVertex());
        buffers.endBatch(layer);
    }
}
