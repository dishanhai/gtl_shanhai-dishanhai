package com.shanhai.machine.host;

import com.gregtechceu.gtceu.api.capability.recipe.IRecipeCapabilityHolder;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;

import com.shanhai.ShanhaiMod;
import com.shanhai.common.heat.ShanhaiExtraMountGate;
import com.shanhai.common.heat.ShanhaiHeatGate;
import com.shanhai.common.machine.PrimordialEngineRecipeLogic;
import com.shanhai.common.recipe.PrimordialRecipeEffects;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import org.gtlcore.gtlcore.api.recipe.IGTRecipe;
import org.gtlcore.gtlcore.api.recipe.RecipeRunnerHelper;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;

/**
 * 归墟视界枢核的配方逻辑 —— {@link PrimordialEngineRecipeLogic} ＋ <b>「额外挂载槽 ×3」闸门</b>。
 *
 * <h2>用户这次要的第三件事（原话逐字）</h2>
 * <blockquote>「然后我说的<b>配方条件（就是模块上面那 3 个槽）复写</b>你还没做，你也给他做了，
 * <b>接口 gui 就和模块的一样</b>」</blockquote>
 *
 * <h2>🔴 复用同一个判定核（不许写第二套）</h2>
 * <pre>
 *   槽位存储 / GUI / tooltip     {@link GuixuVisionCoreMachine}（本机的 3 格）
 *   条件 → 需求 / 判定 / 拒绝文案  {@link ShanhaiExtraMountGate}     ← 模块侧也调它（唯一实现）
 *   真正的判定                   {@link ShanhaiHeatGate#evaluate}  ← 本类<b>不</b>另算
 * </pre>
 * 本类<b>只有</b>三件机器侧的事：① 取本机三格内容；② 判"这台机器算不算热力可达"（交给机器的
 * {@code canUseExtraMountAsHeatSource()}，它读的还是 {@code ShanhaiHeatGate#hasHeatSlot}）；
 * ③ 把拒绝原因写进本机的失败原因（{@code [SHANHAI-HOSTGATE]} 日志 ＋ 一行 reason）。
 *
 * <h2>为什么闸门插在 {@code checkRecipe} 里（与模块同一位置）</h2>
 * 模块侧的理由同样成立：{@code checkRecipe} 是"这条配方能不能开工"的<b>唯一</b>收口
 * （{@code matchRecipe && 电压等级 && checkConditions} 三条合取），
 * 插在它这里 ⇒ 三种结果（放行 / 被槽拦下 / 由槽满足了条件）都能在这里定，
 * 而且<b>不需要</b>去改 gtladditions 的 {@code MutableRecipesLogic}（那是别人的类）。
 *
 * <h2>🔴 与模块侧的一处【刻意】不同：失败原因只落日志，不写 gtlcore 的 RecipeStatus</h2>
 * 模块侧把原因写进 {@code IRecipeStatus}（Jade + GUI 都会画那一行）。本机<b>没有</b>接那条链
 * （主机基类没实现 {@code IRecipeStatus}，本任务不许改 {@code common/machine} 那一层），
 * 所以这里只打一行<b>可 grep</b> 的日志（{@code [SHANHAI-HOSTGATE]}），
 * 并在探头里如实写明「玩家在 Jade 上看不到这句」。宁可缺，不可假：不假装它显示了。
 */
public class GuixuVisionCoreRecipeLogic extends PrimordialEngineRecipeLogic {

    /** 与模块侧同一个前缀族（{@code [SHANHAI-EXTRAMOUNT]} 的读数来自共用核）。 */
    public static final String PREFIX = "[SHANHAI-HOSTGATE]";

    /** 已报过的读数（只报一次，避免每次找配方刷屏；与模块侧同一条纪律）。 */
    private static final Set<String> LOGGED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * 本轮的闸门判定结果 —— {@code checkRecipe} 里算一次，后面两段复用它（不重算）。
     */
    @Nullable
    private ShanhaiHeatGate.Outcome shanhai$gateOutcome;

    public GuixuVisionCoreRecipeLogic(GuixuVisionCoreMachine machine) {
        super(machine);
    }

    /**
     * <b>能不能开工</b> —— 父类那三条（{@code matchRecipe} / 电压等级 / {@code checkConditions}）
     * 之间插入本机的「额外挂载槽」闸门。
     *
     * <h2>两段分流（与模块侧逐字同语义，理由见 {@link ShanhaiExtraMountGate#conditionsPass}）</h2>
     * <ol>
     *   <li><b>没有任何需求被槽满足</b> ⇒ <b>原样委托回父类</b>
     *       （{@code super.checkRecipe(recipe)}）。这一条保证"没有额外条件的配方永远照跑"，
     *       而且<b>我们碰不到</b>上游的任何判据。</li>
     *   <li><b>有需求被槽满足</b> ⇒ 本方法自己复刻父类那三条，其中最后一条换成
     *       {@link ShanhaiExtraMountGate#conditionsPass}（"槽已满足"的那几条算过）。</li>
     * </ol>
     *
     * <h2>父类那三条的出处（javap 实证，见 PrimordialModuleRecipeLogic 同名方法的注释）</h2>
     * <pre>
     *   MutableRecipesLogic#checkRecipe(GTRecipe):
     *      7: RecipeRunnerHelper.matchRecipe(machine, recipe)     ⇒ 假就 false
     *     22: IGTRecipe.of(recipe).getEuTier() &gt; machine.getTier() ⇒ 真就 false
     *     40: recipe.checkConditions(this)                        ⇒ 失败就 false
     * </pre>
     */
    @Override
    protected boolean checkRecipe(@NotNull GTRecipe recipe) {
        final GuixuVisionCoreMachine machine = guixu();
        final GTRecipeType type = recipe.recipeType != null ? recipe.recipeType : machine.getRecipeType();
        final boolean heatReachable = type != null
                && ShanhaiHeatGate.isGated(type.registryName.toString())
                && machine.canUseExtraMountAsHeatSource();
        // 判定核：ShanhaiExtraMountGate → ShanhaiHeatGate#evaluate（与模块同一份）
        final ShanhaiHeatGate.Outcome outcome = ShanhaiExtraMountGate.evaluate(
                recipe, type, heatReachable, this, machine.getExtraMountContents());
        shanhai$gateOutcome = outcome;

        if (outcome.needs.isEmpty() && outcome.satisfied.isEmpty()) {
            // 这条配方对"额外条件"一个字都不要求 ⇒ 与今天逐字相同（含日志都不打）
            return super.checkRecipe(recipe);
        }
        shanhai$logGate(type, outcome, heatReachable);

        if (!outcome.allowed) {
            shanhai$logDeny(outcome);
            return false;
        }
        if (outcome.satisfied.isEmpty()) {
            return super.checkRecipe(recipe);
        }

        // ── 有需求被槽满足 ⇒ 复刻父类那三条（只把最后一条换成"槽也算数"的那一版） ──
        if (!RecipeRunnerHelper.matchRecipe((IRecipeCapabilityHolder) machine, recipe)) {
            return false;
        }
        if (IGTRecipe.of(recipe).getEuTier() > machine.getTier()) {
            // 与父类同一条判据（本机 getMaxVoltage 是 Long.MAX_VALUE，实际不会命中；
            // 留着是为了"复刻"而不是"少一条"——缺一条就是比上游更松，属于静默放水）。
            return false;
        }
        return ShanhaiExtraMountGate.conditionsPass(recipe, this, outcome);
    }

    /**
     * 三格的读数行（哪台机器 / 配方类型 / 热力可达 / 需求 / 判定）。
     * <p>没有需求 ⇒ <b>一个字都不打</b>（本包绝大多数配方没有额外条件，不该刷屏）。
     */
    /**
     * 本机（父类的 {@code getMachine()} 静态类型是主机基类 {@code PrimordialOmegaEngineMachine}，
     * 而本逻辑只装在 {@link GuixuVisionCoreMachine} 上 ⇒ 这里一次强转，别在每处都写）。
     */
    @NotNull
    private GuixuVisionCoreMachine guixu() {
        return (GuixuVisionCoreMachine) this.getMachine();
    }

    private void shanhai$logGate(@Nullable GTRecipeType type,
                                 @NotNull ShanhaiHeatGate.Outcome o,
                                 boolean heatReachable) {
        final String key = "gate|" + o.deny + "|" + o.describe();
        if (LOGGED.size() > 128 || !LOGGED.add(key)) {
            return;
        }
        final GuixuVisionCoreMachine machine = guixu();
        ShanhaiMod.LOGGER.info("{} 机器={}（{}） 配方类型={} 热力可达={} 三格={} {}",
                ShanhaiExtraMountGate.PREFIX,
                machine.getBlockState().getBlock(),
                machine.getPos(),
                type == null ? "-" : type.registryName,
                heatReachable,
                machine.describeExtraMounts(),
                o.describe());
    }

