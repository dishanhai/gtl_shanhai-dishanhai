package com.shanhai.client.holo;

import java.util.Locale;

/**
 * 山海重构 · 全息菜单「空手点板这一拍」的两条<b>纯规则</b>（<b>纯算术，不碰 Minecraft</b>）。
 *
 * <h2>1. 「清除实体」的二次确认状态机（本次崩服的直接起因）</h2>
 *
 * <h3>1.1 事故（日志原文，不是推测）</h3>
 * <pre>
 *   现场：玩家<b>空手、正对前方</b>，13 次点击全部命中 {@code board=1 idx=0}（第 1 格「清除实体」）
 *   旧行为：<b>每一次都当场执行</b> {@code /shanhai purge} 那一套（世界里所有实体全部清场）
 *   后果：连带把其它模组的实体也摘掉 ⇒ 它在自己的 tick 里 {@code NoSuchElementException} ⇒ <b>崩服</b>
 * </pre>
 * ⇒ 一次误触就能造成<b>不可逆</b>后果的操作，不能用"点一次就执行"。
 *
 * <h3>1.2 现在的规则（连点两次确认）</h3>
 * <pre>
 *   第 1 拍（空手、打到第 1 格）  ⇒ <b>只记 pending</b>（{@link #P_ARMED}），不打任何包、不清任何实体
 *   第 2 拍（<b>同一格</b>、<b>仍然空手</b>、距第 1 拍 ≤ {@link #PURGE_WINDOW_MS} ms）
 *                                ⇒ {@link #P_EXECUTE}，这时才发 {@code shanhai purge}
 *   换板 / 换手 / 超时 / 投影关了    ⇒ 清掉 pending，并给一个原因码（调用方打 {@code purge_confirm_cancelled reason=…}）
 * </pre>
 * <p>🔴 <b>手动 {@code /shanhai purge}（服务端命令）完全不受影响</b> —— 它根本不经过本类，
 * 本类只挡"空手点板"这一条路。
 *
 * <h3>1.3 为什么是"连点两次"而不是"按住 400 ms"</h3>
 * 按住那套要读按键的按住时长，而"按住不放"在原版会<b>每 4~5 tick 再回调一次 use</b>
 * （{@code Minecraft.startUseItem} 的重复回调）—— 那条路上"按下"与"连发"本来就分不清，
 * 而{@link ShanhaiHoloMenuInputRules} 的真值表里 {@code freshPress=false} 的那几拍
 * <b>直接返回 ACT_NONE</b>（已验收的去抖口径），也就是说"按住"在输入层根本没有入口。
 * 连点两次则完全复用同一条"新鲜按下"口径 ⇒ <b>不新增任何输入语义</b>，也不会与长按去抖打架。
 * 取舍与代价写在报告里（多一次点击、多 3 秒窗口）。
 *
 * <h2>2. 点击日志里 yaw / pitch / 距离的格式化口径</h2>
 * {@code holo_click} 那一行要回答"他到底瞄了哪一块板"，所以角度必须能直接读数：
 * <pre>
 *   yaw=   -180.00 .. 180.00（原样给，不做归一化：读的就是 {@code LocalPlayer.getYRot()} 那一个数）
 *   pitch=  -90.00 ..  90.00
 *   这些数<b>永远</b>用 {@link Locale#ROOT} 格式化 —— 德语/法语区域下 {@code %.2f} 会给出 {@code 12,35}，
 *   那样 grep {%.2f} 的判据会静默失效（自检里有一条专门盯这个的负对照）。
 *   非有限值（NaN / ∞）一律打成 {@code nan}，绝不出现 {@code NaN} 这种看不清来源的字符串。
 * </pre>
 *
 * <h2>3. 为什么整类都不碰 Minecraft</h2>
 * 同 {@link ShanhaiHoloMenuPending} / {@link ShanhaiHoloMenuAnim}：时间由参数注入、状态在入参里给，
 * 于是整条状态机<b>可以在没有游戏的机器上逐格验</b>（{@link #selfTest()}，含 5 个突变体）。
 */
public final class ShanhaiHoloClickRules {

    private ShanhaiHoloClickRules() {}

    // ==================================================================== 清除实体：确认窗口

    /**
     * 第 1 拍到第 2 拍之间允许的最大间隔（纳秒）。
     * <p>3 秒：比"人双击"宽得多（实测双击 80~250 ms）、又短到"手滑点了一次之后去干别的再回来"不会误触。
     */
    public static final long PURGE_CONFIRM_WINDOW_NANOS = 3_000_000_000L;

