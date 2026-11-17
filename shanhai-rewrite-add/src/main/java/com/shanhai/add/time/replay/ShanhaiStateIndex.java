package com.shanhai.add.time.replay;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.world.level.block.state.BlockState;

/**
 * 山海重构 · <b>{@code BlockState} ↔ 小整数</b>（服务端单例；MC 依赖只在这一层）。
 *
 * <h2>为什么要索引，而不是直接存 {@code BlockState} 对象</h2>
 * 环形缓冲（{@link ShanhaiReplayJournal}）刻意<b>零 MC 依赖</b>，这样它能进离线自检。
 * 存对象引用也"能用"，但那样缓冲就和游戏类解耦不开了。索引化之后：
 * <ul>
 *   <li>日志/断言里看到的是稳定的整数，跨轮可比；</li>
 *   <li>每条记录只多一个 {@code int}（而不是一个可能被 GC 的强引用）。</li>
 * </ul>
 *
 * <h2>为什么用 {@link IdentityHashMap} 而不是 {@code HashMap}</h2>
 * 原版 {@code BlockState} 是<b>规格化单例</b>（每个 block+properties 组合全局只有一个实例，
 * 见 {@code Block.BLOCK_STATE_REGISTRY}），所以身份相等就是值相等，恒等哈希最便宜。
 * 万一某个第三方 mod 造了"值相等但不同实例"的状态（现实中只可能来自反射/序列化直造），
 * 本类会把它当成两个不同的 id —— 后果只是<b>多记一条</b>，不会写错方块。
 *
 * <h2>关于设计稿 §3 的 {@code Block.getId} 兜底</h2>
 * 设计写的是"溢出(>65535)用 {@code Block.getId} 兜底"。本实现用<b>自有 int 下标</b>（不受 65535 约束），
 * 所以兜底用不上了；只有到 {@link ShanhaiReplayConst#MAX_STATES}（20 万种状态）才会停记并计数，
 * 那是"整合包有 20 万个不同的 blockstate"才会发生的事，属于保险丝而不是正常路径。
 */
public final class ShanhaiStateIndex {

    private static final Map<BlockState, Integer> ID = new IdentityHashMap<>();
    private static final List<BlockState> BY_ID = new ArrayList<>();

    /** 索引满了之后"没能索引"的次数（记一次就够，别刷日志）。 */
    private static long unindexed;

    /** 取（或分配）状态索引；{@code -1} = 索引满了，这一笔不该被记录。 */
    public static synchronized int idOf(BlockState state) {
        if (state == null) {
            return -1;
        }
        final Integer hit = ID.get(state);
        if (hit != null) {
            return hit.intValue();
        }
        if (BY_ID.size() >= ShanhaiReplayConst.MAX_STATES) {
            ++unindexed;
            return -1;
        }
        final int id = BY_ID.size();
        BY_ID.add(state);
        ID.put(state, Integer.valueOf(id));
        return id;
    }

    /** 索引 → 状态；越界返回 {@code null}（调用方必须当成"写不了"处理，不许抛）。 */
    public static synchronized BlockState stateOf(int id) {
        if (id < 0 || id >= BY_ID.size()) {
            return null;
        }
        return BY_ID.get(id);
    }

    /**
     * 只查不建（写后回读用）。
     *
     * <p>与 {@link #idOf} 的区别：没见过就返回 {@code -1}，<b>不</b>往表里塞新条目。
     * 回读是每笔写都跑的热路径，塞新条目会把表撑大（而且回读到的状态本来就该在表里）。
     */
    public static synchronized int peek(BlockState state) {
        if (state == null) {
            return -1;
        }
        final Integer hit = ID.get(state);
        return hit == null ? -1 : hit.intValue();
    }

    /** 已经索引了多少种状态（status/日志用）。 */
    public static synchronized int size() {
        return BY_ID.size();
    }

    /** 没能索引的次数（>0 说明有变化被丢掉了，日志里必须能看见）。 */
    public static synchronized long unindexedCount() {
        return unindexed;
    }

    /** 换世界/重开服时清空（每次新会话重新索引，避免跨存档残留强引用）。 */
    public static synchronized void clear() {
        ID.clear();
        BY_ID.clear();
        unindexed = 0L;
    }

    private ShanhaiStateIndex() {
    }
}
