package com.shanhai.client.render.hostfx;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;

import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.Direction;

import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

/**
 * 归墟视界枢核 · 玻璃 AR 面板的<b>几何提交</b>（把 {@link HostFxGlassArModel} 的四边形变成顶点）。
 *
 * <h2>只能用两种绘制手段（用户硬要求 ①）</h2>
 * <ol>
 *   <li>文字：{@code Font.drawInBatch}（在 {@link HostFxGlassArText} 里）；</li>
 *   <li>几何：<b>{@code POSITION_COLOR} 无贴图 {@code RenderType}</b> —— 就是本类。先例是
 *       {@code HostFxDraw.beamLayer()}（同样是 {@code DefaultVertexFormat.POSITION_COLOR} +
 *       {@code VertexFormat.Mode.QUADS} + 不采样任何贴图）。<b>没有</b>第二套画法。</li>
 * </ol>
 *
 * <h2>两条层（只多一条，原因是"黑"画不出来）</h2>
 * <ul>
 *   <li>{@link #addLayer()}：<b>加性</b>（{@code SRC_ALPHA, ONE}）—— 发光件：环带边、12 条细紫束、
 *       4 条主柱、中央两束、光子环、吸积盘、勾叉。<b>加性画不出黑色</b>（黑色加任何东西还是原色），
 *       所以黑洞阴影不走这条；</li>
 *   <li>{@link #alphaLayer()}：<b>普通 alpha</b>（{@code SRC_ALPHA, ONE_MINUS_SRC_ALPHA}）—— 需要"压暗"的：
 *       黑洞阴影（纯黑）、解压机塔体、环带截面、飞船描边。</li>
 * </ul>
 * 两条层都是：不写深度（纯覆盖）、不剔除（双面都画）、深度测试 LEQUAL、无贴图。
 *
 * <h2>为什么几何要单独一层平面（比文字再让 0.012 格）</h2>
 * 见 {@link HostFxGlassArPanel#GEOM_OFFSET}：两条层都写颜色，贴同一平面会 z-fighting 逐像素闪。
 *
 * <h2>坐标</h2>
 * 顶点坐标是<b>墙面帧的 MC 像素</b>（1 px = 0.02 格），矩阵把 px 直接映射到本地世界：
 * <pre>
 *   pivot(环心) → translate(几何面 x, WALL_Y1, WALL_Z1) → rotY(+90°) → scale(PIXEL, −PIXEL, −PIXEL)
 * </pre>
 * 于是 {@code (u_px, v_px)} → 本地 {@code (面 x, WALL_Y1 − v_px·PIXEL, WALL_Z1 − u_px·PIXEL)}，
 * 与文字那一套<b>逐位同源</b>（同一段矩阵代码、同一个 PIXEL）。
 */
public final class HostFxGlassArDraw {

    private HostFxGlassArDraw() {}

    /** 顶点色直接当片元色的原版着色器（不采样贴图）。 */
    private static final RenderStateShard.ShaderStateShard SHADER =
            new RenderStateShard.ShaderStateShard(GameRenderer::getPositionColorShader);

