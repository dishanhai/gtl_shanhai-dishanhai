package com.shanhai.client.backstage;

import com.shanhai.backstage.BackstageState;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 游戏侧的唯一入口：<b>写状态文件 + 起/停后台进程</b>（纯 JDK，可直接被离线探针驱动）。
 *
 * <h4>启动（幂等）</h4>
 * <ol>
 *   <li>把初始状态原子写进 {@code config/shanhai/backstage_state.txt}；</li>
 *   <li>拿 {@code config/shanhai/backstage.lock} 的独占锁试一次：
 *       <b>拿不到</b> ⇒ 已经有子进程在跑（锁被它持有）⇒ 不重复起；
 *       <b>拿到了</b> ⇒ 说明没有在跑，立刻释放并 spawn；</li>
 *   <li>用 {@code ProcessBuilder} 起 {@code javaw.exe -Xmx24m -cp <游戏 classpath + 本 mod jar + jna>
 *       com.shanhai.backstage.BackstageDaemon …}；</li>
 *   <li>挂一个 JVM shutdown hook（关游戏/崩溃时兜底），与 {@code GameShuttingDownEvent} 双保险。</li>
 * </ol>
 *
 * <h4>🔴 管道红线</h4>
 * <p>子进程的 stdout/stderr <b>一律重定向到文件</b>（{@code logs/shanhai-backstage-console.log}），
 * <b>绝不接管道</b>：父进程在加载存档时可能几秒不读管道，管道缓冲区一满子进程就卡死。
 * stdin 用继承（子进程从不读它）。
 *
 * <h4>classpath 怎么拼</h4>
 * <p>{@code System.getProperty("java.class.path")}（Forge 启动器的 {@code -cp} 里就是那一整套
 * libraries，JNA 也在其中）<b>+ 本 mod jar</b>（{@code getProtectionDomain().getCodeSource()}
 * 在生产环境是 {@code union:} 壳 ⇒ 由调用方给真实路径）<b>+ 反射找到的 jna / jna-platform jar</b>
 * （防止某些启动器只把 libraries 放进模块路径、{@code java.class.path} 里没有）。
 */
public final class BackstageProcess {

    /** 状态文件相对游戏目录的路径。 */
    public static final String STATE_RELATIVE = "config/shanhai/backstage_state.txt";
    /** 锁文件相对游戏目录的路径。 */
    public static final String LOCK_RELATIVE = "config/shanhai/backstage.lock";
    /** 子进程自己的日志（它自己写）。 */
    public static final String LOG_RELATIVE = "logs/shanhai-backstage.log";
    /** 子进程的 stdout/stderr（JVM 崩溃时看这里）。 */
    public static final String CONSOLE_RELATIVE = "logs/shanhai-backstage-console.log";

    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static final AtomicBoolean STOPPED = new AtomicBoolean(false);
    private static final Object LOCK = new Object();

    private static volatile Path gameDir;
    private static volatile Path statePath;
    private static volatile Path lockPath;
    private static volatile Path logPath;
    private static volatile Process child;
    private static volatile long childPid;
    private static volatile BackstageState mirror = BackstageState.empty();
    private static volatile BackstageState lastWritten;
    private static volatile long stampCounter;
    private static volatile Consumer<String> logger = BackstageProcess::defaultLog;
    private static volatile boolean shutdownHookInstalled;

    private BackstageProcess() {
    }

    // ─────────────────────────────── 日志 ───────────────────────────────

    /** 游戏日志出口（Forge 侧注入 {@code ShanhaiMod.LOGGER::info}）。 */
    public static void setLogger(Consumer<String> newLogger) {
        if (newLogger != null) {
            logger = newLogger;
        }
    }

    private static void defaultLog(String message) {
        System.err.println("[SHANHAI-BACKSTAGE] " + message);
    }

    private static void log(String message) {
        try {
            logger.accept(message);
        } catch (Throwable ignored) {
            // 日志出口自己炸了也不许影响启动
        }
    }

    // ─────────────────────────────── 启动 ───────────────────────────────

