package com.shanhai.add.hotfix;

/**
 * <b>一个目标的热改"账本"</b>：patch 落地时刻、窗口内撞见它几次、判过什么结论、
 * 重打过几次、还该不该再重打 —— 全部收在这一个对象里。
 *
 * <h2>它与 {@link HotfixPatchProbe} 的分工</h2>
 * <ul>
 *   <li>{@link HotfixPatchProbe}：<b>纯函数</b>（窗口判据／退避上限／限流／目标键）—— 没有状态、没有时钟；</li>
 *   <li>本类：<b>状态机</b>（把这些纯函数按时间轴串起来），也<b>不动手</b>
 *       —— 它只回答"下一步该干什么"，真正 {@code retransformClasses} 的是
 *       {@link HotfixAutoCounter}（唯一持有 {@code Instrumentation} 与白名单的地方）。</li>
 * </ul>
 * 这样拆的理由：判据最容易写成"只有它自己说对"的样子。
 * 拆出来之后，离线自检（{@code temp\hotfix\offline-probe\}）可以用<b>假时间轴</b>
 * 把"窗口内再出现⇒ineffective／窗口内没出现⇒ok／连续 5 次⇒give_up／第 6 次被拒"
 * 全部跑一遍，<b>不需要</b>真 patch、不需要等 5 秒、不需要 agent。
 *
 * <h2>一次 patch 的一生（{@link #poll(long)} 的返回值就是"剧本"）</h2>
 * <pre>
 *   onPatched(T0)                        ← 初次 patch 落地（或重打成功）
 *   poll(T0+…)   -&gt; null                ← 窗口还开着、还没撞见它
 *   noteHit(…)                           ← 我们的违规回调里撞见"这个方法还在跑"（抓栈时刻必须晚于 T0）
 *   poll(T0+…)   -&gt; PROBE_INEFFECTIVE   ← 判死（只报一次）
 *   poll(T0+…)   -&gt; RETALIATE           ← 可以重打（能不能真打由退避/上限判）
 *                -&gt; DEFER wait_ms=…    ← 还在退避里（同一个 wait 只报一次，不刷屏）
 *                -&gt; GIVE_UP            ← 5 次了 ⇒ 永久停手（此后 poll 一律 null）
 *   onRetaliated(T1, ok)                 ← 无论成败都要调它（失败也要计数，否则会死循环）
 * </pre>
 *
 * <p>纯 JDK：只有 {@code java.lang}/{@code java.util}，没有 ASM、没有 MC、没有 log4j、没有线程、不读时钟。
 * 所有"现在几点"都由调用方当参数送进来 ⇒ 离线可以把 60 秒的退避在几毫秒里跑完。
 */
public final class HotfixPatchWatch {

    /** {@link #poll(long)} 的回答：下一步该干什么。 */
    public enum Step {

        /** 判活：窗口关了、一次都没撞见 ⇒ 这次 patch 生效了（只报一次）。 */
        PROBE_OK,

        /** 判死：窗口内（或之后）又撞见"这个方法还在跑"（只报一次）。 */
        PROBE_INEFFECTIVE,

        /** 该重打，但还在指数退避里（等 {@link Action#waitMillis}）。 */
        DEFER,

        /** 该重打，现在就可以动手。 */
        RETALIATE,

        /** 顶到"60 秒 5 次"上限 ⇒ 调用方记一行 {@code retaliate_give_up} 后永久停手。 */
        GIVE_UP
    }

    /** 一次判定的输出（不可变纯数据）。 */
    public static final class Action {

        /** 下一步。 */
        public final Step step;

        /** {@code 类#方法}（与日志里 {@code target=} 逐字一致）。 */
        public final String target;

        /**
         * 这一行日志该不该打（<b>1/s 限流</b>已经在这里判过了）。
         *
         * <p>为什么限流放在状态机里而不是日志出口：{@code poll} 被工作线程按 tick 调用
         * （默认 250 ms 一次），不在这里挡一下的话 {@link Step#DEFER} 会一秒刷四行。
         * 调用方只管"loggable 才打"，不需要知道限流规则。
         */
        public final boolean loggable;

