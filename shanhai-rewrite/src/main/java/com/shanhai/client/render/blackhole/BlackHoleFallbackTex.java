package com.shanhai.client.render.blackhole;

/**
 * 山海重构 · <b>二级兜底的程序化贴图</b>（纯逻辑：零 MC / 零 GL，只吐 {@code int[]}）。
 *
 * <h2>它是什么</h2>
 * 一张 {@code size×size} 的 RGBA 图：<b>中心一块不透明的黑影 + 外面一圈白热光环 + 再外面一层很淡的辉光</b>。
 * 任务书原话是「一张程序化生成的黑盘+光环，允许静态」——所以这里<b>不读任何 png、不采样任何背景</b>，
 * 就是算出来的。它的价值是：一级 shader 挂了以后<b>画面上仍然是一团该有的东西</b>，而不是黑块或空白。
 *
 * <h2>坐标/通道约定（与 GL 上传路径逐位对齐）</h2>
 * <ul>
 *   <li><b>行 0 = 图的下边</b>（GL 的纹理行序；传 {@code glTexImage2D} 时不需要翻转）；</li>
 *   <li>每个 {@code int} 按 <b>{@code 0xAABBGGRR}</b> 打包 ——
 *       {@code glTexImage2D(GL_RGBA, GL_UNSIGNED_BYTE, IntBuffer)} 在小端机上正好按
 *       R,G,B,A 四个字节读，所以这个顺序才是"看起来对"的那个；</li>
 *   <li>半透明处返回 {@code alpha = 0}（<b>不画</b>）：保证二级兜底也不会糊出一个方块。</li>
 * </ul>
 *
 * <h2>为什么 {@link #SHADOW_EDGE} 要公开</h2>
 * 渲染侧必须知道"贴图里黑盘的半径占半边的比例"，才能把 quad 摆成
 * 「贴图黑盘 = 真实视界的屏幕半径」。写死两处一定会漂，所以只有这一处常量。
 */
public final class BlackHoleFallbackTex {

    /** 贴图里黑盘外缘 / 半边长的比例（quad 尺寸反算用；渲染侧唯一引用点）。 */
    public static final double SHADOW_EDGE = 0.66;

    /** 黑盘内部完全不透明到这一比例，再往外 0.04 的宽度软过渡到透明（抗锯齿）。 */
    private static final double SHADOW_SOLID = 0.62;
    private static final double SHADOW_FADE = 0.04;

    /** 光环峰值位置 / 半边长，以及宽度。 */
    private static final double RING_CENTER = 0.705;
    private static final double RING_WIDTH = 0.030;

    /** 最外圈淡辉光的边界（超过就 alpha=0）。 */
    private static final double GLOW_EDGE = 0.98;

    private BlackHoleFallbackTex() {}

    /**
     * @param size 边长（建议 256；会被夹到 [16, 2048]）
     * @return 长度 {@code size*size} 的 ABGR 打包像素，行 0 = 下边
     */
    public static int[] generate(int size) {
        final int n = Math.max(16, Math.min(size, 2048));
        final int[] out = new int[n * n];
        final double half = n * 0.5;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                final double px = (x + 0.5) - half;
                final double py = (y + 0.5) - half;
                final double r = Math.sqrt(px * px + py * py) / half;   // 0 = 中心, 1 = 半边
                out[y * n + x] = shade(r);
            }
        }
        return out;
    }

    /** 单个半径比 r 处的像素（单独抽出来就是为了能离线逐点断言）。 */
    public static int shade(double r) {
        // ---- 黑盘：不透明，边缘 0.04 内软掉（AA）
        final double disk = 1.0 - smoothstep(SHADOW_SOLID, SHADOW_SOLID + SHADOW_FADE, r);

        // ---- 光环：一条高斯亮带（白热） + 外面一层更宽更淡的橙辉光
        final double ring = Math.exp(-sq((r - RING_CENTER) / RING_WIDTH));
        final double glow = 0.34 * Math.exp(-sq((r - (RING_CENTER + 0.045)) / 0.085))
                * (1.0 - smoothstep(GLOW_EDGE - 0.10, GLOW_EDGE, r));

        // 光环颜色：内白热 → 外橙
        final double hot = 1.0 - smoothstep(RING_CENTER - 0.01, RING_CENTER + 0.09, r);
        final double rr = ring * 1.00 + glow * 0.85 + disk * 0.0;
        final double gg = ring * (0.72 + 0.26 * hot) + glow * 0.42;
        final double bb = ring * (0.34 + 0.58 * hot) + glow * 0.16;

        final double alphaGlow = Math.min(1.0, ring * 0.95 + glow * 0.55);
        // 黑盘区域内 alpha 恒 1（挡住后面的东西，= 真正的"洞"）；盘外用光环自己的 alpha
        final double alpha = Math.max(disk, alphaGlow);

        final int a = toByte(alpha);
        final int rc = toByte(rr);
        final int gc = toByte(gg);
        final int bc = toByte(bb);
        return (a << 24) | (bc << 16) | (gc << 8) | rc;
    }

    // ------------------------------------------------------------------ 小工具

    private static int toByte(double v) {
        if (v <= 0.0) {
            return 0;
        }
        if (v >= 1.0) {
            return 255;
        }
        return (int) Math.round(v * 255.0);
    }

    private static double sq(double v) {
        return v * v;
    }

    private static double smoothstep(double e0, double e1, double x) {
        if (e1 <= e0) {
            return x >= e1 ? 1.0 : 0.0;
        }
        final double t = Math.max(0.0, Math.min(1.0, (x - e0) / (e1 - e0)));
        return t * t * (3.0 - 2.0 * t);
    }
}