    /** 被槽拦下：一行可 grep 的日志 ＋ 一行<b>只给日志</b>的原因（见类注释的"刻意不同"）。 */
    private void shanhai$logDeny(@NotNull ShanhaiHeatGate.Outcome o) {
        final List<ItemStack> stacks = guixu().getExtraMountStacks();
        final Component reason = ShanhaiExtraMountGate.denyReason(o, stacks);
        // 🆕 2026-11-01：主机基类那条「我们自己的 Jade 通道」（PrimordialEngineRecipeLogic#shanhai$recordIdleReason）
        //    在这里收口 —— 归墟覆写了 checkRecipe、不走父类的拦截路径，所以必须显式交一次，
        //    否则归墟的抬头上永远不会有"为什么没跑"（用户要的正是这一行）。
        shanhai$recordIdleReason(reason);
        final String key = "deny|" + o.deny + "|" + o.describe();
        if (LOGGED.size() > 128 || !LOGGED.add(key)) {
            return;
        }
        ShanhaiMod.LOGGER.info("{} 配方被额外挂载槽拦下 机器={} 原因={} {}",
                PREFIX, guixu().getPos(),
                reason == null ? "(无文案)" : reason.getString(),
                o.describe());
    }

    /** 上一轮的判定结果（只读出口；自检/日志用，别在别处改语义）。 */
    @Nullable
    public ShanhaiHeatGate.Outcome shanhai$lastGateOutcome() {
        return shanhai$gateOutcome;
    }

    // ═══════════════════════════════════════════════════════════════════════════════════
    //  🔴 2026-11-21 原始 bug 的根因（证据链仍成立，保留）
    //  <pre>
    //    20:41:34 纯发电轮 → 入池 ✔
    //    20:54:35 混合轮   → 【execute】配方=gtceu:dummy/raw，之后【再无】任何入池行
    //                        （面板 发电读数=0 取自=过期 = 整整一轮一次都没入池）
    //  </pre>
    //  读数两条路径都会算 ⇒ 读数 null 只可能是<b>这一轮 ParallelData 里没有那条发电配方</b>；
    //  而分配层是"逐条候选问份数、份数直接从预算里减、预算 &lt;= 0 就 break"，
    //  份数 = {@code min(预算, 物品量上限, 流体量上限)} ⇒ 没有消耗型输入的那条一进循环就把预算吃干
    //  ⇒ 排在它后面的候选<b>一次都没被问过</b>。纯发电轮只有一条候选 ⇒ 永远轮得上。
    //
    //  ═══════════════════════════════════════════════════════════════════════════════
    //  ⛔ 2026-11-21 第一版修法（"让发电型候选先分料"）【2026-11-22 实机证伪，原文留档】
    //  它假设"发电每份要 32 mB 流体 ⇒ 份数上限 ≤ Long.MAX/32 ⇒ 吃不干预算"。
    //  现场反例（预算只有 1024）：发电一条吃掉整个预算 ⇒ 耗电 parallels=0 ⇒
    //  用户：「现在归墟只会跑发电配方了，正常配方不跑了」。
    //  ⇒ <b>只改顺序治不了饥饿，顺序换向只是把饥饿换个方向。</b>
    //
    //  ═══════════════════════════════════════════════════════════════════════════════
    //  ✅ 2026-11-22 第二版：<b>两段式分料</b>（耗电一趟、发电一趟，各给各的预算）
    //
    //  <h2>为什么"按候选封顶"这一版也不行（本轮实测的死角）</h2>
    //  分配循环的第一句是 {@code if (remain <= 0) break;} ⇒ 现场预算 1024 被耗电吃干后
    //  <b>发电根本不会被问到</b> ⇒ 在 {@code calculateParallel} 里封顶<b>永远不执行</b>。
    //  ⇒ 约束必须落在"给发电单独一趟、单独预算"这一层。
    //
    //  <h2>做法</h2>
    //  <pre>
    //    相 1（耗电）：{@code super.calculateParallels()} 照原样跑一整套（含电力自动 / 一致性窗），
    //                 唯一差别 = {@link #lookupRecipeSet()} 暂时把发电型候选摘掉
    //                 ⇒ 发电的料<b>不会在这一趟被扣</b>（不重复扣料）
    //    相 2（发电）：用同一个贪心（{@code greedyAllocateWithLongLimit}）单独分一趟，
    //                 预算 = 上限 = ΣA &lt;= 0 ? Long.MAX : max(1, ΣA)
    //                 ⇒ 预算 > 0 ⇒ 它<b>一定被问到</b>，且拿不到超过 ΣA 的份数
    //    合并：{@code new ParallelData(耗电在前 + 发电在后, 份数, false, 已处理表)}
    //  </pre>
    //  纯发电轮 / 纯耗电轮 ⇒ 不进入两段式，走 {@code super}（一个字节都不改）。
    //
    //  <h2>⚠️ 两处已知代价（都写进报告）</h2>
    //  <ol>
    //    <li>ΣA &gt; 预算/2 时总份数会超过预算（最多 2 倍）——"两边都必须能跑"换来的；</li>
    //    <li>两段式那一轮不经过 {@code CandidateSetConsistency} 的观察窗（它只包父类那条路）。</li>
    //  </ol>
    // ═══════════════════════════════════════════════════════════════════════════════════

    /** 正在跑"耗电那一趟" ⇒ {@link #lookupRecipeSet()} 暂时把发电型候选摘掉（保证它们的料不被扣两次）。 */
    private boolean shanhai$allocatingConsumersOnly;

    /** 本轮耗电份数和 ΣA（判据行 / 进度证明行用）。 */
    private long shanhai$consumerSum;
    /** 本轮发电份数和 B（判据行 / 进度证明行用）。 */
    private long shanhai$genSum;
    /** 本轮给发电用的上限（{@code -1} = 不设限 = 纯发电轮）。 */
    private long shanhai$genCap = -1L;
/** 本轮发电"未封顶时"的份数（只给判据行的"未约束时"那一栏）。 */
    private long shanhai$genRaw = -1L;
    /** 本轮并行预算（= 相 1 第一条候选拿到的 {@code remain}；{@code -1} = 没记录下来）。 */
    private long shanhai$allocBudget = -1L;

    /** 「分料结算」那一行的形状串（限流）。 */
    private String shanhai$lastSplitLog = "";

    /** 「分料顺序」那一行的形状串（限流；本轮把它从"发电先分料"改回"耗电先分料"后保留同一套限流）。 */
    private String shanhai$lastAllocOrderLog = "";

    /**
     * <b>只记录本轮预算</b>，然后<b>原样</b>交给父类 —— 本方法不做任何钳制（钳制在 {@link #calculateParallels}
     * 的两段式里，通过给相 2 一个独立预算实现）。
     *
     * <p>为什么预算要从这里取：分配层的第一条候选拿到的 {@code remain} 就是本轮预算
     * （相 1 走的是父类原路径，所以它对"长/短两条分支"都成立，不依赖我们自己猜）。
     */
    @Override
    protected it.unimi.dsi.fastutil.longs.LongLongPair calculateParallel(
            @NotNull IRecipeLogicMachine machine, @NotNull GTRecipe match, long remain) {
        if (shanhai$allocBudget < 0L && remain > 0L) {
            shanhai$allocBudget = remain;                 // 本轮第一条候选 ⇒ 这就是预算
        }
        return super.calculateParallel(machine, match, remain);
    }
    /** 本轮分料读数（只给机型侧的"进度证明"行用）：{@code {耗电份数和, 发电份数}}。 */
    @NotNull
    public long[] shanhai$lastSplit() {
        return new long[]{shanhai$consumerSum, shanhai$genSum};
    }

