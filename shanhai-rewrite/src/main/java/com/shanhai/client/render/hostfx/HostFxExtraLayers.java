package com.shanhai.client.render.hostfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.shanhai.client.config.ShanhaiClientConfig;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import org.joml.Matrix4f;

import java.util.List;

/**
 * 归墟视界枢核 · <b>四件移植里"世界空间那三层"的提交处</b>（2026-11-22 新增）。
 *
 * <h2>它画什么、挂在哪儿</h2>
 * 由 {@code GuixuHostFxRenderer#render}（TESR）在<b>既有全部绘制之后</b>调一次
 * ⇒ <b>不动任何已验收图层的绘制顺序</b>，只是在这条渲染链路末尾<b>追加</b>三层加性几何：
 * <ol>
 *   <li>① <b>外缘吸积流</b>（{@link GuixuAccretionFlow}）—— 环心本地帧，环面 51..57 格、上下 ±0.45；
 *       3840 quads，逐顶点色（{@link HostFxExtraGeom#diskVertices}）；</li>
 *   <li>③ <b>星辰场</b>（{@link GuixuStarField}）—— 六组采样 → 公告板，≤1256 quads；
 *       开关 = {@code [hostfx] starField}（默认开）；</li>
 *   <li>④ <b>结构闪电</b>（{@link GuixuAccretionLightning} + {@link HostFxStructureLightning}）——
 *       ≤12 条弧 × 12 quads；开关 = {@code [hostfx] structureLightning}（默认开）。</li>
 * </ol>
 * ② <b>黑洞白边波浪</b>不在这里画 —— 它必须画在透镜<b>之后</b>（AFTER_LEVEL），见 {@link HostFxHoleRimWaves};
 * 这里只负责每帧把"洞心 / 半径 / 每像素盘半径"喂给它。
 *
 * <h2>🔴 复用既有加性层，不新建 RenderType</h2>
 * 三层全部提交到 {@link HostFxDraw#beamLayer()}（{@code POSITION_COLOR} + {@code SRC_ALPHA/ONE} 加性 +
 * 关深度写 + 不剔除 + 无贴图）—— 与既有的 12 条细紫光束、光柱环回落<b>同一条</b>层：
 * <ul>
 *   <li>不新增 GL 状态、不新增贴图（红线：<b>不许裸 GL 建纹理</b>）；</li>
 *   <li>加性混合与顺序无关 ⇒ 追加在末尾不会改变既有图层之间的相对观感；</li>
 *   <li>每批末尾一次 {@code endBatch()}（本类的批次数也是"到底画出去了没有"的判据）。</li>
 * </ul>
 *
 * <h2>性能护栏（读数是本类 {@link #stats()} 那一行）</h2>
 * <ul>
 *   <li>①固定 3840 quads（{@code DISK_ANGULAR=160 × DISK_RINGS=24}）；</li>
 *   <li>③上限 1256 quads；<b>距离降级</b>：相机离洞心 &gt; {@link #STAR_DEGRADE_DISTANCE} 格 ⇒
 *       {@code degraded=true}（他的 {@code skyCount}=96 / {@code volumeCount}=64 / {@code dustCount}=0）；
 *       再远到 &gt; {@link #STAR_SKIP_DISTANCE} 格 ⇒ 整组不画；</li>
 *   <li>④上限 12 条弧（他的 {@code MAX_ARCS}）；</li>
 *   <li>三层都是"每一帧按世界时间纯函数采样" ⇒ 没有粒子实体、没有跨世界状态、暂停就冻结。</li>
 * </ul>
 */
public final class HostFxExtraLayers {

    private HostFxExtraLayers() {
    }

    /** 星辰场距离降级阈值（格，相机到洞心）。 */
    public static final float STAR_DEGRADE_DISTANCE = 160.0F;
    /** 星辰场整组跳过的距离阈值（格）。 */
    public static final float STAR_SKIP_DISTANCE = 320.0F;
    /** 星辰密度（0..1）：1.0 = 用满他的常量（背景星 512 / 体积星 384 / 星尘 96）。 */
    public static final float STAR_DENSITY = 1.0F;

