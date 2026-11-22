package com.shanhai.add.hotfix;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>「关键类还归不归我们」的启动期判据</b>：监控表 ＋ 方法粒度指纹 ＋ 落盘基线 ＋ 比对日志。
 *
 * <h2>用户原话（这一条是需求的唯一来源）</h2>
 * <pre>
 *   「把我们<b>已经以及之后处理过的关键类</b>（例如这次的 isDeadOrDying）也加入<b>热改检测</b>，
 *    检测到它的变化之后就往上溯源，废掉对手的代码，这样不怕一直和对手反复抢修改点」
 * </pre>
 *
 * <h2>🔴 为什么只能这么做（两句话，别再重新论证）</h2>
 * <ol>
 *   <li><b>读回"当前生效字节"的 API 不存在</b>：{@code retransform} 给回来的永远是
 *       <b>initial class file bytes</b>（JDK 原文；本仓 {@code HotfixPatchReadback} 的 P1 用真 JVM 量过）
 *       ⇒ <b>不许</b>设计"哈希比对当前字节"，那条路根本采不到样本。</li>
 *   <li>可行只剩两条：<b>(a) 启动期基线的结构化比对</b>（load-time 的 coremod / mixin 改动<b>都在基线里</b>）、
 *       <b>(b) 行为探针</b>（{@code ShanhaiJudgeAuthority}，已存在，本类不碰）。本类只做 (a)。</li>
 * </ol>
 *
 * <h2>基线是怎么拿到的（这一步决定了"能不能看见 load-time 改动"）</h2>
 * <pre>
 *   对监控表里的每个类，会话里<b>第一次</b>见到时做一次 {@code retransformClasses}，
 *   挂一个"只记字节、永远 return null"的临时 transformer，拿到 JVM 手上那份
 *   【initial class bytes】—— 它<b>已经含</b> load-time 的 coremod／mixin 织入结果
 *   （P1：这份基线甚至与磁盘 class 不同，常量池被重建过）。
 *   ⇒ 拿它当"这次启动的基线"，与<b>落盘的那份</b>比 ⇒ 两次启动之间谁动过代码，一比就知道。
 *   ⚠️ 这一步只在"这个类我们<b>没</b>亲手改过"时才做（由胶水注入的 {@link Guard} 判，见下）：
 *      P2 实测"一次全 null 的 retransform 等于 undo"，对我们自己改过的类做它 = 亲手撤掉补丁。
 * </pre>
 *
 * <h2>指纹：只算【方法粒度】的 {@code Code} 属性，且<b>剔除常量池顺序</b></h2>
 * <pre>
 *   fp(method) = sha256( 描述符 ＋ max_stack ＋ max_locals ＋ 规范化字节码 ＋ 异常表 )
 *   ⇒ <b>不含</b>：class 文件的常量池本体、字段表、方法表顺序、以及 {@code Code} 下面的
 *      {@code LineNumberTable} / {@code LocalVariableTable} / {@code StackMapTable}（它们才携带
 *      调试信息与常量池下标，重新编译一次就会变）。
 *   ⇒ <b>常量池顺序怎么剔除</b>：走一遍字节码，凡是指向常量池的操作数（{@code ldc*}／
 *      {@code getstatic…putfield}／{@code invoke*}／{@code new}／{@code checkcast}／{@code instanceof}／
 *      {@code anewarray}／{@code multianewarray}）<b>都不哈希下标</b>，而是把那个常量池条目的
 *      <b>内容</b>哈希进去（类名／成员名＋描述符／字面量）。异常表的 {@code catch_type} 同理。
 *      ⇒ 常量池整体挪位（重编译／常量池合并／reobf）而字节码语义没变 ⇒ 指纹<b>不变</b>。
 * </pre>
 *
 * <h2>三种 verdict（比对结论日志，字段顺序固定）</h2>
 * <pre>
 *   [SHANHAI-HOTFIX] judge_watch class=net.minecraft.world.entity.LivingEntity methods=4 changed=[m_21224_] verdict=foreign
 *   [SHANHAI-HOTFIX] judge_watch class=net.minecraft.world.entity.LivingEntity methods=4 changed=[] verdict=clean
 *   [SHANHAI-HOTFIX] judge_watch class=net.minecraft.world.entity.Player   methods=0 changed=[] verdict=baseline
 *   [SHANHAI-HOTFIX] judge_watch_skip class=net.minecraft.client.Minecraft reason=class_not_present
 *   [SHANHAI-HOTFIX] judge_baseline_written classes=6 methods=9 path=…\config\shanhai\judge-baseline.json
 * </pre>
 * <ul>
 *   <li>{@code baseline} = 这一趟是<b>第一次</b>（没有基线文件）⇒ 刚写的这份<b>就是</b>基线，<b>不报 foreign</b>；</li>
 *   <li>{@code clean}    = 与落盘基线逐方法相同；</li>
 *   <li>{@code foreign}  = 有我们监控的方法变了 ⇒ 当成一次<b>违规样本</b>喂进既有溯源链
 *       （{@link HotfixTriageTap#submitViolation}，与"我们自己的违规现场"同一条路）；</li>
 *   <li>{@code changed} 里<b>只可能</b>出现监控表里那几个方法名；别人的改动落在别的方法上不算。</li>
 * </ul>
 *
 * <h2>客户端两个类在专用服务端<b>不存在</b></h2>
 * <pre>
 *   {@code Minecraft} / {@code ClientPacketListener} 只在客户端侧算；拿不到类 ⇒
 *   {@code judge_watch_skip reason=class_not_present}（一次会话一条，不刷屏），
 *   <b>且绝不影响基线落盘</b>（服务端写的是它真拿到的那几个类）。
 * </pre>
 *
 * <h2>依赖面（离线可编）</h2>
 * <p>只有 {@code java.*}（{@code java.lang.instrument} 也在内）＋ 同包的
 * <b>一个项目类都不引</b>（连 {@code tok()} 都在本类里自带，见下）。
 * <b>不引用</b> MC／Forge／ASM／log4j：日志出口、strike 出口、以及"这个类我们改过没有"
 * 三条都由装配胶水注入（{@link LogSink}／{@link StrikeSink}／{@link Guard}），
 * 这样离线自检能拿真源码直接跑，而且<b>不会被同包其它文件在改这件事拖住</b>
 * （2026-11-21 现场：另一条线正在改 hotfix 包，一个文件半写就让"编全包"红）。
 */
public final class HotfixJudgeWatch {

    /** 基线文件的格式版本（改指纹算法／改监控表 ⇒ 必须 +1；否则老基线会被当成"变了"）。 */
    public static final int BASELINE_VERSION = 1;

    /** 基线文件的 {@code kind} 字段（误写到别的 json 上也能一眼看出来）。 */
    public static final String KIND = "judge_watch_baseline";

    /** 会话里是否已经跑过（"每次会话首次见到时算"这件事的唯一闩）。 */
    private static volatile boolean sessionDone;

    // ═══════════════════════════════ 监控表（用户点名的那几个） ═══════════════════════════════

    /**
     * 监控表的一行：<b>一个类 ＋ 它下面我们认的几个方法</b>。
     *
     * <p>⚠️ {@code methods} 用的是<b>生产环境 class 文件里的真名</b>（SRG 名，如 {@code m_21224_}）：
     * 线上跑的就是这份字节，名字对不上等于监控表空转。{@code aliases} 只是<b>兜底</b>
     * （反混淆环境／离线假 class 用），生产里 SRG 名永远在，所以别名不会误命中。
     */
    public static final class Watch {

        /** 点号类名（进日志的那个，逐字）。 */
        public final String dotName;

        /** 内部名（{@code net/minecraft/…}，给基线与 transformer 的口径）。 */
        public final String internalName;

        /** 监控的方法名（SRG 名；<b>这就是</b>{@code changed=[…]} 里会出现的字面量）。 */
        public final String[] methods;

        /** 同序的反混淆别名（SRG 名找不到时才用）。 */
        public final String[] aliases;

        /** 这个类只在客户端侧存在吗（专用服务端没有）。 */
        public final boolean clientOnly;

        Watch(String dotName, String[] methods, String[] aliases, boolean clientOnly) {
            this.dotName = dotName;
            this.internalName = dotName.replace('.', '/');
            this.methods = methods;
            this.aliases = aliases;
            this.clientOnly = clientOnly;
        }

        /** 监控了同一个类的几个方法（{@code methods=} 的分母）。 */
        public int size() {
            return methods.length;
        }
    }

    /**
     * 🔴 <b>监控表本体</b>（6 个类 / 9 个方法）。
     *
     * <p>为什么是这几个：它们就是判据层与"死亡→摘人"那条链的<b>关键方法</b>
     * （{@code isDeadOrDying} 是用户这次点名的那一个）。别人要抢"我们说了算"这个位置，
     * 最省事的办法就是改这几个方法体 —— 改了，{@code Code} 指纹就变。
     */
    public static final Watch[] WATCH = {
            new Watch("net.minecraft.world.entity.LivingEntity",
                    new String[]{"m_21223_", "m_21224_", "m_6667_", "m_6153_"},
                    new String[]{"getHealth", "isDeadOrDying", "die", "tickDeath"},
                    false),
            new Watch("net.minecraft.server.level.ServerPlayer",
                    new String[]{"m_6667_"},
                    new String[]{"die"},
                    false),
            new Watch("net.minecraft.world.entity.Entity",
                    new String[]{"m_142467_"},
                    new String[]{"setRemoved"},
                    false),
            new Watch("net.minecraft.network.syncher.SynchedEntityData",
                    new String[]{"m_135356_"},
                    new String[]{"assignValues"},
                    false),
            // ---- 下面两个只在客户端侧存在（专用服务端拿不到类 ⇒ judge_watch_skip）
            new Watch("net.minecraft.client.multiplayer.ClientPacketListener",
                    new String[]{"m_142747_"},
                    new String[]{"handlePlayerCombatKill"},
                    true),
            new Watch("net.minecraft.client.Minecraft",
                    new String[]{"m_91152_"},
                    new String[]{"setScreen"},
                    true)
    };

    /** 监控表里一共有几个类（{@code classes=6} 的分母）。 */
    public static int watchedClasses() {
        return WATCH.length;
    }

    /** 监控表里一共几个方法（{@code methods=9} 的分母）。 */
    public static int watchedMethods() {
        int n = 0;
        for (int i = 0; i < WATCH.length; i++) {
            n += WATCH[i].size();
        }
        return n;
    }

    private HotfixJudgeWatch() {
        // 纯静态
    }

    // ═══════════════════════════════ 出口（由装配胶水注入，本类不认识 MC／log4j） ═══════════════════════════════

    /** 日志出口（胶水实现成 {@code [SHANHAI-HOTFIX] …} 那一行）。 */
    public interface LogSink {

        /** @param text 不带前缀的一整行 */
        void line(String text);
    }

    /**
     * 违规样本出口：把一次 {@code verdict=foreign} 当成一条<b>合成的 strike</b>喂进既有溯源链。
     *
     * <p>🔴 本类<b>不</b>自己选靶、不自己 retransform 对手的类：只交出
     * {@code (source, where, frames, atMillis)}，由 {@link HotfixTriageTap#submitViolation} 走
     * {@code HotfixAutoCounter} → {@code HotfixChainTriage} 那条<b>既有</b>分支。
     *
     * <p>⚠️ {@code source}/{@code where} <b>只用于本类自己的</b> {@code judge_watch_feed} 行：
     * strike 通道（{@link HotfixTriageTap}）<b>一个字都不改</b>，它的 {@code strike_source=}
     * 仍按老口径打 —— 两个生产者共用同一条通道，来源靠那一行分开判读。
     */
    public interface StrikeSink {

        /**
         * @param source   {@code judge_watch}（进日志 {@code strike_source=}）
         * @param where    {@code judge}（进日志 {@code where=}／{@code why=}）
         * @param frames   {@code 类#方法:行} 形态的帧（已按上限截断）
         * @param atMillis 抓栈时刻
         * @return {@code true} = 真的投递出去了
         */
        boolean submit(String source, String where, List<String> frames, long atMillis);
    }

    /**
     * 「这个类能不能采基线」的闸门（<b>由胶水注入</b>，本类不认 {@code HotfixBaseline}）。
     *
     * <p>为什么要有这个接口：我们自己写下去的字节必须<b>不采</b>（P2 实测"一次全 null 的 retransform
     * 等于 undo"，对本该是基线的地方采一次 = 亲手撤补丁）。这条判断需要 {@code HotfixBaseline}，
     * 而那一个类会把 {@code HotfixAutoCounter} 整串拉进依赖面 ⇒ 本类改成让胶水把它注入进来：
     * <b>本类的依赖面就只剩 {@code java.*}（一个项目类都不引）</b>，
     * 离线装置因此不受同包其它文件在改的影响（它们正在被另一条线改）。
     */
    public interface Guard {

        /**
         * @param internalName 内部名（{@code net/minecraft/…}）
         * @return {@code true} = 可以采（这个类里没有我们写下去的字节）
         */
        boolean mayCapture(String internalName);
    }

    /** 帧上限（与 {@code HotfixTriageTap.VIOLATION_MAX_FRAMES = 16} 同口径；本类不引那个类）。 */
    public static final int FRAME_LIMIT = 16;

    // ═══════════════ 🆕 2026-11-21（P2）：**等 Instrumentation 就绪**，不再"一见面没拿到就放弃" ═══════════════

    /**
     * 一次会话最多等几拍（<b>1 拍 = 50 ms = 一个游戏 tick</b>；200 拍 = 10 s）。
     *
     * <p>为什么是 200：现场实测 prewarm 的 attach 从 {@code phase=begin} 到 {@code state=ready}
     * 只用 <b>524 ms</b>（≈11 拍）；留 20 倍余量覆盖冷启动/杀软/磁盘忙。
     */
    public static final int WAIT_MAX_TICKS = 200;

    /** 一拍多少毫秒（与游戏 tick 同口径）。 */
    public static final long WAIT_TICK_MILLIS = 50L;

    /**
     * {@link Instrumentation} 的来源（由胶水注入 {@code HotfixHolder::instrumentation}）。
     *
     * <p>为什么不直接传一个 {@code Instrumentation}：需要的是"到点再取一次"，
     * 传引用等于把"还没 attach 完"这个事实固定成 {@code null}。
     */
    public interface InstSource {

        /** 现在拿得到吗（拿不到 ⇒ {@code null}）。 */
        Instrumentation get();
    }

    /**
     * <b>等 Instrumentation 就绪之后再跑一次关键类监控</b>（本类的正式入口，胶水用它）。
     *
     * <p>用户口径（2026-11-21 实机）：{@code judge_watch_skip reason=no_instrumentation} 是在
     * {@code ServerStartedEvent} 上打的，而同一局热改后来<b>确实写成了字节</b> ⇒ Instrumentation
     * <b>存在</b>，只是那一刻 prewarm 的 attach 还没完成（现场：skip 在 08:41:28，attach ready 在
     * 之后近 90 行）。⇒ 这里改成"轮询最多 {@code maxTicks} 拍直到 {@code inst != null}"，
     * 只有<b>等满</b>还拿不到才打 {@code judge_watch_skip reason=no_instrumentation waited_ticks=…}。
     *
     * <p>⚠️ 它在<b>调用方的线程</b>上睡（胶水把它放在一个 daemon 线程里，绝不占服务端主线程）。
     *
     * @param source     Instrumentation 来源
     * @param maxTicks   最多等几拍
     * @param tickMillis 一拍多少毫秒
     * @return {@code true} = 真的判过了（或之前就判过）；{@code false} = 等满仍未就绪／判据失败
     */
    public static boolean runSessionWhenReady(InstSource source, int maxTicks, long tickMillis,
                                              File baselineFile, LogSink log, StrikeSink strike, Guard guard) {
        if (sessionDone) {
            return true;
        }
        int waited = 0;
        Instrumentation inst = source == null ? null : source.get();
        if (inst == null) {
            // 一行"我在等"（INFO，一次会话最多一行）—— 现场一眼就能看出"不是没能力，是在等 attach"
            say(log, "judge_watch_wait reason=no_instrumentation max_ticks=" + maxTicks
                    + " tick_ms=" + tickMillis + " note=等 Instrumentation 就绪（prewarm attach 还没完成）");
        }
        final int ticks = maxTicks <= 0 ? 0 : maxTicks;
        while (inst == null && waited < ticks) {
            try {
                Thread.sleep(tickMillis <= 0L ? 1L : tickMillis);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
            waited++;
            inst = source == null ? null : source.get();
        }
        if (inst == null) {
            say(log, "judge_watch_skip class=* reason=no_instrumentation"
                    + " waited_ticks=" + waited + " max_ticks=" + ticks
                    + " note=等满仍未就绪：不采基线（不猜、不写盘）");
            return false;
        }
        if (waited > 0) {
            say(log, "judge_watch_wait_done waited_ticks=" + waited + " state=ready");
        }
        return runSession(inst, baselineFile, log, strike, guard, waited);
    }

    /**
     * <b>跑一次</b>（每个 JVM 会话只真跑一次；重复调用直接返回 {@code true}）。
     *
     * <p>流程：拿基线文件 → 不存在／认不出来 ⇒ 算一遍并落盘（{@code verdict=baseline}，<b>不报 foreign</b>）；
     * 存在 ⇒ 逐类算指纹并与基线比 → 打 {@code judge_watch} 行 → {@code foreign} 的喂进溯源链。
     *
     * <p>🔴 全程 try/catch：判据自己坏了<b>绝不许</b>影响任何游戏行为，也不许把基线写坏。
     *
     * @param inst          {@link Instrumentation}（{@code null} ⇒ 只打一行 skip，不写盘）
     * @param baselineFile  落盘文件（{@code config/shanhai/judge-baseline.json}）
     * @param log           日志出口
     * @param strike        违规样本出口（可 {@code null} ⇒ 只记日志不喂）
     * @param guard         "能不能采"的闸门（{@code null} ⇒ 一律采；胶水传 {@code HotfixBaseline} 那一条）
     * @return {@code true} = 这一趟真的判过了（或之前就判过）
     */
    public static boolean runSession(Instrumentation inst, File baselineFile, LogSink log, StrikeSink strike,
                                     Guard guard) {
        return runSession(inst, baselineFile, log, strike, guard, 0);
    }

    /** {@link #runSession(Instrumentation, File, LogSink, StrikeSink, Guard)} ＋ 已经等了几拍（进 skip 行）。 */
    static boolean runSession(Instrumentation inst, File baselineFile, LogSink log, StrikeSink strike,
                              Guard guard, int waitedTicks) {
        if (sessionDone) {
            return true;
        }
        if (inst == null || baselineFile == null) {
            say(log, "judge_watch_skip class=* reason=no_instrumentation"
                    + " waited_ticks=" + Math.max(0, waitedTicks)
                    + " note=没有 Instrumentation 就不采基线（不猜、不写盘）");
            return false;
        }
        sessionDone = true;
        try {
            return runOnce(inst, baselineFile, log, strike, guard);
        } catch (Throwable t) {
            say(log, "judge_watch_skip class=* reason=session_" + tok(simpleName(t)));
            return false;
        }
    }

    /** 重新允许跑（命令／离线自检用；正常游戏流程不调）。 */
    public static void resetSession() {
        sessionDone = false;
    }

    /** 会话跑过了吗（判读用）。 */
    public static boolean sessionDone() {
        return sessionDone;
    }

    private static boolean runOnce(Instrumentation inst, File baselineFile, LogSink log, StrikeSink strike,
                                    Guard guard) {
        final Map<String, Map<String, String>> saved = baselineFile.isFile()
                ? readBaseline(baselineFile) : null;
        final boolean writeMode = saved == null;
        final Map<String, Map<String, String>> now = new LinkedHashMap<String, Map<String, String>>();
        int absent = 0;
        final long atMillis = System.currentTimeMillis();

        for (int i = 0; i < WATCH.length; i++) {
            final Watch w = WATCH[i];
            final Class<?> cls = load(w.dotName);
            if (cls == null) {
                absent++;
                say(log, "judge_watch_skip class=" + w.dotName
                        + " reason=class_not_present client_only=" + w.clientOnly);
                continue;
            }
            final byte[] bytes = bytesOf(inst, cls, w, guard);
            if (bytes == null) {
                absent++;
                say(log, "judge_watch_skip class=" + w.dotName + " reason=bytes_unavailable"
                        + " client_only=" + w.clientOnly);
                continue;
            }
            final Map<String, String> fps = parseMethods(w, bytes);
            if (fps == null) {
                absent++;
                say(log, "judge_watch_skip class=" + w.dotName + " reason=classfile_parse_failed");
                continue;
            }
            now.put(w.dotName, fps);
            final Map<String, String> base = saved == null ? null : saved.get(w.dotName);
            final ClassVerdict v = judge(w, base, fps, writeMode);
            say(log, judgeLine(v));
            if (v.foreign() && strike != null) {
                final List<String> frames = framesOf();
                final boolean fed = strike.submit("judge_watch", "judge", frames, atMillis);
                // 🔴 来源只落在**这一行**：strike 通道本身（HotfixTriageTap）一个字都不改，
                //    它的 strike_source= 仍按老口径打 violation —— 两个生产者共用同一条通道，
                //    靠这一行把"这条是从关键类监控来的"钉死。
                say(log, "judge_watch_feed source=judge_watch where=judge"
                        + " class=" + w.dotName
                        + " methods=" + listText(v.changed)
                        + " frames=" + frames.size() + " fed=" + fed);
            }
        }

        if (writeMode) {
            if (now.isEmpty()) {
                say(log, "judge_watch_skip class=* reason=no_classes_written"
                        + " note=一个类都没采到 ⇒ 绝不写一份空基线（空基线会把之后每一次都判成 clean）");
                return false;
            }
            final int methods = countMethods(now);
            final File dir = baselineFile.getParentFile();
            try {
                if (dir != null && !dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
                    say(log, "judge_watch_skip class=* reason=baseline_mkdir_failed path="
                            + baselineFile.getAbsolutePath());
                    return false;
                }
                writeBaseline(baselineFile, now, atMillis);
            } catch (Throwable t) {
                say(log, "judge_watch_skip class=* reason=baseline_write_"
                        + tok(simpleName(t)) + " path=" + baselineFile.getAbsolutePath());
                return false;
            }
            say(log, "judge_baseline_written classes=" + now.size() + " methods=" + methods
                    + " path=" + baselineFile.getAbsolutePath());
            if (absent > 0) {
                say(log, "judge_watch_skip class=* reason=absent_classes count=" + absent
                        + " note=没采到的类不进基线（下次启动它们若出现，判 foreign 的依据是「基线里没有」这一条）");
            }
            return true;
        }
        return true;
    }

    /** 基线里一共有几个方法条目。 */
    private static int countMethods(Map<String, Map<String, String>> table) {
        int n = 0;
        for (Map<String, String> m : table.values()) {
            if (m != null) {
                n += m.size();
            }
        }
        return n;
    }

    // ═══════════════════════════════ 判定（纯函数，离线可验） ═══════════════════════════════

    /** 一次比对的结论（不可变）。 */
    public static final class ClassVerdict {

        /** {@code baseline}／{@code clean}／{@code foreign}（三个，只有这三个）。 */
        public final String verdict;

        /** 监控表那一行。 */
        public final Watch watch;

        /** 这个类里一共认出了几个监控方法（{@code methods=}）。 */
        public final int methods;

        /** 变了的那几个（<b>只可能</b>是 {@link Watch#methods} 里的字面量，顺序＝监控表顺序）。 */
        public final List<String> changed;

        ClassVerdict(String verdict, Watch watch, int methods, List<String> changed) {
            this.verdict = verdict;
            this.watch = watch;
            this.methods = methods;
            this.changed = changed == null ? new ArrayList<String>(0) : changed;
        }

        /** 是不是"有人动了我们监控的方法"。 */
        public boolean foreign() {
            return "foreign".equals(verdict);
        }

        /** 是不是"这一趟刚写基线"。 */
        public boolean baseline() {
            return "baseline".equals(verdict);
        }
    }

    /** verdict 的三个字面量（日志口径的唯一真源）。 */
    public static final String VERDICT_BASELINE = "baseline";

    public static final String VERDICT_CLEAN = "clean";

    public static final String VERDICT_FOREIGN = "foreign";

    /**
     * 🔴 <b>比对本体</b>（纯函数）。
     *
     * <pre>
     *   writeMode == true           ⇒ baseline（刚写的那份就是基线，不报 foreign）
     *   base 里没有这个类            ⇒ clean（这一版监控表第一次见它；写盘由调用方负责）
     *   base 有、且某个监控方法对不上 ⇒ foreign（changed 只列对不上的那几个）
     *   其余                        ⇒ clean
     * </pre>
     *
     * <p>规则细节（都是有意为之）：
     * <ul>
     *   <li>只比<b>监控表里列出的</b>方法 —— 别人的改动落在别的方法上不算（用户要求）；</li>
     *   <li>基线里有、这次<b>整段没了</b>（方法消失）也算 {@code changed}：那个 {@code Code} 已经不在了；</li>
     *   <li>这次有、基线里没有 ⇒ <b>不算</b>（那是监控表扩了条目，不是别人动的手）。</li>
     * </ul>
     */
    public static ClassVerdict judge(Watch w, Map<String, String> base, Map<String, String> current,
                                     boolean writeMode) {
        if (w == null) {
            return new ClassVerdict(VERDICT_CLEAN, null, 0, new ArrayList<String>(0));
        }
        final Map<String, String> cur = current == null ? new LinkedHashMap<String, String>() : current;
        if (writeMode) {
            return new ClassVerdict(VERDICT_BASELINE, w, cur.size(), new ArrayList<String>(0));
        }
        if (base == null || base.isEmpty()) {
            return new ClassVerdict(VERDICT_CLEAN, w, cur.size(), new ArrayList<String>(0));
        }
        final List<String> changed = new ArrayList<String>(2);
        for (int i = 0; i < w.methods.length; i++) {
            final String m = w.methods[i];
            final String want = base.get(m);
            if (want == null || want.isEmpty()) {
                continue;                       // 基线里没这一条 ⇒ 无从比对（监视表扩条目）
            }
            final String got = cur.get(m);
            if (got == null || !want.equals(got)) {
                changed.add(m);                 // 变了，或者整个方法没了
            }
        }
        return new ClassVerdict(changed.isEmpty() ? VERDICT_CLEAN : VERDICT_FOREIGN, w, cur.size(), changed);
    }

    /**
     * {@code judge_watch} 那一行（字段顺序固定：{@code class= / methods= / changed= / verdict=}）。
     *
     * <pre>
     *   judge_watch class=net.minecraft.world.entity.LivingEntity methods=4 changed=[m_21224_] verdict=foreign
     *   judge_watch class=net.minecraft.world.entity.LivingEntity methods=4 changed=[] verdict=clean
     * </pre>
     */
    public static String judgeLine(ClassVerdict v) {
        if (v == null || v.watch == null) {
            return "judge_watch class=? methods=0 changed=[] verdict=clean";
        }
        return "judge_watch class=" + v.watch.dotName
                + " methods=" + v.methods
                + " changed=" + listText(v.changed)
                + " verdict=" + v.verdict;
    }

    /** {@code [a,b]} 形态（空 ⇒ {@code []}）。 */
    static String listText(List<String> names) {
        final StringBuilder sb = new StringBuilder(24);
        sb.append('[');
        if (names != null) {
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(names.get(i));
            }
        }
        sb.append(']');
        return sb.toString();
    }

    // ═══════════════════════════════ 取字节（= 启动期基线） ═══════════════════════════════

    /** 按监控表的名字加载类（不初始化；拿不到 ⇒ {@code null}）。 */
    private static Class<?> load(String dotName) {
        final ClassLoader[] loaders = {
                HotfixJudgeWatch.class.getClassLoader(),
                Thread.currentThread().getContextClassLoader(),
                ClassLoader.getSystemClassLoader()
        };
        for (int i = 0; i < loaders.length; i++) {
            final ClassLoader cl = loaders[i];
            if (cl == null) {
                continue;
            }
            try {
                final Class<?> c = Class.forName(dotName, false, cl);
                if (c != null) {
                    return c;
                }
            } catch (Throwable ignored) {
                // 这个加载器没有它 ⇒ 换下一个（专用服务端上的两个客户端类会全落空 = 预期）
            }
        }
        return null;
    }

    /**
     * 取一个类<b>这次启动的基线字节</b>（= JVM 手上的 initial class bytes）。
     *
     * <p>🔴 两条硬约束：
     * <ol>
     *   <li><b>我们自己改过的类不许取</b>（{@link Guard#mayCapture} 返回 false ⇒ 跳过）：
     *       P2 实测"全 null 的 retransform 等于 undo"，对它做等于亲手撤补丁；</li>
     *   <li>临时 transformer 只记字节、<b>永远 {@code return null}</b>，并在 {@code finally} 里卸掉
     *       （常驻 = 每个类加载都多一次回调，这笔账不值得）。</li>
     * </ol>
     */
    private static byte[] bytesOf(Instrumentation inst, Class<?> cls, Watch w, Guard guard) {
        if (inst == null || cls == null || w == null) {
            return null;
        }
        try {
            if (!inst.isRetransformClassesSupported()) {
                return null;
            }
            if (guard != null && !guard.mayCapture(w.internalName)) {
                return null;                    // 类里有我们写下去的字节 ⇒ 这一趟不采（不许自己撤补丁）
            }
            final Reader reader = new Reader(w.internalName);
            boolean added = false;
            try {
                inst.addTransformer(reader, true);
                added = true;
                inst.retransformClasses(cls);
            } finally {
                if (added) {
                    try {
                        inst.removeTransformer(reader);
                    } catch (Throwable ignored) {
                        // 卸不掉只意味着多一次回调（它按名字短路），不算失败
                    }
                }
            }
            return reader.bytes;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 只记字节的临时 transformer（**永远** {@code return null}）。
     *
     * <p>🔴 返回任何数组（哪怕内容一样）都可能让 {@code retransformClasses} 重新定义这个类 ——
     * 那就从"取基线"变成了"又改了一次"，判据的整个意义就没了。异常也全吞（抛出去会被 JVM
     * 记成"这个 transformer 坏了"，还可能让 retransform 半失败）。
     */
    private static final class Reader implements ClassFileTransformer {

        private final String internalName;

        /** 收到的字节（只留第一份；给"首见"这个口径）。 */
        volatile byte[] bytes;

        Reader(String internalName) {
            this.internalName = internalName;
        }

        @Override
        @SuppressWarnings("deprecation")
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            try {
                if (className == null || internalName == null || classfileBuffer == null) {
                    return null;
                }
                if (!internalName.equals(className)) {
                    return null;
                }
                if (bytes == null) {
                    bytes = classfileBuffer.clone();
                }
            } catch (Throwable ignored) {
                // 采不到 = 这一次判不了；绝不能把 retransform 带崩
            }
            return null;
        }
    }

    /** 抓一帧表（{@code 类#方法:行}，与 {@code HotfixTargetResolver} 同一口径；上限 16 帧）。 */
    static List<String> framesOf() {
        final List<String> out = new ArrayList<String>(16);
        try {
            final StackTraceElement[] st = Thread.currentThread().getStackTrace();
            for (int i = 0; st != null && i < st.length && out.size() < FRAME_LIMIT; i++) {
                final StackTraceElement e = st[i];
                if (e == null) {
                    continue;
                }
                out.add(e.getClassName() + "#" + e.getMethodName() + ":" + e.getLineNumber());
            }
        } catch (Throwable ignored) {
            // 拿不到栈 ⇒ 空帧表（既有链路会自己判 no_origin_candidate）
        }
        return out;
    }

    // ═══════════════════════════════ class 文件解析：方法 Code 指纹 ═══════════════════════════════

    /**
     * 算一个类里<b>监控表列出的那些方法</b>的指纹。
     *
     * @return {@code 方法名 ⇒ sha16}；解析失败 ⇒ {@code null}（调用方按 {@code classfile_parse_failed} 记）
     */
    static Map<String, String> parseMethods(Watch w, byte[] b) {
        if (w == null || b == null) {
            return null;
        }
        final Cf cf = Cf.parse(b);
        if (cf == null) {
            return null;
        }
        final Map<String, String> out = new LinkedHashMap<String, String>();
        for (int i = 0; i < w.methods.length; i++) {
            final String canonical = w.methods[i];
            final String alias = i < w.aliases.length ? w.aliases[i] : null;
            final int at = cf.findMethod(canonical, alias);
            if (at < 0) {
                continue;                       // 这个类里没有它（老/新版本差异）⇒ 不记
            }
            final String fp = cf.methodFingerprint(at);
            if (fp == null) {
                return null;                    // 有一段 Code 读不动 ⇒ 整个类判不了（不半信半疑）
            }
            out.put(canonical, fp);
        }
        return out;
    }

    /** 一个 class 文件的最小结构（只保留"找方法 ＋ 读 Code"需要的东西）。 */
    private static final class Cf {

        static final int U1 = 1;
        static final int U2 = 2;
        static final int S4 = 4;

        final byte[] b;

        /** 常量池：tag 与两个载荷整数（含义随 tag 变）。 */
        int[] tag;
        int[] a;
        int[] c;

        /** 常量池里 LONG/DOUBLE 的 8 字节值（按需）。 */
        long[] wide;

        int methodCount;
        int methodTableAt;

        private Cf(byte[] b) {
            this.b = b;
        }

        static Cf parse(byte[] b) {
            if (b == null || b.length < 16 || u1(b, 0) != 0xCA || u1(b, 1) != 0xFE
                    || u1(b, 2) != 0xBA || u1(b, 3) != 0xBE) {
                return null;                    // 不是 class 文件
            }
            final Cf cf = new Cf(b);
            try {
                if (!cf.parseConstantPool()) {
                    return null;
                }
                int p = cf.cpEnd;
                p += 6;                         // access_flags / this_class / super_class
                final int ifc = u2(b, p);
                p += 2 + ifc * 2;
                final int fields = u2(b, p);
                p += 2;
                for (int i = 0; i < fields; i++) {
                    p = skipMember(b, p);
                }
                cf.methodCount = u2(b, p);
                cf.methodTableAt = p + 2;
                return cf;
            } catch (Throwable t) {
                return null;
            }
        }

        /** 常量池结尾的下标。 */
        int cpEnd;

        private boolean parseConstantPool() {
            final int n = u2(b, 8);
            if (n <= 0 || n > 0xFFFF) {
                return false;
            }
            tag = new int[n];
            a = new int[n];
            c = new int[n];
            wide = new long[n];
            int p = 10;
            for (int i = 1; i < n; i++) {
                final int t = u1(b, p);
                tag[i] = t;
                switch (t) {
                    case 1:                     // Utf8
                        a[i] = p + 3;
                        c[i] = u2(b, p + 1);
                        p += 3 + c[i];
                        break;
                    case 3:                     // Integer
                    case 4:                     // Float
                        wide[i] = s4(b, p + 1);
                        p += 5;
                        break;
                    case 5:                     // Long
                    case 6:                     // Double
                        wide[i] = ((long) s4(b, p + 1) << 32) | (s4(b, p + 5) & 0xFFFFFFFFL);
                        p += 9;
                        i++;                    // 占两个槽
                        break;
                    case 7:                     // Class
                    case 8:                     // String
                    case 16:                    // MethodType
                    case 19:                    // Module
                    case 20:                    // Package
                        a[i] = u2(b, p + 1);
                        p += 3;
                        break;
                    case 9:                     // Fieldref
                    case 10:                    // Methodref
                    case 11:                    // InterfaceMethodref
                    case 12:                    // NameAndType
                    case 17:                    // Dynamic
                    case 18:                    // InvokeDynamic
                        a[i] = u2(b, p + 1);
                        c[i] = u2(b, p + 3);
                        p += 5;
                        break;
                    case 15:                    // MethodHandle
                        a[i] = u1(b, p + 1);
                        c[i] = u2(b, p + 2);
                        p += 4;
                        break;
                    default:
                        return false;           // 不认识的 tag ⇒ 宁可判"解析失败"
                }
            }
            cpEnd = p;
            return true;
        }

        /** {@code field_info}／{@code method_info} 的表头 ＋ 属性表整体跳过一个（前面 6 字节已含）。 */
        static int skipMember(byte[] b, int p) {
            final int attrs = u2(b, p + 6);
            int q = p + 8;
            for (int i = 0; i < attrs; i++) {
                q += 6 + s4(b, q + 2);
            }
            return q;
        }

        /**
         * 找方法：先按 SRG 名找，找不到再按别名找。
         *
         * @return 整个 {@code method_info}（含 attributes_count）的起始下标；没有 ⇒ {@code -1}
         */
        int findMethod(String canonical, String alias) {
            int p = methodTableAt;
            final int[] hit = {-1, -1};
            for (int i = 0; i < methodCount; i++) {
                final String name = utf8(u2(b, p + 2));
                if (canonical != null && canonical.equals(name) && hit[0] < 0) {
                    hit[0] = p;
                } else if (alias != null && alias.equals(name) && hit[1] < 0) {
                    hit[1] = p;
                }
                p = skipMember(b, p);
                if (hit[0] >= 0) {
                    return hit[0];              // SRG 名一旦命中就不再看后面（生产里必然命中）
                }
            }
            return hit[1];
        }

        /** 一个方法的 {@code Code} 指纹；没有 {@code Code}（abstract/native）⇒ {@code null}。 */
        String methodFingerprint(int memberAt) {
            final int attrs = u2(b, memberAt + 6);
            int q = memberAt + 8;
            for (int i = 0; i < attrs; i++) {
                final String an = utf8(u2(b, q));
                final int len = s4(b, q + 2);
                final int body = q + 6;
                if (CODE.equals(an)) {
                    return codeFingerprint(body, len, utf8(u2(b, memberAt + 4)));
                }
                q = body + len;
            }
            return null;
        }

        /** {@code Code} 属性本体 → 指纹（剔除常量池顺序，见类注释）。 */
        String codeFingerprint(int at, int len, String desc) {
            if (len < 12) {
                return null;
            }
            final int maxStack = u2(b, at);
            final int maxLocals = u2(b, at + 2);
            final int codeLen = s4(b, at + 4);
            final int codeAt = at + 8;
            if (codeLen < 0 || codeLen > len || codeAt + codeLen > b.length) {
                return null;
            }
            final ByteArrayOutputStream out = new ByteArrayOutputStream(256 + codeLen);
            u2out(out, maxStack);
            u2out(out, maxLocals);
            s4out(out, codeLen);
            if (!canonicalCode(b, codeAt, codeLen, this, out)) {
                return null;
            }
            final int etAt = codeAt + codeLen;
            if (etAt + 2 > b.length) {
                return null;
            }
            final int et = u2(b, etAt);
            u2out(out, et);
            for (int i = 0; i < et; i++) {
                final int e = etAt + 2 + i * 8;
                if (e + 8 > b.length) {
                    return null;
                }
                u2out(out, u2(b, e));
                u2out(out, u2(b, e + 2));
                u2out(out, u2(b, e + 4));
                final int catchType = u2(b, e + 6);
                if (catchType == 0) {
                    out.write(0x00);
                } else {
                    writeToken(out, classToken(catchType));
                }
            }
            writeToken(out, desc == null ? "" : desc);
            return sha16(out.toByteArray());
        }

        // ------------------------------ 常量池 → 内容 token（这就是"剔除常量池顺序"的落点）

        /** {@code CONSTANT_Utf8} 的原文字符串；不是 Utf8／下标非法 ⇒ {@code null}。 */
        String utf8(int idx) {
            if (idx <= 0 || idx >= tag.length || tag[idx] != 1) {
                return null;
            }
            try {
                return new String(b, a[idx], c[idx], StandardCharsets.UTF_8);
            } catch (Throwable t) {
                return null;
            }
        }

        /** {@code CONSTANT_Class} → 原文类名（不带下标）。 */
        String classToken(int idx) {
            if (idx <= 0 || idx >= tag.length || tag[idx] != 7) {
                return "?class";
            }
            final String n = utf8(a[idx]);
            return "C:" + (n == null ? "?" : n);
        }

        /** 任意常量池条目 → <b>内容</b>（不是下标）。 */
        String token(int idx) {
            if (idx <= 0 || idx >= tag.length) {
                return "?";
            }
            switch (tag[idx]) {
                case 1:
                    return "U:" + nz(utf8(idx));
                case 3:
                    return "I:" + wide[idx];
                case 4:
                    return "F:" + Long.toHexString(wide[idx] & 0xFFFFFFFFL);
                case 5:
                    return "J:" + wide[idx];
                case 6:
                    return "D:" + Long.toHexString(wide[idx]);
                case 7:
                    return classToken(idx);
                case 8:
                    return "S:" + nz(utf8(a[idx]));
                case 9:
                case 10:
                case 11:
                    return "M:" + nz(utf8(a[a[idx]])) + "." + nz(utf8(a[c[idx]]))
                            + ":" + nz(utf8(c[c[idx]]));
                case 12:
                    return "N:" + nz(utf8(a[idx])) + ":" + nz(utf8(c[idx]));
                case 15:
                    return "H:" + a[idx] + ":" + token(c[idx]);
                case 16:
                    return "T:" + nz(utf8(a[idx]));
                case 17:
                case 18:
                    // ⚠️ 刻意<b>不</b>带 bootstrap 下标（BootstrapMethods 的顺序会随重编译变），
                    //    只认 name+desc：对"常量池挪位"稳定，代价是换 bootstrap 方法认不出来。
                    return "Y:" + nz(utf8(a[c[idx]])) + ":" + nz(utf8(c[c[idx]]));
                case 19:
                case 20:
                    return "P:" + nz(utf8(a[idx]));
                default:
                    return "?tag" + tag[idx];
            }
        }

        private static String nz(String s) {
            return s == null ? "?" : s;
        }
    }

    /** {@code Code} 属性名（常量池里的 UTF8）。 */
    private static final String CODE = "Code";

    /**
     * 把一段字节码按<b>规范化</b>形式写进 {@code out}：常量池引用写内容，其余操作数原样。
     *
     * <p>编码是自描述的（每个操作数前面一个标记字节），所以"原样字节"和"解析出来的内容"
     * 永远不会互相冒充：
     * <pre>
     *   0x00 ＋ 原始操作数字节（不定长）
     *   0x01 ＋ u2 长度 ＋ 常量池条目内容的 UTF-8（不定长）
     * </pre>
     *
     * @return {@code false} = 这段字节码走不通（非法 opcode／越界）⇒ 整类判不了
     */
    private static boolean canonicalCode(byte[] b, int codeAt, int codeLen, Cf cp, ByteArrayOutputStream out) {
        int pc = 0;
        while (pc < codeLen) {
            final int op = u1(b, codeAt + pc);
            final int len = insnLength(b, codeAt, codeLen, pc, op);
            if (len <= 0 || pc + len > codeLen) {
                return false;
            }
            out.write(op);
            final int refKind = cpRefKind(op);
            if (refKind == 0) {
                out.write(0x00);
                out.write(b, codeAt + pc + 1, len - 1);
            } else if (refKind == Cf.U1) {
                final int idx = u1(b, codeAt + pc + 1);
                out.write(0x01);
                writeToken(out, cp.token(idx));
                out.write(0x00);
                out.write(b, codeAt + pc + 2, len - 2);
            } else {
                final int idx = u2(b, codeAt + pc + 1);
                out.write(0x01);
                writeToken(out, cp.token(idx));
                out.write(0x00);
                out.write(b, codeAt + pc + 3, len - 3);
            }
            pc += len;
        }
        return pc == codeLen;
    }

    /** 指令长度表（{@code -1} = 变长：tableswitch／lookupswitch／wide）。 */
    private static final int[] LEN = new int[256];

    static {
        for (int i = 0; i < LEN.length; i++) {
            LEN[i] = 1;
        }
        // ---- 2 字节
        LEN[0x10] = 2;                                          // bipush
        LEN[0x12] = 2;                                          // ldc
        LEN[0x15] = 2; LEN[0x16] = 2; LEN[0x17] = 2;            // iload/lload/fload
        LEN[0x18] = 2; LEN[0x19] = 2;                           // dload/aload
        for (int i = 0x36; i <= 0x3A; i++) {                    // istore…astore（带下标）
            LEN[i] = 2;
        }
        LEN[0xA9] = 2;                                          // ret
        LEN[0xBC] = 2;                                          // newarray
        // ---- 3 字节
        LEN[0x11] = 3;                                          // sipush
        LEN[0x13] = 3; LEN[0x14] = 3;                           // ldc_w / ldc2_w
        LEN[0x84] = 3;                                          // iinc
        for (int i = 0x99; i <= 0xA8; i++) {                    // if…／goto／jsr
            LEN[i] = 3;
        }
        for (int i = 0xB2; i <= 0xB8; i++) {                    // getstatic…invokestatic
            LEN[i] = 3;
        }
        LEN[0xBB] = 3;                                          // new
        LEN[0xBD] = 3;                                          // anewarray
        LEN[0xC0] = 3; LEN[0xC1] = 3;                           // checkcast / instanceof
        LEN[0xC6] = 3; LEN[0xC7] = 3;                           // ifnull / ifnonnull
        // ---- 4 字节
        LEN[0xC5] = 4;                                          // multianewarray
        // ---- 5 字节
        LEN[0xB9] = 5;                                          // invokeinterface
        LEN[0xBA] = 5;                                          // invokedynamic
        LEN[0xC8] = 5; LEN[0xC9] = 5;                           // goto_w / jsr_w
        // ---- 变长
        LEN[0xAA] = -1; LEN[0xAB] = -1; LEN[0xC4] = -1;
    }

    private static int insnLength(byte[] b, int codeAt, int codeLen, int pc, int op) {
        final int base = LEN[op];
        if (base >= 0) {
            return base;
        }
        if (op == 0xAA) {                                       // tableswitch
            final int p = (pc + 4) & ~3;
            if (p + 12 > codeLen) {
                return -1;
            }
            final int lo = s4(b, codeAt + p + 4);
            final int hi = s4(b, codeAt + p + 8);
            final long n = (long) hi - (long) lo + 1L;
            if (n < 0L || n > codeLen) {
                return -1;
            }
            return p + 12 + (int) n * 4 - pc;
        }
        if (op == 0xAB) {                                       // lookupswitch
            final int p = (pc + 4) & ~3;
            if (p + 8 > codeLen) {
                return -1;
            }
            final int n = s4(b, codeAt + p + 4);
            if (n < 0 || n > codeLen) {
                return -1;
            }
            return p + 8 + n * 8 - pc;
        }
        // wide
        if (pc + 1 >= codeLen) {
            return -1;
        }
        final int op2 = u1(b, codeAt + pc + 1);
        return op2 == 0x84 ? 6 : 4;
    }

    /** 这个 opcode 的操作数里有几个字节是常量池下标：0=没有，1=u1，2=u2。 */
    private static int cpRefKind(int op) {
        switch (op) {
            case 0x12:                                          // ldc
                return Cf.U1;
            case 0x13:                                          // ldc_w
            case 0x14:                                          // ldc2_w
            case 0xB2: case 0xB3: case 0xB4: case 0xB5:         // get/put static/field
            case 0xB6: case 0xB7: case 0xB8:                    // invoke*
            case 0xB9:                                          // invokeinterface
            case 0xBA:                                          // invokedynamic
            case 0xBB:                                          // new
            case 0xBD:                                          // anewarray
            case 0xC0: case 0xC1:                               // checkcast / instanceof
            case 0xC5:                                          // multianewarray
                return Cf.U2;
            default:
                return 0;
        }
    }

    // ═══════════════════════════════ 基线文件的读写（自家格式，逐行可读） ═══════════════════════════════

    /**
     * 写基线。
     *
     * <pre>
     *   {
     *     "version": 1,
     *     "kind": "judge_watch_baseline",
     *     "written_at": 1730000000000,
     *     "net.minecraft.world.entity.LivingEntity": {"m_21223_": "0123456789abcdef", …},
     *     …
     *   }
     * </pre>
     *
     * <p>为什么是"一个类一行"的扁平对象：读的时候能<b>逐行严格校验</b>（认不出的行一律当
     * "这份基线不可用"→ 重写），比引一个 JSON 库便宜、也比"自己写个递归下降"少一半出错面。
     */
    public static void writeBaseline(File file, Map<String, Map<String, String>> table, long nowMillis)
            throws java.io.IOException {
        final StringBuilder sb = new StringBuilder(1024);
        sb.append("{\n");
        sb.append("  \"version\": ").append(BASELINE_VERSION).append(",\n");
        sb.append("  \"kind\": \"").append(KIND).append("\",\n");
        sb.append("  \"written_at\": ").append(nowMillis);
        if (table != null) {
            for (Map.Entry<String, Map<String, String>> e : table.entrySet()) {
                if (e.getKey() == null) {
                    continue;
                }
                sb.append(",\n  \"").append(e.getKey()).append("\": {");
                final Map<String, String> ms = e.getValue();
                boolean first = true;
                if (ms != null) {
                    for (Map.Entry<String, String> m : ms.entrySet()) {
                        if (m.getKey() == null || m.getValue() == null) {
                            continue;
                        }
                        if (!first) {
                            sb.append(", ");
                        }
                        first = false;
                        sb.append('"').append(m.getKey()).append("\": \"").append(m.getValue()).append('"');
                    }
                }
                sb.append('}');
            }
        }
        sb.append("\n}\n");
        Files.write(file.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 读基线。
     *
     * @return {@code 类名 ⇒ (方法名 ⇒ sha)}；文件坏了／版本不是
     *         {@link #BASELINE_VERSION} ⇒ {@code null}（调用方据此<b>重写</b>，不许拿半份基线判）
     */
    public static Map<String, Map<String, String>> readBaseline(File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        try {
            return parse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        } catch (Throwable t) {
            return null;
        }
    }

    /** {@link #readBaseline} 的纯文本版（离线自检直接喂字符串）。 */
    public static Map<String, Map<String, String>> parse(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        Integer version = null;
        final Map<String, Map<String, String>> out = new LinkedHashMap<String, Map<String, String>>();
        final String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            final String line = lines[i].trim().replace("\r", "");
            if (line.isEmpty() || "{".equals(line) || "}".equals(line)) {
                continue;
            }
            if (line.startsWith("\"version\"")) {
                version = Integer.valueOf(intAfter(line, ':'));
                continue;
            }
            if (line.startsWith("\"kind\"") || line.startsWith("\"written_at\"")) {
                continue;
            }
            // 键：从第一个引号到下一个引号（不按"冒号前一位"算 —— 有人手改时多加一个空格就错位）
            final int keyEnd = line.indexOf('"', 1);
            if (line.charAt(0) != '"' || keyEnd <= 1) {
                return null;
            }
            final String q = line.substring(1, keyEnd);
            if (q.indexOf('.') < 0) {
                return null;                    // 类名必然带点；不带点 ⇒ 这行不是我们写的 ⇒ 整份不可用
            }
            final int colon = line.indexOf(':', keyEnd);
            if (colon < 0) {
                return null;
            }
            final int open = line.indexOf('{', colon);
            final int close = line.lastIndexOf('}');
            if (open < 0 || close <= open) {
                return null;
            }
            final Map<String, String> ms = parsePairs(line.substring(open + 1, close));
            if (ms == null) {
                return null;
            }
            out.put(q, ms);
        }
        if (version == null || version.intValue() != BASELINE_VERSION) {
            return null;
        }
        return out;
    }

    /** {@code {"a": "1", "b": "2"}} 的内层。 */
    private static Map<String, String> parsePairs(String body) {
        final Map<String, String> out = new LinkedHashMap<String, String>();
        int p = 0;
        while (p < body.length()) {
            final int q1 = body.indexOf('"', p);
            if (q1 < 0) {
                break;
            }
            final int q2 = body.indexOf('"', q1 + 1);
            if (q2 < 0) {
                return null;
            }
            final int q3 = body.indexOf('"', q2 + 1);
            if (q3 < 0) {
                return null;
            }
            final int q4 = body.indexOf('"', q3 + 1);
            if (q4 < 0) {
                return null;
            }
            final String k = body.substring(q1 + 1, q2);
            final String v = body.substring(q3 + 1, q4);
            if (k.isEmpty() || v.isEmpty()) {
                return null;
            }
            out.put(k, v);
            p = q4 + 1;
        }
        return out;
    }

    /** {@code "version": 1} 里冒号后面那个整数。 */
    private static int intAfter(String line, char sep) {
        int p = line.indexOf(sep);
        if (p < 0) {
            return -1;
        }
        p++;
        while (p < line.length() && (line.charAt(p) == ' ' || line.charAt(p) == '\t')) {
            p++;
        }
        int q = p;
        while (q < line.length() && Character.isDigit(line.charAt(q))) {
            q++;
        }
        if (q <= p) {
            return -1;
        }
        try {
            return Integer.parseInt(line.substring(p, q));
        } catch (Throwable t) {
            return -1;
        }
    }

    // ═══════════════════════════════ 小工具 ═══════════════════════════════

    /** {@code sha256} 前 16 位十六进制小写（与 {@code HotfixBaseline#sha16} 同一口径）。 */
    static String sha16(byte[] bytes) {
        if (bytes == null) {
            return "?";
        }
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            final byte[] d = md.digest(bytes);
            final StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8 && i < d.length; i++) {
                final int v = d[i] & 0xFF;
                if (v < 0x10) {
                    sb.append('0');
                }
                sb.append(Integer.toHexString(v));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "?";
        }
    }

    private static void writeToken(ByteArrayOutputStream out, String text) {
        final byte[] s = (text == null ? "" : text).getBytes(StandardCharsets.UTF_8);
        u2out(out, s.length);
        out.write(s, 0, s.length);
    }

    private static void u2out(ByteArrayOutputStream out, int v) {
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static void s4out(ByteArrayOutputStream out, int v) {
        out.write((v >>> 24) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    /**
     * 日志字段里不许出现空格／竖线／换行（否则 grep 会把一行切错）。
     *
     * <p>⚠️ 为什么本类自带一份而不复用 {@code HotfixPatchReadback.tok}：那一个类会顺着
     * {@code HotfixTriageVerdict} → {@code HotfixTrigger} 拉出一条链，而本类<b>刻意</b>做到
     * "一个项目类都不引"（这样离线装置只编本类一个文件，不会被同包其它文件在改这件事拖住）。
     * 逻辑与那份逐字同口径：空格／TAB／竖线／全角竖线／换行 ⇒ 下划线。
     */
    static String tok(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "?";
        }
        final StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            final char c = raw.charAt(i);
            if (c == ' ' || c == '\t' || c == '|' || c == '\uFF5C' || c == '\n' || c == '\r') {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
    private static void say(LogSink log, String text) {
        if (log == null) {
            return;
        }
        try {
            log.line(text);
        } catch (Throwable ignored) {
            // 日志出口坏了不许影响判据
        }
    }

    private static String simpleName(Throwable t) {
        return t == null ? "?" : t.getClass().getSimpleName();
    }

    private static int u1(byte[] b, int p) {
        return b[p] & 0xFF;
    }

    private static int u2(byte[] b, int p) {
        return ((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF);
    }

    private static int s4(byte[] b, int p) {
        return ((b[p] & 0xFF) << 24) | ((b[p + 1] & 0xFF) << 16) | ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
    }
}
