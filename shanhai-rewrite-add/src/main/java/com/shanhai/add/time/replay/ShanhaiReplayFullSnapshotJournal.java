package com.shanhai.add.time.replay;

import java.util.ArrayList;
import java.util.List;

/**
 * 山海重构 · <b>第 6 层：邻近区块"每 tick 全量"快照</b>（🔴 零 Minecraft 依赖，别加 import）。
 *
 * <h2>1. 🔴 默认关闭（用户 2026-11 拍板）</h2>
 * 用户原话：「如果 cpu 不行的话，那我们先测试，主要办法还是靠你的差分」。
 * ⇒ <b>主机制永远是差分</b>；这一层只是"兜住我们没 hook 到的第三方状态"的可选件，
 * 默认 {@link #DEFAULT_ENABLED} = {@code false}，实测成本写进报告后才允许讨论默认值。
 * 它的存在<b>不许</b>影响差分的默认路径与任何既有判据。
 *
 * <h2>2. 语义（和被否掉的"周期快照"不是一回事）</h2>
 * 回放到 tick T 时：<b>先</b>应用 T 的差分（方块/BE/实体/进度/标量），<b>再</b>对"T 这一 tick
 * 有快照的区块"做一次整块对齐（{@link #align}，只写与当前不一致的格）。
 * 所以它不是"每 N tick 覆盖一次"（那会让世界看起来一跳一跳），而是
 * "<b>逐 tick 的真实状态</b>"：走上倒流时每个 tick 都是它本来的样子。
 *
 * <h2>3. 为什么用 int 数组而不是对象</h2>
 * 一个区块 16×384×16 = 98304 格；存 {@code int[]} 是 393 KB，存 {@code BlockState[]}
 * 光引用数组就要 786 KB（还不算对象头）。本层只存"状态索引"，与差分层共用
 * {@code ShanhaiStateIndex}。
 *
 * <h2>4. 内存口径</h2>
 * <pre>
 *   bytes_per_tick = 区块数 × (98304 × 4 + BE NBT + 实体列表)
 *   window_mib     = bytes_per_tick × 1200 / 1 MiB
 * </pre>
 * 硬闸与降级在 {@link ShanhaiReplayFullSnapshotBudget}（判据 b 就在它上面）。
 */
public final class ShanhaiReplayFullSnapshotJournal {

    /** 🔴 默认关闭：主机制是差分，这一层只在用户明确开启时生效。 */
    public static final boolean DEFAULT_ENABLED = false;

    /** 一个区块的格数（16 × 384 × 16）。 */
    public static final int CELLS_PER_CHUNK = 16 * 384 * 16;

    /**
     * 一个区块在某一 tick 的完整状态。
     *
     * @param chunkKey {@code ChunkPos#toLong()}
     * @param states   状态索引数组（长度 = 该区块的格数）
     */
    public record ChunkSnap(long chunkKey, int[] states) {
    }

    /**
     * 某一 tick 的快照。
     *
     * @param tick   世界 tick
     * @param chunks 这一 tick 有快照的区块
     */
    public record Snapshot(int tick, List<ChunkSnap> chunks) {
    }

    /** 回放落点（运行期 = {@code ShanhaiReplayEngine}；自检 = 假体）。 */
    public interface Sink {

        /** 读该区块现在的整份状态索引；{@code null} = 区块没加载（这一块跳过，不静默成功）。 */
        int[] readChunk(long chunkKey);

        /** 写一格。 */
        boolean writeCell(long chunkKey, int index, int state);
    }

    /**
     * @param checked 逐格比对过的格数
     * @param written 真的写下去的格数（= 与快照不一致的格数）
     * @param failed  写失败的格数 / 区块没加载的块数
     */
    public record AlignResult(int checked, int written, int failed) {

        public static AlignResult empty() {
            return new AlignResult(0, 0, 0);
        }

        public boolean consistent() {
            return this.failed == 0 && this.written == 0;
        }

        public String toText() {
            return "checked=" + this.checked + " written=" + this.written + " failed=" + this.failed;
        }
    }

