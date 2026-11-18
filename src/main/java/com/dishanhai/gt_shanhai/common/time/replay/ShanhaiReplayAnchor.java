package com.dishanhai.gt_shanhai.common.time.replay;

import java.util.Locale;

import com.dishanhai.gt_shanhai.common.time.ShanhaiClockMode;

/**
 * 山海重构 · <b>锚点语义</b>：倒流不是"回退最近 N 秒"，而是<b>先记一个锚点、再回到那个锚点</b>。
 *
 * <h2>1. 用户口径（口述，设计稿 §11 据此补）</h2>
 * <blockquote>「时间倒流不是先要玩家设置锚点吗，基于此再进行倒流」</blockquote>
 * ⇒ 锚点 = {@code (tick, dim, x/y/z, yaw/pitch, journalCursor)}，落在
 * {@link ShanhaiReplayJournal#setAnchor} 上（<b>只存一份</b>，set 覆盖旧的那条）。
 * 之后 {@code /shanhai time rewind}（无参）就是"回到锚点"；
 * {@code /shanhai time rewind <秒>} 仍按旧语义（回退 N 秒）—— 两条路共用同一个服务状态机。
 *
 * <h2>2. 为什么规则要在这个类里（🔴 零 MC 依赖）</h2>
 * 锚点有四件最容易写错、又最难在游戏里看出来的事：
 * <ol>
 *   <li><b>能不能设</b>（{@code LIVE} 与 <b>{@code PAUSED}</b> 都可以；只有 {@code REPLAY} 拒绝，给理由码）；</li>
 *   <li><b>要退多少 tick</b>（锚点 100、当前 160 ⇒ 60；锚点 100、当前 2560 ⇒ 已淘汰，拒绝）；
 *       —— 拒绝必须给码，<b>不许静默截断</b>；</li>
 *   <li><b>什么时候停</b>（游标退到锚点就停，<b>恰好</b> {@code now-anchor} 个 tick，不多退那一 tick）；</li>
 *   <li><b>要不要传送</b>（同维度才传；跨维度只回退世界状态，且完成行必须写
 *       {@code anchor_dim_mismatch=1}）。</li>
 * </ol>
 * 把它们剥成纯函数之后，{@code tools\replay-selfcheck} 的 G 组（①–⑥）可以在不开游戏的情况下
 * 逐条断言，包括"把 {@code &lt;} 写成 {@code &lt;=} 就多退 1 tick"那条负对照。
 *
 * <h2>3. 拒绝码（字符串，可 grep；一律进日志）</h2>
 * <table border="1">
 *   <caption>上表</caption>
 *   <tr><th>码</th><th>什么时候</th><th>玩家看到的一句话</th></tr>
 *   <tr><td>{@code not_live_paused}</td><td>（<b>2026-11-14 起不再触发</b>）暂停时设锚点</td>
 *       <td>暂停现在允许设锚点："时间停在某一刻"正是设锚点最自然的时刻（码留着，别让旧日志失去解释）</td></tr>
 *   <tr><td>{@code not_live_replay}</td><td>正在倒流（REPLAY）时设锚点</td><td>等倒流结束再设</td></tr>
 *   <tr><td>{@code no_anchor}</td><td>无参 rewind 但没锚点</td><td>先设锚点</td></tr>
 *   <tr><td>{@code anchor_expired}</td><td>锚点超出 1200 tick 窗口（或来自"未来"/日志已清）</td><td>锚点太旧，窗口 60 秒</td></tr>
 *   <tr><td>{@code already_at_anchor}</td><td>锚点就在当前 tick（之后世界没变化）</td><td>无需倒流</td></tr>
 * </table>
 */
public final class ShanhaiReplayAnchor {

    /** "这个倒流没有锚点目标"（{@code anchorTick} 用的是 {@code int}，取一个结构上不可能出现的值）。 */
    public static final int NO_ANCHOR_TICK = Integer.MIN_VALUE;

    private ShanhaiReplayAnchor() {
    }

    // ------------------------------------------------------------------ 状态机口径

    /**
     * 锚点拒绝原因（{@link #NONE} 以外都算拒绝，且<b>必须</b>给玩家一句人能看懂的话）。
     *
     * <p>🔴 拒绝码是<b>判据</b>：{@code temp\reversal\run-selfcheck.ps1} 与日志都按字符串比对，
     * 改字符串等于改对外契约（自检会红）。
     */
    public enum Refusal {

