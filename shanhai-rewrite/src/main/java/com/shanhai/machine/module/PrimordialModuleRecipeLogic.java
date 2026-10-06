package com.shanhai.machine.module;

import com.gregtechceu.gtceu.api.capability.recipe.IRecipeCapabilityHolder;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.machine.multiblock.CleanroomType;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.recipe.RecipeCondition;
import com.gregtechceu.gtceu.api.recipe.RecipeHelper;
import com.gregtechceu.gtceu.api.recipe.condition.RecipeConditionType;
import com.gregtechceu.gtceu.common.recipe.condition.CleanroomCondition;
import com.gregtechceu.gtceu.common.recipe.condition.DimensionCondition;
import com.gregtechceu.gtceu.common.recipe.condition.ResearchCondition;
import com.gtladd.gtladditions.api.machine.logic.MutableRecipesLogic;
import com.gtladd.gtladditions.common.data.ParallelData;
import com.shanhai.ShanhaiMod;
import com.shanhai.common.heat.ShanhaiHeatGate;
import com.shanhai.common.log.ShanhaiLogThrottle;
import com.shanhai.common.machine.CandidateSetConsistency;
import com.shanhai.common.machine.EnergyHatchPower;
import com.shanhai.common.machine.ParallelOverrideMachine;
import com.shanhai.common.machine.ParallelPowerBudget;
import com.shanhai.common.recipe.PrimordialRecipeEffects;
import com.shanhai.common.thread.ShanhaiParallelBudget;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import it.unimi.dsi.fastutil.longs.LongLongPair;

