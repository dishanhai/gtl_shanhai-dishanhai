package com.shanhai.common.thread;

/**
 * 🔴 <b>并行预算的纯算术核（只 import {@code java.*}）</b> —— "本机并行上限 × 跨配方线程数"这
 * 三行算术的<b>唯一实现</b>，同时承担加载期自检与离线判据（本工程惯例：判据做成纯函数，
 * 既能在无头专服加载期真跑，也能单独 {@code javac} 驱动）。
 *
 * <h2>它为什么存在（本轮 2026-09-30 的起因）</h2>
 * 用户报的 bug（原话逐字）：<b>「又发现个bug，零点能反应堆不吃跨配方并行，那个发电量都没加」</b>。
 * 现象：同一台<b>原始真空零点能发生器</b>，线程槽放 30 个「世线残片·共鸣」把跨配方线程
 * 从 <b>1 提到 121</b>，而产能稳定在 <b>92.23E EU/t</b>，<b>一点没动</b>。
 *
 * <p>根因（详见交付报告 §1）：那台机器是<b>唯一</b>被 {@code PrimordialModuleRecipeLogic#shanhai$resolveRouting()}
 * 判定为 {@code getDefinition().isGenerator()} 的机器 ⇒ 它 {@code setUseMultipleRecipes(false)}，
 * <b>退回原生链</b>（引擎会把它的输出电算成深负值）。而原生链的并行实参取自
 * {@code ModuleRegistry#applyModuleRecipeModifier} 里的
 * {@code module.getRecipeLogicMaxParallel()}（= {@code max(1, getCurrentParallel())}）——
 * <b>那把表达式里根本没有跨配方线程</b>；引擎路径那一边（{@code PrimordialModuleRecipeLogic#calculateParallels()}）
 * 用的才是 {@link #totalParallelLimitFor(long, int)} = {@code 表值 × 线程数}。
 * <p>⇒ <b>同一台机器、同一种玩法，两条路对"跨配方线程"的态度相反</b>。本核把那份算术抽成一份，
 * 让原生链也走同一个表达式。
 *
 * <h2>why 值 = 1（线程槽空）时必须逐值不变</h2>
 * 用户那台机器在没放残片时显示的是 {@code 拥有跨配方线程: 1}。
 * {@link #saturatedMultiply(long, long)} 在 {@code b == 1} 时恒等于 {@code a}
 * ⇒ {@code totalParallelLimitFor(x, 1) == recipeLogicMaxParallelFor(x)} <b>对任意 x 成立</b>
 * ⇒ 空线程槽那条路与改动前<b>逐位相同</b>。这一条是本类自检的第一条断言
 * （也是"不影响现有玩法"的形式化证明）。
 *
 * <h2>🔴 一处必须知情的边界：原生链的 int 天花板</h2>
 * gtlcore 的 {@code ParallelLogicMixin#getMaxRecipeMultiplier} 第一句是
 * {@code ldc 2147483647}，末句是 {@code Ints.saturatedCast}
 * ⇒ <b>走 gtceu 那个 int 版 {@code ParallelLogic.applyParallel} 的路，并行永远不可能超过 2^31−1</b>。
 * 用户那台机器的物质模块槽里是「永恒物质模块」，它的表值 {@code 2147483647}
 * <b>恰好等于那个天花板</b> ⇒ 即使把线程数接进 int 版也<b>一个字都不会变</b>。
 * <p>⇒ 这就是为什么本核的乘数必须<b>允许越过 int</b>：超过 {@link #NATIVE_INT_CEILING} 时，
 * 本工程的 {@code PrimordialRecipeEffects#applyParallel(GTRecipe, MetaMachine, long)}
 * 会转交自建的 long 分支（{@code shanhai$applyParallelLong}），那条路才装得下。
 */
public final class ShanhaiParallelBudget {

    /**
     * 原生链（gtceu int 版 {@code ParallelLogic}）的<b>硬天花板</b>。
     *
     * <p>出处（字节码原文，取证文件 {@code temp\zpe-thread\javap\ParallelLogicMixin.txt}）：
     * <pre>
     *   ParallelLogicMixin.getMaxRecipeMultiplier(GTRecipe, IRecipeCapabilityHolder, int):
     *     0: ldc   #29   // int 2147483647     ← 起点就是它
     *     9: invokestatic IParallelLogic.getMaxParallel:(...)J
     *    12: invokestatic Math.min:(JJ)J        ← 与 IParallelLogic 的结果取小
     *    15: invokestatic Ints.saturatedCast:(J)I   ← 再窄化成 int
     * </pre>
     * ⇒ 该方法的返回值<b>上界就是 2147483647</b>，与传进去的 int 实参无关。
     */
    public static final long NATIVE_INT_CEILING = 2147483647L;

    private ShanhaiParallelBudget() {}

