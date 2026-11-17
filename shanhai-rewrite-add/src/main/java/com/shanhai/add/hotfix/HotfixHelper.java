package com.shanhai.add.hotfix;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.util.List;

/**
 * hotfix 的 <b>attach 侧</b>：一个独立的小 JVM，由游戏主进程用 {@link ProcessBuilder} 拉起。
 *
 * <h2>为什么必须是另一个 JVM</h2>
 * 实测（{@code temp/u1-attach\报告.md}）：{@code VirtualMachine.attach(<自己>) } 会抛
 * {@code "Can not attach to current VM"} ⇒ <b>自 attach 不行</b>，必须另起一个小 JVM 去 attach。
 * 反过来，小 JVM 对真实 Forge 1.20.1 服务端是成立的（attach 85ms / loadAgent 72ms）。
 *
 * <h2>🔴 这个类【只能】引用 {@code java.*}</h2>
 * 它会被 {@link HotfixAgentJar} 抽成<b>一个单独的 class 文件</b>放进 {@code %TEMP%\shanhai_hotfix\helper\}
 * （游戏 jar 不在它的 classpath 上）⇒ 只要它引用了项目里任何一个类型，运行期就是
 * {@code NoClassDefFoundError}，而且崩在子进程里、日志隔了一层，极难读。
 * 本类因此：**零内部类、零项目类型、零 MC 类型**，连 {@code com.sun.tools.attach.VirtualMachine}
 * 都是<b>反射</b>调的（见下面"为什么用反射"）。
 *
 * <h2>为什么用反射调 attach，而不是直接 import</h2>
 * {@code com.sun.tools.attach} 在 {@code jdk.attach} 模块里，编译期需要
 * {@code --add-modules jdk.attach}（还要跟 {@code options.release=17} 共存）。
 * 那会给 add 工程的 <b>整条 javac 命令行</b>动手术 —— 一个只影响本类的编译参数，
 * 却要赌整个工程还能编过，风险不对等。反射之后：编译期零依赖，运行期的缺失会变成一条
 * 可读的 {@code reason=jdk_attach_unavailable}（而不是 {@code NoClassDefFoundError} 砸在 main 上）。
 * 命令行仍然显式带 {@code --add-modules jdk.attach}（见用法）。
 *
 * <h2>用法 / 契约（游戏侧 {@link HotfixLauncher} 就按这个调）</h2>
 * <pre>
 *   java --add-modules jdk.attach -cp &lt;ASCII helper 目录&gt; \
 *        com.shanhai.add.hotfix.HotfixHelper &lt;pid&gt; &lt;agentJar 绝对路径&gt; [evidenceDir]
 * </pre>
 * <b>stdout</b>：每行 {@code [SHANHAI-HOTFIX] ...}，最后一行是
 * {@code [SHANHAI-HOTFIX] helper RESULT=SUCCESS|FAILURE reason=... elapsed_ms=...}。
 * <b>退出码</b>：
 * <table border="1">
 *   <tr><td>0</td><td>成功</td></tr>
 *   <tr><td>2</td><td>attach / loadAgent 失败（看最后一行 reason=）</td></tr>
 *   <tr><td>3</td><td>参数不合法</td></tr>
 *   <tr><td>4</td><td>拒绝自我 attach（pid 就是本进程）</td></tr>
 *   <tr><td>5</td><td>路径不是纯 ASCII，或 agent jar 不存在</td></tr>
 *   <tr><td>6</td><td>这个 JVM 没有 jdk.attach 模块</td></tr>
 * </table>
 *
 * <h2>🔴 输出一律纯 ASCII</h2>
 * helper 是子 JVM，它的 stdout 编码受目标机器环境影响（实测 {@code sun.jnu.encoding=GBK}），
 * 而游戏侧会用 UTF-8 解它 —— 所以这里只打 ASCII 字段，避免出现"日志里一串问号"这种二次故障。
 */
public final class HotfixHelper {

    /** 统一前缀。⚠️ 与 {@link HotfixBootstrap#PREFIX} 是同一个字面量，故意重复（本类不能引用那个类）。 */
    public static final String PREFIX = "[SHANHAI-HOTFIX]";

    /** 完整类名（给游戏侧拼命令用；同样只在这里出现一次字面量）。 */
    public static final String CLASS_NAME = "com.shanhai.add.hotfix.HotfixHelper";

    private HotfixHelper() {
    }

