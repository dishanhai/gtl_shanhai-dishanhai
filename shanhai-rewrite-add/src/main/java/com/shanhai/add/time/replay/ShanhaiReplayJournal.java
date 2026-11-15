package com.shanhai.add.time.replay;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 山海重构 · <b>时间倒流的差分日志（环形缓冲）</b>（🔴 零 Minecraft 依赖，别加 import）。
 *
 * <h2>1. 记什么</h2>
 * 每条 = {@code (tick, dim, pos, fromState, afterState, payload)}：
 * <ul>
 *   <li>{@code tick} —— 世界 {@code gameTime}（<b>不是</b>自增序号：倒流要按真实 tick 对齐）；</li>
 *   <li>{@code dim} —— 维度小整数（映射表在 MC 侧的 {@code ShanhaiReplayEngine}，本类不认它）；</li>
 *   <li>{@code fromState}/{@code afterState} —— 我方状态索引（{@code ShanhaiStateIndex} 给），
 *       <b>不是</b> {@code BlockState} 对象 ⇒ 本类才能离线跑；</li>
 *   <li>{@code payload} —— 不透明的"方块实体 NBT 槽号"，{@link #NO_PAYLOAD} 表示没有；
 *       真正的 {@code CompoundTag} 在 MC 侧（{@code ShanhaiReplayPayloads}），本类只存数字。</li>
 * </ul>
 *
 * <h2>2. 三条被离线断言钉住的语义（🔴 改它们必须同步改 {@code ReplaySelfCheck}）</h2>
 * <ol>
 *   <li><b>同 tick 同 pos 合并成一条</b>：保留最早的 {@code fromState}、取最新的 {@code afterState}，
 *       {@code payload} 沿用最早那一条（它才是"回退后那个态"的方块实体）。
 *       为什么必须合并：GT 机器会在<b>同一 tick 反复写回同一个方块</b>（A==B 都出现过），
 *       不合并的话 1 分钟能记出 12 万条以上（设计 §2.1 的量级表）。</li>
 *   <li><b>LIFO</b>：{@link #takeNewestTick()} 返回的第一个元素必须是<b>最后记的那一条</b>
 *       （后改的先回）。改成正序（FIFO）会让"同一个格子被连改两次"回退成中间态
 *       ⇒ 自检里那条负对照就是干这个的。</li>
 *   <li><b>满了丢最老</b>（{@link #droppedOldest()}）：窗口自动变短，但"永远能倒"。
 *       改成"丢最新"会让最近几十秒的改动全部丢失（自检第二条负对照）。</li>
 * </ol>
 *
 * <h2>3. 为什么是"对象条目"而不是并行数组</h2>
 * 设计 §3 给了两个选型（并行数组 12–16 B/条 vs 对象 ~40 B/条）。P1 选<b>对象条目</b>：
 * 合并/裁剪/断言都直白得多，代价是满载 200k 条时 ~8 MB（引用数组本身 0.8 MB 常驻）。
 * 典型基地 1 分钟窗口只有 k 条量级，远到不了上限。这是<b>有意的偏差</b>，记在交付报告里。
 */
public final class ShanhaiReplayJournal {

    /** "这一笔没有方块实体载荷"（与 {@link ShanhaiReplayConst#NO_PAYLOAD} 同值）。 */
    public static final int NO_PAYLOAD = ShanhaiReplayConst.NO_PAYLOAD;

    /**
     * 一笔方块差分。
     *
     * <p>⚠️ {@code tick} 是 {@code int}（与设计 §3 一致）：它只在"当前 1200 tick 窗口内的排序/相等"里用，
     * {@code gameTime} 超过 {@code Integer.MAX_VALUE}（约 3.4 年世界时间）才会截断，届时只影响那个环绕点的排序。
     * 另外它必须<b>单调不减</b>（原版所有维度共用同一个 {@code gameTime} 递增 ⇒ 天然成立）。
     *
     * @param tick      世界 tick 号（{@code ServerLevel#getGameTime()}）
     * @param dim       维度小整数（MC 侧映射，本类只当它是标签）
     * @param pos       {@code BlockPos#asLong()}
     * @param fromState 变化<b>之前</b>的状态索引 ⇒ 倒流时要写回的目标态
     * @param afterState 变化<b>之后</b>的状态索引（只为诊断/去重，回退时不写它）
     * @param payload   方块实体载荷槽号，{@link #NO_PAYLOAD} = 没有
     */
    public record Change(int tick, int dim, long pos, int fromState, int afterState, int payload) {

        public boolean hasPayload() {
            return this.payload != NO_PAYLOAD;
        }

        /** 一行文本（日志/自检用；字段顺序稳定，便于 grep）。 */
        public String toText() {
            return "tick=" + this.tick + " dim=" + this.dim + " pos=" + this.pos
                    + " from=" + this.fromState + " after=" + this.afterState
                    + " payload=" + this.payload;
        }

        @Override
        public String toString() {
            return toText();
        }
    }

    // ------------------------------------------------------------------ 锚点（只存一份）

    /**
     * 锚点：玩家"记下这一刻"的<b>完整快照</b> + 那一刻的日志游标。
     *
     * <p>口径来自用户口述（设计稿 §11）：「时间倒流不是先要玩家设置锚点吗，基于此再进行倒流」
     * ⇒ 倒流的目标不是一个"秒数"，而是<b>这个 tick</b>；回退到位后玩家还要被送回
     * {@code (x,y,z,yaw,pitch)}。
     *
     * <table border="1">
     *   <caption>字段口径</caption>
     *   <tr><th>字段</th><th>含义</th><th>谁用</th></tr>
     *   <tr><td>{@code tick}</td><td>设锚点那一刻的世界 {@code gameTime}（= 回退目标）</td>
     *       <td>{@link ShanhaiReplayAnchor#plan}</td></tr>
     *   <tr><td>{@code dim}</td><td>维度小整数（{@code ShanhaiReplayEngine#dimIdOf}）；<b>跨维度只回退世界、不传送</b></td>
     *       <td>{@link ShanhaiReplayAnchor#restoreGate}</td></tr>
     *   <tr><td>{@code x/y/z}</td><td>玩家坐标（{@code double}，不是方块坐标 —— 传送要原样回去）</td>
     *       <td>回退到位后的 {@code teleportTo}</td></tr>
     *   <tr><td>{@code yaw/pitch}</td><td>朝向（原样回放，否则"回到锚点"会变成"回到锚点但换了朝向"）</td>
     *       <td>同上</td></tr>
     *   <tr><td>{@code journalCursor}</td><td>落锚那一刻 {@code journal.newestTick()}（{@code -1} = 当时还没有任何记录）。
     *       它是一个<b>锚点与日志的绑定标记</b>：日志被清空/换过世界时能和"正常回退"区分开</td>
     *       <td>status / 日志判读</td></tr>
     *   <tr><td>{@code stale} / {@code staleReason} / {@code staleAtTick}</td>
     *       <td>锚点<b>保留</b>但标失效（玩家死亡 / 换维度 / 进 REPLAY）。
     *       🔴 标 stale <b>不动</b> tick 与位置 —— 失效的是"玩家侧快照可信度"，不是锚点本身</td>
     *       <td>{@code anchor status}</td></tr>
     * </table>
     *
     * <p>⚠️ 只存<b>一份</b>：{@link #setAnchor} 是覆盖语义（`set` 会把旧锚点顶掉），
     * 没有队列、没有历史。用户口径就是"设一个锚点"，多份会让"倒到哪个锚点"变成歧义。
     */
    public record Anchor(int tick, int dim, double x, double y, double z,
                         float yaw, float pitch, int journalCursor,
                         boolean stale, String staleReason, int staleAtTick) {

        public Anchor {
            if (staleReason == null) {
                staleReason = "";
            }
        }

        /** 距现在多少 tick（负数 = 锚点在"未来"，调用方应当拒绝）。 */
        public int distanceTo(int nowTick) {
            return nowTick - this.tick;
        }

        /** 坐标文本（{@code 1.50/64.00/-3.50}；日志/status 用）。 */
        public String posText() {
            return String.format(Locale.ROOT, "%.2f/%.2f/%.2f", this.x, this.y, this.z);
        }

        /** 一行文本（字段顺序稳定，便于 grep）。 */
        public String toText() {
            return "tick=" + this.tick + " dim=" + this.dim + " pos=" + posText()
                    + " yaw=" + String.format(Locale.ROOT, "%.1f", this.yaw)
                    + " pitch=" + String.format(Locale.ROOT, "%.1f", this.pitch)
                    + " journalCursor=" + this.journalCursor
                    + " stale=" + (this.stale ? 1 : 0)
                    + (this.stale ? " stale_reason=" + this.staleReason + " stale_at=" + this.staleAtTick : "");
        }

        @Override
        public String toString() {
            return toText();
        }

        /**
         * 在同一份快照上打/撤销 stale（位置、tick、朝向、游标一律不动）。
         *
         * @param reason 空 ⇒ {@code stale=false}（撤销）
         * @param tick   标失效那一刻的世界 tick（撤销时忽略）
         */
        public Anchor withStale(String reason, int tick) {
            final boolean mark = reason != null && !reason.isEmpty();
            return new Anchor(this.tick, this.dim, this.x, this.y, this.z, this.yaw, this.pitch,
                    this.journalCursor, mark, mark ? reason : "", mark ? tick : 0);
        }
    }

    // ------------------------------------------------------------------ 环形状态

    private final Change[] buf;
    private final int capacity;

    /**
     * 满了之后丢哪一头。
     *
     * <p>🔴 <b>运行期恒为 {@code true}</b>（= 丢最老）。这个字段唯一的存在理由是让离线自检
     * 能把"丢最新"这个变异体跑一遍并证明断言会红（见 {@code temp\reversal\run-selfcheck.ps1}
     * 的变异体 B）；生产代码里没有任何一行会写它。
     */
    private boolean dropOldest = true;

    /**
     * 最老一条所在的<b>绝对</b>槽号（只增不减）。
     *
     * <p>用绝对槽号而不是取模下标，是为了让"槽是不是还活着"变成一次区间比较
     * （{@link #isLive(int)}）—— 合并映射表里的旧槽号因此<b>不需要</b>在淘汰时逐个清理。
     */
    private int tail;
    private int count;

    /** 满了被挤掉的老记录条数（设计 §10-⑤：永远能倒，只是倒得少）。 */
    private long droppedOldest;
    /** 「丢最新」次数 —— 正常路径<b>恒为 0</b>；只有被离线负对照改成"丢最新"时才会涨。 */
    private long droppedNewest;
    /** 单 tick 熔断丢弃次数。 */
    private long overflow;
    /** 状态没变的 no-op 写入次数（GT 机器自写回同态，必须在这里就被挡掉）。 */
    private long noop;

    /** 当前 tick 桶的 tick 号（{@code Integer.MIN_VALUE} = 还没有记录）。 */
    private int lastTick = Integer.MIN_VALUE;
    /** 当前 tick 桶已经记了多少条（熔断用）。 */
    private int perTick;
    /** 当前 tick 桶 pos → 绝对槽号（同 tick 合并用）。换 tick 就清空。 */
    private final Map<Long, Integer> mergeSlot = new HashMap<>();

    /**
     * 当前锚点（🔴 <b>只存一份</b>；{@code null} = 没设过/已清）。
     *
     * <p>它与环形缓冲<b>同归本类</b>：锚点是"窗口里的一个刻度 + 那一刻的玩家快照"，
     * 换世界/清 ring 的时候它必须跟着一起被处理，放在同一个类里才不会出现"ring 清了、
     * 锚点还指着一个早就没有记录的 tick"。
     */
    private Anchor anchor;

    /** 累计设过多少次锚点（只增不减；自检用它证明"set 是覆盖而不是追加"）。 */
    private long anchorSetCount;

    /**
     * @param capacity 环形容量（生产 = {@link ShanhaiReplayConst#MAX_RECORDS}；
     *                 自检用小容量把"绕回/丢弃"逼出来）
     */
    public ShanhaiReplayJournal(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity 必须 > 0: " + capacity);
        }
        this.capacity = capacity;
        this.buf = new Change[capacity];
    }

    // ------------------------------------------------------------------ 写

    /**
     * 记一笔方块变化。
     *
     * <p>顺序刻意是：先判 no-op → 再看 tick 是否换桶 → 同 tick 合并 → 熔断 → 淘汰 → 落盘。
     * 任意两步换序都会让某条断言变红（自检 ①/④ 就是按这个顺序写的）。
     *
     * @return {@code true} = 这一笔真的进了缓冲（合并也算"进了"）
     */
    public boolean record(int tick, int dim, long pos, int fromState, int afterState, int payload) {
        if (fromState == afterState) {
            ++this.noop;
            return false;
        }
        if (tick != this.lastTick) {
            // 换桶：上一 tick 的合并表作废（也顺手断开了"上一 tick 的槽号"）
            this.lastTick = tick;
            this.perTick = 0;
            this.mergeSlot.clear();
        }

        // ---- 同 tick 同 pos 合并（保留最早 fromState / 最新 afterState / 最早 payload）
        final Integer slot = this.mergeSlot.get(pos);
        if (slot != null && isLive(slot)) {
            final Change old = this.buf[slot % this.capacity];
            if (old != null && old.tick() == tick) {
                this.buf[slot % this.capacity] = new Change(tick, dim, pos,
                        old.fromState(), afterState, old.payload());
                return true;
            }
        }

        // ---- 单 tick 熔断
        if (this.perTick >= ShanhaiReplayConst.MAX_PER_TICK) {
            ++this.overflow;
            return false;
        }

        // ---- 容量淘汰
        if (this.count == this.capacity) {
            if (this.dropOldest) {
                evictOldest();
            } else {
                // 只有被离线负对照改坏时才会走到这里：丢最新 = 最近几十秒全没了
                ++this.droppedNewest;
                return false;
            }
        }

        final int abs = this.tail + this.count;
        this.buf[abs % this.capacity] = new Change(tick, dim, pos, fromState, afterState, payload);
        this.mergeSlot.put(pos, abs);
        ++this.count;
        ++this.perTick;
        return true;
    }

    private void evictOldest() {
        this.buf[this.tail % this.capacity] = null;
        ++this.tail;
        --this.count;
        ++this.droppedOldest;
    }

    private boolean isLive(int absSlot) {
        return absSlot >= this.tail && absSlot < this.tail + this.count;
    }

    // ------------------------------------------------------------------ 读 / 取

    public int capacity() {
        return this.capacity;
    }

    public int size() {
        return this.count;
    }

    public boolean isEmpty() {
        return this.count == 0;
    }

    /** 被挤掉的老记录条数（窗口因此变短）。 */
    public long droppedOldest() {
        return this.droppedOldest;
    }

    /** 「丢最新」次数 —— 正常运行恒 0（见 {@link #dropOldest}）。 */
    public long droppedNewest() {
        return this.droppedNewest;
    }

    /** 单 tick 熔断丢弃次数。 */
    public long overflow() {
        return this.overflow;
    }

    /** 同态自写的 no-op 次数（记录器在入口就挡掉了，不进缓冲）。 */
    public long noop() {
        return this.noop;
    }

    /** 最新一条的 tick；{@code -1} = 空。 */
    public int newestTick() {
        return this.count == 0 ? -1 : this.buf[(this.tail + this.count - 1) % this.capacity].tick();
    }

    /** 最老一条的 tick；{@code -1} = 空。 */
    public int oldestTick() {
        return this.count == 0 ? -1 : this.buf[this.tail % this.capacity].tick();
    }

    /** 带方块实体载荷的条数（扫描；只给 status/自检用）。 */
    public int payloadCount() {
        int n = 0;
        for (int i = this.tail; i < this.tail + this.count; i++) {
            final Change c = this.buf[i % this.capacity];
            if (c != null && c.hasPayload()) {
                ++n;
            }
        }
        return n;
    }

    /** 快照（从新到旧，<b>不</b>移除）——自检断言顺序用。 */
    public List<Change> snapshotNewestFirst() {
        final List<Change> out = new ArrayList<>(this.count);
        for (int i = this.tail + this.count - 1; i >= this.tail; i--) {
            out.add(this.buf[i % this.capacity]);
        }
        return out;
    }

public List<Change> takeNewestTick() {
        if (this.count == 0) {
            return List.of();
        }
        final int tick = this.buf[(this.tail + this.count - 1) % this.capacity].tick();
        final int end = this.tail + this.count;
        int begin = end;
        // tick 号在追加顺序上是单调不减的 ⇒ 同一个 tick 的条目必是连续的一段（合并只在段内改值）
        while (begin > this.tail && this.buf[(begin - 1) % this.capacity].tick() == tick) {
            --begin;
        }
        final int n = end - begin;
        final List<Change> out = new ArrayList<>(n);
        // 🔴 LIFO：唯一的顺序来源就是这一行（改成 for (i = begin; i < end; i++) 就是 FIFO，自检会红）
        for (int i = end - 1; i >= begin; i--) {
            out.add(this.buf[i % this.capacity]);
        }
        for (int i = begin; i < end; i++) {
            this.buf[i % this.capacity] = null;
        }
        this.count = begin - this.tail;
        this.mergeSlot.clear();
        return out;
    }

    /**
     * 清空环形缓冲（倒流收尾「把 ring 清空」+ 换世界/重开服）。
     *
     * <p>⚠️ 它<b>不动锚点</b>：锚点的生命周期归调用方
     * （{@code ShanhaiReplayService#stop} 收尾时显式 {@link #clearAnchor()}，
     * {@code ShanhaiReplayRecorder#resetAll} 换世界时也显式清）。分成两步是刻意的 ——
     * "记录没了"和"锚点没了"是两件事，混在一起就没法在日志里分清是哪一步出的问题。
     */
    public void clear() {
        for (int i = 0; i < this.capacity; i++) {
            this.buf[i] = null;
        }
        this.tail = 0;
        this.count = 0;
        this.perTick = 0;
        this.lastTick = Integer.MIN_VALUE;
        this.mergeSlot.clear();
        this.droppedOldest = 0L;
        this.droppedNewest = 0L;
        this.overflow = 0L;
        this.noop = 0L;
    }

    /** 一行摘要（日志/status 用）。 */
    public String summary() {
        return "records=" + this.count + "/" + this.capacity
                + " ticks=[" + this.oldestTick() + "," + this.newestTick() + "]"
                + " payloads=" + this.payloadCount()
                + " dropped=" + this.droppedOldest + " overflow=" + this.overflow + " noop=" + this.noop;
    }

    // ------------------------------------------------------------------ 锚点读写

    /** 当前锚点；{@code null} = 没有。 */
    public Anchor anchor() {
        return this.anchor;
    }

    public boolean hasAnchor() {
        return this.anchor != null;
    }

    /** 累计设过多少次锚点（只增不减）。 */
    public long anchorSetCount() {
        return this.anchorSetCount;
    }

    /** 落锚那一刻日志里最新一条的 tick（{@code -1} = 当时还没有任何记录）。 */
    public int journalCursor() {
        return newestTick();
    }

    /**
     * 设锚点（🔴 <b>覆盖</b>旧的那条，只存一份）。
     *
     * @param next {@code null} ⇒ 等价于 {@link #clearAnchor()}（防御分支，命令层不该这么调）
     */
    public void setAnchor(Anchor next) {
        if (next == null) {
            this.anchor = null;
            return;
        }
        this.anchor = next;
        ++this.anchorSetCount;
    }

    /** 清掉锚点（{@code time anchor clear}、倒流收尾、换世界）。 */
    public void clearAnchor() {
        this.anchor = null;
    }

    /**
     * 标 stale（<b>保留</b>锚点本体：tick / 位置 / 朝向 / 游标一个都不动）。
     *
     * <p>触发口径（设计稿 §11-③）：玩家死亡、换维度、进 REPLAY。
     * 这些事件让"玩家侧快照"不再可信，但不改变"世界要回到哪个 tick"。
     *
     * @return {@code false} = 没有锚点（什么也没发生）
     */
    public boolean markAnchorStale(String reason, int tick) {
        if (this.anchor == null) {
            return false;
        }
        this.anchor = this.anchor.withStale(reason, tick);
        return true;
    }

    /**
     * 撤销 stale（回退到位、玩家已被传回锚点 ⇒ 玩家侧快照重新可信）。
     *
     * @return {@code false} = 没有锚点，或本来就不是 stale
     */
    public boolean clearAnchorStale() {
        if (this.anchor == null || !this.anchor.stale()) {
            return false;
        }
        this.anchor = this.anchor.withStale("", 0);
        return true;
    }

    // ------------------------------------------------------------------ 方块实体口径（纯逻辑）

    /**
     * 这一笔变化要不要存"方块实体 NBT 快照"。
     *
     * <h2>判据为什么只有这一条</h2>
     * 倒流是<b>先把目标格设成 AIR、再设目标态</b>（两段式）。真正需要还原的是
     * <b>变化之前那一刻</b>那个方块实体（箱子里的物品、机器的进度 NBT）——
     * 它只可能在"变化前的状态带方块实体"时存在。所以：
     * <ul>
     *   <li>变化前有 BE ⇒ <b>必须存</b>（否则箱子/机器回来是空壳）；</li>
     *   <li>变化前没有 BE（例如往空地上放箱子）⇒ <b>不必存</b>：目标态是 AIR/无 BE，
     *       第一段写完就没有方块实体了（对照组：这种 pos 一个都不许存）。</li>
     * </ul>
     *
     * @param beforeHasBlockEntity 变化<b>之前</b>该 pos 上有没有方块实体
     * @param fromEqualsAfter      这一笔是不是同态自写（是的话整笔都不记，更不会存 NBT）
     */
    public static boolean needsPayload(boolean beforeHasBlockEntity, boolean fromEqualsAfter) {
        return beforeHasBlockEntity && !fromEqualsAfter;
    }
}
