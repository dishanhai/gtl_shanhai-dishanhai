package com.shanhai.add.hotfix;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.message.Message;

/**
 * <b>分诊订阅（最小 bridge）</b>：在 log4j 的 root logger 上挂一个 appender，
 * 把本体的 {@code [SHANHAI-TRIAGE]} 行捞出来，配对成「结论 ＋ 这一拍的调用栈」交给上层。
 *
 * <h2>为什么是"听日志"，而不是改本体</h2>
 * 本体只把分诊结论<b>写成一行日志</b>（{@code ShanhaiGuardTriageBridge.flushTick}），
 * 没有回调口子、也不打算开一个（{@code write scope} 不许动本体）。
 * 而规格要求 add 侧"从本体判 {@code solved=false} 这一拍出发"。
 * ⇒ 唯一既不改本体、又能拿到<b>本体原判据</b>的接法就是：<b>读它打出来的那两行</b>。
 * 这也顺带满足"读本体留下的登记表"这条口径 —— 那两行就是它留下的登记表。
 *
 * <h2>两条行怎么配对</h2>
 * <pre>
 *   拍内先来： [SHANHAI-TRIAGE] strike_caller#1 at=… frames=… ｜ player=Steve   ← 抓栈（每人每局最多 3 次）
 *   拍末才来： [SHANHAI-TRIAGE] solved=false r1=… r4=… src=die  ｜ player=Steve   ← 结论
 * </pre>
 * 按 {@code player=} 配对并记下抓栈时刻；上层只接受 {@code age <= }{@link #STRIKE_TTL_MILLIS} 的配对
 * —— 因为 quota 只有 3 次，二十分钟后的 {@code solved=false} 配到一个旧栈就等于改错方法。
 *
 * <h2>🔴 两个必然踩到的坑（都写在这里，免得后人重踩）</h2>
 * <ol>
 *   <li><b>别在 appender 里直接干活／直接打日志。</b>log4j 有"同一线程递归 log 会被丢弃"的机制
 *       ({@code ThreadContext} 的 recursion guard) ⇒ 在这一层里 {@code LOGGER.info(...)} 会<b>静默消失</b>。
 *       所以本类只做解析＋投递，真正动手和它那些日志都在 {@link HotfixAutoCounter} 的独立工作线程上。</li>
 *   <li><b>不是每个 log event 都值得格式化。</b>先看 logger 名（{@code com.shanhai.*}）或格式串里有没有
 *       {@code [SHANHAI}，再决定要不要 {@code getFormattedMessage()}；否则等于给整台服务器的每条日志
 *       都加一次字符串拼接。</li>
 * </ol>
 *
 * <h2>装不上会怎样</h2>
 * 返回 {@code false} 并把原因写进 {@link #lastInstallReason()}：<b>只影响"主动热改"这个可选能力</b>，
 * 不影响游戏、不影响分诊本身（分诊照旧只打日志）。
 *
 * <p>依赖面：{@code java.*} ＋ log4j（游戏自带，{@code build.gradle} 无需新增依赖 ——
 * {@code log4j-core:2.19.0} 本来就是 MC 1.20.1 的原版库）。
 */
public final class HotfixTriageTap {

    /** 本模块统一前缀（与 {@code HotfixBootstrap.PREFIX} 同一个字面量，故意重复：本类不引用那个类）。 */
    public static final String HOTFIX_PREFIX = "[SHANHAI-HOTFIX]";

    /** appender 名（log4j 按名字去重，重装时不会叠两份）。 */
    public static final String APPENDER_NAME = "shanhai-hotfix-triage";

    /**
     * 安装自证用的探针标记。
     *
     * <p>装完立刻用<b>本体那个 logger 名</b>打一行带这个 token 的日志，再由同一个 appender 看见它 ——
     * 这就把"appender 真的接到了那一条链路上"变成一次可判的读数，而不是"看着像是接上了"。
     */
    public static final String PROBE_TOKEN = "SHANHAI-HOTFIX-TAP-PROBE";

