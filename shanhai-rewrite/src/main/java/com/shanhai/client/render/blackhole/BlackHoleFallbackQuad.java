package com.shanhai.client.render.blackhole;

import java.util.Locale;

/**
 * 山海重构 · <b>二级兜底四边形的"屏幕矩形 + uv"规划器</b>（纯逻辑：零 MC / 零 GL / 零 JOML）。
 *
 * <h2>0. 🔴 它修的是什么（用户 2026-10-25：「传送走再传送回来黑洞渲染位置错了，右下角那一团光」）</h2>
 * 改前的二级四边形是<b>世界空间公告板</b>：4 个顶点写成"相机相对世界坐标"
 * （{@code center − camPos}、{@code ± camUp·half}、{@code ± camLeft·half}），
 * 靠 {@code RenderSystem.getModelViewMatrix()} 把它变换到屏幕。
 * 那份代码的注释写着「此刻 RenderSystem.getModelViewMatrix() 就是相机旋转矩阵」——
 * <b>那句话只在 {@code AFTER_WEATHER} 成立</b>。而这条绘制在
 * {@code GameRenderer.renderLevel} 里 {@code RenderSystem.clear(256)} 之前（= {@code LevelRenderer.renderLevel}
 * 返回之后），那时 {@code LevelRenderer} 已经在方法尾做过
 * {@code posestack.popPose(); RenderSystem.applyModelViewMatrix();}（本地反编译
 * {@code LevelRenderer.java} 第 <b>1385-1387</b> 行 push + 乘相机旋转、第 <b>1420-1421</b> 行 pop + 重新 apply）
 * ⇒ <b>{@code RenderSystem.getModelViewMatrix()} = 单位阵</b>。
 * <p>于是那张四边形的屏幕位置<b>不由"洞在哪"决定</b>：世界方向 + 相机基向量一起被当成 NDC 附近的坐标去投影，
 * 落点由"洞相对于世界坐标轴的方位"决定 —— 传送前恰好盖住洞、传送后落到右下角，
 * 就是这个 bug 的全部内容。同一个洞、同一个朝向，只因为世界方位不同，团光就换地方。
 *
 * <h2>1. 现在的口径（"用当帧的相机与投影算屏幕矩形，并夹在屏幕内"）</h2>
 * 不再走世界空间。屏幕矩形直接用<b>同一帧</b> {@code capture()} 已经算好的三样东西：
 * <ul>
 *   <li>{@code holeScreen[0..1]} —— 洞心在屏幕上的像素坐标（左下原点），由<b>当帧</b>的
 *       {@code uProjMat} × {@code uBhView} 投影而来；</li>
 *   <li>{@code lensHalfPx} —— 整张正方形贴图的半边长（像素）；</li>
 *   <li>{@code rectX0/rectY0/rectW/rectH} —— 已经夹进屏幕、并且被像素预算收小过的那块矩形
 *       （就是一级透镜画的那块，逐像素同一块 ⇒ 二级永远不会跑到一级以外的地方）。</li>
 * </ul>
 * 本类只做两件事：① 把这块矩形换算成 NDC（{@code −1..1}）；② 算出它<b>对应贴图里的哪一小块</b>
 * （{@code uv}）—— 因为矩形可能被屏幕边缘裁掉一截，此时不能把整张贴图铺上去，
 * 否则黑洞贴图会跟着矩形一起挤扁。裁切 + uv 重算是"夹在屏幕内"的正确做法。
 *
 * <h2>2. 贴图方向</h2>
 * 屏幕矩形用<b>左下原点</b>（与 {@code gl_FragCoord} / {@code glCopyTexSubImage2D} 一致）；
 * 贴图 {@code v} 原点在<b>上</b> ⇒ {@code v = 1 − t}。这里只做一换算，调用方不再翻。
 */
public final class BlackHoleFallbackQuad {

    /**
     * 洞心离画面多远还算"可能看得见"。
     *
     * <p>= 屏幕外再放宽一整屏。判据：{@code x ∈ [−W, 2W]} 且 {@code y ∈ [−H, 2H]}。
     * <p>为什么要这一条：{@code rectAround} 会把"以洞心为中心的正方形"夹进屏幕，
     * 洞心远在画面外时夹出来的是一条<b>边缘薄片</b>（实机日志里出现过 2880×1659 屏上
     * {@code rect=[2650.4,1642.8 138.5x16.2]}，正是右下角）—— 那条薄片画的就是用户说的
     * "右下角那一团光"。洞心偏轴 80° 以上时它没有任何信息量，应该<b>不画</b>。
     */
    public static final double ANCHOR_MARGIN_SCREENS = 1.0D;

