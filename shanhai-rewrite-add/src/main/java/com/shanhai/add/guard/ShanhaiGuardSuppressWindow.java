package com.shanhai.add.guard;

import com.shanhai.common.guard.ShanhaiGuardCore;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 山海 · <b>持续压制（B 件）的纯逻辑核：窗口表 ＋ 窗口算术 ＋ 那两行日志文案</b>
 * （纯 JDK，零 Forge / 零 Minecraft）。
 *
 * <h2>它解决什么</h2>
 * 现场：{@code /shanhai purge} 每次抹掉 6~75 只，但<b>过一会它又回来继续打</b>（那只 boss 自带复活机制）。
 * 于是需要一份<b>不持久化</b>的内存状态：<b>"这个类型最近被我们抹过，接下来一段时间内见到就再抹"</b>。
 * 本类只做三件事：
 * <pre>
 *   ① 窗口表：{@code Map<类型, 截止时间(ms)>}（{@link WindowTable}；{@code K} 在游戏侧就是 {@code EntityType<?>}）
 *   ② 窗口算术：新上榜 = +60 秒；窗口内又被抹一次 = +5 秒；长度上限 5 分钟；不出现就自己过期
 *   ③ 两行文案：日志 {@code [SHANHAI-GUARD] suppress type=… removed=… window_left_ms=…}
 *              左下角 {@code 已压制 <名字> ×N}
 * </pre>
 *
 * <h2>🔴 fail-closed：只压制"被我们抹过的类型"</h2>
 * 本类<b>没有</b>任何 mod 包名、类名、实体 id 常量，也不提供"按名字判断该不该压制"的入口 ——
 * <b>上榜的唯一通道就是 {@link WindowTable#note} 被调用</b>，而调用点只有两个：
 * {@code ShanhaiPurge#purgeAll}（{@code /shanhai purge} 真抹掉的那一只）与
 * {@code ShanhaiPurge#drainQueue}（挨打反击抹掉的那一只）。
 * ⇒ 任何"没被我们动过"的类型永远进不了这张表。
 *
 * <h2>窗口算术为什么是"可加性上限"而不是"固定 60 秒"</h2>
 * <pre>
 *   首次上榜（或已过期后再次上榜）   until = now + 60_000
 *   窗口内又被抹一次                until = min(until + 5_000, now + 300_000)
 *   一直不出现                      until 到点 ⇒ 自动过期、并从表里摘掉（{@link WindowTable#prune}）
 * </pre>
 * ⇒ 表现是"它越活越被压得久"，但<b>最多 5 分钟</b>（不会因为刷屏被永久钉死）。
 *
 * <h2>为什么单独一个纯 JDK 类</h2>
 * 和 {@code ShanhaiGuardRemoval} 同族：判据/算术可以<b>空 classpath 单跑</b>（{@link #main}），
 * 游戏侧的 {@code ShanhaiGuardSuppression} 只负责"把 {@code EntityType<?>} 塞进来、把实体交给抹除阶梯"。
 */
public final class ShanhaiGuardSuppressWindow {

    private ShanhaiGuardSuppressWindow() {}

    /** 首次上榜的窗口长度（规格：默认 60 秒）。 */
    public static final long WINDOW_MS = 60_000L;

    /** 窗口内每抹一次加多少（规格：+5 秒）。 */
    public static final long EXTEND_MS = 5_000L;

    /** 窗口长度上限（规格：上限 5 分钟）。 */
    public static final long WINDOW_MAX_MS = 300_000L;

    /** 每几 tick 扫一次（规格：每 5 tick）。 */
    public static final int SCAN_EVERY_TICKS = 5;

    /**
     * 日志／播报的合并间隔：同一类型<b>每秒最多落一行日志、最多播一句</b>
     * （规格：不要每 tick 刷；窗口内每秒扫 4 次，若每次都打就是刷屏）。
     */
    public static final long FLUSH_INTERVAL_MS = 1_000L;

    /** 日志前缀（与守卫其它路径同一个，避免两套前缀）。 */
    public static final String PREFIX = ShanhaiGuardCore.LOG_PREFIX;

    /**
     * <b>窗口表</b>：键（游戏侧 = {@code EntityType<?>}）⇒ 截止时间（毫秒，{@code System.currentTimeMillis()} 口径）。
     *
     * <p>🔴 用 {@link LinkedHashMap}：读数与日志顺序 = 上榜顺序（报告里对得上）。
     * 键用的是<b>对象身份</b>语义（{@code EntityType} 是注册表单例 ⇒ 同一类型恒为同一个对象）。
     */
    public static final class WindowTable<K> {

        private final Map<K, Long> until = new LinkedHashMap<>();

        /**
         * 上榜 / 续期。
         *
         * @return 续期后的窗口剩余毫秒（{@code > 0}）
         */
        public long note(K key, long nowMillis) {
            if (key == null) {
                return 0L;
            }
            final Long prev = until.get(key);
            final long base;
            if (prev == null || prev <= nowMillis) {
                base = nowMillis + WINDOW_MS;          // 新上榜（或已过期后重新上榜）⇒ 60 秒
            } else {
                base = prev + EXTEND_MS;                // 窗口内又抹到 ⇒ +5 秒
            }
            final long capped = Math.min(base, nowMillis + WINDOW_MAX_MS);
            until.put(key, capped);
            return capped - nowMillis;
        }

        /** 还剩多少毫秒；不在表里 / 已过期 ⇒ {@code 0}（顺手把过期的摘掉）。 */
        public long leftMillis(K key, long nowMillis) {
            if (key == null) {
                return 0L;
            }
            final Long deadline = until.get(key);
            if (deadline == null) {
                return 0L;
            }
            if (deadline <= nowMillis) {
                until.remove(key);
                return 0L;
            }
            return deadline - nowMillis;
        }

        /** 现在还在窗口里吗（不摘表：扫描循环里频繁调用，摘表交给 {@link #prune}）。 */
        public boolean isActive(K key, long nowMillis) {
            if (key == null) {
                return false;
            }
            final Long deadline = until.get(key);
            return deadline != null && deadline > nowMillis;
        }

        /** 摘掉所有已过期的；返回摘掉几个。 */
        public int prune(long nowMillis) {
            int removed = 0;
            final var it = until.entrySet().iterator();
            while (it.hasNext()) {
                if (it.next().getValue() <= nowMillis) {
                    it.remove();
                    removed++;
                }
            }
            return removed;
        }

        /** 还没过期的那些（快照，保序）；顺手摘掉过期的。 */
        public Map<K, Long> snapshot(long nowMillis) {
            prune(nowMillis);
            return new LinkedHashMap<>(until);
        }

        /** 清空；返回清掉几项。 */
        public int clear() {
            final int n = until.size();
            until.clear();
            return n;
        }

        /** 表里现在有几项（含已过期但还没被摘的）。 */
        public int size() {
            return until.size();
        }
    }

    // ═══════════════════════════════ 两行文案（规格点名的形状） ═══════════════════════════════

    /**
     * 日志那一行：{@code [SHANHAI-GUARD] suppress type=<id> removed=<n> window_left_ms=<x>}。
     *
     * @param typeId       类型的注册名（{@code example:some_boss} 这种）
     * @param removed      这一次（合并后）总共又抹掉几只
     * @param windowLeftMs 现在的窗口剩余毫秒
     */
    public static String logLine(String typeId, int removed, long windowLeftMs) {
        return PREFIX + "suppress type=" + (typeId == null ? "?" : typeId)
                + " removed=" + removed
                + " window_left_ms=" + windowLeftMs;
    }

    /** 左下角那一句：{@code 已压制 <类型中文/ID> ×N}。 */
    public static String announce(String display, int n) {
        final String name = (display == null || display.isEmpty()) ? "?" : display;
        return "已压制 " + name + " ×" + n;
    }

    /** 剩余秒数（向上取整；日志/命令回显用，别打 59999 这种毫秒噪声）。 */
    public static long secondsLeft(long windowLeftMs) {
        if (windowLeftMs <= 0L) {
            return 0L;
        }
        return (windowLeftMs + 999L) / 1_000L;
    }

    // ═══════════════════════════════ 自检（同文件 main） ═══════════════════════════════

    private static final PrintStream OUT = utf8Stdout();

    private static PrintStream utf8Stdout() {
        try {
            return new PrintStream(new FileOutputStream(FileDescriptor.out), true, "UTF-8");
        } catch (Throwable t) {
            return System.out;
        }
    }

    private static int checks = 0;
    private static int reds = 0;

    private static void expect(String group, String name, boolean expected, boolean actual, String reading) {
        checks++;
        final boolean ok = (expected == actual);
        if (!ok) {
            reds++;
        }
        OUT.println(String.format("%-4s | %-6s | %-46s | 期望=%-5s 实测=%-5s | %s",
                ok ? "PASS" : "RED", group, name, expected, actual, reading));
    }

    /** 【故意写坏】没有上限的续期（负对照：证明"上限 5 分钟"那条断言不是恒绿）。 */
    private static long noteMutantNoCap(long nowMillis, long prev) {
        return (prev <= nowMillis ? nowMillis + WINDOW_MS : prev + EXTEND_MS);
    }

    /**
     * 离线自检：{@code javac -d <out> ShanhaiGuardCore.java ShanhaiGuardSuppressWindow.java} 后
     * {@code java -cp <out> com.shanhai.common.guard.ShanhaiGuardSuppressWindow}。
     */
    public static void main(String[] args) {
        OUT.println("=== ShanhaiGuardSuppressWindow 离线自检 ===");

        // ── ① 窗口算术 ──
        final long t0 = 1_000_000L;
        final WindowTable<String> t2 = new WindowTable<>();
        final long first = t2.note("boss", t0);
        final long second = t2.note("boss", t0);
        final long third = t2.note("boss", t0);
        expect("①窗口", "首次 = 60000ms", true, first == 60_000L, "first=" + first);
        expect("①窗口", "窗口内再抹一次 = 65000ms", true, second == 65_000L, "second=" + second);
        expect("①窗口", "第三次 = 70000ms（每抹一次 +5 秒）", true, third == 70_000L, "third=" + third);

        // ── ① 上限 5 分钟 ──
        final WindowTable<String> capped = new WindowTable<>();
        long last = 0L;
        for (int i = 0; i < 200; i++) {
            last = capped.note("boss", t0);
        }
        expect("①上限", "连抹 200 次也只到 300000ms", true, last == WINDOW_MAX_MS, "left=" + last);
        // 负对照：把上限去掉 ⇒ 同一个循环会超过 5 分钟
        long mutant = 0L;
        long prev = 0L;
        for (int i = 0; i < 200; i++) {
            prev = noteMutantNoCap(t0, prev);
            mutant = prev - t0;
        }
        expect("①上限负对照", "去掉上限的突变体会超过 300000ms（不是恒绿）", true,
                mutant > WINDOW_MAX_MS, "突变体 left=" + mutant);

        // ── ① 过期 ──
        final WindowTable<String> exp = new WindowTable<>();
        exp.note("boss", t0);
        expect("①过期", "窗口内最后一毫秒还算在窗口里", true,
                exp.isActive("boss", t0 + WINDOW_MS - 1L), "");
        expect("①过期", "到点（60 秒整）就过期", false, exp.isActive("boss", t0 + WINDOW_MS), "");
        expect("①过期", "过期后 leftMillis = 0", true, exp.leftMillis("boss", t0 + WINDOW_MS) == 0L, "");
        expect("①过期", "过期后表项被摘掉（size=0）", true, exp.size() == 0, "size=" + exp.size());
        expect("①过期", "过期后重新上榜回到 60000ms", true,
                exp.note("boss", t0 + WINDOW_MS) == WINDOW_MS, "");
        expect("①过期", "从没上过榜的类型 leftMillis=0 / isActive=false", true,
                exp.leftMillis("never", t0) == 0L && !exp.isActive("never", t0), "");

        // ── ② 表语义 ──
        final WindowTable<String> multi = new WindowTable<>();
        multi.note("a", t0);
        multi.note("b", t0 + 10_000L);
        expect("②表", "两个类型各算各的窗口", true,
                multi.leftMillis("a", t0 + 10_000L) == 50_000L
                        && multi.leftMillis("b", t0 + 10_000L) == 60_000L,
                "a=" + multi.leftMillis("a", t0 + 10_000L) + " b=" + multi.leftMillis("b", t0 + 10_000L));
        expect("②表", "prune 只摘过期的（此刻一个都没过期）", true,
                multi.prune(t0 + 10_000L) == 0, "");
        expect("②表", "到 69 秒时 a 已过期、b 还在", true,
                multi.prune(t0 + 69_000L) == 1 && multi.isActive("b", t0 + 69_000L),
                "剩 " + multi.size() + " 项");
        expect("②表", "snapshot 只给没过期的", true,
                multi.snapshot(t0 + 69_000L).size() == 1, "size=" + multi.snapshot(t0 + 69_000L).size());
        expect("②表", "clear 返回清掉的项数并清空", true,
                multi.clear() == 1 && multi.size() == 0, "");
        expect("②表", "null 键不炸、返回 0", true,
                multi.note(null, t0) == 0L && !multi.isActive(null, t0), "");

        // ── ③ 两行文案（规格点名的形状，逐字）──
        expect("③文案", "日志行格式", true,
                "[SHANHAI-GUARD] suppress type=example:some_boss removed=3 window_left_ms=55000"
                        .equals(logLine("example:some_boss", 3, 55_000L)),
                logLine("example:some_boss", 3, 55_000L));
        expect("③文案", "左下角格式", true,
                "已压制 示例生物 ×3".equals(announce("示例生物", 3)), announce("示例生物", 3));
        expect("③文案", "名字为空写 ?（不写空字符串）", true,
                "已压制 ? ×1".equals(announce("", 1)), announce("", 1));
        expect("③文案", "秒数向上取整", true,
                secondsLeft(59_001L) == 60L && secondsLeft(60_000L) == 60L && secondsLeft(0L) == 0L,
                "59001→" + secondsLeft(59_001L) + " 60000→" + secondsLeft(60_000L));

        // ── ④ 常量口径（防止有人手滑改数）──
        expect("④常量", "默认窗口 = 60 秒", true, WINDOW_MS == 60_000L, "WINDOW_MS=" + WINDOW_MS);
        expect("④常量", "每次 +5 秒", true, EXTEND_MS == 5_000L, "EXTEND_MS=" + EXTEND_MS);
        expect("④常量", "上限 5 分钟", true, WINDOW_MAX_MS == 300_000L, "WINDOW_MAX_MS=" + WINDOW_MAX_MS);
        expect("④常量", "每 5 tick 扫一次", true, SCAN_EVERY_TICKS == 5, "SCAN_EVERY_TICKS=" + SCAN_EVERY_TICKS);

        OUT.println("---------------------------------------------------------------");
        OUT.println("合计 " + checks + " 条，红 " + reds + " 条 ⇒ " + (reds == 0 ? "全绿 PASS" : "有红 FAIL"));
        if (reds != 0) {
            System.exit(1);
        }
    }
}
