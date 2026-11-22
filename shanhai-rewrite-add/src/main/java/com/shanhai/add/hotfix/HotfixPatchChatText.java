package com.shanhai.add.hotfix;

import java.util.Map;

/**
 * 热改播报的<b>正文拼装</b>（纯 JDK：零 Forge、零 MC、零 ASM、零日志框架）。
 *
 * <h2>它拼的是"前缀后面那一段"</h2>
 * 前缀 {@code [创始现实修改模块] }（含末尾那<b>一个半角空格</b>）由本体的守卫聊天通道加，
 * 本类<b>一个字都不碰</b> —— 前缀的唯一真源是 {@code ShanhaiGuardCore#CHAT_PREFIX}。
 *
 * <pre>
 *   PATCHED         examplemod ExampleHelper#doHurt -> RETURN (health)
 *   PATCHED         examplemod ExampleHelper#playHitFx -> RETURN (die)
 *   PATCHED         examplemod ExampleEntity#m_8119_ -> RETURN (motion)
 *   PATCH_REFUSED   examplemod ExampleHelper#doHurt (gate_already_patched)
 *   PATCH_REVERTED  examplemod ExampleHelper#doHurt (observed_only)
 * </pre>
 * 接上前缀之后逐字就是用户给的那五条样例的<b>形态</b>（样例里的 modid／类名一律用中性占位
 * {@code examplemod}／{@code examplemod.util.…}，本类不认识任何具体 mod；
 * 自检 {@code main} 里逐字断言，正负各一）。
 *
 * <h2>三条口径（都是用户逐字定的）</h2>
 * <ol>
 *   <li>{@code <modid>} = 目标类所属 mod（从 {@code ModList} 反查，见 {@link #modIdOf}）；查不到写
 *       {@code unknown}（<b>不许猜</b>）。</li>
 *   <li>{@code <Simple>#<method>} = <b>简单类名</b>（只去包名，{@code $} 内部类后缀照旧保留）＋ {@code #}
 *       ＋ 方法名（<b>SRG 名原样</b>，如 {@code m_8119_}；{@code <init>} 也照原样）。</li>
 *   <li>括号里 = {@code why=} / {@code detail=} / {@code reason=} 的<b>原值</b>，不翻译、不改写。</li>
 * </ol>
 *
 * <h2>为什么正文全都要过 {@link #isPrintableAscii}</h2>
 * 聊天栏那条通道有一条硬判据：<b>正文纯 ASCII</b>（非 ASCII 会被本体当场拒收）。
 * 所以本类在拼完之后<b>自己再验一次</b>：验不过就返回 {@code null}
 * （调用方据此<b>不发</b>，而不是发一条带着乱码/中文的半成品）。
 * 这样"格式化错"和"通道拒收"不会互相掩护。
 */
public final class HotfixPatchChatText {

    /** 动作词：一次 patch 真的改成功了（逐字）。 */
    public static final String WORD_PATCHED = "PATCHED";

    /** 动作词：被门槛拒绝（括号里是 {@code detail} 原值）。 */
    public static final String WORD_REFUSED = "PATCH_REFUSED";

    /** 动作词：被撤掉／没落地（括号里是 {@code reason} 原值；<b>只报不重打</b>）。 */
    public static final String WORD_REVERTED = "PATCH_REVERTED";

    /**
     * 🆕 动作词：<b>升级到强力档</b>（{@code attack=line ⇒ attack=method}）。
     *
     * <p>为什么单独一个词：普通攻击只让"那一行"失效（玩家几乎无感），强力档会把<b>整个方法</b>停摆。
     * 这种副作用区别必须在聊天栏里一眼看出来，不能被当成"又一次普通补丁"。
     */
    public static final String WORD_ESCALATED = "PATCH_ESCALATED";

    /** 成功那条的固定中缀：动作词与目标之间是 {@code " -> RETURN "}（逐字，含两侧各一个空格）。 */
    public static final String ARROW_RETURN = " -> RETURN ";

    /** 反查不到"目标类属于哪个 mod"时写这个（逐字，不许猜一个像样的名字）。 */
    public static final String UNKNOWN_MODID = "unknown";

    /** 括号里的原值缺失时写这个（只有"日志里本来就没给"才会走到；不许编一个原因）。 */
    public static final String UNKNOWN_ARG = "?";

