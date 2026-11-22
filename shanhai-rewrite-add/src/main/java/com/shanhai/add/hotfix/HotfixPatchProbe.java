package com.shanhai.add.hotfix;

/**
 * <b>生效探针的纯判定</b>：① 「patch 之后这个方法是不是真的不跑了」的 5 秒窗口判据；
 * ② 重打的<b>指数退避 ＋ 上限</b>；③ 日志的 1/s 限流；④ 目标键的规范化。
 *
 * <h2>为什么要有这个类（而不是把判据写在 {@link HotfixAutoCounter} 里）</h2>
 * 2026-11-08 12:41 的真实现场：patch 的账本说 {@code result=patched}，
 * 而运行中的类<b>一点没变</b>（{@code doHurt:375} 还在调 {@code setHealth}，
 * 29 条 {@code setHealth_guard value_was=-57.0}）。
 * 也就是说「改成功了」这件事<b>从来没人验证过</b>。
 * 而这类判据最容易写成"只有它自己说对"的样子，所以这里把它抽成
 * <b>纯 JDK（只有 {@code java.lang}/{@code java.util}，没有 ASM、没有 MC、没有 log4j、没有线程）</b>
 * 的函数 —— 这样它能被<b>脱离游戏、脱离 agent、脱离 Instrumentation</b> 的离线自检直接驱动，
 * 用假时间轴把每一条边界都跑一遍（{@code temp\hotfix\offline-probe\}）。
 *
 * <h2>判据 ①（核心）：5 秒窗口</h2>
 * <pre>
 *   patch 在 T0 落地 ⇒ 开一个 [T0, T0+5 000] 的窗口；
 *   窗口内只要再来一条 <b>抓栈时刻晚于 T0</b> 的违规、且栈里<b>还有这个 target</b> ⇒ ineffective
 *   —— 理由：那个方法体如果真被换成了 RETURN，它<b>根本没法</b>再出现在调用 {@code setHealth} 的栈上。
 *   窗口关了都没再见它 ⇒ ok
 * </pre>
 * {@code 5 000 ms} 这个数的依据：本体/守卫侧抓栈是<b>按拍</b>的（50 ms 一拍），
 * 一场战斗里同一条链路每拍都可能撞上，5 s 是 100 拍，远大于"补丁生效要等下一次 JIT/deopt"那种延迟；
 * 再长就会跨过战斗阶段（旧栈指向的方法那时可能已经不跑了），得到假 ineffective。
 *
 * <h2>判据 ②：退避 ＋ 上限（绝不打成无限循环）</h2>
 * <pre>
 *   同一 target 60 秒内最多重打 5 次；两次之间至少等 backoffMillis(已重打次数)：
 *     第 1 次前 1 000 ms、第 2 次前 2 000、第 3 次 4 000、第 4 次 8 000、第 5 次 16 000（封顶）
 *   ⇒ 5 次的总等待 31 s &lt; 60 s，所以"5 次上限"不会先被退避挡死；
 *   第 6 次直接判 throttled（调用方记一行 retaliate_give_up 后<b>永久停手</b>）。
 * </pre>
 * 🔴 <b>两条硬纪律</b>：① 这里是"能不能动手"的<b>唯一</b>判据，调用方不许自己再放宽；
 * ② 计数含<b>失败的重打</b>（失败也在消耗对手的容忍度），否则一个永远失败的重打会变成死循环。
 *
 * <h2>可以离线验的部分（就是全部）</h2>
 * 本类<b>没有任何状态、没有时间源</b>：所有函数都把"现在几点"当参数收进来。
 * ⇒ 离线自检不需要等 5 秒、不需要真 patch，就能把窗口/退避/上限/限流全部跑完。
 */
public final class HotfixPatchProbe {

    // ═══════════════════════════════ 常量（规格逐字） ═══════════════════════════════

    /** 生效探针的窗口：patch 之后这么多毫秒内又撞见同 target 的违规 ⇒ ineffective。 */
    public static final long WINDOW_MILLIS = 5_000L;

    /** 同一 target 在 {@link #RETALIATE_WINDOW_MILLIS} 内最多重打几次。 */
    public static final int MAX_RETALIATE_ATTEMPTS = 5;