        /** 没拒绝。 */
        NONE("none"),
        /**
         * （<b>2026-11-14 起不再返回</b>）旧口径：暂停时不许设锚点。
         *
         * <p>保留枚举值是为了不删掉历史日志/自检里出现过的码；新判定见
         * {@link ShanhaiReplayModeCompose#refuse}（{@code PAUSED} 已放行）。
         */
        NOT_LIVE_PAUSED("not_live_paused"),
        /** 正在倒流（REPLAY）⇒ 不许设锚点（此刻的世界是"半倒回去"的中间态）。 */
        NOT_LIVE_REPLAY("not_live_replay"),
        /** 无参 rewind 但没有锚点。 */
        NO_ANCHOR("no_anchor"),
        /** 锚点已不在环形窗口里（太旧 / 在"未来" / 日志被清过）。 */
        ANCHOR_EXPIRED("anchor_expired"),
        /** 锚点就在当前 tick ⇒ 没有任何东西可回退。 */
        ALREADY_AT_ANCHOR("already_at_anchor");

        private final String code;

        Refusal(String code) {
            this.code = code;
        }

        /** 稳定字符串（日志/自检/status 共用；别改）。 */
        public String code() {
            return this.code;
        }

        public boolean refused() {
            return this != NONE;
        }

        /** 给玩家的一句话（命令层直接贴到聊天栏）。 */
        public String text() {
            switch (this) {
                case NONE:
                    return "可以回退";
                case NOT_LIVE_PAUSED:
                    return "世界处于暂停（PAUSED），先 /shanhai clock resume 再设锚点";
                case NOT_LIVE_REPLAY:
                    return "正在倒流（REPLAY），等倒流结束再操作";
                case NO_ANCHOR:
                    return "还没有锚点，先 /shanhai time anchor set 在当前位置记一个";
                case ANCHOR_EXPIRED:
                    return "锚点太旧（超出 1200 tick = 60 秒窗口），请重新设锚点";
                case ALREADY_AT_ANCHOR:
                    return "锚点就在当前 tick（之后世界没有变化），不用倒流";
                default:
                    return this.code;
            }
        }
    }

    /**
     * 能不能<b>设</b>锚点：只有 LIVE 可以。
     *
     * <p>{@code null} 一律放行（调用方已经判过，且失败方向不该是"设不了"）。
     */
    public static Refusal setRefusal(ShanhaiClockMode mode) {
        final ShanhaiReplayModeCompose.Refusal refusal =
                ShanhaiReplayModeCompose.refuse(ShanhaiReplayModeCompose.Action.ANCHOR_SET, mode);
        if (!refusal.refused()) {
            return Refusal.NONE;      // 🔴 PAUSED 现在也放行（"停在某一刻"正是设锚点最自然的时刻）
        }
        return refusal == ShanhaiReplayModeCompose.Refusal.NOT_LIVE_REPLAY
                ? Refusal.NOT_LIVE_REPLAY : Refusal.NOT_LIVE_PAUSED;
    }

    // ------------------------------------------------------------------ 目标 tick

    /**
     * 一次"回到锚点"的计划：要么 {@link Refusal#NONE} + {@code ticks}，要么拒绝 + 理由码。
     *
     * @param refusal   拒绝码（{@link Refusal#NONE} = 可以回退）
     * @param anchorTick 锚点 tick（{@link #NO_ANCHOR_TICK} = 没有锚点）
     * @param nowTick    当前世界 tick
     * @param ticks      要回退多少个 tick（= {@code nowTick - anchorTick}）
     * @param detail     细分（{@code older_than_window} / {@code anchor_in_future} / {@code at_now}；可空）
     */
    public record Plan(Refusal refusal, int anchorTick, int nowTick, int ticks, String detail) {

        public Plan {
            if (detail == null) {
                detail = "";
            }
        }

        public boolean ok() {
            return this.refusal == Refusal.NONE;
        }

        /** 一行文本（日志/自检/status 用；字段顺序稳定，便于 grep）。 */
        public String toText() {
            return "refusal=" + this.refusal.code() + " anchor_tick=" + this.anchorTick
                    + " now_tick=" + this.nowTick + " ticks=" + this.ticks
                    + (this.detail.isEmpty() ? "" : " detail=" + this.detail);
        }

        @Override
        public String toString() {
            return toText();
        }
    }

