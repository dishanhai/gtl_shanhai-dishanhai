package com.shanhai.add.time.replay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 山海重构 · <b>"客户端到底靠什么看见"的同步账本</b>（🔴 零 Minecraft 依赖，别加 import）。
 *
 * <h2>1. 🔴 根因（2026-11-14 真机实测，用户原话）</h2>
 * <blockquote>「方块是真的恢复了，但是需要我在那个地方重新放一个方块才会显示」</blockquote>
 * 服务端读回全对（{@code block_verify checked=32 mismatch=0}），客户端却一个包都没收到。
 * 证据链（1.20.1 mapped 源码，逐行）：
 * <pre>
 *   Level#setBlock            :219  LevelChunk#setBlockState(...)          ← 服务端状态确实改了
 *   Level#markAndNotifyBlock  :246  if ((flags &amp; 2) != 0 &amp;&amp; chunk.getFullStatus() &gt;= BLOCK_TICKING)
 *                             :247     ServerLevel#sendBlockUpdated(...)   ← 这里【只是标脏，不发包】
 *   ServerLevel#sendBlockUpdated :948   getChunkSource().blockChanged(pos)
 *   ServerChunkCache#blockChanged :415  ChunkHolder#blockChanged(pos)
 *   ChunkHolder#blockChanged  :150  getTickingChunk() 非空 ⇒ changedBlocksPerSection[sec].add(rel)
 *                                   ↑ 只往 BitSet 里塞一个位置
 *   ServerChunkCache#tickChunks :372  profilerfiller.popPush("broadcast")
 *                               :374     list.forEach(h -&gt; h.holder.broadcastChanges(h.chunk))
 *                                   ↑ ↑ ↑ 【全 jar 唯一】把 BitSet 变成
 *                                         ClientboundSectionBlocksUpdatePacket 的地方
 * </pre>
 * 而 {@code tickChunks()} 只在 {@code ServerChunkCache#tick(haveTime, <b>true</b>)} 时跑
 * （:311-313）；冻结路径刻意传 {@code false}（{@code ShanhaiClockHooks#tickChunkSourceOnly}）——
 * 那一行原本是为了"不随机 tick / 不刷怪"，<b>顺带把客户端同步整段掐掉了</b>。
 *
 * <h2>2. 于是本版怎么让客户端看见（显式、可数、可判）</h2>
 * 不依赖那条被掐掉的链路，改为<b>我们自己把 {@code ClientboundBlockUpdatePacket} 直发到
 * {@code ServerPlayer#connection}</b>（与原版同一种包；客户端落点见
 * {@code ClientPacketListener#handleBlockUpdate:761} → {@code ClientLevel#setServerVerifiedBlockState:133}
 * → {@code ClientLevel#sendBlockUpdated:555} → {@code LevelRenderer#blockChanged}：标脏区块段 +
 * 该段光照，下一帧重建网格）。
 *
 * <h2>3. 为什么把"要不要发 / 发给了几个人"这笔账单独放一个纯类</h2>
 * 判据要能<b>离线</b>自证：{@code client_sync targeted=N sent=M} 里 M 必须与 N 同量级，
 * 并且要有<b>负对照</b>——把 flag 改回"不通知客户端"的写法 ⇒ {@code sent=0}（判据红）。
 * 只要发送决策经过这里，就能在没有游戏、没有玩家的自检里用替身把这两条断言跑出来
 * （{@code temp\replay-clientsync-selfcheck}）。
 *
 * <h2>4. 口径</h2>
 * <table border="1">
 *   <caption>三个计数</caption>
 *   <tr><th>字段</th><th>含义</th><th>期望</th></tr>
 *   <tr><td>{@code targeted}</td><td>本窗口内<b>真的发出去包</b>的格数（按"这一格本步最终态一次"去重）</td>
 *       <td>与 {@code step=} 行里的 {@code blocks=} 同量级</td></tr>
 *   <tr><td>{@code sent}</td><td>实际发出的包数 = 格数 × 这个格里能收到包的玩家数</td>
 *       <td>≥ {@code targeted}（单机 = 相等）</td></tr>
 *   <tr><td>{@code noFlag}</td><td>flag 里没有 {@code UPDATE_CLIENTS} ⇒ <b>按原版语义不该通知客户端</b>的格数</td>
 *       <td>正常恒为 0；负对照时 &gt; 0 且 {@code sent=0}</td></tr>
 * </table>
 */
public final class ShanhaiReplayClientSync {

    /**
     * {@code Level#setBlock} 的 {@code UPDATE_CLIENTS} 位。
     *
     * <p>1.20.1 {@code Level#markAndNotifyBlock:246} 判的就是 {@code (flags & 2) != 0}；
     * 值必须与 {@code ShanhaiReplayApplier#FLAG_TARGET}（= 2）一致 —— 自检里断的就是这条一致性。
     */
    public static final int FLAG_CLIENTS = 2;

    /** 本步要发的一格（{@code state} 是 {@code BlockState}，这里用 {@code Object} 保持本类零 MC 依赖）。 */
    public record Entry(int dim, long pos, Object state) {
    }

    /** 维度 → （pos → 待发条目）：同格后写覆盖前写（本步最终态只发一次，避免逐格包重复）。 */
    private final Map<Integer, LinkedHashMap<Long, Entry>> pending = new LinkedHashMap<>();

    private long targeted;
    private long sent;
    private long noFlag;
    private long noPlayer;
    private long sendFailed;
    private long motionTargeted;
    private long motionSent;

    private long stepTargeted;
    private long stepSent;
    private int lastPlayers;

    /** 这条 {@code flags} 含不含 {@code UPDATE_CLIENTS}（= 原版会不会通知客户端）。 */
    public static boolean wantsClient(int flags) {
        return (flags & FLAG_CLIENTS) != 0;
    }

    /**
     * 记一笔"这一格本步改了、要发给客户端"。
     *
     * @param flags {@code Level#setBlock} 用的 flag（决策只看 {@code flags & 2}）
     * @return {@code true} = 收下了（本步结束时会被发出去）
     */
    public boolean mark(int dim, long pos, int flags, Object state) {
        if (!wantsClient(flags)) {
            ++this.noFlag;      // 🔴 不通知客户端 ⇒ 客户端看不到，判据必须在这里留痕（不许静默）
            return false;
        }
        if (state == null) {
            return false;
        }
        LinkedHashMap<Long, Entry> perDim = this.pending.get(Integer.valueOf(dim));
        if (perDim == null) {
            perDim = new LinkedHashMap<>();
            this.pending.put(Integer.valueOf(dim), perDim);
        }
        perDim.put(Long.valueOf(pos), new Entry(dim, pos, state));
        return true;
    }

    /** 取走本步全部待发条目（调用方负责真发包；取完即清，绝不重复发）。 */
    public List<Entry> takePending() {
        if (this.pending.isEmpty()) {
            return List.of();
        }
        final List<Entry> out = new ArrayList<>();
        for (Map.Entry<Integer, LinkedHashMap<Long, Entry>> perDim : this.pending.entrySet()) {
            out.addAll(perDim.getValue().values());
        }
        this.pending.clear();
        return out;
    }

    /** 一格真的发出去了：{@code players} = 收到这个包的玩家数（0 = 这个格没有可发的玩家）。 */
    public void countFlushed(int players) {
        final int n = Math.max(0, players);
        ++this.targeted;
        this.sent += n;
        if (n == 0) {
            ++this.noPlayer;   // 🔴 取证：targeted 涨而 sent 不涨的唯一正常原因
        }
        ++this.stepTargeted;
        this.stepSent += n;
        this.lastPlayers = n;
    }

    /** 取证：发包时抛异常（掉线/队列满）的次数 —— 与"范围外不发"必须分开。 */
    public void countSendFailed() {
        ++this.sendFailed;
    }

    /** 一个实体位置写回真的发出去了（第 2 层：实体运动，同一笔账里数）。 */
    public void countMotion(int players) {
        final int n = Math.max(0, players);
        ++this.motionTargeted;
        this.motionSent += n;
        this.lastPlayers = n;
    }

    /**
     * 一行判据（本步 + 累计）。
     *
     * <p>字段顺序稳定，便于 grep {@code client_sync}：
     * {@code client_sync targeted=… sent=… step_targeted=… step_sent=… players=… no_flag=… motion=…/…}
     */
    public String line() {
        return "client_sync targeted=" + this.targeted + " sent=" + this.sent
                + " step_targeted=" + this.stepTargeted + " step_sent=" + this.stepSent
                + " players=" + this.lastPlayers + " no_flag=" + this.noFlag
                + " no_player=" + this.noPlayer + " send_failed=" + this.sendFailed
                + " motion=" + this.motionTargeted + "/" + this.motionSent;
    }

    /** 本步计数清零（每步结束时由调用方在打完日志之后调）。 */
    public void resetStep() {
        this.stepTargeted = 0L;
        this.stepSent = 0L;
    }

    /** 换一次倒流时整体清零（累计值不许跨倒流）。 */
    public void reset() {
        this.pending.clear();
        this.targeted = 0L;
        this.sent = 0L;
        this.noFlag = 0L;
        this.motionTargeted = 0L;
        this.motionSent = 0L;
        this.stepTargeted = 0L;
        this.stepSent = 0L;
        this.lastPlayers = 0;
    }

    public long targeted() {
        return this.targeted;
    }

    public long sent() {
        return this.sent;
    }

    public long noFlag() {
        return this.noFlag;
    }

    public long motionTargeted() {
        return this.motionTargeted;
    }

    public long motionSent() {
        return this.motionSent;
    }
}
