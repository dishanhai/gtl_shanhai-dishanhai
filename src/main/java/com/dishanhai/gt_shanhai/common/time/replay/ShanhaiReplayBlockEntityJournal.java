package com.dishanhai.gt_shanhai.common.time.replay;

import java.util.List;

/**
 * 山海重构 · <b>方块实体的"被标记变化就差分"时间线</b>（🔴 零 Minecraft 依赖，别加 import）。
 *
 * <h2>1. 为什么是"被标记才差"，不是"每 N tick 整块快照"</h2>
 * 用户原话：「别周期兜底，那样看上去就是世界非连续的了」 ⇒ 回退必须<b>逐 tick 连续</b>。
 * 所以这一层的触发点是 {@code BlockEntity#setChanged()}（HEAD 只<b>打标记</b>，不做序列化），
 * 每个外层 tick 末只对"本 tick 被标记过的 BE"做一次 NBT 差分：
 * <pre>
 *   标记集(pos) --tick 末--> 读 newNbt --与上一次的 baseline 比较--> 只存变化的 key 的 before 值
 * </pre>
 * 好处：① 每 tick 连续、无跳变；② 只记真变了的东西 ⇒ 内存是"变化量"级；
 * ③ 覆盖到第三方 mod 的方块实体（只要它调 {@code setChanged}）。
 *
 * <h2>2. 🔴 已知漏点（必须写进覆盖矩阵，不许用周期快照去补）</h2>
 * <b>不调 {@code setChanged()} 的方块实体不会被记</b>。GT 机器的 {@code RecipeLogic} 就是典型：
 * {@code setProgress(int)} 只写字段（javap 实证：{@code putfield progress; return}），
 * <b>不</b>调 {@code markDirty()} ⇒ 它的进度靠 {@link ShanhaiReplayProgressJournal} 那条瘦日志单独记账。
 *
 * <h2>3. 为什么存"最小差"而不是整份 before</h2>
 * 整份 NBT 每条 0.3–3 KB，200 台机器 × 1200 tick 就是几百 MB（详见 {@link ShanhaiReplayBudget}）。
 * 最小差只存"这一 tick 变了的顶层 key 的 before 值"，量级是几十字节；回退时用一个
 * <b>每 pos 只留一份的工作 NBT</b>（首次触碰时从活着的 BE 读一次）把差反向盖回去，再
 * {@code BlockEntity#load} 整份 —— 既省内存，又不会拿半份 NBT 去覆盖第三方 BE 的未知字段。
 *
 * <p>⚠️ 粒度就是<b>顶层 key</b>：一个变了的 {@code CompoundTag}/{@code ListTag} 值整块存。
 */
public final class ShanhaiReplayBlockEntityJournal {

    /**
     * 一条方块实体差分。
     *
     * @param tick        世界 tick
     * @param dim         维度小整数
     * @param pos         {@code BlockPos#asLong()}
     * @param payload     差分槽号（MC 侧池里是 {@code Map<String, Tag> before} + 需要删除的 key 集合）
     * @param changedKeys 这次差分涉及多少个顶层 key（诊断/预算用）
     */
    public record BeDiff(int tick, int dim, long pos, int payload, int changedKeys) {

        public String toText() {
            return "tick=" + this.tick + " dim=" + this.dim + " pos=" + this.pos
                    + " payload=" + this.payload + " keys=" + this.changedKeys;
        }

        @Override
        public String toString() {
            return toText();
        }
    }

    private final ShanhaiReplayTimeRing<BeDiff> ring;

    public ShanhaiReplayBlockEntityJournal(int capacity) {
        this.ring = new ShanhaiReplayTimeRing<>(capacity);
    }

    /**
     * 差分非空才记（空差 = 这一 tick 的 {@code setChanged} 什么也没改出来，记了只是垃圾）。
     */
    public static boolean shouldRecord(int changedKeys) {
        return changedKeys > 0;
    }

    /**
     * 记一条。同一 tick 同一 pos 不会合并（每 tick 每 BE 只捕获一次；
     * 万一重复，LIFO 会让更早那条最后生效 ⇒ 结果仍然正确）。
     */
    public boolean record(int tick, int dim, long pos, int payload, int changedKeys) {
        if (!shouldRecord(changedKeys) || payload == ShanhaiReplayJournal.NO_PAYLOAD) {
            return false;
        }
        final BeDiff diff = new BeDiff(tick, dim, pos, payload, changedKeys);
        return this.ring.add(tick, pos, diff, null, ShanhaiReplayConst.MAX_PER_TICK);
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

    public List<BeDiff> snapshotNewestFirst() {
        return this.ring.snapshotNewestFirst();
    }

    public List<BeDiff> takeNewestTick() {
        return this.ring.takeNewestTick();
    }

    public void clear() {
        this.ring.clear();
    }

    public String summary() {
        return this.ring.summary();
    }

    // ------------------------------------------------------------------ 回退

    /** 回退落点（运行期 = 工作 NBT + {@code BlockEntity#load}；自检 = 假体）。 */
    public interface Sink {

        /**
         * 把一条差分反向盖回该 pos 的方块实体。
         *
         * @return {@code false} = 写不进去（BE 不在了 / 区块没加载 / 载荷丢了）
         */
        boolean apply(int dim, long pos, int payload);
    }

    /**
     * @param written   盖回去的条数
     * @param failed    失败条数
     */
    public record Result(int written, int failed) {

        public static Result empty() {
            return new Result(0, 0);
        }
    }

    /** 按 LIFO（新→旧）把方块实体差分反向盖回去。 */
    public static Result apply(List<BeDiff> newestFirst, Sink sink) {
        if (newestFirst == null || newestFirst.isEmpty() || sink == null) {
            return Result.empty();
        }
        int written = 0;
        int failed = 0;
        for (BeDiff d : newestFirst) {
            if (sink.apply(d.dim(), d.pos(), d.payload())) {
                ++written;
            } else {
                ++failed;
            }
        }
        return new Result(written, failed);
    }
}