    /**
     * 算"回到锚点要退多少 tick"，顺便判三条不可回退的情形。
     *
     * <p>🔴 窗口口径 = <b>墙钟 tick 距离</b>（不是"ring 里还剩多少条"）：
     * 锚点 100、当前 160 ⇒ 60；锚点 100、当前 2560 ⇒ 2460 &gt; 1200 ⇒
     * {@link Refusal#ANCHOR_EXPIRED}（提示"锚点太旧，窗口 60 秒"）。
     * 这样"锚点比窗口还老"永远不会变成"静默只倒一半"。
     *
     * @param anchor      锚点（{@code null} = 没有）
     * @param nowTick     当前世界 tick
     * @param windowTicks 窗口（生产 = {@link ShanhaiReplayConst#WINDOW_TICKS}；自检可以传小值）
     */
    public static Plan plan(ShanhaiReplayJournal.Anchor anchor, int nowTick, int windowTicks) {
        if (anchor == null) {
            return new Plan(Refusal.NO_ANCHOR, NO_ANCHOR_TICK, nowTick, 0, "");
        }
        final int dist = nowTick - anchor.tick();
        if (dist < 0) {
            // 世界 tick 比锚点还小：换过快照/回档/读了一个坏值 —— 一律当"不可用"，绝不去回退负数个 tick
            return new Plan(Refusal.ANCHOR_EXPIRED, anchor.tick(), nowTick, 0, "anchor_in_future");
        }
        if (dist > windowTicks) {
            return new Plan(Refusal.ANCHOR_EXPIRED, anchor.tick(), nowTick, 0, "older_than_window");
        }
        if (dist == 0) {
            return new Plan(Refusal.ALREADY_AT_ANCHOR, anchor.tick(), nowTick, 0, "at_now");
        }
        return new Plan(Refusal.NONE, anchor.tick(), nowTick, dist, "");
    }

    // ------------------------------------------------------------------ 到期语义（B）

    /** 锚点"到期"这一刻该干什么。 */
    public enum Expiry {
        /** 还没到窗口边界，什么都不做。 */
        NONE("none"),
        /**
         * 刚好到窗口边界 ⇒ <b>把世界时钟自动切到 PAUSED</b>（时间不再前进 ⇒ 锚点永不过期、倒流随时可用）。
         *
         * <p>用户原话：「另外，锚点到时间了自动把时间暂停，而不是使锚点自动失效」。
         */
        AUTO_PAUSE("auto_pause"),
        /**
         * 已经自动暂停过，但玩家把暂停关掉了 / 强行 resume ⇒ 再往前就按<b>旧语义</b>拒绝。
         *
         * <p>这条是刻意保留的边界：自动暂停之后时间不前进，`age` 不可能超过窗口；
         * 只有"人把暂停解除了"才会出现这个码，那时 `anchor_expired` 才是正确行为。
         */
        EXPIRED("expired");

        private final String code;

        Expiry(String code) {
            this.code = code;
        }

        public String code() {
            return this.code;
        }
    }

    /**
     * 一次到期判定。
     *
     * @param action      该干什么（见 {@link Expiry}）
     * @param age         锚点距现在多少 tick（{@code < 0} = 来自未来）
     * @param windowTicks 窗口
     * @param reason      细分：{@code at_window} / {@code older_than_window} / {@code anchor_in_future}
     */
    public record ExpiryDecision(Expiry action, int age, int windowTicks, String reason) {

        public ExpiryDecision {
            if (reason == null) {
                reason = "";
            }
        }

        public boolean shouldPause() {
            return this.action == Expiry.AUTO_PAUSE;
        }

        public String toText() {
            return "action=" + this.action.code() + " age=" + this.age + " window=" + this.windowTicks
                    + (this.reason.isEmpty() ? "" : " reason=" + this.reason);
        }
    }