    /** 加性混合（与 {@code HostFxDraw.ADDITIVE} 同一对参数：{@code src×alpha + dst×1}）。 */
    private static final RenderStateShard.TransparencyStateShard ADDITIVE =
            new RenderStateShard.TransparencyStateShard("shanhai_glass_ar_additive",
                    () -> {
                        RenderSystem.enableBlend();
                        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA,
                                GlStateManager.DestFactor.ONE);
                    },
                    () -> {
                        RenderSystem.disableBlend();
                        RenderSystem.defaultBlendFunc();
                    });

    /** 普通 alpha 混合（能给"变暗"）。 */
    private static final RenderStateShard.TransparencyStateShard ALPHA =
            new RenderStateShard.TransparencyStateShard("shanhai_glass_ar_alpha",
                    () -> {
                        RenderSystem.enableBlend();
                        RenderSystem.defaultBlendFunc();
                    },
                    () -> {
                        RenderSystem.disableBlend();
                        RenderSystem.defaultBlendFunc();
                    });

    private static RenderType addLayer;
    private static RenderType alphaLayer;

    /** 上一帧提交的四边形数（日志/判据）。 */
    private static int lastQuads;
    /** 上一帧 {@code endBatch()} 的次数（正常 1~2；0 = 什么都没提交）。 */
    private static int lastBatches;

    public static int quads() {
        return lastQuads;
    }

    public static int batches() {
        return lastBatches;
    }

    private static RenderType make(String name, RenderStateShard.TransparencyStateShard transparency) {
        return RenderType.create(
                name,
                DefaultVertexFormat.POSITION_COLOR,
                VertexFormat.Mode.QUADS,
                8192,
                false,                                        // affectsCrumbling
                false,                                        // sortOnUpload（加性/平面叠加不需要排序）
                RenderType.CompositeState.builder()
                        .setShaderState(SHADER)
                        .setTransparencyState(transparency)
                        .setDepthTestState(new RenderStateShard.DepthTestStateShard("lequal", GL11.GL_LEQUAL))
                        // 只写颜色、不写深度：这是"覆盖层"，不许把后面的玻璃/字挡掉
                        .setWriteMaskState(new RenderStateShard.WriteMaskStateShard(true, false))
                        // 双面都要（从房间外看是同一片几何的镜像）
                        .setCullState(new RenderStateShard.CullStateShard(false))
                        .createCompositeState(false));
    }

    /** 加性层（发光件）。 */
    public static RenderType addLayer() {
        if (addLayer == null) {
            addLayer = make("shanhai_glass_ar_add", ADDITIVE);
        }
        return addLayer;
    }

    /** alpha 层（阴影 / 实体块）。 */
    public static RenderType alphaLayer() {
        if (alphaLayer == null) {
            alphaLayer = make("shanhai_glass_ar_alpha", ALPHA);
        }
        return alphaLayer;
    }

    /**
     * 画一块面板的全部几何（<b>调用方已保证成型闸门通过</b>）。
     *
     * @return 提交的四边形数（0 = 没画）
     */
    public static int render(PoseStack poseStack, float cx, float cy, float cz, Direction facing,
                             HostFxGlassArModel.Model model) {
        lastQuads = 0;
        lastBatches = 0;
        if (poseStack == null || model == null || model.prims.isEmpty()) {
            return 0;
        }
        poseStack.pushPose();
        try {
            HostFxRingTransforms.pivot(poseStack, cx, cy, cz, facing);
            // 墙面帧原点 = 房间里的左上角 ⇒ 本地 (y = WALL_Y1, z = WALL_Z1)
            poseStack.translate(HostFxGlassArPanel.geomPlaneX(),
                    HostFxGlassArPanel.WALL_Y1, HostFxGlassArPanel.WALL_Z1);
            poseStack.mulPose(Axis.YP.rotationDegrees(90.0F));
            final float s = HostFxGlassArPanel.PIXEL;
            poseStack.scale(s, -s, -s);
            final Matrix4f m = poseStack.last().pose();
            // ① alpha 层先画（阴影/实体要吃在发光件底下）
            lastQuads += emitLayer(alphaLayer(), m, model, HostFxGlassArModel.BLEND_ALPHA);
            // ② 加性层后画（发光件叠在上面）
            lastQuads += emitLayer(addLayer(), m, model, HostFxGlassArModel.BLEND_ADD);
        } finally {
            poseStack.popPose();
        }
        return lastQuads;
    }

    /**
     * 把 blend 匹配的那批四边形写进一条层。
     *
     * <p>🔴 每条层<b>自己开一个</b> {@code MultiBufferSource.immediate} 并<b>当场</b> {@code endBatch(layer)} ——
     * 与 {@code HostFxDraw} 的先例逐字相同（那里也是"一段一个 source、结尾一次 endBatch"）。
     * 不复用同一个 source 画两条层：{@code getBuffer} 会按层的顶点格式重新 {@code begin}，
     * 前一批没 end 掉的顶点会被吞掉（"画了但没出现"这类静默失败在本工程是红线）。
     */
    private static int emitLayer(RenderType layer, Matrix4f m, HostFxGlassArModel.Model model, int blend) {
        int n = 0;
        for (HostFxGlassArModel.Prim p : model.prims) {
            if (p.blend == blend) {
                n++;
            }
        }
        if (n == 0) {
            return 0;
        }
        final MultiBufferSource.BufferSource buffers =
                MultiBufferSource.immediate(Tesselator.getInstance().getBuilder());
        try {
            final VertexConsumer vc = buffers.getBuffer(layer);
            for (HostFxGlassArModel.Prim p : model.prims) {
                if (p.blend != blend) {
                    continue;
                }
                final float r = p.r() / 255.0F;
                final float g = p.g() / 255.0F;
                final float b = p.b() / 255.0F;
                final float a = p.a() / 255.0F;
                final float[] xy = p.xy;
                vc.vertex(m, xy[0], xy[1], 0.0F).color(r, g, b, a).endVertex();
                vc.vertex(m, xy[2], xy[3], 0.0F).color(r, g, b, a).endVertex();
                vc.vertex(m, xy[4], xy[5], 0.0F).color(r, g, b, a).endVertex();
                vc.vertex(m, xy[6], xy[7], 0.0F).color(r, g, b, a).endVertex();
            }
        } finally {
            buffers.endBatch(layer);
            RenderSystem.defaultBlendFunc();
            lastBatches++;
        }
        return n;
    }
}
