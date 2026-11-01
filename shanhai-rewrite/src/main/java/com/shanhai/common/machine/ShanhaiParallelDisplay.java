package com.shanhai.common.machine;

import java.util.Locale;

/**
 * 🔴 <b>「并行数怎么显示」与「并行数被谁钳住」这两件事的纯算术核（只 import {@code java.*}）。</b>
 *
 * <p>本工程惯例（见 {@code ShanhaiParallelBudget}）：判据做成纯函数 ⇒ 既能在游戏里跑，
 * 也能被一个裸 {@code javac}/{@code java}（无 classpath、无 Minecraft）单独编译并驱动
 * ⇒ 离线判据与生产实现<b>驱动同一份字节码</b>，不可能漂移。
 *
 * <h2>本类管的两件事（本轮 2026-11 新增）</h2>
 * <ol>
 *   <li><b>显示口径</b>（{@link #isInfiniteParallel(long)} / {@link #format(long)} /
 *       {@link #jadeParallel(long)}）：并行值是 {@code long}，而 Jade / gtladditions 的
 *       「同时处理至多 N 个配方」那一行只认两种语义 —— 正数画真值、{@code -114514} 画彩虹「无限」。
 *       ⇒ 这里给出<b>唯一一处</b>阈值判定与三种显示形态，机器侧（{@code PrimordialOmegaEngineMachine}、
 *       {@code PrimordialModuleMachine}）只做委托，不再各写一遍 {@code Long.MAX_VALUE / 2}。</li>
 *   <li><b>「并行被谁钳住」的三分诊断</b>（{@link #dummyPickLine}）：
 *       {@code in}（输入量）/ {@code out}（输出空间）/ {@code budget}（预算）三者取最小，
 *       外加 {@code perItem / perFluid}（<b>每份配方要吃多少</b>）——
 *       后者是为了让「可用量 ÷ 每份需求」这个除法在日志里直接可见，
 *       而不是让读日志的人自己去猜 702,470 是怎么来的。</li>
 * </ol>
 *
 * <h2>阈值为什么是 {@code Long.MAX_VALUE / 2}（= 4611686018427387903）</h2>
 * <p>[源码原文] 老山海 {@code PrimordialOmegaEngineModuleBase#addParallelDisplay}：
 * <pre>
 *   boolean isInfinite = parallel &gt;= Long.MAX_VALUE / 2;
 * </pre>
 * 本工程三处显示（机器 GUI / Jade / 物品 tooltip）从 2026-09-26 起就共用这一个阈值
 * （原先定义在 {@code PrimordialModuleMachine#isInfiniteParallel(long)}，本轮把<b>实现</b>
 * 搬到本类、那里改一行委托 —— 取值与行为逐字不变）。
 *
 * <h2>⚠️ 为什么不能是 {@code >= Integer.MAX_VALUE}</h2>
 * 那会把 {@code 2147483647}（= 原生链 int 天花板，也是并行表前 14 档里最上面那档）
 * <b>误画成「无限」</b>。本轮判据含一条负面对照专门盯这件事：
 * 把阈值改成 {@code Integer.MAX_VALUE} 后，{@code 2048} 与 {@code 2147483647} 两档
 * <b>必须</b>报红（见 {@code temp/infinity2b/check/DisplayCheck.java}）。
 */
public final class ShanhaiParallelDisplay {

    /**
     * 「无限」阈值 = {@code Long.MAX_VALUE / 2} = {@code 4611686018427387903}（= 2^62−1）。
     *
     * <p>它同时是并行表第 15 档（{@code wzcz3}）的值，所以玩家把物质模块堆到那一档时
     * 显示就会变成「无限」—— 这是<b>老山海口径</b>，不是本工程新定的。
     */
    public static final long INFINITE_THRESHOLD = Long.MAX_VALUE / 2;

    /**
     * gtladditions 自己约定的「无限」哨兵 {@code -114514}
     * （[源码原文] {@code ParallelProviderMixin} 里 {@code private static final long INFINITY_FEATURE = -114514L;}）。
     *
     * <p>🔴 <b>它是【显示通道】的值，不是并行数。</b> 写进 Jade 的服务端 NBT
     * {@code parallel} 键之后，gtladditions 的 {@code appendTooltip} 会画彩虹「无限」。
     * <p>⚠️ <b>绝对不能</b>让它进入任何乘进预算的表达式（历史上踩过一次：
     * {@code (long) getMaxParallel() * getMultipleThreads()} 会得到负数 ⇒ 贪心分配
     * {@code if (remain <= 0L) break;} ⇒ 机器静默停摆）。本类的三条出口都不参与运算。
     */
    public static final int INFINITE_SENTINEL = -114514;

