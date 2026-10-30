package com.shanhai.machine.host;

import com.google.common.primitives.Ints;
import com.gregtechceu.gtceu.api.capability.IEnergyContainer;
import com.gregtechceu.gtceu.api.capability.recipe.EURecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.capability.recipe.IRecipeHandler;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.gui.fancy.ConfiguratorPanel;
import com.gregtechceu.gtceu.api.gui.fancy.FancyMachineUIWidget;
import com.gregtechceu.gtceu.api.gui.fancy.IFancyUIProvider;
import com.gregtechceu.gtceu.api.gui.fancy.TabsWidget;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.fancyconfigurator.CombinedDirectionalFancyConfigurator;
import com.gregtechceu.gtceu.api.machine.multiblock.MultiblockControllerMachine;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableItemStackHandler;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.logic.OCParams;
import com.gregtechceu.gtceu.api.recipe.logic.OCResult;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.texture.ItemStackTexture;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.SlotWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedKey;
import com.mojang.logging.LogUtils;
import com.shanhai.common.machine.PrimordialOmegaEngineMachine;
import com.shanhai.common.recipe.PrimordialRecipeEffects;
import com.shanhai.machine.module.PrimordialModuleMachine;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import org.gtlcore.gtlcore.api.machine.multiblock.IModularMachineModule;
import org.gtlcore.gtlcore.api.recipe.RecipeMultiplierTracker;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.List;
import java.util.Locale;

/**
 * 归墟视界枢核（{@code shanhai:guixu_vision_core}）· 主机机器类。
 *
 * <h2>🔴 为什么 {@code extends PrimordialOmegaEngineMachine}（这不是偷懒）</h2>
 * gtlcore 的模块协议是 <b>F-bounded 自引用泛型</b>：
 * <pre>
 *   IModularMachineHost&lt;H extends IModularMachineHost&lt;H&gt;&gt;
 *   IModularMachineModule&lt;H extends IModularMachineHost&lt;H&gt;, M extends IModularMachineModule&lt;H, M&gt;&gt;
 * </pre>
 * 而现有的 24+ 台模块全部是 {@code PrimordialModuleMachine implements
 * IModularMachineModule&lt;PrimordialOmegaEngineMachine, PrimordialModuleMachine&gt;}，
 * 它们的 {@code getHostType()} 返回 {@code PrimordialOmegaEngineMachine.class}，
 * gtlcore 的 {@code IModularMachineHost#isValidModule} 判据是
 * {@code module.getHostType().isInstance(host)}。
 * <p>⇒ 一台<b>独立</b>的新主机类永远 {@code isInstance} 为 false，现有模块<b>一台都连不上</b>
 * （"插一个模块能回连"这条验收直接不可能）。若反过来把 {@code host} 强塞给模块（绕过类型检查），
 * 运行期 {@code getHost()} 会 <b>ClassCastException</b>。
 * <p>所以本类<b>继承</b>老主机类：它就是一个 {@code PrimordialOmegaEngineMachine}
 * ⇒ 模块侧零改动、零 CCE。我们只覆写少数几个点（24 槽 / 24 上限 / 显示文案 /
 * 配方修饰链 / 渲染活性），回连协议完全沿用基类与 gtlcore 的 default 实现。
 *
 * <h2>第二轮（2026-10-23）在本类里落地的四件事</h2>
 * <ol>
 *   <li><b>能量双仓</b>：图案在 'A'（外环桁架带 / 站体机壳）上<b>同时</b>收能源仓
 *       （{@code PartAbility.INPUT_ENERGY}）与动力仓（{@code PartAbility.OUTPUT_ENERGY}），见
 *       {@link GuixuVisionCoreStructure}；抬头/Jade 把两边的存量<b>分两行</b>画出来
 *       （{@link #addDisplayText}）。机器等级不卡：沿用基类 {@code getMaxVoltage() = Long.MAX_VALUE}。</li>
 *   <li><b>材料增产（沿用那套 + 5）</b>：N3 倍率仍由「主机物质模块槽」门控（等级 0 ⇒ 不放大），
 *       生效时倍率 = {@code (1 + 等级) + 5}；<b>耗能减免</b>与主机同款（同一个
 *       {@code reductionFactor(等级)}）。整条链在 {@link #applyGuixuRecipeModifier}。</li>
 *   <li><b>始终渲染为工作状态</b>：基类已有那个开关（{@code isStarAlwaysWorking()}，侧栏按钮），
 *       但它原本只接在老主机的 TESR 上；本机覆写 {@link #isActive()} 把同一个开关接到
 *       "机壳/贴图是否工作状态"这条链上 ⇒ 对本机也生效。</li>
 *   <li><b>两个按钮</b>：<b>最短配方耗时</b>（gtladditions 的 {@code LimitedDurationConfigurator}）与
 *       <b>始终渲染为工作状态</b>（基类的 toggle）都由基类 {@code attachConfigurators} 挂上 ⇒ 继承；
 *       <b>批处理按钮</b>由基类 {@code canConfigureBatchProcessing() = false} 关掉 ⇒ 不出现。</li>
 * </ol>
 *
 * <h2>扫描时机：只在成型时扫一次</h2>
 * {@link #onStructureFormed()} 先调 {@code super}（基类做"调和式重扫"），再打<b>一行</b>
 * {@code [SHANHAI-HOST]} 日志。<b>不挂任何周期扫描</b>。
 */