        /** 自上次 patch 起一共撞见过几次（进日志的 {@code evidence=violations_after_patch=}）。 */
        public final int hits;

        /** 已经重打过几次（不含初次 patch）。 */
        public final int attempt;

        /** {@link Step#RETALIATE}／{@link Step#DEFER} 时：这一次是第几次（1 起）。 */
        public final int nextAttempt;

        /** {@link Step#DEFER} 时：还差多少毫秒。 */
        public final long waitMillis;

        /** 这一次适用的退避值（判读"退避真的在指数增长"用）。 */
        public final long backoffMillis;

        /** 距 patch 落地过了多久（判"是窗口内撞见的还是迟到撞见的"）。 */
        public final long sincePatchMillis;

        /** 最近一次撞见的 {@code where}（{@code health|die|…}）。 */
        public final String where;

        Action(Step step, String target, boolean loggable, int hits, int attempt, int nextAttempt,
               long waitMillis, long backoffMillis, long sincePatchMillis, String where) {
            this.step = step;
            this.target = target;
            this.loggable = loggable;
            this.hits = hits;
            this.attempt = attempt;
            this.nextAttempt = nextAttempt;
            this.waitMillis = waitMillis;
            this.backoffMillis = backoffMillis;
            this.sincePatchMillis = sincePatchMillis;
            this.where = where;
        }

        @Override
        public String toString() {
            return "step=" + step + " target=" + target + " hits=" + hits + " attempt=" + attempt
                    + " next=" + nextAttempt + " wait_ms=" + waitMillis
                    + " since_patch_ms=" + sincePatchMillis + " loggable=" + loggable;
        }
    }

    // ---- 内部状态常量

    private static final int RESULT_NONE = 0;
    private static final int RESULT_OK = 1;
    private static final int RESULT_BAD = 2;

    /** 记多少个重打时刻（上限 5 次，留一倍余量就够 prune 用）。 */
    private static final int MAX_TRACKED_ATTEMPTS = 8;

    private final String className;
    private final String methodName;
    private final String target;

    /** 窗口开着吗（开着 = 还没下结论）。 */
    private boolean windowOpen;

    /** 最近一次 patch／重打落地的时刻。 */
    private long patchedAtMillis;

    /** 自那次落地以来撞见过几次。 */
    private int hits;

    /** 最近一次撞见的抓栈时刻／{@code where}。 */
    private long lastHitMillis;
    private String lastWhere = "?";

    /** {@link #RESULT_NONE}/{@link #RESULT_OK}/{@link #RESULT_BAD}。 */
    private int lastResult = RESULT_NONE;

    /** 判死这件事报过没有（只报一次；迟到再撞见会重新置 false ⇒ 再报一次）。 */
    private boolean badReported;

    /** 已经重打过几次（不含初次 patch）。 */
    private int attempt;

    /** 重打时刻表（升序；用于 60 秒滑窗计数）。 */
    private final long[] attemptMillis = new long[MAX_TRACKED_ATTEMPTS];
    private int attemptCount;

    /** 最近一次"动手"的时刻（初次 patch 或上一次重打）—— 退避从它起算。 */
    private long lastEventMillis;

    /** 停手了吗（顶到上限 ⇒ 永久 true，直到这个 watch 被退役）。 */
    private boolean givenUp;

    /** 上一次报出去的退避等待（同一个 wait 只报一次）。 */
    private long lastDeferWaitMillis = -1L;

    /**
     * 这一轮"该重打但还在退避"报过了吗（<b>每轮只报一行</b>）。
     *
     * <p>⚠️ 不能用"wait 值变了才报"当判据：{@code wait = backoff - 已经等了多久} 每 tick 都在变小，
     * 那个判据等于<b>每 tick 一行</b>（4 行/秒）—— 正是用户点名禁止的刷屏。
     */
    private boolean deferLogged;

    /** 上一次打探针行的时刻（1/s 限流）。 */
    private long lastLogMillis;

