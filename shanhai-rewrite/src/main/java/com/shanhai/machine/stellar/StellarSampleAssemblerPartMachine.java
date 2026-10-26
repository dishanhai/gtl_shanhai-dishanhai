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
import com.gregtechceu.gtceu.api.machine.trait.IRecipeHandlerTrait;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableFluidTank;
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
import com.shanhai.api.machine.IShanhaiNormalSupplyPart;
import com.shanhai.api.machine.ShanhaiSupplyOnlyFluidHandler;
import com.shanhai.api.machine.ShanhaiSupplyOnlyItemHandler;
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
        implements AutoForgeMultiplierHost, IShanhaiNormalSupplyPart,
                com.shanhai.api.machine.ShanhaiPatternTypeBlockSource {

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

    /**
     * ④ 神锻样板模式开关。
     *
     * <p>🔴 <b>2026-10-25 用户点单：默认【开】</b>（原话「星律样板总成的神锻模式也默认开启」）。
     * 开了只改「对 AE 声明的产出」，不动宿主产量语义。
     *
     * <p>⚠️ 因为字段是 {@code @Persisted}，**已经存在的那台**读档时仍会用它自己存档里的值（可能是关）
     * ⇒ 老机器需要在面板上手动点一下「神锻模式」；本改动只影响新建/未写档的机器。
     */
    @DescSynced
    @Persisted
    private boolean forgePatternMode = true;

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
     * <b>C2（2026-10-27）：第二张 identity 表已删除，只留 {@link #expandedToSlot} 一份登记。</b>
     *
     * <p>改前是「raw 与 effective 两份都登记 + 另有一张 rewrittenToSlot 身份表」⇒ 同一个槽号
     * 在两张 identity 表里各有一份键，AE 手里拿到的可能是 raw（上一次的实例）也可能是 effective
     * —— <b>这正是"两个池对同一条配方"的结构性入口</b>（用户口径：一个池只对应一条配方）。
     * <p>现在：<b>AE 拿到的（{@link #effectivePatterns} 的元素）= {@link #expandedToSlot} 的键</b>，
     * 一一对应、只有一份。{@code rewritten} 的条数改由 {@link #shanhaiForgedCount} 记。
     */
    private int shanhaiForgedCount;

    /**
     * <b>C4：读路径永不重建 —— 重建时机与次数记在这里。</b>
     *
     * <p>纯逻辑核见 {@link ShanhaiPatternRebuildPolicy}（离线探针可跑正向/负对照）。
     * 机器侧只在三个事件点 + {@code update()} 跳消费本策略的脏标记。
     */
    private final ShanhaiPatternRebuildPolicy shanhaiRebuildPolicy = new ShanhaiPatternRebuildPolicy();

    /**
     * <b>C3：通配符展开槽号的动态分配器</b>（原版无上限；K=128 截断已删除）。
     *
     * <p>槽号 = {@link ShanhaiWildcardLayout#EXPAND_OFFSET} + 本分配器发放的"相对下标"；
     * {@link #getInternalSlotCount()} 因此是<b>动态</b>的（= 1130 + 已分配数）。
     */
    private final ShanhaiWildcardSlotAllocator shanhaiSlotAllocator =
            new ShanhaiWildcardSlotAllocator(ShanhaiWildcardLayout.MOTHER_COUNT);

    /** 槽 → 该槽声明的类型 id（per-slot 类型闸的缓存；{@link #shanhaiInvalidateGateCache()} 清）。 */
    private final java.util.Map<Integer, List<String>> shanhaiSlotDeclaredTypeCache = new java.util.HashMap<>();

    /** 闸缓存里顺带算出来的：本机不支持的那个"样板声明类型"（{@code null} = 没有）。 */
    private String shanhaiUnsupportedDeclaredType;

    /** 闸缓存里顺带算出来的：有未声明类型的样板正被闸拦着。 */
    private boolean shanhaiUndeclaredBlocked;

    /** TYPEROUTE 槽级判据行的限流（同形 20 tick 一条）。 */
    private String shanhaiSlotGateLogState = "";

    private long shanhaiSlotGateLogTick = Long.MIN_VALUE;

    /**
     * 🔴 <b>AE 看到的那份清单 = 这个字段本身（的只读视图），不重建、不拷贝元素。</b>
     *
     * <h2>为什么必须是「一个字段 + 一次遍历」</h2>
     * 原版 gtlcore 的 {@code MEWildcardPatternBufferPartMachine#getAvailablePatterns}
     * （{@code javap -c} 偏移 0–7）= {@code Collections.unmodifiableList(this.expandedPatterns)}，
     * 而 {@code expandedPatterns} 与 {@code patternToSlotMap} 是<b>同一个循环</b>里一起填的
     * （{@code refreshPatterns} 偏移 35–146）⇒「AE 手里那个对象」<b>永远</b>等于「认领表里的键」，
     * 孤儿状态在结构上不存在。
     *
     * <p>本类原来的三层拼接（闸 → 展开 → 神锻；两趟遍历 + 两张脏标记 + 两张 identity 表）破掉了这个等式：
     * 展开表一重建，AE 手里的实例立刻失效 ⇒ {@code pushPattern} 恒 false
     * （实机读数 {@code RECIPE claim … ok=false reason=patternNotOurs}）。
     *
     * <p>现在照那个形状合并：{@link #shanhaiRebuildPatternTables()} <b>一次遍历</b>同时产出
     * 「{@link #expandedToSlot} 的键」与「本清单的元素」——两者是<b>同一批实例</b>。
     */
    private final List<IPatternDetails> effectivePatterns = new ArrayList<>();

    /**
     * {@link #effectivePatterns} 的只读视图 —— <b>构造期建一次</b>，之后只换内容不换对象。
     *
     * <p>与上游同款语义（它们每次 new 一个 view、元素集不变）。这里连 view 都只建一次，
     * 于是「AE 两次拿到的清单是不是同一个对象」也变成可断言的（纯逻辑探针 ③ 就断言这一点）。
     */
    private final List<IPatternDetails> effectivePatternView =
            java.util.Collections.unmodifiableList(this.effectivePatterns);

    /**
     * 重建请求标记 —— 与 {@link #expandCacheDirty} <b>合流</b>：任一为 true 都整体重建一次。
     *
     * <p>保留本字段是为了不动既有的置脏点（2034 / 3719 / 3745 / 4065 / 4630 / 4747 / 5017 / 5552 等）：
     * 它们原来只置「神锻快照脏」，现在等价于「整表脏」。两张标记在
     * {@link #shanhaiRebuildPatternTables()} 末尾<b>一起清零</b>。
     */
    private boolean forgeCacheDirty = true;

    /** 当前这次 {@link #getAvailablePatterns()} 是否由 AE 亲自发问（给 EXPAND 判据算 {@code aeSync=}）。 */
    private boolean shanhaiInAePatternQuery = false;

    /** EXPAND 判据的指纹 + 打点（同一指纹 20 tick 内只打一行）。 */
    private String shanhaiExpandLogState = "";
    private long shanhaiExpandLogTick = Long.MIN_VALUE;
    /**
     * <b>展开槽 → 该样板已预解析并绑定的 GTRecipe</b>（J7 的落地表）。
     *
     * <h2>为什么需要第二张表（而不只是交给父类的槽缓存）</h2>
     * 父类那份槽缓存是 {@code protected final boolean[] cacheRecipe}（长度 = 1080）+
     * {@code Int2ReferenceMap}，下标一越过 {@link ShanhaiWildcardLayout#PATTERN_SLOTS} 就 AIOOBE
     * （{@link ShanhaiMEPatternTrait#setSlotCacheRecipe} 原来就是因此对通配符段直接 {@code return}）。
     * ⇒ 展开槽的"已绑定配方"只能另存一份；本表与 {@link #expandedToSlot} <b>同一次遍历、同一个脏标记</b>重建，
     * 生命周期完全一致（不存在"认领表换了、绑定还是旧的"）。
     *
     * <h2>它怎么进候选表</h2>
     * {@code MEPatternRecipeHandlePart#getCachedGTRecipe()}（= {@link ShanhaiMEPatternTrait#getCachedGTRecipe()}
     * 的覆写）把它并进去 ⇒ gtladditions 的 {@code GTRecipeLookupMixin#getRecipeIterator} 偏移 93 会收集它、
     * 偏移 140–147 交给 {@code IAdditionalRecipeIterator.setAdditionalRecipes(...)}。
     * <p>⚠️ <b>但它在候选顺序里是"最后"</b>：{@code RecipeIteratorMixin#hasNext()} 偏移 0–62 先把
     * 原候选（= 控制器当前激活类型）跑完，<b>偏移 65–69 才轮到 additional</b>。⇒ 绑定只保证"这条配方在候选里"，
     * 不保证"它先被选中"。要强行抢先，唯一在仓室侧可达的闸是
     * {@code MEPatternBufferRecipeHandlerTraitBase$MEItemInputHandlerBase#meHandleRecipeInner} 的槽级类型过滤
     * （= 老山海闸① {@code MEPatternBufferItemRecipeTypeFilterMixin}），那要经
     * {@code createRecipeHandler(IO)} → {@code createMEItemHandler(IO)} 换掉内层 handler，
     * 本轮<b>没有</b>做（理由与代价见 {@code temp\crossrecipe-impl\报告.md} 的「没做到」一节）。
     */
    private final java.util.Map<Integer, com.gregtechceu.gtceu.api.recipe.GTRecipe> shanhaiSlotBoundRecipe =
            new java.util.HashMap<>();

    /** 最近一次重建：成功绑定的条数 / 因类型反查失败被剔掉的条数（给 EXPAND 判据）。 */
    private int shanhaiBoundCount = 0;
    private int shanhaiTypeDroppedCount = 0;

    /** XRECIPE 判据的指纹 + 打点（同槽号同结果 20 tick 内只打一行）。 */
    private String shanhaiXrecipeLogState = "";
    private long shanhaiXrecipeLogTick = Long.MIN_VALUE;
    /** 反查失败 WARN / identity 闸 WARN 的节流指纹（各一条，按母槽|类型 / 槽|类型对）。 */
    private String shanhaiBindWarnState = "";
    private String shanhaiGateWarnState = "";

    // ---------------------------------------------------------------- 阶段五：展开器字段

    /**
     * 通配符<b>展开槽</b>（C3 起数量 = 分配器水位 = 实际展开条数；不再有编译期上界）。
     *
     * <p>🔴 每条展开一份独立的 {@code InternalSlot} ⇒ 各自有自己的 itemInventory / fluidInventory /
     * 催化剂数组 ⇒ {@code pushPattern(stacks)} 只写进自己那一格 ⇒ **4 条展开不可能互相串料**。
     * 这正是需求①「防串配方」在物料层的落点（纯算术部分见 {@link ShanhaiWildcardLayout} 类注释 §四）。
     */
    /**
     * 通配符<b>展开槽</b>的 InternalSlot（下标 = 槽号 - {@link ShanhaiWildcardLayout#EXPAND_OFFSET}）。
     *
     * <h2>🔴 为什么是"引用数组 + 懒创建"而不是"全量 new"（2026-10-26，K 从 4 提到 128 的代价）</h2>
     * K=4 时这一段是 200 格，构造器里全量 new 无所谓；<b>K=128 就是 6400 格</b>：
     * <pre>
     *   一个 StockingPatternBufferInternalSlot 的组成（{@code javap -p} 逐字段核过）：
     *      2 × Object2LongOpenHashMap（fastutil 默认 16 容量：Object[16] + long[16]）
     *      4 × ObjectOpenHashSet     （Object[16]）
     *      2 × Object2LongArrayMap   （空时只有两个 null 引用）
     *      1 × SlotCacheManager      （内含 2 个 Object2ObjectOpenHashMap）
     *   ⇒ 无头实测 ≈ 2.82 KB / 个（temp\stellar-expand\probe\MemoryProbe.java）
     *   全量 new 6400 个 ≈ 17.2 MB（每台机器！）  ← 这才是真正的"撑爆"
     * </pre>
     * 而这些槽在绝大多数机器上是<b>一个都用不到</b>的（没插通配符样板 = 展开条数 0）。
     * ⇒ 改成「槽号空间是固定上界、对象按需生成」：槽号仍然只由 (母槽, 分支) 决定，
     * <b>不漂移</b>；只是<b>没被用到的那些格不占内存</b>。
     *
     * <h2>⚠️ 懒创建的前提：不许有任何人"按 getInternalSlotCount() 裸扫"</h2>
     * 只要有一个循环从 0 扫到 {@code getInternalSlotCount()} 再调 {@link #getInternalSlot(int)}，
     * 6400 个对象就会在一次扫里全被造出来（懒创建等于白做）。所以本类把父类那 4 个
     * "扫全部槽"的方法<b>全部覆写</b>成「只扫 {@link #shanhaiCandidateSlots()}」：
     * {@link #getActiveSlots()} / {@link #getActiveAndUnCachedSlots()} /
     * {@link #getActiveInternalSlots()} / {@link #getMergedInternalSlot()}
     * （前两个在配方查找里是热路径，见 {@link #shanhaiCandidateSlots()} 的注释）。
     */
    /**
     * 🔴 <b>C3（2026-10-27）：数组按"已分配数"增长，不再有 6400 的编译期上界。</b>
     *
     * <p>改前长度 = {@code EXPAND_COUNT}（50×K=128=6400）。现在 K 没了 ⇒
     * 展开条数就是"这块母样板展开出几条"，数组随 {@link #shanhaiSlotAllocator} 的水位增长；
     * 元素仍然<b>懒创建</b>（{@link #shanhaiExpandSlotAt(int)} 第一次被问才 new）。
     */
    private MEPatternBufferPartMachineBase.InternalSlot[] expandInternalSlots =
            new MEPatternBufferPartMachineBase.InternalSlot[16];

    /** 已经被懒创建出来的展开槽个数（诊断用；不是槽号上界）。 */
    private int shanhaiExpandSlotsMaterialized = 0;

    /**
     * 懒创建/取一个展开槽的 InternalSlot（<b>唯一</b>的创建点）。
     *
     * @param index 0 .. 分配器已分配数-1（C3：动态，见 {@link ShanhaiWildcardSlotAllocator}）
     */
    private @NotNull MEPatternBufferPartMachineBase.InternalSlot shanhaiExpandSlotAt(int index) {
        if (index < 0) {
            throw new IndexOutOfBoundsException("展开槽相对下标不能为负：" + index);
        }
        if (index >= this.expandInternalSlots.length) {
            // 只在"真的分配出这么多条展开"时增长（分配器水位 = 已分配数）。
            this.expandInternalSlots = java.util.Arrays.copyOf(this.expandInternalSlots,
                    Math.max(index + 1, this.expandInternalSlots.length * 2));
        }
        MEPatternBufferPartMachineBase.InternalSlot slot = this.expandInternalSlots[index];
        if (slot == null) {
            // 与母槽同一套：空回调（只保证 getOnContentsChanged() 非 null），绝不接父类那套
            // recipe-cache 失效逻辑（它会写 cacheRecipe[slot]，而那个数组只有 1080 长）。
            slot = this.createInternalSlot(ShanhaiWildcardLayout.EXPAND_OFFSET + index);
            slot.setOnContentsChanged(() -> { });
            this.expandInternalSlots[index] = slot;
            this.shanhaiExpandSlotsMaterialized++;
        }
        return slot;
    }

    /**
     * <b>C3：回收一个展开槽（展开条数变少 / 母样板被拿走时）</b>。
     *
     * <p>为什么必须清空：槽号会被<b>后一次</b>重建里的别的母槽复用（见
     * {@link ShanhaiWildcardSlotAllocator} 的"先复用再抬高水位"）—— 池里残留的料如果不清，
     * 复用者一上来就带着别人的库存 ⇒ <b>这才是真正的"串料"</b>。
     * <p>做法：把 item/fluid 两份库存 map 清空、把内部对象<b>丢掉</b>（下次用到时重新 new），
     * 并把 {@code hasPatternInSlot} 依赖的展开计数交给分配器（那边已经置 -1）。
     */
    private void shanhaiDropExpandSlot(int index) {
        if (index < 0 || index >= this.expandInternalSlots.length) {
            return;
        }
        final MEPatternBufferPartMachineBase.InternalSlot slot = this.expandInternalSlots[index];
        this.expandInternalSlots[index] = null;
        if (slot == null) {
            return;
        }
        try {
            slot.getItemInventory().clear();
        } catch (Throwable ignored) {
        }
        try {
            slot.getFluidInventory().clear();
        } catch (Throwable ignored) {
        }
        try {
            slot.clearVirtualSupply();
        } catch (Throwable ignored) {
        }
        this.shanhaiExpandSlotsMaterialized--;
    }

    /**
     * <b>需要被"遍历"的槽号</b> = {@code [0, 1130)} 全段 ＋ 当前<b>真正展开出来</b>的那些展开槽。
     *
     * <h2>🔴 为什么必须收窄（不只是省内存，是省时间）</h2>
     * 父类 {@code getActiveSlots()} / {@code getActiveAndUnCachedSlots()} 的实现是
     * {@code int[] slots = new int[getInternalSlotCount()]; for (i < count) …}
     * （{@code temp\machine-principle\out\...\MEPatternBufferPartMachineBase.java:398/412} 逐行核过）：
     * <ul>
     *   <li>K=4（旧）：1330 个下标 ⇒ 每次调用 {@code new int[1330]}（5.3 KB）＋ 1330 次虚调用；</li>
     *   <li>K=128 但照旧裸扫：7530 个下标 ⇒ {@code new int[7530]}（<b>30 KB</b>）＋ 7530 次虚调用；</li>
     * </ul>
     * 而 {@code getActiveAndUnCachedSlots()} 是<b>配方查找</b>的入口
     * （{@code MEPatternBufferRecipeHandlerTraitBase:201} → {@code GTRecipeLookupMixin#gtlCore$fromHolder:144}），
     * 一 tick 里会被问很多次 ⇒ 30 KB/次 的临时数组是实打实的 GC 压力。
     * <p>收窄是<b>语义等价</b>的：被跳过的那些展开槽 {@code hasPatternInSlot(slot)} 恒 false
     * （分支号 ≥ 展开条数）⇒ {@code isActive()} 恒 false ⇒ 本来就不可能进任何活跃名单。
     * <p>顺序仍然是<b>槽号升序</b>（母槽段在前的固定段 → 再按母槽号、分支号升序），与父类一致。
     */
    private int[] shanhaiCandidateSlots() {
        // 🔴 C4：读路径【不重建】（改前这里有一句 `if (expandCacheDirty) rebuild`）。
        //    展开表由事件点 + update() 消费脏标记来建（见 shanhaiBuildPatternTablesIfDirty）。
        final int live = this.shanhaiSlotAllocator.slotCount();
        final int[] out = new int[ShanhaiWildcardLayout.MOTHER_END + live];
        int n = 0;
        for (int slot = 0; slot < ShanhaiWildcardLayout.MOTHER_END; slot++) {
            out[n++] = slot;
        }
        // 🔴 C3：展开段 = 分配器已发放的那些相对下标（槽号升序 = 下标升序）。
        for (int rel = 0; rel < live; rel++) {
            out[n++] = ShanhaiWildcardLayout.EXPAND_OFFSET + rel;
        }
        return out;
    }

    /**
     * 父类「扫全部槽拿活跃名单」那一套的收窄版（见 {@link #shanhaiCandidateSlots()}）。
     *
     * <p>⚠️ 覆写 protected 方法在这条继承链上是合法的：{@code getActiveSlots()} /
     * {@code getActiveAndUnCachedSlots()} / {@code getActiveInternalSlots()} 在
     * {@code MEPatternBufferPartMachineBase} 里都是 {@code protected}、非 final
     * （{@code javap -p} 实证）⇒ 动态分派生效，trait 侧
     * {@code MEPatternBufferRecipeHandlerTraitBase.getMachine().getActiveSlots()} 拿到的就是本实现。
     *
     * <p>🔴 真正的覆写点在下面「库存直供放行」那一节（{@code getActiveSlots()} /
     * {@code getActiveAndUnCachedSlots()} 已各有一份、要带上放行名单）；这里只提供
     * <b>「扫候选集」的那半段</b>，让它们把 {@code super.xxx()} 换成它。
     */
    private int[] shanhaiActiveAndUnCachedCandidates() {
        final int[] candidates = this.shanhaiCandidateSlots();
        final int[] slots = new int[candidates.length];
        int size = 0;
        for (int slot : candidates) {
            if (this.getInternalSlot(slot).isActive() && !this.hasRecipeCacheInSlot(slot)) {
                slots[size++] = slot;
            }
        }
        return size == slots.length ? slots : java.util.Arrays.copyOf(slots, size);
    }

    /** {@link #getActiveSlots()} 的"扫候选集"部分（= 父类实现，只是不扫那 6400 个空展开槽）。 */
    private int[] shanhaiCandidateActiveSlots() {
        final int[] candidates = this.shanhaiCandidateSlots();
        final int[] slots = new int[candidates.length];
        int size = 0;
        for (int slot : candidates) {
            if (this.getInternalSlot(slot).isActive()) {
                slots[size++] = slot;
            }
        }
        return size == slots.length ? slots : java.util.Arrays.copyOf(slots, size);
    }

    /**
     * 活跃 InternalSlot 的迭代视图（父类 {@code getMergedInternalSlot()} 用它做 Jade 合并显示）。
     *
     * <p>覆写理由同上：父类版本会从 0 扫到 {@code getInternalSlotCount()} ⇒ 懒创建全废。
     */
    @Override
    protected Iterable<MEPatternBufferPartMachineBase.InternalSlot> getActiveInternalSlots() {
        final int[] candidates = this.shanhaiCandidateSlots();
        final List<MEPatternBufferPartMachineBase.InternalSlot> slots = new ArrayList<>(candidates.length);
        for (int slot : candidates) {
            final MEPatternBufferPartMachineBase.InternalSlot internal = this.getInternalSlot(slot);
            if (internal.isActive()) {
                slots.add(internal);
            }
        }
        return slots;
    }

    /**
     * Jade 的合并视图（活跃槽的料 + <b>全部槽</b>的催化剂）。
     *
     * <p>父类版本第二段是 {@code for (slotIndex < getInternalSlotCount())} ⇒ 7530 次
     * {@code getInternalSlot()}（懒创建下会把 6400 个对象全造出来）⇒ 必须覆写。
     * 语义不变：只有 {@code [0, 1130)} 与真正展开出来的槽可能有催化剂内容。
     *
     * <p>⚠️ 上游的 {@code MEPatternBufferJadeMerger} 是<b>包私有</b>类（{@code javap -p}：
     * {@code final class …} 且方法无 {@code public}）⇒ 本类跨包拿不到，只能在这里
     * <b>逐行等价复刻</b>它那四个 static 方法（{@code amount > 0} 才累加、键取
     * {@code AEItemKey#getItem()} / {@code AEFluidKey#getFluid()}）。改上游语义时这里要跟着改。
     */
    @Override
    public it.unimi.dsi.fastutil.Pair<it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap<net.minecraft.world.item.Item>,
            it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap<net.minecraft.world.level.material.Fluid>>
            getMergedInternalSlot() {
        final it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap<net.minecraft.world.item.Item> items =
                new it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap<>();
        final it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap<net.minecraft.world.level.material.Fluid> fluids =
                new it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap<>();
        for (MEPatternBufferPartMachineBase.InternalSlot slot : this.getActiveInternalSlots()) {
            shanhaiMergeItemKeys(items, slot.getItemInventory());
            shanhaiMergeFluidKeys(fluids, slot.getFluidInventory());
        }
        for (int slotIndex : this.shanhaiCandidateSlots()) {
            final MEPatternBufferPartMachineBase.InternalSlot slot = this.getInternalSlot(slotIndex);
            shanhaiMergeItemKeys(items, slot.getItemCatalystInventory());
            shanhaiMergeFluidKeys(fluids, slot.getFluidCatalystInventory());
        }
        shanhaiMergeItemStacks(items, this.sharedCatalystInventory.getContents());
        shanhaiMergeFluidStacks(fluids, this.sharedCatalystTank.getContents());
        return it.unimi.dsi.fastutil.Pair.of(items, fluids);
    }

    /** 等价 {@code MEPatternBufferJadeMerger.mergeItemKeys}（包私有，见上）。 */
    private static void shanhaiMergeItemKeys(
            @NotNull it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap<net.minecraft.world.item.Item> target,
            @NotNull it.unimi.dsi.fastutil.objects.Object2LongMap<AEItemKey> source) {
        for (it.unimi.dsi.fastutil.objects.Object2LongMap.Entry<AEItemKey> entry
                : it.unimi.dsi.fastutil.objects.Object2LongMaps.fastIterable(source)) {
            final long amount = entry.getLongValue();
            if (amount > 0L && entry.getKey() != null) {
                target.addTo(entry.getKey().getItem(), amount);
            }
        }
    }

    /** 等价 {@code MEPatternBufferJadeMerger.mergeFluidKeys}（包私有，见上）。 */
    private static void shanhaiMergeFluidKeys(
            @NotNull it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap<net.minecraft.world.level.material.Fluid> target,
            @NotNull it.unimi.dsi.fastutil.objects.Object2LongMap<AEFluidKey> source) {
        for (it.unimi.dsi.fastutil.objects.Object2LongMap.Entry<AEFluidKey> entry
                : it.unimi.dsi.fastutil.objects.Object2LongMaps.fastIterable(source)) {
            final long amount = entry.getLongValue();
            if (amount > 0L && entry.getKey() != null) {
                target.addTo(entry.getKey().getFluid(), amount);
            }
        }
    }

    /** 等价 {@code MEPatternBufferJadeMerger.mergeItemStacks}。 */
    private static void shanhaiMergeItemStacks(
            @NotNull it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap<net.minecraft.world.item.Item> target,
            @NotNull Iterable<?> source) {
        for (Object value : source) {
            if (value instanceof ItemStack stack && !stack.isEmpty()) {
                target.addTo(stack.getItem(), stack.getCount());
            }
        }
    }

    /** 等价 {@code MEPatternBufferJadeMerger.mergeFluidStacks}。 */
    private static void shanhaiMergeFluidStacks(
            @NotNull it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap<net.minecraft.world.level.material.Fluid> target,
            @NotNull Iterable<?> source) {
        for (Object value : source) {
            if (value instanceof com.lowdragmc.lowdraglib.side.fluid.FluidStack stack && !stack.isEmpty()) {
                target.addTo(stack.getFluid(), stack.getAmount());
            }
        }
    }

    /**
     * 「退款全部」按钮：父类版本从 0 扫到 {@code getInternalSlotCount()}（{@code MEPatternBufferPartMachineBase:223}）。
     *
     * <p>K=128 之后那会是 7530 次 {@code getInternalSlot()} ⇒ 在<b>懒创建</b>下等于"点一下就把 6400 个
     * InternalSlot 全造出来"（≈17.2 MB，且这些格永远不会有料）。所以按候选集扫（0..1129 + 真正展开出来的槽），
     * <b>语义不变</b>：被跳过的展开槽既没样板也没料，父类那版对它们也只是两次空 map 遍历。
     */
    @Override
    protected void refundAll(com.lowdragmc.lowdraglib.gui.util.ClickData clickData) {
        if (!clickData.isRemote) {
            for (int slotIndex : this.shanhaiCandidateSlots()) {
                final MEPatternBufferPartMachineBase.InternalSlot slot = this.getInternalSlot(slotIndex);
                this.refundSlot(slot.getItemInventory(), slot.getFluidInventory());
                slot.clearVirtualSupply();
            }
            org.gtlcore.gtlcore.integration.ae2.AEUtils.reFunds(
                    this.buffer, this.getMainNode().getGrid(), this.actionSource);
        }
    }

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
     * <p>🔴 2026-10-25 起它<b>真的参与拉取</b>：{@link #shanhaiTagPull} 用它过滤 AE 缓存库存，
     * 命中的键写进我们那 160 格（物品）／160 格（流体）的 config（同一对名单同时判物品标签与流体标签）。
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
     * <p>🔴 <b>语义（用户 2026-10-25 拍的互斥规则）：开关 = 开 ⇒ 接管这 160 格</b>。
     * 开 ⇒ 每库存同步周期按白/黑名单从 ME 缓存库存里取命中的键，<b>整体重写</b>我们那两份 160 格的
     * config（并清尾）⇒ 面板上的手配会被覆盖（库存页会显式写「标签接管中」，日志有 {@code TAGPULL} 行）。
     * 关 ⇒ <b>不再覆写</b>，但<b>不清空</b>已经写进去的配置（那 160 格立刻回到可手配状态，理由见
     * {@link #shanhaiToggleTagFilter()}）；连匹配预览都不算。
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

    // ---------------------------------------------------------------- ⑧ 标签命中集合 = 独立供料源（读数与快照）
    //
    // 🔴 2026-10-25 用户原话（纠正上一轮的口径）：
    //    「星律样板总成的标签拉取把库存的位置填了，但是实际上它们应该分开各不干扰」
    //    ⇒ 上一轮那套「开关开 = 接管那 160 格（重写 config + 清尾）」**整体作废**。
    //    现在的形态：标签命中的键集合 = **一个独立的供料源**，由
    //    ShanhaiTagSupplyHandlers.Item / .Fluid 两份 handler 从 `shanhaiNormalSupplyHandlers()` 交出去；
    //    那两份 handler **一次都不碰** `shanhaiStockItemHandler/…FluidHandler` 的 config。
    //    ⇒ 手配的 160 格逐格原样（探针 `E` 组断言），标签关也好开也好都不改它。
    //
    // 下面这些字段全是**派生读数 / 快照**（不落盘）：写进存档只会变成「上次关服时的旧数」。

    /**
     * 标签命中集合的一行读数（服务端算、{@code @DescSynced} 给客户端画在标签页底部）。
     *
     * <p>只在<b>文字真的变了</b>时赋值（见 {@link #shanhaiSetTagPullLine(String)}），不每周期刷同步。
     */
    @DescSynced
    private String shanhaiTagPullLine = "";

    /**
     * 这一轮<b>标签供料处于什么状态</b>：{@code ""} = 还没跑过；{@code ok} = 命中集合有效、这一路在供料；
     * {@code disabled} = 开关关；{@code pending} = 刚开开关、等下一次库存同步；
     * {@code emptyExpression} = 白/黑名单都空；{@code noGrid} = 没连上 ME；
     * {@code noInventory} = 取不到缓存库存；{@code badExpression:…} = 表达式语法错。
     *
     * <p>🔴 它<b>必须 @DescSynced</b>：库存页与标签页那两行文案就是按它算的，而这段算在**客户端**
     * （{@code createUIWidget} 两侧都建控件树）。不同步的话客户端会永远显示旧状态 —— 界面说谎，比不显示更糟。
     * <p>⚠️ 它<b>不是</b>持久化字段：它是"这一刻的处境"，读档重算即可。
     * <p>⚠️ 状态码与上一轮<b>逐字相同</b>（{@link ShanhaiTagPullPlanner.Gate#code()}）⇒ 日志判读表不用改；
     * 变的只是含义：{@code ok} 以前表示「已接管那 160 格」，现在表示「命中集合有效、这一路在独立供料」。
     */
    @DescSynced
    private String shanhaiTagPullSkip = "";

    /** 上一轮打出去的 TAGSUPPLY 状态串（**只**用于「变化了才打一行」，不参与任何判定）。 */
    private String shanhaiTagPullLogState = "";

    /**
     * 标签命中集合的<b>物品</b>键快照（不可变；只在集合真的变了时整份替换）。
     *
     * <p>它是 {@link ShanhaiTagSupplyHandlers.Item} 的唯一数据源 ⇒ <b>getContents()/handleRecipeInner
     * 都现读这一份</b>，不每 tick 重扫 AE。
     */
    private java.util.List<AEItemKey> shanhaiTagSupplyItemKeys = java.util.List.of();

    /** 标签命中集合的<b>流体</b>键快照（同上）。 */
    private java.util.List<AEFluidKey> shanhaiTagSupplyFluidKeys = java.util.List.of();

    /**
     * 命中集合的版本号：集合一变就 +1。
     *
     * <p>两份 handler 用它对 {@code getContents()} 做缓存 —— 实机日志里 {@code getContents()}
     * 被问过 18 万次，每次重建 200+ 个 {@code ItemStack} 是纯浪费。
     */
    private long shanhaiTagSupplyVersion = 0L;

    /** 上一轮算出来的物品命中指纹（{@link ShanhaiTagPullPlanner#signature}；只用于「变了才换快照」）。 */
    private long shanhaiTagSupplyItemSig = 0L;

    /** 上一轮算出来的流体命中指纹（同上）。 */
    private long shanhaiTagSupplyFluidSig = 0L;

    // 最后一轮的读数（面板文字 + 日志判读用；物品侧主，流体侧并列）
    private int shanhaiTagSupplyItemScanned = 0;
    private int shanhaiTagSupplyItemMatched = 0;
    private int shanhaiTagSupplyFluidScanned = 0;
    private int shanhaiTagSupplyFluidMatched = 0;
    private boolean shanhaiTagSupplyItemTruncated = false;
    private boolean shanhaiTagSupplyFluidTruncated = false;

    /**
     * 命中集合的上限（<b>安全阀，不是格子数</b>）。
     *
     * <p>上一轮的 160 是「库存配置有 160 格」带来的；现在这一路不占格子 ⇒ 没有 160 的理由。
     * 但也不能无界：命中几千条时 {@code getContents()} 的快照与逐条匹配都会变贵。
     * 1024 条远超实际（用户这局缓存库存一共 370 项），真超了会在日志里打 {@code truncated=true}。
     */
    private static final int TAG_SUPPLY_MAX = 1024;

    // 供料源自己的打点（SUPPLYCHAIN 判据 + 报告读数）
    private long shanhaiTagSupplyItemHandles = 0L;
    private long shanhaiTagSupplyFluidHandles = 0L;
    private long shanhaiTagSupplyItemContents = 0L;
    private long shanhaiTagSupplyFluidContents = 0L;
    private long shanhaiTagSupplyItemExtracts = 0L;
    private long shanhaiTagSupplyFluidExtracts = 0L;

    /** 给 {@link #shanhaiNormalSupplyHandlers()} 那两份 handler 用的供料源（机器侧实现，只读 + 真抽取）。 */
    private final ShanhaiTagSupplyHandlers.Source shanhaiTagSupplySource = new ShanhaiTagSupplySource();

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

    /**
     * <b>自证探针正在跑</b>（2026-10-25 修一个会把排查带偏的读数）。
     *
     * <p>🔴 病因（实机日志实证）：{@code STOCKSLOT … calls[isItemActive=0 handleItem=0 hasCache=4 …]} 里的
     * {@code hasCache=4} 和 {@code hi[…hasCache=2]} 竟然在「一个活跃槽都没有」时也有值。
     * 逐条对过：那 4 次正是 {@link #shanhaiLogBoundaryProof()} 自己打的
     * （{@code hiFirst / hiLast / loFirst / loLast} 各一次，其中两次槽号 ≥1080 ⇒ {@code hi=2}）。
     * ⇒ 读日志的人会以为「配方匹配问过 4 个槽」，而真相是 {@code handleRecipe} 一次都没跑。
     * <p>它把「诊断」混进了「被诊断的读数」——本工程明令禁止的那一类。修法：探针自己打的那几次
     * <b>不计入</b>生产计数器，日志里另有一个 {@code selfProbe} 字段老实交代「我自己问过几次」。
     */
    private boolean shanhaiInSelfProbe = false;

    /** 自证探针自己对 {@code hasCacheInSlot} 的调用次数（<b>不计入</b>生产计数器，只在日志里自曝）。 */
    private long shanhaiProbeSelfHasCache = 0L;

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
        // ③ 阶段五：展开槽（母槽 i 的第 j 条 ⇒ EXPAND_OFFSET + i*K + j）。
        //    🔴 2026-10-26（K 4⇒128）：这里**不再**预先 new 出 EXPAND_COUNT 个对象。
        //    K=128 ⇒ 6400 个，单个 ≈2.82 KB（实测）⇒ 全量就是 ≈17.2 MB/台机器（而这些格绝大多数永远用不到）。
        //    改成"引用数组（6400 × 4B = 25.6 KB）+ 第一次被问到时懒创建"，
        //    槽号仍然只由 (母槽,分支) 决定 ⇒ **固定上界、槽号不漂移**，阶段三的证明不变。
        //    细节与前提（所有"扫全部槽"的父类方法都必须收窄）见 expandInternalSlots 的注释。
        // ---- 阶段六 ⑦：库存输入 160 格（8×2×10）
        // ① 真正干活的 160 格：**自己 new**（父类那份 32 格还在，见字段注释）。
        //    🔴 构造器签名修正（阶段六实证）：javap 打出来的是
        //    `ExportOnlyAEStockingItemList(MEStockingPatternBufferPartMachine, MetaMachine, int)`，
        //    其中**第一个参数是内嵌类的 encloser 实例**，显式参数只有 `(MetaMachine, int)`。
        //    阶段五报告把它读成 `(MEStocking, MetaMachine, int)` 是误读 —— 以本轮 javac 的报错与
        //    javap 的 `this$0` 字段为准（写错会直接编译失败，不会静默）。
        this.shanhaiStockItemHandler = new ShanhaiStockItemList();
        // ② 流体侧（方案 C）：**自己也 new 一份 160 格**，与物品侧等长 ⇒ 面板直接接它，不需要转发窗口。
        //    javap 依据：ExportOnlyAEStockingFluidList 是 public 内嵌类，构造器 public，
        //    显式参数 `(MetaMachine, int)`（第一参 this$0 由 javac 隐式补齐，与上一行物品侧同形）。
        //    它的抽取/同步都用 this$0.actionSource + this$0.getMainNode() ⇒ 抽料走本机自己的账。
        //    🔴 2026-10-25：类型改成我们的子类（只多计数 + 一条 SUPPLYCHAIN 日志，行为转发 super）。
        this.shanhaiStockFluidHandler = new ShanhaiStockFluidList();
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


    // ================================================================ 2026-10-25：万能仓室供料链的现场计数
    //
    //  用户原话：「什么都不填时预览 133/133（ME 里有料、过滤没挡），但机器不动」，日志
    //  `STOCKSLOT space=1330 active=0 supply=0 calls[isItemActive=0 handleItem=0 …]`。
    //  实机 STOCKPAT 同时给出容器真值 `basePatterns=0 wildcardInv=0`（1330 格里一块样板都没有）
    //  ⇒ 「样板槽 → 库存供料」这条链按设计**不会被调用**（它的入口是样板槽号，见
    //  shanhaiRebuildStockSupplySlots 的入选三条件）⇒ 这条场景下唯一可能生效的是
    //  「万能仓室」那条（{@link #shanhaiNormalSupplyHandlers()} 把 160 格库存交给主机，
  //  连样板都不需要）。
    //
    //  🔴 但那条路上原先**一个读数都没有**：注入日志只说「视图插进去了」，说不出
    //  「主机到底有没有问过这两份 handler」（= ②getContents 报没报出去 / ③handleRecipeInner
    //  有没有被调 / ④被调了 simulate 是否直接放行）。这两个子类的唯一职责就是把它变成读数，
    //  **语义逐字节转发上游**（super 调完就返回），不改任何供料行为。

    /** 万能仓室链：物品侧 handleRecipeInner 被调用次数（③的判据）。 */
    private long shanhaiSupplyItemHandleCalls = 0L;

    /** 万能仓室链：物品侧 getContents 被调用次数（②的判据：>0 说明主机来问过内容物）。 */
    private long shanhaiSupplyItemContentsCalls = 0L;

    private long shanhaiSupplyFluidHandleCalls = 0L;

    private long shanhaiSupplyFluidContentsCalls = 0L;

    /** SUPPLYCHAIN 的节流（100 tick = 5 秒；与 STOCKSLOT 不同节拍，避免两行互相盖住）。 */
    private long shanhaiSupplyLogTick = Long.MIN_VALUE;

    private boolean shanhaiSupplyTraceLogged = false;

    // ---- 2026-10-26 五修：HANDLEPOOL（「我们的 part 到底在哪」）＋ SUPPLYVIEW（「构造期空表」踩没踩到）
    //
    //  取证报告 temp\handlernotcalled\报告.md 的硬结论：handleRecipeInner 的默认实现是纯透传，
    //  calls=0 只能是「含我们 handler 的 RecipeHandlePart 从来没被问」⇒ 必须直接读池子本身。
    //  两个读数都只打日志（ShanhaiHandlePoolProbe 只调 getter，不改任何行为）。

    /** HANDLEPOOL 的等待计数：拿到主机之后再等这么多 tick（让 upDate→rebuildRecipeHandleParts 至少跑完一轮）。 */
    private int shanhaiHandlePoolWait = 0;

    /** HANDLEPOOL 打过没有（每台机只打 1 次；结构成型/拆掉时复位 ⇒ 「成型变化时再打一次」）。 */
    private boolean shanhaiHandlePoolLogged = false;

    /** 视图因为「父类构造器还没跑完」被跳过的次数（SUPPLYVIEW 那一行的节流依据）。 */
    private long shanhaiSupplyViewSkipCount = 0L;

    // ---- 2026-10-25（「stock 恒 0」这一刀）：SYNCSTOCK 的调用计数与节流
    //
    //  用户原话：「星律没修好，机器还是不工作」（而输入框已经能保留了）。
    //  实机 12:33 的三条 SUPPLYCHAIN 只给出结果（物品 cfg=0 stock=0 / 流体 cfg=1 stock=0），
    //  但「stock 为什么是 0」有四种成因（掉线 / extract 被拒 / 写进了另一份 handler / 键不对），
    //  必须由同步链自己在写完之后报读数才能分辨 ⇒ SYNCSTOCK（判读表见 shanhaiLogSyncStock 的 javadoc）。

    /** SYNCSTOCK 打了几次（前 3 次无条件打，之后每 200 tick 一行）。 */
    private long shanhaiSyncStockCalls = 0L;

    /** SYNCSTOCK 的上一次打点游戏时间（初值 0L，不用 Long.MIN_VALUE —— 那个会溢出成负数，见 STOCKFIND 的踩坑记录）。 */
    private long shanhaiSyncStockLogTick = 0L;

    // ---- 2026-10-25（「通配符样板不进 AE 合成」这一刀）：AE 侧样板缓存的重推闸
    //
    //  用户原话：「星律里面配置好通配符样板，但是 ae 里面没有显示可以合成这个物品」。
    //  AE2 15.4.10 把 provider 的样板缓存在 NetworkCraftingProviders 里，只在「节点加入网格」
    //  与「ICraftingProvider.requestUpdate」两处重建；上游只对**普通样板槽**置 needPatternSync ⇒
    //  改我们的 wildcardPatternInventory 不会触发任何刷新 ⇒ 展开出来的样板对 AE 不存在。
    //  ⇒ 下面这三个字段 + shanhaiTickAePatternExport() 就是补上这一次重推。

    /** 样板清单变了（通配符/手填类型/展开条数）⇒ 下一 tick 让 AE 重读一次。 */
    private boolean shanhaiAePatternDirty = false;

    /** 这次重推是被谁触发的（写进 PATEXPORT 的 from=，便于对照用户操作）。 */
    private String shanhaiAePatternReason = "";

    /**
     * 脏标记「推不出去」告警的上一次打点（600 tick 节流）。
     *
     * <p>🔴 2026-10-26：上一轮实机里 `PATEXPORT` 只有 `from=onLoad` 一条，之后<b>再没有任何一次
     * 容器变更触发的推送</b>。这有两种完全不同的成因，旧代码在 grid==null 时<b>静默返回</b>，
     * 分不出来：
     * <ol>
     *   <li>脏标记压根没被置（容器回调没跑）⇒ 现在由 {@code RECIPE claim} / {@code TICKER} 反证
     *       {@code update()} 到底有没有在跑；</li>
     *   <li>脏标记置了、但 node/grid 一直不可用 ⇒ 由这条 WARN 明说。</li>
     * </ol>
     */
    private long shanhaiAeDirtyWarnTick = Long.MIN_VALUE;

    /**
     * 打一行 {@code SUPPLYCHAIN}（节流 100 tick + 第一次一定打）。
     *
     * <p>判读：{@code handle=0 且 contents=0} ⇒ 主机<b>从没问过</b>这两份 handler
     * （⇒ 候选配方里根本没有「用这些料」的配方，问题不在供料，在配方/结构）；
     * {@code contents>0 但 handle=0} ⇒ 主机看得到料、却没拿它试配方；
     * {@code handle>0 且 left=0} ⇒ 供料成功（真的扣料）；
     * {@code handle>0 且 left>0 且 simulate=true} ⇒ 模拟阶段就没满足（料不够/键不对）。
     */
    private void shanhaiLogSupplyTrace(@NotNull String state) {
        if (this.isRemote() || this.getLevel() == null) {
            return;
        }
        final long now = this.getLevel().getGameTime();
        if (this.shanhaiSupplyTraceLogged
                && this.shanhaiSupplyLogTick != Long.MIN_VALUE
                && now - this.shanhaiSupplyLogTick < 100L) {
            return;
        }
        this.shanhaiSupplyTraceLogged = true;
        this.shanhaiSupplyLogTick = now;
        ShanhaiStellarLog.info("SUPPLYCHAIN {}", state);
    }

    /** 万能仓室链的物品 handler（行为 = 上游 {@code ExportOnlyAEStockingItemList}，只多计数与一条日志）。 */
    private final class ShanhaiStockItemList
            extends MEStockingPatternBufferPartMachine.ExportOnlyAEStockingItemList {

        private ShanhaiStockItemList() {
            super(StellarSampleAssemblerPartMachine.this, ShanhaiStockConfigLayout.CONFIG_SIZE);
        }

        @Override
        public java.util.List<Ingredient> handleRecipeInner(IO io, com.gregtechceu.gtceu.api.recipe.GTRecipe recipe,
                                                           java.util.List<Ingredient> left,
                                                           @Nullable String slotName, boolean simulate) {
            StellarSampleAssemblerPartMachine.this.shanhaiSupplyItemHandleCalls++;
            final int in = left == null ? -1 : left.size();
            final java.util.List<Ingredient> out = super.handleRecipeInner(io, recipe, left, slotName, simulate);
            // 2026-10-26 四修：out == null 才是「把料吃干 = 短路」（父类契约：非 null = 还缺这些）
            if (out == null) {
                StellarSampleAssemblerPartMachine.this.shanhaiStockItemNullReturns++;
            }
            StellarSampleAssemblerPartMachine.this.shanhaiLogSupplyTrace(
                    "kind=item handle in=" + in + " left=" + (out == null ? -1 : out.size())
                            + " simulate=" + simulate + " io=" + io
                            + " calls[h=" + StellarSampleAssemblerPartMachine.this.shanhaiSupplyItemHandleCalls
                            + " c=" + StellarSampleAssemblerPartMachine.this.shanhaiSupplyItemContentsCalls + "]"
                            + " cfg=" + this.configList.size() + " stock=" + this.stockMap.size());
            return out;
        }

        @Override
        public java.util.List<Object> getContents() {
            StellarSampleAssemblerPartMachine.this.shanhaiSupplyItemContentsCalls++;
            final java.util.List<Object> contents = super.getContents();
            StellarSampleAssemblerPartMachine.this.shanhaiLogSupplyTrace(
                    "kind=item contents n=" + (contents == null ? -1 : contents.size())
                            + " calls[h=" + StellarSampleAssemblerPartMachine.this.shanhaiSupplyItemHandleCalls
                            + " c=" + StellarSampleAssemblerPartMachine.this.shanhaiSupplyItemContentsCalls + "]"
                            + " cfg=" + this.configList.size() + " stock=" + this.stockMap.size());
            return contents;
        }
    }

    /** 万能仓室链的流体 handler（同上，只多计数与一条日志）。 */
    private final class ShanhaiStockFluidList
            extends MEStockingPatternBufferPartMachine.ExportOnlyAEStockingFluidList {

        private ShanhaiStockFluidList() {
            super(StellarSampleAssemblerPartMachine.this, ShanhaiStockConfigLayout.CONFIG_SIZE);
        }

        @Override
        public java.util.List<com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient> handleRecipeInner(
                IO io, com.gregtechceu.gtceu.api.recipe.GTRecipe recipe,
                java.util.List<com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient> left,
                @Nullable String slotName, boolean simulate) {
            StellarSampleAssemblerPartMachine.this.shanhaiSupplyFluidHandleCalls++;
            final int in = left == null ? -1 : left.size();
            final java.util.List<com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient> out =
                    super.handleRecipeInner(io, recipe, left, slotName, simulate);
            // 2026-10-26 四修：同上（out == null = 吃干 = 短路）
            if (out == null) {
                StellarSampleAssemblerPartMachine.this.shanhaiStockFluidNullReturns++;
            }
            StellarSampleAssemblerPartMachine.this.shanhaiLogSupplyTrace(
                    "kind=fluid handle in=" + in + " left=" + (out == null ? -1 : out.size())
                            + " simulate=" + simulate + " io=" + io
                            + " calls[h=" + StellarSampleAssemblerPartMachine.this.shanhaiSupplyFluidHandleCalls
                            + " c=" + StellarSampleAssemblerPartMachine.this.shanhaiSupplyFluidContentsCalls + "]"
                            + " cfg=" + this.configList.size() + " stock=" + this.stockMap.size());
            return out;
        }

        @Override
        public java.util.List<Object> getContents() {
            StellarSampleAssemblerPartMachine.this.shanhaiSupplyFluidContentsCalls++;
            final java.util.List<Object> contents = super.getContents();
            StellarSampleAssemblerPartMachine.this.shanhaiLogSupplyTrace(
                    "kind=fluid contents n=" + (contents == null ? -1 : contents.size())
                            + " calls[h=" + StellarSampleAssemblerPartMachine.this.shanhaiSupplyFluidHandleCalls
                            + " c=" + StellarSampleAssemblerPartMachine.this.shanhaiSupplyFluidContentsCalls + "]"
                            + " cfg=" + this.configList.size() + " stock=" + this.stockMap.size());
            return contents;
        }
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
        this.shanhaiRequestAePatternSync("forgeMultiplier:" + clamped);
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
     * <p>⚠️ 这里<b>不</b>调 {@code shanhaiRebuildPatternTables()}：那一步会写字段、打日志，
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

    /** 当前展开出来的总条数（🔴 C4：读路径不重建，只读现有表）。 */
    private int shanhaiWildcardExpandedCount() {
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
        // 🔴 C4：读路径不重建（改前这里会顺手 rebuild）。
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
        // 🔴 C4：读路径不重建（改前这里会顺手 rebuild）。
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
        // 🔴 2026-10-25（口径纠正，用户原话：「标签拉取把库存的位置填了，但是实际上它们应该分开各不干扰」）：
        //    这一行现在**只是通知**「标签那一路另有一个独立供料源、现在匹配了多少种」，
        //    它<b>不再</b>声明「接管 / 手配会被覆盖」——那套已经整条删除（见 shanhaiTagPull 的 javadoc）。
        //    文字逐帧从 @DescSynced 字段读（两侧都建控件树）；标签关着时返回空串 ⇒ 这一行不占视觉。
        page.addWidget(new LabelWidget(4, 120,
                (java.util.function.Supplier<String>) (() -> this.shanhaiTagSupplyLine()))
                .setHoverTooltips(Component.literal(
                        "标签过滤是**独立的一路供料源**：命中的物品/流体直接从 ME 网络供料（真扣 ME），"
                                + "**不占用**下面这 160 格（物品）+ 160 格（流体）。那 320 格纯手配，"
                                + "标签开也好关也好都不会改写或清空它 —— 两套各不干扰。")));
        return page;
    }

    /**
     * 库存页那一行「标签那一路现在什么状态」的文字（{@code ""} = 标签关，这一行整块不显示）。
     *
     * <p>🔴 <b>2026-10-25 改语义</b>：上一轮这里写的是「标签接管中（手配会被覆盖）」——
     * 那个口径已被用户否掉。现在这一行<b>只报告</b>这一路匹配了多少种，并明确写出
     * 「独立于下面这 160 格」，让玩家一眼知道<b>这 320 格是自己的、标签不会动它</b>。
     */
    private @NotNull String shanhaiTagSupplyLine() {
        if (!this.shanhaiTagFilterEnabled) {
            return "";
        }
        if (this.shanhaiTagPullSkip.isEmpty() || "pending".equals(this.shanhaiTagPullSkip)) {
            return "标签过滤：等待第一次库存同步…（独立于下面这 160 格）";
        }
        if (ShanhaiTagPullPlanner.Gate.OK.code().equals(this.shanhaiTagPullSkip)) {
            final StringBuilder out = new StringBuilder();
            out.append("标签过滤：已匹配 ").append(this.shanhaiTagSupplyItemMatched).append(" 种物品");
            if (this.shanhaiTagSupplyFluidMatched > 0) {
                out.append(" + ").append(this.shanhaiTagSupplyFluidMatched).append(" 种流体");
            }
            out.append("（独立于下面这 160 格）");
            return out.toString();
        }
        if (this.shanhaiTagPullSkip.startsWith("badExpression")) {
            return "标签过滤：暂停 —— 表达式有错（见标签页）；下面这 160 格手配原样";
        }
        if ("emptyExpression".equals(this.shanhaiTagPullSkip)) {
            return "标签过滤：暂停 —— 白/黑名单都空；下面这 160 格手配原样";
        }
        if ("noGrid".equals(this.shanhaiTagPullSkip)) {
            return "标签过滤：暂停 —— 没连上 ME 网络；下面这 160 格手配原样";
        }
        if ("noInventory".equals(this.shanhaiTagPullSkip)) {
            return "标签过滤：暂停 —— 取不到 AE 库存；下面这 160 格手配原样";
        }
        return "标签过滤：暂停（" + this.shanhaiTagPullSkip + "）；下面这 160 格手配原样";
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

    /**
     * 标签过滤总开关：服务端执行（与闸/锻同款：客户端那次本地执行被守卫掉）。
     *
     * <h2>🔴 2026-10-25 口径纠正（用户原话）</h2>
     * 「<b>星律样板总成的标签拉取把库存的位置填了，但是实际上它们应该分开各不干扰</b>」。
     * <p>⇒ 这个开关现在只控制「<b>标签这一路独立供料源</b>要不要供料」，
     * <b>完全不管</b>库存那 160 格（物品）+ 160 格（流体）：
     * <ul>
     *   <li><b>开</b> ⇒ 下一周期按白/黑名单算命中集合 ⇒ {@code ShanhaiTagSupplyHandlers} 那两份
     *       handler 开始从 ME 供料（真扣 ME）；<b>手配的 320 格一格不动</b>；</li>
     *   <li><b>关</b> ⇒ 命中集合立即清空 ⇒ 那两份 handler 回到「等于没有它」
     *       （{@code getContents()} 空、{@code handleRecipeInner} 原样退回）；
     *       <b>手配的 320 格依旧一格不动</b>（不清空、也不恢复什么 —— 它从来没被改过）。</li>
     * </ul>
     * <p>上一轮那段「关掉不清空」的三条理由现在<b>不适用</b>了：已经没有"写进去又清空"这件事。
     */
    private void shanhaiToggleTagFilter() {
        if (this.isRemote()) {
            return;
        }
        this.shanhaiTagFilterEnabled = !this.shanhaiTagFilterEnabled;
        // 立刻把界面那一行刷成开关后的样子（否则要等一个同步周期，界面会短暂自相矛盾）。
        // 开 ⇒ "pending"（等下一次库存同步真算完才敢说"在供料"，不抢跑）；
        // 关 ⇒ "disabled" + 立刻清命中集合（这一路当场停止供料，不等下一周期）。
        this.shanhaiTagPullSkip = this.shanhaiTagFilterEnabled ? "pending" : "disabled";
        if (!this.shanhaiTagFilterEnabled) {
            this.shanhaiClearTagSupply("toggle:off");
        }
        this.shanhaiSetTagPullLine(this.shanhaiTagPullLineText());
        this.shanhaiRefreshTagPreview();
        ShanhaiStellarLog.info("标签过滤总开关 => {}（{}）",
                this.shanhaiTagFilterEnabled ? "开" : "关",
                this.shanhaiTagFilterEnabled
                        ? "标签成为**独立供料源**：按白/黑名单从 ME 供料（真扣 ME），"
                        + "**不占用**也不会改写库存那 320 格"
                        : "这一路停止供料（命中集合已清空）；库存那 320 格手配**依旧原样**，从未被改过");
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
        // 两行 tooltip：第一行 = 「它是什么」，第二行 = 「它<b>现在真的</b>怎么做」（tag.explain2 以前写的是
        // 「自动灌进库存输入尚未接上」，与本页的功能自相矛盾；2026-10-25 起那句已改成实话，见 zh_cn.json）。
        row.setHoverTooltips(Component.translatable(LANG_TAG_ENABLED_TIP),
                Component.translatable(LANG_TAG_EXPLAIN_2));
        return row;
    }

    /** 标签过滤总开关的按钮文字（逐帧现读，拨一下立刻变字）。 */
    private @NotNull String shanhaiTagToggleText() {
        return "标签过滤库存输入：" + (this.shanhaiTagFilterEnabled ? "开" : "关");
    }

    /**
     * ⑧ 的页：<b>白名单 + 黑名单两个输入框 ＋ 语法说明 ＋ 匹配预览 ＋ 供料读数</b>。
     *
     * <p>🔴 <b>2026-10-25 口径纠正</b>（用户原话：「星律样板总成的标签拉取把库存的位置填了，
     * 但是实际上它们应该分开各不干扰」）：
     * 开关打开后，{@link #shanhaiTagPull} 只把「命中的键」记进<b>本类自己的快照</b>，
     * 由 {@link ShanhaiTagSupplyHandlers} 那两份 handler 当<b>独立供料源</b>用（直接从 ME 供料、真扣 ME）。
     * 它<b>不会</b>写库存那 160 格的 config，也<b>不会</b>清空任何格子 ⇒ 库存页的 {@code cfg}
     * 只反映<b>玩家手配</b>。
     *
     * <p>本页的三行读数各有分工，别混：
     * <ul>
     *   <li>{@code 匹配预览：238 / 370} = 「按当前 ME 库存，你的名单会收多少种」——
     *       数据源是库存快照（物品侧，判据，不是结果）；</li>
     *   <li>{@code 标签供料：已匹配 238 种物品（独立供料源，不占用库存 160 格）}
     *       = 「<b>真的</b>成为供料源的有多少种、这一路开没开」——
     *       这一行才是"到底有没有生效"的读数（{@code @DescSynced} 的 {@link #shanhaiTagPullLine}）；</li>
     *   <li>上面的 16 格只读格 = 本机库存配置的<b>前 16 格回显</b>（那是<b>手配</b>的，
     *       标签这一路既不写它也不清它）。</li>
     * </ul>
     */
    private @NotNull WidgetGroup shanhaiTagFilterPage() {
        final WidgetGroup page = new WidgetGroup(0, 0,
                ShanhaiStellarUiLayout.PAGE_W, ShanhaiStellarUiLayout.PAGE_H);
        page.addWidget(new LabelWidget(118, 4, Component.translatable(LANG_TAG_SECTION).getString()));
        // 阶段十九：标签过滤总开关（最上面一行；亮=开、暗=关；默认关）
        page.addWidget(this.shanhaiTagToggleRow());
        page.addWidget(new LabelWidget(4, 14, Component.translatable(LANG_TAG_WHITE).getString()));
        // 🔴 2026-10-25 修（用户原话：「重新打开星律样板总成之后，输入框清空了」）：
        //    原来这里写死 `.setText("")` ⇒ 每次重建这一页（关界面/翻页/重开）都把<b>已经生效</b>的
        //    白/黑名单在界面上抹掉，而机器字段里其实还留着（{@link #shanhaiTagWhite} 是
        //    {@code @Persisted @DescSynced}）⇒ 用户看到的是「输入框空着」，机器按「旧的（空）」算，
        //    两边读数不一致。现在**从字段回填**：字段是唯一真值，widget 只是它的显示。
        page.addWidget(new AETextInputButtonWidget(52, 13, 140, 10)
                .setText(this.shanhaiTagWhite == null ? "" : this.shanhaiTagWhite)
                .setOnConfirm(this::shanhaiApplyTagWhite)
                .setButtonTooltips(Component.translatable(LANG_TAG_WHITE_HINT)));
        page.addWidget(new LabelWidget(4, 27, Component.translatable(LANG_TAG_BLACK).getString()));
        page.addWidget(new AETextInputButtonWidget(52, 26, 140, 10)
                .setText(this.shanhaiTagBlack == null ? "" : this.shanhaiTagBlack)
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
        // 🔴 2026-10-25 改读数口径：这一行现在说的是「**标签这一路当独立供料源**现在匹配了多少种」，
        //    **不再**是「写进库存几格 / 清了几格尾巴」（那是上一轮被否掉的做法）。
        //    文字 = {@code @DescSynced} 的 {@link #shanhaiTagPullLine}（服务端每轮算完才写，见 shanhaiTagPull）。
        page.addWidget(new LabelWidget(4, 168, (java.util.function.Supplier<String>)
                () -> this.shanhaiTagPullLine)
                .setHoverTooltips(Component.literal(
                        "这一行是真读数：本轮按白/黑名单在 ME 缓存库存里**匹配到多少种**物品/流体。"
                                + "命中的东西成为**独立的一路供料源**（直接从 ME 供料，真扣 ME），"
                                + "**不占用**库存那 160 格（物品）+ 160 格（流体）——那 320 格纯手配，"
                                + "标签开也好关也好都不会改写或清空它。"
                                + "安全阀上限 " + TAG_SUPPLY_MAX + " 条（命中更多时按 ME 现量降序截断，日志 truncated=true）。")));
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

    /**
     * 一个 AE <b>流体</b>键身上的标签串（{@code forge:water} / {@code minecraft:water} 这种形式）。
     *
     * <p>走 {@code Fluid#builtInRegistryHolder().tags()}（{@code javap -p} 实证：
     * {@code net.minecraft.world.level.material.Fluid#builtInRegistryHolder(): Holder.Reference<Fluid>}，
     * {@code net.minecraft.core.Holder#tags(): Stream<TagKey<T>>}）—— 与物品侧
     * {@code ItemStack#getTags()} 同一条「Holder 反查标签」的路，<b>不</b>扫全注册表、
     * <b>不</b>建反向索引（tag 重载后不会有陈旧缓存）。
     */
    private @NotNull java.util.List<String> shanhaiFluidTagStrings(@NotNull AEFluidKey key) {
        final java.util.List<String> out = new java.util.ArrayList<>();
        try {
            final net.minecraft.world.level.material.Fluid fluid = key.getFluid();
            if (fluid == null) {
                return out;
            }
            fluid.builtInRegistryHolder().tags().forEach(tag -> out.add(tag.location().toString()));
        } catch (Throwable ignored) {
            // 取不到标签 = 这个键不算命中（宁可少收，不许把"不知道"当成"全收"）
        }
        return out;
    }

    // ---------------------------------------------------------------- ⑧ 标签命中集合 = 独立供料源

    /**
     * <b>刷新「标签命中集合」</b>——它现在是一个<b>独立的供料源</b>，<b>不写</b>那 160 格库存配置。
     *
     * <h2>🔴 与上一轮的根本差别（用户 2026-10-25 原话纠正口径）</h2>
     * 用户原话：「<b>星律样板总成的标签拉取把库存的位置填了，但是实际上它们应该分开各不干扰</b>」。
     * <ul>
     *   <li>上一轮：开关开 ⇒ 每周期把命中的键<b>写进库存配置 + 清尾</b>（手配被覆盖）；</li>
     *   <li>本轮：命中集合只写进本类自己的两个<b>快照字段</b>
     *       （{@link #shanhaiTagSupplyItemKeys} / {@link #shanhaiTagSupplyFluidKeys}），
     *       由 {@link ShanhaiTagSupplyHandlers} 那两份 handler 当<b>独立供料源</b>用。
     *       ⇒ 本方法<b>零</b> {@code setConfig*} / {@code clearInventory} / {@code onConfigChanged} 调用，
     *       手配的 160+160 格<b>逐格原样</b>（探针 `E` 组断言）。</li>
     * </ul>
     *
     * <h2>每一轮做什么</h2>
     * <ol>
     *   <li>闸（{@link ShanhaiTagPullPlanner#gate} 的纯函数，闸序 = 开关 → 名单 → 网络 → 库存）；</li>
     *   <li>扫 {@code IStorageService#getCachedInventory()}（<b>AE2 缓存库存</b>，与上游
     *       {@code TagFilterMEStockBusPartMachine#refreshList} 同一条 API）；</li>
     *   <li>物品 / 流体各走一遍 {@link ShanhaiTagPullPlanner#plan}（过滤 + 按量降序 + 安全阀截断）；</li>
     *   <li>命中指纹变了才整份替换快照并 {@code version++}（handler 的 {@code getContents()} 缓存靠它）；</li>
     *   <li>打<b>一行</b> {@code TAGSUPPLY …}（仅当结果变了，见 {@link #shanhaiLogTagPullOnce}）。</li>
     * </ol>
     *
     * <h2>🔴 每一道闸都是「<b>这一路不供料</b>」，而不是「动那些格子」</h2>
     * <ul>
     *   <li>开关关 ⇒ 命中集合清空（handler 立刻回到"与没有它时逐字节相同"），
     *       <b>手配那 160 格原样</b>；</li>
     *   <li>名单都空 ⇒ 同上（刻意<b>不</b>把「空」当「全收」：那会把整张网络都变成供料源）；</li>
     *   <li>表达式语法错 ⇒ 同上，且错误原文进日志与面板（既不静默全收也不静默清空）。</li>
     * </ul>
     *
     * @param grid 本机主节点所在的网络（{@code null} = 没连上；此时这一路不供料）
     */
    private void shanhaiTagPull(@Nullable appeng.api.networking.IGrid grid) {
        if (this.isRemote()) {
            return;
        }
        final String white = this.shanhaiTagWhite == null ? "" : this.shanhaiTagWhite.trim();
        final String black = this.shanhaiTagBlack == null ? "" : this.shanhaiTagBlack.trim();
        // 取 AE2 缓存库存（与上游 refreshList 同一个 API：KeyCounter）。取不到 ⇒ 记成 hasInventory=false。
        KeyCounter cached = null;
        if (grid != null) {
            try {
                cached = grid.getStorageService().getCachedInventory();
            } catch (Throwable unavailable) {
                cached = null;
            }
        }
        // ---- 闸（纯函数，顺序就是语义；探针 temp\tagpull2\probe 对它跑真值表）
        final ShanhaiTagPullPlanner.Gate gate = ShanhaiTagPullPlanner.gate(
                this.shanhaiTagFilterEnabled, white, black, grid != null, cached != null);
        if (gate != ShanhaiTagPullPlanner.Gate.OK) {
            this.shanhaiTagPullSkip = gate.code();
            this.shanhaiClearTagSupply("gate:" + gate.code());
            this.shanhaiLogTagPullOnce("skip=" + gate.code(),
                    "TAGSUPPLY enabled={} matched=0（item=0 fluid=0）skipped={} ⇒ {}",
                    Boolean.valueOf(this.shanhaiTagFilterEnabled), gate.code(), this.shanhaiTagGateHint(gate));
            this.shanhaiSetTagPullLine(this.shanhaiTagPullLineText());
            return;
        }
        // ---- 候选：一次遍历，物品/流体分两桶（物流体的条目才收，别的类型一律跳过）
        final java.util.List<ShanhaiTagPullPlanner.Candidate<AEItemKey>> itemCandidates = new ArrayList<>();
        final java.util.List<ShanhaiTagPullPlanner.Candidate<AEFluidKey>> fluidCandidates = new ArrayList<>();
        try {
            for (Object2LongMap.Entry<AEKey> entry : cached) {
                final AEKey key = entry.getKey();
                final long amount = entry.getLongValue();
                if (key instanceof AEItemKey itemKey) {
                    itemCandidates.add(new ShanhaiTagPullPlanner.Candidate<>(itemKey,
                            this.shanhaiItemIdOf(itemKey), amount, this.shanhaiItemTagStrings(itemKey)));
                } else if (key instanceof AEFluidKey fluidKey) {
                    fluidCandidates.add(new ShanhaiTagPullPlanner.Candidate<>(fluidKey,
                            String.valueOf(fluidKey.getId()), amount, this.shanhaiFluidTagStrings(fluidKey)));
                }
            }
        } catch (Throwable broken) {
            this.shanhaiTagPullSkip = "noInventory";
            this.shanhaiClearTagSupply("iter:" + broken.getClass().getSimpleName());
            this.shanhaiLogTagPullOnce("skip=noInventory|iter",
                    "TAGSUPPLY enabled=true matched=0（item=0 fluid=0）skipped=noInventory err=[{}]",
                    String.valueOf(broken.getMessage()));
            this.shanhaiSetTagPullLine(this.shanhaiTagPullLineText());
            return;
        }
        // ---- 规划（表达式语法错在这里抛 ⇒ 这一路不供料，但**一格都不动**）
        final ShanhaiTagPullPlanner.Plan<AEItemKey> itemPlan;
        final ShanhaiTagPullPlanner.Plan<AEFluidKey> fluidPlan;
        try {
            itemPlan = ShanhaiTagPullPlanner.plan(white, black, itemCandidates, TAG_SUPPLY_MAX);
            fluidPlan = ShanhaiTagPullPlanner.plan(white, black, fluidCandidates, TAG_SUPPLY_MAX);
        } catch (IllegalArgumentException badExpression) {
            this.shanhaiTagPullSkip = "badExpression:" + badExpression.getMessage();
            this.shanhaiClearTagSupply("badExpression:" + badExpression.getMessage());
            this.shanhaiLogTagPullOnce("skip=badExpression|" + badExpression.getMessage(),
                    "TAGSUPPLY enabled=true matched=0（item=0 fluid=0）skipped=badExpression err=[{}]"
                            + "（表达式写错了 ⇒ 这一路暂停；库存那 160 格一格未动）",
                    String.valueOf(badExpression.getMessage()));
            this.shanhaiSetTagPullLine(this.shanhaiTagPullLineText());
            return;
        }
        this.shanhaiTagPullSkip = "ok";
        // ---- 🔴 这里**只**动本类自己的两个快照字段，绝不碰那两份 160 格 handler ----
        //   上一轮那套 `IMESlot#setConfigWithoutNotify` / `onConfigChanged()` / 清尾（`tailOccupied`）
        //   **整体删除**。用户 2026-10-25 原话：
        //   「星律样板总成的标签拉取把库存的位置填了，但是实际上它们应该分开各不干扰」
        //   ⇒ 命中集合只是「这一路能供什么」，手配的 160+160 格由玩家自己填，两套各不干扰。
        final long itemSig = ShanhaiTagPullPlanner.signature(itemPlan.hits);
        final long fluidSig = ShanhaiTagPullPlanner.signature(fluidPlan.hits);
        if (itemSig != this.shanhaiTagSupplyItemSig) {
            this.shanhaiTagSupplyItemSig = itemSig;
            this.shanhaiTagSupplyItemKeys = shanhaiKeysOf(itemPlan.hits);
            this.shanhaiTagSupplyVersion++;          // handler 的 getContents() 缓存靠它失效
        }
        if (fluidSig != this.shanhaiTagSupplyFluidSig) {
            this.shanhaiTagSupplyFluidSig = fluidSig;
            this.shanhaiTagSupplyFluidKeys = shanhaiKeysOf(fluidPlan.hits);
            this.shanhaiTagSupplyVersion++;
        }
        // ---- 记读数（面板 + 日志用）
        this.shanhaiTagSupplyItemScanned = itemPlan.candidates;
        this.shanhaiTagSupplyItemMatched = itemPlan.matched;
        this.shanhaiTagSupplyItemTruncated = itemPlan.truncated > 0;
        this.shanhaiTagSupplyFluidScanned = fluidPlan.candidates;
        this.shanhaiTagSupplyFluidMatched = fluidPlan.matched;
        this.shanhaiTagSupplyFluidTruncated = fluidPlan.truncated > 0;
        // ---- 一行日志（**只在结果变化时**打：同一份库存 + 同一份名单 ⇒ 一行都不多打）
        // 末尾四位（migrated/legacyCfg/…）是**顺手带上**的迁移闸读数：用户怀疑
        // 「@Persisted 的 160 格读档为空 ⇒ 一次性迁移闸再也不会跑」，这四个数就是否决它的现场证据
        // （migrated=true 且 legacyCfg>0 ⇒ 确实"旧 32 格还有东西但不会再迁"；推理见交付报告 §5）。
        final int legacyItemCfg = this.shanhaiCountConfigured(this.stockItemHandler);
        final int legacyFluidCfg = this.shanhaiCountConfiguredFluid(this.stockFluidHandler);
        final String logState = itemSig + "|" + fluidSig + "|" + white + "|" + black;
        this.shanhaiLogTagPullOnce(logState,
                "TAGSUPPLY enabled=true matched={}（item={} fluid={}）scanned[item={} fluid={}]"
                        + " truncated[item={} fluid={}] source=independent（不占库存 160 格、手配原样）"
                        + " migrated={} legacyCfg={} fluidMigrated={} legacyFluidCfg={} white=[{}] black=[{}]",
                Integer.valueOf(itemPlan.matched + fluidPlan.matched),
                Integer.valueOf(itemPlan.matched), Integer.valueOf(fluidPlan.matched),
                Integer.valueOf(itemPlan.candidates), Integer.valueOf(fluidPlan.candidates),
                Boolean.valueOf(this.shanhaiTagSupplyItemTruncated),
                Boolean.valueOf(this.shanhaiTagSupplyFluidTruncated),
                Boolean.valueOf(this.shanhaiStockMigrated), Integer.valueOf(legacyItemCfg),
                Boolean.valueOf(this.shanhaiStockFluidMigrated), Integer.valueOf(legacyFluidCfg),
                white, black);
        this.shanhaiSetTagPullLine(this.shanhaiTagPullLineText());
    }

    /** 命中集合 → 键快照（不可变）。{@code Candidate.payload} 为 null 的直接跳过。 */
    private static <T> java.util.List<T> shanhaiKeysOf(
            @NotNull java.util.List<ShanhaiTagPullPlanner.Candidate<T>> hits) {
        if (hits.isEmpty()) {
            return java.util.List.of();
        }
        final java.util.List<T> out = new java.util.ArrayList<>(hits.size());
        for (int i = 0; i < hits.size(); i++) {
            final T payload = hits.get(i).payload;
            if (payload != null) {
                out.add(payload);
            }
        }
        return java.util.List.copyOf(out);
    }

    /**
     * 命中集合清空（某道闸没过时用）：让那两份 handler 立刻回到「等于没有它」。
     *
     * <p>🔴 只清<b>本类自己的快照</b>——库存那 160+160 格<b>一个字节都不动</b>。
     * <p>⚠️ 本来就是空的时候直接返回（不动版本号）：否则每个同步周期都会让 handler 白重建一次缓存。
     */
    private void shanhaiClearTagSupply(@NotNull String why) {
        final boolean hadItems = !this.shanhaiTagSupplyItemKeys.isEmpty();
        final boolean hadFluids = !this.shanhaiTagSupplyFluidKeys.isEmpty();
        this.shanhaiTagSupplyItemMatched = 0;
        this.shanhaiTagSupplyFluidMatched = 0;
        if (!hadItems && !hadFluids) {
            return;
        }
        this.shanhaiTagSupplyItemKeys = java.util.List.of();
        this.shanhaiTagSupplyFluidKeys = java.util.List.of();
        this.shanhaiTagSupplyItemSig = ShanhaiTagPullPlanner.signature(java.util.List.of());
        this.shanhaiTagSupplyFluidSig = ShanhaiTagPullPlanner.signature(java.util.List.of());
        this.shanhaiTagSupplyVersion++;
        ShanhaiStellarLog.info("TAGSUPPLY 命中集合清空（{}）⇒ 这一路不再供料；库存那 160 格一格未动", why);
    }

    /** 闸没过的中文原因（日志与面板共用同一套措辞，不另写一份）。 */
    private @NotNull String shanhaiTagGateHint(@NotNull ShanhaiTagPullPlanner.Gate gate) {
        switch (gate) {
            case DISABLED:
                return "标签关：这一路不供料（库存那 160 格恢复纯手配，原样保留）";
            case EMPTY_EXPRESSION:
                return "白/黑名单都空：刻意不当「全收」（那会把整张网络变成供料源）—— 先填一个白名单或黑名单";
            case NO_GRID:
                return "本机主节点不在网上 ⇒ 扫不到 ME 缓存库存";
            case NO_INVENTORY:
                return "取不到 AE 缓存库存（getStorageService().getCachedInventory()）";
            default:
                return "未知闸状态：" + gate.code();
        }
    }

    /**
     * 标签页底部那一行<b>真读数</b>的文字（{@code @DescSynced} 下发，见 {@link #shanhaiTagPullLine}）。
     *
     * <p>🔴 2026-10-25 改口径：它<b>不再</b>说「接管了几格库存配置」（那是上一轮的做法，已作废），
     * 而是说「<b>已匹配几种</b>」+「<b>独立供料源，不占用库存 160 格</b>」。
     */
    private @NotNull String shanhaiTagPullLineText() {
        if (!this.shanhaiTagFilterEnabled) {
            return "标签供料：已关闭（这一路不供料；下面那 160 格手配原样）";
        }
        if (this.shanhaiTagPullSkip.isEmpty()) {
            return "标签供料：等待第一次库存同步…";
        }
        if (!"ok".equals(this.shanhaiTagPullSkip)) {
            return "标签供料：暂停（" + this.shanhaiTagPullSkip + "）—— 这一路不供料，160 格手配原样";
        }
        final StringBuilder out = new StringBuilder();
        out.append("标签供料：已匹配 ").append(this.shanhaiTagSupplyItemMatched).append(" 种物品");
        if (this.shanhaiTagSupplyFluidMatched > 0) {
            out.append(" + ").append(this.shanhaiTagSupplyFluidMatched).append(" 种流体");
        }
        out.append("（独立供料源，不占用库存 160 格）");
        if (this.shanhaiTagSupplyItemTruncated || this.shanhaiTagSupplyFluidTruncated) {
            out.append("｜命中超过 ").append(TAG_SUPPLY_MAX).append(" 条，已截断");
        }
        return out.toString();
    }

    // ---------------------------------------------------------------- 供料源实现（给 ShanhaiTagSupplyHandlers 用）

    /**
     * 机器侧实现的那份 {@link ShanhaiTagSupplyHandlers.Source}：<b>只读快照 + 真抽取</b>。
     *
     * <h2>🔴 到底扣不扣 ME、在哪扣（一句话）</h2>
     * <b>扣</b>。扣在 {@link #shanhaiTagSupplyExtract(AEKey, long, boolean)} 里那一句
     * {@code MEStorage#extract(key, n, Actionable.MODULATE, actionSource)}，
     * 它只会在 {@code handleRecipeInner(..., simulate=false)}（= 配方<b>真跑</b>那一遍）被调用；
     * {@code simulate=true}（配方匹配/算料）走 {@code Actionable.SIMULATE}，<b>ME 里的量一个都不动</b>。
     *
     * <p>⚠️ 别把这一路误会成 {@code ShanhaiSupplyOnlyItemHandler} 那种「只声明能供、永不抽取」——
     * 那种是给<b>催化剂</b>用的（催化剂本来就不该被消耗）；放在<b>配方原料</b>上会凭空造物。
     * 这里与库存输入仓 {@code ExportOnlyAEStocking*List} 同一条语义：
     * <b>与 ME 共享内容物、不搬物品</b>（没有本地缓冲，扣的时候直接从网络里抽走）。
     */
    private final class ShanhaiTagSupplySource implements ShanhaiTagSupplyHandlers.Source {

        @Override
        public boolean shanhaiTagSupplyEnabled() {
            // 开关开 + 闸全过（"ok" 只在上面的 plan 成功后才写）⇒ 这一路才供料。
            // 其余任何处境（关 / 名单空 / 没连网 / 取不到库存 / 表达式错）都是"不供料"，
            // **绝不**顺手去改库存那 160 格。
            return StellarSampleAssemblerPartMachine.this.shanhaiTagFilterEnabled
                    && ShanhaiTagPullPlanner.Gate.OK.code()
                    .equals(StellarSampleAssemblerPartMachine.this.shanhaiTagPullSkip);
        }

        @Override
        public long shanhaiTagSupplyVersion() {
            return StellarSampleAssemblerPartMachine.this.shanhaiTagSupplyVersion;
        }

        @Override
        public java.util.List<AEItemKey> shanhaiTagSupplyItems() {
            return StellarSampleAssemblerPartMachine.this.shanhaiTagSupplyItemKeys;
        }

        @Override
        public java.util.List<AEFluidKey> shanhaiTagSupplyFluids() {
            return StellarSampleAssemblerPartMachine.this.shanhaiTagSupplyFluidKeys;
        }

        @Override
        public long shanhaiTagSupplyAmount(AEKey key) {
            if (key == null) {
                return 0L;
            }
            final KeyCounter snapshot = StellarSampleAssemblerPartMachine.this.shanhaiStockSnapshot;
            if (snapshot == null) {
                return 0L;
            }
            return snapshot.get(key);
        }

        @Override
        public long shanhaiTagSupplyExtract(AEKey key, long amount, boolean simulate) {
            if (key == null || amount <= 0L) {
                return 0L;
            }
            final MEStorage storage = shanhaiTagSupplyStorage();
            if (storage == null) {
                return 0L;
            }
            try {
                final long got = storage.extract(key, amount,
                        simulate ? appeng.api.config.Actionable.SIMULATE : appeng.api.config.Actionable.MODULATE,
                        StellarSampleAssemblerPartMachine.this.actionSource);
                if (!simulate && got > 0L) {
                    if (key instanceof AEFluidKey) {
                        StellarSampleAssemblerPartMachine.this.shanhaiTagSupplyFluidExtracts++;
                    } else {
                        StellarSampleAssemblerPartMachine.this.shanhaiTagSupplyItemExtracts++;
                    }
                }
                return got;
            } catch (Throwable broken) {
                ShanhaiStellarLog.warn("TAGSUPPLY 抽取失败 key={} amount={} simulate={} err=[{}]",
                        key, Long.valueOf(amount), Boolean.valueOf(simulate),
                        String.valueOf(broken.getMessage()));
                return 0L;
            }
        }

        @Override
        public long shanhaiTagSupplyReinsert(AEKey key, long amount) {
            if (key == null || amount <= 0L) {
                return 0L;
            }
            final MEStorage storage = shanhaiTagSupplyStorage();
            if (storage == null) {
                ShanhaiStellarLog.warn("TAGSUPPLY 无法回滚：网络已断开，key={} amount={}（这部分料丢了，如实记）",
                        key, Long.valueOf(amount));
                return 0L;
            }
            try {
                final long back = storage.insert(key, amount, appeng.api.config.Actionable.MODULATE,
                        StellarSampleAssemblerPartMachine.this.actionSource);
                if (back < amount) {
                    ShanhaiStellarLog.warn("TAGSUPPLY 回滚不全：key={} 应回 {} 实回 {}（差额如实记，不装作成功）",
                            key, Long.valueOf(amount), Long.valueOf(back));
                }
                return back;
            } catch (Throwable broken) {
                ShanhaiStellarLog.warn("TAGSUPPLY 回滚抛异常：key={} amount={} err=[{}]",
                        key, Long.valueOf(amount), String.valueOf(broken.getMessage()));
                return 0L;
            }
        }

        @Override
        public void shanhaiTagSupplyCount(boolean fluid, boolean contents, int n, boolean simulate) {
            if (contents) {
                if (fluid) {
                    StellarSampleAssemblerPartMachine.this.shanhaiTagSupplyFluidContents++;
                } else {
                    StellarSampleAssemblerPartMachine.this.shanhaiTagSupplyItemContents++;
                }
                // 🔴 这条路**不打日志**：{@code getContents()} 是超高频调用（实机 18 万次），
                //    而 shanhaiLogSupplyTrace 是 100 tick（5 秒）一行的**全局窗口** ——
                //    在这条路上打点会把窗口全占满，把「库存那两份 handler 的
                //    SUPPLYCHAIN kind=item handle … calls[h=」这条关键判据挤掉（上一轮留下的实机判据）。
                //    计数照样有：下面 handle 那行的 {@code c=} 就是它。
                return;
            }
            if (fluid) {
                StellarSampleAssemblerPartMachine.this.shanhaiTagSupplyFluidHandles++;
            } else {
                StellarSampleAssemblerPartMachine.this.shanhaiTagSupplyItemHandles++;
            }
            final String kind = fluid ? "fluid" : "item";
            StellarSampleAssemblerPartMachine.this.shanhaiLogSupplyTrace(
                    "kind=" + kind + "-tag handle left=" + n + " simulate=" + simulate
                            + " calls[h=" + StellarSampleAssemblerPartMachine.this.shanhaiTagSupplyItemHandles
                            + '/' + StellarSampleAssemblerPartMachine.this.shanhaiTagSupplyFluidHandles
                            + " c=" + StellarSampleAssemblerPartMachine.this.shanhaiTagSupplyItemContents
                            + '/' + StellarSampleAssemblerPartMachine.this.shanhaiTagSupplyFluidContents + "]"
                            + " matched=" + StellarSampleAssemblerPartMachine.this.shanhaiTagSupplyItemMatched
                            + '/' + StellarSampleAssemblerPartMachine.this.shanhaiTagSupplyFluidMatched
                            + " extract=" + StellarSampleAssemblerPartMachine.this.shanhaiTagSupplyItemExtracts
                            + '/' + StellarSampleAssemblerPartMachine.this.shanhaiTagSupplyFluidExtracts);
        }

        /** 本机主节点上的 ME 库存对象（掉线 ⇒ null）。 */
        private @Nullable MEStorage shanhaiTagSupplyStorage() {
            try {
                final appeng.api.networking.IManagedGridNode node =
                        StellarSampleAssemblerPartMachine.this.getMainNode();
                final appeng.api.networking.IGrid grid = node == null ? null : node.getGrid();
                return grid == null ? null : grid.getStorageService().getInventory();
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    /** 改 {@link #shanhaiTagPullLine} 的唯一入口（文字没变就不赋值 ⇒ 不白刷同步包）。 */
    private void shanhaiSetTagPullLine(@NotNull String text) {
        if (text.equals(this.shanhaiTagPullLine)) {
            return;
        }
        this.shanhaiTagPullLine = text;
    }

    /**
     * TAGPULL 那一行的<b>唯一发出口</b>：同一份 {@code state} 只打一次。
     *
     * <p>为什么按状态去重而不是按时间节流：这一行要看的是「<b>库存配置变没变</b>」，
     * 每 200 tick 打一遍同样的数字只会把日志刷成噪声（用户明确要求「不要改成刷屏」）；
     * 而真变化（换了白名单 / ME 里多了一样命中的东西 / 被清尾了）一定会改 {@code state}。
     */
    private void shanhaiLogTagPullOnce(@NotNull String state, @NotNull String message, Object... args) {
        if (state.equals(this.shanhaiTagPullLogState)) {
            return;
        }
        this.shanhaiTagPullLogState = state;
        ShanhaiStellarLog.info(message, args);
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
        // 🔴 2026-10-25：手填类型会改「哪些槽过闸」与样板 NBT ⇒ AE 侧那一份缓存必须重推
        this.shanhaiRequestAePatternSync("applyType:" + wildcardSlot);
        // 🔴 C4：③ 母槽类型变化 ⇒ 建一次（用户口径的第三个触发点）。
        this.shanhaiBuildPatternTablesIfDirty("applyType:" + wildcardSlot);
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
        // 展开条数直接决定 AE 能看见几条 ⇒ 同一处置
        this.shanhaiRequestAePatternSync("cacheCount:" + wildcardSlot);
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
        this.shanhaiRequestAePatternSync("wildcardCircuit:" + wildcardSlot + "=" + normalized);
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
        // 2026-10-26 五修：结构成型 ⇒ HANDLEPOOL 复位。等 40 tick 再打，那时这一次
        // rebuildRecipeHandleParts 至少已经跑过一轮（「改前/改后」比的就是这一行）。
        this.shanhaiHandlePoolWait = 0;
        this.shanhaiHandlePoolLogged = false;
        // 2026-10-26 四修：结构成型那一刻打一次 HANDLERS（顺序 + 谁短路）＋ BUFFERGATE（就绪闸）。
        // 放在这里而不是 update()：成型是「一次性事件」，这两行就是要在那个时刻留档。
        this.shanhaiLogHandlersOnce("formed");
        this.shanhaiLogBufferGate("formed");
    }

    @Override
    public void removedFromController(@NotNull IMultiController controller) {
        super.removedFromController(controller);
        this.shanhaiRefreshHostRecipeTypeIds();
        // 2026-10-26 五修：拆结构 ⇒ HANDLEPOOL 复位（下次成型会再打一次读数）。
        this.shanhaiHandlePoolWait = 0;
        this.shanhaiHandlePoolLogged = false;
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
        // 🔴 2026-10-26（四修 ③）：原来这一处**只**置 forgeCacheDirty —— 少了闸/展开/放行名单三份缓存，
        //    于是「把某一格通配符样板换成另一块 / 清空」这条路上，`getAvailablePatterns()` 仍读陈旧的
        //    gateBlockedSlots 与 expansionsByMother ⇒ AE 拿到的清单和容器真值不一致。
        //    与 {@link #shanhaiOnWildcardInventoryChanged()} 对齐（那里早就调了）。
        this.shanhaiInvalidateGateCache();
        // 🔴 2026-10-25：样板格内容变了 ⇒ 展开表会变 ⇒ AE 那边的「可合成」清单必须重推一次
        // ⚠️ 2026-10-26：调用点可能是**展开槽**（`onPatternChange` 只按 MOTHER_OFFSET 平移），
        //    那时 wildcardSlot 会 ≥50 ⇒ 读容器前必须夹范围（否则越界读写到别的东西）。
        final boolean slotEmpty = wildcardSlot < 0 || wildcardSlot >= WILDCARD_SLOT_COUNT
                || this.wildcardPatternInventory.getStackInSlot(wildcardSlot).isEmpty();
        this.shanhaiRequestAePatternSync("wildcardSlot:" + wildcardSlot + (slotEmpty ? ":empty" : ":set"));
        if (ShanhaiStellarLog.isTypeLogEnabled()) {
            final ItemStack stack = this.wildcardPatternInventory.getStackInSlot(wildcardSlot);
            ShanhaiStellarLog.logSlotType("wildcardChanged", WILDCARD_API_SLOT_OFFSET + wildcardSlot, stack,
                    ShanhaiPatternTypeReader.resolve(stack));
        }
    }

    /** 50 格容器的 contents-changed（`setStackInSlot` / 玩家拖入 / **取出**都会走到这里）。 */
    private void shanhaiOnWildcardInventoryChanged() {
        if (this.isRemote()) {
            return;
        }
        this.shanhaiRefreshWildcardConfigType();
        this.shanhaiRefreshWildcardMask();
        this.forgeCacheDirty = true;
        // 🔴 2026-10-26（四修 ③）：这条回调同时挂在「加」和「删」上，所以它必须把三份缓存一起放倒 ——
        //    只置 forgeCacheDirty 时，**删**掉一块通配符母槽之后 expansionsByMother 还留着它的展开条，
        //    重推给 AE 的还是那批已经不存在的样板（这就是「AE 继续往没样板的机器派单」的成因之一）。
        this.shanhaiInvalidateGateCache();
        // 🔴 2026-10-25：拖入/取出通配符样板是**最常见**的那条路（用户就是拖进去的）⇒
        //    这一处不重推，AE 终端永远看不到新产物「可以合成」。
        //    2026-10-26：`from=` 里带上容器真值（非空格数）—— 取空时它会变成 0，一眼能看出是"删"不是"加"。
        int nonEmpty = 0;
        for (int i = 0; i < WILDCARD_SLOT_COUNT; i++) {
            if (!this.wildcardPatternInventory.getStackInSlot(i).isEmpty()) {
                nonEmpty++;
            }
        }
        this.shanhaiRequestAePatternSync("wildcardInv:" + nonEmpty);
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
        // 🔴 C4（2026-10-27）：**读路径不再重建** ⇒ 脏标记在这里消费（每 tick 一次判断，脏才建）。
        //    于是"连续两次 getAvailablePatterns() 之间不重建"成立：AE 怎么问都只是读字段，
        //    真正建表发生在 ①样板格变 ②读档 ③母槽类型变 以及这里这一跳，建完立刻请求 AE 刷新。
        this.shanhaiBuildPatternTablesIfDirty("tick");
        // 🔴 2026-10-25：通配符样板/类型变了 ⇒ 让 AE 重新读一次我们的样板清单（否则终端永远看不到
        //    「可以合成」）。放在 super.update() 之后：上游那套 needPatternSync 先跑完，
        //    我们这一次是**补**上它覆盖不到的通配符容器。
        this.shanhaiTickAePatternExport();
        this.shanhaiTickAutoForgeMultiplier();
        // ---- 2026-10-26 四修：三组"一条日志判死"的读数（全部自带节流，且不改行为）
        //      TICKER  —— buffer / pushed / refunded（本机有没有被派单、产物有没有回流）
        this.shanhaiTickTickerProbe();
        //      HANDLERS（只一次）+ BUFFERGATE（卡住时每 300 tick 一行）—— 顺序与就绪闸
        this.shanhaiLogHandlersOnce("update");
        this.shanhaiLogBufferGate("update");
        //      HANDLEPOOL（每台机一次）—— 「承载我们 handler 的 RecipeHandlePart 到底存不存在」
        this.shanhaiLogHandlePoolOnce();
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
        // 🔴 2026-10-25：读档时展开表是空的（expandCacheDirty 初值 true），而 AE 的 provider 缓存
        //    在节点加入网格那一刻就把「当时的清单」登记进去了 ⇒ 必须显式重推一次，
        //    否则存档里已有的通配符样板在终端里永远不显示「可以合成」。
        this.shanhaiRequestAePatternSync("onLoad");
        this.forgeCacheDirty = true;
        this.shanhaiInvalidateGateCache();
        // 🔴 C4：② 读档 ⇒ 建一次（改前是等 AE 来问才建；现在读档当场建好并请求 AE 刷新）。
        this.shanhaiBuildPatternTablesIfDirty("onLoad");
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
        this.shanhaiSyncStock("onLoad");
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
     * 槽号空间总长 = <b>1080 + 50 + 已分配展开数</b>（🔴 C3：<b>动态</b>，不再有 7530 这种编译期上界）。
     *
     * <p>展开段长度 = {@link ShanhaiWildcardSlotAllocator#slotCount()}（"实际展开出几条"）；
     * 母样板变少 ⇒ 尾部槽号回收、水位下降 ⇒ 本方法返回值跟着变小。
     * 展开段的 InternalSlot 仍按需创建（见 {@link #expandInternalSlots}）。
     *
     * <p>🔴 覆写它是阶段三第 1 项的核心：Base 类所有「遍历全部槽」的方法
     * （{@code getActiveSlots()} / {@code getActiveAndUnCachedSlots()} / {@code getMergedInternalSlot()} /
     * {@code pushPattern} 里找空槽 等）都以它为界，并且<b>只通过 {@link #getInternalSlot(int)} 取槽</b>
     * （Base 里没有 {@code internalInventory} 这个字段 —— 它住在 {@code MEPatternBufferPartMachine}）
     * ⇒ 把这两个方法成对覆写，Base 的循环就永远不会碰父类数组。
     */
    @Override
    protected int getInternalSlotCount() {
        // 🔴 C3（2026-10-27）：动态 —— 普通 1080 ＋ 母槽 50 ＋ 已分配展开数（原版无上限）。
        //    ⚠️ 构造期（父类构造器里）本字段还是 null ⇒ 那时只报固定段长（与阶段三"父类只看 0..1079"同义）。
        final ShanhaiWildcardSlotAllocator alloc = this.shanhaiSlotAllocator;
        return ShanhaiWildcardLayout.EXPAND_OFFSET + (alloc == null ? 0 : alloc.slotCount());
    }

    /** 本对象现在的槽号空间描述（日志/探针用；槽号空间是动态的 ⇒ 不许再打印编译期常量）。 */
    public @NotNull String shanhaiSlotSpaceDescribe() {
        return "pattern=0.." + (ShanhaiWildcardLayout.PATTERN_SLOTS - 1)
                + " mother=" + ShanhaiWildcardLayout.MOTHER_OFFSET + ".." + (ShanhaiWildcardLayout.EXPAND_OFFSET - 1)
                + " expand=" + ShanhaiWildcardLayout.EXPAND_OFFSET + ".." + (this.getInternalSlotCount() - 1)
                + " allocated=" + this.shanhaiSlotAllocator.slotCount()
                + " (total=" + this.getInternalSlotCount() + ")";
    }

    /** 槽号是不是"本对象当前的"展开槽（动态边界 —— 分配器水位会收缩；构造期分配器还是 null）。 */
    private boolean shanhaiIsExpandSlotNow(int slot) {
        if (slot < ShanhaiWildcardLayout.EXPAND_OFFSET) {
            return false;
        }
        final ShanhaiWildcardSlotAllocator alloc = this.shanhaiSlotAllocator;
        return alloc != null && slot < ShanhaiWildcardLayout.EXPAND_OFFSET + alloc.slotCount();
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
        if (this.shanhaiIsExpandSlotNow(slot)) {
            // 🔴 懒创建：第一次被问到才 new（见 expandInternalSlots 的注释）。
            //    这里**必须**返回非 null —— 父类/本类的调用点都直接 `.isActive()` / `.pushPattern()`。
            //    ⚠️ C3 起展开段长度 = 分配器水位（动态），没有 6400 的固定扫描。
            return this.shanhaiExpandSlotAt(slot - ShanhaiWildcardLayout.EXPAND_OFFSET);
        }
        if (ShanhaiWildcardLayout.isPatternSlot(slot)) {
            return super.getInternalSlot(slot);
        }
        throw new IndexOutOfBoundsException("星律样板总成：槽号 " + slot + " 越界（合法 0.."
                + (this.getInternalSlotCount() - 1) + "：" + this.shanhaiSlotSpaceDescribe()
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
        if (this.shanhaiIsExpandSlotNow(slot)) {
            // 这一条展开"存在" ⇔ 分配器里这个下标真的分配给了某条展开（C3：槽号由分配器发放，
            // 不再由 (母槽, 分支) 算术推出来）。
            final int rel = slot - ShanhaiWildcardLayout.EXPAND_OFFSET;
            final int mother = this.shanhaiSlotAllocator.motherOf(rel);
            if (mother < 0) {
                return false;
            }
            return this.shanhaiExpansionCountFor(mother) > this.shanhaiSlotAllocator.branchOf(rel);
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
        if (ShanhaiWildcardLayout.isMotherSlot(slot) || this.shanhaiIsExpandSlotNow(slot)) {
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
        if (ShanhaiWildcardLayout.isMotherSlot(slot) || this.shanhaiIsExpandSlotNow(slot)) {
            // 通配符槽：只走本类自己的账本（失效闸缓存 + 日志 + 让终端重读），绝不进父类数组。
            this.shanhaiInvalidateGateCache();
            if (ShanhaiWildcardLayout.isMotherSlot(slot)) {
                this.shanhaiOnWildcardSlotChanged(slot - ShanhaiWildcardLayout.MOTHER_OFFSET);
            }
            // 🔴 C4：样板格内容变化 ⇒ 当场建一次（用户口径的三个触发点之一）。
            this.shanhaiBuildPatternTablesIfDirty("wildcardSlot:" + slot);
            return;
        }
        super.onPatternChange(slot);
        this.shanhaiInvalidateGateCache();
        // 🔴 2026-10-26（四修 ③）：**「删」这条路也必须留下 AE 重推的读数**。
        //    事实（{@code javap -c} MEPatternBufferPartMachine#onPatternChange 偏移 66 与 163
        //    两处 {@code putfield needPatternSync}）：这个分支无论「加」还是「删」都会置
        //    {@code needPatternSync=true} ⇒ 父类 {@code update()} 里那次 {@code requestUpdate} 会跑。
        //    但它是**父类**的账，我们看不到 —— 上一轮实机「PATEXPORT 只有 from=onLoad 一条」正是
        //    因为这条重推走的是父类那条路、不进我们的 {@code from=}。
        //    ⇒ 现在这里显式标一次脏：AE 侧同样重读，而日志里会多一条
        //    `PATEXPORT from=patternSlot:<槽>:empty`（删）/`:set`（加）—— 一眼分清是哪条路触发的刷新。
        this.shanhaiRequestAePatternSync("patternSlot:" + slot
                + (this.hasPatternInSlot(slot) ? ":set" : ":empty"));
        // 🔴 C4：① 样板格内容变化 ⇒ 当场建一次（读路径不再重建）。
        this.shanhaiBuildPatternTablesIfDirty("patternSlot:" + slot);
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
        // 🔴 一个字段、一次遍历（形状见 #effectivePatterns 的注释）：
        //    AE 拿到的就是认领表（#expandedToSlot）里的那批实例 —— 不再每层拼一个新 list。
        //
        // 🔴🔴 C4（2026-10-27）：**这一跳绝不重建**（改前是 `shanhaiEnsurePatternTables()`）。
        //    原版时机是"样板格内容变化 / 读档 / 母槽类型变化"时建一次，建完主动请求 AE 刷新
        //    （见 shanhaiBuildPatternTablesIfDirty）；AE 问多少次都只是读同一份字段。
        //    判据：连续两次本方法之间不重建（重建只发生事件点/update 那一跳，并打一行 PATREBUILD）。
        //    ⚠️ 负对照位（生产 false）打开时，这里每次调用都会重建 ⇒ 用于证明判据能翻红。
        if (this.shanhaiRebuildPolicy != null && this.shanhaiRebuildPolicy.shouldBuildOnRead()) {
            this.shanhaiRebuildPatternTables("read-negative-control");
        }
        this.shanhaiInAePatternQuery = true;
        try {
            return this.effectivePatternView;
        } finally {
            this.shanhaiInAePatternQuery = false;
        }
    }

    /** 阶段三那道闸（只拦歧义）；抽成独立方法，由 {@link #shanhaiRebuildPatternTables()} 调用。 */
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
     * <b>「脏了就在这一跳建一次」的唯一入口（C4）</b>。
     *
     * <p>两张脏标记（{@link #expandCacheDirty} / {@link #forgeCacheDirty}）在
     * {@link #shanhaiRebuildPatternTables(String)} 里<b>一起清零</b>。
     *
     * <p>🔴 只有<b>事件点</b>（样板格内容变化 / 读档 / 母槽类型变化 / 神锻·闸开关 / 通配符电路）
     * 与 {@code update()} 每 tick 的那一跳会调它 —— 读路径
     * （{@code getAvailablePatterns} / {@code getSlotIndexForPattern} / 候选槽枚举）<b>一次都不调</b>。
     * 建完立刻 {@link #shanhaiRequestAePatternSync(String)}（"建完主动请求 AE 刷新"）。
     */
    private void shanhaiBuildPatternTablesIfDirty(@NotNull String reason) {
        if (this.isRemote() || this.getLevel() == null) {
            // 客户端 / 读档途中：不能算（rewrite 拿不到 level 会原样返回），脏标记留着。
            return;
        }
        if (!this.expandCacheDirty && !this.forgeCacheDirty) {
            return;
        }
        this.shanhaiRebuildPatternTables(reason);
        this.shanhaiRequestAePatternSync("rebuild:" + reason);
    }

    /**
     * 🔴 反向查槽：<b>父表优先</b>（普通样板一字不差走老路），查不到再查展开认领表。
     *
     * <p>这一处覆写让 {@code pushPattern}（偏移 16 那一次调用）对「改写后的样板」也能拿到原槽号，
     * 于是推料路径不需要任何其它改动。
     *
     * <p>🔴 C2 + C4（2026-10-27）：<b>不再有第二张 identity 表</b>（{@code rewrittenToSlot} 已删），
     * 也<b>不再在读路径重建</b>（改前这里有一句 {@code if (expandCacheDirty) rebuild}）。
     * 于是「AE 手里的对象」与「认领表的键」只剩一份对应关系：一个池 ⇔ 一条配方。
     */
    @Override
    protected Integer getSlotIndexForPattern(IPatternDetails pattern) {
        final Integer direct = super.getSlotIndexForPattern(pattern);
        if (direct != null) {
            return direct;
        }
        return this.expandedToSlot.get(pattern);
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
     * <p>值没变就一个字节都不动（上游 {@code setForgePatternModeEnabled} 同款「变了才重算」）——
     * 这一条是<b>语义</b>不是优化：面板每帧都读 getter，但 setter 只在点击时被调；若「值没变」也走
     * 一遍全量重算，开一次面板就等于把 1130 格重算好几遍。
     * <p>⚠️ 客户端那次调用<b>不写日志、不做重活</b>：LDLib 的开关在两侧都会触发
     * （{@code SwitchWidget.mouseClicked} 偏移 34–44 先 {@code writeClientAction} 发服务端、再本地
     * {@code onPressCallback.accept}；服务端在 {@code handleClientAction} 偏移 18–38 里 accept 第二遍）
     * ⇒ 日志只留服务端那一行，否则一次点击刷两条、看着像点了两下。
     *
     * <h2>🔴 2026-10-26 修：开关一变就【当场】重算，不再等玩家把样板拿出来再放回去</h2>
     * 用户原话：「我要是开启星律样板总成的神锻模式，里面的样板并没有立即生效，
     * 必须要把样板拿出来再放回去才能生效」。
     * <p>改前这里只有「写字段 + {@code forgeCacheDirty = true} + 清闸缓存」，
     * <b>没有任何人消费那个脏标记</b>：AE2 只在<b>①节点入网 ②显式
     * {@code ICraftingProvider.requestUpdate}</b> 两处重建样板缓存（字节码结论见
     * {@link #shanhaiTickAePatternExport()} 的注释），而开关既不入网也不 requestUpdate
     * ⇒ {@code getAvailablePatterns()} 根本没被再问一次 ⇒ 改写快照永远不会重建。
     * <p><b>「拿出来再放回去」为什么就生效（另一条路径，点名）</b>：那条走的是
     * {@code onPatternChange(slot)} → 父类置 {@code needPatternSync = true}
     * （我们的真实父类链是 {@code MEStockingPatternBufferPartMachine} → gtlcore 的
     * {@code MEPatternBufferPartMachine}：{@code onPatternChange} 在第 243–268 行，
     * 第 266 行就是 {@code this.needPatternSync = true;}；
     * 反编译件见 {@code temp/machine-principle/out/gtlcore/part/ae/MEPatternBufferPartMachine.java}）
     * → 下一 tick 上游 {@code update()} 里
     * {@code if (needPatternSync) ICraftingProvider.requestUpdate(getMainNode());}
     * （{@code MEPatternBufferPartMachineBase.update()} 第 153–158 行，同上目录）
     * ⇒ <b>是它替我们叫醒了 AE</b>，我们那个 {@code forgeCacheDirty} 才被消费。
     * ⇒ 缺的从来不是「置脏」，是「叫醒 AE」这一环。
     * <p>⚠️ 注意「知道它为什么生效」≠「该照它做」：同一个 {@code onPatternChange} 第 265 行还有
     * {@code reCalculatePatternSlotMap()}（扫全部 1080 格重建映射），第 261–262 行有
     * {@code refundSlot(...)} ＋ {@code AEUtils.reFunds(...)}（真把料退回 ME）⇒ 逐槽调 1130 次 =
     * 1130 遍「全表重建 + 退款检查」，所以本方法<b>不</b>复用那条路，只复用它的最后一步（叫醒 AE）。
     *
     * <h2>开↔关对称</h2>
     * 关掉走<b>同一条链</b>：{@link #shanhaiRebuildPatternTables()} 里 {@code forgeMode == false} 那一支
     * 不登记 {@code rewrittenToSlot}，于是 AE 拿到的是原样板（等同于把改写全部撤下）。
     * 判据里 {@code rewritten=} 在关的一侧报的是「这次确实撤下的改写条数」，同样不为 0 才算绿。
     */
    @Override
    public void setForgePatternModeEnabled(boolean enabled) {
        if (this.forgePatternMode == enabled) {
            return;
        }
        this.forgePatternMode = enabled;
        this.forgeCacheDirty = true;
        this.shanhaiInvalidateGateCache();
        if (this.isRemote()) {
            // 客户端：只写本地字段（面板靠自己的 isPressed / @DescSynced 刷新）。
            // 🔴 重算绝不能在这里做：patternInventory 不是 @DescSynced，客户端压根读不到样板内容。
            return;
        }
        // ① 先标脏 —— 复用上一轮那条通道（下一 tick requestUpdate，并用 isCraftable 回读判死）。
        //    放在重算之前，判据行里的 aeDirty 才是「本次切换真的叫醒了 AE」而不是上一次的残留。
        this.shanhaiRequestAePatternSync("forgeToggle:on=" + enabled);
        // ② 再当场重算一次 —— 只在这一刻做一次，绝不下沉到 getAvailablePatterns() / update()。
        this.shanhaiRefreshForgePatternsNow(enabled);
        ShanhaiStellarLog.info("神锻样板模式 => {}（倍率 {}，上游实现可用={}）",
                enabled ? "开" : "关", this.forgePatternMultiplier,
                ShanhaiForgePatternMode.isAvailable());
    }

    /**
     * <b>【开关切换当场重算全部样板】—— 唯一调用方是
     * {@link #setForgePatternModeEnabled(boolean)}（每 tick 路径里没有它）。</b>
     *
     * <h2>为什么走 {@link #getAvailablePatterns()}，而不是「逐槽 {@code onPatternChange(slot)}」</h2>
     * 任务书给的候选是「对所有非空样板槽逐个 onPatternChange」。核实后<b>没有选它</b>，因为字节码证明
     * 那不是改写发生的地方，而且它的代价是<b>每槽 O(全部槽数)</b>：
     * <pre>
     *   onPatternChange(int)  ← gtlcore MEPatternBufferPartMachine 第 243–268 行，逐句：
     *       slot2PatternMap.put/remove + hasPatternArray[index]=true/false
     *       （内容真不同才）clearAllCaches + clearVirtualSupply + removeSlotFromGTRecipeCache
     *                       + refundSlot(...) + AEUtils.reFunds(...)   ← 真把料退回 ME
     *       reCalculatePatternSlotMap()                                  ← 第 265 行：扫全部 1080 格重建映射
     *       needPatternSync = true                                       ← 第 266 行：它只【通知 AE】
     *   改写只发生在 shanhaiRebuildPatternTables()，唯一入口 = getAvailablePatterns()
     * </pre>
     * ⇒ 逐槽调用 1130 次会：① 重新 {@code getRealPattern}/{@code decodePattern} 1130 次；
     * ② 触发 {@code refundSlot}/{@code AEUtils.reFunds} —— <b>那是玩家看得见的副作用</b>，
     * 与「拨一个开关」完全不相称（还可能把正在跑的配方原料退回去）；
     * ③ 把 {@code reCalculatePatternSlotMap()} 跑 1130 遍（≈1130×1080 次映射写）。
     * 而 {@code getAvailablePatterns()} 只扫<b>闸放行过的</b>那批样板一遍，与 AE 在 requestUpdate 之后
     * 自己要做的事<b>逐字相同</b>，我们只是提前到这一刻做。选它 = 用 AE 的眼睛做这次重算。
     *
     * <h2>为什么一次性做完，不分批</h2>
     * 分批会让 {@code getAvailablePatterns()} 在批与批之间返回<b>半改写</b>的清单
     * （一半样板 ×15 产出、一半 ×1）⇒ AE 的合成索引在这几 tick 内自相矛盾（玩家会看到「同一个产物
     * 一会儿能合成一会儿不能」），比「晚一 tick 生效」更糟。代价由 {@code ms=} 量出来：
     * <b>真机上如果 ms 大到影响手感，正确的改法是「先建影子表、最后整体交换」，
     * 而不是边建边把半成品暴露给 AE</b>（那时 pending 会变成 >0 的判据）。
     *
     * @param enabling 本次是开（true）还是关（false）—— 只用于挑判据里报哪个 N，不改行为
     */
    private void shanhaiRefreshForgePatternsNow(boolean enabling) {
        final long t0 = System.nanoTime();
        // 关：这一批要被撤下的改写条数 —— 必须在重建前抓（重建会把 shanhaiForgedCount 改写）
        final int carried = this.shanhaiForgedCount;
        // 扫「全部非空样板槽」：0..1079 普通 + 1080..1129 通配母槽（patternStackOf 覆盖这段对外空间）。
        // 只数，不改任何东西 —— 它的唯一用途是让判据能证明「确实扫遍了」，而不是只扫了个空表。
        int slots = 0;
        for (int apiSlot = 0; apiSlot < WILDCARD_API_SLOT_OFFSET + WILDCARD_SLOT_COUNT; apiSlot++) {
            final ItemStack stack = this.patternStackOf(apiSlot);
            if (stack != null && !stack.isEmpty()) {
                slots++;
            }
        }
        // 🔴 没加载（读档途中 / 客户端）⇒ 绝不能算：此时 rewrite 拿不到 level 会「原样返回」，
        //    一算就会把【还没改写】的画面烘进快照，而且 forgeCacheDirty 被消费掉 ⇒ 之后再也不重算。
        //    这种情况保留脏标记，交给下一次（真服务端、有 level）的 AE 询问去算。
        final boolean canRewrite = this.getLevel() != null;
        int rewrittenNow = 0;
        int listSize = 0;
        if (canRewrite) {
            // 与 AE 在 requestUpdate 之后自己会调的那一个方法【完全相同】的一条链：
            //   闸（拦歧义）→ 通配符展开 → 神锻改写
            // 🔴 C4：这条链现在只在事件点跑一次（原来是把 AE 的读路径当触发器）。
            this.shanhaiBuildPatternTablesIfDirty("forgeToggle:on=" + enabling);
            final List<IPatternDetails> now = this.getAvailablePatterns();
            rewrittenNow = this.shanhaiForgedCount;
            listSize = now.size();
        }
        // pending = 还没重算的条数。同步做完 ⇒ 0；连算都没法算（level==null）⇒ 全部欠着。
        final int pending = canRewrite ? 0 : slots;
        final long ms = (System.nanoTime() - t0) / 1_000_000L;
        // 🔴 判据行（每次切换只打一行，服务端）：
        //   on        : 这次是开(true)还是关(false)
        //   rewritten : 开 = 本次真正被改写的样板条数（取自 shanhaiForgedCount，不是「我调了」）；
        //               关 = 本次真正被撤下（回到原样）的改写条数 ⇒ 两个方向都能翻红
        //   slots     : 扫描到的非空样板槽数（1080 普通 + 50 通配母槽）
        //   pending   : 还没重算的条数（同步 ⇒ 0；若将来改分批，这里 >0 说明尾巴没做完）
        //   ms        : 本次重算耗时（真机读数用；决定要不要改分批）
        //   aeDirty   : 是否已标脏让 AE 重新登记（必须 true，否则终端不会刷新）
        //   list      : 重建后 AE 可见的清单条数
        ShanhaiStellarLog.info("FORGETOGGLE on={} rewritten={} slots={} pending={} ms={} aeDirty={} list={}{}",
                enabling, enabling ? rewrittenNow : carried, slots, pending, ms, this.shanhaiAePatternDirty, listSize,
                canRewrite ? "" : " (level==null：保留脏标记，等下一次 AE 询问再重算)");
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
        this.shanhaiRequestAePatternSync("autoForgeMultiplier:" + enabled);
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
     *   <li>{@link #shanhaiRebuildPatternTables()} —— <b>唯一</b>把它交给
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
     * ② {@link #shanhaiSyncStock(String)}（周期性自愈：结构成型/拆掉、控制器被替换都会在一个同步周期内反映）。
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
     * 下一次 {@link #shanhaiRebuildPatternTables()} 会用新的生效倍率重算全部样板
     * （那条路径与「玩家手改倍率」完全相同，见 {@link #shanhaiSetForgeMultiplierFromUi(int)}）。
     *
     * <p>⚠️ 入参只用于语义完整（接口要求带值）；真正被读的是同步字段里的读数 ——
     * 与超级通配符那台「{@code rebuildPatterns()} 按已同步读数重算、入参只用于日志」同构。
     */
    @Override
    public void shanhaiApplyForgeMultiplier(int multiplier) {
        this.forgeCacheDirty = true;
        this.shanhaiInvalidateGateCache();
        this.shanhaiRequestAePatternSync("forgeMultiplierApplied:" + multiplier);
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
        this.shanhaiRequestAePatternSync("gate:" + this.blockAmbiguousPatterns);
        ShanhaiStellarLog.info("闸（拦歧义样板）=> {}", this.blockAmbiguousPatterns ? "开" : "关");
    }

    /**
     * 闸缓存置脏（样板变更 / 手填类型变更 / 通配符电路变更 / 载入存档 都会走到）。
     *
     * <p>🔴 <b>2026-10-26 六修：这里顺手叫醒 AE。</b>改前本方法只放倒缓存，
     * 「叫不叫醒 AE」全靠<b>调用点</b>自己记得 —— 实测 12 个入口里有 <b>5 个忘了</b>
     * （2035 手改倍率 / 3518 通配符电路 / 4748 自动倍率开关 / 5018 自动倍率落地 / 5080 闸开关），
     * 后果是「表重建了、AE 手里还是旧实例」⇒ {@code pushPattern} 恒 false（{@code reason=patternNotOurs}）。
     * ⇒ 把「叫醒」下沉到本方法：<b>任何</b>置脏都连带一次重推。调用点自己的
     * {@code shanhaiRequestAePatternSync("...")} 仍然保留，只为给出更具体的 {@code reason=}
     * （{@link #shanhaiRequestAePatternSync(String)} 后写的覆盖先写的）。
     *
     * <p>⚠️ 本方法<b>不会</b>被每 tick 调（全部入口都是事件：容器回调 / 界面动作 / 读档），
     * 所以这次 requestUpdate 不会刷屏。
     */
    private void shanhaiInvalidateGateCache() {
        this.gateCacheDirty = true;
        this.expandCacheDirty = true;
        // 样板变了 ⇒「哪些槽可以靠库存供料」的快照也要重算（它按 hasPatternInSlot 选槽）
        this.shanhaiMarkStockSupplyDirty();
        // 🔴 兜底：任何置脏都必须叫醒 AE（否则重建出来的新实例没人认 —— 见方法注释）
        this.shanhaiRequestAePatternSync("gateCache");
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
        // 🔴 2026-10-27：槽级类型闸的解析缓存与闸缓存同一个脏标记（样板/类型/读档一变全清）。
        this.shanhaiSlotDeclaredTypeCache.clear();
        final List<Integer> untyped = new ArrayList<>();
        final List<Integer> ambiguous = new ArrayList<>();
        final java.util.BitSet patternAmbiguous = new java.util.BitSet(ShanhaiWildcardLayout.PATTERN_SLOTS);
        final java.util.BitSet patternUntyped = new java.util.BitSet(ShanhaiWildcardLayout.PATTERN_SLOTS);
        // 顺带算「本机不支持的那个声明类型」（Jade 追加二用；与这里同一份判定，不另扫一遍 NBT）。
        final List<String> machineTypes = ShanhaiRecipeTypeLabels.splitIds(this.shanhaiHostRecipeTypeIds);
        String unsupported = null;
        boolean undeclared = false;
        // 🔴 只扫 [0, 1130)：闸的输入是 patternStackOf(slot)（**样板物品的 NBT**），
        //    而展开槽没有样板物品（patternStackOf 对它们恒返回 null）⇒ 扫展开段一定是空转。
        final int count = ShanhaiWildcardLayout.MOTHER_END;
        for (int slot = 0; slot < count; slot++) {
            if (!this.hasPatternInSlot(slot)) {
                continue;
            }
            final ItemStack stack = this.patternStackOf(slot);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            final ShanhaiPatternTypeReader.Resolution resolution = ShanhaiPatternTypeReader.resolve(stack);
            final ShanhaiPatternTypeReader.Verdict verdict = resolution.verdict();
            if (verdict == ShanhaiPatternTypeReader.Verdict.UNIQUE && unsupported == null
                    && !machineTypes.isEmpty()) {
                // ② 追加二：样板声明了、本机一个可执行类型都不支持 ⇒ 记下来（Jade 要报这个，不报"材料不足"）。
                boolean anySupported = false;
                for (String id : resolution.ids()) {
                    if (machineTypes.contains(id)) {
                        anySupported = true;
                        break;
                    }
                }
                if (!anySupported && !resolution.ids().isEmpty()) {
                    unsupported = resolution.ids().get(0);
                }
            }
            if (verdict != ShanhaiPatternTypeReader.Verdict.UNIQUE) {
                // ③ 追加一：未声明/歧义 ⇒ 闸开时被拦（Jade 与槽级闸都读这个）。
                undeclared = true;
            }
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
        this.shanhaiUnsupportedDeclaredType = unsupported;
        this.shanhaiUndeclaredBlocked = undeclared;
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

    // ================================================================ 2026-10-27：两条"定位铜导线不跑"的探针

    /** PUSHSLOT / ACTIVESLOTS 的节流状态（40 tick + 同形去重）。 */
    private String shanhaiPushSlotLogState = "";

    private long shanhaiPushSlotLogTick = Long.MIN_VALUE;

    private String shanhaiActiveSlotsLogState = "";

    private long shanhaiActiveSlotsLogTick = Long.MIN_VALUE;

    /**
     * <b>PUSHSLOT</b>：AE 派单时「这条样板被解析成哪个槽号、要推多少件」。
     *
     * <p>为什么必须有：配方逻辑看不到铜，第一分叉点就是"铜到底塞进了哪个槽"。
     * 槽号语义见 {@link ShanhaiWildcardLayout}：{@code <1080} 普通样板槽 /
     * {@code 1080..1129} 通配符母槽 / {@code ≥1130} 展开槽。
     *
     * <p>⚠️ 拿不到样板 id 时打 {@code class=} 与 {@code identity=}（identityHashCode），
     * 这样即使 {@code toString} 不可读，也能与展开表里的实例对上号。
     * <p>节流：同形 40 tick 一条（一次 AE 派单会连续推几十件，不闸就刷屏）。
     */
    private void shanhaiPushSlotProbe(@NotNull appeng.api.crafting.IPatternDetails pattern,
                                      appeng.api.stacks.KeyCounter[] stacks) {
        if (this.isRemote() || this.getLevel() == null) {
            return;
        }
        final Integer slotObj = this.getSlotIndexForPattern(pattern);
        final String slotText = slotObj == null ? "none" : String.valueOf(slotObj.intValue());
        int count = 0;
        if (stacks != null) {
            for (appeng.api.stacks.KeyCounter counter : stacks) {
                if (counter != null) {
                    count += counter.size();
                }
            }
        }
        final String state = slotText + "|" + count + "|" + System.identityHashCode(pattern);
        final long now = this.getLevel().getGameTime();
        if (state.equals(this.shanhaiPushSlotLogState) && this.shanhaiPushSlotLogTick != Long.MIN_VALUE
                && now - this.shanhaiPushSlotLogTick < 40L) {
            return;
        }
        this.shanhaiPushSlotLogState = state;
        this.shanhaiPushSlotLogTick = now;
        String patternText;
        try {
            patternText = String.valueOf(pattern);
        } catch (Throwable t) {
            patternText = "?";
        }
        ShanhaiStellarLog.info("PUSHSLOT pos={} pattern={} class={} identity={} slot={} stacks={}"
                        + "（槽号语义：<1080 普通槽 / 1080..1129 母槽 / ≥1130 展开槽）",
                this.shanhaiPos(), patternText.length() > 80 ? patternText.substring(0, 80) : patternText,
                pattern.getClass().getSimpleName(), Integer.valueOf(System.identityHashCode(pattern)),
                slotText, Integer.valueOf(count));
    }

    /**
     * <b>ACTIVESLOTS</b>：{@code getActiveSlots()} 的返回值读数 —— 直接回答
     * 「配方逻辑枚举输入时，看到的槽里有没有 ≥1080 的展开槽」。
     *
     * <pre>
     *   ACTIVESLOTS pos=… active=… first=[…前5] last=[…后5] withItems=… expandedInList=…
     * </pre>
     * 判读（写进报告）：
     * <ul>
     *   <li>{@code expandedInList=0} ⇒ <b>展开槽没进候选视野</b>（首嫌疑）；</li>
     *   <li>{@code expandedInList>0} 但仍不跑 ⇒ 视野没问题，问题在内容物枚举；</li>
     *   <li>{@code active=0} ⇒ 一条活跃槽都没有（引擎压根没料可匹配）。</li>
     * </ul>
     * 节流：同形 40 tick 一条。
     */
    private void shanhaiActiveSlotsProbe(@NotNull int[] slots) {
        if (this.isRemote() || this.getLevel() == null) {
            return;
        }
        int expanded = 0;
        int withItems = 0;
        for (int slot : slots) {
            if (slot >= ShanhaiWildcardLayout.PATTERN_SLOTS) {
                expanded++;
            }
            final MEPatternBufferPartMachineBase.InternalSlot internal = this.getInternalSlot(slot);
            if (internal != null && internal.isActive()) {
                withItems++;
            }
        }
        final StringBuilder first = new StringBuilder();
        for (int i = 0; i < Math.min(5, slots.length); i++) {
            if (first.length() > 0) {
                first.append(',');
            }
            first.append(slots[i]);
        }
        final StringBuilder last = new StringBuilder();
        for (int i = Math.max(0, slots.length - 5); i < slots.length; i++) {
            if (last.length() > 0) {
                last.append(',');
            }
            last.append(slots[i]);
        }
        final String state = slots.length + "|" + expanded + "|" + withItems;
        final long now = this.getLevel().getGameTime();
        if (state.equals(this.shanhaiActiveSlotsLogState) && this.shanhaiActiveSlotsLogTick != Long.MIN_VALUE
                && now - this.shanhaiActiveSlotsLogTick < 40L) {
            return;
        }
        this.shanhaiActiveSlotsLogState = state;
        this.shanhaiActiveSlotsLogTick = now;
        ShanhaiStellarLog.info("ACTIVESLOTS pos={} active={} first=[{}] last=[{}] withItems={} expandedInList={}",
                this.shanhaiPos(), Integer.valueOf(slots.length), first, last,
                Integer.valueOf(withItems), Integer.valueOf(expanded));
    }
    // ================================================================ 槽级类型闸的样板侧（2026-10-27 第二轮）

    /**
     * <b>引擎拿一条配方问某个槽时，这个槽的判定（唯一入口，mixin 调的就是它）。</b>
     *
     * <h2>判据（用户口径：样板声明 ∩ 机器可执行，<u>对那单独一个槽</u>）</h2>
     * <pre>
     *   该槽声明类型 ∩ 机器可执行类型 里 包含 recipeType ⇒ 放行
     *   不包含（且槽确实有声明）                    ⇒ 拒
     *   槽没有声明类型                              ⇒ 闸开拦截 / 闸关放行
     *   读不到（配方类型空 / 机器类型表空 / 槽号不认识） ⇒ 放行（不猜）
     * </pre>
     *
     * <p>⚠️ 这里<b>不再</b>回答"整机该搜哪些类型"（上一批的全局收窄已按用户指令拆除）——
     * 候选配方照旧由引擎/控制器按机器能力搜，本方法只让某个槽在轮到自己时选择不接。
     *
     * <p>热路径：{@code meHandleRecipeInner} 会被反复调用 ⇒ 槽声明类型走
     * {@link #shanhaiSlotDeclaredTypeCache}（{@link #shanhaiInvalidateGateCache()} 清），
     * 机器类型表走已有的 {@code shanhaiHostRecipeTypeIds}。
     */
    public ShanhaiSlotTypeGate.@NotNull Outcome shanhaiSlotTypeVerdict(int slot, @Nullable String recipeType) {
        // 🔴 母槽（1080..1129）自己【不跑配方】（它只存样板物品 + 母槽配置）⇒ 不进本闸。
        //    不能让它落到"无声明"那一支：闸开时会把母槽拒掉，那是把"不跑配方的槽"和
        //    "声明缺失的执行槽"混为一谈（用户要求三类情况互不混淆）。
        if (!ShanhaiWildcardLayout.isPatternSlot(slot) && !this.shanhaiIsExpandSlotNow(slot)) {
            return ShanhaiSlotTypeGate.Outcome.PASS_NO_DECLARATION;
        }
        final List<String> machineTypes = ShanhaiRecipeTypeLabels.splitIds(this.shanhaiHostRecipeTypeIds);
        if (machineTypes.isEmpty()) {
            // 仓室读不到控制器 ⇒ 不知道本机能跑什么 ⇒ 放行（不猜）。
            return ShanhaiSlotTypeGate.Outcome.PASS_NO_DECLARATION;
        }
        final List<String> declared = this.shanhaiSlotDeclaredTypes(slot);
        final ShanhaiSlotTypeGate.Outcome outcome =
                ShanhaiSlotTypeGate.decide(declared, machineTypes, recipeType == null ? "" : recipeType,
                        this.blockAmbiguousPatterns);
        this.shanhaiLogSlotGate(slot, recipeType, declared, outcome);
        return outcome;
    }

    /**
     * <b>该槽声明的配方类型</b>（0..1..n 个 id；空表 = 没声明）。
     *
     * <pre>
     *   0..1079  普通样板槽：读样板物品的 NBT（四个来源，ShanhaiPatternTypeReader）
     *                        —— UNIQUE 采信；AMBIGUOUS 把这几个候选都算"声明"
     *                        （闸开时它们本来就被拦在 AE 之外，这里再拦一次也不矛盾）；
     *                        NONE ⇒ 空表。
     *   1080..1129 母槽：它自己不跑配方 ⇒ 空表（不进本闸）。
     *   ≥1130     展开槽：读它母槽的 gt_shanhai_recipe_type（空串 ⇒ 空表）。
     * </pre>
     * <p>结果缓存在 {@link #shanhaiSlotDeclaredTypeCache}（样板变 / 类型变 / 读档都会清）。
     */
    public @NotNull List<String> shanhaiSlotDeclaredTypes(int slot) {
        final List<String> cached = this.shanhaiSlotDeclaredTypeCache.get(slot);
        if (cached != null) {
            return cached;
        }
        final List<String> computed;
        if (this.shanhaiIsExpandSlotNow(slot)) {
            final String assigned = this.shanhaiAssignedTypeOfSlot(slot);
            computed = assigned.isEmpty() ? List.of() : List.of(assigned);
        } else if (ShanhaiWildcardLayout.isPatternSlot(slot)) {
            final ItemStack stack = this.patternStackOf(slot);
            if (stack == null || stack.isEmpty()) {
                computed = List.of();
            } else {
                final ShanhaiPatternTypeReader.Resolution resolution = ShanhaiPatternTypeReader.resolve(stack);
                computed = resolution.verdict() == ShanhaiPatternTypeReader.Verdict.NONE
                        ? List.of() : List.copyOf(resolution.ids());
            }
        } else {
            // 母槽（1080..1129）/ 越界：不参与本闸。
            computed = List.of();
        }
        this.shanhaiSlotDeclaredTypeCache.put(slot, computed);
        return computed;
    }

    /** 槽级判据行（同形 20 tick 一条 —— 这是热路径，必须限流）。 */
    private void shanhaiLogSlotGate(int slot, @Nullable String recipeType, @NotNull List<String> declared,
                                    ShanhaiSlotTypeGate.@NotNull Outcome outcome) {
        final String state = slot + "|" + outcome.name() + "|" + recipeType + "|" + declared;
        final long now = this.getLevel() == null ? 0L : this.getLevel().getGameTime();
        if (state.equals(this.shanhaiSlotGateLogState) && this.shanhaiSlotGateLogTick != Long.MIN_VALUE
                && now - this.shanhaiSlotGateLogTick < 20L) {
            return;
        }
        this.shanhaiSlotGateLogState = state;
        this.shanhaiSlotGateLogTick = now;
        ShanhaiStellarLog.info("TYPEROUTE pos={} slot={} {} machine={} gate={} ⇒ {}",
                this.shanhaiPos(), Integer.valueOf(slot),
                ShanhaiSlotTypeGate.describe(outcome, recipeType, declared),
                this.shanhaiHostRecipeTypeIds, Boolean.valueOf(this.blockAmbiguousPatterns),
                outcome.allows() ? "放行（这个槽可以跑这条配方）" : "拒绝（这个槽不接这条配方，引擎换下一槽）");
        if (outcome == ShanhaiSlotTypeGate.Outcome.BLOCK_UNSUPPORTED) {
            // 追加二要求的那条可 grep 证据行（Jade 文案同源）。
            ShanhaiStellarLog.warn("[SHANHAI-TYPEROUTE] pos={} slot={} unsupported_declared={} machine={}"
                            + " ⇒ jade=本机不支持该样板声明的配方类型",
                    this.shanhaiPos(), Integer.valueOf(slot), declared, this.shanhaiHostRecipeTypeIds);
        } else if (outcome == ShanhaiSlotTypeGate.Outcome.BLOCK_UNDECLARED) {
            ShanhaiStellarLog.warn("[SHANHAI-TYPEROUTE] pos={} slot={} undeclared slot（闸开 ⇒ 拦下）"
                            + " ⇒ jade=样板未声明类型", this.shanhaiPos(), Integer.valueOf(slot));
        }
    }

    // ---------------------------------------------------------------- ShanhaiPatternTypeBlockSource

    /**
     * ② 「类型不支持」那一条：本机一个可执行类型都不支持的、样板声明的类型 id。
     *
     * <p>数据来源 = {@link #shanhaiRefreshGateCache()} 那一趟扫描顺手记下的
     * {@link #shanhaiUnsupportedDeclaredType}（同一份判定，不另扫一遍 NBT）。
     */
    @Override
    public @Nullable String shanhaiUnsupportedDeclaredType() {
        this.shanhaiRefreshGateCache();
        return this.shanhaiUnsupportedDeclaredType;
    }

    /** ③ 「样板未声明类型」那一条（闸开着才算 —— 闸关时它们是放行的，不许报拦截）。 */
    @Override
    public boolean shanhaiUndeclaredBlocked() {
        this.shanhaiRefreshGateCache();
        return this.blockAmbiguousPatterns && this.shanhaiUndeclaredBlocked;
    }

    @Override
    public @NotNull String shanhaiTypeBlockDescribe() {
        return "unsupportedDeclared=" + (this.shanhaiUnsupportedDeclaredType == null ? "none"
                : this.shanhaiUnsupportedDeclaredType)
                + " undeclaredBlocked=" + this.shanhaiUndeclaredBlocked
                + " gate=" + this.blockAmbiguousPatterns
                + " machineTypes=" + this.shanhaiHostRecipeTypeIds;
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
        // 只扫 [0,1130)：展开槽没有样板物品（patternStackOf 恒 null），扫它们纯空转（K=128 时是 6400 格）。
        final int count = ShanhaiWildcardLayout.MOTHER_END;
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
    // ================================================================ 阶段五 第 3 项：通配符展开器（C3：无上限）

    /** 展开槽 → 母槽号（越界即 bug，响亮失败）。C3：改由分配器反查（槽号是动态发放的）。 */
    private int shanhaiMotherOfExpandSlot(int expandSlot) {
        final int mother = this.shanhaiSlotAllocator.motherOf(expandSlot - ShanhaiWildcardLayout.EXPAND_OFFSET);
        if (mother < 0) {
            throw new IllegalArgumentException("不是已分配的展开槽号：" + expandSlot
                    + "（当前 " + this.shanhaiSlotSpaceDescribe() + "）");
        }
        return mother;
    }

    /**
     * 某个母槽当前展开出几条（0 = 没展开 / 没有样板）。
     *
     * <p>🔴 C3 + C4：改由<b>分配器</b>回答（它记的就是"实际分配了几条"），
     * 而且<b>读路径不重建</b>（改前这里有 {@code if (expandCacheDirty) rebuild}）。
     */
    private int shanhaiExpansionCountFor(int mother) {
        return this.shanhaiSlotAllocator.count(mother);
    }


    /**
     * <b>把「通配符样板 → AE 可合成清单」这一跳显式推一次，并把结果读回来（PATEXPORT）。</b>
     *
     * <h2>🔴 为什么必须显式推（AE2 15.4.10 字节码实证）</h2>
     * AE 的合成计算<b>不是</b>每次现问 {@code getAvailablePatterns()}，而是把每个 provider 的样板
     * 缓存进 {@code NetworkCraftingProviders}（三个 map：{@code craftingProviders} /
     * {@code craftingMethods} / {@code craftableItems}）。缓存只在两处重建：
     * <pre>
     *   ① 节点加入网格：CraftingService.addNode → NetworkCraftingProviders.addProvider(node)
     *   ② 显式刷新：ICraftingProvider.requestUpdate(node)
     *      → ICraftingService.refreshNodeCraftingProvider(node)（javap -c 偏移 0–16：
     *        removeProvider(node); addProvider(node);）
     *        → addProvider 里重新调 provider.getAvailablePatterns()（= 我们的覆写，含通配符展开）
     * </pre>
     * 上游自己就是靠 ② 更新的：{@code MEPatternBufferPartMachineBase.update()}（javap -c 偏移 0–19）
     * = {@code if (needPatternSync) { ICraftingProvider.requestUpdate(getMainNode()); needPatternSync = false; }}
     * 而 {@code needPatternSync} 只在**普通样板槽**变化时被置位。
     *
     * <h2>⇒ 断点在哪</h2>
     * 我们的通配符样板放在**另一份容器** {@code wildcardPatternInventory}（50 格）里，改它<b>不会</b>
     * 让 {@code needPatternSync} 变 true ⇒ 上游那套自动刷新<b>永远看不到展开出来的样板</b> ⇒
     * AE 终端里那个产物「不显示可以合成」（用户 2026-10-25 报的第二条）。
     * 而机器配方照样会在别的路径上被问（所以日志里 handle 次数很大），
     * 两条症状看起来无关、其实是同一个「AE 缓存没重建」。
     *
     * <h2>本方法做什么</h2>
     * 脏标记 → 下一 tick（**不在 {@code getAvailablePatterns()} 里做，避免 addProvider 递归**）
     * 重建展开表 → {@code ICraftingProvider.requestUpdate(node)} → 用
     * {@code ICraftingService.isCraftable(产出键)} 把结果<b>读回来</b>打一行 PATEXPORT。
     *
     * <p>判读：{@code craftable=[<key>=1]} ⇒ AE 侧已经认得（终端搜那个产物应显示"可合成"）；
     * {@code =0} ⇒ 缓存还没重建 / 展开表为空（{@code expanded=0}）/ 该键有别的来源。
     */
    private void shanhaiTickAePatternExport() {
        if (this.isRemote() || !this.shanhaiAePatternDirty || this.getLevel() == null) {
            return;
        }
        final appeng.api.networking.IManagedGridNode node = this.getMainNode();
        final appeng.api.networking.IGrid grid = node == null ? null : node.getGrid();
        if (grid == null) {
            // 掉线：脏标记留着，联网后的第一 tick 再推（不要在这里清掉）
            // 🔴 2026-10-26：**挂住的脏标记必须出声** —— 上一轮实机「PATEXPORT 只在 from=onLoad 推过一次」
            //    有两种完全不同的成因：(a) 脏标记压根没被置（容器回调没跑），(b) 置了但一直推不出去。
            //    这一行把 (b) 钉死（每 600 tick 一行，不会刷屏）。
            final long nowTick = this.getLevel().getGameTime();
            if (this.shanhaiAeDirtyWarnTick == Long.MIN_VALUE
                    || nowTick - this.shanhaiAeDirtyWarnTick >= 600L) {
                this.shanhaiAeDirtyWarnTick = nowTick;
                ShanhaiStellarLog.warn("PATEXPORT pos={} from={} 脏标记从上次之后**一直没推出去**"
                                + "（getMainNode()={} grid=null）：AE 侧那份样板缓存仍是旧的",
                        this.shanhaiPos(), this.shanhaiAePatternReason,
                        node == null ? "null" : "notInGrid");
            }
            return;
        }
        // 🔴 C4：这一跳是「叫醒 AE 之前」，脏就建一次（走唯一入口，不在这里写第二份重建）。
        this.shanhaiBuildPatternTablesIfDirty("aeExport");
        // 🔴 2026-10-26：闸缓存也必须**在算 ours 之前**摆平 —— 否则 `ours=` 里混着
        //    「上次闸判定时被拦下的槽」，读数就不是「现在真的几条」。
        //    （`ours` 的定义 = 过闸 + 追加展开之后交给 AE 的条数，闸陈旧时它天然不准。）
        this.shanhaiRefreshGateCache();
        int expanded = 0;
        for (List<IPatternDetails> list : this.expansionsByMother.values()) {
            expanded += list.size();
        }
        // 🔴 2026-10-26：容器真值（不经过展开表，也不经过闸）—— 判「删光之后 ours 有没有跟着变 0」要用它。
        int wildcardInv = 0;
        for (int i = 0; i < WILDCARD_SLOT_COUNT; i++) {
            if (!this.wildcardPatternInventory.getStackInSlot(i).isEmpty()) {
                wildcardInv++;
            }
        }
        int pushed = 0;
        try {
            appeng.api.networking.crafting.ICraftingProvider.requestUpdate(node);
            pushed = 1;
        } catch (Throwable t) {
            ShanhaiStellarLog.warn("PATEXPORT requestUpdate 抛异常（AE 侧不会刷新）：{}", String.valueOf(t));
        }
        // 🔴 放在 requestUpdate 之后清：重建恰好发生在这一跳里时，EXPAND 的 aeSync= 才读得到真值。
        this.shanhaiAePatternDirty = false;
        // 探针：AE 现在认不认这些产出（isCraftable 读的正是刚被 addProvider 重建的那份缓存）
        final StringBuilder craftable = new StringBuilder();
        int probes = 0;
        for (List<IPatternDetails> list : this.expansionsByMother.values()) {
            for (IPatternDetails pattern : list) {
                if (probes >= 3) {
                    break;
                }
                final appeng.api.stacks.GenericStack[] outs;
                try {
                    outs = pattern == null ? null : pattern.getOutputs();
                } catch (Throwable ignored) {
                    continue;
                }
                if (outs == null || outs.length == 0 || outs[0] == null || outs[0].what() == null) {
                    continue;
                }
                final appeng.api.stacks.AEKey key = outs[0].what();
                boolean now = false;
                try {
                    now = grid.getCraftingService().isCraftable(key);
                } catch (Throwable ignored) {
                }
                if (craftable.length() > 0) {
                    craftable.append(',');
                }
                craftable.append(key.getId()).append('=').append(now ? 1 : 0);
                probes++;
            }
        }
        final String verdict;
        if (expanded == 0) {
            verdict = wildcardInv == 0
                    ? "通配符容器现在**真的一块样板都没有**（wildcardInv=0）⇒ 这次重推会把 AE 里那批旧样板清掉"
                    + "（这正是「删了样板但 AE 还派单」的根治点）"
                    : "容器里有 " + wildcardInv + " 块样板但展开表空（解不开类型）⇒ 看通配符槽内容与类型键";
        } else if (probes == 0) {
            verdict = "有展开但没有可读产出 ⇒ 展开出来的样板 getOutputs() 为空，查通配符内容";
        } else if (craftable.indexOf("=1") >= 0) {
            verdict = "AE 已重新登记（craftable=1）⇒ 终端搜该产物应显示可合成；若仍不显示，是终端回执延迟，重开终端再看";
        } else {
            verdict = "推了但 AE 仍不认（craftable=0）⇒ 看 from 是否通配符变更事件、以及该产物是否被别的容器抢注";
        }
        // 🔴 2026-10-26：`ours=` 与容器真值的**硬对账** —— 删光之后 ours 必须跟着归零。
        //    这一条是「样板删了、AE 缓存还留着」的直接判据（以前只能靠人眼比对两行）。
        final int ours = this.getAvailablePatterns().size();
        if (wildcardInv == 0 && ours > 0) {
            ShanhaiStellarLog.warn("PATEXPORT pos={} ours={} 但通配符容器是空的 ⇒ 展开表没被重建"
                            + "（旧实例还挂在 expansionsByMother 里），AE 会继续按已经不存在的样板派单",
                    this.shanhaiPos(), Integer.valueOf(ours));
        }
        ShanhaiStellarLog.info("PATEXPORT pos={} from={} mothers={} expanded={} wildcardInv={} requestUpdate={} "
                        + "craftable=[{}] ours={} ⇒ {}",
                this.shanhaiPos(), this.shanhaiAePatternReason, Integer.valueOf(this.expansionsByMother.size()),
                Integer.valueOf(expanded), Integer.valueOf(wildcardInv), pushed == 1 ? "ok" : "fail",
                craftable.length() == 0 ? "none" : craftable.toString(),
                Integer.valueOf(ours),
                verdict);
    }

    /**
     * 标脏：下一 tick 让 AE 重新读一次我们的样板清单（{@link #shanhaiTickAePatternExport()}）。
     *
     * <p>为什么不在调用点直接 {@code requestUpdate}：几个调用点都在「容器回调 / 界面动作」里跑，
     * 而 {@code addProvider} 会**同步**回调 {@code getAvailablePatterns()}；放到下一 tick 做，
     * 既能保证「这一轮的内容已经全部写进容器」，也不会在回调里递归。
     */
    private void shanhaiRequestAePatternSync(@NotNull String reason) {
        if (this.isRemote()) {
            return;
        }
        this.shanhaiAePatternDirty = true;
        this.shanhaiAePatternReason = reason;
    }

    /**
     * 🔴 <b>唯一一次遍历：闸 → 展开（截断到 K）→ 神锻改写 → 同时写两张表。</b>
     *
     * <h2>形状照抄「超级通配符 ME 样板总成」的 buildEffectivePatterns</h2>
     * {@code SuperWildcardPatternBufferPartMachine:686-696} 逐句：
     * <pre>
     *   for (raw : rawExpandedPatterns) {
     *       effective = ShanhaiForgePatternMode.rewrite(raw, level, forgeMode, multiplier);   // 688
     *       expandedPatterns.add(effective);                                                 // 689
     *       patternToSlotMap.put(effective, index);                                          // 690
     *       activeSlotIndices.add(index);                                                    // 691
     *       internalSlots.add(new ShanhaiPatternBufferInternalSlot(index));                  // 692-694
     *   }
     * </pre>
     * 本方法把「展开表」与「神锻快照」两张表<b>合到这一个循环里</b>，于是
     * <b>「AE 手里的对象」=「{@link #expandedToSlot} 的键」=「{@link #effectivePatterns} 的元素」</b>。
     *
     * <h2>三条不变式（改这里之前先读）</h2>
     * <ol>
     *   <li><b>raw 与 effective 都登记，且登记到同一个槽号</b>。effective 是 AE 这次会拿到的；
     *       raw 是「上一次 AE 拿到的那份」—— 重建若发生在两次 {@code getAvailablePatterns()} 之间，
     *       AE 手里还挂着旧实例，登记 raw 让它在下一 tick 仍能认领（否则就是 patternNotOurs）。</li>
     *   <li><b>槽号由分配器发放（C3）</b>：同一次重建里 (母槽, 分支) → 唯一槽号，绝不共享；
     *       跨重建若某块母样板变少，它尾部腾出的槽号会被后一次重建复用
     *       （见 {@link ShanhaiWildcardSlotAllocator} 的类注释"AE 样板身份会不会变"）；
     *       每次重建之后都会 requestUpdate，AE 整份重读 ⇒ 不会拿着旧槽号解释新表。</li>
     *   <li><b>重建之后必须叫醒 AE</b>：{@link #shanhaiInvalidateGateCache()} 已经兜住
     *       （它内部调 {@link #shanhaiRequestAePatternSync(String)}），
     *       判据见 {@link #shanhaiLogExpanded(int, int, int)} 的 {@code aeSync=}。</li>
     * </ol>
     *
     * <h2>代价</h2>
     * 遍历 GT 材料表不便宜：只在脏标记为 true 时跑，重建点只有
     * 「母样板内容变 / 手填类型变 / 通配符电路变 / 神锻开关或倍率变 / 闸变 / 读档」，<b>绝不每 tick</b>。
     */
    private void shanhaiRebuildPatternTables(@NotNull String reason) {
        this.expandCacheDirty = false;
        this.forgeCacheDirty = false;
        this.expansionsByMother.clear();

        this.effectivePatterns.clear();
        // 🔴 J6/J7：绑定表与认领表**同一个脏标记、同一次遍历**重建（这是上一轮 patternNotOurs 不再复发的前提）。
        this.shanhaiSlotBoundRecipe.clear();
        this.shanhaiBoundCount = 0;
        this.shanhaiTypeDroppedCount = 0;
        // ① 父表那批样板原样放行：它们是父类 patternSlotMap 里的稳定实例，认领走 super，不进本类的表。
        this.effectivePatterns.addAll(this.shanhaiGatedPatterns());
        final net.minecraft.world.level.Level level = this.getLevel();
        if (level == null) {
            // ② 客户端 / 未加载：不展开（AE 只在服务端问 getAvailablePatterns）。
            //    保留脏标记，等真服务端来问那一跳再算 —— 与改前一致（绝不把「没改写」的画面烘成定局）。
            this.expandCacheDirty = true;
            this.forgeCacheDirty = true;
            return;
        }
        final boolean forgeMode = this.forgePatternMode && ShanhaiForgePatternMode.isAvailable();
        // ⚠️ 这里【只读】已经同步好的字段，绝不触发驱动 refresh：
        //    驱动落地时会调 shanhaiApplyForgeMultiplier() ⇒ 置脏，在本循环里调它会造成「重建中又置脏」的自激。
        final int multiplier = this.getEffectiveForgePatternMultiplier();
        int mothers = 0;
        int expanded = 0;
        int forged = 0;
        for (int mother = 0; mother < ShanhaiWildcardLayout.MOTHER_COUNT; mother++) {
            final ItemStack stack = this.wildcardPatternInventory.getStackInSlot(mother);
            if (stack.isEmpty() || !ShanhaiWildcardCompat.isWildcardPattern(stack)) {
                // 🔴 C3：这个母槽这轮没有展开 ⇒ 把它的槽号<b>还回分配器</b>（水位可能因此下降）。
                this.shanhaiReleaseMotherSlots(mother, 0);
                continue;
            }
            final List<IPatternDetails> expandedList = ShanhaiWildcardCompat.expandPatterns(stack, level);
            if (expandedList.isEmpty()) {
                this.shanhaiReleaseMotherSlots(mother, 0);
                continue;
            }
            mothers++;
            // 🔴 J6：母槽手填类型（{@code gt_shanhai_recipe_type}）—— 本跳唯一的类型来源。
            //    空串 = 自动识别（保持改前行为，不做类型内反查）。
            final String assignedType = ShanhaiWildcardTypeNbt.readCanonical(stack.getTag());
            final List<IPatternDetails> kept = new ArrayList<>(expandedList.size());
            // 🔴 C3：**没有 K 上限**（原版无上限）。先按实际条数把槽号要够，再逐条填。
            //    条数变少时 setCount 会把尾部槽号还回去（并收缩水位）。
            final int want = expandedList.size();
            this.shanhaiSlotAllocator.setCount(mother, want);
            int branch = 0;
            for (IPatternDetails raw : expandedList) {
                final int slot = ShanhaiWildcardLayout.EXPAND_OFFSET + this.shanhaiSlotAllocator.slotFor(mother, branch);
                IPatternDetails effective = null;
                try {
                    effective = ShanhaiForgePatternMode.rewrite(raw, level, forgeMode, multiplier);
                } catch (Throwable t) {
                    // 改写失败 ⇒ 该样板按原样放行（绝不因为改写失败就把样板吞掉）
                    ShanhaiStellarLog.warn("神锻改写抛异常（该样板按原样放行）：{}", String.valueOf(t));
                }
                if (effective == null) {
                    effective = raw;
                }
                // 🔴 2026-10-26 七修：绑定降级为【只读诊断】——**不再决定这条样板去不去**。
                //    类型闸的落点已按用户 2026-10-27 口径移到【槽上】（ShanhaiSlotTypeGateMixin），不在这里。
                this.shanhaiRecordSlotBinding(slot, effective, assignedType, mother, branch);
                // 🔴 C2（2026-10-27）：**只登记实际那一份**（effective）。raw 不再登记 ——
                //    一张 identity 表、一个槽号、一条配方（用户口径："只会一个池对于一条配方"）。
                this.expandedToSlot.put(effective, slot);
                if (effective != raw) {
                    forged++;
                }
                this.effectivePatterns.add(effective);
                kept.add(effective);
                expanded++;
                branch++;
            }
            this.expansionsByMother.put(mother, List.copyOf(kept));
        }
        // 🔴 让所有母槽的分配数与表一致（条数变少 ⇒ 尾部回收；回收的槽号下一次重建可复用）。
        for (int mother = 0; mother < ShanhaiWildcardLayout.MOTHER_COUNT; mother++) {
            final List<IPatternDetails> list = this.expansionsByMother.get(mother);
            this.shanhaiReleaseMotherSlots(mother, list == null ? 0 : list.size());
        }
        this.shanhaiForgedCount = forged;
        final int no = this.shanhaiRebuildPolicy.onBuilt(reason);
        // 🔴 C4 判据行：**只在重建时**出现（行数 = 重建次数），连续两次 getAvailablePatterns() 之间不增加。
        ShanhaiStellarLog.info("PATREBUILD no={} reason={} mothers={} expanded={} forged={} slots={} aeDirty={}"
                        + " ⇒ 本行只会在【变化时建一次】的时机出现（读路径不再重建）",
                Integer.valueOf(no), reason, Integer.valueOf(mothers), Integer.valueOf(expanded),
                Integer.valueOf(forged), Integer.valueOf(this.getInternalSlotCount()),
                Boolean.valueOf(this.shanhaiAePatternDirty));
        this.shanhaiLogExpanded(mothers, expanded, forged);
    }

    /**
     * <b>C3：把母槽 {@code mother} 的展开条数对齐到 {@code count}，并回收多出来的槽。</b>
     *
     * <p>回收动作 = ① 分配器释放槽号（可能收缩水位）② 把对应的 {@link #expandInternalSlots} 对象
     * 清空并丢弃（否则复用者会带着别人的库存上来 = 真"串料"）。
     */
    private void shanhaiReleaseMotherSlots(int mother, int count) {
        final int before = this.shanhaiSlotAllocator.count(mother);
        if (before <= count) {
            if (before < count) {
                this.shanhaiSlotAllocator.setCount(mother, count);
            }
            return;
        }
        // 逐条收：先记下被释放的相对下标，再让分配器回收（这样还能按槽号丢 InternalSlot）。
        final int[] released = new int[before - count];
        for (int b = count; b < before; b++) {
            released[b - count] = this.shanhaiSlotAllocator.slotFor(mother, b);
        }
        this.shanhaiSlotAllocator.setCount(mother, count);
        for (int rel : released) {
            this.shanhaiDropExpandSlot(rel);
        }
    }

    /**
     * <b>只读诊断：把「这一条展开样板 → 它在这个类型下的 GTRecipe」记进
     * {@link #shanhaiSlotBoundRecipe}，供 {@code XRECIPE} 判据行读。</b>
     *
     * <h2>🔴 2026-10-26 七修：它<b>不再</b>决定任何一条样板的去留</h2>
     * 上一轮这里返回 {@code boolean}，解析不到就 {@code continue}（= 不交给 AE）。
     * 原版线路取证把这个做法判错了：
     * <ul>
     *   <li>原版「超级样板总成」里<b>没有任何一条</b>比较"样板类型 vs 配方类型"
     *       （{@code meHandleRecipeInner} 偏移 0–96 = isItemActive + testCatalyst + testVirtual +
     *       handleItemInternal）；</li>
     *   <li>类型闸的正解落点是 <b>{@code meHandleRecipeInner}</b>：在那一跳 {@code return false}，
     *       {@code handleRecipe} 的下一槽逻辑（偏移 242–245 / 341–344）自动接手
     *       ⇒ 一次解释两个现象（不再出杆、也不再拿错槽的料）。</li>
     * </ul>
     * ⇒ 在展开循环里"提前砍样板"会在跨类型主机上误杀本来能跑的样板，故降级为纯记录。
     *
     * <h2>它记什么、记不到时打什么</h2>
     * <ul>
     *   <li>反查到 ⇒ {@code shanhaiSlotBoundRecipe.put(slot, recipe)}，{@link #shanhaiBoundCount}++；</li>
     *   <li>反查不到 ⇒ {@link #shanhaiTypeDroppedCount}++（名字沿用，语义 = "没配上"），
     *       并按 {@code 母槽|类型|verdict} 节流打一行 WARN + 一行 {@code XRECIPE … verdict=…}。
     *       <b>样板照样交给 AE</b>。</li>
     * </ul>
     */
    private void shanhaiRecordSlotBinding(int slot, IPatternDetails pattern, String assignedType,
                                          int mother, int branch) {
        if (assignedType == null || assignedType.isEmpty()) {
            // 自动识别：不反查、不绑定（改前行为）。
            return;
        }
        final ShanhaiWildcardRecipeBinding.Result result = ShanhaiWildcardRecipeBinding.find(pattern, assignedType);
        if (result.found()) {
            this.shanhaiSlotBoundRecipe.put(slot, result.recipe);
            this.shanhaiBoundCount++;
            return;
        }
        this.shanhaiTypeDroppedCount++;
        // 失败必须出声（本工程红线）；按 (母槽|类型|verdict) 节流，免得一块样板展开 60 条刷 60 行。
        final String fingerprint = mother + "|" + assignedType + "|" + result.verdict;
        if (!fingerprint.equals(this.shanhaiBindWarnState)) {
            this.shanhaiBindWarnState = fingerprint;
            ShanhaiStellarLog.warn("通配符样板按类型反查失败（**样板仍交给 AE**，只影响 XRECIPE 判据与绑定）："
                            + "母槽={} 分支={} 类型=[{}] 结论={} 产物={}",
                    mother, branch, assignedType, result, shanhaiPatternDigest(pattern));
            // 🔴 零风险判据：把「没配上」与「宿主压根没这个类型」分开。
            //    hostLacksType=true ⇒ 宿主当前支持的配方类型里没有它（面板上选了本机不支持的类型）；
            //    false ⇒ 宿主支持该类型，但该类型下反查不到这条样板。
            final boolean hostHas = this.shanhaiHostSupportsRecipeType(assignedType);
            ShanhaiStellarLog.warn("XRECIPE slot={} claimOut={} slotType={} execRecipe=none execType=none host={}"
                            + " hostLacksType={} claimOk=false verdict={}（只读诊断：样板仍交给 AE）",
                    Integer.valueOf(slot), shanhaiPatternDigest(pattern), assignedType, this.shanhaiHostKind(),
                    Boolean.valueOf(!hostHas),
                    hostHas ? result.verdict.name() : "NO_ACTIVE_TYPE");
        }
    }


    /**
     * 宿主当前支持的配方类型集合里有没有 {@code typeId} —— <b>只用于日志分类，不参与任何行为判定</b>。
     *
     * <p>数据源就是面板选择器用的那一份 {@link #shanhaiHostRecipeTypeIds}（{@code applyWildcardRecipeType}
     * 里对它的用法在 3866 行）。判不出（列表还没算出来 / 解析异常）⇒ 当作"支持"，
     * 于是日志退化成原来的 {@code verdict}，<b>不影响任何一条样板的去留</b>。
     */
    private boolean shanhaiHostSupportsRecipeType(@NotNull String typeId) {
        if (typeId.isEmpty()) {
            return true;
        }
        try {
            return ShanhaiRecipeTypeLabels.splitIds(this.shanhaiHostRecipeTypeIds).contains(typeId);
        } catch (Throwable t) {
            return true;
        }
    }


    /** 母槽手填类型（空串 = 未填）。展开槽走母槽；其它槽返回空串。 */
    private @NotNull String shanhaiAssignedTypeOfSlot(int slot) {
        if (!this.shanhaiIsExpandSlotNow(slot)) {
            return "";
        }
        final int mother = this.shanhaiSlotAllocator.motherOf(slot - ShanhaiWildcardLayout.EXPAND_OFFSET);
        if (mother < 0 || mother >= ShanhaiWildcardLayout.MOTHER_COUNT) {
            return "";
        }
        final ItemStack stack = this.wildcardPatternInventory.getStackInSlot(mother);
        return stack.isEmpty() ? "" : ShanhaiWildcardTypeNbt.readCanonical(stack.getTag());
    }
    /**
     * <b>宿主形态</b>：{@code cross} = 挂在授予「跨配方种类并行」的主机上；{@code normal} = 多类型主机；
     * {@code single} = 单类型 / 读不到控制器。
     *
     * <p>判定口径与模块侧<b>逐字同源</b>（{@code PrimordialModuleRecipeLogic#shanhai$isCrossTypeEnabled()}）：
     * {@code host != null && host.grantsCrossTypeParallel()}；老主机那个方法默认 {@code false}
     * ⇒ 普通机器上的判定结果与改动前一致。
     */
    private @NotNull String shanhaiHostKind() {
        try {
            final java.util.List<? extends com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController> controllers =
                    this.getControllers();
            if (controllers == null || controllers.isEmpty()) {
                return "single";
            }
            int maxTypes = 0;
            boolean cross = false;
            for (Object controller : controllers) {
                if (controller instanceof com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine logicMachine) {
                    final com.gregtechceu.gtceu.api.recipe.GTRecipeType[] types = logicMachine.getRecipeTypes();
                    if (types != null) {
                        maxTypes = Math.max(maxTypes, types.length);
                    }
                }
                if (controller instanceof com.shanhai.common.machine.PrimordialOmegaEngineMachine host
                        && host.grantsCrossTypeParallel()) {
                    cross = true;
                }
            }
            if (cross) {
                return "cross";
            }
            return maxTypes > 1 ? "normal" : "single";
        } catch (Throwable t) {
            return "single";
        }
    }

    /**
     * <b>XRECIPE 判据</b>（一条判死「认领的样板」与「要跑的配方」是否分叉）。
     *
     * <pre>
     *   XRECIPE slot=1362 claimOut=gtceu:copper_single_wirex2 slotType=gtceu:wiremill
     *           execRecipe=gtceu:copper_single_wire execType=gtceu:wiremill host=cross
     *           claimOk=true verdict=OK
     * </pre>
     * 判读：
     * <ul>
     *   <li>{@code verdict=OK} ⇒ 槽里绑的配方类型 = 手填类型 ⇒ 这条样板会被正确类型跑；</li>
     *   <li>{@code CROSS_TYPE} ⇒ <b>绑错了</b>（执行层拿到的是另一类型的配方）—— 实机「要导线出杆」就是这个；</li>
     *   <li>{@code NO_BIND} ⇒ 该槽还没绑定配方（J7 缺口 / 该样板是自己认领上来的而类型为空）。</li>
     * </ul>
     * 节流：同 (槽|verdict|类型) 指纹 20 tick 内只打一行。
     */
    private void shanhaiXrecipeProbe(@Nullable appeng.api.crafting.IPatternDetails pattern, boolean claimOk) {
        if (this.isRemote() || this.getLevel() == null) {
            return;
        }
        final Integer slotObj = this.getSlotIndexForPattern(pattern);
        if (slotObj == null) {
            return;
        }
        final int slot = slotObj.intValue();
        final String slotType = this.shanhaiAssignedTypeOfSlot(slot);
        final com.gregtechceu.gtceu.api.recipe.GTRecipe bound = this.shanhaiSlotBoundRecipe.get(slot);
        final String execType = bound == null ? "none" : ShanhaiWildcardRecipeBinding.typeIdOf(bound);
        final String verdict;
        if (bound == null) {
            verdict = slotType.isEmpty() ? "NO_BIND" : "NO_BIND";
        } else if (!slotType.isEmpty() && !slotType.equals(execType)) {
            verdict = "CROSS_TYPE";
        } else {
            verdict = "OK";
        }
        final String state = slot + "|" + verdict + "|" + slotType + "|" + execType;
        final long now = this.getLevel().getGameTime();
        if (state.equals(this.shanhaiXrecipeLogState) && this.shanhaiXrecipeLogTick != Long.MIN_VALUE
                && now - this.shanhaiXrecipeLogTick < 20L) {
            return;
        }
        this.shanhaiXrecipeLogState = state;
        this.shanhaiXrecipeLogTick = now;
        ShanhaiStellarLog.info("XRECIPE slot={} claimOut={} slotType={} execRecipe={} execType={} host={} claimOk={} verdict={}",
                Integer.valueOf(slot), shanhaiPatternDigest(pattern),
                slotType.isEmpty() ? "none" : slotType,
                ShanhaiWildcardRecipeBinding.describe(bound),
                execType.isEmpty() ? "none" : execType,
                this.shanhaiHostKind(), Boolean.valueOf(claimOk), verdict);
    }

    /**
     * <b>EXPAND 判据</b>：一次重建一行 —— 「重建完了，而且这批新实例 AE 一定拿得到」。
     *
     * <pre>
     *   EXPAND pos=uselessdim@1821,91,273 mothers=3 expanded=57 forged=57 aeSync=true reason=forgeMultiplier:20
     * </pre>
     * 字段判读（下一次实机就照这个读）：
     * <ul>
     *   <li>{@code forged == expanded} ⇒ 每条展开样板都过了神锻（倍率真的乘上去了）；</li>
     *   <li>{@code forged=0} 而 {@code expanded>0} ⇒ 一条都没改写：看 {@code forge} 开关、
     *       生效倍率是否 &le; 1、{@link ShanhaiForgePatternMode#isAvailable()}；</li>
     *   <li>{@code aeSync=false} ⇒ <b>重建了但没人叫醒 AE</b>（也不是 AE 自己在问）
     *       ⇒ AE 手里那批旧实例已失效，这就是 {@code reason=patternNotOurs} 的成因，必须为 true。</li>
     * </ul>
     * 节流：同一指纹 20 tick 内只打一行（重建本身是事件驱动的，一次变化正常只出一行）。
     */
    private void shanhaiLogExpanded(int mothers, int expanded, int forged) {
        if (this.isRemote() || this.getLevel() == null) {
            return;
        }
        // aeSync 的定义 = 「这批新实例 AE 一定会看到」：要么脏标记挂着（下一 tick requestUpdate），
        // 要么这次重建就是 AE 亲自来问触发的（它当场就拿到了）。
        final boolean aeSync = this.shanhaiAePatternDirty || this.shanhaiInAePatternQuery;
        final String state = mothers + "|" + expanded + "|" + forged + "|" + aeSync + "|" + this.shanhaiAePatternReason;
        final long now = this.getLevel().getGameTime();
        if (state.equals(this.shanhaiExpandLogState) && this.shanhaiExpandLogTick != Long.MIN_VALUE
                && now - this.shanhaiExpandLogTick < 20L) {
            return;
        }
        this.shanhaiExpandLogState = state;
        this.shanhaiExpandLogTick = now;
        final String verdict;
        if (!aeSync) {
            verdict = "⚠️ 重建后既没标脏、也不是 AE 来问 ⇒ 这批新实例没人认"
                    + "（查该置脏点有没有走 shanhaiInvalidateGateCache / shanhaiRequestAePatternSync）";
        } else if (expanded > 0 && forged == expanded) {
            verdict = "每条展开样板都过了神锻（倍率生效）";
        } else if (expanded > 0 && forged == 0) {
            verdict = "一条都没改写：看 forge 开关 / 生效倍率是否 <=1 / ShanhaiForgePatternMode.isAvailable()";
        } else {
            verdict = "部分改写（forge=" + forged + "/" + expanded + "）";
        }
        ShanhaiStellarLog.info("EXPAND pos={} mothers={} expanded={} forged={} aeSync={} reason={} ⇒ {}",
                this.shanhaiPos(), Integer.valueOf(mothers), Integer.valueOf(expanded), Integer.valueOf(forged),
                Boolean.valueOf(aeSync), this.shanhaiAePatternReason, verdict);
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
        // 🔴 C4：诊断也不重建（要看最新的先让事件点跑一次）。
        ShanhaiStellarLog.info("展开布局（{}）：{}", where, this.shanhaiSlotSpaceDescribe());
        for (java.util.Map.Entry<Integer, List<IPatternDetails>> entry : this.expansionsByMother.entrySet()) {
            final int mother = entry.getKey();
            int branch = 0;
            for (IPatternDetails ignored : entry.getValue()) {
                final int slot = ShanhaiWildcardLayout.EXPAND_OFFSET + this.shanhaiSlotAllocator.slotFor(mother, branch);
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
     * （{@code EXPAND_OFFSET..getInternalSlotCount()-1}，由分配器反查 —— C3 起槽号是动态发放的）。
     */
    private int shanhaiWildcardMotherOfSlot(int slot) {
        if (slot >= WILDCARD_API_SLOT_OFFSET && slot < WILDCARD_API_SLOT_OFFSET + WILDCARD_SLOT_COUNT) {
            return slot - WILDCARD_API_SLOT_OFFSET;
        }
        if (this.shanhaiIsExpandSlotNow(slot)) {
            return this.shanhaiSlotAllocator.motherOf(slot - ShanhaiWildcardLayout.EXPAND_OFFSET);
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
         * 🔴 <b>2026-10-26 七修：恢复无条件 {@code super} —— 绑定表不再影响候选。</b>
         *
         * <h2>为什么撤销上一轮那个"J7 候选注入"</h2>
         * 原版线路取证（{@code temp\origchain\报告.md}）给了两条决定性事实：
         * <ol>
         *   <li>原版 {@code GTRecipeLookupMixin#getRecipeIterator} 偏移 <b>105–128</b> 本来就有
         *       {@code recipe.recipeType == this.recipeType} 过滤 ⇒「缓存配方只是补充候选」是原版既有语义；</li>
         *   <li>{@code RecipeIteratorMixin#hasNext()} 偏移 0–62 先跑原候选、<b>65–69 才轮到 additional</b>
         *       ⇒ 我们注入的绑定<b>永远排在激活类型之后</b>，抬高不了优先级。</li>
         * </ol>
         * ⇒ 这层注入唯一的效果是在跨类型主机上"多给候选"，而"该不该跑"原本要由
         * {@code meHandleRecipeInner} 的类型闸决定 —— <b>那道闸已于 2026-10-27 按用户指令整体拆除</b>
         * （否决式筛选失效，改走「每个样板独立池 / 分池收口」；见 {@code temp\typeroute\报告.md}）。
         * 放在这里只会让判定更模糊，所以撤掉。
         *
         * <p>{@link #shanhaiSlotBoundRecipe} 仍然保留，但已降级为<b>只读诊断表</b>
         * （只喂 {@code XRECIPE} 判据行），<b>不参与任何返回值与判定</b>。
         */
        @Override
        public it.unimi.dsi.fastutil.objects.ObjectSet<com.gregtechceu.gtceu.api.recipe.GTRecipe> getCachedGTRecipe() {
            return super.getCachedGTRecipe();
        }

        /**
         * 🔴 <b>2026-10-26 七修：恢复无条件 {@code super}（≥1080 仍然直接 return —— 那不是闸，是防越界）。</b>
         *
         * <h2>为什么撤掉上一轮那道"记账闸"</h2>
         * 原版线路取证：{@code MEPatternRecipeHandlePart#handleRecipe} 里
         * <b>{@code setSlotCacheRecipe} 在偏移 403、{@code return slot} 在 408–410</b>
         * ⇒ 记账发生在<b>认领之后</b>。把闸装在这里最多只能"事后不记账"，
         * <b>拦不住本次认领</b>（本次那条错配方照样被认领、照样消费这一槽的料）。
         * ⇒ 闸的正确落点是 {@code meHandleRecipeInner}，但<b>该闸已按用户指令整体拆除</b>
         * （2026-10-27：否决式筛选失效 ⇒ 改「每个样板独立池」= 分池收口），本方法回归纯记账。
         *
         * <h2>为什么 ≥1080 仍然直接 return（这不是类型闸）</h2>
         * 父类 {@code MEPatternTrait#setSlotCacheRecipe} 体内会写 {@code cacheRecipe[slot]}，
         * 该数组长度 = 1080（运行时读数见 {@link #shanhaiProveCacheArrayBounds(String)}）
         * ⇒ 展开槽传下去就是当场 AIOOBE。这一条从阶段一就在，<b>与本轮的类型闸无关</b>。
         */
        @Override
        public void setSlotCacheRecipe(int slot, com.gregtechceu.gtceu.api.recipe.GTRecipe recipe) {
            if (slot >= ShanhaiWildcardLayout.PATTERN_SLOTS) {
                return;
            }
            super.setSlotCacheRecipe(slot, recipe);
        }

        /**
         * 🔴 <b>2026-10-25 八修（崩服修复）：这一段以前【只写在 javadoc 里，从来没实现过】。</b>
         *
         * <h2>现场（实机 crash-reports 原文，16:36:55）</h2>
         * <pre>
         * java.lang.ArrayIndexOutOfBoundsException: Index 1080 out of bounds for length 1080
         *   at MEPatternBufferPartMachine$MEPatternTrait.hasCacheInSlot(MEPatternBufferPartMachine.java:1014)
         *   at StellarSampleAssemblerPartMachine.shanhaiLogBoundaryProof(…:6305)
         *   at StellarSampleAssemblerPartMachine.shanhaiSyncStock(…:7651)
         *   at …syncStockInput(…:6583) ← MEStockingPatternBufferPartMachine.update(…:106)
         * </pre>
         * 父类 {@code hasCacheInSlot} 直接读 {@code cacheRecipe[slot]}，该数组长度 = <b>1080</b>
         * （= 父类构造参数 maxPatternCount），而本机槽号空间 = 1080 + 通配展开 ⇒ 拿 ≥1080 的槽号去问就是当场 AIOOBE。
         *
         * <h2>为什么必须在这里短路（不是可选优化）</h2>
         * 生产路径 {@code MEPatternRecipeHandlePart#handleRecipe} 偏移 378 对<b>每个槽</b>都调
         * {@code IMEPatternTrait#hasCacheInSlot(slot)}，槽号正是 {@code getInternalSlotCount()} 那套
         * ⇒ <b>探针只是先撞上而已，正常认领迟早也会撞</b>。
         *
         * <h2>语义（与 {@link #hasRecipeCacheInSlot(int)} 对齐）</h2>
         * 通配符段一律视为「已进缓存表」⇒ {@code handleRecipe} 会跳过事后记账（那块由我们自己的展开表管），
         * 并且<b>绝不进父类数组</b>。
         */
        @Override
        public boolean hasCacheInSlot(int slot) {
            if (slot >= ShanhaiWildcardLayout.PATTERN_SLOTS) {
                return true;
            }
            return super.hasCacheInSlot(slot);
        }
    }

    /**
     * <b>「不会越界」的边界证明</b>（一行 grep 判读，每个存档只打一次）。
     *
     * <p>它不算「结论」，它把<b>两个真值</b>一起摆出来：
     * <ul>
     *   <li>{@code cacheRecipeLen} —— 直接读父类那个 {@code protected final boolean[] cacheRecipe} 的
     *       {@code length}（<b>不是</b>硬编码常量，读的就是运行时那个数组）；</li>
     *   <li>{@code slotSpace} —— {@code getInternalSlotCount()}（本类覆写 = 7530；K=128）。</li>
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
        // 现场探针：通配符段两端 + 通配符段内一格 + 普通段末一格。
        // 🔴 2026-10-25 八修：**每个下标都必须先过 arrayLen 闸**——取证读数（STOCKSAFE 那一行）要的是
        //    「父类数组有多长 / 本机槽号空间有多大」，不是「父类会不会抛异常」。
        //    上一版把这 4 次调用裸放，第 1 个（MOTHER_OFFSET=1080）就 AIOOBE 崩了整个服务端线程。
        // 🔴 2026-10-25：这 4 次调用必须打上「自证探针」标记，否则它会把自己算进 STOCKSLOT 的
        //    calls[] 里（实机日志里那句 hasCache=4 / hi hasCache=2 就是它自己，见 shanhaiInSelfProbe）。
        boolean hiFirst = false;
        boolean hiLast = false;
        boolean loFirst = false;
        boolean loLast = false;
        int skipped = 0;
        if (trait != null) {
            this.shanhaiInSelfProbe = true;
            try {
                final int[] probeSlots = {
                        ShanhaiWildcardLayout.MOTHER_OFFSET,
                        this.getInternalSlotCount() - 1,
                        0,
                        ShanhaiWildcardLayout.PATTERN_SLOTS - 1,
                };
                final boolean[] out = new boolean[probeSlots.length];
                for (int i = 0; i < probeSlots.length; i++) {
                    final int slot = probeSlots[i];
                    if (slot < 0 || slot >= arrayLen) {
                        // 越界下标一律不喂给父类（这正是刚才那次崩服的形态）；记一次 skipped 供判读。
                        out[i] = false;
                        skipped++;
                    } else {
                        out[i] = trait.hasCacheInSlot(slot);
                    }
                }
                hiFirst = out[0];
                hiLast = out[1];
                loFirst = out[2];
                loLast = out[3];
            } finally {
                this.shanhaiInSelfProbe = false;
            }
        }
        ShanhaiStellarLog.info("STOCKSAFE cacheRecipeLen={} slotSpace={} gap={} guard=[hiFirst={} hiLast={} loFirst={} loLast={} skipped={}]"
                        + " trait={} ⇒ {}",
                Integer.valueOf(arrayLen), Integer.valueOf(slotSpace), Integer.valueOf(slotSpace - arrayLen),
                Boolean.valueOf(hiFirst), Boolean.valueOf(hiLast), Boolean.valueOf(loFirst), Boolean.valueOf(loLast),
                Integer.valueOf(skipped),
                trait == null ? "null" : trait.getClass().getSimpleName(),
                arrayLen < slotSpace
                        ? "下标 " + arrayLen + ".." + (slotSpace - 1) + " 若进数组必 AIOOBE；本探针已按 cacheRecipeLen 过滤（skipped 即被挡下的探针次数）"
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
        // 🔴 2026-10-26（K=4⇒128）：**不能**调 super —— 父类那份是
        //   `new int[getInternalSlotCount()]` + 从 0 扫到 7530（`MEPatternBufferPartMachineBase:398`）。
        //   本类的候选集 = [0,1130) + 真正展开出来的展开槽 ⇒ 语义等价、槽号升序不变、每次少扫 6400 格。
        final int[] base = this.shanhaiCandidateActiveSlots();
        final int[] extra = this.shanhaiStockSupplyActiveSlots();
        final int[] result = extra.length == 0 ? base : shanhaiMergeSlotArrays(base, extra);
        // 🔴 2026-10-27 ACTIVESLOTS：**配方逻辑枚举输入时看到的槽**（40 tick 节流，带 pos）。
        //    判读口径（用户口径）：
        //      active=0                    ⇒ 一条活跃槽都没有 ⇒ 引擎压根没料可匹配；
        //      expandedInList=0            ⇒ 列表里没有 ≥1080 的槽 ⇒ 展开槽没进候选视野（首嫌疑）；
        //      withItems=0                 ⇒ 槽在，但槽里没有物品 ⇒ 推料落点/内容物枚举问题。
        try {
            this.shanhaiActiveSlotsProbe(result);
        } catch (Throwable ignored) {
            // 探针永远不许影响配方查找
        }
        return result;
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
     * <h2>🔴 二修（2026-10-24）：这一段为什么现在是 0..7530 而不是 0..1079</h2>
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
     * 放行名单覆盖到的槽号<b>上界</b> —— 二修之后就是<b>整段槽号空间</b>（{@code getInternalSlotCount()} = 7530）。
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
        // 同上：不调 super（那次调用会扫 7530 格、分配 30 KB 的 int[]，而这条是配方查找热路径）。
        final int[] base = this.shanhaiActiveAndUnCachedCandidates();
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
        // 二修：整段槽号空间（0..SLOT_SPACE_TOTAL-1）。原来这里是 Math.min(..., PATTERN_SLOTS) —— 那正是
        // 「样板都在通配符段 ⇒ 放行名单为空 ⇒ 库存一次都没被问」的直接原因。
        // 🔴 2026-10-26（K 4⇒128）：扫的是候选集，而不是 7530 格 —— 空展开槽
        //    `hasPatternInSlot` 恒 false，本来就进不了名单（语义等价，代价从 7530 降到 ~1130+展开数）。
        final int[] candidates = this.shanhaiCandidateSlots();
        final int count = Math.min(this.shanhaiStockSupplySlotSpace(), this.getInternalSlotCount());
        final int limit = Math.min(count, SHANHAI_STOCK_SUPPLY_SLOT_CAP);
        final int[] out = new int[limit];
        int n = 0;
        int dropped = 0;
        for (int slot : candidates) {
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
        this.shanhaiSyncStock("tick");
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

    /**
     * 本机有没有任何一块样板（只在 5 分钟节流的那条判据里调，不每 tick 跑）。
     *
     * <p>2026-10-26（K 4⇒128）：扫的<b>不是</b> 7530 格，而是候选集（[0,1130) + 真正展开出来的槽）
     * —— 被跳过的展开槽 {@code hasPatternInSlot} 恒 false，语义等价。
     */
    private boolean shanhaiAnyPattern() {
        for (int slot : this.shanhaiCandidateSlots()) {
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
        // 🔴 2026-10-25 改：active=0 时原来说「配方匹配这一轮根本没问过任何槽」，读的人没法判断
        //    「是没样板 ⇒ 本来就不该跑」还是「有样板但没被放行 ⇒ 我们的锅」。这里把「本机样板数」
        //    也摆进结论（与 STOCKPAT 的容器真值同一口径，且它只在节流命中后才算）。
        final String verdict;
        if (active == null || active.length == 0) {
            final int patterns = this.shanhaiAnyPattern() ? 1 : 0;
            verdict = "活跃名单为空：配方匹配这一轮根本没问过任何槽（本机有样板=" + patterns
                    + "；=0 ⇒ 没有可跑的配方，库存供料这条链按设计不会被调用，不是供料坏了）";
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
                        + "hasCache={} findItem={} findFluid={} selfProbe={}] hi[isItemActive={} handleItem={} hasCache={}] "
                        + "claimed=[{}] ⇒ {} {}",
                Integer.valueOf(this.getInternalSlotCount()),
                Integer.valueOf(active == null ? 0 : active.length),
                Integer.valueOf(supply == null ? 0 : supply.length),
                Long.valueOf(this.shanhaiProbeIsItemActive), Long.valueOf(this.shanhaiProbeHandleItem),
                Long.valueOf(this.shanhaiProbeHasCache), Long.valueOf(this.shanhaiProbeFindItem),
                Long.valueOf(this.shanhaiProbeFindFluid), Long.valueOf(this.shanhaiProbeSelfHasCache),
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
        } else if (this.shanhaiIsExpandSlotNow(slot)) {
            final int rel = slot - ShanhaiWildcardLayout.EXPAND_OFFSET;
            seg = "expand" + this.shanhaiSlotAllocator.motherOf(rel) + "." + this.shanhaiSlotAllocator.branchOf(rel);
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
     *   STOCKPAT pos=uselessdim@1887,90,225 slots=1330 lo=N hi=M supply=S gateBlocked=G
     *            containers=[basePatterns=B wildcardInv=W mothers=Mth expands=Me] hiSlots=[1080,1120] ⇒ 判读
     * </pre>
     *
     * <p>🔴 <b>{@code pos=} 是 2026-10-26 补的（风险 0）</b>：实机日志里至少有 8 个不同坐标的星律
     * （{@code overworld@-14,94,13 / 0,108,13 / 0,108,25}、{@code uselessdim@1812/1821/1830/1839,90,273}、
     * {@code uselessdim@1887,90,225}），而这一行原来<b>没打坐标</b> ⇒ 14:42 那两条
     * {@code basePatterns=0 wildcardInv=0 mothers=0} 到底说的是哪台机，事后无法判定。
     * 格式 = {@code <维度id>@<x>,<y>,<z>}（{@link #shanhaiPos()}）。
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
        // 2026-10-26（K 4⇒128）：扫候选集而不是 7530 格（空展开槽本来就不会计数）。
        final int[] censusCandidates = this.shanhaiCandidateSlots();
        final int space = censusCandidates.length;
        for (int slot : censusCandidates) {
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
        // 2026-10-26（K 4⇒128）：不能按 EXPAND_COUNT 扫（那是 6400 格），按真正展开出来的条数扫。
        for (List<IPatternDetails> live : this.expansionsByMother.values()) {
            expands += live.size();
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
            verdict = "本机（星律）" + this.getInternalSlotCount()
                    + " 格槽号空间里真的一块样板都没有 —— 样板不在本机，看同一节的 STOCKWHO 说在谁身上";
        } else {
            verdict = "本机有样板：普通段 " + lo + " 块、通配符段 " + hi + " 块"
                    + (hi > 0 ? "（母槽 " + mothers + " + 展开 " + expands + "）" : "") + "；覆写与容器一致";
        }
        ShanhaiStellarLog.info("STOCKPAT pos={} slots={} lo={} hi={} supply={} gateBlocked={} "
                        + "containers=[basePatterns={} wildcardInv={} mothers={} expands={}] hiSlots=[{}] ⇒ {}",
                this.shanhaiPos(),
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
     *   STOCKWHO pos=uselessdim@1887,90,225 controllers=1 parts=7
     *            providers=[MESuperPatternBufferPartMachine=12, StellarSampleAssemblerPartMachine=0]
     *            own=0 ⇒ 样板不在本机，在 MESuperPatternBufferPartMachine（12 条）
     * </pre>
     * <p>🔴 {@code pos=} 是 2026-10-26 补的：这一行原来只说「样板在谁身上」，不说「本机是哪一台」，
     * 多台星律同时在线时无法把 {@code providers=[…=0]} 对回具体那台机。
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
        ShanhaiStellarLog.info("STOCKWHO pos={} controllers={} parts={} providers=[{}] own={} ⇒ {}",
                this.shanhaiPos(),
                Integer.valueOf(this.getControllers() == null ? 0 : this.getControllers().size()),
                Integer.valueOf(partCount), providers.length() == 0 ? "none" : providers.toString(),
                Integer.valueOf(own), verdict);
    }

    // ================================================================ 2026-10-26 四修：让下一次实机「一条日志判死」
    //
    //  用户报「通配符下单卡住」＋ AE 屏 `合成状态 - 2562047:47:16`。上一轮只读取证已钉死：
    //    ① 那串数字 = AE 的 ETA 溢出饱和（`progress == 0` 且 `startItemCount = 2147483647`
    //       ⇒ `eta = elapsed × 2.1e9` 饱和成 `Long.MAX`），**不是**"等料"、**不是**配方时长异常；
    //    ② `addMaxItems`（已开工）只在 `executeCrafting` 里 `pushPattern` **成功**时调
    //       ⇒ AE 确实把 46 件派出去了；
    //    ③ 但产物一件都没回：`meHandleRecipeOutputInner → buffer → Ticker → AEUtils.reFunds`
    //       这条链的 `buffer` 是空的（`buffer` 是内存 map，**唯一写入点**就是 `meHandleRecipeOutputInner`）。
    //
    //  ⇒ 本节补的不是"再猜一次"，而是三组能把上面三件事**分别**钉死的读数：
    //    · {@link #shanhaiLogClaim}（RECIPE claim）   —— 这单到底有没有被**本机**认领；
    //    · {@link #shanhaiTickTickerProbe}（TICKER）  —— 产物有没有进 buffer、有没有被退回网络；
    //    · {@link #shanhaiLogHandlersOnce}（HANDLERS）＋ {@link #shanhaiLogBufferGate}（BUFFERGATE）
    //         —— 本机在 capability 链里排第几、产物该由谁写进 buffer、就绪闸卡在哪一条。
    //
    //  ⚠️ 三组读数**都不改行为**：只读字段/只数次数，且全部带节流（见各自注释）。

    /**
     * 本机位置的统一写法 {@code <维度id>@<x>,<y>,<z>}。
     *
     * <h2>🔴 为什么 must have（实机日志实证，不是洁癖）</h2>
     * 用户那份日志里至少有 <b>8 个不同坐标</b>的星律同时在跑：
     * <pre>
     *   overworld@-14,94,13     overworld@0,108,13     overworld@0,108,25
     *   uselessdim@1812,90,273  uselessdim@1821,90,273 uselessdim@1830,90,273
     *   uselessdim@1839,90,273  uselessdim@1887,90,225
     * </pre>
     * 而 {@code STOCKPAT / STOCKWHO / PATEXPORT} 三行<b>一个坐标都没打</b>
     * ⇒ 14:42 那两条 {@code STOCKPAT … basePatterns=0 wildcardInv=0 mothers=0}
     * 到底说的是哪一台机，事后<b>无法判定</b>；整轮取证都困在"多台机器混在一起"的日志里对账。
     *
     * <p>永不抛（探针自己抛异常会把 tick 带崩）；取不到维度时写 {@code ?}。
     */
    private @NotNull String shanhaiPos() {
        try {
            final net.minecraft.world.level.Level level = this.getLevel();
            final net.minecraft.resources.ResourceLocation dim =
                    level == null ? null : level.dimension().location();
            final net.minecraft.core.BlockPos pos = this.getPos();
            return (dim == null ? "?" : dim.toString())
                    + '@' + pos.getX() + ',' + pos.getY() + ',' + pos.getZ();
        } catch (Throwable t) {
            return "?";
        }
    }

    // ---- ②-1 TICKER（buffer / pushed / refunded）的字段
    //
    //  「refunded」的诚实定义：`AEUtils.reFunds` 在父类 `MEPatternBufferPartMachine$Ticker` 里被调，
    //  那个类是本类**够不到**的（private static final 内嵌类，字段都是 package-private）。
    //  但 `buffer` 的**唯一写入点**是本机的 `meHandleRecipeOutputInner`（产物入账），
    //  **唯一清空者**就是 Ticker 里那次 `AEUtils.reFunds`（退款成功才会被清掉）
    //  ⇒ 「buffer 从非空变成空」这一次跃变，与「AEUtils.reFunds 成功」是同一事件。
    //  所以这里数的是**跃变次数**（drains），不是把 reFunds 包一层 —— 既不碰上游，也不会双退款。

    /** 上次打印时的状态指纹（变了立刻打，否则 100 tick 一行）。 */
    private String shanhaiTickerState = "";

    /** TICKER 的上一次打点游戏时间。 */
    private long shanhaiTickerLogTick = Long.MIN_VALUE;

    /** 上一 tick 的 buffer 条目数（用来抓「>0 → 0」这次跃变 = 一次退款成功）。 */
    private int shanhaiTickerLastBufferSize = 0;

    /** 累计退款清空次数（= AEUtils.reFunds 成功次数，见上面口径说明）。 */
    private long shanhaiTickerRefundDrains = 0L;

    /** 距上次打印以来 `pushPattern` 成功的次数（= 本次窗口里 AE 派给本机的件数）。 */
    private long shanhaiTickerPushOkSinceLog = 0L;

    /** 累计 `pushPattern` 成功次数。 */
    private long shanhaiTickerPushOkTotal = 0L;

    /** buffer 连续非空的 tick 数（判「产物压着不退」）。 */
    private long shanhaiTickerBusyTicks = 0L;

    /** TICKER 是否已经打过第一行（第一行无条件打）。 */
    private boolean shanhaiTickerFirstLogged = false;

    // ---- ②-3 RECIPE claim 的字段

    /** `pushPattern` 被调用总次数。 */
    private long shanhaiClaimCalls = 0L;

    /** `pushPattern` 返回 true 的次数。 */
    private long shanhaiClaimOk = 0L;

    /** 已经打出去几行 RECIPE claim（节流用）。 */
    private long shanhaiClaimLogged = 0L;

    /** 上次打印时的成败（-1 = 还没打过）；成败翻转必打一行。 */
    private int shanhaiClaimLastOk = -1;

    // ---- ②-2 HANDLERS / ③-2 BUFFERGATE 的字段

    /** HANDLERS 只在「结构成型后」打一次。 */
    private boolean shanhaiHandlersLogged = false;

    /** BUFFERGATE 的上一次打点（300 tick 节流）。 */
    private long shanhaiBufferGateTick = Long.MIN_VALUE;

    /** 本机两个库存 handler「把料吃干后返回 null（= 短路）」的次数（HANDLERS 的判据）。 */
    private long shanhaiStockItemNullReturns = 0L;

    private long shanhaiStockFluidNullReturns = 0L;

    /**
     * <b>TICKER</b>：一行说清「产物在不在 buffer / 有没有被退回网络 / AE 到底派了多少件给本机」。
     *
     * <h2>为什么这一行能判死 ③</h2>
     * 上一轮的结论是「buffer 是空的 ⇒ 产物一件都没回」。但<b>空 buffer</b> 有三种完全不同的含义，
     * 只看"空"分不出来：
     * <ol>
     *   <li>{@code buffer=0 且 pushedTotal=0} ⇒ AE <b>从没</b>把单派给本机（这台的卡点不在产物返还，
     *       在"AE 认不认这台机的样板"，看 RECIPE claim / STOCKWHO）；</li>
     *   <li>{@code buffer=0 且 pushedTotal>0} ⇒ AE 派过单、本机也认领了，<b>但一件产物都没进 buffer</b>
     *       ⇒ `meHandleRecipeOutputInner` 根本没被调（看 BUFFERGATE 的就绪闸）；</li>
     *   <li>{@code buffer>0} ⇒ 产物进来了、只是没退回去（看 busyTicks / grid / CPU）。</li>
     * </ol>
     *
     * <p>格式（字段名固定，便于 grep 与断言）：
     * <pre>
     *   TICKER pos=uselessdim@1887,90,225 buffer=0 pushed=46 refunded=0 pushedTotal=46 busyTicks=0 ⇒ 判读
     * </pre>
     * 字段含义：{@code buffer} = 当前 buffer 条目数；{@code pushed} = <b>本次窗口</b>内 pushPattern 成功次数；
     * {@code refunded} = 累计「buffer 非空→空」跃变次数（= AEUtils.reFunds 成功次数，口径见字段注释）；
     * {@code pushedTotal} = 累计成功次数；{@code busyTicks} = buffer 已连续非空几 tick。
     */
    private void shanhaiTickTickerProbe() {
        if (this.isRemote() || this.getLevel() == null) {
            return;
        }
        final int n = this.buffer == null ? -1 : this.buffer.size();
        if (this.shanhaiTickerLastBufferSize > 0 && n == 0) {
            this.shanhaiTickerRefundDrains++;
        }
        this.shanhaiTickerLastBufferSize = n;
        if (n > 0) {
            this.shanhaiTickerBusyTicks++;
        } else {
            this.shanhaiTickerBusyTicks = 0L;
        }
        final long now = this.getLevel().getGameTime();
        final String state = n + "|" + this.shanhaiTickerPushOkTotal + "|" + this.shanhaiTickerRefundDrains
                + "|" + (this.shanhaiTickerBusyTicks >= 60L ? 1 : 0);
        // 硬下限 20 tick：46 件的单会在几十 tick 内连续 pushPattern，每次 push 都算"变化"，
        // 不设下限就会刷 46 行。被压下的变化不会丢 —— 指纹只在**打印时**写，下次一定还"不一样"。
        if (this.shanhaiTickerFirstLogged
                && this.shanhaiTickerLogTick != Long.MIN_VALUE
                && now - this.shanhaiTickerLogTick < 20L) {
            return;
        }
        if (this.shanhaiTickerFirstLogged
                && state.equals(this.shanhaiTickerState)
                && this.shanhaiTickerPushOkSinceLog == 0L
                && this.shanhaiTickerLogTick != Long.MIN_VALUE
                && now - this.shanhaiTickerLogTick < 100L) {
            return;
        }
        this.shanhaiTickerFirstLogged = true;
        this.shanhaiTickerState = state;
        this.shanhaiTickerLogTick = now;
        final long pushed = this.shanhaiTickerPushOkSinceLog;
        this.shanhaiTickerPushOkSinceLog = 0L;
        final String verdict;
        if (n > 0) {
            verdict = this.shanhaiTickerBusyTicks >= 60L
                    ? "产物压在 buffer 里已经 " + this.shanhaiTickerBusyTicks
                    + " tick 没退 ⇒ Ticker 没在跑（grid 掉线 / io 不是 IO.BOTH / CPU 不接）"
                    : "产物刚写进 buffer（下一 tick 应被 Ticker 退掉；连续几次都 >0 就是退不掉）";
        } else if (this.shanhaiTickerPushOkTotal > 0L) {
            verdict = "AE 已把单派给本机（累计 pushPattern ok=" + this.shanhaiTickerPushOkTotal
                    + " 次）但 buffer 一次都没非空过 ⇒ 产物没走到 meHandleRecipeOutputInner"
                    + "（谁的锅看 BUFFERGATE 那一行的 slotsWith…/meOut）";
        } else {
            verdict = "本机还没被 AE 派过单（pushPattern 一次没成功）⇒ 这台的卡点不在产物返还，"
                    + "看 RECIPE claim 的 reason 与 STOCKWHO 的 own=";
        }
        ShanhaiStellarLog.info("TICKER pos={} buffer={} pushed={} refunded={} pushedTotal={} busyTicks={} ⇒ {}",
                this.shanhaiPos(), Integer.valueOf(n), Long.valueOf(pushed),
                Long.valueOf(this.shanhaiTickerRefundDrains), Long.valueOf(this.shanhaiTickerPushOkTotal),
                Long.valueOf(this.shanhaiTickerBusyTicks), verdict);
    }

    /**
     * <b>RECIPE claim</b>：这单<b>有没有被本机认领</b>（在 `pushPattern` 的真实返回值处打）。
     *
     * <h2>为什么要打它（上一轮的取证缺的就是这一条）</h2>
     * 「AE 把 46 件派出去了」是<b>由 `addMaxItems` 反推</b>出来的（它只在 `pushPattern` 成功时调）——
     * 那是<b>结论</b>，不是<b>证据</b>。而实机里有 <b>8 台</b>星律，谁认领的、认领了几件，
     * 日志里<b>一个字都没有</b> ⇒ 只能靠推断。这一行把它变成读数。
     *
     * <h2>reason 的四种取值（对应父类 pushPattern 的 4 步字节码，偏移 0–57）</h2>
     * <pre>
     *   accepted              成功（0: grid active / 16: 槽号查到 / 47: InternalSlot.pushPattern 已执行）
     *   gridInactive          getMainNode() 为 null 或 !isActive()（本机没连上网）
     *   patternNotOurs         getSlotIndexForPattern 返回 null（AE 拿回来的实例不是本机给的）
     *   slotOutOfRange:&lt;n&gt;    槽号越界
     *   internalSlotReject:&lt;n&gt; 槽号合法但推料仍被拒
     * </pre>
     * <p>节流：前 5 次 + 成败翻转 + 之后每 200 次（46 件的单会连打 46 次，不能全打）。
     */
    private void shanhaiLogClaim(@Nullable appeng.api.crafting.IPatternDetails pattern, boolean ok,
                                 @NotNull String reason) {
        if (this.isRemote()) {
            return;
        }
        final int okFlag = ok ? 1 : 0;
        final boolean changed = this.shanhaiClaimLastOk != okFlag;
        this.shanhaiClaimLastOk = okFlag;
        if (!changed && this.shanhaiClaimLogged >= 5L && this.shanhaiClaimCalls % 200L != 0L) {
            return;
        }
        this.shanhaiClaimLogged++;
        // 字段名按任务书原文：`RECIPE claim machine=<pos> pattern=<id> ok=<bool> reason=<…>`
        // （machine= 与其它行的 pos= 是同一个字符串，见 {@link #shanhaiPos()}）
        ShanhaiStellarLog.info("RECIPE claim machine={} pattern={} ok={} reason={} calls={} okTotal={}",
                this.shanhaiPos(), shanhaiPatternDigest(pattern), Boolean.valueOf(ok), reason,
                Long.valueOf(this.shanhaiClaimCalls), Long.valueOf(this.shanhaiClaimOk));
    }

    /** 「本机为什么拒了这单」——逐条对应父类 `pushPattern` 的 4 步，**不猜**。 */
    private @NotNull String shanhaiClaimRejectReason(@Nullable appeng.api.crafting.IPatternDetails pattern) {
        try {
            final appeng.api.networking.IManagedGridNode node = this.getMainNode();
            if (node == null || !node.isActive()) {
                return "gridInactive";
            }
            final Integer slot = this.getSlotIndexForPattern(pattern);
            if (slot == null) {
                return "patternNotOurs";
            }
            if (slot < 0 || slot >= this.getInternalSlotCount()) {
                return "slotOutOfRange:" + slot;
            }
            return "internalSlotReject:" + slot;
        } catch (Throwable t) {
            return "unknown:" + t.getClass().getSimpleName();
        }
    }

    /** 样板一眼可读的 id：取第一条产出的 `id x count`；拿不到就退化成类名（绝不编）。 */
    private static @NotNull String shanhaiPatternDigest(@Nullable appeng.api.crafting.IPatternDetails pattern) {
        if (pattern == null) {
            return "null";
        }
        try {
            final appeng.api.stacks.GenericStack[] outs = pattern.getOutputs();
            if (outs != null && outs.length > 0 && outs[0] != null && outs[0].what() != null) {
                return outs[0].what().getId() + "x" + outs[0].amount();
            }
        } catch (Throwable ignored) {
            // 落到下面的类名
        }
        return pattern.getClass().getSimpleName();
    }

    /**
     * <b>HANDLERS</b>：只在「结构成型后」打一次 —— 本机在 capability 链里<b>排第几</b>、
     * 以及<b>第一个把料吃干（返回 null = 短路）的 handler 是不是我们自己的</b>。
     *
     * <h2>为什么顺序重要</h2>
     * gtlcore 的 `RecipeHandlePart#handleRecipe` 按 `getParts()` 的顺序遍历仓室、
     * 每个仓室再按 trait 顺序遍历 `IRecipeHandler`；<b>谁先返回 null，后面的这一轮就不再被问</b>。
     * 上一轮的实机日志里同一个结构既有本机、又有 `MESuperPatternBufferPartMachine`
     * ⇒ 「本机的 handler 到底有没有机会被问」这件事，只能用顺序回答。
     *
     * <h2>字段</h2>
     * <ul>
     *   <li>{@code order=[下标:类名, …]} = 本机 {@code getTraits()} 里**所有实现了 IRecipeHandler 的 trait**，
     *       按 trait 列表下标升序 —— 这就是主机遍历本机时看到的顺序（不是猜的，是列表现读）；</li>
     *   <li>{@code short=[ShanhaiStockItemList calls=h nulls=n, …]} = 本机这两个 handler 被调次数与
     *       「返回 null 把料吃干」的次数；</li>
     *   <li>{@code firstShort=<类名|none(ours)>} = 按上面的顺序，第一个 nulls&gt;0 的那一个。
     *       <b>本机之外的仓室看不到 ⇒ 不会瞎写</b>（拿不到就写 {@code none(ours)} 并在判读里说明）。</li>
     * </ul>
     */
    private void shanhaiLogHandlersOnce(@NotNull String where) {
        if (this.isRemote() || this.getLevel() == null || this.shanhaiHandlersLogged) {
            return;
        }
        this.shanhaiHandlersLogged = true;
        final StringBuilder order = new StringBuilder();
        String firstShort = null;
        try {
            final java.util.List<com.gregtechceu.gtceu.api.machine.trait.MachineTrait> traits = this.getTraits();
            if (traits != null) {
                for (int i = 0; i < traits.size(); i++) {
                    final com.gregtechceu.gtceu.api.machine.trait.MachineTrait trait = traits.get(i);
                    if (!(trait instanceof com.gregtechceu.gtceu.api.capability.recipe.IRecipeHandler<?>)) {
                        continue;
                    }
                    final String name = trait.getClass().getSimpleName();
                    if (order.length() > 0) {
                        order.append(", ");
                    }
                    order.append(i).append(':').append(name);
                    if (firstShort == null && this.shanhaiNullReturnsOf(name) > 0L) {
                        firstShort = name;
                    }
                }
            }
        } catch (Throwable t) {
            order.append("?").append(t.getClass().getSimpleName());
        }
        final String shortText = "ShanhaiStockItemList calls=" + this.shanhaiSupplyItemHandleCalls
                + " nulls=" + this.shanhaiStockItemNullReturns
                + ", ShanhaiStockFluidList calls=" + this.shanhaiSupplyFluidHandleCalls
                + " nulls=" + this.shanhaiStockFluidNullReturns;
        final long oursCalls = this.shanhaiSupplyItemHandleCalls + this.shanhaiSupplyFluidHandleCalls;
        final String verdict;
        if (firstShort != null) {
            verdict = "本机的 " + firstShort + " 有一轮把料吃干了（返回 null = 短路）⇒ 那一轮它后面的仓室没被问"
                    + "（证明本机至少有一轮是「供料方」，不是旁观者）";
        } else if (oursCalls == 0L) {
            verdict = "本机这两个库存 handler 一次都没被调过 ⇒ 主机没把本机算进「能处理这份配方」的仓室名单"
                    + "（看 STOCKWHO 的 own= 与配方类型，不是产物返还的问题）";
        } else {
            verdict = "本机 handler 被调过 " + oursCalls + " 次却一次都没把料吃干 ⇒ 短路发生在**别的仓室**"
                    + "（不在本机，本机看不到它的名字 —— 不编）；那次派单的料不是本机供的";
        }
        ShanhaiStellarLog.info("HANDLERS pos={} where={} order=[{}] short=[{}] firstShort={} ⇒ {}",
                this.shanhaiPos(), where, order.length() == 0 ? "none" : order.toString(),
                shortText, firstShort == null ? "none(ours)" : firstShort, verdict);
        // 一次性把「产物该由谁写进 buffer」的静态事实也打出来（下一轮实机可以直接照这条核对）
        ShanhaiStellarLog.info("BUFFERPATH pos={} chain=配方真跑完成 → 主机把输出推给本机 ME handler"
                        + " → meHandleRecipeOutputInner 写 buffer → Ticker(构造时 io==IO.BOTH 才注册)"
                        + " → AEUtils.reFunds; gate=[simulate=true 时直接 return List.of()（偏移 0–7）⇒ 只有真跑那遍会写；"
                        + "io 必须含 OUT；buffer 是内存 map、唯一写入点就是它]",
                this.shanhaiPos());
    }

    /** 按类名取「返回 null（把料吃干）」的次数 —— 只认本机那两个我们自己写的 handler。 */
    private long shanhaiNullReturnsOf(@NotNull String simpleName) {
        if ("ShanhaiStockItemList".equals(simpleName)) {
            return this.shanhaiStockItemNullReturns;
        }
        if ("ShanhaiStockFluidList".equals(simpleName)) {
            return this.shanhaiStockFluidNullReturns;
        }
        return 0L;
    }

    /**
     * <b>HANDLEPOOL 的挂点</b>：结构成型后打<b>一次</b>，回答「我们那两份 handler 到底在哪一份
     * {@code RecipeHandlePart} 里」——这是「{@code handleRecipeInner} calls=0」这件事的<b>直接</b>读数。
     *
     * <h2>一、为什么必须直接读池子（而不是再猜一次）</h2>
     * {@code IRecipeHandler#handleRecipe} 的默认实现（{@code javap -p -c} 偏移 0–75）是<b>纯透传</b>：
     * {@code copyContent} 之后直接调 {@code handleRecipeInner} 并返回，<b>不看</b>
     * {@code getHandlerIO()}/{@code getCapability()}/{@code isProxy()}/{@code isDistinct()}。
     * ⇒ 只要有一份含我们 handler 的 part 被「问」，计数就必然 &gt; 0
     * ⇒ <b>{@code calls=0} 只能意味着「没有那份 part」，或者那份 part 从未被匹配流程问到</b>。
     * 这两种情形在本行里的长相完全不同（{@code oursInNormal} / {@code shared} / ★ 的位置）。
     *
     * <h2>二、为什么在 {@code update()} 里打、还要先等 40 tick</h2>
     * 池子是在 {@code upDate()} 里 rebuild 的，而 {@code upDate()} 的触发点是「成型」与「ME 缓冲变更」。
     * 成型那一瞬间我们很可能正好排在 rebuild <b>之前</b> ⇒ 立刻读会读到<b>上一轮</b>（甚至是空表）的状态，
     * 那会把「还没轮到」误判成「池子里没有」。等 40 tick（2 秒）能让至少一轮 rebuild 跑完；
     * 而每台机只打一次 ⇒ 不会刷屏（这一行的代价是几份 getter，没有遍历机器的开销）。
     *
     * <p>主机取不到（{@code getControllers()} 还没填）时<b>不消耗等待计数、也不打</b>，
     * 下一 tick 再看 —— 绝不把「还没接上主机」写成「池子是空的」。
     * 全部读数与判读在 {@link ShanhaiHandlePoolProbe#log} 里（那个类只读、永不抛）。
     */
    private void shanhaiLogHandlePoolOnce() {
        if (this.isRemote() || this.getLevel() == null || this.shanhaiHandlePoolLogged) {
            return;
        }
        final Object host = this.shanhaiHostRecipeCapabilityMachine();
        if (host == null) {
            // 主机还没接上 ⇒ 这一刻读数没有意义（别把"没接上"写成"池子空"）。
            return;
        }
        if (++this.shanhaiHandlePoolWait < 40) {
            return;
        }
        this.shanhaiHandlePoolLogged = true;
        try {
            ShanhaiHandlePoolProbe.log(this.shanhaiPos(),
                    (org.gtlcore.gtlcore.api.machine.trait.IRecipeCapabilityMachine) host,
                    this.shanhaiStockItemHandler, this.shanhaiStockFluidHandler,
                    this.shanhaiSupplyItemHandleCalls, this.shanhaiSupplyFluidHandleCalls);
        } catch (Throwable ignored) {
            // 探针永远不许把 tick 带崩（那一行的全部价值就是"别改行为"）。
        }
    }

    /**
     * 取「本机所在多方块的主机」，并且它必须已经实现
     * {@code IRecipeCapabilityMachine}（gtlcore 的 mixin 把它加到 {@code WorkableMultiblockMachine} 上）。
     *
     * <p>{@code IMultiPart#getControllers()} 是 <b>public abstract</b>（{@code javap} 实证，本类多处已在用），
     * 所以不需要新增任何访问器；返回 {@code null} 表示「还没接上主机」，调用方必须当成"没有读数"。
     */
    private @Nullable Object shanhaiHostRecipeCapabilityMachine() {
        try {
            final List<IMultiController> controllers = this.getControllers();
            if (controllers != null) {
                for (int i = 0; i < controllers.size(); i++) {
                    final IMultiController controller = controllers.get(i);
                    if (controller instanceof org.gtlcore.gtlcore.api.machine.trait.IRecipeCapabilityMachine) {
                        return controller;
                    }
                }
            }
        } catch (Throwable ignored) {
            // 同上：探针不许抛
        }
        return null;
    }

    /**
     * <b>BUFFERGATE</b>：`buffer` 为空时，把「谁该往里写 / 就绪闸卡在哪一条」摆出来。
     *
     * <h2>判读（三种成因，一次分清）</h2>
     * <pre>
     *   buffer>0                            ⇒ 产物进来了，没退（看 TICKER busyTicks）
     *   buffer=0 且 slotsWithItems/Fluids>0 ⇒ 料已推进本机槽里、却零产物
     *                                       ⇒ 配方没跑到「完成」那一步（不是产物返还的问题）
     *   buffer=0 且 两者都 0                ⇒ 这一台根本没在跑配方
     *                                       ⇒ 派单落在别的机器上（对照 RECIPE claim 的 pos）
     * </pre>
     * <p>节流 300 tick（5 秒），且只在 {@code buffer} 为空或第一次时打 —— 就是为"卡住"这个场景服务的。
     */
    private void shanhaiLogBufferGate(@NotNull String where) {
        if (this.isRemote() || this.getLevel() == null) {
            return;
        }
        final int bufferSize = this.buffer == null ? -1 : this.buffer.size();
        final long now = this.getLevel().getGameTime();
        final boolean first = this.shanhaiBufferGateTick == Long.MIN_VALUE;
        if (!first && bufferSize <= 0 && this.shanhaiBufferGateTick != Long.MIN_VALUE
                && now - this.shanhaiBufferGateTick < 300L) {
            return;
        }
        if (!first && bufferSize > 0) {
            return;   // 有产物时这一行不重要（TICKER 已经在说）
        }
        this.shanhaiBufferGateTick = now;
        int slotsWithItems = 0;
        int slotsWithFluids = 0;
        try {
            for (int slot : this.shanhaiCandidateSlots()) {
                final MEPatternBufferPartMachineBase.InternalSlot internal = this.getInternalSlot(slot);
                if (!internal.getItemInventory().isEmpty()) {
                    slotsWithItems++;
                }
                if (!internal.getFluidInventory().isEmpty()) {
                    slotsWithFluids++;
                }
            }
        } catch (Throwable t) {
            slotsWithItems = -1;
            slotsWithFluids = -1;
        }
        String meItem = "?";
        String meFluid = "?";
        String recipeHandler = "?";
        try {
            final Object pair = this.getMERecipeHandlerTraits();
            if (pair instanceof it.unimi.dsi.fastutil.Pair<?, ?> p) {
                meItem = p.left() == null ? "null" : p.left().getClass().getSimpleName();
                meFluid = p.right() == null ? "null" : p.right().getClass().getSimpleName();
            }
        } catch (Throwable t) {
            meItem = "?" + t.getClass().getSimpleName();
        }
        try {
            recipeHandler = this.recipeHandler == null ? "null" : this.recipeHandler.getClass().getSimpleName();
        } catch (Throwable ignored) {
            recipeHandler = "?";
        }
        final String verdict;
        if (bufferSize > 0) {
            verdict = "产物在 buffer 里等着退（下一秒应看到 TICKER 的 refunded 增加）";
        } else if (slotsWithItems > 0 || slotsWithFluids > 0) {
            verdict = "本机槽里压着料（物品格 " + slotsWithItems + " / 流体格 " + slotsWithFluids
                    + "）却零产物 ⇒ 配方没跑到「完成」那一步，**不是**产物返还的问题";
        } else {
            verdict = "本机既没料也没产物 ⇒ 这一台根本没在跑配方（派单落在别的机器上，"
                    + "对照 RECIPE claim 的 pos= 与 STOCKWHO 的 own=）";
        }
        ShanhaiStellarLog.info("BUFFERGATE pos={} where={} buffer={} slotsWithItems={} slotsWithFluids={} "
                        + "recipeHandler={} meOut=[item={}, fluid={}] ⇒ {}",
                this.shanhaiPos(), where, Integer.valueOf(bufferSize),
                Integer.valueOf(slotsWithItems), Integer.valueOf(slotsWithFluids),
                recipeHandler, meItem, meFluid, verdict);
    }

    /**
     * <b>②-3 探针的挂点</b>：在 {@code pushPattern} 的<b>真实返回值</b>处打 RECIPE claim（行为一字不改）。
     *
     * <p>为什么必须覆写这一跳、而不在别处推断：AE 侧「派出去了几件」唯一可信的来源就是这个方法的返回值
     * （{@code CraftingCpuLogic#executeCrafting} 只在它返回 true 时 {@code addMaxItems}）。
     * 父类实现只有 4 步（{@code javap -c} 偏移 0–57），所以 ok=false 的原因也只可能是那 4 步之一
     * ⇒ {@link #shanhaiClaimRejectReason} 逐条对应，不猜。
     */
    @Override
    public boolean pushPattern(appeng.api.crafting.IPatternDetails pattern, appeng.api.stacks.KeyCounter[] stacks) {
        final boolean ok = super.pushPattern(pattern, stacks);
        // XRECIPE 判据（认领产物 / 该槽手填类型 / 该槽绑定配方的类型 / 宿主形态 / 判定）
        try {
            this.shanhaiXrecipeProbe(pattern, ok);
        } catch (Throwable ignored) {
            // 探针永远不许把派单带崩
        }
        // 🔴 2026-10-27 PUSHSLOT：**推料落点**判据（40 tick 节流，带 pos）——
        //    「铜到底被塞进了哪个槽」是"配方枚举看不到铜"的第一分叉点：
        //    槽号 <1080 ⇒ 进的是普通样板槽；≥1080 ⇒ 进的是展开段（母槽 1080..1129 / 展开 1130..）。
        try {
            this.shanhaiPushSlotProbe(pattern, stacks);
        } catch (Throwable ignored) {
            // 探针永远不许把派单带崩
        }
        this.shanhaiClaimCalls++;
        if (ok) {
            this.shanhaiClaimOk++;
            this.shanhaiTickerPushOkSinceLog++;
            this.shanhaiTickerPushOkTotal++;
        }
        try {
            this.shanhaiLogClaim(pattern, ok, ok ? "accepted" : this.shanhaiClaimRejectReason(pattern));
        } catch (Throwable ignored) {
            // 探针永远不许把派单带崩（它的全部价值就是"别改行为"）
        }
        return ok;
    }

    /**
     * 让<b>两份</b> 160 格各做一次 {@code syncStock}（填每格的 stock，界面显示用）+ 重建键缓存 + 取库存快照。
     *
     * <p>物品侧与流体侧<b>同一个节拍、同一份 MEStorage</b>；哪一份漏了，面板上那一半的格子就会永远显示 0
     * （流体的 {@code syncStock} 自带，{@code javap -p} 实证：{@code public void syncStock(MEStorage)}）。
     */
    private void shanhaiSyncStock(@NotNull String where) {
        if (this.shanhaiStockItemHandler == null) {
            return;
        }
        // 库存放行快照跟着同一个节拍重算一次：面板上改了配置、槽里推了料、样板换了，
        // 最迟一个同步周期内就会反映到 getActiveSlots()（重建是 1080 次数组读，不贵）。
        this.shanhaiMarkStockSupplyDirty();
        final net.minecraft.world.level.Level level = this.getLevel();
        final appeng.api.networking.IManagedGridNode node = this.getMainNode();
        final appeng.api.networking.IGrid grid = node == null ? null : node.getGrid();
        // 🔴 2026-10-25 **口径改写**（用户原话：「标签拉取把库存的位置填了，但是实际上它们应该分开各不干扰」）：
        //    标签这一路现在**不写**任何 config ⇒ 它与紧接着的两处 `syncStock` **完全无耦合**，
        //    放在这里只是因为「它就是跟着库存同步周期跑」这件事没变（同一节拍，不另开计时器）。
        //    上一轮那道 `shanhaiTagPullBusy` 防重入闸**已删除**：它存在的唯一理由是
        //    「写 config ⇒ onStockInputConfigChanged ⇒ syncStockInput ⇒ 回到本方法」，
        //    而现在这个方法里一次 config 写入都没有 ⇒ 不存在那条递归路径。
        this.shanhaiTagPull(grid);
        if (grid == null) {
            // 掉线：清空库存显示（与父类 performStockSync 对 grid==null 的处置同义）
            this.shanhaiStockItemHandler.clearStocks();
            // 流体侧同一处置（javap：ExportOnlyAEStockingFluidList 自带 public clearStocks()）
            this.shanhaiStockFluidHandler.clearStocks();
            this.shanhaiStockSnapshot = null;
            this.shanhaiStockSnapshotTick = Long.MIN_VALUE;
            // 🔴 2026-10-25：**这一支也必须留读数** —— 「stock 恒 0」最常见的一种成因就是
            //    `getMainNode().getGrid() == null`（机器没连上网/节点没上线），此时 syncStock
            //    一次都没跑，下游的 SUPPLYCHAIN 只会看到 stock=0，看不出是"没连网"。
            this.shanhaiLogSyncStock(where + "/noGrid", node, null);
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
        // 🔴 2026-10-25：「stock 恒 0」的取证行（判读表见方法 javadoc）。
        //    位置放在两处 syncStock 之后 ⇒ 打出来的就是**这一次真实写进去**的结果。
        this.shanhaiLogSyncStock(where, node, storage);
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

    /**
     * <b>SYNCSTOCK</b>：把「库存同步链」一次跑完的<b>实际读数</b>打出来（前 3 次 + 之后每 200 tick）。
     *
     * <h2>为什么必须有这一行</h2>
     * 实机 12:33 那三条 {@code SUPPLYCHAIN} 只给了<b>结果</b>（{@code cfg=1 stock=0} ⇒ 配了却没库存），
     * 而库存是 {@code syncStock(MEStorage)} 写进去的。同一个 {@code stock=0} 至少有四种成因，
     * 这条日志把它们<b>分开</b>：
     * <pre>
     *   ① syncStock 没被调（掉线 / 节拍没到）      → where=…/noGrid 或 node=0
     *   ② 被调了，但 ME 侧 extract(SIMULATE) 返回 0 → net&gt;0 且 me=0
     *   ③ 写进了另一份 handler（父类 32 格）        → p32/f32&gt;0 而 cfg=0
     *   ④ 配置槽里的键与 ME 里的键不是同一个        → net=0
     * </pre>
     *
     * <h2>字段判读</h2>
     * <ul>
     *   <li>{@code node}/{@code active}/{@code storage}：主节点在不在网上、{@code getGrid()} 拿到没有、库存对象是谁；</li>
     *   <li>{@code item[cfg/stock]}、{@code fluid[cfg/stock]}：<b>我们那两份 160 格</b>里
     *       「有配置的格数 / 有库存的格数（= {@code IConfigurableSlot#getStock() != null}）」；</li>
     *   <li>{@code p32}/{@code f32}：<b>父类那 32+32 格</b>的配置格数 —— 「配进了另一份 handler」的唯一判据；</li>
     *   <li>{@code probes=[key:net/me ...]}：对每个配置键同时报两个数 ——
     *       {@code net} = {@code storage.getAvailableStacks().get(key)}（ME 里有没有这东西），
     *       {@code me} = {@code storage.extract(key, MAX, SIMULATE, 本机 actionSource)}（= {@code syncStock} 内部那一句）。
     *       <b>{@code net>0 但 me=0} ⇒ 抽取被拒（安全终端 / actionSource），库存永远填不进去</b>。</li>
     * </ul>
     * 采样上限：物品 3 个键、流体 2 个键（一次 {@code extract} 对全网来说不便宜，别刷）。
     */
    private void shanhaiLogSyncStock(@NotNull String where,
                                     @Nullable appeng.api.networking.IManagedGridNode node,
                                     @Nullable MEStorage storage) {
        if (this.isRemote() || this.getLevel() == null) {
            return;
        }
        final long now = this.getLevel().getGameTime();
        final long calls = ++this.shanhaiSyncStockCalls;
        if (calls > 3L && now - this.shanhaiSyncStockLogTick < 200L) {
            return;
        }
        this.shanhaiSyncStockLogTick = now;
        final appeng.api.networking.IGrid grid = node == null ? null : node.getGrid();
        final int[] item = this.shanhaiCfgAndStock(this.shanhaiStockItemHandler);
        final int[] fluid = this.shanhaiCfgAndStock(this.shanhaiStockFluidHandler);
        final int p32 = this.shanhaiCountConfigured(this.stockItemHandler);
        final int f32 = this.shanhaiCountConfiguredFluid(this.stockFluidHandler);
        // ---- 逐键探针：net（ME 有没有）vs me（extract SIMULATE 给不给）
        final StringBuilder probes = new StringBuilder();
        int sampled = 0;
        for (AEItemKey key : this.shanhaiConfiguredItemKeys()) {
            if (sampled >= 3) {
                break;
            }
            this.shanhaiAppendStockProbe(probes, key, storage);
            sampled++;
        }
        int sampledFluid = 0;
        for (AEFluidKey key : this.shanhaiConfiguredFluidKeys()) {
            if (sampledFluid >= 2) {
                break;
            }
            this.shanhaiAppendStockProbe(probes, key, storage);
            sampledFluid++;
        }
        ShanhaiStellarLog.info("SYNCSTOCK where={} calls={} node={} active={} grid={} storage={} tag={} "
                        + "item[cfg={} stock={}] fluid[cfg={} stock={}] p32={} f32={} probes=[{}] ⇒ {}",
                where, Long.valueOf(calls),
                Integer.valueOf(node == null ? 0 : 1),
                Integer.valueOf(node != null && node.isActive() ? 1 : 0),
                Integer.valueOf(grid == null ? 0 : 1),
                storage == null ? "null" : storage.getClass().getSimpleName(),
                this.shanhaiTagPullTagFlag(),
                Integer.valueOf(item[0]), Integer.valueOf(item[1]),
                Integer.valueOf(fluid[0]), Integer.valueOf(fluid[1]),
                Integer.valueOf(p32), Integer.valueOf(f32),
                probes.length() == 0 ? "none" : probes.toString(),
                this.shanhaiSyncStockVerdict(item, fluid, p32, f32, storage, grid));
    }

    /**
     * SYNCSTOCK 行里那个 {@code tag=} 读数。
     *
     * <p>🔴 <b>2026-10-25 改语义</b>：它<b>不再</b>表示「这 160 格现在归谁」
     * （标签那一刀已经改成<b>独立供料源</b>，一次都不碰那 320 格的 config），
     * 而是「标签那一路<b>独立供料源</b>现在什么状态」：
     * <ul>
     *   <li>{@code off} = 标签关（这一路不供料）；</li>
     *   <li>{@code on:N} = 命中 N 种（物品+流体），这一路<b>正在</b>独立供料；</li>
     *   <li>{@code on:wait} = 开着但还没跑过一轮；</li>
     *   <li>{@code on:<原因>} = 开着但这一路暂停（{@code emptyExpression} / {@code badExpression} /
     *       {@code noGrid} / {@code noInventory} / {@code pending}）。</li>
     * </ul>
     * <p>⚠️ <b>判据提醒</b>：{@code tag=on:N} 与 {@code item[cfg=…]} <b>互不影响</b> ——
     * 后者只数<b>玩家手配</b>的那 160 格。看到 {@code tag=on:238 item[cfg=0]} 是<b>正确</b>的
     * （标签在供料、库存格没配）——上一轮那种「tag=on 就必须 cfg=160」的判读已经作废。
     */
    private @NotNull String shanhaiTagPullTagFlag() {
        if (!this.shanhaiTagFilterEnabled) {
            return "off";
        }
        if (ShanhaiTagPullPlanner.Gate.OK.code().equals(this.shanhaiTagPullSkip)) {
            return "on:" + (this.shanhaiTagSupplyItemMatched + this.shanhaiTagSupplyFluidMatched);
        }
        if (this.shanhaiTagPullSkip.isEmpty()) {
            return "on:wait";
        }
        final int colon = this.shanhaiTagPullSkip.indexOf(':');
        return "on:" + (colon < 0 ? this.shanhaiTagPullSkip : this.shanhaiTagPullSkip.substring(0, colon));
    }

    /** SYNCSTOCK 的一句话判读（把「四种成因」直接写成中文，省得每轮再推一遍）。 */
    private @NotNull String shanhaiSyncStockVerdict(int[] item, int[] fluid, int p32, int f32,
                                                    @Nullable MEStorage storage,
                                                    @Nullable appeng.api.networking.IGrid grid) {
        if (grid == null) {
            return "主节点不在网上（getGrid()==null）⇒ syncStock 一次都没跑，先接电/接线";
        }
        if (storage == null) {
            return "库存对象拿不到（getStorageService().getInventory()==null）";
        }
        if (item[0] == 0 && fluid[0] == 0) {
            if (p32 + f32 > 0) {
                return "我们那 320 格一条配置都没有，但父类 32+32 有 " + (p32 + f32)
                        + " 条 ⇒ 配到了另一份 handler（③），供料链读的不是它";
            }
            if (this.shanhaiTagFilterEnabled && ShanhaiTagPullPlanner.Gate.OK.code().equals(this.shanhaiTagPullSkip)) {
                return "手配的 160 格是空的（cfg=0）—— 这是正常的：标签那一路是**独立供料源**，"
                        + "不经库存格（看 SYNCSTOCK 的 tag=on:N 与 SUPPLYCHAIN 里 kind=item-tag 的行）";
            }
            return "两份 160 格都没配置（cfg=0）⇒ 不是同步坏了，是压根没配（库存页没写进来）";
        }
        if (item[1] + fluid[1] > 0) {
            return "库存已填上（stock>0）⇒ 同步链是通的，往下看 SUPPLYCHAIN/STOCKSUPPLY";
        }
        return "配了但 stock 全 0 ⇒ 看 probes[]：net=0 是键不对（④），net>0/me=0 是抽取被拒（②）";
    }

    /**
     * 一个键的 {@code net/me} 双读数（{@link #shanhaiLogSyncStock} 用）。
     *
     * <p>{@code net} 走库存<b>清单快照</b>（不需要 actionSource），{@code me} 走<b>真 SIMULATE 抽取</b>
     * （用本机 {@code actionSource}，与 {@code syncStock} 内部那一句逐字同参）。
     * 两者分开就能分辨「ME 里没有」与「ME 有但不给抽」。
     */
    private void shanhaiAppendStockProbe(@NotNull StringBuilder out, @Nullable appeng.api.stacks.AEKey key,
                                         @Nullable MEStorage storage) {
        if (key == null) {
            return;
        }
        if (out.length() > 0) {
            out.append(' ');
        }
        out.append(key.getId()).append(':');
        long net = 0L;
        try {
            net = this.shanhaiStockSnapshot == null ? 0L : this.shanhaiStockSnapshot.get(key);
        } catch (Throwable ignored) {
        }
        out.append(net).append('/');
        long me = -1L;
        if (storage != null) {
            try {
                me = storage.extract(key, Long.MAX_VALUE, appeng.api.config.Actionable.SIMULATE,
                        this.actionSource);
            } catch (Throwable t) {
                me = -2L;   // -1 = 没测（storage 为 null）；-2 = 抽取抛异常（安全终端/节点异常）
            }
        }
        out.append(me);
    }

    /** 一份 160 格 handler 的「有配置格数 / 有库存格数」（只用公开 API，不碰 protected 字段）。 */
    private int[] shanhaiCfgAndStock(@Nullable com.gregtechceu.gtceu.integration.ae2.slot.IConfigurableSlotList handler) {
        if (handler == null) {
            return new int[]{-1, -1};
        }
        int cfg = 0;
        int stock = 0;
        final int slots = handler.getConfigurableSlots();
        for (int i = 0; i < slots; i++) {
            final IConfigurableSlot slot = handler.getConfigurableSlot(i);
            if (slot == null) {
                continue;
            }
            if (slot.getConfig() != null) {
                cfg++;
            }
            if (slot.getStock() != null) {
                stock++;
            }
        }
        return new int[]{cfg, stock};
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

    // ---------------------------------------------------------------- 万能仓室：普通供料名单（2026-10-24）

    /**
     * 「<b>万能仓室</b>」的对外声明 —— <b>星律这台仓室本身</b>就是万能仓室，
     * 本次注入让它<b>自己</b>那几份 handler 进入「普通供料名单」：
     * <b>不需要样板</b>（不走样板认领那条路）、也不需要玩家再额外摆一台库存输入总线/仓。
     *
     * <h2>一、为什么需要这个方法（问题原文）</h2>
     * 星律的宿主机是 {@code IMEFilterIOPartMachine}（父类 {@code MEPatternBufferPartMachineBase}
     * implements {@code IMEPatternPartMachine}）。gtlcore 的
     * {@code WorkableMultiblockMachineMixin#rebuildRecipeHandleParts} 在<b>偏移 45</b> 用
     * {@code instanceof IMEFilterIOPartMachine} 把<b>所有 ME 仓室</b>整体分给 ME 分支
     * （偏移 63 {@code handleMETraitPart}），而<b>偏移 92 的 {@code handleNormalPart}
     * （那里才 {@code part.getRecipeHandlers()}）根本不会为它执行</b>
     * ⇒ 本机身上这两份「本来就是普通 {@code IRecipeHandlerTrait}」的库存 list
     * <b>永远进不了 {@code normalCapabilities}</b>，只能被样板槽的三道闸
     * （{@code hasPatternInSlot → isItemActive → hasConfig()}）间接问到。
     *
     * <h2>二、本方法交出去的<b>五份</b>（库存 2 + 只匹配不抽取 3）</h2>
     * <table border="1">
     *   <tr><th>字段</th><th>格数</th><th>handlerIO</th><th>交不交</th></tr>
     *   <tr><td>{@link #shanhaiStockItemHandler}</td><td>160（8×2×10）</td><td>{@code IO.IN}</td><td><b>交</b></td></tr>
     *   <tr><td>{@link #shanhaiStockFluidHandler}</td><td>160（8×2×10）</td><td>{@code IO.IN}</td><td><b>交</b></td></tr>
     *   <tr><td>父类 {@code stockItemHandler}/{@code stockFluidHandler}（32+32）</td><td>32+32</td><td>IN</td>
     *       <td>不交 —— 它们是「样板槽的补充通道」，语义不是整机共享池</td></tr>
     *   <tr><td>{@code patternInventory}（1080 格样板）</td><td>1080</td><td>—</td>
     *       <td>🔴 <b>永不交</b>：样板库存不是原料</td></tr>
     *   <tr><td>{@code catalystItems}/{@code catalystFluids}（每槽 3×3）</td><td>1080×9×2</td><td>—</td>
     *       <td>🔴 <b>永不交</b>：类型是 LDLib 的 {@code ItemStackTransfer[]}/{@code FluidTransferList[]}，
     *           <b>根本不是</b> {@code IRecipeHandlerTrait}；且按槽归属 = 「样板认领」语义</td></tr>
     *   <tr><td>🔶 通用催化剂（物品）：父类 {@code sharedCatalystInventory}</td><td>n</td><td>IN</td>
     *       <td><b>交</b>，但走<b>新类</b> {@link ShanhaiSupplyOnlyItemHandler}（不是上游那个 handler）：
     *           上游 {@code CatalystItemStackHandler#handleRecipeInner} 命中即 {@code return left}
     *           （<b>永不返回 null</b>），而 gtlcore {@code RecipeHandlePart#handleRecipe} 偏移 54–60
     *           <b>只认 null 为满足</b> ⇒ 直接用一点料都供不了。新类 = 够就 {@code null}、不够就原样退回、
     *           <b>一件都不抽</b>（探针 E1–E8）。详见报告 §2.6.2b</td></tr>
     *   <tr><td>🔶 通用催化剂（流体）：父类 {@code sharedCatalystTank}</td><td>n</td><td>IN</td>
     *       <td><b>交</b>，走新类 {@link ShanhaiSupplyOnlyFluidHandler}（上游流体版偏移 164 同样 {@code return left}）</td></tr>
     *   <tr><td>🔶 通用编程电路：父类 {@code sharedCircuitInventory}（实例是
     *       {@code NotifiableCircuitItemStackHandler}）</td><td>1</td><td>IN</td>
     *       <td><b>交</b>，也走 {@link ShanhaiSupplyOnlyItemHandler}：上游那个 handler 偏移 <b>0–6</b> 是
     *           {@code if (!simulate) return left;} ⇒ 真扣料阶段不认账，直接用会「匹配上却启动失败」。
     *           新类把「配置对不对」交给 {@code IntCircuitIngredient#test} 自己判，够就 {@code null} 且不扣</td></tr>
     *   <tr><td>{@code getMERecipeHandlerTraits()} 那一对 ME handler</td><td>—</td><td>—</td>
     *       <td>不交：它们不是 {@code IRecipeHandler}，本来就由 ME 分支受理</td></tr>
     * </table>
     *
     * <h2>三、这两份 handler 被交出去之后，配方怎么用它们（不用样板）</h2>
     * 接进 gtlcore 的是 {@code com.shanhai.mixin.ShanhaiUniversalSupplyMixin}（唯一一个注入点：
     * {@code upDate()} 里 {@code getParts()} 的返回值）。它给本机<b>额外</b>追加一个
     * 「只含本方法返回值」的视图，于是 gtlcore <b>自己那份</b> {@code handleNormalPart}
     * 会照常处理这个视图，落点与<b>普通输入仓</b>完全相同：
     * <ul>
     *   <li>主机 {@code isDistinct == false}（默认）⇒ 两份 handler 进
     *       {@code sharedInputRecipeHandlePart}（整机共享输入池）；
     *       流体由 {@code RecipeRunner#handleInputNotDistinct} 偏移 <b>307–358</b> 直接取共享池，
     *       物品由同一方法偏移 <b>539–610</b> 的<b>兜底</b>取共享池；</li>
     *   <li>主机 {@code isDistinct == true} ⇒ 进 {@code normalCapabilities[IN]}（本机自成一组）。</li>
     * </ul>
     * <p>⚠️ 与「样板认领」的根本差别：这条路上<b>没有</b> {@code hasPatternInSlot} 这道闸，
     * 也<b>没有</b>数量配置（配置格只决定「抽哪种」，抽多少由配方决定）。
     *
     * <h2>四、安全边界</h2>
     * <ul>
     *   <li>只有<b>带 {@link IShanhaiNormalSupplyPart} 标记的仓室</b>会被额外插入视图
     *       ⇒ 未标记的 ME 仓室（别的样板总成、别的 ME 总线）<b>行为逐字节不变</b>；</li>
     *   <li>视图的 {@code getRecipeHandlers()} <b>只返回本方法的值</b>，不回读
     *       {@code part.getRecipeHandlers()} ⇒ 样板槽/样板库存/催化剂<b>结构上进不来</b>；</li>
     *   <li>本机<b>原来的 ME 身份完全保留</b>：真实 part 仍走 {@code handleMETraitPart}
     *       （样板认领、缓冲变更监听、{@code mePatternRecipeHandleParts} 照旧）。</li>
     * </ul>
     *
     * <h2>五、验收判据（进游戏看日志，一行就能判）</h2>
     * <pre>
     *   [SHANHAI-UNIVERSAL] 注入生效：upDate#getParts 已被接管（…）
     *   [SHANHAI-UNIVERSAL] 普通供料注入：parts A → B        ← B = A + 1（就是本机这一台）
     * </pre>
     * <p>然后：往本机 160 格库存页配一种<b>没有样板</b>的配方原料，主机配方应能<b>直接匹配并扣料</b>
     * （日志/进度条会走；ME 网络里的数量会减少）。
     *
     * @return 只含「应该参与普通配方匹配」的两份库存 handler；构造器还没跑完时返回空列表
     */
    @Override
    public List<IRecipeHandlerTrait> shanhaiNormalSupplyHandlers() {
        final MEStockingPatternBufferPartMachine.ExportOnlyAEStockingItemList items = this.shanhaiStockItemHandler;
        final MEStockingPatternBufferPartMachine.ExportOnlyAEStockingFluidList fluids =
                this.shanhaiStockFluidHandler;
        if (items == null || fluids == null || this.shanhaiTagSupplySource == null) {
            // 父类构造器还在跑（两个字段是 final，赋值在构造器里）⇒ 这一刻等于「没标记」。
            // 供料源同理：它是普通字段（声明在构造器赋值之后），没初始化完就不交出标签那两路。
            //
            // 🔴 2026-10-26 五修：这一分支**必须有日志**。它返回空表 ⇒ mixin 的
            //    shanhai$normalSupplyView 返回 null ⇒ expandForNormalSupply 因
            //    「一个视图都没造出来」退回**同一个 List 实例**（ShanhaiNormalSupplyBridge:110-112）
            //    ⇒ 那一次 rebuild 的池子里**没有我们**，而 upDate() 之后可能很久不再触发
            //    ⇒ 表观与「材料不足」完全一样。取证报告 §3.2 把它列为「最值得先排除的时序分支」。
            // 节流：本实例第 1 次 + 之后每 500 次一行（一条就够判"踩没踩到"，又不会把日志刷爆）。
            if (this.shanhaiSupplyViewSkipCount++ % 500L == 0L) {
                ShanhaiStellarLog.warn("SUPPLYVIEW skipped=constructing pos={} times={} items={} fluids={} source={}",
                        this.shanhaiPos(), Long.valueOf(this.shanhaiSupplyViewSkipCount),
                        items == null ? "null" : "ok",
                        fluids == null ? "null" : "ok",
                        this.shanhaiTagSupplySource == null ? "null" : "ok");
            }
            return List.of();
        }
        // 🔴 放行清单 = 7 项（2026-10-24 父代理拍板的方案②；2026-10-25 追加标签两路）。
        //    顺序有讲究：**库存 160 格在前**（有料就先扣料，走正常消耗）、
        //    标签命中集合其次（**独立供料源**，只在库存那 160 格供不上时才接手，且真抽 ME）、
        //    「只匹配不抽取」的三份在最后（催化剂/电路本来就是"不该被消耗"的东西，兜底且一件不扣）。
        //    gtlcore 的 RecipeHandlePart 内部按 getCapability() 分组、组内按本 list 的顺序逐个试。
        return List.of(
                // ① ② 库存本体（真抽 ME 网络；**玩家手配的那 320 格**，标签任何操作都不改它）
                items,
                fluids,
                // ③ ④ 标签命中集合（**独立供料源**：不占库存格、不写任何 config、命中即真抽 ME）
                //     关掉标签 / 名单空 / 没连网 ⇒ 它们 getContents() 为空、handleRecipeInner 原样退回
                //     ⇒ 行为与没有这两项时逐字节相同（探针 F 组断言）。
                new ShanhaiTagSupplyHandlers.Item("tagSupplyItems", this.shanhaiTagSupplySource),
                new ShanhaiTagSupplyHandlers.Fluid("tagSupplyFluids", this.shanhaiTagSupplySource),
                // ⑤ 通用催化剂·物品（只匹配、绝不抽取）
                new ShanhaiSupplyOnlyItemHandler("sharedCatalystInventory",
                        () -> shanhaiReadItemContents(this.sharedCatalystInventory)),
                // ⑥ 通用催化剂·流体（同上）
                new ShanhaiSupplyOnlyFluidHandler("sharedCatalystTank",
                        () -> shanhaiReadFluidContents(this.sharedCatalystTank)),
                // ⑦ 通用编程电路（同上；"配置对不对"由 IntCircuitIngredient#test 自己判）
                new ShanhaiSupplyOnlyItemHandler("sharedCircuitInventory",
                        () -> shanhaiReadItemContents(this.sharedCircuitInventory)));
    }

    /**
     * 把「物品 handler 的当前内容」读成一份<b>只读快照</b>（给
     * {@link ShanhaiSupplyOnlyItemHandler} 用）。
     *
     * <p>🔴 只读：这里只有 {@code getSlots()/getStackInSlot(i)}，**没有**任何 {@code extractItem/setStackInSlot}。
     *
     * @param transfer 任意物品容器（{@code sharedCatalystInventory} 与 {@code sharedCircuitInventory}
     *                 都是 {@code NotifiableItemStackHandler}，都实现 {@code IItemTransfer}）
     * @return 非空栈的列表（引用来自容器，调用方只读）
     */
    private static List<ItemStack> shanhaiReadItemContents(IItemTransfer transfer) {
        if (transfer == null) {
            return List.of();
        }
        final int slots = transfer.getSlots();
        final List<ItemStack> out = new java.util.ArrayList<>(slots);
        for (int i = 0; i < slots; i++) {
            final ItemStack stack = transfer.getStackInSlot(i);
            if (stack != null && !stack.isEmpty()) {
                out.add(stack);
            }
        }
        return out;
    }

    /**
     * 把「流体 handler 的当前内容」读成一份<b>只读快照</b>（给
     * {@link ShanhaiSupplyOnlyFluidHandler} 用）。
     *
     * <p>🔴 只读：只有 {@code getTanks()/getFluidInTank(i)}，**没有** {@code drain/fill}。
     *
     * @param tank {@code sharedCatalystTank}（{@code CatalystFluidStackHandler extends NotifiableFluidTank}）
     * @return 非空流体的列表
     */
    private static List<com.lowdragmc.lowdraglib.side.fluid.FluidStack> shanhaiReadFluidContents(
            NotifiableFluidTank tank) {
        if (tank == null) {
            return List.of();
        }
        final int tanks = tank.getTanks();
        final List<com.lowdragmc.lowdraglib.side.fluid.FluidStack> out = new java.util.ArrayList<>(tanks);
        for (int i = 0; i < tanks; i++) {
            final com.lowdragmc.lowdraglib.side.fluid.FluidStack fluid = tank.getFluidInTank(i);
            if (fluid != null && fluid.getAmount() > 0L) {
                out.add(fluid);
            }
        }
        return out;
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
