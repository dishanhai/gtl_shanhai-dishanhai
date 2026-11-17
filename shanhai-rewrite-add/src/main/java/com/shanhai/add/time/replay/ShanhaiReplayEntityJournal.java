package com.shanhai.add.time.replay;

import java.util.List;

/**
 * 山海重构 · <b>实体的"出现 / 消失"时间线</b>（🔴 零 Minecraft 依赖，别加 import）。
 *
 * <h2>1. 口径（用户原话："我刷了几个生物也没回去"）</h2>
 * 只记两件事，回退就是把它们反过来做：
 * <table border="1">
 *   <caption>正放 / 倒放</caption>
 *   <tr><th>记录</th><th>正放（录制）</th><th>倒放（回退）</th></tr>
 *   <tr><td>{@link Kind#APPEAR}</td><td>{@code EntityJoinLevelEvent}（非玩家、非从存档加载）</td>
 *       <td>按 UUID 把这只实体<b>移除</b>（"后出现的"不许存在过）</td></tr>
 *   <tr><td>{@link Kind#DISAPPEAR}</td><td>{@code EntityLeaveLevelEvent} 或 tick 末扫描发现已 removed</td>
 *       <td>用记录的<b>整份实体 NBT</b> 在记录位置<b>重建</b>（"后消失的"必须活过来）</td></tr>
 *   <tr><td>{@link Kind#CANCELLED}</td><td>同一 tick 内先出现又消失（净效果为零）</td><td>什么都不做</td></tr>
 * </table>
 *
 * <h2>2. 为什么 NBT 只存"消失时"那一份</h2>
 * "出现"只需要 UUID（回退时删掉它）；"消失"要能重新造出来 ⇒ 必须整份 NBT
 * （{@code entity.saveWithoutId(new CompoundTag())} + 注册名，见 MC 侧
 * {@code ShanhaiReplayEntityRecorder}）。玩家<b>永远</b>不进这条时间线（负对照见自检 H 组）。
 */
public final class ShanhaiReplayEntityJournal {

    /** 事件种类。 */
    public enum Kind {
        /** 出现在世界里。 */
        APPEAR,
        /** 从世界里消失（死亡 / 丢弃 / 被移除 / 换维度离开）。 */
        DISAPPEAR,
        /** 同 tick 内出现又消失：净效果为零，回退时什么都不做。 */
        CANCELLED
    }

    /**
     * 一条实体事件。
     *
     * @param tick      世界 tick
     * @param dim       维度小整数（{@code ShanhaiReplayEngine#dimIdOf}）
     * @param uuidMost  实体 UUID 高 64 位（回退时按它找实体；不用对象引用，避免持有已卸载实体）
     * @param uuidLeast 实体 UUID 低 64 位
     * @param kind      种类
     * @param payload   {@link Kind#DISAPPEAR} 的整份 NBT 槽号；其余为
     *                  {@link ShanhaiReplayJournal#NO_PAYLOAD}
     */
    public record Event(int tick, int dim, long uuidMost, long uuidLeast, Kind kind, int payload) {

        public String toText() {
            return "tick=" + this.tick + " dim=" + this.dim
                    + " uuid=" + Long.toHexString(this.uuidMost) + ":" + Long.toHexString(this.uuidLeast)
                    + " kind=" + this.kind + " payload=" + this.payload;
        }

        @Override
        public String toString() {
            return toText();
        }
    }

    private final ShanhaiReplayTimeRing<Event> ring;

    public ShanhaiReplayEntityJournal(int capacity) {
        this.ring = new ShanhaiReplayTimeRing<>(capacity);
    }

    /**
     * 记一条。
     *
     * <p>合并语义：同 tick 同 UUID 先出现后消失 ⇒ 合并成 {@link Kind#CANCELLED}（净效果为零）；
     * 同类重复 ⇒ 用新的那条（后一次才是真相）。
     */
    public boolean record(int tick, int dim, long uuidMost, long uuidLeast, Kind kind, int payload) {
        if (kind == Kind.CANCELLED) {
            return false;
        }
        final Event event = new Event(tick, dim, uuidMost, uuidLeast, kind, payload);
        return this.ring.add(tick, uuidMost * 31L + uuidLeast, event,
                (older, newer) -> {
                    if (older.dim() != newer.dim()) {
                        return null;   // 不同维度不会撞（撞了也不合并）
                    }
                    if (older.uuidMost() != newer.uuidMost() || older.uuidLeast() != newer.uuidLeast()) {
                        return null;
                    }
                    if (older.kind() != newer.kind()) {
                        return new Event(newer.tick(), newer.dim(), newer.uuidMost(), newer.uuidLeast(),
                                Kind.CANCELLED, ShanhaiReplayJournal.NO_PAYLOAD);
                    }
                    return newer;
                },
                ShanhaiReplayConst.MAX_PER_TICK);
    }

    public int size() {
        return this.ring.size();
    }

    public boolean isEmpty() {
        return this.ring.isEmpty();
    }

    public int newestTick() {
        return this.ring.newestTick();
    }

    public int oldestTick() {
        return this.ring.oldestTick();
    }

    public long droppedOldest() {
        return this.ring.droppedOldest();
    }

    public long overflow() {
        return this.ring.overflow();
    }

    public List<Event> snapshotNewestFirst() {
        return this.ring.snapshotNewestFirst();
    }

    public List<Event> takeNewestTick() {
        return this.ring.takeNewestTick();
    }

    public void clear() {
        this.ring.clear();
    }

    public String summary() {
        return this.ring.summary();
    }

    // ------------------------------------------------------------------ 回退

    /** 回退的落点（运行期 = {@code ShanhaiReplayEngine}；自检 = 记录调用的假体）。 */
    public interface Sink {

        /**
         * 把"后来才出现"的实体删掉。
         *
         * @return {@code false} = 找不到/删不掉（已卸载的区块、已经没了）
         */
        boolean remove(int dim, long uuidMost, long uuidLeast);

        /**
         * 用整份 NBT 重建"后来消失"的实体（同维度、记录位置）。
         *
         * @return {@code false} = 重建失败（类型没注册 / NBT 坏了）
         */
        boolean rebuild(int dim, int payload);
    }

    /**
     * @param removed  删掉的实体数（= APPEAR 的条数）
     * @param rebuilt  重建的实体数（= DISAPPEAR 的条数）
     * @param failed   删/建失败的条数
     * @param skipped  被抵消（{@link Kind#CANCELLED}）的条数
     */
    public record Result(int removed, int rebuilt, int failed, int skipped) {

        public static Result empty() {
            return new Result(0, 0, 0, 0);
        }

        public int total() {
            return this.removed + this.rebuilt;
        }
    }

    /**
     * 按给定顺序（LIFO）回退一批实体事件。
     */
    public static Result apply(List<Event> newestFirst, Sink sink) {
        if (newestFirst == null || newestFirst.isEmpty() || sink == null) {
            return Result.empty();
        }
        int removed = 0;
        int rebuilt = 0;
        int failed = 0;
        int skipped = 0;
        for (Event e : newestFirst) {
            switch (e.kind()) {
                case APPEAR -> {
                    if (sink.remove(e.dim(), e.uuidMost(), e.uuidLeast())) {
                        ++removed;
                    } else {
                        ++failed;
                    }
                }
                case DISAPPEAR -> {
                    if (sink.rebuild(e.dim(), e.payload())) {
                        ++rebuilt;
                    } else {
                        ++failed;
                    }
                }
                default -> ++skipped;
            }
        }
        return new Result(removed, rebuilt, failed, skipped);
    }
}
