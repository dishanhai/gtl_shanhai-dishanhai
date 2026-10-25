package com.shanhai.mixin.probe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>只读计时探针的记账本</b>（配套 {@link ShanhaiPatternTimingProbeMixin} /
 * {@link ShanhaiHostFormedProbeMixin}）。
 *
 * <h2>🔴 这是只读探针，验收后可整包删除</h2>
 * 本类<b>不改任何状态、不碰任何游戏对象</b>，只做三件事：累加计时、每分钟打一行汇总、打单行慢事件。
 * 删除顺序：① 本文件 ② 两个 ProbeMixin ③ {@code shanhai.probe.mixin.json} 里那两行。
 *
 * <h2>为什么汇总要"懒刷新"而不是开定时器</h2>
 * 开一个 {@code ScheduledExecutorService} 就多一条线程、多一个退出时机、
 * 多一个"探针自己被别人怀疑"的变量。这里改成<b>下一次事件到来时</b>检查"距上次汇总是否 ≥60000ms"，
 * 到点就补一行。代价是：如果服务器之后<b>一次检查都没有</b>，最后那一窗口的汇总不会立刻出现
 * （要等下一个事件、或下一条 {@code onFormed}）—— 这条限制是<b>已知且接受</b>的，
 * 因为本探针要回答的是"卡的那几十秒里发生了什么"，而那几十秒里必然有事件。
 *
 * <h2>字段口径</h2>
 * <ul>
 *   <li>{@code ms}：本次调用的墙钟耗时（{@code System.nanoTime()} 差），三位小数。</li>
 *   <li>{@code count}/{@code ge50}/{@code total_ms}/{@code max_ms}：<b>本窗口</b>（默认 60s）的
 *       调用次数 / 耗时 ≥50ms 的次数 / 累计毫秒 / 最大毫秒。窗口一到就重置。</li>
 *   <li>{@code formed}：读 {@code MultiblockState.lastController.isFormed()}（见 mixin 注释，
 *       特意<b>不</b>用 {@code getController()}，因为后者会写 {@code lastController}/{@code error}）。</li>
 * </ul>
 */
public final class PatternTimingProbe {

    /** 探针自己的 logger（不借宿主的 LOGGER，探针删了不影响任何既有日志行）。 */
    public static final Logger LOGGER = LoggerFactory.getLogger("SHANHAI-PATPROBE");

    /** 窗口长度：60s。 */
    private static final long WINDOW_NANOS = 60_000_000_000L;

    // ---- 窗口内累计（单个服务器/异步线程 + 极少数客户端预览线程 ⇒ 用 synchronized 足够） ----
    private static long windowStartNanos = System.nanoTime();
    private static int count;
    private static int ge50;
    private static long totalNanos;
    private static long maxNanos;
    private static int ge20Formed;
    private static long formedTotalNanos;
    private static long formedMaxNanos;

    /**
     * 正在执行的 {@code onStructureFormed()} 嵌套深度。
     * 只用来说明 {@code cause=} —— 检查是"成型回调里面"发起的（递归/重入），还是别处发起的。
     */
    private static int formedDepth;

    private PatternTimingProbe() {}

    // ------------------------------------------------------------------ 深度标记

    public static void enterFormed() {
        formedDepth++;
    }

    public static void exitFormed() {
        if (formedDepth > 0) {
            formedDepth--;
        }
    }

    public static boolean inFormedCallback() {
        return formedDepth > 0;
    }

    // ------------------------------------------------------------------ 图案检查

    /** 记一次图案检查。{@code nanos} = 耗时。 */
    public static void recordCheck(long nanos) {
        final double ms = nanos / 1_000_000.0;
        synchronized (PatternTimingProbe.class) {
            count++;
            totalNanos += nanos;
            if (nanos > maxNanos) {
                maxNanos = nanos;
            }
            if (ms >= 50.0) {
                ge50++;
            }
            flushIfDueLocked();
        }
    }

    // ------------------------------------------------------------------ 成型回调

    /** 记一次 {@code onStructureFormed()}。 */
    public static void recordFormed(long nanos) {
        final double ms = nanos / 1_000_000.0;
        synchronized (PatternTimingProbe.class) {
            if (ms >= 20.0) {
                ge20Formed++;
            }
            formedTotalNanos += nanos;
            if (nanos > formedMaxNanos) {
                formedMaxNanos = nanos;
            }
            flushIfDueLocked();
        }
    }

    // ------------------------------------------------------------------ 汇总

    /** 每分钟最多一行；由事件驱动（见类注释里"懒刷新"的说明）。 */
    private static void flushIfDueLocked() {
        final long now = System.nanoTime();
        final long span = now - windowStartNanos;
        if (span < WINDOW_NANOS) {
            return;
        }
        final double totalMs = totalNanos / 1_000_000.0;
        final double maxMs = maxNanos / 1_000_000.0;
        final double formedTotalMs = formedTotalNanos / 1_000_000.0;
        final double formedMaxMs = formedMaxNanos / 1_000_000.0;
        // 合并器（[SHANHAI-PATCOALESCE]，那是【真功能】不是探针）在本窗口的账目。
        // 🔴 只是读计数器并清零；探针删掉这一个字段不影响合并器本身。
        final com.shanhai.common.diag.ShanhaiPatternCheckCoalescer.Window coalesce =
                com.shanhai.common.diag.ShanhaiPatternCheckCoalescer.pollWindow();
        try {
            LOGGER.info("[SHANHAI-PATPROBE] summary window_ms={} check_count={} check_ge50={} "
                            + "check_total_ms={} check_max_ms={} | onFormed_ge20={} onFormed_total_ms={} "
                            + "onFormed_max_ms={} | deferred={} applied={} applied_max_ms={}",
                    span / 1_000_000L, count, ge50,
                    round3(totalMs), round3(maxMs), ge20Formed,
                    round3(formedTotalMs), round3(formedMaxMs),
                    coalesce.deferred, coalesce.applied, round3(coalesce.appliedMaxMs));
        } catch (Throwable ignored) {
            // 探针绝不许把异常抛进服务器 tick 或成型回调
        }
        windowStartNanos = now;
        count = 0;
        ge50 = 0;
        totalNanos = 0L;
        maxNanos = 0L;
        ge20Formed = 0;
        formedTotalNanos = 0L;
        formedMaxNanos = 0L;
    }

    public static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    /** 把线程名压成短标签，避免每行都背一长串。 */
    public static String threadTag() {
        try {
            final String n = Thread.currentThread().getName();
            if (n == null || n.isEmpty()) {
                return "unknown";
            }
            if (n.contains("Server thread")) {
                return "server";
            }
            if (n.contains("GTCEu Multiblock Async")) {
                return "gtceu-async";
            }
            if (n.contains("Render thread")) {
                return "render";
            }
            return n.length() > 32 ? n.substring(0, 32) : n;
        } catch (Throwable t) {
            return "unknown";
        }
    }
}
