package com.shanhai.machine.creation;

import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.gui.fancy.FancyMachineUIWidget;
import com.gregtechceu.gtceu.api.gui.fancy.IFancyUIProvider;
import com.gregtechceu.gtceu.api.gui.fancy.TabsWidget;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.TickableSubscription;
import com.gregtechceu.gtceu.config.ConfigHolder;
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.texture.ItemStackTexture;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.SlotWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.misc.ItemStackTransfer;
import com.lowdragmc.lowdraglib.side.item.IItemTransfer;
import com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;
import com.shanhai.common.machine.PrimordialOmegaEngineMachine;
import com.shanhai.common.recipe.PrimordialRecipeEffects;
import com.shanhai.machine.module.PrimordialModuleMachine;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.GridFlags;
import appeng.api.networking.GridHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.blockentity.crafting.IMolecularAssemblerSupportedPattern;
import appeng.core.definitions.AEItems;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

/**
 * 「原初造物编译矩阵」—— 第 30 台原初模块（{@code shanhai:primordial_creation_compilation_matrix}）。
 *
 * <h2>阶段口径（P1 = 骨架；P2 = 接单→出货；<b>P3 = 控制器自己挂 AE，端口方块已删除</b>）</h2>
 * P1 保证了"能被造出来 / 装进主机 / 成型 / 右键开界面 / 放样板 / AE 能看见它的样板"六件事；
 * P2 让 {@code pushPattern} 真的把活做完；<b>P3 用户拍板「改成控制器自己挂 AE」</b>：
 * <ul>
 *   <li><b>AE 侧的身份就在本类</b>：本类实现 {@link IInWorldGridNodeHost}，
 *       自己持有一个 {@link IManagedGridNode}，<b>AE 线缆直接贴控制器方块</b>；
 *       那个 {@code shanhai:creation_pattern_port} 端口仓室方块<b>已经删掉</b>（连同注册与 lang）。</li>
 *   <li>{@link ICraftingProvider} 挂成<b>这个节点的 service</b>，实现者 = 本类持有的
 *       {@link CreationCraftExecutor}（收单 / 份数 / 余量 / 10 tick / 产物回网整段从端口搬过去，
 *       行为与 P2 逐项相同）。</li>
 *   <li>本类负责「1000 格样板存储 + 界面 + 并行/倍率/等级的口径」并实现 {@link CreationPatternHost}
 *       供执行器读取；<b>算术</b>仍集中在 {@link CreationCraftMath}（纯算术，可离线自证）。</li>
 * </ul>
 * <p>🔴 AE 是怎么"看见"本类的（字节码实证，不是猜测）：{@code appeng.api.networking.GridHelper#getNodeHost}
 * 先看方块实体是不是 {@link IInWorldGridNodeHost}，不是就查 Forge 能力
 * {@code appeng.capabilities.Capabilities.IN_WORLD_GRID_NODE_HOST}；而 GTCEu 的
 * {@code MetaMachineBlockEntity#getCapability} 在 AE2 存在时**把该能力桥接到 MetaMachine 自己**身上
 * （{@code javap -c} 偏移 1136–1215：{@code if (machine instanceof IInWorldGridNodeHost) → 返回它}）。
 * ⇒ 本类只要实现那个接口，控制器方块就成为 AE 可见的节点宿主，<b>不需要任何额外注册</b>。
 *
 * <h2>🔴 不接电</h2>
 * 「本机不消耗 EU」是这台机器的本质（上游那台也是纯粹的时间/节流器：原料在端口侧由 AE 先付账，
 * 产物直接回网，机器中间不持有任何物品）。
 * P2 的 AE 路径（{@code pushPattern} → 10 tick → {@code MEStorage#insert}）<b>全程不查电、不扣电</b>：
 * 它压根不经过 GT 的任何配方/EU 检查（没有 {@code RecipeLogic}、没有 {@code EUt}、没有电能仓读写）。
 * <b>本类没有</b>去改 {@code ModuleVoltageGate} 或任何公共类。
 *
 * <h2>并行 / 时长（P1 立口径，P2 已接进执行链）</h2>
 * 用户 2026 拍板（逐字口径）：
 * <blockquote>
 * 并行 = k^物质模块等级，k = 8；等级 ≥ 15 ⇒ 用上游那种「无限」写法（传哨兵、一次性把账目搬空）；
 * 速度固定 10 tick；不需要样板总成；不接电。
 * </blockquote>
 * 口径写在 {@link #parallelForMatterLevel(int)}（门面）+ {@link CreationCraftMath}（算式本体）。
 * 消费者 = {@link CreationCraftExecutor}（每 10 tick 取一次 {@link #getCreationParallelCap()}，P3 起它在控制器手里）。本轮<b>不</b>覆盖
 * {@code getMaxParallel()}：那会改到「物质模块槽并行」那套已被 29 台模块验证的公共口径。
 *
 * <h2>产物倍率（<b>P10 起恒等于主机增产，没有开关</b>）</h2>
 * 用户拍板：「<b>干脆去掉开关，永远吃主机增产</b>」 ⇒ 倍率 =
 * {@code productionMultiplierOf(物质模块槽给的份数, host.getHostProductionBonusExtra())}
 * （本工程现成算式 {@link PrimordialRecipeEffects#productionMultiplierOf(int, int)}，照抄语义、不写死 {@code 1+等级}）。
 * 读取点 = {@link #creationProductionMultiplier()}；
 * 实际乘的地方 = 执行器出货那一步（{@link CreationCraftMath#outputsOf}），
 * 报给 AE 的地方 = 执行器解码样板时包的那一层（{@link CreationScaledPattern}）—— <b>两边同一个倍率</b>。
 * <p>🔴 <b>P10 删掉了那个开关字段</b>（此前的 {@code @Persisted @DescSynced boolean} 字段与它的读写、
 * 它的界面按钮）⇒ 老存档里那个 NBT 键变成<b>未知键</b>：LDLib 的 {@code PersistedParser} 只按当前类
 * 声明过的字段逐个取值，遇到不认识的键<b>直接忽略、不抛</b>（本工程先例：删字段后老档照常读；
 * 顶多那台机器回到本类字段的 Java 默认值）。
 *
 * <h2>倍率 = 主机增产（唯一用途：告诉 AE「一块样板实际出几个」）</h2>
 * 用户原话（逐字）：
 * <blockquote>「只吃主机增产，那个倍率是你下单是为了方便 ae 计算用的啊，不然它产出 17 个 ae 下单计算时
 * 还以为产出了 1 个，这样就多合成了很多份，这个超级样板总成里面的神锻倍率就是这个意思啊」</blockquote>
 * <pre>
 *   报给 AE 的样板产物数量 = 样板原产物数量 × creationProductionMultiplier()   ← 解码样板给 AE 那一步
 *   实际产出的数量         = 样板原产物数量 × creationProductionMultiplier()   ← 与上一行必须严格相等
 *   倍率 = 主机增产（现读主机，不落盘、不可手改；主机给 0 ⇒ ×1，与改动前逐位相同）
 * </pre>
 * 🔴 <b>只有一层乘法</b>：没有"手填倍率"这回事（旧口径「手填 1~30 × 主机增产」已作废）。
 * 报数和产出用<b>同一个函数</b>，是防「AE 多合份数」的关键；倍率变了（主机等级 / 门控 / 换主机）
 * 必须让 AE 重算 —— 执行器每 tick 比对倍率并 {@code ICraftingProvider.requestUpdate(...)}。
 * <p>界面 = 侧栏「神锻」子页签（{@link CreationForgePatternPanel}，<b>我们自己画的</b>，不引上游面板类）——
 * <b>纯读数，没有任何开/关控件</b>。
 * <p>客户端读数走 {@code aeMultiplierMirror}（{@code @DescSynced} 派生量）：客户端 {@code getHost()} 恒 null，
 * 本地现算只能得到 ×1（那是假数据，本工程红线）⇒ 服务端算完推一份镜像给界面读。
 *
 * <h2>1000 格样板存储</h2>
 * {@link #PATTERN_SLOT_COUNT} = 10 列 × 5 行 × 20 页 = 1000 格，<b>存在机器自己身上</b>（不是仓室），
 * 界面是控制器侧栏的一个「样板」子页签（{@link PatternPageProvider}）。
 * 只收「AE 能解码的编码样板」：判据 = {@link PatternDetailsHelper#decodePattern(ItemStack, Level)} 非 null
 * （见 {@code shanhai$isPatternStackAcceptable}）；放错会被拒收，并在页签标题行右侧显示累计拒收数
 * （{@code patternRejectedCount}）。
 */