    /**
     * 起后台进程（幂等：同一 JVM 内多次调用只会 spawn 一次；跨进程靠 {@code backstage.lock}）。
     *
     * @param dir             游戏目录（{@code FMLPaths.GAMEDIR}）
     * @param ownJarOrClasses 本 mod 的 jar 真实路径（开发环境是 classes 目录）
     * @param gamePid         游戏进程号（子进程用它判断父进程是否还活着）
     * @param initialState    初始状态（phase / 起始 epoch / 版本 / 日期 / 玩家名 / hwnd）
     * @return 是否已处于「已启动」状态（{@code false} = 本次 spawn 失败，调用方应继续用降级路径）
     */
    public static boolean bootstrap(Path dir, String ownJarOrClasses, long gamePid, BackstageState initialState) {
        if (dir == null) {
            return false;
        }
        synchronized (LOCK) {
            if (STARTED.get()) {
                if (initialState != null) {
                    publish(initialState);
                }
                return true;
            }
            gameDir = dir;
            statePath = dir.resolve(STATE_RELATIVE);
            lockPath = dir.resolve(LOCK_RELATIVE);
            logPath = dir.resolve(LOG_RELATIVE);
            final Path consolePath = dir.resolve(CONSOLE_RELATIVE);

            final BackstageState init = initialState == null ? BackstageState.empty() : initialState;
            mirror = init;
            writeState(init);
            lastWritten = init;

            // ① 锁检查：锁被别的进程持着 = 已经有子进程在跑
            if (lockHeldByOther()) {
                STARTED.set(true);
                installShutdownHook();
                log("backstage already running (lock held)");
                return true;
            }

            // ② 起子进程（先把 logs 目录建出来：stdout 重定向到 logs/…-console.log，
            //    父目录不存在时 ProcessBuilder.start() 会直接失败，且报错看起来像"找不到 java"）
            try {
                Files.createDirectories(dir.resolve("logs"));
                final List<String> command = buildCommand(dir, ownJarOrClasses, gamePid, consolePath);
                final ProcessBuilder pb = new ProcessBuilder(command);
                pb.directory(dir.toFile());
                pb.redirectErrorStream(true);
                pb.redirectOutput(ProcessBuilder.Redirect.appendTo(consolePath.toFile()));
                pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
                final Process started = pb.start();
                child = started;
                childPid = started.pid();
                STARTED.set(true);
                installShutdownHook();
                log("backstage started pid=" + childPid + " hwnd=0x" + Long.toHexString(init.hwnd)
                        + " java=" + command.get(0));
                return true;
            } catch (Throwable t) {
                log("backstage spawn failed: " + t.getClass().getSimpleName() + ":" + t.getMessage());
                return false;
            }
        }
    }

    /**
     * 只写状态文件，不碰进程（每 tick 由 {@code ShanhaiWindowTitle} 的 sink 调；内容没变就什么都不做）。
     *
     * <p>🔴 <b>一次写、原子</b>：{@link #writeState} 走「写 .tmp + ATOMIC_MOVE」，子进程要么读到旧的
     * 完整一版、要么读到新的完整一版，<b>绝不会读到半截</b>。时间点与标题必须<b>同一份</b>落盘，
     * 否则子进程可能拿新 title 配旧 anchor（那一拍就会跳）。
     */
    public static void publish(BackstageState state) {
        if (state == null) {
            return;
        }
        mirror = state;
        final BackstageState last = lastWritten;
        if (last != null && state.sameContent(last)) {
            return;
        }
        if (!STARTED.get()) {
            lastWritten = state;
            return;
        }
        writeState(state);
        lastWritten = state;
    }

    /** 原子写状态文件（异常只记一行，不抛）。 */
    private static void writeState(BackstageState state) {        if (statePath == null) {
            return;
        }
        try {
            final BackstageState stamped = state.withStamp(++stampCounter);
            com.shanhai.backstage.BackstageStateFile.writeAtomic(statePath, stamped);
        } catch (Throwable t) {
            log("state write failed: " + t.getClass().getSimpleName() + ":" + t.getMessage());
        }
    }

