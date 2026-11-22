package com.shanhai.add.hotfix;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * hotfix 子系统的 <b>ASCII 路径</b>工具 —— 整条链路上唯一认的路径口径。
 *
 * <h2>🔴 为什么一切都必须是 ASCII</h2>
 * 这条不是洁癖，是实测事故（{@code temp/u1-attach\报告.md} §3.2 有逐字原文）：
 * 把中文路径经 <b>agent 的 options 通道</b>传给目标 JVM 时，字符串会被<b>截断 + 变成 {@code ???}</b>
 * <pre>
 *   agentmain called args=<本机中文目录>\<模组目录>\temp/u1-attach\smoke-agent-evidenc
 *   evidence-write-failed: java.io.FileNotFoundException: …\smoke-agent-evidenc (系统找不到指定的路径。)
 * </pre>
 * 注意尾部字符被吃掉了 —— 典型的"字符数 / 字节数口径不一致"（JVM 侧 {@code file.encoding=UTF-8}
 * 而 {@code sun.jnu.encoding=GBK}）。截断后的路径既不是合法路径、也不是合法 {@code agentArgs} 内容，
 * 最终表现为 {@code AgentInitializationException} 或"agent 跑起来了但证据写不出来"。
 * <p>⇒ 因此：<b>agent jar、helper 的 class 目录、证据文件，一律放在纯 ASCII 的
 * {@code %TEMP%\shanhai_hotfix\} 下</b>。本类负责选出这样的目录、并把"不是 ASCII"这件事
 * 变成一条<b>可读的失败原因</b>（而不是让它在下游变成乱码异常）。
 *
 * <h2>为什么本类只碰 {@code java.*}</h2>
 * 它被 {@link HotfixAgentJar} / {@link HotfixLauncher} 使用，而那两个类要能被<b>脱离游戏</b>的自检
 * 直接跑（见 {@code temp\hotfix\报告.md} 的离线自检一节）⇒ 本类不许引用任何 MC/Forge/项目类型。
 */
public final class HotfixPaths {

    /** 工作目录名。落地形态：{@code %TEMP%\shanhai_hotfix\agent.jar}、{@code \helper\}、{@code \evidence\}。 */
    public static final String WORK_DIR_NAME = "shanhai_hotfix";

    private HotfixPaths() {
    }

    /**
     * 纯 ASCII 判据：每个 {@code char} 都 ≤ {@code 0x7F}。
     *
     * <p>判的是 <b>Java 字符串</b>而不是编码后的字节：上面那次事故的本质就是"某处按字节算长度"，
     * 所以这里坚持"只要出现过 &gt; 0x7F 的字符就否"这条与平台无关的口径。
     *
     * @param s 待判字符串（{@code null} 视为非 ASCII）
     */
    public static boolean isAscii(String s) {
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

    /**
     * hotfix 工作目录（已创建 + 已验证可写 + 已通过 ASCII 校验）。
     *
     * <p>候选顺序（第一个可用者胜）：
     * <ol>
     *   <li>{@code %TEMP%\shanhai_hotfix} —— 正常情况。{@code java.io.tmpdir} 在 Windows 上通常是
     *       {@code C:\Users\<name>\AppData\Local\Temp\}；用户名含中文时它就不是 ASCII ⇒ 跳过。</li>
     *   <li>{@code %ProgramData%\shanhai_hotfix} —— 用户名中文但 ProgramData 是 ASCII 时的退路。</li>
     *   <li>{@code %SystemRoot%\Temp\shanhai_hotfix}</li>
     *   <li>{@code C:\shanhai_hotfix} —— 最后兜底（可能没权限 ⇒ 会被 try 掉）。</li>
     * </ol>
     * 全部失败时抛 {@link IOException}，里面的 {@code tried:...} 列出每个候选的失败原因 ——
     * 这条信息会原样进日志，免得"预热没成"变成一个查不动的黑盒。
     *
     * @return 绝对路径、纯 ASCII、已存在且可写
     */
    public static Path workDir() throws IOException {
        final String tmp = System.getProperty("java.io.tmpdir", "");
        final String[] candidates = {
                join(tmp, WORK_DIR_NAME),
                join(System.getenv("ProgramData"), WORK_DIR_NAME),
                join(System.getenv("SystemRoot"), "Temp", WORK_DIR_NAME),
                "C:\\" + WORK_DIR_NAME,
        };
        return pickAsciiWritable(candidates);
    }

    /** {@link #workDir()} 的可注入版本：离线自检用它验证"非 ASCII 候选会被跳过"这条规则。 */
    public static Path pickAsciiWritable(String[] candidates) throws IOException {
        final StringBuilder tried = new StringBuilder();
        IOException last = null;
        for (int i = 0; candidates != null && i < candidates.length; i++) {
            final String raw = candidates[i];
            if (raw == null || raw.isEmpty()) {
                continue;
            }
            if (!isAscii(raw)) {
                tried.append(raw).append("(non-ascii) ");
                continue;
            }
            try {
                final Path dir = Paths.get(raw).toAbsolutePath().normalize();
                Files.createDirectories(dir);
                probeWritable(dir);
                return dir;
            } catch (Throwable t) {
                last = new IOException(raw + " -> " + t, t);
                tried.append(raw).append('(').append(t.getClass().getSimpleName()).append(") ");
            }
        }
        throw new IOException("no usable ascii work dir; tried: " + tried, last);
    }

    /**
     * 校验一个目录路径：非空、纯 ASCII、能建出来。
     *
     * @param raw  候选目录（绝对路径）
     * @param what 用途名（进异常文本，必须是 ASCII，例如 {@code evidence}）
     * @throws IOException 路径为空 / 非 ASCII / 建不出来
     */
    public static Path ensureAsciiDir(String raw, String what) throws IOException {
        if (raw == null || raw.isEmpty()) {
            throw new IOException(what + "_dir_empty");
        }
        if (!isAscii(raw)) {
            // ⚠️ 刻意不带中文：这条路本身就是为了绕开编码问题，别在错误信息里再引入一次。
            throw new IOException(what + "_dir_not_ascii");
        }
        final Path dir = Paths.get(raw).toAbsolutePath().normalize();
        Files.createDirectories(dir);
        return dir;
    }

    /**
     * 拼接路径（{@code null}/空段跳过，段间用平台分隔符）。
     *
     * @return 全部段落都空时返回 {@code null}（调用方按"没这个候选"处理）
     */
    public static String join(String base, String... more) {
        final StringBuilder sb = new StringBuilder();
        if (base != null && !base.isEmpty()) {
            sb.append(base);
        }
        for (int i = 0; more != null && i < more.length; i++) {
            final String seg = more[i];
            if (seg == null || seg.isEmpty()) {
                continue;
            }
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) != File.separatorChar) {
                sb.append(File.separatorChar);
            }
            sb.append(seg);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** 真写一个探针文件再删掉 —— "目录存在"不等于"能写"（只读盘 / ACL 都是这么坑人的）。 */
    private static void probeWritable(Path dir) throws IOException {
        final Path probe = dir.resolve(".shanhai-hotfix-probe");
        Files.write(probe, new byte[]{'o', 'k'}, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        Files.deleteIfExists(probe);
    }
}
