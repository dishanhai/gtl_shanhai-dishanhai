package com.shanhai.client.render.hostfx;

/**
 * 山海 · <b>黑洞"黑面"边缘的白边波浪</b>（作者 dishanhai，2026-11-22 移植进本工程）。
 *
 * <h2>来源与"纯移植"口径</h2>
 * 逐行照抄他的 {@code com.dishanhai.gt_shanhai.client.renderer.blackhole.BlackHoleSurfaceWaves}
 * （{@code temp/upstream-bump/src/BlackHoleSurfaceWaves.java}）。他的类注释逐字：
 * 「{@code 山海：主渲染探針與降級幾何共用的黑色表面白邊波浪；常數對齊 guixu_blackhole.fsh。}」
 *
 * <h2>与他的 shader 逐条对上的证据（不是推测）</h2>
 * 已把 {@code assets/gt_shanhai/shaders/core/guixu_blackhole.fsh} 取到本地
 * （{@code temp/upstream-bump/guixu_blackhole.fsh}，17114 字节），逐处对上：
 * <pre>
 *   fsh:146  surfacePhase(r,a) = (r − 0.62) * 39.2699082 + 1.50 sin(3a − 0.3t) + 0.65 sin(5a + 0.2t) − 0.85t
 *   fsh:141  surfaceRadius(a,d) = B_CRIT * (1 + d * (0.012 sin(3a + 0.5t) + 0.006 sin(5a − 0.37t)))
 *   fsh:325  surfaceDetail = 1 − smoothstep(0.014, 0.055, fwidth(bn) / B_CRIT)
 *   fsh:334  waveCrest = exp(−(sin(wavePhase*0.5) / waveWidth)^2)
 *   fsh:335  surfaceRipple = 0.80 * waveCrest * surfaceDetail * shadowMask
 *                            * smoothstep(0.60, 0.67, surfaceRho) * (1 − smoothstep(0.92, 0.98, surfaceRho))
 *   fsh:351  col += vec3(surfaceRipple) * maskFade        ← 加性、纯白
 * </pre>
 * ⇒ 本类就是他这套的 <b>CPU 版</b>：{@link #RADIAL_FREQUENCY}=39.2699082、3 阶 1.50 / 5 阶 0.65、
 * 时间项 −0.85、{@link #envelope} 的 0.60/0.67 与 0.92/0.98、{@link #detail} 的 0.014/0.055
 * —— <b>一个数都没有改</b>。
 *
 * <h2>半径是"归一化"的（这一点最容易搞错）</h2>
 * shader 里 {@code surfaceRho = bn / waveRadius}，其中 {@code bn} 是屏幕空间碰撞参数、
 * {@code waveRadius = B_CRIT * (1 + 抖動)} 就是<b>黑影边缘的归一化半径</b> ⇒
 * <b>{@code surfaceRho = 1.0} 恰好是黑洞可见黑盘的边缘</b>，包络带 [0.60, 0.98] 落在黑盘内侧。
 * 本工程用它时口径因此是：<b>世界半径 = 黑盘半径 × surfaceRho</b>（见 {@code HostFxExtraGeom.rimArcs}）。
 *
 * <h2>零 Minecraft 依赖</h2>
 * 只有 {@code java.lang} ⇒ 可脱机 {@code javac} 单编、离线探针直接断言（{@code temp/migrate-fx/probe/}）。
 */
public final class BlackHoleSurfaceWaves {

    private static final double TAU = Math.PI * 2;
    /** 径向频率 —— 他的值（= 2π × 6.25；shader 里是同一个字面量）。 */
    private static final double RADIAL_FREQUENCY = 39.2699082;

    private BlackHoleSurfaceWaves() {
    }

    /**
     * 细节系数：每像素的<b>归一化</b>径向宽度（= 每像素多少格 ÷ 黑盘半径）越大 ⇒ 细节越少。
     *
     * <p>他的 shader 那一行：{@code 1 − smoothstep(0.014, 0.055, fwidth(bn)/B_CRIT)} ——
     * ⇒ 远到"一个像素跨 5.5% 盘半径"时波浪整条消失，近到"一个像素跨 1.4%"时满细节。
     *
     * @param pixelWidth 每像素跨过的归一化半径（无单位，见 {@code HostFxHoleRimWaves#pixelWidthRho}）
     * @return 0..1
     */
    public static double detail(double pixelWidth) {
        return 1 - smooth(0.014, 0.055, pixelWidth);
    }

    /** 黑面半径的<b>比例</b>抖动：3 阶 0.012 + 5 阶 0.006（乘在 1 上，所以抖动幅度 ±1.8%）。 */
    public static double radiusScale(double angle, double time, double detail) {
        return 1 + detail * (0.012 * Math.sin(angle * 3 + time * 0.5)
                + 0.006 * Math.sin(angle * 5 - time * 0.37));
    }

    private static double angularPhase(double angle, double time) {
        return 1.50 * Math.sin(angle * 3 - time * 0.3) + 0.65 * Math.sin(angle * 5 + time * 0.2);
    }

    /**
     * 波浪相位（以 {@code radius} 为归一化半径、{@code angle} 为角向）。
     *
     * <p>{@code phase = 2πn} 处就是一条波峰 ⇒ 用 {@link #crestRadius} 反解出波峰所在的归一化半径。
     */
    public static double phase(double radius, double angle, double time) {
        return (radius - 0.62) * RADIAL_FREQUENCY + angularPhase(angle, time) - time * 0.85;
    }

    /**
     * phase = 2πn 的解析波峰；取模只替换进出黑色遮罩的波峰索引。
     *
     * @param wave 波峰序号 n（可正可负；调用方按包络带筛）
     * @return 该波峰在 {@code angle} 方向上的归一化半径
     */
    public static double crestRadius(int wave, double angle, double time) {
        double radialPhase = (time * 0.85) % TAU;
        if (radialPhase < 0) {
            radialPhase += TAU;
        }
        return 0.62 + (TAU * wave - angularPhase(angle, time) + radialPhase) / RADIAL_FREQUENCY;
    }

    /** 包络：0.60→0.67 淡入、0.92→0.98 淡出（他的值，与 fsh:336-337 逐字一致）。 */
    public static double envelope(double radius) {
        return smooth(0.60, 0.67, radius) * (1 - smooth(0.92, 0.98, radius));
    }

    /** 径向频率（离线探针/报告引用；与他的 39.2699082 同值）。 */
    public static double radialFrequency() {
        return RADIAL_FREQUENCY;
    }

    /** smoothstep（他原文的 {@code smooth}，端点用三次 Hermite）。 */
    private static double smooth(double low, double high, double value) {
        double t = Math.max(0, Math.min(1, (value - low) / (high - low)));
        return t * t * (3 - 2 * t);
    }
}
