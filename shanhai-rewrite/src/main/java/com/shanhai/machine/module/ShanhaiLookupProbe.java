package com.shanhai.machine.module;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * <b>【「收集候选」那一段的只读计时器】—— 纯逻辑核（零 Minecraft / GT 依赖 ⇒ 可离线 javac + 裸 JVM 跑探针）。</b>
 *
 * <h2>一、它只回答一个问题</h2>
 * {@code PrimordialModuleRecipeLogic#lookupRecipeSet()} 那一段（含它调的
 * {@code shanhai$lookupCrossTypeSet}）到底占多少 tick 时间 ——
 * 用来决定「第 6 步：候选类型 37 → 2」值不值得做。
 *
 * <h2>二、🔴 零语义改动（四条，缺一条这条探针就不该存在）</h2>
 * <ol>
 *   <li><b>不改返回值 / 不改顺序 / 不加缓存</b>：调用方拿到的是
 *       {@code shanhai$lookupRecipeSetInner()} 的<b>原对象</b>，本类只读它的 {@code size()}；</li>
 *   <li><b>只记汇总，不逐次打日志</b>：每 {@link #WINDOW_TICKS} tick 最多落<b>一行</b>；</li>
 *   <li><b>可一键关</b>：{@link #DISABLE_ENV} 为真 ⇒ 调用方走「完全不计时」的那条早退分支
 *       ⇒ <b>一行都不打</b>（负对照）；</li>
 *   <li><b>读不到的写 {@code n/a}，绝不瞎估</b>：{@code share} 的分母拿不到（例如两次调用之间
 *       机器闲置了一座岛）就写 {@code share=n/a(gappy)}，而不是拿 20 TPS 去推。</li>
 * </ol>
 *
 * <h2>三、{@code share} 的口径（这是唯一一个"想当然就会错"的量）</h2>
 * <pre>
 *   share = 本窗口内 lookupRecipeSet 的总耗时 ÷ 本窗口内【实测墙钟】跨度
 *   墙钟跨度 = 窗口内第一次进入 lookupRecipeSet 的 nanoTime → 最后一次退出的 nanoTime
 * </pre>
 * ⇒ 它<b>不是</b>「÷ 200×50ms」那种估算（那等于假设服务端恒 20 TPS，是瞎估）。
 * <p>⚠️ 两种已知偏差，都写在读数里、不藏：
 * <ul>
 *   <li><b>窗口内有闲置</b>（区块卸载 / 机器没被问）⇒ 墙钟被拉长 ⇒ {@code share} <b>偏小</b>。
 *       检测方式：相邻两次调用间隔 &gt; {@link #GAP_NANOS} 即置 {@code gappy} ⇒ 该窗口
 *       {@code share} 打成 {@code n/a(gappy)}（宁可不给，也不给一个偏小的假数）；</li>
 *   <li><b>整窗只被问过一次</b>（跨度≈0）⇒ {@code share=n/a}。</li>
 * </ul>
 * 同时无条件给出 {@code window_ms} 与 {@code tick_ms}（实测），让读的人自己复核。
 */
public final class ShanhaiLookupProbe {

    /** 回退位：{@code 1/true/yes/on} ⇒ 本探针整体不生效（一行都不打）。 */
    public static final String DISABLE_ENV = "SHANHAI_DISABLE_LOOKUP_PROBE";

    /** 汇总窗口（tick）。200 tick = 10 秒 @20TPS，够稳、又不至于半天看不到一行。 */
    public static final int WINDOW_TICKS = 200;

    /** 相邻两次调用间隔超过这个值（纳秒）就认为窗口内有闲置 ⇒ 本窗口 {@code share} 作废。 */
    public static final long GAP_NANOS = 1_000_000_000L;

    /** 窗口读数（不可变；{@link #format(String)} 是纯函数 ⇒ 可离线断言）。 */
    public static final class Window {

        public final long ticks;
        public final long calls;
        public final int types;
        public final long candidatesTotal;
        public final long minNanos;
        public final long maxNanos;
        public final long totalNanos;
        public final long windowNanos;
        public final boolean gappy;

        Window(long ticks, long calls, int types, long candidatesTotal,
               long minNanos, long maxNanos, long totalNanos, long windowNanos, boolean gappy) {
            this.ticks = ticks;
            this.calls = calls;
            this.types = types;
            this.candidatesTotal = candidatesTotal;
            this.minNanos = minNanos;
            this.maxNanos = maxNanos;
            this.totalNanos = totalNanos;
            this.windowNanos = windowNanos;
            this.gappy = gappy;
        }

        /** 平均候选条数（一位小数）。 */
        public double candidatesAvg() {
            return this.calls <= 0L ? 0.0 : round1((double) this.candidatesTotal / (double) this.calls);
        }

        /** 单次耗时最小值（微秒）。 */
        public long minUs() {
            return this.minNanos / 1000L;
        }

        /** 单次耗时平均值（微秒）。 */
        public long avgUs() {
            return this.calls <= 0L ? 0L : this.totalNanos / this.calls / 1000L;
        }

        /** 单次耗时最大值（微秒）。 */
        public long maxUs() {
            return this.maxNanos / 1000L;
        }

        /** 窗口内这一段的总耗时（毫秒）。 */
        public double totalMs() {
            return round2((double) this.totalNanos / 1_000_000.0);
        }

        /** 窗口实测墙钟跨度（毫秒）。 */
        public double windowMs() {
            return round2((double) this.windowNanos / 1_000_000.0);
        }

        /** 实测平均每 tick 墙钟（毫秒）；跨度算不出来 ⇒ {@code -1}。 */
        public double tickMs() {
            if (this.ticks <= 0L || this.windowNanos <= 0L) {
                return -1.0;
            }
            return round2((double) this.windowNanos / 1_000_000.0 / (double) this.ticks);
        }

        /** {@code share}：这一段占总墙钟的比例（0–100，两位小数）；拿不到 ⇒ {@code -1}。 */
        public double sharePercent() {
            if (this.gappy || this.windowNanos <= 0L) {
                return -1.0;
            }
            return round2(100.0 * (double) this.totalNanos / (double) this.windowNanos);
        }

        /**
         * 落盘那一行（<b>纯函数</b>；离线探针断言的就是它）。
         *
         * <pre>
         * [SHANHAI-LOOKUP] machine=… ticks=… calls=… types=… candidates=… cost_us=min/avg/max
         *                  total_ms=… share=…|n/a window_ms=… tick_ms=…
         * </pre>
         */
        public @NotNull String format(@Nullable String pos) {
            final double share = sharePercent();
            final double tickMs = tickMs();
            return "machine=" + (pos == null ? "?" : pos)
                    + " ticks=" + this.ticks
                    + " calls=" + this.calls
                    + " types=" + this.types
                    + " candidates=" + fmt1(candidatesAvg())
                    + " cost_us=" + minUs() + "/" + avgUs() + "/" + maxUs()
                    + " total_ms=" + fmt2(totalMs())
                    + " share=" + (share < 0.0 ? (this.gappy ? "n/a(gappy)" : "n/a") : (fmt2(share) + "%"))
                    + " window_ms=" + fmt2(windowMs())
                    + " tick_ms=" + (tickMs < 0.0 ? "n/a" : fmt2(tickMs));
        }
    }

    private boolean started;
    private long windowStartTick = Long.MIN_VALUE;
    private long windowStartNanos;
    private long lastNanos;
    private long calls;
    private long candidatesTotal;
    private long minNanos = Long.MAX_VALUE;
    private long maxNanos;
    private long totalNanos;
    private int types;
    private boolean gappy;
    private boolean tickKnown = true;

    /** 回退位是否打开（每次现读、不缓存 ⇒ 运行期也能改；与类型闸 / 预检闸同风格）。 */
    public static boolean disabled() {
        try {
            if (isTruthy(System.getProperty(DISABLE_ENV))) {
                return true;
            }
            return isTruthy(System.getenv(DISABLE_ENV));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isTruthy(String raw) {
        if (raw == null) {
            return false;
        }
        final String v = raw.trim();
        return v.equals("1") || v.equalsIgnoreCase("true") || v.equalsIgnoreCase("yes") || v.equalsIgnoreCase("on");
    }

    /**
     * 记一次调用。
     *
     * @param costNanos   这一次 {@code lookupRecipeSet} 的耗时（{@code System.nanoTime()} 差）
     * @param candidates  这一次返回的候选条数（{@code -1} = 拿不到，不计入均值）
     * @param types       这一次实际遍历的类型数（{@code -1} = 拿不到）
     * @param tick        世界时间 tick（{@code Long.MIN_VALUE} = 读不到 ⇒ 本窗口不按 tick 关门）
     * @return 本拍要落盘的那一行所需的 {@link Window}；{@code null} = 这一拍不落
     */
    public @Nullable Window record(long costNanos, int candidates, int types, long tick) {
        final long now = System.nanoTime();
        final long cost = Math.max(0L, costNanos);
        this.calls++;
        this.totalNanos += cost;
        if (cost < this.minNanos) {
            this.minNanos = cost;
        }
        if (cost > this.maxNanos) {
            this.maxNanos = cost;
        }
        if (candidates >= 0) {
            this.candidatesTotal += candidates;
        }
        if (types > this.types) {
            this.types = types;      // 窗口内出现过的最大类型数（跨类型路径与单类型路径混跑时，取上限）
        }
        if (tick == Long.MIN_VALUE) {
            this.tickKnown = false;
        }
        if (!this.started) {
            this.started = true;
            this.windowStartNanos = now - cost;   // 把"第一次进入"那一瞬补回来
            this.windowStartTick = tick;
            this.lastNanos = now;
            return null;
        }
        if (now - this.lastNanos > GAP_NANOS) {
            this.gappy = true;                    // 窗口内闲置过 ⇒ share 不可信（见类注释 §三）
        }
        this.lastNanos = now;

        final long spanTicks = (this.tickKnown && tick != Long.MIN_VALUE && this.windowStartTick != Long.MIN_VALUE)
                ? tick - this.windowStartTick : Long.MIN_VALUE;
        if (spanTicks != Long.MIN_VALUE ? spanTicks < WINDOW_TICKS : this.calls < WINDOW_TICKS) {
            return null;
        }
        final Window w = freeze(spanTicks == Long.MIN_VALUE ? 0L : spanTicks);
        reset();
        return w;
    }

    /** 打一行"还没到窗口"的即时读数用（可选；本工程没用到，留作离线探针的抓手）。 */
    public @Nullable Window peek() {
        if (!this.started || this.calls <= 0L) {
            return null;
        }
        return freeze(0L);
    }

    private Window freeze(long spanTicks) {
        return new Window(spanTicks, this.calls, this.types, this.candidatesTotal,
                this.minNanos == Long.MAX_VALUE ? 0L : this.minNanos,
                this.maxNanos, this.totalNanos, this.lastNanos - this.windowStartNanos, this.gappy);
    }

    private void reset() {
        this.started = false;
        this.windowStartTick = Long.MIN_VALUE;
        this.windowStartNanos = 0L;
        this.lastNanos = 0L;
        this.calls = 0L;
        this.candidatesTotal = 0L;
        this.minNanos = Long.MAX_VALUE;
        this.maxNanos = 0L;
        this.totalNanos = 0L;
        this.types = 0;
        this.gappy = false;
        this.tickKnown = true;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static @NotNull String fmt1(double v) {
        return Double.toString(round1(v));
    }

    private static @NotNull String fmt2(double v) {
        return Double.toString(round2(v));
    }
}
