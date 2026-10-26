package com.shanhai.client.render.hostfx;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;

import com.shanhai.ShanhaiMod;
import com.shanhai.client.render.blackhole.BlackHoleFallbackGuard;
import com.shanhai.client.render.blackhole.BlackHoleFrameStats;
import com.shanhai.client.render.blackhole.BlackHoleLensMath;
import com.shanhai.client.render.blackhole.BlackHoleLensRenderer;
import com.shanhai.client.render.blackhole.BlackHoleTuning;

import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import java.util.List;
import java.util.Locale;

/**
 * 归墟视界枢核 · <b>黑洞的安全兜底画面</b>（纯顶点几何，<b>零贴图、零裸 GL</b>）。
 *
 * <h2>为什么需要它（用户原话：「我的黑洞呢？」）</h2>
 * 真正的黑洞由 {@code client/render/blackhole} 那套画（屏幕空间引力透镜 + 吸积盘）。
 * 但那一套在 <b>Iris/Oculus 光影包生效</b>时主动降级，且 2026-10-24 为绕开
 * {@code nvoglv64.dll} 驱动崩溃，<b>二级（程序化贴图 quad）整条停用</b>
 * ⇒ {@code drawFallbackQuad} 恒返回 false ⇒ <b>一级二级都不画 ⇒ 画面里没有黑洞</b>。
 * 客户端日志逐帧实录（2026-10-24 12:42:59 起，约每 33 ms 一行）：
 * <pre>
 *   [SHANHAI-BH] fallback=2 不再绘制（2026-10-24 驱动崩溃修复：本路径已无任何裸 GL 调用）
 *                screen=2880x1659 shadowR=48.87 shaderpack=true
 * </pre>
 * 本类就是"光影下也要画点东西"的<b>安全版</b>：只用 {@code position_color} 的三角形，
 * <b>不建纹理、不上传、不碰 direct buffer</b>（驱动崩溃那三条路径一条都不走）。
 *
 * <h2>画什么（示意图，不是物理解算）</h2>
 * 几何全部在 {@link HostFxGeometry}（纯数学，离线预览共用同一份）：
 * <ol>
 *   <li>{@link HostFxGeometry#holeDiscQuads}：正对相机的<b>黑盘</b>，半径 = 黑洞表面半径，近纯黑、<b>写深度</b>；</li>
 *   <li>{@link HostFxGeometry#holeHaloQuads}：<b>光子环</b>（1.00→1.22 倍）＋ <b>引力光晕</b>（1.24→2.10 倍），加性；</li>
 *   <li>{@link HostFxGeometry#holeDiskQuads}：<b>吸积盘</b>（环平面上的加性环带，缓慢自转）。</li>
 * </ol>
 *
 * <h2>开关语义（绝不做"双黑洞"）</h2>
 * {@link #active()} 只在<b>真黑洞这一帧没画出来</b>时返回 true：
 * <ul>
 *   <li>{@link BlackHoleTuning#enabled()} = false（用户关了黑洞）⇒ 本类也不画；</li>
 *   <li>{@link BlackHoleFrameStats#lastFrameNanos()} = 0（blackhole 包还没跑过一帧）⇒ 不画，
 *       避免"同一帧两个黑洞"；</li>
 *   <li>{@link BlackHoleFrameStats#lastDrewAny()} = true（真路径画了）⇒ 不画。</li>
 * </ul>
 * 也就是：光影关着、透镜正常时，画面里只有<b>那一个</b>真黑洞；
 * 光影开着（或透镜降级）时由本类顶上。<b>两条路互斥。</b>
 *
 * <h2>⚠️ 只在渲染线程用</h2>
 * 与 {@link HostFxDraw} 相同：{@code Tesselator} + {@code MultiBufferSource.immediate}。
 */
public final class HostFxBlackHole {

    private HostFxBlackHole() {}

    /** 上一次渲染的状态（日志用）：{@code on(RS=…)/off(原因)}。 */
    private static String lastState = "off(还没调过)";
    /** 上一次提交的四边形数。 */
    private static int lastQuads;

    /** 日志用状态串。 */
    public static String lastState() {
        return lastState;
    }

    /** 上一次提交的四边形数。 */
    public static int quads() {
        return lastQuads;
    }