    /** {@code screenR} 的硬上限倍数：{@code cap = 2 × 屏高}（任务书口径）。 */
    public static final double SCREEN_R_CAP_H = 2.0D;

    private BlackHoleFallbackQuad() {}

    /** 规划结果（都是 NDC / uv / 像素读数的裸值，方便离线逐字比对）。 */
    public static final class Plan {
        /** 矩形左边 / 下边 / 右边 / 上边（NDC，−1..1，左下原点）。 */
        public final double x0Ndc;
        public final double y0Ndc;
        public final double x1Ndc;
        public final double y1Ndc;
        /** 这一帧黑洞中心所在平面的 NDC 深度（4 个角相同 ⇒ 透视插值后恒定）。 */
        public final double ndcZ;
        /** 贴图 uv：{@code u0..u1} 横向、{@code vBottom..vTop} 纵向（v 原点在上）。 */
        public final double u0;
        public final double u1;
        public final double vBottom;
        public final double vTop;
        /** 像素矩形（原样带回来进日志）。 */
        public final double px0;
        public final double py0;
        public final double pw;
        public final double ph;
        /** 整张正方形贴图在屏幕上的半边长（像素）与洞心像素坐标。 */
        public final double half;
        public final double holeX;
        public final double holeY;

        private Plan(double x0Ndc, double y0Ndc, double x1Ndc, double y1Ndc, double ndcZ,
                     double u0, double u1, double vBottom, double vTop,
                     double px0, double py0, double pw, double ph,
                     double half, double holeX, double holeY) {
            this.x0Ndc = x0Ndc;
            this.y0Ndc = y0Ndc;
            this.x1Ndc = x1Ndc;
            this.y1Ndc = y1Ndc;
            this.ndcZ = ndcZ;
            this.u0 = u0;
            this.u1 = u1;
            this.vBottom = vBottom;
            this.vTop = vTop;
            this.px0 = px0;
            this.py0 = py0;
            this.pw = pw;
            this.ph = ph;
            this.half = half;
            this.holeX = holeX;
            this.holeY = holeY;
        }

        /** 像素矩形那一串（进日志；与 {@code parts_missing} 的 {@code rect=[x,y WxH]} 同格式）。 */
        public String rectText() {
            return String.format(Locale.ROOT, "[%.1f,%.1f %.1fx%.1f]", px0, py0, pw, ph);
        }

        /** uv 那一串（进日志）。 */
        public String uvText() {
            return String.format(Locale.ROOT, "[%.4f,%.4f..%.4f,%.4f]", u0, vBottom, u1, vTop);
        }
    }