    /** 拿得到锁 = 没有别的进程在跑；拿不到 = 有。 */
    private static boolean lockHeldByOther() {
        if (lockPath == null) {
            return false;
        }
        try {
            final Path parent = lockPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (FileChannel channel = FileChannel.open(lockPath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                final FileLock lock = channel.tryLock();
                if (lock == null) {
                    return true;
                }
                lock.release();
                return false;
            }
        } catch (OverlappingFileLockException e) {
            return true;   // 本 JVM 里有别的线程持着（理论上不会发生）
        } catch (Throwable t) {
            // 锁文件建不出来（只读目录？）：当成"没有在跑"，让 spawn 去试
            return false;
        }
    }

    /** 命令行：java 可执行文件 + 小堆 + 短 classpath + 主类 + 三个参数。 */
    private static List<String> buildCommand(Path dir, String ownJarOrClasses, long gamePid, Path consolePath) {
        final List<String> cmd = new ArrayList<>(12);
        cmd.add(findJavaExecutable());
        // 子进程只做一个 10 Hz 的计时循环：小堆 + 不分层编译 + 序列化 GC，启动快、占内存少
        cmd.add("-Xmx24m");
        cmd.add("-XX:TieredStopAtLevel=1");
        cmd.add("-XX:+UseSerialGC");
        cmd.add("-Dfile.encoding=UTF-8");
        cmd.add("-cp");
        cmd.add(buildClasspath(ownJarOrClasses));
        cmd.add("com.shanhai.backstage.BackstageDaemon");
        cmd.add(dir.resolve(STATE_RELATIVE).toString());
        cmd.add(dir.resolve(LOG_RELATIVE).toString());
        cmd.add(Long.toString(gamePid));
        return cmd;
    }

    /** {@code java.home/bin/javaw.exe}（优先）→ {@code java.exe} → PATH 上的 {@code java}。 */
    private static String findJavaExecutable() {
        try {
            final Path home = Paths.get(System.getProperty("java.home", ""));
            final Path javaw = home.resolve("bin").resolve("javaw.exe");
            if (Files.isRegularFile(javaw)) {
                return javaw.toString();
            }
            final Path java = home.resolve("bin").resolve("java.exe");
            if (Files.isRegularFile(java)) {
                return java.toString();
            }
            final Path javaBin = home.resolve("bin").resolve("java");
            if (Files.isRegularFile(javaBin)) {
                return javaBin.toString();
            }
        } catch (Throwable ignored) {
            // 落到 PATH
        }
        return "java";
    }

    /**
     * 子进程 classpath：游戏自己的 {@code java.class.path} + 本 mod jar + jna/jna-platform。
     *
     * <p>去重（Windows 上大小写不敏感）且保序；某一段不存在也照加（File 的 classpath 会忽略坏路径，
     * 但保留它能让日志里看出"我们拼过它"）。
     */
    static String buildClasspath(String ownJarOrClasses) {
        final Set<String> entries = new LinkedHashSet<>();
        final String separator = File.pathSeparator;
        final String classPath = System.getProperty("java.class.path", "");
        for (String entry : classPath.split(java.util.regex.Pattern.quote(separator))) {
            final String trimmed = entry.trim();
            if (!trimmed.isEmpty()) {
                entries.add(trimmed);
            }
        }
        if (ownJarOrClasses != null && !ownJarOrClasses.trim().isEmpty()) {
            entries.add(ownJarOrClasses.trim());
        }
        // 有些启动器只把 libraries 放进模块路径，java.class.path 里没有 jna：反射找一下它的 jar
        addCodeSource(entries, "com.sun.jna.NativeLibrary");
        addCodeSource(entries, "com.sun.jna.platform.win32.User32");
        return String.join(separator, entries);
    }

    private static void addCodeSource(Set<String> entries, String className) {
        try {
            final Class<?> type = Class.forName(className, false, BackstageProcess.class.getClassLoader());
            final java.security.CodeSource source = type.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return;
            }
            final java.net.URL location = source.getLocation();
            if (!"file".equalsIgnoreCase(location.getProtocol())) {
                return;
            }
            entries.add(Paths.get(location.toURI()).toString());
        } catch (Throwable ignored) {
            // 没有就是没有：子进程会走 fallback=no_win32
        }
    }

    private static void installShutdownHook() {
        if (shutdownHookInstalled) {
            return;
        }
        shutdownHookInstalled = true;
        try {
            final Thread hook = new Thread(BackstageProcess::requestStop, "shanhai-backstage-stop");
            hook.setDaemon(true);
            Runtime.getRuntime().addShutdownHook(hook);
        } catch (Throwable ignored) {
            // JVM 正在关闭 / 安全管理器：GameShuttingDownEvent 已经管到主要路径
        }
    }

    // ─────────────────────────────── 停止 ───────────────────────────────

    /**
     * 停后台进程：先写 {@code phase=STOP} 让它自己退，最多等 800 ms，再 destroy / destroyForcibly。
     *
     * <p><b>幂等</b>：重复调用只会走一次（探针第 3 条断言这个）。关游戏时
     * {@code GameShuttingDownEvent} 与 JVM shutdown hook 谁先到都行。
     */
    public static void requestStop() {
        if (!STARTED.get() || !STOPPED.compareAndSet(false, true)) {
            return;
        }
        try {
            final BackstageState last = mirror;
            if (last != null) {
                writeState(last.withPhase(com.shanhai.backstage.BackstagePhase.STOP, 0L));
            }
        } catch (Throwable ignored) {
            // 写不进去也没关系：父进程消失时子进程自己会退
        }
        final Process process = child;
        if (process == null) {
            return;
        }
        try {
            if (!process.waitFor(800L, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                process.destroy();
                if (!process.waitFor(300L, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                }
            }
        } catch (Throwable ignored) {
            // 进程已经没了
        }
        log("backstage stop requested");
    }

    // ─────────────────────────────── 只读 ───────────────────────────────

    /** 已经启动过（不等于子进程此刻活着）。 */
    public static boolean isStarted() {
        return STARTED.get();
    }

    /** 子进程 pid（未启动 = 0）。 */
    public static long childPid() {
        return childPid;
    }

    /** 子进程句柄（探针用；未启动 = null）。 */
    public static Process childProcess() {
        return child;
    }

    /** 当前内存里的状态镜像（窗口出生那一帧拼标题用，不读文件）。 */
    public static BackstageState mirror() {
        return mirror;
    }

    public static Path statePath() {
        return statePath;
    }

    public static Path logPath() {
        return logPath;
    }

    /** 探针/诊断：状态文件里此刻写的是什么。 */
    public static BackstageState readStateFile() {
        try {
            return statePath == null ? null : com.shanhai.backstage.BackstageStateFile.read(statePath);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 探针/诊断：只看进程是否活着（不去 destroy）。 */
    public static boolean isChildAlive() {
        final Process process = child;
        return process != null && process.isAlive();
    }
}
