package com.shanhai.machine.creation;

/**
 * 「原初造物编译矩阵」的 <b>节奏（tick 时钟）＋ 日志聚合</b> 的<b>唯一实现</b>
 * （纯算术，<b>零</b> Minecraft / AE2 / LDLib 依赖）。
 *
 * <h2>为什么单独拆一个类（不是为了好看）</h2>
 * P5 实测暴露的核心缺陷是「<b>10 tick 不是真的</b>」：旧实现在 {@code backlog.isEmpty()} 时把
 * 周期进度清零，并且<b>不看"这一单到了多久"</b> ⇒ 在周期进度 9 时到达的单，下一 tick 就被做掉了
 * （等待 1 tick，而不是 10）。这种缺陷<b>读代码看不出来</b>（计数器看起来完全正常），
 * 只有把"到达 → 到期 → 可做"这条判定做成<b>可运行</b>的纯函数，才能离线断言。
 *
 * <p>于是本类持有三样东西，生产路径与离线自证用的是<b>同一份</b>：
 * <ol>
 *   <li><b>单调 tick 时钟</b> {@link #now()}：只增不减（旧实现会在空闲时把进度清零 ⇒ 时钟不连续）；</li>
 *   <li><b>到期判定</b> {@link #dueTickFor(long)} / {@link #isDue(long)}：
 *       到期的绝对时刻 = {@code 到达 tick + 10}，<b>只有 now &ge; due 才允许产出</b>
 *       —— 这就是"一单必须满 10 tick"的落点，不是"下一个周期边界就可以做"；</li>
 *   <li><b>每秒聚合</b>：计数 + "这一 tick 该不该打汇总行"的判据
 *       （旧实现<b>每单一行</b>，5 分钟刷 3.85 万行）。</li>
 * </ol>
 *
 * <h2>🔴 时长只有一个来源</h2>
 * 构造函数吃 {@code durationTicks}，生产路径固定传
 * {@link PrimordialCreationCompilationMatrix#TICK_DURATION}（= 10）。
 * 本类<b>没有</b>任何别的时长来源（不随等级、不随核心、不随电）。
 *
 * <h2>口径（谁负责什么）</h2>
 * <ul>
 *   <li>本类只管 <b>何时允许做</b> 与 <b>怎么记账/打日志</b>；</li>
 *   <li>一个周期能做多少份仍由 {@link CreationCraftMath#cycleBudget(long)} /
 *       {@link CreationCraftMath#craftsThisCycle(long, long)} 决定（<b>本次一个字都没改</b>）；</li>
 *   <li>份数 / 余量 / 产物放大的算术一律留在 {@link CreationCraftMath}（同样未改）。</li>
 * </ul>
 */
public final class CreationPacing {

    /** 一秒钟多少 tick（原版 20）。只用于"每秒最多一行汇总"的限流判据。 */
    public static final int DEFAULT_TICKS_PER_SECOND = 20;

    /** 一个周期多少 tick（生产路径传 {@link PrimordialCreationCompilationMatrix#TICK_DURATION}）。 */
    private final int durationTicks;

    /** 一秒多少 tick（离线自证会显式传，生产恒为 {@value #DEFAULT_TICKS_PER_SECOND}）。 */
    private final int ticksPerSecond;

    /** <b>单调</b> tick 时钟：从 0 起，每 {@link #advance()} 一次 +1，<b>永不回退/清零</b>。 */
    private long nowTick;

    /** 刚刚过去的这一 tick 是不是周期边界（{@link #advance()} 的返回值也存一份）。 */
    private boolean cycleBoundary;

    // ── 本秒聚合（每 20 tick 归零一次） ──
    private long arrivalThisSecond;
    private long ordersThisSecond;
    private long craftsThisSecond;
    private long itemsThisSecond;

    // ── 累计（全程只增） ──
    private long totalArrival;
    private long totalOrders;
    private long totalCrafts;
    private long totalItems;
    private long totalSeconds;
    private long totalSummaryLines;

    /** 生产路径用它（时长 = 控制器那个常量，一秒 = 20 tick）。 */
    public CreationPacing(int durationTicks) {
        this(durationTicks, DEFAULT_TICKS_PER_SECOND);
    }

