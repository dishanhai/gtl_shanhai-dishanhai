package com.shanhai.add.hotfix;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 游戏侧的<b>进程编排</b>：决定用哪个 {@code java.exe}、把 helper 拉起来、等它退出、把结果读回来。
 *
 * <h2>命令行（与 {@link HotfixHelper} 类注释里的契约一致）</h2>
 * <pre>
 *   &lt;java&gt; --add-modules jdk.attach -cp &lt;ASCII helper 目录&gt; \
 *          com.shanhai.add.hotfix.HotfixHelper &lt;pid&gt; &lt;agentJar&gt; &lt;evidenceDir&gt;
 * </pre>
 * <ul>
 *   <li>{@code --add-modules jdk.attach}：helper 里 attach 走的是反射，模块得在运行期存在；</li>
 *   <li>{@code -cp} 必须落在<b>纯 ASCII</b> 目录（{@link HotfixAgentJar} 已经把 helper 抽到那儿）；</li>
 *   <li>三条参数也都是 ASCII（同一个原因），{@link HotfixAgentJar#prepare()} 已校验过。</li>
 * </ul>
 *
 * <h2>为什么要单独一个线程抽 stdout</h2>
 * {@code ProcessBuilder} 的 stdout 是管道；父进程不抽会写满管道缓冲区，子进程就<b>永久阻塞</b>在
 * {@code println} 上 —— 表现为"attach 卡死"，最容易被误判成 attach 本身有问题。
 * 所以：起一个 daemon 线程边读边攒，主线程只负责 {@code waitFor(超时)}。
 *
 * <h2>超时与失败</h2>
 * 超时 → {@code destroyForcibly()}，结果标 {@code timedOut}（这条信息会进日志）。
 * 启动失败（{@link IOException}）不抛给调用方 —— 预热是"尽力而为"，失败只该降级。
 *
 * <p>本类只碰 {@code java.*}：它能被<b>脱离游戏</b>的离线自检直接驱动（见 {@code temp\hotfix\报告.md}）。
 */
public final class HotfixLauncher {

    /** 默认超时。实测 attach 全程 ~1.1s（含子 JVM 启动）；30s 是"机器很忙 + 杀软拦一下"的余量。 */
    public static final long DEFAULT_TIMEOUT_MS = 30_000L;

    private HotfixLauncher() {
    }

    /** 一次 helper 运行的结果（不可变；所有字段都直接进日志）。 */
    public static final class Result {

        /** 进程是否被成功创建（false 时 {@link #failure} 有原因）。 */
        public final boolean launched;

        /** 是否超时被强杀。 */
        public final boolean timedOut;

        /** 退出码；没起来时是 {@code -1}。 */
        public final int exitCode;

        /** 实际用的命令行（空格拼接，仅供判读）。 */
        public final String command;

        /** helper 的 stdout（已按行切好，含 {@code [SHANHAI-HOTFIX]} 前缀）。 */
        public final String[] lines;

        /** 启动失败的原因（成功启动时为空串）。 */
        public final String failure;

        public final long elapsedMillis;

        Result(boolean launched, boolean timedOut, int exitCode, String command, String[] lines,
               String failure, long elapsedMillis) {
            this.launched = launched;
            this.timedOut = timedOut;
            this.exitCode = exitCode;
            this.command = command;
            this.lines = lines;
            this.failure = failure;
            this.elapsedMillis = elapsedMillis;
        }

        /**
         * 成功判据：起来了 + 没超时 + 退出码 0 + helper 自己打的最后一行是 {@code RESULT=SUCCESS}。
         *
         * <p>四条缺一不可：只看退出码不够（helper 里任何一条没走到的分支都可能让退出码保持 0），
         * 只看 {@code RESULT=SUCCESS} 也不够（那行是 helper 自己说的，进程可能随后崩掉）。
         */
        public boolean success() {
            if (!launched || timedOut || exitCode != 0) {
                return false;
            }
            final String last = lastResultLine();
            return last != null && last.contains("RESULT=SUCCESS");
        }

        /** helper 打的最后一条 {@code RESULT=...} 行；没有则 {@code null}。 */
        public String lastResultLine() {
            String found = null;
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].contains("RESULT=")) {
                    found = lines[i];
                }
            }
            return found;
        }

        /** 失败的一句话原因（成功时为空串）。优先级：没起来 &gt; 超时 &gt; helper 的 reason= &gt; 退出码。 */
        public String reason() {
            if (!launched) {
                return "helper_not_launched";
            }
            if (timedOut) {
                return "helper_timeout";
            }
            if (exitCode != 0) {
                final String line = lastResultLine();
                final String fromLine = line == null ? null : field(line, "reason");
                return fromLine != null ? fromLine : ("helper_exit_" + exitCode);
            }
            return success() ? "" : "helper_no_success_line";
        }

        @Override
        public String toString() {
            return "launched=" + launched + " timedOut=" + timedOut + " exit=" + exitCode
                    + " elapsed_ms=" + elapsedMillis + " lines=" + lines.length;
        }
    }

    /**
     * 选 {@code java} 可执行文件。
     *
     * <p>优先 {@code java.home/bin/java[.exe]}：它一定是<b>当前正在跑这个游戏的那个 JDK</b>
     * （我们需要的模块/版本与它一致，最不容易出"另一个 JDK 缺 jdk.attach"这种岔子）。
     * 退路才是 {@code ProcessHandle} 记下的启动命令（某些包装器下 {@code java.home} 指向别处）。
     *
     * @return 可执行文件路径；都拿不到时 {@code null}（调用方降级）
     */
    public static String javaExecutable() {
        final String home = System.getProperty("java.home", "");
        if (!home.isEmpty()) {
            final String exe = HotfixPaths.join(home, "bin", isWindows() ? "java.exe" : "java");
            if (exe != null && new File(exe).isFile()) {
                return exe;
            }
        }
        try {
            final java.util.Optional<String> cmd = ProcessHandle.current().info().command();
            if (cmd != null && cmd.isPresent() && !cmd.get().isEmpty()) {
                return cmd.get();
            }
        } catch (Throwable ignored) {
            // 拿不到就返回 null，由调用方打 degraded
        }
        return null;
    }

    /** 拼命令行（不执行；离线自检用它核对参数顺序与 ASCII 约束，也便于日志复现）。 */
    public static List<String> commandLine(String javaExe, long pid, HotfixAgentJar.Prepared prepared) {
        final List<String> cmd = new ArrayList<String>(10);
        cmd.add(javaExe);
        cmd.add("--add-modules");
        cmd.add("jdk.attach");
        cmd.add("-cp");
        cmd.add(prepared.helperDir);
        cmd.add(HotfixAgentJar.HELPER_CLASS);
        cmd.add(String.valueOf(pid));
        cmd.add(prepared.agentJar);
        cmd.add(prepared.evidenceDir);
        return cmd;
    }

    /**
     * 拉起 helper 并等它结束。
     *
     * @param javaExe  {@link #javaExecutable()} 的结果（{@code null} ⇒ 直接返回未启动的结果）
     * @param pid      目标 pid（= 游戏自己）
     * @param prepared {@link HotfixAgentJar#prepare()} 的产物
     * @param timeoutMs 等多久
     */
    public static Result runHelper(String javaExe, long pid, HotfixAgentJar.Prepared prepared, long timeoutMs) {
        final long t0 = System.currentTimeMillis();
        if (javaExe == null || javaExe.isEmpty()) {
            return new Result(false, false, -1, "", new String[0], "java_executable_not_found",
                    System.currentTimeMillis() - t0);
        }
        if (prepared == null) {
            return new Result(false, false, -1, "", new String[0], "agent_jar_not_prepared",
                    System.currentTimeMillis() - t0);
        }
        final List<String> cmd = commandLine(javaExe, pid, prepared);
        final String command = joinCommand(cmd);

        Process process;
        try {
            final ProcessBuilder pb = new ProcessBuilder(cmd);
            // 合成一条流读：helper 的失败原因也走 stdout（它不打 stderr 的正文），合并后顺序才是真的
            pb.redirectErrorStream(true);
            // 不要把游戏的控制台/标准输入接给它：它不需要，接上反而可能被父进程的关闭动作影响
            pb.redirectInput(ProcessBuilder.Redirect.from(dummyInputFile()));
            process = pb.start();
        } catch (Throwable t) {
            return new Result(false, false, -1, command, new String[0], "start_failed:" + t,
                    System.currentTimeMillis() - t0);
        }

        final ByteArrayOutputStream sink = new ByteArrayOutputStream(8192);
        final Thread pump = new Thread(new Runnable() {
            @Override
            public void run() {
                InputStream in = null;
                try {
                    in = process.getInputStream();
                    final byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        synchronized (sink) {
                            sink.write(buf, 0, n);
                        }
                    }
                } catch (Throwable ignored) {
                    // 进程被强杀时这里会抛，属正常路径
                } finally {
                    if (in != null) {
                        try {
                            in.close();
                        } catch (Throwable ignored) {
                            // ignore
                        }
                    }
                }
            }
        }, "shanhai-hotfix-helper-out");
        pump.setDaemon(true);
        pump.start();

        boolean finished;
        try {
            finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finished = false;
        }
        if (!finished) {
            try {
                process.destroyForcibly();
                process.waitFor(2L, TimeUnit.SECONDS);
            } catch (Throwable ignored) {
                // 尽力而为
            }
        }
        try {
            pump.join(2000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        final String text;
        synchronized (sink) {
            text = new String(sink.toByteArray(), StandardCharsets.UTF_8);
        }
        final int exitCode = finished ? process.exitValue() : -1;
        return new Result(true, !finished, exitCode, command, splitLines(text), "",
                System.currentTimeMillis() - t0);
    }

    /**
     * 从一个 {@code key=value} 行里取值（到空格为止）。
     *
     * @return 值；没有这个 key 时 {@code null}
     */
    public static String field(String line, String key) {
        if (line == null || key == null) {
            return null;
        }
        final String needle = key + "=";
        int from = 0;
        while (true) {
            final int at = line.indexOf(needle, from);
            if (at < 0) {
                return null;
            }
            // 必须是"词首"（前一个字符是空格/制表/行首），避免 reason= 匹配到 other_reason=
            if (at == 0 || line.charAt(at - 1) == ' ' || line.charAt(at - 1) == '\t') {
                final int start = at + needle.length();
                int end = start;
                while (end < line.length() && line.charAt(end) != ' ' && line.charAt(end) != '\t') {
                    end++;
                }
                return line.substring(start, end);
            }
            from = at + needle.length();
        }
    }

    // ------------------------------------------------------------------ 小工具

    private static String[] splitLines(String text) {
        if (text == null || text.isEmpty()) {
            return new String[0];
        }
        final String[] raw = text.split("\r?\n");
        final List<String> out = new ArrayList<String>(raw.length);
        for (int i = 0; i < raw.length; i++) {
            if (!raw[i].isEmpty()) {
                out.add(raw[i]);
            }
        }
        return out.toArray(new String[0]);
    }

    private static String joinCommand(List<String> cmd) {
        final StringBuilder sb = new StringBuilder(256);
        for (int i = 0; i < cmd.size(); i++) {
            if (i > 0) {
                sb.append(' ');
            }
            final String s = cmd.get(i);
            if (s.indexOf(' ') >= 0) {
                sb.append('"').append(s).append('"');
            } else {
                sb.append(s);
            }
        }
        return sb.toString();
    }

    /** 子系统进程的 stdin 指向一个空文件（Windows 上不支持 {@code Redirect.from(null)}）。 */
    private static File dummyInputFile() throws IOException {
        final Path dir = HotfixPaths.workDir();
        final Path empty = dir.resolve("empty-stdin.txt");
        if (!Files.isRegularFile(empty)) {
            Files.write(empty, new byte[0]);
        }
        return empty.toFile();
    }

    private static boolean isWindows() {
        final String os = System.getProperty("os.name", "");
        return os.toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    /** 供离线自检使用：把一段 {@code key=value} 文本按行切（{@code null} → 空数组）。 */
    public static String[] lines(String text) {
        return splitLines(text);
    }

    /** 相对路径转绝对（离线自检里把一个 class 目录变成可命令行传入的绝对路径）。 */
    public static String absolute(String path) {
        return Paths.get(path).toAbsolutePath().toString();
    }
}
