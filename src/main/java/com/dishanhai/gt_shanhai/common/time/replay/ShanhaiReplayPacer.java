package com.dishanhai.gt_shanhai.common.time.replay;

import java.util.ArrayList;
import java.util.List;

/**
 * 山海重构 · <b>逐步反向播放的节拍器</b>（🔴 零 Minecraft 依赖，别加 import）。
 *
 * <h2>1. 用户口径（这是 A3 的验收点）</h2>
 * <blockquote>「我看我放置方块回去了，但是他回溯到结束瞬间回去的，而不是根据我在时间线上面的操作而回去的，
 * 我要的是世界复原需要根据时间线上的变化，回溯时恰好根据那个数相反的样子回溯」</blockquote>
 * ⇒ 一次外层 tick <b>只退一个时间片</b>；"一个时间片" = 记录里<b>同一个 tick</b> 的全部改动
 * （方块 + 方块实体差分 + 机器进度 + 实体事件 + 世界标量）。顺序 <b>LIFO</b>（新的先退）。
 * ⇒ 于是"倒流 N 秒"要花 N 秒完成（{@code speed = 1.0}，见 {@link ShanhaiReplayConst#REPLAY_SPEED_DEFAULT}）。
 *
 * <h2>2. 一个时间片内六层的固定顺序（刻意的，不是随便排的）</h2>
 * <ol>
 *   <li><b>方块</b>：先让格子回到过去那个状态（方块实体的存活与否在这一步决定）；</li>
 *   <li><b>方块实体最小差</b>：方块写回之后 BE 才存在 ⇒ 必须排在方块之后；</li>
 *   <li><b>机器进度</b>：排在 BE 差分之后，用显式 {@code setProgress} 覆盖，
 *       让进度条读到的就是瘦日志里的值；</li>
 *   <li><b>实体（出现/消失）</b>：方块都复位之后再删/建生物，避免把生物重建进一个即将被方块占满的格子；</li>
 *   <li><b>实体运动</b>：<b>排在出现/消失之后</b> —— 先确定"这一拍谁还在"，
 *       再把还在的那些拉回过去的位置；本 tick 被删掉的实体<b>不写位置</b>
 *       （否则客户端会收到幽灵实体移动）。这是"猪倒着走"这一层的落点；
 *       🔴 写回与<b>发包成对</b>（{@link ShanhaiReplayMotionDispatch}）：每条成功的写回必须产生一次发包请求；</li>
 *   <li><b>生物血量</b>：紧挨在运动层之后（先位置、后血量）；本 tick 被删掉的实体同样不写；</li>
 *   <li><b>世界标量</b>：最后拨时间/天气（它是"世界的外壳"，不影响上面六层）。</li>
 * </ol>
 *
 * <h2>3. 空转口径（1:1 墙钟的来源）</h2>
 * 游标从"倒流开始那一 tick"往下走，<b>每外层 tick 退一格</b>；那一格没有记录就是空转一拍。
 * 所以"倒流 5 秒"在墙钟上正好 5 秒，且每个位置最终都等于 5 秒前那个 tick 的状态。
 *
 * <h2>4. 加速倍率</h2>
 * {@code speed} 只允许 {@code >= 1}；{@code 1.0} ⇒ 每外层 tick 退 1 片。{@code 2.0} ⇒ 每拍退 2 片。
 * 默认 1.0（用户口径），任何把默认值改成"一次退完"的改动都会让自检 I 组变红。
 */
public final class ShanhaiReplayPacer {

    /** 一次倒流因为什么停下。 */
    public enum Stop {
        /** 还没停。 */
        NONE("none"),
        /** 计划窗口退完了。 */
        BUDGET("budget"),
        /** 游标到达锚点（锚点那一 tick 本身不退）。 */
        ANCHOR("anchor"),
        CANCELLED("cancelled"),
        FAILED("failed");

        private final String code;

        Stop(String code) {
            this.code = code;
        }

        public String code() {
            return this.code;
        }
    }

