package com.shanhai.add.time.replay;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.shanhai.ShanhaiMod;
import com.shanhai.add.time.ShanhaiClockHooks;
import com.shanhai.add.time.ShanhaiWorldClock;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 山海重构 · <b>方块实体的"被标记变化就差分"记录器</b>（第 1 层，逐 tick 连续）。
 *
 * <h2>1. 用户口径（这一层为什么长这样）</h2>
 * <blockquote>「别周期兜底，那样看上去就是世界非连续的了」</blockquote>
 * ⇒ <b>不</b>做"每 N tick 对改动区块整块快照"。改成：
 * <pre>
 *   BlockEntity#setChanged() HEAD   →  只往"本 tick 标记集"里放一个 (dim,pos)   ← 不做序列化
 *   每个外层 tick 末                 →  只对标记集里的 BE 读一次 NBT，与 baseline 比较
 *                                    →  只把"变了的顶层 key 的 before 值"记成一条差分
 * </pre>
 * 这样：① 每 tick 连续、无跳变；② 只记真变了的东西；③ 第三方 mod 的 BE 只要调 {@code setChanged} 就覆盖到。
 *
 * <h2>2. 🔴 已知漏点（写进覆盖矩阵 {@code be.silent}，不许用周期快照去补）</h2>
 * <b>不调 {@code setChanged()} 的方块实体完全不会被记</b>。GT 的 {@code RecipeLogic#setProgress(int)}
 * javap 实证是 {@code putfield progress; return}（不 markDirty）⇒ 机器进度<b>不</b>走这一层，
 * 走 {@link ShanhaiReplayProgressRecorder} 的瘦日志。
 *
 * <h2>3. 为什么是"最小差"（顶层 key 粒度）</h2>
 * 整份 NBT 每条约 1 KB；一台大基地 200 台机器 × 1200 tick ≈ 240 MB（见 {@link ShanhaiReplayBudget}）。
 * 最小差只存"这一 tick 变了的顶层 key 的 before 值"（几十字节）。
 * 回退时用每 pos 的<b>工作 NBT</b>（首次触碰时从活着的 BE 读一次）把差反向盖回去，再
 * {@code BlockEntity#load(整份)} —— 既省内存，又不会拿半份 NBT 去覆盖第三方 BE 的未知字段
 * （半份 NBT 会让缺省的 key 被读成默认值）。
 *
 * <h2>4. 失败方向</h2>
 * 全程 try/catch 自吞：记录器坏了绝不能让 {@code setChanged} 抛出去（那会让容器/机器的正常流程中断）。
 */
public final class ShanhaiReplayBlockEntityRecorder {

    /** 🔴 已知漏点计数：从没见过 baseline 的第一次变化（不知道 before，记不了）。 */
    private static long baselineMisses;
    /** 打标记的次数 / 真的产生了差分的次数。 */
    private static long markCount;
    private static long capturedCount;
    /** 因为 baseline 表满了而丢弃监控的 BE 数。 */
    private static long baselineEvicted;

    /** 五层自证行里这一层的判据（注入方法名；{@code ShanhaiReplayService#armedLayers} 反射查它）。 */
    public static final String LAYER_ID = "be";
    public static final String MIXIN_METHOD = "shanhai$markForReplay";

    /** {@code onSetChanged} 被调到几次（mixin 到底有没有挂上）+ 各过滤原因计数。 */
    private static long hookCalls;
    private static long rejectedNotLive;
    private static long rejectedReplayWrite;
    private static long rejectedNoLevel;
    private static long lastMarkLogMs;

    /** 本 tick 被 {@code setChanged} 标记过的方块实体（pos → 标记）。 */
    private static final Map<Long, Mark> MARKED = new HashMap<>();
    /** 每个 pos 上一次捕获时的 NBT（<b>只一份</b>，不随窗口增长）。 */
    private static final Map<Long, CompoundTag> BASELINE = new HashMap<>();
    /** 回退时每个 pos 的工作 NBT（倒流开始/结束时清空）。 */
    private static final Map<Long, CompoundTag> WORKING = new HashMap<>();
    /** 差分载荷池：{@code restore}（要写回的 key→值）+ {@code delete}（这一 tick 新加出来的 key，回退要删掉）。 */
    private static final List<Diff> DIFFS = new ArrayList<>();

    private static final ShanhaiReplayBlockEntityJournal JOURNAL =
            new ShanhaiReplayBlockEntityJournal(ShanhaiReplayConst.MAX_BLOCK_ENTITY_RECORDS);

    /** baseline 表上限（每份 ≈ 1 KB ⇒ ≈ 2 MB 常驻，见预算类）。 */
    private static final int MAX_BASELINE = 2_048;

    private static long lastWarnMs;

    /**
     * 一条最小差。
     *
     * @param restore 要写回的 key → 变化前的值（含"这一 tick 被删掉的 key"）
     * @param added   这一 tick 新加出来的 key（回退时要从工作 NBT 里删掉）
     */
    private record Diff(Map<String, Tag> restore, List<String> added) {
    }

    /**
     * 一个"被标记"的方块实体。
     *
     * @param dim 维度小整数
     * @param ref 弱引用（区块卸载后自动失效，不会拖住世界内存）
     */
    private record Mark(int dim, java.lang.ref.WeakReference<BlockEntity> ref) {
    }

    public static ShanhaiReplayBlockEntityJournal journal() {
        return JOURNAL;
    }

    public static long baselineMisses() {
        return baselineMisses;
    }

    public static long capturedCount() {
        return capturedCount;
    }

    public static int baselineSize() {
        return BASELINE.size();
    }

    public static int diffPoolSize() {
        return DIFFS.size();
    }

    /** 换世界/收尾：全部清空。 */
    public static void reset() {
        MARKED.clear();
        BASELINE.clear();
        WORKING.clear();
        DIFFS.clear();
        JOURNAL.clear();
        baselineMisses = 0L;
        markCount = 0L;
        capturedCount = 0L;
        baselineEvicted = 0L;
        hookCalls = 0L;
        rejectedNotLive = 0L;
        rejectedReplayWrite = 0L;
        rejectedNoLevel = 0L;
        lastMarkLogMs = 0L;
    }

    /** 倒流收尾：工作 NBT 必须扔掉（下次倒流要重新从活着的 BE 读）。 */
    public static void clearWorking() {
        WORKING.clear();
    }

    // ------------------------------------------------------------------ 打标记

    /**
     * {@code BlockEntity#setChanged()} HEAD 的转发入口（混入类体内零 MC 成员引用）。
     *
     * <p>热路径，只做"放进标记集"这一件事：不序列化、不比对、不打日志。
     */
    public static void onSetChanged(Object beObj) {
        ++hookCalls;
        try {
            if (!(beObj instanceof BlockEntity be)) {
                return;
            }
            final Level level = be.getLevel();
            if (level == null || level.isClientSide()) {
                ++rejectedNoLevel;
                return;
            }
            if (!ShanhaiReplayModeCompose.recordsUnder(ShanhaiWorldClock.SERVER.getMode())) {
                ++rejectedNotLive;
                return;
            }
            if (ShanhaiClockHooks.inReplayWrite()) {
                ++rejectedReplayWrite;
                return;
            }
            final BlockPos pos = be.getBlockPos();
            if (pos == null) {
                return;
            }
            final int dim = ShanhaiReplayEngine.dimIdOf(level.dimension());
            if (dim < 0) {
                return;
            }
            final long key = pos.asLong();
            if (MARKED.size() < ShanhaiReplayConst.MAX_PER_TICK * 4) {
                MARKED.put(key, new Mark(dim, new java.lang.ref.WeakReference<>(be)));
            }
            ++markCount;
        } catch (Throwable t) {
            warnThrottled("setChanged 打标记失败（这一拍的这个方块实体不会被记）", t);
        }
    }

    /**
     * 每个外层 tick 末：把标记集里的 BE 逐个差分。
     *
     * <p>⚠️ 记录的时间戳取<b>该维度</b>的 {@code gameTime}，与方块记录器同源 —— 两个层若用不同的时间源，
     * 回退时会出现"方块对上了、BE 差一格"。
     */
    public static void flush(MinecraftServer server) {
        if (MARKED.isEmpty() || server == null) {
            return;
        }
        if (!ShanhaiReplayModeCompose.recordsUnder(ShanhaiWorldClock.SERVER.getMode())) {
            MARKED.clear();
            return;
        }
        markLogThrottled();
        final List<Map.Entry<Long, Mark>> batch = new ArrayList<>(MARKED.entrySet());
        MARKED.clear();
        for (Map.Entry<Long, Mark> entry : batch) {
            try {
                captureOne(server, entry.getKey(), entry.getValue());
            } catch (Throwable t) {
                warnThrottled("方块实体差分失败（这一拍的这个 BE 不会被记）", t);
            }
        }
    }

    private static void captureOne(MinecraftServer server, long key, Mark mark) {
        final ServerLevel level = levelOf(server, mark.dim());
        if (level == null) {
            return;
        }
        final BlockEntity be = mark.ref() == null ? null : mark.ref().get();
        if (be == null || be.isRemoved()) {
            // BE 已经不在了：它这一拍的"消失"由方块层的状态变化负责，这里不记
            return;
        }
        final CompoundTag now = be.saveWithoutMetadata();
        if (now == null) {
            return;
        }
        final CompoundTag base = BASELINE.get(key);
        if (base == null) {
            if (BASELINE.size() >= MAX_BASELINE) {
                ++baselineEvicted;
                return;
            }
            BASELINE.put(key, now.copy());
            ++baselineMisses;   // 第一次见：不知道 before，这一拍的变化记不了
            return;
        }
        final Diff diff = diffOf(base, now);
        if (diff.restore().isEmpty() && diff.added().isEmpty()) {
            return;   // setChanged 调了但 NBT 没变（很常见：GT/LDLib 的 markDirty 风暴）
        }
        final int slot = ShanhaiReplayBlockEntityRecorder.putDiff(diff);
        final long tick = level.getGameTime();
        JOURNAL.record((int) tick, mark.dim(), key, slot,
                diff.restore().size() + diff.added().size());
        BASELINE.put(key, now.copy());
        ++capturedCount;
    }

    private static Diff diffOf(CompoundTag base, CompoundTag now) {
        final Map<String, Tag> restore = new java.util.LinkedHashMap<>();
        final List<String> added = new ArrayList<>();
        for (String k : base.getAllKeys()) {
            final Tag oldTag = base.get(k);
            final Tag newTag = now.get(k);
            if (oldTag == null) {
                continue;
            }
            if (newTag == null || !newTag.equals(oldTag)) {
                restore.put(k, oldTag.copy());
            }
        }
        for (String k : now.getAllKeys()) {
            if (!base.contains(k)) {
                added.add(k);
            }
        }
        return new Diff(restore, added);
    }

    private static int putDiff(Diff diff) {
        final int slot = DIFFS.size();
        DIFFS.add(diff);
        return slot;
    }

    private static Diff getDiff(int slot) {
        if (slot < 0 || slot >= DIFFS.size()) {
            return null;
        }
        return DIFFS.get(slot);
    }

    private static ServerLevel levelOf(MinecraftServer server, int dim) {
        if (server == null) {
            return null;
        }
        final net.minecraft.resources.ResourceKey<Level> key = ShanhaiReplayEngine.dimKeyOf(dim);
        return key == null ? null : server.getLevel(key);
    }

    // ------------------------------------------------------------------ 回退

    /**
     * 把一条差分反向盖回该 pos 的方块实体。
     *
     * <p>🔴 必须是"整份工作 NBT + {@code load}"，不能只 {@code load} 一份半截 NBT：
     * 那样缺省的 key 会被各 BE 的 {@code loadAdditional} 读成默认值 ⇒ 把库存/能量清空。
     */
    public static boolean applyDiff(ServerLevel level, long pos, int payload) {
        final Diff diff = getDiff(payload);
        if (diff == null) {
            return false;
        }
        final BlockPos blockPos = BlockPos.of(pos);
        final BlockEntity be = level.getBlockEntity(blockPos);
        if (be == null) {
            return false;
        }
        CompoundTag working = WORKING.get(pos);
        if (working == null) {
            working = be.saveWithoutMetadata();
            if (working == null) {
                return false;
            }
            WORKING.put(pos, working);
        }
        for (Map.Entry<String, Tag> e : diff.restore().entrySet()) {
            working.put(e.getKey(), e.getValue().copy());
        }
        for (String k : diff.added()) {
            working.remove(k);
        }
        ShanhaiClockHooks.beginReplayWrite();
        try {
            be.load(working);
            be.setChanged();
            return true;
        } catch (Throwable t) {
            warnThrottled("回退方块实体差分失败 pos=" + blockPos, t);
            return false;
        } finally {
            ShanhaiClockHooks.endReplayWrite();
        }
    }

    /** 一行摘要（status / done 行）。 */
    public static String summary() {
        return "marks=" + markCount + " diffs=" + capturedCount + " baseline=" + BASELINE.size()
                + " baseline_misses=" + baselineMisses + " baseline_evicted=" + baselineEvicted
                + " pool=" + DIFFS.size() + " " + probeLine() + " journal[" + JOURNAL.summary() + "]";
    }

    /**
     * 把"这个空通道为什么空"摊开（真机 {@code be=records=0} 的硬判据）。
     *
     * <p>判读：
     * <ul>
     *   <li>{@code hook_calls=0} ⇒ 注入点没被调到 ⇒ mixin 没挂上（看 {@code hooks_armed be=… ok=false}）；</li>
     *   <li>{@code hook_calls>0 && marks=0} ⇒ 全被过滤（{@code not_live}/{@code replay_write}）；</li>
     *   <li>{@code marks>0 && diffs=0 && baseline_misses>=marks} ⇒ 每个 BE 都是第一次见
     *       （只建基线，还没出现第二次变化 ⇒ 真的什么都没有可差分的）；</li>
     *   <li>{@code diffs>0} 而回放 {@code be=0} ⇒ 问题在取用侧，不在记录侧。</li>
     * </ul>
     */
    public static String probeLine() {
        return "hook_calls=" + hookCalls + " not_live=" + rejectedNotLive
                + " replay_write=" + rejectedReplayWrite + " no_level=" + rejectedNoLevel;
    }

    /** 每 15 秒最多打一行 {@code be_mark}（限流：这是热路径）。 */
    private static void markLogThrottled() {
        final long now = System.currentTimeMillis();
        if (now - lastMarkLogMs < 15_000L) {
            return;
        }
        lastMarkLogMs = now;
        ShanhaiMod.LOGGER.info("[SHANHAI-REPLAY] be_mark n={} {}", markCount, probeLine());
    }

    private static void warnThrottled(String message, Throwable t) {
        final long now = System.currentTimeMillis();
        if (now - lastWarnMs < 30_000L) {
            return;
        }
        lastWarnMs = now;
        if (t == null) {
            ShanhaiMod.LOGGER.warn("[SHANHAI-REPLAY] {}", message);
        } else {
            ShanhaiMod.LOGGER.warn("[SHANHAI-REPLAY] {}", message, t);
        }
    }

    private ShanhaiReplayBlockEntityRecorder() {
    }
}