public class PrimordialCreationCompilationMatrix extends PrimordialModuleMachine
        implements CreationPatternHost, IInWorldGridNodeHost {

    // ═════════════════════════════ 样板存储（1000 格 = 10×5×20 页） ═════════════════════════════

    /**
     * 注册路径（方块 id = {@code shanhai:primordial_creation_compilation_matrix}）。
     * <p>唯一的字符串真源：{@code ModuleRegistry.SPECS} 与 lang 键 {@code block.shanhai.<path>} 都指向它。
     */
    public static final String MACHINE_PATH = "primordial_creation_compilation_matrix";

    /** 每页列数（10）。 */
    public static final int PATTERN_COLUMNS = 10;
    /** 每页行数（5）。 */
    public static final int PATTERN_ROWS = 5;
    /** 每页格数 = 10 × 5 = 50。 */
    public static final int PATTERN_SLOTS_PER_PAGE = PATTERN_COLUMNS * PATTERN_ROWS;
    /** 页数（20）。 */
    public static final int PATTERN_PAGE_COUNT = 20;
    /** 总格数 = 50 × 20 = <b>1000</b>。 */
    public static final int PATTERN_SLOT_COUNT = PATTERN_SLOTS_PER_PAGE * PATTERN_PAGE_COUNT;

    // ═════════════════════════════ 并行 / 时长（P2 已接进执行链） ═════════════════════════════

    /**
     * 固定配方时长（tick）：<b>10</b>。用户 2026 拍板「速度固定 10 tick」，不需要样板总成。
     *
     * <p>P2 起它是<b>唯一</b>的时长来源：执行器每攒够 {@value #TICK_DURATION} tick 就完成一个周期
     * （把积压按并行上限做掉一批、产物一次全出）。<b>不许</b>引入其它时长来源。
     */
    public static final int TICK_DURATION = 10;

    /** 并行的底数 k = <b>8</b>：并行 = k^物质模块等级（算式本体在 {@link CreationCraftMath}）。 */
    public static final long PARALLEL_BASE_K = CreationCraftMath.PARALLEL_BASE_K;

    /**
     * 「无限」的起点：物质模块等级 <b>≥ 15</b> ⇒ 不算 k^等级，直接传哨兵。
     *
     * <p>为什么这么定（用户拍板，不是我的发明）：上游 gtladditions 的「无尽模式」<b>不是</b>把并行塞成一个大数，
     * 而是<b>把哨兵当参数传下去</b>（{@code Long.MIN_VALUE}），由执行侧识别「这是无限」，
     * 一次性把端口的<b>整张积压表全搬空</b>、时长固定 1 tick。
     * 我们照这个语义：等级 ≥ 15 时 {@link #parallelForMatterLevel(int)} 返回 {@link #INFINITE_PARALLEL_SENTINEL}，
     * 端口的执行侧看到哨兵就做同样的「整表一次搬空」（份数照算，只是不再分批）。
     */
    public static final int INFINITE_PARALLEL_FROM_LEVEL = CreationCraftMath.INFINITE_PARALLEL_FROM_LEVEL;

    /**
     * 「无限」哨兵 = {@link Long#MIN_VALUE}。
     *
     * <p>⚠️ 这个值<b>不可能</b>是任何合法并行数（负数），所以执行侧用它做哨兵是安全的；
     * 但反过来说，<b>任何算术都必须先判哨兵再运算</b>（把它当数字加/乘会得到垃圾）。
     * 判据统一走 {@link CreationCraftMath#isInfinite(long)}（与上游 {@code MECraftHandlerMixin} 逐字同款）。
     */
    public static final long INFINITE_PARALLEL_SENTINEL = CreationCraftMath.INFINITE_PARALLEL_SENTINEL;

    // ═════════════════════════════ 字段 ═════════════════════════════

    /**
     * 本类自己的 {@code ManagedFieldHolder}。
     *
     * <p>🔴 <b>没有它，{@link #patternInventory} 就是一个「看着有、其实不落盘」的死字段</b>
     * —— LDLib 只认 {@code getFieldHolder()} 返回的那条链上登记过的字段，子类不重写 holder，
     * 新增字段既不会落盘也不会同步，<b>而且不报错</b>（本工程踩过：星律那台）。
     * <p>第二参必须取<b>链上最近的那个 holder</b> = {@code PrimordialModuleMachine.MANAGED_FIELD_HOLDER}
     * （该类注释第 1186–1192 行已核：{@code WorkableElectricMultiblockMachine} 自己没有 holder）。
     * <p>⇒ 为此把父类那个字段的可见性从 {@code private} 放宽到 {@code protected}
     * （<b>纯放宽，不改任何行为</b>；否则子类拿不到它，只能偷偷反射）。
     */
    protected static final ManagedFieldHolder MANAGED_FIELD_HOLDER = new ManagedFieldHolder(
            PrimordialCreationCompilationMatrix.class, PrimordialModuleMachine.MANAGED_FIELD_HOLDER);

    private static final Logger LOGGER = LoggerFactory.getLogger("shanhai/creation-matrix");

    /**
     * 1000 格样板存储（存在<b>机器自己</b>身上，不是仓室）。
     *
     * <p>只标 {@code @Persisted}、<b>不</b>标 {@code @DescSynced}：与本工程已验收那台
     * （{@code StellarSampleAssemblerPartMachine#wildcardPatternInventory}）逐字同款
     * —— 槽内物品由服务端权威，界面槽的显示走 LDLib 自己的槽同步，不靠机器字段广播
     * （1000 个 ItemStack 每 tick 广播一次是不可接受的）。
     */
    @Persisted
    private final ItemStackTransfer patternInventory;

    /** 内容版本号：只有内容真变了才 +1（端口靠它决定要不要重解码 + 通知 AE）。不落盘：纯运行期派生量。 */
    private long patternVersion;

    /** 被拒收的非样板物品次数（页签上显示，给玩家"我放错了"的可见反馈）。 */
    private int patternRejectedCount;

    /**
     * 那 1000 格的「批写入收据」（P7 上传接管用，见 {@link CreationPatternUpload}）。
     *
     * <p>它只做一件事：把 {@link #patternInventory} 的 {@code ItemStackTransfer} 翻译成
     * {@link CreationPatternUpload.Sink} 的 10 个动作，好让<b>批写入算法</b>待在
     * {@link CreationPatternUpload} 里（那个类零 MC 依赖 ⇒ 能离线自证）。
     * <p>字段化（而不是每次 new）：上传是玩家动作、频率低，但同一个 sink 复用能让"哪台机器被选"的
     * 反查（{@link CreationPatternUpload.Sink#owner()}）稳定指向本机。
     */
    private final CreationPatternUpload.Sink<ItemStack> uploadSink;

    // ═════════════════════════════ 神锻倍率（P10 起 = 主机增产，没有开关） ═════════════════════════════

    /**
     * <b>「此刻报给 AE 的倍率」的客户端镜像</b>（{@code @DescSynced}；派生量 ⇒ <b>不</b>{@code @Persisted}）。
     *
     * <h2>🔴 为什么非要这个镜像（不是洁癖，是"界面不许放假数据"）</h2>
     * 真正的倍率只有一处：{@link #creationProductionMultiplier()}（现读主机）。但它的两个输入
     * （{@code getHost()} ⇒ {@code moduleSlotBonus()} / {@code getHostProductionBonusExtra()}）
     * <b>都只在服务端有值</b>：客户端 {@code host} 恒 {@code null}（唯一写它的
     * {@code findAndConnectToHost()} 只在服务端跑，见 {@code PrimordialModuleMachine} 同名字段的 javadoc）
     * ⇒ 客户端本地算出来的倍率<b>恒 ×1</b>，而实机主机给的是 ×7。
     * 若界面直接调 {@link #creationProductionMultiplier()}，玩家会看到「×1」—— 正是这次报障的那一幕。
     * <p>⇒ 服务端算完<b>只在值变化时</b>写这个字段 + {@code notifyBlockUpdate()}（本类
     * {@code shanhai$crossTypeModeActive} 那条既有纪律）；客户端只读它，<b>不</b>本地重算。
     *
     * <p>它<b>不参与任何运算</b>：报数（{@code CreationScaledPattern}）与出货（{@link CreationCraftMath#outputsOf}）
     * 用的都是服务端现算的 {@link #getAeReportedPatternMultiplier()}。镜像的唯一消费者是神锻页的读数行。
     */
    @DescSynced
    private int aeMultiplierMirror = 1;


    // ═════════════════════════════ AE 网格节点（P3：控制器自己挂 AE，不再有端口方块） ═════════════════════════════

    /**
     * 本控制器的 AE 网格节点；{@code null} = 还没建 / 已销毁。
     *
     * <h2>🔴 为什么是"可重建"的字段（不是 final、不做 NBT 持久化）</h2>
     * {@link IManagedGridNode} 的实现（{@code appeng.me.ManagedGridNode}）是<b>一次性</b>的：
     * {@code create()} 会把内部的 {@code initData} 置空，之后再调 {@code create()} /
     * {@code addService()} / {@code setExposedOnSides()} 会抛
     * {@code IllegalStateException: The node has already been initialized...}
     * （{@code javap -c appeng.me.ManagedGridNode#getInitData} 实证）。
     * ⇒ 卸载时 {@code destroy()} 之后必须<b>丢掉这个实例</b>，下次加载重新 {@link #shanhai$ensureMainNode()}
     * 建一个新的（服务也要在新实例上重新 {@code addService}）。
     *
     * <p><b>没有做 NBT 持久化</b>（AE2 自己的方块实体会 {@code saveToNBT/loadFromNBT}）：
     * 节点序列号每次加载换一个，等价于"重新放了一根线缆"，功能不受影响；
     * 要跟 GTCEu 的 {@code GridNodeHolder} 那样持久化得多挂一层 LDLib 的
     * {@code @Persisted @ReadOnlyManaged}（三方法反射绑定），属于本轮范围外 —— 见交付报告「偏离」。
     */
    @Nullable
    private IManagedGridNode mainNode;

    /**
     * 收单 / 执行 / 出货（{@link ICraftingProvider} 的实现者）。
     *
     * <p>它是<b>上面那个节点的 service</b>（{@code addService(ICraftingProvider.class, craftExecutor)}）——
     * 那一行就是"AE 认出这台机器能接样板"的全部。
     */
    private final CreationCraftExecutor craftExecutor;

    /** 控制器每个 server tick 的推进（AE 缓存刷新 + 10 tick 周期）；P2 里这是端口自己的订阅。 */
    @Nullable
    private TickableSubscription aeTickSubs;

    /**
     * 节点的监听器（{@link GridHelper#createManagedNode} 的第二个参数）。
     *
     * <p>为什么不用 AE2 自带的 {@code appeng.me.helpers.BlockEntityNodeListener}：
     * 它的泛型上界是 {@code <T extends IGridConnectedBlockEntity>}，而本类是 {@code MetaMachine}
     * 而不是方块实体（GTCEu 的 {@code GridNodeHolder} 为此还做了一次 {@code checkcast}）。
     * 自己写一个同义的小监听器，两个回调各自转发给本类已有的方法 —— 语义与它一致：
     * <ul>
     *   <li>{@code onSaveChanges} ⇒ {@code owner.onChanged()}（GTCEu 的
     *       {@code IGridConnectedMachine#saveChanges()} 默认实现就是 {@code self().onChanged()}）；</li>
     *   <li>{@code onStateChanged} ⇒ 让样板缓存作废 + {@code requestUpdate}
     *       （P2 端口里的 {@code onMainNodeStateChanged} 同义）。</li>
     * </ul>
     */
    private static final IGridNodeListener<PrimordialCreationCompilationMatrix> NODE_LISTENER =
            new IGridNodeListener<>() {

                @Override
                public void onSaveChanges(PrimordialCreationCompilationMatrix owner, IGridNode node) {
                    owner.onChanged();
                }

                @Override
                public void onStateChanged(PrimordialCreationCompilationMatrix owner, IGridNode node,
                                           IGridNodeListener.State state) {
                    owner.craftExecutor.onGridStateChanged();
                }
            };

    public PrimordialCreationCompilationMatrix(IMachineBlockEntity holder, Object... args) {
        super(holder, args);
        this.patternInventory = new ItemStackTransfer(PATTERN_SLOT_COUNT);
        this.patternInventory.setFilter(this::shanhai$isPatternStackAcceptable);
        this.patternInventory.setOnContentsChanged(this::shanhai$onPatternInventoryChanged);
        // 🔴 执行器必须早于节点建好：节点 create() 之前要 addService(ICraftingProvider.class, 它)。
        this.craftExecutor = new CreationCraftExecutor(this);
        // P7：给"样板终端 → 自动上传"那两条链用的批写入收据（只包 1000 格，不碰别的状态）
        this.uploadSink = shanhai$createUploadSink();
    }

    @Override
    public ManagedFieldHolder getFieldHolder() {
        return MANAGED_FIELD_HOLDER;
    }

    // ═════════════════════════════ AE 节点生命周期（照 GTCEu GridNodeHolder 逐条同形） ═════════════════════════════

    /**
     * 建（或取回）本控制器的 AE 节点，并把 {@link ICraftingProvider} 挂成它的 service。
     *
     * <p>参数口径与 GTCEu 的 {@code GridNodeHolder#createManagedNode()} <b>逐项同源</b>
     * （{@code javap -c} 实证），只换了一样东西：暴露面。
     * <ul>
     *   <li>{@code setFlags(GridFlags.REQUIRE_CHANNEL)} —— 与原来的端口仓室（GT AE 仓室）一致，
     *       玩家必须给这台机器一条频道；</li>
     *   <li>{@code setVisualRepresentation(getDefinition().getItem())} —— 节点在 AE 里的图标；</li>
     *   <li>{@code setIdlePowerUsage(ConfigHolder…meHatchEnergyUsage)} —— 与 GT AE 仓室同源的空载耗电，
     *       保证"AE 侧的耗电口径"与 P2 相同；</li>
     *   <li>{@code setInWorldNode(true)} + {@code setExposedOnSides(EnumSet.allOf(Direction))} ——
     *       <b>与 GT 仓室不同的一处</b>：GT 仓室只暴露"正面"（它嵌在结构墙上），
     *       而控制器是<b>整台机器的正面那块方块</b>（见交付报告：图案里控制器 6 面有 5 面是外壳，
     *       唯一没被占住的就是正面），所以这里把 6 面都算可接 —— 比 GT 的 `EnumSet.of(front)` 更宽，
     *       永远不会出现"明明贴着控制器却接不上"的形态；</li>
     *   <li>{@code addService(ICraftingProvider.class, craftExecutor)} —— <b>AE 认出它接样板的那一行</b>，
     *       必须在 {@code create()} 之前（{@code javap -c appeng.me.ManagedGridNode$InitData#createNode}
     *       实证：services 只在建节点那一刻被搬到 {@code GridNode} 上）。</li>
     * </ul>
     */
    private IManagedGridNode shanhai$ensureMainNode() {
        IManagedGridNode node = this.mainNode;
        if (node != null) {
            return node;
        }
        node = GridHelper.createManagedNode(this, NODE_LISTENER);
        node.setFlags(GridFlags.REQUIRE_CHANNEL)
                .setIdlePowerUsage(ConfigHolder.INSTANCE.compat.ae2.meHatchEnergyUsage)
                .setInWorldNode(true)
                .setExposedOnSides(EnumSet.allOf(Direction.class));
        final com.gregtechceu.gtceu.api.machine.MachineDefinition definition = getDefinition();
        if (definition != null) {
            node.setVisualRepresentation(definition.getItem());
        }
        node.addService(ICraftingProvider.class, this.craftExecutor);
        this.mainNode = node;
        return node;
    }

    /** 给执行器用的节点句柄（没建好 ⇒ {@code null}；执行器据此判"网格没连上"）。 */
    @Nullable
    public IManagedGridNode creationMainNode() {
        return this.mainNode;
    }

    /**
     * AE 的 {@link IInWorldGridNodeHost} 契约（🔴 用户拍板「改成控制器自己挂 AE」的落点）。
     *
     * <p>由 {@code GridHelper#getExposedNode(level, pos, side)} 调用（AE 建/刷新节点、以及线缆贴着方块时
     * 都会问一次）。
     * <p>🔴 这里<b>顺手把节点对象建出来</b>（只建"配置 + 服务"那一层，不建 AE 节点；真正 {@code create()}
     * 仍在 {@link #shanhai$createMainNode()} 里延到下一 tick）：这样"AE 比 onLoad 更早问一次"的窗口里
     * 也不会得到"宿主在、节点句柄是 null"的回答 —— 少一个只在加载瞬间出现的连接竞态。
     * <p>客户端恒 {@code null}（AE 那半边只在服务端跑；客户端上 {@code ManagedGridNode#create}
     * 本来就不会建节点）。
     */
    @Override
    @Nullable
    public IGridNode getGridNode(Direction side) {
        if (isRemote()) {
            return null;
        }
        return shanhai$ensureMainNode().getNode();
    }

    /**
     * 加载：建节点（<b>下一 tick 才真的 create</b>）+ 订阅每 tick 的推进。
     *
     * <p>为什么 create 要延到下一 tick：与 GTCEu {@code GridNodeHolder#onMachineLoad()} 逐字同款
     * （它 {@code server.execute(new TickTask(0, this::createMainNode))}）—— AE2 自己也是
     * "方块实体 ready 之后才建节点"，加载那一瞬间网格还没就绪。
     */
    @Override
    public void onLoad() {
        super.onLoad();
        if (isRemote()) {
            return;
        }
        shanhai$ensureMainNode();
        final Level level = getLevel();
        if (level instanceof ServerLevel serverLevel) {
            serverLevel.getServer().execute(new TickTask(0, this::shanhai$createMainNode));
        }
        if (aeTickSubs == null) {
            aeTickSubs = subscribeServerTick(aeTickSubs, this.craftExecutor::serverTick);
        }
    }

    /** 真正 {@code create()} 节点（只在服务端；已经建过就跳过，防止同一对象 onLoad 两次）。 */
    private void shanhai$createMainNode() {
        final IManagedGridNode node = this.mainNode;
        final Level level = getLevel();
        if (node == null || level == null || level.isClientSide()) {
            return;
        }
        if (node.getNode() != null) {
            return;
        }
        node.create(level, getPos());
    }

    /**
     * 卸载：退订 + 把积压的欠账喊出来 + <b>销毁节点并丢掉实例</b>。
     *
     * <p>🔴 {@code destroy()} 之后必须把 {@link #mainNode} 置 {@code null}
     * （{@code ManagedGridNode} 不能复用，见字段注释）。
     */
    @Override
    public void onUnload() {
        if (aeTickSubs != null) {
            aeTickSubs.unsubscribe();
            aeTickSubs = null;
        }
        if (!isRemote()) {
            this.craftExecutor.onMachineUnload();
            final IManagedGridNode node = this.mainNode;
            this.mainNode = null;
            if (node != null) {
                try {
                    node.destroy();
                } catch (Throwable t) {
                    LOGGER.warn("[SHANHAI-CREATION] 销毁 AE 网格节点时抛异常（已忽略，节点实例已丢弃）：{}",
                            t.toString());
                }
            }
        }
        super.onUnload();
    }

    // ═════════════════════════════ CreationPatternHost：执行器从这里读 ═════════════════════════════

    @Override
    public IItemTransfer getCreationPatternInventory() {
        return patternInventory;
    }

    @Override
    public int getCreationPatternSlotCount() {
        return PATTERN_SLOT_COUNT;
    }

    @Override
    public long getCreationPatternVersion() {
        return patternVersion;
    }

    // ═════════════════════════════ 神锻倍率：唯一出口（P10 起恒 = 主机增产，无开关） ═════════════════════════════

    /**
     * <b>此刻该报给 AE 的样板倍率</b>（= {@link CreationPatternHost} 的读取点，执行器用它报数 + 出货）。
     *
     * <h2>🔴 P10：<b>无条件</b>取主机增产（用户原话「干脆去掉开关，永远吃主机增产」）</h2>
     * 这里<b>不再有任何判据</b>：既没有开关、也没有 {@code ? : 1} 的三元。恒等于
     * {@link #creationProductionMultiplier()} = {@code productionMultiplierOf(主机门控等级, 主机自带增产)}。
     * <p>主机给 0（老主机 / 没有主机）⇒ {@code outputMultiplier(0) + max(0, 0) = 1}
     * ⇒ <b>与改动前"开关关着"的形态逐位相同</b>（老主机行为不变，见自证 ②）。
     *
     * <h2>两条消费路径都必须是它</h2>
     * ① 执行器 {@code getAvailablePatterns()} 用它把样板产物数量放大后报给 AE；
     * ② 执行器出货（{@link CreationCraftMath#outputsOf}）用它乘实际产出。
     * 两个数〈必须严格相等〉，否则 AE 会按错的数量下单。
     * <p>服务端顺手把值发布给客户端（只在变化时写 + {@code notifyBlockUpdate()}，见 {@link #aeMultiplierMirror}）。
     */
    @Override
    public int getAeReportedPatternMultiplier() {
        // 🔴 P10：没有 if、没有三元 —— 恒取主机增产。
        final int now = creationProductionMultiplier();
        shanhai$publishAeMultiplier(now);
        return now;
    }

    /**
     * 界面上要显示的倍率。
     *
     * <p>服务端：现算（顺带刷新镜像）；客户端：读服务端推过来的镜像。
     * 🔴 客户端<b>绝不许</b>本地现算 —— 那边 {@code getHost()} 恒 {@code null} ⇒ 现算恒 ×1 ⇒ 界面撒谎。
     */
    public int aeMultiplierForDisplay() {
        if (isRemote()) {
            return this.aeMultiplierMirror;
        }
        return getAeReportedPatternMultiplier();
    }

    /**
     * 把「此刻报给 AE / 出货用的倍率」发布给客户端（<b>只在值变化时</b>写字段 + {@code notifyBlockUpdate()}）。
     *
     * <p>调用点：{@link #getAeReportedPatternMultiplier()}（服务端每 tick 由执行器读一次）。
     * <p>客户端调用直接返回（那份值是服务端写过来的，客户端不许反向覆盖）。
     */
    private void shanhai$publishAeMultiplier(int multiplier) {
        if (isRemote() || this.aeMultiplierMirror == multiplier) {
            return;
        }
        this.aeMultiplierMirror = multiplier;
        // @DescSynced 字段送到客户端的唯一动作（与本类/父类同一条纪律）。
        notifyBlockUpdate();
        LOGGER.info("[SHANHAI-CREATION] 报给 AE 的倍率 → ×{}（现读主机；报数与出货同源）", multiplier);
    }

    // ── 神锻面板的三行纯读数（没有开/关，倍率一律现读主机、绝不落盘、绝不手改） ──

    /** 读数第 ① 行：本机倍率 = 主机增产 ×N（现读）。 */
    public String forgePatternMultiplierLine() {
        return "§7本机倍率 = 主机增产 §f×" + aeMultiplierForDisplay() + "§7（现读主机）";
    }

    /** 读数第 ② 行：报数（AE）与出货（真产出）用的是同一个倍率。 */
    public String forgePatternSameSourceLine() {
        return "§7报给 AE 的产物数量与实际出货 §f用同一个倍率§7（逐项严格相等）";
    }

    /**
     * 读数第 ③ 行：主机增产是怎么来的（<b>解释</b>，不是本机实时值）。
     *
     * <p>与 {@link PrimordialRecipeEffects#productionMultiplierOf(int, int)} 的两项一一对应：
     * {@code outputMultiplier(gateBonus)} = {@code 1 + 等级}（门控 0 ⇒ 1），外加宿主的常驻增产
     * （归墟视界枢核覆写 {@code getHostProductionBonusExtra()} 恒返 5；老主机返 0 ⇒ 逐位不变）。
     * ⇒ <b>不许</b>把它当成"本机此刻的倍率"来读 —— 那个数是第 ① 行。
     */
    public String forgePatternHostBonusLine() {
        return "§7主机增产 = 1 + 物质模块等级§7（归墟门控生效 §f再 +5§7）";
    }

    // ═════════════════════════════ 产物倍率：执行器出货 / 报给 AE 都从这里取 ═════════════════════════════

    /**
     * <b>本机产出倍率的唯一读取点</b>（= 主机 N3 增产那一套，本工程现成算式）。
     *
     * <p>🔴 用户 2026 拍板（逐字）：
     * <blockquote>产物照例吃主机增产，不设上限 ⇒ 倍率 =
     * {@code productionMultiplierOf(物质模块槽给的份数, host.getHostProductionBonusExtra())}</blockquote>
     * ⇒ 这里<b>照抄语义</b>、直接用 {@link PrimordialRecipeEffects#productionMultiplierOf(int, int)}
     * （本工程那份唯一实现：{@code outputMultiplier(gateBonus) + max(0, extra)}），
     * 两个入参都取自<b>主机那两个钩子</b>：
     * <ol>
     *   <li>{@code gateBonus} = 主机门控等级（{@code PrimordialOmegaEngineMachine#moduleSlotBonus()}）；</li>
     *   <li>{@code extra} = 主机自带的常驻增产（{@code PrimordialOmegaEngineMachine#getHostProductionBonusExtra()}）。</li>
     * </ol>
     * <p>🔴 <b>不许写死 {@code 1 + 等级}</b>：那是另一套语义（会把老主机的 extra 丢掉、也会把门控当等级）。
     *
     * <p>P10 起<b>恒</b>乘（两处，同一个数、同一个函数）：
     * <pre>
     *   报给 AE 的产物数量 = 样板原产物数量 × getAeReportedPatternMultiplier()   ← 执行器解码时包 CreationScaledPattern
     *   实际产出的数量     = 样板原产物数量 × getAeReportedPatternMultiplier()   ← 执行器出货时 CreationCraftMath#outputsOf
     *   （两个数必须严格相等；getAeReportedPatternMultiplier() = 本方法，没有开关、没有第二层乘法）
     * </pre>
     * 另：神锻页第 ① 行读数（{@link #forgePatternMultiplierLine()}）读的也是同一个数
     * （显示与生效同源；客户端读的是服务端推下来的 {@link #aeMultiplierMirror}）。
     */
    public int creationProductionMultiplier() {
        final PrimordialOmegaEngineMachine host = getHost();
        final int gateBonus = host == null ? 0 : host.moduleSlotBonus();
        final int hostExtra = host == null ? 0 : host.getHostProductionBonusExtra();
        return PrimordialRecipeEffects.productionMultiplierOf(gateBonus, hostExtra);
    }

    /**
     * 静态形态（给 P2 / 离线自检用）：<b>两个入参都由调用方提供</b>，语义与
     * {@link PrimordialRecipeEffects#productionMultiplierOf(int, int)} 完全相同。
     *
     * <p>存在的唯一理由：让"倍率公式"在这台机器上有<b>一个可命名的入口</b>，
     * P2 与界面都指向它，而不是各自 copy 一遍算式。
     */
    public static int productionMultiplierOf(int matterSlotBonus, int hostProductionBonusExtra) {
        return PrimordialRecipeEffects.productionMultiplierOf(matterSlotBonus, hostProductionBonusExtra);
    }

    // ═════════════════════════════ 样板槽过滤（只收 AE 能解码的编码样板） ═════════════════════════════

    /**
     * 槽位过滤器：只接受<b>能被 AE 解码成 {@link IPatternDetails} 的编码样板</b>。
     *
     * <p>判据两层，缺一不可：
     * <ol>
     *   <li>{@link PatternDetailsHelper#isEncodedPattern(ItemStack)} —— 便宜的第一道，
     *       只认 AE 自己的编码样板物品（空白样板、普通物品在这一道就被挡掉）；</li>
     *   <li>{@link PatternDetailsHelper#decodePattern(ItemStack, Level, boolean)} 非 null ——
     *       用户口径「decodePattern 能为 null 的物品不许放进去」。用 {@code autoRecovery = false}：
     *       这是<b>只读判定</b>，不许顺手修复/改写玩家手里的样板。</li>
     * </ol>
     * <p>放错时的表现：<b>物品留在玩家光标上</b>（原版槽语义：过滤器拒绝 = 这一下没放进去），
     * 同时 {@link #patternRejectedCount} 累加，页签标题行右侧显示「拒收 N 件」。
     * <p>⚠️ 本方法两侧都会跑（客户端点槽 + 服务端执行点击）⇒ 计数只在服务端累加（{@code isRemote()} 守卫），
     * 否则同一个动作会被数两次。
     */
    private boolean shanhai$isPatternStackAcceptable(ItemStack stack) {
        if (stack.isEmpty()) {
            return true;
        }
        if (!PatternDetailsHelper.isEncodedPattern(stack)) {
            shanhai$noteRejectedPattern(stack, "不是 AE 编码样板");
            return false;
        }
        if (shanhai$decodePattern(stack) == null) {
            shanhai$noteRejectedPattern(stack, "decodePattern 返回 null（AE 不认这块样板）");
            return false;
        }
        return true;
    }

    private void shanhai$noteRejectedPattern(ItemStack stack, String why) {
        if (isRemote()) {
            return;
        }
        patternRejectedCount++;
        LOGGER.warn("[SHANHAI-CREATION] 样板槽拒收 {} ×{}：{}（累计拒收 {} 次）",
                stack.getHoverName().getString(), stack.getCount(), why, patternRejectedCount);
    }

    /**
     * 解码一块样板；失败返回 {@code null}（<b>不抛</b>）。
     *
     * <p>为什么不直接 {@code decodePattern} 而包一层：AE 的解码器对"坏样板"的行为没有契约保证
     * （可能返回 null、也可能抛 {@code IllegalArgumentException}），而这里被调用在
     * <b>槽过滤器</b>与 <b>AE 每 tick 的查询链</b>上 —— 让一次坏样板把 GUI 或 AE 网络打崩是不可接受的。
     */
    @Nullable
    public IPatternDetails shanhai$decodePattern(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        final Level level = getLevel();
        if (level == null) {
            return null;
        }
        try {
            return PatternDetailsHelper.decodePattern(stack, level, false);
        } catch (Throwable t) {
            LOGGER.warn("[SHANHAI-CREATION] 样板解码抛异常（按不可解码处理）：{} — {}",
                    stack.getHoverName().getString(), t.toString());
            return null;
        }
    }

    private void shanhai$onPatternInventoryChanged() {
        if (isRemote()) {
            return; // 内容变更回调两侧都会跑；版本号只由服务端推进（执行器也只在服务端读）
        }
        patternVersion++;
    }

    // ═════════════════════════════ 上传接管（P7）：样板终端把样板整批写进这 1000 格 ═════════════════════════════

    /**
     * 本机 1000 格的<b>批写入收据</b>（{@link CreationPatternUpload.Sink}）—— P7 两条上传链唯一的写入口。
     *
     * <h2>为什么要有这一层，而不是让 mixin 直接拿 {@link #patternInventory} 写</h2>
     * <ol>
     *   <li><b>算法与库存解耦</b>：批写入（全或无 / 升序 / 查重）待在 {@link CreationPatternUpload} 里，
     *       那个类<b>零 MC 依赖</b> ⇒ 能在 {@code temp\molmanip\p7selftest} 里离线跑生产代码本身；</li>
     *   <li><b>写槽只走一条路</b>：所有写入都经过 {@link ItemStackTransfer#insertItem(int, ItemStack, boolean)}
     *       ⇒ 槽过滤器（{@link #shanhai$isPatternStackAcceptable}）与
     *       {@code setOnContentsChanged}（{@link #shanhai$onPatternInventoryChanged} 推进
     *       {@code patternVersion}，执行器据此重解码 + 通知 AE）<b>自动生效</b>，
     *       不会出现"外部写进去但 AE 不知道"的形态。</li>
     * </ol>
     *
     * <p>🔴 <b>样板类型的门在这里</b>（{@link CreationPatternUpload.Sink#accepts}）：
     * 只认 {@link IMolecularAssemblerSupportedPattern} = AE2 的合成 / 锻造台 / 切石三类
     * （{@code javap} 实证：{@code AECraftingPattern} / {@code AESmithingTablePattern} /
     * {@code AEStonecuttingPattern} 都 implements 它，且只有它们三个）。
     * 理由：这三类正是<b>分子操纵者</b>收的那一批（gtlcore 的 MA 分支判据
     * {@code PatternQuickUploadRecipeTypeResolver#isMolecularRecipeTypeId} = crafting / smithing / stonecutting），
     * 所以"我们 &gt; 分子操纵者"这条优先级才成立；处理样板仍然归样板总成 / GT 机器，
     * 塞进本机就是"收下但跑不了"= 玩家丢样板。
     */
    private CreationPatternUpload.Sink<ItemStack> shanhai$createUploadSink() {
        return new CreationPatternUpload.Sink<>() {

            @Override
            public String describe() {
                final Level level = PrimordialCreationCompilationMatrix.this.getLevel();
                return "原初造物编译矩阵@[" + PrimordialCreationCompilationMatrix.this.getPos().toShortString() + "]"
                        + (level == null ? "" : ("/" + level.dimension().location()));
            }

            @Override
            public Object owner() {
                return PrimordialCreationCompilationMatrix.this;
            }

            @Override
            public boolean isFormed() {
                return PrimordialCreationCompilationMatrix.this.isFormed();
            }

            @Override
            public boolean isNodeActive() {
                final IManagedGridNode node = PrimordialCreationCompilationMatrix.this.mainNode;
                return node != null && node.isActive();
            }

            @Override
            public boolean accepts(ItemStack stack) {
                return PrimordialCreationCompilationMatrix.this.shanhai$decodePattern(stack)
                        instanceof IMolecularAssemblerSupportedPattern;
            }

            @Override
            public int slotCount() {
                return PrimordialCreationCompilationMatrix.this.patternInventory.getSlots();
            }

            @Override
            public boolean isEmptySlot(int slot) {
                return PrimordialCreationCompilationMatrix.this.patternInventory.getStackInSlot(slot).isEmpty();
            }

            @Override
            public boolean isSameStack(int slot, ItemStack stack) {
                return PrimordialCreationCompilationMatrix.this.shanhai$isSamePatternStack(
                        PrimordialCreationCompilationMatrix.this.patternInventory.getStackInSlot(slot), stack);
            }

            @Override
            public boolean isItemValid(int slot, ItemStack stack) {
                return PrimordialCreationCompilationMatrix.this.patternInventory.isItemValid(slot, stack);
            }

            @Override
            public boolean canInsertInto(int slot, ItemStack stack) {
                // 只读试写：LDLib 的三参 insertItem(slot, stack, simulate=true) 是纯模拟（4 参版 notify=!simulate）
                return PrimordialCreationCompilationMatrix.this.patternInventory
                        .insertItem(slot, stack.copy(), true).isEmpty();
            }

            @Override
            public boolean insertInto(int slot, ItemStack stack) {
                final ItemStack remain = PrimordialCreationCompilationMatrix.this.patternInventory
                        .insertItem(slot, stack.copy(), false);
                if (!remain.isEmpty()) {
                    LOGGER.warn("[SHANHAI-CREATION-UPLOAD] 写入 {} 号槽后仍有剩余 {} 件，按写入失败处理",
                            slot, remain.getCount());
                    return false;
                }
                return true;
            }
        };
    }

    /** 给两个上传 mixin 用的只读句柄（同 {@link #uploadSink}，刻意不暴露 setter）。 */
    public CreationPatternUpload.Sink<ItemStack> creationUploadSink() {
        return uploadSink;
    }

    /**
     * 这批样板能不能<b>整批</b>收进本机（只读、不写、不改任何状态）。
     *
     * <p>判据全在 {@link CreationPatternUpload#decide}（六道闸：成型 / 节点激活 / 类型 / 查重 / 容量 / 过滤器）。
     * 链 A 拿 {@link CreationPatternUpload.Decision#accepted()} 决定要不要 {@code ci.cancel()}；
     * 链 B 拿它在"搜索候选"阶段就把自己排成唯一候选，真正写时才 {@code commit()}。
     */
    public CreationPatternUpload.Decision<ItemStack> creationUploadDecision(List<ItemStack> stacks) {
        return CreationPatternUpload.decide(uploadSink, stacks);
    }

    /**
     * 「是不是同一块样板」—— 与上游 {@code MatrixUploadUtil#matrixContainsPattern} 同口径：
     * 先比物品 + 完整 NBT；不一致时把 NBT 里的 <b>{@code encodePlayer}</b>（记录"谁编码的"）抹掉再比。
     *
     * <p>为什么要抹：AE2/extendedae_plus 会在编码时写入 {@code encodePlayer}，
     * 同一张配方被两个玩家编码出来 NBT 就不同 ⇒ 不抹的话"重复上传"认不出来，1000 格里会攒下同一块样板的多份副本。
     */
    private boolean shanhai$isSamePatternStack(ItemStack stored, ItemStack stack) {
        if (stored == null || stack == null || stored.isEmpty() || stack.isEmpty()) {
            return false;
        }
        if (!ItemStack.isSameItem(stored, stack)) {
            return false;
        }
        if (ItemStack.isSameItemSameTags(stored, stack)) {
            return true;
        }
        final ItemStack a = stored.copy();
        final ItemStack b = stack.copy();
        if (a.getTag() != null) {
            a.getTag().remove("encodePlayer");
        }
        if (b.getTag() != null) {
            b.getTag().remove("encodePlayer");
        }
        return ItemStack.isSameItemSameTags(a, b);
    }

    /**
     * 这张 AE 网格上<b>我们所有的</b>原初造物编译矩阵，<b>按坐标排序</b>。
     *
     * <h2>为什么非得排一次序</h2>
     * {@code IGrid#getMachines(Class)} 背后是 Guava 的 {@code hashSetValues()}（{@code javap} 实证：
     * {@code appeng.me.Grid#add} 偏移 1 = {@code machines.put(node.getOwner().getClass(), node)}，
     * 而 {@code Grid.create} 偏移 8 = {@code hashSetValues()}）⇒ 迭代序是 <b>HashSet 序</b>，
     * 与放置顺序、距离、优先级都无关。不排序的话「这次选了哪台机器」会随加载顺序漂，
     * 玩家看到的就是"同样的操作，样板进了不同的机器"。
     *
     * <p>🔴 顺带解释"我们为什么能被 {@code getMachines} 找到"（这是 P7 能成立的前提，已按字节码核实）：
     * AE2 把机器键成 {@code node.getOwner().getClass()}，而本类的节点是
     * {@code GridHelper.createManagedNode(this, NODE_LISTENER)} 建的 ⇒ {@code logicalHost == 本机}
     * （{@code javap}：{@code ManagedGridNode$InitData#createNode} 偏移 40 取 {@code logicalHost}，
     * 传给 {@code InWorldGridNode(level,pos,logicalHost,listener,flags)}）⇒
     * {@code grid.getMachines(PrimordialCreationCompilationMatrix.class)} 正好返回本类实例。
     */
    public static List<PrimordialCreationCompilationMatrix> creationMachinesOn(@Nullable IGrid grid) {
        if (grid == null) {
            return List.of();
        }
        final List<PrimordialCreationCompilationMatrix> out = new ArrayList<>();
        try {
            for (PrimordialCreationCompilationMatrix machine
                    : grid.getMachines(PrimordialCreationCompilationMatrix.class)) {
                if (machine != null) {
                    out.add(machine);
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("[SHANHAI-CREATION-UPLOAD] 枚举网格上的原初造物编译矩阵时抛异常（按「没有候选」处理）：{}",
                    t.toString());
            return List.of();
        }
        out.sort(Comparator.comparing(PrimordialCreationCompilationMatrix::getPos));
        return out;
    }

    /**
     * 「这次上传归谁」的答案：<b>哪台机器 + 它的落位计划</b>。
     *
     * @param machine 被选中的机器（非 null）
     * @param plan    落位计划（还没写；调用方决定什么时候 {@code commit()}）
     */
    public record UploadChoice(PrimordialCreationCompilationMatrix machine,
                               CreationPatternUpload.Plan<ItemStack> plan) {
    }

    /**
     * 链 A / 链 B <b>共用</b>的判定：网格上有没有一台机器能<b>整批</b>收下这批样板。
     *
     * <p>顺序 = {@link #creationMachinesOn} 的坐标序（<b>不</b>依赖上游的哈希序）；
     * 「第一台能收的就是它」由 {@link CreationPatternUpload#planAny} 决定 ——
     * 那段逻辑是离线自证里跑的那一份，不是这里另写一遍。
     *
     * @return {@code null} = <b>谁都不收</b> ⇒ 调用方<b>不许</b> cancel / 独占，原样交给上游
     *         （分子操纵者 &rarr; AE 装配矩阵）
     */
    @Nullable
    public static UploadChoice creationChoiceFor(@Nullable IGrid grid, List<ItemStack> stacks) {
        final List<PrimordialCreationCompilationMatrix> machines = creationMachinesOn(grid);
        if (machines.isEmpty()) {
            return null;
        }
        final List<CreationPatternUpload.Sink<ItemStack>> sinks = new ArrayList<>(machines.size());
        for (PrimordialCreationCompilationMatrix machine : machines) {
            sinks.add(machine.creationUploadSink());
        }
        final CreationPatternUpload.Plan<ItemStack> plan = CreationPatternUpload.planAny(sinks, stacks);
        if (plan == null) {
            return null;
        }
        final Object owner = plan.sink().owner();
        return (owner instanceof PrimordialCreationCompilationMatrix machine)
                ? new UploadChoice(machine, plan) : null;
    }

    /** 手工上传（例如以后的按钮/命令）走这条路：判定 + 落盘一次做完，返回落位计划（null = 没写）。 */
    @Nullable
    public CreationPatternUpload.Plan<ItemStack> shanhai$uploadPatternsNow(List<ItemStack> stacks) {
        final CreationPatternUpload.Decision<ItemStack> decision = creationUploadDecision(stacks);
        if (!decision.accepted()) {
            LOGGER.info("[SHANHAI-CREATION-UPLOAD] 本机不收这批样板：{}", decision.describe());
            return null;
        }
        final CreationPatternUpload.Plan<ItemStack> plan = decision.plan();
        try {
            if (!plan.commit()) {
                LOGGER.warn("[SHANHAI-CREATION-UPLOAD] 落位计划在提交阶段被否（一个字节都没写）：{}", decision.describe());
                return null;
            }
        } catch (CreationPatternUpload.PartialCommitException partial) {
            LOGGER.error("[SHANHAI-CREATION-UPLOAD] 🔴 整批写入中途失败（已写 {} / {}，第 {} 号槽）"
                            + " —— 库存接口没有原子提交，无法回滚，请按日志核对",
                    partial.written(), partial.total(), partial.failedSlot());
            return null;
        }
        return plan;
    }

    // ═════════════════════════════ 并行 / 时长：P2 的执行侧从这里取 ═════════════════════════════

    /**
     * <b>并行 = k^物质模块等级</b>（k = {@link #PARALLEL_BASE_K} = 8）；等级 ≥ {@value #INFINITE_PARALLEL_FROM_LEVEL}
     * ⇒ 返回「无限」哨兵 {@link #INFINITE_PARALLEL_SENTINEL}。
     *
     * <p>这两条都是<b>用户 2026 拍板</b>的口径，P1 只负责写进方法并注释清楚，<b>不接执行链</b>。
     *
     * <p>边界（逐条写清，省得 P2 再猜）：
     * <ul>
     *   <li>等级 ≤ 0（空槽 / 非物质模块 / 还没扫到槽）⇒ 返回 <b>1</b>，理由：{@code 8^0 = 1}，
     *       这是「k^等级」的字面结果。⚠️ <b>如果用户要的是「空槽保底 64」</b>（其余 29 台模块的
     *       {@code DEFAULT_PARALLEL} 口径），那是<b>一行改动</b>：把这里的 {@code return 1L} 换成
     *       {@code return DEFAULT_PARALLEL}，<b>说一声我改</b>。</li>
     *   <li>1 ≤ 等级 ≤ 14 ⇒ {@code 8^等级}（最大 8^14 = 4 398 046 511 104 ≈ 4.4e12，远在 long 内）；</li>
     *   <li>等级 ≥ 15 ⇒ 哨兵（<b>不算数</b>：8^21 才会溢出 long，但用户的判据是 15，
     *       所以 15..17 也一律走「无限」，不再给具体数字）。</li>
     * </ul>
     * <p>饱和保护仍然保留：万一将来 k 或阈值被改动到会溢出，这里返回 {@link Long#MAX_VALUE} 而不是负数
     * （负数会被误认成哨兵）。
     */
    public static long parallelForMatterLevel(int matterModuleLevel) {
        // P2：算式本体在 CreationCraftMath（纯算术、可离线自证），这里是门面。
        return CreationCraftMath.parallelForMatterLevel(matterModuleLevel);
    }

    /**
     * P2 的执行链<b>直接调这个</b>：本机此刻该用多少并行。
     *
     * <p>用的等级是 {@link PrimordialModuleMachine#getEffectiveMatterModuleLevel()}
     * （= 自己槽里的等级 ＋ 主机复写下限，与其余 29 台同源），不是另算一遍。
     * <p>⚠️ 返回值可能是哨兵（{@link #INFINITE_PARALLEL_SENTINEL}）⇒ 调用方<b>必须先判哨兵</b>再参与算术。
     */
    public long extraParallelFor() {
        return extraParallelFor(getEffectiveMatterModuleLevel());
    }

    /** 同 {@link #extraParallelFor()}，但由调用方指定等级（便于离线自检/单测）。 */
    public long extraParallelFor(int matterModuleLevel) {
        return parallelForMatterLevel(matterModuleLevel);
    }

    /**
     * {@link CreationPatternHost#getCreationParallelCap()} 的实现 = {@link #extraParallelFor()}。
     *
     * <p>执行器每 10 tick 用它当"这一批最多做几份"的预算；哨兵（等级 ≥ 15）⇒ 一次全做完。
     * 等级取的是 {@link PrimordialModuleMachine#getEffectiveMatterModuleLevel()}（与其余 29 台同源，
     * 含主机复写），<b>不是</b>另算一遍。
     */
    @Override
    public long getCreationParallelCap() {
        return extraParallelFor();
    }

    /** P2 的时长钩子：固定 {@value #TICK_DURATION} tick（用户拍板，不随等级/核心变化）。 */
    public int getCreationTickDuration() {
        return TICK_DURATION;
    }

    // ═════════════════════════ P4：界面文本修正（两页共同的那部分） ═════════════════════════

    /**
     * GTCEu「最大配方等级：%s」那一行的翻译键（<b>是 GTCEu 的键，不是我们的</b>）。
     *
     * <p>真值来源（两条都是原文，不是从类名猜的）：
     * <ul>
     *   <li>{@code gtceu-1.20.1-1.4.4.jar!assets/gtceu/lang/zh_cn.json} 逐字：</li>
     * </ul>
     * <pre>{@code "gtceu.multiblock.max_recipe_tier": "最大配方等级：%s"}</pre>
     * <ul>
     *   <li>{@code javap -c MultiblockDisplayText$Builder#addEnergyTierLine(int)}：方法体里
     *       {@code ldc "gtceu.multiblock.max_recipe_tier"} ⇒ <b>这一行只由它产出</b>
     *       （它的唯一调用点是 {@code WorkableElectricMultiblockMachine#addDisplayText}，
     *        也就是本类 {@code super.addDisplayText(textList)} 那一句）。</li>
     * </ul>
     *
     * <p>本机<b>不接 GT 配方等级</b>（接的是 AE 合成单）⇒ 这行画出来只有误导 ⇒ 在本类里整行删掉。
     * 删的时候<b>按翻译键删</b>（不按行号、不按文本），以后 GT 插行/改序也不会误删别的行。
     */
    private static final String GT_MAX_RECIPE_TIER_LINE_KEY = "gtceu.multiblock.max_recipe_tier";

    /**
     * GTCEu「当前机器模式：%s」那一行的翻译键（<b>GTCEu 的键，不是我们的</b>）。
     * 真值：{@code gtceu-1.20.1-1.4.4.jar!assets/gtceu/lang/zh_cn.json} 逐字 {@code "gtceu.gui.machinemode": "当前机器模式：%s"}。
     */
    private static final String GT_MACHINE_MODE_LINE_KEY = "gtceu.gui.machinemode";

    /**
     * 把 GTCEu 画的「当前机器模式：组装机」那一行<b>就地换成</b>「当前机器模式：工作台合成（AE 合成样板）」。
     *
     * <h2>为什么这台必须换，而别的 29 台一个字节都不动</h2>
     * 上游那一行只认 {@code getRecipeType()}（= 本机内部那条 {@code ASSEMBLER_RECIPES}）⇒ 画出来是
     * 「组装机」。而这台机器真正干的是 <b>AE 合成样板（工作台合成）</b> ⇒ 用户 2026-10-31 实机原话：
     * 「把这个组装机改了，改成工作台，不然有歧义」。
     * <p>🔴 与 tooltip 那一处<b>同一个显示名</b>（{@code MachineTooltips.KEY_AE_CRAFTING}），
     * 且<b>内部 recipeTypes 一个字都不动</b>（那是防 {@code getRecipeTypes()} 空数组越界的真类型，P1 结论）。
     * <p>做法与 {@code PrimordialModuleMachine#shanhai$replaceUpstreamMachineModeLine} 同款：
     * <b>按翻译键找行、整行替换</b>（不按行号），并且样式照上游那行（AQUA）。
     * 找不到（未成型 ⇒ 上游本来就没画）⇒ 什么都不做。
     */
    private static void shanhai$replaceMachineModeLine(@NotNull List<Component> textList) {
        for (int i = 0; i < textList.size(); i++) {
            if (isTranslatableKey(textList.get(i), GT_MACHINE_MODE_LINE_KEY)) {
                textList.set(i, Component.translatable(GT_MACHINE_MODE_LINE_KEY,
                                Component.translatable(com.shanhai.machine.MachineTooltips.KEY_AE_CRAFTING))
                        .withStyle(net.minecraft.ChatFormatting.AQUA));
            }
        }
    }

    /**
     * 把 GTCEu 画的「最大配方等级：…」那一行就地删掉（<b>只对本类生效</b>，另外 29 台模块一个字节都不动）。
     *
     * <p>时机：{@code super.addDisplayText(textList)} 把上游 + 本模块基类的行全部画完之后再删，
     * 所以基类里那些「按翻译键插到某行后面」的逻辑（例如 {@code addEnergyTierDisplayText} 插在
     * {@code gtceu.multiblock.max_energy_per_tick} 之后）<b>不受影响</b>。
     * <p>倒序删：删一行会让后面所有下标前移，正序删会漏删/错删。
     */
    @Override
    public void addDisplayText(@NotNull List<Component> textList) {
        super.addDisplayText(textList);
        // P8：把上游「当前机器模式：组装机」换成「工作台合成（AE 合成样板）」（只改显示，按翻译键找行）。
        shanhai$replaceMachineModeLine(textList);
        for (int i = textList.size() - 1; i >= 0; i--) {
            if (isTranslatableKey(textList.get(i), GT_MAX_RECIPE_TIER_LINE_KEY)) {
                textList.remove(i);
            }
        }
    }

    /** 这条文本是不是 {@code key} 对应的翻译文本（只认最外层 {@code TranslatableContents}，够用且不误伤）。 */
    private static boolean isTranslatableKey(@Nullable Component component, @NotNull String key) {
        return component != null && component.getContents() instanceof TranslatableContents contents
                && key.equals(contents.getKey());
    }

    /**
     * <b>「并行上限」一行的文本</b>（界面用；<b>每帧现读</b>，不缓存、不写死）。
     *
     * <p>取自 {@link #extraParallelFor()} ⇒ 它就是执行侧真正用的那个数
     * （{@link #getCreationParallelCap()} 读的是同一个方法），不是界面自己另编的一份。
     *
     * <p>格式（用户 2026 拍板）：{@code 并行上限：8^Lv.<等级> = <数值>}：
     * <ul>
     *   <li>等级 = {@link #getEffectiveMatterModuleLevel()}（自己槽 ∪ 主机复写下限，与判定同源）；
     *       无物质模块 ⇒ 0 ⇒ {@code 8^0 = 1}；</li>
     *   <li>等级 ≥ {@value #INFINITE_PARALLEL_FROM_LEVEL} ⇒ 画「<b>无限</b>」两个字
     *       （哨兵 {@link #INFINITE_PARALLEL_SENTINEL} = {@link Long#MIN_VALUE} <b>绝不</b>直接印成大数/负数）；</li>
     *   <li>其余按千分位（沿用本工程 {@code formatParallel} 的同一口径）。</li>
     * </ul>
     */
    public String parallelDisplayLine() {
        return parallelLineFor(getEffectiveMatterModuleLevel(), extraParallelFor());
    }

    /**
     * 「并行上限」一行的<b>纯格式化</b>（界面 / Jade 共用这一份 ⇒ 不可能出现"界面说 8^Lv.3、抬头说别的"）。
     *
     * <p>格式：{@code 并行上限：8^Lv.<等级> = <数值>}；等级 ≥ {@value #INFINITE_PARALLEL_FROM_LEVEL}
     * ⇒ 画「<b>无限</b>」两个字（哨兵绝不直接印成大数/负数）。
     */
    public static String parallelLineFor(int matterLevel, long parallelCap) {
        // P5：文案唯一实现在 CreationDisplayLines（纯类 ⇒ 可离线断言"绝不印哨兵"）
        return CreationDisplayLines.parallelLine(matterLevel, parallelCap);
    }

    // ═════════════════════════ P5：Jade / 抬头的三行真值（替换 GT 那两行 64/1） ═════════════════════════

    /**
     * <b>抬头上要显示的三行</b>（纯格式化，<b>零</b>机器状态依赖 ⇒ 可离线自证）。
     *
     * <h2>🔴 为什么必须替换掉上游那两行</h2>
     * 本类继承 {@link PrimordialModuleMachine} ⇒ {@code ShanhaiInfiniteThreadDisplayMixin} 会按"模块"给它
     * 往 Jade 的 NBT 里写 {@code parallel = 模块并行槽值}（空槽 = 64）与 {@code threads = 1}
     * ⇒ gtladditions 的 {@code ParallelProviderMixin.appendTooltip} 画出
     * 「同时处理至多 64 个配方」「拥有 1 个跨配方线程」。
     * <b>这两个数与本机口径毫无关系</b>（本机接的是 AE 合成单，不接 GT 配方，也没有跨配方线程）
     * ⇒ 必须<b>整段替换</b>成下面的三行（本工程踩过"叠加显示自相矛盾"的坑，不许两套并存）。
     *
     * @param matterLevel        有效物质模块等级（{@code 8^等级} 的那个等级）
     * @param parallelCap        并行上限（可能是哨兵 ⇒ 画「无限」）
     * @param queuedOrders       队列里的单数
     * @param pendingCrafts      队列里还欠的份数
     * @param cycleProgress      本周期已过 tick 数（{@code 0..durationTicks-1}；<b>队列空时不显示它</b>）
     * @param durationTicks      一单固定多少 tick（{@link #TICK_DURATION}）
     * @param ticksUntilNextDue  最快那一单还要几 tick 到期（没有积压 ⇒ 0；<b>队列空时不显示它</b>，
     *                           显示层画「最快到期 —（空闲）」，见 {@code CreationDisplayLines#queueLine}）
     * @return 恒 3 行（顺序固定：并行上限 → 每单 10 tick → 队列/进度）
     */
    public static java.util.List<String> creationDisplayLines(int matterLevel, long parallelCap,
                                                             int queuedOrders, long pendingCrafts,
                                                             int cycleProgress, int durationTicks,
                                                             long ticksUntilNextDue) {
        // P5：三行的唯一实现在 CreationDisplayLines（纯类 ⇒ 可离线断言）
        return CreationDisplayLines.threeLines(matterLevel, parallelCap, queuedOrders, pendingCrafts,
                cycleProgress, durationTicks, ticksUntilNextDue);
    }

    /**
     * 服务端算这三行（数字全部是本机的真值：等级/并行/队列/进度都是活读）。
     *
     * <p>⚠️ 只能在<b>服务端</b>调：{@link CreationCraftExecutor} 的队列与进度是运行期状态，
     * 客户端那份恒为空 ⇒ 客户端调用会得到"队列 0"的假数据（本工程红线）。
     * Jade 的服务端数据钩子（{@code ParallelProvider.appendServerData}）正好在服务端跑，所以从那里调。
     */
    public java.util.List<String> creationDisplayLines() {
        return creationDisplayLines(
                getEffectiveMatterModuleLevel(),
                extraParallelFor(),
                craftExecutor.getQueuedOrderCount(),
                craftExecutor.getPendingCrafts(),
                craftExecutor.getCycleProgress(),
                TICK_DURATION,
                craftExecutor.getTicksUntilNextDue());
    }

    // ═════════════════════════════ 界面：控制器侧栏的「样板」子页签 ═════════════════════════════

    /**
     * 在父类已有的侧栏（主页面 + 额外挂载 + 机器模式）之后<b>追加</b>「样板」子页签。
     *
     * <p>为什么是 {@code super.attachSideTabs(tabs)} 之后再 {@code attachSubTab}：父类那一段
     * 已经处理了「机器模式子页该不该摘」等判据（见 {@code PrimordialModuleMachine#attachSideTabs}），
     * 这里<b>一个字都不复刻</b>，只往后加一页 ⇒ 另外 29 台模块的侧栏<b>一个像素都没变</b>
     * （它们不是本类，走不到这里）。
     * <p>⚠️ 本方法与父类同款：GUI 两侧都会建（客户端那一次才是玩家看到的），
     * 所以页签里<b>不许</b>读只有服务端才有的字段（比如 {@code getHost()}）。
     */
    @Override
    public void attachSideTabs(TabsWidget tabs) {
        super.attachSideTabs(tabs);
        tabs.attachSubTab(new PatternPageProvider());
        // 「神锻」子页签：P10 起是【纯读数】页 —— 本机倍率 / 报数与出货同源 / 主机增产怎么来的 + 并行上限。
        // 已经没有开关控件，也没有倍率输入框（用户原话：「干脆去掉开关，永远吃主机增产」）。
        // 🔴 页内读数走 aeMultiplierForDisplay()：客户端读服务端推下来的镜像，不在本地现算 getHost()。
        tabs.attachSubTab(new CreationForgePatternPanel(this));
    }

    /**
     * 「样板」子页签：10 列 × 5 行 = 50 格/页，共 20 页 = 1000 格。
     *
     * <h2>翻页怎么写（照 gtlcore {@code PaginationUIManager} 的<b>语义</b>，但不是它的类）</h2>
     * 本工程硬约束：<b>不许 import {@code org.gtlcore} / {@code com.gtladd}</b> ⇒ 那个现成的分页器
     * 用不了（见交付报告「与任务书的偏离」）。这里的做法与它<b>逐条同形</b>：
     * <ol>
     *   <li>页号是<b>本控件实例</b>的一个 int（不落盘、重开界面回第 1 页 —— 与上游行为一致）；</li>
     *   <li>翻页 = {@code clearAllWidgets()} 之后按新页号<b>重建那 50 个 SlotWidget</b>；</li>
     *   <li>重建在<b>两侧都会发生</b>：LDLib 的 {@code ButtonWidget#mouseClicked} 在客户端本地跑一次
     *       {@code onPressCallback}，再 {@code writeClientAction} 让服务端 {@code handleClientAction}
     *       也跑一次（{@code javap} 实证）⇒ 客户端与服务端的 50 个槽<b>始终是同一批真实槽号</b>，
     *       不会出现「点的是第 5 页、服务端以为是第 1 页」。</li>
     * </ol>
     * <p>几何全部写死（10 列 × 5 行 × 18 px）：x = 6 + col*18（6…168），y = 14 + row*18（14…104），
     * 翻页按钮在 y = 106，页高 125 以内。
     */
    private final class PatternPageProvider implements IFancyUIProvider {

        /** 当前页号（0 基）。同上游：控件实例状态，不落盘。 */
        private int pageIndex;

        /** 50 个槽所在的子组；翻页时只重建它。 */
        @Nullable
        private WidgetGroup slotGroup;

        /** 页码标签（翻页时立刻 {@code setText} 一次；文本本身仍由 Supplier 每 tick 现算 —— 双保险）。 */
        @Nullable
        private LabelWidget pageLabel;

        @Override
        public Component getTitle() {
            return Component.literal("§b样板");
        }

        @Override
        public IGuiTexture getTabIcon() {
            // AE 的处理样板图标：一眼能认出"这页是样板"（与服务端/客户端无关，纯静态图标）。
            // ⚠️ 用 AE2 自己声明的 {@code stack()}（本仓库里的 AE2 jar 是 SRG 名，{@code asItem()} 不在其中）。
            return new ItemStackTexture(AEItems.PROCESSING_PATTERN.stack());
        }

        @Override
        public Widget createMainPage(FancyMachineUIWidget widget) {
            final WidgetGroup group = new WidgetGroup(0, 0, 190, 125);
            group.setBackground(GuiTextures.BACKGROUND_INVERSE);
            group.addWidget(new LabelWidget(6, 2, () -> "样板（只收 AE 编码样板）"));
            // 拒收计数：放错东西时的可见反馈（数字来自服务端，见 shanhai$noteRejectedPattern）。
            group.addWidget(new LabelWidget(120, 2, () -> "§c拒收 " + patternRejectedCount + " 件"));

            this.slotGroup = new WidgetGroup(0, 14, 190, PATTERN_ROWS * 18);
            group.addWidget(this.slotGroup);
            rebuildSlots();

            // 上一页 / 页码 / 下一页 —— 三个控件的位置固定，不随页号移动（否则鼠标点着会"跑"）。
            group.addWidget(new ButtonWidget(6, 106, 30, 12,
                    new GuiTextureGroup(GuiTextures.BUTTON, new TextTexture("<<")),
                    clickData -> setPageIndex(pageIndex - 1)));
            // 页码：Component 负责<b>画</b>、Supplier 负责<b>非 null</b>（双重写法，刷新见 refreshPageLabel）。
            // 🔴 P9（2026-10-31 定稿）—— 前两版各错一半，两个原因都用 javap 落在字段上了：
            //   ① `(int,int,Component)` 构造器**只** `putfield component`，全函数没有一处写
            //      `textSupplier` ⇒ 该字段留 null ⇒ 服务端 `detectAndSendChanges` 里那句
            //      **无条件**的 `textSupplier.get()` 空指针 ⇒ 翻页崩服（crash 2026-10-31_18.28.15）。
            //      但它的**绘制是对的**（`component != null` ⇒ `drawString(Component)`，实机确认页码看得见）。
            //   ② `(int,int,Supplier<String>)` 构造器只 `putfield textSupplier`，`component` 恒为 null
            //      ⇒ `drawInBackground` 走 `component == null` 那条分支，字先过
            //      `LocalizationUtils.format(lastTextValue)`（把 label 的字当**翻译键**查表）
            //      ⇒ 实机画出 `color` 五个字母。而 dump 证明控件文本本身是对的 `1 / 20`
            //      ⇒ 错的是**绘制分支**，不是文本、不是翻译键。
            //   ⇒ 结论：**两个字段都留非 null**。Supplier 构造器起头（textSupplier 非 null ⇒ 服务端不崩），
            //     随后 setComponent（component 非 null ⇒ 绘制走 Component 分支、不过 I18n）。
            //   ⚠️ 顺序依据（javap 实证，非推测）：`setText(String)` 只 `putfield textSupplier`，
            //     **不碰 component、也没有 aconst_null**；`setComponent(Component)` 只 `putfield component`、
            //     不碰 textSupplier。⇒ 两者互不破坏；仍固定 **setText → setComponent**，让"真正被画的那一份"最后落定。
            //   ⚠️ `setComponent` 的返回是 `void`（不是 LabelWidget）⇒ 不能链式，必须单独一行。
            this.pageLabel = new LabelWidget(68, 108, this::pageLabelText);
            this.pageLabel.setComponent(Component.literal(pageLabelText()));
            if (CreationWidgetDump.subtractMode()) {
                // 🔴 P5 减法实验：本页"底部区域"我们自己画的那一行不在这次实机里添加
                //    （只在 SHANHAI_WIDGET_DUMP=1 时发生；默认外观不变）。
                CreationWidgetDump.logSubtracted("样板页 y=108（页码）", pageLabelText());
            } else {
                group.addWidget(this.pageLabel);
            }
            group.addWidget(new ButtonWidget(154, 106, 30, 12,
                    new GuiTextureGroup(GuiTextures.BUTTON, new TextTexture(">>")),
                    clickData -> setPageIndex(pageIndex + 1)));
            // 🔴 诊断开关（默认关闭）：把整棵界面树的 类名 + 文本 逐行打出来。
            //    ⚠️ P8 修正：**必须先 dump 本页自己的 group** —— 旧版 dump 的是 `widget`（FancyMachineUIWidget），
            //    而 `createMainPage` 返回的 group 这时**还没被挂进 pageContainer**（LDLib 是"先造页面、后 addWidget"），
            //    所以旧 dump 里永远看不到本页的 LabelWidget（实机 147 行 dump 里一个都没有，就是这个原因），
            //    等于没有证据。现在两个都 dump：先本页 group（能看到 LabelWidget 的真文本），再整屏。
            CreationWidgetDump.dumpTree("pattern-page-group", group);
            CreationWidgetDump.dumpTree("pattern-page", widget);
            return group;
        }

        /** 页码文本：<b>纯 ASCII {@code "N / 20"}</b>（N 从 1 起，页数取自 {@link #PATTERN_PAGE_COUNT}，<b>不写死</b>）。 */
        private String pageLabelText() {
            // 🔴 P8：**故意**去掉 `§b` 前缀、改成纯 ASCII —— 旧文本「§b第 1 / 20 页」在实机上
            //    画出来是 `color` 五个字母（见 P8 报告）。这一版先把"页码能不能画出来"钉死：
            //    纯 ASCII 不经过任何 I18n / 颜色码解析，看得见就是看得见。样式下一轮再加。
            return (pageIndex + 1) + " / " + PATTERN_PAGE_COUNT;
        }

        /**
         * <b>翻页的唯一入口</b>：先把页号夹进 [0, PATTERN_PAGE_COUNT-1]，再重建 50 格，再立刻刷新页码。
         *
         * <p>为什么用 {@code Math.max/min} 而不是 {@code if (pageIndex > 0)}：客户端与服务端各有一份
         * {@code pageIndex}，万一某一侧多翻一次（双击等），下一次点击会把它<b>拉回界内</b>，
         * 而不是停在界外让 {@code start = pageIndex * 50} 落到 1000 格以外（那会画出不存在的槽）。
         */
        private void setPageIndex(int next) {
            final int clamped = Math.max(0, Math.min(PATTERN_PAGE_COUNT - 1, next));
            if (clamped == pageIndex) {
                return;
            }
            pageIndex = clamped;
            rebuildSlots();
            refreshPageLabel();
        }

        /**
         * 刷新页码 —— <b>两条都必须写</b>（P9 双写法），缺一条就是前两版各自的崩法/画法：
         * <ul>
         *   <li>{@code setText(txt)}：把 {@code textSupplier} 换成一个非 null 的常量 lambda
         *       ⇒ 服务端 {@code detectAndSendChanges} 里无条件的 {@code textSupplier.get()} 不会空指针；</li>
         *   <li>{@code setComponent(literal(txt))}：{@code drawInBackground} 在 {@code component != null} 时
         *       走 {@code drawString(Component)} 分支，<b>这一份才是被画出来的字</b>。</li>
         * </ul>
         * 只写 {@code setText}：不崩，但被画的 {@code component} 还是构造时那一页 ⇒ 页号看着不更新；
         * 只写 {@code setComponent}：画得对，但 supplier 侧的文本与 {@code component} 就不再保证同源同步
         * （本版 supplier 是 `this::pageLabelText` 活引用，恰好还能对上；一旦有人把它改成常量就会错位）。
         * ⇒ 固定两条都写、且 {@code setText} 在前 {@code setComponent} 在后。
         * <p>⚠️ 字节码事实（别推翻）：这两个 setter 都**不会**把对方字段或自己写成 null
         * （{@code setText} 只 `putfield textSupplier`，{@code setComponent} 只 `putfield component`），
         * 所以顺序不影响 null 安全，只影响"最后一次落定的是哪一份"。
         */
        private void refreshPageLabel() {
            if (this.pageLabel == null) {
                return;
            }
            final String txt = pageLabelText();
            this.pageLabel.setText(txt);
            this.pageLabel.setComponent(Component.literal(txt));
        }

        /** 重建当前页的 50 个槽（槽号 = pageIndex * 50 + i，所以<b>每一页都是同一份 1000 格的不同窗口</b>）。 */
        private void rebuildSlots() {
            if (this.slotGroup == null) {
                return;
            }
            this.slotGroup.clearAllWidgets();
            final int start = pageIndex * PATTERN_SLOTS_PER_PAGE;
            for (int i = 0; i < PATTERN_SLOTS_PER_PAGE; i++) {
                final int col = i % PATTERN_COLUMNS;
                final int row = i / PATTERN_COLUMNS;
                final SlotWidget slot = new SlotWidget(patternInventory, start + i,
                        6 + col * 18, row * 18, true, true);
                slot.setBackground(SlotWidget.ITEM_SLOT_TEXTURE);
                // 槽号直接显示在 tooltip 里：放错东西时玩家能报出"第几个槽"（排查用，不占格子）。
                final int slotNumber = start + i;
                slot.setOnAddedTooltips((w, tooltips) -> tooltips.add(
                        Component.literal("§7样板槽 #" + (slotNumber + 1) + "§r（只收 AE 编码样板）")));
                this.slotGroup.addWidget(slot);
            }
        }
    }
}