    /**
     * @param className  源级类名（{@code examplemod.util.ExampleHelper}）
     * @param methodName 方法名（{@code doHurt}）
     */
    public HotfixPatchWatch(String className, String methodName) {
        this.className = className == null ? "" : className;
        this.methodName = methodName == null ? "" : methodName;
        this.target = HotfixPatchProbe.key(this.className, this.methodName);
    }

    // ═══════════════════════════════ 外部事件（都由调用方送进来） ═══════════════════════════════

    /**
     * <b>patch 落地</b>：开一个新窗口（初次 patch 与"重打成功"走同一条路，区别只在后者先调
     * {@link #onRetaliated(long, boolean, int)}）。
     *
     * <p>⚠️ 不清 {@link #givenUp()}：一旦停手就是这个 target 的最终决定，
     * 要重新开始得先把 watch 退役（手动 {@code undo} 会退役）。
     *
     * @param nowMillis 落地时刻
     */
    public void onPatched(long nowMillis) {
        windowOpen = true;
        patchedAtMillis = nowMillis;
        hits = 0;
        lastResult = RESULT_NONE;
        badReported = false;
        lastEventMillis = nowMillis;
        lastLogMillis = 0L;          // 新窗口的第一条结论必须打得出来
        lastDeferWaitMillis = -1L;
        deferLogged = false;
    }

    /**
     * <b>重打结果记账</b>（无论成没成都必须调 —— 失败也要计数，否则"永远失败的重打"会变成死循环）。
     *
     * @param nowMillis 动手时刻
     * @param ok        {@code true} = 这次重打真的落地了（新窗口从这一刻起算）
     * @param attemptNo 这一次是第几次（日志里的 {@code attempt=}，让它和动作一一对应）
     */
    public void onRetaliated(long nowMillis, boolean ok, int attemptNo) {
        attempt = Math.max(attempt, attemptNo);
        recordAttempt(nowMillis);
        lastEventMillis = nowMillis;
        lastDeferWaitMillis = -1L;
        deferLogged = false;
        if (ok) {
            onPatched(nowMillis);
            attempt = Math.max(attempt, attemptNo);
        }
        // ok=false：lastResult 保持 BAD ⇒ 下一轮按退避再试（退避从 nowMillis 起算）
    }

    /**
     * <b>撞见它还在跑</b>（违规回调里调；必须给<b>抓栈时刻</b>，不能给"现在"）。
     *
     * @param capturedAtMillis 这一条违规的抓栈时刻
     * @param where            {@code health|die|remove|motion|pos|attack}
     * @return 自上次 patch 起累计撞见几次
     */
    public int noteHit(long capturedAtMillis, String where) {
        if (givenUp) {
            return hits;
        }
        // 抓栈时刻必须严格晚于 patch 落地 —— 队列里躺着的老现场不算证据
        if (!HotfixPatchProbe.afterPatch(patchedAtMillis, capturedAtMillis)) {
            return hits;
        }
        hits++;
        lastHitMillis = capturedAtMillis;
        lastWhere = where == null || where.isEmpty() ? "?" : where;
        // 迟到也算：窗口关了之后又见它跑，同样是"没生效"的证据（重新判死一次）
        if (!windowOpen && lastResult == RESULT_OK) {
            lastResult = RESULT_BAD;
            badReported = false;
        }
        return hits;
    }

