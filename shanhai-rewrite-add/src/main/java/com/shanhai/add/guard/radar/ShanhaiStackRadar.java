package com.shanhai.add.guard.radar;

import com.mojang.logging.LogUtils;
import com.shanhai.add.ShanhaiAdd;
import com.shanhai.add.config.ShanhaiAddConfig;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.forgespi.language.IModInfo;
import org.slf4j.Logger;

/**
 * 常开栈雷达的<b>游戏侧装配点</b> ＋ 对外唯一入口。
 *
 * <h2>🔴 用户口径（原话，这是本类不设"可疑窗口"的原因）</h2>
 * <pre>
 *   「那你怎么判断可疑窗口，要是敌方绕开了怎么办，所以你要一直开这个窗口」
 * </pre>
 * ⇒ 没有"什么时候开扫描"的判据：{@code ServerStartedEvent} 一起就开，{@code ServerStoppedEvent} 才停。
 * 采样是低密度常驻（默认 4 ms 一次），<b>只有命中/违规时</b>才归因 dump（{@link #dump(String)}）。
 *
 * <h2>为什么抓栈引用要在这里记</h2>
 * {@code ServerStartedEvent} 是在<b>服务端主线程</b>上发的（{@code MinecraftServer#run} 里），
 * 所以 {@code Thread.currentThread()} 就是待会儿跑 tick 的那个线程 —— 只有它值得采。
 * 之后抓栈的活全交给 {@link ShanhaiStackRadarSampler} 的 daemon 线程
 * {@value ShanhaiStackRadarSampler#THREAD_NAME}；
 * 🔴 <b>主线程上永远不抓栈</b>（在主线程抓栈只会抓到我们自己这段 dump 调用）。
 *
 * <h2>失败姿势</h2>
 * 整个装配只打日志、绝不冒泡到事件总线（订阅者抛异常会污染总线）。
 * 抓栈/安全点这类偶发异常由采样核只 WARN 一次（不刷屏）。
 *
 * <h2>日志标记（机器可判，前缀 {@code [SHANHAI-RADAR]}）</h2>
 * <pre>
 *   declared trigger=&lt;…&gt; …                                   —— mod 构造期"类在、线程名对"的自证
 *   known_hosts mods=&lt;n&gt; mapped=&lt;n&gt; unknown=&lt;n&gt; namespaces=&lt;k&gt; sample=…  —— 🆕 已知宿主映射（unknown 不许猜）
 *   started target_thread=&lt;名&gt; sampler_thread=&lt;名&gt; interval_ms=&lt;n&gt; depth=&lt;n&gt; …   —— 真的开起来了
 *   disabled stack_radar=false …                               —— 配置关了（与"没开起来"必须分得开）
 *   suspect reason=&lt;…&gt; window_ms=200 samples=&lt;n&gt; suspect_hits=&lt;s&gt; known_hits=&lt;k&gt; hits=&lt;h&gt;
 *           top=&lt;类#方法&gt;ratio=&lt;x.x&gt;(n=&lt;n&gt; base=&lt;b&gt;),…           —— 🆕 归因（按突增倍数；top=none 也打）
 *   known reason=&lt;…&gt; window_ms=200 known_hits=&lt;k&gt; known_top=…  —— 🆕 对照行（已知宿主里谁最吵）
 *   density interval_ms=&lt;n&gt; reason=miss_streak|reset ttl_ms=&lt;n&gt;  —— 每次密度切换
 *   radar boost mode=burst duration_ms=&lt;n&gt;                      —— 进 burst 档
 *   radar boost aborted reason=slow_reset|server_stopped|…      —— burst 中止
 *   stats samples=&lt;n&gt; dropped=&lt;n&gt; avg_us=&lt;n&gt; max_us=&lt;n&gt; buffer=512  —— 每 60 秒的成本自证
 *   stopped trigger=&lt;…&gt; …                                      —— 停机（环形缓冲已清空）
 * </pre>
 */
