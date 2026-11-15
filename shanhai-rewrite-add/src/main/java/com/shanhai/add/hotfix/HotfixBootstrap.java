package com.shanhai.add.hotfix;

import java.lang.instrument.Instrumentation;
import java.util.concurrent.atomic.AtomicBoolean;

import com.shanhai.ShanhaiMod;

/**
 * 游戏侧的 hotfix 装配点：<b>进世界那一刻预热</b>拿到 {@link Instrumentation} 并长期持有。
 *
 * <h2>整条链路（一次预热）</h2>
 * <pre>
 *   Forge 事件（{@link HotfixEvents}）
 *     → HotfixHolder.touch()                      早一步把 holder 类加载进来（agent 靠类名找它）
 *     → HotfixAgentJar.prepare()                  抽 agent jar + helper 到 %TEMP%\shanhai_hotfix\（纯 ASCII）
 *     → HotfixLauncher.runHelper(...)             ProcessBuilder 起一个新的小 JVM：
 *                                                 java --add-modules jdk.attach -cp &lt;ascii&gt; HotfixHelper &lt;pid&gt; &lt;jar&gt;
 *     → （helper）VirtualMachine.attach + loadAgent → 目标 JVM 里执行 agentmain
 *     → （agent）找到 HotfixHolder → acceptInstrumentation(inst) → 对 JDK 类做空转体检
 *     → （agent）publishState("state=ready …") → 游戏侧读 holder → 打一行 state=…
 * </pre>
 *
 * <h2>三条不许破的纪律</h2>
 * <ol>
 *   <li><b>只做一次，失败不重试。</b>第一次尝试就置闩（{@link #ATTEMPTED}），失败也只留日志
 *       —— 自动重试会让"attach 不通"变成每个存档一次卡顿。要再来一次就用
 *       {@code /shanhai_add hotfix prewarm}（{@link #prewarm} 不经过那道闩）。</li>
 *   <li><b>失败降级不崩。</b>整段包在 {@code try/catch (Throwable)} 里：拿不到 Instrumentation
 *       只意味着"热改敌方代码"这个后续能力不可用，<b>与玩家正在玩的那个世界无关</b>。</li>
 *   <li><b>只记录不反制。</b>降级行固定带 {@code policy=record_only}：不去改游戏行为、
 *       不去重试、不去弹提示。</li>
 * </ol>
 *
 * <h2>为什么要另起线程</h2>
 * 预热里唯一的重活是 attach（实测 ~1.1s，含子 JVM 启动；超时上限 30s）。它原本会在
 * {@code ServerStartedEvent} 里同步跑 —— 也就是<b>服务端主线程</b>上。一旦 attach 被
 * 杀软/防火墙拦成几十秒，玩家会看到"进世界卡住"。所以事件里只做两件极便宜的事
 * （置闩 + 起一个 daemon 线程），真正的 attach 在 {@code shanhai-hotfix-prewarm} 线程上跑。
 * 命令 {@code /shanhai_add hotfix prewarm} 则<b>同步</b>跑（管理员按下回车就是在等结果）。
 *
 * <h2>日志字段（都在同一行，便于 grep）</h2>
 * <pre>
 *   [SHANHAI-HOTFIX] event=prewarm trigger=server_started phase=begin pid=… java=…
 *   [SHANHAI-HOTFIX] event=prewarm phase=prepared agent_jar=… helper_dir=… evidence_dir=… elapsed_ms=…
 *   [SHANHAI-HOTFIX] helper …                                   ← helper 的每一行原样转发
 *   [SHANHAI-HOTFIX] event=prewarm phase=helper_done exit=0 timed_out=false elapsed_ms=…
 *   [SHANHAI-HOTFIX] event=prewarm trigger=… state=ready reason=health_ok elapsed_ms=… holder=…
 *   [SHANHAI-HOTFIX] event=prewarm trigger=… state=degraded reason=… policy=record_only note=只记录不反制
 * </pre>
 */
public final class HotfixBootstrap {

