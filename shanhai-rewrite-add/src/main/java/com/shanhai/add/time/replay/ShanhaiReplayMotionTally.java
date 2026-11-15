package com.shanhai.add.time.replay;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 山海重构 · <b>实体运动记录的「按类型计数」账本</b>（🔴 零 Minecraft 依赖，别加 import）。
 *
 * <h2>1. 为什么必须能按类型读（2026-11-14 真机）</h2>
 * 用户原话：「猪还是<b>没有倒着走</b>」。而当时的日志只有两个数：
 * <pre>
 *   done … motion=15426/15256            ← 记录条数 / 写回成功条数
 *   client_sync … motion=15256/15256     ← 发包目标数 / 真发出数
 * </pre>
 * ⇒ <b>"猪到底有没有被记"这个问题，日志里根本没有答案</b>：那 15426 条可能是掉落物、
 * 经验球、或者别的生物。判据要求"先证明猪有没有被记"，所以这一层把每条记录按
 * {@code 实体类型} 记一笔，并单列 {@code minecraft:pig} 的三段读数（记 / 写回 / 发包）。
 *
 * <h2>2. 两条口径（自检 U 组逐条断言）</h2>
 * <ul>
 *   <li>分类只有三种：{@link Kind#MOB 生物}（{@code LivingEntity}）、
 *       {@link Kind#ITEM 掉落物}（{@code ItemEntity}）、{@link Kind#OTHER 其它}；</li>
 *   <li>三段的计数<b>必须分开</b>：{@code recorded}（进 journal）、{@code applied}（写回位置成功）、
 *       {@code sent}（真的产生发包请求）。合在一起就又回到"15426 是谁"那个问题。</li>
 * </ul>
 *
 * <h2>3. 本类只做计数与格式化</h2>
 * 谁被记（{@code Entity#getType()}）、谁写回成功、发了几包，全部由调用方（记录器/引擎）决定；
 * 这样格式与排序可以离线断言，而 Minecraft 侧只负责喂 key。
 */
public final class ShanhaiReplayMotionTally {

    /** 用户盯着的那只：{@code /summon minecraft:pig} 的类型 id（判据要求单列）。 */
    public static final String PIG = "minecraft:pig";

    /** {@code motion_top=} 默认打前几名。 */
    public static final int TOP = 5;

    /** 三类实体（用户口径："在 motion= 计数里区分生物 / 掉落物 / 其它"）。 */
    public enum Kind {

        /** 生物（{@code LivingEntity}：猪/牛/怪…）。 */
        MOB("mob"),
        /** 掉落物（{@code ItemEntity}）。 */
        ITEM("item"),
        /** 其它（箭/矿车/经验球/画…）。 */
        OTHER("other");

        private final String code;

        Kind(String code) {
            this.code = code;
        }

        public String code() {
            return this.code;
        }
    }

    /** 三段计数（同一类型一行）。 */
    private static final int RECORDED = 0;
    private static final int APPLIED = 1;
    private static final int SENT_MOVES = 2;
    private static final int SENT_PACKETS = 3;
    private static final int SLOTS = 4;

    /** 类型 id → 4 个计数（累计值；{@link #reset()} 清零）。 */
    private final Map<String, long[]> perType = new HashMap<>();

    /** 记录侧的分类小计（只统计 {@link #recorded}）。 */
    private long mob;
    private long item;
    private long other;

    /**
     * 分类判定（纯函数，自检直接断言这一条）。
     *
     * @param living     是不是 {@code LivingEntity}（生物）
     * @param itemEntity 是不是 {@code ItemEntity}（掉落物）
     */
    public static Kind kindOf(boolean living, boolean itemEntity) {
        if (itemEntity) {
            return Kind.ITEM;
        }
        return living ? Kind.MOB : Kind.OTHER;
    }

    private long[] slot(String typeKey) {
        final String key = (typeKey == null || typeKey.isEmpty()) ? "unknown" : typeKey;
        long[] s = this.perType.get(key);
        if (s == null) {
            s = new long[SLOTS];
            this.perType.put(key, s);
        }
        return s;
    }

    /** 记一条运动记录（进 journal 就算，含同 tick 合并）。 */
    public void recorded(String typeKey, Kind kind) {
        final String key = (typeKey == null || typeKey.isEmpty()) ? "unknown" : typeKey;
        ++slot(key)[RECORDED];
        switch (kind == null ? Kind.OTHER : kind) {
            case MOB -> ++this.mob;
            case ITEM -> ++this.item;
            default -> ++this.other;
        }
    }

    /** 记一条"位置写回成功"。 */
    public void applied(String typeKey) {
        ++slot(typeKey)[APPLIED];
    }

    /**
     * 记一条"发包请求真的发生了"。
     *
     * @param packets 这次请求实际发给了几个玩家（{@code 0} = 一个都没发出去，仍然算一次请求）
     */
    public void sent(String typeKey, int packets) {
        final long[] s = slot(typeKey);
        ++s[SENT_MOVES];
        s[SENT_PACKETS] += Math.max(0, packets);
    }

    /** 某类型的三段读数（{@code [recorded, applied, sentMoves, sentPackets]} 的拷贝）。 */
    public long[] of(String typeKey) {
        final long[] s = this.perType.get(typeKey == null ? "unknown" : typeKey);
        return s == null ? new long[SLOTS] : s.clone();
    }

    /** 记录总数。 */
    public long recordedTotal() {
        long sum = 0L;
        for (long[] s : this.perType.values()) {
            sum += s[RECORDED];
        }
        return sum;
    }

    /**
     * {@code motion_top=…}：按<b>记录条数</b>降序（同数按类型名升序，保证同一份数据两次打印一致）。
     *
     * @return 例：{@code motion_top=minecraft:pig×120,minecraft:item×30 top=2/3 recorded_total=150}
     */
    public String topLine() {
        final List<Map.Entry<String, long[]>> rows = new ArrayList<>(this.perType.entrySet());
        rows.removeIf(e -> e.getValue()[RECORDED] <= 0L);
        rows.sort((a, b) -> {
            final int cmp = Long.compare(b.getValue()[RECORDED], a.getValue()[RECORDED]);
            return cmp != 0 ? cmp : a.getKey().compareTo(b.getKey());
        });
        final StringBuilder sb = new StringBuilder("motion_top=");
        final int n = Math.min(TOP, rows.size());
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(rows.get(i).getKey()).append('×').append(rows.get(i).getValue()[RECORDED]);
        }
        if (n == 0) {
            sb.append("none");
        }
        sb.append(" top=").append(n).append('/').append(rows.size());
        sb.append(" recorded_total=").append(recordedTotal());
        return sb.toString();
    }

    /**
     * {@code motion_mix=…}：三类小计 + <b>单列猪的三段读数</b>（判据"猪到底有没有被记"的落点）。
     *
     * @return 例：{@code motion_mix recorded[mob=120 item=30 other=11] pig_recorded=120
     *         pig_applied=118 pig_sent=118 pig_packets=118}
     */
    public String mixLine() {
        final long[] pig = of(PIG);
        return "motion_mix recorded[mob=" + this.mob + " item=" + this.item + " other=" + this.other + "]"
                + " pig_recorded=" + pig[RECORDED]
                + " pig_applied=" + pig[APPLIED]
                + " pig_sent=" + pig[SENT_MOVES]
                + " pig_packets=" + pig[SENT_PACKETS];
    }

    /** 一行摘要（{@code /time status} 用）。 */
    public String summary() {
        return "types=" + this.perType.size() + " " + topLine() + " " + mixLine();
    }

    public void reset() {
        this.perType.clear();
        this.mob = 0L;
        this.item = 0L;
        this.other = 0L;
    }
}