    private HotfixPatchChatText() {
        // 纯静态
    }

    // ═══════════════════════════════ 三条正文（唯一入口） ═══════════════════════════════

    /**
     * 成功那条的正文：{@code PATCHED <modid> <Simple>#<method> -> RETURN (<why>)}。
     *
     * @param modId     目标类所属 mod（{@code null}/空/非 ASCII ⇒ {@code unknown}）
     * @param className 目标类全名（例：{@code examplemod.util.ExampleHelper}；<b>只去包名</b>）
     * @param method    方法名（SRG 原样；例：{@code m_8119_}）
     * @param why       {@code why=} 原值（例：{@code health}／{@code die}／{@code motion}）
     * @return 正文（纯 ASCII）；拼不出来（类名/方法名缺失或含非 ASCII）⇒ {@code null}
     */
    public static String patched(String modId, String className, String method, String why) {
        return build(WORD_PATCHED, modId, className, method, why, true);
    }

    /**
     * 被拒绝那条的正文：{@code PATCH_REFUSED <modid> <Simple>#<method> (<detail>)}。
     *
     * @param detail {@code detail=} 原值（例：{@code gate_already_patched}）
     */
    public static String refused(String modId, String className, String method, String detail) {
        return build(WORD_REFUSED, modId, className, method, detail, false);
    }

    /**
     * 被回退那条的正文：{@code PATCH_REVERTED <modid> <Simple>#<method> (<reason>)}。
     *
     * @param reason {@code reason=} 原值（例：{@code observed_only}／{@code plan_failed}）
     */
    public static String reverted(String modId, String className, String method, String reason) {
        return build(WORD_REVERTED, modId, className, method, reason, false);
    }

    /**
     * 🆕 升级那条的正文：{@code PATCH_ESCALATED <modid> <Simple>#<method> -> RETURN (<why>)}。
     *
     * @param why {@code why=} 原值（例：{@code still_hitting}／{@code line_no_site}／{@code health}）
     */
    public static String escalated(String modId, String className, String method, String why) {
        return build(WORD_ESCALATED, modId, className, method, why, true);
    }

    /**
     * 这个词的正文用不用 {@code " -> RETURN "} 那一形（成功／升级都用；拒绝／回退不用）。
     *
     * @param word 动作词
     */
    public static boolean usesArrow(String word) {
        return WORD_PATCHED.equals(word) || WORD_ESCALATED.equals(word);
    }

    /**
     * 按动作词拼一条（{@link #patched}／{@link #refused}／{@link #reverted} 的共同实现）。
     *
     * @param word      动作词（只认三个常量；别的词 ⇒ {@code null}，免得把内部枚举漏给玩家）
     * @param returnForm {@code true} = 成功那条的 {@code " -> RETURN (…)"} 形；
     *                   {@code false} = {@code " (…)"} 形
     * @return 正文；拼不出来 ⇒ {@code null}
     */
    public static String build(String word, String modId, String className, String method, String arg,
                               boolean returnForm) {
        if (word == null) {
            return null;
        }
        final boolean known = WORD_PATCHED.equals(word) || WORD_REFUSED.equals(word)
                || WORD_REVERTED.equals(word) || WORD_ESCALATED.equals(word);
        if (!known) {
            return null;
        }
        final String simple = simpleOf(className);
        final String meth = methodOrNull(method);
        if (simple == null || meth == null) {
            return null;   // 没有类名／方法名：这一条根本没法说清"改了谁"
        }
        // 🔴 给了值就必须是可打印 ASCII；**非 ASCII 一律整条不发**（不拿"?"/"unknown"顶替，
        //    那样会把"这一条说不清"伪装成"这一条说清了"）。
        if (modId != null && !modId.isEmpty() && !isPrintableAscii(modId)) {
            return null;
        }
        if (arg != null && !arg.isEmpty() && !isPrintableAscii(arg)) {
            return null;
        }
        final String mod = modId == null || modId.isEmpty() ? UNKNOWN_MODID : modId;
        final String bracket = arg == null || arg.isEmpty() ? UNKNOWN_ARG : arg;
        final StringBuilder sb = new StringBuilder(96);
        sb.append(word).append(' ').append(mod).append(' ').append(simple).append('#').append(meth);
        if (returnForm) {
            sb.append(ARROW_RETURN).append('(').append(bracket).append(')');
        } else {
            sb.append(" (").append(bracket).append(')');
        }
        final String body = sb.toString();
        // 🔴 最后一道：正文必须能被本体的那条硬判据接受（纯 ASCII、无控制字符）。
        //    在这里拦下来的好处是"发不出去"和"拼错了"不会互相掩护。
        return isPrintableAscii(body) ? body : null;
    }