    /** 重打计数的滑动窗口（60 秒）。 */
    public static final long RETALIATE_WINDOW_MILLIS = 60_000L;

    /** 第一次重打前的退避基数。 */
    public static final long BACKOFF_BASE_MILLIS = 1_000L;

    /** 退避封顶（2^4 × 1 000 = 16 000）。 */
    public static final long BACKOFF_MAX_MILLIS = 16_000L;

    /** 退避的最大移位次数（1 000 ≪ 4 = 16 000，再移就超过封顶了）。 */
    public static final int BACKOFF_MAX_SHIFT = 4;

    /** 同一个 key 的日志最快多久一行（1/s 限流；用户口径："不许让日志每 tick 刷屏"）。 */
    public static final long LOG_MIN_INTERVAL_MILLIS = 1_000L;

    /** 探针窗口还开着（还得再等）。 */
    public static final String PENDING = "pending";

    /** 判死：patch 没有生效。 */
    public static final String INEFFECTIVE = "ineffective";

    /** 判活：窗口内没再见它 ⇒ 这次 patch 生效了。 */
    public static final String OK = "ok";

    /** 重打：这一次可以动手。 */
    public static final String REASON_OK = "ok";

    /** 重打：还在指数退避里，等够了再来。 */
    public static final String REASON_BACKOFF = "backoff_wait";

    /** 重打：这次数已经顶到上限 ⇒ 调用方必须停手（{@code retaliate_give_up}）。 */
    public static final String REASON_THROTTLED = "throttled";

    private HotfixPatchProbe() {
        // 纯静态判定，没有实例
    }

    // ═══════════════════════════════ 判据 ①：生效窗口 ═══════════════════════════════

    /**
     * 判一个探针窗口（纯函数：不读时钟、不写任何状态）。
     *
     * @param patchedAtMillis patch 落地时刻（{@link System#currentTimeMillis()} 口径）
     * @param nowMillis       现在
     * @param hits            窗口内撞见的"同 target 违规"条数（调用方只在该计入时才累加）
     * @return {@link #INEFFECTIVE}（撞见过）／{@link #PENDING}（窗口还开着、还没撞见）／
     *         {@link #OK}（窗口关了、一次都没撞见）
     */
    public static String judgeWindow(long patchedAtMillis, long nowMillis, int hits) {
        // 🔴 顺序有讲究：先看证据、再看时间。
        //    "窗口关了之后才被算进来的命中"（老现场、抓栈时刻早于 patch 的那种）在调用方就被
        //    挡掉了；能走到这里还 hits>0，就是"真跑过了" ⇒ 一律判死，不看窗口。
        if (hits > 0) {
            return INEFFECTIVE;
        }
        if (!windowOpen(patchedAtMillis, nowMillis)) {
            return OK;
        }
        return PENDING;
    }

    /**
     * 窗口还开着吗（{@code T0} 那一瞬间算开着；{@code T0+5 000} 也算开着 —— 闭区间，
     * 这样边界上不会有"既不是 pending 也不是 ok"的空洞）。
     *
     * @param patchedAtMillis patch 落地时刻
     * @param nowMillis       现在
     * @return {@code true} = 还没到下结论的时候
     */
    public static boolean windowOpen(long patchedAtMillis, long nowMillis) {
        final long elapsed = nowMillis - patchedAtMillis;
        if (elapsed < 0L) {
            return true;      // 时钟倒退（或 T0 来自另一台机器的时钟）：宁可不判，也不判错
        }
        return elapsed <= WINDOW_MILLIS;
    }

    /** 这个窗口的截止时刻（给调度用：什么时候必须醒过来判一次）。 */
    public static long windowDeadline(long patchedAtMillis) {
        return patchedAtMillis + WINDOW_MILLIS;
    }

