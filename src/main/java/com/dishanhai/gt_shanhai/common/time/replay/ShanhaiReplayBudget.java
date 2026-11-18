package com.dishanhai.gt_shanhai.common.time.replay;

/**
 * 山海重构 · <b>倒流窗口的内存 / 每 tick 开销预算</b>（🔴 零 Minecraft 依赖，别加 import）。
 *
 * <h2>1. 为什么要有一个"预算"类</h2>
 * 用户口径：「给 1200 tick 窗口的实测内存/每 tick 开销（用离线核按体量估算并写上测量条件），
 * 超过 16 MiB 就降级策略（更长周期/只存变化格）」+ 后来更正「<b>不要</b>改成周期快照，
 * 降低保留内容（例如 BE 只存差分而非整份）」。
 * 所以这里把"记一条要多少字节"变成<b>可打印、可断言</b>的数，而不是拍脑袋。
 *
 * <h2>2. 测量条件（🔴 报数字必须带它）</h2>
 * <ul>
 *   <li>单条字节数由 {@code temp\replay-budget\Measure.java} 实测：{@code System.gc()} 前后
 *       {@code Runtime.totalMemory()-freeMemory()} 差值 ÷ 条数（HotSpot 21，64 位，默认堆，无 TLAB 调参）；</li>
 *   <li>"含摊销"= 记录对象本身 + {@link ShanhaiReplayTimeRing} 的三条并行数组（{@code Object[]}+{@code int[]}+{@code long[]}）
 *       每个槽位 20 B；</li>
 *   <li>不含：{@code slotByKey} 的临时合并表（每 tick 清空，稳态不随窗口增长）、NBT 载荷池的实体/方块实体内容物
 *       （那部分按"每 tick 只差被标记的 BE"另行计入 {@code beDiffsPerTick}）。</li>
 * </ul>
 *
 * <h2>3. 三条档位</h2>
 * <table border="1">
 *   <caption>档位 = 每秒变化量的假设（不是实测，是场景假设；实测的是单条字节数）</caption>
 *   <tr><th>档位</th><th>方块/tick</th><th>实体事件/tick</th><th>机器进度/tick</th><th>BE 差分/tick</th><th>标量/tick</th></tr>
 *   <tr><td>typical 典型基地</td><td>0.5</td><td>0.1</td><td>40</td><td>8</td><td>0.05</td></tr>
 *   <tr><td>heavy 大基地</td><td>5</td><td>1</td><td>300</td><td>60</td><td>0.2</td></tr>
 *   <tr><td>worst 满载上限</td><td colspan="5">直接按各层 {@link ShanhaiReplayConst} 的容量上限算</td></tr>
 * </table>
 */
public final class ShanhaiReplayBudget {

    /** 用户给的预算上限：16 MiB。 */
    public static final long BUDGET_BYTES = 16L * 1024L * 1024L;

    /**
     * 单条记录的字节数（实测 + 环形数组摊销；见类注释 §2 的测量条件）。
     *
     * <p>实测值（2026-11，{@code temp\replay-budget\Measure.java}，
     * 200k/20k/120k/120k/8k 条）：block 45.0 / entity 64.3 / progress 56.8 / be_diff 56.8 / scalar 144.3 B。
     * 这些数只在"估算"里用；真值改动请重跑那把测量。
     */
    public static final int BYTES_BLOCK = 45;
    public static final int BYTES_ENTITY_EVENT = 64;
    public static final int BYTES_PROGRESS = 57;
    public static final int BYTES_BE_DIFF = 57;
    public static final int BYTES_SCALAR = 144;

    /** 一条 BE 差分<b>携带的载荷</b>（每个变了的顶层 key ≈ 一个 NBT 条目 + map 槽位）。 */
    public static final int BYTES_BE_DIFF_KEY = 48;

    /**
     * 一条 BE 差分里"工作 NBT baseline"的常驻成本。
     *
     * <p>baseline 是<b>每 pos 一份</b>（不是每 tick 一份），所以它不随窗口线性增长；
     * 这里按"每台被监控机器 ≈ 1 KB"给一个上限估算。
     */
    public static final int BYTES_BE_BASELINE = 1_024;

    /** 场景档位。 */
    public record Profile(String name, double blocksPerTick, double entityEventsPerTick,
                          double progressPerTick, double beDiffsPerTick, double scalarsPerTick,
                          int beBaselines, double beKeysPerDiff) {

        public double recordsPerTick() {
            return this.blocksPerTick + this.entityEventsPerTick + this.progressPerTick
                    + this.beDiffsPerTick + this.scalarsPerTick;
        }
    }