import org.gtlcore.gtlcore.api.recipe.IGTRecipe;
import org.gtlcore.gtlcore.api.recipe.RecipeMultiplierTracker;
import org.gtlcore.gtlcore.api.recipe.RecipeResult;
import org.gtlcore.gtlcore.api.recipe.RecipeRunnerHelper;
import org.gtlcore.gtlcore.common.recipe.condition.GravityCondition;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 24 台原初模块的<b>多配方引擎逻辑</b>（{@code MutableRecipesLogic} 的本工程子类）——
 * "跨配方线程"机制在模块侧的落点。
 *
 * <h2>本类要解决的唯一问题：把模块的配方口径搬到引擎路径上，且【值 = 1 时逐值不变】</h2>
 * 引擎（gtladditions {@code MutableRecipesLogic}）与原生的差别不是"多一个功能"，而是：
 * <ol>
 *   <li>引擎路径<b>完全不经过</b> {@code RecipeLogic.checkMatchedRecipeAvailable}
 *       ⇒ 模块挂在 {@code .recipeModifier(...)} 上的整条效果链（{@code ModuleRegistry#applyModuleRecipeModifier}
 *       的 并行→N3→N5时长→N6→N5耗能）会<b>静默失效</b>。本类的
 *       {@link #buildFinalNormalRecipe(ParallelData)} 把这条链迁到引擎路径上，
 *       <b>全部复用 {@link PrimordialRecipeEffects} 的同一批纯函数</b>（本类里不出现任何自有算式）。</li>
 *   <li>引擎自己<b>硬编码</b> {@code minDuration = 20}（{@code RecipeCalculationHelper.buildNormalRecipe}），
 *       不认识模块侧栏那个"配方最短耗时"（该旋钮 2026-09-22 已从模块侧摘除）⇒ 由尾链的 N6 按 d0 与 f 重算。</li>
 *   <li>引擎的候选集是<b>该配方类型的全部可跑配方</b>（贪心分配后合并成一条）
 *       ⇒ 值 = 1 时必须限制成一条，见 {@link #lookupRecipeSet()}。</li>
 * </ol>
 *
 * <h2>🔴 EUt 口径（本类最容易出错的一格，已用字节码实证）</h2>
 * <pre>
 *   原生链：{@code ParallelLogic.doParallelRecipes} → {@code recipe.copy(ContentModifier.multiplier(limit), false)}
 *           ⇒ {@code GTRecipe.copy(ContentModifier, boolean)} 把 inputs / outputs / <b>tickInputs</b> / tickOutputs
 *             <b>四张表全部乘 p</b> ⇒ 原生 {@code EUt_content = baseEUt × p}
 *           （运行时真正执行的是 gtlcore {@code ParallelLogicMixin.doParallelRecipes} 的覆写，
 *             它同样是 {@code recipe.copy(ContentModifier.multiplier(limitByOutput), modifyDuration)}）
 *   引擎：{@code EUt_engine = totalEu / D}，而 {@code totalEu = baseEUt × p × d0} ⇒ 同样含 p
 *   ⇒ 两边对 p 的处理一致；本类只需把"引擎时长 D ⇒ 原时长 d0"这一档还原：
 *       {@link PrimordialRecipeEffects#rescaleEnergyForDuration(GTRecipe, int, int) rescaleEnergyForDuration(built, D, d0)}
 *       给出 {@code EUt_engine × D/d0 = totalEu/d0 = baseEUt × p} ✅
 *   ⚠️ {@code realParallels} <b>不参与</b>逐 tick 耗能（gtlcore 里它只进
 *      {@code RecipeRunner} 的概率掷骰、{@code BatchProcessing} 与显示追踪器 —— 已逐类核过）
 *      ⇒ 不存在"重复乘 p"。引擎新造配方的 {@code realParallels} 保持默认 1。
 * </pre>
 *
 * <h2>🔴 "1 线程 = 一次只跑一种配方"是【我们替上游改的语义】</h2>
 * 上游把 {@code getMultipleThreads()} 当<b>并行预算的倍数</b>（{@code totalParallel = getMaxParallel() × threads}）。
 * 本工程改成：<b>候选配方集合最多取 {@code threads} 条</b>（见 {@link #lookupRecipeSet()}）。
 * 值 = 1 时这两套语义在"预算充足"时等价（第一条吃满预算），但在输入稀缺时不同 ——
 * 上游会把余量分给第二条并合并，我们不这么做（那样会与模块今天的行为分叉）。
 * ⇒ <b>这是刻意的口径改动，写在这里以防将来被当成 bug 修掉。</b>
 *
 * <h2>发电模块（唯一那台）走原生链</h2>
 * 它的配方 EUt 是 {@code Integer.MIN_VALUE}（输出电力语义，见冒烟日志
 * {@code zero_point_power … EUt=-2147483648}），而引擎的 {@code totalEu} 是"耗电"累加
 * ⇒ 会算出深负值。判据用 {@code getDefinition().isGenerator()}（全 24 台只有它 true），
 * 命中则 {@code setUseMultipleRecipes(false)} = 退回原生链（那条链的 {@code .generator} 语义原样保留）。
 * <p>⚠️ 切换点放在 {@link #findAndHandleRecipe()} 里<b>懒判定</b>：构造期不许读机器字段
 * （{@code MachineBuilder} 在机器构造过程中调 {@code createRecipeLogic}，此刻字段还没初始化）。
 */
public class PrimordialModuleRecipeLogic extends MutableRecipesLogic<PrimordialModuleMachine> {

    /** 懒判定闸门：只跑一次（发电特判 + 一条可 grep 的路由日志）。 */
    private boolean shanhai$routingResolved;

    /** 对账器的正面对照闸门：只跑一次。 */
    private boolean shanhai$probeSelfTested;

    public PrimordialModuleRecipeLogic(PrimordialModuleMachine machine) {
        super(machine);
        // 引擎开关本身（不写这一行 = 对象是 MutableRecipesLogic 但走的是父类原生路径）。
        // 发电那台会在 findAndHandleRecipe() 里被懒关掉 —— 见类注释。
        setUseMultipleRecipes(true);
    }

    @Override
    public void findAndHandleRecipe() {
        shanhai$resolveRouting();
        // 🔴 每轮重扫前清空「这一轮为什么没跑起来」的暂存 —— 见 shanhai$publishFailReason 的 A③ 注释。
        //
        // 🔴🔴 2026-10-04 修 bug：「额外挂载槽」那个暂存位【漏了清空】，三个暂存只清了两个。
        //   后果（用户实机报的「配方失败原因显示错误」，静态可证 + 日志可证）：
        //     一旦这台机器【曾经】因为额外挂载槽被拦过一次（recordExtraBlock 把下面那个 boolean 置 true），
        //     而该标志【只被写、从不被清】⇒ 此后这台机器的每一轮扫描里
        //     shanhai$recordVoltageTierBlock 都会在【第一行就 return】，电压那条原因【永远写不进去】。
        //     ⇒ shanhai$publishFailReason 拿到 null ⇒ 一个字都不写 ⇒ 机器状态栏里留着的是【别人的】原因。
        //   「别人的原因」为什么偏偏是超净间那一条：本闸门为了判「原版是否已经满足」，
        //     会调 Condition#test 做**窥探**（见本文件 shanhai$extraMountGateAllows 第 853 行），
        //     而 gtlcore 的 CleanroomConditionMixin.test 是**带副作用的** —— 它判定失败时
        //     自己就会 RecipeResult.of(machine, fail("未满足%s条件")) 把原因写进机器
        //     （字节码实证：org/gtlcore/gtlcore/mixin/gtm/api/recipe/condition/CleanroomConditionMixin，
        //       `ldc #83 // String gtceu.recipe.fail.cleanroom` … `RecipeResult.of` `iconst_0; ireturn`）。
        //   ⇒ 实机读数（实例 logs\latest.log，2026-10-04）：
        //       15:43:46 槽全空 ⇒ 拦下(SLOT_EMPTY, cleanroom) ⇒ 这个 boolean 被置 true；
        //       15:43:56 放入超净维护仓 ⇒【槽放行】+【VOLTAGE-GATE 拦下 machineTier=2 < recipeEuTier=3】
        //                ⇒ 电压那条被上面那个陈旧 boolean 吞掉 ⇒ 显示的是窥探留下的「未满足超净间条件」。
        //   判据：这条是【显示错】，不是闸门判错 —— 闸门当时的判定恰好就是用户预期的那一条
        //     （需求 cleanroom=cleanroom、槽提供 ×1 cleanroom ⇒ 放行；真正拦下的是电压 MV < HV）。
        shanhai$pendingFailReason = null;
        shanhai$pendingFailIsModuleLevel = false;
        shanhai$pendingFailIsExtraMount = false;
        super.findAndHandleRecipe();
        shanhai$publishFailReason();
    }

    /**
     * 🔴 <b>2026-09-26：把并行预算接回 long（本任务 ② 的落点）。</b>
     *
     * <h2>病根（一句话）</h2>
     * 父类 {@code MutableRecipesLogic.calculateParallels()} 的预算是
     * <pre>
     *   [源码原文] MutableRecipesLogic.kt:198
     *       val totalParallel: Long = (long)this.getMachine().getMaxParallel() * this.getMultipleThreads();
     * </pre>
     * 第一个因子 {@code getMaxParallel()} 是 <b>gtlcore {@code ParallelMachine} 的 int 方法</b>
     * ⇒ 并行表末三档（4611686018427387903 / 6917529027641081855 / 9223372036854775807）
     * <b>在这里被压成 2147483647</b>，17 档实际只有 14 档有区分度。
     *
     * <h2>修法（老山海的形态，逐条对齐）</h2>
     * <pre>
     *   ① 预算换成 long：{@code totalParallelLimitFor(getCurrentParallel(), getMultipleThreads())}
     *      —— 形状 = 老山海 SelectableRecipeTypeSetRecipeLogic:449 的
     *         {@code saturatedMultiply(getMachine().getRecipeLogicMaxParallel(), getLogicThreadMultiplier())}；
     *   ② 分配仍是上游那一份算法，只是换成 long 版
     *      （{@link PrimordialRecipeEffects#greedyAllocateWithLongLimit} 逐句照抄上游，
     *        因为上游那个形参类型 kotlin.jvm.functions.Function2 在编译期不可见）；
     *   ③ 🔴 <b>预算 ≤ Integer.MAX_VALUE 时【原样走父类】</b> ⇒ 并行表前 14 档、
     *      空槽 64、以及一切正常玩法走的都是<b>改动前那条字节码</b>。
     * </pre>
     * 判据（可 grep）：预算 &gt; 2^31−1 时打一行 {@code [SHANHAI-PARALLEL-LONG]}，
     * 里面同时给出「上限 / 预算 / 15..17 档会命中」。
     *
     * <h2>⚠️ 诚实边界</h2>
     * 本方法保证的是「<b>引擎收到的并行<b>上限</b> = 表值</b>」。
     * 真正分配出去多少，仍由上游的两条边界收敛：
     * {@code IParallelLogic.getMaxParallel}（<b>实际可用输入量</b>）与
     * <b>输出空间</b>。这就是用户规格里那句「实际并行由输入量与输出空间决定」；
     * 装满 4.6e18 份原料时它才会真的是 4.6e18。
     */
    /**
     * <b>本台模块的并行预算 —— 【唯一一处】表达式</b>：
     * {@code totalParallelLimitFor(并行槽值, 跨配方线程数)}（饱和 long）。
     *
     * <h2>🔴 为什么必须抽成一个方法（2026-09-30 用户拍板 B 之后新增）</h2>
     * 这个数现在有<b>两个读点</b>，而它们必须拿到<b>同一个值</b>：
     * <ol>
     *   <li>{@link #calculateParallels()} —— 决定真的分配多少并行；</li>
     *   <li>{@link #buildFinalNormalRecipe(ParallelData)} —— 决定"并行进 long 档 ⇒ 配方时长下限
     *       10 tick"这一步要不要施加（判据 = 本预算 &gt; 2147483647）。</li>
     * </ol>
     * 两处各写一遍表达式的后果是<b>静默分叉</b>（将来只改一处 ⇒ "分配时算 long 档、时长却按 int 档算"），
     * 正是本工程反复记录过的那类错误。⇒ <b>表达式只此一份</b>。
     *
     * <p>两个读都只在<b>同一 tick 内</b>发生，且 {@code getCurrentParallel()} / {@code getMultipleThreads()}
     * 都是纯读（不随调用次数变化）⇒ 两次调用逐值相同。
     *
     * <p>⚠️ 传给下限判据的是<b>预算</b>，不是"实际分配到的并行 p"：后者被输入量钳位、
     * 随箱里剩多少料跳变，会让下限"时灵时不灵"。理由与原生链那一侧逐字相同，见
     * {@code PrimordialRecipeEffects#applyLongScaleDurationFloor} 的 javadoc。
     */
    private long shanhai$parallelBudget() {
        // 🔴 2026-10-02 第五轮（用户裁决 ①）：÷T 的真正落点是【接口层】
        //    （ParallelOverrideMachine#applyEnergyCap → getMaxParallel()），本行只是把同一份算术
        //    再算一遍作为**总预算**（= 每线程上限 × T，与父类
        //    `(long) getMaxParallel() * getMultipleThreads()` 逐位同值）。
        //    ⚠️ 这里的入参 getCurrentParallel() 【已经含 ÷T】（它就是 getEffectiveParallel()）
        //    ⇒ parallelBudget 内部再取一次 min 是【幂等】的，不会重复除。
        //    ShanhaiParallelBudget#parallelBudget(本机上限, 电力上限, 跨配方线程数)
        //      = min(本机上限, 电力上限 ÷ T) × T
        //    ⛔ 第三轮那句「= min(本机上限, 电力上限 ÷ T)」（不乘回 T）已作废：
        //       它让引擎侧与父类那条路差 T 倍，且把原生链的「本机上限 × T」弄坏（本轮修回）。
        //    ⚠️ T = 1 时（线程槽空 = 今天绝大多数场合）新旧逐位相同，见加载期自检 ⑦ 与判据 A 段。
        // 🔴 2026-10-02 第十一轮（用户实机 bug）：入参从 getEnergyParallel() 改成
        //    energyCapForBudget() —— 能源仓那头是无限（创造模式能源仓 / 无线电网输入终端）时它返回
        //    ENERGY_CAP_NONE（= 不限制）⇒ 本方法退回「本机上限 × T」，与「电力自动关着」逐位同值。
        //    ⛔ 改前：∞ 那一支只把电上限抬到本机上限，而后面照样除以 T ⇒ 2048 ÷ 9 = 227
        //       （实测：显示 227、而引擎真正吃的总预算是 min(2048, 2048÷9) × 9 = 2043）。
        return ShanhaiParallelBudget.parallelBudget(
                getMachine().getCurrentParallel(), getMachine().energyCapForBudget(), getMultipleThreads());
    }

    // ═════════════════════ 🔴 电力自动（2026-10-02 新增 · 用户定方案） ═════════════════════

    /**
     * 🔴 <b>按「能源仓总功率 ÷ 每并行耗电」算本轮的能量上限，写回机器。</b>
     *
     * <h2>用户原话（逐字，这就是规格）</h2>
     * <blockquote>「好的，我的方案就是通过计算能源仓可以提供的总功率来确定并行数，
     * 注意机器是可以放2个能源仓的，还可以放2个不同的能源仓的，所以你需要仔细计算，
     * 我们通过总功率和此配方的功率来计算并行数，
     * （若是无线电网输入终端，或者创造能源仓则直接把并行拉到最大，这个就不需要我们算了）」</blockquote>
     *
     * <h2>算式（三行，与 {@link ParallelPowerBudget} 一份算术）</h2>
     * <pre>
     *   k = 配方每 tick 耗电 × 引擎耗能乘数 × N5 减免系数        （ParallelPowerBudget#perParallelMilliCost）
     *       🔴 2026-10-02 第七轮起：k 以【毫 EU/t】定点 long 参与运算，中途不许 round
     *   Pe = Σ 各能源仓的稳态每 tick 可付出 EU                  （EnergyHatchPower，逐个判型后求和）
     *   p  = min(max(1, floor(Pe × 1000 ÷ k毫)), 原本上限)       （Pe 无限 ⇒ 直接取原本上限）
     * </pre>
     *
     * <h2>🔴 只在【配方开始时】算 ⇒ 结构上不可能打断正在跑的配方</h2>
     * 本方法只被 {@link #calculateParallels()} 调，而它只在 {@code getRecipe()}（= 新一轮找配方）里被调；
     * 正在跑的那一轮用的是已经装配好的 {@code lastRecipe}，{@code handleMultipleRecipeWorking()} 不重算并行
     * ⇒ <b>中途绝不改</b>（用户点名的一条）。
     *
     * <h2>多候选（跨配方线程 ≥ 2）时取最保守的那一条</h2>
     * 能量上限管的是<b>这一轮合计</b>的并行；候选各条的 {@code k} 不同时，取<b>最大</b>的 k
     * ⇒ 算出的上限只会偏小、不会偏大（偏小的后果是"没吃满电力"，偏大的后果是"跑不动"）。
     * 单条候选（正常玩法）时就是那一条本身，精确。
     *
     * @return 本轮的能量上限；{@code 0} = 没有上限（开关关着 / 没能源仓 / 认不出仓）
     */
    private long shanhai$updatePowerParallelCap() {
        final PrimordialModuleMachine module = getMachine();
        // 🔴 开关关着 ⇒ 立即返回，连候选集都不查 ⇒ 与改动前【逐值同结果、同开销】。
        if (!module.isPowerAutoParallel()) {
            return ParallelOverrideMachine.ENERGY_CAP_NONE;
        }
        try {
            final Set<GTRecipe> candidates = lookupRecipeSet();
            final long ceiling = module.getParallelOverrideCeiling();
            if (candidates.isEmpty()) {
                // 🔴 2026-10-02 第二轮：这里**不再**写一个裸的"没有上限"，而是把**原因**一起写下去。
                //    上一轮这一档与"还没算过""异常"在面板上长得一模一样（都是「尚未算出」），
                //    玩家无法区分"机器坏了"和"此刻确实没有可跑的配方"。见 EnergyCapState 的注释。
                module.setEnergyCap(ParallelOverrideMachine.EnergyCapState.NO_CANDIDATE,
                        ParallelOverrideMachine.ENERGY_CAP_NONE,
                        ParallelOverrideMachine.ENERGY_CAP_NONE);
                shanhai$logCapSkipped(module, "NO_CANDIDATE", "本轮没有可跑的候选配方");
                return ParallelOverrideMachine.ENERGY_CAP_NONE;
            }
            final double engineMultiplier = getEuMultiplier();
            final double n5Factor = shanhai$n5Factor();
            long worstCostMilli = 0L;
            long firstEut = 0L;
            long worstEut = 0L;
            for (GTRecipe candidate : candidates) {
                final long eut = RecipeHelper.getInputEUt(candidate);
                if (firstEut == 0L) {
                    firstEut = eut;
                }
                // 🔴 2026-10-02 第七轮：改成【毫 EU/t】口径（不再中途 round）。
                //    用户那台模块实机读数：P=655,360、配方 42、N5=5% ⇒ 真实 k=2.1；
                //    旧写法 round(2.1)=2 ⇒ 并行 327,680 ⇒ 整机 688,128 > 655,360 ⇒「电力输入不足」。
                //    算式与理由见 ParallelPowerBudget#perParallelMilliCost 的 javadoc。
                // 🔴🔴 2026-10-02 第九轮（用户点名：「等一下，不同配方耗电是不同的，你不会取静态的数值了吧」）：
                //    候选【多条】时 k 取【最贵的那一条】—— 不是头一条、也不是任何固定值。
                //    理由（k 取大 ⇒ 并行变小 ⇒ 只会少用电力，不会超功率）与「按原耗电比 ≡ 按乘完减免比」
                //    的单调性证明，全部在 ParallelPowerBudget#worstPerParallelMilliCost 的 javadoc 里
                //    —— 那是全工程【唯一一份】"多候选取哪个"的实现（本处不再自己写一遍 Math.max）。
                //    ⚠️ worstEut 只给日志用（argmax 的可读性），【不参与任何算术】。
                final long previousWorst = worstCostMilli;
                worstCostMilli = ParallelPowerBudget.worstPerParallelMilliCost(
                        previousWorst, eut, engineMultiplier, n5Factor);
                if (worstCostMilli > previousWorst) {
                    worstEut = eut;
                }
            }
            final long totalPower = EnergyHatchPower.totalSteadyPowerPerTick(module);
            final long cap = ParallelPowerBudget.parallelFromPowerMilli(totalPower, worstCostMilli, ceiling);
            // 🔴 「为什么是这个数」与数值【一次写下去】，面板据此说人话。
            //    判据 = ParallelOverrideMachine.classify（纯函数、唯一一份、离线可断言）——
            //    它内部那三档与 parallelFromPowerMilli 的三条兜底【逐条对应】，不许各写一份。
            final ParallelOverrideMachine.EnergyCapState state =
                    ParallelOverrideMachine.classify(true, totalPower);
            module.setEnergyCap(state, cap, worstCostMilli);
            shanhai$logPowerParallelCap(module, totalPower, firstEut, worstEut, worstCostMilli, cap, ceiling,
                    engineMultiplier, n5Factor, candidates.size());
            return cap;
        } catch (Throwable t) {
            // 🔴 任何意外都不许把配方打死：退回"不做电力限制"（= 改动前行为）。
            //    ⛔ 2026-10-02 第二轮订正：上一轮这里只打 `t.toString()` ⇒ **栈整个丢了**，
            //       于是"为什么算不出"在下一次实机里依然查不出来（本轮用户报的就是这件事）。
            //       ⇒ 改成把 Throwable 本体交给 logger（完整栈），并把状态写成 ERROR。
            ShanhaiMod.LOGGER.warn("[SHANHAI-POWER-PARALLEL] 模块电力上限计算失败，本轮不做电力限制"
                    + "（完整栈如下；状态已写成 ERROR，面板会显示「电上限异常」）：", t);
            module.setEnergyCap(ParallelOverrideMachine.EnergyCapState.ERROR,
                    ParallelOverrideMachine.ENERGY_CAP_NONE,
                    ParallelOverrideMachine.ENERGY_CAP_NONE);
            return ParallelOverrideMachine.ENERGY_CAP_NONE;
        }
    }

    /**
     * 🔴 <b>「这一轮为什么没算出电上限」的可 grep 证据行</b>（同形只打一次，不刷屏）。
     *
     * <p>为什么必须有：面板能说出的只有一句短话；<b>"是哪种没算出、当时机器处于什么状态"</b>
     * 只有日志讲得清。用户在游戏里跑一次、贴这一行，就能把范围缩到一格。
     */
    private static final Set<String> shanhai$capSkippedLogged =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private void shanhai$logCapSkipped(PrimordialModuleMachine module, String code, String why) {
        if (shanhai$capSkippedLogged.size() > 256) {
            return;
        }
        final String signature = code + "|" + why + "|" + module.getDefinition().getId();
        if (!shanhai$capSkippedLogged.add(signature)) {
            return;
        }
        ShanhaiMod.LOGGER.info("[SHANHAI-POWER-PARALLEL] 模块「{}」（{}）：本轮**没有算出**电力上限（{}：{}）"
                        + " ⇒ 本轮不做电力限制（并行完全按原本口径：物质模块并行表 / 玩家填的上限）。",
                module.getDefinition().getId(), module.getPos(), code, why);
    }

    /**
     * N5 耗能减免系数 = {@code PrimordialRecipeEffects.reductionFactor(门控等级)}。
     *
     * <p>门控等级 = 主机专属槽里那个物质模块的等级（{@code host.moduleSlotBonus()}，0 = 未生效）。
     * <b>与 {@code buildFinalNormalRecipe} 里那句逐字同源</b>（同一个表达式，不另起一份口径）。
     */
    private double shanhai$n5Factor() {
        final PrimordialModuleMachine module = getMachine();
        final int gateBonus = module.getHost() == null ? 0 : module.getHost().moduleSlotBonus();
        return PrimordialRecipeEffects.reductionFactor(gateBonus);
    }

    /** 已报过的「电力上限」签名 —— 同一个组合只报一次，不刷屏（与既有几处探针同一条纪律）。 */
    private static final Set<String> shanhai$powerParallelLogged =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * 🔴 <b>可 grep 的实机判据行：把"这个并行数是怎么算出来的"四个量一次给全。</b>
     *
     * <p>为什么必须有：本功能的全部数字（总功率 / 每并行耗电 / 电力上限）在冒烟（无头专服）里
     * <b>一个都出不来</b>（只在真有成型机器跑配方时才打）⇒ 用户进游戏跑一次、贴这一行，
     * 就能核对"到底认成了哪种仓、乘数取了多少"。
     * <p>⚠️ 只在<b>签名变化</b>时打一行：{@code totalPower / k / cap / 仓型汇总} 任一变化才打，
     * 否则每轮配方一行会把日志刷屏（本项目已为此返工过一次）。
     */
    private void shanhai$logPowerParallelCap(PrimordialModuleMachine module, long totalPower, long firstEut,
                                             long worstEut, long worstCostMilli, long cap, long ceiling,
                                             double engineMultiplier, double n5Factor, int candidateCount) {
        final String kindSummary = EnergyHatchPower.kindSummary(module);
        final String signature = totalPower + "|" + worstCostMilli + "|" + cap + "|" + kindSummary;
        if (!shanhai$powerParallelLogged.add(signature)) {
            return;
        }
        // 🔴 2026-10-02 第九轮：候选【多条】时，参与运算的是【最贵】那条（= 下面"每并行"那个数），
        //    而旧措辞把「首条配方」紧挨着印在它前面 ⇒ 读日志的人会以为并行是拿首条算的
        //    （用户就是这么问出来的：「你不会取静态的数值了吧」）。⇒ 两个数都印、并且说清哪个进了算式。
        ShanhaiMod.LOGGER.info("[SHANHAI-POWER-PARALLEL] 模块「{}」：{} ⇒ 生效并行上限 {}；"
                        + "（候选 {} 条：最贵 {} EU/t ⇒ 引擎耗能乘数 {} × N5 减免 {} ⇒ 每并行 {} EU/t"
                        + "【进算式的是最贵那条；首条 {} EU/t，候选只有一条时两个数相同】）"
                        + "【本行已改为「同一形态只打一次」】",
                module.getDefinition().getId(),
                ParallelPowerBudget.describe(totalPower, EnergyHatchPower.hatchCount(module), kindSummary,
                        worstCostMilli, cap, ceiling),
                cap, candidateCount, worstEut, engineMultiplier, n5Factor,
                ParallelPowerBudget.formatMilli(worstCostMilli), firstEut);
    }

    @Override
    protected @Nullable ParallelData calculateParallels() {
        // 🔴 2026-10-02 第十轮：「本轮」= 「同一轮两次取候选是否同集合」探针的【观察窗】。
        //    开在这里是因为本方法正是"一轮"的边界：它内部会取候选【两次】
        //    （① shanhai$updatePowerParallelCap() 算电上限时一次；② 分配并行时一次 ——
        //     后者可能是本方法自己调的，也可能是父类 MutableRecipesLogic#calculateParallels()
        //     里那一句 invokevirtual lookupRecipeSet()，它走同一个虚方法）。
        //    窗口外一律不观察（见 shanhai$observeCandidates）。
        //    🔴 2026-10-02 第十一轮：finally 里顺便把【累计值心跳】打到日志
        //       （首次必打、之后每 5 分钟最多一行）—— 否则"一致时不打印"会让日志里
        //       `[SHANHAI-WORST-COST]` 0 行，"比过很多次都一致"与"一次没跑到"分不清。
        CandidateSetConsistency.beginRound();
        try {
            return shanhai$calculateParallelsInRound();
        } finally {
            CandidateSetConsistency.endRound(ShanhaiMod.LOGGER::info);
        }
    }

    /** {@link #calculateParallels()} 的本体；拆出来只为让观察窗用一个 try/finally 包住全部 return 口。 */
    private @Nullable ParallelData shanhai$calculateParallelsInRound() {
        final PrimordialModuleMachine module = getMachine();
        // 🔴 2026-10-02「电力自动」：必须【先】算并写回机器，再读预算 ——
        //    因为下面 shanhai$parallelBudget() → getCurrentParallel() → getEffectiveParallel()
        //    里压着 applyEnergyCap()，读的正是这里刚写进去的那个值。
        //    开关关着时本调用【立刻返回且一次 lookup 都不做】⇒ 与改动前同开销、同结果。
        shanhai$updatePowerParallelCap();
        final long limit = shanhai$parallelBudget();

        // ══ ① 恒等快路：线程槽空（threads == 1） ⇒ 候选最多 1 条 ══
        //   我们的 lookupRecipeSet() 保证「返回条数 ≤ threads」，所以 threads == 1 时
        //   下面那条公平分支永远走不到。这一路【一次 lookup 都不多做】，
        //   与改动前逐字节相同（≤ int 走父类，> int 走既有的 long 贪心通道）。
        //   🔴 2026-10-02 第五轮：「≤ int 走父类」那条路上 ÷T 现在【也生效】——
        //      父类自己按 `(long) getMaxParallel() × getMultipleThreads()` 重算，而
        //      getMaxParallel() 的源头就是 getEffectiveParallel() → applyEnergyCap(base, T)
        //      ⇒ 父类那条路与本方法算出的 limit 【逐位同值】。判据断言这条恒等式。
        if (getMultipleThreads() <= 1) {
            if (limit <= (long) Integer.MAX_VALUE) {
                return super.calculateParallels();
            }
            shanhai$logLongParallelBudget(limit);
            return PrimordialRecipeEffects.greedyAllocateWithLongLimit(
                    lookupRecipeSet(), limit, module,
                    (recipe, remain) -> calculateParallel(module, recipe, remain));
        }

        final Set<GTRecipe> candidates = lookupRecipeSet();
        if (candidates.size() <= 1) {
            // ② 多线程槽、但这一轮只有 ≤1 条可跑
            //   ⇒ 公平分配与贪心【逐值相等】（share = min(需求, 预算/1) = min(需求, 预算)），
            //     仍走既有分支，保证「单条候选时分配结果与今天一致」。
            if (limit <= (long) Integer.MAX_VALUE) {
                return super.calculateParallels();
            }
            shanhai$logLongParallelBudget(limit);
            return PrimordialRecipeEffects.greedyAllocateWithLongLimit(
                    candidates, limit, module,
                    (recipe, remain) -> calculateParallel(module, recipe, remain));
        }

        // ══ ③ 真正的跨配方并行：≥2 条候选 ⇒ 公平分配（上游 fair 版，见被调方注释） ══
        if (limit > (long) Integer.MAX_VALUE) {
            shanhai$logLongParallelBudget(limit);
        }
        final ParallelData fair = PrimordialRecipeEffects.fairAllocateWithLongLimit(
                candidates, limit, module,
                (recipe, cap) -> calculateParallel(module, recipe, cap));
        shanhai$logCrossRecipeAllocation(candidates.size(), limit, fair);
        return fair;
    }

    /** 已报过的「候选条数 / 分配结果」签名（每个不同的组合只报一次，不刷屏）。 */
    private static final Set<String> shanhai$crossRecipeLogged =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * 🔴 <b>跨配方分配的可 grep 证据行 —— 用户「挤占修好了没有」的唯一直接判据。</b>
     *
     * <h2>为什么必须有这一行</h2>
     * 冒烟（无头专服）里 {@code [SHANHAI-MODULE-ENGINE]} 与 {@code [SHANHAI-PARALLEL-LONG]}
     * <b>实测都是 0 条</b>（它们只在"世界上真有一台成型模块在跑"时才打）⇒ 这一条改动
     * <b>冒烟覆盖不到</b>。所以把判据做成"用户进游戏跑一次就能读"的形态：
     * <pre>
     *   [SHANHAI-CROSS-RECIPE] 跨配方并行：候选 N 条 / 预算 L ⇒ 分配 p=[…]（成料 M 条）
     * </pre>
     * 判读方式：
     * <ul>
     *   <li><b>挤占已修</b>：{@code p} 数组里<b>不止一个非零项</b>，且
     *       {@code Σp} 接近预算（公平分配把预算摊给多条）；</li>
     *   <li><b>仍然挤占</b>：只有一个非零项、且它 ≈ 预算（= 贪心的老行为）；</li>
     *   <li><b>成料 M &lt; 候选 N</b>：某条候选在扣料相失败了（多半是共享原料不够，
     *       例如两条配方共吃同一个编程电路而仓里只有一个） ⇒ 它那条的额度没有被重分，
     *       <b>这是本工程 fair 版与上游 fair 版的已知差异</b>（见分配函数的注释）。</li>
     * </ul>
     */
    private static void shanhai$logCrossRecipeAllocation(int candidates, long limit,
                                                         @Nullable ParallelData fair) {
        final StringBuilder signature = new StringBuilder();
        signature.append(candidates).append('/').append(limit).append('/');
        if (fair == null) {
            signature.append("null");
        } else {
            final long[] parallels = fair.getParallels();
            long sum = 0L;
            int nonZero = 0;
            for (long p : parallels) {
                sum += p;
                if (p > 0L) {
                    nonZero++;
                }
                signature.append(p).append(',');
            }
            signature.append('|').append(nonZero).append('/').append(sum);
        }
        final String key = signature.toString();
        if (shanhai$crossRecipeLogged.size() > 256 || !shanhai$crossRecipeLogged.add(key)) {
            return;
        }
        if (fair == null) {
            ShanhaiMod.LOGGER.info("[SHANHAI-CROSS-RECIPE] 跨配方并行：候选 {} 条 / 预算 {} ⇒ "
                            + "公平分配【一条都没成料】（全部候选在扣料相失败）⇒ 本 tick 不跑配方。",
                    candidates, limit);
            return;
        }
        final long[] parallels = fair.getParallels();
        long sum = 0L;
        int nonZero = 0;
        for (long p : parallels) {
            sum += p;
            if (p > 0L) {
                nonZero++;
            }
        }
        ShanhaiMod.LOGGER.info("[SHANHAI-CROSS-RECIPE] 跨配方并行【公平分配】：可跑候选 {} 条，"
                        + "预算 {}（= 本机并行上限 × 跨配方线程数）⇒ 分配 p={}（成料 {} 条，Σp={}）；"
                        + "算法 = 每条先拿 min(需求, 预算/条数)，余量按轮转水填充"
                        + "（上游 calculateParallelsWithFairAllocation 逐句）。"
                        + "🔴 判据：p 里【不止一个非零项】= 不同配方条目真的同时在跑；"
                        + "若只有一个非零项且它 ≈ 预算 ⇒ 仍是老的贪心挤占。",
                candidates, limit, java.util.Arrays.toString(parallels), nonZero, sum);
    }

    /** 上一次记下的 long 预算（只在数值变化时打一行，不刷屏；{@code Long.MIN_VALUE} = 还没打过）。 */
    private static long shanhai$lastLoggedLongBudget = Long.MIN_VALUE;

    // ═══════════ 🔴 已删除：calculateParallel 里的"批处理放大"（2026-09-27 第二轮） ═══════════
    //
    // 2026-09-27 第一轮曾在这里覆写 calculateParallel，把并行份数 p 乘上批处理份数 N：
    //     return PrimordialRecipeEffects.scaleParallelForBatch(machine, match, super.calculateParallel(…));
    // 🔴 用户实机判据原文：「批处理应该是不影响机器的耗电的，而且批处理应该是延长机器运行时间的」
    //   ⇒ 那个修法让下游的 multipleRecipe 把 tickInputs(EU/t) 一起乘了 N（⇒ 耗电 ×N），
    //     而耗时被模块 N6 钉回原值（⇒ 耗时不变）—— 两条都与规格相反：
    //     **它做出来的是"并行"而不是"批处理"**（并行才是"耗时不变、耗电 ×N"）。
    //   故整体删除：并行分配回到上游原样（本类不再覆写 calculateParallel），
    //   批处理改由 PrimordialRecipeEffects.applyBatchProcessing 在【成品】上施加
    //   （输入 ×N／输出 ×N／耗时 ×N／EU/t 不变），调用点见 buildFinalNormalRecipe 的 ⑥。

    /** 预算超过 int 范围时的<b>一次性可 grep 证据行</b>（值变化才打）。 */
    private static void shanhai$logLongParallelBudget(long limit) {
        if (limit == shanhai$lastLoggedLongBudget) {
            return;
        }
        shanhai$lastLoggedLongBudget = limit;
        ShanhaiMod.LOGGER.info("[SHANHAI-PARALLEL-LONG] 模块并行预算已走 long 通道："
                        + "engineLimit={}（int 桥会把它压成 {}）⇒ 这一档正是并行表末三档之一；"
                        + "分配算法 = 上游 greedy（long 版），预算本身不再被 int 钳制。",
                limit, Integer.MAX_VALUE);
    }

    /** 懒判定路由：发电模块退回原生链；其余留在引擎路径。只跑一次，日志可 grep。 */
    private void shanhai$resolveRouting() {
        if (shanhai$routingResolved) {
            return;
        }
        shanhai$routingResolved = true;
        final boolean generator = getMachine().getDefinition().isGenerator();
        if (generator) {
            setUseMultipleRecipes(false);
            ShanhaiMod.LOGGER.info("[SHANHAI-MODULE-ENGINE] 发电模块退回原生链（EUt 是输出语义，引擎会算成深负值）：{}",
                    getMachine().getPos());
        } else {
            ShanhaiMod.LOGGER.info("[SHANHAI-MODULE-ENGINE] 模块已接多配方引擎：跨配方线程 = {}，候选配方上限 = {} 条；{}",
                    getMultipleThreads(), getMultipleThreads(), getMachine().getPos());
        }
    }

    /**
     * 🔴 <b>2026-09-28：跨配方并行（线程）真正接上引擎 —— 本方法与
     * {@link #lookupRecipeSet()} 是本任务的落点。</b>
     *
     * <h2>上游的取值链（字节码实证，不是推断）</h2>
     * <pre>
     *   javap -c -p libs/gtladditions-3.2.8Custom-fix1.jar com.gtladd.gtladditions.api.machine.logic.MutableRecipesLogic
     *   public int getMultipleThreads();
     *       0: aload_0
     *       1: invokevirtual #313   // getMachine()
     *       4: checkcast     #584   // class com/gtladd/g/.../IThreadModifierMachine
     *       7: invokeinterface #587 // IThreadModifierMachine.getAdditionalThread:()I
     *      12: ifle          30
     *      15..27:                 // 返回 getAdditionalThread()
     *      30: iconst_1
     *      31: ireturn             // getAdditionalThread() &lt;= 0 ⇒ 返回 1
     * </pre>
     * ⇒ 上游的语义是「机器上挂的<b>线程仓部件</b>给多少」。本工程的 24 台模块
     * ({@code PrimordialModuleMachine implements IThreadModifierMachine}) <b>从来不挂线程仓</b>，
     * 而 {@code IThreadModifierMachine.getThreadPartMachine()} 的默认实现返回 {@code null}
     * （字节码 {@code aconst_null; areturn}）⇒ {@code getAdditionalThread()} 默认 = 0
     * ⇒ <b>这就是"模块侧一直是 1"的原因</b>（不是某处写死了 1，而是默认值兜底）。
     *
     * <h2>为什么不覆写 {@code getAdditionalThread()} 而是覆写本方法</h2>
     * 上游那句是 {@code additionalThread > 0 ? additionalThread : 1} ——
     * <b>值为 1 时它把基础值也吃掉了</b>。而用户规格要求的是
     * {@code 最终 = 1 + 额外}，空槽必须是 <b>1</b>、1 号残片 ×1 必须是 <b>3</b>。
     * 若走 {@code getAdditionalThread() = 2}，上游会返回 <b>2 而不是 3</b> ⇒ 与规格差 1。
     * ⇒ 只覆写本方法、直接返回机器算好的最终值，<b>把上游那个三元表达式整个绕开</b>。
     *
     * <h2>取值的唯一来源</h2>
     * {@link PrimordialModuleMachine#getCrossRecipeThreads()}（= {@code 1 + 2^N × 线程槽数量}）。
     * 本方法<b>不做任何算术</b>：算术只有一处（{@code ShanhaiConcurrencyTables.finalThreads}），
     * 免得"显示 3、预算按 2 算"这种静默分叉。
     * <p>本值同时决定两件事（都在父类 {@code calculateParallels()} 里）：
     * ①并行预算 = {@code getRecipeLogicMaxParallel() × 本值}；
     * ②候选配方条数上限 = 本值（见 {@link #lookupRecipeSet()}）。
     */
    @Override
    public int getMultipleThreads() {
        return getMachine().getCrossRecipeThreads();
    }

    /**
     * 🔴 <b>值 = 1（以及任意 N）时的候选集上限 —— 我们替上游改的语义</b>（理由见类注释）。
     *
     * <p>上游 {@code MutableRecipesLogic.lookupRecipeSet()} 返回<b>全部</b>可跑配方，
     * 贪心分配会把 {@code getMaxParallel() × getMultipleThreads()} 的预算摊到多条上并合并成一条。
     * 本覆写只保留顺序最前的 {@code getMultipleThreads()} 条 ⇒ 值 = 1 时恒为一条（与今天的行为同形）。
     *
     * <h2>⛔ 2026-09-28 订正：「LinkedHashSet 保上游迭代顺序」这句原文<u>是错的</u></h2>
     * <pre>
     * ⛔ 旧注释原文（作废，逐字留档）：
     *    {@code LinkedHashSet} 保持上游迭代顺序（"取前 N 条"必须是确定性的，
     *    否则同一批输入会跑出不同配方）。
     * </pre>
     * 🔴 <b>病根（字节码实证，2026-09-28 查实）</b>：上游返回的<b>不是</b>有序集合 ——
     * <pre>
     *   MutableRecipesLogic.lookupRecipeSet() 非锁分支（javap -c 原文）：
     *     115: getfield machine
     *     119: invokeinterface getRecipeType()
     *     124: invokevirtual   GTRecipeType.getLookup()
     *     140: invokevirtual   GTRecipeLookup.getRecipeIterator(holder, predicate)   ← 这里还有顺序
     *     153: invokestatic    SequencesKt.asSequence(Iterator)
     *     156: new             it/unimi/dsi/fastutil/objects/ObjectOpenHashSet       ← 🔴 顺序在这里被销毁
     *     166: invokestatic    SequencesKt.toCollection(Sequence, Collection)
     *     172: areturn
     * </pre>
     * ⇒ 上游返回的是 {@code ObjectOpenHashSet}，我们那个 {@code LinkedHashSet} 保的是
     * <b>"按哈希桶顺序插入"的顺序</b>，也就是<b>哈希序</b>，<b>不是</b>上游 {@code RecipeIterator} 的顺序。
     * <p><b>它稳不稳定？</b>—— <b>稳</b>，但<b>没有意义</b>：
     * {@code GTRecipe.hashCode()} 的字节码是 {@code id.hashCode()}（{@code ResourceLocation} 的字符串哈希），
     * 不含 identity hash ⇒ 同一批候选配方在任何一次运行里都会被排成同一个（但<b>与配方优先级无关</b>）的顺序。
     * ⚠️ 反过来说：<b>往包里增删任何一条配方都可能把桶布局换掉</b>，届时"取到哪 N 条"会整体漂移。
     * <p><b>为什么一直没被发现</b>：{@code threads == 1} 时最多只取 1 条，
     * 而"哪一条"在多数机器上只影响显示不影响产出 ⇒ 被掩盖了。本轮线程真的打开
     * （最多 {@code 1 + 1024×64 = 65,537} 条），这条必须修。
     *
     * <h2>修法：截断时走<b>上游自己那台</b> {@code RecipeIterator}</h2>
     * 内容仍然完全取 {@code super.lookupRecipeSet()}（<b>一个元素都不增删</b>，
     * 保证"我们改的只是顺序"），只是截断时按上游迭代器的真实顺序重新走一遍，
     * 并用 {@code all.contains(...)} 过滤回父类的集合（{@code GTRecipe.equals} 字节码 = 比 {@code id}，可靠）。
     * <ul>
     *   <li>谓词用 {@code this::checkRecipe} —— 与上游 {@code invokedynamic} 捕获的<b>同一个虚方法</b>
     *       （本类覆写了它，加了自己的电压闸门；两边都走覆写后的版本，所以集合内容一致）；</li>
     *   <li>锁配方分支（{@code all.size() &lt;= threads}，父类只返回 0/1 条）<b>原样返回</b>，不进这条路；</li>
     *   <li>{@code checkRecipe} 的副作用是幂等的（电压闸门日志按 (机器等级,配方等级) 去重、失败原因只是覆写同一个字段），
     *       重复调用不会产生额外日志或错值。</li>
     * </ul>
     *
     * <h2>N 很大时会不会出问题（用户第 5 问，逐条回答）</h2>
     * <ul>
     *   <li><b>越界</b>：没有。全流程没有"按 N 索引数组"的地方，截断用 {@code limited.size() < threads} 收敛。</li>
     *   <li><b>内存</b>：{@code LinkedHashSet} 最多装 {@code min(N, 候选数)} 条引用；
     *       单台机器的候选集本身就是那个配方类型的全部可跑配方 ⇒ 上界不变。
     *       上游 {@code greedyAllocateWithLongLimit} 另建 3 个同长度列表，也是同一量级。</li>
     *   <li><b>性能</b>：截断循环最多 {@code min(N, 候选数)} 次；额外成本 = 一次 {@code getRecipeIterator}
     *       （与父类刚才那次同量级）。N 越大反而<b>越接近"不截断"</b>，即越接近父类原行为。</li>
     *   <li><b>候选不够 N 条</b>：{@code all.size() &lt;= threads} ⇒ 直接返回 {@code all}（= 全部），
     *       与上游同形，不是错误。</li>
     * </ul>
     */
    @Override
    protected @NotNull Set<GTRecipe> lookupRecipeSet() {
        final Set<GTRecipe> all = super.lookupRecipeSet();
        final int threads = Math.max(1, getMultipleThreads());
        if (all.size() <= threads) {
            return shanhai$observeCandidates(all);
        }
        // 上游那台迭代器：顺序的唯一权威来源（super 的返回值已经把它丢进哈希集了）。
        final Iterator<GTRecipe> ordered = getMachine().getRecipeType().getLookup()
                .getRecipeIterator(getMachine(), this::checkRecipe);
        final Set<GTRecipe> limited = new LinkedHashSet<>();
        while (ordered.hasNext() && limited.size() < threads) {
            final GTRecipe recipe = ordered.next();
            if (all.contains(recipe)) {
                limited.add(recipe);
            }
        }
        // 兜底：万一上游换了遍历实现导致一条都没匹配上，宁可退回父类的结果，也不要返回空集（"机器不动"）。
        return shanhai$observeCandidates(limited.isEmpty() ? all : limited);
    }

    /**
     * 🔴 <b>把"这一次取到的候选集"喂给运行期一致性探针，再原样返回</b>（2026-10-02 第十轮）。
     *
     * <p>落点为什么选在这里：{@code lookupRecipeSet()} 是本类<b>唯一</b>的候选集出口
     * ⇒ 不管是"算电上限那次"（{@link #shanhai$updatePowerParallelCap()}）还是"分配并行那次"
     * （本类自己的调用点 / 父类 {@code MutableRecipesLogic#calculateParallels()} 里那一次，
     * 它走的是同一个虚方法），都必须经过这里。
     *
     * <p>⚠️ 行为必须<b>逐位不变</b>：观察窗之外（{@code CandidateSetConsistency.isRoundActive()} 为假）
     * 本方法<b>一个字符串都不建</b>，直接返回原集合对象本身（return 的是同一个引用）。
     */
    private Set<GTRecipe> shanhai$observeCandidates(Set<GTRecipe> set) {
        if (CandidateSetConsistency.isRoundActive()) {
            final List<String> ids = new java.util.ArrayList<>(set.size());
            for (GTRecipe candidate : set) {
                ids.add(String.valueOf(candidate.id));
            }
            CandidateSetConsistency.observe("模块", String.valueOf(getMachine().getDefinition().getId()), ids,
                    ShanhaiMod.LOGGER::warn);
        }
        return set;
    }

    /**
     * 🔴 <b>配方电压等级闸门 —— 2026-09-26 <u>恢复</u>（此前是本类【刻意删掉】的那一条）。</b>
     *
     * <h2>1. 父类原文（字节码实证，不是推断）</h2>
     * <pre>
     *   javap -c -p libs/gtladditions-3.2.8Custom-fix1.jar \
     *         com.gtladd.gtladditions.api.machine.logic.MutableRecipesLogic
     *   protected boolean checkRecipe(GTRecipe recipe);
     *       7: … getfield RecipeLogic.machine … checkcast IRecipeCapabilityHolder
     *      15: invokestatic RecipeRunnerHelper.matchRecipe:(…,GTRecipe;)Z
     *      18: ifeq 82                       ← matchRecipe 假 ⇒ false
     *      21: aload_1 / 22: invokestatic IGTRecipe.of:(GTRecipe;)LIGTRecipe;
     *      25: invokeinterface IGTRecipe.getEuTier:()I
     *      30: getfield machine / 34: invokevirtual WorkableElectricMultiblockMachine.getTier:()I
     *      37: if_icmpgt 82                  ← <b>euTier &gt; machineTier ⇒ false（这里就是我们删掉的那条）</b>
     *      40: recipe.checkConditions(this) …
     * </pre>
     * ⇒ 上游判据是三条合取：{@code matchRecipe && euTier &lt;= machine.getTier() && checkConditions}。
     *
     * <h2>2. 为什么当初删了它（留档，不许再当成"可以删"的理由）</h2>
     * 见本类旧注释的原文：模块 {@code tier} 当时被认为"固定 9"，而这条闸门会让 {@code euTier &gt; 9}
     * 的配方<b>从候选集里静默消失</b>。当时的选择是"整条删掉"。
     *
     * <h2>3. 🔴 为什么现在必须装回去（用户 2026-09-26 实机报的 bug）</h2>
     * 用户的机器电压来自它自己的<b>能源仓</b>（实测用的是创造能源仓，配成 ULV 8V/1A）。
     * 电压等级闸门一删，机器就<b>无条件</b>接受任何 {@code euTier} 的配方，与"这个机器有多少电压"脱钩
     * ⇒ 用户实机：同一个能源仓配置下，原版 GTL 的<b>大型挤压机</b>老实报
     * 「配方失败原因：电压等级未达到配方要求」，而我们的<b>原初山海调试模块</b>照跑 102/205 EU/t 的 MV/HV 配方。
     * <p>⚠️ <b>代价（必须让用户知情）</b>：装回去之后，<b>原本能跑的"超压配方"会跑不动</b>，
     * 显示为 gtlcore 现成的 {@code FAIL_VOLTAGE_TIER}「电压等级未达到配方要求」。这正是用户要的口径。
     *
     * <h2>4. 失败原因写进哪条链（不动自己造显示）</h2>
     * 拦下时写 {@link RecipeResult#FAIL_VOLTAGE_TIER}（gtlcore 自己的枚举），经
     * {@code RecipeResult.of(machine, …)} → {@code IRecipeStatus.setRecipeStatus} 落进
     * {@link org.gtlcore.gtlcore.api.machine.trait.IRecipeStatus}：
     * <ul>
     *   <li><b>Jade</b>：{@code org.gtlcore.gtlcore.mixin.gtm.RecipeLogicProviderMixin#write} 把
     *       {@code getRecipeStatus().reason()} 写成 NBT {@code reason}，{@code addTooltip} 用
     *       {@code gtceu.recipe.fail.reason}（"配方失败原因：%s"）渲成红字；</li>
     *   <li><b>机器 GUI</b>：{@code org.gtlcore.gtlcore.mixin.gtm.fix.WorkableElectricMultiblockMachineMixin
     *       #addDisplayText} 把同一个 {@code reason} 以 {@code ChatFormatting.RED} 追加进文本行
     *       （我们的模块正好 extends WorkableElectricMultiblockMachine ⇒ 这条混入对我们成立）。</li>
     * </ul>
     */
    @Override
    protected boolean checkRecipe(@NotNull GTRecipe recipe) {
        if (!RecipeRunnerHelper.matchRecipe((IRecipeCapabilityHolder) getMachine(), recipe)) {
            return false;
        }
        // 🔴 2026-10-03 改造：「额外挂载槽 ×3」闸门（四类配方条件 + 热力；判定核 ShanhaiHeatGate，可离线取证）。
        //    插在【电压闸门之前】：这两条同时不满足时，先报本工程新加的那条更具体的。
        if (!shanhai$extraMountGateAllows(recipe)) {
            return false;
        }
        final int recipeEuTier = IGTRecipe.of(recipe).getEuTier();
        final int machineTier = getMachine().getTier();
        if (!ModuleVoltageGate.allows(machineTier, recipeEuTier)) {
            shanhai$recordVoltageTierBlock(recipeEuTier, machineTier);
            return false;
        }
        // 🔴 2026-10-03：原版 `recipe.checkConditions(this).isSuccess()` 换成本方法 ——
        //    行为在【没有需求被槽满足】时与原来逐字相同（直接委托回原版），
        //    只有当某条条件确实由槽满足时才由本方法逐条复刻原版语义。
        if (!shanhai$conditionsPass(recipe)) {
            shanhai$recordConditionBlock(recipe);
            return false;
        }
        return true;
    }

    // ═══════════════ 配方失败原因（GUI + Jade 同一条链） ═══════════════
    //     电压闸门的纯函数判据在 ModuleVoltageGate（可离线取证），本类只负责用它与报原因。

    /** 本轮扫描里记下的「为什么没跑起来」（{@code null} = 这一轮没有需要报的原因）。 */
    private Component shanhai$pendingFailReason;

    /** 上面那条是不是「物质模块等级」类的原因（它比"电压等级"更具体 ⇒ 优先级更高）。 */
    private boolean shanhai$pendingFailIsModuleLevel;

    /** 🆕 2026-10-03：上面那条是不是「额外挂载槽」类的原因（同样比"电压等级"更具体）。 */
    private boolean shanhai$pendingFailIsExtraMount;

    /** 电压等级不足：记 gtlcore 现成的 {@code FAIL_VOLTAGE_TIER}。 */
    private void shanhai$recordVoltageTierBlock(int recipeEuTier, int machineTier) {
        shanhai$logVoltageTierBlock(recipeEuTier, machineTier);
        if (shanhai$pendingFailIsModuleLevel || shanhai$pendingFailIsExtraMount) {
            return;     // 物质模块等级 / 额外挂载槽那两条更具体、且是本工程的自有条件 ⇒ 不覆盖它们
        }
        shanhai$pendingFailReason = RecipeResult.FAIL_VOLTAGE_TIER.reason();
    }

    // ═══════════ 🆕「额外挂载槽 ×3」闸门（2026-10-03 用户点单）—— 判定核在 ShanhaiHeatGate（可离线取证） ═══════════

    /** 本轮 gate 的判定结果 —— {@link #shanhai$conditionsPass} 复用它，避免同一件事两处各算一份。 */
    @Nullable
    private ShanhaiHeatGate.Outcome shanhai$gateOutcome;

    /**
     * <b>「额外挂载槽」闸门</b> —— 用户 2026-10-03 规格的落点。
     *
     * <h2>它管什么（两类来源，缺一条就会误伤别的配方）</h2>
     * <ol>
     *   <li><b>四类配方条件</b>：{@code cleanroom} / {@code gravity} / {@code dimension} / {@code research}
     *       —— 与配方类型无关，<b>任何</b>跑在模块上的配方都算；</li>
     *   <li><b>热力</b>：{@code recipe.data} 的 {@code ebf_temp} / {@code SCTier} —— 只在那 7 个配方类型
     *       （{@link ShanhaiHeatGate#GATED_TYPE_IDS}）<b>且</b>在那三台白名单机器上
     *       （{@link ShanhaiHeatGate#hasHeatSlot}）才算，其余一律无视这个格子
     *       （用户 2026-09-30 原话「若选择其他配方则无视这个格子」）。</li>
     * </ol>
     *
     * <h2>🔴 什么算一条「需求」（口径决定正/负判据，逐条写清）</h2>
     * <pre>
     *   cleanroom / gravity / dimension ：【原版判定没过】才生成需求
     *       ⇒ 机器真的建在正确维度 / 结构里真塞了洁净维护仓时，原版自己能过，槽不参与（宽松叠加，绝不比原版更严）
     *   research                        ：【一律】生成需求
     *       ⇒ 实证 ResearchCondition.test 恒 true（类注释见 ShanhaiHeatGate §6），不一律要槽就等于永远自由
     *   ebf_temp / SCTier               ：>0 各生成一条（热力，必须放满 64）
     * </pre>
     *
     * <h2>🔴 门槛值从哪来（不是我们发明的）</h2>
     * 原样读 {@code recipe.data} 的整数字段。GTCEu 自己的
     * {@code GTRecipeBuilder#blastFurnaceTemp(int)} 字节码就是
     * {@code ldc "ebf_temp" → addData(String,int)}；gtlcore 用它渲成
     * {@code gtceu.recipe.coil.tier}（=「线圈：%s」，由 {@code ICoilType.getMinRequiredType} 反查最低所需线圈）。
     * {@code SCTier} 同理，gtlcore 渲成 {@code gtceu.recipe.stellar_containment_tier}（「恒星热力容器等级：%s」）。
     * <p>⚠️ {@code CompoundTag#getInt} 走的是 {@code NumericTag#getAsInt}，<b>对 DoubleTag 同样有效</b> ——
     * 这一点是必须的：实测导出里 {@code SCTier} 序列化成 {@code 1.0 / 2.0 / 3.0}（浮点），
     * 而 {@code ebf_temp} 是整数。用 {@code getInt} 一条路两种都能读对，不需要分支。
     */
    private boolean shanhai$extraMountGateAllows(@NotNull GTRecipe recipe) {
        final PrimordialModuleMachine module = getMachine();
        final List<ShanhaiHeatGate.Requirement> needs = new ArrayList<>();

        // ① 四类配方条件
        for (RecipeCondition condition : recipe.conditions) {
            // 反向条件（isReverse）一律交回原版：槽是"提供"语义，给"不提供 X"这种否定条件当不了依据。
            // （本包实测：56903 条配方里 isReverse 真值 0 条 ⇒ 这条分支在本包里从不触发。）
            if (condition.isReverse()) {
                continue;
            }
            final ShanhaiHeatGate.Requirement need = shanhai$requirementOf(condition);
            if (need == null) {
                continue;       // 不是这四类（module_level / rock_breaker / …）⇒ 原版管
            }
            if (need.kind == ShanhaiHeatGate.Kind.RESEARCH) {
                needs.add(need);                    // 🔴 研究一律要槽（原版恒 true）
                continue;
            }
            if (condition.test(recipe, this) != condition.isReverse()) {
                continue;                           // 原版已经满足 ⇒ 这条与槽无关
            }
            needs.add(need);
        }

        // ② 热力（只在那 7 个类型 + 那三台机器上）
        final GTRecipeType type = module.getRecipeType();
        final boolean heatReachable = type != null
                && ShanhaiHeatGate.isGated(type.registryName.toString())
                && module.canUseExtraMountAsHeatSource();
        if (heatReachable) {
            final int needTemp = shanhai$readRecipeInt(recipe.data, ShanhaiHeatGate.KEY_EBF_TEMP);
            final int needTier = shanhai$readRecipeInt(recipe.data, ShanhaiHeatGate.KEY_SC_TIER);
            if (needTemp > 0) {
                needs.add(ShanhaiHeatGate.Requirement.heatTemp(needTemp));
            }
            if (needTier > 0) {
                needs.add(ShanhaiHeatGate.Requirement.scTier(needTier));
            }
        }

        final ShanhaiHeatGate.Outcome outcome =
                ShanhaiHeatGate.evaluate(needs, module.getExtraMountContents());
        shanhai$gateOutcome = outcome;
        shanhai$logExtraMountGate(type, outcome, heatReachable);
        if (outcome.allowed) {
            return true;
        }
        shanhai$recordExtraBlock(outcome);
        return false;
    }

    /**
     * <b>配方条件判定</b> —— 原版 {@code recipe.checkConditions(this)} 的<b>等价替身</b>，
     * 唯一差别是：<b>由槽满足的那几条条件算过</b>。
     *
     * <h2>为什么不直接调原版（两段分流，缺一段就会出事）</h2>
     * <ol>
     *   <li><b>没有任何需求被槽满足</b> ⇒ <b>原样委托回 {@code recipe.checkConditions(this)}</b>。
     *       这一条保证了「无条件的配方永远能做」与「原版能过的一律照过」，一行行为都不改。</li>
     *   <li><b>有需求被槽满足</b> ⇒ 才走本方法自己那一段。它<b>逐字复刻</b>
     *       {@code GTRecipe#checkConditions} 的语义（下面有字节码出处），只是把
     *       "槽已满足"的那几条也当成满足 —— 因为原版判定对清洁度/重力在我们机器上恒为 false，
     *       不跳过它们就等于槽白放。</li>
     * </ol>
     *
     * <h2>原版语义（{@code javap -c com.gregtechceu.gtceu.api.recipe.GTRecipe} 实读）</h2>
     * <pre>
     *   非 OR 条件：`c.test(recipe,logic) == c.isReverse()` ⇒ 失败（偏移 92–117）
     *   OR 组    ：按 `c.getType()` 分组，`allMatch(c -> c.test(...) == c.isReverse())` ⇒ 整组失败
     *              （偏移 121–187；谓词体在 `lambda$checkConditions$6`，实测就是"这条是失败的"）
     *              ⇒ 组内<b>有任意一条满足</b>即整组通过
     * </pre>
     * 🔴 {@code DimensionCondition.isOr()} 实测<b>恒返回 true</b>（{@code javap -c} 原文 `iconst_1; ireturn`）
     * ⇒ 维度条件天生走 OR 组这条路，本方法<b>必须</b>照抄分组语义，不能按单条判。
     */
    private boolean shanhai$conditionsPass(@NotNull GTRecipe recipe) {
        final ShanhaiHeatGate.Outcome outcome = shanhai$gateOutcome;
        if (outcome == null || outcome.satisfied.isEmpty()) {
            return recipe.checkConditions(this).isSuccess();
        }
        // 值 = "该 OR 组到目前为止是不是【全组都失败】"（首项取反，后续按与合并）。
        final Map<RecipeConditionType<?>, Boolean> orGroupAllFail = new LinkedHashMap<>();
        for (RecipeCondition condition : recipe.conditions) {
            final boolean ok = shanhai$conditionSatisfied(condition, recipe, outcome);
            if (condition.isOr()) {
                orGroupAllFail.merge(condition.getType(), !ok, (a, b) -> a && b);
            } else if (!ok) {
                return false;
            }
        }
        for (Boolean allFail : orGroupAllFail.values()) {
            if (Boolean.TRUE.equals(allFail)) {
                return false;
            }
        }
        return true;
    }

    /** 这条条件过没过 —— 原版满足，或<b>槽提供了对应的物项</b>。 */
    private boolean shanhai$conditionSatisfied(@NotNull RecipeCondition condition, @NotNull GTRecipe recipe,
                                               @NotNull ShanhaiHeatGate.Outcome outcome) {
        if (condition.test(recipe, this) != condition.isReverse()) {
            return true;
        }
        final ShanhaiHeatGate.Requirement need = shanhai$requirementOf(condition);
        return need != null && outcome.satisfied.contains(need);
    }

    /**
     * 把一条原版配方条件翻译成"槽需求"；<b>不是本闸门管的（或认不出来）返回 {@code null}</b>。
     *
     * <p>认不出来的情形必须返回 null（⇒ 交回原版判），而不是"猜一个档位"：
     * 例如将来某个 mod 注册了第 4 种 {@code CleanroomType}，
     * {@link ShanhaiHeatGate#cleanroomTierOfName} 会返回 0，这里就交回原版 —— 后果是"槽帮不上忙"，
     * 而不是"槽用错的档位放行"。
     */
    @Nullable
    private ShanhaiHeatGate.Requirement shanhai$requirementOf(@NotNull RecipeCondition condition) {
        if (condition instanceof CleanroomCondition cleanroom) {
            final CleanroomType type = cleanroom.getCleanroom();
            final int tier = ShanhaiHeatGate.cleanroomTierOfName(type == null ? null : type.getName());
            return tier == ShanhaiHeatGate.CLEANROOM_NONE ? null : ShanhaiHeatGate.Requirement.cleanroom(tier);
        }
        if (condition instanceof DimensionCondition dimension) {
            final ResourceLocation dim = dimension.getDimension();
            return dim == null ? null : ShanhaiHeatGate.Requirement.dimension(dim.toString());
        }
        if (condition instanceof ResearchCondition) {
            return ShanhaiHeatGate.Requirement.research();
        }
        if (condition instanceof GravityCondition) {
            // ⚠️ gtlcore 的 GravityCondition 里 zero 是 private 且无 getter ⇒ 这里【不区分】无重力/强重力，
            //    一律"需要重力控制"。依据：带重力的维护仓是可配置的（isConfig 字段），两种都能给。
            //    详见 ShanhaiHeatSources#HATCH_GRAVITY_IDS 的注释。
            return ShanhaiHeatGate.Requirement.gravity();
        }
        return null;
    }

    /** 读配方 {@code data} 里的一个整数字段；不存在算 0（= 这条配方没有该门槛）。 */
    private static int shanhai$readRecipeInt(@NotNull CompoundTag data, @NotNull String key) {
        return data.contains(key) ? data.getInt(key) : 0;
    }

    /**
     * 把拒绝原因按 {@link ShanhaiHeatGate.Deny} 渲成**具体**文案（走 lang 键，不硬编码中文）。
     *
     * <h2>句式口径（2026-09-30 用户选择题答案逐字「A. 改」定下的）</h2>
     * 一律「<u>先说这个配方需要什么，再说槽里实际是什么</u>」—— 旧句式先否定你放的东西、再说其实要什么，
     * 读起来像"说反了"。2026-10-03 合并到额外挂载槽后<b>沿用同一套句式</b>，只是把"恒星热力槽"换成"额外挂载槽"。
     * <pre>
     *   SLOT_EMPTY     ：这个配方需要【%s】，但 3 个额外挂载槽全是空的
     *   WRONG_ITEM     ：这个配方需要【%s】，但额外挂载槽里放的是【%s】
     *   NOT_FULL       ：这个配方需要放满 %s 个【%s】才生效，槽里只有 %s 个
     *   CLEANROOM_TIER ：这个配方需要【%s】，但槽里的维护仓只提供【%s】
     *   HEAT_TEMP      ：这个配方需要 %sK 炉温，但槽里的线圈只有 %sK
     *   SC_TIER        ：这个配方需要 %s 级恒星热力容器，但槽里的是 %s 级
     * </pre>
     * <p>⚠️ 只给<b>原因</b>，不带「配方失败原因：」前缀 —— 前缀由 Jade 的
     * {@code gtceu.recipe.fail.reason}（"配方失败原因：%s"）加，加了会变成两层（与物质模块等级那条同纪律）。
     */
    private void shanhai$recordExtraBlock(@NotNull ShanhaiHeatGate.Outcome o) {
        final ShanhaiHeatGate.Requirement need = o.blocked;
        if (need == null) {
            return;
        }
        final Component what = shanhai$requirementLabel(need);
        final Component reason = switch (o.deny) {
            case SLOT_EMPTY -> Component.translatable("shanhai.recipe.fail.extra_slot_empty", what);
            case WRONG_ITEM -> Component.translatable("shanhai.recipe.fail.extra_slot_wrong", what,
                    shanhai$extraActualNames());
            case NOT_FULL -> Component.translatable("shanhai.recipe.fail.extra_slot_not_full",
                    ShanhaiHeatGate.REQUIRED_COUNT, what, shanhai$extraCountOfKind(need.kind, o));
            case CLEANROOM_TIER -> Component.translatable("shanhai.recipe.fail.extra_cleanroom_tier",
                    what, shanhai$cleanroomLabel(o.have));
            case HEAT_TEMP -> Component.translatable("shanhai.recipe.fail.heat_coil_temp", need.number, o.have);
            case SC_TIER -> Component.translatable("shanhai.recipe.fail.heat_sc_tier", need.number, o.have);
            default -> null;
        };
        if (reason == null) {
            return;
        }
        shanhai$pendingFailIsExtraMount = true;
        shanhai$pendingFailReason = reason;
    }

    /**
     * 一条需求的中文标签（**走 lang 键**）。
     * <p>超净间那一档用 GTCEu/gtlcore <b>自己的</b> {@code CleanroomType#getTranslationKey()}
     * （{@code gtceu.recipe.cleanroom.display_name} / {@code _sterile_} / {@code _law_}），
     * 不另写一份中文 —— 上游改名时这里自动跟着走。
     */
    @NotNull
    private Component shanhai$requirementLabel(@NotNull ShanhaiHeatGate.Requirement r) {
        return switch (r.kind) {
            case CLEANROOM -> shanhai$cleanroomLabel(r.number);
            case GRAVITY -> Component.translatable("shanhai.recipe.fail.extra_need_gravity");
            case DIMENSION -> Component.translatable("shanhai.recipe.fail.extra_need_dimension", r.text);
            case RESEARCH -> Component.translatable("shanhai.recipe.fail.extra_need_research");
            case HEAT_TEMP -> Component.translatable("shanhai.recipe.fail.extra_need_coil_temp", r.number);
            case SC_TIER -> Component.translatable("shanhai.recipe.fail.extra_need_sc_tier", r.number);
        };
    }

    /** 超净间档位 → 上游自己的可翻译名；查不到（理论上不会）退化成注册名。 */
    @NotNull
    private static Component shanhai$cleanroomLabel(int tier) {
        final CleanroomType type = CleanroomType.getByName(ShanhaiHeatGate.cleanroomName(tier));
        return type == null
                ? Component.literal(ShanhaiHeatGate.cleanroomName(tier))
                : Component.translatable(type.getTranslationKey());
    }

    /**
     * <b>额外挂载槽里实际放的是什么</b> —— 用【物品显示名】，🔴 <b>不是 id</b>（玩家看不懂 id）。
     *
     * <h2>🔴 为什么传 {@link Component} 而不是 {@code String}</h2>
     * 这条原因要经 gtlcore 的 {@code RecipeLogicProviderMixin} 送进 Jade 的<b>服务端 NBT</b>
     * （字节码实证：{@code Component$Serializer.toJson(component)} → {@code CompoundTag.putString("reason", …)}），
     * 而<b>专用服务端不加载客户端 lang</b> —— 如果在这里先 {@code getString()} 成 String，
     * 到了客户端就只剩一个键名/英文回退，中文永远出不来。
     * 传 Component 则整棵子树被 JSON 序列化过去，<b>客户端才翻译</b> ⇒ 中文正确显示。
     *
     * <h2>退化（用户硬要求 ①）</h2>
     * 三格全空 / 拿不到显示名 / 任何异常 ⇒ <b>退化成 lang 键
     * {@code shanhai.recipe.fail.heat_slot_actual.unknown}（「其它物品」），不报错、不留空</b>。
     * <p>吞掉 {@code Throwable} 是有意的：这一句是在<b>报错路径上</b>跑的，
     * 报错路径自己抛异常会把"配方为什么没跑"这条唯一线索也弄没。
     */
    @NotNull
    private Component shanhai$extraActualNames() {
        final Component unknown = Component.translatable("shanhai.recipe.fail.heat_slot_actual.unknown");
        try {
            final PrimordialModuleMachine module = getMachine();
            final MutableComponent out = Component.empty();
            boolean first = true;
            for (int i = 0; i < PrimordialModuleMachine.EXTRA_MOUNT_SLOT_COUNT; i++) {
                final ItemStack stack = module.getExtraMountStack(i);
                if (stack == null || stack.isEmpty()) {
                    continue;
                }
                final Component name = stack.getHoverName();
                if (name == null) {
                    continue;
                }
                if (!first) {
                    out.append(Component.translatable("shanhai.recipe.fail.extra_slot_sep"));
                }
                out.append(name);
                first = false;
            }
            return first ? unknown : out;
        } catch (Throwable t) {
            return unknown;
        }
    }

    /** 该需求对应的物项在某一格里最多放了多少（给"没放满"那条文案报读数）。 */
    private static int shanhai$extraCountOfKind(@NotNull ShanhaiHeatGate.Kind kind,
                                                @NotNull ShanhaiHeatGate.Outcome o) {
        int best = 0;
        for (ShanhaiHeatGate.SlotContent s : o.slots) {
            final boolean relevant = switch (kind) {
                case HEAT_TEMP -> s.coilTemperature > 0;
                case SC_TIER -> s.containmentTier > 0;
                default -> true;
            };
            if (relevant) {
                best = Math.max(best, s.count);
            }
        }
        return best;
    }

    // ── 一条可 grep 的证据行（哪台机器 / 配方类型 / 三格各是什么 / 需求 / 判定结果） ──

    /** 已报过的挂载判定（每条不同的读数只报一次，避免刷屏）。 */
    private static final Set<String> shanhai$extraLogged = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private void shanhai$logExtraMountGate(@Nullable GTRecipeType type,
                                           @NotNull ShanhaiHeatGate.Outcome o,
                                           boolean heatReachable) {
        // 没有需求 ⇒ 一个字都不打。这一格对绝大多数配方（本包 55707/56903 条无条件）本来就不该刷屏。
        if (o.needs.isEmpty()) {
            return;
        }
        final PrimordialModuleMachine module = getMachine();
        final String key = o.deny + "|" + o.describe();
        if (shanhai$extraLogged.size() > 256 || !shanhai$extraLogged.add(key)) {
            return;
        }
        ShanhaiMod.LOGGER.info("[SHANHAI-EXTRAMOUNT] 机器={}（{}） 配方类型={} 热力可达={} 三格={} {}",
                module.getBlockState().getBlock(),
                module.getPos(),
                type == null ? "-" : type.registryName,
                heatReachable,
                module.describeExtraMounts(),
                o.describe());
    }

    /**
     * 条件不满足：<b>只认我们自己的 {@link ModuleLevelCondition}</b>（别的配方条件不抢这一行）。
     *
     * <p>必须先逐条重测条件才能指名"是哪一条"：{@code GTRecipe#checkConditions} 只返回一个
     * {@code ActionResult}（{@code javap} 实测它有 {@code lambda$checkConditions$5/$6/$7} 三条私有 lambda，
     * 失败信息不逐条外露）。本方法复用的是<b>同一条</b> {@code RecipeCondition#test}，
     * 不另写判定 —— 用户那套「等级 ≥ 要求」的语义只有一个实现（{@code ModuleLevelCondition}）。
     */
    private void shanhai$recordConditionBlock(@NotNull GTRecipe recipe) {
        final ModuleLevelCondition blocker = shanhai$failingModuleLevelCondition(recipe);
        if (blocker == null) {
            return;
        }
        shanhai$pendingFailIsModuleLevel = true;
        shanhai$pendingFailReason = shanhai$moduleLevelFailReason(blocker);
    }

    /** 找出这条配方上【不通过】的那一条 {@code module_level} 条件；没有则 {@code null}。 */
    private @Nullable ModuleLevelCondition shanhai$failingModuleLevelCondition(@NotNull GTRecipe recipe) {
        for (RecipeCondition condition : recipe.conditions) {
            if (condition instanceof ModuleLevelCondition mlc && !mlc.test(recipe, this)) {
                return mlc;
            }
        }
        return null;
    }

    /**
     * 文案（**走 lang 键，不硬编码中文**）。
     *
     * <pre>
     *   可解   ：shanhai.recipe.fail.module_level = "物质模块等级不足（需要 Lv.%s，当前 Lv.%s）"
     *   不可解 ：shanhai.recipe.fail.module_level.unresolved
     *            = "物质模块等级要求无法解析（配方里的模块 id 不在 17 个物质模块表里）"
     * </pre>
     * ⚠️ 只给<b>原因</b>，不带「配方失败原因：」前缀 —— 那个前缀由 Jade 的
     * {@code gtceu.recipe.fail.reason}（"配方失败原因：%s"）加，加了会变成两层。
     * GUI 侧 gtlcore 一律画裸原因（它自己也这么画"电压等级未达到配方要求"）⇒ 两边同形。
     */
    private Component shanhai$moduleLevelFailReason(@NotNull ModuleLevelCondition blocker) {
        if (!blocker.isGateResolvable()) {
            return Component.translatable("shanhai.recipe.fail.module_level.unresolved");
        }
        return Component.translatable("shanhai.recipe.fail.module_level",
                blocker.requiredLevelForGate(), getMachine().getMatterModuleLevel());
    }

    /**
     * 把暂存的原因写进 gtlcore 的配方状态 —— <b>只有这一轮真的没跑起来才写</b>。
     *
     * <h2>🔴 判据 A③：不许变成常驻行</h2>
     * {@code checkRecipe} 会被<b>逐条候选配方</b>调用：同一条配方类型下，可能"配方 A 被门槛拦下、
     * 配方 B 照跑"。此处在 {@link #findAndHandleRecipe()} 的<b>末尾</b>才写，并且
     * <b>只要机器已经在跑（WORKING / WAITING）或手上已有配方，就一个字都不写</b>
     * ⇒ 没有真拦截时，Jade 与 GUI 里都不会多出这一行（本项目明令禁止的"活的假数据"）。
     *
     * <p>写入口 = {@code RecipeResult.of(machine, …)}：与 gtlcore / gtladditions 自己
     * （{@code RecipeLogicMixin} / {@code MutableRecipesLogic}）<b>逐字同一条路</b>，不自造显示。
     */
    private void shanhai$publishFailReason() {
        final Component reason = shanhai$pendingFailReason;
        if (reason == null) {
            return;
        }
        final RecipeLogic.Status status = getStatus();
        if (status == RecipeLogic.Status.WORKING || status == RecipeLogic.Status.WAITING) {
            return;
        }
        if (getLastRecipe() != null) {
            return;
        }
        RecipeResult.of(getMachine(), RecipeResult.fail(reason));
        shanhai$logFailReasonPublished(reason);
    }

    // ── 两条一次性可 grep 证据行（"悄悄不发生"与"出故障"必须能分开） ──

    /** 已报过的 (machineTier, recipeEuTier) 组合（每个组合只报一次，避免刷屏）。 */
    private static final Set<String> shanhai$voltageGateLogged = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void shanhai$logVoltageTierBlock(int recipeEuTier, int machineTier) {
        final String key = machineTier + "/" + recipeEuTier;
        if (shanhai$voltageGateLogged.size() > 256 || !shanhai$voltageGateLogged.add(key)) {
            return;
        }
        ShanhaiMod.LOGGER.info("[SHANHAI-VOLTAGE-GATE] 配方电压等级超出本机 ⇒ 拦下："
                        + "machineTier={}（= 本机能源仓等级）< recipeEuTier={}。"
                        + "已写入 gtlcore 现成的 FAIL_VOLTAGE_TIER ⇒ Jade / 机器 GUI 都会渲成"
                        + "「电压等级未达到配方要求」。",
                machineTier, recipeEuTier);
    }

    /** 已报过的原因文本（每个不同的原因只报一次）。 */
    private static final Set<String> shanhai$failReasonLogged = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void shanhai$logFailReasonPublished(@NotNull Component reason) {
        final String key = reason.getString();
        if (shanhai$failReasonLogged.size() > 64 || !shanhai$failReasonLogged.add(key)) {
            return;
        }
        ShanhaiMod.LOGGER.info("[SHANHAI-FAIL-REASON] 已写入配方失败原因（链 = RecipeResult.of → IRecipeStatus "
                        + "→ Jade 的 reason / 机器 GUI 的红字行）：原文=《{}》"
                        + "（专用服务端不加载客户端 lang，这里打出的是键名/参数，客户端才会渲成中文）",
                key);
    }

    /**
     * 🔴 <b>差异 1 的门控：把"引擎先扣料、后 {@code beforeWorking} 拒绝"这条吞料路径挡住。</b>
     *
     * <h2>为什么必须挡</h2>
     * 引擎的扣料点在 {@code getRecipe()} 内部（{@code calculateParallelsWithGreedyAllocation} /
     * {@code buildFinalNormalRecipe} 里的 {@code handleRecipeInput}），<b>严格早于</b>
     * {@code setupRecipe} → {@code machine.beforeWorking(recipe)}；而模块的
     * {@link PrimordialModuleMachine#beforeWorking} 在主机离线时会返回 false
     * ⇒ <b>每试一次就吞一批料、零产出</b>（节奏：{@code serverTick} 里 {@code getOffsetTimer()%5==0}，
     * 因为模块 {@code keepSubscribing()==true}）。
     *
     * <h2>🔴 还必须调 {@code tryReconnectLimited()}</h2>
     * 那个重连尝试原本只在 {@code beforeWorking} 里做。门控一旦在更早处返回 false，
     * {@code beforeWorking} 就<b>永远不会被调用</b> ⇒ 不补这一句就会"挡掉了吞料、也挡掉了重连"
     * ⇒ 模块卡死（比吞料更糟）。
     */
    @Override
    protected boolean checkBeforeWorking() {
        if (!super.checkBeforeWorking()) {
            return false;
        }
        final PrimordialModuleMachine module = getMachine();
        if (!module.canWork()) {
            module.tryReconnectLimited();
            return module.canWork();
        }
        // 🔴 2026-09-26（丢料探针可判化 · 第 ① 步）：在【扣料前】存一份输入仓 / 流体仓摘要。
        //    「这个点早于扣料」不是推断 —— 字节码实证：
        //      MutableRecipesLogic.getRecipe() 偏移 0–8 就是
        //        checkBeforeWorking() → ifne 9 / aconst_null / areturn，
        //      紧接着 9–13 调 calculateParallels()（扣料点 RecipeRunnerHelper.handleRecipeInput
        //      在 RecipeCalculationHelper 里被调 12 处），最后才 buildFinalNormalRecipe()；
        //      而 setupRecipe()（machine.beforeWorking 所在）在 getRecipe() 返回【之后】才被调。
        //    ⇒ 门控 < 扣料 < 拒绝，三段全部有实证。
        module.shanhai$lossProbeBeforeDeduction();
        return true;
    }

    /**
     * <b>尾链迁移</b>：把 并行 → N3产出 → N5时长 → N6下限 → N5耗能 施加在引擎装配出的成品上。
     *
     * <p>顺序与 {@code ModuleRegistry#applyModuleRecipeModifier} <b>逐字一致</b>；差别只在入口：
     * 原生链的入口是"配方定义原实例"（所以链上要自己先做并行），
     * 本路径的入口是<b>引擎已经并好输出、已经扣完料的成品</b>（所以并行那一步由引擎做了，本类不重放）。
     *
     * <p><b>本类里不出现任何自有算式</b>：每一步都是对 {@link PrimordialRecipeEffects} 的调用。
     */
    @Override
    protected @Nullable GTRecipe buildFinalNormalRecipe(@NotNull ParallelData parallelData) {
        final GTRecipe built = super.buildFinalNormalRecipe(parallelData);
        if (built == null) {
            return null;
        }
        final PrimordialModuleMachine module = getMachine();
        // 🔴 2026-09-26（丢料探针 · 第 ③ 步的实机那一半）：走到这里引擎【已经扣过料】
        //    （扣料点在 calculateParallels → calculateParallelsWithGreedyAllocation 里，
        //     严格早于 buildFinalNormalRecipe），所以这一次摘要对比是"实机上真的能看到扣料"的证据。
        //    只读、只在真的读到变化时打一行（全部模块共享一次），不改任何数值。
        module.shanhai$lossProbeLiveControl();
        final int engineDuration = built.duration;          // 引擎时长 D（守恒反缩放的分子）
        // 🔴 2026-09-29：d0 由「第一条」改成【聚合口径】（候选里最长的那条）——
        //    跨配方并行打开后这一份成品是 N 条配方的聚合，单条代表整批没有依据。
        //    单条候选时 max == 那一条本身 ⇒ 与改动前逐值相同。理由见该方法的注释。
        final int originDuration = shanhai$aggregateOriginDurationOf(parallelData);
        final int originCount = parallelData.getOriginRecipeList().size();
        // 🔴 2026-09-26：原有一行 `final int limitedDuration = module.getLimitedDuration();` 已删除 ——
        //    模块的「配方最短耗时（下限）」按用户裁决「连功能一起删」整体删除，
        //    字段与 getter 都不存在了（依赖它的一律改走"只吃 N5 减免系数"的新公式）。
        // 门控只读一次：N3 的倍率与 N5 的成本系数必须同源（与原生链同一条纪律）。
        final int gateBonus = module.getHost() == null ? 0 : module.getHost().moduleSlotBonus();

        GTRecipe modified = built;
        // ── 守恒反缩放：EUt_引擎(D) → baseEUt × p（= totalEu / d0）──
        //    第二个实参就是 d0（时长），语义无错位；D == d0 时本调用原样返回、零分配。
        modified = PrimordialRecipeEffects.rescaleEnergyForDuration(modified, engineDuration, originDuration);
        // ── 时长钉回 d0：引擎的 D 自带硬编码 20 下限，N5/N6 必须在【原时长】上算 ──
        //    用共用的 min 天花板函数（applyHostDurationCap，现在只有模块侧在调）表达"上限 = 原时长"⇒ 结果恒为 d0。
        modified = PrimordialRecipeEffects.applyHostDurationCap(modified, originDuration, originDuration);
        // ── ② N3 产出倍率 ──
        modified = PrimordialRecipeEffects.multiplyOutputs(
                modified, PrimordialRecipeEffects.outputMultiplier(gateBonus));
        // ── ③ N5 耗时减免 ──
        modified = PrimordialRecipeEffects.applyDurationReduction(
                modified, PrimordialRecipeEffects.reductionFactor(gateBonus));
        // ── ④ N6 模块侧（保底 + 天花板）：min(d0, max(1, round(d0 × f))) ──
        //    🔴 2026-09-26 用户裁决「连功能一起删」⇒ 下限 L 与它的形参都已删除；
        //       剩下的 `max(1, …)` 是「至少 1 tick」保底（与"下限 20"是两件事，别混）。
        modified = PrimordialRecipeEffects.applyModuleDuration(modified, originDuration);
        // ── ④-b 🔴 2026-09-30（用户拍板 B）：并行进 long 档 ⇒ 时长下限 10 tick ──
        //    用户原话逐字（第一轮）：「我选B，而且你都上long并行了，就不缺那10tick了，变慢也几乎没有影响」
        //    用户原话逐字（第四轮·破红线）：「B. 破一次红线，让那 25 台也抬到 10」
        //    ⇒ 判据与算式【与原生链那一侧共用同一个纯核】：判据 = 本台并行预算 > 2147483647，
        //      实际生效 = max(当前时长, 10) —— **绝对 10，不再按原时长收缩**。
        //      🔴 新红线措辞（逐字，见 ShanhaiDurationFloor 类注释）：
        //         「只有「进了 long 档」（并行预算 > 2,147,483,647）时，配方时长才允许被抬到 10 tick；
        //           其余一切情形，配方时长仍不许超过配方定义的原时长。」
        //      ⚠️ 这一段（引擎链 25 台）正是"破红线"的对象：它们的配方定义时长 = 1 tick
        //         （用户日志 127 条对账全是 d0=1）⇒ 旧公式 min(10, 1) = 1 = 完全不生效；
        //         新公式 ⇒ 10。**代价 = 那些配方比原版慢（最坏 10 倍），用户明知并接受。**
        //      ⚠️ `originDuration` 现在只用于日志/追踪器显示，公式不读它（签名保留是为了零分叉）。
        //      两条路只有【落点】不同，算术没有第二份。
        //    ⚠️ 位置理由：必须在 N6 之后（N6 的 min(d0, …) 天花板会吃掉更早的下限），
        //      且必须在【批处理之前】——批处理是"把这一份成品再连跑 N-1 次"，
        //      它的 N 与最终时长同乘，放它后面会让"时长 ×N"把下限的意义抹平。
        //    ⚠️ 影子对账（{@link #shanhai$applyNativeAuthority}）里【同一步必须一模一样地重放一遍】，
        //      否则对账器会把 duration 强行改回它自己那套期望值（静默错数）。
        modified = PrimordialRecipeEffects.applyLongScaleDurationFloor(
                modified, originDuration, shanhai$parallelBudget());
        // ── ⑤ N5 耗能减免 ──
        modified = PrimordialRecipeEffects.reduceEnergy(
                modified, PrimordialRecipeEffects.reductionFactor(gateBonus));

        final GTRecipe finalRecipe = shanhai$applyNativeAuthority(
                parallelData, originDuration, gateBonus, modified);
        // ── ⑥ 🔴 批处理（侧栏那个开关）＝「连跑 N 次」──
        //    输入 ×N（补扣 (N-1)×p 份，见 applyBatchProcessing ③）／输出 ×N／耗时 ×N／
        //    🔴 **EU/t 一文不变**（tickInputs 原样写回）。
        //    位置：必须在【已经并好、已经扣过料、N3/N5/N6 与原生权威都施加完】之后 ——
        //    它只是把这一份成品"再连跑 N-1 次"，与上游任何一个步骤都不冲突；
        //    放得比 N6 早会让"耗时 ×N"被 N6 的 `min(d0, …)` 天花板吃掉。
        //    ⚠️ 传的 parallel 必须是【引擎贪心分配出来的份数 p】（材料已按它扣过），
        //      不是侧栏的并行上限 —— 见 applyBatchProcessing ① 的注释。
        //
        //    🔴🔴 2026-09-30：多配方聚合（originCount ≥ 2）不再"整体跳过"，改成【正确形态】。
        //     病史：applyBatchProcessing 的额外投料用的是
        //       shanhai$originRecipeOf(parallelData)（= origins.get(0)）与
        //       shanhai$parallelOf(parallelData)（= parallels[0]），
        //     而它乘 N 的是【聚合后的全部产出】（scaled = finalRecipe.copy(×N)）
        //     ⇒ 只补了第一条配方的料、却把 N 条配方的产出都乘了 N
        //     ⇒ **少扣料 = 白送材料**（本工程红线）。
        //     2026-09-29 的处置是"多配方下干脆不施加"（安全但少功能）；
        //     本轮的处置是 PrimordialRecipeEffects.applyBatchProcessingMulti：
        //       ⇒ 对【每一条】origin i 各补扣 (N-1)×p_i，
        //       ⇒ 且【全部预检通过之后才开始真扣】（任一条不够 ⇒ 整单放弃、一粒料都不扣）。
        //     🔴 单条候选那一支【一个字都没动】（仍然是原来那句 applyBatchProcessing）——
        //       "候选 ≤ 1 条时逐值不变"是硬要求，所以宁可在两个方法之间留 20 行重复，
        //       也不抽公共函数（理由写在 applyBatchProcessingMulti 的注释里）。
        final GTRecipe batchApplied;
        if (originCount >= 2) {
            batchApplied = PrimordialRecipeEffects.applyBatchProcessingMulti(
                    getMachine(), parallelData.getOriginRecipeList(), parallelData.getParallels(), finalRecipe);
        } else {
            batchApplied = PrimordialRecipeEffects.applyBatchProcessing(
                    getMachine(), shanhai$originRecipeOf(parallelData), finalRecipe, shanhai$parallelOf(parallelData));
        }
        // 🔴 2026-09-26：抬头（Jade）那两行的显示因子 —— 与主机
        //    {@code PrimordialEngineRecipeLogic#captureEngineReduction} 逐字同口径
        //    （同一对公式、同一批纯函数、同样"以配方定义原时长 d0 为基线"）。
        //    ⚠️ 传的 T 必须是【本方法返回的那张成品的真实时长】（N6 + 原生权威 + 批处理之后的最终值），
        //      不是侧栏那个下限 L、也不是引擎时长 D —— 与主机"传 T 不传 L"的细化同一条纪律。
        //    ⚠️ batchCycles 必须一起传：批处理只把 duration 乘了 N，EU/t 不变
        //      ⇒ "耗时倍率"要 ×N 才是真的，"耗能倍率"必须仍按 EU/t 的口径算。
        shanhai$captureModuleReduction(originDuration, gateBonus, batchApplied, shanhai$batchCyclesOf(batchApplied));
        return batchApplied;
    }

    /** 本轮"引擎装配用的那张原配方"（= 额外投料与批处理倍率的口径基准；取第一条，与上游同写法）。 */
    private static @Nullable GTRecipe shanhai$originRecipeOf(@NotNull ParallelData parallelData) {
        final List<GTRecipe> origins = parallelData.getOriginRecipeList();
        return origins.isEmpty() ? null : origins.get(0);
    }

    /** 本轮引擎贪心分配出来的并行份数 {@code p}（材料已经按它扣过一次）。 */
    private static long shanhai$parallelOf(@NotNull ParallelData parallelData) {
        final long[] parallels = parallelData.getParallels();
        return parallels.length == 0 ? 0L : Math.max(0L, parallels[0]);
    }

    /**
     * 这张成品已经"连跑几次"（= gtlcore 的 {@code batchSize}）。
     * 引擎成品的初值是 1；批处理施加后会由 {@code applyBatchProcessing} 写成 N。
     */
    private static int shanhai$batchCyclesOf(@Nullable GTRecipe recipe) {
        if (recipe instanceof IGTRecipe igt) {
            return Math.max(1, igt.getBatchSize());
        }
        return 1;
    }

    // ═══════════════ 抬头两行（总耗能倍率 / 总耗时倍率）的真实因子 ═══════════════

    /** 上一次写进去的那一对因子（只在值变化时打一行日志，不刷屏）。 */
    private static long shanhai$lastLoggedReductionBits = Long.MIN_VALUE;

    /**
     * 把「总耗能倍率 / 总耗时倍率」两个<b>真实因子</b>写进 gtlcore 的显示追踪器
     * （{@code RecipeMultiplierTracker} → Jade 的 {@code RecipeMultiplierProvider}）。
     *
     * <h2>1. 为什么模块必须自己写（根因，字节码实证）</h2>
     * 提供者的 {@code write(CompoundTag, IRecipeLogicMachine)} 只有两支：
     * <pre>
     *   if (logic instanceof org.gtlcore.gtlcore.common.machine.trait.MultipleRecipesLogic mrl)
     *         → Optional.of(new Multipliers(mrl.getReductionEUt(), mrl.getReductionDuration()));
     *   else  → RecipeMultiplierTracker.get(machine.self())
     *               .or(() -&gt; last == null ? Optional.empty() : Optional.of(DEFAULT));   // DEFAULT = (1.0, 1.0)
     * </pre>
     * 本类的继承链是 {@code PrimordialModuleRecipeLogic → gtladditions MutableRecipesLogic → gtceu RecipeLogic}
     * （{@code javap} 实测：{@code MutableRecipesLogic extends com.gregtechceu.gtceu.api.machine.trait.RecipeLogic}）
     * ⇒ <b>不是</b> gtlcore 的 {@code MultipleRecipesLogic} ⇒ 走 else 支；而 tracker 里没有本机条目时，
     * 兜底就是 {@code DEFAULT = (1.0, 1.0)} ⇒ <b>抬头恒画 100%／100%，与装不装主机无关</b>。
     * 修饰链那条路上的 {@code captureReduction(…, 1.0, 1.0)}（{@code ModuleRegistry} 里那一句）在引擎路径上是
     * <b>死代码</b>（引擎路径不经过 {@code RecipeModifierList.apply}）⇒ 真值的唯一来源就是这个方法。
     *
     * <h2>2. 写什么（与主机逐字同口径，队长 2026-09-21 裁决 (Q)）</h2>
     * <pre>
     *   durationFactor = T / d0
     *   energyFactor   = f × (d0 / T)      f = PrimordialRecipeEffects.reductionFactor(gateBonus)
     *   ⇒ energyFactor × durationFactor = f 恒成立（两行互为倒数、同源）
     * </pre>
     * 🔴 <b>2026-09-27 追加批处理（第二版）：两行的口径必须分开算</b>
     * <pre>
     *   批处理只做三件事：输入 ×N、产出 ×N、耗时 ×N；<b>EU/t 一文不变</b>。
     *   ⇒ 传进来的 T 已经是"连跑 N 次"之后的总时长，故：
     *     durationFactor = T / d0                      ← ✅ 含 ×N（真的是 T·N）
     *     energyFactor   = f × d0 / (T / N) = f·d0·N/T ← 仍是【EU/t 的口径】，与 N 无关
     *   ⚠️ 若直接沿用旧式 f × d0 / T，抬头会把"耗能"少报 N 倍（假数据）：
     *      EU/t 没变，变的是跑得久了。N = 1（未开批处理）时两式同值 ⇒ 与改动前逐值相同。
     * </pre>
     * d0 = 配方定义原时长（{@link #shanhai$originDurationOf}）；T = 成品真实时长（见调用点注释）。
     *
     * <h2>3. 为什么"先探一次再写"（照抄主机的防假数据手法）</h2>
     * {@code captureReduction} 有两条分支（字节码）：context 支把因子乘进 {@code ctx.captured}，
     * null 支才 {@code MULTIPLIERS.put(machine, multiply(DEFAULT, e, d))} —— <b>只有后者是 provider 读的那张表</b>。
     * 引擎路径上 {@code begin()} 无人调用 ⇒ 预期 context 恒 null，但我们<b>不靠"应该"</b>：
     * 先写 (1.0,1.0) 探一次，用<b>引用相等</b>判断表项有没有换新（{@code Multipliers} 是 record，
     * 值相等会掩盖"到底有没有 put 过"）；换了才写真因子。没换 ⇒ <b>绝不写</b>
     * （写了就是乘第二次 = 假数据），改打一条 ERROR 让抬头退回上游兜底值。
     *
     * <h2>🔴 诚实边界（红线）</h2>
     * 本方法<b>只写显示用的 {@code WeakHashMap}</b>，<b>不改配方的任何字段</b>：
     * 唯一的输入是两个数字，{@code built} 只作为被忽略的形参传入（与主机同形）。
     *
     * @param originDuration 配方定义原时长 d0（&le;0 ⇒ 两行都不写真实值，退化成 1.0）
     * @param gateBonus      主机专属槽门控等级（与 N3/N5 同源的 f）
     * @param built          最终成品（只读它的 {@code duration} 当 T；其余一概不读）
     * @param batchCycles    这张成品已经"连跑几次"（= gtlcore {@code batchSize}；未开批处理恒为 1）
     */
    private void shanhai$captureModuleReduction(int originDuration, int gateBonus, @NotNull GTRecipe built,
                                                int batchCycles) {
        try {
            final PrimordialModuleMachine machine = getMachine();
            final int cycles = Math.max(1, batchCycles);
            final int target = Math.max(1, built.duration);
            final double durationFactor = originDuration > 0
                    ? (double) target / (double) originDuration : 1.0D;
            // EU/t 的口径：批处理把 duration 乘了 N，但 tickInputs(EU/t) 没动
            // ⇒ 先除掉 N 还原"批处理前的那张成品时长"，再套旧式 f × d0 / T。
            final double batchOffTarget = (double) target / (double) cycles;
            final double energyFactor = PrimordialRecipeEffects.reductionFactor(gateBonus)
                    * (originDuration > 0 && batchOffTarget > 0.0D
                            ? (double) originDuration / batchOffTarget : 1.0D);

            // ── ① 探针：先取一次当前值，再决定写法 ──
            final RecipeMultiplierTracker.Multipliers before = RecipeMultiplierTracker.get(machine).orElse(null);
            RecipeMultiplierTracker.captureReduction(machine, built, 1.0D, 1.0D);
            if (RecipeMultiplierTracker.get(machine).orElse(null) == before) {
                // 🔴 值引用没变 ⇒ 探针没写进 MULTIPLIERS ⇒ 落进了 context 分支。
                //    此时 ctx.captured 已经把 (1.0,1.0) 乘进去了（乘 1 无副作用），
                //    但再传真实因子就会乘第二次 ⇒ 绝不允许。
                ShanhaiMod.LOGGER.error("[SHANHAI-MODULE] 抬头两行的显示因子写不进去："
                        + "RecipeMultiplierTracker.captureReduction 走的是 context 分支（ctx != null）。"
                        + "为避免把同一批因子乘第二次（假数据），本次【不写】真实因子；"
                        + "抬头会退化成上游兜底值 100%/100%。配方数值不受影响。pos={}", machine.getPos());
                return;
            }

            // ── ② 确认走的是 null 分支 ⇒ 写真实因子（覆盖探针写下的 1.0/1.0）──
            RecipeMultiplierTracker.captureReduction(machine, built, energyFactor, durationFactor);

            // ── ③ 只在因子变化时打一行（不刷屏）：部署后这一行就是"tracker 真的写进去了"的实机证据 ──
            final long bits = Double.doubleToLongBits(energyFactor) * 31L
                    + Double.doubleToLongBits(durationFactor);
            if (bits != shanhai$lastLoggedReductionBits) {
                shanhai$lastLoggedReductionBits = bits;
                final RecipeMultiplierTracker.Multipliers readBack =
                        RecipeMultiplierTracker.get(machine).orElse(null);
                ShanhaiMod.LOGGER.info("[SHANHAI-MODULE] 抬头因子已写入 tracker："
                                + "总耗能倍率={}（{}%）／总耗时倍率={}（{}%）"
                                + "（口径 (Q)：energy×duration = N5 成本系数 f={}；"
                                + "d0={} → T={}（批处理连跑 N={} 次，∴ 批处理前的成品时长 = T/N = {}）"
                                + "⇒ duration=T/d0、energy=f×d0/(T/N)）；读回={} pos={}",
                        energyFactor, energyFactor * 100.0D, durationFactor, durationFactor * 100.0D,
                        PrimordialRecipeEffects.reductionFactor(gateBonus), originDuration, target, cycles,
                        batchOffTarget, readBack, machine.getPos());
            }
        } catch (Throwable t) {
            // 纯显示：任何异常都不允许影响配方装配本身（与主机 captureEngineReduction 同纪律）。
            ShanhaiMod.LOGGER.error("[SHANHAI-MODULE] 写抬头显示因子时异常（已忽略，配方不受影响）", t);
        }
    }

    /**
     * 🔴 <b>聚合口径的原时长 {@code d0*} = 候选里【最长】的那一条的时长。</b>
     *
     * <h2>为什么要改（2026-09-29 跨配方并行接线）</h2>
     * 引擎路径上"原时长 {@code d0}"有两处用途：
     * <ol>
     *   <li>{@code rescaleEnergyForDuration(modified, D_引擎, d0)} —— 把引擎时长 {@code D} 反缩放回 {@code d0}；</li>
     *   <li>N6 的 {@code applyModuleDuration(modified, d0)} —— 天花板 {@code min(d0, max(1, round(d0×f)))}。</li>
     * </ol>
     * 它们原本都取 {@code origins.get(0)}（上游同写法）。<b>但跨配方并行打开后，
     * 一份配方其实是【N 条配方同时跑】的聚合</b>，而每条的 {@code d0} 可以不相等
     * ⇒ 拿第一条当整批的代表是没有依据的。
     * <p>取 <b>max</b> 的理由：聚合体的时长语义 = "这一批全部做完要多久"，
     * 它不可能比其中最慢的那一条还短；而 N6 的天花板是"绝不把配方拖慢"
     * ⇒ 用最长的那条当天花板，是唯一同时满足这两句话的取值。
     *
     * <h2>🔴 值 = 1 时恒等</h2>
     * 候选只有一条时 {@code max} 就是那一条本身 ⇒ <b>与改动前逐值相同</b>（零分叉）。
     */
    private static int shanhai$aggregateOriginDurationOf(@NotNull ParallelData parallelData) {
        final List<GTRecipe> origins = parallelData.getOriginRecipeList();
        if (origins.isEmpty()) {
            return 0;
        }
        int max = 0;
        for (GTRecipe origin : origins) {
            max = Math.max(max, origin.duration);
        }
        return Math.max(0, max);
    }

    /**
     * 🔴 <b>聚合口径的"原生链 ×p"总耗能 = {@code Σ baseEUt_i × p_i}</b>（long，饱和）。
     *
     * <h2>它替代的是什么</h2>
     * 单条候选时，{@link #shanhai$applyNativeAuthority} 用
     * {@code rescaleEnergyForDuration(expected, pInt, 1)} 表达原生链的"配方 EUt × p"。
     * 多配方并行时，机器在<b>同一时刻</b>消耗的是<b>每条各自 {@code baseEUt_i × p_i} 之和</b>
     * （引擎的 {@code buildNormalRecipe} 累的 {@code totalEu} 也是这个量再乘各自时长，
     * 见 {@code MutableRecipesLogic.buildFinalNormalRecipe} 的两支累加式）。
     * ⇒ 只取 {@code origins.get(0)} 那一条会把耗电少报 {@code (N-1)/N}
     * （**静默白送电力**，正是本工程明令禁止的失败形态）⇒ 必须求和。
     *
     * <h2>🔴 值 = 1 时恒等</h2>
     * 单条候选时本式 = {@code baseEUt_0 × p_0}，与
     * {@code rescaleEnergyForDuration(copy, (int) p, 1)} 的取值路径不同（后者走 double 乘法），
     * <b>所以单条那一支【仍然走旧路径、不调本方法】</b> —— 见调用点。
     */
    private static long shanhai$aggregateNativeEutOf(@NotNull ParallelData parallelData) {
        final List<GTRecipe> origins = parallelData.getOriginRecipeList();
        final long[] parallels = parallelData.getParallels();
        if (origins.isEmpty() || parallels.length == 0) {
            return 0L;
        }
        long sum = 0L;
        for (int i = 0; i < origins.size(); i++) {
            final long p = i < parallels.length ? Math.max(1L, parallels[i]) : 1L;
            sum = PrimordialModuleMachine.saturatedAdd(sum,
                    PrimordialModuleMachine.saturatedMultiply(RecipeHelper.getInputEUt(origins.get(i)), p));
        }
        return sum;
    }

    // ═══════════════ 原生权威 + 影子对账（"值=1 逐字一致"的可核对证据） ═══════════════

    /**
     * 🔴 <b>用"原生链重放值"作为最终权威</b>，并把它与引擎产出的差异打成可核对日志。
     *
     * <h2>为什么必须"覆盖"而不是"只对账"（2026-09-26 用户实机抓到的真 bug）</h2>
     * 引擎给 EUt 的方式是<b>除回来</b>（{@code RecipeCalculationHelper.buildNormalRecipe}：
     * {@code EUt = totalEu / maxEUt} 再按 duration 分摊），只要除不尽就<b>必然丢余数</b>。
     * 用户实机原文（满配物质模块，p = {@code Integer.MAX_VALUE}）：
     * <pre>
     *   引擎路径 EUt = 3221225470 ；原生链重放 EUt = 3221225471（p = 2147483647，d0 = 1）   ⇒ 差 1
     * </pre>
     * 而原生链是 {@code baseEUt × p}（乘法，精确）⇒ 本方法把 duration 与 EUt 都<b>钉到原生重放值</b>。
     * <p>⚠️ <b>普通档（如 p = 64）本来就一致</b> ⇒ 钉这一下对它们是恒等操作（不产生新实例、不改变数值），
     * 只有极端档会真的被纠正。
     *
     * <h2>为什么这里可以放心"重放"（不碰机器、不扣料）</h2>
     * 全程只用 {@link PrimordialRecipeEffects} 的纯函数作用在 {@code origins.get(0).copy()} 上，
     * <b>不调用任何会读机器仓位/会扣料的引擎 API</b>。
     *
     * <h2>诚实边界（写死，别当成已验证）</h2>
     * 它只能证明"我算的那份 == 我要的那份"，<b>不能</b>证明"== 用户换引擎之前玩到的那份"
     * —— 后者只能由用户做 A/B 对账。
     */
    private @NotNull GTRecipe shanhai$applyNativeAuthority(@NotNull ParallelData parallelData, int originDuration,
                                                           int gateBonus,
                                                           @NotNull GTRecipe engineTailed) {
        try {
            shanhai$selfTestProbe();
            final List<GTRecipe> origins = parallelData.getOriginRecipeList();
            final long[] parallels = parallelData.getParallels();
            if (origins.isEmpty() || parallels.length == 0 || originDuration <= 0) {
                return engineTailed;
            }
            // 🔴 2026-09-26：p 由 int 改 long（并行表末三档现在真的会走到这里）。
            final long p = Math.max(1L, parallels[0]);
            final int originCount = origins.size();
            GTRecipe expected;
            if (originCount == 1) {
                // ── 单条候选：原路（逐字节不变）──
                if (p > (long) Integer.MAX_VALUE) {
                    // ⛔ 这条影子重放链的时长/能量原语【全是 int】
                    //    （PrimordialRecipeEffects.rescaleEnergyForDuration(GTRecipe, int, int)），
                    //    装不下 4.6e18 / 6.9e18 / Long.MAX。
                    //    ⇒ 这里【不做权威覆盖】，因为覆盖会把 EUt 钉到一个被 int 压平的错值上（静默错数）。
                    //    只手打一行可 grep 的日志，把引擎自算的成品原样交回（引擎侧才是 long 的真实值）。
                    shanhai$logShadowReplaySkipped(p, originDuration, engineTailed);
                    return engineTailed;
                }
                final int pInt = (int) p;
                expected = origins.get(0).copy();
                // 原生链的 ×p 由 GTRecipe.copy(ContentModifier, boolean) 完成（四张表全乘，已字节码核实）；
                // 本处没有"并行"这一步可调（不碰机器、更不能扣料），所以用同一条守恒函数的 D/T 形式表达"×p"。
                expected = PrimordialRecipeEffects.rescaleEnergyForDuration(expected, pInt, 1);
            } else {
                // ── 多配方聚合（跨配方并行 ≥ 2 条真的同时在跑）：Σ baseEUt_i × p_i ──
                //   🔴 不调 rescaleEnergyForDuration 那一步：它表达的是"单条 ×p"，
                //      而这里要的是 N 条各自 ×p_i 的和。用 setEUtPerTick 直接写这个和
                //      （long，饱和），把 int 那个窗口整个绕开。
                //   ⚠️ 时长仍由后面的 N5/N6 决定（applyModuleDuration 只吃 d0* 与当前时长），
                //      所以这里不需要预设 duration —— 与单条那一支同形。
                expected = origins.get(0).copy();
                final long aggregateEut = shanhai$aggregateNativeEutOf(parallelData);
                expected = PrimordialRecipeEffects.setEUtPerTick(expected, aggregateEut);
                // ⚠️ 必须把基时长也摆到 d0*（origins[0] 的时长可能比 d0* 短）：
                //    链上的 applyDurationReduction 是"当前时长 ×f"，而 applyModuleDuration 是
                //    min(d0*, 当前时长) ⇒ 基数是 origins[0].duration 时会得到 min(d0*, d0_0×f) = d0_0×f
                //    （静默把聚合体的时长按第一条算）。单条候选时 d0* == origins[0].duration，
                //    这一句是恒等的，所以【只在这一支里做】。
                if (originDuration > 0) {
                    expected = PrimordialRecipeEffects.applyHostDurationCap(
                            expected, originDuration, originDuration);
                }
                shanhai$logAggregateAuthority(originCount, aggregateEut, p);
            }
            expected = PrimordialRecipeEffects.multiplyOutputs(
                    expected, PrimordialRecipeEffects.outputMultiplier(gateBonus));
            expected = PrimordialRecipeEffects.applyDurationReduction(
                    expected, PrimordialRecipeEffects.reductionFactor(gateBonus));
            expected = PrimordialRecipeEffects.applyModuleDuration(expected, originDuration);
            // 🔴 2026-09-30（用户拍板 B）：影子重放必须把 ④-b 那一步【一模一样地】重放一遍。
            //    漏了它的后果不是"少一条断言"，而是对账器会把真实配方的 duration **强行改回**
            //    它自己那套（没有下限的）期望值 ⇒ 下限被静默抹掉。判据与算式取自同一个纯核
            //    + 同一个预算方法 {@link #shanhai$parallelBudget()}，不存在第二份来源。
            expected = PrimordialRecipeEffects.applyLongScaleDurationFloor(
                    expected, originDuration, shanhai$parallelBudget());
            expected = PrimordialRecipeEffects.reduceEnergy(
                    expected, PrimordialRecipeEffects.reductionFactor(gateBonus));

            // ── 权威覆盖：duration 与 EUt 都钉到原生重放值（一致时是恒等操作）──
            GTRecipe corrected = engineTailed;
            if (corrected.duration != expected.duration) {
                corrected = PrimordialRecipeEffects.applyHostDurationCap(
                        corrected, expected.duration, expected.duration);
            }
            corrected = PrimordialRecipeEffects.setEUtPerTick(
                    corrected, RecipeHelper.getInputEUt(expected));

            shanhai$compare("真实对账", expected, corrected, p, originDuration);

            // 🔴 realParallels 探针 —— ⚠️ **影子对账【不覆盖】这一项**（只记录、不判成败）。
            //    原生链会把 realParallels 设成 p（gtlcore ParallelLogicMixin.doParallelRecipes 的
            //    setRealParallels(limitByOutput × 原值)），而引擎新造的成品保持默认 1。
            //    它【不参与】逐 tick EU 扣减（gtlcore 里只进 RecipeRunner 的概率掷骰、
            //    BatchProcessing 与 RecipeMultiplierTracker 显示）⇒ 数值口径不受影响；
            //    但玩家开【批处理】时这一档仍可能有细微差别 ⇒ 打出来供实机核对。
            //
            // 🔴 2026-10-01（用户点单「日志的问题」）：本行实测打了 507 次，
            //    而 507 次的形态只有三种（p = 320 / 9216 / 64，引擎成品恒为 1）。
            //    探针自己的 javadoc 已写明「不一致不算失败」——**这一项的常态就是"不一致"**，
            //    所以照字面「不一致才打」等于一条都不少 ⇒ 刷屏照旧。
            //    ⇒ 改成「**同一形态只打一次**」：已知形态（成品 = 1）每个 p 打一行；
            //      **偏离已知形态（成品 ≠ 1）当场 WARN**。判定本体在 ShanhaiLogThrottle（纯 JDK）。
            final long actualRealParallels = ((IGTRecipe) corrected).getRealParallels();
            final ShanhaiLogThrottle.Verdict rp = ShanhaiLogThrottle.decideRealParallels(
                    shanhai$REAL_PARALLELS_GATE, p, actualRealParallels, System.currentTimeMillis());
            if (rp.level != ShanhaiLogThrottle.Level.NONE) {
                if (rp.abnormal) {
                    ShanhaiMod.LOGGER.warn("[SHANHAI-MODULE-EQ] 🔴 realParallels 探针：原生链本会设为 p={}，"
                                    + "引擎成品 = {} —— **偏离已知形态（成品应为 {}）**；不一致本身不算失败，"
                                    + "但这是新形态，请连同这一行原文一起看。自上次落盘以来同形重复 {} 次已静默。",
                            p, actualRealParallels, ShanhaiLogThrottle.EXPECTED_ENGINE_REAL_PARALLELS, rp.suppressed);
                } else {
                    ShanhaiMod.LOGGER.info("[SHANHAI-MODULE-EQ] realParallels 探针（⚠️ 影子对账【不覆盖】此项）："
                                    + "原生链本会设为 p={}，引擎成品 = {}；不一致不算失败，但开【批处理】时请留意这一行。"
                                    + "【本行已改为「同一形态只打一次」：自上次落盘以来同形重复 {} 次已静默】",
                            p, actualRealParallels, rp.suppressed);
                }
            }
            return corrected;
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("[SHANHAI-MODULE-EQ] 对账器自身异常（不影响配方）：{}", t.toString());
            return engineTailed;
        }
    }

    /** 影子重放够不着 long 档时的一次性可 grep 证据行（只在 p 变化时打）。 */
    private static long shanhai$lastShadowSkipped = Long.MIN_VALUE;

    /** 「聚合权威」的一行证据（只打一次，不刷屏）。 */
    private static boolean shanhai$aggregateAuthorityLogged;

    /**
     * 🔴 <b>多配方聚合时"耗能是按全部候选求和、不是只取第一条"的可 grep 证据行。</b>
     *
     * <p>这一行是给验收用的：它把「{@code N 条同时跑}」这件事在<b>耗能口径</b>上也坐实。
     * 若看到 {@code Σ baseEUt×p} 只等于第一条那一份，说明聚合求和的接线断了
     * （表现 = 静默少收电费）。
     */
    private static void shanhai$logAggregateAuthority(int originCount, long aggregateEut, long firstParallel) {
        if (shanhai$aggregateAuthorityLogged) {
            return;
        }
        shanhai$aggregateAuthorityLogged = true;
        ShanhaiMod.LOGGER.info("[SHANHAI-CROSS-RECIPE] 聚合权威链已接线：本轮 {} 条配方同时跑 ⇒ "
                        + "原生链的『×p 总耗能』按 Σ(baseEUt_i × p_i) 求和 = {} EU/t（不是只取第一条的 p={}）。"
                        + "时长基数 d0* = 候选里最长的那条的时长（N6 天花板随之）。"
                        + "⇒ 单条候选时这一支【完全不走】，仍走原来的 rescaleEnergyForDuration(p,1)，逐值不变。",
                originCount, aggregateEut, firstParallel);
    }

    // 📝 2026-09-30 已删除：`shanhai$logBatchSkippedMultiRecipe(int)` 与它的 `shanhai$batchMultiSkippedLogged`。
    //    它打的是「本轮有 N 条配方同时在跑 ⇒ 【批处理本次不施加】」那行 WARN —— 那是 2026-09-29 的
    //    【安全选择】（多配方下整体停用批处理，因为当时只会补第一条的料）。
    //    本轮门控已拆掉、改成正确形态 ⇒ 这行 WARN 的判据已经**反转**：
    //    今天看到「多配方 ⇒ 批处理不施加」不再代表"设计如此"，只可能是
    //    `ShanhaiBatchPlan` 的降级通道被触发（算不出 / 预检不过），
    //    它会打出 `[SHANHAI-BATCH] 多配方档批处理【本轮不施加】：…原因：…`。
    //    保留这段注释是为了让"老日志"与"新日志"能被分开读（本工程惯例：改判时旧文不删，只加注）。

    private static void shanhai$logShadowReplaySkipped(long p, int originDuration, @NotNull GTRecipe engineTailed) {
        if (p == shanhai$lastShadowSkipped) {
            return;
        }
        shanhai$lastShadowSkipped = p;
        ShanhaiMod.LOGGER.warn("[SHANHAI-MODULE-EQ] 🔴 影子重放【已跳过】：p={} 超出 int 范围，"
                        + "而 PrimordialRecipeEffects.rescaleEnergyForDuration 的时长形参是 int "
                        + "⇒ 重放值会被压平。为避免把 EUt 钉到错的数上（静默错数），"
                        + "本档【不做权威覆盖】，配方 = 引擎自算的 long 真实值。"
                        + "引擎成品 duration={} EUt={} d0={}",
                p, engineTailed.duration, RecipeHelper.getInputEUt(engineTailed), originDuration);
    }

    /**
     * 🔴 <b>「真实对账」与「realParallels 探针」两行的闸门（2026-10-01 用户点单「日志的问题」）。</b>
     *
     * <p>实测：{@code 真实对账 一致} 打了 <b>507 次</b>（且 507/507 全部一致）、
     * {@code realParallels 探针} 打了 <b>507 次</b>（形态只有三种）。
     * 对账<b>通过</b>本来就<b>不需要</b>打 —— 只有「不通过」才是信号。
     * <p>判定本体在 {@link ShanhaiLogThrottle}（纯 JDK；离线可单跑自检，游戏里跑的是同一份字节码）。
     */
    private final ShanhaiLogThrottle.Gate shanhai$moduleEqGate = new ShanhaiLogThrottle.Gate();

    /** realParallels 探针那一行的闸门（静态：探针本身不依赖机器实例）。 */
    private static final ShanhaiLogThrottle.Gate shanhai$REAL_PARALLELS_GATE = new ShanhaiLogThrottle.Gate();

    /**
     * 对比一对 (duration, EUt)；<b>一致 ⇒ 不打</b>（对账通过本来就不需要打），
     * <b>不一致 ⇒ 当场 ERROR</b>。
     *
     * <h2>🔴 2026-10-01 改了什么（用户点单「日志的问题」）</h2>
     * <pre>
     *   原来：一致 / 不一致**都打** —— 实测 507 行全是「一致」⇒ 纯噪声，占全日志约 5%
     *   现在：一致 ⇒ 静默（只累计一个计数器）；不一致 ⇒ 当场 ERROR（**级别没有降级**，
     *         改动前这一支就是 ERROR。用户口径里写的「升级到 WARN」= 相对那条 INFO 常态行而言，
     *         而本工程的红线是「不许把 WARN / ERROR 降级」⇒ 保留 ERROR）
     * </pre>
     * 「静默了多少次」不会丢：不一致那一行会把<b>此前已静默的一致次数</b>一并打出来
     * ⇒ 一眼就能看出「对账器一直在跑、只是没说话」。
     */
    private void shanhai$compare(String tag, @NotNull GTRecipe expected, @NotNull GTRecipe actual, long p, int d0) {
        final long expectedEut = RecipeHelper.getInputEUt(expected);
        final long actualEut = RecipeHelper.getInputEUt(actual);
        final ShanhaiLogThrottle.Verdict v = ShanhaiLogThrottle.decideModuleEquality(
                shanhai$moduleEqGate, tag, p, d0,
                actual.duration, actualEut, expected.duration, expectedEut, System.currentTimeMillis());
        if (v.level == ShanhaiLogThrottle.Level.NONE) {
            return;
        }
        ShanhaiMod.LOGGER.error("[SHANHAI-MODULE-EQ] 🔴 {} 不一致！引擎路径 duration={} EUt={}；原生链重放 duration={} EUt={}（p={}，d0={}）"
                        + " ⇒ 值=1 的行为与今天分叉，请把这一行原文交回。"
                        + "（此前已静默 {} 次「一致」—— 对账器一直在跑，只是通过时不打）",
                tag, actual.duration, actualEut, expected.duration, expectedEut, p, d0, v.suppressed);
    }

    /**
     * 🔴 <b>对账器的正面对照</b>：喂一个【已知为坏】的样本（duration 故意差 1），
     * 确认 {@link #shanhai$compare} 走的是"不一致"分支。只跑一次。
     */
    private void shanhai$selfTestProbe() {
        if (shanhai$probeSelfTested) {
            return;
        }
        shanhai$probeSelfTested = true;
        // 🔴 2026-10-01（用户点单「日志的问题」）：日志闸门 ShanhaiLogThrottle 的自检也在这里跑一次。
        //    闸门是**纯 JDK 代码**（无 Minecraft / GT 依赖）⇒ 游戏里跑的就是离线自证跑过的那一份字节码。
        //    「正常静默 / 异常仍看得见」这两条能力如果坏了，这一句会当场抛
        //    IllegalStateException（被 shanhai$applyNativeAuthority 的 catch 兜住并报 ERROR）。
        ShanhaiMod.LOGGER.info(ShanhaiLogThrottle.selfTest());
        final GTRecipe base = com.gregtechceu.gtceu.data.recipe.builder.GTRecipeBuilder.ofRaw().buildRawRecipe();
        base.duration = 100;
        final GTRecipe broken = base.copy();
        broken.duration = 101;
        if (shanhai$isSameAs(base, broken)) {
            throw new IllegalStateException("[SHANHAI-MODULE-EQ] 对账器正面对照失败："
                    + "两份额外相差 1 tick 的配方被它判成'一致' ⇒ 对账器本身是坏的，"
                    + "它在真实输入上说的'一致'没有任何信息量。");
        }
        ShanhaiMod.LOGGER.info("[SHANHAI-MODULE-EQ] 对账器正面对照通过：故意差 1 tick 的样本被判成【不一致】"
                + "（⇒ 它对真实输入报的'一致'可信）。");
    }

    private static boolean shanhai$isSameAs(@NotNull GTRecipe a, @NotNull GTRecipe b) {
        return a.duration == b.duration && RecipeHelper.getInputEUt(a) == RecipeHelper.getInputEUt(b);
    }
}
