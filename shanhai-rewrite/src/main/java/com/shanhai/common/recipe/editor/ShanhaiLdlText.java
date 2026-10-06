package com.shanhai.common.recipe.editor;

import com.shanhai.ShanhaiMod;

import java.util.function.Supplier;

/**
 * 🔴 <b>喂给 LDLib 控件的那一行字</b>（A7 的根治点）。
 *
 * <h2>1. 病是什么 —— 三层字节码实证，不是猜的</h2>
 * 用户截图里那一行是 {@code Format error: 概率…}。把这条链一路追到字节码：
 * <pre>
 *   LabelWidget.drawInBackground
 *     → LocalizationUtils.format(text, new Object[0])          ← LDLib，实参就是"空数组"
 *        → (客户端) I18n.get(text, args)                        ← net.minecraft.client.resources.language.I18n
 *           → Language.getOrDefault(text) 取到 lang 表里的串；取不到就【原样返回 key 本身】
 *           → try { String.format(那个串, args) }
 *             catch (IllegalFormatException e) { return "Format error: " + 那个串; }
 * </pre>
 * 三条实证：
 * <ol>
 *   <li>LDLib 的 {@code LabelWidget.drawInBackground} 里确实有
 *       {@code invokestatic LocalizationUtils.format(String,[Object])}，实参是 {@code new Object[0]}；</li>
 *   <li>{@code LocalizationUtils.format} 在<b>服务端</b>直接 {@code String.format}（没有兜底），
 *       在<b>客户端</b>走 {@code I18n.get}；</li>
 *   <li>{@code I18n} 的常量池里有字面量 <b>{@code "Format error: "}</b>，
 *       且它只 catch {@code java/util/IllegalFormatException}。</li>
 * </ol>
 * ⇒ 我们的标签文本<b>不是</b> lang 表里的键 ⇒ {@code getOrDefault} 原样返回它自己 ⇒
 * {@code String.format("§7概率(万分比 10000=100%)")} 抛
 * {@code UnknownFormatConversionException: Conversion = ')'} ⇒ <b>整行被替换成 "Format error: …"</b>。
 * <b>这正好就是用户看到的那一屏。</b>
 *
 * <h2>2. 只有 {@code %} 有病，{@code {}} 没有</h2>
 * 本机 JDK17 实测（{@code String.format(s, new Object[0])}）：
 * <pre>
 *   "§7概率(万分比 10000=100%)"  → THROW UnknownFormatConversionException: Conversion = ')'
 *   "§e= 100.00%"                → THROW UnknownFormatConversionException: Conversion = '%'
 *   "§8概率/递增只对输出格有意义"   → OK
 *   "{0}" / "a{}b"               → OK   ⇒ 占位符那种花括号【无害】，不必动
 * </pre>
 * ⇒ 本类只处理 {@code %}；把 {@code {}} 也"顺手转义"是没必要的改动（本工程不接受没证据的改动）。
 *
 * <h2>3. 为什么转义成 {@code %%} 是对的做法</h2>
 * {@code String.format("100%%", new Object[0])} = {@code "100%"} ✓ —— 转义后的输出
 * <b>与原文逐字节相同</b>，而不再抛异常。客户端（{@code I18n}）与服务端
 * （{@code LocalizationUtils.format} 直调）两条路都会走一次 {@code String.format}，
 * 所以 {@code %%} 在两边都恰好还原成一个 {@code %}。
 */
public final class ShanhaiLdlText {

    public static final String PREFIX = "[SHANHAI-EDIT] editor";

    private ShanhaiLdlText() {}

