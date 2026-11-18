package com.dishanhai.gt_shanhai.common.saveedit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 存档里那两份<b>纯 JSON</b> 文件（{@code advancements/} 与 {@code stats/}）的读写与比对。
 *
 * <h2>一、为什么还需要一层（而不是直接用 Gson）</h2>
 * 四条硬要求，Gson 本身都不管：
 * <ol>
 *   <li><b>格式要与原版一致</b>：{@code PlayerAdvancements} 用
 *       {@code GsonBuilder().setPrettyPrinting()} 写（见 c11 的取证），
 *       {@code ServerStatsCounter} 写的是<b>单行紧凑</b> JSON。写错不会崩，但会让人以为文件被别的工具改过；
 *       判据里也就没法用"除目标键外逐字节相同"这种便宜的尺子。</li>
 *   <li><b>顶层要有 {@code DataVersion}</b>：两份文件都是"一堆键 + {@code DataVersion}"的混合体
 *       （真实样本里 {@code advancements} 的最后一条就是 {@code "DataVersion": 3465}）。
 *       做"列出"的时候必须把它从条目里剔掉，否则玩家会看到一个叫 {@code DataVersion} 的"成就"。</li>
 *   <li><b>要一把"除目标键以外逐字段等价"的尺子</b>：{@link #diffJson(JsonElement, JsonElement)}
 *       按<b>路径</b>报差异（{@code minecraft:story/mine_diamond.criteria.mine}），
 *       与 NBT 那边的 {@link ShanhaiPlayerDataEditor#diffTags} 同一个口径。</li>
 *   <li><b>数字不许被"美化"</b>：Gson 的 {@code JsonPrimitive} 对数字保留原始字面串
 *       （{@code LazilyParsedNumber}），所以"没动过的键"写回去仍然是原来那串数字
 *       —— 判据 c12 的"其它键一个不动"靠的就是这一点。</li>
 * </ol>
 *
 * <h2>二、编解码统一 UTF-8</h2>
 * 原版 {@code ServerStatsCounter} 用的是 {@code FileUtils.writeStringToFile(file, data)}
 * （平台默认字符集），而两份文件里的键与值<b>全是 ASCII</b>（{@code minecraft:xxx} / 时间戳），
 * 所以 UTF-8 写、UTF-8 读与原版互通，不会踩中文乱码那条老账。
 *
 * <h2>三、本类<b>不</b>判窗口</h2>
 * 与 {@link ShanhaiSaveEditStorage} 同一条红线：窗口判据只在
 * {@link ShanhaiSaveEditFlow#tryApply(String)}；本类只负责字节。
 */
public final class ShanhaiJsonText {

    /** 读文件用的字符集（见类注释 §二）。 */
    public static final java.nio.charset.Charset UTF8 = StandardCharsets.UTF_8;

    /** 紧凑写（{@code stats} 的口径）。 */
    private static final Gson COMPACT = new GsonBuilder().create();
    /** 缩进写（{@code advancements} 的口径，与原版 {@code PlayerAdvancements} 一致）。 */
    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().create();

    private ShanhaiJsonText() {}

    // ================================================================== 读

    /** 读整个文件（UTF-8；不存在 ⇒ 抛 {@link IOException}）。 */
    public static String readString(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            throw new IOException("json_file_missing: " + file);
        }
        final String text = new String(Files.readAllBytes(file), UTF8);
        return stripBom(text);
    }

    /** 解析成一个 JSON 对象（顶层不是对象 / 语法错 ⇒ 抛 {@link IOException}，消息里带原文位置）。 */
    public static JsonObject parseObject(String text) throws IOException {
        if (text == null || text.trim().isEmpty()) {
            throw new IOException("json_empty");
        }
        try {
            final JsonElement el = JsonParser.parseString(stripBom(text));
            if (!el.isJsonObject()) {
                throw new IOException("json_root_not_object");
            }
            return el.getAsJsonObject();
        } catch (JsonParseException e) {
            throw new IOException("json_parse_failed:" + e.getMessage(), e);
        }
    }

    /** 读文件并解析。 */
    public static JsonObject readObject(Path file) throws IOException {
        return parseObject(readString(file));
    }

    private static String stripBom(String s) {
        return s != null && !s.isEmpty() && s.charAt(0) == '\uFEFF' ? s.substring(1) : s;
    }

    // ================================================================== 写

    /** 缩进写（末尾带一个换行；原版 Gson 的 {@code toJson(writer)} 不追加换行，我们也不追加）。 */
    public static String pretty(JsonObject object) {
        return PRETTY.toJson(object);
    }

    /** 紧凑写（{@code stats} 的口径）。 */
    public static String compact(JsonObject object) {
        return COMPACT.toJson(object);
    }

    /** UTF-8 落盘（真正"原子 + 备份"由 {@link ShanhaiSaveEditStorage#writeAtomic} 做）。 */
    public static byte[] utf8(String text) {
        return text == null ? new byte[0] : text.getBytes(UTF8);
    }

    // ================================================================== 结构化比对（判据的尺子）

    /**
     * 两份 JSON 的<b>逐条差异</b>（路径形式，与 {@link ShanhaiPlayerDataEditor#diffTags} 同口径）。
     *
     * <ul>
     *   <li>Object：取两边键的<b>并集</b>排序后逐个下钻（能抓到"多了一个键"/"少了一个键"/"换了顺序"）；</li>
     *   <li>Array：先比长度、再按索引下钻；</li>
     *   <li>叶子：比 {@code getAsString()}（数字按<b>原始字面串</b>比 ⇒ {@code 1} 与 {@code 1.0} 算不同）。</li>
     * </ul>
     * 空列表 = 完全等价。
     */
    public static List<String> diffJson(JsonElement a, JsonElement b) {
        final List<String> out = new ArrayList<>();
        diffInto("", a, b, out);
        return out;
    }

    private static void diffInto(String path, JsonElement a, JsonElement b, List<String> out) {
        if (a == null && b == null) {
            return;
        }
        if (a == null) {
            out.add(path + " (+added)");
            return;
        }
        if (b == null) {
            out.add(path + " (-removed)");
            return;
        }
        final boolean aObj = a.isJsonObject();
        final boolean bObj = b.isJsonObject();
        if (aObj || bObj) {
            if (!(aObj && bObj)) {
                out.add(path + " (type " + kind(a) + "->" + kind(b) + ")");
                return;
            }
            final JsonObject oa = a.getAsJsonObject();
            final JsonObject ob = b.getAsJsonObject();
            final Set<String> keys = new TreeSet<>(oa.keySet());
            keys.addAll(ob.keySet());
            for (String k : keys) {
                diffInto(path.isEmpty() ? k : path + "." + k, oa.get(k), ob.get(k), out);
            }
            return;
        }
        final boolean aArr = a.isJsonArray();
        final boolean bArr = b.isJsonArray();
        if (aArr || bArr) {
            if (!(aArr && bArr)) {
                out.add(path + " (type " + kind(a) + "->" + kind(b) + ")");
                return;
            }
            final JsonArray aa = a.getAsJsonArray();
            final JsonArray ba = b.getAsJsonArray();
            if (aa.size() != ba.size()) {
                out.add(path + " (size " + aa.size() + "->" + ba.size() + ")");
            }
            final int n = Math.min(aa.size(), ba.size());
            for (int i = 0; i < n; i++) {
                diffInto(path + "[" + i + "]", aa.get(i), ba.get(i), out);
            }
            return;
        }
        final String sa = a.getAsString();
        final String sb = b.getAsString();
        if (!sa.equals(sb)) {
            out.add(path + " (" + clamp(sa) + " -> " + clamp(sb) + ")");
        }
    }

    private static String kind(JsonElement e) {
        if (e == null) {
            return "null";
        }
        if (e.isJsonObject()) {
            return "object";
        }
        if (e.isJsonArray()) {
            return "array";
        }
        if (e.isJsonNull()) {
            return "null";
        }
        return e.getAsJsonPrimitive().isNumber() ? "number"
                : e.getAsJsonPrimitive().isBoolean() ? "boolean" : "string";
    }

    private static String clamp(String s) {
        if (s == null) {
            return "null";
        }
        return s.length() <= 60 ? s : s.substring(0, 57) + "...";
    }

    /**
     * 所有差异都落在 {@code prefix} 之下吗？（"除目标键外逐字段相同"的判据就是它）
     *
     * <p>{@code prefix} 为空 ⇒ 任何差异都不接受（只用于"应该完全没变"的断言）。
     */
    public static boolean allDiffsUnder(List<String> diffs, String prefix) {
        if (diffs == null) {
            return false;
        }
        for (String d : diffs) {
            if (!under(d, prefix)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 路径 {@code path} 是否在 {@code prefix} 之下（前缀相等或跟着 {@code .} / {@code [}）。
     *
     * <p>⚠️ {@link #diffJson} 的每条读数长这样：{@code stats.minecraft:custom.x (5 -> 0)} 或
     * {@code id (-removed)} —— 所以判之前必须先把尾巴上的 {@code " (…)"} 摘掉，
     * 否则"删掉一个键"这条最该绿的会变红。
     */
    public static boolean under(String path, String prefix) {
        if (path == null) {
            return false;
        }
        if (prefix == null || prefix.isEmpty()) {
            return false;
        }
        String p = path;
        final int tail = p.indexOf(" (");
        if (tail > 0) {
            p = p.substring(0, tail);
        }
        return p.equals(prefix) || p.startsWith(prefix + ".") || p.startsWith(prefix + "[");
    }

    /** 差异里第一条不属于 {@code prefix} 的（没有就 {@code null}；报错文案用）。 */
    public static String firstDiffOutside(List<String> diffs, String prefix) {
        if (diffs == null) {
            return "<null>";
        }
        for (String d : diffs) {
            if (!under(d, prefix)) {
                return d;
            }
        }
        return null;
    }

    // ================================================================== 小工具

    /** 取一个对象成员（不是对象 ⇒ 空对象，不抛）。 */
    public static JsonObject objectOf(JsonObject parent, String key) {
        if (parent == null || key == null) {
            return null;
        }
        final JsonElement el = parent.get(key);
        return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
    }

    /** 取一个数字（拿不到 / 不是数字 ⇒ {@code null}）。 */
    public static Long longOf(JsonObject parent, String key) {
        final JsonObject o = parent;
        if (o == null || key == null) {
            return null;
        }
        final JsonElement el = o.get(key);
        if (el == null || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        try {
            return el.getAsLong();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 是不是一个 JSON 数字（{@code true} / {@code "1"} 都不算）。 */
    public static boolean isNumber(JsonElement el) {
        return el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber();
    }

    /** 是不是 {@code ns:path} 形状（只查形状，不查注册表）。 */
    public static boolean isResourceLocation(String s) {
        return ShanhaiPlayerDataEdit.isResourceLocation(s);
    }

    /** 一行"文件读数"（{@code bytes=… keys=…}）。 */
    public static String fileLine(Path file) {
        if (file == null) {
            return "file=-";
        }
        try {
            return "file=" + file.getFileName() + " bytes=" + (Files.isRegularFile(file) ? Files.size(file) : -1);
        } catch (Throwable t) {
            return "file=" + file.getFileName() + " bytes=?";
        }
    }

    /** 数字字面量（写回时用；避免出现 {@code 1.0E7} 这种 Gson 的 double 写法）。 */
    public static JsonPrimitive numberPrimitive(long value) {
        return new JsonPrimitive(value);
    }
}
