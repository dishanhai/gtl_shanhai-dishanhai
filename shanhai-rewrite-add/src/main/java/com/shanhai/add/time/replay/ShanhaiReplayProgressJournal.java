package com.shanhai.add.time.replay;

import java.util.List;

/**
 * 山海重构 · <b>机器进度时间线（瘦日志）</b>（🔴 零 Minecraft 依赖，别加 import）。
 *
 * <h2>1. 口径（用户原话："更不要提机器的进度条倒转了"）</h2>
 * 只记"配方进度真的变了"的方块实体：{@code (tick, dim, pos, progressBefore, progressAfter)}。
 * 不变的一律不记（瘦日志）；同一 tick 同一 pos 合并成一条（保留最早的 before / 最新的 after）。
 *
 * <h2>2. 与"方块实体整份差分"的分工</h2>
 * 这一层是<b>进度条</b>（用户能一眼看到的那根）的显式账本，也是
 * {@code progress_unsupported} 的判据点；其它 BE 字段（库存 / 能量 / 流体）走
 * {@link ShanhaiReplayBlockEntityJournal} 的最小差。两层都记同一台机器不冲突：
 * 回退顺序是 <b>方块 → BE 差分 → 进度</b>，进度最后写，读到的就是进度日志里的值。
 */
public final class ShanhaiReplayProgressJournal {

    /**
     * 一条进度变化。
     *
     * @param tick   世界 tick
     * @param dim    维度小整数
     * @param pos    {@code BlockPos#asLong()}
     * @param before 变化前（= 回退要写回的值）
     * @param after  变化后（只用于诊断/去重）
     */
    public record Progress(int tick, int dim, long pos, int before, int after) {

        public String toText() {
            return "tick=" + this.tick + " dim=" + this.dim + " pos=" + this.pos
                    + " before=" + this.before + " after=" + this.after;
        }

        @Override
        public String toString() {
            return toText();
        }
    }

    private final ShanhaiReplayTimeRing<Progress> ring;

    public ShanhaiReplayProgressJournal(int capacity) {
        this.ring = new ShanhaiReplayTimeRing<>(capacity);
    }

    /**
     * 记一条进度变化（{@code before == after} 直接丢）。
     */
    public boolean record(int tick, int dim, long pos, int before, int after) {
        if (before == after) {
            return false;
        }
        final Progress progress = new Progress(tick, dim, pos, before, after);
        return this.ring.add(tick, pos, progress,
                (older, newer) -> older.dim() != newer.dim()
                        ? null
                        : new Progress(newer.tick(), newer.dim(), newer.pos(),
                                older.before(), newer.after()),
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

    public List<Progress> snapshotNewestFirst() {
        return this.ring.snapshotNewestFirst();
    }

    public List<Progress> takeNewestTick() {
        return this.ring.takeNewestTick();
    }

    public void clear() {
        this.ring.clear();
    }

    public String summary() {
        return this.ring.summary();
    }

    // ------------------------------------------------------------------ 回退

    /** 写回结果。 */
    public enum Outcome {
        /** 真的写回去了。 */
        WRITTEN,
        /** 这台机器<b>没有可写的进度</b>（既没有进度字段，NBT 里也没有进度键）⇒ 报告里点名。 */
        UNSUPPORTED,
        /** 有入口但写失败（区块没加载 / 机器已经被拆了）。 */
        FAILED
    }

    /** 回退落点（运行期 = 重新设置 {@code RecipeLogic#progress}；自检 = 假体）。 */
    public interface Sink {

        Outcome write(int dim, long pos, int progress);
    }

    /**
     * @param written      写成功的条数
     * @param unsupported  没有可写进度字段的条数（逐条记在报告里）
     * @param failed       写失败的条数
     */
    public record Result(int written, int unsupported, int failed) {

        public static Result empty() {
            return new Result(0, 0, 0);
        }
    }

    /** 按 LIFO（新→旧）把进度写回去。 */
    public static Result apply(List<Progress> newestFirst, Sink sink) {
        if (newestFirst == null || newestFirst.isEmpty() || sink == null) {
            return Result.empty();
        }
        int written = 0;
        int unsupported = 0;
        int failed = 0;
        for (Progress p : newestFirst) {
            switch (sink.write(p.dim(), p.pos(), p.before())) {
                case WRITTEN -> ++written;
                case UNSUPPORTED -> ++unsupported;
                default -> ++failed;
            }
        }
        return new Result(written, unsupported, failed);
    }
}
