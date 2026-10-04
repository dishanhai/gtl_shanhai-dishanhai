package com.shanhai.common.machine;

import com.google.common.primitives.Ints;
import com.gregtechceu.gtceu.api.capability.recipe.IRecipeCapabilityHolder;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.RecipeHelper;
import com.gregtechceu.gtceu.api.recipe.modifier.ParallelLogic;
import com.gtladd.gtladditions.api.machine.logic.MutableRecipesLogic;
import com.gtladd.gtladditions.common.data.ParallelData;
import com.shanhai.ShanhaiMod;
import com.shanhai.common.machine.ParallelOverrideMachine;
import com.shanhai.common.machine.ParallelPowerBudget;
import com.shanhai.common.thread.ShanhaiParallelBudget;
import com.shanhai.common.recipe.PrimordialRecipeEffects;
import com.shanhai.machine.module.PrimordialModuleMachine;

import it.unimi.dsi.fastutil.longs.LongLongPair;

import org.gtlcore.gtlcore.api.recipe.IParallelLogic;
import org.gtlcore.gtlcore.api.recipe.RecipeMultiplierTracker;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 青铜神锻（原始终焉引擎）主机的<b>多配方引擎</b>逻辑 —— {@code MutableRecipesLogic} 的本工程子类。
 *
 * <h2>为什么存在这个类（T1「换引擎」的落点）</h2>
 * 主机的配方修饰链挂在 {@code MachineBuilder.recipeModifier(...)}
 * （{@code PrimordialOmegaEngineMachine#applyHostRecipeModifier}），而
 * <b>gtladditions 的多配方分支完全不经过 {@code RecipeLogic.checkMatchedRecipeAvailable}</b>
 * ⇒ 一旦主机改用本引擎，那条链的<b>唯一应用点就消失了</b>。
 * <b>不迁移的后果是「N3/N4/N5/N6 整条链静默失效、不报错」</b>——本项目最忌讳的失败模式。
 * ⇒ 四项效果的落点曾与 {@code applyHostRecipeModifier} <b>共用同一份算术</b>
 * （{@link PrimordialRecipeEffects#applyHostTailEffects}；A 口径起本类改走 {@code applyHostTailEffectsA}）。
 *
 * <h2>🔴 与旧私货（{@code gt_shanhai}）同形的部分 —— 只抄了"形状"，没抄它的 mixin</h2>
 * 旧私货把 N3/N4/N5/N6 写在<b>逻辑子类</b>里，而不是挂在 {@code recipeModifier} 上：
 * {@code [源码原文]} {@code originals/upstream/gtl_shanhai-dishanhai/.../SelectableRecipeTypeSetRecipeLogic.java}
 * <pre>
 *   protected long getTotalParallelLimit() {
 *       return saturatedMultiply(getMachine().getRecipeLogicMaxParallel(), getLogicThreadMultiplier());   ← 并行 = 主机上限 × 线程倍数
 *   }
 *   protected long getLogicThreadMultiplier() {
 *       return Math.max(1L, (long) getMultipleThreads());                                                  ← :453 用户点名的那一行
 *   }
 *   protected ParallelData calculateParallels() { … }        ← 覆写并行分配
 *   （另一处）buildFinalWirelessRecipe(...)                   ← 覆写"最终配方装配"，效果写在这里
 * </pre>
 * <b>本类照抄的形状 = 「覆写父类负责装配最终配方的那一个方法，把效果写在逻辑子类里」</b>：
 * <ul>
 *   <li>旧私货覆写 {@code buildFinalWirelessRecipe}（它走无线支路）；
 *       <b>本主机走有线支路</b>（见下面「为什么是 Normal 而不是 Wireless」），所以覆写
 *       {@link #buildFinalNormalRecipe(ParallelData)}；</li>
 *   <li>并行倍率这一条<b>不用覆写</b>：父类 {@code MutableRecipesLogic.calculateParallels()}
 *       原文就是 {@code totalParallel = getMaxParallel() × getMultipleThreads()}，
 *       与旧私货 {@code getTotalParallelLimit()} 是同一形状，所以这里只覆写
 *       {@link #getMultipleThreads()} 给出行程倍数。</li>
 * </ul>
 * <b>没有抄的：旧私货的 mixin（{@code ThreadForceOverrideMixin} / {@code MultipleRecipesLogicThreadMixin}）。
 * 本工程不新建 mixin 基础设施。</b>
 *
 * <h2>🔴 为什么是 {@code buildFinalNormalRecipe} 而不是 {@code buildFinalWirelessRecipe}</h2>
 * {@code [源码原文]} {@code MutableRecipesLogic.java}（反编译）:
 * <pre>
 *   protected GTRecipe getRecipe() {
 *      if (!this.checkBeforeWorking()) return null;
 *      ParallelData parallelData = this.calculateParallels();
 *      if (parallelData == null) return null;
 *      IWirelessNetworkEnergyHandler wirelessTrait = this.getMachine().getWirelessNetworkEnergyHandler();
 *      return wirelessTrait != null ? this.buildFinalWirelessRecipe(parallelData, wirelessTrait)
 *                                   : this.buildFinalNormalRecipe(parallelData);      ← 空值有守卫
 *   }
 * </pre>
 * 而 {@code IWirelessElectricMultiblockMachine.getWirelessNetworkEnergyHandler()} 的<b>接口默认实现</b>
 * 就是 {@code return null;}（{@code [源码原文]} 该接口第 17-20 行），本主机<b>没有</b>无线能源 trait、
 * 也<b>没有</b>覆写它 ⇒ <b>永远走 Normal 支路</b>，且<b>不会 NPE</b>。
 * （两个支路互斥：Normal 支路<b>不</b>经 {@code WirelessGTRecipe}，所以我们不能把效果挂在 Wireless 上
 * —— 那会一行都不执行。）
 *
 * <h2>🔴 {@code setUseMultipleRecipes(true)}：不写这一行，「换引擎」等于没换</h2>
 * 父类的三个入口方法都是<b>开关分流</b>：
 * <pre>
 *   public void findAndHandleRecipe() { if (useMultipleRecipes) findAndHandleMultipleRecipe(); else super.findAndHandleRecipe(); }
 *   public void onRecipeFinish()      { if (useMultipleRecipes) onMultipleRecipeFinish();      else super.onRecipeFinish(); }
 *   public void handleRecipeWorking() { if (useMultipleRecipes) handleMultipleRecipeWorking(); else super.handleRecipeWorking(); }
 * </pre>
 * 而 {@code useMultipleRecipes} 的<b>字段初值是 false</b>，全 gtladditions 里只有一个写 true 的地方：
 * {@code [源码原文]} {@code ThreadPartMachine.addedToController(...)}：
 * {@code if (controller is IRecipeLogicMachine) { … if (logic is MutableRecipesLogic) logic.setUseMultipleRecipes(true) }}
 * —— 那是「跨配方线程」<b>部件</b>挂上时触发的，<b>本主机没有那个部件</b>。
 * ⇒ 若不在构造期自己打开，主机会退化成父类的原生 {@code RecipeLogic} 路径
 * （= 与换引擎之前<b>完全一样</b>），<b>但对象已经是 MutableRecipesLogic</b>，
 * 于是 T3（{@code getMaxParallel()} 负哨兵 × {@code getMultipleThreads()} 的裸乘法）也永远不会被触发
 * —— 一个**看起来成功、实际什么都没换**的状态。所以这一行是引擎切换的开关本身。
 *
 * <h2>这里<b>没有</b>覆写的东西（都是刻意留着的，别误判成漏了）</h2>
 * <ul>
 *   <li>{@code getFieldHolder()}：<b>不覆写</b>。本类<b>不声明任何 {@code @Persisted}/{@code @DescSynced} 字段</b>，
 *       所以父类的 holder（{@code MutableRecipesLogic.MANAGED_FIELD_HOLDER}）就是正确的字段集合。
 *       上游同款先例：{@code ForgeOfTheAntichrist$ForgeOfTheAntichristLogic} 同样不覆写它。
 *       （反过来，本类若将来加了同步字段，<b>必须</b>补一个自己的 holder，否则就是"编译过、不报错、字段永不同步"。）</li>
 *   <li>{@code calculateParallels()}：父类实现就是 {@code getMaxParallel() × getMultipleThreads()}
 *       + {@code calculateParallelsWithGreedyAllocation}，正是我们要的形状（见上面「同形」一节）。</li>
 *   <li>{@code checkRecipe()}：父类已含 {@code euTier <= machine.getTier()} 与条件检查；
 *       主机的 {@code getMaxVoltage() = Long.MAX_VALUE} ⇒ {@code tier = 29} ⇒ 电压闸门恒过。</li>
 * </ul>
 *
 * <h2>已知的残余风险（只能实机验证，本轮禁止启动游戏）</h2>
 * ① {@code ParallelData} 的贪心分配<b>不做输出空间钳制</b>（它只调
 * {@code IParallelLogic.getMaxParallel}，不调 {@code getMinParallel}）
 * ⇒ 并行数只由"实际可用的输入物品量"决定。这是<b>引擎自带</b>的行为，本工程不改它
 * （改了就是自造行为，且 gtladditions 自己的机器都跑在这条路径上）。
 * ② {@code buildNormalRecipe} 内部把 duration 定为 {@code round(max(totalEu/maxEUt, 20))}
 * ⇒ 本类随后用 N6 的<b>下限</b>把它重新写成 {@code T = max(1, dx, limitedDuration)}（A 口径，见本类 buildFinalNormalRecipe）。
 *
 * <p>🔴 <b>③ 那一句里的 {@code 20} 是【引擎自己硬编码的字面量】，不是我们的「配方最短耗时」</b>
 * （2026-09-21 追加核实的结论，证据见 {@code [字节码]}）：
 * <pre>
 *   MutableRecipesLogic.buildFinalNormalRecipe 字节码：
 *       457: bipush 20                         ← 字面量 20，直接当 buildNormalRecipe 的第 5 个实参
 *       459: invokevirtual RecipeCalculationHelper.buildNormalRecipe:(Ljava/util/List;Ljava/util/List;DJI)…
 *   且 MutableRecipesLogic 的整个常量池里
 *       getLimitedDuration / setLimitedDuration / LimitedDuration / IGTLAddMultiRecipeMachine 命中全为 0
 *   ⇒ 【引擎根本不认识侧栏那个旋钮】，我们从没把 limitedDuration 传进引擎。
 *   ⚠️ 对照（别混淆）：RecipeCalculationHelper.buildNormalRecipe 的调用点还有
 *      GTLAddMultipleRecipesLogic —— 那一处传的是真值
 *      （字节码 443-446: getfield limited → IGTLAddMultiRecipeMachine.getLimitedDuration() → 该 buildNormalRecipe）。
 *      本工程走的是 MutableRecipesLogic，【不是】那一个类。
 * </pre>
 * <p>⇒ <b>由此带来的副作用：曾经不守恒，已按队长 2026-09-21 裁决（选「甲」）修好</b>
 * <pre>
 *   问题：引擎保证 EUt_引擎 × duration_引擎 ≈ totalEu；而 N6 只把 duration 改成 limitedDuration、
 *        不改 EUt ⇒ 只有 limitedDuration == duration_引擎 时才守恒。
 *        常见支路 dx ≤ 20 下 duration_引擎 = 20 ⇒ 侧栏 ≠ 20 时每轮总耗能 = totalEu × (侧栏/20)
 *        （侧栏 50 ⇒ 2.5 倍；侧栏 200 ⇒ 10 倍）。
 *   修法（甲）：N6 定完时长后按 duration_引擎 / limitedDuration 反缩放 EUt ——
 *        落在 PrimordialRecipeEffects#rescaleEnergyForIdentityDuration，**只在本类调用**
 *        （原生 recipeModifier 链没有"引擎时长"概念，缩了就是错的 ⇒ 共用方法一字未改）。
 *        ⛔ **【2026-09-22 订正 · 旧句照留，两处】**：
 *        ① 方法已改名为 **{@code rescaleEnergyForDuration}**，且**分母由 {@code limitedDuration} 改成
 *           {@code targetDuration}（= T = max(1, dx, L)，A 口径）** —— 因为"N6 恒等"已被 A 口径取代，
 *           {@code T ≠ L} 成为常态，分母仍用 L 会让总能量差 {@code T/L} 倍（静默失效）。
 *        ② 「共用方法一字未改」**已作废** —— 本轮给 {@code applyHostTailEffects} 加了实参，
 *           **两条路径同步演进**，不再是"一边不动"。
 *   ⇒ 现在：EUt × limitedDuration == EUt_引擎 × duration_引擎（**≈ totalEu**，差值只剩
 *        引擎自带的取整：EUt_引擎 = (long)(totalEu/20) 是截断、duration_引擎 = round(dx) 是四舍五入）。
 *   ⚠️ 「默认档逐值一致」的精确形式见 buildFinalNormalRecipe 里的注释 ③：
 *        **只在 duration_引擎 == limitedDuration 时**原样返回；dx > 20 支路（duration_引擎 = dx > 20）
 *        即使侧栏 = 20 也会按 dx/20 > 1 抬高 EUt —— 那正是把守恒补回来的地方，不是 bug。
 * </pre>
 */
public class PrimordialEngineRecipeLogic extends MutableRecipesLogic<PrimordialOmegaEngineMachine> {

    /** 一次性日志闸门：证明"配方真的由本引擎装配过"，而不是靠推断。 */
    private boolean shanhai$engineAnnounced;

    public PrimordialEngineRecipeLogic(PrimordialOmegaEngineMachine machine) {
        super(machine);
        // 🔴 引擎开关本身；理由见类注释（不写这一行 = 换了对象但没换引擎）。
        // setUseMultipleRecipes 是 MutableRecipesLogic 的 public final 方法，可以直接调。
        setUseMultipleRecipes(true);
    }

    /**
     * 跨配方线程数（N4 的倍数项）。
     *
     * <p>父类实现是 {@code getMachine().getAdditionalThread() > 0 ? … : 1}，而
     * {@code PrimordialOmegaEngineMachine.getAdditionalThread()} 返回的正是 {@link PrimordialOmegaEngineMachine#MAX_PARALLEL}。
     *
     * <h2>🔴🔴 2026-09-22 改判：本方法由 {@code MAX_PARALLEL}（2^30）改成 {@code 1}</h2>
     * ⛔ <b>旧实现（作废，原文照留）</b>：
     * <pre>
     *     {@code @Override public int getMultipleThreads() { return PrimordialOmegaEngineMachine.MAX_PARALLEL; }}
     * </pre>
     * <b>作废原因 —— 那条 B×E 裸乘法【真的发生了，不是理论问题】</b>：
     * <pre>
     *   父类：{@code long totalParallel = (long) this.getMachine().getMaxParallel() * this.getMultipleThreads();}
     *   实测取值：{@code getMaxParallel()} = 2^30（{@code MAX_PARALLEL}）
     *             {@code getMultipleThreads()} = 2^30（旧实现）
     *   ⇒ {@code totalParallel} = 2^60 = 1152921504606846976
     * </pre>
     * 这个数<b>大到没有约束力</b> —— 用户规格写的是「并行上限: 2^30（无限挡；**实际并行由输入量与输出空间决定**）」，
     * 而 {@code 2^60} 把一个**本该由输入量/输出空间决定的量**变成了"几乎无限"，
     * 于是引擎的贪心分配<b>只剩输入量一个边界</b>（这正是交付报告里那句「不再受电压 / 输出空间钳制」）。
     *
     * <h2>为什么改成 1 而不是"在 calculateParallels() 里钳一下"</h2>
     * <ul>
     *   <li>父类的乘积里，{@code getMaxParallel()} 是<b>机器侧</b>的值（= 2^30，就是用户规格的"并行上限"，
     *       <b>不能改</b>）；<b>本方法是我们唯一能改、且语义正确的那一半</b>。</li>
     *   <li>改成 1 之后：{@code totalParallel = 2^30 × 1 = 2^30} —— <b>正好等于用户规格的"并行上限 2^30"</b>，
     *       且<b>结构性不可能溢出</b>（一个因子恒为 1）。</li>
     *   <li>🔴 <b>为什么不重写 {@code calculateParallels()}</b>：父类的实现是
     *       {@code calculateParallelsWithGreedyAllocation(recipes, totalParallel, machine, &lt;父类的私有 lambda&gt;)}
     *       —— 那个 lambda 是<b>父类私有</b>的，自己重写就必须把上游的"查表 + 贪心分配 + 输入扣减"
     *       整套抄一遍 ⇒ <b>抄出来的第二份实现正是本工程最忌讳的漂移源</b>。
     *       ⇒ <b>只改一个乘法因子，不碰上游流程。</b></li>
     * </ul>
     *
     * <h2>⚠️ 诚实边界：本方法只解决"上限没有约束力"，【没有】补回"输出空间钳制"</h2>
     * ⛔⛔ <b>【2026-09-22 订正 · 上面这个标题与下面那段"仍是待办"已作废，原文照留（勿删）】</b>
     * <p>🔴 <b>现况：「输出空间钳制」已经实现（不是待办）</b> —— 落在本类的
     * {@code protected LongLongPair calculateParallel(IRecipeLogicMachine, GTRecipe, long)} 覆写里
     * （见本文件下方的「🔴 输出空间钳制（2026-09-22 新增）」一节）：它调用
     * {@code ParallelLogic.limitByOutputMerging(...)} 一次拿到①输出空间上限②饱和，
     * 且 gtlcore 的 {@code ParallelLogicMixin} 已覆写该方法 —— 即"旧路径按输出空间钳制"的本体。
     * ⇒ <b>本方法（{@code getMultipleThreads()}）现在的职责只是"线程数照伪神填 128"；
     * 真正让并行数收敛的是 {@code calculateParallel(...)} 的输出空间钳制 + 输入量边界。</b>
     * <p>⚠️ <b>为什么会留这处矛盾</b>：标题与下面那段写于"先只把上限修回 2^30"的那半轮，
     * {@code calculateParallel} 覆写是同一轮稍后补上的；<b>旧句当时为真、现在是错的</b>，
     * 按本工程「作废项一律保留原文 + 注明」的规矩，<b>不改写、只追加本订正</b>。
     * <p>⛔ <b>【以下为 2026-09-22 订正之前的原文，逐字保留、不再生效】</b>：
     * <b>已查到的现成入口</b>（不是自己发明）：
     * {@code com.gregtechceu.gtceu.api.recipe.modifier.ParallelLogic.limitByOutputMerging(
     * GTRecipe, IRecipeCapabilityHolder, int, Predicate&lt;RecipeCapability&lt;?&gt;&gt;)}，
     * 且 gtlcore 用 {@code ParallelLogicMixin} 覆写了它 —— <b>那就是旧路径"按输出空间钳制"的本体</b>。
     * <p>🔴 <b>我没有把它接进来</b>（见交付报告）：接线需要先确认那个 {@code Predicate} 的实参，
     * 而<b>猜错谓词 = 静默改变行为</b>（本工程最忌）。⇒ <b>本方法交付的是"上限回归 2^30"，
     * "输出空间钳制"仍是待办</b>，并由 {@link #getRecipe()} 的探针在下次实机时判定它是否就是真凶。
     * <p>🔴 <b>【订正原文结束】</b>
     *
     * <p>🔴 与 {@code getMaxParallel()} 一起构成父类的
     * {@code totalParallel = getMaxParallel() × getMultipleThreads()} ⇒ 现在恒为 {@code 2^30}。
     */
    @Override
    public int getMultipleThreads() {
        // ⛔⛔ 【2026-09-22 作废，原文留档】旧实现 → 新实现（用户裁决「照伪神填 + 显示真实数值」）
        //   ⛔ 旧原文（作废）：
        //       // 🔴 1 = 不放大：让总并行预算 = 机器上限本身（2^30），恢复用户规格「并行上限 2^30」。
        //       //    旧值 PrimordialOmegaEngineMachine.MAX_PARALLEL（2^30）已作废，理由见本方法 javadoc。
        //       return 1;
        //   ⛔ 作废原因：用户裁决**照伪神（FOTC）填写**线程数。伪神祖先 GTLAddMultipleRecipesLogic 的
        //      原文就是 `Ints.saturatedCast(128L + getAdditionalThread())`；
        //      而**前提也要一起照**（只抄公式不抄前提会得到 1073741952，那不是伪神的值）⇒
        //      `PrimordialOmegaEngineMachine.getAdditionalThread()` 已同步回上游默认值 `0`
        //      ⇒ 本式结果 = **128**（与伪神一致）。
        //   ✅ 乘积饱和：本式的返回值是 int，父类 `totalParallel = (long) getMaxParallel() * getMultipleThreads()`
        //      是 long×int ⇒ **不会回绕**；{@code Ints.saturatedCast} 保证"128 + 附加"本身不溢出。
        //      2^31-1 × 128 ≈ 2.75e11 < Long.MAX_VALUE ⇒ **本处不需要额外的 Math.max(0L, …)**
        //      （真正让并行数收敛的是本类的 `calculateParallel(...)` 输出空间钳制 + 输入量边界）。
        //   🔴 2026-10-02 第五轮：本式【搬进纯核】—— 现在有两个读者必须在同一 tick 内逐位同值：
        //      ① 本方法（父类拿它乘预算）；② PrimordialOmegaEngineMachine#getEnergyCapThreads()
        //      （接口层拿它做「电上限 ÷ T」）。两处各写一遍 ⇒ 预算 = min(本机上限, 电上限÷T₁) × T₂，
        //      T₂ > T₁ 时会 > 电上限 ⇒ 总耗电超 P。⇒ 唯一实现是
        //      ShanhaiParallelBudget#crossRecipeThreadsForHost(int, int)。
        //      数值【逐位不变】：Ints.saturatedCast(128L + x) 与纯核那支 saturatedCast 在 int 域内同值
        //      （纯核不用 Guava 是因为它必须能被一个裸 javac 编译，见那个类的注释）。
        return ShanhaiParallelBudget.crossRecipeThreadsForHost(
                BASE_THREADS, getMachine().getAdditionalThread());
    }

    /**
     * <b>主机的跨配方线程基数</b>（照伪神 FOTC 填的 128）。
     *
     * <p>🔴 <b>2026-09-25（任务 A）：从 {@link #getMultipleThreads()} 里那句 {@code 128L} 提出来</b>，
     * 唯一目的是让主机的<b>物品 tooltip</b>（{@code ShanhaiMachines} 的 {@code .tooltips(...)}）
     * 与<b>真正参与运算</b>的那个数同源。
     * <p>不改行为：{@code 128L + x} 与 {@code (long) BASE_THREADS + x} 在 {@code BASE_THREADS == 128} 时
     * 逐位等价（{@code Ints.saturatedCast} 的入参类型仍是 {@code long}）。
     * <p>提出来的理由是本工程的一条硬账：**两处各自硬编码同一个数，迟早会漂移**，
     * 而漂移的表现是"tooltip 说 128、Jade 说别的"——正好落在「活的界面上不许放假数据」这条红线上。
     */
    public static final int BASE_THREADS = 128;

    /**
     * 🔴 <b>2026-09-26：把主机的并行预算接回 long（用户原话「把主机的并行也改成 long.max」）。</b>
     *
     * <h2>病根</h2>
     * 父类 {@code MutableRecipesLogic.calculateParallels()} 的预算是
     * {@code (long) getMaxParallel() * getMultipleThreads()}，第一个因子是
     * <b>gtlcore {@code ParallelMachine} 的 int 方法</b>
     * ⇒ 主机永远拿不到 {@link PrimordialOmegaEngineMachine#MAX_PARALLEL}（{@code Long.MAX_VALUE}）。
     *
     * <h2>修法（与模块侧同一条纪律，逐条对齐）</h2>
     * <pre>
     *   ① 预算 = saturatedMultiply(getMachine().getRecipeLogicMaxParallel(), getMultipleThreads())
     *      —— {@code Long.MAX × 128 ⇒ Long.MAX}（饱和，不回绕）；
     *   ② 分配算法复用上游那一份的 long 版（{@link PrimordialRecipeEffects#greedyAllocateWithLongLimit}）；
     *   ③ 🔴 <b>预算 ≤ Integer.MAX_VALUE 时原样走父类</b>（那条路上的字节码逐字不变）。
     * </pre>
     *
     * <h2>⚠️ 诚实边界：预算变大【不】等于"实际并行变大"</h2>
     * 主机的 {@link #calculateParallel} 覆写里还压着一条 <b>int 形状的输出空间钳制</b>
     * （{@code ParallelLogic.limitByOutputMerging} 是 gtceu 的 int API，gtlcore 的
     * {@code ParallelLogicMixin} 只 {@code @Overwrite} 了实现、没改签名）
     * ⇒ 主机<b>实际分配出去的并行仍然 ≤ 2147483647</b>，与改动前一致。
     * 本改动让"引擎看到的并行上限"变成 {@code Long.MAX_VALUE}（用户的字面要求），
     * <b>没有</b>让主机实际跑出 &gt; 21 亿 的并行 —— 那是另一件事（要改
     * {@code calculateParallel} 的输出钳制，见交付报告的"没做到"一节）。
     */
    @Override
    protected @Nullable ParallelData calculateParallels() {
        // 🔴 2026-10-02 第十轮：「本轮」= 「同一轮两次取候选是否同集合」探针的【观察窗】
        //    （与模块侧 PrimordialModuleRecipeLogic#calculateParallels() 逐字同源）。
        //    主机侧同样会取候选两次：① shanhai$updatePowerParallelCap() 算电上限时一次；
        //    ② 分配并行时一次（本方法自己调的那一句 / 父类 MutableRecipesLogic#calculateParallels()
        //      里那一句 invokevirtual lookupRecipeSet()）。
        //    ⚠️ 主机侧的候选集【不截断】（= 该配方类型下全部可跑配方，可能几百条）⇒
        //       "两次是不是同一个集合"在这里更值得盯：一次顺序漂移就能让 k最贵 漏掉最贵那条。
        //    🔴 2026-10-02 第十一轮：finally 里顺便把【累计值心跳】打到日志
        //       （首次必打、之后每 5 分钟最多一行，与模块侧共用同一个间隔计时器 ⇒ 全局
        //       每 5 分钟最多一行，不会因为"两侧各打一行"翻倍）。
        CandidateSetConsistency.beginRound();
        try {
            return shanhai$calculateParallelsInRound();
        } finally {
            CandidateSetConsistency.endRound(ShanhaiMod.LOGGER::info);
        }
    }

    /** {@link #calculateParallels()} 的本体；拆出来只为让观察窗用一个 try/finally 包住全部 return 口。 */
    private @Nullable ParallelData shanhai$calculateParallelsInRound() {
        final PrimordialOmegaEngineMachine host = getMachine();
        // 🔴 2026-10-02「电力自动」：先算并写回电力上限，再读预算 ——
        //    host.getRecipeLogicMaxParallel() → getEffectiveParallel() → applyEnergyCap() 读的是它。
        //    开关关着时本调用【立刻返回且一次 lookup 都不做】⇒ 与改动前同开销、同结果。
        shanhai$updatePowerParallelCap();
        // 🔴🔴 2026-10-02 第五轮（用户裁决 ①「改，让它真生效」）：那个 ÷T 已经【不靠这一行生效了】。
        //    ÷T 落在【接口层】= ParallelOverrideMachine#applyEnergyCap → getMaxParallel()，
        //    而下面那句 `limit <= Integer.MAX_VALUE ⇒ return super.calculateParallels()` 里，
        //    父类【读的就是 getMaxParallel()】（字节码原文：checkcast ParallelMachine;
        //    invokeinterface getMaxParallel:()I; i2l; getMultipleThreads:()i2l; lmul;
        //    见 temp/autoparallel-fix4/javap-MutableRecipesLogic.txt）⇒
        //    **"退回父类"不但没关系，它算出来的正是我们要的值**：
        //       父类预算 = min(本机上限, floor(电上限÷T)) × T ≤ 电上限 = P ÷ k ⇒ 总耗电 ≤ P。
        //    ⛔ 上一轮那句「≤ 2^31 时走 super ⇒ 那条路上"电上限 ÷ T"尚未生效，待裁」【已作废】。
        //    ⚠️ 本行的 limit 与父类那条路【现在逐位同值】（都是 每线程上限 × T）——
        //       改动前两者差 T 倍（本轮修掉的正是这件事）。判据断言这条恒等式。
        // 🔴 2026-10-02 第十一轮（用户实机 bug）：入参从 getEnergyParallel() 改成 energyCapForBudget()
        //    —— 与模块侧逐字同源：能源仓那头无限（创造模式能源仓 / 无线电网输入终端）时返回
        //    ENERGY_CAP_NONE（= 不限制）⇒ 本方法退回「本机上限 × T」，与「电力自动关着」逐位同值。
        final long limit = ShanhaiParallelBudget.parallelBudget(
                host.getRecipeLogicMaxParallel(), host.energyCapForBudget(), getMultipleThreads());
        if (limit <= (long) Integer.MAX_VALUE) {
            return super.calculateParallels();
        }
        return PrimordialRecipeEffects.greedyAllocateWithLongLimit(
                lookupRecipeSet(), limit, host,
                (recipe, remain) -> calculateParallel(host, recipe, remain));
    }

    /**
     * 🔴 <b>覆写只为【观测】—— 返回值逐字就是父类的返回值</b>（2026-10-02 第十轮）。
     *
     * <p>为什么要有这个覆写：运行期一致性探针（{@link CandidateSetConsistency}）必须看到
     * <b>每一次</b>取候选的结果，而主机的候选集出口原本只有父类
     * {@code MutableRecipesLogic#lookupRecipeSet()}。覆写之后，
     * {@code shanhai$updatePowerParallelCap()} 那次调用、本类自己那次调用、以及父类
     * {@code calculateParallels()} 内部那一句 {@code invokevirtual lookupRecipeSet()}
     * <b>全部</b>汇到这里 —— 于是"同一轮两次取候选是不是同一个集合"才有得比。
     *
     * <p>⚠️ <b>与模块侧那份覆写的关键差别</b>：模块侧会<b>截断</b>（{@code ≤ threads} 条），
     * 本类<b>一条都不动</b>（主机不截断，见 {@code shanhai$updatePowerParallelCap()} 的注释）
     * ⇒ 这里只有"记录 + 原样返回"，没有任何"改内容"的可能。
     * 观察窗之外（{@code isRoundActive() == false}）连字符串都不建，开销为一次布尔读。
     */
    @Override
    protected @NotNull Set<GTRecipe> lookupRecipeSet() {
        final Set<GTRecipe> all = super.lookupRecipeSet();
        if (CandidateSetConsistency.isRoundActive()) {
            final List<String> ids = new ArrayList<>(all.size());
            for (GTRecipe candidate : all) {
                ids.add(String.valueOf(candidate.id));
            }
            CandidateSetConsistency.observe("主机", String.valueOf(getMachine().getDefinition().getId()), ids,
                    ShanhaiMod.LOGGER::warn);
        }
        return all;
    }

    // ═════════════════════ 🔴 电力自动（2026-10-02 新增 · 用户定方案） ═════════════════════

    /**
     * 🔴 <b>按「能源仓总功率 ÷ 每并行耗电」算本轮的能量上限，写回主机。</b>
     *
     * <p>算式、口径、时机与模块侧 {@code PrimordialModuleRecipeLogic#shanhai$updatePowerParallelCap()}
     * <b>逐条同源</b>（同一个 {@link ParallelPowerBudget}、同一个 {@link EnergyHatchPower}）——
     * 两侧只有"读哪个门控等级"这一处不同，那里各自与自己的 {@code buildFinal…} 同源。
     *
     * <p>⚠️ 主机侧的 {@code getEuMultiplier()} 与模块侧同源（都来自 {@code MutableRecipesLogic}），
     * 而 N5 系数取的是 {@code host.moduleSlotBonus()}（主机自己就是门控的持有者，没有"上游主机"这一步）。
     *
     * @return 本轮的能量上限；{@code 0} = 没有上限
     */
    private long shanhai$updatePowerParallelCap() {
        final PrimordialOmegaEngineMachine host = getMachine();
        if (!host.isPowerAutoParallel()) {
            return ParallelOverrideMachine.ENERGY_CAP_NONE;
        }
        try {
            final java.util.Set<GTRecipe> candidates = lookupRecipeSet();
            final long ceiling = host.getParallelOverrideCeiling();
            if (candidates.isEmpty()) {
                // 🔴 2026-10-02 第二轮：不再写一个裸的"没有上限"，而是把**原因**一起写下去
                //    （理由与模块侧那一处逐字相同，见 ParallelOverrideMachine.EnergyCapState 的注释）。
                host.setEnergyCap(ParallelOverrideMachine.EnergyCapState.NO_CANDIDATE,
                        ParallelOverrideMachine.ENERGY_CAP_NONE,
                        ParallelOverrideMachine.ENERGY_CAP_NONE);
                shanhai$logCapSkipped(host, "NO_CANDIDATE", "本轮没有可跑的候选配方");
                return ParallelOverrideMachine.ENERGY_CAP_NONE;
            }
            final double engineMultiplier = getEuMultiplier();
            final double n5Factor = PrimordialRecipeEffects.reductionFactor(host.moduleSlotBonus());
            long worstCostMilli = 0L;
            long firstEut = 0L;
            long worstEut = 0L;
            for (GTRecipe candidate : candidates) {
                final long eut = RecipeHelper.getInputEUt(candidate);
                if (firstEut == 0L) {
                    firstEut = eut;
                }
                // 🔴 2026-10-02 第七轮：改成【毫 EU/t】口径（不再中途 round）。
                //    旧写法 `Math.round(42 × 0.05) = 2` 就是用户那台「整机 688.13K > 仓 655,360」的真凶，
                //    理由与算式逐条见 ParallelPowerBudget#perParallelMilliCost 的 javadoc。
                // 🔴🔴 2026-10-02 第九轮（用户点名：「等一下，不同配方耗电是不同的，你不会取静态的数值了吧」）：
                //    候选【多条】时 k 取【最贵的那一条】—— 与模块侧逐字同源，唯一实现是
                //    ParallelPowerBudget#worstPerParallelMilliCost（本处不再自己写一遍 Math.max）。
                //    ⚠️ 主机侧的候选集【不截断】：上游 MutableRecipesLogic.lookupRecipeSet() 给的是
                //    这一配方类型下【全部】可跑配方 ⇒ 这里的 max 是"全部候选里最贵的一条"，
                //    比"这一轮真被摊到的那几条"更保守（只会少给并行，不会超功率）。
                //    ⚠️ worstEut 只给日志用（argmax 的可读性），【不参与任何算术】。
                final long previousWorst = worstCostMilli;
                worstCostMilli = ParallelPowerBudget.worstPerParallelMilliCost(
                        previousWorst, eut, engineMultiplier, n5Factor);
                if (worstCostMilli > previousWorst) {
                    worstEut = eut;
                }
            }
            final long totalPower = EnergyHatchPower.totalSteadyPowerPerTick(host);
            final long cap = ParallelPowerBudget.parallelFromPowerMilli(totalPower, worstCostMilli, ceiling);
            // 状态与数值【一次写下去】；判据 = ParallelOverrideMachine.classify（纯函数、唯一一份）。
            final ParallelOverrideMachine.EnergyCapState state =
                    ParallelOverrideMachine.classify(true, totalPower);
            host.setEnergyCap(state, cap, worstCostMilli);
            // 🔴 2026-10-02 第八轮修：这里上一版写的是 `worstCost`（残留的旧变量名，上一轮改名时的漏网）
            //    ⇒ **真实 javac 报"找不到符号"**（语义编译判据当场抓到）。本文件的编译期一直是坏的。
            shanhai$logPowerParallelCap(host, totalPower, firstEut, worstEut, worstCostMilli, cap, ceiling,
                    engineMultiplier, n5Factor, candidates.size());
            return cap;
        } catch (Throwable t) {
            // ⛔ 2026-10-02 第二轮订正：上一轮这里只打 `t.toString()` ⇒ 栈丢了、"为什么算不出"查不出来。
            ShanhaiMod.LOGGER.warn("[SHANHAI-POWER-PARALLEL] 主机电力上限计算失败，本轮不做电力限制"
                    + "（完整栈如下；状态已写成 ERROR，面板会显示「电上限异常」）：", t);
            host.setEnergyCap(ParallelOverrideMachine.EnergyCapState.ERROR,
                    ParallelOverrideMachine.ENERGY_CAP_NONE,
                    ParallelOverrideMachine.ENERGY_CAP_NONE);
            return ParallelOverrideMachine.ENERGY_CAP_NONE;
        }
    }

    /** 🔴 主机侧「这一轮为什么没算出电上限」的可 grep 证据行（同形只打一次）。理由同模块侧那一处。 */
    private static final java.util.Set<String> shanhai$capSkippedLogged =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private void shanhai$logCapSkipped(PrimordialOmegaEngineMachine host, String code, String why) {
        if (shanhai$capSkippedLogged.size() > 256) {
            return;
        }
        final String signature = code + "|" + why + "|" + host.getDefinition().getId();
        if (!shanhai$capSkippedLogged.add(signature)) {
            return;
        }
        ShanhaiMod.LOGGER.info("[SHANHAI-POWER-PARALLEL] 主机「{}」（{}）：本轮**没有算出**电力上限（{}：{}）"
                        + " ⇒ 本轮不做电力限制（并行完全按原本口径：玩家填的上限 / MAX_PARALLEL）。",
                host.getDefinition().getId(), host.getPos(), code, why);
    }

    /** 已报过的「电力上限」签名 —— 同形只报一次（与模块侧、以及本项目既有几处探针同一条纪律）。 */
    private static final java.util.Set<String> shanhai$powerParallelLogged =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 可 grep 的实机判据行（字段与模块侧同一批，方便两侧直接对比）。 */
    private void shanhai$logPowerParallelCap(PrimordialOmegaEngineMachine host, long totalPower, long firstEut,
                                             long worstEut, long worstCostMilli, long cap, long ceiling,
                                             double engineMultiplier, double n5Factor, int candidateCount) {
        final String kindSummary = EnergyHatchPower.kindSummary(host);
        final String signature = totalPower + "|" + worstCostMilli + "|" + cap + "|" + kindSummary;
        if (!shanhai$powerParallelLogged.add(signature)) {
            return;
        }
        // 🔴 2026-10-02 第九轮：措辞与模块侧逐字同源 —— 参与运算的是【最贵】那条，不是印在前面的首条。
        //    （用户原话：「等一下，不同配方耗电是不同的，你不会取静态的数值了吧」）
        ShanhaiMod.LOGGER.info("[SHANHAI-POWER-PARALLEL] 主机「{}」：{} ⇒ 生效并行上限 {}；"
                        + "（候选 {} 条：最贵 {} EU/t ⇒ 引擎耗能乘数 {} × N5 减免 {} ⇒ 每并行 {} EU/t"
                        + "【进算式的是最贵那条；首条 {} EU/t，候选只有一条时两个数相同】）"
                        + "【本行已改为「同一形态只打一次」】",
                host.getDefinition().getId(),
                ParallelPowerBudget.describe(totalPower, EnergyHatchPower.hatchCount(host), kindSummary,
                        worstCostMilli, cap, ceiling),
                cap, candidateCount, worstEut, engineMultiplier, n5Factor,
                ParallelPowerBudget.formatMilli(worstCostMilli), firstEut);
    }

    /**
     * <b>主机侧效果的唯一落点</b>（多配方引擎路径）。
     *
     * <p>顺序与 {@code applyHostRecipeModifier} 一致，但 <b>N6 的口径不同</b>（本路径 = A 下限，那条链 = min 天花板）：
     * 并行（已在 {@code calculateParallels()} 里做完，输入也已在贪心分配时被扣掉）
     * → N3 产出倍率 → N6 时长下限（A 口径 T = max(1, dx, L)）→ N5 耗电减免，其中 N3/N6/N5 三者与绊线由
     * {@link PrimordialRecipeEffects#applyHostTailEffectsA} 一份算术承担（旧 {@code applyHostTailEffects} 是 min 口径，本路径不用）。
     *
     * <p>⚠️ 与修饰链路径的<b>唯一</b>差别：本路径的"链入口快照"是<b>引擎装配出来的配方</b>的
     * duration（引擎自己会按能量算一个），而不是配方定义原值。绊线的语义因此精确表述为
     * 「<b>从本工程接手那一刻起，除了 N6 没有任何步骤改动 duration</b>」——
     * 引擎自己那一次赋值发生在接手之前，不在绊线的覆盖范围内（这一点必须如实写在报告里，
     * 不许说成"整个引擎都被绊线覆盖"）。
     *
     * <p><b>为什么调 {@code super} 而不是自己重写整个装配</b>：父类的这个方法<b>在装配过程中
     * 就扣掉了输入</b>（贪心分配里的 {@code handleRecipeInput}）并处理概率掷骰，
     * 重写它等于把上游的输入/输出语义整套抄一遍 —— 抄出来的第二份实现正是本工程最忌讳的漂移源。
     * 所以本类只做「拿到成品配方 → 施加四项效果」这一步。
     *
     * @return 引擎未装配出配方时返回 {@code null}（父类会自行写 {@code RecipeResult.FAIL_FIND}）
     */
    // ═══════════ 🔴 输出空间钳制（2026-09-22 新增 · 补回用户规格的"输出空间"边界） ═══════════

    /**
     * 🔴 <b>覆写单数版 {@code calculateParallel}：给并行数补回"输出空间"上限。</b>
     *
     * <h2>为什么必须补（用户规格原文）</h2>
     * 主机抬头写着：<b>「并行上限: 2^30（无限挡；实际并行由输入量与输出空间决定）」</b>
     * —— <b>「输入量与输出空间」是用户定死的语义</b>。
     * 但新引擎（gtladditions `MutableRecipesLogic`）把"输出空间"这一半丢了：
     * <pre>
     *   RecipeCalculationHelper 全类实测命中：limitByOutputMerging = 0
     *                                            getMinParallel         = 0
     *                                            saturatedCast          = 0
     *                                            getMaxParallel         = 2（唯一来源）
     * </pre>
     * ⇒ 引擎的贪心分配<b>只看输入量</b>。（旧路径靠 gtceu `ParallelLogic` 走了输出空间那一层。）
     *
     * <h2>谓词照抄自上游（不是我发明的）</h2>
     * {@code javap -v ParallelLogic} 的 {@code BootstrapMethods #1} 原文：
     * <pre>
     *   Method arguments:
     *     #232 invokeinterface com/gregtechceu/gtceu/api/machine/feature/IVoidable
     *                            .canVoidRecipeOutputs:(Lcom/gregtechceu/gtceu/api/capability/recipe/RecipeCapability;)Z
     * </pre>
     * ⇒ 上游（{@code ParallelLogic.doParallelRecipes}）传的谓词就是
     * <b>{@code machine::canVoidRecipeOutputs}</b>（捕获 {@code IRecipeLogicMachine} 的<b>绑定方法引用</b>）。
     * 本方法<b>原样照抄该谓词</b>。
     *
     * <h2>为什么调 {@code ParallelLogic.limitByOutputMerging} 而不是自己算</h2>
     * 它是<b>上游现成入口</b>，而且 <b>gtlcore 用 {@code ParallelLogicMixin} 覆写了它</b>：
     * <pre>
     *   原文： min(传入上限, IParallelLogic.getMinParallel(holder, recipe, 传入上限)) → Ints.saturatedCast
     * </pre>
     * ⇒ 一次调用<b>同时得到</b>：① <b>输出空间钳制</b> ② <b>饱和</b>（{@code Ints.saturatedCast}，
     * 正是 {@code 2^60} 那个裸乘法缺的正解形态）。
     *
     * <h2>为什么只改这一处</h2>
     * 父类 {@code calculateParallel} 是 {@code protected}、非 final（L389-397）⇒ <b>是官方留的接缝</b>。
     * 上游 {@code applyParallel} 的接线点在 {@code doParallelRecipes} 里 —— 我们复刻的正是<b>同一层</b>。
     * <p>🔴 <b>本方法不触碰</b> {@code buildFinalNormalRecipe} / {@code applyHostTailEffects} /
     * {@code createRecipeLogic} ⇒ <b>N3/N4/N5/N6 仍在新引擎路径上</b>。
     */
    @Override
    protected LongLongPair calculateParallel(IRecipeLogicMachine machine, GTRecipe match, long remain) {
        // 🔴 2026-09-27 第二轮：原先这里包了一层 PrimordialRecipeEffects.scaleParallelForBatch(...)
        //    （批处理把并行份数 ×N）。该方法已【整体删除】—— 它做出来的是"并行"而不是"批处理"：
        //    multipleRecipe 会把 tickInputs(EU/t) 一起乘 N（⇒ 耗电 ×N），而耗时被模块 N6 钉回原值。
        //    主机侧本来就走不到那一支（BatchProcessing.isEnabled(self) 恒为 false：本类三道覆写全返回 false，
        //    见 canConfigureBatchProcessing() 的 javadoc，那是用户 2026-09-22 的裁决；而 batchMultiplier
        //    的第一句就是 `if (!BatchProcessing.isEnabled(self)) return 1;`）⇒ 删前删后【逐值相同】。
        //    新批处理（连跑 N 次：输入×N／输出×N／耗时×N／EU/t 不变）只在【模块侧】施加
        //    （PrimordialModuleRecipeLogic#buildFinalNormalRecipe ⑥），主机侧一个字节的行为都没变。
        return shanhai$calculateParallelDirect(machine, match, remain);
    }

    /** 上面那一行的原实现（未接批处理倍率的那一份）。 */
    private LongLongPair shanhai$calculateParallelDirect(IRecipeLogicMachine machine, GTRecipe match, long remain) {
        final LongLongPair base = super.calculateParallel(machine, match, remain);
        try {
            final long inputLimited = base.firstLong();
            if (inputLimited <= 0L) {
                return base; // 输入量本来就是 0 ⇒ 原样返回，不做任何加工
            }
            // 🔴 谓词照抄上游：machine::canVoidRecipeOutputs
            final int outputLimited = ParallelLogic.limitByOutputMerging(
                    match,
                    (IRecipeCapabilityHolder) machine,
                    Ints.saturatedCast(inputLimited), // ← 饱和：2^60 量级在这里被压回 int 安全区
                    machine::canVoidRecipeOutputs);
            final long finalParallel = Math.max(0L, Math.min(inputLimited, (long) outputLimited));
            return LongLongPair.of(finalParallel, finalParallel);
        } catch (Throwable t) {
            // 钳制失败时退回"只按输入量"（= 上游新引擎的原行为），绝不让本方法把配方打死。
            ShanhaiMod.LOGGER.warn("[SHANHAI-ENGINE] limitByOutputMerging 失败，退回输入量并行：{}", t.toString());
            return base;
        }
    }

    // ═══════════ 🔴 闸门诊断（2026-09-22 新增 · 用户报「主机完全不工作」时装的探针） ═══════════

    /** 诊断日志：只在"这一次的判定结果与上一次不同"时打（状态变化才打，防刷屏）。 */
    private int shanhai$lastGateCode = Integer.MIN_VALUE;

    /**
     * 🔴 <b>探针：覆写父类的 {@code getRecipe()}，把「闸门结论」记进日志。</b>
     *
     * <h2>为什么装这个（用户报「主机直接无法工作」）</h2>
     * 新引擎的开工路径有四道闸门（反编译原文见类注释 N4 一节）：
     * <pre>
     *   闸①  {@code checkBeforeWorking()}          —— hasProxies() && getOverclockVoltage() > 0
     *   闸②  {@code calculateParallels()}          —— 候选配方集合 → 贪心分配
     *   闸③  {@code RecipeRunnerHelper.matchRecipeOutput(machine, match)}   ← findAndHandleMultipleRecipe 里
     *   闸④  {@code setupRecipe(match)}            —— 真正开工
     * </pre>
     * 本方法<b>只插在闸①之前</b>（它就是父类 {@code getRecipe()} 的外壳），所以它只能回答
     * **「闸①② 过没过」**；但它正是**最有分辨力的那一个观测点**：
     * <pre>
     *   getRecipe() 返回 null  ⇒ 卡在闸①或闸②（再往下看 diagnostic 里的 overclock/maxParallel）
     *   getRecipe() 返回非 null 但机器不动 ⇒ **闸③ 是真凶**（输出空间装不下），
     *                                       或闸④ 之后（输入扣减/能量不足）
     * </pre>
     * ⇒ <b>用户下一次一测，日志就能把范围从"四道闸门"缩到"一道"</b>，不必再来一轮。
     *
     * <h2>为什么返回 {@code super.getRecipe()} 而不是自己算</h2>
     * 本方法<b>不改变任何行为</b>：拿到父类结果 → 打日志 → 原样返回。
     * 任何重写装配逻辑的做法都会造出第二份实现（本工程最忌讳的漂移源）。
     */
    @Override
    @Nullable
    protected GTRecipe getRecipe() {
        final GTRecipe matched = super.getRecipe();
        try {
            // 复算本引擎的并行预算（只用于诊断口径一致）。
            // 🔴 2026-09-26：改成 long 通道的那一份算式 —— 与 calculateParallels() 同源同值，
            //    否则这一行会在 long 档上打出【和真预算不一样的数】（诊断撒谎比没有诊断更糟）。
            // 🔴 2026-10-02 第五轮：这两个数【现在必须逐位相等】——
            //    `budget` = 每线程上限 × T；`parentBudget` = 父类的 (long) getMaxParallel() × getMultipleThreads()。
            //    改动前两者差 T 倍（那正是"退回父类就不生效"的病根）。这一行现在是**实机的自校验**：
            //    玩家贴出这一行，若两者不等就说明 T 的两个来源漂移了。
            // 🔴 2026-10-02 第十一轮：本行是【诊断】，必须与生产路径读同一个口径 —— 入参与
            //    shanhai$calculateParallelsInRound() 一样改成 energyCapForBudget()，
            //    否则「本核预算 == 父类预算」这条自校验会在 ∞ 档上打出假红（诊断撒谎比没有诊断更糟）。
            final long budget = ShanhaiParallelBudget.parallelBudget(
                    getMachine().getRecipeLogicMaxParallel(), getMachine().energyCapForBudget(),
                    getMultipleThreads());
            final long parentBudget = (long) getMachine().getMaxParallel() * (long) getMultipleThreads();
            // 状态码：0 = 有配方；1 = 无配方(match == null)
            final int code = matched == null ? 1 : 0;
            if (code != shanhai$lastGateCode) {
                shanhai$lastGateCode = code;
                ShanhaiMod.LOGGER.info("[SHANHAI-GATE] getRecipe() = {} ; 判定 = {} ; "
                                + "每线程上限(int桥 getMaxParallel)={} ; 跨配方线程 T={} ⇒ "
                                + "本核预算={} ; 父类预算=(long)getMaxParallel()×T={} ; 两者相等={} ; 已成型={}",
                        matched == null ? "null" : "非 null（" + matched.duration + " tick）",
                        matched == null
                                ? "卡在闸①/闸②（checkBeforeWorking 或 calculateParallels）"
                                : "闸①②已过 ⇒ 若机器仍不动，真凶是闸③ matchRecipeOutput（输出空间装不下）或其后",
                        getMachine().getMaxParallel(),
                        getMultipleThreads(),
                        budget,
                        parentBudget,
                        budget == parentBudget,
                        getMachine().isFormed());
            }
        } catch (Throwable ignored) {
            // 纯诊断：任何异常都不允许影响配方装配本身。
        }
        return matched;
    }


    @Override
    @Nullable
    protected GTRecipe buildFinalNormalRecipe(ParallelData parallelData) {
        final GTRecipe built = super.buildFinalNormalRecipe(parallelData);
        if (built == null) {
            return null;
        }
        final PrimordialOmegaEngineMachine host = getMachine();
        // 门控只读一次：N3 的倍率与 N5 的成本系数必须同源（与修饰链那条注释同一条纪律）。
        final int gateBonus = host.moduleSlotBonus();
        final int limitedDuration = host.getLimitedDuration();

        // 🔴 守恒反缩放（队长 2026-09-21 裁决选（甲））—— **只在引擎路径做**：
        //   ① engineDuration 必须在 N6 改写 duration **之前**取 —— 就是 super 刚装配出来的这个值；
        //   ② 顺序 = 引擎装配 → 【本步反缩放】 → N3 → N6(定时长) → N5(×f，自带 clamp ≥1)
        //      ⇒ 最后一次乘法是 N5 的 ×f，其钳制即终钳 ⇒「钳 ≥1 落在最后」成立；
        //   ③ engineDuration == 目标时长 T 时**原样返回入参**（连副本都不建）；
        //   ④ ⛔ **【2026-09-22 作废，原文留档】** 原文写的是：「原生 recipeModifier 链**不调用**本方法
        //      （那条路没有"引擎时长"这个概念，缩了就是错的）⇒ 共用方法 applyHostTailEffects
        //      一个字节都没改，原生路径逐字不变。」
        //      —— **前半句仍成立**（原生链确实不调用本方法）；**后半句已作废**：
        //      本轮给 `applyHostTailEffects` 加了一个实参（配方定义原时长，作"永不变长"的天花板），
        //      **原生路径必须同步传它**。准确表述改为「**公式两边同步演进，不是"一边不动"**」。
        //
        // 🔴🔴 2026-09-22（"min 化"→ 同日再改判 A 口径）—— 下面这段的**现行读法**：
        //   · **守恒反缩放的分母** = N6 之后的真实时长 T（= max(1, dx, L)），**不是** d0、也不是引擎时长 D；
        //     **不能**用引擎装配后的 D —— D 自带硬编码的 20 下限，会让分母取错（守恒静默破掉，总能量差 T/D 倍）。
        //   · d0 仍要单独取一次：它作为 originalDuration 传进 A 入口（该形参在 A 入口里未被使用，见其 javadoc）。
        //   · d0 与抬头两行走**同一个查找**（{@link #originDurationOf}），防漂移。
        final int engineDuration = built.duration;
        final int originDuration = originDurationOf(parallelData);
        // ⛔⛔ 【2026-09-22 作废，原文留档】旧口径 → 新口径 **A1**
        //   ⛔ 旧原文（作废）：
        //       final int durationCeiling = originDuration > 0 ? originDuration : engineDuration;
        //       // N6 之后的真实时长 T = min(原时长, max(1, limitedDuration))；守恒要的是 D/T，不是 D/L。
        //       final int targetDuration = PrimordialRecipeEffects.durationTarget(durationCeiling, limitedDuration);
        //   ⛔ 作废原因（用户 2026-09-22 拍板选 A「照上游：下限 + 总能量守恒，不补产出」）：
        //      **旧口径是【上限】——"不许更慢"；用户要的是【下限】——"不许更快 + 总能量守恒"。**
        //      🔴 **连带作废**：主机侧「**时长永不变长**」这条红线（用户选 A 时**明知**小批量会变慢、
        //      EUt 同步降低）⇒ 「1 tick 配方仍是 1 tick」**不再是判据**。
        //   ✅ 新口径 A1（= 上游 `RecipeCalculationHelper.buildNormalRecipe` 的语义，
        //      已用 `javap -c` 逐句核对：两分支的 `EUt × T` 都等于 `totalEu`）：
        //        T   = max(dx, L)      dx = totalEu ÷ getOverclockVoltage()
        //        EUt = totalEu ÷ T     产出：不动
        //   🔴 **`rescaleEnergyForDuration` 一个字不动**：已证明「引擎自己算的 EUt_engine × D == totalEu」，
        //      而它做的正是 `EUt_final = EUt_engine × D/T` ⇒ `EUt_final × T = totalEu` ——
        //      **它就是 A1 的守恒**，只是原先喂进去的 T 用错了公式。**本处只换 T。**
        final long maxEUt = host.getOverclockVoltage();
        // totalEu 口径（照上游原文，不许自己编）：
        //   Σ_i [ getRecipeEut(originRecipeList[i]) × originRecipeList[i].duration × parallels[i] ] × euMultiplier
        // 依据：MutableRecipesLogic.buildFinalNormalRecipe 字节码 178(getRecipeEut)/184(duration)/188,198,201(三次 dmul)。
        long totalEu = 0L;
        final List<GTRecipe> originRecipes = parallelData.getOriginRecipeList();
        final long[] parallels = parallelData.getParallels();
        for (int i = 0; i < originRecipes.size() && i < parallels.length; i++) {
            final GTRecipe originRecipe = originRecipes.get(i);
            // 🔴 2026-09-26：并行数现在是 long（可达 Long.MAX）⇒ 这条三因子累加改成【饱和】写法。
            //    不溢出时与裸乘/裸加【逐位相同】；溢出时返回 Long.MAX_VALUE 而不是回绕成负数
            //    —— 回绕成负数的后果是 dx < 0 ⇒ targetDuration 取到下限，总能量被算成"几乎免费"
            //    （静默错数，本项目最忌讳的形态）。
            final long perRecipe = PrimordialModuleMachine.saturatedMultiply(
                    PrimordialModuleMachine.saturatedMultiply(getRecipeEut(originRecipe),
                            (long) originRecipe.duration),
                    parallels[i]);
            totalEu = PrimordialModuleMachine.saturatedAdd(totalEu, perRecipe);
        }
        totalEu = (long) ((double) totalEu * getEuMultiplier());
        final int dx = maxEUt > 0L ? (int) Math.min(Integer.MAX_VALUE, totalEu / maxEUt) : 0;
        final int targetDuration = PrimordialRecipeEffects.durationFloorTarget(dx, limitedDuration);
        final GTRecipe balanced = PrimordialRecipeEffects.rescaleEnergyForDuration(
                built, engineDuration, targetDuration);

        // 🔴 尾链走 **A 专用入口**：N6 直接接受上面算出的 A 目标，
        //    否则 applyHostTailEffects 内部那个 min 会把它**静默抹掉**（连绊线都不响，
        //    因为它的 auditDurationWithin 用的是同一个 min 目标 —— 这是 2026-09-22 查出来的最坏形态）。
        final GTRecipe modified = PrimordialRecipeEffects.applyHostTailEffectsA(
                balanced, gateBonus, targetDuration, originDuration, "主机-多配方引擎");

        // 抬头（Jade）两行的显示因子：本路径上 begin() 没人调 ⇒ tracker 的 context 恒为 null，
        // 所以必须由我们自己把【真实因子】写进去（修饰链那条路是 begin/finish 包着的，不能照抄）。
        // 🔴 队长裁决 ⓒ 首版选 (i)、(Q) 改判（2026-09-21 第二次）：耗能倍率 = f × (d0 / T)
        //    —— 两行都以【原配方】为基线且互为倒数，等式 energy × duration = f 恒成立。
        //    与"真乘进 EUt 的反缩放因子 D/T"是**两个不同的量**，代码里分别用
        //    energyRescaleFactor(D,T)（真缩放）与局部变量 (d0/T)（显示）隔离，避免混用。
        //    ⚠️ **2026-09-22 A 口径细化**：下面两个方法里凡写作 L 的地方一律读作 T
        //       （T = max(1, dx, L) 是 N6 之后的真实时长，A 下限）—— 原文保留，**实参换成 T**。
        captureEngineReduction(parallelData, gateBonus, targetDuration, balanced);

        logEngineEvidence(parallelData, gateBonus, targetDuration, engineDuration, modified);
        return modified;
    }

    // ═══════════════════════════ C-fix：抬头两行的显示因子 ═══════════════════════════

    /**
     * 把「耗能倍率 / 耗时倍率」两个<b>真实因子</b>写进 gtlcore 的显示追踪器
     * （{@code RecipeMultiplierTracker} → Jade 的 {@code RecipeMultiplierProvider}）。
     *
     * <h2>为什么必须由我们自己写（引擎路径的缺口）</h2>
     * 修饰链那条路上，{@code begin/finish} 由 gtlcore 的 {@code RecipeModifierListMixin} 包在
     * {@code RecipeModifierList.apply} 外面；{@code captureReduction(machine, modified, 1.0, 1.0)}
     * 落在 <b>context 分支</b>，由 {@code calculate(baseRecipe, recipe)} 自己算出真值。
     * <p>本引擎路径<b>完全不经过</b> {@code RecipeModifierList.apply}
     * （{@code [字节码]} {@code MutableRecipesLogic} 对 {@code RecipeModifier} 的命中数为 {@code 0}）
     * ⇒ 没人调 {@code begin} ⇒ tracker 里<b>没有</b>本机的条目 ⇒
     * {@code RecipeMultiplierProvider.write} 会走它的 {@code or(DEFAULT)} 兜底，
     * 在抬头画成 <b>{@code 100% / 100%}</b>，也就是<b>谎报"毫无减免"</b>——而主机明明在减免。
     * <p>⇒ 本方法把这个条目<b>补上</b>。
     *
     * <h2>🔴 分支判定（不许只写结论，这是本方法的核心）</h2>
     * {@code RecipeMultiplierTracker.captureReduction} 有两条分支（{@code [字节码]}）：
     * <pre>
     *   CaptureContext ctx = contextFor(machine);          // ThreadLocal；且要求 ctx.machine == machine
     *   if (ctx != null) {                                 // ← 【context 分支】
     *       Multipliers m = ctx.captured != null ? ctx.captured : calculate(ctx.baseRecipe, recipe);
     *       ctx.captured = multiply(m, d3, d4);            // ← 累乘！再传 f 就是【乘第二次】= 假数据
     *       return;
     *   }
     *   MULTIPLIERS.put(machine, multiply(DEFAULT, d3, d4)); // ← 【null 分支】= (finiteOrOne(d3), finiteOrOne(d4))
     *                                                       //    recipe 形参在这里【被完全忽略】
     * </pre>
     * <b>引擎路径走的是 null 分支</b>，依据三条：
     * <ol>
     *   <li>{@code begin} 的唯一调用者是 gtlcore {@code RecipeModifierListMixin}（包在
     *       {@code RecipeModifierList.apply} 外）；而本路径不经过 {@code RecipeModifierList.apply}
     *       （见上，字节码命中 0）⇒ <b>没人调 begin</b>；</li>
     *   <li>即便别的机器在<b>同一线程</b>上正处在自己的链里，{@code contextFor} 用的是
     *       {@code ctx.machine == machine} 的<b>引用相等</b>判断（{@code [字节码]} {@code if_acmpne}）
     *       ⇒ 对本机仍返回 null ⇒ 仍是 null 分支；</li>
     *   <li>{@code MULTIPLIERS} 是 {@code WeakHashMap}，{@code put} 会<b>覆盖</b>而不是累加
     *       ⇒ 每轮配方只留最后一次写入，不会跨轮积累。</li>
     * </ol>
     * ⇒ 在 null 分支里传我们自己的因子是<b>安全且精确</b>的（{@code recipe} 形参被忽略）。
     *
     * <h2>🔴 口径与等式（现行 = 队长 2026-09-21 改判 (Q)）</h2>
     * <b>我们的口径 =「每并行份（per-craft）」</b>，与 gtlcore {@code calculate} 同一种归一
     * （它那边是 {@code |EUt_cur|/|EUt_base|/max(1,realParallels)}）。写进 tracker 的两个数是：
     * <pre>
     *   耗时倍率 duration = L / d0            （L = N6 之后的真实时长 = max(1, dx, max(1, limitedDuration))，d0 = 原配方时长）
     *   耗能倍率 energy   = f × (d0 / L)      ← 队长改判 (Q)：分母用【原配方时长 d0】，不是引擎中间量
     *                       └ f = reductionFactor(gateBonus)（与 N5 同一个纯函数）
     *                       └ d0/L 与 duration = L/d0 【互为倒数、同源】：
     *                         两者都由 originDurationOf(parallelData) 这一个查找产出
     * </pre>
     * <b>推导（一行）</b>：
     * <pre>
     *   energy × duration = [f × d0/L] × [L/d0] = f          ✅ 恒成立
     * </pre>
     * <b>更完整的推导（含是否含并行数）</b>：
     * <pre>
     *   记 EUt_引擎 = 引擎装配出的单 tick 耗电（整批），p = 并行数，d0 = 原配方时长，
     *        L = N6 之后的真实时长（= max(1, dx, max(1, limitedDuration))），D = duration_引擎，baseEUt = 原配方单 tick 耗电。
     *
     *   ① 本工程这一步把 EUt 从 EUt_引擎 变成 EUt_final = EUt_引擎 × (D/L) × f
     *        （D/L 来自守恒反缩放；f 来自 N5）。
     *   ② 引擎自己的不变量（守恒反缩放的前提）：EUt_引擎 × D = baseEUt × d0 × p
     *        ⇒ EUt_引擎 = baseEUt × d0 × p / D。
     *   ③ 于是 EUt_final = baseEUt × d0 × p × f / L
     *        ⇒ 「每并行份的单 tick 耗电比」= EUt_final / (p × baseEUt) = f × d0 / L   ← **正是 (Q) 的 energy**
     *   ④ 「每并行份的总能量比」= (EUt_final × L) / (p × baseEUt × d0) = f
     *        ⇒ 与 ③ 相乘的 duration = L/d0 一起：energy × duration = f ✅
     * </pre>
     * <ul>
     *   <li><b>不含并行数</b>：p 在上面每一步的比值里都被约掉了（与 gtlcore {@code calculate} 同口径）——
     *       <b>整批</b>的总能量比 = `f × p`。</li>
     *   <li>✅ <b>(Q) 下等式恒成立</b>：`energy × duration = f` 与 `D` 无关 ⇒ 侧栏 20 / 60 两档乘积都 = `f`。</li>
     *   <li>🔴 <b>与「真乘进 EUt 的反缩放」是不同的量，名字必须分清</b>：
     *       <pre>
     *         energyRescaleFactor(D, L) = D/L   —— 【真实反缩放因子】，乘进 EUt，**保持 D/L 不许换成 d0/L**
     *         energy 里那个 d0/L               —— 【显示用】，分母是原配方时长 d0
     *       两者数值一般不等（默认档 D = 20、d0 = 200 ⇒ 差 10 倍）。
     *       若把真反缩放也换成 d0/L，守恒立刻被破坏（总能量会差 d0/D 倍）。</pre></li>
     *   <li><b>为什么 &gt;100% 是真话</b>：主机把原配方 200 tick 压到 20 tick 跑完，
     *       但总能量要守恒 ⇒ <b>单 tick 功率必须约 10 倍</b>；再乘 N5 的 `f = 0.5819` ⇒ `×5.8188`。
     *       抬头就如实画 <b>581.88%</b>（不许为好看钳回 100%）。</li>
     * </ul>
     *
     * <h3>🔴 作废项留档：上一版是 (P)（只留档、不生效）</h3>
     * <pre>
     *   —— 以下为队长裁决 ⓒ 首版（选 (i) 的 (P) 形式）写在代码里的原文，现已作废 ——
     *   耗能倍率 energy = f × (duration_引擎 / max(1, limitedDuration))
     *                     └ duration_引擎/max(1,limitedDuration) = energyRescaleFactor（与守恒反缩放同一个 helper）
     *   ⇒ 两行的乘积 = energy × duration = f × D / d0
     *   ⇒ 它等于「每并行份的总能量比（= f）」当且仅当 D == d0。
     * </pre>
     * <b>作废原因（队长改判 (Q) 的理由）</b>：选 (i) 的**唯一理由**是"`耗能 × 耗时 = 总能量比` 这个自验等式成立"，
     * 而 (P) 恰恰让它不成立；根因是 **(P) 的分母选错了基线** —— gtlcore 的 `calculate` 口径是相对
     * <b>原配方</b>（`baseRecipe`），而 (P) 的分母 `D` 是<b>引擎中间量</b>。
     * ⇒ 改为 `d0/L` 后等式恒成立；**默认档显示 `58.19% → 581.88%` 是"变真"、不是回归**，
     * 且与另一行自洽：`581.88% × 10% = 58.19% = f`。
     *
     * <h2>🔴 可读的代码守卫：探针法（先取一次当前值，再决定写法）</h2>
     * 上面三条是"推理"，而 {@code contextFor} 是 {@code private} 的，外面查不到 ——
     * 所以本方法再加一道<b>运行期</b>守卫，用两条分支的<b>可观测差别</b>来判断自己走了哪一支：
     * <pre>
     *   null 分支：MULTIPLIERS.put(…, multiply(DEFAULT, d3, d4)) —— multiply 每次都 new 一个新记录
     *              ⇒ 探针调用之后，Map 里的【值引用】一定变了
     *   context 分支：全程只动 ctx.captured，【完全不碰 MULTIPLIERS】
     *              ⇒ 探针调用之后，Map 里的【值引用】一点没变
     * </pre>
     * 于是：<b>先用 (1.0, 1.0) 探一次</b>（{@code multiply} 对累乘器是<b>单位元</b>，
     * 即使在 context 分支里也<b>不会污染</b> {@code ctx.captured}），再看值引用有没有变：
     * <ul>
     *   <li><b>变了</b> ⇒ 确认是 null 分支 ⇒ 再写真实因子（覆盖掉探针写下的 1.0/1.0）；</li>
     *   <li><b>没变</b> ⇒ 意外落进了 context 分支 ⇒ <b>绝不写我们的因子</b>
     *       （写了就是乘第二次 = 假数据），改为打一条 {@code [SHANHAI-ENGINE]} ERROR 并返回 ——
     *       宁可退化成 tracker 的既有值/上游兜底 {@code (1.0,1.0)}（无信息），也不高估减免。</li>
     * </ul>
     * ⚠️ 这里必须用<b>引用相等</b>（{@code ==}）比较，<b>不能</b>用 {@code Multipliers.equals}：
     * {@code Multipliers} 是 Java {@code record}，值相等会掩盖"到底有没有 put 过"这件事。
     * <p>🧾 代价：null 分支下多一次 Map 读写（每轮配方一次，非每 tick）。可忽略。
     *
     * <h2>🔴 诚实边界（红线）</h2>
     * 本方法<b>只写显示用的 {@code WeakHashMap}</b>，<b>不改配方的任何字段</b>：
     * 唯一的输入是数字（成本系数 / 时长因子），`built` 只作为被忽略的形参传入。
     *
     * @param parallelData    引擎本轮的并行数据（取第一条件为「原配方」的时长基准 d0）
     * @param gateBonus       主机专属槽门控等级（与 N5 步骤同源）
     * @param targetDuration  🔴 N6 之后的【真实时长 T】= {@code max(1, dx, max(1, limitedDuration))}（A 口径，下限）；
     *                        <b>不是</b>侧栏那个限定值 L —— {@code dx > L} 时短配方会被<b>拖长</b>，只有恰好相等时才等于 L
     *                        （2026-09-22 A 口径；本方法 javadoc 正文里凡写 {@code L} 处一律读作 {@code T}）。
     * @param built           进链配方（null 分支下该形参被忽略；传它只是为了语义清晰）
     *                       <p>⚠️ 本方法<b>不接收也不使用</b>引擎时长 D —— 队长改判 (Q) 后，
     *                       两行都相对<b>原配方</b>（d0），与"引擎中间量"无关。
     */
    private void captureEngineReduction(ParallelData parallelData, int gateBonus, int targetDuration,
                                        GTRecipe built) {
        try {
            final PrimordialOmegaEngineMachine host = getMachine();
            // 🔴 口径（队长 2026-09-21 改判 (Q)）：两行【互为倒数、同源】，全部来自 originDurationOf 那**一个**查找：
            //    · duration 因子 = T / d0
            //    · energy   因子 = f × (d0 / T)      ← f 与 N5 同一个纯函数；d0/T 与上式倒数
            //    等式：energy × duration = f × (d0/T) × (T/d0) = f  ✅ 恒成立（与 D 无关）
            //    ⚠️ 原文这里写作 {@code L}；N6 走 A 口径后，真实时长 T = max(1, dx, L) 是【下限】
            //       ⇒ 与 L 一般不等（dx > L 时短配方被拖长）⇒ 形参语义由 L 改成 T，**原文里的 L 一律读作 T**。
            //  🔴🔴 别混：真乘进 EUt 的反缩放因子是 PrimordialRecipeEffects.energyRescaleFactor(D, T) = D/T，
            //      **分母是我们的真实时长 T（由引擎时长 D 推出），不是 d0**。若把显示侧那套 d0/T
            //      用到真反缩放上，守恒立刻被破坏（总能量会差 d0/D 倍）。
            final int originDuration = originDurationOf(parallelData);
            final int target = Math.max(1, targetDuration);
            final double durationFactor = originDuration > 0 ? (double) target / (double) originDuration : 1.0D;
            final double energyFactor = PrimordialRecipeEffects.reductionFactor(gateBonus)
                    * (originDuration > 0 ? (double) originDuration / (double) target : 1.0D);

            // ── ① 探针：先取一次当前值，再决定写法 ──
            final RecipeMultiplierTracker.Multipliers before = RecipeMultiplierTracker.get(host).orElse(null);
            RecipeMultiplierTracker.captureReduction(host, built, 1.0D, 1.0D);
            final RecipeMultiplierTracker.Multipliers afterProbe = RecipeMultiplierTracker.get(host).orElse(null);
            if (afterProbe == before) {
                // 🔴 值引用没变 ⇒ 探针没写进 MULTIPLIERS ⇒ 我们落进了 context 分支。
                //    此时 ctx.captured 已经把我们的 (1.0,1.0) 乘进去了（乘 1 无副作用），
                //    但再传真实因子就会乘第二次 ⇒ 绝不允许。响亮报错 + 不写。
                ShanhaiMod.LOGGER.error("[SHANHAI-ENGINE] 显示因子的前置不变式被破坏："
                        + "RecipeMultiplierTracker.captureReduction 走的是 context 分支（ctx != null）。"
                        + "为避免把成本系数乘第二次（假数据），本次【不写】真实因子；"
                        + "抬头两行会退化成上游兜底值。配方数值不受影响。machine={}", host.getPos());
                return;
            }

            // ── ② 确认走的是 null 分支 ⇒ 写真实因子（覆盖探针的 1.0/1.0）──
            RecipeMultiplierTracker.captureReduction(host, built, energyFactor, durationFactor);
        } catch (Throwable t) {
            // 纯显示：任何异常都不允许影响配方装配本身。
            ShanhaiMod.LOGGER.error("[SHANHAI-ENGINE] 写显示因子时异常（已忽略，配方不受影响）", t);
        }
    }

    /**
     * 引擎本轮「原配方」的时长 <b>d0</b>（{@code ParallelData.getOriginRecipeList()} 的第一条——
     * 正是引擎自己算能量时用的那一批 {@code r.duration}）。
     *
     * <h2>🔴 为什么单独抽这个查找（防漂移）</h2>
     * 抬头两行<b>必须互为倒数、同源</b>（队长改判 (Q) 的等式 {@code energy × duration = f} 依赖这一点）：
     * <pre>
     *   duration 因子 = L / d0
     *   energy   因子 = f × (d0 / L)      ← d0 与上式【同一个来源】
     * </pre>
     * 若两处各自去 {@code getOriginRecipeList().get(0)}，将来一边改口径、另一边忘改，
     * 等式就会静默失效（本项目最忌讳的失败模式）⇒ 只此一个查找点。
     *
     * <h2>已知的近似（明写）</h2>
     * 多候选配方（{@code originRecipeList.size() > 1}）时，聚合后的"原时长"没有单一取值；
     * 这里<b>取第一条</b>为基准。单候选＝精确；多候选＝有意的近似。
     *
     * @return {@code d0}；列表为空 / 取不到 / 时长 ≤ 0 时返回 {@code 0}（调用方据此退化成 {@code 1.0} 无信息）
     */
    private static int originDurationOf(ParallelData parallelData) {
        final List<GTRecipe> origins = parallelData.getOriginRecipeList();
        if (origins == null || origins.isEmpty()) {
            return 0;
        }
        final GTRecipe origin = origins.get(0);
        return (origin == null || origin.duration <= 0) ? 0 : origin.duration;
    }

    /**
     * 一次性证据日志。
     *
     * <h2>为什么必须有它</h2>
     * 「换了引擎」这件事本身<b>没有可观察的外观</b>：机器照样转、配方照样出。
     * 若不给一条可 grep 的标记，验收时"引擎到底跑起来没有"就只能靠推断，
     * 而「多配方引擎没生效」与「生效了」在日志上长得<b>完全一样</b>（都是什么都没有）。
     * <p>所以这里在<b>第一次</b>由本引擎装配出配方时打一条 {@code [SHANHAI-ENGINE]} INFO，
     * 把四个效果的可对账数字一次给全：并行数 / 门控等级 / 时长 / EUt，
     * 并额外给出<b>守恒反缩放因子</b>与<b>引擎时长</b>。
     * <p>⚠️ <b>别把日志里那个"反缩放因子"当成抬头的耗能倍率</b>：队长改判 (Q) 之后，
     * 抬头耗能倍率 = {@code f × d0/L}（分母是<b>原配方时长</b>），而日志里这个反缩放因子 = {@code D/L}
     * （分母是<b>引擎时长</b>，是真正乘进 EUt 的那个）。日志把两者都印出来，正是为了让这条口径差
     * 可以被独立核对（默认档下 D=20 而 d0 可能是 200，两者差 10 倍）。
     * <p>只打一次（同 {@code ShanhaiInfiniteThreadDisplayMixin} 的 {@code shanhai$displayAnnounced} 做法），
     * 避免每轮配方刷屏。{@code latest.log} 里 grep {@code SHANHAI-ENGINE} 即可。
     */
    private void logEngineEvidence(ParallelData parallelData, int gateBonus, int targetDuration,
                                   int engineDuration, GTRecipe modified) {
        if (shanhai$engineAnnounced) {
            return;
        }
        shanhai$engineAnnounced = true;
        try {
            long totalParallel = 0L;
            final long[] parallels = parallelData.getParallels();
            for (long p : parallels) {
                totalParallel += p;
            }
            // ⚠️ 2026-09-22：本方法第 3 个形参由 L（侧栏限定值）改成 T（N6 之后的真实时长）；
            //    日志里的标签同步把 L 改写成 T，并额外印出 d0 / D，方便独立核对三者关系。
            final double rescale = PrimordialRecipeEffects.energyRescaleFactor(engineDuration, targetDuration);
            final double f = PrimordialRecipeEffects.reductionFactor(gateBonus);
            // 抬头（Jade）耗能倍率 = f × d0/T —— 分母是【原配方时长 d0】，与耗时倍率 T/d0 互为倒数。
            final int originDuration = originDurationOf(parallelData);
            final double displayEnergy = originDuration > 0
                    ? f * ((double) originDuration / (double) Math.max(1, targetDuration))
                    : f;
            ShanhaiMod.LOGGER.info("[SHANHAI-ENGINE] 多配方引擎已接管主机配方装配："
                            + "候选配方 {} 条 / 实际并行 {} / 门控等级 {}（倍率 ×{}、成本系数 f ×{}）"
                            + "/ 时长 T {} tick（d0 原配方 {}、D 引擎 {}、T N6目标 {}）"
                            + "/ 守恒反缩放（D/T）×{}"
                            + "/ 抬头耗能倍率 f×(d0/T) = ×{} / EUt {}",
                    parallels.length,
                    totalParallel,
                    gateBonus,
                    PrimordialRecipeEffects.outputMultiplier(gateBonus),
                    String.format(java.util.Locale.ROOT, "%.4f", f),
                    modified.duration,
                    originDuration,
                    engineDuration,
                    targetDuration,
                    String.format(java.util.Locale.ROOT, "%.4f", rescale),
                    String.format(java.util.Locale.ROOT, "%.4f", displayEnergy),
                    RecipeHelper.getInputEUt(modified));
        } catch (Throwable ignored) {
            // 纯诊断：任何异常都不允许影响配方装配本身。
        }
    }
}
