package com.dishanhai.gt_shanhai.common.time.replay;

import com.dishanhai.gt_shanhai.common.time.ShanhaiClockMode;

/**
 * 山海重构 · <b>实体出现/消失的"记不记"判定核</b>（🔴 零 Minecraft 依赖，别加 import）。
 *
 * <h2>1. 只记两件事</h2>
 * <ul>
 *   <li><b>出现</b>（{@code EntityJoinLevelEvent}，且 {@code loadedFromDisk()==false}）⇒ 回退时"后出现的被移除"；</li>
 *   <li><b>消失</b>（{@code EntityLeaveLevelEvent} + 每 tick 的已移除扫描）⇒ 回退时用整份 NBT 在记录位置重建。</li>
 * </ul>
 *
 * <h2>2. 四条不记（每条都是实证踩过的坑）</h2>
 * <table border="1">
 *   <caption>判据表</caption>
 *   <tr><th>码</th><th>为什么</th></tr>
 *   <tr><td>{@code skip_player}</td><td><b>玩家永远豁免</b>（用户口径："玩家的时间不受影响"）。
 *       记了玩家就会在回退时把玩家删掉/重建 ⇒ 直接掉线崩客户端。</td></tr>
 *   <tr><td>{@code skip_loaded_from_disk}</td><td>区块加载会把一堆老实体"重新加入世界"，
 *       那不是"新出现的生物"，记了会让每次跨区块都产生成百条假事件。</td></tr>
 *   <tr><td>{@code skip_client}</td><td>客户端也有 join/leave，记了会把表现层当历史。</td></tr>
 *   <tr><td>{@code skip_not_live} / {@code skip_replay_write}</td>
 *       <td>正在倒流（{@code REPLAY}）/ 当前线程正在替倒流自己重建/移除实体</td>
 *       <td>回退期间自己重建/移除的实体绝不能再被记成历史（负对照）。
 *       ⚠️ 2026-11-14 起 <b>{@code PAUSED} 照记</b>（用户口径：暂停期间要继续记录）</td></tr>
 * </table>
 */
public final class ShanhaiReplayEntityGate {

    /** 一条实体事件该不该进历史。 */
    public enum Reason {

        /** 记。 */
        ACCEPT("accept"),
        /** 玩家永远豁免。 */
        SKIP_PLAYER("skip_player"),
        /** 从存档加载（区块加载）不算"出现"。 */
        SKIP_LOADED_FROM_DISK("skip_loaded_from_disk"),
        /** 客户端表现层。 */
        SKIP_CLIENT("skip_client"),
        /** 正在倒流（{@code REPLAY}），不计。⚠️ {@code PAUSED} 从 2026-11-14 起<b>照记</b>。 */
        SKIP_NOT_LIVE("skip_not_live"),
        /** 当前线程正在替倒流自己写世界。 */
        SKIP_REPLAY_WRITE("skip_replay_write");

        private final String code;

        Reason(String code) {
            this.code = code;
        }

        public String code() {
            return this.code;
        }

        public boolean accepted() {
            return this == ACCEPT;
        }
    }

    /**
     * @param mode           世界时钟模式
     * @param isPlayer       是不是玩家（{@code ServerPlayer}）
     * @param clientSide     事件发生在客户端世界
     * @param inReplayWrite  是否在回放写临界区
     * @param loadedFromDisk 只对"出现"有意义：是不是从存档加载进来的
     */
    public static Reason decide(ShanhaiClockMode mode, boolean isPlayer, boolean clientSide,
                               boolean inReplayWrite, boolean loadedFromDisk) {
        if (isPlayer) {
            return Reason.SKIP_PLAYER;
        }
        if (clientSide) {
            return Reason.SKIP_CLIENT;
        }
        if (inReplayWrite) {
            return Reason.SKIP_REPLAY_WRITE;
        }
        if (!ShanhaiReplayModeCompose.recordsUnder(mode)) {
            return Reason.SKIP_NOT_LIVE;
        }
        if (loadedFromDisk) {
            return Reason.SKIP_LOADED_FROM_DISK;
        }
        return Reason.ACCEPT;
    }

    private ShanhaiReplayEntityGate() {
    }
}
