package com.shanhai.add.hotfix;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * hotfix 子系统的 <b>agent 侧</b>：被 attach 到游戏 JVM 之后、由 {@code Attach Listener} 线程执行的
 * {@code agentmain}。
 *
 * <h2>它为什么长得这么"原始"</h2>
 * <ol>
 *   <li><b>不能引用任何 MC/Forge 类型。</b>实测（{@code temp/u1-attach\报告.md} §4.4）：agent 与
 *       {@code system class loader} 是同一个 {@code AppClassLoader}，而 {@code MinecraftServer}
 *       等游戏类在 {@code cpw.mods.modlauncher.TransformingClassLoader} 上 ⇒ agent 里写
 *       {@code (MinecraftServer) x} 会 {@code NoClassDefFoundError}。要拿游戏类只能
 *       {@code getAllLoadedClasses()} 按名字找。</li>
 *   <li><b>不能引用本项目里任何别的类（除了 {@link HotfixNoopTransformer}）。</b>本类是被
 *       {@link HotfixAgentJar} 抽出来放进一个<b>只有两三个条目</b>的独立 agent jar 里加载的
 *       —— 那个 jar 里没有 {@code HotfixPaths}、没有 {@code HotfixHolder}、更没有任何项目代码。
 *       ⇒ ASCII 判定、路径拼接、证据落盘这些"小工具"在这里是<b>故意重复写一遍</b>的，
 *       不是抄漏了重构。</li>
 * </ol>
 *
 * <h2>它做三件事（顺序即失败影响面，从小到大的都在后面）</h2>
 * <pre>
 *   ① 找 holder：getAllLoadedClasses() 里按类名找 com.shanhai.add.hotfix.HotfixHolder
 *                （同名多份时优先"不是 agent 自己那个 loader"的，那才是游戏侧真的那份）
 *   ② 交接    ：反射调 holder.acceptInstrumentation(inst)  —— 拿到就立刻交，别握在 agent 手里
 *   ③ 体检    ：对我们自己的 JDK 类 java.lang.StringBuilder 做一次 no-op retransform
 *                （注册 {@link HotfixNoopTransformer} → retransform → 摘掉）
 *                成功 ⇒ state=ready；失败 ⇒ state=degraded reason=... policy=record_only
 *                结果再反射调 holder.publishState(...) 交回游戏侧
 * </pre>
 *
 * <h2>失败一律降级，绝不牵动游戏</h2>
 * 任何一步 {@link Throwable} 都只落一行日志（{@code state=degraded reason=... policy=record_only}），
 * 然后<b>正常返回</b>。<b>只记录、不反制、不重试</b>：agentmain 抛异常只会污染服务端控制台，
 * 一个已经启动的世界没有任何理由因为"热更新地基没搭好"而受影响。
 *
 * <h2>输出通道（三条，冗余是故意的）</h2>
 * <ol>
 *   <li>{@code System.out}/{@code System.err} —— 游戏里会被 Minecraft 的 {@code Log4jPrintStream}
 *       抓进 {@code logs\latest.log} 的 {@code [STDOUT/]}，实测可用；</li>
 *   <li>证据文件 —— {@code agentArgs}（游戏侧传进来的证据目录，纯 ASCII）下的 {@code agent-<pid>.log}；</li>
 *   <li>{@code java.io.tmpdir} 下的同款文件（agentArgs 不是纯 ASCII 时的退路）。</li>
 * </ol>
 * 🔴 <b>所有输出一律保持纯 ASCII</b>：agent 跑在目标 JVM 里，其 stdout 编码不受我们控制
 * （实测 {@code sun.jnu.encoding=GBK}），中文在中间某一环被吃掉过，没必要再赌一次。
 */
public final class HotfixAgent {

    /** 统一前缀（与游戏侧 {@link HotfixBootstrap#PREFIX} 同一个字面量；此类不能引用那个常量，见类注释 2）。 */
    public static final String PREFIX = "[SHANHAI-HOTFIX]";

    /** 游戏侧 holder 的类名（契约，见 {@link HotfixHolder} 类注释 3）。 */
    public static final String HOLDER_CLASS = "com.shanhai.add.hotfix.HotfixHolder";

    /** holder 上接收 {@link Instrumentation} 的方法名。 */
    public static final String ACCEPT_METHOD = "acceptInstrumentation";

    /** holder 上接收体检结论的方法名。 */
    public static final String PUBLISH_METHOD = "publishState";

    /** 体检用的 JDK 类：与游戏无关、不会被任何 mixin 碰、且实测可 retransform。 */
    public static final String HEALTH_CLASS = "java.lang.StringBuilder";

    private HotfixAgent() {
    }

