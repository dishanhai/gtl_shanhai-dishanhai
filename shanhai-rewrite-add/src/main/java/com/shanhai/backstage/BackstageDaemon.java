package com.shanhai.backstage;

import com.shanhai.backstage.BackstagePorts.ParentCheck;
import com.shanhai.backstage.BackstagePorts.StateSource;
import com.shanhai.backstage.BackstagePorts.TitleSink;
import com.shanhai.backstage.BackstagePorts.WindowProbe;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 后台守护进程的入口（<b>第二个 java 进程</b>，由游戏在客户端初始化时启动）。
 *
 * <p>命令行（由 {@code com.shanhai.client.backstage.BackstageProcess} 生成）：
 * <pre>
 * javaw.exe -Xmx24m -cp &lt;游戏 classpath + 本 mod jar + jna&gt; \
 *   com.shanhai.backstage.BackstageDaemon &lt;stateFile&gt; &lt;logFile&gt; &lt;parentPid&gt;
 * </pre>
 *
 * <h4>它只做四件事</h4>
 * <ol>
 *   <li>每 100 ms 读一次状态文件（<b>变了才重读</b>）；</li>
 *   <li>用 {@code SendMessageTimeout(WM_NULL, 100ms)} 从<b>进程外</b>判游戏窗口卡没卡；</li>
 *   <li>用 {@code currentTimeMillis - startEpochMs} 自己算秒数（<b>与游戏主线程卡不卡无关</b>），
 *       拼标题并交给 {@link Win32TitleSink} 写；</li>
 *   <li>启动时<b>一次性</b>把 {@code user32!DisableProcessWindowsGhosting} 注入父进程
 *       （{@link GhostInjector}，可被 {@code backstage_caption.txt} 的 {@code ghost=off} 关掉）——
 *       不注入的话 Windows 一判「未响应」就用幽灵位图盖掉整窗，标题写进去也看不见；</li>
 *   <li>父进程消失 / 状态文件说 {@code STOP} ⇒ 自己退出。</li>
 * </ol>
 *
 * <h4>🔴 它不碰的东西</h4>
 * <p>不加载任何 Minecraft 类、不读游戏内存、不碰 GLFW；除了 {@code java.*} 与
 * {@code com.shanhai.backstage.*} 只通过反射碰 JNA。这保证了它即使和游戏共用 classpath
 * 也<b>不会把游戏类初始化一遍</b>。
 */
public final class BackstageDaemon {

    /** 循环节拍：100 ms（用户指定）。 */
    public static final long CADENCE_MS = 100L;

    /**
     * 窗口卡住时是否继续写标题。
     *
     * <p>B 组实验（{@code temp/backstage-probe} 的 {@code Win32Experiment}）实测：目标线程阻塞 3 s 时，
     * {@code SendMessageTimeout(WM_NULL, 100)} 返回 0（确认卡住），但跨进程 {@code SetWindowTextW}
     * <b>0 ms 返回成功</b>且读回新标题 ⇒ 卡住时照写（它不走 SendMessage）。
     */
    public static final boolean WRITE_WHILE_HUNG = true;

    private BackstageDaemon() {
    }

