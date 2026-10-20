package com.dishanhai.gt_shanhai.client.gui.scaled;

/** 动画用的纯数学小工具，不依赖 Minecraft。 */
public final class GuiMotion {

    private GuiMotion() {}

    public static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    public static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /** ARGB 逐通道线性插值，t 先夹到 [0,1]。 */
    public static int lerpColor(int a, int b, float t) {
        float c = clamp01(t);
        int aa = channel(a, 24), ar = channel(a, 16), ag = channel(a, 8), ab = channel(a, 0);
        int ba = channel(b, 24), br = channel(b, 16), bg = channel(b, 8), bb = channel(b, 0);
        return (mix(aa, ba, c) << 24) | (mix(ar, br, c) << 16) | (mix(ag, bg, c) << 8) | mix(ab, bb, c);
    }

    /**
     * 浮层面板缩放系数 0.94→1。输入是弹簧值（已经带缓动），不再套 easeOut；
     * 端点与 {@link GuiRenderUtil#popScale} 相同，弹簧略超过 1 或低于 0 时夹住。
     */
    public static float panelScale(float p) {
        return 0.94f + 0.06f * clamp01(p);
    }

    private static int channel(int argb, int shift) {
        return (argb >>> shift) & 0xFF;
    }

    private static int mix(int from, int to, float t) {
        return Math.round(from + (to - from) * t) & 0xFF;
    }
}