    /**
     * <b>分料入口（两段式）</b>：耗电一趟 → 发电一趟（预算 = 上限）→ 合并。
     *
     * <p>纯轮（只有一边有候选）⇒ 原样 {@code super.calculateParallels()}。
     * 任何异常 ⇒ 退回 {@code super}（宁可"按老口径挤一次"，也不许把分料弄坏）。
     */
    @Override
    protected com.gtladd.gtladditions.common.data.ParallelData calculateParallels() {
        shanhai$consumerSum = 0L;
        shanhai$genSum = 0L;
        shanhai$genCap = -1L;
        shanhai$genRaw = -1L;
        shanhai$allocBudget = -1L;
        final Set<GTRecipe> all;
        try {
            all = lookupRecipeSet();
        } catch (Throwable t) {
            return super.calculateParallels();
        }
        final List<GTRecipe> consumers = new java.util.ArrayList<>();
        final List<GTRecipe> generators = new java.util.ArrayList<>();
        for (final GTRecipe candidate : all) {
            if (candidate == null) {
                continue;
            }
            if (shanhai$zeroable(candidate)) {
                generators.add(candidate);
            } else {
                consumers.add(candidate);
            }
        }
        if (generators.isEmpty() || consumers.isEmpty()) {
            // 纯耗电轮 / 纯发电轮 ⇒ 老路径（含父类的电力自动、一致性窗）
            return super.calculateParallels();
        }
        // 说明：相 1 里 {@code calculateParallel} 会把"第一条候选拿到的 remain"记进 {@link #shanhai$allocBudget}
        //      ⇒ 预算的真实口径来自分配层自己，不是我们另外猜一个数。
        try {
            // ── 相 1：耗电（候选里暂时没有发电 ⇒ 发电的料不会被扣两次） ──
            final com.gtladd.gtladditions.common.data.ParallelData consData;
            shanhai$allocatingConsumersOnly = true;
            try {
                consData = super.calculateParallels();
            } finally {
                shanhai$allocatingConsumersOnly = false;
            }
            final long consumerSum = shanhai$sumParallels(consData);
            shanhai$consumerSum = consumerSum;
            // ── 相 2：发电（独立预算 = 上限；预算 > 0 ⇒ 一定被问到） ──
            //    ⚠️ "未约束时份数"必须在【扣料之前】量（否则读到的是扣完剩下的料，判据行会失真）
            shanhai$genRaw = shanhai$rawParallelOfFirst(generators);
            // 预算 = 相 1 真实用掉的那个（第一条候选拿到的 remain；记录不到才退回本机预算钩子）
            final long budget = shanhai$allocBudget > 0L
                    ? shanhai$allocBudget : guixu().perRecipeParallelBudget();
            final long cap = GuixuZeroPointGeneration.generationParallelCap(consumerSum, budget);
            shanhai$genCap = consumerSum <= 0L ? -1L : cap;
            final com.gtladd.gtladditions.common.data.ParallelData genData =
                    PrimordialRecipeEffects.greedyAllocateWithLongLimit(generators, cap,
                            getMachine(), (recipe, remain) -> calculateParallel(getMachine(), recipe, remain));
            final long genSum = shanhai$sumParallels(genData);
            shanhai$genSum = genSum;
            if (genSum <= 0L) {
                // 发电那一趟没分到料（料不够 / 条件不满足）⇒ 只跑耗电（诚实：不凭空造份数）
                shanhai$genRaw = -1L;
                return consData;
            }
            return shanhai$merge(consData, genData);
        } catch (Throwable t) {
            shanhai$allocatingConsumersOnly = false;
            ShanhaiMod.LOGGER.error("{} 归墟两段式分料失败（本轮退回父类原路径）：{}",
                    GuixuZeroPointGeneration.TAG, t.toString());
            return super.calculateParallels();
        }
    }

    /** 一份 {@code ParallelData} 的份数和（{@code null} ⇒ 0）。 */
    private static long shanhai$sumParallels(@Nullable com.gtladd.gtladditions.common.data.ParallelData data) {
        if (data == null) {
            return 0L;
        }
        final long[] parallels = data.getParallels();
        long sum = 0L;
        if (parallels != null) {
            for (final long p : parallels) {
                if (p > 0L) {
                    sum = com.shanhai.machine.module.PrimordialModuleMachine.saturatedAdd(sum, p);
                }
            }
        }
        return sum;
    }

    /** 发电候选"不设限时"能拿到多少份（只读探针，不扣料；只给判据行）。 */
    private long shanhai$rawParallelOfFirst(@NotNull List<GTRecipe> generators) {
        try {
            for (final GTRecipe gen : generators) {
                final long p = org.gtlcore.gtlcore.api.recipe.IParallelLogic.getMaxParallel(
                        (com.gregtechceu.gtceu.api.capability.recipe.IRecipeCapabilityHolder) getMachine(),
                        gen, guixu().perRecipeParallelBudget());
                if (p > 0L) {
                    return p;
                }
            }
        } catch (Throwable ignored) {
            // 纯诊断
        }
        return -1L;
    }

    /**
     * 合并两趟的 {@code ParallelData}（耗电在前、发电在后；{@code shouldProcess = false}，与两趟同形）。
     *
     * <p>为什么可以合并：两趟是<b>互不相交的候选集合</b>，各自已经把自己那份料扣掉了一次
     * ⇒ 合起来"每条候选恰好扣一次"，不存在重复扣料。
     */
    @NotNull
    private static com.gtladd.gtladditions.common.data.ParallelData shanhai$merge(
            @Nullable com.gtladd.gtladditions.common.data.ParallelData first,
            @Nullable com.gtladd.gtladditions.common.data.ParallelData second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        final List<GTRecipe> origins = new java.util.ArrayList<>(first.getOriginRecipeList());
        final List<GTRecipe> processed = new java.util.ArrayList<>(first.getProcessedRecipeList());
        origins.addAll(second.getOriginRecipeList());
        processed.addAll(second.getProcessedRecipeList());
        final long[] a = first.getParallels();
        final long[] b = second.getParallels();
        final long[] merged = new long[a.length + b.length];
        System.arraycopy(a, 0, merged, 0, a.length);
        System.arraycopy(b, 0, merged, a.length, b.length);
        return new com.gtladd.gtladditions.common.data.ParallelData(origins, merged, false, processed);
    }

    /**
     * <b>候选集</b>（唯一改动 = <b>耗电型按原序在前、"发电型视界能流抽取"稳定地排到最后</b>）。
     *
     * <p>⚠️ 2026-11-21 第一版把发电排到<b>最前</b>，被实机证伪（"只会跑发电配方，正常配方不跑"）——
     * 原因与落档见 {@link GuixuZeroPointGeneration} ②'' 那一节。现行方向 = 耗电在前：
     * 一来这就是现场的自然顺序（分区恒等 ⇒ 老路径零改动），
     * 二来只有"发电排在耗电之后"，轮到它时 {@code 预算 − remain} 才等于"耗电已分掉的份数和"，
     * {@code calculateParallel} 才能按用户口径给它封顶。
     *
     * <p>只在真的有发电型候选时才重建集合；否则<b>原样返回父类那个实例</b>
     * ⇒ 纯耗电轮 / 纯发电轮的顺序与改动前逐位相同。
     */
    @Override
    protected @NotNull Set<GTRecipe> lookupRecipeSet() {
        final Set<GTRecipe> all = super.lookupRecipeSet();
        if (shanhai$allocatingConsumersOnly) {
            // 🔴 "耗电那一趟"：把发电型候选<b>摘掉</b>（它们的料留给发电那一趟扣，绝不重复扣料）。
            //    这是两段式能成立的关键一步 —— 见 calculateParallels 的注释。
            try {
                final Set<GTRecipe> consumersOnly = new java.util.LinkedHashSet<>(all.size());
                for (final GTRecipe candidate : all) {
                    if (candidate != null && !shanhai$zeroable(candidate)) {
                        consumersOnly.add(candidate);
                    }
                }
                return consumersOnly;
            } catch (Throwable t) {
                return all;                       // 过滤失败 ⇒ 原样（宁可挤一次，也不许把候选集弄丢）
            }
        }
        try {
            final int n = all.size();
            if (n <= 1) {
                return all;                       // 0/1 条：分区必然是恒等序，连数组都不建
            }
            final List<GTRecipe> list = new java.util.ArrayList<>(all);
            final int[] ranks = new int[n];
            for (int i = 0; i < n; i++) {
                final GTRecipe candidate = list.get(i);
                final String typeId = candidate != null && candidate.recipeType != null
                        ? candidate.recipeType.registryName.toString() : null;
                final long outEut = candidate == null ? 0L
                        : com.gregtechceu.gtceu.api.recipe.RecipeHelper.getOutputEUt(candidate);
                ranks[i] = GuixuZeroPointGeneration.allocationRank(typeId, outEut);
            }
            final int[] order = GuixuZeroPointGeneration.allocationOrderConsumerFirst(ranks);
            if (GuixuZeroPointGeneration.isIdentityOrder(order)) {
                return all;                       // ← 🔴 耗电本来就在前（现场自然顺序）⇒ 老路径一个字节都不改
            }
            final Set<GTRecipe> reordered = new java.util.LinkedHashSet<>(n);
            for (final int idx : order) {
                reordered.add(list.get(idx));
            }
            shanhai$logAllocOrder(list, order);
            return reordered;
        } catch (Throwable t) {
            // 排序出任何问题 ⇒ 退回父类原集合（宁可"按老顺序挤一次"，也不许把候选集弄丢）
            ShanhaiMod.LOGGER.error("{} 归墟的分料顺序失败（本轮按原候选序，行为等于改动前）：{}",
                    GuixuZeroPointGeneration.TAG, t.toString());
            return all;
        }
    }

