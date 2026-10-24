package com.shanhai.client.compat;

import com.gtladd.gtladditions.client.RenderMode;
import com.gtladd.gtladditions.client.render.machine.antichrist.AntichristDeferredRenderer;
import com.gtladd.gtladditions.client.render.machine.antichrist.AntichristRenderProfile;
import com.gtladd.gtladditions.common.machine.multiblock.structure.RingStructure;
import com.gtladd.gtladditions.utils.CommonUtils;
import com.shanhai.ShanhaiMod;
import com.shanhai.config.ShanhaiConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * <b>客户端侧</b>唯一引用 gtladditions 符号的网关。
 *
 * <h2>为什么必须有它（不是洁癖，是硬约束）</h2>
 * 同项目里 common 侧的 {@code com.shanhai.common.compat.GtlAddCompat} 承担了主机/模块几何那部分
 * gtladditions 引用。但 TESR 这批<b>不能</b>放进去，因为它们用的 gtladditions 类全是
 * {@code @OnlyIn(Dist.CLIENT)}（取证：{@code javap -v} 查 {@code RuntimeVisibleAnnotations}）：
 * <pre>
 *   com.gtladd.gtladditions.utils.antichrist.ClientRingBlockHelper                       → @OnlyIn(CLIENT)
 *   com.gtladd.gtladditions.common.machine.multiblock.structure.RingStructure            → @OnlyIn(CLIENT)
 *   com.gtladd.gtladditions.client.render.machine.antichrist.AntichristRenderProfile     → @OnlyIn(CLIENT)
 *   com.gtladd.gtladditions.client.render.machine.antichrist.AntichristDeferredRenderer  → @OnlyIn(CLIENT)
 *   com.gtladd.gtladditions.client.RenderMode                                            → 无 @OnlyIn
 * </pre>
 * 把客户端的 {@code @OnlyIn(CLIENT)} 类写进 common 侧类的常量池，专用服务端上一旦有代码路径触达
 * （类校验、反射扫描、意外的静态引用）就会 {@code NoClassDefFoundError}。
 * 所以项目规则从「全工程唯一一个引用 gtladditions 的类」修正为
 * <b>「common 侧唯一（{@code GtlAddCompat}） + client 侧唯一（本类）」</b>。
 *
 * <h2>为什么要包成静态方法而不是直接透传</h2>
 * 让 gtladditions 的<b>类型</b>（{@code AntichristRenderProfile} / {@code RenderMode} /
 * {@code AntichristDeferredRenderer} / {@code ClientRingBlockHelper}）<b>完全不出现在本类之外</b>。
 * 渲染器那边只会看到 {@code float} / {@code Vec3} / {@code Direction} 这些 MC 原生类型，
 * 于是全工程只有本文件出现 {@code com.gtladd} 包名 —— 这条性质可以用一句 grep 验证，不靠约定。
 *
 * <h2>本类自身也是 {@code @OnlyIn(Dist.CLIENT)}</h2>
 * 调用方（{@code AbstractRingRenderer.render} / {@code PrimordialNeutronStarSphereRenderer.enqueue}）
 * 全部只从 LDLib 的 BER/渲染路径进入，那条路径在专用服务端不会执行；且
 * {@code MachineBuilder.register()} 里对渲染器有显式的客户端门禁
 * （反编译原文：{@code setRenderer(LDLib.isClient() ? this.renderer.get() : IRenderer.EMPTY)}）。
 */
@OnlyIn(Dist.CLIENT)
public final class GtlAddClientCompat {

    private GtlAddClientCompat() {}

    // ------------------------------------------------- 环方块隐藏/恢复（**守卫式**：本工程自己写写入步骤）

    /**
     * 每台主机自己的台账：{@code machinePos → { 位置 → 被写成空气之前的方块状态 }}。
     *
     * <p><b>为什么要自己记（这是 2026-09 用户实机问题的根因之一）</b>：gtladditions 的
     * {@code DIMENSION_CHUNK_BLOCKS} 是<b>按维度共享</b>的一张表（谁写谁生效），
     * 客户端也没有"这台机器只 hide 自己那份环"的概念。我们改用<b>按主机分表</b>的自己的台账 ⇒
     * 同维度多台机器互不干扰。
     */
    private static final Map<Long, Map<Long, BlockState>> HIDDEN = new HashMap<>();

    /**
     * <b>「台账未扫全」标记</b>（2026-09-21 修复"方块永久留空"）。
     *
     * <h2>为什么必须有它</h2>
     * 上一轮若有格子因<b>区块未加载</b>而漏扫（{@code skippedUnloaded > 0}），这一轮必须<b>重扫</b>；
     * 但"要不要重扫"这个状态<b>不能</b>再靠"台账为不为空"来推断 —— 因为台账现在无条件保留
     * （见 {@link #hideRingsAt} 的"台账永不丢弃"注释），空/非空已不再携带"扫全了没有"的信息。
     * 二者因此拆成两个独立状态：{@link #HIDDEN} 管"记了什么"，本集合管"扫全了没有"。
     */
    private static final Set<Long> INCOMPLETE = new HashSet<>();

    /**
     * 每台主机【上一次<b>真正打出去</b>的】{@code ring_hide} 判读量的打包值 —— 降噪状态机的第一字段。
     *
     * <p>⚠️ {@code 0} <b>不</b>表示"没有记录"（合法的打包值也可能为 0），所以"有没有记录"必须靠
     * {@code Map.get()} 返回 {@code null} 判断，<b>不能</b>靠值判零。
     */
    private static final Map<Long, Long> LAST_HIDE_SIGNATURE = new HashMap<>();

    /**
     * 每台主机上次<b>真正打出</b> {@code ring_hide} 行的 tick —— 两个用途：
     * ① "仍 incomplete"期间的心跳计时起点；② 抖动限流 {@link #HIDE_LOG_MIN_GAP_TICKS} 的间隔起点。
     */
    private static final Map<Long, Long> LAST_HIDE_LOG_TICK = new HashMap<>();

    /**
     * <b>抖动限流</b>的最小间隔（tick）：状态虽变、但距上次真打不足本值 ⇒ <b>折叠</b>（只计数，不打行）。
     *
     * <p>参数<b>由队长给定 = {@code 100}</b> tick（≈5 秒，与 {@link #DRIFT_SCAN_INTERVAL_TICKS} 同量级）。
     * 🔴 <b>不许自选</b>。
     *
     * <h2>🔴 三条【不受限流】的例外（永远立刻打）</h2>
     * ① <b>无记录</b>（{@code LAST_HIDE_SIGNATURE} 里没有这台机器）—— 推迟它会把"刚出现"变成"晚出现"；
     * ② <b>收敛行</b>（{@code incomplete == false}）—— "什么时候好的"必须精确可判；
     * ③ <b>心跳到期</b>（{@code incomplete} 且距上次真打 ≥ {@link #DRIFT_HEARTBEAT_TICKS}）。
     * ⇒ 限流<b>只管"抖动重复"</b>，绝不许推迟"第一次出现""心跳"与"最后一次（收敛）"。
     * （{@code ring_drift_ALERT} / {@code ring_drift_recovered} 在另一条路径上，<b>不受本限流影响</b>。）
     */
    private static final long HIDE_LOG_MIN_GAP_TICKS = 100L;

    /**
     * 每台主机<b>自上次真打以来被折叠的次数</b>（打完清零）—— 就是日志行末那个 {@code suppressed=}。
     *
     * <h2>🔴 为什么必须报数（而不只是限流）</h2>
     * <b>只限流不报数 = 静默</b>，而静默违反本工程刚写进 HANDOFF 的那条判据 ——
     * "悄悄不发生"必须能与"出故障"区分开。有了 {@code suppressed=}，
     * "这 5 秒里其实变了 99 次"与"这 5 秒里什么都没发生"在日志上<b>长得不一样</b>。
     */
    private static final Map<Long, Integer> LAST_HIDE_SUPPRESSED = new HashMap<>();

    /**
     * 每台主机<b>已经出现过（打过或被折叠过）的签名集合</b> —— 用来区分"某个状态<b>第一次出现</b>"与"同一状态反复出现"。
     *
     * <h2>🔴 为什么必须有它（这是 (c) 能否成立的关节点）</h2>
     * 队长原话：<b>"限流只管'抖动重复'，不许推迟第一次出现"</b>，且例外写的是
     * <b>"首行（状态首次变化/无记录）"</b>。⇒ 限流必须能识别"这是这个状态第一次出现"。
     * <p>若不做这条例外（即只按字面条件 {@code heartbeatDue || (signatureChanged && gap >= N)}），
     * <b>实测</b>：本局那次真实事故的"中间态首现"距首行只有 ≈5 tick < 100 ⇒ 被折叠，
     * 总行数 = <b>2 行</b>，而那与队长给的验收判据（<b>3 行</b>）不符、也违反"不许推迟第一次出现"。
     * 加上本例外后实测 = <b>3 行</b>，且病态抖动仍是每 100 tick 一行（不退回每帧）。
     * <p><b>折叠过的签名也要登记为"已见过"</b>——否则同一状态在下一帧又会被判成"第一次出现"，
     * 那就等于没限流。
     */
    private static final Map<Long, Set<Long>> LAST_HIDE_SEEN = new HashMap<>();

    /**
     * {@link #LAST_HIDE_SEEN} 的容量上限（每台主机）。超过就整集清空。
     *
     * <p>防的是"病态世界里签名种类无限多 ⇒ 这个集合慢慢变成内存泄漏"（会话可以开好几小时）。
     * 清空只会让"已见过"退回"没见过" ⇒ 方向是<b>多打一两行</b>，<b>绝不会漏打</b>（保守方向）。
     */
    private static final int HIDE_SEEN_CAP = 256;