    /**
     * 锚点到期判定（🔴 纯逻辑：B 的离线断言就打这个函数）。
     *
     * <h2>规则（按顺序）</h2>
     * <ol>
     *   <li>没有锚点 ⇒ {@link Expiry#NONE}；</li>
     *   <li>锚点在"未来"（换快照/坏 tick）⇒ {@link Expiry#EXPIRED}（不许回退负数个 tick）；</li>
     *   <li>{@code age > window} ⇒ {@link Expiry#EXPIRED}（旧语义，只有在"自动暂停被解除"之后才可能到这里）；</li>
     *   <li>{@code age == window} 且还没自动暂停过 ⇒ {@link Expiry#AUTO_PAUSE}；</li>
     *   <li>其余 ⇒ {@link Expiry#NONE}。</li>
     * </ol>
     *
     * @param anchor          锚点（{@code null} = 没有）
     * @param nowTick         当前世界 tick
     * @param windowTicks     窗口（生产 = {@link ShanhaiReplayConst#WINDOW_TICKS}）
     * @param alreadyAutoPaused 这个锚点是否已经触发过自动暂停（防重复切模式/刷日志）
     */
    public static ExpiryDecision expiry(ShanhaiReplayJournal.Anchor anchor, int nowTick, int windowTicks,
                                        boolean alreadyAutoPaused) {
        if (anchor == null) {
            return new ExpiryDecision(Expiry.NONE, 0, windowTicks, "");
        }
        final int age = nowTick - anchor.tick();
        if (age < 0) {
            return new ExpiryDecision(Expiry.EXPIRED, age, windowTicks, "anchor_in_future");
        }
        if (age > windowTicks) {
            return new ExpiryDecision(Expiry.EXPIRED, age, windowTicks, "older_than_window");
        }
        if (age == windowTicks && !alreadyAutoPaused) {
            return new ExpiryDecision(Expiry.AUTO_PAUSE, age, windowTicks, "at_window");
        }
        return new ExpiryDecision(Expiry.NONE, age, windowTicks, "");
    }

    // ------------------------------------------------------------------ 到位判定

    /**
     * 这一拍之后还要不要继续回退（🔴 <b>严格小于</b>）。
     *
     * <p>游标从"倒流开始那一 tick"往下走，每拍退一格；<b>游标等于锚点 tick 就停</b> ⇒
     * 恰好回退 {@code now-anchor} 个 tick（锚点 100 / 当前 160 ⇒ 回退 160…101 共 60 个 tick，
     * <b>锚点那一 tick 本身不退</b>：那一格的状态就是玩家按下"设锚点"时看到的状态）。
     *
     * <p>⚠️ 这就是判据 ③ 的负对照点：把这个比较写成"小于等于"（多退 1 tick）⇒
     * 自检 G4 必须变红、脚本里的变异体 C 必须报红。
     */
    public static boolean needsAnotherStep(int cursor, int anchorTick) {
        return anchorTick < cursor;
    }

    /**
     * 按"每一拍退一格"模拟一遍（<b>只给离线自检用</b>，生产里是
     * {@code ShanhaiReplayService#tick} 一拍一拍走的）。
     *
     * @return 实际回退的 tick 数（期望 = {@code startTick - anchorTick}）
     */
    public static int simulatedSteps(int startTick, int anchorTick, int limit) {
        int cursor = startTick;
        int steps = 0;
        while (needsAnotherStep(cursor, anchorTick)) {
            if (steps >= limit) {
                break;
            }
            --cursor;
            ++steps;
        }
        return steps;
    }

    // ------------------------------------------------------------------ 传送闸

    /** 回退到位之后，玩家侧该怎么办。 */
    public enum Restore {
        /** 同维度 ⇒ 把玩家传回锚点位置/朝向。 */
        TELEPORT,
        /** 跨维度 ⇒ 只回退世界状态，<b>不</b>传送玩家（完成行写 {@code anchor_dim_mismatch=1}）。 */
        DIM_MISMATCH
    }

    /**
     * 传送闸：<b>同维度才传</b>。
     *
     * <p>为什么跨维度不传：玩家此刻站在另一个维度的某个地方（可能正在路上），
     * 把他拽回主世界等于"倒流顺手改了他的维度"——那是用户口径里没有的动作。
     */
    public static Restore restoreGate(int anchorDim, int playerDim) {
        return anchorDim == playerDim ? Restore.TELEPORT : Restore.DIM_MISMATCH;
    }

    /**
     * 真正执行传送的落点（运行期 = {@code ShanhaiReplayAnchorRuntime} 里的
     * {@code ServerPlayer#teleportTo}；自检 = 记录调用的假体）。
     */
    public interface RestoreSink {

        /**
         * @return {@code false} = 传送失败（区块没加载/连接断了）
         */
        boolean teleport(int dim, double x, double y, double z, float yaw, float pitch);
    }

    /**
     * 一次"把玩家送回锚点"的结果（字段直接对应日志里的两个 {@code anchor_*} 标志位）。
     *
     * @param restored     {@code true} = 玩家已经站在锚点上 ⇒ 日志 {@code anchor_restored=1}
     * @param dimMismatch  {@code true} = 维度不一致，<b>没有</b>传送 ⇒ 日志 {@code anchor_dim_mismatch=1}
     * @param detail       {@code teleported} / {@code dim} / {@code no_anchor} / {@code no_player} / {@code teleport_failed}
     */
    public record RestoreResult(boolean restored, boolean dimMismatch, String detail) {

