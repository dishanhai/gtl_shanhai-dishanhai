package com.shanhai.add.hotfix;

import java.util.ArrayList;
import java.util.List;

/**
 * 把本体的 {@code strike_caller#N} 那一行<b>拆成栈帧</b>，再取「第一个非白名单帧」当作热改目标。
 *
 * <h2>本体的行长什么样（逐字引自 {@code ShanhaiGuardTriage.strikeLine(...)}）</h2>
 * <pre>
 *   [SHANHAI-TRIAGE] strike_caller#1 at=examplemod.util.EntityUtil#forceHurt:169 \
 *        frames=examplemod.util.EntityUtil#forceHurt:169 | other.Mod#m:12 ｜ player=Steve
 *   [SHANHAI-TRIAGE] strike_caller#2 none ｜ player=Steve
 * </pre>
 * 一帧的形态是 {@code 类名#方法名:行号}（行号未知写 {@code :?}），后面可能跟两种标注：
 * {@code {pl:mixin:APP:…}}（这个类被哪份 mixin 配置改过）与 {@code {injected:modId}}（Mixin 生成的注入方法）。
 * 栈帧之间用 <b>ASCII</b> {@code " | "} 分隔，玩家名之前是<b>全角</b> {@code ｜} ——
 * 顺序必须"先切全角、再切帧"，否则 {@code | player=} 会被当成一帧。
 *
 * <h2>为什么"第一个非白名单帧"要我们自己也判一遍</h2>
 * 本体的过滤口径是「丢掉纯原版帧、丢掉 {@code com.shanhai.*}、丢掉 JDK 噪声」，
 * <b>但保留原版类上的注入方法</b>（{@code handler$…}，因为它能说明"谁改过这个类"）。
 * 那些注入帧的类名是 {@code net.minecraft.*} ⇒ 对我们来说照样是<b>不许改</b>的目标。
 * 所以这一层必须用 {@link HotfixPatchWhitelist#isNeverPatch} 再筛一次，不能直接用 {@code frames[0]}。
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

        /** 源级类名，例如 {@code examplemod.util.EntityUtil}。 */
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

        /** 在它之前被白名单挡掉了几个帧（证据：说明确实跳过了原版注入帧）。 */
        public final int framesSkipped;

        /** 原样文本。 */
        public final String raw;

        Target(String className, String methodName, int line, int framesSkipped, String raw) {
            this.className = className;
            this.methodName = methodName;
            this.line = line;
            this.framesSkipped = framesSkipped;
            this.raw = raw;
        }

        public String brief() {
            return className + "#" + methodName;
        }

        @Override
        public String toString() {
            return brief() + " line=" + (line >= 0 ? String.valueOf(line) : "?")
                    + " frames_skipped=" + framesSkipped;
        }
    }

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

    /**
     * 取第一个<b>不在硬白名单</b>里的帧。
     *
     * @return 一帧都不可用 ⇒ {@code null}（调用方按"拒绝"记一条，不动手）
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
            if (HotfixPatchWhitelist.isNeverPatch(f.className)) {
                skipped++;
                continue;
            }
            return new Target(f.className, f.methodName, f.line, skipped, f.raw);
        }
        return null;
    }

    private static String cutAt(String text, char marker) {
        final int at = text.indexOf(marker);
        return at < 0 ? text : text.substring(0, at);
    }
}
