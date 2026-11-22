package com.shanhai.add.hotfix;

import java.util.ArrayList;
import java.util.List;

/**
 * 把本体的 {@code strike_caller#N} 那一行<b>拆成栈帧</b>，再从<b>栈顶往下</b>取第一个
 * <b>同时</b>满足两条判据的帧当作热改目标。
 *
 * <h2>两条判据（2026-11-20 改判据：从白名单式改成黑名单式）</h2>
 * <ol>
 *   <li><b>帧的类不在包名白名单里</b>（白名单见 {@link HotfixPatchWhitelist#NEVER_PATCH_PREFIXES}）；</li>
 *   <li><b>该类的 code source / 加载器过得去</b>：不是我们自己的 jar、不是 JDK 运行时、不是 JDK 三件套
 *       加载器；<b>`CodeSource` 拿不到也照样可能通过</b>（要一条二级信号，见
 *       {@link HotfixPatchWhitelist#judge(HotfixPatchWhitelist.Probe)}）。</li>
 * </ol>
 * 🔴 判据里<b>没有</b>"它必须来自 {@code mods\}}"这一条 —— 那条会漏掉 agent / {@code Unsafe} /
 * {@code defineClass} 注入的类（它们的 {@code CodeSource} 往往是 {@code null}）。
 *
 * <h2>为什么"第一个非白名单帧"要我们自己也判一遍</h2>
 * 本体的过滤口径是「丢掉纯原版帧、丢掉 {@code com.shanhai.*}、丢掉 JDK 噪声」，
 * <b>但保留原版类上的注入方法</b>（{@code handler$…}，因为它能说明"谁改过这个类"）。
 * 那些注入帧的类名是 {@code net.minecraft.*} ⇒ 对我们来说照样是<b>不许改</b>的目标。
 * 所以这一层必须用 {@link HotfixPatchWhitelist} 再筛一次，不能直接用 {@code frames[0]}。
 *
 * <h2>本体的行长什么样（逐字引自 {@code ShanhaiGuardTriage.strikeLine(...)}）</h2>
 * <pre>
 *   [SHANHAI-TRIAGE] strike_caller#1 at=examplemod.util.ExampleHelper#forceHurt:169 \
 *        frames=examplemod.util.ExampleHelper#forceHurt:169 | other.Mod#m:12 ｜ player=Steve
 *   [SHANHAI-TRIAGE] strike_caller#2 none ｜ player=Steve
 * </pre>
 * 一帧的形态是 {@code 类名#方法名:行号}（行号未知写 {@code :?}），后面可能跟两种标注：
 * {@code {pl:mixin:APP:…}}（这个类被哪份 mixin 配置改过）与 {@code {injected:modId}}（Mixin 生成的注入方法）。
 * 栈帧之间用 <b>ASCII</b> {@code " | "} 分隔，玩家名之前是<b>全角</b> {@code ｜} ——
 * 顺序必须"先切全角、再切帧"，否则 {@code | player=} 会被当成一帧。
 *
 * <p>纯 JDK（只要 {@code java.util}），可离线验。
 */
public final class HotfixTargetResolver {

    /** 本体那一行的标记。 */
    public static final String STRIKE_MARK = "strike_caller#";

    /** 栈帧之间的分隔符（ASCII）。 */
    public static final String FRAME_SEPARATOR = " | ";

    private HotfixTargetResolver() {
        // 纯静态工具
    }

    /** 一个栈帧。 */
    public static final class Frame {

        /** 源级类名，例如 {@code examplemod.util.ExampleHelper}。 */
        public final String className;

        /** 方法名（可能是 Mixin 生成的 {@code handler$…}）。 */
        public final String methodName;

        /** 行号；未知 ⇒ -1。 */
        public final int line;

        /** 花括号里那一段标注（{@code pl:mixin:…}/{@code injected:…}）；没有 ⇒ {@code null}。 */
        public final String annotation;

        /** 原样文本（留档用）。 */
        public final String raw;

        Frame(String className, String methodName, int line, String annotation, String raw) {
            this.className = className;
            this.methodName = methodName;
            this.line = line;
            this.annotation = annotation;
            this.raw = raw;
        }