    /** 探针依次尝试的 logger 名（第一个命中就停）。 */
    public static final String[] PROBE_LOGGERS = {
            "com.shanhai.ShanhaiMod",                       // LogUtils.getLogger() 在本体里的名字
            "com.shanhai.add.hotfix.HotfixTriageTap"        // 兜底：本类自己的 logger
    };

    /**
     * 抓栈的保鲜期：超过这个时间的 {@code strike_caller} 不许用来选目标。
     *
     * <h2>🆕 2026-11-08 从 5 000 ms 放宽到 15 000 ms（依据写在这里）</h2>
     * <ol>
     *   <li><b>旧的 5 s 没有依据</b>：本体的 {@code strike_caller} 和它的 {@code solved=false} 行
     *       <b>在同一拍里</b>打出（{@code ShanhaiGuardTriage} 的 {@code flushTick}），
     *       配对延迟最多 1 tick（50 ms）⇒ 5 s 是 100 倍的富余，属"随手填的数"；</li>
     *   <li><b>现在多了一个生产者</b>：{@link #submitViolation}（违规现场抓的栈）。
     *       它和本体那一行<b>不一定同拍</b> —— 本体只在它自己的计数器有话说时才 flush，
     *       所以"现场已经发生、结论下一拍才来"是常态；5 s 会把这种合法配对当成 stale 挡掉
     *       （日志里就是 {@code gate_strike_stale}）；</li>
     *   <li><b>为什么不放到无限</b>：栈是"那一刻的调用者快照"。用它去 {@code retransformClasses}
     *       只在"这条代码路径还在作恶"时才有意义。300 tick（15 s）已经覆盖任何一场战斗里
     *       "被拦 → 结论出来"的延迟；再放宽就只剩"改错方法"的风险（而且它是<b>静默</b>的）。
     *       日志里有 {@code strike_age_ms=} 可复查这个假设；</li>
     *   <li>代价可控：目标还要过白名单（{@code HotfixPatchWhitelist}）＋"已改过就不再改"＋
     *       {@code undo} 三条闸门，所以放宽保鲜期<b>不会</b>把改原版的概率提高。</li>
     * </ol>
     */
    public static final long STRIKE_TTL_MILLIS = 15_000L;

    /**
     * 违规现场 strike 的保鲜期：<b>30 000 ms</b>（＝ 60 倍原值）。
     *
     * <p>依据：{@code HealthWrite → 本体发现 → 本体 flush triage} 这条链可能隔好几拍
     * （本体只在自己计数器有话说时才打 {@code solved=false}），而且 {@code die} 那条路
     * 常常是"血量已经写低 → 下一拍才判死"。600 tick 是"同一场战斗"的上界；
     * 再长就跨战斗了，那时旧栈指向的方法大概率已经不是作恶者。
     * <p>⚠️ 与 {@link #STRIKE_TTL_MILLIS} 分开写、不合并：两者的证据质量不同
     * （本体那一行是逐拍联动，我们这个是"现场留档"）。
     */
    public static final long VIOLATION_STRIKE_TTL_MILLIS = 30_000L;

    /** 记住多少个玩家的最近一次抓栈（防泄漏）。 */
    public static final int MAX_REMEMBERED_STRIKES = 32;

    /** 没有玩家名时用的键。 */
    private static final String GLOBAL_KEY = "*";

    /** 收到一条 {@code solved=false} 时的回调。 */
    public interface Sink {

        /**
         * @param verdict 本体那一拍的结论（已经 {@code solved=false}）
         * @param strike  配上的抓栈记录；没配上 ⇒ {@code null}
         */
        void onVerdict(HotfixTriageVerdict verdict, Strike strike);
    }

    /** 一次抓栈记录（＝本体某一拍的 {@code strike_caller#N} 行，或我们自己的违规现场）。 */
    public static final class Strike {

        /** 本体给的序号（1..3）；违规现场 ⇒ {@link #VIOLATION_INDEX}。 */
        public final int index;

        /** 帧表（本体已经滤过一层；解析不动口径）。 */
        public final List<HotfixTargetResolver.Frame> frames;

        /** 这一行落地的墙钟毫秒。 */
        public final long atMillis;

