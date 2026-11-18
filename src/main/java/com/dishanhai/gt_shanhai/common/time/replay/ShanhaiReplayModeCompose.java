package com.dishanhai.gt_shanhai.common.time.replay;

import com.dishanhai.gt_shanhai.common.time.ShanhaiClockMode;

/**
 * 山海重构 · <b>「暂停」与「倒流」的可组合语义</b>（🔴 零 Minecraft 依赖，别加 import）。
 *
 * <h2>1. 用户口径（2026-11-14 12:5x 真机，原话）</h2>
 * <blockquote>「好像和我们的时间暂停有点不兼容？」</blockquote>
 * <blockquote>「我设置锚点之后，暂停时间，再 {@code /shanhai clock replay}，此时直接开始倒流了，
 * 而它应该继续记录才对」</blockquote>
 * ⇒ 这一条把"不兼容"说清楚了：<b>暂停 = 时间停在某一刻，倒流 = 从这一刻往回走</b>。
 * 两者不是互斥状态，而是"停"与"退"两种动作，可以叠加使用：
 * <pre>
 *   PAUSED 下：允许 anchor set / 允许 rewind（倒流开始后模式仍是 REPLAY，不叠加第二层状态）
 *   倒流结束：回到【开始前】的模式（PAUSED → PAUSED、LIVE → LIVE），不是一律回 LIVE
 * </pre>
 *
 * <h2>2. 为什么抽成纯逻辑</h2>
 * 判据要求"状态机表 {@code LIVE/PAUSED/REPLAY × {set anchor, rewind, finish}} 逐格断言"，
 * 而且要有<b>负对照</b>（改回"一律 LIVE" ⇒ {@code PAUSED→PAUSED} 那格必须红）。
 * 只要判定经过这个类，离线自检就能把整张表逐格打出来 —— 不需要开游戏。
 *
 * <h2>3. 唯一的口径（每一条都在自检 V 组里逐格断言）</h2>
 * <table border="1">
 *   <caption>动作 × 模式</caption>
 *   <tr><th>动作</th><th>LIVE</th><th>PAUSED</th><th>REPLAY</th></tr>
 *   <tr><td>{@link Action#ANCHOR_SET anchor set}</td><td>允许</td><td><b>允许</b></td>
 *       <td>{@code not_live_replay}</td></tr>
 *   <tr><td>{@link Action#REWIND time rewind}</td><td>允许</td><td><b>允许</b></td>
 *       <td>{@code not_live_replay}</td></tr>
 *   <tr><td>{@link Action#CLOCK_REPLAY clock replay}</td><td>允许</td>
 *       <td><b>拒绝 {@code paused_use_rewind}</b>（不是倒流的入口，别把"继续记录"变成"开始倒流"）</td>
 *       <td>{@code not_live_replay}</td></tr>
 * </table>
 *
 * <h2>4. 记录闸（B 的另一半）</h2>
 * {@link #recordsUnder(ShanhaiClockMode)}：<b>只有 REPLAY 不记</b>。
 * 暂停期间世界虽然冻着，玩家的走动、第三方 mod 的写、实体出现/消失都还可能发生
 * ⇒ 这些必须进 journal，否则"暂停 → 改 → 倒流"这条用不了（用户的诉求就是这条）。
 * 负对照：把"PAUSED 也停记录"放回去 ⇒ 自检 V3 判红。
 */
public final class ShanhaiReplayModeCompose {

    /** 可以"发起"的三个动作（收尾 {@code finish} 不是发起，见 {@link #restoreAfter}）。 */
    public enum Action {

        /** {@code /shanhai time anchor set}。 */
        ANCHOR_SET("anchor_set"),
        /** {@code /shanhai time rewind [秒]}（回到锚点 / 回退 N 秒）。 */
        REWIND("rewind"),
        /** {@code /shanhai clock replay [秒]}（老入口）。 */
        CLOCK_REPLAY("clock_replay");

        private final String code;

        Action(String code) {
            this.code = code;
        }

        /** 稳定字符串（日志/自检/聊天共用；别改）。 */
        public String code() {
            return this.code;
        }
    }

    /** 动作被拒的原因码（{@link #NONE} 以外都算拒绝；一律进日志并可 grep）。 */
    public enum Refusal {

        /** 没拒绝。 */
        NONE("none"),
        /**
         * 正在倒流（REPLAY）⇒ 拒绝。
         *
         * <p>此刻的世界是"半倒回去"的中间态：再设锚点会把中间态当参照，再开一次倒流是嵌套。
         */
        NOT_LIVE_REPLAY("not_live_replay"),
        /**
         * 暂停（PAUSED）下敲了 <b>{@code /clock replay}</b> ⇒ 拒绝，并提示改用 {@code /time rewind}。
         *
         * <p>用户原话：「暂停时间，再 {@code /shanhai clock replay}，此时直接开始倒流了，
         * 而它应该继续记录才对」。⇒ 这个入口在暂停下的正确回应是"什么都不做，继续记录"，
         * 真想倒流请显式用 {@code /time rewind}（那时是用户主动放弃"继续记录"）。
         */
        PAUSED_USE_REWIND("paused_use_rewind");

