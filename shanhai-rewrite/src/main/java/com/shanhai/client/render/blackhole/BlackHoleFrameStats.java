package com.shanhai.client.render.blackhole;

/**
 * 山海重构 · 黑洞渲染件的 <b>「最近一帧」读数</b>（纯逻辑：零 MC / 零 GL）。
 *
 * <h2>它解决的具体问题</h2>
 * 用户要 {@code /shanhai bh status} 能一口气回答：现在在第几档 / 为什么 / 中心在哪 / 视界多大 /
 * 刚才那一帧占了屏幕多少 / 投影反解出来的 {@code tan(fovY/2)} 是多少。
 * 这些量<b>只有渲染器知道</b>，而渲染器每帧都在跑 —— 所以由它写进这里，命令只读这里。
 *
 * <h2>为什么要单独一个类</h2>
 * 与其它纯逻辑件同一个理由：{@link #formatStatus} 是<b>唯一的格式化出口</b>，
 * 只吃参数、不碰游戏 ⇒ 能在没有游戏的情况下逐字比对（见
 * {@code temp/bh-spike/selftest} 的 {@code stats.*} 用例组）。
 * 渲染器那边只负责"把这一帧的真实读数塞进来"。
 *
 * <h2>两套档位编号（用户裁决里两种叫法都出现了，所以两个都打）</h2>
 * <ul>
 *   <li>{@code level} = 1 / 2 / 3（本件的内部编号，也是<b>日志行</b>
 *       {@code [SHANHAI-BH] fallback=<2|3>} 里那个数）；</li>
 *   <li>{@code fallback} = 0 / 1 / 2（用户裁决里的叫法：0 = 一级正常，1 = 贴图兜底，2 = 什么都不画），
 *       恒等于 {@code level - 1}。</li>
 * </ul>
 * 两个数<b>一起打</b>，避免"status 说 1、日志说 2"这种对不上号的误会。
 */
public final class BlackHoleFrameStats {

    /** 这一帧没画的原因：还没跑过任何一帧。 */
    public static final String SKIP_NONE_YET = "no_frame_yet";
    /** 总开关关着。 */
    public static final String SKIP_DISABLED = "enabled_off";
    /** 没有中心坐标（toml 空且命令没设）。 */
    public static final String SKIP_NO_CENTER = "center_empty";
    /** 不在世界里（主菜单 / 无 level）。 */
    public static final String SKIP_NO_LEVEL = "no_level";
    /** 距离超过 64 格。 */
    public static final String SKIP_TOO_FAR = "dist_too_far";
    /** 在相机背后。 */
    public static final String SKIP_BEHIND = "behind_camera";
    /** 投影后不在视锥内。 */
    public static final String SKIP_OUT_OF_FRUSTUM = "out_of_frustum";
    /** 屏幕半径太小（<8 px）。 */
    public static final String SKIP_TOO_SMALL = "too_small";
    /** 矩形被裁没了（完全在画面外）。 */
    public static final String SKIP_NO_RECT = "off_screen";
    /** 投影矩阵反解出来的 {@code tan(fovY/2)} 不合理（望远镜/调试相机之类的极端值）。 */
    public static final String SKIP_BAD_PROJECTION = "bad_projection";
    /** 一级没走成、二级也没画出来（例如贴图还没建好）。 */
    public static final String SKIP_QUAD_FAILED = "quad_failed";
    /** 三级：什么都不画。 */
    public static final String SKIP_DEAD = "dead_level3";
    /** 这一帧画了一级。 */
    public static final String DREW_FULL = "drew_full";
    /** 这一帧画了二级。 */
    public static final String DREW_QUAD = "drew_quad";

    // ---- 渲染器每帧写、status 读 ----
    private static volatile String lastSkip = SKIP_NONE_YET;
    private static volatile double[] lastCenter;          // 这一帧真正用的中心（null = 没有）
    private static volatile double lastRsBlocks = 1.0D;   // 这一帧真正用的视界半径（格）
    private static volatile double lastDist = -1.0D;      // 到相机的距离（格）
    private static volatile double lastScreenFraction;    // 矩形面积 / 全屏面积
    private static volatile double lastRectW;
    private static volatile double lastRectH;
    private static volatile double lastTanHalfFov;        // tan(fovY/2)，从投影矩阵反解
    private static volatile boolean lastShaderpack;
    private static volatile long lastFrameNanos;

    private BlackHoleFrameStats() {}

    // ------------------------------------------------------------------ 写（渲染器）

    /** 这一帧什么都没画；{@code why} 取本类的 {@code SKIP_*} 常量。 */
    public static void noteSkip(String why) {
        lastSkip = (why == null || why.isEmpty()) ? "unknown" : why;
        lastScreenFraction = 0.0D;
        lastRectW = 0.0D;
        lastRectH = 0.0D;
        lastFrameNanos = System.nanoTime();
    }