    /** 同一个窗口的毫秒口径（日志里打的就是这个数，用户点名的 {@code window_ms=3000}）。 */
    public static final int PURGE_WINDOW_MS = 3_000;

    // ---- 状态机的结果码（输入层拿它决定"打哪一行日志 / 发不发命令"）----

    /** 什么都不用做（没武装、或这一拍不该算进确认窗口）。 */
    public static final int P_NONE = 0;
    /** 第 1 拍：已记下 pending，等第 2 拍。 */
    public static final int P_ARMED = 1;
    /** 第 2 拍（同一格、空手、窗口内）：<b>这一拍才真执行</b>。 */
    public static final int P_EXECUTE = 2;
    /** 取消：等太久（{@code > window}）。 */
    public static final int P_CANCEL_TIMEOUT = 3;
    /** 取消：打了别的板（换板）。 */
    public static final int P_CANCEL_BOARD = 4;
    /** 取消：手里拿着模块了（换手 / 把模块掏出来）。 */
    public static final int P_CANCEL_HAND = 5;
    /** 取消：投影关掉了（面板随投影一起收，pending 不许留到下一次开投影）。 */
    public static final int P_CANCEL_MENU = 6;

    /**
     * 二次确认的状态（<b>可变</b>；由调用方持有，所有迁移都过本类的纯函数）。
     * <p>字段刻意私有：状态只能通过 {@link #purgeTap} / {@link #purgeExpire} /
     * {@link #purgeCancel} / {@link #purgeCancelOnForeignTap} 迁移 ⇒ 不可能出现"某处直接改 armed"。
     */
    public static final class PurgeState {
        private boolean armed;
        private long armedAtNanos;
        private int board = -1;

        /** 现在是不是"已经点了第 1 拍、等第 2 拍"。 */
        public boolean armed() {
            return armed;
        }

        /** 武装在那块板上（0 基）；没武装 = {@code -1}。 */
        public int board() {
            return armed ? board : -1;
        }

        /** 已等了多少毫秒（没武装 = {@code -1}；时间倒退时钳到 0）。 */
        public long elapsedMs(long nowNanos) {
            if (!armed) {
                return -1L;
            }
            final long ns = nowNanos - armedAtNanos;
            return ns <= 0L ? 0L : ns / 1_000_000L;
        }

        private void arm(int boardIndex, long nowNanos) {
            armed = true;
            board = boardIndex;
            armedAtNanos = nowNanos;
        }

        private void clear() {
            armed = false;
            board = -1;
            armedAtNanos = 0L;
        }

        /** 供离线自检复用（把状态清干净）。 */
        public void resetForTest() {
            clear();
        }
    }

    /**
     * 输入层用的<b>那一个</b>实例（静态状态只有这一个落点；纯函数本身不吃它，自检自己造实例）。
     * <p>⚠️ 只有 {@code ShanhaiHoloMenuInput} 可以碰它。
     */
    public static final PurgeState PURGE = new PurgeState();

    /**
     * 一次「清除实体」点击进状态机。
     *
     * @param holdingModule 这一拍手里拿着模块（= "换手"）
     * @param boardIndex    这一拍打到的板下标（0 基）
     * @return {@link #P_ARMED} / {@link #P_EXECUTE} / {@link #P_CANCEL_HAND} /
     *         {@link #P_CANCEL_BOARD} / {@link #P_CANCEL_TIMEOUT} / {@link #P_NONE}
     */
    public static int purgeTap(PurgeState st, boolean holdingModule, int boardIndex, long nowNanos) {
        if (st == null) {
            return P_NONE;
        }
        if (!st.armed) {
            if (holdingModule) {
                return P_NONE;          // 拿着模块这一拍根本到不了这里（被"切换投影"截走了）
            }
            st.arm(boardIndex, nowNanos);
            return P_ARMED;
        }
        // ---- 已武装：先判"这一拍还算不算数" ----
        if (holdingModule) {
            st.clear();
            return P_CANCEL_HAND;       // 换手
        }
        if (boardIndex != st.board) {
            st.clear();
            return P_CANCEL_BOARD;      // 换板
        }
        final long dt = nowNanos - st.armedAtNanos;
        if (dt < 0L) {
            return P_NONE;              // 时间倒退（不该发生）：既不算确认，也不动 pending
        }
        if (dt > PURGE_CONFIRM_WINDOW_NANOS) {
            st.clear();
            return P_CANCEL_TIMEOUT;    // 超时
        }
        st.clear();
        return P_EXECUTE;               // 🔴 窗口内、同一格、空手 ⇒ 这一拍才真执行
    }