        /** 尾部 {@code player=}；没有 ⇒ {@code null}。 */
        public final String player;

        /** 原始那一行（违规现场 ⇒ {@code violation_stack …} 的形态描述）。 */
        public final String raw;

        /**
         * 🆕 这一条是不是"我们自己在违规现场抓的"。
         *
         * <p>决定了用哪个保鲜期（{@link #VIOLATION_STRIKE_TTL_MILLIS} 还是 {@link #STRIKE_TTL_MILLIS}）——
         * 见 {@code HotfixAutoCounter#handle} 的第 ③-a 道门槛。
         */
        public final boolean fromViolation;

        /** 违规现场用的序号（负数 ⇒ 与本体 1..3 天然区分开，日志里一眼看得出）。 */
        public static final int VIOLATION_INDEX = -1;

        /**
         * 🆕 2026-11-21（④）：这条 strike 的<b>来源</b>（进日志 {@code strike_source=}）。
         *
         * <p>为什么是个字段而不是"从 {@code fromViolation} 推"：现在有<b>第三个生产者</b>
         * {@code judge_watch}（关键类监控发现 Code 与基线不符）。它和违规现场走<b>同一条</b>通道，
         * 但来源必须分得开 —— 否则现场看到 {@code strike_source=violation} 会误判成"原版写入点抓的栈"。
         *
         * <p>取值：{@link #SOURCE_TRIAGE}／{@link #SOURCE_VIOLATION}／{@link #SOURCE_JUDGE_WATCH}／
         * {@link #SOURCE_HEALTH_CLAMP}／空串（空串 ⇒ 按 {@link #fromViolation} 老口径推，
         * 行为与改动前逐字相同）。
         */
        public final String source;

        /** 来源：本体分诊（{@code strike_caller#N} 那一行）。 */
        public static final String SOURCE_TRIAGE = "triage";

        /** 来源：我们在原版写入点抓的违规现场。 */
        public static final String SOURCE_VIOLATION = "violation";

        /** 来源：关键类监控（{@code HotfixJudgeWatch}）判 {@code foreign} 时喂进来的合成样本。 */
        public static final String SOURCE_JUDGE_WATCH = "judge_watch";

        /**
         * 来源：<b>写侧钳制命中</b>（{@code health_clamp}）时喂进来的现场
         * （{@link HotfixHealthClampStrike#SOURCE} 逐字引用本常量）。
         *
         * <p>为什么要单开：现场 {@code health_clamp} 命中 284 次而 {@code where=health} 的违规源 0 条
         * ⇒ 那条路以前<b>根本没喂</b>；现在喂了，就必须能在 {@code strike_source=} 上把它与
         * {@code violation}／{@code judge_watch} 分开 —— 否则现场会误判成"原版写入点抓的栈"。
         */
        public static final String SOURCE_HEALTH_CLAMP = "health_clamp";

        /**
         * 🆕 2026-11-21（②）：<b>违规现场那一拍抓的整链</b>（深度 {@link HotfixChainCapture#DEPTH}，
         * <b>最里层在前</b>＝栈数组顺序）。
         *
         * <p>为什么要单开一条：守卫那 16 帧是<b>日志格式</b>（{@code top=} 的条数），
         * 改它会动守卫、动现成的现场判读；而"整链抹除"要的是从顶端（靠框架）到最低端（写点）
         * 的<b>全部</b>外部段。这一条只在 {@link #submitViolation} 那一拍抓（就在原版写入点的调用链上），
         * 与那 16 帧互不影响。
         *
         * <p>可以为空（没抓到／老路径），消费方必须能接受空表并退回 {@link #frames}。
         */
        public final List<HotfixTargetResolver.Frame> chain;

        Strike(int index, List<HotfixTargetResolver.Frame> frames, long atMillis, String player, String raw) {
            this(index, frames, atMillis, player, raw, false, null, "");
        }

        Strike(int index, List<HotfixTargetResolver.Frame> frames, long atMillis, String player, String raw,
              boolean fromViolation) {
            this(index, frames, atMillis, player, raw, fromViolation, null, "");
        }

