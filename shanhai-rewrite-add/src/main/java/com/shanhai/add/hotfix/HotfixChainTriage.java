package com.shanhai.add.hotfix;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * <b>选靶的唯一真源：三段切分 ＋ 两个锚点 ＋ 打分 ＋ 三级证据（cause_id 账本）</b>（纯 JDK，可离线断言）。
 *
 * <h2>一、为什么要有它（2026-11-20 现场）</h2>
 * 老口径是「从栈顶往下取<b>第一个</b>外部帧」。实测那一帧永远是<b>工具类/转发壳</b>：
 * <pre>
 *   violation_stack where=health … top=…capture|…#guardSetHealthHead:111
 *     | …LivingEntity#m_21153_ | examplemod.util.ExampleHelper#writeHealth:375   ← 老口径挑它
 *     | examplemod.util.ExampleHelper#calcDamage:300 | …#hurtOnce:119
 *     | examplemod.mob.ExampleMob#executeKill:819 | …#swipeStep:553 | …#tickBody:411
 *     | …#tick:393 | examplemod.level.ExampleLevel#tickLevel:199 | …Hooks#serverHook:29
 * </pre>
 *
 * <h2>二、三段切分（栈顶 = 写入点）</h2>
 * <pre>
 *   [框架段] 白名单帧（{@code net.minecraft.*}/{@code net.minecraftforge.*}/{@code cpw.*}/
 *            {@code org.gtlcore.*}/{@code com.gtladd.*}/{@code java.*}/{@code com.shanhai.*} …）
 *   [外部段] <b>连续</b>的非白名单帧（＝这个 mod 的"手"）
 *   [框架段] 事件总线 / tick 调度 / 命令 / 网络包
 * </pre>
 *
 * <h2>三、两个锚点（都要算、都要记账）</h2>
 * <ul>
 *   <li><b>入口锚 {@code entry_anchor}</b> ＝ 外部段里<b>最靠近框架（最靠栈底）</b>那一帧。
 *       <b>只记录、不攻击</b>（改它等于让整个 mod 停摆）；</li>
 *   <li><b>动作锚 {@code action_anchor}</b> ＝ 外部段里<b>得分最高</b>那一帧。<b>这才是 patch 目标。</b></li>
 * </ul>
 *
 * <h2>四、三个硬条件（不满足就不算"发起者"＝不进动作锚候选）</h2>
 * <ol>
 *   <li>必须是外部帧，且出现在<b>这个目标</b>的这条链上；</li>
 *   <li>它紧邻下方（更靠近栈底）的那一帧<b>不能是别的 mod 的帧</b>（那是"被转发进来的上层转发者"）；</li>
 *   <li>不能是<b>纯转发壳</b>（方法体只有一次调用转发、无分支、指令数 {@code <15}）。</li>
 * </ol>
 *
 * <h2>🔴 硬条件 2 的实现口径（与逐字读法不同，必须留档）</h2>
 * 逐字读「上方紧邻必须是框架帧」会把<b>同 mod 链上除第一帧外的所有帧</b>全部判成中间人
 * （现场那条链里 {@code executeKill} 下面紧邻的是同 mod 的 {@code swipeStep}），
 * 那样动作锚永远只剩最靠栈底那一帧，与"入口锚＝栈底那一帧、动作锚＝得分最高"的分工直接矛盾。
 * ⇒ 落地为：<b>紧邻下方那一帧若是外部帧且属于<b>另一个顶层包</b>，才判为"被转发进来"并淘汰</b>；
 * 同包（同一个 mod 自己的内部调用链）放行。逐帧判定见 {@code cond2=}。
 *
 * <h2>五、打分（只在"合格"的外部帧里取最高）</h2>
 * <pre>
 *   +3.0 / (帧距 + 1)      帧距 = 距写入点几帧（写入点旁 = 1 ⇒ 1.50，**不再拿满**）
 *   +3.0 × 调用面窄        同一方法近期只被 1 个上游调过 = 满；2 个 = 一半；≥3 = 0
 *   +2.0  含"强度/目标"信息 方法体里有伤害数值 / 目标筛选 / 距离 / 冷却判断
 *   -2.0  工具类特征        包或类名含 util|helper|api|misc|common，或近期在多条链上出现
 *   -2.0  分发器特征        类/方法名含 Bus|Dispatcher|Relay|Proxy|…，或方法体把同一件事
 *                          派给 ≥2 个不同接收者类型（方法体那条更硬，读得到就以它为准）
 *   -1.0  方法体过短        < 15 条指令（通常只是转发）
 * </pre>
 * 逐帧得分原样进日志 {@code scores=[…]}。
 *
 * <h2>五-b、🔴 写入点自己不能当动作锚（2026-11-21 现场，优先级最高）</h2>
 * 现场那条栈里 {@code action=…EventBus#post:72} 与 {@code write=…EventBus#post:72} 是<b>同一帧</b>：
 * 它离写入点最近（旧公式 {@code 3.0/1 = 满}）＋ 方法体有分支（{@code +2.0}）⇒ 6.00，压过真正的动作帧 3.75。
 * <pre>
 *   chain_owner=… depth=9 entry=…Hooks#tickServer:29
 *     action=…EventBus#post:72 picked=…EventBus#post:72 write=…EventBus#post:72   ← 同一帧
 *     scores=[…EventBus#post:72=6.00, EntityUtil#damageHealth:298=2.50, …#instantKill:819=3.75, …]
 * </pre>
 * ⇒ 两条结构判据（都不认 mod 名）：
 * <ol>
 *   <li><b>写入点自己不是"发起者"</b>：{@code 类#方法:行} 与 {@code write} 帧相同 ⇒
 *       {@link Score#eligible()} 恒 {@code false}（日志 {@code skipped=[… reason=is_write_frame]}）。
 *       它是"事情发生的地方"，不是"谁让它发生"；<b>即使分数最高也跳过</b>。深度 1 的链（写入点就是
 *       唯一外部帧）因此退到 {@link #REASON_BEST_EFFORT}（诚实降级、不进账本，但该帧仍会被改）。</li>
 *   <li><b>帧距这一项不许拿满</b>：{@code 3.0 / (dist + 1)}。旧公式 {@code 3.0 / dist} 在写入点旁
 *       直接给 3.00（满分 3.00）⇒ 等于把判断权交给"离得最近"这个最不可靠的信号。
 *       新公式：dist=1 ⇒ 1.50、dist=2 ⇒ 1.00、dist=9 ⇒ 0.30，<b>恒 ≤3.0 且平滑</b>，而且
 *       <b>曲线形状不变 ⇒ 其余帧的相对顺序一个字不动</b>（取舍见 {@link #DIST_POINTS_MAX}）。</li>
 * </ol>
 *
 * <h2>六、三级证据（🔴 时间窗只当最后一级，绝不把"猜"写在"链"前面）</h2>
 * <pre>
 *   ① evidence=stack             现场栈里就有外部段 ⇒ 直接用栈里的 action_anchor（最可靠，不许舍近求远）
 *   ② evidence=ledger_stack      栈里没有外部帧，但账本里有【未结算】的写入记录
 *                                ⇒ 用 cause_id 精确匹配（同 target ＋ 未结算），不是按时间猜
 *                                （{@code die} 只读不消费；{@code remove} 是终结后果，才结算它）
 *   ③ evidence=ledger_timewindow cause_id 匹配不到才退化成时间窗（age≤100t），日志必带 age 与原因
 *   ④ evidence=none              ⇒ refused detail=no_origin_candidate
 * </pre>
 *
 * <h2>七、日志（都是 {@code [SHANHAI-TRIAGE]}，本类只拼行；{@code evidence=} 四选一必须出现）</h2>
 * <pre>
 *   [SHANHAI-TRIAGE] chain_owner=examplemod depth=5 entry=…Hooks#serverHook:29 \
 *        action=…ExampleMob#executeKill:819 write=…ExampleHelper#writeHealth:375 \
 *        picked=…ExampleMob#executeKill:819 reason=action_anchor evidence=stack cause_id=17 \
 *        scores=[…ExampleMob#executeKill:819=5.75,…]
 *   [SHANHAI-TRIAGE] chain_first where=health owner=examplemod frames=[… | … | …]
 *   [SHANHAI-TRIAGE] consequence=remove picked=…ExampleMob#executeKill:819 evidence=ledger_stack \
 *        cause_id=17 settled=1 age=1 entry=…ExampleHooks#serverHook:29 action=… chain_depth=5
 *   [SHANHAI-TRIAGE] refused detail=no_origin_candidate stack=all_framework age_window=100t
 *   [SHANHAI-TRIAGE] motion=evidence_only where=motion frames=16 picked=none reason=evidence_only
 * </pre>
 *
 * <p>⚠️ 本类<b>不做</b> Instrumentation 动作、不读游戏类、不打日志：方法体信号由 {@link BodyProbe}
 * 注进来（生产实现 {@link HotfixBodyProbe}，离线用假体）。
 */
public final class HotfixChainTriage {

    /** 与本体分诊同一个标记（用户点名要这一行）。 */
    public static final String PREFIX = "[SHANHAI-TRIAGE]";

    /** {@code chain_owner=} 摘要行的限流：<b>每 5 秒最多 1 行</b>。 */
    public static final long LOG_INTERVAL_MILLIS = 5_000L;

    /** {@code no_origin_candidate} 的限流：同一玩家 10 s 最多一行（用户口径：必须罕见）。 */
    public static final long NO_ORIGIN_LOG_INTERVAL_MILLIS = 10_000L;

    /** {@code chain_first} 一次最多打多少帧（从写入点往上）。 */
    public static final int MAX_CHAIN_FRAMES = 10;

    /** 1 tick ≈ 50 ms（20 TPS）。⚠️ 墙钟换算，不是服务端 tick 计数器（见 {@link Cause#tick}）。 */
    public static final long MILLIS_PER_TICK = 50L;

    /** 只有第三级（时间窗兜底）才用这个窗口（用户给的值 100）。 */
    public static final int TIMEWINDOW_TICKS = 100;

    /**
     * 一条 pending 账目的<b>存活上限</b>（1200 tick ≈ 60 s）。
     *
     * <h2>为什么不是 100 tick 拍脑袋（用户点名要求写清规则与理由）</h2>
     * <ol>
     *   <li><b>第一道规则不是时间，是"被下一次写入顶掉"</b>：同一个 {@code (target, field)}
     *       只保留<b>最新一条</b>未结算账目（见 {@link CauseLedger#open}）。⇒ 一条账目能被后续
     *       后果认领，只可能发生在"它确实是这个目标最近一次的写入"这个前提下；</li>
     *   <li><b>第二道是"结算即删除"</b>：一旦被某次后果认领（或现场栈已经把凶手带来了），
     *       立刻删掉 ⇒ 同一条账目<b>不可能</b>被认领两次（最多错一次，不会连环错）；</li>
     *   <li><b>这个上限只是兜底</b>：它管的是"写完了却一直没等到后果"的僵尸账目
     *       （玩家被补血/退服/换维度）。取 <b>60 s</b> 的依据是它与既有
     *       {@code VIOLATION_STRIKE_TTL_MILLIS = 30 s}（"同一场战斗"的上界）同量级再翻一倍，
     *       且<b>过期不是静默丢弃</b>：会打一行 {@code cause_expired}，出现频率本身就是
     *       "这条假设对不对"的读数。</li>
     * </ol>
     */
    public static final int PENDING_MAX_TICKS = 1_200;

    /** 一次最多报几个候选。 */
    public static final int MAX_CANDIDATES = 3;

    private static final int MAX_PER_PLAYER = 6;
    private static final int MAX_PLAYERS = 32;
    private static final int MAX_TOOL_KEYS = 256;
    private static final int MAX_TOOL_CLASSES = 128;
    private static final int MAX_CALLERS_PER_KEY = 6;
    private static final int MAX_FIRST_KEYS = 64;
    private static final int MAX_NO_ORIGIN_KEYS = 32;

    /**
     * 🆕 2026-11-20：<b>模糊相邻对</b>的分差闸 —— {@code top1.score - top2.score < 0.5} 才视为"分不清"。
     *
     * <p>用户口径（原话）：<b>"不要赌这个宽度"</b>、<b>"结构上不该靠 0.15 决定改谁"</b>。
     * 实测那张表里 {@code #executeKill:819=5.75} 与 {@code #swipeStep:553=5.60} 只差 0.15 —— 赌它就是赌运气。
     */
    private static final double AMBIGUOUS_GAP = 0.5;

    /**
     * 一次热改最多几个目标：🔴 <b>硬编码 2，不许超过</b>（结构上也超不过：{@link Pick} 只有一个
     * {@code secondFrame} 槽）。
     *
     * <p>为什么写死：这个数的意义是"模糊时别赌，但也不许撒网"。超过 2 就不再是"这两个分不清"，
     * 而是"整段都动"，会连累与该动作无关的方法。
     */
    private static final int MAX_PATCH_TARGETS = 2;

    /**
     * 🆕 2026-11-21：<b>帧距这一项的上限（与旧公式同一个数值：3.0）</b>。
     *
     * <p>⚠️ 数值没变，变的是<b>曲线</b>：旧公式 {@code 3.0 / dist} 在写入点旁（dist=1）<b>直接拿满 3.0</b>
     * ⇒ 等于把选靶的一票交给"离写入点最近"这个最不可靠的信号（现场就是它选出分发器的）。
     *
     * <p>新公式 {@code 3.0 / (dist + 1)}：
     * <pre>
     *   dist=1 ⇒ 1.50    dist=2 ⇒ 1.00    dist=3 ⇒ 0.75    dist=4 ⇒ 0.60
     *   dist=5 ⇒ 0.50    dist=9 ⇒ 0.30    dist 再远也只是趋近 0
     * </pre>
     * <ol>
     *   <li><b>写入点旁不再拿满</b>（1.50 而不是 3.00），且这一项<b>恒 ≤ {@value #DIST_POINTS_MAX}</b>；</li>
     *   <li><b>曲线形状不变</b>（还是 {@code 1/x}）⇒ <b>其余帧的相对顺序一个字不动</b>
     *       （这是验收项之一，见类注释「五-b」）；</li>
     *   <li>为什么不选"线性饱和" {@code 3.0 × (1 - min(dist,10)/10)}（那是任务书里的<b>示例</b>）：
     *       它在 dist=1 仍给 <b>2.70/3.00 = 90%</b>（"不许拿满分"只解决了表面），而且斜率在尾部反而更陡
     *       （每帧 0.30，旧曲线尾部只有 0.03）⇒ 实测会让"被工具类扣分的中段帧"反超"栈底的入口锚帧"
     *       （现场 A 那 9 帧里 {@code damageHealth} 与 {@code serverHook} 会换位）。</li>
     * </ol>
     */
    public static final double DIST_POINTS_MAX = 3.0;

    /** 帧距分母的偏移（{@code 3.0 / (dist + 1)}；把"写入点旁"从满分压到 1/2）。 */
    public static final int DIST_DENOM_OFFSET = 1;

    /** 跳过原因（日志里逐字用）：这一帧就是<b>写入点自己</b>。 */
    public static final String SKIP_IS_WRITE = "is_write_frame";

    /** 降权原因（日志里逐字用）：这一帧是<b>分发器</b>。 */
    public static final String DEMOTE_DISPATCHER = "dispatcher";

    private static final String FRAME_SEP = " | ";

    /** 顶层包前面那一层"容器段"（认"包前缀"时要跳过它们）。 */
    private static final String[] CONTAINER_SEGMENTS = {
            "com", "org", "net", "io", "dev", "cn", "me", "gg", "edu", "co",
            "java", "javax", "jdk", "github", "gitlab", "bitbucket", "top", "site", "xyz"
    };

    /** "工具类特征"的包/类名段（<b>通用词，不含任何具体 mod 的名字</b>）。 */
    private static final String[] UTILITY_SEGMENTS = {
            "util", "utils", "utility", "utilities", "helper", "helpers", "tool", "tools",
            "lib", "libs", "library", "common", "commons", "shared", "internal", "api", "misc"
    };

    /**
     * 🆕 2026-11-21：<b>"分发器"的类型词</b>（类名或方法名<b>包含</b>即算，忽略大小写）。
     *
     * <p>⚠️ 是<b>包含</b>（{@code contains}），不是"结尾等于"：现场那个类叫
     * {@code …EventBus}（类名中间还夹着该 mod 自己的前缀）⇒ 只有"包含"才抓得住。
     * 同理 {@code XXXListener}/{@code XXXHandler} 这类前缀命名也一并覆盖。
     *
     * <p>这些是<b>通用英文词</b>（不含任何具体 mod 的名字）：判据必须"泛用、不认 mod 名"。
     */
    private static final String[] DISPATCH_TYPE_WORDS = {
            "bus", "dispatcher", "dispatch", "relay", "proxy", "bridge",
            "listener", "handler", "hook"
    };

    /** "分发器"的动词前缀（方法名<b>以它开头</b>即算，忽略大小写）。 */
    private static final String[] DISPATCH_VERB_PREFIXES = {
            "post", "dispatch", "fire", "emit", "publish", "notify", "broadcast", "send", "invoke"
    };

    // ═══════════════════════════════ 证据等级（日志里逐字用） ═══════════════════════════════

    /** ① 现场栈里就有外部段（最可靠）。 */
    public static final String EVIDENCE_STACK = "stack";

    /** ② 栈里没有外部帧，用 {@code cause_id} 在未结算账目里精确匹配。 */
    public static final String EVIDENCE_LEDGER_STACK = "ledger_stack";

    /** ③ {@code cause_id} 匹配不到，退化成时间窗（带 age）。 */
    public static final String EVIDENCE_TIMEWINDOW = "ledger_timewindow";

    /** ④ 什么都没有。 */
    public static final String EVIDENCE_NONE = "none";

    // ═══════════════════════════════ 原因码（日志里逐字用） ═══════════════════════════════

    /** 动作锚（得分最高的合格外部帧）。 */
    public static final String REASON_ACTION = "action_anchor";

    /** 整条链只有一个外部帧。 */
    public static final String REASON_SOLE = "sole_caller";

    /**
     * 🆕 <b>模糊相邻对</b>：两帧分差 {@code < 0.5} ＋ 同一个 owner ＋ {@code top2} 恰是 {@code top1} 的
     * <b>直接调用者</b> ⇒ <b>两帧都改</b>（上限硬编码 {@value #MAX_PATCH_TARGETS}）。
     *
     * <p>日志口径：{@code reason=ambiguous_adjacent_pair frames=2 pair=<top1>|<top2> gap=0.15}。
     */
    public static final String REASON_AMBIGUOUS_PAIR = "ambiguous_adjacent_pair";

    /** 用账本里未结算的 cause_id。 */
    public static final String REASON_CAUSE = "cause_id_match";

    /** 退化到时间窗（日志里必须带 age）。 */
    public static final String REASON_TIMEWINDOW = "timewindow_fallback";

    /** 没有任何合格发起者、也没有账目（<b>单独的 detail 码</b>）。 */
    public static final String DETAIL_NO_ORIGIN = "no_origin_candidate";

    /** 全部合格帧都被硬条件淘汰，退而取链内最高分（诚实降级，不进账本）。 */
    public static final String REASON_BEST_EFFORT = "best_effort_no_strict_origin";

    /** 一帧都没有（与老的 {@code gate_no_strike_frame} 同口径）。 */
    public static final String GATE_NO_FRAME = "gate_no_strike_frame";

    /** 有帧、但全被包名白名单挡掉（<b>保留</b>：对"全程只有原版帧且无账目"仍是正确拒绝）。 */
    public static final String GATE_ALL_WHITELISTED = "gate_all_frames_whitelisted";

    /** 过了包名、但来源判据（code source／加载器）都不放行。 */
    public static final String GATE_NO_FOREIGN = "gate_no_foreign_frame";

    // ═══════════════════════════════ 状态 ═══════════════════════════════

    private static final ToolLedger LEDGER = new ToolLedger();
    private static final CauseLedger CAUSES = new CauseLedger();
    private static final Set<String> FIRST_SEEN = new LinkedHashSet<>();
    private static final Map<String, Long> NO_ORIGIN_LAST = new LinkedHashMap<>();

    private static volatile long lastTriageLogMillis;
    private static volatile long lastEvidenceLogMillis;
    private static volatile int lastExpired;

    private HotfixChainTriage() {
        // 纯静态
    }

    /** 全局工具类账本。 */
    public static ToolLedger ledger() {
        return LEDGER;
    }

    /** 全局写入账本（pending 表）。 */
    public static CauseLedger causes() {
        return CAUSES;
    }

    /** 上一次 {@link Result#prunedExpired} 的读数（命令／日志判读用）。 */
    public static int lastExpired() {
        return lastExpired;
    }

    // ═══════════════════════════════ 判据的输入 ═══════════════════════════════

    /** "这一帧算不算外部帧（＝可以当热改目标）"的判定口（游戏侧实现见 {@link HotfixTargetResolver#judgeFrame}）。 */
    public interface Gate {

        /** @param frame 一帧；返回 {@code null} 视为"不是外部帧" */
        Verdict judge(HotfixTargetResolver.Frame frame);
    }

    /** 一帧的判定结果。 */
    public static final class Verdict {

        public final boolean external;
        public final String reason;
        public final String codeSourceToken;

        public static final Verdict NOT_EXTERNAL = new Verdict(false, "not_external", "none");

        public Verdict(boolean external, String reason, String codeSourceToken) {
            this.external = external;
            this.reason = reason == null ? "?" : reason;
            this.codeSourceToken = codeSourceToken == null ? "none" : codeSourceToken;
        }
    }

    /** 方法体信号（由 {@link HotfixBodyProbe} 提供；拿不到 ⇒ {@link BodyInfo#UNKNOWN}）。 */
    public interface BodyProbe {

        /**
         * @param className  源级类名
         * @param methodName 方法名（不含描述符）
         * @return 信号（不许返回 {@code null}）
         */
        BodyInfo bodyOf(String className, String methodName);
    }

    /**
     * 🆕 2026-11-21：<b>class 字节从哪来</b>（只给"分发器"判据数接收者类型用）。
     *
     * <p>为什么不复用 {@link BodyProbe}：那个口子只回"几条指令/几个调用"这类<b>汇总数</b>，
     * 而"不同接收者类型数"必须回到<b>常量池</b>里查 {@code Methodref → Class}，
     * 是本类自己走一趟字节（{@link #dispatchScan(byte[], String)}）才拿得到的东西
     * （{@link HotfixBodyProbe} 不在本轮改动面里 ⇒ 不往它身上加字段）。
     *
     * <p>拿不到（{@code null}、类没加载、jar 读失败）⇒ {@code -1} ⇒ 判据退回"按名字"。
     */
    public interface ClassBytes {

        /** @return 这个类的 class 字节；拿不到 ⇒ {@code null}（<b>不抛</b>） */
        byte[] bytesOf(String className);
    }

    /** 一个方法体的廉价读数（数出来，不猜）。 */
    public static final class BodyInfo {

        /** 拿不到字节 ⇒ 什么都不判（不加分、不扣分，也不参与硬条件 3）。 */
        public static final BodyInfo UNKNOWN = new BodyInfo(false, "", 0, 0, 0, 0, 0, 0);

        /** "不同接收者类型数"读不到（{@link #receiverKinds} 的哨兵值）。 */
        public static final int RECEIVER_KINDS_UNKNOWN = -1;

        public final boolean known;
        public final String desc;
        public final int insns;
        public final int calls;
        public final int condJumps;
        public final int numericConsts;
        public final int typeChecks;
        public final int arith;

        /**
         * 🆕 2026-11-21：{@code invokeinterface}/{@code invokevirtual} 的
         * <b>不同接收者类型数</b>（{@code -1} = 读不到）。
         *
         * <p>判据含义：一个方法把<b>同一件事</b>派给 ≥2 个<b>不同类型</b>的对象 ⇒ 它是分发器，
         * 不是动作帧。这条比"名字像总线"硬得多，所以<b>读得到时以它为准</b>。
         */
        public final int receiverKinds;

        public BodyInfo(boolean known, String desc, int insns, int calls, int condJumps,
                        int numericConsts, int typeChecks, int arith) {
            this(known, desc, insns, calls, condJumps, numericConsts, typeChecks, arith,
                    RECEIVER_KINDS_UNKNOWN);
        }

        public BodyInfo(boolean known, String desc, int insns, int calls, int condJumps,
                        int numericConsts, int typeChecks, int arith, int receiverKinds) {
            this.known = known;
            this.desc = desc == null ? "" : desc;
            this.insns = insns;
            this.calls = calls;
            this.condJumps = condJumps;
            this.numericConsts = numericConsts;
            this.typeChecks = typeChecks;
            this.arith = arith;
            this.receiverKinds = receiverKinds < 0 ? RECEIVER_KINDS_UNKNOWN : receiverKinds;
        }

        /** 复制一份、只把"不同接收者类型数"填上（{@code kinds < 0} 或没变 ⇒ 原样返回）。 */
        public BodyInfo withReceiverKinds(int kinds) {
            if (kinds < 0 || kinds == this.receiverKinds) {
                return this;
            }
            return new BodyInfo(known, desc, insns, calls, condJumps, numericConsts, typeChecks, arith, kinds);
        }

        /** 方法体说它是分发器：把同一件事派给 <b>≥2 个不同接收者类型</b>。 */
        public boolean dispatchByBody() {
            return receiverKinds >= 2;
        }

        /** "是不是分发器"这件事有定论吗（读到了接收者类型数）。 */
        public boolean receiverKnown() {
            return receiverKinds >= 0;
        }

        /** 纯转发壳：只有一次调用转发、无分支、指令数 <15（硬条件 3）。 */
        public boolean pureForward() {
            return known && calls <= 1 && condJumps == 0 && insns < 15;
        }

        /** 含"强度/目标"信息：伤害数值 / 目标筛选 / 距离·冷却判断（+2.0）。 */
        public boolean strengthSignal() {
            return known && (numericConsts >= 1 || typeChecks >= 1 || arith >= 1 || condJumps >= 2);
        }

        /** 日志短写法。 */
        public String brief() {
            return known
                    ? "insns=" + insns + ",calls=" + calls + ",if=" + condJumps
                            + ",num=" + numericConsts + ",type=" + typeChecks + ",arith=" + arith
                            + ",recv=" + (receiverKinds < 0 ? "?" : String.valueOf(receiverKinds))
                    : "body=unknown";
        }
    }

    // ═══════════════════════════════ 结果 ═══════════════════════════════

    /** 一次选靶的结论。 */
    public static final class Result {

        /** 挑中的目标；{@code null} = 一个都没有（此时 {@link #reason} 非空）。 */
        public final Pick pick;

        public final String reason;
        public final String detail;
        public final int framesSkipped;
        public final int framesNotForeign;

        /** 这条链的主人（{@code none} = 没有外部链）。 */
        public final String stackOwner;

        /** 候选（时间倒序，最多 3 个；进日志便于核）。 */
        public final List<Cause> candidates;

        /** 这次顺手清掉了几条过期账目（{@code >0} ⇒ 调用方该打一行 {@code cause_expired}）。 */
        public final int prunedExpired;

        /** 逐帧打分明细（离线核账用；排序＝栈序，栈顶在前）。 */
        public final List<Explained> explained;

        Result(Pick pick, String reason, String detail, int framesSkipped, int framesNotForeign,
               String stackOwner, List<Cause> candidates, int prunedExpired, List<Explained> explained) {
            this.pick = pick;
            this.reason = reason == null ? "" : reason;
            this.detail = detail == null ? "" : detail;
            this.framesSkipped = framesSkipped;
            this.framesNotForeign = framesNotForeign;
            this.stackOwner = stackOwner == null || stackOwner.isEmpty() ? "none" : stackOwner;
            this.candidates = candidates == null ? new ArrayList<Cause>(0) : candidates;
            this.prunedExpired = prunedExpired;
            this.explained = explained == null ? new ArrayList<Explained>(0) : explained;
        }

        public boolean ok() {
            return pick != null;
        }
    }

    /** 挑中的目标（能直接翻成 {@link HotfixTargetResolver.Target}）。 */
    public static final class Pick {

        public final HotfixTargetResolver.Frame frame;
        public final String className;
        public final String methodName;
        public final int line;

        /** 这条链的主人（顶层包前缀；账本 ⇒ {@code ledger}）。 */
        public final String chainOwner;

        /** 这条链里有几个外部帧（{@code depth=}／{@code chain_depth=}）。 */
        public final int depth;

        /** 入口锚（只记录、不攻击）。 */
        public final String entry;

        /** 动作锚（得分最高）。 */
        public final String action;

        /** 写入点帧（整条栈里第一个外部帧）。 */
        public final String write;

        /** 选中的那个（{@code 类#方法:行}）。 */
        public final String picked;

        public final String reason;

        /** {@link #EVIDENCE_STACK}／{@link #EVIDENCE_LEDGER_STACK}／{@link #EVIDENCE_TIMEWINDOW}。 */
        public final String evidence;

        /** 账本命中那条 {@code cause_id}；{@code evidence=stack} 时 = 这次结算掉的账目（{@code 0} = 没有）。 */
        public final long causeId;

        /**
         * 这条账目有没有被这次后果<b>结算掉</b>（{@code 结算即删除}）。
         *
         * <p>口径：只有<b>终结后果</b>（{@code remove}）才消费；{@code die} 只读不消费
         * （同一场死亡通常是 die → remove 两拍，两拍都该看到同一条 cause_id；
         * 若 die 就消费掉，remove 只能退化到时间窗，而"时间窗只当最后一级"）。
         */
        public final boolean causeSettled;

        /** 账本命中时的年龄（tick；现场栈 ⇒ -1）。 */
        public final int ageTicks;

        /** 逐帧得分表（{@code 类#方法:行=5.75}，逗号分隔）。 */
        public final String scoreTable;

        public final int framesSkipped;
        public final int framesNotForeign;
        public final int writeIndex;
        public final String codeSourceToken;

        /**
         * 🆕 第二帧（{@link #reason} = {@link #REASON_AMBIGUOUS_PAIR} 时才非空；其余情况恒 {@code null}）。
         *
         * <p>🔴 <b>只有一个槽</b> ⇒ 一次最多两帧（{@link HotfixChainTriage#MAX_PATCH_TARGETS} 是结构上限，
         * 不是配置）。它必然与 {@link #frame} <b>同一个 owner</b>、且在栈上是 {@link #frame} 的直接调用者。
         */
        public final HotfixTargetResolver.Frame secondFrame;

        /** 两帧的分差（{@code top1 - top2}；没有第二帧 ⇒ {@code 0.0}）。 */
        public final double pairGap;

        /** 第二帧的 {@code code_source=} 标记（没有第二帧 ⇒ {@code none}）。 */
        public final String secondCodeSourceToken;

        /**
         * 🆕 2026-11-21：被<b>跳过</b>的写入点帧（{@code 类#方法:行}；没有 ⇒ {@code ""}）。
         *
         * <p>为什么必须记：现场那次选错的正是"写入点自己"（{@code action} 与 {@code write} 同一帧）。
         * 日志里写成 {@code skipped=[… reason=is_write_frame]}，一眼能看出"它分最高但被结构判据挡掉了"。
         */
        public final String skippedWrite;

        /** 🆕 被"分发器"判据降权的链内帧（{@code 类#方法:行}，逗号分隔；没有 ⇒ {@code ""}）。 */
        public final String demotedFrames;

        Pick(HotfixTargetResolver.Frame frame, String chainOwner, int depth, String entry, String action,
             String write, String picked, String reason, String evidence, long causeId, boolean causeSettled,
             int ageTicks, String scoreTable, int framesSkipped, int framesNotForeign, int writeIndex,
             String codeSourceToken, HotfixTargetResolver.Frame secondFrame, double pairGap,
             String secondCodeSourceToken, String skippedWrite, String demotedFrames) {
            this.frame = frame;
            this.className = frame == null ? "" : frame.className;
            this.methodName = frame == null ? "" : frame.methodName;
            this.line = frame == null ? -1 : frame.line;
            this.chainOwner = chainOwner == null || chainOwner.isEmpty() ? "none" : chainOwner;
            this.depth = depth;
            this.entry = entry == null || entry.isEmpty() ? "none" : entry;
            this.action = action == null || action.isEmpty() ? "none" : action;
            this.write = write == null || write.isEmpty() ? "none" : write;
            this.picked = picked == null || picked.isEmpty() ? "none" : picked;
            this.reason = reason == null ? "?" : reason;
            this.evidence = evidence == null || evidence.isEmpty() ? EVIDENCE_NONE : evidence;
            this.causeId = causeId;
            this.causeSettled = causeSettled;
            this.ageTicks = ageTicks;
            this.scoreTable = scoreTable == null ? "" : scoreTable;
            this.framesSkipped = framesSkipped;
            this.framesNotForeign = framesNotForeign;
            this.writeIndex = writeIndex;
            this.codeSourceToken = codeSourceToken == null ? "none" : codeSourceToken;
            this.secondFrame = secondFrame;
            this.pairGap = pairGap;
            this.secondCodeSourceToken = secondCodeSourceToken == null ? "none" : secondCodeSourceToken;
            this.skippedWrite = skippedWrite == null ? "" : skippedWrite;
            this.demotedFrames = demotedFrames == null ? "" : demotedFrames;
        }

        /** 是不是"从账本里翻出来的"（不是现场栈）。 */
        public boolean fromLedger() {
            return EVIDENCE_LEDGER_STACK.equals(evidence) || EVIDENCE_TIMEWINDOW.equals(evidence);
        }

        /** 🆕 是不是"模糊相邻对 ⇒ 两帧都改"。 */
        public boolean paired() {
            return secondFrame != null;
        }

        /**
         * 这一次一共要改几个目标（{@code 1} 或 {@code 2}）。
         *
         * <p>🔴 数的是<b>真的有几个目标</b>（不是读配置）：只有一个 {@code secondFrame} 槽 ⇒ 最多 2，
         * 这与 {@link HotfixChainTriage#MAX_PATCH_TARGETS} 是同一个上限的两种写法。
         */
        public int targetCount() {
            return secondFrame == null ? 1 : 2;
        }

        /** 翻成老口径的 {@link HotfixTargetResolver.Target}（后续热改代码一个字都不用改）。 */
        public HotfixTargetResolver.Target toTarget() {
            return new HotfixTargetResolver.Target(className, methodName, line, framesSkipped, framesNotForeign,
                    frame == null ? picked : frame.raw, reason, codeSourceToken);
        }

        /**
         * 🆕 第二帧的目标（{@code null} = 这次只有一帧）。
         *
         * <p>计数口径与第一帧<b>同一份</b>（{@code frames_skipped}/{@code frames_not_foreign} 是"挑中之前"的
         * 读数，两帧都在同一个选靶批次里算出来的）。
         */
        public HotfixTargetResolver.Target secondTarget() {
            if (secondFrame == null) {
                return null;
            }
            return new HotfixTargetResolver.Target(secondFrame.className, secondFrame.methodName, secondFrame.line,
                    framesSkipped, framesNotForeign, secondFrame.raw, reason, secondCodeSourceToken);
        }

        /** 🆕 这一次要改的全部目标（1 或 2 个；第一项恒等于 {@link #toTarget()}）。 */
        public List<HotfixTargetResolver.Target> targets() {
            final List<HotfixTargetResolver.Target> list = new ArrayList<>(MAX_PATCH_TARGETS);
            list.add(toTarget());
            final HotfixTargetResolver.Target second = secondTarget();
            if (second != null) {
                list.add(second);
            }
            return list;
        }

        @Override
        public String toString() {
            return picked + " reason=" + reason + " evidence=" + evidence + " owner=" + chainOwner
                    + " depth=" + depth + " entry=" + entry + " action=" + action
                    + (causeId != 0L ? " cause_id=" + causeId : "")
                    + (ageTicks >= 0 ? " age=" + ageTicks : "");
        }
    }

    // ═══════════════════════════════ 选靶 ═══════════════════════════════

    /** 便捷入口（不判方法体）。 */
    public static Result pick(List<HotfixTargetResolver.Frame> frames, Gate gate, String where,
                              String player, long nowMillis) {
        return pick(frames, gate, null, null, where, player, nowMillis);
    }

    /**
     * 从一条栈里挑目标（<b>不数接收者类型</b>：分发器判据退化成"按名字判"）。
     *
     * @param frames    帧表（栈顶在前）
     * @param gate      外部帧判定口（{@code null} ⇒ 一帧都不算外部帧）
     * @param body      方法体信号（{@code null} ⇒ 不判方法体）
     * @param where     {@code health|die|remove|hurt|…}
     * @param player    目标玩家
     * @param nowMillis 现在
     */
    public static Result pick(List<HotfixTargetResolver.Frame> frames, Gate gate, BodyProbe body,
                              String where, String player, long nowMillis) {
        return pick(frames, gate, body, null, where, player, nowMillis);
    }

    /**
     * 从一条栈里挑目标（<b>生产路径</b>）。
     *
     * @param frames    帧表（栈顶在前）
     * @param gate      外部帧判定口（{@code null} ⇒ 一帧都不算外部帧）
     * @param body      方法体信号（{@code null} ⇒ 不判方法体）
     * @param bytes     🆕 class 字节来源（只给"分发器"判据数不同接收者类型；{@code null} ⇒ 按名字判）
     * @param where     {@code health|die|remove|hurt|…}
     * @param player    目标玩家
     * @param nowMillis 现在
     */
    public static Result pick(List<HotfixTargetResolver.Frame> frames, Gate gate, BodyProbe body,
                              ClassBytes bytes, String where, String player, long nowMillis) {
        final boolean consequence = HotfixTrigger.isConsequence(where);
        // 顺手清过期账目（后果事件才需要；原因事件不用账本）
        final int pruned = consequence ? CAUSES.prune(player, nowMillis) : 0;
        lastExpired = pruned;

        if (frames == null || frames.isEmpty()) {
            return noChain(where, player, 0, 0, "", nowMillis, pruned);
        }

        // ---- ① 逐帧：是不是外部帧
        final int size = frames.size();
        final boolean[] external = new boolean[size];
        final Verdict[] verdicts = new Verdict[size];
        int skipped = 0;
        int notForeign = 0;
        String lastDetail = "";
        for (int i = 0; i < size; i++) {
            final HotfixTargetResolver.Frame f = frames.get(i);
            if (f == null) {
                continue;
            }
            Verdict v = null;
            if (gate != null) {
                try {
                    v = gate.judge(f);
                } catch (Throwable t) {
                    v = null;             // 判定口坏了 ⇒ 当"不是外部帧"，宁可不动手
                }
            }
            if (v == null) {
                v = Verdict.NOT_EXTERNAL;
            }
            verdicts[i] = v;
            external[i] = v.external;
            if (!v.external) {
                if (HotfixPatchWhitelist.isNeverPatch(f.className)) {
                    skipped++;
                } else {
                    notForeign++;
                    if (v.reason != null && !v.reason.isEmpty()) {
                        lastDetail = v.reason;
                    }
                }
            }
        }

        // ---- ② 连续段分组（＝三段切分里的"外部段"）
        final List<int[]> runs = new ArrayList<>(4);
        int runStart = -1;
        for (int i = 0; i <= size; i++) {
            final boolean ext = i < size && external[i];
            if (ext && runStart < 0) {
                runStart = i;
            } else if (!ext && runStart >= 0) {
                runs.add(new int[]{runStart, i});
                runStart = -1;
            }
        }
        if (runs.isEmpty()) {
            return noChain(where, player, skipped, notForeign, lastDetail, nowMillis, pruned);
        }

        // ---- ③ 整条外部链是不是同一个顶层包（是 ⇒ 视为一次攻击，把各段连起来）
        String firstOwner = null;
        boolean sameOwner = true;
        for (int r = 0; r < runs.size(); r++) {
            final int[] run = runs.get(r);
            final String owner = ownerOf(frames.get(run[0]).className);
            if (firstOwner == null) {
                firstOwner = owner;
            } else if (!firstOwner.equals(owner)) {
                sameOwner = false;
            }
        }
        final List<Integer> chain = new ArrayList<>(size);
        if (sameOwner) {
            for (int r = 0; r < runs.size(); r++) {
                final int[] run = runs.get(r);
                for (int i = run[0]; i < run[1]; i++) {
                    if (external[i]) {
                        chain.add(Integer.valueOf(i));
                    }
                }
            }
        } else {
            int[] best = null;
            for (int r = 0; r < runs.size(); r++) {
                final int[] run = runs.get(r);
                if (best == null || (run[1] - run[0]) > (best[1] - best[0])) {
                    best = run;
                }
            }
            for (int i = best[0]; i < best[1]; i++) {
                if (external[i]) {
                    chain.add(Integer.valueOf(i));
                }
            }
        }
        if (chain.isEmpty()) {
            return noChain(where, player, skipped, notForeign, lastDetail, nowMillis, pruned);
        }
        final String chainOwner = ownerOf(frames.get(chain.get(0).intValue()).className);

        // ---- ④ 写入点下标（整条栈里第一个外部帧）
        int writeIdx = -1;
        for (int i = 0; i < size; i++) {
            if (external[i]) {
                writeIdx = i;
                break;
            }
        }
        // ---- ⑤ 入口锚 = 链里最靠栈底那一帧（只记录、不攻击）
        final int entryIdx = chain.get(chain.size() - 1).intValue();

        // ---- ⑥ 逐帧算分 ＋ 三个硬条件
        final StringBuilder table = new StringBuilder(192);
        final List<Explained> explained = new ArrayList<>(chain.size());
        // 🆕 2026-11-20：分数留一份（"模糊双改"要拿第二名，重算一遍等于把打分打两次、还可能不同）
        final Score[] scores = new Score[chain.size()];
        int bestIdx = -1;
        double bestScore = Double.NEGATIVE_INFINITY;
        int bestAnyIdx = -1;
        double bestAnyScore = Double.NEGATIVE_INFINITY;
        // 🆕 2026-11-21：被"分发器"判据降权的帧（日志 demote=dispatcher demoted=[…]）
        final StringBuilder demoted = new StringBuilder(96);
        for (int c = 0; c < chain.size(); c++) {
            final int idx = chain.get(c).intValue();
            final HotfixTargetResolver.Frame f = frames.get(idx);
            final Score s = scoreOf(frames, f, external, idx, writeIdx, body, bytes);
            scores[c] = s;
            if (table.length() > 0) {
                table.append(',');
            }
            table.append(f.brief()).append('=').append(s.text());
            explained.add(new Explained(f.brief(), s));
            if (DEMOTE_DISPATCHER.equals(s.demote)) {
                if (demoted.length() > 0) {
                    demoted.append(',');
                }
                demoted.append(f.brief());
            }
            if (s.score > bestAnyScore) {
                bestAnyScore = s.score;
                bestAnyIdx = idx;
            }
            if (s.eligible() && s.score > bestScore) {
                bestScore = s.score;
                bestIdx = idx;
            }
        }

        final int pickedIdx;
        final String reason;
        if (bestIdx >= 0) {
            pickedIdx = bestIdx;
            reason = chain.size() <= 1 ? REASON_SOLE : REASON_ACTION;
        } else {
            pickedIdx = bestAnyIdx >= 0 ? bestAnyIdx : entryIdx;
            reason = REASON_BEST_EFFORT;    // 三个硬条件全灭 ⇒ 诚实降级（不进账本）
        }
        // 🆕 2026-11-21：写入点自己永远是"被跳过"的那一帧（日志 skipped=[… reason=is_write_frame]）
        final String skippedWrite = writeIdx < 0 || writeIdx >= size || frames.get(writeIdx) == null
                ? "" : frames.get(writeIdx).brief();

        // ---- ⑥-b 🆕 2026-11-20：模糊时"双改"（**只加这一个分支**；不满足时与老代码逐字相同）
        //   三条全满足才并列：① 分差 < 0.5；② 同一个 owner；③ top2 恰是 top1 的【直接调用者】。
        final int secondIdx = ambiguousPairIdx(chain, scores, bestIdx, bestScore, frames);
        final String pickReason = secondIdx >= 0 ? REASON_AMBIGUOUS_PAIR : reason;
        double pairGap = 0.0;
        if (secondIdx >= 0) {
            for (int c = 0; c < chain.size(); c++) {
                if (chain.get(c).intValue() == secondIdx) {
                    pairGap = bestScore - scores[c].score;
                    break;
                }
            }
        }

        // ---- ⑦ 计数（只统计"挑中之前"，与老日志同名同义）
        int skippedBefore = 0;
        int notForeignBefore = 0;
        for (int i = 0; i < pickedIdx; i++) {
            final HotfixTargetResolver.Frame f = frames.get(i);
            if (f == null || external[i]) {
                continue;
            }
            if (HotfixPatchWhitelist.isNeverPatch(f.className)) {
                skippedBefore++;
            } else {
                notForeignBefore++;
            }
        }

        // ---- ⑧ 证据等级 ①：现场栈就有外部段 ⇒ 用它，并且把该目标的未结算账目结算掉
        //   （结算 id 进日志，用来回答"这条现场把那一条账兑现了"）
        Cause settled = null;
        if (consequence && isTerminal(where)) {
            settled = CAUSES.settle(player);      // 终结后果把这条账兑现掉（die 只读不消费）
        }
        final Cause open = settled != null ? settled : (consequence ? CAUSES.matchPending(player) : null);
        final Pick pick = new Pick(frames.get(pickedIdx), chainOwner, chain.size(),
                frames.get(entryIdx).brief(),
                bestIdx >= 0 ? frames.get(bestIdx).brief() : "none",
                writeIdx < 0 ? "none" : frames.get(writeIdx).brief(),
                frames.get(pickedIdx) == null ? "none" : frames.get(pickedIdx).brief(),
                pickReason, EVIDENCE_STACK, open == null ? 0L : open.causeId, settled != null, -1,
                table.toString(), skippedBefore, notForeignBefore,
                writeIdx, verdicts[pickedIdx] == null ? "none" : verdicts[pickedIdx].codeSourceToken,
                secondIdx < 0 ? null : frames.get(secondIdx), pairGap,
                secondIdx < 0 || verdicts[secondIdx] == null ? "none" : verdicts[secondIdx].codeSourceToken,
                skippedWrite, demoted.toString());
        return new Result(pick, "", "", skippedBefore, notForeignBefore, chainOwner,
                consequence ? CAUSES.recent(player) : new ArrayList<Cause>(0), pruned, explained);
    }

    /** 一帧的分数与三个硬条件的判定结果。 */
    private static final class Score {

        final double score;
        final boolean cond2;
        final boolean cond3;
        final boolean external;

        // ---- 拆项（只给离线核账／日志复盘用）
        final int dist;
        final int callers;
        final boolean strength;
        final boolean tool;
        final boolean shortBody;
        final boolean pureForward;
        final String bodyBrief;

        /** 🆕 降权原因（{@code ""} = 没被降权；{@code dispatcher} = 分发器判据；{@code name=utility}=工具类）。 */
        final String demote;

        /** 🆕 这一帧是不是<b>写入点自己</b>（是 ⇒ 不许当动作锚，见类注释「五-b」）。 */
        final boolean isWrite;

        Score(double score, boolean external, boolean cond2, boolean cond3, int dist, int callers,
              boolean strength, boolean tool, boolean shortBody, boolean pureForward, String bodyBrief,
              String demote, boolean isWrite) {
            this.score = score;
            this.external = external;
            this.cond2 = cond2;
            this.cond3 = cond3;
            this.dist = dist;
            this.callers = callers;
            this.strength = strength;
            this.tool = tool;
            this.shortBody = shortBody;
            this.pureForward = pureForward;
            this.bodyBrief = bodyBrief == null ? "" : bodyBrief;
            this.demote = demote == null ? "" : demote;
            this.isWrite = isWrite;
        }

        /** 三个硬条件全过才算"发起者"。 */
        boolean eligible() {
            // 🆕 2026-11-21：写入点自己<b>永远</b>不是发起者（它是"事情发生的地方"）——
            //   这条优先级最高：即使它分数最高也必须跳过（现场那次选错的正是它）。
            return external && cond2 && cond3 && !isWrite;
        }

        String text() {
            return fmt(score);
        }

        private static String fmt(double v) {
            final long cents = Math.round(v * 100.0);
            final StringBuilder sb = new StringBuilder(8);
            if (cents < 0L) {
                sb.append('-');
            }
            final long abs = Math.abs(cents);
            sb.append(abs / 100L).append('.');
            final long frac = abs % 100L;
            if (frac < 10L) {
                sb.append('0');
            }
            sb.append(frac);
            return sb.toString();
        }
    }

    /**
     * 一帧的<b>打分明细</b>（离线核账用：把"6.25 是怎么来的"逐项摊开）。
     *
     * <p>生产路径不打印它（日志里只放 {@code scores=[…]} 总分），但离线自检会逐帧打表，
     * 这样"为什么是它"是可复核的，而不是只有一句结论。
     */
    public static final class Explained {

        /** {@code 类#方法:行}。 */
        public final String frame;
        public final double score;
        public final int dist;
        public final int callers;
        public final boolean strength;
        public final boolean tool;
        public final boolean shortBody;
        public final boolean pureForward;
        public final boolean cond2;
        public final boolean eligible;
        public final String body;

        /** 🆕 降权原因（{@code ""} = 没被降权）。 */
        public final String demote;

        /** 🆕 是不是写入点自己（{@code true} ⇒ 被 {@code skip=is_write_frame} 跳过）。 */
        public final boolean isWrite;

        Explained(String frame, Score s) {
            this.frame = frame;
            this.score = s.score;
            this.dist = s.dist;
            this.callers = s.callers;
            this.strength = s.strength;
            this.tool = s.tool;
            this.shortBody = s.shortBody;
            this.pureForward = s.pureForward;
            this.cond2 = s.cond2;
            this.eligible = s.eligible();
            this.body = s.bodyBrief;
            this.demote = s.demote;
            this.isWrite = s.isWrite;
        }

        @Override
        public String toString() {
            return frame + " score=" + Score.fmt(score)
                    + " dist=" + dist + "(+" + Score.fmt(distPoints(dist)) + ")"
                    + " callers=" + callers + "(+" + Score.fmt(callers <= 1 ? 3.0 : callers == 2 ? 1.5 : 0.0) + ")"
                    + " strength=" + strength + "(+" + Score.fmt(strength ? 2.0 : 0.0) + ")"
                    + " tool=" + tool + "(-" + Score.fmt(tool ? 2.0 : 0.0) + ")"
                    + " short=" + shortBody + "(-" + Score.fmt(shortBody ? 1.0 : 0.0) + ")"
                    + " pure_forward=" + pureForward
                    + (demote.isEmpty() ? "" : " demote=" + demote)
                    + (isWrite ? " skip=" + SKIP_IS_WRITE : "")
                    + " cond2=" + cond2 + " eligible=" + eligible
                    + " body[" + body + ']';
        }
    }

    /**
     * 帧距这一项值多少分（{@code 3.0 / (dist + 1)}：dist=1 ⇒ 1.50、dist=2 ⇒ 1.00、dist=9 ⇒ 0.30）。
     *
     * <p>为什么不再是 {@code 3.0 / dist}：写入点旁那一帧（dist=1）会直接拿满 3.0，
     * 而"写入点自己"通常是分发器/转发壳 ⇒ 谁离得最近谁赢，正好把最不可靠的信号当成了一票定胜负。
     * 分母加 1 之后：① 写入点旁只值 1.50；② 这一项恒 ≤ {@link #DIST_POINTS_MAX}；
     * ③ 曲线形状不变（还是 1/x）⇒ 其余帧的相对顺序一个字不动。取舍理由见 {@link #DIST_POINTS_MAX}。
     */
    static double distPoints(int dist) {
        final int d = Math.max(1, dist);
        return DIST_POINTS_MAX / (double) (d + DIST_DENOM_OFFSET);
    }

    /** 算一帧的得分（<b>规则全部写死在这里，便于逐条核</b>）。 */
    private static Score scoreOf(List<HotfixTargetResolver.Frame> frames, HotfixTargetResolver.Frame f,
                                 boolean[] external, int idx, int writeIdx, BodyProbe body,
                                 ClassBytes bytes) {
        final boolean ext = f != null && external[idx];
        double score = 0.0;

        // 帧距（饱和式，见 distPoints）：帧距 = 距写入点几帧（写入点旁 = 1）
        final int dist = writeIdx < 0 ? 1 : Math.max(1, idx - writeIdx + 1);
        score += distPoints(dist);

        // +3.0 × 调用面窄：1 个上游 = 满；2 个 = 一半；≥3 = 0
        final int callers = LEDGER.callerCount(f);
        if (callers <= 1) {
            score += 3.0;
        } else if (callers == 2) {
            score += 1.5;
        }

        // 方法体信号（拿不到 ⇒ 中立）
        BodyInfo bi = BodyInfo.UNKNOWN;
        if (body != null && f != null) {
            try {
                final BodyInfo got = body.bodyOf(f.className, f.methodName);
                if (got != null) {
                    bi = got;
                }
            } catch (Throwable ignored) {
                bi = BodyInfo.UNKNOWN;   // 读不到字节 ⇒ 中立，绝不当成证据
            }
        }
        // 🆕 2026-11-21：再补一个"不同接收者类型数"（javap 读数）—— 分发器那条更硬的判据要用它。
        //   ⚠️ 只在 BodyInfo 已经读到时才去读字节：{@code known=false} 说明这个类的字节本来就拿不到，
        //      再走一趟磁盘只是白花时间，结论仍然是"按名字判"。
        if (bi.known && f != null) {
            bi = bi.withReceiverKinds(receiverKindsOf(f.className, f.methodName, bytes));
        }
        if (bi.strengthSignal()) {
            score += 2.0;
        }
        // 降权：工具类（原判据）＋ 分发器（🆕 2026-11-21，同一档 -2.0）
        final String demote = LEDGER.demoteReason(f, bi);
        if (!demote.isEmpty()) {
            score -= 2.0;
        }
        // {@code tool=} 这个字段仍然只表示"旧口径的工具类特征"（名字/多上游）；
        // 分发器单独写 {@code demote=dispatcher}（两件事在日志里要能分开看）。
        final boolean toolish = !demote.isEmpty() && !DEMOTE_DISPATCHER.equals(demote);
        if (bi.known && bi.insns < 15) {
            score -= 1.0;
        }

        // 硬条件 2：紧邻下方那一帧若是外部帧、且属于【另一个顶层包】⇒ 这一帧是被转发进来的中间人
        boolean cond2 = true;
        if (idx + 1 < external.length && external[idx + 1]) {
            final HotfixTargetResolver.Frame below = frames.get(idx + 1);
            if (below != null && f != null) {
                cond2 = ownerOf(f.className).equals(ownerOf(below.className));
            }
        }
        // 硬条件 3：纯转发壳
        final boolean cond3 = !bi.pureForward();
        // 🆕 2026-11-21：写入点自己 ⇒ 不算发起者（`eligible()` 里再兜一次）
        final boolean isWrite = writeIdx >= 0 && idx == writeIdx;
        return new Score(score, ext, cond2, cond3, dist, callers, bi.strengthSignal(),
                toolish, bi.known && bi.insns < 15, bi.pureForward(), bi.brief(), demote, isWrite);
    }

    /**
     * 🆕 2026-11-20：<b>模糊时"双改"的第二帧</b>（三条判据缺一条就返回 {@code -1}＝保持单目标）。
     *
     * <p>用户拍板："不要赌这个宽度"—— 实测表里 {@code #executeKill:819=5.75} 与
     * {@code #swipeStep:553=5.60} 只差 {@code 0.15}，判据是"<b>结构上不该靠 0.15 的分差决定改谁</b>"。
     *
     * <ol>
     *   <li><b>分差 &lt; 0.5</b>（{@link #AMBIGUOUS_GAP}）；</li>
     *   <li><b>同一个 owner</b>（{@link #ownerOf} 相同）⇒ 不许跨 mod 双改；</li>
     *   <li>{@code top2} 恰是 {@code top1} 的<b>直接调用者</b> ⇒ 栈上就是下一帧、链里也相邻
     *       （"中间隔了一帧"就不算：那一帧才是 top1 的调用者）。</li>
     * </ol>
     *
     * @return 第二帧在 {@code frames} 里的下标；{@code -1} = 不并列（保持现在的单目标行为）
     */
    private static int ambiguousPairIdx(List<Integer> chain, Score[] scores, int bestIdx, double bestScore,
                                        List<HotfixTargetResolver.Frame> frames) {
        if (bestIdx < 0 || chain == null || scores == null || frames == null || chain.size() < 2) {
            return -1;                  // 一个合格帧都没有／只有一个 ⇒ 没什么可分不清的
        }
        int top1Pos = -1;
        for (int c = 0; c < chain.size(); c++) {
            if (chain.get(c).intValue() == bestIdx) {
                top1Pos = c;
                break;
            }
        }
        if (top1Pos < 0) {
            return -1;
        }
        // top2 = 剩下那些【合格】帧里分数最高的（不合格的帧本来就不许改，不能因为"分差小"把它拉进来）
        int secondPos = -1;
        double secondScore = Double.NEGATIVE_INFINITY;
        for (int c = 0; c < chain.size(); c++) {
            if (c == top1Pos) {
                continue;
            }
            final Score s = scores[c];
            if (s == null || !s.eligible()) {
                continue;
            }
            if (s.score > secondScore) {
                secondScore = s.score;
                secondPos = c;
            }
        }
        if (secondPos < 0) {
            return -1;
        }
        // ---- 判据 ①：分差 < 0.5
        if (!(bestScore - secondScore < AMBIGUOUS_GAP)) {
            return -1;
        }
        final int secondIdx = chain.get(secondPos).intValue();
        // ---- 判据 ③（先做，纯位置判断）：top2 必须是 top1 的直接调用者
        //   栈顶在前 ⇒ top1 的调用者就是下一帧；链里也必须是紧挨着的下一个元素。
        if (secondIdx != bestIdx + 1
                || top1Pos + 1 >= chain.size()
                || chain.get(top1Pos + 1).intValue() != secondIdx) {
            return -1;
        }
        // ---- 判据 ②：同一个顶层包（不许跨 mod 双改）
        final HotfixTargetResolver.Frame top1 = frames.get(bestIdx);
        final HotfixTargetResolver.Frame top2 = frames.get(secondIdx);
        if (top1 == null || top2 == null || !ownerOf(top1.className).equals(ownerOf(top2.className))) {
            return -1;
        }
        return secondIdx;
    }

    /**
     * 没有外部帧可挑时的结论（<b>三级证据的 ②③④</b>）。
     *
     * <p>🔴 码不许混：后果事件（{@code die}/{@code remove}）→ 账本；
     * 其它事件保留老的 {@link #GATE_ALL_WHITELISTED}／{@link #GATE_NO_FOREIGN}
     * （对"全程只有原版帧且无账目"仍是<b>正确</b>拒绝）。
     */
    private static Result noChain(String where, String player, int skipped, int notForeign,
                                  String lastDetail, long nowMillis, int pruned) {
        if (HotfixTrigger.isConsequence(where)) {
            final List<Cause> cands = CAUSES.recent(player);
            // ---- ② cause_id 精确匹配（同 target ＋ 未结算）
            final Cause exact = CAUSES.matchPending(player);
            if (exact != null) {
                // ⚠️ die 只读不消费；remove 才结算（见 Pick#causeSettled 的注释）
                final Cause settled = isTerminal(where) ? CAUSES.settle(player) : null;
                final Pick pick = causePick(exact, EVIDENCE_LEDGER_STACK, REASON_CAUSE,
                        skipped, notForeign, nowMillis, settled != null);
                if (pick != null) {
                    return new Result(pick, "", "", skipped, notForeign, "ledger", cands, pruned, null);
                }
            }
            // ---- ③ 时间窗兜底（带 age）
            final Cause window = CAUSES.timewindow(player, nowMillis, TIMEWINDOW_TICKS);
            if (window != null) {
                final Cause settled = isTerminal(where) ? CAUSES.settle(player) : null;
                final Pick pick = causePick(window, EVIDENCE_TIMEWINDOW, REASON_TIMEWINDOW,
                        skipped, notForeign, nowMillis, settled != null);
                if (pick != null) {
                    return new Result(pick, "", "", skipped, notForeign, "ledger", cands, pruned, null);
                }
            }
            // ---- ④ 什么都没有
            return new Result(null, DETAIL_NO_ORIGIN, "all_framework", skipped, notForeign,
                    "none", cands, pruned, null);
        }
        if (notForeign > 0) {
            return new Result(null, GATE_NO_FOREIGN, lastDetail == null || lastDetail.isEmpty()
                    ? "no_code_source_no_signal" : lastDetail, skipped, notForeign, "none",
                    new ArrayList<Cause>(0), pruned, null);
        }
        return new Result(null, GATE_ALL_WHITELISTED, "", skipped, 0, "none",
                new ArrayList<Cause>(0), pruned, null);
    }

    /** 把一条账目翻成 Pick（候选串解析不出来 ⇒ {@code null}）。 */
    private static Pick causePick(Cause c, String evidence, String reason, int skipped, int notForeign,
                                  long nowMillis, boolean settled) {
        if (c == null) {
            return null;
        }
        final HotfixTargetResolver.Frame f = HotfixTargetResolver.parseFrame(c.action);
        if (f == null) {
            return null;
        }
        final long age = nowMillis - c.atMillis;
        return new Pick(f, "ledger", c.chainDepth, c.entry, c.action, c.action, f.brief(),
                reason, evidence, c.causeId, settled, ageTicks(age), "", skipped, notForeign, -1, "?",
                null, 0.0, "?", null, "");
    }

    /**
     * 在<b>写入那一刻</b>把这条链记进 pending 表（＝"完整攻击链"的载体）。
     *
     * @param player 目标玩家
     * @param field  写入点类别（只收 {@code health}/{@code hurt}，见 {@link HotfixTrigger#isCause}）
     * @param pick   这一次的选靶结论（要 action/entry/chain）
     * @param frames 这次栈的帧表（拿前 10 帧存进账本，供事后复盘）
     * @param nowMillis 现在
     * @return 新账目的 {@code cause_id}；{@code 0} = 没记（不是写作类事件／没有动作锚）
     */
    public static long noteWrite(String player, String field, Pick pick,
                                 List<HotfixTargetResolver.Frame> frames, long nowMillis) {
        if (pick == null || pick.fromLedger() || REASON_BEST_EFFORT.equals(pick.reason)) {
            return 0L;      // 三硬条件全灭 ⇒ 只是"最好的猜测"，不进账本（免得污染延迟因果）
        }
        if (pick.action == null || "none".equals(pick.action)) {
            return 0L;
        }
        return CAUSES.open(player, field, pick.action, pick.entry, pick.depth, pick.chainOwner,
                chainText(pick, frames), nowMillis);
    }

    /** 账本里的链摘要（从写入点往上，最多 10 帧）。 */
    private static String chainText(Pick pick, List<HotfixTargetResolver.Frame> frames) {
        if (frames == null || frames.isEmpty()) {
            return "";
        }
        final int startIdx = pick != null && pick.writeIndex >= 0 ? pick.writeIndex : 0;
        final StringBuilder sb = new StringBuilder(256);
        int n = 0;
        for (int i = startIdx; i < frames.size() && n < MAX_CHAIN_FRAMES; i++) {
            final HotfixTargetResolver.Frame f = frames.get(i);
            if (f == null) {
                continue;
            }
            if (n > 0) {
                sb.append(FRAME_SEP);
            }
            sb.append(f.brief());
            n++;
        }
        return sb.toString();
    }

    // ═══════════════════════════════ 日志行（纯函数） ═══════════════════════════════

    /** {@code chain_owner=…} 摘要行的闸门（每 5 秒最多 1 行）。 */
    public static boolean allowTriageLog(long nowMillis) {
        if (lastTriageLogMillis > 0L && nowMillis - lastTriageLogMillis < LOG_INTERVAL_MILLIS) {
            return false;
        }
        lastTriageLogMillis = nowMillis;
        return true;
    }

    /** {@code motion=evidence_only} 行的闸门（单独一把）。 */
    public static boolean allowEvidenceLog(long nowMillis) {
        if (lastEvidenceLogMillis > 0L && nowMillis - lastEvidenceLogMillis < LOG_INTERVAL_MILLIS) {
            return false;
        }
        lastEvidenceLogMillis = nowMillis;
        return true;
    }

    /** {@code refused detail=no_origin_candidate} 的闸门（同一玩家 10 s 一行；用户口径：必须罕见）。 */
    public static boolean allowNoOriginLog(String player, long nowMillis) {
        final String key = player == null || player.isEmpty() ? "*" : player;
        synchronized (NO_ORIGIN_LAST) {
            final Long last = NO_ORIGIN_LAST.get(key);
            if (last != null && nowMillis - last.longValue() < NO_ORIGIN_LOG_INTERVAL_MILLIS) {
                return false;
            }
            if (last == null && NO_ORIGIN_LAST.size() >= MAX_NO_ORIGIN_KEYS) {
                final Iterator<String> it = NO_ORIGIN_LAST.keySet().iterator();
                if (it.hasNext()) {
                    it.remove();
                    it.next();
                    it.remove();
                }
            }
            NO_ORIGIN_LAST.put(key, Long.valueOf(nowMillis));
            return true;
        }
    }

    /**
     * 一行整链摘要（两个锚 ＋ 写入点 ＋ 选中 ＋ <b>evidence=</b> ＋ {@code cause_id=} ＋ 逐帧得分）。
     *
     * <pre>
     *   [SHANHAI-TRIAGE] chain_owner=examplemod depth=5 entry=…Hooks#serverHook:29 \
     *        action=…ExampleMob#executeKill:819 write=…ExampleHelper#writeHealth:375 \
     *        picked=…ExampleMob#executeKill:819 reason=action_anchor \
     *        skipped=[…writeHealth:375 reason=is_write_frame] demote=dispatcher demoted=[…EventBus#post:72] \
     *        evidence=stack cause_id=17 scores=[…=6.80,…]
     * </pre>
     *
     * <p>🆕 2026-11-21 新增两段（都只在有内容时出现）：
     * {@code skipped=[<写入点帧> reason=is_write_frame]} 与
     * {@code demote=dispatcher demoted=[<被降权的帧>,…]}。
     */
    public static String triageLine(Pick pick, String where, long causeId) {
        if (pick == null) {
            return PREFIX + " chain_owner=none depth=0 entry=none action=none write=none picked=none"
                    + " reason=" + token(where) + " evidence=" + EVIDENCE_NONE;
        }
        final StringBuilder sb = new StringBuilder(360);
        sb.append(PREFIX)
                .append(" chain_owner=").append(token(pick.chainOwner))
                .append(" depth=").append(pick.depth)
                .append(" entry=").append(token(pick.entry))
                .append(" action=").append(token(pick.action))
                .append(" write=").append(token(pick.write))
                .append(" picked=").append(token(pick.picked))
                .append(" reason=").append(token(pick.reason));
        // 🆕 2026-11-20：模糊相邻对 ⇒ 紧跟在 reason= 后面写清"是哪两帧、差多少"
        //   （单目标时这一段一个字都不出现 ⇒ 老日志逐字不变）
        if (pick.secondFrame != null) {
            sb.append(" frames=").append(pick.targetCount())
                    .append(" pair=").append(token(pick.picked)).append('|')
                    .append(token(pick.secondFrame.brief()))
                    .append(" gap=").append(Score.fmt(pick.pairGap));
        }
        // 🆕 2026-11-21：写入点自己"分最高但被结构判据跳过" —— 必须一眼看得见
        if (!pick.skippedWrite.isEmpty()) {
            sb.append(" skipped=[").append(token(pick.skippedWrite))
                    .append(" reason=").append(SKIP_IS_WRITE).append(']');
        }
        // 🆕 2026-11-21：分发器降权（-2.0，与工具类同档）
        if (!pick.demotedFrames.isEmpty()) {
            sb.append(" demote=").append(DEMOTE_DISPATCHER)
                    .append(" demoted=[").append(token(pick.demotedFrames)).append(']');
        }
        sb.append(" evidence=").append(token(pick.evidence));
        final long id = pick.causeId != 0L ? pick.causeId : causeId;
        if (id != 0L) {
            sb.append(" cause_id=").append(id);
        }
        if (where != null && !where.isEmpty()) {
            sb.append(" where=").append(token(where));
        }
        if (pick.ageTicks >= 0) {
            sb.append(" age=").append(pick.ageTicks);
        }
        if (!pick.scoreTable.isEmpty()) {
            sb.append(" scores=[").append(pick.scoreTable).append(']');
        }
        return sb.toString();
    }

    /** 首次命中某条链时的一行完整链（每条链只打一次）。 */
    public static String firstSeenLine(Pick pick, String where, List<HotfixTargetResolver.Frame> frames) {
        if (pick == null || pick.fromLedger() || frames == null || frames.isEmpty()) {
            return null;
        }
        final String key = (where == null ? "?" : where) + '|' + pick.chainOwner;
        synchronized (FIRST_SEEN) {
            if (!FIRST_SEEN.add(key)) {
                return null;
            }
            if (FIRST_SEEN.size() > MAX_FIRST_KEYS) {
                final Iterator<String> it = FIRST_SEEN.iterator();
                if (it.hasNext()) {
                    it.next();
                    it.remove();
                }
            }
        }
        final int startIdx = pick.writeIndex >= 0 ? pick.writeIndex : 0;
        final StringBuilder sb = new StringBuilder(384);
        sb.append(PREFIX)
                .append(" chain_first where=").append(token(where))
                .append(" owner=").append(token(pick.chainOwner))
                .append(" frames=[");
        int n = 0;
        for (int i = startIdx; i < frames.size() && n < MAX_CHAIN_FRAMES; i++) {
            final HotfixTargetResolver.Frame f = frames.get(i);
            if (f == null) {
                continue;
            }
            if (n > 0) {
                sb.append(FRAME_SEP);
            }
            sb.append(f.brief());
            n++;
        }
        sb.append(']');
        return sb.toString();
    }

    /**
     * {@code consequence=…}：<b>{@code evidence=} 必须出现</b>（用户靠这一栏判"现场抓的还是猜的"）。
     *
     * <pre>
     *   [SHANHAI-TRIAGE] consequence=remove picked=…ExampleMob#executeKill:819 evidence=ledger_stack \
     *        cause_id=17 age=1 entry=…Hooks#serverHook:29 action=… chain_depth=5
     * </pre>
     */
    public static String consequenceLine(String where, String player, Pick pick, String evidence,
                                         long causeId, boolean settled, int ageTicks, int chainDepth) {
        final StringBuilder sb = new StringBuilder(256);
        sb.append(PREFIX)
                .append(" consequence=").append(token(where))
                .append(" picked=").append(token(pick == null ? "none" : pick.picked))
                .append(" evidence=").append(token(evidence))
                .append(" cause_id=").append(causeId)
                .append(" settled=").append(settled ? 1 : 0)
                .append(" age=").append(ageTicks)
                .append(" entry=").append(token(pick == null ? "none" : pick.entry))
                .append(" action=").append(token(pick == null ? "none" : pick.action))
                .append(" chain_depth=").append(chainDepth)
                .append(" target=").append(token(player));
        return sb.toString();
    }

    /**
     * {@code refused detail=no_origin_candidate}：<b>账本空、栈全框架</b>时的唯一写法。
     *
     * <pre>
     *   [SHANHAI-TRIAGE] refused detail=no_origin_candidate stack=all_framework age_window=100t
     * </pre>
     */
    public static String noOriginLine(String where, String player) {
        return PREFIX + " refused detail=" + DETAIL_NO_ORIGIN
                + " stack=all_framework age_window=" + TIMEWINDOW_TICKS + "t"
                + " where=" + token(where) + " target=" + token(player);
    }

    /** {@code cause_expired}：过期的 pending 账目（不静默丢弃）。 */
    public static String expiredLine(int count) {
        return PREFIX + " cause_expired count=" + count + " max_age=" + PENDING_MAX_TICKS + "t";
    }

    /**
     * {@code motion=evidence_only}：<b>motion 触发源降级为"证据，不参与选靶"</b>的唯一口径。
     *
     * <p>⚠️ 降级<b>不等于</b>删掉取证：这条违规照样以 {@code where=motion} 记进
     * {@code violation_stack}，照样进探针账本；只是不再产生 patch 目标。
     */
    public static String evidenceOnlyLine(String where, int frames) {
        return PREFIX + " motion=evidence_only where=" + token(where)
                + " frames=" + frames + " picked=none reason=evidence_only evidence=" + EVIDENCE_NONE;
    }

    // ═══════════════════════════════ 包前缀 / 工具类 ═══════════════════════════════

    /** 一帧所属的<b>顶层包前缀</b>（"这条链的主人"）。 */
    public static String ownerOf(String className) {
        if (className == null || className.isEmpty()) {
            return "none";
        }
        final String[] parts = className.split("\\.");
        for (int i = 0; i < parts.length - 1; i++) {
            final String p = parts[i];
            if (p == null || p.isEmpty() || isContainerSegment(p)) {
                continue;
            }
            return p;
        }
        return parts[0].isEmpty() ? "none" : parts[0];
    }

    private static boolean isContainerSegment(String segment) {
        final String low = segment.toLowerCase(Locale.ROOT);
        for (int i = 0; i < CONTAINER_SEGMENTS.length; i++) {
            if (CONTAINER_SEGMENTS[i].equals(low)) {
                return true;
            }
        }
        return false;
    }

    /** 类名里是不是带"工具类特征"的段（{@code util}/{@code helper}/{@code api}/…）。 */
    public static boolean isUtilityName(String className) {
        if (className == null || className.isEmpty()) {
            return false;
        }
        final String[] parts = className.split("\\.");
        for (int i = 0; i < parts.length; i++) {
            final String low = parts[i].toLowerCase(Locale.ROOT);
            for (int k = 0; k < UTILITY_SEGMENTS.length; k++) {
                if (UTILITY_SEGMENTS[k].equals(low)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 类的<b>末段</b>（{@code a.b.C} ⇒ {@code C}；{@code null}/空 ⇒ {@code ""}）。 */
    static String simpleName(String className) {
        if (className == null || className.isEmpty()) {
            return "";
        }
        final int dot = className.lastIndexOf('.');
        return dot < 0 ? className : className.substring(dot + 1);
    }

    /**
     * 🆕 2026-11-21：<b>按名字</b>判"分发器"（类名<b>包含</b>类型词，或方法名<b>包含</b>类型词，
     * 或方法名<b>以</b>动词前缀<b>开头</b>；全部忽略大小写）。
     *
     * <p>⚠️ 只看<b>类名末段</b>（{@code simpleName}）：包名里出现 {@code bus}/{@code proxy} 不代表这个类是分发器。
     * ⚠️ 这是<b>兜底判据</b>：读得到方法体时以 {@link #isDispatcher} 的"接收者类型数"为准
     * （名字像 Bus 但方法体只发给一个对象 ⇒ <b>不降权</b>）。
     */
    public static boolean isDispatcherName(String className, String methodName) {
        final String cls = simpleName(className).toLowerCase(Locale.ROOT);
        final String mth = methodName == null ? "" : methodName.toLowerCase(Locale.ROOT);
        if (cls.isEmpty() && mth.isEmpty()) {
            return false;
        }
        for (int i = 0; i < DISPATCH_TYPE_WORDS.length; i++) {
            final String w = DISPATCH_TYPE_WORDS[i];
            if (cls.contains(w) || mth.contains(w)) {
                return true;
            }
        }
        for (int i = 0; i < DISPATCH_VERB_PREFIXES.length; i++) {
            if (mth.startsWith(DISPATCH_VERB_PREFIXES[i])) {
                return true;
            }
        }
        return false;
    }

    /**
     * 🆕 2026-11-21：这一帧是不是<b>分发器</b>（＝同一件事被分发给多个不同类型的对象）。
     *
     * <p>🔴 <b>方法体那条优先于名字那条</b>：读得到"不同接收者类型数"时，只有 {@code >= 2} 才算分发器
     * （结构证据赢过命名习惯）；读不到才退回 {@link #isDispatcherName}。
     *
     * @param body 方法体读数（{@code null} 或 {@code receiverKinds < 0} ⇒ 按名字判）
     */
    public static boolean isDispatcher(HotfixTargetResolver.Frame f, BodyInfo body) {
        if (f == null) {
            return false;
        }
        if (body != null && body.receiverKnown()) {
            return body.dispatchByBody();
        }
        return isDispatcherName(f.className, f.methodName);
    }

    // ═══════════════════════════════ 分发器：不同接收者类型数 ═══════════════════════════════

    /** {@code 类#方法} ⇒ 不同接收者类型数（{@code -1} = 读不到；缓存上限见 {@link #MAX_RECEIVER_KEYS}）。 */
    private static final Map<String, Integer> RECEIVER_KINDS = new LinkedHashMap<>();

    private static final int MAX_RECEIVER_KEYS = 256;

    /**
     * 数一帧对应方法的<b>不同接收者类型数</b>（{@code invokeinterface}/{@code invokevirtual} 的 owner 去重）。
     *
     * @return {@code >=0} = 数出来了；{@link BodyInfo#RECEIVER_KINDS_UNKNOWN} = 拿不到字节／解析不了
     */
    public static int receiverKindsOf(String className, String methodName, ClassBytes bytes) {
        if (className == null || className.isEmpty() || methodName == null || methodName.isEmpty()
                || bytes == null) {
            return BodyInfo.RECEIVER_KINDS_UNKNOWN;
        }
        final String key = className + '#' + methodName;
        synchronized (RECEIVER_KINDS) {
            final Integer hit = RECEIVER_KINDS.get(key);
            if (hit != null) {
                return hit.intValue();
            }
        }
        int kinds = BodyInfo.RECEIVER_KINDS_UNKNOWN;
        try {
            final byte[] raw = bytes.bytesOf(className);
            if (raw != null && raw.length > 16) {
                kinds = dispatchScan(raw, methodName)[1];
            }
        } catch (Throwable t) {
            kinds = BodyInfo.RECEIVER_KINDS_UNKNOWN;   // 读不到 ⇒ 退回按名字判，绝不猜
        }
        synchronized (RECEIVER_KINDS) {
            if (RECEIVER_KINDS.size() >= MAX_RECEIVER_KEYS) {
                final Iterator<String> it = RECEIVER_KINDS.keySet().iterator();
                int drop = MAX_RECEIVER_KEYS / 2;
                while (it.hasNext() && drop-- > 0) {
                    it.next();
                    it.remove();
                }
            }
            RECEIVER_KINDS.put(key, Integer.valueOf(kinds));
        }
        return kinds;
    }

    /** 清缓存（离线用例之间／命令重来）。 */
    public static void resetReceiverCache() {
        synchronized (RECEIVER_KINDS) {
            RECEIVER_KINDS.clear();
        }
    }

    /**
     * 🆕 2026-11-21：只走一趟字节，同时给 {@code {指令数, 不同接收者类型数}}。
     *
     * <p>为什么两个数一起给：离线自检拿"指令数"跟 {@link HotfixBodyProbe.BodyInfo#insns} 交叉核对
     * —— 两个<b>独立实现</b>的指令长度表算出来必须一样，否则就说明这一份走字节的代码坏了
     * （本类的 opcode 表是从 {@code HotfixBodyProbe} 抄的，那边的实现不在本轮改动面里）。
     *
     * @return {@code int[]{insns, receiverKinds}}；读不到 ⇒ {@code {-1, -1}}
     */
    public static int[] dispatchScan(byte[] bytes, String methodName) {
        final int[] none = {-1, -1};
        if (bytes == null || bytes.length < 16 || methodName == null || methodName.isEmpty()) {
            return none;
        }
        try {
            final java.io.DataInputStream in = new java.io.DataInputStream(
                    new java.io.ByteArrayInputStream(bytes));
            if (in.readInt() != 0xCAFEBABE) {
                return none;
            }
            in.readUnsignedShort();                          // minor
            in.readUnsignedShort();                          // major
            final int cpCount = in.readUnsignedShort();
            final int[] tags = new int[cpCount];
            final int[] ref = new int[cpCount];              // 7 → name_index；10/11 → class_index
            final String[] utf8 = new String[cpCount];
            for (int i = 1; i < cpCount; i++) {
                final int tag = in.readUnsignedByte();
                tags[i] = tag;
                switch (tag) {
                    case 1:                                   // Utf8
                        utf8[i] = in.readUTF();
                        break;
                    case 3:                                   // Integer
                    case 4:                                   // Float
                        in.skipBytes(4);
                        break;
                    case 5:                                   // Long
                    case 6:                                   // Double
                        in.skipBytes(8);
                        i++;                                  // 占两个槽
                        break;
                    case 7:                                   // Class
                        ref[i] = in.readUnsignedShort();
                        break;
                    case 8:                                   // String
                    case 16:                                  // MethodType
                    case 19:                                  // Module
                    case 20:                                  // Package
                        in.skipBytes(2);
                        break;
                    case 15:                                  // MethodHandle
                        in.skipBytes(3);
                        break;
                    case 9:                                   // Fieldref
                    case 10:                                  // Methodref
                    case 11:                                  // InterfaceMethodref
                    case 12:                                  // NameAndType
                    case 17:                                  // Dynamic
                    case 18:                                  // InvokeDynamic
                        ref[i] = in.readUnsignedShort();
                        in.skipBytes(2);
                        break;
                    default:
                        return none;                          // 不认识的常量池项 ⇒ 不猜
                }
            }
            in.readUnsignedShort();                           // access_flags
            in.readUnsignedShort();                           // this_class
            in.readUnsignedShort();                           // super_class
            final int interfaces = in.readUnsignedShort();
            in.skipBytes(2 * interfaces);
            skipMemberArray(in);                              // fields
            final int methods = in.readUnsignedShort();
            int bestInsns = -1;
            int bestKinds = -1;
            for (int m = 0; m < methods; m++) {
                in.readUnsignedShort();                       // access
                final String name = atUtf8(in, utf8);
                atUtf8(in, utf8);                             // desc
                final int attrs = in.readUnsignedShort();
                byte[] code = null;
                for (int a = 0; a < attrs; a++) {
                    final String an = atUtf8(in, utf8);
                    final int len = in.readInt();
                    if (len < 0 || len > bytes.length) {
                        return none;
                    }
                    final byte[] payload = new byte[len];
                    in.readFully(payload);
                    if ("Code".equals(an) && payload.length >= 8) {
                        code = payload;                       // 同名重载取"指令数最多"的那一个
                    }
                }
                if (code == null || !methodName.equals(name)) {
                    continue;
                }
                final int[] r = scanCode(code, tags, ref, utf8);
                if (r == null) {
                    return none;
                }
                if (r[0] > bestInsns) {
                    bestInsns = r[0];
                    bestKinds = r[1];
                }
            }
            return bestInsns < 0 ? none : new int[]{bestInsns, bestKinds};
        } catch (Throwable t) {
            return none;
        }
    }

    /** 跳过一段 field_info（access/name/desc/attributes）。 */
    private static void skipMemberArray(java.io.DataInputStream in) throws java.io.IOException {
        final int count = in.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            in.skipBytes(6);
            final int attrs = in.readUnsignedShort();
            for (int a = 0; a < attrs; a++) {
                in.skipBytes(2);
                final int len = in.readInt();
                if (len < 0) {
                    throw new java.io.IOException("bad attr len");
                }
                in.skipBytes(len);
            }
        }
    }

    private static String atUtf8(java.io.DataInputStream in, String[] utf8) throws java.io.IOException {
        final int idx = in.readUnsignedShort();
        if (idx <= 0 || idx >= utf8.length) {
            return "";
        }
        final String s = utf8[idx];
        return s == null ? "" : s;
    }

    /**
     * 走一段 {@code Code} 字节：数指令数 ＋ 数"不同接收者类型数"。
     *
     * <p>接收者类型只在 {@code invokevirtual}(0xB6) 与 {@code invokeinterface}(0xB9) 上取：
     * 它们的操作数是常量池里的 {@code Methodref/InterfaceMethodref}，顺着
     * {@code Methodref → Class → Utf8} 拿到<b>声明接收者的类型名</b>。去重之后 ≥2 ⇒ 分发器。
     *
     * @return {@code {insns, receiverKinds}}；结构坏了 ⇒ {@code null}
     */
    private static int[] scanCode(byte[] payload, int[] tags, int[] ref, String[] utf8) {
        final int codeLen = ((payload[4] & 0xFF) << 24) | ((payload[5] & 0xFF) << 16)
                | ((payload[6] & 0xFF) << 8) | (payload[7] & 0xFF);
        if (codeLen <= 0 || 8 + codeLen > payload.length || codeLen > (16 << 20)) {
            return null;
        }
        final Set<String> owners = new LinkedHashSet<>();
        int insns = 0;
        int pos = 8;
        final int start = 8;                  // code 数组从 payload[8] 开始
        final int end = 8 + codeLen;
        while (pos < end) {
            final int op = payload[pos] & 0xFF;
            insns++;
            if ((op == 0xB6 || op == 0xB9) && pos + 2 < end) {
                final int cp = ((payload[pos + 1] & 0xFF) << 8) | (payload[pos + 2] & 0xFF);
                final String owner = ownerAt(cp, tags, ref, utf8);
                if (owner != null && !owner.isEmpty()) {
                    owners.add(owner);
                }
            }
            final int adv = insnLength(payload, pos, start, op, end);
            if (adv <= 0) {
                return null;
            }
            pos += adv;
        }
        return new int[]{insns, owners.size()};
    }

    /** 常量池下标 ⇒ 那个成员的<b>声明接收者类型</b>（名字里的 {@code /} 换成 {@code .}）。 */
    private static String ownerAt(int cp, int[] tags, int[] ref, String[] utf8) {
        if (cp <= 0 || cp >= tags.length) {
            return null;
        }
        final int tag = tags[cp];
        if (tag != 10 && tag != 11) {              // Methodref / InterfaceMethodref
            return null;
        }
        final int cls = ref[cp];
        if (cls <= 0 || cls >= tags.length || tags[cls] != 7) {
            return null;
        }
        final int nameIdx = ref[cls];
        if (nameIdx <= 0 || nameIdx >= utf8.length || utf8[nameIdx] == null) {
            return null;
        }
        return utf8[nameIdx].replace('/', '.');
    }

    /**
     * 一条指令占几个字节（{@code tableswitch/lookupswitch/wide} 单独算对齐）。
     *
     * <p>🔴 {@code tableswitch/lookupswitch} 的 {@code pad} 是相对 <b>code 数组起点</b> 对齐的，
     * 不是相对"这段字节在文件里的位置"：{@code start} 就是 code 数组在 {@code payload} 里的偏移。
     * （这一条算错，后面整条方法体的指令数就全乱了 —— 离线用例拿 {@code ExampleOther#dense/#sparse}
     * 这两个 {@code switch} 方法盯着它。）
     */
    private static int insnLength(byte[] code, int pos, int start, int op, int end) {
        final int codeOff = pos - start;
        if (op == 0xAA) {                          // tableswitch
            final int pad = (4 - ((codeOff + 1) % 4)) % 4;
            final int p = pos + 1 + pad;
            if (p + 12 > end) {
                return -1;
            }
            final long n = (long) readInt(code, p + 8) - (long) readInt(code, p + 4) + 1L;
            if (n < 0L || n > 65536L) {
                return -1;
            }
            return 1 + pad + 12 + (int) n * 4;
        }
        if (op == 0xAB) {                          // lookupswitch
            final int pad = (4 - ((codeOff + 1) % 4)) % 4;
            final int p = pos + 1 + pad;
            if (p + 8 > end) {
                return -1;
            }
            final int pairs = readInt(code, p + 4);
            if (pairs < 0 || pairs > 65536) {
                return -1;
            }
            return 1 + pad + 8 + pairs * 8;
        }
        if (op == 0xC4) {                          // wide
            if (pos + 1 >= end) {
                return -1;
            }
            return (code[pos + 1] & 0xFF) == 0x84 ? 6 : 4;
        }
        final int adv = INSN_LEN[op] & 0xFF;
        return pos + adv > end ? -1 : adv;
    }

    private static int readInt(byte[] b, int p) {
        return ((b[p] & 0xFF) << 24) | ((b[p + 1] & 0xFF) << 16) | ((b[p + 2] & 0xFF) << 8)
                | (b[p + 3] & 0xFF);
    }

    /** 每个 opcode 的字节长度（{@code tableswitch/lookupswitch/wide} 走 {@link #insnLength}）。 */
    private static final byte[] INSN_LEN = new byte[256];

    static {
        for (int i = 0; i < INSN_LEN.length; i++) {
            INSN_LEN[i] = 1;
        }
        final int[] two = {
                0x10, 0x12, 0x15, 0x16, 0x17, 0x18, 0x19, 0x36, 0x37, 0x38, 0x39, 0x3A, 0xA9, 0xBC
        };
        for (int i = 0; i < two.length; i++) {
            INSN_LEN[two[i]] = 2;
        }
        final int[] three = {
                0x11, 0x13, 0x14, 0x84,
                0x99, 0x9A, 0x9B, 0x9C, 0x9D, 0x9E, 0x9F, 0xA0, 0xA1, 0xA2, 0xA3, 0xA4, 0xA5, 0xA6,
                0xA7, 0xA8,
                0xB2, 0xB3, 0xB4, 0xB5, 0xB6, 0xB7, 0xB8, 0xBB, 0xBD, 0xC0, 0xC1, 0xC6, 0xC7
        };
        for (int i = 0; i < three.length; i++) {
            INSN_LEN[three[i]] = 3;
        }
        final int[] five = {0xB9, 0xBA, 0xC8, 0xC9};
        for (int i = 0; i < five.length; i++) {
            INSN_LEN[five[i]] = 5;
        }
        INSN_LEN[0xC5] = 4;                        // multianewarray
    }

    /** 记一批帧（学"谁被谁用过"）；{@code null}/空 ⇒ 什么也不做。 */
    public static void observe(List<HotfixTargetResolver.Frame> frames) {
        LEDGER.observe(frames);
    }

    /**
     * <b>工具类账本</b>：从近期栈里学"某个方法/类被几个不同上游用过"。
     *
     * <p>为什么不写死黑名单：用户口径是"<b>近期</b>被 ≥2 个不同上游用过"⇒ 必须是学出来的。
     */
    public static final class ToolLedger {

        private final Map<String, Set<String>> callersOfMethod = new LinkedHashMap<>();
        private final Map<String, Set<String>> callersOfClass = new LinkedHashMap<>();
        private final AtomicLong observed = new AtomicLong();

        public long observed() {
            return observed.get();
        }

        /** 记一条栈（白名单帧跳过，免得把表撑满）。 */
        public void observe(List<HotfixTargetResolver.Frame> frames) {
            if (frames == null || frames.size() < 2) {
                return;
            }
            observed.incrementAndGet();
            synchronized (this) {
                for (int i = 0; i + 1 < frames.size(); i++) {
                    final HotfixTargetResolver.Frame f = frames.get(i);
                    final HotfixTargetResolver.Frame caller = frames.get(i + 1);
                    if (f == null || caller == null || f.className == null || f.methodName == null) {
                        continue;
                    }
                    if (HotfixPatchWhitelist.isNeverPatch(f.className)) {
                        continue;
                    }
                    final String upstream = caller.brief();
                    add(callersOfMethod, f.className + '#' + f.methodName, upstream, MAX_TOOL_KEYS);
                    add(callersOfClass, f.className, upstream, MAX_TOOL_CLASSES);
                }
            }
        }

        /** 这个方法近期被几个不同上游调过（没记过 ⇒ 0）。 */
        public synchronized int callerCount(HotfixTargetResolver.Frame f) {
            if (f == null || f.className == null || f.methodName == null) {
                return 0;
            }
            final Set<String> byMethod = callersOfMethod.get(f.className + '#' + f.methodName);
            return byMethod == null ? 0 : byMethod.size();
        }

        /** 这一帧有没有"工具类特征"。 */
        public synchronized boolean toolish(HotfixTargetResolver.Frame f) {
            return !demoteReason(f, null).isEmpty();
        }

        /**
         * 工具类降权理由（{@code ""} = 不是工具类）。
         * <ol>
         *   <li>包/类名含 {@code util|helper|api|misc|common}（用户打分表里的逐字口径）；</li>
         *   <li>🆕 2026-11-21：<b>分发器</b>（{@link HotfixChainTriage#DEMOTE_DISPATCHER}）—— 名字像总线，
         *       或方法体把同一件事派给 ≥2 个不同接收者类型（后者更硬，读得到就以它为准）；</li>
         *   <li>这个方法近期被 <b>≥2 个不同上游</b>用过（"在多条不同链上出现"的可量化版）。</li>
         * </ol>
         */
        public synchronized String demoteReason(HotfixTargetResolver.Frame f) {
            return demoteReason(f, null);
        }

        /**
         * @param body 方法体读数（{@code null}／读不到接收者类型 ⇒ 分发器判据退回"按名字判"）
         */
        public synchronized String demoteReason(HotfixTargetResolver.Frame f, BodyInfo body) {
            if (f == null || f.className == null || f.methodName == null) {
                return "";
            }
            if (isUtilityName(f.className)) {
                return "name=utility";
            }
            if (isDispatcher(f, body)) {
                return DEMOTE_DISPATCHER;
            }
            final int callers = callerCount(f);
            if (callers >= 2) {
                return "callers=" + callers;
            }
            return "";
        }

        /** 清空（新的一局／离线用例之间）。 */
        public synchronized void reset() {
            callersOfMethod.clear();
            callersOfClass.clear();
            observed.set(0L);
        }

        /** 一行读数。 */
        public synchronized String line() {
            return "tool_ledger observed=" + observed.get()
                    + " methods=" + callersOfMethod.size()
                    + " classes=" + callersOfClass.size();
        }

        private static void add(Map<String, Set<String>> map, String key, String value, int maxKeys) {
            if (key == null || value == null) {
                return;
            }
            Set<String> set = map.get(key);
            if (set == null) {
                if (map.size() >= maxKeys) {
                    final Iterator<String> it = map.keySet().iterator();
                    if (it.hasNext()) {
                        it.next();
                        it.remove();
                    }
                }
                set = new LinkedHashSet<>();
                map.put(key, set);
            }
            if (set.size() < MAX_CALLERS_PER_KEY) {
                set.add(value);      // 满了就不再记：判据只需要"≥2"
            }
        }
    }

    /** 一条写入账目（＝一次攻击的完整描述）。 */
    public static final class Cause {

        /** 唯一标识"这一次攻击"（自增）。 */
        public final long causeId;

        public final String player;

        /** 写入点类别（{@code health}/{@code hurt}）。 */
        public final String field;

        /** 写入时刻（毫秒）。 */
        public final long atMillis;

        /**
         * 写入时刻的 tick 序号（{@code atMillis / 50}）。
         * ⚠️ 这是<b>墙钟换算</b>，不是服务端 tick 计数器：热改引擎是纯 JDK、拿不到 server tick。
         * 它只用于日志与窗口换算，<b>不参与 {@code cause_id} 匹配</b>。
         */
        public final long tick;

        /** 入口锚（只记录）。 */
        public final String entry;

        /** 动作锚（后果事件该打它）。 */
        public final String action;

        /** 这条链有几个外部帧。 */
        public final int chainDepth;

        /** 链摘要（从写入点往上，最多 10 帧）。 */
        public final String chain;

        /** 这条链的主人（顶层包前缀）。 */
        public final String owner;

        /** 结算了没有（结算即从表里删；这个字段只在"刚结算"的返回值里为 true）。 */
        public boolean settled;

        Cause(long causeId, String player, String field, long atMillis, String entry, String action,
              int chainDepth, String chain, String owner) {
            this.causeId = causeId;
            this.player = player == null || player.isEmpty() ? "*" : player;
            this.field = field == null ? "?" : field;
            this.atMillis = atMillis;
            this.tick = atMillis / MILLIS_PER_TICK;
            this.entry = entry == null || entry.isEmpty() ? "none" : entry;
            this.action = action == null ? "none" : action;
            this.chainDepth = chainDepth;
            this.chain = chain == null ? "" : chain;
            this.owner = owner == null ? "none" : owner;
        }

        /** 日志短写法。 */
        public String brief() {
            return action + " entry=" + entry + " cause_id=" + causeId + " field=" + field;
        }
    }

    /**
     * <b>pending 表</b>（＝"完整攻击链"的载体）。
     *
     * <h2>结构</h2>
     * {@code {cause_id, target, field, tick, entry_anchor, action_anchor, chain[≤10], atMillis}}
     * —— 每个玩家一个列表，<b>时间倒序</b>，最多 6 条。
     *
     * <h2>何时结算</h2>
     * <ol>
     *   <li><b>后果事件把它兑现</b>：{@code die}/{@code remove} 命中它（{@link #settle}）⇒ 立刻删除；</li>
     *   <li><b>现场栈自己带着凶手</b>（{@code evidence=stack}）⇒ 也立刻删除（"这条账已经用不上了"）；</li>
     *   <li><b>被同 {@code (target, field)} 的下一次写入顶掉</b>（{@link #open} 里删旧的）；</li>
     *   <li><b>过期</b>：年龄超过 {@link HotfixChainTriage#PENDING_MAX_TICKS} ⇒ 删除并计数（调用方打
     *       {@code cause_expired}）。理由见那个常量的 javadoc。</li>
     * </ol>
     */
    public static final class CauseLedger {

        /** 每个玩家最多留几条"已结算"的历史（第三级时间窗兜底要它）。 */
        private static final int MAX_HISTORY = 4;

        private final AtomicLong seq = new AtomicLong();

        /** 未结算的 pending（{@code cause_id} 精确匹配用）。 */
        private final Map<String, List<Cause>> pending = new LinkedHashMap<>();

        /** 已经结算掉的历史（第三级时间窗兜底用；结算即从 pending 删，但在这里留一小段）。 */
        private final Map<String, List<Cause>> history = new LinkedHashMap<>();

        /** 开一条新账目（写入那一刻调）。 */
        public long open(String player, String field, String action, String entry, int chainDepth,
                         String owner, String chain, long atMillis) {
            if (!HotfixTrigger.isCause(field)) {
                return 0L;              // 只有"能改死"的写者才记账
            }
            if (action == null || action.isEmpty() || "none".equals(action)) {
                return 0L;
            }
            final String key = player == null || player.isEmpty() ? "*" : player;
            final long id = seq.incrementAndGet();
            synchronized (this) {
                List<Cause> list = pending.get(key);
                if (list == null) {
                    if (pending.size() >= MAX_PLAYERS) {
                        final Iterator<String> it = pending.keySet().iterator();
                        if (it.hasNext()) {
                            it.next();
                            it.remove();
                        }
                    }
                    list = new ArrayList<>(MAX_PER_PLAYER);
                    pending.put(key, list);
                }
                // 顶掉旧的：同一个 (target, field) 只保留最新一条未结算账目
                for (Iterator<Cause> it = list.iterator(); it.hasNext(); ) {
                    final Cause c = it.next();
                    if (c != null && c.field.equals(field)) {
                        it.remove();
                    }
                }
                list.add(0, new Cause(id, key, field, atMillis, entry, action, chainDepth, chain, owner));
                while (list.size() > MAX_PER_PLAYER) {
                    list.remove(list.size() - 1);
                }
            }
            return id;
        }

        /** 找这个玩家最近一条未结算账目（{@code null} = 没有）——<b>不删</b>。 */
        public Cause matchPending(String player) {
            final String key = player == null || player.isEmpty() ? "*" : player;
            synchronized (this) {
                final List<Cause> list = pending.get(key);
                if (list == null || list.isEmpty()) {
                    return null;
                }
                return list.get(0);
            }
        }

        /**
         * 结算这个玩家的账目：从 pending 删除，<b>但留进 history</b>（第三级兜底要用它），
         * 返回被结算的那一条（{@code null} = 没有）。
         */
        public Cause settle(String player) {
            final String key = player == null || player.isEmpty() ? "*" : player;
            synchronized (this) {
                final List<Cause> list = pending.get(key);
                if (list == null || list.isEmpty()) {
                    return null;
                }
                final Cause c = list.remove(0);
                if (c == null) {
                    return null;
                }
                c.settled = true;
                List<Cause> hist = history.get(key);
                if (hist == null) {
                    if (history.size() >= MAX_PLAYERS) {
                        final Iterator<String> it = history.keySet().iterator();
                        if (it.hasNext()) {
                            it.next();
                            it.remove();
                        }
                    }
                    hist = new ArrayList<>(MAX_HISTORY);
                    history.put(key, hist);
                }
                hist.add(0, c);
                while (hist.size() > MAX_HISTORY) {
                    hist.remove(hist.size() - 1);
                }
                return c;
            }
        }

        /**
         * 时间窗兜底（第三级）：最近 {@code windowTicks} 内<b>任何一条</b>（未结算优先，其次最新）。
         *
         * <p>它真正会命中的典型场景：{@code health} 写入 → {@code die} 把这条账结算掉了 →
         * <b>同一场死亡里紧接着的 {@code remove}</b> 已经找不到 pending 了，但写入就发生在
         * 两三拍之前 ⇒ 时间窗仍然能把它认回来（并且日志会写明 {@code reason=timewindow_fallback}）。
         */
        public Cause timewindow(String player, long nowMillis, int windowTicks) {
            final String key = player == null || player.isEmpty() ? "*" : player;
            final long windowMillis = Math.max(1, windowTicks) * MILLIS_PER_TICK;
            synchronized (this) {
                final List<Cause> list = pending.get(key);
                if (list != null) {
                    for (Iterator<Cause> it = list.iterator(); it.hasNext(); ) {
                        final Cause c = it.next();
                        if (c != null && within(c, nowMillis, windowMillis)) {
                            return c;
                        }
                    }
                }
                final List<Cause> hist = history.get(key);
                if (hist != null) {
                    for (Iterator<Cause> it = hist.iterator(); it.hasNext(); ) {
                        final Cause c = it.next();
                        if (c != null && within(c, nowMillis, windowMillis)) {
                            return c;
                        }
                    }
                }
            }
            return null;
        }

        private static boolean within(Cause c, long nowMillis, long windowMillis) {
            final long age = nowMillis - c.atMillis;
            return age >= 0L && age <= windowMillis;
        }

        /** 只读快照（日志用；pending 优先，其次 history，最多 3 条）。 */
        public List<Cause> recent(String player) {
            final String key = player == null || player.isEmpty() ? "*" : player;
            final List<Cause> out = new ArrayList<>(MAX_CANDIDATES);
            synchronized (this) {
                final List<Cause> list = pending.get(key);
                if (list != null) {
                    for (int i = 0; i < list.size() && out.size() < MAX_CANDIDATES; i++) {
                        final Cause c = list.get(i);
                        if (c != null) {
                            out.add(c);
                        }
                    }
                }
                final List<Cause> hist = history.get(key);
                if (hist != null) {
                    for (int i = 0; i < hist.size() && out.size() < MAX_CANDIDATES; i++) {
                        final Cause c = hist.get(i);
                        if (c != null) {
                            out.add(c);
                        }
                    }
                }
            }
            return out;
        }

        /** 清掉过期账目（pending ＋ history），返回清了几条（不静默：调用方应打 {@code cause_expired}）。 */
        public int prune(String player, long nowMillis) {
            final String key = player == null || player.isEmpty() ? "*" : player;
            final long maxMillis = PENDING_MAX_TICKS * MILLIS_PER_TICK;
            int removed = 0;
            synchronized (this) {
                removed += pruneList(pending.get(key), nowMillis, maxMillis);
                removed += pruneList(history.get(key), nowMillis, maxMillis);
            }
            return removed;
        }

        private static int pruneList(List<Cause> list, long nowMillis, long maxMillis) {
            if (list == null) {
                return 0;
            }
            int removed = 0;
            for (Iterator<Cause> it = list.iterator(); it.hasNext(); ) {
                final Cause c = it.next();
                if (c == null) {
                    continue;
                }
                final long age = nowMillis - c.atMillis;
                if (age < 0L || age > maxMillis) {
                    it.remove();
                    removed++;
                }
            }
            return removed;
        }

        /** 清空（新的一局／离线用例之间）。 */
        public synchronized void reset() {
            pending.clear();
            history.clear();
            seq.set(0L);
        }

        /** 一行读数。 */
        public synchronized String line() {
            int p = 0;
            int h = 0;
            for (Iterator<List<Cause>> it = pending.values().iterator(); it.hasNext(); ) {
                final List<Cause> l = it.next();
                p += l == null ? 0 : l.size();
            }
            for (Iterator<List<Cause>> it = history.values().iterator(); it.hasNext(); ) {
                final List<Cause> l = it.next();
                h += l == null ? 0 : l.size();
            }
            return "cause_ledger players=" + pending.size() + " pending=" + p + " history=" + h
                    + " seq=" + seq.get() + " max_age=" + PENDING_MAX_TICKS + "t";
        }
    }
    /**
     * 这一类后果是不是<b>终结后果</b>（{@code remove}）——只有它消费账目。
     *
     * <p>为什么 {@code die} 不算：同一场死亡通常是 {@code die → remove} 两拍，
     * 两拍都该看到同一条 {@code cause_id}。若 die 就把它删掉，remove 只能退化到时间窗，
     * 而"时间窗只当最后一级"是硬要求。
     */
    private static boolean isTerminal(String where) {
        return HotfixTrigger.REMOVE.equals(HotfixTrigger.canonical(where));
    }

    /** 墙钟毫秒 ⇒ tick（向上取整：1~49 ms 也算 1 tick，不写 0）。 */
    static int ageTicks(long ageMillis) {
        if (ageMillis <= 0L) {
            return 0;
        }
        final long ticks = (ageMillis + MILLIS_PER_TICK - 1L) / MILLIS_PER_TICK;
        return ticks > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) ticks;
    }

    /** 日志字段里不许出现空格／竖线／换行（否则 grep 会把一行切错）。 */
    static String token(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "none";
        }
        final StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            final char c = raw.charAt(i);
            if (c == ' ' || c == '\t' || c == '|' || c == '\n' || c == '\r'
                    || c == HotfixTriageVerdict.FULLWIDTH_BAR) {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