    /**
     * 🔴 <b>「归墟分料结算」判据行</b>（用户点名要的那一行；同形状只打一次）。
     *
     * <pre>
     *   [SHANHAI-GEN-LIMIT] 归墟分料结算 发电份数=B 耗电份数=A 约束=min(耗电份数和)=cap 命中=是/否
     *       未约束时发电份数=raw 剩余预算=(预算−ΣA) 超预算份数=(0|1) 本批预算=预算
     *       批次时长=… tick 批次每tick扣电=… pos=…
     * </pre>
     * <p>{@code 剩余预算} / {@code 超预算份数} 是 2026-11-22 用户拍板「保预算」之后加的：
     * 前者一眼看出这次是不是被预算压住了，后者确认"最多只超 1 份"。
     * 另附两个"这一批到底跑没跑"的读数：
     * <ul>
     *   <li>{@code 批次时长=} —— 聚合配方真正要跑的 tick 数（&gt;0 说明这一批真的在跑）；</li>
     *   <li>{@code 批次每tick扣电=} —— 聚合配方自带的 {@code tickInputs[EU]}（&gt;0 说明<b>耗电那条的电在账上</b>，
     *       这是"耗电配方真的被结算了"的机器可读证据，而不是只看 parallels&gt;0）。</li>
     * </ul>
     */
    private void shanhai$logSplitSettlement(
            @NotNull com.gtladd.gtladditions.common.data.ParallelData parallelData,
            @Nullable GTRecipe built) {
        try {
            long consumerSum = 0L;
            long genSum = 0L;
            final List<GTRecipe> origins = parallelData.getOriginRecipeList();
            final long[] parallels = parallelData.getParallels();
            if (origins != null && parallels != null) {
                for (int i = 0; i < origins.size() && i < parallels.length; i++) {
                    final GTRecipe origin = origins.get(i);
                    if (origin == null || parallels[i] <= 0L) {
                        continue;
                    }
                    if (shanhai$zeroable(origin)) {
                        genSum += parallels[i];
                    } else {
                        consumerSum += parallels[i];
                    }
                }
            }
            final long cap = shanhai$genCap;
            final boolean hit = cap >= 0L && shanhai$genRaw > 0L && shanhai$genRaw > cap;
            final long batchDuration = built == null ? -1L : built.duration;
            final long batchEu = built == null ? -1L
                    : com.gregtechceu.gtceu.api.recipe.RecipeHelper.getInputEUt(built);
            // 🆕 2026-11-22（用户拍板"保预算"）：把"预算被压/没被压"直接打进同一行
            final long budget = shanhai$allocBudget;
            final boolean budgetKnown = budget > 0L && consumerSum > 0L;
            final long budgetLeft = budgetKnown ? Math.max(0L, budget - consumerSum) : -1L;
            final long overBudget = budgetKnown ? Math.max(0L, consumerSum + genSum - budget) : -1L;
            final String shape = consumerSum + "|" + genSum + "|" + cap + "|" + hit + "|" + batchDuration
                    + "|" + batchEu + "|" + budgetLeft + "|" + overBudget;
            if (shape.equals(shanhai$lastSplitLog)) {
                return;
            }
            shanhai$lastSplitLog = shape;
            ShanhaiMod.LOGGER.info("{} 归墟分料结算 发电份数={} 耗电份数={} 约束=min(耗电份数和)={} 命中={} "
                            + "未约束时发电份数={} 剩余预算={} 超预算份数={} 本批预算={} "
                            + "批次时长={} tick 批次每tick扣电={} pos={}",
                    GuixuZeroPointGeneration.TAG, genSum, consumerSum,
                    cap < 0L ? "不设限（纯发电轮）" : String.valueOf(cap),
                    hit ? "是" : "否",
                    shanhai$genRaw < 0L ? "?" : String.valueOf(shanhai$genRaw),
                    budgetLeft < 0L ? "?" : String.valueOf(budgetLeft),
                    overBudget < 0L ? "?" : String.valueOf(overBudget),
                    budgetKnown ? String.valueOf(budget) : "?",
                    batchDuration, batchEu, guixu().getPos());
        } catch (Throwable ignored) {
            // 证据行永远不许影响装配（本包同一条纪律）
        }
    }
    /** 「分料顺序」那一行（同形状只打一次；形状 = 候选 id 的原序 + 新序）。 */
    private void shanhai$logAllocOrder(@NotNull List<GTRecipe> original, @NotNull int[] order) {
        try {
            final StringBuilder before = new StringBuilder();
            final StringBuilder after = new StringBuilder();
            final StringBuilder moves = new StringBuilder();
            for (int i = 0; i < original.size(); i++) {
                if (i > 0) {
                    before.append(", ");
                }
                before.append(String.valueOf(original.get(i).id));
            }
            // order[i] = "新顺序第 i 位取的是原序列的第几个下标" ⇒ 位次变化按新位置报（原 N → 新 M）
            for (int i = 0; i < order.length; i++) {
                if (i > 0) {
                    after.append(", ");
                }
                after.append(String.valueOf(original.get(order[i]).id));
                if (order[i] != i) {
                    if (moves.length() > 0) {
                        moves.append(", ");
                    }
                    moves.append(String.valueOf(original.get(order[i]).id))
                            .append('：').append(order[i] + 1).append('→').append(i + 1);
                }
            }
            final String shape = before + "|" + after;
            if (shape.equals(shanhai$lastAllocOrderLog)) {
                return;
            }
            shanhai$lastAllocOrderLog = shape;
            ShanhaiMod.LOGGER.info("{} 归墟分料顺序 耗电先分料=on 发电排最后=on 候选数={} 原序=[{}] 新序=[{}] 位次变化=[{}]"
                            + "（现场自然顺序 = 恒等序 ⇒ 什么都不动；发电的份数另由 归墟分料结算 那行封顶）pos={}",
                    GuixuZeroPointGeneration.TAG, original.size(), before, after, moves, guixu().getPos());
        } catch (Throwable ignored) {
            // 证据行永远不许把候选集带崩（与本类其它探针同一条纪律）
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════════
    //  D/F（2026-10-25，第二版）：**「配方最短耗时」地板** —— 用户报「一直是 1s」，第一版没修好。
    //
    //  <h2>🔴 第一版为什么没生效（用户实测证据，不是猜）</h2>
    //  第一版把地板补在 {@code buildFinalNormalRecipe(ParallelData)} 的 {@code super} 之后，
    //  结果用户 grep 整份 latest.log：<b>没有任何一行 {@code [SHANHAI-DUR]}</b> ⇒
    //  <b>那个方法一次都没被走到</b> ⇒ 归墟跑的不是「多配方引擎的尾链」那条路
    //  （{@code MutableRecipesLogic#buildFinalNormalRecipe} 只由它自己的
    //   {@code findAndHandleRecipe/handleRecipeWorking} 在并行装配成功时调用；
    //   本机现在一次只跑一条配方 ⇒ 走不到）。⇒ 第一版的覆写已删除（死代码不留）。
    //
    //  <h2>✅ 第二版：挂在 {@code RecipeLogic#setupRecipe}</h2>
    //  {@code javap -p RecipeLogic} 实证：{@code public void setupRecipe(GTRecipe)} —— <b>public</b>，
    //  且它是<b>每条配方开始时唯一必过的闸</b>（{@code duration/maxProgress} 就在它里面被定下来）：
    //  <pre>
    //    引擎路径（MutableRecipesLogic）        ┐
    //    原生路径（RecipeModifierList.apply）   ├→ 都走 RecipeLogic#setupRecipe  ⇒ 这里是"最后一道共同关口"
    //    无线/发电那一脉                        ┘
    //  </pre>
    //  ⇒ 把「面板值」按<b>上游语义（地板）</b>在<b>过闸之前</b>写进配方：
    //     {@code duration = max(配方原时长, max(1, 面板值))}
    //     上游原文（{@code RecipeCalculationHelper.buildNormalRecipe} 反编译）：
    //     {@code recipe.duration = MathKt.roundToInt(Math.max(dx, (double) minDuration));} ⇒ 地板。
    //  🔴 两条安全纪律：
    //    ① <b>绝不原地改 {@code GTRecipe}</b>（同一份配方对象被整个配方表共享，改它 = 污染全部机器）
    //       ⇒ 先 {@code recipe.copy()} 再改副本，把副本交给 {@code super}；
    //    ② {@code panel <= 0} 或异常 ⇒ <b>原样交给 super</b>（行为与改动前逐位相同）。
    // ═══════════════════════════════════════════════════════════════════════════════════

    /** 上一次打 {@code [SHANHAI-DUR]} 的那组读数（只在变化时打，避免刷屏）。 */
    private String shanhai$lastDurLog = "";

    /**
     * 每条配方开始时把「最短耗时」按<b>地板</b>语义补上（上游语义），并打一行判据。
     *
     * <p>只会把<b>比面板值短</b>的配方拖长到面板值；比面板值长的配方<b>一个 tick 都不动</b>
     * （所以"永不变长"那条对长配方依旧成立，与 A 口径不冲突）。
     *
     * <p>🔴 2026-11-14 追加：<b>「零点能发电」的配方清零保险丝</b>也挂在这里（见方法体内 {@code ②}）。
     */
    @Override
    public void setupRecipe(@NotNull com.gregtechceu.gtceu.api.recipe.GTRecipe recipe) {
        // 🔴🆕 2026-11-22：**新一轮开工 ⇒ 逐批摊平计数器归零**（用户口径：绝不把上一批的余额带进下一批）。
        //    放在最前面：连 recipe == null 的那一支也清，保证"任何一次 setup 之后都是干净的一批"。
        //    只清摊平状态（读数 / 池子 / 配方对象一个字节都不碰）。
        try {
            guixu().shanhai$resetVisionGenerationBatch();
        } catch (Throwable ignored) {
            // 归零失败绝不许连坐配方开工（下一次 tick 的身份比较还会重建）
        }
        if (recipe == null) {
            super.setupRecipe(null);
            return;
        }
        com.gregtechceu.gtceu.api.recipe.GTRecipe pass = recipe;
        try {
            // ① 【最短耗时地板】（原有行为，一个字节都没改）
            final int panel = guixu().getLimitedDuration();
            final int origin = recipe.duration;
            final int applied = Math.max(origin, Math.max(1, panel));
            if (panel > 0 && applied != origin) {
                // 只改副本（共享配方对象一个字节都不动）
                final com.gregtechceu.gtceu.api.recipe.GTRecipe copy = recipe.copy();
                copy.duration = applied;
                pass = copy;
            }
            final String key = panel + "/" + origin + "/" + applied;
            if (!key.equals(shanhai$lastDurLog)) {
                shanhai$lastDurLog = key;
                ShanhaiMod.LOGGER.info("[SHANHAI-DUR] host=归墟 panel={} engine={} applied={}"
                                + "（第二版落点 = RecipeLogic#setupRecipe，每条配方必经；上游语义 = 地板："
                                + "applied = max(engine, panel)。engine 是配方进入本闸时的时长）",
                        panel, origin, applied);
            }

            // ② 🔴 2026-11-14：「零点能发电」的配方清零【保险丝】
            //    主落点在 buildFinalNormalRecipe（"匹配前"的那个缝，见该方法注释）；
            //    这里再拦一次的理由是**兜底**：万一这一轮没走多配方引擎尾链，配方自带的电仍会被
            //    逐 tick 送进动力仓 ⇒ 那就等于"同一度电出两次"。
            //    🔴 但**只有在本轮读数已经算好**（= 池子那边确实会入账）时才清零：
            //       拿不到读数却把电清掉 = 白吃那度电（宁可让它从动力仓走，也不凭空吞电）。
            if (shanhai$visionReadings != null && shanhai$zeroable(pass)) {
                if (pass == recipe) {
                    // 上面没建过副本 ⇒ 现在建一份，绝不原地改（并把并行/批处理字段一起带走）
                    pass = shanhai$copyPreservingParallels(recipe);
                }
                com.gregtechceu.gtceu.api.recipe.RecipeHelper.setOutputEUt(pass, 0L);
                shanhai$logTakeOver(pass, shanhai$visionReadings, "setupRecipe");
            }
        } catch (Throwable ignored) {
            pass = recipe;      // 兜底：任何异常都退回"原样交给 super"（行为等于改动前）
        }
        super.setupRecipe(pass);
    }

    // ═══════════════════════════════════════════════════════════════════════════════════
    //  🔴 2026-11-14（用户原话：「那我们当归墟视界枢核运行视界能流抽取的配方时
    //                   执行零点能发生器的逻辑」）
    //
    //  两半：
    //    ① 配方这一半（本段）：跑到 gtceu:vision_energy_extraction 且"配方输出电 > 0"的配方时，
    //       copy() 一份、setOutputEUt(0) 交给引擎 ⇒
    //         · 那度电不会再从动力仓走（否则同一度电出两次）；
    //         · 也不会被多配方引擎当成"负耗电"记进账（发电型的 tickOutputs 是正值，
    //           而引擎的耗电累加点只吃 tickInputs ⇒ 清零后两个方向都是 0）。
    //       ⛔ 绝不改原配方对象（同一份 GTRecipe 被整个配方表共享）。
    //    ② 产量这一半（GuixuVisionCoreMachine#onWorking → shanhai$depositGeneration）：
    //       按 baseEut × 真实并行（BigInteger）入无线池。
    //
    //  🔴 落点为什么是 buildFinalNormalRecipe：
    //     gtladditions MutableRecipesLogic#findAndHandleMultipleRecipe 的顺序是
    //        getRecipe() → matchRecipeOutput(machine, recipe) → setupRecipe(recipe)
    //     （javap 实证，见 temp-selfcheck-out/MutableRecipesLogic.javap.txt:126-148）
    //     ⇒ beforeWorking 太晚；"匹配之前"能动配方的唯一缝就是 getRecipe() 内部的
    //       buildFinalNormalRecipe（字节码 :340-356：wirelessTrait == null ⇒ 走这一支）。
    // ═══════════════════════════════════════════════════════════════════════════════════

    /** 主落点是否被走到过（只报一次；用来回答"这条路到底有没有执行"）。 */
    private boolean shanhai$finalRecipeReached;

    /** 已报过"哪个配方被接管"的配方 id（只报一次，避免每轮刷屏）。 */
    @Nullable
    private net.minecraft.resources.ResourceLocation shanhai$tookOverRecipeId;

    /**
     * 🔴 <b>本轮配方每 tick 该入池多少</b> —— 在"接管那一刻"算好，tick 侧（{@code onWorking}）只读它。
     *
     * <h2>为什么必须在这里算，而不是 tick 时再去读配方（这一条是 bug 而不是风格）</h2>
     * 发电模块那台算产量时读的是 {@code RecipeLogic#getLastOriginRecipe()}（"配方定义实例"）。
     * 那个字段<b>只有一个写者</b>：{@code RecipeLogic#checkMatchedRecipeAvailable}（{@code javap -c}：
     * 全类 {@code putfield lastOriginRecipe} 共 4 处，只有那一处写的是配方对象，其余 3 处写 {@code null}）。
     * 而 {@code checkMatchedRecipeAvailable} <b>只属于原生链</b>；发电模块之所以能读到，是因为它
     * {@code getDefinition().isGenerator()} ⇒ {@code PrimordialModuleRecipeLogic} 会
     * {@code setUseMultipleRecipes(false)} 退回原生链（本工程注释原文）。
     * <p>归墟相反：{@code PrimordialEngineRecipeLogic} 构造器里 {@code setUseMultipleRecipes(true)} 是
     * <b>无条件</b>的 ⇒ 走 {@code MutableRecipesLogic#findAndHandleMultipleRecipe}
     * （{@code getRecipe()} → {@code matchRecipeOutput} → {@code setupRecipe}，<b>完全不碰</b> lastOriginRecipe）
     * ⇒ 在归墟上 {@code getLastOriginRecipe()} <b>永远拿不到这条配方</b>（null 或上一轮的残留）。
     * ⇒ 所以基准必须从本轮装配数据 {@code ParallelData} 里取：
     * <pre>
     *   基础 EUt ← parallelData.getOriginRecipeList()[i]   ← 真的是【配方定义实例】（未经并行/倍率）
     *   并行 p   ← parallelData.getParallels()[i]          ← 引擎这一轮认为这一条要跑几份
     * </pre>
     * 两者都是引擎自己给的读数，本类<b>不自己算</b>并行。
     *
     * <p>🔴 <b>2026-11-21 订正（先前的读法错了，留档以免再错一次）</b>：
     * 曾经有一条判断写成「只有 {@code super.buildFinalNormalRecipe(...) == null} 的回退分支才把读数算出来」
     * ⇒ <b>与字节码/日志都不符</b>：{@link #buildFinalNormalRecipe} 的<b>两条</b>返回路径都调了
     * {@code shanhai$visionReadingsOf(parallelData)}（非 null 分支在方法尾段）。
     * 混合轮里读数为 null 的真正原因是<b>这一轮装配数据里根本没有发电配方</b>
     * （分配层被别的候选一条吃干预算 ⇒ 后面的候选一次都没被问过），
     * 修法与证据见 {@link #lookupRecipeSet()} 上面那一段注释；判据行见 {@link #shanhai$logBatchMix}。
     */
    @Nullable
    private GuixuZeroPointGeneration.Readings shanhai$visionReadings;

    /** 只读出口（{@link GuixuVisionCoreMachine#onWorking()} 每 tick 读它）。 */
    @Nullable
    public GuixuZeroPointGeneration.Readings shanhai$visionReadings() {
        return shanhai$visionReadings;
    }

    /**
     * <b>主落点</b>：多配方引擎装配完、<b>还没匹配输出</b>之前，把归墟这条发电配方的电清零。
     *
     * <p>返回 {@code super} 的同一个实例（非目标配方 ⇒ <b>逐位不变，连副本都不建</b>）；
     * 只有"目标类型 + 输出电 > 0"才建副本并清零。
     *
     * <p>🆕 2026-11-14 <b>第二段：{@code super} 返回 {@code null} 时的回退</b> ——
     * 纯发电配方（{@code shanhai:blackhole_engine_power}）在 {@code outputs} 里一项物品/流体都没有，
     * 上游那道 {@code hasOutputs} 门必然把它判成"没找到配方"⇒ <b>整条装配一次都不发生</b>。
     * 本类于是<b>自己造一份"只有电"的成品配方</b>交回引擎；判据与纪律见方法体内那段注释。
     */
    @Override
    @Nullable
    protected GTRecipe buildFinalNormalRecipe(@NotNull com.gtladd.gtladditions.common.data.ParallelData parallelData) {
        // 每一轮装配先清空读数：这一轮若不是目标配方，tick 侧就什么都不做（绝不吃上一轮的残留）
        shanhai$visionReadings = null;
        final GTRecipe built = super.buildFinalNormalRecipe(parallelData);
        if (built == null) {
            // ═══════════════════════════════════════════════════════════════════════════════════
            // 🆕 2026-11-14 **归墟自己补 null 回退**（只为纯发电配方；负对照见 ①）
            //
            // <h2>为什么上游这里会是 null（字节码，不是猜）</h2>
            //   {@code MutableRecipesLogic#buildFinalNormalRecipe} 装配完输出聚合后有一道门：
            //     {@code :393-409 RecipeCalculationHelper.hasOutputs(itemOutputs, fluidOutputs)}
            //     {@code :409-440} 为假 ⇒ 写 {@code RecipeResult.FAIL_FIND} ⇒ {@code aconst_null; areturn}
            //   而 {@code hasOutputs}（{@code :913-940}）只读 {@code outputs[Item]}/{@code outputs[Fluid]}
            //   两个 {@code isEmpty()}，<b>完全不看 tickOutputs.eu</b> ⇒ 纯发电配方（EUt 写负数 ⇒ 那度电
            //   落在 {@code tickOutputs}，{@code outputs} 一项都没有）<b>必然</b>撞上这道门。
            //   本条 = {@code shanhai:blackhole_engine_power}（1× reality_item_3 不消耗 + 电路 32 +
            //   水 32mB，duration 20，EUt = −140737488355328）。
            //
            // <h2>🔴 三条纪律</h2>
            //   ① <b>非目标配方必须仍然返回 null</b>（负对照：把 null 一律变成非 null ⇒ 所有"没输出"的
            //      配方都会开始跑，等于静默放水）⇒ 下面第一句 {@code if (r == null) return null;}；
            //   ② <b>目标配方但 super 非 null ⇒ 一个字都不碰</b>（走下面原来那条路，逐位与改动前相同）；
            //   ③ <b>绝不改配方定义实例</b>（copy 之后再改；{@code inputs.clear()} 只落在副本上）。
            //
            // <h2>造法：对齐上游"正常返回的那个对象"的每个字段</h2>
            //   上游那份是 {@code RecipeCalculationHelper#buildNormalRecipe} 用 {@code ofRaw()} 现搭的空壳：
            //   {@code inputs} 全程<b>没被写过</b>、{@code outputs} 只有 {@code collectOutputs} 累积的项、
            //   {@code tickInputs} 只有 EU 一项、{@code duration = roundToInt(max(totalEu/overclockVoltage, 20))}。
            //   ⇒ 本机用<b>配方定义实例</b>当底（id / recipeType 都留着，日志与 {@link #shanhai$zeroable}
            //     那道清零保险丝才认得出它），再补上游在那份壳里做过的三件事：
            //     ⒈ {@code inputs} 清空 —— <b>必须</b>：并行分配
            //        （{@code calculateParallelsWithGreedyAllocation} 字节码 {@code :1066-1168} 里的
            //        {@code handleRecipeInput}）已经把料扣过了；留着会让 {@code RecipeLogic#setupRecipe}
            //        的 {@code handleRecipeIO(IN)}（{@code :51-59}）再扣一次 —— 扣不动就整条 IDLE 不启动。
            //     ⒉ 那度电清零（{@code setOutputEUt(copy, 0L)}）—— 与 {@code setupRecipe} 那段保险丝同一个
            //        做法：电由无线池按 {@code shanhai$visionReadings} 入账，同一度电不许出两次。
            //     ⒊ {@code realParallels}/{@code batchSize} —— 上游壳里这两个字段是"没写"（0）；
            //        本机必须写，tick 侧的产量口径要用（{@code ParallelData#getParallels()[i]} 才是
            //        "这一条跑几份"，与 {@link GuixuZeroPointGeneration#readingsFromParallelData} 同源）。
            // ═══════════════════════════════════════════════════════════════════════════════════
            try {
                final GuixuZeroPointGeneration.Readings r = shanhai$visionReadingsOf(parallelData);
                if (r == null) {
                    shanhai$logBatchMix(parallelData, built, null);   // ← 🆕 证据行（这批为什么没结算）
                    shanhai$logSplitSettlement(parallelData, built);   // ← 🆕 分料结算判据行
                    return null;            // ← 🔴 ① 负对照：非目标配方仍然 null
                }
                // 取"配方定义实例"：判据与顺序都照抄 {@link #shanhai$visionReadingsOf}（同一条配方）
                final List<GTRecipe> origins = parallelData.getOriginRecipeList();
                final long[] parallels = parallelData.getParallels();
                GTRecipe originRecipe = null;
                for (int i = 0; i < origins.size() && i < parallels.length && originRecipe == null; i++) {
                    final GTRecipe candidate = origins.get(i);
                    if (candidate == null || parallels[i] <= 0L) {
                        continue;
                    }
                    final String typeId = candidate.recipeType != null
                            ? candidate.recipeType.registryName.toString() : null;
                    if (GuixuZeroPointGeneration.shouldConvert(typeId,
                            com.gregtechceu.gtceu.api.recipe.RecipeHelper.getOutputEUt(candidate), true)) {
                        originRecipe = candidate;
                    }
                }
                if (originRecipe == null) {
                    // 读数在、定义实例却拿不到 ⇒ 仍然"不动"（绝不凭空造一条没有定义的配方）
                    return null;
                }
                shanhai$visionReadings = r;
                final GTRecipe powerOnly = shanhai$copyPreservingParallels(originRecipe);
                powerOnly.inputs.clear();   // ⒈ 料已在并行分配里扣过（见上）
                IGTRecipe.of(powerOnly).setRealParallels(r.parallel);   // ⒊ = getParallels()[i]（≥1）
                IGTRecipe.of(powerOnly).setBatchSize(1);
                com.gregtechceu.gtceu.api.recipe.RecipeHelper.setOutputEUt(powerOnly, 0L);   // ⒉
                shanhai$logTakeOver(powerOnly, r, "buildFinalNormalRecipe/null回退");
                shanhai$logBatchMix(parallelData, powerOnly, r);   // ← 🆕 证据行（本批怎么结算的）
                shanhai$logSplitSettlement(parallelData, powerOnly);   // ← 🆕 分料结算判据行
                return powerOnly;
            } catch (Throwable t) {
                // 任何一步出问题 ⇒ 退回"改动前的行为"（返回 null，机器维持原状），绝不交半成品给引擎
                shanhai$visionReadings = null;
                ShanhaiMod.LOGGER.error("{} 归墟的 null 回退造配方失败（本轮按改动前返回 null）：{}",
                        GuixuZeroPointGeneration.TAG, t.toString());
                return null;
            }
        }
        // ↓↓↓ 以下与改动前逐字相同（目标配方 + super 非 null ⇒ 走原来的"清零 + 入池读数"路径） ↓↓↓
        if (!shanhai$finalRecipeReached) {
            shanhai$finalRecipeReached = true;
            ShanhaiMod.LOGGER.info("{} 归墟的零点能落点【execute】= buildFinalNormalRecipe"
                            + "（多配方引擎尾链；本条一旦出现，说明「匹配前改配方」这条缝真的走到了）配方={}",
                    GuixuZeroPointGeneration.TAG, built.id);
        }
        // ── 从 ParallelData 里找那一条"发电型视界能流抽取"（找不到 ⇒ 与改动前逐位相同） ──
        final GuixuZeroPointGeneration.Readings r = shanhai$visionReadingsOf(parallelData);
        if (r == null) {
            shanhai$logBatchMix(parallelData, built, null);   // ← 🆕 证据行（这批为什么没结算）
            shanhai$logSplitSettlement(parallelData, built);   // ← 🆕 分料结算判据行
            return built;                       // ← 非目标配方：原实例返回，一个字节都没动
        }
        shanhai$visionReadings = r;
        final com.gregtechceu.gtceu.api.recipe.GTRecipe copy = shanhai$copyPreservingParallels(built);
        com.gregtechceu.gtceu.api.recipe.RecipeHelper.setOutputEUt(copy, 0L);
        shanhai$logTakeOver(copy, r, "buildFinalNormalRecipe");
        shanhai$logBatchMix(parallelData, copy, r);   // ← 🆕 证据行（混合轮：本批怎么结算的）
        shanhai$logSplitSettlement(parallelData, copy);   // ← 🆕 分料结算判据行
        return copy;
    }

    /**
     * 在<b>本轮装配数据</b>里找出那条发电配方，并按它的定义实例算好读数。
     *
     * @return {@code null} = 本轮没有"发电型视界能流抽取"（其它类型 / 不发电的条目 / 并行 &le; 0）
     */
    @Nullable
    private GuixuZeroPointGeneration.Readings shanhai$visionReadingsOf(
            @NotNull com.gtladd.gtladditions.common.data.ParallelData parallelData) {
        try {
            final List<GTRecipe> origins = parallelData.getOriginRecipeList();
            final long[] parallels = parallelData.getParallels();
            if (origins == null || parallels == null) {
                return null;
            }
            for (int i = 0; i < origins.size() && i < parallels.length; i++) {
                final GTRecipe originRecipe = origins.get(i);
                if (originRecipe == null) {
                    continue;
                }
                final String typeId = originRecipe.recipeType != null
                        ? originRecipe.recipeType.registryName.toString() : null;
                final long baseEut = com.gregtechceu.gtceu.api.recipe.RecipeHelper.getOutputEUt(originRecipe);
                if (!GuixuZeroPointGeneration.shouldConvert(typeId, baseEut, true)) {
                    continue;
                }
                if (parallels[i] <= 0L) {
                    continue;
                }
                // 🔴 系数 = 主机耗能系数 f（2026-11-14 用户定案「吃主机耗能系数」）：
                //    取值点与零点能模块同源 —— moduleSlotBonus()（主机物质模块槽的门控份数）。
                //    在"接管那一刻"取一次并记进读数：同一轮配方内门控不会变，
                //    tick 侧不再重算（避免"算的时候和发的时候不是同一个系数"）。
                final int gateBonus = guixu().moduleSlotBonus();
                // 🔴🆕 2026-11-22（摊平口径）：把**配方定义实例**的 duration 一并记进读数 ——
                //    摊平的分子用它（{@code 本批计划总量 = 乘积 × 自身时长}）。
                //    取的是 {@code originRecipe.duration}（= {@code ParallelData#getOriginRecipeList()[i]}，
                //    **未经**面板地板、**未经**批次拉长的原值）；拿不到就是 0 ⇒ 摊平退回旧口径并 WARN。
                return GuixuZeroPointGeneration.readingsFromParallelData(
                        baseEut, parallels[i], gateBonus, originRecipe.duration);
            }
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} 归墟读 ParallelData 时异常（本轮不入池、也不清零）：{}",
                    GuixuZeroPointGeneration.TAG, t.toString());
        }
        return null;
    }

