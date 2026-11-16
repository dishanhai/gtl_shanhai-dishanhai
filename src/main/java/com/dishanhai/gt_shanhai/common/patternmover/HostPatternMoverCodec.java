package com.dishanhai.gt_shanhai.common.patternmover;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.util.List;

/**
 * 把 {@link HostPatternMoverEntry} 接到 {@link HostPatternMoverData} 的适配器：
 * <b>字节量 / 去重键 / 配方类型</b>三个问题的生产实现。
 *
 * <h2>一、字节量怎么算（实算 ＋ 精确算术，见 {@link HostPatternMoverBytes}）</h2>
 * <pre>
 *   measureBytes(list) = 20 + Σ(entry.standaloneBytes - 3) + (fromHost ? 13 + writeUTF字节 : 0)
 *   其中 entry.standaloneBytes 是【真的 NbtIo.write 一遍】得到的
 * </pre>
 * ⚠️ <b>为什么不每次全量 {@code NbtIo.write} 整份</b>：那是 O(n²)（1130 条 × 每条几百字节，
 * 一次右键要写掉上亿字节，玩家能感到卡）。所以整份用 NBT 格式的<b>确定性算术</b>拼，
 * 而算法本身由 {@link #realTotalBytes}（真的全量写一遍）在
 * {@link HostPatternMoverSelfCheck} 里逐字节对账 —— 0/1/2/5 条 × 有/无 fromHost × 含中文与代理对，
 * 全部要求<b>相等</b>。生产路径上，写回物品之前也会再跑一次 {@link #realTotalBytes} 做最终校验
 * （见 {@link HostPatternMoverNbt#toTag}）。
 *
 * <h2>二、⛔ 不能用的口径</h2>
 * {@code Tag#sizeInBytes()} 是<b>内存占用估算</b>（{@code CompoundTag} = 48 + Σ…，{@code StringTag} = 36 + 2×char）
 * <b>不是</b>序列化字节数，实测会虚高好几倍 ⇒ <b>不许拿它当 1.5 MiB 判据</b>
 * （取证：{@code temp/pattern-mover/取证.md} §6.1）。
 */
public final class HostPatternMoverCodec implements HostPatternMoverData.Codec<HostPatternMoverEntry> {

    /** {@code fromHost} 为空时的实例（读档/断言常用）。 */
    public static final HostPatternMoverCodec EMPTY_FROM_HOST = new HostPatternMoverCodec("");

    private final String fromHost;

    public HostPatternMoverCodec(String fromHost) {
        this.fromHost = fromHost == null ? "" : fromHost;
    }

    public String fromHost() {
        return this.fromHost;
    }

    @Override
    public int measureBytes(List<HostPatternMoverEntry> entries) {
        return totalBytes(entries, this.fromHost);
    }

    @Override
    public String dedupKey(HostPatternMoverEntry entry) {
        return entry == null ? "" : entry.dedupKey();
    }

    @Override
    public String typeOf(HostPatternMoverEntry entry) {
        return entry == null ? "" : entry.type();
    }

    /** 已经打过 {@code circuit_conflict} 的键（同一份 Codec 内去重 ⇒ 同 key 最多一行）。 */
    private final java.util.Set<String> conflictLogged = new java.util.HashSet<>();

    /** conflict 日志的封顶条数（一次读取最多这么多行；超出的不再打，避免刷屏）。 */
    static final int MAX_CONFLICT_LOG = 64;

