package com.shanhai.backstage;

import com.shanhai.backstage.BackstagePorts.BackstageClock;
import com.shanhai.backstage.BackstagePorts.ParentCheck;
import com.shanhai.backstage.BackstagePorts.StateSource;
import com.shanhai.backstage.BackstagePorts.TitleSink;
import com.shanhai.backstage.BackstagePorts.WindowProbe;

/**
 * 后台进程的<b>心跳逻辑本体</b>（纯 JDK、无原生调用、无线程）：一拍 {@link #runOnce()} 做四件事 ——
 * 读状态、探活、算秒数并拼标题、把标题交给 {@link TitleSink}。
 *
 * <p>为什么要和 {@link BackstageDaemon#main(String[])} 拆开：<b>自证</b>。
 * 探针直接 new 一个本类，注入 fake 时钟 / fake 探活 / fake sink，就能断言
 * 「3 秒里秒数从 0 涨到 3.x、提交次数 ≥ 10、负对照不涨」——测的是<b>交付的那份逻辑</b>，
 * 不是另写一份仿制品。
 *
 * <h4>卡住的判据</h4>
 * <p>每拍对窗口发 {@code WM_NULL} 的 {@code SendMessageTimeout}（{@link WindowProbe#isHung}）。
 * 单拍超时可能只是 100–200 ms 的正常长帧（GC / 资源加载），所以要求<b>连续</b>
 * {@link #HUNG_CONFIRM_BEATS} 拍都失败才算卡住，卡住时长从第一次失败那一拍起算。
 * 恢复时打一行 {@code hung hung_ms=… recovered_ms=… title_written_during_hung=…}。
 *
 * <h4>🔴 卡顿期间「界面干等」要按加载算（2026-11-21 用户实测）</h4>
 * <p>用户那局在<b>标题屏/加载屏</b>卡了 17 秒，而当时状态是 IDLE（= 界面干等）⇒ 加载计时被
 * 「干等冻结」规则冻住。现在补一条：<b>IDLE + 窗口卡住</b>时，把这段卡顿当 LOADING 计时
 * （{@link #hungCreditMs} 单调累加，恢复后不回退），并打一行
 * {@code phase_rescue IDLE->LOADING after_ms=…}（检测延迟 = 2 拍探活 ≈ 100–200 ms，&lt; 500 ms）。
 */
public final class BackstageDaemonCore {

    /** 连续多少拍探活失败才算「卡住」（2 拍 = 约 200 ms，滤掉单次长帧）。 */
    public static final int HUNG_CONFIRM_BEATS = 2;

    /** {@code state} 日志的最小间隔：2 秒 1 行（spec 要求「别刷屏」）。 */
    public static final long STATE_LOG_INTERVAL_MS = 2_000L;

    private final StateSource source;
    private final TitleSink sink;
    private final WindowProbe probe;
    private final BackstageClock clock;
    private final BackstageLog log;
    private final ParentCheck parent;
    private final long startMs;

    private BackstageState state;
    private BackstageState lastState;
    private String lastTitle;

    private boolean windowHung;
    private int consecutiveFailures;
    private long hungSinceMs;
    private long lastHungMs;
    private int writesWhileHungAtHungStart;

    // ── 卡顿补偿（IDLE 下长卡顿按加载算） ──
    private long hungCreditMs;
    private long creditFromMs = -1L;
    private boolean creditEligible;
    private long rescuedAtMs = -1L;

    private long lastStateLogMs;
    private long firstTitleAtMs = -1L;
    private int submitCount;
    private long firstHungLogAtMs = -1L;
    private int waitingLogged;

    private String exitReason = "";

    public BackstageDaemonCore(StateSource source, TitleSink sink, WindowProbe probe,
                               BackstageClock clock, BackstageLog log, ParentCheck parent) {
        this.source = source;
        this.sink = sink;
        this.probe = probe;
        this.clock = clock;
        this.log = log == null ? BackstageLog.NOOP : log;
        this.parent = parent;
        this.startMs = clock.currentTimeMillis();
        this.lastStateLogMs = 0L;
    }

    // ─────────────────────────────── 一拍 ───────────────────────────────

