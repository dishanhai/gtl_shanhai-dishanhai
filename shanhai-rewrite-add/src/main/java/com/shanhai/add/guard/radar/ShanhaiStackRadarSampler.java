package com.shanhai.add.guard.radar;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * <b>常开栈雷达的采样核</b>：一个 daemon 线程按低采样率抓<b>服务端主线程</b>的栈，压进环形缓冲；
 * 命中可疑帧（或别人要求）时把最近 200 ms 归因成 top-5 打一行。
 *
 * <h2>🔴 用户口径（原话）</h2>
 * <pre>
 *   「那你怎么判断可疑窗口，要是敌方绕开了怎么办，所以你要一直开这个窗口」
 * </pre>
 * ⇒ <b>不做"可疑窗口"</b>：本类没有"开关扫描窗口"的概念，服务端在跑就一直采。
 * 唯一会变的是<b>采样密度</b>（默认 4 ms 一档；漏采时自动升档，见下），不是"开/不开"。
 *
 * <h2>🔴 为什么必须另起一个线程（本类存在的根因）</h2>
 * {@code Thread.getStackTrace()} 抓的是<b>调用者自己</b>所在的线程。
 * 谁在主线程里调它，抓到的就只是"我们自己的 dump 调用栈"——<b>一行敌方信息都没有</b>。
 * 所以：主线程只<b>记下自己的 {@code Thread} 引用</b>（{@code ServerStartedEvent} 里
 * {@code Thread.currentThread()}），抓栈永远在这个 daemon 线程上做，抓的对象是<b>那个引用</b>。
 *
 * <h2>成本怎么算（可自证，不估算）</h2>
 * <ul>
 *   <li>单次采样 = {@code target.getStackTrace()}（跨线程，需要目标线程到安全点）
 *       ＋ 白名单过滤（命中即跳过，不分配）＋ 一次环形缓冲写入。</li>
 *   <li>{@code avg_us}/{@code max_us} 用 {@link System#nanoTime()} 围着这一段实测，
 *       每 60 秒打一行 {@code stats}（{@link #printStats()}）。</li>
 *   <li>🔴 等待一律 {@link Thread#sleep(long)}，<b>不用</b> {@code LockSupport.parkNanos}
 *       —— 实测 park 的 1 ms／4 ms／8 ms 全部落到 15.6 ms 的系统节拍上（详见 {@link #BURST_YIELD_MS}
 *       与 {@code temp\radar\报告.md}），那会让"升密度"变成空话。</li>
 *   <li>实测（Win11／JDK 21／纯 JVM，{@code temp\radar\selfcheck\}）：4 ms 档 ≈ <b>220 次/秒</b>、
 *       1 ms 档 ≈ <b>520 次/秒</b>；单次采样 0.2 ms 上下（含等目标线程到安全点的时间）。
 *       ⇒ 常开档的 CPU ≈ 220 × 0.2 ms / 1 s ≈ <b>4 % 单核</b>（真实读数随机器变，看 stats 行）。</li>
 *   <li>跨线程抓栈的<b>真正代价不在我们的线程</b>，而在目标线程"被叫到安全点"——
 *       这一点本类<b>测不到</b>（只能测自己这一侧），已写进 {@code temp\radar\报告.md} 的「没验到的」。</li>
 * </ul>
 *
 * <h2>自适应密度（漏采时自己升档，这是补"可能漏采"那个短板的唯一手段）</h2>
 * <pre>
 *   默认          interval_ms = 4（配置 stack_radar_interval_ms，1~50 夹取）
 *   1 次漏采      ⇒ 1 ms / 30 秒（或下一次 dump 拿到 suspect_hits>0，取先到者）
 *   连续 3 次漏采 ⇒ burst：0 ms 背靠背 + 1 ms 让出 / 10 秒   ← 日志 radar boost mode=burst
 *   burst 自保护  单次 getStackTrace &gt; 2 ms 或线程/服务器已停 ⇒ 立刻退回默认
 *                                                          ← 日志 radar boost aborted reason=…
 *   每次切换      ⇒ 日志 density interval_ms=&lt;n&gt; reason=miss_streak|reset ttl_ms=&lt;n&gt;
 * </pre>
 * ⚠️ <b>一次漏采连击只给一次 burst</b>（{@code missStreak == 3} 才进，4 次以上回 1 ms 档）
 * —— 否则"每隔 10 秒再来一次 burst"会让最坏 CPU 无上限。这条是规格之外我加的闸门，
 * 写进报告的「密度切换的判据与上限」一节。
 *
 * <h2>🔴 线程没了就不许再拿旧样本冒充</h2>
 * {@link #stop(String)} <b>清空</b>环形缓冲。停了之后再 {@link #dump(String)} 只能打出
 * {@code samples=0 hits=0 top=none state=stopped} —— "没数据"和"没问题"必须在日志上分得开。
 *
 * <p>依赖面：{@code java.*} 一切（没有 MC/Forge/日志框架）⇒ 可脱开游戏离线自检
 * （{@code temp\radar\selfcheck\}）。
 */
public final class ShanhaiStackRadarSampler {

    /** 本模块统一日志前缀。 */
    public static final String PREFIX = "[SHANHAI-RADAR]";

    /** 采样线程名（固定，规格要求；/jstack 里一眼能认出来）。 */
    public static final String THREAD_NAME = "shanhai-stack-radar";

    /** 环形缓冲格数（规格 512）。4 ms 一档时覆盖 ≈2 秒。 */
    public static final int BUFFER_SIZE = 512;

    /** dump 的统计窗口（规格 200 ms）。 */
    public static final long WINDOW_MS = 200L;

    /** 一个样本最多留几帧（规格 8）。 */
    public static final int MAX_FRAMES = ShanhaiStackRadarFilter.MAX_FRAMES;

    /** dump 里 top 取前几名（规格 5）。 */
    public static final int TOP_N = 5;

    /** 🆕 {@code known_top=} 取前几名（规格：已知宿主的前三）。 */
    public static final int KNOWN_TOP_N = 3;

    /** 默认间隔（规格 4 ms ≈ 250 次/秒）。 */
    public static final int DEFAULT_INTERVAL_MS = 4;

    /** 间隔下限（规格 1~50 夹取）。 */
    public static final int INTERVAL_MIN_MS = 1;

    /** 间隔上限（规格 1~50 夹取）。 */
    public static final int INTERVAL_MAX_MS = 50;

    /** 默认只看栈顶前 32 帧（配置 stack_radar_depth）。 */
    public static final int DEFAULT_DEPTH = 32;

    /** 深度硬上限：再深也只是把 {@code String[]} 撑大，不会多归因出什么。 */
    public static final int MAX_DEPTH = 256;

    /** 漏采升档后的间隔（规格 1 ms）。 */
    public static final int BOOST_INTERVAL_MS = 1;

    /** 漏采升档的存活时间（规格 30 秒；下一次 dump 拿到 hits>0 也会提前结束）。 */
    public static final long BOOST_TTL_MS = 30_000L;

    /** burst 档的间隔（规格「0 ms 轮转」＝不按间隔排队，只按 {@link #BURST_YIELD_MS} 让出）。 */
    public static final int BURST_INTERVAL_MS = 0;

    /** burst 档的存活时间（规格 10 秒）。 */
    public static final long BURST_TTL_MS = 10_000L;

    /**
     * burst 档每采一次让出多久（规格「带 1 ms 让出」）。
     *
     * <p>🔴 这一毫秒是<b>唯一的 CPU 闸门</b>：真背靠背转（不让出）时单次 30 µs ⇒ 一秒三万次采样
     * ⇒ 单核被打满。让出 1 ms 后 ≈ 500 次/秒（实测读数见下），CPU 上限 ≈ 3 % 单核。
     *
     * <p>⚠️ 实测（Win11 / JDK 21 / 纯 JVM，{@code temp\radar\selfcheck\GranularityProbe}）：
     * {@code LockSupport.parkNanos(1ms)} 实际睡了 <b>15.26 ms</b>（Windows 的等待超时精度是 15.6 ms 的
     * 系统节拍），而 {@code Thread.sleep(1)} 只睡 <b>1.90 ms</b>。
     * ⇒ <b>本类的所有等待一律走 {@link Thread#sleep(long)}，不用 park</b>：
     * 用 park 的话 1 ms 档和 4 ms 档会一起掉到 15.6 ms（约 64 次/秒），"升密度"就成了空话。
     */
    public static final long BURST_YIELD_MS = 1L;

    /** burst 自保护：单次 {@code getStackTrace()} 超过这么多纳秒就中止（规格 2 ms）。 */
    public static final long BURST_ABORT_NANOS = 2_000_000L;

    /** 连续几次漏采进 burst（规格 3）。 */
    public static final int MISS_STREAK_FOR_BURST = 3;

    /** stats 行的周期（规格 60 秒）。 */
    public static final long STATS_INTERVAL_MS = 60_000L;

    /** 自我保护：某一帧一秒内出现超过这么多次 ⇒ 认为"主线程被它占住了"（规格 50）。 */
    public static final int SELF_HITS_THRESHOLD = 50;

    /** 自我保护：连续这么多秒都超阈值才算"持续"（规格 3 秒）。 */
    public static final int SELF_SUSTAINED_SECONDS = 3;

    /** 自我保护 dump 的限流（规格 10 秒）。 */
    public static final long SELF_DUMP_COOLDOWN_MS = 10_000L;

    private static final long SECOND_NANOS = 1_000_000_000L;
    private static final long MILLIS_NANOS = 1_000_000L;
    private static final long WINDOW_NANOS = WINDOW_MS * MILLIS_NANOS;
    private static final long BOOST_TTL_NANOS = BOOST_TTL_MS * MILLIS_NANOS;
    private static final long BURST_TTL_NANOS = BURST_TTL_MS * MILLIS_NANOS;
    private static final long STATS_INTERVAL_NANOS = STATS_INTERVAL_MS * MILLIS_NANOS;
    private static final long SELF_DUMP_COOLDOWN_NANOS = SELF_DUMP_COOLDOWN_MS * MILLIS_NANOS;

    /** 日志出口（游戏侧接 log4j，离线自检接 stdout）。 */
    public interface Log {

        /** 一行 INFO（调用方给<b>不带前缀</b>的正文）。 */
        void line(String text);

        /** 一行 WARN（默认退回 {@link #line(String)}；游戏侧覆盖成真 WARN）。 */
        default void warn(final String text) {
            line(text);
        }
    }

    // ═══════════════════════════════ 密度（可单测的状态机） ═══════════════════════════════

    /**
     * 一档采样密度。<b>不可变</b>：切档＝换一个实例（{@code volatile} 引用，
     * 采样线程每轮读一次，读到旧值最多多采一拍，不会错乱）。
     */
    public static final class Density {

        /** 默认档。 */
        public static final int NORMAL = 0;
        /** 漏采升档（1 ms）。 */
        public static final int BOOST = 1;
        /** 连击漏采的 burst 档（0 ms + 1 ms 让出）。 */
        public static final int BURST = 2;

        public final int mode;
        public final int intervalMs;
        /** 到期时刻（{@link System#nanoTime()} 口径；0 = 不到期）。 */
        public final long untilNanos;
        /** 进这一档的原因（进日志的 {@code reason=}）：{@code normal}/{@code miss_streak}/{@code reset}。 */
        public final String reason;
        /** 这一档的存活毫秒（进日志的 {@code ttl_ms=}；0 = 不到期）。 */
        public final int ttlMs;

        public Density(final int mode, final int intervalMs, final long untilNanos,
                       final String reason, final int ttlMs) {
            this.mode = mode;
            this.intervalMs = intervalMs;
            this.untilNanos = untilNanos;
            this.reason = reason == null ? "?" : reason;
            this.ttlMs = ttlMs;
        }

        public String modeName() {
            return mode == BURST ? "burst" : mode == BOOST ? "boost" : "normal";
        }

        /** 两档是不是"同一档同样参数同样到期时刻"（用来判"要不要打一行 density"）。 */
        boolean sameAs(final Density other) {
            return other != null && other.mode == mode && other.intervalMs == intervalMs
                    && other.untilNanos == untilNanos;
        }

        @Override
        public String toString() {
            return "mode=" + modeName() + " interval_ms=" + intervalMs
                    + " reason=" + reason + " ttl_ms=" + ttlMs;
        }
    }

    /**
     * 🔴 <b>纯函数</b>：这次 dump 的读数该把密度切到哪一档（不读时钟、不打日志、不动状态）。
     *
     * <p>抽成纯函数是为了让"漏采升档 / burst / 到期回落 / 命中即回落"四条判据
     * 能<b>离线断言</b>（{@code temp\radar\selfcheck\}），不必等真游戏里的偶发漏采。
     *
     * @param current           当前档
     * @param samples           这次窗口里的样本数
     * @param suspectHits       🆕 这次窗口里的 <b>suspect</b> 帧总数
     *                          （不是全部非白名单帧 —— 见到 GT 的帧不等于见到了我们在找的东西，
     *                          详见 {@link #dump(String)} 与 {@code 报告.md} ⑥）
     * @param missStreak        这次 dump 之后已经连续几次漏采（{@code suspectHits==0}）
     * @param nowNanos          {@link System#nanoTime()}
     * @param defaultIntervalMs 配置的默认间隔（回落时用它，<b>不</b>写死 4）
     * @return 下一档；返回 {@code current} 本身表示"不变"
     */
    public static Density nextDensity(final Density current, final int samples, final int suspectHits,
                                      final int missStreak, final long nowNanos, final int defaultIntervalMs) {
        final int base = clampInterval(defaultIntervalMs);
        final Density cur = current == null
                ? new Density(Density.NORMAL, base, 0L, "normal", 0) : current;
        // ① 到期回落（放在最前面：升档是"临时"的，时间到了必须自己回默认，哪怕这一拍还在漏采）
        if (cur.untilNanos != 0L && nowNanos >= cur.untilNanos) {
            return new Density(Density.NORMAL, base, 0L, "reset", 0);
        }
        // ② 没样本 ⇒ 判不了，保持原样（"没数据"不等于"没命中"）
        if (samples <= 0) {
            return cur;
        }
        // ③ 抓到可疑东西了 ⇒ 升档立刻结束（规格：或直到下一次 dump 拿到"命中"，取先到者）
        if (suspectHits > 0) {
            return cur.mode == Density.NORMAL
                    ? cur : new Density(Density.NORMAL, base, 0L, "reset", 0);
        }
        // ④ 有样本却一条可疑帧都没有 = 漏采
        if (cur.mode == Density.BURST) {
            return cur;   // 🔴 burst 不许被后续漏采延长（否则 CPU 无上限）
        }
        if (missStreak == MISS_STREAK_FOR_BURST) {
            return new Density(Density.BURST, BURST_INTERVAL_MS, nowNanos + BURST_TTL_NANOS,
                    "miss_streak", (int) BURST_TTL_MS);
        }
        return new Density(Density.BOOST, BOOST_INTERVAL_MS, nowNanos + BOOST_TTL_NANOS,
                "miss_streak", (int) BOOST_TTL_MS);
    }

    /** 间隔夹取（规格 1~50）：配置被手改成 0 或 999 时不许把采样线程变成忙转或哑掉。 */
    public static int clampInterval(final int raw) {
        if (raw < INTERVAL_MIN_MS) {
            return INTERVAL_MIN_MS;
        }
        return Math.min(raw, INTERVAL_MAX_MS);
    }

    // ═══════════════════════════════ 状态 ═══════════════════════════════

    private final Log sink;

    /** 环形缓冲（写者＝采样线程，读者＝dump；两边都短，用同一把锁护住）。 */
    private final String[][] ring = new String[BUFFER_SIZE][];
    private final long[] stamps = new long[BUFFER_SIZE];
    private final Object lock = new Object();

    private int cursor;
    private int filled;

    /** stats 的"上一行 stats 到这一行"窗口读数（锁内读写）。 */
    private long windowSamples;
    private long windowDropped;
    private long windowNanos;
    private long windowMaxNanos;

    /** 抓过多少拍（自检与停止日志用；只增）。 */
    private final AtomicLong totalSamples = new AtomicLong();
    private final AtomicLong totalDropped = new AtomicLong();

    /** 抓栈异常只 WARN 一次（规格：不刷屏）。 */
    private final AtomicBoolean captureWarned = new AtomicBoolean();

    private volatile boolean running;
    private volatile Thread worker;
    private volatile Thread target;
    private volatile int depth = DEFAULT_DEPTH;
    /** 配置里的默认间隔（回落目标）。 */
    private volatile int baseIntervalMs = DEFAULT_INTERVAL_MS;
    /** 当前密度档。 */
    private volatile Density density = new Density(Density.NORMAL, DEFAULT_INTERVAL_MS, 0L, "normal", 0);
    /** 连续几次 dump 漏采（{@code suspectHits==0}）。 */
    private volatile int missStreak;
    private volatile long lastSelfDumpNanos;

    /** 以下三个只在采样线程上用。 */
    private final Map<String, Integer> secondCounts = new HashMap<>();
    private long secondStartNanos;
    private int secondSamples;
    private String lastWorstKey;
    private int sustainedSeconds;

    // ═══════════════════ 🆕 已知宿主 ＋ 基线（突增判据的两块料） ═══════════════════

    /**
     * 🆕 基线的秒数窗口（规格：最近 60 秒每秒命中数的中位数）。
     */
    public static final int BASELINE_SECONDS = 60;

    /**
     * 🆕 最多给多少个帧记基线（内存闸门：60 个 int × 1024 ≈ 245 KB 上限）。
     *
     * <p>满了之后<b>不再给新帧建档</b>（它们的基线按 0 处理 ⇒ 第一次出现就算突增，这是刻意的：
     * "以前从没见过"本身就是突增信号）。真被刷爆时日志里表现为"很多 base=0"，不至于失守。
     */
    public static final int BASELINE_MAX_FRAMES = 1024;

    /**
     * 🆕 已知宿主判定（由 Forge 那侧从 {@code ModList} 灌进来）。
     *
     * <p>默认是"一个都不认" ⇒ 所有非白名单帧都是 suspect（等价于还没接上 ModList，行为与改动前一致）。
     */
    private volatile ShanhaiStackRadarFilter.KnownHosts knownHosts =
            new ShanhaiStackRadarFilter.KnownHosts(ShanhaiStackRadarFilter.NO_WHITELIST);

    /** 🆕 帧键 ⇒ 基线（锁内读写：写＝每秒一次，读＝dump 时算中位数）。 */
    private final Map<String, ShanhaiStackRadarFilter.Baseline> baselines = new HashMap<>();

    public ShanhaiStackRadarSampler(final Log sink) {
        this.sink = sink;
    }

    // ═══════════════════════════════ 起 / 停 ═══════════════════════════════

    /**
     * 起采样线程（幂等：已在跑就先停再起，换目标线程＝换了一个服务器实例）。
     *
     * <p>⚠️ 本方法必须在<b>主线程</b>上调（{@code ServerStartedEvent} 里），
     * 并且 {@code targetThread} 必须是 {@code Thread.currentThread()} ——
     * 传别的线程进来就是"采错人"。
     *
     * @param targetThread 要采的线程（服务端主线程）
     * @param intervalMs   默认间隔（会按 1~50 夹取）
     * @param depthFrames  只看栈顶前几帧（{@code <=0} ⇒ {@link #DEFAULT_DEPTH}）
     * @param knownNamespaces 🆕 已知宿主包前缀（Forge 那侧从 {@code ModList} 取；{@code null}/空 = 一个都不认）
     */
    public void start(final Thread targetThread, final int intervalMs, final int depthFrames,
                      final String[] knownNamespaces) {
        synchronized (this) {
            if (running) {
                stop("restart");
            }
            target = targetThread;
            depth = depthFrames <= 0 ? DEFAULT_DEPTH : Math.min(depthFrames, MAX_DEPTH);
            baseIntervalMs = clampInterval(intervalMs);
            knownHosts = new ShanhaiStackRadarFilter.KnownHosts(knownNamespaces);
            density = new Density(Density.NORMAL, baseIntervalMs, 0L, "normal", 0);
            missStreak = 0;
            lastSelfDumpNanos = 0L;
            secondCounts.clear();
            secondSamples = 0;
            lastWorstKey = null;
            sustainedSeconds = 0;
            secondStartNanos = System.nanoTime();
            synchronized (lock) {
                Arrays.fill(ring, null);
                stampsReset();
                baselines.clear();
                cursor = 0;
                filled = 0;
                windowSamples = 0L;
                windowDropped = 0L;
                windowNanos = 0L;
                windowMaxNanos = 0L;
            }
            running = true;
            final Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    loop();
                }
            }, THREAD_NAME);
            thread.setDaemon(true);   // 🔴 daemon：绝不许它把进程留住
            try {
                thread.start();
                worker = thread;
            } catch (Throwable t) {
                running = false;
                worker = null;
                log("started=false reason=thread_start_failed err="
                        + ShanhaiStackRadarFilter.token(simpleName(t)));
                return;
            }
            log("started target_thread=" + safeName(targetThread) + " sampler_thread=" + THREAD_NAME
                    + " interval_ms=" + baseIntervalMs + " depth=" + depth
                    + " buffer=" + BUFFER_SIZE + " window_ms=" + WINDOW_MS + " top_n=" + TOP_N
                    + " whitelist=" + ShanhaiStackRadarFilter.DEFAULT_WHITELIST.length
                    + " known_hosts=" + knownHosts.size() + " baseline_s=" + BASELINE_SECONDS
                    + " noise_ratio=" + (int) ShanhaiStackRadarFilter.NOISE_MIN_RATIO
                    + " nominate_ratio=" + (int) ShanhaiStackRadarFilter.NOMINATE_MIN_RATIO
                    + " density=" + density.modeName());
        }
    }

    /**
     * 停采样线程（{@code ServerStoppedEvent}／换世界／自检收尾）。
     *
     * <p>🔴 <b>顺手清空环形缓冲</b>：停了之后再 dump 必须是"没有数据"，
     * 不许拿停机前的旧样本冒充当前现场。
     *
     * @param trigger 谁停的（{@code server_stopped}／{@code restart}／…；burst 中止日志会原样带上它）
     */
    public synchronized void stop(final String trigger) {
        final boolean wasRunning = running;
        running = false;
        final Thread thread = worker;
        worker = null;
        if (thread != null) {
            thread.interrupt();   // 把正在 sleep 的采样线程立刻叫醒（它会自己看 running 决定退出）
        }
        synchronized (lock) {
            Arrays.fill(ring, null);
            stampsReset();
            cursor = 0;
            filled = 0;
        }
        final Density now = density;
        if (now.mode == Density.BURST) {
            log("radar boost aborted reason=" + ShanhaiStackRadarFilter.token(trigger));
        }
        if (now.mode != Density.NORMAL) {
            applyDensity(new Density(Density.NORMAL, clampInterval(baseIntervalMs), 0L, "reset", 0));
        }
        log("stopped trigger=" + ShanhaiStackRadarFilter.token(trigger) + " was_running=" + wasRunning
                + " samples_total=" + totalSamples.get() + " dropped_total=" + totalDropped.get());
    }

    // ═══════════════════════════════ 采样循环 ═══════════════════════════════

    /** 采样线程主体。整段 try/catch：这个线程死了也不许影响游戏（它本来就是 daemon）。 */
    private void loop() {
        long nextStatsNanos = System.nanoTime() + STATS_INTERVAL_NANOS;
        try {
            while (running) {
                final Thread sampled = target;
                if (sampled == null) {
                    bumpDropped();
                    sleepQuietly(50L);
                    continue;
                }
                if (!sampled.isAlive()) {
                    // 目标线程没了（服务器已经停了但没人通知）⇒ 自己退出，不空转烧 CPU
                    stop("target_not_alive");
                    return;
                }
                final long captureStart = System.nanoTime();
                final StackTraceElement[] raw;
                try {
                    // 🔴 抓的是【sampled】的栈 —— 绝不是本线程自己的（见类注释第一段）
                    raw = sampled.getStackTrace();
                } catch (Throwable t) {
                    bumpDropped();
                    warnCaptureOnce(t);
                    sleepQuietly(Math.max(1, density.intervalMs));
                    continue;
                }
                final long captureNanos = System.nanoTime() - captureStart;
                final String[] frames = ShanhaiStackRadarFilter.filter(
                        raw, ShanhaiStackRadarFilter.DEFAULT_WHITELIST, depth, MAX_FRAMES);
                publish(frames, System.nanoTime() - captureStart);

                // burst 自保护：慢栈立刻退回默认（规格 2 ms）
                if (density.mode == Density.BURST && captureNanos > BURST_ABORT_NANOS) {
                    abortBurst("slow_reset");
                }

                final long now = System.nanoTime();
                rollSecond(now, frames);
                expire(now);
                if (now - nextStatsNanos >= 0L) {
                    printStats();
                    nextStatsNanos = now + STATS_INTERVAL_NANOS;
                }
                sleepQuietly(waitMillis());
            }
        } catch (Throwable t) {
            // 循环自己炸了：只报一次，然后退出（绝不让异常打到线程组里去印一堆栈）
            warnCaptureOnce(t);
            running = false;
        }
    }

    /** 这一拍之后该睡多久（毫秒）。burst 档固定 {@link #BURST_YIELD_MS}（规格：0 ms 轮转 ＋ 1 ms 让出）。 */
    private long waitMillis() {
        final Density now = density;
        if (now.mode == Density.BURST) {
            return BURST_YIELD_MS;
        }
        return Math.max(1, now.intervalMs);
    }

    /** 把这一拍写进环形缓冲 ＋ 累计成本读数。 */
    private void publish(final String[] frames, final long sampleNanos) {
        final long now = System.nanoTime();
        synchronized (lock) {
            ring[cursor] = frames;
            stamps[cursor] = now;
            cursor++;
            if (cursor == BUFFER_SIZE) {
                cursor = 0;
            }
            if (filled < BUFFER_SIZE) {
                filled++;
            }
            windowSamples++;
            windowNanos += sampleNanos;
            if (sampleNanos > windowMaxNanos) {
                windowMaxNanos = sampleNanos;
            }
        }
        totalSamples.incrementAndGet();
    }

    // ═══════════════════════════════ 归因 dump ═══════════════════════════════

    /**
     * 🔴 <b>归因 dump</b>：取最近 200 ms 的样本，按 {@code 类#方法} 聚合，打出前 5。
     *
     * <p>谁调：① 守卫在判定违规时（外部）；② 本类的自我保护和离线自检。
     * <b>只会读环形缓冲，绝不在这里抓栈</b> —— 从主线程调也是安全的（抓栈是采样线程的活）。
     *
     * <p>输出（{@code samples=0} 或没有 suspect 也照打，{@code top=none} —— <b>不许静默</b>）：
     * <pre>
     *   [SHANHAI-RADAR] suspect reason=&lt;…&gt; window_ms=200 samples=&lt;n&gt; suspect_hits=&lt;s&gt; known_hits=&lt;k&gt; hits=&lt;h&gt;
     *                   top=&lt;类#方法&gt;ratio=&lt;x.x&gt;(n=&lt;n&gt; base=&lt;b&gt;),…
     *   [SHANHAI-RADAR] known reason=&lt;…&gt; window_ms=200 known_hits=&lt;k&gt; known_top=&lt;类#方法&gt;=&lt;n&gt;,…
     * </pre>
     * 两条一起打：{@code suspect} 那条是<b>排名</b>（按突增倍数），{@code known} 那条是<b>对照</b>
     * （已知宿主里谁最吵）—— 没有对照，"top=none"到底是"没异常"还是"分类把它吞了"就分不清。
     * 采样线程已经停了的话，{@code suspect} 行尾多一个 {@code state=stopped}。
     *
     * @param reason 谁为什么要求这一次（写进日志，如 {@code guard_inv_violation}）
     */
    public synchronized void dump(final String reason) {
        final String why = ShanhaiStackRadarFilter.token(reason);
        final Window window = collectWindow();

        // ---- ① suspect 排名：按【突增倍数】而不是绝对计数
        final List<ShanhaiStackRadarFilter.Ranked> ranked = ShanhaiStackRadarFilter.rank(
                window.suspectCounts, window.basePerSecond, (int) WINDOW_MS,
                ShanhaiStackRadarFilter.NOISE_MIN_RATIO);
        final StringBuilder line = new StringBuilder(224);
        line.append("suspect reason=").append(why)
                .append(" window_ms=").append(WINDOW_MS)
                .append(" samples=").append(window.samples)
                .append(" suspect_hits=").append(window.suspectHits)
                .append(" known_hits=").append(window.knownHits)
                .append(" hits=").append(window.hits)
                .append(" top=").append(ShanhaiStackRadarFilter.rankLine(ranked, TOP_N));
        // ⚠️ top= 必须是最后一个字段（规格的行格式），门槛写在 started 行里（noise_ratio=/nominate_ratio=）
        if (!running) {
            line.append(" state=stopped");
        }
        log(line.toString());

        // ---- ② known 对照行（已知宿主里最吵的前三；总是打，否则"top=none"无法与"被分类吞掉"区分）
        log("known reason=" + why
                + " window_ms=" + WINDOW_MS
                + " known_hits=" + window.knownHits
                + " known_top=" + ShanhaiStackRadarFilter.topLine(window.knownCounts, KNOWN_TOP_N));

        // ---- ③ 自适应密度（判据换成 suspect_hits：见 nextDensity 的注释）
        if (window.samples > 0) {
            final int streak;
            if (window.suspectHits == 0L) {
                streak = missStreak + 1;
                missStreak = streak;
            } else {
                streak = 0;
                missStreak = 0;
            }
            applyDensity(nextDensity(density, window.samples,
                    (int) Math.min(Integer.MAX_VALUE, window.suspectHits), streak,
                    System.nanoTime(), baseIntervalMs));
        }
    }

    /**
     * 🔴 <b>把同一批归因结果交给别处用</b>（不打印）：最近 {@link #WINDOW_MS} 里
     * <b>突增倍数达标</b>（{@code ratio >= }{@value ShanhaiStackRadarFilter#NOMINATE_MIN_RATIO}）的
     * {@code 类#方法}，按倍数降序。
     *
     * <p>存在的理由只有一个：让 {@code auto_counter} 那条线能<b>直接</b>拿这份帧去建 strike，
     * 不必去抓自己的日志。帧口径与 {@code HotfixTriageTap#submitViolation} 要的
     * {@code 类#方法}（行号可省）逐字一致 ⇒ 可以直接喂：
     * <pre>
     *   HotfixTriageTap.submitViolation("motion", player, ShanhaiStackRadar.topFrames(8),
     *                                   System.currentTimeMillis());
     * </pre>
     *
     * <p>🔴 <b>没有达标项就返回空表</b>（{@code top=none} 那条日志同理）——
     * <b>绝不</b>退回"绝对计数最高"：那等于"没有突增也硬指一个犯人"，
     * 而热改是要真改字节码的，指错人会改坏正常 mod。
     *
     * @param topN 最多几条（{@code <=0} ⇒ 空表）
     * @return 新列表（可变但没人共享）；没有达标项 ⇒ 空表
     */
    public synchronized List<String> top(final int topN) {
        return ShanhaiStackRadarFilter.keys(rankAt(ShanhaiStackRadarFilter.NOMINATE_MIN_RATIO, topN), topN);
    }

    /**
     * 🆕 按<b>任意门槛</b>取突增排名（诊断用：把门槛压到 0 就能看到"没达标的那几个倍数是多少"）。
     *
     * <p>为什么要这个口子：{@code top=} 只打达标项（规格：{@code ratio < 2} 一律不提名），
     * 于是"没提名"这件事在日志里只有一个 {@code top=none}，看不出是 1.9 还是 0.2。
     * 排查时（以及离线自证里）需要看到那个数。
     *
     * @param minRatio 门槛（{@code 0} = 全都要）
     * @param topN     最多几条
     */
    public synchronized List<ShanhaiStackRadarFilter.Ranked> rankAt(final double minRatio, final int topN) {
        final Window window = collectWindow();
        final List<ShanhaiStackRadarFilter.Ranked> ranked = ShanhaiStackRadarFilter.rank(
                window.suspectCounts, window.basePerSecond, (int) WINDOW_MS, minRatio);
        if (topN <= 0 || ranked.size() <= topN) {
            return ranked;
        }
        return new ArrayList<>(ranked.subList(0, topN));
    }

    /** 一个统计窗口的读数（{@link #dump(String)} 与 {@link #top(int)} 共用同一份扫描）。 */
    private static final class Window {

        int samples;
        /** 窗口内非白名单帧总帧次（= suspect + known）。 */
        long hits;
        /** suspect（不在任何已知宿主命名空间里）的帧次。 */
        long suspectHits;
        /** known（已知宿主 mod 的代码）的帧次。 */
        long knownHits;
        /** suspect：{@code 类#方法 ⇒ 帧次}（只有它参与突增排名）。 */
        final Map<String, Integer> suspectCounts = new LinkedHashMap<>();
        /** known：{@code 类#方法 ⇒ 帧次}（只用来打 {@code known_top=} 对照）。 */
        final Map<String, Integer> knownCounts = new LinkedHashMap<>();
        /** suspect 帧键 ⇒ 基线（每秒命中数的中位数）。 */
        final Map<String, Integer> basePerSecond = new LinkedHashMap<>();
    }

    /**
     * 扫一遍环形缓冲里最近 {@link #WINDOW_MS} 的样本，并按<b>已知宿主</b>把它劈成 suspect / known。
     *
     * <p>⚠️ 只统计"时间戳还在窗口内"的样本：环形缓冲装 512 拍（4 ms 一档 ≈ 2 秒），
     * 但 dump 只看 200 ms —— 窗口更短，才能问出"那一刻"而不是"那两秒平均在干嘛"。
     *
     * <p>分类用的是 {@link ShanhaiStackRadarFilter.KnownHosts}（带缓存，热帧 O(1)）。
     * 🔴 锁序：本方法在 {@code lock} 里调 {@code knownHosts.isKnown}（叶子锁），
     * 而 {@code isKnown} 自己绝不去拿 {@code lock} —— 单向，不会死锁。
     */
    private Window collectWindow() {
        final Window window = new Window();
        final ShanhaiStackRadarFilter.KnownHosts hosts = knownHosts;
        synchronized (lock) {
            final long cutoff = System.nanoTime() - WINDOW_NANOS;
            for (int i = 0; i < filled; i++) {
                // 从最新往回走（i=0 是最新那一拍）
                final int idx = (cursor - 1 - i + BUFFER_SIZE * 2) % BUFFER_SIZE;
                if (stamps[idx] < cutoff) {
                    break;   // 时间倒了：再往前只会更旧
                }
                window.samples++;
                final String[] sample = ring[idx];
                if (sample == null || sample.length == 0) {
                    continue;
                }
                for (int f = 0; f < sample.length; f++) {
                    final String key = sample[f];
                    if (key == null) {
                        continue;
                    }
                    window.hits++;
                    if (hosts.isKnown(key)) {
                        window.knownHits++;
                        bump(window.knownCounts, key);
                    } else {
                        window.suspectHits++;
                        bump(window.suspectCounts, key);
                    }
                }
            }
            // 基线快照（只取窗口里出现过的 suspect 帧，省得把整张表搬出来）
            for (final String key : window.suspectCounts.keySet()) {
                final ShanhaiStackRadarFilter.Baseline baseline = baselines.get(key);
                window.basePerSecond.put(key, Integer.valueOf(baseline == null ? 0 : baseline.median()));
            }
        }
        return window;
    }

    private static void bump(final Map<String, Integer> counts, final String key) {
        final Integer old = counts.get(key);
        counts.put(key, Integer.valueOf(old == null ? 1 : old.intValue() + 1));
    }

    /**
     * 中止 burst 档并退回默认（自保护 ＋ 自检共用）。
     *
     * <p>触发它的两个理由（规格）：{@code slow_reset} = 单次抓栈超过 2 ms；
     * {@code server_stopped} 由 {@link #stop(String)} 直接带上。
     *
     * @param reason {@code slow_reset} 之类
     */
    public void abortBurst(final String reason) {
        final Density now = density;
        if (now.mode != Density.BURST) {
            return;
        }
        log("radar boost aborted reason=" + ShanhaiStackRadarFilter.token(reason));
        applyDensity(new Density(Density.NORMAL, clampInterval(baseIntervalMs), 0L, "reset", 0));
    }

    /**
     * 换档（唯一入口）——<b>每次真的变了就打一行 {@code density}</b>（规格）。
     *
     * <p>行格式：{@code [SHANHAI-RADAR] density interval_ms=<n> reason=miss_streak|reset ttl_ms=<n>}
     */
    private void applyDensity(final Density next) {
        if (next == null) {
            return;
        }
        final Density prev = density;
        if (next.sameAs(prev)) {
            return;
        }
        density = next;
        if (next.mode == Density.BURST) {
            log("radar boost mode=burst duration_ms=" + next.ttlMs);
        }
        log("density interval_ms=" + next.intervalMs + " reason=" + next.reason
                + " ttl_ms=" + next.ttlMs);
    }

    /** 到期回落也走 {@link #nextDensity}：一条判据只有一个实现（避免两处逻辑走偏）。 */
    private void expire(final long nowNanos) {
        applyDensity(nextDensity(density, 0, 0, missStreak, nowNanos, baseIntervalMs));
    }

    // ═══════════════════════════════ 每秒自保护 ═══════════════════════════════

    /**
     * 一圈一秒：攒这一秒的帧计数（<b>只攒 suspect</b>），到点喂基线、判自保护、清账。
     *
     * <p>判据（两条同时成立）：这一秒里某帧出现 <b>&gt; 50 次</b>，且它的出现次数<b>超过这一秒样本数的一半</b>。
     * 后半条是我加的：采样率万一被系统定时器精度压到 100 次/秒以下（Windows 常见），
     * "&gt;50 次"本身就可能永远达不到，"过半"才是"一直卡在里面"的真实含义。
     *
     * <p>🆕 两条改动（都是"已知宿主"带来的）：
     * <ol>
     *   <li><b>只统计 suspect</b>：GT 的机器把主线程占住是<b>正常</b>的，不该触发"被占住"的自保护
     *       —— 否则一个正常 GT 基地每隔 10 秒就来一条 {@code top=none} 的 dump，把真正的异常淹掉。</li>
     *   <li>到点先 {@link #pushBaselines()}：基线是"每秒命中数的中位数"，样本就是这一秒的计数。</li>
     * </ol>
     */
    private void rollSecond(final long nowNanos, final String[] frames) {
        final ShanhaiStackRadarFilter.KnownHosts hosts = knownHosts;
        if (frames != null) {
            for (int i = 0; i < frames.length; i++) {
                final String key = frames[i];
                if (key == null || hosts.isKnown(key)) {
                    continue;   // 🆕 已知宿主不进自保护账（见方法注释 ①）
                }
                final Integer old = secondCounts.get(key);
                secondCounts.put(key, old == null ? 1 : old + 1);
            }
        }
        secondSamples++;
        if (nowNanos - secondStartNanos < SECOND_NANOS) {
            return;
        }
        evaluate(nowNanos);
        pushBaselines();
        secondCounts.clear();
        secondSamples = 0;
        secondStartNanos = nowNanos;
    }

    /**
     * 🆕 把"这一秒里每个 suspect 帧命中了几次"记进各自的基线（60 秒滚动中位数）。
     *
     * <p>要点：<b>没出现的帧也要记 0</b>。只给"出现过的帧"记数会让基线永不衰减 ——
     * 一个只出现过一次的攻击帧会带着那次的高基线活到天荒地老，之后再出现就被当成"正常"。
     *
     * <p>代价：每秒 O(建档帧数)，上限 {@value #BASELINE_MAX_FRAMES} ⇒ 每秒最多一千次 int 写。
     */
    private void pushBaselines() {
        synchronized (lock) {
            for (final Map.Entry<String, ShanhaiStackRadarFilter.Baseline> e : baselines.entrySet()) {
                final Integer count = secondCounts.get(e.getKey());
                e.getValue().push(count == null ? 0 : count.intValue());
            }
            for (final Map.Entry<String, Integer> e : secondCounts.entrySet()) {
                final String key = e.getKey();
                if (baselines.containsKey(key)) {
                    continue;
                }
                if (baselines.size() >= BASELINE_MAX_FRAMES) {
                    break;   // 内存闸门：不再给新帧建档（其基线按 0 ⇒ 第一次出现即视为突增）
                }
                final ShanhaiStackRadarFilter.Baseline baseline =
                        new ShanhaiStackRadarFilter.Baseline(BASELINE_SECONDS);
                baseline.push(e.getValue().intValue());   // 第一秒就把这一秒记进去
                baselines.put(key, baseline);
            }
        }
    }

    private void evaluate(final long nowNanos) {
        String worst = null;
        int worstCount = 0;
        for (final Map.Entry<String, Integer> e : secondCounts.entrySet()) {
            final int count = e.getValue();
            if (count > worstCount) {
                worstCount = count;
                worst = e.getKey();
            }
        }
        final boolean occupied = worst != null
                && worstCount > SELF_HITS_THRESHOLD
                && worstCount * 2 > secondSamples;
        if (!occupied) {
            sustainedSeconds = 0;
            lastWorstKey = null;
            return;
        }
        if (worst.equals(lastWorstKey)) {
            sustainedSeconds++;
        } else {
            lastWorstKey = worst;
            sustainedSeconds = 1;
        }
        if (sustainedSeconds < SELF_SUSTAINED_SECONDS) {
            return;
        }
        if (nowNanos - lastSelfDumpNanos < SELF_DUMP_COOLDOWN_NANOS) {
            return;   // 限流 10 秒（规格）
        }
        lastSelfDumpNanos = nowNanos;
        dump("self_growth_" + sustainedSeconds + "s");
    }

    // ═══════════════════════════════ 成本自证 ═══════════════════════════════

    /**
     * 打一行 {@code stats}（每 60 秒自动一次；自检/命令也可手动触发）。
     *
     * <p>输出（字段与顺序＝规格）：{@code [SHANHAI-RADAR] stats samples=&lt;n&gt; dropped=&lt;n&gt;
     * avg_us=&lt;n&gt; max_us=&lt;n&gt; buffer=&lt;512&gt;}
     *
     * <p>⚠️ 口径写清楚：这四个数是<b>上一行 stats 到这一行之间</b>的窗口读数，不是累计值
     * ——只有窗口读数才能反映"当前这一档密度的真实成本"。累计值在 {@code stopped} 行里给。
     * {@code avg_us} 是 {@link System#nanoTime()} 实测的算术平均（含抓栈 + 过滤 ＋ 入环），<b>不是估算</b>。
     */
    public synchronized void printStats() {
        final long samples;
        final long dropped;
        final long nanos;
        final long max;
        synchronized (lock) {
            samples = windowSamples;
            dropped = windowDropped;
            nanos = windowNanos;
            max = windowMaxNanos;
            windowSamples = 0L;
            windowDropped = 0L;
            windowNanos = 0L;
            windowMaxNanos = 0L;
        }
        final long avgUs = samples > 0L ? (nanos / samples) / 1000L : 0L;
        log("stats samples=" + samples + " dropped=" + dropped
                + " avg_us=" + avgUs + " max_us=" + (max / 1000L) + " buffer=" + BUFFER_SIZE);
    }

    // ═══════════════════════════════ 读数（自检/日志用） ═══════════════════════════════

    /** 采样线程还在跑吗。 */
    public boolean running() {
        return running;
    }

    /** 累计采了多少拍。 */
    public long samplesTotal() {
        return totalSamples.get();
    }

    /** 累计多少次"想采没采成"。 */
    public long droppedTotal() {
        return totalDropped.get();
    }

    /** 当前密度档（自检断言用）。 */
    public Density density() {
        return density;
    }

    /** 采样线程对象（{@code null} = 没起／已停）。 */
    public Thread samplerThread() {
        return worker;
    }

    /** 环形缓冲里现在有几拍（0 = 刚起或刚停）。 */
    public int filled() {
        synchronized (lock) {
            return filled;
        }
    }

    /** 一行判读摘要（停止日志／人工排查用）。 */
    public String statusLine() {
        final Density now = density;
        final int baselineCount;
        synchronized (lock) {
            baselineCount = baselines.size();
        }
        return "running=" + running + " samples_total=" + totalSamples.get()
                + " dropped_total=" + totalDropped.get()
                + " depth=" + depth + " base_interval_ms=" + baseIntervalMs
                + " density=[" + now + "] filled=" + filled()
                + " miss_streak=" + missStreak
                + " known_hosts=" + knownHosts.size()
                + " baselines=" + baselineCount + "/" + BASELINE_MAX_FRAMES
                + " baseline_window_s=" + BASELINE_SECONDS
                + " nominate_ratio=" + ShanhaiStackRadarFilter.NOMINATE_MIN_RATIO
                + " noise_ratio=" + ShanhaiStackRadarFilter.NOISE_MIN_RATIO;
    }

    /** 🆕 已知宿主前缀表（日志判读用；{@code null} → 空表）。 */
    public String[] knownNamespaces() {
        final String[] all = knownHosts.namespaces();
        return all == null ? new String[0] : all.clone();
    }

    /** 🆕 已建档的基线个数（自检用）。 */
    public int baselineCount() {
        synchronized (lock) {
            return baselines.size();
        }
    }

    /** 🆕 某个帧现在的基线（每秒命中数中位数；没建档 ⇒ 0）。自检用。 */
    public int baselineOf(final String frameKey) {
        synchronized (lock) {
            final ShanhaiStackRadarFilter.Baseline baseline = baselines.get(frameKey);
            return baseline == null ? 0 : baseline.median();
        }
    }

    // ═══════════════════════════════ 小工具 ═══════════════════════════════

    private void stampsReset() {
        Arrays.fill(stamps, 0L);
    }

    private void bumpDropped() {
        totalDropped.incrementAndGet();
        synchronized (lock) {
            windowDropped++;
        }
    }

    private void warnCaptureOnce(final Throwable t) {
        if (captureWarned.compareAndSet(false, true)) {
            sink.warn(PREFIX + " warn capture_failed err="
                    + ShanhaiStackRadarFilter.token(simpleName(t))
                    + " detail=" + ShanhaiStackRadarFilter.token(t == null ? "?" : t.getMessage())
                    + "（只报这一次，之后静默；雷达继续跑）");
        }
    }

    private void log(final String body) {
        if (sink == null) {
            return;
        }
        try {
            sink.line(PREFIX + " " + body);
        } catch (Throwable ignored) {
            // 日志失败不许把采样线程带崩
        }
    }

    /**
     * 🔴 睡眠（<b>不用</b> {@code LockSupport.parkNanos}）。
     *
     * <p>实测（Win11 / JDK 21 / 纯 JVM）：{@code parkNanos(4ms)} 实际睡 <b>15.58 ms</b>、
     * {@code parkNanos(1ms)} 睡 <b>15.26 ms</b>（等待超时走的是 15.6 ms 的系统节拍），
     * 而 {@code Thread.sleep(4)} 只睡 <b>4.55 ms</b>、{@code Thread.sleep(1)} 睡 <b>1.90 ms</b>。
     * <p>⇒ 换成 park 的写法，4 ms 档实测只有 65 次/秒，1 ms 档也还是 65 次/秒 —— 升密度完全失效。
     * 换成 {@code Thread.sleep} 之后：4 ms 档 ≈ 220 次/秒、1 ms 档 ≈ 520 次/秒，
     * <b>成本不变</b>（睡得越准不代表烧 CPU，本来就是在等）。
     *
     * <p>打断＝有人要停（{@link #stop(String)} 用 {@code interrupt()} 叫醒）：中断标志就地清掉，
     * 由循环去读 {@link #running} 决定退出，不靠异常控制流程。
     */
    private static void sleepQuietly(final long millis) {
        if (millis <= 0L) {
            Thread.yield();
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            // 就地清掉中断标志：停不停由 running 说了算（见方法注释）
        }
    }

    private static String safeName(final Thread thread) {
        if (thread == null) {
            return "?";
        }
        try {
            return ShanhaiStackRadarFilter.token(thread.getName());
        } catch (Throwable ignored) {
            return "?";
        }
    }

    private static String simpleName(final Throwable t) {
        return t == null ? "unknown" : t.getClass().getSimpleName();
    }
}