    /**
     * 估算结果。
     *
     * @param bytes        窗口稳态常驻字节
     * @param perTickBytes 每 tick 新增字节
     * @param mib          {@code bytes} 换算成 MiB
     * @param overBudget   是否超过 {@link #BUDGET_BYTES}
     * @param degrade      降级建议（{@code overBudget=false} 时是空串）
     * @param conditions   测量条件（打印时必须带上）
     */
    public record Estimate(long bytes, long perTickBytes, double mib, boolean overBudget,
                           String degrade, String conditions) {

        public String toLine() {
            return "bytes=" + this.bytes + " per_tick_bytes=" + this.perTickBytes
                    + " mib=" + String.format(java.util.Locale.ROOT, "%.2f", this.mib)
                    + " budget_mib=16 over=" + (this.overBudget ? 1 : 0)
                    + (this.degrade.isEmpty() ? "" : " degrade=" + this.degrade)
                    + " conditions=" + this.conditions;
        }
    }

    /** 典型基地（保守：一台 GT 大基地里在跑的机器 ≈ 40 台）。 */
    public static Profile typical() {
        return new Profile("typical", 0.5D, 0.1D, 40.0D, 8.0D, 0.05D, 300, 2.0D);
    }

    /** 大基地（几百台机器 + 高频存取）。 */
    public static Profile heavy() {
        return new Profile("heavy", 5.0D, 1.0D, 300.0D, 60.0D, 0.2D, 1_500, 3.0D);
    }

    /** 满载上限：直接按各层容量上限算（此时窗口其实已经"丢最老"变短了）。 */
    public static Profile worst() {
        return new Profile("worst",
                ShanhaiReplayConst.MAX_RECORDS / (double) ShanhaiReplayConst.WINDOW_TICKS,
                ShanhaiReplayConst.MAX_ENTITY_RECORDS / (double) ShanhaiReplayConst.WINDOW_TICKS,
                ShanhaiReplayConst.MAX_PROGRESS_RECORDS / (double) ShanhaiReplayConst.WINDOW_TICKS,
                ShanhaiReplayConst.MAX_BLOCK_ENTITY_RECORDS / (double) ShanhaiReplayConst.WINDOW_TICKS,
                ShanhaiReplayConst.MAX_SCALAR_RECORDS / (double) ShanhaiReplayConst.WINDOW_TICKS,
                4_096, 4.0D);
    }

    /** 测量条件（一句话，进报告/日志）。 */
    public static String conditions() {
        return "per-record bytes measured by temp/replay-budget/Measure.java "
                + "(HotSpot 21, 64-bit, System.gc() delta / count, ring arrays included); "
                + "window=" + ShanhaiReplayConst.WINDOW_TICKS + "t; excludes NBT payload content "
                + "(counted separately as BE diffs)";
    }

    /**
     * 估一个档位。
     *
     * @param profile     场景档位
     * @param windowTicks 窗口（生产 = {@link ShanhaiReplayConst#WINDOW_TICKS}）
     */
    public static Estimate estimate(Profile profile, int windowTicks) {
        final double ticks = Math.max(1, windowTicks);
        final long perTick = (long) Math.ceil(
                profile.blocksPerTick() * BYTES_BLOCK
                        + profile.entityEventsPerTick() * BYTES_ENTITY_EVENT
                        + profile.progressPerTick() * BYTES_PROGRESS
                        + profile.beDiffsPerTick() * (BYTES_BE_DIFF + profile.beKeysPerDiff() * BYTES_BE_DIFF_KEY)
                        + profile.scalarsPerTick() * BYTES_SCALAR);
        long bytes = perTick * (long) ticks
                + (long) profile.beBaselines() * BYTES_BE_BASELINE;
        final boolean over = bytes > BUDGET_BYTES;
        String degrade = "";
        if (over) {
            // 🔴 降级只允许"降低保留内容"，**不许**改成周期快照（用户 2026-11 明确否掉）。
            final double keepRatio = (double) BUDGET_BYTES / (double) bytes;
            final int scaledProgress = (int) Math.floor(ShanhaiReplayConst.MAX_PROGRESS_RECORDS * keepRatio);
            final int scaledBe = (int) Math.floor(ShanhaiReplayConst.MAX_BLOCK_ENTITY_RECORDS * keepRatio);
            degrade = String.format(java.util.Locale.ROOT,
                    "keep_ratio=%.2f max_progress=%d max_be_diff=%d（只降保留内容，不改周期快照；窗口仍是 %d tick，满了丢最老）",
                    keepRatio, scaledProgress, scaledBe, windowTicks);
        }
        return new Estimate(bytes, perTick, bytes / 1048576.0D, over, degrade, conditions());
    }

    /** 每个档位一行（命令 / 日志 / 自检共用）。 */
    public static java.util.List<String> lines() {
        final java.util.List<String> out = new java.util.ArrayList<>(4);
        for (Profile p : new Profile[] {typical(), heavy(), worst()}) {
            out.add("budget profile=" + p.name() + " window=" + ShanhaiReplayConst.WINDOW_TICKS
                    + " records_per_tick=" + String.format(java.util.Locale.ROOT, "%.1f", p.recordsPerTick())
                    + " " + estimate(p, ShanhaiReplayConst.WINDOW_TICKS).toLine());
        }
        return out;
    }

    private ShanhaiReplayBudget() {
    }
}
