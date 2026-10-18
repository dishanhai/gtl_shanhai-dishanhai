package com.shanhai.add.config;

import java.util.List;

/**
 * 从旧本体配置（{@code config/shanhai-common.toml}）里读 {@code [gravity] multiplier} 的<b>纯文本扫描器</b>。
 *
 * <h2>为什么单独放一个类（🔴 零 Minecraft / 零 Forge / 零 slf4j / 零文件 IO）</h2>
 * 迁移是"只跑一次、跑错就把玩家的手感值弄丢"的代码，必须能<b>离线逐格断言</b>
 * （本工程既有口径：判据剥离成纯类后，用裸 {@code javac} + 一个 main 就能验，见
 * {@code com.shanhai.add.time.ShanhaiClockRules} 的类注释）。所以这里只留"把若干行文本变成"一个 double"。
 *
 * <h2>识别的形态（真值来源：实机文件 {@code GTL山海9.10test/config/shanhai-common.toml}）</h2>
 * <pre>
 *   [gravity]
 *   \tmultiplier = 1.0
 * </pre>
 * ⇒ 节头是行首的 {@code [gravity]}，键行可能带缩进/制表符，等号两侧可能有空格，键名可能被引号包起来
 * （Forge 对"需要转义的键名"会写 {@code "multiplier"}；实际没见过，但兜住它只要一行）。
 * <b>只认第一个匹配</b>；后面的重复键不可能由 Forge 写出来，多认反而掩盖问题。
 */
public final class GravityTomlScan {

    /** 旧文件里的节名（与今天逐字一致）。 */
    public static final String SECTION = "gravity";

    /** 旧文件里的键名（与今天逐字一致）。 */
    public static final String KEY = "multiplier";

    /** 没找到 / 解析不了时的返回值（用 NaN 而不是 1.0：1.0 是一个<b>合法</b>的旧值，两者不能混）。 */
    public static final double NOT_FOUND = Double.NaN;

    private GravityTomlScan() {}

    /**
     * 在 {@code lines} 里找 {@code [gravity] multiplier}。
     *
     * @param lines 旧文件的所有行（UTF-8 解码后的原文；{@code null} 元素按空行处理）
     * @return 解析到的倍率；<b>找不到或不是数字时返回 {@link #NOT_FOUND}（NaN）</b>
     */
    public static double parseGravityMultiplier(List<String> lines) {
        if (lines == null) {
            return NOT_FOUND;
        }
        boolean inSection = false;
        for (String raw : lines) {
            if (raw == null) {
                continue;
            }
            final String line = stripLine(raw);
            if (line.isEmpty() || line.charAt(0) == '#') {
                continue;
            }
            if (line.charAt(0) == '[') {
                inSection = SECTION.equals(sectionName(line));
                continue;
            }
            if (!inSection) {
                continue;
            }
            final int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            if (!KEY.equals(unquote(line.substring(0, eq).trim()))) {
                continue;
            }
            // 值的尾巴可能跟行内注释（Forge 自己不写，但手改过的文件会有）
            String value = line.substring(eq + 1).trim();
            final int hash = value.indexOf('#');
            if (hash >= 0) {
                value = value.substring(0, hash).trim();
            }
            value = unquote(value);
            if (value.isEmpty()) {
                return NOT_FOUND;
            }
            try {
                return Double.parseDouble(value);
            } catch (NumberFormatException e) {
                // 有键但不是数字（例如 0.5f / 半个词）⇒ 当作"没找到"，让迁移安静跳过
                return NOT_FOUND;
            }
        }
        return NOT_FOUND;
    }

    /** 去掉行尾 {@code \r} 与首尾空白。 */
    private static String stripLine(String raw) {
        String s = raw;
        final int n = s.length();
        int end = n;
        while (end > 0) {
            final char c = s.charAt(end - 1);
            if (c == '\n' || c == '\r' || c == ' ' || c == '\t') {
                end--;
            } else {
                break;
            }
        }
        int start = 0;
        while (start < end) {
            final char c = s.charAt(start);
            if (c == ' ' || c == '\t') {
                start++;
            } else {
                break;
            }
        }
        return s.substring(start, end);
    }

    /** {@code [gravity]} / {@code [ gravity ]} / {@code [gravity] # 注释} ⇒ {@code gravity}。 */
    private static String sectionName(String line) {
        final int close = line.indexOf(']');
        if (close <= 0) {
            return "";
        }
        return line.substring(1, close).trim();
    }

    /** 去掉成对的单/双引号（Forge 写带空格的键名时会加引号）。 */
    private static String unquote(String s) {
        if (s.length() >= 2) {
            final char first = s.charAt(0);
            final char last = s.charAt(s.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return s.substring(1, s.length() - 1).trim();
            }
        }
        return s;
    }
}