    /**
     * 六条时间线（方块 / 实体 / 实体运动 / 进度 / BE 差分 / 标量）。
     *
     * <p>用 record 打包是为了让 {@link #step} 只有一个参数 —— 六条线必须<b>一起</b>推进，
     * 分开传就一定能写出"只倒方块不倒实体"的半截实现。
     */
    public record Journals(ShanhaiReplayJournal blocks,
                           ShanhaiReplayEntityJournal entities,
                           ShanhaiReplayProgressJournal progress,
                           ShanhaiReplayBlockEntityJournal blockEntities,
                           ShanhaiReplayScalarJournal scalars,
                           ShanhaiReplayEntityMotionJournal entityMotion,
                           ShanhaiReplayHealthJournal health) {

        /** 老的五参构造（自检里大量用到；运动层传 {@code null} = 这一层不参与）。 */
        public Journals(ShanhaiReplayJournal blocks, ShanhaiReplayEntityJournal entities,
                        ShanhaiReplayProgressJournal progress,
                        ShanhaiReplayBlockEntityJournal blockEntities,
                        ShanhaiReplayScalarJournal scalars) {
            this(blocks, entities, progress, blockEntities, scalars, null, null);
        }

        /** 六参构造（运动层参与、血量层不参与）。 */
        public Journals(ShanhaiReplayJournal blocks, ShanhaiReplayEntityJournal entities,
                        ShanhaiReplayProgressJournal progress,
                        ShanhaiReplayBlockEntityJournal blockEntities,
                        ShanhaiReplayScalarJournal scalars,
                        ShanhaiReplayEntityMotionJournal entityMotion) {
            this(blocks, entities, progress, blockEntities, scalars, entityMotion, null);
        }

        /** 七条线里最新的那个 tick（{@code -1} = 全空）；游标对齐用它。 */
        public int newestTick() {
            int newest = Math.max(Math.max(this.blocks.newestTick(), this.entities.newestTick()),
                    Math.max(Math.max(this.progress.newestTick(), this.blockEntities.newestTick()),
                            this.scalars.newestTick()));
            if (this.entityMotion != null) {
                newest = Math.max(newest, this.entityMotion.newestTick());
            }
            if (this.health != null) {
                newest = Math.max(newest, this.health.newestTick());
            }
            return newest;
        }
    }

    /** 七层的回退落点。 */
    public record Targets(ShanhaiReplayApplier.Sink blocks,
                          ShanhaiReplayEntityJournal.Sink entities,
                          ShanhaiReplayProgressJournal.Sink progress,
                          ShanhaiReplayBlockEntityJournal.Sink blockEntities,
                          ShanhaiReplayScalarJournal.Sink scalars,
                          ShanhaiReplayEntityMotionJournal.Sink entityMotion,
                          ShanhaiReplayHealthJournal.Sink health,
                          ShanhaiReplayMotionDispatch.Sender motionSender) {

        /** 老的五参构造（自检里用到；运动层 {@code null} = 这一层不参与）。 */
        public Targets(ShanhaiReplayApplier.Sink blocks, ShanhaiReplayEntityJournal.Sink entities,
                       ShanhaiReplayProgressJournal.Sink progress,
                       ShanhaiReplayBlockEntityJournal.Sink blockEntities,
                       ShanhaiReplayScalarJournal.Sink scalars) {
            this(blocks, entities, progress, blockEntities, scalars, null, null, null);
        }

        /** 六参构造（运动层参与、<b>不发包</b>：负对照用）。 */
        public Targets(ShanhaiReplayApplier.Sink blocks, ShanhaiReplayEntityJournal.Sink entities,
                       ShanhaiReplayProgressJournal.Sink progress,
                       ShanhaiReplayBlockEntityJournal.Sink blockEntities,
                       ShanhaiReplayScalarJournal.Sink scalars,
                       ShanhaiReplayEntityMotionJournal.Sink entityMotion) {
            this(blocks, entities, progress, blockEntities, scalars, entityMotion, null, null);
        }

        /** 七参构造（运动层 + 血量层，<b>不发包</b>）。 */
        public Targets(ShanhaiReplayApplier.Sink blocks, ShanhaiReplayEntityJournal.Sink entities,
                       ShanhaiReplayProgressJournal.Sink progress,
                       ShanhaiReplayBlockEntityJournal.Sink blockEntities,
                       ShanhaiReplayScalarJournal.Sink scalars,
                       ShanhaiReplayEntityMotionJournal.Sink entityMotion,
                       ShanhaiReplayHealthJournal.Sink health) {
            this(blocks, entities, progress, blockEntities, scalars, entityMotion, health, null);
        }
    }

