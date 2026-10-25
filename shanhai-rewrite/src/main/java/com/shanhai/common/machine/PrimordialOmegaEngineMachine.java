package com.shanhai.common.machine;

import com.google.common.primitives.Ints;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.gui.fancy.ConfiguratorPanel;
import com.gregtechceu.gtceu.api.gui.fancy.IFancyConfiguratorButton;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.TickableSubscription;
import com.gregtechceu.gtceu.api.machine.feature.IMachineLife;
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableElectricMultiblockMachine;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableItemStackHandler;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.logic.OCParams;
import com.gregtechceu.gtceu.api.recipe.logic.OCResult;
import com.gtladd.gtladditions.api.machine.IGTLAddMultiRecipeMachine;
import com.gtladd.gtladditions.api.machine.gui.LimitedDurationConfigurator;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.widget.SlotWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;
import com.shanhai.ShanhaiMod;
import com.shanhai.client.compat.ConfiguratorTabGridCompat;
import com.shanhai.common.compat.GtlAddCompat;
import com.shanhai.common.recipe.PrimordialRecipeEffects;
import com.shanhai.common.thread.ShanhaiParallelBudget;
import com.shanhai.config.ShanhaiConfig;
import com.shanhai.machine.engine.ModuleSlotDiagnostics;
import com.shanhai.machine.host.GuixuVisionCoreMachine;
import com.shanhai.machine.host.HostFxConfigurators;
import com.shanhai.machine.module.ModuleSetBlockWatch;
import com.shanhai.machine.module.PrimordialModuleMachine;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.gtlcore.gtlcore.api.machine.multiblock.IModularMachineHost;
import org.gtlcore.gtlcore.api.machine.multiblock.IModularMachineModule;
import org.gtlcore.gtlcore.api.machine.trait.IBatchMachine;
import org.gtlcore.gtlcore.api.recipe.RecipeMultiplierTracker;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 青铜神锻（原始终焉引擎）主机控制器。
 *
 * <h2>🔴 全限定类名冻结</h2>
 * <b>必须是 {@code com.shanhai.common.machine.PrimordialOmegaEngineMachine}</b>。
 * 模块侧（java-module）的 {@code getHostType()} 编译期引用本类做
 * {@code module.getHostType().isInstance(this)} 校验；返回父类、接口或另一个包的同名类都会让
 * 这行恒为 {@code false} —— <b>编译不报错、运行期模块永远连不上、且不抛异常</b>，
 * 是最难查的一类静默失败。
 *
 * <h2>框架：gtlcore 的公开 API，不自造协议</h2>
 * 只实现 {@link IModularMachineHost} 的 2 个抽象方法
 * （{@code getModuleSet()} / {@code getModuleScanPositions()}）；
 * 扫描（{@code scanAndConnectModules()}）与回连协议是 gtlcore 的 default 方法，<b>不重写</b>。
 * 本类额外<b>故意覆写</b>三项：
 * <ul>
 *   <li>{@code safeClearModules()} —— gtlcore 默认实现在遍历模块集合时对同一集合
 *       {@code removeFromHost}，上游为此踩过 `fastutil ReferenceOpenHashSet 迭代器 wrapped 变 null`
 *       的并发修改崩溃。这里改为「先复制快照 → 遍历快照断开 → clear」。</li>
 *   <li>{@code getMaxModuleCount()} —— 默认是 {@code Integer.MAX_VALUE}，不覆写则
 *       {@code exceedsModuleLimit()} 永不触发，「16 槽位」这条语义直接失效。</li>
 *   <li>{@link IMachineLife#onMachineRemoved()} —— 机器被拆掉时同样要断开全部模块，
 *       不能留下对模块的强引用（GTCEu 用 {@code instanceof IMachineLife} 派发这个回调，
 *       多方块基类不实现该接口，因此这里显式实现）。</li>
 * </ul>
 *
 * <h2>16 个模块位的几何来源</h2>
 * {@link GtlAddCompat#moduleSlots(BlockPos, net.minecraft.core.Direction)} —— 与 gtladditions
 * 那台<b>在游戏里正常工作</b>的伪神之锻炉主机走完全相同的代码路径（{@code AntichristPosHelper}）。
 * 每次由 {@code getPos()} + {@code getFrontFacing()} <b>实时计算，不缓存</b>（朝向会变）。
 *
 * <h2>跨区块槽位是常态</h2>
 * 16 个槽位在主机背后 13/25/37/49 格，必然跨区块；{@code MetaMachine.getMachine()} 对未加载区块
 * 返回 {@code null} 是<b>允许</b>的：主机扫描漏掉的槽位由模块侧的主动回连补齐（规格 §3.3 双路回连）。
 * 本类<b>不</b>为此添加任何补救逻辑。
 *
 * <h2>🔴 认知差：旧私货的「批处理」按钮没有代码可删</h2>
 * 旧私货把「批处理」开关做在<b>一个 part</b> 上（{@code CosmicCleanGravityMaintenanceHatchMachine}），
 * 而本重写工程的主机侧栏那个位置现在是 {@code attachConfigurators} 里的<b>球体风格 toggle</b>
 * （中子星 / 鸿蒙微型宇宙），<b>不是</b>批处理按钮。
 * ⇒ 工程上<b>不存在「删几行」这个动作</b>；N6 的「替换」实际含义是
 * 「<b>不把批处理搬进重写工程</b> + 把『配方最短耗时』放进侧栏」。
 * <p>另外：本类继承的 {@code WorkableElectricMultiblockMachine} 在运行期被 gtlcore 的
 * {@code WorkableElectricMultiblockMachineMixin} 织入了 {@code IBatchMachine}（字段 {@code batchEnabled}
 * 默认 {@code false}），所以「批处理」是<b>宿主自带的</b>、默认关闭的能力，
 * 与本工程无关，本工程也没有去动它。
 */
public class PrimordialOmegaEngineMachine
        extends WorkableElectricMultiblockMachine
        implements IModularMachineHost<PrimordialOmegaEngineMachine>, IMachineLife, IGTLAddMultiRecipeMachine, IBatchMachine,
                   ParallelOverrideMachine {

    /** 规格 §3.2 ⑥：16 个模块位。不覆写 {@code getMaxModuleCount()} 就永远不是 16。 */
    public static final int MAX_MODULE_COUNT = 16;

    // ═════════════════════════════════ N4「无限」 ═════════════════════════════════

    /**
     * 🔴 <b>2026-09-26：主机并行上限从 {@code 1 << 30}（2^30）改成 {@code 9223372036854775807L}
     * —— <u>老山海逐字</u>。</b>
     *
     * <h2>老山海原文（反编译逐字）</h2>
     * <pre>
     *   [源码原文] originals/upstream/.../primordial/PrimordialOmegaEngineMachine.java:48
     *       private static final long MAX_PARALLEL = 9223372036854775807L;             ← long，不是 int
     *   :98  &#64;Override public int getMaxParallel() { return Ints.saturatedCast(MAX_PARALLEL); }
     *                                                              ⇒ 2147483647
     *   :103 &#64;Override public int getAdditionalThread() { return Integer.MAX_VALUE; }
     * </pre>
     * ⇒ <b>老山海的 {@code MAX_PARALLEL} 是 long，而它唯一的用途就是被 {@code Ints.saturatedCast}
     * 压回 {@code int}</b>。本类现在<b>逐字照抄这个形态</b>：常量是 long，
     * {@link #getMaxParallel()} 是那条饱和桥。
     *
     * <h2>⛔ 被本常量替换掉的旧口径（原文留档，勿再启用）</h2>
     * <pre>
     *   ⛔ 旧值： public static final int MAX_PARALLEL = 1 << 30;      // 2^30 = 1073741824
     *   ⛔ 旧理由：并行数在 gtladditions MultipleRecipesLogicMixin 与 gtlcore ParallelLogicMixin
     *      两处裸做 long 乘法，取 2^30 可证明最坏乘积 2^36 + 2^60 ≈ 1.153e18 &lt; Long.MAX_VALUE。
     *   ⛔ 作废原因（用户 2026-09-26 原话）：「搞错了，AE里面的是9.2E，流体是9.2P，我们的并行就先
     *      按照老山海的并行表，顺便把主机的并行也改成long.max」⇒ 用户明确要 Long.MAX，方向上不许再"取小"。
     * </pre>
     * 🔴 <b>溢出这套账现在由谁承担</b>（旧注释担心的那两处，逐条给结论）：
     * <ol>
     *   <li>gtladditions {@code MultipleRecipesLogicMixin} 的
     *       {@code (long) getMaxParallel() * Ints.saturatedCast(MAX_THREADS + getAdditionalThread())}：
     *       {@code getMaxParallel()} 现在是 {@code Ints.saturatedCast(MAX_PARALLEL)} = {@code Integer.MAX_VALUE}
     *       ⇒ 乘积 ≤ {@code 2^31 × 2^31 ≈ 4.6e18 < Long.MAX_VALUE}，<b>结构性不可能回绕</b>；
     *       且那一处只作用于 gtlcore 的 {@code MultipleRecipesLogic}，
     *       <b>本工程的主机与模块都不在那个类上</b>（我们继承 gtladditions 的 {@code MutableRecipesLogic}）。</li>
     *   <li>gtlcore {@code ParallelLogicMixin.doParallelRecipes} 的
     *       {@code setRealParallels(limitByOutput * 原值)}：{@code limitByOutput} 是
     *       {@code limitByOutputMerging} 的 <b>int</b> 返回值 ⇒ ≤ {@code Integer.MAX_VALUE}，
     *       与"配方定义原值的 realParallels = 1"相乘 ⇒ ≤ {@code 2^31−1}，不回绕。</li>
     * </ol>
     * <p>主机的 <b>long 通道</b>（引擎真正读的那一个）另由
     * {@link #getRecipeLogicMaxParallel()} 承担 —— 那里才是 {@code 9223372036854775807}。
     */
    public static final long MAX_PARALLEL = 9223372036854775807L;

    /**
     * {@link #MAX_PARALLEL} 的 <b>int 饱和桥</b>（= {@code Ints.saturatedCast(MAX_PARALLEL)} = {@code 2147483647}）。
     *
     * <p>给"形参就是 int"的上游出口用：{@code PrimordialRecipeEffects.applyParallel(recipe, host, int)}、
     * 以及任何 {@code ParallelMachine}/{@code ParallelLogic} 的 int 形参。
     * 老山海那两处在同一个表达式里就地写 {@code Ints.saturatedCast(MAX_PARALLEL)}；
     * 本工程把它提成具名常量，是为了让"这里必须饱和"这件事在调用点上一眼可见，
     * <b>数值与老山海逐位相同</b>。
     */
    public static final int MAX_PARALLEL_INT = Ints.saturatedCast(MAX_PARALLEL);

    /**
     * 主机自带的基础并行（N4）。<b>它是 {@code gtlcore ParallelMachine} 的抽象方法</b>
     * （{@code IGTLAddMultiRecipeMachine} → {@code IWirelessThreadModifierParallelMachine} → {@code ParallelMachine}），
     * 实现它就是本主机进入「多配方体系」的入场券。
     *
     * <p>🔴 <b>2026-09-21 二次改判（换引擎）：本方法的返回值现在是【真的】并行上限 ——
     * 正数 {@link #MAX_PARALLEL}。</b> 之前那个 {@code -114514} 显示哨兵已经<b>从本方法搬走</b>，
     * 改由纯显示层写（见 {@link #DISPLAY_INFINITE_PARALLEL} 的注释）。理由是一条<b>会静默停机</b>的雷：
     * <pre>
     *   多配方引擎 MutableRecipesLogic.calculateParallels() 原文（[源码原文]）：
     *       long totalParallel = (long) this.getMachine().getMaxParallel() * this.getMultipleThreads();
     *   ⇒ -114514 × 1073741824 ≈ -1.23e14（负数，且【没有饱和】）
     *   ⇒ calculateParallelsWithGreedyAllocation 开头 if (remain &lt;= 0L) break; 立刻跳出
     *   ⇒ recipeList 为空 ⇒ 返回 null ⇒ getRecipe() 恒为 null
     *   ⇒ 机器【不工作、不崩溃、日志里一行都不会有】——正是本项目最忌讳的静默失败形态。
     * </pre>
     * 负哨兵在原生 {@code RecipeLogic} 下无害（{@code javap} 全量查过：{@code RecipeLogic} 里
     * {@code getMaxParallel} 有 0 个调用点），换引擎之后立刻致命 ⇒ 必须在本轮一起拆掉。
     *
     * <p>🔴 <b>历史（作废项留档，只留档不生效）</b>：2026-09 队长裁决⑤-2 曾写下
     * 「本工程根本不读这个方法的返回值 / 改这一个方法的返回值不会改变任何并行数」。
     * 那段话的前提是「主机走 GTCEu 原生 {@code RecipeLogic}」，<b>该前提已被本轮换引擎推翻</b>：
     * 现在读它的正是 {@code MutableRecipesLogic.calculateParallels()}（见上）。
     * N4 的【基础上限】仍然同时由 {@link #MAX_PARALLEL} 常量承担
     * （修饰链路径 {@code applyParallel(modified, host, MAX_PARALLEL)} 直接吃常量），
     * 所以两条路径的并行上限<b>同源</b>。
     * <p>除多配方引擎之外，读 {@code getMaxParallel()} 的还有 gtlcore 的
     * {@code org.gtlcore.gtlcore.common.machine.trait.MultipleRecipesLogic}
     * （原文 {@code long remain = this.parallel.getMaxParallel() * 64L;}）、gtladditions 的
     * {@code MultipleRecipesLogicMixin}、gtlcore 的 {@code BatchProcessing.isCustomSubTickParallelized}
     * 与 gtladditions 的 Jade {@code ParallelProviderMixin}
     * （后者写下的那个键最终会被本工程的显示 mixin 覆盖，见 {@link #DISPLAY_INFINITE_PARALLEL}）。
     * 本覆写最初存在的意义只有两条：① 满足接口
     * （{@code IGTLAddMultiRecipeMachine} → … → {@code ParallelMachine}
     * 把 {@code getMaxParallel()} 声明为抽象方法）；② 让
     * {@code machine instanceof ParallelMachine} 成立。
     *
     * <p><b>🔴 一个必须写下来的、差一点就误判的结论</b>：本主机确实<b>是</b> gtlcore 的
     * {@code IRecipeCapabilityMachine} —— 不是靠本工程实现，而是 gtlcore 的
     * {@code gtm.api.machine.WorkableMultiblockMachineMixin}（已在 {@code gtlcore.mixin.json}
     * 的 {@code mixins} 列表里）声明了
     * {@code @Mixin(WorkableMultiblockMachine.class) … implements IRecipeCapabilityMachine, IRecipeLogicMachine}，
     * 在运行期把该接口<b>织进</b>了本主机的基类。
     * 这一点决定了 {@code IParallelLogic.getInputItemParallel} 走的是「按 RecipeHandlePart 统计真实可用量」
     * 那条路、而不是 {@code return 1L} 的退化分支 —— 也就是并行真的会动。
     * <p>（顺带纠正一句本类旧注释里的说法：本主机在<b>编译期</b>就已经是 {@code IRecipeLogicMachine} ——
     * {@code [javap 真 jar]} {@code WorkableMultiblockMachine implements IWorkableMultiController}，
     * 而 {@code IWorkableMultiController extends IMultiController, IRecipeLogicMachine}。
     * 这条正是 {@code MutableRecipesLogic<T>} 那个五元类型界能在编译期满足的原因。）
     */
    // ⛔⛔ 【2026-09-22 作废，原文留档】旧实现 → 新实现（用户裁决「照伪神填 + 显示真实数值」）
    //   ⛔ 旧原文（作废）： {@code @Override public int getMaxParallel() { return MAX_PARALLEL; }}
    //   ⛔ 作废原因：用户 2026-09-22 裁决 —— **主机的并行与线程都照伪神（FOTC）填写**。
    //      伪神从 gtlcore 祖类 {@code WorkableElectricMultipleRecipesMachine} 拿到的就是
    //      {@code Integer.MAX_VALUE}（= 2147483647），不是本工程自定的 2^30。
    //      {@link #MAX_PARALLEL}（2^30）**仍然保留**给修饰链路径
    //      （{@code applyParallel(modified, host, MAX_PARALLEL)} 直接吃常量），两处不再同源。
    //   ✅ 新值 = {@code Integer.MAX_VALUE}；抬头显示真实数值（见显示 mixin 的 2026-09-22 改判）。
    //
    // 🔴 2026-09-25（任务 A/B）：把那个字面量提成【具名常量】，本方法改为返回它。
    //    唯一目的是让【物品 tooltip】（ShanhaiMachines 的 .tooltips(...)）与【Jade】读到同一个来源
    //    —— 机器实例在注册期拿不到，而 tooltip 是注册期就写死的 Component，
    //    所以必须有"不需要实例就能取到"的常量。行为零变化：方法体仍然只是返回这个 int 常量
    //    （编译期常量，javac 会内联，不新增任何类加载）。
    @Override
    public int getMaxParallel() {
        // 🔴 2026-09-26：与老山海逐字同形的【int 饱和桥】
        //   [源码原文] 老山海 PrimordialOmegaEngineMachine.java:99
        //       public int getMaxParallel() { return Ints.saturatedCast(MAX_PARALLEL); }
        //   数值与改动前【逐位相同】（改前 = MAX_PARALLEL_DISPLAY = Integer.MAX_VALUE = 2147483647），
        //   变化的是"这个 2147483647 从哪来"：以前是一个自定的 int 常量，
        //   现在是 Long.MAX_VALUE 经过饱和桥之后的必然结果。
        // 🔴 2026-09-27：改成经 getRecipeLogicMaxParallel() 取 —— 玩家在「并行数」面板里填的覆盖值
        //    必须同时作用到这条 int 桥（Jade 的 ParallelProvider、gtlcore 的 ParallelMachine 读点、
        //    BatchProcessing.isCustomSubTickParallelized 都读它），否则会出现"引擎改了、显示没改"。
        //    ⚠️ 未覆盖时逐值不变：getRecipeLogicMaxParallel() 此时返回 MAX_PARALLEL ⇒ 饱和后仍是
        //       MAX_PARALLEL_INT = 2147483647（改动前那个数，一个 bit 都没动）。
        return Ints.saturatedCast(getRecipeLogicMaxParallel());
    }

    /**
     * 🔴 <b>主机并行上限的 long 通道 —— 引擎真正读的那一个（2026-09-26 新增）。</b>
     *
     * <h2>它解决的问题</h2>
     * 主机的多配方引擎是 gtladditions {@code MutableRecipesLogic}，它的预算是
     * <pre>
     *   [源码原文] MutableRecipesLogic.kt:198
     *       val totalParallel: Long = (long)this.getMachine().getMaxParallel() * this.getMultipleThreads();
     * </pre>
     * {@code getMaxParallel()} 是 int ⇒ 主机永远拿不到 {@link #MAX_PARALLEL}。
     * 用户 2026-09-26 要的是「主机的并行也改成 long.max」⇒ 这个 long 出口就是它。
     *
     * <h2>名字与形状的来源</h2>
     * 老山海 {@code SelectableRecipeTypeSetMachine:175} 的
     * {@code public long getRecipeLogicMaxParallel() { return Math.max(1L, (long) getMaxParallel()); }}
     * —— <b>同一个方法名</b>。差别只在"喂进去的那个数"：老山海喂的是已被 int 压平的 2147483647，
     * 本工程喂的是 {@link #MAX_PARALLEL} 本体（{@code Long.MAX_VALUE}）。
     * <p>⚠️ <b>这是一处【有意的偏离】，必须原样转述给用户</b>：
     * 老山海主机在这条 long 通道上拿到的其实还是 2147483647（它唯一的 override 在模块侧），
     * 所以「主机并行 = Long.MAX」用户在老山海里<b>也看不到</b>；本工程按用户的字面要求做到了 long。
     *
     * <h2>溢出</h2>
     * 与 {@code getMultipleThreads()}（128）相乘的饱和由
     * {@code PrimordialEngineRecipeLogic#calculateParallels()} 用
     * {@link PrimordialModuleMachine#saturatedMultiply(long, long)} 完成
     * （{@code Long.MAX × 128 ⇒ Long.MAX}，不回绕）。
     */
    public long getRecipeLogicMaxParallel() {
        // 🔴 2026-09-27：「并行数」面板里玩家填的覆盖值在这里生效（{@code 0} = 自动 ⇒ 返回 MAX_PARALLEL）。
        //    这一处是主机侧【引擎路径】的唯一读点（PrimordialEngineRecipeLogic#calculateParallels），
        //    另一条路是原生修饰链里的 applyHostRecipeModifier —— 那里已同步改成读本方法（见那一行注释）。
        return Math.max(1L, getEffectiveParallel());
    }

    // ═════════════════════════ 玩家可调的并行数（2026-09-27 新增） ═════════════════════════

    /**
     * 🔴 <b>玩家覆盖的并行数上限；{@code 0} = 未覆盖（用本机自带的 MAX_PARALLEL）。</b>
     *
     * <h2>用户原话（逐字）</h2>
     * <blockquote>「在模块和主机的左下角再新增一个全新的按钮，他可以调节主机或者模块的并行数，
     * 作为一个输入框，可以让玩家输入数字，并且右边有一个一键调至最大的按钮」</blockquote>
     *
     * <h2>为什么主机侧要动【两处】而不是一处（与模块侧的关键不对称）</h2>
     * <pre>
     *   ① 引擎路径（现行主路径）：PrimordialEngineRecipeLogic#calculateParallels()
     *         → host.getRecipeLogicMaxParallel()                ← 本字段经 getEffectiveParallel() 到达
     *   ② 原生修饰链：applyHostRecipeModifier(...) 里那一句 applyParallel(modified, host, MAX_PARALLEL_INT)
     *         —— 它【原来直接吃常量、不读任何方法】⇒ 只改 ① 会出现"填了值、引擎变了、修饰链没变"
     *            的静默半生效。那一行已改成读 getRecipeLogicMaxParallel()（未覆盖时逐值不变）。
     * </pre>
     *
     * <h2>{@code @Persisted} / {@code @DescSynced}</h2>
     * 同「配方最短耗时」（{@code limitedDuration}）那一对：{@code @Persisted} 让拆装/重载后保持，
     * {@code @DescSynced} 让客户端输入框读到服务端的权威值（否则客户端永远显示 0 = 假数据）。
     * 两个注解写在本类里即自动进 {@code MANAGED_FIELD_HOLDER}（按【类】反射登记字段）。
     */
    @Persisted
    @DescSynced
    private long parallelOverride = ParallelOverrideMachine.PARALLEL_AUTO;

    @Override
    public long getParallelOverride() {
        return parallelOverride;
    }

    /**
     * 写入覆盖值：<b>先钳位 → 没变直接返回 → 真变了才 {@code notifyBlockUpdate()}</b>。
     * 形状与 {@link #setLimitedDuration(int)} 逐字一致（本工程"玩家改一个数"的既有规范写法）。
     *
     * <p>🔴 <b>2026-09-27：钳位改成"按本机当前能达到的并行数上钳"</b>
     * （{@link ParallelOverrideMachine#clampOverrideToCeiling(long)}），用户原话：
     * 「不允许玩家输入超出机器可以达到最大并行数的数字」。
     * <b>主机侧这一条是恒等操作</b>：本机的自动值就是 {@link #MAX_PARALLEL}（long 上限），
     * 天花板 = 它 ⇒ 钳位不会改变任何输入值（用户口径 ④）。
     */
    @Override
    public void setParallelOverride(long value) {
        final long next = clampOverrideToCeiling(value);
        if (next == parallelOverride) {
            return;
        }
        parallelOverride = next;
        notifyBlockUpdate();
    }

    /**
     * 🔴 <b>本机当前能达到的并行数 —— 「一键最大」填的就是它（2026-09-27 语义改正）。</b>
     *
     * <pre>
     *   ⛔ 上一版：返回 MAX_PARALLEL（一个与"自动值"同值、但名字写成"天花板"的常量）
     *   ✅ 现行  ：返回 {@link #getAutoParallel()} —— 语义与用户口径逐字对齐
     *             「一键最大是到机器可以达到的并行数（也就是设置 0 时机器的并行数）」
     * </pre>
     * <b>逐值对照</b>：本主机的自动值就是 {@link #MAX_PARALLEL} ⇒
     * <b>返回值与上一版完全相同，主机侧一个 bit 都没动</b>；
     * 改的只是"这个数从哪来"（常量 → 自动值），从此不会与 {@link #getAutoParallel()} 漂移。
     */
    @Override
    public long getParallelOverrideCeiling() {
        return getAutoParallel();
    }

    @Override
    public long getAutoParallel() {
        return MAX_PARALLEL;
    }

    // ⛔⛔ 【2026-10-02 第二轮：本覆写已删除，原文逐字留档】getEffectiveParallel()
    //   ⛔ 旧原文（作废）：
    //       /**
    //        * 覆盖生效之后的并行。
    //        *
    //        * <p>🔴 2026-09-27：多了一道 {@code min(…, 天花板)}。
    //        * 主机侧<b>恒等</b>（天花板 = {@link #MAX_PARALLEL} = {@code Long.MAX_VALUE}，
    //        * {@code min(任何 long, Long.MAX_VALUE)} 就是它自己）⇒ <b>逐值等价于改动前</b>。
    //        * 加它的理由：宿主 {@link ParallelOverrideMachine} 的契约在模块侧需要这条不变式
    //        * （覆盖值永远不许超过机器能达到的并行数），两侧共用同一句写法才不会漂移。
    //        *
    //        * <p>🔴 <b>2026-10-02 订正</b>：「主机侧恒等」这句现在<b>只对"电力自动关着"成立</b>。
    //        * 新增的 {@link ParallelOverrideMachine#applyEnergyCap(long)} 会在开关打开时再压一道
    //        * {@code min(…, 电力上限)}；开关默认关 ⇒ <b>本节上面那句在默认态下仍然逐字为真</b>。
    //        */
    //       @Override
    //       public long getEffectiveParallel() {
    //           final long auto = Math.max(1L, getAutoParallel());
    //           if (parallelOverride > ParallelOverrideMachine.PARALLEL_AUTO) {
    //               return applyEnergyCap(Math.min(parallelOverride, auto));
    //           }
    //           return applyEnergyCap(auto);
    //       }
    //   ⛔ 作废原因：本方法与模块侧 {@code PrimordialModuleMachine#getEffectiveParallel()} 是
    //      **逐字相同的同一段逻辑**（差别只有 {@code auto} 从哪来，而它两边都由
    //      {@link #getAutoParallel()} 提供）⇒ 两份实现在本工程是最忌讳的漂移源。
    //      🔴 现由 {@link ParallelOverrideMachine#getEffectiveParallel()}（接口 default，全工程唯一一份）
    //      承担，本类不再覆写。<b>数值逐位不变</b>（判据 A/C 段逐档对过账）。
    //      ⚠️ 同一轮里 {@code getEffectiveParallel()} 的语义还多了一条用户裁决：
    //      **「电力自动」开着时，输入框里的值完全不参与**（落点
    //      {@link ParallelOverrideMachine#getEffectiveOverride()}）—— 那一句是行为变更，不是重构。

    // ═════════════════════ 🔴 电力自动（2026-10-02 新增 · 用户定方案） ═════════════════════

    /**
     * 🔴 <b>「电力自动」开关。</b>
     *
     * <h2>⛔ 2026-10-02 第二轮改判：默认 {@code false} ⇒ <b>默认 {@code true}</b>（用户点名）</h2>
     * 用户原话（逐字）：
     * <blockquote>「还有我的额外要求：<b>放置机器时默认开启这个电力自动并行</b>，
     * 并且开启这个电力自动并行之后自动禁用上面的输入框和一键最大按钮」</blockquote>
     * ⛔ 旧值 {@code false} 的理由（原文留档）：「默认 false ⇒ 关着 ⇒ 本功能对老存档一个 bit 都不改」。
     * <p>🔴 <b>代价（必须让用户知情）</b>：本功能出现之前就存在的机器（存档里没有这个键）读档时
     * 按字段默认值 <b>true</b> 生效 ⇒ <b>老存档里的主机一读档就变成"电力自动开着"</b>，
     * 并行数开始被能源仓总功率钳制。逐档说明见模块侧 {@code PrimordialModuleMachine#powerAutoParallel}
     * 的同一段（两侧逐字同款）。
     *
     * <p>注解理由与模块侧 {@code PrimordialModuleMachine#powerAutoParallel} 逐字相同
     * （{@code @Persisted} 拆装保持 + {@code @DescSynced} 客户端面板不说谎）。
     */
    @Persisted
    @DescSynced
    private boolean powerAutoParallel = true;

    /**
     * <b>本轮算出来的电力上限</b>（{@code 0} = 没有 / 尚未算过）。<b>刻意不 {@code @Persisted}</b>
     * —— 它是现算值，落盘只会在重载后留下一个过期的假数字。
     */
    @DescSynced
    private long energyParallel = ParallelOverrideMachine.ENERGY_CAP_NONE;

    /**
     * 🔴 <b>2026-10-02 第二轮新增：这个"没有上限"到底是哪一种"没有"。</b>
     * 逐档文案见 {@link ParallelOverrideMachine#energyCapReasonText}；与模块侧同一套。
     */
    @DescSynced
    private int energyCapState = ParallelOverrideMachine.EnergyCapState.NOT_EVALUATED.ordinal();

    /**
     * 🔴🔴 <b>2026-10-02 第八轮新增：本轮的「每并行耗电 k」（毫 EU/t）—— 面板那一行读的就是它。</b>
     * 用户裁决 ②：把那个一直只能靠 Jade 反推的乘数搬到界面上。与模块侧逐条同源
     * （不 {@code @Persisted}、{@code @DescSynced}、只经 {@code setEnergyCap} 三参版写入）。
     */
    @DescSynced
    private long perParallelMilliCost = ParallelOverrideMachine.ENERGY_CAP_NONE;

    @Override
    public boolean isPowerAutoParallel() {
        return powerAutoParallel;
    }

    @Override
    public void setPowerAutoParallel(boolean value) {
        if (value == powerAutoParallel) {
            return;
        }
        powerAutoParallel = value;
        // 一改开关就清掉旧上限、旧状态与旧 k（同模块侧：防止面板留着一句过期的话）。
        energyParallel = ParallelOverrideMachine.ENERGY_CAP_NONE;
        energyCapState = ParallelOverrideMachine.EnergyCapState.NOT_EVALUATED.ordinal();
        perParallelMilliCost = ParallelOverrideMachine.ENERGY_CAP_NONE;
        notifyBlockUpdate();
    }

    @Override
    public long getEnergyParallel() {
        return energyParallel;
    }

    @Override
    public long getPerParallelMilliCost() {
        return perParallelMilliCost;
    }

    /**
     * 🔴🔴 <b>主机侧的跨配方线程数 T —— 供接口层把电上限「÷T」（2026-10-02 第五轮用户裁决 ①）。</b>
     *
     * <pre>
     *   T = ShanhaiParallelBudget.crossRecipeThreadsForHost(BASE_THREADS, getAdditionalThread())
     *     = saturatedCast(128 + 0) = 128                      ← 当前取值
     * </pre>
     *
     * <h2>🔴 为什么必须和 {@code PrimordialEngineRecipeLogic#getMultipleThreads()} <u>同一个函数</u></h2>
     * 父类的预算是 {@code (long) getMaxParallel() * getMultipleThreads()}：
     * <ul>
     *   <li>{@code getMaxParallel()} → {@link #getRecipeLogicMaxParallel()} → {@code getEffectiveParallel()}
     *       → {@code applyEnergyCap(base, }{@link com.shanhai.common.machine.ParallelOverrideMachine#getEnergyCapThreads()}{@code )}</li>
     *   <li>{@code getMultipleThreads()} → {@code PrimordialEngineRecipeLogic.getMultipleThreads()}</li>
     * </ul>
     * 若两边取的 T 不同（比如这里另写一遍 {@code 128 + …}），预算就会是
     * {@code min(本机上限, 电上限÷T₁) × T₂} ⇒ <b>T₂ &gt; T₁ 时 &gt; 电上限 ⇒ 总耗电超 P</b>
     * ⇒ 两处都调 {@link ShanhaiParallelBudget#crossRecipeThreadsForHost(int, int)}（<b>唯一一份实现</b>）。
     *
     * <p>⚠️ 本方法<b>不读任何可能还没初始化的东西</b>（{@code getAdditionalThread()} 是常量返回）
     * ⇒ 构造期被调用也安全（不会 NPE、不会拿到半初始化的字段）。
     */
    @Override
    public int getEnergyCapThreads() {
        return ShanhaiParallelBudget.crossRecipeThreadsForHost(
                PrimordialEngineRecipeLogic.BASE_THREADS, getAdditionalThread());
    }

    @Override
    public ParallelOverrideMachine.EnergyCapState getEnergyCapState() {
        return ParallelOverrideMachine.EnergyCapState.values()[energyCapState];
    }

    /** 状态、数值与「每并行耗电 k」的唯一写入口（一次写三样）；全没变 ⇒ 不刷包。 */
    @Override
    public void setEnergyCap(ParallelOverrideMachine.EnergyCapState state, long value,
                             long perParallelMilliCost) {
        final ParallelOverrideMachine.EnergyCapState nextState = state == null
                ? ParallelOverrideMachine.EnergyCapState.ERROR : state;
        final long nextValue = value <= ParallelOverrideMachine.ENERGY_CAP_NONE
                ? ParallelOverrideMachine.ENERGY_CAP_NONE : value;
        final long nextCost = perParallelMilliCost <= ParallelOverrideMachine.ENERGY_CAP_NONE
                ? ParallelOverrideMachine.ENERGY_CAP_NONE : perParallelMilliCost;
        if (nextState.ordinal() == energyCapState && nextValue == energyParallel
                && nextCost == this.perParallelMilliCost) {
            return;
        }
        energyCapState = nextState.ordinal();
        energyParallel = nextValue;
        this.perParallelMilliCost = nextCost;
        notifyBlockUpdate();
    }

    @Override
    public void setEnergyParallel(long value) {
        final long next = value <= ParallelOverrideMachine.ENERGY_CAP_NONE
                ? ParallelOverrideMachine.ENERGY_CAP_NONE : value;
        if (next == energyParallel) {
            return;
        }
        energyParallel = next;
        notifyBlockUpdate();
    }

    /**
     * 主机并行上限的<b>显示值 / 实际返回值</b>（{@code Integer.MAX_VALUE} = 2147483647）。
     *
     * <p>来源见 {@link #getMaxParallel()} 的注释：用户 2026-09-22 裁决「照伪神（FOTC）填」，
     * 伪神从 gtlcore 祖类拿到的是 {@code Integer.MAX_VALUE}。
     * <p>🔴 与 {@link #MAX_PARALLEL}（2^30）<b>不是同一个数、也不该合并</b>：
     * 后者只在配方修饰链里当基础上限（{@code applyParallel(modified, host, MAX_PARALLEL)}），
     * 两者的分工在 {@link #getMaxParallel()} 的注释里写死了。
     */
    public static final int MAX_PARALLEL_DISPLAY = MAX_PARALLEL_INT;

    /**
     * 🔴 <b>并行/线程显示「无限」的哨兵值 {@code -114514}
     * —— 2026-09-21 二次改判后，它是【纯显示层】常量，不参与任何运算。</b>
     *
     * <h2>🔴 它已经<b>不再</b>是 {@code getMaxParallel()} 的返回值</h2>
     * 原先「E 项」的做法是让 {@link #getMaxParallel()} 返回这个负数，再靠 gtladditions 的
     * Jade {@code ParallelProviderMixin}（它原文对 {@code ForgeOfTheAntichrist} 就是硬编码
     * {@code putLong("parallel", -114514L)}）把抬头画成彩虹「无限」。
     * <b>但那个负数真的会被乘进并行数</b>
     * （{@code totalParallel = (long) getMaxParallel() * getMultipleThreads()}），
     * 换引擎之后会让机器静默停机（完整推导见 {@link #getMaxParallel()} 的注释）。
     * <pre>
     *   ⇒ 现在：{@link #getMaxParallel()} 返回正数 MAX_PARALLEL（= 2^30），【不返回】本常量
     *   ⇒ 「无限」改由纯显示层写：本工程的显示 mixin
     *      com.shanhai.mixin.ShanhaiInfiniteThreadDisplayMixin（已挂在 shanhai.mixin.json 的 mixins 列表）
     *      在 ParallelProvider.appendServerData 的 RETURN 处【同时】写
     *          parallel = -114514L  与  threads = -114514L
     *      —— 只改 Jade 的服务端 NBT，【不参与任何数值运算】。
     * </pre>
     *
     * <h2>为什么还是 -114514（而不是自己画彩虹）</h2>
     * 它是 <b>gtladditions 自己约定的「无限」标记</b>：{@code [源码原文]} gtladditions 3.2.8
     * {@code ParallelProviderMixin} 里
     * {@code private static final long INFINITY_FEATURE = -114514L;}；
     * 且 {@code appendTooltip} 见到 {@code parallel == -114514L} / {@code threads == -114514L}
     * 就画 {@code CommonUtils.createLanguageRainbowComponent(...)}（彩虹「无限」）。
     * 依据还包括用户原话：<b>「我们的线程应该是无限，但它显示不是无限而是一个很大的数」</b>。
     * <p>⚠️ 注意 {@code ParallelProviderMixin} 读的是 {@code threads} 为 {@code int}
     * （{@code data.m_128451_("threads")}），而哨兵按 {@code long} 写；
     * 由于 {@code -114514} 在 int 范围内，{@code getInt} 读回来仍是 {@code -114514}，
     * 判等成立（上游自己对 {@code ForgeOfTheAntichrist} 也是这么写的）。
     *
     * <h2>⚠️ 旧注释里那个「E 依赖 D」的互锁已经消失（如实留档）</h2>
     * 旧注释担心的是：{@code getMaxParallel()} 变负之后，gtlcore
     * {@code BatchProcessing.isCustomSubTickParallelized} 里那句
     * {@code realParallels > Math.max(1L, parallelMachine.getMaxParallel())}
     * （{@code Math.max(1L, -114514) == 1L}）会<b>恒为 true</b>，
     * 所以必须靠 {@link #canConfigureBatchProcessing()} / {@link #isBatchEnabled()} 把批处理关掉来消歧。
     * <b>现在 {@code getMaxParallel()} 又是正数（2^30），那个"恒为 true"根本不会发生</b>
     * ⇒ 该互锁不再需要。批处理关闭（D 项）<b>仍然保留</b>，但它现在的理由只剩
     * 「链尾 {@code duration × batchSize} 绕开绊线」与「{@code realParallels × batchSize} 的裸乘法」
     * 两条，<b>与显示哨兵无关</b>。
     */
    public static final int DISPLAY_INFINITE_PARALLEL = -114514;

    /**
     * 跨配方线程数（N4 的倍数项）。取值 {@link #MAX_PARALLEL}（= 2^30），
     * 与 {@link #MAX_PARALLEL} 那条「对旧私货的已知偏离」同一个理由。
     *
     * <h2>🔴 换引擎之后它<b>真的被读了</b>（旧注释作废留档在下面）</h2>
     * 读者是 {@link PrimordialEngineRecipeLogic#getMultipleThreads()} —— 本工程覆写了它并返回
     * 同一个值，所以本方法即使被别处读到也<b>不会</b>出现"两个来源不一致"。
     * 父类 {@code MutableRecipesLogic.calculateParallels()} 的并发行程原文是
     * {@code totalParallel = (long) getMaxParallel() * getMultipleThreads()}
     * ⇒ 两个因子都是 {@code 2^30}，积 {@code 2^60 = 1152921504606846976 < Long.MAX_VALUE = 9223372036854775807}
     * ⇒ <b>不会回绕</b>；且贪心分配里的 <b>p 会被"实际可用输入量"钳住</b>
     * （{@code IParallelLogic.getMaxParallel} → {@code limitItemAmount} 先钳到
     * {@code Long.MAX_VALUE / 单份量}，再按 {@code RecipeHandlePart} 里的真实库存算），
     * 所以这个巨大的上限<b>不会</b>变成巨大的迭代或分配。
     *
     * <h3>作废项留档（旧注释原文，只留档不生效）</h3>
     * <pre>
     *   —— 以下为 2026-09-21 换引擎之前写在代码里的原文，现已作废 ——
     *   「与 getMaxParallel() 一样，读它的只有 gtladditions 的多配方逻辑；本主机走 GTCEu 原生
     *     RecipeLogic，那个读取点不会被执行。⇒ 改这个方法的返回值不会改变并行，也不会改变线程数。」
     *   「N4 缺口之一：「跨配方线程无限」= 未达成 + 原因：……本工程不新建 mixin 基础设施，
     *     所以「跨配方同时跑 N 条不同配方」这件事在本重写工程里做不到——本方法只是个惰性占位。
     *     做的"假的显示"比不做更糟：GUI 里因此没有「线程数」这一行。」
     * </pre>
     * <b>作废原因</b>：本轮已落地多配方引擎（{@link PrimordialEngineRecipeLogic}），
     * 读取点<b>变成了生产路径</b>；且「跨配方线程」在引擎里的真实含义是
     * {@code totalParallel} 的那个乘数项，而不是旧注释里设想的"同时跑 N 条不同配方"。
     * <p>旧的「不做假显示」这条纪律<b>仍然有效</b>：Jade 那一行上线的同时
     * （{@code ShanhaiInfiniteThreadDisplayMixin}）也把哨兵写成了 gtladditions 自己的
     * 「无限」语义，不是本工程硬编码的一个假数字。
     */
    // ⛔⛔ 【2026-09-22 作废，原文留档】旧实现 → 新实现（用户裁决「照伪神填 + 显示真实数值」）
    //   ⛔ 旧原文（作废）： {@code @Override public int getAdditionalThread() { return MAX_PARALLEL; }}
    //   ⛔ 作废原因：用户裁决**照伪神（FOTC）填写**。伪神的线程数是
    //      {@code Ints.saturatedCast(128L + getAdditionalThread())} 在**它的 getAdditionalThread()
    //      返回上游默认值 0** 时得到的 **128**。**只抄公式不抄前提，就会得到 1073741952**
    //      （2^30 + 128）—— **那不是伪神的值** ⇒ 本方法必须同步回到上游默认值 `0`。
    //   ✅ 影响面已查（2026-09-22）：`getAdditionalThread()` 在本工程**没有其它调用点**
    //      —— 唯一看起来像调用的 `PrimordialMatterRecombinatorCore.java:150` 是**javadoc 引用**
    //      （解释"模块为什么用 Integer.MAX_VALUE"时引用了主机那条算式），**模块自身不调用它**
    //      ⇒ 改这里**不会牵连附属模块**（用户唯一的健康证据是"模块仍正常"）。
    @Override
    public int getAdditionalThread() {
        return 0;
    }

    /**
     * 主机电压上限 = {@code Long.MAX_VALUE}（N4 的配套项）。
     *
     * <h2>它到底改变了什么（逐条查证过，不是装饰）</h2>
     * <ol>
     *   <li><b>解除配方电压等级闸门</b>（这条是真的）：
     *       gtlcore 的 mixin {@code GTRecipeMixin.matchTickRecipe} 原文
     *       <pre>
     *         if (holder instanceof WorkableElectricMultiblockMachine machine && this.io == IO.IN) {
     *             if (… &amp;&amp; this.getEuTier() &gt; GTUtil.getFloorTierByVoltage(machine.getMaxVoltage())) {
     *                 RecipeResult.of(rlm, RecipeResult.FAIL_VOLTAGE_TIER);
     *                 return ActionResult.fail(() -&gt; null);
     *             }
     *         }
     *       </pre>
     *       {@code GTUtil.getFloorTierByVoltage(Long.MAX_VALUE)} 实算 =
     *       {@code (60 - numberOfLeadingZeros(Long.MAX_VALUE)) >> 1 = (60-1)>>1 = 29}
     *       ⇒ 任何配方（最高 14 级）都过闸，不再需要玩家堆到对应等级的能源仓。
     *       这就是「无限速度」在电压侧的配套：不解除它，高等级配方会被
     *       {@code FAIL_VOLTAGE_TIER} 挡在门外。</li>
     *   <li><b>不影响 {@code getOverclockVoltage()}</b>（这条是"负结论"，很重要）：
     *       gtlcore 的 {@code WorkableElectricMultiblockMachineMixin} 把
     *       {@code getOverclockVoltage()} <b>@Overwrite</b> 成了
     *       {@code Math.max(energyContainer.getInputVoltage(), energyContainer.getOutputVoltage())}；
     *       它读的是<b>能源仓本身</b>的电压/电流，<b>完全不经过 {@code getMaxVoltage()}</b>。
     *       ⇒ 「覆写 {@code getMaxVoltage()} 就能让机器用上无限电压去超频」是<b>不成立</b>的
     *       （任务书里标为「未验证」的那一点，此处给出结论与依据）。</li>
     *   <li><b>副作用 1</b>：{@code WorkableElectricMultiblockMachine.onStructureFormed()} 会执行
     *       {@code this.tier = GTUtil.getFloorTierByVoltage(getMaxVoltage())} ⇒ {@code tier = 29}。
     *       GUI 那行「最大配方等级」由 {@code MultiblockDisplayText.addEnergyTierLine(29)} 绘制，
     *       而它的原文是 {@code else if (tier >= 0 && tier <= 14)} ⇒ <b>该行直接不显示</b>
     *       （不是崩，是少一行；矩阵下标安全）。</li>
     *   <li><b>副作用 2</b>：{@code getTier() = 29} 会传给 {@code getMinOverclockTier()/getMaxOverclockTier()}。
     *       原版电过载修饰器会用它做
     *       {@code getRecipeEUtTier(recipe) > overclockMachine.getMaxOverclockTier()} 的判断；
     *       而本工程的主机<b>不再挂原版电过载</b>（见 {@link #applyHostRecipeModifier} 的说明），
     *       所以这条路径不会被走到。</li>
     *   <li><b>🔴 澄清任务书里那条"EUt 线性暴涨"的担忧（重要，与任务书的推断相反）</b>：
     *       本工程走的是标准 {@code ParallelLogic} 路径，而并行<b>已经被电压项限住</b>了 ——
     *       {@code EURecipeCapability.getMaxParallelRatio} 的原文是
     *       <pre>
     *         long maxVoltage = Long.MAX_VALUE;
     *         if (holder instanceof IOverclockMachine om) maxVoltage = om.getOverclockVoltage();
     *         else if (holder instanceof ITieredMachine tm) maxVoltage = tm.getMaxVoltage();
     *         long recipeEUt = RecipeHelper.getInputEUt(recipe);
     *         return recipeEUt == 0L ? Integer.MAX_VALUE : Math.abs(Ints.saturatedCast(maxVoltage / recipeEUt));
     *       </pre>
     *       而 {@code WorkableElectricMultiblockMachine implements IOverclockMachine} ⇒
     *       走的是<b>第一个</b>分支（{@code getOverclockVoltage()}，即能源仓的电压），
     *       <b>根本读不到 {@code getMaxVoltage()}</b>。
     *       于是 {@code 实际并行 ≤ 能源仓电压 / 单份 EUt} ⇒ {@code 实际 EUt = 单份 EUt × 并行 ≤ 能源仓电压}，
     *       <b>不可能越过能源仓的供电能力</b>。
     *       ⇒ 结论：{@code getMaxVoltage() = Long.MAX_VALUE} 在本工程里<b>不是</b>
     *       「防止 EUt 暴涨 / 防 {@code FAIL_NO_ENOUGH_EU_IN}」的配套（框架自己已经限住了），
     *       它真实且可查证的作用是上面第 1 条（解除 {@code FAIL_VOLTAGE_TIER} 电压等级闸门）
     *       与第 2/3 条的副作用。这一点必须如实告诉队长。</li>
     * </ol>
     */
    @Override
    public long getMaxVoltage() {
        return Long.MAX_VALUE;
    }

    // ---------------------------------------------- D：彻底关闭「批处理」（2026-09-21 队长裁决）

    /**
     * 🔴 <b>D：不许配置批处理</b>（{@code false} = 侧栏那个「批处理」按钮<b>根本不会被挂上</b>）。
     *
     * <h2>为什么必须显式覆写它（而不是"在 attachConfigurators 里删掉那个按钮"）</h2>
     * {@code [字节码]} 三条证据：
     * <ol>
     *   <li>本类运行期<b>本来就是</b> {@code IBatchMachine} —— gtlcore 的
     *       {@code WorkableElectricMultiblockMachineMixin} 声明了
     *       {@code @Mixin(WorkableElectricMultiblockMachine.class) … implements IFancyUIMachine}
     *       并带 {@code @Unique private boolean batchEnabled = false;}。
     *       本类显式 {@code implements IBatchMachine} 只是把这件事变成<b>编译期可校验</b>的；</li>
     *   <li>挂载点是<b>另一个</b> mixin {@code BatchConfiguratorMixin}：它对
     *       {@code FancyMachineUIWidget.setupFancyUI(...)} 做 {@code @Inject(at = @At("INVOKE"),
     *       target = "…IFancyUIProvider;attachConfigurators(…)V", shift = At.Shift.AFTER)}
     *       —— 即在<b>我们的 {@code attachConfigurators} 返回之后</b>，调
     *       {@code IBatchMachine.attachBatchConfigurator(configuratorPanel, machine)}。
     *       ⇒ <b>结构上不可能在它之前把按钮删掉</b>，而且 {@code ConfiguratorPanel} 也没有
     *       移除单个 configurator 的 API（{@code clear()} 会把我们自己的球体风格 + 配方最短耗时一起清掉）。</li>
     *   <li>{@code IBatchMachine.attachBatchConfigurator} 的<b>唯一闸门</b>就是本方法：
     *       <pre>
     *         public static void attachBatchConfigurator(ConfiguratorPanel configuratorPanel,
     *                                                    WorkableElectricMultiblockMachine machine) {
     *             if (!(machine instanceof IBatchMachine)
     *                     || !((IBatchMachine) machine).canConfigureBatchProcessing()) {
     *                 return;                          // ← 这里返回 = 按钮不挂
     *             }
     *             configuratorPanel.attachConfigurators(new IFancyConfiguratorButton.Toggle(…));
     *         }
     *       </pre>
     *       ⇒ 返回 {@code false} 是<b>唯一正确路径</b>。</li>
     * </ol>
     *
     * <h2>🔴 去掉它同时消掉的两个副作用（写进注释，因为这是"去掉"的真实收益）</h2>
     * <pre>
     *   ① 链尾的 duration × batchSize：{@code BatchProcessing} 会在<b>配方修饰链之后</b>把
     *      duration 再乘一次批处理点数（侧栏写 20、批处理 5 点 ⇒ 实际 100）——
     *      而本工程的 {@code auditDuration} 绊线挂在链内，<b>够不到链尾那一次乘法</b>，
     *      也就是说批处理开着时"主机时长 == N6 目标（A 口径 T）"这条不变式其实会被悄悄破坏。
     *   ② {@code setRealParallels(realParallels × batchSize)}：这是<b>无饱和的 int 乘法</b>，
     *      配合本工程 2^30 量级的并行有溢出成负数的风险。
     *   ⇒ 批处理关掉后，这两条一并消失，"主机时长 == N6 目标（A 口径 T）"重新成为可证明的性质。
     * </pre>
     */
    // ═════════════ D 项：主机【继续禁用】批处理（用户 2026-09-22 纠正：只回滚模块的批处理） ═════════════
    // 🔴 2026-09-22 过程留档：本轮曾按队长一条**错误指令**把这 4 个覆写撤掉，随后用户纠正
    //    「**主机的批处理是要删去的啊，只是回滚模块的批处理**」⇒ **已按原样恢复**。
    //    （旧留档文字里那句「`implements IBatchMachine` 的声明保留」在本恢复后**重新成立**。）
    // ⚠️ 撤回期间的那一版**从未构建、从未部署**（纪律：未 build / 未部署）⇒ 用户手上那版没受影响。

    @Override
    public boolean canConfigureBatchProcessing() {
        return false;
    }

    @Override
    public boolean supportsBatchProcessing() {
        return false;
    }

    @Override
    public boolean isBatchEnabled() {
        return false;
    }

    @Override
    public void setBatchEnabled(boolean enabled) {
        // 有意为空：本机不参与批处理，见 canConfigureBatchProcessing() 的说明。
    }

    /**
     * LDLib syncdata 的字段持有者。
     *
     * <p>🔴 <b>加 {@code @Persisted}/{@code @DescSynced} 字段就必须同时声明它</b>：
     * LDLib 的 {@code ManagedFieldHolder} 是按类登记的，父类的 holder 里没有本类的字段。
     * 漏了这一步的表现是<b>编译通过、游戏不报错、字段永不落盘也永不同步</b>（切换按钮点了没反应）
     * —— 属于本项目最忌讳的"静默失败"。
     *
     * <p>证据：{@code javap ManagedFieldHolder} → {@code public ManagedFieldHolder(Class<? extends IManaged>, ManagedFieldHolder)}；
     * 且上游同为「本类 + 直接父类的 holder」这一形状。
     *
     * <p>⚠️ 第二参<b>必须取"链上最近的那个 holder"，不能跳级</b>。实测（{@code javap -p} 逐级查）：
     * <pre>
     *   MetaMachine                       有 MANAGED_FIELD_HOLDER
     *   MultiblockControllerMachine       有（覆写 MetaMachine 的）
     *   WorkableMultiblockMachine         有（再覆写一次）
     *   WorkableElectricMultiblockMachine 没有自己的 —— 继承 WorkableMultiblockMachine 那个
     * </pre>
     * 所以这里写 {@code WorkableElectricMultiblockMachine.MANAGED_FIELD_HOLDER}：
     * 它当前解析到 {@code WorkableMultiblockMachine} 的那一个（即链上最近者，正确），
     * 且将来 GTCEu 真给 {@code WorkableElectricMultiblockMachine} 加了自有 holder 时，
     * 本行会<b>自动跟着走</b>（写成 {@code MultiblockControllerMachine.MANAGED_FIELD_HOLDER} 就会跳级、
     * 静默丢掉中间层的字段）。
     */
    private static final ManagedFieldHolder MANAGED_FIELD_HOLDER = new ManagedFieldHolder(
            PrimordialOmegaEngineMachine.class, WorkableElectricMultiblockMachine.MANAGED_FIELD_HOLDER);

    @Override
    public ManagedFieldHolder getFieldHolder() {
        return MANAGED_FIELD_HOLDER;
    }

    /**
     * <b>给子类用的父 holder 读取口</b>（2026-10-23 新增，唯一调用点是归墟视界枢核）。
     *
     * <p>子类的 {@code @Persisted}/{@code @DescSynced} 字段要落盘/同步，必须覆写
     * {@code getFieldHolder()} 返回 {@code new ManagedFieldHolder(子类.class, 父holder)}，
     * 而字段 {@link #MANAGED_FIELD_HOLDER} 是 {@code private}。
     * <p>⚠️ 子类<b>不许</b>图省事写成 {@code WorkableElectricMultiblockMachine.MANAGED_FIELD_HOLDER} ——
     * 那会<b>跳级</b>、把本类的字段（{@code matterBonusSlot} / {@code limitedDuration} / 球体风格…）
     * 从子类的 holder 链上整段丢掉，表现同样是"编译通过、游戏不报错、字段永不落盘"。
     *
     * <p>本方法<b>纯读一个静态常量</b>，对既有行为零影响。
     */
    public static ManagedFieldHolder managedFieldHolder() {
        return MANAGED_FIELD_HOLDER;
    }

    /**
     * 中心球体的渲染风格（{@link PrimordialSphereStyle} 的 {@code ordinal}）。
     *
     * <p>TESR 只跑在客户端，所以必须 {@code @DescSynced} 才能让玩家在 GUI 里的切换实时反映到渲染；
     * {@code @Persisted} 让它在拆装/重载后保持。
     * 上游同款（{@code DShanhaiMachines} 的 {@code PrimordialOmegaEngineMachine:82-84}）。
     */
    @Persisted
    @DescSynced
    private int sphereStyle = PrimordialSphereStyle.UNIVERSE.ordinal();

    // ═══════════════════ 中子星渲染面板（2026-09-22 用户裁决「A + C 都要」） ═══════════════════

    /**
     * 🔴 <b>已停用（2026-10-18）</b>：用户要求去掉中子星外观面板的模块等级门槛 ⇒
     * {@link #canControlStarRender()} 改为恒 {@code true}，本常量<b>不再参与任何判据</b>，
     * 保留仅为记录当初那个口径（⛔ 不要再拿它当门）。
     *
     * <p>允许调整中子星渲染的<b>最低专属槽等级</b> = <b>15</b> = 物质创造模块。
     *
     * <p>用户原话：「当主机填入 <b>64 个物质创造模块或者更高等级的模块</b> 时允许玩家通过这个面版来控制
     * 中子星的大小、颜色（甚至可以是彩色）」。
     * <p>{@code 15} 的来历是本工程<b>唯一</b>那份等级表：{@code PrimordialModuleMachine.MODULE_LEVELS} 里
     * {@code shanhai:material_creation_module → 15}（⛔ 不是 16 —— 16 是现实锚点模块、17 是创始现实修改模块）。
     * ⇒ {@code >= 15} 一次性覆盖「物质创造 / 现实锚点 / 创始现实修改」三种，即"或更高等级"。
     * <p>🔴 <b>「64 个」不在这里判</b>：那个口径的唯一实现在 {@code computeMatterBonusLevel()}
     * （{@code stack.getCount() == 64}），本常量当初只判"够不够格调外观"。
     */
    public static final int STAR_PANEL_MIN_MODULE_LEVEL = 15;

    /** 手动半径下限 = 13 = 上游 {@code AntichristRenderProfile.BASE_STAR_RADIUS}（= 门控未生效时的半径）。 */
    public static final int STAR_RADIUS_MIN = 13;

    /**
     * 手动半径上限 = <b>43</b>。
     *
     * <p>🔴 <b>为什么不是 44（2026-09-23 闪退事故的直接修正）</b>：上游 {@code AntichristBeamRenderer.getStartAngle}
     * 里有 {@code asin(starRadius / sqrt(44.5² + 5.0²))} = {@code asin(r / 44.7805)}，
     * <b>r &gt; 44.7805 就返回 NaN</b>（NaN 传到 {@code packAlpha} 的 {@code roundToInt} ⇒ 客户端闪退，
     * 实证：{@code crash-2026-09-23_19.53.02-client.txt}）。而**呼吸脉动是乘在基数之后的**：
     * <pre>
     *   44 × 1.035 = 45.54 > 44.7805  ⇒ NaN ⇒ 闪退   ← 上一版（上限 44）踩的就是这一脚
     *   43 × 1.035 = 44.505 < 44.7805 ⇒ 安全
     * </pre>
     * <p>⚠️ <b>这是对早先「13 ~ 44」那次锁定的刻意偏离</b>：44 会崩，所以上限收到 43。
     * 渲染侧另有一道 {@code SAFE_MAX_STAR_RADIUS = 44.6} 兜底钳位（钳在脉动之后），
     * 取 43 是为了让脉动在最高档也<b>不被削平</b>（玩家不会看到"到顶就不呼吸"的怪现象）。
     */
    public static final int STAR_RADIUS_MAX = 43;

    /** 渲染档位：跟随等级（<b>默认</b>；此时用哪套自动配色由 {@link #starPalette} 决定）。 */
    public static final int STAR_MODE_FOLLOW_LEVEL = 0;

    /** 渲染档位：手动（尺寸 + 单色色相都由玩家定）。 */
    public static final int STAR_MODE_MANUAL = 1;

    /** 未设置哨兵：半径/色相的覆盖值等于它时，渲染仍走等级的既有公式。 */
    public static final int STAR_OVERRIDE_UNSET = -1;

    /**
     * 自动配色档：<b>原光谱 7 档（橙红 → 中段近白 → 冷蓝）—— 这是默认档</b>。
     *
     * <p>🔴 <b>它是"跟随等级"档的默认配色，别动</b>：用户裁决原文「<b>A · 默认用原来的 7 档（推荐）</b>
     * —— 回到你之前定的那个（Lv.1 橙红 → 中段近白 → Lv.17 冷蓝）；彩虹只当手动模式里的一个选项，
     * 不抢默认位」。并对应客户端验收清单第 ⑩ 条（用户亲自定的）：
     * 「专属槽满 64 后：Lv.1 偏橙红 → 中段近白 → Lv.17 冷蓝；不再随时间变化」。
     */
    public static final int STAR_PALETTE_SPECTRAL = 0;

    /** 自动配色档：彩虹 7 档（红 → 黄绿 → 青 → 蓝 → 紫 → 品红）。<b>可选，不抢默认位。</b> */
    public static final int STAR_PALETTE_RAINBOW = 1;

    // ───────────────── 彩虹变化周期（2026-09-23 新增；只作用于「彩虹」档） ─────────────────

    /**
     * <b>彩虹不推进（静态）</b> —— 🔴 <b>这是默认值，用户已拍板。</b>
     *
     * <p>选它当默认的理由只有一条，但足够硬：<b>默认路径必须与"加这个功能之前"逐位相同</b>。
     * 2026-09-22 用户裁定原话：「<b>9的话我不希望光束的颜色随着时间变化，
     * 而希望它随着物质模块的等级变化</b>」⇒ 默认的「光谱」档<b>一个字都不许动</b>；
     * 只有当玩家<b>手动切到「彩虹」</b>并且<b>手动调过周期</b>时，颜色才随周期推进。
     * <p>口径澄清（用户 2026-09-23 拍板「A · 只让【彩虹】档随时间动」）：
     * <b>「彩虹要不要随时间动」与 2026-09-22 那条裁定不冲突</b> ——
     * 那条说的是<b>默认档</b>，本机制只作用于玩家主动选的彩虹档，且默认周期就是"不变化"。
     */
    public static final int STAR_RAINBOW_PERIOD_OFF = 0;

    /** 周期的合法上限（tick）。{@code 12000} tick = 10 分钟。 */
    public static final int STAR_RAINBOW_PERIOD_MAX = 12000;

    /**
     * <b>周期档位表（tick）</b>：{@code 0} = 不变化；其余 = 彩虹<b>往返一轮</b>（红→品红→红）所需的 tick 数。
     * 20 tick = 1 秒。共 8 档，面板上那个循环按钮点一下走一格。
     *
     * <pre>
     *   0     不变（静态）      200   10 秒
     *   20    1 秒              600   30 秒
     *   60    3 秒              1800  90 秒
     *                          6000  5 分钟
     *                          12000 10 分钟
     * </pre>
     * <p>🔴 <b>跨度是刻意拉开的</b>：从"看得出在转"（1 秒）到"缓慢流动"（10 分钟）。
     * <p>⚠️ <b>本按钮刻意【不做】 Shift/Ctrl 加速</b>：它点一下就是"跳下一格"，
     * 再叠加速会变成"跳过好几格"，玩家反而定位不到想要的档（加速只用在「色相 −/+」上）。
     * <p>⚠️ 数组是 {@code public static final} 而<b>不是不可变类型</b>（Java 数组天然可变）——
     * 这里接受这个暴露面：它只在 common 侧被读，且本工程禁用外部改写入口；
     * 换成 {@code List.of} 会让上面"点一下走一格"的取模逻辑多一层装箱，收益为零。
     */
    public static final int[] STAR_RAINBOW_PERIODS = {0, 20, 60, 200, 600, 1800, 6000, 12000};

    /**
     * 渲染档位（{@code STAR_MODE_FOLLOW_LEVEL} / {@code STAR_MODE_MANUAL}）。
     *
     * <p>🔴 默认 {@code 0} ⇒ <b>不动面板时渲染行为与加面板之前【逐位相同】</b>（零回归风险的默认值）。
     */
    @Persisted
    @DescSynced
    private int starRenderMode = STAR_MODE_FOLLOW_LEVEL;

    /**
     * <b>自动配色档</b>（{@code STAR_PALETTE_SPECTRAL} = 原光谱（默认） / {@code STAR_PALETTE_RAINBOW} = 彩虹）。
     *
     * <p>🔴 默认 {@code STAR_PALETTE_SPECTRAL} ⇒ 与"加面板之前"逐位相同。
     * 与 {@link #starRenderMode} 同款两件套：{@code @Persisted}（存档重载后保持）+ {@code @DescSynced}
     * （渲染在客户端，没有它客户端的 TESR 读不到玩家选的档）。
     *
     * <p>⚠️ <b>它只在「跟随等级」档有意义</b>：手动档的颜色是一根色相环，与这里无关
     * （所以 {@link #toggleStarPalette()} 在手动档里直接返回 —— 见该方法的 javadoc）。
     */
    @Persisted
    @DescSynced
    private int starPalette = STAR_PALETTE_SPECTRAL;

    /**
     * <b>彩虹变化周期</b>（tick；{@code 0} = 不变化）。
     *
     * <p>语义 = <b>从红端走到品红端、再走回红端，一个完整往返所需的 tick 数</b>
     * （不是"绕环一圈"—— 彩虹表的首尾是红 {@code 0xFF2626} 与品红 {@code 0xFF26FF}，
     * <b>不是闭环</b>，所以推进曲线用三角波往返而不是取模，否则每轮会出现一次品红硬跳回红的断裂）。
     *
     * <p>🔴 默认 {@link #STAR_RAINBOW_PERIOD_OFF}（= 0 = 不变化）⇒ 与"加这个按钮之前"逐位相同。
     * 与另外三个旋钮同款两件套：{@code @Persisted}（存档重载后保持）+ {@code @DescSynced}
     * （相位在客户端逐帧算，但周期值只有点击时变，走低频同步就够 —— <b>没有逐 tick 发包</b>）。
     *
     * <p>⚠️ <b>它只在「跟随等级 + 彩虹」档有意义</b>：手动档的颜色是一根色相环、光谱档走静态 7 档，
     * 两种情况下推进它都<b>看不到任何效果</b> ⇒ {@link #cycleRainbowPeriod()} 直接拒绝，
     * 面板上对应按钮置灰并把原因写在标签里（本项目明令不做的"按了没反应的假开关"）。
     *
     * <p>边界：服务端 setter（{@code cycleRainbowPeriod} 只走档位表所以永远合法）之外没有别的入口，
     * 渲染侧 {@code trianglePhase} 另有一道"非正周期直接回静态"的兜底（防除零）。
     */
    @Persisted
    @DescSynced
    private int starRainbowPeriodTicks = STAR_RAINBOW_PERIOD_OFF;

    /**
     * 手动半径（{@code STAR_RADIUS_MIN..STAR_RADIUS_MAX}；{@code -1} = 未设置）。
     *
     * <p>⚠️ 注意 {@code 0} 不是合法值 —— 于是"未设置"用 {@code -1} 表达，
     * 而渲染侧的判据写成 {@code > 0}（见 {@code PrimordialOmegaEngineRenderer#starRadiusOverrideOf}）。
     */
    @Persisted
    @DescSynced
    private int starRadiusOverride = STAR_OVERRIDE_UNSET;

    /** 手动色相（{@code 0..359}；{@code -1} = 未设置）。饱和度/明度由渲染侧固定，不开放。 */
    @Persisted
    @DescSynced
    private int starHueOverride = STAR_OVERRIDE_UNSET;

    /**
     * <b>「始终渲染为工作状态」开关</b>（GUI 左侧按钮列的第 7 个按钮）。
     *
     * <p>用户交办原话：「<b>是否始终渲染为工作状态</b>」——打开后，主机的渲染状态
     * <b>不再依赖机器是否在运行</b>，视觉上始终是"工作状态"。
     *
     * <h2>🔴 它接在渲染链的哪一点上（唯一的一处）</h2>
     * 渲染链里"是否工作状态"<b>只有一个推导点</b>：
     * <pre>
     *   PrimordialOmegaEngineRenderer#getSmoothTick(machine, partialTick)   // :48-53
     *       :49  if (machine instanceof PrimordialOmegaEngineMachine poe
     *                 &amp;&amp; poe.getRecipeLogic().isWorking())  →  RenderUtil.getSmoothTick(...)
     *       :52  return 0f;                                   // 停机 ⇒ 恒 0
     *              ↓
     *   AbstractRingRenderer#render(...)                          // :82-118
     *       :111 boolean isWorking = smoothTick &gt; 0;            ← ★【唯一推导点】
     *       :114 renderAllRings(..., isWorking, ...)  → :149 if (isWorking) 轨道环是否转
     *       :117 renderSpecialEffects(..., isWorking, ...) → PrimordialOmegaEngineRenderer:72
     *              ↓
     *   PrimordialNeutronStarSphereRenderer#enqueue(..., isWorking, ...)
     *       :177 beamAlpha = isWorking ? BEAM_ALPHA : 0.0f     ← ★【等离子体光束亮/灭的唯一开关】
     * </pre>
     * ⇒ 本开关<b>只改 {@code getSmoothTick} 那一处</b>（{@code isWorking() || isStarAlwaysWorking()}），
     * <b>光束与轨道环同时生效</b>；<b>不碰</b>半径/脉动那两处
     * （{@code PrimordialNeutronStarSphereRenderer:304/:320-321} 有明文注释说明它们<b>刻意</b>不读
     * {@code isWorking}）⇒ 半径钳位（{@code STAR_RADIUS_MAX = 43}）与 {@code SAFE_MAX_STAR_RADIUS}
     * 的 NaN 兜底一行都没动。
     *
     * <h2>为什么是"机器状态"而不是"只影响我自己"</h2>
     * 与 {@link #sphereStyle} 同款两件套：{@code @Persisted}（拆装/存档重载后保持）+
     * {@code @DescSynced}（渲染在客户端，没有它客户端的 TESR 读不到玩家按的开关）。
     * ⇒ 写在 tooltip 里的说法<b>必须</b>是「所有人都会看到（会随存档保存）」——
     * 本工程已经因为 ④ 球体风格开关的旧 tooltip 写「只影响你自己的画面」而订正过一次，
     * 那条口径<b>不能</b>在这里重犯（见 {@code sphereStyleTooltips} 的 2026-09-22 订正）。
     *
     * <h2>默认值 = false（关）</h2>
     * 关掉时 {@code getSmoothTick} 的判据与加这个按钮之前<b>逐位相同</b>（零回归风险的默认值），
     * 与 {@link #starRenderMode} / {@link #starPalette} / {@link #starRainbowPeriodTicks} 同一条纪律。
     */
    @Persisted
    @DescSynced
    private boolean starAlwaysWorking = false;

    /**
     * <b>外观面板的启用判据 —— 2026-10-18 起恒为 {@code true}</b>（用户要求：外观面板不再受模块等级限制）。
     *
     * <p>签名刻意保留：服务端 5 个 setter 与渲染侧 4 处覆盖取值仍<b>共用这一个方法</b>调用它
     * （全工程唯一的汇合点）⇒ 恒 {@code true} 就把那 9 处判据一起放开了，不需要逐个删。
     * 等级不再决定"能不能调"，<b>只</b>决定「跟随等级」档的自动大小/颜色
     * （{@link #moduleSlotBonus()} / {@code computeMatterBonusLevel()} 一个字未动）。
     *
     * <p>⚠️ 历史：旧实现是 {@code moduleSlotBonus() >= STAR_PANEL_MIN_MODULE_LEVEL}
     * （专属槽放满 64 个 Lv.15+ 模块才开门）；该常量保留但已不参与任何判据。
     */
    public boolean canControlStarRender() {
        return true;
    }

    public int getStarRenderMode() {
        return starRenderMode;
    }

    public boolean isStarRenderManual() {
        return starRenderMode == STAR_MODE_MANUAL;
    }

    public int getStarRadiusOverride() {
        return starRadiusOverride;
    }

    public int getStarHueOverride() {
        return starHueOverride;
    }

    public int getStarPalette() {
        return starPalette;
    }

    /** 彩虹变化周期（tick；{@code 0} = 不变化 = 默认）。渲染侧只读它，不读别的。 */
    public int getStarRainbowPeriodTicks() {
        return starRainbowPeriodTicks;
    }

    /**
     * 切换「自动配色：原光谱 ⇄ 彩虹」。
     *
     * <p>🔴 <b>默认是原光谱</b>（{@link #STAR_PALETTE_SPECTRAL}）—— 用户裁决「彩虹只当手动模式里的一个选项，
     * 不抢默认位」。
     *
     * <p>⚠️ <b>手动档里这个方法直接返回</b>：手动档的颜色来自一根色相环
     * （{@code starHueOverride}），与"7 档自动配色"没有关系。留着它能改但看不到任何效果，
     * 就成了本项目明令避免的"按了没反应的假开关"——所以宁可拒绝，并在面板上把状态写成
     * 「手动档不用」（见 {@code StarRenderConfigurator} 的配色按钮标签）。
     */
    public void toggleStarPalette() {
        if (!canControlStarRender() || isStarRenderManual()) {
            return;
        }
        starPalette = starPalette == STAR_PALETTE_SPECTRAL ? STAR_PALETTE_RAINBOW : STAR_PALETTE_SPECTRAL;
        logStarRenderState("toggle_palette");
        notifyBlockUpdate();
    }

    /**
     * 循环「彩虹变化周期」：跳到 {@link #STAR_RAINBOW_PERIODS} 的<b>下一格</b>（点满一圈回到"不变"）。
     *
     * <p>三道闸门（与 {@link #toggleStarPalette()} 同款，多一道"必须彩虹档"）：
     * <ol>
     *   <li>{@code canControlStarRender()} —— 外观门控已停用（恒 {@code true}，2026-10-18），此行保留不删；</li>
     *   <li>{@code isStarRenderManual()} —— 手动档颜色走色相环，推进周期<b>看不到任何效果</b>；</li>
     *   <li>{@code starPalette == STAR_PALETTE_RAINBOW} —— 光谱档是静态 7 档，同理无效。</li>
     * </ol>
     * <p>🔴 <b>不设 setter、不收网络包里的任意整数</b>：本方法只按档位表取下一格，
     * 所以 {@code starRainbowPeriodTicks} 永远落在 {@link #STAR_RAINBOW_PERIODS} 的元素上
     * （{@code 0..STAR_RAINBOW_PERIOD_MAX} 内的合法值由此保证，不需要额外钳位）。
     * <p>⚠️ <b>本按钮刻意不带 Shift/Ctrl 加速</b> —— 见 {@link #STAR_RAINBOW_PERIODS} 的 javadoc。
     */
    public void cycleRainbowPeriod() {
        if (!canControlStarRender() || isStarRenderManual()
                || starPalette != STAR_PALETTE_RAINBOW) {
            return;
        }
        final int idx = indexOfPeriod(starRainbowPeriodTicks);
        starRainbowPeriodTicks = STAR_RAINBOW_PERIODS[(idx + 1) % STAR_RAINBOW_PERIODS.length];
        logStarRenderState("cycle_rainbow_period");
        notifyBlockUpdate();
    }

    /**
     * 在 {@link #STAR_RAINBOW_PERIODS} 里找 {@code period} 所在的下标。
     *
     * <p>找不到（合法值只可能是档位表里的，但存档可能来自更早的版本）时返回<b>比它小的最大档</b>的下标，
     * 于是"下一格"是从它<b>之后</b>的那一档开始 —— 不会跳过任何档、也不会算出负下标；
     * 比所有档都大时返回最后一档的下标（下一格回到 {@code 0} = 不变，语义上正好是"收尾"）；
     * 比所有档都小（负数）时返回 {@code 0}（下一格 = 第一档，即 20 tick）。
     */
    private static int indexOfPeriod(int period) {
        for (int i = STAR_RAINBOW_PERIODS.length - 1; i >= 0; i--) {
            if (STAR_RAINBOW_PERIODS[i] <= period) {
                return i;
            }
        }
        return 0;
    }

    /**
     * 切换「跟随等级 ⇄ 手动」。
     *
     * <p><b>首次切到手动时把两个覆盖值"播种"成当前等级对应的值</b>，否则玩家一按开关画面就会
     * 从他的球突然跳到下限（13.0）—— 那是"按了一下就坏了"的观感。
     * <p>播种用的是 {@link #estimateLevelRadius()}（见其 javadoc：它只是给 UI 用的估算，
     * 渲染永远以客户端渲染器的常量为准）。
     */
    public void toggleStarRenderMode() {
        if (!canControlStarRender()) {
            return;
        }
        if (starRenderMode == STAR_MODE_MANUAL) {
            starRenderMode = STAR_MODE_FOLLOW_LEVEL;
        } else {
            starRenderMode = STAR_MODE_MANUAL;
            if (starRadiusOverride < STAR_RADIUS_MIN) {
                starRadiusOverride = estimateLevelRadius();
            }
            if (starHueOverride < 0) {
                starHueOverride = estimateLevelHue();
            }
        }
        logStarRenderState("toggle_mode");
        notifyBlockUpdate();
    }

    /**
     * 手动调半径（步长 ±1，面板上就是一对 − / + 按钮）。
     *
     * <p>两条纪律（与 {@code setLimitedDuration} 完全同款）：
     * <ol>
     *   <li><b>服务端兜底钳位</b>到 {@code [13, STAR_RADIUS_MAX]}：网络包不保证只带合法值，
     *       而半径越过上游 {@code asin} 的域（44.7805）会让光束几何变 NaN ⇒ <b>客户端闪退</b>
     *       （2026-09-23 真事故，见 {@link #STAR_RADIUS_MAX}）；</li>
     *   <li><b>门控不足时拒写</b>（⚠️ 外观门控已于 2026-10-18 停用 ⇒ 本条对等级不再生效，钳位仍然有效）：
     *       客户端可以伪造 {@code writeClientAction}，所以"条件不满足"必须服务端拦，
     *       UI 上的变灰只是提示、不是安全边界。</li>
     * </ol>
     */
    public void stepStarRadius(int delta) {
        if (!canControlStarRender()) {
            return;
        }
        int base = starRadiusOverride < STAR_RADIUS_MIN ? estimateLevelRadius() : starRadiusOverride;
        int next = clampStarRadius(base + delta);
        if (next == starRadiusOverride) {
            return;
        }
        starRadiusOverride = next;
        // 🔴 2026-09-23 新增：把半径写进日志。
        // 起因：闪退事故复盘时发现【日志里没有任何半径记录】（本类与面板一行日志都没写），
        // 于是"用户到底把半径设到多少"只能靠推断 ⇒ 这条信息的代价太高了。
        // 每次用户点击最多 1 行（值不变时上面就 return 了），不刷屏。
        logStarRenderState("step_radius");
        notifyBlockUpdate();
    }

    /**
     * 把当前外观旋钮写进 {@code [SHANHAI-SPEC]} 日志（沿用本工程既有的日志前缀与写法）。
     *
     * <p>只在<b>用户操作导致状态变化</b>时调用（每点一次最多 1 行），不在渲染/每 tick 路径上调用。
     * 记的数就是渲染侧真正读的那几个：{@code mode}（跟随等级/手动）、{@code radius}（手动半径，
     * {@code -1} = 未设置）、{@code hue}（手动色相，{@code -1} = 未设置）+ {@code palette}（自动配色档）
     * + {@code period}（彩虹变化周期 tick，{@code 0} = 不变化）。
     *
     * <p>🔴 <b>2026-09-23 有意变更：format 串新增 {@code period={}} 一个字段 ⇒ 本行日志的字段集合变了。</b>
     * 这不是笔误也不是格式回退 —— 加了"彩虹变化周期"这个新旋钮之后，
     * 不记它就等于下次复盘时"用户到底把周期设到多少"又要靠推断
     * （{@code stepStarRadius} 那次闪退的教训就是"出事时日志里没有任何半径记录"）。
     * <p>⚠️ <b>依赖本行的日志解析/对账脚本需要跟着改</b>：字段从
     * {@code mode= radius= hue= palette= level=} 变成 {@code mode= radius= hue= palette= period= level=}
     * （新字段插在 {@code palette} 与 {@code level} 之间，尾部 {@code level={}} 仍在最后）。
     */
    private void logStarRenderState(String trigger) {
        ShanhaiMod.LOGGER.info(
                "[SHANHAI-SPEC] star_render {} machine=({},{},{}) mode={} radius={} hue={} palette={} period={} level={}",
                trigger, getPos().getX(), getPos().getY(), getPos().getZ(),
                starRenderMode, starRadiusOverride, starHueOverride, starPalette,
                starRainbowPeriodTicks, moduleSlotBonus());
    }

    /**
     * 手动调色相（步长由面板决定，本工程用 ±15°）。
     *
     * <p>色相绕环 ⇒ 用取模而不是钳位（359 + 15 = 14，不是 359）。
     */
    public void stepStarHue(int delta) {
        if (!canControlStarRender()) {
            return;
        }
        int base = starHueOverride < 0 ? estimateLevelHue() : starHueOverride;
        int next = Math.floorMod(base + delta, 360);
        if (next == starHueOverride) {
            return;
        }
        starHueOverride = next;
        logStarRenderState("step_hue");
        notifyBlockUpdate();
    }

    private static int clampStarRadius(int radius) {
        return Math.max(STAR_RADIUS_MIN, Math.min(radius, STAR_RADIUS_MAX));
    }

    /**
     * <b>仅供 GUI 播种用的</b>等级 → 半径估算（四舍五入到整数）。
     *
     * <p>🔴 公式与客户端渲染器 {@code PrimordialNeutronStarSphereRenderer#baseRadiusFor} 一致
     * （{@code 13.0 + (35.1 - 13.0) × level / 17}），但<b>权威值在客户端那一侧</b>
     * （它的 {@code BASE_RADIUS} 取自 gtladditions 的 {@code AntichristRenderProfile.BASE_STAR_RADIUS}，
     * common 侧<b>不许</b>引用那个客户端类）。两边万一漂移，后果只是"首次开手动档时的起点差一点点"，
     * 不会影响渲染本身 —— 这是刻意选的可容忍退化。
     */
    private int estimateLevelRadius() {
        int level = Math.min(moduleSlotBonus(), 17);
        if (level <= 0) {
            return STAR_RADIUS_MIN;
        }
        return clampStarRadius(Math.round(13.0F + (35.1F - 13.0F) * (level / 17.0F)));
    }

    /**
     * <b>仅供 GUI 播种用</b>的等级 → 色相估算：与彩虹档的 7 个停靠点同构
     * （红 0° → 品红 300°，{@code level / 17} 线性）。
     */
    private int estimateLevelHue() {
        int level = Math.max(0, Math.min(moduleSlotBonus(), 17));
        return Math.round(300.0F * (level / 17.0F));
    }

    /**
     * 主机专属「物质模块槽」（1 格，只收 17 个物质模块，容量 64 —— 即 {@code ItemStackTransfer} 的默认
     * slot limit）。
     *
     * <h2>为什么 {@code IO.NONE, IO.NONE}</h2>
     * 它是<b>玩家手动投放的专属槽</b>，不是自动化接口：
     * {@code handlerIO == NONE} ⇒ 不参与任何配方输入/输出（GTCEu 的配方逻辑按 handlerIO 过滤，
     * 这正是模块侧三个槽的用法）；{@code capabilityIO == NONE} ⇒ 不对外暴露物品能力，管道/总线塞不进来。
     * 两条都取 NONE，门控的输入就<b>只可能来自玩家的 GUI 操作</b>，不存在"被自动化改掉"的意外路径。
     *
     * <p>{@code MachineTrait} 构造器里会自动 {@code machine.attachTraits(this)}
     * （{@code javap -c MachineTrait.<init>} 第 30-32 条指令），所以不需要我们再手动注册 ——
     * 注册之后持久化（{@code @Persisted storage}）与能力暴露都按 GTCEu 的既有机制走。
     *
     * <p><b>容量 64 的依据</b>：{@code javap -c ItemStackTransfer.getSlotLimit} = {@code bipush 64} +
     * {@code ireturn}（常量 64），本槽不覆写它。
     *
     * <h2>🔴 {@code @Persisted} 是本轮（任务 2，2026-09-24）补的 —— 没有它就是「重进游戏槽里物品消失」的病根</h2>
     * 这个字段此前<b>一个注解都没有</b>，而 {@code NotifiableItemStackHandler} 是
     * {@code MachineTrait}，<b>trait 的字段不会自动落盘</b>：
     * <pre>
     *   · LDLib 的持久化根是 {@code MetaMachineBlockEntity.managedStorage}（{@code MultiManagedStorage}）；
     *     全 {@code libs/}（33 个 jar）里 {@code MultiManagedStorage.attach} 只有【一个】调用点，
     *     即 {@code MetaMachine.<init>} 那句 {@code holder.getRootStorage().attach(this.getSyncStorage())}
     *     ⇒ <b>根里只有机器自己那一份 storage</b>，trait 的 storage 不在其中。
     *   · 兜底通道也是空的：{@code MetaMachine.saveCustomPersistedData} 只把 tag 转给每个 trait 的
     *     {@code saveCustomPersistedData}，而 GTCEu 的
     *     {@code com.gregtechceu.gtceu.api.machine.trait.MachineTrait#saveCustomPersistedData}
     *     是 {@code 0: return}（空实现），{@code NotifiableItemStackHandler} 也不覆写它。
     * </pre>
     * ⇒ 结果就是「{@code @Persisted int matterBonusLevel} 这类<b>标量留下了</b>，而<b>物品栈从来没有进过 NBT</b>」。
     *
     * <p><b>正确写法来自 GTCEu 自己</b>：{@code javap -p -v
     * com.gregtechceu.gtceu.api.machine.WorkableTieredMachine} 里
     * {@code importItems / exportItems / importFluids / exportFluids / importComputation / exportComputation}
     * <b>每个 handler 字段上都标着 {@code @Persisted}</b>（LDLib 的 {@code IManagedAccessor}
     * 是 readonly accessor：读进字段里<b>已有的</b>那个对象，所以这些字段都是 {@code final}）。
     * 本字段照此办理，<b>与 {@code WorkableTieredMachine} 逐字对齐</b>。
     *
     * <p>本类自己的 {@code MANAGED_FIELD_HOLDER}（见上）按<b>类</b>反射登记字段 ⇒ 字段写在本类里就自动进 holder，
     * <b>不需要再动 holder 那一行</b>。
     *
     * <p>⚠️ <b>本注解的效果我在沙盒里实测过</b>（存盘→停服→重启→读回，并带 GTCEu 原生
     * {@code gtceu:lv_input_bus} 正面对照）；若将来失效，退路是照上游
     * {@code PrimordialOmegaEngineModuleBase} 的写法显式
     * {@code storage.serializeNBT()/deserializeNBT()} 进自定义键（不要两套同时上）。
     */
    @Persisted
    private final NotifiableItemStackHandler matterBonusSlot;

    /**
     * 门控结果的<b>客户端镜像</b>（= {@link #moduleSlotBonus()} 的返回值）。
     *
     * <h2>为什么必须另开一个字段，而不是让渲染器直接读槽里的物品</h2>
     * 渲染器在客户端，而 {@code NotifiableItemStackHandler.storage} 字段上的同步注解<b>只有</b>
     * {@code @Persisted}、<b>没有</b> {@code @DescSynced}
     * （取证：{@code javap -v NotifiableItemStackHandler} 该字段的 {@code RuntimeVisibleAnnotations}
     * 只列出 {@code com.lowdragmc.lowdraglib.syncdata.annotation.Persisted}）
     * ⇒ 槽里的物品<b>不会</b>通过 syncdata 送到客户端。TESR 若直接读槽，客户端会永远读到空槽、
     * 半径永远停在 13.0，<b>且不报任何错</b>（本项目最忌讳的静默失败）。
     * 所以门控结果自己走一遍 {@code @DescSynced}（与 {@link #sphereStyle} 同款机制）。
     *
     * <h2>一致性</h2>
     * 服务端是唯一写者（见 {@link #refreshMatterBonusLevel()}：只有 {@code !isRemote()} 才刷新），
     * 客户端只读。写入时同步调 {@code notifyBlockUpdate()} —— 那是 {@code @DescSynced} 字段
     * 把值送到客户端的唯一动作。
     */
    @Persisted
    @DescSynced
    private int matterBonusLevel;

    /**
     * 🔴 必须是<b>可变</b>的活动集合：gtlcore 的 {@code addModule()} 默认实现直接
     * {@code getModuleSet().add(m)}，返回 {@code Collections.unmodifiableSet(...)} 会在模块成型的
     * 瞬间抛异常。用 {@code ReferenceOpenHashSet}（引用相等）与上游/gtladditions 一致。
     */
    private final Set<IModularMachineModule<PrimordialOmegaEngineMachine, ?>> modules = new ReferenceOpenHashSet<>();

    // ------------------------------------------------------- N6 配方最短耗时

    /**
     * <b>N6：配方最短耗时 —— 语义是【下限】（A 口径）</b>（tick）。默认 {@code 20}，GUI 范围 {@code 10..200}。
     *
     * <p>🔴 <b>现行 A 口径 = {@code max(1, dx, 这个值)}（{@code dx = 整批总能量 ÷ 最大电压}）⇒ 会随配方 duration 与并行数变。</b>⛔ <b>【旧口径 · 已作废，原文照留】语义是「恒等」不是「下限」</b>（2026-09 队长裁决，取证见
     * {@link PrimordialRecipeEffects} 类注释 N6 一节）：主机实际跑起来的配方时长
     * <b>恒等于</b>这个值，与配方自身写死的 duration、与并行数都无关。
     * 字段名仍叫 {@code limitedDuration} / {@code getLimitedDuration()}，因为那是
     * gtladditions {@code IGTLAddMultiRecipeMachine} 与侧栏 {@code LimitedDurationConfigurator}
     * 的接口名（改名就得换接口，不动）。
     * <p>⛔ <b>对照：模块侧那一套已于 2026-09-26 整体删除</b>（用户裁决原话逐字「连功能一起删」）——
     * 原文（作废，留档）：「<b>模块侧</b>仍把同一个值当<b>下限</b>用（{@code max(下限, round(d × f))}），
     * 见 {@code PrimordialRecipeEffects#applyDurationFloorForModule}」。
     * 现在模块侧<b>没有</b>自己的 {@code limitedDuration}、也没有下限项：模块耗时 =
     * {@code min(原时长, max(1, round(原时长 × f)))}，见
     * {@code PrimordialRecipeEffects#applyModuleDuration(GTRecipe, int)}。
     * <b>本类（主机）的字段与侧栏控件不受影响</b> —— 被删的是「<b>模块的</b>最小配方耗时」。
     * ⛔ **【2026-09-22 订正 · 旧句照留】** 上面「{@code 恒等于}这个值」是<b>旧口径</b>（本字段的 javadoc 写于恒等时代），
     * 已被用户同日改判的 **A 口径**（「照上游：下限 + 总能量守恒」）取代 ⇒ 主机现为 **{@code max(1, dx, 这个值)}**（口径见
     * {@code PrimordialRecipeEffects#durationFloorTarget(int,int)}）。
     *
     * <h2>字段声明为什么长这样</h2>
     * <ul>
     *   <li>{@code @Persisted}：拆装 / 存档重载后保留（任务书硬要求）。</li>
     *   <li>{@code @DescSynced}：<b>本类额外加的</b>（任务书只要求 {@code @Persisted}）。
     *       理由是本工程的红线「活的界面上不许放假数据」：{@code WorkableElectricMultiblockMachine.createUIWidget()}
     *       里那个 {@code ComponentPanelWidget} 的 {@code textSupplier} 在客户端为 {@code null} 时
     *       会退回<b>客户端本地</b>的 {@code addDisplayText}，而我们在里面显示了这个值；
     *       不加 {@code @DescSynced} 就会让客户端永远显示 20 这个陈旧数字。
     *       （写者仍是服务端，见 {@link #setLimitedDuration(int)}。）</li>
     * </ul>
     *
     * <h2>🔴 与 {@code MANAGED_FIELD_HOLDER} 的关系</h2>
     * 本类的 {@code MANAGED_FIELD_HOLDER} 是
     * {@code new ManagedFieldHolder(PrimordialOmegaEngineMachine.class, WorkableElectricMultiblockMachine.MANAGED_FIELD_HOLDER)}，
     * 它按<b>类</b>登记字段（LDLib 反射扫本类所有 {@code @Persisted} 字段）——
     * 所以只要字段写在<b>本类</b>里，就自动被收进 holder，<b>不需要</b>再动 holder 那一行。
     * 反过来说：把字段挪到父类 / 另开一个类而不同步 holder，才会出现
     * 「编译过、不报错、字段永不同步」的静默失败。
     */
    @Persisted
    @DescSynced
    private int limitedDuration = PrimordialRecipeEffects.DEFAULT_LIMITED_DURATION;

    public PrimordialOmegaEngineMachine(IMachineBlockEntity holder, Object... args) {
        super(holder, args);
        // 覆写 onContentsChanged 是为了"槽一变就立刻重算门控 + 推同步"，不必等 any tick 订阅；
        // 客户端也会收到这个回调（GUI 槽的本地交互），所以必须挡住 isRemote() 那一侧。
        this.matterBonusSlot = new NotifiableItemStackHandler(this, 1, IO.NONE, IO.NONE) {
            @Override
            public void onContentsChanged() {
                super.onContentsChanged();
                if (!isRemote()) {
                    refreshMatterBonusLevel();
                }
            }
        }.setFilter(PrimordialModuleMachine::isMatterModuleStack);
    }

    // ═════════════════════════ 多配方引擎（T1：换引擎的入口） ═════════════════════════

    /**
     * 🔴 <b>引擎替换点</b>：主机不再用 GTCEu 原生的 {@code RecipeLogic}，改用
     * {@link PrimordialEngineRecipeLogic}（{@code MutableRecipesLogic} 的本工程子类）。
     *
     * <h2>为什么是"换引擎"而不是"加一层"</h2>
     * 多配方引擎自己实现了 {@code findAndHandleRecipe / onRecipeFinish / handleRecipeWorking}
     * 三个入口，<b>完全绕开</b> {@code RecipeLogic.checkMatchedRecipeAvailable}
     * —— 而那正是 {@code recipeModifier} 链的唯一应用点。
     * ⇒ <b>四项效果必须随之迁移</b>，否则整条链静默失效（不报错、不崩溃、数值悄悄不对）。
     * 迁移的落点与完整论证见 {@link PrimordialEngineRecipeLogic} 的类注释。
     *
     * <h2>🔴 本方法在<b>父类构造器里</b>被调用（时序陷阱）</h2>
     * {@code [源码原文]} {@code WorkableMultiblockMachine} 的构造器：
     * <pre>
     *   public WorkableMultiblockMachine(IMachineBlockEntity holder, Object... args) {
     *       super(holder);
     *       this.recipeTypes = this.getDefinition().getRecipeTypes();
     *       this.activeRecipeType = 0;
     *       this.recipeLogic = this.createRecipeLogic(args);     ← 此刻本类的字段【都还没初始化】
     *       …
     *   }
     * </pre>
     * ⇒ {@link PrimordialEngineRecipeLogic} 的构造器<b>不许读本类的任何字段</b>
     * （它只调 {@code setUseMultipleRecipes(true)} + 存下 machine 引用，所以安全）。
     * 这一点与本类原有的「构造期兜底」注释（{@code matterBonusSlot == null}）是同一条纪律。
     *
     * <p>{@code args} 原样不转发是<i>刻意的</i>：{@link PrimordialEngineRecipeLogic} 只有一个
     * 只吃 machine 的构造器，而 {@code EntityMachine} 那套 {@code args} 在本主机上没有用途
     * （{@code MachineBuilder.multiblock(id, PrimordialOmegaEngineMachine::new)} 不传额外实参）。
     */
    @Override
    protected RecipeLogic createRecipeLogic(Object... args) {
        return new PrimordialEngineRecipeLogic(this);
    }

    // ═════════════════════════════ 中子星的「运行时间」（🟡 已作废 · 原文留档） ═════════════════════════════

    /**
     * ⛔⛔ <b>【本节整体已作废 · 2026-09-22 用户裁定】—— 下面这段原文一字未改，只作留档，它描述的字段与整条同步链已【全部删除】。</b>
     *
     * <h2>作废原因（用户原话）</h2>
     * 「<b>9的话我不希望光束的颜色随着时间变化，而希望它随着物质模块的等级变化</b>」
     * ⇒ 颜色的驱动源从 <b>运行时间（{@code runningSecs}）</b> 换成
     * <b>主机专属槽里那个模块的等级</b>（与星体尺寸<b>同一个源</b>：{@link #moduleSlotBonus()}）。
     *
     * <h2>因此删除了什么</h2>
     * <ul>
     *   <li>{@code @Persisted @DescSynced private long runningSecs;} —— 字段本身；</li>
     *   <li>它的 <b>整条同步链</b>：{@code runningSecsSubs} / {@code runningSecsTick} /
     *       {@code getRunningSecs()} / {@code startRunningSecsTicker()} / {@code stopRunningSecsTicker()} /
     *       {@code tickRunningSecs()}，以及 <b>3 处调用点</b>
     *       （{@code onStructureFormed} / {@code onStructureInvalid} / {@code onMachineRemoved}）。</li>
     * </ul>
     * ⇒ <b>副产品（正的）</b>：<b>少了一处 {@code @DescSynced} 方块实体同步</b>
     * （原本每台主机每秒最多 1 个更新包），以及一个服务端 20-tick 订阅。
     *
     * <h2>⚠️ 为什么保留下面的原文、而不顺手改掉里面的 {@code @link}</h2>
     * 按本工程「<b>作废项一律保留原文 + 注明作废原因</b>」原则：
     * <b>引用是史料</b>。下面文字里指向 {@code #runningSecs} / {@code #getRunningSecs()} 的锚点
     * <b>已经指不到任何东西</b>（成员已删）—— 这是<b>故意</b>不修的：一修，原文就不再是原文，
     * 后人也就看不出"这里曾经真有一条按运行时间累加的链"。
     *
     * <h2>作废后颜色从哪来</h2>
     * {@code PrimordialNeutronStarSphereRenderer#colorOf(int)} ←
     * {@code PrimordialStarGradient#ratioFromModuleLevel(int, int)}，源 = 专属槽等级（{@code 0..17}）。
     * <p>🔴 <b>等级没有被新增到任何别的数值链</b>：它本来就驱动 N3 产出倍率与 N5 耗电减免（既有设计），
     * 这次只是<b>颜色也读它</b>。
     *
     * <hr>
     * <p>—— 以下为原文（2026-09-21 撰写，作废于 2026-09-22）——
     *
     * <p><b>累计运行秒数</b> —— 纯视觉量，<b>只被中子星渲染读</b>。
     *
     * <h2>🔴 它绝对不参与任何数值链（本工程已裁定的红线）</h2>
     * 本工程<b>不做</b>原版伪神之锻炉那套「按运行时间给产出倍率 / 耗能倍率」的机制。
     * 本字段的唯一读者是 {@code PrimordialNeutronStarSphereRenderer}（经
     * {@link #getRunningSecs()}）：
     * <pre>
     *   读它的人：PrimordialOmegaEngineRenderer（客户端 TESR）→ 中子星颜色
     *   不读它的人：PrimordialRecipeEffects（N3/N5/N6 全部算术）、ModuleRegistry 的配方修饰链、
     *              duration、EUt、产出 —— 一个字都不读。
     * </pre>
     * 可以用一句 grep 证明：{@code getRunningSecs} 在本工程里只有渲染侧一个调用点。
     *
     * <h2>为什么必须新加字段（先查过"有没有现成的"）</h2>
     * 查过的候选与结论：
     * <ul>
     *   <li>{@code getOffsetTimer()}：{@code MetaMachine.getOffsetTimer()} → {@code holder.getOffsetTimer()}，
     *       是「等级游戏时间 − 偏移」的<b>当前时刻</b>，不是"累计跑了多久"的累加量，
     *       而且上游口径要求的是<b>运行</b>时间（不工作时还会回退）；<b>不采用</b>。</li>
     *   <li>{@code RenderUtil.getSmoothTick(machine, partialTick)}：客户端渲染用的连续时钟
     *       （本类的中子星分支已经在用它做姿态角），它是<b>世界时间</b>不是运行时间，
     *       且未工作时不代表"运行了多久"；<b>不采用</b>。</li>
     *   <li>本类已有字段：{@code sphereStyle} / {@code matterBonusLevel} / {@code limitedDuration}
     *       —— 都与时间无关；<b>没有现成的</b>。</li>
     * </ul>
     * ⇒ 新加一个 {@code @Persisted @DescSynced long}。{@code @DescSynced} 是硬需求：
     * 颜色算在客户端，客户端必须拿到服务端累计的值，否则会出现"两台机器各算各的"。
     *
     * <h2>与上游的逐字对照</h2>
     * 上游 {@code ForgeOfTheAntichrist} 的字段声明是
     * {@code @Persisted @DescSynced private long runningSecs;}，语义也照抄：
     * <pre>
     *   每 20 tick（= 1 秒）结算一次：
     *      正在工作          ⇒ runningSecs += 1
     *      没在工作          ⇒ runningSecs -= 16      （上游原文是 -16L，是它的"冷却"设计）
     *      结果钳到 ≥ 0
     * </pre>
     * 上游还有"时空停滞（stasis）时既不加也不减"那一条分支，本主机没有停滞机制，故不实现。
     *
     * <h2>为什么写值要 {@code notifyBlockUpdate()}</h2>
     * 那是 {@code @DescSynced} 字段把值送到客户端的唯一动作（与本类另外两个字段同一条纪律）。
     * 代价：每台主机每秒 ≤ 1 个方块实体更新包，只在"值真的变了"时才发。
     */
    // ⛔ 【已删除 · 2026-09-22】这里原本是 runningSecs 字段 + 它的整条 @DescSynced 同步链：
    //    @Persisted @DescSynced private long runningSecs;
    //    private TickableSubscription runningSecsSubs;   private int runningSecsTick;
    //    public long getRunningSecs();  startRunningSecsTicker();  stopRunningSecsTicker();
    //    private void tickRunningSecs();   // 每 20 tick 结算一次：工作中 +1、否则 -16（上游的"冷却"设计），钳 ≥ 0
    //    连同 3 处调用点（onStructureFormed / onStructureInvalid / onMachineRemoved）一并删除。
    // 删除理由 + 原文留档 = 上方整节「中子星的『运行时间』（🟡 已作废 · 原文留档）」：
    //   用户裁定【颜色改随物质模块等级，不随时间】⇒ 本字段与它的同步链不再有任何读者。
    // 收益：少一处 @DescSynced 方块实体同步（原本每台主机每秒 ≤ 1 个更新包）+ 少一个服务端 20-tick 订阅。

    // ------------------------------------------------------- 「模块等级复写」的主机侧出口（默认不启用）

    /**
     * <b>材料增产的额外倍率</b>（加在 N3 的 {@code outputMultiplier(门控等级)} 之上）：{@code 0} = 不加。
     *
     * <h2>谁读它</h2>
     * 多配方引擎路径的尾链（{@code PrimordialEngineRecipeLogic#buildFinalNormalRecipe}）在调用
     * {@code PrimordialRecipeEffects.applyHostTailEffectsA(..., 倍率, 耗电系数)} 时把它加进倍率；
     * 原生修饰链那条路（{@code #applyHostRecipeModifier} / 子类覆写）读同一个钩子 ⇒ 两条路同源。
     *
     * <h2>🔴 为什么老主机必须返回 0</h2>
     * {@code 1 + 0 == 1} ⇒ 倍率与改动前<b>逐位相同</b>。归墟视界枢核覆写它返回 {@code 5}
     * （只在门控生效即 64 个物质模块时非零）。
     */
    public int getHostProductionBonusExtra() {
        return 0;
    }

    /**
     * <b>主机侧 N5 耗电成本系数</b>（乘到 EUt 上的那个 {@code f}）：默认 = 现有的
     * {@link PrimordialRecipeEffects#reductionFactor(int)}（即老主机的现行公式，逐位不变）。
     *
     * <h2>为什么要有这个出口</h2>
     * 引擎路径的尾链是<b>静态方法</b>（拿不到宿主），原先在里面直接调 {@code reductionFactor(gateBonus)}，
     * 宿主无法参与。把它变成"宿主给数、尾链接数"，才能让"这台主机用哪套减免"由宿主决定
     * （归墟视界枢核：与原始终焉引擎<b>同款</b>，所以覆写后返回同一个值；这条出口是为将来换减免公式留的）。
     *
     * <p>🔴 默认实现<b>必须</b>保持与改动前完全等价：返回值仍是 {@code reductionFactor(gateBonus)}。
     *
     * @param gateBonus 门控等级（{@code moduleSlotBonus()}，0 = 未生效 ⇒ 返回 1.0 = 不减免）
     */
    public double getHostEnergyReductionFactor(int gateBonus) {
        return PrimordialRecipeEffects.reductionFactor(gateBonus);
    }
    /**
     * 主机侧「物质模块等级复写」的等级：{@code 0} = <b>没有复写</b>（本类恒返回 0）。
     *
     * <h2>它是给谁读的</h2>
     * 模块侧 {@code PrimordialModuleMachine#getHostMatterLevelOverride()} 会向宿主问这个数，
     * 再算「有效等级 = {@code max(模块自己槽里的等级, 本值)}」（用户原话：「若模块上安装的物质模块等级
     * 大于主机等级则不复写」）。
     *
     * <h2>🔴 为什么老主机必须返回 0</h2>
     * 老主机（原始终焉引擎）没有这个功能；返回 0 时模块侧的 {@code max(自己, 0) == 自己}
     * ⇒ <b>行为与加这个钩子之前逐位相同</b>。这是本钩子"可 fail-open"的结构性保证，
     * 不是"恰好没影响"。
     *
     * <p>归墟视界枢核覆写它（见 {@code GuixuVisionCoreMachine#getHostMatterLevelOverride()}）：
     * 那台主机的第二个专属槽放满 64 个物质模块 ⇒ 返回 1..17。
     */
    public int getHostMatterLevelOverride() {
        return 0;
    }

    /**
     * <b>本主机是否给它挂载的模块授予「跨配方种类并行」</b>：{@code true} = 授予。
     *
     * <h2>授予之后模块变成什么样（口径 = 用户 2026-10-23 原话，逐字）</h2>
     * <pre>
     *   「使安装在它上面的模块也获得跨配方种类并行，且<b>跨配方种类并行等于原机器的线程数</b>，
     *     也就相当于把他的<b>跨配方线程变成了跨配方种类并行</b>」
     * </pre>
     * ⇒ 模块的候选集来源从「{@code getRecipeTypes()[activeRecipeType]} <b>一个</b>类型」换成
     * 「按类型数组顺序<b>逐类型取 1 条</b>、凑够<b>线程数</b>即停」；
     * 其余全部不动 —— 下游本来就把 N 条候选<b>塌缩成一条配方</b>（共用一份 {@code progress}、
     * 逐条各扣各的料、产出拼接后一次结算），那套口径与"这 N 条来自几个类型"无关。
     * 落点见 {@code PrimordialModuleRecipeLogic#lookupRecipeSet()}。
     *
     * <h2>🔴 为什么默认 {@code false}（本钩子的全部安全性都在这一行）</h2>
     * 老主机（原始终焉引擎）没有这个功能，{@code false} ⇒ 模块走原有的单类型路径，
     * <b>与加这个钩子之前逐位相同</b>（候选集、截断顺序、扣料顺序、日志一行都不变）。
     * 这是「可 fail-open」的<b>结构性</b>保证而不是「恰好没影响」：判定点只有一个
     * （{@code PrimordialModuleRecipeLogic#shanhai$isCrossTypeEnabled()}），默认值一关就整条新路都不执行。
     *
     * <p>归墟视界枢核覆写它返回 {@code true}（{@code GuixuVisionCoreMachine#grantsCrossTypeParallel()}）。
     * ⚠️ 模块侧<b>每次 lookup 都现读</b>本方法、<b>不缓存</b> ⇒ 主机成型 / 拆解 / 授予条件变化都立刻生效。
     */
    public boolean grantsCrossTypeParallel() {
        return false;
    }

    // ═══════════════ 「机器模式」显示名（用户 2026-10-25 定稿；⭐ 改名只改下面两行里的字符串） ═══════════════
    //
    // 为什么要这一对常量（用户原话）：
    //   「伪神也是跨配方种类并行，它 jade 里面显示是「群星烧却」（一个新的名字），我们的主机和模块
    //     跨配方种类并行还是老的单个配方的名字，会引起歧义，所以你也分别给模块和主机起个新的名字
    //     放到 jade 还有机器里面显示」
    // ⇒ 跨配方种类并行真在生效时，「机器模式」那一行不再画「当前激活的那个配方类型名」
    //   （如「视界物质解压」「车床」—— 那只代表"本轮恰好轮到哪个类型"，会被读成"这台机器只会跑这一类"），
    //   改画下面这个名字。未生效时【一个字符都不改】：调用方一律不换名、不加行。

    /**
     * 🔴 <b>主机（归墟视界枢核）在「跨配方种类并行」生效时的机器模式名</b>。
     *
     * <p>⭐ <b>用户要改名，只改这一行的字符串</b>（改完重新构建，两个显示点一起变：
     * Jade 的「机器模式」行 + 机器 GUI 抬头那一行 —— 两处读的都是
     * {@link #crossTypeModeDisplayName()}，见该方法的说明）。
     */
    public static final String CROSS_TYPE_MODE_NAME_HOST = "视界物质并流";

    /**
     * 🔴 <b>任意模块在「跨配方种类并行」生效时的机器模式名</b>。
     *
     * <p>⭐ <b>用户要改名，只改这一行的字符串</b>（同上：Jade 与机器 GUI 两处一起变）。
     */
    public static final String CROSS_TYPE_MODE_NAME_MODULE = "原初并流加工";

    /**
     * <b>纯函数：跨类型生效 ⇒ 给出要显示的新名字；否则 ⇒ {@code null}（= 叫调用方"照原样显示"）。</b>
     *
     * <h2>两个分支各自保证什么</h2>
     * <pre>
     *   crossTypeInEffect == false          ⇒ null ⇒ 调用方【不加行、不换名】
     *                                        ⇒ Jade / GUI 与加这个功能之前【逐字相同】
     *   crossTypeInEffect == true 但名字为空 ⇒ null ⇒ 同样照原样（绝不画一行空名字出来）
     *   crossTypeInEffect == true 且名字非空 ⇒ 返回该名字
     * </pre>
     *
     * <p>★ 这是本功能<b>唯一的判定点</b>：Jade 那一路（服务端写 NBT / 客户端画行）与 GUI 那一路
     * （{@code addDisplayText}）都调用它 ⇒ 两侧不可能分叉。
     *
     * @param crossTypeInEffect 「跨配方种类并行」此刻是否<b>真的在生效</b>（各机器自己按状态判，见子类覆写）
     * @param crossTypeModeName 要显示的新名字（只能是上面两个常量之一）
     */
    public static @Nullable String resolveCrossTypeModeName(final boolean crossTypeInEffect,
                                                           final @Nullable String crossTypeModeName) {
        if (!crossTypeInEffect) {
            return null;
        }
        if (crossTypeModeName == null || crossTypeModeName.isBlank()) {
            return null;
        }
        return crossTypeModeName;
    }

    /**
     * <b>本机此刻该显示的「机器模式」新名字；{@code null} = 不换名（照上游/照原样显示）。</b>
     *
     * <h2>默认实现为什么必须返回 {@code null}</h2>
     * 老主机（原始终焉引擎）没有这个功能 ⇒ {@code null} ⇒ Jade 与 GUI
     * <b>与加这个功能之前逐字相同</b>（这就是"可 fail-open"的结构性保证，不是"恰好没影响"）。
     *
     * <h2>两个显示点（都只读<b>这一个</b>方法）</h2>
     * <ol>
     *   <li><b>Jade</b>：{@code com.shanhai.mixin.ShanhaiMachineModeNameMixin} ——
     *       服务端在 GTCEu {@code MachineModeProvider#appendServerData} 的 RETURN 处读本方法，
     *       非 {@code null} 才往 Jade 的服务端数据里写一个字符串键；客户端在
     *       {@code #appendTooltip} 的 HEAD 处读那个键，命中才把「机器模式」那一行换成新名字并取消上游渲染。
     *       <b>键不存在时一个字节都不写</b> ⇒ 未生效的机器完全走上游原路径。</li>
     *   <li><b>机器 GUI</b>：{@code addDisplayText(...)}（本工程主机 / 模块各自的实现）——
     *       非 {@code null} 才<b>多画一行</b>「机器模式：&lt;新名字&gt;」。</li>
     * </ol>
     *
     * <h2>⚠️ 为什么必须是"状态相关"的而不是常量</h2>
     * 用户要求「只在真跨类型跑时换名」⇒ 未授予（老主机）、锁了配方（玩家明确指定了单条配方）、
     * 结构未成型 等退化情形<b>一律显示原来的配方类型名</b>。各子类怎么判见它们的覆写。
     */
    public @Nullable String crossTypeModeDisplayName() {
        return null;
    }

    /**
     * 🔴 <b>纯函数：这一次挂侧栏时，要不要把「机器模式」选择器子页藏起来</b>
     * （用户 2026-10-25 拍板原话逐字：「<b>我说的是「机器模式」子页，那个要不然你就在机器是跨配方种类
     * 并行的情况下隐藏掉得了</b>」）。
     *
     * <h2>为什么这个子页在跨类型生效时【没有意义】</h2>
     * 那个子页是 GTCEu 的 {@code MachineModeFancyConfigurator}，它只做一件事：改
     * {@code activeRecipeType}（{@code IRecipeLogicMachine#setActiveRecipeType(int)}）。
     * 而跨配方种类并行生效时，模块侧取候选的入口被整段换掉了 ——
     * {@code PrimordialModuleRecipeLogic#lookupRecipeSet()} 在 {@code shanhai$isCrossTypeEnabled()}
     * 为真时<b>无视 {@code activeRecipeType}</b>、按本机的<b>全部</b>配方类型轮转取候选
     * ⇒ 玩家在那页手选哪一类都<b>不会</b>改变机器跑什么，只会被读成"这台机器只跑这一类"（误导）。
     * 未生效时（老主机 / 锁了配方 / 未成型 / 发电模块）它确实是<b>唯一</b>能决定"跑哪一类"的控件
     * ⇒ 那时必须<b>逐字还原</b>：照上游默认挂上、照常可选。
     *
     * <h2>判据只有一行，而且与「机器模式」改名【同一个判据】</h2>
     * <pre>
     *   crossTypeModeDisplayName == null  ⇒ false ⇒ 子页照挂（= 上游 GTCEu 默认行为，一个字节都不动）
     *   crossTypeModeDisplayName != null  ⇒ true  ⇒ 子页不挂（= 跨配方种类并行正在生效）
     * </pre>
     * ⚠️ 两者共用 {@link #crossTypeModeDisplayName()} 这一个方法 ⇒ 界面上不可能出现
     * "名字已经换成「原初并流加工」、选择器却还能手选类型"这种自相矛盾的状态。
     *
     * <h2>⚠️ 调用方必须【现场】判、不许缓存</h2>
     * 跨类型在不在生效是<b>会变</b>的（主机成型/拆解、锁/解锁配方、模块换主机）。
     * 侧栏每次都是"整列清空后重挂"（{@code FancyMachineUIWidget#setupSideTabs} 偏移 4
     * {@code clearSubTabs()}、偏移 12 {@code attachSideTabs(...)}，javap 已核）
     * ⇒ 在挂载那一刻现取 {@code crossTypeModeDisplayName()} 就自动跟随机器状态，
     * 玩家关掉界面再打开即更新，<b>不需要</b>任何"状态变了要重挂"的补偿逻辑。
     *
     * <h2>🔴 2026-10-25 补充：客户端必须传【同步镜像】的值，不能传本地现算的值</h2>
     * 这个判据的调用点有一个在<b>客户端</b>（GUI：{@code attachSideTabs} 两侧都会跑 ——
     * LDLib {@code UIFactory#initClientUI} 在客户端也调 {@code createUITemplate} +
     * {@code initWidgets}，javap 偏移 22 / 35），而模块的 {@code getHost()} 是
     * <b>纯服务端字段</b>（唯一写者 {@code findAndConnectToHost()} 只从 {@code onStructureFormed}
     * 与 {@code subscribeServerTick} 进，前者在 GTCEu 里唯一调用点是 {@code asyncCheckPattern} 的
     * {@code ServerLevel} lambda）⇒ 客户端本地现算恒 {@code null} ⇒ 恒不隐藏。
     * 所以模块侧现在传的是服务端 {@code @DescSynced} 下来的 {@code shanhai$crossTypeModeActive}。
     * <b>将来谁再加调用点，先问一句"这份值在客户端拿得到吗"。</b>
     *
     * @param crossTypeModeDisplayName 调用方现场取的 {@link #crossTypeModeDisplayName()}（可为 {@code null}）
     */
    public static boolean shouldHideMachineModeTab(final @Nullable String crossTypeModeDisplayName) {
        return crossTypeModeDisplayName != null;
    }

    /**
     * <b>某个配方逻辑是否被玩家"锁配方"了</b>（只读，供上面那些显示判定用）。
     *
     * <h2>为什么需要它（而不是直接 {@code getRecipeLogic().isLock()}）</h2>
     * {@code isLock()} 不在 GTCEu 的 {@code RecipeLogic} 上，而在 gtlcore 的
     * {@code org.gtlcore.gtlcore.api.machine.trait.ILockRecipe}（{@code default boolean isLock()}，
     * javap 已核）—— 本工程的配方逻辑（{@code PrimordialEngineRecipeLogic} /
     * {@code PrimordialModuleRecipeLogic}）经由 gtladditions 的 {@code MutableRecipesLogic}
     * 实现了那个接口。而 {@code IRecipeLogicMachine#getRecipeLogic()} 的静态类型是 {@code RecipeLogic}
     * ⇒ 必须 {@code instanceof} 一下才能调。
     *
     * <h2>为什么是 {@code static}（吃一个 Object）而不是本类的实例方法</h2>
     * 模块（{@code PrimordialModuleMachine}）<b>不是</b>本类的子类（它是
     * {@code WorkableElectricMultiblockMachine} 的抽象子类，见该类的 {@code extends} 那一行）
     * ⇒ 实例方法它调不到。显示判据两边必须是<b>同一份实现</b>（否则主机会漂移成"模块锁了配方还换名"）。
     *
     * <p>纯读、不缓存（与"锁配方"开关同 ttl）、任何异常都按"没锁"处理（显示路径不许抛）。
     */
    public static boolean isRecipeLockedForDisplay(final @Nullable Object recipeLogic) {
        try {
            return recipeLogic instanceof org.gtlcore.gtlcore.api.machine.trait.ILockRecipe lock && lock.isLock();
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ------------------------------------------------------- 「物质模块槽」门控（N3/N7 共用）

    /**
     * 🔴 <b>全工程唯一的门控实现</b>。
     *
     * <p><b>审计命令</b>：在 {@code src} 下 grep 计数「两个等号 + 空格 + 64」这个字面串，
     * 应当<b>恰好命中 1 行</b> —— 就是本方法里那行 {@code getCount()} 比较。
     * （注释里刻意<b>不复写</b>那个字面串：一复写，审计计数就会被注释污染成 4 行，
     * 这条 grep 就再也不具备"只实现一处"的证明力了。）
     *
     * <h2>口径（2026-09-21 队长裁决，已锁定）</h2>
     * <pre>
     *   主机那个「物质模块专属槽」内 必须是【单一物品类型】且【数量必须等于 64】
     *     不满 64 / 空槽 / 非物质模块 ⇒ 0（未生效）
     *     满 64                      ⇒ 1..17（= 该模块的等级）
     * </pre>
     * 「单一物品类型」在本实现里是<b>结构性保证</b>而不是额外判断：槽只有 1 格，一个 {@code ItemStack}
     * 按定义就是一种物品；再加 {@link PrimordialModuleMachine#isMatterModuleStack} 过滤器，
     * 非物质模块根本放不进去。所以这里只需判数量。
     *
     * <p>刻意写成字面量而不是常量：这条口径要靠上面那条 grep 审计，
     * 换成常量会让审计变成"0 处"从而失去意义。
     */
    private int computeMatterBonusLevel() {
        if (matterBonusSlot == null) {
            // 构造期兜底：handler 的 onContentsChanged 回调理论上不会在字段赋值前触发，
            // 但"宁可不放大，也不要 NPE 崩在方块实体构造里"。
            return 0;
        }
        ItemStack stack = matterBonusSlot.storage.getStackInSlot(0);
        if (stack.getCount() == 64) {
            return PrimordialModuleMachine.getModuleLevelByStack(stack);
        }
        return 0;
    }

    /**
     * <b>N3 的产出倍率与 N7 的星体尺寸都调这一个方法。</b>
     *
     * @return {@code 0} = <b>未生效</b>；{@code 1..17} = <b>生效</b>，值为物质模块等级。
     *
     * <h2>为什么是"一个 int"而不是 {@code boolean + int}</h2>
     * "是否生效"与"生效时的等级"在构造上不可能不一致（等级 0 只可能是未生效），
     * 所以用单个 int 表达两件事，就没有"两个字段被写成矛盾状态"的可能。
     *
     * <h2>服务端 / 客户端取值路径</h2>
     * <ul>
     *   <li><b>服务端</b>（N3 的产出倍率）：本方法每次调用都按槽内<b>实况</b>重算
     *       （{@link #refreshMatterBonusLevel()}，内部只在值变化时才写字段 + 同步），
     *       所以不存在"要等某个 tick 才生效"的延迟；</li>
     *   <li><b>客户端</b>（N7 的球体半径）：直接返回 {@code @DescSynced} 镜像
     *       {@link #matterBonusLevel}，由服务端的 {@code notifyBlockUpdate()} 推过来。</li>
     * </ul>
     * 两边读的是同一个方法、同一个口径；客户端拿到的是服务端算出来的值（不是自己算的）。
     */
    public int moduleSlotBonus() {
        if (!isRemote()) {
            refreshMatterBonusLevel();
        }
        return matterBonusLevel;
    }

    /**
     * 重算门控并（仅在值变化时）推一次同步。
     *
     * <p>只在值真的变了才 {@code notifyBlockUpdate()}：这个方法是<b>服务端每次读
     * {@link #moduleSlotBonus()} 都会走</b>的，无脑同步会变成每 tick 一个包。
     */
    private void refreshMatterBonusLevel() {
        int now = computeMatterBonusLevel();
        if (now == matterBonusLevel) {
            return;
        }
        matterBonusLevel = now;
        notifyBlockUpdate();
    }

    // ------------------------------------------------------- N6 配方最短耗时的读写

    /**
     * {@code IGTLAddMultiRecipeMachine} 的读接口 —— gtladditions 的
     * {@code LimitedDurationConfigurator} 就是靠它把值画进侧栏的。
     *
     * <p>同时，主机下的每台模块的 recipeModifier 也会读这个方法（下限夹取用主机这一份，见
     * {@code ModuleRegistry} 的组合修饰器与「顺序与钳制」一节）。
     */
    @Override
    public int getLimitedDuration() {
        return limitedDuration;
    }

    /**
     * {@code IGTLAddMultiRecipeMachine} 的写接口：侧栏那个 {@code IntInputWidget} 的 setter 走这里。
     *
     * <p>两条额外纪律（本工程自己加的，不是上游行为）：
     * <ol>
     *   <li><b>服务端兜底钳位</b>到 {@code [10, 200]}：GUI 控件虽然 {@code setMin(10).setMax(200)}，
     *       但网络包不保证只带合法值，而 {@code limitedDuration <= 0} 会让 {@code duration} 下限失去意义；</li>
     *   <li>值真变了才 {@code notifyBlockUpdate()} —— 那是 {@code @DescSynced} 字段送到客户端的唯一动作，
     *       也是「无脑同步会变成每 tick 一个包」的反面。</li>
     * </ol>
     */
    @Override
    public void setLimitedDuration(int duration) {
        int next = PrimordialRecipeEffects.clampLimitedDuration(duration);
        if (next == limitedDuration) {
            return;
        }
        limitedDuration = next;
        notifyBlockUpdate();
    }

    // ------------------------------------------------------- recipeModifier：主机侧 N4 → N3 → N6

    /**
     * <b>主机侧的整条配方修饰链</b>，由 {@code ShanhaiMachines} 的主机注册处
     * {@code .recipeModifier(PrimordialOmegaEngineMachine::applyHostRecipeModifier)} 挂上。
     *
     * <h2>🔴 2026-09-21（换引擎）：本方法在【正常运行路径】下<b>不会被调用</b>，请勿在此改效果</h2>
     * 主机已经改用 gtladditions 的<b>多配方引擎</b>（{@link PrimordialEngineRecipeLogic}），
     * 该引擎的三个入口（{@code findAndHandleRecipe} / {@code onRecipeFinish} / {@code handleRecipeWorking}）
     * 全部走<b>它自己的</b> {@code findAndHandleMultipleRecipe} / {@code onMultipleRecipeFinish} /
     * {@code handleMultipleRecipeWorking}，<b>完全不经过</b>
     * {@code RecipeLogic.checkMatchedRecipeAvailable} —— 而 {@code recipeModifier} 链的<b>唯一</b>
     * 应用点就是那里（{@code checkMatchedRecipeAvailable} → {@code fullModifyRecipe} →
     * {@code WorkableMultiblockMachine.getRealRecipe} → {@code definition.getRecipeModifier().apply(...)}）。
     * <pre>
     *   本方法现在只有两种情形会被执行：
     *     ① 有人把 {@code PrimordialEngineRecipeLogic} 的 useMultipleRecipes 关掉（回退到原生路径）；
     *     ② 将来的引擎实现改回调用 {@code checkMatchedRecipeAvailable}。
     *   ⇒ 它保留下来是因为 ① 需要一个「已知正确」的原生路径实现，且
     *     {@code MachineBuilder.recipeModifier(x)} 是【替换】而不是叠加（删掉这一行会把
     *     GTCEu 默认的电磁过载拿回来，那是比"留一段不执行的代码"更大的行为变化）。
     * </pre>
     * <b>⇒ 改 N3/N4/N5/N6 的口径时，必须同时确认两条路径：本方法 + {@code PrimordialEngineRecipeLogic}。
     * 两条路径的 N3/N6/N5 已经共用 {@link PrimordialRecipeEffects#applyHostTailEffects} 这一份算术，
     * 所以正常情况下<b>只需要改那一处</b>；唯一的独立项是 N4 并行（本方法用
     * {@code PrimordialRecipeEffects.applyParallel(..., MAX_PARALLEL)}，
     * 引擎路径用 {@code getMaxParallel() × getMultipleThreads() = MAX_PARALLEL × MAX_PARALLEL}）。</b>
     * <p>「换引擎为什么不迁移 = 静默失效」的完整论证见 {@link PrimordialEngineRecipeLogic} 的类注释。
     *
     * <h2>顺序（写死的口径，不许换）</h2>
     * <pre>
     *   ① 并行     (N4)   并行先定「跑几份」
     *   ② 产出倍率  (N3)   倍率再定「每份出多少」——只动 outputs / tickOutputs（概率拉满）
     *   ③ 时长天花板 (N6)   recipe.duration = min(原时长, max(1, getLimitedDuration()))   ⚠️ 本段已不执行（生产路径 = A 下限）
     *   ④ 耗电减免  (N5)   EUt ×= f（f 取【主机专属槽】的等级），钳 ≥ 1 EUt
     *   ✗ N5 的【时长】减免<b>不</b>加在主机上（用户原话：「耗时减免是对于模块的」；速度已无限）
     * </pre>
     * <b>为什么 ④ 放在 ③ 之后</b>：③ 会把 {@code duration} 一次写定（本段 = min(原时长, 下限)，**与配方原值有关**）；
     * ④ 只碰 {@code tickInputs} 里的 EU 内容，两个字面量互不重叠 —— 谁先谁后对结果没有影响，
     * 但写成「先定时长、后算电费」与用户读到的口径顺序一致，也让 ⑤ 的绊线能证明
     * 「④ 没有顺手改 duration」。
     *
     * <h2>🔴 主机 vs 模块的不对称（改动任一侧都要同步改另一处的注释）</h2>
     * <pre>
     *   主机（本方法，⚠️ 已不执行）：时长【min(原时长, 下限)】；耗电【吃】N5 减免；时长【不吃】N5 减免
     *   模块（ModuleRegistry#applyModuleRecipeModifier）：
     *                     时长 = min(原时长, max(1, round(原时长 × f)))（吃 N5 时长，⛔ 下限项已删）；耗电【吃】N5 减免
     * </pre>
     * 两侧的 {@code f} <b>来源完全相同</b>：主机专属槽里那个模块的等级 ⇒
     * {@code f = 1 − 0.95^(17/等级)}（即 1 − 减免比例；2026-09-21 用户点破旧口径方向反了之后改的），
     * 等级 ≤ 0 时在 {@code reductionFactor} 里先短路成 1.0。作用在主机<b>与其所有模块</b>上。
     * <p><b>可实机对账的判据</b>：同一主机 + 同一配方，专属槽【空】vs【满 64 个同种模块】
     * ⇒ <b>耗电应差 f 倍</b>（Lv.1 ⇒ f ≈ 0.5819 倍；Lv.17 ⇒ f = 0.05）；
     * <b>耗时两栏都不变</b>（主机时长 = N6 的 A 目标 {@code max(1, dx, 下限)}，与 f 无关）。
     *
     * <h2>可对账的例子（写进代码，验收时照着点）</h2>
     * <pre>
     *   配方 A：输入 1 铁 → 产出 1 齿轮，duration = 200t，EUt = 30
     *   主机：并行 100（上限 2^30，实际由你喂进去的 100 个铁决定）
     *   门户槽：满 64 个「基础物质模块」(Lv.2) ⇒ 倍率 M = 1 + 2 = 3，
     *           R = 0.95^(17/2) ≈ 0.6466（减免 64.7%）⇒ f = 1 − R ≈ 0.3534
     *   ⇒ 输入 100 铁 ⇒ 一轮加工 ⇒ 产出 300 齿轮 ✔（用户原话的例子）
     *   ⇒ duration = 20（= 侧栏那个「配方最短耗时」，与 200 无关）
     *   ⇒ EUt = 30 × 100(并行) × 0.3534 ≈ 1060
     * </pre>
     *
     * <h2>🔴 本方法刻意<b>不写</b> {@code OCResult}</h2>
     * gtlcore 的 {@code RecipeModifierListMixin} 覆写了 {@code RecipeModifierList.apply}，
     * 收尾处原文是 {@code if (modifiedRecipe != null && result.getDuration() != 0) { modifiedRecipe.duration = result.getDuration(); … }}。
     * 也就是说：<b>只要 {@code OCResult} 里有非零 duration，它就会在链尾覆盖掉 {@code recipe.duration}</b>。
     * 我们的 N6 时长值必须活到最后，所以这里不往 {@code result} 里写任何东西
     * （保持 {@code result.getDuration() == 0} ⇒ 收尾那段整块跳过）。
     *
     * <h2>🔴 duration 绊线（本段 = min 天花板口径；⚠️ 本段已不执行，生产路径 = A 下限）</h2>
     * <pre>
     *   更旧（作废）：N6 = 「恒等」⇒ 无条件写 {@code duration = max(1, limitedDuration)}
     *   本段：N6 = {@code min(原时长, max(1, limitedDuration))} ⇒ <b>永不变长</b>
     * </pre>
     * <b>作废原因</b>：恒等语义会把<b>比限定值更短的配方拖慢</b>（1 tick 的配方被钉到 10 tick
     * ⇒ 抬头「总耗时倍率」1000%）。主机链在 ① 并行、② 倍率 之后各插一次绊线，N6 之后再做终检：
     * <pre>
     *   ① 并行之后：{@code auditDuration}(期望 = 链入口快照)   —— 标签 "主机-修饰链-N4-并行后"
     *   ②③④ 由 applyHostTailEffects 一次做完，内部共三次绊线： —— 标签前缀 "主机-修饰链-"
     *        N3 之后（期望 = 尾巴入口快照）
     *        N6 之后（{@code auditDurationWithin}：期望 = min(原时长, 上限)，并断言 ≤ 原时长、≤ 上限）
     *        N5 之后（期望 = N6 目标）
     * </pre>
     * 一次不一致就会打出 {@code [SHANHAI-…]} 错误行，而不是静默改变时长。
     * <p>🔴 这两条命令在本方法<b>不再被调用</b>之后依然有效（它们跑在同样的位置），
     * 但真正在生产路径上生效的是 {@code PrimordialEngineRecipeLogic} 那一侧的同一批绊线
     * （标签前缀 {@code "主机-多配方引擎-"}，便于在日志里分辨是哪条路径在跑）。
     *
     * <h2>🔴 已知行为变化：主机不再挂 GTCEu 原版电过载</h2>
     * {@code MachineBuilder} 的默认值是
     * {@code private RecipeModifier recipeModifier = new RecipeModifierList(GTRecipeModifiers.ELECTRIC_OVERCLOCK.apply(OverclockingLogic.NON_PERFECT_OVERCLOCK));}，
     * 而 {@code MachineBuilder.recipeModifier(x)} 的字节码是
     * {@code this.recipeModifier = x instanceof RecipeModifierList list ? list : new RecipeModifierList(x);}
     * —— <b>它是替换，不是叠加</b>。本工程的主机原先没调过 {@code .recipeModifier(...)}，
     * 所以吃的是那个默认电过载；现在换成这条自写链之后，<b>原版电过载不再生效</b>。
     * <p>这是<b>有意为之、且已上报</b>的取舍（照模块侧既有约定：{@code ModuleRegistry} 早就在用
     * {@code .recipeModifier(...)} 覆盖同一个默认值）。恢复路径与代价另见交付报告专节。
     */
    public static GTRecipe applyHostRecipeModifier(MetaMachine machine, GTRecipe recipe,
                                                   OCParams params, OCResult result) {
        if (!(machine instanceof PrimordialOmegaEngineMachine host)) {
            // 防御：这个修饰器只挂在主机定义上，落到别的机器就是配置错了 —— 原样放行，不放大也不缩水。
            return recipe;
        }
        // 链条入口的 duration 快照：用来证明「除了 N6 那一步，没有任何步骤改动 duration」。
        final int durationAtEntry = recipe.duration;
        final int limitedDuration = host.getLimitedDuration();
        // 🔴 门控只读【一次】：②（倍率）与 ④（耗电减免）必须用同一个数，
        //    「同一台主机下倍率与减免系数同源」这条不变式才算结构性成立，而不是靠"两次读恰好一样"。
        final int gateBonus = host.moduleSlotBonus();
        GTRecipe modified = recipe;

        // ① N4 并行（上限 = MAX_PARALLEL_INT；真正跑多少由输入量与输出空间决定）
        // 🔴 2026-09-26：本方法是【原生修饰链】（引擎路径不走它，见上面"两种情形"）。
        //    int 形参的上游出口只能吃 int ⇒ 这里必须传饱和桥（MAX_PARALLEL_INT = 2147483647），
        //    不能传 MAX_PARALLEL（long）本身；改前这里传的是 1<<30（2^30）——
        //    口径统一到"老山海的饱和值"，且这条路线上两值都远超任何真实配方需求。
        // 🔴 2026-09-27：实参从常量 MAX_PARALLEL_INT 改成读 getRecipeLogicMaxParallel() 的饱和桥。
        //    原因 = 新增的「并行数」玩家覆盖必须在这条路上也生效（只改引擎路径会出现"半生效"）。
        //    逐值对照：未覆盖时 getRecipeLogicMaxParallel() = MAX_PARALLEL（long）
        //    ⇒ Ints.saturatedCast(...) = MAX_PARALLEL_INT ⇒ 【与改动前那个实参完全相同】。
        modified = PrimordialRecipeEffects.applyParallel(modified, host,
                Ints.saturatedCast(host.getRecipeLogicMaxParallel()));
        modified = PrimordialRecipeEffects.auditDuration(modified, durationAtEntry, "主机-修饰链-N4-并行后");

        // ②③④ N3 产出倍率 → N6 时长（天花板）→ N5 耗电减免：
        //   🔴 这三步与【多配方引擎】路径共用同一份实现（PrimordialRecipeEffects.applyHostTailEffects），
        //      不许在本方法里另写一份 —— 两条路径各写一份的后果是静默漂移。
        //   注意此时 modified 已经是"做完并行"的配方，所以尾巴里不再碰并行。
        //   🔴 2026-09-22 第 4 个实参 = 【配方定义的原时长】（"永不变长"的天花板）：
        //      本路径下入参 recipe 就是配方定义原实例 ⇒ 链入口快照 durationAtEntry 即原时长，传它。
        //      （⚠️ 引擎路径<b>不能</b>照抄这一句 —— 那条路的入口是引擎装配后的成品，必须传 d0。）
        modified = PrimordialRecipeEffects.applyHostTailEffects(
                modified, gateBonus, limitedDuration, durationAtEntry, "主机-修饰链");

        // ───────────── ⑤ C（2026-09-21）：把"真实因子"交给 gtlcore 的抬头追踪器 ─────────────
        // 🔴 只写【显示用】的 tracker（内部是一张 WeakHashMap<MetaMachine, Multipliers>），
        //    **不改配方任何字段**：本调用读的是 recipe 的两个数值，不写 recipe。
        // 🔴 因子传 1.0/1.0（口径"甲"）：captureReduction 内部是【累乘】
        //    （multiply(...) = 旧值 × finiteOrOne(传入值)），而它在 context 路径下会先自己调
        //    calculate(baseRecipe, before) 把 |EUt(尾)|/|EUt(基)|/max(1,realParallels) 与
        //    duration(尾)/duration(基) 算出来 ⇒ 再传 f 就等于把 f 乘第二次（假数据）。
        // 🔴 传的是【链尾】那个 modified，不是入参 recipe。
        // 🔴 machine 必须是 begin() 的那个对象：begin/finish 由 gtlcore 的 RecipeModifierListMixin
        //    包在本修饰链外面调用（原文 `RecipeMultiplierTracker.begin(machine, recipe); for(...)
        //    modifier.apply(...); RecipeMultiplierTracker.finish(machine, modifiedRecipe);`），
        //    传进来的 machine 就是同一个引用。
        // 于是 Jade 抬头会画：耗能 = f（真实值，主机只吃耗电半边）、
        // 耗时 = min(原时长, limitedDuration) / 配方原时长（本段的 min 天花板 ⇒ 100 tick ⇒ 20 tick ⇒ 0.2）。
        // 🔴 诚实边界：本段是 min 口径 ⇒ 该因子恒 ≤ 1（永不变长）；**A 生产路径才会 > 1**（短配方被 dx 拖长），
        //    照样如实画，不许为了好看钳到 1.0。
        // ⚠️ 若外层的 begin() 没被调用（例如将来有人绕过 RecipeModifierList 直接调本方法），
        //    captureReduction 会走 fallback 分支写入 multiply(DEFAULT, 1.0, 1.0) = (1.0, 1.0)
        //    —— 退化成"没有信息"，而不是假数据。
        RecipeMultiplierTracker.captureReduction(machine, modified, 1.0D, 1.0D);

        return modified;
    }

    // ------------------------------------------------------- IModularMachineHost

    @Override
    public Set<IModularMachineModule<PrimordialOmegaEngineMachine, ?>> getModuleSet() {
        return modules;
    }

    /** 16 个模块位的世界坐标；每次实时算（规格 §3.2 禁止缓存成跨朝向常量）。 */
    @Override
    public BlockPos[] getModuleScanPositions() {
        return GtlAddCompat.moduleSlots(getPos(), getFrontFacing());
    }

    @Override
    public int getMaxModuleCount() {
        return MAX_MODULE_COUNT;
    }

    /**
     * 快照式清理：先复制 → 遍历副本断开 → 再 clear。
     *
     * <p>断开时会逐条打 {@code module_disconnected} 日志（验收 A6 抓这条序列）。
     *
     * <h2>🔴 本方法就是「模块被挖掉后，谁把它从 {@code modules} 里摘掉」的答案（2026-09 专项核查）</h2>
     * 模块方块坐在主机结构的 <b>J 位</b>（谓词 J = 空槽占位 ∪ 模块控制器方块）⇒ 模块被挖
     * ⇒ 主机结构失效 ⇒ 走 GTCEu/gtlcore 的通用链路
     * （gtlcore {@code mixin/mc/LevelMixin} → {@code MultiblockState#onBlockStateChanged} →
     * {@code IMultiController#onStructureInvalid()}）⇒ <b>打到本方法</b> ⇒
     * 逐模块 {@code removeFromHost(this)} ⇒ gtlcore 默认实现内层
     * {@code host.removeModule(this)} ⇒ {@code modules.remove(module)}。
     * <p>完整证据链、以及"为什么不照抄上游 {@code ForgeOfTheAntichristModuleBase#onMachineRemoved()}
     * 里的 {@code removeFromHost(host)}"见 {@code PrimordialModuleMachine#onMachineRemoved()} 的 javadoc。
     *
     * <h3>附：一条与谓词严格程度<b>无关</b>的框架事实（2026-09-22 查实，保留）</h3>
     * {@code MultiblockState#onBlockStateChanged} 在 pattern <b>仍然匹配</b>时走的是另一条分支
     * —— {@code checkPatternWithLock()==true} ⇒ {@code controller.onStructureFormed()} ⇒
     * 本机 {@code onStructureFormed()} ⇒ {@link #reconcileModules()} ⇒
     * {@code isModuleStillValid()} 里 {@code MetaMachine.getMachine(level, pos) == module} 判 false
     * ⇒ <b>同样摘掉</b>。这条覆盖"那一格被换成了另一种 J 合法方块 / 模块自己失型"之类
     * <b>主机保持成型</b>的场景，是 <b>2026-09-20 方案 A</b> 的既有语义。
     * <p>🔴 <b>关键点</b>：它<b>不依赖</b> J 谓词有多严 —— 谓词严格时主路径是上面那条失效链，
     * 谓词放宽时它才会成为主路径。字节码依据（{@code javap -p -c MultiblockState}，一般分支偏移 84–219）：
     * <pre>
     *   97: invokeinterface #296  // IMultiController.isFormed:()Z
     *  102: ifeq          148     ← isFormed()==false ⇒ 照样跳到 148 去做 checkPattern
     *  109: instanceof    #298  // class com/gregtechceu/gtceu/api/block/ActiveBlock
     *  112: ifeq          148     ← 新方块不是 ActiveBlock（例如空气）⇒ 照样跳到 148
     *  147: return               ← 唯一提前返回：isFormed && 新方块是 ActiveBlock && pos∈vaBlocks
     *  148: checkPatternWithLock()
     *  155: ifeq          182     ← false ⇒ onStructureInvalid()（182–199）
     *  174: onStructureFormed()   ← true  ⇒ 重新触发成型回调 ⇒ 上面的 reconcile 路径
     * </pre>
     *
     * <hr>
     * <p>⛔ <b>【2026-09-22 作废 · 原文照留，一字未改】</b>本节曾短暂写作（那是"J 谓词放宽为允许空气"那一版）：
     * <pre>
     *   🔴 「模块被挖掉后谁把它从 modules 里摘掉」——2026-09-22 已换机制
     *   现在不是本方法。J 谓词放宽为「空气 ∪ 占位方块 ∪ 模块控制器方块」之后，挖掉一台模块
     *   不再让主机结构失效 ⇒ 走 MultiblockState#onBlockStateChanged 的
     *   checkPatternWithLock()==true 分支 ⇒ onStructureFormed() ⇒ 本机的
     *   reconcileModules() ⇒ isModuleStillValid() 判 false ⇒
     *   module.removeFromHost(this) ⇒ gtlcore 默认实现内层 host.removeModule(this)
     *   ⇒ modules.remove(module)。
     *   本方法仍然是**主机自己**被挖（onMachineRemoved()）或结构真的失效
     *   （onStructureInvalid()）时的清场入口，职责未变。
     * </pre>
     * <b>作废原因</b>：用户在同一天撤回「拆一个模块不该停全机」
     * （原话「<b>我后悔了，我返回之前那个替换或者拆除模块就停止全机的决定</b>」）
     * ⇒ J 谓词恢复为不含 {@code Predicates.air()} ⇒ <b>主路径重新变回上面那条失效链</b>。
     * 上面那条"框架事实"因此<b>保留但降级为附注</b>（它本身仍然为真）。
     * 🔴 <b>那次放宽从未部署、从未进游戏。</b>
     */
    @Override
    public void safeClearModules() {
        if (modules.isEmpty()) return;
        final Level level = getLevel();
        final BlockPos hostPos = getPos();
        final List<IModularMachineModule<PrimordialOmegaEngineMachine, ?>> snapshot = new ArrayList<>(modules);
        for (IModularMachineModule<PrimordialOmegaEngineMachine, ?> module : snapshot) {
            // removeFromHost 会反过来调 host.removeModule(this) → 改的正是 modules 本身；
            // 因为遍历的是 snapshot，这里不会 ConcurrentModificationException。
            module.removeFromHost(this);
            ModuleSlotDiagnostics.logModuleDisconnected(level, modulePos(module), hostPos);
        }
        modules.clear();
    }

    // ------------------------------------------------------------- 生命周期

    /**
     * 成型事件计数（**诊断用**，不参与任何判定逻辑）。
     *
     * <p>为什么要它：用户日志里出现"一场 12 次成型 / 44 连接 / 46 断开"，而每次成型都会
     * {@code safeClearModules()} → 全部断开 → 重扫 → 重连。计数打进
     * {@code host_scan_begin ... reform=#N}，下一轮就能把这个数字与"玩家移动 / 区块装卸"对上，
     * 不必再靠推断。（根因候选：GTCEu {@code onPartUnload} → {@code MultiblockState.UNLOAD_ERROR}
     * → {@code MultiblockWorldSavedData.addAsyncLogic} → {@code asyncCheckPattern} 每 4 tick 重查。）
     */
    private int reformCount;

    /** 上一次调和式重扫的结果（**诊断用**，只为了折进 {@code host_scan_begin} 那一行）。 */
    private int lastReconcileKept;
    private int lastReconcileDropped;

    /**
     * 成型时的<b>调和式重扫</b>（2026-09-20 方案 A，队长批准；规格变更记录见
     * {@code docs/specs/phase-1-freeze-changelog.md} v1.3）。
     *
     * <h2>为什么不再"全清空"</h2>
     * 规格 §3.2「重新成型 = 清空旧连接 → 按固定位置重新识别」是在<b>"成型是罕见事件（玩家拆搭）"</b>
     * 这个前提上写的。但实测该前提不成立：GTCEu 的 {@code onPartUnload}（结构部件所在区块卸载）会给
     * {@code MultiblockState} 打上 {@code UNLOAD_ERROR} 并排进 {@code MultiblockWorldSavedData} 的异步重查队列，
     * 之后 {@code asyncCheckPattern} 每 4 tick 重查一次 —— 主机跨约 150 区块，玩家一走就反复触发。
     * 用户日志实测：**一场 12 次成型 / 44 连接 / 46 断开**，而每次都全清空 → 全部断开 → 重连。
     *
     * <h2>现在怎么做</h2>
     * 只断开<b>真的失效</b>的模块（不在槽位上了 / 自己没成型了 / 同位置被换成了另一台机器），
     * 其余原样保留；然后用 gtlcore 的 {@code scanAndConnectModules()} 补连（它对该主机幂等：
     * {@code connectToHost} 只在换宿主时才 {@code removeFromHost}，其余是 {@code setHost} + {@code Set.add}）。
     *
     * <h2>没动的</h2>
     * {@link #onStructureInvalid()} 里的 {@code safeClearModules()} <b>一个字都没改</b>——
     * 那才是规格 §3.2 真正要保的语义，验收 A6「断链重连」走的正是那条路。
     *
     * <h2>已知残余风险（已裁定：按现状交付，且这里有一条不变式说明它为什么不用管）</h2>
     * 若同一台模块同时落在<b>两台主机</b>的槽位范围内（两台主机结构重叠），本方法会"保留"它，
     * 而不让它改挂另一台主机。
     *
     * <p><b>为什么不用管（不变式，不是"概率低"）</b>：gtlcore 的
     * {@code IModularMachineModule.connectToHost(H)} 字节码是
     * <pre>
     *   old = getHost();
     *   if (old != null && old != host) removeFromHost(old);   ← 加入新宿主前先把自己从旧宿主摘掉
     *   host.addModule(this);
     * </pre>
     * ⇒ <b>一个模块不可能同时存在于两台主机的模块集合里</b>，于是
     * {@code this.getModules()} 里每个模块的 {@code getHost()} <b>按构造就等于 {@code this}</b>
     * （除非有人绕过 {@code connectToHost} 手写 {@code addModule}，而全工程没有第二处调用）。
     * ⇒ 再加一条 {@code && module.getHost() == this} 是**空操作**：唯一效果是"万一不变式被打破就把它丢掉"，
     * 而丢掉之后立刻重扫重连，**反而制造出本方法要消灭的那种 churn**。
     * ⇒ 队长裁决：**不加**（收益为零、理论上有副作用）。
     */
    private void reconcileModules() {
        final Level level = getLevel();
        if (level == null || level.isClientSide()) {
            // 拿不到服务端世界时退回老路径（保守）
            safeClearModules();
            lastReconcileKept = 0;
            lastReconcileDropped = modules.size();
            return;
        }

        final BlockPos[] positions = getModuleScanPositions();
        final List<IModularMachineModule<PrimordialOmegaEngineMachine, ?>> snapshot = new ArrayList<>(modules);
        int kept = 0;
        int dropped = 0;
        for (IModularMachineModule<PrimordialOmegaEngineMachine, ?> module : snapshot) {
            if (isModuleStillValid(level, positions, module)) {
                kept++;
                continue;
            }
            module.removeFromHost(this);
            ModuleSlotDiagnostics.logModuleDisconnected(level, modulePos(module), getPos());
            dropped++;
        }
        lastReconcileKept = kept;
        lastReconcileDropped = dropped;

        // 幂等补连：已连着的模块只会被 setHost/addModule 再刷一次，不会产生 module_disconnected。
        scanAndConnectModules();
    }

    /**
     * 模块是否<b>仍然算数</b>：①它自己还成型；②它还在这 16 个槽位之一上；③那一格现在的机器就是它。
     *
     * <p>③ 是必须的：同位置被换成**另一台**机器时，老实例必须断开（否则主机集合里会留下幽灵模块）。
     */
    private boolean isModuleStillValid(Level level, BlockPos[] positions,
                                       IModularMachineModule<PrimordialOmegaEngineMachine, ?> module) {
        if (!module.isFormed()) {
            return false;
        }
        final BlockPos mp = modulePos(module);
        for (BlockPos pos : positions) {
            if (pos.equals(mp)) {
                return MetaMachine.getMachine(level, pos) == module;
            }
        }
        return false;
    }

    @Override
    public void onStructureFormed() {
        super.onStructureFormed();
        reformCount++;
        // 方案 A（队长批准）：调和式重扫，代替原来的 safeClearModules() + scanAndConnectModules()。
        reconcileModules();
        // 门控兜底：成型时按槽内实况刷新一次（不管落盘数据是何时读进来的，这里都对齐一次）。
        if (!isRemote()) {
            refreshMatterBonusLevel();
        }
        // ⛔ 【2026-09-22 删除】此处原为 startRunningSecsTicker()（每台主机一个服务端 20-tick 订阅）。
        //    「运行秒数」已随【颜色改随物质模块等级】一并删除，不再需要这个订阅。
        logHostScanDiagnostics();
    }

    /**
     * 载入兜底：区块重新加载后按槽内实况刷新门控。
     *
     * <p>为什么不能只靠 {@code onContentsChanged}：那个回调只在"槽内容<b>发生改变</b>"时才响，
     * 而机器从 NBT 恢复出来的那次<b>不算改变</b>（它只是被反序列化）⇒ 不补这一下，
     * 存档重进后 {@code matterBonusLevel} 会停在旧值/0，客户端半径也随之错，直到玩家手动动一下槽。
     */
    @Override
    public void onLoad() {
        super.onLoad();
        if (!isRemote()) {
            refreshMatterBonusLevel();
            // 🔴 取证探针 [SHANHAI-SETBLOCK]（2026-09-26）：把本主机坐标 + 16 个模块位坐标登记进探针的
            //    「定格表」。本方法 = 【区块重载（用户退出后重进存档）时机器被反序列化】那一拍必被调用
            //    ⇒ 重进之后发生在这个坐标上的【任何】方块写入都会被记录，
            //    ★包括「那一格此刻已经不是我方方块」的情形（模块被换成 kubejs:steam_assembly_block 之后
            //    它就不在我方命名空间里了，只靠"旧方块是我方方块"这一条抓不到随后的每一次写入）。
            //    探针内部全程 try/catch 自吞，绝不从机器加载链上抛出去（见 ModuleSetBlockWatch §5）。
            ModuleSetBlockWatch.registerHost(this);
        }
    }

    @Override
    public void onStructureInvalid() {
        super.onStructureInvalid();
        // ⛔ 【2026-09-22 删除】此处原为 stopRunningSecsTicker()（随 runningSecs 一并删除）。
        // 🔴 规格 §3.2 的语义保留在这里，一个字都不许动（验收 A6 走这条）。
        safeClearModules();
    }

    /**
     * 机器被拆掉时的收尾：<b>断开全部模块 + 把「专属物质模块槽」里的物品掉到世界上</b>。
     *
     * <h2>🔴 为什么必须有第二件事（2026-09 用户报的 bug）</h2>
     * 用户原话：「<b>模块和主机被挖掉的时候并不会掉落里面的物品（物质模块）</b>」。
     * 主机这一侧丢失的是 {@link #matterBonusSlot} 里那一格（玩家手放进去的物质模块，容量 64），
     * 破坏方块时它<b>随机器一起消失</b>，玩家净亏一件 17 级物质模块。
     *
     * <h2>上游原文（照抄，不自造）</h2>
     * <b>① 派发点</b> —— {@code com.gregtechceu.gtceu.api.block.MetaMachineBlock#m_6810_}（= 原版
     * {@code Block#onRemove}），{@code javap -p -c} 反编译逐字：
     * <pre>
     *   27: aload         6
     *   29: instanceof    #186                // class com/gregtechceu/gtceu/api/machine/feature/IMachineLife
     *   32: ifeq          49
     *   42: aload         7
     *   44: invokeinterface #480,  1          // InterfaceMethod …feature/IMachineLife.onMachineRemoved:()V
     * </pre>
     * ⇒ <b>全 {@code libs/}（33 个 jar）里只有一个派发点就是这个 {@code MetaMachineBlock}</b>
     * 〔口径：33 个 jar 全量扫 class，共 <b>68</b> 个 class 的字节里含字面量 {@code onMachineRemoved}；
     * 逐个 {@code javap -p} 复查「<b>它自己是否声明了这个方法</b>」，只有 {@code MetaMachineBlock}
     * 是「引用了但自己不声明」⇒ 有且仅有它是调用方，其余 67 个都是实现者/覆写者〕
     * ⇒ <b>只要实现该接口，就一定收到回调</b>，不需要自己挂事件、也不需要 mixin。
     *
     * <p><b>② 掉落工具</b> —— {@code com.gregtechceu.gtceu.api.machine.MetaMachine#clearInventory
     * (com.lowdragmc.lowdraglib.side.item.IItemTransfer)}，{@code javap -p -c} 反编译逐字：
     * <pre>
     *   14: invokeinterface #728,  2  // InterfaceMethod …IItemTransfer.getStackInSlot:(I)Lnet/minecraft/world/item/ItemStack;
     *   24: ifne          55
     *   32: invokeinterface #738,  3  // InterfaceMethod …IItemTransfer.setStackInSlot:(ILnet/minecraft/world/item/ItemStack;)V
     *   38: invokeinterface #741,  1  // InterfaceMethod …IItemTransfer.onContentsChanged:()V
     *   52: invokestatic  #747       // Method net/minecraft/world/level/block/Block.m_49840_:(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/item/ItemStack;)V
     * </pre>
     * 即「<b>逐槽取出 → 清空该槽 → {@code onContentsChanged()} → {@code Block.popResource(level, pos, stack)}</b>」。
     * <b>不自己 {@code new ItemEntity}</b>（原话要求），这就是 GTCEu 现成的掉落工具。
     *
     * <p><b>③ 调用形状</b> —— {@code com.gregtechceu.gtceu.api.machine.SimpleTieredMachine#onMachineRemoved()}，
     * 反编译逐字：
     * <pre>
     *    0: aload_0
     *    1: invokespecial #366   // Method com/gregtechceu/gtceu/api/machine/WorkableTieredMachine.onMachineRemoved:()V
     *    9: invokevirtual #370   // Method clearInventory:(Lcom/lowdragmc/lowdraglib/side/item/IItemTransfer;)V
     * </pre>
     * 即「<b>先 super，再逐容器 {@code clearInventory(...)}</b>」。本方法的语句顺序与之逐字对齐。
     * <p>传参口径同 {@code WorkableTieredMachine#onMachineRemoved()} /
     * {@code ObjectHolderMachine#onMachineRemoved()} —— 它们传的都是
     * {@code NotifiableItemStackHandler.storage}（{@code getfield …/NotifiableItemStackHandler.storage:
     * Lcom/lowdragmc/lowdraglib/misc/ItemStackTransfer;} 之后才 {@code invokevirtual clearInventory}），
     * 所以这里也传 {@code .storage}，不传 handler 本身。
     *
     * <h2>⚠️ 本条不是"改行为"，是"补一条 GTCEu 元机器方块本来就有的语义"</h2>
     * 本工程的多方块基类链
     * （{@code MetaMachine → MultiblockControllerMachine → WorkableMultiblockMachine →
     * WorkableElectricMultiblockMachine}）<b>一个都不实现 {@code IMachineLife}</b>
     * （四者 {@code javap} 的类头逐字核对过）⇒ 多方块主机天然收不到这个回调。
     * 本类在类头已显式 {@code implements IMachineLife}（那是为断模块加的），
     * 但<b>当时只补了"断模块"、漏了"掉物品"</b> —— 这就是本 bug 的成因。
     */
    @Override
    public void onMachineRemoved() {
        IMachineLife.super.onMachineRemoved();
        // ⛔ 【2026-09-22 删除】此处原为 stopRunningSecsTicker()（随 runningSecs 一并删除）。
        safeClearModules();
        // 断模块在前（清空强引用），掉物品在后 —— 与 SimpleTieredMachine 的语句顺序同形。
        // 幂等性：clearInventory 走的是"取出即清空"，清完再调一次时槽已空 ⇒ 不会翻倍。
        clearInventory(matterBonusSlot.storage);
    }

    // ------------------------------------------------------------------ 诊断

    /**
     * 成型后输出 16 行 {@code host_scan}（规格 §4.3，验收 A3），
     * 并把本轮真正接上的模块按槽位号打成 {@code module_connected}（验收 A4）。
     *
     * <p>槽位号是「扫描位数组下标」，靠「该下标位置上的机器就是本次连接上的模块」反查得到，
     * 不依赖模块自报。
     */
    private void logHostScanDiagnostics() {
        final Level level = getLevel();
        if (level == null || level.isClientSide()) return;
        final BlockPos hostPos = getPos();
        final BlockPos[] positions = getModuleScanPositions();

        // 逐槽三态（2026-09 加）：把「日志里没有 slot=N」从推断变成实测 ——
        // 到底是"那儿没有机器"、"有机器但没成型"、还是"成型了却没被收下"。
        // gtlcore 的 isValidModule 要求模块自身 isFormed()，所以 not_formed 是最常见的漏收原因。
        String[] states = new String[positions.length];
        for (int slot = 0; slot < positions.length; slot++) {
            MetaMachine machine = MetaMachine.getMachine(level, positions[slot]);
            if (machine == null) {
                states[slot] = ModuleSlotDiagnostics.STATE_NO_MACHINE;
            } else if (!(machine instanceof IModularMachineModule<?, ?> module)) {
                states[slot] = ModuleSlotDiagnostics.STATE_NOT_MODULE;
            } else if (!module.isFormed()) {
                states[slot] = ModuleSlotDiagnostics.STATE_NOT_FORMED;
            } else if (modules.contains(module)) {
                states[slot] = ModuleSlotDiagnostics.STATE_OK;
            } else {
                states[slot] = ModuleSlotDiagnostics.STATE_FORMED_UNCONNECTED;
            }
        }

        ModuleSlotDiagnostics.logHostScan(level, hostPos, getFrontFacing(), positions, states, reformCount,
                lastReconcileKept, lastReconcileDropped);
        for (int slot = 0; slot < positions.length; slot++) {
            if (ModuleSlotDiagnostics.STATE_OK.equals(states[slot])) {
                MetaMachine machine = MetaMachine.getMachine(level, positions[slot]);
                if (machine != null) {
                    ModuleSlotDiagnostics.logModuleConnected(level, machine.getPos(), hostPos, slot);
                }
            }
        }
    }

    @Nullable
    private static BlockPos modulePos(IModularMachineModule<?, ?> module) {
        return module instanceof MetaMachine machine ? machine.getPos() : null;
    }

    // --------------------------------------------------------------------- GUI

    /**
     * 规格 §4.2 要求 GUI 文本含：<b>已连接模块数 / 主机工作状态 / 结构未成型提示</b>。
     *
     * <p>这里用字面量而不是 {@code Component.translatable}：语言文件归 java-assets 管，
     * 缺 lang 条目时 translatable 会直接把裸 key 显示在 GUI 上，恰好会让上面三条判定落空。
     *
     * <p>🔴 2026-09-21 改名：行首标签由 {@code [青铜神锻]} 改为 {@code [原始终焉引擎]}。
     * <b>理由（这是一处判断，不是机械替换）</b>：① 用户同一轮里要求把「青铜神锻」这个名字从本 mod 上拿掉
     * （原话「把后面那个青铜神锻还有括号里面的后缀全去了」）；② 本轮把 GUI 标题的 lang 键补成了
     * 「原始终焉引擎」（上游官方名，见 {@code assets/shanhai/lang/zh_cn.json}）⇒ 同一个界面里
     * 标题写「原始终焉引擎」而行首标签写「青铜神锻」会同时出现两个名字；
     * ③ 该 lang 名与 {@code gradle.properties} 的 {@code mod_description}（Primordial Omega Engine）一致。
     * 若要回退，只改这一处字面量即可（两行同字符串）。
     */
    @Override
    public void addDisplayText(List<Component> textList) {
        super.addDisplayText(textList);
        if (!isFormed()) {
            textList.add(Component.literal("[原始终焉引擎] ").withStyle(ChatFormatting.GOLD)
                    .append(Component.literal("结构未成型").withStyle(ChatFormatting.RED)));
            // ⛔ 【2026-09-22 作废 · 用户撤回 · 原文照留】本行曾短暂改为：
            //     Component.literal("16 个模块位可留空：空槽不用放方块（拆掉模块不会停整机）")
            //   作废原因：用户裁决 A「拆一个模块不该停全机」后又撤回
            //   （原话「我后悔了，我返回之前那个替换或者拆除模块就停止全机的决定」）
            //   ⇒ J 谓词恢复为不含 Predicates.air()（见 ShanhaiMachines.moduleSlotPredicate()）
            //   ⇒ 空槽仍需占位方块，故文案回到原句。**那次改动从未部署、从未进游戏。**
            textList.add(Component.literal("16 个模块位每格都要有方块：空槽放 kubejs:steam_assembly_block 占位")
                    .withStyle(ChatFormatting.GRAY));
            textList.add(Component.literal("已连接模块：0 / " + MAX_MODULE_COUNT).withStyle(ChatFormatting.GRAY));
            return;
        }
        textList.add(Component.literal("[原始终焉引擎] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal("结构已成型").withStyle(ChatFormatting.GREEN)));
        textList.add(Component.literal("已连接模块：" + getFormedModuleCount() + " / " + MAX_MODULE_COUNT)
                .withStyle(ChatFormatting.GRAY));
        textList.add(Component.literal("主机工作状态：" + (recipeLogic.isWorking() ? "运行中" : "待机"))
                .withStyle(ChatFormatting.GRAY));

        // 门控的 GUI 可见性：不开日志也能一眼看出"生效了没有、几级"。
        // 客户端走 @DescSynced 镜像（不重算），服务端走实况 —— 都经 moduleSlotBonus() 这一个入口。
        int bonus = moduleSlotBonus();
        textList.add(bonus > 0
                ? Component.literal("物质模块加成：Lv." + bonus + "（满 64 生效）").withStyle(ChatFormatting.AQUA)
                : Component.literal("物质模块加成：未生效（专属槽需放满 64 个同种物质模块）")
                        .withStyle(ChatFormatting.DARK_GRAY));

        // N3 产出倍率的效果：门控没生效就是 ×1，生效就是 ×2..×18。
        // 用字面量而不是 translatable / 也不查表：与上面两行同一条约定（lang 归 java-assets 管，
        // 缺条目时 translatable 会把裸 key 画在 GUI 上），而且玩家不开日志就能对账
        // 「100 并行 × 3 倍 ⇒ 输入 100 铁 ⇒ 产出 300 齿轮」里的那个 3。
        int outputMultiplier = PrimordialRecipeEffects.outputMultiplier(bonus);
        textList.add(outputMultiplier > 1
                ? Component.literal("产出倍率：×" + outputMultiplier + "（额外产出 " + bonus + " 份）")
                        .withStyle(ChatFormatting.LIGHT_PURPLE)
                : Component.literal("产出倍率：×1（未生效，专属槽满 64 后 ×2…×18）")
                        .withStyle(ChatFormatting.DARK_GRAY));

        // N6：配方最短耗时 —— 🔴 语义是【下限】（不是恒等）：实际时长 = max(1, dx, 这个数)，
        // dx = 整批总能量 ÷ 最大电压 ⇒ 它【会】随配方原时长与并行数变（短配方会被它拖长）；
        // 这一行也是"这个旋钮到底写进去没有"的现场证据（侧栏 LimitedDurationConfigurator 改的就是它）。
        textList.add(Component.literal("配方时长：下限 " + limitedDuration + " tick"
                        + "（实际时长 = max(整批总能量÷最大电压, 此值)；侧栏可调 "
                        + PrimordialRecipeEffects.MIN_LIMITED_DURATION
                        + "–" + PrimordialRecipeEffects.MAX_LIMITED_DURATION + "）")
                .withStyle(ChatFormatting.GRAY));
        // N5 耗电：主机只吃「耗电减免」这半边（用户原话「主机耗电吃N5减免」），不吃时长减免。
        // 🔴 2026-09-21 改正方向：这里的「×f」是【成本系数】= 1 − 减免比例 R，即「原耗电 × f」。
        //    旧口径把 R 当成本系数印在这里，于是 Lv.1 显示 ×0.4181（其实是减免 41.8%，成本应为 ×0.5819）。
        // 与模块侧一样用 moduleSlotBonus() 同一个门控值取系数，所以这一行就是配方修饰器要乘的那个数。
        double energyFactor = PrimordialRecipeEffects.reductionFactor(bonus);
        double reductionRatio = 1.0D - energyFactor;
        textList.add(Component.literal("主机耗电减免：×" + String.format(java.util.Locale.ROOT, "%.4f", energyFactor)
                        + "（原耗电 × 此系数，减免 "
                        + String.format(java.util.Locale.ROOT, "%.1f", reductionRatio * 100.0D) + "%）"
                        + (bonus > 0 ? "" : "（未生效，专属槽满 64 后生效）"))
                .withStyle(bonus > 0 ? ChatFormatting.YELLOW : ChatFormatting.DARK_GRAY));
        // 🔴 2026-09-27 订正：原文写死「2^30」。那是 MAX_PARALLEL 变成 Long.MAX_VALUE 之前的旧值，
        //    属于本工程红线「活的界面上不许放假数据」点名的形态 ⇒ 改成读真实值（同一条链上的生效值）。
        //    有玩家覆盖时如实写明是玩家设定的数（覆盖是"上限"，不是"精确并行"）。
        // 🔴🔴 2026-10-02 第二轮订正：判据从 getParallelOverride() 改成 getEffectiveOverride()。
        //    **旧写法在"电力自动开着 + 框里填过数"时会印「（玩家设定）」——那是一句假数据**：
        //    按用户裁决 A，电力自动开着时框里的值【完全不参与运算】（见 ParallelOverrideMachine
        //    #getEffectiveOverride）。⇒ 新增一档，把"电力自动把框里的值接管了"如实说出来。
        textList.add(Component.literal("并行上限：" + getEffectiveParallel()
                        + (isPowerAutoParallel()
                                ? "（电力自动；框里的数不参与，实际并行由能源仓功率与输入量/输出空间决定）"
                                : (getEffectiveOverride() > ParallelOverrideMachine.PARALLEL_AUTO
                                        ? "（玩家设定；实际并行由输入量与输出空间决定）"
                                        : "（本机上限；实际并行由输入量与输出空间决定）")))
                .withStyle(ChatFormatting.GRAY));
    }

    // ------------------------------------------------------- 物质模块专属槽的 GUI

    /**
     * 把「物质模块专属槽」画进主机 GUI。
     *
     * <p>🔴 <b>与模块侧同一个坑</b>（{@code PrimordialModuleMachine#createUIWidget} 的 javadoc 记过）：
     * {@link NotifiableItemStackHandler} 只是机器特性，<b>不会自动长出一个格子</b>。
     * 少了这个覆写，玩家在界面上找不到这个槽 ⇒ {@link #moduleSlotBonus()} 恒为 0、N7 半径永远是 13.0、
     * N3 倍率永远不生效，<b>而且不报任何错</b>。
     *
     * <p>坐标<b>照抄模块侧已跑通的布局</b>（右侧 {@code width-30}，纵向 {@code height-68}）。
     * 基类 {@code WorkableElectricMultiblockMachine#createUIWidget} 实测返回
     * {@code new WidgetGroup(0, 0, 190, 125)}（{@code javap -c} 字节码：{@code iconst_0/iconst_0/
     * sipush 190/bipush 125}），所以本槽落在 (160, 57)，在滚动区 ({@code 4,4,182,117}) 之内、不越界。
     */
    @Override
    public Widget createUIWidget() {
        Widget widget = super.createUIWidget();
        if (widget instanceof WidgetGroup group) {
            var size = group.getSize();
            SlotWidget bonusSlotWidget = new SlotWidget(matterBonusSlot.storage, 0,
                    size.width - 30, size.height - 68, true, true);
            bonusSlotWidget.setBackground(SlotWidget.ITEM_SLOT_TEXTURE);
            bonusSlotWidget.setHoverTooltips(
                    Component.literal("§d§l物质模块专属槽"),
                    Component.literal("§7只收「山海的神人私货」的 17 种物质模块"),
                    Component.literal("§7必须放满 §b64§7 个§b同一种§7模块才生效"),
                    Component.literal("§7生效后：中子星半径随模块等级增大（Lv.17 = 35.1）"));
            group.addWidget(bonusSlotWidget);
        }
        return widget;
    }

    // ------------------------------------------------------- 中心球体渲染风格

    /** 当前球体渲染风格（TESR 与 GUI 都读它）。 */
    public PrimordialSphereStyle getSphereStyle() {
        return PrimordialSphereStyle.byIndex(sphereStyle);
    }

    /**
     * 切换球体渲染风格。<b>只有真的变了才写</b>，并且必须 {@code notifyBlockUpdate()}
     * —— 那是 {@code @DescSynced} 字段把值送到客户端的唯一动作。
     */
    public void setSphereStyle(PrimordialSphereStyle style) {
        if (style == null || sphereStyle == style.ordinal()) return;
        sphereStyle = style.ordinal();
        notifyBlockUpdate();
    }

    // ------------------------------------------------------- 始终渲染为工作状态（第 7 个按钮）

    /**
     * <b>「始终渲染为工作状态」的语言键</b>（zh_cn / en_us 两份都写了）。
     *
     * <p>⚠️ <b>这四处是本类唯一使用 {@code translatable} 的地方，是有意偏离</b>：
     * 本类其它 GUI 文案（{@code addDisplayText}、{@code sphereStyleTooltips}、
     * {@code StarRenderConfigurator} 里的全部标签）一律用 {@code Component.literal} 字面量，
     * 理由是「{@code lang} 归 java-assets 管，缺条目时 {@code translatable} 会把裸 key 直接画在 GUI 上」。
     * 本次是按交办方明确要求「中文文案写进 {@code assets/shanhai/lang/zh_cn.json}，并顺便补 {@code en_us}」
     * ⇒ 两个语言文件<b>同批写入</b>，不存在"只写中文导致英文端裸显"的那种缺口。
     * 若将来 {@code lang} 侧被回退，这里会退化成画裸 key —— <b>这是已知取舍，不是疏忽</b>。
     *
     * <p>命名沿用本工程 {@code <分类>.<模组id>.<标识>} 的习惯（{@code item.shanhai.*} /
     * {@code block.shanhai.*} / {@code fluid.shanhai.*}），本按钮没有注册表 id，
     * 故用 {@code gui.shanhai.star_always_working} 这一族（本工程首个 GUI 文案键）。
     */
    public static final String LANG_KEY_STAR_ALWAYS_WORKING = "gui.shanhai.star_always_working";

    /** {@link #LANG_KEY_STAR_ALWAYS_WORKING} 的「开」态词（tooltip 首行拼在标题之后）。 */
    public static final String LANG_KEY_STAR_ALWAYS_WORKING_ON =
            "gui.shanhai.star_always_working.state.on";

    /** {@link #LANG_KEY_STAR_ALWAYS_WORKING} 的「关」态词（默认态）。 */
    public static final String LANG_KEY_STAR_ALWAYS_WORKING_OFF =
            "gui.shanhai.star_always_working.state.off";

    /** {@link #LANG_KEY_STAR_ALWAYS_WORKING} 的三行说明（{@code .tip.0/.1/.2}，下标即行序）。 */
    public static final String LANG_KEY_STAR_ALWAYS_WORKING_TIP = "gui.shanhai.star_always_working.tip.";

    /**
     * <b>当前开关值</b>（渲染侧读它）。
     *
     * <p>客户端读的是 {@code @DescSynced} 镜像；服务端读实况 —— 两侧读的是同一个方法，
     * 与 {@link #moduleSlotBonus()} / {@link #canControlStarRender()} 同一条纪律。
     */
    public boolean isStarAlwaysWorking() {
        return starAlwaysWorking;
    }

    /**
     * 切换「始终渲染为工作状态」。<b>只有真的变了才写</b>，并且必须 {@code notifyBlockUpdate()}
     * —— 那是 {@code @DescSynced} 字段把值送到客户端的唯一动作（与 {@link #setSphereStyle} 同款）。
     *
     * <p>⚠️ <b>刻意不做任何门控</b>（不判 {@link #canControlStarRender()}、不判球体风格档）：
     * 它改的是 {@code getSmoothTick}，而<b>轨道环与（宇宙模式的）行星同样吃这个值</b>
     * ⇒ 两种球体风格下都有可见效果，不存在"按了没反应的假开关"。
     * 这与 ④ 球体风格开关（同样无门控）是同一档东西，而<b>不是</b>中子星面板里那批外观旋钮
     * （那批旋钮 2026-10-18 起也已不再受专属槽等级门控）。
     */
    public void setStarAlwaysWorking(boolean value) {
        if (starAlwaysWorking == value) return;
        starAlwaysWorking = value;
        notifyBlockUpdate();
    }

    /**
     * 第 7 个按钮（{@code IFancyConfiguratorButton.Toggle}）的 tooltip。
     *
     * <p>结构照 {@link #sphereStyleTooltips(boolean)}：<b>首行说清"这是什么 + 现在是哪个状态"</b>，
     * 后面几行说清代价与边界。
     * <p>🔴 「所有人都会看到」这一句<b>不可省</b>：本开关写的是 {@code @Persisted @DescSynced} 字段
     * + {@code notifyBlockUpdate()}，即<b>机器状态</b>，不是"只影响我自己"的客户端设置
     * （真正只影响自己的只有 {@code shanhai-common.toml} 里的那几项）。
     */
    private static List<Component> starAlwaysWorkingTooltips(boolean pressed) {
        List<Component> tooltips = new ArrayList<>(3);
        tooltips.add(Component.translatable(LANG_KEY_STAR_ALWAYS_WORKING).withStyle(ChatFormatting.YELLOW)
                .append(Component.literal("：").withStyle(ChatFormatting.GRAY))
                .append(Component.translatable(pressed
                                ? LANG_KEY_STAR_ALWAYS_WORKING_ON
                                : LANG_KEY_STAR_ALWAYS_WORKING_OFF)
                        .withStyle(pressed ? ChatFormatting.AQUA : ChatFormatting.DARK_GRAY)));
        tooltips.add(Component.translatable(LANG_KEY_STAR_ALWAYS_WORKING_TIP + "0")
                .withStyle(ChatFormatting.GRAY));
        tooltips.add(Component.translatable(LANG_KEY_STAR_ALWAYS_WORKING_TIP + "1")
                .withStyle(ChatFormatting.GOLD));
        tooltips.add(Component.translatable(LANG_KEY_STAR_ALWAYS_WORKING_TIP + "2")
                .withStyle(ChatFormatting.DARK_GRAY));
        return tooltips;
    }

    /**
     * GUI 侧栏的球体风格开关（上游同款位置：{@code attachConfigurators}）。
     *
     * <p>⚠️ <b>为什么可以在 common 的机器类里覆写它</b>：参数类型 {@code ConfiguratorPanel} 本身是
     * {@code @OnlyIn(CLIENT)}，但 {@code IFancyUIMachine.attachConfigurators} 声明在一个<b>非</b>
     * {@code @OnlyIn} 的接口里 —— 也就是 GTCEu 自己的架构就已经把这个客户端类型放进了 common 接口的
     * 方法描述符。实证：覆写它的类里有大量 common 侧机器类，例如
     * {@code com/gregtechceu/gtceu/api/machine/SimpleTieredMachine.class}、
     * {@code org/gtlcore/gtlcore/common/machine/multiblock/part/ae/MEMolecularAssemblerIOPartMachine.class}、
     * {@code com/gtladd/gtladditions/common/machine/multiblock/controller/BasicOreProcessorMachine.class}
     * （对三个 jar 做 {@code findstr attachConfigurators} 全量搜出来的，不是抽样）。
     * 且该方法只在客户端 GUI 打开时被调用。
     */
    @Override
    public void attachConfigurators(ConfiguratorPanel panel) {
        // ─────────── 🔴 2026 本轮：侧栏按钮列【太长】⇒ 改成超级样板总成那种 4 行网格 ───────────
        // 用户原话（逐字）：「还有一件事，主机的左下角列表太长了，可以改成像超级样板总成这样的」。
        //
        // 🔴 本机侧栏一共 7 个 tab（按 attach 顺序自上而下）：
        //     ① 工作开关      —— GTCEu `IFancyUIMachine.attachConfigurators` 的 default
        //                        （本机经 IRecipeLogicMachine → IWorkable → IControllable 命中）
        //     ② 超频          —— 同上 default（本机 implements IOverclockMachine，见 WorkableElectricMultiblockMachine
        //                        的类声明，javap 核实）
        //     ③ 球体风格      —— 本方法下面挂的（中子星 / 鸿蒙微型宇宙）
        //     ④ 配方最短耗时  —— gtladditions 的 LimitedDurationConfigurator
        //     ⑤ 中子星渲染    —— 本工程的 StarRenderConfigurator
        //     ⑥ 始终渲染为工作—— 本工程的 Toggle
        //     ⑦ 并行数        —— 本工程的 ParallelOverrideConfigurator
        //   ⇒ 7 × (24+2) − 2 = 180px 的一条竖列，底对齐窗口下沿 ⇒ 面板顶部一路顶到 guiHeight−184，
        //     这就是用户说的「太长了」。
        //   ⚠️ 「批处理」那个 tab 不在列表里：本机 canConfigureBatchProcessing() 返回 false，
        //      gtlcore 的 BatchConfiguratorMixin → IBatchMachine.attachBatchConfigurator 直接 return。
        //
        // 🔴 改动本身只有一行 —— 复用 gtlcore 已有的同一套布局（不是新写 mixin、不是布局代码）：
        //    MEStorageConfiguratorTabLayout（org.gtlcore.gtlcore.client.gui）把 tab 按
        //    TABS_PER_COLUMN = 4 摆成"每列 4 行、第 5 个起向左另起一列"的网格，
        //    并由 gtlcore 的 ConfiguratorPanelMixin 在每次 attachConfigurators 的 TAIL 上自动重排。
        //    它原本只对 MEHatchPartMachine / MEPatternBufferPartMachineBase（= 超级样板总成）打开
        //    （gtlcore FancyMachineUIWidgetMixin @HEAD of setupFancyUI），本机不在那两个类型里 ⇒ 默认是关的。
        //    ⇒ 我们把它打开，观感就与超级样板总成【完全一致】（同一段算法，不是"照着做一遍"）。
        //    详细证据链 / 三条备选路的取舍，见 ConfiguratorTabGridCompat 的类注释。
        //
        // ⚠️ 必须放在【第一次 panel.attachConfigurators(...) 之前】（即 super 之前）：
        //    gtlcore 的重排挂在 attachConfigurators 的 TAIL 上，开关之前挂的 tab 要等下一次挂载才被重排。
        ConfiguratorTabGridCompat.enableTabGrid(panel);
        super.attachConfigurators(panel);

        // ───────────────────────── 三个控件的"双形态"总开关 ─────────────────────────
        // 🔴 2026-10-25 第十九改（用户原话）：
        //     ①「这个角速度干脆让玩家可以在主机控制器里面调得了，可以修改那个没用的调整中子星的大小
        //        与颜色的按钮」
        //     ②「中心球体渲染风格，你直接改成可以修改黑洞的半径和是否开启引力透镜效果得了」
        //     ③「然后始终渲染为工作状态的按钮你也用起来，直接做成开关特效渲染」
        //
        // 🔴 为什么必须分支、而不是无条件把这三个控件换成新的：
        //   挂载点在本类（基类），而本类本体 = 老主机 `shanhai:primordial_omega_engine`，
        //   它仍然注册着、仍然能摆（`ShanhaiMachines:182`）。新的 6 个字段只在
        //   `GuixuVisionCoreMachine` 上 ⇒ 无条件替换的话老主机会得到【三个点了没反应的假开关】
        //   （本工程明令禁止的形态），并且丢掉它唯一的中子星外观入口。
        //   ⇒ 归墟视界枢核 = 新用途；老主机 = 原来的三个控件，一字未改。
        final GuixuVisionCoreMachine guixuVisionCore =
                this instanceof GuixuVisionCoreMachine host ? host : null;
        if (guixuVisionCore == null) {
            // ③ 球体风格（老主机原样保留）
            panel.attachConfigurators(new IFancyConfiguratorButton.Toggle(
                            GuiTextures.BUTTON_SWITCH_VIEW.getSubTexture(0.0D, 0.0D, 1.0D, 0.5D),
                            GuiTextures.BUTTON_SWITCH_VIEW.getSubTexture(0.0D, 0.5D, 1.0D, 0.5D),
                            () -> getSphereStyle() == PrimordialSphereStyle.NEUTRON_STAR,
                            (clickData, pressed) -> setSphereStyle(Boolean.TRUE.equals(pressed)
                                    ? PrimordialSphereStyle.NEUTRON_STAR
                                    : PrimordialSphereStyle.UNIVERSE))
                    .setTooltipsSupplier(PrimordialOmegaEngineMachine::sphereStyleTooltips));
        } else {
            // ③ 🔴 2026-10-25 第二十一改：归墟视界枢核的这 3 个渲染按钮【合并成一个】了
            //    （用户原话：「把主机那 3 个调整渲染的按钮合并成一个吧，然后再新增一个功能开关…」）。
            //    ⇒ 位置 ③ / ⑤ / ⑥ 这三格在本机上一个都不挂，改由位置 ⑥ 那一个「渲染参数」面板承载
            //    6 + 1 项（角速度×3 / 洞径 / 引力透镜 / 特效渲染 / AR 遮罩）。
            //    ⚠️ 为什么不在这里挂、再由别处"删掉另外两个"：`ConfiguratorPanel` 没有移除单个
            //       configurator 的 API（`clear()` 会把所有 tab 一起清掉，见 canConfigureBatchProcessing
            //       那段的 javadoc ②）⇒ 合并只能在【挂载点】上做。
        }

        // ─────────────────────────── N6：配方最短耗时（侧栏） ───────────────────────────
        // 直接复用 gtladditions 的原版组件（不自己写 widget）：
        //   com.gtladd.gtladditions.api.machine.gui.LimitedDurationConfigurator
        //   —— implements IFancyConfigurator，构造参数就是要 IGTLAddMultiRecipeMachine（本类已实现）。
        // 它的 getTitle() 用的是 {@code ComponentExtensions.getToComponent("gtceu.machine.limitduration_configurator")}，
        // 而那个 key 的语言条目由 gtladditions 自己带（实测位于它的 jar 内
        // assets/gtceu/lang/zh_cn.json：「配方最短耗时」；en_us.json：「Limit Duration」），
        // 所以这里不需要本工程补 lang。
        // 它的 createConfigurator() 是 new IntInputWidget(getter, setter).setMin(10).setMax(200)，
        // getter/setter 分别打到 getLimitedDuration()/setLimitedDuration(int)。
        panel.attachConfigurators(new LimitedDurationConfigurator(this));

        // ─────────────────── 中子星渲染面板（侧栏【最后一个】= 整条最下面） ───────────────────
        // 🔴 位置由 attach 顺序决定：`ConfiguratorPanel$Tab` 的 y = index * (tabSize + 2)，
        //    而面板整体底对齐（`FancyMachineUIWidget.setupFancyUI` 把它的 y 设成"GUI 高度 − 面板高度 − 4"）
        //    ⇒ 最后挂的这一个正好落在【左下角最下面】，即用户指定的位置。
        // 🔴 按钮的外观、尺寸（24×24）、图标区（16×16，落在 (+4,+4)）、间距（2px）全部由 GTCEu 画，
        //    我们只需要给一个 getIcon() —— 见 `StarRenderConfigurator`。
        // 🔴 2026-10-25 第十九改：类名与挂载点都没动，但**内容分形态** ——
        //    归墟视界枢核 ⇒ 标题「角速度」+ 3 个 IntInputWidget（内环/上舰/下舰）；
        //    本类本体（老主机）⇒ 原来的「中子星渲染」面板，一字未改。
        //    分形态的判断在 `StarRenderConfigurator` 的构造器里（`machine instanceof GuixuVisionCoreMachine`）
        //    ⇒ 这里既不分支也不多传参数，挂载顺序/数量/位置逐位不变。
        // 🔴 2026-10-25 第二十一改：归墟视界枢核<b>不再挂这一格</b>（它的三个角速度已经被搬进
        //    位置 ⑥ 那个合并后的「渲染参数」面板）；老主机仍然挂原来的「中子星渲染」面板，一字未改。
        if (guixuVisionCore == null) {
            panel.attachConfigurators(new StarRenderConfigurator(this));
        }

        // ────────── 「特效渲染」总开关（原「始终渲染为工作状态」的位置 ⑥）──────────
        // 🔴 形态照抄上面 ③ 球体风格开关（同一台机器上已有的先例），【不是】新机制：
        //    IFancyConfiguratorButton.Toggle 是"点一下直接改状态"的官方按钮形态 ——
        //    它的 getTitle()/createConfigurator() 两个 default 都直接抛 NotImplementedException，
        //    且 ConfiguratorPanel$Tab 的构造器里 `instanceof IFancyConfiguratorButton` 那一支
        //    把 view 置 null 并直接 addWidget(button)（javap 逐指令：偏移 67→88 跳过整段建面板逻辑）
        //    ⇒ 它【结构上不存在面板】，点一下就是一次 onClick 回调。
        // 🔴 位置：attach 顺序 = 自上而下（Tab 的 y = index*(tabSize+2)），整条【底对齐】
        //    （FancyMachineUIWidget.setupFancyUI 把它设成 guiHeight-panelHeight-4）
        //    ⇒ 位置 ⑥ 就在这一格（最后挂的是并行数）。
        // 🔴 图标：GuiTextures.BUTTON_WORKING_ENABLE（gtceu:textures/gui/widget/button_working_enable.png，
        //    20x40 = 上下两帧 20x20）的【上半帧】= 一枚干净的齿轮 = "在工作"。
        //    ⚠️ 【刻意不用】它真实存在的下半帧（同一枚齿轮 + 一个大红叉）：那帧语义是"工作被禁用"，
        //       把它当"未按下"画出来会被读成"这台机器坏了"。改用与本文件 ICON_DISABLED 同款的
        //       【色乘子】区分两态（暗 = 未开，原色 = 已开），与 ① 工作开关"灰=关 / 蓝=开"的惯例一致。
        //    ⚠️ 贴图对象必须在【本方法内】新建，不能放进 static 字段：GuiTextures 是客户端侧常量类，
        //       本类会在专用服务端加载 —— 静态初始化器里碰它等于让服务端去加载客户端类。
        //       （本方法与 GuiTextures.BUTTON_SWITCH_VIEW 那行同理，只在客户端 GUI 打开时被调用。）
        // ⚠️ getSubTexture 返回的是 new ResourceTexture（javap：字节码 new #2）
        //    ⇒ 对返回对象 setColor 不会污染 GuiTextures 里那份共享常量。
        //
        // 🔴 2026-10-25 第十九改的语义变化（用户原话：「然后始终渲染为工作状态的按钮你也用起来，
        //    直接做成开关特效渲染」）：
        //    · 归墟视界枢核 ⇒ 绑**新字段** hostFxEnabled（只管特效）；基类的 starAlwaysWorking
        //      **保留原义、但不再有 UI**（它还连着 isActive() ⇒ 机壳/贴图的工作态外观）
        //      ⇒ 顺带的可见变化：机壳外观回到"按真实工作态显示"，已写进交付报告。
        //    · 老主机 ⇒ 仍然绑 starAlwaysWorking（一字未改）。
        if (guixuVisionCore == null) {
            IGuiTexture alwaysWorkingOn =
                    GuiTextures.BUTTON_WORKING_ENABLE.getSubTexture(0.0D, 0.0D, 1.0D, 0.5D);
            IGuiTexture alwaysWorkingOff =
                    GuiTextures.BUTTON_WORKING_ENABLE.getSubTexture(0.0D, 0.0D, 1.0D, 0.5D)
                            .setColor(0xFF5A5A5A);
            panel.attachConfigurators(new IFancyConfiguratorButton.Toggle(
                            alwaysWorkingOff,
                            alwaysWorkingOn,
                            this::isStarAlwaysWorking,
                            (clickData, pressed) ->
                                    setStarAlwaysWorking(Boolean.TRUE.equals(pressed)))
                    .setTooltipsSupplier(PrimordialOmegaEngineMachine::starAlwaysWorkingTooltips));
        } else {
            // ⑥ 🔴 2026-10-25 第二十一改：这里现在是【唯一】的渲染面板 —— 原来 ③⑤⑥ 三个按钮
            //    合并成这一个「渲染参数」面板（6 项旧值 + 1 项新增的 AR 遮罩开关）。
            panel.attachConfigurators(HostFxConfigurators.renderPanel(guixuVisionCore));
        }

        // ─────────────────── 🔴 并行数（2026-09-27 新增 · 侧栏【最后一个】= 左下角最下面） ───────────────────
        // 用户原话：「在模块和主机的左下角再新增一个全新的按钮，他可以调节主机或者模块的并行数，
        //            作为一个输入框，可以让玩家输入数字，并且右边有一个一键调至最大的按钮」。
        // 位置纪律与上面那条完全同源：attach 顺序 = 自上而下（Tab 的 y = index*(tabSize+2)），
        // 整条底对齐（FancyMachineUIWidget.setupFancyUI：guiHeight - panelHeight - 4）
        // ⇒ 挂在【末尾】的这一个落在整条最下面 = 用户指定的「左下角」。
        // ⚠️ 必须挂在【最后】：本方法前面挂着球体风格 / 配方最短耗时 / 中子星渲染 / 始终工作 四个，
        //    任何插在中间的写法都会把这条挤走。
        panel.attachConfigurators(new ParallelOverrideConfigurator(this));
    }

    /**
     * 开关的 tooltip。
     *
     * <p>用<b>字面量</b>而不是 {@code Component.translatable}：与本类 {@code addDisplayText} 同一条约定
     * —— lang 文件归 java-assets 管，缺条目时 translatable 会把裸 key 直接画在 GUI 上。
     * （这里刻意偏离上游的 {@code gt_shanhai.gui.*} 键，理由是这条项目内既有约定；
     * 若将来要国际化，把这三处换成 translatable 并补 lang 即可。）
     */
    private static List<Component> sphereStyleTooltips(boolean pressed) {
        List<Component> tooltips = new ArrayList<>(3);
        tooltips.add(Component.literal("中心球体渲染风格：").withStyle(ChatFormatting.YELLOW)
                .append(Component.literal(pressed ? "中子星" : "鸿蒙微型宇宙")
                        .withStyle(pressed ? ChatFormatting.AQUA : ChatFormatting.LIGHT_PURPLE)));
        // 🔴 2026-09-22 订正（原文：「点击切换；只影响你自己的画面」）：
        //    那句是错的 —— 本开关写的是 @Persisted @DescSynced 的 sphereStyle + notifyBlockUpdate()，
        //    即【机器状态、所有人都会看到】；真正"只影响我自己"的只有配置文件项
        //    （shanhai-common.toml: primordial_omega_engine.sphereStyle）。
        //    按本项目「活的界面上不许假数据」的纪律，这里必须说真话，否则它就是个骗人的 tooltip。
        tooltips.add(Component.literal("点击切换；").withStyle(ChatFormatting.GRAY)
                .append(Component.literal("所有人都会看到").withStyle(ChatFormatting.GOLD))
                .append(Component.literal("（这是机器状态，会随存档保存）").withStyle(ChatFormatting.GRAY)));
        tooltips.add(Component.literal("只想改自己的画面 ⇒ 用配置文件 shanhai-common.toml 的那一项")
                .withStyle(ChatFormatting.DARK_GRAY));
        if (isSphereStyleOverridden()) {
            tooltips.add(Component.literal("⚠ 已被配置文件强制覆盖（shanhai-common.toml: sphereStyle）")
                    .withStyle(ChatFormatting.GOLD));
            tooltips.add(Component.literal("此时本按钮不生效；把它改回 FOLLOW_MACHINE 才会跟随本开关")
                    .withStyle(ChatFormatting.GOLD));
        }
        return tooltips;
    }

    /**
     * 配置是否强制覆盖了风格。
     *
     * <p>🔴 这个提示<b>不可省</b>：覆盖生效时本按钮点了没反应，不说清楚它就是个假开关
     * （上游同类 javadoc 原话：「否则它在覆盖者眼里就是一颗按了没反应的假开关」）。
     */
    private static boolean isSphereStyleOverridden() {
        return ShanhaiConfig.isLoaded()
                && ShanhaiConfig.COMMON.primordialSphereStyle.get()
                        != ShanhaiConfig.ConfigValues.SphereStyleOverride.FOLLOW_MACHINE;
    }
}
