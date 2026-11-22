package com.shanhai.add.hotfix;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.security.ProtectionDomain;

/**
 * <b>「patch 到底有没有进到运行中的类里」的判据与取证</b>。
 *
 * <h2>它回答的那个问题</h2>
 * 2026-11-08 的现场：我们的账本写着 {@code auto_counter … result=patched}，可是
 * 血量照样被写、栈里 {@code ExampleHelper#doHurt:375} 照样在调 {@code setHealth}。
 * ⇒ 「改成功了」这件事<b>从来没有人验过</b>，而用户的假设是
 * <b>它自己带的 agent 又把类织回去了</b>。
 *
 * <h2>🔴 先说实测结论：这条"读回"路的前提是错的（2026-11-08 13:0x 用真 JVM 量的）</h2>
 * 拿 {@code -javaagent} 起一个普通 JVM、加载一个 {@code mods\rbtarget.jar} 里的假 mod 类，
 * 量到三件事（实验与读数：{@code temp\hotfix\offline-readback\RbPremise.java}）：
 * <pre>
 *   P1  patch 生效后（行为已经从 true 变成 false）再做一次 retransform，
 *       transformer 收到的字节 = 1065 字节，**逐字等于 patch 前的基线、且不等于我们写下去的 994 字节**
 *       ⇒ {@code retransform} 给的是【初始 class 字节】，不是"上次定义后的版本"。
 *          （顺带：未改过的类，这份基线也与磁盘 class **不同**（常量池被重建）——
 *           这就是工程里"常量池合并"那条踩坑的真身，所以按偏移比字节本来就不成立。）
 *
 *   P2  同样是这次"只记指纹、永远 return null"的读回 —— 类被打回了基线：
 *       patch 后的 {@code allowHurt=false}，读完一遍又变回 {@code true}。
 *       ⇒ **一次"全 null"的 retransform 等于 undo**。所谓"只读读回"其实是<b>破坏性</b>的：
 *         它会亲手把每个 patch 撤掉。
 *
 *   P3  同一个类的第二个方法被 patch（还是从基线出发）时，第一个方法的 patch <b>没了</b>
 *       （{@code allowHurt} 回到 true、{@code strength} 变成 0）。
 *       ⇒ 这是「账本 patched、行为没变」的一条<b>新的、可判</b>的机制：
 *         同一类里第二个目标被 patch ⇒ 前一个目标被静默撤回（账本却是干净的）。
 * </pre>
 * 由此定下本类的两条铁律：
 * <ol>
 *   <li><b>{@link #capture} 不许在 patch 之后调用</b> —— 那只会在 1 tick 内把刚打上的补丁撤掉
 *       （第一版就是这么写的，离线套件里 {@code a2.verify_changed}／
 *       {@code a4.mods_segment_still_patches} 当场变红）。它只在"这个类本来就该是基线"的时刻
 *       才安全：<b>undo／还原之后</b>（此时 sample 就是当前状态，判 {@code reverted} 是真观测）。</li>
 *   <li><b>{@link #judge}</b>（三份指纹 → 三种 verdict）仍然是本类唯一的判据，
 *       而且它<b>与调用点无关</b>：哪天有了能拿到"当前字节"的路（例如改用
 *       {@code redefineClasses} 打补丁，基线会跟着走），同一份判据直接可用，不用重写。</li>
 * </ol>
 *
 * <h2>三份指纹与三种 verdict（{@link #judge} 就是全部判据，纯函数）</h2>
 * <pre>
 *   orig    = patch 之前 transformer 收到的字节（= 初始基线的指纹）
 *   patched = 我们 return 下去的那份字节
 *   live    = 采到的样本（在 undo 之后采：就是类当前的那份）
 *
 *   live == patched ⇒ {@link #VERDICT_PATCHED_LIVE}：类里确实是我们那份
 *                    ⇒ 行为没变就得去别处找原因（不是"没改进去"）
 *   live == orig    ⇒ {@link #VERDICT_REVERTED}    ：被织回去了（用户的假设成立）
 *   都不是          ⇒ {@link #VERDICT_UNKNOWN}     ：打全三份哈希，不猜
 * </pre>
 *
 * <h2>为什么只比哈希、不按偏移比</h2>
 * 同一份输入数组的 SHA-256 稳定；而"基线"与磁盘 class 之间常量池被重建过（P1 实测），
 * 偏移对齐毫无意义。长度只在"长度相同但哈希不同"这条边界上作为<b>辅助读数</b>打出来
 * （{@link Verdict#lenSameAsPatched}），绝不用来判相等。
 *
 * <h2>可选但没启用的"标记法"（写进报告作备选）</h2>
 * 原规格给过一条备选：patch 时在方法体里插唯一字符串常量（{@code "shanhai$patched$<n>"}，
 * {@code LDC} 之后立即 {@code POP} 的死代码），回读时搜字符串就能分辨"同一份补丁的另一种排布"。
 * <b>本项目不启用</b>，两条理由都是硬的：
 * ① {@code HotfixMethodBodyPatch} 的方法体被既有离线断言逐条锁死"正好两条指令"
 *    （{@code ICONST_0 / IRETURN}，见 {@code temp\hotfix\offline-autocounter\AcOfflineCheck.java}
 *    的 {@code a6.body_is_two_insns}）—— 插死代码会同时改掉"指令数"和"被 patch 的行为面"；
 * ② 就算插了也没用：P1 已经证明读回拿到的是<b>基线</b>，标记常量根本不在样本里。
 * ⇒ 需要标记法时，它是"改用 redefine 路线之后"的下一步手段，不是默认路径。
 *
 * <h2>依赖面</h2>
 * 只有 {@code java.*}（{@code java.lang.instrument}／{@code java.security}／{@code java.lang.reflect}）
 * —— 没有 ASM、没有 MC、没有 Forge、没有日志框架。
 * ⇒ 纯函数部（{@link #judge}／{@link #line}／{@link #fingerprint}）能被无参 JVM 直接驱动；
 * {@link #capture} 只需要一个真的 {@link Instrumentation}（离线用 {@code -javaagent} 送进来）。
 */