    /**
     * <b>饱和 long 乘法</b>（全工程唯一一份；2026-09-30 从 {@code PrimordialModuleMachine} 原样搬来，
     * 那里现在只剩一行委托 —— 搬动<b>没有</b>改动任何数值或分支）。
     *
     * <p>老山海 guide 的自写红线（{@code special_index.md:113}）：
     * 「最大并行不能超过 {@code Long.MAX_VALUE}，否则会发生数值溢出」
     * ⇒ 并行预算这一路上的每个乘法<b>都必须饱和</b>，不许裸乘。
     * <p>语义：任一因子 ≤ 0 ⇒ 0（"没有预算"，引擎据此停机而<b>不是</b>得到负数）；
     * 否则溢出时返回 {@code Long.MAX_VALUE}（"无限"，正是老山海对最高档的表达）。
     */
    public static long saturatedMultiply(long a, long b) {
        if (a <= 0L || b <= 0L) {
            return 0L;
        }
        if (a > Long.MAX_VALUE / b) {
            return Long.MAX_VALUE;
        }
        return a * b;
    }

    /**
     * <b>本模块的并行上限（long 形态）</b> = {@code max(1, currentParallel)}。
     *
     * <p>与老山海逐字同源：{@code PrimordialOmegaEngineModuleBase.java:520}
     * {@code return Math.max(1L, getCurrentParallel());}。
     * 下限 1 的理由与老山海一致：0/负值会让引擎的贪心分配
     * {@code if (remain <= 0L) break;} 立刻跳出 ⇒ 机器【不动、不崩、日志无输出】。
     */
    public static long recipeLogicMaxParallelFor(long currentParallel) {
        return Math.max(1L, currentParallel);
    }

    /**
     * <b>并行预算</b> = {@link #recipeLogicMaxParallelFor(long)} × 跨配方线程数（饱和）。
     *
     * <p>形状与老山海的 {@code getTotalParallelLimit()}
     * （{@code saturatedMultiply(getMachine().getRecipeLogicMaxParallel(), getLogicThreadMultiplier())}）
     * 逐字同源，而不是 gtladditions 父类的 {@code (long) getMaxParallel() * getMultipleThreads()}
     * —— 后者的第一个因子是 int，正是被压平的那一处。
     *
     * @param threads 跨配方线程数；{@code <= 0} 按 <b>1</b> 算（"没有线程"= 不放大）
     */
    public static long totalParallelLimitFor(long currentParallel, int threads) {
        return saturatedMultiply(recipeLogicMaxParallelFor(currentParallel), Math.max(1, threads));
    }