    // ═══════════════════════════════ 名字与字段的小工具（纯函数） ═══════════════════════════════

    /**
     * 类名 → <b>简单类名</b>（只去包名）。
     *
     * <pre>
     *   examplemod.util.ExampleHelper               ⇒ ExampleHelper
     *   examplemod.util.ExampleHelper$Inner         ⇒ ExampleHelper$Inner   （$ 后缀照旧保留）
     *   ExampleHelper                               ⇒ ExampleHelper         （本来就没有包名）
     * </pre>
     *
     * @return 简单类名；{@code className} 为空或含非 ASCII ⇒ {@code null}
     */
    public static String simpleOf(String className) {
        if (!isPrintableAscii(className) || className.isEmpty()) {
            return null;
        }
        final int dot = className.lastIndexOf('.');
        final String simple = dot < 0 ? className : className.substring(dot + 1);
        return simple.isEmpty() ? null : simple;
    }

    /** 方法名（SRG 原样、{@code <init>} 原样）；空／含非 ASCII ⇒ {@code null}。 */
    public static String methodOrNull(String method) {
        if (!isPrintableAscii(method) || method.isEmpty()) {
            return null;
        }
        return method;
    }

    /**
     * 把账本里的目标键 {@code 类#方法} 拆成两段。
     *
     * <p>为什么按<b>最后一个</b> {@code '#'} 拆：类名里不会出现 {@code '#'}，而方法单独拿出来时可能带
     * 描述符（{@code Foo#m(I)V} 这种形）—— 按最后一个拆，{@code 类#方法} 两段才是干净的
     * （本工程的账本键就是 {@code className + "#" + methodName}，方法名里没有 {@code '#'}）。
     *
     * @return {@code {类全名, 方法名}}；没有 {@code '#'} 或任一段为空 ⇒ {@code null}
     */
    public static String[] splitTargetKey(String key) {
        if (!isPrintableAscii(key)) {
            return null;
        }
        final int hash = key.lastIndexOf('#');
        if (hash <= 0 || hash == key.length() - 1) {
            return null;
        }
        final String cls = key.substring(0, hash);
        final String meth = key.substring(hash + 1);
        return new String[] { cls, meth };
    }

    /**
     * 目标类属于哪个 mod（<b>前缀最长匹配</b>；不猜）。
     *
     * <p>{@code namespaceToModId} 的键是"某个 mod 的 {@code @Mod} 实例类所在的包"（末尾带不带 {@code .}
     * 都行，本方法自己补）；值是那个 mod 的 {@code modId}。这张表由 Forge 那侧从 {@code ModList} 取出来
     * 灌进来 —— 于是本类仍然是纯 JDK。
     *
     * @return {@code modId}；匹配不到／表是空的／类名非法 ⇒ {@link #UNKNOWN_MODID}
     */
    public static String modIdOf(String className, Map<String, String> namespaceToModId) {
        if (!isPrintableAscii(className) || className.isEmpty()
                || namespaceToModId == null || namespaceToModId.isEmpty()) {
            return UNKNOWN_MODID;
        }
        String bestPrefix = null;
        String bestMod = null;
        for (Map.Entry<String, String> e : namespaceToModId.entrySet()) {
            final String ns = e.getKey();
            final String mod = e.getValue();
            if (!isPrintableAscii(ns) || ns.isEmpty() || !isPrintableAscii(mod) || mod.isEmpty()) {
                continue;
            }
            final String prefix = ns.endsWith(".") ? ns : ns + ".";
            if (!className.startsWith(prefix)) {
                continue;
            }
            if (bestPrefix == null || prefix.length() > bestPrefix.length()) {
                bestPrefix = prefix;
                bestMod = mod;
            }
        }
        return bestMod == null ? UNKNOWN_MODID : bestMod;
    }

