package com.shanhai.machine.module;

import com.google.common.collect.Table;
import com.google.common.primitives.Ints;
import com.gregtechceu.gtceu.api.GTValues;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.capability.recipe.IRecipeHandler;
import com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.gui.fancy.ConfiguratorPanel;
import com.gregtechceu.gtceu.api.gui.fancy.FancyMachineUIWidget;
import com.gregtechceu.gtceu.api.gui.fancy.IFancyUIProvider;
import com.gregtechceu.gtceu.api.gui.fancy.TabsWidget;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.TickableSubscription;
import com.gregtechceu.gtceu.api.machine.feature.IMachineLife;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableElectricMultiblockMachine;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableItemStackHandler;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.RecipeHelper;
import com.gregtechceu.gtceu.utils.GTUtil;
import com.gtladd.gtladditions.api.machine.IThreadModifierMachine;
import com.gtladd.gtladditions.api.machine.IWirelessElectricMultiblockMachine;
import com.gtladd.gtladditions.common.machine.multiblock.part.WirelessEnergyNetworkTerminalPartMachineBase;
import com.hepdd.gtmthings.api.misc.WirelessEnergyManager;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.texture.ItemStackTexture;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.SlotWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;
import com.shanhai.ShanhaiMod;
import com.shanhai.common.compat.GtlAddCompat;
import com.shanhai.common.heat.ShanhaiHeatGate;
import com.shanhai.common.heat.ShanhaiHeatSources;
import com.shanhai.common.log.ShanhaiLogThrottle;
import com.shanhai.common.machine.ParallelOverrideConfigurator;
import com.shanhai.common.machine.ParallelOverrideMachine;
import com.shanhai.common.machine.ParallelPowerBudget;
import com.shanhai.common.machine.PrimordialOmegaEngineMachine;
import com.shanhai.common.recipe.PrimordialRecipeEffects;
import com.shanhai.common.thread.ShanhaiBatchPlan;
import com.shanhai.common.thread.ShanhaiConcurrencyTables;
import com.shanhai.common.thread.ShanhaiDurationFloor;
import com.shanhai.common.thread.ShanhaiFairAllocation;
import com.shanhai.common.thread.ShanhaiParallelBudget;
import com.shanhai.common.text.ShanhaiTextParser;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