    /**
     * 每 tick 查一次"窗口过期了没有"。
     * <p>为什么要每 tick 查而不是等下一次点击才查：<b>取消要有一行日志</b>
     * （{@code purge_confirm_cancelled reason=timeout}），而"用户再也不点了"是常态 ⇒
     * 只靠下一次点击触发的话，那条日志永远不会出现，pending 会静默留着一个"看起来还开着"的状态。
     */
    public static int purgeExpire(PurgeState st, long nowNanos) {
        if (st == null || !st.armed) {
            return P_NONE;
        }
        if (nowNanos - st.armedAtNanos > PURGE_CONFIRM_WINDOW_NANOS) {
            st.clear();
            return P_CANCEL_TIMEOUT;
        }
        return P_NONE;
    }

    /** 无条件取消（投影关了等等）。没武装时返回 {@link #P_NONE}（不刷日志）。 */
    public static int purgeCancel(PurgeState st, int reason) {
        if (st == null || !st.armed) {
            return P_NONE;
        }
        st.clear();
        return reason;
    }

    /**
     * "这一拍<b>不是</b>那一格"的取消判据（在动作分派<b>之前</b>调）：
     * <pre>
     *   拿着模块            ⇒ 取消（换手）
     *   打的不是清除实体那格 ⇒ 取消（换板 / 点了别的板 / 点了空处）
     *   打了别的板下标       ⇒ 取消（换板）
     *   否则（就是那一格）   ⇒ P_NONE，<b>不清</b> —— 交给 {@link #purgeTap} 当第 2 拍
     * </pre>
     */
    public static int purgeCancelOnForeignTap(PurgeState st, boolean holdingModule,
                                              boolean isPurgeAction, int boardIndex) {
        if (st == null || !st.armed) {
            return P_NONE;
        }
        if (holdingModule) {
            st.clear();
            return P_CANCEL_HAND;
        }
        if (!isPurgeAction || boardIndex != st.board) {
            st.clear();
            return P_CANCEL_BOARD;
        }
        return P_NONE;
    }

    /** 原因码 ⇒ 日志里的 {@code reason=} 值（只这几个词，便于 grep）。 */
    public static String purgeReasonName(int code) {
        return switch (code) {
            case P_CANCEL_TIMEOUT -> "timeout";
            case P_CANCEL_BOARD -> "board";
            case P_CANCEL_HAND -> "hand";
            case P_CANCEL_MENU -> "menu_closed";
            default -> "none";
        };
    }

    // ==================================================================== 日志字段口径

    /**
     * 角度 ⇒ {@code %.2f}（永远点号；非有限值给 {@code nan}）。<b>不做规范化</b>：
     * 要进日志的玩家朝向请走 {@code ShanhaiHoloViewMath#yawText}/{@code #pitchText}
     * （那边先把累积值规范到 {@code (-180,180]}，否则会打出 {@code yaw=-993.45} 这种越界值）。
     */
    public static String angleText(float deg) {
        if (!Float.isFinite(deg)) {
            return "nan";
        }
        return String.format(Locale.ROOT, "%.2f", deg);
    }

    /** 距离 ⇒ {@code %.2f}；没打中时调用方传的就是 {@code -1}，这里原样打成 {@code -1.00}。 */
    public static String distText(double d) {
        if (!Double.isFinite(d)) {
            return "nan";
        }
        return String.format(Locale.ROOT, "%.2f", d);
    }

    // ==================================================================== 自检

    /** 自检结果。 */
    public record Report(int pass, int total, String bad, int mutants, int mutantsCaught) {
        public boolean ok() {
            return bad == null;
        }
    }