    private ShanhaiParallelDisplay() {}

    /**
     * <b>「无限」判定的唯一一处实现</b> —— {@code p >= Long.MAX_VALUE / 2}。
     *
     * <p>三处显示（机器 GUI / Jade / 物品 tooltip）与 Jade 取值映射都调它。
     */
    public static boolean isInfiniteParallel(long parallel) {
        return parallel >= INFINITE_THRESHOLD;
    }

    /**
     * 并行数的<b>显示文案</b>：{@code 无限} 或 {@code 千分位十进制}。
     *
     * <p>逐字沿用 {@code PrimordialModuleMachine#formatParallel(long)} 改前那一版
     * （{@code String.format(Locale.ROOT, "%,d", parallel)}）⇒ 输出与老行为<b>逐字相同</b>。
     */
    public static String format(long parallel) {
        return isInfiniteParallel(parallel) ? "无限" : String.format(Locale.ROOT, "%,d", parallel);
    }

    /**
     * <b>Jade「parallel」键该写什么</b>（{@code int} 口径；机器侧 {@code getMaxParallel()} 的返回值）。
     *
     * <pre>
     *   并行值 &lt;  Long.MAX_VALUE / 2  ⇒  Ints.saturatedCast(并行值)      ← 与改动前逐位相同
     *   并行值 &gt;= Long.MAX_VALUE / 2  ⇒  -114514（「无限」哨兵）        ← 本轮新增，只在无限档生效
     * </pre>
     *
     * <h2>为什么无限档要返回负数（而不是继续给 2147483647）</h2>
     * Jade 那一行的取值链是
     * {@code ShanhaiInfiniteThreadDisplayMixin.appendServerData → machine.getMaxParallel()}，
     * 而那个方法<b>编译期就是 int</b>（{@code gtlcore ParallelMachine} 的抽象方法签名）——
     * 它<b>装不下</b> {@code 9223372036854775807}。把 long 预算塞进 int 通道的唯一两种可能：
     * ① 饱和成 2147483647（= 用户截图里那个数，也就是这次要消灭的东西）；
     * ② 写 gtladditions 自己的「无限」哨兵（= 本次选择）。
     * <p>⚠️ <b>这个负数只允许出现在「预算本来就是无限」的状态下</b>，而那个状态下引擎走的是
     * {@code PrimordialEngineRecipeLogic} 的 long 分支（{@code limit > Integer.MAX_VALUE}），
     * <b>根本不会退回父类</b>去读 {@code getMaxParallel() × T} ⇒ 历史那次静默停机的路径<b>结构上不存在</b>。
     * 两个已知的上游读者也各有护栏（{@code BatchProcessing.isCustomSubTickParallelized} 用
     * {@code Math.max(1L, …)} 夹住；gtlcore {@code MultipleRecipesLogic} 不是本主机的配方逻辑）。
     * 完整读者清单与判据见 {@code PrimordialOmegaEngineMachine#getMaxParallel()} 的 javadoc。
     */
    public static int jadeParallel(long parallel) {
        return isInfiniteParallel(parallel) ? INFINITE_SENTINEL : saturatedCast(parallel);
    }

