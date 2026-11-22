package com.shanhai.add.hotfix;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * ② 🆕 <b>整链采集：深度 {@value #DEPTH} 帧</b>（"整链抹除"需要"顶端到最低端"，16 帧不够）。
 *
 * <h2>为什么另开一条采集，而不是把守卫那边的 16 改大</h2>
 * 守卫的 {@code ShanhaiViolationStack.MAX_FRAMES = 16} 是<b>日志格式</b>（{@code top=} 那一段的条数），
 * 改它会动守卫、改现成的现场判读口径。这里要的是同一时刻的<b>更深的栈</b>，
 * 而且在<b>热改这一侧</b>才能改（写入范围只允许 {@code com\shanhai\add\hotfix\}）。
 * ⇒ 本类在<b>违规现场那一拍</b>（{@code HotfixTriageTap#submitViolation}，跑在服务端线程、
 * 就在原版写入点的调用链上）自己抓一次 {@value #DEPTH} 帧，与守卫那 16 帧<b>互不影响</b>。
 *
 * <h2>帧方向（🔴 这一条最容易搞反）</h2>
 * Java 的栈数组 {@code [0]} 是<b>最里层</b>（＝写点）；用户说的"最顶端"是
 * 「最靠近框架」那一端（＝攻击的发起者）。所以：
 * <pre>
 *   输入（栈数组顺序）：[0]=LivingEntity#setHealth  [1]=…Util#forceHurt  [2]=…Hook#onTick  [3]=ServerLevel#tick
 *   输出（本类的 external）：…Hook#onTick → …Util#forceHurt → LivingEntity#setHealth
 *                            ↑顶端（靠框架，发起者）                    ↑最低端（写点）
 * </pre>
 * {@link Chain#external} 就是"从顶端到最低端"的外部帧列表（每个外部帧都在，<b>不做一帧截断</b>）。
 *
 * <h2>什么算外部帧</h2>
 * 判据只有一条：{@link HotfixFrameOwner#classify} 说它是 {@code foreign}
 * （方法名里的 modid 不是我们 ⇒ 强制外部；拿不到 modid ⇒ 走包名白名单原逻辑）。
 *
 * <p>依赖面：只有 {@code java.util} ＋ 同包两个纯 JDK 类；离线自检直接调。
 */
public final class HotfixChainCapture {

    /** 整链采集深度（用户口径："不要有上限"；这里是<b>一次抓栈的深度</b>，不是抹除数量上限）。 */
    public static final int DEPTH = 64;

    /** 抓栈时跳过的机制帧（它们永远在、永远没用；与我们自己的类不同，这些不属于任何一方）。 */
    private static final String[] MACHINERY_PREFIXES = {
            "java.lang.Thread",
            "java.lang.Throwable",
            "java.lang.StackWalker",
            "com.shanhai.add.hotfix.HotfixChainCapture",
            "com.shanhai.add.hotfix.HotfixTriageTap",
            "com.shanhai.add.guard.stack.ShanhaiViolationStack",
    };

    private HotfixChainCapture() {
        // 纯静态
    }

    // ═══════════════════════════════ 抓 ═══════════════════════════════

    /**
     * 抓<b>当前线程</b>的栈（最里层在前，深度 {@link #DEPTH}）。
     *
     * <p>⚠️ 必须在<b>违规现场那一拍</b>调（{@code HotfixTriageTap#submitViolation}），
     * 那时候当前线程就是"正在写受保护状态"的那条服务端线程；晚一拍再抓就只剩我们自己的帧。
     *
     * @return 帧表（可能为空）；<b>不抛</b>
     */
    public static List<HotfixTargetResolver.Frame> live() {
        final StackTraceElement[] stack;
        try {
            stack = Thread.currentThread().getStackTrace();
        } catch (Throwable t) {
            return new ArrayList<HotfixTargetResolver.Frame>(0);
        }
        final List<HotfixTargetResolver.Frame> out = new ArrayList<HotfixTargetResolver.Frame>(DEPTH);
        for (int i = 0; stack != null && i < stack.length && out.size() < DEPTH; i++) {
            final StackTraceElement e = stack[i];
            if (e == null || machinery(e.getClassName())) {
                continue;
            }
            final int line = e.getLineNumber() > 0 ? e.getLineNumber() : -1;
            out.add(new HotfixTargetResolver.Frame(e.getClassName(), e.getMethodName(), line, null,
                    e.getClassName() + "#" + e.getMethodName() + ":" + (line >= 0 ? line : "?")));
        }
        return out;
    }

    /**
     * 把现成的帧文本（守卫那 16 帧，或离线用例给的假栈）转成 {@link HotfixTargetResolver.Frame}。
     *
     * @param texts {@code 类#方法:行}（也接受带 {@code {标注}} 的形态）
     * @param cap   最多几条（{@code <=0} ⇒ {@link #DEPTH}）
     */
    public static List<HotfixTargetResolver.Frame> ofTexts(List<String> texts, int cap) {
        final int max = cap <= 0 ? DEPTH : cap;
        final List<HotfixTargetResolver.Frame> out =
                new ArrayList<HotfixTargetResolver.Frame>(texts == null ? 0 : Math.min(texts.size(), max));
        for (int i = 0; texts != null && i < texts.size() && out.size() < max; i++) {
            final HotfixTargetResolver.Frame f = HotfixTargetResolver.parseFrame(texts.get(i));
            if (f != null) {
                out.add(f);
            }
        }
        return out;
    }

    // ═══════════════════════════════ 组装整链 ═══════════════════════════════

    /** 一次整链采集的结果（不可变）。 */
    public static final class Chain {

        /** <b>从顶端（靠框架）到最低端（写点）</b>的外部帧（每一个都在，不去重、不截断）。 */
        public final List<HotfixTargetResolver.Frame> external;

        /** 外部帧的类名（按 {@link #external} 顺序、去重）。 */
        public final List<String> classes;

        /** 链上出现过的"注入者 modid"（按 {@link #external} 顺序、去重）。 */
        public final List<String> owners;

        /** 真正扫到的帧数（≤ {@link #DEPTH}）。 */
        public final int scanned;

        Chain(List<HotfixTargetResolver.Frame> external, List<String> classes, List<String> owners,
              int scanned) {
            this.external = external == null ? new ArrayList<HotfixTargetResolver.Frame>(0) : external;
            this.classes = classes == null ? new ArrayList<String>(0) : classes;
            this.owners = owners == null ? new ArrayList<String>(0) : owners;
            this.scanned = scanned;
        }

        public boolean empty() {
            return external.isEmpty();
        }

        /** 这个类在链上的帧（顶端 → 最低端；第一个＝入口，最后一个＝写点）。 */
        public List<HotfixTargetResolver.Frame> framesOf(String className) {
            final List<HotfixTargetResolver.Frame> out = new ArrayList<HotfixTargetResolver.Frame>(2);
            for (int i = 0; i < external.size(); i++) {
                final HotfixTargetResolver.Frame f = external.get(i);
                if (f != null && className != null && className.equals(f.className)) {
                    out.add(f);
                }
            }
            return out;
        }

        /** {@code chain_capture depth=64 external=<N> owners=[…] classes=[…]}（用户点名的逐字格式）。 */
        public String line() {
            return "chain_capture depth=" + DEPTH
                    + " external=" + external.size()
                    + " owners=[" + join(owners) + "]"
                    + " classes=[" + join(classes) + "]";
        }
    }

    /**
     * 把"栈数组顺序（最里层在前）"的帧表组装成整链。
     *
     * @param stackInnermostFirst 栈数组顺序的帧（{@code [0]} = 写点）
     * @param depth               最多看几帧（{@code <=0} ⇒ {@link #DEPTH}）
     */
    public static Chain build(List<HotfixTargetResolver.Frame> stackInnermostFirst, int depth) {
        final int max = depth <= 0 ? DEPTH : depth;
        final List<HotfixTargetResolver.Frame> ext = new ArrayList<HotfixTargetResolver.Frame>(max);
        final List<String> classes = new ArrayList<String>(4);
        final Set<String> classSet = new LinkedHashSet<String>();
        final List<String> owners = new ArrayList<String>(2);
        final Set<String> ownerSet = new LinkedHashSet<String>();
        int scanned = 0;
        String lastKey = null;
        if (stackInnermostFirst != null) {
            // ① 先按栈数组顺序走一遍（最里层 → 最外层），只留外部帧
            final List<HotfixTargetResolver.Frame> kept = new ArrayList<HotfixTargetResolver.Frame>(max);
            for (int i = 0; i < stackInnermostFirst.size() && scanned < max; i++) {
                final HotfixTargetResolver.Frame f = stackInnermostFirst.get(i);
                if (f == null) {
                    continue;
                }
                scanned++;
                final HotfixFrameOwner.Verdict v = HotfixFrameOwner.classify(f);
                if (!v.foreign()) {
                    continue;
                }
                // 相邻重复（同一类同一方法连着出现）只留一条：守卫的现场抓栈在某些注入点会重复
                final String key = f.className + "#" + f.methodName;
                if (key.equals(lastKey)) {
                    continue;
                }
                lastKey = key;
                kept.add(f);
                if (v.hasModid && !HotfixFrameOwner.SELF_MODID.equals(v.owner)) {
                    if (ownerSet.add(v.owner)) {
                        owners.add(v.owner);
                    }
                }
            }
            // ② 反过来 ⇒ 顶端（靠框架）→ 最低端（写点）
            for (int i = kept.size() - 1; i >= 0; i--) {
                final HotfixTargetResolver.Frame f = kept.get(i);
                ext.add(f);
                if (classSet.add(f.className)) {
                    classes.add(f.className);
                }
            }
        }
        return new Chain(ext, classes, owners, scanned);
    }

    /** 便捷重载：直接用一份文本帧表（守卫那 16 帧的兜底路径）。 */
    public static Chain fromTexts(List<String> texts, int depth) {
        return build(ofTexts(texts, depth), depth);
    }

    static boolean machinery(String className) {
        if (className == null) {
            return true;
        }
        for (int i = 0; i < MACHINERY_PREFIXES.length; i++) {
            if (className.startsWith(MACHINERY_PREFIXES[i])) {
                return true;
            }
        }
        return false;
    }

    /** 逗号连接（日志的 {@code owners=[…]}／{@code classes=[…]} 用）。 */
    static String join(List<String> list) {
        final StringBuilder sb = new StringBuilder(64);
        for (int i = 0; list != null && i < list.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(list.get(i));
        }
        return sb.toString();
    }
}
