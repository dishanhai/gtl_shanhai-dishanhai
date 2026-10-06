package com.shanhai.machine.wildcard;

import appeng.api.crafting.IPatternDetails;
import appeng.api.implementations.blockentities.PatternContainerGroup;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.IGridNode;
import appeng.api.networking.ticking.IGridTickable;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.networking.ticking.TickingRequest;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEKey;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.gui.fancy.ConfiguratorPanel;
import com.gregtechceu.gtceu.api.gui.fancy.IFancyConfigurator;
import com.gregtechceu.gtceu.api.gui.fancy.IFancyConfiguratorButton.Toggle;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MachineDefinition;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient;
import com.gregtechceu.gtceu.common.data.GTRecipeTypes;
import com.gregtechceu.gtceu.integration.ae2.gui.widget.AETextInputButtonWidget;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.misc.FluidStorage;
import com.lowdragmc.lowdraglib.misc.FluidTransferList;
import com.lowdragmc.lowdraglib.misc.ItemStackTransfer;
import com.lowdragmc.lowdraglib.side.fluid.FluidHelper;
import com.lowdragmc.lowdraglib.side.fluid.FluidStack;
import com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced;
import com.lowdragmc.lowdraglib.syncdata.annotation.LazyManaged;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.Pair;
import it.unimi.dsi.fastutil.ints.Int2ReferenceMap;
import it.unimi.dsi.fastutil.ints.Int2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntConsumer;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongArrayMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongMaps;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import org.gtlcore.gtlcore.api.gui.GuiTextures;
import org.gtlcore.gtlcore.api.machine.trait.IMERecipeHandlerTrait;
import org.gtlcore.gtlcore.api.machine.trait.MEPart.IMEPatternTrait;
import org.gtlcore.gtlcore.client.gui.widget.PatternCycleWidget;
import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEIOPartMachine.MEIOTrait;
import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferPartMachineBase;
import org.gtlcore.gtlcore.integration.ae2.AEUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * <b>超级通配符ME样板总成</b> —— 宿主 {@code gtceu:me_wildcard_pattern_buffer}（通配符ME样板总成）
 * 的全部能力，外加「同时放置 {@value #WILDCARD_SLOT_COUNT} 块通配符样板」。
 *
 * <h2>一、它和宿主那台是什么关系（需求①②的核心）</h2>
 * 宿主那台的真身是 <b>gtlcore</b> 注册的（不是 gtceu 原生，只是挂在 {@code gtceu:} 命名空间下）：
 * <pre>
 *   org.gtlcore.gtlcore.integration.wildcard.MEWildcardPatternBufferPartMachine
 *       extends org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferPartMachineBase
 *           extends …MEIOPartMachine extends MultiblockPartMachine
 * </pre>
 * （取证：{@code WildcardPatternCompatImpl.registerMachines()} 里
 * {@code GTRegistration.REGISTRATE.machine("me_wildcard_pattern_buffer", holder -> new MEWildcardPatternBufferPartMachine(holder, IO.BOTH))}，
 * 见 {@code temp\wildcard\decomp\...\WildcardPatternCompatImpl.java:25-31}。）
 *
 * <p>🔴 宿主那台的通配符样板槽是<b>写死的 1 个</b>：反编译原文
 * {@code this.wildcardPatternSlot = new ItemStackTransfer(1);}、
 * {@code internalPatternInventory.size() → 1}（{@code MEWildcardPatternBufferPartMachine.java:110/86}）。
 *
 * <p>⚠️ 而它的槽位集合 <b>不能靠继承改</b>：{@code wildcardPatternSlot} / {@code expandedPatterns} /
 * {@code patternToSlotMap} / {@code internalSlots} / {@code activeSlotIndices} 全是
 * <b>private final</b>，{@code refreshPatterns(boolean)} 是 <b>private</b>，
 * 它依赖的 {@code MEWildcardPatternBufferPersistenceHelper} / {@code WildcardPatternCompatImpl}
 * 是 <b>包私有类/方法</b>（不同包即使继承也拿不到）。
 *
 * <h2>二、⇒ 采取的方案：继承<b>宿主所用的同一个基类</b>，把「1 槽」写成「N 槽」</h2>
 * 本类直接继承 {@link MEPatternBufferPartMachineBase}（= 宿主那台的父类），
 * 因此 <b>AE 网格接入、样板容器协议、槽位缓存、催化剂、共享库存、镜像（proxy）、
 * 退款、Jade、终端可见性、槽位级物料隔离</b>这一整套全部<b>沿用同一份上游实现</b>，
 * 不是重写的。我们只做三件宿主做不到的事：
 * <ol>
 *   <li>样板槽从 1 个变成 {@value #WILDCARD_SLOT_COUNT} 个（{@link #wildcardPatternSlot}）；</li>
 *   <li>展开样板时给每个槽的样板分配<b>连续的全局索引</b>（{@link #refreshPatterns(boolean)}）；</li>
 *   <li>GUI / 终端 / 存档按 N 槽处理。</li>
 * </ol>
 *
 * <h2>三、🔴 与宿主<b>已知的行为差异</b>（写清楚，不许含糊）</h2>
 * <ul>
 *   <li><b>槽位内容是「按索引 + 样板输出指纹」双重校验后恢复的</b>（见
 *       {@link #saveCustomPersistedData}/{@link #loadCustomPersistedData}）。
 *       宿主用 gtlcore 的包私有 {@code MEWildcardPatternBufferPersistenceHelper}，
 *       我们拿不到 ⇒ 自己实现，语义对齐（指纹不符时把该槽内容<b>退回 buffer</b>，绝不静默丢弃）。</li>
 *   <li><b>「已缓存配方」这份运行时账本不落盘</b>（本轮由 {@code recipeCacheMap} 换成
 *       {@link ShanhaiPatternRecipeCache}，形状对齐上游 <b>超级样板总成</b> 的
 *       {@code recipeMultipleCacheMap}；宿主那台存的是单条配方）。
 *       ⇒ 影响面 = 重载存档后「缓存」栏为空、配方需要重新被 AE 搜索一次；<b>不影响产出</b>。
 *       ⚠️ 与「<b>实际配方数量</b>」{@link #cacheRecipeCount} 区分清楚：那个是玩家设的阈值、
 *       <b>{@code @Persisted @DescSynced}，不重置</b>；这一份是运行时账本，重载后从头攒。</li>
 * </ul>
 *
 * <h2>四、🔴 KubeJS 契约</h2>
 * 本类的任何方法<b>都不</b>从 KubeJS 调用（宿主脚本只引用它的<b>物品 id</b>），
 * 因此不存在「Rhino 撞重载」的风险面。
 */
public class SuperWildcardPatternBufferPartMachine extends MEPatternBufferPartMachineBase
        implements AutoForgeMultiplierHost {

    /**
     * 通配符样板槽位数 —— 需求②点名的数字（宿主 = 1）。
     *
     * <p>同时决定三处：{@link #wildcardPatternSlot} 的容量、{@code internalPatternInventory.size()}、
     * 以及 GUI 里画几个格子。三者<b>同一个常量</b>，不存在「改了槽位但 GUI 还是 1 格」的分裂。
     */
    public static final int WILDCARD_SLOT_COUNT = 10;

    /** 存档键：本机私有（宿主用 {@code wildcardDynamicSlots}，互不干扰）。 */
    private static final String NBT_SLOTS = "shanhaiWildcardSlots";

    /** 存档键：每块通配符样板各自的电路号（{@value #CIRCUIT_UNSET} = 没设过）。 */
    private static final String NBT_SLOT_CIRCUIT = "shanhaiSlotCircuit";

    /** 存档键：每槽催化剂槽内的物品/流体（与上游 {@code catalystItems} / {@code catalystFluids} 同义）。 */
    private static final String NBT_CATALYST_ITEMS = "shanhaiCatalystItems";

    private static final String NBT_CATALYST_FLUIDS = "shanhaiCatalystFluids";

    /** 「没设过电路」的规范值。⚠️ 只用于本类自己的数组，<b>绝不</b>传给上游的任何 setter。 */
    public static final int CIRCUIT_UNSET = 0;

    /** 每槽催化剂的形状 —— 与上游 {@code MEPatternBufferPartMachine} 逐值相同：9 格 / 9 罐。 */
    private static final int CATALYST_ITEM_SLOTS = 9;

    private static final int CATALYST_FLUID_TANKS = 9;

    private static final long CATALYST_TANK_CAPACITY = 16L * FluidHelper.getBucket();

    private static final Logger LOGGER = LogUtils.getLogger();

    protected static final ManagedFieldHolder MANAGED_FIELD_HOLDER = new ManagedFieldHolder(
            SuperWildcardPatternBufferPartMachine.class, MEPatternBufferPartMachineBase.MANAGED_FIELD_HOLDER);

    /** 通配符样板槽（{@value #WILDCARD_SLOT_COUNT} 格，过滤 = 只收通配符样板）。 */
    @Persisted
    private final ItemStackTransfer wildcardPatternSlot;

    /**
     * 🔴 「每块通配符样板各设一个电路」的<b>权威存储</b>（下标 = 通配符样板槽号）。
     *
     * <p>取值 {@value #CIRCUIT_UNSET} = 没设过（沿用左侧共享电路）；1–32 = 指定电路。
     *
     * <h2>为什么不是写进样板物品 NBT（自创通路已作废）</h2>
     * 2026-10-01 无头专服实测（见 {@code handoff/outbound/通配符样板总成-六条修复.md}）：
     * 上游每槽电路的权威存储是 {@code InternalSlot} 自己的
     * {@code SlotCacheManager.circuitCache}，而 {@code InternalSlot.serializeNBT} 会把它落盘
     * （键 {@code virtualCircuit}），重载后原样恢复。把编号再写一份到<b>样板物品</b>的 NBT 上，
     * 等于在一个<b>没有 {@code @DescSynced}</b> 的槽位上又加一份影子状态 —— 客户端读不到，
     * 面板就只能显示默认值（就是用户第 1/2 条看到的「重置为 0」「第二格显示第一格」）。
     *
     * <p>{@code @DescSynced} 是这一条的关键：面板在<b>客户端</b>取值，必须能拿到服务端权威值。
     */
    @DescSynced
    @Persisted
    private final int[] slotCircuit = new int[WILDCARD_SLOT_COUNT];

    /**
     * 每槽物品催化剂（{@value #CATALYST_ITEM_SLOTS} 格），下标 = 通配符样板槽号。
     *
     * <p>🔴 用户第 5 条「我的催化剂槽位呢？」缺的就是这一整套 ——
     * 光有界面不生效：必须同时有<b>存储</b>（本字段）、<b>槽子类</b>
     * （{@link ShanhaiPatternBufferInternalSlot} 覆写催化剂钩子）与<b>界面</b>（中键弹框）。
     * 三者缺一不可，与 {@code MEPatternBufferPartMachine} 的三件套一一对应。
     *
     * <p>⚠️ <b>本字段刻意【不】带 {@code @LazyManaged}</b>（与上游逐字一致）：元素类型
     * {@code ItemStackTransfer} 实现了 {@code IContentChangeAware}，LDLib 的
     * {@code ReadonlyArrayRef.init()} 对它的检查是通过的。别照抄 {@link #catalystFluids} 的
     * {@code @LazyManaged} 往这里加 —— 加了会连带丢掉 LDLib 自动挂的 contents-changed 处理器。
     */
    @Persisted
    private final ItemStackTransfer[] catalystItems = new ItemStackTransfer[WILDCARD_SLOT_COUNT];

    /**
     * 每槽流体催化剂（{@value #CATALYST_FLUID_TANKS} 罐 × 16 桶），下标 = 通配符样板槽号。
     *
     * <h2>🔴 为什么必须带 {@code @LazyManaged}（2026-10-01 18:05:47 客户端闪退的根因）</h2>
     * 崩溃原文：{@code java.lang.IllegalStateException: Failed to create ref of catalystFluids with type:}
     * {@code com.lowdragmc.lowdraglib.misc.FluidTransferList[]}，
     * 根因 {@code IllegalArgumentException: complex sync field must be an IContentChangeAware if not lazy!}。
     *
     * <p>LDLib 的规矩（全部 {@code javap -v} / {@code javap -c} 实证，读数见
     * {@code handoff/outbound/修复-同步字段崩溃.md}）：
     * <ol>
     *   <li>{@code ManagedFieldUtils.getManagedFields} 对 {@code @Persisted} <b>和</b> {@code @DescSynced}
     *       一视同仁 ⇒ <b>标了 {@code @Persisted} 就会建 ref</b>（不是只有 {@code @DescSynced} 才建）；</li>
     *   <li>ref 走 {@code ReadonlyArrayRef.init()}（元素访问器不 managed 时）⇒
     *       元素类型既不是 {@code IContentChangeAware} 也不是 {@code IManaged} 时<b>直接抛异常</b>，
     *       除非字段带 {@code @LazyManaged}（此时 {@code isLazy()} 为真、init 立刻 return）；</li>
     *   <li>{@code ItemStackTransfer} 实现了 {@code IContentChangeAware}
     *       ⇒ {@link #catalystItems} 不 lazy 也合法；而 {@code FluidTransferList} <b>没有</b>实现它
     *       ⇒ 本字段<b>必须</b>是 lazy。</li>
     * </ol>
     * ⇒ 这正是上游 {@code MEPatternBufferPartMachine} 的标法：{@code catalystItems} 只 {@code @Persisted}，
     * {@code catalystFluids} 是 {@code @Persisted @LazyManaged}。<b>照它做，不要自己决定注解。</b>
     */
    @Persisted
    @LazyManaged
    private final FluidTransferList[] catalystFluids = new FluidTransferList[WILDCARD_SLOT_COUNT];

    /** 是否在 ME 终端里隐藏（与宿主同款开关）。 */
    @DescSynced
    @Persisted
    public boolean isHiddenTerminal = false;

    /**
     * 神锻样板模式开关（需求④，与上游超级样板总成的 {@code foaModeEnabled} 同义）。
     *
     * <p>⚠️ 与上游的差异（有意为之、且更保守）：上游那个是<b>不落盘</b>的普通字段，
     * 重载存档就回到「关」；本字段 {@code @Persisted} ⇒ 重载后保持上次的选择，
     * 不会出现「存档里那批样板忽然按原始形态算料」这种读数突变。
     */
    @DescSynced
    @Persisted
    private boolean forgePatternMode = false;

    /** 神锻模式倍率（上游默认 15，这里跟随）。⚠️ <b>它就是「手填值」</b>，语义没有变。 */
    @DescSynced
    @Persisted
    private int forgePatternMultiplier = ShanhaiForgePatternMode.DEFAULT_MULTIPLIER;

    /**
     * 🔴 <b>「自动倍率」开关</b>（用户 2026-10-04 点单；同日第二轮提了四条修正）。
     *
     * <p>用户原话（逐字）：
     * <b>「就是那个倍率不是需要你配置的嘛，我希望给他添加一个开关，打开之后可以自动配置这个倍率
     * （通过读取那台机器上的倍率）」</b>；「读哪台机器」他选的是
     * <b>「读它所在多方块的控制器」</b>。
     *
     * <h2>语义（2026-10-04 第二轮修正后的最终口径）</h2>
     * <pre>
     *   开着 ⇒ 生效倍率 = 从本仓室所在多方块的控制器上读到的「额外产出倍率」，
     *                    【向下取整】并夹到 [1, 30]（用户修正②）；
     *          🔴 读不到 ⇒ 按「无额外产出」算 = ×1（用户修正④），
     *                     手填值在关开着时【不参与运算】；
     *   关联 ⇒ 手填值只在【关】的时候才参与。
     * </pre>
     * 两种来源（用户修正①「其实不止是伪神之锻炉，还有我们的原始终焉引擎」）：
     * <ul>
     *   <li>gtladditions 的 <b>伪神之锻炉</b>控制器 ⇒ 反射 {@code getRecipeOutputMultiply()}；</li>
     *   <li>本工程的 <b>原始终焉引擎</b> ⇒ 直接调
     *       {@code PrimordialRecipeEffects.outputMultiplier(engine.moduleSlotBonus())}。</li>
     * </ul>
     * 两者都不匹配 ⇒ ×1。全程绝不抛异常、绝不让配方算错。
     *
     * <h2>🔴 默认值 = <b>开</b>（用户第二轮逐字追加：「顺便提一个要求，这个开关默认打开」）</h2>
     * 两条连带后果，都已按用户口径处理、<b>没有做任何特判</b>：
     * <ul>
     *   <li><b>老存档里的既有机器也变成「默认开」</b>：本字段是新增的 ⇒ 旧 NBT 里没有它 ⇒
     *       反序列化落回 Java 默认值 {@code true}。这正是用户要的，故<b>刻意不写任何「补默认」代码</b>。</li>
     *   <li><b>「读不到 ⇒ ×1」从此是常态路径</b>（多数机器的控制器不给额外产出）
     *       —— 用户 2026-10-04 修正④逐字：「读不到不应该默认是1吗，<b>因为没有额外产出的机器才读不到啊</b>」
     *       ⇒ ① 日志走 {@link ShanhaiAutoForgeMultiplierDriver} 里的限流闸门（同因只打一次，不刷屏）；
     *       ② 面板第二行明写原因（见 {@link #shanhaiAutoForgeMultiplierReasonText()}）。
     *       ⚠️ 「读不到」<b>不是</b>「退回手填值」：手填值<b>只在开关关着时</b>才参与运算。</li>
     * </ul>
     *
     * <h2>为什么与 {@link #forgePatternMode} 同样标 {@code @DescSynced @Persisted}</h2>
     * {@code @DescSynced}：开关状态在<b>客户端</b>的面板上被读（{@code ForgePatternConfigurator}），
     * 不同步就只会显示服务端那份之外的另一套默认值。
     * {@code @Persisted}：重载存档后保持上次的选择，不会出现「开着的开关忽然变回关」这种读数突变。
     */
    @DescSynced
    @Persisted
    private boolean autoForgeMultiplier = true;

    /**
     * 最近一次<b>从控制器读到的</b>倍率（已规范化）；{@link ShanhaiAutoForgeMultiplier#NO_READ} = 没读到/非法。
     *
     * <h2>🔴 为什么它必须 {@code @DescSynced}（而不是让面板自己去读控制器）</h2>
     * 「面板显示的数」必须<b>就是算法用的那个数</b>。若让界面自己再读一次控制器，
     * 就会出现「界面写着 23、配方按 18 算」这种两份真相 —— 正是本工程反复血账过的那一类。
     * ⇒ 读取只发生在<b>服务端</b>一处（{@link #shanhaiRefreshAutoForgeMultiplier()}），
     * 结果同步到客户端，界面、日志、算法<b>读的是同一个字段</b>。
     *
     * <p>⚠️ 它<b>不带</b> {@code @Persisted}：这是运行时派生量（伪神锻的倍率每时每刻都在变），
     * 落盘没有意义；重载后第一次重算会立刻写新值。
     */
    @DescSynced
    private int autoForgeMultiplierRead = ShanhaiAutoForgeMultiplier.NO_READ;

    /**
     * 上一次读取的<b>标注</b>：读到时是<b>来源</b>（{@code SOURCE_*}），读不到时是<b>原因</b>（{@code REASON_*}）。
     *
     * <p>与 {@link #autoForgeMultiplierRead} 同一种性质：服务端算、同步给面板显示
     * （前缀「来源：」/「原因：」由 {@link ShanhaiAutoForgeMultiplier#reasonLine} 加）。
     * 自动开关关着时它是 {@link ShanhaiAutoForgeMultiplier#REASON_AUTO_OFF}。
     *
     * <p>⚠️ 2026-10-04：它<b>不再</b>只表示「失败原因」—— 读成功时它承载「这份数是从哪台机器读来的」，
     * 因为用户要求面板能看出<b>当前的来源</b>（伪神之锻炉 / 原始终焉引擎）。
     */
    @DescSynced
    private String autoForgeMultiplierReason = ShanhaiAutoForgeMultiplier.REASON_AUTO_OFF;

    /**
     * <b>本机那份「自动倍率」驱动</b> —— 探测/判定/文案/限流日志/节流计数都在它里面。
     *
     * <p>🔴 2026-10-04 追加 B：这些逻辑<b>整体搬进 {@link ShanhaiAutoForgeMultiplierDriver}</b>，
     * 于是上游那台「超级样板总成」（经 mixin 加装）走的是<b>同一份实现</b>——
     * 用户逐字：「不许出现第二份实现」。
     *
     * <p>⚠️ 刻意<b>不加</b> {@code @DescSynced}/{@code @Persisted}：闸门与节流计数是纯服务端运行时状态，
     * 不属于机器的持久化/同步字段（持久化的那三个见上面）。
     */
    private final ShanhaiAutoForgeMultiplierDriver autoForgeMultiplierDriver =
            new ShanhaiAutoForgeMultiplierDriver(this);

    /** 通配符样板展开出来的**原始**样板（未经神锻改写）。{@link #buildEffectivePatterns()} 由它派生生效表。 */
    private final List<IPatternDetails> rawExpandedPatterns = new ObjectArrayList<>();

    /** 展开后的全部 AE 样板，索引 = 全局槽位索引（跨通配符槽连续）。 */
    private final List<IPatternDetails> expandedPatterns = new ObjectArrayList<>();

    /** AE 样板 → 全局槽位索引。 */
    private final Object2IntMap<IPatternDetails> patternToSlotMap = new Object2IntOpenHashMap<>();

    /** 全局槽位表；每个元素对应 {@link #expandedPatterns} 的同下标。 */
    private final List<InternalSlot> internalSlots = new ObjectArrayList<>();

    /** 当前真正"有样板"的全局索引集合。 */
    private final IntSet activeSlotIndices = new IntOpenHashSet();

    /**
     * 全局索引 → 它来自哪个通配符样板槽（{@code 0 .. WILDCARD_SLOT_COUNT-1}）。
     *
     * <p>与 {@link #rawExpandedPatterns} <b>同下标同长度</b>：{@link #refreshPatterns(boolean)}
     * 按槽 0 → 9 的顺序把所有展开样板追加进去，所以「第 i 条展开样板出自哪个槽」只能在那儿记下来。
     *
     * <p>🔴 需求「<b>每块通配符样板各设一个电路</b>」的落点就是它：
     * 电路写在<b>样板物品</b>的 NBT 上，而电路缓存挂在<b>展开出来的样板</b>（{@link InternalSlot}）上，
     * 两者靠这张表对上号。缺了它就只能退化成「全机一个电路」。
     */
    private final IntList expandedPatternOwner = new IntArrayList();

    /**
     * 「实际配方数量」的<b>逻辑核</b>（全局展开样板索引 → 已缓存的不同配方集合 ＋ 就绪闸门）。
     *
     * <p>🔴 上游对应物是 {@code MEPatternBufferPartMachine.recipeMultipleCacheMap}
     * （{@code Int2ReferenceMap<ObjectSet<GTRecipe>>}）＋ {@code cacheRecipe}（{@code boolean[]}）。
     * 宿主那台（{@code MEWildcardPatternBufferPartMachine}）用的是<b>单条配方</b>的
     * {@code recipeCacheMap}、且<b>没有</b>「实际配方数量」——本机现在按<b>超级样板总成</b>那一套做。
     * 语义与逐字依据见 {@link ShanhaiPatternRecipeCache}。
     */
    protected final ShanhaiPatternRecipeCache<GTRecipe> recipeCache = new ShanhaiPatternRecipeCache<>();

    /**
     * 🔴 「实际配方数量」的<b>权威存储</b>（下标 = 通配符样板槽号）。
     *
     * <p>含义：<b>这块通配符样板要攒够多少条不同配方，它名下那批配方才允许被机器看见</b>。
     * 上游原始字节码：{@code cacheRecipe[slot] = set.size() >= cacheRecipeCount[slot]}。
     *
     * <h2>为什么是 {@code @Persisted @DescSynced}（而不是像上游那样什么都不标）</h2>
     * 上游 {@code javap -v} 实证：{@code protected final byte[] cacheRecipeCount;} <b>没有任何注解</b>
     * （同类的 {@code cacheRecipe} 有 {@code @DescSynced}、{@code keepByProduct} 有 {@code @Persisted}），
     * 它靠 {@code saveCustomPersistedData} 手写落盘、<b>不往客户端同步</b>。
     * 本机标 {@code @Persisted} ⇒ 退出重进不重置；标 {@code @DescSynced} ⇒
     * 面板取到的是服务端权威值（这正是用户第 1/2 条那一类缺陷的根因形态）。
     *
     * <p>⚠️ <b>这个数组对象本身会被交给界面控件</b>（{@code MEPatternCatalystUIManager}），
     * 控件是直接往里面写的 —— 所以绝不可以在中间再插一层拷贝。
     *
     * <p>与「每格电路」({@link #slotCircuit}) 是<b>两个不同的字段</b>，不共享任何存储。
     */
    @DescSynced
    @Persisted
    private final byte[] cacheRecipeCount = new byte[WILDCARD_SLOT_COUNT];

    /**
     * 派生位（下标 = 通配符样板槽号）：该块名下<b>有没有任何一条展开样板已就绪</b>。
     *
     * <p>它只服务一件事：样板格上那句「已缓存配方」提示是在<b>客户端</b>渲染的
     * （改造前那版是 {@code PaginationUIManager} 的 {@code isCached} 回调；本轮主页面改回原版布局后
     * 那个回调已无调用点，本字段仍照旧同步，留着给以后的界面用），而就绪状态本身是服务端运行时状态
     * （{@code recipeCache} 不落盘也不同步）。所以把「块」这一级的结论导出成一个可同步的定长数组。
     * 🔴 它是<b>单向派生</b>的（只在 {@link #refreshCacheRecipeReadyFlags()} 一处按 {@code recipeCache} 重算），
     * <b>不是</b>第二份真相。
     */
    @DescSynced
    private final boolean[] cacheRecipeReady = new boolean[WILDCARD_SLOT_COUNT];

    protected IntConsumer removeSlotFromMap = i -> {};

    protected final SuperWildcardRecipeHandlerTrait recipeHandler;

    /** 载入存档时暂存的槽位数据，等 {@link #refreshPatterns(boolean)} 展开完样板之后再按指纹对号入座。 */
    @Nullable
    private ListTag pendingSlotData;

    /** AE 终端看到的样板格：{@value #WILDCARD_SLOT_COUNT} 格，逐格对应一个通配符样板物品。 */
    private final InternalInventory internalPatternInventory = new InternalInventory() {
        @Override
        public int size() {
            return WILDCARD_SLOT_COUNT;
        }

        @Override
        public ItemStack getStackInSlot(int slotIndex) {
            return SuperWildcardPatternBufferPartMachine.this.wildcardPatternSlot.getStackInSlot(slotIndex);
        }

        @Override
        public void setItemDirect(int slotIndex, ItemStack stack) {
            SuperWildcardPatternBufferPartMachine.this.wildcardPatternSlot.setStackInSlot(slotIndex, stack);
            SuperWildcardPatternBufferPartMachine.this.wildcardPatternSlot.onContentsChanged(slotIndex);
            SuperWildcardPatternBufferPartMachine.this.onWildcardPatternChange();
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return ShanhaiWildcardCompat.isWildcardPattern(stack);
        }
    };

    public SuperWildcardPatternBufferPartMachine(IMachineBlockEntity holder, IO io) {
        super(holder, io);
        this.wildcardPatternSlot = new ItemStackTransfer(WILDCARD_SLOT_COUNT);
        this.wildcardPatternSlot.setFilter(ShanhaiWildcardCompat::isWildcardPattern);
        this.patternToSlotMap.defaultReturnValue(-1);
        // 🔴 「实际配方数量」出厂默认 1 —— 与上游构造器逐值相同：
        //   `cacheRecipeCount = new byte[maxPatternCount]; Arrays.fill(cacheRecipeCount, (byte) 1);`
        //   含义：默认「攒到 1 条就算就绪」，也就是本机改动之前的行为（零行为回归）。
        Arrays.fill(this.cacheRecipeCount, (byte) 1);
        for (int i = 0; i < WILDCARD_SLOT_COUNT; i++) {
            this.slotCircuit[i] = CIRCUIT_UNSET;
            this.catalystItems[i] = new ItemStackTransfer(CATALYST_ITEM_SLOTS);
            this.catalystFluids[i] = new FluidTransferList(java.util.stream.Stream
                    .generate(() -> (com.lowdragmc.lowdraglib.side.fluid.IFluidTransfer)
                            new FluidStorage(CATALYST_TANK_CAPACITY))
                    .limit(CATALYST_FLUID_TANKS)
                    .toList());
        }
        this.recipeHandler = new SuperWildcardRecipeHandlerTrait(this, io);
        this.getMainNode().addService(IGridTickable.class, new Ticker());
    }

    public boolean isVisibleInTerminal() {
        return !this.isHiddenTerminal;
    }

    // ------------------------------------------------------------------ 生命周期

    @Override
    public void onLoad() {
        super.onLoad();
        if (this.getLevel() instanceof ServerLevel serverLevel) {
            serverLevel.getServer().execute(() -> {
                this.refreshPatterns(true);
                this.needPatternSync = true;
            });
            // 🔴 催化剂槽的内容变化回调 —— 必须在【服务端】挂，且必须在 super.onLoad() 之后
            //    （落盘字段的还原发生在 onLoad 之前，构造器里挂会挂到被换掉的对象上）。
            //    这是上游 MEPatternBufferPartMachine#onLoad 的同款接线，见本方法注释。
            this.shanhaiWireCatalystCallbacks();
        }
    }

    /**
     * <b>把「催化剂槽内容变了」接到重算上</b> —— 逐句对齐上游
     * {@code MEPatternBufferPartMachine#onLoad()}（{@code javap -p -c} ＋
     * {@code javap -v} 的 {@code BootstrapMethods} 双向实证）。
     *
     * <h2>🔴 为什么本机必须自己接一遍（缺口的根因）</h2>
     * 上游那段接线<b>写在 {@code MEPatternBufferPartMachine.onLoad()} 里</b>，而不是写在
     * 共同基类 {@code MEPatternBufferPartMachineBase} 里。本机继承的是<b>基类</b>
     * （见类注释「二」：宿主也继承基类），所以 {@code super.onLoad()} 走的是
     * {@code MEPatternBufferPartMachineBase.onLoad()} —— <b>上游那一段一次都不会被执行</b>。
     *
     * <p>取证（{@code javap} 打在 {@code gtlcore-1.2.3.2.jar} 上）：
     * <ul>
     *   <li>{@code MEPatternBufferPartMachineBase} 里<b>没有「每槽」的</b>
     *       {@code catalystItems} / {@code catalystFluids} —— 它只有整机共享的那两份
     *       {@code sharedCatalystInventory} / {@code sharedCatalystTank}
     *       （{@code com.hepdd.gtmthings} 的 {@code CatalystItemStackHandler} / {@code CatalystFluidStackHandler}）；
     *       而且它整份 {@code javap -c} 里 {@code setOnContentsChanged} <b>命中 0 次</b>；</li>
     *   <li>反之 {@code MEPatternBufferPartMachine.onLoad()} 的字节码里有三处
     *       {@code setOnContentsChanged}：
     *       {@code InternalSlot}（样板槽，BM #6 → {@code lambda$onLoad$7} → 两个 handler 的
     *       {@code notifyListeners()}，<b>本机已在 {@code buildEffectivePatterns()} 里等价接好</b>）、
     *       {@code ItemStackTransfer}（物品催化剂，BM #7 → {@code lambda$onLoad$8} →
     *       {@code reCalculateCatalystItemMap(slot)}）、
     *       以及 {@code FluidStorage}（流体催化剂，BM #8 → {@code lambda$onLoad$9} →
     *       {@code reCalculateCatalystFluidMap(slot)}）——
     *       后两条就是本方法要补的；</li>
     *   <li>宿主 {@code MEWildcardPatternBufferPartMachine} 的整份字节码里
     *       <b>「atalyst」命中 0 次</b> ⇒ 宿主确实没有催化剂槽，也不会替我们接。</li>
     * </ul>
     *
     * <h2>下标口径（沿用「每块样板一个电路」那一套）</h2>
     * 上游 {@code catalystItems[slot]} 的下标 = 样板格号，和它 {@code internalInventory[slot]} 同一空间。
     * 本机的 {@link #catalystItems} 长度是 {@link #WILDCARD_SLOT_COUNT}（= 通配符样板槽），
     * 而 {@code internalSlots} 的下标是<b>全局展开样板索引</b>（一块样板会展开成 N 条）。
     * ⇒ 回调里传的是<b>通配符槽号</b>，由 {@link #shanhaiOnCatalystChanged(int)} 负责换算，
     * 中间绝不做下标直传。
     */
    private void shanhaiWireCatalystCallbacks() {
        int itemHooks = 0;
        int fluidHooks = 0;
        for (int wildcardSlot = 0; wildcardSlot < WILDCARD_SLOT_COUNT; wildcardSlot++) {
            final int slot = wildcardSlot;
            this.catalystItems[slot].setOnContentsChanged(() -> this.shanhaiOnCatalystChanged(slot, "物品"));
            itemHooks++;
            // 上游遍历的是 catalystFluids[slot].transfers 数组、只认 FluidStorage 那一档；
            // 本机的 9 个罐就是 FluidStorage（见构造器），与上游逐值相同。
            for (var transfer : this.catalystFluids[slot].transfers) {
                if (transfer instanceof FluidStorage storage) {
                    storage.setOnContentsChanged(() -> this.shanhaiOnCatalystChanged(slot, "流体"));
                    fluidHooks++;
                }
            }
        }
        // 🔴 「没接线」这个状态必须在日志上可分（照本机 shanhaiOnCacheRecipeCountChanged 那套三态纪律）：
        //    本机的接线只在 onLoad 的 ServerLevel 分支里发生 ⇒ 被跳过 / 抛异常时【下面这一行不会出现】。
        //    ⇒ 排查判据：日志里【没有】这一行 = 没接线；有这一行但【没有】「收到催化剂槽变化」= 接线了但回调没触发。
        LOGGER.info("[SHANHAI-WILDCARD] 催化剂槽回调已接线：物品槽 {} 条（每块 1 条）＋ 流体槽 {} 条，共 {} 条，覆盖 {} 块通配符样板。"
                        + "（日志里缺这一行 = 没接线，回调一次都不会触发）",
                itemHooks, fluidHooks, itemHooks + fluidHooks, WILDCARD_SLOT_COUNT);
    }

    @Override
    protected void update() {
        super.update();
        if (!this.buffer.isEmpty()) {
            AEUtils.reFunds(this.buffer, this.getMainNode().getGrid(), this.actionSource);
        }
        // 🔴 自动倍率的周期复读（2026-10-04）。位置放在最后：它可能触发一次 rebuildPatterns()，
        //    那件事的代价比上面那次退款大，不该挡住退款。
        this.shanhaiTickAutoForgeMultiplier();
    }

    @Override
    public void onMachineRemoved() {
        super.onMachineRemoved();
        this.clearInventory(this.wildcardPatternSlot);
    }

    // ------------------------------------------------------------------ 样板展开

    /**
     * 重新展开全部通配符样板。
     *
     * <p>与宿主的 {@code refreshPatterns} <b>逐句同构</b>，唯一差别是外面套了一层
     * {@code for (slot : 0..WILDCARD_SLOT_COUNT)} 并把样板索引改成<b>跨槽连续</b>的全局索引。
     *
     * <p>⇒ 宿主的行为 = 「槽数为 1 时的本方法」，因此这一段对宿主那台的语义<b>零偏离</b>。
     */
    private void refreshPatterns(boolean restorePersistedData) {
        for (int wildcardSlot = 0; wildcardSlot < WILDCARD_SLOT_COUNT; wildcardSlot++) {
            final ItemStack wildcardStack = this.wildcardPatternSlot.getStackInSlot(wildcardSlot);
            if (wildcardStack.isEmpty() || this.getLevel() == null) {
                continue;
            }
            final List<IPatternDetails> expanded =
                    ShanhaiWildcardCompat.expandPatterns(wildcardStack, this.getLevel());
            // 先记「这批展开样板出自哪个通配符槽」，再追加 —— 两张表必须同下标同长度
            // （buildEffectivePatterns() 就是按下标把 InternalSlot 建出来的）。
            for (int ignored = 0; ignored < expanded.size(); ignored++) {
                this.expandedPatternOwner.add(wildcardSlot);
            }
            this.rawExpandedPatterns.addAll(expanded);
        }
        this.buildEffectivePatterns();
        if (restorePersistedData) {
            this.restorePersistedSlotData();
        }
        // 🔴 顺序不许动：必须在 restorePersistedSlotData() 【之后】。
        //    InternalSlot.deserializeNBT 内部会调 SlotCacheManager.clearAllCaches()，
        //    而 SlotCacheManager.serializeNBT 【不写】 circuitCache ⇒ 还原存档会顺手把电路缓存擦掉。
        //    ⇒ 每槽电路必须在还原结束之后重新应用一次（硬约束②）。
        this.applySlotCircuits();
        // 每槽催化剂同理：InternalSlot 里那两份 map 会随 deserializeNBT 从 catalystInventory 恢复，
        // 但 catalystItems 槽里的东西也可能被 loadCustomPersistedData 换过 ⇒ 再对齐一次。
        this.reCalculateAllCatalysts();
    }

    /**
     * 把「每块样板自己的电路号」应用到对应的槽位电路缓存上。
     *
     * <h2>🔴 2026-10-01：这条路已经<b>用无头专服实测过</b>，不是读字节码推的</h2>
     * 实测装置：{@code temp\wildcard-circuit-probe}（探针 mod，在真专服里对着
     * {@code gtceu:me_wildcard_pattern_buffer} 的真实
     * {@code MEWildcardPatternBufferPartMachine} 取 {@code InternalSlot}）。
     * 原始读数见交付报告「验证题」一节，结论：
     * <pre>
     * 往 InternalSlot.add(虚拟原料(payload=编程电路7), 1) 之后：
     *   SlotCacheManager.getCircuitCache()  = -1 → 7      （setCircuitCache 真的被执行）
     *   SlotCacheManager.getCircuitStack()  = 1 programmed_circuit
     *   handleItemInternal(empty, 7, true)  = true        （电路 7 的配方由此成立）
     *   handleItemInternal(empty, 10, true) = false       （不匹配就是硬拒绝）
     *   没有虚拟原料时 handleItemInternal(empty, 7) = false
     * </pre>
     * ⇒ 上游「每槽电路」的机制层（{@code SlotCacheManager.setCircuitCache} ＋
     * {@code InternalSlot.handleItemInternal} 硬比较）在<b>我们继承的这个基类上完全生效</b>。
     *
     * <h2>三条硬约束在这里的落点</h2>
     * <ol>
     *   <li>编号范围 1–32：{@link ShanhaiSlotCircuit#normalize} 兜住，越界一律折成「没有电路」；
     *       「没有电路」走 {@code clearCircuitCache()}。<b>绝不</b>把 0 / -1 传给
     *       {@code setCircuitCache(int)} —— 实测 {@code IntCircuitBehaviour.stack(0)} 在
     *       1.20.1 上<b>不抛</b>（只有 &lt; 0 或 &gt; 32 才抛），所以 0 不是「安全哨兵」，
     *       它会被写成一个合法电路 0 并让「没设过」与「设成 0」不可分。</li>
     *   <li>存档重载擦除：本方法每次 {@link #refreshPatterns(boolean)} 都会被调，
     *       且位置在还原之后 —— 重载后电路照旧生效
     *       （实测 {@code InternalSlot.serializeNBT} 写 {@code virtualCircuit}、
     *       {@code deserializeNBT} 末端 {@code setCircuitCache} 灌回，21 → 21）。</li>
     *   <li>空槽 active：本方法<b>只</b>动 {@code cacheManager}，不碰 {@code itemInventory}。</li>
     * </ol>
     */
    private void applySlotCircuits() {
        if (this.isRemote()) {
            return;
        }
        for (int index = 0; index < this.internalSlots.size(); index++) {
            final InternalSlot slot = this.internalSlots.get(index);
            if (slot == null) {
                continue;
            }
            final int circuit = this.circuitOfExpandedPattern(index);
            if (ShanhaiSlotCircuit.actionFor(circuit) == ShanhaiSlotCircuit.Action.SET) {
                slot.getCacheManager().setCircuitCache(circuit);
            } else {
                slot.getCacheManager().clearCircuitCache();
            }
        }
    }

    /** 第 {@code index} 条展开样板应当用的电路（规范值，{@code 0} = 没有）。 */
    private int circuitOfExpandedPattern(int index) {
        if (index < 0 || index >= this.expandedPatternOwner.size()) {
            return ShanhaiSlotCircuit.NONE;
        }
        final int wildcardSlot = this.expandedPatternOwner.getInt(index);
        return this.shanhaiSlotCircuit(wildcardSlot);
    }

    /**
     * 由 {@link #rawExpandedPatterns} 生成真正生效的样板表。
     *
     * <p>「神锻样板模式」开着时，每条样板都过一遍 {@link ShanhaiForgePatternMode#rewrite}
     * ——这就是需求④那个按钮<b>背后的功能</b>，不是只画了个开关。
     */
    private void buildEffectivePatterns() {
        this.expandedPatterns.clear();
        this.patternToSlotMap.clear();
        this.activeSlotIndices.clear();
        this.internalSlots.clear();
        final boolean forgeMode = this.isForgePatternModeEnabled();
        // 🔴 生效倍率的唯一读点。顺序不许动：必须在取 multiplier 【之前】重读一次控制器
        //    （关掉自动时它会把读数清成 NO_READ、把手填值交给下面那个方法）。
        this.shanhaiRefreshAutoForgeMultiplier();
        final int multiplier = this.getEffectiveForgePatternMultiplier();
        int index = 0;
        for (IPatternDetails raw : this.rawExpandedPatterns) {
            final IPatternDetails effective =
                    ShanhaiForgePatternMode.rewrite(raw, this.getLevel(), forgeMode, multiplier);
            this.expandedPatterns.add(effective);
            this.patternToSlotMap.put(effective, index);
            this.activeSlotIndices.add(index);
            final InternalSlot slot = new ShanhaiPatternBufferInternalSlot(index);
            slot.setOnContentsChanged(this.recipeHandler::notifyHandlers);
            this.internalSlots.add(slot);
            index++;
        }
        // 🔴 下标表重建之后必须重新绑定逻辑核：它持有的是「全局索引 → 通配符槽」这张表，
        //   而这张表每次展开样板都会重算（bind() 同时会清空上一轮的缓存与就绪位）。
        this.recipeCache.bind(this.expandedPatternOwner.toIntArray(), this.cacheRecipeCount);
        this.refreshCacheRecipeReadyFlags();
    }

    /**
     * 按逻辑核重算「块」级就绪派生位（{@link #cacheRecipeReady}）。
     *
     * <p>🔴 唯一的重算点 —— 别在别处直接写这个数组，否则它就变成第二份真相了。
     */
    private void refreshCacheRecipeReadyFlags() {
        for (int owner = 0; owner < WILDCARD_SLOT_COUNT; owner++) {
            this.cacheRecipeReady[owner] = this.recipeCache.isOwnerReady(owner);
        }
    }

    /** 第 {@code index} 条展开样板出自哪一块通配符样板；越界 ⇒ {@code -1}。 */
    private int ownerWildcardSlot(int index) {
        return index >= 0 && index < this.expandedPatternOwner.size()
                ? this.expandedPatternOwner.getInt(index)
                : -1;
    }

    /**
     * 由 {@link #catalystItems} / {@link #catalystFluids} 重算某一槽的催化剂表。
     *
     * <p>与上游 {@code MEPatternBufferPartMachine.reCalculateCatalystItemMap/FluidMap}
     * <b>逐句同构</b>（javap -c 对照），两种槽内容语义不同：
     * <ul>
     *   <li><b>普通物品</b> ⇒ 累加进 {@code itemCatalystInventory}（「不消耗」的原料）；</li>
     *   <li><b>虚拟原料</b> ⇒ 走 {@code configuredVirtualItems} / {@code configuredVirtualFluids}，
     *       由 {@code testVirtualItemInternal} / {@code stripVirtuallySuppliedItems}
     *       把该原料整条从配方要求里剥掉。</li>
     * </ul>
     */
    private void reCalculateCatalyst(int index) {
        if (index < 0 || index >= this.internalSlots.size()) {
            return;
        }
        // 🔴 下标口径（本轮顺手修正，之前是混的）：`index` 是**全局展开样板索引**，
        //   而 catalystItems / catalystFluids 是**按通配符样板槽**存的（长度 = WILDCARD_SLOT_COUNT）。
        //   一块通配符样板会展开成 N 条 AE 样板，两个下标空间并不相等 ⇒ 必须经 ownerWildcardSlot 换算。
        //   之前直接写 `catalystItems[index]`，在「第 0 块样板展开出 >1 条」时会把别的块的催化剂读进来。
        final int owner = this.ownerWildcardSlot(index);
        if (owner < 0 || owner >= WILDCARD_SLOT_COUNT) {
            return;
        }
        final InternalSlot slot = this.internalSlots.get(index);
        if (!(slot instanceof ShanhaiPatternBufferInternalSlot catalystSlot)) {
            return;
        }
        final Object2LongMap<AEItemKey> items = catalystSlot.getItemCatalystInventory();
        items.clear();
        slot.getConfiguredVirtualItems().clear();
        slot.getConfiguredVirtualFluids().clear();
        final ItemStackTransfer itemTransfer = this.catalystItems[owner];
        for (int i = 0; i < itemTransfer.getSlots(); i++) {
            final ItemStack stack = itemTransfer.getStackInSlot(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (org.gtlcore.gtlcore.common.data.GTLItems.VIRTUAL_INGREDIENT.isIn(stack)) {
                final AEItemKey payloadItem =
                        org.gtlcore.gtlcore.common.item.VirtualIngredientBehavior.payloadItemKey(stack);
                if (payloadItem != null) {
                    slot.getConfiguredVirtualItems().add(payloadItem);
                }
                final AEFluidKey payloadFluid =
                        org.gtlcore.gtlcore.common.item.VirtualIngredientBehavior.payloadFluidKey(stack);
                if (payloadFluid != null) {
                    slot.getConfiguredVirtualFluids().add(payloadFluid);
                }
            } else {
                items.mergeLong(AEItemKey.of(stack), stack.getCount(), Long::sum);
            }
        }
        final FluidTransferList fluidTanks = this.catalystFluids[owner];
        final Object2LongMap<AEFluidKey> fluids = catalystSlot.getFluidCatalystInventory();
        fluids.clear();
        for (int i = 0; i < fluidTanks.getTanks(); i++) {
            final FluidStack fluid = fluidTanks.getFluidInTank(i);
            if (fluid.isEmpty()) {
                continue;
            }
            fluids.mergeLong(AEFluidKey.of(fluid.getFluid()), fluid.getAmount(), Long::sum);
        }
        // 🔴 上游 reCalculateCatalystItemMap / reCalculateCatalystFluidMap 的【末尾两句】
        //    （javap -c 原文）：
        //        removeSlotFromGTRecipeCache(index);
        //        internalInventory[index].getOnContentsChanged().run();
        //    而 removeSlotFromGTRecipeCache(slot) 自己是【四句】：
        //        cacheRecipe[slot] = false;
        //        recipeMultipleCacheMap.remove(slot);
        //        removeSlotFromMap.accept(slot);
        //        notifyProxySlotRemoved(slot);
        //
        //    ⚠️ 为什么「作废配方缓存」是【必须】的，不是顺手加的：
        //      机器「看得见哪些配方」走的是 MEPatternRecipeHandlePart#getCachedGTRecipe()
        //      （javap 实证：GTRecipeLookupMixin#getRecipeIterator 只收集这一份），
        //      而缓存是**按放催化剂当时的那套催化剂**攒出来的；
        //      只更新催化剂表、不作废缓存 ⇒ 新放进去的催化剂带来的新配方【永远不会被攒进来】。
        //
        //    🔴 前两句（cacheRecipe=false ＋ recipeMultipleCacheMap.remove）**不在本方法里**，
        //       而在 {@link #shanhaiOnCatalystChanged(int)} 里按【通配符槽】整体作一次 ——
        //       因为本机的催化剂是「一块样板一个」，而本方法的下标是**展开样板**下标，
        //       一块会展开成 N 条（N 可达上百），在这里逐条作会变成 O(N²) 的无谓开销，
        //       而语义完全等价（resetOwner 一次就覆盖了该块名下的全部展开样板）。
        //       下游那两句（removeSlotFromMap / notifyProxySlotRemoved）是**按展开样板下标**的，
        //       必须留在本方法里逐条做 —— 与上游一字不差。
        this.removeSlotFromMap.accept(index);
        this.notifyProxySlotRemoved(index);
        final Runnable onChanged = slot.getOnContentsChanged();
        if (onChanged != null) {
            onChanged.run();
        }
    }

    /**
     * 全部槽的催化剂表都重算一次（样板表变了之后调用）。
     *
     * <p>⚠️ <b>它<b>不</b>负责作废配方缓存</b>：唯一的调用点是 {@link #refreshPatterns(boolean)}
     * 的末尾，而那之前已经跑过 {@code buildEffectivePatterns()} → {@code recipeCache.bind(...)}，
     * 那份缓存已经被清空 ⇒ 这里再作一次是空操作。玩家改催化剂走的是
     * {@link #shanhaiOnCatalystChanged(int)}，作废在那儿做（见该方法的注释）。
     */
    private void reCalculateAllCatalysts() {
        for (int i = 0; i < this.internalSlots.size(); i++) {
            this.reCalculateCatalyst(i);
        }
    }

    /**
     * 全量重算：把槽内物料退回 buffer、清账、重新展开并重新按神锻模式改写。
     *
     * <p>两条触发路径共用它，保证「样板换了」和「神锻模式变了」走的是<b>同一条</b>代码 ——
     * 免得出现「改了模式但槽位物料还挂在旧样板上」这种分裂状态。
     */
    private void rebuildPatterns() {
        if (this.isRemote()) {
            return;
        }
        for (InternalSlot slot : this.internalSlots) {
            this.refundSlot(slot.getItemInventory(), slot.getFluidInventory());
        }
        AEUtils.reFunds(this.buffer, this.getMainNode().getGrid(), this.actionSource);
        this.clearPatternData();
        this.refreshPatterns(false);
        this.needPatternSync = true;
    }

    /** 样板槽内容变化：把已有物料全部退回 buffer，清账，再重新展开。 */
    protected void onWildcardPatternChange() {
        this.rebuildPatterns();
    }

    private void clearPatternData() {
        final IntIterator it = this.activeSlotIndices.iterator();
        while (it.hasNext()) {
            final int slot = it.nextInt();
            this.removeSlotFromMap.accept(slot);
            this.notifyProxySlotRemoved(slot);
        }
        this.recipeCache.clear();
        this.refreshCacheRecipeReadyFlags();
        this.internalSlots.clear();
        this.expandedPatterns.clear();
        this.rawExpandedPatterns.clear();
        this.expandedPatternOwner.clear();
        this.patternToSlotMap.clear();
        this.activeSlotIndices.clear();
    }

    // --------------------------------------------------- 每块样板自己的电路（需求：中键设置）

    /**
     * 该通配符槽当前设定的电路（规范值；{@link ShanhaiSlotCircuit#NONE} = 没设过）。
     *
     * <p>🔴 这是<b>唯一</b>的读入点 —— 界面、应用、日志全部走它，不存在第二份影子状态。
     * 字段带 {@code @DescSynced}，所以客户端读到的是服务端权威值
     * （这正是修掉用户第 1/2 条「重置为 0」「显示上一格」的关键）。
     */
    public int shanhaiSlotCircuit(int wildcardSlot) {
        if (wildcardSlot < 0 || wildcardSlot >= WILDCARD_SLOT_COUNT) {
            return ShanhaiSlotCircuit.NONE;
        }
        return ShanhaiSlotCircuit.normalize(true, this.slotCircuit[wildcardSlot]);
    }

    /** 该槽<b>是否设过</b>电路（供界面区分「没设过」与「设成 N」）。 */
    public boolean shanhaiHasSlotCircuit(int wildcardSlot) {
        return this.shanhaiSlotCircuit(wildcardSlot) != ShanhaiSlotCircuit.NONE;
    }

    /**
     * 服务端入口：把「第 {@code wildcardSlot} 块样板」的电路号设为 {@code value}。
     *
     * <p>{@code value == 0}（{@link ShanhaiSlotCircuit#NONE}）⇒ 记回
     * {@link #CIRCUIT_UNSET}（= 恢复「没设过」，沿用左侧共享电路）。
     * 写完立刻 {@link #rebuildPatterns()} 让改动当场生效（与换样板同一条路径），
     * 那条路径末尾会调 {@link #applySlotCircuits()} 把值真正灌进
     * {@code SlotCacheManager.setCircuitCache}。
     *
     * @return 真的改动了才返回 {@code true}
     */
    public boolean shanhaiApplySlotCircuit(int wildcardSlot, int value) {
        if (this.isRemote() || wildcardSlot < 0 || wildcardSlot >= WILDCARD_SLOT_COUNT) {
            LOGGER.info("[SHANHAI-WILDCARD] 设电路被拒（早期返回）：格={} 值={} 原因={}",
                    wildcardSlot + 1, value,
                    this.isRemote() ? "这是客户端侧（isRemote）" : "格号越界");
            return false;
        }
        final ItemStack current = this.wildcardPatternSlot.getStackInSlot(wildcardSlot);
        if (current.isEmpty() || !ShanhaiWildcardCompat.isWildcardPattern(current)) {
            LOGGER.info("[SHANHAI-WILDCARD] 设电路被拒（早期返回）：格={} 值={} 原因=该格没有通配符样板",
                    wildcardSlot + 1, value);
            return false;
        }
        final int normalized = ShanhaiSlotCircuit.normalize(true, value);
        final int previous = this.slotCircuit[wildcardSlot];
        if (previous == normalized) {
            LOGGER.info("[SHANHAI-WILDCARD] 设电路被拒（早期返回）：格={} 值={} 原因=值没变（当前就是 {}）",
                    wildcardSlot + 1, value, normalized);
            return false;
        }
        this.slotCircuit[wildcardSlot] = normalized;
        LOGGER.info("[SHANHAI-WILDCARD] 第 {} 格通配符样板的电路：{} → {}（已存入本机 slotCircuit 并重算；"
                        + "重算末尾会灌进该格每条展开样板的 SlotCacheManager）",
                wildcardSlot + 1,
                previous == CIRCUIT_UNSET ? "未设置" : Integer.toString(previous),
                normalized == CIRCUIT_UNSET ? "未设置" : Integer.toString(normalized));
        this.rebuildPatterns();
        return true;
    }

    // ------------------------------------------------------- 神锻样板模式（需求④）

    /**
     * 神锻样板模式是否开启。
     *
     * <p>⚠️ 名字与上游 {@code MESuperPatternBufferPartMachine.isFOAModeEnabled()} 同义，
     * 但**不带重载**（本工程铁律：凡可能被 KubeJS 碰到的 Java 方法一律不留重载）。
     * 本方法实际不供 KubeJS 使用，保持命名纪律即可。
     */
    public boolean isForgePatternModeEnabled() {
        return this.forgePatternMode;
    }

    /** 切换神锻样板模式；值真的变了才重算（避免 GUI 每次刷新都触发一次全量重建）。 */
    public void setForgePatternModeEnabled(boolean enabled) {
        if (this.forgePatternMode != enabled) {
            this.forgePatternMode = enabled;
            this.rebuildPatterns();
        }
    }

    /** 神锻模式倍率（上游 UI 范围 1–30）。 */
    public int getForgePatternMultiplier() {
        return this.forgePatternMultiplier;
    }

    /** 设置倍率；值真的变了、且模式开着才重算。 */
    public void setForgePatternMultiplier(int multiplier) {
        final int clamped = Math.max(ShanhaiForgePatternMode.MIN_MULTIPLIER,
                Math.min(ShanhaiForgePatternMode.MAX_MULTIPLIER, multiplier));
        if (this.forgePatternMultiplier != clamped) {
            this.forgePatternMultiplier = clamped;
            if (this.forgePatternMode) {
                this.rebuildPatterns();
            }
        }
    }

    // ------------------------------------------------------- 自动倍率（需求：读控制器/模块）

    /** 自动倍率开关是否打开（默认开，见字段注释）。 */
    public boolean isAutoForgeMultiplierEnabled() {
        return this.autoForgeMultiplier;
    }

    /**
     * 切换自动倍率；值真的变了才重算 —— 与 {@link #setForgePatternModeEnabled(boolean)} 同款。
     *
     * <p>🔴 必须重算：关掉时要让手填值<b>立刻</b>生效（用户验收步骤③「关开关 ⇒ 手填值立刻生效」），
     * 打开时要让读到的值立刻生效。走的是与「玩家手改倍率」完全相同的那条路径
     * （{@code rebuildPatterns()}），不另开岔路。
     */
    public void setAutoForgeMultiplierEnabled(boolean enabled) {
        if (this.autoForgeMultiplier != enabled) {
            this.autoForgeMultiplier = enabled;
            this.rebuildPatterns();
        }
    }

    /**
     * 最近一次从控制器/模块读到的倍率（详见 {@link #autoForgeMultiplierRead}）；
     * {@link ShanhaiAutoForgeMultiplier#NO_READ} = 没读到。
     */
    public int shanhaiAutoForgeMultiplierRead() {
        return this.autoForgeMultiplierRead;
    }

    /**
     * 读取标注：读到时是<b>来源</b>（{@code SOURCE_*}），读不到时是<b>原因</b>（{@code REASON_*}）。
     *
     * <p>{@link AutoForgeMultiplierHost} 要求的名字是 {@code shanhaiAutoForgeMultiplierNote()}；
     * 旧名 {@link #shanhaiAutoForgeMultiplierReason()} 保留为别名（对旧调用点是同一件事）。
     */
    @Override
    public String shanhaiAutoForgeMultiplierNote() {
        return this.autoForgeMultiplierReason;
    }

    /** 旧名别名（等价于 {@link #shanhaiAutoForgeMultiplierNote()}）。 */
    public String shanhaiAutoForgeMultiplierReason() {
        return this.autoForgeMultiplierReason;
    }

    /** 把「读数 + 标注」写进同步字段 —— <b>驱动是唯一的写点</b>。 */
    @Override
    public void shanhaiStoreAutoForgeMultiplier(int read, String note) {
        this.autoForgeMultiplierRead = read;
        this.autoForgeMultiplierReason = note;
    }

    /**
     * 🔴 <b>生效倍率 —— 全机唯一的口径</b>（用户验收步骤⑤点名的那个二选一，本机选的是「新增方法」）。
     *
     * <pre>
     *   {@link #getForgePatternMultiplier()}    = 【手填值】（输入框里那个数；语义一个字都没改）
     *   getEffectiveForgePatternMultiplier()    = 【真正传给 rewrite(...) 的那个数】
     *       自动开 ⇒ 读到多少就是多少；【读不到 ⇒ ×1】（用户 2026-10-04 修正④）
     *       自动关 ⇒ 手填值
     * </pre>
     *
     * <h2>谁在读它（说清楚，免得后来人接错线）</h2>
     * <ul>
     *   <li>{@link #buildEffectivePatterns()} —— <b>唯一</b>把它交给
     *       {@code ShanhaiForgePatternMode.rewrite(raw, level, forgeMode, multiplier)} 的地方
     *       （= 算法侧）；</li>
     *   <li>{@link ForgePatternConfigurator} 的面板第一行（= 界面侧，经
     *       {@link #shanhaiAutoForgeMultiplierText()}）；</li>
     *   <li>{@link ShanhaiAutoForgeMultiplierDriver#tick()}（= 变化检测）；</li>
     *   <li>日志行（= 日志侧）。</li>
     * </ul>
     * ⇒ 界面说 23、日志说 23、算法用的就是 23，<b>不存在第二份真相</b>。
     */
    public int getEffectiveForgePatternMultiplier() {
        return ShanhaiAutoForgeMultiplier.effective(
                this.autoForgeMultiplier,
                this.autoForgeMultiplierRead,
                this.forgePatternMultiplier,
                ShanhaiForgePatternMode.MIN_MULTIPLIER,
                ShanhaiForgePatternMode.MAX_MULTIPLIER);
    }

    /** 面板第一行（唯一实现在 {@link ShanhaiAutoForgeMultiplierDriver#text()}）。 */
    @Override
    public String shanhaiAutoForgeMultiplierText() {
        return this.shanhaiAutoForgeMultiplierDriver().text();
    }

    /** 面板第二行（唯一实现在 {@link ShanhaiAutoForgeMultiplierDriver#reasonText()}）。 */
    @Override
    public String shanhaiAutoForgeMultiplierReasonText() {
        return this.shanhaiAutoForgeMultiplierDriver().reasonText();
    }

    // ------------------------------------------------------- AutoForgeMultiplierHost 的其余部分
    //
    // 🔴 这些方法是「两台机器不一样的那几件事」——共享逻辑（Driver/Panel）只认它们，
    //    所以判定/文案/日志**只有一份**（用户 2026-10-04 追加 B 的硬要求）。

    /** 本机那份驱动（持有日志闸门与节流计数）。 */
    @Override
    public ShanhaiAutoForgeMultiplierDriver shanhaiAutoForgeMultiplierDriver() {
        return this.autoForgeMultiplierDriver;
    }

    /** 客户端侧还是服务端侧。 */
    @Override
    public boolean shanhaiIsRemote() {
        return this.isRemote();
    }

    /**
     * 本仓室所在多方块的控制器列表。
     *
     * <p>{@code MultiblockPartMachine} 上那个方法（{@code javap} 实证签名
     * {@code public List<IMultiController> getControllers()}）—— 一个仓室可以同时属于多座多方块
     * （共享仓室），所以是 List；<b>逐个试</b>，谁能读出额外产出倍率就用谁。
     */
    @Override
    public List<?> shanhaiControllers() {
        return this.getControllers();
    }

    /** 已经落地的生效倍率（本机从同步读数算出来）。 */
    @Override
    public int shanhaiAppliedForgeMultiplier() {
        return this.getEffectiveForgePatternMultiplier();
    }

    /**
     * 把生效倍率落地 —— 本机走 {@link #rebuildPatterns()}（它按已同步的读数重算，
     * 与「玩家手改倍率」同一条路径；入参只用于日志）。
     */
    @Override
    public void shanhaiApplyForgeMultiplier(int multiplier) {
        this.rebuildPatterns();
    }

    /** 日志前缀。 */
    @Override
    public String shanhaiLogTag() {
        return "[SHANHAI-WILDCARD]";
    }

    /**
     * <b>把控制器/模块上的倍率刷新进同步字段</b>（并打限流日志）—— 转发给共享驱动。
     *
     * <p>⚠️ 2026-10-04 追加 B 之后这里<b>一行逻辑都没有了</b>：探测、判定、文案、限流日志
     * 全部搬到 {@link ShanhaiAutoForgeMultiplierDriver}，上游那台「超级样板总成」走的是<b>同一份</b>
     * （用户逐字：「不许出现第二份实现」）。
     *
     * <h2>怎么拿到控制器（留给后来人）</h2>
     * 本机继承链上那个方法叫 {@code getControllers()}（{@code MultiblockPartMachine} 上，
     * {@code javap} 实证签名 {@code public List<IMultiController> getControllers()}）——
     * 一个仓室可以同时属于多座多方块（共享仓室），所以是 List；
     * <b>逐个试</b>，谁能读出额外产出倍率就用谁（四种来源见
     * {@link ShanhaiForgeMultiplierReader}），都读不出就返回第一条失败原因。
     * 返回的 {@code List} 里装的是 {@code IMultiController}，其实现就是那台控制器机器本身
     * （{@code MultiblockControllerMachine}：伪神之锻炉、<b>原始终焉引擎</b>，以及追加 A 之后的
     * <b>模块机器</b>）⇒ {@link ShanhaiForgeMultiplierReader#read(Object, int, int)} 直接收
     * {@link Object}，<b>gtladditions 的类型不出现在编译期签名里</b>。
     */
    private ShanhaiForgeMultiplierReader.Result shanhaiRefreshAutoForgeMultiplier() {
        return this.shanhaiAutoForgeMultiplierDriver().refresh();
    }

    // ⚠️ 2026-10-04 追加 B：原 `shanhaiLogAutoForgeMultiplier` 与 `suppressedSuffix` 已整体搬进
    //    {@link ShanhaiAutoForgeMultiplierDriver#log}／{@code suppressedSuffix}（同一份实现两台机器共用）。
    //    这里刻意不留副本 —— 用户逐字：「不许出现第二份实现」。


    /**
     * <b>周期复读控制器倍率，变了就整机重算</b>（{@link #update()} 里每 tick 调一次）—— 转发给共享驱动。
     *
     * <h2>为什么必须「周期」而不是「只读一次」</h2>
     * 🔴 {@code javap -c} 实证（并已写成自检里的断言）：
     * {@code getRecipeOutputMultiply() = base × 递归反演加成}，其中
     * {@code base = 1 + 14 × (min(runningSecs,14400)/14400)²} ∈ <b>[1, 15]</b>、
     * 递归反演那个因子只可能是 1 或 2。
     * ⇒ 它是<b>随这台机器的运行时间从 1 爬到 15</b> 的（每十几分钟变一档）。
     * 若只在展开样板时读一次，玩家会看到「开关开着、数却永远停在 1」——
     * 那是「活的界面上放死数据」，本工程红线。
     * （⛔ 旧句曾写成「0 秒 = 15，跑满 4 小时 = 29」—— 那是我把 {@code Math.pow} 的两个操作数
     * 读反了算出来的错值，2026-10-04 已订正，详见 {@code ShanhaiForgeMultiplierReader} §三。）
     *
     * <h2>三条安全阀（缺一条就可能变成抖动源）—— 现在都住在 Driver 里</h2>
     * <ol>
     *   <li><b>每 100 tick 才探一次</b>（≈5 秒）；</li>
     *   <li><b>只有「已落地的生效值」真的变了才动手</b>；</li>
     *   <li><b>落地过一次之后 200 tick 内不再动</b>（≈10 秒）—— 万一上游那个数在震荡，
     *       也不会把机器拖进重建循环。</li>
     * </ol>
     *
     * <h2>代价（如实写）</h2>
     * 走的是与「玩家手改倍率」<b>完全相同</b>的 {@link #rebuildPatterns()}：
     * 会把槽内物料退回 ME buffer、清空配方缓存再重新展开。
     * ⇒ 在伪神锻实际运行的情况下，<b>每十几分钟会出现一次这样的整机重算</b>
     * （数值每变一档一次，见上）。这是「自动跟随」的必然代价，不粉饰。
     */
    private void shanhaiTickAutoForgeMultiplier() {
        this.shanhaiAutoForgeMultiplierDriver().tick();
    }

    // ------------------------------------------------------------------ 基类钩子

    @Nullable
    @Override
    protected Integer getSlotIndexForPattern(IPatternDetails pattern) {
        final int slot = this.patternToSlotMap.getInt(pattern);
        return slot >= 0 ? slot : null;
    }

    @Override
    protected int getInternalSlotCount() {
        return this.internalSlots.size();
    }

    @Override
    protected InternalSlot getInternalSlot(int index) {
        return this.internalSlots.get(index);
    }

    @Override
    protected boolean hasRecipeCacheInSlot(int slotIndex) {
        // 🔴 上游同一处是 `return cacheRecipe[slotIndex];`（javap -c 实证），
        //   而宿主那台是 `recipeCacheMap.containsKey(slotIndex)`（永远等价于「阈值 = 1」）。
        //   本机改成「按玩家设的实际配方数量判定就绪」—— 这是「设了值真的生效」的落点之一：
        //   未就绪的槽会继续留在 getActiveAndUnCachedSlots() 里被反复试配。
        return this.recipeCache.isReady(slotIndex);
    }

    @Override
    protected boolean hasPatternInSlot(int slotIndex) {
        return this.activeSlotIndices.contains(slotIndex);
    }

    @NotNull
    @Override
    public ManagedFieldHolder getFieldHolder() {
        return MANAGED_FIELD_HOLDER;
    }

    @NotNull
    @Override
    protected SuperWildcardMEPatternTrait createMETrait() {
        return new SuperWildcardMEPatternTrait(this);
    }

    @Override
    public Pair<IMERecipeHandlerTrait<Ingredient, ItemStack>, IMERecipeHandlerTrait<FluidIngredient, FluidStack>> getMERecipeHandlerTraits() {
        return Pair.of(this.recipeHandler.getMeItemHandler(), this.recipeHandler.getMeFluidHandler());
    }

    // ------------------------------------------------------------------ 存档

    @Override
    public void saveCustomPersistedData(@NotNull CompoundTag tag, boolean forDrop) {
        super.saveCustomPersistedData(tag, forDrop);
        final ListTag entries = new ListTag();
        for (int index = 0; index < this.internalSlots.size(); index++) {
            final InternalSlot slot = this.internalSlots.get(index);
            if (!slot.isActive()) {
                continue;
            }
            final CompoundTag entry = new CompoundTag();
            entry.putInt("index", index);
            entry.put("outputs", AEUtils.createListTag(AEKey::toTagGeneric, this.patternOutputMap(index)));
            entry.put("slot", slot.serializeNBT());
            entries.add(entry);
        }
        if (!entries.isEmpty()) {
            tag.put(NBT_SLOTS, entries);
        }
        // 每槽电路（用户第 1/2 条）：存本机数组 —— 这一份带 @DescSynced，界面读的就是它。
        // ⚠️ 源码里一律写**官方名**（getIntArray / putIntArray）：本工程的编译期是官方名的
        //    parchment joined jar，构建时由 ForgeGradle 的 reobf 把它们重映射成 SRG
        //    （实证：上一轮已部署的 shanhai-0.1.0.jar 里，我们源码写的 getTag/getInt/contains
        //     在字节码里是 m_41783_ / m_128451_ / m_128425_，说明这条重映射链是好的）。
        //    ⇒ **不要**在源码里直接写 m_128385_ 这类 SRG 名：它对官方名 jar 不可见，编译不过。
        tag.putIntArray(NBT_SLOT_CIRCUIT, this.slotCircuit);
        // 每槽催化剂槽（用户第 5 条）：上游同样是「机器自己存」，
        // 因为 InternalSlot 里那两份 map 只是 catalystItems 的派生量。
        final ListTag catalystItemTags = new ListTag();
        final ListTag catalystFluidTags = new ListTag();
        for (int i = 0; i < WILDCARD_SLOT_COUNT; i++) {
            catalystItemTags.add(this.catalystItems[i].serializeNBT());
            catalystFluidTags.add(this.catalystFluids[i].serializeNBT());
        }
        tag.put(NBT_CATALYST_ITEMS, catalystItemTags);
        tag.put(NBT_CATALYST_FLUIDS, catalystFluidTags);
    }

    @Override
    public void loadCustomPersistedData(@NotNull CompoundTag tag) {
        super.loadCustomPersistedData(tag);
        this.pendingSlotData = tag.getList(NBT_SLOTS, 10);
        final int[] circuits = tag.getIntArray(NBT_SLOT_CIRCUIT);
        for (int i = 0; i < WILDCARD_SLOT_COUNT && i < circuits.length; i++) {
            this.slotCircuit[i] = circuits[i];
        }
        final ListTag catalystItemTags = tag.getList(NBT_CATALYST_ITEMS, 10);
        final ListTag catalystFluidTags = tag.getList(NBT_CATALYST_FLUIDS, 10);
        for (int i = 0; i < WILDCARD_SLOT_COUNT; i++) {
            if (i < catalystItemTags.size()) {
                this.catalystItems[i].deserializeNBT(catalystItemTags.getCompound(i));
            }
            if (i < catalystFluidTags.size()) {
                this.catalystFluids[i].deserializeNBT(catalystFluidTags.getCompound(i));
            }
        }
    }

    /** 展开结束后把存档里的槽内物料按「全局索引 + 样板输出指纹」对号入座。 */
    private void restorePersistedSlotData() {
        final ListTag entries = this.pendingSlotData;
        this.pendingSlotData = null;
        if (entries == null || entries.isEmpty()) {
            return;
        }
        for (int i = 0; i < entries.size(); i++) {
            final CompoundTag entry = entries.getCompound(i);
            final int index = entry.getInt("index");
            if (!entry.contains("slot", 10)) {
                continue;
            }
            final CompoundTag slotTag = entry.getCompound("slot");
            boolean matched = false;
            if (index >= 0 && index < this.internalSlots.size()) {
                final Object2LongMap<AEKey> stored = new Object2LongOpenHashMap<>();
                AEUtils.loadInventory(entry.getList("outputs", 10), AEKey::fromTagGeneric, stored);
                if (stored.equals(this.patternOutputMap(index))) {
                    this.internalSlots.get(index).deserializeNBT(slotTag);
                    matched = true;
                }
            }
            if (!matched) {
                // 指纹对不上（样板被换过 / 索引漂移）⇒ 内容退回 buffer，绝不静默丢弃。
                final InternalSlot orphan = new InternalSlot(-1);
                orphan.deserializeNBT(slotTag);
                this.refundSlot(orphan.getItemInventory(), orphan.getFluidInventory());
            }
        }
        AEUtils.reFunds(this.buffer, this.getMainNode().getGrid(), this.actionSource);
    }

    /** 全局索引对应样板的**输出**指纹（与宿主 persistenceHelper 的 PatternSignature 同义，只取输出半）。 */
    private Object2LongMap<AEKey> patternOutputMap(int index) {
        final Object2LongOpenHashMap<AEKey> map = new Object2LongOpenHashMap<>();
        if (index < 0 || index >= this.expandedPatterns.size()) {
            return map;
        }
        for (appeng.api.stacks.GenericStack stack : this.expandedPatterns.get(index).getOutputs()) {
            map.addTo(stack.what(), stack.amount());
        }
        return map;
    }

    // ------------------------------------------------------------------ GUI / 终端

    @Override
    public void attachConfigurators(ConfiguratorPanel configuratorPanel) {
        super.attachConfigurators(configuratorPanel);
        configuratorPanel.attachConfigurators(
                new Toggle(
                        GuiTextures.BUTTON_VISIBLE.getSubTexture(0.0, 0.0, 1.0, 0.5),
                        GuiTextures.BUTTON_VISIBLE.getSubTexture(0.0, 0.5, 1.0, 0.5),
                        () -> this.isHiddenTerminal,
                        (clickData, pressed) -> this.isHiddenTerminal = pressed)
                        .setTooltipsSupplier(pressed -> List.of(Component.translatable(
                                pressed ? "gui.gtlcore.hidden_in_terminal" : "gui.gtlcore.visible_in_terminal"))));
        // 🔴 需求④：神锻样板模式按钮 —— 必须挂在【最后一个】。
        //    GTCEu 的 ConfiguratorPanel 按挂载顺序自上而下排在界面左侧，上游超级样板总成也是
        //    最后一个挂它 ⇒ 落到整列**最下面** = 用户截图里的左下角。挂序不能改。
        configuratorPanel.attachConfigurators(new ForgePatternConfigurator(this));
    }

    @NotNull
    @Override
    public Widget createUIWidget() {
        // ================================ 版面来源（全部现读上游字节码，不含任何估值） ================================
        // 命令：
        //   javap -p -c -cp shanhai-rewrite\libs\gtlcore-1.2.3.2.jar \
        //         org.gtlcore.gtlcore.integration.wildcard.MEWildcardPatternBufferPartMachine
        // 原版 createUIWidget 的字节码偏移 → 控件（组 = WidgetGroup(0, 0, 158, 156)，偏移 0–12）：
        //   偏移  16– 33  LabelWidget(8, 4)                        在线/离线
        //   偏移  37– 87  AETextInputButtonWidget(90, 4, 60, 10)   改名
        //   偏移  88–136  样板格（原版【1 格】）：SlotWidget(wildcardPatternSlot, 0, 70, 25)
        //                 ＋ setChangeListener ＋ setBackground(SLOT, PATTERN_OVERLAY)
        //   偏移 137–158  LabelWidget(8, 50)                       样板数：N
        //   偏移 159–185  PatternCycleWidget(8, 62, 142, 36)       （输入 3×2 → 输出 3×2）
        //   偏移 186–207  LabelWidget(8, 104)                      已缓存：N
        //   偏移 208–234  PatternCycleWidget(8, 116, 142, 36)      （输入 3×2 → 输出 3×2）
        //
        // 🔴 用户 2026-10-01 原话：「主页面就可以用这样的，只不过把上面那一个样板格子改成 10 个」
        //    ⇒ 【唯一】改动 = 样板格 1 格 → WILDCARD_SLOT_COUNT(10) 格（5 列 × 2 行）。
        //       其余控件的坐标/尺寸/文案【逐字照原版】，没有例外。
        //
        // 🔴 10 格怎么排（用户口径：从原版那一格的位置「往两边排开」）
        //    · 列：5 列 × 18 px = 90 px；原版单格在 x=70（group 宽 158、格宽 18 ⇒ 水平居中，中心 79）
        //      ⇒ 首列 x = 79 - 90/2 = 34；列坐标 34 / 52 / 70 / 88 / 106（末列右缘 124 ≤ 158，放得下）。
        //    · 行：2 行，行距 18；第一行仍用原版那格的 y = 25 ⇒ 第二行 y = 43（格区 25..61）。
        //    · 多出来的一整行高 18 px ⇒ 原版 y≥50 的三段【整体下移 18】：
        //         50→68、62→80、104→122、116→134。
        //      组高 156→174（原版最后一段结束于 152、组高 156 ⇒ 留 4 px；本版结束于 170、组高 174 ⇒ 同样留 4 px）。
        //
        // 🔴 本版【一个 PaginationUIManager 都不用】⇒ 没有翻页条，也不存在
        //    「界面位置数 ≠ 容器格数」那个等式（2026-10-01 19:03:08 那次
        //    「Slot 10 not in valid range - [0,10)」崩溃的根因就是它）。
        //    样板格下标 i 直接就是容器槽号 0..9 ⇒ 两侧永远同一个数。
        //
        // 🔴 中键（照 gtladditions 的 MESuperPatternBufferPartMachine.createUIWidget 偏移 115–178）：
        //    右侧那一块 = org.gtlcore.gtlcore.api.gui.MEPatternCatalystUIManager，
        //    挂在【同一个 group】上（waitToAdded），x = group.getSizeWidth() + 4，
        //    y = 16（它自己构造函数里写死 WidgetGroup(x, 16, 16, 16)）；
        //    中键 ⇒ catalystUIManager.toggleFor(格号)（= 再按一次收起 / 换一格重开）。
        //    它不是浮窗、不是整页替换、不改主界面尺寸。
        final int slotColumns = 5;
        final int slotCell = 18;
        final int slotOriginX = (158 - slotColumns * slotCell) / 2;
        final int slotOriginY = 25;
        final int gridHeightPx = 18;
        final int groupHeight = 174;

        final WidgetGroup group = new WidgetGroup(0, 0, 158, groupHeight);
        group.addWidget(new LabelWidget(8, 4,
                () -> this.isOnline ? "gtceu.gui.me_network.online" : "gtceu.gui.me_network.offline"));
        group.addWidget(new AETextInputButtonWidget(90, 4, 60, 10)
                .setText(this.customName)
                .setOnConfirm(this::setCustomName)
                .setButtonTooltips(Component.translatable("gui.gtceu.rename.desc")));

        // ---- 样板格：原版那 1 格 → 10 格（本版唯一改动）。
        //      ⚠️ 下标 i 直接当容器槽号交给容器 ⇒ 判据 tools\sync-check\check-slot-bounds.mjs
        //         的规则 B 能就地比对「下标 0..9 < 容器 10 格」。
        final ShanhaiCircuitSlotWidget[] slotWidgets = new ShanhaiCircuitSlotWidget[WILDCARD_SLOT_COUNT];
        for (int i = 0; i < WILDCARD_SLOT_COUNT; i++) {
            final int cellX = slotOriginX + (i % slotColumns) * slotCell;
            final int cellY = slotOriginY + (i / slotColumns) * slotCell;
            final ShanhaiCircuitSlotWidget cell =
                    new ShanhaiCircuitSlotWidget(this.wildcardPatternSlot, i, cellX, cellY, this);
            cell.setChangeListener(this::onWildcardPatternChange);
            cell.setBackground(com.gregtechceu.gtceu.api.gui.GuiTextures.SLOT,
                    com.gregtechceu.gtceu.api.gui.GuiTextures.PATTERN_OVERLAY);
            slotWidgets[i] = cell;
            group.addWidget(cell);
        }

        group.addWidget(new LabelWidget(8, 50 + gridHeightPx,
                () -> Component.translatable("shanhai.machine.super_wildcard_pattern_buffer.patterns",
                        this.expandedPatterns.size()).getString()));
        group.addWidget(new PatternCycleWidget(8, 62 + gridHeightPx, 142, 36,
                () -> this.expandedPatterns));
        //      ⚠️ 原版那一行读的是宿主自己的 {@code recipeCacheMap.size()}；
        //         本机在「实际配方数量」那一轮把那份账本换成了 {@link ShanhaiPatternRecipeCache}
        //         （见类注释 §三），**`recipeCacheMap` 这个字段在本类里已经不存在**
        //         ⇒ 等价的写法是 {@code recipeCache.cachedSlots().length}（= 已存下配方的展开样板条数）。
        group.addWidget(new LabelWidget(8, 104 + gridHeightPx,
                () -> Component.translatable("shanhai.machine.super_wildcard_pattern_buffer.cached",
                        this.recipeCache.cachedSlots().length).getString()));
        group.addWidget(new PatternCycleWidget(8, 116 + gridHeightPx, 142, 36,
                this::getCachedPreviewPatterns));

        // ---- 右侧那一块（用户图1）：实际配方数量 ＋ 物品催化剂槽 3×3 ＋ 流体催化剂槽 3×3
        //      ＋（本轮新增）电路设置 0~32 按钮网格。
        //      照上游 MESuperPatternBufferPartMachine：挂同一个 group、x = 组宽 + 4、不参与主界面布局
        //      ⇒ 主界面本身不动，只是窗口整体变宽。
        //      ⚠️ 第 4 个参数 cacheRecipeCount 是机器自己的 @Persisted @DescSynced 权威字段，
        //         控件直接往它里面写 ⇒ 中间绝不许插拷贝。
        final ShanhaiCatalystPanel catalystUIManager = new ShanhaiCatalystPanel(
                group.getSizeWidth() + 4,
                this.catalystItems,
                this.catalystFluids,
                this.cacheRecipeCount,
                this::shanhaiOnCacheRecipeCountChanged,
                this);
        catalystUIManager.setVisible(false);
        catalystUIManager.setActive(false);
        group.waitToAdded(catalystUIManager);

        // ---- 中键 = 【只】开关右边那一块（用户原话：「中键之后，是【是否开关这个页面】，
        //      你看那右侧不是多出来一块吗」）。用到的就是上游 toggleFor 的语义：
        //        同一格再按一次 ⇒ 收起；换一格 ⇒ 重开并换成那一格的内容。
        //      ⚠️ 主界面（样板格/样板数/已缓存）一个字都不动。
        for (final ShanhaiCircuitSlotWidget cell : slotWidgets) {
            cell.shanhaiSetMiddleClickHandler(catalystUIManager::toggleFor);
        }
        this.catalystUIManager = catalystUIManager;
        return group;
    }

    /**
     * 右侧那一块（图1 的面板）＋（本轮新增的）电路设置段；由 {@link #createUIWidget()} 装配后留存。
     *
     * <p>⚠️ 原来是 {@code MEPatternCatalystUIManager}，本轮换成它的子类
     * {@link ShanhaiCatalystPanel}（只是多接了一段 GTCEu 的「电路设置」按钮网格）。
     */
    @Nullable
    private ShanhaiCatalystPanel catalystUIManager;

    /** 中键面板的互斥：电路面板打开时把催化剂弹框关掉。 */
    public void shanhaiHideCatalystPanel() {
        final ShanhaiCatalystPanel manager = this.catalystUIManager;
        if (manager != null && manager.isVisible()) {
            manager.setVisible(false);
            manager.setActive(false);
        }
    }

    /**
     * <b>某一块通配符样板的催化剂槽内容变了</b> ⇒ 重算它名下全部展开样板的催化剂表，
     * 并按上游同款把该块的配方缓存作废、让界面刷新。
     *
     * <h2>谁调它（2026-10-01 本轮补上的接线）</h2>
     * {@link #shanhaiWireCatalystCallbacks()} —— 在 {@link #onLoad()} 里挂到
     * {@code catalystItems[槽]} 的 {@code setOnContentsChanged} 与
     * {@code catalystFluids[槽].transfers[i]}（{@code FluidStorage}）的
     * {@code setOnContentsChanged} 上，与上游 {@code MEPatternBufferPartMachine#onLoad} 同款。
     *
     * <p>⚠️ 本轮之前这个方法<b>全库没有任何调用点</b>，催化剂槽也没挂回调 ⇒
     * 放/取催化剂<b>不会重算</b>（只在碰「实际配方数量」那个数字时才顺带重算一次）。
     *
     * <p><b>触发链（LDLib 侧也是字节码实证的）</b>：玩家往格子里放/取物品时走
     * {@code SlotWidget$WidgetSlotItemTransfer.setChanged()} →
     * {@code IItemTransfer.onContentsChanged()} → {@code ItemStackTransfer.onContentsChanged()}
     * → 跑我们挂的 Runnable；流体走 {@code FluidStorage} 的 fill/drain 内部三处
     * {@code onContentsChanged()} 调用。
     *
     * @param wildcardSlot 通配符样板槽号（{@code 0 .. WILDCARD_SLOT_COUNT-1}），
     *                     <b>不是</b>全局展开样板索引
     */
    public void shanhaiOnCatalystChanged(int wildcardSlot) {
        this.shanhaiOnCatalystChanged(wildcardSlot, "未标注来源");
    }

    /**
     * 上面那个入口的<b>带来源标注</b>形态 —— {@link #shanhaiWireCatalystCallbacks()} 的两条接线
     * （物品槽 / 流体槽）分别传 {@code "物品"} / {@code "流体"}，这样日志上能直接看出<b>是哪一档催化剂</b>触发的。
     * 除多出的这个标注之外，与 {@link #shanhaiOnCatalystChanged(int)} <b>逐句同构</b>。
     *
     * <h2>🔴 三态日志（照本机 {@code shanhaiOnCacheRecipeCountChanged} 的同款纪律）</h2>
     * <ul>
     *   <li><b>① 收到了</b> ⇒ 每次进方法先打一行（块号 ／ 类型 ／ 合法区间）；</li>
     *   <li><b>③ 早退了</b> ⇒ 下列三种情况<b>各自一行、且原因写在里面</b>：
     *       越界 ／ 客户端侧（{@code isRemote}，重算无意义）／
     *       该块名下 0 条展开样板（样板还没展开 ⇒ 没有缓存可作废）；</li>
     *   <li><b>② 重算了</b> ⇒ 一行给出结果：作废了几条展开样板 ＋ 本块就绪位「前→后」＋
     *       本机 10 块里共有几块的就绪位发生变化。</li>
     * </ul>
     * ⇒ 判据：<b>日志里不会出现「什么都没发生」这种无法归因的情况</b> ——
     * 要嘛有「重算完成」，要嘛有带原因的「早期返回」，两者必有其一。
     */
    private void shanhaiOnCatalystChanged(int wildcardSlot, String source) {
        // ------------------------------------------------ 三态 ①：收到了
        LOGGER.info("[SHANHAI-WILDCARD] 收到催化剂槽变化：块={} 类型={}（合法块号 1..{}）",
                wildcardSlot + 1, source, WILDCARD_SLOT_COUNT);
        // ------------------------------------------------ 三态 ③：早退（必须写出为什么）
        if (wildcardSlot < 0 || wildcardSlot >= WILDCARD_SLOT_COUNT) {
            LOGGER.info("[SHANHAI-WILDCARD] 催化剂重算被拒（早期返回）：块号={} 类型={} 原因=越界（合法 1..{}）",
                    wildcardSlot + 1, source, WILDCARD_SLOT_COUNT);
            return;
        }
        if (this.isRemote()) {
            LOGGER.info("[SHANHAI-WILDCARD] 催化剂重算被拒（早期返回）：块={} 类型={} 原因=这是客户端侧（isRemote），"
                            + "服务端那份才是权威 ⇒ 一个字都没动",
                    wildcardSlot + 1, source);
            return;
        }
        final int[] owned = this.recipeCache.slotsOf(wildcardSlot);
        if (owned.length == 0) {
            LOGGER.info("[SHANHAI-WILDCARD] 催化剂重算提前结束（早期返回）：块={} 类型={} 原因=该块名下当前 0 条展开样板"
                            + "（样板尚未展开 / 该格不是通配符样板）⇒ 没有配方缓存可作废",
                    wildcardSlot + 1, source);
            return;
        }
        final boolean[] readyBefore = this.cacheRecipeReady.clone();
        for (int index : owned) {
            this.reCalculateCatalyst(index);
        }
        // 🔴 上游 reCalculateCatalystItemMap 里那句 removeSlotFromGTRecipeCache(index)
        //    的【前两句】在本机的落点（下标换算见 {@link #reCalculateCatalyst(int)} 的注释）：
        //        cacheRecipe[index] = false;
        //        recipeMultipleCacheMap.remove(index);
        //    ⇒ 这里的 index 换成「通配符槽」，一块作一次。
        //    必须在上面那个循环【之后】：循环里会逐条 removeSlotFromMap / notifyProxySlotRemoved。
        this.recipeCache.resetOwner(wildcardSlot);
        this.refreshCacheRecipeReadyFlags();
        // ⚠️ 原来这里还有一句 `paginationUIManager.refreshPage(0)`。本轮主页面改用
        //    宿主原版那套「10 格直接排开、不翻页」的写法 ⇒ 一个 PaginationUIManager 都不存在了，
        //    那句连同它的页号越界面一起消失（判据工具里的「规则 C」从此没有调用点）。
        // ------------------------------------------------ 三态 ②：重算了（结果写进日志）
        int readyChanged = 0;
        for (int i = 0; i < WILDCARD_SLOT_COUNT; i++) {
            if (readyBefore[i] != this.cacheRecipeReady[i]) {
                readyChanged++;
            }
        }
        LOGGER.info("[SHANHAI-WILDCARD] 催化剂重算完成：块={} 类型={} 已作废该块名下 {} 条展开样板的配方缓存；"
                        + "本块就绪位 {}→{}；本机 {} 块里共有 {} 块的就绪位发生了变化",
                wildcardSlot + 1, source, owned.length,
                readyBefore[wildcardSlot], this.cacheRecipeReady[wildcardSlot],
                WILDCARD_SLOT_COUNT, readyChanged);
    }

    /**
     * 🔴「实际配方数量」被玩家改了（中键面板里那个数字）。
     *
     * <p>对应上游 {@code MEPatternCatalystUIManager} 构造时接收的 {@code onCacheCountChange}
     * 那个 {@code IntConsumer}；上游接的是 {@code MEPatternBufferPartMachine#onPatternChange(int)}。
     *
     * <h2>为什么本机不照抄「接 onPatternChange」</h2>
     * 上游那个方法开头就是 {@code if (isRemote()) return;}，然后<b>只有当样板本身换了</b>
     * （{@code oldPattern != null && !oldPattern.equals(newPattern)}）才去调
     * {@code removeSlotFromGTRecipeCache(i)}（{@code cacheRecipe[i]=false} ＋ 清缓存）。
     * ⇒ 只改数字、样板没换时，上游这条链<b>一个字节都不会动</b>，那个数字要下一次配方重算才可能生效。
     * 本机改成：<b>数字变了就把该块样板名下的配方缓存与就绪状态立刻作废、按新阈值重新攒</b>
     * —— 否则「设了值不生效」这个病原样还在。
     *
     * <h2>三态可分（照上一轮那套日志纪律）</h2>
     * <ul>
     *   <li>面板值没送到 ⇒ <b>本方法一行日志都没有</b>；</li>
     *   <li>送到了但被拒 ⇒ 出现「被拒（早期返回）」那一行，且原因写在里面；</li>
     *   <li>成功 ⇒ 出现「改为 N」那一行（带作废了几条展开样板）。</li>
     * </ul>
     *
     * <p>⚠️ 服务端权威：这个值只有服务端那份 {@code cacheRecipeCount} 算数，客户端那份只用于显示。
     * {@code clickData.isRemote} 的口径已用字节码钉死（{@code javap -c ClickData}）：
     * 客户端本地那次 {@code new ClickData()} 置 {@code isRemote=true}、
     * 服务端 {@code readFromBuf} 置 {@code isRemote=false}；
     * 而 {@code NumberInputWidget#changeValue} 是 {@code if (clickData.isRemote) return;}
     * ⇒ <b>+/- 按钮只在服务端执行</b>。
     */
    public void shanhaiOnCacheRecipeCountChanged(int wildcardSlot) {
        if (this.isRemote()) {
            LOGGER.info("[SHANHAI-WILDCARD] 设「实际配方数量」被拒（早期返回）：块={} 值={} 原因=这是客户端侧（isRemote）",
                    wildcardSlot + 1, this.shanhaiCacheRecipeCount(wildcardSlot));
            return;
        }
        if (wildcardSlot < 0 || wildcardSlot >= WILDCARD_SLOT_COUNT) {
            LOGGER.info("[SHANHAI-WILDCARD] 设「实际配方数量」被拒（早期返回）：块号={} 原因=越界（合法 1..{}）",
                    wildcardSlot + 1, WILDCARD_SLOT_COUNT);
            return;
        }
        final int value = this.shanhaiCacheRecipeCount(wildcardSlot);
        final int[] owned = this.recipeCache.slotsOf(wildcardSlot);
        // 上游 removeSlotFromGTRecipeCache(slot) 的三句：cacheRecipe[slot]=false、
        // recipeMultipleCacheMap.remove(slot)、removeSlotFromMap.accept(slot)。
        // 前两句在逻辑核的 resetOwner() 里，第三句在这儿补（还要通知 proxy 那一份）。
        for (int index : owned) {
            this.removeSlotFromMap.accept(index);
            this.notifyProxySlotRemoved(index);
        }
        this.recipeCache.resetOwner(wildcardSlot);
        this.refreshCacheRecipeReadyFlags();
        LOGGER.info("[SHANHAI-WILDCARD] 第 {} 块通配符样板的「实际配方数量」改为 {}：已作废该块名下 {} 条展开样板的配方缓存与就绪状态，按新阈值重新攒（攒够 {} 条该块才会被机器看见）",
                wildcardSlot + 1, value, owned.length, value);
    }

    /** 第 {@code wildcardSlot} 块样板当前设的「实际配方数量」（已夹到 1–127）。 */
    public int shanhaiCacheRecipeCount(int wildcardSlot) {
        if (wildcardSlot < 0 || wildcardSlot >= WILDCARD_SLOT_COUNT) {
            return ShanhaiPatternRecipeCache.DEFAULT_COUNT;
        }
        return this.cacheRecipeCount[wildcardSlot] & 0xFF;
    }

    /**
     * 这块通配符样板名下<b>是否已有展开样板存下配方</b>（界面「已缓存配方」提示用）。
     *
     * <p>⚠️ 它是「块」级派生位，与「每一条展开样板各自是否就绪」不是一回事；
     * 后者才是闸门（见 {@link ShanhaiPatternRecipeCache#isReady(int)}）。
     */
    public boolean shanhaiCacheRecipeReady(int wildcardSlot) {
        return wildcardSlot >= 0 && wildcardSlot < WILDCARD_SLOT_COUNT && this.cacheRecipeReady[wildcardSlot];
    }

    /** 「已缓存」那一栏要展示的样板。 */
    private List<IPatternDetails> getCachedPreviewPatterns() {
        final List<IPatternDetails> patterns = new ObjectArrayList<>();
        for (int slot : this.recipeCache.cachedSlots()) {
            if (slot >= 0 && slot < this.expandedPatterns.size()) {
                patterns.add(this.expandedPatterns.get(slot));
            }
        }
        return patterns;
    }

    public List<IPatternDetails> getAvailablePatterns() {
        return Collections.unmodifiableList(this.expandedPatterns);
    }

    public InternalInventory getTerminalPatternInventory() {
        return this.internalPatternInventory;
    }

    public PatternContainerGroup getTerminalGroup() {
        final MachineDefinition definition = ShanhaiWildcardMachines.superWildcardPatternBuffer();
        final ItemStack icon = definition != null ? definition.asStack() : ItemStack.EMPTY;
        final Component fallbackName =
                Component.translatable("block.shanhai." + ShanhaiWildcardMachines.SUPER_WILDCARD_PATTERN_BUFFER_ID);
        return this.customName.isEmpty()
                ? new PatternContainerGroup(AEItemKey.of(icon), fallbackName, Collections.emptyList())
                : new PatternContainerGroup(AEItemKey.of(icon), Component.literal(this.customName), Collections.emptyList());
    }

    public ItemStackTransfer getWildcardPatternSlot() {
        return this.wildcardPatternSlot;
    }

    // ------------------------------------------------------------------ AE 心跳

    protected class Ticker implements IGridTickable {

        @Override
        public TickingRequest getTickingRequest(IGridNode node) {
            return new TickingRequest(5, 60, false, true);
        }

        @Override
        public TickRateModulation tickingRequest(IGridNode node, int ticksSinceLastCall) {
            if (!SuperWildcardPatternBufferPartMachine.this.getMainNode().isActive()) {
                return TickRateModulation.SLEEP;
            }
            if (SuperWildcardPatternBufferPartMachine.this.buffer.isEmpty()) {
                return ticksSinceLastCall >= 60 ? TickRateModulation.SLEEP : TickRateModulation.SLOWER;
            }
            return AEUtils.reFunds(
                    SuperWildcardPatternBufferPartMachine.this.buffer,
                    SuperWildcardPatternBufferPartMachine.this.getMainNode().getGrid(),
                    SuperWildcardPatternBufferPartMachine.this.actionSource)
                    ? TickRateModulation.URGENT
                    : TickRateModulation.SLOWER;
        }
    }

    /**
     * 本机的 {@code IMEPatternTrait}。
     *
     * <p>与宿主的 {@code WildcardMEPatternTrait} 逐句同义；差别只有泛型类型（不再硬转成宿主那台）。
     */
    protected class SuperWildcardMEPatternTrait extends MEIOTrait implements IMEPatternTrait {

        public SuperWildcardMEPatternTrait(SuperWildcardPatternBufferPartMachine machine) {
            super(machine);
        }

        @NotNull
        @Override
        public ObjectSet<GTRecipe> getCachedGTRecipe() {
            // 🔴 「实际配方数量」的生效点①（也是最主要的一个）。
            //    上游同一处是（javap -c MEPatternBufferPartMachine$MEPatternTrait#getCachedGTRecipe）：
            //        if (recipeSet.isEmpty()) { it.remove(); }
            //        else if (cacheRecipe[slot] && internalInventory[slot].isActive()) { recipes.addAll(recipeSet); }
            //    ⇒ 未就绪的槽，它的配方**一条都进不来**。
            //    这些配方随后经 MEPatternRecipeHandlePart#getCachedGTRecipe() 进
            //    GTRecipeLookupMixin#find() / #getRecipeIterator()，
            //    也就是机器「找得到哪些配方」的那张表 —— 进不去 = 下不了这个单。
            final List<InternalSlot> slots = SuperWildcardPatternBufferPartMachine.this.internalSlots;
            return SuperWildcardPatternBufferPartMachine.this.recipeCache.visibleRecipes(
                    index -> index >= 0 && index < slots.size() && slots.get(index).isActive());
        }

        @Override
        public void setSlotCacheRecipe(int index, GTRecipe recipe) {
            if (recipe != null
                    && recipe.recipeType != GTRecipeTypes.DUMMY_RECIPES
                    && index >= 0
                    && index < SuperWildcardPatternBufferPartMachine.this.internalSlots.size()) {
                // 🔴 「实际配方数量」的生效点②：攒到一条就按阈值重算这一槽（以及它所属那一块）的就绪位。
                //    上游原句：cacheRecipe[index] = set.size() >= cacheRecipeCount[index];
                final ShanhaiPatternRecipeCache<GTRecipe> cache =
                        SuperWildcardPatternBufferPartMachine.this.recipeCache;
                cache.add(index, recipe);
                final int owner = SuperWildcardPatternBufferPartMachine.this.ownerWildcardSlot(index);
                if (owner >= 0 && owner < WILDCARD_SLOT_COUNT) {
                    SuperWildcardPatternBufferPartMachine.this.cacheRecipeReady[owner] = cache.isOwnerReady(owner);
                }
            }
        }

        @NotNull
        @Override
        public Int2ReferenceMap<ObjectSet<GTRecipe>> getSlot2RecipesCache() {
            final Int2ReferenceMap<ObjectSet<GTRecipe>> slot2Recipes = new Int2ReferenceOpenHashMap<>();
            final ShanhaiPatternRecipeCache<GTRecipe> cache =
                    SuperWildcardPatternBufferPartMachine.this.recipeCache;
            // 宿主那台也是现搭一份（不是把内部表直接交出去）——保留这个做法，免得外部改到我们的表。
            for (int slot : cache.cachedSlots()) {
                slot2Recipes.put(slot, new ObjectOpenHashSet<>(cache.cachedRecipes(slot)));
            }
            return slot2Recipes;
        }

        @Override
        public void setOnPatternChange(IntConsumer removeMapOnSlot) {
            SuperWildcardPatternBufferPartMachine.this.removeSlotFromMap = removeMapOnSlot;
        }

        @Override
        public boolean hasCacheInSlot(int slot) {
            // 上游 = cacheRecipe[slot]；宿主那台 = recipeCacheMap.containsKey(slot)（等价于阈值恒 1）。
            return SuperWildcardPatternBufferPartMachine.this.recipeCache.isReady(slot);
        }
    }

    /**
     * 本机的槽子类 —— 带<b>每槽催化剂</b>。
     *
     * <p>🔴 这是用户第 5 条的<b>结构件</b>：基类 {@code InternalSlot.getItemCatalystInventory()}
     * 返回的是不可变空 map、{@code hasItemCatalystInventory()} 返回 false，
     * 所以<b>光加界面必然无效</b>。必须像上游
     * {@code MEPatternBufferPartMachine.PatternBufferInternalSlot} 那样，
     * 覆写这几个钩子并提供两个真实 map。
     *
     * <p>与上游<b>逐句同构</b>（javap -c 对照），只有一处有意不同：
     * 序列化时键名用 {@code catalystInventory} / {@code catalystFluidInventory}（上游同款），
     * 于是「槽里的催化剂」跟着 {@code InternalSlot} 一起落盘。
     */
    protected class ShanhaiPatternBufferInternalSlot extends InternalSlot {

        private final Object2LongMap<AEItemKey> itemCatalystInventory = new Object2LongArrayMap<>();

        private final Object2LongMap<AEFluidKey> fluidCatalystInventory = new Object2LongArrayMap<>();

        public ShanhaiPatternBufferInternalSlot(int index) {
            super(index);
            this.itemCatalystInventory.defaultReturnValue(0L);
            this.fluidCatalystInventory.defaultReturnValue(0L);
        }

        @Override
        protected boolean hasItemCatalystInventory() {
            return !this.itemCatalystInventory.isEmpty();
        }

        @Override
        protected boolean hasFluidCatalystInventory() {
            return !this.fluidCatalystInventory.isEmpty();
        }

        @Override
        protected void appendLimitItemCatalystInputs(ObjectList<ItemStack> inputs) {
            for (Object2LongMap.Entry<AEItemKey> entry : Object2LongMaps.fastIterable(this.itemCatalystInventory)) {
                inputs.add(entry.getKey().toStack((int) Math.min(entry.getLongValue(), Integer.MAX_VALUE)));
            }
        }

        @Override
        protected void appendLimitFluidCatalystInputs(ObjectList<FluidStack> inputs) {
            for (Object2LongMap.Entry<AEFluidKey> entry : Object2LongMaps.fastIterable(this.fluidCatalystInventory)) {
                inputs.add(FluidStack.create(entry.getKey().getFluid(), entry.getLongValue()));
            }
        }

        @Override
        public boolean testCatalystItemInternal(GTRecipe recipe) {
            for (var content : recipe.getInputContents(
                    com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability.CAP)) {
                if (content.chance <= 0) {
                    continue;
                }
                final Ingredient ingredient = (Ingredient) content.getContent();
                for (ItemStack candidate : ingredient.getItems()) {
                    if (this.itemCatalystInventory.containsKey(AEItemKey.of(candidate))) {
                        return false;
                    }
                }
            }
            return true;
        }

        @Override
        public boolean testCatalystFluidInternal(GTRecipe recipe) {
            for (var content : recipe.getInputContents(
                    com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability.CAP)) {
                if (content.chance <= 0) {
                    continue;
                }
                final FluidIngredient ingredient = (FluidIngredient) content.getContent();
                for (FluidStack candidate : ingredient.getStacks()) {
                    final AEFluidKey key = AEFluidKey.of(candidate.getFluid());
                    if (key != null && this.fluidCatalystInventory.containsKey(key)) {
                        return false;
                    }
                }
            }
            return true;
        }

        @NotNull
        @Override
        public CompoundTag serializeNBT() {
            final CompoundTag tag = super.serializeNBT();
            final ListTag items = AEUtils.createListTag(AEKey::toTagGeneric, this.itemCatalystInventory);
            if (!items.isEmpty()) {
                tag.put("catalystInventory", items);
            }
            final ListTag fluids = AEUtils.createListTag(AEKey::toTagGeneric, this.fluidCatalystInventory);
            if (!fluids.isEmpty()) {
                tag.put("catalystFluidInventory", fluids);
            }
            return tag;
        }

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public void deserializeNBT(@NotNull CompoundTag tag) {
            super.deserializeNBT(tag);
            this.itemCatalystInventory.clear();
            AEUtils.loadInventory(tag.getList("catalystInventory", 10),
                    AEKey::fromTagGeneric, (Object2LongMap) this.itemCatalystInventory);
            this.fluidCatalystInventory.clear();
            AEUtils.loadInventory(tag.getList("catalystFluidInventory", 10),
                    AEKey::fromTagGeneric, (Object2LongMap) this.fluidCatalystInventory);
        }

        @Override
        public Object2LongMap<AEItemKey> getItemCatalystInventory() {
            return this.itemCatalystInventory;
        }

        @Override
        public Object2LongMap<AEFluidKey> getFluidCatalystInventory() {
            return this.fluidCatalystInventory;
        }
    }
}