    /**
     * JVM 入口。{@code args} = 游戏侧传进来的<b>证据目录</b>（纯 ASCII；也可能是空串）。
     *
     * <p>⚠️ 这个方法的<b>签名</b>由 JVM 规定（{@code String} + {@link Instrumentation}），不能改。
     */
    public static void agentmain(String args, Instrumentation inst) {
        final long t0 = System.currentTimeMillis();
        final List<String> out = new ArrayList<String>(32);
        out.add("agent enter pid=" + pid() + " args=<" + nullToEmpty(args) + "> java="
                + System.getProperty("java.version") + " thread=" + Thread.currentThread().getName());
        add(out, "agent args_ascii=" + isAscii(args) + " jnu_encoding=" + System.getProperty("sun.jnu.encoding"));

        if (inst == null) {
            add(out, "state=degraded reason=instrumentation_null policy=record_only note=只记录不反制");
        } else {
            Class<?> holder = null;
            try {
                add(out, "inst loaded_classes=" + inst.getAllLoadedClasses().length
                        + " is_retransform_supported=" + inst.isRetransformClassesSupported()
                        + " is_redefine_supported=" + inst.isRedefineClassesSupported());
                holder = handOver(inst, out);
            } catch (Throwable t) {
                add(out, "state=degraded reason=handover_exception exception=" + t + " policy=record_only");
                addFrames(out, t, 5);
            }
            try {
                final String health = healthCheck(inst);
                add(out, health);
                if (holder != null) {
                    publishState(holder, health, out);
                } else {
                    // 总体结论：这一行的 state 才是"本次预热成不成"的最终判据。
                    // （health 行只是单项读数：即使没找到 holder，retransform 管道本身也可能是好的。）
                    add(out, "state=degraded reason=holder_not_found holder_missing=true"
                            + " policy=record_only note=只记录不反制");
                }
            } catch (Throwable t) {
                add(out, "state=degraded reason=health_exception exception=" + t + " policy=record_only");
                addFrames(out, t, 5);
            }
        }
        add(out, "agent done elapsed_ms=" + (System.currentTimeMillis() - t0));
        emit(args, out);
    }

    // ------------------------------------------------------------------ ① + ② 找 holder 并交接

    /**
     * 在已加载类里按名字找 {@link HotfixHolder} 并交接 {@link Instrumentation}。
     *
     * <p>找不到不是异常：<b>可能只是本类还没被游戏侧加载</b>（这就是游戏侧在拉起 helper 之前
     * 必须先 {@link HotfixHolder#touch()} 的原因）。这里做有限次重试（100ms × 30 ≈ 3s），
     * 因为 agentmain 跑在 {@code Attach Listener} 线程上，等一等不会卡游戏主线程。
     *
     * @return 找到并<b>成功交接</b>的 holder 类；失败 {@code null}
     */
    private static Class<?> handOver(Instrumentation inst, List<String> out) {
        final ClassLoader agentLoader = HotfixAgent.class.getClassLoader();
        for (int attempt = 1; attempt <= 30; attempt++) {
            final Class<?>[] all;
            try {
                all = inst.getAllLoadedClasses();
            } catch (Throwable t) {
                add(out, "holder lookup_failed=true attempt=" + attempt + " exception=" + t);
                return null;
            }
            final int candidates = countHolderCandidates(all);
            final Class<?> holder = findHolderClass(all, agentLoader);
            if (holder == null) {
                if (attempt == 1) {
                    add(out, "holder found=false candidates=" + candidates
                            + " lookup=" + (candidates == 0 ? "class_not_loaded" : "no_accept_method")
                            + " agent_loader=" + describeLoader(agentLoader));
                }
                sleepQuietly(100L);
                continue;
            }
            final ClassLoader holderLoader = holder.getClassLoader();
            add(out, "holder found=true candidates=" + candidates + " attempt=" + attempt
                    + " loader=" + describeLoader(holderLoader)
                    + " same_as_agent_loader=" + (holderLoader == agentLoader));
            boolean accepted;
            try {
                final Object r = holder.getMethod(ACCEPT_METHOD, Instrumentation.class).invoke(null, inst);
                accepted = Boolean.TRUE.equals(r);
            } catch (Throwable t) {
                add(out, "holder accept=false reason=invoke_failed exception=" + t);
                addFrames(out, t, 5);
                return null;
            }
            add(out, "holder accept=" + accepted);
            if (!accepted) {
                return null;
            }
            return holder;
        }
        add(out, "holder found=false attempts=30 waited_ms=3000 reason=timeout_waiting_for_class"
                + " hint=game_side_must_load_holder_before_attach policy=record_only");
        return null;
    }