    /**
     * 这条违规算不算"patch 之后发生的"（抓栈时刻必须<b>严格晚于</b> patch 落地时刻）。
     *
     * <p>为什么要卡这一条：违规现场是<b>先抓栈、再投递</b>的，队列里躺着的那几条往往是
     * patch 之前就抓好的；把它们算成"patch 之后还在跑"就会得到<b>假 ineffective</b>
     * ⇒ 白重打一次（虽然被上限挡住，但日志会误导排查）。
     *
     * @param patchedAtMillis patch 落地时刻
     * @param capturedAtMillis 这一条违规的抓栈时刻
     * @return {@code true} = 这条证据在 patch 之后
     */
    public static boolean afterPatch(long patchedAtMillis, long capturedAtMillis) {
        return capturedAtMillis > patchedAtMillis;
    }

    // ═══════════════════════════════ 判据 ②：退避 ＋ 上限 ═══════════════════════════════

    /**
     * 第 {@code priorAttempts + 1} 次重打之前至少要等多久（指数退避，封顶 {@link #BACKOFF_MAX_MILLIS}）。
     *
     * @param priorAttempts 已经重打过的次数（{@code 0} = 还没重打过）
     * @return 毫秒
     */
    public static long backoffMillis(int priorAttempts) {
        if (priorAttempts <= 0) {
            return BACKOFF_BASE_MILLIS;
        }
        final int shift = Math.min(priorAttempts, BACKOFF_MAX_SHIFT);
        final long ms = BACKOFF_BASE_MILLIS << shift;
        return Math.min(ms, BACKOFF_MAX_MILLIS);
    }

    /**
     * 现在能不能重打一次（纯函数）。
     *
     * @param attemptMillis   已经重打过的时刻表（升序；未使用的槽写 {@code 0}），可为 {@code null}
     * @param count           表里有效条数
     * @param lastEventMillis 最近一次<b>动手</b>的时刻（初次 patch 或上一次重打）——
     *                        退避是从它起算的，{@code <= 0} 表示"没有这个信息，不按它等"
     * @param nowMillis       现在
     * @return 判定结果（{@link Throttle#allowed} ＋ 机器可判的 {@link Throttle#reason}）
     */
    public static Throttle judgeRetaliate(long[] attemptMillis, int count, long lastEventMillis, long nowMillis) {
        int inWindow = 0;
        long newest = 0L;
        final int n = attemptMillis == null ? 0 : Math.min(Math.max(count, 0), attemptMillis.length);
        for (int i = 0; i < n; i++) {
            long t = attemptMillis[i];
            if (t <= 0L) {
                continue;                       // 空槽
            }
            if (t > nowMillis) {
                t = nowMillis;                  // 时钟倒退：当作刚发生（宁可多等，不可少等）
            }
            if (nowMillis - t <= RETALIATE_WINDOW_MILLIS) {
                inWindow++;
                if (t > newest) {
                    newest = t;
                }
            }
        }
        long last = Math.max(newest, lastEventMillis);
        if (last > nowMillis) {
            last = nowMillis;
        }
        final long backoff = backoffMillis(inWindow);
        // ---- ① 上限：先判它。也就是"60 秒内 5 次"这条红线优先于退避
        if (inWindow >= MAX_RETALIATE_ATTEMPTS) {
            return new Throttle(false, REASON_THROTTLED, inWindow, 0, 0L, backoff);
        }
        // ---- ② 退避
        if (last > 0L) {
            final long since = nowMillis - last;
            if (since < backoff) {
                return new Throttle(false, REASON_BACKOFF, inWindow, inWindow + 1, backoff - since, backoff);
            }
        }
        // ---- ③ 放行
        return new Throttle(true, REASON_OK, inWindow, inWindow + 1, 0L, backoff);
    }

    // ═══════════════════════════════ 判定 ③：日志 1/s 限流 ═══════════════════════════════

    /**
     * 这次日志允许打吗（同一个 key 最快 1 s 一行）。
     *
     * @param lastLogMillis 上一次打的时刻；{@code <= 0} = 从没打过
     * @param nowMillis     现在
     * @return {@code true} = 可以打（调用方随后要把 {@code nowMillis} 记回去）
     */
    public static boolean logAllowed(long lastLogMillis, long nowMillis) {
        if (lastLogMillis <= 0L) {
            return true;
        }
        if (nowMillis < lastLogMillis) {
            return true;                        // 时钟倒退：宁可多打一行，也不许永远打不出来
        }
        return nowMillis - lastLogMillis >= LOG_MIN_INTERVAL_MILLIS;
    }