        Strike(int index, List<HotfixTargetResolver.Frame> frames, long atMillis, String player, String raw,
              boolean fromViolation, List<HotfixTargetResolver.Frame> chain) {
            this.index = index;
            this.frames = frames == null ? new ArrayList<>(0) : frames;
            this.atMillis = atMillis;
            this.player = player;
            this.raw = raw == null ? "" : raw;
            this.fromViolation = fromViolation;
            this.chain = chain == null ? new ArrayList<HotfixTargetResolver.Frame>(0) : chain;
            this.source = "";
        }

        Strike(int index, List<HotfixTargetResolver.Frame> frames, long atMillis, String player, String raw,
               boolean fromViolation, List<HotfixTargetResolver.Frame> chain, String source) {
            this.index = index;
            this.frames = frames == null ? new ArrayList<>(0) : frames;
            this.atMillis = atMillis;
            this.player = player;
            this.raw = raw == null ? "" : raw;
            this.fromViolation = fromViolation;
            this.chain = chain == null ? new ArrayList<HotfixTargetResolver.Frame>(0) : chain;
            this.source = source == null ? "" : source;
        }

        /** 距现在多久（毫秒）。 */
        public long ageMillis() {
            return System.currentTimeMillis() - atMillis;
        }

        /** 该用哪个保鲜期（毫秒）。 */
        public long ttlMillis() {
            return fromViolation ? VIOLATION_STRIKE_TTL_MILLIS : STRIKE_TTL_MILLIS;
        }

        /** 来源的机器名（日志用）。 */
        public String sourceToken() {
            if (source != null && !source.isEmpty()) {
                return source;
            }
            return fromViolation ? SOURCE_VIOLATION : SOURCE_TRIAGE;
        }
    }

    /** 已安装的 appender（{@code null} = 没装上）。 */
    private static volatile TriageAppender appender;

    /** 上层回调。 */
    private static volatile Sink sink;

    /** 安装时那次自证有没有被看见。 */
    private static volatile boolean probeSeen;

    /** 探针命中的 logger 名（{@code "none"} = 没命中）。 */
    private static volatile String probeLogger = "none";

    /** 装不上的原因（{@code ""} = 没试过）。 */
    private static volatile String lastInstallReason = "";

    /** 每个玩家的最近一次抓栈（键：玩家名；无名用 {@link #GLOBAL_KEY}）。 */
    private static final Map<String, Strike> STRIKES = new LinkedHashMap<>();

    /** appender 一共收到过多少个 event（判读"到底有没有接上"用；不参与任何判定）。 */
    private static final AtomicLong SEEN_EVENTS = new AtomicLong();

    /** 其中带 {@code [SHANHAI-TRIAGE]} 的有多少条（判读"本体的话我们听没听到"用）。 */
    private static final AtomicLong SEEN_TRIAGE = new AtomicLong();

    /** 🆕 收到过多少次"我们自己的违规现场"（{@link #submitViolation}）。 */
    private static final AtomicLong VIOLATIONS = new AtomicLong();

    /** 🆕 其中真的投递进 strike 通道多少次（{@code 0} 但 {@link #VIOLATIONS} > 0 ⇒ 通道没装上）。 */
    private static final AtomicLong VIOLATION_FED = new AtomicLong();

    private HotfixTriageTap() {
        // 纯静态
    }