    /**
     * <b>每台主机的「未成型起始时刻」</b>（{@code machinePos → }{@link #nowMs()}），用于 restore 去抖。
     *
     * <p>⚠️ 2026-10-18：值从 {@code level.getGameTime()}（tick）换成了<b>单调墙钟毫秒</b>
     * —— 理由见 {@link #restoreRingsAt} 的"计时源"一节（暂停时 gameTime 不走，去抖会卡在窗口里）。
     *
     * <p>键只在该主机<b>连续未成型</b>期间存在；重新成型（{@link #hideRingsAt} 被调到的第一件事）
     * 或换维度时清除。{@link #restoreRingsAt} 一旦真的恢复了，也把自己的键清掉（下次从零重新计时）。
     */
    private static final Map<Long, Long> UNFORMED_SINCE = new HashMap<>();

    /**
     * <b>每台主机的「渲染闸门失守起始时刻」</b>（{@code machinePos → }{@link #nowMs()}），
     * 用于 {@link #isFormedStable} 给<b>渲染可见性</b>做去抖（2026-10-18，缺陷「环特效忽隐忽现」）。
     *
     * <h2>为什么渲染也需要一份去抖（而 restore 那份保护不了它）</h2>
     * {@code AbstractRingRenderer#render} 的绘制闸门原来是裸的 {@code controller.isFormed()}，
     * 而 {@code isFormed()} 本身是<b>抖的</b>（结构部件所在区块卸载 ⇒ GTCEu {@code onPartUnload} ⇒
     * {@code UNLOAD_ERROR} ⇒ 每 4 tick 异步重查；本文件 :91-96 记着实测"12 次成型 / 6 次 restore，
     * 瞬时窗口最长 3.012 秒"）。原有的 {@link #UNFORMED_SINCE} 去抖只保护<b>方块写回</b>
     * （restore 要连续未成型 ≥ 阈值才真的写），<b>不保护绘制</b> ⇒ 结构一瞬间报告未成型，
     * 环特效就<b>消失一帧再回来</b> —— 正是用户说的"渲染特效突然没了，然后突然又有了"。
     *
     * <p>本闸门与 restore 用<b>同一份阈值、同一个起点口径</b>（首帧未成型开始计时）⇒
     * 两者在时间上对齐：环特效消失的那一帧，正是方块被写回来的那一帧（不会出现"环没了但方块还藏着"）。
     *
     * <p>⚠️ 值可能等于 {@link #FORMED_LOST_LOCKED}：那是"已到点、已打过 {@code gone} 行、锁死 false"
     * 的哨兵。超阈值后<b>不再删键</b>（删键会让下一帧重新变回"失守第一帧"），只有重新成型才移除。
     */
    private static final Map<Long, Long> FORMED_LOST_SINCE = new HashMap<>();

    /**
     * {@link #FORMED_LOST_SINCE} 的<b>锁死哨兵值</b>（刻意远小于任何 {@link #nowMs()} 的量级）。
     *
     * <p>为什么需要它：去掉"到点删键"之后，超过阈值的<b>每一帧</b>都满足
     * {@code now - since >= debounceMs}；没有哨兵就每帧打一行 {@code state=gone}（渲染路径是逐帧调用的）。
     */
    private static final long FORMED_LOST_LOCKED = Long.MIN_VALUE;

    /**
     * <b>曾经成型过</b>的机器位置（{@code formed == true} 时记入，换维度整表清空）。
     *
     * <p>2026-10-18 二次修复"方块一放下就画"：旧实现把<b>失守的第一帧</b>（{@code since == null}）
     * 当成可见；而方块刚放下（结构未成型）时 {@code isFormed()} 恒为 {@code false}，
     * 于是它<b>永远</b>走"第一帧"这条分支 ⇒ 一直在画。现在只有<b>本会话里真的成型过</b>的机器
     * 才享有去抖宽限；从未成型过 ⇒ 直接不可见。
     */
    private static final Set<Long> EVER_FORMED = new HashSet<>();

    /** {@link #FORMED_LOST_SINCE} / {@link #EVER_FORMED} 所属维度（换维度整表清空）。 */
    @Nullable
    private static ResourceKey<Level> latchDimension;

    /** {@code ring_drift} 体检的<b>扫描</b>限频表（{@code machinePos → 上次扫描的 tick}）。 */
    private static final Map<Long, Long> LAST_DRIFT_TICK = new HashMap<>();

    /**
     * {@code ring_drift} 体检的<b>扫描</b>最小间隔（tick）—— <b>只管"多久量一次"，不管"多久打一行"</b>。
     *
     * <p>用 tick 而不是帧数：渲染是<b>逐帧</b>调用的，按帧限频的话同一份代码在 60fps 与 240fps 下
     * 打日志的频率会差 4 倍，日志就不再可比。{@code level.getGameTime()} 每 tick +1，与帧率无关。
     *
     * <h2>⚠️ 作废说明：本常量曾是 {@code DRIFT_INTERVAL_TICKS = 100L}</h2>
     * <b>作废原文</b>（保留，勿删）：
     * <pre>
     * /** {@code ring_drift} 体检的最小间隔（tick）。
     *  * &lt;p&gt;用 tick 而不是帧数：…（同上，未改动）
     *  * /
     * private static final long DRIFT_INTERVAL_TICKS = 100L;
     * </pre>
     * <b>作废理由</b>：一个常量同时兼任"扫描节奏"与"打日志节奏"，于是"心跳降到 5 分钟"会把
     * <b>探测延迟</b>一起拉长到 5 分钟（漂移已经开始、却要等 5 分钟才被量到）。
     * ⇒ 拆成两个：本常量管<b>扫描</b>（**值保持 100 不变 ⇒ 探测延迟与改造前逐字一致**），
     * {@link #DRIFT_HEARTBEAT_TICKS} 管<b>打行</b>。改名也为了让"grep 心跳常量"的人不会误读 100。
     */
    private static final long DRIFT_SCAN_INTERVAL_TICKS = 100L;

    /**
     * {@code ring_drift} <b>心跳行</b>（以及"漂移持续中"的重复告警行）的节奏（tick）：6000 = 5 分钟。
     *
     * <h2>🔴 为什么心跳行不许删</h2>
     * 若改成"只在漂移时打一行"，那么日志里<b>没有</b> {@code ring_drift} 这件事会有<b>两种互斥解释</b>：
     * ①守卫生效、确实没漂移；②守卫压根没跑（渲染器没进、台账为空、限频表被写坏…）。
     * 后者恰恰是<b>最需要被发现</b>的故障，却会长得和"一切正常"一模一样。
     * ⇒ 心跳行常驻，是"<b>悄悄不发生 ≠ 出故障</b>"这条判据的唯一载体。
     *
     * <h2>本常量同时被 {@code ring_hide} 复用（<b>刻意同口径</b>）</h2>
     * {@link #hideRingsAt} 的"仍 incomplete ⇒ 每 {@code DRIFT_HEARTBEAT_TICKS} 打一条 ring_hide"
     * 用<b>同一个常量</b>：两处都是"体检类心跳"，口径必须一致，免得后人把 5 分钟改成两套值。
     * 名字里的 {@code DRIFT} 是历史（它先用于 {@code ring_drift}），语义以本注释为准。
     */
    private static final long DRIFT_HEARTBEAT_TICKS = 6000L;

    /** 每台主机上次打<b>心跳行/重复告警行</b>的 tick（与"上次扫描"分开记，两者节奏不同）。 */
    private static final Map<Long, Long> LAST_HEARTBEAT_TICK = new HashMap<>();

    /**
     * <b>上次扫描发现漂移</b>（{@code not_air > 0}）的主机集合 —— 状态机的第三个字段。
     *
     * <p>它只区分"<b>刚出现 / 刚恢复</b>"与"<b>一直如此</b>"这两种情形，从而让首次告警与恢复通报
     * <b>立即</b>发生、而持续态按心跳节奏复述。与会话里其它体检状态<b>同生同死</b>
     * （换维度清、{@link #restoreRingsAt} 按主机移除）。
     */
    private static final Set<Long> DRIFTING = new HashSet<>();

    /**
     * <b>反向索引：{@code machinePos → chunkKey → 该区块内属于该主机台账的方块坐标}</b>（A 方案用）。
     *
     * <h2>为什么必须有它</h2>
     * A 的钩子是"<b>每收到一个区块</b>就补写一次"。没有索引的话，每次都要遍历整张台账（≈18k 条）
     * 才能找出"哪些格在这个区块里" ⇒ 玩家走回来时几十上百个区块批量下发，就是几百万次迭代（不可接受）。
     * 有了索引，单次查询 = 一次哈希 + 该区块内格数（通常几十）。
     *
     * <p>在 {@link #hideRingsAt} 扫描时<b>顺手</b>建（同一次 O(n) 遍历，不额外扫全量），
     * 与台账<b>同生同死</b>：换维度清、{@link #restoreRingsAt} 按主机整条移除。
     * 于是不变式 <b>「索引里的格子」⊆「台账里的格子」</b> 恒成立（台账新增点与索引新增点是同一组语句）。
     *
     * <p>⚠️ 键一律用 {@code ChunkPos.asLong(cx, cz)} 的<b>同一套打包</b>（构建时 {@code x >> 4}、
     * 查询时事件给的 chunkX/chunkZ 原样），不混用 {@code ChunkPos.asLong(BlockPos)} 这个重载，
     * 免得将来有人改了那边而这里静默查不到（"查不到"的表现就是 A 完全无效却不报错）。
     */
    private static final Map<Long, Map<Long, Set<Long>>> LEDGER_INDEX = new HashMap<>();

