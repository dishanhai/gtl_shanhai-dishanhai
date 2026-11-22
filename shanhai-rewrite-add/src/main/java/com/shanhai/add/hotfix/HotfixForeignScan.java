package com.shanhai.add.hotfix;

import java.lang.instrument.Instrumentation;
import java.security.CodeSource;
import java.security.ProtectionDomain;

/**
 * <b>启动期只读自检</b>：数一数"场上有没有 agent 注入的东西"。
 *
 * <h2>它回答的那个问题（用户当场点名的盲区）</h2>
 * <pre>
 *   「你防止我们自己的agent，那是不是会漏掉敌人的agent？」
 * </pre>
 * 敌人用 {@code Unsafe} / {@code MethodHandles.Lookup#defineClass} / {@code -javaagent} 现算现定义的类，
 * 特征就是 <b>没有 {@code CodeSource}</b>。这一行把它们<b>数额 ＋ 样例包名</b>打出来 ——
 * 于是"场上到底有没有 agent 注入的代码"变成一次可判的读数，而不是靠猜。
 *
 * <h2>这一行长什么样</h2>
 * <pre>
 *   [SHANHAI-HOTFIX] foreign_nocode classes=0 scanned=18422 whitelisted=17901 with_code_source=521
 *        no_domain=0 samples=[] note=非白名单且CodeSource为空（=agent注入的候选）
 * </pre>
 *
 * <h2>🔴 只读</h2>
 * 不 {@code retransform}、不 {@code redefine}、不缓存 {@code Class} 引用、不改任何东西 ——
 * 它只在 {@link HotfixAutoCounterGlue#ensure(String)} 里被调一次（同一 JVM 一次）。
 *
 * <p>依赖面：{@code java.*}，所以离线自检能直接跑同一份字节码。
 */
public final class HotfixForeignScan {

    /** 样例包名最多列几个（只进日志，不影响判定）。 */
    public static final int MAX_SAMPLES = 6;

    /** 同一 JVM 只扫一次（进世界会被调多次）。 */
    private static volatile String lastLine;

    private HotfixForeignScan() {
    }

    /**
     * 扫一遍已加载类，返回一行读数（第一次调用真的扫；之后返回同一行）。
     *
     * @param inst           {@code Instrumentation}（{@code null} ⇒ 返回 {@code unavailable} 那行）
     * @param selfCodeSource 我们自己的 code source 原样字符串（{@code null} = 未知）
     * @param selfLoader     我们自己的加载器（{@code null} = 未知）
     * @param mcLoader       一个原版类的加载器（{@code null} = 未知）
     * @return 一行（不含 {@code [SHANHAI-HOTFIX]} 前缀）
     */
    public static String scanOnce(Instrumentation inst, String selfCodeSource,
                                  ClassLoader selfLoader, ClassLoader mcLoader) {
        final String cached = lastLine;
        if (cached != null) {
            return cached;
        }
        synchronized (HotfixForeignScan.class) {
            if (lastLine != null) {
                return lastLine;
            }
            lastLine = scan(inst, selfCodeSource, selfLoader, mcLoader);
            return lastLine;
        }
    }

    /**
     * 真的扫一遍（不做"只扫一次"的记忆；离线自检要反复调）。
     *
     * @return 一行读数
     */
    public static String scan(Instrumentation inst, String selfCodeSource,
                              ClassLoader selfLoader, ClassLoader mcLoader) {
        if (inst == null) {
            return "foreign_nocode classes=? scanned=0 reason=no_instrumentation";
        }
        final Class<?>[] all;
        try {
            all = inst.getAllLoadedClasses();
        } catch (Throwable t) {
            return "foreign_nocode classes=? scanned=0 reason=getAllLoadedClasses_" + t.getClass().getSimpleName();
        }
        if (all == null) {
            return "foreign_nocode classes=? scanned=0 reason=null_array";
        }
        int scanned = 0;
        int whitelisted = 0;
        int withSource = 0;
        int noDomain = 0;
        int foreignNoCode = 0;
        final StringBuilder samples = new StringBuilder(64);
        for (int i = 0; i < all.length; i++) {
            final Class<?> c = all[i];
            if (c == null) {
                continue;
            }
            if (c.isArray() || c.isPrimitive() || c.isHidden()) {
                continue;   // 数组/基本类型没有"类"可言，也不可能是谁的代码；hidden 是 JDK 内部的 lambda 形态
            }
            scanned++;
            final String name;
            try {
                name = c.getName();
            } catch (Throwable t) {
                continue;
            }
            if (HotfixPatchWhitelist.isNeverPatch(name)) {
                whitelisted++;
                continue;
            }
            final String source = codeSourceOf(c);
            if (source != null && !source.isEmpty()) {
                withSource++;
                continue;
            }
            final ProtectionDomain pd;
            try {
                pd = c.getProtectionDomain();
            } catch (Throwable t) {
                noDomain++;
                countSample(samples, name);
                foreignNoCode++;
                continue;
            }
            if (pd == null) {
                noDomain++;
            }
            foreignNoCode++;
            countSample(samples, name);
        }
        final StringBuilder sb = new StringBuilder(220);
        sb.append("foreign_nocode classes=").append(foreignNoCode)
                .append(" scanned=").append(scanned)
                .append(" whitelisted=").append(whitelisted)
                .append(" with_code_source=").append(withSource)
                .append(" no_domain=").append(noDomain)
                .append(" samples=[").append(samples).append(']')
                .append(" loader_keys=").append(loaderKeys(foreignNoCode, selfLoader, mcLoader))
                .append(" note=非白名单且CodeSource为空（=agent注入的候选，判据见 HotfixPatchWhitelist）");
        return sb.toString();
    }

