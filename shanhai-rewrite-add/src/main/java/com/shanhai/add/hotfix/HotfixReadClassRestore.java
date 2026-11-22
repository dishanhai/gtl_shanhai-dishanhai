package com.shanhai.add.hotfix;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 🔴 <b>{@code read_side_faked} ⇒ 直接做一次"关键类还原"</b>（2026-11-21 P2）。
 *
 * <h2>现场那条缺口（逐字读数）</h2>
 * <pre>
 *   [SHANHAI-GUARD] read_side_mismatch hp=-Infinity field=20.0 lost=Infinity hits=1 / 324 / 627 …（61 条）
 *   [SHANHAI-GUARD] death_gate_probe read=-Infinity field=20.000 dead=true note=read_side_faked
 *   同一局：帧分类 verdict=foreign=0、chain_wipe_handoff=0、keyclass_restore=0
 *   ⇒ 我们【从来没有看见过改 getHealth 的那只手】⇒ 用户原话第 ④ 步一次都没触发
 * </pre>
 * <p>判据本身一行都不用新写：{@code getHealth()} 读成死的（{@code <=0 / NaN}）而
 * <b>血量字段还是活的</b>（{@code >0}）⇒ 读那一步被人改了。这一条不需要"看见对手"，
 * 所以它能在整条链看不见对手时<b>直接动手</b>：
 * <pre>
 *   对【声明 getHealth 的那个类】调 HotfixKeyClassRestore.restoreForTriage(inst, 该类, null)
 *   ⇒ method=null ⇒ 扫该类全部真实方法，把挂在上面的【外部】handler 方法体清空
 *     （我们自己的逐字节保留；手术实现不在本类，本类只调它的公开入口）
 * </pre>
 *
 * <h2>限流：同一类 {@value #MIN_INTERVAL_TICKS} tick 只做一次</h2>
 * <p>为什么必须限流：{@code getHealth()} 是每 tick 每实体都在读的热接口，
 * 真按"每次命中都还原"写，就是每秒一次整类重建 ⇒ 玩家那边直接"时间暂停"。
 * 一次还原之后外部 handler 的方法体已经是空的（除非对手重新注入），把间隔拉到
 * {@value #MIN_INTERVAL_TICKS} tick（60 s）是足够便宜的复查节奏。
 *
 * <h2>🔴 线程边界（这条是硬约束，别改成"就地跑"）</h2>
 * 触发点在<b>服务端主线程</b>（{@code Player#aiStep} 里的 {@code getHealth()}）上，
 * 而 {@code restoreForTriage} 里有<b>一次整类 retransformClasses</b>
 * ⇒ <b>绝不许在触发线程上跑</b>。这里只做"判 + 限流 + 投递"，真正的手术在
 * 一条 daemon 工作线程上（队列长度 1：满了就丢掉这一条，限流已经保证不会堆）。
 *
 * <h2>日志（逐字；三态 ＋ 字节来源）</h2>
 * <pre>
 *   成功：keyclass_restore_by_readclass class=net.minecraft.world.entity.LivingEntity trigger=read_side_faked \
 *           read=-Infinity field=20.000 stripped=1 ours_kept=2 retransforms=1 bytes=baseline size=31902
 *   无事：keyclass_restore_by_readclass class=… trigger=read_side_faked read=… field=… \
 *           stripped=0 ours_kept=2 retransforms=0 reason=no_external_handler bytes=baseline size=31902
 *         🔴 stripped=0 只在 {@code size>0} 时才是<b>结论</b>：字节拿到了、这上面确实没有外部 handler
 *           ⇒ <b>对手不是 mixin 形态</b>（它是 Agent／Unsafe.defineClass 之类，运行期改的字节）。
 *           这一条必须如实打出来，绝不许吞掉。
 *   没读到：keyclass_restore_by_readclass class=… … stripped=0 ours_kept=0 retransforms=0 \
 *           reason=no_class_bytes bytes=none
 *         🔴 三级字节回退（基线 → 磁盘/jar → classloader 资源）全空 ⇒ <b>没拿到字节</b>。
 *           这一行<b>不再</b>伪装成 {@code no_external_handler}：那种 0 是假结论
 *           （现场：{@code LivingEntity} 上明明有 9 个 {@code shanhai$} 方法，却打成
 *           {@code ours_kept=0 reason=no_external_handler}）。
 *   拒绝：keyclass_restore_by_readclass_refused reason=no_instrumentation   （整个 JVM <b>只报一次</b>）
 * </pre>
 *
 * <h2>依赖面</h2>
 * 本类只依赖 {@code java.*} ＋ {@link HotfixKeyClassRestore}（纯 JDK）——
 * 日志出口走反射 {@code HotfixAutoCounter.chainLog}（与 {@code HotfixKeyClassRestore} 同一套兜底），
 * 所以离线自检（{@code temp\hotfix\p2-readclasscooldown\}）跑的是<b>同一份</b>字节码。
 */
public final class HotfixReadClassRestore {

    /** 日志行首（成功/无事两态）。 */
    public static final String LINE = "keyclass_restore_by_readclass";

    /** 日志行首（拒绝态）。 */
    public static final String LINE_REFUSED = "keyclass_restore_by_readclass_refused";

    /** {@code trigger=} 的取值（用户点名的那个判据名）。 */
    public static final String TRIGGER = "read_side_faked";

    /** 同一类两次还原之间的最小间隔（tick）。 */
    public static final long MIN_INTERVAL_TICKS = 1200L;

    /** 日志字段里的 {@code reason=no_external_handler}（与 {@code HotfixKeyClassRestore} 同一个词）。 */
    public static final String REASON_NO_EXTERNAL = HotfixKeyClassRestore.REASON_NO_EXTERNAL;

    /** 日志字段里的 {@code reason=no_instrumentation}。 */
    public static final String REASON_NO_INSTRUMENTATION = HotfixKeyClassRestore.REASON_NO_INSTRUMENTATION;

    /** 日志出口（游戏里由装配胶水接上；离线自检接收集器）。 */
    public interface LineSink {
        void line(String text);
    }

    private static volatile LineSink sink;

    /** 反射缓存：{@code HotfixAutoCounter.chainLog(String)}（找不到 ⇒ {@code null}，永久）。 */
    private static volatile Method logMethod;

    private static volatile boolean logLookedUp;

    /** 类名 ⇒ 上一次（已接受的）还原的 tick。 */
    private static final Map<String, Long> LAST_TICK = new HashMap<String, Long>();

    /** {@code no_instrumentation} 只报一次（用户点名的口径）。 */
    private static final AtomicBoolean REFUSED_LOGGED = new AtomicBoolean(false);

    /** 投递队列（长度 1：手术没跑完时再来一条就丢掉 —— 限流已经保证不会真的堆）。 */
    private static final LinkedBlockingQueue<Runnable> QUEUE = new LinkedBlockingQueue<Runnable>(1);

    /** 队列里还有几条没跑完（{@link #awaitIdle} 用）。 */
    private static final AtomicInteger PENDING = new AtomicInteger(0);

    /** 工作线程起过没有。 */
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);

    /** 已接受的触发次数（读数；只进离线自检/命令）。 */
    private static final AtomicInteger ACCEPTED = new AtomicInteger(0);

    /** 真的做过手术的次数（读数）。 */
    private static final AtomicInteger RESTORED = new AtomicInteger(0);

    /** 被队列丢掉的次数（读数：它可以 >0，但必须能被解释）。 */
    private static final AtomicInteger DROPPED = new AtomicInteger(0);

    private HotfixReadClassRestore() {
        // 纯静态
    }

    /**
     * 接上日志出口（幂等；传 {@code null} = 断开，退回"反射 {@code HotfixAutoCounter.chainLog} → stdout"）。
     *
     * <p>与 {@link HotfixKeyClassRestore#bind} 同一个理由：本类不该为了打一行日志把 5000 行的
     * 热改引擎拖进编译依赖。
     */
    public static void bind(LineSink newSink) {
        sink = newSink;
    }

    /**
     * 触发点（守卫侧唯一的公开入口）。
     *
     * <p>⚠️ 在<b>触发线程</b>上调即可（通常就是服务端主线程）：这里只判 + 限流 + 投递，
     * 一次 {@code retransformClasses} 都不会在调用线程上发生。
     *
     * @param inst   热改引擎的 {@link Instrumentation}（{@code null} ⇒ 打一次 {@code _refused} 就完事）
     * @param target 声明 {@code getHealth()} 的那个类（= {@code net.minecraft.world.entity.LivingEntity}）
     * @param tick   当前游戏 tick（同一类的限流按它算）
     * @param read   这一次 {@code getHealth()} 的读数（{@code <=0} 才该调进来）
     * @param field  同一刻的血量字段真值（{@code >0} 才该调进来）
     * @return {@code true} = 这一拍真的接受了（会做一次还原）；{@code false} = 拒绝/限流/参数不成立
     */
    public static boolean onReadSideFaked(Instrumentation inst, Class<?> target, long tick, float read, float field) {
        try {
            if (target == null) {
                return false;                       // 没有目标类 ⇒ 什么都做不了（也不该在这里报错）
            }
            if (read > 0.0F || !(field > 0.0F)) {
                // 🔴 判据的兜底：读数不是死的 ／ 字段也是死的（= 真死）⇒ 这不是 read_side_faked
                return false;
            }
            if (inst == null) {
                if (REFUSED_LOGGED.compareAndSet(false, true)) {
                    log(LINE_REFUSED + " reason=" + REASON_NO_INSTRUMENTATION);
                }
                return false;
            }
            final String className = target.getName();
            if (!admit(className, tick)) {
                return false;                       // 同一类 1200 tick 内已经做过了
            }
            ACCEPTED.incrementAndGet();
            final String readText = num(read);
            final String fieldText = num(field);
            final Runnable job = new Runnable() {
                @Override
                public void run() {
                    restoreNow(inst, target, className, readText, fieldText);
                }
            };
            if (!submit(job)) {
                DROPPED.incrementAndGet();          // 上一次还没跑完 ⇒ 丢掉这一条（不排队、不重试）
                return false;
            }
            return true;
        } catch (Throwable ignored) {
            return false;                           // 判据坏了绝不许影响移动/拾取/读血
        }
    }

    /**
     * 等队列跑空（<b>只给离线自检/命令用</b>：游戏里没有任何调用点，服务端线程绝不许等这里）。
     *
     * @param millis 最多等多久
     * @return {@code true} = 已跑空
     */
    public static boolean awaitIdle(long millis) {
        final long deadline = System.currentTimeMillis() + Math.max(0L, millis);
        while (System.currentTimeMillis() <= deadline) {
            if (PENDING.get() <= 0) {
                return true;
            }
            try {
                Thread.sleep(5L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return PENDING.get() <= 0;
            }
        }
        return PENDING.get() <= 0;
    }

    /** 读数（{@code accepted=… restored=… dropped=… min_interval_ticks=…}；只进自检/命令）。 */
    public static String statusLine() {
        final int last;
        synchronized (LAST_TICK) {
            last = LAST_TICK.size();
        }
        return "accepted=" + ACCEPTED.get()
                + " restored=" + RESTORED.get()
                + " dropped=" + DROPPED.get()
                + " classes_seen=" + last
                + " min_interval_ticks=" + MIN_INTERVAL_TICKS;
    }

    /** 清空限流状态与读数（离线自检用；游戏里没有调用点）。 */
    public static void reset() {
        synchronized (LAST_TICK) {
            LAST_TICK.clear();
        }
        ACCEPTED.set(0);
        RESTORED.set(0);
        DROPPED.set(0);
        REFUSED_LOGGED.set(false);
    }

    // ═══════════════════════════════ 真正的手术（工作线程） ═══════════════════════════════

    /**
     * 一次关键类还原 ＋ 一行逐字日志。
     *
     * <p>🔴 手术实现不在本类：这里只调 {@link HotfixKeyClassRestore#restoreForTriage}
     * （{@code method=null} ⇒ 扫该类全部真实方法）。它<b>自己不写日志</b>（逐字行放在
     * {@code Result#line}），所以外面的 {@code keyclass_restore_by_readclass} 是<b>唯一</b>
     * 一条 —— 不会出现"恢复两次"的误读。
     */
    private static void restoreNow(Instrumentation inst, Class<?> target, String className,
                                   String readText, String fieldText) {
        String line = "";
        try {
            final HotfixKeyClassRestore.Result r = HotfixKeyClassRestore.restoreForTriage(inst, target, null);
            if (r == null) {
                line = LINE + " class=" + className + " trigger=" + TRIGGER
                        + " read=" + readText + " field=" + fieldText
                        + " stripped=0 ours_kept=0 retransforms=0 reason=null_result bytes=none";
            } else {
                RESTORED.incrementAndGet();
                // 🆕 2026-11-21（P2）：`bytes=<来源> size=<N>` 永远打在**最后一列**。
                //   🔴 它是"真 0"与"没读到"的分界线：
                //     stripped=0 ours_kept=0 … bytes=baseline size=31902  ⇒ 读过，确实 0
                //     stripped=0 ours_kept=0 … bytes=none                 ⇒ 三级回退全空，没读到
                line = LINE + " class=" + token(r.className == null || "?".equals(r.className) ? className : r.className)
                        + " trigger=" + TRIGGER
                        + " read=" + readText + " field=" + fieldText
                        + " stripped=" + r.stripped
                        + " ours_kept=" + r.oursKept
                        + " retransforms=" + r.retransforms
                        // 成功路径到这里就结束（逐字行与任务书一致）；失败/无事才补 reason=
                        + (r.ok ? "" : " reason=" + token(r.reason))
                        + " bytes=" + r.bytesTag();
            }
        } catch (Throwable t) {
            line = LINE + " class=" + className + " trigger=" + TRIGGER
                    + " read=" + readText + " field=" + fieldText
                    + " stripped=0 ours_kept=0 retransforms=0 reason=exception_" + simple(t) + " bytes=none";
        }
        log(line);
    }

    // ═══════════════════════════════ 限流 / 队列 ═══════════════════════════════

    /**
     * 同一类 {@value #MIN_INTERVAL_TICKS} tick 只放行一次。
     *
     * <p>tick 往回跳（换世界 / 换维度，gameTime 重新计）时<b>放行一次</b> ——
     * 否则新世界前 1200 tick 里一次都不会动，看起来像"没挂上"。
     */
    private static boolean admit(String className, long tick) {
        synchronized (LAST_TICK) {
            final Long prev = LAST_TICK.get(className);
            if (prev != null && tick >= prev.longValue()
                    && tick - prev.longValue() < MIN_INTERVAL_TICKS) {
                return false;
            }
            LAST_TICK.put(className, Long.valueOf(tick));
            return true;
        }
    }

    /** 投递到工作线程（队列满 ⇒ {@code false}，调用方如实记一次 dropped）。 */
    private static boolean submit(Runnable job) {
        ensureWorker();
        if (!QUEUE.offer(job)) {
            return false;
        }
        PENDING.incrementAndGet();
        return true;
    }

    /** 起那条 daemon 工作线程（只起一次；连线程都起不来 ⇒ 退化成"就地在调用线程跑一次"）。 */
    private static void ensureWorker() {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        try {
            final Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    for (;;) {
                        final Runnable job;
                        try {
                            job = QUEUE.take();
                        } catch (InterruptedException ie) {
                            return;                 // 被中断 ⇒ 就这样退出（daemon，不影响进程）
                        }
                        try {
                            job.run();
                        } catch (Throwable ignored) {
                            // 单次手术炸了不许把工作线程带走
                        } finally {
                            PENDING.decrementAndGet();
                        }
                    }
                }
            }, "shanhai-hotfix-readclass");
            t.setDaemon(true);
            t.start();
        } catch (Throwable t) {
            // 起线程失败 ⇒ 退回"就地在调用线程上跑"：宁可卡一下，也别让这条判据完全不工作
            final Runnable fallback = QUEUE.poll();
            if (fallback != null) {
                PENDING.decrementAndGet();
                try {
                    fallback.run();
                } catch (Throwable ignored) {
                    // 同上
                }
            }
        }
    }

    // ═══════════════════════════════ 日志 / 小工具 ═══════════════════════════════

    /** 打一行（顺序：接上的出口 → 反射 {@code chainLog} → stdout；与 {@code HotfixKeyClassRestore} 同款）。 */
    private static void log(String line) {
        final LineSink s = sink;
        if (s != null) {
            try {
                s.line(line);
                return;
            } catch (Throwable ignored) {
                // 接上的出口炸了 ⇒ 继续走兜底
            }
        }
        final Method m = logMethod();
        if (m != null) {
            try {
                m.invoke(null, line);
                return;
            } catch (Throwable ignored) {
                // 兜底失败 ⇒ stdout
            }
        }
        try {
            System.out.println("[SHANHAI-HOTFIX] " + line);
        } catch (Throwable ignored) {
            // 连 stdout 都没了：只能放弃这一行
        }
    }

    /** 反射找 {@code HotfixAutoCounter.chainLog(String)}（只找一次；找不到就一直走 stdout）。 */
    private static Method logMethod() {
        if (logLookedUp) {
            return logMethod;
        }
        synchronized (HotfixReadClassRestore.class) {
            if (!logLookedUp) {
                try {
                    final Class<?> engine = Class.forName("com.shanhai.add.hotfix.HotfixAutoCounter", false,
                            HotfixReadClassRestore.class.getClassLoader());
                    final Method m = engine.getDeclaredMethod("chainLog", String.class);
                    m.setAccessible(true);
                    logMethod = m;
                } catch (Throwable ignored) {
                    logMethod = null;
                }
                logLookedUp = true;
            }
        }
        return logMethod;
    }

    /** 日志字段里不许出现空格／竖线（与 {@code HotfixAutoCounter.token} 同一个口径）。 */
    private static String token(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "?";
        }
        final StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            final char c = raw.charAt(i);
            if (c == ' ' || c == '\t' || c == '|' || c == HotfixTriageVerdict.FULLWIDTH_BAR
                    || c == '\n' || c == '\r') {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 数值文本（与 {@code HotfixHealthClampStrike#num} 同一个口径）：{@code -Infinity} ／ 三位小数。 */
    private static String num(float v) {
        if (Float.isNaN(v)) {
            return "NaN";
        }
        if (Float.isInfinite(v)) {
            return v > 0.0F ? "Infinity" : "-Infinity";
        }
        return String.format(Locale.ROOT, "%.3f", Float.valueOf(v));
    }

    private static String simple(Throwable t) {
        return t == null ? "?" : t.getClass().getSimpleName();
    }
}