    private static int lastDiskQuads;
    /**
     * 上一帧外缘吸积流的状态（{@code "完整"} / {@code "关(环显示)"} / {@code "关(特效总开关)"}）。
     *
     * <p>为什么要有它：跳段时 {@link #lastDiskQuads} 就是 0，而"0/3840"本身分不清
     * "被环显示关掉"和"被特效总开关关掉" ⇒ 读数里带上原因，日志不撒谎。
     */
    private static String lastDiskMode = "未画";
    private static int lastStarQuads;
    private static int lastLightningQuads;
    private static int lastLightningArcs;
    /**
     * 这一帧最亮那条电弧的包络（0..1）。
     *
     * <p>为什么必须报它：{@code arcs=12} 只说明"提交了 12 条"，而这 12 条可能全是刚出生/快死的
     * （包络接近 0）⇒ 看上去"什么都没有"。{@code peakAlpha} 才是"这一帧到底亮不亮"的读数。
     */
    private static float lastLightningPeak;
    /**
     * 上一帧结构闪电<b>为什么是 0 条</b>（{@code "(关:面板)"} / {@code "(关:配置)"} / {@code ""} = 真的画了）。
     *
     * <p>跳段时 {@link #lastLightningArcs} 就是 0，光看数字分不清"玩家关的"和"配置关的" ⇒ 读数带原因。
     */
    private static String lastLightningMode = "";
    private static String lastStarMode = "未画";
    private static int lastBatches;