    /**
     * 装上（幂等）。
     *
     * @param newSink 收到 {@code solved=false} 时的回调
     * @return {@code true} = appender 已挂上<b>且</b>自证探针走通了（{@code false} 不代表游戏有问题）
     */
    public static synchronized boolean install(Sink newSink) {
        if (newSink == null) {
            lastInstallReason = "null_sink";
            return false;
        }
        sink = newSink;
        if (appender != null) {
            return probeSeen;
        }
        TriageAppender ap = new TriageAppender(APPENDER_NAME);
        try {
            ap.start();
        } catch (Throwable t) {
            lastInstallReason = "appender_start_failed:" + t.getClass().getSimpleName();
            return false;
        }
        final org.apache.logging.log4j.Logger root = LogManager.getRootLogger();
        if (!(root instanceof org.apache.logging.log4j.core.Logger)) {
            lastInstallReason = "root_logger_not_core:" + (root == null ? "null" : root.getClass().getName());
            return false;
        }
        try {
            ((org.apache.logging.log4j.core.Logger) root).addAppender(ap);
        } catch (Throwable t) {
            lastInstallReason = "add_appender_failed:" + t.getClass().getSimpleName();
            return false;
        }
        appender = ap;
        lastInstallReason = "";
        // 自证：用本体那个 logger 名打一行探针，必须被同一个 appender 看见
        probeSeen = false;
        probeLogger = "none";
        for (int i = 0; i < PROBE_LOGGERS.length && !probeSeen; i++) {
            try {
                LogManager.getLogger(PROBE_LOGGERS[i])
                        .info(HOTFIX_PREFIX + " tap_probe " + PROBE_TOKEN + " logger=" + PROBE_LOGGERS[i]);
            } catch (Throwable ignored) {
                // 探针失败不是致命错：接着试下一个名字
            }
            if (probeSeen) {
                probeLogger = PROBE_LOGGERS[i];
            }
        }
        if (!probeSeen) {
            lastInstallReason = "probe_missed";
        }
        return probeSeen;
    }

    /** 挂上了吗（不要求自证通过）。 */
    public static boolean installed() {
        return appender != null;
    }

    /** 安装自证（探针）通过了吗。 */
    public static boolean probeSeen() {
        return probeSeen;
    }

    /** 装不上的原因；空串 = 没失败过。 */
    public static String lastInstallReason() {
        return lastInstallReason;
    }

    /** 一行判读摘要（命令 {@code status} 与日志共用）。 */
    public static String statusLine() {
        int remembered;
        synchronized (STRIKES) {
            remembered = STRIKES.size();
        }
        return "tap_installed=" + (appender != null)
                + " tap_probe=" + (probeSeen ? probeLogger : "missed")
                + " tap_reason=" + (lastInstallReason.isEmpty() ? "ok" : lastInstallReason)
                + " tap_seen_events=" + SEEN_EVENTS.get()
                + " tap_seen_triage=" + SEEN_TRIAGE.get()
                + " strikes_remembered=" + remembered
                + " strike_ttl_ms=" + STRIKE_TTL_MILLIS
                + " violation_seen=" + VIOLATIONS.get()
                + " violation_fed=" + VIOLATION_FED.get()
                + " violation_ttl_ms=" + VIOLATION_STRIKE_TTL_MILLIS;
    }

    /** 清空抓栈记忆（新的一局／命令重来）。appender 不动。 */
    public static void reset() {
        synchronized (STRIKES) {
            STRIKES.clear();
        }
    }

    // ------------------------------------------------------------------ 解析入口（离线自检直接调这个）

    /**
     * 处理一行日志（appender 回调与离线自检<b>同一条路</b>）。
     *
     * <p>包级可见：离线自检在同一个包里跑不了（它不在本包里），所以这里给 {@code public}。
     */
    public static void handleLine(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        if (text.indexOf(PROBE_TOKEN) >= 0) {
            probeSeen = true;
            return;
        }
        if (text.indexOf(HotfixTriageVerdict.PREFIX) < 0) {
            return;
        }
        SEEN_TRIAGE.incrementAndGet();
        if (text.indexOf(HotfixTargetResolver.STRIKE_MARK) >= 0) {
            remember(HotfixTargetResolver.parseStrike(text));
            return;
        }
        final HotfixTriageVerdict verdict = HotfixTriageVerdict.parse(text);
        if (verdict == null || verdict.solved) {
            return;
        }
        final Strike strike = strikeFor(verdict.player);
        final Sink target = sink;
        if (target == null) {
            return;
        }
        try {
            target.onVerdict(verdict, strike);
        } catch (Throwable ignored) {
            // 上层炸了也不许把日志线程带崩
        }
    }

