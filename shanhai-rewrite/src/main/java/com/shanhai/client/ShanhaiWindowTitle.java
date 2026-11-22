package com.shanhai.client;

import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.shanhai.ShanhaiMod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.forgespi.language.IModFileInfo;
import net.minecraftforge.forgespi.locating.IModFile;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * 山海客户端窗口标题 —— <b>默认自己写（老样子），装了 add 就把写标题这件事交给 sink</b>。
 *
 * <h4>两种形态（2026-11-21 用户拍板：「没 add = 标题完全跟老的一样；装了 add = 走 add 那条线」）</h4>
 * <ul>
 *   <li><b>无 sink（默认）</b>：本类每 tick/每帧拼老格式标题，<b>主线程直接
 *       {@code Minecraft.getWindow().setTitle(...)}</b>（值没变就不写）。卡死时冻住 —— 这正是"和老的一样"，
 *       不做任何优化。</li>
 *   <li><b>有 sink（装了 add）</b>：每拍把<b>快照</b>（{@link TitleSnapshot}：时间点 + 累计量 + 拼好的串）
 *       交给 {@link TitleSink#submit}，本类<b>一行 {@code setTitle} 都不写</b>；sink 撤掉立刻恢复自己写
 *       （{@link #removeSink}）。送时间点而不是只送字，是为了让子进程在游戏卡住时也能自己把秒数算下去。</li>
 * </ul>
 *
 * <h4>标题格式（旧 jar 常量池逐字，2026-11-21 还原）</h4>
 * <pre>
 * GregTech Leisure:Shanhai Re-release version  Welcome:名    版本号:哈希   构建日期:11月8日   加载时间:0小时12分钟3秒 [|]
 * GregTech Leisure:Shanhai Re-release version  Welcome:名    版本号:哈希   构建日期:11月8日   游玩时间:0小时3分钟12秒 15tick
 * </pre>
 * 加载中转圈 {@code [|]/[/]/[-]/[\]} 200 ms 一帧；界面干等时停转；游玩带 {@code Ntick}。
 *
 * <h4>时间源（不依赖日志/tick 计数）</h4>
 * {@code anchorEpochMs}=进程启动；{@code pausedAccumMs/pausedSinceEpochMs}=界面干等；
 * {@code playedAccumMs/worldEnterEpochMs}=游玩累计。全部用 {@code currentTimeMillis} 现算。
 *
 * <h4>窗口出生那一帧</h4>
 * {@link #titleForStartup()} 给 {@code ShanhaiWindowTitleMixin} 的 {@code createTitle} 用 ——
 * 两条线都要（没 add 时它是首帧标题；有 add 时它会被 add 的第一拍覆盖）。
 */
@Mod.EventBusSubscriber(modid = ShanhaiMod.MOD_ID, value = Dist.CLIENT)
public final class ShanhaiWindowTitle {

    private ShanhaiWindowTitle() {
    }

    /** 取不到真值时显示的占位（本工程铁律：宁可缺，不可假）。 */
    private static final String NA = "N/A";

    /** 阶段（只用于日志/首帧；标题拼法不依赖它）。 */
    private enum Phase {
        LOADING,
        IDLE,
        INGAME
    }

    /**
     * add 侧接管标题的端口（本体只定义、不实现）。
     *
     * <p>送的是<b>快照</b>而不是成品字符串：装了扩展之后，标题由独立子进程每 100 ms 现拼，
     * 它需要的是「时间点 + 累计量」，不是某一帧算出来的字。只送字的话，游戏一卡主子进程就
     * 拿不到新字，标题也就不动了。
     */
    public interface TitleSink {
        void submit(TitleSnapshot snapshot);
    }

    /**
     * 一拍标题的<b>纯数据快照</b>（零 Minecraft / 零窗口依赖，可直接离线单测）。
     *
     * <h4>为什么要有它</h4>
     * <p>旧形态是「本体把成品字符串交给 add」⇒ 本体一卡就不再有新字符串 ⇒ 卡死期间标题冻住。
     * 现在本体只送<b>时间点与累计量</b>（这些值在游戏卡住时本来就不变），由子进程用
     * {@code currentTimeMillis} 现算，所以主线程卡多久，标题里的秒数照样在走。
     *
     * <h4>与状态文件字段逐项同义</h4>
     * <p>{@code anchorEpochMs / worldEnterEpochMs / playedAccumMs / pausedAccumMs / pausedSinceEpochMs /
     * everInWorld} 与 {@code BackstageState} 里的同名字段<b>同口径</b>；add 侧原样落盘，不改语义。
     * <b>一个逻辑都不加</b>（不钳位、不补默认值）—— 语义只在本类 {@link #snapshotFor(long)} 里算一次。
     */
    public static final class TitleSnapshot {

        /** 完整旧格式标题串（没装扩展时用它 {@code setTitle}；装了扩展也留作兜底）。 */
        public final String title;
        /** {@code "LOADING"} / {@code "INGAME"} / {@code "IDLE"}（关闭时另有 {@code "STOP"}，本体不发）。 */
        public final String phase;
        /** 加载计时的零点 = 进程启动时刻（与旧版 {@code 加载时间} 同一个零点；不是"这一阶段"的起点）。 */
        public final long anchorEpochMs;
        /** 本场进世界的时刻（epoch 毫秒）；不在世界 = 0。 */
        public final long worldEnterEpochMs;
        /** 已结束存档场次累计的游玩毫秒（退出存档不清零）。 */
        public final long playedAccumMs;
        /** 「人待在界面里干等」已累计的毫秒。 */
        public final long pausedAccumMs;
        /** 当前这一段干等的起点（epoch 毫秒）；0 = 此刻不在干等（转圈继续转）。 */
        public final long pausedSinceEpochMs;
        /** 是否进过世界（锁存；决定标签是「加载时间」还是「游玩时间」）。 */
        public final boolean everInWorld;
        /** {@code 版本号} 那一段（取不到 = {@code N/A}）。 */
        public final String versionText;
        /** {@code 构建日期} 那一段（取不到 = {@code N/A}）。 */
        public final String buildDateText;
        /** {@code Welcome:} 那一段（取不到 = {@code N/A}）。 */
        public final String welcome;

        public TitleSnapshot(String title, String phase, long anchorEpochMs, long worldEnterEpochMs,
                             long playedAccumMs, long pausedAccumMs, long pausedSinceEpochMs,
                             boolean everInWorld, String versionText, String buildDateText, String welcome) {
            this.title = title;
            this.phase = phase;
            this.anchorEpochMs = anchorEpochMs;
            this.worldEnterEpochMs = worldEnterEpochMs;
            this.playedAccumMs = playedAccumMs;
            this.pausedAccumMs = pausedAccumMs;
            this.pausedSinceEpochMs = pausedSinceEpochMs;
            this.everInWorld = everInWorld;
            this.versionText = versionText;
            this.buildDateText = buildDateText;
            this.welcome = welcome;
        }
    }

    /** 当前已装上的 sink；null = 本体自己写标题（默认，老样子）。 */
    private static volatile TitleSink sink;
    /** 当前 sink 的 owner（多 owner 时后者覆盖前者）。 */
    private static volatile String sinkOwner;
    /** 上一次真正写进窗口的标题（值没变就不写）。 */
    private static volatile String lastAppliedTitle;
    /**
     * 标题里 {@code Welcome:} 那一段的缓存（只在主线程刷新）。
     *
     * <p>为什么要缓存：{@link #snapshotFor(long)} 必须是<b>纯函数</b>（零 Minecraft 调用，能离线单测），
     * 而玩家名只能从 {@code Minecraft} 取。所以由 {@link #writeTitle()} / {@link #titleForStartup()}
     * 在主线程先 {@link #refreshWelcomeCache()}，纯函数只读这里。取不到就是 {@link #NA}（宁可缺，不可假）。
     */
    private static volatile String cachedWelcome = NA;

    /** 构建期注入的构建日期（与横幅同源，见类注释字段 ③）。 */
    private static final String BUILD_PROPS_RESOURCE = "/assets/shanhai/build.properties";
    private static final String BUILD_DATE_KEY = "build_date";
    /** 只认 {@code yyyy-MM-dd}；占位符原文 {@code ${build_date}} 到不了这里就已经被拒。 */
    private static final Pattern BUILD_DATE_SHAPE = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");

    /** 版本号取 SHA-256 的前 12 个十六进制字符（用户给的范围是 12–16）。 */
    private static final int JAR_HASH_CHARS = 12;

    /** 算哈希失败后隔多久才允许再试（纳秒）：500 ms（限流，不是判死刑）。 */
    private static final long HASH_RETRY_BACKOFF_NANOS = 500_000_000L;
    /** 哈希失败原因日志最多打几条。 */
    private static final int DIAG_HASH_FAIL_LOGS = 3;
    /** 启动进程失败后隔多久再试（毫秒）：5 秒。 */
    private static final long START_RETRY_BACKOFF_MS = 5_000L;

    // ─────────────────────────── 哈希来源标记 ───────────────────────────
    private static final String SOURCE_FML = "fml";
    private static final String SOURCE_SELF_JAR_RESOURCE = "selfJar";
    private static final String SOURCE_CODE_SOURCE = "codeSource";
    private static final String SOURCE_NONE = "none";

    /** 本 mod jar 的 SHA-256 前 12 位；null = 还没算出来。 */
    private static String jarHash;
    /** 成功取到过的哈希正本：非 null 之后就只认它，永不退回 {@link #NA}。 */
    private static String hashKnownGood;
    /** 失败后何时才允许再试（{@link System#nanoTime()} 口径）。 */
    private static long hashRetryNotBeforeNanos;
    /** 哈希尝试次数（只用于日志）。 */
    private static int hashAttempts;
    /** 已经打过几条失败原因日志。 */
    private static int hashFailLogs;
    /** 这次哈希走的是哪条路。 */
    private static String jarHashSource;
    /** 算哈希用的那个文件（只用于日志）。 */
    private static String jarHashPath;
    /** 全失败时的简短原因。 */
    private static String jarHashDetail;

    /** 构建日期的中文形态（例如 {@code 11月8日}）；null = 还没读 / 读不到。 */
    private static String buildDateCn;
    /** 已经尝试过读构建日期。 */
    private static boolean buildDateTried;

    // ─────────────────────────── 阶段状态（volatile，主线程写） ───────────────────────────
    /**
     * 进程启动时刻（epoch 毫秒）：初始加载的计时零点。
     *
     * <p>用 {@code ManagementFactory.getRuntimeMXBean().getStartTime()}（进程启动的墙上时钟），
     * 不用「本类初始化时刻」：本类由 Forge 在模组发现之后才 {@code Class.forName}，那时启动已经过去几秒。
     * 取不到就退回「现在」（绝不留 0）。
     */
    private static final long LOADING_ANCHOR_EPOCH_MS = initialLoadingAnchorEpochMs();

    /** 当前阶段；初始 = LOADING（窗口出生时游戏确实在加载）。 */
    private static volatile Phase phase = Phase.LOADING;
    /** 当前阶段起点（epoch 毫秒）；0 = 不计时（IDLE）。只用于日志。 */
    private static volatile long startEpochMs = LOADING_ANCHOR_EPOCH_MS;
    /**
     * 「人待在界面里干等」已累计的毫秒（旧版 {@code menuPausedNanos}）。
     * 现在由子进程用 {@code now - anchor - pausedAccum - 当前干等} 现算加载时间。
     */
    private static volatile long pausedAccumMs;
    /** 当前这一段干等的起点（epoch 毫秒）；0 = 不在干等。 */
    private static volatile long pausedSinceEpochMs;
    /** 已结束存档场次累计的游玩毫秒（旧版 {@code playedAccumNanos}，退出存档不清零）。 */
    private static volatile long playedAccumMs;
    /** 当前这一场进入存档的时刻（epoch 毫秒）；0 = 不在存档里。 */
    private static volatile long worldEnterEpochMs;
    /** 是否进过世界（锁存；决定标签「加载时间」还是「游玩时间」）。 */
    private static volatile boolean everInWorld;
    /** 上一拍是否在存档里（用来发现进/出存档的跳变）。 */
    private static volatile boolean wasInWorld;
    /** 游戏窗口句柄（HWND）；0 = 还没取到。只在主线程写。 */
    private static volatile long hwnd;
    /** 游戏进程号（子进程用它判断父进程是否还活着）。 */
    private static volatile long gamePid = currentPid();
    /** 下一次允许尝试启动进程的时刻（epoch 毫秒）；0 = 立刻允许。 */
    private static volatile long nextStartAttemptMs;
    /** 启动失败的日志是否已经打过（避免每 tick 刷屏）。 */
    private static volatile boolean startFailureLogged;
    /** 窗口出生标题日志是否已经打过（{@code createTitle} 每次 setScreen 都会被调，只打第一行）。 */
    private static volatile boolean startupTitleLogged;

    // ─────────────────────────── 事件入口 ───────────────────────────

    /**
     * 客户端 tick 末发布一次状态（<b>不写标题</b>）。
     *
     * <p>为什么是 {@code Phase.END}：本 tick 的登录/维度切换都已完成，player/level 已稳定。
     */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        publishState();
    }

    /** 每帧渲染末再发布一次（屏幕变化/加载遮罩在这条路径上更及时）。 */
    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        publishState();
    }

    /**
     * <b>加载遮罩每画一帧</b>就调一次 —— 给 {@code com.shanhai.mixin.ShanhaiLoadingOverlayMixin} 用。
     *
     * <p>遮罩在画 = 游戏在干活 ⇒ 至少是 LOADING；本方法只发布状态，不写标题。
     * （遮罩在资源重载时也会画，那时若已在世界，{@link #publishState()} 会按通用属性判回 INGAME。）
     */
    public static void tickFromOverlay() {
        try {
            final Minecraft minecraft = Minecraft.getInstance();
            if (minecraft == null) {
                return;
            }
            if (!minecraft.isSameThread()) {
                return;   // 只许主线程碰窗口/玩家名
            }
            if (minecraft.player == null) {
                hintLoadingStarted();
            } else {
                publishState();
            }
        } catch (Throwable ignored) {
            // 纯装饰：从加载遮罩里抛出去会把加载页搞崩
        }
    }

    /**
     * <b>加载期心跳</b> —— 给 {@code com.shanhai.mixin.ShanhaiLoaderHeartbeatMixin} 用：
     * 由 Forge 自己的加载进度泵在加载期反复调用。
     *
     * <p>这里是<b>最早的启动点</b>：加载泵第一拍时窗口已经存在（{@code Minecraft.<init>} 已跑过），
     * 一定早于「主线程第一次卡住」。{@link BackstageProcess#bootstrap} 幂等，失败会 5 秒后重试。
     */
    public static void tickFromLoader() {
        try {
            final Minecraft minecraft = Minecraft.getInstance();
            if (minecraft == null || !minecraft.isSameThread()) {
                // 不在游戏主线程：绝不碰 GLFW（窗口句柄只能在主线程取），也不发布状态
                return;
            }
            hintLoadingStarted();
        } catch (Throwable ignored) {
            // 从 Forge 的加载泵里抛出去会打断模组加载
        }
    }

    /**
     * <b>窗口出生那一帧</b>的标题串 —— 给 {@code com.shanhai.mixin.ShanhaiWindowTitleMixin} 用。
     *
     * <p>与后台进程共用 {@link BackstageTitle#compose}、共用同一份阶段状态 ⇒ 原版在
     * {@code setScreen} 时写的那一次（{@code updateTitle → createTitle → setTitle}）与后台进程写的是
     * <b>同一个内容</b>，不会闪烁。
     *
     * <p>🔴 返回值语义：<b>拿不到就返回 {@code null}</b> ⇒ 调用方保留原版标题；整段包在
     * {@code catch (Throwable)} 里（这是客户端启动链路上的早期插入点）。
     */
    public static String titleForStartup() {
        try {
            final long now = System.currentTimeMillis();
            refreshWelcomeCache();          // 唯一碰 Minecraft 的那一步（主线程）
            final String title = composeTitle(now);
            if (!startupTitleLogged) {
                startupTitleLogged = true;
                try {
                    ShanhaiMod.LOGGER.info("[SHANHAI-TITLE] 窗口出生标题已给出: {} (mode=startup)", title);
                } catch (Throwable ignored) {
                    // 日志打不出来不影响标题本身
                }
            }
            return title;
        } catch (Throwable ignored) {
            return null;   // 拿不到 ⇒ 让原版标题生效，绝不外抛
        }
    }

    // ─────────────────────────── 状态发布 ───────────────────────────

    /**
     * 判定当前阶段 + 起点，并把状态交给 {@link BackstageProcess#publish}（内容没变就不写盘）。
     *
     * <p>整段 {@code try/catch (Throwable)}：发布失败只影响标题，不许影响游戏。
     */
    public static void publishState() {
        try {
            final Minecraft minecraft = Minecraft.getInstance();
            if (minecraft == null || minecraft.getWindow() == null) {
                return;
            }
            if (!minecraft.isSameThread()) {
                // 非主线程：不碰 GLFW、不读玩家名（GLFW 有线程亲和性，晚一拍没关系）
                return;
            }

            final long now = System.currentTimeMillis();
            final boolean nowInWorld = minecraft.player != null && minecraft.level != null;
            final Screen screen = minecraft.screen;
            final boolean idlingInMenu = minecraft.player == null
                    && screen != null
                    && minecraft.getOverlay() == null
                    && minecraft.getSingleplayerServer() == null;

            // ① 加载计时：人「待在界面里干等」的那段时间不累计（与旧版 refresh() 同一套状态机）
            if (!everInWorld) {
                if (idlingInMenu) {
                    if (pausedSinceEpochMs == 0L) {
                        pausedSinceEpochMs = now;
                    }
                } else if (pausedSinceEpochMs != 0L) {
                    pausedAccumMs += now - pausedSinceEpochMs;
                    pausedSinceEpochMs = 0L;
                }
            }
            // ② 进/出存档：进 = 从 0 计这一场；出 = 把这一场并进累计（退出不清零）
            if (nowInWorld && !wasInWorld) {
                worldEnterEpochMs = now;
                everInWorld = true;
                pausedSinceEpochMs = 0L;
            } else if (!nowInWorld && wasInWorld) {
                if (worldEnterEpochMs != 0L) {
                    playedAccumMs += now - worldEnterEpochMs;
                }
                worldEnterEpochMs = 0L;
            }
            wasInWorld = nowInWorld;

            final Phase newPhase = nowInWorld
                    ? Phase.INGAME
                    : (idlingInMenu ? Phase.IDLE : Phase.LOADING);
            if (newPhase != phase) {
                startEpochMs = newPhase == Phase.IDLE ? 0L : now;
                phase = newPhase;
            }
            if (hwnd == 0L) {
                final long captured = captureWindowHandle();
                if (captured != 0L) {
                    hwnd = captured;
                    logInfo("window hwnd captured=0x" + Long.toHexString(captured));
                }
            }
            writeTitle();
        } catch (Throwable ignored) {
            // 装饰性功能：不记日志、不外抛
        }
    }

    /**
     * {@code ScreenEvent.Opening} 的快速通道：<b>开始加载存档</b>的那一刻立刻把阶段置成 LOADING。
     *
     * <p>调用点：{@code BackstageForgeEvents#onScreenOpening} 看到
     * {@code LevelLoadingScreen} / {@code ReceivingLevelScreen} 时调用
     * （1.20.1 只有这两个加载屏：{@code com/shanhai} 之外的全 jar 类表里没有
     * {@code DownloadingTerrainScreen} / {@code LoadLevelScreen}，那两个是别的版本的名字）。
     * 它比下一拍 tick 判定早最多 50 ms —— 对标题来说几乎看不出，但"进入加载那一刻"
     * 的起点因此更准（{@code startEpochMs} 不再被四舍五入到最近的 tick）。
     */
    public static void hintLoadingStarted() {
        try {
            if (phase != Phase.LOADING) {
                phase = Phase.LOADING;
                startEpochMs = System.currentTimeMillis();
            }
            writeTitle();
        } catch (Throwable ignored) {
            // 同上：纯装饰
        }
    }

    // ─────────────────────────── 标题端口（add 接管用） ───────────────────────────

    /**
     * 装上标题 sink（add 侧调用）。装上之后本体<b>一行 {@code setTitle} 都不写</b>，
     * 每拍把 {@link TitleSnapshot} 交给 sink；sink 抛异常只记日志，不影响游戏。
     *
     * @return 是否装上（ownerId/sink 为 null 时 false）
     */
    public static boolean installSink(String ownerId, TitleSink newSink) {
        try {
            if (ownerId == null || newSink == null) {
                return false;
            }
            sink = newSink;
            sinkOwner = ownerId;
            lastAppliedTitle = null;
            logInfo("title sink installed owner=" + ownerId);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 撤掉 sink（owner 不匹配时不动）；撤掉后下一拍本体恢复自己写。 */
    public static void removeSink(String ownerId) {
        try {
            if (sinkOwner != null && ownerId != null && !sinkOwner.equals(ownerId)) {
                return;
            }
            if (sink != null) {
                logInfo("title sink removed owner=" + sinkOwner);
            }
            sink = null;
            sinkOwner = null;
            lastAppliedTitle = null;
        } catch (Throwable ignored) {
            // 装饰性
        }
    }

    // ─────────────────────────── 唯一写点 ───────────────────────────

    /**
     * <b>唯一写点</b>：sink 非空 ⇒ 只交快照给 sink（<b>本体一行 {@code setTitle} 都不写</b>）；
     * sink 为空 ⇒ 主线程 {@code setTitle(snapshot.title)}（值没变就不写）。
     *
     * <p>所有对 Minecraft / 窗口的调用都在这里；{@link #snapshotFor(long)} 本身是纯函数。
     */
    private static void writeTitle() {
        final TitleSink current = sink;
        final long now = System.currentTimeMillis();
        refreshWelcomeCache();          // 唯一碰 Minecraft 的那一步（主线程）
        final TitleSnapshot snapshot = snapshotFor(now);
        if (current != null) {
            try {
                current.submit(snapshot);
            } catch (Throwable t) {
                logInfo("title sink submit failed " + t.getClass().getSimpleName());
            }
            return;
        }
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getWindow() == null) {
            return;
        }
        if (snapshot.title.equals(lastAppliedTitle)) {
            return;
        }
        lastAppliedTitle = snapshot.title;
        minecraft.getWindow().setTitle(snapshot.title);
    }

    /**
     * <b>把本类此刻的计时状态拍成一张纯数据快照</b>（{@code title} 用现有的 {@link #composeTitle(long)}）。
     *
     * <p>🔴 <b>纯函数</b>：不碰 {@code Minecraft}、不碰窗口、不碰 GLFW、不改任何字段 —— 所以可以离线单测，
     * 也可以被 add 侧在非主线程安全调用。玩家名走 {@link #cachedWelcome}（由主线程刷新），
     * 版本号/构建日期走已有的缓存取值路径。
     *
     * <p>字段语义与「装 add 时子进程每 100 ms 现拼」严格对齐：只要 {@code anchorEpochMs > 0}，
     * 子进程就能在<b>游戏主线程卡住时</b>照样把秒数算下去。
     *
     * @param nowMs 现在（epoch 毫秒）；由调用方给，便于假时钟测试
     */
    public static TitleSnapshot snapshotFor(long nowMs) {
        return new TitleSnapshot(
                composeTitle(nowMs),
                phase.name(),
                LOADING_ANCHOR_EPOCH_MS,
                worldEnterEpochMs,
                playedAccumMs,
                pausedAccumMs,
                pausedSinceEpochMs,
                everInWorld,
                versionText(),
                buildDateText(),
                cachedWelcome);
    }

    /**
     * 刷新 {@link #cachedWelcome}（<b>唯一</b>会读 Minecraft 的玩家名路径）。
     *
     * <p>只锁存「非占位」的值：拿不到就拿不到，下一拍再试 —— 与旧版每拍现取的行为一致，
     * 但把「碰 Minecraft」留在主线程、把纯函数留干净。
     */
    private static void refreshWelcomeCache() {
        try {
            final String name = playerNameAtStartup();
            if (name != null && !name.isEmpty() && !NA.equals(name)) {
                cachedWelcome = name;
            }
        } catch (Throwable ignored) {
            // 读不到就保持上一个值（初始是 NA）
        }
    }

    // ─────────────────────────── 老格式标题拼法 ───────────────────────────

    /** 加载中转圈帧（旧 jar 常量池逐字，200 ms 一帧）。 */
    private static final String[] LOADING_FRAMES = {"[|]", "[/]", "[-]", "[\\]"};

    /** 拼旧格式标题。**只读本类字段 + 纯计算**（玩家名读缓存，不碰 Minecraft）。 */
    private static String composeTitle(long now) {
        final boolean played = everInWorld;
        final long elapsed = played ? playedElapsedMs(now) : loadingElapsedMs(now);
        final String value = durationText(elapsed)
                + (played ? " " + ((elapsed / 50L) % 20L) + "tick" : "");
        return "GregTech Leisure:Shanhai Re-release version"
                + "  Welcome:" + cachedWelcome
                + "    版本号:" + versionText()
                + "   构建日期:" + buildDateText()
                + "   " + (played ? "游玩时间" : "加载时间") + ":" + value
                + animation(now);
    }

    private static long loadingElapsedMs(long now) {
        long elapsed = now - LOADING_ANCHOR_EPOCH_MS - pausedAccumMs;
        if (pausedSinceEpochMs > 0L) {
            elapsed -= now - pausedSinceEpochMs;
        }
        return Math.max(0L, elapsed);
    }

    private static long playedElapsedMs(long now) {
        long elapsed = playedAccumMs;
        if (worldEnterEpochMs > 0L) {
            elapsed += now - worldEnterEpochMs;
        }
        return Math.max(0L, elapsed);
    }

    private static String durationText(long ms) {
        final long total = Math.max(0L, ms) / 1000L;
        return (total / 3600L) + "小时" + ((total % 3600L) / 60L) + "分钟" + (total % 60L) + "秒";
    }

    private static String animation(long now) {
        if (everInWorld || pausedSinceEpochMs > 0L) {
            return "";
        }
        return " " + LOADING_FRAMES[(int) ((now / 200L) % LOADING_FRAMES.length)];
    }

    /** 游戏窗口句柄（add 侧起子进程时用作初始值）。 */
    public static long windowHandle() {
        return hwnd;
    }

    /** 游戏进程号。 */
    public static long gamePid() {
        return gamePid;
    }

    private static void logInfo(String message) {
        try {
            ShanhaiMod.LOGGER.info("[SHANHAI-BACKSTAGE] {}", message);
        } catch (Throwable ignored) {
            // 日志系统还没就绪
        }
    }

    private static long currentPid() {
        try {
            return ProcessHandle.current().pid();
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    // ─────────────── 窗口句柄 / 本 mod jar 路径（给子进程 / 状态文件用） ───────────────

    /**
     * 取游戏窗口的 Win32 句柄（HWND）。
     *
     * <p>路径：{@code Minecraft.getWindow().getWindow()}（GLFW 句柄，long）→
     * {@code GLFWNativeWin32.glfwGetWin32Window(glfwHandle)}。
     * <b>只许在主线程调用</b>（GLFW 有线程亲和性）；本类所有调用点都是主线程。
     *
     * @return HWND；取不到 = 0（调用方据此走「只算不写」）
     */
    public static long captureWindowHandle() {
        try {
            final Minecraft minecraft = Minecraft.getInstance();
            if (minecraft == null || minecraft.getWindow() == null) {
                return 0L;
            }
            final long glfwHandle = minecraft.getWindow().getWindow();
            if (glfwHandle == 0L) {
                return 0L;
            }
            return org.lwjgl.glfw.GLFWNativeWin32.glfwGetWin32Window(glfwHandle);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    /**
     * 本 mod 自己的 jar（开发环境是 classes 目录）的路径 —— 拼给子进程的 classpath 用。
     *
     * <p>生产环境 {@code getProtectionDomain().getCodeSource().getLocation()} 是 {@code union:} 壳
     * （见 {@link #jarHash12()} 的取证），所以先走 FML 的真实路径，再走本类 jar 的资源 URL，
     * 最后才是 code source（开发环境 = 目录，也接受）。
     */
    public static String ownJarOrClassesPath() {
        try {
            final Path fml = fmlModJarPath();
            if (isJarFile(fml)) {
                return fml.toString();
            }
        } catch (Throwable ignored) {
            // 继续退路
        }
        try {
            final Path self = selfJarResourcePath();
            if (isJarFile(self)) {
                return self.toString();
            }
        } catch (Throwable ignored) {
            // 继续退路
        }
        try {
            final CodeSource codeSource = ShanhaiWindowTitle.class.getProtectionDomain().getCodeSource();
            if (codeSource != null && codeSource.getLocation() != null) {
                final URL location = codeSource.getLocation();
                if ("file".equalsIgnoreCase(location.getProtocol())) {
                    return Paths.get(location.toURI()).toString();
                }
                if ("union".equalsIgnoreCase(location.getProtocol())) {
                    final Path path = unionJarPath(location.toString());
                    return path == null ? null : path.toString();
                }
            }
        } catch (Throwable ignored) {
            // 放弃：子进程会因为没有本 mod jar 而起不来，主线程侧已有降级
        }
        return null;
    }

    // ─────────────────────────── 基础字段 ───────────────────────────

    /** 玩家名：客户端当前用户名（启动器给的名字在进世界之前就可用）。 */
    private static String playerName(Minecraft minecraft) {
        try {
            final User user = minecraft.getUser();
            if (user != null) {
                final String name = user.getName();
                if (name != null && !name.isEmpty()) {
                    return name;
                }
            }
        } catch (Throwable ignored) {
            // 落到下面的退路
        }
        try {
            if (minecraft.player != null) {
                final String name = minecraft.player.getName().getString();
                if (name != null && !name.isEmpty()) {
                    return name;
                }
            }
        } catch (Throwable ignored) {
            // 落到 NA
        }
        return NA;
    }

    /**
     * 玩家名（客户端当前用户名；启动器给的名字在进世界之前就可用）。
     *
     * <p>🔴 <b>会碰 Minecraft</b> ⇒ 只允许 {@link #refreshWelcomeCache()} 调它（主线程）。
     * 标题拼法本身读 {@link #cachedWelcome}，保证 {@link #snapshotFor(long)} 是纯函数。
     */
    private static String playerNameAtStartup() {
        try {
            final Minecraft minecraft = Minecraft.getInstance();
            if (minecraft != null) {
                return playerName(minecraft);
            }
        } catch (Throwable ignored) {
            // 落到 NA
        }
        return NA;
    }

    /** {@link #LOADING_ANCHOR_EPOCH_MS} 的求值核。 */
    private static long initialLoadingAnchorEpochMs() {
        try {
            final long startMillis = ManagementFactory.getRuntimeMXBean().getStartTime();
            if (startMillis > 0L) {
                final long sinceMillis = System.currentTimeMillis() - startMillis;
                if (sinceMillis >= 0L && sinceMillis < 3_600_000L) {
                    return startMillis;
                }
            }
        } catch (Throwable ignored) {
            // 运行时不带 java.management 模块
        }
        return System.currentTimeMillis();
    }

    /** 版本号（= 本 mod jar 的 SHA-256 前 12 位；取不到 = {@code 不可用}）。 */
    public static String versionText() {
        return jarHash12();
    }

    /** 构建日期（{@code 11月8日}；取不到 = {@code 不可用}）。 */
    public static String buildDateText() {
        return buildDateCn();
    }

    // ─────────────────────────── jar 哈希（原逻辑保留） ───────────────────────────

    /**
     * 本 mod 自身 jar 的 SHA-256 前 12 位；<b>成功一次就锁存</b>，失败<b>不封版</b>（限流后重试）。
     *
     * <p>生产环境里 ModLauncher 给每个 mod jar 挂的是 union 文件系统，code source 是
     * {@code union:/C:/…/mods/shanhai-0.1.0.jar%2362!/} ⇒ 主路必须用 FML 的
     * {@code getFilePath()}（真实路径）；退路是本类 jar 的资源 URL 与 code source。
     * 三条全失败才显示 {@code 不可用}，且下一轮还会重试。
     */
    private static synchronized String jarHash12() {
        final String known = hashKnownGood;
        if (known != null) {
            return known;
        }
        final long now = System.nanoTime();
        if (now < hashRetryNotBeforeNanos) {
            return NA;
        }
        hashAttempts++;
        try {
            final String hex = resolveOwnJarSha256();
            if (hex != null && !hex.isEmpty()) {
                hashKnownGood = hex;
                jarHash = hex;
                logJarHash();
                return hex;
            }
        } catch (Throwable ignored) {
            jarHash = null;
            jarHashSource = SOURCE_NONE;
            jarHashPath = null;
            jarHashDetail = "unexpected:" + ignored.getClass().getSimpleName();
        }
        jarHash = null;
        hashRetryNotBeforeNanos = now + HASH_RETRY_BACKOFF_NANOS;
        if (hashFailLogs < DIAG_HASH_FAIL_LOGS) {
            hashFailLogs++;
            logInfo("jar_hash 暂不可用（第" + hashAttempts + "次尝试，" + (HASH_RETRY_BACKOFF_NANOS / 1_000_000L)
                    + "ms 后重试） detail=" + (jarHashDetail == null ? "unknown" : jarHashDetail));
        }
        return NA;
    }

    private static boolean ownModFileReady() {
        try {
            final ModList modList = ModList.get();
            return modList != null && modList.getModFileById(ShanhaiMod.MOD_ID) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String resolveOwnJarSha256() {
        // ① FML：mods 目录里的真实 jar（首选）
        try {
            final Path path = fmlModJarPath();
            final String hex = sha256Prefix12(path);
            if (hex != null) {
                jarHashSource = SOURCE_FML;
                jarHashPath = String.valueOf(path);
                return hex;
            }
            jarHashDetail = ownModFileReady() ? "fml-miss" : "fml-modlist-not-ready";
        } catch (Throwable t) {
            jarHashDetail = "fml-error:" + t.getClass().getSimpleName();
        }
        // ② 本类所在 jar 的资源 URL：只要 jar 上挂着 union 文件系统就反推得回 mods 里的真 jar
        try {
            final Path path = selfJarResourcePath();
            final String hex = sha256Prefix12(path);
            if (hex != null) {
                jarHashSource = SOURCE_SELF_JAR_RESOURCE;
                jarHashPath = String.valueOf(path);
                return hex;
            }
            jarHashDetail = joinDetail(jarHashDetail, (path == null) ? "selfjar-miss" : "selfjar-not-file");
        } catch (Throwable t) {
            jarHashDetail = joinDetail(jarHashDetail, "selfjar-error:" + t.getClass().getSimpleName());
        }
        // ③ 退路：code source（开发环境是 classes 目录 ⇒ 这条路也会失败）
        try {
            final Path path = codeSourceJarPath();
            final String hex = sha256Prefix12(path);
            if (hex != null) {
                jarHashSource = SOURCE_CODE_SOURCE;
                jarHashPath = String.valueOf(path);
                return hex;
            }
            jarHashDetail = joinDetail(jarHashDetail, (path == null) ? "codeSource-miss" : "codeSource-not-file");
        } catch (Throwable t) {
            jarHashDetail = joinDetail(jarHashDetail, "codeSource-error:" + t.getClass().getSimpleName());
        }
        jarHashSource = SOURCE_NONE;
        jarHashPath = null;
        return null;
    }

    private static String joinDetail(String first, String second) {
        return (first == null || first.isEmpty()) ? second : first + " " + second;
    }

    /** FML 眼里的本 mod 文件路径（{@code mods} 目录下的真 jar）；取不到返回 null。 */
    private static Path fmlModJarPath() {
        final ModList modList = ModList.get();
        if (modList == null) {
            return null;
        }
        final IModFileInfo info = modList.getModFileById(ShanhaiMod.MOD_ID);
        if (info == null) {
            return null;
        }
        final IModFile file = info.getFile();
        return file == null ? null : file.getFilePath();
    }

    /** code source 指向的真 jar；顺带把 union 壳解开。开发环境（目录）返回 null。 */
    private static Path codeSourceJarPath() throws Exception {
        final CodeSource codeSource = ShanhaiWindowTitle.class.getProtectionDomain().getCodeSource();
        if (codeSource == null) {
            return null;
        }
        final URL location = codeSource.getLocation();
        if (location == null) {
            return null;
        }
        final String protocol = location.getProtocol();
        if ("file".equalsIgnoreCase(protocol)) {
            final URI uri = location.toURI();
            return Paths.get(uri);
        }
        if ("union".equalsIgnoreCase(protocol)) {
            return unionJarPath(location.toString());
        }
        return null;
    }

    /**
     * <b>本类自己所在 jar</b>的路径 —— 从「本类的 class 资源 URL」反推，<b>不需要 ModList</b>。
     *
     * <p>资源 URL 形如 {@code union:/C:/…/mods/shanhai-0.1.0.jar%2362!/com/shanhai/client/ShanhaiWindowTitle.class}；
     * 只认 {@code .jar}（开发环境这条会指到 class 文件，若照哈希贴上去就是假读数）。
     */
    private static Path selfJarResourcePath() {
        try {
            final URL url = ShanhaiWindowTitle.class.getResource("ShanhaiWindowTitle.class");
            if (url == null) {
                return null;
            }
            final String location = url.toString();
            Path path = null;
            if (location.regionMatches(true, 0, "union:", 0, "union:".length())) {
                path = unionJarPath(location);
            } else if (location.regionMatches(true, 0, "jar:", 0, "jar:".length())) {
                final String inner = location.substring("jar:".length());
                final int bang = inner.indexOf("!/");
                if (inner.regionMatches(true, 0, "file:", 0, "file:".length())) {
                    path = Paths.get(new URI(bang >= 0 ? inner.substring(0, bang) : inner));
                }
            }
            return isJarFile(path) ? path : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 这个路径是不是「默认文件系统上真实存在的 {@code .jar}」。 */
    private static boolean isJarFile(Path path) {
        try {
            if (path == null || !Files.isRegularFile(path)) {
                return false;
            }
            final Path name = path.getFileName();
            return name != null && name.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".jar");
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** {@code union:/C:/…/x.jar%2362!/} → {@code C:\…\x.jar}。 */
    private static Path unionJarPath(String location) {
        if (location == null || !location.startsWith("union:")) {
            return null;
        }
        String raw = location.substring("union:".length());
        final int bang = raw.indexOf("!/");
        if (bang >= 0) {
            raw = raw.substring(0, bang);
        }
        final int member = raw.lastIndexOf("%23");
        if (member >= 0) {
            raw = raw.substring(0, member);
        }
        final String decoded = URLDecoder.decode(raw, StandardCharsets.UTF_8);
        final String trimmed = (decoded.length() > 2 && decoded.charAt(0) == '/' && decoded.charAt(2) == ':')
                ? decoded.substring(1)
                : decoded;
        try {
            return Paths.get(trimmed);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 读文件算 SHA-256 并取前 {@link #JAR_HASH_CHARS} 位；不是可读的真文件就返回 null。 */
    private static String sha256Prefix12(Path path) throws Exception {
        if (path == null || !Files.isRegularFile(path)) {
            return null;
        }
        if (!"file".equalsIgnoreCase(path.toUri().getScheme())) {
            return null;
        }
        final MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(path)) {
            final byte[] buffer = new byte[1 << 16];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        final byte[] hash = digest.digest();
        final StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.length() < JAR_HASH_CHARS ? hex.toString() : hex.substring(0, JAR_HASH_CHARS);
    }

    /** 打一行 jar 哈希成功日志（成功只会有这一行）。 */
    private static void logJarHash() {
        if (SOURCE_NONE.equals(jarHashSource)) {
            logInfo("jar_hash source=none detail=" + (jarHashDetail == null ? "unknown" : jarHashDetail));
            return;
        }
        logInfo("jar_hash source=" + jarHashSource + " path=" + jarHashPath + " hex=" + jarHash);
    }

    // ─────────────────────────── 构建日期 ───────────────────────────

    /** 构建日期的中文形态（{@code 2026-11-08} → {@code 11月8日}）；读不到 = {@code 不可用}。 */
    private static synchronized String buildDateCn() {
        if (!buildDateTried) {
            buildDateTried = true;
            try {
                buildDateCn = readBuildDateCn();
            } catch (Throwable ignored) {
                buildDateCn = null;
            }
        }
        return buildDateCn == null ? NA : buildDateCn;
    }

    private static String readBuildDateCn() {
        final Properties props = new Properties();
        try (InputStream in = ShanhaiWindowTitle.class.getResourceAsStream(BUILD_PROPS_RESOURCE)) {
            if (in == null) {
                return null;
            }
            // 与 build.gradle 口径一致：该文件刻意保持纯 ASCII，按 ISO-8859-1 读即可。
            props.load(in);
        } catch (Exception ignored) {
            return null;
        }
        final String raw = props.getProperty(BUILD_DATE_KEY);
        if (raw == null) {
            return null;
        }
        final Matcher matcher = BUILD_DATE_SHAPE.matcher(raw.trim());
        if (!matcher.matches()) {
            return null;   // 占位符没被 expand 覆盖（字面量 ${build_date}）
        }
        try {
            final int month = Integer.parseInt(matcher.group(2));
            final int day = Integer.parseInt(matcher.group(3));
            return month + "月" + day + "日";
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