    /** 一拍里各层各回退了多少条。 */
    public record Counts(int blocks, int entityEvents, int entityApplied, int progress, int blockEntities,
                         int scalars, int motion, int motionApplied, int motionSent, int motionPackets,
                         int health, int healthApplied, int motionAfterRebuild,
                         int rebuildPosOk, int rebuildPosMismatch,
                         int written, int failed, int skipped, EntityReason entityReason,
                         int blockWritten, int blockVerified, int blockAlreadyCorrect,
                         int blockWriteFailed, int blockMismatch) {

        public static Counts zero() {
            return new Counts(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    EntityReason.NONE, 0, 0, 0, 0, 0);
        }

        /** 这一拍真正回退的条目数（各层之和；运动层按"写回了几个位置"算）。 */
        public int changes() {
            return this.blocks + this.entityEvents + this.progress + this.blockEntities + this.scalars
                    + this.motion;
        }

        public String toText() {
            return "blocks=" + this.blocks + " entities=" + this.entityEvents
                    + " entity_applied=" + this.entityApplied + " entity_reason=" + this.entityReason.code()
                    + " progress=" + this.progress + " be=" + this.blockEntities + " scalars=" + this.scalars
                    + " motion=" + this.motion + " motion_applied=" + this.motionApplied
                    + " motion_sent=" + this.motionSent + " motion_packets=" + this.motionPackets
                    + " health=" + this.health + " health_applied=" + this.healthApplied
                    + " motion_after_rebuild=" + this.motionAfterRebuild
                    + " entity_rebuild_pos=" + (this.rebuildPosMismatch == 0 ? "ok" : "mismatch")
                    + ":" + this.rebuildPosOk + "/" + this.rebuildPosMismatch
                    + " changes=" + changes() + " written=" + this.written
                    + " failed=" + this.failed + " skipped=" + this.skipped
                    + " restored_now=" + this.blocks;
        }
    }

    /**
     * 这一拍实体层为什么是 0 / 为什么没应用。
     *
     * <p>🔴 真机实测的血账：日志里只有 {@code entities=0} 一个数字，
     * 于是「这一层被跳过」和「这一层没东西可退」分不开，只能靠猜。
     * 现在每一次倒流的每一行 step 里都带 {@code entity_reason=…}。
     */
    public enum EntityReason {

        /** 这一拍没有取实体层（不该出现；正常一拍一定有下面某个原因）。 */
        NONE("none"),
        /** 取了桶、真的删/建了实体。 */
        APPLIED("applied"),
        /** 取了桶，但一个都没删/建成功（找不到实体 / 区块没加载 / NBT 没了）。 */
        APPLY_FAILED("apply_failed"),
        /** 取了桶，全是"同 tick 出现又消失"的抵消项。 */
        CANCELLED("cancelled"),
        /** 实体层这一轮一条记录都没有。 */
        JOURNAL_EMPTY("journal_empty"),
        /** 这一层<b>最新</b>那个桶还在游标<b>之前</b>（等游标走过去才轮到它）。 */
        BUCKET_BEHIND("bucket_behind"),
        /** 这一层最新的桶在游标<b>之后</b>（游标还没退到它）。 */
        BUCKET_AHEAD("bucket_ahead");

        private final String code;

        EntityReason(String code) {
            this.code = code;
        }

        public String code() {
            return this.code;
        }
    }

    /**
     * 一次外层 tick 的读法（日志 {@code per_tick=…} 直接用它）。
     *
     * @param active    还在倒流
     * @param stop      这一拍之后该不该收尾
     * @param cursor    这一拍退掉的 tick（{@code -1} = 没退）
     * @param slices    这一拍退掉的时间片数（{@code speed=1} ⇒ ≤1）
     * @param exhausted 游标已经越界（调用方可以安全收尾）
     */
    public record Step(boolean active, Stop stop, int cursor, int slices, Counts counts, boolean exhausted) {

        public static Step inactive() {
            return new Step(false, Stop.NONE, -1, 0, Counts.zero(), true);
        }

        /** 一行日志（字段顺序稳定，便于 grep {@code per_tick=})。 */
        public String toText() {
            return "per_tick=" + this.slices + " steps=" + this.counts.changes()
                    + " cursor=" + this.cursor + " stop=" + this.stop.code() + " " + this.counts.toText();
        }
    }

    // ------------------------------------------------------------------ 状态

    private boolean active;
    private int window;
    private int remaining;
    private int cursor;
    private int startTick;
    private int doneTicks;
    private double speed = ShanhaiReplayConst.REPLAY_SPEED_DEFAULT;
    private int anchorTick = ShanhaiReplayAnchor.NO_ANCHOR_TICK;
    private long totalSlices;
    private long totalChanges;

