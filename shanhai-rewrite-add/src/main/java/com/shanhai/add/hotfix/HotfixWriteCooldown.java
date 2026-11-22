package com.shanhai.add.hotfix;

import java.util.HashMap;
import java.util.Map;

/**
 * 🔴 <b>写字节的按类冷却</b>（2026-11-21 P2）：同一类 ＋ <b>同一批目标方法集合</b>
 * 在 {@value #WINDOW_TICKS} tick 内最多被我们写 {@value #MAX_WRITES} 次。
 *
 * <h2>为什么要有这一道（现场读数，不是推测）</h2>
 * <pre>
 *   现场（2026-11-21 08:5x）：patch_write=144 次、chain_wipe_write=8 次
 *   ⇒ "Can't keep up! Running 5597ms or 111 ticks behind" × 6 次
 *   ⇒ holo_frame_gap ms=78534（78 秒）
 * </pre>
 * <p>同一类、同一批方法在几百毫秒内被反复重写时，<b>没有任何语义收益</b>：
 * {@code retransformClasses} 是整类重写，JVM 还要把所有已 JIT 的版本作废
 * ⇒ 玩家的体感就是"时间暂停"。所以这里只挡<b>重复写</b>。
 *
 * <h2>🔴 判据（一个字都不许放宽，否则会把"该写的那次"也挡掉）</h2>
 * <pre>
 *   键 = 类名 ＋ 目标方法集合（{@code 类#方法#描述符} 排序去重后拼成一串）
 *   ⇒ 同一类、<b>新增了方法</b>（集合变了）⇒ 键不同 ⇒ 一律放行（首次写不受影响）
 *   ⇒ 同一类、同一个集合、窗口内已写 {@value #MAX_WRITES} 次 ⇒ 才挡，并记一行
 *        {@code write_cooldown class=<类名> skipped=<窗口内第几次被挡>}
 * </pre>
 *
 * <h2>窗口为什么用墙钟</h2>
 * <p>写字节发生在热改引擎的<b>工作线程</b>上（那里没有 level、没有 gameTime），
 * 所以"200 tick"按 {@code 50 ms/tick} 折成 {@value #WINDOW_MILLIS} ms —— 与
 * {@code HotfixJudgeWatch.WAIT_TICK_MILLIS} 同一个口径。<b>这是折算，不是真 tick</b>，
 * 如实写在这里。
 *
 * <h2>依赖面</h2>
 * 纯 {@code java.*} ⇒ 离线自检（{@code temp\hotfix\p2-readclasscooldown\}）加载的是<b>同一份</b>字节码。
 */
public final class HotfixWriteCooldown {

    /** 日志行首（逐字；{@code write_cooldown class=<类名> skipped=<N>}）。 */
    public static final String LINE = "write_cooldown";

    /** 冷却窗口（tick）。 */
    public static final int WINDOW_TICKS = 200;

    /** 1 tick 折算的毫秒数（20 TPS）。 */
    public static final long TICK_MILLIS = 50L;

    /** 冷却窗口（毫秒）＝ {@value #WINDOW_TICKS} × {@value #TICK_MILLIS}。 */
    public static final long WINDOW_MILLIS = WINDOW_TICKS * TICK_MILLIS;

    /** 窗口内最多写几次（第 3 次起挡）。 */
    public static final int MAX_WRITES = 2;

    /** 键 ⇒ 窗口状态（类 ＋ 目标方法集合；条数有限，不清理也一样有界）。 */
    private static final Map<String, State> STATE = new HashMap<String, State>();

    private HotfixWriteCooldown() {
        // 纯静态
    }

    /** 一个键在当前窗口里的状态。 */
    private static final class State {

        /** 窗口起点（墙钟毫秒）。 */
        long windowStart;

        /** 本窗口里<b>真的写成功</b>了几次。 */
        int writes;

        /** 本窗口里被挡了几次（进 {@code skipped=}）。 */
        int skipped;
    }

    /**
     * 一次"能不能写"的结论。
     *
     * @see #admit(String, String, long)
     */
    public static final class Decision {