    /**
     * 🔴🔴 <b>「电上限 ÷ T」的【全工程唯一一份实现】—— 落到【取并行上限那一层】的那个值
     * （{@code getMaxParallel()} ／ {@code ParallelOverrideMachine#applyEnergyCap}）。</b>
     *
     * <pre>
     *   T      = max(1, 跨配方线程数)                        ← T ≤ 0 按 1 算 ⇒ 分母恒 ≥ 1
     *   P      = 能源仓总功率（EU/t，{@code EnergyHatchPower} 逐仓求和）
     *   k      = 每并行耗电（🔴 2026-10-02 第七轮起以【毫 EU/t】的定点 long 参与运算，
     *           {@code ParallelPowerBudget#perParallelMilliCost}；中途不许 round）
     *   电上限 = floor(P × 1000 ÷ k毫)                        ← {@code ParallelPowerBudget#parallelFromPowerMilli}
     *          🔴 它是【总并行】口径（见 2026-10-11 那一段）⇒ 它自己的 ceiling 也必须吃
     *             【总】口径的上限 = {@link #totalParallelLimitFor}(本机上限, T)；
     *             ⛔ 传入"每线程上限"就会把 min(P÷k, 本机上限) 当成总并行（2026-10-11 修掉的那个 bug）。
     *   ────────────────────────────────────────────────────────────────────────────
     *   每线程并行 p = min( 本机并行上限 , floor(电上限 ÷ T) )   ← 【本方法 = 接口层那个值】
     *   ────────────────────────────────────────────────────────────────────────────
     *   电上限 ≤ 0（开关关着 / 没算出 / 没能源仓）⇒ p = 本机并行上限（不做电力限制，老行为）
     *   floor(电上限 ÷ T) == 0 ⇒ p = 1（保底；见下方「地板」一节）
     * </pre>
     *
     * <h2>🔴🔴 2026-10-02 第五轮：为什么必须落在【这一层】（用户裁决 ①）</h2>
     * 上一轮（第三轮）把 {@code ÷T} 只落到了 {@link #parallelBudget} —— 那个值只在
     * <b>&gt; Integer.MAX_VALUE</b> 时才被引擎真正使用；≤ 2³¹ 时两个引擎都
     * {@code return super.calculateParallels()}，而<b>父类自己重算一份预算</b>（字节码原文，
     * 取证文件 {@code temp/autoparallel-fix4/javap-MutableRecipesLogic.txt}）：
     * <pre>
     *   protected ParallelData calculateParallels();
     *      5: aload_0
     *      6: invokevirtual   getMachine()
     *      9: checkcast       ParallelMachine
     *     12: invokeinterface ParallelMachine.getMaxParallel:()I     ← 🔴 读的就是【本层】
     *     17: i2l
     *     18: aload_0
     *     19: invokevirtual   getMultipleThreads:()I
     *     22: i2l
     *     23: lmul                                                     ← 父类把它乘回 T
     *     24: lstore_2
     *     49: invokevirtual   RecipeCalculationHelper.calculateParallelsWithGreedyAllocation(...)
     * </pre>
     * ⇒ <b>「退回父类」不但没关系，反而是正确路径</b>：父类的两个因子之一是
     * {@code getMaxParallel()}，而那正是 {@code applyEnergyCap()} 的出口 ⇒ 我们把
     * {@code ÷T} 放在本方法（= 接口层算出的<b>每线程</b>上限），父类的 {@code ×T} 就把它还原成
     * <b>总预算</b>：{@code 预算 = min(本机上限, floor(电上限÷T)) × T ≤ 电上限 = P ÷ k}
     * ⇒ {@code 总耗电 = k × 预算 ≤ P} <b>对任意 T 成立</b>（除下方「地板」档）。
     *
     * <h2>🔴 为什么是"÷ T"（用户裁决原话，逐字）</h2>
     * <blockquote>「总耗电 = k × p × T（每个线程都在跑、各自带着并行）⇒ 要不缺电 ⇒ k × p × T ≤ P
     * ⇒ p ≤ P ÷ (k × T)。而现在的写法是"每个线程各按 P 算"⇒ 两个线程就吃 2P ⇒ 必然超。」</blockquote>
     * ⛔ <b>本方法<b>改前</b>的算式（2026-10-02 第二轮）—— 作废，原文逐字留档：</b>
     * <pre>
     *   budget = min( 本机并行上限 × T , 电力上限 × T )     ≡ T × min(本机并行上限, 电力上限)
     * </pre>
     * 它把电上限那一项<b>乘</b>了 T（= 每个线程各拿一份完整的 P）⇒ 总耗电是 P 的 T 倍，
     * 正是用户报的「电力输入不足」。离线判据把这条旧算式当<b>负面对照实现</b>跑：
     * T = 8 时它给出 <b>384</b>，而生产实现给出 <b>6</b>
     * ⇒ <b>判据对「忘了除 T」这件事是可分辨的</b>。
     *
     * <h2>🔴 为什么 T = 1 时必须逐位不变（最硬的一条）</h2>
     * {@code floor(电上限 ÷ 1) == 电上限} ⇒
     * 新式 = {@code min(本机上限, 电上限)} = 改动前的值，<b>对任意
     * (本机上限, 电上限) 组合成立</b>（判据 A 段 6 × 8 = 48 组逐位对账 + 本类加载期自检 ⑦ 都断言它）。
     * 用户实测的两组（电网仓 2048 EU/t ⇒ 48、无线仓 128 EU/t ⇒ 3）正是 T = 1 那一档。
     *
     * <h2>⚠️ 「地板」档：floor(电上限 ÷ T) == 0 —— 总耗电 ≤ P 在该档数学上不可满足</h2>
     * {@code T > 电上限} 时，"每线程 1 份并行"所需的功率就已经超过 P（{@code k × 1 × T > P}）。
     * 本核取<b>保底 1</b>（与 {@link #recipeLogicMaxParallelFor} 的既有纪律同源：
     * 0 会让引擎贪心分配的 {@code if (remain <= 0L) break;} 立刻跳出 ⇒ 机器静默停摆），
     * 并把这一档的<b>残差如实上报</b>：该档总耗电 = {@code k × T}，超 P 的倍数 = {@code T ÷ 电上限}。
     * 「保底 1」与「归零（不跑、不耗电）」是<b>两种产品口径</b>，沿用既有纪律取前者，待裁。
     *
     * @param machineParallel 本机并行上限（{@code ≤ 0} 按 1 算，与 {@link #recipeLogicMaxParallelFor} 同口径）
     * @param energyCap       本轮算出来的电力上限；{@code ≤ 0} = 没有上限（不钳）
     * @param threads         跨配方线程数 T；{@code ≤ 0} 按 1 算
     */
    public static long perThreadParallelFor(long machineParallel, long energyCap, int threads) {
        final long machine = recipeLogicMaxParallelFor(machineParallel);
        if (energyCap <= 0L) {
            // 没有电力上限（开关关着 / 本轮没算出 / 没能源仓）⇒ 不做电力限制。
            return machine;
        }
        // T ≤ 0 ⇒ 按 1 算 ⇒ 分母恒 ≥ 1 ⇒ 【除零在结构上不可能】。
        final long t = Math.max(1, threads);
        // 两个操作数都是正 long ⇒ 整数除法就是数学上的 floor（不需要额外的饱和：本式【没有乘法】）。
        final long perThreadCap = energyCap / t;
        if (perThreadCap <= 0L) {
            // T > 电上限 ⇒ 连"每线程 1 份"都养不起（k × 1 × T > P）。
            // 保底 1：与 recipeLogicMaxParallelFor 的既有纪律同源 —— 0 会让引擎贪心分配的
            // `if (remain <= 0L) break;` 立刻跳出 ⇒ 机器【不动、不崩、日志无输出】。
            // ⚠️ 这一档「总耗电 ≤ P」数学上不可满足，残差如实上报（见方法 javadoc「地板」一节）。
            return 1L;
        }
        // 🔴 唯一的算式：每线程并行 = min(本机上限, 总功率 ÷ (每并行耗电 × 跨配方线程数))
        return Math.min(machine, perThreadCap);
    }

