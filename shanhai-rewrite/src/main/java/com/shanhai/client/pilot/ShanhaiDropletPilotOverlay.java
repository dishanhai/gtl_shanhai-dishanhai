package com.shanhai.client.pilot;

import com.shanhai.common.droplet.ShanhaiDropletPilotCore;
import com.shanhai.common.droplet.ShanhaiDropletProjector;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 驾驶期间的<b>画面叠加层</b>（纯客户端、2D 绘制）：绿色目标框 + 距离/名字/血量 + 准星辅助圈。
 *
 * <h2>一、为什么全部在 2D 阶段画（而不是 3D 世界渲染）</h2>
 * <ul>
 *   <li>3D 阶段那块目录（水滴本体/环/尾迹/高亮/拉线）<b>本轮不许碰</b>；</li>
 *   <li>文本天然是 2D 图元，放 3D 里还要自己摆朝向、管深度；</li>
 *   <li>绿框只要"世界盒的 12 条边投影到屏幕"，本身就是 2D 线段；</li>
 *   <li>投影数学是零 MC 纯函数（{@link ShanhaiDropletProjector}），可以离线断言。</li>
 * </ul>
 * 投影矩阵与相机位姿由 {@link ShanhaiDropletPilotClient} 在 3D 阶段抓一份（同一帧内有效）。
 *
 * <h2>二、成本（写进报告的口径）</h2>
 * 每帧：1 次 view 矩阵 + 每个目标 8 次 {@code toView} + 12 条边各裁剪投影一次；
 * 每条边按 {@link ShanhaiDropletPilotCore#EDGE_DOT_SPACING_PX} 打点、上限
 * {@link ShanhaiDropletPilotCore#MAX_DOTS_PER_EDGE} 个 2×2 像素点；准星圈固定
 * {@link ShanhaiDropletPilotCore#CROSSHAIR_CIRCLE_SEGMENTS} 个点。
 * 32 个目标的最坏情况 ≈ 32×(12×24) = 9216 个 2×2 点 + 96 点圈 + 64 行文本；
 * 典型 8 个目标 ≈ 2300 点 + 96 + 16 行。
 *
 * <h2>三、为什么用"点虚线"而不是 GL 线段</h2>
 * 用 {@link GuiGraphics#fill} 打点走的是原版 GUI 的批量缓冲（{@code RenderType.gui()}），
 * <b>不需要自己切着色器/混合/深度状态</b>，在 GUI 阶段零状态风险；
 * 换成 {@code Tesselator + RenderType.lines()} 反而要自己管
 * {@code setShader/enableBlend/disableDepthTest}，还会与后续 GUI 绘制互相影响。
 * 代价是"虚线"而不是实线 —— 对瞄准框来说反而更不挡视线。
 */
public final class ShanhaiDropletPilotOverlay {

    /** 目标框颜色：绿（规格：绿框标记所有目标）。 */
    private static final int COLOR_BOX = 0xFF3CE04B;

    /** 标签颜色：白。 */
    private static final int COLOR_LABEL = 0xFFFFFFFF;

    /** 准星圈：已锁定某目标（绿）。 */
    private static final int COLOR_RING_LOCKED = 0xFF3CE04B;

    /** 准星圈：未锁定（白灰）。 */
    private static final int COLOR_RING_IDLE = 0xFFE8E8E8;

    /** 屏幕坐标护栏：投影在近平面上会把像素推到 ±1e6，直接画会溢出。 */
    private static final double SCREEN_GUARD = 10000.0D;

    /** 点尺寸（像素，2×2）。 */
    private static final int DOT = 1;

    private ShanhaiDropletPilotOverlay() {}

    /**
     * 画一帧。
     *
     * @param mc 客户端
     * @param graphics GUI 图形上下文
     * @param marks 本 tick 的目标缓存（可为空）
     * @param tanHalfFovY {@code tan(fovY/2)}（由投影矩阵 m11 反解）
     * @param camX 相机世界 X
     * @param camY 相机世界 Y
     * @param camZ 相机世界 Z
     * @param camYaw 相机 yaw（度）
     * @param camPitch 相机 pitch（度）
     * @param assistOn 瞄准辅助是否开启
     * @param assistAngleDeg 瞄准辅助判定角度（度）
     */
    public static void render(Minecraft mc, GuiGraphics graphics, List<ShanhaiDropletPilotTargets.Mark> marks,
                              double tanHalfFovY, double camX, double camY, double camZ,
                              float camYaw, float camPitch, boolean assistOn, double assistAngleDeg) {
        if (mc == null || graphics == null) {
            return;
        }
        if (mc.screen != null) {
            return;             // 有任何界面（物品栏/聊天/暂停）就不画，避免压住 UI
        }
        final int width = mc.getWindow().getGuiScaledWidth();
        final int height = mc.getWindow().getGuiScaledHeight();
        if (width <= 0 || height <= 0 || tanHalfFovY <= 1.0E-6D) {
            return;
        }
        final double aspect = (double) width / (double) height;
        final double[] view = ShanhaiDropletProjector.viewMatrix(camYaw, camPitch, camX, camY, camZ);

        if (marks != null && !marks.isEmpty()) {
            for (final ShanhaiDropletPilotTargets.Mark mark : marks) {
                drawBox(mc, graphics, view, mark, tanHalfFovY, aspect, width, height);
            }
        }
        drawCrosshairRing(graphics, width, height, marks, assistOn, assistAngleDeg, tanHalfFovY,
                view, camX, camY, camZ);
    }

    // ------------------------------------------------------------------ 绿框 + 标签

    private static void drawBox(Minecraft mc, GuiGraphics graphics, double[] view,
                                ShanhaiDropletPilotTargets.Mark mark, double tanHalfFovY, double aspect,
                                int width, int height) {
        final double[][] corners = ShanhaiDropletProjector.corners(
                mark.minX, mark.minY, mark.minZ, mark.maxX, mark.maxY, mark.maxZ);
        final double[][] viewCorners = new double[8][];
        for (int i = 0; i < 8; i++) {
            viewCorners[i] = ShanhaiDropletProjector.toView(view, corners[i][0], corners[i][1], corners[i][2]);
        }

        double topX = Double.NaN;
        double topY = Double.MAX_VALUE;
        double sumX = 0.0D;
        int visibleCorners = 0;

        for (final int[] edge : ShanhaiDropletProjector.AABB_EDGES) {
            final double[][] clipped = ShanhaiDropletProjector.clipSegmentToNear(
                    viewCorners[edge[0]], viewCorners[edge[1]]);
            if (clipped == null) {
                continue;
            }
            final double[] a = ShanhaiDropletProjector.viewToScreen(tanHalfFovY, aspect, width, height, clipped[0]);
            final double[] b = ShanhaiDropletProjector.viewToScreen(tanHalfFovY, aspect, width, height, clipped[1]);
            if (a == null || b == null) {
                continue;
            }
            drawDottedLine(graphics, a[0], a[1], b[0], b[1], COLOR_BOX);
            if (a[1] < topY) {
                topY = a[1];
                topX = a[0];
            }
            if (b[1] < topY) {
                topY = b[1];
                topX = b[0];
            }
            sumX += a[0] + b[0];
            visibleCorners += 2;
        }
        if (visibleCorners == 0 || Double.isNaN(topX)) {
            return;             // 整只在相机背后 / 全部贴近平面上：不画（也不画标签）
        }

        // 标签贴在框的最高点上方两行；水平方向用"框中心的平均 X"而不是单个角，视觉更稳
        final double centerScreenX = sumX / (double) visibleCorners;
        drawLabel(mc, graphics, mark.lineFirst, centerScreenX, topY - 20.0D, width);
        drawLabel(mc, graphics, mark.lineSecond, centerScreenX, topY - 10.0D, width);
    }

    private static void drawLabel(Minecraft mc, GuiGraphics graphics, String text,
                                  double centerX, double y, int width) {
        if (text == null || text.isEmpty()) {
            return;
        }
        final int textWidth = mc.font.width(text);
        final int x = (int) Math.round(centerX - (double) textWidth * 0.5D);
        final int drawY = (int) Math.round(y);
        if (drawXOutOfRange(x, textWidth, width) || drawY < -8 || drawY > 4000) {
            return;
        }
        graphics.drawString(mc.font, text, x, drawY, COLOR_LABEL, true);
    }

    // ------------------------------------------------------------------ 准星辅助圈

    /**
     * 准星圈：半径 {@code r = (H/2)·tan(θ)/tan(fov/2)}（见 {@link ShanhaiDropletPilotCore#circleRadiusPx}）。
     *
     * <p>颜色＝<b>是否锁定</b>：锁定（最近目标落进 θ 锥内且辅助开着）绿、其余白灰。
     * 锁定判定在客户端<b>本地复算</b>（同一套 128 格/模式过滤 + 同一套夹角公式），不额外同步状态。
     */
    private static void drawCrosshairRing(GuiGraphics graphics, int width, int height,
                                          List<ShanhaiDropletPilotTargets.Mark> marks,
                                          boolean assistOn, double assistAngleDeg, double tanHalfFovY,
                                          double[] view, double camX, double camY, double camZ) {
        final double halfHeight = (double) height * 0.5D;
        final double radius = ShanhaiDropletPilotCore.circleRadiusPx(halfHeight, 1.0D / tanHalfFovY, assistAngleDeg);
        if (radius < 2.0D || radius > (double) width) {
            return;
        }
        final boolean locked = assistOn && isLocked(marks, assistAngleDeg, view, camX, camY, camZ);
        final int color = locked ? COLOR_RING_LOCKED : COLOR_RING_IDLE;
        final double cx = (double) width * 0.5D;
        final double cy = (double) height * 0.5D;
        final int segments = ShanhaiDropletPilotCore.CROSSHAIR_CIRCLE_SEGMENTS;
        for (int i = 0; i < segments; i++) {
            final double angle = 2.0D * Math.PI * (double) i / (double) segments;
            final int px = (int) Math.round(cx + radius * Math.cos(angle));
            final int py = (int) Math.round(cy + radius * Math.sin(angle));
            graphics.fill(px - DOT, py - DOT, px + DOT, py + DOT, color);
        }
    }

    /** 最近的那个目标是否落进"准星 θ 锥"。 */
    private static boolean isLocked(List<ShanhaiDropletPilotTargets.Mark> marks, double assistAngleDeg,
                                    double[] view, double camX, double camY, double camZ) {
        if (marks == null || marks.isEmpty()) {
            return false;
        }
        // 🔴 与服务器同一口径：**遍历全部候选取最小角**，不是"拿最近那只"。
        //    （服务器侧是 ShanhaiDropletEntity#pickBestAngleIndex；这里用同一套 angleDeg + assistLocks。）
        //    否则会出现"服务端锁了、圈还是白的"：玩家瞄的是另一只，而最近那只在他身后。
        double minAngle = Double.MAX_VALUE;
        for (final ShanhaiDropletPilotTargets.Mark mark : marks) {
            final double[] targetCenter = ShanhaiDropletProjector.toView(
                    view, mark.centerX(), mark.centerY(), mark.centerZ());
            // 视轴就是相机空间里的 −Z ⇒ 直接与 (0,0,−1) 求夹角，省一次世界坐标变换
            final double angle = ShanhaiDropletPilotCore.angleDeg(0.0D, 0.0D, -1.0D,
                    targetCenter[0], targetCenter[1], targetCenter[2]);
            if (!Double.isNaN(angle) && angle < minAngle) {
                minAngle = angle;
            }
        }
        if (minAngle == Double.MAX_VALUE) {
            return false;
        }
        return ShanhaiDropletPilotCore.assistLocks(minAngle, assistAngleDeg);
    }

    // ------------------------------------------------------------------ 线段打点

    private static void drawDottedLine(GuiGraphics graphics, double x1, double y1, double x2, double y2, int color) {
        if (!withinGuard(x1) || !withinGuard(y1) || !withinGuard(x2) || !withinGuard(y2)) {
            return;
        }
        final double dx = x2 - x1;
        final double dy = y2 - y1;
        final double length = Math.sqrt(dx * dx + dy * dy);
        if (length < 1.0D) {
            final int px = (int) Math.round(x1);
            final int py = (int) Math.round(y1);
            graphics.fill(px - DOT, py - DOT, px + DOT, py + DOT, color);
            return;
        }
        int dots = (int) Math.round(length / ShanhaiDropletPilotCore.EDGE_DOT_SPACING_PX);
        if (dots < ShanhaiDropletPilotCore.MIN_DOTS_PER_EDGE) {
            dots = ShanhaiDropletPilotCore.MIN_DOTS_PER_EDGE;
        }
        if (dots > ShanhaiDropletPilotCore.MAX_DOTS_PER_EDGE) {
            dots = ShanhaiDropletPilotCore.MAX_DOTS_PER_EDGE;
        }
        for (int i = 0; i <= dots; i++) {
            final double t = (double) i / (double) dots;
            final int px = (int) Math.round(x1 + dx * t);
            final int py = (int) Math.round(y1 + dy * t);
            graphics.fill(px - DOT, py - DOT, px + DOT, py + DOT, color);
        }
    }

    private static boolean withinGuard(double value) {
        return value > -SCREEN_GUARD && value < SCREEN_GUARD;
    }

    private static boolean drawXOutOfRange(int x, int textWidth, int width) {
        return x + textWidth < 0 || x > width;
    }
}