        private final String code;

        Refusal(String code) {
            this.code = code;
        }

        /** 稳定字符串（日志/自检共用；别改）。 */
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
                    return "可以";
                case NOT_LIVE_REPLAY:
                    return "正在倒流（REPLAY），等倒流结束再操作";
                case PAUSED_USE_REWIND:
                    return "暂停中不会开始倒流；要保持记录就什么都不用做，真的要倒流请用 /shanhai time rewind";
                default:
                    return this.code;
            }
        }
    }

    /**
     * 这个动作在当前模式下能不能发起。
     *
     * @param action 动作（{@code null} ⇒ 拒绝，码 {@link Refusal#NOT_LIVE_REPLAY} 不用：直接 NONE 之外的兜底见实现）
     * @param mode   世界时钟模式（服务端权威；{@code null} 视为 LIVE，失败方向是"放行"）
     */
    public static Refusal refuse(Action action, ShanhaiClockMode mode) {
        if (action == null) {
            return Refusal.NONE;
        }
        final ShanhaiClockMode m = mode == null ? ShanhaiClockMode.LIVE : mode;
        if (m == ShanhaiClockMode.REPLAY) {
            return Refusal.NOT_LIVE_REPLAY;
        }
        if (m == ShanhaiClockMode.PAUSED && action == Action.CLOCK_REPLAY) {
            return Refusal.PAUSED_USE_REWIND;
        }
        return Refusal.NONE;
    }

    /** 这个动作在当前模式下是否允许（{@link #refuse} 的布尔投影）。 */
    public static boolean allowed(Action action, ShanhaiClockMode mode) {
        return !refuse(action, mode).refused();
    }

    /**
     * 记录器在这个模式下要不要干活。
     *
     * <p>🔴 <b>只有 REPLAY 不记</b>（那时的世界是"倒流自己写的"，记了就是无限套娃）。
     * PAUSED 照记 —— 这是 2026-11-14 用户口径的直接产物。
     */
    public static boolean recordsUnder(ShanhaiClockMode mode) {
        return com.dishanhai.gt_shanhai.common.time.ReplayRecording.isEnabled() && mode != ShanhaiClockMode.REPLAY;
    }

    /**
     * 倒流收尾之后该回到哪个模式 —— <b>回到开始前的那个</b>。
     *
     * <p>用户口径：「暂停 = 时间停在某一刻，倒流 = 从这一刻往回走」⇒ 走回来还是停在那一刻。
     * <ul>
     *   <li>{@code PAUSED → PAUSED}（这一格就是负对照的抓点：旧实现一律回 LIVE）；</li>
     *   <li>{@code LIVE → LIVE}；</li>
     *   <li>{@code REPLAY / null → LIVE}（结构上不该出现：倒流中不允许再开倒流）。</li>
     * </ul>
     */
    public static ShanhaiClockMode restoreAfter(ShanhaiClockMode modeBefore) {
        if (modeBefore == ShanhaiClockMode.PAUSED) {
            return ShanhaiClockMode.PAUSED;
        }
        return ShanhaiClockMode.LIVE;
    }

    /**
     * 状态机表的一行（{@code status} / 启动日志 / 自检失败时的读数共用一份）。
     *
     * @return 例：{@code mode=LIVE record=true anchor=none rewind=none clock_replay=none finish->LIVE}
     */
    public static String rowLine(ShanhaiClockMode mode) {
        final ShanhaiClockMode m = mode == null ? ShanhaiClockMode.LIVE : mode;
        return "mode=" + m
                + " record=" + recordsUnder(m)
                + " anchor=" + refuse(Action.ANCHOR_SET, m).code()
                + " rewind=" + refuse(Action.REWIND, m).code()
                + " clock_replay=" + refuse(Action.CLOCK_REPLAY, m).code()
                + " finish->" + (m == ShanhaiClockMode.REPLAY ? ShanhaiClockMode.LIVE : restoreAfter(m));
    }

    /** 整张表（三行；启动时打一次，便于"代码跑的那套"和"文档写的那套"当场对齐）。 */
    public static String[] tableLines() {
        final ShanhaiClockMode[] all = ShanhaiClockMode.values();
        final String[] out = new String[all.length];
        for (int i = 0; i < all.length; i++) {
            out[i] = rowLine(all[i]);
        }
        return out;
    }

    private ShanhaiReplayModeCompose() {
    }
}
