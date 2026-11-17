package com.shanhai.add.hotfix;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * <b>硬白名单</b>（永不改）＋「类必须来自 {@code mods\} 目录」这一条来源判据。
 *
 * <h2>它是纯判定，不碰任何东西</h2>
 * 只有 {@code java.*}（含 {@code java.lang.reflect} / {@code java.nio.charset} / {@link Locale}）：
 * 没有 MC／Forge／日志框架、没有 ASM、<b>没有 {@code java.nio.file}</b>（原因见下面"为什么不再用 Path"）。
 * 这样它既能被游戏里的 {@link HotfixAutoCounter} 用，也能被<b>脱离游戏</b>的离线自检
 * （{@code temp\hotfix\modsdir\selfcheck\}、{@code temp\hotfix\offline-autocounter\}）直接加载 ——
 * 白名单判错是最贵的一种错（"改到了原版类"），所以这条判据必须能离线验、且有负对照。
 *
 * <h2>🔴 为什么不再把 code source 交给 {@code Paths.get(...)}（2026-11-08 现场定案）</h2>
 * Forge／ModLauncher 给 mod 类的 code source 是 <b>union URL</b>，形如
 * <pre>
 *   union:/C:/…/versions/<实例目录>/mods/examplemod-1.2.3.jar%23326!/
 * </pre>
 * 老判据走 {@code Paths.get(url.toURI())}，而 {@code cpw.mods.niofs.union.UnionFileSystemProvider#getPath(URI)}
 * 的实现是「把 URI 的 path 按 {@code !} 切开，只要切出 2 段就返回<b>union 文件系统的根</b>」：
 * <pre>
 *   uri.getPath() = /C:/…/mods/examplemod-1.2.3.jar#326!/   →   split("!") 得 2 段
 *   ⇒ provider 返回 union 根，toString() = "/"
 *   ⇒ 老判据拿到的"路径"是 "/"（Windows 上被解析成 C:\），永远不在 mods\ 下
 *   ⇒ 于是必然打出 gate_outside_mods_dir，jar 明明就在 mods\ 里也没用
 * </pre>
 * （离线用本机真实的 {@code securejarhandler-2.1.10.jar} 复现出逐字相同的结果，见
 * {@code temp\hotfix\modsdir\报告.md}。另一种形态——URL 里中文不编码——会在
 * {@code url.toURI()} 抛 {@code URISyntaxException}，老判据变成 {@code no_code_source}。）
 *
 * <p>所以现在<b>只做字符串规范化</b>，从不把 code source 变成 {@link java.nio.file.Path}：
 * 原样的 URL 字符串里 {@code mods} 那一级是完好的，丢掉它的只有 {@code Paths.get} 那一步。
 *
 * <h2>判据表（第一列命中即停）</h2>
 * <pre>
 *   拒 1  类名命中 {@link #NEVER_PATCH_PREFIXES}            → whitelisted:&lt;前缀&gt;
 *   拒 2  code source 拿不到（null/空）                      → no_code_source
 *   拒 3  规范化之后是空串                                   → bad_code_source
 *   拒 4  原样或规范化后带 jrt:                              → refuse_jrt
 *   拒 5  规范化后含 \libraries\                             → refuse_libraries
 *   拒 6  规范化后含 \jars\                                  → refuse_jars
 *   拒 7  规范化后是 \versions\&lt;x&gt;\&lt;x&gt;.jar（原版版本 jar）    → refuse_version_jar
 *   收 1  规范化后以 mods 根 + "\" 开头                       → ok
 *   收 2  规范化后**按 {@code \} 切段**，有一段等于 {@code mods}      → ok_mods_path（mods 根拿不到时是 ok_mods_segment）
 *   收 3  类加载器类名含 Launch/SecureJarHandler/ModLauncher → ok_forge_loader
 *   收 4  jar 文件名出现在 ModList 的文件信息里（拿不到就跳过） → ok_modlist
 *   拒 8  以上都不成立                                       → outside_mods_dir
 * </pre>
 * <b>拒绝优先于接受</b>：拒 1–7 先跑，收 1–4 只在它们都没命中时才看。
 *
 * <h2>为什么要"来自 mods 目录"</h2>
 * 热改的目标永远是"某个第三方 mod 的方法"。而"某个 jar 是不是第三方 mod"在运行期只有两条
 * 便宜证据：① 它在 {@code mods\} 下；② 它不在 {@code net.minecraft.*}/{@code com.mojang.*}
 * 这些前缀里。①挡"改到 Forge 自己打进 libraries 的东西"，②挡"改到原版/我们"。两条都不成立才动手。
 *
 * <p>⚠️ 判据里<b>没有</b>"这个 mod 是谁"的白名单 —— 规格要求按"白名单外"判，不按包名猜。
 *
 * <p>⚠️ 收 2（{@code \mods\} 那一段）是一条<b>按路径分段</b>的判据：当 {@code modsRoot} 与这条路径
 * 对不上时（例如多实例、实例搬家），它仍然会放行。这是规格第 ① 条的字面要求，代价是
 * "modsRoot 不匹配"不再构成拒绝理由（离线自检 {@code a7.refuse_outside_mods} 那一条的期望值
 * 随之变化，见报告"没验到的"一节）。
 */
public final class HotfixPatchWhitelist {

    /**
     * 永不改的类名前缀（规格逐条点名）。
     *
     * <p>顺序无所谓（{@link #hitPrefix} 取第一个命中），但<b>不要删条目</b>：
     * 每一条都对应一类"改了必出事"的东西（JDK／Mojang／原版／Forge／启动器／我们／主办方 mod）。
     *
     * <p>🆕 2026-11-08：按规格补齐 {@code cpw.}（含 {@code cpw.mods.}）、{@code org.spongepowered.}、
     * {@code org.gtlcore.}、{@code com.gtladd.}。
     */
    public static final String[] NEVER_PATCH_PREFIXES = {
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
            "org.gtlcore.",
            "com.gtladd."
    };

    /** {@code mods\} 这一级目录的名字（大小写不敏感地比）。 */
    public static final String MODS_SEGMENT = "mods";

    /** 规范化时先去掉的 scheme 前缀（长的在前：{@code jar:file:} 要先去掉 {@code jar:}）。 */
    private static final String[] SCHEME_PREFIXES = {"union:", "jar:", "file:"};

    /** 判「这是 Forge 的 mod 类加载器」用的类名片段（<b>大小写敏感</b>，见 {@link #hitForgeLoader}）。 */
    private static final String[] FORGE_LOADER_MARKERS = {"Launch", "SecureJarHandler", "ModLauncher"};

    /** ModList 探针只跑一次；拿不到就永久跳过（{@code null} = 还没试过，{@code []} = 试过但没有）。 */
    private static volatile String[] modListJarNames;

    private HotfixPatchWhitelist() {
        // 纯判定类，没有实例
    }

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

    /** 完整判据（不带类加载器名；等价于 {@code loaderName == null}）。 */
    public static Decision judge(String className, String codeSource, String modsRoot) {
        return judge(className, codeSource, modsRoot, null);
    }

    /**
     * 完整判据：白名单 ＋ 来源 ＋（兜底）类加载器／ModList。
     *
     * @param className  源级类名（{@code examplemod.util.EntityUtil}），允许 {@code $} 内部类
     * @param codeSource {@code getProtectionDomain().getCodeSource().getLocation().toString()} 的
     *                   <b>原样字符串</b>（{@code union:/…}/{@code jar:file:/…}/{@code file:/…}）；
     *                   {@code null}/空 = 拿不到
     * @param modsRoot   {@code mods\} 的绝对路径；{@code null}/空 = 运行期拿不到
     *                   （退化成"路径里必须有一级叫 mods"）
     * @param loaderName {@code cls.getClassLoader().getClass().getName()}（可为 {@code null}）
     * @return 判定结果（{@link Decision#allowed} ＋ 机器可判的 {@link Decision#reason}）
     */
    public static Decision judge(String className, String codeSource, String modsRoot, String loaderName) {
        final String raw = codeSource == null ? null : codeSource.trim();
        // ---- 拒 1：硬白名单（类名层面，与来源无关）
        final String hit = hitPrefix(className);
        if (hit != null) {
            return new Decision(false, "whitelisted:" + hit, raw, normalizeSource(raw), modsRoot, loaderName);
        }
        // ---- 拒 2：拿不到 code source（规格："拿不到就拒"，不猜、不降级）
        if (raw == null || raw.isEmpty()) {
            return new Decision(false, "no_code_source", raw, "", modsRoot, loaderName);
        }
        final String norm = normalizeSource(raw);
        // ---- 拒 3：规范化之后什么都没剩
        if (norm.isEmpty()) {
            return new Decision(false, "bad_code_source", raw, norm, modsRoot, loaderName);
        }
        final Decision deny = refuseByPath(raw, norm, modsRoot, loaderName);
        if (deny != null) {
            return deny;
        }
        // ---- 收 1：在 mods 根之下
        final String rootNorm = normalizeSource(modsRoot);
        if (!rootNorm.isEmpty() && underRoot(norm, rootNorm)) {
            return new Decision(true, "ok", raw, norm, modsRoot, loaderName);
        }
        // ---- 收 2：按 \ 切段后，有一段等于 mods（逐段比，不是 substring）
        if (containsSegment(norm, MODS_SEGMENT)) {
            // ① mods 根拿不到 ⇒ 这就是唯一的来源证据（沿用旧 token，日志口径不变）
            // ② mods 根拿得到但对不上 ⇒ 按路径段放行（规格第 ① 条的字面要求，见类注释的告警）
            return new Decision(true, rootNorm.isEmpty() ? "ok_mods_segment" : "ok_mods_path",
                    raw, norm, modsRoot, loaderName);
        }
        // ---- 收 3：Forge 的 mod 类加载器（路径形态没见过时的兜底）
        final String marker = hitForgeLoader(loaderName);
        if (marker != null) {
            return new Decision(true, "ok_forge_loader", raw, norm, modsRoot, loaderName);
        }
        // ---- 收 4：jar 文件名出现在 ModList 的文件信息里（拿不到就跳过）
        final String jar = jarFileName(norm);
        if (jar != null && modListContains(jar)) {
            return new Decision(true, "ok_modlist", raw, norm, modsRoot, loaderName);
        }
        // ---- 拒 8：都不成立
        return new Decision(false, "outside_mods_dir", raw, norm, modsRoot, loaderName);
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

    /** 拒 4–7（路径形态层面的硬拒绝）。命中返回 Decision，否则 {@code null}。 */
    static Decision refuseByPath(String raw, String norm, String modsRoot, String loaderName) {
        final String rawLower = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
        if (rawLower.startsWith("jrt:") || norm.startsWith("jrt:") || norm.contains("jrt:")) {
            return new Decision(false, "refuse_jrt", raw, norm, modsRoot, loaderName);
        }
        if (containsSegment(norm, "libraries")) {
            return new Decision(false, "refuse_libraries", raw, norm, modsRoot, loaderName);
        }
        if (containsSegment(norm, "jars")) {
            return new Decision(false, "refuse_jars", raw, norm, modsRoot, loaderName);
        }
        if (looksLikeVersionJar(norm)) {
            return new Decision(false, "refuse_version_jar", raw, norm, modsRoot, loaderName);
        }
        return null;
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
     * 是否形如 {@code …\versions\&lt;x&gt;\&lt;x&gt;.jar}（原版/Forge 的版本 jar，绝不许改）。
     *
     * <p>注意：{@code …\versions\&lt;x&gt;\mods\any.jar} 是<b>合法</b>的（版本隔离的实例），不命中。
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

        /** 机器可判的原因：{@code ok}/{@code ok_mods_path}/{@code whitelisted:net.minecraft.}/{@code outside_mods_dir}/… */
        public final String reason;

        /** code source 的<b>原样</b>字符串（{@code union:/…} 就照抄；可能为 {@code null}）。 */
        public final String raw;

        /** 规范化之后的路径（小写、反斜杠；可能为空串）。 */
        public final String normalized;

        /** 规范化之后的 mods 根（可能为空串）。 */
        public final String modsRoot;

        /** 类加载器类名（可能为 {@code null}）。 */
        public final String loader;

        Decision(boolean allowed, String reason, String raw, String normalized, String modsRoot, String loader) {
            this.allowed = allowed;
            this.reason = reason;
            this.raw = raw;
            this.normalized = normalized == null ? "" : normalized;
            this.modsRoot = modsRoot == null ? "" : modsRoot;
            this.loader = loader;
        }

        /** 一行日志用的摘要（不含空格，可直接塞进 {@code detail=}）。 */
        public String brief() {
            return "allowed=" + allowed + " reason=" + reason;
        }

        @Override
        public String toString() {
            return brief() + " raw=" + (raw == null ? "?" : raw)
                    + " normalized=" + (normalized.isEmpty() ? "?" : normalized)
                    + " mods_root=" + (modsRoot.isEmpty() ? "?" : modsRoot)
                    + " loader=" + (loader == null ? "?" : loader);
        }
    }
}