        /** {@code 类#方法:行} 的规范写法。 */
        public String brief() {
            return className + "#" + methodName + ":" + (line >= 0 ? String.valueOf(line) : "?");
        }

        @Override
        public String toString() {
            return annotation == null ? brief() : brief() + " {" + annotation + "}";
        }
    }

    /** 一条 {@code strike_caller#N} 行的解析结果。 */
    public static final class Strike {

        /** 本体的抓栈序号（1..3；解析失败 ⇒ -1）。 */
        public final int index;

        /** 过滤后的帧（本体已经滤过一层；这里只做解析，不再改口径）。 */
        public final List<Frame> frames;

        /** 尾部 {@code player=}；没有 ⇒ {@code null}。 */
        public final String player;

        /** 原始那一行。 */
        public final String raw;

        Strike(int index, List<Frame> frames, String player, String raw) {
            this.index = index;
            this.frames = frames;
            this.player = player;
            this.raw = raw;
        }
    }

    /** 选中的目标。 */
    public static final class Target {

        public final String className;
        public final String methodName;
        public final int line;

        /** 在它之前被<b>包名白名单</b>挡掉了几个帧（证据：说明确实跳过了原版注入帧）。 */
        public final int framesSkipped;

        /** 在它之前被<b>来源判据</b>挡掉了几个帧（例如"我们自己的 jar"／"JDK 加载器"）。 */
        public final int framesNotForeign;

        /** 原样文本。 */
        public final String raw;

        /** 选它的那一次判定（{@code reason=ok_code_mods}/{@code ok_code_none_loader}/…；可读日志口径）。 */
        public final String reason;

        /** 它的 {@code code_source=} 标记（{@code yes}/{@code none}）。 */
        public final String codeSourceToken;

        Target(String className, String methodName, int line, int framesSkipped, int framesNotForeign,
               String raw, String reason, String codeSourceToken) {
            this.className = className;
            this.methodName = methodName;
            this.line = line;
            this.framesSkipped = framesSkipped;
            this.framesNotForeign = framesNotForeign;
            this.raw = raw;
            this.reason = reason == null ? "?" : reason;
            this.codeSourceToken = codeSourceToken == null ? "none" : codeSourceToken;
        }

        public String brief() {
            return className + "#" + methodName;
        }

        @Override
        public String toString() {
            return brief() + " line=" + (line >= 0 ? String.valueOf(line) : "?")
                    + " frames_skipped=" + framesSkipped
                    + " frames_not_foreign=" + framesNotForeign
                    + " reason=" + reason
                    + " code_source=" + codeSourceToken;
        }
    }

    /** 一次"从栈里挑目标"的完整结论（{@link #reason} 为空 = 成功）。 */
    public static final class Resolution {

        /** 挑中的目标；{@code null} = 一个都不合格。 */
        public final Target target;

        /**
         * 没挑到时的机器可判原因：
         * {@code gate_no_strike_frame}（一帧都没有）／{@code gate_all_frames_whitelisted}（全被包名挡掉）／
         * {@code gate_no_foreign_frame}（过了包名、但来源判据都不放行）；
         * 挑到时为空串。
         */
        public final String reason;

        /** 最后一帧被来源判据拒掉的那个原因（{@code detail=} 用；没有 ⇒ 空串）。 */
        public final String detail;

        public final int framesSkipped;
        public final int framesNotForeign;

        Resolution(Target target, String reason, String detail, int framesSkipped, int framesNotForeign) {
            this.target = target;
            this.reason = reason == null ? "" : reason;
            this.detail = detail == null ? "" : detail;
            this.framesSkipped = framesSkipped;
            this.framesNotForeign = framesNotForeign;
        }

        public boolean ok() {
            return target != null;
        }
    }

    /**
     * 按类名在<b>已加载</b>的类里找 {@code Class}（{@code null} = 找不到／拿不到）。
     *
     * <p>为什么要一个接口：本类必须是<b>纯 JDK</b>（离线自检直接用同一份字节码），
     * 而"按名字取已加载的 {@code Class}"要 {@code Instrumentation} 或反射 —— 交给调用方。
     */
    public interface Lookup {
        Class<?> findLoaded(String className);
    }