    // ═══════════════════════════════ ④ 目标键（纯文本） ═══════════════════════════════

    /**
     * 目标的规范写法 {@code 类名#方法名}（与日志里 {@code target=} 逐字一致）。
     *
     * @param className  源级类名
     * @param methodName 方法名
     * @return {@code 类#方法}；两个都空 ⇒ {@code "?"}
     */
    public static String key(String className, String methodName) {
        final String c = className == null ? "" : className.trim();
        final String m = methodName == null ? "" : methodName.trim();
        if (c.isEmpty() && m.isEmpty()) {
            return "?";
        }
        if (c.isEmpty() || m.isEmpty()) {
            return c.isEmpty() ? m : c;
        }
        return c + "#" + m;
    }

    /**
     * 把一帧 {@code 类#方法:行号} 去掉行号，得到与 target 可比的形式。
     *
     * <p>只砍<b>最后一段</b>且那一段必须是纯数字或 {@code ?}；否则原样返回
     * （{@code handler$zzz$mymod$hurt} 这种带 {@code $} 的名字里不会有冒号，但 Mixin 的
     * 注入方法名里可能有，所以不能按第一个冒号切）。
     *
     * @param frame 帧文本（可为 {@code null}）
     * @return 去掉行号的结果；{@code null} ⇒ {@code "?"}
     */
    public static String stripLineNo(String frame) {
        if (frame == null) {
            return "?";
        }
        final String text = frame.trim();
        if (text.isEmpty()) {
            return "?";
        }
        final int colon = text.lastIndexOf(':');
        if (colon <= 0 || colon == text.length() - 1) {
            return text;
        }
        final String tail = text.substring(colon + 1);
        for (int i = 0; i < tail.length(); i++) {
            final char c = tail.charAt(i);
            if (!Character.isDigit(c) && c != '?') {
                return text;                    // 尾巴不是行号 ⇒ 这个冒号是名字的一部分
            }
        }
        return text.substring(0, colon);
    }

    /**
     * 两段目标文本是不是同一个方法（都去掉行号之后逐字比）。
     *
     * @param a {@code 类#方法} 或 {@code 类#方法:行}
     * @param b 同上
     * @return {@code true} = 同一个
     */
    public static boolean sameTarget(String a, String b) {
        final String x = stripLineNo(a);
        final String y = stripLineNo(b);
        return !"?".equals(x) && x.equals(y);
    }

    // ═══════════════════════════════ 数据结构 ═══════════════════════════════

    /** 一次"能不能重打"的判定结果（不可变纯数据）。 */
    public static final class Throttle {

        /** {@code true} = 现在可以重打。 */
        public final boolean allowed;

        /** {@link #REASON_OK}／{@link #REASON_BACKOFF}／{@link #REASON_THROTTLED}。 */
        public final String reason;

        /** 60 秒窗口内已经重打过几次。 */
        public final int attempts;

        /** 放行时：这一次是第几次（1 起）；不放行 ⇒ 0。 */
        public final int nextAttempt;

        /** {@link #REASON_BACKOFF} 时：还差多少毫秒。 */
        public final long waitMillis;

        /** 这一次适用的退避值（判读用）。 */
        public final long backoffMillis;

        Throttle(boolean allowed, String reason, int attempts, int nextAttempt, long waitMillis,
                 long backoffMillis) {
            this.allowed = allowed;
            this.reason = reason;
            this.attempts = attempts;
            this.nextAttempt = nextAttempt;
            this.waitMillis = waitMillis;
            this.backoffMillis = backoffMillis;
        }

        /** 顶到上限了吗（调用方据此打 {@code retaliate_give_up} 并永久停手）。 */
        public boolean atCap() {
            return REASON_THROTTLED.equals(reason);
        }

        @Override
        public String toString() {
            return "allowed=" + allowed + " reason=" + reason + " attempts=" + attempts
                    + " next=" + nextAttempt + " wait_ms=" + waitMillis + " backoff_ms=" + backoffMillis;
        }
    }
}