    /**
     * <b>只复制配方、并把 gtlcore 那几个"并行/批处理"字段一起带过去</b>。
     *
     * <h2>🔴 为什么必须手动带（这一步漏了会静默少产电）</h2>
     * {@code GTRecipe.copy()} 是 GTCEu 自己的方法，它<b>不认识</b> gtlcore mixin 加进去的那几个字段：
     * {@code realParallels} / {@code batchSize} / {@code batchProcessed} / {@code subTickParallelized}
     * （{@code javap -p -c org.gtlcore.gtlcore.mixin.gtm.api.recipe.GTRecipeMixin}：<b>没有</b> {@code copy()} 覆写，
     * 只有这几个字段自己的 setter/getter）⇒ 复制出来的配方里 {@code getRealParallels() = 0}
     * ⇒ 本工程的产量算式拿到的并行会是 0，{@link GuixuZeroPointGeneration#effectiveParallel} 按 1 兜底
     * ⇒ <b>看起来在发电，实际上只产 1 份</b>（本工程最忌讳的"静默错数"）。
     *
     * <p>同一条纪律在 {@code PrimordialRecipeEffects}（{@code dst.setRealParallels(src.getRealParallels())}）
     * 与 {@code ShanhaiWirelessWrap} 里都是<b>显式补写</b>的 —— 这里逐字照做。
     */
    @NotNull
    private static GTRecipe shanhai$copyPreservingParallels(@NotNull GTRecipe src) {
        final GTRecipe copy = src.copy();
        try {
            final IGTRecipe s = IGTRecipe.of(src);
            final IGTRecipe d = IGTRecipe.of(copy);
            d.setRealParallels(s.getRealParallels());
            d.setBatchSize(s.getBatchSize());
            d.setBatchProcessed(s.isBatchProcessed());
            d.setSubTickParallelized(s.isSubTickParallelized());
        } catch (Throwable ignored) {
            // 老版本 gtlcore 没这些方法时不连坐配方（行为退回"与改动前相同"）
        }
        return copy;
    }

