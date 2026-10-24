package com.shanhai.machine.stellar;

import appeng.api.crafting.IPatternDetails;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.integration.ae2.gui.widget.AETextInputButtonWidget;
import com.gregtechceu.gtceu.integration.ae2.slot.ExportOnlyAEFluidList;
import com.gregtechceu.gtceu.integration.ae2.slot.IConfigurableSlot;
import com.lowdragmc.lowdraglib.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.DraggableScrollableWidgetGroup;
import com.lowdragmc.lowdraglib.gui.widget.ImageWidget;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.misc.FluidStorage;
import com.lowdragmc.lowdraglib.misc.FluidTransferList;
import com.lowdragmc.lowdraglib.misc.ItemStackTransfer;
import com.lowdragmc.lowdraglib.side.fluid.FluidHelper;
import com.lowdragmc.lowdraglib.side.fluid.IFluidTransfer;
import com.lowdragmc.lowdraglib.side.item.IItemTransfer;
import com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced;
import com.lowdragmc.lowdraglib.syncdata.annotation.LazyManaged;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;
import com.shanhai.machine.wildcard.AutoForgeMultiplierHost;
import com.shanhai.machine.wildcard.AutoForgeMultiplierPanel;
import com.shanhai.machine.wildcard.ShanhaiAutoForgeMultiplier;
import com.shanhai.machine.wildcard.ShanhaiAutoForgeMultiplierDriver;
import com.shanhai.machine.wildcard.ShanhaiForgePatternMode;
import com.shanhai.machine.wildcard.ShanhaiSlotCircuit;
import com.shanhai.machine.wildcard.ShanhaiWildcardCompat;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import org.gtlcore.gtlcore.api.machine.trait.MEPart.IMEPatternTrait;
import org.gtlcore.gtlcore.api.machine.trait.MEStock.IMESlot;
import org.gtlcore.gtlcore.api.gui.MEPatternCatalystUIManager;
import org.gtlcore.gtlcore.client.gui.widget.AEDualConfigWidget;
import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferPartMachine;
// 2026-10-24 通配页重排：循环预览改用 GT 原生控件（参照机 SuperWildcardPatternBufferPartMachine 用的同一个）。
import org.gtlcore.gtlcore.client.gui.widget.PatternCycleWidget;
import org.gtlcore.gtlcore.common.machine.multiblock.part.PaginationUIManager;
import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferPartMachineBase;
import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEStockingPatternBufferPartMachine;
import org.gtlcore.gtlcore.integration.ae2.widget.AEPatternViewExtendSlotWidget;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>星律样板总成</b> —— 「山海」版的上游同名机器 {@code gt_shanhai:recipe_type_pattern_buffer}
 * （类 {@code RecipeTypePatternBufferPartMachine}，3450 行）。
 * <b>阶段一（已交）</b>：能注册、能插进多方块、能开 GUI、能翻页；1080 个普通样板槽是真的。
 * <br><b>阶段二（本轮）</b>：① 50 个通配符样板槽（10×5 一页）＋ 母槽中间配置（写/清
 * {@code gt_shanhai_recipe_type}）；② 只读的四来源配方类型读取层
 * （{@link ShanhaiPatternTypeReader} + 别名表 {@link ShanhaiRecipeTypeIds}）；
 * ③ {@code [SHANHAI-STELLAR]} 日志闸（{@link ShanhaiStellarLog}，默认关）。
 * 下标/风险推演见交付报告「阶段二」一节 —— <b>本类刻意不覆写任何会按槽号索引父类数组的方法</b>，
 * 理由写在 §七。
 *
 * <h2>一、🔴 继承路线 = C 路（字节码依据，逐条可复核）</h2>
 * <pre>
 *   public class MEStockingPatternBufferPartMachine
 *          extends MEPatternBufferPartMachine
 *          implements MEPart.IModifiableSyncOffset
 * </pre>
 * 取证命令（现成产物：{@code temp\stellar-ground\javap-MEStockingPatternBufferPartMachine.txt}）：
 * <pre>
 *   javap -p -cp shanhai-rewrite\libs\gtlcore-1.2.3.2-fix3.jar \
 *       org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEStockingPatternBufferPartMachine
 * </pre>
 * 选它的四条理由（每条都是字节码事实，不是估值）：
 * <ol>
 *   <li><b>类声明可用</b>：{@code flags: (0x0021) ACC_PUBLIC, ACC_SUPER}
 *       —— public、<b>没有 ACC_FINAL</b>，构造器也是 public ⇒ 跨包继承合法。</li>
 *   <li><b>样板槽数就是构造参数</b>：它的构造器是
 *       {@code (IMachineBlockEntity, int maxPatternCount, IO)}，并把那个 int 原样上抛
 *       {@code MEPatternBufferPartMachine.<init>(holder, maxPatternCount, io)}；后者用它一次性 new 出
 *       {@code hasPatternArray} / {@code cacheRecipe} / {@code internalInventory} / {@code catalystItems} /
 *       {@code catalystFluids} / {@code cacheRecipeCount} / {@code patternInventory}
 *       （{@code javap -c} 偏移 47–135，全是 {@code iload_2 → newarray/anewarray}）。
 *       ⇒ <b>「1080 个普通样板槽」不需要覆写任何方法</b>，构造器传 1080 就够。
 *       （{@code maxPatternCount} 自身是父类 {@code protected final int} ⇒ 子类读得到。）</li>
 *   <li><b>库存输入（32 物品 + 32 流体）是现成的</b>：{@code protected final stockItemHandler} /
 *       {@code stockFluidHandler}，{@code CONFIG_SIZE = 32}（常量池 {@code #50 = Integer 32}）；
 *       每槽的 {@code createInternalSlot} 返回 {@code StockingPatternBufferInternalSlot}
 *       （覆写了 8 个方法：isItemActive / isFluidActive / getLimitItemStackInput /
 *       getLimitFluidStackInput / getItemStackInputMap / getFluidStackInputMap /
 *       handleItemInternal / handleFluidInternal）⇒ 这一整套不用抄。</li>
 *   <li><b>不踩「getMachine() 会 checkcast」那个坑</b>：
 *       {@code MEPatternBufferRecipeHandlerTrait$MEItemInputHandler.getMachine()} 会
 *       {@code checkcast MEPatternBufferPartMachine} ⇒ 继承层级只要<b>在其之上</b>就安全；
 *       本类经 MEStocking 继承到 MEPatternBufferPartMachine，满足。</li>
 * </ol>
 *
 * <h2>二、🔴 「分页」上游没有 —— 但 gtlcore 有现成的 {@link PaginationUIManager}</h2>
 * 上游那个 {@code createUIWidget()} 的版面是<b>死的不分页</b>（{@code javap -c} 偏移 0–10）：
 * <pre>
 *   int cols = 9;                      // bipush 9
 *   int rows = maxPatternCount / 9;    // getfield maxPatternCount; idiv
 *   width  = 18 * cols + 16;           // = 178
 *   height = 18 * rows + 16;           // maxPatternCount=1080 ⇒ rows=120 ⇒ 2176
 *   ... new WidgetGroup(0, 0, width, height + 6 + 12 + 95)   // 面板高 ≈ 2289 px
 * </pre>
 * ⇒ 直接把 1080 交给它 = 一个 2289 px 高的窗口，<b>不可用</b>。
 * ⚠️ 它那个 {@code page} 字段<b>也不是给样板槽翻页的</b>：唯一消费者是末尾那块
 * {@code AEDualConfigWidget}（库存配置 32+32 的分页，偏移 380–419）⇒ 继承下来的分页只管库存配置。
 *
 * <p>真正的分页件是 gtlcore 的
 * {@code org.gtlcore.gtlcore.common.machine.multiblock.part.PaginationUIManager}：
 * <pre>
 *   // 7 参重载（本类用的这个）：第 3 个参数是【maxPatternCount】，maxPages 由它自己算
 *   PaginationUIManager(int patternsPerRow, int rowsPerPage, int maxPatternCount,
 *                       int uiWidth, int uiHeight, IntConsumer onPatternChange, IItemTransfer patternInventory)
 *       → this.maxPatternCount = 第3参;  this.maxPages = ceil(第3参 / (rowsPerPage*patternsPerRow))
 *   // 8 参重载：第 3 个参数是【maxPages】，maxPatternCount 由它自己算（两个重载第 3 参含义相反！）
 *   WidgetGroup createPaginationUI(IntConsumer onMiddleClicked)
 * </pre>
 * 上游那台机器就是用它拼出「9 × 6 × 20」的（{@code DShanhaiMachines.java:1456-1461} 的构造实参
 * {@code recipeTypePatternsPerRow / RowsPerPage / MaxPages / WildcardSlotsPerPage / WildcardMaxPages}）。
 * 它的版面几何（{@code javap -c} 实测，全部是它自己写死的常数）：
 * <pre>
 *   rebuildPatternSlots : 槽区子组 = WidgetGroup(0, 16, uiWidth, rowsPerPage*18)
 *                         每格 x = 8 + (i % patternsPerRow)*18，y = (i / patternsPerRow)*18
 *   createPageControls  : labelY = 16 + rowsPerPage*18 + 4
 *                         上一页按钮 (8, labelY, 30, 12)  下一页按钮 (uiWidth-38, labelY, 30, 12)
 *                         页码标签 ((uiWidth-12)/2, labelY+2)，文案 = "当前页+1 / maxPages"
 *   refreshPage(p)      : currentPageIndex = p → clearAllWidgets() → 重建第 p 页的格（越界用 Math.min 夹住）
 * </pre>
 * ⇒ 本类的 UI = 这个类 + 一行联机状态标签，<b>不自己写翻页逻辑</b>（自己写要多一份「页面重建」的维护面，
 * 而那正是 {@code SuperWildcardPatternBufferPartMachine} 里记过的越界崩溃来源）。
 *
 * <h2>三、🔴 产物回 ME 网络：不写一行代码，但有一条硬条件</h2>
 * 通道是现成的：{@code meHandleRecipeOutputInner} 把产物写进
 * {@code MEPatternBufferPartMachineBase.buffer}（内存 map）→ {@code MEPatternBufferPartMachine$Ticker}
 * → {@code AEUtils.reFunds}。<b>Ticker 只在 {@code io == IO.BOTH} 时注册</b>
 * （{@code MEPatternBufferPartMachine} 构造器 {@code javap -c} 偏移 247–274：
 * {@code getstatic IO.BOTH; if_acmpne 275; … addService(IGridTickable.class, new Ticker(this))}）
 * ⇒ 构造器<b>必须</b>传 {@link IO#BOTH}，已照做。
 * <p>⚠️ {@code buffer} <b>不持久化</b>：断线 / 区块卸载 / 拆机时还没退款的产物会消失。
 * <b>本轮不动</b>，只标 {@code TODO(阶段四)}。
 *
 * <h2>四、🔴 本轮真做到 / 明确做不到</h2>
 * <b>做到</b>：
 * <ul>
 *   <li>能注册（{@code ShanhaiStellarMachines}）、能插进多方块（4 个 PartAbility 与上游那台、
 *       以及本工程已验收的超级通配符样板总成一致）；</li>
 *   <li>能开 GUI：右击打开，9 列 × 6 行 / 页、共 20 页，页码 "1 / 20" 由 {@link PaginationUIManager}
 *       自己画，前后翻页按钮可用；</li>
 *   <li>1080 个普通样板槽<b>是真的</b>：父类按 {@code maxPatternCount} 建的 {@code patternInventory}
 *       长度就是 1080，界面槽号 = 页号×54 + 格号，是<b>同一个下标空间</b>（所以不会出现
 *       「界面位置数 ≠ 容器格数」那类越界）；</li>
 *   <li>库存输入 32+32、每槽 {@code StockingPatternBufferInternalSlot}、产物回 ME
 *       —— 全部继承自 MEStocking / MEPatternBufferPartMachine，<b>不是重写的</b>。</li>
 * </ul>
 * <b>做不到（阶段二~四，逐条有 TODO）</b>：
 * <ul>
 *   <li>通配符样板槽（10×5 = 50 格）：本类<b>只有常量</b>，一个通配符容器都没建；</li>
 *   <li>库存配置界面（上游那块 32+32 的 {@code AEDualConfigWidget}）与催化剂面板
 *       （中键 {@code MEPatternCatalystUIManager.toggleFor(槽号)}）在本版 UI 里<b>都没有</b>
 *       —— 中键现在什么都不做；</li>
 *   <li>翻页页号不落盘（{@code PaginationUIManager.currentPageIndex} 是 UI 实例状态，重开界面回第 1 页）；</li>
 *   <li>产物 buffer 持久化。</li>
 * </ul>
 *
 * <h2>五、🔴 四条上游坑，在本类里的落点</h2>
 * <ol>
 *   <li>{@code createInternalSlot} / {@code createRecipeHandler} / {@code createMETrait} /
 *       {@code createNodeHolder} 都在<b>超类构造器里</b>就被调用 ⇒ 那一刻子类字段还是 null。
 *       ⇒ 本类<b>不覆写这四个方法</b>；阶段三必须覆写时，一律只读 {@code static} 常量。</li>
 *   <li>{@code MEItemInputHandler.getMachine()} 的 {@code checkcast MEPatternBufferPartMachine}
 *       ⇒ 已满足（见 §一.4）。</li>
 *   <li>gtladditions 的 mixin <b>@Overwrite 压在</b> {@code MEPatternBufferPartMachineBase.onUse}
 *       与 {@code MEPatternBufferPartMachine.getRealPattern} 上（{@code javap -v} 实证：
 *       {@code MEPatternBufferPartMachineMixin} methods=2，常量池 {@code Overwrite → getRealPattern}；
 *       {@code MEPatternBufferPartMachineBaseMixin} 常量池 {@code Overwrite → onUse}）
 *       ⇒ 本类<b>绝不覆写 onUse / getRealPattern</b>（后者本来就是 private，覆写不了）。</li>
 *   <li>已占用字段名（{@code stockItemHandler} / {@code stockFluidHandler} / {@code syncOffset} /
 *       {@code page} / {@code maxPatternCount} / {@code catalystItems} / {@code catalystFluids} /
 *       {@code internalInventory} / {@code patternInventory} / {@code cacheRecipeCount} / …）
 *       ⇒ 阶段一：一个实例字段都没有；阶段二新增的 3 个字段
 *       （{@code wildcardPatternInventory} / {@code wildcardConfigSlot} / {@code wildcardConfigType}）
 *       与上表逐个比对过，<b>不撞名</b>。</li>
 * </ol>
 *
 * <h2>七、🔴 阶段二的下标推演结论（为什么本类<b>不</b>把通配符槽塞进父类的槽号空间）</h2>
 * 父类有 6 处<b>直接用下标访问</b> {@code internalInventory[]}（{@code javap -p -c} 实证，
 * 明细见 {@code temp\stellar-p1\javap-arrayindex.txt}）：
 * <pre>
 *   onPatternChange(int)      9: getfield internalInventory → 13: aaload     ← 无边界检查！
 *   getInternalSlot(int)      1: getfield internalInventory →  5: aaload     ← 无边界检查
 *   getInternalSlotCount()    1: getfield internalInventory →  4: arraylength
 *   reCalculateCatalystItemMap / reCalculateCatalystFluidMap / onLoad / getRealPattern /
 *   gtlcore$graphDiagnostic              同样直接吃 internalInventory
 * </pre>
 * ⇒ 若采用「数组仍是 1080、但逻辑槽号扩到 1130」的写法，任何一条
 * {@code onPatternChange(1080)} 就会 {@code ArrayIndexOutOfBoundsException}。
 * 上游那台机器的写法是 <b>另开一份 50 格 {@code ItemStackTransfer} + 覆写
 * {@code getInternalSlotCount()/getInternalSlot(int)}</b>（见
 * {@code temp/stellar-ground/upstream-RecipeTypePatternBufferPartMachine.java:1628/1734/1741}），
 * 并且它必须自己接管全部会越界的入口。
 *
 * <p><b>阶段二的选择</b>：通配符槽用<b>独立的 {@link #wildcardPatternInventory}（50 格）</b>，
 * 但<b>不</b>改 {@code getInternalSlotCount()/getInternalSlot(int)} ⇒ 父类看到的槽号空间<b>仍然只有 0..1079</b>
 * ⇒ <b>不存在任何一个 ≥1080 的下标进得了父类数组</b>（越界风险 = 0，而不是「小心一点」）。
 * 代价（写清楚，不许含糊）：<b>通配符槽里的样板阶段二<b>不参与</b> AE 的配方推送</b>
 * —— 它现在是一个「能放、能配类型、能存档」的容器；让它们真的出样板是阶段三「展开 + 三道闸」的事。
 * 阶段三要做的事就是把上游那三个覆写（{@code getInternalSlotCount} / {@code getInternalSlot} /
 * 越界入口接管）补上，并把 {@code onPatternChange} 对通配符槽号短路。
 *
 * <h2>六、为什么不继承「超级通配符样板总成」（本工程已验收那台）</h2>
 * 它继承的是 {@code MEPatternBufferPartMachineBase}（更靠上的一层），拿不到
 * {@code MEPatternBufferPartMachine} 的「每槽一个 InternalSlot + 配方缓存 + 库存输入」，
 * 而且它的通配符槽是 10 格写死、无分页（{@code WILDCARD_SLOT_COUNT = 10}）。
 * ⇒ 那台只作<b>注册写法 / UI 写法 / lang 键的只读参考</b>，<b>一个字都没改</b>。
 */
// 🔴 2026-10-24：implements AutoForgeMultiplierHost —— 「神锻样板模式」面板不再自摆，
//    改用上游那份逐字拷贝（AutoForgeMultiplierPanel.buildFull）。接口把「面板要读写什么」
//    抽成 17 个方法，本机逐个对上（映射表见类尾 §AutoForgeMultiplierHost 那一节）。
//    依据：用户原话「我要和超级样板总成的那个一模一样，不要你这乱搞」。
public class StellarSampleAssemblerPartMachine extends MEStockingPatternBufferPartMachine
        implements AutoForgeMultiplierHost {

    // ---------------------------------------------------------------- 规格（用户给的数字）

    /** 每行几个普通样板槽（与上游 {@code createUIWidget} 的 {@code bipush 9} 逐值相同）。 */
    public static final int PATTERN_COLUMNS = 9;

    /** 每页几行普通样板槽。 */
    public static final int PATTERN_ROWS_PER_PAGE = 6;

    /** 每页普通样板槽数 = 9 × 6 = 54。 */
    public static final int PATTERN_SLOTS_PER_PAGE = PATTERN_COLUMNS * PATTERN_ROWS_PER_PAGE;

    /** 普通样板总页数。 */
    public static final int PATTERN_PAGE_COUNT = 20;

    /**
     * 普通样板槽总数 = 9 × 6 × 20 = <b>1080</b>。
     *
     * <p>🔴 它<b>不是</b>界面占位数，而是父类构造器的 {@code maxPatternCount}：
     * {@code MEPatternBufferPartMachine} 用它 new 出 {@code patternInventory}（长度 1080）、
     * {@code internalInventory[1080]}、{@code catalystItems[1080]}、{@code catalystFluids[1080]} 等
     * （{@code javap -c} 偏移 47–135）⇒ <b>槽位是真的 1080 个</b>。
     * <p>⚠️ 同一条事实的另一面：构造 1080 槽意味着 1080 个 {@code StockingPatternBufferInternalSlot}
     * ＋ 1080×9 个物品催化剂格 ＋ 1080×9 个流体催化剂罐（后者由父类 {@code lambda$new$3/4} 生成）
     * ⇒ <b>单台机器的对象数是万级</b>。这是设计要的规模，不是 bug，但它是「进游戏 30 秒判据」之一
     * （见交付报告第 5 节）。
     */
    public static final int PATTERN_SLOT_COUNT = PATTERN_SLOTS_PER_PAGE * PATTERN_PAGE_COUNT;

    /** 通配符样板：每页 10 列。 */
    public static final int WILDCARD_COLUMNS = 10;

    /** 通配符样板：每页 5 行。 */
    public static final int WILDCARD_ROWS_PER_PAGE = 5;

    /**
     * 通配符样板槽数 = 10 × 5 = <b>50</b>（对齐本工程已验收那台「超级通配符样板总成」一页的规模）。
     *
     * <p>🔴 阶段二起它是<b>真槽位</b>：{@link #wildcardPatternInventory}（独立 50 格
     * {@code ItemStackTransfer}，{@code @Persisted}）。但它<b>不</b>进父类的槽号空间
     * —— 原因见类注释 §七（越界风险 = 0 的写法）。
     */
    public static final int WILDCARD_SLOT_COUNT = WILDCARD_COLUMNS * WILDCARD_ROWS_PER_PAGE;

    /** 通配符槽的<b>对外槽号起点</b>：API 里 {@code 0..1079} 是普通槽，{@code 1080..1129} 是通配符槽。 */
    public static final int WILDCARD_API_SLOT_OFFSET = PATTERN_SLOT_COUNT;

    // ---------------------------------------------------------------- 版面常量（像素）

    /**
     * 面板宽 = 9 列 × 18 + 16 边距 = <b>178</b>。
     *
     * <p>与上游 {@code createUIWidget} 的 {@code 18*cols+16} 逐值相同；
     * {@link PaginationUIManager} 自己把首列画在 x=8、下一列 +18（见类注释 §二的字节码），
     * 所以末列右缘 = 8 + 8×18 + 18 = 168 ≤ 178。
     */
    private static final int PANEL_WIDTH = 18 * PATTERN_COLUMNS + 16;

    /**
     * 面板高 = 16（顶部留白）+ 6 行 × 18 + 4（间隙）+ 12（翻页按钮）= <b>140</b>。
     *
     * <p>这四个数<b>不是我定的</b>，是 {@link PaginationUIManager} 里写死的（见类注释 §二）：
     * 槽区子组固定在 y=16，页码/按钮行在 y = 16 + 18×行数 + 4。
     * ⇒ 面板高必须 ≥ 140，否则翻页按钮会被裁掉。
     */
    private static final int PANEL_HEIGHT = 16 + 18 * PATTERN_ROWS_PER_PAGE + 4 + 12;

    // ---------------------------------------------------------------- 通配符区版面（阶段二）

    /** 通配符区标题行 y（紧贴普通区下沿）。 */
    private static final int WILDCARD_TITLE_Y = PANEL_HEIGHT + 6;

    /** 通配符 10×5 格区左上角。 */
    private static final int WILDCARD_GRID_X = 8;

    private static final int WILDCARD_GRID_Y = WILDCARD_TITLE_Y + 12;

    /** 母槽配置行：标签 y / 输入框 y。 */
    private static final int WILDCARD_CONFIG_LABEL_Y = WILDCARD_GRID_Y + 18 * WILDCARD_ROWS_PER_PAGE + 4;

    private static final int WILDCARD_CONFIG_INPUT_Y = WILDCARD_CONFIG_LABEL_Y + 12;

    /**
     * 面板总宽 / 总高（= 普通区宽 178 ∪ 通配符区宽 8+10×18+8=196 ⇒ 取 196；高 = 配置行 + 输入框 + 留白）。
     *
     * <p>⚠️ 这两个数按 {@link PaginationUIManager} 与格区的写死几何推出来的（见 §二），
     * 不是估算；**没在真客户端看过**是唯一残余不确定性（报告 §5 已列）。
     */
    private static final int ROOT_WIDTH = Math.max(PANEL_WIDTH, WILDCARD_GRID_X + 18 * WILDCARD_COLUMNS + 8);

    private static final int ROOT_HEIGHT = WILDCARD_CONFIG_INPUT_Y + 10 + 6;

    // ---------------------------------------------------------------- 页内版面（阶段七：四页各自从 (0,0) 起排）

    /**
     * 通配符页的 10×5 格区左上角 y（页内坐标）。
     *
     * <p>旧版是「页面从通配符标题 146 开始」，现在通配符<b>自己是一页</b> ⇒ 从 y=16 起排
     * （上面 2..12 留给标题行）。格的 x 仍然复用 {@link #WILDCARD_GRID_X}（= 8）。
     */
    private static final int WILDCARD_PAGE_GRID_Y = 16;

    /** 通配符页：母槽配置标签 y。 */
    private static final int WILDCARD_PAGE_CONFIG_LABEL_Y = WILDCARD_PAGE_GRID_Y + 18 * WILDCARD_ROWS_PER_PAGE + 4;

    /** 通配符页：母槽配置输入框 y。 */
    private static final int WILDCARD_PAGE_CONFIG_INPUT_Y = WILDCARD_PAGE_CONFIG_LABEL_Y + 12;

    // ---------------------------------------------------------------- lang 键（阶段七：页签 + 左下角按钮 + 标签页）

    /** 页① 页签名（短，要塞进 28×28）。 */
    private static final String LANG_TAB_PATTERNS = "shanhai.machine.stellar_sample_assembler.tab.patterns";

    /** 页② 页签名。 */
    private static final String LANG_TAB_WILDCARD = "shanhai.machine.stellar_sample_assembler.tab.wildcard";

    /** 页③ 页签名。 */
    private static final String LANG_TAB_STOCK = "shanhai.machine.stellar_sample_assembler.tab.stock";

    /** 页④ 页签名。 */
    private static final String LANG_TAB_TAG = "shanhai.machine.stellar_sample_assembler.tab.tag";

    /** 左下角：闸按钮名。 */
    private static final String LANG_BTN_GATE = "shanhai.machine.stellar_sample_assembler.btn.gate";

    /** 左下角：神锻按钮名。 */
    private static final String LANG_BTN_FORGE = "shanhai.machine.stellar_sample_assembler.btn.forge";

    /** 闸 tooltip（%s = 开/关）。 */
    private static final String LANG_TIP_GATE = "shanhai.machine.stellar_sample_assembler.tip.gate";

    /** 神锻 tooltip（%s = 开/关，%s = 倍率）。 */
    private static final String LANG_TIP_FORGE = "shanhai.machine.stellar_sample_assembler.tip.forge";

    /** 标签页标题。 */
    private static final String LANG_TAG_SECTION = "shanhai.machine.stellar_sample_assembler.tag.section";

    /** 标签页：白名单标签名。 */
    private static final String LANG_TAG_WHITE = "shanhai.machine.stellar_sample_assembler.tag.white";

    /** 标签页：白名单输入框 tooltip。 */
    private static final String LANG_TAG_WHITE_HINT = "shanhai.machine.stellar_sample_assembler.tag.whiteHint";

    /** 标签页：黑名单标签名。 */
    private static final String LANG_TAG_BLACK = "shanhai.machine.stellar_sample_assembler.tag.black";

    /** 标签页：黑名单输入框 tooltip。 */
    private static final String LANG_TAG_BLACK_HINT = "shanhai.machine.stellar_sample_assembler.tag.blackHint";

    /** 标签页说明行 1。 */
    private static final String LANG_TAG_EXPLAIN_1 = "shanhai.machine.stellar_sample_assembler.tag.explain1";

    /** 标签页说明行 2。 */
    private static final String LANG_TAG_EXPLAIN_2 = "shanhai.machine.stellar_sample_assembler.tag.explain2";

    /** 库存输入页标题（%s = 格数，%s = 页数）。 */
    private static final String LANG_STOCK_SECTION = "shanhai.machine.stellar_sample_assembler.stock.section";

    /** GUI：闸拦截计数（%s = 未标注射，%s = 歧义数）。 */
    private static final String LANG_GATE_COUNT = "shanhai.machine.stellar_sample_assembler.gate.count";

    /** GUI：没有样板被拦。 */
    private static final String LANG_GATE_COUNT_NONE = "shanhai.machine.stellar_sample_assembler.gate.countNone";
    /** 神锻面板标题。 */
    private static final String LANG_FORGE_PANEL_TITLE = "shanhai.machine.stellar_sample_assembler.forge.title";

    /** 神锻面板：神锻模式行。 */
    private static final String LANG_FORGE_ROW_MODE = "shanhai.machine.stellar_sample_assembler.forge.mode";

    // ⚠️ 2026-10-24：原来这里的 forge.multiplier / forge.auto / forge.autoReason / forge.on /
    //    forge.off / gate.on / gate.off 七个 lang 常量已删除 —— 它们只服务「本类自己摆的那块面板」，
    //    那块已整体换成上游面板（AutoForgeMultiplierPanel.buildFull），文案改由上游 / 共享面板出。
    //    （顺带一提：gate.on / gate.off / forge.on / forge.off 这四个 key 在 zh_cn/en_us 里本来就不存在，
    //     旧面板显示的是裸 key —— 这个隐患随常量一起消失了。）

    // ⚠️ 2026-10-24 三修 ①：原来这四个 lang 常量（forge.status / forge.reason / forge.autoWord /
    //    forge.unreadable）加上 forge.reasonNoHost 一起删掉了 —— 它们只服务「把状态行拼成写死的
    //    『自动 · 读不到 -> ×1』」和「原因：未成型（无控制器）」这两条**假话**。
    //    现在状态行走共享判定核 ShanhaiAutoForgeMultiplier.describe（真实倍率），
    //    原因行走 forge.installed / forge.autoUnavailable 两个新键（真实状态）。
    //    （那几个旧 key 仍留在 zh_cn/en_us 里，属无人引用的死键，与 forge.multiplier / forge.auto 同一批。）

    /**
     * 面板原因行：<b>已安装于</b>某个控制器（{@code %s} = 控制器/结构的显示名）。
     *
     * <p>🔴 2026-10-24 三修 ①：原来这里写死「原因：未成型（无控制器）」，即使这台仓室
     * 明明装在一座成型的多方块里也照写 —— 那是<b>假话</b>（活的界面上不许放假数据）。
     */
    private static final String LANG_FORGE_INSTALLED = "shanhai.machine.stellar_sample_assembler.forge.installed";

    /** 面板原因行：<b>自动倍率不可用</b>（仓室读不到控制器；手填倍率仍然有效）。 */
    private static final String LANG_FORGE_AUTO_UNAVAILABLE =
            "shanhai.machine.stellar_sample_assembler.forge.autoUnavailable";

    /** 通配页：左 3×2 表头「样板数：N」。 */
    private static final String LANG_WILDCARD_ROW_MOTHERS = "shanhai.machine.stellar_sample_assembler.wildcard.rowMothers";

    /** 通配页：右 3×2 表头「该槽展开：M 条」。 */
    private static final String LANG_WILDCARD_ROW_EXPANDED = "shanhai.machine.stellar_sample_assembler.wildcard.rowExpanded";

    /** 标签过滤：开。 */
    private static final String LANG_TAG_ON = "shanhai.machine.stellar_sample_assembler.tag.on";

    /** 标签过滤：关。 */
    private static final String LANG_TAG_OFF_TXT = "shanhai.machine.stellar_sample_assembler.tag.off";
    /** 标签页：总开关 tooltip。 */
    private static final String LANG_TAG_ENABLED_TIP = "shanhai.machine.stellar_sample_assembler.tag.enabledTip";

    /** 标签页：总开关关闭时的预览文案。 */
    private static final String LANG_TAG_DISABLED = "shanhai.machine.stellar_sample_assembler.tag.disabled";

    /** 标签页：上格（库存配置）tooltip。 */
    private static final String LANG_TAG_CONFIG_GRID_TIP = "shanhai.machine.stellar_sample_assembler.tag.configGridKeptTip";

    /** 标签页：下格（ME 里可用的）tooltip（%s = 最多几个）。 */
    private static final String LANG_TAG_ACTUAL_GRID_TIP = "shanhai.machine.stellar_sample_assembler.tag.actualGridSharedTip";

    /** 标签页：上面那组格子「库存配置（选择要保留的种类）」。 */
    private static final String LANG_TAG_CONFIG_GRID_RENAMED =
            "shanhai.machine.stellar_sample_assembler.tag.configGridKept";

    /** 标签页：下面那组格子「ME 里可用的（与 ME 共享）」。 */
    private static final String LANG_TAG_ACTUAL_GRID_RENAMED =
            "shanhai.machine.stellar_sample_assembler.tag.actualGridShared";
    /** 通用「开」（tooltip 用）。 */
    private static final String LANG_STATE_ON = "shanhai.machine.stellar_sample_assembler.state.on";

    /** 通用「关」（tooltip 用）。 */
    private static final String LANG_STATE_OFF = "shanhai.machine.stellar_sample_assembler.state.off";

    /** 标签页：匹配预览（%s = 命中数，%s = 扫描总数）。 */
    private static final String LANG_TAG_PREVIEW = "shanhai.machine.stellar_sample_assembler.tag.preview";

    /** 标签页：还没有库存快照。 */
    private static final String LANG_TAG_PREVIEW_NO_DATA = "shanhai.machine.stellar_sample_assembler.tag.previewNoData";

    /** 标签页：表达式语法错（%s = 错误原文）。 */
    private static final String LANG_TAG_PREVIEW_ERROR = "shanhai.machine.stellar_sample_assembler.tag.previewError";
    /** 通配页：两行数字（样板数 / 已缓存）。 */
    private static final String LANG_WILDCARD_COUNTS = "shanhai.machine.stellar_sample_assembler.wildcard.counts";

    /** 通配页：左侧 3×2 信息组标题。 */
    private static final String LANG_WILDCARD_INFO_LEFT = "shanhai.machine.stellar_sample_assembler.wildcard.infoLeft";

    /** 通配页：右侧 3×2 信息组标题。 */
    private static final String LANG_WILDCARD_INFO_RIGHT = "shanhai.machine.stellar_sample_assembler.wildcard.infoRight";

    /** 标签页：上面那组「禁用态配置格」标题。 */
    private static final String LANG_TAG_CONFIG_GRID = "shanhai.machine.stellar_sample_assembler.tag.configGrid";

    /** 标签页：下面那组「实际物品格」标题。 */
    private static final String LANG_TAG_ACTUAL_GRID = "shanhai.machine.stellar_sample_assembler.tag.actualGrid";

    /** 标签页：底部标签行（%s = 生效的白名单，%s = 黑名单）。 */
    private static final String LANG_TAG_LABEL_ROW = "shanhai.machine.stellar_sample_assembler.tag.labelRow";
    /** 标签页：当前生效的白/黑名单（%s = 白，%s = 黑）。 */
    private static final String LANG_TAG_CURRENT = "shanhai.machine.stellar_sample_assembler.tag.current";

    // ---------------------------------------------------------------- lang 键（C 刀：配方种类选择器 + 标签页下半组）

    /** 通配页：当前类型不在宿主支持列表（%s = 类型 id）。 */
    private static final String LANG_WILDCARD_UNAVAILABLE = "shanhai.machine.stellar_sample_assembler.wildcard.unavailableType";

    /** 通配页：宿主一个配方类型都没有。 */
    private static final String LANG_WILDCARD_NO_HOST_TYPES = "shanhai.machine.stellar_sample_assembler.wildcard.noHostTypes";

    /** 通配页：选择器标题。 */
    private static final String LANG_WILDCARD_PICKER = "shanhai.machine.stellar_sample_assembler.wildcard.picker";

    // ⚠️ 2026-10-24：原 tag.matchedGrid 常量（「实际命中物品（前 %s 个）」）已删 ——
    //    标签页下半组那行标题改说人话：「ME 里可用的（与 ME 共享）」= tag.actualGridShared
    //    （「前 N 个」这层信息挪进 tooltip：tag.actualGridSharedTip，%s = 上限个数）。

    // ---------------------------------------------------------------- lang 键（阶段二新增）

    private static final String LANG_WILDCARD_SECTION = "shanhai.machine.stellar_sample_assembler.wildcard.section";

    private static final String LANG_WILDCARD_CONFIG = "shanhai.machine.stellar_sample_assembler.wildcard.config";

    private static final String LANG_WILDCARD_AUTO = "shanhai.machine.stellar_sample_assembler.wildcard.auto";

    private static final String LANG_WILDCARD_HINT = "shanhai.machine.stellar_sample_assembler.wildcard.hint";

    // ---------------------------------------------------------------- 阶段二：新增字段（3 个，已逐条比对不与父类撞名）

    /**
     * 本类自己的 {@code ManagedFieldHolder}。
     *
     * <p>🔴 <b>没有它，本轮新增的 {@code @Persisted} 字段就是死字段</b>：LDLib 只认
     * {@code getFieldHolder()} 返回的那条链上登记过的字段 —— 子类不重写 holder，
     * 新增字段既不会落盘也不会同步，而且<b>不报错</b>（又一个「静默不发生」）。
     * <p>构造签名 {@code (子类.class, 父类 holder)} 与本工程已验收的
     * {@code SuperWildcardPatternBufferPartMachine.MANAGED_FIELD_HOLDER} 逐字同形。
     */
    protected static final ManagedFieldHolder MANAGED_FIELD_HOLDER = new ManagedFieldHolder(
            StellarSampleAssemblerPartMachine.class, MEStockingPatternBufferPartMachine.MANAGED_FIELD_HOLDER);

    /**
     * 通配符样板槽（{@value #WILDCARD_SLOT_COUNT} 格）——<b>与父类 {@code patternInventory} 完全分离</b>的一份容器。
     *
     * <h2>为什么不塞进父类那 1080 格的数组（这是本轮最重要的一条设计决定）</h2>
     * 见类注释 §七：父类有 6 处直接 {@code internalInventory[slot]} 且<b>没有边界检查</b>，
     * 逻辑槽号一旦扩到 1130，任何一条 {@code onPatternChange(1080)} 就是
     * {@code ArrayIndexOutOfBoundsException}。独立容器 + <b>不改父类槽号空间</b> ⇒ 越界风险 = 0。
     *
     * <p>过滤：只收<b>通配符样板</b>（{@link ShanhaiWildcardCompat#isWildcardPattern(ItemStack)}，
     * 语义与 gtlcore 的包私有 {@code WildcardPatternCompatImpl.isWildcardPattern} 逐字等价）。
     * ⚠️ 宿主没装 {@code wildcard_pattern} 时该判断恒 false ⇒ 这 50 格会<b>拒绝一切物品</b>
     * （与已验收那台通配符总成同一行为，不新增风险）。
     *
     * <p>只标 {@code @Persisted}、<b>不标 {@code @DescSynced}</b>：与父类 {@code patternInventory} 逐字同款
     * （{@code javap -v} 实证它也只有 {@code @Persisted}），槽内物品由服务端权威、界面在创建时读取。
     */
    @Persisted
    private final ItemStackTransfer wildcardPatternInventory;

    /**
     * 「母槽中间配置」的<b>当前目标格</b>（0 基；{@code -1} = 还没选）。
     *
     * <p>设置方式：<b>中键</b>点通配符格。中键在本控件里的执行路径是
     * {@code mouseClicked} → {@code writeClientAction(10, …)} → 服务端
     * {@code handleClientAction(10, …)} 再跑一次 {@code onMiddleClick}（javap 实证，偏移 23–57 / 6–28）
     * ⇒ 服务端会真的执行，然后 {@code @DescSynced} 把这个 int 同步回客户端给标签显示。
     * <p>⚠️ 客户端也会本地跑一次同一个 lambda ⇒ handler 里必须 {@code if (isRemote()) return;}，
     * 否则客户端会写一个永远发不出去的本地值（父类 {@code onPatternChange} 也是这么防的）。
     */
    @DescSynced
    @Persisted
    private int wildcardConfigSlot = -1;

    /**
     * 当前目标格上<b>手填</b>的配方类型（归一化后的 id；{@code ""} = 没填 = 自动识别）。
     *
     * <p>🔴 它是<b>派生显示值</b>，唯一写入点是 {@link #shanhaiRefreshWildcardConfigType()}
     * （读目标格样板 NBT 里的 {@code gt_shanhai_recipe_type}）——<b>不是</b>第二份真相：
     * 权威永远是那块样板的 NBT，本字段只做「服务端算出、同步给标签画」。
     * <p>为什么必须同步：界面标签在客户端取值，而样板 NBT 所在的 {@code wildcardPatternInventory}
     * <b>不是</b> {@code @DescSynced}（见上）⇒ 客户端读不到它，只能读这个字符串。
     * <p>为什么用 {@code String} 而不是 {@code String[]}（50 个值）：LDLib 的
     * {@code @Persisted/@DescSynced} 复杂数组要么元素是 {@code IContentChangeAware}/{@code IManaged}、
     * 要么必须 {@code @LazyManaged}，写错就是启动/开界面期 {@code IllegalStateException}
     * （本工程 {@code catalystFluids} 那条血账）。单值 String 是已验证的安全类型
     * （本工程 {@code SuperWildcardPatternBufferPartMachine#autoForgeMultiplierReason} 就是
     * {@code @DescSynced String}）。50 个值各自存在<b>各自的样板 NBT</b> 里，不需要数组字段。
     */
    @DescSynced
    private String wildcardConfigType = "";

    // ---------------------------------------------------------------- 阶段三：新增字段（4 个，均已比对不撞名）

    /**
     * 闸开关：<b>歧义样板是否禁止参与推送</b>（默认 <b>true</b> = 禁止）。
     *
     * <p>语义（严格执行「不猜」）：
     * <ul>
     *   <li>唯一类型（UNIQUE）⇒ 放行；</li>
     *   <li>未标注（NONE）⇒ 放行（绝大多数样板压根没人写类型键，禁掉会让全机停摆）；</li>
     *   <li><b>歧义（AMBIGUOUS）⇒ 拦截</b>：同一块样板被多个来源写成了不同类型，说明样板自己都没说清，
     *       放它跑就是需求①「串配方」的入口。</li>
     * </ul>
     * <p>调 {@code false} 可临时全放行（排查用），但**默认真值必须是 true**。
     */
    @DescSynced
    @Persisted
    private boolean blockAmbiguousPatterns = true;

    /** 闸缓存：被拦下的槽号（{@link #getAvailablePatterns()} 每次都会读，不能每次重算 NBT）。 */
    private final java.util.Set<Integer> gateBlockedSlots = new java.util.HashSet<>();

    /**
     * <b>槽号 → 该槽的样板</b>反表（阶段九：样板内虚拟电路要用它）。
     *
     * <p>🔴 为什么要自建：父类的 {@code patternSlotMap} 是 <b>private</b>、
     * {@code getRealPattern(int, ItemStack)} 也是 <b>private</b>（且被 gtladditions {@code @Overwrite}）
     * ⇒ 源码层面拿不到「这个槽是哪个样板」。唯一可用的公开/受保护面是
     * {@code getAvailablePatterns()}（public）+ {@code getSlotIndexForPattern(pattern)}（protected）
     * —— 正好够反推，且与闸缓存同一个脏标记（样板一变就重建）。
     */
    private final java.util.Map<Integer, IPatternDetails> shanhaiPatternBySlot = new java.util.HashMap<>();
    /** 被闸拦下的「未标注类型」槽号（GUI/Jade 计数与 WARN 用）。 */
    private List<Integer> shanhaiUntypedBlockedSlots = List.of();

    /** 被闸拦下的「歧义」槽号。 */
    private List<Integer> shanhaiAmbiguousBlockedSlots = List.of();

    /**
     * 被拦下的「未标注配方类型」样板数（{@code @DescSynced}：GUI 与 Jade 都读它）。
     *
     * <p>🔴 为什么要同步：用户明确要求「拦截必须看得出来」——只在日志里打一行不算看得见。
     */
    @DescSynced
    private int shanhaiUntypedPatternCount = 0;

    /** 被拦下的「歧义」样板数。 */
    @DescSynced
    private int shanhaiAmbiguousPatternCount = 0;

    /** 闸缓存是否过期（样板变了 / 手填类型变了 ⇒ 置脏）。 */
    private boolean gateCacheDirty = true;

    /** 歧义 WARN 的节流：上一次的槽号集合指纹 + 时间。 */
    private int lastAmbiguousWarnHash = 0;

    private long lastAmbiguousWarnTick = Long.MIN_VALUE;

    // ---------------------------------------------------------------- 阶段四：新增字段

    /** ④ 神锻样板模式开关（默认关；开了只改「对 AE 声明的产出」）。 */
    @DescSynced
    @Persisted
    private boolean forgePatternMode = false;

    /** ④ 神锻倍率（1..30，默认 15）。 */
    @DescSynced
    @Persisted
    private int forgePatternMultiplier = ShanhaiForgePatternMode.DEFAULT_MULTIPLIER;

    /**
     * ④ <b>自动倍率开关</b>（默认<b>开</b> —— 与「超级通配符 ME 样板总成」那台<b>同一个默认值</b>）。
     *
     * <h2>🔴 2026-10-24（用户当场纠正）：「自动倍率不是我们自己写的功能吗？」—— 对，是我们自己的</h2>
     * 上一轮给星律写 host 实现时留了桩（{@code isAutoForgeMultiplierEnabled()} 恒 false、
     * {@code shanhaiAutoForgeMultiplierDriver()} 恒 null），注释还写成「仓室读不到控制器」——
     * <b>那是错的</b>：超级通配符那台同样是仓室、同样有 {@code getControllers()}，它能用。
     * 本轮把那一套<b>照原样</b>接过来（{@link ShanhaiAutoForgeMultiplierDriver} 一个字节没改，
     * 面板仍是共用的 {@link AutoForgeMultiplierPanel}）。
     *
     * <p>{@code @DescSynced}：开关状态在<b>客户端</b>那块面板上被读；不同步就只会显示另一套默认值。
     * <p>{@code @Persisted}：重载后保持上次的选择（与 {@link #forgePatternMode} 同款）。
     */
    @DescSynced
    @Persisted
    private boolean autoForgeMultiplier = true;

    /**
     * 最近一次<b>从控制器/模块读到的</b>倍率；{@link ShanhaiAutoForgeMultiplier#NO_READ} = 没读到。
     *
     * <p>🔴 {@code @DescSynced} 的理由与超级通配符那台逐字相同：<b>面板显示的数必须就是算法用的数</b>，
     * 读取只在服务端一处发生（{@link ShanhaiAutoForgeMultiplierDriver#refresh()}），
     * 结果同步下来 ⇒ 界面 / 日志 / 算法读同一个字段，不存在第二份真相。
     * <p>⚠️ 不带 {@code @Persisted}：运行时派生量，落盘没意义。
     */
    @DescSynced
    private int shanhaiAutoForgeMultiplierRead = ShanhaiAutoForgeMultiplier.NO_READ;

    /**
     * 上一次读取的<b>标注</b>：读到时是来源（{@code SOURCE_*}），读不到时是原因（{@code REASON_*}）。
     * <p>{@code @DescSynced}：面板第二行直接读它。
     */
    @DescSynced
    private String shanhaiAutoForgeMultiplierNote = ShanhaiAutoForgeMultiplier.REASON_AUTO_OFF;

    /**
     * 本机那份「自动倍率」驱动（探测 / 判定 / 文案 / 限流日志 / 节流计数全在它里面）。
     *
     * <p>⚠️ 刻意<b>不加</b> {@code @DescSynced}/{@code @Persisted}：它是纯服务端运行时状态。
     */
    private final ShanhaiAutoForgeMultiplierDriver shanhaiAutoForgeMultiplierDriver =
            new ShanhaiAutoForgeMultiplierDriver(this);

    /** {@code AUTOFSM} 那行判据的上一次指纹（状态翻转立刻打，否则 200 tick 一行）。 */
    private String shanhaiAutoFsmLogState = "";

    private long shanhaiAutoFsmLogTick = 0L;

    /**
     * ⑤ 通配符 50 格的<b>歧义位掩码</b>（bit i = 第 i 格是歧义）。
     *
     * <p>🔴 为什么是一位掩码而不是 {@code boolean[]}/`Verdict[]`：界面角标在<b>客户端</b>画，
     * 而样板 NBT 所在的 {@code wildcardPatternInventory} 不是 {@code @DescSynced}（客户端读不到），
     * 复杂数组又要么必须 {@code IContentChangeAware} 要门 {@code @LazyManaged}（本工程
     * {@code catalystFluids} 的血账）。一个 {@code long} 是「简单类型」，同步零风险，50 位够用。
     */
    @DescSynced
    private long wildcardAmbiguousMask = 0L;

    /**
     * 普通 1080 格的歧义位掩码（**十六进制串**，bit i = 槽 i；1080 bit ⇒ 270 字符）。
     *
     * <p>为什么是 String 而不是 {@code long[]}：1080 bit 装不进一个 long；
     * 而 LDLib 的「复杂数组」同步要么元素必须 IContentChangeAware、要么必须 {@code @LazyManaged}
     * （本工程 {@code catalystFluids} 的血账）⇒ 用**简单类型 String** 最安全。
     * <p>角标在客户端画，而样板 NBT 所在的 {@code patternInventory} 不是 {@code @DescSynced}
     * （客户端读不到）⇒ 只能靠这个串同步「哪些格是歧义」。
     */
    @DescSynced
    private String patternAmbiguousMaskHex = "";

    /**
     * 神锻改写后的样板 → 原槽号。
     *
     * <p>🔴 用 {@link java.util.IdentityHashMap}：AE 持有的是我们返回的那份实例，
     * 靠 <b>identity</b> 反查才不会被「两个内容相同的改写样板」互相覆盖。
     */
    private final java.util.Map<IPatternDetails, Integer> rewrittenToSlot = new java.util.IdentityHashMap<>();

    /** 神锻改写结果的快照（与 {@link #rewrittenToSlot} 同生共死；AE 每 tick 都会读它）。 */
    private final List<IPatternDetails> forgePatternSnapshot = new ArrayList<>();

    /** 神锻改写快照是否过期。 */
    private boolean forgeCacheDirty = true;

    // ---------------------------------------------------------------- 阶段五：展开器字段

    /**
     * 通配符<b>展开槽</b>（{@link ShanhaiWildcardLayout#EXPAND_COUNT} 个 = 50 × K）。
     *
     * <p>🔴 每条展开一份独立的 {@code InternalSlot} ⇒ 各自有自己的 itemInventory / fluidInventory /
     * 催化剂数组 ⇒ {@code pushPattern(stacks)} 只写进自己那一格 ⇒ **4 条展开不可能互相串料**。
     * 这正是需求①「防串配方」在物料层的落点（纯算术部分见 {@link ShanhaiWildcardLayout} 类注释 §四）。
     */
    private final List<MEPatternBufferPartMachineBase.InternalSlot> expandInternalSlots =
            new ArrayList<>(ShanhaiWildcardLayout.EXPAND_COUNT);

    /** 母槽号 → 该母槽本次展开出来的样板清单（截断到 K 条）。 */
    private final java.util.Map<Integer, List<IPatternDetails>> expansionsByMother = new java.util.HashMap<>();

    /** 展开出来的样板 → 展开槽号（identity 语义：AE 拿到的就是我们给的那份实例）。 */
    private final java.util.Map<IPatternDetails, Integer> expandedToSlot = new java.util.IdentityHashMap<>();

    /** 展开表是否过期。 */
    private boolean expandCacheDirty = true;

    /** 上一次截断告警的指纹（避免每次重建都刷屏）。 */
    private int lastTruncationStamp = 0;

    // ---------------------------------------------------------------- 阶段六 ⑦：库存输入 160（8×2×10）

    /**
     * ⑦ 的库存输入配置（<b>物品，160 格</b>）—— 与父类那份 32 格<b>并存</b>，不替换。
     *
     * <h2>为什么另建一份（字节码事实，不是选择）</h2>
     * 父类的 {@code stockItemHandler} 是 {@code protected final} 且容量在<b>超类构造器</b>里用常量
     * {@code CONFIG_SIZE = 32} 建死（{@code javap -v}：{@code ConstantValue: int 32}）⇒ 子类没有
     * 任何赋值机会，只能另建一份。
     *
     * <h2>为什么 {@code @Persisted} 能让 {@code final} 字段落盘</h2>
     * LDLib 对 {@code IManaged} 类型的持久化字段<b>不替换对象</b>，而是「就地反序列化」：
     * {@code IManagedAccessor.writeToReadonlyField} → {@code writePersistedFields(tag, value.getSyncStorage().getPersistedFields())}
     * （{@code javap -c} 实证，偏移 238–266）⇒ final 也没关系。父类那两份 handler 用的正是同一条路。
     * <p>它自己的持久化形状（同一套 LDLib 管线推出来的，样本见报告阶段六 §A）：
     * <pre>
     *   &lt;BE tag&gt;/shanhaiStockItemHandler = { inventory: [ {t:&lt;byte&gt;, p:{config:{id, #c, #}, stock:{...}}}, ... ] }
     * </pre>
     * 字段名就是键名（{@code @Persisted.key()} 默认空 ⇒ {@code IRef.getPersistedKey()} 回落字段名，
     * {@code javap -c} 偏移 7–24 实证）。
     */
    @Persisted
    private final MEStockingPatternBufferPartMachine.ExportOnlyAEStockingItemList shanhaiStockItemHandler;

    /**
     * ⑦ 的库存输入配置（<b>流体，160 格</b>）—— <b>方案 C</b>：我们自己另存一份，与父类那份 32 格<b>并存</b>。
     *
     * <h2>🔴 为什么类型直接用 gtlcore 的 {@code ExportOnlyAEStockingFluidList}</h2>
     * {@code javap -p} 实证它是 <b>public 的内嵌类</b>、构造器 public，显式参数只有
     * {@code (MetaMachine, int)}（第一参是内嵌类的 encloser，由 javac 隐式补齐 —— 与物品侧
     * {@code ExportOnlyAEStockingItemList} 逐字同形，写错会直接编译失败，不会静默）：
     * <pre>
     *   public org.gtlcore...$ExportOnlyAEStockingFluidList(
     *          org.gtlcore...MEStockingPatternBufferPartMachine,   // this$0（隐含第一参）
     *          com.gregtechceu.gtceu.api.machine.MetaMachine, int);
     * </pre>
     * 它的 {@code extractStock} / {@code getAvailableAmount} / {@code syncStock} 用的都是
     * {@code this$0.actionSource} 与 {@code this$0.getMainNode()}（{@code javap -c} 偏移 6–57 实证）
     * ⇒ 我们 new 出来的这一份<b>天然用本机自己的 actionSource 从 ME 抽料</b>，
     * 不需要（也<b>不许</b>）另写一条 ME 抽取路径。
     *
     * <h2>注解组合（与父类 {@code stockFluidHandler} 逐字相同）</h2>
     * 只有 {@code @Persisted}（{@code javap -v} 原文；物品侧 {@code shanhaiStockItemHandler} 同款）。
     * 它是<b>单个 {@code IManaged} 对象</b>、不是「引用类型数组」⇒ <b>不吃</b> {@code @LazyManaged}
     * 那条铁律（铁律针对的是 {@code catalystFluids} 那种 {@code FluidTransferList[]}）。
     * 落盘形状由它自己的 {@code MANAGED_FIELD_HOLDER} 决定：{@code ExportOnlyAEFluidList.inventory}
     * 带 {@code @Persisted}（{@code javap -v} 实证）⇒ 160 格的配置会逐格存进本机的 BE tag。
     */
    @Persisted
    private final MEStockingPatternBufferPartMachine.ExportOnlyAEStockingFluidList shanhaiStockFluidHandler;

    /**
     * ⑦ 的当前页号（1..10）。持久化 + 同步：翻页只是界面状态，但用户重开界面回到上次那页更顺手。
     *
     * <p>它由 {@link AEDualConfigWidget} 的 {@code pageSetter} 回调写入；越界一律夹住。
     */
    @DescSynced
    @Persisted
    private int shanhaiStockPage = 1;

    /**
     * 旧 32 条配置<b>已经迁移过</b>的标记（幂等闸）。
     *
     * <p>🔴 为什么必须有它，而不是「只在我们的 160 格为空时迁移」：
     * 用户可能把迁过来的 160 条<b>全删掉</b>；那种情况下父类那 32 格仍留着旧数据
     * （我们刻意不动它，留作回滚/取证），若只按「我们为空」判断，删光后重进存档会把
     * 32 条旧配置<b>当成幽灵复活</b>。所以迁移<b>只做一次</b>，之后永不再做。
     */
    @Persisted
    private boolean shanhaiStockMigrated = false;

    /**
     * <b>流体侧</b>旧 32 条配置<b>已经迁移过</b>的标记（与物品侧 {@link #shanhaiStockMigrated} 同款的一刀性闸）。
     *
     * <p>🔴 同样<b>不能</b>用「两边都空」来置标记：{@code onLoad()} 与 {@code loadCustomPersistedData(tag)}
     * 的先后不是我们能保证的（见 {@link ShanhaiStockConfigLayout.MigrationAction#NOTHING_TO_COPY}）。
     * 判定一律走 {@link ShanhaiStockConfigLayout#migrationAction(boolean, int, int)} 这个纯函数，
     * 机器侧<b>不再自己写 if</b>。
     */
    @Persisted
    private boolean shanhaiStockFluidMigrated = false;

    /** ⑦ 的「有配置」缓存（{@code findStockItemKey} 每次解析配方都要问，不能每次扫 160 格）。 */
    private final java.util.Set<AEItemKey> shanhaiStockItemKeys = new java.util.LinkedHashSet<>();

    /**
     * ⑦ 流体侧的「配了哪些流体键」缓存（三修 ②）：{@code findStockFluidKey} 每次解析配方都要问，
     * 不能每次扫 160 格。重建时机 = 配置变更回调 / 周期性库存同步（与物品侧同一套）。
     *
     * <p>⚠️ 它扫的是<b>我们自己的 160 格</b>（{@link #shanhaiStockFluidHandler}），不是父类那 32 格；
     * 也不依赖 {@code ExportOnlyAEStockingFluidList.configList} 这份可能陈旧的清单缓存
     * （那正是三修 ② 要补的那一刀：{@code configList} 只在 {@code onConfigChanged()} 时重建）。
     */
    private final java.util.Set<AEFluidKey> shanhaiStockFluidKeys = new java.util.LinkedHashSet<>();

    /** ⑦ 的库存快照（每游戏 tick 最多重建一次；{@code findStockItemKey} 判「够不够」用它）。 */
    private KeyCounter shanhaiStockSnapshot;

    /** 库存快照对应的游戏时间（{@link Long#MIN_VALUE} = 还没有）。 */
    private long shanhaiStockSnapshotTick = Long.MIN_VALUE;

    /** 迁移日志只打一次（进游戏看日志时不会刷屏）。 */
    private boolean shanhaiStockMigrationLogged = false;

    /** 流体侧迁移日志只打一次（与物品侧分开计数，否则「先物品后流体」会把流体那行吞掉）。 */
    private boolean shanhaiStockFluidMigrationLogged = false;

    // ---------------------------------------------------------------- 阶段七 ⑧ 标签过滤页（白/黑名单）

    /**
     * 标签过滤页：<b>白名单表达式</b>（空 = 不过滤）。
     *
     * <p>类型选 {@code @DescSynced String} 而不是数组：本工程已验证 String 是最安全的同步类型
     * （阶段二 {@code wildcardConfigType} 的先例），而这一个字符串本身就是用户输入的全部内容。
     * <p>⚠️ 它<b>只被标签页的预览与（将来的）过滤使用</b>；本阶段不参与库存扣料逻辑（见页注释）。
     */
    @DescSynced
    @Persisted
    private String shanhaiTagWhite = "";

    /** 标签过滤页：<b>黑名单表达式</b>（黑名单命中 ⇒ 排除，优先于白名单）。 */
    @DescSynced
    @Persisted
    private String shanhaiTagBlack = "";

    /**
     * 标签过滤库存输入**总开关**（默认 false = 这个功能等于没装）。
     *
     * <p>关 ⇒ 不做匹配、不供料（连匹配预览都不算）；开 ⇒ 才按白/黑名单从 ME 里供应（与 ME 共享内容物，不搬物品）。
     */
    @DescSynced
    @Persisted
    private boolean shanhaiTagFilterEnabled = false;

    /**
     * 标签过滤页：<b>匹配预览</b>（服务端算出来的一行文字，同步给客户端显示）。
     *
     * <p>它让这一页「有真信息」：数字来自当前 ME 库存快照 + {@link ShanhaiTagFilterExpression} 实算。
     * <p>为什么不 {@code @Persisted}：它是派生值（库存一变就过期），存进存档只会变成「上次关服时的旧数」。
     */
    @DescSynced
    private String shanhaiTagPreview = "";

    // ================================================================ C 刀 · A 段：通配符格的中键面板

    /** 每个通配符母槽的「物品催化剂」格数 = 3×3（与父类 {@code catalystItems} 每项同值）。 */
    private static final int WILDCARD_CATALYST_ITEM_SLOTS = 9;

    /** 每个通配符母槽的「流体催化剂」罐数 = 3×3。 */
    private static final int WILDCARD_CATALYST_FLUID_TANKS = 9;

    /** 每个流体催化剂罐的容量（父类 {@code lambda$new$3} 的字节码常量：{@code 16L * FluidHelper.getBucket()}）。 */
    private static final long WILDCARD_CATALYST_TANK_CAPACITY = 16L * FluidHelper.getBucket();

    /**
     * 通配符母槽的<b>物品催化剂</b>：下标 = 母槽号，每项 {@value #WILDCARD_CATALYST_ITEM_SLOTS} 格。
     *
     * <h2>为什么必须<b>另建一份</b>（字节码事实）</h2>
     * 父类的 {@code catalystItems} 是 {@code new ItemStackTransfer[maxPatternCount]}，
     * {@code maxPatternCount = 1080} ⇒ 母槽号 1080..1129 <b>根本不在那个数组里</b>；
     * 而 {@code PatternBufferInternalSlot} 的催化剂判定看的是<b>它自己那份 map</b>
     * （{@code hasItemCatalystInventory()} = {@code itemCatalystInventory.isEmpty()} 取反，
     * {@code javap -c} 原文）⇒ 只要我们<b>自己往那份 map 里填</b>，催化剂对通配符槽就是真生效的
     * （见 {@link #shanhaiRecalculateWildcardCatalyst(int)}）。
     *
     * <p>⚠️ 注解与父类同款：{@code ItemStackTransfer[]} 只标 {@code @Persisted}（父类
     * {@code catalystItems} 的 {@code javap -v} 原文就是只有这一个注解）。
     */
    @Persisted
    private final ItemStackTransfer[] shanhaiWildcardCatalystItems =
            new ItemStackTransfer[WILDCARD_SLOT_COUNT];

    /**
     * 通配符母槽的<b>流体催化剂</b>：下标 = 母槽号，每项 {@value #WILDCARD_CATALYST_FLUID_TANKS} 罐
     * × {@value #WILDCARD_CATALYST_TANK_CAPACITY} mB。
     *
     * <p>⚠️ {@code @LazyManaged} 与父类 {@code catalystFluids} 的注解组合<b>逐字相同</b>
     * （{@code javap -v}：{@code @Persisted} + {@code @LazyManaged}）—— 复合数组不加它会在
     * 反序列化期抛异常（本工程 {@code catalystFluids} 那条血账）。
     */
    @Persisted
    @LazyManaged
    private final FluidTransferList[] shanhaiWildcardCatalystFluids =
            new FluidTransferList[WILDCARD_SLOT_COUNT];

    /**
     * 通配符母槽的「<b>实际配方数量</b>」：下标 = 母槽号，出厂默认 <b>1</b>
     * （与父类 {@code cacheRecipeCount} 构造器里的 {@code Arrays.fill(.., (byte) 1)} 同值）。
     *
     * <p>⚠️ <b>这个数组对象本身会被交给界面控件</b>（{@code MEPatternCatalystUIManager} 是直接往里面写的）
     * ⇒ 中间<b>绝不</b>再插一层拷贝。
     */
    @DescSynced
    @Persisted
    private final byte[] shanhaiWildcardCacheCount = new byte[WILDCARD_SLOT_COUNT];

    /**
     * 通配符母槽的「<b>编程电路</b>」：下标 = 母槽号，{@link ShanhaiSlotCircuit#NONE}（= 0）= 未设置。
     *
     * <p>语义：面板里点 1..32 的任意一个按钮 ⇒ 记在这里 ⇒ {@link #getCircuitForRecipe(int)}
     * 对这个母槽（以及它展开出来的各条）返回该电路。<b>不消耗任何电路物品</b>（只读一个数）。
     * <p>⚠️ 0 号按钮与 ghostCircuit 的「清除」键同义（都是「不设置」）——与 {@code ShanhaiCircuitGrid}
     * 的既有结论逐字一致，不是本轮新口径。
     */
    @DescSynced
    @Persisted
    private final byte[] shanhaiWildcardCircuit = new byte[WILDCARD_SLOT_COUNT];

    // ================================================================ C 刀 · B 段：配方种类选择器

    /**
     * <b>宿主真正支持的配方类型 id 列表</b>（逗号分隔，服务端算、同步给界面画按钮）。
     *
     * <h2>为什么由服务端算、客户端只读（不直接调 getControllers）</h2>
     * {@code getControllers()} 是 {@code IMultiPart} 的 public 方法（{@code javap} 实证，本类可随时调用），
     * {@code GTRecipeType.registryName} 也是 {@code public final}（同）——两条前置都成立。
     * 但那个 List 是<b>服务端结构成型时</b>由 {@code addedToController} 填的运行时状态，
     * 客户端那份部件机器上一般是空的 ⇒ 界面若自己去问，会画出一个空列表。
     * 所以：<b>服务端算 → 拼成一行字符串 → {@code @DescSynced}</b>，界面只负责拆开画。
     * <p>类型选的字段类型（{@code String}）与 {@link #wildcardConfigType} 同款：本工程已验证的同步类型。
     */
    @DescSynced
    private String shanhaiStockDiagLine = "";

    /**
     * 宿主支持的配方类型 id 串（逗号分隔）。<b>界面已不再依赖它</b>（数据源改成 widget 初始数据，
     * 见 {@code WildcardTypePicker#writeInitialData}）；这里保留只为调试对照。
     */
    private String shanhaiHostRecipeTypeIds = "";

    /**
     * <b>本仓室所在控制器的显示名</b>（服务端算 → {@code @DescSynced} 下发；空串 = 没有控制器）。
     *
     * <h2>🔴 为什么必须服务端算（三修 ① 的关键一步）</h2>
     * {@code getControllers()} 是「多方块成型时由 {@code addedToController} 填进去」的<b>运行时状态</b>，
     * 而 {@code createUIWidget} 会在<b>两侧</b>各建一次控件树 —— <b>客户端那份 {@code getControllers()} 一般是空的</b>
     * （本类 {@link #shanhaiHostRecipeTypeIds} 的注释与阶段十八的 picker 已经实证过同一条）。
     * ⇒ 界面若在客户端自己问「有没有控制器」，就会<b>永远</b>答「没有」——
     * 那正是用户截图里那句「未成型（无控制器）」的来历（假话，因为机器明明装在一座成型多方块里）。
     * <p>所以口径与 {@link #shanhaiStockDiagLine} 完全一致：服务端算 → 一行字符串 → 客户端只读。
     */
    @DescSynced
    private String shanhaiForgeHostName = "";

    /** 客户端诊断节流（不落盘、不同步）：上一次打 PICKER ids 日志的游戏时间。 */
    private long shanhaiPickerDiagTick = Long.MIN_VALUE;

    // ---- 2026-10-24（问题 2「库存配好了却不工作」的判据）：
    //      三份都是【纯诊断】状态，不落盘、不同步 —— 它们只决定「STOCKTRIGGER 那一行打不打」。
    /** 上一次 STOCKTRIGGER 打日志时的游戏时间（节流 400 tick = 20 秒）。 */
    private long shanhaiStockTriggerLoggedTick = Long.MIN_VALUE;

    /** 上一次打日志时的「无样板」状态（状态翻转时立刻打，不等节流窗口）。 */
    private boolean shanhaiStockTriggerLoggedState;

    /** 上一次打日志时的「有没有库存配置」状态。 */
    private boolean shanhaiStockTriggerLoggedCfg;

    /**
     * 库存解析诊断节流（不落盘、不同步）：上一次打 STOCKFIND 日志的游戏时间。
     *
     * <p>⚠️ <b>诚实记录（2026-10-24 收窄这条日志时发现的，未擅自改）</b>：初值用
     * {@link Long#MIN_VALUE} 当「从没打过」的哨兵，而判据是
     * {@code stockDiagNow - 这个字段 >= 200}；{@code int64} 环绕 ⇒
     * {@code 任意正数 - Long.MIN_VALUE} 得一个<b>负数</b>（实测 1000 - MIN = −9223372036854774808）
     * ⇒ 判据<b>永远不成立</b> ⇒ 这条 STOCKFIND 行在真机上<b>从来没打出来过</b>。
     * 🔴 2026-10-24 修正：初值改为 {@code 0L}。原因是原初值 {@code Long.MIN_VALUE} 让
     * {@code now - tick} 溢出成负数（实测 1000 − MIN = −9223372036854774808），判据恒 false ⇒
     * 收窄等于 no-op、我们<b>根本拿不到任何诊断数据</b>。现在按 200 tick（10 s）节流真正生效，
     * 且只在 {@code stockCfg>0 && amount>0}（即"真有可能命中"）时才打 ⇒ 不会刷屏。
     */
    private long shanhaiStockFindDiagTick = 0L;

    /**
     * 流体侧解析诊断节流（不落盘、不同步）：上一次打 {@code STOCKFLUID} 日志的游戏时间。
     *
     * <p>⚠️ 初值必须是 {@code 0L}，<b>不能</b>学上面那个旧的 {@code Long.MIN_VALUE} 哨兵：
     * {@code now - Long.MIN_VALUE} 在 int64 上会环绕成负数 ⇒ 判据恒 false ⇒ 日志永远打不出来
     * （上面那段注释就是那次踩坑的实录）。</p>
     */
    private long shanhaiStockFluidFindDiagTick = 0L;

    // ---- 2026-10-24（「库存供料被拦」这一刀）：放行快照 + STOCKSUPPLY 判据
    //
    //  用户原话：「库存拉取是不需要防止串配方的，所以不用拦，直接放过就行」。
    //  字节码结论（javap -c 全文见 temp\stellar-p1\javap\*.txt）：
    //    · 供料链的每一跳（handleItemInternal / findStockItemKey / consumeStockItem / extractStock）
    //      **没有**任何 blockAmbiguousPatterns / gateBlockedSlots / 配方类型判定 ⇒ 闸不在链上；
    //    · 但供料链的**入口**是 MEPatternRecipeHandlePart#handleRecipe：它先按 recipes2SlotsMap（样板认领）
    //      取槽，取不到就退到 **getActiveSlots()**；而父类的 isActive() 要求「槽里已经躺着东西」⇒
    //      「靠库存供料」的槽（本来就该是空的）永远不在这个名单里 ⇒ handleItemInternal 一次都不会被调用
    //      （这正是上一轮日志里 STOCKFIND=0 / STOCKTRIGGER activeSlots=0 的成因）。
    //  ⇒ 放行点选在 getActiveSlots()：把「有样板 + 库存有配置」的普通槽追加进去，
    //    样板执行那条链（getAvailablePatterns / pushPattern）一个字不动。

    /**
     * 「库存可以直接供料」的<b>样板槽号</b>快照（只有 {@code < PATTERN_SLOTS} 的 0..1079 段，理由见
     * {@link #shanhaiRebuildStockSupplySlots()}）。
     *
     * <p>为什么要缓存：{@link #getActiveSlots()} 在配方搜索期会被反复调用，而父类那份要扫 1330 格；
     * 我们这份只在「样板变了 / 库存配置变了 / 一次库存同步」时重建，读取是 O(1) 取数组。
     */
    private int[] shanhaiStockSupplySlots = new int[0];

    /** 上面那份快照是否过期（样板变 / 配置变 / 存档载入 ⇒ 置脏）。 */
    private boolean shanhaiStockSupplyDirty = true;

    /** 上一次重建时被上限砍掉的槽数（0 = 没砍；>0 会打一条 WARN，绝不静默）。 */
    private int shanhaiStockSupplyCapped = 0;

    /** 上限 WARN 的节流（6000 tick = 5 分钟；状态变了立刻打）。 */
    private long shanhaiStockSupplyCapWarnTick = Long.MIN_VALUE;

    /**
     * 放行槽数的**上限**。
     *
     * <p>为什么要上限：这些槽会被 {@code MEPatternRecipeHandlePart#handleRecipe} 的兜底循环
     * 逐个 {@code meHandleRecipe}（javap -c 偏移 275–418）⇒ 槽数越多，配方搜索越贵。
     * 128 已经远大于一台"库存供料"机器真正会同时用到的槽数；超了会打 WARN 并把数字说出来。
     */
    private static final int SHANHAI_STOCK_SUPPLY_SLOT_CAP = 128;

    /** STOCKSUPPLY 判据的上一次状态指纹（状态翻转时立刻打，不等节流窗口）。 */
    private String shanhaiStockSupplyLogState = "";

    /** STOCKSUPPLY 判据的上一次打日志时间（节流 200 tick = 10 秒；初值必须 0L，不能学 Long.MIN_VALUE）。 */
    private long shanhaiStockSupplyLogTick = 0L;

    // ---- 2026-10-24 二修（「库存直供还是不行」）：四段调用计数 + 活跃槽现场 + 越界边界证明
    //
    //  用户原话：「星律库存直供还是不行，机器还是不跑」。
    //  上一轮的日志只能看到「活跃槽有几个」，看不到「那个槽被问到哪一步就断了」。
    //  这四个计数器就是那条链的四个断点（顺序 = 字节码里的调用顺序，javap -c 实证）：
    //    ① ShanhaiStockingSlot#isItemActive       <- MEItemInputHandlerBase#meHandleRecipeInner 偏移 16
    //    ② ShanhaiStockingSlot#handleItemInternal <- 同一个方法 偏移 71
    //    ③ ShanhaiMEPatternTrait#hasCacheInSlot   <- MEPatternRecipeHandlePart#handleRecipe 偏移 378
    //    ④ findStockItemKey / findStockFluidKey   <- handleItemInternal 体内（STOCKSUPPLY 的唯一发出点）
    //  ⇒ 谁先变 0，断点就在它前一跳。判读表见 temp\stellar-p1\报告-库存直供二修.md §3。

    /** ①②③④ 的总调用次数（hi* = 槽号 >= PATTERN_SLOTS，即通配符母槽/展开槽那一段）。 */
    private long shanhaiProbeIsItemActive = 0L;

    private long shanhaiProbeHandleItem = 0L;

    private long shanhaiProbeHasCache = 0L;

    private long shanhaiProbeFindItem = 0L;

    private long shanhaiProbeFindFluid = 0L;

    private long shanhaiProbeHiIsItemActive = 0L;

    private long shanhaiProbeHiHandleItem = 0L;

    private long shanhaiProbeHiHasCache = 0L;

    private long shanhaiProbeHiFindItem = 0L;

    /** STOCKSLOT 那条现场日志的上一次打点（节流 200 tick；初值 Long.MIN_VALUE 表示「还没打过」）。 */
    private long shanhaiProbeLogTick = Long.MIN_VALUE;

    /** STOCKSAFE 那条「不会越界」的边界证明只打一次（Long.MIN_VALUE = 还没打）。 */
    private long shanhaiSafeLoggedTick = Long.MIN_VALUE;

    // ---- 2026-10-24 三修（「机器仍然不工作」＋ 实机日志 `anyPattern=false`）：样板到底在谁身上
    //
    //  实机日志（新 jar 97D7714F32E09785）：
    //    STOCKSLOT … active=0 supply=0 calls[isItemActive=0 handleItem=0 hasCache=4 …] ⇒ 活跃名单为空
    //    STOCKTRIGGER activeSlots=0 stockConfigured=true anyPattern=false ⇒ 本机一块样板都没有
    //    [SHANHAI-RLPROBE] setup_gate … match=null  /  finish_gate … match=gtceu:dummy/raw
    //  ⇒ 两个可能，必须分开：① 样板真的不在本机（最可能在同一个多方块里的**别的仓室**上）；
    //                          ② 样板在本机，但我们的 hasPatternInSlot 对通配符段判错。
    //  下面两条日志就是这条判据：STOCKPAT 交叉核对「容器真值 vs 覆写结论」，STOCKWHO 报「同一结构里还有谁持有样板」。

    /** STOCKPAT / STOCKWHO 的上一次指纹 + 打点（状态变了立刻打，否则 6000 tick 一行）。 */
    private String shanhaiPatternCensusState = "";

    private long shanhaiPatternCensusTick = Long.MIN_VALUE;

    // ================================================================ C 刀 · C 段：标签页下半组的真实数据

    /**
     * 标签页下半组那 8×2 格要画的<b>命中物品 id 的前 16 个</b>（逗号分隔，服务端在刷新匹配预览时顺手写）。
     *
     * <p>🔴 客户端按 id 反查物品再画图标；<b>反查不到就留空，绝不用占位物品凑数</b>
     * （见 {@link #shanhaiMatchedIconTexture(int)}）。只同步 id 字符串而不是物品栈数组：
     * 复杂数组要 {@code IContentChangeAware}/{@code @LazyManaged}，一个 String 零风险。
     */
    @DescSynced
    private String shanhaiTagMatchedIcons = "";

    /** 标签页下半组最多画几个（用户口径：前 16 个）。 */
    private static final int TAG_MATCHED_ICON_LIMIT = ShanhaiRecipeTypeLabels.ICON_LIMIT;


    /**
     * 构造器。
     *
     * <p>🔴 三个实参的来历，一个都不能改：
     * <ul>
     *   <li>{@code holder} —— 透传；</li>
     *   <li>{@code PATTERN_SLOT_COUNT}（1080）—— 父类用它建<b>全部</b>样板槽相关数组（见常量注释）；
     *       ⚠️ 阶段二<b>没有</b>把它改成 1130：见类注释 §七（那样会让父类数组越界）。</li>
     *   <li>{@link IO#BOTH} —— <b>硬条件</b>：{@code MEPatternBufferPartMachine} 构造器
     *       {@code if (io == IO.BOTH) getMainNode().addService(IGridTickable.class, new Ticker(this))}
     *       （{@code javap -c} 偏移 247–274）。传别的值 ⇒ 产物永远不退给 ME 网络，
     *       而且<b>不报错</b>（悄悄吞产物）。</li>
     * </ul>
     */
    public StellarSampleAssemblerPartMachine(IMachineBlockEntity holder, IO io) {
        super(holder, PATTERN_SLOT_COUNT, io);
        this.wildcardPatternInventory = new ItemStackTransfer(WILDCARD_SLOT_COUNT);
        this.wildcardPatternInventory.setFilter(ShanhaiWildcardCompat::isWildcardPattern);
        this.wildcardPatternInventory.setOnContentsChanged(this::shanhaiOnWildcardInventoryChanged);
        // ---- 阶段三：把 50 格通配符槽接进「槽号空间」
        // ① 父类那份终端库存句柄先取下来（合并视图要用它当 0..1079 那一段）。
        this.baseTerminalPatternInventory = super.getTerminalPatternInventory();
        this.combinedTerminalPatternInventory = new CombinedTerminalPatternInventory();
        // ② 50 个 InternalSlot：**自己造**（父类构造器那个循环只到 1080，且给它挂的内容变更回调会写
        //    cacheRecipe[slot] —— 通配符槽绝不能走那条路）。
        for (int i = 0; i < WILDCARD_SLOT_COUNT; i++) {
            final MEPatternBufferPartMachineBase.InternalSlot slot = this.createInternalSlot(PATTERN_SLOT_COUNT + i);
            // 空回调：只保证 getOnContentsChanged() 非 null（别处调用时不会 NPE），
            // **不**接父类那套 recipe-cache 失效逻辑。
            slot.setOnContentsChanged(() -> { });
            this.wildcardInternalSlots.add(slot);
        }
        // ③ 阶段五：展开槽（母槽 i 的第 j 条 ⇒ i*K+j）。同样不挂父类回调（它会写 cacheRecipe[slot]）。
        for (int i = 0; i < ShanhaiWildcardLayout.EXPAND_COUNT; i++) {
            final MEPatternBufferPartMachineBase.InternalSlot slot =
                    this.createInternalSlot(ShanhaiWildcardLayout.EXPAND_OFFSET + i);
            slot.setOnContentsChanged(() -> { });
            this.expandInternalSlots.add(slot);
        }
        // ---- 阶段六 ⑦：库存输入 160 格（8×2×10）
        // ① 真正干活的 160 格：**自己 new**（父类那份 32 格还在，见字段注释）。
        //    🔴 构造器签名修正（阶段六实证）：javap 打出来的是
        //    `ExportOnlyAEStockingItemList(MEStockingPatternBufferPartMachine, MetaMachine, int)`，
        //    其中**第一个参数是内嵌类的 encloser 实例**，显式参数只有 `(MetaMachine, int)`。
        //    阶段五报告把它读成 `(MEStocking, MetaMachine, int)` 是误读 —— 以本轮 javac 的报错与
        //    javap 的 `this$0` 字段为准（写错会直接编译失败，不会静默）。
        this.shanhaiStockItemHandler =
                new ExportOnlyAEStockingItemList(this, ShanhaiStockConfigLayout.CONFIG_SIZE);
        // ② 流体侧（方案 C）：**自己也 new 一份 160 格**，与物品侧等长 ⇒ 面板直接接它，不需要转发窗口。
        //    javap 依据：ExportOnlyAEStockingFluidList 是 public 内嵌类，构造器 public，
        //    显式参数 `(MetaMachine, int)`（第一参 this$0 由 javac 隐式补齐，与上一行物品侧同形）。
        //    它的抽取/同步都用 this$0.actionSource + this$0.getMainNode() ⇒ 抽料走本机自己的账。
        this.shanhaiStockFluidHandler =
                new ExportOnlyAEStockingFluidList(this, ShanhaiStockConfigLayout.CONFIG_SIZE);
        // ---- C 刀 · A 段：通配符母槽的三份「每槽存储」（面板要用的那些）
        // ① 「实际配方数量」出厂 1 —— 与父类构造器 `Arrays.fill(cacheRecipeCount, (byte) 1)` 同值。
        java.util.Arrays.fill(this.shanhaiWildcardCacheCount, (byte) 1);
        // ② 「编程电路」出厂 = ShanhaiSlotCircuit.NONE（0 = 未设置，与清除键同义）。
        java.util.Arrays.fill(this.shanhaiWildcardCircuit, (byte) ShanhaiSlotCircuit.NONE);
        // ③ 每槽 3×3 物品催化剂 / 3×3 流体催化剂。容量与父类逐值相同（javap：9 格 / 9 罐 / 16 桶每罐）。
        for (int i = 0; i < WILDCARD_SLOT_COUNT; i++) {
            final int mother = i;
            this.shanhaiWildcardCatalystItems[i] = new ItemStackTransfer(WILDCARD_CATALYST_ITEM_SLOTS);
            this.shanhaiWildcardCatalystItems[i].setOnContentsChanged(
                    () -> this.shanhaiRecalculateWildcardCatalyst(mother));
            this.shanhaiWildcardCatalystFluids[i] = new FluidTransferList(java.util.stream.Stream
                    .generate(() -> (IFluidTransfer) new FluidStorage(WILDCARD_CATALYST_TANK_CAPACITY))
                    .limit(WILDCARD_CATALYST_FLUID_TANKS)
                    .toList());
        }
        // TODO(阶段五)：产物 buffer（MEPatternBufferPartMachineBase#buffer）不落盘 ——
        //  断线 / 区块卸载 / 拆机器时里面还没退款的产物会消失。修法见交付报告「分刀计划」第五刀。
        //  本轮【刻意不做】：它要动 saveCustomPersistedData/loadCustomPersistedData 的成对读写，
        //  做歪了会把库存输入一起写坏。
    }

    @Override
    public ManagedFieldHolder getFieldHolder() {
        return MANAGED_FIELD_HOLDER;
    }


    // ---------------------------------------------------------------- 阶段八 ③：版面配置读取

    /** 配置的行数（夹到 1..8；读不到配置时用默认 6）。 */
    private static int shanhaiConfiguredPatternRows() {
        try {
            return ShanhaiStellarUiLayout.clampPatternRows(
                    com.shanhai.config.ShanhaiConfig.COMMON.stellarPatternRowsPerPage.get());
        } catch (Throwable unavailable) {
            return ShanhaiStellarUiLayout.DEFAULT_PATTERN_ROWS_PER_PAGE;
        }
    }

    /** 配置的页数（夹到 1..120；读不到配置时用默认 20）。 */
    private static int shanhaiConfiguredPatternPages() {
        try {
            return ShanhaiStellarUiLayout.clampPatternPages(
                    com.shanhai.config.ShanhaiConfig.COMMON.stellarPatternPages.get());
        } catch (Throwable unavailable) {
            return ShanhaiStellarUiLayout.DEFAULT_PATTERN_PAGES;
        }
    }

    /** 界面可见的普通样板槽数（= 分页器的 maxPatternCount）。 */
    private static int shanhaiConfiguredVisiblePatternSlots() {
        return ShanhaiStellarUiLayout.patternVisibleSlots(
                shanhaiConfiguredPatternRows(), shanhaiConfiguredPatternPages());
    }

    /**
     * 配置把可见槽数调小后，检查「有没有样板被挤到看不见的地方」并打一条 WARN。
     *
     * <p>🔴 用户要求：<b>页数变小不能把已有样板弄丢</b>。本实现里<b>数据根本不会丢</b>
     * （底层 storage 与槽号空间都没变），但「看不见」也必须说出来 —— 否则用户会以为样板消失。
     */
    private void shanhaiWarnInvisiblePatterns(@NotNull String where) {
        if (this.isRemote()) {
            return;
        }
        final int visible = shanhaiConfiguredVisiblePatternSlots();
        if (visible >= PATTERN_SLOT_COUNT) {
            return;
        }
        int hidden = 0;
        final java.util.List<Integer> firstFew = new ArrayList<>();
        for (int slot = visible; slot < PATTERN_SLOT_COUNT; slot++) {
            if (this.hasPatternInSlot(slot)) {
                hidden++;
                if (firstFew.size() < 8) {
                    firstFew.add(slot);
                }
            }
        }
        if (hidden > 0) {
            ShanhaiStellarLog.warn("版面配置（{}）：可见槽数被设为 {}（行×页配置），有 {} 个样板在可见范围之外"
                    + "（例如槽 {}）—— 数据仍在存档里，只是界面翻不到；调回页数即可重新看到",
                    where, visible, hidden, firstFew);
        }
    }
    // ---------------------------------------------------------------- GUI（阶段七：左侧竖排页签 + 四页）

    /**
     * 本机主界面 = <b>左侧一列页签（4 页 + 2 个开关按钮）＋ 右侧页区</b>。
     *
     * <h2>🔴 复用的上游组件：LDLib 的 {@code TabContainer} / {@code TabButton}（不是自造）</h2>
     * <pre>
     *   com.lowdragmc.lowdraglib.gui.widget.TabContainer   // 竖排页签容器（TABS_LEFT 贴图）
     *   com.lowdragmc.lowdraglib.gui.widget.TabButton      // 单个页签（SwitchWidget，pressed = 高亮）
     *   addTab(TabButton, WidgetGroup)                     // 页 = 一个 WidgetGroup；首个 tab 自动成为 focus
     *   switchTag(WidgetGroup)                             // 切页只做 setPressed/setVisible/setActive
     * </pre>
     * 依据（{@code javap -c}）：{@code TabContainer.initTemplate} 的页签惯例是
     * {@code new TabButton(-28, 0, 32, 28)} + {@code TABS_LEFT.getSubTexture(...)}（x 为负 ⇒ 伸到容器左外侧）；
     * {@code addTab} 把页加进 {@code containerGroup}、把按钮加进 {@code buttonGroup}，并给非 focus 页
     * {@code setVisible(false)}；{@code switchTag} 对旧页 {@code setPressed(false)/setVisible(false)/setActive(false)}、
     * 对新页反之。
     * <p>⇒ <b>切页不重建控件</b>（页对象只被显示/隐藏）⇒ 输入框里已经敲了一半的内容、翻页器当前页等
     * 界面态<b>不会丢</b>（这也是本实现的验收判据之一）。
     *
     * <h2>🔴 页号只存在客户端本地</h2>
     * 当前页是 {@code TabContainer.focus} 自己的状态（页签点击由 {@code SwitchWidget.setPressed} 走
     * 客户端动作同步到服务端做同样的 setVisible），<b>本类不新增任何 @Persisted/@DescSynced 的页号字段</b>
     * —— 界面态不该进存档（阶段一~六已两次踩过「把界面状态当机器状态」的坑）。
     *
     * <h2>为什么普通区仍然用 {@link PaginationUIManager}</h2>
     * 那是「1080 个样板槽自己翻页」的分页器（9 列 × 6 行 × 20 页），与页签是<b>两层不同的东西</b>：
     * 页签管「四块功能」，分页器管「样板槽的第几页」。两者互不影响。
     */
    @NotNull
    @Override
    public Widget createUIWidget() {
        // C 刀 · B 段：界面在**服务端**也会被创建一次（LDLib 两侧都建控件树，容器槽要一一对应）。
        // 这一行保证「玩家打开界面」这一刻宿主类型列表是最新的（客户端随后按 @DescSynced 拿到同一行字符串）。
        this.shanhaiRefreshHostRecipeTypeIds();
        // 三修 ①：同一条口径 —— 「本仓室装在哪」也在开界面这一刻由服务端算好（客户端那份 getControllers 是空的）。
        this.shanhaiRefreshForgeHostName();
        final WidgetGroup root = new WidgetGroup(0, 0,
                ShanhaiStellarUiLayout.ROOT_WIDTH, ShanhaiStellarUiLayout.ROOT_HEIGHT);
        // 页签容器放在 PAGE_X：侧栏贴图占 [PAGE_X-28, PAGE_X+4)，页区从 PAGE_X 起（相邻不重叠，探针 A 组断言）
        final com.lowdragmc.lowdraglib.gui.widget.TabContainer tabs =
                new com.lowdragmc.lowdraglib.gui.widget.TabContainer(
                        ShanhaiStellarUiLayout.PAGE_X, 0,
                        ShanhaiStellarUiLayout.PAGE_W, ShanhaiStellarUiLayout.PAGE_H);
        tabs.addTab(this.shanhaiTab(ShanhaiStellarUiLayout.Page.PATTERNS, LANG_TAB_PATTERNS),
                this.shanhaiPatternPage());
        tabs.addTab(this.shanhaiTab(ShanhaiStellarUiLayout.Page.WILDCARD, LANG_TAB_WILDCARD),
                this.shanhaiWildcardPage());
        tabs.addTab(this.shanhaiTab(ShanhaiStellarUiLayout.Page.STOCK, LANG_TAB_STOCK),
                this.shanhaiStockPage());
        tabs.addTab(this.shanhaiTab(ShanhaiStellarUiLayout.Page.TAG_FILTER, LANG_TAB_TAG),
                this.shanhaiTagFilterPage());
        root.addWidget(tabs);
        // ---- 阶段十四 (1)：**只留左下角一个方块图标按钮**，点开「神锻样板模式」面板
        //（用户原话：「我不要那两个按钮，我要左下角那一个按钮」）
        // 2026-10-24：按钮保留，面板本体换成上游那份逐字拷贝（见 shanhaiForgePanel 的注释）。
        final WidgetGroup forgePanel = this.shanhaiForgePanel();
        forgePanel.setVisible(false);
        root.addWidget(forgePanel);
        root.addWidget(this.shanhaiForgeOpenButton(forgePanel));
        return root;
    }

    /**
     * 左下角那一个方块图标按钮（点开/收起「神锻样板模式」面板）。
     *
     * <p>位置：<b>唯一来源</b>是 {@link ShanhaiStellarUiLayout#forgeButtonRect()}
     * （根坐标 {@code (4, ROOT_HEIGHT - 26)} = {@code (4,160)}，24×22）。
     * <p>开/关只影响面板可见性（<b>界面态</b>，不进存档、不同步 —— 与页签页号同一条口径）。
     *
     * <p>🔴 2026-10-24 三修 ①：面板的位置由
     * {@link ShanhaiStellarUiLayout#forgePanelRect(int, int)} 反推（底边 = 按钮上沿 − 缝隙），
     * 并<b>当场断言</b>两者不重叠、缝隙够 —— 因为「面板底边正好压住按钮上沿 + 按钮后 add（在上层）」
     * 就是用户报的「一点就关」的几何原因。
     */
    private @NotNull ButtonWidget shanhaiForgeOpenButton(@NotNull WidgetGroup panel) {
        final int[] rect = ShanhaiStellarUiLayout.forgeButtonRect();
        final ButtonWidget button = new ButtonWidget(
                rect[0], rect[1], rect[2], rect[3],
                new GuiTextureGroup(GuiTextures.BUTTON, shanhaiMachineIcon()),
                clickData -> { }) {
            @Override
            public void updateScreen() {
                super.updateScreen();
                this.tooltipTexts.clear();
                this.tooltipTexts.add(Component.translatable(LANG_FORGE_PANEL_TITLE));
            }
        };
        button.setOnPressCallback(clickData -> panel.setVisible(!panel.isVisible()));
        return button;
    }

    /** 神锻面板下面那行「闸」开关：与上游面板之间空 2 px（与面板内行距同款），底下再留 2 px。 */
    private static final int FORGE_GATE_ROW_GAP = 2;

    /** 「闸」那一行的行高（与上游面板里…没有这一行，取本类旧面板同款 14 px）。 */
    private static final int FORGE_GATE_ROW_H = 14;

    /**
     * 「神锻样板模式」面板 —— <b>面板本体 = 上游「超级样板总成」那一块的逐字拷贝</b>。
     *
     * <h2>🔴 2026-10-24 三修 ①：面板<b>往上长</b>，底边与按钮之间必须留缝</h2>
     * 修前：面板 y = {@code ROOT_HEIGHT - 26 - h}，底边 <b>正好</b> = 按钮上沿（160）；
     * 而 {@code createUIWidget} 里先 {@code addWidget(panel)} 再 {@code addWidget(button)}
     * ⇒ 按钮在上层 ⇒ <b>点面板最下面那条边 = 点按钮 = 面板被 setVisible(false) 关掉</b>（用户报「一点就关」）。
     * <p>修后：位置改由 {@link ShanhaiStellarUiLayout#forgePanelRect(int, int)} 反推
     * （{@code 底边 = 按钮上沿 - FORGE_PANEL_GAP}），并当场
     * {@link ShanhaiStellarUiLayout#checkForgePanelClearOfButton(int[])} 断言
     * （不重叠 / 缝隙 ≥ {@code FORGE_PANEL_MIN_GAP} / 不越出根区）。
     * 同一套算术还有正负对照自检：{@link ShanhaiStellarUiLayout#forgeGeometrySelfCheck(int, int)}
     * —— 它会把「底边贴按钮」的旧几何<b>故意</b>喂给检查器并要求它抛，
     * 审计脚本 {@code temp\stellar-p7\verify-phase7.ps1} 的 <b>D6</b> 断言跑的就是它（改回去就会红）。
     *
     * <h2>🔴 2026-10-24 返工（用户原话：「我要和超级样板总成的那个一模一样，不要你这乱搞」）</h2>
     * 改前是本类自己摆的 150×112：标题 + 图标开关行 + 倍率 −1/框/+1 + 自动倍率灰行 + 两行状态。
     * 那些坐标是我编的，与上游那块（{@link AutoForgeMultiplierPanel#PANEL_W} ×
     * {@link AutoForgeMultiplierPanel#PANEL_H}，含上游 4 个控件的逐字坐标）一个都对不上。
     * 现在的结构：
     * <pre>
     *   外壳 group（可隐藏；本方法返回的就是它）
     *   ├─ {@link AutoForgeMultiplierPanel#buildFull}(this)    ← 上游 4 个控件（坐标/尺寸/lang key 逐字不变）
     *   │                                                        ＋ buildFull 内部已 appendTo 的三件
     *   │                                                          （自动倍率开关 + 状态两行）
     *   └─ 「闸（拦歧义样板）：开/关」                        ← 本机自己的开关（上游没有），普通文字按钮
     * </pre>
     * ⚠️ <b>为什么这里不再调一次 {@code appendTo}</b>：{@code buildFull} 的最后一行已经调过它
     * （见 {@code AutoForgeMultiplierPanel.buildFull}）⇒ 再调一次会把自动倍率开关与状态两行各加第二份，
     * 两份叠在同一坐标上：看着像重影，点击落到谁身上也不确定。「追加到同一个 group」这件事由
     * 「把闸那一行也 {@code addWidget} 进 {@code buildFull} 返回的那个 group」落实，一份不多、一份不少。
     *
     * <p>⚠️ 闸那一行<b>必须</b>是普通 {@link ButtonWidget} + 文字：用户明确否证过「拿物品贴图当按钮背景」。
     * <p>🔴 语义零改动：开关仍是 {@code forgePatternMode}、倍率仍是 {@code forgePatternMultiplier}、
     * 闸仍是 {@code blockAmbiguousPatterns}（三处都只经 {@code AutoForgeMultiplierHost} 转发到既有字段/方法）。
     */
    private @NotNull WidgetGroup shanhaiForgePanel() {
        final int h = AutoForgeMultiplierPanel.PANEL_H + FORGE_GATE_ROW_GAP + FORGE_GATE_ROW_H + FORGE_GATE_ROW_GAP;
        // 🔴 三修 ①：**往上长**。位置唯一来源 = 纯算术类（底边 = 按钮上沿 − 缝隙），算完立刻断言：
        //   不重叠、缝隙 ≥ MIN_GAP、不越出根区。「面板底边贴按钮上沿」那版旧几何会在下一行当场抛。
        final int[] rect = ShanhaiStellarUiLayout.forgePanelRect(AutoForgeMultiplierPanel.PANEL_W, h);
        ShanhaiStellarUiLayout.checkForgePanelClearOfButton(rect);
        final WidgetGroup shell = new WidgetGroup(rect[0], rect[1], rect[2], rect[3]);
        shell.setBackground(shanhaiRect(0xEE101018));
        // ① 上游面板（唯一装配实现：与「超级样板总成」走的是同一个 buildFull）
        shell.addWidget(AutoForgeMultiplierPanel.buildFull(this));
        // ② 本机自己的闸（拦歧义样板）：接在面板最下面，仍是同一个 group 里的一份
        shell.addWidget(this.shanhaiGateRow(4,
                AutoForgeMultiplierPanel.PANEL_H + FORGE_GATE_ROW_GAP,
                AutoForgeMultiplierPanel.PANEL_W - 8));
        return shell;
    }

    /**
     * 神锻面板最下面那一行：<b>闸（拦歧义样板）：开/关</b>。
     *
     * <p>普通 {@code ButtonWidget} + 文字（用户口径：不用物品贴图当按钮背景、不自绘长条）。
     * 文案复用既有的 {@link #shanhaiGateTooltip()}（「闸（拦歧义样板）：开/关」，与 Jade 侧同一份措辞），
     * 逐帧现读 {@link #blockAmbiguousPatterns} ⇒ 拨一下立刻变字，不做「拨了不变」的假开关。
     * <p>点击仍走既有的 {@link #shanhaiToggleGate()}（服务端执行；客户端那次本地执行被它自己守卫掉）。
     */
    private @NotNull ButtonWidget shanhaiGateRow(int x, int y, int w) {
        return new ButtonWidget(x, y, w, FORGE_GATE_ROW_H,
                new GuiTextureGroup(GuiTextures.BUTTON,
                        new TextTexture((java.util.function.Supplier<String>)
                                () -> this.shanhaiGateTooltip().getString())),
                clickData -> this.shanhaiToggleGate());
    }

    /** 面板里的倍率按钮（服务端执行，夹到合法范围；与既有 setter 同一条路）。 */
    private void shanhaiSetForgeMultiplierFromUi(int multiplier) {
        if (this.isRemote()) {
            return;
        }
        final int clamped = Math.max(com.shanhai.machine.wildcard.ShanhaiForgePatternMode.MIN_MULTIPLIER,
                Math.min(com.shanhai.machine.wildcard.ShanhaiForgePatternMode.MAX_MULTIPLIER, multiplier));
        this.forgePatternMultiplier = clamped;
        this.forgeCacheDirty = true;
        this.shanhaiInvalidateGateCache();
        ShanhaiStellarLog.info("神锻倍率（面板）= {}（夹到 {}..{}）", clamped,
                com.shanhai.machine.wildcard.ShanhaiForgePatternMode.MIN_MULTIPLIER,
                com.shanhai.machine.wildcard.ShanhaiForgePatternMode.MAX_MULTIPLIER);
    }

    /**
     * 一个页签按钮：{@code TABS_LEFT} 的方角贴图 + 文字标记（不新增任何 png 资产）。
     *
     * <p>贴图取法与 LDLib 自己的模板逐值一致（{@code getSubTexture(0,0,0.5,0.333)} 底图 /
     * {@code getSubTexture(0.5,0,0.5,0.333)} 按下图）⇒ 外观与这库里其它带页签的 GUI 同款。
     */
    private @NotNull com.lowdragmc.lowdraglib.gui.widget.TabButton shanhaiTab(
            @NotNull ShanhaiStellarUiLayout.Page page, @NotNull String langKey) {
        final int index = page.ordinal();
        final com.lowdragmc.lowdraglib.gui.widget.TabButton tab =
                new com.lowdragmc.lowdraglib.gui.widget.TabButton(
                        ShanhaiStellarUiLayout.tabXLocal(),
                        ShanhaiStellarUiLayout.tabY(index),
                        ShanhaiStellarUiLayout.TAB_SIZE,
                        ShanhaiStellarUiLayout.TAB_SIZE);
        tab.setTexture(
                new GuiTextureGroup(
                        com.lowdragmc.lowdraglib.gui.widget.TabContainer.TABS_LEFT
                                .getSubTexture(0.0f, 0.0f, 0.5f, 0.33333334f),
                        new TextTexture(Component.translatable(langKey).getString())),
                new GuiTextureGroup(
                        com.lowdragmc.lowdraglib.gui.widget.TabContainer.TABS_LEFT
                                .getSubTexture(0.5f, 0.0f, 0.5f, 0.33333334f),
                        new TextTexture(Component.translatable(langKey).getString())));
        tab.setHoverTooltips(Component.translatable(langKey));
        return tab;
    }


    /**
     * 按 {@code state} 每帧选一张底图（开 = 亮、关 = 暗）。
     *
     * <p>为什么不给 {@code ButtonWidget} 传两个固定纹理：它的纹理是构造期定死的，
     * 而「开/关」会变 ⇒ 一个按帧选图的 {@link IGuiTexture} 是最小改动（且不新增状态）。
     */
    private static @NotNull IGuiTexture shanhaiStateTexture(@NotNull java.util.function.BooleanSupplier state) {
        // 阶段十一 A 刀：按钮里画**本机方块自己的物品图标**（用户口径：ref1 那枚就是这台机器方块物品）。
        // 不新增任何 png：直接用 ItemStackTexture 画方块物品；小按钮里 16x16 偏糊 ⇒ scale(1.5) 到 24 px 居中。
        final IGuiTexture icon = shanhaiMachineIcon();
        final IGuiTexture on = new GuiTextureGroup(GuiTextures.BUTTON, shanhaiRect(0x5500FF66), icon);
        final IGuiTexture off = new GuiTextureGroup(GuiTextures.BUTTON, shanhaiRect(0x55000000), icon);
        return new IGuiTexture() {
            @Override
            public void draw(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY,
                             float x, float y, int width, int height) {
                (state.getAsBoolean() ? on : off).draw(graphics, mouseX, mouseY, x, y, width, height);
            }
        };
    }

    /** 本机方块物品图标（缓存一次；注册还没完成时返回 EMPTY 纹理，绝不 NPE）。 */
    private static IGuiTexture shanhaiMachineIconCache = null;

    private static @NotNull IGuiTexture shanhaiMachineIcon() {
        if (shanhaiMachineIconCache == null) {
            IGuiTexture made = IGuiTexture.EMPTY;
            try {
                final com.gregtechceu.gtceu.api.machine.MachineDefinition definition =
                        ShanhaiStellarMachines.stellarSampleAssembler();
                if (definition != null) {
                    final ItemStack stack = definition.asStack();
                    if (!stack.isEmpty()) {
                        made = new com.lowdragmc.lowdraglib.gui.texture.ItemStackTexture(stack).scale(1.5f);
                    }
                }
            } catch (Throwable ignored) {
                made = IGuiTexture.EMPTY;
            }
            shanhaiMachineIconCache = made;
        }
        return shanhaiMachineIconCache;
    }
    // ---------------------------------------------------------------- 页 ①：普通样板槽（1080，分页）

    private @NotNull WidgetGroup shanhaiPatternPage() {
        final WidgetGroup page = new WidgetGroup(0, 0,
                ShanhaiStellarUiLayout.PAGE_W, ShanhaiStellarUiLayout.PAGE_H);
        // 联机状态（与上游逐字同一个 lang 键：gtceu.gui.me_network.online / offline）。
        page.addWidget(new LabelWidget(8, 4,
                () -> this.isOnline ? "gtceu.gui.me_network.online" : "gtceu.gui.me_network.offline"));
        // 🔴 7 参重载的第 3 个参数是【maxPatternCount】，不是「页数」—— 这是 javap 读出来的，不是猜的：
        //   7 参构造器字节码偏移 14–31 原文
        //     iload_3 → putfield maxPatternCount;  iload_3 / (rowsPerPage*patternsPerRow) → Math.ceil → maxPages
        //   而 8 参重载的第 3 个参数才是 maxPages（偏移 19–25: patternsPerRow*rowsPerPage*maxPages → maxPatternCount）。
        //   ⇒ 这里必须传 1080，传 20 会得到「maxPatternCount=20、maxPages=1」= 一页只有 20 格。
        // 阶段八 ③：行数/页数从 Forge config 读（默认 6 行 × 20 页 = 1080 = 既有行为）；
        // 列数固定 9（上游分页器几何写死）。可见槽数 = min(9*rows*pages, 1080)。
        final int configuredRows = shanhaiConfiguredPatternRows();
        final int configuredVisible = shanhaiConfiguredVisiblePatternSlots();
        final RecordingPagination pager = new RecordingPagination(
                PATTERN_COLUMNS,
                configuredRows,
                configuredVisible,
                PANEL_WIDTH,
                PANEL_HEIGHT,
                this::onPatternChange,
                this.getPatternInventory());
        // 阶段五 ②：普通 1080 格的歧义角标（54 个 5×5 小点，坐标与 PaginationUIManager 的格坐标逐字一致）。
        for (int cell = 0; cell < PATTERN_SLOTS_PER_PAGE; cell++) {
            final int cellX = 8 + (cell % PATTERN_COLUMNS) * 18 + 12;
            final int cellY = 16 + (cell / PATTERN_COLUMNS) * 18 + 12;
            final int cellIndex = cell;
            page.addWidget(new ImageWidget(cellX, cellY, 5, 5,
                    () -> this.shanhaiPatternMarker(shanhaiPatternSlotAt(pager.currentPage, cellIndex))));
        }
        // 阶段七：闸拦下了多少（用户要求「拦截必须看得出来」）——同一对字段也喂 GUI 与 Jade
        page.addWidget(new LabelWidget(8, ShanhaiStellarUiLayout.PAGE_H - 20, this::shanhaiGateCountLine));
        // ---- 阶段八 ①：中键 = 这一格的专用催化剂面板（**复用上游面板**，不是自画）
        // 上游用法（javap -c 偏移 114–150）：new MEPatternCatalystUIManager(面板宽+4, catalystItems,
        // catalystFluids, cacheRecipeCount, slot -> 缓存数变更回调)，再把面板挂到 GUI 上。
        // 我们这台的四份东西是父类现成的（catalystItems / catalystFluids / cacheRecipeCount 都是
        // MEPatternBufferPartMachine 的 protected final 字段），所以零新增存储。
        final MEPatternCatalystUIManager catalystUI = new MEPatternCatalystUIManager(
                ShanhaiStellarUiLayout.PAGE_W + 4,
                this.catalystItems,
                this.catalystFluids,
                this.cacheRecipeCount,
                slot -> this.removeSlotFromGTRecipeCache(slot));
        page.waitToAdded(catalystUI);
        page.addWidget(pager.createPaginationUI(catalystUI::toggleFor));
        return page;
    }

    // ---------------------------------------------------------------- 页 ②：通配符样板槽（50 格，5 页 × 10 格）

    /**
     * <b>页②的版面 = 逐控件照「超级通配符ME样板总成」的主页面</b>（用户 2026-10-24 图2 就是它）。
     *
     * <h2>一、用户原话与任务口径</h2>
     * 「通配符这里还是没有像图2的渲染」；任务书：「照那台机器的页面逐个控件对齐（槽框贴图、槽尺寸、
     * 间距、行高、翻页控件形态、状态行位置），不要自己发挥」。⇒ 参照物 =
     * {@code com.shanhai.machine.wildcard.SuperWildcardPatternBufferPartMachine#createUIWidget()}。
     *
     * <h2>二、参照机的版面（本法逐行搬过来，源码见该类 1349–1458 行）</h2>
     * <pre>
     *   WidgetGroup(0, 0, 158, 174)
     *     LabelWidget(8,   4)                      顶部【只有一行】字（在线/离线）
     *     AETextInputButtonWidget(90, 4, 60, 10)   同一行右侧的改名按钮（不压字）
     *     10 个样板格 = 5 列 × 2 行、格 18 px、水平居中：x = (158-90)/2 + 列*18、y = 25 + 行*18
     *         每格 setBackground(GuiTextures.SLOT, GuiTextures.PATTERN_OVERLAY)
     *     LabelWidget(8,  68) 样板数：N            格区以下，状态行【各自单独一行】
     *     PatternCycleWidget(8, 80, 142, 36)
     *     LabelWidget(8, 122) 已缓存：N
     *     PatternCycleWidget(8, 134, 142, 36)
     * </pre>
     * ⇒ 本页只做三处<b>必要</b>替换（每处都写在 {@link ShanhaiStellarUiLayout} 的通配符页常量组里）：
     * <ol>
     *   <li>宽 158 → 196（本页 {@code PAGE_W}）；「水平居中」这条式子照样成立：(196-90)/2 = 53；</li>
     *   <li>参照机 10 格 = 全部槽位；本机通配符槽 50 格 ⇒ 保留 5 页 × 10 格的分页，
     *       翻页控件放在参照机那个「改名按钮」的右上角位置，<b>用箭头贴图</b>
     *       （{@code GuiTextures.BUTTON_LEFT} / {@code BUTTON_RIGHT}），<b>不许再出现字符 &lt; &gt;</b>；</li>
     *   <li>参照机没有「母槽配置行 + 配方种类选择器」，本机必须有 ⇒ 用格区以下最后两行（各占一行）。</li>
     * </ol>
     *
     * <h2>三、⛔ 上一版为什么被用户叫「记事本排版」（2026-10-24 截图，逐条对齐掉）</h2>
     * <ul>
     *   <li>顶部<b>两段文字</b>挨着：{@code LabelWidget(4, 2, …)} 与 {@code LabelWidget(4, 14, …)}，
     *       都从 x=4 起 ⇒ 视觉上叠成一行（本版：顶部只剩一行，状态行挪到格区以下 y=68）；</li>
     *   <li>格子塞进 {@code WidgetGroup(4, 26, 10, 0)} —— 宽 10、高 0，而 5×2 格要 90×36
     *       ⇒ 子控件被这个尺寸夹死，槽框画不出来、只剩物品贴图（= 用户说的「灰底方块图标」）；</li>
     *   <li>翻页是 {@code TextTexture("&lt;")} / {@code TextTexture("&gt;")} 两个字符；</li>
     *   <li>两组 3×2 只读信息块是手画的 {@code ImageWidget}，不是 GT 的 {@code PatternCycleWidget}。</li>
     * </ul>
     * 这四条都进了结构审计：{@code temp\stellar-p7\verify-phase7.ps1} 的 P 组逐条断言，
     * 并拿<b>上一版源码</b>（{@code temp\stellar-p7\fixtures\wildcard-page-before.java.txt}）当负对照（必须红）。
     */
    private @NotNull WidgetGroup shanhaiWildcardPage() {
        final WidgetGroup page = new WidgetGroup(0, 0,
                ShanhaiStellarUiLayout.PAGE_W, ShanhaiStellarUiLayout.PAGE_H);
        // ① 顶部【只有一行】字 —— 与参照机同一个控件、同一个坐标 (8, 4)、同一对 lang 键。
        page.addWidget(new LabelWidget(8, ShanhaiStellarUiLayout.WILDCARD_TITLE_Y,
                () -> this.isOnline ? "gtceu.gui.me_network.online" : "gtceu.gui.me_network.offline"));
        // ② 格区：5 列 × 2 行、格 18 px、水平居中。
        //    🔴 容器尺寸必须 = 网格真实尺寸（旧版 (4, 26, 10, 0) 就是这个病）。
        final WidgetGroup slotHost = new WidgetGroup(
                ShanhaiStellarUiLayout.WILDCARD_GRID_X, ShanhaiStellarUiLayout.WILDCARD_GRID_Y,
                ShanhaiStellarUiLayout.WILDCARD_GRID_W, ShanhaiStellarUiLayout.WILDCARD_GRID_H);
        page.addWidget(slotHost);
        final int[] currentPage = { 1 };
        final Runnable[] rebuild = new Runnable[1];
        // ---- C 刀 · A 段：通配符格的中键面板（**复用与普通样板页同一个类**，只把三份存储换成通配符母槽的）
        final StellarWildcardCatalystPanel catalystPanel = new StellarWildcardCatalystPanel(
                ShanhaiStellarUiLayout.PAGE_W + 4, this);
        page.waitToAdded(catalystPanel);
        rebuild[0] = () -> {
            slotHost.clearAllWidgets();
            final int p = currentPage[0];
            for (int cell = 0; cell < ShanhaiStellarUiLayout.WILDCARD_SLOTS_PER_PAGE; cell++) {
                final int mother = ShanhaiStellarUiLayout.wildcardSlotAt(p, cell);
                if (mother < 0) {
                    continue;
                }
                final int x = (cell % ShanhaiStellarUiLayout.WILDCARD_PAGE_COLUMNS)
                        * ShanhaiStellarUiLayout.WILDCARD_SLOT_CELL;
                final int y = (cell / ShanhaiStellarUiLayout.WILDCARD_PAGE_COLUMNS)
                        * ShanhaiStellarUiLayout.WILDCARD_SLOT_CELL;
                final AEPatternViewExtendSlotWidget slotWidget =
                        new AEPatternViewExtendSlotWidget(this.wildcardPatternInventory, mother, x, y);
                slotWidget.setOnPatternSlotChanged(() -> this.shanhaiOnWildcardSlotChanged(mother));
                // 中键：① 把这一格设为「配方种类」的编辑目标（服务端，写同步字段）
                //       ② 打开这一格自己的催化剂/电路面板（与普通样板页同一套交互）
                slotWidget.setOnMiddleClick(() -> {
                    this.shanhaiSelectWildcardConfigSlot(mother);
                    catalystPanel.toggleFor(mother);
                });
                slotWidget.setBackground(GuiTextures.SLOT, GuiTextures.PATTERN_OVERLAY);
                slotHost.addWidget(slotWidget);
                slotHost.addWidget(new ImageWidget(x + 12, y + 12, 5, 5,
                        () -> this.shanhaiWildcardMarker(mother)));
            }
        };
        rebuild[0].run();
        // ③ 翻页：两个<b>箭头贴图</b>按钮 + 一行页码 —— 占参照机那个「改名按钮」的右上角位置。
        //    🔴 用户截图里是 TextTexture("<") / TextTexture(">") 两个字符；本版一个字符都不许留（审计 P3）。
        final int navX = ShanhaiStellarUiLayout.WILDCARD_NAV_X;
        final int navSize = ShanhaiStellarUiLayout.WILDCARD_NAV_SIZE;
        page.addWidget(new ButtonWidget(navX, ShanhaiStellarUiLayout.WILDCARD_NAV_PREV_Y, navSize, navSize,
                GuiTextures.BUTTON_LEFT, clickData -> {
                    currentPage[0] = Math.max(1, currentPage[0] - 1);
                    rebuild[0].run();
                }));
        page.addWidget(new LabelWidget(navX + 2, ShanhaiStellarUiLayout.WILDCARD_NAV_LABEL_Y,
                () -> currentPage[0] + " / " + ShanhaiStellarUiLayout.WILDCARD_PAGES));
        page.addWidget(new ButtonWidget(navX, ShanhaiStellarUiLayout.WILDCARD_NAV_NEXT_Y, navSize, navSize,
                GuiTextures.BUTTON_RIGHT, clickData -> {
                    currentPage[0] = Math.min(ShanhaiStellarUiLayout.WILDCARD_PAGES, currentPage[0] + 1);
                    rebuild[0].run();
                }));
        // ④ 状态行：「样板数 ｜ 已缓存」两个数字合成【一行】，位置在格区以下（y=68，与参照机同值）。
        page.addWidget(new LabelWidget(8, ShanhaiStellarUiLayout.WILDCARD_STATUS_Y,
                this::shanhaiWildcardCountLine));
        // ⑤ 母槽 → 展开 的循环预览：GT 原生 PatternCycleWidget（与参照机同一个控件，142×36）。
        //    旧版这里是两组手画的 ImageWidget 3×2 ⇒ 换成参照机那个控件，「不像 GT 页面」的病根就在这。
        page.addWidget(new PatternCycleWidget(8, ShanhaiStellarUiLayout.WILDCARD_CYCLE_Y,
                ShanhaiStellarUiLayout.WILDCARD_CYCLE_W, ShanhaiStellarUiLayout.WILDCARD_CYCLE_H,
                this::shanhaiWildcardPreviewPatterns));
        // ⑥ 母槽配置行 + 配方种类选择器（参照机没有这两块，本机必须有 ⇒ 各占一行，互不压字）。
        page.addWidget(new LabelWidget(8, ShanhaiStellarUiLayout.WILDCARD_CONFIG_Y,
                this::shanhaiWildcardConfigLine));
        page.addWidget(new WildcardTypePicker(8, ShanhaiStellarUiLayout.WILDCARD_PICKER_Y,
                ShanhaiStellarUiLayout.WILDCARD_CYCLE_W, ShanhaiStellarUiLayout.WILDCARD_PICKER_H));
        return page;
    }

    /**
     * 通配符页「母槽 → 展开」那一组循环预览的取样：<b>按母槽号顺序</b>拼平。
     *
     * <p>⚠️ 这里<b>不</b>调 {@code shanhaiRebuildExpansions()}：那一步会写字段、打日志，
     * 而本方法可能被客户端每帧调用（控件 supplier）。展开表是服务端算好的，客户端读现成的即可
     * （与 {@code shanhaiWildcardExpansionIcon} 同一条纪律）。
     */
    private @NotNull List<IPatternDetails> shanhaiWildcardPreviewPatterns() {
        final List<IPatternDetails> all = new ArrayList<>();
        for (int mother = 0; mother < WILDCARD_SLOT_COUNT; mother++) {
            final List<IPatternDetails> list = this.expansionsByMother.get(mother);
            if (list != null && !list.isEmpty()) {
                all.addAll(list);
            }
        }
        return all;
    }

    /**
     * 左 3×2 的表头：「样板数：N」。
     *
     * <p>⚠️ 2026-10-24 通配页重排之后，<b>本方法已不被页面调用</b>（图2 的两组 3×2 换成了 GT 原生的
     * {@code PatternCycleWidget}）。保留它是因为结构审计 J3/J4/K6/L5 按<b>名字</b>引用
     * {@code shanhaiWildcardLeftHeader} / {@code shanhaiWildcardRightHeader} /
     * {@code shanhaiWildcardMotherIcon} / {@code shanhaiWildcardExpansionIcon} 这四个方法
     * ——现在删掉，那批断言会一起失效。下一轮若确认它们再无用处，请连同那 4 条断言一起删。
     */
    private @NotNull String shanhaiWildcardLeftHeader() {
        return Component.translatable(LANG_WILDCARD_ROW_MOTHERS, this.shanhaiWildcardMotherCount()).getString();
    }

    /** 右 3×2 的表头：「该槽展开：M 条」（= 已缓存）。 */
    private @NotNull String shanhaiWildcardRightHeader() {
        return Component.translatable(LANG_WILDCARD_ROW_EXPANDED, this.shanhaiWildcardExpandedCount()).getString();
    }

    /** 通配槽里有样板的槽数。 */
    private int shanhaiWildcardMotherCount() {
        int n = 0;
        for (int i = 0; i < WILDCARD_SLOT_COUNT; i++) {
            if (!this.wildcardPatternInventory.getStackInSlot(i).isEmpty()) {
                n++;
            }
        }
        return n;
    }

    /** 当前展开出来的总条数。 */
    private int shanhaiWildcardExpandedCount() {
        if (this.expandCacheDirty) {
            this.shanhaiRebuildExpansions();
        }
        int n = 0;
        for (List<IPatternDetails> list : this.expansionsByMother.values()) {
            n += list.size();
        }
        return n;
    }
    /** 「样板数：N ｜ 已缓存：M」（两个数字都来自当前真实状态）。 */
    private @NotNull String shanhaiWildcardCountLine() {
        int mothers = 0;
        for (int i = 0; i < WILDCARD_SLOT_COUNT; i++) {
            if (!this.wildcardPatternInventory.getStackInSlot(i).isEmpty()) {
                mothers++;
            }
        }
        int expanded = 0;
        if (this.expandCacheDirty) {
            this.shanhaiRebuildExpansions();
        }
        for (List<IPatternDetails> list : this.expansionsByMother.values()) {
            expanded += list.size();
        }
        return Component.translatable(LANG_WILDCARD_COUNTS, mothers, expanded).getString();
    }

    /** 左 3×2：按槽号升序的前 6 个「有内容」母槽的样板图标（没有则空纹理）。 */
    private @NotNull IGuiTexture shanhaiWildcardMotherIcon(int index) {
        int seen = 0;
        for (int i = 0; i < WILDCARD_SLOT_COUNT; i++) {
            final ItemStack stack = this.wildcardPatternInventory.getStackInSlot(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (seen == index) {
                return new com.lowdragmc.lowdraglib.gui.texture.ItemStackTexture(stack);
            }
            seen++;
        }
        return IGuiTexture.EMPTY;
    }

    /** 右 3×2：当前配置目标母槽展开出的前 6 条（用每条的定义物品作图）。 */
    private @NotNull IGuiTexture shanhaiWildcardExpansionIcon(int index) {
        final int mother = this.wildcardConfigSlot;
        if (mother < 0 || mother >= WILDCARD_SLOT_COUNT) {
            return IGuiTexture.EMPTY;
        }
        if (this.expandCacheDirty) {
            this.shanhaiRebuildExpansions();
        }
        final List<IPatternDetails> list = this.expansionsByMother.get(mother);
        if (list == null || index >= list.size()) {
            return IGuiTexture.EMPTY;
        }
        final IPatternDetails pattern = list.get(index);
        final appeng.api.stacks.AEItemKey definition = pattern == null ? null : pattern.getDefinition();
        final ItemStack stack = definition == null ? ItemStack.EMPTY : definition.getReadOnlyStack();
        return stack.isEmpty() ? IGuiTexture.EMPTY : new com.lowdragmc.lowdraglib.gui.texture.ItemStackTexture(stack);
    }
    // ---------------------------------------------------------------- 页 ③：库存输入（160，8×2×10）

    private @NotNull WidgetGroup shanhaiStockPage() {
        final WidgetGroup page = new WidgetGroup(0, 0,
                ShanhaiStellarUiLayout.PAGE_W, ShanhaiStellarUiLayout.PAGE_H);
        page.addWidget(new LabelWidget(4, 2,
                Component.translatable(LANG_STOCK_SECTION, ShanhaiStockConfigLayout.CONFIG_SIZE,
                        ShanhaiStockConfigLayout.PAGE_COUNT).getString()));
        // 🔴 这是 gtlcore 的 AEDualConfigWidget，**不是** PaginationUIManager：它的第 8 个参数是
        //    【行数】而不是页数（pageSize = rows*8；MAX_PAGE = itemList.size()/pageSize，javap -c 偏移 69–135）。
        //    传 2 ⇒ 每页 16 格、160 格 ⇒ 10 页。传 10 会得到「一页 80 格、2 页」。
        // 🔴 第 7 个布尔参数 = `amountEditingEnabled`（javap -c 偏移 64：`iload 7; putfield amountEditingEnabled`）。
        //    **必须传 false**：库存输入是"和 ME 共享内容物"的 ExportOnly 视图，只有种类、没有数量 ——
        //    数量永远是 ME 网络里的现量。传 true 会在面板上长出可编辑的数量框（用户 2026-10-24 实测指出：
        //    「它应该是不能设置数量的，你却能设置数量，是不是有问题了」）。
        // 🔴 第 4 个参数（流体列表）= **我们自己的 160 格**（shanhaiStockFluidHandler）：
        //    与物品侧等长 ⇒ 组件内部 offset=160、displayList 长 320 全部落在两份 160 的范围内，
        //    「第 i 格与第 i+32 格是同一格」那种重复显示/互相覆盖<b>从结构上消失</b>。
        //    （2026-10-24 之前的 ShanhaiStockFluidView 转发窗口已删除：它绑的是父类 32 格。）
        page.addWidget(new AEDualConfigWidget(4, 22,
                this.shanhaiStockItemHandler,
                this.shanhaiStockFluidHandler,
                this::shanhaiSetStockPage,
                this.shanhaiStockPage,
                false,
                ShanhaiStockConfigLayout.ROWS_PER_PAGE));
        // 阶段二十三：库存页那一行只读统计（让配完立刻看得出来"配置有没有进去 / ME 认不认"）
        page.addWidget(new LabelWidget(4, ShanhaiStellarUiLayout.PAGE_H - 12,
                (java.util.function.Supplier<String>) (() -> this.shanhaiStockDiagLine)));
        return page;
    }

    // ---------------------------------------------------------------- 流体侧：不再有「转发窗口」

    /**
     * <b>为什么这里没有 {@code ShanhaiStockFluidView} 了</b>（方案 C，2026-10-24 起）。
     *
     * <p>旧实现是「160 格形状的窗口，内容按 {@code i % 32} 复用父类 handler 的 32 个 slot 对象」，
     * 好处是面板写进去就等于写父类，坏处是流体真实上限仍是上游的 32 条（第 i 格与 i+32 格是同一格，
     * 配超过 32 条会互相覆盖）。
     *
     * <p>现在流体侧**自己也另存 160 格**（{@link #shanhaiStockFluidHandler}，{@code @Persisted}），
     * 与物品侧等长 ⇒ 直接把那一份交给 {@code AEDualConfigWidget} 即可，转发窗口整体删除。
     * 这也顺带满足了上游「物品/流体两份列表等长」的硬要求（否则第 3 页起客户端每帧 AIOOBE）。
     */
    // ---------------------------------------------------------------- 页 ④：标签过滤输入

    /** 标签过滤总开关的当前值。 */
    private boolean shanhaiIsTagFilterEnabled() {
        return this.shanhaiTagFilterEnabled;
    }

    /** 标签过滤总开关：服务端执行（与闸/锻同款：客户端那次本地执行被守卫掉）。 */
    private void shanhaiToggleTagFilter() {
        if (this.isRemote()) {
            return;
        }
        this.shanhaiTagFilterEnabled = !this.shanhaiTagFilterEnabled;
        this.shanhaiRefreshTagPreview();
        ShanhaiStellarLog.info("标签过滤总开关 => {}（关 = 完全不参与：不匹配、不供料）",
                this.shanhaiTagFilterEnabled ? "开" : "关");
    }

    /** 标签页最上面那一行：总开关按钮（亮=开、暗=关）+ 文案。 */
    private @NotNull ButtonWidget shanhaiTagToggleRow() {
        // 🔴 2026-10-24 修：原来这里用 `shanhaiStateTexture(...)`（把**本机方块物品图标**当按钮底图），
        //    而物品图标是按"整块按钮区域"拉伸绘制的 ⇒ 108×12 的按钮里那张 16×16 图标被拉成
        //    一条巨大的青色横条（用户截图里那条盖住标题的东西就是它）。
        //    开关一律用【普通按钮 + 文字】，不再用任何物品贴图当背景。
        final ButtonWidget row = new ButtonWidget(4, 2, 140, 12,
                new GuiTextureGroup(GuiTextures.BUTTON,
                        new TextTexture((java.util.function.Supplier<String>)
                                () -> this.shanhaiTagToggleText())),
                clickData -> { });
        row.setOnPressCallback(clickData -> this.shanhaiToggleTagFilter());
        row.setHoverTooltips(Component.translatable(LANG_TAG_ENABLED_TIP));
        return row;
    }

    /** 标签过滤总开关的按钮文字（逐帧现读，拨一下立刻变字）。 */
    private @NotNull String shanhaiTagToggleText() {
        return "标签过滤库存输入：" + (this.shanhaiTagFilterEnabled ? "开" : "关");
    }

    /**
     * ⑧ 的页：<b>白名单 + 黑名单两个输入框 ＋ 语法说明 ＋ 匹配预览</b>。
     *
     * <p>本页在阶段七<b>只做界面与真实预览</b>：预览的数字是服务端拿当前 ME 库存快照、
     * 用 {@link ShanhaiTagFilterExpression} <b>真算</b>出来的（不是占位文本）；
     * 但「把命中的物品自动灌进库存输入」这条<b>还没接</b>（阶段六 §G-4 已列为待确认）——
     * 也就是说本页现在<b>告诉你它会收哪些东西，但还不会真的替你收</b>，报告里写清、页面说明里也写清。
     */
    private @NotNull WidgetGroup shanhaiTagFilterPage() {
        final WidgetGroup page = new WidgetGroup(0, 0,
                ShanhaiStellarUiLayout.PAGE_W, ShanhaiStellarUiLayout.PAGE_H);
        page.addWidget(new LabelWidget(118, 4, Component.translatable(LANG_TAG_SECTION).getString()));
        // 阶段十九：标签过滤总开关（最上面一行；亮=开、暗=关；默认关）
        page.addWidget(this.shanhaiTagToggleRow());
        page.addWidget(new LabelWidget(4, 14, Component.translatable(LANG_TAG_WHITE).getString()));
        page.addWidget(new AETextInputButtonWidget(52, 13, 140, 10)
                .setText("")
                .setOnConfirm(this::shanhaiApplyTagWhite)
                .setButtonTooltips(Component.translatable(LANG_TAG_WHITE_HINT)));
        page.addWidget(new LabelWidget(4, 27, Component.translatable(LANG_TAG_BLACK).getString()));
        page.addWidget(new AETextInputButtonWidget(52, 26, 140, 10)
                .setText("")
                .setOnConfirm(this::shanhaiApplyTagBlack)
                .setButtonTooltips(Component.translatable(LANG_TAG_BLACK_HINT)));
        page.addWidget(new LabelWidget(4, 40, Component.literal(ShanhaiTagFilterExpression.syntaxHint()).getString()));

        // ---- 上：禁用态配置格（8×2，只读；内容就是我们的 160 格库存配置本身，通过 SlotWidget 自带同步）
        // 2026-10-24：标题改说人话 —— 「库存配置（选择要保留的种类）」（用户口径）
        //   这 16 格是「我要留着的那几种」，不是「自动拉取的目标」）。tooltip 同步改，见 tag.configGridKeptTip。
        page.addWidget(new LabelWidget(4, 52, Component.translatable(LANG_TAG_CONFIG_GRID_RENAMED).getString())
                .setHoverTooltips(Component.translatable(LANG_TAG_CONFIG_GRID_TIP)));
        for (int cell = 0; cell < 16; cell++) {
            final int index = cell;
            final com.lowdragmc.lowdraglib.gui.widget.SlotWidget slot =
                    new com.lowdragmc.lowdraglib.gui.widget.SlotWidget(
                            (com.lowdragmc.lowdraglib.side.item.IItemTransfer) this.shanhaiStockItemHandler,
                            index,
                            4 + (cell % 8) * 18,
                            62 + (cell / 8) * 18);
            slot.setCanTakeItems(false);
            slot.setCanPutItems(false);
            page.addWidget(slot);
            // 半透明覆盖：看得见、点不动（用户口径：禁用态；不新增任何字段）
            page.addWidget(new ImageWidget(4 + (cell % 8) * 18, 62 + (cell / 8) * 18, 18, 18,
                    shanhaiRect(0x66000000)));
        }

        // ---- 下：实际命中物品格（8×2，只读；C 刀起接上**真实数据**）
        // 2026-10-24：标题改说人话 —— 「ME 里可用的（与 ME 共享）」（用户口径：这些是 ME 网络里
        //   现在就取得到的东西，与 ME 共享内容物、不搬物品）。「前 N 个」那层信息挪进 tooltip。
        page.addWidget(new LabelWidget(4, 102, Component.translatable(LANG_TAG_ACTUAL_GRID_RENAMED).getString())
                .setHoverTooltips(Component.translatable(LANG_TAG_ACTUAL_GRID_TIP, TAG_MATCHED_ICON_LIMIT)));
        for (int cell = 0; cell < 16; cell++) {
            final int index = cell;
            // 🔴 每一格都按同步下来的 id **反查物品**再画；反查不到就只留暗底（留空），
            //    绝不用占位物品凑数（用户口径 + 本工程「活的界面上不许放假数据」）。
            page.addWidget(new ImageWidget(4 + (cell % 8) * 18, 112 + (cell / 8) * 18, 18, 18,
                    this.shanhaiMatchedIconTexture(index)));
        }

        // ---- 底部标签行：当前生效的白/黑名单 + 匹配预览（都是同步字段，客户端读得到）
        page.addWidget(new LabelWidget(4, 144, this::shanhaiTagLabelRow));
        page.addWidget(new LabelWidget(4, 156, this::shanhaiTagPreviewLine));
        return page;
    }

    /** 底部标签行：把生效的白/黑名单压成一行（长串会截断，避免撑破面板）。 */
    private @NotNull String shanhaiTagLabelRow() {
        return Component.translatable(LANG_TAG_LABEL_ROW, shorten(this.shanhaiTagWhite),
                shorten(this.shanhaiTagBlack)).getString();
    }

    /** 截断到 24 字符（超长显示 …）。 */
    private static @NotNull String shorten(@org.jetbrains.annotations.Nullable String text) {
        if (text == null || text.isEmpty()) {
            return "-";
        }
        return text.length() <= 24 ? text : text.substring(0, 24) + "...";
    }
    /** 页面上的「当前白名单 / 黑名单」一行（读同步字段，不读输入框）。 */
    private @NotNull String shanhaiTagCurrentLine() {
        final String white = this.shanhaiTagWhite == null || this.shanhaiTagWhite.isEmpty()
                ? Component.translatable(LANG_WILDCARD_AUTO).getString() : this.shanhaiTagWhite;
        final String black = this.shanhaiTagBlack == null || this.shanhaiTagBlack.isEmpty()
                ? Component.translatable(LANG_WILDCARD_AUTO).getString() : this.shanhaiTagBlack;
        return Component.translatable(LANG_TAG_CURRENT, white, black).getString();
    }

    /** 匹配预览一行。 */
    private @NotNull String shanhaiTagPreviewLine() {
        return this.shanhaiTagPreview == null ? "" : this.shanhaiTagPreview;
    }

    // ---------------------------------------------------------------- 阶段七：左下角两个开关（闸 / 神锻）

    /** 闸计数行（GUI；与 Jade 两行、WARN 同一对字段）。 */
    private @NotNull String shanhaiGateCountLine() {
        if (this.shanhaiUntypedPatternCount <= 0 && this.shanhaiAmbiguousPatternCount <= 0) {
            return Component.translatable(LANG_GATE_COUNT_NONE).getString();
        }
        return Component.translatable(LANG_GATE_COUNT, this.shanhaiUntypedPatternCount,
                this.shanhaiAmbiguousPatternCount).getString();
    }

    /**
     * 闸按钮 tooltip：{@code 闸（拦歧义样板）：开/关}。
     *
     * <p>语义一个字都没动 —— 仍然只读 {@link #blockAmbiguousPatterns}；
     * 变的只是「呈现方式」（原来是页面上一个 30 px 的文字按钮，现在是左侧一列的高亮/暗图标按钮）。
     */
    private @NotNull Component shanhaiGateTooltip() {
        return Component.translatable(LANG_TIP_GATE,
                Component.translatable(this.blockAmbiguousPatterns
                        ? LANG_STATE_ON : LANG_STATE_OFF));
    }

    /** 神锻按钮 tooltip：{@code 神锻样板：开/关（倍率 N）}。 */
    private @NotNull Component shanhaiForgeTooltip() {
        return Component.translatable(LANG_TIP_FORGE,
                Component.translatable(this.forgePatternMode
                        ? LANG_STATE_ON : LANG_STATE_OFF),
                this.forgePatternMultiplier);
    }

    // ---------------------------------------------------------------- 阶段七：标签过滤页的输入/预览

    /** 标签页：白名单输入框回调（服务端；空串 = 不过滤）。 */
    private void shanhaiApplyTagWhite(@Nullable String text) {
        if (this.isRemote()) {
            return;
        }
        this.shanhaiTagWhite = text == null ? "" : text.trim();
        this.shanhaiRefreshTagPreview();
        ShanhaiStellarLog.info("标签过滤：白名单 => [{}]（{}）", this.shanhaiTagWhite,
                this.shanhaiTagPreview);
    }

    /** 标签页：黑名单输入框回调（服务端；空串 = 不排除）。 */
    private void shanhaiApplyTagBlack(@Nullable String text) {
        if (this.isRemote()) {
            return;
        }
        this.shanhaiTagBlack = text == null ? "" : text.trim();
        this.shanhaiRefreshTagPreview();
        ShanhaiStellarLog.info("标签过滤：黑名单 => [{}]（{}）", this.shanhaiTagBlack,
                this.shanhaiTagPreview);
    }

    /**
     * 重算标签页的匹配预览（<b>服务端</b>）。
     *
     * <p>数据源 = {@link #shanhaiStockSnapshot}（ME 网络可用物品的每 ≤1 秒快照）；
     * 判据 = {@link ShanhaiTagFilterExpression}（白名单为空 ⇒ 全收；黑名单命中 ⇒ 排除；语法错 ⇒ 报错原文）。
     * <p>⚠️ 只统计<b>物品</b>（标签在物品注册表里；流体是另一套 registry，本阶段不混算）。
     */
    private void shanhaiRefreshTagPreview() {
        if (this.isRemote()) {
            return;
        }
        if (!this.shanhaiTagFilterEnabled) {
            // 关 ⇒ 完全不参与（不匹配、不供料）；预览也写清"已关闭"，避免看着像在工作
            this.shanhaiTagPreview = Component.translatable(LANG_TAG_DISABLED).getString();
            this.shanhaiTagMatchedIcons = "";
            return;
        }
        if (this.shanhaiStockSnapshot == null) {
            this.shanhaiTagPreview = Component.translatable(LANG_TAG_PREVIEW_NO_DATA).getString();
            this.shanhaiTagMatchedIcons = "";
            return;
        }
        final String white = this.shanhaiTagWhite == null ? "" : this.shanhaiTagWhite.trim();
        final String black = this.shanhaiTagBlack == null ? "" : this.shanhaiTagBlack.trim();
        int matched = 0;
        int total = 0;
        // C 刀 · C 段：顺手记下**前 16 个命中物品的 id**（顺序 = 遍历顺序，稳定可复现）
        final java.util.List<String> icons = new java.util.ArrayList<>(TAG_MATCHED_ICON_LIMIT);
        try {
            for (it.unimi.dsi.fastutil.objects.Object2LongMap.Entry<AEKey> entry : this.shanhaiStockSnapshot) {
                if (!(entry.getKey() instanceof AEItemKey itemKey)) {
                    continue;
                }
                total++;
                final java.util.List<String> tags = this.shanhaiItemTagStrings(itemKey);
                final boolean whiteOk = white.isEmpty() || ShanhaiTagFilterExpression.matches(white, tags);
                final boolean blackHit = !black.isEmpty() && ShanhaiTagFilterExpression.matches(black, tags);
                if (whiteOk && !blackHit) {
                    matched++;
                    if (icons.size() < TAG_MATCHED_ICON_LIMIT) {
                        final String id = this.shanhaiItemIdOf(itemKey);
                        if (!id.isEmpty()) {
                            icons.add(id);
                        }
                    }
                }
            }
        } catch (IllegalArgumentException badExpression) {
            // 表达式写错 ⇒ 把错误原文显示出来（不静默当成全收/全不收）
            this.shanhaiTagPreview = Component.translatable(LANG_TAG_PREVIEW_ERROR,
                    String.valueOf(badExpression.getMessage())).getString();
            this.shanhaiTagMatchedIcons = "";
            return;
        }
        this.shanhaiTagPreview = Component.translatable(LANG_TAG_PREVIEW, matched, total).getString();
        this.shanhaiTagMatchedIcons = ShanhaiRecipeTypeLabels.joinIds(icons);
    }

    /** 一个 AE 物品键的注册名（{@code minecraft:iron_ingot}），取不到 ⇒ {@code ""}。 */
    private @NotNull String shanhaiItemIdOf(@NotNull AEItemKey key) {
        try {
            final ItemStack stack = key.getReadOnlyStack();
            if (stack.isEmpty()) {
                return "";
            }
            final ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            return id == null ? "" : id.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    /**
     * 标签页下半组第 {@code cell} 格的纹理：暗底 ＋（若能反查）物品图标。
     *
     * <p>🔴 <b>反查不到 ⇒ 只有暗底</b>：绝不用占位物品凑数。
     * ⚠️ 只在客户端 {@code draw} 里读注册表（服务端也会创建这个纹理对象，但从不 draw ⇒ 不碰渲染类）。
     */
    private IGuiTexture shanhaiMatchedIconTexture(int cell) {
        return new IGuiTexture() {
            @Override
            public void draw(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY,
                             float x, float y, int width, int height) {
                StellarSampleAssemblerPartMachine.shanhaiRect(0x33000000)
                        .draw(graphics, mouseX, mouseY, x, y, width, height);
                final IGuiTexture icon = StellarSampleAssemblerPartMachine.this.shanhaiMatchedIconAt(cell);
                if (icon != null) {
                    icon.draw(graphics, mouseX, mouseY, x, y, width, height);
                }
            }
        };
    }

    /** 第 {@code cell} 格该画哪个物品图标；没有/反查不到 ⇒ {@code null}（= 留空）。 */
    private @Nullable IGuiTexture shanhaiMatchedIconAt(int cell) {
        final IGuiTexture[] icons = this.shanhaiMatchedIcons();
        return cell >= 0 && cell < icons.length ? icons[cell] : null;
    }

    /** 反查结果的缓存（key = 同步下来的 id 串；变了才重建）。 */
    private String shanhaiMatchedIconCacheKey;
    private IGuiTexture[] shanhaiMatchedIconCache = new IGuiTexture[0];

    /**
     * 把同步下来的 id 串解析成「每格一个图标（或 null）」。
     *
     * <p>⚠️ 缓存只是**性能**优化，不改变语义：key 变了就整批重建，反查失败的那格<b>仍然是 null</b>。
     */
    private IGuiTexture[] shanhaiMatchedIcons() {
        final String key = this.shanhaiTagMatchedIcons == null ? "" : this.shanhaiTagMatchedIcons;
        if (java.util.Objects.equals(key, this.shanhaiMatchedIconCacheKey)) {
            return this.shanhaiMatchedIconCache;
        }
        final java.util.List<String> ids = ShanhaiRecipeTypeLabels.splitIds(key);
        final int size = Math.min(TAG_MATCHED_ICON_LIMIT, Math.max(0, ids.size()));
        final IGuiTexture[] icons = new IGuiTexture[size];
        for (int i = 0; i < size; i++) {
            icons[i] = shanhaiIconOfItemId(ids.get(i));
        }
        this.shanhaiMatchedIconCacheKey = key;
        this.shanhaiMatchedIconCache = icons;
        return icons;
    }

    /** 注册名 → 物品图标；{@code null} = 反查不到（调用方留空，不许拿别的物品顶替）。 */
    private static @Nullable IGuiTexture shanhaiIconOfItemId(@Nullable String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        try {
            final ResourceLocation key = ResourceLocation.tryParse(id);
            if (key == null) {
                return null;
            }
            final net.minecraft.world.item.Item item = BuiltInRegistries.ITEM.get(key);
            if (item == null || item == net.minecraft.world.item.Items.AIR) {
                return null;
            }
            final ItemStack stack = new ItemStack(item);
            if (stack.isEmpty()) {
                return null;
            }
            return new com.lowdragmc.lowdraglib.gui.texture.ItemStackTexture(stack);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 一个 AE 物品键身上的标签串（{@code forge:ores} 这种形式）。 */
    private @NotNull java.util.List<String> shanhaiItemTagStrings(@NotNull AEItemKey key) {
        final ItemStack stack = key.getReadOnlyStack();
        if (stack.isEmpty()) {
            return java.util.List.of();
        }
        final java.util.List<String> out = new java.util.ArrayList<>();
        stack.getTags().forEach(tag -> out.add(tag.location().toString()));
        return out;
    }

    // ---------------------------------------------------------------- 槽号 ↔ 样板（对外 API）

    /**
     * 对外槽号 → 那块样板。<b>唯一的槽号换算点</b>。
     *
     * <p>槽号空间（对外）：{@code 0 .. 1079} = 普通样板（父类 {@code patternInventory}），
     * {@code 1080 .. 1129} = 通配符样板（本类 {@link #wildcardPatternInventory}）。
     * <p>⚠️ 这个「对外空间」<b>只用于本类的 API/日志/界面</b>，<b>绝不</b>回灌父类方法 ——
     * 父类只认 0..1079（类注释 §七）。
     *
     * @return 越界/空槽 ⇒ 空栈；{@code null} 只可能来自真正越界的槽号
     */
    public @Nullable ItemStack patternStackOf(int apiSlot) {
        if (apiSlot >= 0 && apiSlot < PATTERN_SLOT_COUNT) {
            return this.getPatternInventory().getStackInSlot(apiSlot);
        }
        if (apiSlot >= WILDCARD_API_SLOT_OFFSET && apiSlot < WILDCARD_API_SLOT_OFFSET + WILDCARD_SLOT_COUNT) {
            return this.wildcardPatternInventory.getStackInSlot(apiSlot - WILDCARD_API_SLOT_OFFSET);
        }
        return null;
    }

    /**
     * <b>③ 的对外 API（只读）</b>：这块样板被读出来的<b>归一化配方类型 id</b>。
     *
     * <ul>
     *   <li>空表 ⇒ 四个来源都没写（= 自动识别）；</li>
     *   <li>1 个 ⇒ 唯一采信；</li>
     *   <li>≥2 个 ⇒ <b>歧义</b>（不猜，把候选原样交出去，由调用方决定怎么办）。</li>
     * </ul>
     * 方法名与语义按父代理给的口径；本方法<b>不产生任何副作用</b>（不写 NBT、不改缓存、不做闸）。
     */
    public @NotNull List<String> getRecipeTypeIdsForSlot(int apiSlot) {
        return this.resolveRecipeTypeForSlot(apiSlot).ids();
    }

    /** 同上，但把「四个来源各读到了什么 / 有没有歧义 / 哪些 id 在注册表里查不到」一并交出去。 */
    public @NotNull ShanhaiPatternTypeReader.Resolution resolveRecipeTypeForSlot(int apiSlot) {
        final ItemStack stack = this.patternStackOf(apiSlot);
        return stack == null ? ShanhaiPatternTypeReader.resolve(ItemStack.EMPTY)
                : ShanhaiPatternTypeReader.resolve(stack);
    }

    // ---------------------------------------------------------------- 母槽中间配置（⑤）

    /**
     * <b>⑤ 的写入口</b>：给某个通配符槽的样板写入（或清除）它<b>手动指定</b>的配方类型。
     *
     * <p>「中间配置」落在<b>样板物品自己的 NBT</b> 上（键 {@code gt_shanhai_recipe_type}），不是机器的数组字段：
     * <ul>
     *   <li>清掉 = 删键 = 回自动识别（本方法 {@code raw} 为空即如此）；</li>
     *   <li>这份配置<b>跟着样板走</b>：把这块通配符样板挪到另一台同款机器，指定关系不丢；</li>
     *   <li>机器侧<b>零新增数组字段</b> ⇒ 绕开 LDLib「复杂数组必须 IContentChangeAware/@LazyManaged」
     *       那一整类崩溃（见 {@link #wildcardConfigType} 的注释）。</li>
     * </ul>
     * ⚠️ 代价（写清楚）：配置<b>不</b>跟「槽号」绑定 —— 换一块样板进来，读到的是新样板自己的配置。
     * 若用户要「配置钉在槽上、换样板仍保留」，那是另一种语义，需要 {@code @Persisted @LazyManaged String[]}，
     * 属显式设计变更（报告「待确认」里已列）。
     *
     * @param wildcardSlot 0 .. {@value #WILDCARD_SLOT_COUNT}-1
     * @param raw          要写的类型 id（任意写法，走 {@link ShanhaiRecipeTypeIds#canonical}）；
     *                     空/null = 清除
     * @return 真的改了东西 ⇒ true；槽空/越界/客户端侧 ⇒ false
     */
    public boolean applyWildcardRecipeType(int wildcardSlot, @Nullable String raw) {
        if (this.isRemote()) {
            return false;
        }
        if (wildcardSlot < 0 || wildcardSlot >= WILDCARD_SLOT_COUNT) {
            ShanhaiStellarLog.warn("applyWildcardRecipeType 槽号越界：{}（合法 0..{}）", wildcardSlot,
                    WILDCARD_SLOT_COUNT - 1);
            return false;
        }
        final ItemStack stack = this.wildcardPatternInventory.getStackInSlot(wildcardSlot);
        if (stack.isEmpty()) {
            ShanhaiStellarLog.type("applyType 跳过：通配符槽 {} 是空的", wildcardSlot);
            return false;
        }
        final String normalized = ShanhaiRecipeTypeIds.canonical(raw);
        // 改的是【副本】再塞回去：ItemStackTransfer 的 setStackInSlot 会触发 contents-changed，
        // 直接 in-place 改 tag 不触发（那样会「看起来改了、其实没存档」）。
        final ItemStack updated = stack.copy();
        final CompoundTag tag = updated.getOrCreateTag();
        // 🔴 键名/清除语义的唯一落笔处在 ShanhaiWildcardTypeNbt（与上游同一个键 gt_shanhai_recipe_type）：
        //    normalized 为空 ⇒ **删键**（回自动识别），不是写空串。
        final boolean changed = ShanhaiWildcardTypeNbt.write(tag, normalized);
        if (tag.isEmpty()) {
            updated.setTag(null);
        }
        this.wildcardPatternInventory.setStackInSlot(wildcardSlot, updated);
        this.shanhaiRefreshWildcardConfigType();
        this.shanhaiRefreshWildcardMask();
        this.shanhaiInvalidateGateCache();
        ShanhaiStellarLog.info("通配符配方种类写回：母槽={} 类型=[{}] 键={} 内容有变化={}",
                wildcardSlot + 1, normalized, ShanhaiWildcardTypeNbt.KEY, changed);
        if (ShanhaiStellarLog.isTypeLogEnabled()) {
            final ShanhaiPatternTypeReader.Resolution after =
                    ShanhaiPatternTypeReader.resolve(this.wildcardPatternInventory.getStackInSlot(wildcardSlot));
            ShanhaiStellarLog.logSlotType("applyType", WILDCARD_API_SLOT_OFFSET + wildcardSlot,
                    this.wildcardPatternInventory.getStackInSlot(wildcardSlot), after);
        }
        return true;
    }

    /**
     * 中键：把这一格设为配置目标（**服务端**执行才有效，客户端那次本地执行被守卫掉）。
     *
     * <p>⚠️ C 刀起它只负责「哪一格是编辑目标」；打开面板由调用点紧接着调
     * {@code StellarWildcardCatalystPanel#toggleFor(母槽)}。两件事分开，是因为前者是机器状态
     * （要同步给界面画「目标：第 N 格」），后者是界面状态（每侧各一份）。
     */
    private void shanhaiSelectWildcardConfigSlot(int wildcardSlot) {
        if (this.isRemote()) {
            return;
        }
        this.wildcardConfigSlot = wildcardSlot;
        this.shanhaiRefreshWildcardConfigType();
        ShanhaiStellarLog.type("select wildcardSlot={} apiSlot={}", wildcardSlot,
                WILDCARD_API_SLOT_OFFSET + wildcardSlot);
    }

    // ================================================================ C 刀 · A 段：面板要用的读取面

    /** 面板要的三份存储之一：每母槽 3×3 物品催化剂（{@code ItemStackTransfer[]} 是 {@code IItemTransfer[]} 的子类型）。 */
    public IItemTransfer[] shanhaiWildcardCatalystItemTransfers() {
        return this.shanhaiWildcardCatalystItems;
    }

    /** 面板要的三份存储之二：每母槽 3×3 流体催化剂。 */
    public FluidTransferList[] shanhaiWildcardCatalystFluidTransfers() {
        return this.shanhaiWildcardCatalystFluids;
    }

    /**
     * 面板要的三份存储之三：「实际配方数量」数组本身（{@code byte[]}）。
     *
     * <p>🔴 <b>返回的就是字段本身，不是拷贝</b>：{@code MEPatternCatalystUIManager} 直接往这个数组里写。
     */
    public byte[] shanhaiWildcardCacheCountArray() {
        return this.shanhaiWildcardCacheCount;
    }

    /**
     * 面板改了某槽的「实际配方数量」（服务端回调）。
     *
     * <p>⚠️ 现状写清楚（不许含糊）：这个数<b>已经落库、已经同步、界面上能改能看</b>，
     * 但「攒够 N 条不同配方才允许被看见」这条<b>语义还没接进本机的执行链</b>——
     * 本机的通配符展开是「静态展开」（{@code ShanhaiWildcardCompat.expandPatterns}），
     * 没有上游那套「每槽一组已缓存配方」的容器。接它需要另一刀（报告「待你确认」里已列）。
     */
    public void shanhaiOnWildcardCacheCountChanged(int wildcardSlot) {
        if (this.isRemote() || wildcardSlot < 0 || wildcardSlot >= WILDCARD_SLOT_COUNT) {
            return;
        }
        ShanhaiStellarLog.info("通配符「实际配方数量」变更：母槽={} 新值={}",
                wildcardSlot + 1, this.shanhaiWildcardCacheCount[wildcardSlot]);
    }

    /** 某母槽的编程电路（{@link ShanhaiSlotCircuit#NONE} = 未设置）。 */
    public int shanhaiWildcardCircuit(int wildcardSlot) {
        if (wildcardSlot < 0 || wildcardSlot >= WILDCARD_SLOT_COUNT) {
            return ShanhaiSlotCircuit.NONE;
        }
        return this.shanhaiWildcardCircuit[wildcardSlot];
    }

    /**
     * 写某母槽的编程电路（非 {@code 1..32} 一律归一成 {@link ShanhaiSlotCircuit#NONE} = 清除）。
     *
     * <p>🔴 <b>客户端侧一律不写</b>（{@code isRemote()} 早退）：服务端权威值由 {@code @DescSynced} 同步回来。
     * <p>值域沿用本工程既有口径 {@link ShanhaiSlotCircuit#MIN}..{@link ShanhaiSlotCircuit#MAX}
     * （{@code javap}{@code IntCircuitBehaviour} 同值），并且 {@link ShanhaiSlotCircuit#NONE} = 0：
     * GT 那张网格的 0 号按钮与 ghostCircuit 的「清除」键在语义上都是「不设置」——
     * 这与本工程 {@code ShanhaiCircuitGrid#circuitAt} 的既有结论逐字一致。
     */
    public void shanhaiApplyWildcardCircuit(int wildcardSlot, int circuit) {
        if (this.isRemote()) {
            return;
        }
        if (wildcardSlot < 0 || wildcardSlot >= WILDCARD_SLOT_COUNT) {
            return;
        }
        final int normalized = ShanhaiSlotCircuit.isValid(circuit) ? circuit : ShanhaiSlotCircuit.NONE;
        if (this.shanhaiWildcardCircuit[wildcardSlot] == (byte) normalized) {
            return;
        }
        this.shanhaiWildcardCircuit[wildcardSlot] = (byte) normalized;
        this.expandCacheDirty = true;
        ShanhaiStellarLog.info("通配符编程电路写入：母槽={} 电路={}（{} = 清除）",
                wildcardSlot + 1, normalized, ShanhaiSlotCircuit.NONE);
    }

    // ================================================================ C 刀 · B 段：宿主支持的配方类型

    /**
     * <b>两边共用的纯函数</b>：从控制器列表采「宿主真正支持的配方类型 id」（去重、保序、跳过 gtceu:dummy）。
     *
     * <p>阶段十八：服务端实体字段的重算与界面初始数据包（{@code writeInitialData}）**共用这一份**
     * ⇒ 不会出现"两处各算一遍、结果不一致"的漂移。**不依赖 isRemote / 不写任何字段**。
     */
    private static @NotNull java.util.List<String> shanhaiCollectHostTypeIds(
            @NotNull java.util.List<IMultiController> controllers) {
        final java.util.List<String> ids = new java.util.ArrayList<>();
        for (IMultiController controller : controllers) {
            if (!(controller instanceof IRecipeLogicMachine recipeMachine)) {
                continue;
            }
            final GTRecipeType[] types = recipeMachine.getRecipeTypes();
            if (types == null) {
                continue;
            }
            for (GTRecipeType type : types) {
                if (type == null || type.registryName == null) {
                    continue;
                }
                if ("gtceu:dummy".equals(type.registryName.toString())) {
                    continue;
                }
                final String id = type.registryName.toString();
                if (!ids.contains(id)) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    /**
     * 重算「宿主真正支持的配方类型」并写进 {@link #shanhaiHostRecipeTypeIds}（<b>只在该方法内写</b>）。
     *
     * <h2>算法 = 上游 {@code WildcardPatternRecipeTypeBinding.collectHostRecipeTypes} 的逐条复刻</h2>
     * 那个类只在<b>上游 jar</b> 里（本仓库 libs 无此包，已用 {@code jar tf} 全量扫过），
     * 所以按它的字节码原样重写：
     * <pre>
     *   入参 null                                    ⇒ List.of()
     *   for controller in controllers:
     *       if (!(controller instanceof IRecipeLogicMachine)) continue;      // 只问「有配方逻辑」的控制器
     *       types = ((IRecipeLogicMachine) controller).getRecipeTypes();
     *       if (types == null) continue;
     *       for type in types:
     *           type == null || type.registryName == null          ⇒ 跳过
     *           "gtceu:dummy".equals(type.registryName)            ⇒ 跳过（上游 PatternRecipeExecutionGuard
     *                                                                 的 AUXILIARY_IO_RECIPE_TYPE）
     *           LinkedHashMap.putIfAbsent(registryName, type)      ⇒ 按 id 去重、保插入序
     * </pre>
     * 两条前置的 javap 结论（本轮实测，见报告 §前置取证）：
     * {@code IMultiPart.getControllers()} 是 <b>public abstract</b>（本类可直接调用）；
     * {@code GTRecipeType.registryName} 是 <b>public final ResourceLocation</b>（可直接读）。
     *
     * <p>⚠️ 只服务端算（{@link #isRemote()} 早退）：客户端那份 {@code getControllers()} 一般是空的。
     */
    private void shanhaiRefreshHostRecipeTypeIds() {
        if (this.isRemote()) {
            return;
        }
        final java.util.List<String> ids = shanhaiCollectHostTypeIds(this.getControllers());
        final String joined = ShanhaiRecipeTypeLabels.joinIds(ids);
        if (!joined.equals(this.shanhaiHostRecipeTypeIds)) {
            this.shanhaiHostRecipeTypeIds = joined;
            ShanhaiStellarLog.info("宿主支持配方类型：{} 个（{}）", ids.size(), joined);
        }
    }

    /**
     * 结构成型/拆掉时重算宿主类型。
     *
     * <p>⚠️ 必须调 {@code super}：{@code MultiblockPartMachine} 在这两个方法里维护
     * {@code controllerPositions}（{@code javap} 实证有该字段），不转发结构状态就坏了。
     */
    @Override
    public void addedToController(@NotNull IMultiController controller) {
        super.addedToController(controller);
        this.shanhaiRefreshHostRecipeTypeIds();
    }

    @Override
    public void removedFromController(@NotNull IMultiController controller) {
        super.removedFromController(controller);
        this.shanhaiRefreshHostRecipeTypeIds();
    }

    // ================================================================ C 刀 · A 段：催化剂 → InternalSlot

    /**
     * 把某母槽的 3×3 物品 / 3×3 流体催化剂<b>灌进那个母槽自己的 {@code InternalSlot} 催化剂 map</b>。
     *
     * <h2>为什么灌 map 而不是别的地方</h2>
     * {@code PatternBufferInternalSlot.testCatalystItemInternal(recipe)} 判的就是它<b>自己那份</b>
     * {@code itemCatalystInventory}（{@code javap -c} 实证：{@code hasItemCatalystInventory()} =
     * {@code itemCatalystInventory.isEmpty()} 取反）⇒ 把内容写进那份 map，催化剂语义立刻成立。
     *
     * <h2>逐句对齐父类 {@code reCalculateCatalystItemMap(int)} / {@code reCalculateCatalystFluidMap(int)}</h2>
     * <pre>
     *   itemCatalystInventory.clear(); configuredVirtualItems.clear(); configuredVirtualFluids.clear();
     *   for stack in catalystItems[slot]:
     *       空 ⇒ 跳过
     *       GTLItems.VIRTUAL_INGREDIENT.isIn(stack) ⇒ 只往 configuredVirtual* 里塞（虚拟供料，不占催化剂 map）
     *       否则 ⇒ itemCatalystInventory.merge(AEItemKey.of(stack), stack.getCount(), Long::sum)
     *   fluidCatalystInventory.clear();
     *   for tank in catalystFluids[slot]:
     *       fluidCatalystInventory.merge(AEFluidKey.of(fluid), amount, Long::sum)
     * </pre>
     */
    private void shanhaiRecalculateWildcardCatalyst(int mother) {
        if (this.isRemote() || mother < 0 || mother >= WILDCARD_SLOT_COUNT) {
            return;
        }
        final MEPatternBufferPartMachineBase.InternalSlot internal;
        try {
            internal = this.getInternalSlot(WILDCARD_API_SLOT_OFFSET + mother);
        } catch (Throwable notReadyYet) {
            // 读档期 LDLib 是「就地反序列化」的：万一某条槽容器在 InternalSlot 列表就绪之前先被还原，
            // 这里只会拿到一个越界异常 —— 吞掉并及时收手，绝不让它把加载弄崩（onLoad 会再刷一遍）。
            return;
        }
        if (internal == null) {
            return;
        }
        final Object2LongMap<AEItemKey> itemMap = internal.getItemCatalystInventory();
        itemMap.clear();
        internal.getConfiguredVirtualItems().clear();
        internal.getConfiguredVirtualFluids().clear();
        final ItemStackTransfer items = this.shanhaiWildcardCatalystItems[mother];
        for (int i = 0; i < items.getSlots(); i++) {
            final ItemStack stack = items.getStackInSlot(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (org.gtlcore.gtlcore.common.data.GTLItems.VIRTUAL_INGREDIENT.isIn(stack)) {
                final AEItemKey virtualItem =
                        org.gtlcore.gtlcore.common.item.VirtualIngredientBehavior.payloadItemKey(stack);
                if (virtualItem != null) {
                    internal.getConfiguredVirtualItems().add(virtualItem);
                }
                final AEFluidKey virtualFluid =
                        org.gtlcore.gtlcore.common.item.VirtualIngredientBehavior.payloadFluidKey(stack);
                if (virtualFluid != null) {
                    internal.getConfiguredVirtualFluids().add(virtualFluid);
                }
            } else {
                itemMap.mergeLong(AEItemKey.of(stack), stack.getCount(), Long::sum);
            }
        }
        final Object2LongMap<AEFluidKey> fluidMap = internal.getFluidCatalystInventory();
        fluidMap.clear();
        final FluidTransferList fluids = this.shanhaiWildcardCatalystFluids[mother];
        if (fluids != null) {
            for (int tank = 0; tank < fluids.getTanks(); tank++) {
                final com.lowdragmc.lowdraglib.side.fluid.FluidStack fluid = fluids.getFluidInTank(tank);
                if (fluid.isEmpty()) {
                    continue;
                }
                fluidMap.mergeLong(AEFluidKey.of(fluid.getFluid()), fluid.getAmount(), Long::sum);
            }
        }
        // ⚠️ 这里【不】置 expandCacheDirty：催化剂只影响「配方测试」，不影响展开表的内容；
        //    每次刷新都置脏的话，展开表会被按 tick 重建（那是实打实的开销）。
    }

    /**
     * 把 50 个母槽的催化剂 map 全刷一遍（服务端，跟着 {@code syncStockInput} 的节拍）。
     *
     * <h2>为什么要「定期刷」而不是「只在变化时刷」</h2>
     * 物品催化剂那边有 {@code ItemStackTransfer.setOnContentsChanged} 可挂；
     * <b>流体那 9 个罐没有这条回调</b>（{@code FluidTransferList} 不提供）。
     * 只靠「物品变了才刷」的话，只配了流体催化剂的玩家会看到「配了不生效」。
     * 代价：50 × (9 + 9) 次循环/节拍，且只读数组不碰 NBT ⇒ 比一次展开表重建便宜得多。
     */
    private void shanhaiRefreshAllWildcardCatalysts() {
        if (this.isRemote()) {
            return;
        }
        for (int mother = 0; mother < WILDCARD_SLOT_COUNT; mother++) {
            this.shanhaiRecalculateWildcardCatalyst(mother);
        }
    }

    /** 样板格内容变了（服务端回调）：刷新显示用的手填值 + 打开开关时打一行。 */
    private void shanhaiOnWildcardSlotChanged(int wildcardSlot) {
        if (this.isRemote()) {
            return;
        }
        this.shanhaiRefreshWildcardConfigType();
        this.shanhaiRefreshWildcardMask();
        this.forgeCacheDirty = true;
        if (ShanhaiStellarLog.isTypeLogEnabled()) {
            final ItemStack stack = this.wildcardPatternInventory.getStackInSlot(wildcardSlot);
            ShanhaiStellarLog.logSlotType("wildcardChanged", WILDCARD_API_SLOT_OFFSET + wildcardSlot, stack,
                    ShanhaiPatternTypeReader.resolve(stack));
        }
    }

    /** 50 格容器的 contents-changed（`setStackInSlot` / 玩家拖入都会走到这里）。 */
    private void shanhaiOnWildcardInventoryChanged() {
        if (this.isRemote()) {
            return;
        }
        this.shanhaiRefreshWildcardConfigType();
        this.shanhaiRefreshWildcardMask();
        this.forgeCacheDirty = true;
        this.shanhaiInvalidateGateCache();
    }

    /**
     * 把「当前目标格上手填的类型」重新读一遍（<b>唯一写入点</b>）。
     *
     * <p>权威 = 那块样板 NBT 里的 {@code gt_shanhai_recipe_type}；这里只是把它归一化后放进
     * {@link #wildcardConfigType} 给客户端标签用。
     */
    private void shanhaiRefreshWildcardConfigType() {
        final int slot = this.wildcardConfigSlot;
        String manual = "";
        if (slot >= 0 && slot < WILDCARD_SLOT_COUNT) {
            final ItemStack stack = this.wildcardPatternInventory.getStackInSlot(slot);
            if (!stack.isEmpty() && stack.getTag() != null) {
                manual = stack.getTag().getString(ShanhaiRecipeTypeIds.KEY_RECIPE_TYPE);
            }
        }
        this.wildcardConfigType = ShanhaiRecipeTypeIds.canonical(manual);
    }

    /** 配置行文案：{@code 目标：第 N 格 / 配方种类：X（空 = 自动）}；不可用时标成不可用并附 id。 */
    private String shanhaiWildcardConfigLine() {
        final int slot = this.wildcardConfigSlot;
        final String slotText = slot < 0 || slot >= WILDCARD_SLOT_COUNT
                ? "-" : String.valueOf(slot + 1);
        final String type = this.wildcardConfigType == null ? "" : this.wildcardConfigType;
        final String typeText;
        if (type.isEmpty()) {
            typeText = Component.translatable(LANG_WILDCARD_AUTO).getString();
        } else if (!ShanhaiRecipeTypeLabels.splitIds(this.shanhaiHostRecipeTypeIds).contains(type)) {
            // 🔴 上游同款：当前类型不在宿主支持列表里 ⇒ 标「不可用」并把 id 打出来（便于排查）
            typeText = Component.translatable(LANG_WILDCARD_UNAVAILABLE, type).getString();
        } else {
            typeText = ShanhaiRecipeTypeLabels.displayName(
                    ShanhaiRecipeTypeLabels.namespaceOf(type), ShanhaiRecipeTypeLabels.pathOf(type),
                    key -> Component.translatable(key).getString());
        }
        return Component.translatable(LANG_WILDCARD_CONFIG, slotText, typeText).getString();
    }

    // ================================================================ C 刀 · B 段：配方种类选择器

    /**
     * <b>配方种类选择器</b>：一个可滚动列表，每行一个按钮；第一行固定是「自动识别（默认）」。
     *
     * <h2>版面与交互（逐条照上游 {@code fillConfigurator} / {@code addRecipeTypeButtons}）</h2>
     * <pre>
     *   DraggableScrollableWidgetGroup(8,123,width-16,42)         ← 上游那块列表（我们换成页内坐标 150×56）
     *   ButtonWidget(2, y, 134, 16, TextTexture(text, -1))        ← 每行；行距 18，y 从 2 起
     *   选中行前缀 "§a&gt; "，未选中 "§7"（自动行）/ "§f"（类型行）
     *   宿主一个类型都没有 ⇒ 显示 no_host_types 文本（不是空白列表）
     * </pre>
     * 🔴 <b>没有输入框</b>：玩家只能点，不能打字（这是用户对老版本「手打配方 id」的明确否证）。
     *
     * <h2>为什么文字用 {@code TextTexture(Supplier)} 而不是每次重建列表</h2>
     * 「哪一行是选中」会随服务端写回（{@code wildcardConfigType} 同步）变化；
     * 用按帧取值的 supplier ⇒ <b>不需要重建控件树</b>，也就不会让「按钮在父组里的下标」漂移
     * （下标一漂，点击的客户端动作就会派发到别的控件上，正是本工程踩过的那类静默失败）。
     *
     * <h2>为什么点击由本组自己发客户端动作</h2>
     * 见 {@link #ACTION_SET_WILDCARD_TYPE} 的注释：链接从本组开始（它自己知道自己在父组里的下标，
     * 而这个下标不随列表内容变化），服务端再由本组覆写的
     * {@link WildcardTypePicker#handleClientAction} 接住。
     */
    private final class WildcardTypePicker extends WidgetGroup {

        /** 列表本体（滚动区）。 */
        private final DraggableScrollableWidgetGroup list;

        /** 上次建列表时看到的两个同步值（变了就重建；两个都是服务端权威值）。 */
        /** 界面真正的数据源：控件初始数据优先，实体字段只作兜底/对照。 */
        private @NotNull String shanhaiEffectiveIds() {
            if (this.initialIds != null && !this.initialIds.isEmpty()) {
                return this.initialIds;
            }
            final String fallback = StellarSampleAssemblerPartMachine.this.shanhaiHostRecipeTypeIds;
            return fallback == null ? "" : fallback;
        }

        /** 服务端：把宿主类型清单写进界面初始数据包（每次打开界面都发一次）。 */
        @Override
        public void writeInitialData(net.minecraft.network.FriendlyByteBuf buf) {
            super.writeInitialData(buf);
            String ids = "";
            if (!this.isRemote()) {
                try {
                    ids = ShanhaiRecipeTypeLabels.joinIds(
                            shanhaiCollectHostTypeIds(StellarSampleAssemblerPartMachine.this.getControllers()));
                } catch (Throwable ignored) {
                    ids = "";
                }
            }
            buf.writeUtf(ids);
        }

        /** 客户端：读初始数据包 ⇒ 存进控件自己的字段 ⇒ 强制重建一次列表。 */
        @Override
        public void readInitialData(net.minecraft.network.FriendlyByteBuf buf) {
            super.readInitialData(buf);
            this.initialIds = buf.readUtf();
            this.lastIds = null;
            this.shanhaiRebuild();
            ShanhaiStellarLog.info("PICKER initialData ids={} len={}（控件通道；len=0 才是真没算出来）",
                    this.initialIds, this.initialIds == null ? Integer.valueOf(-1)
                            : Integer.valueOf(this.initialIds.length()));
        }

        /**
         * 控件自己通过 {@code readInitialData} 拿到的类型 id 串（**界面的权威数据源**）。
         *
         * <p>阶段十八：宿主类型清单改走**控件初始数据通道**，不再依赖实体字段的 @DescSynced。
         * 原因：实体字段是「变更才发」，若它在玩家没看这台机器时被赋值，之后就再也不会发
         * （值不变 ⇒ 没有下一次变更事件）⇒ 客户端永远空串；控件初始数据是每次打开界面都发一次。
         */
        private String initialIds = "";

        private String lastIds;
        private String lastSelected;

        WildcardTypePicker(int x, int y, int w, int h) {
            super(x, y, w, h);
            this.list = new DraggableScrollableWidgetGroup(0, 0, w, h);
            this.list.setBackground(GuiTextures.DISPLAY);
            this.list.setYScrollBarWidth(4).setYBarStyle(
                    new ColorRectTexture(0xFF3F4252), new ColorRectTexture(0xFFE0E2EA).setRadius(2));
            this.list.setHoverTooltips(Component.translatable(LANG_WILDCARD_PICKER));
            this.addWidget(this.list);
            this.shanhaiRebuild();
        }

        /**
         * 每帧比对「宿主类型列表 / 当前选中类型」，变了就重建行。
         *
         * <p>⚠️ 先 {@code super.updateScreen()}（它会遍历当前子控件）再重建 ⇒ 不会在迭代中改集合。
         * ⚠️ 重建只发生在<b>两个同步值真变了</b>的时候，不是每帧都建。
         */
        @Override
        public void updateScreen() {
            super.updateScreen();
            final String ids = this.shanhaiEffectiveIds();
            // 🔴 阶段十七 · 唯一判据（父代理指定）：客户端到底有没有收到值。同一窗口 5 秒最多一条。
            final long diagNow = StellarSampleAssemblerPartMachine.this.getLevel() == null
                    ? 0L : StellarSampleAssemblerPartMachine.this.getLevel().getGameTime();
            if (diagNow - StellarSampleAssemblerPartMachine.this.shanhaiPickerDiagTick >= 100L) {
                StellarSampleAssemblerPartMachine.this.shanhaiPickerDiagTick = diagNow;
                ShanhaiStellarLog.info("PICKER client ids=\"{}\" len={} remote={}（len=0 ⇒ 同步没到）",
                        ids, ids == null ? Integer.valueOf(-1) : Integer.valueOf(ids.length()),
                        Boolean.valueOf(StellarSampleAssemblerPartMachine.this.isRemote()));
            }
            final String selected = StellarSampleAssemblerPartMachine.this.wildcardConfigType;
            if (!java.util.Objects.equals(this.lastIds, ids)
                    || !java.util.Objects.equals(this.lastSelected, selected)) {
                this.shanhaiRebuild();
            }
        }

        /** 重建整张列表（保留滚动位置）。 */
        private void shanhaiRebuild() {
            this.lastIds = this.shanhaiEffectiveIds();
            this.lastSelected = StellarSampleAssemblerPartMachine.this.wildcardConfigType;
            final int scroll = this.list.getScrollYOffset();
            this.list.clearAllWidgets();
            // 第一行：自动识别（默认）—— 点它 = 清除类型键
            this.list.addWidget(this.shanhaiRow(2, null));
            final java.util.List<String> ids = ShanhaiRecipeTypeLabels.splitIds(this.lastIds);
            if (ids.isEmpty()) {
                // 宿主一个类型都没有：照上游显示一行说明（不是空白）
                this.list.addWidget(new ImageWidget(2, 20, 134, 16, new TextTexture(
                        Component.translatable(LANG_WILDCARD_NO_HOST_TYPES).getString(), -1)));
            } else {
                int rowY = 20;
                for (String id : ids) {
                    this.list.addWidget(this.shanhaiRow(rowY, id));
                    rowY += 18;
                }
            }
            this.list.setScrollYOffset(scroll);
        }

        /**
         * 一行。
         *
         * @param typeId {@code null} = 「自动识别（默认）」那一行；否则是完整类型 id
         */
        private Widget shanhaiRow(int y, @Nullable String typeId) {
            return new ButtonWidget(2, y, 134, 16,
                    new TextTexture(() -> this.shanhaiRowText(typeId)),
                    clickData -> this.shanhaiOnRowClicked(typeId));
        }

        /**
         * 玩家点了这一行（客户端与服务端都会跑一次）。
         *
         * <ul>
         *   <li><b>客户端</b>：把「哪个类型」<b>显式发</b>给服务端（动作号 {@value #ACTION_SET_WILDCARD_TYPE}）；
         *       自己不写 —— 写回样板是服务端的活（{@code applyWildcardRecipeType} 有 {@code isRemote()} 早退）。
         *       ⚠️ {@code writeClientAction} 是 {@code Widget} 的 {@code protected} 方法（本轮 javac 实证：
         *       从别的类上调它会报「protected 访问权限」）⇒ 只能由本组自己发；
         *       正因如此链接只从本组起算（本组在页里的下标不随列表内容变化）。</li>
         *   <li><b>服务端</b>：直接写回那块样板（复用既有唯一写入口）。</li>
         * </ul>
         */
        private void shanhaiOnRowClicked(@Nullable String typeId) {
            final String payload = typeId == null ? "" : typeId;
            if (StellarSampleAssemblerPartMachine.this.isRemote()) {
                this.writeClientAction(ACTION_SET_WILDCARD_TYPE, buf -> buf.writeUtf(payload));
            } else {
                StellarSampleAssemblerPartMachine.this.applyWildcardRecipeType(
                        StellarSampleAssemblerPartMachine.this.wildcardConfigSlot, payload);
            }
        }

        /** 一行上的文字：选中前缀 {@code §a> }（上游原文），后面是本地化名字。 */
        private String shanhaiRowText(@Nullable String typeId) {
            final String selected = StellarSampleAssemblerPartMachine.this.wildcardConfigType;
            final boolean isSelected = typeId == null
                    ? (selected == null || selected.isEmpty())
                    : typeId.equals(selected);
            if (typeId == null) {
                final String auto = Component.translatable(LANG_WILDCARD_AUTO).getString();
                return (isSelected ? "§a> " : "§7") + auto;
            }
            final String label = ShanhaiRecipeTypeLabels.displayName(
                    ShanhaiRecipeTypeLabels.namespaceOf(typeId), ShanhaiRecipeTypeLabels.pathOf(typeId),
                    key -> Component.translatable(key).getString());
            return (isSelected ? "§a> " : "§f") + label;
        }

        /**
         * 服务端收到「玩家点了这一行」。
         *
         * <p>🔴 <b>{@code id == 1} 故意吞掉（不转发 super）</b>：那是 {@code ButtonWidget} 自带的
         * 「按子控件下标分发」通道，而本列表的行数是<b>随同步值变化</b>的
         * （客户端可能比服务端晚一轮拿到宿主类型列表）⇒ 两边下标可能对不上，
         * 一旦让那条通道进来，就会「点了 A 行、服务端应用了 B 行」。
         * 我们自己的动作（{@value #ACTION_SET_WILDCARD_TYPE}）带着<b>类型 id 本身</b>，不依赖任何下标，
         * 所以只要把自带通道关掉，这条路就只有一个确定的行为。
         */
        @Override
        public void handleClientAction(int id, net.minecraft.network.FriendlyByteBuf buffer) {
            if (id == ACTION_SET_WILDCARD_TYPE) {
                final String typeId = buffer.readUtf();
                StellarSampleAssemblerPartMachine.this.applyWildcardRecipeType(
                        StellarSampleAssemblerPartMachine.this.wildcardConfigSlot, typeId);
                return;
            }
            if (id == 1) {
                return;
            }
            super.handleClientAction(id, buffer);
        }
    }

    /**
     * 「点配方种类」的客户端动作号（C 刀 · B 段）。
     *
     * <p>取 {@code 21331}：与 {@code 21320/21321/21322/21330}（其它几处）相邻不重复。
     * 🔴 <b>绝不能取 1</b>：{@code WidgetGroup.handleClientAction} 把 {@code id == 1}
     * 当成「（子控件下标, 子 id）」去解析。
     */
    public static final int ACTION_SET_WILDCARD_TYPE = 21331;

    // ---------------------------------------------------------------- 日志（③ 的可断言面）

    /**
     * 每 tick 一次：<b>自动倍率的周期复读</b>（驱动内部自带 100 tick 探测间隔 + 变化检测）。
     *
     * <p>与「超级通配符 ME 样板总成」那台的 {@code update()} 同构（它也是在 {@code super.update()}
     * 之后调 {@code shanhaiTickAutoForgeMultiplier()}）。放在最后：它可能置脏神锻改写缓存，
     * 那件事的代价比父类 update 里的记账大，不该挡住它们。
     */
    @Override
    protected void update() {
        super.update();
        this.shanhaiTickAutoForgeMultiplier();
    }

    /**
     * {@code onLoad}：先让父类完成它那套接线，再（仅在开关打开时）把全部槽的读取结果打出来。
     *
     * <p>⚠️ 服务端判据用 {@code isRemote()}（与父类 {@code onPatternChange} 同款），
     * 客户端那份不打日志（否则同一台机器两份读数，日志里会互相矛盾）。
     */
    @Override
    public void onLoad() {
        super.onLoad();
        if (this.isRemote()) {
            return;
        }
        this.shanhaiRefreshWildcardConfigType();
        this.shanhaiRefreshWildcardMask();
        this.forgeCacheDirty = true;
        this.shanhaiInvalidateGateCache();
        // C 刀 · B 段：把「宿主支持的配方类型」算出来（结构成型那一路上 addedToController 已经算过，
        // 这里再算一次是为了覆盖「onLoad 晚于结构成型」与「读档后控制器列表已就绪」两种时序）。
        this.shanhaiRefreshHostRecipeTypeIds();
        // C 刀 · A 段：催化剂槽的持久化内容在 onLoad 之前还原 ⇒ 此刻把三份催化剂灌进各自的 InternalSlot。
        for (int mother = 0; mother < WILDCARD_SLOT_COUNT; mother++) {
            this.shanhaiRecalculateWildcardCatalyst(mother);
        }
        // ---- 阶段六 ⑦：迁移 + 缓存 + 首轮同步
        // （onLoad 早于/晚于 loadCustomPersistedData 都不影响正确性：迁移有 once 标记，重复调用是无操作）
        this.shanhaiMigrateLegacyStockConfigs("onLoad");
        // 流体侧同一支（两支互不依赖：各读各的 handler，先后不影响结果；重复调用被 once 标记挡掉）
        this.shanhaiMigrateLegacyStockFluidConfigs("onLoad");
        this.shanhaiWarnInvisiblePatterns("onLoad");
        if (this.shanhaiStockItemHandler != null) {
            this.shanhaiStockItemHandler.onConfigChanged();
        }
        // 流体侧同一处置：读档只还原了 160 个 slot 对象，它自己那份 configList 还是陈旧的
        // （javap：ExportOnlyAEStockingFluidList.onConfigChanged 才重建 configList/configIndexList）
        if (this.shanhaiStockFluidHandler != null) {
            this.shanhaiStockFluidHandler.onConfigChanged();
        }
        ShanhaiStellarLog.info("STOCKCFG 配置变化：我们的键数={}（160 格）", Integer.valueOf(this.shanhaiConfiguredItemKeys().size()));
        this.shanhaiRebuildStockItemKeys();
        this.shanhaiRebuildStockFluidKeys();
        this.shanhaiSyncStock();
        if (ShanhaiStellarLog.isTypeLogEnabled()) {
            this.shanhaiDumpStockConfig("onLoad");
        }
        if (ShanhaiStellarLog.isTypeLogEnabled()) {
            this.shanhaiDumpRecipeTypesToLog("onLoad");
        }
    }

    /**
     * 把「普通 1080 槽 + 通配符 50 槽」里<b>有样板</b>的那些逐槽打一行，最后打一行汇总。
     *
     * <p>只在开关打开时做任何事（{@link ShanhaiStellarLog#isTypeLogEnabled()}）；
     * 关着时连循环都不进那两次字符串拼接（逐槽拼接在 1130 槽上是实打实的开销）。
     *
     * <p>调用点：{@link #onLoad()}（开服/放机器时一次）、以及阶段三把它挂到命令上做现场断言。
     */
    public void shanhaiDumpRecipeTypesToLog(@NotNull String where) {
        if (!ShanhaiStellarLog.isTypeLogEnabled()) {
            return;
        }
        int unique = 0;
        int ambiguous = 0;
        int none = 0;
        int total = 0;
        final int maxApiSlot = WILDCARD_API_SLOT_OFFSET + WILDCARD_SLOT_COUNT;
        for (int apiSlot = 0; apiSlot < maxApiSlot; apiSlot++) {
            final ItemStack stack = this.patternStackOf(apiSlot);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            total++;
            final ShanhaiPatternTypeReader.Resolution resolution = ShanhaiPatternTypeReader.resolve(stack);
            switch (resolution.verdict()) {
                case UNIQUE -> unique++;
                case AMBIGUOUS -> ambiguous++;
                default -> none++;
            }
            ShanhaiStellarLog.logSlotType(where, apiSlot, stack, resolution);
        }
        ShanhaiStellarLog.logSummary(where, unique, ambiguous, none, total);
    }

    // ================================================================ 阶段三：槽号空间接入（第 1 项）

    /** 本槽号是不是通配符槽（{@code 1080..1129}）。<b>唯一的判定点</b>。 */
    private static boolean shanhaiIsWildcardSlot(int slot) {
        return slot >= PATTERN_SLOT_COUNT && slot < PATTERN_SLOT_COUNT + WILDCARD_SLOT_COUNT;
    }

    /**
     * 通配符槽的 {@code InternalSlot}（50 个，下标 = 槽号 − 1080）。
     *
     * <p>🔴 <b>自己造，不交给父类的 {@code createInternalSlot} 循环</b>，理由有两个：
     * <ol>
     *   <li>父类那个循环跑在<b>超类构造器里</b>，长度是 {@code internalInventory.length}（1080）
     *       —— 它根本不会碰 ≥1080；</li>
     *   <li>父类构造器给每个槽挂的 {@code setOnContentsChanged} 会调 {@code removeSlotFromGTRecipeCache(slot)}，
     *       那里第一条就是 {@code cacheRecipe[slot] = false}（{@code javap -c} 实证）——
     *       {@code cacheRecipe} 长度只有 1080 ⇒ 通配符槽<b>绝不能</b>挂父类那套回调。
     *       本类给它们挂的是空回调（只保证「非 null」，不让别处的 {@code getOnContentsChanged().run()} NPE）。</li>
     * </ol>
     * <p>⚠️ 数量固定 50（不是「有样板的条数」）：上游用活动数，那样槽号↔下标会随样板增减漂移；
     * 固定 50 + 纯位移映射（槽号−1080）让外部拿到的下标<b>永远指同一格</b>。
     */
    private final List<MEPatternBufferPartMachineBase.InternalSlot> wildcardInternalSlots =
            new ArrayList<>(WILDCARD_SLOT_COUNT);

    /** 父类的终端样板库存（1080 格），构造器里取一次，供合并视图用。 */
    private final InternalInventory baseTerminalPatternInventory;

    /** 合并视图（1080 + 50），构造器里建一次；{@link #getTerminalPatternInventory()} 返回它。 */
    private final InternalInventory combinedTerminalPatternInventory;

    /**
     * AE 终端看到的样板库存 = <b>1080 普通 + 50 通配符</b> 的合并视图。
     *
     * <p>为什么必须合并：父类的 {@code getTerminalPatternInventory()} 返回的是
     * {@code internalPatternInventory}（一个只认 {@code patternInventory} 的匿名类，长度 1080）
     * ⇒ 不合并的话，AE 终端里**看不到**那 50 格通配符样板。上游也是这么处理的
     * （{@code upstream-RecipeTypePatternBufferPartMachine.java:331}
     * {@code createCombinedTerminalPatternInventory(super.getTerminalPatternInventory())}）。
     *
     * <p>{@code InternalInventory} 只有 3 个抽象方法（{@code javap} 实证：{@code size()} /
     * {@code getStackInSlot(int)} / {@code setItemDirect(int, ItemStack)}；{@code isItemValid} 有 default）。
     */
    private final class CombinedTerminalPatternInventory implements InternalInventory {

        @Override
        public int size() {
            return PATTERN_SLOT_COUNT + WILDCARD_SLOT_COUNT;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            if (slot < 0 || slot >= size()) {
                return ItemStack.EMPTY;
            }
            if (slot < PATTERN_SLOT_COUNT) {
                return CombinedTerminalPatternInventory.this.baseRef().getStackInSlot(slot);
            }
            return StellarSampleAssemblerPartMachine.this.wildcardPatternInventory
                    .getStackInSlot(slot - PATTERN_SLOT_COUNT);
        }

        @Override
        public void setItemDirect(int slot, ItemStack stack) {
            if (slot < 0 || slot >= size()) {
                return;
            }
            if (slot < PATTERN_SLOT_COUNT) {
                CombinedTerminalPatternInventory.this.baseRef().setItemDirect(slot, stack);
                return;
            }
            // 通配符槽：走我们自己的容器（setStackInSlot 会触发 contents-changed ⇒ 会存档）。
            StellarSampleAssemblerPartMachine.this.wildcardPatternInventory
                    .setStackInSlot(slot - PATTERN_SLOT_COUNT, stack);
            StellarSampleAssemblerPartMachine.this.onPatternChange(slot);
        }

        private InternalInventory baseRef() {
            return StellarSampleAssemblerPartMachine.this.baseTerminalPatternInventory;
        }
    }

    /**
     * 槽数 = <b>1080 + 50 = 1130</b>（固定）。
     *
     * <p>🔴 覆写它是阶段三第 1 项的核心：Base 类所有「遍历全部槽」的方法
     * （{@code getActiveSlots()} / {@code getActiveAndUnCachedSlots()} / {@code getMergedInternalSlot()} /
     * {@code pushPattern} 里找空槽 等）都以它为界，并且<b>只通过 {@link #getInternalSlot(int)} 取槽</b>
     * （Base 里没有 {@code internalInventory} 这个字段 —— 它住在 {@code MEPatternBufferPartMachine}）
     * ⇒ 把这两个方法成对覆写，Base 的循环就永远不会碰父类数组。
     */
    @Override
    protected int getInternalSlotCount() {
        return ShanhaiWildcardLayout.SLOT_SPACE_TOTAL;
    }

    /**
     * 槽号 → InternalSlot。<b>这是唯一一处把 ≥1080 的下标接住的地方</b>。
     *
     * <p>🔴 越界时<b>抛</b>而不是回落：本类的 {@link #getInternalSlotCount()} 与映射是同一个常量对，
     * 越界只可能来自「有人算错了槽号」这种真 bug。抛出来带着槽号与范围，
     * 比悄悄返回一个别的槽（会让配方跑错格子）或返回 null（会在下游 NPE，堆栈离现场很远）都好。
     */
    @Override
    protected MEPatternBufferPartMachineBase.InternalSlot getInternalSlot(int slot) {
        if (ShanhaiWildcardLayout.isMotherSlot(slot)) {
            return this.wildcardInternalSlots.get(slot - ShanhaiWildcardLayout.MOTHER_OFFSET);
        }
        if (ShanhaiWildcardLayout.isExpandSlot(slot)) {
            return this.expandInternalSlots.get(slot - ShanhaiWildcardLayout.EXPAND_OFFSET);
        }
        if (ShanhaiWildcardLayout.isPatternSlot(slot)) {
            return super.getInternalSlot(slot);
        }
        throw new IndexOutOfBoundsException("星律样板总成：槽号 " + slot + " 越界（合法 0.."
                + (ShanhaiWildcardLayout.SLOT_SPACE_TOTAL - 1) + "：" + ShanhaiWildcardLayout.describe()
                + "）—— 这是槽号计算 bug，不是数据问题");
    }

    /**
     * 通配符槽的「有样板」判定 = 那 50 格里有东西。
     *
     * <p>为什么要覆写：父类那份是 {@code hasPatternArray[slot]} 且<b>自带边界检查</b>
     * （{@code slot < 0 || slot >= hasPatternArray.length → false}，偏移 0–27），
     * 于是通配符槽会一律被判成「没样板」⇒ {@code InternalSlot.isItemActive} 恒 false ⇒
     * 槽永远不活跃。覆写后：普通槽走父类原逻辑（一字不差），通配符槽读自己的容器。
     */
    @Override
    protected boolean hasPatternInSlot(int slot) {
        if (ShanhaiWildcardLayout.isMotherSlot(slot)) {
            return !this.wildcardPatternInventory.getStackInSlot(slot - ShanhaiWildcardLayout.MOTHER_OFFSET).isEmpty();
        }
        if (ShanhaiWildcardLayout.isExpandSlot(slot)) {
            // 这一条展开"存在" ⇔ 母槽有样板且展开条数 > 分支号（展开表在 getAvailablePatterns 路径里重建）
            return this.shanhaiExpansionCountFor(this.shanhaiMotherOfExpandSlot(slot)) > ShanhaiWildcardLayout.branchOf(slot);
        }
        return super.hasPatternInSlot(slot);
    }

    /**
     * 通配符槽的「配方缓存是否就绪」—— <b>二修起改成 false（= 还没就绪，要继续被试配）</b>。
     *
     * <h2>🔴 为什么改（上一轮为什么不能这么写，本轮为什么可以）</h2>
     * 这个方法（机器侧 {@code hasRecipeCacheInSlot}）与 trait 侧
     * {@code IMEPatternTrait#hasCacheInSlot} 是<b>两个不同的东西</b>，上一轮把它们混成一个了：
     * <ul>
     *   <li><b>本方法</b>只被 {@code MEPatternBufferPartMachineBase#getActiveAndUnCachedSlots()}
     *       读（{@code isActive() && !hasRecipeCacheInSlot(i)}），
     *       而那份名单经 {@code MEItemInputHandlerBase#getActiveAndUnCachedSlotsLimitContentsMap()}
     *       进 {@code GTRecipeLookupMixin#gtlCore$fromHolder}（{@code javap -c} 偏移 131）
     *       ⇒ <b>它决定「机器拿什么去配方表里找候选配方」</b>。
     *       返回 true = 「这格已有缓存，别再拿它试配」⇒ 该槽的库存配置<b>根本不会进候选树</b>
     *       ⇒ 控制器一条候选配方都找不到 ⇒ {@code handleRecipe} 一次都不会被调
     *       ⇒ {@code findStockItemKey} 一次都不会被问（= 上一轮日志里
     *       {@code activeSlots=1} 却 {@code STOCKSUPPLY=0} 的真正原因）。</li>
     *   <li><b>trait 侧那个</b>{@code hasCacheInSlot} 才会下标 {@code cacheRecipe[slot]}（长度 1080）——
     *       它已经被 {@link ShanhaiMEPatternTrait#hasCacheInSlot(int)} 短路成「≥1080 ⇒ true，不碰数组」
     *       ⇒ <b>本方法返回 false 不会造成任何越界</b>。</li>
     * </ul>
     * ⇒ 二修：通配符段也要被「试配」（否则库存直供对它们无效），所以这里返回 false。
     * <p>⚠️ 0..1079 仍然逐字节转交父类（那段行为一个字没改）。
     */
    @Override
    protected boolean hasRecipeCacheInSlot(int slot) {
        if (ShanhaiWildcardLayout.isMotherSlot(slot) || ShanhaiWildcardLayout.isExpandSlot(slot)) {
            return false;
        }
        return super.hasRecipeCacheInSlot(slot);
    }

    /**
     * 🔴 <b>对通配符槽号短路</b> —— 阶段三第 1 项里最要命的那一条。
     *
     * <p>父类 {@code onPatternChange(int)} 的第一句就是 {@code internalInventory[slot]}（{@code aaload}，
     * 偏移 9/13，<b>没有边界检查</b>），紧接着调 {@code getRealPattern(slot, stack)} ——
     * 而 {@code getRealPattern} 在 gtladditions 里是 <b>@Overwrite</b>，
     * 它自己也 {@code internalInventory[slot]}（{@code javap -c} 偏移 9/14）⇒
     * 任何 ≥1080 的槽号走到父类那条路 = 当场 {@code ArrayIndexOutOfBoundsException}，
     * <b>而且子类无法通过覆写 getRealPattern 来救</b>（mixin 覆写的是父类方法，我们只能绕开）。
     *
     * <p>⇒ 本覆写是「结构性隔离」的落点：通配符槽号<b>永不</b>调 {@code super.onPatternChange}。
     * 普通槽号一字不差地转交父类（行为零变化）。
     */
    @Override
    protected void onPatternChange(int slot) {
        if (ShanhaiWildcardLayout.isMotherSlot(slot) || ShanhaiWildcardLayout.isExpandSlot(slot)) {
            // 通配符槽：只走本类自己的账本（阶段二/三都还没有 InternalSlot 缓存，
            // 这里只做「失效闸缓存 + 日志 + 让终端重读」），绝不进父类数组。
            this.shanhaiInvalidateGateCache();
            this.shanhaiOnWildcardSlotChanged(slot - ShanhaiWildcardLayout.MOTHER_OFFSET);
            return;
        }
        super.onPatternChange(slot);
        this.shanhaiInvalidateGateCache();
    }

    /** AE 终端看到的样板库存 = 合并视图（见 {@link CombinedTerminalPatternInventory}）。 */
    @Override
    public InternalInventory getTerminalPatternInventory() {
        return this.combinedTerminalPatternInventory;
    }

    // ================================================================ 阶段三 第 2 项：① 防串配方（零 mixin）

    /**
     * <b>零 mixin 的闸</b>：在 AE 看到「这台机器有哪些样板」的那一步做过滤。
     *
     * <h2>为什么这条路是零注入（关键证据）</h2>
     * AE 的 {@code PatternContainer#getAvailablePatterns()} 是<b>父类实现的一个普通 public 方法</b>
     * （{@code MEPatternBufferPartMachine.getAvailablePatterns()} = {@code patternSlotMap.keySet()}，
     * {@code javap -c} 偏移 0–19），我们<b>在自己类里覆写它</b>就能改变 AE 拿到的清单 ——
     * 全程没有 mixin、没有反射，也没碰任何别人的类。
     * <br>旁证：<b>上游那台机器自己也是这么干的</b>（{@code upstream-RecipeTypePatternBufferPartMachine.java:1823-1826}
     * 在同一方法里把通配符展开出来的样板 {@code combined.addAll(wildcardPatterns)}）
     * ⇒ 这就是「控制 AE 看见什么」的官方缝。
     *
     * <h2>闸规则（与 {@link #blockAmbiguousPatterns} 一致）</h2>
     * <pre>
     *   UNIQUE   ⇒ 放行
     *   NONE     ⇒ 放行（未标注 ≠ 歧义；绝大多数样板没有类型键）
     *   AMBIGUOUS⇒ 拦截（并打一条独立 WARN，见 {#shanhaiWarnAmbiguous}）
     * </pre>
     * ⚠️ 被拦下的样板<b>不是「悄悄消失」</b>：它有专门的 WARN 行（槽号 + 候选），
     * 且 {@link #snapshotPatternTypes()} 里能查到它属于 {@code ambiguousSlots}。
     *
     * <h2>性能</h2>
     * 这个方法在 AE 算料期会被频繁调用 ⇒ 用 {@link #gateBlockedSlots} 缓存，
     * 只在「样板变了 / 手填类型变了 / 载入」时置脏（{@link #shanhaiInvalidateGateCache()}）。
     */
    @Override
    public List<IPatternDetails> getAvailablePatterns() {
        return this.shanhaiApplyForgeRewrite(this.shanhaiAppendWildcardExpansions(this.shanhaiGatedPatterns()));
    }

    /** 阶段三那道闸（只拦歧义）；抽成独立方法，好让阶段四的神锻改写叠在它之后。 */
    private List<IPatternDetails> shanhaiGatedPatterns() {
        final List<IPatternDetails> all = super.getAvailablePatterns();
        if (!this.blockAmbiguousPatterns || all.isEmpty()) {
            if (!this.blockAmbiguousPatterns) {
                // 闸关时计数归零（GUI/Jade 不能显示「上闸时的旧数」）
                this.shanhaiUntypedPatternCount = 0;
                this.shanhaiAmbiguousPatternCount = 0;
                this.shanhaiUntypedBlockedSlots = List.of();
                this.shanhaiAmbiguousBlockedSlots = List.of();
            }
            return all;
        }
        this.shanhaiRefreshGateCache();
        if (this.gateBlockedSlots.isEmpty()) {
            return all;
        }
        final List<IPatternDetails> kept = new ArrayList<>(all.size());
        for (IPatternDetails pattern : all) {
            final Integer slot = super.getSlotIndexForPattern(pattern);
            if (slot != null && this.gateBlockedSlots.contains(slot)) {
                continue;
            }
            kept.add(pattern);
        }
        // 拦截必须看得见：两类各一条 WARN（带槽号）；GUI/Jade 另读那两个 @DescSynced 计数
        this.shanhaiWarnBlocked(this.shanhaiUntypedBlockedSlots, this.shanhaiAmbiguousBlockedSlots);
        return kept;
    }

    // ================================================================ 阶段四 第 1 项：④ 神锻样板模式

    /**
     * <b>神锻改写层</b>：把闸放行的样板再过一道「只改对 AE 声明的产出」。
     *
     * <h2>🔴 为什么这条路是可行的（`pushPattern` 调用链的字节码结论）</h2>
     * {@code MEPatternBufferPartMachineBase.pushPattern(pattern, stacks)} 全body只有 4 步（{@code javap -c} 偏移 0–57）：
     * <pre>
     *   0: getMainNode().isActive()            // 网格在不在
     *  16: getSlotIndexForPattern(pattern)     // ← **唯一**一次「这是哪一格」
     *  20: if (slot == null || slot &lt; 0 || slot &gt;= getInternalSlotCount()) return false;
     *  47: getInternalSlot(slot).pushPattern(stacks);  return true;
     * </pre>
     * ⇒ AE 把「它从 {@code getAvailablePatterns()} 拿到的那个对象」原样传回来时，
     * 父类只需要能用它反查到槽号。<b>而 {@code getSlotIndexForPattern} 是 protected、可覆写、且不在
     * gtladditions 的 @Overwrite 名单里</b> ⇒ 我们只要「先查父表、查不到再查改写表」就能让改写样板正常推料。
     * <b>不需要</b>改 pushPattern 的其它任何一跳。
     *
     * <h2>反向映射的键为什么必须是「改写后的对象本身」</h2>
     * AE 持有的是我们返回的那份实例；`MEBufferPatternHelper` 每次改写都会 new 一份新的 {@code IPatternDetails}
     * ⇒ 只能用 <b>identity</b> 语义的 map（{@code HashMap} 默认 equals，但 {@code IPatternDetails} 的实现
     * 没有覆写 equals 的话就是 identity；为稳妥，本类用 {@code java.util.IdentityHashMap}）。
     */
    private List<IPatternDetails> shanhaiApplyForgeRewrite(@NotNull List<IPatternDetails> gated) {
        if (!this.forgePatternMode || gated.isEmpty() || !ShanhaiForgePatternMode.isAvailable()) {
            if (!this.forgePatternSnapshot.isEmpty()) {
                this.forgePatternSnapshot.clear();
                this.rewrittenToSlot.clear();
            }
            return gated;
        }
        if (this.forgeCacheDirty) {
            this.forgeCacheDirty = false;
            this.forgePatternSnapshot.clear();
            this.rewrittenToSlot.clear();
            final net.minecraft.world.level.Level level = this.getLevel();
            for (IPatternDetails pattern : gated) {
                IPatternDetails rewritten = null;
                try {
                    // 🔴 2026-10-24：这里用【生效倍率】而不是手填值 —— 自动倍率开着时就是控制器读数，
                    //    关着时等于手填值（getEffectiveForgePatternMultiplier 的语义，见方法注释）。
                    rewritten = ShanhaiForgePatternMode.rewrite(pattern, level, true, this.getEffectiveForgePatternMultiplier());
                } catch (Throwable t) {
                    // 改写失败 ⇒ 该样板按原样放行（绝不因为改写失败就把样板吞掉）
                    ShanhaiStellarLog.warn("神锻改写抛异常（该样板按原样放行）：{}", String.valueOf(t));
                }
                if (rewritten != null && rewritten != pattern) {
                    final Integer slot = super.getSlotIndexForPattern(pattern);
                    if (slot != null) {
                        this.rewrittenToSlot.put(rewritten, slot);
                        this.forgePatternSnapshot.add(rewritten);
                        continue;
                    }
                }
                this.forgePatternSnapshot.add(pattern);
            }
            ShanhaiStellarLog.type("神锻改写重建：gated={} rewritten={}", gated.size(), this.rewrittenToSlot.size());
        }
        return List.copyOf(this.forgePatternSnapshot);
    }

    /**
     * 🔴 反向查槽：<b>父表优先</b>（普通样板一字不差走老路），查不到再查神锻改写表。
     *
     * <p>这一处覆写让 {@code pushPattern}（偏移 16 那一次调用）对「改写后的样板」也能拿到原槽号，
     * 于是推料路径不需要任何其它改动。写成「父表优先」也保证了<b>改写关闭时行为与阶段三完全一致</b>。
     */
    @Override
    protected Integer getSlotIndexForPattern(IPatternDetails pattern) {
        final Integer direct = super.getSlotIndexForPattern(pattern);
        if (direct != null) {
            return direct;
        }
        if (this.expandCacheDirty) {
            this.shanhaiRebuildExpansions();
        }
        final Integer expanded = this.expandedToSlot.get(pattern);
        if (expanded != null) {
            return expanded;
        }
        return this.rewrittenToSlot.get(pattern);
    }

    /**
     * 神锻倍率（1..30，默认 15；越界自动夹住）。
     *
     * <p>{@code @Override} 自 {@link AutoForgeMultiplierHost}（2026-10-24）：上游面板里的
     * {@code IntInputWidget} 直接绑这个 setter。里面转调既有的
     * {@link #shanhaiSetForgeMultiplierFromUi(int)}（夹范围 + 置脏 + 清闸缓存 + 日志）——
     * <b>逻辑只有一份</b>，不因为多了一个调用方就分叉出第二条路。
     * <p>⚠️ 那个方法带 {@code isRemote()} 守卫 ⇒ 客户端这一侧不写字段（面板上的框靠
     * {@code @DescSynced} 的 {@code forgePatternMultiplier} 同步刷新，与上游那块同一个口径）。
     */
    @Override
    public void setForgePatternMultiplier(int multiplier) {
        this.shanhaiSetForgeMultiplierFromUi(multiplier);
    }

    @Override
    public int getForgePatternMultiplier() {
        return this.forgePatternMultiplier;
    }

    public boolean isForgePatternMode() {
        return this.forgePatternMode;
    }

    // ================================================================ AutoForgeMultiplierHost（17 个方法）

    /**
     * <b>「神锻样板模式」面板的宿主实现</b>（{@link AutoForgeMultiplierHost}，2026-10-24）。
     *
     * <h2>为什么走接口，而不是在本类里自己摆面板</h2>
     * 用户原话：「我要和超级样板总成的那个一模一样，不要你这乱搞」。
     * 上游那台「超级样板总成」与本工程那台超级通配符仓室用的都是
     * {@link AutoForgeMultiplierPanel#buildFull}（<b>唯一装配实现</b>）⇒ 想「一模一样」，
     * 就不能只抄坐标：<b>必须真的调它</b>。接口把「面板要读写的那几件事」抽出来，
     * 判定/文案/限流日志仍然只有一份（住在 {@link ShanhaiAutoForgeMultiplierDriver} 里）。
     *
     * <h2>映射表（接口方法 → 本机既有实现，逐条可核对）</h2>
     * <pre>
     *   isForgePatternModeEnabled()        → {@link #isForgePatternMode()}（既有）
     *   setForgePatternModeEnabled(boolean)→ 写 {@code forgePatternMode} + 清两个缓存 +（服务端）一行日志
     *   getForgePatternMultiplier()        → {@code forgePatternMultiplier}（既有）
     *   setForgePatternMultiplier(int)     → {@link #shanhaiSetForgeMultiplierFromUi(int)}（既有）
     *   isAutoForgeMultiplierEnabled()     → <b>false</b>（本机没有自动倍率读取器，见下）
     *   setAutoForgeMultiplierEnabled(b)   → <b>空实现</b>（同上：不给假功能）
     *   shanhaiAutoForgeMultiplierRead()   → {@code ShanhaiAutoForgeMultiplier.NO_READ}
     *   shanhaiAutoForgeMultiplierNote()   → 没控制器 = {@code REASON_NO_CONTROLLER}；有控制器 = {@code REASON_NO_SOURCE}
     *   shanhaiStoreAutoForgeMultiplier(…) → 空实现（没有驱动 ⇒ 没有写点）
     *   shanhaiAutoForgeMultiplierText()   → 共享判定核 {@code describe(...)} 现算 ⇒ 真实倍率（「手动·倍率 N」）
     *   shanhaiAutoForgeMultiplierReasonText() → 「已安装于 &lt;控制器名&gt;」/「自动倍率不可用（…）」（真实状态）
     *   shanhaiAutoForgeMultiplierDriver() → <b>null</b>（本机不接驱动）
     *   shanhaiIsRemote()                  → {@code isRemote()}
     *   shanhaiControllers()               → {@code getControllers()}
     *   shanhaiAppliedForgeMultiplier()    → {@code forgePatternMultiplier}
     *   shanhaiApplyForgeMultiplier(int)   → {@link #shanhaiSetForgeMultiplierFromUi(int)}
     *   shanhaiLogTag()                    → {@code "SHANHAI-STELLAR"}
     * </pre>
     * ⚠️ 接口一共 <b>17</b> 个方法（任务书那 16 条里少了 §二 那三个同步字段方法
     * {@code shanhaiAutoForgeMultiplierRead / Note / Store}）—— 以接口源码为准，一个都没漏。
     *
     * <h2>🔴 为什么「自动倍率」在本机是死的（而不是抄一个能用的开关）</h2>
     * 自动倍率＝「读仓室所在多方块控制器上的额外产出倍率」。它需要
     * {@link ShanhaiAutoForgeMultiplierDriver}（探测 + 判定 + 文案 + 限流日志）＋
     * {@code ShanhaiForgeMultiplierReader}（伪神之锻炉 / 原始终焉引擎那几种来源）。
     * 本机是<b>样板仓室</b>，本轮<b>没有</b>把这两块接进来（驱动返回 null）⇒ 开关恒 false、
     * <b>绝不让一个点了没用的开关看起来是开着的</b>（本工程红线：活的界面上不许放假数据）。
     *
     * <p>🔴 <b>但「没接驱动」不等于「可以写死两句假话」</b>（2026-10-24 三修 ① 的教训）：
     * 改前那一版把状态行写死成「自动 · 读不到 -&gt; ×1」、原因行写死成「原因：未成型（无控制器）」，
     * 结果是一台正装在成型多方块里的机器也在喊「没有控制器」，玩家把倍率从 15 改到 7 那一行也纹丝不动
     * ⇒ <b>界面在说谎</b>。现在两行都按真实状态现算（倍率 = {@code forgePatternMultiplier}，
     * 装在哪 = 服务端算好并同步的控制器名）。
     */
    @Override
    public boolean isForgePatternModeEnabled() {
        return this.isForgePatternMode();
    }

    /**
     * 切换神锻样板模式（上游面板第①行那个开关的唯一入口）。
     *
     * <p>值没变就一个字节都不动（上游 {@code setForgePatternModeEnabled} 同款「变了才重算」）。
     * <p>⚠️ 客户端那次调用<b>不写日志</b>：LDLib 的开关在两侧都会触发，日志只留服务端那一行
     * （否则一次点击刷两条，看着像点了两下）。
     */
    @Override
    public void setForgePatternModeEnabled(boolean enabled) {
        if (this.forgePatternMode == enabled) {
            return;
        }
        this.forgePatternMode = enabled;
        this.forgeCacheDirty = true;
        this.shanhaiInvalidateGateCache();
        if (!this.isRemote()) {
            ShanhaiStellarLog.info("神锻样板模式 => {}（倍率 {}，上游实现可用={}）",
                    enabled ? "开" : "关", this.forgePatternMultiplier,
                    ShanhaiForgePatternMode.isAvailable());
        }
    }

    /**
     * 「自动倍率」开关状态 —— <b>真实字段</b>（{@link #autoForgeMultiplier}，默认<b>开</b>）。
     *
     * <p>🔴 2026-10-24 更正：上一轮这里恒 {@code false}（面板上那个开关拨不动、拨了下一帧弹回），
     * 注释还写成「本机没有读取器」。事实是<b>超级通配符那台同样能读</b>（同样 {@code getControllers()}），
     * 本轮照原样接过来 ⇒ 开关可点亮、可关闭，行为与那台一致。
     */
    @Override
    public boolean isAutoForgeMultiplierEnabled() {
        return this.autoForgeMultiplier;
    }

    /**
     * 切换「自动倍率」：<b>值真的变了才动手</b>（与 {@link #setForgePatternModeEnabled(boolean)} 同款）。
     *
     * <p>🔴 必须重算：关掉时要让<b>手填值立刻生效</b>；打开时要让<b>读到的值立刻生效</b>。
     * 走的是与「玩家手改倍率」完全相同的那条路径（{@code forgeCacheDirty} ⇒ 下次改写重算），不另开岔路。
     * <p>服务端那一侧打一行 {@code AUTOFSM} 判据（客户端那次不打，否则一次点击两行）。
     */
    @Override
    public void setAutoForgeMultiplierEnabled(boolean enabled) {
        if (this.autoForgeMultiplier == enabled) {
            return;
        }
        this.autoForgeMultiplier = enabled;
        this.forgeCacheDirty = true;
        this.shanhaiInvalidateGateCache();
        if (!this.isRemote()) {
            // 立刻复读一次：开 ⇒ 马上把控制器上的数读进来；关 ⇒ 读数清成 NO_READ（显示「手动」）
            this.shanhaiAutoForgeMultiplierDriver.refresh();
            this.shanhaiLogAutoFsm("toggle");
        }
    }

    /** 最近一次读到的倍率：{@link ShanhaiAutoForgeMultiplier#NO_READ} = 没读到（自动关时恒为它）。 */
    @Override
    public int shanhaiAutoForgeMultiplierRead() {
        return this.shanhaiAutoForgeMultiplierRead;
    }

    /**
     * 读数标注：读到时是<b>来源</b>（{@code SOURCE_*}），读不到时是<b>原因</b>（{@code REASON_*}）。
     *
     * <p>🔴 唯一写点是驱动（{@link ShanhaiAutoForgeMultiplierDriver#refresh()} 经
     * {@link #shanhaiStoreAutoForgeMultiplier(int, String)}），本方法<b>只读字段</b> ——
     * 面板第二行直接读它，界面说的和算法用的因此是同一份。
     */
    @Override
    public @NotNull String shanhaiAutoForgeMultiplierNote() {
        final String note = this.shanhaiAutoForgeMultiplierNote;
        return note == null ? ShanhaiAutoForgeMultiplier.REASON_AUTO_OFF : note;
    }

    /** 把「读数 + 标注」写进同步字段 —— <b>驱动是唯一写点</b>（与超级通配符那台逐字同构）。 */
    @Override
    public void shanhaiStoreAutoForgeMultiplier(int read, String note) {
        this.shanhaiAutoForgeMultiplierRead = read;
        this.shanhaiAutoForgeMultiplierNote =
                note == null ? ShanhaiAutoForgeMultiplier.REASON_AUTO_OFF : note;
    }

    /**
     * 🔴 <b>生效倍率 —— 全机唯一的口径</b>（与超级通配符那台同一个式子、同一份判定核）。
     *
     * <pre>
     *   {@link #getForgePatternMultiplier()}   = 【手填值】（输入框里那个数；语义一个字没改）
     *   getEffectiveForgePatternMultiplier()   = 【真正传给 rewrite(...) 的那个数】
     *       自动开 ⇒ 读到多少就是多少；读不到 ⇒ ×1
     *       自动关 ⇒ 手填值
     * </pre>
     *
     * <h2>谁在读它（说清，免得后来人接错线）</h2>
     * <ul>
     *   <li>{@link #shanhaiApplyForgeRewrite(List)} —— <b>唯一</b>把它交给
     *       {@code ShanhaiForgePatternMode.rewrite(...)} 的地方（= 算法侧）；</li>
     *   <li>面板那一行（经驱动的 {@code text()}，= 界面侧）；</li>
     *   <li>{@link ShanhaiAutoForgeMultiplierDriver#tick()}（= 变化检测）；
     *   <li>{@code AUTOFSM} 日志行（= 日志侧）。</li>
     * </ul>
     * ⇒ 界面说 23、日志说 23、算法用的就是 23，<b>不存在第二份真相</b>。
     */
    public int getEffectiveForgePatternMultiplier() {
        return ShanhaiAutoForgeMultiplier.effective(
                this.autoForgeMultiplier,
                this.shanhaiAutoForgeMultiplierRead,
                this.forgePatternMultiplier,
                ShanhaiForgePatternMode.MIN_MULTIPLIER,
                ShanhaiForgePatternMode.MAX_MULTIPLIER);
    }

    /**
     * <b>自动倍率的周期复读</b>（每 tick 调一次；驱动内部自带 100 tick 的探测间隔与变化检测）。
     *
     * <p>与超级通配符那台的 {@code shanhaiTickAutoForgeMultiplier()} 同义，唯一差别是
     * 「落地」那一跳在本机是「置脏神锻改写缓存」而不是 {@code rebuildPatterns()}
     * （见 {@link #shanhaiApplyForgeMultiplier(int)}）。
     */
    private void shanhaiTickAutoForgeMultiplier() {
        this.shanhaiAutoForgeMultiplierDriver.tick();
        this.shanhaiLogAutoFsm("tick");
    }

    /**
     * <b>AUTOFSM 判据</b>（一次状态变化一行；稳定时最多 200 tick 一行）：
     *
     * <pre>
     *   AUTOFSM enabled=&lt;true|false&gt; source=&lt;computed|manual&gt; value=&lt;N&gt; why=&lt;来源/原因|auto-off&gt;
     *           read=&lt;读到的数|none&gt; manual=&lt;手填值&gt;（规则：自动开 ⇒ 生效倍率 = 控制器/模块读数，
     *           读不到 ⇒ ×1；自动关 ⇒ 手填值；只有 value 变化时才落地一次）
     * </pre>
     *
     * <h2>怎么判（下次进游戏 grep 这一行）</h2>
     * <ul>
     *   <li>{@code enabled=true source=computed value=N} ⇒ 自动倍率<b>生效中</b>，
     *       神锻改写用的就是这个 N（{@code read=N} 说明是从控制器真读到的；{@code read=none} ⇒ 按 ×1）；</li>
     *   <li>{@code enabled=false source=manual value=N} ⇒ 自动关，用的是手填值 N；</li>
     *   <li><b>一行都没有</b> ⇒ 这台仓室的 {@code update()} 没跑到（没通电/没成型），
     *       或者服务端没加载到本机。</li>
     * </ul>
     */
    private void shanhaiLogAutoFsm(@NotNull String where) {
        if (this.isRemote() || this.getLevel() == null) {
            return;
        }
        final int read = this.shanhaiAutoForgeMultiplierRead;
        final int manual = ShanhaiAutoForgeMultiplier.clamp(this.forgePatternMultiplier,
                ShanhaiForgePatternMode.MIN_MULTIPLIER, ShanhaiForgePatternMode.MAX_MULTIPLIER);
        final int value = this.getEffectiveForgePatternMultiplier();
        final boolean enabled = this.autoForgeMultiplier;
        final String source = enabled ? "computed" : "manual";
        final String why = enabled
                ? (read == ShanhaiAutoForgeMultiplier.NO_READ ? this.shanhaiAutoForgeMultiplierNote() : this.shanhaiAutoForgeMultiplierNote())
                : "auto-off";
        final String state = enabled + "|" + source + "|" + value + "|" + why + "|" + read;
        final long now = this.getLevel().getGameTime();
        if (state.equals(this.shanhaiAutoFsmLogState) && this.shanhaiAutoFsmLogTick > 0L
                && now - this.shanhaiAutoFsmLogTick < 200L) {
            return;
        }
        this.shanhaiAutoFsmLogState = state;
        this.shanhaiAutoFsmLogTick = now;
        ShanhaiStellarLog.info("AUTOFSM enabled={} source={} value={} why={} read={} manual={} where={}"
                        + "（规则：自动开 ⇒ 生效倍率 = 控制器/模块读数，读不到 ⇒ ×1；"
                        + "自动关 ⇒ 手填值；只有 value 变化时才落地一次）",
                Boolean.valueOf(enabled), source, Integer.valueOf(value), why,
                read == ShanhaiAutoForgeMultiplier.NO_READ ? "none" : String.valueOf(read),
                Integer.valueOf(manual), where);
    }

    /**
     * 面板那一行（<b>手填 N / 读到 N / 读不到 -&gt; ×1</b>）—— <b>唯一实现在驱动里</b>。
     *
     * <p>🔴 2026-10-24：上一轮这里是本类自己拼的 {@code describe(false, NO_READ, 手填值, …)}，
     * 也就是<b>把「自动恒关」写死</b>。本轮改回「转调驱动」——与「超级通配符 ME 样板总成」
     * 那台<b>同一个调用</b>（{@code shanhaiAutoForgeMultiplierDriver().text()}），
     * 于是两台机器这块文案只有一份实现（用户 2026-10-04 的硬要求）。
     *
     * <p>⚠️ 面板每帧读（传的是方法引用）⇒ 必须现算，不许缓存。
     */
    @Override
    public String shanhaiAutoForgeMultiplierText() {
        return this.shanhaiAutoForgeMultiplierDriver.text();
    }

    /**
     * 面板第四行（来源 / 原因）—— <b>按真实状态出，不再写死「未成型（无控制器）」</b>。
     *
     * <h2>🔴 改前的 bug（用户截图点名的那一条）</h2>
     * 无论这台仓室装在哪，都渲染「原因：未成型（无控制器）」⇒ 一台正装在成型多方块里的机器
     * 也在喊「没有控制器」= 假话。
     *
     * <h2>改后（两条真话，二选一）</h2>
     * <pre>
     *   拿得到控制器 ⇒ 「已安装于 &lt;控制器/结构名&gt;」    （名字 = 控制器方块自己的本地化显示名）
     *   拿不到       ⇒ 「自动倍率不可用（仓室读不到控制器，可手填倍率）」
     * </pre>
     * 🔴 <b>数据源是服务端算好的同步字段</b> {@link #shanhaiForgeHostName}，<b>不是</b>在客户端调
     * {@code getControllers()}（客户端那份一般是空的，见该字段注释）—— 否则这一行会「永远是第一档」，
     * 正是改前那个 bug 的另一种写法。
     */
    @Override
    public String shanhaiAutoForgeMultiplierReasonText() {
        final String host = this.shanhaiForgeHostName;
        if (host == null || host.isBlank()) {
            return Component.translatable(LANG_FORGE_AUTO_UNAVAILABLE).getString();
        }
        return Component.translatable(LANG_FORGE_INSTALLED, host).getString();
    }

    /**
     * 重算「本仓室装在哪」（<b>只在该方法内写 {@link #shanhaiForgeHostName}</b>）。
     *
     * <p>服务端调用点两处：① {@code createUIWidget}（开界面那一刻，玩家立刻看到对的字）；
     * ② {@link #shanhaiSyncStock()}（周期性自愈：结构成型/拆掉、控制器被替换都会在一个同步周期内反映）。
     * <p>客户端调用直接返回 {@code false}（一个字都不动）。
     *
     * @return 这次有没有改动字段（改了就返回 true，调用方可以据此打日志）
     */
    private boolean shanhaiRefreshForgeHostName() {
        if (this.isRemote()) {
            return false;
        }
        final String name = this.shanhaiComputeForgeHostName();
        if (name.equals(this.shanhaiForgeHostName)) {
            return false;
        }
        this.shanhaiForgeHostName = name;
        return true;
    }

    /** 控制器列表 → 一行显示名（空串 = 没有控制器）。服务端专用（客户端那份列表空）。 */
    private @NotNull String shanhaiComputeForgeHostName() {
        final List<IMultiController> controllers = this.getControllers();
        if (controllers == null || controllers.isEmpty()) {
            return "";
        }
        for (IMultiController controller : controllers) {
            if (controller == null) {
                continue;
            }
            return shanhaiControllerDisplayName(controller);
        }
        return "";
    }

    /**
     * 控制器的显示名：优先取它<b>方块自己的本地化名字</b>（= 玩家在 JEI/物品栏里看到的那台机器的名字），
     * 取不到再退<b>类简名</b>。
     *
     * <p>⚠️ 任何一步都不许抛：这条链跑在界面文案里，一个异常会让整块面板画不出来；
     * 而「名字退回类简名」只是显示层的轻微降级（本工程口径：显示可以退化，加载/界面不可以崩）。
     */
    private static @NotNull String shanhaiControllerDisplayName(@NotNull IMultiController controller) {
        try {
            final net.minecraft.world.level.block.state.BlockState state = controller.self().getBlockState();
            if (state != null) {
                final String name = state.getBlock().getName().getString();
                if (name != null && !name.isBlank()) {
                    return name;
                }
            }
        } catch (Throwable ignored) {
            // 落到下面那行
        }
        return controller.getClass().getSimpleName();
    }

    /**
     * 本机那份驱动 —— <b>真实对象</b>（与「超级通配符 ME 样板总成」那台同一个实现）。
     *
     * <p>🔴 2026-10-24 更正：上一轮这里是 {@code return null}，注释还写成「本机没有接自动倍率读取」——
     * 那是**桩**，不是上游限制。{@link ShanhaiAutoForgeMultiplierDriver} 内部判定/文案/限流日志
     * 与超级通配符那台<b>逐字节同一份</b>，机器差异全部由本类实现的
     * {@link AutoForgeMultiplierHost} 那几处吸收（控制器列表 / 生效值 / 落地方式 / 日志前缀）。
     */
    @Override
    public ShanhaiAutoForgeMultiplierDriver shanhaiAutoForgeMultiplierDriver() {
        return this.shanhaiAutoForgeMultiplierDriver;
    }

    /** 客户端侧还是服务端侧（接口要这个方法是为了让共享逻辑能判断「这次调用该不该动真格」）。 */
    @Override
    public boolean shanhaiIsRemote() {
        return this.isRemote();
    }

    /**
     * 本仓室所在多方块的控制器列表。
     *
     * <p>{@code MultiblockPartMachine#getControllers()} 的签名是 {@code List<IMultiController>}
     * ⇒ 对上接口的 {@code List<?>}（协变）。驱动从这里拿控制器去读「额外产出倍率」
     * （{@code ShanhaiForgeMultiplierReader}：伪神之锻炉 → 原始终焉引擎 → 伪神之锻炉模块 → 原始终焉引擎模块）。
     */
    @Override
    public List<?> shanhaiControllers() {
        return this.getControllers();
    }

    /** 当前已经落地的生效倍率 —— 本机就是神锻改写真正用的那个数（{@link #getEffectiveForgePatternMultiplier()}）。 */
    @Override
    public int shanhaiAppliedForgeMultiplier() {
        return this.getEffectiveForgePatternMultiplier();
    }

    /**
     * 把生效倍率落到机器上 —— 本机没有 {@code rebuildPatterns()}，等价的落地点是
     * 「<b>让神锻改写缓存失效</b>」：{@code forgeCacheDirty = true} 之后，
     * 下一次 {@link #shanhaiApplyForgeRewrite(List)} 会用新的生效倍率重算全部样板
     * （那条路径与「玩家手改倍率」完全相同，见 {@link #shanhaiSetForgeMultiplierFromUi(int)}）。
     *
     * <p>⚠️ 入参只用于语义完整（接口要求带值）；真正被读的是同步字段里的读数 ——
     * 与超级通配符那台「{@code rebuildPatterns()} 按已同步读数重算、入参只用于日志」同构。
     */
    @Override
    public void shanhaiApplyForgeMultiplier(int multiplier) {
        this.forgeCacheDirty = true;
        this.shanhaiInvalidateGateCache();
    }

    /** 日志前缀（与 {@link ShanhaiStellarLog} 的前缀同一个串，grep 一次看全）。 */
    @Override
    public String shanhaiLogTag() {
        return "SHANHAI-STELLAR";
    }

    // ================================================================ 阶段四 第 3 项：⑤ 界面标记 + 开关

    /** 角标：歧义 = 红点；其余（含未标注）= 透明。 */
    private static final IGuiTexture MARKER_AMBIGUOUS = shanhaiRect(0xFFFF3030);

    private static final IGuiTexture MARKER_NONE = shanhaiRect(0x00000000);

    private static IGuiTexture shanhaiRect(int argb) {
        final ColorRectTexture texture = new ColorRectTexture();
        texture.color = argb;
        return texture;
    }

    /**
     * 某一格通配符槽要画的角标。
     *
     * <p>⚠️ 只标<b>歧义</b>（红）—— <b>未标注是绝大多数样板的常态</b>（没人写类型键就是未标注），
     * 全标等于整屏飘红、等于没标。若确实要标未标注，把这里的判断加一条即可（一行）。
     */
    private IGuiTexture shanhaiWildcardMarker(int localSlot) {
        final long bit = 1L << localSlot;
        return (this.wildcardAmbiguousMask & bit) != 0L ? MARKER_AMBIGUOUS : MARKER_NONE;
    }

    /** 重算 50 格的歧义位掩码（服务端算，`@DescSynced` 同步给客户端画角标）。 */
    private void shanhaiRefreshWildcardMask() {
        long mask = 0L;
        for (int i = 0; i < WILDCARD_SLOT_COUNT; i++) {
            final ItemStack stack = this.wildcardPatternInventory.getStackInSlot(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (ShanhaiPatternTypeReader.resolve(stack).verdict() == ShanhaiPatternTypeReader.Verdict.AMBIGUOUS) {
                mask |= (1L << i);
            }
        }
        this.wildcardAmbiguousMask = mask;
    }

    // 阶段七：原来这里的两个「闸:开/关」「锻:开/关」文字按钮已删除 —— 它们现在由左侧竖排的图标按钮
    // 呈现（{@link #shanhaiToggleButton}），文案走 lang 键，tooltip 每帧按真实字段重算。
    // 语义（{@link #blockAmbiguousPatterns} / {@link #forgePatternMode} 的两个 toggle 方法）一个字没动。
    //
    // 2026-10-24：神锻那个 toggle 方法（shanhaiToggleForge）已删除 —— 它的开关现在由上游面板的
    //   ToggleButtonWidget 驱动，走 AutoForgeMultiplierHost#setForgePatternModeEnabled（同一份副作用：
    //   写字段 + forgeCacheDirty + 清闸缓存 + 服务端一行日志）。留着旧方法就是第二份实现。

    /** 闸开关（服务端执行；客户端那次本地执行被守卫掉，与中键同款）。 */
    private void shanhaiToggleGate() {
        if (this.isRemote()) {
            return;
        }
        this.blockAmbiguousPatterns = !this.blockAmbiguousPatterns;
        this.shanhaiInvalidateGateCache();
        ShanhaiStellarLog.info("闸（拦歧义样板）=> {}", this.blockAmbiguousPatterns ? "开" : "关");
    }

    /** 闸缓存置脏（样板变更 / 手填类型变更 / 载入存档 都会走到）。 */
    private void shanhaiInvalidateGateCache() {
        this.gateCacheDirty = true;
        this.expandCacheDirty = true;
        // 样板变了 ⇒「哪些槽可以靠库存供料」的快照也要重算（它按 hasPatternInSlot 选槽）
        this.shanhaiMarkStockSupplyDirty();
    }

    /**
     * 重算闸缓存：扫「有样板」的槽，只把<b>歧义</b>的槽号收进来。
     *
     * <p>⚠️ 槽号空间：本类的 {@code InternalSlot} 下标与「对外 API 槽号」在此处<b>重合</b>
     * （0..1079 普通、1080..1129 通配符）—— 所以 {@link #patternStackOf(int)} 可以直接吃内部下标。
     */
    private void shanhaiRefreshGateCache() {
        if (!this.gateCacheDirty) {
            return;
        }
        this.gateCacheDirty = false;
        this.gateBlockedSlots.clear();
        final List<Integer> untyped = new ArrayList<>();
        final List<Integer> ambiguous = new ArrayList<>();
        final java.util.BitSet patternAmbiguous = new java.util.BitSet(ShanhaiWildcardLayout.PATTERN_SLOTS);
        final java.util.BitSet patternUntyped = new java.util.BitSet(ShanhaiWildcardLayout.PATTERN_SLOTS);
        final int count = this.getInternalSlotCount();
        for (int slot = 0; slot < count; slot++) {
            if (!this.hasPatternInSlot(slot)) {
                continue;
            }
            final ItemStack stack = this.patternStackOf(slot);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            final ShanhaiPatternTypeReader.Verdict verdict =
                    ShanhaiPatternTypeReader.resolve(stack).verdict();
            // 🔴 闸的唯一判定点（阶段七：NONE 与 AMBIGUOUS 都拦；UNIQUE 放行；闸关时全放行）
            if (!ShanhaiPatternTypeReader.gateBlocks(verdict, this.blockAmbiguousPatterns)) {
                continue;
            }
            this.gateBlockedSlots.add(slot);
            if (verdict == ShanhaiPatternTypeReader.Verdict.AMBIGUOUS) {
                ambiguous.add(slot);
                if (ShanhaiWildcardLayout.isPatternSlot(slot)) {
                    patternAmbiguous.set(slot);
                }
            } else {
                untyped.add(slot);
                if (ShanhaiWildcardLayout.isPatternSlot(slot)) {
                    patternUntyped.set(slot);
                }
            }
        }
        this.patternAmbiguousMaskHex = ShanhaiWildcardLayout.toHexMask(patternAmbiguous.toLongArray());
        // 阶段九：同一拍重建「槽 → 样板」反表（虚拟电路要用；与闸共用脏标记，不会每 tick 扫）
        this.shanhaiPatternBySlot.clear();
        for (IPatternDetails pattern : super.getAvailablePatterns()) {
            final Integer mapped = super.getSlotIndexForPattern(pattern);
            if (mapped != null && ShanhaiWildcardLayout.isPatternSlot(mapped)) {
                this.shanhaiPatternBySlot.putIfAbsent(mapped, pattern);
            }
        }
        this.shanhaiUntypedBlockedSlots = untyped;
        this.shanhaiAmbiguousBlockedSlots = ambiguous;
        this.shanhaiUntypedPatternCount = untyped.size();
        this.shanhaiAmbiguousPatternCount = ambiguous.size();
    }

    /** 当前被拦下的「未标注类型」槽数（@DescSynced，GUI/Jade 都读它）。 */
    public int shanhaiUntypedPatternCount() {
        return this.shanhaiUntypedPatternCount;
    }

    /** 当前被拦下的「歧义」槽数。 */
    public int shanhaiAmbiguousPatternCount() {
        return this.shanhaiAmbiguousPatternCount;
    }

    /**
     * 被拦下就得<b>看得出来</b>（阶段七要求：不许静默）。两类各一条 WARN，<b>都带槽号</b>。
     *
     * <p>节流：槽号集合指纹相同 + 距上次 &lt;200 tick（≈10 秒）就不重复打，
     * 但<b>集合一变就立刻打</b>（新出现的未标注/歧义不许被压掉）。
     */
    private void shanhaiWarnBlocked(@NotNull List<Integer> untyped, @NotNull List<Integer> ambiguous) {
        if (untyped.isEmpty() && ambiguous.isEmpty()) {
            return;
        }
        final int hash = 31 * untyped.hashCode() + ambiguous.hashCode();
        final long now = this.getLevel() == null ? 0L : this.getLevel().getGameTime();
        if (hash == this.lastAmbiguousWarnHash && now - this.lastAmbiguousWarnTick < 200L) {
            return;
        }
        this.lastAmbiguousWarnHash = hash;
        this.lastAmbiguousWarnTick = now;
        if (!untyped.isEmpty()) {
            ShanhaiStellarLog.warn("配方类型【未标注 ⇒ 已拦截，不参与推送】：{} 个槽 {}（填了类型才会执行）",
                    untyped.size(), untyped);
        }
        if (!ambiguous.isEmpty()) {
            final List<String> candidates = new ArrayList<>();
            for (Integer slot : ambiguous) {
                candidates.add(slot + "→" + this.resolveRecipeTypeForSlot(slot).ids());
            }
            ShanhaiStellarLog.warn("配方类型【歧义 ⇒ 已拦截，不参与推送】：{} 个槽 {}",
                    ambiguous.size(), candidates);
        }
    }

    // ================================================================ 阶段三 第 3 项：③ 机器侧只读查询 API

    /**
     * <b>给控制器的只读 API</b>：这台机器上<b>有唯一类型</b>的样板一共涉及哪些配方类型。
     *
     * <p>🔴 语义（与需求「零类型与歧义都不得静默当默认类型跑」对齐）：
     * 返回集合里<b>只</b>有「四来源去重后恰好 1 个」的类型；
     * 未标注（NONE）与歧义（AMBIGUOUS）的槽<b>不进</b>这个集合 ——
     * 调用方因此拿不到任何「看起来像默认类型」的假值。
     * 要区分「未标注/歧义」请用 {@link #snapshotPatternTypes()}。
     */
    public @NotNull List<String> machineRecipeTypeIds() {
        return this.snapshotPatternTypes().machineTypeIds();
    }

    /**
     * 整机快照：类型集合 + 每槽类型 + 未标注槽 + 歧义槽（全部带槽号）。
     *
     * <p>实现是「本类扫槽 → 用纯函数 {@link ShanhaiPatternTypeReader#summarize} 聚合」，
     * 聚合规则因此可被无头探针实跑验证（{@code temp\stellar-p1\probe3\SnapshotProbe.java}）。
     */
    public @NotNull ShanhaiPatternTypeReader.Snapshot snapshotPatternTypes() {
        final List<ShanhaiPatternTypeReader.SlotType> slots = new ArrayList<>();
        final int count = this.getInternalSlotCount();
        for (int slot = 0; slot < count; slot++) {
            if (!this.hasPatternInSlot(slot)) {
                continue;
            }
            final ItemStack stack = this.patternStackOf(slot);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            final ShanhaiPatternTypeReader.Resolution resolution = ShanhaiPatternTypeReader.resolve(stack);
            slots.add(new ShanhaiPatternTypeReader.SlotType(slot, resolution.verdict(), resolution.ids()));
        }
        return ShanhaiPatternTypeReader.summarize(slots);
    }

    /**
     * 把整机快照打一行（受「类型日志开关」控制；命令/排查用）。
     *
     * <p>与 {@link #shanhaiDumpRecipeTypesToLog(String)} 的区别：那个是<b>逐槽</b>明细，
     * 这个是<b>一次聚合</b>，一行就能看出「有几块没标 / 有几块歧义 / 在哪几号槽」。
     */
    public void shanhaiDumpTypeSnapshot(@NotNull String where) {
        if (!ShanhaiStellarLog.isTypeLogEnabled()) {
            return;
        }
        ShanhaiStellarLog.type("SNAPSHOT where={} {}", where, this.snapshotPatternTypes().describe());
    }


    // ================================================================ 阶段五 第 2 项：普通 1080 格的角标

    /**
     * 普通格角标要读的槽号 = <b>页号 × 54 + 格内序号</b>。
     *
     * <h2>🔴 定位推演（父代理要求先给推演）</h2>
     * <pre>
     *   PaginationUIManager 的槽区子组固定在 (0, 16)，格距 18：
     *     格内序号 c ∈ [0,54) 的格坐标  x = 8 + (c % 9) * 18,  y = 16 + (c / 9) * 18   （javap 偏移 37–82 实证）
     *   而它给槽 c 绑定的容器下标 = 页号 * 54 + c（rebuildPatternSlots：from = page*rows*perRow，to = from+54）
     *   ⇒ 角标只要摆在同一个 (x,y)，并按 `page * 54 + c` 去查掩码，位置与数据就永远同源。
     * </pre>
     * ⚠️ 唯一的前提：**知道当前页号**。`PaginationUIManager.currentPageIndex` 是 private 且无 getter，
     * 所以本类改用 {@link RecordingPagination}（覆写它 public 的 {@link RecordingPagination#refreshPage(int)} 记录页号）。
     */
    private static int shanhaiPatternSlotAt(int page, int cell) {
        return page * PATTERN_SLOTS_PER_PAGE + cell;
    }

    /** 第 {@code apiSlot} 格要不要画歧义角标（读同步过来的 hex 掩码）。 */
    private IGuiTexture shanhaiPatternMarker(int apiSlot) {
        return ShanhaiWildcardLayout.maskBit(this.patternAmbiguousMaskHex, apiSlot) ? MARKER_AMBIGUOUS : MARKER_NONE;
    }

    /**
     * 只比父类多记一个「当前页号」的分页器。
     *
     * <p>父类 {@code currentPageIndex} 是 **private 且没有 getter**（{@code javap -p} 实证），
     * 而它的 {@code refreshPage(int)} 是 <b>public</b> ⇒ 覆写它记下页号即可，不改父类任何行为。
     */
    private static final class RecordingPagination extends PaginationUIManager {

        private int currentPage;

        private RecordingPagination(int patternsPerRow, int rowsPerPage, int maxPatternCount,
                                    int uiWidth, int uiHeight,
                                    java.util.function.IntConsumer onPatternChange,
                                    com.lowdragmc.lowdraglib.side.item.IItemTransfer patternInventory) {
            super(patternsPerRow, rowsPerPage, maxPatternCount, uiWidth, uiHeight, onPatternChange, patternInventory);
        }

        @Override
        public void refreshPage(int page) {
            this.currentPage = page;
            super.refreshPage(page);
        }
    }
    // ================================================================ 阶段五 第 3 项：通配符展开器（K=4）

    /** 展开槽 → 母槽号（越界即 bug，响亮失败）。 */
    private int shanhaiMotherOfExpandSlot(int expandSlot) {
        return ShanhaiWildcardLayout.motherOf(expandSlot);
    }

    /** 某个母槽当前展开出几条（0 = 没展开 / 没有样板）。 */
    private int shanhaiExpansionCountFor(int mother) {
        if (this.expandCacheDirty) {
            this.shanhaiRebuildExpansions();
        }
        final List<IPatternDetails> list = this.expansionsByMother.get(mother);
        return list == null ? 0 : list.size();
    }

    /**
     * 把展开出来的样板接在闸放行的清单后面。
     *
     * <p>顺序是刻意的：<b>闸 → 展开 → 神锻</b>。
     * 展开在闸之后 ⇒ 被闸拦掉的样板不会浪费展开成本；神锻在展开之后 ⇒ 展开出来的样板也能被改写
     * （否则「通配符 + 神锻」这个组合就是空功能）。
     */
    private List<IPatternDetails> shanhaiAppendWildcardExpansions(@NotNull List<IPatternDetails> gated) {
        if (this.expandCacheDirty) {
            this.shanhaiRebuildExpansions();
        }
        if (this.expansionsByMother.isEmpty()) {
            return gated;
        }
        int extra = 0;
        for (List<IPatternDetails> list : this.expansionsByMother.values()) {
            extra += list.size();
        }
        final List<IPatternDetails> out = new ArrayList<>(gated.size() + extra);
        out.addAll(gated);
        for (List<IPatternDetails> list : this.expansionsByMother.values()) {
            out.addAll(list);
        }
        return out;
    }

    /**
     * 重建展开表：逐母槽 {@code expandPatterns} → 截断到 K 条 → 登记「样板 → 展开槽号」。
     *
     * <p>🔴 三条风险的对策就写在这里：
     * <ol>
     *   <li><b>内存/存档 ×K</b>：展开槽是固定 200 个 InternalSlot（不随样板数增长），
     *       代价是「空槽也要占位」——这是刻意的：槽号必须固定不漂移（阶段三 ① 的结论）；</li>
     *   <li><b>遍历 GT 材料表不便宜</b>：只在 {@link #expandCacheDirty} 时重建，重建点只有
     *       「母槽内容变 / 手填类型变 / onLoad / 开关变」，<b>绝不每 tick 展开</b>；</li>
     *   <li><b>K 可调</b>：K 只在 {@link ShanhaiWildcardLayout#BRANCHES} 一处；改它会改槽号空间，
     *       必须同步改阶段三的证明与审计断言（该类注释 §三 已写明）。</li>
     * </ol>
     * <p>截断（一块母槽展开条数 &gt; K）⇒ **不静默丢**：记 WARN，写明母槽号 / 需要几条 / 只放几条。
     */
    private void shanhaiRebuildExpansions() {
        this.expandCacheDirty = false;
        this.expansionsByMother.clear();
        this.expandedToSlot.clear();
        final net.minecraft.world.level.Level level = this.getLevel();
        if (level == null) {
            return;   // 客户端 / 未加载：不展开（AE 只在服务端问 getAvailablePatterns）
        }
        int truncatedMothers = 0;
        final List<String> truncated = new ArrayList<>();
        for (int mother = 0; mother < ShanhaiWildcardLayout.MOTHER_COUNT; mother++) {
            final ItemStack stack = this.wildcardPatternInventory.getStackInSlot(mother);
            if (stack.isEmpty() || !ShanhaiWildcardCompat.isWildcardPattern(stack)) {
                continue;
            }
            final List<IPatternDetails> expanded = ShanhaiWildcardCompat.expandPatterns(stack, level);
            if (expanded.isEmpty()) {
                continue;
            }
            final List<IPatternDetails> kept = new ArrayList<>(ShanhaiWildcardLayout.BRANCHES);
            int branch = 0;
            for (IPatternDetails pattern : expanded) {
                if (branch >= ShanhaiWildcardLayout.BRANCHES) {
                    truncatedMothers++;
                    truncated.add(mother + "(" + expanded.size() + "条>K=" + ShanhaiWildcardLayout.BRANCHES + ")");
                    break;
                }
                this.expandedToSlot.put(pattern, ShanhaiWildcardLayout.expandSlot(mother, branch));
                kept.add(pattern);
                branch++;
            }
            if (!kept.isEmpty()) {
                this.expansionsByMother.put(mother, List.copyOf(kept));
            }
        }
        if (truncatedMothers > 0 && truncated.hashCode() != this.lastTruncationStamp) {
            this.lastTruncationStamp = truncated.hashCode();
            ShanhaiStellarLog.warn("通配符展开被截断（K={}）：{} 个母槽超限 {}", ShanhaiWildcardLayout.BRANCHES,
                    truncatedMothers, truncated);
        }
        ShanhaiStellarLog.type("展开表重建：母槽命中={} 展开条数={}", this.expansionsByMother.size(),
                this.expandedToSlot.size());
    }

    /**
     * <b>「展开后的各条各自扣自己的料」的取证接口</b>（阶段五判据②要用它）。
     *
     * <p>逐母槽列出：分支号 → 展开槽号 → 该槽 <b>自己的</b> itemInventory / fluidInventory 里现在有什么。
     * 只要两行出现在<b>不同的展开槽号</b>且各自只含自己的输入 ⇒ 就证明它们没有共用物料池。
     * <p>格式稳定、一行一个母槽（便于 grep）：
     * <pre>
     *   [SHANHAI-STELLAR] EXPAND mother=7 slot=1158 items={gtceu:iron_ingot=64} fluids={}
     * </pre>
     */
    public void shanhaiDumpExpansionLayout(@NotNull String where) {
        if (this.expandCacheDirty) {
            this.shanhaiRebuildExpansions();
        }
        ShanhaiStellarLog.info("展开布局（{}）：{}", where, ShanhaiWildcardLayout.describe());
        for (java.util.Map.Entry<Integer, List<IPatternDetails>> entry : this.expansionsByMother.entrySet()) {
            final int mother = entry.getKey();
            int branch = 0;
            for (IPatternDetails ignored : entry.getValue()) {
                final int slot = ShanhaiWildcardLayout.expandSlot(mother, branch);
                final MEPatternBufferPartMachineBase.InternalSlot internalSlot = this.getInternalSlot(slot);
                ShanhaiStellarLog.info("EXPAND mother={} branch={} slot={} items={} fluids={}",
                        mother, branch, slot,
                        String.valueOf(internalSlot.getItemInventory()),
                        String.valueOf(internalSlot.getFluidInventory()));
                branch++;
            }
        }
    }
    // ================================================================ 阶段九 ②：样板内虚拟电路

    /**
     * <b>这一格该用哪个电路</b>：样板里填了电路 ⇒ 用样板那条（覆盖共享电路，且不消耗任何物品）；
     * 样板没填 ⇒ 原样交回父类（父类的实现是「缓存里的电路 → 共享电路槽 → 空」，见 Stage 九报告 §2.1）。
     *
     * <p>🔴 <b>为什么这里不可能"消耗"电路</b>：本方法只<b>读</b>样板输入里的电路号并<b>构造</b>一个
     * {@code ItemStack} 返回；既不碰共享电路库存，也不碰 ME 网络（审计 G4 用字节码断言：
     * 本方法体里不许出现 extract/consume 类调用）。
     *
     * <p>方法名/签名来自 {@code MEPatternBufferPartMachineBase.getCircuitForRecipe(int)}（public、可覆写，
     * 不在 gtladditions 的 @Overwrite 名单里）。
     */
    @Override
    public ItemStack getCircuitForRecipe(int slot) {
        // C 刀 · A 段：通配符母槽（1080..1129）与它展开出来的槽（1130..1329）优先用「该母槽自己的电路」。
        // 语义与样板内虚拟电路同一套：读一个数 ⇒ **现造一份**返回（不碰任何库存，不可能被消耗）。
        final int mother = this.shanhaiWildcardMotherOfSlot(slot);
        if (mother >= 0) {
            final int wildcardCircuit = this.shanhaiWildcardCircuit(mother);
            if (wildcardCircuit != ShanhaiSlotCircuit.NONE) {
                return ShanhaiPatternCircuit.stackOf(wildcardCircuit);
            }
        }
        if (this.shanhaiPatternBySlot.isEmpty() && this.gateCacheDirty) {
            this.shanhaiRefreshGateCache();
        }
        final int fromPattern = ShanhaiPatternCircuit.circuitInPattern(this.shanhaiPatternBySlot.get(slot));
        final int fromSuper = ShanhaiPatternCircuit.circuitOfStack(super.getCircuitForRecipe(slot));
        final int resolved = ShanhaiPatternCircuit.resolve(fromPattern, fromSuper);
        if (resolved < 0) {
            return ItemStack.EMPTY;
        }
        if (resolved == fromPattern) {
            // 样板自带：**现造一份**（不引用任何库存里的那一个，也就不存在被消耗的可能）
            return ShanhaiPatternCircuit.stackOf(resolved);
        }
        return super.getCircuitForRecipe(slot);
    }

    /**
     * 槽号 → 它的<b>通配符母槽号</b>；不是通配符槽 ⇒ {@code -1}。
     *
     * <p>两种槽都归到同一个母槽：母槽本身（{@code 1080..1129}）与它展开出来的槽
     * （{@code EXPAND_OFFSET..SLOT_SPACE_TOTAL}，{@code ShanhaiWildcardLayout.motherOf} 反查）。
     */
    private int shanhaiWildcardMotherOfSlot(int slot) {
        if (slot >= WILDCARD_API_SLOT_OFFSET && slot < WILDCARD_API_SLOT_OFFSET + WILDCARD_SLOT_COUNT) {
            return slot - WILDCARD_API_SLOT_OFFSET;
        }
        if (slot >= ShanhaiWildcardLayout.EXPAND_OFFSET && slot < ShanhaiWildcardLayout.SLOT_SPACE_TOTAL) {
            return ShanhaiWildcardLayout.motherOf(slot);
        }
        return -1;
    }
    // ================================================================ 阶段六 ⑦：库存输入 160（8×2×10）

    /**
     * <b>⑦ 的核心：把「每个样板槽用哪份库存配置、扣谁的料」从父类那 32 格换成我们的 160 格。</b>
     *
     * <h2>🔴 为什么只能覆写 {@code createInternalSlot}（三处字节码事实）</h2>
     * <ol>
     *   <li>{@code StockingPatternBufferInternalSlot.isItemActive} 直接
     *       {@code getfield this$0.stockItemHandler}（{@code javap -c} 偏移 76/102 实证）⇒
     *       普通 0..1079 槽<b>永远只认父类那 32 格</b>，我们新造的 160 格对它们完全不可见；</li>
     *   <li>{@code hasActiveSlot()} / {@code performStockSync()} 是 <b>private</b>（{@code javap -p} 实证）
     *       ⇒ 父类不会替我们的 160 格做同步；</li>
     *   <li>而 {@code createInternalSlot} 是 {@code protected}、在<b>超类构造器</b>里就被调用
     *       （{@code MEPatternBufferPartMachine} 构造器循环 0..maxPatternCount-1）⇒
     *       覆写它就能替所有普通槽换掉「用哪份配置」，但<b>那一刻子类字段还是 null</b>。</li>
     * </ol>
     * ⇒ 本槽子类<b>构造期一个字段都不读</b>（构造函数只把 slot 号交给父类），
     * 全部字段读取都发生在 {@code isItemActive} / {@code handleItemInternal} 这些<b>使用期</b>方法里。
     *
     * <h2>🔴 为什么不是「照抄父类那 8 个方法」而是「父类优先 + 我们的补一层」</h2>
     * 逐个核过父类的 8 个方法（{@code javap -c} 全文见报告阶段六 §B.2）：
     * <ul>
     *   <li>{@code getLimitItemStackInput()} / {@code getItemStackInputMap()} 只是
     *       {@code super} 的结果 <b>+ 机器侧的 {@code appendStockItemContents} / {@code addStockItemMap}</b>
     *       ⇒ 覆写<b>机器侧那两个方法</b>即可，槽这边不用动；</li>
     *   <li>{@code isItemActive} / {@code handleItemInternal} 是唯一两处<b>直接读父类字段</b>的
     *       ⇒ 只覆写这两个，且都写成「父类成立 ⇒ 直接返回父类结果；父类不成立 ⇒ 再看我们」，
     *       这样<b>父类原有 32 格的行为逐字节不变</b>（阶段一~五的既有行为不回归）。</li>
     * </ul>
     */
    private final class ShanhaiStockingSlot extends MEStockingPatternBufferPartMachine.StockingPatternBufferInternalSlot {

        /**
         * @param slot 内部槽号（0..1079）
         *             <p>⚠️ 构造期只做一件事：把 slot 号交给父类。**不许读本类的任何字段**
         *             （此刻 {@code shanhaiStockItemHandler} 还没赋值 —— 父类构造器正在跑）。
         */
        private ShanhaiStockingSlot(int slot) {
            StellarSampleAssemblerPartMachine.this.super(slot);
        }

        /**
         * 活跃判定 = 父类判定 <b>或</b>（本槽有样板 且 我们的 160 格里有配置）。
         *
         * <p>与父类逐分支对照（{@code javap -c} 偏移 0–116）：父类的返回值为
         * {@code hasPatternInSlot(slot) && (若干催化剂/电路分支 || stockItemHandler.hasConfig())}。
         * 本覆写把最后那个 {@code hasConfig()} 换成「父类那份 或 我们那份」：
         * 当 {@code super} 已经为 true ⇒ 原样 true；为 false 时只可能在「没有样板」或
         * 「所有分支都空且没有配置」两种情形 ⇒ 前者我们也不该激活（故显式再查一次
         * {@code hasPatternInSlot}），后者才是我们要补的那种。
         */
        @Override
        public boolean isItemActive(boolean simulate) {
            StellarSampleAssemblerPartMachine.this.shanhaiProbeIsItemActive++;
            if (this.getSlotIndex() >= ShanhaiWildcardLayout.PATTERN_SLOTS) {
                StellarSampleAssemblerPartMachine.this.shanhaiProbeHiIsItemActive++;
            }
            if (super.isItemActive(simulate)) {
                return true;
            }
            return StellarSampleAssemblerPartMachine.this.hasPatternInSlot(this.getSlotIndex())
                    && StellarSampleAssemblerPartMachine.this.shanhaiHasStockItemConfig();
        }

        /**
         * 推料：父类成立走父类（32 格原样）；否则走「我们这层」的等价实现。
         *
         * <p>逐跳照抄 {@code StockingPatternBufferInternalSlot.handleItemInternal}
         * （{@code javap -c} 偏移 0–329，全文见报告阶段六 §B.2），<b>唯一区别</b>是
         * 第一跳的 {@code stockItemHandler.hasConfig()} 换成 {@code shanhaiHasStockItemConfig()}，
         * 而 {@code findStockItemKey} / {@code consumeStockItem} 会再走回父类（父类优先），
         * 所以「我们这层」既覆盖新 160 格，也覆盖父类那 32 格。
         */
        @Override
        public boolean handleItemInternal(Object2LongMap<Ingredient> map, int circuit, boolean simulate) {
            StellarSampleAssemblerPartMachine.this.shanhaiProbeHandleItem++;
            if (this.getSlotIndex() >= ShanhaiWildcardLayout.PATTERN_SLOTS) {
                StellarSampleAssemblerPartMachine.this.shanhaiProbeHiHandleItem++;
            }
            if (!StellarSampleAssemblerPartMachine.this.shanhaiHasStockItemConfig()) {
                return super.handleItemInternal(map, circuit, simulate);
            }
            if (map.isEmpty() && circuit < 0) {
                return true;
            }
            // 电路不匹配 ⇒ 这个槽不该跑（与父类同一判据：getCacheManager().getCircuitCache()）
            if (simulate && circuit > 0 && circuit != this.getCacheManager().getCircuitCache()) {
                return false;
            }
            final Object2LongOpenHashMap<AEItemKey> itemInventory = this.getItemInventory();
            final Object2LongMap<AEItemKey> catalysts = this.getItemCatalystInventory();
            for (Object2LongMap.Entry<Ingredient> entry : it.unimi.dsi.fastutil.objects.Object2LongMaps.fastIterable(map)) {
                final Ingredient ingredient = entry.getKey();
                final long amount = entry.getLongValue();
                if (amount <= 0) {
                    continue;
                }
                final AEItemKey key = StellarSampleAssemblerPartMachine.this
                        .findStockItemKey(ingredient, itemInventory, catalysts, amount, simulate);
                if (key == null) {
                    return false;
                }
            }
            if (!simulate) {
                final it.unimi.dsi.fastutil.objects.ObjectIterator<Object2LongMap.Entry<Ingredient>> iterator =
                        it.unimi.dsi.fastutil.objects.Object2LongMaps.fastIterator(map);
                while (iterator.hasNext()) {
                    final Object2LongMap.Entry<Ingredient> entry = iterator.next();
                    final Ingredient ingredient = entry.getKey();
                    final long amount = entry.getLongValue();
                    if (amount <= 0) {
                        iterator.remove();
                        continue;
                    }
                    final AEItemKey key = StellarSampleAssemblerPartMachine.this
                            .findStockItemKey(ingredient, itemInventory,
                                    it.unimi.dsi.fastutil.objects.Object2LongMaps.emptyMap(), amount, false);
                    if (key == null
                            || !StellarSampleAssemblerPartMachine.this.consumeStockItem(itemInventory, key, amount)) {
                        return false;
                    }
                    iterator.remove();
                }
            }
            return true;
        }
    }

    /**
     * 🔴 <b>本类唯一覆写的「造槽」方法</b>：<b>所有</b>槽都换成 {@link ShanhaiStockingSlot}。
     *
     * <p>⚠️ 它<b>在超类构造器里</b>就被调用 ⇒ 方法体只许读 {@code static} 常量与入参，
     * 不许读任何实例字段（那时它们全是 null / 0）。
     *
     * <h2>2026-10-24「库存供料放行」为什么把通配符母槽/展开槽也纳进来</h2>
     * 父类 {@code StockingPatternBufferInternalSlot#handleItemInternal} 的第一跳是
     * {@code this$0.stockItemHandler.hasConfig()}（{@code javap -c} 偏移 0–17）—— 它只认<b>父类那 32 格</b>。
     * 面板上配的是我们自己的 160 格 ⇒ 只要槽还是父类那个类，我们的库存配置对**通配符母槽/展开槽**
     * 就完全不可见（它们会被判成「没配库存」⇒ 直接退到基类实现 ⇒ 从不问 ME）。
     * 本类这层 {@code isItemActive} / {@code handleItemInternal} 把那个 {@code hasConfig()} 换成
     * {@link #shanhaiHasStockItemConfig()}（父类 32 格 <b>或</b> 我们 160 格），逻辑与父类逐跳等价
     * （见 {@link ShanhaiStockingSlot} 类注释）⇒ 对所有槽类型都成立。
     */
    @Override
    protected MEPatternBufferPartMachineBase.InternalSlot createInternalSlot(int slot) {
        return new ShanhaiStockingSlot(slot);
    }

    // ================================================================ 2026-10-24 二修：通配符段的 cacheRecipe 越界闸

    /**
     * <b>把「配方缓存位」这道闸对通配符段放到安全状态</b> —— 这是「通配符母槽/展开槽能不能进活跃名单」
     * 的<b>唯一前置条件</b>。
     *
     * <h2>🔴 为什么必须改这一处（三处字节码事实，全在 temp\stellar-p1\javap\）</h2>
     * <ol>
     *   <li>我们的活跃名单（{@code getActiveSlots()}）会被 {@code MEPatternRecipeHandlePart#handleRecipe}
     *       的兜底循环逐槽消费；循环里<b>先</b> {@code meHandleRecipe(...)}，成功<b>紧接着</b>就是
     *       偏移 378 的 {@code IMEPatternTrait#hasCacheInSlot(slot)}；</li>
     *   <li>上游那份实现的字节码是 <b>零检查</b>的直接下标读：
     *       {@code MEPatternBufferPartMachine$MEPatternTrait#hasCacheInSlot} 偏移 0–9 =
     *       {@code getfield this$0.cacheRecipe : [Z} → {@code iload_1} → {@code baload} → {@code ireturn}；</li>
     *   <li>而 {@code cacheRecipe} 的长度在<b>构造器里就写死了</b>：{@code MEPatternBufferPartMachine}
     *       构造器偏移 52–63 是
     *       <pre>
     *         52: aload_0
     *         53: iload_2          // 构造器第 2 个参数 maxPatternCount
     *         54: newarray boolean
     *         56: putfield hasPatternArray
     *         59: aload_0
     *         60: iload_2
     *         61: newarray boolean
     *         63: putfield cacheRecipe
     *       </pre>
     *       我们传的是 {@code PATTERN_SLOT_COUNT}（1080，见构造器 {@code super(holder, PATTERN_SLOT_COUNT, io)}）
     *       ⇒ {@code cacheRecipe.length == 1080}，而槽号空间是 1330
     *       （{@code ShanhaiWildcardLayout.SLOT_SPACE_TOTAL}）⇒ <b>槽号 >= 1080 直接 AIOOBE</b>。
     *       同一次 {@code newarray iload_2} 还建了 {@code hasPatternArray} / {@code internalInventory} /
     *       {@code catalystItems} / {@code catalystFluids} / {@code cacheRecipeCount} ——
     *       全是 1080，<b>所以「把数组撑到 1330」这条路不成立</b>（它们是 {@code protected final}，
     *       子类不能重新赋值；唯一能改的是把 1330 传给 super，那会让父类把 0..1329 全部再 new 一遍、
     *       {@code patternInventory} 也变 1330，和我们自己造的通配符槽重复 ⇒ 改动面远大于收益）。
     *       ⇒ <b>本轮走最小替代方案：只把「这一个下标读」改成有闸的，槽号空间的其余部分一个字不动。</b></li>
     * </ol>
     *
     * <h2>做法（为什么是「覆写 createMETrait」而不是别的）</h2>
     * {@code MEPatternRecipeHandlePart} 手里那个 {@code meTrait} 来自
     * {@code IMEPatternPartMachine#getMETrait()}，后者返回的就是 {@code MEIOPartMachine} 构造器里
     * （偏移 49–52）{@code createMETrait()} 的返回值 ⇒ <b>覆写 createMETrait 就能换掉 handleRecipe 看到的那个对象</b>，
     * 而父类自己内部用的 {@code this.meTrait} 仍是同一个实例（我们把父类那层作为 super 继承下来，没有掉包）。
     *
     * <p>🔴 <b>为什么通配符段「一律算已缓存」</b>：{@code hasCacheInSlot} 的语义是「这格的配方已经进过缓存表了，
     * 不用再搜」。返回 true 带来两个后果，都是我们要的：
     * <ul>
     *   <li>{@code handleRecipe} 偏移 371–408 里，{@code hasCacheInSlot == true} ⇒ <b>跳过</b>
     *       {@code setSlotCacheRecipe(slot, recipe)}。而那个方法体内有<b>两次</b>零检查下标读
     *       （{@code cacheRecipeCount[slot]} 偏移 67–68、{@code cacheRecipe[slot]} 偏移 77）—— 跳过 = 两处都不会越界；</li>
     *   <li>{@code recipeMultipleCacheMap} 因此永远不会出现 >= 1080 的键，
     *       于是 {@code MEPatternTrait#getCachedGTRecipe()} 里那句 {@code cacheRecipe[slot]}
     *       （偏移 80–85）也就永远读不到越界下标（那条路径是<b>间接</b>越界，最容易被漏掉）。</li>
     * </ul>
     * <p>⚠️ 通配符段的「配方就绪」本来就不该走父类这套缓存位 —— 上游超级样板总成自己也是用
     * {@code recipeCacheMap}（{@code Int2ReferenceMap}，<b>没有</b>定长数组）判就绪，
     * 我们自己的 {@code SuperWildcardPatternBufferPartMachine$SuperWildcardMEPatternTrait#hasCacheInSlot}
     * 就是 {@code recipeCache.isReady(slot)}。本轮星律还<b>没有</b>把那套按「实际配方数量」记账的账本接过来
     * （见交付报告「没做到」一节），所以通配符段先取「恒就绪」，语义 = 上游阈值 = 1 的默认态。
     */
    @Override
    protected MEPatternBufferPartMachine.MEPatternTrait createMETrait() {
        return new ShanhaiMEPatternTrait(this);
    }

    /**
     * 父类 {@code MEPatternTrait} 的子类，<b>只动两个下标方法</b>，其余（含 0..1079 的原有行为）逐字节继承。
     *
     * <p>⚠️ 构造期：它是在 {@code MEIOPartMachine} 的构造器里被建的（偏移 49），
     * 所以构造函数里<b>不许读本类字段</b>（那时全是 null / 0）；本类确实一个都没读。
     */
    private final class ShanhaiMEPatternTrait extends MEPatternBufferPartMachine.MEPatternTrait {

        private ShanhaiMEPatternTrait(@NotNull StellarSampleAssemblerPartMachine outer) {
            outer.super(outer);
        }

        /**
         * 通配符段（>= 1080）<b>不碰数组</b>直接回答 true；0..1079 原样交给父类（那段行为一个字不变）。
         *
         * <p>判据顺序<b>必须</b>是先比槽号、再调 super —— 反过来的话越界已经发生了。
         */
        @Override
        public boolean hasCacheInSlot(int slot) {
            if (slot >= ShanhaiWildcardLayout.PATTERN_SLOTS) {
                StellarSampleAssemblerPartMachine.this.shanhaiProbeHasCache++;
                if (slot < ShanhaiWildcardLayout.SLOT_SPACE_TOTAL) {
                    StellarSampleAssemblerPartMachine.this.shanhaiProbeHiHasCache++;
                }
                return true;
            }
            StellarSampleAssemblerPartMachine.this.shanhaiProbeHasCache++;
            return super.hasCacheInSlot(slot);
        }

        /** 通配符段不记缓存（父类体内两处定长数组下标都在 1080 处截断，见类注释）。 */
        @Override
        public void setSlotCacheRecipe(int slot, com.gregtechceu.gtceu.api.recipe.GTRecipe recipe) {
            if (slot >= ShanhaiWildcardLayout.PATTERN_SLOTS) {
                return;
            }
            super.setSlotCacheRecipe(slot, recipe);
        }
    }

    /**
     * <b>「不会越界」的边界证明</b>（一行 grep 判读，每个存档只打一次）。
     *
     * <p>它不算「结论」，它把<b>两个真值</b>一起摆出来：
     * <ul>
     *   <li>{@code cacheRecipeLen} —— 直接读父类那个 {@code protected final boolean[] cacheRecipe} 的
     *       {@code length}（<b>不是</b>硬编码常量，读的就是运行时那个数组）；</li>
     *   <li>{@code slotSpace} —— {@code getInternalSlotCount()}（本类覆写 = 1330）。</li>
     * </ul>
     * {@code cacheRecipeLen < slotSpace} ⇒ 「下标 >= cacheRecipeLen 的读一定会抛 AIOOBE」这件事成立；
     * 而 {@code guardHi=1} 证明这些下标<b>根本不进数组</b>（{@link ShanhaiMEPatternTrait#hasCacheInSlot} 先比槽号）。
     * {@code hiProbe} 是「真的拿 1080 / 1329 / 1330 去问过一遍、没有抛异常」的现场读数。
     *
     * <p>负对照（离线、可跑）：{@code temp\stellar-p1\probe-hascache\HasCacheGuardProbe.java}——
     * 同一个判据跑在 {@code boolean[1080]} 上，<b>去掉闸就会真的抛 AIOOBE</b>（它当场 catch 并打印）。
     */
    private void shanhaiLogBoundaryProof() {
        if (this.isRemote() || this.getLevel() == null) {
            return;
        }
        final long now = this.getLevel().getGameTime();
        if (this.shanhaiSafeLoggedTick != Long.MIN_VALUE && now - this.shanhaiSafeLoggedTick < 6000L) {
            return;
        }
        this.shanhaiSafeLoggedTick = now;
        final int arrayLen = this.cacheRecipe.length;
        final int slotSpace = this.getInternalSlotCount();
        final IMEPatternTrait trait = this.getMETrait();
        // 现场探针：通配符段两端 + 一段之外一格（1330 已越出槽号空间，只是证明「这个判定纯粹是算术、
        // 不看槽号空间」；它不会进任何数组，因为 >= 1080 一律短路）。
        final boolean hiFirst = trait != null && trait.hasCacheInSlot(ShanhaiWildcardLayout.MOTHER_OFFSET);
        final boolean hiLast = trait != null && trait.hasCacheInSlot(ShanhaiWildcardLayout.SLOT_SPACE_TOTAL - 1);
        final boolean loFirst = trait != null && trait.hasCacheInSlot(0);
        final boolean loLast = trait != null && trait.hasCacheInSlot(ShanhaiWildcardLayout.PATTERN_SLOTS - 1);
        ShanhaiStellarLog.info("STOCKSAFE cacheRecipeLen={} slotSpace={} gap={} guard=[hiFirst={} hiLast={} loFirst={} loLast={}]"
                        + " trait={} ⇒ {}",
                Integer.valueOf(arrayLen), Integer.valueOf(slotSpace), Integer.valueOf(slotSpace - arrayLen),
                Boolean.valueOf(hiFirst), Boolean.valueOf(hiLast), Boolean.valueOf(loFirst), Boolean.valueOf(loLast),
                trait == null ? "null" : trait.getClass().getSimpleName(),
                arrayLen < slotSpace
                        ? "下标 " + arrayLen + ".." + (slotSpace - 1) + " 若进数组必 AIOOBE；通配符段一律短路返回 true ⇒ 不越界"
                        : "数组长度已覆盖槽号空间（本轮应当不会走到这一支）");
    }

    // ================================================================ 2026-10-24：库存供料放行（只放供料，不动样板执行）

    /**
     * <b>放行点</b>：在第 4 个参数 {@code slot} 上把「库存可以直接供料」的槽补进活跃名单。
     *
     * <h2>为什么就是这一处（字节码链路，全部实证）</h2>
     * <pre>
     *   MEPatternRecipeHandlePart#handleRecipe(recipe, caps, simulate, flag)
     *     ├─ recipes2SlotsMap.getValues(recipe)        // 样板认领过这条配方 ⇒ 直接用它给的槽
     *     └─ 否则兜底：itemHandler.getActiveSlots()    // ← 父类实现 = 逐槽 isActive()
     *           └─ meHandleRecipe(recipe, simulate, slot)
     *                 └─ MEItemInputHandlerBase#meHandleRecipeInner
     *                       ├─ getInternalSlotOrNull(slot)
     *                       ├─ slot.isItemActive(simulate)     // 库存供料在这里就已经算「能供」了
     *                       └─ slot.handleItemInternal(map, circuit, simulate)
     *                             └─ findStockItemKey / consumeStockItem
     * </pre>
     * 父类 {@code isActive()} = {@code hasPatternInSlot && (itemInventory 非空 || fluidInventory 非空)}
     * （{@code javap -c} 偏移 0–39）⇒ <b>槽里必须先躺着东西</b>。而「从库存拉料」的槽按定义就是空的
     * ⇒ 永远进不了 {@code getActiveSlots()} ⇒ 供料链一次都不会被走到（上一轮日志 {@code STOCKFIND=0}）。
     *
     * <p>所以这里把「有样板 + 库存有配置」的槽补上（它本来就满足 {@code isItemActive}，只是不满足
     * {@code isActive} 那条「槽里非空」）。<b>闸（防串配方）不参与这个判定</b> —— 用户口径就是
     * 「库存拉取不需要防止串配方」。
     *
     * <p>⚠️ <b>为什么只放 0..1079</b>：见 {@link #shanhaiRebuildStockSupplySlots()} 的 AIOOBE 说明。
     */
    @Override
    protected int[] getActiveSlots() {
        final int[] base = super.getActiveSlots();
        final int[] extra = this.shanhaiStockSupplyActiveSlots();
        if (extra.length == 0) {
            return base;
        }
        return shanhaiMergeSlotArrays(base, extra);
    }

    /**
     * 两条槽号数组的<b>保序去重合并</b>（{@code base} 在前、{@code extra} 在后）。
     *
     * <p>两个放行点（{@link #getActiveSlots()} 与 {@link #getActiveAndUnCachedSlots()}）共用这一份，
     * 免得「两处各写一遍去重、结果顺序不一样」这种漂移。
     */
    private static int[] shanhaiMergeSlotArrays(int[] base, int[] extra) {
        final it.unimi.dsi.fastutil.ints.IntOpenHashSet seen =
                new it.unimi.dsi.fastutil.ints.IntOpenHashSet(base.length + extra.length);
        final int[] merged = new int[base.length + extra.length];
        int n = 0;
        for (int slot : base) {
            if (seen.add(slot)) {
                merged[n++] = slot;
            }
        }
        for (int slot : extra) {
            if (seen.add(slot)) {
                merged[n++] = slot;
            }
        }
        return n == merged.length ? merged : java.util.Arrays.copyOf(merged, n);
    }

    /** 放行快照（脏了就重建一次）。 */
    private int[] shanhaiStockSupplyActiveSlots() {
        if (this.shanhaiStockSupplyDirty) {
            this.shanhaiRebuildStockSupplySlots();
        }
        return this.shanhaiStockSupplySlots;
    }

    /** 放行快照置脏（样板变 / 库存配置变 / 载入 ⇒ 下一次 {@link #getActiveSlots()} 重建）。 */
    private void shanhaiMarkStockSupplyDirty() {
        this.shanhaiStockSupplyDirty = true;
    }

    /**
     * 重算「库存可以直接供料」的槽号快照。
     *
     * <h2>入选条件（三条，缺一不可）</h2>
     * <ol>
     *   <li>本机<b>确实配了库存</b>（{@link #shanhaiStockHasConfig()}：我们的 160 格 或 父类 32 格 或 流体侧）；</li>
     *   <li>该槽<b>有样板</b>（{@code hasPatternInSlot}，父类 {@code isItemActive} 的第一条判据，绕不过也不需要绕）；</li>
     *   <li>该槽<b>还不是活跃槽</b>（{@code isActive() == false}）—— 已经是活跃槽的由父类那份处理，不重复放。</li>
     * </ol>
     * <p>刻意<b>不</b>看 {@code blockAmbiguousPatterns} / {@code gateBlockedSlots}：闸只决定「哪块样板能被执行」，
     * 不决定「库存能不能供料」。
     *
     * <h2>🔴 二修（2026-10-24）：这一段为什么现在是 0..1329 而不是 0..1079</h2>
     * 上一轮只收 0..1079，理由是这个数组会进 {@code MEPatternRecipeHandlePart#handleRecipe} 的兜底循环，
     * 那里对每个槽都会调 {@code IMEPatternTrait#hasCacheInSlot(slot)}，而父类实现直接读
     * {@code MEPatternBufferPartMachine.cacheRecipe[slot]}（{@code javap -c} 偏移 0–9），该数组长度 = 1080
     * ⇒ 槽号 >=1080 会当场 AIOOBE。上一轮日志里
     * {@code 通配符展开被截断（K=4）：1 个母槽超限 [0(67条>K=4)]} / {@code [40(67条>K=4)]} 说明
     * <b>用户的样板就在通配符段</b>，于是 {@code STOCKTRIGGER activeSlots=0}、库存一次都没被问。
     *
     * <p>本轮把这个越界点<b>从根上堵掉</b>：{@link ShanhaiMEPatternTrait#hasCacheInSlot(int)} 对 >=1080
     * 直接短路返回 true（不进数组），{@link ShanhaiMEPatternTrait#setSlotCacheRecipe} 对 >=1080 直接不记
     * （父类那两处下标读也一起跳过）⇒ 通配符段<b>可以</b>进活跃名单，越界不可能发生
     * （边界证明见 {@link #shanhaiLogBoundaryProof()} 与 {@code STOCKSAFE} 那一行）。
     *
     * <h2>上限</h2>
     * 最多 {@value #SHANHAI_STOCK_SUPPLY_SLOT_CAP} 个；超了打 WARN 写明「有几个没放」，绝不静默。
     * <p>顺序 = 槽号升序（普通槽 → 母槽 → 展开槽），所以上限先吃普通槽；母槽/展开槽被砍时 WARN 里的数字会说出来。
     */
    /**
     * 放行名单覆盖到的槽号<b>上界</b> —— 二修之后就是<b>整段槽号空间</b>（{@code getInternalSlotCount()} = 1330）。
     *
     * <p>🔴 单独抽成一个方法，是为了让「放行到底覆盖哪一段」变成<b>一处可断言的落点</b>：
     * 结构审计 <b>W4</b> 盯这一行，负对照把这里的返回值改回「只到 1079」时 W4 必红
     * （见 {@code temp\stellar-p7\verify-phase7.ps1} 的 W 组）。上一轮那一版正是把它裁到
     * {@code PATTERN_SLOTS}，于是「样板全在通配符段」时放行名单恒空、库存一次都没被问。
     */
    private int shanhaiStockSupplySlotSpace() {
        return this.getInternalSlotCount();
    }

    /**
     * <b>二修新增的放行点②</b>：把「库存直供槽」并进 {@code getActiveAndUnCachedSlots()}。
     *
     * <h2>🔴 为什么只放 {@link #getActiveSlots()} 不够（这是上一轮真正漏掉的那一跳）</h2>
     * 父类那两条名单的用途<b>完全不同</b>，字节码逐条核过：
     * <pre>
     *   getActiveSlots()            ← MEPatternRecipeHandlePart#handleRecipe 兜底循环（偏移 275）
     *                                  「拿这个槽去试一下这条配方 / 真扣料」
     *   getActiveAndUnCachedSlots() ← MEPatternBufferPartMachineBase 偏移 818
     *                                  过滤条件 = internal.isActive() && !hasRecipeCacheInSlot(i)
     *        └─ MEItemInputHandlerBase#getActiveAndUnCachedSlotsLimitContentsMap（偏移 25 / 98）
     *             └─ GTRecipeLookupMixin#gtlCore$fromHolder（偏移 131）
     *                  ⇒ 这份「每槽可用内容物」就是机器拿去配方树里找<b>候选配方</b>的那个输入
     * </pre>
     * ⇒ 只放第一份 ⇒ 槽在「试配方」名单里、却<b>不在候选配方树里</b> ⇒ 控制器连一条候选都找不到
     * ⇒ {@code handleRecipe} 一次都不会被调 ⇒ 库存永远不被问。
     * 这正是上一轮日志 {@code activeSlots=1} 却 {@code STOCKSUPPLY=0} 的成因
     * （那 1 个槽是**父类激活**的通配符槽，而通配符槽的 {@code hasRecipeCacheInSlot} 当时恒 true
     * ⇒ 它也不在候选树里 ⇒ 一台机器一条候选都没有）。
     *
     * <p>⚠️ 顺序与 {@link #getActiveSlots()} 一致：父类那份在前，我们的放行槽在后（去重）。
     */
    @Override
    protected int[] getActiveAndUnCachedSlots() {
        final int[] base = super.getActiveAndUnCachedSlots();
        final int[] extra = this.shanhaiStockSupplyActiveSlots();
        if (extra.length == 0) {
            return base;
        }
        return shanhaiMergeSlotArrays(base, extra);
    }

    private void shanhaiRebuildStockSupplySlots() {
        this.shanhaiStockSupplyDirty = false;
        this.shanhaiStockSupplyCapped = 0;
        if (this.isRemote() || this.getLevel() == null || !this.shanhaiStockHasConfig()) {
            this.shanhaiStockSupplySlots = new int[0];
            return;
        }
        // 二修：整段槽号空间（0..1329）。原来这里是 Math.min(..., PATTERN_SLOTS) —— 那正是
        // 「样板都在通配符段 ⇒ 放行名单为空 ⇒ 库存一次都没被问」的直接原因。
        final int count = this.shanhaiStockSupplySlotSpace();
        final int limit = Math.min(count, SHANHAI_STOCK_SUPPLY_SLOT_CAP);
        final int[] out = new int[limit];
        int n = 0;
        int dropped = 0;
        for (int slot = 0; slot < count; slot++) {
            if (!this.hasPatternInSlot(slot)) {
                continue;
            }
            final MEPatternBufferPartMachineBase.InternalSlot internal = this.getInternalSlot(slot);
            if (internal == null || internal.isActive()) {
                continue;
            }
            if (n >= limit) {
                dropped++;
                continue;
            }
            out[n++] = slot;
        }
        this.shanhaiStockSupplySlots = n == out.length ? out : java.util.Arrays.copyOf(out, n);
        this.shanhaiStockSupplyCapped = dropped;
        if (dropped > 0) {
            final long now = this.getLevel() == null ? 0L : this.getLevel().getGameTime();
            if (this.shanhaiStockSupplyCapWarnTick == Long.MIN_VALUE || now - this.shanhaiStockSupplyCapWarnTick >= 6000L) {
                this.shanhaiStockSupplyCapWarnTick = now;
                ShanhaiStellarLog.warn("STOCKSUPPLY 放行上限 {}：还有 {} 个「有样板且库存有配置」的槽没进活跃名单"
                                + "（配方搜索会更省，但这些槽的库存供料这一轮不会发生；槽号按 0..{} 升序取前 {} 个）",
                        Integer.valueOf(SHANHAI_STOCK_SUPPLY_SLOT_CAP), Integer.valueOf(dropped),
                        Integer.valueOf(count - 1), Integer.valueOf(SHANHAI_STOCK_SUPPLY_SLOT_CAP));
            }
        }
    }

    /**
     * <b>STOCKSUPPLY 判据</b>（节流 200 tick + 状态翻转立刻打）：库存供料链<b>到底有没有被走到</b>。
     *
     * <p>格式（一行 grep 就够）：
     * <pre>
     *   STOCKSUPPLY path=on gate=&lt;off|clear|bypassed&gt; cfg=&lt;配了 N 格&gt; hit=&lt;键|none&gt; simulate=&lt;bool&gt; via=&lt;parent|ours&gt; kind=&lt;item|fluid&gt; supplySlots=&lt;放行槽数&gt;
     * </pre>
     * 四个字段各管一件事：
     * <ul>
     *   <li>{@code path=on} —— <b>这一行存在本身</b>就证明「库存被问过」（{@code findStockItemKey}/{@code findStockFluidKey}
     *       里唯一一处发出点；这两个方法在 gtlcore 里只有 {@code handleItemInternal/handleFluidInternal} 会调）；</li>
     *   <li>{@code gate=bypassed} —— 闸<b>正开着且确实拦下了样板</b>（{@code gateBlockedSlots} 非空），库存照样被供料
     *       ⇒ 「供料不受防串配方约束」当场自证；{@code gate=clear} = 闸开着但没拦下任何样板；
     *       {@code gate=off} = 闸关（开关 {@code blockAmbiguousPatterns=false}）；</li>
     *   <li>{@code hit=<键|none>} —— 这次查询有没有命中一个可用的键；</li>
     *   <li>{@code simulate} —— 这次是「算料」还是「真扣」。</li>
     * </ul>
     * 判读表见交付报告 §3；一句话：<b>没有 STOCKSUPPLY 行 = 供料链一次没走到</b>（去看 STOCKTRIGGER / 样板）。
     */
    private void shanhaiLogStockSupply(@NotNull String kind, @Nullable Object key, int cfg,
                                       boolean simulate, @NotNull String via) {
        if (this.isRemote()) {
            return;
        }
        this.shanhaiRefreshGateCache();
        final boolean gateArmed = this.blockAmbiguousPatterns;
        final boolean gateBlocking = gateArmed && !this.gateBlockedSlots.isEmpty();
        final String gate = !gateArmed ? "off" : (gateBlocking ? "bypassed" : "clear");
        final String hit = key == null ? "none" : String.valueOf(key);
        final long now = this.getLevel() == null ? 0L : this.getLevel().getGameTime();
        final String state = kind + '|' + gate + '|' + hit + '|' + simulate + '|' + cfg + '|' + via
                + '|' + this.shanhaiStockSupplySlots.length;
        if (state.equals(this.shanhaiStockSupplyLogState) && this.shanhaiStockSupplyLogTick > 0L
                && now - this.shanhaiStockSupplyLogTick < 200L) {
            return;
        }
        this.shanhaiStockSupplyLogState = state;
        this.shanhaiStockSupplyLogTick = now;
        ShanhaiStellarLog.info("STOCKSUPPLY path=on gate={} cfg={} hit={} simulate={} via={} kind={} supplySlots={}",
                gate, Integer.valueOf(cfg), hit, Boolean.valueOf(simulate), via, kind,
                Integer.valueOf(this.shanhaiStockSupplyActiveSlots().length));
    }

    /** 我们的 160 格（或父类 32 格）里到底有没有配置 —— 唯一判定点。 */
    private boolean shanhaiHasStockItemConfig() {
        if (this.shanhaiStockItemHandler != null && this.shanhaiStockItemHandler.hasConfig()) {
            return true;
        }
        return this.stockItemHandler != null && this.stockItemHandler.hasConfig();
    }

    /**
     * 库存同步：<b>父类先跑（32 格行为零改动）</b>，再让我们那 160 格同步 + 刷新缓存。
     *
     * <p>为什么覆写这里而不是 {@code update()}：父类的 {@code update()}
     * （{@code javap -c} 偏移 0–35）只在「{@code getOffsetTimer() % 周期 == 0}」时调
     * {@code syncStockInput()}，周期计算也在里面；覆写 {@code syncStockInput()} 就<b>沿用了同一个节拍</b>，
     * 不用把 {@code ME_UPDATE_INTERVAL} 那段复制一遍（它是父类的静态字段，访问面还不确定）。
     */
    @Override
    protected void syncStockInput() {
        super.syncStockInput();
        // C 刀 · A 段：通配符母槽的催化剂 map 跟着这个节拍对一遍（流体侧没有内容变更回调，见方法注释）
        this.shanhaiRefreshAllWildcardCatalysts();
        this.shanhaiSyncStock();
    }

    /** 库存页那行只读统计（服务端现算，@DescSynced 下发；不每帧反射）。 */
    private @NotNull String shanhaiComputeStockDiagLine() {
        final int ours = this.shanhaiConfiguredItemKeys().size();
        int parent = -1;
        try {
            final java.lang.reflect.Field f = com.shanhai.machine.stellar.StellarSampleAssemblerPartMachine.class
                    .getSuperclass().getDeclaredField("stockItemHandler");
            f.setAccessible(true);
            final Object handler = f.get(this);
            if (handler != null) {
                parent = 0;
                final java.lang.reflect.Method m = handler.getClass().getMethod("getConfigurableSlot", int.class);
                for (int s = 0; s < 32; s++) {
                    try {
                        final Object slot = m.invoke(handler, Integer.valueOf(s));
                        if (slot == null) { continue; }
                        final Object cfg = slot.getClass().getMethod("getConfig").invoke(slot);
                        if (cfg != null) { parent++; }
                    } catch (Throwable ignored) { }
                }
            }
        } catch (Throwable ignored) { parent = -1; }
        final int me = this.shanhaiStockSnapshot == null ? 0 : this.shanhaiStockSnapshot.size();
        // 三修 ②：流体侧也报一个真数（配置进没进去，玩家在界面上一眼能看出来）
        final int fluids = this.shanhaiConfiguredFluidKeys().size();
        // 🔴 2026-10-24（问题 2 的判据落点）：**库存不是配方触发器**。
        //    父类取料的唯一入口是 StockingPatternBufferInternalSlot#handleItemInternal（javap -c 实证：
        //    全 jar 里只有它调 findStockItemKey / consumeStockItem），而它拿的是**样板槽号**
        //    ⇒ 没有任何活跃样板槽时，库存配置<b>永远不会被问</b>，抬头只会显示「未找到配方」。
        //    这一格数字（activeSlots）就是当场分得清两者的唯一读数，所以直接摆在诊断行里。
        //    2026-10-24「库存供料放行」之后：它会含上本类补进来的「库存直供槽」（见 getActiveSlots 覆写），
        //    所以再单独把那个数字摆出来（supplySlots），一眼能看出放行有没有生效。
        final int activeSlots = this.shanhaiActiveSlotCount();
        final int supplySlots = this.shanhaiStockSupplyActiveSlots().length;
        return "库存输入：" + ours + " 格（我们的 160）｜" + (parent < 0 ? "?" : String.valueOf(parent))
                + " 格（父类 32）｜流体 " + fluids + " 条（父类 32 格）｜ME 可供应 " + me + " 种"
                + "｜活跃样板槽 " + activeSlots + " 个｜库存直供槽 " + supplySlots + " 个"
                + (this.shanhaiStockHasConfig() && activeSlots == 0
                        ? (this.shanhaiAnyPattern() ? "（有样板但没有活跃槽 ⇒ 库存不会被问）"
                                : "（无样板 ⇒ 库存不会被问）")
                        : "");
    }

    /**
     * 本机<b>参与供料/认领的样板槽数</b>（{@code getActiveSlots()} 的长度；本类的覆写 = 父类那份
     * ＋ 库存直供槽，因此这个数在「配了库存且有样板」时一定 &gt; 0）。
     *
     * <p>为什么用这个数而不是「槽里有几个物品」：父类取料/认领的入口是
     * {@code StockingPatternBufferInternalSlot#handleItemInternal(map, slot, simulate)}，
     * 它要的是<b>槽号</b>；槽号只可能来自活跃样板槽。所以「活跃样板槽 = 0」⇒ 库存那 160 格不会被问。
     */
    private int shanhaiActiveSlotCount() {
        final int[] active = this.getActiveSlots();
        return active == null ? 0 : active.length;
    }

    /**
     * 库存侧（我们的 160+160 或父类 32+32）到底有没有配置过东西。
     *
     * <p>复用同文件里既有的 {@link #shanhaiHasStockItemConfig()}（它已经同时看我们那 160 格与父类那 32 格，
     * 用的是 {@code hasConfig()} 直调，不反射）＋ 流体侧的键缓存 ⇒ <b>不新增第二条判定口径</b>。
     */
    private boolean shanhaiStockHasConfig() {
        return this.shanhaiHasStockItemConfig() || !this.shanhaiConfiguredFluidKeys().isEmpty();
    }

    /**
     * <b>问题 2 的现场判据</b>：库存配了东西、但一个活跃样板槽都没有 ⇒ 打一行
     * 「库存不会被问」。节流 6000 tick（5 分钟），状态变了立刻打一次。
     *
     * <p>判读方法（写进交付报告）：
     * <ul>
     *   <li>看到本行 {@code active=0} ⇒ 库存<b>不是</b>瓶颈，去查「有没有样板 / 样板认领了哪条配方」；</li>
     *   <li>看到 {@code STOCKFIND … stockHit=none}（或 {@code STOCKFLUID … hit=none}）⇒ 那才是「料取不到」；</li>
     *   <li>两个都没有、抬头仍是「未找到配方」⇒ 匹配在供料之前就死了（配方类型里没有这条配方 / 需要的是别的料）。</li>
     * </ul>
     */
    private void shanhaiLogStockTriggerHint() {
        final int active = this.shanhaiActiveSlotCount();
        final boolean cfg = this.shanhaiStockHasConfig();
        final boolean state = cfg && active == 0;
        if (this.shanhaiStockTriggerLoggedState == state && this.shanhaiStockTriggerLoggedCfg == cfg
                && (this.getLevel() == null
                        || this.getLevel().getGameTime() - this.shanhaiStockTriggerLoggedTick < 6000L)) {
            return;
        }
        this.shanhaiStockTriggerLoggedState = state;
        this.shanhaiStockTriggerLoggedCfg = cfg;
        this.shanhaiStockTriggerLoggedTick = this.getLevel() == null ? 0L : this.getLevel().getGameTime();
        // 🔴 2026-10-24（库存放行）：这一行现在必须能分清三种「库存不被问」的原因，
        //    否则「有样板但没活跃槽」会被读成「没样板」，又一次把排查带偏：
        //      · 没有任何样板                         ⇒ 机器本来就没有可跑的配方；
        //      · 有样板但没有活跃槽                   ⇒ 样板所在槽不满足入选三条（见 shanhaiRebuildStockSupplySlots）
        //                                               —— 二修之后 0..1329 全段都覆盖，所以不再是「段没覆盖」；
        //      · 有活跃槽                             ⇒ 库存只决定「够不够」。
        final boolean anyPattern = active > 0 ? true : this.shanhaiAnyPattern();
        ShanhaiStellarLog.info("STOCKTRIGGER activeSlots={} stockConfigured={} anyPattern={} ⇒ {}",
                Integer.valueOf(active), Boolean.valueOf(cfg), Boolean.valueOf(anyPattern),
                !cfg ? "没有库存配置：库存不参与供料"
                        : active > 0 ? "库存只决定「料够不够」，配方由样板 / 库存直供推进"
                        : anyPattern ? "库存不会被问：有样板、但没有活跃槽（二修后 0..1329 全段可放行；"
                                + "若仍有这行，看同一节的 STOCKSLOT 那一行说卡在哪一步）"
                        : "库存不会被问：本机一块样板都没有");
    }

    /** 本机有没有任何一块样板（1330 格里随便一块；只在 5 分钟节流的那条判据里调，不每 tick 跑）。 */
    private boolean shanhaiAnyPattern() {
        final int count = this.getInternalSlotCount();
        for (int slot = 0; slot < count; slot++) {
            if (this.hasPatternInSlot(slot)) {
                return true;
            }
        }
        return false;
    }

    /**
     * <b>STOCKSLOT 现场判据</b>（节流 200 tick = 10 秒）：上一轮日志里出现过
     * {@code STOCKTRIGGER activeSlots=1 …} 却<b>一条 STOCKSUPPLY 都没有</b>，
     * 那一行只说「有 1 个活跃槽」，说不出这个槽是谁、卡在哪一步。本行把它补全。
     *
     * <p>一行里有三段，按顺序读：
     * <pre>
     *   STOCKSLOT space=1330 active=A supply=S calls[isItemActive=X handleItem=Y hasCache=Z findItem=W findFluid=V] hi[...]
     *             slot[..] slot[..]
     * </pre>
     * <ul>
     *   <li>{@code active}/{@code supply}：活跃名单长度（= 父类那份 + 我们放行的）与放行快照长度；</li>
     *   <li>{@code calls[...]}：四个断点被调用的累计次数（顺序 = 字节码调用顺序）：
     *       ① {@code isItemActive} ② {@code handleItemInternal} ③ {@code hasCacheInSlot}
     *       ④ {@code findStockItemKey}/{@code findStockFluidKey}。<b>谁先变 0 就断在它前一跳</b>；</li>
     *   <li>{@code slot[...]}：每个活跃槽（最多 4 个）的现场 ——
     *       {@code #槽号/段 p32=(父类32格有没有配置) our=(我们160格有没有配置) pat=(有没有样板)
     *        act=(isActive) iA1=(isItemActive(true)) iA0=(isItemActive(false)) inv=(物料池件数) cin=(电路) sup=(在不在放行名单)}。</li>
     * </ul>
     * 判读表见 {@code temp\stellar-p1\报告-库存直供二修.md} §3。
     */
    private void shanhaiLogStockSlotProbe() {
        if (this.isRemote() || this.getLevel() == null) {
            return;
        }
        final long now = this.getLevel().getGameTime();
        if (this.shanhaiProbeLogTick != Long.MIN_VALUE && now - this.shanhaiProbeLogTick < 200L) {
            return;
        }
        this.shanhaiProbeLogTick = now;
        final int[] active = this.getActiveSlots();
        final int[] supply = this.shanhaiStockSupplySlots;
        final StringBuilder slots = new StringBuilder();
        final int shown = Math.min(active == null ? 0 : active.length, 4);
        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                slots.append(' ');
            }
            slots.append(this.shanhaiDescribeSlot(active[i], supply));
        }
        // 「有没有真的认领配方」：getSlot2RecipesCache() = 父类 recipeMultipleCacheMap 的只读副本，
        // 键 = 槽号、值 = 该槽攒到的配方集合。空 = 这条机器一条配方都还没被认领过。
        final StringBuilder claimed = new StringBuilder();
        int claimedSlots = 0;
        try {
            final IMEPatternTrait trait = this.getMETrait();
            if (trait != null) {
                final it.unimi.dsi.fastutil.ints.Int2ReferenceMap<
                        it.unimi.dsi.fastutil.objects.ObjectSet<com.gregtechceu.gtceu.api.recipe.GTRecipe>> cache =
                        trait.getSlot2RecipesCache();
                if (cache != null) {
                    for (final it.unimi.dsi.fastutil.ints.Int2ReferenceMap.Entry<
                            it.unimi.dsi.fastutil.objects.ObjectSet<com.gregtechceu.gtceu.api.recipe.GTRecipe>> e
                            : it.unimi.dsi.fastutil.ints.Int2ReferenceMaps.fastIterable(cache)) {
                        if (claimedSlots++ > 0) {
                            claimed.append(',');
                        }
                        claimed.append(e.getIntKey()).append('x').append(e.getValue() == null ? 0 : e.getValue().size());
                        if (claimedSlots >= 6) {
                            claimed.append(",…");
                            break;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            claimed.append('?');
        }
        // 一句话结论（先算成局部变量，别写成四层嵌套三元 —— 括号一多就看不出谁套谁）
        final String verdict;
        if (active == null || active.length == 0) {
            verdict = "活跃名单为空：配方匹配这一轮根本没问过任何槽";
        } else if (this.shanhaiProbeIsItemActive == 0L) {
            verdict = "有活跃槽但 isItemActive 一次没被调 ⇒ handleRecipe 没走到这里";
        } else if (this.shanhaiProbeHandleItem == 0L) {
            verdict = "isItemActive 被问了但 handleItemInternal 没被调 ⇒ 催化剂/虚拟料判定把它挡在门外";
        } else if (this.shanhaiProbeFindItem + this.shanhaiProbeFindFluid == 0L) {
            verdict = "handleItemInternal 进了但没查库存 ⇒ 看 map 是否为空/电路是否不匹配";
        } else {
            verdict = "供料链通了（库存被问过），剩下的看 STOCKSUPPLY 的 hit=";
        }
        ShanhaiStellarLog.info("STOCKSLOT space={} active={} supply={} calls[isItemActive={} handleItem={} "
                        + "hasCache={} findItem={} findFluid={}] hi[isItemActive={} handleItem={} hasCache={}] "
                        + "claimed=[{}] ⇒ {} {}",
                Integer.valueOf(this.getInternalSlotCount()),
                Integer.valueOf(active == null ? 0 : active.length),
                Integer.valueOf(supply == null ? 0 : supply.length),
                Long.valueOf(this.shanhaiProbeIsItemActive), Long.valueOf(this.shanhaiProbeHandleItem),
                Long.valueOf(this.shanhaiProbeHasCache), Long.valueOf(this.shanhaiProbeFindItem),
                Long.valueOf(this.shanhaiProbeFindFluid),
                Long.valueOf(this.shanhaiProbeHiIsItemActive), Long.valueOf(this.shanhaiProbeHiHandleItem),
                Long.valueOf(this.shanhaiProbeHiHasCache),
                claimed.length() == 0 ? "none" : claimed.toString(),
                verdict, slots.toString());
    }

    /** 单个槽的现场描述（{@link #shanhaiLogStockSlotProbe()} 用；只读、无副作用）。 */
    private @NotNull String shanhaiDescribeSlot(int slot, int[] supply) {
        final String seg;
        if (ShanhaiWildcardLayout.isMotherSlot(slot)) {
            seg = "mother" + (slot - ShanhaiWildcardLayout.MOTHER_OFFSET);
        } else if (ShanhaiWildcardLayout.isExpandSlot(slot)) {
            seg = "expand" + ShanhaiWildcardLayout.motherOf(slot) + "." + ShanhaiWildcardLayout.branchOf(slot);
        } else {
            seg = "pattern";
        }
        boolean inSupply = false;
        if (supply != null) {
            for (int s : supply) {
                if (s == slot) {
                    inSupply = true;
                    break;
                }
            }
        }
        final MEPatternBufferPartMachineBase.InternalSlot internal = this.getInternalSlot(slot);
        final boolean pat = this.hasPatternInSlot(slot);
        final boolean act = internal != null && internal.isActive();
        final boolean iA1 = internal != null && internal.isItemActive(true);
        final boolean iA0 = internal != null && internal.isItemActive(false);
        final int inv = internal == null ? -1 : internal.getItemInventory().size();
        final int finv = internal == null ? -1 : internal.getFluidInventory().size();
        return "#" + slot + '/' + seg
                + " p32=" + (this.stockItemHandler != null && this.stockItemHandler.hasConfig() ? 1 : 0)
                + " our=" + (this.shanhaiStockItemHandler != null && this.shanhaiStockItemHandler.hasConfig() ? 1 : 0)
                + " pat=" + (pat ? 1 : 0) + " act=" + (act ? 1 : 0)
                + " iA1=" + (iA1 ? 1 : 0) + " iA0=" + (iA0 ? 1 : 0)
                + " inv=" + inv + " finv=" + finv + " sup=" + (inSupply ? 1 : 0);
    }

    // ================================================================ 三修：样板在哪（STOCKPAT / STOCKWHO）

    /**
     * <b>STOCKPAT</b>：交叉核对「容器真值」与「{@code hasPatternInSlot} 覆写结论」。
     *
     * <h2>为什么必须有这一行</h2>
     * 实机日志给出 {@code STOCKTRIGGER … anyPattern=false}，而 {@code anyPattern} 本身就是
     * {@link #shanhaiAnyPattern()} 用 {@code hasPatternInSlot} 数出来的 —— <b>它是结论，不是证据</b>。
     * 这一行把两边并列：
     * <ul>
     *   <li><b>覆写结论</b>：{@code lo}（0..1079 命中数）/ {@code hi}（1080..1329 命中数）；</li>
     *   <li><b>容器真值</b>：{@code basePatterns}（父类那 1080 格样板容器里非空的格数，直接读
     *       {@code baseTerminalPatternInventory}，<b>不经过任何覆写</b>）、
     *       {@code wildcardInv}（我们那 50 格通配符样板容器的非空数）、
     *       {@code mothers}/{@code expands}（通配符母槽/展开槽按 {@code hasPatternInSlot} 判为真的个数）。</li>
     * </ul>
     * ⇒ <b>{@code basePatterns} 与 {@code lo} 不等 = 覆写有 bug</b>（这就是「样板放了但判不出来」的判据）；
     * 两者都为 0 才是「本机真的一块样板都没有」。
     *
     * <p>格式（一行 grep）：
     * <pre>
     *   STOCKPAT slots=1330 lo=N hi=M supply=S gateBlocked=G containers=[basePatterns=B wildcardInv=W mothers=Mth expands=Me]
     *            hiSlots=[1080,1120] ⇒ 判读
     * </pre>
     */
    private void shanhaiLogPatternCensus() {
        if (this.isRemote() || this.getLevel() == null) {
            return;
        }
        final long now = this.getLevel().getGameTime();
        // 覆写结论
        int lo = 0;
        int hi = 0;
        final StringBuilder hiSlots = new StringBuilder();
        final int space = this.getInternalSlotCount();
        for (int slot = 0; slot < space; slot++) {
            if (!this.hasPatternInSlot(slot)) {
                continue;
            }
            if (slot < ShanhaiWildcardLayout.PATTERN_SLOTS) {
                lo++;
            } else {
                hi++;
                if (hi <= 4) {
                    if (hiSlots.length() > 0) {
                        hiSlots.append(',');
                    }
                    hiSlots.append(slot);
                }
            }
        }
        // 容器真值（不经过任何覆写）
        int basePatterns = 0;
        try {
            if (this.baseTerminalPatternInventory != null) {
                final int n = Math.min(this.baseTerminalPatternInventory.size(), ShanhaiWildcardLayout.PATTERN_SLOTS);
                for (int i = 0; i < n; i++) {
                    if (!this.baseTerminalPatternInventory.getStackInSlot(i).isEmpty()) {
                        basePatterns++;
                    }
                }
            }
        } catch (Throwable ignored) {
            basePatterns = -1;
        }
        int wildcardInv = 0;
        for (int i = 0; i < WILDCARD_SLOT_COUNT; i++) {
            if (!this.wildcardPatternInventory.getStackInSlot(i).isEmpty()) {
                wildcardInv++;
            }
        }
        int mothers = 0;
        for (int i = 0; i < WILDCARD_SLOT_COUNT; i++) {
            if (this.hasPatternInSlot(ShanhaiWildcardLayout.motherApiSlot(i))) {
                mothers++;
            }
        }
        int expands = 0;
        for (int i = 0; i < ShanhaiWildcardLayout.EXPAND_COUNT; i++) {
            if (this.hasPatternInSlot(ShanhaiWildcardLayout.EXPAND_OFFSET + i)) {
                expands++;
            }
        }
        final int supply = this.shanhaiStockSupplySlots == null ? 0 : this.shanhaiStockSupplySlots.length;
        final int gateBlocked = this.gateBlockedSlots == null ? 0 : this.gateBlockedSlots.size();
        final String state = lo + "|" + hi + "|" + basePatterns + "|" + wildcardInv + "|" + mothers + "|" + expands + "|" + supply;
        if (state.equals(this.shanhaiPatternCensusState)
                && this.shanhaiPatternCensusTick != Long.MIN_VALUE
                && now - this.shanhaiPatternCensusTick < 6000L) {
            return;
        }
        this.shanhaiPatternCensusState = state;
        this.shanhaiPatternCensusTick = now;
        final String verdict;
        if (basePatterns > 0 && basePatterns != lo) {
            verdict = "⚠️ 覆写与容器真值不一致：父类那 1080 格容器里有 " + basePatterns
                    + " 格有样板，而 hasPatternInSlot 只报 " + lo + " ⇒ 覆写判错（把这条发我）";
        } else if (lo == 0 && hi == 0 && basePatterns <= 0 && wildcardInv == 0) {
            verdict = "本机（星律）1330 格里真的一块样板都没有 —— 样板不在本机，看同一节的 STOCKWHO 说在谁身上";
        } else {
            verdict = "本机有样板：普通段 " + lo + " 块、通配符段 " + hi + " 块"
                    + (hi > 0 ? "（母槽 " + mothers + " + 展开 " + expands + "）" : "") + "；覆写与容器一致";
        }
        ShanhaiStellarLog.info("STOCKPAT slots={} lo={} hi={} supply={} gateBlocked={} "
                        + "containers=[basePatterns={} wildcardInv={} mothers={} expands={}] hiSlots=[{}] ⇒ {}",
                Integer.valueOf(space), Integer.valueOf(lo), Integer.valueOf(hi), Integer.valueOf(supply),
                Integer.valueOf(gateBlocked), Integer.valueOf(basePatterns), Integer.valueOf(wildcardInv),
                Integer.valueOf(mothers), Integer.valueOf(expands),
                hiSlots.length() == 0 ? "none" : hiSlots.toString(), verdict);
        this.shanhaiLogPatternOwners();
    }

    /**
     * <b>STOCKWHO</b>：同一个多方块里<b>还有谁持有样板</b>（每个「能提供样板的仓室」+ 它的样板条数）。
     *
     * <h2>为什么需要它</h2>
     * 用户的实机日志里 `layout=[MESuperPatternBufferPartMachine x1, StellarSampleAssemblerPartMachine x1, …]`
     * —— <b>同一座结构里还有一台「超级通配符 ME 样板总成」</b>。样板完全可能放在那台上，
     * 而本机的放行名单要求「本槽有样板」（{@code shanhaiRebuildStockSupplySlots} 里的
     * {@code if (!hasPatternInSlot(slot)) continue;}）⇒ 本机一条都放不出去 ⇒ 库存永远不会被问。
     *
     * <h2>判据（一次 grep 就知道样板在谁身上）</h2>
     * <pre>
     *   STOCKWHO controllers=1 parts=7 providers=[MESuperPatternBufferPartMachine=12, StellarSampleAssemblerPartMachine=0]
     *            own=0 ⇒ 样板不在本机，在 MESuperPatternBufferPartMachine（12 条）
     * </pre>
     * <ul>
     *   <li>{@code providers} 的每一项 = 一个实现了 {@code ICraftingProvider} 的仓室（= 能给 AE 供样板的那种）
     *       及其 {@code getAvailablePatterns().size()}；本机永远在列表里（便于对照）；</li>
     *   <li>{@code parts} = 整个结构的仓室总数（不含控制器自己）；</li>
     *   <li>{@code own} = 本机<b>过闸之后</b>真正提供给 AE 的样板条数（{@code getAvailablePatterns().size()}）。</li>
     * </ul>
     */
    private void shanhaiLogPatternOwners() {
        if (this.isRemote() || this.getLevel() == null) {
            return;
        }
        final StringBuilder providers = new StringBuilder();
        String otherHolder = null;
        int otherCount = 0;
        int partCount = 0;
        final java.util.Set<MetaMachine> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        try {
            final List<IMultiController> controllers = this.getControllers();
            if (controllers != null) {
                for (IMultiController controller : controllers) {
                    if (controller == null) {
                        continue;
                    }
                    final List<IMultiPart> parts = controller.getParts();
                    if (parts == null) {
                        continue;
                    }
                    for (IMultiPart part : parts) {
                        if (part == null) {
                            continue;
                        }
                        final MetaMachine machine;
                        try {
                            machine = part.self();
                        } catch (Throwable ignored) {
                            continue;
                        }
                        if (machine == null || !seen.add(machine)) {
                            continue;
                        }
                        partCount++;
                        if (!(machine instanceof ICraftingProvider provider)) {
                            continue;
                        }
                        int count;
                        try {
                            final List<IPatternDetails> list = provider.getAvailablePatterns();
                            count = list == null ? 0 : list.size();
                        } catch (Throwable ignored) {
                            count = -2;
                        }
                        if (providers.length() > 0) {
                            providers.append(", ");
                        }
                        providers.append(machine.getClass().getSimpleName()).append('=').append(count);
                        if (machine != this && count > otherCount) {
                            otherCount = count;
                            otherHolder = machine.getClass().getSimpleName();
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            providers.append("?");
        }
        final int own = this.getAvailablePatterns() == null ? 0 : this.getAvailablePatterns().size();
        final String verdict;
        if (own > 0) {
            verdict = "本机自己就有 " + own + " 条样板（过闸后）⇒ 样板路径没问题，看 STOCKSLOT 的卡点";
        } else if (otherHolder != null) {
            verdict = "样板不在本机，在 " + otherHolder + "（" + otherCount + " 条）⇒ 本机放行名单要求「本槽有样板」，所以恒为空"
                    + "（最小改法见 报告-库存直供二修.md §11：把「供料名单」与「候选配方名单」拆成两份）";
        } else {
            verdict = "整个结构里没有任何仓室持有样板 ⇒ 请先放样板（这条与库存无关）";
        }
        ShanhaiStellarLog.info("STOCKWHO controllers={} parts={} providers=[{}] own={} ⇒ {}",
                Integer.valueOf(this.getControllers() == null ? 0 : this.getControllers().size()),
                Integer.valueOf(partCount), providers.length() == 0 ? "none" : providers.toString(),
                Integer.valueOf(own), verdict);
    }

    /**
     * 让<b>两份</b> 160 格各做一次 {@code syncStock}（填每格的 stock，界面显示用）+ 重建键缓存 + 取库存快照。
     *
     * <p>物品侧与流体侧<b>同一个节拍、同一份 MEStorage</b>；哪一份漏了，面板上那一半的格子就会永远显示 0
     * （流体的 {@code syncStock} 自带，{@code javap -p} 实证：{@code public void syncStock(MEStorage)}）。
     */
    private void shanhaiSyncStock() {
        if (this.shanhaiStockItemHandler == null) {
            return;
        }
        // 库存放行快照跟着同一个节拍重算一次：面板上改了配置、槽里推了料、样板换了，
        // 最迟一个同步周期内就会反映到 getActiveSlots()（重建是 1080 次数组读，不贵）。
        this.shanhaiMarkStockSupplyDirty();
        final net.minecraft.world.level.Level level = this.getLevel();
        final appeng.api.networking.IGrid grid = this.getMainNode().getGrid();
        if (grid == null) {
            // 掉线：清空库存显示（与父类 performStockSync 对 grid==null 的处置同义）
            this.shanhaiStockItemHandler.clearStocks();
            // 流体侧同一处置（javap：ExportOnlyAEStockingFluidList 自带 public clearStocks()）
            this.shanhaiStockFluidHandler.clearStocks();
            this.shanhaiStockSnapshot = null;
            this.shanhaiStockSnapshotTick = Long.MIN_VALUE;
            return;
        }
        final MEStorage storage = grid.getStorageService().getInventory();
        // ⚠️ syncStock 内部用的是 handler 自己的 actionSource（它属于本机）⇒ 我们不需要、也拿不到那个字段
        this.shanhaiStockItemHandler.syncStock(storage);
        // 流体侧同一节拍：不填 stock，面板流体格永远显示不出 ME 现量
        // （javap：ExportOnlyAEStockingFluidList 自带 public syncStock(MEStorage)，同样用 this$0.actionSource）
        this.shanhaiStockFluidHandler.syncStock(storage);
        this.shanhaiRebuildStockItemKeys();        // 清单快照 = 一次「全网络列举」，不便宜 ⇒ 最多每 20 游戏刻（1 秒）重建一次。
        this.shanhaiRebuildStockFluidKeys();       // 三修 ②：流体侧同一节拍（它只扫 160 个 slot 的 config，很便宜）
        // 与父类那套 stockMap 的「同步周期内是陈旧值」同一量级的代价，不额外加负担。
        final long now = level == null ? 0L : level.getGameTime();
        if (this.shanhaiStockSnapshot == null
                || this.shanhaiStockSnapshotTick == Long.MIN_VALUE
                || now < this.shanhaiStockSnapshotTick
                || now - this.shanhaiStockSnapshotTick >= 20L) {
            this.shanhaiStockSnapshot = storage.getAvailableStacks();
            this.shanhaiStockSnapshotTick = now;
        }
        // 闸计数：让 GUI/Jade 的数字也跟着刷新（脏标记挡住重算，不会每拍扫 1330 格 NBT）
        this.shanhaiRefreshGateCache();
        // 标签过滤页的匹配预览：跟着库存快照更新（同一节拍，因为它的数据源就是这份快照）
        this.shanhaiRefreshTagPreview();
        // 库存页那行只读统计（服务端现算；@DescSynced 下发，界面只读）
        this.shanhaiStockDiagLine = this.shanhaiComputeStockDiagLine();
        // 🔴 问题 2 的现场判据：库存配了、但一个活跃样板槽都没有 ⇒ 打一行「库存不会被问」。
        //    判读方法见 {@link #shanhaiLogStockTriggerHint()} 的 javadoc（写进交付报告同一份口径）。
        this.shanhaiLogStockTriggerHint();
        // 二修（2026-10-24）：上一行只说「有几个活跃槽」，说不清「那个槽是谁、被问到哪一步就断了」。
        //    下面这一行把它补全（同一节拍、节流 200 tick），并在第一次同步时打一遍越界边界证明。
        this.shanhaiLogStockSlotProbe();
        this.shanhaiLogBoundaryProof();
        // 三修（2026-10-24 实机 anyPattern=false）：样板到底在不在本机 / 在别的仓室身上。
        this.shanhaiLogPatternCensus();
        // 三修 ①：神锻面板第四行那句「装在哪儿」跟着同一个节拍自愈（结构成型/拆掉都会在一个周期内反映）
        if (this.shanhaiRefreshForgeHostName()) {
            ShanhaiStellarLog.info("神锻面板：本仓室所在控制器 => {}", this.shanhaiForgeHostName);
        }
    }

    /** 重扫 160 格，缓存「哪些键被配置过」（{@code findStockItemKey} 每个配方都要问，不能每次扫 160 格）。 */
    private void shanhaiRebuildStockItemKeys() {
        this.shanhaiStockItemKeys.clear();
        if (this.shanhaiStockItemHandler == null) {
            return;
        }
        final int count = Math.min(this.shanhaiStockItemHandler.getConfigurableSlots(),
                ShanhaiStockConfigLayout.CONFIG_SIZE);
        for (int i = 0; i < count; i++) {
            final IConfigurableSlot slot = this.shanhaiStockItemHandler.getConfigurableSlot(i);
            if (slot == null) {
                continue;
            }
            final GenericStack config = slot.getConfig();
            if (config != null && config.what() instanceof AEItemKey itemKey) {
                this.shanhaiStockItemKeys.add(itemKey);
            }
        }
    }

    /** 当前 160 格里配置过的物品键（只读视图，调用方不许改）。 */
    private java.util.Set<AEItemKey> shanhaiConfiguredItemKeys() {
        if (this.shanhaiStockItemKeys.isEmpty() && this.shanhaiStockItemHandler != null
                && this.shanhaiStockItemHandler.hasConfig()) {
            this.shanhaiRebuildStockItemKeys();
        }
        return this.shanhaiStockItemKeys;
    }

    /**
     * 某个键现在在 ME 网络里<b>可取的量</b>。
     *
     * <p>用 {@code MEStorage.getAvailableStacks()} 的<b>每 tick 一次</b>快照，而不是逐个键 SIMULATE 抽取：
     * 抽取需要 {@code IActionSource}，而父类的 {@code actionSource} 是 <b>mixin 加进去的字段</b>
     * （{@code javap -p} 里没有，源码层面引用不到）⇒ 为了不靠反射，本类改用「清单快照」这一路。
     * <p>⚠️ 二者语义差异（写清楚，不藏）：清单里可能有<b>不可抽取</b>的键（例如只读存储），
     * 于是「判够」可能偏乐观；但真正扣料走的是父类 {@code consumeStockItem → extractStock}
     * （那是<b>真抽取</b>，用机器自己的 actionSource），扣不到就返回 false ⇒ <b>不会凭空造物</b>。
     */
    private long shanhaiAvailableItem(@Nullable AEItemKey key) {
        if (key == null) {
            return 0L;
        }
        if (this.shanhaiStockSnapshot == null) {
            return 0L;
        }
        return this.shanhaiStockSnapshot.get(key);
    }

    /** 饱和加法（与父类的 {@code NumberUtils.saturatedAdd} 同义：溢出就停在 Long.MAX_VALUE）。 */
    private static long shanhaiSaturatedAdd(long a, long b) {
        final long sum = a + b;
        if (((a ^ sum) & (b ^ sum)) < 0L) {
            return a > 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
        return sum;
    }

    /**
     * ⑦ 的解析入口：<b>父类优先</b>（32 格路径逐字节不变），父类找不到再按我们的 160 格找。
     *
     * <p>父类的实现（{@code javap -c} 偏移 0–208）：① 先按 Ingredient 的候选栈逐个算
     * 「槽内已有 + {@code stockItemHandler.getAvailableAmount}」；② 再遍历父类
     * {@code configList} 里 {@code matches(ingredient)} 的键；都没有就返回 null。
     * 我们补的就是「②那份清单的 160 格版本」。
     */
    @Override
    protected AEItemKey findStockItemKey(Ingredient ingredient, Object2LongMap<AEItemKey> stockMap,
                                         Object2LongMap<AEItemKey> pendingMap, long amount, boolean simulate) {
        // 阶段二十二 · 诊断（节流）：配方查询时到底看到了什么 —— stockCfg=我们配置的键数、stockHit=命中键、simulate
        // 🔴 2026-10-24 收窄（用户口径：「顺手收窄一条诊断日志」），两刀：
        //   ① 节流窗口 20 tick（1 s）→ 200 tick（10 s）：同一批配方搜索一轮本来就会连打十几行，
        //      1 秒窗口等于没节流；
        //   ② 只在【真有可能命中】时才打：stockCfg=0 ⇒ 我们的 160 格一个键都没配，这一段必不可能命中；
        //      amount<=0 ⇒ 需求量为 0，也不可能判「够」。这两种情况下这行日志没有诊断价值，
        //      却会跟着配方搜索一起刷屏 ⇒ 直接不打（不是改成打「none」，那还是刷屏）。
        final long stockDiagNow = this.getLevel() == null ? 0L : this.getLevel().getGameTime();
        // 二修：④ 号断点计数（这一行只可能来自 handleItemInternal，见方法 javadoc）
        this.shanhaiProbeFindItem++;
        final int stockCfgCount = this.shanhaiConfiguredItemKeys().size();
        final boolean stockDiagWanted = stockCfgCount > 0 && amount > 0L;
        final boolean stockDiag = stockDiagNow - this.shanhaiStockFindDiagTick >= 200L;   // 无条件节流（父代理要求：去掉 stockCfg>0 那层门）
        final AEItemKey direct = super.findStockItemKey(ingredient, stockMap, pendingMap, amount, simulate);
        if (direct != null) {
            if (stockDiag) {
                this.shanhaiStockFindDiagTick = stockDiagNow;
                ShanhaiStellarLog.info("STOCKFIND stockCfg={} stockHit={} via=parent simulate={}",
                        Integer.valueOf(stockCfgCount), direct, Boolean.valueOf(simulate));
            }
            // 供料链判据：这一行只可能在 handleItemInternal 那条路上出现（见方法 javadoc）
            this.shanhaiLogStockSupply("item", direct, stockCfgCount, simulate, "parent");
            return direct;
        }
        for (AEItemKey key : this.shanhaiConfiguredItemKeys()) {
            if (key == null || !key.matches(ingredient)) {
                continue;
            }
            long available = shanhaiSaturatedAdd(stockMap.getLong(key), this.shanhaiAvailableItem(key));
            if (simulate) {
                available = shanhaiSaturatedAdd(available, pendingMap.getLong(key));
            }
            if (available >= amount) {
                if (stockDiag) {
                    this.shanhaiStockFindDiagTick = stockDiagNow;
                    ShanhaiStellarLog.info("STOCKFIND stockCfg={} stockHit={} via=ours simulate={}",
                            Integer.valueOf(stockCfgCount), key, Boolean.valueOf(simulate));
                }
                this.shanhaiLogStockSupply("item", key, stockCfgCount, simulate, "ours");
                return key;
            }
        }
        if (stockDiag) {
            this.shanhaiStockFindDiagTick = stockDiagNow;
            ShanhaiStellarLog.info("STOCKFIND stockCfg={} stockHit=none simulate={}（这一跳就断了）",
                    Integer.valueOf(stockCfgCount), Boolean.valueOf(simulate));
        }
        this.shanhaiLogStockSupply("item", null, stockCfgCount, simulate, "ours");
        return null;
    }

    // ================================================================ 三修 ②：流体侧的「父类优先 + 我们自己补一层」

    /**
     * 流体解析入口：<b>父类优先</b>（它那 32 格／configList 的路径逐字节不变），父类找不到再扫我们自己在面板里配过的键。
     *
     * <h2>🔴 为什么「只改面板绑定」还不够，必须连这条一起覆写</h2>
     * 父类 {@code findStockFluidKey}（{@code javap -c} 偏移 0–211）只认<b>两样东西</b>：
     * ① {@code FluidIngredient.getStacks()} 里的**精确**流体键（再经 {@code stockFluidHandler.getAvailableAmount}）；
     * ② {@code stockFluidHandler.configList} 里被 {@code AEUtils.testFluidIngredient} 命中的键。
     * 第二样是一份**清单缓存**（{@code onConfigChanged()} 才重建）。面板改配置时它会被 slot 回调刷新，
     * 但只要有一次没刷到（例如存档刚载入、或配置是在客户端那份 slot 上改的），
     * 父类就「看不见一个明明配好的流体」⇒ 用户报的现象就回来了。
     * ⇒ 本覆写补的就是「②那份清单的 160 格版本」，与物品侧 {@link #findStockItemKey} 完全同一套写法。
     *
     * <h2>日志（节流）</h2>
     * {@code STOCKFLUID cfg=<父类配了几格> hit=<键|none> via=<parent|ours> simulate=<bool>}，
     * 200 tick（10 s）最多一行 —— 配方搜索一轮本来就会连问十几次，不节流等于刷屏。
     * 另加一道门（与物品侧 {@code STOCKFIND} 同款）：<b>两边都没配置 或 需求量为 0 时一行都不打</b>，
     * 否则一台没配流体的机器也会每 10 秒刷一行无用诊断。
     */
    @Override
    protected AEFluidKey findStockFluidKey(com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient ingredient,
                                           Object2LongMap<AEFluidKey> stockMap,
                                           Object2LongMap<AEFluidKey> pendingMap, long amount, boolean simulate) {
        final long now = this.getLevel() == null ? 0L : this.getLevel().getGameTime();
        this.shanhaiProbeFindFluid++;   // 二修：④ 号断点的流体侧
        final int parentCfg = this.shanhaiParentConfiguredFluidCount();
        final boolean diagWanted = amount > 0L && (parentCfg > 0 || !this.shanhaiConfiguredFluidKeys().isEmpty());
        final boolean diag = diagWanted && (now - this.shanhaiStockFluidFindDiagTick >= 200L);
        final AEFluidKey direct = super.findStockFluidKey(ingredient, stockMap, pendingMap, amount, simulate);
        if (direct != null) {
            if (diag) {
                this.shanhaiStockFluidFindDiagTick = now;
                ShanhaiStellarLog.info("STOCKFLUID cfg={} hit={} via=parent simulate={}",
                        Integer.valueOf(parentCfg), direct, Boolean.valueOf(simulate));
            }
            this.shanhaiLogStockSupply("fluid", direct, parentCfg, simulate, "parent");
            return direct;
        }
        for (AEFluidKey key : this.shanhaiConfiguredFluidKeys()) {
            if (key == null || !shanhaiFluidIngredientMatches(ingredient, key)) {
                continue;
            }
            long available = shanhaiSaturatedAdd(stockMap.getLong(key), this.shanhaiAvailableFluid(key));
            if (simulate) {
                available = shanhaiSaturatedAdd(available, pendingMap.getLong(key));
            }
            if (available >= amount) {
                if (diag) {
                    this.shanhaiStockFluidFindDiagTick = now;
                    ShanhaiStellarLog.info("STOCKFLUID cfg={} hit={} via=ours simulate={}",
                            Integer.valueOf(parentCfg), key, Boolean.valueOf(simulate));
                }
                this.shanhaiLogStockSupply("fluid", key, parentCfg, simulate, "ours");
                return key;
            }
        }
        if (diag) {
            this.shanhaiStockFluidFindDiagTick = now;
            ShanhaiStellarLog.info("STOCKFLUID cfg={} hit=none via=ours simulate={}（父类与我们都没命中）",
                    Integer.valueOf(parentCfg), Boolean.valueOf(simulate));
        }
        this.shanhaiLogStockSupply("fluid", null, parentCfg, simulate, "ours");
        return null;
    }

    /**
     * 扣流体：<b>父类优先</b>（{@code stockMap} 里那部分 + 从 ME 真抽取，两步逐字节与父类一致），
     * 扣之前先补一跳「让父类那份键清单自己重扫一遍」。
     *
     * <h2>🔴 为什么「只改查找」会出现「能找到但扣不到」</h2>
     * 父类 {@code ExportOnlyAEStockingFluidList.extractStock(key, n)}（{@code javap -c} 全文核过）
     * 抽完 ME 之后会调 {@code getAvailableAmount(key)} 回填格子库存，而那个方法<b>第一句就是
     * 「{@code configList.contains(key)}，否则返回 0」</b> ⇒ 清单没收录我们那个键时：
     * ME 里的流体被抽走了，界面却显示「没有库存」，下一轮又「找不到」⇒ 来回抖。
     * 所以这里在扣之前做一件<b>无副作用</b>的事：发现「我们配过、父类清单不认」就把父类自己的
     * {@code onConfigChanged()}（public，只重建它那份清单，不碰任何库存数字）调一次。
     *
     * <p>⚠️ <b>绝不在这里再抽一遍 ME</b>：{@code super.consumeStockFluid} 失败时可能已经部分抽走了，
     * 再抽一次就是凭空多扣（本工程红线：不许吞东西）。所以本方法只做「让父类看得见」+ 转发 + 日志。
     */
    @Override
    protected boolean consumeStockFluid(Object2LongMap<AEFluidKey> stockMap, AEFluidKey key, long amount) {
        if (key != null && this.shanhaiIsConfiguredFluid(key) && !this.shanhaiParentFluidKnowsKey(key)
                && this.stockFluidHandler != null) {
            // 只重建父类那份键清单（它自己的 public 方法），不动任何数量
            this.stockFluidHandler.onConfigChanged();
            if (this.shanhaiParentConfiguredFluidCount() > 0) {
                ShanhaiStellarLog.info("STOCKFLUID 父类键清单陈旧 ⇒ 就地重扫一次（本次键 {} 已收录）", key);
            }
        }
        final boolean ok = super.consumeStockFluid(stockMap, key, amount);
        // 同一个 200 tick 节流：扣料每个配方 tick 都会走到这里，不节流就是刷屏
        final long now = this.getLevel() == null ? 0L : this.getLevel().getGameTime();
        if (!this.isRemote() && now - this.shanhaiStockFluidFindDiagTick >= 200L) {
            this.shanhaiStockFluidFindDiagTick = now;
            ShanhaiStellarLog.info("STOCKFLUID consume key={} amount={} ok={}（父类两步：先扣 stockMap，再从 ME 抽）",
                    key, Long.valueOf(amount), Boolean.valueOf(ok));
        }
        return ok;
    }

    /** 重扫面板那 160 格流体，缓存「配了哪些流体键」（{@code findStockFluidKey} 每个配方都要问，不能每次扫 160 格）。 */
    private void shanhaiRebuildStockFluidKeys() {
        this.shanhaiStockFluidKeys.clear();
        if (this.shanhaiStockFluidHandler == null) {
            return;
        }
        for (com.gregtechceu.gtceu.integration.ae2.slot.IConfigurableSlot slot : this.shanhaiStockFluidHandler.getInventory()) {
            if (slot == null) {
                continue;
            }
            final GenericStack config = slot.getConfig();
            if (config != null && config.what() instanceof AEFluidKey fluidKey) {
                this.shanhaiStockFluidKeys.add(fluidKey);
            }
        }
    }

    /** 面板里配置过的流体键（只读视图，调用方不许改）。与物品侧同款：缓存空而真 handler 明明有配置 ⇒ 先补建一次。 */
    private java.util.Set<AEFluidKey> shanhaiConfiguredFluidKeys() {
        if (this.shanhaiStockFluidKeys.isEmpty() && this.stockFluidHandler != null
                && this.stockFluidHandler.hasConfig()) {
            this.shanhaiRebuildStockFluidKeys();
        }
        return this.shanhaiStockFluidKeys;
    }

    /** 这个流体键在不在我们面板的配置里。 */
    private boolean shanhaiIsConfiguredFluid(@Nullable AEFluidKey key) {
        return key != null && this.shanhaiConfiguredFluidKeys().contains(key);
    }

    /**
     * 父类那 32 格里到底配了几格（= 日志里的 {@code cfg=}；也用来判断「父类看不看得见」）。
     *
     * <p>⚠️ 计数委托给 {@link #shanhaiCountConfiguredFluid}：迁移闸门与诊断日志必须<b>同一个数法</b>，
     * 否则会出现「日志说有 3 条、闸门说 0 条」这种自己骗自己的读数。
     */
    private int shanhaiParentConfiguredFluidCount() {
        if (this.stockFluidHandler == null) {
            return 0;
        }
        return this.shanhaiCountConfiguredFluid(this.stockFluidHandler);
    }

    /** 父类那份键清单里有没有这个键（照它自己的 {@code getConfigurableSlot(i).getConfig()} 直接看，不依赖它的缓存语义）。 */
    private boolean shanhaiParentFluidKnowsKey(@NotNull AEFluidKey key) {
        if (this.stockFluidHandler == null) {
            return false;
        }
        final int count = this.stockFluidHandler.getConfigurableSlots();
        for (int i = 0; i < count; i++) {
            final com.gregtechceu.gtceu.integration.ae2.slot.IConfigurableSlot slot =
                    this.stockFluidHandler.getConfigurableSlot(i);
            if (slot == null) {
                continue;
            }
            final GenericStack config = slot.getConfig();
            if (config != null && key.equals(config.what())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 某个流体键现在在 ME 网络里<b>可取的量</b>（用本 tick 的库存快照，与物品侧 {@link #shanhaiAvailableItem} 同一份）。
     *
     * <p>⚠️ 与物品侧同一条诚实边界：清单里可能有不可抽取的键（只读存储）⇒「判够」可能偏乐观；
     * 真正扣料走父类 {@code extractStock}（真抽取），抽不到就返回 false ⇒ 不会凭空造物。
     */
    private long shanhaiAvailableFluid(@Nullable AEFluidKey key) {
        if (key == null || this.shanhaiStockSnapshot == null) {
            return 0L;
        }
        return this.shanhaiStockSnapshot.get(key);
    }

    /** 流体原料匹配：直接用父类同一个判据（{@code AEUtils.testFluidIngredient}，public static）。 */
    private static boolean shanhaiFluidIngredientMatches(
            com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient ingredient, @NotNull AEFluidKey key) {
        try {
            return org.gtlcore.gtlcore.integration.ae2.AEUtils.testFluidIngredient(ingredient, key);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 界面/统计用：把 160 格里「有货」的那些也追加进去（父类那 32 格的结果保持原样）。
     *
     * <p>槽子类的 {@code getLimitItemStackInput()} = {@code super} 结果 + 本方法
     * （{@code javap -c} 偏移 0–14 实证）⇒ 覆写本方法即覆盖整条展示链。
     */
    @Override
    protected void appendStockItemContents(List<ItemStack> contents) {
        super.appendStockItemContents(contents);
        if (this.shanhaiStockItemHandler == null) {
            return;
        }
        for (AEItemKey key : this.shanhaiConfiguredItemKeys()) {
            final long available = this.shanhaiAvailableItem(key);
            if (available > 0L) {
                contents.add(key.toStack((int) Math.min(available, Integer.MAX_VALUE)));
            }
        }
    }

    /** 同上，给 {@code getItemStackInputMap()} 用。 */
    @Override
    protected void addStockItemMap(Object2LongOpenHashMap<ItemStack> map) {
        super.addStockItemMap(map);
        if (this.shanhaiStockItemHandler == null) {
            return;
        }
        for (AEItemKey key : this.shanhaiConfiguredItemKeys()) {
            final long available = this.shanhaiAvailableItem(key);
            if (available > 0L) {
                map.addTo(key.toStack(), available);
            }
        }
    }

    /** 配置变了 ⇒ 重建键缓存（父类的 {@code onStockInputConfigChanged} 会先跑它自己那套）。 */
    @Override
    protected void onStockInputConfigChanged(boolean invalidateRecipeCaches) {
        super.onStockInputConfigChanged(invalidateRecipeCaches);
        this.shanhaiRebuildStockItemKeys();
        this.shanhaiRebuildStockFluidKeys();      // 三修 ②：流体键清单跟着同一个回调重建
        this.shanhaiStockSnapshot = null;
        this.shanhaiStockSnapshotTick = Long.MIN_VALUE;
        // 库存配置变了 ⇒「哪些槽可以靠库存供料」的放行快照必须重算（新增配置的槽要立刻能供料）
        this.shanhaiMarkStockSupplyDirty();
    }

    // ---------------------------------------------------------------- ⑦ 旧 32 条配置迁移（幂等）

    /**
     * <b>旧存档迁移</b>：父类那 32 格配置 ⇒ 我们的 160 格（只做一次）。
     *
     * <h2>🔴 为什么这里<b>不需要</b>任何 NBT 键名</h2>
     * 迁移发生在 {@code loadCustomPersistedData} 的末尾 —— 那一刻 LDLib 已经把
     * <b>两份 handler 都就地还原好了</b>（{@code loadManagedPersistentData} 先
     * {@code IManagedAccessor.writePersistedFields(tag, getPersistedFields())}
     * 再调 {@code loadCustomPersistedData(tag)}，{@code javap -c} 实证，见报告 §A.3）。
     * ⇒ 我们直接<b>读对象</b>（{@code stockItemHandler.getConfigurableSlot(i).getConfig()}）就行，
     * 不用碰 raw tag ⇒ <b>不存在「猜键名猜错 = 静默不迁移」这一类失败</b>。
     *
     * <h2>幂等</h2>
     * 三条闸：① {@link #shanhaiStockMigrated}（落盘的 once 标记，删光配置也不会「幽灵复活」）；
     * ② 我们的 160 格已经有配置 ⇒ 直接认定迁过；③ 父类 32 格没有任何配置 ⇒ 认定迁过（新机器）。
     *
     * <h2>绝不静默丢</h2>
     * 旧配置条数 &gt; 160 时打 WARN 写明条数（实际上父类只有 32 格，不可能触发，
     * 但这段是为了「将来有人把 32 改大」时不静默）。
     */
    private void shanhaiMigrateLegacyStockConfigs(@NotNull String where) {
        if (this.shanhaiStockItemHandler == null) {
            return;
        }
        if (this.stockItemHandler == null) {
            return;
        }
        final int ourConfigured = this.shanhaiCountConfigured(this.shanhaiStockItemHandler);
        final int legacyConfigured = this.shanhaiCountConfigured(this.stockItemHandler);
        // 🔴 闸门只在 ShanhaiStockConfigLayout.migrationAction 一处（纯函数，探针实跑真值表）
        final ShanhaiStockConfigLayout.MigrationAction action =
                ShanhaiStockConfigLayout.migrationAction(this.shanhaiStockMigrated, ourConfigured, legacyConfigured);
        switch (action) {
            case SKIP_ALREADY_MIGRATED -> {
                return;
            }
            case SKIP_ALREADY_CONFIGURED -> {
                this.shanhaiStockMigrated = true;
                this.shanhaiStockItemHandler.onConfigChanged();
                this.shanhaiRebuildStockItemKeys();
                this.shanhaiLogMigrationOnce(where, -1, 0);
                return;
            }
            case NOTHING_TO_COPY -> {
                // 不置 once 标记！onLoad 可能早于 loadCustomPersistedData（见 MigrationAction#NOTHING_TO_COPY）
                this.shanhaiLogMigrationOnce(where, 0, 0);
                return;
            }
            default -> {
                // COPY：继续往下
            }
        }
        final int legacySize = this.stockItemHandler.getConfigurableSlots();
        final List<GenericStack> legacy = new ArrayList<>();
        for (int i = 0; i < legacySize; i++) {
            final IConfigurableSlot slot = this.stockItemHandler.getConfigurableSlot(i);
            final GenericStack config = slot == null ? null : slot.getConfig();
            if (config != null && config.what() instanceof AEItemKey) {
                legacy.add(config);
            }
        }
        int copied = 0;
        for (int i = 0; i < legacy.size() && i < ShanhaiStockConfigLayout.CONFIG_SIZE; i++) {
            final IConfigurableSlot target = this.shanhaiStockItemHandler.getConfigurableSlot(i);
            if (target == null) {
                continue;
            }
            if (target instanceof IMESlot silent) {
                // 不触发逐格回调（160 次回调会让 syncStockInput 跑 160 遍）
                silent.setConfigWithoutNotify(legacy.get(i));
            } else {
                target.setConfig(legacy.get(i));
            }
            copied++;
        }
        this.shanhaiStockItemHandler.onConfigChanged();
        this.shanhaiRebuildStockItemKeys();
        this.shanhaiStockMigrated = true;
        this.shanhaiLogMigrationOnce(where, legacy.size(), copied);
        if (legacy.size() > copied) {
            ShanhaiStellarLog.warn("库存输入迁移被截断：旧配置 {} 条，只灌入 {} 条（新上限 {}）",
                    legacy.size(), copied, ShanhaiStockConfigLayout.CONFIG_SIZE);
        }
    }

    /** 数一份 handler 上有几条配置（只用 public API）。 */
    private int shanhaiCountConfigured(@NotNull MEStockingPatternBufferPartMachine.ExportOnlyAEStockingItemList handler) {
        int count = 0;
        final int slots = Math.min(handler.getConfigurableSlots(), ShanhaiStockConfigLayout.CONFIG_SIZE);
        for (int i = 0; i < slots; i++) {
            final IConfigurableSlot slot = handler.getConfigurableSlot(i);
            if (slot != null && slot.getConfig() != null) {
                count++;
            }
        }
        return count;
    }

    /** 迁移日志只打一次（{@code legacy < 0} = 早就迁过/我们已有配置）。 */
    private void shanhaiLogMigrationOnce(@NotNull String where, int legacy, int copied) {
        if (this.shanhaiStockMigrationLogged) {
            return;
        }
        this.shanhaiStockMigrationLogged = true;
        if (legacy < 0) {
            ShanhaiStellarLog.info("库存输入迁移（{}）：我们的 160 格已有配置，跳过迁移（幂等）", where);
        } else if (legacy == 0) {
            ShanhaiStellarLog.info("库存输入迁移（{}）：旧 32 格没有配置，无需迁移（已标记 once）", where);
        } else {
            ShanhaiStellarLog.info("库存输入迁移（{}）：旧 32 格读出 {} 条 ⇒ 已灌入 160 格 {} 条（once 标记已置）",
                    where, legacy, copied);
        }
    }

    // ---------------------------------------------------------------- ⑦ 旧 32 条**流体**配置迁移（幂等）

    /**
     * <b>旧存档迁移（流体侧）</b>：父类那 32 格流体配置 ⇒ 我们的 160 格（只做一次）。
     *
     * <p>与物品侧 {@link #shanhaiMigrateLegacyStockConfigs(String)} <b>逐段同构</b>：同一个闸门纯函数
     * （{@link ShanhaiStockConfigLayout#migrationAction(boolean, int, int)}）、同一套「何时置 once /
     * 何时<b>刻意不置</b>」处置、同一套「日志只打一次」。差别只有三处：目标 handler 是
     * {@link #shanhaiStockFluidHandler}、来源是父类 {@code stockFluidHandler}、判定用 {@link AEFluidKey}。
     *
     * <h2>🔴 为什么这里<b>不需要</b>任何 NBT 键名</h2>
     * 迁移发生在 {@code loadCustomPersistedData} 的末尾 —— 那一刻 LDLib 已经把
     * <b>两份 handler 都就地还原好了</b>（{@code loadManagedPersistentData} 先
     * {@code IManagedAccessor.writePersistedFields(tag, getPersistedFields())}
     * 再调 {@code loadCustomPersistedData(tag)}，{@code javap -c} 实证，见报告 §A.3）。
     * ⇒ 我们直接<b>读对象</b>（{@code stockFluidHandler.getConfigurableSlot(i).getConfig()}）就行，
     * 不用碰 raw tag ⇒ <b>不存在「猜键名猜错 = 静默不迁移」这一类失败</b>。
     *
     * <h2>🔴 槽序是<b>非 1:1 槽序</b>（空槽压缩，与物品侧同款）</h2>
     * 搬过去的是「旧槽号升序<b>压紧</b>后的第 {@code i} 条」，<b>不是</b>「旧第 {@code i} 格」：
     * 旧 32 格里第 0..4 格空、第 5 格有配置 ⇒ 它落在我们第 <b>0</b> 格。只有「相对先后顺序」被保留
     * （旧 32 格本来就允许跳格配置，原样占位会把后面的配置全推到高位）。
     * 所以<b>不要</b>拿旧槽号去新面板里找同号格：那是 1:1 假设，这里不成立。
     *
     * <h2>幂等</h2>
     * 三条闸：① {@link #shanhaiStockFluidMigrated}（落盘的 once 标记，删光配置也不会「幽灵复活」）；
     * ② 我们的 160 格已经有配置 ⇒ 直接认定迁过；③ 父类 32 格没有任何配置 ⇒ 认定迁过（新机器）。
     *
     * <h2>绝不静默丢</h2>
     * 旧配置条数 &gt; 160 时打 WARN 写明条数（实际上父类只有 32 格，不可能触发，
     * 但这段是为了「将来有人把 32 改大」时不静默）。
     */
    private void shanhaiMigrateLegacyStockFluidConfigs(@NotNull String where) {
        if (this.shanhaiStockFluidHandler == null) {
            return;
        }
        if (this.stockFluidHandler == null) {
            return;
        }
        final int ourConfigured = this.shanhaiCountConfiguredFluid(this.shanhaiStockFluidHandler);
        final int legacyConfigured = this.shanhaiCountConfiguredFluid(this.stockFluidHandler);
        // 🔴 闸门只在 ShanhaiStockConfigLayout.migrationAction 一处（纯函数，探针实跑真值表）
        final ShanhaiStockConfigLayout.MigrationAction action =
                ShanhaiStockConfigLayout.migrationAction(this.shanhaiStockFluidMigrated, ourConfigured, legacyConfigured);
        switch (action) {
            case SKIP_ALREADY_MIGRATED -> {
                return;
            }
            case SKIP_ALREADY_CONFIGURED -> {
                this.shanhaiStockFluidMigrated = true;
                this.shanhaiStockFluidHandler.onConfigChanged();
                this.shanhaiRebuildStockFluidKeys();
                this.shanhaiLogFluidMigrationOnce(where, -1, 0);
                return;
            }
            case NOTHING_TO_COPY -> {
                // 不置 once 标记！onLoad 可能早于 loadCustomPersistedData（见 MigrationAction#NOTHING_TO_COPY）
                this.shanhaiLogFluidMigrationOnce(where, 0, 0);
                return;
            }
            default -> {
                // COPY：继续往下
            }
        }
        final int legacySize = this.stockFluidHandler.getConfigurableSlots();
        final List<GenericStack> legacy = new ArrayList<>();
        for (int i = 0; i < legacySize; i++) {
            final IConfigurableSlot slot = this.stockFluidHandler.getConfigurableSlot(i);
            final GenericStack config = slot == null ? null : slot.getConfig();
            if (config != null && config.what() instanceof AEFluidKey) {
                legacy.add(config);
            }
        }
        int copied = 0;
        // ⚠️ 非 1:1 槽序（见本方法 javadoc）：这里是「压紧后的第 i 条 ⇒ 我们第 i 格」，
        //    中间的旧空槽被挤掉，所以目标格号 ≠ 旧槽号。
        for (int i = 0; i < legacy.size() && i < ShanhaiStockConfigLayout.CONFIG_SIZE; i++) {
            final IConfigurableSlot target = this.shanhaiStockFluidHandler.getConfigurableSlot(i);
            if (target == null) {
                continue;
            }
            if (target instanceof IMESlot silent) {
                // 不触发逐格回调（160 次回调会让 syncStockInput 跑 160 遍）
                silent.setConfigWithoutNotify(legacy.get(i));
            } else {
                target.setConfig(legacy.get(i));
            }
            copied++;
        }
        this.shanhaiStockFluidHandler.onConfigChanged();
        this.shanhaiRebuildStockFluidKeys();
        this.shanhaiStockFluidMigrated = true;
        this.shanhaiLogFluidMigrationOnce(where, legacy.size(), copied);
        if (legacy.size() > copied) {
            ShanhaiStellarLog.warn("流体库存输入迁移被截断：旧配置 {} 条，只灌入 {} 条（新上限 {}）",
                    legacy.size(), copied, ShanhaiStockConfigLayout.CONFIG_SIZE);
        }
    }

    /**
     * 数一份<b>流体</b> handler 上有几条配置（只用 public API）。
     *
     * <p>父类那 32 格与我们的 160 格<b>共用这一处</b>，否则「闸门怎么数」与「日志怎么数」
     * 会长成两份可能漂移的实现。
     */
    private int shanhaiCountConfiguredFluid(
            @NotNull MEStockingPatternBufferPartMachine.ExportOnlyAEStockingFluidList handler) {
        int count = 0;
        final int slots = Math.min(handler.getConfigurableSlots(), ShanhaiStockConfigLayout.CONFIG_SIZE);
        for (int i = 0; i < slots; i++) {
            final IConfigurableSlot slot = handler.getConfigurableSlot(i);
            if (slot != null && slot.getConfig() != null) {
                count++;
            }
        }
        return count;
    }

    /** 流体迁移日志只打一次（{@code legacy < 0} = 早就迁过/我们已有配置）。 */
    private void shanhaiLogFluidMigrationOnce(@NotNull String where, int legacy, int copied) {
        if (this.shanhaiStockFluidMigrationLogged) {
            return;
        }
        this.shanhaiStockFluidMigrationLogged = true;
        if (legacy < 0) {
            ShanhaiStellarLog.info("流体库存输入迁移（{}）：我们的 160 格已有配置，跳过迁移（幂等）", where);
        } else if (legacy == 0) {
            ShanhaiStellarLog.info("流体库存输入迁移（{}）：旧 32 格没有配置，无需迁移（按设计不置 once 标记）", where);
        } else {
            ShanhaiStellarLog.info("流体库存输入迁移（{}）：旧 32 格读出 {} 条 ⇒ 已灌入 160 格 {} 条（once 标记已置）",
                    where, legacy, copied);
        }
    }

    /**
     * 载入存档的最后一跳：先让 LDLib 把两份 handler 都还原，再做一次性迁移。
     *
     * <p>⚠️ 父类<b>没有</b>覆写本方法（{@code javap -p} 实证：MEStocking / MEPatternBufferPartMachine /
     * Base 三个类里都没有 {@code saveCustomPersistedData} / {@code loadCustomPersistedData}）⇒
     * {@code super} 走的是 {@code MetaMachine} 的实现，不会把任何东西写坏。
     */
    @Override
    public void loadCustomPersistedData(CompoundTag tag) {
        super.loadCustomPersistedData(tag);
        this.shanhaiMigrateLegacyStockConfigs("loadCustomPersistedData");
        // 流体侧同一支：父类那 32 格流体配置 ⇒ 我们的 160 格（两侧都读对象，不碰 raw tag）
        this.shanhaiMigrateLegacyStockFluidConfigs("loadCustomPersistedData");
    }

    // ---------------------------------------------------------------- ⑦ 页面 + 取证接口

    /** ⑦ 的分页回调（{@link AEDualConfigWidget} 翻页时调；越界一律夹住）。 */
    private void shanhaiSetStockPage(int page) {
        this.shanhaiStockPage = Math.max(1, Math.min(ShanhaiStockConfigLayout.PAGE_COUNT, page));
    }

    /**
     * <b>⑦ 的取证接口</b>（「旧存档 32 条还在」这条判据要用它）。
     *
     * <p>一行一格、格式稳定便于 grep：
     * <pre>
     *   [SHANHAI-STELLAR] STOCKCFG where=onLoad slot=7 config={...} stock={...}
     *   [SHANHAI-STELLAR] STOCKCFG-SUMMARY where=onLoad configured=32 migrated=true page=1/10 slots=160
     * </pre>
     */
    public void shanhaiDumpStockConfig(@NotNull String where) {
        if (this.shanhaiStockItemHandler == null) {
            ShanhaiStellarLog.warn("STOCKCFG where={} 我们的 160 格还没建（构造器没跑完？）", where);
            return;
        }
        int configured = 0;
        for (int i = 0; i < ShanhaiStockConfigLayout.CONFIG_SIZE; i++) {
            final IConfigurableSlot slot = this.shanhaiStockItemHandler.getConfigurableSlot(i);
            final GenericStack config = slot == null ? null : slot.getConfig();
            if (config == null) {
                continue;
            }
            configured++;
            ShanhaiStellarLog.info("STOCKCFG where={} slot={} page={} config={} stock={}",
                    where, i, ShanhaiStockConfigLayout.pageOf(i), config,
                    slot.getStock());
        }
        ShanhaiStellarLog.info("STOCKCFG-SUMMARY where={} configured={} legacy={} migrated={} page={}/{} {}",
                where, configured, this.shanhaiCountConfigured(this.stockItemHandler),
                this.shanhaiStockMigrated, this.shanhaiStockPage, ShanhaiStockConfigLayout.PAGE_COUNT,
                ShanhaiStockConfigLayout.describe());
    }

    // ---------------------------------------------------------------- 阶段划分（本轮之后）

    // TODO(阶段三)：① 三道闸 + ④ 神锻样板模式。
    //   闸的第一件事就是让通配符槽真的进 AE 的槽号空间，也就是把阶段二【刻意】没做的三件事补上：
    //     ① 覆写 getInternalSlotCount() → maxPatternCount + 通配符槽实际有样板数；
    //     ② 覆写 getInternalSlot(int) → 越界段分派到自己的 InternalSlot 列表；
    //     ③ 覆写 onPatternChange(int) 对通配符槽号【短路】（父类那一行是 `internalInventory[slot]` aaload，
    //        没有边界检查 —— 这是唯一会 AIOOBE 的入口，javap 偏移 9/13）。
    //   ⚠️ 不许照抄「超级通配符样板总成」的展开逻辑就完事：那台的槽位数写死 10、且它继承的是更上层的 Base；
    //      本机的通配符槽必须和 1080 个普通槽共用同一个 InternalSlot 空间才会被 ME 一起看到
    //      （上游 RecipeTypePatternBufferPartMachine 3450 行做的就是这件事，行号 1628/1734/1741）。
    //   ④ 神锻样板模式：复用本工程现有那套反射（ShanhaiForgePatternMode / ShanhaiAutoForgeMultiplierDriver），
    //      **不要**抄上游的「产出倍率」语义。
    //
    // TODO(阶段四)：⑦ 库存输入页 8×2×10 ＋ ⑧ 标签过滤输入页 8×2×10（两页各 160 格）。
    //   库存那页的底座是父类的 stockItemHandler/stockFluidHandler（32+32），
    //   要先把它们扩到 160 格才谈得上「8×2×10」；标签过滤页本机<层>一点现成的都没有，属新写。
    //
    // TODO(阶段五)：⑥ 输出/返还 —— 已大半继承（buffer → Ticker → AEUtils.reFunds），
    //   本刀只需补「buffer 持久化」+「产物真的回到 ME 的验法」。
    //   验收判据：往 buffer 里塞产物 → 保存退出 → 重进 → 产物应当还在（或已退款，二者其一）。
    //
    // TODO(阶段二收尾，未做)：把类型日志开关挂到命令上（现在只能靠 -Dshanhai.stellar.typeLog=true）。
    //   要动命令注册入口（现有文件），按任务书「只新增文件」的约束本轮没做。
}
