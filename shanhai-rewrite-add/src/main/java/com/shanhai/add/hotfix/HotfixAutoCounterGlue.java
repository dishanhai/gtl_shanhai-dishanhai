package com.shanhai.add.hotfix;

import com.shanhai.ShanhaiMod;
import com.shanhai.add.config.ShanhaiAddConfig;

import net.minecraftforge.fml.loading.FMLPaths;

/**
 * 主动热改的<b>装配胶水</b>：本模块里唯一认识 MC／Forge／log4j 的那个人。
 *
 * <h2>为什么要把"引擎"和"胶水"拆开</h2>
 * {@link HotfixAutoCounter} 与 {@link HotfixTriageTap} 都是<b>纯 JDK（＋ASM）</b>的，
 * 目的是让它们能被<b>脱离游戏</b>的离线自检直接跑（{@code temp\hotfix\offline-autocounter\}）。
 * 一旦引擎里出现 {@code ShanhaiMod.LOGGER} 或 {@code FMLPaths}，那条离线路就断了
 * （{@code NoClassDefFoundError}，而且是在最不该崩的地方崩）。
 * ⇒ 需要 MC 的两件事（日志出口、{@code mods\} 路径）只在<b>这个文件</b>里出现，用 {@link HotfixAutoCounter#bind} 注入进去。
 *
 * <h2>它做三件事</h2>
 * <ol>
 *   <li>{@link HotfixAutoCounter#bind}：把日志出口与 {@code mods\} 根交给引擎；</li>
 *   <li>{@link HotfixTriageTap#install}：订阅本体分诊日志（幂等，只成功装一次）；</li>
 *   <li>打一行可判读的 {@code tap } 摘要（含自证探针结果）。</li>
 * </ol>
 *
 * <h2>失败姿势</h2>
 * 全部 {@code try/catch(Throwable)}：装不上 tap 只意味着"主动热改暂时收不到触发"，
 * 与游戏、与本体的分诊<b>都没有关系</b>。
 */
public final class HotfixAutoCounterGlue {

    /** 日志出口：一律经本体的 logger（与热更新其它日志同一个前缀，便于一起 grep）。 */
    public static final HotfixAutoCounter.Log LOG = new HotfixAutoCounter.Log() {
        @Override
        public void line(String text) {
            try {
                ShanhaiMod.LOGGER.info("{} {}", HotfixAutoCounter.PREFIX, text);
            } catch (Throwable ignored) {
                // 日志都打不出去时什么也别做
            }
        }

        /**
         * 失败/跳过路径 → <b>WARN</b>。
         *
         * <p>为什么单独一条：读回取证（{@code patch_readback}）的失败路径是"这一次没读到"，
         * 不是"热改出事了"，更不是玩家行为 —— 用 INFO 会让人以为它在正常干活，
         * 用 ERROR 会把一次可自愈的取不到放大成告警。WARN 是这条链唯一合适的水位。
         */
        @Override
        public void warn(String text) {
            try {
                ShanhaiMod.LOGGER.warn("{} {}", HotfixAutoCounter.PREFIX, text);
            } catch (Throwable ignored) {
                // 同上
            }
        }

        /**
         * 🆕 2026-11-20：ERROR 出口（只给 {@code patch_lost} 用）。
         *
         * <p>为什么这条要 WARN 之上：{@code patch_lost} = "我们写下去的字节被人换掉了，
         * 重打一次也救不回来" ⇒ 那一刻这个目标已经<b>不再受我们保护</b>。这不是"这次没读到"。
         */
        @Override
        public void error(String text) {
            try {
                ShanhaiMod.LOGGER.error("{} {}", HotfixAutoCounter.PREFIX, text);
            } catch (Throwable ignored) {
                // 同上
            }
        }
    };

    /** 分诊订阅的落地动作：原样交给引擎（入队，立刻返回）。 */
    private static final HotfixTriageTap.Sink SINK = new HotfixTriageTap.Sink() {
        @Override
        public void onVerdict(HotfixTriageVerdict verdict, HotfixTriageTap.Strike strike) {
            HotfixAutoCounter.onVerdict(verdict, strike);
        }
    };