    /**
     * 这条配方要不要被"清零 + 入池"（<b>唯一的判定调用点</b>，第三个实参写死 {@code true}）。
     *
     * <p>判据两条：配方类型 == {@code gtceu:vision_energy_extraction}，且<b>该配方输出电 > 0</b>
     * （{@code RecipeHelper.getOutputEUt}）。第二条不是多余的：同类型下也可能有不发电的条目。
     *
     * <p>🔴 本方法<b>只判不改</b>：改是调用方的事（必须 copy 后再改）。
     */
    private boolean shanhai$zeroable(@NotNull GTRecipe recipe) {
        final String typeId = recipe.recipeType != null ? recipe.recipeType.registryName.toString() : null;
        return GuixuZeroPointGeneration.shouldConvert(typeId,
                com.gregtechceu.gtceu.api.recipe.RecipeHelper.getOutputEUt(recipe), true);
    }

    /**
     * 🔴🆕 2026-11-21 <b>「本批到底怎么结算的」那一行</b>（新前缀族：{@code [SHANHAI-GEN-LIMIT] 混合轮读数 …}）。
     *
     * <p>为什么必须加：上一轮实机日志里，混合轮只有 {@code 【execute】} 一行，<b>既没有入池行、
     * 也没有"为什么没入池"</b> ⇒ 只能靠"面板 发电读数=过期"倒推。这条把判据直接打出来：
     * <pre>
     *   入批=true  ⇒ 本批的 ParallelData 里<b>有</b>发电配方（读数已算、tick 侧会入池）；
     *   入批=false ⇒ 本批里<b>没有</b>发电配方（读数恒 null ⇒ 整轮不结算），
     *                并顺带打一次"候选集里到底有没有它 / 它本可拿多少并行"（只读探针，不扣料）。
     * </pre>
     *
     * <h2>🔴 长批次那条读数（用户点名要的残余风险，直接打出来而不是留给人事后算）</h2>
     * tick 侧的口径是<b>每 tick 入池 = 乘积</b>；批次时长由上游装配决定（实测混合轮 3199 tick、
     * 而发电配方自身 20 tick）⇒ 同一个"每 tick 乘积"在长批次里<b>总付 tick 数更多</b>。
     * 这一行把 {@code 本批时长} / {@code 发电配方自身时长} 与 {@code 倍数} 并排打出来，
     * 让"这一批一共付了多少"变成可读的事实，而不是要人去别处拼。
     *
     * <p>同形状只打一次（形状 = 批次组成 + 时长 + 入池与否），所以不会每批刷屏。
     */
    private void shanhai$logBatchMix(@NotNull com.gtladd.gtladditions.common.data.ParallelData parallelData,
                                     @Nullable GTRecipe built,
                                     @Nullable GuixuZeroPointGeneration.Readings r) {
        try {
            final List<GTRecipe> origins = parallelData.getOriginRecipeList();
            final long[] parallels = parallelData.getParallels();
            final StringBuilder batch = new StringBuilder();
            long ownDuration = -1L;
            if (origins != null && parallels != null) {
                for (int i = 0; i < origins.size() && i < parallels.length; i++) {
                    final GTRecipe origin = origins.get(i);
                    if (origin == null) {
                        continue;
                    }
                    if (batch.length() > 0) {
                        batch.append(", ");
                    }
                    batch.append(origin.id).append('×').append(parallels[i]);
                    if (ownDuration < 0L && parallels[i] > 0L && shanhai$zeroable(origin)) {
                        ownDuration = origin.duration;
                    }
                }
            }
            final int batchDuration = built == null ? -1 : built.duration;
            // 形状 = 入池与否 + 批次组成 + 时长 + 并行（**先判形状再探候选**：探针贵，形状没变就不探）
            final String shape = (r != null ? "in|" : "out|") + batch + "|" + batchDuration
                    + "|" + (r != null ? r.parallel : 0L);
            if (shape.equals(shanhai$lastBatchMixLog)) {
                return;
            }
            shanhai$lastBatchMixLog = shape;
            if (r != null) {
                // 🔴 2026-11-22 订正措辞：本行只报"这一批多长 / 配方自身多长"。
                //    结算口径已改成摊平（总量守恒）：批次变长**不再**意味着多发，
                //    每 tick 实发看同前缀的「归墟零点能摊平」行（两条读数各管一件事，不再互相矛盾）。
                final String note = ownDuration > 0L && batchDuration > ownDuration
                        ? String.format(java.util.Locale.ROOT, "批次倍数=%.2f（摊平后总量不变，见「归墟零点能摊平」行）",
                        (double) batchDuration / (double) ownDuration)
                        : "批次倍数=1.00";
                ShanhaiMod.LOGGER.info("{} 混合轮读数 host=归墟 入批=true 批次=[{}] 发电配方={} 并行={} "
                                + "乘积={} 本批时长={} tick 发电配方自身时长={} tick 付法=总量守恒摊平（{}）pos={}",
                        GuixuZeroPointGeneration.TAG, batch,
                        built == null || built.id == null ? "?" : built.id, r.parallel, r.product,
                        batchDuration, ownDuration < 0L ? "?" : String.valueOf(ownDuration), note,
                        guixu().getPos());
            } else {
                final String candidateNote = shanhai$candidateProbe();
                ShanhaiMod.LOGGER.info("{} 混合轮读数 host=归墟 入批=false 批次=[{}] "
                                + "判定=本批的装配数据里没有发电配方（读数恒 null ⇒ 整轮不结算）{} pos={}",
                        GuixuZeroPointGeneration.TAG, batch, candidateNote, guixu().getPos());
            }
        } catch (Throwable ignored) {
            // 证据行永远不许影响装配（本包同一条纪律）
        }
    }