    /**
     * 追加三层（<b>调用方 = {@code GuixuHostFxRenderer#render}，在所有既有绘制之后</b>）。
     *
     * @param poseStack   TESR 的 PoseStack（原点 = 控制器格最小角）
     * @param level       客户端世界（④ 要拿它核对真实方块）
     * @param controller  控制器方块坐标
     * @param facing      机器朝向
     * @param cx,cy,cz    环心相对控制器的坐标（与既有三层共用同一个枢轴，见 {@code HostFxRingTransforms.pivot}）
     * @param seconds     世界时间（秒）—— ①②③④ 的相位
     * @param spinSeconds 自转时钟（{@link HostFxSwitch#spinSeconds()}）—— 只有 ④ 的"被藏格子"锚点用它
     * @param cam         相机世界坐标
     * @param holeR       黑洞可见半径（格）= {@link HostFxBlackHole#surfaceRadius()}
     */
    public static void render(PoseStack poseStack, ClientLevel level, BlockPos controller, Direction facing,
                              float cx, float cy, float cz, float seconds, float spinSeconds,
                              Vec3 cam, float holeR) {
        lastDiskQuads = 0;
        lastStarQuads = 0;
        lastLightningQuads = 0;
        lastLightningArcs = 0;
        lastLightningPeak = 0.0F;
        lastBatches = 0;
        lastStarMode = "未画";
        lastDiskMode = "未画";
        lastLightningMode = "";
        // 相机换算到"环心本地"帧（与 HostFxDraw.drawBeams 同一份换算）
        final float[] camLocal = HostFxGeometry.cameraInRingLocal(facing,
                cam.x - controller.getX(), cam.y - controller.getY(), cam.z - controller.getZ());
        final float camDistance = (float) Math.sqrt(camLocal[0] * camLocal[0]
                + camLocal[1] * camLocal[1] + camLocal[2] * camLocal[2]);

        // ── ① 外缘吸积流（他的 GuixuAccretionFlow）──────────────────────────────────────
        // 不套 spin：那是自由流动的气体，自带开普勒差速（他的 angularSpeed），与环的自转无关。
        //
        // 🔴 2026-11-21：「环显示」三态 + 特效总开关，收口在 HostFxSwitch.accretionOn()。
        //    · mode=2（只画粗光柱环）或降级态 ⇒【整段跳过】：连顶点都不算
        //      （HostFxExtraGeom.diskVertices 一次都不跑），lastDiskQuads 保持 0，
        //      stats() 会如实写成「吸积流 0/3840(关…)」——绝不留上一帧的旧数。
        //    · 几何常量（51/57/±0.45）一个都没动：跳过的只是"这一帧提交不提交"。
        if (HostFxSwitch.accretionOn()) {
            final int diskFloats = HostFxExtraGeom.diskVertices(seconds,
                    camLocal[0], camLocal[1], camLocal[2]);
            poseStack.pushPose();
            try {
                HostFxRingTransforms.pivot(poseStack, cx, cy, cz, facing);
                lastDiskQuads = submitDisk(poseStack, diskFloats);
            } finally {
                poseStack.popPose();
            }
            lastDiskMode = "完整";
        } else {
            lastDiskQuads = 0;
            lastDiskMode = HostFxSwitch.degraded() ? "关(特效总开关)" : "关(环显示)";
        }

        // ── ③ 星辰场（他的 GuixuStarField）──────────────────────────────────────────────
        // 🔴 2026-11-22：开关 = 客户端 [hostfx] starField（**总闸**）× 机器面板「星辰场」。
        //    两者是「与」关系，口径只在 HostFxSwitch.starFieldOn(config) 一处；
        //    跳过时 lastStarQuads 必须归零（读数不许留上一帧的旧数）。
        if (HostFxSwitch.starFieldOn(ShanhaiClientConfig.hostFxStarField())) {
            if (camDistance > STAR_SKIP_DISTANCE) {
                lastStarMode = "跳过(距离 " + Math.round(camDistance) + " 格)";
            } else {
                final boolean degraded = camDistance > STAR_DEGRADE_DISTANCE;
                final List<HostFxGeometry.Quad> stars = HostFxExtraGeom.starQuads(seconds, holeR,
                        camLocal[0], camLocal[1], camLocal[2], STAR_DENSITY, degraded);
                poseStack.pushPose();
                try {
                    HostFxRingTransforms.pivot(poseStack, cx, cy, cz, facing);
                    lastStarQuads = submitQuads(poseStack, stars);
                } finally {
                    poseStack.popPose();
                }
                lastStarMode = degraded ? "降级" : "完整";
            }
        } else {
            lastStarQuads = 0;
            lastStarMode = ShanhaiClientConfig.hostFxStarField() ? "关:面板" : "关:配置";
        }

        // ── ④ 结构闪电（他的 GuixuAccretionLightning + 世界核对）──────────────────────────
        // 🔴 2026-11-22：开关 = 客户端 [hostfx] structureLightning（**总闸**）× 机器面板「结构闪电」。
        //    判定在 HostFxStructureLightning.frame() 里（**不在本文件再判一次** —— 收口只有一处）；
        //    它跳过时返回 Frame.EMPTY，这里照旧提交（0 个四边形 = 什么都不画），读数由 skipReason 说明原因。
        final GuixuAccretionLightning.Frame arcs =
                HostFxStructureLightning.frame(level, controller, facing, seconds, spinSeconds);
        lastLightningArcs = arcs.arcs();
        lastLightningPeak = arcs.peakAlpha();
        lastLightningMode = arcs.arcs() > 0 ? "" : "(" + HostFxStructureLightning.skipReason() + ")";
        poseStack.pushPose();
        try {
            HostFxRingTransforms.pivot(poseStack, cx, cy, cz, facing);
            lastLightningQuads = submitQuads(poseStack, arcs.quads());
        } finally {
            poseStack.popPose();
        }

        // ── ② 把"洞心 / 半径 / 每像素盘半径"喂给 AFTER_LEVEL 那一档（它负责在透镜之上画白边波浪）──
        final BlockPos center = HostFxGeometry.centerWorld(controller, facing);
        HostFxHoleRimWaves.capture(
                center.getX() + HostFxGeometry.CELL_CENTER,
                center.getY() + HostFxGeometry.CELL_CENTER,
                center.getZ() + HostFxGeometry.CELL_CENTER,
                holeR, seconds, cam);
    }