    public static void main(String[] args) {
        if (args == null || args.length < 3) {
            System.err.println("usage: BackstageDaemon <stateFile> <logFile> <parentPid>");
            return;
        }
        final Path stateFile = Paths.get(args[0]);
        final Path logFile = Paths.get(args[1]);
        long parsedParent;
        try {
            parsedParent = Long.parseLong(args[2].trim());
        } catch (Throwable t) {
            parsedParent = -1L;
        }
        final long parentPid = parsedParent;

        final BackstageLog log = BackstageFileLog.open(logFile);
        final BackstageStateFile file = new BackstageStateFile(stateFile);
        BackstageState initial = null;
        try {
            initial = BackstageStateFile.read(stateFile);
        } catch (Throwable ignored) {
            // 读不到就按 0 句柄起：循环里还会再读到
        }
        final long initialHwnd = initial == null ? BackstageState.NO_HWND : initial.hwnd;

        // 🔴 幂等：子进程活着期间一直持有 backstage.lock；拿不到说明已经有一个在跑，直接退出。
        // 游戏侧的 BackstageProcess 也是靠"能不能拿到这把锁"判断要不要 spawn 的。
        final String lock = acquireLock(stateFile);
        if ("already_running".equals(lock)) {
            log.line("exit reason=already_running");
            System.exit(0);
        }

        final Win32 win32 = Win32.tryCreate();
        final String javaExe = System.getProperty("java.home", "?") + "\\bin\\java";
        // 写路径开关：<stateDir>/backstage_write.txt 里一行 write=deflast|settext（默认 deflast）
        final Win32TitleSink.WritePath writePath = readWritePath(stateFile);
        log.line("start pid=" + ProcessHandle.current().pid()
                + " parent=" + parentPid
                + " hwnd=0x" + Long.toHexString(initialHwnd)
                + " jna=" + (win32 != null ? "yes" : "no")
                + " cadence_ms=" + CADENCE_MS
                + " write_while_hung=" + (WRITE_WHILE_HUNG ? 1 : 0)
                + " write_path=" + (writePath == Win32TitleSink.WritePath.DEFLAST ? "deflast" : "settext")
                + " lock=" + lock
                + " java=" + javaExe);
        if (win32 == null) {
            log.line("fallback=no_win32 reason=" + Win32.failureReason());
        }

        // ③ 一次性关掉 Windows 的「未响应」幽灵态：对父进程（= 游戏进程）注入一次
        //    DisableProcessWindowsGhosting。放在独立守护线程里 ⇒ 它再慢（最多 WaitForSingleObject
        //    的 2 s）也只慢它自己，绝不进写标题 / 画标题带 / 计时这三条线。
        //    只调一次、不重试、失败只记一行（ghost_disabled ok=… / reason=…）。
        startGhostInjection(log, win32, parentPid, stateFile);

        final Win32TitleSink writer = win32 == null ? null
                : new Win32TitleSink(win32, log, WRITE_WHILE_HUNG, writePath);
        if (writer != null) {
            writer.setHwnd(initialHwnd);
        }

        final TitleSink sink = writer != null ? writer : new NoWin32Sink(log);
        final WindowProbe probe = win32 != null ? new RealWindowProbe(win32) : new DeadWindowProbe();
        final BackstageState[] last = new BackstageState[]{initial};
        final StateSource source = () -> {
            final BackstageState fresh = file.readIfChanged();
            if (fresh != null) {
                last[0] = fresh;
                if (writer != null) {
                    writer.setHwnd(fresh.hwnd);
                }
            }
            return last[0];
        };
        final ParentCheck parent = parentPid <= 0L
                ? () -> true
                : () -> ProcessHandle.of(parentPid).map(ProcessHandle::isAlive).orElse(false);

        final BackstageDaemonCore core = new BackstageDaemonCore(source, sink, probe,
                System::currentTimeMillis, log, parent);

        // 标题带画线程（第二步）：独立线程、100 ms 一拍；默认只在写调用飞 >300 ms 时画。
        final CaptionPainter painter = writer == null || !CaptionPainter.VERIFIED ? null : CaptionPainter.tryCreate();
        if (writer != null && !CaptionPainter.VERIFIED) {
            log.line("caption_paint=unavailable reason=not_verified");
        } else if (writer != null && painter == null) {
            log.line("caption_paint=unavailable reason=" + CaptionPainter.failureReason());
        }
        final CaptionPaintThread paintThread = painter == null ? null
                : new CaptionPaintThread(painter, writer, log, core::lastTitle, stateFile);
        if (paintThread != null) {
            paintThread.start();
            log.line("caption_paint thread=started");
        }

        long nextBeat = System.currentTimeMillis();
        try {
            while (core.runOnce()) {
                nextBeat += CADENCE_MS;
                final long sleep = nextBeat - System.currentTimeMillis();
                if (sleep > 0L) {
                    try {
                        Thread.sleep(sleep);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } else if (sleep < -CADENCE_MS) {
                    // 落后超过一拍（例如整机卡顿）：直接对齐到现在，不追帧
                    nextBeat = System.currentTimeMillis();
                }
            }
        } catch (Throwable t) {
            // 主循环绝不因为一拍异常就死：记录后按正常退出处理
            log.line("loop_error " + t.getClass().getSimpleName() + ":" + t.getMessage());
        }

        if (paintThread != null) {
            paintThread.shutdown();
        }
        final String reason = core.exitReason().isEmpty() ? "loop_end" : core.exitReason();
        log.line("exit reason=" + reason
                + " submitted=" + core.submitCount()
                + " completed=" + (writer == null ? 0 : writer.completedCount())
                + " failed=" + (writer == null ? 0 : writer.failedCount())
                + " fallbacks=" + (writer == null ? 0 : writer.fallbackCount())
                + " blocked_ms=" + (writer == null ? -1L : writer.lastWriteMs())
                + " path=" + (writer == null ? "-" : writer.lastPathUsed())
                + " skipped_hung=" + (writer == null ? 0 : writer.skipCount()));
        if (writer != null) {
            writer.close();
        }
        releaseLock();
        if (log instanceof AutoCloseable) {
            try {
                ((AutoCloseable) log).close();
            } catch (Throwable ignored) {
                // 进程要退出了
            }
        }
        System.exit(0);
    }

    // ─────────────────────────── 幽灵态开关（D） ───────────────────────────

    /**
     * 起一条一次性守护线程做幽灵态注入（{@code user32!DisableProcessWindowsGhosting}）。
     *
     * <p>为什么单独一条线程：注入要 {@code OpenProcess + VirtualAllocEx + CreateRemoteThread}
     * 并在目标上等它跑完，<b>任何一步都可能慢</b>；放进主循环就等于让写标题/计时去等它。
     * 这里既不加锁、也不碰状态文件，失败只写一行日志。
     *
     * <p>开关：{@code config/shanhai/backstage_caption.txt} 的 {@code ghost=on|off}（默认 on）。
     *
     * @param win32      JNA 是否可用（{@code null} = 连 user32 都取不到 ⇒ 一定注不进去，直接报 -1）
     * @param parentPid  子进程拿到的父进程 pid（生产 = 游戏进程）
     * @param stateFile  状态文件（配置与它同目录）
     */
    private static void startGhostInjection(BackstageLog log, Win32 win32, long parentPid, Path stateFile) {
        try {
            if (!GhostInjector.enabledFromConfig(stateFile)) {
                log.line("ghost_disabled skipped reason=ghost_off");
                return;
            }
            if (win32 == null) {
                log.line("ghost_disabled ok=0 reason=" + GhostInjector.REASON_JNA_MISSING);
                return;
            }
            if (parentPid <= 0L) {
                log.line("ghost_disabled ok=0 reason=" + GhostInjector.REASON_OPEN_PROCESS);
                return;
            }
            final Thread thread = new Thread(() -> {
                try {
                    log.line(GhostInjector.disableGhosting(parentPid).logLine());
                } catch (Throwable t) {
                    log.line("ghost_disabled ok=0 reason=" + GhostInjector.REASON_JNA_MISSING);
                }
            }, "shanhai-ghost-inject");
            thread.setDaemon(true);
            thread.start();
        } catch (Throwable t) {
            // 连开关都读不出来：按"没做过"记一行，绝不影响主循环
            try {
                log.line("ghost_disabled ok=0 reason=" + GhostInjector.REASON_JNA_MISSING);
            } catch (Throwable ignored) {
                // 日志出口自己炸了也只能算了
            }
        }
    }

    // ─────────────────────────── 单实例锁 ───────────────────────────

    /**
     * 读写路径开关：{@code <stateDir>/backstage_write.txt} 里一行 {@code write=deflast|settext}。
     *
     * <p>没这个文件 / 读不懂 ⇒ 默认 {@code deflast}（B 方案）。只在启动时读一次。
     */
    private static Win32TitleSink.WritePath readWritePath(Path stateFile) {
        try {
            final Path cfg = stateFile.resolveSibling("backstage_write.txt");
            if (java.nio.file.Files.isRegularFile(cfg)) {
                for (String raw : java.nio.file.Files.readAllLines(cfg, java.nio.charset.StandardCharsets.UTF_8)) {
                    final String line = raw == null ? "" : raw.trim();
                    if (line.startsWith("write=")) {
                        return Win32TitleSink.WritePath.parse(line.substring("write=".length()));
                    }
                }
            }
        } catch (Throwable ignored) {
            // 读不懂就用默认
        }
        return Win32TitleSink.WritePath.DEFLAST;
    }

    private static java.nio.channels.FileChannel lockChannel;
    private static java.nio.channels.FileLock processLock;

    /**
     * 拿 {@code <gameDir>/config/shanhai/backstage.lock} 的独占锁，<b>持有到进程退出</b>。
     *
     * @return {@code held} / {@code already_running}（锁被别人持着） / {@code error}（锁文件都打不开）
     */
    private static String acquireLock(Path stateFile) {
        try {
            final Path lockFile = stateFile.resolveSibling("backstage.lock");
            final Path parent = lockFile.getParent();
            if (parent != null) {
                java.nio.file.Files.createDirectories(parent);
            }
            lockChannel = java.nio.channels.FileChannel.open(lockFile,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
            processLock = lockChannel.tryLock();
            return processLock != null ? "held" : "already_running";
        } catch (Throwable t) {
            // 锁文件打不开（权限/路径）：不影响计时与写标题，只是失去"多开"保护
            return "error";
        }
    }

    private static void releaseLock() {
        try {
            if (processLock != null) {
                processLock.release();
            }
        } catch (Throwable ignored) {
            // 进程都要退了
        }
        try {
            if (lockChannel != null) {
                lockChannel.close();
            }
        } catch (Throwable ignored) {
            // 同上
        }
    }

    /** JNA 不可用时的 sink：只记一行，之后什么都不做（核心照常算、照常记日志）。 */
    private static final class NoWin32Sink implements TitleSink {
        private final BackstageLog log;
        private boolean logged;

        NoWin32Sink(BackstageLog log) {
            this.log = log;
        }

        @Override
        public void submit(String title, boolean windowHung) {
            if (!logged) {
                logged = true;
                log.line("fallback=main_thread reason=jna_missing (只算不写)");
            }
        }
    }

    /** 真探活：先看窗口在不在，再发 WM_NULL 带 100 ms 超时。 */
    private static final class RealWindowProbe implements WindowProbe {
        private final Win32 win32;

        RealWindowProbe(Win32 win32) {
            this.win32 = win32;
        }

        @Override
        public boolean isWindow(long hwnd) {
            return win32.isWindow(hwnd);
        }

        @Override
        public boolean isHung(long hwnd, int timeoutMs) {
            return win32.isHung(hwnd, timeoutMs);
        }
    }

    /** 没有 JNA 时的探活：没有窗口可探，永远"不卡"（只算不写）。 */
    private static final class DeadWindowProbe implements WindowProbe {
        @Override
        public boolean isWindow(long hwnd) {
            return false;
        }

        @Override
        public boolean isHung(long hwnd, int timeoutMs) {
            return false;
        }
    }
}
