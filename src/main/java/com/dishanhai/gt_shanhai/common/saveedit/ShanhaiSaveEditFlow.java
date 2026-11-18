package com.dishanhai.gt_shanhai.common.saveedit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 存档直改的<b>显式状态机</b>（纯 JDK：不引用任何 Minecraft / Forge / 本 mod 的类）。
 *
 * <h2>一、为什么必须有一个状态机（而不是"顺序调用几个方法"）</h2>
 * 本功能的红线只有一条：<b>改盘只许发生在 {@code clearLevel} 返回之后、重开之前</b>。
 * 证据（{@code temp\save-edit\报告.md} §1.2）：
 * <ul>
 *   <li>早于它 ⇒ 服务器还活着，{@code stopServer} 里那一串保存会把改动打回，甚至与
 *       {@code IOWorker} 的异步写撞上；</li>
 *   <li>晚于它 ⇒ 新服已经在读盘，改了也白改（而且 {@code doWorldLoad} 会再写一次 level.dat）。</li>
 * </ul>
 * 所以本类把"什么时候能改盘"做成<b>一个纯函数</b> {@link #windowOpen(Phase)}，
 * 所有落盘调用点都必须先过 {@link #tryApply(String)}；被拒时会留一条
 * {@link ApplyAttempt} 记录，供离线判据 ① 断言。
 *
 * <h2>二、四步（用户口径「保存 → 停服 → 改盘 → 重开」）</h2>
 * <pre>
 *   IDLE ──begin()──▶ SAVING ──markSaved()──▶ SAVED ──markStopping()──▶ STOPPING
 *                                                                    │
 *                                            markStopped() ◀─────────┘
 *                                                       │
 *                                                       ▼
 *                                        ┌───── STOPPED ◀══ 🔴 唯一的改盘窗口
 *                                        │            │
 *                                        │   tryApply() ──▶ APPLYING ──markApplied()──▶ APPLIED
 *                                        │                                          │
 *                                        └────────────── markReopening() ◀──────────┘
 *                                                              │
 *                                                              ▼
 *                                                        REOPENING ──markDone()──▶ DONE
 *   任何一步出错 ⇒ fail(why) ⇒ FAILED（窗口永久关闭）
 * </pre>
 * 🔴 {@code APPLYING} <b>不是</b>窗口：一次运行只允许一次落盘（中途失败不许重试，
 * 因为半写状态最危险）。
 *
 * <h2>三、并发</h2>
 * 全局同时只允许一次运行（{@link #begin(String)} 用同一把锁判）。第二次启动被拒，
 * 且被拒的次数会被判据 ① 读出来（{@code concurrent_rejected}）。
 */
public final class ShanhaiSaveEditFlow {

    /** 日志前缀（与本功能其它类同一个串；这里定义一份，避免纯逻辑核反依赖日志类）。 */
    public static final String PREFIX = "[SHANHAI-SAVEEDIT]";

    /** 一次运行的阶段。 */
    public enum Phase {
        /** 还没有运行。 */
        IDLE,
        /** 第 ① 步：正在存盘（{@code saveEverything(flush=true)}）。 */
        SAVING,
        /** 第 ① 步完成。 */
        SAVED,
        /** 第 ② 步：正在停服（{@code level.disconnect()} + {@code clearLevel}）。 */
        STOPPING,
        /** 第 ② 步完成 = 🔴 改盘窗口打开（{@code clearLevel} 已返回）。 */
        STOPPED,
        /** 窗口内：正在改盘（只许一次）。 */
        APPLYING,
        /** 改盘已落盘。 */
        APPLIED,
        /** 第 ④ 步：正在重开（{@code validateAndCreateAccess → doWorldLoad}）。 */
        REOPENING,
        /** 全部完成。 */
        DONE,
        /** 出错了（窗口永久关闭）。 */
        FAILED
    }

    /**
     * 🔴 <b>唯一的窗口判据</b>（纯函数，判据 ① 与运行期共用它）。
     *
     * @return 只有 {@link Phase#STOPPED} 为 {@code true}
     */
    public static boolean windowOpen(Phase phase) {
        return phase == Phase.STOPPED;
    }

    /** 一次"想改盘"的尝试记录（运行期被拒也记；判据 ① 读它）。 */
    public record ApplyAttempt(String who, Phase phaseAtCall, boolean permitted) {
        @Override
        public String toString() {
            return (permitted ? "PERMIT" : "REFUSE") + "@" + phaseAtCall + "(" + who + ")";
        }
    }

    /** 判据 ① 的读数。 */
    public record Criterion1(boolean pass, int attempts, boolean anyPermitted, int outsideWindow,
                             int concurrentAccepted, String detail) {
        public String line() {
            return "criterion1 pass=" + pass
                    + " attempts=" + attempts
                    + " any_permitted=" + anyPermitted
                    + " permitted_outside_window=" + outsideWindow
                    + " concurrent_accepted=" + concurrentAccepted
                    + (detail == null || detail.isEmpty() ? "" : " at=[" + detail + "]");
        }
    }

    // ------------------------------------------------------------------ 全局唯一运行

    private static final Object GLOBAL = new Object();
    private static ShanhaiSaveEditFlow active;
    private static int concurrentAccepted;
    private static int concurrentRejected;
    private static int staleStolen;
    /**
     * 一行"刚刚发生、还没人写进日志"的事件（{@code flow_stolen reason=…} / {@code flow_released reason=…}）。
     *
     * <p>为什么放在这里而不是直接写日志：本类是<b>纯 JDK</b>（不引用 {@code GTDishanhaiMod}，离线判据要能
     * 直接 new 它跑）。所以这里只<b>记账</b>，由调用方 {@link #takeNotify()} 取走并写进日志
     * —— 见 {@code ShanhaiSaveEditBridge#logFlowNotify} / {@code ShanhaiSaveEditFactory#forget}。
     */
    private static String notify = "";

    /**
     * 一次运行超过这么久还没结束 ⇒ 视为僵死（比如客户端在停服中途被关），下一次 {@link #begin(String)}
     * 直接抢占。**没有这条，一次异常就会让功能永远"正忙"直到重开游戏。**
     */
    public static final long STALE_MS = 5L * 60L * 1000L;

    /**
     * 停服<b>之前</b>那几步（存盘 / 已存 / 正在停服）的正常耗时上限。
     *
     * <p>这三步全部在"点一下确认"之后 1 秒内完成（现场：{@code panel_confirm} 14:13:03.311 →
     * {@code confirm_started} 14:13:03.743 = 0.43 s）。所以超过 20 秒还停在这三步 ⇒ 客户端那条交接
     * 根本不会来了（{@code ShanhaiSaveEditBridge} 抛在 begin 之后、{@code mc.execute} 被丢掉…），
     * 这时候世界还没被动过，抢占是安全的。
     */
    public static final long PRE_WINDOW_MAX_MS = 20_000L;

    /** 改盘窗口 / 已改盘（{@code clearLevel} 之后）的正常耗时上限：超过就没人能救回来了。 */
    public static final long WINDOW_MAX_MS = 120_000L;

    /**
     * "上一次运行所属的面板已经不在"这条判据的最小年龄。
     *
     * <p>为什么要一个下限：{@code forget(holder)} 与"客户端开始停服"是两条并行的时间线（关容器包 vs
     * 客户端主线程），同一 tick 内先后到达是正常的。要求"至少过了 2 秒"才不会把一次<b>正常</b>运行
     * 误判成僵死。
     */
    public static final long OWNER_GONE_MIN_MS = 2_000L;


    private final String runId;
    /**
     * 这一次运行属于谁（服务端那份 {@code ShanhaiSaveEditFactory.Holder}；{@code null} = 调用方没给）。
     *
     * <p>只用于"抢占"判据：{@link #begin(String, Object)} 时如果上一次运行的面板对象已经不是
     * 这一个（= 旧面板早被关掉、Holder 也从复用表里摘掉了），那它不可能再跑完 —— 直接抢。
     */
    private final Object owner;
    private final long startedAt = System.currentTimeMillis();
    private final List<ApplyAttempt> attempts = new ArrayList<>();
    private Phase phase = Phase.IDLE;
    private String failure = "";
    private boolean released;

    private ShanhaiSaveEditFlow(String runId, Object owner) {
        this.runId = runId;
        this.owner = owner;
    }

    /**
     * 开始一次运行（全局互斥；<b>没有</b>面板身份可判，等价于 {@code owner = null}）。
     *
     * @return 抢到了就返回这次运行的 flow；已经有别的运行在跑就返回 {@code null}
     */
    public static ShanhaiSaveEditFlow begin(String runId) {
        return begin(runId, null);
    }

    /**
     * 开始一次运行（全局互斥 + <b>可抢占</b>）。
     *
     * <p>🔴 为什么要"可抢占"：{@link Phase} 里除了 DONE/FAILED 之外没有任何"中止"终态，而
     * "确认流程"横跨服务端线程与客户端主线程 —— 一旦中途有人抛异常（现场：{@code describe()} 在
     * {@code begin()} 之后 0.4 秒 NPE），<b>没人会来放锁</b>，之后每一次确认都只会得到
     * {@code STATUS_BUSY}（"另一次存档直改还没结束，等它完"），直到重开游戏。
     *
     * <p>三条抢占判据（按安全程度排序，理由都写在 {@link #stealReason}）：
     * <ol>
     *   <li>上一次已经是终态（DONE / FAILED）⇒ 它的 {@code release()} 漏了，直接抢；</li>
     *   <li>上一次属于<b>另一个</b> Holder，且它还停在"停服之前"那三步、且已经过了
     *       {@link #OWNER_GONE_MIN_MS} ⇒ 那个面板早没了，它不可能再跑完；</li>
     *   <li>超时（停服之前 20 s / 窗口 120 s / 重开 5 min，见三个常量）。</li>
     * </ol>
     *
     * @param owner 这一次运行所属的面板对象（服务端那份 Holder；{@code null} = 不判这条）
     * @return 抢到了就返回这次运行的 flow；否则 {@code null}（并让 {@code concurrentRejected} 加一）
     */
    public static ShanhaiSaveEditFlow begin(String runId, Object owner) {
        synchronized (GLOBAL) {
            if (active != null) {
                final long age = System.currentTimeMillis() - active.startedAt;
                final String why = stealReason(active, owner, age);
                if (why == null) {
                    concurrentRejected++;
                    return null;
                }
                staleStolen++;
                notify = "flow_stolen reason=" + why + " prev_phase=" + active.phase
                        + " prev_run=" + active.runId + " age_ms=" + age;
                // 上一次的 release() 之后会发现自己已不是 active（它里面判的是 active == this），
                // 所以这里直接清掉是安全的；不清就会永远 BUSY。
                active = null;
            }
            final ShanhaiSaveEditFlow flow = new ShanhaiSaveEditFlow(
                    runId == null || runId.isEmpty() ? "run" : runId, owner);
            flow.phase = Phase.SAVING;
            active = flow;
            concurrentAccepted++;
            return flow;
        }
    }

    /**
     * 上一次运行能不能直接抢占（{@code null} = 不能，只能拒绝）。
     *
     * @param previous 上一次运行（非 {@code null}）
     * @param owner    这一次运行所属的面板对象（{@code null} = 不判"面板已不在"这条）
     * @param ageMs    上一次运行已经活了多久
     */
    private static String stealReason(ShanhaiSaveEditFlow previous, Object owner, long ageMs) {
        // ① 终态：它本该已经 release()（漏了才会还挂在 active 上）⇒ 抢
        if (previous.phase == Phase.DONE || previous.phase == Phase.FAILED) {
            return "terminal:" + previous.phase;
        }
        // ② 上一次那个面板已经不在了（Holder 被从复用表摘掉 ⇒ 点确认的那个界面不存在了）。
        //    只在"停服之前"那三步认这条：那时世界还没停，除了那个面板没有第二个写入者。
        if (owner != null && previous.owner != null && previous.owner != owner
                && preWindow(previous.phase) && ageMs > OWNER_GONE_MIN_MS) {
            return "owner_gone:" + previous.phase;
        }
        // ③ 超时
        if (ageMs > deadlineMs(previous.phase)) {
            return "stale:" + previous.phase;
        }
        return null;
    }

    /**
     * 放锁的那几种入口（全部幂等；每一条都会写一行 {@code flow_released reason=…} 交给调用方打日志）。
     *
     * @param owner         必须匹配的面板对象（{@code null} = 不判）
     * @param reason        为什么要放（进日志；建议用"哪里放"而不是"为什么"，如 {@code panel_closed}）
     * @param preWindowOnly 只放"停服之前"那三步（{@code true}）还是无条件放（{@code false}）
     * @return 真的放掉了（{@code false} = 当前那次不是它 / 已经放过 / 阶段不允许）
     */
    private static boolean releaseIf(Object owner, String reason, boolean preWindowOnly) {
        final ShanhaiSaveEditFlow flow;
        synchronized (GLOBAL) {
            flow = active;
        }
        if (flow == null || flow.released) {
            return false;
        }
        if (owner != null && flow.owner != owner) {
            return false;
        }
        if (preWindowOnly && !preWindow(flow.phase)) {
            return false;
        }
        final String line = "flow_released reason=" + (reason == null || reason.isEmpty() ? "?" : reason)
                + " phase=" + flow.phase + " run=" + flow.runId;
        // ⚠️ 顺序要紧：release() 自己会拿 this → GLOBAL，所以上面那段 GLOBAL 必须先放掉
        //    （反序就是死锁：GLOBAL → this 对上别人的 this → GLOBAL）。
        flow.release();
        synchronized (GLOBAL) {
            notify = line;
        }
        return true;
    }

    /** 无条件放锁（只用于"确认流程自己抛了异常"这种已知必死的场合）。 */
    public static boolean releaseIfActive(String reason) {
        return releaseIf(null, reason, false);
    }

    /** 只在"停服之前"那三步放锁（面板被关掉 / 玩家取消这种场合：窗口一开就不许从这里放）。 */
    public static boolean releaseIfActivePreWindow(String reason) {
        return releaseIf(null, reason, true);
    }

    /** 只放<b>这一个面板</b>发起、且还停在"停服之前"那三步的那次运行（面板真关闭时调）。 */
    public static boolean releaseIfOwned(Object owner, String reason) {
        return releaseIf(owner, reason, true);
    }

    /** 取走那一行待写日志（没有 ⇒ 空串）。调用方用 {@code ShanhaiSaveEditFlow.PREFIX} 打出去。 */
    public static String takeNotify() {
        synchronized (GLOBAL) {
            final String line = notify;
            notify = "";
            return line == null ? "" : line;
        }
    }

    /** 这一步是不是"停服之前"（世界还没停、还没有任何东西被写盘）。 */
    public static boolean preWindow(Phase phase) {
        return phase == Phase.IDLE || phase == Phase.SAVING || phase == Phase.SAVED
                || phase == Phase.STOPPING;
    }

    /** 某个阶段"还算正常"的最长时长（超过 ⇒ 僵死，可抢）。 */
    private static long deadlineMs(Phase phase) {
        if (preWindow(phase)) {
            return PRE_WINDOW_MAX_MS;
        }
        if (phase == Phase.STOPPED || phase == Phase.APPLYING || phase == Phase.APPLIED) {
            return WINDOW_MAX_MS;
        }
        return STALE_MS;
    }


    /** 当前那次运行（没有就 {@code null}）。 */
    public static ShanhaiSaveEditFlow current() {
        synchronized (GLOBAL) {
            return active;
        }
    }

    /** 已放行的运行次数（离线判据读它；生产里应该永远是 1）。 */
    public static int concurrentAcceptedCount() {
        synchronized (GLOBAL) {
            return concurrentAccepted;
        }
    }

    /** 被并发挡掉的次数。 */
    public static int concurrentRejectedCount() {
        synchronized (GLOBAL) {
            return concurrentRejected;
        }
    }

    /** 抢占僵死运行的次数（正常应该永远是 0）。 */
    public static int staleStolenCount() {
        synchronized (GLOBAL) {
            return staleStolen;
        }
    }

    /** 把全局计数清零（只给离线判据用；生产不调）。 */
    public static void resetGlobalForProbe() {
        synchronized (GLOBAL) {
            active = null;
            concurrentAccepted = 0;
            concurrentRejected = 0;
            staleStolen = 0;
            notify = "";
        }
    }

    // ------------------------------------------------------------------ 状态迁移

    public Phase phase() {
        return this.phase;
    }

    public String runId() {
        return this.runId;
    }

    public String failure() {
        return this.failure;
    }

    /** ① 存盘成功。 */
    public synchronized boolean markSaved() {
        if (this.phase != Phase.SAVING) {
            return false;
        }
        this.phase = Phase.SAVED;
        return true;
    }

    /** ② 开始停服（可选一拍，便于日志区分"存完了"与"正在停"）。 */
    public synchronized boolean markStopping() {
        if (this.phase != Phase.SAVED) {
            return false;
        }
        this.phase = Phase.STOPPING;
        return true;
    }

    /**
     * ② 停服完成 = <b>窗口打开</b>。调用点必须在 {@code clearLevel(...)} <b>返回之后</b>。
     */
    public synchronized boolean markStopped() {
        if (this.phase != Phase.SAVED && this.phase != Phase.STOPPING) {
            return false;
        }
        this.phase = Phase.STOPPED;
        return true;
    }

    /**
     * 🔴 所有落盘路径的唯一闸门。
     *
     * <p>放行时把阶段推到 {@link Phase#APPLYING}（= 窗口关闭，一次运行只给一次），
     * 不管放不放行都会留一条 {@link ApplyAttempt}。
     *
     * @param who 谁在请求（"panel"/"probe"…；只进日志与判据）
     */
    public synchronized ApplyAttempt tryApply(String who) {
        final Phase at = this.phase;
        final boolean permitted = windowOpen(at);
        final ApplyAttempt attempt = new ApplyAttempt(who == null ? "?" : who, at, permitted);
        this.attempts.add(attempt);
        if (permitted) {
            this.phase = Phase.APPLYING;
        }
        return attempt;
    }

    /** 改盘成功落盘。 */
    public synchronized boolean markApplied() {
        if (this.phase != Phase.APPLYING) {
            return false;
        }
        this.phase = Phase.APPLIED;
        return true;
    }

    /** ③ 开始重开（窗口在这里永久关闭）。 */
    public synchronized boolean markReopening() {
        if (this.phase != Phase.STOPPED && this.phase != Phase.APPLYING && this.phase != Phase.APPLIED) {
            return false;
        }
        this.phase = Phase.REOPENING;
        return true;
    }

    /** ④ 完成。 */
    public synchronized boolean markDone() {
        if (this.phase != Phase.REOPENING) {
            return false;
        }
        this.phase = Phase.DONE;
        release();
        return true;
    }

    /** 任何一步失败：窗口永久关闭，并让下一次运行可以开始。 */
    public synchronized void fail(String why) {
        final Phase before = this.phase;
        this.phase = Phase.FAILED;
        this.failure = why == null ? "" : why;
        release();
        // 失败也是一次"放锁"，同样留一行给调用方写进日志（🔴 失败必须先放锁，否则下一次永远 BUSY）
        synchronized (GLOBAL) {
            notify = "flow_released reason=fail:" + (why == null || why.isEmpty() ? "?" : why)
                    + " phase_before=" + before + " run=" + this.runId;
        }
    }

    /** 手动放锁（正常路径由 {@link #markDone()} / {@link #fail(String)} 触发）。 */
    public synchronized void release() {
        if (this.released) {
            return;
        }
        this.released = true;
        synchronized (GLOBAL) {
            if (active == this) {
                active = null;
            }
        }
    }

    // ------------------------------------------------------------------ 读数

    /** 本次运行的尝试快照（不可变副本）。 */
    public synchronized List<ApplyAttempt> attempts() {
        return Collections.unmodifiableList(new ArrayList<>(this.attempts));
    }

    /** 一行机器可判的轨迹：`phase=DONE run=… attempts=[REFUSE@SAVING(probe), PERMIT@STOPPED(panel)]`。 */
    public synchronized String traceLine() {
        return "phase=" + this.phase + " run=" + this.runId
                + " attempts=" + this.attempts
                + (this.failure.isEmpty() ? "" : " failure=" + this.failure);
    }

    @Override
    public synchronized String toString() {
        return "ShanhaiSaveEditFlow[" + traceLine() + "]";
    }

    // ------------------------------------------------------------------ 判据 ①（离线）

    /**
     * <b>判据 ①</b>：改盘只发生在窗口内。
     *
     * <p>三条一起判：
     * <ol>
     *   <li>至少有一次"放行"（否则一个永远拒绝的假实现也能全绿）；</li>
     *   <li><b>没有任何一次放行发生在窗口之外</b>（早于停服 / 晚于重开 / 任何别的阶段）；</li>
     *   <li>并发启动只被接受 1 次。</li>
     * </ol>
     * 负对照就是把 (2)/(3) 弄坏：把改盘挪到停服前 ⇒ 会出现一条 {@code PERMIT@SAVED} ⇒ 红；
     * 挪到重开后 ⇒ {@code PERMIT@REOPENING}/{@code PERMIT@DONE} ⇒ 红；并发两次 ⇒ (3) 红。
     */
    public static Criterion1 criterion1(List<ApplyAttempt> attempts, int concurrentAccepted,
                                        int concurrentRejected) {
        boolean anyPermitted = false;
        int outside = 0;
        final StringBuilder where = new StringBuilder();
        if (attempts != null) {
            for (ApplyAttempt a : attempts) {
                if (a == null || !a.permitted()) {
                    continue;
                }
                anyPermitted = true;
                if (!windowOpen(a.phaseAtCall())) {
                    outside++;
                    if (where.length() < 240) {
                        where.append(where.length() == 0 ? "" : ", ").append(a);
                    }
                }
            }
        }
        final boolean single = concurrentAccepted <= 1;
        final boolean pass = anyPermitted && outside == 0 && single;
        return new Criterion1(pass, attempts == null ? 0 : attempts.size(), anyPermitted, outside,
                concurrentAccepted, where.toString()
                + (concurrentRejected > 0 ? (where.length() > 0 ? ", " : "") + "concurrent_rejected=" + concurrentRejected : ""));
    }
}