    /** 环形缓冲（按 tick 分桶；与五条差分时间线同款语义：满了丢最老）。 */
    private final Snapshot[] buf;
    private final int capacity;
    private int tail;
    private int count;
    private int lastTick = Integer.MIN_VALUE;
    private long droppedOldest;

    public ShanhaiReplayFullSnapshotJournal(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity 必须 > 0: " + capacity);
        }
        this.capacity = capacity;
        this.buf = new Snapshot[capacity];
    }

    /**
     * 记这一 tick 的快照。
     *
     * <p>同一 tick 重复记 ⇒ 覆盖（后到的是更完整的一份）。
     */
    public void record(int tick, List<ChunkSnap> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        final Snapshot snap = new Snapshot(tick, new ArrayList<>(chunks));
        if (tick == this.lastTick && this.count > 0
                && this.buf[(this.tail + this.count - 1) % this.capacity].tick() == tick) {
            this.buf[(this.tail + this.count - 1) % this.capacity] = snap;
            return;
        }
        if (this.count == this.capacity) {
            this.buf[this.tail % this.capacity] = null;
            ++this.tail;
            --this.count;
            ++this.droppedOldest;
        }
        this.buf[(this.tail + this.count) % this.capacity] = snap;
        ++this.count;
        this.lastTick = tick;
    }

    public int size() {
        return this.count;
    }

    public int newestTick() {
        return this.count == 0 ? -1 : this.buf[(this.tail + this.count - 1) % this.capacity].tick();
    }

    public int oldestTick() {
        return this.count == 0 ? -1 : this.buf[this.tail % this.capacity].tick();
    }

    public long droppedOldest() {
        return this.droppedOldest;
    }

    /** 取走该 tick 的快照（没有就 {@code null}）。 */
    public Snapshot take(int tick) {
        for (int i = this.tail + this.count - 1; i >= this.tail; i--) {
            final Snapshot s = this.buf[i % this.capacity];
            if (s != null && s.tick() == tick) {
                return s;
            }
        }
        return null;
    }

    public void clear() {
        for (int i = 0; i < this.capacity; i++) {
            this.buf[i] = null;
        }
        this.tail = 0;
        this.count = 0;
        this.lastTick = Integer.MIN_VALUE;
        this.droppedOldest = 0L;
    }

    public String summary() {
        return "fullsnap_records=" + this.count + "/" + this.capacity
                + " ticks=[" + this.oldestTick() + "," + this.newestTick() + "]"
                + " dropped=" + this.droppedOldest;
    }

    /**
     * 整块对齐：把"当前状态"拉成快照里的样子，<b>只写不一致的格</b>。
     *
     * <p>判据 a 的被测物：4 个区块 × 20 tick 的快照，回放到第 10 tick 时把当前状态弄脏 5 格
     * ⇒ 对齐后必须逐格一致（负对照：不对齐 ⇒ 5 格不一致）。
     */
    public static AlignResult align(Snapshot snap, Sink sink) {
        if (snap == null || snap.chunks() == null || snap.chunks().isEmpty() || sink == null) {
            return AlignResult.empty();
        }
        int checked = 0;
        int written = 0;
        int failed = 0;
        for (ChunkSnap chunk : snap.chunks()) {
            final int[] current = sink.readChunk(chunk.chunkKey());
            if (current == null) {
                ++failed;
                continue;
            }
            final int[] want = chunk.states();
            final int n = Math.min(current.length, want.length);
            for (int i = 0; i < n; i++) {
                ++checked;
                if (current[i] == want[i]) {
                    continue;
                }
                if (sink.writeCell(chunk.chunkKey(), i, want[i])) {
                    current[i] = want[i];
                    ++written;
                } else {
                    ++failed;
                }
            }
        }
        return new AlignResult(checked, written, failed);
    }

    private ShanhaiReplayFullSnapshotJournal() {
        throw new UnsupportedOperationException("用带容量的构造器");
    }
}