@Mod.EventBusSubscriber(modid = ShanhaiAdd.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ShanhaiStackRadar {

    /** 日志前缀（与采样核同一个字面量）。 */
    public static final String PREFIX = ShanhaiStackRadarSampler.PREFIX;

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 只 WARN 一次（规格：反射/安全点异常不刷屏）。 */
    private static final AtomicBoolean WARNED = new AtomicBoolean();

    /** 采样核（一直存在；有没有在跑看它自己的 running）。dump 在"没开"时也要能打出一行。 */
    private static final ShanhaiStackRadarSampler SAMPLER = new ShanhaiStackRadarSampler(
            new ShanhaiStackRadarSampler.Log() {
                @Override
                public void line(final String text) {
                    LOGGER.info(text);
                }

                @Override
                public void warn(final String text) {
                    LOGGER.warn(text);
                }
            });

    /** 被采的那个线程（{@code ServerStartedEvent} 里记下的主线程）。 */
    private static volatile Thread serverThread;

    private ShanhaiStackRadar() {
        // 纯静态
    }

    // ═══════════════════════════════ mod 构造期：一行自证 ═══════════════════════════════

    /**
     * mod 构造期的一行自证（{@code ShanhaiAdd} 构造器里那一行接线就调这个）。
     *
     * <p>⚠️ 这里<b>不读配置</b>：mod 构造期 {@code COMMON_SPEC} 通常还没加载，
     * 读出来会是一条假的 {@code enabled=false}。真值由 {@code ServerStartedEvent} 那一行给
     * （{@code started=…} 或 {@code disabled=…}）。
     *
     * @param trigger 谁调的（{@code mod_ctor}）
     */
    public static void declare(final String trigger) {
        try {
            LOGGER.info("{} declared trigger={} sampler_thread={} section=[guard_module]"
                            + " keys=stack_radar|stack_radar_interval_ms|stack_radar_depth"
                            + "（真值看 ServerStartedEvent 那一行的 started/disabled）",
                    PREFIX, ShanhaiStackRadarFilter.token(trigger), ShanhaiStackRadarSampler.THREAD_NAME);
        } catch (Throwable t) {
            warnOnce("declare", t);
        }
    }

    // ═══════════════════════════════ 生命周期 ═══════════════════════════════

    /**
     * 服务端起来了：<b>记下主线程引用</b>，读配置，起采样线程。
     *
     * <p>为什么在 {@code ServerStartedEvent} 而不是 mod 构造器：那时才有服务端主线程，
     * 而且此刻本体已经打完 {@code Done (…)!} —— 判读日志只有一个时间基准（与时钟/重力/热改同一时点）。
     */
    @SubscribeEvent
    public static void onServerStarted(final ServerStartedEvent event) {
        try {
            // 🔴 全类唯一一处"记下主线程"的地方：只有这个线程值得采
            final Thread mainThread = Thread.currentThread();
            serverThread = mainThread;

            final boolean enabled = ShanhaiAddConfig.stackRadarOr(ShanhaiAddConfig.DEFAULT_STACK_RADAR);
            final int intervalMs = ShanhaiAddConfig.stackRadarIntervalMsOr(
                    ShanhaiAddConfig.DEFAULT_STACK_RADAR_INTERVAL_MS);
            final int depth = ShanhaiAddConfig.stackRadarDepthOr(
                    ShanhaiAddConfig.DEFAULT_STACK_RADAR_DEPTH);

            if (!enabled) {
                // 与"没开起来"必须分开：这一行的存在本身就是"配置把它关了"的证据
                LOGGER.info("{} disabled stack_radar=false interval_ms={} depth={}"
                                + "（[guard_module] stack_radar；本项默认开，改完重启世界生效）",
                        PREFIX, intervalMs, depth);
                return;
            }
            // 🆕 已知宿主集合（deny-by-default 的另一半）：从 ModList 取，映射不出来的一律记 unknown
            final KnownWatch watch = collectKnownHosts();
            SAMPLER.start(mainThread, intervalMs, depth, watch.namespaces);
        } catch (Throwable t) {
            // 雷达起不来只意味着"归因不可用"，绝不许影响进世界
            warnOnce("server_started", t);
            try {
                LOGGER.error("{} started=false reason=wiring_failed err={}"
                        + "（雷达不可用：本次日志里【没有】RADAR suspect 行 ≠ 没发生可疑调用）",
                        PREFIX, t.toString(), t);
            } catch (Throwable ignored) {
                // 连日志都打不出去：什么也不做
            }
        }
    }

    // ═══════════════════════════════ 🆕 已知宿主：从 ModList 反推包命名空间 ═══════════════════════════════

    /**
     * 从运行时 {@code ModList} 收集"已知宿主"的<b>包命名空间</b>。
     *
     * <h2>怎么推（只用证据，不猜）</h2>
     * 每个 mod 的 {@code @Mod} 实例类就在它自己的代码里 ⇒ 取那个类的包当命名空间前缀。
     * 例：{@code com.gregtechceu.gtceu.GTCEu} ⇒ {@code com.gregtechceu.gtceu.}
     * （GTCEu 的 {@code api}/{@code common} 都在这个前缀下，于是它不再算 suspect）。
     *
     * <h2>🔴 推不出来就记 unknown，不许猜</h2>
     * 拿不到 mod 实例、或类名不在任何包里（默认包）⇒ 这个 mod 记为 <b>unknown</b>，它的帧继续算 suspect。
     * 猜错的代价是单向的：把某个未知 mod 的帧误判成"已知宿主" ⇒ 真正的可疑帧被折叠掉，
     * 而且<b>日志里看不出来</b>。反过来（该 known 的被当 suspect）只是噪声多一点，看得出、也可调。
     *
     * <h2>覆盖不到的那一类要明说</h2>
     * 一个 mod 可能有多个顶层包（{@code @Mod} 类只给出其中一个）⇒ 它的其它包仍会算 suspect。
     * 这是"不猜"的必然代价，靠三样东西兜住：{@code known_top=} 对照行、{@code ratio} 门槛、以及人工看日志。
     */
    private static KnownWatch collectKnownHosts() {
        final Set<String> namespaces = new LinkedHashSet<>();
        int mods = 0;
        int mapped = 0;
        int unknown = 0;
        try {
            final ModList list = ModList.get();
            if (list == null) {
                LOGGER.warn("{} known_hosts mods=0 mapped=0 unknown=0 reason=modlist_null"
                        + "（所有非白名单帧都会算 suspect）", PREFIX);
                return new KnownWatch(new String[0], 0, 0, 0);
            }
            for (final IModInfo info : list.getMods()) {
                if (info == null) {
                    continue;
                }
                mods++;
                final String namespace = namespaceOfMod(info.getModId(), list);
                if (namespace == null) {
                    unknown++;
                } else {
                    namespaces.add(namespace);
                    mapped++;
                }
            }
        } catch (Throwable t) {
            // 这里失败只意味着"分类退化成全部 suspect"（= 改动前的行为），绝不许影响开服
            warnOnce("known_hosts", t);
            return new KnownWatch(new String[0], 0, 0, 0);
        }
        final String[] all = namespaces.toArray(new String[0]);
        final StringBuilder sample = new StringBuilder(96);
        int shown = 0;
        for (int i = 0; i < all.length && shown < 3; i++) {
            if (shown > 0) {
                sample.append(',');
            }
            sample.append(ShanhaiStackRadarFilter.token(all[i]));
            shown++;
        }
        LOGGER.info("{} known_hosts mods={} mapped={} unknown={} namespaces={} sample={}",
                PREFIX, mods, mapped, unknown, all.length, sample.length() == 0 ? "none" : sample);
        return new KnownWatch(all, mods, mapped, unknown);
    }

    /**
     * 一个 mod 的包命名空间；拿不到就 {@code null}（记 unknown）。
     *
     * @param modId 注册名（只用于查表与日志，<b>不</b>参与"猜包名"）
     * @param list  {@code ModList}
     */
    private static String namespaceOfMod(final String modId, final ModList list) {
        if (modId == null || modId.isEmpty()) {
            return null;
        }
        try {
            final Optional<?> mod = list.getModObjectById(modId);
            if (mod == null || !mod.isPresent() || mod.get() == null) {
                return null;   // 没有 @Mod 实例（库 / 虚拟 mod / 加载器自身）⇒ unknown
            }
            final Class<?> cls = mod.get().getClass();
            if (cls == null) {
                return null;
            }
            return ShanhaiStackRadarFilter.namespaceOfClassName(cls.getName());
        } catch (Throwable ignored) {
            return null;   // 反射失败 = 映射不出来（不许猜）
        }
    }

    /** {@link #collectKnownHosts()} 的结果（只用来打一行日志 ＋ 灌给采样核）。 */
    private static final class KnownWatch {

        final String[] namespaces;
        final int mods;
        final int mapped;
        final int unknown;

        KnownWatch(final String[] namespaces, final int mods, final int mapped, final int unknown) {
            this.namespaces = namespaces;
            this.mods = mods;
            this.mapped = mapped;
            this.unknown = unknown;
        }
    }

    /** 服务端停了：线程退出，环形缓冲清空（停机后再 dump 只能打出"没有数据"）。 */
    @SubscribeEvent
    public static void onServerStopped(final ServerStoppedEvent event) {
        try {
            SAMPLER.stop("server_stopped");
        } catch (Throwable t) {
            warnOnce("server_stopped", t);
        } finally {
            serverThread = null;
        }
    }

    // ═══════════════════════════════ 对外入口（guard 那条线 / 命令 / 自检） ═══════════════════════════════

    /**
     * 🔴 <b>归因 dump（对外唯一入口）</b>：把最近 200 ms 的主线程样本聚合成 top-5 打一行。
     *
     * <p>守卫那条线在判定违规（动量被写 / 位置被传送 / 物品栏被抹）时应调这个，
     * 例如 {@code ShanhaiStackRadar.dump("guard_momentum_violation")}。
     * <b>任何线程都能调</b>（只读环形缓冲，不抓栈）；异常一律吞掉只 WARN 一次。
     *
     * @param reason 谁为什么（进日志 {@code reason=}；不许带空格，带了会被换成下划线）
     */
    public static void dump(final String reason) {
        try {
            SAMPLER.dump(reason);
        } catch (Throwable t) {
            warnOnce("dump", t);
        }
    }

    /**
     * {@link #dump(String)} 的别名：给"还没合入、只知道要一个请求入口"的调用方用。
     *
     * <p>语义完全一样（不排队、不同步、不重试），只是名字更像个请求。等守卫那条线合入后，
     * 它调哪个都行 —— 两个名字都留着，避免两边各写一套。
     */
    public static void requestDump(final String reason) {
        dump(reason);
    }

    /**
     * 🔴 <b>把 top 当 strike 喂给 auto_counter 的那一半</b>：
     * 同一次 200 ms 窗口里<b>突增倍数达标</b>（{@code ratio >= }{@value ShanhaiStackRadarFilter#NOMINATE_MIN_RATIO}）的帧，
     * 按倍数降序（同倍数按命中数、再按键名）。
     *
     * <p>为什么帧口径能直接对接：本模块的键就是 {@code 类#方法}，
     * 而 {@code HotfixTargetResolver#parseFrame} 接受 {@code 类#方法}（行号可省，内部记 -1）
     * ⇒ 守卫那条线可以原样转手：
     * <pre>
     *   ShanhaiStackRadar.dump("guard_motion_violation");                  // ① 先落归因日志（人看）
     *   HotfixTriageTap.submitViolation("motion", player.getName().getString(),
     *           ShanhaiStackRadar.topFrames(8), System.currentTimeMillis()); // ② 再喂给主动热改
     * </pre>
     * ⚠️ 本方法<b>不</b>自己调 {@code submitViolation}：热改默认是关的，是否动手由
     * {@code auto_counter} 那道闸门决定（这里只提供"那一刻主线程在哪串帧上"这个事实）。
     *
     * <p>🔴 <b>没有达标项就返回空表</b> —— 不许退回"绝对计数最高"。
     * 热改会真改字节码，对一个"稳定 20 次/秒"的正常帧动手等于打错人。
     *
     * @param topN 最多几条（{@code <=0} ⇒ 空表）
     * @return 帧文本列表；无达标项 ⇒ 空表（<b>不</b>返回 {@code null}）
     */
    public static List<String> topFrames(final int topN) {
        try {
            return SAMPLER.top(topN);
        } catch (Throwable t) {
            warnOnce("topFrames", t);
            return Collections.emptyList();
        }
    }

    /** 采样核（只读用：命令回显 / 自检；{@code null} 从不会出现）。 */
    public static ShanhaiStackRadarSampler sampler() {
        return SAMPLER;
    }

    /** 被采的线程（{@code null} = 还没进世界／已停服）。 */
    public static Thread sampledThread() {
        return serverThread;
    }

    /** 一行读数（命令回显用）。 */
    public static String statusLine() {
        try {
            return SAMPLER.statusLine();
        } catch (Throwable t) {
            return "status=unavailable err=" + ShanhaiStackRadarFilter.token(t.getClass().getSimpleName());
        }
    }

    // ═══════════════════════════════ 小工具 ═══════════════════════════════

    /** 只 WARN 一次：偶发异常（安全点/反射）不许刷屏。 */
    private static void warnOnce(final String where, final Throwable t) {
        if (!WARNED.compareAndSet(false, true)) {
            return;
        }
        try {
            LOGGER.warn("{} warn where={} err={}（只报这一次，之后静默；雷达不因此停）",
                    PREFIX, ShanhaiStackRadarFilter.token(where), t == null ? "?" : t.toString(), t);
        } catch (Throwable ignored) {
            // 日志都打不出去就算了
        }
    }
}
