package com.shanhai.common.saveedit.audit;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「双份登记 · 审计」的<b>匹配判据件</b>（纯 JDK：{@code java.util.regex} + {@link java.util.UUID}）。
 *
 * <h2>一、它只回答两件事</h2>
 * <ol>
 *   <li>一段文本里有没有 <b>UUID 形态</b>的串 —— 32 位无连字符、36 位带连字符、大小写混写都认；</li>
 *   <li>一段文本里有没有 <b>坐标三元组</b>（{@code x,y,z} 三个浮点数、逗号分隔），
 *       某个三元组与某个实体位置是否在 {@link #POS_TOLERANCE} 内相等。</li>
 * </ol>
 * 🔴 这两件事是"双份登记"判据的全部原子操作：判据只立在<b>世界数据本身</b>（同一个 UUID /
 * 同一组坐标出现在两个地方），不认任何具体类型名、文件名或字段名 —— 换一个名字的东西照样命中。
 *
 * <h2>二、为什么 UUID 有四种形态都要认</h2>
 * 同一个 UUID 在磁盘上至少可能有这几种写法，任何一种漏认都会让检测出现盲区：
 * <pre>
 *   xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx      36 位带连字符（最常见）
 *   xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx          32 位无连字符（日志、文件名、压缩写法）
 *   XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX      大写
 *   {"k":"xxxxxxxx-....-....-....-xxxxxxxxxxxx","o":1}   夹在别的文本里（JSON/键值对/代码）
 * </pre>
 * 所以识别不依赖上下文，只看"这一段字符像不像 UUID"：先用 36 位形态匹配，
 * 再用 32 位形态匹配，两次都要求<b>两侧不是十六进制字符</b>（否则会把一长串十六进制里的一截当成 UUID）。
 *
 * <h2>三、为什么坐标用"扫描数字 + 手工判分隔符"而不是一个大正则</h2>
 * 一坨嵌套量词的大正在长行上既慢又容易在边界上出错（例如把 {@code 1,2,3,4} 只认成一个三元组）。
 * 这里的做法是：先扫出全部"数字 token"，再逐对检查<b>两个 token 之间只有"一个逗号 + 空白"</b>，
 * 连续三个满足即成三元组。这样 {@code (a,b,c)}、{@code [a, b, c]}、{@code a , b , c} 都命中，
 * 而 {@code a,b}、{@code a;b,c}、{@code a,b,c,d} 里的前三元也照常命中（前三个确实是三元组）。
 *
 * <h2>四、容差</h2>
 * {@link #POS_TOLERANCE} = 0.001：写盘时坐标是 {@code double}，文本里常被格式化成长小数
 * （{@code -179.9999200883557}），所以比较必须带容差；而"差 0.01"这种<b>不是同一个点</b>的
 * 必须<b>不</b>命中 —— 这就是判据里那条负对照。
 */
public final class ShanhaiSaveEditAuditUuid {

    /** 坐标比较容差（三个分量都必须在它之内才算同一个点）。 */
    public static final double POS_TOLERANCE = 0.001D;

    /** 浮点比较的兜底 epsilon（避免 0.001 这种边界被二进制表示误差打掉）。 */
    private static final double EPS = 1e-12D;

    /** 36 位带连字符形态（大小写不敏感；两侧不许是十六进制字符）。 */
    public static final Pattern UUID_36 = Pattern.compile(
            "(?<![0-9A-Fa-f])([0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12})(?![0-9A-Fa-f])");

    /** 32 位无连字符形态（两侧不许是十六进制字符）。 */
    public static final Pattern UUID_32 = Pattern.compile(
            "(?<![0-9A-Fa-f])([0-9A-Fa-f]{32})(?![0-9A-Fa-f])");

    /** 一个"数字 token"（可带符号、小数、指数）。 */
    private static final Pattern NUMBER = Pattern.compile(
            "[-+]?(?:\\d+\\.\\d*|\\.\\d+|\\d+)(?:[eE][-+]?\\d+)?");

    private ShanhaiSaveEditAuditUuid() {}

    // ================================================================== UUID

    /**
     * 把一个"看起来像 UUID"的串归一成标准形态（小写 + 连字符）。
     *
     * @return 归一后的 36 位形态；<b>不像 UUID ⇒ {@code null}</b>（调用方必须当成"没匹配"）
     */
    public static String canonical(String raw) {
        final String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            if (s.length() == 32 && s.indexOf('-') < 0) {
                return new UUID(Long.parseUnsignedLong(s.substring(0, 16), 16),
                        Long.parseUnsignedLong(s.substring(16), 16)).toString();
            }
            if (s.length() == 36) {
                // UUID.fromString 会接受"少一位也能凑出来"的病态写法 ⇒ 这里先逐位验形状
                if (!isHex36(s)) {
                    return null;
                }
                return UUID.fromString(s.toLowerCase(Locale.ROOT)).toString();
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** {@code xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx} 的形状校验（逐位，不依赖 fromString 的宽容）。 */
    private static boolean isHex36(String s) {
        for (int i = 0; i < 36; i++) {
            final char c = s.charAt(i);
            if (i == 8 || i == 13 || i == 18 || i == 23) {
                if (c != '-') {
                    return false;
                }
            } else if (!isHex(c)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /** 32 位无连字符形态（面板/日志里短一点的那种写法）。 */
    public static String hex32(String canonicalUuid) {
        final String c = canonical(canonicalUuid);
        return c == null ? null : c.replace("-", "");
    }

    /**
     * 一段文本里出现的全部 UUID（已归一、去重、保持出现顺序）。
     *
     * <p>同一个 UUID 在一行里出现两次（例如既写成带连字符又写成不带）只会返回一次 ——
     * 一条旁挂证据（文件 + 行）只该报一条 finding。
     */
    public static List<String> findUuids(String text) {
        final List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        final LinkedHashSet<String> seen = new LinkedHashSet<>();
        collect(UUID_36, text, seen);
        collect(UUID_32, text, seen);
        out.addAll(seen);
        return out;
    }

    private static void collect(Pattern p, String text, LinkedHashSet<String> seen) {
        final Matcher m = p.matcher(text);
        while (m.find()) {
            final String c = canonical(m.group(1));
            if (c != null) {
                seen.add(c);
            }
        }
    }

    /** 4 个 int（NBT 里实体 UUID 的存法）→ 标准形态。 */
    public static String uuidOfInts(int[] v) {
        if (v == null || v.length != 4) {
            return null;
        }
        return new UUID(((long) v[0] << 32) | (v[1] & 0xFFFFFFFFL),
                ((long) v[2] << 32) | (v[3] & 0xFFFFFFFFL)).toString();
    }

    /** 两个 long（旧写法的 UUIDMost / UUIDLeast）→ 标准形态。 */
    public static String uuidOfLongs(long most, long least) {
        return new UUID(most, least).toString();
    }

    // ================================================================== 坐标

    /**
     * 一行文本里的全部坐标三元组（{@code x,y,z}）。
     *
     * <p>四个数字连写（{@code a,b,c,d}）会产出 {@code (a,b,c)} 与 {@code (b,c,d)} 两个候选 ——
     * 这是对的：那个点的事实就是"存在一组三个连着的坐标"。
     */
    public static List<double[]> findTriples(String line) {
        final List<double[]> out = new ArrayList<>();
        if (line == null || line.isEmpty()) {
            return out;
        }
        final List<int[]> spans = new ArrayList<>();
        final List<Double> vals = new ArrayList<>();
        final Matcher m = NUMBER.matcher(line);
        while (m.find()) {
            final int start = m.start();
            final int end = m.end();
            if (!numberBoundary(line, start, end)) {
                continue;
            }
            final double v;
            try {
                v = Double.parseDouble(m.group());
            } catch (NumberFormatException nfe) {
                continue;
            }
            if (!isFinite(v)) {
                continue;
            }
            vals.add(v);
            spans.add(new int[] {start, end});
        }
        for (int i = 0; i + 2 < vals.size(); i++) {
            if (onlyOneComma(line, spans.get(i)[1], spans.get(i + 1)[0])
                    && onlyOneComma(line, spans.get(i + 1)[1], spans.get(i + 2)[0])) {
                out.add(new double[] {vals.get(i), vals.get(i + 1), vals.get(i + 2)});
            }
        }
        return out;
    }

    /** 数字 token 的两侧不许是"词字符"（字母/数字/下划线/点），否则它只是某个更长的串里的一截。 */
    private static boolean numberBoundary(String s, int start, int end) {
        if (start > 0 && isWordChar(s.charAt(start - 1))) {
            return false;
        }
        return end >= s.length() || !isWordChar(s.charAt(end));
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '.';
    }

    /** {@code [from,to)} 里必须"只有一个逗号，其余全是空白"。 */
    private static boolean onlyOneComma(String s, int from, int to) {
        if (from < 0 || to > s.length() || from > to) {
            return false;
        }
        int commas = 0;
        for (int i = from; i < to; i++) {
            final char c = s.charAt(i);
            if (c == ',') {
                commas++;
            } else if (!Character.isWhitespace(c)) {
                return false;
            }
        }
        return commas == 1;
    }

    /** 两个点是否在 {@link #POS_TOLERANCE} 之内相等（三个分量都要满足）。 */
    public static boolean posWithin(double[] a, double[] b) {
        if (a == null || b == null || a.length < 3 || b.length < 3) {
            return false;
        }
        return near(a[0], b[0]) && near(a[1], b[1]) && near(a[2], b[2]);
    }

    /** 单分量比较（含兜底 epsilon ⇒ 恰好 0.001 也算命中）。 */
    public static boolean near(double a, double b) {
        if (!isFinite(a) || !isFinite(b)) {
            return false;
        }
        return Math.abs(a - b) <= POS_TOLERANCE + EPS;
    }

    /** 单分量比较，但容差可以在调用点收窄（探针拿来证明"差 0.01 不命中"）。 */
    public static boolean near(double a, double b, double tolerance) {
        if (!isFinite(a) || !isFinite(b)) {
            return false;
        }
        return Math.abs(a - b) <= Math.max(0.0D, tolerance) + EPS;
    }

    private static boolean isFinite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }

    // ================================================================== 读数

    /** 坐标的<b>全精度</b>文本（{@code Double.toString} 可以逐位还原 ⇒ 日志里能直接对账）。 */
    public static String posText(double[] p) {
        if (p == null || p.length < 3) {
            return "?";
        }
        return Double.toString(p[0]) + "," + Double.toString(p[1]) + "," + Double.toString(p[2]);
    }

    /** 坐标的<b>短</b>文本（面板清单用；只到 3 位小数，不用于比较）。 */
    public static String posShort(double[] p) {
        if (p == null || p.length < 3) {
            return "?";
        }
        return round(p[0]) + "," + round(p[1]) + "," + round(p[2]);
    }

    private static String round(double v) {
        return String.valueOf(Math.round(v * 1000.0D) / 1000.0D);
    }

    @Override
    public String toString() {
        return "ShanhaiSaveEditAuditUuid[tol=" + POS_TOLERANCE + "]";
    }
}