    /** {@code Ints.saturatedCast(long)} 的零依赖等价实现（本类只许 import {@code java.*}，不引 Guava）。 */
    public static int saturatedCast(long value) {
        if (value > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (value < Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        return (int) value;
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════
    //  三分诊断的第二份：「可用量 ÷ 每份需求」与 pmax-dummy 行
    // ═══════════════════════════════════════════════════════════════════════════════════════

    /** 「读不到」的占位（{@code -1}）—— 与 {@code pmax} 行同口径，绝不拿 {@code 0} 冒充"没有空间"。 */
    public static final long UNKNOWN = -1L;

    /**
     * <b>{@code floor(可用量 ÷ 每份需求)} —— 「输入量能养出多少份并行」的唯一一处实现。</b>
     *
     * <p>逐字同源：gtlcore {@code IParallelLogic#limitItemAmount} 一族都是这个形状
     * （{@code 可用量 / 单份量}，再与调用方传进来的上限取 {@code Math.min}）。
     * 本方法只做<b>一条</b>除法，不做 min —— min 由调用方（{@code IParallelLogic.getMaxParallel}）做。
     *
     * @param available 实际可用量（物品个数 / 流体 mB）；{@code <= 0} ⇒ 0 份
     * @param perCopy   每份配方要吃多少；{@code <= 0} ⇒ 该条目对并行<b>没有约束</b>（返回
     *                  {@link #UNKNOWN}，让上层不要拿它去比较）
     */
    public static long pickFromStock(long available, long perCopy) {
        if (perCopy <= 0L) {
            return UNKNOWN;
        }
        if (available <= 0L) {
            return 0L;
        }
        // 整数除法就是数学上的 floor；两个操作数都 ≥ 0 ⇒ 商 ≤ available ≤ Long.MAX ⇒ 不会溢出。
        return available / perCopy;
    }

    /**
     * <b>瓶颈判定：三者取最小，返回是谁。</b>
     *
     * <pre>
     *   in / out 为 {@link #UNKNOWN}（= 没算/读不到）时【不参与比较】—— 绝不把 -1 当成"上限很小"；
     *   pick == budget 且 in/out 都不比预算小 ⇒ {@code "budget"}（"没被任何东西钳住"）；
     *   其余平手时优先报 {@code in}（与 pmax 行的既有口径一致）。
     * </pre>
     *
     * @param pick 真正进分配的份数；{@code < 0} = 不做"pick 顶到预算"这一步特别判定
     * @return {@code "in"} / {@code "out"} / {@code "budget"}
     */
    public static String whyLimited(long in, long out, long budget, long pick) {
        final long i = in < 0L ? Long.MAX_VALUE : in;
        final long o = out < 0L ? Long.MAX_VALUE : out;
        final long b = budget < 0L ? Long.MAX_VALUE : budget;
        if (pick >= 0L && pick == b && b <= i && b <= o) {
            return "budget";
        }
        if (i <= o && i <= b) {
            return "in";
        }
        if (o <= b) {
            return "out";
        }
        return "budget";
    }

    /**
     * <b>「明显没吃满预算」</b>：{@code pick <= budget / 2}。
     *
     * <p>阈值与 {@code pmax} 行逐字相同（{@code ShanhaiParallelDiag#obviousBelowBudget}）——
     * 两个诊断若各定一个阈值，读日志的人就无法横向对比。{@code pick == budget}（预算就是瓶颈）⇒
     * <b>不算</b>"明显没吃满"，不落盘。
     */
    public static boolean obviousBelowBudget(long pick, long budget) {
        if (budget <= 0L || pick <= 0L) {
            return false;
        }
        return pick <= budget / 2L;
    }

    /**
     * 限流用的<b>数量级桶</b>（2 的幂次），{@code <= 0} ⇒ 0。
     *
     * <p>为什么压桶：主机一轮可能有几百条候选配方，精确数字各不相同 ⇒ 若拿精确数字做限流键，
     * 每一轮都会"形态变化"⇒ 每轮都打一行 ⇒ 刷屏。压成 2 的幂次后，几百条只落在几个桶里。
     */
    public static long bucket(long value) {
        if (value <= 0L) {
            return 0L;
        }
        return Long.highestOneBit(value);
    }

    /**
     * <b>{@code pmax-dummy} 那一行的正文（任务 B：星律 dummy / 无线假配方那条路）。</b>
     *
     * <p>行形（任务书给死，一个字段都不许少）：
     * <pre>
     *   [SHANHAI-INFPAR] pmax-dummy machine=&lt;id&gt; in=&lt;…&gt; out=&lt;…&gt; budget=&lt;…&gt;
     *                    pick=&lt;…&gt; why=&lt;in|out|budget&gt; perItem=&lt;…&gt; perFluid=&lt;…&gt;
     * </pre>
     * <ul>
     *   <li>{@code in / out} = {@code -1} 表示当时没算（{@code in <= 1} 时上游不跑输出那一步，
     *       与 {@code pmax} 行同一纪律：绝不拿 {@code in} 冒充 {@code out}）；</li>
     *   <li>{@code pick} = 真正进分配的那个份数（{@code ParallelData.parallels} 里的值）；</li>
     *   <li>{@code perItem / perFluid} = <b>每份配方要吃多少</b>物品 / 流体（{@code -1} = 该配方没有这一类输入）。
     *       <b>这一格是本轮新增的</b>：{@code pick} 几乎总是 {@code floor(可用量 ÷ 每份需求)}，
     *       有了分母就能一眼反推"是料不够，还是预算不够"。</li>
     * </ul>
     */
    public static String dummyPickLine(String machineId, long in, long out, long budget, long pick,
                                       long perItem, long perFluid) {
        return "[SHANHAI-INFPAR] pmax-dummy machine=" + (machineId == null ? "?" : machineId)
                + " in=" + in
                + " out=" + out
                + " budget=" + budget
                + " pick=" + pick
                + " why=" + whyLimited(in, out, budget, pick)
                + " perItem=" + perItem
                + " perFluid=" + perFluid;
    }
}