public final class HotfixPatchReadback {

    // ═══════════════════════════════ 常量 ═══════════════════════════════

    /** {@code live == patched}：类里确实是我们写下去的那份。 */
    public static final String VERDICT_PATCHED_LIVE = "patched_live";

    /** {@code live == orig}：被织回去了（用户的假设成立）。 */
    public static final String VERDICT_REVERTED = "reverted";

    /** 两份参考指纹都对不上（或指纹缺失／两份参考相同）：不猜。 */
    public static final String VERDICT_UNKNOWN = "unknown";

    /** 指纹用 SHA-256 的<b>前 8 字节</b> ⇒ 16 个十六进制字符。 */
    public static final int HASH_HEX_CHARS = 16;

    /** readback 的延迟：<b>约 1 tick</b>（20 TPS ⇒ 50 ms）。 */
    public static final long DELAY_MILLIS = 50L;

    /** 失败路径日志的限流口径（同一个 target 最快 1 s 一行）。 */
    public static final long LOG_MIN_INTERVAL_MILLIS = 1_000L;

    /** Mixin 生成的处理器方法前缀（1.20.1 / Mixin 0.8.x）。 */
    public static final String MIXIN_HANDLER_PREFIX = "handler$";

    /** Mixin 生成的局部变量捕获方法前缀。 */
    public static final String MIXIN_LOCALVAR_PREFIX = "localvar$";

    /** 数不出来的计数（{@code getDeclaredMethods} 抛了／类不可见）。 */
    public static final int COUNT_UNKNOWN = -1;

    /** {@link Verdict#reason}：两份指纹都对上各自该对的那份。 */
    public static final String REASON_OK = "ok";

    /** {@link Verdict#reason}：缺指纹（没拿到字节／哈希算不出来）⇒ 判不了。 */
    public static final String REASON_HASH_MISSING = "hash_missing";

