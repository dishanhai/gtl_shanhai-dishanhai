package com.shanhai.add.hotfix;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 🆕 2026-11-21（根因④）：<b>行为判据 —— 用"还在不在违规栈里"替代"字节对不对"</b>。
 *
 * <h2>为什么必须换判据</h2>
 * 字节判据在这套机制里<b>根本判不了</b>：
 * <pre>
 *   P1  retransform 交给 transformer 的永远是【初始基线】，不是"上次定义后的版本"
 *   P2  一次"全 null"的 retransform 会把类退回基线（等价 undo）
 *   ⇒ 读不到"当前定义"，所以"我写下去的字节还在不在"这件事【不可观测】
 * </pre>
 * 现场（2026-11-20 22:56）的证据正好相反：{@code patch_repair … match=false}
 * <b>第一次 attempt 就是 false</b> —— 判据自己先坏掉了，而"坏判据 + 自动重打 = 物理破坏"。
 *
 * <h2>这条判据的形状</h2>
 * patch 生效后，统计<b>该目标在违规栈里出现的次数</b>在 60 秒窗口内有没下降：
 * <pre>
 *   patch_effect class=&lt;假名&gt; win=60s hits_before=&lt;N&gt; hits_after=&lt;M&gt; verdict=effective|ineffective
 * </pre>
 * 🔴 {@code hits_before} 的口径必须写清：<b>[写入时刻-60s, 写入时刻]</b> 这个闭区间里，
 * 该 target 在违规栈里出现过几次（<b>含</b>触发这次写入的那一拍 —— 所以它恒 ≥1，
 * 窗口才永远可判，不会出现"证据为零于是判 effective"这种假绿灯）。
 * {@code hits_after} = <b>(写入时刻, 现在]</b> 同一个计数。
 *
 * <h2>配对（小表就两张）</h2>
 * <ul>
 *   <li><b>写入点</b>：{@code HotfixAutoCounter#notePatched}（= 某个 target 被选中并真的写进 JVM）；</li>
 *   <li><b>观测点</b>：{@code HotfixAutoCounter#noteViolation} 里
 *       {@code containsTarget(...)} 命中那一刻（= triage 观测到同一个 target 再次出现在栈里）。</li>
 * </ul>
 * 两点都以 {@code 类#方法} 为键 —— 与 {@code PATCHED}／{@code WATCHES} 完全同一个口径，
 * 不新造 id、不做模糊匹配。
 *
 * <h2>判完之后（不许无限重打）</h2>
 * <ul>
 *   <li>{@code ineffective} ＋ 当前是 {@code line} 档 ⇒ <b>升级到 method 档</b>（每个 target 只升一次）；</li>
 *   <li>{@code ineffective} ＋ 已经是 {@code method} 档 ⇒ 打 {@code patch_giveup reason=ineffective}
 *       并<b>停止对该类继续热改</b>（{@link #block}）。</li>
 * </ul>
 * ⇒ 最多"一次升级"，之后一定停下来。这条与 {@code Keep.repairUsed} 预算是<bs>两套独立的止损</bs>，
 * 谁先触发谁生效，互不放宽。
 */
public final class HotfixPatchEffect {

    /** 观察窗口（用户口径：60 秒）。 */
    public static final long WINDOW_MILLIS = 60_000L;

    /** 一个 target 保留多久的观测时间戳（比窗口长 4 倍：够算 before/after，又不无限长）。 */
    public static final long KEEP_MILLIS = 240_000L;

    /** 每个 target 最多留多少个时间戳（超过就丢最老的）。 */
    public static final int STAMP_CAP = 256;

    /** 同时跟踪多少个 target（防泄漏）。 */
    public static final int MAX_ROWS = 64;

    public static final String VERDICT_EFFECTIVE = "effective";

    public static final String VERDICT_INEFFECTIVE = "ineffective";

    /**
     * 一个目标的行为判据行。
     *
     * <p>{@code stamps} 是这个 target <b>每次</b>出现在违规栈里的时刻（环形，满了丢最老）。
     * {@code hits_before}／{@code hits_after} 都从它按时间戳现算 —— 不做增量累加，
     * 也就不会出现"两个计数器各自漂移"的假结论。
     */
    public static final class Row {

        public final String key;
        public final String className;
        public final String methodName;

        /** 上一次真的写进 JVM 的时刻（{@code 0} = 还没写过）。 */
        long writtenAtMillis;

        /** 那一次用的档（{@code line}／{@code method}）。 */
        String tier = HotfixAttackTier.LINE;

        /** 这一代窗口的结论打过了没有。 */
        boolean reported;

        /** 这一代已经升级过一次了（升级后重新开窗，第二次 ineffective ⇒ giveup）。 */
        boolean escalated;

        final long[] stamps = new long[STAMP_CAP];
        int n;

        Row(String key, String className, String methodName) {
            this.key = key == null ? "" : key;
            this.className = className == null ? "" : className;
            this.methodName = methodName == null ? "" : methodName;
        }

        public String tier() {
            return tier;
        }

        public boolean written() {
            return writtenAtMillis > 0L;
        }
    }

    private static final Map<String, Row> ROWS = new LinkedHashMap<String, Row>();

    /** 已经被判 {@code ineffective} 而停手的类（{@code patch_giveup reason=ineffective}）。 */
    private static final Map<String, Long> BLOCKED = new LinkedHashMap<String, Long>();

    private HotfixPatchEffect() {
        // 纯静态
    }

    // ═══════════════════════════ 两个记账点（配对的两头） ═══════════════════════════

    /**
     * <b>观测点</b>：这个 target 刚刚又在违规栈里出现了一次。
     *
     * @param key        {@code 类#方法}
     * @param className  源级类名
     * @param methodName 方法名
     * @param atMillis   <b>抓栈时刻</b>（不是投递时刻；队列里躺着的老现场不许算进窗口）
     */
    public static void noteObserved(String key, String className, String methodName, long atMillis) {
        if (key == null || key.isEmpty()) {
            return;
        }
        final long at = atMillis <= 0L ? System.currentTimeMillis() : atMillis;
        synchronized (ROWS) {
            Row row = ROWS.get(key);
            if (row == null) {
                if (ROWS.size() >= MAX_ROWS) {
                    return;
                }
                row = new Row(key, className, methodName);
                ROWS.put(key, row);
            }
            row.stamps[row.n < STAMP_CAP ? row.n++ : oldestIndex(row)] = at;
        }
    }

    /**
     * <b>写入点</b>：这个 target 刚刚被选中并真的写进了 JVM ⇒ 开一代新窗口。
     *
     * @param tier 这一刀用的档（决定下一步是"升级"还是"停手"）
     */
    public static void noteWritten(String key, String className, String methodName, String tier, long nowMillis) {
        if (key == null || key.isEmpty()) {
            return;
        }
        final long now = nowMillis <= 0L ? System.currentTimeMillis() : nowMillis;
        synchronized (ROWS) {
            Row row = ROWS.get(key);
            if (row == null) {
                if (ROWS.size() >= MAX_ROWS) {
                    return;
                }
                row = new Row(key, className, methodName);
                ROWS.put(key, row);
            }
            row.writtenAtMillis = now;
            row.tier = HotfixAttackTier.orDefault(tier);
            row.reported = false;
            // 🔴 把"触发这次写入的那一拍"补记进去：hits_before 恒 ≥1 ⇒ 窗口永远可判
            noteObservedLocked(row, now);
        }
    }

    /** 窗口重开（升级之后）：档变了、结论要重新判，{@code escalated} 保留。 */
    static void rearm(String key, String tier, long nowMillis) {
        synchronized (ROWS) {
            final Row row = ROWS.get(key);
            if (row == null) {
                return;
            }
            row.writtenAtMillis = nowMillis <= 0L ? System.currentTimeMillis() : nowMillis;
            row.tier = HotfixAttackTier.orDefault(tier);
            row.reported = false;
            row.escalated = true;
            noteObservedLocked(row, row.writtenAtMillis);
        }
    }

    private static void noteObservedLocked(Row row, long at) {
        row.stamps[row.n < STAMP_CAP ? row.n++ : oldestIndex(row)] = at;
    }

    /** 环形满了 ⇒ 覆盖最老的那个位置（{@code stamps} 里最老的未必在 0，见 {@link #hits}）。 */
    private static int oldestIndex(Row row) {
        int idx = 0;
        long best = Long.MAX_VALUE;
        for (int i = 0; i < row.n; i++) {
            if (row.stamps[i] < best) {
                best = row.stamps[i];
                idx = i;
            }
        }
        return idx;
    }

    // ═══════════════════════════ 结论 ═══════════════════════════

    /** 窗口 [from, to] 闭区间里的观测次数。 */
    public static long hits(String key, long fromMillis, long toMillis) {
        synchronized (ROWS) {
            final Row row = ROWS.get(key);
            return row == null ? 0L : hits(row, fromMillis, toMillis);
        }
    }

    private static long hits(Row row, long from, long to) {
        long c = 0L;
        for (int i = 0; i < row.n; i++) {
            final long t = row.stamps[i];
            if (t >= from && t <= to) {
                c++;
            }
        }
        return c;
    }

    /** 写入前窗口里的次数（含写入那一刻的那一拍）。 */
    public static long hitsBefore(Row row) {
        return row == null || !row.written() ? 0L : hits(row, row.writtenAtMillis - WINDOW_MILLIS, row.writtenAtMillis);
    }

    /** 写入后窗口里的次数（到 {@code now} 为止）。 */
    public static long hitsAfter(Row row, long nowMillis) {
        return row == null || !row.written() ? 0L
                : hits(row, row.writtenAtMillis + 1L, Math.min(nowMillis, row.writtenAtMillis + WINDOW_MILLIS));
    }

    /**
     * 到点的窗口（工作线程每 250 ms 扫一次；扫到就打一行 {@code patch_effect} 并清标记）。
     *
     * <p>两种到点情形：
     * <ol>
     *   <li><b>窗口结束</b>（60 s 到了）：正常出结论；</li>
     *   <li><b>次数已不可挽回</b>（{@code hits_after >= hits_before}）：不用等窗口走完 ——
     *       之后的次数只会更多，已经可以判 {@code ineffective}（旧口径"次数达阈值时打一行"就是它）。</li>
     * </ol>
     */
    public static List<Row> due(long nowMillis) {
        final List<Row> out = new ArrayList<Row>(2);
        synchronized (ROWS) {
            for (Iterator<Map.Entry<String, Row>> it = ROWS.entrySet().iterator(); it.hasNext(); ) {
                final Row row = it.next().getValue();
                if (row == null || !row.written() || row.reported) {
                    continue;
                }
                final long endsAt = row.writtenAtMillis + WINDOW_MILLIS;
                final long before = hitsBefore(row);
                final long after = hitsAfter(row, nowMillis);
                if (nowMillis >= endsAt || (before > 0L && after >= before)) {
                    out.add(row);
                }
            }
        }
        return out;
    }

    /** 判这一代窗口的结论（{@code hits_after < hits_before} ⇒ 有效）。 */
    public static String verdict(Row row, long nowMillis) {
        final long before = hitsBefore(row);
        final long after = hitsAfter(row, nowMillis);
        return after < before ? VERDICT_EFFECTIVE : VERDICT_INEFFECTIVE;
    }

    /** 标记这一代窗口的结论已经打过了（{@code escalated} = 紧接着要升级）。 */
    public static void markReported(Row row, boolean escalated) {
        if (row == null) {
            return;
        }
        synchronized (ROWS) {
            row.reported = true;
            if (escalated) {
                row.escalated = true;
            }
        }
    }

    public static boolean escalated(Row row) {
        synchronized (ROWS) {
            return row != null && row.escalated;
        }
    }

    // ═══════════════════════════ 停手（每类一次） ═══════════════════════════

    /** {@code patch_giveup reason=ineffective}：这个类不再继续热改。 */
    public static void block(String className) {
        if (className == null || className.isEmpty()) {
            return;
        }
        synchronized (BLOCKED) {
            if (!BLOCKED.containsKey(className)) {
                BLOCKED.put(className, System.currentTimeMillis());
            }
        }
    }

    /** 这个类是不是已经被判"改了也没用"而停手了。 */
    public static boolean blocked(String className) {
        if (className == null || className.isEmpty()) {
            return false;
        }
        synchronized (BLOCKED) {
            return BLOCKED.containsKey(className);
        }
    }

    /** 有多少个类被停手（{@code status} 用）。 */
    public static int blockedCount() {
        synchronized (BLOCKED) {
            return BLOCKED.size();
        }
    }

    /** 停服／退出世界：整表清空。 */
    public static void reset() {
        synchronized (ROWS) {
            ROWS.clear();
        }
        synchronized (BLOCKED) {
            BLOCKED.clear();
        }
    }

    /** 显式 undo 之后把这个 target 的窗口摘掉（它已经不该再"生效"了）。 */
    public static void forget(String key) {
        if (key == null) {
            return;
        }
        synchronized (ROWS) {
            ROWS.remove(key);
        }
    }

    // ═══════════════════════════ 日志行 ═══════════════════════════

    /**
     * {@code patch_effect class=<假名> win=60s hits_before=<N> hits_after=<M> verdict=effective|ineffective}。
     *
     * <p>{@code win=60s} 是<b>字面口径</b>（不是"大约"）：两侧都按 60 000 ms 闭区间算。
     */
    public static String line(Row row, long nowMillis) {
        final long before = hitsBefore(row);
        final long after = hitsAfter(row, nowMillis);
        return "patch_effect class=" + HotfixAutoCounter.token(row.className)
                + " target=" + HotfixAutoCounter.token(row.key)
                + " win=60s"
                + " hits_before=" + before
                + " hits_after=" + after
                + " verdict=" + verdict(row, nowMillis)
                + " attack=" + HotfixAttackTier.orDefault(row.tier)
                + (row.escalated ? " escalated=1" : "");
    }

    /** {@code patch_giveup reason=ineffective} 的那一行（与既有 patch_giveup 同名，reason 不同）。 */
    public static String giveupLine(String className, int hitsBefore, int hitsAfter) {
        return "patch_giveup class=" + HotfixAutoCounter.token(className)
                + " reason=ineffective"
                + " hits_before=" + hitsBefore
                + " hits_after=" + hitsAfter
                + " note=method档也压不下来⇒停止对该类继续热改";
    }

    /** 有几个 target 在表里（{@code status} 用）。 */
    public static int rowCount() {
        synchronized (ROWS) {
            return ROWS.size();
        }
    }
}
