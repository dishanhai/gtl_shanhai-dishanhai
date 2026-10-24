package com.dishanhai.gt_shanhai.client.renderer.blackhole;

/**
 * 山海重构 · 黑洞渲染件的 <b>运行期可调参数</b>（纯逻辑：零 MC / 零 Forge / 零 GL）。
 *
 * <h2>为什么是"运行期覆盖"而不是"再加一堆 toml 键"</h2>
 * 用户裁决原文：*"视觉参数要可调，但**不进 toml**"*、*"全部**当场生效、不落盘**"*。
 * 所以这里持有的是<b>内存里的一份覆盖值</b>：
 * <ul>
 *   <li>启动时由 {@link #initFromConfig} 灌一次 toml 的默认值（{@code [debug]} 段的 4 个键）；</li>
 *   <li>之后 {@code /shanhai bh scale|strength|disk|center|on|off|lens} 直接改这份值；</li>
 *   <li>渲染器<b>每帧只读字段</b>，不重新解析任何字符串（用户建议、也是本类的写法）。</li>
 * </ul>
 *
 * <h2>🔴 线程约定</h2>
 * 命令在<b>客户端主线程</b>执行、渲染也在<b>客户端主线程</b>（MC 的 render thread == main thread），
 * 所以本来就不并发。但字段仍全部 {@code volatile}：万一将来有人从别的线程写，
 * 最坏也是"晚一帧看到"，不会读到撕裂的 {@code double}。
 *
 * <h2>范围夹取</h2>
 * 每个 setter 都<b>夹取并返回实际生效值</b>（命令回话里报的就是这个值，不是用户敲的那个）
 * —— 这样"我敲了 99 为什么没变"在聊天栏里当场就有答案，不用去翻代码。
 */
public final class BlackHoleTuning {

    /** {@code /shanhai bh lens} 的三档。 */
    public enum LensMode {
        /** 默认：光影包生效时降级到二级（安全做法）。 */
        AUTO,
        /** 强制走一级（光影包生效时也试；日志会写明"用户强制"）。 */
        ON,
        /** 强制二级（只看静态黑盘+光环，用来做 A/B 对照）。 */
        OFF;

        public String id() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        /** 解析 {@code auto|on|off}（大小写不敏感）；不认识返回 {@code null}。 */
        public static LensMode parse(String s) {
            if (s == null) {
                return null;
            }
            final String t = s.trim().toLowerCase(java.util.Locale.ROOT);
            for (LensMode m : values()) {
                if (m.id().equals(t)) {
                    return m;
                }
            }
            return null;
        }
    }

    // ---- 夹取范围（命令参数与 setter 用同一套，聊天栏回话里会打印）----
    public static final double SCALE_MIN = 0.05D;
    public static final double SCALE_MAX = 8.0D;
    public static final double STRENGTH_MIN = 0.0D;
    public static final double STRENGTH_MAX = 2.0D;
    public static final double DISK_MIN = 0.0D;
    public static final double DISK_MAX = 2.0D;

    // ---- 默认值（由 toml 灌入；只灌一次）----
    private static volatile boolean initialized;
    private static volatile boolean defaultEnabled = true;
    private static volatile double defaultScale = 1.0D;
    private static volatile double defaultStrength = 1.0D;

    // ---- 运行期值 ----
    private static volatile Boolean enabledOverride;   // null = 跟 toml
    private static volatile double[] centerOverride;   // null = 跟 toml（已含 +0.5 补正）
    private static volatile double scale = 1.0D;
    private static volatile double strength = 1.0D;
    private static volatile double disk = 1.0D;
    private static volatile LensMode lensMode = LensMode.AUTO;

    /** {@code /shanhai bh test} 立的旗：下一次"首次加载 shader"时故意失败。 */
    private static volatile boolean pendingSimulatedFailure;

    private BlackHoleTuning() {}

    // ------------------------------------------------------------------ 初始化 / 默认值

    /**
     * 用 toml 的默认值灌一次（只生效一次；之后再调不会覆盖命令改过的值）。
     *
     * <p>⚠️ 必须"只灌一次"：否则"先敲命令、再进世界"会被第一帧的初始化冲掉。
     */
    public static void initFromConfig(boolean enabled, double scaleDefault, double strengthDefault) {
        if (initialized) {
            return;
        }
        initialized = true;
        defaultEnabled = enabled;
        defaultScale = clamp(scaleDefault, SCALE_MIN, SCALE_MAX);
        defaultStrength = clamp(strengthDefault, STRENGTH_MIN, STRENGTH_MAX);
        scale = defaultScale;
        strength = defaultStrength;
    }