    /** 上一次打过的 tap 状态（只在状态变化时再打一行，免得每个世界都刷屏）。 */
    private static volatile String lastTapState = "";

    /**
     * 🆕 2026-11-22：{@code config/shanhai_add-common.toml [hotfix] auto} 的值是否已经应用过。
     *
     * <p>{@link #bind()} 每次进世界都会跑；只应用一次 ⇒ 管理员用 {@code /shanhai_add hotfix auto off}
     * 关掉的开关不会被"下一次进世界"悄悄打开（关掉的同时文件也改了，所以两边本来就是一致的）。
     */
    private static final java.util.concurrent.atomic.AtomicBoolean autoConfigured =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * 🆕 2026-11-08：<b>热改播报的出口</b>（把"改了谁／为什么"播到玩家聊天栏）。
     *
     * <p>它<b>不新建任何通道</b>：内部调用本体 {@code ShanhaiGuardEvents#chatRawBodies} ——
     * 就是守卫那条现成的聊天栏（前缀 {@code [创始现实修改模块] } ＋ 同一个闸门 ＋ 只发持有者本人）。
     * 装配在这里（而不是引擎里）是因为引擎是纯 JDK 的，见 {@link HotfixAutoCounter.PatchChat}。
     */
    private static final HotfixAutoCounter.PatchChat CHAT = new HotfixPatchChat();

    private HotfixAutoCounterGlue() {
        // 纯静态
    }

    /** 只接线（不进日志、不装 tap）—— 停服／退出世界时 undoall 之前调，保证日志出口是通的。 */
    public static void bind() {
        HotfixAutoCounter.bind(LOG, modsDir());
        // 🆕 2026-11-20：把「我们是谁」交给引擎（准入判据的三条排除要用，见 HotfixPatchWhitelist）。
        //   🔴 这里传的是【对象与它自己的 code source】，不写任何路径字面量 ——
        //   所以"我们自己的 jar"是按"与 ShanhaiMod 同一个 code source"认出来的，换个安装位置照样成立。
        HotfixAutoCounter.bindSelf(selfCodeSource(), selfLoader(), mcLoader());
        // 🆕 2026-11-08：热改播报的出口（没有玩家上下文时引擎什么都不会发；这一句只是把出口装上）
        HotfixAutoCounter.bindChatSink(CHAT);
        // 🆕 2026-11-21（根因②）：基线快照落盘目录。
        //   落盘只是为了让现场能离线复核"我们当初抓到的原始字节到底是哪一份"；
        //   写不进去只意味着没有这份证据，绝不影响任何动作（HotfixBaseline 内部全 try 掉）。
        HotfixBaseline.bindDumpDir(baselineDir());
        // 🆕 2026-11-21（②配置）：整链抹除的默认值从 Forge 配置读一次（config/shanhai_add-common.toml
        //   [hotfix] chain_wipe，默认 true）。⚠️ 只应用一次：之后管理员用
        //   /shanhai_add hotfix chainwipe off 关掉，再进一次世界也不会被配置打回去。
        try {
            HotfixChainWipe.applyConfiguredDefault(
                    ShanhaiAddConfig.chainWipeOr(HotfixChainWipe.DEFAULT_ENABLED));
        } catch (Throwable ignored) {
            // 配置读不到 ⇒ 保持引擎自己的默认值（开）；绝不影响任何游戏行为
        }
        // 🆕 2026-11-22（用户报"热修改的配置重开就不见了"）：主动热改的启动值同样从配置读一次
        //   （config/shanhai_add-common.toml [hotfix] auto）。同样是"只应用一次"：
        //   命令 `/shanhai_add hotfix auto off` 现在会把文件也改掉，所以两边不会再分叉。
        //   ⚠️ 走 setAuto 而不是直接写引擎的 static：引擎类（HotfixAutoCounter）本轮一个字节都不改，
        //      setAuto 是它公开的唯一写入口；值相同就不调，免得每个世界多一次 undoAll 噪音。
        try {
            if (autoConfigured.compareAndSet(false, true)) {
                final boolean want = ShanhaiAddConfig.hotfixAutoOr(HotfixAutoCounter.DEFAULT_AUTO);
                final boolean before = HotfixAutoCounter.auto();
                if (want != before) {
                    HotfixAutoCounter.setAuto(want);
                }
                ShanhaiMod.LOGGER.info("{} auto_configured key={} value={} was={} default={}"
                                + " note=配置文件的值（只应用一次；之后以命令为准）",
                        HotfixAutoCounter.PREFIX, ShanhaiAddConfig.KEY_HOTFIX_AUTO, want, before,
                        HotfixAutoCounter.DEFAULT_AUTO);
            }
        } catch (Throwable ignored) {
            // 配置读不到 ⇒ 保持引擎自己的默认值（开）
        }
    }

    /**
     * 基线落盘目录：{@code config/shanhai/hotfix-baseline}。
     *
     * <p>⚠️ 这里用 {@code FMLPaths.CONFIGDIR}（游戏自己的口径）而不是硬编码路径 —— 换个安装位置照样成立。
     * 拿不到 ⇒ 空串（= 不落盘）。
     */
    public static String baselineDir() {
        try {
            return HotfixPaths.join(FMLPaths.CONFIGDIR.get().toAbsolutePath().toString(),
                    "shanhai", "hotfix-baseline");
        } catch (Throwable t) {
            return "";
        }
    }

    /** 我们自己（{@code ShanhaiMod}）的 code source 原样串；拿不到 ⇒ {@code null}。 */
    public static String selfCodeSource() {
        return HotfixForeignScan.codeSourceOf(ShanhaiMod.class);
    }

    // ═══════════════════════════════ 🆕 关键类监控（judge_watch）═══════════════════════════════

    /**
     * 关键类监控的基线文件：{@code config/shanhai/judge-baseline.json}。
     *
     * <p>用 {@code FMLPaths.CONFIGDIR}（与 {@link #baselineDir()} 同一个口径，不写路径字面量）；
     * 拿不到 ⇒ {@code null}（{@link HotfixJudgeWatch} 会打一行 skip，<b>不猜、不写盘</b>）。
     */
    public static java.io.File judgeBaselineFile() {
        try {
            return new java.io.File(HotfixPaths.join(FMLPaths.CONFIGDIR.get().toAbsolutePath().toString(),
                    "shanhai", "judge-baseline.json"));
        } catch (Throwable t) {
            return null;
        }
    }

    /** {@code judge_watch} 的日志出口（与热改其它行同一个 logger、同一个前缀）。 */
    private static final HotfixJudgeWatch.LogSink JUDGE_LOG = new HotfixJudgeWatch.LogSink() {
        @Override
        public void line(String text) {
            LOG.line(text);
        }
    };

    /**
     * {@code verdict=foreign} ⇒ 当成一次<b>违规样本</b>喂进<b>既有</b>溯源链。
     *
     * <p>🔴 这里只调 {@link HotfixTriageTap#submitViolation}（公开入口），<b>不在这一侧新建任何
     * 选靶／溯源逻辑</b>：后续完全交给 {@code HotfixAutoCounter} 的门槛链与
     * {@code HotfixChainTriage} 的"往上溯源"分支（用户要求"废掉对手的代码"就走那条路）。
     *
     * <p>{@code where=judge}：不是血／死／摘人那四类，也不是动量那两类 —— 它是一条
     * "关键原版方法的 Code 与基线不符"的证据。规范化（{@code HotfixTrigger#canonical}）对它原样透传。
     */
    private static final HotfixJudgeWatch.StrikeSink JUDGE_STRIKE = new HotfixJudgeWatch.StrikeSink() {
        @Override
        public boolean submit(String source, String where, java.util.List<String> frames, long atMillis) {
            try {
                // 🆕 2026-11-21（④）：**来源透传** —— 只多这一个参数，日志里才会出现
                //   strike_source=judge_watch（否则第三个生产者和违规现场分不开）。
                return HotfixTriageTap.submitViolation(where, null, frames, atMillis, source);
            } catch (Throwable t) {
                return false;                  // 通道炸了也不许影响启动
            }
        }
    };

    /**
     * "这个类能不能采基线"的闸门：<b>我们自己写下去的字节一律不采</b>。
     *
     * <p>为什么这条判断在胶水这一侧（而不在 {@link HotfixJudgeWatch} 里）：那边的类
     * {@code HotfixBaseline} 会把 {@code HotfixAutoCounter} 整串拉进依赖面，而关键类监控
     * 刻意做到"一个项目类都不引"（离线装置只编它一个文件就能跑）。所以把 {@code HotfixBaseline}
     * 留在胶水里，用一个 {@code Guard} 交给它。
     *
     * <p>判据用 {@code stampedSha}（"我们上次写下去的 sha"）而不是"现在是不是我们的字节"——
     * 后者<b>读不到</b>（P1：retransform 永远给 initial class bytes），见 {@code HotfixPatchReadback}。
     */
    private static final HotfixJudgeWatch.Guard JUDGE_GUARD = new HotfixJudgeWatch.Guard() {
        @Override
        public boolean mayCapture(String internalName) {
            try {
                return HotfixBaseline.stampedSha(internalName) == null;
            } catch (Throwable t) {
                return false;                  // 判不了就<b>不采</b>（宁可少采，也不许把自己补丁撤掉）
            }
        }
    };

    /**
     * 🆕 2026-11-21（P2）：每会话只起<b>一条</b>等待线程（{@code ensure} 每进一次世界都会调）。
     */
    private static final java.util.concurrent.atomic.AtomicBoolean JUDGE_STARTED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * 跑一次关键类监控（每个 JVM 会话只真跑一次，见 {@link HotfixJudgeWatch#runSession}）。
     *
     * <p>为什么放在 {@code tap} 装好<b>之后</b>：{@code verdict=foreign} 那一条要立刻投递进
     * {@code submitViolation}；tap 没装上时投递只会在内存里记住栈、不产生动作。
     *
     * <h2>🆕 2026-11-21（P2）：为什么不在这里直接跑，而是起一条等待线程</h2>
     * 现场（{@code GTL山海9.10test2} 2026-11-21 08:41:28）：这里跑的时候 prewarm 的 attach
     * <b>还没完成</b> ⇒ {@code HotfixHolder.instrumentation()} 是 {@code null} ⇒ 只打了一行
     * {@code judge_watch_skip reason=no_instrumentation}，而同一局热改后来<b>确实写成了字节</b>
     * ⇒ Instrumentation 是存在的，只是"来早了一拍"。
     *
     * <p>⇒ 改成：在一条 daemon 线程上<b>等最多 {@link HotfixJudgeWatch#WAIT_MAX_TICKS} 拍</b>
     * （1 拍 = 50 ms；现场 attach 只用了 524 ms ≈ 11 拍），就绪之后再真跑。
     * <b>绝不在服务端主线程上睡</b>（那会把进世界卡住最多 10 s）。
     */
    private static void runJudgeWatch() {
        if (!JUDGE_STARTED.compareAndSet(false, true)) {
            return;
        }
        try {
            final Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        HotfixJudgeWatch.runSessionWhenReady(
                                new HotfixJudgeWatch.InstSource() {
                                    @Override
                                    public java.lang.instrument.Instrumentation get() {
                                        return HotfixHolder.instrumentation();
                                    }
                                },
                                HotfixJudgeWatch.WAIT_MAX_TICKS,
                                HotfixJudgeWatch.WAIT_TICK_MILLIS,
                                judgeBaselineFile(), JUDGE_LOG, JUDGE_STRIKE, JUDGE_GUARD);
                    } catch (Throwable t2) {
                        // 判据自己坏了绝不许影响启动／游戏
                        try {
                            LOG.line("judge_watch_skip class=* reason=trigger_"
                                    + HotfixPatchReadback.tok(t2.getClass().getSimpleName()));
                        } catch (Throwable ignored) {
                            // 连日志都打不出去：什么也不做
                        }
                    }
                }
            }, "shanhai-hotfix-judge");
            t.setDaemon(true);
            t.start();
        } catch (Throwable t) {
            // 起线程都失败 ⇒ 退回同步跑一次（宁可阻塞一下，也别让判据完全不工作）
            try {
                HotfixJudgeWatch.runSession(HotfixHolder.instrumentation(), judgeBaselineFile(),
                        JUDGE_LOG, JUDGE_STRIKE, JUDGE_GUARD);
            } catch (Throwable t3) {
                try {
                    LOG.line("judge_watch_skip class=* reason=trigger_"
                            + HotfixPatchReadback.tok(t3.getClass().getSimpleName()));
                } catch (Throwable ignored) {
                    // 同上
                }
            }
        }
    }

    /** 我们自己的类加载器。 */
    public static ClassLoader selfLoader() {
        try {
            return ShanhaiMod.class.getClassLoader();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 一个<b>一定属于原版</b>的加载器实例（只给"code source 拿不到时的二级信号 (b)"用）。
     *
     * <p>用 {@code net.minecraft.world.entity.Entity} 那个类 —— 它在任何 Forge 环境里都必然存在、
     * 而且必然由 MC 那个模块加载器定义。拿不到 ⇒ {@code null}（那条二级信号就跳过）。
     */
    public static ClassLoader mcLoader() {
        try {
            return Class.forName("net.minecraft.world.entity.Entity", false,
                    HotfixAutoCounterGlue.class.getClassLoader()).getClassLoader();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 接线 ＋ 订阅分诊（进世界时调；可重复调）。
     *
     * @param trigger 谁触发的（只进日志）
     * @return {@code true} = 分诊订阅已经装着（且自证探针走通）
     */
    public static boolean ensure(String trigger) {
        bind();
        // 🆕 2026-11-20：启动时一行可判读的自证（同一 JVM 只打一次，不是每 tick）：
        //   [SHANHAI-HOTFIX] auto_default=on auto=true default=true decoupled_from=attack_side …
        HotfixAutoCounter.logAutoDefaultOnce();
        // 🆕 2026-11-20：启动期【只读】自检 —— 数一数"非白名单 ＋ CodeSource 为空"的已加载类有几个。
        //   这一行直接回答「场上有没有 agent 注入的东西」（敌人的 agent / Unsafe / defineClass 都会落在这里）。
        HotfixAutoCounter.logForeignScanOnce();
        final boolean installed;
        try {
            installed = HotfixTriageTap.install(SINK);
        } catch (Throwable t) {
            LOG.line("tap trigger=" + HotfixAutoCounter.token(trigger)
                    + " installed=false reason=" + HotfixAutoCounter.token("exception_" + t.getClass().getSimpleName()));
            return false;
        }
        final String state = "installed=" + installed + " " + HotfixTriageTap.statusLine();
        if (!state.equals(lastTapState)) {
            lastTapState = state;
            LOG.line("tap trigger=" + HotfixAutoCounter.token(trigger) + " " + state
                    + " auto=" + HotfixAutoCounter.auto());
        }
        // 🆕 2026-11-21（用户点名）：<b>关键类监控</b> —— 启动期基线比对 ＋ verdict=foreign 直接喂进
        //   上面刚装好的 strike 通道。放在 tap 之后、且自己再 try/catch 一层（判据坏了不许影响启动）。
        //   每个 JVM 会话只真跑一次（幂等闩在 HotfixJudgeWatch 里）。
        runJudgeWatch();
        return installed;
    }

    /**
     * {@code mods\} 的绝对路径。
     *
     * @return 路径；拿不到 ⇒ 空串（此时白名单会退化成"路径里必须有一级 mods"这条<b>更严</b>的判据）
     */
    public static String modsDir() {
        try {
            return FMLPaths.MODSDIR.get().toAbsolutePath().toString();
        } catch (Throwable t) {
            return "";
        }
    }
}
