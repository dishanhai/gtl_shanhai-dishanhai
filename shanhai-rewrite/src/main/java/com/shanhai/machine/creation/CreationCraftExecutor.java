package com.shanhai.machine.creation;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
// 🔴 P5 唯一允许的 gtlcore import（用户拍板「A 挂 gtlcore 的标记接口」）：
//    这是一个**纯标记接口**（AE 是否可以把整单剩余 N 份合并成一次 pushPattern）。
//    本类只实现它、只从它读一个判据，**不调用** org.gtlcore 的任何实现代码；
//    其余 gtlcore/gtladd 依赖一律 0（见 temp/molmanip/p5check.ps1 的白名单断言）。
import org.gtlcore.gtlcore.api.crafting.IAutoExpandSettings;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「原初造物编译矩阵」的 <b>AE 接单 / 执行 / 出货</b> 全部逻辑（P3 从已删除的 {@code CreationPatternPortPartMachine}
 * <b>整段搬过来</b>，一行算术都没改）。
 *
 * <h2>它在系统里的位置（P3 起：控制器自己就是 AE 节点，不再有端口方块）</h2>
 * <pre>
 * 控制器方块（{@link PrimordialCreationCompilationMatrix}）
 *   ├ implements {@link appeng.api.networking.IInWorldGridNodeHost}  ← AE 线缆直接贴控制器
 *   ├ 一个 {@code IManagedGridNode}（{@code GridHelper.createManagedNode(this, …)}）
 *   │    └ addService(ICraftingProvider.class, <b>本对象</b>)        ← 唯一让 AE 认得它能接样板的一行
 *   └ 持有本对象（{@link #CreationCraftExecutor}）
 *        ├ getAvailablePatterns()  读控制器身上那 1000 格样板 → 解码 → <b>恒包</b> {@link CreationScaledPattern}
 *        ├ pushPattern(…)          算份数 / 算余量退回 / 入队（<b>不扣任何实物</b>）
 *        ├ isBusy()                队列满 或 有货插不进网络
 *        └ serverTick()            每 10 tick 一个周期：跨样板合并 → 产物一次全出（{@code MEStorage#insert}）
 * </pre>
 *
 * <h2>🔴 与 P2 的行为必须逐项相同（本轮唯一允许变的是"东西挂在哪"）</h2>
 * 份数 / 余量退回 / 多产物全出 / 10 tick / 无限模式 / 跨样板合并 / 插不进重试 / 日志字段与顺序
 * <b>全部逐字保持</b>。搬家只换了三样东西：
 * <ol>
 *   <li><b>宿主</b>：原来是 {@code MultiblockPartMachine}（端口仓室），现在是控制器本身
 *       ⇒ {@code getLevel()/getPos()/isRemote()} 改成 {@code machine.xxx()}；</li>
 *   <li><b>节点</b>：原来是 GTCEu 的 {@code GridNodeHolder}，现在是控制器自己那个
 *       {@link IManagedGridNode}；</li>
 *   <li><b>"读不到控制器"的判据</b>：P2 是 {@code getControllers()} 里找不到
 *       {@link CreationPatternHost}（= 端口没装进成型结构）；现在控制器就是宿主，
 *       等价判据 = <b>{@code machine.isFormed()}</b>（结构没成型 ⇒ 与 P2 的"没有宿主"完全同义）。
 *       这一条是<b>必须</b>的：不加它，没成型的机器也会被 AE 派活（那是行为变化，不是搬家）。</li>
 * </ol>
 *
 * <h2>🔴 三条红线（照抄 P2，一条没松）</h2>
 * <ol>
 *   <li><b>不扣料、不返还原料</b>：料是 AE 在 {@code pushPattern} 之前就扣掉的 ⇒ 本类<b>没有</b>任何
 *       "扣减输入"的代码；唯一往网络里插东西的两个地方是「余量退回」与「产物回网」，两者都是<b>出</b>。</li>
 *   <li><b>{@code return false} 时一个字节都不许动账目</b>：AE 会自己把它收回网络
 *       （{@code CraftingCpuHelper#reinjectPatternInputs}）；我们要是也退一次就是<b>刷物品</b>。</li>
 *   <li><b>不查电、不扣电</b>：这条路径不经过 GT 的任何 {@code RecipeLogic} / {@code EUt} / 电压闸门，
 *       全程只有 AE 的账目与 {@code MEStorage#insert}（不带 {@code poweredInsert}）。</li>
 * </ol>
 *
 * <h2>并行与速度（用户拍板，读数全部来自控制器）</h2>
 * <ul>
 *   <li><b>每个 10 tick 周期</b>能做完的份数上限 = {@link CreationPatternHost#getCreationParallelCap()}
 *       （{@code 8^物质模块等级}；空槽 ⇒ 1）；</li>
 *   <li>等级 ≥ 15 ⇒ 哨兵 {@code Long.MIN_VALUE} ⇒ <b>无限模式</b>：队列里所有待做份数<b>一次全做完</b>；</li>
 *   <li><b>速度固定 10 tick</b>（{@link PrimordialCreationCompilationMatrix#TICK_DURATION}，<b>唯一</b>时长来源）。</li>
 * </ul>
 *
 * <h2>⚠️ 日志 logger 名字为什么还叫 {@code shanhai/creation-port}</h2>
 * P5 的验收判据是 grep {@code [SHANHAI-CREATION]} 前缀行；日志框架会在行首打 logger 名，
 * 一改名就等于改了日志前缀。⇒ <b>故意沿用</b>（不是漏改）：见 {@link #LOGGER}。
 *
 * <h2>⚠️ 已知边界（P2 立的口径，本轮未动）</h2>
 * <ul>
 *   <li><b>容器物返还</b>：第一版<b>不做</b> —— 打一条 {@code WARN} 点名会丢什么，产物照出
 *       （{@link #shanhai$warnContainerLoss}）。</li>
 *   <li><b>积压不落盘</b>：{@link #backlog} / {@link #unsent} 是运行期状态；卸载/被拆时打一条带数量的
 *       {@code WARN}（不静默），但不会跨存档恢复。</li>
 * </ul>
 */
public final class CreationCraftExecutor implements ICraftingProvider, IAutoExpandSettings {

    /**
     * ⚠️ 名字沿用 P2 的 {@code shanhai/creation-port}（<b>故意不改</b>）：
     * 日志行首会带 logger 名，改名等于改日志前缀，而 P5 的判据就是那些行。
     */
    private static final Logger LOGGER = LoggerFactory.getLogger("shanhai/creation-port");

    /** 日志统一前缀（验收判据就认它）。 */
    private static final String PREFIX = "[SHANHAI-CREATION]";

    /**
     * 积压队列的容量：{@code isBusy()} 的"接不下"判据。
     *
     * <p>为什么要有上限：AE 的契约里 {@code pushPattern} 没有"稍后再接"这个返回值，
     * 唯一的流控是 {@code isBusy()}。没有上限的话一台机器能被灌进任意多单，
     * 而它每 10 tick 只能做 8^等级 份 ⇒ 内存里会堆一堆"AE 已经扣了料"的欠账。
     * <p>512 单 ≈ 一台机器的样板表装满之后的常态量级；满了就 return false 让 AE 自己换目标/重试。
     */
    public static final int MAX_PENDING_ORDERS = 512;

    /** 单次出货对同一样产物的插入重试次数（插不进去 ⇒ 进 {@link #unsent} 每 tick 继续重试）。 */
    private static final int INSERT_ATTEMPTS = 3;

    /** {@link #unsent} 非空时的日志间隔（tick），避免每 tick 刷屏。 */
    private static final int UNSENT_LOG_INTERVAL = 100;

    /** 各类"限流告警"的间隔（tick）。 */
    private static final int THROTTLED_WARN_INTERVAL = 200;

    /** 接单记录最多留多少条（P1 是无上限的 List，长时间挂机会涨内存）。 */
    private static final int ACCEPTED_LOG_CAP = 32;

    /** 宿主 = 控制器（既是 {@link CreationPatternHost}，也是 {@code MetaMachine}／网格节点持有者）。 */
    private final PrimordialCreationCompilationMatrix machine;

    /** 样板解码缓存（只在版本号 / 报给 AE 的倍率变化时重建）。 */
    private final List<IPatternDetails> cachedPatterns = new ArrayList<>();

    /** 缓存对应的控制器内容版本号；{@link Long#MIN_VALUE} = 还没有效缓存。 */
    private long cachedVersion = Long.MIN_VALUE;

    /** 上次报给 AE 的样板倍率（{@link CreationPatternHost#getAeReportedPatternMultiplier()}）。 */
    private int cachedAeMultiplier = Integer.MIN_VALUE;

    /** 上次统计的"不可解码条数"，只在变化时打一条日志（避免每 tick 刷屏）。 */
    private int lastUndecodableReported = -1;

    /**
     * 上一 tick 看到的"结构已成型"状态。
     *
     * <p>用途与 P2 的 {@code addedToController} / {@code removedFromController} <b>逐条同义</b>：
     * 结构成型/散架的那一刻样板源"出现/消失" ⇒ 必须作废缓存并 {@code requestUpdate} 让 AE 重问。
     * （P2 那两个回调的等价物在本轮就是这一对状态跳变。）
     */
    private boolean lastFormed;

    // ═════════════════════════════ 积压队列与出货状态（P2 原样） ═════════════════════════════

    /**
     * <b>积压队列</b>：AE 已经付过账、我们还欠着的单。
     *
     * <p>🔴 里面的每一份份数都对应"AE 已经扣走的料" ⇒ 只要机器在，<b>这一份都不能丢</b>
     * （处理不完就留到下一个周期；结构散了就等它回来）。
     */
    private final List<PendingOrder> backlog = new ArrayList<>();

    /**
     * <b>待退回的余量</b>（整数除法截断出来的零头）。
     *
     * <p>🔴 为什么要排队而不是在 {@code pushPattern} 里当场 {@code insert}：{@code pushPattern} 是
     * <b>被 AE 的合成 CPU 在自己的循环里调</b>的（{@code CraftingCpuLogic#executeCrafting}），
     * 在那一刻往同一个网络里插货可能又回调进 CPU（满足它的 {@code waitingFor}），属于在别人的
     * 循环里改别人的状态 —— 能避就避。排队到下一 tick 插，语义完全一样（"不吞"），风险少一层。
     */
    private final Map<AEKey, Long> pendingRefund = new LinkedHashMap<>();

    /**
     * <b>没插进网络的产物/余量</b>（网络满 / 断网 / 抛异常都会落在这里）。
     *
     * <p>🔴 "不许静默丢货"的兜底：每 tick 重试一次，插进去为止；日志按 {@link #UNSENT_LOG_INTERVAL} 限流。
     */
    private final Map<AEKey, Long> unsent = new LinkedHashMap<>();

    /**
     * <b>节奏 + 日志聚合的唯一实现</b>（纯算术，见 {@link CreationPacing}）。
     *
     * <p>🔴 P5 起"一单要满 10 tick"与"每秒最多一行汇总"都由它判定，本类只负责在
     * {@code serverTick} 里推进时钟、在周期边界上执行、在有活动时把那一行打出来。
     * <p>⛔ 旧实现（已删除）：{@code private int cycleProgress;} —— 它会在
     * {@code backlog.isEmpty()} 时被清零，而且<b>不看"这一单到了多久"</b>
     * ⇒ 在周期进度 9 时到达的单，下一 tick 就被做掉（等待 1 tick，不是 10）。
     */
    private final CreationPacing pacing = new CreationPacing(
            PrimordialCreationCompilationMatrix.TICK_DURATION);

    /** 全局 tick 计数器（只用于日志限流；到 1000 归零，不参与任何业务判断）。 */
    private int tickCounter;

    /** 首次接单 / 首次产出各打一行样例日志（便于判读；之后一律走每秒汇总）。 */
    private boolean firstAcceptLogged;
    private boolean firstOutputLogged;

    /** 接单记录（<b>只是排查用的文本</b>，最多 {@link #ACCEPTED_LOG_CAP} 条）。 */
    private final List<String> acceptedOrders = new ArrayList<>();

    /** 各类限流告警的上次触发 tick（key ⇒ tick）。 */
    private final Map<String, Integer> throttledWarnTicks = new LinkedHashMap<>();

    /** 一单积压：样板 + 还欠着的份数（<b>份数已经在接单时算好</b>，见 {@link CreationCraftMath#craftsOf}）。 */
    private static final class PendingOrder {

        /** <b>拆过包装</b>的原样板（产物数量是"单份"口径，没有乘倍率）。 */
        final IPatternDetails rawPattern;

        /** 日志用的样板名（主产物显示名；比"处理样板"这种物品名好认）。 */
        final String label;

        /** 接单那一刻报给 AE / 将来出货要用的主机增产倍率（≥ 1）。 */
        final int multiplier;

        /** 单份产物表（<b>全部</b>产物，不是只有第 0 个）。 */
        final List<CreationCraftMath.Product<AEKey>> perCraftOutputs;

        /** 接单时算出的总份数（只用于日志/排查）。 */
        final long craftsTotal;

        /** 还欠着没做的份数（每个周期减一次，减到 0 才出队）。 */
        long craftsRemaining;

        /**
         * <b>最早允许产出的绝对 tick</b> = 接单那一刻的 {@link CreationPacing#now()} + {@code TICK_DURATION}。
         *
         * <p>🔴 这就是"一单必须满 10 tick"的落点：{@link #shanhai$runCycle()} 只做 {@code now >= dueTick}
         * 的单。⛔ 旧实现没有这个字段（只数"周期过了几 tick"）⇒ 在边界前 1 tick 到达的单会被下一个边界顺手做掉。
         */
        final long dueTick;

        PendingOrder(IPatternDetails rawPattern, String label, int multiplier,
                     List<CreationCraftMath.Product<AEKey>> perCraftOutputs, long craftsTotal, long dueTick) {
            this.rawPattern = rawPattern;
            this.label = label;
            this.multiplier = multiplier;
            this.perCraftOutputs = perCraftOutputs;
            this.craftsTotal = craftsTotal;
            this.craftsRemaining = craftsTotal;
            this.dueTick = dueTick;
        }
    }

    CreationCraftExecutor(PrimordialCreationCompilationMatrix machine) {
        this.machine = machine;
    }

    // ═════════════════════════════ 生命周期（由控制器转调） ═════════════════════════════

    /**
     * 控制器每 server tick 调一次（P2 里这是端口自己的 {@code subscribeServerTick}）。
     *
     * <p>只做两件事：① 结构成型/散架的跳变检测（作废样板缓存 + 让 AE 重问）；
     * ② 比对版本号/倍率（变就让 AE 重问一遍）并推进 10 tick 周期。
     */
    void serverTick() {
        tickCounter = tickCounter >= 1000 ? 0 : tickCounter + 1;
        if (machine.isRemote()) {
            return;
        }
        // ① 时钟先走一格（单调、永不重置）⇒ 本 tick 是不是周期边界
        final boolean cycleBoundary = pacing.advance();
        // 结构成型 ↔ 散架（= P2 的 addedToController / removedFromController）
        final boolean formed = machine.isFormed();
        if (formed != lastFormed) {
            this.lastFormed = formed;
            shanhai$invalidatePatternCache(formed ? "加入多方块结构" : "离开多方块结构");
        }
        final CreationPatternHost host = shanhai$patternHost();
        if (host != null) {
            if (host.getCreationPatternVersion() != cachedVersion) {
                shanhai$invalidatePatternCache("控制器样板内容变化");
            }
            // 🔴 神锻倍率变了 ⇒ 必须让 AE 重算（否则 AE 仍按旧倍率下单，会多合份数）。
            final int multiplier = host.getAeReportedPatternMultiplier();
            if (multiplier != cachedAeMultiplier) {
                final int previous = cachedAeMultiplier;
                this.cachedAeMultiplier = multiplier;
                // 报给 AE 的"一块样板出几个"变了 ⇒ 解码缓存里那些包装过的样板也过期了。
                shanhai$invalidatePatternCache("报给 AE 的倍率变化");
                LOGGER.info("{} pos={} 报给 AE 的样板倍率 {} → ×{}（已 requestUpdate 让 AE 重算）",
                        PREFIX, shanhai$posTag(),
                        previous == Integer.MIN_VALUE ? "初始化" : String.valueOf(previous), multiplier);
            }
        }
        shanhai$tickCreation(cycleBoundary);
    }

    /**
     * 控制器卸载时调（P2 里这是端口的 {@code onUnload}）：有欠账就<b>大声说出来</b>（不静默丢）。
     *
     * <p>P2 的积压<b>不落盘</b>（见类注释「已知边界」）⇒ 卸载等于放弃这些欠账。它们对应的原料
     * AE 早就扣掉了 ⇒ 玩家视角是"料没了、东西没出来"。所以宁可刷一条 WARN，也不能悄悄消失。
     */
    void onMachineUnload() {
        if (machine.isRemote()) {
            return;
        }
        shanhai$warnOnDiscard("卸载（区块卸载 / 机器被拆 / 存档退出）");
    }

    /** 网格节点状态变化（AE 回调）⇒ 样板源可能要重问一遍（与 P2 的 {@code onMainNodeStateChanged} 同义）。 */
    void onGridStateChanged() {
        shanhai$invalidatePatternCache("网格节点状态变化");
    }

    private void shanhai$warnOnDiscard(String why) {
        if (backlog.isEmpty() && unsent.isEmpty() && pendingRefund.isEmpty()) {
            return;
        }
        long pendingCrafts = 0L;
        for (PendingOrder order : backlog) {
            pendingCrafts = CreationCraftMath.saturatedAdd(pendingCrafts, order.craftsRemaining);
        }
        LOGGER.warn("{} pos={} 积压被打断（{}）：还有 {} 单 / {} 份没做完，{} 样产物没插进网络，"
                        + "{} 样余量没退回。⚠️ 积压【不落盘】⇒ 这些份数对应的原料是 AE 已扣的，等价于丢失。",
                PREFIX, shanhai$posTag(), why, backlog.size(), pendingCrafts,
                unsent.size(), pendingRefund.size());
    }

    private void shanhai$invalidatePatternCache(String why) {
        this.cachedVersion = Long.MIN_VALUE;
        this.cachedPatterns.clear();
        if (!machine.isRemote()) {
            // 样板集变了必须主动刷 AE 的缓存，否则"新插的样板在终端里做不了"，直到下次网络变动。
            final IManagedGridNode node = machine.creationMainNode();
            if (node != null) {
                ICraftingProvider.requestUpdate(node);
            }
            LOGGER.debug("{} 样板缓存作废（{}），已 requestUpdate", PREFIX, why);
        }
    }

    /**
     * 找"样板源" = 已经<b>成型</b>的控制器自己。
     *
     * <p>P2 的判据是"端口在哪台多方块里、那台控制器是不是 {@link CreationPatternHost}"；
     * 现在控制器就是宿主，等价判据是 {@link PrimordialCreationCompilationMatrix#isFormed()}：
     * <ul>
     *   <li>结构没成型 ⇒ 与 P2 的"没有宿主"<b>完全同义</b>（AE 看不到样板、接单被拒、周期不跑）；</li>
     *   <li>结构成型 ⇒ 与 P2 的"端口装进了成型结构"同义。</li>
     * </ul>
     * <p>⚠️ 客户端恒 {@code null}（AE 那半边本来就只在服务端跑）。
     */
    @Nullable
    private CreationPatternHost shanhai$patternHost() {
        if (machine.isRemote()) {
            return null;
        }
        return machine.isFormed() ? machine : null;
    }

    // ═════════════════════════════ ICraftingProvider：能做什么 ═════════════════════════════

    /**
     * AE 问："你现在能做哪些样板？"
     *
     * <h2>取数与解码路径（逐跳，与 P2 逐条相同）</h2>
     * <pre>
     * 结构成型？
     *   → 否：清缓存 ⇒ 返回空集合（AE 看不见样板，不会派活；与 P2「端口不在成型结构里」同义）
     *   → 是：host.getCreationPatternInventory()          // 控制器身上那 1000 格 ItemStackTransfer
     *       → 逐格 getStackInSlot(i)                      // 空格跳过
     *           → PatternDetailsHelper.decodePattern(stack, level, false)
     *               → 成功：收进 List&lt;IPatternDetails&gt;
     *               → 失败（null / 抛异常）：<b>跳过</b>，计数，日志里报一条（见 lastUndecodableReported）
     *   → 若 host.getAeReportedPatternMultiplier() &gt; 1 ⇒ 包成 CreationScaledPattern（产物数量 × 倍率）
     *   → 缓存（版本号 = host.getCreationPatternVersion()；只在变化时重建）
     * </pre>
     *
     * <h2>🔴 P6 起：<b>恒包装</b>，返回的每一个元素都是 {@link CreationScaledPattern}</h2>
     * 上面那条 "若倍率 &gt; 1" 的条件<b>已经删掉</b>（倍率 = 1 也包、原样板没产物也包）。
     * 理由：包装类同时负责把 {@code supportsPushInputsToExternalInventory()} 答成 true，
     * 而裸 {@code AECraftingPattern} 答 false ⇒ gtlcore 的 {@code canAutoExpand} 在偏移 5 就否掉，
     * AE 每次只肯派 1 份（实机：每秒 257~512 单、每单 crafts=1）。
     * <p>⇒ 本方法是<b>唯一</b>把样板交给 AE 的出口（{@code ICraftingProvider} 只有它读样板），
     * 而它只从 {@code cachedPatterns} 出，{@code cachedPatterns} 只在
     * {@link #shanhai$reloadPatterns} 里经 {@link #shanhai$scaledForAe} 填
     * ⇒ "不许有一条裸样板漏网"是<b>结构上</b>成立的，不靠人工巡检。
     *
     * <h2>🔴 为什么要包那一层</h2>
     * 倍率恒 = 主机增产（P10 起没有开关）⇒ 机器实际一块样板出 {@code 原产物 × 主机增产}；报给 AE 的如果还是原数量，
     * 用户原话就是那个后果：「不然它产出 17 个 ae 下单计算时还以为产出了 1 个，这样就多合成了很多份」。
     * ⇒ 报数（本方法）与出货（{@link #shanhai$runCycle()}）用的是<b>同一个倍率</b>
     * （{@link CreationPatternHost#getAeReportedPatternMultiplier()}）。
     */
    @Override
    public List<IPatternDetails> getAvailablePatterns() {
        final CreationPatternHost host = shanhai$patternHost();
        if (host == null) {
            if (!cachedPatterns.isEmpty()) {
                cachedPatterns.clear();
            }
            cachedVersion = Long.MIN_VALUE;
            return Collections.emptyList();
        }
        if (host.getCreationPatternVersion() != cachedVersion) {
            shanhai$reloadPatterns(host);
        }
        // AE 只读、会自己复制进 NetworkCraftingProviders 的列表 ⇒ 这里返回内部列表即可（不额外拷贝 1000 项）。
        return cachedPatterns;
    }

    private void shanhai$reloadPatterns(CreationPatternHost host) {
        cachedPatterns.clear();
        final int slots = Math.min(host.getCreationPatternSlotCount(), host.getCreationPatternInventory().getSlots());
        final int multiplier = Math.max(1, host.getAeReportedPatternMultiplier());
        int undecodable = 0;
        for (int i = 0; i < slots; i++) {
            final ItemStack stack = host.getCreationPatternInventory().getStackInSlot(i);
            if (shanhai$isEmptyStack(stack)) {
                continue;
            }
            final IPatternDetails details = decodePattern(stack);
            if (details == null) {
                undecodable++;
                continue;
            }
            cachedPatterns.add(shanhai$scaledForAe(details, multiplier));
        }
        cachedVersion = host.getCreationPatternVersion();
        if (undecodable > 0 && undecodable != lastUndecodableReported) {
            LOGGER.warn("{} 解码样板：成功 {} 块 / 跳过 {} 块（不可解码）；跳过的槽位内容不会被 AE 看见",
                    PREFIX, cachedPatterns.size(), undecodable);
            lastUndecodableReported = undecodable;
        } else if (undecodable == 0 && lastUndecodableReported > 0) {
            LOGGER.info("{} 解码样板：{} 块全部成功（此前的不可解码问题已消失）",
                    PREFIX, cachedPatterns.size());
            lastUndecodableReported = 0;
        }
    }

    /**
     * 报给 AE 之前<b>无条件</b>包一层（P6：<b>倍率 = 1 也包、原样板没有产物也包</b>）。
     *
     * <h2>🔴 为什么"倍率 = 1 就不包"是错的（P6 修的核心缺陷）</h2>
     * 我们这个包装类还负责回答 {@link CreationScaledPattern#supportsPushInputsToExternalInventory()}
     * （恒 {@code true}）。一旦"倍率 = 1 就不包"，台机器报给 AE 的就是<b>裸
     * {@code AECraftingPattern}</b>，而它对这个问题的答案是 {@code false}
     * （{@code IMolecularAssemblerSupportedPattern} 把它定成 false，比
     * {@code IPatternDetails} 的 default true 更具体）⇒ gtlcore 的
     * {@code canAutoExpand} 在偏移 5 直接返回 false ⇒ 当年那台 <b>倍率 = 1 的机器被"不包"</b>时永远只接 1 份一单。
     * 实机读数就是：并行 2097152、每秒 257~512 单、每单 {@code crafts=1}、累计 9000+ 次推送。
     *
     * <h2>语义（包装只改两件事，改不了别的）</h2>
     * ① 产物数量按倍率放大；② 对"能不能自己吃料做"答 {@code true}。
     * 输入表/份数/余量/出货算术<b>一个字都不经过本方法</b>（见 {@link CreationScaledPattern} 类注释）。
     *
     * <p>被包住的样板会被 AE 原样递回 {@code pushPattern} ⇒ 那边必须用
     * {@link CreationScaledPattern#delegateOf(IPatternDetails)} 拆回来，否则倍率会乘两次。
     *
     * @param details    解码出来的原样板（非空）
     * @param multiplier 主机增产倍率（&lt; 1 按 1 处理）
     */
    private static IPatternDetails shanhai$scaledForAe(IPatternDetails details, int multiplier) {
        // 🔴 恒包装（P6）：这里的 if 被删掉了，不是漏写 —— 任何一条裸样板到 AE 手里都等于"永不合并"。
        return new CreationScaledPattern(details, Math.max(1, multiplier));
    }

    /**
     * 解码单块样板；失败返回 {@code null}（不抛）。
     *
     * <p>为什么自己包一层 try/catch：这个方法被调在 AE 的查询链上，一次坏样板不能把整个 AE 网络带下去。
     */
    @Nullable
    private IPatternDetails decodePattern(ItemStack stack) {
        final Level level = machine.getLevel();
        if (level == null) {
            return null;
        }
        try {
            return appeng.api.crafting.PatternDetailsHelper.decodePattern(stack, level, false);
        } catch (Throwable t) {
            LOGGER.warn("{} 样板解码抛异常（按不可解码跳过）：{} — {}",
                    PREFIX, stack.getHoverName().getString(), t.toString());
            return null;
        }
    }

    // ═════════════════════════════ ICraftingProvider：接单（P2 的核心） ═════════════════════════════

    /**
     * AE 派活："这块样板的料给你了（{@code table} 是已经从网络里扣走的那份清单）。"
     *
     * <h2>逐段说明（顺序不能改，与 P2 逐条相同）</h2>
     * <ol>
     *   <li><b>前置闸门</b>：客户端 / 样板为空 / 结构没成型 / 网格没激活 / 队列满 ⇒ {@code return false}
     *       （AE 自己把账目原路退回，我们一个字节都不动）；</li>
     *   <li><b>拆包装</b>：拿回原样板（产物是"单份"口径）+ 这一单该用的倍率；</li>
     *   <li><b>账目</b>：{@code KeyCounter[]} 摊平成 {@code AEKey → long}；</li>
     *   <li><b>单份需求</b>：从 {@code IPatternDetails#getInputs()} 的 {@code getPossibleInputs()}
     *       里挑<b>账目里真的出现</b>的那个候选，× {@code getMultiplier()}；</li>
     *   <li><b>份数</b>：{@link CreationCraftMath#craftsOf}（整数除法取 min）⇒ <b>0 就
     *       {@code return false}</b>（"让 AE 退回，不许静默丢"）；</li>
     *   <li><b>余量退回</b>：{@link CreationCraftMath#leftoversOf} ⇒ 非 0 就 {@code MEStorage#insert}
     *       退回 AE 网络（<b>这一步只在"已经决定收下"之后做</b>，否则会和 AE 的 reinject 撞成刷物品）；</li>
     *   <li><b>容器物告警</b>：{@link #shanhai$warnContainerLoss}（第一版不返还，只报）；</li>
     *   <li><b>入队</b>：份数记到 {@link PendingOrder#craftsRemaining} 上，等 10 tick 周期消费。</li>
     * </ol>
     * 🔴 全程<b>不扣任何实物</b>（料早在 AE 侧扣完了），也<b>没有</b>新增任何扣减/返还原料的路径。
     *
     * @return {@code true} = 收下这一单（欠账已记账，必出货）；{@code false} = 不收（AE 原因路退回）
     */
    @Override
    public boolean pushPattern(IPatternDetails pattern, KeyCounter[] table) {
        if (machine.isRemote()) {
            return false;
        }
        if (pattern == null) {
            LOGGER.warn("{} pos={} 拒单：样板为 null", PREFIX, shanhai$posTag());
            return false;
        }
        final CreationPatternHost host = shanhai$patternHost();
        if (host == null) {
            LOGGER.warn("{} pos={} 拒单：结构没成型（本机此刻不接单）", PREFIX, shanhai$posTag());
            return false;
        }
        final IManagedGridNode node = machine.creationMainNode();
        if (node == null || !node.isActive()) {
            LOGGER.warn("{} pos={} 拒单：网格节点未激活（pattern = {}）", PREFIX, shanhai$posTag(), pattern);
            return false;
        }
        if (backlog.size() >= MAX_PENDING_ORDERS) {
            shanhai$warnThrottled("push-full", THROTTLED_WARN_INTERVAL,
                    "拒单：积压已满（{} 单 ≥ 上限 {}），等消费完再接", backlog.size(), MAX_PENDING_ORDERS);
            return false;
        }

        // ② 拆包装：AE 很可能把我们包装过的样板原样递回来（getOutputs 已放大）⇒ 必须取回原样板。
        final IPatternDetails raw = CreationScaledPattern.delegateOf(pattern);
        final int multiplier = Math.max(1,
                CreationScaledPattern.multiplierOf(pattern, host.getAeReportedPatternMultiplier()));
        final String label = shanhai$labelOf(raw);

        // ③ 账目（KeyCounter[] → AEKey → long）
        final Map<AEKey, Long> accounted = shanhai$accountingOf(table);
        if (accounted.isEmpty()) {
            LOGGER.warn("{} pos={} pattern={} 拒单：账目为空（table={}）", PREFIX, shanhai$posTag(), label,
                    table == null ? "null" : String.valueOf(table.length));
            return false;
        }

        // ④ 单份需求
        final Map<AEKey, Long> perCraft = shanhai$perCraftRequirements(raw, accounted);
        if (perCraft == null) {
            LOGGER.warn("{} pos={} pattern={} 拒单：输入表解析失败（账目里找不到某个输入组的任何候选）⇒ "
                    + "AE 原因路退回，本机未扣任何东西", PREFIX, shanhai$posTag(), label);
            return false;
        }

        // ⑤ 份数（0 ⇒ 做不了 ⇒ 拒单，绝不静默丢）
        //   🔴 P5 自动扩批：AE 会"一次抽 N 份、只推一次"，而<b>集成电路这类输入每批只抽 1 个</b>
        //   （判据见 shanhai$isBatchConstantInput）⇒ 必须把它们排除出"取 min"，
        //   否则份数会被算成 1、AE 等 N 倍产物 ⇒ 永久挂死。见 CreationCraftMath#craftsOf(Map,Map,Set)。
        final Set<AEKey> batchConstant = shanhai$batchConstantKeys(accounted);
        final long crafts = CreationCraftMath.craftsOf(accounted, perCraft, batchConstant);
        if (crafts <= 0L) {
            LOGGER.warn("{} pos={} pattern={} 拒单：份数=0（账目不足一份 / 单份需求解析失败）⇒ "
                            + "AE 原因路退回，本机未扣任何东西。账目={} / 单份需求={}",
                    PREFIX, shanhai$posTag(), label, shanhai$summarizeRaw(accounted), shanhai$summarizeRaw(perCraft));
            return false;
        }

        // ⑥ 产物（多产物全部登记）
        final List<CreationCraftMath.Product<AEKey>> perCraftOutputs = shanhai$perCraftOutputs(raw);
        if (perCraftOutputs.isEmpty()) {
            LOGGER.warn("{} pos={} pattern={} 拒单：样板没有产物（getOutputs 为空）", PREFIX, shanhai$posTag(), label);
            return false;
        }

        // ⑦ 余量退回（🔴 只有"已决定收下"才退；return false 的路径已经在上面全部返回掉了）
        final Map<AEKey, Long> leftovers = CreationCraftMath.leftoversOf(accounted, perCraft, crafts);
        shanhai$refundLeftovers(leftovers, label);

        // ⑧ 容器物告警（第一版不返还）
        shanhai$warnContainerLoss(raw, accounted, label);

        // ⑨ 入队（🔴 记下"最早允许产出的绝对 tick" = 现在 + 10；见 {@link PendingOrder#dueTick}）
        final long dueTick = pacing.dueTickFor(pacing.now());
        final PendingOrder order = new PendingOrder(raw, label, multiplier, perCraftOutputs, crafts, dueTick);
        backlog.add(order);
        pacing.noteArrival();
        shanhai$noteAccepted("样板=" + label + " 份数=" + crafts + " 倍率=×" + multiplier
                + " 产物种类=" + perCraftOutputs.size());
        if (!firstAcceptLogged) {
            firstAcceptLogged = true;
            LOGGER.info("{} pos={} pattern={} 【首次接单样例】crafts={} multiplier=×{} 产物种类={} ⇒ 积压 {} 单"
                            + "（本单最早在 {} tick 后产出；此后只打每秒汇总行与异常行）",
                    PREFIX, shanhai$posTag(), label, crafts, multiplier, perCraftOutputs.size(),
                    backlog.size(), PrimordialCreationCompilationMatrix.TICK_DURATION);
        }
        return true;
    }

    @Override
    public boolean isBusy() {
        // 只有"真接不下"才算忙：队列满 或 有货没插进网络（网络断了的话再收单只会继续堆）。
        // ⚠️ 处理中不返回 true —— 上游端口恒返回 false，AE 的吞吐靠它；我们的流控是队列上限。
        return backlog.size() >= MAX_PENDING_ORDERS || !unsent.isEmpty();
    }

    // ═════════════════════════════ 自动扩批（唯一允许的 gtlcore 接缝） ═════════════════════════════

    /**
     * <b>AE 是否可以把"整单剩余 N 份"合并成一次 {@code pushPattern}</b> —— 恒 {@code true}。
     *
     * <h2>为什么需要这个标记接口（取证结论，附偏移）</h2>
     * AE 原生只按"1 份"推样板；把 N 份合并成一次是 <b>gtlcore</b> 干的，
     * 判据全在 {@code org.gtlcore.gtlcore.mixin.ae2.logic.CraftingCpuLogicMixin#executeCrafting}：
     * <pre>
     *   偏移 211: boolean isProcessing = details.supportsPushInputsToExternalInventory();
     *   偏移 227: boolean autoExpand   = CraftingPatternAutoExpand.canAutoExpand(isProcessing, provider);
     *   偏移 228: long operations      = CraftingPatternAutoExpand.getOperations(isProcessing, provider, details, taskProgress.getValue());
     *   偏移 231: AEUtils#extractForProcessingPattern(details, inventory, expectedOutputs, operations)   ← 一次抽 N 份
     *   偏移 244: provider.pushPattern(details, craftingContainer)                                       ← 只推一次
     * </pre>
     * 而 {@code CraftingPatternAutoExpand#canAutoExpand} 的原文是
     * <pre>
     *   if (!processingPattern) return false;
     *   if (provider instanceof IMEPatternPartMachine || provider instanceof IMECraftIOPart) return true;
     *   ...
     *   return provider instanceof IAutoExpandSettings s ? s.isPatternAutoExpand() : ...;
     * </pre>
     * ⇒ 我们只要 implements {@link IAutoExpandSettings} 并返回 {@code true}
     * 就能进"合并"那一支，<b>不需要</b>变成上游那四种机器，也不需要任何别的 gtlcore 代码。
     *
     * <h2>⚠️ 光有本方法<b>不够</b>（P5 曾经以为够了，P6 实机推翻了那一版注释）</h2>
     * 下面这段是 <b>P6 的前置条件</b>，缺了它上面那一串偏移<b>一个都走不到</b>：
     * <pre>
     *   CraftingPatternAutoExpand#canAutoExpand 第 0-5 字节：
     *     0: iload_0（processingPattern = details.supportsPushInputsToExternalInventory()）
     *     1: ifne 6
     *     4: iconst_0 / 5: ireturn      ← false 就在这里直接返回 false
     *     6..: 才是 instanceof 那四支（IAutoExpandSettings 在偏移 40）
     * </pre>
     * <p>🔴 <b>P5 的这段注释写错过一次</b>（原文断言"合成样板也是 true、不需要转处理样板"）。
     * 那句话只 {@code javap} 了 {@code AECraftingPattern} <b>自己</b>有没有覆写，<b>没看父接口</b>：
     * 它 implements {@code IMolecularAssemblerSupportedPattern}，而该接口把这个 default
     * 定成 {@code false}（{@code 0: iconst_0 / 1: ireturn}），比 {@code IPatternDetails} 的
     * default {@code true} <b>更具体</b> ⇒ 按 JVMS 5.4.3.3 的 maximally-specific 解析，
     * 调 {@code AECraftingPattern} 实例上的这个方法得到的是 <b>false</b>。
     * <p>⇒ 于是修正落在<b>包装层</b>而不是这里：{@link CreationScaledPattern} 自己覆写这个方法
     * 恒返回 {@code true}（它只 implements {@code IPatternDetails}，没有第二个更具体的父接口，
     * 覆写一定生效），并且 {@code getAvailablePatterns()} <b>恒包装</b> ⇒ AE 手里那份 details
     * 永远是包装对象，答 true。<b>本类（执行器）仍然只是标记接口的实现方，一个字没变。</b>
     *
     * <h2>本方法的语义边界</h2>
     * 它是一个<b>纯标记</b>：只被 gtlcore 读，不参与我们自己的任何算术；调用它<b>不会</b>触发任何代码。
     * 恒 {@code true}（用户拍板「A 挂 gtlcore 的标记接口」；不设开关，避免"两块牌子两种行为"）。
     */
    @Override
    public boolean isPatternAutoExpand() {
        return true;
    }

    /**
     * 见 {@link #isPatternAutoExpand()}：本机<b>没有</b>扩批开关，恒为开。
     *
     * <p>⚠️ 不落盘、不进 GUI：接口要求这个方法存在，但本工程不提供关闭路径
     * （关掉它等于回到"AE 每 tick 推 1 份"的 3.85 万行刷屏形态）。
     */
    @Override
    public void setPatternAutoExpand(boolean expand) {
        // 故意空实现：本机的扩批恒开（见上）。
    }

    /**
     * 这一类输入<b>每批只抽 1 个</b>（gtlcore 的 {@code AEUtils#isIntegratedCircuit}：GT 集成电路）。
     *
     * <p>判据与上游逐字同源：{@code key instanceof AEItemKey && GTItems.INTEGRATED_CIRCUIT.is(item)}
     * （上游原文见 {@code AEUtils#isIntegratedCircuit}）。我们只按<b>物品</b>判，不按槽位/下标。
     * <p>⚠️ 用 {@code try/catch} 包住：判据拿不到就当"不是"（宁可多算一份 min，也不要因为一次探测打断接单）。
     */
    private static boolean shanhai$isBatchConstantInput(@Nullable AEKey key) {
        if (!(key instanceof appeng.api.stacks.AEItemKey itemKey)) {
            return false;
        }
        try {
            return com.gregtechceu.gtceu.common.data.GTItems.INTEGRATED_CIRCUIT.is(itemKey.getItem());
        } catch (Throwable t) {
            return false;
        }
    }

    /** 从账目里挑出"每批只抽 1 个"的那些 key（交给 {@link CreationCraftMath#craftsOf(Map, Map, java.util.Set)}）。 */
    private static java.util.Set<AEKey> shanhai$batchConstantKeys(Map<AEKey, Long> accounted) {
        java.util.Set<AEKey> keys = new java.util.LinkedHashSet<>();
        for (AEKey key : accounted.keySet()) {
            if (shanhai$isBatchConstantInput(key)) {
                keys.add(key);
            }
        }
        return keys;
    }

    // ═════════════════════════════ 账目 / 单份需求 / 产物 的取数 ═════════════════════════════

    /** {@code KeyCounter[]} → {@code AEKey → long}（多个组里的同一个 key 累加）。 */
    private static Map<AEKey, Long> shanhai$accountingOf(KeyCounter[] table) {
        final Map<AEKey, Long> accounted = new LinkedHashMap<>();
        if (table == null) {
            return accounted;
        }
        for (KeyCounter counter : table) {
            if (counter == null) {
                continue;
            }
            for (Object2LongMap.Entry<AEKey> entry : counter) {
                final AEKey key = entry.getKey();
                final long amount = entry.getLongValue();
                if (key == null || amount <= 0L) {
                    continue;
                }
                final Long previous = accounted.get(key);
                accounted.put(key, previous == null ? amount : CreationCraftMath.saturatedAdd(previous, amount));
            }
        }
        return accounted;
    }

    /**
     * 单份需求 = 每一样输入 {@code 候选数量 × IInput#getMultiplier()}。
     *
     * <p>候选怎么挑：AE 的账目里放的可能是<b>替换材质</b>（标签/可替代物品）而不是
     * {@code getPossibleInputs()[0]} ⇒ 逐个数过去，<b>取账目里真的出现的那一个</b>。
     * 一个都没出现 ⇒ 这一单的账目和样板对不上（或样板被改过）⇒ 返回 {@code null} 让调用方拒单。
     *
     * <p>🔴 与上游的差别（P2 已做）：上游只看 {@code getInputs()[0]}，我们<b>每一组都核</b>。
     *
     * @return 单份需求表；解析失败返回 {@code null}（注意：与"空表"不同）
     */
    @Nullable
    private static Map<AEKey, Long> shanhai$perCraftRequirements(IPatternDetails raw, Map<AEKey, Long> accounted) {
        final IPatternDetails.IInput[] inputs = raw.getInputs();
        if (inputs == null || inputs.length == 0) {
            return null;
        }
        final Map<AEKey, Long> perCraft = new LinkedHashMap<>();
        for (IPatternDetails.IInput input : inputs) {
            if (input == null) {
                return null;
            }
            final GenericStack[] possible = input.getPossibleInputs();
            if (possible == null || possible.length == 0) {
                return null;
            }
            AEKey matched = null;
            long unitAmount = 0L;
            for (GenericStack candidate : possible) {
                if (candidate == null || candidate.what() == null) {
                    continue;
                }
                if (accounted.containsKey(candidate.what())) {
                    matched = candidate.what();
                    unitAmount = candidate.amount();
                    break;
                }
            }
            if (matched == null) {
                return null;
            }
            final long perCraftAmount = CreationCraftMath.saturatingMultiply(
                    Math.max(1L, input.getMultiplier()), Math.max(0L, unitAmount));
            final Long previous = perCraft.get(matched);
            perCraft.put(matched, previous == null
                    ? perCraftAmount
                    : CreationCraftMath.saturatedAdd(previous, perCraftAmount));
        }
        return perCraft;
    }

    /** 单份产物表（<b>全部</b>产物；上游只取 {@code getOutputs()[0]} 是它的短板）。 */
    private static List<CreationCraftMath.Product<AEKey>> shanhai$perCraftOutputs(IPatternDetails raw) {
        final List<CreationCraftMath.Product<AEKey>> outputs = new ArrayList<>();
        final GenericStack[] stacks = raw.getOutputs();
        if (stacks == null) {
            return outputs;
        }
        for (GenericStack stack : stacks) {
            if (stack == null || stack.what() == null || stack.amount() <= 0L) {
                continue;
            }
            outputs.add(new CreationCraftMath.Product<>(stack.what(), stack.amount()));
        }
        return outputs;
    }

    // ═════════════════════════════ 余量退回 / 容器物告警 ═════════════════════════════

    /**
     * 把"整数除法截断出来的零头"记进待退回队列（{@link #pendingRefund}），由下一 tick 用
     * {@code MEStorage#insert} <b>退回 AE 网络</b>（不吞）。
     *
     * <p>🔴 为什么必须有这一步：上游 {@code multiplier = 账目 / 需求} 是 {@code ldiv}（向下取整），
     * 余量既不产出也不退还 —— AE 已经扣走的料就这么白扣了。用户点名要修，所以这里显式退回。
     *
     * <p>为什么不在 {@code pushPattern} 里当场插：见 {@link #pendingRefund} 的注释
     * （不在 AE 的 CPU 循环里往同一个网络插货）。排队与当场插对玩家完全等价（最多晚 1 tick）。
     */
    private void shanhai$refundLeftovers(Map<AEKey, Long> leftovers, String label) {
        if (leftovers == null || leftovers.isEmpty()) {
            return;
        }
        final StringBuilder detail = new StringBuilder();
        boolean any = false;
        for (Map.Entry<AEKey, Long> entry : leftovers.entrySet()) {
            final AEKey key = entry.getKey();
            final long amount = entry.getValue() == null ? 0L : entry.getValue();
            if (key == null || amount <= 0L) {
                continue;
            }
            shanhai$addPendingRefund(key, amount);
            if (any) {
                detail.append(", ");
            }
            detail.append(amount).append("x ").append(shanhai$keyId(key));
            any = true;
        }
        if (any) {
            LOGGER.info("{} pos={} pattern={} 余量退回：{}（整数除法截断的零头，下一 tick 用 MEStorage#insert 退回 AE 网络）",
                    PREFIX, shanhai$posTag(), label, detail);
        }
    }

    /** 记一笔待退回的余量（同一样原料累加）。 */
    private void shanhai$addPendingRefund(AEKey what, long amount) {
        if (what == null || amount <= 0L) {
            return;
        }
        final Long previous = pendingRefund.get(what);
        pendingRefund.put(what, previous == null ? amount : CreationCraftMath.saturatedAdd(previous, amount));
    }

    /**
     * <b>容器物返还：第一版不做</b> —— 遇到"会返还容器物"的合成，打一条 WARN 说明会丢，产物照出。
     *
     * <p>判据用 AE 自己的公开 API：{@code IInput#getRemainingKey(实际入料的 key)} 非 null
     * （= 这个输入会留下一个容器物，例如水桶 / 岩浆桶 / 不毁的工具）。
     * <p>⚠️ <b>以后要加开关就加在这里 + {@link #shanhai$runCycle()} 出货那一步</b>：
     * <ul>
     *   <li>开关字段（{@code @Persisted @DescSynced boolean returnContainers}，放控制器）；</li>
     *   <li>出货时若开关开：按 {@code getRemainingKey} 把容器物原样 {@code insert} 回网络；</li>
     *   <li>或者走 F-2 形态（真摆 3×3 网格 + {@code fillCraftingGrid} + {@code assemble} +
     *       {@code getRemainingItems}），代价见 {@code temp/molmanip/并行与样板.md} §B-F。</li>
     * </ul>
     */
    private void shanhai$warnContainerLoss(IPatternDetails raw, Map<AEKey, Long> accounted, String label) {
        final Level level = machine.getLevel();
        if (level == null) {
            return;
        }
        final List<String> lost = new ArrayList<>(2);
        final IPatternDetails.IInput[] inputs = raw.getInputs();
        if (inputs == null) {
            return;
        }
        for (IPatternDetails.IInput input : inputs) {
            if (input == null) {
                continue;
            }
            for (AEKey key : accounted.keySet()) {
                try {
                    if (!input.isValid(key, level)) {
                        continue;
                    }
                    final AEKey remaining = input.getRemainingKey(key);
                    if (remaining != null) {
                        lost.add(shanhai$keyId(key) + " ⇒ 容器物 " + shanhai$keyId(remaining));
                    }
                } catch (Throwable ignored) {
                    // 判据拿不到就当"不知道"（不许因为一次探测把接单流程打断）。
                }
            }
        }
        if (!lost.isEmpty()) {
            LOGGER.warn("{} pos={} pattern={} 容器物返还：第一版不做 —— 本单的容器物会丢 {}（产物照出；"
                            + "以后要加就在 CreationCraftExecutor#shanhai$warnContainerLoss 这里接开关）",
                    PREFIX, shanhai$posTag(), label, lost);
        }
    }

    // ═════════════════════════════ 10 tick 周期：跨样板合并 → 产物一次全出 ═════════════════════════════

    /**
     * 每 tick 的推进：先补发上轮没插进去的货，再按 {@link CreationPacing} 的时钟走 10 tick 周期。
     *
     * <p>🔴 时长<b>只</b>来自 {@link PrimordialCreationCompilationMatrix#TICK_DURATION}（= 10），
     * 不引入任何别的时长来源（不随等级、不随核心、不随电）。
     *
     * <p>⛔ 旧实现（P5 删掉的，原文留档）：
     * <pre>
     *   if (backlog.isEmpty()) { cycleProgress = 0; return; }          // ← 空闲把时钟清零（时钟不连续）
     *   if (++cycleProgress &lt; TICK_DURATION) return;                   // ← 只看"周期过了几 tick"
     *   cycleProgress = 0; shanhai$runCycle();                          // ← 不看每单到了多久
     * </pre>
     * 后果两条：① 在周期进度 9 到达的单<b>下一 tick 就完成</b>（"10 tick"是假的）；
     * ② 每完成一单就打一行 INFO（5 分钟 3.85 万行）。
     *
     * @param cycleBoundary 本 tick 是不是周期边界（{@link CreationPacing#advance()} 的返回值）
     */
    private void shanhai$tickCreation(boolean cycleBoundary) {
        shanhai$flushPendingInserts();
        if (cycleBoundary) {
            shanhai$runCycle();
        }
        shanhai$logSummaryIfDue();
    }

    /**
     * 执行一个周期：<b>把队列里不同样板的积压合并成一次</b>做完、产物一次全出（照上游的合并语义）。
     *
     * <p>并行预算 = {@link CreationPatternHost#getCreationParallelCap()}：
     * <ul>
     *   <li>普通模式（{@code 8^等级}）⇒ 本周期最多做这么多份，做不完的留在队列里下个周期继续；</li>
     *   <li>哨兵（等级 ≥ 15）⇒ 预算 = {@link Long#MAX_VALUE} ⇒ <b>本周期到期的份数一次全做完</b>。</li>
     * </ul>
     *
     * <p>🔴 <b>P5 起多了一道硬闸门</b>：{@link CreationPacing#isDue(long)} —— 只有
     * "到达 tick + 10 ≤ 现在"的单才允许在本周期被做掉。于是：
     * <pre>
     *   同一 tick 被 AE 推进来的 100 单（并行上限 = 64）
     *     → 第 1..9 tick：一份都不产出（全部未到期）
     *     → 第 10 tick ：做完 64 单（= 并行上限），剩 36 单
     *     → 第 20 tick ：做完剩下 36 单
     * </pre>
     * 也就是「同一 tick 到达的多单按并行上限分摊到后续周期」，而不是"到达即完成"。
     *
     * <p>⛔ 旧实现每单一行 INFO（{@code crafts=/parallel=/ret=…}），5 分钟 3.85 万行。
     * 现在改成<b>每秒最多一行汇总</b>（{@link #shanhai$logSummaryIfDue()}），
     * 单行日志只留给<b>异常</b>（插不进网络 / 余量退不回 / 队列满 / 拒单）＋首次接单/首次产出样例各一行。
     */
    private void shanhai$runCycle() {
        if (backlog.isEmpty()) {
            return;
        }
        final CreationPatternHost host = shanhai$patternHost();
        if (host == null) {
            // 结构散了/被拆：积压留着（AE 已付账），等结构回来继续。不丢，也不刷屏。
            shanhai$warnThrottled("cycle-no-host", THROTTLED_WARN_INTERVAL,
                    "积压还在（{} 单），但结构没成型 ⇒ 本周期不跑；不丢，结构恢复后继续",
                    backlog.size());
            return;
        }
        final long cap = host.getCreationParallelCap();
        final boolean infinite = CreationCraftMath.isInfinite(cap);
        long budget = CreationCraftMath.cycleBudget(cap);
        if (budget <= 0L) {
            return;
        }
        long ordersDone = 0L;
        long craftsDone = 0L;
        long itemsDone = 0L;
        final Iterator<PendingOrder> iterator = backlog.iterator();
        while (iterator.hasNext() && budget > 0L) {
            final PendingOrder order = iterator.next();
            // 🔴 硬闸门：还没满 10 tick 的单，本周期<b>一份都不做</b>（这正是"10 tick 是真的"的落点）
            if (!pacing.isDue(order.dueTick)) {
                continue;
            }
            final long doNow = CreationCraftMath.craftsThisCycle(order.craftsRemaining, budget);
            if (doNow <= 0L) {
                iterator.remove(); // 欠账已为 0（理论上早该出队）；清掉防止空转
                continue;
            }
            // 产物 = 单份产物 × 本次份数 × 倍率（多产物全部出）
            final Map<AEKey, Long> outputs = CreationCraftMath.outputsOf(order.perCraftOutputs, doNow, order.multiplier);
            final String ret = shanhai$insertOutputs(outputs);
            order.craftsRemaining -= doNow;
            budget -= doNow;
            ordersDone++;
            craftsDone = CreationCraftMath.saturatedAdd(craftsDone, doNow);
            itemsDone = CreationCraftMath.saturatedAdd(itemsDone, shanhai$totalAmount(outputs));
            if (order.craftsRemaining <= 0L) {
                iterator.remove();
            }
            if (!"ok".equals(ret)) {
                // 🔴 只对异常打单行（插不进网络 / 产物为空的份数会进 unsent，每 tick 重试）
                LOGGER.warn("{} pos={} pattern={} 本周期产出异常：crafts={} multiplier={} out={} parallel={} mode={} ticks={} ret={}",
                        PREFIX, shanhai$posTag(), order.label, doNow, order.multiplier,
                        shanhai$summarize(outputs), infinite ? "infinity" : String.valueOf(cap),
                        infinite ? "infinity" : "normal",
                        PrimordialCreationCompilationMatrix.TICK_DURATION, ret);
            }
            if (!firstOutputLogged) {
                firstOutputLogged = true;
                LOGGER.info("{} pos={} pattern={} 【首次产出样例】crafts={} parallel={} multiplier={} out={} mode={} ticks={} ret={}"
                                + "（此后只打每秒汇总行）",
                        PREFIX, shanhai$posTag(), order.label, doNow,
                        infinite ? "infinity" : String.valueOf(cap), order.multiplier,
                        shanhai$summarize(outputs), infinite ? "infinity" : "normal",
                        PrimordialCreationCompilationMatrix.TICK_DURATION, ret);
            }
        }
        pacing.recordCycle(ordersDone, craftsDone, itemsDone);
    }

    /**
     * 每秒最多一行汇总（{@code [SHANHAI-CREATION] pos=… 本秒 接 N 单 / 完成 M 单 / 产物 K ｜ 累计 单 A / 产物 B
     *  ｜ 并行=… ｜ 队列=… ｜ 进度 p/10 ｜ mode=…}）。
     *
     * <p>判据全部来自 {@link CreationPacing}：只有"整秒"且<b>这一秒真的有活动</b>（收单 / 完成 / 产物任一非 0）
     * 才打一行 ⇒ 空闲的机器<b>一行都不刷</b>，"汇总行数 ≤ 秒数"是结构上成立的。
     */
    private void shanhai$logSummaryIfDue() {
        if (!pacing.isSecondBoundary()) {
            return;
        }
        if (!pacing.hasActivityThisSecond()) {
            pacing.endSecond();
            return;
        }
        final CreationPatternHost host = shanhai$patternHost();
        final boolean infinite = host != null
                && CreationCraftMath.isInfinite(host.getCreationParallelCap());
        LOGGER.info(CreationPacing.summaryLine(PREFIX, shanhai$posTag(), pacing, shanhai$capForLogOrUnknown(),
                backlog.size(), getPendingCrafts(), pacing.cycleProgress(),
                host == null ? "no-host" : (infinite ? "infinity" : "normal")));
        pacing.noteSummaryLine();
        pacing.endSecond();
    }

    /** 产物表的总个数（只用于汇总计数；跳过的非法项与 {@link #shanhai$summarize(Map)} 同一口径）。 */
    private static long shanhai$totalAmount(Map<AEKey, Long> outputs) {
        long total = 0L;
        if (outputs == null || outputs.isEmpty()) {
            return 0L;
        }
        for (Map.Entry<AEKey, Long> entry : outputs.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue() <= 0L) {
                continue;
            }
            total = CreationCraftMath.saturatedAdd(total, entry.getValue());
        }
        return total;
    }

    /**
     * 一次出货：逐样产物 {@code MEStorage#insert}。
     *
     * @return {@code "ok"} / {@code "fail:<原因>"}（失败项会进 {@link #unsent} 每 tick 重试，不静默丢）
     */
    private String shanhai$insertOutputs(Map<AEKey, Long> outputs) {
        if (outputs == null || outputs.isEmpty()) {
            return "fail:no-output";
        }
        final StringBuilder failure = new StringBuilder();
        for (Map.Entry<AEKey, Long> entry : outputs.entrySet()) {
            final AEKey key = entry.getKey();
            final long amount = entry.getValue() == null ? 0L : entry.getValue();
            if (key == null || amount <= 0L) {
                continue;
            }
            final long remaining = shanhai$insertIntoNetwork(key, amount);
            if (remaining > 0L) {
                shanhai$addUnsent(key, remaining);
                if (failure.length() > 0) {
                    failure.append(" + ");
                }
                failure.append(remaining).append("x ").append(shanhai$keyId(key)).append(" 留待重试");
            }
        }
        return failure.length() == 0 ? "ok" : "fail:insert(" + failure + ")";
    }

    /**
     * 往 AE 网络里插货（<b>唯一的"出"口</b>；余量退回与产物回网都走这里）。
     *
     * <p>重试 {@link #INSERT_ATTEMPTS} 次；还是插不完 ⇒ <b>返回到数</b>交给调用方进 {@link #unsent}
     * （"不许静默丢货"）。全程 {@code Actionable.MODULATE}（真插），源用 {@link IActionSource#empty()}。
     *
     * @return 没插进去的量（0 = 全部插进去了）
     */
    private long shanhai$insertIntoNetwork(AEKey what, long amount) {
        if (what == null || amount <= 0L) {
            return 0L;
        }
        long remaining = amount;
        for (int attempt = 1; attempt <= INSERT_ATTEMPTS; attempt++) {
            final MEStorage storage = shanhai$networkStorage();
            if (storage == null) {
                shanhai$warnThrottled("no-grid-" + shanhai$keyId(what), THROTTLED_WARN_INTERVAL,
                        "插货失败：拿不到 AE 网络的 MEStorage（网格没连上），剩余 {}x {} 留待重试",
                        remaining, shanhai$keyId(what));
                return remaining;
            }
            try {
                final long inserted = storage.insert(what, remaining, Actionable.MODULATE, IActionSource.empty());
                if (inserted > 0L) {
                    remaining -= Math.min(inserted, remaining);
                }
            } catch (Throwable t) {
                LOGGER.warn("{} pos={} 插货抛异常（第 {}/{} 次）：{} — {}",
                        PREFIX, shanhai$posTag(), attempt, INSERT_ATTEMPTS, shanhai$keyId(what), t.toString());
            }
            if (remaining <= 0L) {
                return 0L;
            }
            if (attempt < INSERT_ATTEMPTS) {
                LOGGER.warn("{} pos={} 插货未完成（第 {}/{} 次：还差 {}x {}，网络可能满了）⇒ 立即重试",
                        PREFIX, shanhai$posTag(), attempt, INSERT_ATTEMPTS, remaining, shanhai$keyId(what));
            }
        }
        return remaining;
    }

    /** 取当前网格的库存视图（{@code MEStorage}）；没连上返回 {@code null}。 */
    @Nullable
    private MEStorage shanhai$networkStorage() {
        final IManagedGridNode node = machine.creationMainNode();
        if (node == null) {
            return null;
        }
        final IGrid grid = node.getGrid();
        if (grid == null) {
            return null;
        }
        final IStorageService service = grid.getStorageService();
        return service == null ? null : service.getInventory();
    }

    /** 把插不进去的量记进 {@link #unsent}（同一样产物累加）。 */
    private void shanhai$addUnsent(AEKey what, long amount) {
        if (what == null || amount <= 0L) {
            return;
        }
        final Long previous = unsent.get(what);
        unsent.put(what, previous == null ? amount : CreationCraftMath.saturatedAdd(previous, amount));
        LOGGER.warn("{} pos={} 产物暂存待重试：{}x {}（网络暂时插不进去；每 tick 重试一次，不静默丢）",
                PREFIX, shanhai$posTag(), amount, shanhai$keyId(what));
    }

    /** 每 tick 把"待插队列"里的货插进网络（插进去就删；日志限流）。先退余量，再补产物。 */
    private void shanhai$flushPendingInserts() {
        shanhai$flushPendingRefund();
        if (unsent.isEmpty()) {
            return;
        }
        final Map<AEKey, Long> snapshot = new LinkedHashMap<>(unsent);
        unsent.clear();
        long stillStuck = 0L;
        for (Map.Entry<AEKey, Long> entry : snapshot.entrySet()) {
            final AEKey key = entry.getKey();
            final long amount = entry.getValue() == null ? 0L : entry.getValue();
            if (key == null || amount <= 0L) {
                continue;
            }
            final long remaining = shanhai$insertIntoNetwork(key, amount);
            if (remaining > 0L) {
                unsent.put(key, remaining);
                stillStuck = CreationCraftMath.saturatedAdd(stillStuck, remaining);
            }
        }
        if (stillStuck > 0L && tickCounter % UNSENT_LOG_INTERVAL == 0) {
            LOGGER.warn("{} pos={} 还有 {} 样产物共 {} 个没插进网络（每 tick 重试中；不会静默丢弃）",
                    PREFIX, shanhai$posTag(), unsent.size(), stillStuck);
        }
    }

    /** 把 {@link #pendingRefund} 里的余量退回 AE 网络（插不进去的留在表里下一 tick 再试）。 */
    private void shanhai$flushPendingRefund() {
        if (pendingRefund.isEmpty()) {
            return;
        }
        final Map<AEKey, Long> snapshot = new LinkedHashMap<>(pendingRefund);
        pendingRefund.clear();
        for (Map.Entry<AEKey, Long> entry : snapshot.entrySet()) {
            final AEKey key = entry.getKey();
            final long amount = entry.getValue() == null ? 0L : entry.getValue();
            if (key == null || amount <= 0L) {
                continue;
            }
            final long remaining = shanhai$insertIntoNetwork(key, amount);
            if (remaining > 0L) {
                shanhai$addPendingRefund(key, remaining);
            } else {
                LOGGER.info("{} pos={} 余量已退回 AE 网络：{}x {}", PREFIX, shanhai$posTag(), amount, shanhai$keyId(key));
            }
        }
    }

    // ═════════════════════════════ 日志/诊断小工具 ═════════════════════════════

    /**
     * 位置标签：{@code <dim>@<x,y,z>}。
     *
     * <p>🔴 为什么日志必须带它：同类机器可能有好几台，没有 pos 根本判不出是哪一个在接单/出货。
     * <p>P2 里这个值<b>优先取控制器坐标</b>（端口坐标只是兜底）；本轮宿主就是控制器，
     * 所以输出的字符串与 P2 在成型机器上<b>逐字相同</b>。
     */
    private String shanhai$posTag() {
        final Level level = machine.getLevel();
        final BlockPos pos = machine.getPos();
        final String dim = level == null ? "?" : level.dimension().location().toString();
        final BlockPos at = pos == null ? BlockPos.ZERO : pos;
        return dim + "@" + at.getX() + "," + at.getY() + "," + at.getZ();
    }

    /** 样板名：主产物的显示名（比"处理样板"这种物品名更能区分是哪一块样板）。 */
    private static String shanhai$labelOf(IPatternDetails raw) {
        final GenericStack[] outputs = raw.getOutputs();
        if (outputs != null) {
            for (GenericStack stack : outputs) {
                if (stack == null || stack.what() == null) {
                    continue;
                }
                final String name = stack.what().getDisplayName().getString();
                return name == null || name.isEmpty() ? shanhai$keyId(stack.what()) : name;
            }
        }
        final Object definition = raw.getDefinition();
        return definition == null ? String.valueOf(raw) : String.valueOf(definition);
    }

    private static String shanhai$keyId(AEKey key) {
        if (key == null) {
            return "null";
        }
        try {
            return key.getId().toString();
        } catch (Throwable t) {
            return String.valueOf(key);
        }
    }

    /** 产物汇总（最多 6 项，多了写"另有 N 种"，保证一行日志）。 */
    private static String shanhai$summarize(Map<AEKey, Long> outputs) {
        if (outputs == null || outputs.isEmpty()) {
            return "-";
        }
        final StringBuilder sb = new StringBuilder();
        int printed = 0;
        for (Map.Entry<AEKey, Long> entry : outputs.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue() <= 0L) {
                continue;
            }
            if (printed > 0) {
                sb.append(", ");
            }
            sb.append(entry.getValue()).append("x ").append(shanhai$keyId(entry.getKey()));
            printed++;
            if (printed >= 6) {
                final int rest = outputs.size() - printed;
                if (rest > 0) {
                    sb.append(", …(另有 ").append(rest).append(" 种)");
                }
                break;
            }
        }
        return sb.length() == 0 ? "-" : sb.toString();
    }

    /** 任意 {@code AEKey → long} 表的汇总（拒单日志里给账目/需求用）。 */
    private static String shanhai$summarizeRaw(Map<AEKey, Long> table) {
        return shanhai$summarize(table);
    }

    /** 只用于日志：这一台机器此刻的并行上限（含"无限"）。 */
    private String shanhai$capForLog(CreationPatternHost host) {
        try {
            final long cap = host.getCreationParallelCap();
            return CreationCraftMath.isInfinite(cap) ? "infinity" : String.valueOf(cap);
        } catch (Throwable t) {
            return "?";
        }
    }

    /** {@link #shanhai$capForLog(CreationPatternHost)} 的"取不到宿主也不炸"形态（汇总行用它）。 */
    private String shanhai$capForLogOrUnknown() {
        final CreationPatternHost host = shanhai$patternHost();
        return host == null ? "?" : shanhai$capForLog(host);
    }

    /** 同一条告警每 {@code interval} tick 最多打一次（避免"结构散了"这类状态每 tick 刷屏）。 */
    private void shanhai$warnThrottled(String key, int interval, String format, Object... args) {
        final Integer last = throttledWarnTicks.get(key);
        if (last != null && tickCounter - last < interval && tickCounter >= last) {
            return;
        }
        throttledWarnTicks.put(key, tickCounter);
        if (throttledWarnTicks.size() > 64) {
            throttledWarnTicks.clear();
            throttledWarnTicks.put(key, tickCounter);
        }
        LOGGER.warn(PREFIX + " pos={} " + format, shanhai$posTagAndArgs(args));
    }

    /** 把 pos 拼进 WARN 的参数表（{@code warnThrottled} 的 format 里<b>不</b>写 pos，由这里统一加）。 */
    private Object[] shanhai$posTagAndArgs(Object... args) {
        final Object[] merged = new Object[args.length + 1];
        merged[0] = shanhai$posTag();
        System.arraycopy(args, 0, merged, 1, args.length);
        return merged;
    }

    /** 接单记录（环形，最多 {@link #ACCEPTED_LOG_CAP} 条）。 */
    private void shanhai$noteAccepted(String line) {
        acceptedOrders.add(line);
        while (acceptedOrders.size() > ACCEPTED_LOG_CAP) {
            acceptedOrders.remove(0);
        }
    }

    // ═════════════════════════════ 只读视图（给界面 / 排查用） ═════════════════════════════

    /** 当前积压单数（= 还没做完的样板单数）。 */
    public int getQueuedOrderCount() {
        return backlog.size();
    }

    /** 还欠着的总份数（跨全部积压单）。 */
    public long getPendingCrafts() {
        long total = 0L;
        for (PendingOrder order : backlog) {
            total = CreationCraftMath.saturatedAdd(total, order.craftsRemaining);
        }
        return total;
    }

    /** 还没插进网络的产物种类数（正常应恒为 0）。 */
    public int getUnsentKindCount() {
        return unsent.size();
    }

    /**
     * 本周期已过的 tick 数（{@code 0..9}）—— Jade 的「本周期进度 p/10」读它。
     *
     * <p>🔴 <b>这是内部时钟，不代表"有活在干"</b>：它单调地走（永不清零，P5 的修复），
     * 队列空时也会从 0 走到 9。⇒ <b>显示层必须先判队列</b>（队列 = 0 就画 0/10，
     * 见 {@link CreationDisplayLines#queueLine}），否则玩家会看到"没下单进度还在动"的假读数。
     */
    public int getCycleProgress() {
        return pacing.cycleProgress();
    }

    /** 单调 tick 时钟（排查用；也用于"这一单还要几 tick 到期"）。 */
    public long getNowTick() {
        return pacing.now();
    }

    /**
     * 最快到期的那一单还差几 tick。
     *
     * <p>🔴 <b>没有积压时返回 0，而这个 0 不是"马上到期"的意思</b> —— 它的含义是"没有任何单在等"。
     * 直接印成「最快 0 tick 后到期」就是在说谎（用户实机报的缺陷之一）。
     * ⇒ 显示层必须先判队列（队列 = 0 ⇒ 画 {@code 最快到期 —}），见 {@link CreationDisplayLines#queueLine}。
     */
    public long getTicksUntilNextDue() {
        long best = Long.MAX_VALUE;
        for (PendingOrder order : backlog) {
            final long left = pacing.ticksUntilDue(order.dueTick);
            if (left < best) {
                best = left;
            }
        }
        return best == Long.MAX_VALUE ? 0L : Math.max(0L, best);
    }

    /** 这一台机器"一个周期多少 tick"（= {@link PrimordialCreationCompilationMatrix#TICK_DURATION}）。 */
    public int getTickDuration() {
        return pacing.durationTicks();
    }

    /** 最近若干条接单记录的快照（只读；排查"AE 到底派没派进来"用）。 */
    public List<String> getAcceptedOrderLog() {
        return Collections.unmodifiableList(acceptedOrders);
    }

    /** 一行可读状态（给界面 / Jade / 排查用）。 */
    public String getCreationStatusLine() {
        return PREFIX + " pos=" + shanhai$posTag() + " 积压=" + backlog.size() + " 单 / 欠 " + getPendingCrafts()
                + " 份；未插进网络 " + unsent.size() + " 种；待退余量 " + pendingRefund.size()
                + " 种；周期进度 " + pacing.cycleProgress() + "/" + pacing.durationTicks()
                + "；累计完成 " + pacing.totalOrders() + " 单 / 产物 " + pacing.totalItems();
    }

    /** 内部小工具：空串/空栈的判定集中在一处，省得每个调用点都写 {@code stack == null || stack.isEmpty()}。 */
    private static boolean shanhai$isEmptyStack(@Nullable ItemStack stack) {
        return stack == null || stack.isEmpty();
    }
}
