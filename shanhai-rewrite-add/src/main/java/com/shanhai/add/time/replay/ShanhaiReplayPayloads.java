package com.shanhai.add.time.replay;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.nbt.CompoundTag;

/**
 * 山海重构 · <b>方块实体 NBT 载荷池</b>（MC 依赖只在这一层）。
 *
 * <h2>为什么单独一个池，而不是把 {@code CompoundTag} 塞进日志条目</h2>
 * <ol>
 *   <li>日志条目要能<b>零 MC 依赖</b>地跑离线自检 ⇒ 它只存一个 {@code int} 槽号
 *       （{@link ShanhaiReplayJournal#NO_PAYLOAD} = 没有）；</li>
 *   <li>同一个 pos 在一个 tick 桶里可能被合并 ⇒ 槽号可以被复用，池是"只增"的，
 *       合并时沿用最早那个槽号，旧的槽留在池里（倒流收尾一次性 {@link #clear()}）。</li>
 * </ol>
 *
 * <h2>量级</h2>
 * 只在"变化前该位置有方块实体"时才放（{@link ShanhaiReplayJournal#needsPayload}）：箱子 0.1–1 KB、
 * GT 机器 0.5–3 KB（设计 §2 第 1b 行）。200k 条满载时若全是机器 ≈ 数百 MB —— 这是<b>已知上限</b>，
 * 由 {@link ShanhaiReplayConst#MAX_RECORDS} 与单 tick 熔断共同夹住；P1 不做总字节熔断（记在报告"未做"里）。
 */
public final class ShanhaiReplayPayloads {

    private static final List<CompoundTag> POOL = new ArrayList<>();

    /**
     * 存一份 NBT，返回槽号。
     *
     * @param tag 由 {@code BlockEntity#saveWithFullMetadata()} 取出的快照（含 x/y/z/id）
     * @return 槽号（{@code >= 0}）；{@code tag == null} 时返回 {@link ShanhaiReplayJournal#NO_PAYLOAD}
     */
    public static synchronized int put(CompoundTag tag) {
        if (tag == null) {
            return ShanhaiReplayJournal.NO_PAYLOAD;
        }
        final int id = POOL.size();
        POOL.add(tag);
        return id;
    }

    /** 槽号 → NBT；越界/没有返回 {@code null}（调用方必须容忍）。 */
    public static synchronized CompoundTag get(int id) {
        if (id < 0 || id >= POOL.size()) {
            return null;
        }
        return POOL.get(id);
    }

    public static synchronized int size() {
        return POOL.size();
    }

    /** 倒流收尾/换世界时清空（在途的 {@code CompoundTag} 由 GC 回收）。 */
    public static synchronized void clear() {
        POOL.clear();
    }

    private ShanhaiReplayPayloads() {
    }
}