    /**
     * 按类名在 {@code all} 里挑一个 holder。
     *
     * <p>排序规则：<b>优先"不是 agent 自己那个 class loader"的那一份</b>。理由：同名类可能在两个
     * loader 里各存在一份（例如开发环境把项目输出挂在启动 classpath 上），把引用写进错误的那一份，
     * 游戏侧读到的永远是 {@code null} —— 而这种失败在日志上长得跟"agent 没跑"一模一样。
     * 只有全部候选都在 agent 自己的 loader 上时才退回那一份（好过什么都没有）。
     *
     * <p>用 {@code public static} 是为了让离线自检能直接对这条规则做正/负对照
     * （含"两份同名类、优先取得非 agent loader 那份"）。
     *
     * @param all         已加载类快照
     * @param agentLoader agent 自己的 class loader（通常就是 system class loader）
     * @return 可用的 holder 类；没有则 {@code null}
     */
    public static Class<?> findHolderClass(Class<?>[] all, ClassLoader agentLoader) {
        if (all == null) {
            return null;
        }
        Class<?> fallback = null;
        for (int i = 0; i < all.length; i++) {
            final Class<?> c = all[i];
            if (c == null || !HOLDER_CLASS.equals(c.getName())) {
                continue;
            }
            if (!hasAcceptMethod(c)) {
                continue;
            }
            if (c.getClassLoader() != agentLoader) {
                return c;                      // 游戏侧那一份，优先
            }
            if (fallback == null) {
                fallback = c;                  // 只有 agent 自己 loader 上的那一份时才退而求其次
            }
        }
        return fallback;
    }

    /** {@code all} 里名为 {@link #HOLDER_CLASS} 的类有几份（判读用：0 = 还没加载，2 = 有重名）。 */
    public static int countHolderCandidates(Class<?>[] all) {
        int n = 0;
        for (int i = 0; all != null && i < all.length; i++) {
            if (all[i] != null && HOLDER_CLASS.equals(all[i].getName())) {
                n++;
            }
        }
        return n;
    }

