package com.dishanhai.gt_shanhai.common.saveedit;

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
                    + (isSet() ? " value=" + this.value : ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.cd1657621c905cab"));
        }

        /**
         * 校验（面板暂存前 + 落盘前各一次）。
         *
         * @throws IllegalArgumentException 中文原因
         */
        public void validate() {
            if (isEmpty()) {
                throw new IllegalArgumentException(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.815868c0d6d5aeb7"));
            }
            if (!ACTION_SET.equals(this.action) && !ACTION_CLEAR.equals(this.action)) {
                throw new IllegalArgumentException(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.08a33482b8b6c49b") + ACTION_SET + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.60ab743988bb19f7") + ACTION_CLEAR
                        + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.89386c88dfcda546") + this.action + "」");
            }
            if (!ShanhaiJsonText.isResourceLocation(cat())) {
                throw new IllegalArgumentException(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.6df74fb4c301d986")
                        + this.category + "」");
            }
            if (!ShanhaiJsonText.isResourceLocation(statKey())) {
                throw new IllegalArgumentException(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.6d3727f65ddab623")
                        + this.key + "」");
            }
            if (isSet()) {
                if (this.value == null) {
                    throw new IllegalArgumentException(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.cb0466e8a9fee239"));
                }
                if (this.value < 0L) {
                    throw new IllegalArgumentException(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.28cac63c8e76836e") + this.value + "」");
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
                out.add("§c" + (this.error == null ? ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.b70adf1ec584e24c") : this.error));
                return out;
            }
            final StringBuilder cats = new StringBuilder();
            if (this.byCategory != null) {
                for (Map.Entry<String, Integer> e : this.byCategory.entrySet()) {
                    cats.append(cats.length() == 0 ? "" : " · ").append(shortCat(e.getKey()))
                            .append(" ").append(e.getValue());
                }
            }
            out.add(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.2253daecd4a7438d") + this.count + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.645476571106fa07") + (cats.length() == 0 ? "-" : cats)
                    + " · DataVersion " + (this.dataVersion == null ? "-" : this.dataVersion));
            int i = 0;
            for (Entry e : this.entries) {
                if (out.size() >= Math.max(1, max)) {
                    out.add(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.f66f63d1beade69a") + Math.max(0, this.count - i) + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.2e329baea5db32dd"));
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
            return new Listing(false, ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.92f7ef8c72c984ae")
                    + (file == null ? "-" : file.getFileName()), file, null, 0, Map.of(), List.of(),
                    false, null, 0);
        }
        final JsonObject root;
        final int bytes;
        try {
            bytes = (int) Files.size(file);
            root = ShanhaiJsonText.readObject(file);
        } catch (Throwable t) {
            return new Listing(false, ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.241047958f59f8e3") + t, file, null, 0, Map.of(), List.of(),
                    false, null, 0);
        }
        final JsonObject stats = ShanhaiJsonText.objectOf(root, KEY_STATS);
        if (stats == null) {
            return new Listing(false, ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.dfc1a35093ab98d5"), file, null, 0, Map.of(), List.of(),
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
            return fail(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.195155e551cc806a"));
        }
        if (!Files.isRegularFile(file)) {
            return fail(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.92f7ef8c72c984ae") + file);
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
            return fail(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.2f4bbe6458cddeed"));
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
            return fail(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.241047958f59f8e3") + t);
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
            return fail(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.60d59f49b536ac3d") + pre.line());
        }
        // 等价：只允许动 stats.<cat>.<key> 这一个叶子
        final String path = KEY_STATS + "." + req.cat() + "." + req.statKey();
        final List<String> intended = ShanhaiJsonText.diffJson(before, after);
        if (!ShanhaiJsonText.allDiffsUnder(intended, path)) {
            return fail(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.058bf48d5f03d9d1")
                    + ShanhaiJsonText.firstDiffOutside(intended, path) + " intended=" + intended);
        }
        if (intended.isEmpty()) {
            return fail(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.00e11a67c1c99e16"));
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
                return fail(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.0ab2bfaa442c9f81") + post.line());
            }
            final List<String> observed = ShanhaiJsonText.diffJson(before, disk);
            if (!observed.equals(intended)) {
                restore(write, file);
                return fail(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.31f6232e181821c4") + intended + " observed=" + observed + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.d91cbe867beae1bd"));
            }
            notes.add(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.414593989e50fbfb") + path + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.f3550effd00b631c") + observed.size() + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.72ed1749ff2118b6"));
            notes.add(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.6969e0e84bc42022") + content.length + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.70f5cbb91bd5ec47"));
            return new Result(true, "", changed, notes, write, pre.line());
        } catch (Throwable t) {
            restore(write, file);
            return fail(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.d6f6c504a44d9002") + t);
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
            problems.add(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.a8b6fe03fc020b16") + KEY_STATS + "." + category + "." + key);
        } else if (want != null && !want.equals(got)) {
            problems.add(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.50aebcee1705f64d") + want + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.17b9917f171b9c66") + got);
        } else {
            changes.add(KEY_STATS + "." + category + "." + key + "=" + got);
        }
        if (after.has(KEY_DATA_VERSION)) {
            changes.add("DataVersion=" + after.get(KEY_DATA_VERSION).getAsString() + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.57a554d191f38d13"));
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
            problems.add(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.ee8619800b6dc302") + key);
        } else {
            changes.add(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.4f616c9f960e9818") + KEY_STATS + "." + category + "." + key);
        }
        if (after.has(KEY_DATA_VERSION)) {
            changes.add("DataVersion=" + after.get(KEY_DATA_VERSION).getAsString() + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.57a554d191f38d13"));
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