    /**
     * 离线自检（<b>不碰 Minecraft，时间全部由参数注入</b>）。
     *
     * <p>两类判据，各自有负对照：
     * <pre>
     *   A. 正向：状态机的每一条迁移（第 1 拍只武装 / 第 2 拍才执行 / 超时 / 换板 / 换手 / 执行后归零）
     *   B. 突变体：把一条<b>故意写错的期望</b>喂给同一个断言器 ⇒ 它<b>必须报错</b>
     *      （证明"断言器真的会红"，而不是恒绿的）
     * </pre>
     */
    public static Report selfTest() {
        final StringBuilder bad = new StringBuilder();
        final int[] n = {0, 0};
        final int[] mut = {0, 0};
        final long t0 = 20_000_000_000L;
        final long win = PURGE_CONFIRM_WINDOW_NANOS;
        final PurgeState st = new PurgeState();

        // ---------- A1 第 1 拍：只武装，绝不执行 ----------
        st.resetForTest();
        c(bad, n, "第 1 拍 ⇒ P_ARMED（不许执行）", purgeTap(st, false, 0, t0), P_ARMED);
        c(bad, n, "第 1 拍之后 armed()=true", st.armed(), true);
        c(bad, n, "第 1 拍记下的板 = 0", st.board(), 0);
        c(bad, n, "第 1 拍之后 elapsedMs=0", st.elapsedMs(t0), 0L);

        // ---------- A2 第 2 拍（窗口内、同一格、空手）⇒ 执行 ----------
        c(bad, n, "第 2 拍（+120ms）⇒ P_EXECUTE", purgeTap(st, false, 0, t0 + 120_000_000L), P_EXECUTE);
        c(bad, n, "执行之后 armed()=false（不许连着执行两次）", st.armed(), false);
        c(bad, n, "执行之后 board()=-1", st.board(), -1);

        // ---------- A3 执行完再点一次 ⇒ 又要重新确认（不许连续执行） ----------
        c(bad, n, "执行之后第 3 拍 ⇒ 回到 P_ARMED", purgeTap(st, false, 0, t0 + 200_000_000L), P_ARMED);
        st.resetForTest();

        // ---------- A4 窗口边界：恰好 3000ms 还算数，3000ms+1ns 不算 ----------
        st.resetForTest();
        purgeTap(st, false, 0, t0);
        c(bad, n, "恰好 " + PURGE_WINDOW_MS + "ms ⇒ 仍然 P_EXECUTE（边界不吞）",
                purgeTap(st, false, 0, t0 + win), P_EXECUTE);
        st.resetForTest();
        purgeTap(st, false, 0, t0);
        c(bad, n, PURGE_WINDOW_MS + "ms + 1ns ⇒ P_CANCEL_TIMEOUT（边界外必拒）",
                purgeTap(st, false, 0, t0 + win + 1L), P_CANCEL_TIMEOUT);
        c(bad, n, "超时之后 armed()=false", st.armed(), false);

        // ---------- A5 换板 ⇒ 取消 ----------
        st.resetForTest();
        purgeTap(st, false, 0, t0);
        c(bad, n, "第 1 拍打第 1 格、第 2 拍打第 2 格 ⇒ P_CANCEL_BOARD",
                purgeTap(st, false, 1, t0 + 100_000_000L), P_CANCEL_BOARD);
        c(bad, n, "换板之后 armed()=false", st.armed(), false);

        // ---------- A6 换手（拿着模块）⇒ 取消 ----------
        st.resetForTest();
        purgeTap(st, false, 0, t0);
        c(bad, n, "第 2 拍手里拿着模块 ⇒ P_CANCEL_HAND",
                purgeTap(st, true, 0, t0 + 100_000_000L), P_CANCEL_HAND);
        c(bad, n, "换手之后 armed()=false", st.armed(), false);
        c(bad, n, "拿着模块点 ⇒ 从不武装（P_NONE）", purgeTap(st, true, 0, t0 + 200_000_000L), P_NONE);

        // ---------- A7 时间倒退（防御）⇒ 不动 pending ----------
        st.resetForTest();
        purgeTap(st, false, 0, t0);
        c(bad, n, "负对照：now 比武装时刻更早 ⇒ P_NONE（不确认、也不清）",
                purgeTap(st, false, 0, t0 - 5_000_000_000L), P_NONE);
        c(bad, n, "负对照：时间倒退之后仍然武装着", st.armed(), true);

        // ---------- A8 每 tick 的到期检查 ----------
        st.resetForTest();
        purgeTap(st, false, 0, t0);
        c(bad, n, "窗口内 purgeExpire ⇒ P_NONE", purgeExpire(st, t0 + 1_000_000_000L), P_NONE);
        c(bad, n, "过期后 purgeExpire ⇒ P_CANCEL_TIMEOUT", purgeExpire(st, t0 + win + 1L), P_CANCEL_TIMEOUT);
        c(bad, n, "过期之后 armed()=false", st.armed(), false);
        c(bad, n, "没武装时 purgeExpire ⇒ P_NONE（不刷日志）", purgeExpire(st, t0 + win + 2L), P_NONE);

        // ---------- A9 "这一拍不是那一格"的取消 ----------
        st.resetForTest();
        purgeTap(st, false, 0, t0);
        c(bad, n, "同一格 ⇒ purgeCancelOnForeignTap 不清（交给 purgeTap）",
                purgeCancelOnForeignTap(st, false, true, 0), P_NONE);
        c(bad, n, "同一格之后仍然武装着", st.armed(), true);
        c(bad, n, "打到别的板 ⇒ P_CANCEL_BOARD", purgeCancelOnForeignTap(st, false, true, 2), P_CANCEL_BOARD);
        c(bad, n, "打到别的板之后 armed()=false", st.armed(), false);
        st.resetForTest();
        purgeTap(st, false, 0, t0);
        c(bad, n, "这一拍的动作不是清除实体 ⇒ P_CANCEL_BOARD",
                purgeCancelOnForeignTap(st, false, false, 0), P_CANCEL_BOARD);
        st.resetForTest();
        purgeTap(st, false, 0, t0);
        c(bad, n, "这一拍拿着模块 ⇒ P_CANCEL_HAND", purgeCancelOnForeignTap(st, true, true, 0), P_CANCEL_HAND);
        c(bad, n, "没武装时 purgeCancel ⇒ P_NONE（不刷日志）", purgeCancel(st, P_CANCEL_MENU), P_NONE);

        // ---------- A10 原因码的日志口径 ----------
        c(bad, n, "reason=timeout", "timeout".equals(purgeReasonName(P_CANCEL_TIMEOUT)), true);
        c(bad, n, "reason=board", "board".equals(purgeReasonName(P_CANCEL_BOARD)), true);
        c(bad, n, "reason=hand", "hand".equals(purgeReasonName(P_CANCEL_HAND)), true);
        c(bad, n, "reason=menu_closed", "menu_closed".equals(purgeReasonName(P_CANCEL_MENU)), true);

        // ---------- A11 角度/距离的格式化口径 ----------
        c(bad, n, "angleText(0)=0.00", "0.00".equals(angleText(0.0f)), true);
        c(bad, n, "angleText(-90.5)=-90.50", "-90.50".equals(angleText(-90.5f)), true);
        c(bad, n, "angleText(12.345)=12.35（四舍五入到 2 位）", "12.35".equals(angleText(12.345f)), true);
        c(bad, n, "负对照：angleText 里必须是【点号】不是逗号", angleText(12.345f).indexOf(',') < 0, true);
        c(bad, n, "angleText(NaN)=nan", "nan".equals(angleText(Float.NaN)), true);
        c(bad, n, "angleText(+∞)=nan", "nan".equals(angleText(Float.POSITIVE_INFINITY)), true);
        c(bad, n, "distText(4.5)=4.50", "4.50".equals(distText(4.5)), true);
        c(bad, n, "distText(-1)=-1.00（没打中就是负一）", "-1.00".equals(distText(-1.0)), true);
        c(bad, n, "distText(NaN)=nan", "nan".equals(distText(Double.NaN)), true);

        // ---------- B 突变体：故意写错的期望必须被同一个断言器抓到 ----------
        //   ① 德语区域下 %.2f 会变成 12,35 —— 这就是不锁 Locale.ROOT 的后果（这里先证明"它真的会变"）
        final String de = String.format(Locale.GERMANY, "%.2f", 12.345f);
        c(bad, n, "负对照：显式德语区域会给出逗号（这就是必须锁 ROOT 的理由）", de.indexOf(',') >= 0, true);
        mutant(bad, n, mut, "突变体 1：把 angleText(12.345) 当成 \"12,35\" 必须报错",
                "12,35", angleText(12.345f));
        mutant(bad, n, mut, "突变体 2：把 angleText(NaN) 当成 \"NaN\" 必须报错",
                "NaN", angleText(Float.NaN));
        mutant(bad, n, mut, "突变体 3：把\"窗口内第 2 拍\"当成 P_ARMED 必须报错",
                String.valueOf(P_ARMED), String.valueOf(secondTapAfterArm()));
        mutant(bad, n, mut, "突变体 4：把\"恰好 3000ms\"当成 P_CANCEL_TIMEOUT 必须报错",
                String.valueOf(P_CANCEL_TIMEOUT), String.valueOf(boundaryTap()));
        mutant(bad, n, mut, "突变体 5：把\"60ms 内就不算确认\"当成取消必须报错（实际是 P_EXECUTE）",
                String.valueOf(P_CANCEL_TIMEOUT), String.valueOf(quickSecondTap()));

        PURGE.resetForTest();
        st.resetForTest();
        final String b = bad.length() == 0 ? null : bad.toString();
        return new Report(n[0], n[1], b, mut[1], mut[0]);
    }