    // ------------------------------------------------------------------ 起停

    /**
     * 开始节拍。
     *
     * @param plan      要退多少 tick（{@code > 0}）
     * @param startTick 游标起点（= 最新可回退的那个 tick）
     */
    public void begin(int plan, int startTick) {
        this.pending.clear();
        this.slicePrepared = false;
        this.stopAtBoundary = false;
        this.window = Math.max(0, plan);
        this.remaining = this.window;
        this.startTick = startTick;
        this.cursor = startTick;
        this.doneTicks = 0;
        this.totalSlices = 0L;
        this.totalChanges = 0L;
        this.anchorTick = ShanhaiReplayAnchor.NO_ANCHOR_TICK;
        this.speed = ShanhaiReplayConst.REPLAY_SPEED_DEFAULT;
        this.active = this.window > 0;
    }

    /** 收尾（清状态；{@link Step#stop()} 已给出原因，这里只负责"不再推进"）。 */
    public void reset() {
        this.active = false;
        this.pending.clear();
        this.slicePrepared = false;
        this.stopAtBoundary = false;
        this.window = 0;
        this.remaining = 0;
        this.cursor = 0;
        this.doneTicks = 0;
    }

    /** 锚点语义：退到 {@code tick} 就停（该 tick 本身不退）。 */
    public void atAnchor(int tick) {
        this.anchorTick = tick;
    }

    /** 加速倍率（{@code 1.0} = 等速；超出区间会被钳住，绝不退到"瞬移"）。 */
    public void setSpeed(double requested) {
        double s = requested;
        if (Double.isNaN(s) || Double.isInfinite(s)) {
            s = ShanhaiReplayConst.REPLAY_SPEED_DEFAULT;
        }
        this.speed = Math.max(ShanhaiReplayConst.REPLAY_SPEED_DEFAULT,
                Math.min(ShanhaiReplayConst.REPLAY_SPEED_MAX, s));
    }

    public boolean isActive() {
        return this.active;
    }

    public int window() {
        return this.window;
    }

    public int remaining() {
        return this.remaining;
    }

    public int cursor() {
        return this.cursor;
    }

    public int startTick() {
        return this.startTick;
    }

    public int doneTicks() {
        return this.doneTicks;
    }

    public double speed() {
        return this.speed;
    }

    public int slicesPerTick() {
        return Math.max(1, (int) Math.round(this.speed));
    }

    public long totalSlices() {
        return this.totalSlices;
    }

    /**
     * 游标防御：万一还有比游标更新的记录（同一个外层 tick 里的采集抖动），把游标对齐到最新。
     *
     * <p>正常路径不会触发（REPLAY 期间所有记录器都停），但"记录器停之前的那一拍"可能出现
     * {@code newest > cursor}；不对齐就会永远差着那几条。
     *
     * <p>🔴 2026-11 真机实测的 bug：对齐<b>只抬游标、不加预算</b>时，整个窗口会整体上移 N tick
     * （实测 N=28：锚点倒流的 {@code window=246} 是按"方块日志最新 tick 43375"算的，
     * 而游标被抬到五层最新的 43403 之后仍只走 246 拍 ⇒ 停在 43157，
     * 距锚点 43129 还差 28 tick，却因为走 {@code BUDGET} 收尾路径打了 {@code anchor_restored=1}）。
     * ⇒ 所以本方法返回<b>抬了多少 tick</b>，调用方必须把同样的数补进 {@code remaining}。
     *
     * @param newestTick 五层里最新的那个 tick
     * @return 抬高的 tick 数（{@code 0} = 没动）
     */
    public int alignCursor(int newestTick) {
        if (!this.active || newestTick <= this.cursor) {
            return 0;
        }
        final int delta = newestTick - this.cursor;
        this.cursor = newestTick;
        return delta;
    }

    /**
     * 补预算（{@link #alignCursor} 抬高游标时调用方要调它，否则窗口整体上移）。
     *
     * <p>只加不减，且只影响"还剩几拍"和"总窗口多少"两个读数 —— 不改游标。
     */
    public void extendPlan(int ticks) {
        if (ticks <= 0) {
            return;
        }
        this.remaining += ticks;
        this.window += ticks;
    }

    public long totalChanges() {
        return this.totalChanges;
    }

    // ------------------------------------------------------------------ 推进

