package com.shanhai.add.hotfix;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * 把 agent jar 与 helper 的 class 目录<b>在运行期造出来，落到纯 ASCII 的 {@code %TEMP%\shanhai_hotfix\}</b>。
 *
 * <h2>agent jar 的来源：为什么选"从我们自己的 jar 里抽一份独立的小 jar"（方案 b）</h2>
 * 需求给了两个选择：
 * <ul>
 *   <li><b>(a)</b> 把 agent 类编进 {@code shanhai_add} 本体 jar、MANIFEST 加 {@code Agent-Class}，
 *       运行期整份复制到 {@code %TEMP%\shanhai_hotfix\agent.jar}；</li>
 *   <li><b>(b)</b> 从我们的 jar 里抽出<b>独立</b>的一份小 agent jar（本类采用）。</li>
 * </ul>
 * 选 (b) 的三条理由（都与"能不能离线证明它对"有关）：
 * <ol>
 *   <li><b>不赌 reobf/jarjar 会保住 MANIFEST 属性。</b>(a) 依赖 {@code build.gradle} 里写的
 *       {@code Agent-Class} 在 {@code reobfJar}（ForgeGradle 会重新打包一次 jar）之后仍然存在。
 *       这台机器上<b>不许构建</b> ⇒ 这条我<b>没有</b>实测过，只能赌。而 (b) 的 MANIFEST 是本类在
 *       运行期用 {@link Manifest} 现写的，写完立刻回读校验（{@link #verifyAgentJar}）——
 *       属性在不在、{@code Agent-Class} 对不对，都是运行期可判的。</li>
 *   <li><b>不把整个 mod jar 塞进 system classpath。</b>agent jar 会被
 *       {@code Instrumentation.appendToClassPathForInstrumentation} 追加到目标 JVM 的
 *       <b>system class loader</b> 上。整份 mod jar 里有上千个引用 MC 的类、还有
 *       {@code META-INF/services} 与 Mixin 配置 ⇒ 让它们对 system loader 可见，是白送的额外风险面；
 *       独立小 jar 里只有 {@code HotfixAgent} + {@code HotfixNoopTransformer} 两个类。</li>
 *   <li><b>不产生"同名类落在 system loader 上"的第二份 holder。</b>整份 jar 被追加后，
 *       {@code com.shanhai.add.hotfix.HotfixHolder} 就有了两份（system loader 一份、游戏 loader 一份），
 *       agent 按名字找时必须挑对；独立 jar 里根本没有 holder，这条风险直接消失
 *       （{@link HotfixAgent#findHolderClass} 里那条"优先非 agent loader"的规则仍保留，作为第二道保险）。</li>
 * </ol>
 * 代价（如实记下）：agent jar 是"运行期现造的"，所以它<b>本身</b>也要过一次
 * {@link #verifyAgentJar} 回读校验；两个类名与 jar 条目路径的一致性由 {@link #SANITY_ANCHORS}
 * 在<b>编译期</b>钉住（见该字段注释）。
 *
 * <h2>为什么 helper 也要抽出来</h2>
 * 游戏 jar 在中文路径 {@code 构建\shanhai重构\} 下，而 helper 的启动命令要求
 * {@code -cp <ASCII 路径>}（同一条"非 ASCII 会烂在某一环"的教训）。抽出来之后 helper 的
 * classpath 就是纯 ASCII 的一个目录，且<b>只有一个 class 文件</b>（helper 刻意写成零内部类，见
 * {@link HotfixHelper} 类注释）。
 */
public final class HotfixAgentJar {

    /** agent 入口类（MANIFEST 的 {@code Agent-Class} 必须是它）。 */
    public static final String AGENT_CLASS = "com.shanhai.add.hotfix.HotfixAgent";

    /** helper 主类（游戏侧拼命令行用）。 */
    public static final String HELPER_CLASS = "com.shanhai.add.hotfix.HotfixHelper";

    /**
     * 抽进 agent jar 的条目（<b>固定清单</b>，改 agent 的类结构时必须同步改这里）。
     *
     * <p>⚠️ 这也是 {@link HotfixNoopTransformer} 必须是<b>顶层类</b>、不能写成内部类的原因：
     * 内部类的编译期名字（{@code HotfixAgent$1}）无法在这里静态列出。
     */
    private static final String[] AGENT_ENTRIES = {
            "com/shanhai/add/hotfix/HotfixAgent.class",
            "com/shanhai/add/hotfix/HotfixNoopTransformer.class",
    };

    /** 抽成独立 class 文件的 helper（单个，无内部类）。 */
    private static final String HELPER_ENTRY = "com/shanhai/add/hotfix/HotfixHelper.class";

    /**
     * 编译期锚点：类字面量 ↔ 上面那些字符串路径。
     *
     * <p>它不是"运行期要用"，而是让<b>类改名/搬家</b>在 {@code javac} 阶段就红 —— 否则失败形态是
     * "运行期抽不到 class ⇒ 预热降级"，而日志里只会写 {@code agent_class_resource_missing}，
     * 排查要多绕一大圈。
     *
     * <p>安全：这三个类都是纯 JDK 类（不碰 MC/Forge），加载它们不会牵连游戏类。
     */
    private static final Class<?>[] SANITY_ANCHORS = {
            HotfixAgent.class,
            HotfixNoopTransformer.class,
            HotfixHelper.class,
    };

    /** MANIFEST 里是否声明了"可 retransform"（JVM 据此决定 {@code isRetransformClassesSupported()}）。 */
    public static final String ATTR_CAN_RETRANSFORM = "Can-Retransform-Classes";

    /** 同上，redefine 版（我们只用 retransform，声明它只是为了与参考实现一致、且不影响任何东西）。 */
    public static final String ATTR_CAN_REDEFINE = "Can-Redefine-Classes";

    private HotfixAgentJar() {
    }

    /** 造好的三样东西 + 工作目录（全部是<b>纯 ASCII 绝对路径</b>）。 */
    public static final class Prepared {

        public final String workDir;
        public final String agentJar;
        public final String helperDir;
        public final String evidenceDir;

        Prepared(String workDir, String agentJar, String helperDir, String evidenceDir) {
            this.workDir = workDir;
            this.agentJar = agentJar;
            this.helperDir = helperDir;
            this.evidenceDir = evidenceDir;
        }

        @Override
        public String toString() {
            return "work_dir=" + workDir + " agent_jar=" + agentJar + " helper_dir=" + helperDir
                    + " evidence_dir=" + evidenceDir;
        }
    }

    /**
     * 造 agent jar + 抽 helper + 建证据目录。幂等（同名文件覆盖；被占用时退到带时间戳的名字）。
     *
     * @throws IOException 任一步失败（调用方负责把它变成一行 {@code state=degraded reason=...}）
     */
    public static Prepared prepare() throws IOException {
        // 字符串路径 ↔ 真实类名的一致性（编译期锚点在此"兑现"：类改名会先在 javac 里红，
        // 万一有人改了清单而没改类，也会在第一次预热时给出可读原因，而不是"抽不到资源"）
        if (SANITY_ANCHORS.length != AGENT_ENTRIES.length + 1) {
            throw new IOException("sanity_anchor_count anchors=" + SANITY_ANCHORS.length
                    + " entries=" + AGENT_ENTRIES.length);
        }
        for (int i = 0; i < AGENT_ENTRIES.length; i++) {
            final String expected = SANITY_ANCHORS[i].getName().replace('.', '/') + ".class";
            if (!expected.equals(AGENT_ENTRIES[i])) {
                throw new IOException("agent_entries_out_of_sync entry=" + AGENT_ENTRIES[i]
                        + " expected=" + expected);
            }
        }
        if (!(HotfixHelper.class.getName().replace('.', '/') + ".class").equals(HELPER_ENTRY)) {
            throw new IOException("helper_entry_out_of_sync entry=" + HELPER_ENTRY);
        }
        final Path work = HotfixPaths.workDir();
        final Path agentJar = writeAgentJar(work);
        verifyAgentJar(agentJar);
        final Path helperDir = extractHelper(work);
        final Path evidenceDir = HotfixPaths.ensureAsciiDir(work.resolve("evidence").toString(), "evidence");
        final Prepared prepared = new Prepared(ascii(work), ascii(agentJar), ascii(helperDir), ascii(evidenceDir));
        // 最后一道闸：三个路径必须全是 ASCII（任何一个是，就说明上游某步走了非 ASCII 分支）
        if (!HotfixPaths.isAscii(prepared.agentJar) || !HotfixPaths.isAscii(prepared.helperDir)
                || !HotfixPaths.isAscii(prepared.evidenceDir)) {
            throw new IOException("prepared_path_not_ascii " + prepared);
        }
        return prepared;
    }

    // ------------------------------------------------------------------ agent jar

    /**
     * 写 agent jar：MANIFEST 由我们现写，里面只有 {@link HotfixAgent} 与 {@link HotfixNoopTransformer}。
     *
     * <p>为什么先试 {@code agent.jar}、失败再试带时间戳的名字：同一进程里第二次预热时，上一次
     * 那份 jar 可能正被 JVM 持有（{@code appendToClassPathForInstrumentation}）⇒ Windows 上可能拒绝覆盖。
     * 换一个文件名就能继续，代价只是 {@code %TEMP%} 下多一个小文件。
     */
    private static Path writeAgentJar(Path work) throws IOException {
        final Manifest manifest = new Manifest();
        final Attributes main = manifest.getMainAttributes();
        main.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        main.put(new Attributes.Name("Agent-Class"), AGENT_CLASS);
        main.put(new Attributes.Name(ATTR_CAN_RETRANSFORM), "true");
        main.put(new Attributes.Name(ATTR_CAN_REDEFINE), "true");

        final byte[][] payload = new byte[AGENT_ENTRIES.length][];
        for (int i = 0; i < AGENT_ENTRIES.length; i++) {
            payload[i] = readResource(AGENT_ENTRIES[i]);
        }

        IOException last = null;
        final String[] names = {"agent.jar", "agent-" + System.currentTimeMillis() + ".jar"};
        for (int n = 0; n < names.length; n++) {
            final Path jar = work.resolve(names[n]);
            OutputStream os = null;
            JarOutputStream jos = null;
            try {
                os = new java.io.BufferedOutputStream(Files.newOutputStream(jar,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE));
                jos = new JarOutputStream(os, manifest);
                for (int i = 0; i < AGENT_ENTRIES.length; i++) {
                    jos.putNextEntry(new JarEntry(AGENT_ENTRIES[i]));
                    jos.write(payload[i]);
                    jos.closeEntry();
                }
                jos.finish();
                jos.flush();
                return jar;
            } catch (Throwable t) {
                last = new IOException("write " + jar + " failed: " + t, t);
            } finally {
                closeQuietly(jos);
                closeQuietly(os);
            }
        }
        throw last != null ? last : new IOException("write_agent_jar_failed");
    }

    /**
     * 回读校验：{@code Agent-Class} / {@code Can-Retransform-Classes} / 两个类条目都在。
     *
     * <p>没有这一步，"MANIFEST 写对了没有"只能等 {@code loadAgent} 在目标 JVM 里报
     * {@code AgentInitializationException} 才知道 —— 那条错误信息跨进程、还经过 attach 通道，
     * 读起来非常费劲。宁可在这里自己先红一次，错误落在我们自己的日志里。
     */
    static void verifyAgentJar(Path jar) throws IOException {
        JarFile jf = null;
        try {
            jf = new JarFile(jar.toFile());
            final Manifest mf = jf.getManifest();
            if (mf == null) {
                throw new IOException("agent_jar_no_manifest path=" + jar);
            }
            final String agentClass = mf.getMainAttributes().getValue("Agent-Class");
            if (!AGENT_CLASS.equals(agentClass)) {
                throw new IOException("agent_jar_bad_agent_class got=<" + agentClass + "> want=" + AGENT_CLASS);
            }
            final String canRetransform = mf.getMainAttributes().getValue(ATTR_CAN_RETRANSFORM);
            if (!"true".equalsIgnoreCase(canRetransform)) {
                throw new IOException("agent_jar_bad_can_retransform got=<" + canRetransform + ">");
            }
            for (int i = 0; i < AGENT_ENTRIES.length; i++) {
                final JarEntry entry = jf.getJarEntry(AGENT_ENTRIES[i]);
                if (entry == null || entry.getSize() == 0L) {
                    throw new IOException("agent_jar_missing_entry " + AGENT_ENTRIES[i]);
                }
            }
        } finally {
            closeQuietly(jf);
        }
    }

    // ------------------------------------------------------------------ helper

    /**
     * 把 {@link HotfixHelper} 写成一个单独 class 文件：{@code <work>/helper/com/shanhai/add/hotfix/HotfixHelper.class}。
     *
     * @return helper 的 classpath 根（{@code <work>/helper}），已通过 ASCII 校验
     */
    private static Path extractHelper(Path work) throws IOException {
        final Path root = Files.createDirectories(work.resolve("helper"));
        final Path target = root.resolve(HELPER_ENTRY.replace('/', File.separatorChar));
        final Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        final byte[] bytes = readResource(HELPER_ENTRY);
        Files.write(target, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        final long written = Files.size(target);
        if (written != bytes.length) {
            throw new IOException("helper_class_short_write written=" + written + " expected=" + bytes.length);
        }
        return root;
    }

    // ------------------------------------------------------------------ 小工具

    /**
     * 从<b>我们自己的 class loader</b> 里读一个 class 的字节。
     *
     * <p>为什么不直接用 {@code HotfixAgent.class.getResourceAsStream("HotfixAgent.class")}：
     * 那会"因为要取类字面量而先把类加载进来"。这里走资源路径，避免在游戏侧多一份
     * 注定用不到的 {@code HotfixAgent} 副本（它在 agent 侧用的是 system loader 那一份）。
     */
    private static byte[] readResource(String entry) throws IOException {
        InputStream in = null;
        try {
            in = HotfixAgentJar.class.getResourceAsStream("/" + entry);
            if (in == null) {
                throw new IOException("class_resource_missing " + entry);
            }
            final ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
            final byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            final byte[] bytes = out.toByteArray();
            if (bytes.length == 0) {
                throw new IOException("class_resource_empty " + entry);
            }
            return bytes;
        } finally {
            closeQuietly(in);
        }
    }

    private static String ascii(Path p) {
        return p.toAbsolutePath().toString();
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Throwable ignored) {
                // ignore
            }
        }
    }
}