    // ═══════════════════════════════ 解析 ═══════════════════════════════

    /**
     * 解析一条 {@code strike_caller#N} 行。
     *
     * @param line 日志原文
     * @return 解析结果；这一行不是 strike 行 ⇒ {@code null}（{@code none} 是合法结果，帧表为空）
     */
    public static Strike parseStrike(String line) {
        if (line == null || line.isEmpty()) {
            return null;
        }
        final int mark = line.indexOf(STRIKE_MARK);
        if (mark < 0) {
            return null;
        }
        int i = mark + STRIKE_MARK.length();
        final int digitsAt = i;
        while (i < line.length() && Character.isDigit(line.charAt(i))) {
            i++;
        }
        int index = -1;
        if (i > digitsAt) {
            try {
                index = Integer.parseInt(line.substring(digitsAt, i));
            } catch (NumberFormatException e) {
                index = -1;
            }
        }
        String rest = line.substring(i).trim();
        final String player = HotfixTriageVerdict.playerOf(line);
        // ① 先切玩家尾巴：全角竖线（本体用的那个）
        rest = cutAt(rest, HotfixTriageVerdict.FULLWIDTH_BAR);
        // ② 再切 ASCII 形态的尾巴。⚠️ 只能认【带 player= 的】那一个：
        //    帧之间的分隔是 " | "，它本身就含 '|'，按 '|' 直接切会把帧表截成第一帧。
        final int asciiTail = rest.indexOf("| player=");
        if (asciiTail >= 0) {
            rest = rest.substring(0, asciiTail);
        }

        final List<Frame> frames = new ArrayList<>(4);
        if (rest.startsWith("none")) {
            return new Strike(index, frames, player, line);
        }
        String framesText = null;
        final int framesAt = rest.indexOf(" frames=");
        if (framesAt >= 0) {
            framesText = rest.substring(framesAt + " frames=".length()).trim();
        } else {
            final int atEq = rest.indexOf("at=");
            if (atEq >= 0) {
                framesText = rest.substring(atEq + "at=".length()).trim();
            }
        }
        if (framesText != null && !framesText.isEmpty()) {
            final String[] parts = framesText.split(" \\| ");
            for (int k = 0; k < parts.length; k++) {
                final Frame f = parseFrame(parts[k]);
                if (f != null) {
                    frames.add(f);
                }
            }
        }
        return new Strike(index, frames, player, line);
    }

    /**
     * 解析 "类名#方法名:行号 可选{标注}"。
     *
     * @return 解析不了 ⇒ {@code null}（例如空串、缺 {@code #}、缺方法名）
     */
    public static Frame parseFrame(String text) {
        if (text == null) {
            return null;
        }
        String t = text.trim();
        if (t.isEmpty()) {
            return null;
        }
        String annotation = null;
        final int space = t.indexOf(' ');
        if (space > 0) {
            annotation = t.substring(space + 1).trim();
            t = t.substring(0, space);
        }
        final int hash = t.indexOf('#');
        if (hash <= 0 || hash == t.length() - 1) {
            return null;
        }
        final String className = t.substring(0, hash);
        final String tail = t.substring(hash + 1);
        final int colon = tail.lastIndexOf(':');
        String methodName = tail;
        int line = -1;
        if (colon > 0) {
            methodName = tail.substring(0, colon);
            final String lineText = tail.substring(colon + 1);
            if (!"?".equals(lineText)) {
                try {
                    line = Integer.parseInt(lineText.trim());
                } catch (NumberFormatException e) {
                    line = -1;
                }
            }
        }
        if (methodName.isEmpty()) {
            return null;
        }
        return new Frame(className, methodName, line, annotation, text);
    }

    // ═══════════════════════════════ 挑目标 ═══════════════════════════════