    /**
     * 走一拍。
     *
     * @return {@code false} = 该退出了（父进程没了 / 状态文件说 STOP）
     */
    public boolean runOnce() {
        final long now = clock.currentTimeMillis();

        // ① 父进程还在不在
        if (parent != null && !parent.isAlive()) {
            exitReason = "parent_gone";
            return false;
        }

        // ② 读状态（变了才真的读文件；读不到就沿用上一份）
        final BackstageState fresh = source == null ? null : source.current();
        if (fresh != null) {
            state = fresh;
        }
        if (state == null) {
            if (waitingLogged == 0) {
                waitingLogged = 1;
                log.line("wait_state file_not_ready");
            }
            return true;
        }
        if (state != lastState) {
            onPhaseChanged(lastState, state);
            lastState = state;
        }
        if (state.phase == BackstagePhase.STOP) {
            exitReason = "stop_file";
            return false;
        }

        // ③ 探活
        boolean hungNow = false;
        final long hwnd = state.hwnd;
        if (hwnd != BackstageState.NO_HWND && probe != null) {
            try {
                if (probe.isWindow(hwnd)) {
                    hungNow = probe.isHung(hwnd, 100);
                }
            } catch (Throwable ignored) {
                hungNow = true;   // 探活自己炸了：宁可当卡住
            }
        }
        updateHung(now, hungNow);

        // ④ 算秒数、拼标题（旧格式）、交给 sink
        final long credit = currentCredit(now);
        final String title = titleFor(state, now, credit);
        if (!title.equals(lastTitle)) {
            lastTitle = title;
            submitCount++;
            if (firstTitleAtMs < 0L) {
                firstTitleAtMs = now;
                log.line("first_title ms=" + (now - startMs));
            }
            try {
                sink.submit(title, windowHung);
            } catch (Throwable t) {
                log.line("sink_error " + t.getClass().getSimpleName());
            }
        }

        // ⑤ 限流日志：每 2 秒一行
        if (now - lastStateLogMs >= STATE_LOG_INTERVAL_MS) {
            lastStateLogMs = now;
            final double secs = displayedSeconds(now);
            log.line("state phase=" + effectivePhase().id() + " secs=" + BackstageTitle.logSeconds(secs)
                    + " submitted=" + submitCount
                    + " completed=" + (sink == null ? 0 : sink.completedCount())
                    + " blocked_ms=" + (sink == null ? -1L : sink.lastWriteMs())
                    + " hwnd=0x" + Long.toHexString(state.hwnd)
                    + (windowHung ? " hung=1" : ""));
        }
        return true;
    }

    /**
     * <b>这一拍该显示的标题</b>：状态里有时间点就<b>永远自己现拼</b>，只有老状态文件（没 anchor）才退回
     * 父进程写来的成品串。
     *
     * <h4>为什么要这样（2026-11-22 定案）</h4>
     * <p>旧行为是「父进程把成品串塞进 {@code state.title}，子进程原样用」⇒ 游戏一卡，父进程就不再算时间、
     * 也不再写新串 ⇒ 标题冻住（实测 48 秒不动）。现在父进程只送 {@code anchorEpochMs / worldEnter /
     * playedAccum / paused*} 这些<b>时间点与累计量</b>（卡住时它们本来也不变），子进程每 100 ms 用
     * {@link BackstageTitle#compose} 现算 ⇒ 字在变。
     *
     * <p>{@code anchorEpochMs <= 0} 只可能是「老版本父进程写的状态文件」或「父进程还没发过第一份」，
     * 这时只能退回 {@code state.title}（父进程还没送时间点，硬拼会从 1970 年算起）。
     */
    private static String titleFor(BackstageState state, long nowMs, long creditMs) {
        if (state.anchorEpochMs > 0L) {
            return BackstageTitle.compose(state, nowMs, creditMs);
        }
        final String fallback = state.title;
        // 占位符"不可用"不算成品串（BackstageState 把空串归一成了它）—— 那种情况拼出来也比它强
        if (fallback != null && !fallback.isEmpty() && !BackstageState.NA.equals(fallback)) {
            return fallback;
        }
        return BackstageTitle.compose(state, nowMs, creditMs);
    }

    /** 阶段切换：清掉上一段的卡顿注记，并打一行。 */
    private void onPhaseChanged(BackstageState previous, BackstageState current) {        final BackstagePhase from = previous == null ? null : previous.phase;
        if (from != current.phase) {
            log.line("phase " + (from == null ? "none" : from.id()) + " -> " + current.phase.id()
                    + " start_epoch_ms=" + current.startEpochMs);
        }
        if (current.phase == BackstagePhase.IDLE) {
            lastHungMs = 0L;
            windowHung = false;
            consecutiveFailures = 0;
        }
    }

