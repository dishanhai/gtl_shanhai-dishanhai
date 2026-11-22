package com.shanhai.add.hotfix;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * ① 🆕 <b>帧分类：先拆方法名，再判类名</b>（用户 2026-11-21 点名的"强力模式"第一件）。
 *
 * <h2>为什么必须多这一层（旧判据的盲区）</h2>
 * 旧判据只看<b>声明类名</b>：{@link HotfixPatchWhitelist#isNeverPatch(String)} 命中
 * {@code net.minecraft.} 就整帧丢掉。而 Mixin 注入到原版类里的方法，
 * <b>声明类就是那个原版类</b>，方法名却带着"是谁注入的"：
 * <pre>
 *   net.minecraft.world.entity.LivingEntity#handler$abc000$examplemod$forceHurt:169
 *                                                  ↑ hash  ↑ modid   ↑ 原方法名
 *   net.minecraft.world.entity.LivingEntity#localvar$abc000$examplemod$forceHurt:169   ← LocalCapture 变体
 * </pre>
 * 这类帧恰恰是"已处理过的关键类被动了"的唯一证据（用户原话：
 * 「把我们已处理过的关键类也加入热改检测，检测到它的变化之后就往上溯源」）。
 * 旧判据把它们和"纯原版帧"一起丢掉 ⇒ 对手只要往原版类里注入，我们就"看不见它"。
 *
 * <h2>判据（2026-11-21 定的口径，逐条）</h2>
 * <ol>
 *   <li>{@code modid} 等于 {@link #SELF_MODID}（我们自己的 mod id）<b>或</b>声明类以
 *       {@link #SELF_CLASS_PREFIX} 开头 ⇒ <b>内部帧</b>（照旧跳过）；</li>
 *   <li>其余 ⇒ <b>强制外部帧</b>，{@code owner = modid}，<b>优先于</b>
 *       {@link HotfixPatchWhitelist#isNeverPatch(String)}
 *       （即：声明类是 {@code net.minecraft.*} 也一样算外部帧）；</li>
 *   <li>方法名里<b>拿不到 modid</b>（不是那两种注入形态） ⇒ <b>走原逻辑</b>
 *       （命中包名白名单 ⇒ 内部帧；否则外部帧，owner = 顶层包）。</li>
 * </ol>
 *
 * <h2>🔴 一个必须写清楚的边界：算外部帧 ≠ 拿这个原版类去改字节</h2>
 * {@code handler$…$examplemod$x} 的声明类是 {@code net.minecraft.…}，它<b>不是</b>对手的类：
 * 对手的代码在 {@code examplemod.*} 里，这个方法只是对手塞进原版类的入口。
 * ⇒ 本类只负责"把它认成外部帧、把 modid 交出去"，<b>绝不允许</b>把它变成补丁目标
 * （{@code HotfixTargetResolver#judgeFrame} 对"强制外部帧 ＋ 声明类白名单"返回
 * {@code frame_owner_foreign_class_whitelisted}）。否则就是"为了打对手去改原版类"，那是崩服的做法。
 * 它的价值在于：<b>owner（modid）</b>能进整链，让 ②③ 把这条链上该 mod 的类整类废掉。
 *
 * <h2>日志（用户点名的逐字格式）</h2>
 * <pre>
 *   frame_owner class=net.minecraft.world.entity.LivingEntity frame=handler$abc000$examplemod$forceHurt \
 *        owner=examplemod verdict=foreign
 *   frame_owner class=net.minecraft.world.entity.LivingEntity frame=handler$abc000$shanhai$probe \
 *        owner=shanhai verdict=self
 * </pre>
 * 去重（同一个 {@code 类#方法} 只打一次，上限 {@link #MAX_LOGGED}）—— 这条判定在每个违规现场都会跑，
 * 不去重会把日志刷满。
 *
 * <p>依赖面：只有 {@code java.util} ＋ 同包的白名单类；可被离线自检直接加载。
 */
public final class HotfixFrameOwner {

    /** Mixin 注入方法的固定前缀（普通注入）。 */
    public static final String PREFIX_HANDLER = "handler$";

    /** Mixin 注入方法的固定前缀（{@code @LocalCapture} 变体）。 */
    public static final String PREFIX_LOCALVAR = "localvar$";

    /** 我们自己的 mod id（帧分类里"内部帧"的判据之一）。 */
    public static final String SELF_MODID = "shanhai";

    /** 我们自己的包前缀（"内部帧"的另一条判据）。 */
    public static final String SELF_CLASS_PREFIX = "com.shanhai.";

    /** {@code verdict=} 的两个取值（用户点名的逐字取值）。 */
    public static final String VERDICT_FOREIGN = "foreign";

    public static final String VERDICT_SELF = "self";

    /** 注入形态的机器名（{@code owner_kind=}，只在诊断里用）。 */
    public static final String KIND_HANDLER = "handler";

    public static final String KIND_LOCALVAR = "localvar";

    /**
     * 🆕 2026-11-21（P2）：<b>不带 {@code handler$}／{@code localvar$} 前缀</b>的自有方法形态
     * （Mixin 对 {@code @Unique} 成员／直接加进目标类的方法只做 {@code <modid>$<name>} 改名）。
     */
    public static final String KIND_UNIQUE = "unique";

    /** {@code frame_owner} 一行最多打多少种（去重表上限；判据本身不设上限）。 */
    public static final int MAX_LOGGED = 256;

    /** 日志出口（游戏里由 {@code HotfixAutoCounter} 接上；离线自检接一个收集器）。 */
    public interface LineSink {
        void line(String text);
    }

    private static volatile LineSink sink;

    /** 已经打过日志的 {@code 类#方法 ⇒ verdict}（去重；超上限就整体清了重来，绝不无限长）。 */
    private static final Set<String> LOGGED = new LinkedHashSet<String>();

    private HotfixFrameOwner() {
        // 纯静态
    }

    /** 接上日志出口（幂等；传 {@code null} = 断开）。 */
    public static void bind(LineSink newSink) {
        sink = newSink;
    }

    /** 清空去重表（离线用例之间必须清，否则第二个用例看不到日志行）。 */
    public static void resetLogOnce() {
        synchronized (LOGGED) {
            LOGGED.clear();
        }
    }

    // ═══════════════════════════════ 方法名解析 ═══════════════════════════════

    /**
     * 一条 mixin 注入方法名拆出来的 owner。
     *
     * <p>只认两种形态：{@code handler$<hash>$<modid>$<name>} 与
     * {@code localvar$<hash>$<modid>$<name>}（{@code name} 里可以再有 {@code $}，例如 {@code x$synthetic}）。
     */
    public static final class Owner {

        /** {@code handler}／{@code localvar}。 */
        public final String kind;

        /** 注入唯一号（Mixin 算出来的那一串；只用于"这确实是一条注入方法"的证据，不参与判定）。 */
        public final String hash;

        /** 注入者的 mod id（判据的核心）。 */
        public final String modid;

        /** 原方法名（可能带 {@code $synthetic} 后缀）。 */
        public final String name;

        /** 原样方法名。 */
        public final String raw;

        Owner(String kind, String hash, String modid, String name, String raw) {
            this.kind = kind;
            this.hash = hash;
            this.modid = modid;
            this.name = name;
            this.raw = raw;
        }

        @Override
        public String toString() {
            return kind + ":" + modid + "/" + name;
        }
    }

    /**
     * 拆一条方法名。
     *
     * @param methodName 栈帧里的方法名
     * @return 拆得出来 ⇒ {@link Owner}；不是注入形态／字段不够 ⇒ {@code null}（调用方走原逻辑）
     */
    public static Owner parse(String methodName) {
        if (methodName == null || methodName.isEmpty()) {
            return null;
        }
        final String kind;
        final int from;
        if (methodName.startsWith(PREFIX_HANDLER)) {
            kind = KIND_HANDLER;
            from = PREFIX_HANDLER.length();
        } else if (methodName.startsWith(PREFIX_LOCALVAR)) {
            kind = KIND_LOCALVAR;
            from = PREFIX_LOCALVAR.length();
        } else {
            return null;
        }
        // hash$modid$name ⇒ 至少两个 '$'，且三段都非空
        final int first = methodName.indexOf('$', from);
        if (first <= from) {
            return null;
        }
        final int second = methodName.indexOf('$', first + 1);
        if (second <= first + 1 || second == methodName.length() - 1) {
            return null;
        }
        final String hash = methodName.substring(from, first);
        final String modid = methodName.substring(first + 1, second);
        final String name = methodName.substring(second + 1);
        if (!looksLikeModid(modid) || !looksLikeHash(hash) || name.isEmpty()) {
            return null;
        }
        return new Owner(kind, hash, modid, name, methodName);
    }

    /**
     * 🔴 <b>这条方法名算不算"我们自己的"</b>（{@code ours_kept} 的判据；2026-11-21 P2 补）。
     *
     * <p>三种形态<b>都</b>认（旧判据只认第一种）：
     * <ol>
     *   <li>{@code handler$<hash>$shanhai$<name>} —— 普通注入；</li>
     *   <li>{@code localvar$<hash>$shanhai$<name>} —— {@code @LocalCapture} 变体；</li>
     *   <li>{@code shanhai$<name>} —— 不带注入前缀的形态（Mixin 对 {@code @Unique} 成员只做
     *       {@code <modid>$<name>} 改名）。<b>旧判据对它返回 {@code null} ⇒ 一个都不数</b>。</li>
     * </ol>
     * <p>{@code name} 段后面再挂后缀（{@code …$mixin} 之类）不影响判定 —— {@link #parse(String)}
     * 把第二个 {@code $} 之后的全部当 {@code name}。
     *
     * <p>⚠️ 这里只回答"名字看起来是我们自己的"（用于<b>计数</b>与"绝不动它"的负对照），
     * <b>不</b>用来决定"能不能改这个类"——那条判据在 {@code HotfixTargetResolver#judgeFrame}。
     *
     * @param methodName 方法名（{@code null}/空 ⇒ {@code false}）
     */
    public static boolean isSelfName(String methodName) {
        if (methodName == null || methodName.isEmpty()) {
            return false;
        }
        final Owner o = parse(methodName);
        if (o != null) {
            return SELF_MODID.equals(o.modid);
        }
        final String marker = SELF_MODID + "$";     // "shanhai$"
        return methodName.startsWith(marker) || methodName.contains("$" + marker);
    }

    /** mod id 的字符面：字母数字 {@code _ - .}（够宽；Mixin 实际用的是 {@code [a-z0-9_-]}）。 */
    static boolean looksLikeModid(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            final boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /** 注入唯一号的字符面：小写字母数字（Mixin 是六位；这里只拒绝明显不是哈希的东西）。 */
    static boolean looksLikeHash(String s) {
        if (s == null || s.isEmpty() || s.length() > 32) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            final boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    // ═══════════════════════════════ 帧分类 ═══════════════════════════════

    /** 一帧的分类结论。 */
    public static final class Verdict {

        /** 声明类（{@code null}/空 = 坏帧）。 */
        public final String className;

        /** 方法名。 */
        public final String frameName;

        /** 这一帧的归属：注入帧 ⇒ 注入者 modid；普通帧 ⇒ 顶层包段。 */
        public final String owner;

        /** {@link #VERDICT_FOREIGN}／{@link #VERDICT_SELF}。 */
        public final String verdict;

        /** 方法名里真的拆出了 modid（⇒ 判据 1/2 生效，不是"走原逻辑"）。 */
        public final boolean hasModid;

        /** 这一帧是不是被"方法名里的 modid"强制判成外部帧（优先于包名白名单）。 */
        public final boolean forced;

        /** 声明类自己命中包名白名单（此时这一帧只能当证据，不许当补丁目标）。 */
        public final boolean classWhitelisted;

        /** 机器可判的原因：{@code modid_foreign}／{@code modid_self}／{@code whitelisted_class}／{@code not_whitelisted}。 */
        public final String reason;

        Verdict(String className, String frameName, String owner, String verdict,
                boolean hasModid, boolean forced, boolean classWhitelisted, String reason) {
            this.className = className == null ? "" : className;
            this.frameName = frameName == null ? "" : frameName;
            this.owner = owner == null ? "none" : owner;
            this.verdict = verdict;
            this.hasModid = hasModid;
            this.forced = forced;
            this.classWhitelisted = classWhitelisted;
            this.reason = reason;
        }

        public boolean foreign() {
            return VERDICT_FOREIGN.equals(verdict);
        }

        public boolean self() {
            return VERDICT_SELF.equals(verdict);
        }

        /**
         * 能不能拿<b>这个声明类</b>去改字节。
         *
         * <p>🔴 只有"外部帧 ＋ 声明类不在包名白名单"才可以 —— 原版类上的注入帧只交 owner。
         */
        public boolean patchableClass() {
            return foreign() && !classWhitelisted;
        }
    }

    /**
     * 判一帧（纯函数：不取栈、不打日志）。
     *
     * @param f 帧（{@code null} ⇒ 内部帧，什么都不做）
     */
    public static Verdict classify(HotfixTargetResolver.Frame f) {
        if (f == null) {
            return new Verdict("", "", "none", VERDICT_SELF, false, false, true, "bad_frame");
        }
        final String cn = f.className == null ? "" : f.className;
        final boolean ourClass = cn.startsWith(SELF_CLASS_PREFIX);
        final boolean whitelisted = HotfixPatchWhitelist.isNeverPatch(cn);
        final Owner o = parse(f.methodName);
        if (o == null) {
            // ---- 判据 3：拿不到 modid ⇒ 走原逻辑（与 2026-11-20 那一版逐字相同）
            if (ourClass || whitelisted) {
                return new Verdict(cn, f.methodName, ourClass ? SELF_MODID : HotfixChainTriage.ownerOf(cn),
                        VERDICT_SELF, false, false, whitelisted,
                        ourClass ? "modid_self" : "whitelisted_class");
            }
            return new Verdict(cn, f.methodName, HotfixChainTriage.ownerOf(cn),
                    VERDICT_FOREIGN, false, false, false, "not_whitelisted");
        }
        // ---- 判据 1：我们自己注入的（或我们自己类里的）⇒ 内部帧
        if (SELF_MODID.equals(o.modid) || ourClass) {
            return new Verdict(cn, f.methodName, SELF_MODID, VERDICT_SELF, true, false, whitelisted,
                    "modid_self");
        }
        // ---- 判据 2：别人的 modid ⇒ 强制外部帧（优先于包名白名单）
        return new Verdict(cn, f.methodName, o.modid, VERDICT_FOREIGN, true, true, whitelisted,
                "modid_foreign");
    }

    /** 这一帧算不算"内部帧"（白名单命中且没有外部 modid）。 */
    public static boolean isSelf(HotfixTargetResolver.Frame f) {
        return classify(f).self();
    }

    /** 这一帧是"别人的 modid 注入进来的"吗（① 的核心判据）。 */
    public static boolean isForcedForeign(HotfixTargetResolver.Frame f) {
        final Verdict v = classify(f);
        return v.forced && v.foreign();
    }

    // ═══════════════════════════════ 日志 ═══════════════════════════════

    /**
     * {@code frame_owner class=… frame=… owner=… verdict=foreign|self}（用户点名的逐字格式）。
     */
    public static String line(HotfixTargetResolver.Frame f) {
        return line(f, classify(f));
    }

    public static String line(HotfixTargetResolver.Frame f, Verdict v) {
        final Verdict vv = v == null ? classify(f) : v;
        return "frame_owner class=" + vv.className
                + " frame=" + vv.frameName
                + " owner=" + vv.owner
                + " verdict=" + vv.verdict;
    }

    /**
     * 打一行 {@code frame_owner}（去重；同一个 {@code 类#方法} 只打一次）。
     *
     * @return {@code true} = 这一行真的交出去了（{@code false} = 打过／没接出口／没有 modid 且不是外部帧）
     */
    public static boolean emit(HotfixTargetResolver.Frame f) {
        final Verdict v = classify(f);
        return emit(f, v);
    }

    public static boolean emit(HotfixTargetResolver.Frame f, Verdict v) {
        final LineSink s = sink;
        if (s == null || v == null) {
            return false;
        }
        // 只打"值得判读"的两类：① 方法名里真的拆出了 modid；② 外部帧但声明类在白名单里（这是新盲区）
        if (!v.hasModid && !v.forced) {
            return false;
        }
        final String key = v.className + "#" + v.frameName;
        synchronized (LOGGED) {
            if (LOGGED.contains(key)) {
                return false;
            }
            if (LOGGED.size() >= MAX_LOGGED) {
                LOGGED.clear();
            }
            LOGGED.add(key);
        }
        try {
            s.line(line(f, v));
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 已打过多少种（读数；离线自检用来判"去重真的生效"）。 */
    public static int loggedCount() {
        synchronized (LOGGED) {
            return LOGGED.size();
        }
    }

    /** 小写化（日志里比较 modid 用；不改写判定值）。 */
    static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
