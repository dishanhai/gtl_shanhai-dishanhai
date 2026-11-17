package com.shanhai.common.saveedit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 「存档直改 · 第四期」：{@code stats/<uuid>.json}（统计，<b>纯 JSON</b>）。
 *
 * <h2>一、格式（本机真实存档反解）</h2>
 * <pre>
 * {"stats":{
 *    "minecraft:custom":{"minecraft:play_time":6219362,"minecraft:jump":3490,…},
 *    "minecraft:mined":{"minecraft:stone":…},
 *    "minecraft:picked_up":{…}, "minecraft:killed":{…}, …
 *  },"DataVersion":3465}
 * </pre>
 * 两条从真实样本里读出来的事实：
 * <ol>
 *   <li>整份文件是<b>单行紧凑</b> JSON（{@code ServerStatsCounter} 用的 Gson 没开缩进）；
 *       写回也用紧凑，别用缩进 —— 否则"除目标键外逐字节相同"这条便宜的判据就没法用了；</li>
 *   <li>顶层除了 {@code stats} 还有 {@code DataVersion}，必须原样保留。</li>
 * </ol>
 *
 * <h2>二、"清零"的两种口径，本类都提供</h2>
 * <ul>
 *   <li>{@link #ACTION_SET} {@code value=0} —— 键<b>留着</b>、值写成 0（"这个统计我清成 0 了"）；</li>
 *   <li>{@link #ACTION_CLEAR} —— 把这条键<b>整条删掉</b>（游戏读不到 = 当 0，面板也少一条）。
 *       这是"清零"更常见的口径，判据 c12 两种各验一次。</li>
 * </ul>
 *
 * <h2>三、🔴 数字不许被"美化"</h2>
 * Gson 的 {@code JsonPrimitive} 对数字保留原始字面串，所以<b>没动过的键</b>写回去仍是原来那串
 * 数字（判据 c12 的"其它键不动"靠这一点）。写新值用 {@code long}，不会出现 {@code 1.0E7}。
 *
 * <h2>四、本类<b>不</b>判窗口</h2>
 * 同一条红线：调用方必须先过 {@link ShanhaiSaveEditFlow#tryApply(String)}。
 */
public final class ShanhaiStatsEdit {

    /** 日志前缀。 */
    public static final String PREFIX = ShanhaiSaveEditFlow.PREFIX;

    /** 顶层键。 */
    public static final String KEY_STATS = "stats";
    /** 顶层版本键。 */
    public static final String KEY_DATA_VERSION = "DataVersion";

    /** 动作：设值（含设 0）。 */
    public static final String ACTION_SET = "set";
    /** 动作：整条删（清零的常见口径）。 */
    public static final String ACTION_CLEAR = "clear";

    /** 默认分类（原版通用统计全在这里）。 */
    public static final String DEFAULT_CATEGORY = "minecraft:custom";

    /** 目录名。 */
    public static final String DIR = "stats";

    /** 原版用到的分类（只用于面板提示；不限制填写 —— 模组可能有自己的）。 */
    public static final List<String> KNOWN_CATEGORIES = List.of(
            "minecraft:custom", "minecraft:mined", "minecraft:crafted", "minecraft:used",
            "minecraft:broken", "minecraft:picked_up", "minecraft:dropped",
            "minecraft:killed", "minecraft:killed_by");

    private ShanhaiStatsEdit() {}

    // ================================================================== 路径

    /** {@code <存档>/stats/<uuid>.json}。 */
    public static Path fileOf(Path worldDir, String playerUuid) {
        if (worldDir == null || playerUuid == null || playerUuid.isEmpty()) {
            return null;
        }
        return worldDir.resolve(DIR).resolve(playerUuid + ".json");
    }

    // ================================================================== 请求

    /**
     * 一次统计改动。
     *
     * @param category 分类（空 ⇒ {@link #DEFAULT_CATEGORY}），如 {@code minecraft:custom}
     * @param key      统计键，如 {@code minecraft:play_time}
     * @param action   {@link #ACTION_SET} / {@link #ACTION_CLEAR}
     * @param value    {@code ACTION_SET} 时的值（{@code null} ⇒ 非法）
     */
    public record Request(String category, String key, String action, Long value) {

        /** 要不要写。 */
        public boolean isEmpty() {
            return this.key == null || this.key.trim().isEmpty();
        }

        /** 归一化后的分类。 */
        public String cat() {
            final String c = this.category == null ? "" : this.category.trim();
            return c.isEmpty() ? DEFAULT_CATEGORY : c;
        }

        /** 归一化后的键。 */
        public String statKey() {
            return this.key == null ? "" : this.key.trim();
        }

        /** 是不是"设值"。 */
        public boolean isSet() {
            return ACTION_SET.equals(this.action);
        }

        /** 一行读数。 */
        public String line() {
            return "action=" + this.action + " cat=" + cat() + " key=" + statKey()
                    + (isSet() ? " value=" + this.value : " (整条删)");
        }

        /**
         * 校验（面板暂存前 + 落盘前各一次）。
         *
         * @throws IllegalArgumentException 中文原因
         */
        public void validate() {
            if (isEmpty()) {
                throw new IllegalArgumentException("统计键是空的");
            }
            if (!ACTION_SET.equals(this.action) && !ACTION_CLEAR.equals(this.action)) {
                throw new IllegalArgumentException("动作只能是 " + ACTION_SET + " 或 " + ACTION_CLEAR
                        + "，现在是「" + this.action + "」");
            }
            if (!ShanhaiJsonText.isResourceLocation(cat())) {
                throw new IllegalArgumentException("分类要写成 namespace:path（例如 minecraft:custom），现在是「"
                        + this.category + "」");
            }
            if (!ShanhaiJsonText.isResourceLocation(statKey())) {
                throw new IllegalArgumentException("统计键要写成 namespace:path（例如 minecraft:play_time），现在是「"
                        + this.key + "」");
            }
            if (isSet()) {
                if (this.value == null) {
                    throw new IllegalArgumentException("设值要给出一个整数");
                }
                if (this.value < 0L) {
                    throw new IllegalArgumentException("统计值不能是负数：「" + this.value + "」");
                }
            }
        }
    }

    // ================================================================== 列出

    /** 一条统计读数。 */
    public record Entry(String category, String key, long value) {
        public String line() {
            return shortCat(this.category) + " §f" + shortKey(this.key) + " §7= " + this.value;
        }
    }

    /** 一次"列出"的读数。 */
    public record Listing(boolean ok, String error, Path path, String category, int count,
                          Map<String, Integer> byCategory, List<Entry> entries, boolean truncated,
                          String dataVersion, int bytes) {
        public String line() {
            return "ok=" + this.ok
                    + (this.error == null || this.error.isEmpty() ? "" : " error=" + this.error)
                    + " file=" + (this.path == null ? "-" : this.path.getFileName())
                    + " bytes=" + this.bytes
                    + " cat=" + (this.category == null || this.category.isEmpty() ? "*" : this.category)
                    + " listed=" + this.count
                    + " categories=" + (this.byCategory == null ? 0 : this.byCategory.size())
                    + " data_version=" + (this.dataVersion == null ? "-" : this.dataVersion);
        }

        /** 面板明细。 */
        public List<String> lines(int max) {
            final List<String> out = new ArrayList<>();
            if (!this.ok) {
                out.add("§c" + (this.error == null ? "列不出来" : this.error));
                return out;
            }
            final StringBuilder cats = new StringBuilder();
            if (this.byCategory != null) {
                for (Map.Entry<String, Integer> e : this.byCategory.entrySet()) {
                    cats.append(cats.length() == 0 ? "" : " · ").append(shortCat(e.getKey()))
                            .append(" ").append(e.getValue());
                }
            }
            out.add("§7列出 " + this.count + " 项 · 分类：" + (cats.length() == 0 ? "-" : cats)
                    + " · DataVersion " + (this.dataVersion == null ? "-" : this.dataVersion));
            int i = 0;
            for (Entry e : this.entries) {
                if (out.size() >= Math.max(1, max)) {
                    out.add("§8…还有 " + Math.max(0, this.count - i) + " 项没显示");
                    break;
                }
                out.add(e.line());
                i++;
            }
            return out;
        }
    }

    /**
     * 列出某个玩家的统计。
     *
     * @param category 只列这个分类（空 ⇒ 全部；面板一般只列 {@code minecraft:custom}）
     * @param maxEntries 最多带回几条（其余只计数）
     */
    public static Listing listFile(Path file, String category, int maxEntries) {
        if (file == null || !Files.isRegularFile(file)) {
            return new Listing(false, "还没有统计文件（这个玩家还没进过这个世界）："
                    + (file == null ? "-" : file.getFileName()), file, null, 0, Map.of(), List.of(),
                    false, null, 0);
        }
        final JsonObject root;
        final int bytes;
        try {
            bytes = (int) Files.size(file);
            root = ShanhaiJsonText.readObject(file);
        } catch (Throwable t) {
            return new Listing(false, "读统计文件失败：" + t, file, null, 0, Map.of(), List.of(),
                    false, null, 0);
        }
        final JsonObject stats = ShanhaiJsonText.objectOf(root, KEY_STATS);
        if (stats == null) {
            return new Listing(false, "文件里没有 stats 对象", file, null, 0, Map.of(), List.of(),
                    false, null, bytes);
        }
        final String only = category == null ? "" : category.trim();
        final Map<String, Integer> byCategory = new TreeMap<>();
        final List<Entry> entries = new ArrayList<>();
        int count = 0;
        boolean truncated = false;
        final List<String> cats = new ArrayList<>(stats.keySet());
        java.util.Collections.sort(cats);
        for (String cat : cats) {
            final JsonObject body = ShanhaiJsonText.objectOf(stats, cat);
            if (body == null) {
                continue;
            }
            if (!only.isEmpty() && !only.equals(cat)) {
                continue;
            }
            byCategory.put(cat, body.size());
            final List<String> keys = new ArrayList<>(body.keySet());
            java.util.Collections.sort(keys);
            for (String k : keys) {
                final Long v = ShanhaiJsonText.longOf(body, k);
                if (v == null) {
                    continue;
                }
                count++;
                if (entries.size() < Math.max(0, maxEntries)) {
                    entries.add(new Entry(cat, k, v));
                } else {
                    truncated = true;
                }
            }
        }
        final String dv = root.has(KEY_DATA_VERSION) ? root.get(KEY_DATA_VERSION).getAsString() : null;
        return new Listing(true, "", file, only, count, byCategory, entries, truncated, dv, bytes);
    }

    // ================================================================== 写

    /** 一次改动结果。 */
    public record Result(boolean ok, String error, List<String> changed, List<String> notes,
                         ShanhaiSaveEditStorage.WriteResult write, String verifyLine) {
        public String line() {
            return "ok=" + this.ok
                    + (this.error == null || this.error.isEmpty() ? "" : " error=" + this.error)
                    + " changed=" + this.changed
                    + (this.notes == null || this.notes.isEmpty() ? "" : " notes=" + this.notes)
                    + " verify=[" + (this.verifyLine == null ? "" : this.verifyLine) + "]"
                    + (this.write == null ? "" : " | " + this.write.line());
        }
    }

    /** 按世界目录 + 玩家 uuid 写（会写盘）。 */
    public static Result apply(Path worldDir, String playerUuid, Request req, Path backupRoot, String stamp) {
        final Path file = fileOf(worldDir, playerUuid);
        if (file == null) {
            return fail("算不出统计文件路径（worldDir/uuid 缺失）");
        }
        if (!Files.isRegularFile(file)) {
            return fail("还没有统计文件（这个玩家还没进过这个世界）：" + file);
        }
        return applyToFile(file, req, backupRoot, stamp);
    }

    /** 直接对一个 {@code stats/<uuid>.json} 做改动（离线判据走这条）。 */
    public static Result applyToFile(Path file, Request req, Path backupRoot, String stamp) {
        final List<String> notes = new ArrayList<>();
        final List<String> changed = new ArrayList<>();
        if (file == null || req == null) {
            return fail("null_arg");
        }
        if (req.isEmpty()) {
            return fail("empty_request（统计键是空的）");
        }
        try {
            req.validate();
        } catch (IllegalArgumentException iae) {
            return fail(iae.getMessage());
        }
        final JsonObject before;
        try {
            before = ShanhaiJsonText.readObject(file);
        } catch (Throwable t) {
            return fail("读统计文件失败：" + t);
        }
        final JsonObject after = before.deepCopy();
        if (req.isSet()) {
            set(after, req.cat(), req.statKey(), req.value);
        } else {
            clear(after, req.cat(), req.statKey());
        }
        final Verify pre = req.isSet()
                ? verifySet(after, req.cat(), req.statKey(), req.value)
                : verifyClear(after, req.cat(), req.statKey());
        if (!pre.ok()) {
            return fail("改完的统计没通过校验（未写盘）：" + pre.line());
        }
        // 等价：只允许动 stats.<cat>.<key> 这一个叶子
        final String path = KEY_STATS + "." + req.cat() + "." + req.statKey();
        final List<String> intended = ShanhaiJsonText.diffJson(before, after);
        if (!ShanhaiJsonText.allDiffsUnder(intended, path)) {
            return fail("算出来的差异超出了目标键（未写盘）："
                    + ShanhaiJsonText.firstDiffOutside(intended, path) + " intended=" + intended);
        }
        if (intended.isEmpty()) {
            return fail("no_effective_change（这条统计本来就是这个值）");
        }
        changed.addAll(intended);
        final byte[] content = ShanhaiJsonText.utf8(ShanhaiJsonText.compact(after));
        final ShanhaiSaveEditStorage.WriteResult write =
                ShanhaiSaveEditStorage.writeAtomic(file, content, backupRoot, stamp);
        if (!write.ok()) {
            return new Result(false, write.error(), List.of(), notes, write, "");
        }
        try {
            final JsonObject disk = ShanhaiJsonText.readObject(file);
            final Verify post = req.isSet()
                    ? verifySet(disk, req.cat(), req.statKey(), req.value)
                    : verifyClear(disk, req.cat(), req.statKey());
            if (!post.ok()) {
                restore(write, file);
                return fail("回读校验没过 ⇒ 已用备份盖回：" + post.line());
            }
            final List<String> observed = ShanhaiJsonText.diffJson(before, disk);
            if (!observed.equals(intended)) {
                restore(write, file);
                return fail("回读不一致：intended=" + intended + " observed=" + observed + " ⇒ 已用备份盖回");
            }
            notes.add("除 " + path + " 外逐字段相同（差异 " + observed.size() + " 条都在它下面）");
            notes.add("文件 " + content.length + " 字节（紧凑写，与原版 ServerStatsCounter 同口径）");
            return new Result(true, "", changed, notes, write, pre.line());
        } catch (Throwable t) {
            restore(write, file);
            return fail("回读阶段抛了（已用备份盖回）：" + t);
        }
    }

    private static void restore(ShanhaiSaveEditStorage.WriteResult write, Path target) {
        ShanhaiSaveEditStorage.restore(write == null ? null : write.backupPath(), target);
    }

    // ================================================================== 改（纯内存）

    /** 把 {@code stats.<cat>.<key>} 设成 {@code value}（分类不存在就建；其它键一个不动）。 */
    public static void set(JsonObject root, String category, String key, Long value) {
        if (root == null || category == null || key == null || value == null) {
            return;
        }
        JsonObject stats = ShanhaiJsonText.objectOf(root, KEY_STATS);
        if (stats == null) {
            stats = new JsonObject();
            root.add(KEY_STATS, stats);
        }
        JsonObject body = ShanhaiJsonText.objectOf(stats, category);
        if (body == null) {
            body = new JsonObject();
            stats.add(category, body);
        }
        body.add(key, new JsonPrimitive(value));
    }

    /** 把 {@code stats.<cat>.<key>} 整条删掉（删完如果分类空了也保留分类对象 —— 原作也有空分类的例子）。 */
    public static void clear(JsonObject root, String category, String key) {
        if (root == null || category == null || key == null) {
            return;
        }
        final JsonObject stats = ShanhaiJsonText.objectOf(root, KEY_STATS);
        final JsonObject body = stats == null ? null : ShanhaiJsonText.objectOf(stats, category);
        if (body != null) {
            body.remove(key);
        }
    }

    // ================================================================== 校验（判据的尺子）

    /** 一次校验的读数。 */
    public record Verify(boolean ok, List<String> problems, List<String> changes) {
        public String line() {
            return "ok=" + this.ok
                    + (this.changes.isEmpty() ? "" : " changes=" + this.changes)
                    + (this.problems.isEmpty() ? "" : " problems=" + this.problems);
        }
    }

    /** 🔴 判据 c12 的尺子：{@code stats.<cat>.<key>} 等于期望值，且顶层 {@code DataVersion} 还在。 */
    public static Verify verifySet(JsonObject after, String category, String key, Long want) {
        final List<String> problems = new ArrayList<>();
        final List<String> changes = new ArrayList<>();
        if (after == null) {
            return new Verify(false, List.of("root_null"), List.of());
        }
        final Long got = read(after, category, key);
        if (got == null) {
            problems.add("键不在了：" + KEY_STATS + "." + category + "." + key);
        } else if (want != null && !want.equals(got)) {
            problems.add("值不对：期望 " + want + " 实际 " + got);
        } else {
            changes.add(KEY_STATS + "." + category + "." + key + "=" + got);
        }
        if (after.has(KEY_DATA_VERSION)) {
            changes.add("DataVersion=" + after.get(KEY_DATA_VERSION).getAsString() + " 保留");
        }
        return new Verify(problems.isEmpty(), problems, changes);
    }

    /** 🔴 判据 c12 的尺子：{@code stats.<cat>.<key>} 已经不存在。 */
    public static Verify verifyClear(JsonObject after, String category, String key) {
        final List<String> problems = new ArrayList<>();
        final List<String> changes = new ArrayList<>();
        if (after == null) {
            return new Verify(false, List.of("root_null"), List.of());
        }
        final JsonObject stats = ShanhaiJsonText.objectOf(after, KEY_STATS);
        final JsonObject body = stats == null ? null : ShanhaiJsonText.objectOf(stats, category);
        if (body != null && body.has(key)) {
            problems.add("这条统计还在：" + key);
        } else {
            changes.add("已删除 " + KEY_STATS + "." + category + "." + key);
        }
        if (after.has(KEY_DATA_VERSION)) {
            changes.add("DataVersion=" + after.get(KEY_DATA_VERSION).getAsString() + " 保留");
        }
        return new Verify(problems.isEmpty(), problems, changes);
    }

    /** 读一个统计值（拿不到 ⇒ {@code null}）。 */
    public static Long read(JsonObject root, String category, String key) {
        final JsonObject stats = ShanhaiJsonText.objectOf(root, KEY_STATS);
        final JsonObject body = stats == null ? null : ShanhaiJsonText.objectOf(stats, category);
        return body == null ? null : ShanhaiJsonText.longOf(body, key);
    }

    /** 这个键在不在（区分"没有"与"=0"）。 */
    public static boolean has(JsonObject root, String category, String key) {
        final JsonObject stats = ShanhaiJsonText.objectOf(root, KEY_STATS);
        final JsonObject body = stats == null ? null : ShanhaiJsonText.objectOf(stats, category);
        return body != null && body.has(key);
    }

    // ================================================================== 小工具

    /** 失败结果（不写盘）。 */
    public static Result fail(String why) {
        return new Result(false, why == null ? "failed" : why, List.of(), List.of(), null, "");
    }

    static String shortCat(String cat) {
        return cat != null && cat.startsWith("minecraft:") ? cat.substring("minecraft:".length()) : cat;
    }

    static String shortKey(String key) {
        return key != null && key.startsWith("minecraft:") ? key.substring("minecraft:".length()) : key;
    }

    /** 分类列表的读数（面板提示用）。 */
    public static String categoriesText() {
        final StringBuilder sb = new StringBuilder();
        for (String c : KNOWN_CATEGORIES) {
            sb.append(sb.length() == 0 ? "" : " / ").append(c);
        }
        return sb.toString();
    }

    /** 一个 {@link JsonElement} 是不是合法的"非负整数"（面板暂存时校验用）。 */
    public static Long parseValue(String raw) {
        final String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException nfe) {
            return null;
        }
    }
}