    public static void main(String[] args) {
        final long t0 = System.currentTimeMillis();
        // ⚠️ 参数不足时也要给出"机器可判"的一行，不能只是 System.exit。
        if (args == null || args.length < 2) {
            line("helper usage pid agentJar [evidenceDir]");
            fail(t0, 3, "bad_args", "argc=" + (args == null ? 0 : args.length));
            return;
        }
        final String pidText = trim(args[0]);
        final String agentJar = args[1] == null ? "" : args[1];
        final String evidenceDir = args.length > 2 && args[2] != null ? args[2] : "";

        line("helper start pid=" + pidText + " jar=" + agentJar + " evidence=" + evidenceDir
                + " java=" + System.getProperty("java.version")
                + " jnu_encoding=" + System.getProperty("sun.jnu.encoding"));

        // ---- ① 参数
        final long pid = parsePid(pidText);
        if (pid <= 0L) {
            fail(t0, 3, "bad_pid", "pid=" + pidText);
            return;
        }
        if (pid == currentPid()) {
            // 实测：JVM 会抛 "Can not attach to current VM"。这里提前挡掉，给出比异常更清楚的原因。
            fail(t0, 4, "self_attach_not_allowed", "pid=" + pid);
            return;
        }
        // ---- ② 路径（🔴 必须纯 ASCII：这是那条"中文被截断成 ???"事故的防线）
        if (!isAscii(agentJar)) {
            fail(t0, 5, "agent_jar_not_ascii", "jar=" + escape(agentJar));
            return;
        }
        final File jarFile = new File(agentJar);
        if (!jarFile.isFile()) {
            fail(t0, 5, "agent_jar_missing", "jar=" + agentJar);
            return;
        }
        if (!evidenceDir.isEmpty() && !isAscii(evidenceDir)) {
            fail(t0, 5, "evidence_dir_not_ascii", "dir=" + escape(evidenceDir));
            return;
        }

        // ---- ③ 反射拿 jdk.attach
        final Class<?> vmClass;
        try {
            vmClass = Class.forName("com.sun.tools.attach.VirtualMachine");
        } catch (Throwable t) {
            fail(t0, 6, "jdk_attach_unavailable", "err=" + t);
            return;
        }

        // ---- ④ attach（诊断用：先把 attach 列表打出来，pid 取错时一眼能看出来）
        try {
            listTargets(vmClass);
        } catch (Throwable t) {
            line("helper list_targets_failed err=" + t);
        }

        Object vm = null;
        try {
            line("helper attach_calling");
            vm = vmClass.getMethod("attach", String.class).invoke(null, pidText);
            line("helper attached");
        } catch (Throwable t) {
            fail(t0, 2, "attach_failed", describe(t));
            return;
        }

        // ---- ⑤ loadAgent（⚠️ 它在目标 JVM 里【同步】执行 agentmain：返回时 holder 早已写完，
        //         所以游戏侧在 helper 退出后再读 holder 是有序的，不需要额外握手文件）
        try {
            line("helper load_agent_calling");
            vmClass.getMethod("loadAgent", String.class, String.class)
                    .invoke(vm, jarFile.getAbsolutePath(), evidenceDir);
            line("helper load_agent_done");
        } catch (Throwable t) {
            fail(t0, 2, "load_agent_failed", describe(t));
            detachQuietly(vmClass, vm);
            return;
        }

        // ---- ⑥ detach + 成功
        detachQuietly(vmClass, vm);
        line("helper RESULT=SUCCESS elapsed_ms=" + (System.currentTimeMillis() - t0));
        System.out.flush();
        System.exit(0);
    }

    // ------------------------------------------------------------------ 内部

    /** 打印 attach 列表（最多 12 条）：pid 取错 / 目标不是游戏时，这行是第一手证据。 */
    private static void listTargets(Class<?> vmClass) throws Exception {
        final Object list = vmClass.getMethod("list").invoke(null);
        if (!(list instanceof List)) {
            line("helper list_targets=unexpected_type");
            return;
        }
        final List<?> vms = (List<?>) list;
        line("helper list_targets size=" + vms.size());
        final int n = Math.min(vms.size(), 12);
        for (int i = 0; i < n; i++) {
            final Object d = vms.get(i);
            if (d == null) {
                continue;
            }
            final String id = String.valueOf(d.getClass().getMethod("id").invoke(d));
            final String display = String.valueOf(d.getClass().getMethod("displayName").invoke(d));
            line("helper   target id=" + id + " display=" + truncate(display, 160));
        }
    }

    private static void detachQuietly(Class<?> vmClass, Object vm) {
        if (vm == null) {
            return;
        }
        try {
            vmClass.getMethod("detach").invoke(vm);
            line("helper detached");
        } catch (Throwable t) {
            line("helper detach_failed err=" + t);
        }
    }

    /** 把反射异常拆成"真正的那一层"，否则日志里永远只有 {@code InvocationTargetException}。 */
    private static String describe(Throwable t) {
        Throwable root = t;
        while (root instanceof InvocationTargetException && root.getCause() != null) {
            root = root.getCause();
        }
        final StringBuilder sb = new StringBuilder(256);
        sb.append("exception=").append(root.getClass().getName())
                .append(" message=<").append(escape(String.valueOf(root.getMessage()))).append('>');
        final StackTraceElement[] st = root.getStackTrace();
        final int n = Math.min(4, st.length);
        for (int i = 0; i < n; i++) {
            sb.append(" at=").append(st[i]);
        }
        if (root != t) {
            sb.append(" wrapped_in=").append(t.getClass().getName());
        }
        return sb.toString();
    }

    private static void fail(long t0, int exitCode, String reason, String detail) {
        line("helper RESULT=FAILURE reason=" + reason + " " + detail
                + " elapsed_ms=" + (System.currentTimeMillis() - t0));
        System.out.flush();
        System.exit(exitCode);
    }

    private static void line(String s) {
        System.out.println(PREFIX + " " + s);
        System.out.flush();
    }

    private static long parsePid(String text) {
        if (text == null || text.isEmpty()) {
            return -1L;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private static long currentPid() {
        try {
            return ProcessHandle.current().pid();
        } catch (Throwable t) {
            return -1L;
        }
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** 非 ASCII 字符换成 {@code ?}：保证本类所有输出都是纯 ASCII（见类注释）。 */
    private static String escape(String s) {
        if (s == null) {
            return "null";
        }
        final StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            sb.append(c > 0x7F ? '?' : c);
        }
        return sb.toString();
    }

    /** 纯 ASCII 判定（{@link HotfixPaths#isAscii} 的本地副本：helper 的 classpath 上没有那个类）。 */
    static boolean isAscii(String s) {
        if (s == null) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 0x7F) {
                return false;
            }
        }
        return true;
    }
}