    /** 突变体 3 的样本：武装后 120 ms 的第 2 拍（= 真值 P_EXECUTE）。 */
    private static int secondTapAfterArm() {
        final PurgeState s = new PurgeState();
        purgeTap(s, false, 0, 0L);
        return purgeTap(s, false, 0, 120_000_000L);
    }

    /** 突变体 4 的样本：恰好 3000 ms 时的第 2 拍（= 真值 P_EXECUTE，不是超时）。 */
    private static int boundaryTap() {
        final PurgeState s = new PurgeState();
        purgeTap(s, false, 0, 0L);
        return purgeTap(s, false, 0, PURGE_CONFIRM_WINDOW_NANOS);
    }

    /** 突变体 5 的样本：60 ms 内的第 2 拍（= 真值 P_EXECUTE）。 */
    private static int quickSecondTap() {
        final PurgeState s = new PurgeState();
        purgeTap(s, false, 0, 0L);
        return purgeTap(s, false, 0, 60_000_000L);
    }

    /** 一行自检读数（含突变体计数 —— "突变体一个都没抓到"就是断言器失效）。 */
    public static String selfTestLine() {
        final Report r = selfTest();
        final boolean allCaught = r.mutants() == r.mutantsCaught();
        final boolean ok = r.ok() && allCaught;
        return "holo_clickrules_selftest " + r.pass() + "/" + r.total() + " PASS=" + ok
                + " mutant_caught=" + r.mutantsCaught() + "/" + r.mutants()
                + (ok ? "（清除实体二次确认窗口 " + PURGE_WINDOW_MS + "ms；" + r.total()
                        + " 条正向判据 + " + r.mutants() + " 个突变体）"
                : (" FAILED:" + r.bad() + (allCaught ? "" : "；有突变体没被抓到 ⇒ 断言器失效")));
    }