    /** 统一前缀（与 {@link HotfixAgent#PREFIX} / {@link HotfixHelper#PREFIX} 同一个字面量）。 */
    public static final String PREFIX = "[SHANHAI-HOTFIX]";

    /** 首次尝试的闩：置上之后 {@code prewarmOnce} 一律跳过（失败也不重试）。 */
    private static final AtomicBoolean ATTEMPTED = new AtomicBoolean(false);

    /** "有一轮预热正在跑"的闩：防止自动触发与手动命令并发 attach 同一个 JVM（见 {@link #prewarm}）。 */
    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean(false);

    /** agent 交接之后等 holder 被填上的上限（实测 helper 返回时早已填好，这里只是保险）。 */
    private static final long HOLDER_WAIT_MS = 3_000L;

    private HotfixBootstrap() {
    }

    /**
     * <b>只做一次</b>的入口（Forge 事件走这条）。
     *
     * <p>置闩与起线程都在调用线程上完成，所以"到底有没有开始过"在日志里是确定的；
     * 真正的 attach 在 daemon 线程上跑，绝不占服务端主线程。
     */
    public static void prewarmOnce(String trigger) {
        if (!ATTEMPTED.compareAndSet(false, true)) {
            try {
                final boolean installed = HotfixHolder.installed();
                ShanhaiMod.LOGGER.info("{} event=prewarm trigger={} phase=skip state={} reason={}"
                                + " installed={} elapsed_ms=0 policy=once",
                        PREFIX, trigger, installed ? "ready" : "degraded",
                        installed ? "already_installed" : "already_attempted", installed);
            } catch (Throwable ignored) {
                // 日志本身失败也不许影响调用方（事件总线）
            }
            return;
        }
        try {
            final Thread worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    prewarm(trigger);
                }
            }, "shanhai-hotfix-prewarm");
            worker.setDaemon(true);
            worker.start();
            ShanhaiMod.LOGGER.info("{} event=prewarm trigger={} phase=scheduled thread={} daemon=true",
                    PREFIX, trigger, worker.getName());
        } catch (Throwable t) {
            // 连线程都起不来：降级，不许冒泡到事件总线
            degrade(trigger, "spawn_thread_failed", "exception=" + t, 0L);
        }
    }

    /**
     * 真正干活的那一段（同步；命令 {@code /shanhai_add hotfix prewarm} 直接用这个，绕过"只做一次"）。
     *
     * <p><b>同一时刻只允许一轮在跑</b>（{@link #IN_FLIGHT}）：自动触发与手动命令有可能撞上
     * （服务端刚起来时管理员手快敲了命令）。两轮同时 attach 同一个 JVM 的后果<b>没有实测过</b>，
     * 而这里也不需要赌 —— 撞上就直接跳过并说明原因，不排队、不阻塞服务端线程。
     *
     * @param trigger 谁触发的（{@code server_started} / {@code command} / …，只进日志）
     * @return {@code true} = 拿到了 Instrumentation 且 agent 体检 {@code state=ready}
     */
    public static boolean prewarm(String trigger) {
        final long t0 = System.currentTimeMillis();
        if (!IN_FLIGHT.compareAndSet(false, true)) {
            try {
                ShanhaiMod.LOGGER.info("{} event=prewarm trigger={} phase=skip state=degraded"
                                + " reason=prewarm_in_flight installed={} elapsed_ms=0 policy=record_only",
                        PREFIX, trigger, HotfixHolder.installed());
            } catch (Throwable ignored) {
                // 日志失败也不许影响调用方
            }
            return HotfixHolder.installed();
        }
        try {
            return prewarmLocked(trigger, t0);
        } finally {
            IN_FLIGHT.set(false);
        }
    }

    /** {@link #prewarm(String)} 的实体（调用方已持有 {@link #IN_FLIGHT}）。 */
    private static boolean prewarmLocked(String trigger, long t0) {
        try {
            // 🔴 第一件事：把 holder 类加载进来。agent 是"按类名在已加载类里找"的，
            //    没加载就找不到 —— 这一步是整条链路成立的前提，不能挪到 prepare 之后。
            HotfixHolder.touch();

            if (HotfixHolder.installed()) {
                ShanhaiMod.LOGGER.info("{} event=prewarm trigger={} state=ready reason=already_installed"
                                + " elapsed_ms={} holder={}",
                        PREFIX, trigger, System.currentTimeMillis() - t0, HotfixHolder.summary());
                return true;
            }

            final long pid = ProcessHandle.current().pid();
            ShanhaiMod.LOGGER.info("{} event=prewarm trigger={} phase=begin pid={} java={} thread={}"
                            + " tmpdir={}",
                    PREFIX, trigger, pid, System.getProperty("java.version"),
                    Thread.currentThread().getName(), System.getProperty("java.io.tmpdir"));

            final HotfixAgentJar.Prepared prepared;
            try {
                prepared = HotfixAgentJar.prepare();
            } catch (Throwable t) {
                degrade(trigger, "prepare_failed", "exception=" + t, System.currentTimeMillis() - t0);
                return false;
            }
            ShanhaiMod.LOGGER.info("{} event=prewarm trigger={} phase=prepared agent_jar={} helper_dir={}"
                            + " evidence_dir={} elapsed_ms={}",
                    PREFIX, trigger, prepared.agentJar, prepared.helperDir, prepared.evidenceDir,
                    System.currentTimeMillis() - t0);

            final String javaExe = HotfixLauncher.javaExecutable();
            if (javaExe == null) {
                degrade(trigger, "java_executable_not_found", "hint=java.home 与 ProcessHandle 都拿不到",
                        System.currentTimeMillis() - t0);
                return false;
            }

            final HotfixLauncher.Result result = HotfixLauncher.runHelper(
                    javaExe, pid, prepared, HotfixLauncher.DEFAULT_TIMEOUT_MS);

            // helper 的每一行原样转发：attach 失败的真正原因（AgentLoadException 等）就在这些行里
            for (int i = 0; i < result.lines.length; i++) {
                ShanhaiMod.LOGGER.info("{} helper {}", PREFIX, result.lines[i]);
            }
            ShanhaiMod.LOGGER.info("{} event=prewarm trigger={} phase=helper_done launched={} exit={}"
                            + " timed_out={} success={} elapsed_ms={} cmd={}",
                    PREFIX, trigger, result.launched, result.exitCode, result.timedOut, result.success(),
                    result.elapsedMillis, result.command);

            if (!result.success()) {
                // tail 也要带上：子 JVM 如果根本没跑到 helper（例如 "Unknown module: jdk.attach"），
                // 就只有最后这行 stderr 能说明问题，而那时是没有 RESULT= 行的。
                final String tail = result.lines.length == 0
                        ? "" : result.lines[result.lines.length - 1];
                degrade(trigger, "helper_" + result.reason(),
                        "detail=<" + (result.lastResultLine() == null ? "" : result.lastResultLine()) + ">"
                                + " tail=<" + tail + ">",
                        System.currentTimeMillis() - t0);
                return false;
            }

            // helper 的 loadAgent 是在目标 JVM 里【同步】执行 agentmain 的，所以这里绝大多数情况下
            // 一进来就已经填好了；这点等待只防"agent 还在收尾"的极小窗口。
            final boolean installed = awaitHolder(HOLDER_WAIT_MS);
            final String stateLine = HotfixHolder.stateLine();
            if (!installed) {
                degrade(trigger, "holder_not_populated",
                        "holder_state=<" + stateLine + "> hint=看 agent-<pid>.log 里 holder found=",
                        System.currentTimeMillis() - t0);
                return false;
            }
            final boolean ready = stateLine.contains("state=ready");
            if (ready) {
                ShanhaiMod.LOGGER.info("{} event=prewarm trigger={} state=ready reason=health_ok elapsed_ms={}"
                                + " helper_exit={} holder={} holder_state=<{}>",
                        PREFIX, trigger, System.currentTimeMillis() - t0, result.exitCode,
                        HotfixHolder.summary(), stateLine);
            } else {
                // 交接成了但体检没过：Instrumentation 已经在手里（后续仍可尝试），只是这条管道不可靠
                degrade(trigger, "health_not_ready",
                        "holder_state=<" + stateLine + "> holder=" + HotfixHolder.summary(),
                        System.currentTimeMillis() - t0);
            }
            return ready;
        } catch (Throwable t) {
            degrade(trigger, "prewarm_exception_" + t.getClass().getSimpleName(), "exception=" + t,
                    System.currentTimeMillis() - t0);
            return false;
        }
    }

    /**
     * 跑自检（{@code /shanhai_add hotfix selftest}）。
     *
     * @return {@code true} = 闭环全过
     */
    public static boolean selftest() {
        final Instrumentation inst = HotfixHolder.instrumentation();
        if (inst == null) {
            ShanhaiMod.LOGGER.warn("{} event=selftest state=degraded reason=no_instrumentation"
                            + " hint=先 /shanhai_add hotfix status 看预热结果，必要时 /shanhai_add hotfix prewarm"
                            + " policy=record_only",
                    PREFIX);
        }
        // 每一行都经同一个 sink 出去：前缀 + 原文，与离线自检的读数逐字可比
        return HotfixSelfTest.run(inst, line -> ShanhaiMod.LOGGER.info("{} {}", PREFIX, line));
    }

    /** 一行判读用摘要（命令 {@code status} 与预热日志共用）。 */
    public static String statusLine() {
        final Instrumentation inst = HotfixHolder.instrumentation();
        boolean retransform = false;
        boolean redefine = false;
        int loaded = -1;
        if (inst != null) {
            try {
                retransform = inst.isRetransformClassesSupported();
            } catch (Throwable ignored) {
                // 保持 false
            }
            try {
                redefine = inst.isRedefineClassesSupported();
            } catch (Throwable ignored) {
                // 保持 false
            }
            try {
                loaded = inst.getAllLoadedClasses().length;
            } catch (Throwable ignored) {
                // 保持 -1
            }
        }
        return "installed=" + (inst != null)
                + " retransform_supported=" + retransform
                + " redefine_supported=" + redefine
                + " loaded_classes=" + loaded
                + " attempt_done=" + ATTEMPTED.get()
                + " in_flight=" + IN_FLIGHT.get()
                + " java=" + System.getProperty("java.version")
                + " work_dir=" + safeWorkDir()
                + " " + HotfixHolder.summary();
    }

    /** 本轮预热是否已经尝试过（失败也算）。 */
    public static boolean attempted() {
        return ATTEMPTED.get();
    }

    // ------------------------------------------------------------------ 内部

    private static boolean awaitHolder(long timeoutMs) {
        final long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (HotfixHolder.installed()) {
                return true;
            }
            try {
                Thread.sleep(25L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return HotfixHolder.installed();
            }
        }
        return HotfixHolder.installed();
    }

    /**
     * 降级：一行 WARN，字段固定为 {@code state=degraded reason=… policy=record_only note=只记录不反制}。
     *
     * <p>{@code policy=record_only} 与 {@code note=} 是<b>承诺</b>，不是装饰：读到这一行的人要知道
     * "游戏没有因此改变任何行为、也不会自动重试"。
     */
    private static void degrade(String trigger, String reason, String detail, long elapsedMs) {
        try {
            ShanhaiMod.LOGGER.warn("{} event=prewarm trigger={} state=degraded reason={} {} elapsed_ms={}"
                            + " policy=record_only note=只记录不反制",
                    PREFIX, trigger, reason, detail, elapsedMs);
        } catch (Throwable ignored) {
            // 日志都打不出去时也别抛：预热失败本身已经没有任何补救动作了
        }
    }

    private static String safeWorkDir() {
        try {
            return HotfixPaths.workDir().toString();
        } catch (Throwable t) {
            return "<unavailable:" + t.getClass().getSimpleName() + ">";
        }
    }
}