    /** 离线自证用（可以显式给 ticksPerSecond，便于把"5 分钟"压成几行断言）。 */
    public CreationPacing(int durationTicks, int ticksPerSecond) {
        if (durationTicks <= 0) {
            throw new IllegalArgumentException("durationTicks 必须 > 0（收到 " + durationTicks + "）");
        }
        if (ticksPerSecond <= 0) {
            throw new IllegalArgumentException("ticksPerSecond 必须 > 0（收到 " + ticksPerSecond + "）");
        }
        this.durationTicks = durationTicks;
        this.ticksPerSecond = ticksPerSecond;
    }

    // ═════════════════════════════ 时钟 ═════════════════════════════

    /** 当前 tick（单调）。 */
    public long now() {
        return nowTick;
    }

    /** 本类吃到的"一周期多少 tick"（= 控制器那个常量的副本，只读）。 */
    public int durationTicks() {
        return durationTicks;
    }

    /**
     * 推进一 tick。
     *
     * @return {@code true} = 这一 tick 恰好是<b>周期边界</b>（每 {@code durationTicks} 一次）
     */
    public boolean advance() {
        nowTick++;
        this.cycleBoundary = nowTick % durationTicks == 0L;
        return this.cycleBoundary;
    }

    /** 上一次 {@link #advance()} 是不是周期边界（给"先算别的、后跑周期"的写法用）。 */
    public boolean isCycleBoundary() {
        return cycleBoundary;
    }

    /** 本周期已过的 tick 数：{@code 0..durationTicks-1}（0 = 刚跑完一个周期）。 */
    public int cycleProgress() {
        return (int) (nowTick % durationTicks);
    }

    /** 现在是不是"整秒"（每 {@code ticksPerSecond} tick 一次，给汇总日志用）。 */
    public boolean isSecondBoundary() {
        return nowTick % ticksPerSecond == 0L;
    }

    // ═════════════════════════════ 到期判定（"必须满 10 tick"的落点） ═════════════════════════════

    /** 到达于 {@code arrivalTick} 的那一单最早什么时候允许产出 = {@code arrivalTick + durationTicks}。 */
    public long dueTickFor(long arrivalTick) {
        return CreationCraftMath.saturatedAdd(arrivalTick, durationTicks);
    }

    /**
     * 这一单现在允许产出吗？
     *
     * <p>🔴 判据是<b>绝对到期时刻</b>，不是"下一个周期边界"：所以"在边界前 1 tick 到达"的单
     * 不会被下一个边界顺手做掉（那是旧实现的缺陷）。
     */
    public boolean isDue(long dueTick) {
        return nowTick >= dueTick;
    }

    /** 还差几 tick 到期（&lt;= 0 = 已到期；界面/Jade 显示用）。 */
    public long ticksUntilDue(long dueTick) {
        return dueTick - nowTick;
    }

    // ═════════════════════════════ 记账（每秒聚合） ═════════════════════════════

    /** 收下一单（{@code pushPattern} 里调）。 */
    public void noteArrival() {
        this.arrivalThisSecond = CreationCraftMath.saturatedAdd(arrivalThisSecond, 1L);
        this.totalArrival = CreationCraftMath.saturatedAdd(totalArrival, 1L);
    }

    /**
     * 一个周期做完之后的记账。
     *
     * @param orders 本周期做完的<b>单数</b>（一单可能一次做完，也可能跨周期）
     * @param crafts 本周期做完的<b>份数</b>
     * @param items  本周期插进网络的<b>产物总个数</b>（多产物累加）
     */
    public void recordCycle(long orders, long crafts, long items) {
        this.ordersThisSecond = CreationCraftMath.saturatedAdd(ordersThisSecond, Math.max(0L, orders));
        this.craftsThisSecond = CreationCraftMath.saturatedAdd(craftsThisSecond, Math.max(0L, crafts));
        this.itemsThisSecond = CreationCraftMath.saturatedAdd(itemsThisSecond, Math.max(0L, items));
        this.totalOrders = CreationCraftMath.saturatedAdd(totalOrders, Math.max(0L, orders));
        this.totalCrafts = CreationCraftMath.saturatedAdd(totalCrafts, Math.max(0L, crafts));
        this.totalItems = CreationCraftMath.saturatedAdd(totalItems, Math.max(0L, items));
    }

