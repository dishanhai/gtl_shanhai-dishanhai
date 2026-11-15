package com.shanhai.add.time.replay;

import java.util.List;

/**
 * 山海重构 · <b>「写回位置」与「发给客户端」的配对器</b>（🔴 零 Minecraft 依赖，别加 import）。
 *
 * <h2>1. 为什么要把这两件事放进同一个纯类</h2>
 * 2026-11-14 真机：用户「猪还是没有倒着走」，而服务端日志里 {@code motion=15426/15256}
 * 看起来全绿 —— 因为旧实现把<b>写回</b>（{@code Entity#moveTo}）和<b>发包</b>
 * （{@code ClientboundTeleportEntityPacket}）写在同一个 MC 方法里，
 * <b>"写了但没发"和"发了但没收到"在日志上分不开</b>，也没法离线断言。
 *
 * <p>现在契约只有一句：<b>每一次写回成功，必须恰好请求一次发包</b>。
 * 判据（自检 U 组）：
 * <ol>
 *   <li>5 条同一只猪、每 tick +1 格 ⇒ 写回顺序 {@code 5,4,3,2,1} 且 {@code sent==5}；</li>
 *   <li>【负对照】{@code sender == null}（= 旧实现只 {@code moveTo} 不发包）⇒ {@code sent==0}，判据红；</li>
 *   <li>【负对照】写回失败的那一条<b>不许</b>发包（否则客户端收到幽灵实体移动）。</li>
 * </ol>
 *
 * <h2>2. 计数口径</h2>
 * <ul>
 *   <li>{@code moved} —— 位置真的写进去了几条（= 旧日志里的 {@code motion_applied}）；</li>
 *   <li>{@code sentMoves} —— 产生过发包请求的条数（新读数 {@code motion_sent}，<b>与 moved 分开</b>）；</li>
 *   <li>{@code sentPackets} —— 发包请求实际覆盖的玩家数之和（{@code motion_packets}）。</li>
 * </ul>
 * 三个数一起看才能分开"没写回" / "写回了没发" / "发了没人收"。
 */
public final class ShanhaiReplayMotionDispatch {

    /**
     * 一次派发的结果。
     *
     * @param moved        位置写回成功的条数
     * @param failed       找不到实体 / 写回失败的条数
     * @param sentMoves    发包请求次数（每条成功的 move 恰好一次）
     * @param sentPackets  请求实际覆盖的玩家数之和
     */
    public record Result(int moved, int failed, int sentMoves, int sentPackets) {

        public static Result empty() {
            return new Result(0, 0, 0, 0);
        }

        /** 判据行文本（{@code step=} / {@code done} 行都用它）。 */
        public String toText() {
            return "motion_applied=" + this.moved + " motion_sent=" + this.sentMoves
                    + " motion_packets=" + this.sentPackets + " motion_failed=" + this.failed;
        }
    }

    /**
     * 发包落点（运行期 = {@code ShanhaiReplayEngine#sendMove}：直发
     * {@code ClientboundTeleportEntityPacket}；自检 = 记录调用序的假体）。
     */
    public interface Sender {

        /**
         * 请求把这只实体的<b>当前位置</b>发给客户端。
         *
         * @return 实际覆盖的玩家数（{@code 0} = 没有一个玩家在收包范围内，但那仍然算一次请求）
         */
        int sendMove(int dim, long uuidMost, long uuidLeast);
    }

    /**
     * 按给定顺序（<b>LIFO</b>，由 {@link ShanhaiReplayPacer} 保证）写回位置，并在每次成功后请求一次发包。
     *
     * @param newestFirst 一批运动记录（新→旧）
     * @param sink        位置落点
     * @param sender      发包落点（{@code null} ⇒ 一次都不发，供负对照用）
     */
    public static Result apply(List<ShanhaiReplayEntityMotionJournal.Motion> newestFirst,
                               ShanhaiReplayEntityMotionJournal.Sink sink, Sender sender) {
        if (newestFirst == null || newestFirst.isEmpty() || sink == null) {
            return Result.empty();
        }
        int moved = 0;
        int failed = 0;
        int sentMoves = 0;
        int sentPackets = 0;
        for (ShanhaiReplayEntityMotionJournal.Motion m : newestFirst) {
            final boolean ok;
            try {
                ok = sink.move(m.dim(), m.uuidMost(), m.uuidLeast(), m.x(), m.y(), m.z(),
                        m.yRot(), m.xRot());
            } catch (Throwable t) {
                ++failed;
                continue;
            }
            if (!ok) {
                ++failed;
                // 🔴 位置没写进去 ⇒ 绝不许发包（那会让客户端看到"幽灵实体在动"）
                continue;
            }
            ++moved;
            if (sender == null) {
                continue;   // 负对照：只 moveTo，不发包 ⇒ sentMoves 必须停在 0
            }
            try {
                final int players = sender.sendMove(m.dim(), m.uuidMost(), m.uuidLeast());
                ++sentMoves;
                sentPackets += Math.max(0, players);
            } catch (Throwable ignored) {
                // 发包失败不许影响位置本身（位置已经写对了）
            }
        }
        return new Result(moved, failed, sentMoves, sentPackets);
    }

    private ShanhaiReplayMotionDispatch() {
    }
}
