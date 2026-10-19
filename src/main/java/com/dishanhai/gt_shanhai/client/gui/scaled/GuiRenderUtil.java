package com.dishanhai.gt_shanhai.client.gui.scaled;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * GUI 绘制小工具（移植自 kinetictweaks {@code GuiRenderUtil}）。纯原版实现。
 */
public class GuiRenderUtil {

    /** 画一个带描边的矩形面板。 */
    public static void drawPanel(GuiGraphics g, int x, int y, int w, int h, int bgColor, int outlineColor) {
        g.fill(x, y, x + w, y + h, bgColor);
        g.renderOutline(x, y, w, h, outlineColor);
    }

    /** 标准浅色面板。 */
    public static void drawStandardPanel(GuiGraphics g, int x, int y, int w, int h) {
        drawPanel(g, x, y, w, h, -14935012, -10066330);
    }

    /** 深色半透明面板（商店网格背景用）。 */
    public static void drawDarkPanel(GuiGraphics g, int x, int y, int w, int h) {
        drawPanel(g, x, y, w, h, -2013265920, -11184811);
    }

    /** 整屏半透明遮罩。 */
    public static void drawShadowOverlay(GuiGraphics g, int screenWidth, int screenHeight) {
        g.fill(0, 0, screenWidth, screenHeight, -1073741824);
    }

    /** 文本超宽时截断加省略号。 */
    public static String trimText(Font font, String text, int width) {
        if (text == null) return "";
        if (font.width(text) <= width) return text;
        return font.plainSubstrByWidth(text, Math.max(8, width - font.width("..."))) + "...";
    }