    /**
     * 由"当帧的洞心 + 透镜半径 + 已夹好的矩形"算出 NDC 矩形与贴图 uv。
     *
     * @param holeX    洞心像素 x（左下原点）
     * @param holeY    洞心像素 y（左下原点）
     * @param halfPx   整张正方形贴图在屏幕上的半边长（像素）
     * @param rectX0   实际要画的矩形（已经被 {@link BlackHoleLensMath#rectAround} 夹进屏幕）
     * @param rectY0   "
     * @param rectW    "
     * @param rectH    "
     * @param screenW  主渲染目标宽
     * @param screenH  主渲染目标高
     * @param ndcZ     洞心所在平面的 NDC 深度
     * @return {@code null} = 这一帧画不了（几何退化 / 矩形退化 / 非有限数），调用方<b>不许</b>硬画
     */
    public static Plan plan(double holeX, double holeY, double halfPx,
                            double rectX0, double rectY0, double rectW, double rectH,
                            int screenW, int screenH, double ndcZ) {
        if (screenW <= 0 || screenH <= 0) {
            return null;
        }
        if (!finite(holeX) || !finite(holeY) || !finite(halfPx)
                || !finite(rectX0) || !finite(rectY0) || !finite(rectW) || !finite(rectH)
                || !finite(ndcZ)) {
            return null;
        }
        if (!(halfPx > 1.0e-6D) || !(rectW > 0.0D) || !(rectH > 0.0D)) {
            return null;
        }
        // 矩形必须夹在屏幕里（调用方已经夹过；这里再夹一次，保证"绝不画到屏幕外"是可离线验证的）
        final double x0 = clamp(rectX0, 0.0D, screenW);
        final double y0 = clamp(rectY0, 0.0D, screenH);
        final double x1 = clamp(rectX0 + rectW, 0.0D, screenW);
        final double y1 = clamp(rectY0 + rectH, 0.0D, screenH);
        final double w = x1 - x0;
        final double h = y1 - y0;
        if (!(w > 0.0D) || !(h > 0.0D)) {
            return null;
        }
        // 整张正方形在屏幕上的范围：以洞心为中心、半边长 halfPx
        final double sq0x = holeX - halfPx;
        final double sq1x = holeX + halfPx;
        final double sq0y = holeY - halfPx;
        final double sq1y = holeY + halfPx;
        final double twoHalf = 2.0D * halfPx;
        // 屏幕像素 → 贴图 uv（左下原点 → 贴图 t，再翻成 v）
        final double u0 = clamp01((x0 - sq0x) / twoHalf);
        final double u1 = clamp01((x1 - sq0x) / twoHalf);
        final double tBottom = clamp01((y0 - sq0y) / twoHalf);
        final double tTop = clamp01((y1 - sq0y) / twoHalf);
        final double vBottom = clamp01(1.0D - tBottom);
        final double vTop = clamp01(1.0D - tTop);
        // 像素 → NDC
        final double x0Ndc = x0 / screenW * 2.0D - 1.0D;
        final double x1Ndc = x1 / screenW * 2.0D - 1.0D;
        final double y0Ndc = y0 / screenH * 2.0D - 1.0D;
        final double y1Ndc = y1 / screenH * 2.0D - 1.0D;
        return new Plan(x0Ndc, y0Ndc, x1Ndc, y1Ndc, clamp(ndcZ, -1.0D, 1.0D),
                u0, u1, vBottom, vTop, x0, y0, w, h, halfPx, holeX, holeY);
    }

    /**
     * 洞心是不是还"在画面附近"（判据见 {@link #ANCHOR_MARGIN_SCREENS}）。
     *
     * <p>不满足 ⇒ 这块矩形只是屏幕边缘的一条薄片，画出来就是"右下角那一团光"。
     */
    public static boolean anchorVisible(double holeX, double holeY, int screenW, int screenH) {
        if (!finite(holeX) || !finite(holeY) || screenW <= 0 || screenH <= 0) {
            return false;
        }
        final double mx = screenW * ANCHOR_MARGIN_SCREENS;
        final double my = screenH * ANCHOR_MARGIN_SCREENS;
        return holeX >= -mx && holeX <= screenW + mx
                && holeY >= -my && holeY <= screenH + my;
    }

    /**
     * {@code screenR} 的硬上限（任务书第 3 条）：{@code min(raw, 高度 × }{@value #SCREEN_R_CAP_H}{@code )}。
     *
     * <p>为什么要有：{@code screenR = rs · B_CRIT · (H/2)/(tan · forwardDist)}，而
     * {@code forwardDist} 是<b>前向</b>距离。洞偏轴 85° 时前向距离只剩 2~3 格 ⇒ 读数能到 1700+ px，
     * 而那块矩形其实已经被夹到屏幕边缘的一条薄片。于是"日志读数"和"真画出来的"彻底脱钩
     * —— 实机日志里 {@code screenR=1725.9} 旁边就是 {@code parts=disk}（四件只剩一件）。
     */
    public static double clampScreenR(double raw, int screenH) {
        if (!finite(raw) || raw < 0.0D) {
            return 0.0D;
        }
        if (screenH <= 0) {
            return raw;
        }
        final double cap = screenH * SCREEN_R_CAP_H;
        return raw > cap ? cap : raw;
    }

    /** 被夹掉多少像素（{@code 0} = 没夹；进日志的 {@code screenR_cut=}）。 */
    public static double screenRCut(double raw, int screenH) {
        if (!finite(raw) || raw < 0.0D || screenH <= 0) {
            return 0.0D;
        }
        final double cap = screenH * SCREEN_R_CAP_H;
        return raw > cap ? raw - cap : 0.0D;
    }

    private static boolean finite(double v) {
        return Double.isFinite(v);
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static double clamp01(double v) {
        return clamp(v, 0.0D, 1.0D);
    }
}
