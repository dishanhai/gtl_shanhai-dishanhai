package com.shanhai.machine.stellar;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;

/**
 * ⑧ 标签过滤的<b>表达式求值器</b>（阶段七新增：把用户填的那一行字符串变成「这个物品收不收」）。
 *
 * <h2>一、语法（与页面上那句说明逐字对应）</h2>
 * <pre>
 *   expr    := term ( '|' term )*            或（任一命中即可）
 *   term    := factor ( '&amp;' factor )*          且（全部命中）
 *   factor  := '!' factor | '(' expr ')' | pattern
 *   pattern := 任意不含  | &amp; ! ( )  的字符串；其中 '*' = 任意串、'?' = 单个字符
 * </pre>
 * 例：{@code forge:ores | forge:raw_materials}、{@code (forge:ores &amp; !minecraft:coal)}、
 * {@code forge:*gears}、{@code minecraft:?ood}。
 *
 * <h2>二、为什么是「自己解析」而不是继续用竖线切分</h2>
 * 页面上的说明要对用户<b>诚实</b>：写了「支持 &amp; | ! () 与 * ?」就必须真的支持。
 * 竖线切分是这套语法的<b>子集</b>（{@code a|b} 在两边含义一致）⇒ 旧的
 * {@link ShanhaiStockConfigLayout#accepts} 现在改为委托本类，<b>只剩一个求值器</b>。
 *
 * <h2>三、出错必须响亮</h2>
 * 括号不配对 / 运算符缺操作数 / 空 pattern ⇒ {@link IllegalArgumentException}。
 * 机器侧接住它并把错误原文放进页面预览（<b>不静默当成「全收」或「全不收」</b>），
 * 因为「用户填错了却什么都不收」和「填错了却全收」都是最难查的两种静默失败。
 */
public final class ShanhaiTagFilterExpression {

    /** 通配符：多个任意字符。 */
    public static final char WILDCARD_ANY = '*';

    /** 通配符：单个任意字符。 */
    public static final char WILDCARD_ONE = '?';

    private ShanhaiTagFilterExpression() {}

    /**
     * 这个物品的标签集合是否满足表达式。
     *
     * @param expression 表达式；{@code null}/空白 = <b>恒 false</b>（"没写过滤" 由调用方决定语义，
     *                   本方法不替它决定，避免「空串=全收」这种隐式规则散落两处）
     * @param tags       物品拥有的标签串（形如 {@code forge:ores}）；{@code null} 当空集
     * @throws IllegalArgumentException 表达式语法错
     */
    public static boolean matches(@Nullable String expression, @Nullable Collection<String> tags) {
        if (expression == null || expression.isBlank()) {
            return false;
        }
        return new Parser(expression, tags).parseWhole();
    }

    /** 表达式是否语法合法（页面预览用；不抛异常）。 */
    public static boolean isValid(@Nullable String expression) {
        try {
            matches(expression, java.util.List.of());
            return true;
        } catch (IllegalArgumentException expected) {
            return false;
        }
    }

    /** 页面说明里那句话（机器侧直接拿它渲染，避免文案与实现各写一套）。 */
    public static @NotNull String syntaxHint() {
        return "支持通配符 " + WILDCARD_ANY + " " + WILDCARD_ONE + "，支持 & | ! ( )";
    }

    // ---------------------------------------------------------------- 递归下降

    private static final class Parser {

        private final String src;
        private final @Nullable Collection<String> tags;
        private int pos;

        Parser(@NotNull String src, @Nullable Collection<String> tags) {
            this.src = src;
            this.tags = tags;
        }

        boolean parseWhole() {
            final boolean value = parseOr();
            skipWhitespace();
            if (this.pos < this.src.length()) {
                throw error("多余字符 '" + this.src.charAt(this.pos) + "'");
            }
            return value;
        }

        private boolean parseOr() {
            boolean value = parseAnd();
            while (true) {
                skipWhitespace();
                if (peek() != '|') {
                    return value;
                }
                this.pos++;
                final boolean right = parseAnd();
                value = value || right;
            }
        }

        private boolean parseAnd() {
            boolean value = parseUnary();
            while (true) {
                skipWhitespace();
                if (peek() != '&') {
                    return value;
                }
                this.pos++;
                final boolean right = parseUnary();
                value = value && right;
            }
        }

        private boolean parseUnary() {
            skipWhitespace();
            final char c = peek();
            if (c == 0) {
                throw error("表达式在运算符后结束（缺少操作数）");
            }
            if (c == '!') {
                this.pos++;
                return !parseUnary();
            }
            if (c == '(') {
                this.pos++;
                final boolean value = parseOr();
                skipWhitespace();
                if (peek() != ')') {
                    throw error("括号不配对（缺少 ')'）");
                }
                this.pos++;
                return value;
            }
            if (c == ')') {
                throw error("多余的 ')'");
            }
            if (c == '|' || c == '&') {
                throw error("运算符 '" + c + "' 前缺少操作数");
            }
            return matchesPattern(readPattern());
        }

        private @NotNull String readPattern() {
            final int start = this.pos;
            while (this.pos < this.src.length()) {
                final char c = this.src.charAt(this.pos);
                if (c == '|' || c == '&' || c == '!' || c == '(' || c == ')') {
                    break;
                }
                this.pos++;
            }
            final String raw = this.src.substring(start, this.pos).trim();
            if (raw.isEmpty()) {
                throw error("空的标签表达式");
            }
            return raw;
        }

        private boolean matchesPattern(@NotNull String pattern) {
            if (this.tags == null || this.tags.isEmpty()) {
                return false;
            }
            for (String tag : this.tags) {
                if (tag != null && globMatch(pattern, tag)) {
                    return true;
                }
            }
            return false;
        }

        private char peek() {
            return this.pos < this.src.length() ? this.src.charAt(this.pos) : 0;
        }

        private void skipWhitespace() {
            while (this.pos < this.src.length() && Character.isWhitespace(this.src.charAt(this.pos))) {
                this.pos++;
            }
        }

        private @NotNull IllegalArgumentException error(@NotNull String message) {
            return new IllegalArgumentException("标签过滤表达式语法错（第 " + (this.pos + 1) + " 个字符）："
                    + message + " ｜ 表达式：" + this.src);
        }
    }

    /**
     * 通配符匹配（{@code *} = 任意串、{@code ?} = 单字符）。
     *
     * <p>双指针 + 回溯（O(n*m) 最坏、无递归栈开销）。<b>不</b>把 {@code :} {@code /} 当特殊字符。
     */
    public static boolean globMatch(@NotNull String pattern, @NotNull String text) {
        int p = 0;
        int t = 0;
        int starP = -1;
        int starT = -1;
        while (t < text.length()) {
            if (p < pattern.length() && (pattern.charAt(p) == WILDCARD_ONE || pattern.charAt(p) == text.charAt(t))) {
                p++;
                t++;
            } else if (p < pattern.length() && pattern.charAt(p) == WILDCARD_ANY) {
                starP = p++;
                starT = t;
            } else if (starP >= 0) {
                p = starP + 1;
                t = ++starT;
            } else {
                return false;
            }
        }
        while (p < pattern.length() && pattern.charAt(p) == WILDCARD_ANY) {
            p++;
        }
        return p == pattern.length();
    }
}
