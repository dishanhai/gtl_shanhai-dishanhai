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

    /**
     * {@code /shanhai bh insidedebug} 的三档：<b>洞内那一条链的隔离诊断开关</b>。
     *
     * <h2>为什么需要它（2026-10-25）</h2>
     * 上一轮"一进洞就不渲染"只能靠猜：日志说"画了"，屏幕上没有，而<b>所有异常路径都已被 try/catch 吞掉</b>。
     * 有了这个开关就能把两种完全不同的病因分开：
     * <ul>
     *   <li>{@link #SOLID}：不采样、不看参数，直接画一整屏<b>纯亮绿</b> ⇒
     *       看到绿 = 四边形提交这一路（RenderType/深度/FBO/注入点/着色器）是通的，
     *       黑屏只能在<b>采样</b>那一段；看不到绿 = 与采样无关（没提交 / 被后面的 pass 盖掉）；</li>
     *   <li>{@link #SAMPLE}：正常采样 —— <b>默认值，就是改前的行为</b>；</li>
     *   <li>{@link #OFF}：关掉调试色块，回到现行为（与 {@link #SAMPLE} 等价 ——
     *       任务书口径原文「{@code off} = 现行为」）。</li>
     * </ul>
     * <p>🔴 <b>不改行为</b>：默认 {@link #SAMPLE}，与改前逐像素一致；开关只存在于运行期内存
     * （与 {@code window}/{@code streak}/{@code lens} 同一套 {@link BlackHoleTuning} 机制，
     * 按用户裁决「视觉参数不进 toml、不落盘」）—— 不写任何配置文件。
     */
    public enum InsideDebug {
        /** 默认：正常采样（= 改前行为）。 */
        SAMPLE,
        /** 不采样，直接纯亮绿全屏（隔离"提交"与"采样"）。 */
        SOLID,
        /** 关掉调试色块 ⇒ 回到现行为（与 {@link #SAMPLE} 等价）。 */
        OFF;

        public String id() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        /** 解析 {@code solid|sample|off}（大小写不敏感）；不认识返回 {@code null}。 */
        public static InsideDebug parse(String s) {
            if (s == null) {
                return null;
            }
            final String t = s.trim().toLowerCase(java.util.Locale.ROOT);
            for (InsideDebug m : values()) {
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

    /**
     * 洞内诊断隔离位（{@code /shanhai bh insidedebug solid|sample|off}）。
     *
     * <p>默认 {@link InsideDebug#SAMPLE} = <b>改前的行为</b>；这是唯一一个"改了也不影响画面"的开关，
     * 只在排查"洞内不渲染"时用。见 {@link InsideDebug} 的三档说明。
     */
    private static volatile InsideDebug insideDebug = InsideDebug.SAMPLE;

    /**
     * <b>采样侧深度门</b>的 A/B 开关（{@code /shanhai bh depthgate on|off|status}）。
     *
     * <h2>为什么要有它（2026-10-25）</h2>
     * 用户报「引力透镜现在连黑洞后面的方块也不影响了」——采样门把整屏都拦住了。
     * 有了这个开关，进游戏<b>5 秒内</b>就能判定病因在哪一侧：
     * <ul>
     *   <li>{@code off}（{@code uDepthGate=0}）⇒ 门整段跳过，行为 = 2026-10-25 之前
     *       （背景全弯折、前景颜色会被搬动）。<b>此时画面弯了</b> ⇒ 病在门/深度数据这一侧；</li>
     *   <li>{@code off} 时画面<b>仍然不弯</b> ⇒ 与门无关（弯折强度的 mask / 拷贝 / 着色器别处），
     *       下一轮不用再查深度。</li>
     * </ul>
     * <p>默认 {@code true}（门开）。与 {@code window}/{@code streak}/{@code insidedebug} 同一套机制：
     * 只在内存里，<b>不落盘、不进 toml</b>（用户裁决「视觉参数全部当场生效、不落盘」）。
     */
    private static volatile boolean depthGate = true;

    /**
     * 洞内「窗口半径」的<b>倍率</b>（{@code /shanhai bh window <mul>}）。
     *
     * <p>为什么不直接给像素/半径：窗口半径本身随"进洞多深"变化
     * （{@link BlackHoleInsideSpec#windowRadius}：0.92 → 0.15），
     * 给倍率才能<b>保住那条随深度收缩的曲线</b>，只整体放大/缩小。
     * 默认 1.0；范围 {@link BlackHoleInsideSpec#WINDOW_MUL_MIN}..{@link BlackHoleInsideSpec#WINDOW_MUL_MAX}。
     */
    private static volatile double insideWindowMul = 1.0D;

    /**
     * 洞内「径向拖影强度」的<b>覆盖值</b>（{@code /shanhai bh streak <0..1>}）。
     *
     * <p>{@code < 0} = 不覆盖（用 {@link BlackHoleInsideSpec#streakStrength} 随深度自动算），
     * 默认就是 -1；≥0 时固定成这个值（做 A/B 对照用：0 = 完全无拖影）。
     */
    private static volatile double insideStreakOverride = -1.0D;

    /** {@code /shanhai bh test} 立的旗：下一次"首次加载 shader"时故意失败。 */
    private static volatile boolean pendingSimulatedFailure;

    // ------------------------------------------------------------------ 主机接管（归墟视界枢核）

    /**
     * <b>主机接管</b>的黑洞半径（<b>格</b>；{@code null} = 没有主机接管）。
     *
     * <h2>为什么是"另一个覆盖"而不是直接改 {@link #scale}</h2>
     * 机器字段的语义是"黑洞表面半径 = N 格"，而 {@code scale} 是"倍率"——
     * 两者的锚点不同（半径 = {@code B_CRIT × RS_BLOCKS × scale}）。若把机器半径反算成 scale 写进
     * {@link #scale}，就会<b>永久改掉</b>玩家用 {@code /shanhai bh scale} 设的值（用户明确要求不许）。
     * 所以这里单独存一份"主机接管"，读取口 {@code BlackHoleLensRenderer.rsBlocks()} 优先用它；
     * 主机消失时由渲染器 {@code clearHostRadiusBlocks()} 还原，命令设的值原样回来。
     *
     * <p>值域 1.0..12.0 格（上限来自 {@code HostFxState.HOLE_MAX_BLOCKS}，
     * 那又是被 {@code HostFxBlackHole.surfaceRadius()} 的 {@code Math.min(r, 12.0F)} 钉住的）。
     */
    private static volatile Float hostRadiusBlocks;

    /**
     * <b>主机接管</b>的引力透镜开关（{@code null} = 没有主机接管）。
     *
     * <p>语义见 {@link #effectiveLensMode()}：主机在场时它<b>压过</b>
     * {@code /shanhai bh lens auto|on|off}（任务书口径：「主机在场时以机器字段为准」）；
     * 主机不在时命令照旧生效（无主机的独立黑洞仍可用 {@code /shanhai bh lens off} 做 A/B）。
     */
    private static volatile Boolean hostLensEnabled;

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

    /**
     * <b>这一帧真正生效</b>的透镜档位：<b>主机接管优先</b>，否则跟 {@code /shanhai bh lens} / 默认。
     *
     * <p>映射：机器"开" ⇒ {@link LensMode#ON}，机器"关" ⇒ {@link LensMode#OFF}。
     * <p>为什么不用 {@code AUTO} 表示"开"：{@code AUTO} 与 {@code ON} 在渲染行为上等价
     * （两者都走一级真透镜，见 {@code BlackHoleLensRenderer} 的 {@code allowFull}），
     * 差别只在日志里的 {@code lens_mode mode=} 字样；机器字段是玩家明确按下的"开"，
     * 打成 {@code ON} 才不会让日志读起来像"系统自己决定的"。
     */
    public static LensMode effectiveLensMode() {
        final Boolean host = hostLensEnabled;
        if (host == null) {
            return lensMode();
        }
        return host ? LensMode.ON : LensMode.OFF;
    }

    /** 有没有主机在接管黑洞半径（{@code false} = 用 {@code RS_BLOCKS × scale}，即命令/默认那套）。 */
    public static boolean hasHostRadiusBlocks() {
        return hostRadiusBlocks != null;
    }

    /** 主机接管半径（格）；<b>没有接管时返回 {@link Float#NaN}</b> —— 调用方必须先判 {@link #hasHostRadiusBlocks()}。 */
    public static float hostRadiusBlocks() {
        final Float r = hostRadiusBlocks;
        return r == null ? Float.NaN : r;
    }

    /** 有没有主机在接管透镜开关。 */
    public static boolean hasHostLensEnabled() {
        return hostLensEnabled != null;
    }

    /** 主机接管的透镜开关（{@code null} = 没有接管）；读的是<b>原始三态</b>，真正生效值用 {@link #effectiveLensMode()}。 */
    public static Boolean hostLensEnabled() {
        return hostLensEnabled;
    }

    /** 洞内窗口半径倍率（默认 1.0）。 */
    public static double insideWindowMul() {
        return insideWindowMul;
    }

    /** 洞内拖影强度覆盖（{@code < 0} = 自动按深度算）。 */
    public static double insideStreakOverride() {
        return insideStreakOverride;
    }

    /** 洞内诊断隔离位（默认 {@link InsideDebug#SAMPLE} = 现行为；{@code /shanhai bh insidedebug}）。 */
    public static InsideDebug insideDebug() {
        return insideDebug;
    }

    /**
     * 采样侧深度门是否启用（{@code false} = {@code uDepthGate=0}，退回 2026-10-25 之前的"全都弯折"）。
     *
     * <p>渲染器每帧读它；{@code /shanhai bh depthgate off} 立刻生效（下一帧），不需要重载资源。
     */
    public static boolean depthGate() {
        return depthGate;
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

    /**
     * 写入"主机接管半径"（格）。由 {@code GuixuHostFxRenderer} 每帧在成型闸门之后调，
     * 值来自 {@code HostFxState.holeRadiusBlocks()}（已钳到 1.0..12.0）。
     *
     * <p>⚠️ 本方法<b>不</b>动 {@link #scale} —— 命令设的倍率原样保留，主机一走就回到它。
     */
    public static void setHostRadiusBlocks(float blocks) {
        hostRadiusBlocks = blocks;
    }

    /** 撤销主机接管半径（回到 {@code RS_BLOCKS × scale}，即命令/默认那套）。 */
    public static void clearHostRadiusBlocks() {
        hostRadiusBlocks = null;
    }

    /** 写入"主机接管透镜开关"（主机在场时以它为准，见 {@link #effectiveLensMode()}）。 */
    public static void setHostLensEnabled(boolean enabled) {
        hostLensEnabled = enabled;
    }

    /** 撤销主机接管透镜开关（{@code /shanhai bh lens auto|on|off} 立刻重新生效）。 */
    public static void clearHostLensEnabled() {
        hostLensEnabled = null;
    }

    /**
     * 洞内窗口半径倍率。
     *
     * @return 实际生效值（已夹取）
     */
    public static double setInsideWindowMul(double value) {
        insideWindowMul = clamp(value, BlackHoleInsideSpec.WINDOW_MUL_MIN,
                BlackHoleInsideSpec.WINDOW_MUL_MAX);
        return insideWindowMul;
    }

    /**
     * 洞内拖影强度覆盖。
     *
     * @param value {@code < 0} = 恢复自动（跟着深度走）；否则夹到 0..1
     * @return 实际生效值（{@code < 0} 表示"自动"）
     */
    public static double setInsideStreakOverride(double value) {
        insideStreakOverride = value < 0.0D ? -1.0D : clamp(value, 0.0D, 1.0D);
        return insideStreakOverride;
    }

    /**
     * 洞内诊断隔离位。
     *
     * @param mode {@code null} = 回默认（{@link InsideDebug#SAMPLE}）
     * @return 实际生效值
     */
    public static InsideDebug setInsideDebug(InsideDebug mode) {
        insideDebug = (mode == null) ? InsideDebug.SAMPLE : mode;
        return insideDebug;
    }

    /**
     * 采样侧深度门的 A/B 开关。
     *
     * @return 实际生效值
     */
    public static boolean setDepthGate(boolean value) {
        depthGate = value;
        return depthGate;
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
                + " insideWindowMul=" + fmt(insideWindowMul)
                + " insideStreak=" + (insideStreakOverride < 0.0D ? "auto" : fmt(insideStreakOverride))
                + " insideDebug=" + insideDebug.id()
                + " depthGate=" + (depthGate ? "on" : "off")
                + " sim_pending=" + pendingSimulatedFailure
                // 🔴 2026-10-25 第二十改：主机接管（归墟视界枢核）—— 这两项非 none 时，
                //    scale 与 lens 命令对"主机那颗黑洞"不生效（机器字段优先，见 effectiveLensMode）
                + " hostRadius=" + (hostRadiusBlocks == null ? "none" : fmt(hostRadiusBlocks) + "格")
                + " hostLens=" + (hostLensEnabled == null ? "none" : (hostLensEnabled ? "on" : "off"));
    }

    /** 给 {@code status} 用：范围说明（让"为什么 99 变成 2"在聊天栏里能自答）。 */
    public static String ranges() {
        return "scale∈[" + fmt(SCALE_MIN) + "," + fmt(SCALE_MAX) + "]"
                + " strength∈[" + fmt(STRENGTH_MIN) + "," + fmt(STRENGTH_MAX) + "]"
                + " disk∈[" + fmt(DISK_MIN) + "," + fmt(DISK_MAX) + "]"
                + " window∈[" + fmt(BlackHoleInsideSpec.WINDOW_MUL_MIN) + ","
                + fmt(BlackHoleInsideSpec.WINDOW_MUL_MAX) + "]"
                + " streak∈[0,1]（<0 = 自动）";
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