    /** 只读快照：某个玩家最近一次抓栈；没有 ⇒ {@code null}。 */
    public static Strike strikeFor(String player) {
        final String key = player == null || player.isEmpty() ? GLOBAL_KEY : player;
        synchronized (STRIKES) {
            return STRIKES.get(key);
        }
    }

    // ------------------------------------------------------------------ 🆕 违规现场 → strike 通道

    /**
     * <b>把"我们自己的违规现场"当成一次 strike 喂进来</b>（用户点名的那条路）。
     *
     * <pre>
     *   「主动拆代码谁叫你只抓伤害事件相关的了，你去抓所有代码」
     *   「每一次违规/拦截的现场都抓一次完整栈，交给 HotfixTriageTap 现有的 strike 通道」
     * </pre>
     *
     * <p>做三件事（全部便宜：只在已经抓完栈的慢路径上调）：
     * <ol>
     *   <li>把帧文本解析成 {@link HotfixTargetResolver.Frame}（<b>同一套</b>帧口径）；</li>
     *   <li>记进 {@link #STRIKES}（键 = 玩家名），让随后来的本体 {@code solved=false} 也能配上它；</li>
     *   <li>立刻投递一次 {@code verdict}（{@code why=health|die|remove|motion|pos|attack}）——
     *       不等本体那一行，否则"本体刚好没话说"时这套就空转了。</li>
     * </ol>
     *
     * <p>⚠️ 在<b>服务端线程</b>上调（注入点里），所以这里只做"解析 ＋ map 写 ＋ 回调"，
     * 真正的 retransform 在 {@code HotfixAutoCounter} 的工作线程上（它自己入队）。
     *
     * @param where  {@code health|die|remove|motion|pos|attack}
     * @param player 玩家名（可 {@code null}）
     * @param frames 帧文本（{@code 类#方法:行}，<b>不过滤</b>的前 16 帧）
     * @param atMillis 抓栈时刻（毫秒）
     * @return {@code true} = 真的投递出去了（{@code false} = 没接上/无帧且无目标）
     */
    public static boolean submitViolation(String where, String player, List<String> frames, long atMillis) {
        return submitViolation(where, player, frames, atMillis, Strike.SOURCE_VIOLATION);
    }

    /**
     * 🆕 2026-11-21（④）：带<b>来源</b>的重载（第三个生产者 {@code judge_watch} 走这里）。
     *
     * <p>只多一个字段：{@link Strike#source} ⇒ 日志里的 {@code strike_source=} 不再总是 {@code violation}。
     * 通道本身（appender／配对／保鲜期／门槛链）<b>一个字都没改</b>。
     *
     * @param source {@link Strike#SOURCE_JUDGE_WATCH} 等；{@code null}/空 ⇒ 按老口径（violation）
     */
    public static boolean submitViolation(String where, String player, List<String> frames, long atMillis,
                                          String source) {
        VIOLATIONS.incrementAndGet();
        final List<HotfixTargetResolver.Frame> parsed = new ArrayList<>(
                frames == null ? 0 : Math.min(frames.size(), VIOLATION_MAX_FRAMES));
        if (frames != null) {
            for (int i = 0; i < frames.size() && parsed.size() < VIOLATION_MAX_FRAMES; i++) {
                final HotfixTargetResolver.Frame f = HotfixTargetResolver.parseFrame(frames.get(i));
                if (f != null) {
                    parsed.add(f);
                }
            }
        }
        final String key = player == null || player.isEmpty() ? GLOBAL_KEY : player;
        // 🆕 2026-11-21（②）：在**违规现场这一拍**抓整链（深度 64）。只有这里有意义 ——
        //   调用方（守卫）就是在原版写入点的调用链上调进来的；晚一拍再抓就只剩我们自己的帧。
        //   代价：一次 Thread#getStackTrace（≤64 帧，几十微秒级），而守卫那条路本来就有 1/s 限流。
        List<HotfixTargetResolver.Frame> chain;
        try {
            chain = HotfixChainCapture.live();
        } catch (Throwable ignored) {
            chain = new ArrayList<HotfixTargetResolver.Frame>(0);
        }
        if (chain == null) {
            chain = new ArrayList<HotfixTargetResolver.Frame>(0);
        }
        final StringBuilder raw = new StringBuilder(160);
        raw.append("violation_stack where=").append(where == null ? "?" : where)
                .append(" player=").append(player == null ? "?" : player)
                .append(" frames=").append(parsed.size())
                .append(" chain=").append(chain.size())
                .append(" source=").append(source == null || source.isEmpty() ? Strike.SOURCE_VIOLATION : source);
        final Strike record = new Strike(Strike.VIOLATION_INDEX, parsed, atMillis, player,
                raw.toString(), true, chain, source);
        synchronized (STRIKES) {
            STRIKES.put(key, record);
        }
        final Sink target = sink;
        if (target == null) {
            return false;       // 通道还没装（tap 没装上）⇒ 至少把栈记住了
        }
        try {
            target.onVerdict(HotfixTriageVerdict.ofViolation(where, player), record);
            VIOLATION_FED.incrementAndGet();
            return true;
        } catch (Throwable ignored) {
            return false;       // 上层炸了也不许把 tick 带崩
        }
    }