        public RestoreResult {
            if (detail == null) {
                detail = "";
            }
        }

        public int restoredFlag() {
            return this.restored ? 1 : 0;
        }

        public int dimMismatchFlag() {
            return this.dimMismatch ? 1 : 0;
        }

        public String toText() {
            return "restored=" + restoredFlag() + " dim_mismatch=" + dimMismatchFlag() + " detail=" + this.detail;
        }
    }

    /**
     * 🔴 <b>倒流收尾不搬人</b>（2026-11-14 用户原话：「他倒流结束那一刻会拉回玩家位置，不要拉回」）。
     *
     * <p>锚点仍然记着位置/朝向（用于锚点条、日志、{@code anchor status}），
     * 但收尾<b>一个坐标都不许改</b>：玩家的时间不受影响，空间也一样。
     *
     * <p>判据（自检 Y 组）：倒流前后玩家 {@code Pos} 逐位相同；
     * 【负对照】把 {@link #restore}（旧路径，会 {@code teleportTo}）放回来 ⇒ 判据红。
     */
    public static RestoreResult restoreAtEnd(ShanhaiReplayJournal.Anchor anchor) {
        return new RestoreResult(false, false, anchor == null ? "no_anchor" : "no_teleport_by_design");
    }

    /**
     * 回退到位：判闸 → 传送（同维度）→ 给出两个标志位。
     *
     * <p>⚠️ <b>生产路径已不再调用本方法</b>（见 {@link #restoreAtEnd}：用户明确要求不拉回）。
     * 它保留下来只做两件事：① 旧的 {@code anchor_restored} 语义可查；
     * ② 自检 Y 组的<b>负对照</b>需要一个"真的会搬人"的实现来证明那条断言不是空转。
     *
     * <p>🔴 跨维度时 {@code sink} <b>一次都不许被调用</b>（自检 G6 就断言这个"零次"）。
     */
    public static RestoreResult restore(ShanhaiReplayJournal.Anchor anchor, int playerDim, RestoreSink sink) {
        if (anchor == null) {
            return new RestoreResult(false, false, "no_anchor");
        }
        if (sink == null) {
            return new RestoreResult(false, false, "no_player");
        }
        if (restoreGate(anchor.dim(), playerDim) == Restore.DIM_MISMATCH) {
            return new RestoreResult(false, true, "dim");
        }
        final boolean ok = sink.teleport(anchor.dim(), anchor.x(), anchor.y(), anchor.z(),
                anchor.yaw(), anchor.pitch());
        return new RestoreResult(ok, false, ok ? "teleported" : "teleport_failed");
    }

    // ------------------------------------------------------------------ 文案

    /**
     * {@code /shanhai time anchor status} 那一行。
     *
     * @param anchor      锚点（{@code null} = 无锚点）
     * @param nowTick     当前世界 tick（用来算"距现在多少 tick"）
     * @param windowTicks 窗口（超过它就该重新设锚点了）
     */
    public static String statusLine(ShanhaiReplayJournal.Anchor anchor, int nowTick, int windowTicks) {
        if (anchor == null) {
            return "锚点: 无（先 /shanhai time anchor set 在当前位置记一个）";
        }
        final int dist = nowTick - anchor.tick();
        final String distText;
        if (dist < 0) {
            distText = "未来(" + dist + ")";
        } else {
            distText = dist + " tick（" + String.format(Locale.ROOT, "%.1f", dist / (double) ShanhaiReplayConst.TICKS_PER_SECOND) + " 秒）";
        }
        final String expired = dist > windowTicks ? " 已超出窗口(会拒绝: anchor_expired)" : "";
        return "锚点: tick=" + anchor.tick()
                + " dim=" + anchor.dim()
                + " pos=" + anchor.posText()
                + " yaw=" + String.format(Locale.ROOT, "%.1f", anchor.yaw())
                + " pitch=" + String.format(Locale.ROOT, "%.1f", anchor.pitch())
                + " journalCursor=" + anchor.journalCursor()
                + " 距现在=" + distText
                + " window=" + windowTicks
                + " stale=" + (anchor.stale() ? 1 : 0)
                + (anchor.stale()
                        ? " stale_reason=" + anchor.staleReason() + " stale_at=" + anchor.staleAtTick()
                        : "")
                + expired;
    }

    /**
     * 设锚点成功那一行（日志与聊天栏共用；字段顺序稳定，便于 grep）。
     */
    public static String setLine(ShanhaiReplayJournal.Anchor anchor) {
        return anchor == null ? "anchor=null" : anchor.toText();
    }
}