    /**
     * 🔴 <b>引擎真正吃到的【总预算】= {@link #perThreadParallelFor} × T
     * （逐位等于父类的 {@code (long) getMaxParallel() * getMultipleThreads()}）。</b>
     *
     * <pre>
     *   总预算 = saturatedMultiply(每线程并行上限, max(1, T))
     *          = min(本机上限, floor(电上限÷T)) × T                      ← 有电上限时
     *          = max(1, 本机上限) × T                                    ← 没有电上限时（老行为）
     *   ⇒ 有电上限时恒 ≤ 电上限 = P ÷ k ⇒ 总耗电 k × 总预算 ≤ P（除「地板」档）
     * </pre>
     *
     * <h2>🔴 为什么它【乘回 T】而不是把"每线程值"当预算</h2>
     * 因为引擎的预算语义就是<b>聚合</b>的：父类把预算交给
     * {@code RecipeCalculationHelper.calculateParallelsWithGreedyAllocation} 之后，
     * 由它摊给最多 T 条候选配方（每条各拿一份并行）⇒ <b>"每线程上限"必须乘回 T 才是预算</b>，
     * 否则机器会少跑 T 倍（本工程记录过"少给并行 = 白算"的那一类静默失效）。
     * <p>⚠️ 上一轮（第三轮）这里返回的是<b>没乘回 T</b> 的 {@code min(本机上限, 电上限÷T)}，
     * 导致：① 引擎侧与父类那条路<b>差 T 倍</b>；② 原生链（发电机那台）拿到的值从
     * {@code 本机上限 × T} 掉成 {@code 本机上限}，把 2026-09-30 那条已验收修复弄坏。
     * 两处都已在本轮修回（用户 2026-10-02 第五轮裁决 ① 与 ②）。
     *
     * <h2>🔴 为什么必须抽成唯一一份（而不是主机、模块各写一遍）</h2>
     * 调用点全部调本方法，全工程<b>没有第二份乘法或除法的副本</b> ⇒ 结构上不可能漂移。
     *
     * @param machineParallel 本机并行上限（{@code ≤ 0} 按 1 算）
     * @param energyCap       本轮算出来的电力上限；{@code ≤ 0} = 没有上限（不钳）
     * @param threads         跨配方线程数 T；{@code ≤ 0} 按 1 算
     */
    public static long parallelBudget(long machineParallel, long energyCap, int threads) {
        final int t = Math.max(1, threads);
        return saturatedMultiply(perThreadParallelFor(machineParallel, energyCap, t), t);
    }

    /**
     * 🔴 <b>主机的跨配方线程数 T —— 【唯一一份】实现
     * （配方逻辑的 {@code getMultipleThreads()} 与机器侧 {@code ÷T} 都调它）。</b>
     *
     * <pre>
     *   T_主机 = saturatedCast( BASE_THREADS + getAdditionalThread() )      ← 照伪神（FOTC）填
     *   当前取值：BASE_THREADS = 128、getAdditionalThread() = 0（上游默认） ⇒ T = 128
     * </pre>
     *
     * <h2>为什么必须抽成纯函数（而不是两处各写一遍）</h2>
     * 本轮起这个值有<b>两个读者</b>，而它们必须在<b>同一 tick 内逐位相同</b>：
     * <ol>
     *   <li>{@code PrimordialEngineRecipeLogic#getMultipleThreads()} —— 父类拿它乘预算；</li>
     *   <li>{@code PrimordialOmegaEngineMachine#getEnergyCapThreads()} —— 接口层拿它做 {@code ÷T}。</li>
     * </ol>
     * 两处各写一遍的后果是 {@code getMaxParallel()} 用 T₁、父类用 T₂ ⇒
     * 预算 = {@code min(本机上限, 电上限÷T₁) × T₂}，<b>可能 &gt; 电上限 ⇒ 总耗电超 P</b>
     * （T₂ &gt; T₁ 时）。⇒ 表达式只此一份。
     * <p>⚠️ 它<b>不读机器实例</b>，所以构造期被调用也安全（不会 NPE）。
     *
     * @param baseThreads      主机跨配方线程基数（{@code PrimordialEngineRecipeLogic.BASE_THREADS} = 128）
     * @param additionalThread 机器侧附加线程（{@code PrimordialOmegaEngineMachine#getAdditionalThread()}，当前 0）
     */
    public static int crossRecipeThreadsForHost(int baseThreads, int additionalThread) {
        return saturatedCast((long) baseThreads + (long) additionalThread);
    }

