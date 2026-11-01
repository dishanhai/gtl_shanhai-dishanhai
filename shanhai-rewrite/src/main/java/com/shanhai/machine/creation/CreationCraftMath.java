package com.shanhai.machine.creation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「原初造物编译矩阵」接单算术的<b>唯一实现</b>（纯算术，<b>零</b> Minecraft / AE2 / LDLib 依赖）。
 *
 * <h2>为什么单独拆一个类（不是为了好看）</h2>
 * 这台机器的核心风险全在算术上（份数、余量、并行上限、多产物放大），而算术一旦算错，
 * 后果是<b>吞料</b>或<b>刷物品</b>——两种都是无法靠"读代码"证明的。所以把算术抽成一个
 * <b>不引用任何游戏类</b>的 static 工具类：
 * <ul>
 *   <li>生产路径（{@link CreationCraftExecutor}）<b>只调它</b>，不自己再写一份算式；</li>
 *   <li>离线自证可以直接 {@code javac CreationCraftMath.java} + {@code java} 跑
 *       （不需要整个 Forge 运行时，见 {@code temp/molmanip/selftest}），
 *       这样"份数/余量/无限模式/多产物"四条判据能<b>真的被运行过</b>，而不是"看着对"。</li>
 * </ul>
 *
 * <h2>算术口径（用户拍板，逐条对应本类的方法）</h2>
 * <pre>
 *   对每一样输入 key：份数_i = 账目[key] / 单份需求[key]        （整数除法）
 *   份数 = min(份数_1, 份数_2, …)                              ⇒ 0 = 本单做不了 ⇒ 拒单（AE 自己退回）
 *   余量 = 账目 - 份数 × 单份需求                               ⇒ 非 0 ⇒ 用 MEStorage#insert 退回网络（不吞）
 *   每周期份数上限 = 8^物质模块等级（等级 ≥ 15 ⇒ 哨兵 = 无限 ⇒ 一次全做完）
 *   产物 = 样板产物 × 本次份数 × 主机增产倍率                     （多产物<b>全部</b>出，不只第 0 个）
 * </pre>
 * 🔴 上游（gtlcore 端口）用整数除法<b>截断就白扣了余量</b>，本类把"余量"显式算出来交给调用方退回
 * —— 这是用户点名要修的两条之一（另一条是"多产物只出第一个"）。
 *
 * <h2>本类<b>不</b>做的事（红线）</h2>
 * <ul>
 *   <li><b>不扣料、不返还原料</b>：料是 AE 侧在 {@code pushPattern} 之前就扣掉的（见
 *       {@code temp/molmanip/材料归属.md}）⇒ 这里只有"账目换算"，没有任何物品 I/O。</li>
 *   <li>不含 {@code return false} 的决策：拒单时<b>一个字节都不许</b>动账目（AE 会原路退回），
 *       ⇒ 调用方必须把 {@link #craftsOf} 的 0 当成"什么都别做"。</li>
 * </ul>
 */
public final class CreationCraftMath {

    /** 并行的底数 k = 8：并行 = k^物质模块等级。 */
    public static final long PARALLEL_BASE_K = 8L;

    /** 「无限」起点：物质模块等级 ≥ 15 ⇒ 不算 k^等级，直接给哨兵。 */
    public static final int INFINITE_PARALLEL_FROM_LEVEL = 15;

    /**
     * 「无限」哨兵 = {@link Long#MIN_VALUE}（与上游 {@code MolecularAssemblerMultiblockMachine.kt}
     * 传给 {@code extractGTRecipe} 的那个字面量逐字同款）。
     *
     * <p>⚠️ 它<b>不可能</b>是合法并行数（负数）⇒ 安全；但反过来说，<b>任何算术都必须先判它</b>
     * （把它当数字乘/加会得到垃圾）。判据统一走 {@link #isInfinite(long)}。
     */
    public static final long INFINITE_PARALLEL_SENTINEL = Long.MIN_VALUE;

    private CreationCraftMath() {
    }

    // ═════════════════════════════ 并行上限 ═════════════════════════════

    /**
     * 并行 = {@code k^物质模块等级}；等级 ≥ {@value #INFINITE_PARALLEL_FROM_LEVEL} ⇒ {@link #INFINITE_PARALLEL_SENTINEL}。
     *
     * <p>边界（逐条写清，省得后来者再猜）：
     * <ul>
     *   <li>等级 ≤ 0（空槽 / 无模块 / 还没扫到槽）⇒ <b>1</b>（{@code 8^0 = 1} 的字面结果；P1 已定，任务书要求别改）；</li>
     *   <li>1 ≤ 等级 ≤ 14 ⇒ {@code 8^等级}（最大 8^14 ≈ 4.4e12，远在 long 内）；</li>
     *   <li>等级 ≥ 15 ⇒ 哨兵（不给具体数字）。</li>
     * </ul>
     * <p>饱和保护保留：万一 k 或阈值被改到会溢出，返回 {@link Long#MAX_VALUE} 而不是负数
     * （负数会被误认成哨兵）。
     */
    public static long parallelForMatterLevel(int matterModuleLevel) {
        if (matterModuleLevel >= INFINITE_PARALLEL_FROM_LEVEL) {
            return INFINITE_PARALLEL_SENTINEL;
        }
        if (matterModuleLevel <= 0) {
            return 1L;
        }
        long result = 1L;
        for (int i = 0; i < matterModuleLevel; i++) {
            if (result > Long.MAX_VALUE / PARALLEL_BASE_K) {
                return Long.MAX_VALUE; // 饱和：绝不返回负数（负数 == 哨兵语义，会出事）
            }
            result *= PARALLEL_BASE_K;
        }
        return result;
    }

    /** 这个并行上限是不是「无限」（上游语义：哨兵 {@code Long.MIN_VALUE}）。 */
    public static boolean isInfinite(long parallelCap) {
        return parallelCap == INFINITE_PARALLEL_SENTINEL;
    }

    /**
     * 一个 10 tick 周期里最多能做多少份：<b>无限模式 ⇒ 全做</b>（返回 {@link Long#MAX_VALUE}
     * 当预算，实际份数由 {@link #craftsThisCycle(long, long)} 与积压取 min）。
     *
     * <p>⚠️ 本方法<b>只</b>把哨兵翻成预算，不做别的判断（负数/0 的普通值原样返回，
     * 由调用方的 {@code budget <= 0 ⇒ 本轮不跑} 兜住）。
     */
    public static long cycleBudget(long parallelCap) {
        if (isInfinite(parallelCap)) {
            return Long.MAX_VALUE;
        }
        return parallelCap;
    }

    /**
     * 本周期对某一单做多少份 = {@code min(这一单还欠的份数, 本周期剩余预算)}。
     *
     * <p>这就是"跨配方并行 = 合并"的落点：多张不同样板的积压<b>共用一个</b>预算，
     * 谁的份数先被预算吃完就留到下一个周期（不丢，欠账继续记在 {@code PendingOrder} 上）。
     */
    public static long craftsThisCycle(long craftsOwed, long budgetLeft) {
        final long owed = Math.max(0L, craftsOwed);
        final long budget = Math.max(0L, budgetLeft);
        return Math.min(owed, budget);
    }

    // ═════════════════════════════ 份数 / 余量 ═════════════════════════════

    /**
     * 份数 = {@code min over inputs (账目 / 单份需求)}，<b>整数除法</b>。
     *
     * <p>返回 {@code 0} 的含义（调用方必须当成"本单做不了 ⇒ {@code pushPattern} 返回 false"）：
     * <ul>
     *   <li>某一样输入的账目 <b>不足一份</b>（{@code 账目 < 单份需求}）；</li>
     *   <li>单份需求表为空或某一项 ≤ 0（≤ 0 的除法没有意义）；</li>
     *   <li>账目里<b>没有</b>某一样输入（= 解析时没匹配上，等价于 0）。</li>
     * </ul>
     * 🔴 这里<b>绝不</b>返回"截断后的部分余量"：余量由 {@link #leftoversOf} 单独算，调用方负责退回。
     */
    public static <K> long craftsOf(Map<K, Long> accounted, Map<K, Long> perCraftRequired) {
        return craftsOf(accounted, perCraftRequired, null);
    }

    /**
     * 同 {@link #craftsOf(Map, Map)}，但<b>排除"每批只抽 1 个"的输入</b>再取 min。
     *
     * <h2>🔴 为什么需要这个重载（P5 自动扩批的前提）</h2>
     * 挂上 {@code IAutoExpandSettings} 之后，gtlcore 的 {@code CraftingCpuLogicMixin} 会把"整单剩余 N 份"
     * <b>合并成一次 {@code pushPattern}</b>，而它抽料用的是
     * {@code AEUtils#extractForProcessingPattern(details, inv, expectedOutputs, operations)}（原文）：
     * <pre>
     *   long effectiveMultiplier = isIntegratedCircuit(key) ? 1L : multiplier;   // ← 集成电路每批只抽 1 个
     *   long amount = inputs[x].getMultiplier() * effectiveMultiplier;
     *   ...
     *   for (GenericStack output : originDetail.getOutputs())
     *       expectedOutputs.add(output.what(), output.amount() * multiplier);     // ← AE 在等 N 倍产物
     * </pre>
     * ⇒ 账目里集成电路只有 1 个、其余是 N 个。如果这里对<b>全部</b>输入取 min，份数会被算成 <b>1</b>，
     * 剩下的 N-1 份帐目会被当成"余量"退回去；而 AE 等的是 N 倍产物 ⇒
     * <b>任务永久挂死（WAITING_FOR_INPUTS），一个东西都出不来</b>。
     *
     * @param batchConstant 每批只抽 1 个的那类输入（调用方用
     *                      {@code AEUtils#isIntegratedCircuit} 的同款判据挑出来；{@code null}/空 = 不排除）
     * @return 份数；<b>参与取 min 的输入一个都没有</b>时（例如整块样板只由集成电路构成）
     *         退回"对全部输入取 min"的老语义，绝不返回 {@link Long#MAX_VALUE}
     */
    public static <K> long craftsOf(Map<K, Long> accounted, Map<K, Long> perCraftRequired,
                                    Set<K> batchConstant) {
        if (perCraftRequired == null || perCraftRequired.isEmpty()) {
            return 0L; // 单份需求解析失败 / 样板无输入 ⇒ 算不出份数
        }
        if (accounted == null || accounted.isEmpty()) {
            return 0L;
        }
        long crafts = Long.MAX_VALUE;
        int counted = 0;
        for (Map.Entry<K, Long> entry : perCraftRequired.entrySet()) {
            final K key = entry.getKey();
            if (batchConstant != null && batchConstant.contains(key)) {
                continue; // 每批只抽 1 个 ⇒ 它不携带"N"这个信息，不参与取 min
            }
            final long need = entry.getValue() == null ? 0L : entry.getValue();
            if (need <= 0L) {
                return 0L; // 单份需求非正 ⇒ 算术无意义 ⇒ 拒单（绝不拿它去除）
            }
            final Long haveBoxed = accounted.get(key);
            final long have = haveBoxed == null ? 0L : haveBoxed;
            final long craftsForThisInput = have / need; // 整数除法（向下取整）
            if (craftsForThisInput <= 0L) {
                return 0L;
            }
            if (craftsForThisInput < crafts) {
                crafts = craftsForThisInput;
            }
            counted++;
        }
        if (counted == 0) {
            // 全部输入都是"每批只抽 1 个"的那类 ⇒ 取 min 拿不到任何信息 ⇒ 退回老语义（对全部输入取 min）
            return craftsOf(accounted, perCraftRequired);
        }
        return crafts == Long.MAX_VALUE ? 0L : crafts;
    }

    /**
     * 余量 = {@code 账目 - 份数 × 单份需求}，<b>逐 key 全算</b>（含"账目里有、单份需求里没有"的 key：
     * 那些 key 这一单<b>一份都不用</b>，整份都是余量）。
     *
     * <p>只返回<b>正数</b>项（0 与负数不进表）。🔴 调用方必须把它 {@code MEStorage#insert} 回 AE 网络
     * —— 上游就是在这里"截断就白扣"，用户点名要修。
     */
    public static <K> Map<K, Long> leftoversOf(Map<K, Long> accounted,
                                               Map<K, Long> perCraftRequired,
                                               long crafts) {
        final Map<K, Long> leftovers = new LinkedHashMap<>();
        if (accounted == null || accounted.isEmpty()) {
            return leftovers;
        }
        final long safeCrafts = Math.max(0L, crafts);
        for (Map.Entry<K, Long> entry : accounted.entrySet()) {
            final K key = entry.getKey();
            if (key == null) {
                continue;
            }
            final long have = entry.getValue() == null ? 0L : Math.max(0L, entry.getValue());
            final Long needBoxed = perCraftRequired == null ? null : perCraftRequired.get(key);
            final long need = needBoxed == null ? 0L : Math.max(0L, needBoxed);
            final long used = saturatingMultiply(need, safeCrafts);
            final long left = have - used;
            if (left > 0L) {
                leftovers.put(key, left);
            }
        }
        return leftovers;
    }

    // ═════════════════════════════ 产物放大（多产物全出） ═════════════════════════════

    /**
     * 单一样产物的一次产出量 = {@code 样板产物数量 × 本次份数 × 主机增产倍率}（饱和到 {@link Long#MAX_VALUE}）。
     *
     * <p>倍率 ≤ 0 一律<b>按 1</b> 处理：「宁可少乘，不许把货算没了」。
     * （正常路径倍率恒 ≥ 1：P10 起它就是"主机增产"，主机给 0 ⇒ 1 ⇒ 与改动前逐位相同。）
     */
    public static long scaleOutput(long perCraftAmount, long crafts, int multiplier) {
        final long amount = Math.max(0L, perCraftAmount);
        final long safeCrafts = Math.max(0L, crafts);
        final long safeMultiplier = Math.max(1, multiplier);
        return saturatingMultiply(saturatingMultiply(amount, safeCrafts), safeMultiplier);
    }

    /**
     * <b>一次出货的完整产物明细</b>：每一样产物都按 {@code 单份数量 × 份数 × 倍率} 放大。
     *
     * <p>🔴 这就是"多产物全部出"的落点：上游端口只取 {@code getOutputs()[0]}（见
     * {@code temp/molmanip/材料归属.md} §7 坑 A），本方法<b>遍历全部</b>产物，
     * 同一个 key 出现多次（样板自己写了两个同 key 产物）时累加。
     *
     * @param perCraft      单份产物表（key 可以是任意类型，生产路径传 {@code AEKey}）
     * @param crafts        本次份数
     * @param multiplier    主机增产倍率（≤ 0 按 1）
     * @return 按输入顺序排好的 产物 → 数量（只含正数项；份数为 0 ⇒ 空表）
     */
    public static <K> Map<K, Long> outputsOf(List<Product<K>> perCraft, long crafts, int multiplier) {
        final Map<K, Long> outs = new LinkedHashMap<>();
        if (perCraft == null || perCraft.isEmpty() || crafts <= 0L) {
            return outs;
        }
        for (Product<K> product : perCraft) {
            if (product == null || product.key == null) {
                continue;
            }
            final long amount = scaleOutput(product.amount, crafts, multiplier);
            if (amount <= 0L) {
                continue;
            }
            final Long previous = outs.get(product.key);
            outs.put(product.key, previous == null ? amount : saturatedAdd(previous, amount));
        }
        return outs;
    }

    // ═════════════════════════════ 饱和算术（绝不许回绕成负数） ═════════════════════════════

    /** {@code a × b}，溢出则 {@link Long#MAX_VALUE}（两个入参都按非负处理）。 */
    public static long saturatingMultiply(long a, long b) {
        final long x = Math.max(0L, a);
        final long y = Math.max(0L, b);
        if (x == 0L || y == 0L) {
            return 0L;
        }
        if (x > Long.MAX_VALUE / y) {
            return Long.MAX_VALUE;
        }
        return x * y;
    }

    /** {@code a + b}，溢出则 {@link Long#MAX_VALUE}（两个入参都按非负处理）。 */
    public static long saturatedAdd(long a, long b) {
        final long x = Math.max(0L, a);
        final long y = Math.max(0L, b);
        final long sum = x + y;
        return sum < 0L ? Long.MAX_VALUE : sum;
    }

    /** 产物条目（单份口径：{@code key} 出 {@code amount} 个）。生产路径的 key 是 {@code AEKey}。 */
    public static final class Product<K> {

        public final K key;
        public final long amount;

        public Product(K key, long amount) {
            this.key = key;
            this.amount = amount;
        }
    }
}