    /**
     * <b>只看包名白名单</b>的挑法（不含来源判据）。
     *
     * <p>保留它是为了：① 离线自检里那条 {@code a8.resolve_skips_vanilla}（验"确实跳过了原版注入帧"）
     * 仍然只测这一层；② 有些调用点只想知道"第一帧名字上像不像目标"。
     * <b>生产路径请用 {@link #resolve(List, HotfixPatchWhitelist.Probe, Lookup)}</b>。
     *
     * @return 一帧都不可用 ⇒ {@code null}
     */
    public static Target resolve(List<Frame> frames) {
        if (frames == null || frames.isEmpty()) {
            return null;
        }
        int skipped = 0;
        for (int i = 0; i < frames.size(); i++) {
            final Frame f = frames.get(i);
            if (f == null) {
                continue;
            }
            // 🆕 2026-11-21（①）：先拆方法名再看类名 —— 原版类上的注入帧不再被当"内部帧"丢掉
            if (!HotfixFrameOwner.classify(f).patchableClass()) {
                skipped++;
                continue;
            }
            return new Target(f.className, f.methodName, f.line, skipped, 0, f.raw, "name_only", "?");
        }
        return null;
    }

    /**
     * <b>生产路径</b>：从栈顶往下取第一个<b>同时</b>满足"不在包名白名单"与"来源判据放行"的帧。
     *
     * @param frames   帧表（栈顶在前）
     * @param template 判定模板（已装好 {@code modsRoot}/{@code selfLoader}/{@code selfCodeSource}/{@code mcLoader}）；
     *                 {@link #resolve} 会在它的一份副本上填 {@code className}/{@code codeSource}/{@code loader}
     * @param lookup   按类名取已加载的 {@code Class}（{@code null} ⇒ 全部按"拿不到 code source"判）
     * @return 结论（{@link Resolution#target} 为 {@code null} 时 {@link Resolution#reason} 非空）
     */
    public static Resolution resolve(List<Frame> frames, HotfixPatchWhitelist.Probe template, Lookup lookup) {
        if (frames == null || frames.isEmpty()) {
            return new Resolution(null, "gate_no_strike_frame", "", 0, 0);
        }
        final HotfixPatchWhitelist.Probe base = template == null ? new HotfixPatchWhitelist.Probe() : template;
        int skipped = 0;
        int notForeign = 0;
        int externalOnly = 0;
        String lastDetail = "";
        for (int i = 0; i < frames.size(); i++) {
            final Frame f = frames.get(i);
            if (f == null) {
                continue;
            }
            // 🆕 2026-11-21（①）：**先拆方法名，再判类名**（见 {@link HotfixFrameOwner}）。
            //   旧判据只看声明类 ⇒ 对手往原版类里注入（handler$<hash>$<modid>$<name>）时，
            //   声明类是 net.minecraft.* ⇒ 整帧被当"内部帧"丢掉，等于"看不见它"。
            //   新判据：方法名里的 modid 不是我们 ⇒ **强制外部帧**（优先于包名白名单）。
            final HotfixFrameOwner.Verdict fo = HotfixFrameOwner.classify(f);
            HotfixFrameOwner.emit(f, fo);
            if (fo.self()) {
                skipped++;
                continue;
            }
            // 🔴 外部帧 ≠ 可以拿这个声明类去改字节：原版类上的注入帧只交 owner（谁动过它），
            //   拿去 retransform 就是"为了打对手去改原版类"，那是崩服的做法。
            if (!fo.patchableClass()) {
                externalOnly++;
                lastDetail = "frame_owner_foreign_class_whitelisted";
                continue;
            }
            // ---- 判据 2：来源（code source ＋ 加载器）
            final HotfixPatchWhitelist.Decision d = judgeFrame(f, base, lookup);
            if (!d.allowed) {
                notForeign++;
                lastDetail = d.reason;
                continue;
            }
            final Target target = new Target(f.className, f.methodName, f.line, skipped, notForeign,
                    f.raw, d.reason, d.codeSourceToken);
            return new Resolution(target, "", "", skipped, notForeign);
        }
        if (notForeign > 0) {
            return new Resolution(null, "gate_no_foreign_frame",
                    lastDetail.isEmpty() ? "no_code_source_no_signal" : lastDetail, skipped, notForeign);
        }
        if (externalOnly > 0) {
            // 🆕 ①：确实看见了外部帧（owner 已进 frame_owner 日志），但它的声明类在白名单里
            //   ⇒ 这一帧不能当补丁目标。与"一帧外部帧都没看见"必须分得开。
            return new Resolution(null, "gate_foreign_on_whitelisted_class", lastDetail, skipped, 0);
        }
        return new Resolution(null, "gate_all_frames_whitelisted", "", skipped, 0);
    }

