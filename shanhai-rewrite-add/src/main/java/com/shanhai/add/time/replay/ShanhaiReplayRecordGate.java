package com.shanhai.add.time.replay;

import com.shanhai.add.time.ShanhaiClockMode;

/**
 * 山海重构 · <b>方块变化的"记不记"判定核</b>（🔴 零 Minecraft 依赖，别加 import）。
 *
 * <h2>1. 为什么把这条判据单独抽出来</h2>
 * 倒流记录器挂在 {@code LevelChunk#setBlockState} 的 HEAD 上，那是方块写入的<b>最内层唯一入口</b>
 * （{@code Level#setBlock} / {@code Level#setBlockAndUpdate} / {@code Level#destroyBlock}→{@code removeBlock}
 * / 爆炸 → 全部经过它）。所以"入口覆盖"不是靠多挂几处，而是靠<b>这一处判据正确</b> ——
 * 判据一旦写错，受害面就是"整类操作都记不到"（2026-11 实证：玩家挖掉的方块一条都没回来）。
 *
 * <p>抽成纯函数的收益：{@code tools\replay-selfcheck} 的 H 组能用<b>三条真实入口构造的入参</b>
 * （挖 / 放 / 爆炸）各来一次，再用"回放自己写的块"做负对照 —— 不用开游戏。
 *
 * <h2>2. 五个理由码（每个都对应一种真实误记）</h2>
 * <table border="1">
 *   <caption>判据表</caption>
 *   <tr><th>码</th><th>什么时候</th><th>不挡会发生什么</th></tr>
 *   <tr><td>{@code accept}</td><td>服务端 + LIVE + 不是回放自己的写 + 状态真变了</td><td>——</td></tr>
 *   <tr><td>{@code skip_client}</td><td>客户端 {@code ClientLevel} 的区块</td><td>把表现层幻影方块当历史</td></tr>
 *   <tr><td>{@code skip_not_live}</td><td><b>只有 {@code REPLAY}</b>（2026-11-14 起 {@code PAUSED} <b>照记</b>）</td>
 *       <td>倒流自己的写进历史 ⇒ 无限套娃。暂停期间的写必须进历史，否则「暂停 → 改 → 倒流」这条用不了</td></tr>
 *   <tr><td>{@code skip_replay_write}</td><td>当前线程正处在回放写临界区</td><td>同上（更硬的一道：模式被别处改了也拦得住）</td></tr>
 *   <tr><td>{@code skip_noop}</td><td>前后状态相同</td><td>GT 机器同态自写 ⇒ 1 分钟 12 万条</td></tr>
 *   <tr><td>{@code skip_no_state}</td><td>状态索引建不出来（越界/为负）</td><td>记一条写不回去的垃圾</td></tr>
 * </table>
 *
 * <p>⚠️ 顺序是刻意的：先判"我们自己的写"（最要紧的负对照），再判同态，最后判索引。
 */
public final class ShanhaiReplayRecordGate {

    /** 一条方块变化该不该进历史。 */
    public enum Reason {

        /** 记。 */
        ACCEPT("accept"),
        /** 客户端表现层，不记。 */
        SKIP_CLIENT("skip_client"),
        /**
         * 正在倒流（{@code REPLAY}），不记。
         *
         * <p>⚠️ 2026-11-14 起 <b>{@code PAUSED} 也记</b>（用户口径：暂停期间"应该继续记录"）；
         * 码名保留 {@code skip_not_live} 是为了不动日志/自检的既有字符串契约，
         * 但它的触发条件已经收窄成"只有 {@code REPLAY}"。
         */
        SKIP_NOT_LIVE("skip_not_live"),
        /** 当前线程正在替倒流自己写世界，不记（负对照就靠这一条）。 */
        SKIP_REPLAY_WRITE("skip_replay_write"),
        /** 前后状态相同，不记。 */
        SKIP_NOOP("skip_noop"),
        /** 状态索引建不出来，不记。 */
        SKIP_NO_STATE("skip_no_state");

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
     * 判一笔方块变化。
     *
     * @param mode           当前世界时钟模式（服务端权威）
     * @param clientSide     这一笔是不是发生在客户端 {@code LevelChunk}
     * @param inReplayWrite  当前线程是否在 {@code ShanhaiReplayGate#beginReplayWrite()} 临界区里
     * @param hasStateIndex  {@code ShanhaiStateIndex#idOf(before/after)} 是否都成功（{@code >= 0}）
     * @param sameState      前后状态是不是同一个（实例相同或值相等）
     */
    public static Reason decide(ShanhaiClockMode mode, boolean clientSide, boolean inReplayWrite,
                                boolean hasStateIndex, boolean sameState) {
        if (clientSide) {
            return Reason.SKIP_CLIENT;
        }
        if (inReplayWrite) {
            return Reason.SKIP_REPLAY_WRITE;
        }
        if (!ShanhaiReplayModeCompose.recordsUnder(mode)) {
            return Reason.SKIP_NOT_LIVE;
        }
        if (sameState) {
            return Reason.SKIP_NOOP;
        }
        if (!hasStateIndex) {
            return Reason.SKIP_NO_STATE;
        }
        return Reason.ACCEPT;
    }

    private ShanhaiReplayRecordGate() {
    }
}