    /**
     * 这一帧该不该由本类顶上去画。
     *
     * <p>🔴 2026-10-25（父代理约束②）：<b>收紧成"仅 {@code level == LEVEL_NONE} 时兜底"</b> ——
     * blackhole 包的一级（透镜）与二级（贴图公告板）现在都能出画面，所以只有它自己判死
     * （{@link BlackHoleFallbackGuard#LEVEL_NONE}）时才轮到我们。另外三道保险照旧：
     * <ul>
     *   <li>{@link BlackHoleTuning#enabled()} = false（用户关了黑洞）⇒ 不画；</li>
     *   <li>{@link BlackHoleFrameStats#lastFrameNanos()} = 0（blackhole 包还没跑过一帧）⇒ 不画
     *       （避免"同一帧两个黑洞"）；</li>
     *   <li>{@link BlackHoleFrameStats#lastDrewAny()} = true（真路径这一帧画了）⇒ 不画。</li>
     * </ul>
     * 结论每 {@value #OWNER_LOG_MS} ms 打一行 {@code [SHANHAI-BH] owner=hostfx|lens}（约束②要求）。
     */
    public static boolean active() {
        if (!BlackHoleTuning.enabled()) {
            lastState = "off(黑洞总开关=false)";
            logOwner(false, "blackhole_enabled=false");
            return false;
        }
        if (BlackHoleFrameStats.lastFrameNanos() == 0L) {
            lastState = "off(blackhole 包还没跑过一帧)";
            logOwner(false, "bh_no_frame_yet");
            return false;
        }
        final int level = BlackHoleLensRenderer.levelNow();
        if (level != BlackHoleFallbackGuard.LEVEL_NONE) {
            lastState = "off(bh level=" + level + "≠" + BlackHoleFallbackGuard.LEVEL_NONE + ")";
            logOwner(false, "level=" + level);
            return false;
        }
        if (BlackHoleFrameStats.lastDrewAny()) {
            lastState = "off(真路径这一帧画了: " + BlackHoleFrameStats.lastSkip() + ")";
            logOwner(false, "last_drew=" + BlackHoleFrameStats.lastSkip());
            return false;
        }
        lastState = "on(level=NONE ⇒ hostfx 顶上)";
        logOwner(true, "level=NONE");
        return true;
    }

    /** {@code owner=} 日志节流（毫秒）：父代理要求 5 秒一行。 */
    public static final long OWNER_LOG_MS = 5_000L;
    private static long lastOwnerLogMs;

    /**
     * 这一帧黑洞到底由谁画（5 s 节流）。
     *
     * <h2>🔴 2026-10-24 傍晚：这行以前会骗人，现在改成按"真的画了什么"报</h2>
     * 改前它报的是 {@code owner=lens level=1 why=level=1} —— 那三个字段的意思其实是
     * "<b>hostfx 没有接管，因为 guard 的降级级别还不是 NONE</b>"，
     * <b>完全不等于"透镜画了"</b>。于是日志里出现自相矛盾：
     * <pre>
     *   [SHANHAI-BH] fallback_quad_ok …            ← 实际画的是二级贴图
     *   [SHANHAI-BH] owner=lens level=1 why=level=1 ← 同几秒内却报"lens"
     * </pre>
     * 现在这一行读的是 {@link BlackHoleLensRenderer#lastPath()}（<b>这一帧真的走了哪条路</b>）：
     * <ul>
     *   <li>{@code owner=lens} ⇒ 一级真透镜（{@code path=lens-after-composite|after-weather}）；</li>
     *   <li>{@code owner=fallback} ⇒ 二级程序化贴图（{@code path=fallback-quad}）；</li>
     *   <li>{@code owner=hostfx} ⇒ 本类兜底（{@code active()==true}）；</li>
     *   <li>{@code owner=none} ⇒ 这一帧什么都没画（{@code path=none}）。</li>
     * </ul>
     * {@code level=} 仍然打（它是 guard 的<b>降级级别</b>，不是"画了哪条路"），但前面多一个
     * {@code path=} 就不会再被误读。{@code why=} 保留原义（本类为什么没接管）。
     */
    private static void logOwner(boolean hostfx, String why) {
        final long now = System.currentTimeMillis();
        if (now - lastOwnerLogMs < OWNER_LOG_MS) {
            return;
        }
        lastOwnerLogMs = now;
        final String path = BlackHoleLensRenderer.lastPath();
        final String owner;
        if (hostfx) {
            owner = "hostfx";
        } else if (BlackHoleLensRenderer.PATH_FALLBACK_QUAD.equals(path)) {
            owner = "fallback";
        } else if (BlackHoleLensRenderer.PATH_NONE.equals(path)) {
            owner = "none";
        } else {
            owner = "lens";
        }
        ShanhaiMod.LOGGER.info("[SHANHAI-BH] owner={} path={} level={} why={}",
                owner, path, BlackHoleLensRenderer.levelNow(), why);
    }

