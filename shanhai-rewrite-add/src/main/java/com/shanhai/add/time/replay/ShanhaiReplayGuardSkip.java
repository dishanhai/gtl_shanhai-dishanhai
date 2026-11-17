package com.shanhai.add.time.replay;

/**
 * 山海重构 · <b>「回放期间的取证/刹车豁免」判定核</b>（🔴 零 Minecraft 依赖，别加 import）。
 *
 * <h2>1. 真机噪声（2026-11-14 13:31）</h2>
 * <pre>
 *   [SHANHAI-GUARD] violation_stack where=motion target=dgyjh02804 first_external=none frames=16
 * </pre>
 * 每秒一条，target 还是玩家 —— 而那一刻是<b>我们自己在替倒流写世界</b>
 * （{@code ShanhaiReplayGate#inReplayWrite()} 为真）。这些栈没有任何信息量，
 * 还会把真正的"外部改写"证据淹掉。
 *
 * <h2>2. 豁免面只有两条（用户口径："不许放宽回放之外的判定"）</h2>
 * <ul>
 *   <li>条件：<b>当前线程正在回放写临界区里</b>（不是"暂停"，也不是"倒流中"）；</li>
 *   <li>范围：只有 {@code motion} / {@code motion_brake} 这两条"动量证据"。</li>
 * </ul>
 * {@code health} / {@code die} / {@code attack} / {@code remove} 一律照抓 ——
 * 那些不是我们造成的，豁免了就是把安全线拆了。
 *
 * <p>判据（自检 W 组旁）：回放写为真 ⇒ motion/motion_brake 跳过、health 不跳过；
 * 回放写为假 ⇒ 一条都不跳过（负对照）。
 */
public final class ShanhaiReplayGuardSkip {

    /** 被豁免的证据类型（只有动量两条）。 */
    public static final String[] EXEMPT = {"motion", "motion_brake"};

    /**
     * 这一条证据要不要跳过。
     *
     * @param where         证据类型（{@code health|die|remove|motion|motion_brake|pos|attack}）
     * @param inReplayWrite 当前线程是否处在"替回放写世界"的临界区
     */
    public static boolean skip(String where, boolean inReplayWrite) {
        if (!inReplayWrite || where == null) {
            return false;
        }
        for (String w : EXEMPT) {
            if (w.equals(where)) {
                return true;
            }
        }
        return false;
    }

    /** 一行读数（限流由调用方做：1 行 / 5 秒）。 */
    public static String line(String where, long total) {
        return "guard_motion_skip where=" + where + " reason=replay_write total=" + total;
    }

    /** 按类型累计被跳过的条数（返回累计值）。 */
    public static long note(String where) {
        synchronized (COUNTS) {
            final Long old = COUNTS.get(where);
            final long n = (old == null ? 0L : old.longValue()) + 1L;
            COUNTS.put(where, Long.valueOf(n));
            TOTAL += 1L;
            return n;
        }
    }

    /** 累计跳过条数（{@code /time status} 与日志判读用）。 */
    public static long total() {
        synchronized (COUNTS) {
            return TOTAL;
        }
    }

    private static final java.util.Map<String, Long> COUNTS = new java.util.HashMap<>();
    private static long TOTAL;

    private ShanhaiReplayGuardSkip() {
    }
}
