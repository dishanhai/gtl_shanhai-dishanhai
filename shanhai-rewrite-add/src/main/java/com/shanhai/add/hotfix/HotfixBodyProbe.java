package com.shanhai.add.hotfix;

import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.security.CodeSource;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * <b>方法体的廉价读数</b>（纯 JDK）：给"整链选靶"的打分器提供
 * "它是不是纯转发壳 / 有没有强度信息"这两类判据。
 *
 * <h2>为什么不用 ASM</h2>
 * 引擎是<b>纯 JDK ＋ 可离线跑</b>的那一层（见 {@code HotfixAutoCounterGlue} 的类注释）。
 * 这个类只需要回答"这个方法有几条指令、几个调用、几个分支、有没有数值常量"，
 * 用三十行 class-file 结构解析就够；引 ASM 反而会让 {@code HotfixChainTriage} 也跟着绑上
 * 一个"离线编不过"的依赖。
 *
 * <h2>字节从哪来</h2>
 * 按 {@code Class#getProtectionDomain().getCodeSource()} 去找<b>磁盘上那一份</b>
 * （{@code mods\*.jar} 或开发环境的 {@code build\classes} 目录）。
 * <ul>
 *   <li>⚠️ 为什么不用 {@code Instrumentation} 读"当前定义"：P1/P2 实测过 ——
 *       {@code retransformClasses} 交给 transformer 的永远是<b>初始基线</b>，
 *       读不到"当前定义"（见 {@code HotfixPatchReadback} 的类注释）。</li>
 *   <li>⚠️ 磁盘那一份是<b>没被 mixin 改过</b>的原版方法体：这正是我们想要的
 *       （"这个方法自己写了什么"，不是"谁又往它上面注入了什么"）。</li>
 *   <li>拿不到（{@code CodeSource} 为 {@code null} 的 agent/defineClass 类）⇒
 *       {@link HotfixChainTriage.BodyInfo#UNKNOWN}，打分时<b>中立</b>，绝不猜。</li>
 * </ul>
 *
 * <h2>代价</h2>
 * 每个 {@code 类#方法} 只读一次（缓存，{@link #MAX_CACHE} 上限）；一次 {@code pick} 最多 16 帧。
 * 读的是本地文件（游戏目录里的 jar），不在 tick 上做，且只在"有违规栈"时才发生。
 */
public final class HotfixBodyProbe implements HotfixChainTriage.BodyProbe {

    /** 缓存上限（{@code 类#方法} 条数）。超了就清最老的一半。 */
    public static final int MAX_CACHE = 256;

    /** 拿"已加载的类"的口子（与选靶共用 {@link HotfixTargetResolver.Lookup}）。 */
    private final HotfixTargetResolver.Lookup lookup;

    /** 字节来源（离线用例可以直接灌字节，不碰文件系统）。 */
    public interface ByteSource {

        /** @return class 字节；拿不到 ⇒ {@code null} */
        byte[] bytesOf(String className);
    }

    private final ByteSource source;

    /**
     * 🔴 缓存必须<b>跨实例</b>：生产路径每次都 {@code new HotfixBodyProbe(...)}，
     * 实例级缓存等于"每帧都去开一次 mods 的 jar"。键 {@code 类#方法}，同一 JVM 里磁盘那份不会变。
     */
    private static final Map<String, HotfixChainTriage.BodyInfo> CACHE = new LinkedHashMap<>();

    /** 生产路径：按 {@code CodeSource} 去磁盘读。 */
    public HotfixBodyProbe(HotfixTargetResolver.Lookup lookup) {
        this(lookup, null);
    }

    /**
     * @param lookup 按类名取已加载的 {@code Class}（{@code null} ⇒ 一律判"拿不到"）
     * @param source 字节来源（{@code null} ⇒ 用 {@link #diskBytes(Class, String)}）
     */
    public HotfixBodyProbe(HotfixTargetResolver.Lookup lookup, ByteSource source) {
        this.lookup = lookup;
        this.source = source;
    }

    @Override
    public HotfixChainTriage.BodyInfo bodyOf(String className, String methodName) {
        if (className == null || className.isEmpty() || methodName == null || methodName.isEmpty()) {
            return HotfixChainTriage.BodyInfo.UNKNOWN;
        }
        final String key = className + '#' + methodName;
        synchronized (CACHE) {
            final HotfixChainTriage.BodyInfo hit = CACHE.get(key);
            if (hit != null) {
                return hit;
            }
        }
        HotfixChainTriage.BodyInfo info = HotfixChainTriage.BodyInfo.UNKNOWN;
        try {
            byte[] bytes = null;
            if (source != null) {
                bytes = source.bytesOf(className);
            } else if (lookup != null) {
                final Class<?> cls = lookup.findLoaded(className);
                if (cls != null) {
                    bytes = diskBytes(cls, className);
                }
            }
            if (bytes != null && bytes.length > 0) {
                info = analyze(bytes, methodName);
            }
        } catch (Throwable t) {
            info = HotfixChainTriage.BodyInfo.UNKNOWN;   // 读不到 ⇒ 中立，绝不当成证据
        }
        synchronized (CACHE) {
            if (CACHE.size() >= MAX_CACHE) {
                final Iterator<String> it = CACHE.keySet().iterator();
                int drop = MAX_CACHE / 2;
                while (it.hasNext() && drop-- > 0) {
                    it.next();
                    it.remove();
                }
            }
            CACHE.put(key, info);
        }
        return info;
    }

    /** 缓存了多少条。 */
    public int cached() {
        synchronized (CACHE) {
            return CACHE.size();
        }
    }

    /** 清缓存（命令重来／离线用例之间）。 */
    public void reset() {
        synchronized (CACHE) {
            CACHE.clear();
        }
    }

    // ═══════════════════════════════ 字节来源 ═══════════════════════════════

    /**
     * 从 {@code CodeSource} 指向的那个位置读 class 字节（jar 里的条目，或目录里的文件）。
     *
     * @return 字节；拿不到 ⇒ {@code null}（<b>不抛</b>）
     */
    public static byte[] diskBytes(Class<?> cls, String className) {
        if (cls == null || className == null || className.isEmpty()) {
            return null;
        }
        try {
            final CodeSource cs = cls.getProtectionDomain() == null
                    ? null : cls.getProtectionDomain().getCodeSource();
            final URL loc = cs == null ? null : cs.getLocation();
            if (loc == null) {
                return null;
            }
            final File root = new File(loc.toURI());
            final String entry = className.replace('.', '/') + ".class";
            if (root.isDirectory()) {
                final File f = new File(root, entry);
                if (!f.isFile() || f.length() <= 0L || f.length() > MAX_CLASS_BYTES) {
                    return null;
                }
                return readAll(f);
            }
            try (JarFile jar = new JarFile(root)) {
                final JarEntry je = jar.getJarEntry(entry);
                if (je == null || je.getSize() > MAX_CLASS_BYTES) {
                    return null;
                }
                try (InputStream in = jar.getInputStream(je)) {
                    return readAll(in);
                }
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /** 一个 class 文件的上限（防呆：正常几 KB，超过 4 MB 一定不对劲）。 */
    private static final long MAX_CLASS_BYTES = 4L * 1024L * 1024L;

    /**
     * 🆕 2026-11-21（P2）：<b>从类自己的 classloader 读同名资源</b>（{@code /x/y/Z.class}）。
     *
     * <p>为什么需要这第三条来源：ModLauncher 的类 {@code CodeSource} 是
     * {@code union:/…/x.jar%23326!/}（**不是**文件路径）⇒ {@link #diskBytes} 里的
     * {@code new File(loc.toURI())} 必抛、只能返回 {@code null}。而基线快照只在
     * "我们第一次对它写字节"时才抓 ⇒ 整链抹除<b>第一次</b>碰到这种类时两份来源都是 null，
     * 现场表现就是整批 {@code chain_wipe … reason=no_class_bytes}、下一趟才成。
     * {@code TransformingClassLoader} 能正常提供 {@code .class} 资源，这条补上就没有那个空窗。
     *
     * @return 字节；拿不到 ⇒ {@code null}（<b>不抛</b>）
     */
    public static byte[] resourceBytes(Class<?> cls, String className) {
        if (cls == null || className == null || className.isEmpty()) {
            return null;
        }
        final String entry = "/" + className.replace('.', '/') + ".class";
        try {
            final java.io.InputStream in = cls.getResourceAsStream(entry);
            if (in == null) {
                return null;
            }
            try {
                final byte[] b = readAll(in);
                return b == null || b.length <= 0 ? null : b;
            } finally {
                try {
                    in.close();
                } catch (Throwable ignored) {
                    // 关不掉不影响已经读到的字节
                }
            }
        } catch (Throwable t) {
            return null;
        }
    }

    // ═══════════════════════════════ 三级字节回退（本包唯一实现） ═══════════════════════════════

    /** 字节来源标签：基线快照（{@code HotfixBaseline} 里"第一次见到这个类"抓的那份）。 */
    public static final String SRC_BASELINE = "baseline";

    /** 字节来源标签：{@code CodeSource} 指向的磁盘／jar（**mixin 之前**的原版字节）。 */
    public static final String SRC_DISK = "disk";

    /** 字节来源标签：类自己的 classloader 里的同名资源（ModLauncher 下唯一还活着的那条）。 */
    public static final String SRC_RESOURCE = "resource";

    /** 三路都拿不到（日志 {@code bytes=none}）。 */
    public static final String SRC_NONE = "none";

    /** 一次"按三级回退拿字节"的结论（{@link #chainBytes} 的产物；不可变、只读）。 */
    public static final class Sourced {

        /** {@link #SRC_BASELINE}／{@link #SRC_DISK}／{@link #SRC_RESOURCE}／{@link #SRC_NONE}。 */
        public final String source;

        /** 拿到的字节；{@code null} = 三路全空。只读，调用方不许改。 */
        public final byte[] bytes;

        Sourced(String source, byte[] bytes) {
            this.source = source == null ? SRC_NONE : source;
            this.bytes = bytes;
        }

        /** 真的拿到了非空字节。 */
        public boolean ok() {
            return bytes != null && bytes.length > 0;
        }

        /** 字节数（拿不到 ⇒ 0）。 */
        public int size() {
            return bytes == null ? 0 : bytes.length;
        }

        /**
         * 日志 {@code bytes=} 后面那一截：{@code baseline size=31902} / {@code none}。
         *
         * <p>🔴 这个字段就是"<b>真 0</b> 还是<b>没读到</b>"的分界线：{@code size>0} ⇒ 字节到手，
         * 后面数出来的 0 才是结论；{@code none} ⇒ 根本没读到，任何 0 都不算结论。
         */
        public String tag() {
            return ok() ? source + " size=" + bytes.length : SRC_NONE;
        }

        @Override
        public String toString() {
            return tag();
        }
    }

    private static final Sourced NOTHING = new Sourced(SRC_NONE, null);

    /**
     * 🔴 <b>三级字节回退</b>（2026-11-21 P2）—— 本包<b>唯一</b>的"按类拿 class 字节"实现。
     *
     * <p>顺序（任何一级拿到就用，后面的不再试）：
     * <ol>
     *   <li>{@code baseline} —— {@link HotfixBaseline#bytes(String)}（transformer 第一次回调收到的
     *       那份 = JVM 定义这个类时的初始字节，<b>含 mixin 注入的方法</b>）；</li>
     *   <li>{@code disk} —— {@link #diskBytes(Class, String)}（jar／目录里那一份，<b>mixin 之前</b>）；</li>
     *   <li>{@code resource} —— {@link #resourceBytes(Class, String)}（classloader 资源；
     *       ModLauncher 的 {@code union:/…jar%2326!/} CodeSource 下 {@code disk} 必抛，只有这条还在）。</li>
     * </ol>
     *
     * <p>为什么必须共用这一份：整链抹除那条线曾经<b>现场 50 次</b>打
     * {@code chain_wipe … reason=no_class_bytes}，根因就是只试了前两级；补上第三级之后才有字节。
     * 关键类还原（{@code HotfixKeyClassRestore#restore0}）走的是<b>同一个</b>方法 ——
     * 谁再加第四级，两条线一起变，不会出现"一条修好、另一条还在空跑"。
     *
     * <p>⚠️ 第 ①② 两级拿到的都是 <b>mixin 之前</b>的原版字节（磁盘/jar 里那一份），
     * 所以在那两级上数不到 {@code handler$…$shanhai$…} 是<b>真 0</b>，不是判据坏 ——
     * 日志里 {@code bytes=baseline|disk|resource} 正是用来区分这件事的。
     *
     * @param cls       目标类（{@code null} ⇒ 只可能命中 baseline）
     * @param className 源级类名（{@code net.minecraft.world.entity.LivingEntity}）
     * @return 结论（永不为 {@code null}；三路全空 ⇒ {@link Sourced#ok()} 为 {@code false}）。<b>绝不抛</b>
     */
    public static Sourced chainBytes(Class<?> cls, String className) {
        final String name = className == null || className.isEmpty()
                ? (cls == null ? null : cls.getName()) : className;
        // ---- ① 基线快照
        try {
            if (name != null && !name.isEmpty()) {
                final byte[] base = HotfixBaseline.bytes(name.replace('.', '/'));
                if (base != null && base.length > 0) {
                    return new Sourced(SRC_BASELINE, base);
                }
            }
        } catch (Throwable ignored) {
            // 快照拿不到 ⇒ 下一级
        }
        // ---- ② CodeSource 指向的磁盘／jar
        try {
            final byte[] disk = diskBytes(cls, name);
            if (disk != null && disk.length > 0) {
                return new Sourced(SRC_DISK, disk);
            }
        } catch (Throwable ignored) {
            // 读不到（union:/ 之类）⇒ 下一级
        }
        // ---- ③ classloader 资源
        try {
            final byte[] res = resourceBytes(cls, name);
            if (res != null && res.length > 0) {
                return new Sourced(SRC_RESOURCE, res);
            }
        } catch (Throwable ignored) {
            // 同上
        }
        return NOTHING;
    }

    private static byte[] readAll(File f) throws Exception {
        try (InputStream in = new java.io.FileInputStream(f)) {
            return readAll(in);
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(4096);
        final byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > MAX_CLASS_BYTES) {
                return null;
            }
        }
        return out.toByteArray();
    }

    // ═══════════════════════════════ class 文件解析 ═══════════════════════════════

    private static final int CP_UTF8 = 1;
    private static final int CP_INTEGER = 3;
    private static final int CP_FLOAT = 4;
    private static final int CP_LONG = 5;
    private static final int CP_DOUBLE = 6;

    /**
     * 数一个类的某个方法（同名重载取<b>指令数最多</b>的那一个）。
     *
     * @param bytes      class 字节
     * @param methodName 方法名（源级名；{@code <init>}/{@code <clinit>} 也可以）
     * @return 读数；解析不了／方法不存在 ⇒ {@link HotfixChainTriage.BodyInfo#UNKNOWN}
     */
    public static HotfixChainTriage.BodyInfo analyze(byte[] bytes, String methodName) {
        if (bytes == null || bytes.length < 16 || methodName == null || methodName.isEmpty()) {
            return HotfixChainTriage.BodyInfo.UNKNOWN;
        }
        try {
            final Reader r = new Reader(bytes);
            if (r.u4() != 0xCAFEBABEL) {
                return HotfixChainTriage.BodyInfo.UNKNOWN;
            }
            r.u2();                                  // minor
            r.u2();                                  // major
            final int cpCount = r.u2();
            final String[] utf8 = new String[cpCount];
            final int[] tags = new int[cpCount];
            for (int i = 1; i < cpCount; i++) {
                final int tag = r.u1();
                tags[i] = tag;
                switch (tag) {
                    case CP_UTF8:
                        utf8[i] = r.utf8();
                        break;
                    case CP_INTEGER:
                    case CP_FLOAT:
                        r.skip(4);
                        break;
                    case CP_LONG:
                    case CP_DOUBLE:
                        r.skip(8);
                        i++;                          // Long/Double 占两个槽
                        break;
                    case 7:                               // Class
                    case 8:                               // String
                    case 16:                              // MethodType
                    case 19:                              // Module
                    case 20:                              // Package
                        r.skip(2);
                        break;
                    case 15:                              // MethodHandle
                        r.skip(3);
                        break;
                    case 9:                               // Fieldref
                    case 10:                              // Methodref
                    case 11:                              // InterfaceMethodref
                    case 12:                              // NameAndType
                    case 17:                              // Dynamic
                    case 18:                              // InvokeDynamic
                        r.skip(4);
                        break;
                    default:
                        return HotfixChainTriage.BodyInfo.UNKNOWN;   // 不认识的常量池项 ⇒ 不猜
                }
            }
            r.u2();                                   // access_flags
            r.u2();                                   // this_class
            r.u2();                                   // super_class
            final int interfaces = r.u2();
            r.skip(2 * interfaces);
            skipMembers(r);                           // fields
            final int methods = r.u2();
            HotfixChainTriage.BodyInfo best = null;
            for (int m = 0; m < methods; m++) {
                r.u2();                               // access
                final String name = utf8At(r, utf8);
                final String desc = utf8At(r, utf8);
                final int attrs = r.u2();
                int insns = -1;
                int[] stats = null;
                for (int a = 0; a < attrs; a++) {
                    final String attrName = utf8At(r, utf8);
                    final long len = r.u4();
                    final int start = r.pos;
                    if ("Code".equals(attrName)) {
                        r.u2();                       // max_stack
                        r.u2();                       // max_locals
                        final int codeLen = (int) Math.min(Integer.MAX_VALUE, r.u4());
                        if (codeLen > 0 && codeLen <= r.len - r.pos) {
                            stats = scan(r.bytes, r.pos, codeLen, tags);
                            insns = stats[0];
                        }
                    }
                    r.pos = start + (int) len;        // 跳过这个属性（含 Code 的尾巴）
                    if (r.pos > r.len) {
                        return HotfixChainTriage.BodyInfo.UNKNOWN;
                    }
                }
                if (methodName.equals(name) && stats != null) {
                    final HotfixChainTriage.BodyInfo info = new HotfixChainTriage.BodyInfo(
                            true, desc, stats[0], stats[1], stats[2], stats[3], stats[4], stats[5]);
                    if (best == null || info.insns > best.insns) {
                        best = info;
                    }
                }
                if (insns < 0) {
                    // 没有 Code 属性（native/abstract）：不算错误，继续看下一个方法
                    continue;
                }
            }
            return best == null ? HotfixChainTriage.BodyInfo.UNKNOWN : best;
        } catch (Throwable t) {
            return HotfixChainTriage.BodyInfo.UNKNOWN;
        }
    }

    /** 跳过一段 field_info（access/name/desc/attributes）。 */
    private static void skipMembers(Reader r) {
        final int count = r.u2();
        for (int i = 0; i < count; i++) {
            r.skip(6);
            final int attrs = r.u2();
            for (int a = 0; a < attrs; a++) {
                r.skip(2);
                final long len = r.u4();
                r.skip((int) len);
            }
        }
    }

    private static String utf8At(Reader r, String[] utf8) {
        final int idx = r.u2();
        if (idx <= 0 || idx >= utf8.length) {
            return "";
        }
        final String s = utf8[idx];
        return s == null ? "" : s;
    }

    // ---- 指令扫描（返回 int[6]：insns, calls, condJumps, numericConsts, typeChecks, arith）

    private static int[] scan(byte[] code, int start, int len, int[] cpTags) {
        int insns = 0;
        int calls = 0;
        int condJumps = 0;
        int numericConsts = 0;
        int typeChecks = 0;
        int arith = 0;
        int pos = start;
        final int end = start + len;
        while (pos < end) {
            final int op = code[pos] & 0xFF;
            insns++;
            // ---- 分类
            if (op >= 0x99 && op <= 0xA6 || op == 0xC6 || op == 0xC7 || op == 0xAA || op == 0xAB) {
                condJumps++;
            } else if (op == 0xB6 || op == 0xB7 || op == 0xB8 || op == 0xB9 || op == 0xBA) {
                calls++;
            } else if (op == 0xC1) {
                typeChecks++;
            } else if (op == 0x62 || op == 0x63 || op == 0x66 || op == 0x67
                    || op == 0x6A || op == 0x6B || op == 0x6E || op == 0x6F
                    || op == 0x95 || op == 0x96 || op == 0x97 || op == 0x98) {
                arith++;                       // 浮点/双精度算术与比较 ⇒ "取值计算"
            } else if (op == 0x12 || op == 0x13) {
                // ldc / ldc_w：看常量池里那个项的类型
                int idx;
                if (op == 0x12) {
                    idx = code[pos + 1] & 0xFF;
                } else {
                    idx = ((code[pos + 1] & 0xFF) << 8) | (code[pos + 2] & 0xFF);
                }
                if (idx > 0 && idx < cpTags.length) {
                    final int tag = cpTags[idx];
                    if (tag == CP_INTEGER || tag == CP_FLOAT || tag == CP_LONG || tag == CP_DOUBLE) {
                        numericConsts++;
                    }
                }
            } else if (op >= 0x05 && op <= 0x08 || op == 0x09 || op == 0x0A
                    || (op >= 0x0B && op <= 0x0F)) {
                // iconst_2..iconst_5 / lconst_0..1 / fconst_0..2 / dconst_0..1
                numericConsts++;
            } else if (op == 0x10) {
                final int v = code[pos + 1];               // bipush（有符号）
                if (Math.abs(v) >= 2) {
                    numericConsts++;
                }
            } else if (op == 0x11) {
                final int v = (short) (((code[pos + 1] & 0xFF) << 8) | (code[pos + 2] & 0xFF));
                if (Math.abs(v) >= 2) {
                    numericConsts++;
                }
            }
            // ---- 前进
            final int advance = length(code, pos, start, op, end);
            if (advance <= 0) {
                break;                          // 结构坏了：就此打住，已数的照给
            }
            pos += advance;
        }
        return new int[]{insns, calls, condJumps, numericConsts, typeChecks, arith};
    }

    /**
     * 一条指令占几个字节（{@code tableswitch/lookupswitch/wide} 单独算对齐）。
     *
     * <p>🔴 2026-11-21 修：{@code tableswitch/lookupswitch} 后面的 {@code pad} 是<b>相对 code 数组起点</b>
     * 对齐的（JVM 规范：{@code (pc + 1) % 4}，{@code pc} 是<b>方法内</b>的字节偏移），
     * 不是相对"这段字节在 class 文件里的位置"。旧写法用 {@code pos}（文件内绝对偏移）取模 ⇒
     * 只要 code 数组起点不是 4 的倍数，pad 就算错、指令流当场走歪（实测：含 {@code switch} 的方法
     * 真值 12 条指令被数成 <b>2</b>）。
     *
     * <p>为什么这个错很贵：{@code insns}／{@code calls}／{@code condJumps} 三个数都从这条扫描来，
     * 而 {@link HotfixChainTriage.BodyInfo#pureForward()}（＝"纯转发壳"硬条件 3）看的正是
     * {@code calls <= 1 && condJumps == 0 && insns < 15} ⇒ 走歪之后真实动作帧会被判成转发壳而<b>不许改</b>。
     *
     * @param pos   当前指令的位置（<b>文件内</b>绝对偏移）
     * @param start code 数组起点的绝对偏移（对齐基准从这里算）
     */
    private static int length(byte[] code, int pos, int start, int op, int end) {
        if (op == 0xAA) {                       // tableswitch
            final int pad = (4 - (((pos - start) + 1) % 4)) % 4;
            int p = pos + 1 + pad;
            if (p + 12 > end) {
                return -1;
            }
            final int low = readInt(code, p + 4);
            final int high = readInt(code, p + 8);
            final long n = (long) high - (long) low + 1L;
            if (n < 0L || n > 65536L) {
                return -1;
            }
            return 1 + pad + 12 + (int) n * 4;
        }
        if (op == 0xAB) {                       // lookupswitch
            final int pad = (4 - (((pos - start) + 1) % 4)) % 4;
            int p = pos + 1 + pad;
            if (p + 8 > end) {
                return -1;
            }
            final int pairs = readInt(code, p + 4);
            if (pairs < 0 || pairs > 65536) {
                return -1;
            }
            return 1 + pad + 8 + pairs * 8;
        }
        if (op == 0xC4) {                       // wide
            if (pos + 1 >= end) {
                return -1;
            }
            final int next = code[pos + 1] & 0xFF;
            return next == 0x84 ? 6 : 4;        // wide iinc / wide <op>
        }
        return OP_LEN[op];
    }

    private static int readInt(byte[] b, int p) {
        return ((b[p] & 0xFF) << 24) | ((b[p + 1] & 0xFF) << 16) | ((b[p + 2] & 0xFF) << 8)
                | (b[p + 3] & 0xFF);
    }

    /** 每个 opcode 的字节长度（{@code tableswitch/lookupswitch/wide} 走 {@link #length}）。 */
    private static final byte[] OP_LEN = new byte[256];

    static {
        for (int i = 0; i < OP_LEN.length; i++) {
            OP_LEN[i] = 1;
        }
        final int[] two = {
                0x10, 0x12, 0x15, 0x16, 0x17, 0x18, 0x19, 0x36, 0x37, 0x38, 0x39, 0x3A, 0xA9, 0xBC
        };
        for (int i = 0; i < two.length; i++) {
            OP_LEN[two[i]] = 2;
        }
        final int[] three = {
                0x11, 0x13, 0x14, 0x84,
                0x99, 0x9A, 0x9B, 0x9C, 0x9D, 0x9E, 0x9F, 0xA0, 0xA1, 0xA2, 0xA3, 0xA4, 0xA5, 0xA6,
                0xA7, 0xA8,
                0xB2, 0xB3, 0xB4, 0xB5, 0xB6, 0xB7, 0xB8, 0xBB, 0xBD, 0xC0, 0xC1, 0xC6, 0xC7
        };
        for (int i = 0; i < three.length; i++) {
            OP_LEN[three[i]] = 3;
        }
        final int[] five = {0xB9, 0xBA, 0xC8, 0xC9};
        for (int i = 0; i < five.length; i++) {
            OP_LEN[five[i]] = 5;
        }
        OP_LEN[0xC5] = 4;                       // multianewarray
    }

    /** 极简大端读取器。 */
    private static final class Reader {

        final byte[] bytes;
        final int len;
        int pos;

        Reader(byte[] bytes) {
            this.bytes = bytes;
            this.len = bytes.length;
        }

        int u1() {
            if (pos + 1 > len) {
                throw new IllegalStateException("eof");
            }
            return bytes[pos++] & 0xFF;
        }

        int u2() {
            if (pos + 2 > len) {
                throw new IllegalStateException("eof");
            }
            final int v = ((bytes[pos] & 0xFF) << 8) | (bytes[pos + 1] & 0xFF);
            pos += 2;
            return v;
        }

        long u4() {
            if (pos + 4 > len) {
                throw new IllegalStateException("eof");
            }
            final long v = ((long) (bytes[pos] & 0xFF) << 24)
                    | ((long) (bytes[pos + 1] & 0xFF) << 16)
                    | ((long) (bytes[pos + 2] & 0xFF) << 8)
                    | (long) (bytes[pos + 3] & 0xFF);
            pos += 4;
            return v;
        }

        void skip(int n) {
            if (n < 0 || pos + n > len) {
                throw new IllegalStateException("eof");
            }
            pos += n;
        }

        String utf8() {
            final int l = u2();
            if (pos + l > len) {
                throw new IllegalStateException("eof");
            }
            final String s = new String(bytes, pos, l, java.nio.charset.StandardCharsets.UTF_8);
            pos += l;
            return s;
        }
    }
}