    /** {@link Verdict#reason}：{@code orig} 与 {@code patched} 逐字相同 ⇒ 这两份参考没有分辨力。 */
    public static final String REASON_REFERENCES_IDENTICAL = "references_identical";

    /** {@link Verdict#reason}：三份互不相同。 */
    public static final String REASON_NO_MATCH = "no_match";

    /**
     * {@link Verdict#reason}：<b>这一次根本采不到"当前字节"</b>（patch 刚落地，读回只会拿到基线，
     * 而拿基线去判等于自欺欺人）⇒ verdict 只能是 {@code unknown}，并且必须写明"判不了"。
     *
     * <p>为什么这个词要单独存在：{@code unknown} 有三种完全不同的含义
     * （缺指纹／两份参考相同／三份都对不上），而"这个时刻根本没法采"是第四种 ——
     * 混在一起会让判读的人以为"读到了但没对上"。
     */
    public static final String REASON_NOT_OBSERVABLE = "not_observable";

    /** {@link Verdict#reason}：还原之后的取样，{@code live == orig} ⇒ 类确实回到了初始字节。 */
    public static final String REASON_UNDO_CONFIRMED = "undo_confirmed";

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private HotfixPatchReadback() {
        // 纯静态
    }

    // ═══════════════════════════════ 指纹 ═══════════════════════════════

    /** 一段 class 字节的指纹：{@code SHA-256 前 16 位十六进制/长度}。 */
    public static final class Fingerprint {

        /** 指纹「没有」：{@code hash = null}、{@code length = -1}（<b>不参与</b>任何相等判定）。 */
        public static final Fingerprint NONE = new Fingerprint(null, -1);

        /** 16 个十六进制字符；{@code null} = 没有（不可比）。 */
        public final String hash;

        /** 字节长度；{@code -1} = 没有。 */
        public final int length;

        Fingerprint(String hash, int length) {
            this.hash = hash;
            this.length = length;
        }

        /** 能参与判定吗（哈希非空且长度非负）。 */
        public boolean comparable() {
            return hash != null && !hash.isEmpty() && length >= 0;
        }

        /** 日志里的写法：{@code <hash16>/<len>}；缺的那一半写 {@code ?}。 */
        public String brief() {
            final String h = hash == null || hash.isEmpty() ? "?" : hash;
            return h + "/" + (length < 0 ? "?" : String.valueOf(length));
        }

        @Override
        public String toString() {
            return brief();
        }
    }

