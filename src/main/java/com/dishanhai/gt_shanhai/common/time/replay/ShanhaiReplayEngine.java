package com.dishanhai.gt_shanhai.common.time.replay;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.common.time.ShanhaiClockHooks;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 山海重构 · <b>倒流的落点（五层的回退实现都在这）</b>。
 *
 * <h2>1. 方块：两段式（与 {@link ShanhaiReplayApplier} 的契约逐条对应）</h2>
 * <pre>
 *   ⓪ ServerLevel#getBlockState(pos).isAir() ?        ← 目标格已经是空气 ⇒ 跳过第一段（A1 根因）
 *   ① ServerLevel#setBlock(pos, AIR, 3)               ← flag 3：让世界承认"这里空了"，方块实体被移除
 *   ② ServerLevel#setBlock(pos, 目标态, 2)            ← flag 2：只刷客户端，不回灌邻块
 *   ③ BlockEntity#load(NBT)（只有带载荷时）            ← 把箱子里/机器里的数据放回去
 * </pre>
 * 🔴 ⓪ 这一条是 2026-11 的关键修复：原版 {@code Level#setBlock} 在"写进去和现在同态"时返回 {@code false}
 * （{@code LevelChunk#setBlockState} 同态返回 null），而"玩家挖掉的方块"目标格本来就是空气 ⇒
 * 旧实现把每一笔"挖掉"都判成写失败并跳过目标态 ⇒ 打破的方块一块都没回来。
 *
 * <h2>2. 每一笔写都包在 {@code beginReplayWrite()/endReplayWrite()} 里</h2>
 * 硬网 {@code ShanhaiLevelSetBlockClockMixin} 挂在 {@code Level#setBlock} 上，REPLAY 期间它本该拦一切
 * —— 包括我们自己。后门是 <b>ThreadLocal 可重入计数</b>（{@link ShanhaiReplayGate}）。
 *
 * <h2>2.5 🔴 客户端到底靠什么看见（2026-11-14 A 组：用户「方块是真的恢复了，但是需要我在那个地方
 * 重新放一个方块才会显示」）</h2>
 * 只看"服务端写进去了"是不够的：{@code Level#setBlock(..., flag 2)} 只走到
 * {@code Level#markAndNotifyBlock:247 → ServerLevel#sendBlockUpdated:948 → ServerChunkCache#blockChanged:415
 * → ChunkHolder#blockChanged:150}，<b>那一步只往 {@code changedBlocksPerSection} 的 BitSet 里塞一个位置，
 * 一个包都不发</b>。真正把 BitSet 变成 {@code ClientboundSectionBlocksUpdatePacket} 的地方只有
 * {@code ServerChunkCache#tickChunks:372-375}（{@code broadcastChanges}），而它在
 * {@code getChunkSource().tick(haveTime, <b>true</b>)} 分支里 —— 冻结路径刻意传 {@code false}
 * （{@code ShanhaiClockHooks#tickChunkSourceOnly}）⇒ <b>整段倒流一个方块包都没发出去</b>，
 * 而 BitSet 还留着：等玩家在世界里再放一个方块、下一拍 {@code tickChunks()} 跑到
 * {@code broadcastChanges} 时，<b>整段（包括那些早该显示的方块）才一起补发</b>
 * —— 这就是"要在原地重新放一个方块才显示"的机制。
 * <p>本版改成<b>显式双向</b>：两段写完之后把"这一格最终态"记进 {@link ShanhaiReplayClientSync}，
 * 每步结束时 {@link #flushClientSync()} 直发 {@code ClientboundBlockUpdatePacket}（同一个包，
 * 客户端落点 {@code ClientPacketListener#handleBlockUpdate:761 → ClientLevel#setServerVerifiedBlockState:133
 * → ClientLevel#sendBlockUpdated:555 → LevelRenderer#blockChanged}：标脏区块段 + 该段光照）。
 * 实体运动层同理：{@code ClientboundTeleportEntityPacket}（原因见 {@link #move} 的注释）。
 *
 * <h2>3. 其余四层</h2>
 * <table border="1">
 *   <caption>落到哪</caption>
 *   <tr><th>层</th><th>落点</th></tr>
 *   <tr><td>实体</td><td>{@link ShanhaiReplayEntityRecorder#remove}/{@link ShanhaiReplayEntityRecorder#rebuild}</td></tr>
 *   <tr><td>机器进度</td><td>{@link ShanhaiReplayProgressRecorder#write}（GT {@code RecipeLogic#setProgress}）</td></tr>
 *   <tr><td>方块实体最小差</td><td>{@link ShanhaiReplayBlockEntityRecorder#applyDiff}（工作 NBT + {@code load}）</td></tr>
 *   <tr><td>世界标量</td><td>{@link ShanhaiReplayScalarRecorder#write}（时间/天气/游戏规则/计分板）</td></tr>
 * </table>
 */
public final class ShanhaiReplayEngine
        implements ShanhaiReplayApplier.Sink,
                   ShanhaiReplayEntityJournal.Sink,
                   ShanhaiReplayProgressJournal.Sink,
                   ShanhaiReplayBlockEntityJournal.Sink,
                   ShanhaiReplayScalarJournal.Sink,
                   ShanhaiReplayEntityMotionJournal.Sink,
                   ShanhaiReplayMotionDispatch.Sender,
                   ShanhaiReplayHealthJournal.Sink {

    /**
     * 维度 → 小整数（第一次见到时分配；同一个服务端进程内稳定）。
     *
     * <p>为什么不用 {@code ResourceKey.hashCode()}：不同维度可能撞号，撞了就会把方块写到别的维度。
     */
    private static final Map<ResourceKey<Level>, Integer> DIM_IDS = new HashMap<>();
    private static final List<ResourceKey<Level>> DIM_KEYS = new ArrayList<>();

    private final MinecraftServer server;

    /** 写失败的原因（只进日志判读，不参与判定）。 */
    private long noLevel;
    private long noChunk;
    private long noState;
    private long noBe;
    private long entityRemoveFailed;
    private long entityRebuildFailed;
    private long progressWritten;
    private long progressUnsupported;
    private long beDiffWritten;
    private long beDiffFailed;
    private long scalarWritten;
    private long scalarUnsupported;
    private long scalarSkipped;
    private long alreadyAir;
    /** {@code setBlock} 返回 false 的次数（= 世界没变：同态早退或写失败，两者靠回读分开）。 */
    private long targetUnchanged;

    // ---- 方块写回账本（2026-11"方块状态一条都回不来"的直接产物）----
    // 计数本体在 ShanhaiReplayPacer.Counts 里（唯一来源），这里只留"首几条明细"与"期望态表"。
    /** 首几条方块记录的明细（最多 {@link ShanhaiReplayApplier#TRACE_LIMIT} 条，直接打日志）。 */
    private final List<String> blockSamples = new ArrayList<>();

    /**
     * 每个被写过的 pos 的"最老那次 fromState" = <b>窗口起点（锚点）时那个格子该长什么样</b>。
     *
     * <p>回放按新→旧应用 ⇒ 同一 pos 最后一次写的 {@code fromState} 就是它最老那次变化前的状态。
     * 收尾时抽样读回比对这个值，才是"方块到底有没有回来"的硬判据（{@code block_verify}）。
     */
    private final java.util.Map<Long, Integer> expectedState = new java.util.HashMap<>();

    // ---- 实体运动层 ----
    private long motionWritten;
    private long motionFailed;
    /** 按 uuid 找不到实体（区块没加载 / 已经被删）的 motion 条数（判据 A1）。 */
    private long motionUuidMissing;
    private long motionUuidAbsent;
    private long motionDimMismatch;
    private long motionAlreadyRemoved;
    /** 发包请求次数（{@code motion_sent=}，与 {@link #motionWritten} 分开）。 */
    private long motionSentMoves;
    /** 发包请求覆盖的玩家数之和（{@code motion_packets=}）。 */
    private long motionSentPackets;
    /** 找不到实体 / 发包时实体已经没了（{@code motion_send_failed=}）。 */
    private long motionSendFailed;

    // ---- 生物血量层 ----
    private long healthApplied;
    private long healthFailed;
    /** 血量记录是 0/负（"死亡那一刻"）而被跳过的条数（判据⑤：写 0 会把生物写死）。 */
    private long healthZeroSkipped;
    /** 被强制救活的实体 uuid（收尾时判 {@code entity_alive_after=}）。 */
    private final java.util.List<java.util.UUID> revivedUuids = new java.util.ArrayList<>();

    // ---- A：最后一次写入的坐标（收尾对齐用；判据 end_snap_max_delta ≤ 0.05） ----
    private final java.util.Map<java.util.UUID, float[]> lastWritten = new java.util.HashMap<>();
    private long endAlignSent;
    private double endAlignMax;

    // ---- 取证（用户："收尾瞬移十几格" ⇒ 先查清楚，不先修） ----
    /** uuid → 这个实体一共收到过几次位置包（②）。 */
    private final java.util.Map<java.util.UUID, long[]> sentByEntity = new java.util.HashMap<>();
    /** uuid → 实体类型（②的 top5 里要能看出是猪还是掉落物）。 */
    private final java.util.Map<java.util.UUID, String> typeOfEntity = new java.util.HashMap<>();
    /** 发包时"一个玩家都没覆盖"的次数（②）。 */
    private long sentNoPlayer;
    private long nearSkipConn;
    private long nearSkipDead;
    private long nearSkipDist;

    // ---- spawn 直发（重建实体的新 id 必须让客户端知道） ----
    private long spawnSent;
    private long spawnNoPlayer;
    private long spawnFailed;
    private long entityIdRemap;
    private long spawnRetry;
    private long spawnRetryOk;
    private final java.util.Map<String, long[]> spawnByType = new java.util.HashMap<>();
    private long spawnDataMissing;
    private final java.util.Map<java.util.UUID, Integer> knownIds = new java.util.HashMap<>();

    /** 重建实体立即直发 spawn 的读数（判据：`snap_unknown_id` 应归零）。 */
    public String spawnLine() {
        return "spawn_sent=" + this.spawnSent + " spawn_no_player=" + this.spawnNoPlayer
                + " spawn_failed=" + this.spawnFailed + " entity_id_remap=" + this.entityIdRemap
                + " spawn_data_missing=" + this.spawnDataMissing + " spawn_retry=" + this.spawnRetry
                + " no_player_reason=dim:0,conn:" + this.nearSkipConn
                + ",dead:" + this.nearSkipDead + ",dist:" + this.nearSkipDist
                + " spawn_retry_ok=" + this.spawnRetryOk + " " + spawnTop();
    }

    /** `spawn_by_type top5=<type>×n`（判据：`minecraft:falling_block` 必须 > 0）。 */
    private String spawnTop() {
        final java.util.List<java.util.Map.Entry<String, long[]>> rows =
                new java.util.ArrayList<>(this.spawnByType.entrySet());
        rows.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
        final StringBuilder sb = new StringBuilder("spawn_by_type top5=");
        final int n = Math.min(5, rows.size());
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(rows.get(i).getKey()).append('×').append(rows.get(i).getValue()[0]);
        }
        return sb.toString();
    }
    // ---- ① 粒子提前一拍（先有粒子、方块后出现） ----
    private final java.util.Set<Long> leaded = new java.util.HashSet<>();
    private long emittedAhead;

    /** 方块包延后几步发出（用户口径：先看到碎块聚起来，方块才出现；5 步 = 0.25 s）。 */
    public static final int BLOCK_LAG_STEPS = 5;

    /** 延后队列（同格后写覆盖前写；到 due 的那一 step 才真发）。 */
    private final java.util.Map<Long, Lagged> lagQueue = new java.util.LinkedHashMap<>();
    private long flushSeq;
    private long blockFlushedLate;
    private int restoredThisStep;

    private record Lagged(int dim, long pos, BlockState state, long dueStep) {
    }

    /** ① 的读数（{@code particle_lead= / emitted_ahead= / block_lag= / block_flushed_late=}）。 */
    public String leadLine() {
        return "particle_lead=" + BLOCK_LAG_STEPS + " emitted_ahead=" + this.emittedAhead
                + " block_lag=" + this.lagQueue.size() + " block_flushed_late=" + this.blockFlushedLate;
    }

    /**
     * 提前一发给"下一拍要恢复的方块"发粒子（{@link ShanhaiReplayApplier.Sink#leadParticles}）。
     *
     * <p>用户口径：「正常时序是方块破坏→发出粒子；我们是倒放，<b>应该是先有粒子，方块再出现</b>」。
     * 这里在写方块<b>之前</b>把粒子发出去；下一拍真写那一格时粒子<b>不再重复发</b>。
     */
    @Override
    public void leadParticles(int dim, long pos, int state) {
        try {
            final ServerLevel level = levelOf(dim);
            final BlockState target = ShanhaiStateIndex.stateOf(state);
            if (level == null || target == null || target.isAir()) {
                return;
            }
            final BlockPos p = BlockPos.of(pos);
            this.leaded.add(Long.valueOf(((long) dim << 48) ^ pos));
            ++this.emittedAhead;
            ++this.particlesSent;
            ++this.particlesWanted;
            sendReverseParticles(level, p, target);
        } catch (Throwable ignored) {
            // 粒子发不出去不影响方块回退
        }
    }

    // ---- 反向破坏粒子 ----
    /** 本拍每格最多发一次；每拍上限见 {@link ShanhaiReplayParticleBudget#MAX_PER_STEP}。 */
    private int particleBudget = ShanhaiReplayParticleBudget.MAX_PER_STEP;
    private long particlesSent;
    private long particlesWanted;
    /** 最近一次抽样的判据文本（{@code step=} 行尾直接贴）。 */
    private String lastParticleLine = "particles=0/0 sampled=false";

    /**
     * "客户端到底看见了几个"的账本（见类注释 §2.5）。
     *
     * <p>它同时是判据的<b>唯一来源</b>：{@code client_sync targeted=… sent=…} 由它出，
     * 负对照（flag 去掉 {@code UPDATE_CLIENTS}）也在它里面留痕（{@code no_flag=}）。
     */
    private final ShanhaiReplayClientSync clientSync = new ShanhaiReplayClientSync();

    /** 发包半径 = 视距（区块）× 16；更远的客户端本来就没加载那一段（原版也一样不发）。 */
    private int sendRadius = 512;

    /** 反向破坏粒子的单格数量（用户口径 8–12 ⇒ 取 10）。 */
    public static final int PARTICLE_COUNT = 10;

    /** 反向破坏粒子的投递半径（格）：用户口径 32。 */
    public static final int PARTICLE_RADIUS = 32;

    /** 期望态表上限（超了就不抽样自查，避免倒流期间撑内存）。 */
    private static final int MAX_EXPECTED = 65_536;

    public ShanhaiReplayEngine(MinecraftServer server) {
        this.server = server;
        try {
            final int chunks = server.getPlayerList().getViewDistance();
            if (chunks > 0) {
                // 🔴 半径 = 服务端视距，且不小于 256：上一版按 64/视距筛，把"要发 spawn 的实体"筛掉了
                this.sendRadius = Math.max(256, chunks * 16);
            }
        } catch (Throwable ignored) {
            // 取不到视距 ⇒ 用保守默认值（256 格），绝不因此不发包
        }
    }

    // ------------------------------------------------------------------ 维度映射

    /** 维度 → 小整数（记录时用；同时登记到反查表）。 */
    public static synchronized int dimIdOf(ResourceKey<Level> key) {
        if (key == null) {
            return -1;
        }
        final Integer hit = DIM_IDS.get(key);
        if (hit != null) {
            return hit.intValue();
        }
        final int id = DIM_KEYS.size();
        DIM_KEYS.add(key);
        DIM_IDS.put(key, Integer.valueOf(id));
        return id;
    }

    /** 小整数 → 维度 key（写回时用）。 */
    public static synchronized ResourceKey<Level> dimKeyOf(int id) {
        if (id < 0 || id >= DIM_KEYS.size()) {
            return null;
        }
        return DIM_KEYS.get(id);
    }

    /** 小整数 → {@link ServerLevel}（每次都从 server 现取，避免持有一份可能失效的缓存）。 */
    public ServerLevel levelOf(int dim) {
        if (this.server == null) {
            return null;
        }
        final ResourceKey<Level> key = dimKeyOf(dim);
        if (key == null) {
            return null;
        }
        return this.server.getLevel(key);
    }

    /** 换世界/重开服时清空维度表。 */
    public static synchronized void clearDims() {
        DIM_IDS.clear();
        DIM_KEYS.clear();
    }

    // ------------------------------------------------------------------ 方块层

    @Override
    public ShanhaiReplayApplier.Skip precheck(int dim, long pos, int targetState) {
        final ServerLevel level = levelOf(dim);
        if (level == null) {
            return ShanhaiReplayApplier.Skip.NONE; // 让 setAir 去记 failed，别在这里假装跳过
        }
        final BlockState target = ShanhaiStateIndex.stateOf(targetState);
        if (target == null || target.isAir()) {
            return ShanhaiReplayApplier.Skip.NONE; // 清空不会卡住人 ⇒ 照常回退
        }
        final BlockPos p = BlockPos.of(pos);
        final double x = p.getX();
        final double y = p.getY();
        final double z = p.getZ();
        try {
            for (ServerPlayer player : level.getPlayers(pl -> true)) {
                if (player == null || player.isRemoved()) {
                    continue;
                }
                if (player.getBoundingBox().intersects(x, y, z, x + 1.0D, y + 1.0D, z + 1.0D)) {
                    return ShanhaiReplayApplier.Skip.PLAYER_AABB;
                }
            }
        } catch (Throwable ignored) {
            // 判定失败 ⇒ 不跳过（宁可写，也不要把一大片该回退的方块"静默漏掉"）
        }
        return ShanhaiReplayApplier.Skip.NONE;
    }

    @Override
    public boolean isAir(int dim, long pos) {
        final ServerLevel level = levelOf(dim);
        if (level == null) {
            return false;   // 维度取不到 ⇒ 让 setAir 去记 failed（不许假装"已经是空气"就跳过写）
        }
        try {
            final BlockPos p = BlockPos.of(pos);
            if (!level.hasChunkAt(p)) {
                return false;
            }
            if (level.getBlockState(p).isAir()) {
                ++this.alreadyAir;
                return true;
            }
        } catch (Throwable ignored) {
            return false;
        }
        return false;
    }

    @Override
    public boolean setAir(int dim, long pos, int flags) {
        final ServerLevel level = levelOf(dim);
        if (level == null) {
            ++this.noLevel;
            return false;
        }
        final BlockPos p = BlockPos.of(pos);
        ShanhaiClockHooks.beginReplayWrite();
        try {
            if (!level.hasChunkAt(p)) {
                ++this.noChunk;
                return false;
            }
            // 🔴 用 setBlock 的返回值判成败（区块没加载/被拦时它是 false，不会抛）
            if (!level.setBlock(p, Blocks.AIR.defaultBlockState(), flags)) {
                ++this.noChunk;
                return false;
            }
            // 显式同步（见类注释 §2.5）：AIR 段也要让客户端看得见"这里空了"
            this.clientSync.mark(dim, pos, flags, Blocks.AIR.defaultBlockState());
            return true;
        } catch (Throwable t) {
            GTDishanhaiMod.LOGGER.warn("[SHANHAI-REPLAY] setAir 失败 pos={} err={}", p, t.toString());
            return false;
        } finally {
            ShanhaiClockHooks.endReplayWrite();
        }
    }

    @Override
    public boolean setTarget(int dim, long pos, int state, int flags, int payload) {
        final ServerLevel level = levelOf(dim);
        if (level == null) {
            ++this.noLevel;
            return false;
        }
        final BlockState target = ShanhaiStateIndex.stateOf(state);
        if (target == null) {
            ++this.noState;
            return false;
        }
        // 期望态 = 这个格子"窗口起点时该长什么样"（同一 pos 后写的是更老的记录 ⇒ 覆盖成更老的）
        if (this.expectedState.size() < MAX_EXPECTED || this.expectedState.containsKey(Long.valueOf(pos))) {
            this.expectedState.put(Long.valueOf(pos), Integer.valueOf(state));
        }
        final BlockPos p = BlockPos.of(pos);
        ShanhaiClockHooks.beginReplayWrite();
        try {
            // 🔴 显式同步 + 反向粒子都挂在"这一格被写"这件事上，所以必须<b>先 mark 再判返回值</b>
            //    （2026-11-14 真机 bug：{@code setBlock} 同态早退返回 false 时我们直接 return
            //      ⇒ 一格都不 mark ⇒ {@code particles=0/0}、方块包也漏发）。
            this.clientSync.mark(dim, pos, flags, target);
            // 🔴 注意：返回 false 有两种意思 —— ①真写失败 ②目标格本来就是目标态（原版同态早退）。
            //    权威判据是 stateIndexAt 的回读（见 ShanhaiReplayApplier 类注释 §2）。
            if (!level.setBlock(p, target, flags)) {
                ++this.targetUnchanged;   // "世界没变"——可能是同态早退，也可能是写失败；回读才分得开
                return false;
            }
            if (payload != ShanhaiReplayJournal.NO_PAYLOAD) {
                final CompoundTag tag = ShanhaiReplayPayloads.get(payload);
                if (tag == null) {
                    // 载荷丢了（池被清空/越界）：方块能回退，箱子里的东西回不去 —— 必须可判
                    GTDishanhaiMod.LOGGER.warn("[SHANHAI-REPLAY] payload 丢失 pos={} slot={}（方块已回退，数据没有）",
                            p, payload);
                } else {
                    final BlockEntity be = level.getBlockEntity(p);
                    if (be == null) {
                        GTDishanhaiMod.LOGGER.warn("[SHANHAI-REPLAY] 目标态没有方块实体 pos={} state={}（数据没能放回）",
                                p, target);
                    } else {
                        be.load(tag);
                        be.setChanged();
                    }
                }
            }
            return true;
        } catch (Throwable t) {
            GTDishanhaiMod.LOGGER.warn("[SHANHAI-REPLAY] setTarget 失败 pos={} err={}", p, t.toString());
            return false;
        } finally {
            ShanhaiClockHooks.endReplayWrite();
        }
    }

    /** 写后回读：这个格子<b>现在</b>的状态索引（{@code -1} = 读不到 ⇒ applier 退回看返回值）。 */
    @Override
    public int stateIndexAt(int dim, long pos) {
        final ServerLevel level = levelOf(dim);
        if (level == null) {
            return -1;
        }
        try {
            final BlockPos p = BlockPos.of(pos);
            if (!level.hasChunkAt(p)) {
                return -1;
            }
            return ShanhaiStateIndex.peek(level.getBlockState(p));
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /** 首几条方块记录的明细（真机判读用：dim/pos/from/after/isAir/返回值/回读）。 */
    @Override
    public void traceWrite(String line) {
        if (this.blockSamples.size() >= ShanhaiReplayApplier.TRACE_LIMIT) {
            return;
        }
        this.blockSamples.add(line);
        GTDishanhaiMod.LOGGER.info("[SHANHAI-REPLAY] block_sample n={} {}", this.blockSamples.size(), line);
    }

    // ------------------------------------------------------------------ 客户端同步（A 组的落点）

    /**
     * 把本步记下的"这些格子变了"<b>真的发出去</b>，并返回一行判据。
     *
     * <p>🔴 为什么不是"写一格发一格"：同一格在一个时间片里会被写两次
     * （AIR 段 + 目标态段），逐格直发会让客户端收到两条互相矛盾的包，也会把包数抬到与
     * 改格数不同量级。所以按 {@code (维度,pos)} 去重、<b>每步结束发一次最终态</b>
     * —— 与用户口径"每个回放步结束时对本步改过的区块统一发一次刷新"一致。
     *
     * @return {@code client_sync targeted=… sent=…} 一行（调用方按节流打 INFO/DEBUG）
     */
    /**
     * 把本步的方块更新<b>延后 {@value #BLOCK_LAG_STEPS} 步</b>再发，粒子立刻发。
     *
     * <p>用户口径（2026-11-14 14:2x）：「正常时序是方块破坏 → 发出粒子；我们是倒放，
     * <b>应该是先有粒子，方块再出现</b>」。同一 tick 内两个包到达 ⇒ 画面上是同一帧，观感仍是"方块先/同时"。
     * ⇒ 这里维护一个<b>待写队列</b>：本步只发粒子（坐标已知）并把方块包进队；
     * 上一步之前进队的那批方块到本步才真正发出 ⇒ 粒子比它的方块早 {@value #BLOCK_LAG_STEPS} 步（0.25 s）。
     *
     * <p>🔴 只延后<b>客户端包</b>：方块写回与回读（{@code block_verified}）仍在真实时刻完成，
     * 统计口径不受影响。
     */
    public String flushClientSync() {
        final java.util.List<ShanhaiReplayClientSync.Entry> pending = this.clientSync.takePending();
        // ① 粒子对本片要恢复的格子立刻发出（每拍上限 + 超限均匀抽样，见 ShanhaiReplayParticleBudget）
        final ShanhaiReplayParticleBudget.Plan plan =
                ShanhaiReplayParticleBudget.plan(pending.size(), this.particleBudget);
        final int[] picks = plan.indices();
        int pickCursor = 0;
        this.particlesWanted += plan.wanted();
        this.lastParticleLine = plan.toText();
        this.restoredThisStep = pending.size();
        for (int i = 0; i < pending.size(); i++) {
            final ShanhaiReplayClientSync.Entry entry = pending.get(i);
            final ServerLevel level = levelOf(entry.dim());
            if (level == null || !(entry.state() instanceof BlockState state)) {
                continue;
            }
            final BlockPos p = BlockPos.of(entry.pos());
            if (pickCursor < picks.length && picks[pickCursor] == i) {
                ++pickCursor;
                ++this.particlesSent;
                ++this.emittedAhead;
                sendReverseParticles(level, p, state);
            }
            // ② 方块包进"延后队列"（同格后写覆盖前写，due 取最新一次）
            final Long key = Long.valueOf(((long) entry.dim() << 48) ^ entry.pos());
            this.lagQueue.put(key, new Lagged(entry.dim(), entry.pos(), state,
                    this.flushSeq + BLOCK_LAG_STEPS));
        }
        ++this.flushSeq;
        // ③ 本步到期的那批方块包才真正发出（= 早 K 步前就发过粒子的那些格子）
        int drained = 0;
        final java.util.Iterator<java.util.Map.Entry<Long, Lagged>> it = this.lagQueue.entrySet().iterator();
        while (it.hasNext()) {
            final Lagged lag = it.next().getValue();
            if (lag.dueStep() > this.flushSeq) {
                continue;
            }
            it.remove();
            ++drained;
            sendBlockUpdate(lag);
        }
        final String line = this.clientSync.line() + " " + this.lastParticleLine
                + " particle_lead=" + BLOCK_LAG_STEPS + " emitted_ahead=" + this.emittedAhead
                + " restored_now=" + this.restoredThisStep + " block_lag=" + this.lagQueue.size();
        this.clientSync.resetStep();
        return line;
    }

    /** 收尾：把延后队列里剩下的一次性发完（不许丢）。 */
    public void flushLagNow() {
        for (Lagged lag : this.lagQueue.values()) {
            try {
                sendBlockUpdate(lag);
                ++this.blockFlushedLate;
            } catch (Throwable ignored) {
                // 单格发不出去不影响收尾
            }
        }
        this.lagQueue.clear();
    }

    /** 一拍内真正发出一个方块包（原版同款包）。 */
    private void sendBlockUpdate(Lagged lag) {
        try {
            final ServerLevel level = levelOf(lag.dim());
            if (level == null) {
                return;
            }
            final BlockPos p = BlockPos.of(lag.pos());
            final ClientboundBlockUpdatePacket packet = new ClientboundBlockUpdatePacket(p, lag.state());
            int players = 0;
            for (ServerPlayer player : playersNear(level, p)) {
                try {
                    player.connection.send(packet);
                    ++players;
                } catch (Throwable ignored) {
                    this.clientSync.countSendFailed();
                }
            }
            this.clientSync.countFlushed(players);
        } catch (Throwable ignored) {
            // 单格失败不影响其余
        }
    }

    /**
     * 在这一格发<b>它自己的破坏粒子</b>（{@code BlockParticleOption(ParticleTypes.BLOCK, state)}）。
     *
     * <p>为什么用显式的 {@code ClientboundLevelParticlesPacket} 而不是
     * {@code ServerLevel#sendParticles}：那个方法的投递范围是<b>区块追踪距离</b>（≥ 视距），
     * 而这里要的是"32 格内"—— 与 {@code playersNear} 同一套距离口径，读数才好判。
     *
     * <p>参数照用户口径：{@code count} ≈ 8–12（这里取 10）、{@code speed=0.1}、范围 32 格。
     */
    private void sendReverseParticles(ServerLevel level, BlockPos p, BlockState state) {
        try {
            if (state == null || state.isAir()) {
                return;
            }
            final net.minecraft.core.particles.ParticleOptions particle =
                    new net.minecraft.core.particles.BlockParticleOption(
                            net.minecraft.core.particles.ParticleTypes.BLOCK, state);
            final net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket packet =
                    new net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket(
                            particle, false,
                            p.getX() + 0.5D, p.getY() + 0.5D, p.getZ() + 0.5D,
                            0.4F, 0.4F, 0.4F, 0.1F, PARTICLE_COUNT);
            for (ServerPlayer player : playersNear(level, p, PARTICLE_RADIUS)) {
                try {
                    player.connection.send(packet);
                } catch (Throwable ignored) {
                    // 单个玩家发不出去不影响这一格
                }
            }
        } catch (Throwable ignored) {
            // 粒子发不出去不影响方块本身（负对照就是"一格粒子都不发"）
        }
    }

    /** 实体运动层的显式下发（见 {@link #sendMove} 的注释）；返回值 = 这次发给了几个玩家。 */
    private int broadcastEntityMove(ServerLevel level, Entity entity) {
        try {
            final ClientboundTeleportEntityPacket packet = new ClientboundTeleportEntityPacket(entity);
            int players = 0;
            for (ServerPlayer player : playersNear(level, entity.blockPosition())) {
                try {
                    player.connection.send(packet);
                    ++players;
                } catch (Throwable ignored) {
                    // 同上
                }
            }
            this.clientSync.countMotion(players);
            return players;
        } catch (Throwable ignored) {
            // 发包失败不许影响方块/实体本身的回退
            return 0;
        }
    }

    /** 这个维度里"离这一格够近、值得收包"的玩家（原版也只在追踪范围内下发）。 */
    private java.util.List<ServerPlayer> playersNear(ServerLevel level, BlockPos p) {
        return playersNear(level, p, this.sendRadius);
    }

    /** 同上，但投递半径由调用方给（反向粒子用 32 格）。 */
    private java.util.List<ServerPlayer> playersNear(ServerLevel level, BlockPos p, double radius) {
        java.util.List<ServerPlayer> out = java.util.List.of();
        try {
            final double cx = p.getX() + 0.5D;
            final double cz = p.getZ() + 0.5D;
            final double limit = radius * radius;
            for (ServerPlayer player : level.players()) {
                if (player == null || player.isRemoved() || player.connection == null) {
                    if (player == null || player.connection == null) {
                        ++this.nearSkipConn;
                    } else {
                        ++this.nearSkipDead;
                    }
                    continue;
                }
                final double dx = player.getX() - cx;
                final double dz = player.getZ() - cz;
                if (dx * dx + dz * dz > limit) {
                    ++this.nearSkipDist;
                    continue;
                }
                if (out.isEmpty()) {
                    out = new java.util.ArrayList<>(2);
                }
                out.add(player);
            }
        } catch (Throwable ignored) {
            return java.util.List.of();
        }
        return out;
    }

    /** 判据读数（{@code stop} 行里带上，方便一眼看"客户端到底收到几个"）。 */
    public String clientSyncLine() {
        return this.clientSync.line();
    }

    // ------------------------------------------------------------------ 实体运动层

    /**
     * 把实体摆回过去那个位置（"猪倒着走"的落点）。
     *
     * <p>🔴 用 {@code moveTo(...)} + {@code setOldPosAndRot()}：后者把插值起点也设成同一处，
     * 否则客户端会把"我们写的这一跳"插值成一次滑行（同一套手法见本工程的时钟插值）。
     * 回放期间实体<b>不 tick</b>（世界 tick 被整段取消）⇒ 位置只由我们写。
     *
     * <p>🔴 <b>2026-11-14 起本方法只写位置、不发包</b>：发包挪到 {@link #sendMove}，
     * 由 {@link ShanhaiReplayMotionDispatch} 保证"每条成功的写回恰好请求一次发包"。
     * 旧实现把两件事写在同一个方法里，日志上"写了没发"和"发了没收到"分不开。
     */
    @Override
    public boolean move(int dim, long uuidMost, long uuidLeast, float x, float y, float z,
                        float yRot, float xRot) {
        final ServerLevel level = levelOf(dim);
        if (level == null) {
            ++this.motionFailed;
            ++this.motionDimMismatch;      // 三档②：维度对不上（维度表被清 / 换世界）
            return false;
        }
        try {
            final net.minecraft.world.entity.Entity entity =
                    level.getEntity(new java.util.UUID(uuidMost, uuidLeast));
            if (entity == null) {
                ++this.motionFailed;
                ++this.motionUuidMissing;
                ++this.motionUuidAbsent;      // 三档①：uuid 根本不存在（区块没加载 / 从没到这个客户端）
                return false;
            }
            if (entity.isRemoved()) {
                ++this.motionFailed;
                ++this.motionUuidMissing;
                ++this.motionAlreadyRemoved;  // 三档③：实体已被移除（本 tick 被删掉）
                return false;
            }
            if (entity instanceof ServerPlayer) {
                ++this.motionFailed;
                return false;   // 玩家的时间不受影响（第四道保险）
            }
            ShanhaiClockHooks.beginReplayWrite();
            try {
                entity.moveTo(x, y, z, yRot, xRot);
                entity.setOldPosAndRot();
                ++this.motionWritten;
                // 取证：最后一次写入的坐标（收尾"十几格瞬移"要能和服务端当时的坐标对账）
                this.lastWritten.put(entity.getUUID(), new float[]{x, y, z});
                ShanhaiReplayEntityMotionRecorder.tally()
                        .applied(ShanhaiReplayEntityMotionRecorder.typeKeyOf(entity));
                return true;
            } finally {
                ShanhaiClockHooks.endReplayWrite();
            }
        } catch (Throwable t) {
            ++this.motionFailed;
            GTDishanhaiMod.LOGGER.warn("[SHANHAI-REPLAY] 写回实体位置失败 uuid={} err={}",
                    Long.toHexString(uuidMost), t.toString());
            return false;
        }
    }

    /**
     * 实体位置<b>发包</b>（{@code ClientboundTeleportEntityPacket}）。
     *
     * <p>🔴 为什么必须显式发：原版由 {@code ServerEntity#sendChanges} 下发
     * （{@code ClientboundMoveEntityPacket}/{@code Teleport}），它的驱动是
     * {@code ChunkMap#tick()}（1.20.1 {@code ChunkMap.java:1188}，被
     * {@code ServerChunkCache#tickChunks:378} 调用）—— 冻结路径同样跳过了 {@code tickChunks()}
     * ⇒ 我们写回的位置客户端一个都收不到。
     *
     * <p>🔴 为什么"发了"还不够：客户端 {@code ClientPacketListener#handleTeleportEntity}
     * （1.20.1 {@code ClientPacketListener.java:528-544}）对 {@code LivingEntity} 走的是
     * {@code LivingEntity#lerpTo}（{@code LivingEntity.java:2759-2766}）——
     * 它<b>只写插值目标</b>（{@code lerpX/lerpY/lerpZ/lerpSteps=3}），真正落位在
     * {@code LivingEntity#aiStep}（{@code LivingEntity.java:2505-2514}），
     * 而 {@code aiStep} 在冻结期间根本没有 tick ⇒ 猪停在原地。
     * 客户端的补法见 {@code ShanhaiClientTeleportClockMixin}（冻结时把 teleport 直接落位）。
     */
    @Override
    public int sendMove(int dim, long uuidMost, long uuidLeast) {
        final ServerLevel level = levelOf(dim);
        if (level == null) {
            ++this.motionSendFailed;
            return 0;
        }
        try {
            final Entity entity = level.getEntity(new java.util.UUID(uuidMost, uuidLeast));
            if (entity == null || entity.isRemoved()) {
                ++this.motionSendFailed;
                return 0;
            }
            ++this.motionSentMoves;
            final int players = broadcastEntityMove(level, entity);
            this.motionSentPackets += players;
            // ② 逐实体发包计数（"sent 涨了但客户端没收到"必须能按实体对账）
            final java.util.UUID uuid = entity.getUUID();
            long[] n = this.sentByEntity.get(uuid);
            if (n == null) {
                n = new long[1];
                this.sentByEntity.put(uuid, n);
                this.typeOfEntity.put(uuid, ShanhaiReplayEntityMotionRecorder.typeKeyOf(entity));
            }
            if (players > 0) {
                n[0] += players;
            } else {
                n[0] = n[0];   // 0 = 这一包一个玩家都没覆盖（记在 noPlayer 里）
                ++this.sentNoPlayer;
            }
            ShanhaiReplayEntityMotionRecorder.tally()
                    .sent(ShanhaiReplayEntityMotionRecorder.typeKeyOf(entity), players);
            return players;
        } catch (Throwable t) {
            ++this.motionSendFailed;
            return 0;
        }
    }

    /**
     * 判据 A3 的读数来源：这只实体<b>现在</b>的位置（{@code null} = 查不到）。
     *
     * <p>由 {@code ShanhaiReplayPacer} 在"重建过的实体写回位置之后"调一次，
     * 用来回答"重建有没有把位置覆盖回旧值 / 有没有别的路径把它拉回"。
     */
    @Override
    public float[] appliedPos(int dim, long uuidMost, long uuidLeast) {
        final ServerLevel level = levelOf(dim);
        if (level == null) {
            return null;
        }
        try {
            final Entity entity = level.getEntity(new java.util.UUID(uuidMost, uuidLeast));
            if (entity == null) {
                return null;
            }
            return new float[]{(float) entity.getX(), (float) entity.getY(), (float) entity.getZ()};
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 生物血量层

    /**
     * 把这只好生物摆回过去那个血量（用户口径：「倒流之后，生物血量没有恢复」）。
     *
     * <p>🔴 <b>玩家豁免</b>：记录侧就不记玩家（{@code skip_player}），这里再挡一次 ——
     * 血量的时间线里出现玩家会直接改到真人身上。
     */
    @Override
    public boolean heal(int dim, long uuidMost, long uuidLeast, float health) {
        final ServerLevel level = levelOf(dim);
        if (level == null) {
            ++this.healthFailed;
            return false;
        }
        try {
            final Entity entity = level.getEntity(new java.util.UUID(uuidMost, uuidLeast));
            if (!(entity instanceof net.minecraft.world.entity.LivingEntity living) || entity.isRemoved()) {
                ++this.healthFailed;
                return false;
            }
            if (living instanceof ServerPlayer || living instanceof net.minecraft.world.entity.player.Player) {
                ++this.healthFailed;
                return false;   // 玩家的时间不受影响
            }
            if (Float.isNaN(health) || health <= 0.0F) {
                // 🔴 ⑤ 死亡那一刻记下来的血量是 0 ⇒ 写回去 = 把它重新写死（用户：「我杀的生物还是没活过来」）。
                //    一律跳过并计数；同时强制救活（满血 + 清死亡计时）。
                ++this.healthZeroSkipped;
                forceAlive(living);
                ++this.healthApplied;
                return true;
            }
            ShanhaiClockHooks.beginReplayWrite();
            try {
                living.setHealth(health);
                living.deathTime = 0;
                living.hurtTime = 0;
                if (!this.revivedUuids.contains(living.getUUID())) {
                    this.revivedUuids.add(living.getUUID());
                }
                ++this.healthApplied;
                return true;
            } finally {
                ShanhaiClockHooks.endReplayWrite();
            }
        } catch (Throwable t) {
            ++this.healthFailed;
            return false;
        }
    }

    /**
     * 🔴 ⑤ 强制救活：血量记录是 0/负（死亡那一刻）时的唯一正确写法。
     *
     * <p>用户原话：「我杀的生物还是没活过来，还是死了」。根因是死亡边界记录到的血量就是 0，
     * 倒流把它写回去 = 又写死一次（并在下一拍冒白烟）。这里给满血并把死亡/受击计时清零。
     */
    private void forceAlive(net.minecraft.world.entity.LivingEntity living) {
        try {
            ShanhaiClockHooks.beginReplayWrite();
            try {
                living.setHealth(living.getMaxHealth());
                living.deathTime = 0;
                living.hurtTime = 0;
                living.invulnerableTime = 0;
                // 只"取消移除"：setRemoved(null) 等价于"不是被移除状态"
                // （unsetRemoved() 是 protected 的，普通类调不到）
                if (living.isRemoved()) {
                    living.setRemoved(null);
                }
                if (!this.revivedUuids.contains(living.getUUID())) {
                    this.revivedUuids.add(living.getUUID());
                }
            } finally {
                ShanhaiClockHooks.endReplayWrite();
            }
        } catch (Throwable t) {
            ++this.healthFailed;
        }
    }

    /** 血量记录是 0 而被跳过的条数（判据⑤）。 */
    public long healthZeroSkipped() {
        return this.healthZeroSkipped;
    }

    /**
     * ② 逐实体发包读数（top5）+ "一条都没收到"的实体数。
     *
     * <p>用户口径：收尾瞬移十几格 ⇒ 要先分清"发了不少包"与"客户端真收到了"。
     * {@code motion_sent=6400} 只说明我们<b>请求</b>了 6400 次；这里按 uuid 对账：
     * 谁的包最多、谁一个包都没有、有多少包一个玩家都没覆盖（{@code no_player}）。
     */
    public String sendTopLine() {
        final java.util.List<java.util.Map.Entry<java.util.UUID, long[]>> rows =
                new java.util.ArrayList<>(this.sentByEntity.entrySet());
        rows.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
        final StringBuilder sb = new StringBuilder("sent_by_entity top5=");
        final int n = Math.min(5, rows.size());
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(',');
            }
            final java.util.UUID u = rows.get(i).getKey();
            sb.append(this.typeOfEntity.getOrDefault(u, "?")).append('×').append(rows.get(i).getValue()[0]);
        }
        int zero = 0;
        for (java.util.Map.Entry<java.util.UUID, float[]> e : this.lastWritten.entrySet()) {
            final long[] got = this.sentByEntity.get(e.getKey());
            if (got == null || got[0] == 0) {
                ++zero;
            }
        }
        sb.append(" entities=").append(this.sentByEntity.size());
        sb.append(" sent_zero_entities=").append(zero);
        sb.append(" sent_no_player=").append(this.sentNoPlayer);
        return sb.toString();
    }

    /** 最后一次写入坐标的条数（收尾对齐的对照量）。 */
    public String lastWrittenLine() {
        return "last_written_entities=" + this.lastWritten.size()
                + " end_align_sent=" + this.endAlignSent
                + " end_align_max=" + String.format(java.util.Locale.ROOT, "%.3f", this.endAlignMax);
    }

    /** 收尾时还被救活的实体里"活着的"个数 / 总数（{@code entity_alive_after=}）。 */
    public String aliveAfterLine() {
        int alive = 0;
        int total = 0;
        for (java.util.UUID uuid : this.revivedUuids) {
            try {
                final Entity e = entityOf(uuid);
                if (e == null) {
                    continue;
                }
                ++total;
                if (e instanceof net.minecraft.world.entity.LivingEntity living
                        && !living.isDeadOrDying() && living.getHealth() > 0.0F) {
                    ++alive;
                }
            } catch (Throwable ignored) {
                // 单只查不了不算进分母
            }
        }
        return "entity_alive_after=" + alive + "/" + total;
    }

    /** 按 uuid 在所有已登记维度里找实体（诊断用）。 */
    private Entity entityOf(java.util.UUID uuid) {
        try {
            for (int i = 0; i < DIM_KEYS.size(); i++) {
                final ServerLevel level = levelOf(i);
                if (level == null) {
                    continue;
                }
                final Entity e = level.getEntity(uuid);
                if (e != null) {
                    return e;
                }
            }
        } catch (Throwable ignored) {
            // 找不到就是 null
        }
        return null;
    }

    // ------------------------------------------------------------------ 实体层

    @Override
    public boolean remove(int dim, long uuidMost, long uuidLeast) {
        final ServerLevel level = levelOf(dim);
        final boolean ok = ShanhaiReplayEntityRecorder.remove(level, uuidMost, uuidLeast);
        if (!ok) {
            ++this.entityRemoveFailed;
        }
        return ok;
    }

    @Override
    public boolean rebuild(int dim, int payload) {
        final ServerLevel level = levelOf(dim);
        final boolean ok = ShanhaiReplayEntityRecorder.rebuild(level, payload);
        if (!ok) {
            ++this.entityRebuildFailed;
            return false;
        }
        // 🔴 根因落地（用户"收尾瞬移十几格"）：重建 = 新 server id，而 spawn 包只在被跳过的
        //    chunk tracking 里发 ⇒ 客户端没有这个 id ⇒ 我们的 teleport 被 handleTeleportEntity 丢弃。
        //    这里立刻直发 add（真实新 id）→ 紧接着 teleport（坐标 = 最后写入坐标）。
        final Entity rebuilt = ShanhaiReplayEntityRecorder.lastRebuilt();
        if (rebuilt != null) {
            sendSpawnAndPos(level, rebuilt);
        }
        return true;
    }

    /**
     * 冻结期间重建实体后<b>直发 spawn + 位置</b>（顺序固定：add → teleport）。
     *
     * <p>读数：{@code spawn_sent=}（真发出去了几次）/ {@code spawn_no_player=}（范围内没人）/
     * {@code spawn_failed=}（构造或发送抛异常）—— <b>"发了一次"不等于"客户端收到了"</b>。
     */
    private void sendSpawnAndPos(ServerLevel level, Entity entity) {
        if (level == null || entity == null) {
            return;
        }
        final java.util.UUID uuid = entity.getUUID();
        final int id = entity.getId();
        final Integer known = this.knownIds.put(uuid, Integer.valueOf(id));
        if (known != null && known.intValue() != id) {
            ++this.entityIdRemap;
            GTDishanhaiMod.LOGGER.info("[SHANHAI-REPLAY] entity_id_remap uuid={} type={} id={}→{}",
                    uuid, ShanhaiReplayEntityMotionRecorder.typeKeyOf(entity), known, id);
        }
        try {
            // 🔴 头号根因（javap 已核）：`ClientboundAddEntityPacket(Entity)` 的 `data` 恒为 0，
            //    而 `FallingBlockEntity#recreateFromPacket` 用 `packet.getData()` 反查方块状态
            //    ⇒ 0 = 空气 ⇒ 客户端渲染器画不出沙子（TNT 不读 data，所以照常可见）。
            int data = 0;
            if (entity instanceof net.minecraft.world.entity.item.FallingBlockEntity fb) {
                data = net.minecraft.world.level.block.Block.getId(fb.getBlockState());
                if (data <= 0) {
                    ++this.spawnDataMissing;
                }
            }
            final String typeKey = ShanhaiReplayEntityMotionRecorder.typeKeyOf(entity);
            long[] byType = this.spawnByType.get(typeKey);
            if (byType == null) {
                byType = new long[1];
                this.spawnByType.put(typeKey, byType);
            }
            ++byType[0];
            final net.minecraft.network.protocol.game.ClientboundAddEntityPacket spawn =
                    new net.minecraft.network.protocol.game.ClientboundAddEntityPacket(entity, data);
            final java.util.List<ServerPlayer> players = playersNear(level, entity.blockPosition());
            int sent = 0;
            for (ServerPlayer player : players) {
                try {
                    player.connection.send(spawn);
                    ++sent;
                } catch (Throwable t) {
                    ++this.spawnFailed;
                }
            }
            if (sent == 0) {
                ++this.spawnNoPlayer;
                return;
            }
            this.spawnSent += sent;
            final float[] last = this.lastWritten.get(uuid);
            if (last != null) {
                entity.moveTo(last[0], last[1], last[2]);
                entity.setOldPosAndRot();
            }
            broadcastEntityMove(level, entity);
        } catch (Throwable t) {
            ++this.spawnFailed;
        }
    }

    /**
     * stop 收尾：对所有"动过的实体"各补发一次 teleport（坐标 = {@link #lastWritten}）。
     *
     * <p>🔴 绝不再向"服务端当前坐标"对齐（那条路会把实体拉回去 = 用户看到的瞬移）。
     * 读数：{@code end_align_sent=} / {@code end_align_max=}（当前坐标与最后写入坐标的最大偏差）。
     * 新增对账读数（2026-11-15）：{@code final_pos_check}（偏差最大的前 8 条逐实体明细）/
     * {@code final_pos_summary n= mismatch= max= absent=}（参与条数 / 偏差 &gt; 0.05 的条数 /
     * 最大偏差 / 找不到实体的条数）。
     */
    public void flushFinalPositions() {
        // 🔴 ③ 只加对账（本方法本来只在 stop 调一次 ⇒ 不需要额外限流）。
        //    写入顺序、moveTo / setOldPosAndRot / broadcastEntityMove 一个字节没动。
        int posN = 0;          // 找到实体并写过的条数（对账分母）
        int posAbsent = 0;     // 实体找不到 / 已移除 / 抛异常 ⇒ 没进对账
        int posMismatch = 0;   // d > 0.05 的条数
        double posMaxD = 0.0D;
        // uuid → {id, last_x, last_y, last_z, actual_x, actual_y, actual_z, d}（局部临时表，不是字段）
        final java.util.Map<java.util.UUID, double[]> posChecked = new java.util.HashMap<>();
        for (java.util.Map.Entry<java.util.UUID, float[]> e : this.lastWritten.entrySet()) {
            try {
                final Entity entity = entityOf(e.getKey());
                if (entity == null || entity.isRemoved()) {
                    ++posAbsent;
                    continue;
                }
                final float[] p = e.getValue();
                final double dx = entity.getX() - p[0];
                final double dy = entity.getY() - p[1];
                final double dz = entity.getZ() - p[2];
                final double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (d > this.endAlignMax) {
                    this.endAlignMax = d;
                }
                // ③ 先把"搬回之前"的真实坐标留档：这才是"最后一帧到底有没有落到实体身上"的读数。
                //    （moveTo 之后再回读必定恒为 0 —— float→double 往返无损 —— 那个数永远绿、无诊断价值。）
                final double actualX = entity.getX();
                final double actualY = entity.getY();
                final double actualZ = entity.getZ();
                entity.moveTo(p[0], p[1], p[2]);
                entity.setOldPosAndRot();
                if (entity.level() instanceof ServerLevel level) {
                    final int players = broadcastEntityMove(level, entity);
                    if (players > 0) {
                        ++this.endAlignSent;
                    }
                }
                ++posN;
                if (d > 0.05D) {
                    ++posMismatch;
                }
                if (d > posMaxD) {
                    posMaxD = d;
                }
                posChecked.put(e.getKey(), new double[]{entity.getId(),
                        p[0], p[1], p[2], actualX, actualY, actualZ, d});
                // 回读兜底：搬回之后若还差（正常必须为 0）⇒ 有东西又把实体拉走了，只在异常时出这一行。
                final double bx = entity.getX() - p[0];
                final double by = entity.getY() - p[1];
                final double bz = entity.getZ() - p[2];
                final double dBack = Math.sqrt(bx * bx + by * by + bz * bz);
                if (dBack > 1.0E-6D) {
                    GTDishanhaiMod.LOGGER.warn("[SHANHAI-REPLAY] final_pos_readback uuid={} id={} d={}",
                            e.getKey(), entity.getId(),
                            String.format(java.util.Locale.ROOT, "%.3f", dBack));
                }
            } catch (Throwable ignored) {
                // 单只对不齐不影响收尾；已经记进 posChecked 的不算缺席（n 里已经算过）
                if (!posChecked.containsKey(e.getKey())) {
                    ++posAbsent;
                }
            }
        }
        // 前 8 条按 d 从大到小（d 是"搬回之前"的偏差 = 最后一帧没落到实体身上的量）
        final java.util.List<java.util.Map.Entry<java.util.UUID, double[]>> ranked =
                new java.util.ArrayList<>(posChecked.entrySet());
        ranked.sort((x, y) -> Double.compare(y.getValue()[7], x.getValue()[7]));
        final int dump = Math.min(8, ranked.size());
        for (int i = 0; i < dump; i++) {
            final java.util.UUID uuid = ranked.get(i).getKey();
            final double[] v = ranked.get(i).getValue();
            GTDishanhaiMod.LOGGER.info(
                    "[SHANHAI-REPLAY] final_pos_check uuid={} id={} last={} actual={} d={}",
                    uuid, (int) v[0],
                    String.format(java.util.Locale.ROOT, "%.3f/%.3f/%.3f", v[1], v[2], v[3]),
                    String.format(java.util.Locale.ROOT, "%.3f/%.3f/%.3f", v[4], v[5], v[6]),
                    String.format(java.util.Locale.ROOT, "%.3f", v[7]));
        }
        // 汇总：n = 参与对账条数（n + absent = lastWritten 总数）；mismatch = d > 0.05 条数
        GTDishanhaiMod.LOGGER.info(
                "[SHANHAI-REPLAY] final_pos_summary n={} mismatch={} max={} absent={}",
                posN, posMismatch, String.format(java.util.Locale.ROOT, "%.3f", posMaxD), posAbsent);
    }

    // ------------------------------------------------------------------ 进度层

    @Override
    public ShanhaiReplayProgressJournal.Outcome write(int dim, long pos, int progress) {
        final ShanhaiReplayProgressJournal.Outcome outcome =
                ShanhaiReplayProgressRecorder.write(levelOf(dim), pos, progress);
        switch (outcome) {
            case WRITTEN -> ++this.progressWritten;
            case UNSUPPORTED -> ++this.progressUnsupported;
            default -> {
                // FAILED 由记录器自己计数
            }
        }
        return outcome;
    }

    // ------------------------------------------------------------------ 方块实体差分

    @Override
    public boolean apply(int dim, long pos, int payload) {
        final ServerLevel level = levelOf(dim);
        if (level == null) {
            ++this.noLevel;
            ++this.beDiffFailed;
            return false;
        }
        boolean ok;
        synchronized (ShanhaiReplayBlockEntityRecorder.class) {
            ok = ShanhaiReplayBlockEntityRecorder.applyDiff(level, pos, payload);
        }
        if (ok) {
            ++this.beDiffWritten;
        } else {
            ++this.beDiffFailed;
            ++this.noBe;
        }
        return ok;
    }

    // ------------------------------------------------------------------ 标量层

    @Override
    public ShanhaiReplayScalarJournal.Outcome write(int dim, int keyId, String value) {
        final ShanhaiReplayScalarJournal.Outcome outcome =
                ShanhaiReplayScalarRecorder.write(this.server, dim, keyId, value);
        switch (outcome) {
            case WRITTEN -> ++this.scalarWritten;
            case UNSUPPORTED -> ++this.scalarUnsupported;
            default -> {
                // FAILED 由记录器自己计数
            }
        }
        return outcome;
    }

    /** 失败原因计数（服务收尾时打进 {@code done} 行）。 */
    public String failureSummary() {
        // 🔴 2026-11-14 D：这里**不许**再出现 `motion=` / `health=`（done 行里已经各有一份
        //    `motion=<记录>/<应用>`、`health=<记录>/<应用>`；同名异值会让整行失去可判读性）。
        //    引擎自己的"写成功/写失败"一律前缀 `engine_`。
        return "noLevel=" + this.noLevel + " noChunk=" + this.noChunk + " noState=" + this.noState
                + " noBe=" + this.noBe + " alreadyAir=" + this.alreadyAir
                + " targetUnchanged=" + this.targetUnchanged
                + " entityFail=" + (this.entityRemoveFailed + this.entityRebuildFailed)
                + " engine_motion=" + this.motionWritten + "/" + this.motionFailed
                + " motion_uuid_missing=" + this.motionUuidMissing
                + " uuid_absent=" + this.motionUuidAbsent + " dim_mismatch=" + this.motionDimMismatch
                + " already_removed=" + this.motionAlreadyRemoved
                + " motion_send_failed=" + this.motionSendFailed
                + " engine_health=" + this.healthApplied + "/" + this.healthFailed
                + " progress=" + this.progressWritten + "/" + this.progressUnsupported
                + " beDiff=" + this.beDiffWritten + "/" + this.beDiffFailed
                + " scalar=" + this.scalarWritten + "/" + this.scalarUnsupported
                + "/skip" + this.scalarSkipped
                + " " + this.lastParticleLine;
    }

    /** 首几条方块明细的条数（{@code done} 行用它证明"明细真的打出来了"）。 */
    public int blockSampleCount() {
        return this.blockSamples.size();
    }

    public long alreadyAir() {
        return this.alreadyAir;
    }

    public long motionWritten() {
        return this.motionWritten;
    }

    public long motionFailed() {
        return this.motionFailed;
    }

    /** 发包请求次数（{@code motion_sent=}）。 */
    public long motionSentMoves() {
        return this.motionSentMoves;
    }

    /** 发包覆盖玩家数之和（{@code motion_packets=}）。 */
    public long motionSentPackets() {
        return this.motionSentPackets;
    }

    public long healthApplied() {
        return this.healthApplied;
    }

    public long healthFailed() {
        return this.healthFailed;
    }

    /** 粒子判据文本（{@code particles=发出数/应发数 sampled=…}，<b>累计</b>口径）。 */
    public String particleLine() {
        return "particles=" + this.particlesSent + "/" + this.particlesWanted
                + " sampled=" + this.lastParticleLine.contains("sampled=true")
                + " particle_lead=1 emitted_ahead=" + this.emittedAhead;
    }

    /** 本次倒流累计发出粒子的格数（{@code particles=} 的分子）。 */
    public long particlesSent() {
        return this.particlesSent;
    }

    /** 本次倒流累计"应发"的格数（{@code particles=} 的分母）。 */
    public long particlesWanted() {
        return this.particlesWanted;
    }

    /** 关掉粒子（只给负对照/自检用；默认 = {@link ShanhaiReplayParticleBudget#MAX_PER_STEP}）。 */
    public void setParticleBudget(int budget) {
        this.particleBudget = Math.max(0, budget);
    }

    // ------------------------------------------------------------------ 收尾自查

    /**
     * <b>回放结束抽样自查</b>：抽 32 个"本窗口内被改过的 pos"，读回 blockstate 与
     * "锚点那一刻应当是什么"（{@link #expectedState}）比对。
     *
     * <p>🔴 这是"方块到底有没有回来"的唯一硬判据 —— 计数全对但方块没回来，是本次真机实测的形态。
     * 抽样是<b>确定性</b>的（按 pos 排序后等距取 32 个），同一个窗口重跑结果一致。
     *
     * @return {@code "checked=32 mismatch=0"} 形式的读数（{@code checked=0} = 这一轮没有可自查的）
     */
    public String verifyBlocks() {
        if (this.expectedState.isEmpty()) {
            return "checked=0 mismatch=0";
        }
        final Long[] keys = this.expectedState.keySet().toArray(new Long[0]);
        java.util.Arrays.sort(keys);
        final int n = Math.min(32, keys.length);
        int checked = 0;
        int mismatch = 0;
        final StringBuilder bad = new StringBuilder();
        for (int i = 0; i < n; i++) {
            final int pick = (int) ((long) i * keys.length / n);   // 等距抽样（确定性）
            final long pos = keys[Math.min(pick, keys.length - 1)].longValue();
            final Integer expect = this.expectedState.get(Long.valueOf(pos));
            if (expect == null) {
                continue;
            }
            ++checked;
            final int actual = stateIndexAt(0, pos);
            if (actual != expect.intValue()) {
                ++mismatch;
                if (bad.length() < 200) {
                    bad.append(Long.toHexString(pos)).append(':')
                            .append(expect.intValue()).append("->").append(actual).append(' ');
                }
            }
        }
        return "checked=" + checked + " mismatch=" + mismatch
                + (mismatch == 0 ? "" : " bad=[" + bad.toString().trim() + "]");
    }
}