    /** 提交一组四边形（每批一次 {@code endBatch}）；返回提交的四边形数。 */
    private static int submitQuads(PoseStack poseStack, List<HostFxGeometry.Quad> quads) {
        if (quads == null || quads.isEmpty()) {
            return 0;
        }
        final RenderType layer = HostFxDraw.beamLayer();
        final MultiBufferSource.BufferSource buffers =
                MultiBufferSource.immediate(Tesselator.getInstance().getBuilder());
        final VertexConsumer vc = buffers.getBuffer(layer);
        final Matrix4f m = poseStack.last().pose();
        for (HostFxGeometry.Quad q : quads) {
            final float[] p = q.xyz();
            final float r = clamp(q.r());
            final float g = clamp(q.g());
            final float b = clamp(q.b());
            final float a = clamp(q.a());
            vc.vertex(m, p[0], p[1], p[2]).color(r, g, b, a).endVertex();
            vc.vertex(m, p[3], p[4], p[5]).color(r, g, b, a).endVertex();
            vc.vertex(m, p[6], p[7], p[8]).color(r, g, b, a).endVertex();
            vc.vertex(m, p[9], p[10], p[11]).color(r, g, b, a).endVertex();
        }
        buffers.endBatch(layer);
        lastBatches++;
        return quads.size();
    }

    /** 提交外缘吸积流（<b>逐顶点色</b>：{@link HostFxExtraGeom#diskVertices} 的复用缓冲，7 float/顶点）。 */
    private static int submitDisk(PoseStack poseStack, int floatCount) {
        if (floatCount <= 0) {
            return 0;
        }
        final RenderType layer = HostFxDraw.beamLayer();
        final MultiBufferSource.BufferSource buffers =
                MultiBufferSource.immediate(Tesselator.getInstance().getBuilder());
        final VertexConsumer vc = buffers.getBuffer(layer);
        final Matrix4f m = poseStack.last().pose();
        final float[] v = HostFxExtraGeom.diskBuffer();
        final int stride = HostFxExtraGeom.DISK_VERTEX_STRIDE;
        for (int i = 0; i + stride <= floatCount; i += stride) {
            vc.vertex(m, v[i], v[i + 1], v[i + 2])
                    .color(clamp(v[i + 3]), clamp(v[i + 4]), clamp(v[i + 5]), clamp(v[i + 6]))
                    .endVertex();
        }
        buffers.endBatch(layer);
        lastBatches++;
        return floatCount / (4 * stride);
    }

    private static float clamp(float value) {
        return value < 0.0F ? 0.0F : (value > 1.0F ? 1.0F : value);
    }

    /** 一层读数（进 {@code draw} 日志；"到底画了多少"的唯一判据）。 */
    public static String stats() {
        return "新增层 吸积流 " + lastDiskQuads + "/" + HostFxExtraGeom.DISK_QUAD_COUNT
                + "(" + lastDiskMode + ")"
                + " | 星辰 " + lastStarQuads + "/" + HostFxExtraGeom.STAR_MAX_QUADS
                + "(" + lastStarMode + ")"
                + " | 闪电 " + lastLightningArcs + "/" + GuixuAccretionLightning.MAX_ARCS
                + " 条" + lastLightningMode + "(存活 alive=" + lastLightningArcs + " peakAlpha="
                + String.format(java.util.Locale.ROOT, "%.2f", lastLightningPeak) + ")"
                + " " + lastLightningQuads + " quads"
                + " 核对[" + HostFxStructureLightning.auditLine() + "]"
                + " | 批 " + lastBatches
                + " | " + HostFxHoleRimWaves.stats();
    }
}
