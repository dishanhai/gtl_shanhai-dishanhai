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
import org.lwjgl.opengl.GL11;

/** 山海：只包圍黑洞近旁的深空幕；隨視界半徑縮放，不延伸至歸墟內環及站體。 */
public final class GuixuDeepSpaceBackdrop {
    private GuixuDeepSpaceBackdrop() {}
    private static RenderType layer;
    public interface Sink { void vertex(float x, float y, float z, float alpha); }

    public static void emit(float[] camera, double horizon, Sink sink) {
        float opacity = opacity(camera, horizon);
        if (opacity <= 0) return;
        double extent = GuixuStarField.environmentRadius(horizon);
        double length = Math.sqrt(camera[0]*camera[0] + camera[1]*camera[1] + camera[2]*camera[2]);
        double vx = length > 1e-5 ? camera[0]/length : 0;
        double vy = length > 1e-5 ? camera[1]/length : 0;
        double vz = length > 1e-5 ? camera[2]/length : 1;
        double rightLength = Math.hypot(vx, vz);
        double rx = rightLength > 1e-5 ? vz/rightLength : 1, rz = rightLength > 1e-5 ? -vx/rightLength : 0;
        double ux = vy*rz, uy = vz*rx-vx*rz, uz = -vy*rx;
        for (int ring = 0; ring < 12; ring++) for (int segment = 0; segment < 64; segment++) {
            double a = segment * Math.PI / 32, b = (segment + 1) * Math.PI / 32;
            vertex(sink, ring/12D, a, vx, vy, vz, rx, rz, ux, uy, uz, extent, opacity);
            vertex(sink, (ring+1)/12D, a, vx, vy, vz, rx, rz, ux, uy, uz, extent, opacity);
            vertex(sink, (ring+1)/12D, b, vx, vy, vz, rx, rz, ux, uy, uz, extent, opacity);
            vertex(sink, ring/12D, b, vx, vy, vz, rx, rz, ux, uy, uz, extent, opacity);
        }
    }

    private static void vertex(Sink sink, double radius, double angle, double vx, double vy, double vz,
                               double rx, double rz, double ux, double uy, double uz, double extent, float opacity) {
        double x = extent * .96 * radius * Math.cos(angle), y = extent * .96 * radius * Math.sin(angle);
        double fade = Math.max(0, Math.min(1, (radius - .62) / .38));
        float alpha = (float)(.997 * (1 - fade*fade*(3-2*fade))) * opacity;
        double depth = extent * .25;
        sink.vertex((float)(-depth*vx + rx*x + ux*y), (float)(-depth*vy + uy*y),
                (float)(-depth*vz + rz*x + uz*y), alpha);
    }

    /** 與洞內透鏡共用進入判據；提前淡出，避免透鏡把黑幕當成世界背景取樣。 */
    private static float opacity(float[] camera, double horizon) {
        double distance = Math.sqrt(camera[0]*(double)camera[0] + camera[1]*(double)camera[1]
                + camera[2]*(double)camera[2]);
        double threshold = BlackHoleLensSpec.insideThresholdBlocks(horizon / BlackHoleLensSpec.B_CRIT);
        double fade = Math.max(0, Math.min(1, (distance - threshold) / Math.max(2, horizon * .5)));
        return (float)(fade * fade * (3 - 2 * fade));
    }

    public static void render(PoseStack pose, float[] camera, double horizon) {
        if (opacity(camera, horizon) <= 0) return;
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
        var matrix = pose.last().pose();
        emit(camera, horizon, (x,y,z,alpha) -> vertices.vertex(matrix,x,y,z).color(0F,0F,0F,alpha).endVertex());
        buffers.endBatch(layer);
    }
}
