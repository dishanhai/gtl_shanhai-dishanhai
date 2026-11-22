package com.shanhai.common.reality;

/**
 * 名字的<b>「可见字符」长度口径</b>（纯字符串，<b>一个 MC 类都不 import</b>）。
 *
 * <h2>🔴 为什么必须有这一层（2026-11-22 用户点单）</h2>
 * 用户原话：「物品编辑器的物品名字居然不能用原版或者山海的特殊字体，把那个功能也加上」。
 * 名字框换成能插码的 {@link ShanhaiTokenField} 之后，名字里会出现两类<b>不占可见宽度</b>的码：
 * <pre>
 *   §f / §l / §r …        原版 16 色 + 6 格式（每个 2 个 char）
 *   &amp;$neon- / &amp;$ultimate-  山海样式（每个 7..20 个 char）
 *   &amp;r                    山海样式的收尾符（2 个 char）
 * </pre>
 * 若仍按 {@code String#length()} 截断（旧实现就是 {@code cap()} 里一次 {@code substring}），
 * 玩家加了两个样式码之后<b>肉眼看到的字会被砍掉一大截</b>（48 字的槽位可能只剩 30 个可见字）。
 * ⇒ 本类把「截断」重定义为：<b>可见字符 ≤ maxVisible</b>，码原样保留（不占可见长度）。
 *
 * <h2>口径（三条，落盘/网络/界面共用同一份）</h2>
 * <ol>
 *   <li><b>可见字符</b> = 按 <b>Unicode 码点</b>计（emoji 算 1 个），且<b>不算</b>上面那三类码；</li>
 *   <li><b>截断只切可见字符</b>：切到上限就停，<b>绝不把一个码切成半截</b>
 *       （被切出半个 {@code §} / {@code &$…-} 会让整行渲染直接崩成原样文字）；</li>
 *   <li>另有一道 <b>原始字符上限</b>（{@code maxRaw}）：防止"48 个可见字 + 200 个码"把
 *       网络包/JSON 撑爆。<b>两条上限同时生效，先到者为准</b>。</li>
 * </ol>
 *
 * <h2>为什么它是公开类</h2>
 * 读侧（{@code ShanhaiRealityItemTable} / {@code ShanhaiItemOverrideTable}）与写侧
 * （两个面板的保存按钮）以及<b>离线探针</b>都用它 —— 探针不许启动游戏，所以它不能碰 MC。
 */
public final class ShanhaiNameCaps {

    /** 段位符 U+00A7。🔴 用常量写，源码里不打字面量（工具/编码容易把它搞坏）。 */
    public static final char SECTION = '\u00A7';

    /** 山海样式码的开头。 */
    public static final String STYLE_PREFIX = "&$";

    /** 山海样式的收尾符（也是"码"，不占可见长度）。 */
    public static final String RESTORE = "&r";

    private ShanhaiNameCaps() {}

    // ------------------------------------------------------------------ 扫描

    /**
     * 从 {@code i} 开始的那一段是什么。
     *
     * @param length 这一段占几个 char（恒 &gt; 0）
     * @param code   {@code true} = 这是一段格式码（不占可见长度）
     */
    private record Token(int length, boolean code) {}

    /** 认出 {@code i} 处的这一段（码 / 一个可见码点）。 */
    private static Token tokenAt(String s, int i) {
        final char c = s.charAt(i);
        // ---- ① 原版 §x：两 char 一枚；行尾孤零零一个 § 也当码（不让它变成可见字）----
        if (c == SECTION) {
            return new Token(i + 1 < s.length() ? 2 : 1, true);
        }
        // ---- ② &r：收尾符 ----
        if (c == '&' && i + 1 < s.length() && s.charAt(i + 1) == 'r') {
            return new Token(2, true);
        }
        // ---- ③ &$…-：山海样式（必须有 '-' 才是码；没有 '-' 就按普通字符算）----
        if (c == '&' && i + 1 < s.length() && s.charAt(i + 1) == '$') {
            final int dash = s.indexOf('-', i + 2);
            if (dash >= 0) {
                return new Token(dash - i + 1, true);
            }
        }
        // ---- ④ 普通字符：按码点整段算 ----
        final int cpLen = Character.charCount(s.codePointAt(i));
        return new Token(cpLen, false);
    }

    /** 可见字符数（码点计，不算格式码）。 */
    public static int visibleLength(String raw) {
        if (raw == null || raw.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (int i = 0; i < raw.length(); ) {
            final Token t = tokenAt(raw, i);
            if (!t.code()) {
                n++;
            }
            i += t.length();
        }
        return n;
    }

    /** 去掉所有格式码之后剩下的可见文本（搜索/对拍用；<b>不</b>用于落盘）。 */
    public static String stripCodes(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw == null ? "" : raw;
        }
        final StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); ) {
            final Token t = tokenAt(raw, i);
            if (!t.code()) {
                sb.append(raw, i, i + t.length());
            }
            i += t.length();
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 截断

    /**
     * 按「可见字符 ≤ {@code maxVisible}」＋「原始 char ≤ {@code maxRaw}」截断。
     *
     * <p>语义与旧 {@code cap()} 一致的地方：<b>先 {@code trim()}</b>（首尾空白不算）；
     * 不一致的地方：不再按 char 数一刀切，<b>码原样保留</b>、<b>可见字才算数</b>。
     *
     * @param raw        原串（{@code null} ⇒ 空串）
     * @param maxVisible 可见字符上限（{@code <=0} = 不限制可见长度）
     * @param maxRaw     原始 char 上限（{@code <=0} = 不限制原始长度）
     */
    public static String cap(String raw, int maxVisible, int maxRaw) {
        if (raw == null) {
            return "";
        }
        final String v = raw.trim();
        if (v.isEmpty()) {
            return "";
        }
        if ((maxVisible <= 0 || visibleLength(v) <= maxVisible)
                && (maxRaw <= 0 || v.length() <= maxRaw)) {
            return v;      // 已经合规 ⇒ 逐字符原样返回（这条保证了"§ 不被吃/不被改写"）
        }
        final StringBuilder sb = new StringBuilder(Math.min(v.length(), Math.max(16, maxRaw)));
        int visible = 0;
        for (int i = 0; i < v.length(); ) {
            final Token t = tokenAt(v, i);
            if (!t.code()) {
                if (maxVisible > 0 && visible >= maxVisible) {
                    break;
                }
                visible++;
            }
            if (maxRaw > 0 && sb.length() + t.length() > maxRaw) {
                break;
            }
            sb.append(v, i, i + t.length());
            i += t.length();
        }
        return sb.toString();
    }

    /** 一行读数（面板/日志用）。 */
    public static String describe(String raw) {
        return "chars=" + (raw == null ? 0 : raw.length())
                + " visible=" + visibleLength(raw)
                + " has_section=" + (raw != null && raw.indexOf(SECTION) >= 0)
                + " has_style=" + (raw != null && raw.indexOf(STYLE_PREFIX) >= 0);
    }
}
