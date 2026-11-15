package com.shanhai.add.time.replay;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 山海重构 · <b>按 tick 分桶的环形时间线</b>（🔴 零 Minecraft 依赖，别加 import）。
 *
 * <h2>1. 为什么不是 5 份各写一遍</h2>
 * 倒流要按时间线逐步反向播放，所以<b>每一层</b>（方块 / 实体 / 机器进度 / 方块实体差分 / 世界标量）
 * 都需要同一套语义：<b>按 tick 分桶、同桶同 key 合并、LIFO 吐出、满了丢最老、单 tick 熔断</b>。
 * 写成 5 份复制会被"改一处忘四处"咬（本工程血账）。
 *
 * <h2>2. 三条被离线断言钉住的语义（{@code tools\replay-selfcheck} I 组）</h2>
 * <ol>
 *   <li>{@link #takeNewestTick()} 返回<b>最后记的那一条先回</b>（LIFO）；</li>
 *   <li>同 tick 同 key 的条目走 {@link Merger}：合并<b>是默认</b>，合并器返回 {@code null} 表示
 *       "这两条不是同一条东西，别合并"（例如同 pos 不同维度）；</li>
 *   <li>满了<b>丢最老</b>（窗口变短，但永远能倒）。</li>
 * </ol>
 *
 * <h2>3. 内存口径</h2>
 * 三条并行数组（{@code Object[]} + {@code int[]} + {@code long[]}）⇒ 每条 ≈ 8 + 4 + 8 + 记录本身。
 * 记的是<b>差分</b>，不是每 tick 全量快照 —— 这是"逐步连续、无跳变"与"内存可控"能同时成立的原因。
 *
 * @param <T> 条目类型
 */
public final class ShanhaiReplayTimeRing<T> {

    /**
     * 同 tick 同 key 的合并器。
     *
     * @return 合并后的条目；{@code null} = 不合并（照常追加一条）
     */
    public interface Merger<T> {
        T merge(T older, T newer);
    }

    private final int capacity;
    private final Object[] entries;
    private final int[] ticks;
    private final long[] keys;

    /** 最老一条的绝对槽号（只增不减；用绝对槽号让"槽还活着吗"变成一次区间比较）。 */
    private int tail;
    private int count;

    private long droppedOldest;
    private long overflow;

    private int lastTick = Integer.MIN_VALUE;
    private int perTick;
    private final Map<Long, Integer> slotByKey = new HashMap<>();

    /**
     * @param capacity 环形容量（生产见 {@link ShanhaiReplayConst} 各层的 *_RECORDS）
     */
    public ShanhaiReplayTimeRing(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity 必须 > 0: " + capacity);
        }
        this.capacity = capacity;
        this.entries = new Object[capacity];
        this.ticks = new int[capacity];
        this.keys = new long[capacity];
    }

    /**
     * 追加一条。
     *
     * @param tick     世界 tick
     * @param key      同桶合并用的 key（一般是 {@code BlockPos#asLong()}；实体的 UUID 等）
     * @param entry    条目本体（它的 {@code tick} 必须与参数一致，由调用方保证）
     * @param merger   合并器（{@code null} = 本层不合并）
     * @param maxPerTick 单 tick 熔断上限
     * @return {@code true} = 进了缓冲（合并也算进了）
     */
    public boolean add(int tick, long key, T entry, Merger<T> merger, int maxPerTick) {
        if (tick != this.lastTick) {
            this.lastTick = tick;
            this.perTick = 0;
            this.slotByKey.clear();
        }
        final Integer slot = this.slotByKey.get(key);
        if (merger != null && slot != null && isLive(slot) && this.ticks[slot % this.capacity] == tick) {
            @SuppressWarnings("unchecked")
            final T older = (T) this.entries[slot % this.capacity];
            final T merged = merger.merge(older, entry);
            if (merged != null) {
                this.entries[slot % this.capacity] = merged;
                return true;
            }
        }
        if (this.perTick >= maxPerTick) {
            ++this.overflow;
            return false;
        }
        if (this.count == this.capacity) {
            evictOldest();
        }
        final int abs = this.tail + this.count;
        final int idx = abs % this.capacity;
        this.entries[idx] = entry;
        this.ticks[idx] = tick;
        this.keys[idx] = key;
        this.slotByKey.put(key, abs);
        ++this.count;
        ++this.perTick;
        return true;
    }

    private void evictOldest() {
        this.entries[this.tail % this.capacity] = null;
        ++this.tail;
        --this.count;
        ++this.droppedOldest;
    }

    private boolean isLive(int absSlot) {
        return absSlot >= this.tail && absSlot < this.tail + this.count;
    }

    public int capacity() {
        return this.capacity;
    }

    public int size() {
        return this.count;
    }

    public boolean isEmpty() {
        return this.count == 0;
    }

    /** 被挤掉的老条目数（窗口因此变短）。 */
    public long droppedOldest() {
        return this.droppedOldest;
    }

    /** 单 tick 熔断丢弃次数。 */
    public long overflow() {
        return this.overflow;
    }

    /** 最新一条的 tick；{@code -1} = 空。 */
    public int newestTick() {
        return this.count == 0 ? -1 : this.ticks[(this.tail + this.count - 1) % this.capacity];
    }

    /** 最老一条的 tick；{@code -1} = 空。 */
    public int oldestTick() {
        return this.count == 0 ? -1 : this.ticks[this.tail % this.capacity];
    }

    /** 快照（从新到旧，<b>不</b>移除）。 */
    public List<T> snapshotNewestFirst() {
        final List<T> out = new ArrayList<>(this.count);
        for (int i = this.tail + this.count - 1; i >= this.tail; i--) {
            out.add(at(i));
        }
        return out;
    }

    /**
     * <b>偷看</b>最新那个 tick 桶（<b>不</b>移除）—— "粒子提前一拍"要用它。
     *
     * <p>倒放顺序是 cursor、cursor-1、…；某一拍把 T 桶取走后，环里最新的桶就是
     * <b>下一拍（T-1）要恢复的那些方块</b>。粒子必须在 T 这一拍就发出去（先有粒子、方块后出现），
     * 而方块要到下一拍才写 ⇒ 需要一个"只看不取"的入口。
     *
     * @return 空列表 = 环里空了
     */
    public List<T> peekNewestTick() {
        if (this.count == 0) {
            return List.of();
        }
        final int tick = this.ticks[(this.tail + this.count - 1) % this.capacity];
        final int end = this.tail + this.count;
        int begin = end;
        while (begin > this.tail && this.ticks[(begin - 1) % this.capacity] == tick) {
            --begin;
        }
        final List<T> out = new ArrayList<>(end - begin);
        for (int i = end - 1; i >= begin; i--) {
            out.add(at(i));
        }
        return out;
    }

    /**
     * 取走<b>最新那个 tick 桶</b>的全部条目，返回顺序 = 记录顺序的<b>逆序</b>（LIFO）。
     *
     * <p>驱动方（{@link ShanhaiReplayPacer}）每外层 tick 每层调一次 ⇒ 1:1 反向播放的来源。
     *
     * @return 空列表 = 这一层在这个 tick 没有东西可倒
     */
    public List<T> takeNewestTick() {
        if (this.count == 0) {
            return List.of();
        }
        final int tick = this.ticks[(this.tail + this.count - 1) % this.capacity];
        final int end = this.tail + this.count;
        int begin = end;
        while (begin > this.tail && this.ticks[(begin - 1) % this.capacity] == tick) {
            --begin;
        }
        final List<T> out = new ArrayList<>(end - begin);
        // 🔴 LIFO：后改的先回。改成正序就是"同一 tick 内回退到中间态"（负对照见自检）。
        for (int i = end - 1; i >= begin; i--) {
            out.add(at(i));
        }
        for (int i = begin; i < end; i++) {
            this.entries[i % this.capacity] = null;
        }
        this.count = begin - this.tail;
        this.slotByKey.clear();
        return out;
    }

    @SuppressWarnings("unchecked")
    private T at(int absSlot) {
        return (T) this.entries[absSlot % this.capacity];
    }

    /** 清空（倒流收尾 / 换世界）。 */
    public void clear() {
        for (int i = 0; i < this.capacity; i++) {
            this.entries[i] = null;
            this.ticks[i] = 0;
            this.keys[i] = 0L;
        }
        this.tail = 0;
        this.count = 0;
        this.perTick = 0;
        this.lastTick = Integer.MIN_VALUE;
        this.slotByKey.clear();
        this.droppedOldest = 0L;
        this.overflow = 0L;
    }

    /** 一行摘要（日志/status 用）。 */
    public String summary() {
        return "records=" + this.count + "/" + this.capacity
                + " ticks=[" + this.oldestTick() + "," + this.newestTick() + "]"
                + " dropped=" + this.droppedOldest + " overflow=" + this.overflow;
    }
}