    public static boolean isInitialized() {
        return initialized;
    }

    public static double defaultScale() {
        return defaultScale;
    }

    public static double defaultStrength() {
        return defaultStrength;
    }

    // ------------------------------------------------------------------ 读（渲染器每帧调）

    /** 是否画（toml 的 {@code blackhole_enabled} 会被 {@code /shanhai bh on|off} 覆盖）。 */
    public static boolean enabled() {
        final Boolean o = enabledOverride;
        return o != null ? o : defaultEnabled;
    }

    /** 运行期中心覆盖（{@code null} = 用 toml 的 {@code blackhole_center}）。 */
    public static double[] centerOverride() {
        final double[] c = centerOverride;
        return c == null ? null : c.clone();
    }

    public static boolean hasCenterOverride() {
        return centerOverride != null;
    }

    public static double scale() {
        return scale;
    }

    public static double strength() {
        return strength;
    }

    public static double disk() {
        return disk;
    }

    public static LensMode lensMode() {
        return lensMode;
    }

    // ------------------------------------------------------------------ 写（命令调）

    /** @return 实际生效的开关状态 */
    public static boolean setEnabled(boolean value) {
        enabledOverride = value;
        return value;
    }

    /** 清除运行期开关覆盖 ⇒ 回 toml。 */
    public static void clearEnabledOverride() {
        enabledOverride = null;
    }

    public static boolean hasEnabledOverride() {
        return enabledOverride != null;
    }

    /** @param center 已经是"最终坐标"（调用方负责 {@code ~} 与 +0.5）；{@code null} = 清除覆盖 */
    public static void setCenter(double[] center) {
        centerOverride = (center == null) ? null : new double[]{center[0], center[1], center[2]};
    }

    /** @return 实际生效的倍率（已夹取） */
    public static double setScale(double value) {
        scale = clamp(value, SCALE_MIN, SCALE_MAX);
        return scale;
    }

    /** @return 实际生效的偏折强度（已夹取） */
    public static double setStrength(double value) {
        strength = clamp(value, STRENGTH_MIN, STRENGTH_MAX);
        return strength;
    }

    /** @return 实际生效的盘亮度系数（已夹取） */
    public static double setDisk(double value) {
        disk = clamp(value, DISK_MIN, DISK_MAX);
        return disk;
    }

    public static LensMode setLensMode(LensMode mode) {
        lensMode = (mode == null) ? LensMode.AUTO : mode;
        return lensMode;
    }

    // ------------------------------------------------------------------ 一次性动作（test / reload）

    /** {@code /shanhai bh test}：立旗 + 把渲染器的 shader 缓存作废 + 兜底状态复位。 */
    public static void requestSimulatedFailure() {
        pendingSimulatedFailure = true;
    }

    /** 渲染器消费这个旗：拿到 {@code true} 就"假装加载失败"跑一遍兜底路径。 */
    public static boolean consumeSimulatedFailure() {
        if (!pendingSimulatedFailure) {
            return false;
        }
        pendingSimulatedFailure = false;
        return true;
    }

    public static boolean isSimulatedFailurePending() {
        return pendingSimulatedFailure;
    }

    // ------------------------------------------------------------------ 读数

    /** 一行读数（命令回话与日志共用）。 */
    public static String describe() {
        final double[] c = centerOverride;
        return "enabled=" + enabled()
                + (enabledOverride == null ? "(toml)" : "(cmd)")
                + " center=" + (c == null ? "toml" : BlackHoleDebugSpec.format(c))
                + " scale=" + fmt(scale)
                + " strength=" + fmt(strength)
                + " disk=" + fmt(disk)
                + " lens=" + lensMode.id()
                + " sim_pending=" + pendingSimulatedFailure;
    }

    /** 给 {@code status} 用：范围说明（让"为什么 99 变成 2"在聊天栏里能自答）。 */
    public static String ranges() {
        return "scale∈[" + fmt(SCALE_MIN) + "," + fmt(SCALE_MAX) + "]"
                + " strength∈[" + fmt(STRENGTH_MIN) + "," + fmt(STRENGTH_MAX) + "]"
                + " disk∈[" + fmt(DISK_MIN) + "," + fmt(DISK_MAX) + "]";
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }

    private static double clamp(double v, double lo, double hi) {
        if (!Double.isFinite(v)) {
            return lo;
        }
        return Math.max(lo, Math.min(hi, v));
    }
}