    /** 那个类上有没有 {@code public static boolean acceptInstrumentation(Instrumentation)}。 */
    private static boolean hasAcceptMethod(Class<?> c) {
        try {
            return c.getMethod(ACCEPT_METHOD, Instrumentation.class) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------ ③ 体检

    /**
     * 空转体检：对 {@link #HEALTH_CLASS}（一个与游戏无关的 JDK 类）做一次 no-op retransform。
     *
     * <p>三步：注册 {@link HotfixNoopTransformer}（{@code canRetransform = true}）→
     * {@code retransformClasses} → <b>无论成败都摘掉</b> transformer。
     * 一个字节都没改，但整条"注册 → JVM 回调 → 重新定义"的管道被真的走过一遍。
     *
     * @return 一行结论：{@code state=ready ...} 或 {@code state=degraded reason=... policy=record_only}
     */
    public static String healthCheck(Instrumentation inst) {
        final long t0 = System.currentTimeMillis();
        final String head = "health jdk=" + HEALTH_CLASS;
        if (inst == null) {
            return head + " state=degraded reason=instrumentation_null policy=record_only note=只记录不反制";
        }
        Class<?> target;
        try {
            target = Class.forName(HEALTH_CLASS);
        } catch (Throwable t) {
            return head + " state=degraded reason=health_class_missing exception=" + t
                    + " policy=record_only note=只记录不反制"
                    + " elapsed_ms=" + (System.currentTimeMillis() - t0);
        }
        if (!inst.isRetransformClassesSupported()) {
            return head + " state=degraded reason=retransform_unsupported policy=record_only note=只记录不反制"
                    + " elapsed_ms=" + (System.currentTimeMillis() - t0);
        }
        // 注意：这里 new 的是【独立类】，不是匿名内部类 —— agent jar 里只有固定几个 class，
        // 见 HotfixNoopTransformer 类注释（少一个类 = 目标 JVM 里 NoClassDefFoundError）。
        final HotfixNoopTransformer noop = new HotfixNoopTransformer();
        boolean added = false;
        try {
            inst.addTransformer(noop, true);
            added = true;
            inst.retransformClasses(target);
            return head + " transformer=noop_transformer retransform=ok"
                    + " state=ready reason=health_ok elapsed_ms=" + (System.currentTimeMillis() - t0);
        } catch (Throwable t) {
            return head + " state=degraded reason=retransform_failed exception=" + t
                    + " policy=record_only note=只记录不反制"
                    + " elapsed_ms=" + (System.currentTimeMillis() - t0);
        } finally {
            if (added) {
                try {
                    inst.removeTransformer(noop);
                } catch (Throwable ignored) {
                    // 摘不掉也只会让后续 retransform 多跑一个空转 transformer，不影响游戏
                }
            }
        }
    }

    /** 把体检结论交回游戏侧（失败只记一行，不抛）。 */
    private static void publishState(Class<?> holder, String line, List<String> out) {
        try {
            holder.getMethod(PUBLISH_METHOD, String.class).invoke(null, line);
            add(out, "holder publish_state=ok");
        } catch (Throwable t) {
            add(out, "holder publish_state=failed exception=" + t);
        }
    }

    // ------------------------------------------------------------------ 输出

    /**
     * 三条通道一起写。任何一条失败都不影响其它两条，也不抛出。
     *
     * @param agentArgs 游戏侧传进来的证据目录（可能为空或非 ASCII）
     */
    private static void emit(String agentArgs, List<String> lines) {
        final StringBuilder sb = new StringBuilder(4096);
        for (int i = 0; i < lines.size(); i++) {
            sb.append(PREFIX).append(' ').append(lines.get(i)).append('\n');
        }
        final String text = sb.toString();
        boolean stdoutOk = false;
        try {
            System.out.print(text);
            System.out.flush();
            stdoutOk = true;
        } catch (Throwable ignored) {
            // stdout 被关掉也不该拖累交接
        }
        // stderr 只在 stdout 失败时才用：游戏里两条流都会被 Log4jPrintStream 收进 latest.log，
        // 两边都打 ⇒ 同一段话在日志里出现两遍，读起来很吵（而证据文件本来就已经是第二通道了）。
        if (!stdoutOk) {
            try {
                System.err.print(text);
                System.err.flush();
            } catch (Throwable ignored) {
                // 连 stderr 也不行：还有证据文件
            }
        }
        final String path = evidenceFile(agentArgs);
        if (path == null) {
            return;
        }
        FileOutputStream fos = null;
        try {
            final File f = new File(path);
            final File parent = f.getParentFile();
            if (parent != null) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            fos = new FileOutputStream(f, true);
            fos.write(text.getBytes(StandardCharsets.UTF_8));
            fos.flush();
        } catch (Throwable t) {
            try {
                System.out.println(PREFIX + " evidence_write_failed path=" + path + " err=" + t);
                System.out.flush();
            } catch (Throwable ignored) {
                // 连这行都打不出来就算了：stdout 那两条通道还在
            }
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (Throwable ignored) {
                    // ignore
                }
            }
        }
    }

    /**
     * 证据文件路径：优先 {@code agentArgs} 指向的纯 ASCII 目录（{@code agent-<pid>-<ms>.log}），
     * 否则退回 {@code java.io.tmpdir\shanhai_hotfix\evidence\}。
     *
     * <p>⚠️ 非 ASCII 的 {@code agentArgs} <b>必须整条丢弃</b>，不能"尽力写一次"：
     * 见 {@link HotfixPaths} 类注释里那次"尾部被吃掉"的事故。
     */
    private static String evidenceFile(String agentArgs) {
        if (agentArgs != null && !agentArgs.isEmpty() && isAscii(agentArgs)) {
            final File dir = new File(agentArgs);
            return new File(dir, "agent-" + pid() + ".log").getAbsolutePath();
        }
        final String tmp = System.getProperty("java.io.tmpdir", "");
        if (!isAscii(tmp) || tmp.isEmpty()) {
            return null;
        }
        return new File(new File(new File(tmp, "shanhai_hotfix"), "evidence"), "agent-" + pid() + ".log")
                .getAbsolutePath();
    }

    // ------------------------------------------------------------------ 小工具（故意在此类里重复实现）

    /**
     * 纯 ASCII 判定（{@link HotfixPaths#isAscii} 的本地副本：agent jar 里没有那个类）。
     *
     * <p>用 {@code public} 是为了让<b>离线自检</b>能直接对这条判据做正负对照
     * （它不依赖任何游戏类型，公开它不会扩大什么风险面）。
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

    private static long pid() {
        try {
            return ProcessHandle.current().pid();
        } catch (Throwable t) {
            return -1L;
        }
    }

    private static String describeLoader(ClassLoader loader) {
        return loader == null ? "bootstrap" : loader.getClass().getName() + "@" + Integer.toHexString(loader.hashCode());
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static void add(List<String> out, String line) {
        out.add(line + " t=" + now());
    }

    private static void addFrames(List<String> out, Throwable t, int max) {
        final StackTraceElement[] st = t.getStackTrace();
        final int n = Math.min(max, st.length);
        for (int i = 0; i < n; i++) {
            out.add("at " + st[i]);
        }
        final Throwable cause = t.getCause();
        if (cause != null) {
            out.add("cause=" + cause);
        }
    }

    private static String now() {
        return new SimpleDateFormat("HH:mm:ss.SSS").format(new Date());
    }
}
