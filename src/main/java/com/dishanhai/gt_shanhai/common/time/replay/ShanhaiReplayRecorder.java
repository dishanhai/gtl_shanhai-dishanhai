package com.dishanhai.gt_shanhai.common.time.replay;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.common.time.ShanhaiWorldClock;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * 山海重构 · <b>倒流记录器（方块层）</b>：两个见证点，一本日志。
 *
 * <h2>1. 两个见证点，为什么是这两个</h2>
 * <table border="1">
 *   <caption>入口覆盖</caption>
 *   <tr><th>见证点</th><th>覆盖</th><th>混入</th></tr>
 *   <tr><td>{@code Level#removeBlock} HEAD</td>
 *       <td><b>玩家挖掉 / 破坏</b>（{@code ServerPlayerGameMode#destroyBlock → Level#removeBlock}）</td>
 *       <td>{@code ShanhaiLevelRemoveBlockReplayMixin}</td></tr>
 *   <tr><td>{@code LevelChunk#setBlockState} HEAD</td>
 *       <td>放置 / 爆炸 / 流体 / 活塞 / 第三方 mod 的一切 {@code BlockState} 写入</td>
 *       <td>{@code ShanhaiLevelChunkReplayMixin}</td></tr>
 * </table>
 * 两个点会撞车（{@code removeBlock} 内部就是一次 {@code setBlock}）⇒ 用 <b>ThreadLocal 吞一次</b>
 * （{@link #SWALLOW}）：{@code removeBlock} 记完就把它挂上，紧接着 {@code setBlockState} 到同一个 pos
 * 时把那一次让掉。效果是"一笔变化只记一条"，而不是"多挂一个点就多记一条"。
 *
 * <h2>2. 破坏为什么必须单独有一个见证点</h2>
 * 用户实证：「我这段时间不仅破坏了方块……最大的问题是方块状态回不来，并不是个别，而是所有」。
 * 破坏链是 {@code destroyBlock → removeBlock → setBlock → LevelChunk#setBlockState}，
 * 挂最内层理论上就能看见；但 {@code Level#setBlock} 在<b>目标格已经是空气</b>时会提前 return
 * （同态早退）—— 那条早退发生在 {@code setBlockState} 内部，HEAD 仍然会到，
 * 可一旦上游（第三方 mod / GT / 我们自己的两段式）先动了手，这条链就不一定完整。
 * ⇒ 在最外层再放一个见证点，代价是一个 ThreadLocal，收益是"破坏<b>结构上</b>不可能漏记"。
 *
 * <h2>3. 记什么（`fromState` 必须是<b>变化之前</b>那个状态）</h2>
 * 🔴 {@code fromState} 是回退时要写回的目标态。它<b>只能</b>取"变化发生前"的旧态：
 * 取反（存成 after）会让回退变成"把空气写到空气上" ⇒ 原版同态早退 ⇒ 静默失败 ⇒
 * 用户看到的"方块状态一条都回不来"。所以两个入口都先用 {@code level.getBlockState(pos)}
 * 读旧态，再交给同一个 {@link #recordChange}。
 *
 * <h2>4. 三条过滤</h2>
 * <ol>
 *   <li><b>只记服务端</b>：客户端 {@code LevelChunk} 也走这个方法，记了会把幻影方块当历史；</li>
 *   <li><b>只在 LIVE 记</b>：{@code PAUSED}/{@code REPLAY} 一律不记（结构上不可能把自己写的记成新历史）；</li>
 *   <li><b>同态自写直接丢</b>：GT 机器会在同一 tick 把同一个方块写回同一个状态，不挡会记出十万条。</li>
 * </ol>
 *
 * <h2>5. 失败方向</h2>
 * 全程 {@code try/catch(Throwable)} 自吞 + 限流 WARN：记录器挂了<b>绝不能</b>让方块写不进去
 * （那是比"倒流不完整"严重的多得多的故障）。
 */
public final class ShanhaiReplayRecorder {

    /** 五层自证行里这一层的 id 与注入方法名（{@code ShanhaiReplayService#armedLayers} 反射查它）。 */
    public static final String LAYER_ID = "blocks";
    public static final String MIXIN_METHOD = "shanhai$recordForReplay";
    /** 破坏见证点（{@code Level#removeBlock}）。 */
    public static final String LAYER_DESTROY_ID = "blocks_destroy";
    public static final String MIXIN_DESTROY_METHOD = "shanhai$recordRemoveForReplay";

    private static final ShanhaiReplayJournal JOURNAL =
            new ShanhaiReplayJournal(ShanhaiReplayConst.MAX_RECORDS);

    /** 记成功多少笔 / 同态丢了多少笔（status 判读用）。 */
    private static long recordAttempts;
    private static long recordKept;
    private static long recordNoop;
    private static long recordFailed;

    /** 分类计数（"破坏到底记没记"的唯一判据；见 {@link #classifyLine()}）。 */
    private static long placed;
    private static long broken;
    private static long swapped;

    /** 闸门各拒绝了几笔（把"记不到"从猜测变成读数）。 */
    private static long skippedClient;
    private static long skippedNotLive;
    private static long skippedReplayWrite;
    private static long skippedNoopGate;
    private static long skippedNoState;

    /** 两个见证点各被调到几次（armed 判读的辅助读数）。 */
    private static long chunkHookCalls;
    private static long destroyHookCalls;
    /** 记录侧首几条明细打了几条（{@code ShanhaiReplayApplier#TRACE_LIMIT} 封顶）。 */
    private static long recordSamples;

    /**
     * {@code removeBlock} 已经记过这个 pos ⇒ 紧接着那次 {@code setBlockState} 让掉（一笔变化只记一条）。
     *
     * <p>用 ThreadLocal 而不是全局集合：{@code removeBlock} 与它内部的 {@code setBlock} 必定同线程、
     * 同步相邻，跨线程共享只会误吞别人的记录。
     */
    private static final ThreadLocal<Long> SWALLOW = new ThreadLocal<>();

    private static long lastWarnMs;

    /** 倒流/记录器共用同一份日志（服务收尾时会 {@code clear()} 它）。 */
    public static ShanhaiReplayJournal journal() {
        return JOURNAL;
    }

    public static long recordKept() {
        return recordKept;
    }

    public static long recordNoop() {
        return recordNoop;
    }

    public static long recordFailed() {
        return recordFailed;
    }

    public static long recordAttempts() {
        return recordAttempts;
    }

    public static long chunkHookCalls() {
        return chunkHookCalls;
    }

    public static long destroyHookCalls() {
        return destroyHookCalls;
    }

    public static long blocksPlaced() {
        return placed;
    }

    public static long blocksBroken() {
        return broken;
    }

    /**
     * 一行分类读数（把"破坏到底记没记"从猜测变成日志里的一对数）。
     *
     * <pre>
     *   placed=… broken=… swapped=… noop=… no_state=… not_live=… replay_write=… client=…
     * </pre>
     * 判读：{@code broken=0} 而用户确实挖了方块 ⇒ 破坏没进日志（这一层要修）；
     * {@code broken>0} 但回放 {@code blocks=0} ⇒ 是取用/写回那一侧的问题，不是记录。
     */
    public static String classifyLine() {
        return "placed=" + placed + " broken=" + broken + " swapped=" + swapped
                + " noop=" + recordNoop + " no_state=" + skippedNoState
                + " not_live=" + skippedNotLive + " replay_write=" + skippedReplayWrite
                + " client=" + skippedClient + " chunk_hook=" + chunkHookCalls
                + " destroy_hook=" + destroyHookCalls;
    }

    /** 换世界/重开服：把缓冲、状态索引、NBT 池、锚点一起清干净。 */
    public static void resetAll() {
        JOURNAL.clear();
        // 🔴 锚点不跟着 ring 一起走：它记的是"上一个世界里的某个人站在哪"，
        //    新世界必须把它丢掉（否则第一次 /time rewind 会朝一个陌生 tick 回退）。
        JOURNAL.clearAnchor();
        ShanhaiStateIndex.clear();
        ShanhaiReplayPayloads.clear();
        recordAttempts = 0L;
        recordKept = 0L;
        recordNoop = 0L;
        recordFailed = 0L;
        placed = 0L;
        broken = 0L;
        swapped = 0L;
        skippedClient = 0L;
        skippedNotLive = 0L;
        skippedReplayWrite = 0L;
        skippedNoopGate = 0L;
        skippedNoState = 0L;
        chunkHookCalls = 0L;
        destroyHookCalls = 0L;
        recordSamples = 0L;
        SWALLOW.remove();
    }

    // ------------------------------------------------------------------ 见证点 ①：LevelChunk#setBlockState

    /**
     * {@code LevelChunk#setBlockState} HEAD 的转发入口（混入类体内零 MC 成员引用，见
     * {@code ShanhaiLevelChunkReplayMixin} 类注释）。
     *
     * @param chunkObj {@code (Object) this}，运行期是 {@link LevelChunk}
     * @param pos      写入坐标
     * @param newState 即将写入的状态（HEAD 时区块里还是旧状态）
     */
    public static void onSetBlockState(Object chunkObj, Object pos, Object newState) {
        if (!com.dishanhai.gt_shanhai.common.time.ReplayRecording.isEnabled()) return;
        ++recordAttempts;
        ++chunkHookCalls;
        try {
            if (!(chunkObj instanceof LevelChunk chunk)
                    || !(pos instanceof BlockPos blockPos)
                    || !(newState instanceof BlockState after)) {
                return;
            }
            final Level level = chunk.getLevel();
            if (level == null) {
                return;
            }
            // 🔴 removeBlock 那个见证点已经记过这一笔 ⇒ 这次让掉（一笔变化只记一条）
            final Long swallowed = SWALLOW.get();
            if (swallowed != null && swallowed.longValue() == blockPos.asLong()) {
                SWALLOW.remove();
                return;
            }
            recordChange(level, blockPos, chunk.getBlockState(blockPos), after);
        } catch (Throwable t) {
            ++recordFailed;
            warnThrottled("记录器内部错误（这一笔变化没记住）", t);
        }
    }

    // ------------------------------------------------------------------ 见证点 ②：Level#removeBlock

    /**
     * {@code Level#removeBlock} HEAD 的转发入口（{@code ShanhaiLevelRemoveBlockReplayMixin}）。
     *
     * <p>这是<b>破坏</b>的独立见证点：{@code ServerPlayerGameMode#destroyBlock} 的第一步就是它。
     * 记完把 pos 挂到 {@link #SWALLOW} 上，让紧接着的那次 {@code setBlockState} 不再重复记。
     *
     * @param levelObj {@code (Object) this}，运行期是 {@link Level}
     * @param posObj   被移除的坐标
     */
    public static void onRemoveBlock(Object levelObj, Object posObj) {
        if (!com.dishanhai.gt_shanhai.common.time.ReplayRecording.isEnabled()) return;
        ++recordAttempts;
        ++destroyHookCalls;
        SWALLOW.remove();
        try {
            if (!(levelObj instanceof Level level) || !(posObj instanceof BlockPos pos)) {
                return;
            }
            final BlockState before = level.getBlockState(pos);
            if (before.isAir()) {
                return;   // 本来就是空气：没什么可记（也给不出"破坏"这条事实）
            }
            // 破坏的"之后"就是空气 —— 回退时把 before 写回去
            if (recordChange(level, pos, before, Blocks.AIR.defaultBlockState())) {
                SWALLOW.set(Long.valueOf(pos.asLong()));
            }
        } catch (Throwable t) {
            ++recordFailed;
            warnThrottled("破坏见证点内部错误（这一笔破坏没记住）", t);
        }
    }

    // ------------------------------------------------------------------ 公共记账

    /**
     * 两个见证点共用的记账体：判闸 → 取 BE 载荷 → 进日志 → 分类计数。
     *
     * @return {@code true} = 这一笔真的进了日志
     */
    private static boolean recordChange(Level level, BlockPos pos, BlockState before, BlockState after) {
        final boolean same = before == after || before.equals(after);
        final int fromId = same ? -1 : ShanhaiStateIndex.idOf(before);
        final int afterId = same ? -1 : ShanhaiStateIndex.idOf(after);
        // 🔴 判据全在纯逻辑核里（自检 H 组逐条断言；包括"回放自己写的块不许被记"这条负对照）
        final ShanhaiReplayRecordGate.Reason reason = ShanhaiReplayRecordGate.decide(
                ShanhaiWorldClock.SERVER.getMode(), level.isClientSide(),
                ShanhaiReplayGate.inReplayWrite(), fromId >= 0 && afterId >= 0, same);
        switch (reason) {
            case ACCEPT -> {
                // 继续往下记
            }
            case SKIP_NOOP -> {
                ++recordNoop;
                ++skippedNoopGate;
                return false;
            }
            case SKIP_NO_STATE -> {
                ++recordFailed;
                ++skippedNoState;
                warnThrottled("状态索引已满，这一笔变化没有记录（倒流会少一块）", null);
                return false;
            }
            case SKIP_CLIENT -> {
                ++skippedClient;
                return false;
            }
            case SKIP_NOT_LIVE -> {
                ++skippedNotLive;
                return false;
            }
            default -> {
                ++skippedReplayWrite;
                return false;   // 回放自己的写
            }
        }
        // ④ 方块实体 NBT：只有"变化前该位置有方块实体"才存
        int payload = ShanhaiReplayJournal.NO_PAYLOAD;
        final BlockEntity be = level.getBlockEntity(pos);
        if (ShanhaiReplayJournal.needsPayload(be != null, false)) {
            payload = capturePayload(be);
        }
        // ⑤ 新放上去的方块如果带方块实体，顺手登记进"机器进度候选"（GT 机器就是这么被发现的）
        final BlockEntity afterBe = level.getBlockEntity(pos);
        if (afterBe != null) {
            ShanhaiReplayProgressRecorder.observe(afterBe);
        }
        // ⑥ 🔴 分类计数：破坏 / 放置 / 互换（"破坏记没记"的唯一判读口径）
        if (before.isAir() && !after.isAir()) {
            ++placed;
        } else if (!before.isAir() && after.isAir()) {
            ++broken;
        } else {
            ++swapped;
        }
        final int dim = ShanhaiReplayEngine.dimIdOf(level.dimension());
        // 🔴 记录侧的首 5 条明细：`from` 必须是"变化之前"那个状态。
        //    真机血账是"方块状态一条都回不来"，而"存成了 after"与"存成了 before"在日志上
        //    长得一样 —— 所以把 from/after 与两侧的 isAir 直接打出来（5 条封顶，不刷屏）。
        if (recordSamples < ShanhaiReplayApplier.TRACE_LIMIT) {
            ++recordSamples;
            GTDishanhaiMod.LOGGER.info("[SHANHAI-REPLAY] block_record_sample n={} dim={} pos={} from={}"
                            + " after={} from_air={} after_air={} (from 存的是变化前的旧状态)",
                    recordSamples, dim, pos.asLong(), fromId, afterId, before.isAir(), after.isAir());
        }
        JOURNAL.record((int) level.getGameTime(), dim, pos.asLong(), fromId, afterId, payload);
        ++recordKept;
        return true;
    }

    /**
     * 取一份方块实体 NBT 快照并放进池里。
     *
     * <p>为什么用 {@code saveWithFullMetadata()}：它带上 {@code x/y/z/id}，配合
     * {@code BlockEntity#load} 就能原样灌回去（{@code saveWithoutMetadata()} 缺 id，load 会失败）。
     *
     * <p>失败<b>不</b>让整笔变化丢掉：方块照样能回退，只是箱子里的东西回不去 —— 这种情况必须留 WARN。
     */
    private static int capturePayload(BlockEntity be) {
        try {
            final CompoundTag tag = be.saveWithFullMetadata();
            return ShanhaiReplayPayloads.put(tag);
        } catch (Throwable t) {
            warnThrottled("方块实体 NBT 快照失败（该格子倒流后数据回不去，方块仍会回退）", t);
            return ShanhaiReplayJournal.NO_PAYLOAD;
        }
    }

    /** 同一个问题最多每 30 秒打一次 WARN（记录器是每 tick 都跑的热路径，不许刷屏）。 */
    private static void warnThrottled(String message, Throwable t) {
        final long now = System.currentTimeMillis();
        if (now - lastWarnMs < 30_000L) {
            return;
        }
        lastWarnMs = now;
        if (t == null) {
            GTDishanhaiMod.LOGGER.warn("[SHANHAI-REPLAY] {}", message);
        } else {
            GTDishanhaiMod.LOGGER.warn("[SHANHAI-REPLAY] {}", message, t);
        }
    }

    private ShanhaiReplayRecorder() {
    }
}
