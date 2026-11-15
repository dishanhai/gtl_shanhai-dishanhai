package com.shanhai.common.text;

import org.gtlcore.gtlcore.utils.NumberUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;

/**
 * 山海重构 · <b>大数显示的唯一口径</b>（2026-11-17 用户点单）。
 *
 * <h2>用户原话（逐字）</h2>
 * <blockquote>「归墟好了，而且可以吃并行了，但是 <b>jade 没有显示发电功率</b>，<b>AR 面版也没显示</b>，
 * 而且 <b>AR 它这个能源仓好像到 long 上限了，这个改成大整数的科学计数法吧</b>」</blockquote>
 *
 * <h2>🔴 本类【不】自己写格式化 —— 直接委派上游那一套</h2>
 * 用户点名的参照物是「<b>无线能源监视器</b>能把大数显示成 {@code 19.8Y EU/t} / {@code 4.4E12A}」。
 * 那一族的实现是 gtlcore 的
 * {@code org.gtlcore.gtlcore.utils.NumberUtils#formatDouble(double)}：
 * <pre>
 *   UNITS = ["", "K", "M", "G", "T", "P", "E", "Z", "Y", "B", "N", "D"]   ← 每档 1000（javap 常量池实证）
 *   DecimalFormat("#.##")                                                  ← 最多两位小数、去尾零
 *   ⇒ 1.98e25 ⇒ {@code 19.8Y}；9.223e18 ⇒ {@code 9.22E}；1.2e6 ⇒ {@code 1.2M}
 * </pre>
 * <b>离线实测（JDK 25 + {@code libs/gtlcore-1.2.3.2-fix4.jar}，temp/bigfmt/Probe*.java）</b>：
 * {@code formatDouble(1.98e25) = "19.8Y"}、{@code formatDouble(9.223e18) = "9.22E"}、
 * {@code formatDouble(1e6) = "1M"} —— 与用户说的 {@code 19.8Y} 逐字对上。
 *
 * <h2>🔴 两套号位表并存（2026-11-19 起）—— 用哪一套看那一格要跟谁对齐</h2>
 * <ul>
 *   <li>{@link #compact(BigInteger)}：gtlcore 表 {@code ["" K M G T P E Z Y B N D]}。离线可验证
 *       （{@code temp/bigfmt/Probe*.java} 实跑），本工程绝大多数格子用它（池子余额 / 能源仓 / AR）。</li>
 *   <li>{@link #upstream(BigInteger)}：gtladditions 表 {@code ["" K M G T P E Z Y R Q e33 …]}。
 *       <b>图1 那行「产能 … EU/t」就是它印的</b>（用户 2026-11-19 点单「改成图1 那种」），
 *       归墟的产能行与 AR 那一格现在都走它。<b>它内部失败就自动退回 {@code compact}</b>（见下）。</li>
 * </ul>
 * <b>两表在 ≤1e24 逐字相同</b>（{@code 19.8Y} 两边一致），只在 {@code ≥1e27} 分叉
 * （{@code B/N/D} vs {@code R/Q/e33}）。归墟的产量 4e31 <b>正好落在 1e30 档</b> ⇒
 * 走 {@code upstream} 印 {@code 40.56Q}，走 {@code compact} 印 {@code 40.56N}。
 *
 * <h2>⛔ 为什么 {@link #upstream} 必须自带回落（而不是全工程都改用它）</h2>
 * gtladditions 那个<b>是 Kotlin {@code object}</b>：离线实测
 * {@code NoClassDefFoundError: kotlin/jvm/functions/Function0}（类初始化里有 {@code lazy}，
 * 而 gtladditions jar <b>没有 shade</b> kotlin，靠包里的前置）⇒ <b>本机没法在不启动游戏的情况下证明它印出什么</b>。
 * 本工程纪律是"没验过的不许写进判据"，所以：
 * ① 它只用在用户点名要对齐图1 的那两格；
 * ② 任何 {@link Throwable} 都退回 {@link #compact}（退化方向 = 后缀字母从 Q 变 N，<b>不会没字、不会崩</b>）；
 * ③ 回落时打一行限流 WARN（离线环境连 logger 都不在 ⇒ 那条日志自己也被包在 try/catch 里）。
 * <p>⚠️ 另一条历史边界已作废：本节旧版写过"归墟的读数到不了那一档"—— 4.06e31 恰恰落在 1e30 档，实测打脸。
 *
 * <h2>🔴 三个必须由本类兜住的坑（都是实测出来的，不是防御性编程）</h2>
 * <ol>
 *   <li><b>负数会印成整串数字</b>：{@code formatDouble(-1.98e25) = "-19800000000000000000000000"}
 *       （{@code Math.log10(负数) = NaN} ⇒ 进位档取 0）⇒ <b>必须先取绝对值、符号自己拼</b>；</li>
 *   <li><b>超过 double 上限（&gt;1.8e308）会印成乱码</b>（{@code formatDouble(∞)} 走进最后一个单位档）；
 *       ⇒ 非有限值走本类的科学计数兜底，<b>绝不写 {@code ∞} 冒充一个有限读数</b>；</li>
 *   <li><b>{@code BigInteger} 不能先降成 {@code long} 再格式化</b> —— 那正是本 bug 的根因
 *       （仓侧 {@code getEnergyStored()} 就是 {@code NumberUtils.getLongValue(池子)}，
 *       {@code >Long.MAX} 静默饱和成 {@code 9.22E18}）。本类只接收 {@link BigInteger}，
 *       降精只发生在<b>已经决定要印几位有效数字</b>之后。</li>
 * </ol>
 *
 * <p>⚠️ 本类<b>只管显示</b>：不参与任何配方 / 发电 / 并行判断。
 */