    /**
     * 推进一个外层 tick。
     *
     * <p>🔴 只退 {@link #slicesPerTick()} 片（默认 1 片）：这是"逐步"与"瞬移"的唯一分界。
     *
     * @param journals 五条时间线
     * @param targets  五层落点
     */
    /** Work from one recorded tick remains here until all its layers have been applied. */
    private final java.util.ArrayDeque<BudgetWork> pending = new java.util.ArrayDeque<>();
    private boolean slicePrepared;
    private boolean stopAtBoundary;
    private record BudgetWork(int cost, java.util.function.Consumer<BudgetCounts> apply) {}

    private static final class BudgetCounts {
        int blocks, entities, entityApplied, progress, be, scalars, motion, motionApplied, motionSent,
                motionPackets, health, healthApplied, motionAfterRebuild, rebuildPosOk, rebuildPosMismatch,
                written, failed, skipped, blockWritten, blockVerified, blockAlreadyCorrect, blockWriteFailed, blockMismatch;
        EntityReason entityReason = EntityReason.NONE;
        Counts result() {
            return new Counts(blocks, entities, entityApplied, progress, be, scalars, motion, motionApplied,
                    motionSent, motionPackets, health, healthApplied, motionAfterRebuild, rebuildPosOk,
                    rebuildPosMismatch, written, failed, skipped, entityReason, blockWritten, blockVerified,
                    blockAlreadyCorrect, blockWriteFailed, blockMismatch);
        }
    }

    /** True only while a recorded tick has been partially applied. */
    public boolean hasPendingSlice() { return slicePrepared; }

    /** Defer cancellation until all work in the current slice is complete. */
    public boolean requestStop() {
        if (!slicePrepared) return false;
        stopAtBoundary = true;
        return true;
    }

    private void prepareSlice(Journals journals, Targets targets, int tick) {
        var removed = new java.util.HashSet<Long>();
        var rebuilt = new java.util.HashSet<Long>();
        if (journals.blocks().newestTick() == tick) {
            for (var entry : journals.blocks().takeNewestTick()) {
                pending.add(new BudgetWork(2, counts -> {
                    var r = ShanhaiReplayApplier.apply(List.of(entry), targets.blocks());
                    counts.blocks++; counts.written += r.written(); counts.failed += r.failed() + r.skipped();
                    counts.skipped += r.skipped(); counts.blockWritten += r.written();
                    counts.blockVerified += r.verified(); counts.blockAlreadyCorrect += r.alreadyCorrect();
                    counts.blockWriteFailed += r.failed(); counts.blockMismatch += r.mismatch();
                }));
            }
        }
        if (journals.blockEntities().newestTick() == tick) {
            for (var entry : journals.blockEntities().takeNewestTick()) {
                pending.add(new BudgetWork(1, counts -> {
                    var r = ShanhaiReplayBlockEntityJournal.apply(List.of(entry), targets.blockEntities());
                    counts.be++; counts.written += r.written(); counts.failed += r.failed();
                }));
            }
        }
        if (journals.progress().newestTick() == tick) {
            for (var entry : journals.progress().takeNewestTick()) {
                pending.add(new BudgetWork(1, counts -> {
                    var r = ShanhaiReplayProgressJournal.apply(List.of(entry), targets.progress());
                    counts.progress++; counts.written += r.written(); counts.failed += r.failed() + r.unsupported();
                }));
            }
        }
        if (journals.entities().newestTick() == tick) {
            for (var entry : journals.entities().takeNewestTick()) {
                long key = ShanhaiReplayEntityMotionJournal.keyOf(entry.uuidMost(), entry.uuidLeast());
                if (entry.kind() == ShanhaiReplayEntityJournal.Kind.APPEAR) removed.add(key);
                if (entry.kind() == ShanhaiReplayEntityJournal.Kind.DISAPPEAR) rebuilt.add(key);
                pending.add(new BudgetWork(1, counts -> {
                    var r = ShanhaiReplayEntityJournal.apply(List.of(entry), targets.entities());
                    counts.entities++; counts.entityApplied += r.total(); counts.written += r.total();
                    counts.failed += r.failed(); counts.skipped += r.skipped();
                    counts.entityReason = r.total() > 0 ? EntityReason.APPLIED
                            : r.failed() > 0 ? EntityReason.APPLY_FAILED : EntityReason.CANCELLED;
                }));
            }
        }
        if (journals.entityMotion() != null && targets.entityMotion() != null
                && journals.entityMotion().newestTick() == tick) {
            for (var entry : journals.entityMotion().takeNewestTick()) {
                long key = ShanhaiReplayEntityMotionJournal.keyOf(entry.uuidMost(), entry.uuidLeast());
                if (removed.contains(key)) continue;
                pending.add(new BudgetWork(1, counts -> {
                    var r = ShanhaiReplayMotionDispatch.apply(List.of(entry), targets.entityMotion(), targets.motionSender());
                    counts.motion++; counts.motionApplied += r.moved(); counts.motionSent += r.sentMoves();
                    counts.motionPackets += r.sentPackets(); counts.written += r.moved(); counts.failed += r.failed();
                    if (rebuilt.contains(key)) {
                        counts.motionAfterRebuild++;
                        float[] pos = targets.entityMotion().appliedPos(entry.dim(), entry.uuidMost(), entry.uuidLeast());
                        if (pos != null) {
                            if (Math.abs(pos[0] - entry.x()) <= 0.05F && Math.abs(pos[1] - entry.y()) <= 0.05F
                                    && Math.abs(pos[2] - entry.z()) <= 0.05F) counts.rebuildPosOk++;
                            else counts.rebuildPosMismatch++;
                        }
                    }
                }));
            }
        }
        if (journals.health() != null && targets.health() != null && journals.health().newestTick() == tick) {
            for (var entry : journals.health().takeNewestTick()) {
                long key = ShanhaiReplayHealthJournal.keyOf(entry.uuidMost(), entry.uuidLeast());
                if (removed.contains(key)) continue;
                pending.add(new BudgetWork(1, counts -> {
                    var r = ShanhaiReplayHealthJournal.apply(List.of(entry), targets.health());
                    counts.health++; counts.healthApplied += r.applied(); counts.written += r.applied(); counts.failed += r.failed();
                }));
            }
        }
        if (journals.scalars().newestTick() == tick) {
            for (var entry : journals.scalars().takeNewestTick()) {
                pending.add(new BudgetWork(1, counts -> {
                    var r = ShanhaiReplayScalarJournal.apply(List.of(entry), targets.scalars());
                    counts.scalars++; counts.written += r.written(); counts.failed += r.failed() + r.unsupported();
                    counts.skipped += r.skipped();
                }));
            }
        }
        slicePrepared = true;
    }