    /** 上一行「混合轮读数」的形状串（只在形状变化时打）。 */
    private String shanhai$lastBatchMixLog = "";

    /** 候选探针的最小间隔（毫秒）：探针要枚举一遍候选集，别让"每批一行"变成"每批一次全枚举"。 */
    private static final long PROBE_MIN_INTERVAL_MILLIS = 5_000L;

    /** 上一次候选探针的毫秒时间戳（0 = 还没探过）。 */
    private long shanhai$lastProbeMillis;

    /**
     * <b>只读探针</b>：候选集里有没有发电配方、它"本可拿多少并行"（不扣料、不改状态）。
     *
     * <p>{@code lookupRecipeSet()} 是候选集的唯一出口（本类已覆写：发电型会先分料）；
     * {@code getMaxParallel} 只查询持有者、<b>不扣任何东西</b> ⇒ 这是一次纯读数。
     */
    @NotNull
    private String shanhai$candidateProbe() {
        try {
            // 限流：探针要枚举一遍候选集 ⇒ 同一台机器 5 秒内最多探一次（判据行本来也是"变化才打"）
            final long now = System.currentTimeMillis();
            if (now - shanhai$lastProbeMillis < PROBE_MIN_INTERVAL_MILLIS) {
                return "（候选探针 5 秒内已探过，本次省略）";
            }
            shanhai$lastProbeMillis = now;
            final Set<GTRecipe> candidates = lookupRecipeSet();
            GTRecipe gen = null;
            for (final GTRecipe candidate : candidates) {
                if (candidate == null) {
                    continue;
                }
                final String typeId = candidate.recipeType != null
                        ? candidate.recipeType.registryName.toString() : null;
                if (GuixuZeroPointGeneration.shouldConvert(typeId,
                        com.gregtechceu.gtceu.api.recipe.RecipeHelper.getOutputEUt(candidate), true)) {
                    gen = candidate;
                    break;
                }
            }
            if (gen == null) {
                return " 候选里发电配方=无（候选数=" + candidates.size() + "；类型不可用或配方条件不满足）";
            }
            long could = -1L;
            try {
                could = org.gtlcore.gtlcore.api.recipe.IParallelLogic.getMaxParallel(
                        (com.gregtechceu.gtceu.api.capability.recipe.IRecipeCapabilityHolder) guixu(),
                        gen, guixu().perRecipeParallelBudget());
            } catch (Throwable ignored) {
                could = -1L;
            }
            return " 候选里发电配方=" + gen.id + "（候选数=" + candidates.size() + "）该配方本可并行="
                    + (could < 0L ? "?" : String.valueOf(could)) + "（只读探针，未扣料）";
        } catch (Throwable t) {
            return " 候选探针失败=" + t;
        }
    }