    /**
     * 🔴 同键合并：<b>其余字段按"新的覆盖旧的"，但 {@code circuit} 取非 0 的那一个</b>
     * （用户 2026-11-06 拍板；三种组合见 {@link HostPatternMoverCircuit#mergeForDedup}）。
     *
     * <p>为什么非得单独管这一项：电路是<b>机器槽的状态</b>、不是样板身份的一部分，所以若跟 type 一样
     * 被"新的覆盖旧的"处理，就会丢：
     * <pre>
     *   第 1 次读：星律母槽 ⇒ circuit = 5
     *   第 2 次读：同一配方出现在普通槽 / 另一台机器 ⇒ circuit = 0（那里没设电路）
     *   「整条换成新的」⇒ 合并后 0 ⇒ 放置时一个电路都写不回去（用户报的"没复制黏贴"就复发）
     * </pre>
     *
     * <p>两边都是<b>不同</b>的非 0 值 ⇒ 保留后读到的，并打<b>一行</b> {@code circuit_conflict}
     * （同 key 最多一次 ＋ 封顶 {@link #MAX_CONFLICT_LOG} 条 ⇒ 绝不刷屏）。
     * 同值合并（5 与 5）、以及任何一边是 0 的合并，都<b>不</b>算冲突、不打日志。
     */
    @Override
    public HostPatternMoverEntry merge(HostPatternMoverEntry existing, HostPatternMoverEntry incoming) {
        if (existing == null || incoming == null) {
            return incoming;
        }
        final boolean conflict = HostPatternMoverCircuit.isConflict(existing.circuit(), incoming.circuit());
        final int kept = HostPatternMoverCircuit.mergeForDedup(existing.circuit(), incoming.circuit());
        if (!conflict && kept == incoming.circuit()) {
            // 常态（后读到的是非 0，或两边同一个值，或两边都 0）：整条换成新的就对了，不必重建
            return incoming;
        }
        if (conflict) {
            logConflictOnce(incoming.dedupKey(), existing.circuit(), incoming.circuit(), kept);
        }
        // "新的覆盖旧的"，只把电路换成合并后的值（pattern / type 仍取新的）
        return HostPatternMoverEntry.of(incoming.patternNbt(), incoming.type(), kept);
    }

    private void logConflictOnce(String key, int a, int b, int kept) {
        if (this.conflictLogged.size() >= MAX_CONFLICT_LOG) {
            return;
        }
        if (!this.conflictLogged.add(key == null ? "" : key)) {
            return; // 这个 key 已经报过了
        }
        HostPatternMoverLog.info("{} circuit_conflict {} a={} b={} keep={}", HostPatternMoverLog.PREFIX,
                key, Integer.valueOf(a), Integer.valueOf(b), Integer.valueOf(kept));
    }

    /** Σ 每条作为列表元素的字节数。 */
    public static int sumElementBytes(List<HostPatternMoverEntry> entries) {
        int sum = 0;
        if (entries != null) {
            for (HostPatternMoverEntry e : entries) {
                if (e != null) {
                    sum += e.listElementBytes();
                }
            }
        }
        return sum;
    }

    /** 算术口径的整份字节数（与 {@link #realTotalBytes} 必须逐字节相等）。 */
    public static int totalBytes(List<HostPatternMoverEntry> entries, String fromHost) {
        return HostPatternMoverBytes.totalBytes(sumElementBytes(entries), fromHost);
    }

    /**
     * <b>真的</b>把整份 {@code shanhai:mover} 标签写一遍，返回字节数（对账 / 最终校验用）。
     *
     * <p>与 {@link #totalBytes} 是同一个量的两种算法：一个是「Σ + 固定开销」，一个是「实写」。
     * 两者不等就说明本类的字节算术坏了 —— 那种情况必须<b>报红</b>，不许悄悄放行。
     */
    public static int realTotalBytes(List<HostPatternMoverEntry> entries, String fromHost) {
        return HostPatternMoverBytes.writeSize(rawTag(entries, fromHost, false));
    }

    /** 只建 {@code {patterns, fromHost?}}，不带 {@code bytes}（它自己也要计字节，不能自指）。 */
    static CompoundTag rawTag(List<HostPatternMoverEntry> entries, String fromHost, boolean withBytes) {
        final CompoundTag tag = new CompoundTag();
        final ListTag list = new ListTag();
        if (entries != null) {
            for (HostPatternMoverEntry e : entries) {
                if (e != null) {
                    list.add(e.entryTag());
                }
            }
        }
        tag.put(HostPatternMoverNbt.K_PATTERNS, list);
        if (fromHost != null && !fromHost.isEmpty()) {
            tag.putString(HostPatternMoverNbt.K_FROM_HOST, fromHost);
        }
        if (withBytes) {
            tag.putInt(HostPatternMoverNbt.K_BYTES, HostPatternMoverBytes.totalBytes(
                    sumElementBytes(entries), fromHost));
        }
        return tag;
    }
}