    // ------------------------------------------------------------------ 断言器

    /**
     * <b>突变体</b>：把一条故意写错的期望喂给一个<b>全新的</b>断言器，它<b>必须</b>报错。
     * <p>抓不到就说明"断言器"本身是恒绿的 —— 那比没有断言更危险（它会替我们把错误藏起来）。
     */
    private static void mutant(StringBuilder bad, int[] n, int[] mut, String what,
                               String expected, String actual) {
        final StringBuilder probeBad = new StringBuilder();
        final int[] probeN = {0, 0};
        c(probeBad, probeN, "probe", actual, expected);
        mut[1]++;
        if (probeBad.length() > 0) {
            mut[0]++;
            n[0]++;
        } else {
            bad.append(' ').append(what).append("：断言器没抓到这条错的期望")
                    .append("（actual=").append(actual).append(", expected=").append(expected).append("）;");
        }
        n[1]++;
    }

    private static void c(StringBuilder bad, int[] n, String what, boolean actual, boolean expected) {
        n[1]++;
        if (actual == expected) {
            n[0]++;
        } else {
            bad.append(' ').append(what).append("：实际 ").append(actual)
                    .append("，应当 ").append(expected).append(';');
        }
    }

    private static void c(StringBuilder bad, int[] n, String what, int actual, int expected) {
        n[1]++;
        if (actual == expected) {
            n[0]++;
        } else {
            bad.append(' ').append(what).append("：实际 ").append(actual)
                    .append("（").append(purgeReasonName(actual)).append("）")
                    .append("，应当 ").append(expected).append(';');
        }
    }

    private static void c(StringBuilder bad, int[] n, String what, long actual, long expected) {
        n[1]++;
        if (actual == expected) {
            n[0]++;
        } else {
            bad.append(' ').append(what).append("：实际 ").append(actual)
                    .append("，应当 ").append(expected).append(';');
        }
    }

    private static void c(StringBuilder bad, int[] n, String what, String actual, String expected) {
        n[1]++;
        if (java.util.Objects.equals(actual, expected)) {
            n[0]++;
        } else {
            bad.append(' ').append(what).append("：实际 `").append(actual)
                    .append("`，应当 `").append(expected).append("`;");
        }
    }
}
