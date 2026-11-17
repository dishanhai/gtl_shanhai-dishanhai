package com.shanhai.add.guard.state;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 山海 · <b>「这一次写入是不是玩家自己的操作」的可观测标记</b>（<b>纯 JDK</b>：不认 MC、不认 mod、不认类名）。
 *
 * <h2>🔴 它存在的唯一理由（用户原话，这就是验收判据）</h2>
 * <pre>
 *   「反击退的话，你为什么要把 tick 作为判据，我说了把是否为玩家自己的操作作为判据啊」
 * </pre>
 * 旧实现（2026-11-08 那一版）把 <b>tick 边界</b>当判据：{@code PlayerTickEvent.START} 上发现
 * {@code deltaMovement} 与上一拍 END 不同 ⇒ 判"有人在窗口之外写过" ⇒ 写回。
 * 这条判据<b>分不清"别人推我"和"我自己在走"</b> —— 玩家自己的移动包
 * （{@code ServerGamePacketListenerImpl#handleMovePlayer}）就落在两拍之间，
 * 于是「自己起跳 0.42」「正常冲刺 0.2 格」全被判成外部写入（见 {@code temp\momentum\报告.md} 的两条真实日志）。
 *
 * <h2>现在的地基：两个来源标记（{@code self_input} / {@code self_guard}）</h2>
 * <pre>
 *   ① self_input —— 原版处理【玩家自己的移动包】那一段（HEAD 置位、RETURN 清位）
 *                  ⇒ 这一段里的所有写入都是"玩家自己干的" ⇒ 采纳（并记成新的认可值）
 *   ② self_guard —— 我们自己的写入（复活 / 复位 / 清击退 / 还原后重锚）
 *                  ⇒ 也不要打自己 ⇒ 采纳
 *   都不是 ⇒ 才可能是外部（猫推我、别的 mod 直写血量 / 动量 / 位置）
 * </pre>
 * <b>tick 边界从此只是"采样时机"，不再是"归属依据"</b>（用户点名的区别）。
 *
 * <h2>为什么是 {@link ThreadLocal}</h2>
 * 1.20.1 的服务端把玩家移动包<b>在服务端线程上</b>处理（{@code PacketUtils.ensureRunningOnSameThread}），
 * 我们的判据也在服务端线程上跑 ⇒ 同一个线程的标记就够，不需要加锁、不会串玩家。
 * 深度计数（而不是布尔）是为了「标记里还有标记」时不出错。
 *
 * <h2>🔴 自带保鲜期（mark TTL）：异常路径的失败姿势</h2>
 * HEAD 置位 / RETURN 清位这种成对写法，遇到<b>方法体里抛异常</b>时清位那一步不会执行
 * ⇒ 标记会一直留着，等于这一整条线程上的外部写入全被当成"玩家自己的"（保护静默失效）。
 * ⇒ 所以标记自带 {@link #MARK_TTL_NANOS}（200 ms ≈ 4 tick）：超过就<b>当成没有标记</b>，
 * 并计入 {@link #staleIgnored()}（读数可见，绝不静默）。
 * 200 ms 的依据：一个移动包处理是<b>微秒级</b>的（不含跨 tick 等待），200 ms 已经比它大五个数量级；
 * 同时又远小于"保护失效"的代价 ⇒ 宁可多判几次外部，也不让标记永久粘住。
 *
 * <h2>为什么不用 Forge 的 {@code TickEvent} 当兜底</h2>
 * 那正是旧实现的错法（用时间轴猜归属）。这里只认"谁置的位"。
 */
public final class ShanhaiSelfInput {

    /** 这次写入的来源。 */
    public enum Origin {

        /** 没有任何标记：可能是外部写入（也可能是不经过这两条路的原版物理）。 */
        NONE,

        /** 玩家自己的操作（原版处理移动包那一段）。 */
        SELF_INPUT,

        /** 我们自己的写入（复活 / 复位 / 清击退 / 还原后重锚）。 */
        SELF_GUARD;

        /** 是不是"我们自己的人"（self_input 或 self_guard）。 */
        public boolean isSelf() {
            return this != NONE;
        }

        /** 进日志的机器名（{@code none} / {@code self_input} / {@code self_guard}）。 */
        public String token() {
            switch (this) {
                case SELF_INPUT:
                    return "self_input";
                case SELF_GUARD:
                    return "self_guard";
                default:
                    return "none";
            }
        }

        /** 由名字还原（认得 {@code input}/{@code guard} 简写；不认识 ⇒ {@link #NONE}）。 */
        public static Origin byToken(String text) {
            if (text == null) {
                return NONE;
            }
            final String t = text.trim().toLowerCase(java.util.Locale.ROOT);
            if ("self_input".equals(t) || "input".equals(t)) {
                return SELF_INPUT;
            }
            if ("self_guard".equals(t) || "guard".equals(t)) {
                return SELF_GUARD;
            }
            return NONE;
        }
    }

    /**
     * 标记的保鲜期（纳秒）：200 ms ≈ 4 tick。
     *
     * <p>超过它 ⇒ 标记<b>作废</b>（当成 {@link Origin#NONE}）。依据见类注释「自带保鲜期」。
     */
    public static final long MARK_TTL_NANOS = 200_000_000L;

    /**
     * 这个线程的标记（字段名与任务书一致：{@code shanhaiSelfInput}）。
     *
     * <p>{@code ThreadLocal.withInitial} 会为每个线程建一个 {@link Mark}；服务端线程数是个位数，
     * 不存在泄漏风险（玩家下线也不会多一个线程）。
     */
    private static final ThreadLocal<Mark> shanhaiSelfInput = ThreadLocal.withInitial(Mark::new);

    private static final AtomicLong INPUT_ENTERED = new AtomicLong();
    private static final AtomicLong GUARD_ENTERED = new AtomicLong();
    private static final AtomicLong STALE_IGNORED = new AtomicLong();
    private static final AtomicLong MAX_DEPTH = new AtomicLong();
    private static final AtomicLong UNBALANCED_EXIT = new AtomicLong();

    private ShanhaiSelfInput() {}

    // ═══════════════════════════════ 置位 / 清位 ═══════════════════════════════

    /**
     * 进入「玩家自己的操作」那一段（原版 {@code handleMovePlayer} 的 HEAD）。
     *
     * <p>必须与 {@link #exitInput()} 成对（{@code try/finally}）；异常漏掉清位时靠
     * {@link #MARK_TTL_NANOS} 兜底。
     */
    public static void enterInput() {
        final Mark m = shanhaiSelfInput.get();
        if (m.inputDepth == 0) {
            m.inputAtNanos = System.nanoTime();
            m.inputStale = false;
        }
        m.inputDepth++;
        INPUT_ENTERED.incrementAndGet();
        noteDepth(m);
    }

    /** 退出「玩家自己的操作」那一段（原版 {@code handleMovePlayer} 的 RETURN / TAIL）。 */
    public static void exitInput() {
        final Mark m = shanhaiSelfInput.get();
        if (m.inputDepth <= 0) {
            m.inputDepth = 0;
            UNBALANCED_EXIT.incrementAndGet();     // 没有对应置位就清位：只是计数，不改任何行为
            return;
        }
        m.inputDepth--;
        if (m.inputDepth == 0) {
            m.inputStale = false;
        }
    }

    /** 进入「我们自己的写入」那一段（复活 / 复位 / 清击退）。 */
    public static void enterGuard() {
        final Mark m = shanhaiSelfInput.get();
        if (m.guardDepth == 0) {
            m.guardAtNanos = System.nanoTime();
            m.guardStale = false;
        }
        m.guardDepth++;
        GUARD_ENTERED.incrementAndGet();
        noteDepth(m);
    }

    /** 退出「我们自己的写入」那一段。 */
    public static void exitGuard() {
        final Mark m = shanhaiSelfInput.get();
        if (m.guardDepth <= 0) {
            m.guardDepth = 0;
            UNBALANCED_EXIT.incrementAndGet();
            return;
        }
        m.guardDepth--;
        if (m.guardDepth == 0) {
            m.guardStale = false;
        }
    }

    // ═══════════════════════════════ 读 ═══════════════════════════════

    /**
     * 这一刻的写入来源（<b>判据的唯一入口</b>）。
     *
     * <p>{@code self_input} 优先于 {@code self_guard}：玩家自己的移动包是最强的归属证据。
     *
     * @return {@link Origin#NONE} / {@link Origin#SELF_INPUT} / {@link Origin#SELF_GUARD}
     */
    public static Origin origin() {
        final Mark m = shanhaiSelfInput.get();
        if (m.inputDepth > 0) {
            if (expired(m.inputAtNanos)) {
                countStale(m, true);
            } else {
                return Origin.SELF_INPUT;
            }
        }
        if (m.guardDepth > 0) {
            if (expired(m.guardAtNanos)) {
                countStale(m, false);
            } else {
                return Origin.SELF_GUARD;
            }
        }
        return Origin.NONE;
    }

    /** 现在是否处在"玩家自己的操作"那一段里（已过保鲜期的标记算没有）。 */
    public static boolean inInput() {
        return origin() == Origin.SELF_INPUT;
    }

    /** 现在是否处在"我们自己的写入"那一段里（已过保鲜期的标记算没有）。 */
    public static boolean inGuard() {
        return origin() == Origin.SELF_GUARD;
    }

    /** 标记深度（{@code input*1000 + guard}；只作读数/命令用）。 */
    public static int depth() {
        final Mark m = shanhaiSelfInput.get();
        return (m.inputDepth + m.guardDepth) == 0 ? 0 : m.inputDepth * 1000 + m.guardDepth;
    }

    /**
     * 强制清掉本线程的标记（<b>只给自检/停服清理用</b>）。
     *
     * <p>正常路径不许调它：那会把"正在处理移动包"这个事实抹掉。
     */
    public static void forceClear() {
        final Mark m = shanhaiSelfInput.get();
        m.inputDepth = 0;
        m.guardDepth = 0;
        m.inputStale = false;
        m.guardStale = false;
    }

    // ═══════════════════════════════ 读数 ═══════════════════════════════

    /** 进过多少次 {@code self_input}（读数）。 */
    public static long inputEntered() {
        return INPUT_ENTERED.get();
    }

    /** 进过多少次 {@code self_guard}（读数）。 */
    public static long guardEntered() {
        return GUARD_ENTERED.get();
    }

    /** 标记过了保鲜期被当成"没有标记"多少次（读数：>0 说明有异常路径漏了清位）。 */
    public static long staleIgnored() {
        return STALE_IGNORED.get();
    }

    /** 没有对应置位的清位次数（读数：>0 说明成对写法被破坏）。 */
    public static long unbalancedExit() {
        return UNBALANCED_EXIT.get();
    }

    /** 见过的最深嵌套（读数）。 */
    public static long maxDepth() {
        return MAX_DEPTH.get();
    }

    /** 一行读数（命令 / 开机自证 / 报告）。 */
    public static String statusLine() {
        return "self_input_entered=" + INPUT_ENTERED.get()
                + " self_guard_entered=" + GUARD_ENTERED.get()
                + " mark_ttl_ms=" + (MARK_TTL_NANOS / 1_000_000L)
                + " stale_ignored=" + STALE_IGNORED.get()
                + " unbalanced_exit=" + UNBALANCED_EXIT.get()
                + " max_depth=" + MAX_DEPTH.get();
    }

    // ═══════════════════════════════ 内部 ═══════════════════════════════

    /** 每个线程一份（不是每个玩家）：服务端线程只有一个，客户端线程不参与判据。 */
    private static final class Mark {

        int inputDepth;
        int guardDepth;
        long inputAtNanos;
        long guardAtNanos;
        boolean inputStale;
        boolean guardStale;
    }

    private static boolean expired(long atNanos) {
        return System.nanoTime() - atNanos > MARK_TTL_NANOS;
    }

    /** 每个"过期事件"只记一次，免得一个卡住的标记把计数刷成天文数字。 */
    private static void countStale(Mark m, boolean input) {
        if (input) {
            if (!m.inputStale) {
                m.inputStale = true;
                STALE_IGNORED.incrementAndGet();
            }
        } else if (!m.guardStale) {
            m.guardStale = true;
            STALE_IGNORED.incrementAndGet();
        }
    }

    private static void noteDepth(Mark m) {
        final int d = m.inputDepth + m.guardDepth;
        if (d <= 1) {
            return;
        }
        long seen = MAX_DEPTH.get();
        while (d > seen && !MAX_DEPTH.compareAndSet(seen, d)) {
            seen = MAX_DEPTH.get();
        }
    }
}