    /** 本秒有没有"值得打一行"的活动（收单 / 完成 / 产物 任一非 0）。 */
    public boolean hasActivityThisSecond() {
        return arrivalThisSecond > 0L || ordersThisSecond > 0L || craftsThisSecond > 0L || itemsThisSecond > 0L;
    }

    /**
     * 结算这一秒：累计秒数 + 累计汇总行数 + 清理本秒计数。
     *
     * <p>🔴 <b>调用方必须只在真的要打这一行时调</b>（{@code logSummary} 里已经这么做了）：
     * 这样"汇总行数 ≤ 秒数"是<b>结构上</b>成立的，而不是靠限流碰巧成立。
     */
    public void endSecond() {
        this.totalSeconds = CreationCraftMath.saturatedAdd(totalSeconds, 1L);
        this.arrivalThisSecond = 0L;
        this.ordersThisSecond = 0L;
        this.craftsThisSecond = 0L;
        this.itemsThisSecond = 0L;
    }

    /** 记一次"真的打了一行汇总"（给自证用：断言 汇总行数 ≤ 秒数）。 */
    public void noteSummaryLine() {
        this.totalSummaryLines = CreationCraftMath.saturatedAdd(totalSummaryLines, 1L);
    }

    public long arrivalThisSecond() {
        return arrivalThisSecond;
    }

    public long ordersThisSecond() {
        return ordersThisSecond;
    }

    public long craftsThisSecond() {
        return craftsThisSecond;
    }

    public long itemsThisSecond() {
        return itemsThisSecond;
    }

    public long totalArrival() {
        return totalArrival;
    }

    public long totalOrders() {
        return totalOrders;
    }

    public long totalCrafts() {
        return totalCrafts;
    }

    public long totalItems() {
        return totalItems;
    }

    public long totalSeconds() {
        return totalSeconds;
    }

    public long totalSummaryLines() {
        return totalSummaryLines;
    }

    // ═════════════════════════════ 汇总行的唯一格式 ═════════════════════════════

    /**
     * <b>每秒汇总行的唯一格式</b>（生产路径与离线自证共用）。
     *
     * <p>形如：
     * <pre>[SHANHAI-CREATION] pos=dim@1,2,3 本秒 接 6 单 / 完成 60 单 / 产物 60 ｜ 累计 单 38528 / 产物 38528
     *  ｜ 并行=64 ｜ 队列=0 单（欠 0 份）｜ 进度 4/10 ｜ mode=normal</pre>
     *
     * @param prefix   日志前缀（{@code [SHANHAI-CREATION]}）
     * @param pos      位置标签
     * @param pacing   取"本秒/累计"的那一份计数
     * @param parallel 并行上限的可读串（{@code "infinity"} / 具体数字 / {@code "?"}）
     * @param queue    队列里还有几单
     * @param owed     队列里还欠几份
     * @param progress 本周期已过 tick 数（0..duration-1）
     * @param mode     模式串（{@code "normal"} / {@code "infinity"}）
     */
    public static String summaryLine(String prefix, String pos, CreationPacing pacing, String parallel,
                                     int queue, long owed, int progress, String mode) {
        final int duration = pacing == null ? 0 : pacing.durationTicks;
        return prefix + " pos=" + pos
                + " 本秒 接 " + (pacing == null ? 0L : pacing.arrivalThisSecond())
                + " 单 / 完成 " + (pacing == null ? 0L : pacing.ordersThisSecond())
                + " 单 / 产物 " + (pacing == null ? 0L : pacing.itemsThisSecond())
                + " ｜ 累计 单 " + (pacing == null ? 0L : pacing.totalOrders())
                + " / 产物 " + (pacing == null ? 0L : pacing.totalItems())
                + " ｜ 并行=" + parallel
                + " ｜ 队列=" + queue + " 单（欠 " + owed + " 份）"
                + " ｜ 进度 " + progress + "/" + duration
                + " ｜ mode=" + mode;
    }
}