    /**
     * 🔴 判据：这一段（或最后拼好的整条正文）是不是<b>可打印 ASCII</b>。
     *
     * <pre>
     *   null                             ⇒ false
     *   含任何一个 &gt; 0x7F 的字符      ⇒ false（中文／全角符号／§ 色码）
     *   含控制字符（&lt; 0x20 或 0x7F）   ⇒ false（换行会把"一行"顶成两行）
     *   其余（含空串）                   ⇒ true
     * </pre>
     *
     * <p>与本体那条硬判据（{@code ShanhaiGuardCore#isBroadcastableBody}）<b>同一条口径</b>：
     * 这里只多一个"允许空串"的差别（因为本类要判单个字段，而本体判的是整条正文）。
     */
    public static boolean isPrintableAscii(String text) {
        if (text == null) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (c > 0x7F) {
                return false;
            }
            if (c < 0x20 || c == 0x7F) {
                return false;
            }
        }
        return true;
    }

    // ═══════════════════════════════ 离线自检（同文件 main，纯 JDK 可跑） ═══════════════════════════════

    private static int checks;
    private static int reds;

    private static void expect(String name, boolean expected, boolean actual, String reading) {
        checks++;
        final boolean ok = expected == actual;
        if (!ok) {
            reds++;
        }
        System.out.println(String.format("%-4s | %-34s | 期望=%-5s 实测=%-5s | %s",
                ok ? "PASS" : "RED", name, expected, actual, reading));
    }

    /**
     * 离线自检：<b>给定 modid／类／方法／why ⇒ 逐字等于用户样例</b>（正负各一），外加三条格式口径。
     *
     * <pre>
     *   javac -encoding UTF-8 -d &lt;out&gt; HotfixPatchChatText.java
     *   java  -Dfile.encoding=UTF-8 -cp &lt;out&gt; com.shanhai.add.hotfix.HotfixPatchChatText
     * </pre>
     * 有任何一条不成立 ⇒ 退出码 1。
     */
    public static void main(String[] args) {
        System.out.println("=== [SHANHAI-HOTFIX] 热改播报正文拼装 · 离线自检（纯 JDK） ===");
        // 五条样例的形态（modid／类名用中性占位 examplemod：本类不认识任何具体 mod）
        final String m1 = patched("examplemod", "examplemod.util.ExampleHelper", "doHurt", "health");
        final String m2 = patched("examplemod", "examplemod.util.ExampleHelper", "playHitFx", "die");
        final String m3 = patched("examplemod", "examplemod.entity.ExampleEntity",
                "m_8119_", "motion");
        expect("①PATCHED health 逐字", true,
                "PATCHED examplemod ExampleHelper#doHurt -> RETURN (health)".equals(m1), "→ " + m1);
        expect("①PATCHED die 逐字", true,
                "PATCHED examplemod ExampleHelper#playHitFx -> RETURN (die)".equals(m2), "→ " + m2);
        expect("①SRG 名 m_8119_ 原样（下划线不动）", true,
                "PATCHED examplemod ExampleEntity#m_8119_ -> RETURN (motion)".equals(m3), "→ " + m3);
        final String r1 = refused("examplemod", "examplemod.util.ExampleHelper", "doHurt",
                "gate_already_patched");
        expect("②PATCH_REFUSED 逐字", true,
                "PATCH_REFUSED examplemod ExampleHelper#doHurt (gate_already_patched)".equals(r1), "→ " + r1);
        final String v1 = reverted("examplemod", "examplemod.util.ExampleHelper", "doHurt",
                "observed_only");
        expect("③PATCH_REVERTED 逐字", true,
                "PATCH_REVERTED examplemod ExampleHelper#doHurt (observed_only)".equals(v1), "→ " + v1);

        // ---- 负对照：同一组输入换一处，必须【不等】（证明上面那三条不是"怎么拼都过"）
        final String wrongMod = patched("unknown", "examplemod.util.ExampleHelper", "doHurt", "health");
        expect("负①换 modid ⇒ 与样例不等", true,
                !"PATCHED examplemod ExampleHelper#doHurt -> RETURN (health)".equals(wrongMod), "→ " + wrongMod);
        final String wrongWhy = patched("examplemod", "examplemod.util.ExampleHelper", "doHurt", "die");
        expect("负②换 why ⇒ 与样例不等", true,
                !"PATCHED examplemod ExampleHelper#doHurt -> RETURN (health)".equals(wrongWhy), "→ " + wrongWhy);
        final String noBracket = "PATCH_REFUSED examplemod ExampleHelper#doHurt gate_already_patched";
        expect("负③少了那对括号的写法 ⇒ 与样例不等", true,
                !"PATCH_REFUSED examplemod ExampleHelper#doHurt (gate_already_patched)".equals(noBracket)
                        && r1 != null && !r1.equals(noBracket),
                "少括号那条=" + noBracket + " ｜ 真实读数=" + r1);

        // ---- 口径：纯 ASCII／查不到 mod ⇒ unknown／名字的边界
        expect("④非 ASCII 的 why ⇒ 整条不发（null）", true,
                patched("examplemod", "examplemod.util.ExampleHelper", "doHurt", "血量") == null,
                "→ " + patched("examplemod", "examplemod.util.ExampleHelper", "doHurt", "血量"));
        expect("④非 ASCII 的类名 ⇒ null", true, patched("examplemod", "examplemod.util.实体", "m", "health") == null,
                "→ " + patched("examplemod", "examplemod.util.实体", "m", "health"));
        expect("④查不到 mod ⇒ unknown（不猜）", true,
                "PATCHED unknown ExampleHelper#doHurt -> RETURN (health)".equals(
                        patched(null, "examplemod.util.ExampleHelper", "doHurt", "health")),
                "→ " + patched(null, "examplemod.util.ExampleHelper", "doHurt", "health"));
        expect("④非 ASCII 的 mod ⇒ 整条不发（不拿 unknown 顶替一个非 ASCII 的 mod 名）", true,
                patched("敌方模组", "a.b.ExampleHelper", "m", "health") == null,
                "→ " + patched("敌方模组", "a.b.ExampleHelper", "m", "health"));
        expect("④mod 缺失（null）⇒ unknown", true,
                "PATCHED unknown ExampleHelper#m -> RETURN (health)".equals(
                        patched(null, "a.b.ExampleHelper", "m", "health")),
                "→ " + patched(null, "a.b.ExampleHelper", "m", "health"));
        expect("④括号里的原值缺失（null/空）⇒ ?", true,
                "PATCH_REFUSED examplemod ExampleHelper#m (?)".equals(
                        refused("examplemod", "a.b.ExampleHelper", "m", "")),
                "→ " + refused("examplemod", "a.b.ExampleHelper", "m", ""));
        final java.util.Map<String, String> table = new java.util.LinkedHashMap<>();
        table.put("examplemod.", "examplemod");
        table.put("com.other.", "other");
        expect("⑤前缀最长匹配 = examplemod", true,
                "examplemod".equals(modIdOf("examplemod.util.ExampleHelper", table)),
                "→ " + modIdOf("examplemod.util.ExampleHelper", table));
        expect("⑤不在任何 mod 的命名空间 ⇒ unknown", true,
                "unknown".equals(modIdOf("net.minecraft.world.entity.LivingEntity", table)),
                "→ " + modIdOf("net.minecraft.world.entity.LivingEntity", table));
        table.put("examplemod.util.", "deep");
        expect("⑤更长的前缀优先（不按插入顺序）", true,
                "deep".equals(modIdOf("examplemod.util.ExampleHelper", table)),
                "→ " + modIdOf("examplemod.util.ExampleHelper", table));
        final String[] split = splitTargetKey("examplemod.util.ExampleHelper#m_8119_");
        expect("⑥账本键拆两段", true, split != null && "examplemod.util.ExampleHelper".equals(split[0])
                && "m_8119_".equals(split[1]), "→ " + (split == null ? "null" : split[0] + " ／ " + split[1]));
        expect("⑥没有 # 的键 ⇒ null", true, splitTargetKey("ExampleHelper") == null, "→ " + splitTargetKey("ExampleHelper"));
        expect("⑥<init> 原样保留", true,
                "PATCHED examplemod ExampleHelper#<init> -> RETURN (pos)".equals(
                        patched("examplemod", "a.b.ExampleHelper", "<init>", "pos")),
                "→ " + patched("examplemod", "a.b.ExampleHelper", "<init>", "pos"));

        System.out.println("=== 汇总：判据 " + checks + " 条，报红 " + reds + " 条 ⇒ "
                + (reds == 0 ? "全绿" : "有红 ✗") + " ===");
        if (reds != 0) {
            System.exit(1);
        }
    }
}