    /**
     * {@code Ints.saturatedCast(long)} 的<b>零依赖等价实现</b>（本核只许 import {@code java.*}）。
     *
     * <p>为什么不用 {@code com.google.common.primitives.Ints.saturatedCast}：本类的全部意义是
     * <b>能被一个裸 {@code javac}（无 classpath）编译并驱动</b>（离线判据 + 加载期自检）⇒
     * 不能依赖 Guava。语义与它<b>逐位相同</b>：超出 int 域时钳到边界，不截断、不回绕。
     */
    public static int saturatedCast(long value) {
        if (value > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (value < Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        return (int) value;
    }

    /** 用户实测档：物质模块槽里的「永恒物质模块」表值（{@code ShanhaiConcurrencyTables} 现读）。 */
    public static final long USER_ETERNAL_MODULE_PARALLEL = 2147483647L;
    /** 用户实测档：线程槽 30 个「世线残片·共鸣」（2 号残片 = 2^2 = 4/枚 ⇒ 1 + 4×30 = 121）。 */
    public static final int USER_SHARD_THREADS = 121;
    /** 用户实测档：121 线程下的预算字面量 = 2147483647 × 121（已核算）。 */
    public static final long USER_BUDGET_AT_121 = 259845521287L;

    /**
     * 🔴 <b>加载期自检（正向对照 + 负面对照 + 复原），由
     * {@code PrimordialModuleMachine#assertParallelTablesConsistent()} → {@code ModuleRegistry#init()}
     * 在注册期调用 ⇒ 无头专服里必跑、日志可 grep。</b>
     *
     * @return 一行可 grep 的读数（调用方直接 {@code LOGGER.info(...)}）
     * @throws IllegalStateException 任一条断言不成立（本项目风格：加载期 fail-fast，不静默）
     */
    public static String selfTest() {
        // ── ① 恒等（改动安全性的形式化证明）：线程 = 1 时与改动前的实参逐值相同 ──
        final long[] samples = {0L, 1L, 2L, 64L, 128L, 1048576L, 536870912L, 2147483647L,
                4611686018427387903L, 6917529027641081855L, Long.MAX_VALUE};
        int identityChecked = 0;
        for (int i = 0; i < samples.length; i++) {
            final long c = samples[i];
            final long before = recipeLogicMaxParallelFor(c);          // 改动前原生链的实参
            final long after = totalParallelLimitFor(c, 1);            // 改动后（线程槽空 = 1）
            if (before != after) {
                throw new IllegalStateException("[SHANHAI-PARALLEL-BUDGET] 自检失败：线程 = 1 时必须恒等，"
                        + "但 currentParallel=" + c + " 时 改动前=" + before + " / 改动后=" + after
                        + " ⇒ 「不影响现有玩法」这句话不成立。");
            }
            identityChecked++;
        }

        // ── ② 用户实测档：121 线程必须真的把预算顶过原生链的 int 天花板 ──
        final long userBefore = totalParallelLimitFor(USER_ETERNAL_MODULE_PARALLEL, 1);
        final long userAfter = totalParallelLimitFor(USER_ETERNAL_MODULE_PARALLEL, USER_SHARD_THREADS);
        assertEq(userBefore, 2147483647L, "1 线程时预算必须 = 永恒物质模块表值");
        assertEq(userAfter, USER_BUDGET_AT_121, "121 线程时预算必须 = 2147483647 × 121");
        if (userAfter <= NATIVE_INT_CEILING) {
            throw new IllegalStateException("[SHANHAI-PARALLEL-BUDGET] 自检失败：121 线程下预算 "
                    + userAfter + " 没有越过原生链天花板 " + NATIVE_INT_CEILING
                    + " ⇒ 用户那台机器加了残片也不会有任何变化（这正是他报的现象）。");
        }
        // 121 这个数本身自洽：1 + 2^2 × 30（世线残片·共鸣 = thread_shard_2）
        assertEq(1L + 4L * 30L, (long) USER_SHARD_THREADS, "121 必须 = 1 + 2^2 × 30（共鸣残片）");

        // ── ③ 饱和红线：Long.MAX 档 × 线程数不许回绕成负数 ──
        assertEq(totalParallelLimitFor(Long.MAX_VALUE, 121), Long.MAX_VALUE, "MAX 档 × 121 必须饱和到 MAX");
        assertEq(totalParallelLimitFor(4611686018427387903L, 3), Long.MAX_VALUE, "4.6e18 × 3 必须饱和到 MAX");
        assertEq(totalParallelLimitFor(100L, 0), 100L, "线程数 ≤ 0 按 1 算（不放大、也不清零）");
        assertEq(totalParallelLimitFor(100L, -5), 100L, "线程数为负同样按 1 算");

        // ── ④ 负面对照 A：不改的话（老实参）在 121 线程下永远是表值 ⇒ 与用户所见吻合 ──
        if (userBefore >= userAfter) {
            throw new IllegalStateException("[SHANHAI-PARALLEL-BUDGET] 自检的【负面对照】失败："
                    + "老实参在 121 线程下竟然不小于新实参 ⇒ 本核什么都没证明。");
        }

        // ── ⑤ 负面对照 B：防"把乘数写死" —— 1 线程那档必须【不】等于 121 线程那档 ──
        if (totalParallelLimitFor(USER_ETERNAL_MODULE_PARALLEL, 1) == USER_BUDGET_AT_121) {
            throw new IllegalStateException("[SHANHAI-PARALLEL-BUDGET] 自检的【负面对照】失败："
                    + "1 线程与 121 线程算出了同一个预算 ⇒ 乘数写死了。");
        }

        // ── ⑥ 复原（顺序无关 / 无状态）：同样的输入再算一次必须同值 ──
        if (totalParallelLimitFor(USER_ETERNAL_MODULE_PARALLEL, USER_SHARD_THREADS) != userAfter
                || totalParallelLimitFor(USER_ETERNAL_MODULE_PARALLEL, 1) != userBefore) {
            throw new IllegalStateException("[SHANHAI-PARALLEL-BUDGET] 自检失败：重复计算不同值（本核应当无状态）。");
        }

        // ── ⑦ 🔴 2026-10-02 第五轮（接口层口径）：÷T 落到「取并行上限那一层」 ──
        //    要证五件事：
        //    ① T = 1 与改动前逐位相同（用户实测的 48 / 3 就在这一档）；
        //    ② 接口层（= getMaxParallel()/applyEnergyCap()）拿到的是【每线程】上限，
        //       而引擎总预算 = 每线程上限 × T（= 父类的 (long) getMaxParallel() * getMultipleThreads()）；
        //    ③ 总耗电 k × 总预算 ≤ P（在 T ≤ 电上限 的全部档位上）；
        //    ④ 负面对照：「忘了除 T」的口径在同一批输入上【必须】超功率（否则判据没有分辨力）；
        //    ⑤ 原生链（发电机那台）= 本机上限 × T，2026-09-30 那条已验收修复所依赖的乘积还在。
        final long[] caliberCaps = {0L, -1L, 1L, 3L, 48L, 1024L, 2147483647L, Long.MAX_VALUE};
        final long[] caliberMachines = {0L, -1L, 1L, 64L, 2147483647L, Long.MAX_VALUE};
        int caliberChecked = 0;
        for (int i = 0; i < caliberCaps.length; i++) {
            for (int j = 0; j < caliberMachines.length; j++) {
                final long cap = caliberCaps[i];
                final long m = caliberMachines[j];
                // ① 改动前（第二轮口径）在 T = 1 时的值 —— 逐字照抄那一版的实现，用于逐位对账。
                final long scaledMachineAt1 = saturatedMultiply(recipeLogicMaxParallelFor(m), 1L);
                final long oldAt1 = cap <= 0L ? scaledMachineAt1
                        : Math.min(scaledMachineAt1, saturatedMultiply(cap, 1L));
                if (oldAt1 != perThreadParallelFor(m, cap, 1)) {
                    throw new IllegalStateException("[SHANHAI-PARALLEL-BUDGET] 自检失败（接口层 · T=1 恒等）："
                            + "本机上限=" + m + " / 电上限=" + cap + " 时 改前=" + oldAt1
                            + " 而 改后（每线程）=" + perThreadParallelFor(m, cap, 1)
                            + " ⇒ 「T = 1 逐位不变」不成立。");
                }
                if (oldAt1 != parallelBudget(m, cap, 1)) {
                    throw new IllegalStateException("[SHANHAI-PARALLEL-BUDGET] 自检失败（总预算 · T=1 恒等）："
                            + "本机上限=" + m + " / 电上限=" + cap + " 时 改前=" + oldAt1
                            + " 而 改后（总预算）=" + parallelBudget(m, cap, 1));
                }
                caliberChecked++;
            }
        }
        // ② 接口层 = 每线程上限；引擎总预算 = 它 × T。
        assertEq(perThreadParallelFor(Long.MAX_VALUE, 48L, 1), 48L, "接口层 T=1 时电上限 48 原样 = 48（用户实测档）");
        assertEq(perThreadParallelFor(Long.MAX_VALUE, 48L, 8), 6L, "接口层 T=8 时 48 ÷ 8 = 6（这就是 getMaxParallel() 的返回值）");
        assertEq(parallelBudget(Long.MAX_VALUE, 48L, 8), 48L, "总预算 T=8 ⇒ 6 × 8 = 48（= 父类 getMaxParallel()×线程）");
        assertEq(perThreadParallelFor(64L, 48L, 8), 6L, "模块（本机上限 64）接口层 T=8 ⇒ min(64, 6) = 6");
        assertEq(parallelBudget(64L, 48L, 8), 48L, "模块总预算 T=8 ⇒ 6 × 8 = 48");
        assertEq(perThreadParallelFor(Long.MAX_VALUE, 48L, 128), 1L, "主机 T=128、电上限 48 ⇒ 48÷128=0 ⇒ 接口层保底 1");
        assertEq(parallelBudget(Long.MAX_VALUE, 48L, 128), 128L, "主机总预算 T=128 ⇒ 1 × 128 = 128（地板档）");
        assertEq(perThreadParallelFor(64L, 0L, 121), 64L, "没有电力上限 ⇒ 接口层不钳（与 T 无关）");
        assertEq(parallelBudget(64L, 0L, 121), 7744L, "没有电力上限 ⇒ 总预算 = 64 × 121（老行为：× T）");
        assertEq(perThreadParallelFor(64L, -5L, 121), 64L, "电上限为负（哨兵）同样不钳");
        assertEq(perThreadParallelFor(0L, 48L, 8), 1L, "本机上限 ≤ 0 ⇒ 地板 1");
        assertEq(parallelBudget(Long.MAX_VALUE, 48L, 0), 48L, "T ≤ 0 与 T = 1 同值（分母恒 ≥ 1，除零不存在）");
        assertEq(perThreadParallelFor(Long.MAX_VALUE, -1L, 0), Long.MAX_VALUE, "T=0 且没有电上限 ⇒ 原样本机上限");
        // ⑤ 用户裁决 ②：原生链（发电机那台）必须还是「本机上限 × T」——
        //    永恒物质模块表值 2147483647 × 121 线程 = 259845521287（正是 2026-09-30 验收过的那个乘积）。
        assertEq(parallelBudget(USER_ETERNAL_MODULE_PARALLEL, 0L, USER_SHARD_THREADS), USER_BUDGET_AT_121,
                "原生链（没有电上限）必须回到「本机上限 × T」= 2147483647 × 121");
        // ③ 总耗电 = k × 总预算 ≤ P：用用户实测档 k = 42、P = 2048 EU/t（电网仓 2×LV2A）逐档跑。
        int powerChecked = 0;
        for (int t = 1; t <= 48; t++) {
            final long cap = 2048L / 42L;                 // = 48
            final long budget = parallelBudget(Long.MAX_VALUE, cap, t);
            final long draw = saturatedMultiply(42L, budget);
            if (draw > 2048L) {
                throw new IllegalStateException("[SHANHAI-PARALLEL-BUDGET] 自检失败（总耗电红线）：T=" + t
                        + " 时 总预算=" + budget + " ⇒ 总耗电 " + draw + " EU/t > 能源仓 " + 2048L + " EU/t。");
            }
            powerChecked++;
        }
        // ④ 负面对照：「忘了除 T」的口径（预算 = min(本机上限, 电上限) × T）在同一批输入上【必须】超功率。
        int oldOver = 0;
        for (int t = 1; t <= 48; t++) {
            final long cap = 2048L / 42L;
            final long forgotDivide = saturatedMultiply(Math.min(Long.MAX_VALUE, cap), (long) t);
            final long draw = saturatedMultiply(42L, forgotDivide);
            if (draw > 2048L) {
                oldOver++;
            }
        }
        if (oldOver == 0) {
            throw new IllegalStateException("[SHANHAI-PARALLEL-BUDGET] 自检的【负面对照】失败："
                    + "「忘了除 T」的口径竟然一次都没超功率 ⇒ 本核的总耗电判据没有分辨力。");
        }
        // ⑥ 主机 T 的唯一一份纯函数：当前 BASE_THREADS = 128、附加 = 0 ⇒ 128。
        assertEq(crossRecipeThreadsForHost(128, 0), 128L, "主机 T 纯函数：128 + 0 = 128");
        assertEq(crossRecipeThreadsForHost(128, Integer.MAX_VALUE), Integer.MAX_VALUE,
                "主机 T 纯函数饱和：128 + Integer.MAX_VALUE ⇒ 钳到 Integer.MAX_VALUE（不回绕成负数）");
        assertEq(saturatedCast(Long.MAX_VALUE), (long) Integer.MAX_VALUE, "饱和桥：Long.MAX ⇒ Integer.MAX");
        assertEq(saturatedCast(-1L), -1L, "饱和桥：域内值原样（-1 ⇒ -1）");

        // ── ⑧ 🔴 2026-10-02 第九轮：T > 1【且候选配方多条】时，整机红线仍然成立 ──
        //    起因（用户原话逐字）：「等一下，不同配方耗电是不同的，你不会取静态的数值了吧」
        //    ⇒ 候选多条时 k 取【最贵】的那条，唯一实现在
        //      {@code com.shanhai.common.machine.ParallelPowerBudget#worstPerParallelMilliCost}
        //      （那边的 selfTest ⑧ 断言"取最贵、且取头一条会真超功率"）。
        //    ⚠️ 本核**刻意不 import 它**（两个纯核各自保持"能被裸 javac 单独编"的性质）⇒
        //      这里只把那个判据的输出值 100 EU/t 当【输入常量】用，四个数全部手算写进断言：
        //        P = 655,360 EU/t、k = 100 EU/t（候选 {42,100} 里最贵的那条）、T = 8
        //        电上限   = floor(655,360 ÷ 100)  = 6,553        （= 上一条判据的输出）
        //        每线程   = floor(6,553 ÷ 8)      = 819
        //        总预算   = 819 × 8               = 6,552
        //        整机耗电 = 100 × 6,552           = 655,200 ≤ 655,360 ✓
        //    为什么"最贵那条 × 总预算"就是整机耗电的上界：引擎把总预算摊给最多 T 条候选，
        //    各线程真正跑的配方单价 k_i ≤ k_max ⇒ 合计 = Σ k_i × p_i ≤ k_max × Σ p_i = k_max × 总预算。
        final long multiK = 100L;
        final long multiCap = 655_360L / multiK;
        assertEq(multiCap, 6_553L, "T>1 档的前提：电上限必须 = 655,360 ÷ 100 = 6,553");
        assertEq(perThreadParallelFor(Long.MAX_VALUE, multiCap, 8), 819L, "T=8 ⇒ 每线程上限 819");
        assertEq(parallelBudget(Long.MAX_VALUE, multiCap, 8), 6_552L, "T=8 ⇒ 总预算 819 × 8 = 6,552");
        assertEq(saturatedMultiply(multiK, 6_552L), 655_200L, "T=8 ⇒ 整机耗电 100 × 6,552 = 655,200");
        int multiThreadChecked = 0;
        for (int t = 1; t <= 64; t++) {
            final long budget = parallelBudget(Long.MAX_VALUE, multiCap, t);
            final long draw = saturatedMultiply(multiK, budget);
            if (draw > 655_360L) {
                throw new IllegalStateException("[SHANHAI-PARALLEL-BUDGET] 自检失败（多候选 · 整机红线）："
                        + "T=" + t + " 时总预算=" + budget + " ⇒ 按最贵的 100 EU/t 算整机耗电 " + draw
                        + " EU/t > 能源仓 655,360 EU/t。");
            }
            multiThreadChecked++;
        }

        return "[SHANHAI-PARALLEL-BUDGET] 并行预算纯算术核自检通过（恒等 " + identityChecked
                + " 档 + 用户实测档 + 饱和 " + 2 + " 条 + 负面对照 2 条 + 复原 1 条）："
                + "线程槽空（=1）时与原实参逐值相同；永恒物质模块表值 " + USER_ETERNAL_MODULE_PARALLEL
                + " 在 1 线程下预算 = " + userBefore + "（恰 = 原生链 int 天花板 " + NATIVE_INT_CEILING
                + "），在 " + USER_SHARD_THREADS + " 线程下预算 = " + userAfter
                + "（> 天花板，会转交 long 分支）；MAX 档 × 121 饱和到 " + Long.MAX_VALUE + "（不回绕）。"
                + "【2026-10-02 第五轮 · ÷T 落到接口层】T=1 恒等 " + caliberChecked + " 档逐位对账通过；"
                + "接口层（= getMaxParallel()）T=1⇒48 / T=8⇒6 / T=128⇒1（保底），"
                + "引擎总预算（= 父类 getMaxParallel()×T）T=1⇒48 / T=8⇒48 / T=128⇒128；"
                + "总耗电 k×总预算 ≤ P 共 " + powerChecked + " 档全过，同一批输入下「忘了除 T」超功率 " + oldOver
                + " 档（负面对照 ⇒ 判据有分辨力）；原生链（发电机那台）仍为「本机上限 × T」=" + USER_BUDGET_AT_121
                + "。【多候选 · T>1】k 取最贵的那条（100 EU/t）时 P=655,360 ⇒ 电上限 6,553 / T=8 ⇒ 每线程 819"
                + " / 总预算 6,552 / 整机 655,200 ≤ P；T=1..64 共 " + multiThreadChecked + " 档整机红线全过。";
    }

    private static void assertEq(long actual, long expected, String what) {
        if (actual != expected) {
            throw new IllegalStateException("[SHANHAI-PARALLEL-BUDGET] 自检失败：" + what
                    + "，实际 " + actual + "，应为 " + expected + "。");
        }
    }
}