    /**
     * 黑洞表面半径（格）：<b>主机接管优先</b>，否则用 blackhole 包这一帧真正用的视界半径 × B_CRIT。
     *
     * <h2>🔴 2026-10-25 第二十改</h2>
     * 归墟视界枢核的机器字段 {@code hostBlackHoleRadiusBlocks} 是<b>可见半径（格）</b>，
     * 由 {@code GuixuHostFxRenderer} 每帧喂进 {@link BlackHoleTuning#setHostRadiusBlocks(float)}。
     * 有接管时这里<b>直接用那个数</b>（再夹一次 0.8..12.0：下限是本类几何的退化保护，
     * 上限与 {@code HostFxState.HOLE_MAX_BLOCKS} 同值 —— 否则"面板能填、画面不变"就是假控件）。
     *
     * <p>为什么不能只靠 {@link BlackHoleFrameStats#lastRsBlocks()}：那份读数来自"真黑洞这一帧画过"
     * 的那条路；本类顶上的场合恰恰是**真路径一帧都没画**（{@code LEVEL_NONE}）⇒ 那份读数可能是
     * 很久以前的（甚至是"上次别的位置那个黑洞"的）⇒ 兜底黑盘会跟着一个过时半径。
     */
    public static float surfaceRadius() {
        if (BlackHoleTuning.hasHostRadiusBlocks()) {
            final float host = BlackHoleTuning.hostRadiusBlocks();
            return Math.max(0.8F, Math.min(host, 12.0F));
        }
        final double rs = BlackHoleFrameStats.lastRsBlocks();
        if (!(rs > 1.0e-3D)) {
            return HostFxGeometry.HOLE_SURFACE_R;
        }
        final float r = (float) (rs * BlackHoleLensMath.B_CRIT);
        return Math.max(0.8F, Math.min(r, 12.0F));
    }

    /**
     * 画兜底黑洞（<b>环心本地坐标</b>；调用方负责把 pose 摆到"环心格中心"）。
     *
     * @param camLocalX/Y/Z 相机在环心本地帧里的位置（{@link HostFxGeometry#cameraInRingLocal}）
     * @param seconds       世界时间（秒），只用来让吸积盘缓慢自转
     * @param surfaceR      黑洞表面半径（格）= {@link #surfaceRadius()}
     * @return 提交的四边形数（0 = 这一帧不该画）
     */
    public static int render(PoseStack poseStack, float camLocalX, float camLocalY, float camLocalZ,
                             float seconds, float surfaceR) {
        lastQuads = 0;
        if (!active()) {
            return 0;
        }
        final float r = Math.max(0.8F, surfaceR);
        // ① 黑盘（不透明、写深度）
        int quads = submit(poseStack, opaqueLayer(),
                HostFxGeometry.holeDiscQuads(camLocalX, camLocalY, camLocalZ, r));
        // ② 光子环 + 光晕 + ③ 吸积盘（加性、不写深度）
        final RenderType add = additiveLayer();
        quads += submit(poseStack, add, HostFxGeometry.holeHaloQuads(camLocalX, camLocalY, camLocalZ, r));
        quads += submit(poseStack, add, HostFxGeometry.holeDiskQuads(r, seconds));
        lastQuads = quads;
        lastState = "on(RS=" + String.format(Locale.ROOT, "%.2f", r) + " 黑盘+光子环+光晕+吸积盘 "
                + quads + " quads)";
        return quads;
    }