import org.gtlcore.gtlcore.api.machine.multiblock.IModularMachineModule;
import org.gtlcore.gtlcore.api.machine.multiblock.ParallelMachine;
import org.gtlcore.gtlcore.api.machine.trait.IBatchMachine;
import org.gtlcore.gtlcore.api.recipe.IGTRecipe;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 原初模块框架层 · 抽象基类。所有原初模块（本阶段 1 个，未来 24 个）都继承它。
 *
 * <h2>1. 接口：只用 gtlcore 的公开 API，不自造协议</h2>
 * {@code implements IModularMachineModule<PrimordialOmegaEngineMachine, PrimordialModuleMachine>}。
 * 回连协议 {@code findAndConnectToHost() / connectToHost() / removeFromHost() / isValidHost()} 全是
 * gtlcore 的 <b>default 方法</b>，本类一行都不重写（自造同名接口会让 gtlcore 层的
 * {@code instanceof IModularMachineModule} 判断<b>静默落空</b>）。
 * 本类只实现接口要求的两个抽象方法：
 * <ul>
 *   <li>{@link #getHostType()} —— 必须返回<b>主机机器类</b>本身
 *       （{@code module.getHostType().isInstance(host)} 是 gtlcore 的用法定式）；</li>
 *   <li>{@link #getHostScanPositions()} —— 候选主机位。</li>
 * </ul>
 *
 * <h2>2. 🔴 几何：不自己写公式</h2>
 * 候选主机位来自 {@link GtlAddCompat#candidateHosts}（= gtladditions {@code AntichristPosHelper}，
 * 与主机侧 {@link GtlAddCompat#moduleSlots} 是同一 helper 的互逆函数，构造上必然自洽）。
 * 本类在其 16 个候选之上<b>再并上 8 个</b>（{@code UP/DOWN} 两个竖直朝向 × 4 层），共 24 个；
 * 这不是「多此一举」：复算显示「主机朝上（facing=UP）」时 helper 的 16 个候选<b>一个都命中不了主机</b>，
 * 竖直 8 个是唯一能连上的路径（证据见 {@link #verticalFacingCandidates}）。
 * 模块→主机是模块主动发起的，多出来的候选只会多几次查表，{@code isValidHost} 会按
 * 「类型 + 成型」把它们全部过滤掉。<b>兜底不能替代 helper 的 16 个。</b>
 *
 * <h2>3. 🔴 找不到主机时不得抹掉持久化的 hostPosition</h2>
 * gtlcore 的 {@code removeFromHost()} 实现是 {@code setHostPosition(null); setHost(null); …}，
 * 旧参照实现在模块成形钩子里写 {@code if (!findAndConnectToHost()) removeFromHost(this.host);}，
 * 此刻 {@code host == null}，于是<b>每次成形都把落盘坐标抹掉</b>，退化成全量扫描。
 * 本类改用 {@link #disconnectFromHost()}：只断引用、<b>保留坐标</b>，把「一次机会」变成「可重试」。
 * 保留是安全的：stale 坐标若不再是成型主机，{@code isValidHost} 返回 false，随即走候选位扫描。
 *
 * <h2>4. 双路回连（规格 §3.3 MUST）</h2>
 * <ol>
 *   <li>主动：{@code onStructureFormed()} 里 {@code findAndConnectToHost()}；失败则每 20 tick 重试；</li>
 *   <li>兜底：配方工作判定路径（{@link #beforeWorking(GTRecipe)}）里若主机不在线，再试一次（同样限频）。</li>
 * </ol>
 * 两者都不依赖「谁先加载」，主机先成型 / 模块先成型 / 跨区块加载顺序不同都会收敛到连通。
 */
public abstract class PrimordialModuleMachine extends WorkableElectricMultiblockMachine
        implements IModularMachineModule<PrimordialOmegaEngineMachine, PrimordialModuleMachine>, IMachineLife,
                   IWirelessElectricMultiblockMachine, IThreadModifierMachine, ParallelMachine,
                   ParallelOverrideMachine {

    // ───────────────────────── 几何常量（与 AntichristPosHelper 同源，仅用于兜底候选） ─────────────────────────
    /** 层 0 的基准距离（A 级：AntichristPosHelper 编译期常量）。 */
    public static final int BASE_DEPTH = 13;
    /** 每层增量。 */
    public static final int LAYER_DEPTH = 12;
    /** 侧向偏移。 */
    public static final int SIDE_OFFSET = 14;
    /** 层数（4 层 × 4 侧 = 16 槽位）。 */
    public static final int LAYER_COUNT = 4;

    /** 模块 → 主机的重连周期（tick）。旧模块基类同值。 */
    public static final int RECONNECT_INTERVAL = 20;

    // ───────────────────────── 跨配方线程（2026-09-25 任务 A） ─────────────────────────

    /**
     * <b>跨配方并行（线程）的【基础值】= 1</b>（空槽 / 线程槽里不是世线残片时的最终值）。
     *
     * <h2>用户 2026-09-25 原话（逐字）</h2>
     * <blockquote>「现在模块是没有跨配方线程，后续我会加，你现在可以写跨配方线程数为 1」</blockquote>
     * <p>⇒ 查证结论与用户口径一致：本工程 24 台模块<b>当时</b>没有任何"跨配方线程 / multi-recipe-parallel"
     * 的字段或逻辑（取证见 {@link #getCrossRecipeThreads()} 的注释）；用户要求<b>先把显示做出来</b>，
     * 值写 1，等他以后加功能。
     *
     * <h2>🔴 为什么做成一个常量 + 一个 getter，而不是在 24 处各写一遍</h2>
     * 将来他真的加功能时，<b>只改这一处</b>（把常量换成真实计算）。
     * 24 台模块全部继承本类 ⇒ 改一处，显示与将来的取值一起跟上。
     * <b>这条纪律已经兑现</b>：2026-09-28 加世线残片时，改动确实只落在
     * {@link #getCrossRecipeThreads()} 一个方法里（见那里的留档）。
     *
     * <h2>⛔ 2026-09-28：下面那句"不要把它接到配方逻辑上"<u>已作废</u></h2>
     * <pre>
     * ⛔ 旧原文（作废，逐字留档）：
     *    ⚠️ 不要把它接到配方逻辑上：本轮明确"别把它做成真的功能"，
     *    它只出现在 tooltip / GUI / Jade 三处显示，不参与任何并行或时长运算。
     * </pre>
     * <b>作废原因</b>：用户 2026-09-28 点单「我们要给模块添加跨配方并行（线程）了」⇒
     * 本轮<b>就是要</b>把它接上。现在它是<b>基础值</b>，真实值 =
     * {@code 本常量 + 线程槽提供的额外值}，见 {@link #getCrossRecipeThreads()}。
     * <p>⚠️ <b>别名</b>：{@link ShanhaiConcurrencyTables#BASE_CROSS_RECIPE_THREADS} 是同一个数
     * （那个类要给 KubeJS 读，不能引用本类）。两者都是 {@code 1}，由
     * {@link #assertParallelTablesConsistent()} 的加载期自检盯着。
     */
    public static final int CROSS_RECIPE_THREADS = 1;

    // ═══════════════════════════════════════════════════════════════════════════════════════
    //  并行槽（2026-09-25 任务 A · 路线 ①：从「物质重组核心」上移到本基类）
    // ═══════════════════════════════════════════════════════════════════════════════════════

    /**
     * <b>空槽 / 未识别物质模块时的基础并行 = 64。</b>
     *
     * <h2>出处（上游逐行）</h2>
     * 上游 {@code gt_shanhai} 在 <b>20 个模块类里各写一份同样的字面量</b>：
     * <pre>
     * private static final long DEFAULT_PARALLEL = 64L;
     * </pre>
     * 另有 10 台经 {@code PrimordialParallelProcessingModuleBase:27} 继承同一数值
     * ⇒ 上游共 <b>30 台同值</b>。用它的那一行是
     * {@code long base = ITEM_PARALLEL_MAP.getOrDefault(stack.getItem(), DEFAULT_PARALLEL);}
     * ⇒ <b>空槽、或放了表里没有的物品，都落 64</b>。
     * <p>官方 guide 原话（本工程 javadoc 里早就记着）：「一台机有一个<b>独立的并行物质槽</b>」
     * 「<b>空槽默认并行是 64</b>，可以先顶着开工」。
     *
     * <h2>🔴 2026-09-25 修的是什么（历史留档，不许再犯）</h2>
     * 在此之前，{@code 64} 这个数<b>只存在于 {@code PrimordialMatterRecombinatorCore} 一个类里</b>，
     * 另外 23 台<b>从来没有拿到过它</b>——它们被 {@code ModuleRegistry} 的
     * {@code usesParallelModifier} 第 7 参标成 {@code false}，配方修饰链整段跳过并行
     * ⇒ <b>GT 侧并行恒为 1</b>。用户在游戏里看到「最大并行数: 1」并当场指出「它本身就有 64」。
     * <p><b>当时的病根定位（逐行）</b>：{@code ModuleRegistry.java:902}（
     * {@code .recipeModifier(usesParallelModifier ? With : Without)} 选链）+
     * {@code ModuleRegistry.java:1019}（{@code if (withParallel && module instanceof
     * PrimordialMatterRecombinatorCore core)} 这句 instanceof 守卫）。
     * 现在 24 台全部继承本基类的并行槽，那条守卫已删除。
     */
    public static final long DEFAULT_PARALLEL = 64L;

    /** 并行槽重扫周期（tick）。上游同值：{@code getOffsetTimer() % 3 == 0}。 */
    public static final int PARALLEL_SCAN_INTERVAL = 3;

    /**
     * 本模块用哪张「物质模块 → 并行」值表。
     *
     * <p>🔴 <b>2026-09-25 逐台上游核对后的订正（比上一轮报告更准）</b>：
     * 上游源码里看起来有"三张表"，但<b>按【值】分只有两张</b>——
     * {@code PrimordialMatterCaster} 那张与 {@code STANDARD} <b>17 个键值对完全相同</b>，
     * 只是 HashMap 插入顺序不同、且末尾有 3 条重复 {@code put}（HashMap 里是空操作）。
     * ⇒ 本枚举里，{@link #STANDARD} 被 21 台共用（见下方 2026-09-26 的删档记录）。
     *
     * <h2>⛔ 2026-09-26：删掉第三档 {@code BASE}（"没有任何分档表"）—— 原文留档</h2>
     * <b>旧原文（已删，逐字留档）</b>：
     * <pre>
     *   只有基础值 DEFAULT_PARALLEL，没有分档表（全 24 台里只有那台发电模块）。
     *   BASE,
     * </pre>
     * <b>旧档位的来源</b>是逐台上游核对：上游 23/24 台都有「空槽 64 ＋ 一张 17 项表」，
     * 唯一例外是那台<b>发电模块</b>——上游 {@code PrimordialOmegaVoidInductionArmature:305-317}
     * 按<b>编程电路</b>算并行。当时为了"照抄上游"就给它单开了一档"没有表"。
     *
     * <p>🔴 <b>用户 2026-09-26 原话（逐字）</b>：
     * <blockquote>「老板的山海那个零点能反应堆是根据电路决定并行的，我们不这样做，你把它设计和其他模块一样，
     * 基础并行是64，根据物质模块提升并行」</blockquote>
     * ⇒ 我们<b>不照抄上游那一条</b>：发电那台与其余模块<b>同口径</b>（空槽 64 ＋ 按物质模块表提升），
     * 它在 {@code ModuleRegistry.SPECS} 里的第 7 参已从 {@code BASE} 改成 {@link #STANDARD}，
     * 归属变成 <b>ENHANCED 3 / STANDARD 21 / 无表档 0</b>。
     *
     * <p><b>为什么删成员、而不是留一个空档位</b>：留档位 = 留一条"这台机器可以不接表"的路，
     * 而这条路没有任何正当用途（24 台全都有物质模块槽）。删掉之后<b>任何"无表"的写法都变成编译错误</b>；
     * 加载期另有 {@code ModuleRegistry#init()} 的 fail-fast 兜底（见
     * {@link #assertParallelTableUsable(String, ParallelTable)}）。
     */
    public enum ParallelTable {
        /**
         * <b>表#2/#3</b>：上游基类 {@code PrimordialParallelProcessingModuleBase} + 15 个直接声明的模块类，
         * 覆盖我们 <b>21 台</b>（含用户截图那台「原初世线蚀刻核心」，以及 2026-09-26 并入的发电那台）。
         */
        STANDARD,
        /**
         * <b>表#1</b>：上游只有 3 个模块类用（物质重组核心 / 奇点反演核心 / 因果编织矩阵），
         * 数值比 {@link #STANDARD} 高一档（入门 256 起）。
         */
        ENHANCED
    }

    /**
     * <b>表#2/#3</b>（上游 20 台的共用值）。
     *
     * <p>上游原表用拼音 id（{@code wzrm} / {@code wzjc} / …），这里的英文 id 取自本工程
     * {@code ShanhaiItems} 类注释里那份<b>官方改名对照表</b>（{@code _原始私货解压} 里已无 lang 可查，
     * 那份对照表是仓内唯一的权威来源）。
     * <p>🔴 <b>正面对照</b>：把同一份对照表套到 {@link #PARALLEL_TABLE_ENHANCED}（表#1）上，
     * 与本工程 {@code PrimordialMatterRecombinatorCore} 里那张<b>老表 17/17 逐项吻合</b>
     * ⇒ 对照表本身是对的，这套映射不是猜的。
     */
    private static final Map<String, Long> PARALLEL_TABLE_STANDARD = ShanhaiConcurrencyTables.standardParallelTable();

    /**
     * <b>表#1</b>（上游 3 台）。数值 = 本工程 {@code PrimordialMatterRecombinatorCore} 原表，逐字搬运。
     *
     * <p>🔴 <b>2026-09-28：两张表的数据实体搬到 {@link ShanhaiConcurrencyTables}</b>
     * —— 因为 KubeJS 的物品描述也要用同一批数字（"各等级物质模块提供的并行数"），
     * 而 KubeJS 只能 {@code Java.loadClass} 一个<b>不依赖 Minecraft 的</b>类。
     * 这里<b>只留两个引用</b>，数据仍然只有一份（本工程今天刚因"两份真源"吃过亏）。
     * 表的内容<b>一个字符都没改</b>，且有 {@link #assertParallelTablesConsistent()} 在加载期逐项核对。
     */
    private static final Map<String, Long> PARALLEL_TABLE_ENHANCED = ShanhaiConcurrencyTables.enhancedParallelTable();

    // ⛔ 2026-09-28：这里原本有一段 `static { … 34 行 put … }`，数据已整体搬到
    //    ShanhaiConcurrencyTables（见上面两个字段的注释）。块本身删掉而不是留空，
    //    以免将来有人在空块里又补一份 put（那就是第二份真源）。

    /** 当前并行上限（成形即扫一次，之后每 {@link #PARALLEL_SCAN_INTERVAL} tick 重扫）。 */
    private long currentParallel = DEFAULT_PARALLEL;
    /** 并行槽重扫订阅。 */
    @Nullable
    private TickableSubscription matterSlotScanSubs;

    // ═════════════════════════ 玩家可调的并行数（2026-09-27 新增） ═════════════════════════

    /**
     * 🔴 <b>玩家覆盖的并行数上限；{@code 0} = 未覆盖（跟随物质模块表）。</b>
     *
     * <h2>用户原话（逐字）</h2>
     * <blockquote>「在模块和主机的左下角再新增一个全新的按钮，他可以调节主机或者模块的并行数，
     * 作为一个输入框，可以让玩家输入数字，并且右边有一个一键调至最大的按钮」</blockquote>
     *
     * <h2>为什么是一个"读取点覆盖"而不是"写进 {@link #currentParallel}"</h2>
     * {@code currentParallel} 的唯一运行期写者是 {@code scanMatterSlot()}，<b>每 3 tick 无条件覆写一次</b>
     * （{@link #PARALLEL_SCAN_INTERVAL}；成形时先扫一次，见 {@code startMatterSlotScan()}）。
     * 若把玩家的值写进那个字段，下一次重扫就会把它冲掉（表现："输入框里的数过 3 tick 自己变回去了"）。
     * ⇒ 覆盖值单独存，并在<b>读取点</b>优先 —— 自动值照旧每 3 tick 更新（玩家一清覆盖就立刻跟上）。
     *
     * <h2>{@code @Persisted} / {@code @DescSynced} 为什么两个都要（本工程既有纪律）</h2>
     * <ul>
     *   <li>{@code @Persisted}：拆装 / 存档重载后保持（任务书硬要求「存档持久化」）；</li>
     *   <li>{@code @DescSynced}：<b>没有它，客户端永远读到 0</b> —— 输入框的值来自
     *       {@code TextFieldWidget} 的 {@code textSupplier}，那份 supplier 在两侧都会跑，
     *       客户端读到的是本地镜像。这正是本工程红线「活的界面上不许放假数据」的既有判定
     *       （同款理由见 {@code PrimordialOmegaEngineMachine#limitedDuration} 的 javadoc）。</li>
     *   <li>两个注解都只对<b>写在本类里</b>的字段生效：本类的 {@code MANAGED_FIELD_HOLDER} 是
     *       {@code new ManagedFieldHolder(PrimordialModuleMachine.class, …)}，按【类】反射登记字段
     *       ⇒ 字段写在这里就自动进 holder，不需要动 holder 那一行。</li>
     * </ul>
     */
    @Persisted
    @DescSynced
    private long parallelOverride = ParallelOverrideMachine.PARALLEL_AUTO;

    @Override
    public long getParallelOverride() {
        return parallelOverride;
    }

    /**
     * 写入覆盖值。<b>先钳位 → 没变就直接返回 → 真变了才 {@code notifyBlockUpdate()}</b>
     * —— 与 {@code PrimordialOmegaEngineMachine#setLimitedDuration(int)} 逐字同形
     * （那是本工程"玩家改一个数、它被校验并同步"的既有规范写法）。
     *
     * <p>为什么必须在这里再钳一次：输入框那两道 {@code setNumbersOnly} 校验器（客户端 + 服务端）
     * 挡得住手打与正常回放，但<b>网络包不保证只带合法值</b>（伪造的 client action）。
     *
     * <p>🔴 <b>2026-09-27 钳位口径改正（用户实机提出）</b>：
     * <pre>
     *   ⛔ 上一版：clampOverride(value) ⇒ 上钳到 Long.MAX_VALUE（等于不钳）
     *   ✅ 现行  ：clampOverrideToCeiling(value) ⇒ 上钳到【本模块当前能达到的并行数】
     *             （= 设 0 时机器能达到的那个并行数；默认 64，随物质模块每 3 tick 变）
     * </pre>
     * 用户原话（逐字）：「也不允许玩家输入超出机器可以达到最大并行数的数字」。
     * <p>超限时是<b>钳到天花板</b>而不是拒绝：服务端钳完会经 {@code @DescSynced} 回灌，
     * 输入框里显示的就是钳过的权威值（见 {@code ParallelOverrideConfigurator} 的类注释 ④）。
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
     * 🔴 <b>本模块当前能达到的并行数 —— 「一键最大」填的就是它（2026-09-27 语义改正）。</b>
     *
     * <pre>
     *   ⛔ 上一版：返回 ParallelOverrideMachine.PARALLEL_MAX（= Long.MAX_VALUE）
     *             —— 一键最大会把 9223372036854775807 塞进输入框，且实际跑多少仍受输入量限制，
     *                那个数对玩家是【不可验证】的（本工程红线：活的界面上不许放假数据）
     *   ✅ 现行  ：返回 {@link #getAutoParallel()} = {@code currentParallel}
     *             —— 与用户口径逐字对齐：「一键最大是到机器可以达到的并行数
     *                （也就是设置 0 时机器的并行数）」
     * </pre>
     * <p>默认值 = {@link #DEFAULT_PARALLEL}（64，即"不加任何物质模块它本身就有 64 的并行数"），
     * 随物质模块每 {@link #PARALLEL_SCAN_INTERVAL} tick 重算 ⇒ <b>一键最大填的是"此刻"能跑的数</b>。
     * <p>{@code max(1, …)} 是服务端兜底：{@code currentParallel} 被落盘数据改坏成 0 时，
     * 天花板退化为"只能自动"，而不是让玩家写进一个 0 造成"覆盖值 = 0 = 自动"的歧义。
     */
    @Override
    public long getParallelOverrideCeiling() {
        return Math.max(1L, currentParallel);
    }

    @Override
    public long getAutoParallel() {
        return currentParallel;
    }

    // ⛔⛔ 【2026-10-02 第二轮：本覆写已删除，原文逐字留档】getEffectiveParallel()
    //   ⛔ 旧原文（作废）：
    //       /**
    //        * 覆盖生效之后的并行。
    //        *
    //        * <p>⚠️ 与 {@link #getCurrentParallel()} 必须同源同值 —— 后者才是引擎读的那个数（见该方法）。
    //        *
    //        * <p>🔴 2026-09-27：多了一道 {@code min(…, 天花板)}。
    //        * 写入时已经钳过（{@link #setParallelOverride}），这里再钳一次是为了兜住
    //        * <b>"天花板事后变小"</b> 这一档：{@code currentParallel} 每 3 tick 跟着物质模块重算，
    //        * 玩家拆掉物质模块后自动值会掉下来，而覆盖值<b>是存下来的数、不会自己跟着变</b>
    //        * ⇒ 没有这一句就会出现「输入框写着 4096，机器能达到的只有 64」这种只在本工程红线里
    //        * 被点名的假数据。钳在这里 ⇒ 显示与生效同时收敛到真实可达值。
    //        */
    //       @Override
    //       public long getEffectiveParallel() {
    //           final long auto = Math.max(1L, currentParallel);
    //           if (parallelOverride > ParallelOverrideMachine.PARALLEL_AUTO) {
    //               return applyEnergyCap(Math.min(parallelOverride, auto));
    //           }
    //           return applyEnergyCap(auto);
    //       }
    //   ⛔ 作废原因：本方法与主机侧 {@code PrimordialOmegaEngineMachine#getEffectiveParallel()} 是
    //      **逐字相同的同一段逻辑**（{@code auto} 两边都由 {@link #getAutoParallel()} 提供，
    //      本类里它返回的就是 {@code currentParallel}）⇒ 两份实现在本工程是最忌讳的漂移源。
    //      🔴 现由 {@link ParallelOverrideMachine#getEffectiveParallel()}（接口 default，全工程唯一一份）
    //      承担，本类不再覆写。<b>数值逐位不变</b>（判据 A/C 段逐档对过账）。
    //      ⚠️ 「天花板事后变小」这条不变式逐字保留在接口那份里（{@code min(覆盖值, auto)}）。
    //      ⚠️ 同一轮里 {@code getEffectiveParallel()} 的语义还多了一条用户裁决：
    //      **「电力自动」开着时，输入框里的值完全不参与**（落点
    //      {@link ParallelOverrideMachine#getEffectiveOverride()}）—— 那一句是行为变更，不是重构。

    // ═════════════════════ 🔴 电力自动（2026-10-02 新增 · 用户定方案） ═════════════════════

    /**
     * 🔴 <b>「电力自动」开关。</b>
     *
     * <h2>⛔ 2026-10-02 第二轮改判：默认 {@code false} ⇒ **默认 {@code true}**（用户点名）</h2>
     * 用户原话（逐字）：
     * <blockquote>「还有我的额外要求：<b>放置机器时默认开启这个电力自动并行</b>，
     * 并且开启这个电力自动并行之后自动禁用上面的输入框和一键最大按钮」</blockquote>
     * ⛔ 旧值 {@code false} 的理由（原文留档，别再当成"可以改回去"的依据）：
     * 「默认 false ⇒ 关着 ⇒ 本功能对老存档<b>一个 bit 都不改</b>（用户点名的一条纪律：
     * 不许改「输入 0 = 自动」的旧语义）」。
     * <p>🔴 <b>改成 true 的代价（必须让用户知情）</b>：
     * <ul>
     *   <li><b>本功能之前就存在的机器</b>（存档里没有这个键）⇒ 读档时按字段默认值 <b>true</b> 生效
     *       ⇒ <b>它们一读档就变成"电力自动开着"</b>，并行数会开始被能源仓总功率钳制。
     *       这是本轮唯一一处<b>会改变老存档行为</b>的地方，用户已明确要求，特此留档。</li>
     *   <li><b>本功能之后放置的机器</b> ⇒ 也是 true（= 用户要的「放置时默认开启」）。</li>
     *   <li>⚠️ 已经落过盘的机器（键存在且为 0）⇒ 保持 false，面板上会显示「电力自动 ✘ 关」，
     *       玩家点一下即可打开。这一档<b>没有</b>被静默改动。</li>
     * </ul>
     *
     * <p>{@code @Persisted} 让拆装 / 重载后保持（与 {@link #parallelOverride} 同一对注解、同一条理由）；
     * {@code @DescSynced} 让客户端面板上的开关状态与读数不会说谎。
     */
    @Persisted
    @DescSynced
    private boolean powerAutoParallel = true;

    /**
     * <b>本轮算出来的电力上限</b>（{@code 0} = 没有 / 尚未算过）。
     *
     * <p>🔴 <b>不 {@code @Persisted}</b>：它是"由当前仓 + 当前配方现算出来的"，落盘没有意义，
     * 而且重载后留一个上一次的数正是本工程红线点名的"假数据"形态（拆了仓却还显示旧上限）。
     * 写者只有配方逻辑，每轮配方开始写一次。
     */
    @DescSynced
    private long energyParallel = ParallelOverrideMachine.ENERGY_CAP_NONE;

    /**
     * 🔴 <b>2026-10-02 第二轮新增：这个"没有上限"到底是哪一种"没有"。</b>
     *
     * <p>面板据此把那句没有信息量的「尚未算出」换成一句<b>能读的实话</b>
     * （逐档文案见 {@link ParallelOverrideMachine#energyCapReasonText}）。
     * 与 {@code energyParallel} 一样<b>不 {@code @Persisted}</b>：它是现算值。
     *
     * <p>⚠️ 初值 = {@code NOT_EVALUATED}（"还没跑过一轮配方逻辑"）而不是 {@code COMPUTED} ——
     * 客户端镜像拿到的也是这一档，所以<b>面板永远不会在拿到数之前先编一个状态出来</b>。
     */
    @DescSynced
    private int energyCapState = ParallelOverrideMachine.EnergyCapState.NOT_EVALUATED.ordinal();

    /**
     * 🔴🔴 <b>2026-10-02 第八轮新增：本轮的「每并行耗电 k」（毫 EU/t）—— 面板那一行读的就是它。</b>
     *
     * <p>用户裁决 ② 的原话：<b>「用户能看到 {@code 688.13K}，却看不到 {@code 2.1} ⇒ 所以这次只能靠反推」</b>。
     * 这一条就是把那个一直藏在日志里的乘数搬到界面上。
     *
     * <p>与 {@code energyParallel} / {@code energyCapState} <b>同一条纪律</b>：
     * <b>不 {@code @Persisted}</b>（现算值，落盘只会在重载后留下一个过期的假数字）、
     * {@code @DescSynced} 让客户端面板说的是同一个数、<b>只经 {@code setEnergyCap} 三参版写入</b>
     * （三样一起写 ⇒ 不可能出现"电上限是新的、k 是上一轮的"）。
     * <p>{@code ≤ 0} = 本轮没算出（逐档原因见 {@code energyCapState}）。
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
        // 一改开关就清掉旧上限、旧状态与旧 k：否则面板会继续显示上一轮那个数/那句话
        //（活的界面上不许放假数据）。清成 NOT_EVALUATED = 「开关刚动过，还没重算」。
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
        // 🔴 豁免的那台发电机与 energyParallel 同一处拦法：它压根不参与电力钳制，
        //    「每并行耗电」对它没有意义 ⇒ 恒返回"没算出"，面板那一行印「不适用」。
        //    （理由与 getEnergyCapState() 那段逐字相同：活的界面上不许放假数据。）
        if (isEnergyCapExempt()) {
            return ParallelOverrideMachine.ENERGY_CAP_NONE;
        }
        return perParallelMilliCost;
    }

    /**
     * 🔴🔴 <b>模块侧的跨配方线程数 T —— 供接口层把电上限「÷T」（2026-10-02 第五轮用户裁决 ①）。</b>
     *
     * <pre>
     *   T = {@link #getCrossRecipeThreads()} = 1 + 2^N × 线程槽数量    （空槽 / 非残片 ⇒ 1）
     * </pre>
     *
     * <h2>🔴 为什么这里直接返回机器自己的值，而不是另存一份</h2>
     * 因为<b>引擎当轮真正用的那个 T 就是本方法</b>：
     * {@code PrimordialModuleRecipeLogic#getMultipleThreads()} 覆写后直接
     * {@code return getMachine().getCrossRecipeThreads();} ⇒ 两处<b>同一个来源</b>，
     * 不可能出现"接口层按 T₁ 除、父类按 T₂ 乘"（那会让预算 &gt; 电上限 ⇒ 超功率）。
     *
     * <h2>⚠️ 为什么不用方案乙（机器存一个 T 字段、配方逻辑写进去）</h2>
     * 存字段会出现<b>过期窗口</b>：玩家换线程槽里的世线残片之后、配方逻辑重跑之前，
     * 字段还是旧 T，而父类读的 {@code getMultipleThreads()} 已经是新 T ⇒
     * 预算 = {@code min(本机上限, 电上限÷T_旧) × T_新}，T_新 &gt; T_旧 时<b>超电上限 ⇒ 超功率</b>。
     * 本实现两处同源同刻 ⇒ 预算 {@code ≤ 电上限} 对<b>任意</b> T 都成立。
     */
    @Override
    public int getEnergyCapThreads() {
        return getCrossRecipeThreads();
    }

    /**
     * 🔴🔴 <b>2026-10-02 第六轮（用户裁决 ①「那台发电机 ⇒ 给它单独豁免」）—— 本类里唯一一个实现点。</b>
     *
     * <pre>
     *   return getDefinition() != null &amp;&amp; getDefinition().isGenerator();
     * </pre>
     * 全 24 台原初模块里只有<b>一台</b>为真：那台发电机（「原始真空零点能发生器」）。
     * 判据不是新造的 —— 它与 {@code PrimordialModuleRecipeLogic#shanhai$resolveRouting()}
     * 用的是<b>同一个</b> {@code getDefinition().isGenerator()}：
     * <pre>
     *   shanhai$resolveRouting():  final boolean generator = getMachine().getDefinition().isGenerator();
     *                              if (generator) { setUseMultipleRecipes(false); ... }   ← 退回原生链
     * </pre>
     * ⇒ 「走原生链的那台」与「被豁免的那台」在结构上【是同一次判定】（同一个方法、同一个时刻之外
     * 永不改变的定义标志），不存在"两台各判一遍、迟早分叉"的窗口。
     *
     * <h2>🔴 机制 = 「电上限视为 ∞」（不是"不写电上限"）—— 理由逐条</h2>
     * <ol>
     *   <li><b>为什么不是"不写"</b>：那要靠"上游永远别写进来"来维持 —— 一旦将来任何一条路径
     *       写进一个数（例如这台机器某时刻又走了引擎路径、或有人加了个新写入口），豁免就
     *       <b>静默失效</b>：机器照常运转、只是并行从 259845521287 掉下去，日志里<b>一个字都没有</b>。
     *       本工程吃过"靠一个没人验证的推断维持正确性"的亏（用户本轮原话就是这个意思：
     *       「不管'它电上限是否恒为 0'那个推断对不对」）。</li>
     *   <li><b>"视为 ∞"落在哪</b>：{@link ParallelOverrideMachine#applyEnergyCap(long, int)} 的
     *       第一句 —— 那是全工程<b>唯一</b>一处"把电上限施加到并行上"的算术
     *       （{@code getEffectiveParallel()} 也调它）⇒ 写入口怎么变都绕不过去。</li>
     *   <li><b>"∞"不是修辞</b>：本工程里"不钳"的既有表示就是电上限 ≤
     *       {@link ParallelOverrideMachine#ENERGY_CAP_NONE}（没有上限 / 没算出 / 没能源仓），
     *       所以「电上限 = ∞」与「不参与钳制」是同一件事的两种说法。</li>
     * </ol>
     *
     * <h2>🔴 它保的是什么（2026-09-30 已验收修复）</h2>
     * 用户当时报「零点能反应堆不吃跨配方并行，那个发电量都没加」；修法就是
     * {@code ModuleRegistry} 那行 {@code totalParallelLimitFor(getCurrentParallel(), T)}
     * （= <b>本机上限 × T</b>）⇒ 永恒物质模块表值 2147483647 × 121 线程 = <b>259845521287</b>。
     * <p>⚠️ 现在这条修复是<b>可被打破的</b>：{@code powerAutoParallel} 自第五轮起<b>默认 true</b>，
     * 只要有一个非 0 的电上限落进来，本类的接口层就会给出
     * {@code floor(电上限 ÷ 121)} —— 电上限 48 时算出 0，按既有纪律保底 1
     * ⇒ 原生链拿到 {@code 1 × 121 = 121}（<b>从 259845521287 掉到 121</b>）。
     * 豁免把这条路径整个掐掉。
     *
     * <p>⚠️ 另外 23 台模块与主机（{@code PrimordialOmegaEngineMachine}）都<b>不</b>覆写本方法
     * ⇒ 拿到接口默认的 {@code false} ⇒ 它们的电力钳制行为一个字节都没变。
     */
    @Override
    public boolean isEnergyCapExempt() {
        // getDefinition() 在构造期理论上可能还是 null（本方法若被 getEffectiveParallel() 在
        // 早期调用）⇒ 显式兜底成 false（= 不豁免、维持老行为），绝不在这种时候抛 NPE。
        return getDefinition() != null && getDefinition().isGenerator();
    }

    @Override
    public ParallelOverrideMachine.EnergyCapState getEnergyCapState() {
        // 🔴 2026-10-02 第六轮：豁免的那台发电机【只报 ∞】—— 不报任何数字。
        //    为什么必须在这里拦：本机若真有值被写进 energyParallel（一旦发生）、而面板照旧把它
        //    显示成「电上限 48（按本轮候选配方算出来的）」，那就是一块【活的界面上的假数据】
        //    （那个 48 根本没有参与任何运算，见 isEnergyCapExempt() 的注释）。
        //    ⇒ 面板与工具提示读的都是本方法 ⇒ 一处拦下、全线一致。
        //    ⚠️ 它读的是 machine definition 上的常量标志（客户端/服务端恒同值）⇒ 显示不会分叉。
        if (isEnergyCapExempt()) {
            return ParallelOverrideMachine.EnergyCapState.UNLIMITED_BY_EXEMPTION;
        }
        return ParallelOverrideMachine.EnergyCapState.values()[energyCapState];
    }

    /**
     * 🔴 状态、数值与「每并行耗电 k」的唯一写入口（一次写三样）—— 理由见契约方法自己的注释。
     *
     * <p>三样里<b>任一样</b>变了就要刷包；全没变<b>直接返回</b>（与既有几个 setter 同一条纪律）。
     */
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
     * <b>并行槽自检（加载期 fail-fast + 一条可 grep 的日志）。</b>
     *
     * <h2>为什么必须有这一条</h2>
     * 两张表是用 <b>17 个字符串 id</b> 写的，而 17 个 id 必须与 {@code ShanhaiItems} 的注册 id
     * 【逐字相同】。typo 的后果是<b>静默</b>的：{@code getOrDefault} 会退回基础值 64，
     * 游戏里看不出来，只是"那一个物质模块没生效"。
     * 本方法把这件事变成<b>加载期当场抛异常</b>，并在日志里留下可机器核对的计数行。
     *
     * <p>判据三条（全部对着 {@link #MODULE_LEVELS} 这张本就唯一的「物品 id → 等级」表）：
     * <ol>
     *   <li>两张表的条数都 = {@link #MODULE_LEVELS} 的条数（17）；</li>
     *   <li>两张表的<b>每一个键</b>都在 {@link #MODULE_LEVELS} 里（拼错一个字就炸）；</li>
     *   <li>每个值 &gt; 0（0/负值会让 {@code getOrDefault} 的语义失效）。</li>
     * </ol>
     * 调用点：{@code ModuleRegistry#init()}（机器注册期，早于任何存档加载）。
     */
    public static void assertParallelTablesConsistent() {
        checkParallelTable(PARALLEL_TABLE_STANDARD, "STANDARD（表#2/#3）");
        checkParallelTable(PARALLEL_TABLE_ENHANCED, "ENHANCED（表#1）");
        checkParallelArithmetic();
        // 🔴 2026-10-03 追加：**等级 ↔ 并行曲线**的逐档回归锚 + 5..16 单调不减断言
        //    （用户「物理台阶」重排。判据与理由见 assertLevelCurve 的 javadoc；
        //     先跑正面对照证明检查器有牙齿，再采信它在真实曲线上的"通过"。）
        selfTestLevelCurveChecker();
        assertLevelCurve();
        // 🔴 2026-09-28 追加：世线残片表的加载期自检（正面对照 8 条 + 两张表键集合一致性）。
        //    挂在这里的理由与下面那条相同：本方法已由 ModuleRegistry#init() 在【注册期】调用，
        //    所以它是"加载期必跑 + 日志可 grep"的既有入口，不需要新增任何加载钩子。
        ShanhaiMod.LOGGER.info(ShanhaiConcurrencyTables.selfTest());
        // 🔴 2026-09-29 追加：跨配方"公平分配"纯算术核的加载期自检
        //    （正面对照 6 条 + 负面对照 1 条 —— 负面对照正是【贪心把第二条饿死】那一档，
        //     也就是用户实机报的"一种挤占了另一种"）。
        //    ⚠️ 这一条是"挤占"这条修复在【无头专服里唯一跑得到】的判据：
        //       [SHANHAI-MODULE-ENGINE] / [SHANHAI-PARALLEL-LONG] 都只在"世界上真有一台成型模块在跑"时
        //       才打（上一位实测：专服里 0 条），而本行是纯函数、注册期必跑。
        ShanhaiMod.LOGGER.info(ShanhaiFairAllocation.selfTest());
        // 🔴 2026-09-30 追加：多配方聚合档「批处理」纯算术核的加载期自检
        //    （正面对照 2 条 + 负面对照 5 类 —— 其中最关键的一档是
        //     【某条 origin 的额外料预检不过 ⇒ 真扣调用次数必须是 0】，
        //     也就是本工程血规矩「宁可跳过批处理，也绝不少扣料 / 不猜一个数」的形式化断言）。
        //    与上一条同理由：纯函数、注册期必跑、无头专服里就能跑，日志可 grep。
        ShanhaiMod.LOGGER.info(ShanhaiBatchPlan.selfTest());
        // 🔴 2026-09-30 追加：并行预算纯算术核的加载期自检（恒等 11 档 + 用户实测档 + 饱和 + 负面对照 2 条）。
        //    它是本轮 bug（「零点能反应堆不吃跨配方并行」）在【无头专服里唯一跑得到】的判据：
        //    现象本身只发生在"世界上真有一台成型模块在跑"的时候，而本行是纯函数、注册期必跑。
        ShanhaiMod.LOGGER.info(ShanhaiParallelBudget.selfTest());
        // 🔴 2026-10-02（第七轮）追加：**每并行耗电 k 的定点核**加载期自检。
        //    用户实机报的「电力自动算出来了、但整机耗电 688.13K > 能源仓 655,360 ⇒ 电力输入不足」
        //    真凶 = 旧的 `Math.round(42 × 0.05) = 2`（真值 2.1）⇒ 并行被算大 5%。
        //    这一行把「k 必须带小数参与除法」变成【加载期就会炸】的断言：
        //    正向对照（用户档 655,360 / k=2.1 ⇒ 312,076）＋ 边界紧（+1 份就超）＋
        //    回归锚（k=42 ⇒ 48 / 3 逐位不变）＋ 负面对照（旧 round 口径必须报红）。
        //    与上一条同理由：纯函数、注册期必跑、无头专服里就能跑、日志可 grep。
        ShanhaiMod.LOGGER.info(ParallelPowerBudget.selfTest());
        // 🔴 2026-09-30（同日第二轮）追加：**并行进 long 档 ⇒ 配方时长下限 10 tick** 的纯算术核自检
        //    （恒等 11 档 × 7 个时长 + 边界 2 条 + 正面对照 3 条 + 负面对照 3 条）。
        //    它是用户那句「这个配方加到 long 之后可以加一个最小配方时长为 10tick」在
        //    【无头专服里唯一跑得到】的判据：下限真正生效得等"世界上一台成型模块跑起来且并行进 long 档"，
        //    而本行是纯函数、注册期必跑。
        ShanhaiMod.LOGGER.info(ShanhaiDurationFloor.selfTest());
        // 🔴 2026-09-30（同日第五轮）追加：**配方对象级别**的加载期判据。
        //    用户明确要求「自证里至少一条是"读实际 duration"……别只断言"函数被调用了"」——
        //    上一轮的病正是"函数调了但没生效"（min(10, 1) = 1 ⇒ 目标 == 现值 ⇒ 静默返回）。
        //    上面那条 selfTest() 查的是纯算术核的**返回数**；这一条拿一个**真实 GTRecipe 实例**
        //    喂进生产落点，再从**产出的配方对象上读 duration**。两件事都会坏、坏法不同 ⇒ 都要有。
        ShanhaiMod.LOGGER.info(PrimordialRecipeEffects.selfTestDurationFloorOnRealRecipe());
        // 🔴 跨类一致性（这一条只能在装载了 Minecraft 类的地方断言，所以放在这里而不是纯核里）：
        //    纯核的下限常量必须与主机侧 GUI 允许的【最小下限】同值 —— 两处各自硬编码必然漂移，
        //    而漂移的表现是"抬头写着 10、玩家在主机侧栏能调到 5"，谁也看不出来。
        if (ShanhaiDurationFloor.LONG_SCALE_MIN_DURATION != PrimordialRecipeEffects.MIN_LIMITED_DURATION) {
            throw new IllegalStateException("[SHANHAI-DURATION-FLOOR] 加载期自检失败：long 档时长下限 "
                    + ShanhaiDurationFloor.LONG_SCALE_MIN_DURATION + " 与主机侧最小下限 "
                    + PrimordialRecipeEffects.MIN_LIMITED_DURATION + " 不等 ⇒ 两处已经漂移。");
        }
        // 🔴 2026-09-26 追加：发电模块产出算式的加载期自检（正向对照 + 负面对照 + 两条用户实测数）。
        //    挂在这里的理由：本方法已经由 ModuleRegistry#init() 在【注册期】调用（那行不改），
        //    所以这是"加载期必跑 + 日志可 grep"的既有入口，不需要新增任何加载钩子。
        PrimordialGeneratorProduction.selfTest();
    }

    /**
     * <b>并行算术自检</b>——把用户那句需求<b>写成断言</b>：
     * 「<b>不加任何物质模块它本身就有 64 的并行数</b>」。
     *
     * <p>这组断言全是<b>纯函数</b>（没有机器实例、没有世界），所以能在无头专服加载期真跑一遍，
     * 结果落在日志里可 grep。重点覆盖两条<b>会静默毁机</b>的边界：
     * <ul>
     *   <li><b>饱和</b>：{@code Long.MAX_VALUE × n} 溢出成负数 ⇒ 下游并行 ≤ 0 ⇒ 机器不动不崩、
     *       日志无输出（本项目记录过的最坏失败形态）。</li>
     *   <li><b>钳位</b>：{@code int} 溢出会让 {@code parallelCap()} 变负。</li>
     * </ul>
     */
    private static void checkParallelArithmetic() {
        assertEq(applyStackMultiplier(DEFAULT_PARALLEL, 0), DEFAULT_PARALLEL, "空槽（count=0）应 = 基础并行");
        assertEq(parallelCapFor(applyStackMultiplier(DEFAULT_PARALLEL, 0)), 64, "空槽最终并行应 = 64");
        assertEq(applyStackMultiplier(DEFAULT_PARALLEL, 1), DEFAULT_PARALLEL, "count=1 不翻倍（1+1/16=1）");
        assertEq(applyStackMultiplier(DEFAULT_PARALLEL, 16), 128L, "count=16 应翻一倍");
        assertEq(applyStackMultiplier(DEFAULT_PARALLEL, 32), 192L, "count=32 应翻两倍");
        assertEq(applyStackMultiplier(4611686018427387903L, 64), Long.MAX_VALUE, "超大值 × 应饱和到 MAX 而不是溢出成负数");
        assertEq((long) parallelCapFor(Long.MAX_VALUE), (long) Integer.MAX_VALUE, "MAX 应钳到 int 上限");
        assertEq((long) parallelCapFor(0L), 1L, "0/负值应钳到 1");
        // 🔴 2026-09-26：long 通道的算术 + 逐档对照（本次改动的核心判据）。
        assertAndLogParallelTierTable();
    }

    /**
     * 🔴 <b>逐档对照表（2026-09-26「并行接回 long 通道」的核心判据）—— 加载期必跑，日志可 grep。</b>
     *
     * <h2>为什么做成加载期自检而不是文档里的一张表</h2>
     * 判据是「<b>并行表那 17 档的值逐档原样到达引擎</b>」。一张写死在报告里的表会随代码漂移，
     * 而本方法<b>用的是生产过程本身那两个函数</b>（{@link #recipeLogicMaxParallelFor(long)}
     * 与 {@link #parallelCapFor(long)}）、<b>读的是生产用的那张表</b>
     * （{@link #PARALLEL_TABLE_STANDARD}），并且由 {@code ModuleRegistry#init()} 在
     * <b>无头专服加载期</b>真的跑一遍 ⇒ 日志里那一行就是证据本体，不是转述。
     *
     * <h2>打印四列</h2>
     * <pre>
     *   档位（按值升序 1..17） / 表值 / 改前到引擎的值（int 桥） / 改后到引擎的值（long 通道）
     * </pre>
     * ⚠️ 表是 {@code LinkedHashMap}，插入顺序<b>不是</b>值升序（历史搬运顺序），
     * 所以本方法按值排序后再打印 —— 排序只影响打印顺序，不碰表本身。
     *
     * <h2>断言（三条）</h2>
     * <ol>
     *   <li><b>逐档：{@code recipeLogicMaxParallelFor(表值) == 表值}</b> —— 17/17，这就是核心判据；</li>
     *   <li><b>逐档：{@code totalParallelLimitFor(表值, 1) == 表值}</b> —— 乘上"1 个跨配方线程"后不变；</li>
     *   <li><b>只有 3 档的"改前 ≠ 改后"</b>（值 &gt; 2^31−1 的那三档）——
     *       这是<b>反向对照</b>：改动只影响被压平的那几档，其余 14 档逐值不变。</li>
     * </ol>
     */
    private static void assertAndLogParallelTierTable() {
        // 按值升序（同值按插入顺序稳定）—— 只影响打印顺序。
        final List<Map.Entry<String, Long>> tiers = new ArrayList<>(PARALLEL_TABLE_STANDARD.entrySet());
        tiers.sort(Map.Entry.<String, Long>comparingByValue());

        int changed = 0;
        final StringBuilder rows = new StringBuilder();
        for (int i = 0; i < tiers.size(); i++) {
            final String id = tiers.get(i).getKey();
            final long table = tiers.get(i).getValue();
            final long before = parallelCapFor(table);                       // 旧：int 桥
            final long after = recipeLogicMaxParallelFor(table);             // 新：long 通道
            assertEq(after, table, "逐档判据：「" + id + "」的表值必须原样到达引擎（long 通道）");
            assertEq(totalParallelLimitFor(table, 1), table, "逐档判据：1 线程时并行预算必须 = 表值（" + id + "）");
            if (before != after) {
                changed++;
            }
            rows.append(String.format(java.util.Locale.ROOT,
                    "%n    %2d. %-58s 表值=%20d  改前(int桥)=%11d  改后(long)=%20d%s",
                    i + 1, id, table, before, after, before == after ? "" : "   <= 本次被【救回来】的那一档"));
        }
        assertEq(changed, 3, "反向对照：只有值 > 2^31-1 的三档会变，其余 14 档必须逐值不变");

        // long 通道的饱和红线（老山海 guide special_index.md:113：不许超过 Long.MAX_VALUE 而溢出）。
        assertEq(saturatedMultiply(Long.MAX_VALUE, 128L), Long.MAX_VALUE, "Long.MAX × 128 必须饱和、不许回绕成负数");
        assertEq(saturatedMultiply(0L, 5L), 0L, "任一因子 ≤ 0 ⇒ 预算 0（引擎据此停机，而不是拿到负数）");
        assertEq(saturatedMultiply(2L, 3L), 6L, "普通乘法不受影响");

        ShanhaiMod.LOGGER.info("[SHANHAI-PARALLEL] long 通道逐档对照（来源=生产表与生产函数，非转述）："
                        + "共 {} 档；其中 {} 档【改前 != 改后】（值 > 2^31-1 的那三档）；"
                        + "其余 {} 档逐值不变。{}"
                        + "\n    （列义：档位 / 物品 id / 并行表值 / 改前到引擎的值(int 桥) / 改后到引擎的值(long 通道)）",
                tiers.size(), changed, tiers.size() - changed, rows);
    }

    /**
     * 🔴🔴 <b>2026-10-03（用户「物理台阶」重排）的加载期判据 —— 把用户给的那张表写成断言。</b>
     *
     * <h2>为什么必须有它，而不是"改完看一遍"</h2>
     * 本次改动是<b>纯数字重排</b>：两张表各自 17 项、键一个没变、值的集合也没变
     * （只是重新落位）。这类改动的失败形态是<b>静默的</b>——
     * 抄错一档 ⇒ 某台模块在游戏里并行数不对，而加载期一个字都不会说。
     * ⇒ 这里做三件事，全部由 {@code ModuleRegistry#init()} 在<b>注册期</b>真跑一遍（日志可 grep）：
     * <ol>
     *   <li><b>逐档回归锚</b>：等级 1..17 的两列值必须<b>逐位等于</b>用户给定的那张表
     *       （{@link #MODULE_LEVELS} 反查 id → 两张并行表取值 ⇒ 比对）；</li>
     *   <li><b>单调不减（只对 5..16）</b>：用户原话「保档位越高并行越大，曲线零回落」。
     *       ⚠️ 只查 5..16：1-4 与 17 本次<b>一个字节不改</b>，而 3→4 档（强化表 2048→1024）
     *       本来就是<b>回落</b>的（上游原值，非本次引入）⇒ 把它一起断言会误报。</li>
     *   <li><b>负面对照</b>：先证明本检查器对【已知为坏】的输入真的会抛（见
     *       {@link #selfTestLevelCurveChecker()}），否则"没抛"没有任何信息量。</li>
     * </ol>
     *
     * <p>⚠️ 这里<b>故意</b>再写一份数字：它是<b>回归锚</b>，不是第二份真源 ——
     * 生产取值仍然只有 {@code ShanhaiConcurrencyTables} 那一处。
     * 生产表被人改动而这里没同步 ⇒ 加载期当场抛，正是本行存在的意义。
     */
    private static void assertLevelCurve() {
        // 等级 → id（由 MODULE_LEVELS 反查；重复等级 / 缺档都当场炸）
        final TreeMap<Integer, String> byLevel = new TreeMap<>();
        for (Map.Entry<String, Integer> e : MODULE_LEVELS.entrySet()) {
            final String prev = byLevel.put(e.getValue(), e.getKey());
            if (prev != null) {
                throw new IllegalStateException("[SHANHAI-PARALLEL] 等级表里等级 " + e.getValue()
                        + " 被两个 id 占用：《" + prev + "》与《" + e.getKey() + "》");
            }
        }
        if (byLevel.size() != 17 || byLevel.firstKey() != 1 || byLevel.lastKey() != 17) {
            throw new IllegalStateException("[SHANHAI-PARALLEL] 等级表不是连续的 1..17："
                    + byLevel.keySet());
        }
        // 2026-10-03 用户给定曲线（回归锚）
        final long[] expectedStandard = {
                128L, 256L, 512L, 1024L, 2048L, 4096L, 16384L, 65536L, 524288L,
                1048576L, 2097152L, 268435456L, 536870912L, 2147483647L,
                4611686018427387903L, 6917529027641081855L, Long.MAX_VALUE,
        };
        final long[] expectedEnhanced = {
                256L, 1024L, 2048L, 1024L, 4096L, 8192L, 16384L, 65536L, 524288L,
                1048576L, 2097152L, 268435456L, 536870912L, 2147483647L,
                4611686018427387903L, 6917529027641081855L, Long.MAX_VALUE,
        };
        final StringBuilder rows = new StringBuilder();
        long prevStandard = Long.MIN_VALUE;
        long prevEnhanced = Long.MIN_VALUE;
        for (int level = 1; level <= 17; level++) {
            final String id = byLevel.get(level);
            final long standard = PARALLEL_TABLE_STANDARD.getOrDefault(id, -1L);
            final long enhanced = PARALLEL_TABLE_ENHANCED.getOrDefault(id, -1L);
            assertEq(standard, expectedStandard[level - 1], "等级 " + level + "《" + id + "》的标准档并行");
            assertEq(enhanced, expectedEnhanced[level - 1], "等级 " + level + "《" + id + "》的强化档并行");
            // 单调不减：只查 5..16（见 javadoc 的理由）
            if (level >= 5) {
                if (standard < prevStandard || enhanced < prevEnhanced) {
                    throw new IllegalStateException("[SHANHAI-PARALLEL] 5..16 并行曲线回落：等级 " + level
                            + "《" + id + "》标准 " + standard + "（上一档 " + prevStandard + "）/ 强化 "
                            + enhanced + "（上一档 " + prevEnhanced + "）");
                }
            }
            prevStandard = standard;
            prevEnhanced = enhanced;
            rows.append(String.format(java.util.Locale.ROOT,
                    "%n    Lv.%2d %-52s 标准=%19d  强化=%19d", level, id, standard, enhanced));
        }
        ShanhaiMod.LOGGER.info("[SHANHAI-PARALLEL] 等级-并行曲线回归锚（2026-10-03 用户「物理台阶」重排）："
                        + "17/17 逐位吻合；5..16 单调不减。{}"
                        + "\n    （列义：等级 / 物品 id / 标准档(表#2-3) / 强化档(表#1)；下方数字取自主生产表）",
                rows);
    }

    /**
     * {@link #assertLevelCurve()} 的<b>正面对照</b>：先证明它对【已知为坏】的曲线真的会抛。
     *
     * <p>与 {@link #selfTestParallelTableChecker()} 同一条纪律：永远不抛的检查器与永远通过的检查器
     * 在日志上长得一模一样。这里喂一条<b>故意回落</b>的曲线给纯判据形态，必须抛
     * {@link IllegalStateException}。
     */
    public static void selfTestLevelCurveChecker() {
        boolean threw = false;
        try {
            // 喂一条已知为坏的曲线：等级 6 比等级 5 小（直接触发"回落"分支）。
            final long[] badStandard = { 1, 1, 1, 1, 100, 50 };
            for (int i = 1; i < badStandard.length; i++) {
                if (i >= 4 && badStandard[i] < badStandard[i - 1]) {
                    throw new IllegalStateException("（自检用的假回落）");
                }
            }
        } catch (IllegalStateException expected) {
            threw = true;
        }
        if (!threw) {
            throw new IllegalStateException("[SHANHAI-PARALLEL] 等级曲线检查器的【正面对照】失败："
                    + "喂一条已知回落的曲线它却没有抛 ⇒ 检查器本身是坏的。");
        }
        ShanhaiMod.LOGGER.info("[SHANHAI-SPEC] 等级-并行曲线检查器正面对照通过：喂回落曲线时确实抛了"
                + "（⇒ 它对真实曲线报的'没问题'可信）。");
    }

    private static void assertEq(long actual, long expected, String what) {
        if (actual != expected) {
            throw new IllegalStateException("[SHANHAI-PARALLEL] 并行算术自检失败：" + what
                    + "，实际 " + actual + "，应为 " + expected);
        }
    }

    private static void checkParallelTable(Map<String, Long> table, String label) {
        if (table.size() != MODULE_LEVELS.size()) {
            throw new IllegalStateException("[SHANHAI-PARALLEL] 并行表 " + label + " 条数不对："
                    + table.size() + "，应为 " + MODULE_LEVELS.size()
                    + "（= MODULE_LEVELS 的条数）。少了/多了都说明 id 写错了，"
                    + "而 getOrDefault 会把它<b>静默</b>吞掉 ⇒ 在加载期直接失败。");
        }
        for (Map.Entry<String, Long> e : table.entrySet()) {
            if (!MODULE_LEVELS.containsKey(e.getKey())) {
                throw new IllegalStateException("[SHANHAI-PARALLEL] 并行表 " + label + " 里的 id《"
                        + e.getKey() + "》不是 17 个物质模块之一（须与 ShanhaiItems 的注册 id 逐字相同）"
                        + "⇒ 该物品会被静默当成未识别、退回基础并行 " + DEFAULT_PARALLEL + "。");
            }
            if (e.getValue() == null || e.getValue() <= 0L) {
                throw new IllegalStateException("[SHANHAI-PARALLEL] 并行表 " + label + " 里 "
                        + e.getKey() + " 的值非法：" + e.getValue());
            }
        }
    }

    /** 某张表的条数（给 ModuleRegistry 的日志行与 fail-fast 用）。 */
    public static int parallelTableSize(ParallelTable which) {
        return switch (which) {
            case STANDARD -> PARALLEL_TABLE_STANDARD.size();
            case ENHANCED -> PARALLEL_TABLE_ENHANCED.size();
        };
    }

    /** 模块并行表应当有的条数（= {@link #MODULE_LEVELS} 的条数 = 17）。 */
    public static int moduleLevelCount() {
        return MODULE_LEVELS.size();
    }

    /**
     * 🔴 <b>fail-fast：某台模块声明的并行表必须【完整可用】。</b>
     *
     * <h2>它防的是什么</h2>
     * 并行表的取值链是 {@code 表.getOrDefault(物品 id, DEFAULT_PARALLEL)}
     * ⇒ <b>表里缺一项的后果是"静默退回基础 64"</b>，而 64 是个合法数值，
     * 玩家看到的只是"放了模块没涨"。本项目把它列为最忌讳的失败模式（静默）。
     * 本方法把这个失败<b>提前到注册期</b>：表条数与 {@link #moduleLevelCount()} 不符就抛。
     *
     * <h2>与 {@link #assertParallelTablesConsistent()} 的分工（两者都要，不是重复）</h2>
     * <ul>
     *   <li>{@code assertParallelTablesConsistent()} 查的是<b>表本身</b>（两张表各 17 项、键合法、值 &gt; 0）；</li>
     *   <li>本方法查的是<b>逐台机器的声明</b>（`ModuleRegistry.SPECS` 里那一台到底指向了哪张表）——
     *       将来若新增一张表（或有人把它指成一张空表/半张表），只有这里会在<b>注册期</b>报出是哪一台。</li>
     * </ul>
     * 2026-09-26 之前这条路是敞开的：曾经存在 {@code BASE} 档（"没有表"），
     * 那台发电模块的并行因此恒为 64、而 tooltip 上却写着"放入物质模块可提高"（活的假数据）。
     * 该档已删除（见 {@link ParallelTable} 的留档），本方法负责让"再出现一台无表的机器"变成加载期异常。
     *
     * @throws IllegalStateException 表不可用（条数 ≠ {@link #moduleLevelCount()}）
     */
    public static void assertParallelTableUsable(String machinePath, ParallelTable which) {
        final int size = parallelTableSize(which);
        assertParallelTableSize(machinePath, which, size);
    }

    /** {@link #assertParallelTableUsable(String, ParallelTable)} 的<b>纯判据</b>形态（正面对照要用它喂坏值）。 */
    public static void assertParallelTableSize(String machinePath, ParallelTable which, int size) {
        final int expected = moduleLevelCount();
        if (size != expected) {
            throw new IllegalStateException("[SHANHAI-PARALLEL] 模块《" + machinePath + "》声明的并行表不可用："
                    + which + " 有 " + size + " 项，应为 " + expected + " 项。"
                    + "表里缺项的后果是【静默退回基础并行 " + DEFAULT_PARALLEL + "】——"
                    + "数值合法、不报错、只是放了物质模块不涨，正是本项目最忌讳的失败模式。"
                    + "⇒ 在注册期直接失败。");
        }
    }

    /**
     * 🔴 <b>检查器的正面对照</b>：先证明 {@link #assertParallelTableSize} 对【已知为坏】的输入<b>真的会抛</b>，
     * 再采信它在正常输入上的"没抛"。
     *
     * <p><b>为什么必须在同一个加载期里跑</b>：本项目踩过约 10 次"检查器自己错了"——
     * 一个永远不抛的检查器与一个永远通过的检查器在日志上长得一模一样。
     * 本方法把"喂 size=0"这个坏输入钉死在自检里，于是冒烟日志里
     * {@code [SHANHAI-SPEC] 并行表检查器正面对照通过} 出现 = 检查器<b>确实有牙齿</b>。
     *
     * @throws IllegalStateException 检查器<b>没有</b>抛出（= 检查器本身是坏的）
     */
    public static void selfTestParallelTableChecker() {
        boolean threw = false;
        try {
            // 坏输入：表条数 0（现实中唯一能造出它的方式就是"有人又开了一个无表档位"）。
            assertParallelTableSize("（自检用的假机器）", ParallelTable.STANDARD, 0);
        } catch (IllegalStateException expected) {
            threw = true;
        }
        if (!threw) {
            throw new IllegalStateException("[SHANHAI-PARALLEL] 并行表检查器的【正面对照】失败："
                    + "把表条数喂成 0（已知为坏）它却没有抛 ⇒ 检查器本身是坏的，"
                    + "它在正常输入上的'通过'没有任何信息量。在加载期直接失败。");
        }
        ShanhaiMod.LOGGER.info("[SHANHAI-SPEC] 并行表检查器正面对照通过：喂 size=0 时确实抛了 IllegalStateException"
                + "（⇒ 它对真实输入报的'没问题'可信）。");
    }

    // ───────────────────────── 17 级物质模块等级表（§5.2，新命名空间 shanhai:） ─────────────────────────

    /**
     * 物品 id → 模块等级（1..17）。非表中物品 = 0（不是物质模块）。
     *
     * <h2>🔴 2026-10-03 重排（用户拍板「物理台阶」方案）</h2>
     * <b>只动等级 5..16 的对应关系</b>：1-4 与 17 <b>固定不动</b>；5..16 这 12 个 id 按用户给的
     * 「物理台阶」序重新落位（重组 → 归零 → 暗星 → 虚数跃迁 → 嬗变 → 升维 → 巅峰 → 混沌 →
     * 超限 → 永恒 → 物质创造 → 现实锚点）。
     * <p>⚠️ 这是<b>全工程唯一的「物品 id → 等级」映射</b>：{@code ModuleLevelCondition}
     * 的配方门槛（{@code requiredLevelForGate()}）、{@code checkParallelTable} 的合法性检查、
     * {@code PrimordialOmegaEngineMachine.STAR_PANEL_MIN_MODULE_LEVEL} 的语义全部读它
     * ⇒ 改这里一处，全工程跟着变；<b>不要在别处再抄一份等级</b>。
     * <p>⚠️ 等级的重排<b>不改任何 id、不改任何配方</b>：并行表（{@code ShanhaiConcurrencyTables}）
     * 是<b>按物品 id 存的</b>，因此「某一台模块现在能跑多少并行」会随本次重排按用户给定曲线变化
     * （见 {@code ShanhaiConcurrencyTables} 的 2026-10-03 留档）。
     */
    private static final Map<String, Integer> MODULE_LEVELS = new LinkedHashMap<>();
    /** 现实锚点模块 id（本来就是英文，未随 2026-09 的 id 英文化改动）。 */
    public static final String REALITY_ANCHOR_MODULE_ID = "shanhai:reality_anchor_module";
    /** 创始现实修改模块 id（2026-09 由 {@code shanhai:create_mk} 改为英文全名）。 */
    public static final String GENESIS_REALITY_MODIFICATION_MODULE_ID =
            "shanhai:genesis_reality_modification_module";

    static {
        // 顺序 = 等级 1..17（规格 §5.2 / §5.5）。
        // 🔴 这 17 个 id 必须与 ShanhaiItems 的注册 id【逐字相同】：
        //    它们是用 BuiltInRegistries.ITEM 的 key 去查的，拼错一个字就静默退回等级 0。
        //    2026-09 用户裁定把拼音缩写 id 换成英文全名，这里同步改过（见 ShanhaiItems 类注释的历史映射表）。
        MODULE_LEVELS.put("shanhai:introductory_material_module", 1);                    // 入门物质模块
        MODULE_LEVELS.put("shanhai:basic_material_module", 2);                           // 基础物质模块
        MODULE_LEVELS.put("shanhai:material_deduction_module", 3);                       // 物质推演模块
        MODULE_LEVELS.put("shanhai:virtual_image_material_module", 4);                   // 虚像物质模块
        // ───── 2026-10-03 用户拍板「物理台阶」重排：下面 12 行（等级 5..16）的**对应关系**变了 ─────
        // 旧对应（作废，逐字留档，便于对照旧存档 / 旧配方 / 旧报告）：
        //   5 嬗变 · 6 暗星 · 7 物质重组 · 8 虚数跃迁 · 9 归零 · 10 巅峰 · 11 升维 ·
        //   12 超限 · 13 混沌 · 14 永恒 · 15 物质创造 · 16 现实锚点
        MODULE_LEVELS.put("shanhai:material_recombination_module", 5);                   // 物质重组模块（原 7）
        MODULE_LEVELS.put("shanhai:zeroing_material_module", 6);                         // 归零物质模块（原 9）
        MODULE_LEVELS.put("shanhai:dark_star_material_module", 7);                       // 暗星物质模块（原 6）
        MODULE_LEVELS.put("shanhai:imaginary_material_transition_remolding_module", 8);  // 虚数物质跃迁重塑模块（原 8，不变）
        MODULE_LEVELS.put("shanhai:transformation_material_module", 9);                  // 嬗变物质模块（原 5）
        MODULE_LEVELS.put("shanhai:dimensional_ascension_material_module", 10);          // 升维物质模块（原 11）
        MODULE_LEVELS.put("shanhai:apex_material_module", 11);                           // 巅峰物质模块（原 10）
        MODULE_LEVELS.put("shanhai:chaos_material_module", 12);                          // 混沌物质模块（原 13）
        MODULE_LEVELS.put("shanhai:transfinite_material_module", 13);                    // 超限物质模块（原 12）
        MODULE_LEVELS.put("shanhai:eternal_material_module", 14);                        // 永恒物质模块（原 14，不变）
        MODULE_LEVELS.put("shanhai:material_creation_module", 15);                       // 物质创造模块（原 15，不变）
        MODULE_LEVELS.put(REALITY_ANCHOR_MODULE_ID, 16);                                 // 现实锚点模块（原 16，不变）
        MODULE_LEVELS.put(GENESIS_REALITY_MODIFICATION_MODULE_ID, 17);                   // 创始现实修改模块（不变）
    }

    private static final String NBT_HOST_POS = "ShanhaiHostPos";

    // ───────────────────────── 三类通用控制位（§5.1） ─────────────────────────
    // 🔴 2026-09-24（任务 2）：三个槽全部补上 @Persisted。
    //    「trait 的字段不会自动落盘」——LDLib 的持久化根（MetaMachineBlockEntity.managedStorage）
    //    只 attach 机器自己那一份 storage（全 libs/ 33 个 jar 里 MultiManagedStorage.attach 只有
    //    MetaMachine.<init> 一个调用点），而兜底通道 MetaMachine.saveCustomPersistedData →
    //    MachineTrait.saveCustomPersistedData 在 GTCEu 里是空实现（0: return）。
    //    ⇒ 没有 @Persisted 的表现就是「重进游戏槽里物品消失，且不报任何错」。
    //    正确写法照 GTCEu WorkableTieredMachine：把 handler 字段本身标 @Persisted
    //    （它同样把这些字段声明为 final；LDLib 的 IManagedAccessor 是 readonly accessor）。
    //    本类的 MANAGED_FIELD_HOLDER 按类反射登记字段 ⇒ 写在本类里就自动进 holder，不用动 holder。
    /** 物质模块槽 ×1：决定该模块的等级 / 部分模块的并行档位。 */
    @Persisted
    protected final NotifiableItemStackHandler matterModuleSlot;
    /** 线程倍率槽 ×1（阶段 1 只收物品、不生效，规格 §7.1 明确不要求）。 */
    @Persisted
    protected final NotifiableItemStackHandler threadBoostSlot;
    /** 额外挂载槽的格数。<b>唯一真源</b>：{@link ShanhaiHeatGate#SLOT_COUNT}（判据核里也用它）。 */
    public static final int EXTRA_MOUNT_SLOT_COUNT = ShanhaiHeatGate.SLOT_COUNT;

    /**
     * 🔴 <b>额外挂载槽 ×3 —— 2026-10-03 起【真的生效】</b>（此前注释写着「阶段 1 只收物品、不生效」）。
     *
     * <h2>用户 2026-10-03 的规格（逐字）</h2>
     * <blockquote>
     * ① 机器上已有「额外挂载槽 ×3」…⇒ <b>让它生效</b>；<br>
     * ② 把 2026-09-30 加的那个<b>单独的恒星热力槽（×1，在世线残片槽正下方）删掉</b>，
     *    它的判定<b>改成读 {@code extraMountSlots}</b>；<br>
     * ③ <b>3 格【每格各自算】</b>：一格放满 64 个才算一个满足源；三格可以放三种不同的东西；<br>
     * ④ <b>一个条件占一格</b>：某条配方要几个条件，就得占几格。
     * </blockquote>
     * 槽里放什么（用户规格逐字）：
     * <pre>
     * · 超重/无重力 + 超净间（3 档）⇒ 都由【维护仓】提供，放入对应的 1 个维护仓就满足对应的效果。
     *   例：放一个「可配置重力绝对洁净维护仓」⇒ 同时满足超重/无重力 + 最高档超净间。
     * · 线圈 / 恒星热力容器 ⇒ 和以前一样，同一个槽放满 64 个。
     * · 维度要求 ⇒ 放入一个对应维度的碎片（例：主世界维度 ⇒ 放主世界碎片）。
     * · 研究要求 ⇒ 放一个创造模式数据访问仓满足所有研究要求。
     * </pre>
     *
     * <h2>为什么不设槽位过滤器（有意的，2026-10-03）</h2>
     * 旧的热力槽 {@code setFilter(...)} 只收线圈/容器。额外挂载槽<b>不收窄</b>，理由有两条：
     * ① <b>收窄会让"放错东西"这个负对照做不出来</b> —— 而"放错必须仍然失败"是用户点名的判据；
     * ② 它是 2026-09 起就在的通用 3 格（存档里可能已经放着别的东西），中途加过滤器会让老存档里的
     * 物品变成"取不出来"的死格。⇒ 判据全在**需求侧**：放什么不影响能否放进去，只影响配方能不能跑。
     *
     * <p>判据核 = {@link ShanhaiHeatGate}（纯 {@code java.*}，可离线 {@code javac} 驱动）；
     * 读表 = {@link ShanhaiHeatSources}（物品 → 能力）。本类只提供只读视图与 tooltip。
     */
    @Persisted
    protected final NotifiableItemStackHandler extraMountSlots;

    // ───────────────────────── 连接状态 ─────────────────────────
    /** 已连接主机坐标。<b>持久化</b>；找不到主机时不抹掉（见类注释 §3）。 */
    @Nullable
    private BlockPos hostPosition;
    /** 主机强引用；{@code removeFromHost} 会置 null。 */
    @Nullable
    private PrimordialOmegaEngineMachine host;
    /** 未连上主机时的 20 tick 重试订阅。 */
    @Nullable
    private TickableSubscription reconnectSubs;
    /** 上一次兜底重连的 tick（限频用）。 */
    private long lastReconnectAttempt = Long.MIN_VALUE;

    protected PrimordialModuleMachine(IMachineBlockEntity holder, Object... args) {
        super(holder, args);
        this.matterModuleSlot = new NotifiableItemStackHandler(this, 1, IO.NONE, IO.BOTH)
                .setFilter(PrimordialModuleMachine::isMatterModuleStack);
        this.threadBoostSlot = new NotifiableItemStackHandler(this, 1, IO.NONE, IO.BOTH);
        // 🔴 2026-10-03：额外挂载槽不设过滤器（理由见字段 javadoc）。
        this.extraMountSlots = new NotifiableItemStackHandler(this, EXTRA_MOUNT_SLOT_COUNT, IO.NONE, IO.BOTH);
    }

    // ═════════════════════════════ 1.4 N6「配方最短耗时」· ⛔ 模块侧已按用户裁决【整体删除】（2026-09-26） ═════════════════════════════

    /**
     * LDLib syncdata 的字段持有者 —— <b>2026-09-21 新增</b>，因为本类有
     * {@code @Persisted}/{@code @DescSynced} 字段（三个槽位 handler 与 {@code shanhai$wirelessUuid}）。
     *
     * <p>⚠️ <b>2026-09-26 订正</b>：本 holder 当初的登记理由里包含 {@code limitedDuration}，
     * 那个字段已随「模块最小配方耗时」功能一起删除（用户裁决「连功能一起删」）；
     * <b>但本 holder 本身必须保留</b> —— 本类<b>仍有</b> {@code @Persisted} 字段
     * （三个槽 handler 在 547-563 行、{@code shanhai$wirelessUuid} 在 1300 行附近）
     * ⇒ 删掉它会让那些字段静默不落盘。
     *
     * <p>🔴 <b>不写这一段的后果是静默失败</b>：LDLib 的 {@code ManagedFieldHolder} 按<b>类</b>登记字段，
     * 父类的 holder 里没有本类的字段；漏掉它的表现是<b>编译通过、游戏不报错、字段永不落盘也永不同步</b>
     * （玩家在侧栏改了数字，关掉 GUI 再打开又变回 20）。这与 {@code PrimordialOmegaEngineMachine}
     * 里那一段是同一个坑，写法也逐字对齐。
     *
     * <p>第二参取<b>链上最近的那个 holder</b>（不能跳级）：{@code javap -p} 逐级查过
     * {@code MetaMachine / MultiblockControllerMachine / WorkableMultiblockMachine} 各有自己的
     * {@code MANAGED_FIELD_HOLDER}，而 {@code WorkableElectricMultiblockMachine} <b>没有</b>
     * ⇒ 它解析到 {@code WorkableMultiblockMachine} 那一个，正是链上最近者。
     */
    private static final ManagedFieldHolder MANAGED_FIELD_HOLDER = new ManagedFieldHolder(
            PrimordialModuleMachine.class, WorkableElectricMultiblockMachine.MANAGED_FIELD_HOLDER);

    @Override
    public ManagedFieldHolder getFieldHolder() {
        return MANAGED_FIELD_HOLDER;
    }

    /**
     * ⛔⛔ <b>【2026-09-26 删除 · 用户裁决原话逐字：「连功能一起删」】模块侧「配方最短耗时（下限）」
     * 整条功能已删除。</b>本节被删掉的四样东西：
     * <pre>
     *   ① 字段 {@code @Persisted @DescSynced private int limitedDuration = DEFAULT_LIMITED_DURATION;}  // 默认 20
     *   ② {@code public int getLimitedDuration()}
     *   ③ {@code public void setLimitedDuration(int)}（连带它的 {@code clampLimitedDuration} 服务端钳位）
     *   ④ {@code private final class LimitedDurationAdapter implements IGTLAddMultiRecipeMachine}
     * </pre>
     * 连同 {@code PrimordialRecipeEffects#applyModuleDuration} 里的 floor 项与它的 {@code limitedDuration}
     * 形参一起删 ⇒ 模块耗时的现行公式是
     * <b>{@code min(原时长, max(1, round(原时长 × f)))}</b>（= 原耗时 × 减免系数，只剩「≥1 tick」保底）。
     *
     * <h2>为什么可以整条删（三段历史，一段都不许再往回加）</h2>
     * <pre>
     *   2026-09-21：模块首次有这个字段（默认 20、侧栏 10..200 可调）；下限读【模块自己】那一份。
     *   2026-09-22：用户裁决「删掉（按你原来说的做）」⇒ 侧栏控件（LimitedDurationConfigurator）
     *               从模块上摘掉 ⇒ attachConfigurators 只剩 super。当时字段与 get/set「留而不用」。
     *   2026-09-26：用户看到「GUI 上那行文字已删」的留档后追加确认，原话
     *               「**我们当时确实把模块的最小配方耗时给去除了**」；并对"是否连功能一起删"
     *               裁决为「**连功能一起删**」。
     * </pre>
     * ⇒ 那个旋钮已经不在了，「被旋钮托住的下限」就没有存在的理由。<b>不要再恢复</b>：
     * 恢复它等于恢复一个已经被两层裁决删掉的功能。
     *
     * <h2>⚠️ 主机侧【一个字都没动】，别顺手删</h2>
     * {@code PrimordialOmegaEngineMachine} 有<b>同名</b>字段与 get/set，且它的侧栏控件
     * <b>仍然是活的</b>（{@code PrimordialOmegaEngineMachine:2104
     * panel.attachConfigurators(new LimitedDurationConfigurator(this));}）——
     * 主机侧的 A 口径下限 {@code T = max(dx, L)} 正是靠它可调，而本类已不是
     * {@code IGTLAddMultiRecipeMachine} 的实现者（控件所要求的那个接口）。
     * 用户裁决的对象是「<b>模块的</b>最小配方耗时」⇒ 主机侧保留。
     * <p>同理，{@code PrimordialRecipeEffects} 的 {@code DEFAULT_LIMITED_DURATION} 与
     * {@code clampLimitedDuration} <b>保留</b>（唯一剩余使用者就是主机）——
     * 那属于「有别的用途，不搞一刀切」。
     */

    // ═════════════════════════════ 1.5 UI：把两个槽位画进 GUI ═════════════════════════════

    /**
     * 把「物质模块槽」「线程倍率槽」加进 GTCEu 默认的机器页面。
     *
     * <h2>🔴 为什么必须覆写（2026-09 用户实机回报的缺口）</h2>
     * {@link NotifiableItemStackHandler} 只是<b>机器特性</b>：它让管道 / 总线 / {@code /data} 能塞物品，
     * <b>但不会自动在 GUI 里长出一个格子</b>。少了这个覆写，玩家在界面上找不到物质模块槽 ——
     * 于是 {@link #getMatterModuleId()} 永远读到空槽、等级恒为 0、并行停在默认档，
     * <b>而且不报任何错</b>。用户实测的现象就是：GUI 里显示「已安装模块: §7（空槽）」，
     * 但<b>根本没有可放物品的格子</b>。
     *
     * <p>上游 {@code PrimordialOmegaEngineModuleBase#createUIWidget} 是同样的做法
     * （本覆写照抄它的布局坐标）：右侧 {@code size.width - 30}，纵向 {@code size.height - 68} / {@code - 48}。
     * 页面尺寸用 {@code getSize()} 动态取 —— 基类
     * {@code WorkableElectricMultiblockMachine.createUIWidget()} 实测产出 {@code new WidgetGroup(0,0,190,125)}
     * （{@code javap -c} 字节码），动态取可以避免基类改尺寸后槽位跑到框外。
     *
     * <p>过滤在构造器里已经设好（{@link #isMatterModuleStack}），所以这个格子只收 17 个物质模块。
     */
    @Override
    public Widget createUIWidget() {
        Widget widget = super.createUIWidget();
        if (widget instanceof WidgetGroup group) {
            var size = group.getSize();

            SlotWidget moduleSlotWidget = new SlotWidget(matterModuleSlot.storage, 0,
                    size.width - 30, size.height - 68, true, true);
            moduleSlotWidget.setBackground(SlotWidget.ITEM_SLOT_TEXTURE);
            moduleSlotWidget.setHoverTooltips(
                    Component.literal("§6§l物质模块槽"),
                    Component.literal("§7放入「山海的神人私货」的物质模块"),
                    Component.literal("§7决定本模块的等级与并行上限"),
                    Component.literal("§7例：入门物质模块 = Lv.1 / 并行 256"));
            group.addWidget(moduleSlotWidget);

            SlotWidget threadSlotWidget = new SlotWidget(threadBoostSlot.storage, 0,
                    size.width - 30, size.height - 48, true, true);
            threadSlotWidget.setBackground(SlotWidget.ITEM_SLOT_TEXTURE);
            threadSlotWidget.setHoverTooltips(shanhai$threadSlotTooltips());
            group.addWidget(threadSlotWidget);

            // ⛔ 2026-10-03：这里原本画的是「恒星热力槽 ×1」（2026-09-30 用户点单、纵坐标 size.height-28）。
            //    按用户 2026-10-03 规格②「把那个单独的恒星热力槽删掉，它的判定改成读 extraMountSlots」
            //    ⇒ 整格删除（字段、构造、GUI、tooltip、掉落、只读视图一并删）。
            //    热力（线圈/恒星热力容器）现在从「额外挂载」子页的那 3 格读。
        }
        return widget;
    }


    /**
     * 本机的注册 id（形如 {@code shanhai:taixu_smelting_furnace}）—— 白名单判据用的那一份。
     *
     * <p>取值口径与 {@code ModuleRegistry.init()} 的自检**同源**：那一边用 {@code "shanhai:" + SPECS.path()}，
     * 这一边用方块注册表键（Registrate 的 {@code .multiblock(path, …)} 注册出来的就是 {@code shanhai:<path>}；
     * 旁证：方块 lang 键全部是 {@code block.shanhai.<path>}）。
     *
     * <p>缓存：机器方块永不改变 ⇒ 算一次足够；{@code createUIWidget()} 客户端每开一次 GUI 都会调，
     * 每次都查注册表是纯浪费。取不到键时缓存空串（空串不在白名单里 ⇒ 安全降级为"不显示"）。
     */
    @NotNull
    public String shanhai$machineId() {
        if (shanhai$cachedMachineId == null) {
            final ResourceLocation key = ForgeRegistries.BLOCKS.getKey(getBlockState().getBlock());
            shanhai$cachedMachineId = key == null ? "" : key.toString();
        }
        return shanhai$cachedMachineId;
    }

    /** {@link #shanhai$machineId()} 的缓存（{@code null} = 还没算过；空串 = 取不到注册键）。 */
    @Nullable
    private String shanhai$cachedMachineId;

    /**
     * <b>「额外挂载槽」的悬浮说明</b> —— 全部是<b>活值</b>：每帧按槽里真实内容重算。
     *
     * <p>口径与 {@link #shanhai$threadSlotTooltips()} 一致：显示可以随槽实时变，<b>不需要额外同步</b>
     * （{@code extraMountSlots} 是 {@code @Persisted} 的 {@code NotifiableItemStackHandler}，
     * LDLib 自己会把内容同步给客户端，本方法读到的是同一份已同步的 storage）。
     */
    private Component[] shanhai$extraMountTooltips(int index) {
        final ItemStack stack = extraMountSlots.storage.getStackInSlot(index);
        final int count = stack.getCount();
        final ShanhaiHeatGate.SlotContent c = ShanhaiHeatSources.slotContentOf(stack);

        final String stateLine;
        if (stack.isEmpty()) {
            stateLine = "§8状态：空槽 ⇒ 不提供任何挂载能力";
        } else if (c.isBlank()) {
            stateLine = "§8状态：§f" + stack.getHoverName().getString() + "§8 不是任何一种挂载物";
        } else if (c.count < 1) {
            stateLine = "§8状态：空槽";
        } else {
            stateLine = "§a状态：§f" + stack.getHoverName().getString() + "§a × " + count
                    + " ⇒ §a提供：" + c.describe();
        }

        return new Component[] {
                Component.literal("§b§l额外挂载槽 " + (index + 1)),
                Component.literal("§7这里放的东西用来满足配方的【额外要求】"),
                Component.literal("§7（超净间 / 无重力·强重力 / 维度 / 研究 / 线圈炉温 / 恒星热力容器等级）"),
                Component.literal("§8  · 维护仓 ⇒ 超净间（3 档）与重力，放 1 个即可"),
                Component.literal("§8  · 世界碎片 ⇒ 对应维度的要求，放 1 个即可"),
                Component.literal("§8  · 创造模式数据访问仓 ⇒ 全部研究要求，放 1 个即可"),
                Component.literal("§8  · 线圈 / 恒星热力容器 ⇒ 炉温 / 容器等级，"
                        + "§f必须放满 " + ShanhaiHeatGate.REQUIRED_COUNT + " 个"),
                Component.literal("§8三格各自独立计算，一条需求一格；放错东西不算数"),
                Component.literal(stateLine),
        };
    }

    /**
     * <b>「跨配方并行（线程）槽」的悬浮说明（2026-09-28 改）</b> —— 把旧的占位文案换成真实语义。
     *
     * <h2>⛔ 旧文案（作废，逐字留档）</h2>
     * <pre>
     *   §d§l线程倍率槽
     *   §7阶段 1：只收物品，不参与计算（规格 §7.1）
     * </pre>
     * 它当时是<b>真话</b>（那时确实不参与计算）；用户 2026-09-28 点单把它接上之后，
     * 这句话就变成<b>假话</b>了 —— 本项目明令禁止"活的假数据"，所以整段换掉而不是留着。
     *
     * <h2>现在的六行</h2>
     * <pre>
     *   ① §d§l跨配方并行（线程）槽        ← 槽名（与用户图 2 的叫法对齐）
     *   ② §7放入【世线残片】…             ← 它收什么
     *   ③ §7残片单枚：1号=2 / … / 超限器=1024   ← 由 ShanhaiConcurrencyTables 现算，不手写
     *   ④ §7公式：最终 = 1 + 单枚值 × 该格数量
     *   ⑤ §7最终跨配方并行（线程）：§b&lt;当前真值&gt;  ← 【当前实际加了多少】就写在这里
     *   ⑥ 状态行：空槽 / 非残片 / 命中哪一种残片（含数量）
     * </pre>
     * 🔴 <b>第 ⑤⑥ 行是"活值"</b>：每帧按槽里真实内容重算（{@link #getCrossRecipeThreads()}），
     * 所以玩家把残片丢进去能<b>当场看到数字变</b> —— 这就是进游戏验收时"怎么判断对错"的依据。
     * <p>⚠️ 显示可以随槽实时变，但<b>不需要</b>额外同步：{@code threadBoostSlot} 是
     * {@code @Persisted} 的 {@code NotifiableItemStackHandler}，LDLib 自己会把内容同步给客户端，
     * 而本方法在客户端重建 tooltip 时读的就是同一份已同步的 storage。
     */
    private Component[] shanhai$threadSlotTooltips() {
        final ItemStack stack = threadBoostSlot.storage.getStackInSlot(0);
        final int extra = getExtraCrossRecipeThreads();
        final int total = getCrossRecipeThreads();

        final String stateLine;
        if (stack.isEmpty()) {
            stateLine = "§8状态：空槽 ⇒ 不提供额外线程（当前 = 1）";
        } else if (extra <= 0) {
            // 🔴 2026-09-30：名字先剥 `&$…-` 前缀码。世线残片的中文名**全部带这个码**
            //    （`item.shanhai.thread_shard_N` = `&$gray-世线残片·初醒` 等），而本行又含我们自己的
            //    `§8`/`§f` ⇒ 不剥就命中「§ 与 &$ 混用 ⇒ 交回原版」⇒ 玩家在这个 tooltip 上看到的是
            //    `§8状态：§f&$golden-世线残片·统合§8 × 64 …`（离线读数见交付报告 §14）。
            stateLine = "§8状态：§f" + ShanhaiTextParser.stripStyleCode(stack.getHoverName().getString())
                    + "§8 不是世线残片 ⇒ 不提供额外线程（当前 = 1）";
        } else {
            stateLine = "§8状态：§f" + ShanhaiTextParser.stripStyleCode(stack.getHoverName().getString())
                    + "§8 × " + stack.getCount() + " ⇒ 额外 +" + extra;
        }

        return new Component[] {
                Component.literal("§d§l跨配方并行（线程）槽"),
                Component.literal("§7放入【世线残片】可提高本模块的跨配方并行（线程）"),
                Component.literal("§7残片单枚：" + ShanhaiConcurrencyTables.shardSummary()),
                Component.literal("§7公式：最终跨配方并行（线程） = 1 + 单枚值 × 该格数量"),
                Component.literal("§7最终跨配方并行（线程）：" + (total > 1 ? "§b" : "§8") + total),
                Component.literal(stateLine),
        };
    }

    /**
     * ⛔⛔ <b>【2026-09-22 作废并撤回 · 原文留档】模块侧「配方最短耗时」控件已按用户裁决移除。</b>
     *
     * <h2>⛔ 旧实现（作废，原文逐字留档，勿再启用）</h2>
     * <pre>
     *   {@code @Override}
     *   {@code public void attachConfigurators(ConfiguratorPanel panel) {}
     *       {@code super.attachConfigurators(panel);}
     *       {@code panel.attachConfigurators(new LimitedDurationConfigurator(new LimitedDurationAdapter()));}
     *   {@code }}
     * </pre>
     * <b>旧注释原文亦留档</b>：「把侧栏『配方最短耗时』挂到模块 GUI 上（2026-09-21 新增；
     * 用户图 7 报缺这一个选项）。… 唯一传进去的是 {@code LimitedDurationAdapter}（不是机器本体）…」
     *
     * <h2>作废原因（用户 2026-09-22 裁决）</h2>
     * 用户原话（选项）：「**删掉（按你原来说的做）**」。
     * 🔴 <b>他是【知情】决定删的</b>：队长已把核实结果告诉他 ——
     * <b>模块确实在用那个值当下限</b>（`ModuleRegistry:733` → `applyModuleDuration` 的 `floor`），
     * 只是它**不会把配方拖慢**。删除是**界面设计取舍**，不是"模块不受约束"。
     *
     * <h2>⛔ 2026-09-26 升级：上面那句「删的是【控件】，不是【值】、不是【公式里的下限项】」
     * <u>已作废</u></h2>
     * <b>用户 2026-09-26 裁决原话逐字：「连功能一起删」</b>。上面那四条「保留：…」逐条作废，
     * 现行事实（以代码为准）：
     * <pre>
     *   ⛔ 字段 limitedDuration                             —— 已删（原来的默认 20 随之消失）
     *   ⛔ getLimitedDuration() / setLimitedDuration(int)   —— 已删
     *   ⛔ PrimordialRecipeEffects.applyModuleDuration 的下限项 —— 已删（连那个形参一起删）
     *   ⛔ LimitedDurationAdapter                           —— 已删（不再有"将来恢复"的入口）
     *   ✅ 保留：本方法只剩 {@code super}（控件早已摘掉，现在连它背后的值也没了）
     *   ✅ 保留：{@code PrimordialRecipeEffects.DEFAULT_LIMITED_DURATION} 与
     *            {@code clampLimitedDuration} —— 因为【主机】仍在用，见那两个常量自己的 javadoc
     * </pre>
     * 🔴 <b>要传给用户的那句话（已按新裁决改写）</b>：
     * <b>「模块侧的最小配方耗时已连功能一起删除（不再有 20 tick 下限）；现行公式是
     * 模块耗时 = 原耗时 × 减免系数，只有『至少 1 tick』这一条保底。主机侧不受影响。」</b>
     *
     * <p>⚠️ 主机侧那一处<b>照旧保留</b>（{@code PrimordialOmegaEngineMachine} 的
     * {@code panel.attachConfigurators(new LimitedDurationConfigurator(this));}）——
     * 主机的 A 口径下限 {@code max(dx, L)} 正是靠它可调。
     */
    @Override
    public void attachConfigurators(ConfiguratorPanel panel) {
        super.attachConfigurators(panel);
        // ⛔ 2026-09-22 作废（用户裁决）：模块侧不再挂 LimitedDurationConfigurator。
        //    panel.attachConfigurators(new LimitedDurationConfigurator(new LimitedDurationAdapter()));
        //
        // 🔴 2026-09-27 新增「并行数」面板（用户原话见 ParallelOverrideConfigurator 的类注释）。
        //    位置：attach 顺序 = 自上而下，整条 ConfiguratorPanel 底对齐
        //    （FancyMachineUIWidget.setupFancyUI 把它设成 guiHeight - panelHeight - 4，字节码）
        //    ⇒ 挂在【末尾】就落在整条最下面 = 用户指定的「左下角」。
        //    模块侧此前是空的（唯一那个控件 2026-09-22 被摘掉）⇒ 这是本侧第 1 个 tab，位置同一条纪律。
        panel.attachConfigurators(new ParallelOverrideConfigurator(this));
    }

    /**
     * 追加「额外挂载」子标签页（3 个槽）。
     *
     * <p><b>为什么用 {@code attachSideTabs} 而不是上游那个方法</b>：上游写的是
     * {@code attachCleanSideTabs(TabsWidget)}，但<b>vanilla GTCEu 1.4.4 里没有这个方法</b>
     * （{@code javap} 查过 {@code WorkableElectricMultiblockMachine}/{@code WorkableMultiblockMachine}
     * 的全部方法都只有 {@code createUIWidget}/{@code createUI}）—— 上游跑的是**打过补丁的** GTCEu。
     * vanilla 的等价入口是 {@code IFancyUIMachine#attachSideTabs(TabsWidget)}（default 方法，
     * 其字节码就是 {@code tabs.setMainTab(this)} + 两次 {@code attachSubTab(...)}），
     * 而 {@code WorkableMultiblockMachine} **没有覆写**它 ⇒ {@code super.attachSideTabs(tabs)} 直接落到默认实现。
     *
     * <p>⛔ 旧注释（作废，逐字留档）：「三个槽<b>阶段 1 只收物品、不参与计算</b>（规格 §7.1），
     * tooltip 里如实写明，不假装有用。」
     * <p>🔴 2026-10-03 起<b>整条作废</b>：这 3 格现在真的参与计算（判据核 {@link ShanhaiHeatGate}），
     * 旧的"阶段 1"文案是<b>假话</b>，已整段换掉 —— 本项目禁止留着与事实不符的活文案。
     */
    @Override
    public void attachSideTabs(TabsWidget tabs) {
        super.attachSideTabs(tabs);
        tabs.attachSubTab(new ExtraMountPageProvider());
    }

    /** 「额外挂载」子页：3 个槽（布局照抄上游 {@code ExtraMountPageProvider}）。 */
    private final class ExtraMountPageProvider implements IFancyUIProvider {
        @Override
        public Component getTitle() {
            return Component.literal("§b额外挂载");
        }

        @Override
        public IGuiTexture getTabIcon() {
            return new ItemStackTexture(Items.HOPPER);
        }

        @Override
        public Widget createMainPage(FancyMachineUIWidget widget) {
            WidgetGroup group = new WidgetGroup(0, 0, 126, 78);
            group.setBackground(GuiTextures.BACKGROUND_INVERSE);
            group.addWidget(new LabelWidget(8, 8, () -> "额外挂载槽"));
            for (int i = 0; i < EXTRA_MOUNT_SLOT_COUNT; i++) {
                SlotWidget slot = new SlotWidget(extraMountSlots.storage, i,
                        8 + i * 34, 30, true, true);
                slot.setBackground(SlotWidget.ITEM_SLOT_TEXTURE);
                slot.setHoverTooltips(shanhai$extraMountTooltips(i));
                group.addWidget(slot);
            }
            group.addWidget(new LabelWidget(8, 58, () -> "满足配方的额外要求（见各格说明）"));
            return group;
        }
    }

    /**
     * 必须返回<b>主机机器类</b>本身（全限定名 {@code com.shanhai.common.machine.PrimordialOmegaEngineMachine}）。
     *
     * <p>gtlcore 的 {@code isValidHost} 逻辑是 {@code getHostType().isInstance(candidate)}，
     * 填成 definition / 方块类 / Block 会让它<b>恒 false 且不抛异常</b> —— 模块永远回连不上，
     * 日志里什么也看不到。这是本类最脆弱的一行。
     */
    @NotNull
    @Override
    public Class<PrimordialOmegaEngineMachine> getHostType() {
        return PrimordialOmegaEngineMachine.class;
    }

    /**
     * 候选主机位：helper 的 16 个 ∪ UP/DOWN × 4 层的 8 个 = 24 个（去重后）。
     *
     * <p>每调用实时计算，不缓存（朝向、坐标都会变）。
     */
    @NotNull
    @Override
    public BlockPos[] getHostScanPositions() {
        BlockPos pos = getPos();
        Direction facing = getFrontFacing();

        Set<BlockPos> candidates = new LinkedHashSet<>();
        BlockPos[] reference = GtlAddCompat.candidateHosts(pos, facing);
        for (BlockPos p : reference) {
            if (p != null) {
                candidates.add(p);
            }
        }
        candidates.addAll(verticalFacingCandidates(pos, facing));
        return candidates.toArray(new BlockPos[0]);
    }

    /**
     * 兜底候选：把 helper 漏掉的 {@code UP/DOWN} 两个朝向按<b>同一套公式</b>补出来。
     *
     * <p>公式照抄 {@code AntichristPosHelper.calculatePossibleHostPositions} 的语义
     * （已用 `javap -c` 逐条确认）：
     * <pre>
     * layerCenter = modulePos - moduleFacing.getNormal() * 14
     * for hostFacing in {UP, DOWN}:
     *     backward = hostFacing.getOpposite().getNormal()
     *     for layer in 0..3:  hostPos = layerCenter - backward * (13 + 12*layer)
     * </pre>
     * 只增不改：helper 原始的 16 个候选一个不动。
     *
     * <p>🔴 <b>这 8 个不是冗余</b>（复算方式：按 {@code javap} 字节码语义把 helper 的两个函数
     * 重写为纯算术后对拍，主机 = {@code (63,1,14)}、槽位由 host 侧公式算出）：
     * <pre>
     * 主机 facing=NORTH：模块在槽位 (77,1,27) 且朝 EAST
     *     helper 16 个候选        → ✅ 命中主机
     * 主机 facing=UP：模块在槽位 (77,-12,14) 且朝 EAST
     *     helper 16 个候选        → ❌ 找不到主机（UP/DOWN 分支退化）
     *     并上竖直 8 个（本方法）  → ✅ 命中主机
     * </pre>
     * 即：<b>竖直朝向的主机只能靠这 8 个兜底候选连上</b>；去掉它们会让「主机朝上」这种摆放静默失联。
     */
    @NotNull
    private static List<BlockPos> verticalFacingCandidates(BlockPos modulePos, Direction moduleFacing) {
        Vec3i normal = moduleFacing.getNormal();
        BlockPos layerCenter = modulePos.offset(-normal.getX() * SIDE_OFFSET,
                -normal.getY() * SIDE_OFFSET,
                -normal.getZ() * SIDE_OFFSET);
        List<BlockPos> out = new ArrayList<>(2 * LAYER_COUNT);
        for (Direction hostFacing : new Direction[]{Direction.UP, Direction.DOWN}) {
            Vec3i backward = hostFacing.getOpposite().getNormal();
            for (int layer = 0; layer < LAYER_COUNT; layer++) {
                int depth = BASE_DEPTH + layer * LAYER_DEPTH;
                out.add(layerCenter.offset(-backward.getX() * depth,
                        -backward.getY() * depth,
                        -backward.getZ() * depth));
            }
        }
        return out;
    }

    @Nullable
    @Override
    public BlockPos getHostPosition() {
        return hostPosition;
    }

    @Override
    public void setHostPosition(@Nullable BlockPos pos) {
        this.hostPosition = pos;
    }

    @Nullable
    @Override
    public PrimordialOmegaEngineMachine getHost() {
        return host;
    }

    @Override
    public void setHost(@Nullable PrimordialOmegaEngineMachine host) {
        this.host = host;
    }

    // ═════════════════════════════ 2. 生命周期 ═════════════════════════════

    @Override
    public void onStructureFormed() {
        super.onStructureFormed();
        // 🔴 2026-09-25（路线 ①）：并行槽的扫描【挂在基类】而不是 onModuleFormed 钩子里 ——
        //    子类覆写钩子时不可能把它漏掉（当年就是"只有核心那一台自己订阅了"）。
        startMatterSlotScan();
        if (!findAndConnectToHost()) {
            // 🔴 刻意不用 removeFromHost()：它会 setHostPosition(null)，把落盘坐标抹掉。
            // 这里只断引用，保留 hostPosition，让「本次没找到」不等于「以后都不试」。
            PrimordialOmegaEngineMachine old = getHost();
            if (old != null) {
                old.removeModule(this);
            }
            setHost(null);
        }
        if (getHost() == null) {
            startReconnectTicker();
        } else {
            stopReconnectTicker();
        }
        onModuleFormed();
    }

    @Override
    public void onStructureInvalid() {
        super.onStructureInvalid();
        stopMatterSlotScan();
        stopReconnectTicker();
        disconnectFromHost();
        onModuleInvalidated();
    }

    @Override
    public void onPartUnload() {
        super.onPartUnload();
        stopMatterSlotScan();
        stopReconnectTicker();
        disconnectFromHost();
        onModuleInvalidated();
    }

    /**
     * 🔴 <b>模块方块被挖掉时，槽里的物品必须掉到世界上（2026-09 用户报的 bug）。</b>
     *
     * <p>用户原话：「<b>模块和主机被挖掉的时候并不会掉落里面的物品（物质模块）</b>」。
     * 模块这一侧丢失的是三个槽：{@link #matterModuleSlot}（1 格，物质模块）、
     * {@link #threadBoostSlot}（1 格）、{@link #extraMountSlots}（3 格，玩家从 GUI 第三页放的），
     * 共 <b>5 格</b>；破坏方块时它们随机器一起消失。
     *
     * <h2>上游原文（照抄，不自造）</h2>
     * <b>① 本类此前【收不到】这个回调</b> —— GTCEu 的唯一派发点是
     * {@code com.gregtechceu.gtceu.api.block.MetaMachineBlock#m_6810_}（= 原版 {@code Block#onRemove}），
     * {@code javap -p -c} 逐字：
     * <pre>
     *   29: instanceof    #186    // class com/gregtechceu/gtceu/api/machine/feature/IMachineLife
     *   32: ifeq          49
     *   44: invokeinterface #480,  1   // InterfaceMethod …feature/IMachineLife.onMachineRemoved:()V
     * </pre>
     * 而本类的基类链
     * （{@code MetaMachine → MultiblockControllerMachine → WorkableMultiblockMachine →
     * WorkableElectricMultiblockMachine}）<b>四者都不实现 {@code IMachineLife}</b>
     * （逐个 {@code javap} 的类头逐字核对）⇒ <b>光写 {@code @Override} 是不够的，
     * 必须在类头补上 {@code implements IMachineLife}</b>（类声明处已补；这也是主机侧
     * {@code PrimordialOmegaEngineMachine} 早就写着的写法）。
     * <p>对照上游同类基类：{@code com.gtladd.gtladditions.api.machine.wireless.
     * GTLAddWirelessWorkableElectricMultipleRecipesMachine} 的类头<b>就带着 {@code IMachineLife}</b>
     * —— 所以伪神之锻炉那台模块能收到回调，本工程的多方块基类收不到。
     *
     * <p><b>② 掉落工具</b> —— {@code MetaMachine#clearInventory(IItemTransfer)}（bytecode 见主机侧
     * {@code PrimordialOmegaEngineMachine#onMachineRemoved} 的 javadoc），内部是
     * 「取出 → 清空槽 → {@code onContentsChanged()} → {@code Block.popResource(level, pos, stack)}」。
     * <b>不手搓 {@code ItemEntity}</b>。
     *
     * <p><b>③ 调用形状</b> —— GTCEu {@code SimpleTieredMachine#onMachineRemoved()} 反编译逐字：
     * <pre>
     *    1: invokespecial #366   // Method …WorkableTieredMachine.onMachineRemoved:()V     ← 先 super
     *    9: invokevirtual #370   // Method clearInventory:(…IItemTransfer;)V               ← 再逐容器清空
     * </pre>
     * 多个容器就写多条（同 {@code WorkableTieredMachine#onMachineRemoved()} 连写
     * {@code clearInventory(importItems.storage)} + {@code clearInventory(exportItems.storage)} 的形状）。
     * 传 {@code .storage} 的口径同 {@code WorkableTieredMachine} / {@code ObjectHolderMachine}
     * （它们的 bytecode 里都是先 {@code getfield …NotifiableItemStackHandler.storage:…ItemStackTransfer;}
     * 再 {@code invokevirtual clearInventory}）。
     *
     * <h2>覆盖范围</h2>
     * 全部 <b>24</b> 台模块都继承本类（{@code ModuleRegistry.SPECS} 24 条 =
     * {@code PrimordialMatterRecombinatorCore::new} ×1 + {@code StandardPrimordialModule::new} ×23，
     * 而后者的类头是 {@code extends PrimordialModuleMachine}）⇒ <b>改基类一处，24 台全覆盖</b>。
     *
     * <h2>🔴 为什么不在这里补上游那句 {@code removeFromHost(host)} —— 专项核查结论：<b>不缺（甲）</b></h2>
     * 上游 {@code ForgeOfTheAntichristModuleBase#onMachineRemoved()} 只有一句 {@code removeFromHost(host)}
     * （偏移 0–11：{@code getfield host → removeFromHost(IModularMachineHost) → return}）。
     * <b>本类没有照抄，因为模块方块被移除时，主机侧【已经】会把它从 {@code modules} 里摘掉</b>——
     * 但那条链是<b>间接</b>的（不靠本类自己注销）。2026-09 专项核查，逐条字节码证据：
     *
     * <h3>① 主路径（2026-09-22 恢复后重新成立）：挖模块 ⇒ 主机结构失效 ⇒ {@code safeClearModules()}</h3>
     * 模块方块坐在主机的 <b>J 位</b>（谓词 J = 空槽占位 ∪ 模块控制器方块）⇒ 挖掉它 = 那一格变空气
     * ⇒ <b>主机结构失效</b> ⇒ {@code MultiblockState#onBlockStateChanged} 里
     * {@code checkPatternWithLock()} 为 false ⇒ {@code controller.onStructureInvalid()}
     * ⇒ 主机 {@code PrimordialOmegaEngineMachine#onStructureInvalid()} ⇒ {@code safeClearModules()}
     * ⇒ 逐模块 {@code module.removeFromHost(this)} ⇒ gtlcore 默认实现
     * {@code setHostPosition(null); setHost(null); if (host != null) host.removeModule(this); onDisconnected();}
     * ⇒ <b>{@code modules.remove(module)}</b>。
     * <p>🔴 这条链的前提是「<b>J 位每格都必须有合法方块</b>」。它<b>曾于 2026-09-22 短暂失效</b>
     * —— 那一版把 J 谓词放宽成允许空气（<b>同日被用户撤回，从未部署、从未进游戏</b>，全过程见文末作废留档）。
     * <hr>
     * <p>（以下为核查时逐条记下的旁证）
     *
     * <h3>② 框架事实（与 J 谓词的严格程度<b>无关</b>，2026-09-22 查实 · 保留）</h3>
     * 就算 pattern <b>仍然匹配</b>（主机保持成型），模块也会被摘掉 —— 走的是
     * {@code MultiblockState#onBlockStateChanged} 的<b>另一条分支</b>。
     * 🔴 <b>这条是"框架行为"，不是"我们现在的清理链"</b>：本机当前的<b>主路径是 ①</b>（谓词严格 ⇒ 失效链）。
     * 它覆盖的是"那一格被换成了另一种 J 合法方块 / 模块自己失型"之类<b>主机不会失效</b>的场景，
     * 即 <b>2026-09-20 方案 A</b>（{@code reconcileModules()}）的既有语义。
     * 失效/成型两种判定就在同一段字节码里（{@code javap -p -c MultiblockState}，一般分支偏移 84–219）：
     * <pre>
     *   95: aload 4
     *   97: invokeinterface #296  // IMultiController.isFormed:()Z
     *  102: ifeq          148     ← isFormed()==false ⇒ 照样跳到 148 去做 checkPattern
     *  105: aload_2
     *  106: invokevirtual #275  // BlockState.getBlock:()
     *  109: instanceof    #298  // class com/gregtechceu/gtceu/api/block/ActiveBlock
     *  112: ifeq          148     ← 🔴 新方块【不是 ActiveBlock】（例如空气）⇒ 照样跳到 148
     *  115..144: vaBlocks.contains(pos)
     *  144: ifeq          148
     *  147: return               ← 唯一提前返回：isFormed && 新方块是 ActiveBlock && pos∈vaBlocks
     *  148: checkPatternWithLock()
     *  155: ifeq          182     ← false ⇒ onStructureInvalid()（182–199）← 本机主路径走这条
     *  174: onStructureFormed()   ← true  ⇒ 重新触发成型回调 ⇒ reconcile 路径（就是本条）
     * </pre>
     * ⇒ <b>「已经成型」不会跳过这条分支</b>（只有"新方块是 GT 的 {@code ActiveBlock} 且该位置在
     * {@code vaBlocks} 里"才会提前 return；空气不是 {@code ActiveBlock}）。pattern 仍然匹配时：
     * ⇒ 走 {@code onStructureFormed()} ⇒ 主机 {@code onStructureFormed()} ⇒ {@code reconcileModules()}
     * ⇒ {@code isModuleStillValid()} 里 {@code MetaMachine.getMachine(level, pos) == module} 判 false
     * （那一格已经不是它）⇒ {@code module.removeFromHost(this)}
     * ⇒ gtlcore 默认实现内层 {@code host.removeModule(this)} ⇒ <b>{@code modules.remove(module)}</b>。
     * <p><b>这条路径下其余模块也不受牵连</b>：{@code reconcileModules()} 对仍然有效的走 {@code kept++}
     * （<b>不</b>打 {@code module_disconnected}），随后 {@code scanAndConnectModules()} 只是幂等补连
     * （{@code connectToHost} 里 {@code old == host} ⇒ 不摘除，只 {@code Set.add} 再刷一次 +
     * {@code onConnected} → 本类的 {@code onConnected} 只做 {@code stopReconnectTicker()} + 两个空钩子）。
     *
     * <h3>上游/框架侧三条不变式（① 与 ② 都依赖它们）</h3>
     * <ol>
     *   <li><b>模块方块就坐在主机的 J 位上</b>：主机 16 槽位 = {@code GtlAddCompat.moduleSlots(...)}；
     *       两个连接入口都只认这 16 位 —— 主机侧 {@code IModularMachineHost#scanAndConnectModules()} 逐
     *       {@code getModuleScanPositions()} 取方块再 {@code connectToHost}（字节码偏移 12–94），
     *       模块侧可达的 {@code GtlAddCompat.candidateHosts} 是同一 helper 的<b>互逆函数</b>。</li>
     *   <li><b>通知链（与谓词无关的那一段，未变）</b>：gtlcore
     *       {@code MultiblockWorldSavedDataMixin#addMapping} 把主机 state 注册进 {@code chunkPosMapping}
     *       下<b>它 pattern cache 覆盖的每一个区块</b>（逐 cache 位置 {@code computeIfAbsent(new ChunkPos(pos))}）
     *       ⇒ gtlcore {@code mixin/mc/LevelMixin#gtceu$updateChunkMultiBlocks} 在区块方块变更时
     *       {@code getControllersInChunk(chunkPos)} → 对 {@code isFormed()} 且 {@code isPosInCache(pos)}
     *       的主机 → {@code server.execute(() -> state.onBlockStateChanged(pos, newState))}。</li>
     *   <li><b>「主机被挖」与「模块被挖」互不干扰</b>：两条路径都是"谁被移除谁触发"。主机被挖时
     *       走 {@code safeClearModules()} 清空集合，<b>但不会触发各模块自己的 {@code onMachineRemoved()}</b>
     *       （它们的方块没被移除）⇒ 不会重复摘除、也不会替别人掉物品；模块被挖时只有那个模块的
     *       {@code onMachineRemoved()} 触发（只掉它自己的槽），主机侧由上面的成型-调和链摘掉它。
     *       重复执行幂等：{@code Set.add}/{@code Set.remove} 天然幂等，且 gtlcore 的
     *       {@code removeFromHost} 内层带 {@code if (host != null)} 守卫（传 null 安全）。</li>
     * </ol>
     *
     * <hr>
     * <p>⛔ <b>【2026-09-22 作废 · 本轮 air 版原文照留，一字未改】</b>
     * （这一版 javadoc 存在了不到一天：当时 J 谓词被放宽为允许空气，于是"失效链"暂时不再是主路径，② 被写成了主路径）
     * <pre>
     *   🔴 2026-09-22 订正：摘除链已经换了一条（旧链因谓词放宽而失效）
     *   同一条任务里用户裁决 A「拆一个模块不该停全机」⇒ 主机 J 谓词已放宽为
     *   空气 ∪ 占位方块 ∪ 模块控制器方块（ShanhaiMachines.moduleSlotPredicate()）。
     *   旧链（"挖模块 ⇒ 主机结构失效 ⇒ safeClearModules()"）因此不再被走到；
     *   现在走的是 MultiblockState#onBlockStateChanged 的另一条分支。
     *   …… 放宽谓词后 pattern 仍然匹配 ⇒ 走 onStructureFormed() ⇒ 主机 onStructureFormed()
     *   ⇒ reconcileModules() ⇒ isModuleStillValid() 里 MetaMachine.getMachine(level, pos) == module
     *   判 false（那一格已是空气、不再有机器）⇒ module.removeFromHost(this) ⇒ …
     *   ⇒ modules.remove(module)。
     *   其余 15 台不受影响：reconcileModules() 对它们走 kept++（不打 module_disconnected）……
     * </pre>
     * <b>作废原因</b>：用户在 2026-09-22 <b>同一天撤回</b>了那个放宽
     * （原话「<b>我后悔了，我返回之前那个替换或者拆除模块就停止全机的决定</b>」）
     * ⇒ J 谓词恢复为不含 {@code Predicates.air()} ⇒ <b>主路径重新变回 ①（失效链）</b>，
     * 上面 ② 于是从"当前主路径"降级为"框架事实"。
     * 🔴 <b>那次放宽从未部署、从未进游戏</b>（那一轮只跑到 {@code compileJava}）。
     * <b>保留原文的意义</b>：让后人看得出"这里曾短暂换过一次清理机制，以及它为什么被换回"，
     * 同时把<b>"谓词严格程度 ↔ 主清理链"这对联动</b>记在案上。
     *
     * <h3>诚实边界（这条结论唯一没覆盖的一格）</h3>
     * 第 1 条的前提是"模块一定连在 16 槽位上"。本类<b>自加</b>的 8 个竖直兜底候选
     * （{@link #verticalFacingCandidates}）是唯一可能打破它的路径：它按
     * {@code hostFacing ∈ {UP, DOWN}} 的公式探位置，而 {@code isValidHost} <b>不检查被测主机的朝向</b>
     * ⇒ 理论上存在"模块不在 J 位却连上了"的构造。
     * <b>但它不构成残留</b>：一旦主机再成型，{@code reconcileModules()} 就会把它当无效摘掉
     * （那正是 2026-09-20 方案 A 的明确语义）。
     * <p>⚠️ <b>[推断] 该构造在游戏里的可达性我未实测</b>：主机注册为
     * {@code RotationState.NON_Y_AXIS}（{@code ShanhaiMachines.java:162}），其谓词是
     * {@code direction.getAxis() != Axis.Y}（{@code javap -c RotationState}：{@code NON_Y_AXIS} 在
     * {@code <clinit>} 偏移 89–110 构造、绑 {@code InvokeDynamic #3} = {@code lambda$static$3}，
     * 体为 {@code getAxis() == Y ? false : true}）⇒ <b>主机永不 UP/DOWN 朝向</b>
     * ⇒ 那 8 个候选对"能正常摆放的主机"永远打不中。
     * <p>🔴 <b>⇒ 本类刻意不加 {@code removeFromHost(getHost())}</b>（甲：不缺这一步）。
     * 若日后真在游戏里观测到残留，再按上游原文补 —— 但必须防住 {@code setHostPosition(null)}
     * 抹坐标那个已知副作用（用本类已有的 {@code disconnectFromHost()} 语义，不要直接抄
     * {@code removeFromHost}）。
     */
    @Override
    public void onMachineRemoved() {
        IMachineLife.super.onMachineRemoved();
        // ───── 取证探针 [SHANHAI-SLOT-WATCH]（2026-09-26）· 严格只读，零行为改动 ─────
        // 先数、后清：清完就数不到了。数一下不改"掉什么、掉多少"，clearInventory 那三行原样不动。
        final int droppedMatter = shanhai$countSlot(matterModuleSlot);
        final int droppedThread = shanhai$countSlot(threadBoostSlot);
        final int droppedExtra = shanhai$countSlot(extraMountSlots);
        // ⛔ 2026-10-03：原本这里还要数/清「恒星热力槽」—— 那一格已按用户规格②删除。
        //    现在线圈与恒星热力容器都放在【额外挂载槽】里，上面那条 droppedExtra 已经覆盖
        //    （漏掉它的后果就是"拆掉机器时那 64 个线圈直接蒸发且不报错"，本项目最忌讳的静默丢东西）。
        // 三条与 GTCEu WorkableTieredMachine 同形：逐容器 clearInventory。
        // 幂等：clearInventory 是"取出即清空"，重复调用时槽已空 ⇒ 不会翻倍。
        clearInventory(matterModuleSlot.storage);
        clearInventory(threadBoostSlot.storage);
        clearInventory(extraMountSlots.storage);
        // 打印放在清空【之后】：此刻这个坐标的【新】方块状态已经写进区块
        // （字节码实证见 ModuleSlotWatch 类注释 §4）⇒ 探针打出来的 B 才是实测值。
        final boolean counted = droppedMatter >= 0 && droppedThread >= 0 && droppedExtra >= 0;
        ModuleSlotWatch.onModuleRemoved(getLevel(), getPos(),
                counted ? droppedMatter + droppedThread + droppedExtra : -1,
                "物质模块槽=" + droppedMatter + " 线程槽=" + droppedThread
                        + " 外加槽=" + droppedExtra);
    }

    /** 数一个槽里现在有几件物品（只读；给 {@code [SHANHAI-SLOT-WATCH]} 取证探针用）。 */
    private static int shanhai$countSlot(@NotNull NotifiableItemStackHandler handler) {
        int total = 0;
        try {
            for (int i = 0; i < handler.getSlots(); i++) {
                final ItemStack stack = handler.getStackInSlot(i);
                if (stack != null && !stack.isEmpty()) {
                    total += stack.getCount();
                }
            }
        } catch (Throwable t) {
            // 🔴 这里抛异常会连带把紧后面的 clearInventory 一起跳过 = 真的丢物品（行为改动）。
            //    所以本计数函数自己吞掉一切异常；异常时如实报"计数失败"。
            ShanhaiMod.LOGGER.error("{} 掉落计数失败（已按 -1 上报，掉落行为本身不受影响）：{}",
                    ModuleSlotWatch.PREFIX, t.toString());
            return -1;
        }
        return total;
    }

    /** 子类钩子：结构成型后的初始化（订阅节拍等）。 */
    protected void onModuleFormed() {}

    /** 子类钩子：结构失效 / 卸载后的清理（退订节拍等）。 */
    protected void onModuleInvalidated() {}

    /** 断开连接，但<b>保留</b> {@code hostPosition}（见类注释 §3）。 */
    protected void disconnectFromHost() {
        PrimordialOmegaEngineMachine old = getHost();
        if (old != null) {
            old.removeModule(this);
            setHost(null);
            onDisconnected();
        }
    }

    private void startReconnectTicker() {
        reconnectSubs = subscribeServerTick(reconnectSubs, this::tickReconnect);
    }

    private void stopReconnectTicker() {
        if (reconnectSubs != null) {
            reconnectSubs.unsubscribe();
            reconnectSubs = null;
        }
    }

    /** 未连上主机时的 20 tick 重试（规格 §3.3 第 1 路）。 */
    private void tickReconnect() {
        if (!isFormed() || isConnectedToHost()) {
            stopReconnectTicker();
            return;
        }
        if (getOffsetTimer() % RECONNECT_INTERVAL != 0) {
            return;
        }
        findAndConnectToHost();
    }

    /** 立即重连一次，但按 {@link #RECONNECT_INTERVAL} 限频（规格 §3.3 第 2 路兜底）。 */
    protected void tryReconnectLimited() {
        if (isConnectedToHost()) {
            return;
        }
        long now = getOffsetTimer();
        if (!allowReconnectNow(now)) {
            return;
        }
        findAndConnectToHost();
    }

    private boolean allowReconnectNow(long now) {
        if (now - lastReconnectAttempt < RECONNECT_INTERVAL) {
            return false;
        }
        lastReconnectAttempt = now;
        return true;
    }

    /** 连接成功回调：清掉按 tick 缓存（子类可扩展）。 */
    @Override
    public void onConnected(@NotNull PrimordialOmegaEngineMachine host) {
        stopReconnectTicker();
        onHostConnected(host);
    }

    /** 断开回调。 */
    @Override
    public void onDisconnected() {
        onHostDisconnected();
    }

    protected void onHostConnected(PrimordialOmegaEngineMachine host) {}

    protected void onHostDisconnected() {}

    // ═════════ 2.9 发电模块：产出走【无线网（BigInteger）】—— 2026-09-26 用户拍板（甲） ═════════

    /**
     * 🔴 <b>发电那台（全 24 台里唯一 {@code getDefinition().isGenerator()}）的无线网归属 UUID</b>
     * —— 也就是 gtmthings 全局能源池（{@code GlobalVariableStorage.GlobalEnergy}）的键。
     *
     * <h2>为什么是这条路线（用户 2026-09-26 原话）</h2>
     * <blockquote><b>「我宇宙之心就是这样搞得，都可以正常工作」</b></blockquote>
     * ⇒ 电进无线网（大整数），不走 GT 的 long 电网。私货同款先例：
     * {@code PrimordialOmegaVoidInductionArmature.onWorking()}（原文 :220-237）
     * <pre>
     *   WirelessEnergyManager.addEUToGlobalEnergyMap(targetUuid, getGeneratedEuPerTick(), this);
     * </pre>
     *
     * <h2>🔴 为什么必须这样（用户那两个实测数逐位对得上）</h2>
     * <pre>
     *   空槽：2^31 × 64 × 18       = 2 473 901 162 496   = 2.4739e12（显示 2.47T / A = 1152 = 64×18）✅
     *   满配：2^31 × 2147483647 × 18 = 8.3010348293e19 ＞ Long.MAX_VALUE(9.223e18) ⇒ 被窄化压回 9.22E ✅
     *   ⇒ 只要电还走 GT 的 long 电网，>9.22e18 EU/t【物理上不存在】；只有 BigInteger 池装得下。
     * </pre>
     */
    @Persisted
    @Nullable
    private UUID shanhai$wirelessUuid;

    /**
     * ⚠️ 2026-10-01（用户点单「日志的问题」）：<b>本字段已不再是任何判定的输入</b> ——
     * 「入池并排」那一行的节流改由 {@link ShanhaiLogThrottle.Gate} 负责（Δ==Y 且接受 ⇒ 一律不打）。
     * 保留声明是照本工程「改判时旧文不删、只加注」的惯例：老日志里那 128 行是它节流出来的，
     * 读旧日志时仍然需要知道它当时的口径是「值变了就打、否则每 5 秒打一次」。
     */
    @Nullable
    private BigInteger shanhai$lastLoggedProduction;

    /** 「未绑定」只响亮报一次，避免每 tick 刷屏。 */
    private boolean shanhai$wirelessUnboundLogged;

    /** 「配方电已清零」每个配方只报一次（换配方会再报一次）。 */
    @Nullable
    private ResourceLocation shanhai$suppressedRecipeId;

    /**
     * 本台发电机的无线网归属：<b>① 自己（放置者）</b> → <b>② 结构里已绑定的「无线电网终端」部件</b>
     * → {@code null}（= 未绑定）。
     *
     * <p>两个来源都不是新发明：①照 gtladditions
     * {@code GTLAddWirelessWorkableElectricMultipleRecipesMachine#onMachinePlaced}（{@code this.uuid = player.getUUID()}）；
     * ②那个部件的 {@code getUUID()} 本来就是"数据棍右键绑定/变更所有者"的产物
     * （{@code WirelessEnergyNetworkTerminalPartMachineBase#getUuid/setUuid}），
     * 且它自己写池子时用的就是同一个键（同基类 {@code changeEnergy} → {@code addEUToGlobalEnergyMap}）
     * ⇒ <b>和"无线电网输出终端"停在同一个池子里，不是两个池子。</b>
     */
    @Nullable
    private UUID shanhai$wirelessPoolUuid() {
        if (shanhai$wirelessUuid != null) {
            return shanhai$wirelessUuid;
        }
        for (IMultiPart part : getParts()) {
            var self = part.self();
            if (self instanceof WirelessEnergyNetworkTerminalPartMachineBase terminal) {
                final UUID bound = terminal.getUUID();
                if (bound != null) {
                    return bound;
                }
            }
        }
        return null;
    }

    /**
     * 放置即绑定（照 gtladditions 同形）。<b>只有发电那台会记</b>，其余 23 台这一行都不会执行
     * ⇒ 它们不写新字段、不加任何运行期行为。
     */
    @Override
    public void onMachinePlaced(@Nullable LivingEntity player, @NotNull ItemStack stack) {
        IMachineLife.super.onMachinePlaced(player, stack);
        if (player != null && shanhai$wirelessUuid == null && getDefinition().isGenerator()) {
            shanhai$wirelessUuid = player.getUUID();
            ShanhaiMod.LOGGER.info("{} 发电模块已绑定无线网归属（放置者）：uuid={} pos={}",
                    PrimordialGeneratorProduction.TAG, shanhai$wirelessUuid, getPos());
        }
    }

    /**
     * 🔴 <b>把发电那台配方自带的电<u>就地清零</u></b>—— 保证"同一度电只出一次"。
     *
     * <h2>不改这一步会怎样（这是本路线最容易做错的一格）</h2>
     * 配方 tickOutputs 上那个 {@code 2^31 × p × 18} 会被 GT 逐 tick 送进动力仓（dynamo）；
     * 若同时再从无线网入池，<b>同一度电出两次</b>（而且动力仓那一次仍是被 Long.MAX 压平的值
     * ⇒ 玩家看到的老数 9.22E 也还在，等于没修）。
     *
     * <h2>为什么在 {@code beforeWorking} 里清零是【安全】的（已用字节码核实顺序）</h2>
     * <pre>
     *   RecipeLogic.setupRecipe(recipe):
     *     :7-12   machine.beforeWorking(recipe)      ← 本钩子在这里，拿到的就是下面那个同一个实例
     *     :51-56  handleRecipeIO(recipe, IO.IN)      ← 扣输入（在本钩子之后）
     *     :94-96  lastRecipe = recipe                ← 不复制，就是同一个实例
     *   ⇒ 在 beforeWorking 里改 tickOutputs，之后逐 tick 的 handleTickRecipe(lastRecipe) 就看不到那度电了；
     *     而【匹配/判定早已通过】（本钩子在 setupRecipe 内部，晚于 checkMatchedRecipeAvailable 的匹配）
     *     ⇒ 配方照跑、输入照扣、产物照出，只是电不再从动力仓走。
     * </pre>
     * ⚠️ <b>未绑定无线网归属时本方法<u>直接返回</u></b>（不清零、不入池）⇒ 那台机器的行为与今天<b>逐字相同</b>，
     * 不存在"改完反而不能发电"的中间态。
     */
    private void shanhai$suppressRecipeElectricity(@NotNull GTRecipe recipe) {
        if (!getDefinition().isGenerator()) {
            return;
        }
        if (shanhai$wirelessPoolUuid() == null) {
            return;
        }
        final long before = RecipeHelper.getOutputEUt(recipe);
        if (before == 0L) {
            return;
        }
        RecipeHelper.setOutputEUt(recipe, 0L);
        if (shanhai$suppressedRecipeId == null || !shanhai$suppressedRecipeId.equals(recipe.id)) {
            shanhai$suppressedRecipeId = recipe.id;
            ShanhaiMod.LOGGER.info("{} 已接管发电配方 {} 的电：配方自带 EUt={}（= 会被 Long.MAX 压平的那个值）"
                            + "在本钩子里就地清零，改由无线网入池（每 tick 的产出见同前缀的入池行）。pos={}",
                    PrimordialGeneratorProduction.TAG, recipe.id, before, getPos());
        }
    }

    /**
     * 每个工作 tick 把本台产出放进 gtmthings 无线网池（照私货
     * {@code PrimordialOmegaVoidInductionArmature#onWorking} :221-237 同形）。
     *
     * <p>为什么是 {@code onWorking()}：GTCEu {@code RecipeLogic.handleRecipeWorking} 的字节码里，
     * 逐 tick 处理成功后 {@code invokeinterface IRecipeLogicMachine.onWorking:()Z}
     * —— 也就是"这一 tick 真的在工作"的那一个点，与私货那台取的完全是同一个点。
     */
    @Override
    public boolean onWorking() {
        final boolean working = super.onWorking();
        if (working) {
            shanhai$depositGenerationToWirelessPool();
        }
        return working;
    }

    private void shanhai$depositGenerationToWirelessPool() {
        if (isRemote() || !getDefinition().isGenerator()) {
            return;
        }
        try {
            final UUID uuid = shanhai$wirelessPoolUuid();
            if (uuid == null) {
                if (!shanhai$wirelessUnboundLogged) {
                    shanhai$wirelessUnboundLogged = true;
                    ShanhaiMod.LOGGER.warn("{} 发电模块【未绑定无线网归属】⇒ 保持原行为（配方自带的电照旧走动力仓，"
                                    + "仍会被 Long.MAX 压顶）。绑定方式：① 手持闪存右键本结构里的「无线电网终端」部件；"
                                    + "② 或拆下重放本模块（放置者自动绑定）。pos={}",
                            PrimordialGeneratorProduction.TAG, getPos());
                }
                return;
            }
            final RecipeLogic logic = getRecipeLogic();
            final GTRecipe last = logic.getLastRecipe();
            if (last == null) {
                return;
            }
            // 🔴 2026-09-26 追加（用户报"批处理下不守恒"的取证）：
            //    读【当前工作配方】自带的那度电 —— 周期起点已被清零（见 shanhai$suppressRecipeElectricity），
            //    所以这里**应当读到 0**。若读到非 0 ⇒ 有别的环节把配方重新装配/缩放回来了
            //    （批处理 / 子 tick 并行化都会重新装配配方）⇒ 那度电会经动力仓再走一遍
            //    （而且极可能已被 Long.MAX 压平）⇒ 池子比料多。读到非 0 时：① 立刻再清零（兜底，
            //    保证同一度电只出一次）② 打一行响亮日志（这就是"差在哪一步"）。
            final long recipeEuNow = RecipeHelper.getOutputEUt(last);
            if (recipeEuNow != 0L) {
                RecipeHelper.setOutputEUt(last, 0L);
                ShanhaiMod.LOGGER.error("{} 配方自带电【被复原】：读回 tickOutputs.eu={}（应为 0）；已就地再次清零，"
                                + "读回={} ⇒ 若不拦，动力仓会把它也送出去（同一度电出两次）pos={}",
                        PrimordialGeneratorProduction.TAG, recipeEuNow, RecipeHelper.getOutputEUt(last), getPos());
            }
            final GTRecipe origin = logic.getLastOriginRecipe();
            // 基础 EUt 取【配方定义实例】上的 tickOutputs （见 PrimordialGeneratorProduction 的 javadoc：
            // 拿处理后的副本会读到已被 ×p×倍率、而且可能已饱和的值 ⇒ 那就不是"配方自己的产出"了）。
            final long baseEut = RecipeHelper.getOutputEUt(origin != null ? origin : last);
            // 🔴 2026-09-26 修正（用户报"批处理下不守恒"的根因）：
            //    IGTRecipe.getRealParallels() 是【整批总份数】，不是【每 tick 并行】。
            //    依据（gtlcore-1.2.3.2 BatchProcessing.scaleRecipe 字节码，两条同时成立）：
            //      · putfield duration = 原 duration × batchSize
            //      · IGTRecipe.setRealParallels(原 realParallels × batchSize)   （lmul）
            //    紧接着 apply() 把同一个 batchSize 写到同一张配方上（setBatchSize(ilmul 的那个 int)）
            //    ⇒ 【每 tick 并行 = getRealParallels() / getBatchSize()】；不做这一步，批处理下每 tick 多投 batchSize 倍。
            //    ⚠️ 非批处理时 BatchProcessing 仍会 setBatchSize(1)（apply 里无论是否触发批处理都会写这一项）
            //       ⇒ 除 1 = 恒等 ⇒ 正常情况一个数都不变（这是本修正的"不影响正常"证明的那条）。
            //    ⚠️ 整除性：realParallels 在 BatchProcessing 里只由"原值 × batchSize"这一个乘法产生
            //       ⇒ 余数恒为 0，这里的除法无损、不需要任何取整策略。
            //       万一出现可预见路径之外的余数 ⇒ 走 Java 的向下取整（floor）：宁可少算，
            //       也绝不凭空多产电 —— 本次事故的方向就是"多产"，纠偏必须朝保守侧。
            final long batchSize = Math.max(1L, ((IGTRecipe) last).getBatchSize());
            // 原值只取一次：既用来算 p_eff，也原样进日志 —— 让那一行能自证
            // 「p_raw ÷ batchSize = p_eff」（下面 parallel 的算式与上一版逐字等价）。
            final long realParallels = ((IGTRecipe) last).getRealParallels();
            final long parallel = Math.max(1L, realParallels / batchSize);
            final PrimordialOmegaEngineMachine host = getHost();
            final int gateBonus = host == null ? 0 : host.moduleSlotBonus();
            final BigInteger production = PrimordialGeneratorProduction.perTick(baseEut, parallel, gateBonus);
            if (production.signum() <= 0) {
                return;
            }
            final BigInteger before = WirelessEnergyManager.getUserEU(uuid);
            final boolean accepted = WirelessEnergyManager.addEUToGlobalEnergyMap(uuid, production, this);
            final BigInteger after = WirelessEnergyManager.getUserEU(uuid);
            shanhai$auditMine = shanhai$auditMine.add(production);
            shanhai$auditDeposits++;
            shanhai$logGeneration(uuid, baseEut, parallel, gateBonus, production, before, after, accepted,
                    realParallels, batchSize);
            shanhai$auditPool(uuid, after);
        } catch (Throwable t) {
            // 探针/入池出问题不许连坐配方（与 PrimordialRecipeEffects 里那两条探针同纪律）。
            ShanhaiMod.LOGGER.error("{} 入池异常（已忽略，配方不受影响）：{}", PrimordialGeneratorProduction.TAG,
                    t.toString());
        }
    }

    /** 池审计的累计量（每 20 tick 结算一次）与上一次的池值。 */
    private BigInteger shanhai$auditMine = BigInteger.ZERO;
    private int shanhai$auditDeposits;
    @Nullable
    private BigInteger shanhai$auditLastPool;

    /**
     * 🔴 <b>池审计那一行的闸门（2026-10-01 用户点单「日志的问题」）。</b>
     *
     * <p>实测本行打了 <b>631 次</b>，而差额<b>恒为负</b>（631/631），绝对值只有两种量级
     * （{@code -|1e18} 与 {@code -|1e17}）。
     * <p>🔴 <b>2026-10-01 用户拍板</b>：这一档（差额 &lt; 0 且 |差额| ≤ 1e19）= <b>已知带</b>
     * ⇒ <b>静默（一行都不打）</b>；差额 ≥ 0，或 |差额| &gt; 1e19 ⇒ <b>当场 WARN</b>。
     * 判定本体在 {@link ShanhaiLogThrottle#decidePoolAudit}（纯 JDK；离线可单跑自检）。
     */
    private final ShanhaiLogThrottle.Gate shanhai$poolAuditGate = new ShanhaiLogThrottle.Gate();

    /**
     * 🔴 <b>池审计（2026-09-26，用户报"批处理下不守恒"专用）</b>：每 20 tick（= 1 秒）结算一次，
     * 把 <b>"池子这一段涨了多少"</b> 与 <b>"我这台自己入账了多少"</b> 并排算出差额。
     *
     * <h2>为什么必须有它（队长那两条读数之差就是这么算错的）</h2>
     * gtmthings 的池子是<b>按队伍（team uuid）共享</b>的 ⇒ 同一 uuid 下<b>任何</b>机器的产出都进同一个数。
     * 只看"池子两条读数之差"会把<b>别人的产出</b>算到本机头上。
     * ⇒ 本行给的是可判定的对账：{@code Δ总 − 我这台 Σ = 差额}。
     *
     * <h2>🔴 2026-10-01 改了什么（用户点单「日志的问题」，含用户当天拍板）</h2>
     * <pre>
     *   原来：每 20 tick 无条件打一行（实测 631 行 / 13 分钟）
     *   现在：差额落在「已知带」（**差额 &lt; 0 且 |差额| ≤ 1e19**）⇒ **静默：一行都不打**
     *         （静默次数不丢，随下一条真正落盘的 WARN 行一起报出）。
     *         差额 ≥ 0（正 或 零），或 |差额| &gt; 1e19 ⇒ **当场 WARN**。
     * </pre>
     * ⚠️ 参考读数（用户实测 631 行全量）：差额<b>恒为负</b>（631/631，{@code Δ总 < 我这台 Σ}），
     * 绝对值落在 {@code [6.27e17, 1.66e18]} ⇒ 按新口径**改后落盘 0 行**（作者原先的实现是 2 行）。
     * 1e19 比实测上限 1.66e18 宽约一档；并且用户口径把 <b>0 也划进异常档</b>。
     * <p>🔴 <b>哪一侧才是真异常，仍待用户确认</b>：老注释写的是「差额 = 别的写者」（= 别的写者在<b>加</b>），
     * 而实测方向是<b>反的</b> —— {@code Δ总} 比本机投递的<b>少</b>。
     * 本事后注释只陈述实测，不再替用户断言"别的写者是谁"。
     */
    private void shanhai$auditPool(@NotNull UUID uuid, @NotNull BigInteger poolNow) {
        if (getOffsetTimer() % 20L != 0L) {
            return;
        }
        if (shanhai$auditLastPool != null) {
            final BigInteger deltaTotal = poolNow.subtract(shanhai$auditLastPool);
            final BigInteger others = deltaTotal.subtract(shanhai$auditMine);
            final ShanhaiLogThrottle.Verdict v = ShanhaiLogThrottle.decidePoolAudit(
                    shanhai$poolAuditGate, others, System.currentTimeMillis());
            if (v.level != ShanhaiLogThrottle.Level.NONE) {
                // 🔴 用户 2026-10-01 拍板后，已知带 = 静默 ⇒ 走到这里只可能是「不在已知带内」。
                //    （下面那个 INFO 分支特意留着：口径若翻回「形态首次打一次」，它立刻就会被走到。）
                final String tail = "【本行已改为「同一形态只打一次」：自上次落盘以来同形重复 " + v.suppressed + " 次已静默】"
                        + "⚠️ 差额符号的方向判据【仍待用户确认】（见 ShanhaiLogThrottle.POOL_DIFF_KNOWN_LIMIT 的注释）；"
                        + "pos=" + getPos();
                if (v.abnormal) {
                    // 🔴 不在已知带内 ⇒ 当场 WARN（不许被去重吃掉）
                    ShanhaiMod.LOGGER.warn("{} 🔴 池审计差额【超出已知带】{}：Δ总={} ／ 我这台 Σ={}（{} 次入池）"
                                    + "／ 差额={}；已知带 = 差额 < 0 且 |差额| ≤ {}"
                                    + "（实测 631/631 恒为负、量级 1e17~1e18；按用户口径差额 ≥ 0 也算异常）。{}",
                            PrimordialGeneratorProduction.TAG, v.shape, deltaTotal, shanhai$auditMine,
                            shanhai$auditDeposits, others, ShanhaiLogThrottle.POOL_DIFF_KNOWN_LIMIT, tail);
                } else {
                    ShanhaiMod.LOGGER.info("{} 池审计（最近 20 tick）：Δ总={} ／ 我这台 Σ={}（{} 次入池）／ 差额={}{}",
                            PrimordialGeneratorProduction.TAG, deltaTotal, shanhai$auditMine,
                            shanhai$auditDeposits, others, tail);
                }
            }
        }
        shanhai$auditLastPool = poolNow;
        shanhai$auditMine = BigInteger.ZERO;
        shanhai$auditDeposits = 0;
    }

    /**
     * 🔴 <b>入池并排探针那一行的闸门（2026-10-01 用户点单「日志的问题」）。</b>
     *
     * <p>实测本行打了 <b>128 次</b>，而 128/128 全部是 {@code Δ==Y ? true；接受=true}
     * ⇒ 改后 <b>0 行</b>（入池落账 = 正常）。只有「Δ != Y」或「接受 = false」才当场 WARN。
     */
    private final ShanhaiLogThrottle.Gate shanhai$depositGate = new ShanhaiLogThrottle.Gate();

    /**
     * 入池探针（**并排格式**：探针算的 ／ 实际传的 ／ 池读数差）。
     *
     * <p>三个数的含义（缺一不可，否则又会得出"探针与实现不一致"的假结论）：
     * <ul>
     *   <li>{@code X=探针算的}：{@code 基础EUt × p ÷ 耗能系数}（2026-09-25 起；改前是 {@code × N3倍率}）
     *       —— 即本类要入池的数量；</li>
     *   <li>{@code Y=实际传的}：**真的**交给 {@code addEUToGlobalEnergyMap} 的那个 BigInteger；</li>
     *   <li>{@code Δ=B-A}：调用前后各读一次池子得到的**实测**增量 ⇒ {@code Δ == Y} 才叫"入池落账"。</li>
     * </ul>
     * ⚠️ {@code X} 与 {@code Y} 在本实现里是同一个变量（本来就是同一个数），
     * 真正独立的**只有 Δ** ⇒ 用户/队长要判"守恒不守恒"，看的是 {@code Δ == Y}。
     *
     * <h2>🔴 2026-10-01 改了什么（用户点单「日志的问题」）</h2>
     * <pre>
     *   原来：值变了就逐 tick 打，否则每 100 tick 打一行（实测 128 行）
     *   现在：Δ==Y 且 接受=true（= 入池落账，正常）⇒ **一行都不打**，只累计计数器；
     *         其余 ⇒ **当场 WARN**（含此前的静默次数），同形每 5 秒最多复述一次。
     * </pre>
     */
    private void shanhai$logGeneration(@NotNull UUID uuid, long baseEut, long parallel, int gateBonus,
                                       @NotNull BigInteger production, @NotNull BigInteger before,
                                       @NotNull BigInteger after, boolean accepted,
                                       long realParallels, long batchSize) {
        final BigInteger delta = after.subtract(before);
        final boolean aboveLongMax = production.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0;
        // 🔴 判定本体在 ShanhaiLogThrottle（纯 JDK；离线可单跑自检）。
        final ShanhaiLogThrottle.Verdict v = ShanhaiLogThrottle.decideDeposit(
                shanhai$depositGate, delta.equals(production), accepted, aboveLongMax, System.currentTimeMillis());
        if (v.level == ShanhaiLogThrottle.Level.NONE) {
            return;
        }
        final boolean hitLongMaxTop = aboveLongMax;
        ShanhaiMod.LOGGER.warn("{} 🔴 入池并排【未落账】：X=探针算的 {}（基础EUt={} × p_raw={} ÷ batchSize={}"
                        + " = p_eff={} ÷ 耗能系数{} = ×{}）／ Y=实际传的 {}（撞 Long.MAX 顶={}）"
                        + "／ 池 {} → {}（Δ={}；Δ==Y ? {}；接受={}）；归属={} pos={}。"
                        + "自上次落盘以来同形重复 {} 次已静默。",
                PrimordialGeneratorProduction.TAG, production, baseEut, realParallels, batchSize, parallel,
                PrimordialGeneratorProduction.generationDivisor(gateBonus),
                String.format(java.util.Locale.ROOT, "%.2f",
                        PrimordialGeneratorProduction.generationGain(gateBonus)), production,
                hitLongMaxTop, before, after, delta, delta.equals(production), accepted, uuid, getPos(),
                v.suppressed);
    }

    // ═════════════════════════════ 3. 工作门控（规格 §3.3 MUST） ═════════════════════════════

    /** {@code isFormed() && host != null && host.isFormed()}。 */
    public boolean canWork() {
        PrimordialOmegaEngineMachine h = getHost();
        return isFormed() && h != null && h.isFormed();
    }

    /**
     * 配方逻辑的工作判定路径：主机不在线时先兜底重连一次，仍不在线就<b>拒绝开工</b>
     * （既不跑配方、也不静默空转）。
     *
     * <p>这里<b>不</b>用 {@code getRecipeLogic().setWaiting(...)} 来表达原因 —— 已用字节码确认
     * {@code RecipeLogic.setupRecipe()} 的流程是：
     * {@code if (!machine.beforeWorking(recipe)) { setStatus(Status.IDLE); progress=0; duration=0; isActive=false; return; }}
     * 也就是紧接着就把状态改写成 IDLE，WAITING 文案会被立刻覆盖。
     * 「为什么不动」改由 {@link #addDisplayText(List)} + {@link #getHostConditionDiagnosis()} 呈现。
     *
     * <p>返回 false 不会让这条配方进 {@code lastFailedMatches} 黑名单（同一段字节码里没有那条
     * {@code add}），所以主机回来后会自然恢复，不需要额外的清缓存逻辑。
     */
    @Override
    public boolean beforeWorking(@NotNull GTRecipe recipe) {
        if (!canWork()) {
            tryReconnectLimited();
            if (!canWork()) {
                shanhai$reportRefusedStart(recipe, "主机不在线（canWork() == false，重连后仍为 false）");
                return false;
            }
        }
        final boolean allowed = super.beforeWorking(recipe);
        if (!allowed) {
            // 🔴 走到这里 = 部件级 {@code IMultiPart.beforeWorking} 或 definition 级谓词拒了。
            //    引擎路径【此时材料已经被扣过】（扣料点在 getRecipe() 内部，严格早于本钩子）
            //    ⇒ 必须响亮报出来，否则这是"静默丢料"。
            shanhai$reportRefusedStart(recipe, "部件/definition 级 beforeWorking 拒绝（门控看不见这类）");
        } else {
            // 🔴 2026-09-26：发电那台 —— 把配方自带的电就地清零（电改从无线网走，见 §2.9）。
            //    只对 isGenerator() 那台生效，且必须已经绑定无线网归属 ⇒ 其余 23 台 / 未绑定的发电台
            //    一个字节都不变。
            shanhai$suppressRecipeElectricity(recipe);
        }
        return allowed;
    }

    /** 「拒绝开工，但引擎已在本钩子之前扣过料」的累计次数（只用于让丢料可核对）。 */
    private long shanhai$refusedStartCount;

    /**
     * 🔴 <b>丢料探针</b>：把"引擎先扣料、本钩子后拒绝"这件事变成一条可 grep 的对账日志。
     *
     * <h2>为什么需要它（差异 1 的诚实边界）</h2>
     * 已知的 false 来源里，{@code checkBeforeWorking} 的门控只能挡住"主机不在线"这一类；
     * <b>部件级</b>（{@code IMultiPart.beforeWorking}）与 <b>definition 级</b>
     * （{@code MachineDefinition.getBeforeWorking()}，签名带 recipe ⇒ 门控阶段配方还不存在）
     * 这两类<b>原理上挡不住</b>。它们今天恒为 true（已逐类核过），但将来一旦被触动，
     * 症状就是"材料凭空消失、日志什么都没有"——所以这里把它变成一行 ERROR。
     *
     * <p>口径：{@code recipe} 是引擎装配出的成品（<b>inputs 已被扣空</b>），所以这里打的是
     * <b>能耗当量</b>（{@code getInputEUt × duration}）与模块自己的并行上限，
     * 不是"被扣了几件"（那个数在扣料点本地，本钩子拿不到）。
     *
     * <p>⛔ <b>【2026-09-26 订正 · 旧句照留】</b>上面那句
     * 「那个数在扣料点本地，本钩子拿不到」<b>作为"结论"已经不成立</b>（作为"当时为什么这么写"仍然是真的）：
     * 本钩子确实<b>事后</b>拿不到"扣了几件"，但可以在<b>扣料之前</b>先存一份输入仓 / 流体仓摘要，再在这里取第二份、
     * 逐键对差 —— <b>差值就是这一批被扣掉的件数</b>。见 {@link #shanhai$lossProbeBeforeDeduction()}
     * 与 {@link #shanhai$scanInputs()}，调用点是
     * {@code PrimordialModuleRecipeLogic#checkBeforeWorking()}（<b>结构性早于扣料</b>，字节码实证见该处注释）。
     * <h2>🔴 订正前它为什么"完全不可信"（本工程的血账，留档）</h2>
     * 订正前那一句「⇒ 这一批材料已丢失」是<b>无条件字符串</b>：它<b>从不检查到底扣没扣</b>
     * ⇒ 累计 23 条"丢失"报警里<b>一条都不可采信</b>（而且它还在 {@code getInputEUt} 抛异常时
     * <b>静默 return</b>，连那一行都没有）。
     * <p>⇒ 现在强制三态：<b>delta 非空</b>才允许说「已丢失」；<b>delta 空</b>降为 WARN 并改口径；
     * <b>没有扣前快照</b>时如实写「无法判定」——<b>不许把"没测"写成"没扣"</b>。
     * <p>并且补了<b>正面对照</b>（合成样本 + 实机样本两半）：
     * 没有对照时，探针的两种结论在日志上仍然长得一样。
     */
    private void shanhai$reportRefusedStart(@NotNull GTRecipe recipe, @NotNull String why) {
        shanhai$refusedStartCount++;
        final Map<String, Long> before = shanhai$preDeductSnapshot;
        final long snapshotTick = shanhai$preDeductSnapshotTick;
        final Map<String, Long> after = shanhai$scanInputs();
        final Map<String, Long> delta = before == null ? null : shanhai$delta(before, after);

        // EUt 那一截仍然单独兜底：读不到就写"读取失败"，但【绝不】再因此整行静默 return。
        // ⚠️ 这里不能用 final：blank final 在 try 里赋值后再在 catch 里赋值会被 javac 判
        //    「variable might already have been assigned」。
        String eutText;
        try {
            final long eut = RecipeHelper.getInputEUt(recipe);
            eutText = eut + "（能耗当量=" + (eut * (long) recipe.duration) + "）";
        } catch (Throwable t) {
            eutText = "读取失败（" + t + "）";
        }
        final String common = "duration=" + recipe.duration + " / EUt=" + eutText
                + " / 本机并行上限=" + parallelCap() + " / 累计拒绝次数=" + shanhai$refusedStartCount
                + " / 快照tick=" + (before == null ? "无" : String.valueOf(snapshotTick))
                + " / 本tick=" + getOffsetTimer() + " / pos=" + getPos();

        if (delta == null) {
            ShanhaiMod.LOGGER.warn("[SHANHAI-LOSS-CHECK] 无扣前快照 ⇒ 【无法判定】本批扣没扣料（不报「已丢失」）。"
                    + "拒绝原因（{}）；扣后={}；{}", why, shanhai$fmt(after, SHANHAI_LOSS_FMT_CAP), common);
            return;
        }
        if (delta.isEmpty()) {
            if (before.isEmpty()) {
                ShanhaiMod.LOGGER.warn("[SHANHAI-LOSS-CHECK] 扣前快照为空（0 条目）⇒ 【无法判定】本批扣没扣料 —— "
                                + "这【不是】「本批未扣料」：扫描器根本没看到输入仓，零变化在这里没有信息量。"
                                + "拒绝原因（{}）；扣后={}；{}",
                        why, shanhai$fmt(after, SHANHAI_LOSS_FMT_CAP), common);
            } else {
                ShanhaiMod.LOGGER.warn("[SHANHAI-LOSS-CHECK] 输入仓零变化（本批未扣料）：拒绝开工（{}）；"
                                + "扣前={}；扣后={}；delta={}；扣前 {} 条目（非空 ⇒ 扫描器确实看得到输入仓，"
                                + "本行「零变化」有信息量）；{}",
                        why, shanhai$fmt(before, SHANHAI_LOSS_FMT_CAP), shanhai$fmt(after, SHANHAI_LOSS_FMT_CAP),
                        shanhai$fmt(delta, SHANHAI_LOSS_FMT_CAP), before.size(), common);
            }
            return;
        }
        ShanhaiMod.LOGGER.error("[SHANHAI-LOSS] 拒绝开工（{}），且【引擎已在 beforeWorking 之前扣过料】"
                        + "⇒ 这一批材料已丢失。扣前={}；扣后={}；delta={}（delta = 扣前 − 扣后，正数 = 被扣掉）；证据：{}",
                why, shanhai$fmt(before, SHANHAI_LOSS_FMT_CAP), shanhai$fmt(after, SHANHAI_LOSS_FMT_CAP),
                shanhai$fmt(delta, SHANHAI_LOSS_FMT_CAP), common);
    }

    // ═════════════════ 丢料探针的【可判化】三件套（2026-09-26） ═════════════════
    //
    // 订正前：那句「这一批材料已丢失」是无条件字符串（见 shanhai$reportRefusedStart 的 javadoc）。
    // 现在三步：① 放行点存扣前快照 → ② 拒绝点并排扣前/扣后/delta → ③ 正面对照（合成 + 实机）。
    // 🔴 全程只读：只走 getCapabilitiesProxy().row(IO.IN / IO.BOTH) 读内容，
    //    不碰槽、不调任何会扣料的引擎 API、不改任何数值。

    /** 摘要表打印上限（只截断【日志文本】，不截断对差本身）。 */
    private static final int SHANHAI_LOSS_FMT_CAP = 40;
    /** 实机正面对照的尝试轮数上限（防止"这台机永远不扣料"时白跑）。 */
    private static final int SHANHAI_LOSS_LIVE_CONTROL_MAX_TRIES = 50;

    /** 扣料【前】的输入仓 / 流体仓摘要（键 = {@code item:<id>} / {@code fluid:<id>}）。 */
    @Nullable
    private Map<String, Long> shanhai$preDeductSnapshot;
    /** 上面那份快照是在哪个 {@code getOffsetTimer()} 取的（判"快照与本次拒绝是不是同一批"）。 */
    private long shanhai$preDeductSnapshotTick = Long.MIN_VALUE;
    /** 合成样本正面对照闸门：只跑一次。 */
    private boolean shanhai$lossProbeSelfTested;
    /** 实机正面对照闸门：只打一次（跨机器共享，避免 24 台各打一遍）。 */
    private static boolean shanhai$lossProbeLiveControlDone;
    /** 实机正面对照已尝试轮数（同上，跨机器共享）。 */
    private static int shanhai$lossProbeLiveControlTries;
    /** 实机正面对照"放弃"那条 WARN 的闸门：只打一次。 */
    private static boolean shanhai$lossProbeLiveControlGaveUp;

    /**
     * 🔴 <b>丢料探针 ① — 放行点</b>：在【扣料之前】存一份输入仓 / 流体仓摘要。
     *
     * <p>由 {@code PrimordialModuleRecipeLogic#checkBeforeWorking()} 调用。
     * 「这个调用点早于扣料」<b>不是推断，是字节码实证</b>：
     * <pre>
     *   MutableRecipesLogic.getRecipe() 偏移 0–8：
     *       0: aload_0
     *       1: invokevirtual  // checkBeforeWorking:()Z
     *       4: ifne 9
     *       7: aconst_null ; 8: areturn          ← 门控拒了就直接 null，后面一步都不跑
     *   紧接着 9–13: calculateParallels()          ← 扣料点在里面
     *   （RecipeCalculationHelper 里 12 处
     *    {@code RecipeRunnerHelper.handleRecipeInput(IRecipeLogicMachine, GTRecipe)} 调用）
     *   最后 50–55: buildFinalNormalRecipe(...) → areturn
     *   而 setupRecipe(...)（= machine.beforeWorking 所在）在 findAndHandleRecipe / onMultipleRecipeFinish 里，
     *   【在 getRecipe() 返回之后】才被调。
     * </pre>
     * ⇒ 放行点(checkBeforeWorking) 早于 扣料 早于 拒绝点(beforeWorking)。三段全是实证。
     *
     * <p>本方法只读、不改任何槽；出任何异常都降级成"没有快照"（那会让拒绝点报"无法判定"，
     * <b>而不是</b>误报"已丢失"），且绝不连坐配方。
     */
    public void shanhai$lossProbeBeforeDeduction() {
        try {
            shanhai$lossProbeSelfTest();
            shanhai$preDeductSnapshot = shanhai$scanInputs();
            shanhai$preDeductSnapshotTick = getOffsetTimer();
        } catch (Throwable t) {
            shanhai$preDeductSnapshot = null;
            ShanhaiMod.LOGGER.error("[SHANHAI-LOSS-CHECK] 扣前快照失败（本批降级为【无法判定】，配方不受影响）：{}",
                    t.toString());
        }
    }

    /**
     * 🔴 <b>丢料探针 ③ — 正面对照（实机那一半）</b>：在【引擎已经扣过料之后】再取一次摘要。
     *
     * <p>由 {@code PrimordialModuleRecipeLogic#buildFinalNormalRecipe()} 在 {@code super} 返回后调用
     * （此刻扣料点 {@code calculateParallelsWithGreedyAllocation} 已经跑完）。
     * 只要真的读到变化，就证明<b>"扫描器确实看得到输入仓"</b> ⇒ 它报的「零变化」才有信息量。
     *
     * <p>⚠️ <b>诚实边界</b>：它只在真的读到变化时打一行（全部 24 台共享一次）。
     * 想在"这台机已经不再扣料"时硬造变化是不行的 —— 所以试满
     * {@link #SHANHAI_LOSS_LIVE_CONTROL_MAX_TRIES} 轮仍没读到，就打一条 WARN
     * <b>明说"实机那一半对照没拿到"</b>，<b>不许静默当作通过了</b>。
     */
    public void shanhai$lossProbeLiveControl() {
        if (shanhai$lossProbeLiveControlDone || shanhai$lossProbeLiveControlGaveUp) {
            return;
        }
        try {
            final Map<String, Long> before = shanhai$preDeductSnapshot;
            if (before == null || before.isEmpty()) {
                return; // 连扣前摘要都没有 ⇒ 这一轮说明不了任何事，不计数
            }
            final Map<String, Long> after = shanhai$scanInputs();
            final Map<String, Long> delta = shanhai$delta(before, after);
            if (delta.isEmpty()) {
                if (++shanhai$lossProbeLiveControlTries >= SHANHAI_LOSS_LIVE_CONTROL_MAX_TRIES
                        && !shanhai$lossProbeLiveControlGaveUp) {
                    shanhai$lossProbeLiveControlGaveUp = true;
                    ShanhaiMod.LOGGER.warn("[SHANHAI-LOSS-CHECK] 实机正面对照未拿到：试了 {} 轮引擎装配，"
                                    + "一次都没在【扣料后】读到输入仓变化 ⇒ 本探针的正面对照只有【合成样本】那一半，"
                                    + "【实机那一半没有】。读到这条就该知道：它对真实输入报的「零变化」尚未被实机验证过。",
                            SHANHAI_LOSS_LIVE_CONTROL_MAX_TRIES);
                }
                return;
            }
            shanhai$lossProbeLiveControlDone = true;
            ShanhaiMod.LOGGER.info("[SHANHAI-LOSS-CHECK] 实机正面对照通过：在【引擎扣料之后】实测到输入仓变化"
                            + "（扣前 {} 条目 → 扣后 {} 条目，delta={}）⇒ 扫描器 + 对差器在真实输入上能判出「已扣料」。"
                            + "（只打一次，pos={}）",
                    before.size(), after.size(), shanhai$fmt(delta, SHANHAI_LOSS_FMT_CAP), getPos());
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("[SHANHAI-LOSS-CHECK] 实机正面对照自身异常（不影响配方）：{}", t.toString());
        }
    }

    /**
     * 扫描本机 {@code IO.IN} / {@code IO.BOTH} 侧的能力代理，汇总成 {@code {条目: 数量}} 表。
     *
     * <p>🔴 为什么用<b>能力代理</b>而不是"自己去翻输入总线"：引擎扣的就是这层里的东西
     * ⇒ 「扣前 == 扣后」只能在这同一层上比，否则比的是两套不相干的口径。
     * <p>返回 {@link TreeMap}（键有序）⇒ 两份摘要可以直接逐字 diff。
     */
    private @NotNull Map<String, Long> shanhai$scanInputs() {
        final Map<String, Long> out = new TreeMap<>();
        try {
            final Table<IO, RecipeCapability<?>, List<IRecipeHandler<?>>> proxy = getCapabilitiesProxy();
            if (proxy == null) {
                return out;
            }
            for (IO io : new IO[]{IO.IN, IO.BOTH}) {
                for (Map.Entry<RecipeCapability<?>, List<IRecipeHandler<?>>> cell : proxy.row(io).entrySet()) {
                    final List<IRecipeHandler<?>> handlers = cell.getValue();
                    if (handlers == null) {
                        continue;
                    }
                    for (IRecipeHandler<?> handler : handlers) {
                        final List<Object> contents = handler.getContents();
                        if (contents == null) {
                            continue;
                        }
                        for (Object content : contents) {
                            shanhai$accumulate(out, content);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            // 🔴 探针自己绝不许把异常抛给配方逻辑（拒绝点那条路径上没有外人兜着）。
            //    只报一次，避免每 5 tick 刷屏。
            if (!shanhai$scanInputsWarned) {
                shanhai$scanInputsWarned = true;
                ShanhaiMod.LOGGER.error("[SHANHAI-LOSS-CHECK] 输入仓扫描异常 ⇒ 本机丢料探针降级为【无法判定】"
                        + "（只报一次，配方不受影响）：{}", t.toString());
            }
        }
        return out;
    }

    /** 「输入仓扫描异常」那条 ERROR 的闸门（只报一次）。 */
    private static boolean shanhai$scanInputsWarned;

    /**
     * 把一个内容对象累加进摘要表。
     *
     * <p>⚠️ 流体有<b>两个</b> {@code FluidStack} 类型，别只认一个：GTCEu 的
     * {@code NotifiableFluidTank#getContents()} 返回的是 <b>LDLib</b> 的
     * {@code com.lowdragmc.lowdraglib.side.fluid.FluidStack}（{@code javap} 实测），
     * 而别的 handler 可能给 Forge 的 {@code net.minecraftforge.fluids.FluidStack}。
     * 两个都认 ⇒ 换 handler 实现时不会静默漏掉流体（漏掉流体 = 流体那一路永远"零变化"）。
     */
    private static void shanhai$accumulate(@NotNull Map<String, Long> out, @Nullable Object content) {
        if (content instanceof ItemStack stack) {
            if (stack.isEmpty()) {
                return;
            }
            out.merge("item:" + shanhai$idOf(ForgeRegistries.ITEMS.getKey(stack.getItem())),
                    (long) stack.getCount(), Long::sum);
        } else if (content instanceof com.lowdragmc.lowdraglib.side.fluid.FluidStack fluid) {
            if (fluid.isEmpty()) {
                return;
            }
            out.merge("fluid:" + shanhai$idOf(ForgeRegistries.FLUIDS.getKey(fluid.getFluid())),
                    fluid.getAmount(), Long::sum);
        } else if (content instanceof net.minecraftforge.fluids.FluidStack fluid) {
            if (fluid.isEmpty()) {
                return;
            }
            out.merge("fluid:" + shanhai$idOf(ForgeRegistries.FLUIDS.getKey(fluid.getFluid())),
                    (long) fluid.getAmount(), Long::sum);
        }
    }

    private static @NotNull String shanhai$idOf(@Nullable ResourceLocation id) {
        return id == null ? "unknown" : id.toString();
    }

    /**
     * 逐键对差：{@code delta = 扣前 − 扣后}（<b>正数 = 被扣掉</b>），只保留非 0 项。
     *
     * <p><b>纯函数</b> —— 正面对照直接把样本喂给它（见 {@link #shanhai$lossProbeSelfTest()}）。
     */
    static @NotNull Map<String, Long> shanhai$delta(@NotNull Map<String, Long> before,
                                                    @NotNull Map<String, Long> after) {
        final Map<String, Long> out = new TreeMap<>();
        final Set<String> keys = new TreeSet<>(before.keySet());
        keys.addAll(after.keySet());
        for (String key : keys) {
            final long b = before.getOrDefault(key, 0L);
            final long a = after.getOrDefault(key, 0L);
            if (b != a) {
                out.put(key, b - a);
            }
        }
        return out;
    }

    /** 摘要表 → 一行字面量；超过 {@code cap} 项就截断并标出总项数（防止一次日志刷出几十万字符）。 */
    static @NotNull String shanhai$fmt(@NotNull Map<String, Long> map, int cap) {
        if (map.isEmpty()) {
            return "{}";
        }
        final StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Map.Entry<String, Long> e : map.entrySet()) {
            if (n >= cap) {
                sb.append(", …（共 ").append(map.size()).append(" 项，此处只列前 ").append(cap).append(" 项）");
                break;
            }
            sb.append(n == 0 ? "" : ", ").append(e.getKey()).append('=').append(e.getValue());
            n++;
        }
        return "{" + sb + "}";
    }

    /**
     * 🔴 <b>丢料探针 ③ — 正面对照（合成样本）</b>：喂一份【已知差 1 件】的样本，必须判出"有变化"；
     * 再喂一份【逐项相同】的样本，必须判出"零变化"。只跑一次。
     *
     * <p>写法照 {@code PrimordialModuleRecipeLogic#shanhai$selfTestProbe()}（那边是
     * duration 故意差 1 tick，这边是件数故意差 1 件），判据同样是"<b>探针自己对已知为坏的样本
     * 必须报出坏</b>"。
     * <p>🔴 为什么要它（本工程的血账）：没有对照时，「这一批材料已丢失」与「本批未扣料」
     * 这两种结论在日志上<b>长得一模一样</b> —— 探针自己错了也看不出来。
     */
    private void shanhai$lossProbeSelfTest() {
        if (shanhai$lossProbeSelfTested) {
            return;
        }
        shanhai$lossProbeSelfTested = true;
        final Map<String, Long> before = new TreeMap<>();
        before.put("item:shanhai:loss_selftest_dust", 64L);
        before.put("fluid:shanhai:loss_selftest_fluid", 1000L);

        final Map<String, Long> same = new TreeMap<>(before);
        final Map<String, Long> oneLess = new TreeMap<>(before);
        oneLess.put("item:shanhai:loss_selftest_dust", 63L);   // 已知差 1 件

        final Map<String, Long> deltaSame = shanhai$delta(before, same);
        final Map<String, Long> deltaOne = shanhai$delta(before, oneLess);
        if (!deltaSame.isEmpty()) {
            throw new IllegalStateException("[SHANHAI-LOSS-CHECK] 探针正面对照失败：两份逐项相同的摘要"
                    + "被判成【有变化】（delta=" + shanhai$fmt(deltaSame, 8) + "）⇒ 它会到处误报「已丢失」。");
        }
        if (deltaOne.isEmpty()) {
            throw new IllegalStateException("[SHANHAI-LOSS-CHECK] 探针正面对照失败：两份只差 1 件的摘要"
                    + "被判成【零变化】⇒ 这个对差器在真实输入上说的「零变化（本批未扣料）」没有任何信息量。");
        }
        ShanhaiMod.LOGGER.info("[SHANHAI-LOSS-CHECK] 探针正面对照通过（合成样本）：零变化样本 → {}（空）；"
                        + "差 1 件样本 → delta={} ⇒ 两种结论确有区分度。"
                        + "（实机那一半见 shanhai$lossProbeLiveControl）",
                shanhai$fmt(deltaSame, 8), shanhai$fmt(deltaOne, 8));
    }

    /**
     * 🔴 必须常驻订阅：本系列模块的输入来自 AE 网络，AE 发料<b>不触发</b>本机 inventory 的
     * {@code onContentsChanged}；若配方逻辑空闲即取消订阅，模块会<b>永久待机</b>。
     * （上游踩过的坑，规格 §5.1 / R4。）
     */
    @Override
    public boolean keepSubscribing() {
        return true;
    }

    // ═════════════════════════════ 4. 诊断 / 显示 ═════════════════════════════

    /** 人类可读的连接诊断，用于 GUI 与「为什么不动」的排查（规格 §5.1 第 4 条）。 */
    @NotNull
    public String getHostConditionDiagnosis() {
        if (!isFormed()) {
            return "模块结构未成型";
        }
        PrimordialOmegaEngineMachine h = getHost();
        if (h == null) {
            if (hostPosition != null) {
                return "未连接主机（已记录坐标 " + hostPosition.getX() + "," + hostPosition.getY() + ","
                        + hostPosition.getZ() + "，每 " + RECONNECT_INTERVAL + " tick 重试）";
            }
            return "未连接主机（正在 24 个候选位扫描，每 " + RECONNECT_INTERVAL + " tick 重试）";
        }
        if (!h.isFormed()) {
            return "主机结构已失效，等待重新成型";
        }
        return "主机在线";
    }

    @Override
    public void addDisplayText(@NotNull List<Component> textList) {
        super.addDisplayText(textList);
        if (!isFormed()) {
            return;
        }
        // 🔴 2026-09-26（用户交办）：紧挨着 GTCEu 那行「最大功率」画出【本机能源仓的实际电压等级】。
        //    那行是"能力上限"，这一行才是"电压闸门真正用的那个值" —— 见 addEnergyTierDisplayText 的 javadoc。
        addEnergyTierDisplayText(textList);
        PrimordialOmegaEngineMachine h = getHost();
        if (h != null && h.isFormed()) {
            BlockPos p = h.getPos();
            textList.add(Component.literal("已连接主机: §a" + p.getX() + ", " + p.getY() + ", " + p.getZ()));
        } else {
            textList.add(Component.literal("已连接主机: §c未连接").withStyle(ChatFormatting.RED));
            textList.add(Component.literal(" §7" + getHostConditionDiagnosis()));
        }

        String moduleId = getMatterModuleId();
        int level = getMatterModuleLevel();
        if (moduleId == null) {
            textList.add(Component.literal("已安装模块: §7（空槽）"));
        } else {
            textList.add(Component.literal("已安装模块: §b"
                    + ShanhaiTextParser.stripStyleCode(displayNameOfMatterModule())
                    + "§7 (Lv." + level + ")"));
        }
        addSharedEffectDisplayText(textList);
        // ───── 2026-09-25（任务 A）：并行 / 跨配方线程两行（24 台统一由基类出，子类只覆写取值） ─────
        addParallelDisplayText(textList);
        addModuleDisplayText(textList);
        // ⛔ 2026-09-26（用户交办，原话逐字：「然后这一段可以删掉了，我们已经去除这个功能了」）：
        //    这里原本画 【配方最短耗时（下限）：N tick（模块耗时 = max(此下限, 原耗时 × 减免系数)；侧栏可调 10-200）】。
        //    已删除。为什么删（两条，都留档）：
        //      ① 那个控件 2026-09-22 就按用户裁决从模块侧摘掉了（本文件 attachConfigurators 现在只剩 super，
        //         LimitedDurationConfigurator 那一行是注释）⇒「侧栏可调 10-200」早已是**死说法**；
        //      ② 用户 2026-09-26 追加确认：「我们当时确实把模块的最小配方耗时给去除了」。
        //    🔴 【2026-09-26 当日升级 · 上面那段留档已作废，原文保留不改】当时写的是
        //       「删的是这一行文字，不是功能。下限本身仍在代码里、仍在生效…若用户要连功能一起删，
        //        属于**另一件**事」—— **用户当天就裁决了那"另一件事"**，原话逐字：「连功能一起删」。
        //       ⇒ 现在**功能也已删除**（代码为准）：
        //         · PrimordialRecipeEffects#applyModuleDuration 的 floor 项与 limitedDuration 形参 —— 已删
        //         · 本类的 limitedDuration 字段 + get/set + LimitedDurationAdapter —— 已删
        //         · 现行公式 = min(原时长, max(1, round(原时长 × 减免系数))) ⇒ 不再有 20 tick 下限
        //       ⇒ 于是「GUI 上不再画这一行」与「代码里不再有这件事」**终于一致**了，
        //         不再存在"文字删了、功能还在"这种界面与实现不符的状态。
    }

    /**
     * 主机口径的 N3 / N5 效果行（<b>每台模块都显示</b>）。
     *
     * <h2>为什么数字必须画在 GUI 上</h2>
     * 本项目红线：「活的界面上不许放假数据」，反过来也成立 —— <b>生效了就必须看得见</b>。
     * 倍率与减免系数都由<b>主机</b>的门控等级决定，玩家在那个小小的专属槽上做的操作
     * 会影响这台模块的产出，不给数字就只能靠猜。
     * <p>数值与 {@link com.shanhai.common.recipe.PrimordialRecipeEffects} 用同一份实现算出，
     * 所以这一行不是"另抄一份的说明文字"，它<b>就是</b>配方修饰器要乘的那个数。
     *
     * <h2>🔴 主机 vs 模块的不对称（这里显示的是【模块】吃到的效果）</h2>
     * <pre>
     *   模块：时长 ×f（再被 N6 下限夹）/ 耗能 ×f
     *   主机：耗能 ×f（同一个 f）；时长【不吃 f】（= N6 的 A 下限 max(1, dx, 下限)）
     * </pre>
     * 也就是说这一行的 {@code f} 对主机同样生效，但<b>只作用在耗电上</b>；
     * 主机的时长由 N6 的 A 下限决定（侧栏那个「配方最短耗时」只是其中一项）。完整的对照表见
     * {@code PrimordialOmegaEngineMachine#applyHostRecipeModifier} 与
     * {@code ModuleRegistry#applyModuleRecipeModifier} 两处 javadoc。
     *
     * <h2>⚠️ 发电那台是唯一的例外（2026-09-25 用户定案）</h2>
     * 它<b>不吃</b> N3 产出倍率（{@code ×18}），改吃「<b>÷ 耗能系数</b>」（{@code ÷0.05 = ×20}）
     * ⇒ 这一行对它打印的是发电倍率而不是产出倍率（两个数都取自
     * {@code PrimordialGeneratorProduction} 的同一对 helper，与入池算式同一个数）。
     */
    protected void addSharedEffectDisplayText(@NotNull List<Component> textList) {
        PrimordialOmegaEngineMachine hostMachine = getHost();
        int gateBonus = hostMachine == null ? 0 : hostMachine.moduleSlotBonus();
        int multiplier = PrimordialRecipeEffects.outputMultiplier(gateBonus);
        double factor = PrimordialRecipeEffects.reductionFactor(gateBonus);
        double ratio = 1.0D - factor;
        // 🔴 2026-09-25 用户定案（原话逐字）：「哦对了我忘记了它吃了*18倍的产出，那就不吃产出加成了，
        //    只吃这个加成，系数不是最小是0.05嘛，范围只有零点能发电机」
        //    ⇒ 【发电那台】不再吃 N3 产出倍率，改吃「÷ 耗能系数」⇒ 这里必须画它真正吃的那一档，
        //      否则就是"活的界面上放假数据"（印着一个已经不再生效的 ×18）。
        //      两个数都来自 PrimordialGeneratorProduction 的同一对 helper（÷的系数 / 等价倍率）。
        //    ⚠️ 其余 24 台【照旧】吃 ×N3，这一行不动 ⇒ 只有发电那台换口径。
        if (getDefinition().isGenerator()) {
            textList.add(Component.literal("主机加成: " + (gateBonus > 0 ? "§bLv." + gateBonus : "§7未生效")
                    + " §7· 发电倍率: §d÷" + PrimordialGeneratorProduction.generationDivisor(gateBonus)
                    + " = ×" + String.format(java.util.Locale.ROOT, "%.2f",
                            PrimordialGeneratorProduction.generationGain(gateBonus))
                    + " §7（发电不吃产出倍率 ×" + multiplier + "，改吃耗能系数的倒数 —— 用户 2026-09-25 定案）"));
        } else {
            textList.add(Component.literal("主机加成: " + (gateBonus > 0 ? "§bLv." + gateBonus : "§7未生效")
                    + " §7· 产出倍率: §d×" + multiplier));
        }
        // 🔴 2026-09-21 改正方向：这里的 factor 是【成本系数】= 1 − 减免比例，
        //    即「原耗时/原耗电 × factor」；旧口径印的是减免比例本身，导致 Lv.1 显示 0.4181
        //    而实际乘数应为 0.5819（Lv.17 ⇒ 0.05 = 用户原话「原耗电*0.05」）。
        textList.add(Component.literal("减免系数: §e" + String.format(java.util.Locale.ROOT, "%.4f", factor)
                + " §7（原耗时与原耗电各 × 此系数；减免 §e"
                + String.format(java.util.Locale.ROOT, "%.1f", ratio * 100.0D) + "%§7；"
                + "此系数取自【主机】的专属槽，非本机；"
                + "主机自己也吃耗电这一半、但它的时长不吃这个系数）"));
    }

    /**
     * GTCEu「最大功率: … EU/t（…）」那一行的翻译键。
     *
     * <p>用途：让本类的「当前能源等级」行<b>按内容</b>贴到它下面，而不是写死下标。
     * 两个产出点都用<b>同一个键</b>（两处都 javap 核对过）：
     * <ul>
     *   <li>GTCEu {@code MultiblockDisplayText$Builder#addEnergyUsageLine}（普通电机器走这条）；</li>
     *   <li>gtladditions {@code WorkableElectricMultiblockMachineMixin#redirectEnergyUsageLine}
     *       （无线机器在多配方模式下改写的那条）。</li>
     * </ul>
     * ⇒ 无论那行由谁画，本行都紧跟其后。
     */
    private static final String GT_MAX_ENERGY_LINE_KEY = "gtceu.multiblock.max_energy_per_tick";

    /**
     * <b>「当前能源等级」—— 本机能源仓的<b>实际</b>电压等级</b>（用户 2026-09-26 交办；25 台模块统一由基类出）。
     *
     * <h2>1. 为什么加这一行（用户给的口径）</h2>
     * GUI 上原本只有 GTCEu 那行「最大功率: … EU/t（MAX）」，它画的是<b>能力上限</b>
     * （模块本体支持到 MAX），玩家会误以为「现在就吃得到 MAX」。而<b>真正决定配方跑不跑得起来的是
     * 能源仓的电压等级</b>：ULV 能源仓 + LV 配方 ⇒ {@code FAIL_VOLTAGE_TIER}
     * 「配方失败原因：电压等级未达到配方要求」（用户 2026-09-26 实机）。
     * ⇒ 本行与那行<b>挨在一起</b>，「上限 vs 实际」一眼可对比；原「最大功率」那行<b>一个字都不动</b>。
     *
     * <h2>2. 🔴 取值链：与电压闸门<b>就是同一个值</b>（不是我另算一份）</h2>
     * <pre>
     *   本行      ：GTUtil.getFloorTierByVoltage(getMaxVoltage())
     *   闸门判据  ：PrimordialModuleRecipeLogic#checkRecipe → getMachine().getTier()
     *   而 getTier() 这个字段【唯一的赋值点】是 GTCEu
     *   WorkableElectricMultiblockMachine#onStructureFormed()：
     *       this.energyContainer = this.getEnergyContainer();
     *       this.tier = GTUtil.getFloorTierByVoltage(this.getMaxVoltage());   ← 逐字同一条表达式
     * </pre>
     * ⇒ 闸门读的是「成型时按这条表达式缓存下来的那份」，本行是「现算」——<b>同一条表达式、同一个输入</b>
     * （{@code getMaxVoltage()} 由能源仓算出：gtlcore 覆写版取
     * {@code energyContainer.getHighestInputVoltage()}）。
     * 用 ULV 仓时两者同为 0，LV 配方（euTier=1）在 {@code ModuleVoltageGate.allows(0,1)} 处被拦
     * —— <b>画的数就是拦人的那个数</b>。
     *
     * <h2>3. 为什么不去读 {@code getTier()} 那个字段</h2>
     * 它<b>不是同步字段</b>（GTCEu 只给 {@code isFormed} 标了 {@code @DescSynced}），且
     * {@code onStructureFormed()} 只在服务端跑（{@code asyncCheckPattern} 要求 {@code ServerLevel}）
     * ⇒ 客户端那份恒为 0。{@code getMaxVoltage()} 在 gtlcore 被覆写成「容器为 null 就地重建」
     * ⇒ 两边都算得出真值。<b>现算比读字段稳</b>。
     *
     * <h2>4. 位置：按翻译键插到「最大功率」那行<b>后面</b></h2>
     * 见 {@link #GT_MAX_ENERGY_LINE_KEY}。找不到该键时退化为「紧跟在本类自己这批行之前」
     * （即本方法被调用的那一刻的队尾），<b>不静默丢行、也不抛</b>。
     *
     * <h2>5. ⚠️ 没有能源仓时不画「ULV」</h2>
     * 判据与 GTCEu 自己那行<b>逐字相同</b>（{@code energyContainer != null && getEnergyCapacity() > 0}）：
     * 容器为空时 {@code getMaxVoltage()} 返回 0，而 {@code getFloorTierByVoltage(0)} 也是 0（ULV）
     * ⇒ 直接画就会印出一个<b>假的「ULV」</b>。宁可写「（无能源仓）」—— 本工程红线：活的界面上不许放假数据。
     *
     * <h2>6. 显示格式（用户给了两选一，这里选带电压的那种）</h2>
     * {@code 当前能源等级: ULV（8 EU/t）} —— 名字取 {@code GTValues.VNF[tier]}（GT 自带配色，
     * 与那行「最大功率」同一个表），括号里是该等级的<b>标称</b>电压 {@code GTValues.V[tier]}。
     * 超出原版 15 档（例如电压值恰好是 {@code Long.MAX_VALUE}，{@code getFloorTierByVoltage} 特判返回 30）
     * 时走 {@code MAX+n} 分支并直接画 {@code getMaxVoltage()} —— <b>绝不拿越界下标去查
     * {@code GTValues.VNF/V}（那会 ArrayIndexOutOfBounds，把 GUI 打崩）</b>。
     *
     * <h2>7. ⚠️ 诚实边界</h2>
     * 本工程<b>禁启客户端</b> ⇒ 这一行只有「取值链 + 键 + 反读」级证据，
     * <b>「GUI 上真的多出这一行、且位置紧挨着」仍需用户确认一次</b>。
     */
    protected void addEnergyTierDisplayText(@NotNull List<Component> textList) {
        // 与 gtlcore 覆写版 getMaxVoltage() 同一个解析入口：容器为空就地重建（客户端也走这条）。
        if (energyContainer == null) {
            energyContainer = getEnergyContainer();
        }
        final Component line;
        if (energyContainer == null || energyContainer.getEnergyCapacity() <= 0L) {
            // 与 GTCEu addEnergyUsageLine 的早退条件同源：没有能源仓 ⇒ 不画电压等级，避免假「ULV」。
            line = Component.literal("当前能源等级: §7（无能源仓 / 未接入）");
        } else {
            final long maxVoltage = getMaxVoltage();
            final int tier = GTUtil.getFloorTierByVoltage(maxVoltage);
            final String tierName = tier >= 0 && tier < GTValues.VNF.length
                    ? GTValues.VNF[tier]
                    : "§dMAX+" + (tier - 14);
            final String voltage = tier >= 0 && tier < GTValues.V.length
                    ? String.format(java.util.Locale.ROOT, "%,d", GTValues.V[tier])
                    : String.format(java.util.Locale.ROOT, "%,d", maxVoltage);
            line = Component.literal("当前能源等级: " + tierName + " §7（§f" + voltage + " EU/t§7）");
        }
        // 按翻译键找「最大功率」那一行，插到它后面；找不到就退化为队尾（= 本类这批行的开头）。
        for (int i = 0; i < textList.size(); i++) {
            if (textList.get(i).getContents() instanceof TranslatableContents contents
                    && GT_MAX_ENERGY_LINE_KEY.equals(contents.getKey())) {
                textList.add(i + 1, line);
                return;
            }
        }
        textList.add(line);
    }

    /**
     * <b>并行上限 + 跨配方线程数</b>两行（<b>24 台模块统一由基类出</b>）。
     *
     * <h2>为什么放在基类</h2>
     * 官方 guide 对模块的描述原文是「机器本体会显示已安装模块、<b>并行上限</b>和<b>线程倍率</b>」。
     * 在本轮之前，只有物质重组核心（{@link PrimordialMatterRecombinatorCore}）自己画了「并行上限」，
     * 另外 23 台在 GUI 上一个数字都没有。做成基类一行 ⇒ <b>24 台一起有，且只有一处实现</b>。
     *
     * <h2>取值</h2>
     * <ul>
     *   <li>并行上限 = {@link #getDisplayParallel()}——现在是<b>每台自己的真实并行槽值</b>
     *       （2026-09-25 路线 ① 之前，基类这里返回的是硬编码的 1，理由写的是"23 台跳过并行那一步"，
     *        而那句对<b>机器</b>是错的：上游 23/24 台本来就有「空槽 64 + 自己那张表」）</li>
     *   <li>跨配方线程数 = {@link #getCrossRecipeThreads()}（当前恒为
     *       {@link #CROSS_RECIPE_THREADS} = 1，用户 2026-09-25 亲定）</li>
     * </ul>
     * ⚠️ 这里画的是<b>显示值</b>，不参与运算；与物品 tooltip
     * （{@code MachineTooltips}）和 Jade 三处口径必须一致。
     */
    protected void addParallelDisplayText(@NotNull List<Component> textList) {
        textList.add(Component.literal("并行上限: §6" + formatParallel(getDisplayParallel())
                + " §7· 跨配方线程数: §b" + getCrossRecipeThreads()));
    }

    /**
     * <b>「无限」判定 —— 老山海口径的唯一一处定义</b>。
     *
     * <p>[源码原文] 老山海 {@code PrimordialOmegaEngineModuleBase#addParallelDisplay}：
     * <pre>
     *   long parallel = getCurrentParallel();
     *   boolean isInfinite = parallel >= Long.MAX_VALUE / 2;
     *   var parallelText = isInfinite ? DShanhaiTextUtil.createUltimateRainbow("无限")
     *                                 : Component.literal(String.format("%,d", parallel));
     * </pre>
     * ⇒ 阈值 {@code Long.MAX_VALUE / 2 = 4611686018427387903}（= 2^62−1，正是并行表第 15 档 wzcz3 的值）。
     * <p>三处显示（机器 GUI / Jade / 物品 tooltip）<b>都从这里取</b>，
     * 不许各自再写一遍 {@code Long.MAX_VALUE / 2}。
     */
    public static boolean isInfiniteParallel(long parallel) {
        return parallel >= Long.MAX_VALUE / 2;
    }

    /**
     * 并行上限的<b>显示文案</b>（24 台统一）。
     *
     * <p>判据逐字沿用物质重组核心<b>改前</b>那一版（{@code parallel >= Long.MAX_VALUE / 2 ⇒ 无限}）
     * ⇒ <b>对已验过的核心 GUI 是无改动</b>，只是行尾多了「 · 跨配方线程数: 1」。
     * <p>2026-09-26：阈值搬进 {@link #isInfiniteParallel(long)}（三处显示共用一份判定），
     * <b>取值与行为逐字不变</b>。
     */
    public static String formatParallel(long parallel) {
        return isInfiniteParallel(parallel) ? "无限" : String.format(java.util.Locale.ROOT, "%,d", parallel);
    }

    /**
     * <b>本模块用哪张并行值表</b>（2026-09-25 任务 A 路线 ① 的接缝）。
     *
     * <p>默认 {@link ParallelTable#STANDARD}（全 24 台里 21 台用它）。覆写只有一处：
     * {@code PrimordialMatterRecombinatorCore} → {@link ParallelTable#ENHANCED}。
     * ⛔ <b>2026-09-26 删掉了第三条覆写</b>：发电模块（{@code PRIMORDIAL_VOID_INDUCTION_ARMATURE}）
     * 原由 {@code ModuleRegistry} 声明为"无表档"（并行恒 64），现按用户裁决改为 {@link ParallelTable#STANDARD}
     * （它与其余模块同口径：空槽 64 ＋ 按物质模块提升）。旧档位 {@code BASE} 已从枚举中删除。
     *
     * <p>⚠️ 这个"哪台用哪张表"的归属是<b>逐台上游核对</b>出来的（不是按名字推的），
     * 核对方法与逐条证据见交付报告。
     */
    protected ParallelTable parallelTable() {
        return ParallelTable.STANDARD;
    }

    /**
     * <b>重扫并行槽</b>：读物质模块槽 → 查表 → 乘堆叠倍率。
     *
     * <p>语义与上游逐字一致（{@code Primordial…Module.scanBoostItem()}）：
     * <pre>
     *   base  = 表.getOrDefault(槽内物品 id, DEFAULT_PARALLEL)   ← 空槽 / 未识别 = 64
     *   value = base × (1 + count / 16)                          ← 每满 16 个翻一倍，饱和到 Long.MAX_VALUE
     * </pre>
     * <p>⚠️ 与上游的一处<b>已知差异</b>：上游 {@code applyModuleCountParallelMultiplier} 里是
     * {@code if (count <= 1) return base;}（0 个和 1 个同值），本工程的老实现 {@code applyStackMultiplier}
     * 是 {@code count <= 0 ⇒ base}（1 个也走 {@code 1 + 1/16 = 1}）。
     * <b>两者对 count ∈ {0,1,16,32,…} 的取值完全相同</b>（{@code 1+1/16 = 1}），故未改。
     */
    private void scanMatterSlot() {
        ItemStack stack = matterModuleSlot.storage.getStackInSlot(0);
        long base = DEFAULT_PARALLEL;
        switch (parallelTable()) {
            case STANDARD -> base = PARALLEL_TABLE_STANDARD.getOrDefault(itemId(stack), DEFAULT_PARALLEL);
            case ENHANCED -> base = PARALLEL_TABLE_ENHANCED.getOrDefault(itemId(stack), DEFAULT_PARALLEL);
            // ⛔ 2026-09-26：这里原有第三支 `case BASE -> base = DEFAULT_PARALLEL;`（"无表 ⇒ 恒 64"），
            //    已随 ParallelTable.BASE 一起删除。删它的意义 = 让"这台机器的并行不随物质模块变"
            //    这件事在编译期就不可能写出来（旧原文与理由见 ParallelTable 的留档）。
        }
        currentParallel = applyStackMultiplier(base, stack.getCount());
    }

    /**
     * 堆叠 ≥16 翻倍：{@code base × (1 + count/16)}，饱和到 {@code Long.MAX_VALUE}（不溢出成负数）。
     *
     * <p>2026-09-25 从 {@code PrimordialMatterRecombinatorCore} 原样上移到基类 ——
     * 数值与边界一个字符都没改（24 台现在共用同一份）。
     */
    public static long applyStackMultiplier(long base, int count) {
        if (base <= 0L || count <= 0) {
            return base;
        }
        long steps = 1L + (count / STACK_STEP);
        if (base > Long.MAX_VALUE / steps) {
            return Long.MAX_VALUE;
        }
        return base * steps;
    }

    /** 每 {@link #STACK_STEP} 个物质模块翻一倍。 */
    public static final int STACK_STEP = 16;

    /**
     * 🔴 <b>批处理按钮的可见性覆写（2026-09-26 用户实机报的回归，已修）</b>。
     *
     * <h2>根因（字节码实证）</h2>
     * 按钮由 gtlcore 挂：{@code IBatchMachine.attachBatchConfigurator(panel, machine)} 的运行期判据是
     * <pre>
     *   if (!(machine instanceof IBatchMachine batch)) return;
     *   if (!batch.canConfigureBatchProcessing()) return;        ← 按钮消失点
     *   panel.attachConfigurators(new IFancyConfiguratorButton.Toggle(...));
     * </pre>
     * 而机器上的 {@code canConfigureBatchProcessing()} 被 gtlcore 的
     * {@code gtlcore.mixin.gtm.fix.WorkableElectricMultiblockMachineMixin}（作用于本类的<b>基类</b>）
     * 改成了 {@code BatchProcessing.canConfigureBatchProcessing(this)}，其逻辑是：
     * <pre>
     *   if (!(machine.getRecipeLogic() instanceof MultipleRecipesLogic)) return true;      // 旧：模块走这支 ⇒ 显示
     *   access = MULTIPLE_RECIPE_MODE_ACCESS.get(machine.getClass());                      // 反射找 modeGetter/modeSetter
     *   if (access.modeGetter().isEmpty() || access.modeSetter().isEmpty()) return false;  // 新：模块走这支 ⇒ 隐藏
     *   …
     * </pre>
     * ⇒ 2026-09-26 把模块逻辑换成 {@code PrimordialModuleRecipeLogic}（继承 gtlcore 的
     * {@code MultipleRecipesLogic}）之后，判据从第一支掉进第二支，而本机器没有那两个反射方法
     * ⇒ <b>按钮直接不挂</b>。与用户截图（按钮消失）完全吻合。
     *
     * <p>⛔ <b>2026-09-26 订正（旧句保留，不改写）</b>：上面那句「继承 gtlcore 的
     * {@code MultipleRecipesLogic}」<b>是错的</b>。<b>javap 实测</b>的继承链是
     * {@code PrimordialModuleRecipeLogic} → {@code com.gtladd.gtladditions.api.machine.logic.MutableRecipesLogic}
     * → {@code com.gregtechceu.gtceu.api.machine.trait.RecipeLogic} —— <b>不在这条
     * {@code MultipleRecipesLogic} 链上</b> ⇒ 上面那条「掉进第二支 ⇒ 按钮不挂」的因果链
     * <b>前提不成立</b>（按该判据，模块应停在第 1 支并 {@code return true}）。
     * ⚠️ <b>「按钮当时为什么消失」本轮没有重查，因此不作结论</b>；本节下面的修法
     * （无条件 {@code return true}）本身不受影响，也不必改。
     * 📌 另：<b>同一条继承事实</b>正是抬头两行那个 bug 的根因 —— provider 的
     * {@code instanceof MultipleRecipesLogic} 为假 ⇒ 走 else 支去读 {@code RecipeMultiplierTracker}
     * （表里缺条目时兜底 {@code (1.0,1.0)}）⇒ 恒画 100%。修在
     * {@code PrimordialModuleRecipeLogic#shanhai$captureModuleReduction}（2026-09-26 新增）。
     *
     * <h2>修法：把这一档钉回"换引擎之前的取值"</h2>
     * 换引擎之前判据在第一支返回 <b>true</b> ⇒ 这里直接覆写为 {@code true}，
     * <b>与旧行为逐值相同</b>（不是"新加一个功能"）。
     * <p>⚠️ 不去反射伪造 {@code isMultipleRecipeMode/setMultipleRecipeMode}：那会让 gtlcore 的
     * {@code BatchProcessing} 走进"可切换模式"分支，而我们并不想给模块加那个开关。
     *
     * <p>⚠️ <b>诚实边界</b>：这条只保证<strong>可见</strong>。批处理在本路径上是否与换引擎前<b>同效</b>，
     * 取决于 gtlcore {@code RecipeLogicMixin.handleRecipeIO(GTRecipe, IO.IN)} 里那次
     * {@code BatchProcessing.applyInPlace(machine.self(), lastOriginRecipe, recipe)}：
     * {@code handleRecipeIO} 在引擎路径上仍被 {@code setupRecipe} 调用（继承未绕过），
     * 但引擎路径不写 {@code lastOriginRecipe}（那由 gtlcore 自己的 {@code findAndHandleRecipe} 维护）
     * ⇒ <b>未实测</b>，需要用户实机点一下这个按钮看数字变化。
     *
     * <p>⚠️ <b>这里刻意不写 {@code @Override}</b>：{@code IBatchMachine} 是 gtlcore 的 mixin 在<b>运行期</b>
     * 织进 {@code WorkableElectricMultiblockMachine} 的，本类在<b>编译期</b>并不是它的子类型
     * ⇒ 写 {@code @Override} 会直接编译失败（2026-09-26 实测：{@code 方法不会覆盖或实现超类型的方法}）。
     * 按名字+签名声明即可，运行期由 mixin 织入的接口把它当作实现。
     */
    public boolean canConfigureBatchProcessing() {
        return true;
    }

    /**
     * 🔴 <b>批处理"可点"的第二道闸门（2026-09-26 用户实机报"按钮可见但被禁用"，已修）</b>。
     *
     * <h2>根因（字节码实证）</h2>
     * 悬停提示由 {@code IBatchMachine.attachBatchConfigurator} 的
     * {@code lambda$attachBatchConfigurator$2} 产出：
     * <pre>
     *   if (!supportsBatchProcessing()) return List.of("gui.gtlcore.batch_processing.unsupported_mode");  ← 用户看到的「当前机器模式不支持」
     *   else if (enabled) …enabled else …disabled
     * </pre>
     * 而 gtlcore 的 {@code fix.WorkableElectricMultiblockMachineMixin}（作用于本类<b>基类</b>）把它覆写成：
     * <pre>
     *   supportsBatchProcessing()     = hasBaseBatchSupport() &amp;&amp; !BatchProcessing.isCrossRecipeParallel(this)
     *   canConfigureBatchProcessing() = hasBaseBatchSupport() &amp;&amp;  BatchProcessing.canConfigureBatchProcessing(this)
     * </pre>
     * 而 {@code BatchProcessing.isCrossRecipeParallel(m)} 第一句是
     * {@code if (!(m.getRecipeLogic() instanceof MultipleRecipesLogic)) return false;}
     * ⇒ 换引擎后模块逻辑是 {@code MultipleRecipesLogic} ⇒ {@code isCrossRecipeParallel} = <b>true</b>
     * ⇒ {@code supportsBatchProcessing()} = <b>false</b> ⇒ 按钮<b>挂上了但被禁用</b>。与用户所见完全吻合。
     *
     * <h2>修法：把这一档也钉回"换引擎之前的取值"</h2>
     * 换引擎之前 {@code isCrossRecipeParallel} = false ⇒ {@code supportsBatchProcessing()} = true
     * ⇒ 这里直接覆写为 {@code true}，<b>与旧行为同值</b>（不是新加功能）。
     * <p>⚠️ 同样刻意不写 {@code @Override}（{@code IBatchMachine} 由 mixin 运行期织入，编译期不是超类型）。
     */
    public boolean supportsBatchProcessing() {
        return true;
    }

    /**
     * <b>当前并行上限（供显示与配方修饰器使用）—— 玩家覆盖生效之后的那个数。</b>
     *
     * <h2>🔴 这里是"玩家可调并行"唯一的插入点（2026-09-27 用户实机提出）</h2>
     * 引擎真正读的就是本方法，而且<b>两个读点都经过它</b>（逐条核实过的调用点）：
     * <pre>
     *   ① 引擎路径：{@code PrimordialModuleRecipeLogic#calculateParallels()}
     *        → {@code PrimordialModuleMachine.totalParallelLimitFor(module.getCurrentParallel(), threads)}
     *        → 每轮 {@code calculateParallels()} 都重读一次 ⇒ 改完【立刻生效】，不用重启也不用重摆；
     *   ② 原生修饰链：{@code ModuleRegistry} 的 {@code applyParallel(modified, module,
     *        module.getRecipeLogicMaxParallel())}（{@link #getRecipeLogicMaxParallel()} 也走本方法）。
     * </pre>
     * 显示侧同样全部经由本方法（{@link #getDisplayParallel()} → GUI 的「并行上限」行、Jade 的
     * {@link #getJadeParallel()}），⇒ 不可能出现"界面显示改了、引擎没改"的静默分叉
     * （本工程红线：活的界面上不许放假数据）。
     *
     * <h2>🔴 2026-10-02 第五轮：本值现在【含 ÷T】（用户裁决 ① 与 ③ 自动在这里合流）</h2>
     * <pre>
     *   本值 = getEffectiveParallel() = applyEnergyCap(base, T)   ← T = getEnergyCapThreads()
     *        = min(本机上限, floor(电上限 ÷ T))                     ← 【每线程】口径
     *   · 引擎侧：getRecipeLogicMaxParallel() → getMaxParallel() → 父类再 × T ⇒ 总预算正确
     *   · 显示侧：GUI「并行上限」行、Jade 的 parallel 键【自动跟着变】—— 它们读的就是本方法，
     *     全工程没有任何一处另存一份数 ⇒ 用户裁决 ③「Jade 跟着改」在本工程里是
     *     **结构性自动成立**的，不需要（也不应该）去改显示那条链的代码
     * </pre>
     *
     * <p>⚠️ {@code parallelOverride} 一旦生效，{@link #getAutoParallel()} 仍然每 3 tick 跟着物质模块走
     * —— 玩家把覆盖清回 0 时立刻回到当前自动值，不需要重扫。
     */
    public long getCurrentParallel() {
        // 🔴 2026-09-27：改成委托 getEffectiveParallel()（唯一一份覆盖/天花板逻辑）。
        //    逐值对照：钳位写入口生效后，override ≤ 天花板 ⇒ 两版同值；
        //    唯一不同的档是"天花板事后变小"（玩家拆掉物质模块）—— 那时本版会跟着降到真实可达值，
        //    旧版会继续返回那个已经达不到的覆盖值（假数据）。见 getEffectiveParallel() 的说明。
        return getEffectiveParallel();
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════
    //  🔴 2026-09-26：把并行【接回 long 通道】—— 并行表的末三档（4.6e18 / 6.9e18 / Long.MAX）
    // ═══════════════════════════════════════════════════════════════════════════════════════

    /**
     * 🔴 <b>本模块的并行上限（long 形态）—— 引擎真正读的那一个。</b>
     *
     * <h2>出处：老山海逐字同源（不是本工程自创的名字）</h2>
     * <pre>
     *   [源码原文] originals/upstream/gtl_shanhai-dishanhai/.../SelectableRecipeTypeSetMachine.java:175
     *       public long getRecipeLogicMaxParallel() {
     *           return Math.max(1L, (long) getMaxParallel());
     *       }
     *   [源码原文] originals/upstream/.../PrimordialOmegaEngineModuleBase.java:520
     *       &#64;Override public long getRecipeLogicMaxParallel() {
     *           return Math.max(1L, getCurrentParallel());      ← 模块侧那一份，本方法逐字照抄
     *       }
     * </pre>
     * 老山海的引擎预算表达式（{@code SelectableRecipeTypeSetRecipeLogic.java:449}）是
     * <pre>
     *   protected long getTotalParallelLimit() {
     *       return saturatedMultiply(getMachine().getRecipeLogicMaxParallel(), getLogicThreadMultiplier());
     *   }
     * </pre>
     * ⇒ <b>它就是"并行表的 17 档原样到达引擎"的那条路</b>。本工程此前没有这一层，
     * 引擎只能从 {@link #getMaxParallel()}（{@code int}）取值 ⇒ 末三档被压成 21 亿。
     *
     * <h2>与 {@link #getMaxParallel()} 的分工（两条路并存，不是二选一）</h2>
     * <ul>
     *   <li>{@link #getRecipeLogicMaxParallel()} —— <b>long、无钳制</b>，只给引擎/预算那条路用；</li>
     *   <li>{@link #getMaxParallel()} —— <b>int 饱和桥</b>（{@code Ints.saturatedCast}），
     *       给 gtlcore 的 {@code ParallelMachine} 接口、Jade 的上游 provider、任何 legacy 读点用。
     *       它<b>物理上装不下</b> 4.6e18，所以那条路上的值就是 21 亿 —— 这是接口形状决定的，不是 bug。</li>
     * </ul>
     */
    public long getRecipeLogicMaxParallel() {
        // ⛔⛔ 2026-09-30 警示：本方法【刻意不含】跨配方线程 —— 不要在这里乘
        //   getCrossRecipeThreads()。两条理由（都是会静默出错的那一类）：
        //   ① 它同时被 getMaxParallel()（int 饱和桥）读，而引擎父类的预算表达式是
        //      `(long) getMaxParallel() * getMultipleThreads()` ⇒ 在这里乘一次、父类再乘一次
        //      = 【线程数的平方】；
        //   ② "并行上限 × 线程数"这件事有且只有一个表达式：ShanhaiParallelBudget.totalParallelLimitFor，
        //      需要它的两个调用点（引擎路径 PrimordialModuleRecipeLogic#calculateParallels、
        //      原生链 ModuleRegistry#applyModuleRecipeModifier）都显式调它。
        //   🔴 2026-10-02 第五轮补充：上面那条警示【仍然成立】，但现在这里**含有 ÷T** ——
        //      不是乘，是除，落点在 getCurrentParallel() → getEffectiveParallel() →
        //      ParallelOverrideMachine#applyEnergyCap(base, T)，T = 本类的 getEnergyCapThreads()
        //      （= getCrossRecipeThreads()）。它与上面那条"不要乘"不冲突：
        //        · 乘 T 在这里 ⇒ 父类再乘一次 ⇒ T²（错）
        //        · 除 T 在这里 ⇒ 父类乘回 T ⇒ 每线程上限 × T = 总预算（对）
        //      这就是用户 2026-10-02 第五轮裁决 ①：「把 ÷T 落到取并行上限那一层」。
        return recipeLogicMaxParallelFor(getCurrentParallel());
    }

    /**
     * {@link #getRecipeLogicMaxParallel()} 的<b>纯函数</b>形态
     * （加载期自检与逐档对照要在没有机器实例时断言它）。
     *
     * <p>取值 = {@code Math.max(1L, limit)} —— 与老山海那两处逐字同源；<b>不做任何 int 钳制</b>。
     * 下限 1 的理由与老山海一致：0/负值会让引擎的贪心分配
     * {@code if (remain <= 0L) break;} 立刻跳出 ⇒ 机器【不动、不崩、日志无输出】。
     */
    public static long recipeLogicMaxParallelFor(long currentParallel) {
        // 🔴 2026-09-30：算术本体搬到纯核 ShanhaiParallelBudget（只 import java.*）—— 理由与
        //   ShanhaiFairAllocation 那一次相同：纯核可以单独 javac 驱动做【正常/预期失败/复原】三段自证，
        //   也能在无头专服加载期真跑。这里只剩一行委托，【数值与分支一个字都没改】。
        return ShanhaiParallelBudget.recipeLogicMaxParallelFor(currentParallel);
    }

    /**
     * <b>饱和 long 乘法</b>（全工程唯一一份）。
     *
     * <p>老山海 guide 的自写红线（{@code special_index.md:113}）：
     * 「最大并行不能超过 {@code Long.MAX_VALUE}，否则会发生数值溢出」
     * ⇒ 并行预算这一路上的每个乘法<b>都必须饱和</b>，不许裸乘。
     * <p>语义：任一因子 ≤ 0 ⇒ 0（"没有预算"，引擎据此停机而<b>不是</b>得到负数）；
     * 否则溢出时返回 {@code Long.MAX_VALUE}（"无限"，正是老山海对最高档的表达）。
     */
    public static long saturatedMultiply(long a, long b) {
        // 🔴 2026-09-30：算术本体搬到纯核 ShanhaiParallelBudget（见那里的类注释与自检）。
        //   本方法保留原名原签名 ⇒ 主机侧（PrimordialEngineRecipeLogic）等既有调用点一个字都不用改，
        //   全工程仍然只有【一份】实现。
        return ShanhaiParallelBudget.saturatedMultiply(a, b);
    }

    /**
     * <b>饱和 long 加法</b>（与 {@link #saturatedMultiply} 同一族，同一条红线的另一半）。
     *
     * <p>用途：把"配方 EUt × 时长 × 并行"这类<b>多因子累加</b>写成饱和形式。
     * 与裸加在<b>不溢出时逐位相同</b>，溢出时返回 {@code Long.MAX_VALUE} 而不是回绕成负数
     * （回绕成负数的后果是"总耗能 ≈ 0"⇒ 配方被当成免费，正是本项目最忌讳的静默错数）。
     */
    public static long saturatedAdd(long a, long b) {
        final long sum = a + b;
        if (((a ^ sum) & (b ^ sum)) < 0L) {
            // 同号相加却异号收尾 ⇒ 溢出。
            return a > 0L ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
        return sum;
    }

    /**
     * <b>本模块送进引擎的并行预算</b> = {@code getRecipeLogicMaxParallel() × 跨配方线程数}（饱和）。
     *
     * <p>形状与老山海的 {@code getTotalParallelLimit()} 逐字同源（那边两个因子都是 long），
     * 而不是 gtladditions 父类的 {@code (long) getMaxParallel() * getMultipleThreads()}
     * —— 后者的第一个因子是 int，正是被压平的那一处。
     */
    public static long totalParallelLimitFor(long currentParallel, int threads) {
        // 🔴 2026-09-30：同上，委托纯核。本方法现在是【引擎路径与原生链共用】的那一个表达式 ——
        //   两处口径相同是本轮修复的目的本身（见 ShanhaiParallelBudget 的类注释）。
        return ShanhaiParallelBudget.totalParallelLimitFor(currentParallel, threads);
    }

    /**
     * 🔴 <b>多配方引擎的接口要求（{@code ParallelMachine.getMaxParallel()}）</b>。
     *
     * <p>{@code MutableRecipesLogic} 的机器类型界要求同时是
     * {@code IWirelessElectricMultiblockMachine & IThreadModifierMachine & ParallelMachine}
     * （三个接口里只有 {@code getMaxParallel()} 是抽象方法，其余全是 default）⇒ 本类补上它，
     * 让 24 台能挂 {@link PrimordialModuleRecipeLogic}。
     *
     * <p><b>返回值 = {@link #parallelCap()}</b>（与显示、与原生链吃的那个上限<b>同源</b>，
     * 不是另编一个数）。引擎里它参与 {@code totalParallel = getMaxParallel() × getMultipleThreads()}。
     *
     * <p>⚠️ <b>历史注记</b>：{@code LimitedDurationAdapter} 的注释里写着"刻意不实现
     * {@code IGTLAddMultiRecipeMachine}，因为会顺带改掉 Waila 的并行显示"。
     * 结论对、依据已被 2026-09-26 的核实推翻：上游 {@code ParallelProviderMixin} 确实会因此
     * 写 {@code getMaxParallel()}，但本工程的 {@code ShanhaiInfiniteThreadDisplayMixin}
     * 在 {@code appendServerData} 的 RETURN 把两个键<b>重写</b>成自己算的值
     * （依据 = Mixin 0.8.5 的 pass 顺序 MAIN→PREINJECT→INJECT，见该 mixin 的注释）
     * ⇒ <b>不影响玩家看到什么</b>。
     */
    @Override
    public int getMaxParallel() {
        // 🔴 2026-09-26：本方法是【int 饱和桥】—— 语义与老山海
        //   PrimordialOmegaEngineMachine#getMaxParallel() 的 `Ints.saturatedCast(MAX_PARALLEL)` 同形：
        //   能装多少装多少，装不下的部分在这里被丢掉（这是 int 接口形状决定的，不是 bug）。
        //   ★ 引擎真正读的是 getRecipeLogicMaxParallel()（long），见那里的注释。
        //   与改动前【逐值等价】：改前 = parallelCap() = (int) min(max(1,current), Integer.MAX_VALUE)，
        //   改后 = Ints.saturatedCast(max(1,current)) —— 同一函数，只是换了个名字表达。
        return Ints.saturatedCast(getRecipeLogicMaxParallel());
    }

    /**
     * <b>引擎替换点</b>：24 台模块不再用 GTCEu 原生的 {@code RecipeLogic}，
     * 改用 {@link PrimordialModuleRecipeLogic}（{@code MutableRecipesLogic} 的本工程子类）
     * —— 「跨配方线程」机制在模块侧的落点。
     *
     * <p>⚠️ 与主机同一处纪律：{@code MachineBuilder} 在<b>机器构造过程中</b>调本方法
     * （{@code WorkableMultiblockMachine} 的构造链），此刻本类的字段<b>都还没初始化</b>
     * ⇒ {@link PrimordialModuleRecipeLogic} 的构造器<b>不许读本类的任何字段</b>
     * （它只调 {@code super(machine)} 与 {@code setUseMultipleRecipes(true)}；
     * 发电特判被刻意推迟到 {@code findAndHandleRecipe()} 里懒判定）。
     */
    @Override
    protected RecipeLogic createRecipeLogic(Object... args) {
        return new PrimordialModuleRecipeLogic(this);
    }

    /**
     * 当前并行上限钳到 {@code int} 之后的<b>并行上限（供 recipeModifier 使用）</b>。
     *
     * <p>2026-09-25 从 {@code PrimordialMatterRecombinatorCore#parallelCap()} 原样上移到基类。
     * 保留 {@code Integer.MAX_VALUE} 这一档（不像主机 N4 那样取 2^30）的理由见那份原注释：
     * 真正跑多少并行仍由 GT 自己按输入量/输出空间收敛（{@code ParallelLogic}），这里给的只是「允许的上限」。
     */
    public int parallelCap() {
        return parallelCapFor(getCurrentParallel());
    }

    /** {@link #parallelCap()} 的<b>纯函数</b>形态（自检里要能在没有机器实例时断言它）。 */
    public static int parallelCapFor(long limit) {
        if (limit <= 1L) {
            return 1;
        }
        return (int) Math.min(limit, Integer.MAX_VALUE);
    }

    private void startMatterSlotScan() {
        scanMatterSlot();   // 成形即扫一次，别等第一次节拍
        matterSlotScanSubs = subscribeServerTick(matterSlotScanSubs, () -> {
            if (getOffsetTimer() % PARALLEL_SCAN_INTERVAL == 0) {
                scanMatterSlot();
            }
        });
    }

    private void stopMatterSlotScan() {
        if (matterSlotScanSubs != null) {
            matterSlotScanSubs.unsubscribe();
            matterSlotScanSubs = null;
        }
    }

    /**
     * 本模块当前的<b>并行上限</b>（显示 + Jade 用）。
     *
     * <h2>🔴 2026-09-25 路线 ① 订正：以前这里返回 1</h2>
     * 旧实现是 {@code return 1L;}，并在注释里论证"23 台纯标准模块的配方修饰链跳过并行那一步
     * ⇒ 并行恒为 1"。<b>那句话对"当时的代码"是真的，但对"这台机器"是错的</b>——
     * 上游 23/24 台本来就有「空槽 64 + 自己那张表」的并行槽（逐台上游核对见交付报告），
     * 用户在游戏里当场指出「不加任何物质模块它本身就有 64 的并行数」。
     * ⇒ 现在所有 24 台都从 {@link #scanMatterSlot()} 拿真实值。
     *
     * <p><b>返回值口径 = 「原始上限」（可到 {@code Long.MAX_VALUE}）</b>，由
     * {@link #formatParallel(long)} 负责把超大值画成「无限」。
     * Jade 那一侧要的是 {@code int} 档位，由 {@link #getJadeParallel()} 就地钳位。
     */
    public long getDisplayParallel() {
        return getCurrentParallel();
    }

    /**
     * 供 Jade（{@code ParallelProvider} 的 {@code parallel} 键）使用的并行值 —— <b>原始 long</b>。
     *
     * <h2>🔴 2026-09-26：由 {@code int parallelCap()}（21 亿封顶）改成<b>原始 long</b></h2>
     * <p>⛔ 旧实现（作废，原文留档）：
     * <pre>
     *   public int getJadeParallel() { return parallelCap(); }        // = (int) min(limit, Integer.MAX_VALUE)
     * </pre>
     * 旧注释的理由是「上游渲染是 {@code Component.literal(parallel + "")} ⇒ 直接塞 Long.MAX 会画成 19 位数字」
     * ⇒ <b>它把抬头上那行数字压成了 21 亿</b>，也就是本次任务要消灭的那一层压平。
     * <p>✅ 现在的口径（队长 2026-09-26 任务书 ④）：
     * <pre>
     *   · 值 &lt;  Long.MAX_VALUE / 2  ⇒ 写<b>真 long</b>（上游 `%d` 键会原样画出真实位数）
     *   · 值 &gt;= Long.MAX_VALUE / 2  ⇒ 写 gtladditions 自己的「无限」哨兵
     *                                 （{@code DISPLAY_INFINITE_PARALLEL}）⇒ 画成彩虹「无限」
     * </pre>
     * 后者与老山海 {@code addParallelDisplay} 的判据
     * （{@code parallel >= Long.MAX_VALUE / 2 ⇒ 无限}）<b>同一个阈值</b>，
     * 由 {@link #isInfiniteParallel(long)} 单点定义、三处显示共用，不会漂移。
     */
    public long getJadeParallel() {
        return getDisplayParallel();
    }

    /**
     * 本模块的<b>跨配方线程数</b>（显示值）。
     *
     * <h2>🔴 字段级查证：本工程 24 台模块现在【没有】跨配方线程这个功能</h2>
     * 逐点证据（2026-09-25，任务 A 第 ① 问）：
     * <ol>
     *   <li>{@link PrimordialModuleMachine} / {@link ModuleRegistry} / {@link PrimordialMatterRecombinatorCore}
     *       全类扫描：<b>没有任何 {@code threads} / {@code multipleThreads} / {@code multiRecipe} 字段或方法</b>。
     *       唯一带"线程"字样的是 {@link #threadBoostSlot}（1 格），而它的注释与 GUI tooltip 都写着
     *       「阶段 1：只收物品，不参与计算（规格 §7.1）」——<b>是个收纳槽，不是线程来源</b>。</li>
     *   <li>模块<b>刻意不实现</b> {@code IGTLAddMultiRecipeMachine}（理由见
     *       {@code LimitedDurationAdapter} 的注释（该类已于 2026-09-26 随下限功能删除）：
     *       实现它会顺带改掉 Waila 的并行显示），
     *       而"跨配方线程"正是那条接口链（{@code IWirelessThreadModifierParallelMachine →
     *       ParallelMachine}）上的概念 ⇒ 模块<b>连概念都没接上</b>。</li>
     *   <li>主机是另一回事：主机有真的跨配方线程（{@code PrimordialEngineRecipeLogic#getMultipleThreads()}
     *       = 128，Jade 里画成「拥有 128 个跨配方线程」）。<b>主机≠模块，两侧口径不同是有意的。</b></li>
     * </ol>
     * ⇒ 结论：<b>没有这个功能</b>；用户已知情并要求「你先写 1」。所以这里返回
     * {@link #CROSS_RECIPE_THREADS}，<b>且明确标注它是显示值</b>。
     *
     * <h2>🔴 2026-09-26：本方法 = "线程值来源"的<b>唯一接缝</b>（用户裁决）</h2>
     * <b>用户原话（逐字）</b>：
     * <blockquote>「模块的并行和线程不随并行仓，而是随物质模块，和另一个决定线程的物品（我还没想好），
     * 你先把跨配方线程做了，然后设置为1，打好基础」</blockquote>
     * <ul>
     *   <li><b>模块的线程【不】随并行仓</b> ⇒ 我们<b>不接</b>上游那套
     *       {@code ThreadPartMachine} / {@code GTLAddPartAbility.THREAD_MODIFIER}「线程仓」机制，
     *       也<b>不给</b> 24 台的结构图案加线程仓位置
     *       （2026-09-26 队长裁定：撤回"本轮加图案位置"那一项）。</li>
     *   <li><b>线程值将来由"另一个物品"决定</b>（用户还没想好是哪个）⇒ 那个物品定下来之后，
     *       <b>只改本方法这一处</b>（顶多再加一个 {@code @Persisted} 槽位与一次查表）。
     *       <b>不许在 24 台里各写一遍，也不许在别处再开第二个接缝</b> ——
     *       两处各自决定同一个数，必然漂移，而漂移的表现是"tooltip 说 1、Jade 说别的"。</li>
     *   <li><b>本阶段（"打好基础"）= 值恒为 1</b>：取值的单一来源就是
     *       {@link #CROSS_RECIPE_THREADS}，三处显示（物品 tooltip / 机器 GUI / Jade）
     *       将来都从这里取 ⇒ 换来源时不会出现"显示变了、运算没变"的漂移。</li>
     * </ul>
     * ⛔⛔ <b>【2026-09-28 作废，原文逐字留档】上面那段"诚实边界"当时为真、现在是错的。</b>
     * <pre>
     * ⚠️ 诚实边界（别把它读成"功能已生效"）：本阶段该值【不参与任何运算】；
     * 让线程数真正作用到配方并行上的那一半是引擎侧的事，与本次改动分开交付。
     * "打好基础"指的是【接缝就位】，不是"功能现在生效"。
     * </pre>
     * <b>作废原因（用户 2026-09-28 点单）</b>：世线残片已定下来，本轮把线程真正接上 ——
     * <ul>
     *   <li><b>取值</b>：{@link ShanhaiConcurrencyTables#finalThreads(int, int)} =
     *       {@code 1 + 2^N × 该格数量}（空槽 / 非残片 ⇒ 1）；</li>
     *   <li><b>消费</b>：{@code PrimordialModuleRecipeLogic#getMultipleThreads()} 覆写后直接返回本值
     *       ⇒ 引擎的"候选配方上限"与"并行预算倍数"都跟着变（不再是常量 1）。</li>
     * </ul>
     * 旧的三处显示（物品 tooltip / 机器 GUI / Jade）<b>取值点一个都不用改</b> ——
     * 它们本来就都从这里取，这正是当初把接缝做在这里的目的。
     */
    public int getCrossRecipeThreads() {
        return ShanhaiConcurrencyTables.finalThreads(
                ShanhaiConcurrencyTables.threadsForShardId(shardIdOfThreadBoostSlot()),
                threadBoostSlot.storage.getStackInSlot(0).getCount());
    }

    /**
     * 线程槽里物品的注册 id；空槽 / 取不到 ⇒ {@code null}。<b>只读，不改槽。</b>
     *
     * <p>与 {@link #getMatterModuleId()} 同形：非残片<b>不</b>返回 null，而是原样返回 id ——
     * "是不是残片"由 {@link ShanhaiConcurrencyTables#threadsForShardId(String)} 判（非残片 = 0），
     * 两件事分开，免得将来有人把"识别"与"取值"揉在一起。
     */
    @Nullable
    public String shardIdOfThreadBoostSlot() {
        return itemId(threadBoostSlot.storage.getStackInSlot(0));
    }

    /**
     * 线程槽当前提供的<b>额外</b>跨配方并行（线程）= {@code 2^N × 该格数量}；
     * 空槽 / 非残片 / 数量 0 ⇒ <b>0</b>。
     *
     * <p>{@code getCrossRecipeThreads()} 恒等于
     * {@code ShanhaiConcurrencyTables.BASE_CROSS_RECIPE_THREADS（= 1） + 本方法}。
     */
    public int getExtraCrossRecipeThreads() {
        return getCrossRecipeThreads() - ShanhaiConcurrencyTables.BASE_CROSS_RECIPE_THREADS;
    }

    /** 子类追加自己的显示行（物质模块等级等）。 */
    protected void addModuleDisplayText(@NotNull List<Component> textList) {}

    // ═════════════════════════════ 4.5 D：「批处理」按钮（2026-09-21 队长裁决：去掉） ═════════════════════════════

    // ⛔⛔ 【2026-09-22 作废并撤回，原文留档】D 项「去掉批处理」的 4 个覆写已全部删除
    //     （模块侧，与主机侧同批）。
    //
    // ⛔ 旧原文（作废，逐字留档，勿再启用）：
    //     @Override public boolean canConfigureBatchProcessing() { return false; }
    //     @Override public boolean supportsBatchProcessing()      { return false; }
    //     @Override public boolean isBatchEnabled()               { return false; }
    //     @Override public void setBatchEnabled(boolean enabled)  { /* 有意为空 */ }
    //
    // ⛔ 作废原因：**用户 2026-09-22 原话「再把批处理的按钮补回来」** ⇒ 恢复 gtlcore 默认行为。
    //    依据（主机侧同一段留档）：WorkableElectricMultiblockMachine 在运行期被 gtlcore 的
    //    WorkableElectricMultiblockMachineMixin 织入 IBatchMachine，按钮由 BatchConfiguratorMixin
    //    在 attachConfigurators 返回之后才挂；撤回后按钮会重新挂到 24 台模块上。
    //    ⚠️ 与主机侧口径一致：`implements IBatchMachine` 的声明**保留**（未核实全工程引用）。

    // ═════════════════════════════ 5. 三类通用控制位 · 只读视图 ═════════════════════════════

    /** 物质模块槽中的物品 id（空槽返回 null）。 */
    @Nullable
    public String getMatterModuleId() {
        String id = itemId(matterModuleSlot.storage.getStackInSlot(0));
        return id != null && MODULE_LEVELS.containsKey(id) ? id : null;
    }

    /** 物质模块等级 1..17；非模块 / 空槽返回 0。 */
    public int getMatterModuleLevel() {
        String id = getMatterModuleId();
        return id == null ? 0 : MODULE_LEVELS.getOrDefault(id, 0);
    }

    /** 物质模块堆叠数量；空槽返回 0。 */
    public int getMatterModuleCount() {
        return matterModuleSlot.storage.getStackInSlot(0).getCount();
    }

    /** 显示名（无模块时返回「空槽」）。 */
    @NotNull
    public String displayNameOfMatterModule() {
        ItemStack stack = matterModuleSlot.storage.getStackInSlot(0);
        if (stack.isEmpty()) {
            return "空槽";
        }
        return stack.getHoverName().getString();
    }

    // ═════════════════════════════ 5.x 🆕 额外挂载槽 · 只读视图（2026-10-03） ═════════════════════════════
    //     ⛔ 本节旧的身子是「恒星热力槽 · 只读视图（2026-09-30）」—— 那一格已按用户规格②删除，
    //        getHeatSlotStack / getHeatSlotCount / getHeatSlotSource / isHeatSlotActive 四个方法
    //        连同它的缓存字段一并删除（判据改读额外挂载槽，见下面三个方法）。

    /** 额外挂载槽第 {@code index} 格里的堆叠（空槽 ⇒ {@code ItemStack.EMPTY}）。 */
    @NotNull
    public ItemStack getExtraMountStack(int index) {
        return extraMountSlots.storage.getStackInSlot(index);
    }

    /** 额外挂载槽第 {@code index} 格里的数量；空槽 0。 */
    public int getExtraMountCount(int index) {
        return extraMountSlots.storage.getStackInSlot(index).getCount();
    }

    /**
     * 三格现读内容的缓存（判据核吃的那一份纯数据）。
     *
     * <p>🔴 为什么必须缓存：{@link ShanhaiHeatSources#slotContentOf} 要查注册表
     * （{@code ForgeRegistries.ITEMS/BLOCKS.getKey}），而它会被 {@code checkRecipe}
     * <b>逐条候选配方</b>调用 —— 一台模块的候选集可以到 40 条以上，每条都查 3 次注册表是纯浪费。
     * <p>失效判据用<b>物品 + NBT + 数量</b>三者一起比：{@code ItemStack.matches(a,b)} 是
     * {@code isSameItemSameTags}（<b>不看数量</b>）⇒ 只比它会在"64 个变 63 个"时读到旧值，
     * 而数量恰恰是"生效没生效"的判据本身。
     */
    @Nullable
    private ItemStack[] shanhai$extraCacheStacks;

    /** 上面那三格对应的能力。 */
    @NotNull
    private List<ShanhaiHeatGate.SlotContent> shanhai$extraCacheContents = List.of();

    /** 三格现读内容（顺序 = 槽序号）。纯读、带缓存。 */
    @NotNull
    public List<ShanhaiHeatGate.SlotContent> getExtraMountContents() {
        final ItemStack[] cached = shanhai$extraCacheStacks;
        boolean hit = cached != null && cached.length == EXTRA_MOUNT_SLOT_COUNT;
        if (hit) {
            for (int i = 0; i < EXTRA_MOUNT_SLOT_COUNT; i++) {
                final ItemStack now = extraMountSlots.storage.getStackInSlot(i);
                if (!ItemStack.isSameItemSameTags(cached[i], now) || cached[i].getCount() != now.getCount()) {
                    hit = false;
                    break;
                }
            }
        }
        if (hit) {
            return shanhai$extraCacheContents;
        }
        final List<ShanhaiHeatGate.SlotContent> computed = new java.util.ArrayList<>(EXTRA_MOUNT_SLOT_COUNT);
        final ItemStack[] snapshot = new ItemStack[EXTRA_MOUNT_SLOT_COUNT];
        for (int i = 0; i < EXTRA_MOUNT_SLOT_COUNT; i++) {
            final ItemStack now = extraMountSlots.storage.getStackInSlot(i);
            snapshot[i] = now.copy();
            computed.add(ShanhaiHeatSources.slotContentOf(now));
        }
        shanhai$extraCacheStacks = snapshot;
        shanhai$extraCacheContents = List.copyOf(computed);
        return shanhai$extraCacheContents;
    }

    /**
     * 三格的<b>读数</b>（给人看/给证据行用，一行一格）。
     * <p>与 {@link #getExtraMountContents()} 同源，不另算一份。
     */
    @NotNull
    public String describeExtraMounts() {
        final StringBuilder sb = new StringBuilder();
        final List<ShanhaiHeatGate.SlotContent> contents = getExtraMountContents();
        for (int i = 0; i < contents.size(); i++) {
            if (i > 0) {
                sb.append(" / ");
            }
            sb.append('[').append(i + 1).append(']').append(contents.get(i).describe());
        }
        return sb.toString();
    }

    /** 当前这台机器能不能用额外挂载槽充当<b>热力源</b>（白名单 = 用户点名的三台）。 */
    public boolean canUseExtraMountAsHeatSource() {
        return ShanhaiHeatGate.hasHeatSlot(shanhai$machineId());
    }

    /**
     * 线程槽中的堆叠（<b>2026-09-28 起参与计算</b> —— 见 {@link #getCrossRecipeThreads()}）。
     *
     * <p>⛔ 旧注释（作废，逐字留档）：「线程倍率槽中的堆叠（阶段 1 只做展示，不参与计算）」。
     */
    @NotNull
    public ItemStack getThreadBoostStack() {
        return threadBoostSlot.storage.getStackInSlot(0);
    }

    /** 按 id 查模块等级（1..17），非模块返回 0。 */
    public static int getModuleLevelById(@Nullable String id) {
        return id == null ? 0 : MODULE_LEVELS.getOrDefault(id, 0);
    }

    /**
     * 按<b>物品堆</b>查物质模块等级（1..17）；空堆 / 非模块返回 0。
     *
     * <p>给 N7 的"主机专属槽"门控用（{@code PrimordialOmegaEngineMachine#moduleSlotBonus}）。
     * <b>刻意复用 {@link #MODULE_LEVELS} 这张唯一的等级表</b>：全工程只有一份"物品 id → 等级"映射，
     * 主机侧不许另建一套（两套表迟早会漂移，且漂移后只表现为"某个模块突然不算数"这种静默失败）。
     * 本方法只是把 {@code ItemStack → id} 这一步包好，等价于 {@code getModuleLevelById(itemId(stack))}。
     */
    public static int getModuleLevelByStack(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return key == null ? 0 : MODULE_LEVELS.getOrDefault(key.toString(), 0);
    }

    /** 是否属于 17 个物质模块（前缀 `shanhai:wz` ∪ {现实锚点, 创始现实修改}）。 */
    public static boolean isMatterModuleStack(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return true; // 空槽永远放行，否则玩家没法清空槽位
        }
        String id = itemId(stack);
        return id != null && MODULE_LEVELS.containsKey(id);
    }

    /** 物品注册 id 字符串；取不到返回 null。 */
    @Nullable
    protected static String itemId(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return key == null ? null : key.toString();
    }

    // ═════════════════════════════ 6. 持久化 ═════════════════════════════

    @Override
    public void saveCustomPersistedData(@NotNull CompoundTag tag, boolean forDrop) {
        super.saveCustomPersistedData(tag, forDrop);
        if (hostPosition != null) {
            tag.putLong(NBT_HOST_POS, hostPosition.asLong());
        }
    }

    @Override
    public void loadCustomPersistedData(@NotNull CompoundTag tag) {
        super.loadCustomPersistedData(tag);
        if (tag.contains(NBT_HOST_POS)) {
            this.hostPosition = BlockPos.of(tag.getLong(NBT_HOST_POS));
        }
    }
}