public class GuixuVisionCoreMachine extends PrimordialOmegaEngineMachine
        implements com.shanhai.common.heat.ShanhaiHostMountSource {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 材料增产的<b>额外</b>倍率：在原始终焉引擎那套（{@code 1 + 物质模块槽等级}）之上再加 5。
     *
     * <p>只加在"门控已生效"（等级 ≥ 1）的情况：门控未生效时倍率仍是 1
     * （空槽主机白得 6 倍产出不是"沿用那套 + 5"）。若以后要改成"永远 +5"，
     * 只需把 {@link #productionMultiplier()} 与 {@link #applyGuixuRecipeModifier} 里那个
     * {@code base > 1 ? … : 1} 的三元去掉。
     */
    public static final int PRODUCTION_BONUS_EXTRA = 5;

    /**
     * 🔴 <b>本类自己的 {@link ManagedFieldHolder}</b>（2026-10-23 新增字段 ⇒ 必须有它）。
     *
     * <p>LDLib 的 holder 按<b>类</b>反射登记 {@code @Persisted}/{@code @DescSynced} 字段，
     * 父类的 holder 里没有本类的字段；漏掉这个覆写的表现是
     * <b>编译通过、游戏不报错、字段永不落盘也永不同步</b>（玩家在专属槽里放满 64 个模块，
     * 关掉 GUI 再打开就"回到空"）—— 本工程最忌讳的"静默失败"。
     *
     * <p>第二参取<b>父类的 holder</b>（{@code PrimordialOmegaEngineMachine.managedFieldHolder()}，
     * 那是本类新加的只读出口）：<b>不许跳级</b>写 {@code WorkableElectricMultiblockMachine.MANAGED_FIELD_HOLDER}，
     * 否则父类那一整段的字段（物质模块槽 / 最短耗时 / 球体风格…）会从本类的链上消失。
     */
    private static final ManagedFieldHolder MANAGED_FIELD_HOLDER = new ManagedFieldHolder(
            GuixuVisionCoreMachine.class, PrimordialOmegaEngineMachine.managedFieldHolder());

    @Override
    public ManagedFieldHolder getFieldHolder() {
        return MANAGED_FIELD_HOLDER;
    }

    /**
     * 「模块等级复写」专属槽（1 格，只收物质模块）。
     *
     * <p>用户规格（原话）：「<b>物质模块槽下面再加一格，放满 64 个物质模块生效 —— 用它复写安装在身上的
     * 模块的物质模块等级</b>」 ⇒ 门控 {@link #matterLevelOverride()}：
     * <b>必须 64 个同种物质模块</b>才返回 1..17，否则 0（未生效）。
     *
     * <p>与基类的「物质模块专属槽」（管 N3 增产 / N5 减免门控）是<b>两个独立槽</b>：
     * 那个管"主机自己的增产"，这个管"模块的等级复写"，互不影响。
     */
    @Persisted
    private final NotifiableItemStackHandler levelOverrideSlot;

    /**
     * {@link #matterLevelOverride()} 的<b>客户端镜像</b>（渲染/抬头在客户端，槽里的物品到不了那边）。
     *
     * <p>与基类 {@code matterBonusLevel} 同一个套路：值真变了才 {@code notifyBlockUpdate()}，
     * 不无脑同步（那会变成每 tick 一个包）。
     */
    @DescSynced
    private int hostMatterLevelOverride = 0;

    /**
     * <b>服务端认定的"已成型"位</b>（2026-10-24 新增）：{@code @DescSynced} ⇒ 客户端也读得到。
     *
     * <h2>为什么在 {@code controller.isFormed()} 之外还要这一位</h2>
     * 客户端渲染器原本只读 {@code IMultiController#isFormed()}。那一份是 GTCEu 自己的同步位，
     * 理论上够用；但 2026-10-24 实机排查「特效一点没看见」时<b>无法排除</b>"客户端成型状态没跟上"这一档
     * （当时渲染路径还没有日志，见 {@code GuixuHostFxRenderer} 的类注释）。
     * 多一位自己维护、在 {@code onStructureFormed()} / {@code onStructureInvalid()} 里显式写的同步位，
     * 渲染器就能判 {@code isFormed() || formedSync}，并把<b>两个值都</b>打进 {@code draw}/{@code gate} 日志
     * ⇒ 下一轮再有异常，一行日志就能定位是哪一边没跟上。
     *
     * <p>默认 {@code false}；值变化才 {@code notifyBlockUpdate()}（无脑同步 = 每 tick 一个包）。
     */
    @DescSynced
    private boolean hostFormedSync = false;

    /** 给客户端渲染器读的"服务端成型位"（{@code @DescSynced} 镜像）。 */
    public boolean isHostFormedSync() {
        return hostFormedSync;
    }

    /** 只在值真的变化时写 + 推同步。 */
    private void setHostFormedSync(boolean value) {
        if (hostFormedSync == value) {
            return;
        }
        hostFormedSync = value;
        if (!isRemote()) {
            notifyBlockUpdate();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════════
    //  视界参数（2026-10-25 新增：玩家在主机面板里调的那 6 项）
    // ═══════════════════════════════════════════════════════════════════════════════════

    /*
     * 用户原话（本轮，逐字）：
     *   ① 「这个角速度干脆让玩家可以在主机控制器里面调得了，可以修改那个没用的调整中子星的大小
     *       与颜色的按钮」
     *   ② 「中心球体渲染风格，你直接改成可以修改黑洞的半径和是否开启引力透镜效果得了」
     *   ③ 「始终渲染为工作状态的按钮你也用起来，直接做成开关特效渲染」
     *
     * 🔴 为什么这 6 个字段必须【同时】是 @Persisted 与 @DescSynced（与基类 sphereStyle 同款两件套）：
     *   · @Persisted ⇒ 拆装/存档重载后玩家调的值还在（否则"调完退出再进就回默认"）；
     *   · @DescSynced ⇒ 渲染在客户端（TESR），没有它客户端读不到玩家调的开关；
     *   · 写入必须走"值真变了才 notifyBlockUpdate()"（无脑同步 = 每 tick 一个包）。
     *   样板：本类 setHostFormedSync / refreshMatterLevelOverride，基类 starAlwaysWorking 的 javadoc。
     *
     * ⚠️ 新字段名必须同时加进 assertFieldHolderComplete() 的 required 数组 ——
     *    LDLib 的字段登记失败是【完全静默】的（编译通过、游戏不报错、字段永不落盘也永不同步），
     *    那个自检是唯一能在没有玩家操作的情况下抓住它的地方。
     */

    /** 内环角速度下限（°/s）—— 玩家看到的整数。 */
    public static final int HOST_RING_DEG_MIN = 1;
    /** 内环角速度上限（°/s）。 */
    public static final int HOST_RING_DEG_MAX = 60;
    /** 内环角速度默认值（°/s）= 改造前 {@code HostFxGeometry.RING_DEG_PER_SEC}。 */
    public static final int HOST_RING_DEG_DEFAULT = 12;
    /** 船舱角速度下限（°/s）：允许 0（那一组不转）。 */
    public static final int HOST_SHIP_DEG_MIN = 0;
    /** 船舱角速度上限（°/s）。 */
    public static final int HOST_SHIP_DEG_MAX = 60;
    /** 上 4 舰角速度默认值（°/s）。 */
    public static final int HOST_SHIP_UP_DEG_DEFAULT = 9;
    /** 下 4 舰角速度默认值（°/s，<b>正数</b>；内部/渲染侧取负号，玩家看到的始终是正数）。 */
    public static final int HOST_SHIP_DOWN_DEG_DEFAULT = 9;
    /** 黑洞半径下限（×0.1 格）= 1.0 格。 */
    public static final int HOST_HOLE_RADIUS_TENTHS_MIN = 10;
    /**
     * 黑洞半径上限（×0.1 格）= <b>12.0 格</b>。
     *
     * <p>🔴 <b>上限被渲染侧的现有 clamp 钉死在 120</b>：{@code HostFxBlackHole.surfaceRadius()}
     * （{@code HostFxBlackHole.java:181}）里是 {@code Math.max(0.8F, Math.min(r, 12.0F))}
     * ⇒ 玩家填 121..200 在画面上<b>不会有任何变化</b>（"能填但看不见效果"= 假控件，本工程红线）。
     * 所以范围先对齐到 10..120。<b>未来</b>渲染侧把那处 clamp 放宽（或改成读本字段）之后，
     * 再把这里改成 200 —— 只改这一个常量 + `HostFxState.HOLE_MAX_BLOCKS`（两处必须同步）。
     */
    public static final int HOST_HOLE_RADIUS_TENTHS_MAX = 120;
    /**
     * 黑洞半径默认值（×0.1 格）= <b>52</b>（= 5.2 格）。
     *
     * <p>这不是拍的：现行生效半径 = {@code B_CRIT 2.598076 × RS_BLOCKS_DEFAULT 2.0}
     * = <b>5.196 格</b>（{@code BlackHoleLensMath.B_CRIT} / {@code BlackHoleLensSpec.RS_BLOCKS_DEFAULT}，
     * 也就是 {@code HostFxGeometry.HOLE_SURFACE_R = 5.196F}）⇒ 按 ×0.1 格四舍五入就是 <b>52</b>。
     * <b>默认值必须等于改造前的实际半径</b>，否则"装上这个 mod 什么都不点"画面就跟以前不一样了。
     */
    public static final int HOST_HOLE_RADIUS_TENTHS_DEFAULT = 52;

    /**
     * <b>特效总开关</b>（面板第 ⑦ 个 tab「特效渲染」）。
     *
     * <p>🔴 与基类的 {@code starAlwaysWorking} 是<b>两个字段</b>，刻意不合并：
     * 那个字段被 {@code GuixuVisionCoreMachine#isActive()} 用来驱动<b>机壳/贴图的工作态外观</b>，
     * 改成"特效开关"会让机壳跟着变待机外观（用户没要求动这个）。
     * ⇒ 本字段只管特效；{@code starAlwaysWorking} 保留原义（且<b>不再有 UI</b>，见交付报告）。
     */
    @Persisted
    @DescSynced
    private boolean hostFxEnabled = true;

    /** 内环带角速度（°/s，1..60，默认 12）。 */
    @Persisted
    @DescSynced
    private int hostRingDegPerSec = HOST_RING_DEG_DEFAULT;

    /** 上 4 舰角速度（°/s，0..60，默认 9）。 */
    @Persisted
    @DescSynced
    private int hostShipUpDegPerSec = HOST_SHIP_UP_DEG_DEFAULT;

    /** 下 4 舰角速度（°/s，0..60，默认 9；<b>正数</b>，方向由渲染侧取负）。 */
    @Persisted
    @DescSynced
    private int hostShipDownDegPerSec = HOST_SHIP_DOWN_DEG_DEFAULT;

    /** 黑洞半径（×0.1 格，10..120，默认 52 = 5.2 格；上限受渲染侧 `HostFxBlackHole` 的 12.0 格 clamp 约束）。 */
    @Persisted
    @DescSynced
    private int hostBlackHoleRadiusTenths = HOST_HOLE_RADIUS_TENTHS_DEFAULT;

    /**
     * 引力透镜开关（默认<b>开</b>）：开 = 强制走真透镜（背景弯折）；
     * 关 = 只画黑盘 + 光子环 + 吸积盘，不做背景弯折。
     */
    @Persisted
    @DescSynced
    private boolean hostLensEnabled = true;

    /**
     * <b>玻璃 AR 屏幕背后是否铺一层灰色半透明遮罩</b>（默认<b>开</b>）。
     *
     * <p>用户 2026-10-25 原话（逐字）：「然后再新增一个功能开关（是否在 AR 屏幕背后添加灰色半透明遮罩
     * （这样字可以看得更清楚））」。
     *
     * <p>注解与 {@link #hostFxEnabled} 同款两件套（{@code @Persisted} = 拆装/重载后保持；
     * {@code @DescSynced} = 客户端渲染器读得到）。<b>默认 true</b>：用户的目的就是"字看得更清楚"，
     * 关掉只是"想看清玻璃后面的东西"时的退路 ⇒ 默认值取"他点单想要的那个效果"。
     *
     * <p>渲染侧只经 {@code HostFxState.arMaskEnabled()} 读它（渲染包不认识机器包）。
     */
    @Persisted
    @DescSynced
    private boolean hostArMaskEnabled = true;

    /**
     * <b>已装模块的槽位位图</b>（服务端权威；{@code bit i = 1} ⇒ 第 {@code i} 个扫描位上有成型且
     * 已挂到本机的模块）。
     *
     * <h2>为什么要这一位，而不是让 AR 面板直接调 {@code getFormedModuleCount()}</h2>
     * {@code getFormedModuleCount()} 读的是 {@code getModules()}，而那份集合是<b>两侧各自扫描</b>出来的
     * （{@code scanAndConnectModules} 没有 {@code isRemote} 闸门）——客户端那份能不能建起来，取决于
     * 客户端有没有走到成型回调，<b>不可证</b>。AR 面板要显示"已装 N / 24"与模块名字，
     * 一旦客户端那份是空的，面板就会画出 <b>0 / 24</b> —— 这正是本工程的红线「活的界面上不许放假数据」。
     * ⇒ 由<b>服务端</b>算一次位图、{@code @DescSynced} 过去，客户端只读这一位。
     *
     * <p>为什么不直接同步一个"名字数组"：LDLib 的 syncdata 只登记<b>基本类型与基本类型数组</b>
     * （{@code ManagedField$Long} / {@code ManagedArrayItem$IntArrayItem} 等，javap 列过
     * {@code com/lowdragmc/lowdraglib/syncdata/managed/} 全表，<b>没有</b> {@code String}）⇒
     * 同步 String 属于"编译过、运行期不生效"的高危写法。名字由客户端按<b>权威槽位号</b>去
     * 自己那一侧的世界里查（同一个注册表数据，不是猜）。
     *
     * <p>写者只有 {@link #refreshModuleMask()}（服务端）；值没变就一个字节都不动。
     * 24 个槽位 ⇒ 一个 {@code long} 装得下。
     */
    @DescSynced
    private long hostModuleMask = 0L;

    // ═══════════════════════════════════════════════════════════════════════════════════
    //  「额外挂载槽 ×3」—— 2026-10-25 用户点单（T3「复写」）
    //
    //  用户原话（逐字）：「然后我说的**配方条件（就是模块上面那 3 个槽）复写**你还没做，你也给他做了，
    //                    **接口 gui 就和模块的一样**」
    //
    //  ⇒ 与模块（{@code PrimordialModuleMachine#extraMountSlots}）**同语义、同一套控件与位置**：
    //    · 3 格，{@link com.shanhai.common.heat.ShanhaiHeatGate#SLOT_COUNT}（唯一真源）；
    //    · 每格放满 {@link com.shanhai.common.heat.ShanhaiHeatGate#REQUIRED_COUNT} 个才生效；
    //    · 可满足配方的额外条件（cleanroom / gravity / dimension / research）与热力门槛；
    //    · 判定核 = {@link com.shanhai.common.heat.ShanhaiExtraMountGate}（模块也调它，只有一份实现）。
    //
    //  🔴 必须 {@code @Persisted}（与模块那三个槽同款）：LDLib 的字段登记失败是**完全静默**的
    //  （编译过、游戏不报错、字段永不落盘）⇒ 名字同时加进 {@link #assertFieldHolderComplete()} 的
    //  required 数组，那是唯一能在没有玩家操作时抓住它的地方。
    //
    //  ⚠️ 不设过滤器：与模块同一条纪律（用户 2026-10-03 的负对照要求「放错必须仍然失败」
    //  ⇒ 收窄会让"放错了"这个负对照做不出来，而且中途加过滤器会让老存档里的物品变成取不出来的死格）。
    // ═══════════════════════════════════════════════════════════════════════════════════

    /** 额外挂载槽的格数 —— <b>唯一真源</b>是 {@code ShanhaiHeatGate.SLOT_COUNT}（模块侧同源）。 */
    public static final int EXTRA_MOUNT_SLOT_COUNT = com.shanhai.common.heat.ShanhaiHeatGate.SLOT_COUNT;

    /** 额外挂载槽 ×3（玩家从 GUI 左侧「额外挂载」页放置）。 */
    @Persisted
    private final NotifiableItemStackHandler extraMountSlots;

    /** {@link #getExtraMountContents()} 的逐格缓存（避免每 tick 查注册表）。 */
    private List<com.shanhai.common.heat.ShanhaiHeatGate.SlotContent> extraMountCache = List.of();
    /** 缓存对应的三格快照（逐格 {@code ItemStack} 相等才算命中）。 */
    private ItemStack[] extraMountCacheKeys = new ItemStack[0];

    // ═══════════════════════════════════════════════════════════════════════════════════
    //  AR 右侧信息栏：主机 / 每模块的运行快照（2026-10-25 第二十二改新增）
    //
    //  用户口径（原话）：「右侧空出来这么大块地方都可以显示模块和主机的运行配方的信息，
    //  需要详细显示每个模块的信息（…进度…耗能…并行…跨配方线程…配方输出），
    //  然后主机运行的信息…再加上已经安装了多少个模块、总产出、总耗能、总发电」
    //  并且补了一句：「然后6我说的是 AR 上面的显示啊」⇒ 这些数都是给玻璃 AR 面板读的。
    //
    //  🔴 三条硬约束（写这套字段的理由）：
    //   ① LDLib 1.0.33.b 的 syncdata <b>没有 String 存取器</b>（{@code SyncedFieldAccessors}
    //      javap 列表里只有基本类型 / 基本类型数组 / 少量对象类型）⇒ 名字类数据一律不在这里同步，
    //      由客户端按权威槽位号去自己那一侧的世界查（与 {@link #hostModuleMask} 同一条路）。
    //   ② 数组走 {@code ARRAY_ACCESSOR_FACTORY}（{@code arrayAccessor(IAccessor, Class)} 存在，
    //      javap 实证）⇒ {@code byte[]/int[]/long[]} 是<b>受支持</b>的类型（与"引用类型数组需要
    //      @LazyManaged"那条是两件事）。
    //   ③ <b>读不到必须是 −1，不是 0</b>：客户端拿 −1 画 {@code --}，拿 0 就是假数据（本工程红线）。
    //      {@link #hostSnapSeq} 是"快照到过没有"的凭据：0 ⇒ 客户端一律 {@code --}。
    //
    //  ⚠️ 现状（本轮交付的边界，报告里也写了）：通道 + 面板排版 + 客户端喂料<b>都已经接好</b>，
    //      {@link #refreshHostRuntimeSnapshot()} 目前只填"已装模块数"这一项（它走的是已经验证过的
    //      {@link #hostModuleCountAuthoritative()}）。<b>每模块的进度/耗能/并行/线程、主机级汇总
    //      还没有取值来源</b>：它们要读 gtlcore 的模块配方状态（{@code IModularMachineModule} 的
    //      recipe logic），本轮没有取到可验证的 API 证据 ⇒ 一律留 −1（面板显示 {@code --}），
    //      <b>绝不填 0 冒充</b>。接上时只需要在下面那个方法里逐槽位填数组，别的都不用动。
    // ═══════════════════════════════════════════════════════════════════════════════════

    /** 每个扫描位（0..23）的配方进度百分比；{@code <0} = 未知。 */
    @DescSynced
    private byte[] hostModProgressPct = sentinelBytes(SNAP_SLOTS);
    /** 每个扫描位的当前 {@code EU/t}；{@code <0} = 未知。 */
    @DescSynced
    private long[] hostModEuPerTick = sentinelLongs(SNAP_SLOTS);
    /** 每个扫描位"同时处理几个配方"；{@code <0} = 未知。 */
    @DescSynced
    private int[] hostModParallel = sentinelInts(SNAP_SLOTS);
    /** 每个扫描位"拥有几个跨配方线程"；{@code <0} = 未知。 */
    @DescSynced
    private int[] hostModThreads = sentinelInts(SNAP_SLOTS);
    /** 每个扫描位"还剩几秒"；{@code <0} = 未知。 */
    @DescSynced
    private int[] hostModRemainSec = sentinelInts(SNAP_SLOTS);

    /**
     * 每个扫描位的<b>「这一拍为什么没在跑」原因码</b>（2026-10-25 第三十三改）——
     * 取值见 {@link #REASON_NONE} / {@link #REASON_WORKING} / {@link #REASON_NO_POWER} /
     * {@link #REASON_OUTPUT_FULL} / {@link #REASON_BLOCKED}；{@code <0} = 读不到（客户端不画标签）。
     *
     * <h2>用户口径（原话）</h2>
     * <pre>
     *   「机器"不工作"的四种形态你判断是对的，就按照你说的这4种」
     *   ⇒ ① 缺电 ② 输出满 ③ 被拦（类型闸 / 额外条件） ④ 正在跑（进度在涨）
     *   （"等料"这一档【不要】：GT 里料不够就匹配不到配方，不表现为"在跑但不动"）
     * </pre>
     *
     * <h2>🔴 取数来源（每一条都是已有信号，逐条见 {@link #reasonOf} 的注释）</h2>
     * <ul>
     *   <li><b>在跑</b>：{@code RecipeLogic#isWorking()}；</li>
     *   <li><b>缺电 / 输出满 / 被拦</b>：gtlcore 的 <b>已发布</b>配方状态
     *       {@code IRecipeStatus#getWorkingStatus() / getRecipeStatus()}
     *       （{@code RecipeResult.of(machine, …)} 写入的那一份，Jade / 机器 GUI 读的也是它）——
     *       按 {@code TranslatableContents#getKey()} 分类，键名逐字抄自
     *       {@code RecipeResult} 静态初始化的 {@code ldc}（javap 实证，见 {@link #KEY_EU_IN}）；</li>
     *   <li>未发布原因但确实在 waiting ⇒ 再用<b>能量实读</b>兜一层（{@link #energyShortFor}）；</li>
     *   <li>全部读不到 ⇒ {@link #REASON_NONE}（客户端不画标签，<b>绝不编</b>）。</li>
     * </ul>
     *
     * <p>同步成本：{@code byte[24]}，与其余模块快照数组同一个 10 tick 推送节奏，只在<b>内容变了</b>时才换引用。
     */
    @DescSynced
    private byte[] hostModReason = sentinelBytes(SNAP_SLOTS);

    // ── 原因码（唯一的定义处；客户端 HostFxGlassArModel 有同名副本，两处必须逐值相同）──────
    /** 没在跑（也没查到原因）。 */
    public static final byte REASON_NONE = 0;
    /** 正在跑（进度在涨）。 */
    public static final byte REASON_WORKING = 1;
    /** 缺电（输入电力不足）。 */
    public static final byte REASON_NO_POWER = 2;
    /** 输出满（产出来的东西放不下）。 */
    public static final byte REASON_OUTPUT_FULL = 3;
    /** 被拦（配方条件 / 类型闸 / 额外挂载 / 温度 / 燃料 … 一切非电力、非输出的拦截）。 */
    public static final byte REASON_BLOCKED = 4;

    /**
     * 「输入电力不足」的 gtlcore 原因键 —— <b>逐字抄自字节码</b>：
     * {@code RecipeResult.<clinit>} 偏移 56：{@code ldc "gtceu.recipe.fail.no.enough.eu.in"} ⇒
     * {@code FAIL_NO_ENOUGH_EU_IN}。
     */
    private static final String KEY_EU_IN = "gtceu.recipe.fail.no.enough.eu.in";
    /**
     * 「输出放不下」的 gtlcore 原因键 —— {@code RecipeResult.<clinit>} 偏移 34：
     * {@code ldc "gtceu.recipe.fail.Output"} ⇒ {@code FAIL_OUTPUT}（注意大写 O，不是拼错）。
     */
    private static final String KEY_OUTPUT = "gtceu.recipe.fail.Output";
    /** 快照序号：<b>0 = 一次都没快照过 ⇒ 客户端整片画 {@code --}</b>；每推一次 +1。 */
    @DescSynced
    private int hostSnapSeq = 0;
    /** 主机级：当前配方进度百分比（{@code <0} = 未知）。 */
    @DescSynced
    private int hostRecipeProgressPct = -1;
    /** 主机级：当前配方还剩几秒（{@code <0} = 未知）。 */
    @DescSynced
    private int hostRecipeRemainSec = -1;
    /** 主机级：同时处理几个配方（{@code <0} = 未知）。 */
    @DescSynced
    private int hostRecipeParallel = -1;
    /** 主机级：拥有几个跨配方线程（{@code <0} = 未知）。 */
    @DescSynced
    private int hostRecipeThreads = -1;
    /** 主机级：当前 {@code EU/t}（{@code <0} = 未知）。 */
    @DescSynced
    private long hostEuPerTick = -1L;
    /** 主机级：总产出条目数（所有模块配方输出累加；{@code <0} = 未知）。 */
    @DescSynced
    private long hostTotalOutputCount = -1L;
    /** 主机级：总产出种类数（{@code <0} = 未知）。 */
    @DescSynced
    private int hostTotalOutputKinds = -1;
    /**
     * 主机级：<b>总耗能功率</b>（{@code EU/t}，<b>瞬时速率</b>；{@code <0} = 未知）。
     *
     * <p>🔴 用户 2026-10-25 口径原话：「<b>总耗能/总发电我说的应该是总耗能功率/总发电功率</b>」
     * ⇒ 这里存的是<b>速率</b>，<b>不是</b>"自本机加载以来的累计 EU"。
     * 取数见 {@link #sumEnergyRatePerTick(IO)}（能源仓/动力仓的实测吞吐）。
     */
    @DescSynced
    private long hostTotalEuUsedPerTick = -1L;
    /** 主机级：<b>总发电功率</b>（{@code EU/t}，瞬时速率；{@code <0} = 未知）。 */
    @DescSynced
    private long hostTotalEuGenPerTick = -1L;
    /** 主机级：<b>已安装模块数</b>（服务端权威；{@code <0} = 未知）。 */
    @DescSynced
    private int hostInstalledModules = -1;

    // ── 输出（2026-10-25 第二十三改）：LDLib 的 syncdata **没有** String 存取器 ──────────────
    //
    //  取证实证（`javap -c TypedPayloadRegistries` 偏移 122..134 / 287..299）：注册表里
    //  String / ResourceLocation **确实**各有一条 `registerSimple(...)`，但两者都带
    //  priority = -1 且走 `SimpleObjectAccessor`（managed=false），而 `managed/` 包下
    //  **没有 String 版的 IManagedVar**（全表只有 Byte/Short/Int/Long/Float/Double/Char/Boolean
    //  ＋ 8 种基本类型数组）。⇒ 放进 @DescSynced 属于"编译过、运行期不生效"的高危写法，
    //  与上一轮的结论一致，**继续不用 String**。
    //
    //  ⇒ 输出名字走【数字通道】：物品的**注册表数值 id**（`BuiltinRegistries.ITEM.getId`）。
    //    `int[]` 是本类已经在用的受支持类型（hostModParallel 等），且**数值 id 就是
    //    原版自己发物品时用的线路格式**（FriendlyByteBuf 的 ItemStack 编解码走
    //    `ByteBufCodecs.registry(Registries.ITEM)` ⇒ 写的就是 getId）。客户端按 id 反查
    //    自己那一侧同一个注册表 ⇒ 与 `hostModuleMask`"按权威槽位查名字"同一条纪律，
    //    不是猜。查不到（id < 0 / byId 为 null / air）⇒ 那个条目直接不出现在显示里。
    /** 每个扫描位的输出条目数（0..2；{@code <0} = 未知）。 */
    @DescSynced
    private int[] hostModOutCount = sentinelInts(SNAP_SLOTS);
    /** 每 (扫描位, 第几条) 的物品注册表 id（{@code <0} = 没有/未知）；长度 {@code SNAP_SLOTS * OUT_PER_MODULE}。 */
    @DescSynced
    private int[] hostModOutItem = sentinelInts(SNAP_SLOTS * OUT_PER_MODULE);
    /** 与 {@link #hostModOutItem} 同下标的数量（{@code <0} = 没有/未知）。 */
    @DescSynced
    private int[] hostModOutAmount = sentinelInts(SNAP_SLOTS * OUT_PER_MODULE);
    /** 主机级当前产出（最多 {@link #HOST_OUT_MAX} 条）的物品注册表 id；{@code <0} = 没有/未知。 */
    @DescSynced
    private int[] hostOutItem = sentinelInts(HOST_OUT_MAX);
    /** 与 {@link #hostOutItem} 同下标的数量。 */
    @DescSynced
    private int[] hostOutAmount = sentinelInts(HOST_OUT_MAX);
    /** 主机级：所有模块配方输出加起来一共几种（去重后的物品数）；{@code <0} = 未知。 */
    @DescSynced
    private int hostOutKinds = -1;

    // ── 侧栏/Jade 面板上已经在算的那几项（2026-10-25 第二十三改）─────────────────────────
    //
    //  用户原话：「接数据顺便把这个接上」＋两张侧栏截图（能源仓 4996/4996、
    //  动力仓 19 位数字、物质模块等级 Lv.17/17、模块等级复写 生效、材料增产 ×N）。
    //  ⇒ 这几项的**唯一来源**是 {@link #addDisplayText(List)}（1177 行那段），
    //    它读的是 {@link #energyTotals(IO)} / {@link #moduleSlotBonus()} /
    //    {@link #getHostMatterLevelOverride()} / {@link #productionMultiplier()}。
    //    其中 moduleSlotBonus()/getHostMatterLevelOverride()/productionMultiplier() 客户端
    //    读的是 @DescSynced 镜像（客户端的槽是空的，**不能自己算**）；只有**存量**在客户端
    //    恒 0（仓是 @Persisted 不是 @DescSynced）⇒ 必须由服务端推过来。
    /** 能源仓（{@code IO.IN}）已存 EU；{@code <0} = 未知。来源 = {@link #energyTotals(IO)}。 */
    @DescSynced
    private long hostEnergyInStored = -1L;
    /** 能源仓容量。 */
    @DescSynced
    private long hostEnergyInCapacity = -1L;
    /** 动力仓（{@code IO.OUT}）已存 EU；{@code <0} = 未知。 */
    @DescSynced
    private long hostEnergyOutStored = -1L;
    /** 动力仓容量。 */
    @DescSynced
    private long hostEnergyOutCapacity = -1L;

    // ── 非同步：本机自己的采样器与 tick 订阅句柄（不需要过网络，也不需要落盘）──────────────
    /** 机器级 tick 钩子的句柄（{@code ensureSnapshotTick()} 订阅、{@code onUnload()} 退订）。 */
    private com.gregtechceu.gtceu.api.machine.TickableSubscription snapTick;
    /** tick 回调被真正调到过多少次（判据：> 0 说明订阅活着）。 */
    private long snapTickCalls = 0L;
    /** {@link #refreshHostRuntimeSnapshot()} 被调到过多少次（含"值没变所以没推包"的那些）。 */
    private long snapRefreshCalls = 0L;
    /**
     * 每 tick 的轻量路径 {@link #refreshProgressTick()} <b>真的把进度写变过</b>多少次
     * （= 推了几个包；空转/没在跑时是 0，一个字节都不发）。
     *
     * <p>判据（用户实机一眼可读）：机器在跑时它应该 ≈ {@code 20/秒}（每 tick 一次），
     * 而 {@code refresh#}（完整快照）是 {@code 2/秒} —— 两个数的比值就是"进度比其余字段快 10 倍"。
     */
    private long snapProgressPushes = 0L;
    /** 打了几行 {@code [SHANHAI-HOSTSNAP]} 日志（前 3 次 + 之后每 200 次一行）。 */
    private long snapLogCount = 0L;
    /** 异常只报一次（同一个异常刷屏会盖掉别的日志）。 */
    private boolean snapErrorLogged = false;

    /**
     * 功率滑窗的采样环（长度 2）：每次快照把「能源仓/动力仓上一个 20 tick 窗口内的实测吞吐」
     * 压进环里，取环内均值 ÷ {@link #POWER_WINDOW_TICKS} ⇒ {@code EU/t}。
     *
     * <p>窗口来源（javap 实证，不是猜）：{@code NotifiableEnergyContainer#getInputPerSec()}
     * 直接返回 {@code lastEnergyInputPerSec} 字段，而该字段每 20 tick 由
     * {@code energyInputPerSec}（一个只增的累计器）滚动一次 ⇒ <b>它的单位就是"上一个 20 tick
     * 窗口内累计的 EU"</b>。{@code EnergyContainerList#getInputPerSec()} 是各仓求和。
     */
    private final long[] powerInRing = new long[2];
    /** 与 {@link #powerInRing} 同构（动力仓 = {@code getOutputPerSec()}）。 */
    private final long[] powerOutRing = new long[2];
    /** 环里有几个有效样本（0..2）。 */
    private int powerRingFill = 0;

    /** 快照数组长度 = 模块扫描位上限（24）。 */
    public static final int SNAP_SLOTS = 24;

    /** 每个模块在 AR 面板上最多显示几条输出（与 {@code HostFxGlassArPanel.MOD_ROW_OUTPUTS} 对齐）。 */
    public static final int OUT_PER_MODULE = 2;

    /** 主机一节最多显示几条输出（与 {@code HostFxGlassArPanel.HOST_OUTPUTS} 对齐）。 */
    public static final int HOST_OUT_MAX = 3;

    /**
     * <b>完整</b>快照的刷新周期（tick）。<b>10 tick = 0.5 s</b>。
     *
     * <p>为什么不每 tick：一趟要遍历 24 个扫描位、各自读配方与输出，还要把 8 个数组各 new 一遍。
     * 0.5 s 对"输出清单 / 功率 / 容量 / 位图"这些<b>慢变量</b>足够，包量降到 1/10。
     *
     * <p>🔴🔴 <b>2026-10-25 第二十七改：本常量只管"慢变量"了 —— 进度类连续量改成每 tick。</b>
     *
     * <p>起因（用户原话，逐字）：「<b>不是，你原本 60 fps 它也不流畅啊（指的是进度条），
     * 那应该不是刷新率导致的</b>」—— 用户判断对了。<b>真凶就是这个 10</b>：进度是
     * <b>连续量</b>，10 tick 才采一次样 ⇒ 面板上的进度条只能 2 次/秒地跳格，
     * 客户端哪怕每帧重建，读到的也只是同一个不前前的数字（"看着 60 fps，其实 2 Hz"）。
     *
     * <p>处置见 {@link #refreshProgressTick()}：<b>每 tick</b>只刷"主机进度/剩余 + 每个已装模块的
     * 进度/剩余"，其余字段全部保持本常量的节奏。
     */
    public static final int SNAPSHOT_INTERVAL_TICKS = 10;

    /**
     * 功率口径的窗口长度（tick）。<b>20 tick = 1 s</b>。
     *
     * <p>🔴 用户 2026-10-25 口径：「总耗能/总发电我说的应该是<b>总耗能功率/总发电功率</b>」⇒
     * 面板上那两个数必须是 <b>EU/t 的瞬时速率</b>，<b>不是</b>"自加载以来的累计 EU"。
     *
     * <p>为什么是 20：GTCEu 的 {@code NotifiableEnergyContainer#getInputPerSec()} /
     * {@code getOutputPerSec()} 返回的就是"上一个 20 tick 窗口内累计的 EU"（javap 实证：
     * 直接返回 {@code lastEnergyInputPerSec} 字段，该字段每 20 tick 由只增累计器
     * {@code energyInputPerSec} 滚动得到）⇒ 除以 20 即 EU/t，且<b>窗口恰好就是 GT 自己的
     * 20 tick</b>。本类再把相邻 2 次采样（间隔 {@link #SNAPSHOT_INTERVAL_TICKS} = 10 tick）
     * 取均值，得到<b>每 10 tick 前进一格的 20 tick 滑动窗口</b>（消掉 tumbling window 的台阶感）。
     */
    public static final int POWER_WINDOW_TICKS = 20;

    /** 填满 {@code n} 个 {@code -1}（{@code int[]} 的"未知"哨兵；**不能**用 0 —— 0 是合法注册表 id）。 */
    static int[] sentinelInts(int n) {
        final int[] a = new int[n];
        java.util.Arrays.fill(a, -1);
        return a;
    }

    /** 填满 {@code n} 个 {@code -1}（{@code long[]} 的"未知"哨兵）。 */
    static long[] sentinelLongs(int n) {
        final long[] a = new long[n];
        java.util.Arrays.fill(a, -1L);
        return a;
    }

    /** 填满 {@code n} 个 {@code -1}（{@code byte[]} 的"未知"哨兵）。 */
    static byte[] sentinelBytes(int n) {
        final byte[] a = new byte[n];
        java.util.Arrays.fill(a, (byte) -1);
        return a;
    }

    /** 特效总开关（{@code true} = 开）。 */
    public boolean isHostFxEnabled() {
        return hostFxEnabled;
    }

    /** 写特效总开关（值没变就一个字节都不动）。 */
    public void setHostFxEnabled(boolean value) {
        if (hostFxEnabled == value) {
            return;
        }
        hostFxEnabled = value;
        pushHostFxSync();
    }

    /** 内环带角速度（°/s，已钳 1..60）。 */
    public int getHostRingDegPerSec() {
        return hostRingDegPerSec;
    }

    /** 写内环带角速度（°/s；越界钳到 1..60，值没变不推同步）。 */
    public void setHostRingDegPerSec(int degPerSec) {
        final int v = clampInt(degPerSec, HOST_RING_DEG_MIN, HOST_RING_DEG_MAX);
        if (hostRingDegPerSec == v) {
            return;
        }
        hostRingDegPerSec = v;
        pushHostFxSync();
    }

    /** 上 4 舰角速度（°/s，已钳 0..60）。 */
    public int getHostShipUpDegPerSec() {
        return hostShipUpDegPerSec;
    }

    /** 写上 4 舰角速度（°/s；越界钳到 0..60）。 */
    public void setHostShipUpDegPerSec(int degPerSec) {
        final int v = clampInt(degPerSec, HOST_SHIP_DEG_MIN, HOST_SHIP_DEG_MAX);
        if (hostShipUpDegPerSec == v) {
            return;
        }
        hostShipUpDegPerSec = v;
        pushHostFxSync();
    }

    /** 下 4 舰角速度（°/s <b>幅值</b>，已钳 0..60；玩家看到的始终是正数）。 */
    public int getHostShipDownDegPerSec() {
        return hostShipDownDegPerSec;
    }

    /** 写下 4 舰角速度幅值（°/s；越界钳到 0..60。传负数的语义 = 0，方向永远由渲染侧负责）。 */
    public void setHostShipDownDegPerSec(int degPerSec) {
        final int v = clampInt(degPerSec, HOST_SHIP_DEG_MIN, HOST_SHIP_DEG_MAX);
        if (hostShipDownDegPerSec == v) {
            return;
        }
        hostShipDownDegPerSec = v;
        pushHostFxSync();
    }

    /** 黑洞半径（×0.1 格，已钳 10..120）。 */
    public int getHostBlackHoleRadiusTenths() {
        return hostBlackHoleRadiusTenths;
    }

    /** 黑洞半径（格）= {@link #getHostBlackHoleRadiusTenths()} ÷ 10（渲染侧直接用这个）。 */
    public float getHostBlackHoleRadiusBlocks() {
        return hostBlackHoleRadiusTenths / 10.0F;
    }

    /** 写黑洞半径（×0.1 格；越界钳到 10..120）。 */
    public void setHostBlackHoleRadiusTenths(int tenths) {
        final int v = clampInt(tenths, HOST_HOLE_RADIUS_TENTHS_MIN, HOST_HOLE_RADIUS_TENTHS_MAX);
        if (hostBlackHoleRadiusTenths == v) {
            return;
        }
        hostBlackHoleRadiusTenths = v;
        pushHostFxSync();
    }

    /** 引力透镜开关（{@code true} = 开）。 */
    public boolean isHostLensEnabled() {
        return hostLensEnabled;
    }

    /** 玻璃 AR 屏幕背后的灰色半透明遮罩开关（{@code true} = 铺，默认开）。 */
    public boolean isHostArMaskEnabled() {
        return hostArMaskEnabled;
    }

    /** 写 AR 遮罩开关（值没变就一个字节都不动；客户端那次是本地即时反馈，不推包）。 */
    public void setHostArMaskEnabled(boolean value) {
        if (hostArMaskEnabled == value) {
            return;
        }
        hostArMaskEnabled = value;
        pushHostFxSync();
    }

    /** 已装模块槽位位图（服务端权威、{@code @DescSynced}；{@code bit i} = 第 i 个扫描位上有模块）。 */
    public long hostModuleMask() {
        return hostModuleMask;
    }

    /**
     * 已装模块数（<b>服务端权威</b>位图的人口数）。
     *
     * <p>位图还是 0（例如刚读档、服务端还没跑过 {@link #refreshModuleMask()}）时回落到框架自己的
     * {@code getFormedModuleCount()} —— 两条都是真读数，取"先到的那一条"，<b>绝不写死 0</b>。
     */
    public int hostModuleCountAuthoritative() {
        final int bits = Long.bitCount(hostModuleMask);
        return bits > 0 ? bits : getFormedModuleCount();
    }

    /**
     * 重算 {@link #hostModuleMask}（<b>只在服务端跑</b>）：逐槽位看世界里那台机器是不是
     * 「已成型 + 认本机为宿主」的模块。
     *
     * <p>判据与 gtlcore {@code isValidModule} 同源（{@code module.isFormed() &&
     * module.getHostType().isInstance(this)}），但这里<b>额外</b>要求 {@code module.getHost() == this}
     * —— 位图是"已经挂到本机上的模块"，不是"位置上放着一台模块形状的机器"。
     */
    private void refreshModuleMask() {
        if (isRemote()) {
            return;
        }
        // 🔴 第二十四改：模块挂上来是"本机活着"的最强信号 —— 顺手确保快照钩子在跑
        //（幂等；解决"读档后实例换过、onLoad 那一次订阅没挂上"的静默断链）。
        ensureSnapshotTick();
        final Level level = getLevel();
        long mask = 0L;
        if (level != null) {
            final BlockPos[] positions = getModuleScanPositions();
            for (int i = 0; i < positions.length && i < Long.SIZE; i++) {
                final MetaMachine machine = MetaMachine.getMachine(level, positions[i]);
                if (machine instanceof IModularMachineModule<?, ?> module
                        && module.isFormed() && module.getHost() == this) {
                    mask |= 1L << i;
                }
            }
        }
        if (mask == hostModuleMask) {
            return;
        }
        hostModuleMask = mask;
        notifyBlockUpdate();
    }

    /** 写引力透镜开关（值没变就一个字节都不动）。 */
    public void setHostLensEnabled(boolean value) {
        if (hostLensEnabled == value) {
            return;
        }
        hostLensEnabled = value;
        pushHostFxSync();
    }

    /** 推一次 @DescSynced（只在服务端做；客户端那次是本地即时反馈，不推包）。 */
    private void pushHostFxSync() {
        if (!isRemote()) {
            notifyBlockUpdate();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════════
    //  AR 右侧信息栏：快照的客户端读口 + 服务端刷新（见上面那一段字段注释）
    // ═══════════════════════════════════════════════════════════════════════════════════

    /** 快照序号（0 = 还没快照过 ⇒ 客户端一律 {@code --}）。 */
    public int hostSnapSeq() {
        return hostSnapSeq;
    }

    /** 每模块进度百分比（{@code <0} = 未知）；数组长度 {@link #SNAP_SLOTS}。 */
    public byte[] hostModProgressPct() {
        return hostModProgressPct;
    }

    /** 每模块 {@code EU/t}（{@code <0} = 未知）。 */
    public long[] hostModEuPerTick() {
        return hostModEuPerTick;
    }

    /** 每模块并行数（{@code <0} = 未知）。 */
    public int[] hostModParallel() {
        return hostModParallel;
    }

    /** 每模块跨配方线程数（{@code <0} = 未知）。 */
    public int[] hostModThreads() {
        return hostModThreads;
    }

    /** 每模块剩余秒数（{@code <0} = 未知）。 */
    public int[] hostModRemainSec() {
        return hostModRemainSec;
    }

    /** 每模块「为什么没在跑」原因码（{@code <0} = 读不到；取值见 {@link #REASON_NONE}…{@link #REASON_BLOCKED}）。 */
    public byte[] hostModReason() {
        return hostModReason;
    }

    public int hostRecipeProgressPct() {
        return hostRecipeProgressPct;
    }

    public int hostRecipeRemainSec() {
        return hostRecipeRemainSec;
    }

    public int hostRecipeParallel() {
        return hostRecipeParallel;
    }

    public int hostRecipeThreads() {
        return hostRecipeThreads;
    }

    public long hostEuPerTick() {
        return hostEuPerTick;
    }

    public long hostTotalOutputCount() {
        return hostTotalOutputCount;
    }

    public int hostTotalOutputKinds() {
        return hostTotalOutputKinds;
    }

    /** 主机级：<b>总耗能功率</b>（{@code EU/t} 瞬时速率；{@code <0} = 未知）。 */
    public long hostTotalEuUsedPerTick() {
        return hostTotalEuUsedPerTick;
    }

    /** 主机级：<b>总发电功率</b>（{@code EU/t} 瞬时速率；{@code <0} = 未知）。 */
    public long hostTotalEuGenPerTick() {
        return hostTotalEuGenPerTick;
    }

    public int hostInstalledModules() {
        return hostInstalledModules;
    }

    /** 每模块输出条目数（{@code <0} = 未知；0..2 = 实际条数）。 */
    public int[] hostModOutCount() {
        return hostModOutCount;
    }

    /** 每 (扫描位, 第几条) 输出的物品注册表数值 id（{@code <0} = 没有/未知）。 */
    public int[] hostModOutItem() {
        return hostModOutItem;
    }

    /** 与 {@link #hostModOutItem()} 同下标的数量。 */
    public int[] hostModOutAmount() {
        return hostModOutAmount;
    }

    /** 主机级当前产出的物品注册表 id（{@code <0} = 没有/未知）。 */
    public int[] hostOutItem() {
        return hostOutItem;
    }

    /** 与 {@link #hostOutItem()} 同下标的数量。 */
    public int[] hostOutAmount() {
        return hostOutAmount;
    }

    /** 主机级所有模块输出加起来一共几种（{@code <0} = 未知）。 */
    public int hostOutKinds() {
        return hostOutKinds;
    }

    /** 能源仓（{@code IO.IN}）已存 EU（{@code <0} = 未知）。 */
    public long hostEnergyInStored() {
        return hostEnergyInStored;
    }

    /** 能源仓容量（{@code <0} = 未知）。 */
    public long hostEnergyInCapacity() {
        return hostEnergyInCapacity;
    }

    /** 动力仓（{@code IO.OUT}）已存 EU（{@code <0} = 未知）。 */
    public long hostEnergyOutStored() {
        return hostEnergyOutStored;
    }

    /** 动力仓容量（{@code <0} = 未知）。 */
    public long hostEnergyOutCapacity() {
        return hostEnergyOutCapacity;
    }

    /** 上一次打 {@code [SHANHAI-HOSTBONUS]} 的那组读数（只在变化时打）。 */
    private String bonusLoggedKey = "";

    /**
     * 🔴 <b>缺陷 A 的判据行</b>（用户原话：「<b>主机吃不到物质模块的额外产出加成</b>」）。
     *
     * <h2>取证：这条链在代码里有 5 环（改前逐环核过，<b>一环都没断</b>）</h2>
     * <pre>
     *   ① 面板/槽   基类 {@code matterBonusSlot}（1 格，容量 64，:1299）
     *   ② 门控      {@code PrimordialOmegaEngineMachine#computeMatterBonusLevel()}（:1771）
     *              ⇒ 必须 64 个<b>同种</b>物质模块才返回 1..17
     *   ③ 读数口    {@code moduleSlotBonus()}（:1803 → refreshMatterBonusLevel :1816）
     *   ④ 算式      {@code PrimordialRecipeEffects#productionMultiplierOf(gate, extra)}（:545）
     *              = (1 + gate) + max(0, extra)
     *   ⑤ 两条消费者
     *        引擎路径：{@code PrimordialEngineRecipeLogic#buildFinalNormalRecipe:687}
     *                  gate = host.moduleSlotBonus() ⇒ :767 productionMultiplierOf(gate, host.getHostProductionBonusExtra())
     *                  ⇒ applyHostTailEffectsA(...) :2377 multiplyOutputs(recipe, multiplier)
     *        原生链  ：{@link #applyGuixuRecipeModifier}（:2442）—— ⚠️ 引擎路径不经过它（死代码，留档）
     * </pre>
     * ⇒ <b>结论（取证结论，不是猜）</b>：③→⑤ 全在，<b>能断的只有 ①②</b> ——
     * 即「物质模块槽里的东西<b>没有达到 64 个同种</b>」或「槽根本不是玩家以为的那一格」。
     * 最可能的现场：玩家把 64 个物质模块放进了 <b>{@code levelOverrideSlot}</b>（本类的「模块等级复写」槽，
     * 语义是"复写装在上面的模块的等级"，<b>不影响主机自己的增产</b>）或新增的 <b>3 个额外挂载槽</b>
     * （那是 cleanroom/重力/维度/研究/热力用的），而不是基类的「物质模块专属槽」。
     * 这一行日志把 ①②③④ 的读数一次打出来，<b>下次一看就知道断在哪一环</b>。
     *
     * <p>判据（用户可读）：
     * <pre>
     *   槽放满 64 个同种物质模块 ⇒ gate 必须 1..17；gate=0 ⇒ 槽没放满/放错槽（②没成立）
     *   产出倍率 mult = (1 + gate) + extra ⇒ 空槽 = 6；满槽 Lv17 = 23
     *   mult 对了但产出没变 ⇒ 断在 ⑤ 的 multiplyOutputs（那是引擎路径的事，看 [SHANHAI-DUR] 同一批日志）
     * </pre>
     */
    private void logProductionBonusOnce() {
        if (isRemote()) {
            return;
        }
        try {
            final int gate = moduleSlotBonus();
            final int extra = getHostProductionBonusExtra();
            final int mult = com.shanhai.common.recipe.PrimordialRecipeEffects
                    .productionMultiplierOf(gate, extra);
            final String key = gate + "/" + extra + "/" + mult;
            if (key.equals(bonusLoggedKey)) {
                return;
            }
            bonusLoggedKey = key;
            LOGGER.info("[SHANHAI-HOSTBONUS] 归墟 gate(物质模块槽等级)={} extra(主机钩子)={} mult={}"
                            + " —— 判据：槽里放满 64 个【同种】物质模块时 gate 必须 1..17"
                            + "（=0 ⇒ 槽没放满 / 放进了 levelOverrideSlot 或 3 个额外挂载槽 = 不放错就是这原因）；"
                            + "mult = (1+gate)+extra ⇒ 空槽 6、满槽 Lv17 23",
                    gate, extra, mult);
        } catch (Throwable ignored) {
            // 纯诊断，绝不影响机器
        }
    }

    /**
     * 重算 AR 右侧信息栏那份快照（<b>只在服务端跑</b>，由 {@link #SNAPSHOT_INTERVAL_TICKS} 的
     * tick 钩子驱动，见 {@link #onLoad()}）。
     *
     * <h2>逐项取数来源（每一项都是"已有的权威读数"，没有一项是另算的）</h2>
     * <pre>
     *   每模块 进度 %      ← {@code RecipeLogic#getProgress() / getMaxProgress()}（模块自己那份引擎逻辑）
     *   每模块 EU/t        ← {@code RecipeHelper.getInputEUt(getLastRecipe())}（引擎成品配方，已含并行）
     *   每模块 并行        ← {@code PrimordialModuleMachine#getCurrentParallel()}
     *   每模块 线程        ← {@code PrimordialModuleMachine#getCrossRecipeThreads()}
     *   每模块 剩余秒      ← {@code (getMaxProgress() - getProgress()) / 20}
     *   每模块 输出(id×量) ← {@code RecipeHelper.getOutputItems(getLastRecipe())} → 物品注册表数值 id
     *   主机级 进度/剩余   ← 本机自己的 {@code getRecipeLogic()}
     *   主机级 已装模块    ← {@link #hostModuleCountAuthoritative()}（= 服务端权威位图人口数）
     *   主机级 总产出/kinds← 各模块 {@code getOutputItems} 累加 / 去重
     *   主机级 总耗能/发电 ← **功率口径（2026-10-25 第二十四改）**：{@link #sumEnergyRatePer20(IO)}
     *                        读能源仓/动力仓<b>实测吞吐</b>（{@code getInputPerSec()/getOutputPerSec()}，
     *                        单位 = 上一个 20 tick 窗口累加的 EU），除以 {@link #POWER_WINDOW_TICKS}
     *                        ⇒ {@code EU/t} 瞬时速率；再对最近 2 次采样取均值（20 tick 滑动窗口）。
     *                        <b>不再是"自加载以来累计 EU"</b>（用户原话要的是功率）。
     *   能源仓/动力仓      ← {@link #energyTotals(IO)}（= addDisplayText 那两行用的同一个方法）
     * </pre>
     *
     * <p>🔴 <b>拿不到的项一律留 {@code -1}（客户端画 {@code --}），绝不填 0</b>。
     * 会留 {@code -1} 的只有这几类：槽位上没有模块 / 模块未成型 / 模块没连上本机 /
     * 模块没拿到过配方（{@code getLastRecipe() == null}）/ 读的过程中抛异常。
     * 反过来，"模块在、但当前没在跑"是<b>真读数</b>（进度 0%、EU/t 0），照实写 0。
     *
     * <p>🔴 <b>只在真有变化时才 {@code hostSnapSeq++} 并推一次同步</b>：否则 0.5 s 一个包，
     * 而方块实体包会连带触发客户端那一侧的 block update。比对用 {@code Arrays.equals}。
     *
     * <p>🔴🔴 <b>整个过程包在 try/catch 里（2026-10-25 第二十四改）</b>：本方法跑在
     * {@code subscribeServerTick} 的回调里，**任何一处抛出都会让 {@code hostSnapSeq} 永远停在 0**
     * ⇒ 客户端整片画 {@code --}，而且现场只有一行看不出因果的堆栈。<b>"快照没到"的最贵
     * 排查成本就在这里</b>，所以这里改成：异常 ⇒ 打一行带 {@code [SHANHAI-HOSTSNAP]} 前缀的
     * WARN（只打一次）＋ 仍然把 {@code hostSnapSeq} 推上去（让客户端知道"服务端活着、
     * 只是这一项读不到"）。
     */
    public void refreshHostRuntimeSnapshot() {
        if (isRemote()) {
            return;
        }
        snapRefreshCalls++;
        boolean failed = false;
        Throwable failure = null;
        try {
            refreshHostRuntimeSnapshot$body();
        } catch (Throwable t) {
            failed = true;
            failure = t;
            if (!snapErrorLogged) {
                snapErrorLogged = true;
                LOGGER.warn("[SHANHAI-HOSTSNAP] refresh threw at call #{} —— 后续每次都会吞掉"
                        + "（这一行只打一次），快照序号仍然推上去，避免客户端整片 --",
                        snapRefreshCalls, t);
            }
        }
        // 🆕 2026-10-25（缺陷 A）：材料增产那一条链的**判据行**（每 10 tick 判一次，值变了才打）
        logProductionBonusOnce();
        snapLogCount++;
        // 2026-10-25 第二十七改：一行里同时给出 3 个节拍（前 3 次 + 之后每 40 次一行，约 20 秒）：
        //   refresh#   = 完整快照（10 tick 一次 ⇒ ≈2/秒，慢变量）
        //   progress#  = 每 tick 的进度推送（只算"真的变了"的那些 ⇒ 机器在跑时 ≈20/秒）
        //   tick#      = tick 钩子活着没有（≈20/秒；不涨 = 订阅没挂上）
        if (snapLogCount <= 3 || snapLogCount % 40 == 0) {
            LOGGER.info("[SHANHAI-HOSTSNAP] seq={} refresh#{} progress#{} tick#{} formed={} modules={}"
                            + " (installed={}) energyIn={} EU/t energyOut={} EU/t failed={}{}",
                    hostSnapSeq, snapRefreshCalls, snapProgressPushes, snapTickCalls, isFormed(),
                    getFormedModuleCount(), hostInstalledModules,
                    hostTotalEuUsedPerTick, hostTotalEuGenPerTick, failed,
                    failed ? (" err=" + (failure == null ? "?" : failure.getClass().getSimpleName())) : "");
        }
    }

    /**
     * {@link #refreshHostRuntimeSnapshot()} 的<b>实际取数体</b>（拆出来只为了让外面那层
     * try/catch 能同时兜住"取数"和"推包"两段）。
     */
    private void refreshHostRuntimeSnapshot$body() {
        final Level level = getLevel();
        final BlockPos[] positions = level == null ? new BlockPos[0] : getModuleScanPositions();

        final byte[] pct = sentinelBytes(SNAP_SLOTS);
        final long[] eu = sentinelLongs(SNAP_SLOTS);
        final int[] par = sentinelInts(SNAP_SLOTS);
        final int[] thr = sentinelInts(SNAP_SLOTS);
        final int[] rem = sentinelInts(SNAP_SLOTS);
        final int[] outCount = sentinelInts(SNAP_SLOTS);
        final int[] outItem = sentinelInts(SNAP_SLOTS * OUT_PER_MODULE);
        final int[] outAmount = sentinelInts(SNAP_SLOTS * OUT_PER_MODULE);
        // 2026-10-25 第三十三改：每模块「为什么没在跑」（0..4；-1 = 读不到）
        final byte[] reason = sentinelBytes(SNAP_SLOTS);

        int installed = 0;
        long euPerTickSum = 0L;
        long euGenPerTickSum = 0L;
        long outEntries = 0L;
        final java.util.LinkedHashMap<Integer, Integer> hostOut = new java.util.LinkedHashMap<>();
        final java.util.HashSet<Integer> kinds = new java.util.HashSet<>();

        if (level != null) {
            for (int i = 0; i < SNAP_SLOTS && i < positions.length; i++) {
                final MetaMachine machine = MetaMachine.getMachine(level, positions[i]);
                if (!(machine instanceof PrimordialModuleMachine module)) {
                    continue;                                  // 空槽 / 不是模块 ⇒ 整行留 -1
                }
                if (!module.isFormed() || module.getHost() != this) {
                    continue;                                  // 没连上本机 ⇒ 也留 -1
                }
                installed++;
                final ModuleReading reading = readModule(module);
                applyReading(i, reading, pct, eu, par, thr, rem, outCount, outItem, outAmount);
                // 2026-10-25 第三十三改：原因码（只读；异常 ⇒ NONE，绝不影响机器）
                reason[i] = reasonOf(module);
                if (reading.euPerTick >= 0L) {
                    euPerTickSum += reading.euPerTick;
                }
                if (reading.genPerTick >= 0L) {
                    euGenPerTickSum += reading.genPerTick;
                }
                for (int k = 0; k < OUT_PER_MODULE; k++) {
                    final int id = reading.outItem[k];
                    final int amount = reading.outAmount[k];
                    if (id < 0 || amount <= 0) {
                        continue;
                    }
                    kinds.add(id);
                    outEntries += amount;
                    hostOut.merge(id, amount, Integer::sum);
                }
            }
        }

        // ── 主机级：进度 / 剩余 / 当前 EUt（本机自己的配方逻辑）──────────────────────────
        int hostPct = -1;
        int hostRemain = -1;
        long hostEu = -1L;
        try {
            final com.gregtechceu.gtceu.api.machine.trait.RecipeLogic logic = getRecipeLogic();
            if (logic != null) {
                // 2026-10-25 第二十七改：公式统一走 progressOf（与每 tick 的轻量路径同一个实现）
                final int[] pr = progressOf(logic.getProgress(), logic.getMaxProgress(), logic.isWorking());
                hostPct = pr[0];
                hostRemain = pr[1];
                // 🔴 2026-10-25 第三十四改：同样回落 getLastOriginRecipe()（**只读**，不换口径）
                GTRecipe last = logic.getLastRecipe();
                if (last == null) {
                    last = logic.getLastOriginRecipe();
                }
                if (last != null) {
                    hostEu = Math.max(0L, com.gregtechceu.gtceu.api.recipe.RecipeHelper.getInputEUt(last));
                } else {
                    hostEu = logic.isWorking() ? -1L : 0L;
                }
            }
        } catch (Throwable ignored) {
            hostPct = -1;
            hostRemain = -1;
            hostEu = -1L;
        }

        // ── 主机级：总耗能功率 / 总发电功率（EU/t，20 tick 滑动窗口）────────────────────────
        //
        //  2026-10-25 第二十四改（用户口径）：用户原话「总耗能/总发电我说的应该是总耗能功率/
        //  总发电功率」⇒ 这里**不再**做"Σ EU/t × 经过 tick 数"的累计，改成**瞬时速率**：
        //    ① 读能源仓（IO.IN）/动力仓（IO.OUT）的实测吞吐 getInputPerSec()/getOutputPerSec()
        //       —— javap 实证：NotifiableEnergyContainer 直接返回 lastEnergyInputPerSec 字段，
        //          该字段每 20 tick 由只增累计器滚动 ⇒ 单位 = "上一个 20 tick 窗口累加的 EU"；
        //          EnergyContainerList 是各仓求和。
        //    ② 压进长度 2 的采样环，取环内均值 ÷ POWER_WINDOW_TICKS(20) ⇒ EU/t。
        //       两次采样相隔 SNAPSHOT_INTERVAL_TICKS(10) ⇒ 等价于一个 20 tick 宽、每 10 tick
        //       前进一格的滑动窗口（纯 tumbling 窗口会有台阶感）。
        //    ③ 读不到（capability 代理抛异常 / 没有仓）⇒ −1 ⇒ 客户端画 `--`，不编数。
        final long rateInPer20 = sumEnergyRatePer20(IO.IN, true);
        final long rateOutPer20 = sumEnergyRatePer20(IO.OUT, false);
        powerInRing[1] = powerInRing[0];
        powerInRing[0] = rateInPer20;
        powerOutRing[1] = powerOutRing[0];
        powerOutRing[0] = rateOutPer20;
        if (powerRingFill < 2) {
            powerRingFill++;
        }
        final long euUsedPower = averagePerTick(powerInRing, powerRingFill);
        final long euGenPower = averagePerTick(powerOutRing, powerRingFill);

        // ── 主机级：能源仓 / 动力仓（与 addDisplayText 同一个方法）────────────────────────
        final long[] in = energyTotals(IO.IN);
        final long[] out = energyTotals(IO.OUT);

        // ── 主机级：当前产出（最多 3 条）────────────────────────────────────────────────
        final int[] hostOutIds = sentinelInts(HOST_OUT_MAX);
        final int[] hostOutAmounts = sentinelInts(HOST_OUT_MAX);
        int hostIdx = 0;
        for (java.util.Map.Entry<Integer, Integer> e : hostOut.entrySet()) {
            if (hostIdx >= HOST_OUT_MAX) {
                break;
            }
            hostOutIds[hostIdx] = e.getKey();
            hostOutAmounts[hostIdx] = e.getValue();
            hostIdx++;
        }

        final int installedNow = hostModuleCountAuthoritative();
        final int kindsNow = kinds.size();
        final boolean changed =
                installed != hostInstalledModules
                        || hostPct != hostRecipeProgressPct
                        || hostRemain != hostRecipeRemainSec
                        || hostEu != hostEuPerTick
                        || kindsNow != hostOutKinds
                        || outEntries != hostTotalOutputCount
                        || euUsedPower != hostTotalEuUsedPerTick
                        || euGenPower != hostTotalEuGenPerTick
                        || in[0] != hostEnergyInStored || in[1] != hostEnergyInCapacity
                        || out[0] != hostEnergyOutStored || out[1] != hostEnergyOutCapacity
                        || !java.util.Arrays.equals(pct, hostModProgressPct)
                        || !java.util.Arrays.equals(eu, hostModEuPerTick)
                        || !java.util.Arrays.equals(par, hostModParallel)
                        || !java.util.Arrays.equals(thr, hostModThreads)
                        || !java.util.Arrays.equals(rem, hostModRemainSec)
                        || !java.util.Arrays.equals(reason, hostModReason)
                        || !java.util.Arrays.equals(outCount, hostModOutCount)
                        || !java.util.Arrays.equals(outItem, hostModOutItem)
                        || !java.util.Arrays.equals(outAmount, hostModOutAmount)
                        || !java.util.Arrays.equals(hostOutIds, hostOutItem)
                        || !java.util.Arrays.equals(hostOutAmounts, hostOutAmount);
        if (!changed) {
            return;
        }

        hostInstalledModules = installedNow;
        hostRecipeProgressPct = hostPct;
        hostRecipeRemainSec = hostRemain;
        hostEuPerTick = hostEu;
        // 主机级并行 / 跨配方线程：本机**没有**"当前并行份数"的权威读数（getRecipeLogicMaxParallel()
        // 是上限不是现值）⇒ 保持 -1（面板画 --），绝不拿上限冒充现值。
        hostRecipeParallel = -1;
        hostRecipeThreads = -1;
        hostOutKinds = kindsNow;
        hostTotalOutputCount = outEntries;
        hostTotalEuUsedPerTick = euUsedPower;
        hostTotalEuGenPerTick = euGenPower;
        hostEnergyInStored = in[0];
        hostEnergyInCapacity = in[1];
        hostEnergyOutStored = out[0];
        hostEnergyOutCapacity = out[1];
        hostModProgressPct = pct;
        hostModEuPerTick = eu;
        hostModParallel = par;
        hostModThreads = thr;
        hostModRemainSec = rem;
        hostModReason = reason;
        hostModOutCount = outCount;
        hostModOutItem = outItem;
        hostModOutAmount = outAmount;
        hostOutItem = hostOutIds;
        hostOutAmount = hostOutAmounts;
        hostSnapSeq++;
        notifyBlockUpdate();
    }

    // ------------------------------------------------------------------ 快照：功率（EU/t）

    /**
     * 某一侧能量的<b>实测吞吐</b>：{@code IO.IN} 取各能源仓的 {@code getInputPerSec()}，
     * {@code IO.OUT} 取各动力仓的 {@code getOutputPerSec()}，<b>求和</b>。
     *
     * <p>单位是 <b>"上一个 20 tick 窗口内累加的 EU"</b>（见 {@link #POWER_WINDOW_TICKS} 的
     * javap 实证说明），除以 20 就是 {@code EU/t}。
     *
     * <p>🔴 返回 {@code -1} 的两种情形（客户端画 {@code --}）：
     * ① capability 代理读不出来（抛异常）；② 这一侧<b>一个 {@link IEnergyContainer} 都没有</b>
     * —— 那是"本机没装仓"，速率无意义，宁可 {@code --} 也不写 0 冒充"零耗能"。
     */
    private long sumEnergyRatePer20(IO io, boolean input) {
        boolean any = false;
        long sum = 0L;
        try {
            final List<IRecipeHandler<?>> handlers = getCapabilitiesProxy().get(io, EURecipeCapability.CAP);
            if (handlers != null) {
                for (IRecipeHandler<?> handler : handlers) {
                    if (handler instanceof IEnergyContainer container) {
                        any = true;
                        // getInputPerSec()/getOutputPerSec() 是 IEnergyContainer 的 default 方法（返回 0），
                        // GTCEu 的 NotifiableEnergyContainer 覆写成"上一个 20 tick 窗口的累计"。
                        sum += input ? container.getInputPerSec() : container.getOutputPerSec();
                    }
                }
            }
        } catch (Throwable ignored) {
            return -1L;
        }
        return any ? sum : -1L;
    }

    /**
     * 采样环 → {@code EU/t}：{@code (环内有效样本之和) / (样本数 × 20)}。
     *
     * <p>①任一样本是 −1（这一轮读不到）⇒ 整个结果 −1，<b>不让"半次读数"变成数</b>；
     * ②{@code fill == 0} ⇒ −1（还没采过）。
     */
    private static long averagePerTick(long[] ring, int fill) {
        if (fill <= 0) {
            return -1L;
        }
        long sum = 0L;
        int n = 0;
        for (int i = 0; i < fill; i++) {
            if (ring[i] < 0L) {
                return -1L;
            }
            sum += ring[i];
            n++;
        }
        return n == 0 ? -1L : sum / (n * (long) POWER_WINDOW_TICKS);
    }

    // ------------------------------------------------------------------ 快照：单台模块的读数

    /**
     * 一台模块的读数（<b>纯数据</b>，不含任何世界访问）。
     *
     * <p>拆出来是为了能被<b>离线探针</b>直接构造并喂给 {@link #applyReading} ——
     * "给定模块状态 → 快照数组"这段映射必须能在没有游戏的情况下自证一遍。
     */
    static final class ModuleReading {

        int progressPct = -1;
        long euPerTick = -1L;
        long genPerTick = -1L;
        int parallel = -1;
        int threads = -1;
        int remainSec = -1;
        final int[] outItem = sentinelInts(OUT_PER_MODULE);
        final int[] outAmount = sentinelInts(OUT_PER_MODULE);

        /** 一条输出都没有（未知）⇒ 把条目数也置成 -1。 */
        ModuleReading noOutputs() {
            for (int k = 0; k < OUT_PER_MODULE; k++) {
                outItem[k] = -1;
                outAmount[k] = -1;
            }
            return this;
        }
    }

    /**
     * 把一台模块的读数写进第 {@code slot} 号扫描位的快照数组（<b>纯函数</b>，可离线自证）。
     *
     * <p>越界 / {@code null} 一律当作"未知"处理（写 {@code -1}），永不写 0 顶替。
     */
    static void applyReading(int slot, ModuleReading r,
                             byte[] pct, long[] eu, int[] par, int[] thr, int[] rem,
                             int[] outCount, int[] outItem, int[] outAmount) {
        if (slot < 0 || slot >= SNAP_SLOTS) {
            return;
        }
        final boolean known = r != null;
        pct[slot] = (byte) (known ? clampPct(r.progressPct) : -1);
        eu[slot] = known ? r.euPerTick : -1L;
        par[slot] = known ? r.parallel : -1;
        thr[slot] = known ? r.threads : -1;
        rem[slot] = known ? r.remainSec : -1;
        int count = 0;
        for (int k = 0; k < OUT_PER_MODULE; k++) {
            final int idx = slot * OUT_PER_MODULE + k;
            final int id = known ? r.outItem[k] : -1;
            final int amount = known ? r.outAmount[k] : -1;
            final boolean valid = id >= 0 && amount > 0;
            outItem[idx] = valid ? id : -1;
            outAmount[idx] = valid ? amount : -1;
            if (valid) {
                count++;
            }
        }
        outCount[slot] = known ? count : -1;
    }

    /** 百分比钳到 {@code -1..100}（{@code -1} 保持"未知"）。 */
    static int clampPct(int pct) {
        if (pct < 0) {
            return -1;
        }
        return Math.min(100, pct);
    }

    /**
     * 读一台模块的运行状态（<b>只在服务端跑</b>）。
     *
     * <p>一处异常 ⇒ 整台当作"未知"（全 {@code -1}），绝不半真半假。
     */
    static ModuleReading readModule(PrimordialModuleMachine module) {
        final ModuleReading r = new ModuleReading();
        try {
            final com.gregtechceu.gtceu.api.machine.trait.RecipeLogic logic = module.getRecipeLogic();
            if (logic == null) {
                return r;
            }
            final boolean working = logic.isWorking();
            // 2026-10-25 第二十七改：公式统一走 progressOf —— 它与**每 tick** 的轻量路径
            // （refreshProgressTick → moduleProgressOf）是同一个实现，否则面板会在
            // "快路径写的值"和"慢路径写的值"之间来回跳（同一格数字每 10 tick 抖一次）。
            final int[] pr = progressOf(logic.getProgress(), logic.getMaxProgress(), working);
            r.progressPct = pr[0];                    // −1 = 未知；0..100 = 真读数
            r.remainSec = pr[1];
            // 🔴 2026-10-25 第三十四改：lastRecipe 为空时回落 getLastOriginRecipe()（**只读**）。
            //    为什么：引擎路径（gtladditions MutableRecipesLogic）装配出的成品配方不一定写进 lastRecipe，
            //    而 getLastOriginRecipe() 是 GT 自己的兜底字段（javap 有 getter）⇒ 只认 lastRecipe 会
            //    导致"在跑却拿不到配方"⇒ 耗能/输出整列 `--`（用户这次的两条回归之一）。
            //    ⚠️ lastRecipe 非空时行为与改动前逐字相同；last 因此不能再是 final。
            GTRecipe last = logic.getLastRecipe();
            if (last == null) {
                last = logic.getLastOriginRecipe();
            }
            if (last != null) {
                final long in = com.gregtechceu.gtceu.api.recipe.RecipeHelper.getInputEUt(last);
                final long out = com.gregtechceu.gtceu.api.recipe.RecipeHelper.getOutputEUt(last);
                // 发电模块的耗电侧是深负值（Integer.MIN_VALUE 语义）⇒ 钳到 0，不让它污染"总耗能"
                r.euPerTick = working ? Math.max(0L, in) : 0L;
                r.genPerTick = working ? Math.max(0L, out) : 0L;
                final java.util.List<ItemStack> outs =
                        com.gregtechceu.gtceu.api.recipe.RecipeHelper.getOutputItems(last);
                if (outs != null) {
                    int k = 0;
                    for (ItemStack stack : outs) {
                        if (k >= OUT_PER_MODULE) {
                            break;
                        }
                        if (stack == null || stack.isEmpty()) {
                            continue;
                        }
                        final int id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getId(
                                stack.getItem());
                        if (id < 0) {
                            continue;
                        }
                        r.outItem[k] = id;
                        r.outAmount[k] = stack.getCount();
                        k++;
                    }
                }
            } else {
                // 拿不到配方 ⇒ EU/t 未知；但"没在跑"时 0 EU/t 是真读数
                r.euPerTick = working ? -1L : 0L;
                r.genPerTick = working ? -1L : 0L;
            }
            r.parallel = Ints.saturatedCast(module.getCurrentParallel());
            r.threads = module.getCrossRecipeThreads();
        } catch (Throwable ignored) {
            return new ModuleReading();                        // 全 -1
        }
        return r;
    }

    // ------------------------------------------------------------------ 快照：原因码（第三十三改）

    /**
     * 一台模块<b>这一拍为什么没在跑</b>（0/1/2/3/4，见 {@link #REASON_NONE}…{@link #REASON_BLOCKED}）。
     *
     * <h2>🔴 每条判据的信号来源（没有一条是猜的）</h2>
     * <table border="1">
     *   <tr><th>码</th><th>含义</th><th>信号（类#方法/字段）</th></tr>
     *   <tr><td>1</td><td>正在跑</td><td>{@code RecipeLogic#isWorking()}
     *       （= {@code getStatus() == Status.WORKING}；字节码：{@code handleRecipeWorking} 在
     *       {@code handleTickRecipe} 成功那一刻 {@code setStatus(WORKING)} 再 {@code progress++}）</td></tr>
     *   <tr><td>2</td><td>缺电</td>
     *       <td>① gtlcore 已发布原因键 = {@link #KEY_EU_IN}（= {@code RecipeResult.FAIL_NO_ENOUGH_EU_IN}，
     *       键名 javap 实证）；② 没有已发布原因但确实在等：{@link #energyShortFor} 实测
     *       「这一 tick 要的电 &gt; 仓里现存的电」</td></tr>
     *   <tr><td>3</td><td>输出满</td><td>已发布原因键 = {@link #KEY_OUTPUT}
     *       （= {@code RecipeResult.FAIL_OUTPUT}）</td></tr>
     *   <tr><td>4</td><td>被拦</td><td>有已发布原因但是其它键（电压等级 / 输入不足 / 温度 / 超净 /
     *       本工程的类型闸与额外挂载原因 → 都是 {@code RecipeResult.fail(Component)}）；
     *       或 {@code isWaiting()} 却查不到具体键</td></tr>
     *   <tr><td>0</td><td>没在跑</td><td>既不在工作也不在等（{@code IDLE} / {@code SUSPEND}），
     *       或任何读取抛异常</td></tr>
     * </table>
     *
     * <h2>为什么读"已发布状态"而不是自己重算</h2>
     * {@code handleRecipeWorking()} 的字节码（{@code javap -c RecipeLogic}）已经把四种结局分开了：
     * <pre>
     *   checkConditions 失败        ⇒ setWaiting(actionResult.reason())   ← 被拦（条件）
     *   handleFuelRecipe 失败       ⇒ setWaiting("gtceu.recipe_logic.insufficient_fuel")
     *   handleTickRecipe 失败       ⇒ setWaiting(actionResult.reason())   ← 缺电 / 输出满 都在这里
     *   然后  if (isWaiting()) doDamping();                              ← 🔴 damp 的触发点
     * </pre>
     * 也就是说 <b>{@code doDamping()} 的触发条件就是 {@code isWaiting()}</b>（不是某个独立的"缺电"标志：
     * {@code isHasNotEnoughEnergy()} 的字节码是 {@code return isWaiting();}）⇒ 想区分"缺电"与"输出满"
     * <b>只能</b>看那条 reason；而 gtlcore 的 {@code RecipeResult.of(...)} 正是把那条 reason 落到
     * {@code IRecipeStatus} 上（Jade / 机器 GUI 读的同一份）⇒ 复用它 = 复用 damp 现场的同一条证据，
     * 不需要 mixin、也不需要每帧遍历机器。
     */
    static byte reasonOf(PrimordialModuleMachine module) {
        try {
            final com.gregtechceu.gtceu.api.machine.trait.RecipeLogic logic = module.getRecipeLogic();
            if (logic == null) {
                return REASON_NONE;
            }
            if (logic.isWorking()) {
                return REASON_WORKING;
            }
            // 只在这两种情形下信任"已发布原因"（= 它一定是【本轮】的）：
            //   ① 正在 waiting（每 tick 都在重写 reason）；
            //   ② 手上没有任何配方（本工程的 PrimordialModuleRecipeLogic#shanhai$publishFailReason
            //      写原因的闸门之一就是 getLastRecipe() == null，两条判据同源）。
            final boolean fresh = logic.isWaiting() || logic.getLastRecipe() == null;
            final String key = fresh ? publishedFailKey(logic) : null;
            if (KEY_EU_IN.equals(key)) {
                return REASON_NO_POWER;
            }
            if (KEY_OUTPUT.equals(key)) {
                return REASON_OUTPUT_FULL;
            }
            if (key != null) {
                return REASON_BLOCKED;
            }
            if (!logic.isWaiting()) {
                return REASON_NONE;
            }
            // 在等、但没有已发布原因（例如原生链只 setWaiting 了一个 Component、没走 RecipeResult）
            // ⇒ 用能量实读兜最后一层；仍然说不清 ⇒ 被拦。
            return energyShortFor(module, logic.getLastRecipe()) ? REASON_NO_POWER : REASON_BLOCKED;
        } catch (Throwable t) {
            return REASON_NONE;                      // 读不到 ⇒ 0（客户端不画标签，绝不编）
        }
    }

    /**
     * 已发布配方状态的<b>原因键</b>；{@code null} = 没有失败原因（或这一侧不是 gtlcore 的
     * {@code IRecipeStatus} 实现），{@code ""} = 有失败但不是可翻译文本（= 有原因、认不出是哪条）。
     *
     * <p>先看 {@code getWorkingStatus()}（"在跑却卡住"的那份），再看 {@code getRecipeStatus()}
     * （"这一轮没找到/没跑起来"的那份）—— 两者的写入方分别是 gtlcore 的 RecipeLogic mixin 与
     * 本工程的 {@code shanhai$publishFailReason}。
     */
    private static String publishedFailKey(com.gregtechceu.gtceu.api.machine.trait.RecipeLogic logic) {
        try {
            if (!(logic instanceof org.gtlcore.gtlcore.api.machine.trait.IRecipeStatus status)) {
                return null;                         // 上游换实现 ⇒ 查不到（走兜底，不编）
            }
            final String working = resultKeyOf(status.getWorkingStatus());
            if (working != null) {
                return working;
            }
            return resultKeyOf(status.getRecipeStatus());
        } catch (Throwable t) {
            return null;
        }
    }

    /** {@code RecipeResult} → 原因键；{@code null} = 成功/没有；{@code ""} = 有失败但认不出键。 */
    private static String resultKeyOf(org.gtlcore.gtlcore.api.recipe.RecipeResult result) {
        if (result == null || result.isSuccess()) {
            return null;
        }
        try {
            final net.minecraft.network.chat.Component reason = result.reason();
            if (reason == null) {
                return "";
            }
            if (reason.getContents()
                    instanceof net.minecraft.network.chat.contents.TranslatableContents translatable) {
                return translatable.getKey();
            }
            return "";
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 「这一 tick 要的电 &gt; 仓里现存的电」——<b>只读</b>，用作没有已发布原因时的兜底。
     *
     * <p>语义：{@code need = RecipeHelper.getInputEUt(recipe)}（该配方每 tick 的输入 EU，正是面板
     * ⑦「耗能EU/t」那一列的同源读数）；{@code have = Σ 各能源仓 getEnergyStored()}（饱和加，避免
     * 无限仓的 {@code Long.MAX_VALUE} 溢出成负数）。<b>只在真不够时返回 true</b>（充要条件不敢说，
     * 充分条件敢说：连一 tick 的电都付不出，一定跑不动）。
     */
    private static boolean energyShortFor(PrimordialModuleMachine module,
                                         com.gregtechceu.gtceu.api.recipe.GTRecipe recipe) {
        if (recipe == null) {
            return false;
        }
        try {
            final long need = com.gregtechceu.gtceu.api.recipe.RecipeHelper.getInputEUt(recipe);
            if (need <= 0L) {
                return false;                        // 不耗电（发电机 / 纯条件配方）⇒ 不是缺电
            }
            final List<IRecipeHandler<?>> handlers =
                    module.getCapabilitiesProxy().get(IO.IN, EURecipeCapability.CAP);
            if (handlers == null) {
                return false;
            }
            boolean any = false;
            long have = 0L;
            for (IRecipeHandler<?> handler : handlers) {
                if (!(handler instanceof IEnergyContainer container)) {
                    continue;
                }
                any = true;
                final long stored = container.getEnergyStored();
                if (stored <= 0L) {
                    continue;
                }
                have = (have > Long.MAX_VALUE - stored) ? Long.MAX_VALUE : have + stored;
            }
            return any && have < need;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------ 快照：tick 钩子

    /**
     * 机器加载：把 10 tick 的快照钩子挂上（<b>服务端</b>）。
     *
     * <p>用 {@code MetaMachine#subscribeServerTick(Runnable)}（GTCEu 现成的机器级订阅），
     * <b>不新挂</b> {@code MinecraftForge.EVENT_BUS.addListener}：那样要在 {@code ShanhaiMod}
     * 里加一行全局监听，且每 tick 扫全世界。这里只在本机加载期间每 tick 走一个计数判断。
     *
     * <p>🔴 2026-10-25 第二十四改：<b>不再只在 {@code onLoad()} 订阅</b>。理由（取证结论）：
     * {@code subscribeServerTick} 在 {@code isRemote()==true} 或 {@code getLevel()} 还不是
     * {@code ServerLevel} 时<b>只把 Runnable 塞进 {@code waitingToAdd}、不给方块打
     * {@code BlockProperties.SERVER_TICK=true} 的标记</b>（{@code javap -c MetaMachine
     * #subscribeServerTick} 偏移 27..121）⇒ 那一刻没挂上的订阅<b>永远不会被 tick</b>，
     * 而且<b>完全静默</b>。机器实例在"读档后重新成型 / 结构重建"时可能换过，所以除了
     * {@code onLoad()}，还要在 {@link #onStructureFormed()} 里再 arm 一次。
     */
    @Override
    public void onLoad() {
        super.onLoad();
        ensureSnapshotTick();
        logRecipeCounts();
    }

    /**
     * 🆕 2026-10-25（T1 的<b>可核对读数</b>）：把本机三个配方类型在<b>运行时配方表</b>里的条数打进日志。
     *
     * <h2>为什么需要这一行</h2>
     * 用户报「三个配方类型在<b>配方编辑器</b>里找不到」。原因不是类型没注册，而是
     * <b>类型注册了、但一条配方都没有</b> —— 而编辑器的类型列表是<b>从配方表现算</b>出来的
     * （{@code ShanhaiRecipeEditorWorkspace#buildTypeRows}：{@code ShanhaiRecipeReverseIndex.all()}
     * 里出现过的类型 ∪ 开机快照），零配方的类型在<b>两个来源里都不存在</b> ⇒ 界面上根本没有它。
     * 这条日志把"注册 / 配方数"两件事分开报出来，就是那条判据的读数：
     * <pre>
     *   改前： type=gtceu:vision_matter_decompression registered=true recipes=0   ⇒ 编辑器里看不到
     *   改后： type=gtceu:vision_matter_decompression registered=true recipes=1   ⇒ 编辑器里(1 条)、JEI 里都有
     * </pre>
     * 判据：把本机放下去（或读一个已有存档）后 grep {@code [SHANHAI-HOSTRECIPE]}。
     * 读的是 {@code RecipeManager#getRecipes()} 的全量拉平（与编辑器的列表来源同一条路），
     * <b>不是</b> GT 的输入索引（索引为空不代表配方不存在，见 {@code ShanhaiRecipeEditorWorkspaceCheck}
     * 里那条"空配方插不进树"的实证）。
     */
    private void logRecipeCounts() {
        if (isRemote()) {
            return;                     // 客户端那张表是同步下来的，读数以服务端为准
        }
        final Level level = getLevel();
        if (level == null || level.getRecipeManager() == null) {
            return;
        }
        try {
            final java.util.Collection<?> all = level.getRecipeManager().getRecipes();
            final com.gregtechceu.gtceu.api.recipe.GTRecipeType[] types = getRecipeTypes();
            for (com.gregtechceu.gtceu.api.recipe.GTRecipeType type : types) {
                if (type == null) {
                    continue;
                }
                int n = 0;
                for (Object o : all) {
                    if (o instanceof GTRecipe gt && gt.getType() == type) {
                        n++;
                    }
                }
                LOGGER.info("[SHANHAI-HOSTRECIPE] type={} registered=true recipes={}"
                                + "（0 ⇒ 配方编辑器里列不出这个类型，因为它的类型列表是从配方表现算的；"
                                + "≥1 ⇒ 编辑器与 JEI 都能看到）",
                        type.registryName, n);
            }
        } catch (Throwable t) {
            LOGGER.warn("[SHANHAI-HOSTRECIPE] 读配方表失败（不影响机器）: {}", t.toString());
        }
    }

    /**
     * 确保 10 tick 快照钩子挂在<b>本实例</b>上（幂等：已经有句柄就什么都不做）。
     *
     * <p>调用点：{@link #onLoad()}（读档）与 {@link #onStructureFormed()}（重新成型）。
     * 两处都要，因为控制器实例可能在两者之间换过 —— 这正是"服务端明明在跑、AR 却整片
     * {@code --} "最容易被忽略的那条断点。
     */
    private void ensureSnapshotTick() {
        if (isRemote() || snapTick != null) {
            return;
        }
        snapTick = subscribeServerTick(this::shanhai$snapshotTick);
        if (snapTick == null) {
            // subscribeServerTick 在 isRemote() 时返回 null；服务端正常不会走到这里。
            LOGGER.warn("[SHANHAI-HOSTSNAP] subscribeServerTick returned null @{} —— 快照不会跑",
                    getPos());
        }
    }

    /** 每 tick 一次；满 {@link #SNAPSHOT_INTERVAL_TICKS} 才做一次真正的（完整）快照。 */
    private void shanhai$snapshotTick() {
        if (isRemote()) {
            return;
        }
        snapTickCalls++;
        // ══════════════════════════════════════════════════════════════════════════════════
        // 🔴🔴 2026-10-25 第二十七改 —— **「进度条不流畅」的真凶就是下面那个 %10** 🔴🔴
        //
        //   用户原话（逐字）：「<b>不是，你原本 60 fps 它也不流畅啊（指的是进度条），
        //   那应该不是刷新率导致的</b>」—— 用户判断对了，客户端刷新率是替罪羊。
        //
        //   真正的时间线（改前）：
        //     服务端：每 10 tick（500 ms，2 Hz）才把 progress 采一次样、写进 @DescSynced 字段
        //             ⇒ 不管客户端多快，被观测的量本身只有 2 Hz 的新鲜度；
        //     客户端：每帧重建读的是 LDLib 的同步镜像 ⇒ 30 帧读到的是**同一个数**。
        //   ⇒ "60 fps 但不流畅"就是必然结果。进度是**连续量**，必须按 tick 喂。
        //
        //   处置（最小面 —— 别把整套快照提到每 tick）：
        //     · 每 tick 只刷【连续量】= 主机 进度/剩余 + 每个已装模块的 进度/剩余
        //       （refreshProgressTick：≤24 次方块实体查表 + 1 次主机配方逻辑读，**不碰**输出/功率/数组）；
        //     · 其余（预测输出、8 个数组、功率滑窗、容量、位图）保持 10 tick 的节奏，逻辑一个字没改。
        //
        //   为什么敢每 tick 写 @DescSynced 字段（不会变成每 tick 一个"全量包"）：
        //     LDLib 的同步是 **按字段脏位 + BitSet 打包**（javap 实证）：
        //       IAutoSyncBlockEntity.syncNow(force) → 对每个非 lazy 字段 IRef.update()
        //         → SPacketManagedPayload.of(entity, force) 里逐个 `IRef.isSyncDirty()`
        //           （偏移 53），只把脏字段 readSyncedField + clearSyncDirty，
        //           线上编码 = PacketIntLocation + BitSet + ITypedPayload[]
        //     ⇒ **只发这一 tick 真变了的字段**（进度数组 + 剩余数组 + 序号），
        //       不会连带重发那 8 个大数组。字节量见 temp\ar-tick2\报告.md §3。
        //   证据台账：temp\ar-debug\报告.md §1.9/§1.10（notifyBlockUpdate 是红石邻接更新、不是同步通道）
        //            + 本轮 javap 的 SPacketManagedPayload 字节码。
        // ══════════════════════════════════════════════════════════════════════════════════
        refreshProgressTick();
        if (getOffsetTimer() % SNAPSHOT_INTERVAL_TICKS != 0L) {
            return;
        }
        refreshHostRuntimeSnapshot();
    }

    // ------------------------------------------------------------------ 快照：每 tick 的连续量（进度）

    /** {@code {−1, −1}}：进度/剩余"未知"（面板画 {@code --}）。<b>只读，绝不改这个数组。</b> */
    private static final int[] PROGRESS_UNKNOWN = {-1, -1};
    /** {@code {0, 0}}：未开工的<b>真读数</b>（0% / 剩 0 s），不是占位。 */
    private static final int[] PROGRESS_ZERO = {0, 0};

    /**
     * 「tick 进度 → {百分比, 剩余秒}」的<b>唯一实现</b>（三处共用，改口径只改这里）。
     *
     * <pre>
     *   max &gt; 0 ⇒ pct = clamp(progress*100/max, 0..100)，remain = max(0,(max−progress)/20)
     *   max ≤ 0 ⇒ 在跑却没配方 ⇒ 未知(−1)；没在跑 ⇒ 真读数 0
     * </pre>
     *
     * <p>调用点：{@link #refreshProgressTick()}（每 tick，主机级 + 模块级）、
     * {@link #refreshHostRuntimeSnapshot$body()}（10 tick，主机级）、{@link #readModule}（10 tick，模块级）。
     * <b>三处必须同口径</b>，否则面板会在"快路径写的值"和"慢路径写的值"之间来回跳。
     *
     * @return 长度 2 的<b>新</b>数组 {@code {pct, remainSec}}（−1 = 未知）
     */
    static int[] progressOf(int progress, int maxProgress, boolean working) {
        if (maxProgress > 0) {
            final int pct = (int) Math.max(0L, Math.min(100L, (long) progress * 100L / maxProgress));
            return new int[]{pct, Math.max(0, (maxProgress - progress) / 20)};
        }
        // 没有配方：未开工时"进度 0% / 剩 0 s"是**真读数**，不是占位；在跑却读不到 max ⇒ 未知
        return working ? PROGRESS_UNKNOWN : PROGRESS_ZERO;
    }

    /**
     * <b>每 tick</b>刷新「连续量」—— 只碰 4 个进度字段，别的字段一个都不动。
     *
     * <p>写哪 4 个：{@code hostRecipeProgressPct} / {@code hostRecipeRemainSec}（主机一节）、
     * {@code hostModProgressPct} / {@code hostModRemainSec}（⑦ 模块表）。
     *
     * <h2>三条纪律</h2>
     * <ol>
     *   <li><b>懒复制</b>：只有当某个槽的值真的变了才 {@code clone()} 一次数组、换引用再写。
     *       不每 tick 无条件 new —— 那是白分配，而且会让 LDLib 每 tick 都判脏、白发包；</li>
     *   <li><b>空转一个字节都不发</b>：{@code changed == false} ⇒ 直接 return（机器没在跑时
     *       进度恒 0，本来就不该有流量）；</li>
     *   <li><b>读不到 ≠ 0</b>：查表/读配方抛异常 ⇒ 写 {@code -1}（面板画 {@code --}），
     *       绝不拿 0 顶替。</li>
     * </ol>
     *
     * <p>🔴 <b>不调 {@code notifyBlockUpdate()}</b>：字节码实证它是红石/比较器邻接更新
     * （{@code Level.updateNeighborsAt}），<b>不是同步通道</b>（{@code temp\ar-debug\报告.md} §1.9）。
     * 每 tick 调它 = 20 次/秒的邻接更新噪声，对"字段下发"一点帮助都没有；
     * 真正下发靠 LDLib 的每 tick 脏字段轮询（§1.10 + 本轮 {@code SPacketManagedPayload} javap）。
     */
    private void refreshProgressTick() {
        final Level level = getLevel();
        if (level == null) {
            return;
        }
        boolean changed = false;

        // ── ① 主机级进度 / 剩余（与 10 tick 的完整快照同一个 progressOf）────────────────
        try {
            final com.gregtechceu.gtceu.api.machine.trait.RecipeLogic logic = getRecipeLogic();
            if (logic != null) {
                final int[] pr = progressOf(logic.getProgress(), logic.getMaxProgress(), logic.isWorking());
                if (pr[0] != hostRecipeProgressPct) {
                    hostRecipeProgressPct = pr[0];
                    changed = true;
                }
                if (pr[1] != hostRecipeRemainSec) {
                    hostRecipeRemainSec = pr[1];
                    changed = true;
                }
            }
        } catch (Throwable ignored) {
            // 读不到就**保持上一次的值**（不写 -1）：每 tick 抖一下"未知"比"稍旧的真值"更糟；
            // 真的长期读不到时，由 10 tick 的完整快照统一写 -1（那条路径有 try/catch + 日志）。
        }

        // ── ② 模块级进度 / 剩余（只扫权威位图置位的槽 = 真的装了模块的槽）──────────────
        final long mask = hostModuleMask;
        if (mask != 0L) {
            final BlockPos[] positions = getModuleScanPositions();
            byte[] pct = null;                       // 懒复制：真变了才换新数组
            int[] rem = null;
            for (int i = 0; i < SNAP_SLOTS && i < positions.length; i++) {
                if ((mask & (1L << i)) == 0L) {
                    continue;                        // 空槽：保持 -1（由完整快照维护，本来就是 -1）
                }
                final int[] pr = slotProgressAt(level, positions[i]);
                final int p = clampPct(pr[0]);        // -1 保持 -1；0..100 原样
                if (p == hostModProgressPct[i] && pr[1] == hostModRemainSec[i]) {
                    continue;
                }
                if (pct == null) {
                    pct = hostModProgressPct.clone();
                    rem = hostModRemainSec.clone();
                }
                pct[i] = (byte) p;
                rem[i] = pr[1];
                changed = true;
            }
            if (pct != null && rem != null) {
                hostModProgressPct = pct;
                hostModRemainSec = rem;
            }
        }

        if (!changed) {
            return;                                  // 空转：一个字节都不发
        }
        hostSnapSeq++;
        snapProgressPushes++;
    }

    /**
     * 某个扫描位的模块「进度 / 剩余」（每 tick 的轻量路径入口）。
     *
     * <p>三种"拿不到"一律回 {@link #PROGRESS_UNKNOWN}（面板画 {@code --}），<b>绝不写 0</b>：
     * ①方块实体查不到 / 不是模块；②模块没成型或没连上本机（与 {@code refreshHostRuntimeSnapshot$body}
     * 的判据逐字相同）；③读配方逻辑抛异常。
     */
    private int[] slotProgressAt(Level level, BlockPos pos) {
        try {
            final MetaMachine machine = MetaMachine.getMachine(level, pos);
            if (machine instanceof PrimordialModuleMachine module
                    && module.isFormed() && module.getHost() == this) {
                return moduleProgressOf(module);
            }
        } catch (Throwable ignored) {
            // 读不到 ⇒ 未知（下面的 return 统一处理）
        }
        return PROGRESS_UNKNOWN;
    }

    /**
     * 一台模块的「进度 / 剩余」（每 tick 的轻量路径 —— <b>不碰</b>输出/功率/并行/线程）。
     *
     * <p>与 {@link #readModule} 的区别只有一个：{@code readModule} 每次还要查物品注册表、
     * 拼输出数组、读并行/线程（10 tick 一次无所谓，每 tick 就贵了）。
     */
    static int[] moduleProgressOf(PrimordialModuleMachine module) {
        try {
            final com.gregtechceu.gtceu.api.machine.trait.RecipeLogic logic = module.getRecipeLogic();
            if (logic == null) {
                return PROGRESS_UNKNOWN;
            }
            return progressOf(logic.getProgress(), logic.getMaxProgress(), logic.isWorking());
        } catch (Throwable t) {
            return PROGRESS_UNKNOWN;                 // 读不到 ⇒ --，绝不写 0
        }
    }

    @Override
    public void onUnload() {
        if (snapTick != null) {
            unsubscribe(snapTick);
            snapTick = null;
        }
        super.onUnload();
    }

    private static int clampInt(int value, int min, int max) {
        return value < min ? min : (value > max ? max : value);
    }

    /**
     * 6 个视界参数的<b>一行摘要</b>（给渲染侧那个每 5 秒一次的 {@code draw} 日志用）。
     *
     * <p>判据（用户进游戏后唯一需要的 grep）：玩家在面板里调完，这行里的数字必须跟着变。
     */
    public String hostFxParamsLine() {
        return "fx=" + hostFxEnabled
                + " 环=" + hostRingDegPerSec + "°/s"
                + " 上舰=+" + hostShipUpDegPerSec + "°/s"
                + " 下舰=−" + hostShipDownDegPerSec + "°/s"
                + " 洞半径=" + String.format(Locale.ROOT, "%.1f", getHostBlackHoleRadiusBlocks())
                + "格(" + hostBlackHoleRadiusTenths + "×0.1)"
                + " 透镜=" + (hostLensEnabled ? "开" : "关");
    }

    public GuixuVisionCoreMachine(IMachineBlockEntity holder, Object... args) {
        super(holder, args);
        // 覆写 onContentsChanged ⇒ "槽一变就重算并推同步"，不等任何 tick 订阅。
        // 客户端也会收到这个回调（GUI 槽的本地交互），所以必须挡住 isRemote() 那一侧。
        this.levelOverrideSlot = new NotifiableItemStackHandler(this, 1, IO.NONE, IO.NONE) {
            @Override
            public void onContentsChanged() {
                super.onContentsChanged();
                if (!isRemote()) {
                    refreshMatterLevelOverride();
                }
            }
        }.setFilter(PrimordialModuleMachine::isMatterModuleStack);

        // 「额外挂载槽 ×3」（2026-10-25 T3）：与模块侧**同构造**（3 格、IO.NONE/IO.BOTH、不设过滤器）。
        // ⚠️ 构造器里只给形状，不读任何别的字段（基类的 createRecipeLogic 在更早的 super(holder) 里就跑过了，
        //    那边只 new 逻辑、不碰这三个槽 ⇒ 顺序安全；这条坑见 PrimordialOmegaEngineMachine#createRecipeLogic）。
        this.extraMountSlots = new NotifiableItemStackHandler(this, EXTRA_MOUNT_SLOT_COUNT, IO.NONE, IO.BOTH);
    }

    // ═══════════════════════════════════════════════════════════════════════════════════
    //  配方逻辑（2026-10-25 T3）：换成"带额外挂载槽闸门"的那一支
    // ═══════════════════════════════════════════════════════════════════════════════════

    /**
     * 🔴 <b>引擎替换点</b>：基类在这里 {@code new PrimordialEngineRecipeLogic(this)}（javap 实证，
     * {@code PrimordialOmegaEngineMachine#createRecipeLogic} 只有一个 ctor 实参），
     * 本机换成 {@link GuixuVisionCoreRecipeLogic} —— 它只多一件事：在
     * {@code checkRecipe} 里插一段「额外挂载槽 ×3」闸门，<b>判定核与模块同一个</b>
     * （{@code ShanhaiExtraMountGate} → {@code ShanhaiHeatGate}）。
     *
     * <p>⚠️ {@code args} 原样不转发（与基类同一条纪律）：那个逻辑的构造器只吃机器自己，
     * 而本方法的调用点在基类构造器里、字段都还没初始化 ⇒ 构造器里不许读本类的任何字段。
     */
    @Override
    protected com.gregtechceu.gtceu.api.machine.trait.RecipeLogic createRecipeLogic(Object... args) {
        return new GuixuVisionCoreRecipeLogic(this);
    }

    // ═══════════════════════════════════════════════════════════════════════════════════
    //  「额外挂载槽 ×3」—— 读口（GUI / tooltip / 判定核三方共用，不许各算一份）
    // ═══════════════════════════════════════════════════════════════════════════════════

    /** 某一格的物品（越界返回空栈）。 */
    @NotNull
    public ItemStack getExtraMountStack(int index) {
        if (extraMountSlots == null || index < 0 || index >= EXTRA_MOUNT_SLOT_COUNT) {
            return ItemStack.EMPTY;
        }
        final ItemStack s = extraMountSlots.storage.getStackInSlot(index);
        return s == null ? ItemStack.EMPTY : s;
    }

    /** 某一格的数量（越界 0）。 */
    public int getExtraMountCount(int index) {
        return getExtraMountStack(index).getCount();
    }

    /** 三格现读物品（顺序 = 槽序号）—— 只给"槽里实际放的是什么"那句文案用。 */
    @NotNull
    public List<ItemStack> getExtraMountStacks() {
        final List<ItemStack> out = new java.util.ArrayList<>(EXTRA_MOUNT_SLOT_COUNT);
        for (int i = 0; i < EXTRA_MOUNT_SLOT_COUNT; i++) {
            out.add(getExtraMountStack(i));
        }
        return out;
    }

    /**
     * 三格现读内容（{@link com.shanhai.common.heat.ShanhaiHeatGate.SlotContent}），<b>逐格缓存</b>。
     *
     * <p>🔴 为什么必须缓存：{@code ShanhaiHeatSources#slotContentOf} 要查注册表，
     * 而 {@code checkRecipe} 是<b>每次找配方都调</b>的（每 tick 每线程）⇒ 不缓存就是每 tick 几十次注册表查询。
     * 缓存键 = 三格 {@code ItemStack} 的"物品 + 数量"，命中就复用上一份。
     *
     * <p>与模块侧 {@code PrimordialModuleMachine#getExtraMountContents} 同一套写法（同一语义、同一退化路径），
     * 但<b>判定</b>不在本方法里 —— 判定只经 {@code ShanhaiExtraMountGate}。
     */
    @NotNull
    public List<com.shanhai.common.heat.ShanhaiHeatGate.SlotContent> getExtraMountContents() {
        final ItemStack[] now = new ItemStack[EXTRA_MOUNT_SLOT_COUNT];
        boolean hit = extraMountCache.size() == EXTRA_MOUNT_SLOT_COUNT
                && extraMountCacheKeys.length == EXTRA_MOUNT_SLOT_COUNT;
        for (int i = 0; i < EXTRA_MOUNT_SLOT_COUNT; i++) {
            now[i] = getExtraMountStack(i);
            if (hit) {
                final ItemStack old = extraMountCacheKeys[i];
                hit = old != null && ItemStack.matches(old, now[i]);
            }
        }
        if (hit) {
            return extraMountCache;
        }
        final List<com.shanhai.common.heat.ShanhaiHeatGate.SlotContent> computed =
                new java.util.ArrayList<>(EXTRA_MOUNT_SLOT_COUNT);
        for (int i = 0; i < EXTRA_MOUNT_SLOT_COUNT; i++) {
            computed.add(com.shanhai.common.heat.ShanhaiHeatSources.slotContentOf(now[i]));
        }
        extraMountCache = computed;
        extraMountCacheKeys = now;
        return computed;
    }

    /**
     * 这台机器的三格<b>允不允许当热力源</b>（{@code ebf_temp} / {@code SCTier} 那两条门槛）。
     *
     * <p>用的是<b>同一个</b>白名单判定 {@code ShanhaiHeatGate#hasHeatSlot}（模块侧
     * {@code PrimordialModuleMachine#canUseExtraMountAsHeatSource} 也是它）——
     * 归墟的机器 id 不在那份白名单里 ⇒ 恒 {@code false} ⇒ <b>热力门槛不加到本机上</b>
     * （用户点单的是"配方条件（cleanroom/gravity/dimension/research）复写"，不是给主机加炉温门槛）。
     * <p>要放开只需把本机 id 加进 {@code ShanhaiHeatGate#HEAT_SLOT_MACHINE_IDS}（一处，闸门自动跟着走）。
     */
    public boolean canUseExtraMountAsHeatSource() {
        final var def = getDefinition();
        if (def == null || def.getId() == null) {
            return false;
        }
        return com.shanhai.common.heat.ShanhaiHeatGate.hasHeatSlot(def.getId().toString());
    }

    // ═══════════════════════════════════════════════════════════════════════════════════
    //  🆕 2026-10-25 收尾：「复写」—— 把这 3 格【供源给挂在身上的模块】
    //
    //  用户原话（逐字）：「**开**，而且我说的是**复写**，归墟视界枢核的配方虽然没有，
    //  **但是他模块的配方需要啊**」
    //  ⇒ 模块跑任何配方时，额外条件（cleanroom / gravity / dimension / research ＋ 热力
    //    ebf_temp / SCTier）的可用源 = 模块自己的 3 格 ∪ 本机这 3 格，取两者中更满足的那一个。
    // ═══════════════════════════════════════════════════════════════════════════════════

    /**
     * 模块侧经 {@code module.getHost() instanceof ShanhaiHostMountSource} 取到本方法。
     *
     * <p>🔴 <b>只有归墟实现这个接口</b> ⇒ 老主机 {@code PrimordialOmegaEngineMachine} 不实现
     * ⇒ 模块拿到 {@code null} ⇒ 老主机与其它机器上的模块行为<b>一个数都不变</b>；
     * 也不需要动基类（{@code common/machine} 不在本轮的写入范围里）。
     *
     * <p>为什么不抛异常：这是模块每次找配方都会走的路（每 tick 每线程）⇒ 实现必须"拿不到就当空"。
     */
    @Override
    public java.util.List<com.shanhai.common.heat.ShanhaiHeatGate.SlotContent> hostExtraMountContents() {
        try {
            return getExtraMountContents();
        } catch (Throwable t) {
            return java.util.List.of();      // 供源坏了 ⇒ 模块退回"只有自己那 3 格"（原行为）
        }
    }

    /** 给模块日志用的显示名（`host=` 那一栏）。 */
    @Override
    public String hostExtraMountSourceName() {
        return "归墟视界枢核";
    }

    /** 一行可 grep 的三格读数（日志与 tooltip 共用）。 */
    @NotNull
    public String describeExtraMounts() {
        final List<com.shanhai.common.heat.ShanhaiHeatGate.SlotContent> contents = getExtraMountContents();
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < contents.size(); i++) {
            sb.append('[').append(i + 1).append(']').append(contents.get(i).describe()).append(' ');
        }
        return sb.toString().trim();
    }

    /**
     * 三格各自 tooltip —— <b>与模块侧逐句同源</b>（{@code ShanhaiHeatSources#slotContentOf} 现读，
     * 不写死任何"放什么有用"的文案；放错了就照实说"不是任何一种挂载物"）。
     */
    @NotNull
    public Component[] extraMountTooltips(int index) {
        final ItemStack stack = getExtraMountStack(index);
        final com.shanhai.common.heat.ShanhaiHeatGate.SlotContent c =
                com.shanhai.common.heat.ShanhaiHeatSources.slotContentOf(stack);
        if (c.isBlank()) {
            return new Component[]{
                    Component.literal("§e额外挂载槽 " + (index + 1) + " / " + EXTRA_MOUNT_SLOT_COUNT),
                    Component.literal("§7可放：维护仓（超净间/重力）· 维度碎片 · 创造模式数据访问仓 · 线圈 / 恒星热力容器"),
                    Component.literal("§7一个条件占一格；热力源要 §f放满 "
                            + com.shanhai.common.heat.ShanhaiHeatGate.REQUIRED_COUNT + " 个§7 才生效")};
        }
        return new Component[]{
                Component.literal("§e额外挂载槽 " + (index + 1) + " / " + EXTRA_MOUNT_SLOT_COUNT),
                Component.literal("§7现读：" + c.describe()),
                Component.literal(c.count >= com.shanhai.common.heat.ShanhaiHeatGate.REQUIRED_COUNT
                        ? "§a已放满，可以满足对应条件"
                        : "§c只有 " + c.count + " 个（热力源需 §f"
                        + com.shanhai.common.heat.ShanhaiHeatGate.REQUIRED_COUNT + "§c 个）")};
    }

    // ------------------------------------------------------------------ 模块等级复写（门控 + 出口）

    /**
     * 门控：槽里<b>恰好 64 个同种物质模块</b> ⇒ 该模块的等级（1..17）；否则 0（未生效）。
     *
     * <p>槽只有 1 格、一个 {@code ItemStack} 按定义就是一种物品，且过滤器只放物质模块
     * ⇒ "单一物品类型"是<b>结构性保证</b>，这里只需判数量（与基类那个门控同一口径）。
     */
    public int matterLevelOverride() {
        if (levelOverrideSlot == null) {
            return 0;
        }
        final ItemStack stack = levelOverrideSlot.storage.getStackInSlot(0);
        return stack.getCount() == 64 ? PrimordialModuleMachine.getModuleLevelByStack(stack) : 0;
    }

    /** 重算门控并（仅在值变化时）推一次同步。 */
    private void refreshMatterLevelOverride() {
        final int now = matterLevelOverride();
        if (now == hostMatterLevelOverride) {
            return;
        }
        hostMatterLevelOverride = now;
        notifyBlockUpdate();
    }

    /**
     * <b>加载期自检</b>：本类的字段 holder 必须同时登记了本类两个新字段与父类的关键字段。
     *
     * <h2>为什么值得为它写一段代码</h2>
     * LDLib 的字段登记失败是<b>完全静默</b>的（编译通过、游戏不报错、字段永不落盘也永不同步）；
     * 唯一的现场症状是"玩家放满 64 个模块，关掉 GUI 再打开就回到空"。把这件事变成
     * <b>注册期抛异常 + 一行可 grep 的日志</b>，是唯一能在没有玩家操作的情况下抓住它的办法
     * （与 {@link GuixuHostGeometry#verify()} 同一条纪律）。
     *
     * @throws IllegalStateException 缺字段 / 注解没生效（说明 holder 的父链写错了，通常是"跳级"）
     */
    public static void assertFieldHolderComplete() {
        final java.util.Set<String> names = new java.util.HashSet<>();
        boolean overrideSlotPersisted = false;
        boolean overrideLevelSynced = false;
        for (final ManagedKey key : MANAGED_FIELD_HOLDER.getFields()) {
            names.add(key.getName());
            if ("levelOverrideSlot".equals(key.getName())) {
                overrideSlotPersisted = key.isPersist();
            }
            if ("hostMatterLevelOverride".equals(key.getName())) {
                overrideLevelSynced = key.isDestSync();
            }
        }
        final java.util.List<String> problems = new java.util.ArrayList<>();
        for (final String required : new String[]{"levelOverrideSlot", "hostMatterLevelOverride",
                "hostFormedSync", "matterBonusSlot", "limitedDuration", "matterBonusLevel",
                // 🔴 2026-10-25 第十九改：6 个视界参数（面板可调）也必须逐个登记，
                //    漏一个的表现是"面板能改、画面不动、退出重进回默认"，且【完全静默】。
                "hostFxEnabled", "hostRingDegPerSec", "hostShipUpDegPerSec",
                "hostShipDownDegPerSec", "hostBlackHoleRadiusTenths", "hostLensEnabled",
                // 🔴 2026-10-25 第二十一改：AR 遮罩开关（必须落盘 + 同步）与模块位图（必须同步）
                "hostArMaskEnabled", "hostModuleMask",
                // 🔴 2026-10-25 第二十二改：AR 右侧信息栏那份运行快照（数组 + 序号 + 主机级汇总）。
                //    漏登记 = 后台永远画 --（反而是"安全"的那一边），但等于白做 ⇒ 也逐个点出来。
                "hostModProgressPct", "hostModEuPerTick", "hostModParallel", "hostModThreads",
                "hostModRemainSec", "hostSnapSeq", "hostRecipeProgressPct", "hostRecipeRemainSec",
                "hostRecipeParallel", "hostRecipeThreads", "hostEuPerTick", "hostTotalOutputCount",
                "hostTotalOutputKinds", "hostTotalEuUsedPerTick", "hostTotalEuGenPerTick",
                "hostInstalledModules",
                // 🔴 2026-10-25 第二十三改：输出（数字 id 通道）+ 侧栏那几项（能源仓/动力仓的已存）
                "hostModOutCount", "hostModOutItem", "hostModOutAmount",
                "hostOutItem", "hostOutAmount", "hostOutKinds",
                "hostEnergyInStored", "hostEnergyInCapacity",
                "hostEnergyOutStored", "hostEnergyOutCapacity",
                // 🔴 2026-10-25 第二十八改（T3）：额外挂载槽 ×3 —— 不落盘的表现是"放满 64 个、
                //    关掉 GUI 再打开就回到空"，且完全静默 ⇒ 必须逐个登记。
                "extraMountSlots"}) {
            if (!names.contains(required)) {
                problems.add("缺字段 " + required);
            }
        }
        if (!overrideSlotPersisted) {
            problems.add("levelOverrideSlot 没有落盘（@Persisted 未登记）");
        }
        if (!overrideLevelSynced) {
            problems.add("hostMatterLevelOverride 没有同步（@DescSynced 未登记）");
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("[SHANHAI-HOST] 字段 holder 自检失败：" + problems
                    + " —— 这会导致「编译通过、游戏不报错、字段永不落盘也永不同步」。"
                    + "最常见原因是 MANAGED_FIELD_HOLDER 的父 holder 写成了跳级"
                    + "（必须 PrimordialOmegaEngineMachine.managedFieldHolder()，不能直接用"
                    + " WorkableElectricMultiblockMachine.MANAGED_FIELD_HOLDER）。实际登记的字段 = " + names);
        }
        LOGGER.info("[SHANHAI-HOST] 字段 holder 自检通过：{} 个字段（含本类 levelOverrideSlot/hostMatterLevelOverride"
                + "/6 个视界参数 hostFxEnabled/hostRingDegPerSec/hostShipUpDegPerSec/hostShipDownDegPerSec/"
                + "hostBlackHoleRadiusTenths/hostLensEnabled 与父类 matterBonusSlot/limitedDuration/matterBonusLevel）",
                names.size());
    }

    /**
     * 覆写基类的「模块等级复写」出口：模块侧 {@code getHostMatterLevelOverride()} 读的就是这个值。
     *
     * <p>返回 0 = 没有复写 ⇒ 模块侧 {@code max(自己, 0) == 自己} ⇒ 与今天逐位一致。
     * 服务端每次读都按槽内<b>实况</b>重算（不存在"要等某个 tick 才生效"的延迟）；
     * 客户端读 {@code @DescSynced} 镜像。
     */
    @Override
    public int getHostMatterLevelOverride() {
        if (!isRemote()) {
            refreshMatterLevelOverride();
            return hostMatterLevelOverride;
        }
        return hostMatterLevelOverride;
    }

    /**
     * 把「模块等级复写」槽画进 GUI —— 位置在基类那个物质模块槽的<b>正下方</b>。
     *
     * <p>基类 {@link PrimordialOmegaEngineMachine#createUIWidget()} 把物质模块槽放在
     * {@code (width-30, height-68)}（= (160, 57)）；本槽放在 {@code (width-30, height-48)}（= (160, 77)），
     * 仍在滚动区 {@code (4,4,182,117)} 之内、不越界。
     *
     * <p>🔴 少了这个覆写，玩家在界面上<b>找不到</b>这个槽 ⇒ 复写永远是 0，
     * <b>而且不报任何错</b>（与基类那个槽同一个坑，见基类方法的 javadoc）。
     */
    @Override
    public Widget createUIWidget() {
        final Widget widget = super.createUIWidget();
        if (widget instanceof WidgetGroup group) {
            final var size = group.getSize();
            final SlotWidget overrideSlot = new SlotWidget(levelOverrideSlot.storage, 0,
                    size.width - 30, size.height - 48, true, true);
            overrideSlot.setBackground(SlotWidget.ITEM_SLOT_TEXTURE);
            overrideSlot.setHoverTooltips(
                    Component.literal("§5§l模块等级复写槽"),
                    Component.literal("§7只收「山海的神人私货」的 17 种物质模块"),
                    Component.literal("§7必须放满 §b64§7 个§b同一种§7模块才生效"),
                    Component.literal("§7生效后用它的等级复写本机 24 个模块的"),
                    Component.literal("§7物质模块等级（§f并行§7 ＋ §f配方等级需求§7）"),
                    Component.literal("§8模块自己装的等级更高时，以模块自己的为准"));
            group.addWidget(overrideSlot);
        }
        return widget;
    }

    // ------------------------------------------------------------------ 24 个模块槽位

    /** 24 个槽位的世界坐标；每次实时算（换朝向/换位置都跟着变，不做跨朝向缓存）。 */
    @Override
    public BlockPos[] getModuleScanPositions() {
        return GuixuHostGeometry.slotPositions(getPos(), getFrontFacing());
    }

    /** 本机 24 个模块位（基类常量 {@code MAX_MODULE_COUNT = 16} 是原始终焉引擎的）。 */
    @Override
    public int getMaxModuleCount() {
        return GuixuHostGeometry.SLOT_COUNT;
    }

    // ------------------------------------------------------------------ 成型日志

    /**
     * 成型：先让基类做一次调和式重扫（断失效、补新增），再打<b>一行</b>可 grep 的日志。
     *
     * <p>日志格式（一行，前缀 {@code [SHANHAI-HOST]}）：
     * <pre>
     * [SHANHAI-HOST] 归墟视界枢核 成型 @(x,y,z) 朝F | 槽位 24 | 回连 N/24 | #0[外环E0](x,y,z)=&lt;方块/机器id&gt;[已连] | …
     * </pre>
     * 槽位号与 {@link GuixuHostGeometry#slots()} 的下标一致（0..15 外环、16..19 上柱、20..23 下柱）。
     */
    @Override
    public void onStructureFormed() {
        super.onStructureFormed();
        // 成型位（@DescSynced）：客户端渲染器的第二道成型判据，见 hostFormedSync 的 javadoc
        setHostFormedSync(true);
        // 成型时按槽内实况重算一次等级复写（存档读回后 @DescSynced 的值可能还没跟上）
        if (!isRemote()) {
            // 🔴 2026-10-25 第二十四改：重新成型 = 可能换过实例 / 换过 level，
            //    快照钩子必须重新 arm（幂等），否则 AR 面板整片 -- 且没有任何日志。
            ensureSnapshotTick();
            refreshMatterLevelOverride();
            // 成型时把"已装模块"位图重算一次（AR 面板的「已装 N / 24」就是读它）
            refreshModuleMask();
            // 成型后立刻推一次快照（不等 10 tick），让 AR 面板在成型那一刻就有数
            refreshHostRuntimeSnapshot();
        }
        logModuleSlots();
    }

    /** 结构失效：把成型位翻回 false 并推同步（渲染器据此停特效并恢复真方块）。 */
    @Override
    public void onStructureInvalid() {
        setHostFormedSync(false);
        // 结构没了 ⇒ 模块全断开（基类 onStructureInvalid → safeClearModules）⇒ 位图清零
        if (!isRemote()) {
            hostModuleMask = 0L;
            notifyBlockUpdate();
        }
        super.onStructureInvalid();
    }

    /**
     * 模块挂上来时刷新位图（服务端）。
     *
     * <p>⚠️ 为什么不写 {@code IModularMachineHost.super.addModule(module)}：本类<b>不是</b>该接口的
     * <b>直接</b>实现类（它由父类 {@code PrimordialOmegaEngineMachine} 实现），JVMS 不允许经由
     * "间接父接口"做 {@code super} 调用。这里逐字复刻 gtlcore 的 default 实现
     * （javap 实证：{@code getModuleSet():Set → add(Object) → return}，两条件反射原样）再加一句刷新。
     */
    @Override
    public <M extends IModularMachineModule<PrimordialOmegaEngineMachine, M>> void addModule(M module) {
        getModuleSet().add(module);
        refreshModuleMask();
    }

    /** 模块摘掉时刷新位图（同上：复刻 default 实现 + 刷新）。 */
    @Override
    public <M extends IModularMachineModule<PrimordialOmegaEngineMachine, M>> void removeModule(M module) {
        getModuleSet().remove(module);
        refreshModuleMask();
    }

    /**
     * 🔴 <b>本机不挂「机器模式」页</b>（用户 2026-10-25 原话：「它是跨配方种类并行，那就不需要选择」）。
     *
     * <h2>注入点证据（javap 逐指令，落在 {@code IFancyUIMachine#attachSideTabs(TabsWidget)}）</h2>
     * <pre>
     *   0: aload_1 / aload_0 / invokevirtual TabsWidget.setMainTab(IFancyUIProvider)   ← 主 tab，必须在
     *   5..18: this instanceof IRecipeLogicMachine → astore_2（本机命中）
     *  19: invokeinterface getRecipeTypes()[LGTRecipeType;
     *  25: arraylength      26: iconst_1      27: if_icmple 42      ← 长度 ≤ 1 就跳过整段
     *  30: new MachineModeFancyConfigurator
     *  35: aload_2         36: invokespecial &lt;init&gt;(IRecipeLogicMachine)
     *  39: invokevirtual TabsWidget.attachSubTab(IFancyUIProvider)    ← 就是这一步挂上「机器模式」
     *  42: self()/self() → CombinedDirectionalFancyConfigurator.of(…) → attachSubTab
     * </pre>
     * ⇒ 唯一入口就是<b>这个 default 方法自己</b>（{@code attachConfigurators} 只管侧栏那一条，
     * 与子页无关：{@code FancyMachineUIWidget#setupFancyUI} 分别调 {@code attachConfigurators}）⇒
     * 覆写它、<b>只跳过偏移 30..39 那一段</b>，其余两句逐字保留，就是最小且正确的改法
     * （不动 gtceu/gtlcore 的公共类）。
     *
     * <p>⚠️ 本机的三个配方类型仍在（{@code GuixuVisionCoreMachines} 未动）：
     * 少了这一页 ⇒ 玩家<b>选不了</b> {@code activeRecipeType}，它会保持存档里那个下标（默认 0
     * = 视界物质解压）。与用户"不需要选择"的口径一致；如果将来要"主机自己也跨类型跑"，
     * 那是引擎侧的事，不是这一页能给的。
     *
     * <p>🔴 <b>2026-10-25 本轮复核（结论：本机<b>不需要改</b>）</b>：这里是<b>无条件</b>跳过，不是
     * "按状态判着挂"。原因见上一段 —— 本机的 {@link #grantsCrossTypeParallel()} 恒 {@code true}，
     * 对主机来说这一页<b>从来没有</b>有意义的时刻 ⇒ 主机本来就<b>没有</b>这个子页。
     * 用户 2026-10-25 那句「在机器是跨配方种类并行的情况下隐藏掉」落地的<b>唯一</b>位置是模块侧
     * （{@code PrimordialModuleMachine#attachSideTabs}，判据纯函数 =
     * {@link PrimordialOmegaEngineMachine#shouldHideMachineModeTab}）—— 模块是 GTCEu 按
     * {@code getRecipeTypes().length > 1} 自动挂的，那才是需要挡的地方。
     * <p>若将来要"未成型 / 锁配方时把这一页还给玩家"，把下面那段改成
     * {@code if (!PrimordialOmegaEngineMachine.shouldHideMachineModeTab(crossTypeModeDisplayName())) { …挂… }}
     * 即可（判据现成，一个条件，不影响别处）。
     */
    @Override
    public void attachSideTabs(TabsWidget tabs) {
        if (tabs == null) {
            return;
        }
        tabs.setMainTab(this);
        // ⛔⛔ 2026-10-25 用户撤回（原话逐字）：「**我不是让你重新显示主机的这个页面**」
        //    ⇒ 上一版在这里挂 {@code MachineModeFancyConfigurator} 是**判错了动机**，已撤掉，
        //      回到改前状态（**不挂**「机器模式」子页）。
        //    🔴 仍然成立、写在这里免得下一个人再判错的事实：
        //      · 三个配方类型的**注册**没问题（`GuixuVisionCoreMachines` 的 `.recipeTypes(...)`）；
        //      · Jade 的「机器模式」行读 `definition.getRecipeTypes()`（3 个）⇒ 它上报的本来就是 3 种；
        //      · 用户说的"只显示 1 种"= **JEI 里只看到 1 个分类**，真因是另外两个类型
        //        **一条配方都没有**（读数：`[SHANHAI-HOSTRECIPE] … recipes=0`）。
        //        解法在**配方编辑器**（让它列出"已注册但 0 条"的类型 = 缺陷 E，落点
        //        `ShanhaiRecipeTypes#seedEditorRosterWithRegisteredTypes()`），**不是**挂这一页。
        // 朝向页照挂（本机是多方块控制器，of(...) 对它返回 null ⇒ 这里自然什么都不加；
        // 保留这一句是为了"只差机器模式"这件事在代码上一眼可见，而不是顺手删干净）
        final CombinedDirectionalFancyConfigurator directional =
                CombinedDirectionalFancyConfigurator.of(self(), self());
        if (directional != null) {
            tabs.attachSubTab(directional);
        }
        // 🆕 2026-10-25（T3）：「额外挂载」子页 —— 与模块**同一个控件、同一套位置**。
        tabs.attachSubTab(new ExtraMountPageProvider());
    }

    /**
     * 「额外挂载」子页：3 个槽（<b>布局逐字照抄模块侧</b> {@code PrimordialModuleMachine#ExtraMountPageProvider}
     * —— 用户原话「接口 gui 就和模块的一样」）。
     *
     * <p>尺寸 126×78 / {@code BACKGROUND_INVERSE} 底 / 标题 (8,8) /
     * 三个 {@code SlotWidget} 在 y=30、x = 8 + i×34 / 底注 (8,58) —— 六个数字一个不差，
     * 就是为了让玩家在两台机器上看到的是<b>同一个界面</b>，而不是"差不多"。
     */
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
                slot.setHoverTooltips(extraMountTooltips(i));
                group.addWidget(slot);
            }
            group.addWidget(new LabelWidget(8, 58, () -> "满足配方的额外要求（见各格说明）"));
            return group;
        }
    }

    /** 侧栏控件挂载（继承基类）—— 本机<b>不</b>覆写，只由基类按 {@code instanceof} 分形态。 */

    /** 打那一行槽位/回连日志（服务端跑；客户端没有世界时直接跳过）。 */
    private void logModuleSlots() {
        final Level level = getLevel();
        if (level == null || level.isClientSide()) {
            return;
        }
        final BlockPos controller = getPos();
        final Direction facing = getFrontFacing();
        final BlockPos[] positions = getModuleScanPositions();
        final StringBuilder detail = new StringBuilder(2048);
        int connected = 0;
        for (int i = 0; i < positions.length; i++) {
            final BlockPos pos = positions[i];
            final MetaMachine machine = MetaMachine.getMachine(level, pos);
            String id = "-";
            if (machine instanceof MultiblockControllerMachine controllerMachine) {
                id = String.valueOf(controllerMachine.getDefinition().getId());
            } else if (machine != null) {
                id = machine.getClass().getSimpleName();
            }
            boolean linked = false;
            if (machine instanceof IModularMachineModule<?, ?> module) {
                linked = module.isFormed() && module.getHost() == this;
            }
            if (linked) {
                connected++;
            }
            detail.append(" #").append(i).append('[').append(slotLabel(i)).append("](")
                    .append(pos.getX()).append(',').append(pos.getY()).append(',').append(pos.getZ()).append(")=")
                    .append(id).append(linked ? "[已连]" : "");
        }
        LOGGER.info("[SHANHAI-HOST] 归墟视界枢核 成型 @({},{},{}) 朝{} | 槽位 {} | 回连 {}/{} |{}",
                controller.getX(), controller.getY(), controller.getZ(), facing,
                positions.length, connected, positions.length, detail);
    }

    /** 槽位短标签：外环 E0..N3 / 上柱 UE..UN / 下柱 LE..LN（只用于日志可读性）。 */
    private static String slotLabel(int index) {
        final List<GuixuHostGeometry.Slot> slots = GuixuHostGeometry.slots();
        if (index < 0 || index >= slots.size()) {
            return "?";
        }
        final GuixuHostGeometry.Slot slot = slots.get(index);
        final String side = switch (slot.side()) {
            case EAST -> "E";
            case WEST -> "W";
            case SOUTH -> "S";
            case NORTH -> "N";
            default -> "?";
        };
        return switch (slot.kind()) {
            case OUTER_RING -> "外环" + side + slot.index();
            case UPPER_PILLAR -> "上柱" + side;
            case LOWER_PILLAR -> "下柱" + side;
        };
    }

    // ------------------------------------------------------------------ 材料增产（+5）与耗能减免（同款）

    /**
     * 本机自己的配方修饰链（由 {@code GuixuVisionCoreMachines} 的 {@code .recipeModifier(...)} 挂上）。
     *
     * <h2>与主机基类那条链的关系</h2>
     * 顺序、钳制、绊线<b>逐字沿用</b> {@link PrimordialRecipeEffects} 的公开入口，只有一处不同：
     * <b>N3 的产出倍率在"门控已生效"时 +5</b>。其余（并行 N4 / 最短耗时 N6 的 min 天花板 /
     * 耗电减免 N5）与原始终焉引擎<b>同款</b>：直接调同一批公开方法，不另写一份算术
     * （两条路径各写一份的后果是静默漂移，这条纪律沿用主机侧的注释）。
     *
     * <pre>
     *   N4 并行        → PrimordialRecipeEffects.applyParallel(…, host.getRecipeLogicMaxParallel())
     *   ②  N3 倍率      → outputMultiplier(门控等级)〔生效时 +5〕 → multiplyOutputs
     *   ③  N6 最短耗时  → applyHostDurationCap(min(原时长, 最短耗时)) + auditDurationWithin（永不变长）
     *   ④  N5 耗电减免  → reduceEnergy(reductionFactor(门控等级))（钳 ≥ 1 EUt）
     *   ⑤ 显示因子      → RecipeMultiplierTracker.captureReduction(…, 1.0, 1.0)（只写显示用 WeakHashMap）
     * </pre>
     *
     * <p>🔴 <b>门控只读一次</b>：倍率与减免必须同源，所以 {@code gateBonus} 在链入口读一次往下传。
     */
    public static GTRecipe applyGuixuRecipeModifier(MetaMachine machine, GTRecipe recipe,
                                                    OCParams params, OCResult result) {
        if (!(machine instanceof GuixuVisionCoreMachine host)) {
            // 防御：本修饰器只挂在本机定义上，落到别的机器就是配置错了 —— 原样放行，不放大也不缩水。
            return recipe;
        }
        final int durationAtEntry = recipe.duration;
        final int limitedDuration = host.getLimitedDuration();
        // 🔴 门控只读一次（倍率与减免同源）
        final int gateBonus = host.moduleSlotBonus();

        // ① N4 并行（上限随时间/覆盖变化，走基类同一入口与同一个 getRecipeLogicMaxParallel）
        GTRecipe modified = PrimordialRecipeEffects.applyParallel(recipe, host,
                Ints.saturatedCast(host.getRecipeLogicMaxParallel()));
        modified = PrimordialRecipeEffects.auditDuration(modified, durationAtEntry, "归墟-链-N4-并行后");

        // ② N3 产出倍率：沿用主机那套 1+等级；门控生效时额外 +5（额外量由 getHostProductionBonusExtra 这一个出口给）
        modified = PrimordialRecipeEffects.multiplyOutputs(modified,
                productionMultiplierOf(gateBonus, host.getHostProductionBonusExtra()));
        modified = PrimordialRecipeEffects.auditDuration(modified, durationAtEntry, "归墟-链-N3-倍率后");

        // ③ N6 最短耗时：min(原时长, 上限)；🔴 永不变长
        final int cap = Math.max(1, limitedDuration);
        final int durationTarget = PrimordialRecipeEffects.durationTarget(durationAtEntry, limitedDuration);
        modified = PrimordialRecipeEffects.applyHostDurationCap(modified, limitedDuration, durationAtEntry);
        modified = PrimordialRecipeEffects.auditDurationWithin(modified, durationTarget,
                durationAtEntry, cap, "归墟-链-N6-终检");

        // ④ N5 耗电减免（与主机同款：读同一个宿主出口，默认实现就是 reductionFactor）
        modified = PrimordialRecipeEffects.reduceEnergy(modified,
                host.getHostEnergyReductionFactor(gateBonus));
        modified = PrimordialRecipeEffects.auditDuration(modified, durationTarget, "归墟-链-N5-耗电后");

        // ⑤ 真实因子交给 gtlcore 的抬头追踪器（只写显示用 WeakHashMap，不改配方任何字段）
        RecipeMultiplierTracker.captureReduction(machine, modified, 1.0D, 1.0D);
        return modified;
    }

    // ------ 两个宿主钩子（引擎路径读它们；见 PrimordialEngineRecipeLogic#buildFinalNormalRecipe）------

    /**
     * <b>材料增产的额外倍率</b>（第 3 项：用户原话「沿用原始终焉引擎那套…并且把增产倍率额外 +5」）。
     *
     * <h2>🔴 2026-10-25 用户裁决：<b>恒为 +5，不再看专属槽</b></h2>
     * 用户原话（逐字）：「<b>2通过（但是专属槽清空 应该是×6）</b>」。
     * <pre>
     *   旧（作废）：moduleSlotBonus() > 0 ? PRODUCTION_BONUS_EXTRA : 0
     *              ⇒ 专属槽一清空 ⇒ extra = 0 ⇒ 倍率 ×1（用户：不对，应该是 ×6）
     *   现行      ：恒 return PRODUCTION_BONUS_EXTRA（= 5）
     *              ⇒ 专属槽【空】⇒ 1 + 0 + 5 = ×6
     *                 专属槽【满 64、Lv.17】⇒ 1 + 17 + 5 = ×23（这一档用户已验过，逐位不变）
     * </pre>
     * 口径澄清（这是用户那句话的全部含义）：<b>专属槽只决定那 0..17 级的门控部分</b>，
     * 而归墟视界枢核自带的 <b>+5</b> 是<b>常驻增产</b>，与槽满不满无关。
     * 旧注释里那句「空槽白得 ×6 产出不是『沿用那套 + 5』」是作废口径，原文留档（见上）。
     *
     * <p>同一个方法被<b>引擎路径</b>（{@code PrimordialEngineRecipeLogic#buildFinalNormalRecipe}）
     * 与<b>原生修饰链</b>（{@link #applyGuixuRecipeModifier}）以及<b>四个显示点</b>读
     * ⇒ 两条路的倍率不可能漂移。
     * <p>⚠️ 老主机（{@code PrimordialOmegaEngineMachine}）的默认实现仍是
     * {@code return 0} ⇒ 那边逐位不变。
     */
    @Override
    public int getHostProductionBonusExtra() {
        // 🔴 2026-10-25：去掉 `moduleSlotBonus() > 0 ? … : 0` 这道闸（用户："专属槽清空 应该是×6"）。
        return PRODUCTION_BONUS_EXTRA;
    }

    /**
     * 🔴 <b>本机给挂在它上面的模块授予「跨配方种类并行」</b>（用户规格第 ③ 条，2026-10-25 实装）。
     *
     * <p>用户原话（逐字）：「<b>使安装在它上面的模块也获得跨配方种类并行，且跨配方种类并行等于原机器的线程数，
     * 也就相当于把他的跨配方线程变成了跨配方种类并行</b>」。
     *
     * <p>语义（由 {@code PrimordialModuleRecipeLogic#lookupRecipeSet()} 兑现）：模块的候选集来源从
     * 「当前激活的那一个配方类型」换成「<b>模块自己声明的全部类型</b>」—— 按类型数组顺序每个类型取 1 条、
     * 凑够线程数即停；类型数不够时剩余额度回落成同类型内并行；<b>发电模块不参与</b>（它走原生链）。
     *
     * <p>为什么<b>只在这个类里返回 true</b>：基类默认 {@code false} ⇒ 原始终焉引擎（老主机）下所有模块
     * 与改动前<b>逐位相同</b>。这是一条结构性保证：谁忘了覆写，退化方向永远是"老行为"，
     * 不会出现"老主机偷偷换了并行语义"这种不可回滚的意外。
     */
    @Override
    public boolean grantsCrossTypeParallel() {
        return true;
    }

    /**
     * 🔴 <b>本机此刻该显示的「机器模式」新名字</b>（用户 2026-10-25：「分别给模块和主机起个新的名字
     * 放到 jade 还有机器里面显示」）—— 名字本体是
     * {@link PrimordialOmegaEngineMachine#CROSS_TYPE_MODE_NAME_HOST}（<b>改名只改那一行</b>）。
     *
     * <h2>判据（三个条件合取；任一条不成立 ⇒ {@code null} ⇒ 两个显示点都照原样）</h2>
     * <ol>
     *   <li>{@link #isFormed()} —— 未成型的主机<b>一个模块都没连上</b>，这时画"视界物质并流"
     *       就是"活的界面上放假数据"；</li>
     *   <li>{@link #grantsCrossTypeParallel()} —— 授予能力本身（本机恒 {@code true}；老主机走基类
     *       默认实现时为 {@code false} ⇒ 永远显示原来的配方类型名）；</li>
     *   <li>{@code !isLock()} —— 玩家锁了某一条配方 ⇒ 主机自己退回"单配方"，按用户口径
     *       <b>仍显示原来的配方类型名</b>。⚠️ 主机这一条与模块那一条是同一个口径；
     *       若你认为"锁主机自己的配方不该影响它给模块授予的模式名"，把本方法里那个
     *       {@code && !shanhai$recipeLockedForDisplay()} 删掉即可（一个条件，不影响别处）。</li>
     * </ol>
     *
     * <h2>两个显示点</h2>
     * <ul>
     *   <li><b>Jade</b>：{@code ShanhaiMachineModeNameMixin}（服务端读本方法 → 写 NBT → 客户端换行）；</li>
     *   <li><b>机器 GUI</b>：本类的 {@link #addDisplayText(List)}（下面那段「机器模式：…」）。</li>
     * </ul>
     * <p>⚠️ 本机的侧栏<b>没有</b>「机器模式」子页（用户 2026-10-25 定案：「它是跨配方种类并行，
     * 那就不需要选择」，见 {@link #attachSideTabs}）⇒ GUI 上"换名"的落点就是抬头那一行。
     */
    @Override
    public @Nullable String crossTypeModeDisplayName() {
        return PrimordialOmegaEngineMachine.resolveCrossTypeModeName(
                isFormed() && grantsCrossTypeParallel()
                        && !PrimordialOmegaEngineMachine.isRecipeLockedForDisplay(getRecipeLogic()),
                PrimordialOmegaEngineMachine.CROSS_TYPE_MODE_NAME_HOST);
    }

    /**
     * <b>N5 耗电成本系数</b>（第 4 项：用户原话「耗能减免：同款」）。
     *
     * <p>与基类默认实现<b>逐位相同</b>（直接返回 {@code reductionFactor(gateBonus)}）——
     * 覆写它的意义是让"减免"也走本机这一个出口：将来若要给归墟换减免公式，只改这里一处，
     * 不必再回头拆引擎尾链。
     */
    @Override
    public double getHostEnergyReductionFactor(int gateBonus) {
        return PrimordialRecipeEffects.reductionFactor(gateBonus);
    }

    /**
     * 门控等级 + 额外倍率 ⇒ 实际倍率。
     *
     * <p>{@code outputMultiplier(gate)}：门控未生效（0）⇒ 1；生效 ⇒ {@code 1+等级}。
     * ⛔ <b>作废口径（2026-10-25 用户推翻，原文留档）</b>：「额外量只加在『已生效』上：
     * {@code extra <= 0} 或门控为 0 时结果就是 {@code outputMultiplier} 本身」。
     * ✅ <b>现行</b>：{@code extra} <b>恒参与</b> ⇒ 空槽 ⇒ {@code 1 + 5 = ×6}；满槽 Lv.17 ⇒ {@code 1+17+5 = ×23}。
     * 算式本体只有 {@link PrimordialRecipeEffects#productionMultiplierOf(int, int)} 一处。
     */
    public static int productionMultiplierOf(int gateBonus, int extra) {
        // 🔴 2026-10-23：算式<b>搬进</b>了 {@link PrimordialRecipeEffects#productionMultiplierOf(int, int)}
        //    （全工程唯一一份）。本方法只剩"历史入口的转发"这一个职责，签名与调用点一个都没变。
        //    为什么必须搬：模块侧也要用同一个数（本次那个 bug 就是"主机有 +5、模块没有"），
        //    而 {@code com.shanhai.common.recipe} 不能反向依赖 {@code com.shanhai.machine.host}
        //    —— 依赖方向只能是这样（本类 → PrimordialRecipeEffects 本来就已经存在）。
        return PrimordialRecipeEffects.productionMultiplierOf(gateBonus, extra);
    }

    /** 本机当前的实际倍率（显示用）。 */
    public int productionMultiplier() {
        return productionMultiplierOf(moduleSlotBonus(), getHostProductionBonusExtra());
    }

    // ------------------------------------------------------------------ 始终渲染为工作状态

    /**
     * 覆写「是否处于工作状态」：把基类侧栏那个开关（{@code isStarAlwaysWorking()}）接到本机的
     * 机壳/贴图活性上。
     *
     * <p>为什么只改这一处：GTCEu 渲染链里"是否工作状态"的读点就是
     * {@code IRecipeLogicMachine#isActive()}（<b>default 方法</b>，本类可覆写，javap 已核）。
     *
     * <p>🔴 为什么写 {@code getRecipeLogic().isActive()} 而不是 {@code super.isActive()}：
     * 接口 default 的字节码就是 {@code getRecipeLogic().isActive()}（javap 实证，见报告），
     * 两者语义逐位相同；但 {@code super.isActive()} 要求 JVM 在"类链"上解析到一个其实是
     * <b>接口 default</b> 的方法（JVMS 5.4.3.3 的 maximally-specific 回落），多一层解析依赖。
     * 本工程里<b>没有任何类</b>覆写过 {@code isActive()}（grep 全量核过）⇒ 显式调用与 super 等价，
     * 且开关关着时行为与加这个覆写之前<b>逐位相同</b>（默认 false）。
     */
    @Override
    public boolean isActive() {
        return isStarAlwaysWorking() || getRecipeLogic().isActive();
    }

    // ------------------------------------------------------------------ 能量双仓的存量（Jade 两行）

    /**
     * 某一侧的 {@code (存量, 容量)}。
     *
     * <p>{@code io = IO.IN} ⇒ <b>能源仓</b>（{@code PartAbility.INPUT_ENERGY} 贡献的 EU 输入仓）；
     * {@code io = IO.OUT} ⇒ <b>动力仓</b>（{@code PartAbility.OUTPUT_ENERGY} 贡献的 EU 输出仓）。
     * 两个集合都直接从 {@code getCapabilitiesProxy()} 取 —— 与 GTCEu 真正装配配方所用的
     * 是同一份集合（不是另算一遍），读法与 {@code EnergyHatchPower#containersOf} 同源。
     *
     * <p>纯读：任何异常都退回 {@code (0,0)}，绝不打断抬头渲染。
     */
    private long[] energyTotals(IO io) {
        long stored = 0L;
        long capacity = 0L;
        try {
            final List<IRecipeHandler<?>> handlers = getCapabilitiesProxy().get(io, EURecipeCapability.CAP);
            if (handlers != null) {
                for (IRecipeHandler<?> handler : handlers) {
                    if (handler instanceof IEnergyContainer container) {
                        stored += container.getEnergyStored();
                        capacity += container.getEnergyCapacity();
                    }
                }
            }
        } catch (Throwable ignored) {
            // 纯读：显示用，异常 ⇒ 当作"没有仓"
        }
        return new long[]{stored, capacity};
    }

    // ------------------------------------------------------------------ 显示文案（别放假数据）

    /**
     * 覆写显示文案（Jade / GUI 抬头）。
     *
     * <p>🔴 <b>不调 {@code super}</b>：基类那套写的是「[原始终焉引擎]」「16 个模块位」
     * 「kubejs:steam_assembly_block 占位」，对本机全是假数据；而 GTCEu 默认那条能量行
     * 也被这条覆写一并挡掉 —— <b>于是两边存量只在下面这两行里出现，不会重复</b>。
     *
     * <p>能量双仓两条的读法：能源仓 = {@code IO.IN} 侧全部 {@link IEnergyContainer} 的存量/容量之和；
     * 动力仓 = {@code IO.OUT} 侧同理。两侧都没装仓时显示 {@code 0 / 0}（不是假数据，是"真的没有"）。
     */
    @Override
    public void addDisplayText(List<Component> textList) {
        if (!isFormed()) {
            textList.add(Component.literal("[归墟视界枢核] ").withStyle(ChatFormatting.GOLD)
                    .append(Component.literal("结构未成型").withStyle(ChatFormatting.RED)));
            textList.add(Component.literal("24 个模块位：空槽可以留空（插/拆模块不会让本机失效）")
                    .withStyle(ChatFormatting.GRAY));
            textList.add(Component.literal("已连接模块：0 / " + getMaxModuleCount())
                    .withStyle(ChatFormatting.GRAY));
            return;
        }
        textList.add(Component.literal("[归墟视界枢核] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal("结构已成型").withStyle(ChatFormatting.GREEN)));
        textList.add(Component.literal("已连接模块：" + getFormedModuleCount() + " / " + getMaxModuleCount())
                .withStyle(ChatFormatting.GRAY));

        // ── 能量双仓：两行分别显示（默认那条已被本覆写挡掉，不重复）──
        final long[] input = energyTotals(IO.IN);
        final long[] output = energyTotals(IO.OUT);
        textList.add(Component.literal("能源仓 (IN)：" + input[0] + " / " + input[1] + " EU")
                .withStyle(ChatFormatting.AQUA));
        textList.add(Component.literal("动力仓 (OUT)：" + output[0] + " / " + output[1] + " EU")
                .withStyle(ChatFormatting.AQUA));

        // ── 物质模块槽门控 ⇒ 增产 / 减免（生效了就必须看得见）──
        final int bonus = moduleSlotBonus();
        textList.add(Component.literal(bonus <= 0
                ? "物质模块等级：未生效（主机那格放 64 个物质模块才生效）"
                : "物质模块等级：Lv." + bonus + " / 17").withStyle(ChatFormatting.GRAY));
        // ── 模块等级复写（第二个专属槽）──
        final int levelOverride = getHostMatterLevelOverride();
        textList.add(Component.literal(levelOverride <= 0
                ? "模块等级复写：未生效（复写槽放满 64 个物质模块才生效）"
                : "模块等级复写：Lv." + levelOverride + "（本机 24 个模块的有效等级至少抬到这一档）")
                .withStyle(ChatFormatting.GRAY));
        textList.add(Component.literal("材料增产：x" + productionMultiplier()
                + "（主机那套 + " + PRODUCTION_BONUS_EXTRA + "）").withStyle(ChatFormatting.GRAY));
        textList.add(Component.literal(String.format(Locale.ROOT,
                "耗能减免：x%.4f 耗电（同主机那套）",
                PrimordialRecipeEffects.reductionFactor(bonus))).withStyle(ChatFormatting.GRAY));

        // ── 🔴 跨配方种类并行（用户 2026-10-25：「跨配方种类并行应该在提示上面写一下」）──
        // 判据：这一行只在 grantsCrossTypeParallel() 为 true 时显示，取值就是那个方法的返回值
        //      （= 模块侧真正执行的那条判据，不是另写一句常量）。
        // 落点说明：addDisplayText 同时喂给 Jade 抬头与 GUI 抬头（基类 createUIWidget 的
        //          ComponentPanelWidget 走它）⇒ 只要看到这台机器的提示，就一定看得到这一行。
        textList.add(Component.literal("跨配方种类并行：")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(grantsCrossTypeParallel() ? "开" : "关")
                        .withStyle(grantsCrossTypeParallel() ? ChatFormatting.GREEN : ChatFormatting.RED))
                .append(Component.literal("（挂在本机上的模块按各自配方类型并行，无需选「机器模式」）")
                        .withStyle(ChatFormatting.GRAY)));

        // ── 🔴 机器模式名（用户 2026-10-25：「也分别给模块和主机起个新的名字放到 jade 还有机器里面显示」）──
        // 判据与 Jade 那一行【同一个方法】：crossTypeModeDisplayName() 返回 null ⇒ 这里【一行都不加】
        // （= 未生效 / 未成型 / 锁配方 时 GUI 与改动前逐字相同）。
        final String crossTypeModeName = crossTypeModeDisplayName();
        if (crossTypeModeName != null) {
            textList.add(Component.literal("机器模式：").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(crossTypeModeName).withStyle(ChatFormatting.AQUA))
                    .append(Component.literal("（跨配方种类并行：挂在本机上的模块按全部配方类型轮转取配方）")
                            .withStyle(ChatFormatting.GRAY)));
        }
    }
}