    /** 上一次 hide 所在的维度；换维度先把台账清掉，避免把别的维度的坐标带到本维度。 */
    @Nullable
    private static ResourceKey<Level> hiddenDimension;

    /** 写入 flag：与上游 gtladditions <b>逐字一致</b>（122 = UPDATE_CLIENTS|UPDATE_IMMEDIATE|UPDATE_KNOWN_SHAPE|UPDATE_SUPPRESS_DROPS|UPDATE_MOVE_BY_PISTON）。 */
    private static final int FLAGS = 122;

    /**
     * 把该主机对应的环方块在<b>客户端</b>置为假空气，并把<b>原方块状态全部快照下来</b>。
     *
     * <h2>与上游的三处<b>有意</b>不同（2026-09 用户实机后改，每处都有取证）</h2>
     * <ol>
     *   <li>🔴 <b>快照 + 按原状态恢复。</b>上游 {@code restore} 写回的是
     *       {@code BLOCK_MAPPER.get(letter)}（gtladditions 自己的"神铸"系方块），
     *       <b>不是原来的方块</b> —— 即 hide/restore <b>不是互逆操作</b>，结构一失效就把那片方块
     *       整片换成另一套材质（用户实机"破坏方块后渲染错乱"的直接来源）。
     *       本实现 restore 写回 <b>快照里的原方块状态</b>，因此 hide→restore 是真正的逆操作。</li>
     *   <li><b>台账按主机分表</b>（见 {@link #HIDDEN}），不再共用上游那张
     *       "维度→区块→坐标集"的<b>共享</b>表（同维度多台机器会互相踩）。</li>
     *   <li><b>扫全了才算完成，但台账永不丢弃</b>：上一轮若因区块未加载而漏格，下一帧重扫。
     *       上游是"记录无条件、写入按已加载" ⇒ <b>隐藏结果天然带缺口</b>（用户实机"带缺口"的来源）。
     *       ⚠️ 2026-09-21 修正：漏格时<b>只把"未扫全"这个状态置上</b>（{@link #INCOMPLETE}），
     *       <b>不是</b>把整张台账丢掉 —— 丢掉台账会让"已经写成 AIR 的格子"永久失联（见下）。</li>
     * </ol>
     *
     * <h2>🔴 2026-09-21 修复：漏格时丢台账 ⇒ 方块<b>永久留空</b>（静默损坏玩家数据）</h2>
     * 旧实现是"只有扫全了才记台账（{@code if (skippedUnloaded == 0) HIDDEN.put(...)}）"，
     * 于是 {@code skippedUnloaded > 0} 时<b>整张 {@code collected} 被丢弃</b>，
     * 而其中<b>已经写进 AIR 的那些格子不会回滚</b>。下一帧重扫时它们读到 {@code isAir()} ⇒
     * 走 {@code skippedAir++} 分支（<b>不记台账</b>）⇒ 这些格子在台账里<b>永远不存在</b>，
     * {@link #restoreRingsAt} 也就永远救不回来。修复后的不变式：
     * <pre>
     *   HIDDEN 里的条目【只增不减】（除 restore / 换维度），且【写入 AIR 之前】先落台账
     *   ⇒ "被我们写成 AIR 的格子" ⊆ "台账里的格子" 恒成立（无论扫没扫全）
     * </pre>
     *
     * <h2>⚠️ 曾经写过、已被证据否决的一版：材质守卫</h2>
     * 曾按"只在该位置方块<b>正是</b>该字母对应的环方块（{@code RingStructureVertexBuffer.BLOCK_MAPPER}）时才写空气"
     * 实现过一版守卫，随后被<b>实测否决</b>：T7 验收存档全量扫 2,693 区块，
     * <b>gtladditions 环材质命中 0 格</b>（那片环坐标上站的是 GTCEu 替代方块：青铜机器外壳／工业蒸汽外壳／耐火砖…）
     * ⇒ 该守卫<b>永不匹配</b> ⇒ hide 永不触发 ⇒ <b>真环 + VBO 环双重显示</b>，正是要避免的结果。
     * <b>材质不是可靠判据</b>：上游映射是单射、而本工程与世界那份替代映射都是多对一，
     * 字母级精确判定在这个世界里信息论上就不可能。⇒ 改为"<b>不按材质判、只按坐标判 + 快照兜底</b>"。
     *
     * <p><b>几何仍然完全用 gtladditions 的公开函数</b>（{@link CommonUtils#getRotatedRenderPosition}
     * + {@link RingStructure#getRINGS()}），参数与上游 {@code getWorldRealPosByPosition} 逐字一致
     * ⇒ 坐标<b>不可能</b>因为"我们自己算"而偏。我们只替换了"写"和"记"这两步。
     *
     * <p><b>区块是否加载判据用 {@code level.isLoaded(pos)}</b>：上游反编译里的 {@code m_46749_} 经三重核对
     * （SRG／tsrg／官方 client_mappings）就是 {@code Level.isLoaded(BlockPos)}；而
     * {@code LevelReader.hasChunkAt(BlockPos)} 在本版 javac 下是 <b>deprecated</b>（实测告警），故直接对齐上游。
     *
     * <p>逐帧调用是安全的：同一台机器第二次进来是 O(1)（台账已有）；
     * 只有当上一轮因<b>区块未加载</b>而漏格时才重扫（比上游"每帧全扫 18k 格"更省）。
     *
     * <p><b>已知代价（如实记录）</b>：只要 {@link #INCOMPLETE} 还在，本方法<b>每帧都重扫</b>
     * （遍历 18,334 格）—— <b>这半句仍然成立、本次未改</b>（本次只动日志）。
     * 由于 {@code ring_hide} 的 log <b>前置条件</b>含 {@code skippedAir > 0}，而重扫时"我们上一轮写下的
     * 空气"必然计入 {@code skippedAir}，所以一个长期加载不全的区块会让<b>每一帧都满足打日志的前置条件</b>
     * （这正是本局那次 43 秒事故的成因）。
     * 实际触发面很小：本渲染器 {@code isGlobalRenderer} + 视距 384，主机不可见时本方法根本不会被调到；
     * 而主机可见时它周围的区块通常已加载。
     *
     * <h2>⚠️ 作废说明：本段曾写"每帧<b>打一行</b> ring_hide"（原文保留，勿删）</h2>
     * <b>作废原文</b>：
     * <pre>
     * &lt;p&gt;&lt;b&gt;已知代价（如实记录，未改）&lt;/b&gt;：只要 INCOMPLETE 还在，本方法每帧都重扫并
     * 每帧打一行 ring_hide（这是旧实现就有的行为，本修复没有新增也没有去掉）。
     * &lt;p&gt;…（后半段与上面一致，未改动）
     * </pre>
     * <b>作废理由</b>：2026-09-21 队长裁决 ⓐ 之后，<b>打日志</b>已改为"状态变化 + 仍 incomplete 心跳"，
     * 不再是每帧一行 ⇒ 上句"<b>每帧打一行</b>"作废。
     * 但"<b>每帧都重扫</b>"这半句<b>仍然成立且未改</b>（本次只动日志，不动扫描/台账/去抖/setBlock）。
     * 实测收益：本局那次 43 秒事故的 ring_hide 从 <b>3127 行降到 3 行</b>（本版 (c') 回放值；
     * 不加"首次出现"例外的字面 (c) 为 2 行，但那会违反"不许推迟第一次出现" ⇒ 未采用）。
     */
    public static void hideRingsAt(Level level, long machinePos, Direction frontFacing) {
        if (hiddenDimension != level.dimension()) {
            // 换维度：台账、"未扫全"标记、去抖计时、体检限频表、反向索引<b>一起</b>清（状态同生同死）。
            // 2026-09-21 降噪：体检状态机新增"上次心跳 tick""是否正在漂移"两项，一并在这里清
            //（否则换了维度还带着上一个维度的漂移态 ⇒ 下一条会打出假 recovered）。
            HIDDEN.clear();
            INCOMPLETE.clear();
            UNFORMED_SINCE.clear();
            LAST_DRIFT_TICK.clear();
            LAST_HEARTBEAT_TICK.clear();
            DRIFTING.clear();
            LAST_HIDE_SIGNATURE.clear();
            LAST_HIDE_LOG_TICK.clear();
            LAST_HIDE_SUPPRESSED.clear();
            LAST_HIDE_SEEN.clear();
            LEDGER_INDEX.clear();
            FORMED_LOST_SINCE.clear();   // 渲染闸门的去抖计时与台账同生同死
            EVER_FORMED.clear();         // "曾经成型过"的台账同理（换维度后旧位置无意义）
            latchDimension = level.dimension();
            hiddenDimension = level.dimension();
        }
        // 🔴 进入 hide 路径 = 本帧结构是成型的 ⇒ 撤销 restore 去抖的计时（去抖的唯一复位点）。
        UNFORMED_SINCE.remove(machinePos);

        Map<Long, BlockState> mine = HIDDEN.get(machinePos);
        if (mine != null && !mine.isEmpty() && !INCOMPLETE.contains(machinePos)) {
            // 已 hide 过、且上一轮扫全了 ⇒ O(1) 短路；顺手做一次**只读**台账体检
            //（扫描每 DRIFT_SCAN_INTERVAL_TICKS 一次；打不打行由里面的状态机决定）。
            logLedgerDrift(level, machinePos, mine);
            return;
        }

        BlockPos base = BlockPos.of(machinePos);
        // 🔴 台账在扫描【之前】就登记：本方法此后无论走哪条分支、有没有扫全，
        //    已经写下的 AIR 都有一份快照在身上（旧实现是在扫描【之后】且有条件地登记）。
        if (mine == null) {
            mine = new HashMap<>();
            HIDDEN.put(machinePos, mine);
        }
        Map<String, Integer> firstSeen = new HashMap<>();
        int matched = 0;
        int skippedAir = 0;
        int skippedUnloaded = 0;

        for (String[][] ring : RingStructure.INSTANCE.getRINGS()) {
            int w = ring.length;
            int h = ring[0].length;
            int d = ring[0][0].length();
            for (int x = 0; x < w; x++) {
                for (int y = 0; y < h; y++) {
                    String row = ring[x][y];
                    for (int z = 0; z < row.length(); z++) {
                        char letter = row.charAt(z);
                        if (letter == ' ') {
                            continue;
                        }
                        BlockPos pos = worldPos(base, frontFacing, x, y, z, w, h, d);
                        if (!level.isLoaded(pos)) {
                            skippedUnloaded++;
                            continue;
                        }
                        BlockState current = level.getBlockState(pos);
                        if (current.isAir()) {
                            // 那里本来就是空气（本来就空 / 已被挖掉 / <b>上一轮被我们写成 AIR</b>）⇒ 不写。
                            // ⚠️ 后一种情况正是旧实现的死亡路径：不记台账 ⇒ 永久留空。
                            //    现在"上一轮被我们写成 AIR"的格子【已经在台账里】了（先登记再写），
                            //    所以这里 skippedAir 只是跳过重复写入，不再造成任何丢失。
                            skippedAir++;
                            continue;
                        }
                        // 台账：**我们写 AIR 之前，客户端这一格到底是什么**。
                        // 这是"客户端幽灵方块"的可读证据（见字段说明）：
                        // 按 T7 模板，这些坐标在服务端应该是我们自己的 GTCEu 外壳；
                        // 若这里统计出 gtladditions: 的环材质 ⇒ 客户端持有服务端没有的方块
                        // （来源只可能是上一轮 restore 写错的材质，或 gtladditions 自己的渲染残留）。
                        //
                        // putIfAbsent 而不是 put：重扫时【绝不覆盖】首次快照。
                        // 否则"隐藏期间客户端又收到了真方块"（区块重载）会把原始快照改成那个真方块，
                        // 而首次快照才是 restore 该写回去的东西。
                        mine.putIfAbsent(pos.asLong(), current);
                        // 反向索引（A 方案）：同一次遍历顺手建；重复 add 由 Set 去重。
                        // 🔴 键的打包口径必须与 reassertHiddenAt 的查询口径一致，见 LEDGER_INDEX 注释。
                        LEDGER_INDEX.computeIfAbsent(machinePos, k -> new HashMap<>())
                                .computeIfAbsent(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4), k -> new HashSet<>())
                                .add(pos.asLong());
                        firstSeen.merge(BuiltInRegistries.BLOCK.getKey(current.getBlock()).toString(), 1, Integer::sum);
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS);
                        matched++;
                    }
                }
            }
        }

        // "扫全了没有"与"台账里有什么"是两个独立状态：这里只动前者，台账无论扫没扫全都保留。
        boolean incomplete = skippedUnloaded > 0;
        if (incomplete) {
            INCOMPLETE.add(machinePos);
        } else {
            INCOMPLETE.remove(machinePos);
        }

        if (matched > 0 || skippedAir > 0) {
            // 🔴 2026-09-21 降噪：打日志 = 状态变化就打 + 仍 incomplete 按心跳打 + 抖动重复折叠并报数。
            //    （队长裁决 ⓐ 定形，裁决 (c) 追加限流与 suppressed= 报数；参数 N=100 由队长给定，非自选）
            //
            // ⚠️ 作废说明（旧版规则原文保留，勿删）：本段曾是"【状态变化时打】+【仍 incomplete 按心跳打】"，即
            //        if (signatureChanged || heartbeatDue) { ... }
            //    作废理由（队长原话）：**这次修复的整个目的就是"不再每帧打"** —— 若病态抖动（签名逐帧来回变）
            //    时又退回每帧一行，等于这个修复在最需要它的时候失效（合成回放实测：抖动 1000 帧 ⇒ 1001 行）。
            //    ⇒ 裁决 (c)：加 HIDE_LOG_MIN_GAP_TICKS 限流，并追加 suppressed= 报数（**只限流不报数 = 静默**）。
            //    现条件（队长原话）：heartbeatDue || (signatureChanged && now - lastLogTick >= N)，N = 100。
            //
            // 实测依据（本局那次事故）：3127 行里，去掉时间戳后【只有 3 种不同文本】，
            // 其中 3125 行【逐字完全相同】⇒ 重复部分的信息量 = 0，折叠它们<b>零损失</b>。
            // ⚠️ 那次事故窗口只有 42.6 秒（42.6 s × 20 tick/s ≈ 852 tick）【短于】心跳 6000 tick
            //    ⇒ 那次心跳触发 **0 次**。
            // ⚠️ 规则演进与作废留痕（勿删，含每版实测回放值）：
            //   ⓐ 版      = `if (signatureChanged || heartbeatDue)`
            //               ⇒ 真实事故回放 **3 行**；但病态抖动 1001 帧 ⇒ **1001 行**（= 本修复在最需要时失效）⇒ 作废
            //   (c) 字面版 = `heartbeatDue || (signatureChanged && now - lastLogTick >= 100)`（含例外①②③）
            //               ⇒ 真实事故回放 **2 行**：中间态首现距首行仅 ≈5 tick < 100 ⇒ 被折叠。
            //               ⇒ **作废**，理由（队长两条明文）：①"限流只管抖动重复，**不许推迟第一次出现**"；
            //                  ②验收判据是"对它仍然 = **3 行**"。字面版两条都不满足。
            //   (c') 本版  = (c) 字面版 + 例外④"该签名【从未出现过】⇒ 立刻打"（见 LAST_HIDE_SEEN）
            //               ⇒ 真实事故回放 **3 行 ✓**；病态抖动 **12 行 ✓**（约每 100 tick 一行，没退回每帧）
            //   （旧注释里"3 行 = ①首行/②中间态首现/③末行"这句是 ⓐ 版的值，对字面 (c) 曾不成立，对 (c') 重新成立）
            //
            // 🔴 前置条件 `matched > 0 || skippedAir > 0` **原样保留**（旧条件原文见下）：
            //    新逻辑只是在它之上再加一层"要不要真的打"，不改变"哪一轮算有效轮"的口径。
            final long nowTick = level.getGameTime();
            final long signature = hideSignature(matched, skippedAir, skippedUnloaded, incomplete);
            final Long lastSignature = LAST_HIDE_SIGNATURE.get(machinePos);
            final boolean firstEver = lastSignature == null;
            final boolean signatureChanged = firstEver || lastSignature.longValue() != signature;
            final Long lastLogTick = LAST_HIDE_LOG_TICK.get(machinePos);
            final boolean heartbeatDue = incomplete
                    && (lastLogTick == null || nowTick - lastLogTick >= DRIFT_HEARTBEAT_TICKS);
            // 🔴 例外③'（实现依据见 LAST_HIDE_SEEN 的注释）：该签名【从未出现过】⇒ 立刻打。
            //    这是"限流只管抖动重复、不许推迟第一次出现"的落点，也是 (c) 能打出 3 行而不是 2 行的原因。
            Set<Long> seen = LAST_HIDE_SEEN.get(machinePos);
            if (seen == null) {
                seen = new HashSet<>();
                LAST_HIDE_SEEN.put(machinePos, seen);
            }
            if (seen.size() > HIDE_SEEN_CAP) {
                seen.clear();   // 防病态累积；方向是多打一两行，不会漏打
            }
            final boolean newState = !seen.contains(signature);
            // 🔴 四条【不受限流】的例外 ⇒ 永远立刻打：
            //    ① firstEver    —— "无记录"= 这台机器的第一次；推迟它会把"刚出现"变成"晚出现"
            //    ② !incomplete  —— 收敛行；"什么时候好的"必须精确可判
            //    ③ heartbeatDue —— 心跳有自己的节奏（6000 tick），不受 100 tick 限流管
            //    ④ newState     —— 这个状态【第一次出现】；限流只管"抖动重复"
            final boolean immediate = firstEver || !incomplete || heartbeatDue || newState;
            // 抖动重复：状态变了、但距上次真打不足 HIDE_LOG_MIN_GAP_TICKS ⇒ 折叠（只计数，不打行）
            final boolean changeDue = signatureChanged
                    && (lastLogTick == null || nowTick - lastLogTick >= HIDE_LOG_MIN_GAP_TICKS);
            if (immediate || changeDue) {
                final int suppressed = LAST_HIDE_SUPPRESSED.getOrDefault(machinePos, 0);
                final String reason;
                if (firstEver) {
                    reason = "first";
                } else if (!incomplete) {
                    reason = "converged";
                } else if (heartbeatDue) {
                    reason = "heartbeat";
                } else if (newState) {
                    reason = "newstate";
                } else {
                    reason = "change";
                }
                LAST_HIDE_SIGNATURE.put(machinePos, signature);
                LAST_HIDE_LOG_TICK.put(machinePos, nowTick);
                LAST_HIDE_SUPPRESSED.put(machinePos, 0);   // 🔴 打完清零
                seen.add(signature);                       // 登记为"已见过"（打过的当然算见过）
                // 旧条件原文（保留，勿删）；新逻辑把它当<b>前置条件</b>，并未删除：
                //     if (matched > 0 || skippedAir > 0) {
                // 旧格式字符串原文（保留，勿删）；新格式只在【末尾】依次追加 reason= / suppressed=，
                // 其余字段名与顺序【逐字未变】⇒ 既有 grep（matched=/skipped_air=/incomplete=）全部照旧可用：
                //     "[SHANHAI-SPEC] ring_hide machine=({},{},{}) matched={} skipped_air={} skipped_unloaded={} incomplete={} first_seen={}"
                ShanhaiMod.LOGGER.info("[SHANHAI-SPEC] ring_hide machine=({},{},{}) matched={} skipped_air={} skipped_unloaded={} incomplete={} first_seen={} reason={} suppressed={}",
                        base.getX(), base.getY(), base.getZ(), matched, skippedAir, skippedUnloaded, incomplete,
                        histogram(firstSeen), reason, suppressed);
            } else if (signatureChanged) {
                // 折叠分支：**记录**这个新签名（否则同一状态会在后续帧被重复计数），并累加折叠次数。
                // ⚠️ 折叠过的签名【也要登记为已见过】，否则下一帧又会被判成"第一次出现"⇒ 等于没限流。
                LAST_HIDE_SIGNATURE.put(machinePos, signature);
                LAST_HIDE_SUPPRESSED.put(machinePos, LAST_HIDE_SUPPRESSED.getOrDefault(machinePos, 0) + 1);
                seen.add(signature);
            }
        }
    }

    /**
     * 把一轮 {@code ring_hide} 的四个判读量（{@code matched} / {@code skippedAir} / {@code skippedUnloaded} /
     * {@code incomplete}）打包成一个 {@code long}，供"和上一轮比一比，看状态变了没有"用
     * —— <b>不做任何分配</b>（本方法在渲染路径上，逐帧可达）。
     *
     * <p>位域：{@code [62:42]=matched}、{@code [41:21]=skippedAir}、{@code [20:0]=skippedUnloaded}、
     * 最高位 {@code [63]=incomplete}。每段 21 位（上限 2,097,151）远大于本机台账实测的 18,334 格。
     * ⚠️ 若将来环规模涨到 200 万格以上会串位 —— 那时改存字符串/record 即可（只此一处用）。
     */
    private static long hideSignature(int matched, int skippedAir, int skippedUnloaded, boolean incomplete) {
        return ((long) matched << 42)
                | ((long) skippedAir << 21)
                | (long) skippedUnloaded
                | (incomplete ? 1L << 63 : 0L);
    }

    /**
     * <b>A 方案</b>：区块（重新）下发到客户端后，把台账里落在该区块内的环格子<b>重新断言为 AIR</b>。
     *
     * <h2>只补哪一个洞</h2>
     * 环隐藏是<b>纯客户端</b>写（服务端从未 hide 过）。区块一旦在客户端卸载、之后又被服务端重新下发，
     * 客户端那些格子的方块会回到<b>服务端真值 = 真环方块</b>；而此时 {@link #hideRingsAt} 会因为
     * "台账非空且扫全了"而 O(1) 短路 ⇒ <b>不会再 hide 一遍</b> ⇒ 真环 + VBO 环双重显示。
     * 本方法就是在"区块刚到达"这个时刻把这类格子再写成 AIR。<b>它不管别的洞</b>。
     *
     * <h2>🔴 它<b>不</b>覆盖：服务端方块更新包（这条是 {@code [推断]}）</h2>
     * {@code ClientboundBlockUpdatePacket} / {@code ClientboundSectionBlocksUpdatePacket}
     * （玩家挖放、机器改方块、结构变更都走这条）直接改客户端区块数据，<b>不触发 {@code ChunkEvent.Load}</b>
     * ⇒ 本方法不会被调到。这类"单格回灌"目前由 {@link #hideRingsAt} 的 {@link #INCOMPLETE} 重扫路径兜。
     *
     * <h2>🔴 严格只读台账，不写台账</h2>
     * 本方法<b>不</b>碰 {@link #HIDDEN}/{@link #INCOMPLETE}：特别是<b>不</b>覆盖首见快照
     * —— "首见快照永不被覆盖"这条不变式（{@code putIfAbsent} 语义）是本方法的前提，不是它可以动的东西。
     * 唯一的写操作是 {@code setBlock(pos, AIR, FLAGS)}。
     *
     * <h2>幂等 / 与 C（restore 去抖）的边界</h2>
     * 写 AIR 是幂等的，与 {@code hideRingsAt} 的重复扫描不冲突。
     * 边界情形：机器正处于"未成型去抖窗口"（≤ 阈值）内恰好有区块重达 ⇒ 本方法会把那几格再隐藏一次，
     * 随后 {@code restoreRingsAt} 会按台账写回 —— <b>终态正确</b>，代价是那几格多隐藏 ≤ 阈值的时间。
     * 反过来（在去抖窗口内什么都不做）会留下一个必须"再飞远再飞回"才能自愈的洞，故不取。
     *
     * <h2>成本</h2>
     * 每台主机 O(1) 哈希 + 该区块内格数（见 {@link #LEDGER_INDEX}），<b>不</b>遍历整张台账。
     *
     * @param chunkX chunk 坐标（不是方块坐标），来自 {@code ChunkPos#x}
     * @param chunkZ chunk 坐标（不是方块坐标），来自 {@code ChunkPos#z}
     */
    public static void reassertHiddenAt(Level level, int chunkX, int chunkZ) {
        // 🔴 第二道防线。第一道在事件处理器第一行（ClientChunkLoadHandler.onChunkLoad），任务书要求。
        //    这里再判一次是刻意的"纵深防御"：本方法下面就是真的写方块，而写进【服务端】世界会落盘毁档。
        if (!level.isClientSide() || LEDGER_INDEX.isEmpty()) {
            return;
        }
        final long chunkKey = ChunkPos.asLong(chunkX, chunkZ);
        for (Map.Entry<Long, Map<Long, Set<Long>>> byMachine : LEDGER_INDEX.entrySet()) {
            final long machinePos = byMachine.getKey();
            // 台账已经没了（restore 过）⇒ 本就不该再隐藏这台机器的环。
            if (!HIDDEN.containsKey(machinePos)) {
                continue;
            }
            final Set<Long> cells = byMachine.getValue().get(chunkKey);
            if (cells == null || cells.isEmpty()) {
                continue;
            }
            int inChunk = 0;
            int reasserted = 0;
            for (Long packed : cells) {
                BlockPos pos = BlockPos.of(packed);
                if (!level.isLoaded(pos)) {
                    continue;   // 刚下发事件的区块必然已加载；这里只是防御
                }
                inChunk++;
                // ⚠️ 口径说明（与任务书的字面写法相反，理由如下 —— 这是刻意的，不是笔误）：
                //    任务书写"只处理当前 isAir() 的格子"，但本路径要补的洞恰恰是"客户端又拿到真方块"：
                //    区块重达后那一格是【非空气】。若只处理 isAir() 的格子，则整条路径退化成空操作
                //    （往已经是空气的格子再写一次空气），M 恒为 0；更糟的是任务书自己给的判据
                //    "M == 0 而 ring_drift 的 not_air > 0 ⇒ 处理器没被调到"会永远成立，
                //    从此再也分不清"处理器没跑"和"条件写反了"。
                //    ⇒ 按物理事实实现：**非空气才补写**；本来就是空气的格子自然跳过。
                if (level.getBlockState(pos).isAir()) {
                    continue;
                }
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS);
                reasserted++;
            }
            if (inChunk > 0) {
                // 判读：区块重达且台账有该区块 ⇒ 这一行应当出现，且 M 通常 > 0。
                // 若这一行有而 M == 0、且 ring_drift 的 not_air > 0 ⇒ 说明该区块的格子在事件里读到的仍是空气
                // （时机不对，需回到字节码取证）；若连这一行都没有、而 not_air > 0 ⇒ 钩子没被调到（注册/边判写错）。
                BlockPos base = BlockPos.of(machinePos);
                ShanhaiMod.LOGGER.info("[SHANHAI-SPEC] ring_reassert machine=({},{},{}) chunk=({},{}) in_chunk={} reasserted={}",
                        base.getX(), base.getY(), base.getZ(), chunkX, chunkZ, inChunk, reasserted);
            }
        }
    }

    // ================================================================== ⑤ 自愈 / 渲染闸门（2026-10-18）

    /**
     * 本类所有"去抖/限流"用的时间源：<b>单调墙钟毫秒</b>。
     *
     * <p>🔴 为什么不用 {@code level.getGameTime()}：世界时钟暂停时 {@code ClientLevel#tickTime}
     * 被钉住 ⇒ gameTime 不走 ⇒ 任何按 tick 计数的去抖都可能永久卡在窗口里（见
     * {@link #restoreRingsAt} 与 {@link #isFormedStable} 的注释）。用 {@code nanoTime} 换算毫秒
     * 是为了单调性（{@code currentTimeMillis} 会被系统对时往回拨）。
     */
    private static long nowMs() {
        return System.nanoTime() / 1_000_000L;
    }

    /**
     * <b>整表补扫一次藏环</b>：台账里凡是"当前还是非空气"的格子，重新写成 AIR。
     *
     * <h2>为什么需要它（缺陷 ⑤ 的自愈路径）</h2>
     * 世界时钟的"世界写入硬网"曾经<b>不看 level 在哪一侧</b>、一律拒绝 {@code Level#setBlock}，
     * 于是暂停期间客户端<b>藏环</b>的写入也被拦掉。而本类的台账是
     * <b>先登记快照、后写 AIR</b>（{@code :380-387}）⇒ 被拦的那一轮留下
     * "台账非空，但方块还是真方块"的状态，下一帧走 {@code :326-331} 的 O(1) 短路
     * （"已 hide 过、且上一轮扫全了"）⇒ <b>永远不会自己补上</b> ——
     * 用户看到的就是"环上那一整片实体方块露出来"。
     *
     * <p>修好按侧分流之后不会再有新的坏状态，但"已经坏掉的那一局"需要一次补扫才能自愈
     * ⇒ 世界时钟在<b>每次暂停⇄恢复跳变</b>时调一次本方法（次数极少，成本 = 台账格数）。
     *
     * <h2>安全边界</h2>
     * <ul>
     *   <li>🔴 <b>只碰客户端</b>：第一行就判 {@code !level.isClientSide()} 直接返回
     *       （写进服务端世界会落盘毁档，与 {@link #reassertHiddenAt} 同一条红线）；</li>
     *   <li><b>只读台账、不写台账</b>（快照永不被覆盖，与 A 方案同一不变式）；</li>
     *   <li>只处理"当前非空气"的格子；本来就是空气的自然跳过（幂等）。</li>
     * </ul>
     */
    public static void reassertAllHidden(Level level) {
        if (level == null || !level.isClientSide() || HIDDEN.isEmpty()) {
            return;
        }
        int machines = 0;
        int healed = 0;
        int scanned = 0;
        for (Map.Entry<Long, Map<Long, BlockState>> byMachine : HIDDEN.entrySet()) {
            Map<Long, BlockState> mine = byMachine.getValue();
            if (mine == null || mine.isEmpty()) {
                continue;
            }
            int machineHealed = 0;
            for (Long packed : mine.keySet()) {
                BlockPos pos = BlockPos.of(packed);
                if (!level.isLoaded(pos)) {
                    continue;
                }
                ++scanned;
                if (level.getBlockState(pos).isAir()) {
                    continue;
                }
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS);
                ++machineHealed;
            }
            if (machineHealed > 0) {
                ++machines;
                healed += machineHealed;
                BlockPos base = BlockPos.of(byMachine.getKey());
                ShanhaiMod.LOGGER.info("[SHANHAI-SPEC] ring_heal machine=({},{},{}) healed={}",
                        base.getX(), base.getY(), base.getZ(), machineHealed);
            }
        }
        // 判据行：healed == 0 表示台账与画面一致（健康）；> 0 表示补回来了（原来是坏的）
        ShanhaiMod.LOGGER.info("[SHANHAI-SPEC] ring_heal_total machines={} healed={} scanned={} ledger={}",
                machines, healed, scanned, HIDDEN.size());
    }

    /**
     * <b>渲染闸门的去抖</b>：把抖动的 {@code isFormed()} 变成"连续失守 ≥ 阈值才算真的没了"。
     *
     * <h2>缺陷「环特效忽隐忽现」的根因与修法</h2>
     * {@code AbstractRingRenderer#render} 的绘制闸门是裸 {@code controller.isFormed()}，
     * 而它就是抖的（见 {@link #FORMED_LOST_SINCE} 的注释：实测 12 次成型 / 6 次 restore、
     * 瞬时窗口最长 3.012 秒）⇒ 结构一瞬报告未成型，环特效就消失一帧再回来。
     * 于是：<b>成型</b> ⇒ 立刻可见（不推迟"第一次出现"）；<b>未成型</b> ⇒ 只有连续撑满
     * {@code ringRestoreDebounceTicks}（默认 100 tick = 5 秒）才真的不可见 —— 与 restore 完全同步。
     *
     * <h2>2026-10-18 二次修复（"方块一放下就一直在画"）</h2>
     * 上一版有两个<b>互补</b>缺陷，只修一个就会换出同族的另一个：
     * <ol>
     *   <li><b>失守第一帧被当成可见</b>（{@code since == null} 分支）—— 而方块刚放下、结构未成型时
     *       {@code isFormed()} 每帧都是 {@code false} ⇒ 从未成型过的主机永远走这条分支；</li>
     *   <li>到点后又 {@code FORMED_LOST_SINCE.remove(machinePos)} 把计时键删掉 ⇒ 下一帧又变回
     *       "第一帧" ⇒ 死循环"5 秒可见 / 1 帧不可见"，观感就是<b>一直在画</b>。</li>
     * </ol>
     * 现在：从未成型过（{@link #EVER_FORMED} 里没有）⇒ <b>第一帧就不可见</b>；到点后写
     * {@link #FORMED_LOST_LOCKED} <b>锁死</b> false（{@code gone} 行每个失守周期只打一次），
     * 只有再次 {@code formed == true} 才复位。成型后的渲染路径与上一版逐字相同。
     *
     * <h2>🔴 计时源 = 单调墙钟 {@link #nowMs()}（<b>不是</b> {@code level.getGameTime()}）</h2>
     * 2026-10-18 父代理明确要求："去抖别用 {@code getGameTime()}"。理由（实机风险，不是洁癖）：
     * {@code ClientLevel#tickTime} 被世界时钟<b>钉住</b>时 {@code getGameTime()} 根本不走；万一客户端
     * 因为任何原因停在"以为还在暂停"的状态，任何用 tick 计数的去抖就<b>永久卡在窗口里</b>
     * （环要么永远不消失、要么永远不出现），而且日志上看不出是"没到阈值"还是"时钟不走"。
     * 墙钟是单调的、与 tick/暂停/帧率全都无关 ⇒ 这一类"卡死"从结构上不存在。
     *
     * <p>代价（如实写明）：暂停期间<b>真实时间照样在走</b> ⇒ 如果暂停中结构真的失守超过阈值，
     * 环会在暂停期间消失。这与 {@code restoreRingsAt} 的阈值同源（见那里也换成了墙钟）⇒
     * "环消失"与"方块写回"仍然发生在同一个窗口 ⇒ 不会出现"环没了、方块还藏着"的洞。
     *
     * @param level      客户端 level
     * @param machinePos 主机 {@code BlockPos#asLong()}
     * @param formed     本帧 {@code controller.isFormed()} 的原始值（抖的）
     * @return 本帧应当用来决定"画不画环"的稳定值
     */
    public static boolean isFormedStable(Level level, long machinePos, boolean formed) {
        if (formed) {
            FORMED_LOST_SINCE.remove(machinePos);   // 重新成型 ⇒ 立刻复位（这条保证"第一次出现不推迟"）
            EVER_FORMED.add(machinePos);            // 记入"曾经成型过"（从未成型过的主机不画）
            return true;
        }
        if (level == null) {
            return false;
        }
        if (latchDimension != level.dimension()) {
            FORMED_LOST_SINCE.clear();
            EVER_FORMED.clear();        // 与失守计时同生同死，否则换维度后旧位置的记录会串台
            latchDimension = level.dimension();
        }
        if (!EVER_FORMED.contains(machinePos)) {
            // 从未成型过（方块刚放下 / 结构从未拼起来）⇒ 第一帧就不可见，不进去抖窗口。
            return false;
        }
        long now = nowMs();
        Long since = FORMED_LOST_SINCE.get(machinePos);
        if (since == null) {
            // 【成型过】的机器失守的第一帧：仍然按"可见"处理（下一帧起才开始计时），且打一行可判读的日志。
            FORMED_LOST_SINCE.put(machinePos, now);
            BlockPos base = BlockPos.of(machinePos);
            ShanhaiMod.LOGGER.info("[SHANHAI-SPEC] ring_flicker_gate machine=({},{},{}) state=lost at_ms={}（去抖窗口开始）",
                    base.getX(), base.getY(), base.getZ(), now);
            return true;
        }
        if (since.longValue() == FORMED_LOST_LOCKED) {
            // 已经判过"真消失"：锁死 false，直到 formed == true 复位。
            // 🔴 这里【不许】打日志：渲染路径逐帧进来，打一行就是每帧一行。
            return false;
        }
        long debounceMs = restoreDebounceTicks() * 50L;
        if (now - since < debounceMs) {
            return true;    // 仍在去抖窗口内 ⇒ 继续可见（这一句就是"忽隐忽现"的止血点）
        }
        // 🔴 到点后【不删键】（旧实现就在这里 remove ⇒ 下一帧又变回"第一帧" ⇒ 死循环）。
        //    改成写锁死哨兵：gone 行每个失守周期只打这一次，之后逐帧在上面的分支直接 return false。
        FORMED_LOST_SINCE.put(machinePos, FORMED_LOST_LOCKED);
        BlockPos base = BlockPos.of(machinePos);
        ShanhaiMod.LOGGER.info("[SHANHAI-SPEC] ring_flicker_gate machine=({},{},{}) state=gone at_ms={} elapsed_ms={}（超过阈值，真消失）",
                base.getX(), base.getY(), base.getZ(), now, now - since);
        return false;
    }

    /**
     * <b>只读</b>台账体检（任务 B，2026-09-21）。
     *
     * <h2>三种行：前缀决定语义（grep 一眼可分）</h2>
     * <pre>
     * [SHANHAI-SPEC] ring_drift           machine=(x,y,z) ledger=… loaded=… not_air=…          INFO  心跳（健康）
     * [SHANHAI-SPEC] ring_drift_ALERT     machine=(x,y,z) ledger=… loaded=… not_air=… onset=…  WARN  漂移
     * [SHANHAI-SPEC] ring_drift_recovered machine=(x,y,z) ledger=… loaded=… not_air=0          INFO  恢复
     * </pre>
     * {@code not_air} 就是<b>"用户看到的方块格数"</b> —— 客户端读不到服务端，观感只能这样变成数字。
     *
     * <h2>🔴 为什么心跳行不许删（"悄悄不发生"必须能与"出故障"区分开）</h2>
     * 若改成"只在漂移时打"，那么日志里<b>没有</b> {@code ring_drift} 会有<b>两种互斥解释</b>：
     * ①守卫生效、确实没漂移；②守卫压根没跑（渲染器没进、台账为空、限频表被写坏…）。
     * 后者恰恰是最需要被发现的故障，却会长得和"一切正常"一模一样。
     * ⇒ 心跳行必须常驻，它是"守卫还活着"的唯一正面证据。
     *
     * <h2>🔴 本体检只读；补写由 A 方案负责，两处都写会互相掩盖故障</h2>
     * 也就是说：本方法<b>一个 {@code setBlock} 都不许出现</b>。它是"病灶的测量仪"，
     * 一旦它自己动手补写（把 {@code not_air} 清零），就再也分不清"修好了"和"被测量仪擦了"。
     * 2026-09-21 的降噪改造<b>只动节奏与分级</b>，不动任何隐藏/恢复行为
     * （{@code setBlock} 时机、台账内容、去抖逻辑一律未变）。
     *
     * <h2>状态机（每台主机三个字段）</h2>
     * <pre>
     *   LAST_DRIFT_TICK      上次【扫描】tick     —— 只管扫描限频（100 tick）
     *   LAST_HEARTBEAT_TICK  上次【打行】tick     —— 只管输出限频（6000 tick）
     *   DRIFTING             上次扫描是否为漂移态  —— 区分"刚出现/刚恢复"与"一直如此"
     *
     *   扫描后按 not_air 分两支：
     *     not_air &gt; 0
     *       ├ DRIFTING 里【没有】它 ⇒ 首次出现：立即 WARN ring_drift_ALERT onset=true（不等心跳）
     *       ├ DRIFTING 里【有】它 且 距上次打行 ≥ 6000 tick ⇒ WARN ring_drift_ALERT onset=false
     *       └ 否则 ⇒ 静默  ←「不许每 tick/每 5 秒刷」的落点：持续漂移也只按心跳节奏复述
     *     not_air == 0
     *       ├ DRIFTING 里【有】它 ⇒ 刚恢复：立即 INFO ring_drift_recovered
     *       └ 否则 ⇒ 距上次心跳 ≥ 6000 tick 才打 INFO ring_drift
     * </pre>
     * ⚠️ <b>"立即"的准确含义</b>：= "下一次扫描发现的那一瞬间就打，<b>不等心跳</b>"。
     * 扫描粒度 = {@link #DRIFT_SCAN_INTERVAL_TICKS}（100 tick = 5 秒），故最坏延迟 5 秒
     * —— 与改造<b>前</b>的<b>探测</b>延迟逐字相同（改前也是 100 tick 才量一次，只是量到之后要等下一次才打）。
     * <b>为什么不把扫描做成逐帧</b>（那样才是"同帧告警"）：单次扫描 = 台账格数（本机实测 18334）次
     * {@code isLoaded} + 同次数 {@code getBlockState}（每次还各含一个 {@code BlockPos.of} 分配），
     * 而渲染路径是逐帧进来的；本机实测该分支曾以 <b>≈73 行/秒</b>刷屏（即每帧一行）
     * ⇒ 逐帧体检会把这段定量成 ≈130 万格/秒的只读遍历落在<b>渲染线程</b>上。
     * 100 tick 一次则是 ≈3670 格/秒（与改造前完全相同，<b>零性能增量</b>）。
     * 若日后确要同帧告警，把 {@link #DRIFT_SCAN_INTERVAL_TICKS} 改成 1 即可（只此一处，代价见上）。
     * <p>第一次调用<b>必打心跳行</b>（限频表里还没有这台机器）。
     */
    private static void logLedgerDrift(Level level, long machinePos, Map<Long, BlockState> ledger) {
        // 2026-10-18：连"只读体检"的频率也换成单调墙钟 —— 否则暂停期间这一路会静默，
        // 而用户正是在"暂停 60 秒盯环"这个窗口里需要它出数（阈值仍按 tick 配置，×50ms 换算）。
        long now = nowMs();
        Long lastScan = LAST_DRIFT_TICK.get(machinePos);
        if (lastScan != null && now - lastScan < DRIFT_SCAN_INTERVAL_TICKS * 50L) {
            return;
        }
        LAST_DRIFT_TICK.put(machinePos, now);

        int loaded = 0;
        int notAir = 0;
        for (Long packed : ledger.keySet()) {
            BlockPos pos = BlockPos.of(packed);
            if (!level.isLoaded(pos)) {
                continue;
            }
            loaded++;
            if (!level.getBlockState(pos).isAir()) {
                notAir++;
            }
        }
        BlockPos base = BlockPos.of(machinePos);
        final int bx = base.getX();
        final int by = base.getY();
        final int bz = base.getZ();
        final int ledgerSize = ledger.size();
        final Long lastHeartbeat = LAST_HEARTBEAT_TICK.get(machinePos);
        final boolean heartbeatDue = lastHeartbeat == null || now - lastHeartbeat >= DRIFT_HEARTBEAT_TICKS * 50L;

        if (notAir > 0) {
            boolean onset = DRIFTING.add(machinePos);   // add 返回 true ⇔ 集合里原本没有它 ⇔ 首次出现
            if (onset || heartbeatDue) {
                LAST_HEARTBEAT_TICK.put(machinePos, now);
                ShanhaiMod.LOGGER.warn("[SHANHAI-SPEC] ring_drift_ALERT machine=({},{},{}) ledger={} loaded={} not_air={} onset={}",
                        bx, by, bz, ledgerSize, loaded, notAir, onset);
            }
            return;
        }

        if (DRIFTING.remove(machinePos)) {
            // 刚恢复：立即通报（这是"漂移结束"的唯一证据，同样不许等心跳）。
            LAST_HEARTBEAT_TICK.put(machinePos, now);
            ShanhaiMod.LOGGER.info("[SHANHAI-SPEC] ring_drift_recovered machine=({},{},{}) ledger={} loaded={} not_air=0",
                    bx, by, bz, ledgerSize, loaded);
            return;
        }

        if (heartbeatDue) {
            LAST_HEARTBEAT_TICK.put(machinePos, now);
            ShanhaiMod.LOGGER.info("[SHANHAI-SPEC] ring_drift machine=({},{},{}) ledger={} loaded={} not_air={}",
                    bx, by, bz, ledgerSize, loaded, notAir);
        }
    }

    /**
     * 撤销 {@link #hideRingsAt}（结构未成型时每帧调用）。
     *
     * <h2>🔴 为什么必须去抖（2026-09-21，事实来自本轮实测）</h2>
     * "未成型"<b>不是</b>一个稳定状态：结构部件所在区块卸载 ⇒ GTCEu 的 {@code onPartUnload}
     * 给 {@code MultiblockState} 打 {@code UNLOAD_ERROR} 并排进异步重查队列 ⇒ {@code asyncCheckPattern}
     * <b>每 4 tick 重查一次</b> ⇒ 玩家走远/走近时 {@code isFormed()} <b>反复翻转</b>。
     * 本轮实测：<b>12 次成型、6 次 restore、瞬时窗口最长 3.012 秒</b>。
     * 在本方法<b>逐帧直连</b>的旧实现里，翻转一次就真的写回一整片方块（成百上千次 {@code setBlock}），
     * 紧接着下一帧又 hide 一遍 —— 玩家看到的就是"方块闪一下"和被反复改写的方块数据。
     *
     * <h2>现在的语义</h2>
     * {@code !isFormed()} 必须<b>连续保持 ≥ 阈值</b>才真的 restore。阈值 =
     * {@code shanhai-common.toml: primordial_omega_engine.ringRestoreDebounceTicks}，
     * 默认 {@link ShanhaiConfig#DEFAULT_RING_RESTORE_DEBOUNCE_TICKS}（= 100 tick = 5 秒），
     * 远大于实测最长瞬时窗口 3.012 秒。
     *
     * <h2>计时源：单调墙钟 {@link #nowMs()}（2026-10-18 由 {@code level.getGameTime()} 换过来）</h2>
     * 本方法由渲染路径<b>逐帧</b>调用。若按帧计数，同一个 3 秒窗口在 60fps 下是 180 "单位"、
     * 在 240fps 下是 720 "单位" —— 阈值会变成"玩家帧率越低越容易触发 restore"，语义完全不可控。
     * 所以<b>不能</b>用帧数。
     *
     * <p>🔴 换成墙钟的原因（父代理 2026-10-18 明确要求）：世界时钟暂停时
     * {@code ClientLevel#tickTime} 被钉住 ⇒ {@code gameTime} 不走 ⇒ 任何按 tick 计数的去抖
     * 都可能<b>卡在窗口里</b>（尤其是客户端万一停在"以为还在暂停"的状态 ⇒ 永久卡住）。
     * 墙钟单调、与 tick/暂停/帧率都无关 ⇒ 这一类卡死结构上不存在。
     * 阈值仍以 <b>tick 为单位</b>（配置口径不变），换算 {@code ×50 ms} 后比较。
     *
     * <p>⚠️ 与之配套：{@link #isFormedStable}（渲染闸门）用<b>同一个</b> {@link #nowMs()} 与同一个阈值
     * ⇒ "环消失"和"方块写回"落在同一个窗口里，不会出现"环没了、方块还藏着"的洞。
     */
    public static void restoreRingsAt(Level level, long machinePos, Direction frontFacing) {
        long now = nowMs();
        Long since = UNFORMED_SINCE.get(machinePos);
        if (since == null) {
            // 本帧是"未成型"的第一帧（或刚换过维度）⇒ 只开始计时，不动任何方块。
            UNFORMED_SINCE.put(machinePos, now);
            return;
        }
        if (now - since < restoreDebounceTicks() * 50L) {
            return; // 还没连续撑够阈值 ⇒ 什么都不做（这才是"去抖"）
        }
        UNFORMED_SINCE.remove(machinePos);   // 本轮未成型已兑现，下次重新计时

        Map<Long, BlockState> mine = HIDDEN.remove(machinePos);
        INCOMPLETE.remove(machinePos);       // 台账没了，"未扫全"也无意义（否则会一直重扫空气）
        LAST_DRIFT_TICK.remove(machinePos);
        // 体检状态机同生同死：台账都没了，就不该留着"上次心跳"与"正在漂移"
        //（否则下次重新 hide 时，第一行体检会打成假的 ring_drift_recovered）。
        LAST_HEARTBEAT_TICK.remove(machinePos);
        DRIFTING.remove(machinePos);
        // 同理：ring_hide 的降噪状态也随台账一起清 ⇒ 重新 hide 时第一轮必然"状态变化"⇒ 首行必打。
        LAST_HIDE_SIGNATURE.remove(machinePos);
        LAST_HIDE_LOG_TICK.remove(machinePos);
        LAST_HIDE_SUPPRESSED.remove(machinePos);
        LAST_HIDE_SEEN.remove(machinePos);
        LEDGER_INDEX.remove(machinePos);     // A 的反向索引与台账同生同死
        if (mine == null) {
            return;
        }
        Map<String, Integer> written = new HashMap<>();
        int restored = 0;
        int skippedNotAir = 0;
        for (Map.Entry<Long, BlockState> entry : mine.entrySet()) {
            BlockPos pos = BlockPos.of(entry.getKey());
            if (!level.isLoaded(pos)) {
                continue;
            }
            // 只写回"还是我们留下的空气"的位置：不覆盖玩家在此期间自己放的方块
            if (!level.getBlockState(pos).isAir()) {
                skippedNotAir++;
                continue;
            }
            level.setBlock(pos, entry.getValue(), FLAGS);
            // 台账：写回去的**实际方块 id**。修复后这里恒等于 hide 时的快照，
            // 因此它若出现 gtladditions: 的环材质 ⇒ 说明"快照本身就带着幽灵"（历史遗留）。
            written.merge(BuiltInRegistries.BLOCK.getKey(entry.getValue().getBlock()).toString(), 1, Integer::sum);
            restored++;
        }
        if (restored > 0 || skippedNotAir > 0) {
            BlockPos base = BlockPos.of(machinePos);
            ShanhaiMod.LOGGER.info("[SHANHAI-SPEC] ring_restore machine=({},{},{}) restored={} skipped_not_air={} written={}",
                    base.getX(), base.getY(), base.getZ(), restored, skippedNotAir, histogram(written));
        }
    }

    /**
     * restore 去抖阈值（tick），来自配置。
     *
     * <p>配置未加载时退回 {@link ShanhaiConfig#DEFAULT_RING_RESTORE_DEBOUNCE_TICKS}：
     * {@code ConfigValue.get()} 在 spec 未加载时会直接抛 {@code IllegalStateException}
     * （开发环境/资源重载窗口期），而本方法在渲染路径上——不能抛。
     * 这也是本工程既有的写法（{@code PrimordialOmegaEngineMachine#isSphereStyleOverridden} 同款兜底）。
     */
    private static long restoreDebounceTicks() {
        if (!ShanhaiConfig.isLoaded()) {
            return ShanhaiConfig.DEFAULT_RING_RESTORE_DEBOUNCE_TICKS;
        }
        return ShanhaiConfig.COMMON.ringRestoreDebounceTicks.get();
    }

    /**
     * 把"id → 格数"统计渲染成最多 3 项 + 其余合计的紧凑串（**只用于诊断日志**）。
     *
     * <p>为什么需要它：用户报告"客户端看得见 gtladditions 的方块，但服务端根本没有"。
     * 客户端代码<b>读不到服务端的方块</b>（这正是"幽灵"的定义），所以客户端能给出的最强证据是
     * **"我写 AIR 之前，这一格在客户端上是什么"** —— 把它打成数字，就能把"观感"变成"可对数的实测"。
     * 判读方式：{@code first_seen} 里出现 {@code gtladditions:} ⇒ 客户端确实持有服务端没有的方块。
     */
    private static String histogram(Map<String, Integer> counts) {
        if (counts.isEmpty()) {
            return "none";
        }
        String best1 = null;
        String best2 = null;
        String best3 = null;
        int c1 = 0;
        int c2 = 0;
        int c3 = 0;
        int total = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            int c = e.getValue();
            total += c;
            if (c > c1) {
                best3 = best2;
                c3 = c2;
                best2 = best1;
                c2 = c1;
                best1 = e.getKey();
                c1 = c;
            } else if (c > c2) {
                best3 = best2;
                c3 = c2;
                best2 = e.getKey();
                c2 = c;
            } else if (c > c3) {
                best3 = e.getKey();
                c3 = c;
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append(best1).append(':').append(c1);
        if (best2 != null) {
            sb.append('|').append(best2).append(':').append(c2);
        }
        if (best3 != null) {
            sb.append('|').append(best3).append(':').append(c3);
        }
        int rest = total - c1 - c2 - c3;
        if (rest > 0) {
            sb.append("|+other:").append(rest);
        }
        return sb.toString();
    }

    /**
     * 位置公式：<b>逐字照抄</b> gtladditions {@code ClientRingBlockHelper.getWorldRealPosByPosition}
     * （反编译原文 L194-208）：
     * <pre>
     * starOffset = getRotatedRenderPosition(BASE_DIRECTION, facing, w == 94 ? -123.0 : -122.0, 0, 0)
     * cellOffset = getRotatedRenderPosition(WEST, facing, x - w/2, h/2 - y, z - d/2)
     * worldPos   = machinePos + floor(starOffset) + floor(cellOffset)
     * </pre>
     */
    private static BlockPos worldPos(BlockPos machinePos, Direction frontFacing,
                                     int x, int y, int z, int w, int h, int d) {
        Vec3 starOffset = CommonUtils.INSTANCE.getRotatedRenderPosition(
                AntichristRenderProfile.Companion.getBASE_DIRECTION(), frontFacing,
                w == 94 ? -123.0 : -122.0, 0.0, 0.0);
        Vec3 cellOffset = CommonUtils.INSTANCE.getRotatedRenderPosition(
                Direction.WEST, frontFacing,
                x - w / 2, h / 2 - y, z - d / 2);
        return machinePos
                .offset(BlockPos.containing(starOffset.x, starOffset.y, starOffset.z))
                .offset(BlockPos.containing(cellOffset.x, cellOffset.y, cellOffset.z))
                .immutable();
    }

    // ------------------------------------------------- 中子星：交给伪神的延迟渲染批次

    /**
     * 中子星基准半径（来自 gtladditions 的 {@code AntichristRenderProfile.BASE_STAR_RADIUS}）。
     * 保持与伪神之锻炉同一基准，球体大小才不会在两台机器间"看起来不一样大"。
     */
    public static float baseStarRadius() {
        return AntichristRenderProfile.BASE_STAR_RADIUS;
    }

    /**
     * 入队一颗中子星，由 gtladditions 的延迟批次在 {@code AFTER_TRANSLUCENT_BLOCKS} 阶段统一绘制。
     *
     * <p><b>入参刻意只用 MC 原生类型</b>：调用方不需要、也不允许碰到
     * {@code AntichristRenderProfile} / {@code RenderMode}。这里固定用 {@link RenderMode#NORMAL}
     * （上游原样：{@code RenderMode.NORMAL}），{@code beamAlpha} 传 0 表示<b>不借用伪神的天顶光柱</b>。
     *
     * @param continuousTick <b>必须是连续时钟</b>（{@code RenderUtil.getSmoothTick}）。
     *                       ⚠️ 语义不许丢：球体常驻可见，任何"在工作状态翻转时归零"的时钟都会被
     *                       直接渲染成一次姿态突跳 —— {@code AntichristStarRenderer} 把 tick 当
     *                       <b>累积角度</b>用（{@code base + tick*mult % 360000}），三层球壳无插值无状态，
     *                       时钟一跳就是整颗球猛地翻一下。
     */
    public static void enqueueNeutronStar(BlockEntity blockEntity, float continuousTick, boolean isWorking,
                                          Direction facing, Vec3 starPos,
                                          float colorR, float colorG, float colorB,
                                          float starRadius, float beamAlpha) {
        AntichristRenderProfile profile = new AntichristRenderProfile(
                continuousTick,
                isWorking,
                facing,
                RenderMode.NORMAL,
                starPos,
                colorR, colorG, colorB,
                starRadius,
                beamAlpha);
        AntichristDeferredRenderer.INSTANCE.enqueue(blockEntity, profile);
    }
}