public final class ShanhaiBigNumberText {

    /** 读不到时统一印这个（本工程红线：宁可缺，不可假 —— 绝不拿 0 冒充读不到）。 */
    public static final String DASH = "--";

    /** 单位口径的参考实现类名（日志里印出来，出事时一眼知道"这个数是谁算的"）。 */
    public static final String REFERENCE = "org.gtlcore.gtlcore.utils.NumberUtils#formatDouble";

    private ShanhaiBigNumberText() {}

    /**
     * {@link BigInteger} → 紧凑量级（{@code 19.8Y} / {@code 9.22E} / {@code 1.2M} / {@code 0}）。
     *
     * @param value {@code null} = 读不到 ⇒ {@link #DASH}
     */
    @NotNull
    public static String compact(@Nullable BigInteger value) {
        if (value == null) {
            return DASH;
        }
        if (value.signum() == 0) {
            return "0";
        }
        final BigInteger abs = value.abs();
        final String body = compactAbs(abs);
        return value.signum() < 0 ? "-" + body : body;
    }

    /** 同 {@link #compact(BigInteger)}，但入参是 long；{@code <0} = 读不到 ⇒ {@link #DASH}。 */
    @NotNull
    public static String compactLong(long value) {
        return value < 0L ? DASH : compact(BigInteger.valueOf(value));
    }

    /** {@link #upstream(BigInteger)} 退回本类时只提示一次（{@code false} = 还没退过）。 */
    private static boolean shanhai$upstreamFallbackWarned;

    /**
     * <b>图1 / 无线能源监视器那个口径</b>：{@link BigInteger} → 紧凑量级，<b>号位表用 gtladditions 那张</b>。
     *
     * <p>🔴 与 {@link #compact(BigInteger)} 的唯一差别就在 {@code ≥1e27} 那几档：
     * <pre>
     *   本类(gtlcore) : … Y(1e24) B(1e27) N(1e30) D(1e33)
     *   gtladditions  : … Y(1e24) R(1e27) Q(1e30) e33(1e33) e36 …
     * </pre>
     * ⇒ 1e24 以下两者逐字相同（图1 的 {@code 19.81Y}、图2 的 {@code 40.56N} 各自都对）；
     * 归墟的产量 4e31 落在 1e30 档 ⇒ 这一栏会印 {@code Q}（与图1 那台机器同一个函数）。
     *
     * <p>⚠️ 它是 gtladditions 的 Kotlin {@code object}（{@code formatDouble} 是 {@code @JvmStatic}）：
     * 类初始化要 kotlin stdlib（jar 里没有 shade，靠包里的 kotlin 前置）。<b>绝不让显示层被它带崩</b>
     * ⇒ 任何 {@link Throwable}（含 {@code NoClassDefFoundError}）都退回 {@link #compact(BigInteger)}，
     * 并打一行限流 WARN；<b>离线探针</b>里连 logger 都可能不在 ⇒ 那条日志本身也包一层 try/catch，
     * 保证本方法在「没有 mojang 日志、没有 kotlin」的离线环境里照样返回字符串。
     *
     * @param value {@code null} = 读不到 ⇒ {@link #DASH}
     */
    @NotNull
    public static String upstream(@Nullable BigInteger value) {
        if (value == null || value.signum() == 0) {
            return compact(value);                          // 0 / 读不到 与主口径完全一样
        }
        try {
            final double d = value.abs().doubleValue();
            if (!Double.isFinite(d)) {
                return compact(value);                      // >1.8e308 的病态值：别让上游印 ∞ 冒充有限读数
            }
            final String body = com.gtladd.gtladditions.utils.CommonUtils.formatDouble(d);
            return value.signum() < 0 ? "-" + body : body;
        } catch (Throwable t) {
            if (!shanhai$upstreamFallbackWarned) {
                shanhai$upstreamFallbackWarned = true;
                try {
                    // 只在真能拿到 logger 时打（离线探针里这一句会自己抛 ⇒ 被下面吞掉）
                    com.mojang.logging.LogUtils.getLogger().warn(
                            "[SHANHAI-JADE] 大数显示退回 compact（gtladditions CommonUtils.formatDouble 调不到"
                                    + " ⇒ 1e30 档印 N 而不是 Q；这一行只打一次）：{}", String.valueOf(t));
                } catch (Throwable ignored) {
                    // 离线环境：静默退回即可（返回值本身已经正确到 1e24 档）
                }
            }
            return compact(value);
        }
    }

