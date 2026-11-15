package com.shanhai.client.holo;

/**
 * 山海重构 · 全息菜单<b>开关键的去抖闸门（纯算术核，不依赖 Minecraft）</b>。
 *
 * <h2>1. 它修的是哪一条真实现象（不是猜的）</h2>
 * 用户报「<b>猫在的时候打不开面板</b>」。2026-11-08 的日志取证里有一条<b>与摆位无关</b>的病根：
 * <pre>
 *   [SHANHAI-HOLO] holo_menu_toggled_by_item … now=true
 *   [SHANHAI-HOLO] holo_menu_toggled_by_item … now=false
 *   …（1 秒内 6 次，now=true/false 交替）
 * </pre>
 * ⇒ 用户"连点两下"，两次 {@code ACT_TOGGLE_MENU} <b>互相抵消</b>，净状态落回"关"，
 * 而他看到的就是"打不开"。这一条在日志上长得和"根本没点"一模一样，所以旧日志里查不动。
 *
 * <h2>2. 规则（用户任务书逐字）</h2>
 * <pre>
 *   两次切换的间隔 &lt; {@link ShanhaiHoloMenuTuning#TOGGLE_DEBOUNCE_MS}（= 250 ms）
 *     ⇒ 忽略【后一次】，并打一行
 *        [SHANHAI-HOLO] holo_toggle_debounced gap_ms=&lt;n&gt; dropped=true
 * </pre>
 * <p>⚠️ 只丢"太快的那一拍"，<b>不改任何</b>"什么情况算一次切换"的判据：
 * 拿着模块右键 = 切换投影（开着就关）这条<b>已验收的语义一个字都没动</b>，
 * 本类只决定"这一拍要不要真的落下去"。
 *
 * <h2>3. 为什么量"距上一次【被接受】的间隔"，而不是"距上一次【尝试】的间隔"</h2>
 * 在均匀连点上两者结果相同（都被压成第一拍）；但在"点一下、再点一下（间隔 249 ms）"上：
 * 前者按 250 ms 静默窗口判，后者按 249 ms 判 —— 数值上一致。
 * 真正区分它们的是这条硬性质：<b>凡是"被接受"的两拍，间隔恒 ≥ 250 ms</b>
 * —— 这正是"状态不会被两拍快速互相抵消"这句话的机器判据（见 {@link #selfTest} 的 D4/D5）。
 *
 * <h2>4. 边界口径（{@link #accept} 写的，不是猜的）</h2>
 * <pre>
 *   gap == 250 ms ⇒ 【接受】（判据是 "&lt; 250 才丢"，不是 "≤ 250 就丢"）
 *   gap == 249 ms ⇒ 【丢掉】
 *   第一次（还没接受过任何一拍）⇒ 一律接受
 *   时钟回退（now &lt; last）⇒ 一律接受（宁可放行，也不许把开关永久卡死）
 * </pre>
 */
public final class ShanhaiHoloMenuToggleGate {

    private ShanhaiHoloMenuToggleGate() {}

    /** 上一次<b>被接受</b>的那一拍的时刻（{@code System.nanoTime()}）；{@code 0} = 还没接受过。 */
    private static long lastAcceptedNanos;

    /** 上一次被<b>丢掉</b>的那一拍距上一次接受的间隔（毫秒）；{@code -1} = 从没丢过（读数用）。 */
    private static long lastGapMs = -1L;

    /** 累计丢掉了几拍（读数用）。 */
    private static int droppedCount;

    /**
     * 一次判定的结果。
     *
     * @param accepted 这一拍要不要真的执行
     * @param gapMs    <b>距上一次被接受</b>的间隔（毫秒）；第一次 / 时钟回退 ⇒ {@code -1}
     */
    public record Decision(boolean accepted, long gapMs) {
    }

    /**
     * 这一拍能不能过（窗口 = {@link ShanhaiHoloMenuTuning#TOGGLE_DEBOUNCE_MS}）。
     *
     * @param nowNanos 这一拍的时刻（{@code System.nanoTime()}）
     */
    public static Decision offer(long nowNanos) {
        return offer(nowNanos, ShanhaiHoloMenuTuning.TOGGLE_DEBOUNCE_MS);
    }

    /** 同上，窗口由参数给（<b>给离线自检用</b>；游戏路径恒用默认值）。 */
    public static Decision offer(long nowNanos, long windowMs) {
        final long gap = gapMsOf(lastAcceptedNanos, nowNanos);
        if (!accept(lastAcceptedNanos, nowNanos, windowMs)) {
            droppedCount++;
            lastGapMs = gap;
            return new Decision(false, gap);
        }
        lastAcceptedNanos = nowNanos;
        return new Decision(true, gap);
    }

    /**
     * <b>纯函数</b>：给"上一次被接受的时刻 / 现在 / 窗口（毫秒）"，判这一拍要不要接受。
     *
     * <p>它没有状态，所以离线自检可以逐格枚举边界值（见 {@link #selfTest} 的 D1–D3、D6），
     * 而不需要伪造时钟。
     */
    public static boolean accept(long lastAcceptedNanosValue, long nowNanos, long windowMs) {
        if (lastAcceptedNanosValue == 0L) {
            return true;                                    // 还没接受过任何一拍
        }
        if (windowMs <= 0L) {
            return true;                                    // 窗口关掉 = 不去抖
        }
        final long delta = nowNanos - lastAcceptedNanosValue;
        if (delta < 0L) {
            return true;                                    // 时钟回退：宁可放行
        }
        return delta >= windowMs * 1_000_000L;
    }