    /**
     * 把一段要交给 LDLib 控件的文本转成<b>安全形态</b>。
     *
     * <p>规则只有一条：把裸 {@code %} 变成 {@code %%}。已经是 {@code %%} 的不再重复转义
     * —— 否则第二次调用会得到 {@code %%%%}（显示成两个 {@code %}），而"同一个字符串被
     * 两个地方各转义一次"这种事一定会发生。
     */
    public static String esc(String s) {
        if (s == null || s.indexOf('%') < 0) {
            return s;
        }
        final StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c == '%') {
                final boolean alreadyEscaped = i + 1 < s.length() && s.charAt(i + 1) == '%';
                if (alreadyEscaped) {
                    sb.append("%%");
                    i++;                 // 跳过配对的那一个，避免 %%%% 这种双重转义
                } else {
                    sb.append("%%");
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 为"动值"标签准备的转义供应器（每帧都会被取值，所以转义也每帧做）。 */
    public static Supplier<String> sup(Supplier<String> raw) {
        return raw == null ? () -> null : () -> esc(raw.get());
    }

    /**
     * <b>这条链的机器判据</b>（照工程既有形态：正面对照 ＋ 负面对照一起给）。
     *
     * <p>它复刻的是 {@code I18n.get} 里那句 {@code String.format(s, new Object[0])} —— 也就是
     * <b>抛异常的那一步本身</b>，所以这里的红/绿与游戏里那一行字是同源的，不是"关于它的比喻"。
     * 负面对照（不转义必须抛）是必需的：没有它，一个"永远返回空串"的假实现也能全绿。
     */
    public static void selfcheck() {
        int pass = 0;
        int total = 0;
        final StringBuilder bad = new StringBuilder();

        // ── A1 现场那两行：转义后必须能过，且还原成原文 ──
        final String[] live = {
                "§7概率(万分比 10000=100%)",
                "§e= 100.00%",
                "§7随电压递增(万分比)",
                "§8概率/递增只对输出格有意义",
                "§a催化剂：不消耗 = 开",
        };
        for (String raw : live) {
            total++;
            final String formatted;
            try {
                formatted = String.format(esc(raw), new Object[0]);
            } catch (Throwable t) {
                bad.append(" A1[").append(raw).append("] threw ").append(t.getClass().getSimpleName());
                continue;
            }
            if (raw.equals(formatted)) {
                pass++;
            } else {
                bad.append(" A1[").append(raw).append("] -> [").append(formatted).append("]");
            }
        }

        // ── A2 负面对照：不转义的那两行【必须】抛（证明这次检查真能看见这个病） ──
        for (String raw : new String[]{"§7概率(万分比 10000=100%)", "§e= 100.00%", "50%"}) {
            total++;
            try {
                String.format(raw, new Object[0]);
                bad.append(" NEG[").append(raw).append("] 居然没抛 —— 本检查器看不见这个病，它给的绿不算数");
            } catch (java.util.IllegalFormatException expected) {
                pass++;
            } catch (Throwable other) {
                bad.append(" NEG[").append(raw).append("] 抛的是 ").append(other.getClass().getSimpleName());
            }
        }

        // ── A3 幂等：已经转义过的不许再补一遍（否则会变成 %%%%，界面上显示成两个 %） ──
        total++;
        if ("100%%".equals(esc("100%%")) && "100%".equals(String.format(esc("100%%"), new Object[0]))
                && "100%".equals(String.format(esc("100%"), new Object[0]))) {
            pass++;
        } else {
            bad.append(" A3 二次转义 => [").append(esc("100%%")).append("] 单次 => [").append(esc("100%")).append("]");
        }

        // ── A4 无 % 的文本必须原样（不许"顺手改字"） ──
        total++;
        if ("§7数量".equals(esc("§7数量"))) {
            pass++;
        } else {
            bad.append(" A4 无%文本被改了");
        }

        // ── A5 {}/{} 无害这条结论本身也要有判据（免得以后有人把它也"修"了） ──
        total++;
        try {
            if ("{0}".equals(String.format("{0}", new Object[0]))) {
                pass++;
            } else {
                bad.append(" A5 花括号行为变了");
            }
        } catch (Throwable t) {
            bad.append(" A5 花括号居然抛了：" + t.getClass().getSimpleName());
        }

        final boolean allOk = pass == total;
        ShanhaiMod.LOGGER.info("{} LDLABEL_SELFCHECK {}/{} PASS={} （判据 = 复刻 I18n.get 里那句 "
                        + "String.format(s,new Object[0])；A1 转义后过且还原、A2 负面对照不转义必抛、"
                        + "A3 二次转义幂等、A4 无%文本原样、A5 花括号无害）",
                PREFIX, pass, total, allOk);
        if (!allOk) {
            ShanhaiMod.LOGGER.error("{} LDLABEL_SELFCHECK 有 {} 项没过：{}", PREFIX, total - pass, bad);
        }
    }
}
