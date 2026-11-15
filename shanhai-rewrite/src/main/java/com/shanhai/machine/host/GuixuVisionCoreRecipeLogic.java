package com.shanhai.machine.host;

import com.gregtechceu.gtceu.api.capability.recipe.IRecipeCapabilityHolder;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;

import com.shanhai.ShanhaiMod;
import com.shanhai.common.heat.ShanhaiExtraMountGate;
import com.shanhai.common.heat.ShanhaiHeatGate;
import com.shanhai.common.machine.PrimordialEngineRecipeLogic;

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
            return built;                       // ← 非目标配方：原实例返回，一个字节都没动
        }
        shanhai$visionReadings = r;
        final com.gregtechceu.gtceu.api.recipe.GTRecipe copy = shanhai$copyPreservingParallels(built);
        com.gregtechceu.gtceu.api.recipe.RecipeHelper.setOutputEUt(copy, 0L);
        shanhai$logTakeOver(copy, r, "buildFinalNormalRecipe");
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
                return GuixuZeroPointGeneration.readingsFromParallelData(baseEut, parallels[i], gateBonus);
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