    /**
     * 这一帧真的画了。
     *
     * @param drewFull 是否是一级（false = 二级）
     */
    public static void noteDraw(boolean drewFull, double[] center, double rsBlocks, double dist,
                                double rectW, double rectH, double screenW, double screenH,
                                double tanHalfFov, boolean shaderpack) {
        lastSkip = drewFull ? DREW_FULL : DREW_QUAD;
        lastCenter = (center == null) ? null : new double[]{center[0], center[1], center[2]};
        lastRsBlocks = rsBlocks;
        lastDist = dist;
        lastRectW = rectW;
        lastRectH = rectH;
        lastScreenFraction = (screenW > 0.0D && screenH > 0.0D)
                ? (rectW * rectH) / (screenW * screenH) : 0.0D;
        lastTanHalfFov = tanHalfFov;
        lastShaderpack = shaderpack;
        lastFrameNanos = System.nanoTime();
    }

    /** 只更新"环境"读数字段（没有画、但已经算出了 tan/fov 的场合）。 */
    public static void noteEnv(double tanHalfFov, boolean shaderpack, double[] center,
                               double rsBlocks, double dist) {
        lastTanHalfFov = tanHalfFov;
        lastShaderpack = shaderpack;
        lastCenter = (center == null) ? null : new double[]{center[0], center[1], center[2]};
        lastRsBlocks = rsBlocks;
        lastDist = dist;
    }

    public static String lastSkip() {
        return lastSkip;
    }

    public static boolean lastDrewFull() {
        return DREW_FULL.equals(lastSkip);
    }

    public static boolean lastDrewAny() {
        return DREW_FULL.equals(lastSkip) || DREW_QUAD.equals(lastSkip);
    }

    public static long lastFrameNanos() {
        return lastFrameNanos;
    }

    public static double lastScreenFraction() {
        return lastScreenFraction;
    }

    public static double lastRectW() {
        return lastRectW;
    }

    public static double lastRectH() {
        return lastRectH;
    }

    public static boolean lastShaderpack() {
        return lastShaderpack;
    }

    public static double lastTanHalfFov() {
        return lastTanHalfFov;
    }

    // ------------------------------------------------------------------ 格式化（唯一出口）

    /**
     * 生成 {@code /shanhai bh status} 的那一行。
     *
     * <p>格式固定、字段全部 {@code key=value}、<b>无空格值</b> ⇒ 一行、可 grep、可被测试逐字比对。
     *
     * @param level    1/2/3（内部编号 = 日志行里那个数）
     * @param reason   最近一次降级的原因（没降过级 = {@code none}）
     * @param mode     {@code auto|on|off}
     * @param skip     这一帧的状态（{@code drew_full} / {@code drew_quad} / {@code SKIP_*}）
     */
    public static String formatStatus(int level, String reason, String mode,
                                      boolean enabled, double scale, double strength, double disk,
                                      String skip, double[] center, double rsBlocks, double dist,
                                      double rectW, double rectH, double screenFraction,
                                      double tanHalfFov, boolean shaderpack) {
        final int tier = Math.max(0, Math.min(2, level - 1));
        final StringBuilder sb = new StringBuilder(320);
        sb.append(BlackHoleFallbackGuard.PREFIX)
                .append(" status fallback=").append(tier)
                .append(" level=").append(level)
                .append(" reason=").append(BlackHoleFallbackGuard.sanitize(reason))
                .append(" mode=").append(mode)
                .append(" enabled=").append(enabled)
                .append(" shaderpack=").append(shaderpack)
                .append(" skip=").append(skip)
                .append(" center=").append(BlackHoleDebugSpec.format(center))
                .append(" rs_blocks=").append(d3(rsBlocks))
                .append(" dist=").append(d2(dist))
                .append(" rect_px=").append(d0(rectW)).append('x').append(d0(rectH))
                .append(" screen_frac=").append(d4(screenFraction))
                .append(" tan_half_fov=").append(d4(tanHalfFov))
                .append(" scale=").append(d3(scale))
                .append(" strength=").append(d3(strength))
                .append(" disk=").append(d3(disk))
                .append(" draw=").append(drawName(skip));
        return sb.toString();
    }

    /** 把"这一帧的状态"翻成人类可读的 {@code draw=} 值。 */
    public static String drawName(String skip) {
        if (DREW_FULL.equals(skip)) {
            return "full";
        }
        if (DREW_QUAD.equals(skip)) {
            return "quad";
        }
        if (SKIP_DEAD.equals(skip)) {
            return "none";
        }
        return "none";
    }

    /** 渲染器读走"这一帧用的中心"（防御性拷贝）。 */
    public static double[] lastCenter() {
        final double[] c = lastCenter;
        return c == null ? null : new double[]{c[0], c[1], c[2]};
    }

    public static double lastRsBlocks() {
        return lastRsBlocks;
    }

    public static double lastDist() {
        return lastDist;
    }

    private static String d0(double v) {
        if (!Double.isFinite(v)) {
            return "nan";
        }
        return String.format(java.util.Locale.ROOT, "%.0f", v);
    }

    private static String d2(double v) {
        if (!Double.isFinite(v)) {
            return "nan";
        }
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    private static String d3(double v) {
        if (!Double.isFinite(v)) {
            return "nan";
        }
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }

    private static String d4(double v) {
        if (!Double.isFinite(v)) {
            return "nan";
        }
        return String.format(java.util.Locale.ROOT, "%.4f", v);
    }
}