    /**
     * 🆕 2026-11-20：<b>单帧</b>的白名单 ＋ 来源判定（{@link #resolve} 与"整链选靶"共用同一份口径）。
     *
     * <p>为什么要把这十行抽出来：选靶从"第一个外部帧"改成"整链投票"之后，
     * {@code HotfixChainTriage} 要<b>逐帧</b>问同一个问题。两份实现一旦漂移，
     * 出现的形态就是"某条链能挑到、另一条挑不到"这种最难查的差异。
     *
     * @param f        一帧（{@code null} ⇒ 直接拒）
     * @param template 判定模板（已装好 {@code modsRoot}/{@code selfLoader}/{@code selfCodeSource}/{@code mcLoader}）
     * @param lookup   按类名取已加载的 {@code Class}（{@code null} ⇒ 全部按"拿不到 code source"判）
     * @return 判定（{@link HotfixPatchWhitelist.Decision#allowed} ＋ 原因 ＋ {@code code_source=}）
     */
    public static HotfixPatchWhitelist.Decision judgeFrame(Frame f, HotfixPatchWhitelist.Probe template,
                                                           Lookup lookup) {
        if (f == null) {
            return new HotfixPatchWhitelist.Decision(false, "bad_frame", null, "", "", null, "none");
        }
        // 🆕 2026-11-21（①）：帧分类。**先拆方法名，再判类名**：
        //   ① 方法名里有非我们的 modid ⇒ 这一帧是"外部帧"，但声明类若在白名单里
        //      （典型：net.minecraft.* 上的 handler$…$examplemod$x），它**只能当证据**，
        //      绝不能当补丁目标（拿去 retransform 就是改原版类）。
        //   ② 拿不到 modid ⇒ 完全走老逻辑（包名白名单 ＋ code source）。
        final HotfixFrameOwner.Verdict owner = HotfixFrameOwner.classify(f);
        HotfixFrameOwner.emit(f, owner);
        if (owner.forced && owner.foreign() && HotfixPatchWhitelist.isNeverPatch(f.className)) {
            return new HotfixPatchWhitelist.Decision(false,
                    "frame_owner_foreign_class_whitelisted",
                    f.className, f.className, "", null, "none");
        }
        final HotfixPatchWhitelist.Probe base = template == null ? new HotfixPatchWhitelist.Probe() : template;
        final HotfixPatchWhitelist.Probe probe = new HotfixPatchWhitelist.Probe();
        probe.className = f.className;
        probe.modsRoot = base.modsRoot;
        probe.loader = null;
        probe.selfLoader = base.selfLoader;
        probe.selfCodeSource = base.selfCodeSource;
        probe.mcLoader = base.mcLoader;
        Class<?> cls = null;
        if (lookup != null) {
            try {
                cls = lookup.findLoaded(f.className);
            } catch (Throwable ignored) {
                cls = null;
            }
        }
        if (cls != null) {
            // withLoader 会把 loaderKnown 置 true（null 就是 bootstrap，那是一个真实值）；
            // 取不到 Class 时 loaderKnown 保持 false ⇒ 判据只按 code source 走。
            probe.withLoader(loaderOf(cls));
            probe.codeSource = HotfixForeignScan.codeSourceOf(cls);
        }
        return HotfixPatchWhitelist.judge(probe);
    }

    /** {@code Class#getClassLoader()}（拿不到 ⇒ {@code null}）。 */
    static ClassLoader loaderOf(Class<?> cls) {
        try {
            return cls == null ? null : cls.getClassLoader();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String cutAt(String text, char marker) {
        final int at = text.indexOf(marker);
        return at < 0 ? text : text.substring(0, at);
    }
}
