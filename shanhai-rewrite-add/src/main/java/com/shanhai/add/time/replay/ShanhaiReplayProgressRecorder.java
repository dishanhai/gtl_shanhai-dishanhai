package com.shanhai.add.time.replay;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.shanhai.ShanhaiMod;
import com.shanhai.add.time.ShanhaiClockHooks;
import com.shanhai.add.time.ShanhaiClockMode;
import com.shanhai.add.time.ShanhaiWorldClock;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * 山海重构 · <b>机器进度记录器（瘦日志）</b>（第 1 层，逐 tick 连续）。
 *
 * <h2>1. 用户口径</h2>
 * <blockquote>「更不要提机器的进度条倒转了」</blockquote>
 * ⇒ 每 tick 只对"有配方进度"的机器读一个 int，变了就记 {@code (pos, before, after)}。
 * 这是"瘦日志"：一台机器一秒最多 20 条，且不序列化任何 NBT。
 *
 * <h2>2. 为什么不能靠 {@code setChanged}</h2>
 * javap 实证：GT {@code RecipeLogic#setProgress(int)} 只 {@code putfield progress}，
 * <b>不</b>调 {@code markDirty()} ⇒ 机器跑配方时 {@code BlockEntity#setChanged} 一次都不会被调到。
 * 所以候选集必须自己维护：{@code LevelChunk#setBlockEntity}（含区块加载）时登记；
 * 而"记录开始前就已经加载好"的机器永远不会再走那一拍 ⇒ 由 {@link #bootstrap} 在第一次
 * 有玩家在线时补登记一次（玩家周围 9×9 已加载区块，{@code load=false} 不加载新区块）。
 * 每 tick 只遍历"登记过的、能读到进度的"那些（不是全服方块实体）。
 *
 * <h2>3. 写不回去就点名</h2>
 * 回退时 {@link #write} 返回 {@link ShanhaiReplayProgressJournal.Outcome#UNSUPPORTED} 的，
 * 按<b>方块实体类型名</b>归类计数，进 {@code progress_unsupported} 报告（用户要求"点名是哪类机器、为什么"）。
 */
public final class ShanhaiReplayProgressRecorder {

    /** 五层自证行里这一层的判据（注入方法名；{@code ShanhaiReplayService#armedLayers} 反射查它）。 */
    public static final String LAYER_ID = "progress";
    public static final String MIXIN_METHOD = "shanhai$observeForReplay";

    /** 登记候选的上限（只登记"能读到进度"的方块实体）。 */
    private static final int MAX_CANDIDATES = 8_192;

    /** 补登记半径（区块）：以玩家所在区块为中心 {@code (2*4+1)^2 = 81} 块（见 {@link #bootstrap}）。 */
    private static final int CHUNK_RADIUS = 4;

    private static final ShanhaiReplayProgressJournal JOURNAL =
            new ShanhaiReplayProgressJournal(ShanhaiReplayConst.MAX_PROGRESS_RECORDS);

    /** pos → 候选（dim 在候选里；弱引用 BE）。 */
    private static final Map<Long, Candidate> CANDIDATES = new HashMap<>();

    /**
     * {@link #bootstrap} 的幂等闸：这一轮已经补登记过"记录开始前就加载好的机器"。
     *
     * <p>🔴 <b>先置位再干活</b>：异常时宁可这一轮不补，也<b>不许</b>每 tick 重扫
     * （一次性异常不能被放大成 20 Hz 的永久区块扫描）。
     */
    private static boolean bootstrapped;

    private static long observed;
    private static long written;
    private static long unsupported;
    private static long failed;

    // ---- 空通道排查读数（真机实测 progress=records=0 的直接产物）----
    /** {@code observe} 被调到几次（mixin 到底有没有挂上）。 */
    private static long observeCalls;
    /** 登记失败的次数与原因（{@code not_be} / {@code client} / {@code unsupported} / {@code cap}）。 */
    private static long rejectNotBe;
    private static long rejectClient;
    private static long rejectUnsupported;
    private static long rejectCap;
    /** 每 tick 扫描被调到几次 / 读到几次有效进度 / 记了几条。 */
    private static long scanTicks;
    private static long reads;
    private static long recorded;
    private static long lastProbeLogMs;
    /** 写不回去的类型名 → 次数（报告里逐类点名）。 */
    private static final Map<String, Integer> UNSUPPORTED_TYPES = new HashMap<>();

    /**
     * 候选。
     *
     * @param dim    维度小整数
     * @param ref    弱引用
     * @param last   上一次读到的进度（{@code Integer.MIN_VALUE} = 还没读过）
     */
    private record Candidate(int dim, WeakReference<BlockEntity> ref, int[] last) {
    }

    public static ShanhaiReplayProgressJournal journal() {
        return JOURNAL;
    }

    public static int candidates() {
        return CANDIDATES.size();
    }

    public static long observed() {
        return observed;
    }

    public static String unsupportedSummary() {
        if (UNSUPPORTED_TYPES.isEmpty()) {
            return "progress_unsupported=none";
        }
        final StringBuilder sb = new StringBuilder("progress_unsupported=");
        for (Map.Entry<String, Integer> e : UNSUPPORTED_TYPES.entrySet()) {
            sb.append(e.getKey()).append('x').append(e.getValue()).append(';');
        }
        return sb.toString();
    }

    public static void reset() {
        CANDIDATES.clear();
        bootstrapped = false;
        JOURNAL.clear();
        UNSUPPORTED_TYPES.clear();
        observed = 0L;
        written = 0L;
        unsupported = 0L;
        failed = 0L;
        observeCalls = 0L;
        rejectNotBe = 0L;
        rejectClient = 0L;
        rejectUnsupported = 0L;
        rejectCap = 0L;
        scanTicks = 0L;
        reads = 0L;
        recorded = 0L;
        lastProbeLogMs = 0L;
    }

    /**
     * 一条"这台机器到底有没有在记账"的读数（真机 {@code progress=records=0} 的硬判据）。
     *
     * <p>判读：
     * <ul>
     *   <li>{@code observe_calls=0} ⇒ 注入点没被调到（mixin 没挂上 / 世界还没放过机器）；</li>
     *   <li>{@code observe_calls>0 && candidates=0} ⇒ 挂上了，但登记的{" "}
     *       {@code rejected_unsupported} 全涨 ⇒ GT 类名/接口对不上（{@code gt_absent}）；</li>
     *   <li>{@code candidates>0 && reads=0} ⇒ 候选在，但每 tick 读不到进度（机器没在跑配方）；</li>
     *   <li>{@code reads>0 && recorded=0} ⇒ 读到了但值没变过（机器停着）。</li>
     * </ul>
     */
    public static String probeLine() {
        return "observe_calls=" + observeCalls + " candidates=" + CANDIDATES.size()
                + " scan_ticks=" + scanTicks + " reads=" + reads + " recorded=" + recorded
                + " rejected[not_be=" + rejectNotBe + " client=" + rejectClient
                + " unsupported=" + rejectUnsupported + " cap=" + rejectCap + "]"
                + " bootstrapped=" + (bootstrapped ? 1 : 0);
    }

    // ------------------------------------------------------------------ 登记

    /**
     * 看到一个新的方块实体（{@code LevelChunk#setBlockEntity} 与方块记录器都会调）。
     *
     * <p>只有"能读到进度"的才登记（其余方块实体连 map 都不进 ⇒ 每 tick 扫描是零成本的）。
     */
    public static void observe(Object beObj) {
        ++observeCalls;
        try {
            if (!(beObj instanceof BlockEntity be)) {
                ++rejectNotBe;
                return;
            }
            final Level level = be.getLevel();
            if (level == null || level.isClientSide()) {
                ++rejectClient;
                return;
            }
            if (!supportsProgress(be)) {
                ++rejectUnsupported;
                return;
            }
            if (CANDIDATES.size() >= MAX_CANDIDATES) {
                ++rejectCap;
                return;
            }
            final int dim = ShanhaiReplayEngine.dimIdOf(level.dimension());
            if (dim < 0) {
                return;
            }
            CANDIDATES.put(be.getBlockPos().asLong(),
                    new Candidate(dim, new WeakReference<>(be), new int[] {Integer.MIN_VALUE}));
            ++observed;
        } catch (Throwable ignored) {
            // 登记失败只意味着这台机器进不了进度时间线（不影响世界）
        }
    }

    private static boolean supportsProgress(BlockEntity be) {
        try {
            return ShanhaiReplayGtProgress.supports(be);
        } catch (Throwable ignored) {
            // 没有 GT（NoClassDefFoundError）或 GT 改了 API ⇒ 这一层不生效，不是崩溃点
            return false;
        }
    }

    // ------------------------------------------------------------------ 启动补登记

    /**
     * 把<b>已经在世界里加载好的</b>候选机器补登记一遍（{@code LevelChunk#setBlockEntity} 只覆盖"之后才进来的"）。
     *
     * <h2>为什么需要它</h2>
     * 候选的<b>唯一</b>登记点是 {@code LevelChunk#setBlockEntity}（区块加载 / 放方块那一刻）；
     * 而玩家基地的机器在这一轮记录开始前<b>早就加载完了</b> ⇒ {@code CANDIDATES} 恒空
     * ⇒ {@link #scan} 首行就早退，机器进度层一条都记不到（2026-11-14 日志：{@code progress=… records=0}）。
     *
     * <h2>边界：有界、只碰已加载的区块</h2>
     * 以每个玩家所在区块为中心的 {@code (2*CHUNK_RADIUS+1)^2 = 81} 块（{@link #CHUNK_RADIUS} = 4）；
     * 取区块用 {@code getChunk(x, z, ChunkStatus.FULL, false)}，<b>第 4 参 {@code false} = 不加载/不生成新区块</b>
     * ⇒ 返回 {@code null}（没加载）就跳过，一个方块都不碰。
     *
     * <h2>幂等：先置位再干活</h2>
     * 只补一次（{@link #bootstrapped}）。失败/异常时宁可这一轮不补，也不许每 tick 重扫 ——
     * 一次性异常不能被放大成 20 Hz 的永久扫描。代价见 {@code probeLine()} 的 {@code bootstrapped=}：
     * 这一轮若因异常扫到 0 块，闸门也已经落下，不会自动重试（{@link #reset} 才会复位）。
     * （{@link #scan} 那侧另有一道前置：没有在线玩家时不进本方法，见 {@link #anyPlayerOnline}。）
     */
    public static void bootstrap(MinecraftServer server) {
        if (bootstrapped) {
            return;
        }
        // 🔴 先置位再干活（见方法注释的"幂等"一节）
        bootstrapped = true;
        int chunks = 0;
        try {
            if (server != null) {
                for (ServerLevel level : server.getAllLevels()) {
                    if (level == null) {
                        continue;
                    }
                    for (ServerPlayer player : level.players()) {
                        if (player == null) {
                            continue;
                        }
                        final int cx = player.getBlockX() >> 4;
                        final int cz = player.getBlockZ() >> 4;
                        for (int dx = -CHUNK_RADIUS; dx <= CHUNK_RADIUS; ++dx) {
                            for (int dz = -CHUNK_RADIUS; dz <= CHUNK_RADIUS; ++dz) {
                                // 第 4 参 false = 不加载新区块：只碰已经加载好的，返回 null 就跳过
                                final ChunkAccess chunk =
                                        level.getChunk(cx + dx, cz + dz, ChunkStatus.FULL, false);
                                if (!(chunk instanceof LevelChunk loadedChunk)) {
                                    continue;
                                }
                                ++chunks;
                                for (BlockEntity be : loadedChunk.getBlockEntities().values()) {
                                    // observe 内部已经有"能不能读到进度"的过滤，这里不复制那段判据
                                    observe(be);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            // 补登记失败只意味着"这一轮机器进度层可能是空的"（不影响世界），绝不许拖垮服务端 tick
            ShanhaiMod.LOGGER.warn("[SHANHAI-REPLAY] progress_bootstrap 失败"
                    + "（这一轮机器进度层可能是空的） chunks={} candidates={} err={}",
                    chunks, candidates(), t.toString());
            return;
        }
        ShanhaiMod.LOGGER.info("[SHANHAI-REPLAY] progress_bootstrap chunks={} candidates={}",
                chunks, candidates());
    }

    /**
     * 有没有在线玩家 —— {@link #bootstrap} 的调用前置。
     *
     * <p>为什么必须有：服务端启动后的头几十拍<b>还没有玩家</b>（{@code ServerStartedEvent} 早于接客），
     * 这一拍若进 {@link #bootstrap}，它会以 {@code chunks=0} 落闸 ⇒ <b>这一整次启动都补不上</b>
     * （"机器早就加载好"的基地永远进不了候选，正是本轮要修的那个场景）。
     * 没玩家 = 没有"玩家周边的基地"可扫 ⇒ 不落闸、下一拍再问；
     * 代价只是几次 {@code players().isEmpty()}，<b>不扫区块</b>。
     */
    private static boolean anyPlayerOnline(MinecraftServer server) {
        if (server == null) {
            return false;
        }
        try {
            for (ServerLevel level : server.getAllLevels()) {
                if (level != null && !level.players().isEmpty()) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // 读不到当"没有"：下一拍再问；绝不因为读不到就去扫，更不许因此落闸
        }
        return false;
    }

    // ------------------------------------------------------------------ 每 tick 采集

    /**
     * 每个外层 tick：把候选机器的进度读一遍，变了就记。
     *
     * <p>⚠️ 只在 LIVE 记（暂停/倒流期间不记，否则回退自己的写会被当成新历史）。
     */
    public static void scan(MinecraftServer server) {
        ++scanTicks;
        // 候选空 ≠ 世界里没有机器：多半是"机器在记录开始前就加载好了"（setBlockEntity 那一拍早过去了）。
        // 🔴 补登记**不放在"候选空"分支里**：只要先有一台新放的机器走 setBlockEntity 进了候选，
        //    基地里的老机器就永远补不上。闸门只靠 bootstrapped（reset() 才复位），仍只扫一次。
        // ⚠️ 前置"有在线玩家"：ServerStartedEvent 之后的头几十拍还没有玩家，那时进 bootstrap 会以
        //    chunks=0 落闸，本次启动就再也补不上。
        if (!bootstrapped && anyPlayerOnline(server)) {
            bootstrap(server);
        }
        if (CANDIDATES.isEmpty()) {
            return;
        }
        if (!ShanhaiReplayModeCompose.recordsUnder(ShanhaiWorldClock.SERVER.getMode())) {
            return;
        }
        probeLogThrottled();
        final List<Long> dead = new ArrayList<>();
        for (Map.Entry<Long, Candidate> entry : CANDIDATES.entrySet()) {
            final Candidate cand = entry.getValue();
            final BlockEntity be = cand.ref() == null ? null : cand.ref().get();
            if (be == null || be.isRemoved()) {
                dead.add(entry.getKey());
                continue;
            }
            final ServerLevel level = levelOf(server, cand.dim());
            if (level == null) {
                continue;
            }
            final int progress;
            try {
                progress = ShanhaiReplayGtProgress.read(be);
            } catch (Throwable ignored) {
                continue;
            }
            if (progress == Integer.MIN_VALUE) {
                continue;
            }
            ++reads;
            final int before = cand.last()[0];
            cand.last()[0] = progress;
            if (before == Integer.MIN_VALUE || before == progress) {
                continue;
            }
            if (JOURNAL.record((int) level.getGameTime(), cand.dim(), entry.getKey(), before, progress)) {
                ++recorded;
            }
        }
        for (Long key : dead) {
            CANDIDATES.remove(key);
        }
    }

    /** 每 15 秒最多打一行 {@code progress_probe}（把"两个空通道"从猜测变成读数）。 */
    private static void probeLogThrottled() {
        final long now = System.currentTimeMillis();
        if (now - lastProbeLogMs < 15_000L) {
            return;
        }
        lastProbeLogMs = now;
        ShanhaiMod.LOGGER.info("[SHANHAI-REPLAY] progress_probe {}", probeLine());
    }

    // ------------------------------------------------------------------ 回退

    /**
     * 把一台机器的进度写回过去的值。
     *
     * @return {@link ShanhaiReplayProgressJournal.Outcome}
     */
    public static ShanhaiReplayProgressJournal.Outcome write(ServerLevel level, long pos, int progress) {
        if (level == null) {
            ++failed;
            return ShanhaiReplayProgressJournal.Outcome.FAILED;
        }
        final BlockEntity be = level.getBlockEntity(BlockPos.of(pos));
        if (be == null) {
            ++failed;
            return ShanhaiReplayProgressJournal.Outcome.FAILED;
        }
        if (!supportsProgress(be)) {
            ++unsupported;
            UNSUPPORTED_TYPES.merge(typeName(be), 1, Integer::sum);
            return ShanhaiReplayProgressJournal.Outcome.UNSUPPORTED;
        }
        ShanhaiClockHooks.beginReplayWrite();
        try {
            if (ShanhaiReplayGtProgress.write(be, progress)) {
                ++written;
                return ShanhaiReplayProgressJournal.Outcome.WRITTEN;
            }
            ++unsupported;
            UNSUPPORTED_TYPES.merge(typeName(be), 1, Integer::sum);
            return ShanhaiReplayProgressJournal.Outcome.UNSUPPORTED;
        } catch (Throwable t) {
            ++failed;
            ShanhaiMod.LOGGER.warn("[SHANHAI-REPLAY] 写回机器进度失败 pos={} err={}", pos, t.toString());
            return ShanhaiReplayProgressJournal.Outcome.FAILED;
        } finally {
            ShanhaiClockHooks.endReplayWrite();
        }
    }

    private static String typeName(BlockEntity be) {
        try {
            return be.getType().toString();
        } catch (Throwable ignored) {
            return be.getClass().getName();
        }
    }

    private static ServerLevel levelOf(MinecraftServer server, int dim) {
        if (server == null) {
            return null;
        }
        final net.minecraft.resources.ResourceKey<Level> key = ShanhaiReplayEngine.dimKeyOf(dim);
        return key == null ? null : server.getLevel(key);
    }

    public static String summary() {
        return "candidates=" + CANDIDATES.size() + " observed=" + observed
                + " written=" + written + " unsupported=" + unsupported + " failed=" + failed
                + " " + probeLine() + " " + unsupportedSummary()
                + " journal[" + JOURNAL.summary() + "]";
    }

    private ShanhaiReplayProgressRecorder() {
    }
}