    /**
     * 功率一格：{@code 19.8Y EU/t}；读不到 ⇒ {@code -- EU/t}（<b>不省略单位</b>，
     * 否则"没数"和"没单位"分不开）。
     */
    @NotNull
    public static String euPerTick(@Nullable BigInteger value) {
        return compact(value) + " EU/t";
    }

    /**
     * 容量一格：{@code ∞}（上游的无限容量哨兵）/ 数值 / {@link #DASH}。
     *
     * @param capacity   容量读数
     * @param infinite   {@code true} = 上游报的是 {@code Long.MAX_VALUE} 这个<b>"无限"哨兵</b>
     *                   （创造模式能源仓 / 无线电网终端的容器都这么报，见
     *                   {@code ParallelPowerBudget.knownKindByName} 那两类）——
     *                   这时<b>不能</b>把它印成 {@code 9.22E}，那会让人以为"仓正好装满 9.22e18"
     */
    @NotNull
    public static String capacity(long capacity, boolean infinite) {
        if (infinite) {
            return "∞";
        }
        return compactLong(capacity);
    }

    /**
     * 绝对值 → 紧凑量级（<b>符号由调用方处理</b>，因为参考实现对负数会退化成整串数字）。
     *
     * <p>{@code abs} 必须 {@code > 0}（0 由 {@link #compact} 提前返回）。
     */
    @NotNull
    private static String compactAbs(@NotNull BigInteger abs) {
        final double d = abs.doubleValue();
        if (!Double.isFinite(d)) {
            // 只可能是病态数据（池子被写成 >1.8e308）。给"首位.两位 e 指数"的科学计数，
            // 绝不印 ∞（∞ 是"无限"的语义，与"很大"不是一件事）。
            return scientific(abs);
        }
        try {
            return NumberUtils.formatDouble(d);
        } catch (Throwable t) {
            // NumberUtils 自己炸了（上游换了实现）⇒ 退到同一个口径的本地最小实现，绝不把面板搞崩
            return scientific(abs);
        }
    }

    /**
     * 科学计数兜底：{@code 1.98e19} 形式（<b>只在前两条路都不可用时才走到</b>）。
     *
     * <p>指数 = 位数 − 1（十进制）；尾数取前 3 位有效数字并去掉尾零 ⇒ 与本类主口径的"2 位小数"同档。
     */
    @NotNull
    private static String scientific(@NotNull BigInteger abs) {
        final String digits = abs.toString();
        final int exp = digits.length() - 1;
        if (exp < 3) {
            return digits;                                  // 小到不需要科学计数
        }
        final int keep = Math.min(3, digits.length());
        String mantissa = digits.charAt(0) + "." + digits.substring(1, keep);
        while (mantissa.endsWith("0")) {
            mantissa = mantissa.substring(0, mantissa.length() - 1);
        }
        if (mantissa.endsWith(".")) {
            mantissa = mantissa.substring(0, mantissa.length() - 1);
        }
        return mantissa + "e" + exp;
    }

    /**
     * 解码 {@code BigInteger.toByteArray()} 那条同步通道（LDLib syncdata 没有 BigInteger，
     * 只有 {@code byte[]}；见 {@code GuixuVisionCoreMachine#hostGenPerTickBytes} 的同款用法）。
     *
     * @return {@code null} = 空数组（从没同步过 / 读不到）
     */
    @Nullable
    public static BigInteger decode(@Nullable byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try {
            return new BigInteger(bytes);
        } catch (Throwable t) {
            return null;
        }
    }
}
