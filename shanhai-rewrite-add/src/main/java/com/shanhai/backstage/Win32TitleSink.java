package com.shanhai.backstage;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * 真正写窗口标题的那条线程（子进程内，名字 {@code shanhai-backstage-writer}）。
 *
 * <h4>两条写路径（2026-11-21 用户拍板 B）</h4>
 * <ul>
 *   <li><b>{@code deflast}（默认）</b>：{@link Win32#setTextViaDefWindowProc} ——
 *       直接调 {@code DefWindowProcW(hwnd, WM_SETTEXT, 0, 文本)}。实测（{@code WritePathExperiment}）
 *       在目标线程卡死 10 秒期间 <b>2 ms 返回</b>且外部读回新值；</li>
 *   <li><b>{@code settext}（回退）</b>：{@link Win32#setWindowText} —— 普通 {@code SetWindowTextW}，
 *       卡死时会阻塞整个卡死期（实测 9.96 秒）。</li>
 * </ul>
 * 配置在 {@code <gameDir>/config/shanhai/backstage_write.txt}：一行 {@code write=deflast|settext}。
 *
 * <h4>🔴 计数口径（上一轮就是被 {@code writes} 骗的）</h4>
 * <ul>
 *   <li>{@code submitted} —— 收到多少次要写的请求（= 计时循环的交卷次数）；</li>
 *   <li>{@code completed} —— <b>原生调用返回成功 + 立刻用 {@code GetWindowTextW} 读回一致</b>才算 1 次。
 *       卡死期间 {@code submitted} 会涨、{@code completed} 不涨 —— 一眼就能看出"根本没写进去"。</li>
 * </ul>
 *
 * <h4>失败降级</h4>
 * <p>{@code deflast} 返回 false / 抛异常 ⇒ 打一行
 * {@code write_path=deflast failed reason=…}，<b>这一次</b>回退 {@code SetWindowTextW}；
 * 不永久切换（下一拍还会先试 deflast），也不崩。
 */
public final class Win32TitleSink implements BackstagePorts.TitleSink, AutoCloseable {

    /** 写路径。 */
    public enum WritePath {
        /** {@code DefWindowProcW(WM_SETTEXT)}：不阻塞（默认）。 */
        DEFLAST,
        /** {@code SetWindowTextW}：会阻塞，回退用。 */
        SETTEXT;

        public static WritePath parse(String raw) {
            return "settext".equalsIgnoreCase(raw == null ? "" : raw.trim()) ? SETTEXT : DEFLAST;
        }
    }

    /** 一次写请求：标题 + 当时的句柄 + 当时窗口是否卡住。 */
    private static final class Request {
        final long hwnd;
        final String title;
        final boolean windowHung;

        Request(long hwnd, String title, boolean windowHung) {
            this.hwnd = hwnd;
            this.title = title;
            this.windowHung = windowHung;
        }
    }

    private final Win32 win32;
    private final BackstageLog log;
    private final WritePath requestedPath;
    private final AtomicReference<Request> slot = new AtomicReference<>();
    private final Thread worker;

    private volatile long hwnd = BackstageState.NO_HWND;
    private volatile boolean writeWhileHung;
    private volatile boolean running = true;

    // ── 计数（口径见类注释） ──
    private volatile int submitted;
    private volatile int completed;
    private volatile int failed;
    private volatile int fallbacks;
    private volatile int skipped;
    private volatile int writesWhileHung;
    private volatile long lastWriteMs = -1L;
    private volatile long maxWriteMs = -1L;
    /** 正在进行的写调用开始时刻（ns）；0 = 没有在飞。{@link #lastWriteMs()} 在飞时返回"已卡多久"。 */
    private volatile long inflightSinceNs = 0L;
    private volatile boolean loggedFirst;
    private volatile boolean loggedFallback;
    private volatile String lastPathUsed = "";

    public Win32TitleSink(Win32 win32, BackstageLog log, boolean writeWhileHung, WritePath path) {
        this.win32 = win32;
        this.log = log == null ? BackstageLog.NOOP : log;
        this.writeWhileHung = writeWhileHung;
        this.requestedPath = path == null ? WritePath.DEFLAST : path;
        this.worker = new Thread(this::loop, "shanhai-backstage-writer");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    public WritePath requestedPath() {
        return requestedPath;
    }

    public void setWriteWhileHung(boolean value) {
        this.writeWhileHung = value;
    }

    public void setHwnd(long newHwnd) {
        this.hwnd = newHwnd;
    }

    @Override
    public void submit(String title, boolean windowHung) {
        if (title == null) {
            return;
        }
        submitted++;
        slot.set(new Request(hwnd, title, windowHung));
        LockSupport.unpark(worker);
    }

    private void loop() {
        while (running) {
            final Request request = slot.getAndSet(null);
            if (request == null) {
                LockSupport.parkNanos(5_000_000L);
                continue;
            }
            if (request.hwnd == BackstageState.NO_HWND) {
                continue;
            }
            if (request.windowHung && !writeWhileHung) {
                skipped++;
                continue;
            }
            final long t0 = System.nanoTime();
            inflightSinceNs = t0;
            boolean ok = false;
            String used = requestedPath == WritePath.DEFLAST ? "deflast" : "settext";
            boolean fellBack = false;
            try {
                if (requestedPath == WritePath.DEFLAST) {
                    ok = win32.setTextViaDefWindowProc(request.hwnd, request.title);
                    if (!ok) {
                        fellBack = true;
                        fallbacks++;
                        if (!loggedFallback) {
                            loggedFallback = true;
                            log.line("write_path=deflast failed reason=return_false; fallback=settext");
                        }
                        ok = win32.setWindowText(request.hwnd, request.title);
                        used = "settext_fallback";
                    }
                } else {
                    ok = win32.setWindowText(request.hwnd, request.title);
                }
            } catch (Throwable t) {
                ok = false;
                if (!loggedFallback) {
                    loggedFallback = true;
                    log.line("write_path=" + used + " failed reason=" + t.getClass().getSimpleName()
                            + "; fallback=settext");
                }
                try {
                    ok = win32.setWindowText(request.hwnd, request.title);
                    used = "settext_fallback";
                    fellBack = true;
                } catch (Throwable ignored) {
                    ok = false;
                }
            }
            final long ms = (System.nanoTime() - t0) / 1_000_000L;
            inflightSinceNs = 0L;
            lastWriteMs = ms;
            maxWriteMs = Math.max(maxWriteMs, ms);
            lastPathUsed = used;

            // completed 的口径：原生调用成功 + 立刻读回一致
            boolean match = false;
            if (ok) {
                try {
                    final String read = win32.getWindowText(request.hwnd, Math.max(64, request.title.length() + 8));
                    match = request.title.equals(read);
                } catch (Throwable ignored) {
                    match = false;
                }
            }
            if (ok && match) {
                completed++;
                if (request.windowHung) {
                    writesWhileHung++;
                }
                if (!loggedFirst) {
                    loggedFirst = true;
                    log.line("first_write hwnd=0x" + Long.toHexString(request.hwnd) + " ms=" + ms
                            + " path=" + used + " during_hung=" + (request.windowHung ? 1 : 0)
                            + (fellBack ? " fallback=1" : ""));
                }
            } else {
                failed++;
                if (failed <= 3) {
                    log.line("write_fail hwnd=0x" + Long.toHexString(request.hwnd) + " path=" + used
                            + " err=" + win32.lastError() + " ms=" + ms + " returned=" + (ok ? 1 : 0)
                            + " verify=" + (match ? 1 : 0));
                }
            }
            if (ms >= 200L) {
                log.line("write_slow ms=" + ms + " path=" + used
                        + " during_hung=" + (request.windowHung ? 1 : 0) + " ok=" + (ok ? 1 : 0));
            }
        }
    }

    @Override
    public int writesWhileHung() {
        return writesWhileHung;
    }

    @Override
    public int completedCount() {
        return completed;
    }

    @Override
    public long lastWriteMs() {
        final long since = inflightSinceNs;
        if (since > 0L) {
            // 正在写：报"已经卡了多久"——卡死时这条会每 2 秒涨一次，一眼能看出来
            return Math.max(0L, (System.nanoTime() - since) / 1_000_000L);
        }
        return lastWriteMs;
    }

    public int submittedCount() {
        return submitted;
    }

    /** 当前写调用已经飞了多久（毫秒）；0 = 没有在飞。画线程的 inflight 判据。 */
    public long inflightMs() {
        final long since = inflightSinceNs;
        return since > 0L ? Math.max(0L, (System.nanoTime() - since) / 1_000_000L) : 0L;
    }

    /** 当前目标句柄（画线程用）。 */
    public long hwnd() {
        return hwnd;
    }

    public int failedCount() {
        return failed;
    }

    public int fallbackCount() {
        return fallbacks;
    }

    public int skipCount() {
        return skipped;
    }

    public long maxWriteMs() {
        return maxWriteMs;
    }

    public String lastPathUsed() {
        return lastPathUsed;
    }

    @Override
    public void close() {
        running = false;
        LockSupport.unpark(worker);
        try {
            worker.join(300L);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