    /**
     * <b>推进一格</b>：按当前时间算出"下一步该干什么"。
     *
     * @param nowMillis 现在
     * @return 需要动作 ⇒ {@link Action}；什么都不用做 ⇒ {@code null}
     */
    public Action poll(long nowMillis) {
        // ---- ① 窗口期：要么还没结论，要么刚刚判出来
        if (windowOpen) {
            if (hits > 0) {
                windowOpen = false;                  // 有证据就不用等满 5 秒
                lastResult = RESULT_BAD;
                badReported = false;
            } else if (!HotfixPatchProbe.windowOpen(patchedAtMillis, nowMillis)) {
                windowOpen = false;
                lastResult = RESULT_OK;
                return action(Step.PROBE_OK, nowMillis, 0, 0L, 0L);
            } else {
                return null;                         // 窗口还开着，什么都别做
            }
        }
        if (givenUp) {
            return null;
        }
        // ---- ② 判死（只报一次）
        if (lastResult == RESULT_BAD && !badReported) {
            badReported = true;
            return action(Step.PROBE_INEFFECTIVE, nowMillis, 0, 0L, 0L);
        }
        if (lastResult != RESULT_BAD) {
            return null;
        }
        // ---- ③ 反制：先判上限，再判退避（顺序在 HotfixPatchProbe 里固定）
        final HotfixPatchProbe.Throttle t = HotfixPatchProbe.judgeRetaliate(
                attemptMillis, attemptCount, lastEventMillis, nowMillis);
        if (t.atCap()) {
            givenUp = true;
            lastResult = RESULT_NONE;
            return action(Step.GIVE_UP, nowMillis, t.attempts, 0L, t.backoffMillis);
        }
        if (!t.allowed) {
            final boolean log = !deferLogged;
            deferLogged = true;
            lastDeferWaitMillis = t.waitMillis;
            final Action a = action(Step.DEFER, nowMillis, t.nextAttempt, t.waitMillis, t.backoffMillis);
            return log ? a : new Action(a.step, a.target, false, a.hits, a.attempt, a.nextAttempt,
                    a.waitMillis, a.backoffMillis, a.sincePatchMillis, a.where);
        }
        lastDeferWaitMillis = -1L;
        deferLogged = false;
        return action(Step.RETALIATE, nowMillis, t.nextAttempt, 0L, t.backoffMillis);
    }

    // ═══════════════════════════════ 读数 ═══════════════════════════════

    /** {@code 类#方法}。 */
    public String target() {
        return target;
    }

    /** 源级类名。 */
    public String className() {
        return className;
    }

    /** 方法名。 */
    public String methodName() {
        return methodName;
    }

    /** 停手了吗。 */
    public boolean givenUp() {
        return givenUp;
    }

    /** 已经重打过几次。 */
    public int attempts() {
        return attempt;
    }

    /** 自上次 patch 起撞见过几次。 */
    public int hits() {
        return hits;
    }

    /** 最近一次撞见的 {@code where}。 */
    public String lastWhere() {
        return lastWhere;
    }

    /** 最近一次"动手"的时刻。 */
    public long lastEventMillis() {
        return lastEventMillis;
    }

    /** 最近一次 patch 落地时刻。 */
    public long patchedAtMillis() {
        return patchedAtMillis;
    }

    /** 一行读数（{@code /shanhai_add hotfix status} 用；不含空格以外的问题字符）。 */
    public String brief() {
        return target + " hits=" + hits + " attempts=" + attempt
                + (givenUp ? " given_up=true" : "")
                + " last_event_ms=" + (lastEventMillis <= 0L ? "?" : String.valueOf(lastEventMillis));
    }

    // ═══════════════════════════════ 内部 ═══════════════════════════════

    /** 组一个 Action（顺手把 1/s 限流用掉）。 */
    private Action action(Step step, long nowMillis, int nextAttempt, long waitMillis, long backoffMillis) {
        boolean loggable;
        if (step == Step.PROBE_OK || step == Step.PROBE_INEFFECTIVE) {
            loggable = HotfixPatchProbe.logAllowed(lastLogMillis, nowMillis);
            if (loggable) {
                lastLogMillis = nowMillis;
            }
        } else {
            // DEFER / RETALIATE / GIVE_UP：由调用方各自决定（DEFER 已在 poll 里改成"同一个 wait 只报一次"）
            loggable = true;
        }
        return new Action(step, target, loggable, hits, attempt, nextAttempt, waitMillis, backoffMillis,
                patchedAtMillis <= 0L ? -1L : nowMillis - patchedAtMillis, lastWhere);
    }

    /** 把一次重打时刻记进滑窗表（满了就整体左移一格）。 */
    private void recordAttempt(long nowMillis) {
        if (attemptCount < attemptMillis.length) {
            attemptMillis[attemptCount++] = nowMillis;
            return;
        }
        System.arraycopy(attemptMillis, 1, attemptMillis, 0, attemptMillis.length - 1);
        attemptMillis[attemptMillis.length - 1] = nowMillis;
    }
}
