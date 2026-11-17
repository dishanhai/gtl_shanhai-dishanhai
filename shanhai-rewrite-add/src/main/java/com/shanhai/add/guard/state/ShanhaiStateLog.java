package com.shanhai.add.guard.state;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 山海 · <b>「拦截 状态被改写」统一日志行</b>（<b>纯 JDK</b>：不认 MC、不认 log4j）。
 *
 * <h2>行形态（可 grep，字段顺序固定，用户点名要的这一条）</h2>
 * <pre>
 *   [SHANHAI-GUARD] 拦截 状态被改写 ｜ field=health|motion|pos|inv target=&lt;玩家&gt; \
 *        before=… after=… source=&lt;栈第1个非白名单帧 或 unknown&gt;
 * </pre>
 * 例：
 * <pre>
 *   [SHANHAI-GUARD] 拦截 状态被改写 ｜ field=motion target=Steve before=(0.000, -0.078, 0.000) \
 *        after=(2.400, 0.400, -1.100) source=unknown restored=true via=tick_boundary
 * </pre>
 *
 * <h2>为什么前缀与竖线是字面量（而不是引用本体的常量）</h2>
 * 与 {@code com.shanhai.add.hotfix.HotfixAutoCounter} 同一条理由：<b>本类要能被脱离游戏单独编译并跑</b>
 * （{@code temp\momentum\offline\}）。一旦 import 本体/Forge 的类，那条离线路就断了。
 * 前缀 {@code "[SHANHAI-GUARD] "} 与本体 {@code ShanhaiGuardCore.LOG_PREFIX} <b>逐字相同</b>
 * （故意重复，改一处必须改两处 —— 自检里有断言）。
 *
 * <h2>限流（用户口径：1/s）</h2>
 * 同一个 {@code field|target} 形态，<b>最多每 {@link #MIN_INTERVAL_MILLIS} 毫秒一行</b>；
 * 被挡下的次数累计进下一条真正落盘的行（行尾 {@code folded=N}），
 * 这样"被刷的时候"既不会刷屏，也不会看不出发生了几次。
 *
 * <h2>{@code source=} 现在固定写 {@code none_poll}（🔴 2026-11-08 纠正后的口径）</h2>
 * 这一行的产生点是<b>轮询</b>（tick 边界上比对"值变了没有"）。等我们看出来的时候，
 * 改它的那个调用栈<b>早就返回了</b> —— 栈上只剩 {@code ServerTickEvent → 我们自己的类}。
 * 那天 46 条 {@code field=motion} 的 {@code source=unknown} 就是这个原因，不是抓取代码坏了。
 * ⇒ <b>轮询点不再假装能抓到写者</b>，固定写 {@code source=none_poll} ＋
 * {@code note=restored_wait_injection_stack}；
 * 真正的写者由<b>原版写入点的注入</b>抓（{@code [SHANHAI-GUARD] violation_stack where=…}，
 * 见 {@code ShanhaiViolationStack}／{@code ShanhaiViolationProbe}）。
 */
public final class ShanhaiStateLog {

    /** 与本体 {@code ShanhaiGuardCore.LOG_PREFIX} 逐字相同（故意重复：本类保持纯 JDK）。 */
    public static final String PREFIX = "[SHANHAI-GUARD] ";

    /** 这一族日志的统一开头（用户点名的一句话）。 */
    public static final String TITLE = "拦截 状态被改写";

    /** 同一个 {@code field|target} 最多每这么多毫秒一行。 */
    public static final long MIN_INTERVAL_MILLIS = 1000L;

    /** 抓栈时一律跳过的帧前缀（我们自己 / 原版 / Forge / JDK 噪声）。 */
    private static final String[] SKIP_PREFIXES = {
            "java.", "javax.", "jdk.", "sun.", "com.sun.",
            "net.minecraft.", "net.minecraftforge.", "cpw.mods.",
            "com.shanhai.",
            "com.shanhai.add.guard.state.ShanhaiStateLog",
    };

    /** 日志出口（游戏侧接 {@code ShanhaiMod.LOGGER}；离线自检接 stdout）。{@code null} = 静默。 */
    public interface Sink {
        void line(String text);
    }

    private static volatile Sink sink;

    /** 形态 ⇒ 上次落盘的时刻。 */
    private static final Map<String, Long> LAST = new LinkedHashMap<>();

    private static final AtomicLong EMITTED = new AtomicLong();
    private static final AtomicLong FOLDED = new AtomicLong();

    private ShanhaiStateLog() {}

    /** 接日志出口（游戏侧在装配点调；离线自检自己调）。 */
    public static void bind(Sink newSink) {
        sink = newSink;
    }

    /** 清空限流记忆（命令 selfcheck / 离线自检用）。 */
    public static synchronized void reset() {
        LAST.clear();
    }

    /** 已经真正落盘多少行（读数）。 */
    public static long emitted() {
        return EMITTED.get();
    }

    /** 被限流挡掉多少次（读数）。 */
    public static long folded() {
        return FOLDED.get();
    }

    // ═══════════════════════════════ 一行 ═══════════════════════════════

    /**
     * 组一行（<b>纯函数</b>，限流与出口都不在这里）。
     *
     * @param field   {@code health}/{@code motion}/{@code pos}/{@code inv}
     * @param target  玩家名
     * @param before  我们认可的值（要写回去的那个）
     * @param after   现在读到的值（被别人改成什么）
     * @param source  {@code 类#方法:行} 或 {@code unknown}
     * @param extra   附加字段（可以为空串；每段自己带 {@code key=}）
     * @return 完整一行
     */
    public static String line(String field, String target, String before, String after,
                              String source, String extra) {
        final StringBuilder sb = new StringBuilder(160);
        sb.append(PREFIX).append(TITLE).append(" \uFF5C field=").append(safe(field))
                .append(" target=").append(safe(target))
                .append(" before=").append(safe(before))
                .append(" after=").append(safe(after))
                .append(" source=").append(safe(source));
        if (extra != null && !extra.isEmpty()) {
            sb.append(' ').append(extra);
        }
        return sb.toString();
    }

    /**
     * 限流后落盘一行（{@code <= 1 行/秒/形态}）。
     *
     * @return true = 这一行真的写出去了；false = 被限流挡下（计数＋1）
     */
    public static boolean emit(String field, String target, String before, String after,
                               String source, String extra) {
        final String shape = safe(field) + '|' + safe(target);
        final long folded;
        synchronized (ShanhaiStateLog.class) {
            final long now = System.currentTimeMillis();
            final Long last = LAST.get(shape);
            if (last != null && now - last < MIN_INTERVAL_MILLIS) {
                FOLDED.incrementAndGet();
                return false;
            }
            LAST.put(shape, now);
            // 形态表有界（防泄漏）：超过 64 个形态就把最老的丢掉（玩家数远小于这个）
            if (LAST.size() > 64) {
                final String oldest = LAST.keySet().iterator().next();
                LAST.remove(oldest);
            }
            folded = FOLDED.getAndSet(0L);
        }
        final String tail = extra == null || extra.isEmpty() ? "" : extra;
        final String line = line(field, target, before, after, source,
                (tail.isEmpty() ? "" : tail + " ") + (folded > 0L ? "folded=" + folded : ""));
        EMITTED.incrementAndGet();
        final Sink s = sink;
        if (s != null) {
            try {
                s.line(line);
            } catch (Throwable ignored) {
                // 日志出口坏了不许把 tick 带崩
            }
        }
        return true;
    }

    // ═══════════════════════════════ 格式化小工具 ═══════════════════════════════

    /** 一个三分量值：{@code (x, y, z)}（三位小数，{@code Locale.ROOT} 防逗号小数点）。 */
    public static String vec(double x, double y, double z) {
        return "(" + num(x) + ", " + num(y) + ", " + num(z) + ")";
    }

    /** 单个数（三位小数；NaN/Inf 原样写字面量，不假装是数）。 */
    public static String num(double d) {
        if (Double.isNaN(d)) {
            return "NaN";
        }
        if (Double.isInfinite(d)) {
            return d > 0 ? "Infinity" : "-Infinity";
        }
        return String.format(Locale.ROOT, "%.3f", d);
    }

    /** 取第一个非白名单栈帧（{@code 类#方法:行}）；取不到 ⇒ {@code "unknown"}（<b>不编名字</b>）。 */
    public static String sourceFrame() {
        try {
            final StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            for (int i = 0; i < stack.length; i++) {
                final StackTraceElement e = stack[i];
                if (e == null) {
                    continue;
                }
                final String cls = e.getClassName();
                if (cls == null || skipped(cls)) {
                    continue;
                }
                return cls + "#" + e.getMethodName() + ":" + (e.getLineNumber() >= 0 ? e.getLineNumber() : -1);
            }
        } catch (Throwable ignored) {
            // 抓不到栈不是错误：如实写 unknown
        }
        return "unknown";
    }

    private static boolean skipped(String className) {
        for (int i = 0; i < SKIP_PREFIXES.length; i++) {
            if (className.startsWith(SKIP_PREFIXES[i])) {
                return true;
            }
        }
        return false;
    }

    private static String safe(String text) {
        if (text == null || text.isEmpty()) {
            return "?";
        }
        return text;
    }
}