    /**
     * 一个类的 code source 原样字符串（拿不到 ⇒ {@code null}）。
     *
     * <p>三步都吞异常：{@code getProtectionDomain()} 在某些动态类上会抛
     * {@code SecurityException} 或返回 {@code null}。
     */
    public static String codeSourceOf(Class<?> cls) {
        if (cls == null) {
            return null;
        }
        try {
            final ProtectionDomain pd = cls.getProtectionDomain();
            if (pd == null) {
                return null;
            }
            final CodeSource cs = pd.getCodeSource();
            if (cs == null || cs.getLocation() == null) {
                return null;
            }
            return cs.getLocation().toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 单个类的<b>识别信号</b>（报告里"agent 注入类的识别"那一节逐条列的就是这几项）。
     *
     * <pre>
     *   cls=examplemod.injected.Helper
     *   code_source=none                 （能区分：agent/Unsafe/Lookup 定义的类基本都没它）
     *   domain=none                      （能区分：连 ProtectionDomain 都没有）
     *   loader=null|bootstrap|jdk.internal.loader.ClassLoaders$AppClassLoader|… 
     *   loader_name=<ClassLoader#getName() 或 ?>
     *   unnamed_module=true|false        （能区分：mod 类是 named module；动态类通常 unnamed）
     *   assignable_signature=…           （不能区分：签名看不出谁定义的）
     *   in_white_list=true|false
     * </pre>
     *
     * @return 一行（不含空格问题的 {@code key=value} 序列）
     */
    public static String describe(Class<?> cls) {
        if (cls == null) {
            return "cls=?";
        }
        final StringBuilder sb = new StringBuilder(160);
        final String name = safeName(cls);
        sb.append("cls=").append(name);
        sb.append(" code_source=").append(orNone(codeSourceOf(cls)));
        String domain;
        try {
            domain = cls.getProtectionDomain() == null ? "none" : "present";
        } catch (Throwable t) {
            domain = "threw_" + t.getClass().getSimpleName();
        }
        sb.append(" domain=").append(domain);
        ClassLoader loader = null;
        try {
            loader = cls.getClassLoader();
        } catch (Throwable ignored) {
            loader = null;
        }
        sb.append(" loader=").append(loader == null ? "bootstrap" : loader.getClass().getName());
        sb.append(" loader_name=").append(loaderNameOf(loader));
        sb.append(" jdk_loader=").append(HotfixPatchWhitelist.isJdkLoader(loader));
        sb.append(" unnamed_module=").append(unnamedModule(cls));
        sb.append(" in_white_list=").append(HotfixPatchWhitelist.isNeverPatch(name));
        return sb.toString();
    }

    // ------------------------------------------------------------------ 内部

    private static void countSample(StringBuilder samples, String className) {
        // 只留"包名."（最多两层），且去重、限条数
        final String pkg = packageOf(className);
        if (pkg.isEmpty()) {
            return;
        }
        final String token = pkg;
        if (samples.indexOf(token) >= 0) {
            return;
        }
        if (samples.length() > 0 && samples.toString().split(",").length >= MAX_SAMPLES) {
            return;
        }
        if (samples.length() > 0) {
            samples.append(',');
        }
        samples.append(token);
    }

    private static String packageOf(String className) {
        if (className == null || className.isEmpty()) {
            return "";
        }
        final int dot = className.lastIndexOf('.');
        if (dot <= 0) {
            return "(default)";
        }
        final String pkg = className.substring(0, dot);
        // 再往上取一层，否则一堆 x.y.z 的叶子包会占满样例位
        final int dot2 = pkg.lastIndexOf('.');
        if (dot2 > 0 && pkg.length() - dot2 <= 12) {
            final String upper = pkg.substring(0, dot2);
            final int dot3 = upper.lastIndexOf('.');
            return (dot3 > 0 ? upper.substring(0, dot3) : upper) + ".";
        }
        return pkg + ".";
    }

    private static String loaderKeys(int foreignNoCode, ClassLoader selfLoader, ClassLoader mcLoader) {
        if (foreignNoCode == 0) {
            return "none";
        }
        return "self=" + (selfLoader == null ? "?" : selfLoader.getClass().getName())
                + ",mc=" + (mcLoader == null ? "?" : mcLoader.getClass().getName());
    }

    private static String unnamedModule(Class<?> cls) {
        try {
            final Module m = cls.getModule();
            return m == null ? "?" : String.valueOf(m.isNamed() ? "false" : "true");
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String safeName(Class<?> cls) {
        try {
            final String n = cls.getName();
            return n == null ? "?" : n;
        } catch (Throwable t) {
            return "?";
        }
    }

    /** {@code ClassLoader#getName()}（Java 9+）；拿不到写 {@code ?}。 */
    public static String loaderNameOf(ClassLoader loader) {
        if (loader == null) {
            return "bootstrap";
        }
        try {
            final String n = loader.getName();
            return n == null || n.isEmpty() ? "?" : n;
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String orNone(String s) {
        return s == null || s.isEmpty() ? "none" : s;
    }
}
