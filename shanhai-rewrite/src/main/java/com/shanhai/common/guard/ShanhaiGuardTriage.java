package com.shanhai.common.guard;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 山海 · <b>分诊核（T 件 ①）：四个机器可判读数 ⇒ 这一拍"活下来这件事到底解没解决"</b>。
 *
 * <h2>为什么需要它（现场）</h2>
 * <pre>
 *   2026-11-07 实测：三次启动里守卫 0 次触发 ⇒ 没有任何动态样本。
 *   用户要的是"死前能收集多少信息、能不能当场判断"。
 * </pre>
 * 拦住了 ／ 救回来了 是<b>两种不同的状态</b>：只打"拦截 移除"只能证明"我们动手了"，
 * 不能证明"这一拍之后血是真的 > 0"。于是把"到底解没解决"压成<b>四个只读读数</b>，
 * 由 {@link #tier1Solved} 一条判据给出 {@code solved=true/false}。
 *
 * <h2>四个读数（全部只读，全部可离线复现）</h2>
 * <pre>
 *   R1  我们的监听器这一拍有没有 fire            （有事件就算 true）        ⇒ {@link Readings#listenerFired}
 *   R2  【救完之后】字段真值血量 &gt; 0                                    ⇒ {@link #r2}
 *       —— 用 LivingEntity.DATA_HEALTH_ID 的真值（{@code ShanhaiGuardValueLayer#readHealthField}），
 *          <b>不信 getHealth()</b>（对手给每条 return 加过偏移 ⇒ 那个读数偏高时会吃掉"该补血"的信号）。
 *   R3  setHealth 被外部直调 且 &lt;= 0 且 无事件                          ⇒ {@link #r3}
 *   R4  die / setRemoved 触达 且 同 tick 无【伤害】事件                   ⇒ {@link #r4}
 * </pre>
 * <b>判据</b>：{@code tier1Solved = R1 &amp;&amp; R2 &amp;&amp; !R3 &amp;&amp; !R4}。
 * <p>口径为什么是这四个：
 * <ul>
 *   <li>R1 单独为真 ⇒ 事件层在说话，但没说"血是真的"；</li>
 *   <li>R1 ＋ R2 ⇒ <b>事件层说话 ＋ 字段真值 > 0</b> = 最常见的好结局（"普通强怪一棒"就是这一格）；</li>
 *   <li>R3 ⇒ 有人在<b>绕开事件层</b>直写血量（{@code <=0} 只可能是"想弄死"）⇒ 事件层没说话就说明我们没被通知；</li>
 *   <li>R4 ⇒ 有人<b>绕开伤害事件</b>直接触达 {@code die}/{@code setRemoved} ⇒ 同理。</li>
 * </ul>
 *
 * <h2>🔴 三条硬约束（写在这里，改这个类之前先读）</h2>
 * <ol>
 *   <li><b>零 MC 依赖</b>：本文件只 import {@code java.*} ⇒ 空 classpath 就能 {@code javac} 编译、
 *       {@link #main} 能离线单跑（自检条数见报告）。</li>
 *   <li><b>只读</b>：本类<b>不</b>写血量、<b>不</b>调 {@code setHealth}/{@code setRemoved}、<b>不</b>决定拦不拦
 *       —— 它只把读数变成一行字符串。判定权仍在 {@link ShanhaiGuardCore}。</li>
 *   <li><b>绝不抛</b>：{@link #frameTextOrNull} ／ {@link #captureStrikeFrames} ／ {@link #injectedModId}
 *       对垃圾输入一律返回 {@code null}，让调用方继续走原路。</li>
 * </ol>
 *
 * <h2>③ 受伤也抓栈（补动态样本）</h2>
 * {@link #captureStrikeFrames()} 用 {@code StackWalker.walk(…limit 24…)}（<b>不是</b>
 * {@code new Throwable().getStackTrace()}）：
 * <pre>
 *   [SHANHAI-TRIAGE] strike_caller#1 at=fake.StrikeSource#boom:12 frames=fake.StrikeSource#boom:12 | …
 *   [SHANHAI-TRIAGE] strike_caller#1 none
 * </pre>
 * 过滤口径（{@link #frameTextOrNull}）：
 * <ul>
 *   <li>{@code net.minecraft.*} ／ {@code net.minecraftforge.*} = <b>原版帧</b>，丢 ——
 *       <b>但</b>这两族的帧如果方法名是注入方法（{@code handler$…}），<b>保留</b>：
 *       那一帧正是"谁改过这个类"的最便宜来源（类名还是原版，方法名带 modId 段）；</li>
 *   <li>{@code com.shanhai.*} = <b>我们自己</b>（含 mixin 包），丢 —— 否则前几帧全是本守卫；
 *       与 {@link ShanhaiGuardCallerTrace#SPEC_SKIP_PREFIXES} 同一族口径；</li>
 *   <li>{@code java./javax./jdk./sun./com.mojang./…} = JDK 与框架噪声，丢；</li>
 *   <li>帧上带 {@code pl:mixin:APP:<config>:<Class>} 标注（真机崩溃报告里就长这样，见
 *       {@code ShanhaiJeiSyncPlan} 里引的那段原始堆栈）⇒ 原样标出来。</li>
 * </ul>
 *
 * <h2>④ 注入方法名解析（关键类扫描与栈标注共用一份）</h2>
 * Mixin 把注入体改名成 {@code <kind>$<会话前缀>$<modId 段>$<原名>}，真样本：
 * {@code handler$cni000$shooting_star_demo$quiet} ⇒ {@link #injectedModId} = {@code shooting_star_demo}。
 *
 * <h2>限流（规格：同一 src 每人每 5 秒最多 1 行）</h2>
 * {@link Throttle}：窗口 {@link #THROTTLE_WINDOW_MILLIS}，键 {@code uuid|src}，
 * <b>时间由调用方喂</b>（离线自检直接用假时钟跑同一段代码）。
 *
 * <p>⚠️ 本类<b>不</b>记任何全局状态（除了自检用的计数器）：状态都在
 * {@link ShanhaiGuardTriageBridge}，这样"谁在读"一眼可见。
 */
public final class ShanhaiGuardTriage {

    private ShanhaiGuardTriage() {}

    /** 本件所有日志行的前缀（与守卫的 {@code [SHANHAI-GUARD]} 分开，方便 grep 与限流）。 */
    public static final String PREFIX = "[SHANHAI-TRIAGE] ";

    /** 限流窗口（毫秒）：<b>同一 src 每人</b>最多一行 / 5 秒（规格点名的 5 秒）。 */
    public static final long THROTTLE_WINDOW_MILLIS = 5000L;

    /** {@code strike_caller} 每人每局最多打几次（规格：3）。 */
    public static final int MAX_STRIKE_CALLERS = 3;

    /** 抓栈时最多<b>扫</b>几帧（成本上限；比实际上限多扫，是因为前面几帧会被过滤掉）。 */
    public static final int STRIKE_SCAN_FRAMES = 24;

    /** 过滤之后最多<b>留</b>几帧（规格：帧上要能看出"是谁"就够了）。 */
    public static final int STRIKE_KEPT_FRAMES = 4;

    /** 注入方法名的前缀（Mixin 改写目标类时生成的合成方法名）。 */
    public static final String[] INJECTED_PREFIXES = {
            "handler$",
            "redirect$",
            "wrapWithCondition$",
            "modifyArg$",
            "modifyVariable$",
            "modifyReturnValue$",
            "modifyExpressionValue$",
            "modifyReceiver$",
            "wrapOperation$"};

    /** 规格点名的「原版」前缀（③ 要过滤掉的那两族）。 */
    public static final String[] VANILLA_PREFIXES = {"net.minecraft.", "net.minecraftforge."};

    /** 我们自己（含 {@code com.shanhai.mixin}）——取证时永远先滤掉。 */
    public static final String[] SELF_PREFIXES = {"com.shanhai."};

    /**
     * JDK ／ 框架噪声（<b>不是</b> mod 帧）。
     * <p>与 {@link ShanhaiGuardCallerTrace#EXTRA_SKIP_PREFIXES} 同一族口径；
     * ⚠️ 这里<b>不许</b>出现任何具体 mod 的包名（规格红线：不按类名硬编码任何 mod）。
     */
    public static final String[] NOISE_PREFIXES = {
            "java.",
            "javax.",
            "jdk.",
            "sun.",
            "com.sun.",
            "com.mojang.",
            "cpw.mods.modlauncher.",
            "org.objectweb.asm.",
            "com.llamalad7.mixinextras."};

    /** Mixin 栈帧上的标注前缀（"谁改过这个类"的最便宜来源）。 */
    public static final String MIXIN_MARKER = "pl:mixin:";

    // ═══════════════════════════════ ① 四个读数 ＋ 判据 ═══════════════════════════════

    /**
     * <b>一拍的读数</b>（可变；由 {@link ShanhaiGuardTriageBridge} 在守卫各路径上累加，拍末清空）。
     *
     * <p>字段与规格一一对应，不多不少；{@link #clear()} 之后所有布尔为 {@code false}、
     * 血量读数为 {@link Float#NaN}（= 这一拍没读过）。
     */
    public static final class Readings {

        /** R1 原料：我们的监听器这一拍 fire 过（<b>只</b>由 Forge 事件置位，不认 mixin 触达）。 */
        public boolean listenerFired;

        /** 这一拍有没有【伤害】事件（{@code LivingAttackEvent}）—— R4 要用它做"无伤害事件"判据。 */
        public boolean damageEvent;

        /** 这一拍有没有【死亡】事件（{@code LivingDeathEvent}）。 */
        public boolean deathEvent;

        /** 我们"救"过（拦下死亡 ＋ 补血 ／ 改写入参 ／ 拒死）—— R2 的"救完之后"就是这个。 */
        public boolean rescued;

        /** 外部直调过 {@code setHealth(<=0)}（我们自己只会写 {@code max>0}，所以不可能是我们）。 */
        public boolean directSetHealth;

        /** {@code die} ／ {@code setRemoved} 被触达（可能是 mixin 层，没有对应事件）。 */
        public boolean dieOrRemove;

        /** 字段真值血量（{@code DATA_HEALTH_ID}；读不到 = {@link Float#NaN}，<b>绝不</b>拿 getHealth 顶替）。 */
        public float hpTrue = Float.NaN;

        /** API 读数（{@code getHealth()}；只用来对照，<b>不参与</b>判据）。 */
        public float hpApi = Float.NaN;

        /** 这一拍读到了任何东西吗（拍末决定"要不要打一行"）。 */
        public boolean any() {
            return listenerFired || damageEvent || deathEvent || rescued
                    || directSetHealth || dieOrRemove;
        }

        /** 拍末清空（下一拍从零开始）。 */
        public void clear() {
            listenerFired = false;
            damageEvent = false;
            deathEvent = false;
            rescued = false;
            directSetHealth = false;
            dieOrRemove = false;
            hpTrue = Float.NaN;
            hpApi = Float.NaN;
        }
    }

    /** R1：我们的监听器这一拍有没有 fire（有事件就算 true）。 */
    public static boolean r1(Readings r) {
        return r != null && r.listenerFired;
    }

    /**
     * R2：<b>救完之后</b>字段真值血量 &gt; 0。
     *
     * <p>两个条件缺一不可：
     * <ol>
     *   <li>{@code rescued} —— 这一拍我们确实救过（否则"血 &gt; 0"只是没被打，不叫"救回来"）；</li>
     *   <li>{@code hpTrue > 0} —— <b>字段真值</b>；读不到（{@code NaN}）时 {@code NaN > 0} 为 {@code false}
     *       ⇒ <b>判成没解决</b>（fail-closed：读不到就不许声称"救回来了"，响应里会带 {@code hp_true=NaN}）。</li>
     * </ol>
     */
    public static boolean r2(Readings r) {
        return r != null && r.rescued && r.hpTrue > 0f;
    }

    /** R3：{@code setHealth} 被外部直调且 {@code <=0} 且<b>这一拍没有任何事件</b>。 */
    public static boolean r3(Readings r) {
        return r != null && r.directSetHealth && !r.listenerFired;
    }

    /** R4：{@code die}/{@code setRemoved} 触达且<b>同 tick 无伤害事件</b>（死亡事件不算伤害事件）。 */
    public static boolean r4(Readings r) {
        return r != null && r.dieOrRemove && !r.damageEvent;
    }

    /**
     * 🔴 <b>本件的唯一判据</b>：{@code R1 && R2 && !R3 && !R4}。
     *
     * <p>「普通强怪一棒」（血被写到 0，但被我们救回来）必须判成 {@code true}
     * —— 离线自检里就是 {@code 强怪一棒} 那一条（见 {@link #main}）。
     */
    public static boolean tier1Solved(Readings r) {
        return r1(r) && r2(r) && !r3(r) && !r4(r);
    }

    // ═══════════════════════════════ 那一行日志（格式由规格点名） ═══════════════════════════════

    /**
     * 拼那一行：
     * <pre>
     *   [SHANHAI-TRIAGE] solved=&lt;bool&gt; r1=&lt;bool&gt; r2=&lt;bool&gt; r3=&lt;bool&gt; r4=&lt;bool&gt; hp_true=&lt;float&gt; hp_api=&lt;float&gt; src=&lt;事件名|direct|remove&gt;
     * </pre>
     *
     * @param src 触发这一行的来源：事件名（{@code LivingAttackEvent}/{@code LivingDeathEvent}）／
     *            {@code direct}（外部直调 setHealth）／ {@code remove}（setRemoved 触达）／{@code die}
     */
    public static String line(Readings r, String src) {
        final Readings read = (r == null ? new Readings() : r);
        final StringBuilder sb = new StringBuilder(160);
        sb.append(PREFIX)
                .append("solved=").append(tier1Solved(read))
                .append(" r1=").append(r1(read))
                .append(" r2=").append(r2(read))
                .append(" r3=").append(r3(read))
                .append(" r4=").append(r4(read))
                .append(" hp_true=").append(num(read.hpTrue))
                .append(" hp_api=").append(num(read.hpApi))
                .append(" src=").append(src == null || src.isEmpty() ? "?" : src);
        return sb.toString();
    }

    /** 浮点数的打印口径（{@code NaN} 原样写 {@code NaN}，别写成 0）。 */
    public static String num(float v) {
        return Float.toString(v);
    }

    // ═══════════════════════════════ ③ 抓栈（StackWalker，纯 JDK） ═══════════════════════════════

    /** 抓栈用的 walker（不需要 RETAIN_CLASS_REFERENCE ⇒ 更便宜；只要名字与文件名）。 */
    private static final StackWalker WALKER = StackWalker.getInstance();

    /**
     * 抓一次"谁在打/谁在写"的调用栈，<b>只要非原版帧</b>（口径见类注释）。
     *
     * <p>🔴 除规格点名的 {@code net.minecraft.}/{@code net.minecraftforge.} 之外，还丢掉三类：
     * 我们自己（{@code com.shanhai.}）、JDK、框架噪声 —— 否则前几帧永远是本类，白抓。
     * <p>⚠️ 原版族里<b>注入方法</b>那一帧例外：保留（那正是"谁改过这个类"）。
     *
     * @return 已经格式化好的帧文本（最多 {@link #STRIKE_KEPT_FRAMES} 个）；一帧都没留下 ⇒ 空表（不是 null）
     */
    public static List<String> captureStrikeFrames() {
        final List<String> out = new ArrayList<>(STRIKE_KEPT_FRAMES);
        try {
            WALKER.walk(frames -> {
                frames.limit(STRIKE_SCAN_FRAMES).forEach(f -> {
                    if (out.size() >= STRIKE_KEPT_FRAMES) {
                        return;
                    }
                    final String text = frameTextOrNull(
                            f.getClassName(), f.getMethodName(), f.getFileName(), f.getLineNumber());
                    if (text != null) {
                        out.add(text);
                    }
                });
                return null;
            });
        } catch (Throwable t) {
            out.clear();   // 抓不到就是抓不到 —— 调用方会写 strike_caller#N none
        }
        return out;
    }

    /**
     * 一个栈帧 ⇒ 文本；<b>该丢的帧返回 {@code null}</b>。
     *
     * <p>形如 {@code 类#方法:行}（行号未知写 {@code :?}），后面可能带两种标注：
     * <pre>
     *   …#handler$cni000$shooting_star_demo$quiet:? {injected:shooting_star_demo}
     *   …#onTick:42 {pl:mixin:APP:shanhai.mixin.json:ShanhaiJeiBookmarkMixin}
     * </pre>
     */
    public static String frameTextOrNull(String className, String methodName, String fileName, int lineNumber) {
        if (className == null || className.isEmpty()) {
            return null;
        }
        final String method = methodName == null ? "?" : methodName;
        final boolean injected = isInjectedMethod(method);
        if (!injected && hasPrefix(className, VANILLA_PREFIXES)) {
            return null;   // 纯原版帧：丢（规格点名）
        }
        if (hasPrefix(className, SELF_PREFIXES)) {
            return null;   // 我们自己：丢（否则 #1 永远是本类）
        }
        if (!injected && hasPrefix(className, NOISE_PREFIXES)) {
            return null;   // JDK ／ 框架噪声：丢
        }
        final StringBuilder sb = new StringBuilder(96);
        sb.append(className).append('#').append(method)
                .append(':').append(lineNumber >= 0 ? String.valueOf(lineNumber) : "?");
        final String mixin = mixinMarker(fileName, className);
        if (mixin != null) {
            sb.append(" {").append(mixin).append('}');
        }
        final String mod = injectedModId(method);
        if (mod != null) {
            sb.append(" {injected:").append(mod).append('}');
        }
        return sb.toString();
    }

    /** 给 {@link StackTraceElement} 用的重载（离线自检直接造帧，不需要真栈）。 */
    public static String frameTextOrNull(StackTraceElement e) {
        if (e == null) {
            return null;
        }
        return frameTextOrNull(e.getClassName(), e.getMethodName(), e.getFileName(), e.getLineNumber());
    }

    /**
     * 帧上有没有 {@code pl:mixin:APP:<config>:<Class>} 标注（真机崩溃报告就长这样）。
     *
     * <p>Mixin 把它写在<b>文件名</b>一栏（有时外面套一层花括号），所以两个字段都看一眼。
     *
     * @return {@code pl:mixin:…} 那一段（已去掉花括号/空白）；没有 ⇒ {@code null}
     */
    public static String mixinMarker(String fileName, String className) {
        final String hit = sliceMarker(fileName);
        return hit != null ? hit : sliceMarker(className);
    }

    private static String sliceMarker(String raw) {
        if (raw == null) {
            return null;
        }
        final int at = raw.indexOf(MIXIN_MARKER);
        if (at < 0) {
            return null;
        }
        int end = raw.length();
        for (int i = at; i < raw.length(); i++) {
            final char c = raw.charAt(i);
            if (c == '}' || c == '{' || Character.isWhitespace(c)) {
                end = i;
                break;
            }
        }
        final String cut = raw.substring(at, end).trim();
        return cut.length() <= MIXIN_MARKER.length() ? null : cut;
    }

    /** {@code strike_caller#N} 那一行（{@code frames} 为空 ⇒ {@code none}，<b>不假装</b>）。 */
    public static String strikeLine(int index, List<String> frames) {
        final StringBuilder sb = new StringBuilder(256);
        sb.append(PREFIX).append("strike_caller#").append(index);
        if (frames == null || frames.isEmpty()) {
            return sb.append(" none").toString();
        }
        sb.append(" at=").append(frames.get(0)).append(" frames=");
        for (int i = 0; i < frames.size(); i++) {
            if (i > 0) {
                sb.append(" | ");
            }
            sb.append(frames.get(i));
        }
        return sb.toString();
    }

    // ═══════════════════════════════ ④ 注入方法名解析 ═══════════════════════════════

    /** 这个方法名像不像 Mixin 生成的注入方法（{@code handler$…} ／ {@code redirect$…} …）。 */
    public static boolean isInjectedMethod(String methodName) {
        if (methodName == null || methodName.isEmpty()) {
            return false;
        }
        for (final String p : INJECTED_PREFIXES) {
            if (methodName.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    /** 注入方法的<b>种类</b>（{@code handler}/{@code redirect}/…，去掉末尾的 {@code $}）；不是注入方法 ⇒ {@code null}。 */
    public static String injectedKind(String methodName) {
        if (methodName == null) {
            return null;
        }
        for (final String p : INJECTED_PREFIXES) {
            if (methodName.startsWith(p)) {
                return p.substring(0, p.length() - 1);
            }
        }
        return null;
    }

    /**
     * 🔴 从注入方法名里抽出 <b>modId 段</b>（"谁改过这个类"的最便宜来源）。
     *
     * <pre>
     *   handler$cni000$shooting_star_demo$quiet   ⇒ shooting_star_demo   ← 真样本
     *   redirect$afa000$mymod$onHurt              ⇒ mymod
     *   wrapWithCondition$cni000$create$canPlace  ⇒ create
     *   modifyArg$zzz$FooMixin$bar                ⇒ FooMixin            ← 拿不到包名时，它就是"最像谁"的那一段
     *   hurt ／ handler$ ／ handler$cni000         ⇒ null                ← 抽不出就如实说没有
     * </pre>
     *
     * <p>口径（写成规则，免得下次改坏）：
     * <ol>
     *   <li>按 {@code $} 切；段数 &lt; 3 ⇒ 没有 modId 段；</li>
     *   <li>第 2 段（下标 1）是 Mixin 的<b>会话前缀</b>（{@code zzz} ／ {@code cni000} ／ {@code afa000}，
     *       形如"几个小写字母 ＋ 可选数字"）⇒ 跳过，取第 3 段（下标 2）；</li>
     *   <li>否则第 2 段本身就是 modId 段。</li>
     * </ol>
     * ⚠️ 后缀方法名（{@code quiet}）<b>不</b>当 modId；段里的 {@code $} 已经被切开，不会误取。
     */
    public static String injectedModId(String methodName) {
        if (!isInjectedMethod(methodName)) {
            return null;
        }
        final String[] parts = methodName.split("\\$", -1);
        if (parts.length < 3) {
            return null;
        }
        final String second = parts[1];
        if (second.isEmpty()) {
            return null;
        }
        final String mod = looksLikeSessionPrefix(second) ? parts[2] : second;
        return mod == null || mod.isEmpty() ? null : mod;
    }

    /** 这一段像不像 Mixin 的会话前缀（{@code zzz} ／ {@code cni000} ／ {@code afa000}）。 */
    static boolean looksLikeSessionPrefix(String seg) {
        if (seg == null || seg.isEmpty() || seg.length() > 8) {
            return false;
        }
        if ("zzz".equals(seg)) {
            return true;   // Mixin 的默认前缀
        }
        int letters = 0;
        int digits = 0;
        for (int i = 0; i < seg.length(); i++) {
            final char c = seg.charAt(i);
            if (c >= 'a' && c <= 'z') {
                letters++;
            } else if (c >= '0' && c <= '9') {
                digits++;
            } else {
                return false;   // 含大写/下划线/其它 ⇒ 不是会话前缀（大概率是 modId 或类名）
            }
        }
        if (digits == 0) {
            return letters <= 4;          // 纯小写短词（zzz/aab）当会话前缀
        }
        return letters >= 2 && digits >= 2;   // cni000 / afa000
    }

    /** 收集一组方法名里的 modId 段并排序去重（关键类扫描的 {@code mods=[a,b]} 用它）。 */
    public static List<String> modsOf(Collection<String> methodNames) {
        final Set<String> out = new TreeSet<>();
        if (methodNames != null) {
            for (final String n : methodNames) {
                final String mod = injectedModId(n);
                if (mod != null) {
                    out.add(mod);
                }
            }
        }
        return new ArrayList<>(out);
    }

    /** {@code [a,b]}（空表写 {@code []}，<b>不写</b> {@code [null]}）。 */
    public static String joinList(List<String> items) {
        final StringBuilder sb = new StringBuilder("[");
        if (items != null) {
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(items.get(i));
            }
        }
        return sb.append(']').toString();
    }

    private static boolean hasPrefix(String s, String[] prefixes) {
        for (final String p : prefixes) {
            if (s.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    // ═══════════════════════════════ 限流（同一 src 每人每 5 秒 1 行） ═══════════════════════════════

    /**
     * 限流账本：<b>每个键一个 5 秒窗口</b>。
     *
     * <p>时间由调用方喂（{@code System.currentTimeMillis()} 在桥上取），
     * 离线自检用假时钟跑<b>同一段代码</b> ⇒ "5 秒"这条口径可被判据证明，不用等真 5 秒。
     *
     * <p>⚠️ 单线程使用（服务端主线程）；{@code synchronized} 只为"命令线程也读它"兜底。
     */
    public static class Throttle {

        private final long windowMillis;
        private final Map<String, Long> last = new HashMap<>();

        public Throttle(long windowMillis) {
            this.windowMillis = Math.max(0L, windowMillis);
        }

        /**
         * @param key       账本键（桥上 = {@code uuid|src}）
         * @param nowMillis 当前毫秒
         * @return true = 这一行可以打（并记下这次的时间）；false = 还在窗口里，压掉
         */
        public synchronized boolean admit(String key, long nowMillis) {
            if (key == null || key.isEmpty()) {
                return true;   // 没键就不限流（宁可多打一行，也不要静默丢证据）
            }
            final Long prev = last.get(key);
            if (prev != null && nowMillis - prev < windowMillis && nowMillis >= prev) {
                return false;
            }
            last.put(key, nowMillis);
            return true;
        }

        /** 这个键上次放行的时间（从没放过 ⇒ {@code Long.MIN_VALUE}）。 */
        public synchronized long lastAt(String key) {
            final Long prev = last.get(key);
            return prev == null ? Long.MIN_VALUE : prev;
        }

        /** 账本里有多少个键（读数用）。 */
        public synchronized int size() {
            return last.size();
        }

        public synchronized void clear() {
            last.clear();
        }
    }

    // ═══════════════════════════════ 配额（每人每局最多 N 次） ═══════════════════════════════

    /** 配额账本：{@link #take} 返回 1..max，用光返回 0（并<b>不</b>偷偷加一）。 */
    public static final class Quota {

        private final int max;
        private final Map<String, Integer> used = new HashMap<>();

        public Quota(int max) {
            this.max = Math.max(0, max);
        }

        /** 给这个键发一个编号；额度用完 ⇒ 0。 */
        public synchronized int take(String key) {
            if (key == null || key.isEmpty()) {
                return 0;
            }
            final int cur = used.getOrDefault(key, 0);
            if (cur >= max) {
                return 0;
            }
            used.put(key, cur + 1);
            return cur + 1;
        }

        /** 这个键已经用掉几次。 */
        public synchronized int used(String key) {
            return key == null ? 0 : used.getOrDefault(key, 0);
        }

        public synchronized int max() {
            return max;
        }

        public synchronized void clear() {
            used.clear();
        }
    }

    // ═══════════════════════════════ 自检（纯 JDK，离线跑） ═══════════════════════════════

    private static final PrintStream OUT = utf8Stdout();

    private static PrintStream utf8Stdout() {
        try {
            return new PrintStream(new FileOutputStream(FileDescriptor.out), true, "UTF-8");
        } catch (Throwable t) {
            return System.out;
        }
    }

    /** 自检结果（命令入口直接回显这两个数）。 */
    public static final class SelfcheckResult {

        /** 一共判了几条。 */
        public int checks;
        /** 其中红的几条（0 = 全绿）。 */
        public int reds;

        public boolean green() {
            return reds == 0;
        }

        @Override
        public String toString() {
            return "checks=" + checks + " reds=" + reds;
        }
    }

    private static void expect(SelfcheckResult res, PrintStream out, String group, String name,
                              boolean expected, boolean actual, String reading) {
        res.checks++;
        final boolean ok = (expected == actual);
        if (!ok) {
            res.reds++;
        }
        out.println(String.format("%-4s | %-8s | %-52s | 期望=%-5s 实测=%-5s | %s",
                ok ? "PASS" : "RED", group, name, expected, actual, reading));
    }

    /** 造一帧（离线自检用；{@code line < 0} = 行号未知）。 */
    private static StackTraceElement frame(String cls, String method, int line) {
        return frame(cls, method, null, line);
    }

    /** 造一帧 ＋ 指定文件名（验 {@code pl:mixin:…} 标注时要它）。 */
    private static StackTraceElement frame(String cls, String method, String file, int line) {
        final int cut = cls.lastIndexOf('.');
        final String base = (cut >= 0 ? cls.substring(cut + 1) : cls) + ".java";
        return new StackTraceElement(cls, method, file == null ? base : file, line);
    }

    // ── 负对照用的"故意写坏"的实现（证明上面的绿不是恒绿） ──

    /** 【故意写坏】不看 R2/R3/R4，只看 R1。 */
    private static boolean tier1SolvedMutantOnlyR1(Readings r) {
        return r1(r);
    }

    /** 【故意写坏】过滤全关：任何帧都留（证明过滤确实在干活）。 */
    private static List<String> framesMutantNoFilter(StackTraceElement[] stack) {
        final List<String> out = new ArrayList<>();
        for (final StackTraceElement e : stack) {
            if (e != null && out.size() < STRIKE_KEPT_FRAMES) {
                out.add(e.getClassName() + "#" + e.getMethodName() + ":" + e.getLineNumber());
            }
        }
        return out;
    }

    /** 【故意写坏】永远取第 2 段（把会话前缀当 modId）。 */
    private static String injectedModIdMutantSecondSegment(String methodName) {
        if (!isInjectedMethod(methodName)) {
            return null;
        }
        final String[] parts = methodName.split("\\$", -1);
        return parts.length >= 2 && !parts[1].isEmpty() ? parts[1] : null;
    }

    /** 【故意写坏】不限流（时间窗口当不存在）。 */
    private static final class ThrottleMutantAlwaysOpen extends Throttle {

        ThrottleMutantAlwaysOpen(long windowMillis) {
            super(windowMillis);
        }

        @Override
        public synchronized boolean admit(String key, long nowMillis) {
            super.admit(key, nowMillis);
            return true;
        }
    }

    /**
     * 离线自检：{@code javac --release 17 -d <out> ShanhaiGuardTriage.java} 之后
     * {@code java -cp <out> com.shanhai.common.guard.ShanhaiGuardTriage}。
     *
     * <p>四类，<b>每类都有负对照</b>：
     * <ol>
     *   <li>① {@code tier1Solved} 真值表（含<b>普通强怪一棒</b>那一条 —— 必须 {@code solved=true}）；</li>
     *   <li>② 栈过滤（原版帧丢、注入帧留、{@code pl:mixin:} 标出来）；</li>
     *   <li>③ 注入方法名解析（含真样本 {@code handler$cni000$shooting_star_demo$quiet}）；</li>
     *   <li>④ 限流计数（同一 src 每人 5 秒 1 行）。</li>
     * </ol>
     * @return 自检结果（{@link #main} 用它决定退出码；命令入口用它回显）
     */
    public static SelfcheckResult selfcheck(PrintStream out) {
        final SelfcheckResult res = new SelfcheckResult();
        out.println("=== ShanhaiGuardTriage 离线自检（纯 JDK） ===");

        // ═══════ ① tier1Solved 真值表 ═══════
        final Readings strongHit = new Readings();
        strongHit.listenerFired = true;    // LivingAttackEvent 这一拍 fire 过
        strongHit.damageEvent = true;
        strongHit.rescued = true;          // 血被写到 0，但被我们救回来
        strongHit.hpTrue = 20.0f;          // 字段真值：满血
        strongHit.hpApi = 20.0f;
        expect(res, out, "①真值表", "【普通强怪一棒】血到 0 被救回来 ⇒ solved=true", true,
                tier1Solved(strongHit), line(strongHit, "LivingAttackEvent"));

        final Readings notSaved = copy(strongHit);
        notSaved.hpTrue = 0.0f;
        expect(res, out, "①真值表", "救过但字段真值还是 0 ⇒ R2=false、solved=false", false,
                tier1Solved(notSaved), "hp_true=0.0");

        final Readings unreadable = copy(strongHit);
        unreadable.hpTrue = Float.NaN;
        expect(res, out, "①真值表", "字段读不到（NaN）⇒ 不许声称解决（fail-closed）", false,
                tier1Solved(unreadable), "hp_true=NaN");

        final Readings noListener = copy(strongHit);
        noListener.listenerFired = false;
        noListener.damageEvent = false;
        expect(res, out, "①真值表", "监听器没 fire ⇒ R1=false、solved=false", false,
                tier1Solved(noListener), "事件层没说话");

        final Readings directNoEvent = copy(strongHit);
        directNoEvent.listenerFired = false;
        directNoEvent.damageEvent = false;
        directNoEvent.deathEvent = false;
        directNoEvent.directSetHealth = true;
        expect(res, out, "①真值表", "外部 setHealth(<=0) 且无事件 ⇒ R3=true、solved=false", false,
                tier1Solved(directNoEvent), line(directNoEvent, "direct"));

        final Readings directWithEvent = copy(strongHit);
        directWithEvent.directSetHealth = true;   // 同一拍有事件 ⇒ 不算"偷偷直调"
        expect(res, out, "①真值表", "外部 setHealth(<=0) 但同拍有事件 ⇒ R3=false、solved=true", true,
                tier1Solved(directWithEvent), line(directWithEvent, "direct"));

        final Readings dieNoDamage = copy(strongHit);
        dieNoDamage.damageEvent = false;
        dieNoDamage.dieOrRemove = true;
        expect(res, out, "①真值表", "die 触达且同拍无伤害事件 ⇒ R4=true、solved=false", false,
                tier1Solved(dieNoDamage), line(dieNoDamage, "die"));

        final Readings dieWithDamage = copy(strongHit);
        dieWithDamage.dieOrRemove = true;         // 有伤害事件 ⇒ R4 不成立
        expect(res, out, "①真值表", "die 触达但同拍有伤害事件 ⇒ R4=false、solved=true", true,
                tier1Solved(dieWithDamage), "同拍 a棒 → die");

        final Readings empty = new Readings();
        expect(res, out, "①真值表", "四个读数全空 ⇒ solved=false", false, tier1Solved(empty), "空读数");

        // 单独验四个读数函数（怕判据里漏掉哪一个）
        expect(res, out, "①读数", "R1 只看 listenerFired", true, r1(strongHit), "");
        expect(res, out, "①读数", "R2 需要『救过 ＋ 真值>0』两个条件", true, r2(strongHit), "");
        expect(res, out, "①读数", "R3 = 直调 且 无事件", true, r3(directNoEvent), "");
        expect(res, out, "①读数", "R4 = 触达 且 无伤害事件", true, r4(dieNoDamage), "");

        // 负对照：把判据退化成"只看 R1" ⇒ R1 为真但 R2/R4 该否决的用例必须被误判成 true
        expect(res, out, "①负对照", "只判 R1 的退化版会把『救过但真值仍为 0』误判成 solved（证明断言有判别力）",
                true, tier1SolvedMutantOnlyR1(notSaved),
                "退化版对同一读数 = " + tier1SolvedMutantOnlyR1(notSaved));
        expect(res, out, "①负对照", "只判 R1 的退化版会把『die 无伤害事件』误判成 solved",
                true, tier1SolvedMutantOnlyR1(dieNoDamage), "");
        expect(res, out, "①负对照", "退化版与正式判据在『强怪一棒』上一致（说明差异只出在坏情况）",
                true, tier1SolvedMutantOnlyR1(strongHit), "");

        // 日志行形状（规格点名的那 8 个字段一个都不能少）
        final String l = line(strongHit, "LivingAttackEvent");
        expect(res, out, "①格式", "行首是 [SHANHAI-TRIAGE]", true, l.startsWith(PREFIX), l);
        for (final String field : new String[] {
                "solved=true", "r1=true", "r2=true", "r3=false", "r4=false",
                "hp_true=20.0", "hp_api=20.0", "src=LivingAttackEvent"}) {
            expect(res, out, "①格式", "含字段 " + field, true, l.contains(field), l);
        }
        expect(res, out, "①格式", "NaN 如实写 NaN（不写 0.0）", true,
                line(unreadable, "direct").contains("hp_true=NaN"), line(unreadable, "direct"));

        // ═══════ ② 栈过滤 ═══════
        final StackTraceElement[] mixed = {
                frame("com.shanhai.common.guard.ShanhaiGuardTriage", "captureStrikeFrames", 300),
                frame("com.shanhai.common.guard.ShanhaiGuardTriageBridge", "noteDamage", 120),
                frame("java.lang.Thread", "run", 800),
                frame("net.minecraft.world.entity.LivingEntity", "hurt", 1234),
                frame("net.minecraftforge.eventbus.EventBus", "post", 120),
                frame("com.example.mod.Boss", "onAttack", 88)};
        final List<String> kept = framesKeptByRealFilter(mixed);
        expect(res, out, "②栈过滤", "原版帧（net.minecraft.*）被丢掉", true,
                !containsPrefix(kept, "net.minecraft."), String.valueOf(kept));
        expect(res, out, "②栈过滤", "forge 帧被丢掉", true,
                !containsPrefix(kept, "net.minecraftforge."), String.valueOf(kept));
        expect(res, out, "②栈过滤", "我们自己的帧被丢掉（否则 #1 永远是我们）", true,
                !containsPrefix(kept, "com.shanhai."), String.valueOf(kept));
        expect(res, out, "②栈过滤", "JDK 帧被丢掉", true, !containsPrefix(kept, "java."), "");
        expect(res, out, "②栈过滤", "外部 mod 帧留下", true,
                kept.size() == 1 && kept.get(0).startsWith("com.example.mod.Boss#onAttack:88"),
                String.valueOf(kept));

        // 原版族里的【注入方法】必须留下（这正是"谁改过这个类"）
        final String injectedFrame = frameTextOrNull(
                "net.minecraft.world.entity.LivingEntity", "handler$cni000$shooting_star_demo$quiet",
                "LivingEntity.java", -1);
        expect(res, out, "②栈过滤", "原版类上的注入方法帧【保留】（不是纯原版帧）", true,
                injectedFrame != null, String.valueOf(injectedFrame));
        expect(res, out, "②栈过滤", "注入帧带 {injected:shooting_star_demo} 标注", true,
                injectedFrame != null && injectedFrame.contains("{injected:shooting_star_demo}"),
                String.valueOf(injectedFrame));
        expect(res, out, "②栈过滤", "行号未知写 :?（不写 :-1）", true,
                injectedFrame != null && injectedFrame.contains(":?"), String.valueOf(injectedFrame));

        // 帧上的 pl:mixin:APP:<config>:<Class> 标注（真机崩溃报告里的形状）
        final StackTraceElement mixinFrame = frame(
                "mezz.jei.gui.recipes.RecipesGui", "showRecipes", "RecipesGui.java", 496);
        expect(res, out, "②栈过滤", "普通文件名的帧不带 mixin 标注", false,
                frameTextOrNull(mixinFrame).contains(MIXIN_MARKER), frameTextOrNull(mixinFrame));
        expect(res, out, "②栈过滤", "文件名里没有标注 ⇒ mixinMarker=null", true,
                mixinMarker("FooMixin.java", "com.example.mixin.FooMixin") == null, "");
        final String withMarker = frameTextOrNull("com.example.mixin.FooMixin", "onTick",
                "{pl:mixin:APP:shanhai.mixin.json:ShanhaiJeiBookmarkMixin}", 42);
        expect(res, out, "②栈过滤", "文件名带 pl:mixin:… ⇒ 原样标出来（花括号去掉）", true,
                withMarker != null && withMarker.contains("{pl:mixin:APP:shanhai.mixin.json:ShanhaiJeiBookmarkMixin}"),
                String.valueOf(withMarker));
        expect(res, out, "②栈过滤", "只写了 pl:mixin: 但后面没内容 ⇒ 不产生空标注", true,
                mixinMarker("{pl:mixin:}", "x.Y") == null, String.valueOf(mixinMarker("{pl:mixin:}", "x.Y")));
        expect(res, out, "②栈过滤", "标注只取一段（遇到空格/右花括号就停，且不带花括号）", true,
                "pl:mixin:APP:a.json:B".equals(
                        mixinMarker("{pl:mixin:APP:a.json:B} 尾巴", null)),
                String.valueOf(mixinMarker("{pl:mixin:APP:a.json:B} 尾巴", null)));

        // strike 行形状：留下 ／ 一个都不留
        expect(res, out, "②strike", "有帧 ⇒ at= ＋ frames=", true,
                strikeLine(1, kept).startsWith(PREFIX + "strike_caller#1 at=com.example.mod.Boss#onAttack:88 frames="),
                strikeLine(1, kept));
        expect(res, out, "②strike", "一帧都不留 ⇒ strike_caller#N none（不假装）", true,
                (PREFIX + "strike_caller#2 none").equals(strikeLine(2, new ArrayList<>())),
                strikeLine(2, new ArrayList<>()));
        expect(res, out, "②strike", "null 帧表也写 none（不抛）", true,
                strikeLine(3, null).endsWith(" none"), strikeLine(3, null));

        // 真·StackWalker 走一遍：从本自检里抓到的帧全是 com.shanhai./java.*
        // ⇒ 过滤后必须是空表（这同时证明"抓不到就写 none"这条路是活的）
        final List<String> fromRealStack = captureStrikeFrames();
        expect(res, out, "②strike", "真 StackWalker 抓本自检的栈 ⇒ 全被过滤（空表，不抛）", true,
                fromRealStack.isEmpty(), String.valueOf(fromRealStack));

        // 负对照：过滤全关 ⇒ 同样的栈会冒出一堆帧
        expect(res, out, "②负对照", "过滤关掉后同样的栈会冒帧（证明上面的空不是恒绿）", true,
                framesMutantNoFilter(mixed).size() > kept.size(),
                "不过滤时 " + framesMutantNoFilter(mixed).size() + " 帧");
        expect(res, out, "②负对照", "不过滤时原版帧会出现", true,
                containsPrefix(framesMutantNoFilter(mixed), "net.minecraft."), "");

        // 最多留 4 帧
        final StackTraceElement[] many = new StackTraceElement[10];
        for (int i = 0; i < many.length; i++) {
            many[i] = frame("com.example.mod.Thing" + i, "m", i);
        }
        expect(res, out, "②strike", "最多只留 4 帧（STRIKE_KEPT_FRAMES）", true,
                framesKeptByRealFilter(many).size() == STRIKE_KEPT_FRAMES,
                "实际 " + framesKeptByRealFilter(many).size());

        // ═══════ ③ 注入方法名解析 ═══════
        expect(res, out, "③解析", "真样本 handler$cni000$shooting_star_demo$quiet ⇒ shooting_star_demo",
                true, "shooting_star_demo".equals(
                        injectedModId("handler$cni000$shooting_star_demo$quiet")),
                String.valueOf(injectedModId("handler$cni000$shooting_star_demo$quiet")));
        expect(res, out, "③解析", "redirect$afa000$mymod$onHurt ⇒ mymod", true,
                "mymod".equals(injectedModId("redirect$afa000$mymod$onHurt")),
                String.valueOf(injectedModId("redirect$afa000$mymod$onHurt")));
        expect(res, out, "③解析", "wrapWithCondition$cni000$create$canPlace ⇒ create", true,
                "create".equals(injectedModId("wrapWithCondition$cni000$create$canPlace")),
                String.valueOf(injectedModId("wrapWithCondition$cni000$create$canPlace")));
        expect(res, out, "③解析", "modifyArg$zzz$FooMixin$bar ⇒ FooMixin（拿不到包名时最像谁的那段）", true,
                "FooMixin".equals(injectedModId("modifyArg$zzz$FooMixin$bar")),
                String.valueOf(injectedModId("modifyArg$zzz$FooMixin$bar")));
        expect(res, out, "③解析", "modifyVariable$aab$gtceu$damage ⇒ gtceu", true,
                "gtceu".equals(injectedModId("modifyVariable$aab$gtceu$damage")),
                String.valueOf(injectedModId("modifyVariable$aab$gtceu$damage")));
        expect(res, out, "③解析", "普通方法名 hurt ⇒ null（不许瞎猜）", true,
                injectedModId("hurt") == null, String.valueOf(injectedModId("hurt")));
        expect(res, out, "③解析", "handler$ 后面没东西 ⇒ null", true,
                injectedModId("handler$") == null, String.valueOf(injectedModId("handler$")));
        expect(res, out, "③解析", "handler$cni000（段数不够）⇒ null", true,
                injectedModId("handler$cni000") == null, String.valueOf(injectedModId("handler$cni000")));
        expect(res, out, "③解析", "isInjectedMethod 认得出 handler$/redirect$", true,
                isInjectedMethod("handler$x") && isInjectedMethod("redirect$x")
                        && !isInjectedMethod("onTick"), "");
        expect(res, out, "③解析", "injectedKind 去掉了末尾的 $", true,
                "handler".equals(injectedKind("handler$cni000$m$n"))
                        && "wrapWithCondition".equals(injectedKind("wrapWithCondition$a$m$n")), "");
        expect(res, out, "③解析", "modsOf 去重 ＋ 排序", true,
                "[aaa,shooting_star_demo]".equals(joinList(modsOf(List.of(
                        "handler$cni000$shooting_star_demo$quiet",
                        "handler$cni000$shooting_star_demo$loud",
                        "redirect$afa000$aaa$onHurt",
                        "plain")))),
                joinList(modsOf(List.of(
                        "handler$cni000$shooting_star_demo$quiet",
                        "handler$cni000$shooting_star_demo$loud",
                        "redirect$afa000$aaa$onHurt",
                        "plain"))));
        expect(res, out, "③解析", "空表写 []（不写 [null]）", true, "[]".equals(joinList(modsOf(null))),
                joinList(modsOf(null)));

        // 负对照：把会话前缀当 modId 的退化版 ⇒ 真样本上必须抽错
        final String mutantMod = injectedModIdMutantSecondSegment("handler$cni000$shooting_star_demo$quiet");
        expect(res, out, "③负对照", "退化版（永远取第 2 段）在真样本上抽出 cni000 ⇒ 与正确值不同",
                false, "shooting_star_demo".equals(mutantMod),
                "退化版抽出 " + mutantMod);

        // ═══════ ④ 限流计数 ═══════
        final Throttle th = new Throttle(THROTTLE_WINDOW_MILLIS);
        expect(res, out, "④限流", "t=0 第一次放行", true, th.admit("p1|LivingAttackEvent", 0L), "");
        expect(res, out, "④限流", "t=4999 同一键被压掉", false, th.admit("p1|LivingAttackEvent", 4999L), "");
        expect(res, out, "④限流", "t=5000 正好跨窗口 ⇒ 放行（>= 而非 >）", true,
                th.admit("p1|LivingAttackEvent", 5000L), "");
        expect(res, out, "④限流", "另一个玩家同一 src 不受影响", true, th.admit("p2|LivingAttackEvent", 5001L), "");
        expect(res, out, "④限流", "同一玩家另一个 src 不受影响", true, th.admit("p1|direct", 5002L), "");
        expect(res, out, "④限流", "空键不拦（宁可多打一行也不静默丢证据）", true, th.admit(null, 5003L), "");
        expect(res, out, "④限流", "账本里 3 个键（p1|攻击 ／ p2|攻击 ／ p1|direct）", true, th.size() == 3,
                "size=" + th.size());
        th.clear();
        expect(res, out, "④限流", "clear 之后窗口重新开", true, th.admit("p1|LivingAttackEvent", 0L), "");

        // 负对照：不限流的退化版 ⇒ 4999 那一拍也会放行
        final Throttle mutant = new ThrottleMutantAlwaysOpen(THROTTLE_WINDOW_MILLIS);
        mutant.admit("p1|x", 0L);
        expect(res, out, "④负对照", "不限流的退化版在 4999 那一拍会放行（证明上面那条 false 不是恒假）",
                true, mutant.admit("p1|x", 4999L), "");

        // 配额（strike 每人每局 3 次）
        final Quota q = new Quota(MAX_STRIKE_CALLERS);
        expect(res, out, "④配额", "第 1 次是 #1", true, q.take("u1") == 1, "");
        expect(res, out, "④配额", "第 2 次是 #2", true, q.take("u1") == 2, "");
        expect(res, out, "④配额", "第 3 次是 #3", true, q.take("u1") == 3, "");
        expect(res, out, "④配额", "第 4 次是 0（不再打）", true, q.take("u1") == 0, "");
        expect(res, out, "④配额", "另一个人自己的编号从 #1 开始", true, q.take("u2") == 1, "");
        expect(res, out, "④配额", "used(u1) 停在 3（没偷偷加一）", true, q.used("u1") == 3,
                "used=" + q.used("u1"));
        final Quota unlimited = new Quota(Integer.MAX_VALUE);
        expect(res, out, "④负对照", "max 很大时第 4 次会返回 4（证明上面那条 0 是判据给的）", true,
                unlimited.take("u1") == 1 && unlimited.take("u1") == 2
                        && unlimited.take("u1") == 3 && unlimited.take("u1") == 4, "");

        out.println("---------------------------------------------------------------");
        out.println("合计 " + res.checks + " 条，红 " + res.reds + " 条 ⇒ " + (res.green() ? "全绿 PASS" : "有红 FAIL"));
        return res;
    }

    private static Readings copy(Readings src) {
        final Readings r = new Readings();
        r.listenerFired = src.listenerFired;
        r.damageEvent = src.damageEvent;
        r.deathEvent = src.deathEvent;
        r.rescued = src.rescued;
        r.directSetHealth = src.directSetHealth;
        r.dieOrRemove = src.dieOrRemove;
        r.hpTrue = src.hpTrue;
        r.hpApi = src.hpApi;
        return r;
    }

    /** 用<b>正式</b>过滤器跑一遍假栈（不依赖 StackWalker，方便在自检里造场景）。 */
    private static List<String> framesKeptByRealFilter(StackTraceElement[] stack) {
        final List<String> out = new ArrayList<>();
        for (final StackTraceElement e : stack) {
            if (out.size() >= STRIKE_KEPT_FRAMES) {
                break;
            }
            final String text = frameTextOrNull(e);
            if (text != null) {
                out.add(text);
            }
        }
        return out;
    }

    private static boolean containsPrefix(List<String> texts, String prefix) {
        for (final String t : texts) {
            if (t.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 离线自检入口：
     * {@code javac --release 17 -d <out> ShanhaiGuardTriage.java} ＋ {@code java -cp <out> com.shanhai.common.guard.ShanhaiGuardTriage}。
     */
    public static void main(String[] args) {
        final SelfcheckResult res = selfcheck(OUT);
        if (!res.green()) {
            System.exit(1);
        }
    }
}
