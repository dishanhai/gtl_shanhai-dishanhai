package com.shanhai.add.hotfix;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * <b>黑名单式准入</b>：<b>默认任何类都可以当热改目标</b>，只排除三类。
 *
 * <h2>🔴 2026-11-20 改判据（用户当场点出盲区）</h2>
 * <pre>
 *   「你防止我们自己的agent，那是不是会漏掉敌人的agent？」
 * </pre>
 * 上一版的准入条件是「该类的 code source 必须落在 {@code mods\} 下的 jar 里」。那是一条
 * <b>白名单式</b>判据，它挡得住 {@code mods\} 里的 jar，但<b>挡不住</b>：
 * <ul>
 *   <li>{@code -javaagent} 注入的类（{@code agentmain}/{@code premain} 里 {@code defineClass}）；</li>
 *   <li>{@code Unsafe.defineAnonymousClass} / {@code MethodHandles.Lookup#defineClass}；</li>
 *   <li>任何自建 {@code ClassLoader} 现算现用的类。</li>
 * </ul>
 * 这三类恰恰是"对手也会用"的手段，而它们的 {@code CodeSource} <b>很可能是 {@code null}</b>
 * ⇒ 按旧判据一律 {@code no_code_source} 拒掉，等于"敌人换一种加载方式我们就废不掉它"。
 *
 * <p>所以判据改成<b>排除法</b>：先假设可改，再逐条排除。
 *
 * <h2>判据表（第一列命中即停；<b>拒绝优先于接受</b>）</h2>
 * <pre>
 *   拒 1  包名命中 {@link #NEVER_PATCH_PREFIXES}                   → whitelisted:&lt;前缀&gt;
 *   拒 2  该类的 code source 与<b>我们自己</b>的相同（规范化后相等） → own_code_source
 *   拒 3  code source 是 {@code jrt:}（JDK 运行时镜像）              → jdk_runtime
 *   拒 4  code source 落在 {@code \libraries\}/\jars\}/版本 jar      → 不收也不拒（见下"环境类"）
 *   ——
 *   收 1  code source 拿得到（任何路径，含 libraries 与 agent 自建 jar）→ ok_code_source
 *   收 2  code source 在 {@code mods\} 下（只是<b>标记</b>，不参与准入）  → ok_code_mods
 *   收 3  code source 拿不到，且加载器<b>不是</b> JDK 三件套            → ok_code_none_loader
 *   收 4  code source 拿不到，且加载器<b>不是</b> MC 那个模块加载器实例  → ok_code_none_foreign
 *   拒 5  code source 拿不到 ＋ 两条二级信号都不成立                  → no_code_source_no_signal
 * </pre>
 *
 * <h2>为什么"拒 3/拒 4"里的 libraries 改成"不拒"</h2>
 * 用户的口径是「默认任何类都可以当目标」。{@code libraries\} 里装的是第三方库（ASM、log4j、
 * fastutil…），它们<b>不是</b>"运行环境必须项"以外的东西 —— 而那些必须项已经逐条进了
 * {@link #NEVER_PATCH_PREFIXES}。留一条"整段 libraries 拒掉"只会把"对手把自己的 jar 丢进
 * libraries"这种玩法变成免死金牌。（{@code jrt:} 仍然拒：那是 JDK 自己的类。）
 *
 * <h2>为什么不再把 code source 交给 {@code Paths.get(...)}（2026-11-08 现场定案，仍然有效）</h2>
 * Forge／ModLauncher 给 mod 类的 code source 是 <b>union URL</b>，形如
 * <pre>
 *   union:/C:/…/versions/&lt;实例目录&gt;/mods/examplemod-1.2.3.jar%23326!/
 * </pre>
 * 老判据走 {@code Paths.get(url.toURI())}，而 {@code cpw.mods.niofs.union.UnionFileSystemProvider#getPath(URI)}
 * 的实现是「把 URI 的 path 按 {@code !} 切开，只要切出 2 段就返回<b>union 文件系统的根</b>」：
 * <pre>
 *   uri.getPath() = /C:/…/mods/examplemod-1.2.3.jar#326!/   →   split("!") 得 2 段
 *   ⇒ provider 返回 union 根，toString() = "/"
 *   ⇒ 老判据拿到的"路径"是 "/"（Windows 上被解析成 C:\），永远不在 mods\ 下
 * </pre>
 * （离线用本机真实的 {@code securejarhandler-2.1.10.jar} 复现出逐字相同的结果，见
 * {@code temp\hotfix\modsdir\报告.md}。）
 *
 * <p>所以现在<b>只做字符串规范化</b>，从不把 code source 变成 {@link java.nio.file.Path}：
 * 原样的 URL 字符串里 {@code mods} 那一级是完好的，丢掉它的只有 {@code Paths.get} 那一步。
 *
 * <h2>依赖面：只有 {@code java.*}</h2>
 * 没有 MC／Forge／日志框架、没有 ASM、<b>没有 {@code java.nio.file}</b>。这样它既能被游戏里的
 * {@link HotfixAutoCounter} 用，也能被<b>脱离游戏</b>的离线自检（{@code temp\hotfix\selfcheck\}、
 * {@code temp\hotfix\agentblind\}）直接加载 —— 准入判错是最贵的一种错（"改到了原版类"），
 * 所以这条判据必须能离线验、且有负对照。
 *
 * <h2>🔴 判据里没有"这个 mod 是谁"</h2>
 * 规格要求按"白名单外 ＋ 不是我们自己 ＋ 不是 JDK"判，<b>不按包名猜 mod</b>。所以对手换包名、
 * 换 mod id、换类名，判据一个字都不用改。
 */
public final class HotfixPatchWhitelist {

    /**
     * 永不改的类名前缀（"包名白名单"那一类）。
     *
     * <p>顺序无所谓（{@link #hitPrefix} 取第一个命中），但<b>不要删条目</b>：
     * 每一条都对应一类"改了必出事"的东西（JDK／Mojang／原版／Forge／启动器／我们／主办方 mod／
     * 运行环境必须项）。
     *
     * <p>后 9 条是 2026-11-20 按"环境必须项"补齐的：<b>引擎自己就用 ASM 与 log4j</b>，
     * 把 ASM 当目标改掉会在下一次 {@code retransform} 时自杀。
     */
    public static final String[] NEVER_PATCH_PREFIXES = {
            // ---- 规格逐条点名的十二类
            "java.",
            "javax.",
            "jdk.",
            "sun.",
            "com.mojang.",
            "net.minecraft.",
            "net.minecraftforge.",
            "cpw.",
            "org.spongepowered.",
            "com.shanhai.",
            "org.gregtechceu.",
            "org.gtlcore.",
            "com.gtladd.",
            // ---- 运行环境必须项（改了会在下一次 retransform 时自杀，或把日志/网络搞坏）
            "org.objectweb.asm.",        // 热改引擎自己就在用 ASM 解析/写字节
            "org.apache.logging.log4j.", // 分诊订阅（appender）走这条日志管道
            "org.apache.commons.",       // MC 内部大量使用（lang3 / io）
            "org.slf4j.",                // 本体的日志门面
            "io.netty.",                 // 网络层
            "it.unimi.",                 // fastutil（同步字段容器的底层）
            "com.google.",               // gson / guava / brigadier
            "com.electronwill.",         // nightconfig（Forge 配置）
            "org.joml.",                 // 向量数学
            "org.lwjgl.",                // 图形/音频绑定
            "oshi."                      // 系统信息（Forge 启动期）
    };

    /** {@code mods\} 这一级目录的名字（大小写不敏感地比；现在只用于<b>标记</b>，不用于准入）。 */
    public static final String MODS_SEGMENT = "mods";

    /** 规范化时先去掉的 scheme 前缀（长的在前：{@code jar:file:} 要先去掉 {@code jar:}）。 */
    private static final String[] SCHEME_PREFIXES = {"union:", "jar:", "file:"};

    /**
     * JDK 自带那三个类加载器的名字片段（命中 ⇒ 这是"JDK 自己的类"，不可能是敌人的 agent）。
     *
     * <p>三条一起看：类名以 {@code jdk.internal.} 或 {@code sun.misc.} 开头，且含下列片段。
     * 这条判据<b>只用于"code source 拿不到时的二级信号"</b>与拒 4 —— code source 拿得到的类
     * 一律按"默认可改"处理，不看加载器。
     */
    private static final String[] JDK_LOADER_MARKERS = {"AppClassLoader", "PlatformClassLoader"};

    /** JDK 类加载器的包前缀（与 {@link #JDK_LOADER_MARKERS} 一起用）。 */
    private static final String[] JDK_LOADER_PACKAGES = {"jdk.internal.loader.", "sun.misc."};

    /** 判「这是 Forge 的 mod 类加载器」用的类名片段（<b>大小写敏感</b>，见 {@link #hitForgeLoader}）。 */
    private static final String[] FORGE_LOADER_MARKERS = {"Launch", "SecureJarHandler", "ModLauncher"};

    /** ModList 探针只跑一次；拿不到就永久跳过（{@code null} = 还没试过，{@code []} = 试过但没有）。 */
    private static volatile String[] modListJarNames;

    private HotfixPatchWhitelist() {
        // 纯判定类，没有实例
    }

    // ═══════════════════════════════ 判据的输入 ═══════════════════════════════

    /**
     * 一次判定的全部输入（可变的小数据袋；每次判定新建一个，绝不复用）。
     *
     * <p>为什么不用 7 个参数：{%@code judge} 有 7 项输入，按位置传参最容易写反
     * （{@code selfLoader} 与 {@code mcLoader} 都是 {@link ClassLoader}）。
     */
    public static final class Probe {

        /** 源级类名（{@code examplemod.util.ExampleHelper}），允许 {@code $} 内部类。 */
        public String className;

        /**
         * {@code getProtectionDomain().getCodeSource().getLocation().toString()} 的<b>原样字符串</b>
         * （{@code union:/…}/{@code jar:file:/…}/{@code file:/…}）；{@code null}/空 = 拿不到。
         */
        public String codeSource;

        /** {@code mods\} 的绝对路径；{@code null}/空 = 运行期拿不到（只影响 {@code code_source=} 标记）。 */
        public String modsRoot;

        /** 目标类的加载器；{@code null} = bootstrap。 */
        public ClassLoader loader;

        /**
         * {@link #loader} 这一项<b>可不可信</b>。
         *
         * <p>为什么需要它：{@code null} 有两种完全不同的含义 ——
         * ① <b>bootstrap 加载器</b>（真实值，只可能是 JDK 自己的类）；
         * ② <b>调用方没告诉我们</b>（例如只给"类名 ＋ code source"的离线工具）。
         * 把 ② 当成 ① 会把"code source 明明在 mods 里的第三方类"判成 {@code jdk_loader}。
         * ⇒ 只有真的拿到了 {@code Class} 的调用方（{@link #withLoader}／
         * {@code HotfixTargetResolver#resolve}／{@code HotfixAutoCounter#apply}）才把这一项置 true。
         */
        public boolean loaderKnown;

        /** 补上目标类的加载器（<b>置 loaderKnown = true</b>：{@code null} 就是 bootstrap）。 */
        public Probe withLoader(ClassLoader loader) {
            this.loader = loader;
            this.loaderKnown = true;
            return this;
        }

        /** <b>我们自己</b>（{@code ShanhaiMod}）的加载器；{@code null} = 未知。 */
        public ClassLoader selfLoader;

        /** <b>我们自己</b>的 code source 原样字符串；{@code null}/空 = 未知。 */
        public String selfCodeSource;

        /**
         * 一个<b>一定属于原版/MC 模块</b>的加载器实例（装配侧传 {@code Minecraft.class.getClassLoader()}）；
         * {@code null} = 未知 ⇒ 二级信号 (b) 跳过。
         */
        public ClassLoader mcLoader;

        /** 建一个只有类名与 code source 的最小探针（离线自检最常用）。 */
        public static Probe of(String className, String codeSource, String modsRoot) {
            final Probe p = new Probe();
            p.className = className;
            p.codeSource = codeSource;
            p.modsRoot = modsRoot;
            return p;
        }

        /** 补齐"我们自己"与 MC 的加载器（装配侧一行搞定）。 */
        public Probe self(ClassLoader selfLoader, String selfCodeSource, ClassLoader mcLoader) {
            this.selfLoader = selfLoader;
            this.selfCodeSource = selfCodeSource;
            this.mcLoader = mcLoader;
            return this;
        }


    }

    // ═══════════════════════════════ 入口 ═══════════════════════════════

    /**
     * 类名命中的白名单前缀。
     *
     * @return 命中的前缀（形如 {@code "net.minecraft."}）；不在白名单里 ⇒ {@code null}
     */
    public static String hitPrefix(String className) {
        if (className == null || className.isEmpty()) {
            // 拿不到类名时按"不许改"处理：调用方会把它记一条 refused
            return "?";
        }
        for (int i = 0; i < NEVER_PATCH_PREFIXES.length; i++) {
            if (className.startsWith(NEVER_PATCH_PREFIXES[i])) {
                return NEVER_PATCH_PREFIXES[i];
            }
        }
        return null;
    }

    /** 这个类是不是"永不改"（白名单命中）。{@code null}/空串也算命中（fail-closed）。 */
    public static boolean isNeverPatch(String className) {
        return hitPrefix(className) != null;
    }

    /** 最小形态：只有类名 ＋ code source ＋ mods 根（离线自检、{@code first_external=} 用）。 */
    public static Decision judge(String className, String codeSource, String modsRoot) {
        return judge(Probe.of(className, codeSource, modsRoot));
    }

    /**
     * 老签名的兼容重载（{@code loaderName} 只用来认 Forge 加载器；不参与"JDK 加载器"那条）。
     *
     * @deprecated 新代码请用 {@link Probe}：{@code loaderName} 是 {@link String}，认不出"同一个加载器实例"，
     *             而二级信号 (b) 恰恰要实例比较。
     */
    @Deprecated
    public static Decision judge(String className, String codeSource, String modsRoot, String loaderName) {
        final Decision d = judge(Probe.of(className, codeSource, modsRoot));
        if (d.allowed || loaderName == null || loaderName.isEmpty()) {
            return d;
        }
        // 只有"被拒"时才让 Forge 加载器名当一次兜底（与旧版同口径）
        if (hitForgeLoader(loaderName) != null) {
            return new Decision(true, "ok_forge_loader", d.raw, d.normalized, d.modsRoot,
                    d.loader, d.codeSourceToken);
        }
        return d;
    }

    /**
     * 完整判据（排除法）。
     *
     * @param p 判定输入（{@code null} ⇒ 直接拒 {@code bad_probe}）
     * @return 判定结果（{@link Decision#allowed} ＋ 机器可判的 {@link Decision#reason}）
     */
    public static Decision judge(Probe p) {
        if (p == null) {
            return new Decision(false, "bad_probe", null, "", "", null, "none");
        }
        final String raw = p.codeSource == null ? null : p.codeSource.trim();
        final String norm = raw == null ? "" : normalizeSource(raw);
        final String selfNorm = p.selfCodeSource == null ? "" : normalizeSource(p.selfCodeSource);
        final String have = raw == null || raw.isEmpty() ? "none" : (norm.isEmpty() ? "none" : "yes");

        // ---- 拒 1：包名硬白名单（与来源无关）
        final String hit = hitPrefix(p.className);
        if (hit != null) {
            return new Decision(false, "whitelisted:" + hit, raw, norm, p.modsRoot, p.loader, have);
        }
        // ---- 拒 2：我们自己的 code source（"我们自己的 jar 与 agent 自己的 jar"）
        //   ⚠️ 不写任何路径字面量：只比"规范化之后是否与我们自己这个类完全相同"。
        if (!selfNorm.isEmpty() && !norm.isEmpty() && selfNorm.equals(norm)) {
            return new Decision(false, "own_code_source", raw, norm, p.modsRoot, p.loader, have);
        }
        // ---- 拒 3：JDK 运行时镜像
        if (raw != null && raw.toLowerCase(Locale.ROOT).startsWith("jrt:")
                || norm.startsWith("jrt:") || norm.contains("jrt:")) {
            return new Decision(false, "jdk_runtime", raw, norm, p.modsRoot, p.loader, have);
        }
        // ---- 拒 4：JDK 自带三个类加载器载入的类（code source 拿得到时也拒 —— 那是 JDK 自己的类）
        //   ⚠️ 只在"加载器这一项真的可信"时才判：{@code loaderKnown=false} 意味着调用方没告诉我们
        //   （例如只给"类名 ＋ code source"的离线工具），此时 null 不能当成 bootstrap。
        if (p.loaderKnown && isJdkLoader(p.loader)) {
            return new Decision(false, "jdk_loader", raw, norm, p.modsRoot, p.loader, have);
        }
        // ---- 收 1：code source 拿得到 ⇒ 默认可改（mods / libraries / agent 自建 jar 都算）
        if ("yes".equals(have)) {
            final String rootNorm = normalizeSource(p.modsRoot);
            final boolean inMods = (!rootNorm.isEmpty() && underRoot(norm, rootNorm))
                    || containsSegment(norm, MODS_SEGMENT);
            // 🔴 mods\ 只是【标记】，不参与准入（旧版这里是准入条件，被用户当场指出有盲区）
            return new Decision(true, inMods ? "ok_code_mods" : "ok_code_source",
                    raw, norm, p.modsRoot, p.loader, have);
        }
        // ---- 收 2/3：code source 拿不到 ⇒ 要一条二级信号（任一满足即可）
        //
        //   (a) 加载器存在、且不是 JDK 三件套 —— 前提是"我们知道它是谁"（loaderKnown）。
        if (p.loaderKnown && p.loader != null && !isJdkLoader(p.loader)) {
            return new Decision(true, "ok_code_none_loader", raw, norm, p.modsRoot, p.loader, have);
        }
        //   (b) 加载器与"MC 那个模块加载器"不是同一个实例（对手自建加载器 / agent 的形态）
        if (p.mcLoader != null && p.loader != p.mcLoader) {
            return new Decision(true, "ok_code_none_foreign", raw, norm, p.modsRoot, p.loader, have);
        }
        // ---- 拒 5：两条二级信号都不成立
        return new Decision(false, "no_code_source_no_signal", raw, norm, p.modsRoot, p.loader, have);
    }

    // ------------------------------------------------------------------ 规范化（纯函数）

    /**
     * 把任意形态的 code source 字符串规范化成"可比对的小写反斜杠路径"。
     *
     * <p>步骤（顺序有讲究）：① 去掉 {@code union:}/{@code jar:}/{@code file:} 前缀（可多轮）；
     * ② 从第一个 {@code !} 处截断（{@code jar!/包内路径} 只留 jar）；③ URL-decode（{@code %20}/{@code %23}/
     * 中文的 {@code %E6%97%A5} 等）；④ 去掉 ModLauncher 的 {@code #<数字>} jar 序号尾巴；
     * ⑤ {@code /} → {@code \}；⑥ 去掉 {@code /C:/…} 前面那层多余的反斜杠；⑦ {@code toLowerCase(Locale.ROOT)}。
     *
     * @param raw 原样字符串（可为 {@code null}）
     * @return 规范化结果（不会返回 {@code null}，最坏是空串）
     */
    public static String normalizeSource(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.trim();
        if (text.isEmpty()) {
            return "";
        }
        // ① 去 scheme（可能叠加：jar:file:）
        boolean stripped = true;
        while (stripped) {
            stripped = false;
            final String lower = text.toLowerCase(Locale.ROOT);
            for (int i = 0; i < SCHEME_PREFIXES.length; i++) {
                if (lower.startsWith(SCHEME_PREFIXES[i])) {
                    text = text.substring(SCHEME_PREFIXES[i].length());
                    stripped = true;
                    break;
                }
            }
        }
        // ② jar 根之后的东西不要（union:/…x.jar!/com/foo ⇒ …x.jar）
        final int bang = text.indexOf('!');
        if (bang >= 0) {
            text = text.substring(0, bang);
        }
        // ③ URL-decode
        text = urlDecode(text);
        // ④ 去掉 #<数字>（ModLauncher 的 jar 序号；%23 解码之后就是它）
        text = stripHashIndex(text);
        // ⑤ 统一分隔符
        text = text.replace('/', '\\');
        // ⑥ file:///C:/… 之类的多重前导斜杠：只有当后面紧跟盘符时才去掉
        while (text.length() > 2 && text.charAt(0) == '\\' && isDriveColon(text, 1)) {
            text = text.substring(1);
        }
        // ⑦ 小写
        return text.toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ 判定小工具（包内可见，自证会用）

    /** 这个加载器是不是 JDK 自带的三个之一（{@code null} = bootstrap，<b>算</b> JDK）。 */
    public static boolean isJdkLoader(ClassLoader loader) {
        if (loader == null) {
            return true;      // bootstrap 只加载 JDK 自己的类
        }
        final String name = loader.getClass().getName();
        if (name == null || name.isEmpty()) {
            return false;
        }
        boolean pkg = false;
        for (int i = 0; i < JDK_LOADER_PACKAGES.length; i++) {
            if (name.startsWith(JDK_LOADER_PACKAGES[i])) {
                pkg = true;
                break;
            }
        }
        if (!pkg) {
            return false;
        }
        for (int i = 0; i < JDK_LOADER_MARKERS.length; i++) {
            if (name.contains(JDK_LOADER_MARKERS[i])) {
                return true;
            }
        }
        return false;
    }

    /** 加载器的人类可读名字（{@code null} ⇒ {@code bootstrap}）。 */
    public static String loaderName(ClassLoader loader) {
        if (loader == null) {
            return "bootstrap";
        }
        try {
            final String n = loader.getClass().getName();
            return n == null || n.isEmpty() ? "?" : n;
        } catch (Throwable t) {
            return "?";
        }
    }

    /** 规范化后的路径里有没有 {@code \segment\} 这一级（{@code segment} 已是小写）。 */
    static boolean containsSegment(String norm, String segment) {
        if (norm == null || norm.isEmpty() || segment == null || segment.isEmpty()) {
            return false;
        }
        // 🔴 逐段比，不做 substring：`…\mymods_old\x.jar` / `…\mods_backup\x.jar` /
        //    `…\temp\mods_stuff\x.jar` / `…\somemods\mods_like\x.jar` 里都没有"一段等于 mods"
        //    （含 `\mods\` 的裸 contains 判据挡不住这几个的反向想象，逐段才没有歧义）。
        final int n = norm.length();
        int start = 0;
        while (start <= n) {
            int end = norm.indexOf('\\', start);
            if (end < 0) {
                end = n;
            }
            // 空的段（前导 `\` 或连续 `\\`）直接跳过
            if (end > start && segment.regionMatches(true, 0, norm, start, end - start)
                    && end - start == segment.length()) {
                return true;
            }
            if (end >= n) {
                return false;
            }
            start = end + 1;
        }
        return false;
    }

    /** {@code norm} 是否在 {@code rootNorm} 之下（两边都已规范化；相等也算）。 */
    static boolean underRoot(String norm, String rootNorm) {
        if (norm == null || rootNorm == null || norm.isEmpty() || rootNorm.isEmpty()) {
            return false;
        }
        if (norm.equals(rootNorm)) {
            return true;
        }
        final String prefix = rootNorm.endsWith("\\") ? rootNorm : rootNorm + "\\";
        return norm.startsWith(prefix);
    }

    /**
     * 是否形如 {@code …\versions\&lt;x&gt;\&lt;x&gt;.jar}（原版/Forge 的版本 jar）。
     *
     * <p>⚠️ 2026-11-20 起这条<b>不再用于准入</b>（原版类由 {@code net.minecraft.} 前缀挡掉），
     * 只保留给标记/诊断用。
     */
    static boolean looksLikeVersionJar(String norm) {
        if (norm == null || norm.isEmpty()) {
            return false;
        }
        final String marker = "\\versions\\";
        final int i = norm.indexOf(marker);
        if (i < 0) {
            return false;
        }
        final String rest = norm.substring(i + marker.length());
        final int sep = rest.indexOf('\\');
        if (sep <= 0 || sep + 1 >= rest.length()) {
            return false;
        }
        final String versionDir = rest.substring(0, sep);
        final String file = rest.substring(sep + 1);
        if (file.indexOf('\\') >= 0) {
            // 还有子目录 ⇒ 是 versions\<x>\<子目录>\…，交给别的判据
            return false;
        }
        return file.equals(versionDir + ".jar");
    }

    /** 规范化路径里最后一段文件名；不是 {@code .jar} ⇒ {@code null}。 */
    static String jarFileName(String norm) {
        if (norm == null || norm.isEmpty()) {
            return null;
        }
        final int sep = norm.lastIndexOf('\\');
        final String name = sep < 0 ? norm : norm.substring(sep + 1);
        if (name.length() <= 4 || !name.endsWith(".jar")) {
            return null;
        }
        return name;
    }

    /**
     * 类加载器类名里有没有 Forge 的标记（<b>大小写敏感</b>）。
     *
     * <p>🔴 故意大小写敏感：{@code cpw.mods.modlauncher.TransformingClassLoader} 是小写的
     * {@code modlauncher}，而它<b>同时加载原版和 libraries</b> —— 若大小写不敏感地匹配
     * {@code "ModLauncher"}，就等于"什么都能改"。这条兜底只认 {@code SecureJarHandler} /
     * {@code Launch} / {@code ModLauncher} 这几个原样写法。
     */
    static String hitForgeLoader(String loaderName) {
        if (loaderName == null || loaderName.isEmpty()) {
            return null;
        }
        for (int i = 0; i < FORGE_LOADER_MARKERS.length; i++) {
            if (loaderName.contains(FORGE_LOADER_MARKERS[i])) {
                return FORGE_LOADER_MARKERS[i];
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ ModList 探针（反射，拿不到就跳过）

    /**
     * {@code jar} 文件名是否出现在 {@code ModList} 里（反射访问，没有编译期 Forge 依赖）。
     *
     * <p>拿不到 ModList（离线、类不在、字段改名）⇒ 一律 {@code false}（＝跳过，不是拒绝）。
     * 结果缓存：第一次调用算一次，之后只用缓存。
     */
    static boolean modListContains(String jar) {
        if (jar == null || jar.isEmpty()) {
            return false;
        }
        final String[] names = modListJarNames();
        for (int i = 0; i < names.length; i++) {
            if (jar.equals(names[i])) {
                return true;
            }
        }
        return false;
    }

    private static String[] modListJarNames() {
        final String[] cached = modListJarNames;
        if (cached != null) {
            return cached;
        }
        synchronized (HotfixPatchWhitelist.class) {
            if (modListJarNames != null) {
                return modListJarNames;
            }
            String[] found = new String[0];
            try {
                found = scanModList();
            } catch (Throwable ignored) {
                // 拿不到就跳过（规格："能拿到就用，拿不到跳过"）
                found = new String[0];
            }
            modListJarNames = found;
            return found;
        }
    }

    private static String[] scanModList() throws Exception {
        final Class<?> modListCls = Class.forName("net.minecraftforge.fml.ModList");
        final Object modList = modListCls.getMethod("get").invoke(null);
        if (modList == null) {
            return new String[0];
        }
        final Object mods = modListCls.getMethod("getMods").invoke(modList);
        if (!(mods instanceof Iterable)) {
            return new String[0];
        }
        final java.util.List<String> names = new java.util.ArrayList<>();
        for (Object modInfo : (Iterable<?>) mods) {
            if (modInfo == null) {
                continue;
            }
            final String name = oneModFileName(modInfo);
            if (name != null && !names.contains(name)) {
                names.add(name);
            }
        }
        return names.toArray(new String[0]);
    }

    /** {@code IModInfo#getOwningFile().getFile().getFileName()}（三步都反射，任一步失败返回 null）。 */
    private static String oneModFileName(Object modInfo) {
        try {
            final Method getOwningFile = modInfo.getClass().getMethod("getOwningFile");
            final Object owningFile = getOwningFile.invoke(modInfo);
            if (owningFile == null) {
                return null;
            }
            final Object file = owningFile.getClass().getMethod("getFile").invoke(owningFile);
            if (file == null) {
                return null;
            }
            Object name = null;
            try {
                name = file.getClass().getMethod("getFileName").invoke(file);
            } catch (Throwable ignored) {
                name = null;
            }
            if (name == null) {
                try {
                    name = file.getClass().getMethod("getFilePath").invoke(file);
                } catch (Throwable ignored) {
                    name = null;
                }
            }
            if (name == null) {
                return null;
            }
            final String text = String.valueOf(name).replace('/', '\\');
            final int sep = text.lastIndexOf('\\');
            final String base = sep < 0 ? text : text.substring(sep + 1);
            return base.toLowerCase(Locale.ROOT);
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 小工具（纯字符串）

    private static boolean isDriveColon(String s, int i) {
        if (s == null || i < 0 || i + 1 >= s.length()) {
            return false;
        }
        final char c = s.charAt(i);
        return ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) && s.charAt(i + 1) == ':';
    }

    private static int hex(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    /** {@code %XX} 解码（连续的 {@code %XX} 一起按 UTF-8 解，所以中文三字节也对）。坏序列原样保留。 */
    static String urlDecode(String s) {
        if (s == null || s.indexOf('%') < 0) {
            return s == null ? "" : s;
        }
        final StringBuilder out = new StringBuilder(s.length());
        final int n = s.length();
        int i = 0;
        while (i < n) {
            final char c = s.charAt(i);
            if (c == '%' && i + 2 < n && hex(s.charAt(i + 1)) >= 0 && hex(s.charAt(i + 2)) >= 0) {
                final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(4);
                while (i + 2 < n && s.charAt(i) == '%'
                        && hex(s.charAt(i + 1)) >= 0 && hex(s.charAt(i + 2)) >= 0) {
                    bytes.write((hex(s.charAt(i + 1)) << 4) | hex(s.charAt(i + 2)));
                    i += 3;
                }
                out.append(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** 去掉 ModLauncher 的 jar 序号尾巴：{@code x.jar#326} ⇒ {@code x.jar}（只吃纯数字）。 */
    static String stripHashIndex(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        int hash = s.lastIndexOf('#');
        if (hash < 0 || hash + 1 >= s.length()) {
            return s;
        }
        for (int i = hash + 1; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return s;
            }
        }
        return s.substring(0, hash);
    }

    // ------------------------------------------------------------------ 结论对象

    /** 判定结果（不可变）。 */
    public static final class Decision {

        /** {@code true} = 允许自动改这一个类。 */
        public final boolean allowed;

        /**
         * 机器可判的原因：
         * {@code ok_code_mods}／{@code ok_code_source}／{@code ok_code_none_loader}／
         * {@code ok_code_none_foreign}／{@code ok_forge_loader}／
         * {@code whitelisted:<前缀>}／{@code own_code_source}／{@code jdk_runtime}／
         * {@code jdk_loader}／{@code no_code_source_no_signal}／{@code bad_probe}
         */
        public final String reason;

        /** code source 的<b>原样</b>字符串（{@code union:/…} 就照抄；可能为 {@code null}）。 */
        public final String raw;

        /** 规范化之后的路径（小写、反斜杠；可能为空串）。 */
        public final String normalized;

        /** 规范化之后的 mods 根（可能为空串）。 */
        public final String modsRoot;

        /** 目标类的加载器（{@code null} = bootstrap）。 */
        public final ClassLoader loader;

        /** 加载器类名（{@code bootstrap} = {@code null} 那个）。 */
        public final String loaderName;

        /** {@code yes} = code source 拿得到；{@code none} = 拿不到（进日志的 {@code code_source=}）。 */
        public final String codeSourceToken;

        Decision(boolean allowed, String reason, String raw, String normalized, String modsRoot,
                 ClassLoader loader, String codeSourceToken) {
            this.allowed = allowed;
            this.reason = reason;
            this.raw = raw;
            this.normalized = normalized == null ? "" : normalized;
            this.modsRoot = modsRoot == null ? "" : modsRoot;
            this.loader = loader;
            this.loaderName = loaderName(loader);
            this.codeSourceToken = codeSourceToken == null ? "none" : codeSourceToken;
        }

        /** 一行日志用的摘要（不含空格，可直接塞进 {@code detail=}）。 */
        public String brief() {
            return "allowed=" + allowed + " reason=" + reason + " code_source=" + codeSourceToken;
        }

        @Override
        public String toString() {
            return brief() + " raw=" + (raw == null ? "?" : raw)
                    + " normalized=" + (normalized.isEmpty() ? "?" : normalized)
                    + " mods_root=" + (modsRoot.isEmpty() ? "?" : modsRoot)
                    + " loader=" + loaderName;
        }
    }
}
