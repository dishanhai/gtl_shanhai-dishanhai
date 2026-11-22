package com.shanhai.add.backstage;

import com.shanhai.add.ShanhaiAdd;
import com.shanhai.backstage.BackstagePhase;
import com.shanhai.backstage.BackstageState;
import com.shanhai.client.ShanhaiWindowTitle;
import com.shanhai.client.backstage.BackstageProcess;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.forgespi.language.IModFileInfo;

import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 装 add 之后的标题接管（<b>第一步</b>：只接管"写标题"，画线程在第二步）。
 *
 * <h4>行为（2026-11-21 用户拍板：「装了 add 就直接停掉主 jar 的那条渲染线，add 接手渲染标题」）</h4>
 * <ol>
 *   <li>{@link FMLClientSetupEvent}：起子进程（{@link BackstageProcess#bootstrap}）＋
 *       {@link ShanhaiWindowTitle#installSink} 装上 sink ＋ 起活动采样器与它的发布线程；</li>
 *   <li>sink 的实现 = 把本体送来的<b>快照</b>（{@link ShanhaiWindowTitle.TitleSnapshot}）连同
 *       {@code phase / anchorEpochMs / worldEnterEpochMs / playedAccumMs / pausedAccumMs /
 *       pausedSinceEpochMs / everInWorld / version / buildDate / welcome / title} <b>一次原子写</b>进
 *       状态文件（{@link BackstageProcess#publish}），真正的 Win32 写在子进程里；</li>
 *   <li>本体一旦装上 sink 就<b>一行 {@code setTitle} 都不写</b>；</li>
 *   <li>{@code GameShuttingDownEvent} ＋ JVM shutdown hook：{@code removeSink} ＋ 停子进程；</li>
 *   <li>任何一步失败（拿不到 own jar / 子进程起不来 / sink 装不上 / 子进程后来死了）⇒
 *       {@code removeSink} ＋ 打一行 {@code title=fallback_vanilla reason=…}，标题退回本体自己写。</li>
 * </ol>
 *
 * <h4>🔴 为什么不再只送成品字符串</h4>
 * <p>送字 = 父进程一卡就没有新字 ⇒ 子进程只能原样重写旧字 ⇒ 卡死期间标题冻住（实测 48 秒）。
 * 现在送时间点（{@code snapshot}），子进程每 100 ms 自己用 {@code currentTimeMillis} 现算，
 * 主线程卡多久秒数都照走。
 *
 * <h4>活动标签（2026-11-21 新增：标题上「正在加载：X」的来源）</h4>
 * <p>{@link ActivitySampler} 用 JMX 只读地采主线程的栈，得到「此刻在干什么」；
 * 本类的 {@code shanhai-activity-publisher} 线程（<b>不在主线程上</b>，也不占主线程）每 250 ms 把标签
 * 交给 {@link #publishActivity}，由它复制 {@link #lastPublished}、只换 {@code activity} 后写盘。
 * 主线程那条 {@link #submitSnapshot} 每次都把上一份的 activity 原样搬进新状态，所以两条线不会互相擦掉。
 *
 * <p>本类不做注入、不碰游戏窗口（句柄只读一次交给子进程）、不合成输入。
 */
@Mod.EventBusSubscriber(modid = ShanhaiAdd.MOD_ID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.MOD)
public final class AddBackstageTitle {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String OWNER = "shanhai_add";

    /**
     * 状态文件的<b>写锁</b>：主线程（{@link #submitSnapshot}）与活动发布线程
     * （{@link #publishActivity}）都会调 {@code BackstageProcess.publish}，而那份实现
     * <b>不是线程安全的</b>（见 {@link #publishActivity} 的注释）——所有写盘都从这把锁里过。
     */
    private static final Object PUBLISH_LOCK = new Object();

    /** 因 activity 再写一次状态文件的<b>最小间隔</b>：500 ms。 */
    private static final long ACTIVITY_WRITE_MIN_GAP_MS = 500L;

    /** 活动发布线程的节拍：250 ms（与采样器同频）。 */
    private static final long ACTIVITY_PUBLISH_PERIOD_MS = 250L;

    /** activity 发布失败的日志限流间隔。 */
    private static final long ACTIVITY_FAIL_LOG_PERIOD_MS = 10_000L;

    /** 是否已经接管（幂等）。 */
    private static volatile boolean started;
    /** 是否已经因失败退回本体（只打一行）。 */
    private static volatile boolean fellBack;
    /** 退出钩子只注册一次。 */
    private static volatile boolean hookRegistered;

    /**
     * 最近一次成功<b>交给</b> {@code BackstageProcess.publish} 的那一份状态
     * （= 主线程那份完整快照；{@link #publishActivity} 复制它、只换 {@code activity}）。
     *
     * <p>读写都在 {@link #PUBLISH_LOCK} 里（也声明成 volatile，方便出问题时在调试器里看一眼）。
     */
    private static volatile BackstageState lastPublished;

    /** 上一次因 activity 写盘的时刻（epoch 毫秒；限流用，读写都在锁内）。 */
    private static long lastActivityWriteMs;

    /** activity 发布失败的上一次日志时刻（不在锁内，方法内的局部语义即可）。 */
    private static volatile long lastActivityFailLogMs;

    /** 活动发布线程（把采样器的当前标签送进状态文件）。 */
    private static volatile Thread activityPublisher;
    /** 活动发布线程是否该继续跑。 */
    private static volatile boolean activityPublisherRunning;

    private AddBackstageTitle() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        try {
            start();
        } catch (Throwable t) {
            fallback("exception " + t.getClass().getSimpleName() + ":" + t.getMessage());
        }
    }

    private static void start() {
        if (started) {
            return;
        }
        final Path gameDir = FMLPaths.GAMEDIR.get();
        final long pid = ProcessHandle.current().pid();
        final long hwnd = ShanhaiWindowTitle.captureWindowHandle();
        final long now = System.currentTimeMillis();
        // 先让本体在主线程把 Welcome 缓存刷成真名（titleForStartup 那句日志也顺带打出窗口出生标题），
        // 再取<b>纯数据</b>快照 —— snapshotFor 不碰 Minecraft，所以下面随便哪个线程调都安全。
        ShanhaiWindowTitle.titleForStartup();
        final ShanhaiWindowTitle.TitleSnapshot first = ShanhaiWindowTitle.snapshotFor(now);
        final String ownJar = ownJarPath();
        if (ownJar == null) {
            fallback("own_jar_not_found");
            return;
        }
        final BackstageState initial = stateFrom(pid, hwnd, first, first.title);
        if (!BackstageProcess.bootstrap(gameDir, ownJar, pid, initial)) {
            fallback("bootstrap_failed");
            return;
        }
        // bootstrap 已经把 initial 写进状态文件了：它就是"最近一次发布的那一份"。
        lastPublished = initial;
        if (!ShanhaiWindowTitle.installSink(OWNER, AddBackstageTitle::submitSnapshot)) {
            fallback("install_sink_failed");
            return;
        }
        started = true;
        fellBack = false;
        registerShutdownHook();
        // 采样器只读主线程的栈（JMX），起不来也只是没有「正在加载：X」，不影响标题本身。
        ActivitySampler.startIfNeeded();
        startActivityPublisher();
        LOGGER.info("[SHANHAI-ADD] title=add_takeover pid={} hwnd=0x{} own_jar={} anchor={} phase={} sampler={}",
                pid, Long.toHexString(hwnd), ownJar, first.anchorEpochMs, first.phase,
                ActivitySampler.selfCheck());
    }

    /**
     * sink 实现：把<b>快照</b>整体落进状态文件（一次原子写）；子进程死了就退回本体。
     *
     * <p>时间点与标题必须<b>同一份</b>落盘：子进程按 {@code anchorEpochMs > 0} 自己现拼，
     * 若两边来自不同版本的文件，那一拍的数字就会跳。
     *
     * <p>🔴 <b>activity 在这里是"搬运"的</b>：本体送来的快照里没有活动标签（它压根不知道采样器），
     * 所以每次都用<b>上一份状态里的 activity</b> 填新状态。不搬的话，每 tick/每帧的常规发布会把
     * 采样器刚写进去的标签清空（子进程那一拍就会把「正在加载：X」整段去掉，标题一抖一抖）。
     */
    private static void submitSnapshot(ShanhaiWindowTitle.TitleSnapshot snapshot) {
        try {
            if (snapshot == null) {
                return;
            }
            if (!started || fellBack) {
                return;
            }
            if (!BackstageProcess.isChildAlive()) {
                fallback("child_gone");
                return;
            }
            final BackstageState mirror = BackstageProcess.mirror();
            final long pid = mirror == null ? ProcessHandle.current().pid() : mirror.pid;
            final long hwnd = mirror == null ? 0L : mirror.hwnd;
            final BackstageState next;
            synchronized (PUBLISH_LOCK) {
                final String activity = lastPublished == null ? "" : lastPublished.activity;
                next = stateFrom(pid, hwnd, snapshot, snapshot.title).withActivity(activity);
                BackstageProcess.publish(next);
                lastPublished = next;
            }
        } catch (Throwable t) {
            fallback("submit_exception " + t.getClass().getSimpleName());
        }
    }

    /**
     * <b>供发布线程调用</b>（绝不占主线程）：把 {@link #lastPublished} 复制一份、只换 {@code activity}
     * 字段写出去。
     *
     * <h4>写盘判据</h4>
     * <p>仅当 <b>activity 变了</b> 或 <b>距上次因 activity 写盘 ≥ 500 ms</b> 时才调
     * {@code BackstageProcess.publish}（本次调用都会把限流时间戳往前推）。
     * <p>进过世界（{@code everInWorld=true}）之后直接返回：{@code BackstageTitle.compose} 在那种状态下
     * <b>永远不显示</b> activity，写出去也只是白写盘。
     *
     * <h4>🔴 为什么整段要加锁（不是可选的）</h4>
     * <p>{@code BackstageProcess.publish} 自己没有同步，且它做的三件事都不是线程安全的：
     * <pre>
     *   mirror = state;                                          // ② 共享镜像
     *   final BackstageState last = lastWritten;                 // ③ 读-比较-写，非原子
     *   if (last != null &amp;&amp; state.sameContent(last)) return;
     *   writeState(state);                                        // ④ 内部用固定名 .tmp + 原子改名
     * </pre>
     * 其中 ④ 是致命的：{@code BackstageStateFile.writeAtomic} 的临时文件名是
     * {@code backstage_state.txt.tmp}（<b>固定</b>，见 {@code BackstageStateFile.tmpPathFor}），
     * 两个线程同时写会往同一个 .tmp 里互相截断/覆盖，再把一个"写到一半"的文件原子改名成正式文件 ——
     * 子进程就会读到半截状态；另外 {@code writeState} 里的 {@code ++stampCounter} 是
     * <b>非原子的 volatile 自增</b>（可能撞号）。所以：<b>所有 publish 调用都在 {@link #PUBLISH_LOCK} 里</b>。
     * <p>异常吞掉并限流打一行（每 {@link #ACTIVITY_FAIL_LOG_PERIOD_MS}），绝不外抛 —— 这条线程只管标题。
     */
    public static void publishActivity(String label) {
        try {
            if (!started || fellBack) {
                return;
            }
            final String nextActivity = label == null ? "" : label;
            final long now = System.currentTimeMillis();
            synchronized (PUBLISH_LOCK) {
                final BackstageState base = lastPublished;
                if (base == null || base.everInWorld) {
                    return;
                }
                final boolean changed = !nextActivity.equals(base.activity);
                if (!changed && (now - lastActivityWriteMs) < ACTIVITY_WRITE_MIN_GAP_MS) {
                    return;
                }
                final BackstageState next = base.withActivity(nextActivity);
                lastActivityWriteMs = now;
                BackstageProcess.publish(next);
                lastPublished = next;
            }
        } catch (Throwable t) {
            final long now = System.currentTimeMillis();
            if ((now - lastActivityFailLogMs) >= ACTIVITY_FAIL_LOG_PERIOD_MS) {
                lastActivityFailLogMs = now;
                LOGGER.warn("[SHANHAI-ADD] activity_publish_failed err={} label={}",
                        t.getClass().getSimpleName(), label);
            }
        }
    }

    /** 起活动发布线程（幂等；daemon）。 */
    private static void startActivityPublisher() {
        if (activityPublisherRunning) {
            return;
        }
        activityPublisherRunning = true;
        final Thread thread = new Thread(AddBackstageTitle::activityPublisherLoop,
                "shanhai-activity-publisher");
        thread.setDaemon(true);
        activityPublisher = thread;
        try {
            thread.start();
        } catch (Throwable t) {
            activityPublisherRunning = false;
            activityPublisher = null;
            LOGGER.warn("[SHANHAI-ADD] activity_publisher=start_failed err={}", t.getClass().getSimpleName());
        }
    }

    /** 每秒 4 次把采样器的当前标签喂给 {@link #publishActivity}。 */
    private static void activityPublisherLoop() {
        while (activityPublisherRunning) {
            try {
                publishActivity(ActivitySampler.currentLabel());
            } catch (Throwable ignored) {
                // publishActivity 自己已经限流记过一行；这里只保证循环不倒
            }
            try {
                Thread.sleep(ACTIVITY_PUBLISH_PERIOD_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** 停活动发布线程（幂等）。 */
    private static void stopActivityPublisher() {
        activityPublisherRunning = false;
        final Thread thread = activityPublisher;
        activityPublisher = null;
        if (thread == null) {
            return;
        }
        try {
            thread.interrupt();
        } catch (Throwable ignored) {
            // 中断不出去也只影响"它下次睡醒时自己退"
        }
        // 等它真的离开（最多 300 ms）：关游戏时 requestStop 会再写一次状态文件，
        // 若此刻它还卡在一次 publish 里，两次写会撞在那一个固定的 .tmp 上。
        try {
            thread.join(300L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Throwable ignored) {
            // 关游戏路径：等不到就走，最多留一次并发写窗口
        }
    }

    /**
     * 快照 → 状态文件那一份（<b>只搬运、不改语义</b>）。
     *
     * <p>{@code startEpochMs} 在状态文件里只用于日志与"老文件兜底"（{@code anchorEpochMs<=0} 时才会被读），
     * 所以按阶段取一个最贴近语义的值：游玩 = 进世界时刻、干等 = 0、加载/其余 = 加载零点。
     */
    private static BackstageState stateFrom(long pid, long hwnd,
                                            ShanhaiWindowTitle.TitleSnapshot snapshot, String title) {
        final BackstagePhase phase = BackstagePhase.parse(snapshot.phase);
        final long startEpochMs;
        if (phase == BackstagePhase.INGAME) {
            startEpochMs = snapshot.worldEnterEpochMs;
        } else if (phase == BackstagePhase.IDLE || phase == BackstagePhase.STOP) {
            startEpochMs = 0L;
        } else {
            startEpochMs = snapshot.anchorEpochMs;
        }
        return new BackstageState(pid, hwnd, phase,
                startEpochMs,
                snapshot.anchorEpochMs,
                snapshot.pausedAccumMs,
                snapshot.pausedSinceEpochMs,
                snapshot.playedAccumMs,
                snapshot.worldEnterEpochMs,
                snapshot.everInWorld,
                snapshot.versionText,
                snapshot.buildDateText,
                snapshot.welcome,
                0L,
                title == null ? "" : title);
    }

    /**
     * 关游戏：撤 sink、停子进程。
     *
     * <p>🔴 这里<b>不能</b>写成 {@code @SubscribeEvent}：本类挂在 <b>MOD 总线</b>（为了
     * {@link FMLClientSetupEvent}），而 {@code GameShuttingDownEvent} 是 <b>FORGE 总线</b>的事件 ——
     * 混在一起 Forge 会在加载期抛 {@code IllegalArgumentException}（"not a subtype of IModBusEvent"），
     * 整个 add 加载失败、游戏起不来（2026-11-22 真踩过）。
     * 真正的事件入口在 {@code com.shanhai.client.backstage.BackstageForgeEvents#onGameShuttingDown}
     * （那条线默认 FORGE 总线），它调本方法。
     */
    public static void stopNow(String reason) {
        stop(reason);
    }

    private static void registerShutdownHook() {
        if (hookRegistered) {
            return;
        }
        hookRegistered = true;
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(
                    () -> stop("jvm_shutdown"), "shanhai-add-title-stop"));
        } catch (Throwable t) {
            LOGGER.warn("[SHANHAI-ADD] shutdown hook 注册失败: {}", t.toString());
        }
    }

    private static void stop(String reason) {
        try {
            if (!started) {
                return;
            }
            started = false;
            stopActivityPublisher();
            ActivitySampler.stop(reason);
            ShanhaiWindowTitle.removeSink(OWNER);
            BackstageProcess.requestStop();
            LOGGER.info("[SHANHAI-ADD] title=add_stopped reason={}", reason);
        } catch (Throwable t) {
            LOGGER.warn("[SHANHAI-ADD] title=add_stop_failed reason={} err={}", reason, t.toString());
        }
    }

    /** 任何一步失败 ⇒ 退回本体自己写（只打一行）。 */
    private static void fallback(String reason) {
        if (fellBack) {
            return;
        }
        fellBack = true;
        try {
            ShanhaiWindowTitle.removeSink(OWNER);
        } catch (Throwable ignored) {
            // 撤不掉也必须继续
        }
        LOGGER.warn("[SHANHAI-ADD] title=fallback_vanilla reason={}", reason);
    }

    /** add 自己的 jar（生产）/ classes 目录（开发）。 */
    private static String ownJarPath() {
        try {
            final IModFileInfo info = ModList.get().getModFileById(ShanhaiAdd.MOD_ID);
            if (info != null && info.getFile() != null && info.getFile().getFilePath() != null) {
                return info.getFile().getFilePath().toString();
            }
        } catch (Throwable ignored) {
            // 退回 code source
        }
        try {
            return Paths.get(AddBackstageTitle.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toString();
        } catch (Throwable t) {
            return null;
        }
    }
}