    /**
     * 清零之后<b>立刻读回一次</b>并把结果打进日志 —— 判据 ①「引擎拿到的 EU 输出是 0」的现场证据。
     *
     * <p>读回非 0 ⇒ 说明清零没生效（{@code setOutputEUt} 语义变了 / 被别处复原），<b>当场 ERROR</b>，
     * 而不是等到玩家发现"电没进池子"。
     */
    private void shanhai$logTakeOver(@NotNull GTRecipe zeroedCopy,
                                     @NotNull GuixuZeroPointGeneration.Readings r,
                                     @NotNull String where) {
        final long readBack = com.gregtechceu.gtceu.api.recipe.RecipeHelper.getOutputEUt(zeroedCopy);
        if (readBack != 0L) {
            ShanhaiMod.LOGGER.error("{} 归墟清零【失败】：配方 {} 读回 EUt={}（应为 0）落点={} pos={}",
                    GuixuZeroPointGeneration.TAG, zeroedCopy.id, readBack, where, guixu().getPos());
            return;
        }
        if (shanhai$tookOverRecipeId == null || !shanhai$tookOverRecipeId.equals(zeroedCopy.id)) {
            shanhai$tookOverRecipeId = zeroedCopy.id;
            ShanhaiMod.LOGGER.info("{} 归墟已接管发电配方 {}（类型={}）落点={}："
                            + "配方自带输出电={} ⇒ 在【副本】上清零后读回={}（原配方对象未改）；"
                            + "本轮产量口径：{} pos={}",
                    GuixuZeroPointGeneration.TAG, zeroedCopy.id,
                    zeroedCopy.recipeType != null ? zeroedCopy.recipeType.registryName : "?",
                    where, r.baseEut, readBack, r.line("", null), guixu().getPos());
        }
    }
}