    /** 帧上限（与守卫侧 {@code ShanhaiViolationStack.MAX_FRAMES = 16} 同口径）。 */
    public static final int VIOLATION_MAX_FRAMES = 16;

    /** 收到过多少次违规现场。 */
    public static long violations() {
        return VIOLATIONS.get();
    }

    /** 其中真的投递进 strike 通道多少次。 */
    public static long violationsFed() {
        return VIOLATION_FED.get();
    }

    // ------------------------------------------------------------------ 内部

    /** 记住一次抓栈（顺手做过期清理与条数上限）。 */
    private static void remember(HotfixTargetResolver.Strike parsed) {
        if (parsed == null) {
            return;
        }
        final String key = parsed.player == null || parsed.player.isEmpty() ? GLOBAL_KEY : parsed.player;
        final Strike record = new Strike(parsed.index, parsed.frames, System.currentTimeMillis(),
                parsed.player, parsed.raw);
        synchronized (STRIKES) {
            STRIKES.put(key, record);
            if (STRIKES.size() > MAX_REMEMBERED_STRIKES) {
                final long cutoff = System.currentTimeMillis() - (STRIKE_TTL_MILLIS * 10L);
                final Iterator<Map.Entry<String, Strike>> it = STRIKES.entrySet().iterator();
                while (it.hasNext() && STRIKES.size() > MAX_REMEMBERED_STRIKES) {
                    final Strike s = it.next().getValue();
                    if (s == null || s.atMillis < cutoff) {
                        it.remove();
                    } else {
                        break;
                    }
                }
            }
        }
    }

    /** 真正的 appender：只解析、只投递，绝不在这里动手、绝不在这里打日志。 */
    private static final class TriageAppender extends AbstractAppender {

        TriageAppender(String name) {
            // name / filter=null / layout=null（我们不用 layout，直接读 Message）/ ignoreExceptions=true / 无属性
            // ⚠️ 用 5 参构造：4 参那个在 log4j 2.19 里已 @Deprecated（-Xlint:all 会警告）
            super(name, null, null, true, null);
        }

        @Override
        public void append(LogEvent event) {
            try {
                if (event == null) {
                    return;
                }
                final Level level = event.getLevel();
                // 只要 INFO 及以上（DEBUG/TRACE 直接丢，连格式化都不做）
                if (level == null || level.intLevel() > Level.INFO.intLevel()) {
                    return;
                }
                final Message message = event.getMessage();
                if (message == null) {
                    return;
                }
                SEEN_EVENTS.incrementAndGet();
                final String format = message.getFormat();
                final String loggerName = event.getLoggerName();
                final boolean ours = (loggerName != null && loggerName.startsWith("com.shanhai"))
                        || (format != null && format.indexOf("[SHANHAI") >= 0);
                if (!ours) {
                    return;
                }
                final String text = message.getFormattedMessage();
                handleLine(text);
            } catch (Throwable ignored) {
                // appender 抛异常会污染日志管道 —— 全吞
            }
        }
    }
}