    /** 命中测试。 */
    public static boolean isHovering(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    /**
     * 把 {@code &<格式码>} 转成 {@code §<格式码>}：FTBQ 用 {@code &} 代替 {@code §} 写格式化码，两者等价，
     * 兼容玩家在自定义文本（如商店描述）里两种写法混用。非法字符后的 {@code &} 原样保留。
     */
    public static String translateAmpCodes(String s) {
        if (s == null || s.isEmpty() || s.indexOf('&') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&' && i + 1 < s.length() && isFormatChar(s.charAt(i + 1))) {
                sb.append('§').append(s.charAt(i + 1));
                i++;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean isFormatChar(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')
                || (c >= 'k' && c <= 'o') || (c >= 'K' && c <= 'O') || c == 'r' || c == 'R';
    }

    /** 商店、货币、会员界面统一的单次动效时长。 */
    public static final long MOTION_MS = 150L;
    /** 切页横移距离（逻辑像素）。只平移，不叠缩放。 */
    public static final float TAB_SLIDE_DIST = 32f;

    /**
     * 弹入动画进度 [0,1]：{@code atMs} 为动画起始时刻（{@code System.currentTimeMillis()}），
     * {@code atMs<=0} 视为没有进行中的动画，直接返回 1。
     */
    public static float popAnimProgress(long atMs, long durationMs) {
        if (atMs <= 0L || durationMs <= 0L) return 1f;
        return Math.min(1f, (System.currentTimeMillis() - atMs) / (float) durationMs);
    }

    /** ease-out：{@code 1-(1-t)^2}。t 先夹到 [0,1]。 */
    public static float easeOut(float t) {
        float c = t < 0f ? 0f : (t > 1f ? 1f : t);
        return 1f - (1f - c) * (1f - c);
    }

    /**
     * 以 (cx,cy) 为锚点做一次缩放弹入（0.94→1.0，ease-out）。
     * 调用方须自行 pushPose/popPose，范围只包住这一层面板。锚点是该面板中心。
     */
    public static void popScaleAt(GuiGraphics g, float cx, float cy, float t) {
        if (t >= 1f) return;
        float scale = 0.94f + 0.06f * easeOut(t);
        g.pose().translate(cx, cy, 0);
        g.pose().scale(scale, scale, 1f);
        g.pose().translate(-cx, -cy, 0);
    }

    /** 切页横向偏移。dir&gt;0 从右侧进入，dir&lt;0 从左侧进入。播完为 0。 */
    public static float slideOffsetX(int dir, float t) {
        if (t >= 1f) return 0f;
        return dir * TAB_SLIDE_DIST * (1f - easeOut(t));
    }

    /** 切页只做横向平移。调用方须自行 pushPose/popPose。 */
    public static void slideInX(GuiGraphics g, int dir, float t) {
        float offset = slideOffsetX(dir, t);
        if (offset != 0f) g.pose().translate(offset, 0, 0);
    }

    /** t&gt;=1 时直接画。否则以 (cx,cy) 为中心套一次 {@link #popScaleAt}。 */
    public static void withPopScale(GuiGraphics g, float cx, float cy, float t, Runnable draw) {
        if (t >= 1f) {
            draw.run();
            return;
        }
        g.pose().pushPose();
        try {
            popScaleAt(g, cx, cy, t);
            draw.run();
        } finally {
            g.pose().popPose();
        }
    }

    /**
     * 共享元素飞行（商品卡片 → 右侧详情面板）用的单弹簧。
     *
     * <p>只保留 {@code value} 与 {@code velocity}，中途改目标不换系数；默认参数为
     * {@code response = 0.22s}、{@code dampingFraction = 0.92}，接近临界阻尼，落位时几乎不超调。
     * 商品卡片离详情面板越远，起手时传入的 response 越大（见 {@link #flightResponseForDistance(double)}），
     * 动画时长随距离增加，但上限仍是 0.32s。
     * 时间用毫秒，单步超过 50ms 截断，内部再拆成 4ms 子步，避免帧卡顿时显式积分发散。</p>
     */
    public static final class FluidSpring {
        public static final double RESPONSE_S = 0.22;
        public static final double DAMPING_FRACTION = 0.92;

        private static final double OMEGA = 2.0 * Math.PI / RESPONSE_S;
        private static final double STIFFNESS = OMEGA * OMEGA;
        private static final double DRAG = 2.0 * DAMPING_FRACTION * OMEGA;
        private static final double MIN_RESPONSE_S = 0.16;
        private static final double MAX_RESPONSE_S = 0.32;
        private static final double DISTANCE_FOR_MAX_RESPONSE = 800.0;
        private static final double MAX_STEP_S = 0.05;
        private static final double SUBSTEP_S = 0.004;
        private static final float SETTLE_EPS = 0.0015f;
        private static final float SETTLE_VELOCITY = 0.02f;

        private float value;
        private float velocity;
        private long lastMs;
        private boolean settled = true;
        private double stiffness = STIFFNESS;
        private double drag = DRAG;

        /** 从 0 起弹向 1，使用默认 response 0.22s。{@code nowMs} 取自 {@code System.currentTimeMillis()}。 */
        public void start(long nowMs) {
            start(nowMs, RESPONSE_S);
        }

        /** 从 0 起弹向 1，按本次距离换算出的 response 起手；中途不再改系数。 */
        public void start(long nowMs, double responseSeconds) {
            double response = Math.max(MIN_RESPONSE_S, Math.min(MAX_RESPONSE_S, responseSeconds));
            double omega = 2.0 * Math.PI / response;
            this.stiffness = omega * omega;
            this.drag = 2.0 * DAMPING_FRACTION * omega;
            this.value = 0f;
            this.velocity = 0f;
            this.lastMs = nowMs;
            this.settled = false;
        }

        /** 推进到 {@code nowMs}。已经归位后重复调用无副作用。 */
        public void update(long nowMs) {
            if (this.settled) return;
            double dt = (nowMs - this.lastMs) / 1000.0;
            this.lastMs = nowMs;
            if (dt <= 0.0) return;
            if (dt > MAX_STEP_S) dt = MAX_STEP_S;

            int steps = Math.max(1, (int) Math.ceil(dt / SUBSTEP_S));
            double h = dt / steps;
            for (int i = 0; i < steps; i++) {
                double accel = -this.stiffness * (this.value - 1.0) - this.drag * this.velocity;
                this.velocity += (float) (accel * h);
                this.value += (float) (this.velocity * h);
            }

            if (!Float.isFinite(this.value) || !Float.isFinite(this.velocity)) {
                this.value = 1f;
                this.velocity = 0f;
                this.settled = true;
            } else if (Math.abs(this.value - 1f) < SETTLE_EPS
                    && Math.abs(this.velocity) < SETTLE_VELOCITY) {
                this.value = 1f;
                this.velocity = 0f;
                this.settled = true;
            }
        }

        public float value() {
            return this.value;
        }

        public boolean settled() {
            return this.settled;
        }
    }

    /**
     * 卡片到详情面板的距离 → 弹簧 response：越远越慢，最近 0.16s，800 逻辑像素及以上封顶 0.32s。
     * 只影响起手参数，动画过程中不换系数。
     */
    public static double flightResponseForDistance(double distance) {
        double t = distance / 800.0;
        if (t < 0.0) t = 0.0;
        if (t > 1.0) t = 1.0;
        return 0.16 + 0.16 * t;
    }
}