    /** Apply at most MAX_WRITES_PER_STEP writes, advancing the cursor only after all layers complete. */
    public Step step(Journals journals, Targets targets) {
        if (!active) return Step.inactive();
        BudgetCounts counts = new BudgetCounts();
        int budget = ShanhaiReplayConst.MAX_WRITES_PER_STEP;
        int slices = 0;
        int maxSlices = slicesPerTick();
        while (remaining > 0 && slices < maxSlices) {
            if (!slicePrepared) prepareSlice(journals, targets, cursor);
            while (!pending.isEmpty() && pending.peek().cost() <= budget) {
                BudgetWork work = pending.remove();
                budget -= work.cost();
                try { work.apply().accept(counts); }
                catch (Throwable failure) { counts.failed++; }
                if (counts.failed > 0) break;
            }
            if (counts.failed > 0 || !pending.isEmpty()) break;
            slicePrepared = false;
            --cursor; --remaining; ++doneTicks; ++totalSlices; ++slices;
            if (stopAtBoundary || budget <= 0) break;
        }
        Counts result = counts.result();
        totalChanges += result.changes() + result.health();
        Stop stop = Stop.NONE;
        if (counts.failed > 0) stop = Stop.FAILED;
        else if (!slicePrepared && stopAtBoundary) { stop = Stop.CANCELLED; stopAtBoundary = false; }
        else if (!slicePrepared && anchorTick != ShanhaiReplayAnchor.NO_ANCHOR_TICK
                && !ShanhaiReplayAnchor.needsAnotherStep(cursor, anchorTick)) stop = Stop.ANCHOR;
        else if (!slicePrepared && remaining <= 0) stop = Stop.BUDGET;
        if (stop != Stop.NONE) active = false;
        return new Step(true, stop, slicePrepared ? cursor : cursor + 1, slices, result, stop != Stop.NONE);
    }

    /** 一行摘要（status / 日志用）。 */
    public String summary() {
        return "active=" + (this.active ? 1 : 0) + " window=" + this.window + " left=" + this.remaining
                + " cursor=" + this.cursor + " done=" + this.doneTicks + " speed=" + this.speed
                + " slices=" + this.totalSlices + " changes=" + this.totalChanges;
    }
}