        /** {@code true} = 放行（可以写）。 */
        public final boolean ok;

        /** {@code ok=false} 时：本窗口里第几次被挡（{@code skipped=} 取它）。 */
        public final int skipped;

        /** {@code ok=false} 时的整行日志（{@code write_cooldown class=… skipped=N}）。 */
        public final String line;

        Decision(boolean ok, int skipped, String line) {
            this.ok = ok;
            this.skipped = skipped;
            this.line = line;
        }

        @Override
        public String toString() {
            return ok ? "ok=true" : "ok=false " + line;
        }
    }

    /**
     * 问一次"这个类 ＋ 这批目标方法现在还能写吗"。
     *
     * <p>⚠️ 只有 {@link #recordWritten} 记过的写才计数 —— <b>写失败不算</b>
     * （失败的那一趟一个字节都没交，不该拿它去挡后面的真写）。
     *
     * @param className 源级类名（{@code null}/空 ⇒ 用一个占位名，仍然照常限流）
     * @param targetKey 目标方法集合的稳定串（同一批 ⇒ 同一串；见 {@code HotfixAutoCounter#targetSetKey}）
     * @param atMillis  这一拍的墙钟毫秒
     * @return 放行 ⇒ {@code ok=true}；挡下 ⇒ {@code ok=false} ＋ 逐字日志行
     */
    public static Decision admit(String className, String targetKey, long atMillis) {
        final String cn = className == null || className.isEmpty() ? "?" : className;
        final String key = key(cn, targetKey);
        synchronized (STATE) {
            final State st = window(cn, key, atMillis);
            if (st.writes >= MAX_WRITES) {
                st.skipped++;
                return new Decision(false, st.skipped, LINE + " class=" + cn + " skipped=" + st.skipped);
            }
            return new Decision(true, 0, "");
        }
    }

    /**
     * 记一次<b>真的写成功</b>（{@code ok=true} 那一趟之后调）。
     *
     * @param className 源级类名
     * @param targetKey 与 {@link #admit} 逐字相同的目标集合串
     * @param atMillis  写完的时刻（窗口以它为准；跨窗口时重开一个窗口）
     */
    public static void recordWritten(String className, String targetKey, long atMillis) {
        final String cn = className == null || className.isEmpty() ? "?" : className;
        final String key = key(cn, targetKey);
        synchronized (STATE) {
            window(cn, key, atMillis).writes++;
        }
    }

    /**
     * 读数（只进日志/离线自检；不改任何状态）。
     *
     * @param className 源级类名
     * @param targetKey 目标集合串
     * @return {@code writes=… skipped=… window_ms=… max=2}
     */
    public static String readings(String className, String targetKey) {
        final String cn = className == null || className.isEmpty() ? "?" : className;
        final String key = key(cn, targetKey);
        synchronized (STATE) {
            final State st = STATE.get(key);
            return "writes=" + (st == null ? 0 : st.writes)
                    + " skipped=" + (st == null ? 0 : st.skipped)
                    + " window_ms=" + WINDOW_MILLIS + " max=" + MAX_WRITES;
        }
    }

    /** 清空全部窗口（离线自检用；游戏里没有调用点）。 */
    public static void reset() {
        synchronized (STATE) {
            STATE.clear();
        }
    }

    // ═══════════════════════════════ 内部 ═══════════════════════════════

    /** 取（必要时新建/滚动）这个键的窗口状态。调用方必须已持 {@link #STATE} 的锁。 */
    private static State window(String className, String key, long atMillis) {
        State st = STATE.get(key);
        // 时钟回跳（NTP 校正 / 用户改表）⇒ 也重开窗口，绝不因此永久挡住
        if (st == null || atMillis < st.windowStart || atMillis - st.windowStart >= WINDOW_MILLIS) {
            st = new State();
            st.windowStart = atMillis;
            STATE.put(key, st);
        }
        return st;
    }

    /** 键 ＝ 类名 ＋ '|' ＋ 目标集合串。 */
    private static String key(String className, String targetKey) {
        return className + '|' + (targetKey == null ? "" : targetKey);
    }
}