    /**
     * 算一段字节的指纹（{@code null} ⇒ {@link Fingerprint#NONE}）。
     *
     * <p>只取 SHA-256 的<b>前 8 字节</b>（{@value #HASH_HEX_CHARS} 个十六进制字符）：
     * 这段字节最多 4 MB，取前 64 bit 已经足够区分"同一份 / 不同份"，
     * 而日志一行里要塞三份，太长会毁掉 grep。
     *
     * @param bytes class 字节；{@code null} 允许
     * @return 指纹（算不出来 ⇒ {@link Fingerprint#NONE}，<b>绝不抛</b>）
     */
    public static Fingerprint fingerprint(byte[] bytes) {
        if (bytes == null) {
            return Fingerprint.NONE;
        }
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            final byte[] digest = md.digest(bytes);
            final StringBuilder sb = new StringBuilder(HASH_HEX_CHARS);
            for (int i = 0; i < HASH_HEX_CHARS / 2; i++) {
                final int v = digest[i] & 0xFF;
                sb.append(HEX[v >>> 4]).append(HEX[v & 0x0F]);
            }
            return new Fingerprint(sb.toString(), bytes.length);
        } catch (Throwable t) {
            // 连 SHA-256 都拿不到就只能"没有指纹"——调用方据此判 unknown，不猜
            return Fingerprint.NONE;
        }
    }

    /** 两份指纹是不是同一份（都可比且哈希逐字相同）。{@code null}/缺指纹 ⇒ {@code false}。 */
    public static boolean same(Fingerprint a, Fingerprint b) {
        if (a == null || b == null) {
            return false;
        }
        if (!a.comparable() || !b.comparable()) {
            return false;
        }
        return a.hash.equals(b.hash) && a.length == b.length;
    }

    // ═══════════════════════════════ 判定（纯函数） ═══════════════════════════════

    /** 一次判定的完整结论（{@link #judge} 的输出；不可变）。 */
    public static final class Verdict {

        /** {@link #VERDICT_PATCHED_LIVE}／{@link #VERDICT_REVERTED}／{@link #VERDICT_UNKNOWN}。 */
        public final String verdict;

        /** 机器可判的原因：{@code ok}／{@code hash_missing}／{@code references_identical}／{@code no_match}。 */
        public final String reason;

        /** {@code live} 与 {@code patched} 长度相同（"长度相近但哈希不同"那条边界的判据）。 */
        public final boolean lenSameAsPatched;

        /** {@code live} 与 {@code orig} 长度相同。 */
        public final boolean lenSameAsOrig;

        Verdict(String verdict, String reason, boolean lenSameAsPatched, boolean lenSameAsOrig) {
            this.verdict = verdict;
            this.reason = reason;
            this.lenSameAsPatched = lenSameAsPatched;
            this.lenSameAsOrig = lenSameAsOrig;
        }

        public boolean patchedLive() {
            return VERDICT_PATCHED_LIVE.equals(verdict);
        }

        public boolean reverted() {
            return VERDICT_REVERTED.equals(verdict);
        }

        @Override
        public String toString() {
            return verdict + " reason=" + reason + " len_same_patched=" + lenSameAsPatched
                    + " len_same_orig=" + lenSameAsOrig;
        }
    }

    /**
     * 三份指纹 → 结论（<b>这是本类唯一的判据</b>；纯函数，不看时钟、不看状态）。
     *
     * <p>顺序有讲究：
     * <ol>
     *   <li>缺 {@code live}／{@code patched} 的哈希 ⇒ {@link #VERDICT_UNKNOWN}（判不了就不判）；</li>
     *   <li>{@code live == patched} ⇒ {@link #VERDICT_PATCHED_LIVE}，<b>但</b>若 {@code orig} 与
     *       {@code patched} 本来就逐字相同（说明这两份参考没有分辨力，真实发生的概率 0：
     *       patch 一定会改字节）⇒ 退回 {@link #VERDICT_UNKNOWN}；</li>
     *   <li>{@code live == orig} ⇒ {@link #VERDICT_REVERTED}；</li>
     *   <li>都对不上 ⇒ {@link #VERDICT_UNKNOWN}（{@code orig} 可用时 {@code reason=no_match}，
     *       {@code orig} 本身缺失时 {@code reason=hash_missing} —— 后者绝不许说成"比过了没对上"）。</li>
     * </ol>
     *
     * @param orig    patch 之前收到的字节
     * @param patched 我们写下去的字节
     * @param live    readback 收到的字节
     * @return 结论（永不为 {@code null}）
     */
    public static Verdict judge(Fingerprint orig, Fingerprint patched, Fingerprint live) {
        final boolean liveKnown = live != null && live.comparable();
        final boolean patchedKnown = patched != null && patched.comparable();
        final boolean origKnown = orig != null && orig.comparable();
        final boolean lenSamePatched = liveKnown && patchedKnown && live.length == patched.length;
        final boolean lenSameOrig = liveKnown && origKnown && live.length == orig.length;

        if (!liveKnown || !patchedKnown) {
            return new Verdict(VERDICT_UNKNOWN, REASON_HASH_MISSING, lenSamePatched, lenSameOrig);
        }
        if (same(patched, live)) {
            if (same(orig, patched)) {
                // orig 与 patched 是同一份 ⇒ "live 等于 patched" 什么也证明不了
                return new Verdict(VERDICT_UNKNOWN, REASON_REFERENCES_IDENTICAL,
                        lenSamePatched, lenSameOrig);
            }
            return new Verdict(VERDICT_PATCHED_LIVE, REASON_OK, lenSamePatched, lenSameOrig);
        }
        if (origKnown && same(orig, live)) {
            return new Verdict(VERDICT_REVERTED, REASON_OK, lenSamePatched, lenSameOrig);
        }
        // orig 本身缺失时"no_match"是误导（听起来像"比过了、没对上"）⇒ 如实写 hash_missing
        return new Verdict(VERDICT_UNKNOWN, origKnown ? REASON_NO_MATCH : REASON_HASH_MISSING,
                lenSamePatched, lenSameOrig);
    }

    /** {@link #judge} 的简写：只要 verdict 字符串。 */
    public static String verdict(Fingerprint orig, Fingerprint patched, Fingerprint live) {
        return judge(orig, patched, live).verdict;
    }

    /**
     * 直接构一个"判不了"的结论（不经过三份指纹）。
     *
     * <p>用途：patch 刚落地那一刻，<b>采不到当前字节</b>（见类注释 P1/P2）——
     * 这时必须打 {@code verdict=unknown} 且写明原因，<b>而不是</b>把基线当 live 去判
     * （那会得到 {@code reverted}，等于把自己的正常补丁报成"被织回去了"）。
     *
     * @param reason 机器可判的原因（{@link #REASON_NOT_OBSERVABLE} 等）
     * @return 结论（{@code verdict=unknown}）
     */
    public static Verdict unknown(String reason) {
        return new Verdict(VERDICT_UNKNOWN, reason == null ? REASON_NOT_OBSERVABLE : reason, false, false);
    }

    // ═══════════════════════════════ 日志行 ═══════════════════════════════

    /**
     * 组一行 {@code patch_readback …}（字段顺序固定，供 grep；<b>不含</b> {@code [SHANHAI-HOTFIX]} 前缀，
     * 前缀由日志出口统一加）。
     *
     * <pre>
     *   patch_readback target=&lt;类&gt;#&lt;方法&gt; orig=&lt;hash16&gt;/&lt;len&gt; patched=&lt;hash16&gt;/&lt;len&gt; \
     *        live=&lt;hash16&gt;/&lt;len&gt; verdict=&lt;patched_live|reverted|unknown&gt; mixin_wrappers=&lt;n&gt; [extra]
     * </pre>
     *
     * <p>{@code verdict=unknown} 时额外带两个长度判据（回答"是不是长度相同但哈希不同"
     * 这条边界，不用读者自己数），并带 {@code verdict_reason=}。
     *
     * @param target         {@code 类#方法}（与 {@code auto_counter} 的 {@code target=} 逐字一致）
     * @param orig           patch 前的指纹
     * @param patched        写下去的指纹
     * @param live           readback 收到的指纹
     * @param v              判定（{@code null} ⇒ 按 unknown 打）
     * @param mixinWrappers  类里 {@code handler$}/{@code localvar$} 方法的个数（{@link #COUNT_UNKNOWN} = 数不出来）
     * @param extra         追加字段（可为 {@code null}；调用方自己保证是 {@code key=value} 形式）
     * @return 一行文本（永不为 {@code null}）
     */
    public static String line(String target, Fingerprint orig, Fingerprint patched, Fingerprint live,
                              Verdict v, int mixinWrappers, String extra) {
        final StringBuilder sb = new StringBuilder(224);
        sb.append("patch_readback target=").append(tok(target))
                .append(" orig=").append(orig == null ? Fingerprint.NONE.brief() : orig.brief())
                .append(" patched=").append(patched == null ? Fingerprint.NONE.brief() : patched.brief())
                .append(" live=").append(live == null ? Fingerprint.NONE.brief() : live.brief())
                .append(" verdict=").append(v == null ? VERDICT_UNKNOWN : tok(v.verdict))
                .append(" mixin_wrappers=").append(mixinWrappers);
        if (v != null && !REASON_OK.equals(v.reason)) {
            // 用 verdict_reason= 而不是 unknown_reason=：reverted 也可能带原因
            // （undo_confirmed = "这次退回基线是我们自己干的"），字段名必须两种都说得通。
            sb.append(" verdict_reason=").append(tok(v.reason));
        }
        if (v == null || VERDICT_UNKNOWN.equals(v.verdict)) {
            sb.append(" len_same_as_patched=").append(v != null && v.lenSameAsPatched)
                    .append(" len_same_as_orig=").append(v != null && v.lenSameAsOrig);
        }
        if (extra != null && !extra.isEmpty()) {
            sb.append(' ').append(extra);
        }
        return sb.toString();
    }

    // ═══════════════════════════════ Mixin 重织的旁证 ═══════════════════════════════

    /**
     * 这个方法名是不是 Mixin 生成的包装（{@code handler$…}／{@code localvar$…}）。
     *
     * <p>只认这两个前缀：它们是 1.20.1 / Mixin 0.8.x 里 {@code @Inject}/{@code @Redirect}
     * 执行时会留下的名字。名字里带 {@code $} 的普通方法（比如 Kotlin 的合成方法）不算。
     *
     * @param name 方法名（可为 {@code null}）
     * @return {@code true} = Mixin 包装
     */
    public static boolean isMixinWrapper(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        return name.startsWith(MIXIN_HANDLER_PREFIX) || name.startsWith(MIXIN_LOCALVAR_PREFIX);
    }

    /**
     * 反射数一个类里还有几个 Mixin 包装方法（{@code handler$}/{@code localvar$}）。
     *
     * <p>为什么只数不判：Mixin 的包装方法<b>不会</b>因为我们的 patch 消失（我们只换别的
     * 方法的类体，不动方法表），所以这个数只回答"readback 这一刻这个类里还带着 Mixin 的痕迹吗"。
     * 它<b>不能</b>单独用来判"有没有被重织"——重织的证据只有上面的三份指纹。
     *
     * @param cls 目标类（可为 {@code null}）
     * @return 个数；数不出来 ⇒ {@link #COUNT_UNKNOWN}（<b>不抛</b>）
     */
    public static int mixinWrappers(Class<?> cls) {
        if (cls == null) {
            return COUNT_UNKNOWN;
        }
        try {
            final Method[] methods = cls.getDeclaredMethods();
            if (methods == null) {
                return 0;
            }
            int n = 0;
            for (int i = 0; i < methods.length; i++) {
                final Method m = methods[i];
                if (m != null && isMixinWrapper(m.getName())) {
                    n++;
                }
            }
            return n;
        } catch (Throwable t) {
            // 方法签名里引用了缺失的类型时 getDeclaredMethods 会抛 NoClassDefFoundError/TypeNotPresent
            return COUNT_UNKNOWN;
        }
    }

    // ═══════════════════════════════ 限流（纯函数） ═══════════════════════════════

    /**
     * 这次失败日志允许打吗（同一 target 最快 {@value #LOG_MIN_INTERVAL_MILLIS} ms 一行）。
     *
     * <p>只用于<b>失败/跳过</b>路径：成功的那一行是"每次 patch 一条"，本来就被
     * {@code gate_already_patched} 挡住了频率，不需要再限流。
     *
     * @param lastLogMillis 上一次打的时刻；{@code <= 0} = 从没打过
     * @param nowMillis     现在
     * @return {@code true} = 可以打（调用方随后要把 {@code nowMillis} 记回去）
     */
    public static boolean logAllowed(long lastLogMillis, long nowMillis) {
        if (lastLogMillis <= 0L) {
            return true;
        }
        if (nowMillis < lastLogMillis) {
            return true;                           // 时钟倒退：宁可多打一行，也别永远打不出来
        }
        return nowMillis - lastLogMillis >= LOG_MIN_INTERVAL_MILLIS;
    }

    // ═══════════════════════════════ 读回（需要 Instrumentation） ═══════════════════════════════

    /** 一次 readback 的结果（不可变）。 */
    public static final class Probe {

        /** {@code true} = 真的从 JVM 那里收到了目标类的字节。 */
        public final boolean ok;

        /** {@code ok} 或一条机器可判的失败原因。 */
        public final String reason;

        /** readback 收到的字节的指纹；没收到 ⇒ {@link Fingerprint#NONE}。 */
        public final Fingerprint live;

        /** 我们那个临时 transformer 被回调了几次（{@code 0} 说明 JVM 根本没回调 ⇒ 不可改/认错类）。 */
        public final int calls;

        /** 临时 transformer 卸下来了吗（{@code false} = 还挂着，等于常驻，违反成本纪律）。 */
        public final boolean removed;

        /** 整段花了多久。 */
        public final long elapsedMillis;

        Probe(boolean ok, String reason, Fingerprint live, int calls, boolean removed, long elapsedMillis) {
            this.ok = ok;
            this.reason = reason == null ? "?" : reason;
            this.live = live == null ? Fingerprint.NONE : live;
            this.calls = calls;
            this.removed = removed;
            this.elapsedMillis = elapsedMillis;
        }

        static Probe fail(String reason, int calls, boolean removed, long elapsedMillis) {
            return new Probe(false, reason, Fingerprint.NONE, calls, removed, elapsedMillis);
        }

        /** 日志里的写法（{@code readback_reason=… readback_calls=… transformer_removed=…}）。 */
        public String brief() {
            return "readback_reason=" + tok(reason) + " readback_calls=" + calls
                    + " transformer_removed=" + removed + " elapsed_ms=" + elapsedMillis;
        }

        @Override
        public String toString() {
            return "ok=" + ok + " live=" + live.brief() + " " + brief();
        }
    }

    /**
     * <b>取一次样</b>：让 JVM 把它<b>手上那份初始 class 字节</b>交给我们，算一份指纹就丢。
     *
     * <p>做法（每一步都有失败出口，<b>绝不抛</b>）：
     * <ol>
     *   <li>挂一个临时 {@link ClassFileTransformer}（{@code canRetransform=true}）；</li>
     *   <li>{@code retransformClasses(cls)} —— 它收到 {@code classfileBuffer} 就记指纹，然后
     *       <b>永远 {@code return null}</b>（不改字节；返回 {@code null} 的意思是"这个类我不管"）；</li>
     *   <li>{@code finally} 里立刻 {@code removeTransformer}（<b>不常驻</b>）；</li>
     *   <li>只留 16 个字符的哈希 ＋ 长度，{@code classfileBuffer} 当场丢。</li>
     * </ol>
     *
     * <p>🔴 <b>调用时机是安全前提，不是建议</b>：实测（类注释 P2）一次"全 null"的 retransform
     * 会把类<b>退回初始字节</b> —— 也就是一次 undo。所以本方法<b>只能</b>在这些时刻调用：
     * <ul>
     *   <li>刚刚 {@code undo}／还原过这个类（此时"退回基线"正是意图，样本 == 当前状态）；</li>
     *   <li>类本来就还没被我们改过。</li>
     * </ul>
     * <b>绝不许</b>在 patch 成功之后调用：那样会在 1 tick 内把刚打上的补丁撤掉
     * （第一版犯过，离线套件当场红了两条断言）。
     *
     * <p>另外它不是线程无关的：{@code retransformClasses} 会回调<b>所有</b>可重定义的
     * transformer（含 Mixin 的），所以调用点必须在我们的工作线程/命令线程上，
     * 并且不能与 {@code HotfixAutoCounter} 的 patch 同时在飞（调用方用那把锁保证）。
     *
     * @param inst {@link Instrumentation}；{@code null} ⇒ 失败
     * @param cls  目标类；数组/基本类型 ⇒ 失败（{@code retransformClasses} 不收它们）
     * @return 结果（永远非 {@code null}）
     */
    public static Probe capture(Instrumentation inst, Class<?> cls) {
        final long t0 = System.nanoTime();
        if (inst == null) {
            return Probe.fail("no_instrumentation", 0, false, 0L);
        }
        if (cls == null) {
            return Probe.fail("no_class", 0, false, 0L);
        }
        if (cls.isArray() || cls.isPrimitive()) {
            return Probe.fail("not_a_normal_class", 0, false, 0L);
        }
        final Reader reader = new Reader(cls.getName().replace('.', '/'));
        boolean added = false;
        boolean removed = false;
        String reason = REASON_OK;
        try {
            if (!inst.isRetransformClassesSupported()) {
                return Probe.fail("retransform_unsupported", 0, false, elapsed(t0));
            }
            inst.addTransformer(reader, true);
            added = true;
            inst.retransformClasses(cls);
        } catch (Throwable t) {
            reason = "retransform_" + t.getClass().getSimpleName();
        } finally {
            if (added) {
                try {
                    removed = inst.removeTransformer(reader);
                } catch (Throwable t) {
                    removed = false;
                    if (REASON_OK.equals(reason)) {
                        reason = "remove_transformer_" + t.getClass().getSimpleName();
                    }
                }
            }
        }
        final Fingerprint live = reader.observed;
        if (REASON_OK.equals(reason)) {
            if (reader.calls <= 0) {
                // JVM 没回调我们 ⇒ 这段字节根本没经过我们的手，判不了
                reason = "transformer_not_called";
            } else if (live == null || !live.comparable()) {
                reason = "fingerprint_missing";
            }
        }
        final boolean ok = REASON_OK.equals(reason) && reader.calls > 0
                && live != null && live.comparable();
        return new Probe(ok, reason, ok ? live : Fingerprint.NONE, reader.calls, removed, elapsed(t0));
    }

    private static long elapsed(long t0) {
        return (System.nanoTime() - t0) / 1_000_000L;
    }

    /**
     * 只记指纹的临时 transformer。
     *
     * <p>🔴 两条硬约束（写在代码里，改之前先读）：
     * <ol>
     *   <li><b>永远 {@code return null}</b>：返回 {@code null} 才是"不改这个类"；
     *       返回任何数组（哪怕内容一模一样）都可能让 {@code retransformClasses} 重新定义这个类，
     *       那就从"读回"变成了"又改了一次"——读回的整个意义就没了。</li>
     *   <li><b>绝不抛</b>：transform 里抛异常会被 JVM 记成"这个 transformer 坏了"，
     *       而且可能让 {@code retransformClasses} 失败、连带把我们的 patch 弄成半状态。</li>
     * </ol>
     */
    private static final class Reader implements ClassFileTransformer {

        private final String internalName;

        /** 被回调几次（只用工作线程写；声明 volatile 是为了让抓取方立即可见）。 */
        volatile int calls;

        /** 收到的那段字节的指纹（只留这一份，字节当场丢）。 */
        volatile Fingerprint observed;

        Reader(String internalName) {
            this.internalName = internalName;
        }

        @Override
        @SuppressWarnings("deprecation")
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            try {
                if (classfileBuffer == null || className == null || internalName == null) {
                    return null;
                }
                if (!internalName.equals(className)) {
                    return null;
                }
                calls = calls + 1;
                if (observed == null) {
                    observed = fingerprint(classfileBuffer);
                }
            } catch (Throwable ignored) {
                // 读回失败只意味着"这一次判不了"；绝不能把 retransform 带崩
            }
            return null;                       // 🔴 绝不许改字节
        }
    }

    // ═══════════════════════════════ 小工具 ═══════════════════════════════

    /** 日志字段里不许出现空格／竖线／换行（否则 grep 会把一行切错）。 */
    static String tok(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "?";
        }
        final StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            final char c = raw.charAt(i);
            if (c == ' ' || c == '\t' || c == '|' || c == HotfixTriageVerdict.FULLWIDTH_BAR
                    || c == '\n' || c == '\r') {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