    /**
     * 卡住状态机：连续 {@link #HUNG_CONFIRM_BEATS} 拍失败才置「卡住」；恢复时打一行耗时。
     * 若卡住时状态是 IDLE（界面干等），立刻做「按加载算」的救援并开始累计补偿。
     */
    private void updateHung(long now, boolean failedBeat) {
        if (failedBeat) {
            consecutiveFailures++;
            if (consecutiveFailures == 1) {
                hungSinceMs = now;
            }
            if (!windowHung && consecutiveFailures >= HUNG_CONFIRM_BEATS) {
                windowHung = true;
                writesWhileHungAtHungStart = sink == null ? 0 : sink.writesWhileHung();
                log.line("hung_begin since_ms=" + (now - hungSinceMs) + " hwnd=0x"
                        + Long.toHexString(state == null ? 0L : state.hwnd));
                // IDLE + 卡住 ⇒ 这段卡顿按加载算（用户 2026-11-21 实测：卡 17 秒却没进 LOADING）
                if (state != null && state.phase == BackstagePhase.IDLE) {
                    creditEligible = true;
                    creditFromMs = hungSinceMs;
                    rescuedAtMs = now;
                    log.line("phase_rescue IDLE->LOADING after_ms=" + (now - hungSinceMs));
                }
            }
        } else {
            if (windowHung) {
                windowHung = false;
                lastHungMs = Math.max(1L, now - hungSinceMs);
                final int during = (sink == null ? 0 : sink.writesWhileHung()) - writesWhileHungAtHungStart;
                log.line("hung hung_ms=" + lastHungMs + " recovered_ms=" + now
                        + " title_written_during_hung=" + (during > 0 ? 1 : 0));
            }
            if (creditEligible) {
                hungCreditMs += Math.max(0L, now - creditFromMs);
                creditEligible = false;
                creditFromMs = -1L;
                log.line("resume_stall credited_ms=" + hungCreditMs + " rescued_at_ms=" + rescuedAtMs);
            }
            consecutiveFailures = 0;
        }
    }

    /** 当前补偿（卡住中要现算，恢复后是累计值）。 */
    private long currentCredit(long nowMs) {
        long credit = hungCreditMs;
        if (creditEligible && creditFromMs > 0L) {
            credit += Math.max(0L, nowMs - creditFromMs);
        }
        return credit;
    }

    /** 日志/救援用的「有效阶段」：IDLE 且卡住 ⇒ 当作 LOADING。 */
    public BackstagePhase effectivePhase() {
        if (state == null) {
            return BackstagePhase.IDLE;
        }
        if (windowHung && state.phase == BackstagePhase.IDLE && state.pausedSinceEpochMs > 0L) {
            return BackstagePhase.LOADING;
        }
        return state.phase;
    }

    /** 日志里的 {@code secs}：加载中 = 加载已累计秒；进过世界 = 游玩秒。 */
    private double displayedSeconds(long nowMs) {
        if (state == null) {
            return 0.0D;
        }
        final long ms = state.everInWorld
                ? BackstageTitle.playedElapsedMs(state, nowMs)
                : BackstageTitle.loadingElapsedMs(state, nowMs, currentCredit(nowMs));
        return ms / 1000.0D;
    }

    // ─────────────────────────────── 读数 ───────────────────────────────

    /** 当前标题该显示的加载/游玩秒数（含卡顿补偿）。 */
    public double elapsedSeconds(long nowMs) {
        return displayedSeconds(nowMs);
    }

    /** 上一次卡顿时长（秒）。 */
    public double stallSeconds(long nowMs) {
        if (windowHung) {
            return Math.max(0.0D, (nowMs - hungSinceMs) / 1000.0D);
        }
        return lastHungMs > 0L ? lastHungMs / 1000.0D : 0.0D;
    }

    public boolean isWindowHung() {
        return windowHung;
    }

    public long lastHungMs() {
        return lastHungMs;
    }

    public long hungCreditMs() {
        return hungCreditMs + (creditEligible && creditFromMs > 0L ? Math.max(0L, clock.currentTimeMillis() - creditFromMs) : 0L);
    }

    public int submitCount() {
        return submitCount;
    }

    public long firstTitleAtMs() {
        return firstTitleAtMs;
    }

    public long startedAtMs() {
        return startMs;
    }

    public String exitReason() {
        return exitReason;
    }

    public BackstageState currentState() {
        return state;
    }

    public String lastTitle() {
        return lastTitle;
    }
}
