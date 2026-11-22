package com.shanhai.client.render.hostfx;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.shanhai.ShanhaiMod;
import com.shanhai.client.config.ShanhaiClientConfig;
import com.shanhai.client.render.blackhole.BlackHoleTuning;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.util.List;

/**
 * 归墟视界枢核 · <b>黑洞边缘白边波浪</b>的提交处（作者 dishanhai 的公式，2026-11-22 移植）。
 *
 * <h2>为什么这一层<b>必须</b>在 {@code AFTER_LEVEL}（不是 TESR、也不是别的档）</h2>
 * 取证（本地反编译源码，不是推断）：
 * <pre>
 *   GameRenderer.renderLevel 第 1126 行
 *       this.minecraft.levelRenderer.renderLevel(p_109092_, …)        ← 整座世界（含 TESR）在这里画完
 *   第 1128 行
 *       ForgeHooksClient.dispatchRenderStage(Stage.AFTER_LEVEL, …)     ← 我们挂在这一档
 *   本工程的透镜：BlackHoleLensRenderer.STAGE_GAME_RENDERER =
 *       "GameRenderer@renderLevel:AFTER_INVOKE:LevelRenderer.renderLevel"
 *       ⇒ 它的 mixin 回调插在<b>同一条 invoke 指令之后</b>、第 1128 行之前 ⇒
 *       <b>透镜先画，AFTER_LEVEL 后到</b>。
 * </pre>
 * ⇒ 画在 TESR 里（透镜之前）会被透镜那个 quad 整块覆盖/吃掉（近洞面映射到纯黑）；
 * 画在 {@code AFTER_LEVEL} 才真的"叠在透镜之上"。这也是本件<b>唯一</b>碰到的绘制顺序问题：
 * <b>我们只追加、不插队</b>（既有任何一层的相对次序一个都没动）。
 *
 * <h2>它画什么</h2>
 * 全部几何来自 {@link HostFxExtraGeom#rimArcs}（纯数学，{@link BlackHoleSurfaceWaves} 的波峰解析解）：
 * <ul>
 *   <li>波峰半径 = {@link BlackHoleSurfaceWaves#crestRadius}(n, angle, t) × 黑面半径；</li>
 *   <li>每条的亮度 = 他的 {@code 0.80 × 包络(0.60~0.98) × detail}；颜色 = 纯白（fsh:351 的 {@code vec3(surfaceRipple)}）；</li>
 *   <li>{@code detail} 由<b>每像素的归一化盘半径</b>算（他的 fsh:325 {@code fwidth(bn)/B_CRIT}）⇒ 越远越淡、越稀。</li>
 * </ul>
 *
 * <h2>相机帧怎么拿（AFTER_LEVEL 的 poseStack 是投影栈，不能当 modelview 用）</h2>
 * <pre>
 *   GameRenderer 第 1123-1124 行：matrix3f = invert(p_109092_.last().normal());
 *                                   RenderSystem.setInverseViewRotationMatrix(matrix3f)
 *   p_109092_ 里累积的是 ZP(roll) → XP(xRot) → YP(yRot+180) ⇒ 它就是"世界→相机"的旋转
 *   ⇒ 本类取 RenderSystem.getInverseViewRotationMatrix() 再求逆，得到<b>与这一帧世界渲染同一份</b> view 旋转
 *     （含 roll；用 Camera#getYRot/getXRot 自己拼会漏 roll —— Camera 没有 getRoll()）。
 * </pre>
 * 顶点在世界坐标里现算，modelview 只装那份旋转 + 一次 {@code translate(-camPos)}，
 * 投影用事件给的 {@code getProjectionMatrix()}（那里已经含 bob，与主渲染一致）⇒ 与主世界<b>像素级对齐</b>。
 *
 * <h2>安全</h2>
 * <ul>
 *   <li>整段 {@code try/finally}：矩阵成对 push/pop、投影 backup/restore ⇒ 绝不把 AFTER_LEVEL 之后的
 *       GUI 绘制带歪；</li>
 *   <li>失败只吞掉（记一次 WARN），不让异常冒到渲染线程；</li>
 *   <li>复用 {@link HostFxDraw#beamLayer()}（加性 + 关深度写 + 不剔除），<b>不新建 RenderType、不建纹理</b>。</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = ShanhaiMod.MOD_ID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class HostFxHoleRimWaves {

    /** 上一帧读数被取走的有效期：TESR 每帧刷新一次；超过它说明这台主机这一帧没渲染 ⇒ 不画。 */
    private static final long STALE_NANOS = 250_000_000L;

    private static final double[] CENTER = new double[3];
    private static long stampNanos;
    private static float surfaceR;
    private static double capturedSeconds;
    private static double pixelWidthRho = 0.02D;
    /** 相机到洞心的距离（格）—— px 换算的参考量之一，进日志。 */
    private static double lastDistance;
    /** 帧缓冲高度（px）—— px 换算的参考量之一。 */
    private static int lastViewportHeight = -1;
    /** FOV（度）—— px 换算的参考量之一。 */
    private static double lastFov = -1.0D;
    /** 黑洞中心在屏幕上的像素坐标与半径（-1 = 没算出来；进 {@code rim_screen} 诊断行）。 */
    private static double lastCenterPx = -1.0D;
    private static double lastRadiusPx = -1.0D;

    private static int lastQuads;
    private static String lastState = "off(还没喂过)";
    private static boolean warned;

    private HostFxHoleRimWaves() {
    }

    /**
     * 每帧由 TESR 喂一次（{@link HostFxExtraLayers#render} 末尾调）。
     *
     * @param cx,cy,cz  黑洞中心的世界坐标（= 环心那一格的格中心，与 `feedBlackHole` 同一份）
     * @param holeR     黑洞可见半径（格）
     * @param seconds   世界时间（秒）
     * @param cam       相机世界坐标（算距离与"每像素跨多远"）
     */
    public static void capture(double cx, double cy, double cz, float holeR, float seconds, Vec3 cam) {
        if (!(holeR > 0.0F) || cam == null) {
            return;
        }
        CENTER[0] = cx;
        CENTER[1] = cy;
        CENTER[2] = cz;
        surfaceR = holeR;
        capturedSeconds = seconds;
        final double dx = cam.x - cx;
        final double dy = cam.y - cy;
        final double dz = cam.z - cz;
        final double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        lastDistance = distance;
        pixelWidthRho = pixelWidthRho(distance, holeR);
        stampNanos = System.nanoTime();
    }

    /**
     * 每像素跨过的<b>归一化盘半径</b>（他的 {@code fwidth(bn)/B_CRIT} 的 CPU 版）。
     *
     * <p>逐项（参考量同时存进 {@link #lastDistance}/{@link #lastViewportHeight}/{@link #lastFov}，
     * 都会打进 {@code stats()}）：
     * <pre>
     *   bp    = 2 · 距离 · tan(fov/2) / 帧缓冲高度     ← 每像素多少<b>格</b>
     *   pxRho = bp / 黑盘半径(格)                      ← 每像素多少<b>归一化盘半径</b>
     *   屏幕厚度 px = 2 · half / pxRho = 2 · half · 黑盘半径 / bp
     * </pre>
     * 取不到任何一项时退回 {@code 0.02}（= 满细节附近），绝不因为拿不到窗口尺寸就整件不画。
     */
    private static double pixelWidthRho(double distance, float holeR) {
        try {
            final Minecraft mc = Minecraft.getInstance();
            final double fovDeg = mc.options.fov().get();
            final int height = Math.max(1, mc.getWindow().getHeight());
            lastFov = fovDeg;
            lastViewportHeight = height;
            final double blocksPerPixel = 2.0D * Math.max(0.5D, distance)
                    * Math.tan(Math.toRadians(fovDeg) * 0.5D) / height;
            return blocksPerPixel / holeR;
        } catch (Throwable ignored) {
            return 0.02D;
        }
    }

    /**
     * 一行读数（进 {@code HostFxExtraLayers.stats()}）。
     *
     * <h2>2026-11-22 第三改：标签写清（上一版就是这里被误读）</h2>
     * 旧串 {@code "半宽 0.0120(px 0.0074)"} 里的 {@code px 0.0074} 其实是
     * <b>每像素多少归一化盘半径（pxRho）</b>，不是屏幕厚度；括号里的 {@code px} 让人
     * 以为是"厚度 = 0.0074 px"。现在拆成三个互不混淆的量：
     * <pre>
     *   厚=3.2px    ← 屏幕上真的有多厚（2·half/pxRho）
     *   half=0.0160 ← 归一化半宽
     *   pxRho=0.0074 d=30 h=1080 fov=70  ← 换算用的三件参考量
     * </pre>
     */
    public static String stats() {
        final double thickness = HostFxExtraGeom.rimThicknessPx();
        final double floorOk = HostFxExtraGeom.RIM_MIN_THICKNESS_PX;
        return "白边波浪 " + lastQuads + "/" + HostFxExtraGeom.MAX_RIM_QUADS
                + String.format(java.util.Locale.ROOT,
                " 厚=%.1fpx(地板%.1f) half=%.4f pxRho=%.4f d=%.0f h=%d fov=%.0f rpx=%.0f",
                thickness, floorOk, HostFxExtraGeom.rimHalfWidthRho(), pixelWidthRho,
                lastDistance, lastViewportHeight, lastFov, lastRadiusPx)
                + (thickness + 1.0e-9D >= floorOk ? "" : " ⚠低于地板")
                + " " + lastState;
    }

    /** {@code AFTER_LEVEL}：透镜之后的追加层。 */
    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            return;
        }
        try {
            lastQuads = 0;
            if (!ShanhaiClientConfig.hostFxEnabled()) {
                lastState = "关(hostfx 总开关)";
                return;
            }
            // 黑洞被玩家/命令关掉时不许留一圈"白边"悬在环心（那是假象，不是特效）
            if (!BlackHoleTuning.enabled()) {
                lastState = "关(黑洞总开关)";
                return;
            }
            if (System.nanoTime() - stampNanos > STALE_NANOS) {
                lastState = "未喂料(这一帧没有成型的主机在画)";
                return;
            }
            final List<HostFxGeometry.Quad> quads = HostFxExtraGeom.rimArcs(capturedSeconds,
                    pixelWidthRho, surfaceR, CENTER[0], CENTER[1], CENTER[2],
                    event.getCamera().getPosition().x, event.getCamera().getPosition().y,
                    event.getCamera().getPosition().z);
            if (quads.isEmpty()) {
                lastState = "细节为 0(太远)";
                return;
            }
            // 🔴 自检断言：屏幕厚度必须 ≥ 地板（不满足就打一行，绝不是"提交了就算完"）
            final double thicknessPx = HostFxExtraGeom.rimThicknessPx();
            if (thicknessPx + 1.0e-9D < HostFxExtraGeom.RIM_MIN_THICKNESS_PX) {
                logThrottled("[SHANHAI-HOSTFX] rim_px_floor_violated px="
                        + String.format(java.util.Locale.ROOT, "%.2f", thicknessPx)
                        + " expect>=" + String.format(java.util.Locale.ROOT, "%.1f",
                        HostFxExtraGeom.RIM_MIN_THICKNESS_PX)
                        + " half=" + String.format(java.util.Locale.ROOT, "%.4f",
                        HostFxExtraGeom.rimHalfWidthRho())
                        + " pxRho=" + String.format(java.util.Locale.ROOT, "%.4f", pixelWidthRho)
                        + " d=" + String.format(java.util.Locale.ROOT, "%.1f", lastDistance)
                        + " h=" + lastViewportHeight
                        + " fov=" + String.format(java.util.Locale.ROOT, "%.1f", lastFov));
            }
            final Matrix4f view = viewRotation();
            if (view == null) {
                lastState = "拿不到 view 矩阵";
                return;
            }
            final Vec3 cam = event.getCamera().getPosition();
            updateScreenDiagnostics(cam);
            final PoseStack mvStack = RenderSystem.getModelViewStack();
            mvStack.pushPose();
            RenderSystem.backupProjectionMatrix();
            try {
                mvStack.last().pose().set(view);
                RenderSystem.applyModelViewMatrix();
                RenderSystem.setProjectionMatrix(event.getProjectionMatrix(),
                        VertexSorting.DISTANCE_TO_ORIGIN);
                final PoseStack ps = new PoseStack();
                ps.last().pose().translate((float) -cam.x, (float) -cam.y, (float) -cam.z);
                submit(ps, quads);
                lastQuads = quads.size();
                lastState = "on";
            } finally {
                RenderSystem.restoreProjectionMatrix();
                mvStack.popPose();
                RenderSystem.applyModelViewMatrix();
            }
        } catch (Throwable t) {
            lastState = "异常 " + t.getClass().getSimpleName();
            if (!warned) {
                warned = true;
                ShanhaiMod.LOGGER.warn("[SHANHAI-HOSTFX] 白边波浪（AFTER_LEVEL）异常已兜住，本层本帧不画", t);
            }
        }
    }

    /**
     * 洞心/半径在屏幕上的位置（px）——诊断用：如果半径只有 1~2 px、或者洞心在相机背后，
     * 那"看不见"就不是亮度问题而是位置/尺度问题。只在异常情况打一行（每 5 s 最多一条）。
     */
    private static void updateScreenDiagnostics(Vec3 cam) {
        try {
            final Minecraft mc = Minecraft.getInstance();
            final int w = Math.max(1, mc.getWindow().getWidth());
            final int h = Math.max(1, mc.getWindow().getHeight());
            final double fov = lastFov > 0.0D ? lastFov : 70.0D;
            final double dx = CENTER[0] - cam.x;
            final double dy = CENTER[1] - cam.y;
            final double dz = CENTER[2] - cam.z;
            final double depth = Math.sqrt(dx * dx + dy * dy + dz * dz);
            final double focal = (h * 0.5D) / Math.tan(Math.toRadians(fov) * 0.5D);
            lastRadiusPx = surfaceR * focal / Math.max(1.0e-4D, depth);
            final float yaw = mc.gameRenderer.getMainCamera().getYRot();
            final double lx = -Math.sin(Math.toRadians(yaw));
            final double lz = Math.cos(Math.toRadians(yaw));
            lastCenterPx = depth > 1.0e-4D ? (dx * lx + dz * lz) / depth : 0.0D;
            if (lastCenterPx <= 0.0D) {
                logThrottled("[SHANHAI-HOSTFX] rim_screen behind_camera toward="
                        + String.format(java.util.Locale.ROOT, "%.2f", lastCenterPx)
                        + " radiusPx=" + String.format(java.util.Locale.ROOT, "%.1f", lastRadiusPx)
                        + " d=" + String.format(java.util.Locale.ROOT, "%.1f", lastDistance)
                        + " viewport=" + w + "x" + h);
            }
        } catch (Throwable ignored) {
            // 诊断失败不影响画
        }
    }

    /** 每 5 s 最多一条的日志出口（断言/诊断用）。 */
    private static void logThrottled(String line) {
        final long now = System.currentTimeMillis();
        if (now - lastLogMs < 5_000L) {
            return;
        }
        lastLogMs = now;
        ShanhaiMod.LOGGER.warn(line);
    }

    /** {@code rim_screen} 诊断行的节流时间戳。 */
    private static long lastLogMs;

    /** 世界 → 相机 的<b>旋转</b>矩阵（4×4，无平移）。
     *
     * <p>来源见类注释：{@code RenderSystem.getInverseViewRotationMatrix()} 是它的逆（3×3）。
     * 拿不到 / 退化成非旋转时返回 {@code null}（宁可这一层不画，也不要整块飞掉）。
     */
    private static Matrix4f viewRotation() {
        final Matrix3f inverse = RenderSystem.getInverseViewRotationMatrix();
        if (inverse == null) {
            return null;
        }
        final Matrix4f view = new Matrix4f(inverse);
        final float det = view.determinant3x3();
        if (!(Math.abs(det) > 1.0e-3F) || !Float.isFinite(det)) {
            return null;                                     // 还没设过（恒等/零矩阵）⇒ 不画
        }
        view.invert();
        return view;
    }

    /** 提交（每批一次 {@code endBatch}）。 */
    private static void submit(PoseStack poseStack, List<HostFxGeometry.Quad> quads) {
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
    }

    private static float clamp(float value) {
        return value < 0.0F ? 0.0F : (value > 1.0F ? 1.0F : value);
    }
}