    /** 两个时刻之间隔了多少毫秒；上一次是 {@code 0}（没接受过）或时钟回退 ⇒ {@code -1}。 */
    public static long gapMsOf(long lastAcceptedNanosValue, long nowNanos) {
        if (lastAcceptedNanosValue == 0L) {
            return -1L;
        }
        final long delta = nowNanos - lastAcceptedNanosValue;
        return delta < 0L ? -1L : delta / 1_000_000L;
    }

    /**
     * 被丢掉时那一行的<b>正文</b>（不含 {@code [SHANHAI-HOLO] } 前缀 —— 前缀由调用方打）。
     *
     * <p>⚠️ <b>字段名固定</b>：{@code gap_ms=} / {@code dropped=}（用户任务书逐字点名，
     * 改一个字就等于把这条诊断抹掉）。
     */
    public static String debouncedLine(long gapMs) {
        return "holo_toggle_debounced gap_ms=" + gapMs + " dropped=true";
    }

    /** 累计丢掉几拍（读数用）。 */
    public static int droppedTotal() {
        return droppedCount;
    }

    /** 上一次被丢掉的间隔（毫秒；没丢过 = {@code -1}）。 */
    public static long lastDroppedGapMs() {
        return lastGapMs;
    }

    /** 一行读数（给日志用）。 */
    public static String describe() {
        return "toggle_debounce window_ms=" + ShanhaiHoloMenuTuning.TOGGLE_DEBOUNCE_MS
                + " dropped_total=" + droppedCount
                + " last_dropped_gap_ms=" + lastGapMs;
    }

    /** 清状态。<b>只在自检里调</b>（游戏路径上没有"重置去抖"这回事：它只跟 nanoTime 有关）。 */
    public static void resetForTest() {
        lastAcceptedNanos = 0L;
        lastGapMs = -1L;
        droppedCount = 0;
    }

    /**
     * <b>离线自检</b>（{@code /shanhai menu selftest} 也调它）。返回 {@code null} = 全过。
     *
     * <p>7 条判据，其中 <b>4 条是负对照</b>（故意构造"应当被拒 / 应当不同"的输入）：
     * 没有负对照，"全绿"可能只是判据从来不响。
     * <pre>
     *   D1 第一次（last=0）⇒ 接受
     *   D2 gap 249 ms ⇒ 丢掉；gap 250 ms ⇒ 接受（边界值，"{@code &lt; 250}" 的机器判据）
     *   D3 【负对照】gap 251 ms ⇒ 接受（证明 D2 的"250 接受"不是靠窗口失效换来的）
     *   D4 6 连点回放（间隔 0/100/200/300/400/500 ms，= 用户日志里那 6 次）
     *      ⇒ 只接受 2 拍、丢 4 拍，且被接受的两拍间隔 300 ms ≥ 250 ms
     *   D5 【负对照】窗口 = 0 ⇒ 一律接受（证明窗口参数真的在用，不是恒绿的）
     *   D6 时钟回退（now &lt; last）⇒ 接受（不许把开关永久卡死）
     *   D7 日志正文逐字等于 {@code holo_toggle_debounced gap_ms=137 dropped=true}
     *      （字段名一旦漂了，grep 就失效 —— 这条把它钉住）
     * </pre>
     */
    public static String selfTest() {
        final StringBuilder bad = new StringBuilder();
        final long ms = 1_000_000L;
        // D1 第一次
        if (!accept(0L, 123_456_789L, 250L)) {
            bad.append("D1 第一次被拒;");
        }
        // D2 边界：249 丢、250 接受
        if (accept(1_000L * ms, 1_000L * ms + 249L * ms, 250L)) {
            bad.append("D2a gap=249 没被丢;");
        }
        if (!accept(1_000L * ms, 1_000L * ms + 250L * ms, 250L)) {
            bad.append("D2b gap=250 被丢;");
        }
        // D3 负对照：251 必须接受
        if (!accept(1_000L * ms, 1_000L * ms + 251L * ms, 250L)) {
            bad.append("D3 负对照 gap=251 被丢;");
        }
        // D4 6 连点回放（状态机路径；时间戳由参数给 ⇒ 可离线复现）
        {
            resetForTest();
            final long base = 500_000L * ms;
            final long[] offsets = {0L, 100L, 200L, 300L, 400L, 500L};
            int accepted = 0;
            int dropped = 0;
            long prevAccept = -1L;
            for (long off : offsets) {
                final Decision d = offer(base + off * ms, 250L);
                if (!d.accepted()) {
                    dropped++;
                    continue;
                }
                accepted++;
                if (prevAccept >= 0L && base + off * ms - prevAccept < 250L * ms) {
                    bad.append("D4 接受了两拍间隔<250ms;");
                }
                prevAccept = base + off * ms;
            }
            if (accepted != 2 || dropped != 4) {
                bad.append("D4 6连点读数 accepted=").append(accepted)
                        .append(" dropped=").append(dropped).append("（应为 2/4）;");
            }
            // 被接受的那两拍之间必须隔 300 ms（0 与 300 那两拍）
            if (prevAccept - base != 300L * ms) {
                bad.append("D4 最后一拍接受时刻偏移=").append((prevAccept - base) / ms).append("ms;");
            }
            resetForTest();
        }
        // D5 负对照：窗口 0 ⇒ 恒接受
        if (!accept(1_000L * ms, 1_000L * ms + 1L, 0L)) {
            bad.append("D5 负对照 window=0 却拒了;");
        }
        // D6 时钟回退
        if (!accept(2_000L * ms, 1_000L * ms, 250L)) {
            bad.append("D6 时钟回退被拒;");
        }
        // D7 日志正文逐字
        if (!"holo_toggle_debounced gap_ms=137 dropped=true".equals(debouncedLine(137L))) {
            bad.append("D7 日志正文不符=").append(debouncedLine(137L)).append(';');
        }
        return bad.length() == 0 ? null : bad.toString();
    }
}
