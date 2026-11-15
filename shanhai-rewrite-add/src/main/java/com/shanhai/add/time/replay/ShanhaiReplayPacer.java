package com.shanhai.add.time.replay;

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
        ANCHOR("anchor");

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
    public Step step(Journals journals, Targets targets) {
        if (!this.active) {
            return Step.inactive();
        }
        int slices = 0;
        int blocks = 0;
        int entities = 0;
        int entityApplied = 0;
        int progress = 0;
        int beDiffs = 0;
        int scalars = 0;
        int motion = 0;
        int motionApplied = 0;
        int motionSent = 0;
        int motionPackets = 0;
        int health = 0;
        int healthApplied = 0;
        int blockWritten = 0;
        int blockVerified = 0;
        int blockAlreadyCorrect = 0;
        int blockWriteFailed = 0;
        int blockMismatch = 0;
        int written = 0;
        int failed = 0;
        int skipped = 0;
        int motionAfterRebuild = 0;
        int rebuildPosOk = 0;
        int rebuildPosMismatch = 0;
        int emittedAhead = 0;
        // 本轮倒流里被重建过的实体（判据 A1：之后写给它们的 motion 属于"写给了重建后的实例"）
        final java.util.Set<Long> rebuiltSinceStart = new java.util.HashSet<>();
        EntityReason entityReason = EntityReason.NONE;
        final int budget = slicesPerTick();

        for (int i = 0; i < budget && this.remaining > 0; i++) {
            final int tick = this.cursor;

            // ---- ① 方块（两段式写回，见 ShanhaiReplayApplier）
            final List<ShanhaiReplayJournal.Change> blockBatch =
                    journals.blocks().newestTick() == tick
                            ? journals.blocks().takeNewestTick() : List.of();
            if (!blockBatch.isEmpty()) {
final ShanhaiReplayApplier.Result r = ShanhaiReplayApplier.apply(blockBatch, targets.blocks());
                written += r.written();
                failed += r.failed();
                skipped += r.skipped();
                blocks += blockBatch.size();
                blockWritten += r.written();
                blockVerified += r.verified();
                blockAlreadyCorrect += r.alreadyCorrect();
                blockWriteFailed += r.failed();
                blockMismatch += r.mismatch();
            }

            // ---- ② 方块实体最小差
            final List<ShanhaiReplayBlockEntityJournal.BeDiff> beBatch =
                    journals.blockEntities().newestTick() == tick
                            ? journals.blockEntities().takeNewestTick() : List.of();
            if (!beBatch.isEmpty()) {
                final ShanhaiReplayBlockEntityJournal.Result r =
                        ShanhaiReplayBlockEntityJournal.apply(beBatch, targets.blockEntities());
                written += r.written();
                failed += r.failed();
                beDiffs += beBatch.size();
            }

            // ---- ③ 机器进度（显式写回，覆盖 BE 差分里的同一字段）
            final List<ShanhaiReplayProgressJournal.Progress> progressBatch =
                    journals.progress().newestTick() == tick
                            ? journals.progress().takeNewestTick() : List.of();
            if (!progressBatch.isEmpty()) {
                final ShanhaiReplayProgressJournal.Result r =
                        ShanhaiReplayProgressJournal.apply(progressBatch, targets.progress());
                written += r.written();
                failed += r.failed();
                progress += progressBatch.size();
            }

            // ---- ④ 实体（移除后出现的 / 重建后消失的）
            final int entityNewest = journals.entities().newestTick();
            final List<ShanhaiReplayEntityJournal.Event> entityBatch =
                    entityNewest == tick ? journals.entities().takeNewestTick() : List.of();
            final java.util.Set<Long> justRemoved = new java.util.HashSet<>();
            if (!entityBatch.isEmpty()) {
                final ShanhaiReplayEntityJournal.Result r =
                        ShanhaiReplayEntityJournal.apply(entityBatch, targets.entities());
                written += r.total();
                failed += r.failed();
                skipped += r.skipped();
                entities += entityBatch.size();
                // 🔴 记的是"真删/真建了几只"，不是"桶里几条"：两个数必须能分开读
                entityApplied += r.removed() + r.rebuilt();
                entityReason = r.removed() + r.rebuilt() > 0
                        ? EntityReason.APPLIED
                        : (r.failed() > 0 ? EntityReason.APPLY_FAILED : EntityReason.CANCELLED);
                // 本 tick 被删掉的实体：不许再写位置（它已经不存在了）
                for (ShanhaiReplayEntityJournal.Event e : entityBatch) {
                    if (e.kind() == ShanhaiReplayEntityJournal.Kind.APPEAR) {
                        justRemoved.add(Long.valueOf(
                                ShanhaiReplayEntityMotionJournal.keyOf(e.uuidMost(), e.uuidLeast())));
                    } else if (e.kind() == ShanhaiReplayEntityJournal.Kind.DISAPPEAR) {
                        // 🔴 判据 A3：被<b>重建</b>过的实体（它在这一步才"回到这个世界"）。
                        //    motion 层必须排在它<b>之后</b>（顺序见本方法 ④→⑤），否则位置会被 NBT 覆盖回去
                        //    ⇒ 用户看到的"倒流到错误的位置、最后一下被拉回"。
                        rebuiltSinceStart.add(Long.valueOf(
                                ShanhaiReplayEntityMotionJournal.keyOf(e.uuidMost(), e.uuidLeast())));
                    }
                }
            } else if (entityNewest < 0) {
                entityReason = EntityReason.JOURNAL_EMPTY;
            } else if (entityNewest > tick) {
                entityReason = EntityReason.BUCKET_AHEAD;
            } else {
                entityReason = EntityReason.BUCKET_BEHIND;
            }

            // ---- ⑤ 实体运动（把活着的实体沿原路拉回去："猪倒着走"）
            if (journals.entityMotion() != null && targets.entityMotion() != null) {
                final int motionNewest = journals.entityMotion().newestTick();
                if (motionNewest == tick) {
                    List<ShanhaiReplayEntityMotionJournal.Motion> motionBatch =
                            journals.entityMotion().takeNewestTick();
                    if (!justRemoved.isEmpty()) {
                        final List<ShanhaiReplayEntityMotionJournal.Motion> kept =
                                new ArrayList<>(motionBatch.size());
                        for (ShanhaiReplayEntityMotionJournal.Motion m : motionBatch) {
                            if (!justRemoved.contains(Long.valueOf(
                                    ShanhaiReplayEntityMotionJournal.keyOf(m.uuidMost(), m.uuidLeast())))) {
                                kept.add(m);
                            }
                        }
                        motionBatch = kept;
                    }
                    if (!motionBatch.isEmpty()) {
                        // 🔴 写回位置与发包<b>成对</b>：每条成功的 move 必须产生一次发包请求
                        //    （旧实现两件事写在同一个 MC 方法里 ⇒ "写了没发"在日志上分不开）
                        final ShanhaiReplayMotionDispatch.Result r = ShanhaiReplayMotionDispatch.apply(
                                motionBatch, targets.entityMotion(), targets.motionSender());
                        written += r.moved();
                        failed += r.failed();
                        motion += motionBatch.size();
                        motionApplied += r.moved();
                        motionSent += r.sentMoves();
                        motionPackets += r.sentPackets();
                        // 🔴 判据 A3 的后半：写完之后再读一次，位置必须真的等于记录值
                        //    （不等 ⇒ "重建把位置覆盖回旧值"/被别的路径拉回，用户看到的"最后一下被拉回"）
                        for (ShanhaiReplayEntityMotionJournal.Motion m : motionBatch) {
                            final long k = ShanhaiReplayEntityMotionJournal.keyOf(m.uuidMost(), m.uuidLeast());
                            if (!rebuiltSinceStart.contains(Long.valueOf(k))) {
                                continue;
                            }
                            ++motionAfterRebuild;
                            final float[] pos = targets.entityMotion().appliedPos(
                                    m.dim(), m.uuidMost(), m.uuidLeast());
                            if (pos == null) {
                                continue;   // 查不到就不判（绝不能当成 ok）
                            }
                            if (Math.abs(pos[0] - m.x()) <= 0.05F && Math.abs(pos[1] - m.y()) <= 0.05F
                                    && Math.abs(pos[2] - m.z()) <= 0.05F) {
                                ++rebuildPosOk;
                            } else {
                                ++rebuildPosMismatch;
                            }
                        }
                    }
                }
            }

            // ---- ⑤b 生物血量（与位置同一拍、紧挨着；顺序 = 先位置后血量）
            if (journals.health() != null && targets.health() != null) {
                final int healthNewest = journals.health().newestTick();
                if (healthNewest == tick) {
                    List<ShanhaiReplayHealthJournal.Health> healthBatch =
                            journals.health().takeNewestTick();
                    if (!justRemoved.isEmpty()) {
                        final List<ShanhaiReplayHealthJournal.Health> kept =
                                new ArrayList<>(healthBatch.size());
                        for (ShanhaiReplayHealthJournal.Health h : healthBatch) {
                            if (!justRemoved.contains(Long.valueOf(
                                    ShanhaiReplayHealthJournal.keyOf(h.uuidMost(), h.uuidLeast())))) {
                                kept.add(h);
                            }
                        }
                        healthBatch = kept;
                    }
                    if (!healthBatch.isEmpty()) {
                        final ShanhaiReplayHealthJournal.Result r =
                                ShanhaiReplayHealthJournal.apply(healthBatch, targets.health());
                        written += r.applied();
                        failed += r.failed();
                        health += healthBatch.size();
                        healthApplied += r.applied();
                    }
                }
            }

            // ---- ⑤ 世界标量（时间 / 天气 / 雷暴）
            final List<ShanhaiReplayScalarJournal.Scalar> scalarBatch =
                    journals.scalars().newestTick() == tick
                            ? journals.scalars().takeNewestTick() : List.of();
            if (!scalarBatch.isEmpty()) {
                final ShanhaiReplayScalarJournal.Result r =
                        ShanhaiReplayScalarJournal.apply(scalarBatch, targets.scalars());
                written += r.written();
                failed += r.failed();
                scalars += scalarBatch.size();
            }

            --this.cursor;
            --this.remaining;
            ++this.doneTicks;
            ++this.totalSlices;
            ++slices;
        }

        final Counts counts = new Counts(blocks, entities, entityApplied, progress, beDiffs, scalars,
                motion, motionApplied, motionSent, motionPackets, health, healthApplied,
                motionAfterRebuild, rebuildPosOk, rebuildPosMismatch,
                written, failed, skipped, entityReason,
                blockWritten, blockVerified, blockAlreadyCorrect, blockWriteFailed, blockMismatch);
        this.totalChanges += counts.changes();

        Stop stop = Stop.NONE;
        if (this.anchorTick != ShanhaiReplayAnchor.NO_ANCHOR_TICK
                && !ShanhaiReplayAnchor.needsAnotherStep(this.cursor, this.anchorTick)) {
            stop = Stop.ANCHOR;
        } else if (this.remaining <= 0) {
            stop = Stop.BUDGET;
        }
        if (stop != Stop.NONE) {
            this.active = false;
        }
        return new Step(true, stop, this.cursor + 1, slices, counts, stop != Stop.NONE);
    }

    /** 一行摘要（status / 日志用）。 */
    public String summary() {
        return "active=" + (this.active ? 1 : 0) + " window=" + this.window + " left=" + this.remaining
                + " cursor=" + this.cursor + " done=" + this.doneTicks + " speed=" + this.speed
                + " slices=" + this.totalSlices + " changes=" + this.totalChanges;
    }
}