    /** 提交一批四边形（每批末尾一次 {@code endBatch()}）。 */
    private static int submit(PoseStack poseStack, RenderType layer, List<HostFxGeometry.Quad> quads) {
        if (quads.isEmpty()) {
            return 0;
        }
        final Matrix4f m = poseStack.last().pose();
        final MultiBufferSource.BufferSource buffers =
                MultiBufferSource.immediate(Tesselator.getInstance().getBuilder());
        final VertexConsumer vc = buffers.getBuffer(layer);
        for (HostFxGeometry.Quad q : quads) {
            final float[] p = q.xyz();
            final float red = clamp(q.r());
            final float green = clamp(q.g());
            final float blue = clamp(q.b());
            final float alpha = clamp(q.a());
            vc.vertex(m, p[0], p[1], p[2]).color(red, green, blue, alpha).endVertex();
            vc.vertex(m, p[3], p[4], p[5]).color(red, green, blue, alpha).endVertex();
            vc.vertex(m, p[6], p[7], p[8]).color(red, green, blue, alpha).endVertex();
            vc.vertex(m, p[9], p[10], p[11]).color(red, green, blue, alpha).endVertex();
        }
        buffers.endBatch(layer);                 // 🔴 每批末尾一次
        return quads.size();
    }

    private static float clamp(float v) {
        return v < 0.0F ? 0.0F : (v > 1.0F ? 1.0F : v);
    }

    /** 相机在环心本地帧里的位置（给调用方用；与光束同一份换算）。 */
    public static float[] cameraLocal(Direction facing, double camWorldX, double camWorldY,
                                      double camWorldZ, BlockPos controllerPos) {
        return HostFxGeometry.cameraInRingLocal(facing,
                camWorldX - controllerPos.getX(),
                camWorldY - controllerPos.getY(),
                camWorldZ - controllerPos.getZ());
    }

    // ==========================================================================================
    //  RenderType（都是 position_color：不采样任何贴图）
    // ==========================================================================================

    private static RenderType opaqueLayer;
    private static RenderType additiveLayer;

    private static RenderStateShard.ShaderStateShard shader() {
        return new RenderStateShard.ShaderStateShard(GameRenderer::getPositionColorShader);
    }

    /** 黑盘：不透明、写深度。 */
    private static RenderType opaqueLayer() {
        if (opaqueLayer == null) {
            opaqueLayer = RenderType.create(
                    "shanhai_hostfx_hole_opaque",
                    DefaultVertexFormat.POSITION_COLOR,
                    VertexFormat.Mode.QUADS,
                    4096,
                    false, false,
                    RenderType.CompositeState.builder()
                            .setShaderState(shader())
                            .setTransparencyState(noTransparency())
                            .setDepthTestState(new RenderStateShard.DepthTestStateShard("lequal", GL11.GL_LEQUAL))
                            .setWriteMaskState(new RenderStateShard.WriteMaskStateShard(true, true))
                            .setCullState(new RenderStateShard.CullStateShard(false))
                            .createCompositeState(false));
        }
        return opaqueLayer;
    }

    /**
     * "不混合"。
     *
     * <p>⚠️ 原版 {@code RenderStateShard.NO_TRANSPARENCY} 是 {@code protected}，外部取不到
     * （2026-10-25 编译报错原文：{@code NO_TRANSPARENCY 在 RenderStateShard 中是 protected 访问控制}）⇒ 自带一个等价的。
     */
    private static RenderStateShard.TransparencyStateShard noTransparency() {
        return new RenderStateShard.TransparencyStateShard("shanhai_hostfx_hole_opaque",
                RenderSystem::disableBlend, RenderSystem::disableBlend);
    }

    /** 光晕/光子环/吸积盘：加性、不写深度。 */
    private static RenderType additiveLayer() {
        if (additiveLayer == null) {
            additiveLayer = RenderType.create(
                    "shanhai_hostfx_hole_add",
                    DefaultVertexFormat.POSITION_COLOR,
                    VertexFormat.Mode.QUADS,
                    4096,
                    false, false,
                    RenderType.CompositeState.builder()
                            .setShaderState(shader())
                            .setTransparencyState(new RenderStateShard.TransparencyStateShard(
                                    "shanhai_hostfx_hole_additive",
                                    () -> {
                                        RenderSystem.enableBlend();
                                        RenderSystem.blendFunc(
                                                com.mojang.blaze3d.platform.GlStateManager.SourceFactor.SRC_ALPHA,
                                                com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE);
                                    },
                                    () -> {
                                        RenderSystem.disableBlend();
                                        RenderSystem.defaultBlendFunc();
                                    }))
                            .setDepthTestState(new RenderStateShard.DepthTestStateShard("lequal", GL11.GL_LEQUAL))
                            .setWriteMaskState(new RenderStateShard.WriteMaskStateShard(true, false))
                            .setCullState(new RenderStateShard.CullStateShard(false))
                            .createCompositeState(false));
        }
        return additiveLayer;
    }
}
