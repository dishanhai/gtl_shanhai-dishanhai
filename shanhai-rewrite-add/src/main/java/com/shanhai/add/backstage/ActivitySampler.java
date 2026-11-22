package com.shanhai.add.backstage;

import com.shanhai.backstage.BackstageState;
import com.shanhai.client.backstage.BackstageProcess;

import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>主线程活动采样器</b>：回答「游戏主线程<b>此刻</b>在干什么」——一条 daemon 线程，每 250 ms
 * 从<b>外面</b>读一次主线程的栈（只读，绝不干预）。
 *
 * <h4>为什么需要它（用户 2026-11-21 提的两条）</h4>
 * <ol>
 *   <li><b>去找真正卡住加载的那部分内容</b> —— 而且<b>两个阶段不一样</b>：
 *       ① 游戏加载到标题；② 进存档。两者在这条采样线上是同一件事：都在 {@code phase=LOADING}
 *       下采样，标签（= 栈上第一个"不是框架"的帧）直接指出<b>那一拍是谁在占着主线程</b>；
 *       {@code phase=} 字段区分是哪一段。所以标题上的「正在加载：X」在①和②里都自动可用。</li>
 *   <li><b>挂一个通用钩子，它可以找出所有内容</b> —— 所以这条线<b>不点名任何类</b>：标签全部来自
 *       运行时栈，规则只有一张前缀白名单（见下）。任何"新出现的卡顿来源"都会被自动看见，
 *       不需要为它改一次代码。</li>
 * </ol>
 *
 * <h4>🔴 红线：标签里绝不许出现任何第三方 mod 的类名</h4>
 * <p>本类<b>没有</b>任何写死的类名/包名映射表。判定只有一条：从栈顶往下找第一个<b>不</b>命中
 * 白名单前缀的帧，取它的 {@code 简单类名.方法名}。白名单（末尾都带点 ⇒ 天然按包边界匹配）：
 * <pre>
 *   java. javax. jdk. sun. com.sun. com.shanhai. com.mojang. net.minecraft. net.minecraftforge.
 *   cpw. org.spongepowered. org.objectweb.asm. org.apache.logging. it.unimi.dsi. com.google.
 * </pre>
 * 全都是"加载框架自己的东西"，不是任何 mod。找不到这种帧（整栈都是框架帧）时，用<b>最深</b>的那个
 * 白名单帧的简单类名（例如 {@code SimpleReloadInstance}）——依然不含第三方名字。
 *
 * <h4>三样输出（都用 {@code [SHANHAI-ACT]} 前缀，grep 这一个词即可）</h4>
 * <pre>
 *   [SHANHAI-ACT] phase=LOADING label=X site=pkg.Cls#method held_ms=8123 samples=57   ← 每 2 秒，或标签一变立刻
 *   [SHANHAI-ACT] switch from=A to=B after_ms=1500                                   ← 标签切换当场
 *   [SHANHAI-ACT] top30s n=7 1:pkg.Cls#m=14500 2:pkg2.C2#m2=3000 3:...=250           ← 每 30 秒（只打前 3）
 * </pre>
 * {@code site=} 是内部键（{@code 全类名#方法名}），<b>只出现在日志里</b>，不进标题。
 *
 * <h4>线程安全 / 失败面</h4>
 * <p>所有对外读数都是 {@code volatile}；采样线程名 {@code shanhai-activity-sampler}、daemon=true。
 * 整个采样循环包在 {@code try/catch(Throwable)} 里：任何异常只打一行
 * {@code [SHANHAI-ACT] sample_failed err=<类名>}（同一个异常类只打一次），循环继续。
 * 本类<b>不碰 Minecraft 的任何对象</b>（只用 JMX 读栈），因此随便哪个线程调用 {@link #startIfNeeded()} 都安全。
 */
public final class ActivitySampler {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 日志前缀（判据行；grep 这一个词即可）。 */
    private static final String PREFIX = "[SHANHAI-ACT]";

    /** 目标线程名：Minecraft 客户端主线程（渲染线程）就叫这个。 */
    private static final String TARGET_THREAD_NAME = "Render thread";

    /** 采样周期：250 ms。 */
    public static final long PERIOD_MS = 250L;

    /** 栈深度上限：64 帧（够穿过 Forge/Mixin 的包装，又不至于把整个栈搬过来）。 */
    public static final int MAX_DEPTH = 64;

    /** 标签字符上限：超过就截断并补一个 {@code …}（所以最长是 {@code 40 + 1}）。 */
    public static final int LABEL_MAX = 40;

    /** 普通读数行的最小间隔：2 秒（标签变化时会立刻多打一行）。 */
    private static final long LABEL_LOG_INTERVAL_MS = 2_000L;

    /** {@code top30s} 聚合行的周期：30 秒。 */
    private static final long TOP_LOG_INTERVAL_MS = 30_000L;

    /** 截断标记（单个字符，不占两格宽度预算）。 */
    private static final String ELLIPSIS = "…";

    /**
     * 白名单前缀（<b>末尾都带点</b>）。
     *
     * <p>带点是有意的：{@code net.minecraft.} 不会把 {@code net.minecraft_a.B} 当成框架帧
     * （自检第 ⑤ 条就在钉这个边界）。反过来说，<b>不在这张表里的任何包都会被当成"外部内容"</b>
     * ——这正是"通用钩子"的含义：不点名，只看栈。
     */
    private static final String[] FRAMEWORK_PREFIXES = {
            "java.", "javax.", "jdk.", "sun.", "com.sun.", "com.shanhai.", "com.mojang.",
            "net.minecraft.", "net.minecraftforge.", "cpw.", "org.spongepowered.",
            "org.objectweb.asm.", "org.apache.logging.", "it.unimi.dsi.", "com.google."
    };

    // ─────────────────────────────── 状态（采样线程写，别处读） ───────────────────────────────

    /** 采样线程（{@code null} = 没起）。 */
    private static volatile Thread worker;
    /** 是否正在采样。 */
    private static volatile boolean running;
    /** 目标线程 id（-1 = 没找到）。 */
    private static volatile long targetThreadId = -1L;
    /** 当前标签（可能为 {@code ""}）。 */
    private static volatile String label = "";
    /** 当前标签已持续多少毫秒。 */
    private static volatile long heldMs;
    /** 当前站点的内部键（日志/聚合用）。 */
    private static volatile String site = "";

    /** 启动互斥（只允许起一条采样线程）。 */
    private static final Object START_LOCK = new Object();

    /** {@code target_missing} 只打一次。 */
    private static volatile boolean missingLogged;

    private ActivitySampler() {
    }

    // ─────────────────────────────── 对外 API（给标题/接线用） ───────────────────────────────

    /** 当前标签（没有就是 {@code ""}）。<b>随时可读，不阻塞。</b> */
    public static String currentLabel() {
        return label;
    }

    /** 当前标签已持续的毫秒数（没有标签时是 0）。 */
    public static long currentHeldMs() {
        return heldMs;
    }

    /** 采样线程是否在跑。 */
    public static boolean started() {
        return running;
    }

    /**
     * 起采样线程（幂等）。
     *
     * <p>目标线程的取法：① 名字等于 {@code Render thread} 的线程；② 退一步找"栈里在跑
     * {@code net.minecraft.client.Minecraft} 主循环"的那条线程（= {@code Minecraft.getInstance()} 所在线程；
     * JDK 没有 API 能直接取到它，所以只能按它的栈认）；③ 都拿不到 ⇒ 打一行
     * {@code [SHANHAI-ACT] target_missing} 并放弃（绝不硬猜一条别的线程，那会把标签指错人）。
     */
    public static void startIfNeeded() {
        if (running) {
            return;
        }
        synchronized (START_LOCK) {
            if (running) {
                return;
            }
            final Thread target = findTargetThread();
            if (target == null) {
                if (!missingLogged) {
                    missingLogged = true;
                    log("target_missing");
                }
                return;
            }
            targetThreadId = target.getId();
            final Thread thread = new Thread(ActivitySampler::loop, "shanhai-activity-sampler");
            thread.setDaemon(true);
            worker = thread;
            running = true;
            log("start target=" + target.getName() + " tid=" + target.getId()
                    + " period_ms=" + PERIOD_MS + " depth=" + MAX_DEPTH);
            try {
                thread.start();
            } catch (Throwable t) {
                running = false;
                worker = null;
                targetThreadId = -1L;
                log("start_failed err=" + className(t));
            }
        }
    }

    /** 停下（幂等；关游戏/退回本体时调）。{@code reason} 只进日志。 */
    public static void stop(String reason) {
        final Thread thread = worker;
        final boolean wasRunning = running;
        running = false;
        worker = null;
        targetThreadId = -1L;
        label = "";
        site = "";
        heldMs = 0L;
        if (thread != null) {
            try {
                thread.interrupt();
            } catch (Throwable ignored) {
                // 中断不出去也只影响"它下次睡醒时自己退"
            }
        }
        if (wasRunning) {
            log("stop reason=" + (reason == null ? "" : reason));
        }
    }

    // ─────────────────────────────── 采样循环 ───────────────────────────────

    /**
     * 采样循环本体。<b>整个循环包在 try/catch(Throwable) 里</b>：一拍出问题只记一行，下一拍照常。
     */
    private static void loop() {
        ThreadMXBean bean = null;
        try {
            bean = ManagementFactory.getThreadMXBean();
        } catch (Throwable t) {
            log("mxbean_missing err=" + className(t));
        }
        String currentLabel = "";
        String currentSite = "";
        long heldSinceMs = System.currentTimeMillis();
        long lastLabelLogMs = 0L;
        long lastTopLogMs = heldSinceMs;
        long sampleCount = 0L;
        final Map<String, Long> accum = new HashMap<>();
        Class<?> lastErrorClass = null;
        while (running) {
            try {
                if (bean == null) {
                    running = false;
                    break;
                }
                final long now = System.currentTimeMillis();
                final ThreadInfo info = bean.getThreadInfo(targetThreadId, MAX_DEPTH);
                if (info == null) {
                    // 目标线程没了（游戏正在退出）：报一行就走，绝不换成别的线程继续
                    log("target_gone tid=" + targetThreadId);
                    running = false;
                    break;
                }
                final Picked picked = pick(info.getStackTrace(), true);
                ++sampleCount;
                site = picked.site;
                accum.merge(picked.site.isEmpty() ? "-" : picked.site, PERIOD_MS, Long::sum);

                final boolean changed = !picked.label.equals(currentLabel);
                if (changed) {
                    final long afterMs = Math.max(0L, now - heldSinceMs);
                    log("switch from=" + dash(currentLabel) + " to=" + dash(picked.label)
                            + " after_ms=" + afterMs);
                    currentLabel = picked.label;
                    currentSite = picked.site;
                    heldSinceMs = now;
                    label = currentLabel;
                }
                heldMs = Math.max(0L, now - heldSinceMs);

                if (changed || (now - lastLabelLogMs) >= LABEL_LOG_INTERVAL_MS) {
                    lastLabelLogMs = now;
                    log("phase=" + phaseId() + " label=" + dash(currentLabel)
                            + " site=" + dash(currentSite) + " held_ms=" + heldMs
                            + " samples=" + sampleCount);
                }
                if ((now - lastTopLogMs) >= TOP_LOG_INTERVAL_MS) {
                    lastTopLogMs = now;
                    log(topText(accum));
                    accum.clear();
                }
            } catch (Throwable t) {
                final Class<?> errorClass = t.getClass();
                if (errorClass != lastErrorClass) {
                    lastErrorClass = errorClass;
                    log("sample_failed err=" + className(t));
                }
            }
            if (!sleepOneBeat()) {
                break;
            }
        }
    }

    /** 睡一拍；返回 {@code false} = 该退出了（被 interrupt 或已经 stop）。 */
    private static boolean sleepOneBeat() {
        try {
            Thread.sleep(PERIOD_MS);
            return running;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 找目标线程（见 {@link #startIfNeeded()} 的三步）。 */
    private static Thread findTargetThread() {
        Map<Thread, StackTraceElement[]> all = null;
        try {
            all = Thread.getAllStackTraces();
        } catch (Throwable ignored) {
            // 拿不到全量栈就只走"名字"这条（下面会再试一次名字）
        }
        if (all != null) {
            try {
                for (Thread thread : all.keySet()) {
                    if (thread != null && TARGET_THREAD_NAME.equals(thread.getName())) {
                        return thread;
                    }
                }
            } catch (Throwable ignored) {
                // 继续往下试
            }
            try {
                for (Map.Entry<Thread, StackTraceElement[]> entry : all.entrySet()) {
                    final Thread thread = entry.getKey();
                    final StackTraceElement[] stack = entry.getValue();
                    if (thread == null || stack == null) {
                        continue;
                    }
                    for (StackTraceElement frame : stack) {
                        if (frame != null && classNameOf(frame).startsWith("net.minecraft.client.Minecraft")) {
                            return thread;
                        }
                    }
                }
            } catch (Throwable ignored) {
                // 落到 null（调用方打 target_missing）
            }
        }
        return null;
    }

    // ─────────────────────────────── 标签规则 ───────────────────────────────

    /** 一个采样结果：{@code label} 上标题用，{@code site} 只进日志/聚合。 */
    static final class Picked {

        final String label;
        final String site;

        Picked(String label, String site) {
            this.label = label == null ? "" : label;
            this.site = site == null ? "" : site;
        }
    }

    /**
     * 标签规则本体（<b>纯函数</b>：只吃一个栈数组，不碰任何全局状态 —— 所以 {@link #selfCheck()} 能用
     * 人为构造的栈把它整条跑一遍）。
     *
     * @param stack               栈（下标 0 = 栈顶）
     * @param allFrameworkFallback {@code true} = 生产实现；{@code false} = 只给自检负对照用的"坏实现"
     *                            （全框架帧时返回空串，用来自证这套检查真的会 fail）
     */
    static Picked pick(StackTraceElement[] stack, boolean allFrameworkFallback) {
        if (stack == null || stack.length == 0) {
            return new Picked("", "");
        }
        for (StackTraceElement frame : stack) {
            if (frame == null) {
                continue;
            }
            final String owner = classNameOf(frame);
            if (owner.isEmpty() || isFramework(owner)) {
                continue;
            }
            final String method = methodOf(frame);
            return new Picked(truncate(simpleName(owner) + "." + method), owner + "#" + method);
        }
        if (!allFrameworkFallback) {
            // 🔴 负对照专用的坏实现：全框架帧 ⇒ 空标签（selfCheck 必须因此报 fail）
            return new Picked("", "");
        }
        // 整栈都是框架帧 ⇒ 用最深的那一个（最靠近"真正的入口"）
        for (int i = stack.length - 1; i >= 0; i--) {
            final StackTraceElement frame = stack[i];
            if (frame == null) {
                continue;
            }
            final String owner = classNameOf(frame);
            if (owner.isEmpty()) {
                continue;
            }
            return new Picked(truncate(simpleName(owner)), owner);
        }
        return new Picked("", "");
    }

    /** 是否白名单帧（前缀<b>自带末尾的点</b>，所以天然按包边界匹配）。 */
    static boolean isFramework(String className) {
        if (className == null || className.isEmpty()) {
            return false;
        }
        for (String prefix : FRAMEWORK_PREFIXES) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** 超过 {@link #LABEL_MAX} 就截断并补 {@code …}。 */
    static String truncate(String value) {
        if (value == null) {
            return "";
        }
        if (value.length() <= LABEL_MAX) {
            return value;
        }
        return value.substring(0, LABEL_MAX) + ELLIPSIS;
    }

    /** 简单类名（内嵌类保留 {@code Outer$Inner} 形态）。 */
    static String simpleName(String className) {
        final int dot = className.lastIndexOf('.');
        return dot < 0 ? className : className.substring(dot + 1);
    }

    private static String classNameOf(StackTraceElement frame) {
        try {
            final String name = frame.getClassName();
            return name == null ? "" : name;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String methodOf(StackTraceElement frame) {
        try {
            final String name = frame.getMethodName();
            return (name == null || name.isEmpty()) ? "?" : name;
        } catch (Throwable ignored) {
            return "?";
        }
    }

    // ─────────────────────────────── 自检（带负对照） ───────────────────────────────

    /**
     * 自检：用<b>人为构造的栈</b>把标签规则跑一遍，返回 {@code ok=<通过数> fail=<失败数>}。
     *
     * <p>覆盖六条规则（每一条都能单独失败）：
     * <ol>
     *   <li>第三方帧命中（栈顶是框架帧，往下第一个外部帧 ⇒ {@code 类.方法}）；</li>
     *   <li>全框架帧 ⇒ 用<b>最深</b>的白名单帧的简单类名；</li>
     *   <li>空栈 ⇒ 空标签，且不抛；</li>
     *   <li>超长标签 ⇒ 截到 40 字并补 {@code …}；</li>
     *   <li>白名单前缀<b>不带点</b>的边界：{@code net.minecraft_a.B} 不能被当成 {@code net.minecraft.}；</li>
     *   <li>{@code com.shanhai.} 自己的帧不算"外部内容"。</li>
     * </ol>
     * 第 7 条是<b>负对照</b>：把第 2 条换成"全框架帧返回空串"的坏实现时，本检查<b>必须</b>报 fail ——
     * 否则这套自检只是在自说自话。
     */
    public static String selfCheck() {
        final int[] tally = new int[2];   // [0] = ok，[1] = fail

        // ① 第三方帧命中（合成包名，不是任何真实 mod）
        expect(tally, pick(new StackTraceElement[]{
                frame("java.lang.reflect.Method", "invoke"),
                frame("demo.probe.Hit", "methodOne"),
                frame("org.spongepowered.asm.mixin.transformer.MixinProcessor", "applyMixins")
        }, true), "Hit.methodOne", "demo.probe.Hit#methodOne");

        // ② 全框架帧 ⇒ 用最深的白名单帧
        expect(tally, pick(new StackTraceElement[]{
                frame("org.spongepowered.asm.mixin.transformer.MixinProcessor", "applyMixins"),
                frame("jdk.internal.reflect.GeneratedMethodAccessor1", "invoke"),
                frame("org.spongepowered.asm.mixin.transformer.SimpleReloadInstance", "run")
        }, true), "SimpleReloadInstance", "org.spongepowered.asm.mixin.transformer.SimpleReloadInstance");

        // ③ 空栈
        expect(tally, pick(new StackTraceElement[0], true), "", "");

        // ④ 超长截断（"VeryLongSyntheticProbeTypeName.invokeHandler" 共 44 字 ⇒ 截到 40 字 + …）
        expect(tally, pick(new StackTraceElement[]{
                frame("demo.probe.VeryLongSyntheticProbeTypeName", "invokeHandler")
        }, true), "VeryLongSyntheticProbeTypeName.invokeHan…",
                "demo.probe.VeryLongSyntheticProbeTypeName#invokeHandler");

        // ⑤ 白名单前缀不带点的边界：net.minecraft_a 不是 net.minecraft.
        expect(tally, pick(new StackTraceElement[]{
                frame("net.minecraft_a.B", "leak")
        }, true), "B.leak", "net.minecraft_a.B#leak");

        // ⑥ com.shanhai. 自己的帧不算外部
        expect(tally, pick(new StackTraceElement[]{
                frame("com.shanhai.add.backstage.ActivitySampler", "loop"),
                frame("demo.probe.Hit", "methodOne")
        }, true), "Hit.methodOne", "demo.probe.Hit#methodOne");

        // ⑦ 🔴 负对照：坏实现（全框架帧返回空串）时，第 ② 条<b>必须</b>不成立
        final Picked broken = pick(new StackTraceElement[]{
                frame("org.spongepowered.asm.mixin.transformer.MixinProcessor", "applyMixins"),
                frame("jdk.internal.reflect.GeneratedMethodAccessor1", "invoke"),
                frame("org.spongepowered.asm.mixin.transformer.SimpleReloadInstance", "run")
        }, false);
        tally[broken.label.isEmpty() ? 0 : 1]++;

        return "ok=" + tally[0] + " fail=" + tally[1];
    }

    /** 记账一条断言：标签与内部键都必须逐字相等。 */
    private static void expect(int[] tally, Picked actual, String wantLabel, String wantSite) {
        final boolean ok = actual != null
                && wantLabel.equals(actual.label)
                && wantSite.equals(actual.site);
        tally[ok ? 0 : 1]++;
    }

    /** 造一个假栈帧（不加载任何类）。 */
    private static StackTraceElement frame(String declaringClass, String methodName) {
        return new StackTraceElement(declaringClass, methodName, declaringClass, 1);
    }

    // ─────────────────────────────── 日志 / 小工具 ───────────────────────────────

    /** 空前缀上占位（日志里 {@code label=} 后面空着不好读）。 */
    private static String dash(String value) {
        return (value == null || value.isEmpty()) ? "-" : value;
    }

    /** 当前阶段的 id（从状态镜像读；读不到写 {@code ?}）。 */
    private static String phaseId() {
        try {
            final BackstageState state = BackstageProcess.mirror();
            return state == null || state.phase == null ? "?" : state.phase.id();
        } catch (Throwable ignored) {
            return "?";
        }
    }

    /** {@code top30s n=<站点数> 1:<site>=<ms> 2:… 3:…}（只打前 3）。 */
    private static String topText(Map<String, Long> accum) {
        final StringBuilder sb = new StringBuilder(160);
        final List<Map.Entry<String, Long>> sorted = new ArrayList<>(accum.entrySet());
        sorted.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        sb.append("top30s n=").append(sorted.size());
        for (int i = 0; i < 3 && i < sorted.size(); i++) {
            sb.append(' ').append(i + 1).append(':')
                    .append(sorted.get(i).getKey()).append('=')
                    .append(sorted.get(i).getValue());
        }
        return sb.toString();
    }

    private static void log(String message) {
        try {
            LOGGER.info("{} {}", PREFIX, message);
        } catch (Throwable ignored) {
            // 日志系统不可用时丢掉这一行：采样绝不能因为日志而中断
        }
    }

    /** 异常类全限定名（取不到写 {@code null}）。 */
    private static String className(Throwable t) {
        try {
            return t == null ? "null" : t.getClass().getName();
        } catch (Throwable ignored) {
            return "null";
        }
    }
}
