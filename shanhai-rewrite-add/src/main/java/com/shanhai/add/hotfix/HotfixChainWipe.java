package com.shanhai.add.hotfix;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * ③④ 🆕 <b>整链抹除（无数量上限）＋ 指令开关（默认开）</b>。
 *
 * <h2>用户口径（逐字，2026-11-21）</h2>
 * <pre>
 *   「加一个更高等级的攻击手段：先溯源到攻击的最顶端发起者，然后把最顶端到最低端
 *     没有白名单的所有代码整个类全部废掉，可以指令开关，默认开启」
 *   「不要有上限」
 *   「稳定手法是不加 add，现在写的是 add 的代码，就允许不稳定」
 * </pre>
 * ⇒ ① 目标集合 = {@link HotfixChainCapture.Chain#external} 里<b>所有</b>外部帧的类
 * （非白名单的、不是我们的）；<b>不设条数上限</b>；
 * ② 默认档 = <b>整类</b>（该类的所有可改方法一律换成"安全默认返回"）；
 * ③ 不稳定由 add 层承担（用户明确接受），<b>不做</b>"跨 owner 才废"之类的豁免。
 *
 * <h2>🔴 唯一例外：防崩服（不是数量上限）</h2>
 * 整类 RETURN 的危险不在"改得多"，而在<b>某类方法返回 {@code null} 后被下游解引用</b>：
 * 实体 tick 被废 ⇒ {@code Level#m_46653_} 的 {@code catch (Throwable)} ⇒
 * {@code Ticking entity} CrashReport ⇒ {@code ReportedException} ⇒ <b>服务端直接崩</b>；
 * 同理类级 RETURN 让 {@code getBoundingBox()} 回 {@code null} ⇒ {@code Entity#move} NPE ⇒ 同上。
 *
 * <p>⇒ 判据（ASM 读该类的<b>全部</b>方法）：
 * <pre>
 *   所有方法都返回 void 或基本类型 ⇒ <b>允许整类 RETURN</b>（这类方法没法制造 null）
 *                                   mode=whole reason=all_void_or_primitive
 *   只要有一个方法返回引用／数组   ⇒ <b>只废三个方法</b>（写入方法 ＋ tick 方法 ＋ 入口方法）
 *                                   mode=triad reason=has_reference_returns
 * </pre>
 * 这三个方法按"它在这条链上的角色"定位：链上<b>最里层</b>那一帧 = 写入方法；
 * 最外层那一帧 = 入口方法；类里叫 {@code tick}／{@code m_8119_} 的方法 = tick 方法。
 *
 * <h2>④ 开关（{@code hotfix.chain_wipe}，默认 {@code true}）</h2>
 * {@code off} 时<b>只记录不写字节</b>：
 * <pre>
 *   chain_wipe_refused reason=chainwipe_off classes=[…]
 * </pre>
 * 且 {@code retransformClasses} 一次都不许调（离线自证里用假 JVM 的计数核对 = 0）。
 *
 * <h2>日志（逐字）</h2>
 * <pre>
 *   chain_capture depth=64 external=7 owners=[examplemod] classes=[a.b.C,…]
 *   chain_wipe class=examplemod.util.VoidOnly mode=whole methods=4 reason=all_void_or_primitive
 *   chain_wipe_write class=examplemod.util.VoidOnly ok=true retransforms=1 methods=4 rev=1 detail=ok
 *   chain_wipe_batch groups=5 ok=5 aborted=0
 *   chain_wipe_refused reason=chainwipe_off classes=[examplemod.util.VoidOnly,…]
 * </pre>
 *
 * <p>依赖面：{@code java.*} ＋ ASM（游戏自带；离线自检同一份字节码）＋ 同包几个纯 JDK 类。
 */
public final class HotfixChainWipe {

    /** ④ 配置键（用户点名）。 */
    public static final String KEY = "hotfix.chain_wipe";

    /** ④ 默认值：<b>开</b>（用户点名「默认开启」）。 */
    public static final boolean DEFAULT_ENABLED = true;

    /** {@code mode=} 的三个取值。 */
    public static final String MODE_WHOLE = "whole";

    public static final String MODE_TRIAD = "triad";

    public static final String MODE_NONE = "none";

    /** {@code reason=}（用户点名的两个值 ＋ 跳过的几个）。 */
    public static final String REASON_ALL_VOID_OR_PRIMITIVE = "all_void_or_primitive";

    public static final String REASON_HAS_REFERENCE_RETURNS = "has_reference_returns";

    public static final String REASON_NO_PATCHABLE_METHOD = "no_patchable_method";

    public static final String REASON_NO_TRIAD_METHOD = "no_triad_method";

    public static final String REASON_NO_CLASS_BYTES = "no_class_bytes";

    public static final String REASON_CLASS_NOT_LOADED = "class_not_loaded";

    public static final String REASON_CLASS_WHITELISTED = "class_whitelisted";

    public static final String REASON_CHAINWIPE_OFF = "chainwipe_off";

    public static final String REASON_EMPTY_CHAIN = "empty_chain";

    /** 构造器／静态初始化器不能这么换（与 {@code HotfixMethodBodyPatch} 同一条红线）。 */
    private static final String INIT = "<init>";

    private static final String CLINIT = "<clinit>";

    /** tick 方法的两个名字（{@code m_8119_} 是映射后的 {@code LivingEntity#tick}，{@code tick} 是开发名）。 */
    private static final String[] TICK_NAMES = {"m_8119_", "tick"};

    private static final AtomicBoolean ENABLED = new AtomicBoolean(readConfigDefault());

    private static final AtomicLong WIPED_CLASSES = new AtomicLong();
    private static final AtomicLong WIPED_METHODS = new AtomicLong();
    private static final AtomicLong REFUSED = new AtomicLong();
    private static final AtomicLong TRANSFORMS = new AtomicLong();
    private static final AtomicLong SUPPRESSED = new AtomicLong();

    /** 交接给 {@code HotfixKeyClassRestore} 的次数（白名单类上的外部 handler；我们不动那些类）。 */
    private static final AtomicLong HANDOFFS = new AtomicLong();

    /**
     * 日志限流（每个 {@code kind} 最多 1 条/秒）。
     *
     * <p>为什么需要：整链抹除挂在<b>每一个</b>违规现场上（守卫那条路 1/s），而
     * {@code chain_capture}／{@code chain_wipe_refused} 在"开关关着"或"战线拉得很长"时会变成刷屏源。
     * 判据一行都不丢（读数进 {@link #statusLine()}），只是不重复打同一类行。
     */
    private static final long LOG_INTERVAL_MILLIS = 1_000L;

    private static final Map<String, Long> LOG_LAST = new LinkedHashMap<String, Long>();

    private HotfixChainWipe() {
        // 纯静态
    }

    // ═══════════════════════════════ ④ 开关 ═══════════════════════════════

    /**
     * 读配置键的<b>兜底</b>值：{@code -Dhotfix.chain_wipe=false} 可以关掉默认值（给排查/离线留一条路）。
     *
     * <p>⚠️ 这里只读 {@code java.lang.System}：本类要能被离线自检直接加载，不许出现 Forge 配置类。
     * 游戏里的真值走 {@code config/shanhai_add-common.toml [hotfix] chain_wipe}（{@link #KEY}）：
     * 启动时由 {@link #applyConfiguredDefault} 应用一次；
     * {@code /shanhai_add hotfix chainwipe on|off} 会<b>同时</b>改配置与这里的 {@link #ENABLED}（热开关，不重启）。
     */
    private static boolean readConfigDefault() {
        try {
            final String v = System.getProperty(KEY);
            if (v == null || v.isEmpty()) {
                return DEFAULT_ENABLED;
            }
            final String t = v.trim().toLowerCase(Locale.ROOT);
            return "1".equals(t) || "true".equals(t) || "on".equals(t) || "yes".equals(t);
        } catch (Throwable ignored) {
            return DEFAULT_ENABLED;
        }
    }

    /** 开关现在是不是开的（默认开）。 */
    public static boolean enabled() {
        return ENABLED.get();
    }

    /** 热开关（返回<b>改之后</b>的值）。 */
    public static boolean setEnabled(boolean on) {
        ENABLED.set(on);
        if (!on) {
            REFUSED.incrementAndGet();
        }
        return ENABLED.get();
    }

    /** 配置文件的默认值是否已经应用过（只应用一次：命令是运行期覆盖，不许被下一次 bind 打回去）。 */
    private static final AtomicBoolean CONFIGURED = new AtomicBoolean(false);

    /**
     * 🆕 2026-11-21（②配置）：把 {@code config/shanhai_add-common.toml} 里 {@code [hotfix] chain_wipe}
     * 的值<b>应用一次</b>（默认 {@link #DEFAULT_ENABLED} = 开）。
     *
     * <p>为什么要"一次"：{@code HotfixAutoCounterGlue#bind()} 每次进世界都会跑；如果每次都拿配置覆盖，
     * 管理员刚用 {@code /shanhai_add hotfix chainwipe off} 关掉的开关会在下一次进世界被悄悄打开
     * ⇒ 配置是<b>启动默认值</b>，命令是<b>运行期覆盖</b>（与 {@code auto} 同一条纪律）。
     *
     * <p>🔴 2026-11-22：命令那一侧已经改成<b>先写配置／落盘、再调 {@link #setEnabled}</b>
     * （见 {@code HotfixCommand#chainWipeSet}），所以"启动默认值 vs 运行期覆盖"不会再分叉：
     * 文件里记着的永远是最后一次命令的结果。
     *
     * @return 这一次真的应用了吗（{@code false} = 之前应用过／离线环境没调过）
     */
    public static boolean applyConfiguredDefault(boolean on) {
        if (CONFIGURED.compareAndSet(false, true)) {
            ENABLED.set(on);
            return true;
        }
        return false;
    }

    /** 配置默认值应用过了吗（status 判读用）。 */
    public static boolean configured() {
        return CONFIGURED.get();
    }

    /** {@code chain_wipe=true default=true config_key=hotfix.chain_wipe …}（status 用）。 */
    public static String statusLine() {
        return "chain_wipe=" + ENABLED.get()
                + " chain_default=" + DEFAULT_ENABLED
                + " config_key=" + KEY
                + " configured=" + CONFIGURED.get()
                + " wiped_classes=" + WIPED_CLASSES.get()
                + " wiped_methods=" + WIPED_METHODS.get()
                + " handoffs=" + HANDOFFS.get()
                + " refused=" + REFUSED.get()
                + " log_suppressed=" + SUPPRESSED.get()
                + " retransforms=" + TRANSFORMS.get();
    }

    /** 离线／命令复位用（不动开关，只清计数）。 */
    public static void resetCounters() {
        WIPED_CLASSES.set(0L);
        WIPED_METHODS.set(0L);
        REFUSED.set(0L);
        TRANSFORMS.set(0L);
        SUPPRESSED.set(0L);
        synchronized (LOG_LAST) {
            LOG_LAST.clear();
        }
    }

    // ═══════════════════════════════ ③ 计划（纯函数，可离线验） ═══════════════════════════════

    /** 一个类的抹除计划（{@code mode=none} ⇒ 这一类不动）。 */
    public static final class Target {

        public final String className;

        /** {@link #MODE_WHOLE}／{@link #MODE_TRIAD}／{@link #MODE_NONE}。 */
        public final String mode;

        /** 机器可判的原因（{@link #REASON_ALL_VOID_OR_PRIMITIVE} 等）。 */
        public final String reason;

        /** 要废的方法（{@code [name, desc]}，按类里的顺序；{@code mode=none} 时为空）。 */
        public final List<String[]> methods;

        /** 类里一共有几个方法（含 {@code <init>}/{@code <clinit>}/abstract/native；读数用）。 */
        public final int declaredMethods;

        /** 其中有几个"返回引用／数组"的方法（{@code has_reference_returns} 的证据）。 */
        public final int referenceReturns;

        Target(String className, String mode, String reason, List<String[]> methods,
               int declaredMethods, int referenceReturns) {
            this.className = className == null ? "" : className;
            this.mode = mode;
            this.reason = reason;
            this.methods = methods == null ? new ArrayList<String[]>(0) : methods;
            this.declaredMethods = declaredMethods;
            this.referenceReturns = referenceReturns;
        }

        public boolean actionable() {
            return !MODE_NONE.equals(mode) && !methods.isEmpty();
        }

        /** {@code chain_wipe class=… mode=… methods=<N> reason=…}（用户点名的逐字格式）。 */
        public String line() {
            return "chain_wipe class=" + className
                    + " mode=" + mode
                    + " methods=" + methods.size()
                    + " reason=" + reason;
        }

        public String[] names() {
            final String[] out = new String[methods.size()];
            for (int i = 0; i < methods.size(); i++) {
                out[i] = methods.get(i)[0];
            }
            return out;
        }

        public String[] descs() {
            final String[] out = new String[methods.size()];
            for (int i = 0; i < methods.size(); i++) {
                out[i] = methods.get(i)[1];
            }
            return out;
        }
    }

    /**
     * 从 class 字节算抹除计划（<b>纯函数</b>：不取栈、不写字节、不打日志）。
     *
     * @param classBytes  这个类的字节（基线／磁盘那份；{@code null} ⇒ {@code mode=none}）
     * @param className   源级类名
     * @param classFrames 这个类在这条链上的帧（<b>顶端 → 最低端</b>；可空）
     */
    public static Target plan(byte[] classBytes, String className, List<HotfixTargetResolver.Frame> classFrames) {
        final List<String[]> none = new ArrayList<String[]>(0);
        final ClassNode cn = HotfixMethodBodyPatch.read(classBytes);
        if (cn == null) {
            return new Target(className, MODE_NONE, REASON_NO_CLASS_BYTES, none, 0, 0);
        }
        final List<MethodNode> patchable = new ArrayList<MethodNode>(cn.methods.size());
        int refReturns = 0;
        for (int i = 0; i < cn.methods.size(); i++) {
            final MethodNode mn = cn.methods.get(i);
            if (mn == null) {
                continue;
            }
            if (returnsReference(mn.desc)) {
                refReturns++;
            }
            if (isPatchable(mn)) {
                patchable.add(mn);
            }
        }
        if (patchable.isEmpty()) {
            return new Target(className, MODE_NONE, REASON_NO_PATCHABLE_METHOD, none,
                    cn.methods.size(), refReturns);
        }
        // ---- 判据：只要有一个方法返回引用／数组 ⇒ 不许整类 RETURN（防 null 解引用崩服）
        if (refReturns == 0) {
            final List<String[]> whole = new ArrayList<String[]>(patchable.size());
            for (int i = 0; i < patchable.size(); i++) {
                whole.add(new String[]{patchable.get(i).name, patchable.get(i).desc});
            }
            return new Target(className, MODE_WHOLE, REASON_ALL_VOID_OR_PRIMITIVE, whole,
                    cn.methods.size(), refReturns);
        }
        // ---- 否则只废三个角色：写入方法 ＋ tick 方法 ＋ 入口方法
        final Set<String> wanted = new LinkedHashSet<String>();
        final List<String> roles = new ArrayList<String>(3);
        if (classFrames != null && !classFrames.isEmpty()) {
            // 顶端（第一个）＝入口（最靠框架）；最低端（最后一个）＝写点
            final String entry = classFrames.get(0).methodName;
            final String write = classFrames.get(classFrames.size() - 1).methodName;
            if (write != null && !write.isEmpty()) {
                roles.add(write);
            }
            if (entry != null && !entry.isEmpty() && !entry.equals(write)) {
                roles.add(entry);
            }
        }
        for (int i = 0; i < TICK_NAMES.length; i++) {
            roles.add(TICK_NAMES[i]);
        }
        final List<String[]> triad = new ArrayList<String[]>(4);
        for (int r = 0; r < roles.size(); r++) {
            final String name = roles.get(r);
            for (int i = 0; i < patchable.size(); i++) {
                final MethodNode mn = patchable.get(i);
                if (name != null && name.equals(mn.name) && wanted.add(mn.name + mn.desc)) {
                    triad.add(new String[]{mn.name, mn.desc});
                }
            }
        }
        if (triad.isEmpty()) {
            return new Target(className, MODE_NONE, REASON_NO_TRIAD_METHOD, none,
                    cn.methods.size(), refReturns);
        }
        return new Target(className, MODE_TRIAD, REASON_HAS_REFERENCE_RETURNS, triad,
                cn.methods.size(), refReturns);
    }

    /** 能换方法体的方法（{@code <init>}/{@code <clinit>}/abstract/native/空方法体 一律不行）。 */
    static boolean isPatchable(MethodNode mn) {
        if (mn == null || mn.name == null || mn.name.isEmpty()) {
            return false;
        }
        if (INIT.equals(mn.name) || CLINIT.equals(mn.name)) {
            return false;
        }
        if ((mn.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
            return false;
        }
        return mn.instructions != null && mn.instructions.size() > 0;
    }

    /** 返回类型是不是引用／数组（⇒ 能制造 {@code null}）。 */
    static boolean returnsReference(String desc) {
        try {
            final Type t = Type.getReturnType(desc);
            return t.getSort() == Type.OBJECT || t.getSort() == Type.ARRAY;
        } catch (Throwable ignored) {
            // 描述符都读不懂 ⇒ 保守当成"可能有引用返回"（宁可走 triad，也不整类制造 null）
            return true;
        }
    }

    // ═══════════════════════════════ ③④ 落盘（整批） ═══════════════════════════════

    /** 一批的结果（{@code groups} = 去重后的目标类数）。 */
    public static final class Batch {

        public int groups;
        public int ok;
        public int aborted;
        public int skipped;

        /** 🆕 2026-11-21（P2 追加）：本批真的写成功的方法数（进 {@code PATCHED … methods=<N>} 的口径核对）。 */
        public int methods;

        /** 其中"本来就已经抹过"（计划里全在）几个 —— 它们<b>没有再 retransform</b>。 */
        public int already;

        /** 其中"白名单类 ⇒ 交接给 {@code HotfixKeyClassRestore}"几个（我们不碰原版类）。 */
        public int handoffs;

        /** 这一批<b>真的被抹掉</b>的类（调用方据此跳过普通单靶攻击，保证"一个类只 retransform 一次"）。 */
        public final Set<String> wiped = new LinkedHashSet<String>();

        /** 每个类一行（判读用；离线自检直接打印）。 */
        public final List<String> lines = new ArrayList<String>(8);

        /** 这个类在这一批里已经被整链抹掉了吗。 */
        public boolean covers(String className) {
            return className != null && wiped.contains(className);
        }

        public String line() {
            return "chain_wipe_batch groups=" + groups + " ok=" + ok + " aborted=" + aborted
                    + (already > 0 ? " already=" + already : "")
                    + (handoffs > 0 ? " handoff=" + handoffs : "");
        }
    }

    /**
     * 对一整条链做整链抹除（<b>无数量上限</b>）。
     *
     * <p>⚠️ 调用方必须已经拿到 {@link Instrumentation}，且必须<b>在工作线程</b>上调
     * （写字节要拿引擎那把锁；服务端线程上不许做）。
     *
     * <p>提交语义：<b>按类分组</b>（每个类一个子计划、一次 {@code retransform}），
     * 一个类失败不拖累其它类 —— 整链抹除太大，整批 abort 等于白干。
     *
     * @param inst  热改引擎的 {@code Instrumentation}
     * @param chain {@link HotfixChainCapture#build} 的产物
     */
    public static Batch wipe(Instrumentation inst, HotfixChainCapture.Chain chain) {
        final Batch out = new Batch();
        if (chain == null || chain.external.isEmpty()) {
            // 🆕 2026-11-21（P2，用户口径③）：<b>字段顺序固定成 {@code class=… reason=…} 相邻</b>。
            //   现场 20 条 skip 里 16 条是这一种，而它的老写法是 {@code chain_wipe_skip reason=empty_chain}
            //   （没有 class=）⇒ 任何 `chain_wipe_skip class=(\S+) reason=(\S+)` 的正则都会漏掉它们。
            chainLog("chain_wipe_skip class=* reason=" + REASON_EMPTY_CHAIN);
            return out;
        }
        // ---- ② 采集的读数（无论开关如何都要打：它是"整链到底有多长"的唯一证据）
        chainLogGated("chain_capture", chain.line());

        // ---- 目标类：链上所有外部类（去重，保持链序：顶端 → 最低端）
        //   两类分开：
        //     ① 非白名单 ⇒ 我们做整链抹除（本类）；
        //     ② 白名单（典型：net.minecraft.* 上的 handler$…$<modid>$x）⇒ **交接**给
        //        {@link HotfixKeyClassRestore}（原版类一个字节都不许我们动）。
        final List<String> targetClasses = new ArrayList<String>(chain.classes.size());
        final List<String> rawClasses = new ArrayList<String>(chain.classes.size());
        final List<String> handoffClasses = new ArrayList<String>(chain.classes.size());
        final Map<String, List<String>> handoffHandlers = new LinkedHashMap<String, List<String>>();
        for (int i = 0; i < chain.classes.size(); i++) {
            final String cn = chain.classes.get(i);
            final List<HotfixTargetResolver.Frame> frames = chain.framesOf(cn);
            if (frames.isEmpty()) {
                continue;
            }
            rawClasses.add(cn);
            final HotfixFrameOwner.Verdict v = HotfixFrameOwner.classify(frames.get(0));
            if (!v.patchableClass()) {
                handoffClasses.add(cn);
                handoffHandlers.put(cn, externalHandlers(frames));
                continue;
            }
            targetClasses.add(cn);
        }
        // ---- ④ 开关：off ⇒ 只记录不写字节（一次 retransform 都不许调；交接也不做）
        if (!enabled()) {
            REFUSED.incrementAndGet();
            // 用户点名的逐字格式；1/s 限流（关着开关时它会跟着每一个违规现场来一次）
            chainLogGated("chain_wipe_refused", "chain_wipe_refused reason=" + REASON_CHAINWIPE_OFF
                    + " classes=[" + HotfixChainCapture.join(rawClasses) + "]");
            return out;
        }
        if (inst == null) {
            chainLog("chain_wipe_skip class=* reason=no_instrumentation classes=["
                    + HotfixChainCapture.join(rawClasses) + "]");
            return out;
        }
        out.groups = targetClasses.size();
        // ---- ③-a 白名单类：**交接**（🔴 我们绝不在这类上做整类/整方法抹除）
        //   手术由 {@link HotfixKeyClassRestore} 负责（只清空外部 handler 方法体，我们自己的逐字节保留）。
        for (int i = 0; i < handoffClasses.size(); i++) {
            final String cn = handoffClasses.get(i);
            final List<String> handlers = handoffHandlers.get(cn);
            out.handoffs++;
            HANDOFFS.incrementAndGet();
            chainLog(handoffLine(cn, handlers));
            chainLog("chain_wipe_handoff_result class=" + cn + " "
                    + HotfixAutoCounter.chainHandoff(inst, cn, handlers));
        }
        // ---- ③-b 逐类：计划 → 白名单/加载门 → 一次写（每类一份子计划、一次 retransform）
        for (int i = 0; i < targetClasses.size(); i++) {
            final String cn = targetClasses.get(i);
            final Class<?> cls = HotfixAutoCounter.chainFindLoaded(inst, cn);
            if (cls == null) {
                out.skipped++;
                chainLog("chain_wipe_skip class=" + cn + " reason=" + REASON_CLASS_NOT_LOADED);
                continue;
            }
            final HotfixPatchWhitelist.Decision gate = HotfixAutoCounter.chainGate(cls, cn);
            if (gate == null || !gate.allowed) {
                out.skipped++;
                chainLog("chain_wipe_skip class=" + cn + " reason=gate_"
                        + (gate == null ? "bad_probe" : gate.reason));
                continue;
            }
            final byte[] bytes = HotfixAutoCounter.chainClassBytes(cls, cn);
            final Target target = plan(bytes, cn, chain.framesOf(cn));
            out.lines.add(target.line());
            chainLog(target.line());
            if (!target.actionable()) {
                out.skipped++;
                continue;
            }
            // 🔴 已经在计划里全在（这条链每拍都会重算一遍）⇒ 不再写第二次：否则每一个违规现场
            //    都会给同一个类再来一次 retransform（那不是"更强"，那是"每秒让 JVM 去优化一堆类"）。
            if (HotfixAutoCounter.chainAlreadyWiped(cn, target.names(), target.descs())) {
                out.ok++;
                out.already++;
                out.wiped.add(cn);
                chainLog("chain_wipe_skip class=" + cn + " reason=already_wiped methods="
                        + target.methods.size() + " retransforms=0");
                continue;
            }
            final String readings = HotfixAutoCounter.chainWipeWriteClass(inst, cls, cn,
                    target.names(), target.descs(), cn + "#" + target.names()[0]);
            chainLog("chain_wipe_write class=" + cn + " " + readings);
            out.lines.add("chain_wipe_write class=" + cn + " " + readings);
            final boolean ok = field(readings, "ok").startsWith("true");
            final int transforms = intField(readings, "retransforms");
            final int methods = intField(readings, "methods");
            TRANSFORMS.addAndGet(transforms);
            if (ok) {
                out.ok++;
                out.methods += methods;
                out.wiped.add(cn);
                WIPED_CLASSES.incrementAndGet();
                WIPED_METHODS.addAndGet(methods);
            } else {
                out.aborted++;
            }
        }
        chainLog(out.line());
        // 🆕 2026-11-21（最终版，用户拍板）：<b>整链抹除不再单独播</b> —— 它与 patch 合并成
        //   同一份计划、同一次写，播报也合并成"每类一行"，由
        //   {@code HotfixAutoCounter.writePlanLocked} 写成功后统一发
        //   （{@code PATCHED <owner> <类简名> <N> 方法（…）}）。
        //   (the old per-wave WIPED line was removed together with its helper)
        return out;
    }

    /**
     * 这个类在链上的<b>外部 handler 方法名</b>（交接内容；去重、保持出现顺序）。
     *
     * <p>只收"方法名里带非 shanhai modid"的那几个 —— 它们才是"对手塞进这个类的东西"。
     * 同一个类里的我们自己的 handler（{@code $shanhai$}）不在里面（手术由
     * {@link HotfixKeyClassRestore} 保证逐字节保留）。
     */
    static List<String> externalHandlers(List<HotfixTargetResolver.Frame> frames) {
        final Set<String> out = new LinkedHashSet<String>();
        for (int i = 0; frames != null && i < frames.size(); i++) {
            final HotfixTargetResolver.Frame f = frames.get(i);
            if (f == null) {
                continue;
            }
            final HotfixFrameOwner.Owner o = HotfixFrameOwner.parse(f.methodName);
            if (o != null && !HotfixFrameOwner.SELF_MODID.equals(o.modid)) {
                out.add(f.methodName);
            }
        }
        return new ArrayList<String>(out);
    }

    /**
     * {@code chain_wipe_handoff class=<原版类> reason=class_whitelisted handlers=[…] target=keyclass_restore}
     * （用户点名的逐字格式）。
     */
    static String handoffLine(String className, List<String> handlers) {
        return "chain_wipe_handoff class=" + className
                + " reason=" + REASON_CLASS_WHITELISTED
                + " handlers=[" + HotfixChainCapture.join(handlers) + "]"
                + " target=keyclass_restore";
    }

    // ═══════════════════════════════ 日志 ═══════════════════════════════

    /** 交一行 {@code [SHANHAI-HOTFIX]} 日志（出口在 {@code HotfixAutoCounter}；离线时由它收集）。 */
    static void chainLog(String line) {
        HotfixAutoCounter.chainLog(line);
    }

    /**
     * 限流版的 {@link #chainLog}（同一个 {@code kind} 最多 1 条/秒）。
     *
     * @return {@code true} = 真的交出去了
     */
    static boolean chainLogGated(String kind, String line) {
        final long now = System.currentTimeMillis();
        synchronized (LOG_LAST) {
            final Long last = LOG_LAST.get(kind);
            if (last != null && now - last.longValue() < LOG_INTERVAL_MILLIS) {
                SUPPRESSED.incrementAndGet();
                return false;
            }
            if (LOG_LAST.size() >= 16) {
                LOG_LAST.clear();
            }
            LOG_LAST.put(kind, Long.valueOf(now));
        }
        chainLog(line);
        return true;
    }

    /** 从读数串里取 {@code key=value} 的值（取不到 ⇒ {@code "?"}）。 */
    static String field(String readings, String key) {
        if (readings == null || key == null) {
            return "?";
        }
        final int at = readings.indexOf(key + "=");
        if (at < 0) {
            return "?";
        }
        final int from = at + key.length() + 1;
        int to = from;
        while (to < readings.length() && readings.charAt(to) != ' ') {
            to++;
        }
        return readings.substring(from, to);
    }

    static int intField(String readings, String key) {
        final String v = field(readings, key);
        try {
            return Integer.parseInt(v);
        } catch (Throwable ignored) {
            return -1;
        }
    }
}
