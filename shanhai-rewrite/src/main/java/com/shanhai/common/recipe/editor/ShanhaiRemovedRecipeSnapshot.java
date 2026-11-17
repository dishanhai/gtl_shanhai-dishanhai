package com.shanhai.common.recipe.editor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.shanhai.ShanhaiMod;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeSerializer;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 山海重构 · <b>「被删除配方的原样快照」</b>：{@code config/shanhai/removed_recipe_snapshots.json}。
 *
 * <h2>1. 它补的是哪个洞（取证结论，不是设计洁癖）</h2>
 * 覆盖文件 {@code config/shanhai/recipe_overrides.json} 里 {@code op=remove} 的条目
 * <b>只有</b> {@code uid / op / id / type / fields(空) / base_fp / ts} 七样
 * （见 {@link ShanhaiRecipeOverrideStore#makeRemoveEntry}）——
 * <b>没有产物、也没有整条配方 JSON</b>。而 {@code base_fp} 是
 * {@code "v3.sorted:json:" + 规范化 JSON 的 400 字窗口}（前 220 + {@code ~~} + 后 180，
 * 见 {@link ShanhaiRecipeFingerprint#truncate}）⇒ <b>靠它还原不出 outputs</b>。
 * 底本 {@link ShanhaiRecipeBase} 只装在<b>内存</b>里，且是"开机那一刻活表里的配方" ——
 * 删过的那条下次开机根本不在活表里 ⇒ 重启之后<b>主产物无从得知</b>。
 *
 * <h2>2. 两个写入时机（一个管以后，一个管已经删掉的）</h2>
 * <ol>
 *   <li><b>删除那一刻</b>：{@link ShanhaiRecipeEditorOps#removeRecipe} 成功之后调
 *       {@link #putLive(GTRecipe)} —— 用 GT 自己的 codec
 *       （{@code GTRecipeSerializer.CODEC}）编出 JSON，形状与配方加载器认的完全一致；</li>
 *   <li><b>开机那一刻</b>：{@link ShanhaiRecipeFingerprintCapture} 在 KubeJS 的配方事件窗口里，
 *       对覆盖文件里<b>所有</b> {@code op=remove/disable} 的 id 补一份
 *       （{@code event.takenIds / originalRecipes} 里那条 {@code RecipeJS.json} 就在手上 ——
 *       本机字节码实证：{@code RecipesEventJS.remove} 只把 {@code RecipeJS.removed} 置 true，
 *       <b>不</b>从 {@code originalRecipes} 里摘掉，见 {@code temp\editor-restore\ev\*.javap.txt}）。
 *       ⇒ 用户那些"旧版本删掉、现在已经看不见产物"的配方，下一次进游戏就会被自动补上。</li>
 * </ol>
 *
 * <h2>3. 拿不到就如实拿不到</h2>
 * 两条写入都失败（或旧条目还没被补上）⇒ 本类不返回任何假的 JSON；
 * 列表那一行显示 {@code §8(主产物未知)}，详情页明确写"这条没有留存快照"。
 * <b>宁可缺，不可假。</b>
 *
 * <h2>4. 落盘纪律（与覆盖文件同一条）</h2>
 * 读—改—写 + {@code .tmp} + {@code ATOMIC_MOVE}；只动 {@code entries}，别的键原样带回去。
 */
public final class ShanhaiRemovedRecipeSnapshot {

    public static final String PREFIX = ShanhaiRecipeOverrideStore.PREFIX;

    /** 相对【游戏目录】。 */
    public static final String RELATIVE_PATH = "config/shanhai/removed_recipe_snapshots.json";

    public static final int SCHEMA_VERSION = 1;

    /** 单条快照的 JSON 上限（超过就不存 —— 存进去也只会把内存与包体拖大）。 */
    public static final int MAX_JSON_CHARS = 256 * 1024;

    /** 最多存多少条（防止有人把整张表都标成删除）。 */
    public static final int MAX_ENTRIES = 4096;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private static final String NOTE =
            "Snapshots of recipes that the in-game editor DELETED (op=remove/disable). "
                    + "recipe_overrides.json only keeps uid/op/id/type/base_fp for a remove entry - no outputs - "
                    + "so this file is the only place that remembers what the main product was. "
                    + "Written (a) at delete time from the live GTRecipe via GTRecipeSerializer.CODEC, and "
                    + "(b) at boot for pre-existing remove entries from KubeJS RecipeJS.json. "
                    + "Safe to delete: the list then shows '(main product unknown)' and the recipe still restores.";

    private ShanhaiRemovedRecipeSnapshot() {}

    public static Path path() {
        return FMLPaths.GAMEDIR.get().resolve(RELATIVE_PATH).toAbsolutePath().normalize();
    }

    // ------------------------------------------------------------------ 读

    /** 整份文件（不存在就返回骨架，不写盘）。 */
    public static JsonObject loadRoot() {
        final Path p = path();
        if (!Files.isRegularFile(p)) {
            return skeleton();
        }
        try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            final JsonElement el = JsonParser.parseReader(r);
            if (el == null || !el.isJsonObject()) {
                ShanhaiMod.LOGGER.error("{} snapshot_not_object path={} -> 当作空表（磁盘上的文件没动）", PREFIX, p);
                return skeleton();
            }
            final JsonObject o = el.getAsJsonObject();
            if (!o.has("entries") || !o.get("entries").isJsonArray()) {
                o.add("entries", new JsonArray());
            }
            return o;
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} snapshot_read_failed path={} err={} -> 当作空表（磁盘上的文件没动）",
                    PREFIX, p, t.toString());
            return skeleton();
        }
    }

    private static JsonObject skeleton() {
        final JsonObject o = new JsonObject();
        o.addProperty("schema_version", SCHEMA_VERSION);
        o.addProperty("generated_by", ShanhaiRecipeOverrideStore.GENERATED_BY);
        o.addProperty("_note", NOTE);
        o.add("entries", new JsonArray());
        return o;
    }

    /** 这条 id 的快照（整份 GT 配方 JSON）；没有 ⇒ {@code null}。 */
    public static JsonObject get(String recipeId) {
        final JsonObject entry = findEntry(loadRoot(), recipeId);
        if (entry == null) {
            return null;
        }
        final JsonElement j = entry.get("json");
        return j != null && j.isJsonObject() ? j.getAsJsonObject() : null;
    }

    public static boolean has(String recipeId) {
        return get(recipeId) != null;
    }

    /**
     * 🆕 <b>这里到底有没有这条的快照</b>（不管它能不能用）。
     *
     * <p>与 {@link #has(String)}（要求读到的是个 JsonObject）的区别：本方法只认"条目在不在"。
     * 用途是让界面能把"<b>缺快照</b>"（盘上压根没有 ⇒ 下次开机还能补）和
     * "<b>快照残了</b>"（有，但内容不可用 ⇒ 补份不会再覆盖它）分成两句话说。
     */
    public static boolean entryExists(String recipeId) {
        return findEntry(loadRoot(), recipeId) != null;
    }

    public static int size() {
        final JsonObject root = loadRoot();
        return root.has("entries") && root.get("entries").isJsonArray() ? root.getAsJsonArray("entries").size() : 0;
    }

    /** 快照里记着的 type（读不到 ⇒ {@code null}）。 */
    public static String typeOf(String recipeId) {
        final JsonObject e = findEntry(loadRoot(), recipeId);
        if (e == null || !e.has("type")) {
            return null;
        }
        try {
            final JsonElement t = e.get("type");
            return t == null || t.isJsonNull() ? null : t.getAsString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 🆕 盘上记着的<b>全部条目 id</b>（一次性读，**不管那份 json 可不可用**）。
     *
     * <p>为什么要有它：列表 17 行若每行都调一次 {@link #entryExists(String)}，就是 17 次
     * "读全文件 + 解析全文件"。批量版只解析一次 ⇒ 与 {@link #allJson()} 配起来用，
     * 列表组装阶段<b>零</b>按行读盘。
     */
    public static java.util.Set<String> allIds() {
        final JsonObject root = loadRoot();
        final java.util.Set<String> out = new java.util.LinkedHashSet<>();
        if (!root.has("entries") || !root.get("entries").isJsonArray()) {
            return out;
        }
        for (JsonElement e : root.getAsJsonArray("entries")) {
            if (!e.isJsonObject()) {
                continue;
            }
            final JsonObject o = e.getAsJsonObject();
            if (o.has("id")) {
                try {
                    out.add(o.get("id").getAsString());
                } catch (Throwable ignored) {
                    // 坏 id 跳过（不影响别的条目）
                }
            }
        }
        return out;
    }

    /** id → 整条配方 JSON（列表面板一次性拿它算主产物）。 */
    public static Map<String, JsonObject> allJson() {
        final JsonObject root = loadRoot();
        final Map<String, JsonObject> out = new LinkedHashMap<>();
        if (!root.has("entries") || !root.get("entries").isJsonArray()) {
            return out;
        }
        for (JsonElement e : root.getAsJsonArray("entries")) {
            if (!e.isJsonObject()) {
                continue;
            }
            final JsonObject o = e.getAsJsonObject();
            if (!o.has("id") || !o.has("json") || !o.get("json").isJsonObject()) {
                continue;
            }
            out.put(o.get("id").getAsString(), o.getAsJsonObject("json"));
        }
        return out;
    }

    private static JsonObject findEntry(JsonObject root, String recipeId) {
        if (root == null || recipeId == null) {
            return null;
        }
        if (!root.has("entries") || !root.get("entries").isJsonArray()) {
            return null;
        }
        for (JsonElement e : root.getAsJsonArray("entries")) {
            if (!e.isJsonObject()) {
                continue;
            }
            final JsonObject o = e.getAsJsonObject();
            if (o.has("id") && recipeId.equals(o.get("id").getAsString())) {
                return o;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 写

    /**
     * 存一条（同 id 覆盖）。JSON 太大 / 不是对象 ⇒ 拒收并打日志（<b>不静默</b>）。
     *
     * @return true = 已落盘
     */
    public static boolean put(String recipeId, String typeId, JsonObject recipeJson) {
        if (recipeId == null || recipeId.isEmpty() || recipeJson == null) {
            return false;
        }
        // 🔴 2026-11-07：**不许把空壳写进来**。KubeJS 的 RecipeJS.json 对脚本新增/被删的配方
        //    只有一个 {"type":"unknown"}（18 字符）。它一旦落盘：
        //    ① 列表会把"缺快照"显示成"有快照但产物未知"（两件不同的事被混成一件）；
        //    ② 补份走"已经存过就跳过"⇒ 这一条**永远**不会再被修好。
        //    ⇒ 入口处一次性拒收（连 putLive 一起受益：万一 codec 编出空壳也不落盘）。
        if (!ShanhaiDeletedRecipeList.isUsableRecipeJson(recipeJson)) {
            ShanhaiMod.LOGGER.warn("{} snapshot_skip reason=unusable_json id={} type={} json_chars={} json_keys={} "
                            + "（缺快照比空壳好：空壳会让补份再也不修它）",
                    PREFIX, recipeId, typeId, lenOf(recipeJson), keysOf(recipeJson));
            return false;
        }
        final String raw;
        try {
            raw = recipeJson.toString();
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("{} snapshot_skip reason=to_string_failed id={} err={}", PREFIX, recipeId, t.toString());
            return false;
        }
        if (raw.length() > MAX_JSON_CHARS) {
            ShanhaiMod.LOGGER.warn("{} snapshot_skip reason=too_big id={} chars={} limit={} "
                            + "（列表那一行会显示「主产物未知」，恢复功能不受影响）",
                    PREFIX, recipeId, raw.length(), MAX_JSON_CHARS);
            return false;
        }
        final JsonObject root = loadRoot();
        final JsonArray entries = root.getAsJsonArray("entries");
        final JsonArray kept = new JsonArray();
        boolean replaced = false;
        for (JsonElement e : entries) {
            if (e.isJsonObject() && e.getAsJsonObject().has("id")
                    && recipeId.equals(e.getAsJsonObject().get("id").getAsString())) {
                kept.add(e);                    // 同 id 的旧条目由下面那条新条目顶替（先保留位置无关紧要）
                replaced = true;
                continue;
            }
            kept.add(e);
        }
        if (kept.size() >= MAX_ENTRIES) {
            ShanhaiMod.LOGGER.warn("{} snapshot_skip reason=table_full entries={} limit={} id={}",
                    PREFIX, kept.size(), MAX_ENTRIES, recipeId);
            return false;
        }
        final JsonObject e = new JsonObject();
        e.addProperty("id", recipeId);
        if (typeId != null && !typeId.isEmpty()) {
            e.addProperty("type", typeId);
        }
        e.addProperty("ts", TS_FMT.format(Instant.now()));
        e.add("json", recipeJson.deepCopy());
        kept.add(e);
        root.add("entries", kept);
        root.add("schema_version", new com.google.gson.JsonPrimitive(SCHEMA_VERSION));
        return write(root, recipeId, replaced ? "replaced" : "added");
    }

    /** 批量补写用的一条（id ＋ type ＋ 整份配方 JSON）。 */
    public record Pending(String id, String type, JsonObject json) {}

    /**
     * <b>批量补写（一次读 ＋ 一次写）</b> —— 开机补份走这一条。
     *
     * <p>为什么不循环调 {@link #put}：那个每存一条就重写整份文件，
     * 用户有 N 条旧删除条目就是 N 次"读全文件 + 写全文件"（N=30、文件 1MB 时就是 60MB 无效 IO）。
     * 批量版只在最后落一次盘，语义完全一样。
     *
     * <p>🔴 2026-11-07 改名（{@code putAllIfAbsent} → 本名，并且删掉了旧的 {@code putIfAbsent}
     * —— 它的口径"id 存在就跳过"是个陷阱：旧版本写进去的空壳会永远挡住补份）：
     * 现在只有<b>已有那份能用</b>（{@link ShanhaiDeletedRecipeList#isUsableRecipeJson}）
     * 才跳过；已有但不可用 ⇒ <b>顶替</b>掉。那正是"重启补一次就再也不修"的病根之一。
     *
     * @return 真正补了几条（0 = 一条都不缺 / 全都不可用被跳过）
     */
    public static int putAllIfMissingOrUnusable(java.util.List<Pending> items) {
        if (items == null || items.isEmpty()) {
            return 0;
        }
        final JsonObject root = loadRoot();
        final JsonArray entries = root.getAsJsonArray("entries");
        // id → 已有那份（用于"已有且可用就跳过；已有但不可用就顶替"）
        final Map<String, JsonObject> existing = new LinkedHashMap<>();
        for (JsonElement e : entries) {
            if (e.isJsonObject() && e.getAsJsonObject().has("id")) {
                final JsonObject o = e.getAsJsonObject();
                JsonObject j = null;
                if (o.has("json") && o.get("json").isJsonObject()) {
                    j = o.getAsJsonObject("json");
                }
                existing.put(o.get("id").getAsString(), j);
            }
        }
        int wrote = 0;
        int fixed = 0;
        int skippedUsable = 0;
        int skippedUnusable = 0;
        final JsonArray kept = new JsonArray();
        for (Pending p : items) {
            if (p == null || p.id() == null || p.id().isEmpty() || p.json() == null) {
                continue;
            }
            if (!ShanhaiDeletedRecipeList.isUsableRecipeJson(p.json())) {
                skippedUnusable++;
                ShanhaiMod.LOGGER.warn("{} snapshot_skip reason=unusable_json id={} type={} json_chars={} "
                        + "json_keys={}（补份拿到的不是配方内容，宁可不写）", PREFIX, p.id(), p.type(),
                        lenOf(p.json()), keysOf(p.json()));
                continue;
            }
            if (existing.containsKey(p.id())) {
                if (ShanhaiDeletedRecipeList.isUsableRecipeJson(existing.get(p.id()))) {
                    skippedUsable++;
                    continue;                     // 已经有好的那份 ⇒ 一个字节都不动
                }
                fixed++;                          // 盘上是空壳/残的 ⇒ 用这份把它顶掉
            }
            final String raw;
            try {
                raw = p.json().toString();
            } catch (Throwable t) {
                continue;
            }
            if (raw.length() > MAX_JSON_CHARS) {
                ShanhaiMod.LOGGER.warn("{} snapshot_skip reason=too_big id={} chars={} limit={}",
                        PREFIX, p.id(), raw.length(), MAX_JSON_CHARS);
                continue;
            }
            // 落盘后的条数 = 旧条数 − 本次顶替掉的 + 本次新增的（`fixed` 只算"顶替"，不涨条数）
            final int projected = entries.size() - fixed + kept.size() + 1;
            if (projected > MAX_ENTRIES) {
                ShanhaiMod.LOGGER.warn("{} snapshot_skip reason=table_full entries={} projected={} limit={}",
                        PREFIX, entries.size(), projected, MAX_ENTRIES);
                break;
            }
            final JsonObject e = new JsonObject();
            e.addProperty("id", p.id());
            if (p.type() != null && !p.type().isEmpty()) {
                e.addProperty("type", p.type());
            }
            e.addProperty("ts", TS_FMT.format(Instant.now()));
            e.add("json", p.json().deepCopy());
            kept.add(e);
            existing.put(p.id(), p.json());
            wrote++;
        }
        if (wrote == 0) {
            if (skippedUsable > 0 || skippedUnusable > 0) {
                ShanhaiMod.LOGGER.info("{} snapshot_backfill_nothing_to_write already_ok={} unusable={}",
                        PREFIX, skippedUsable, skippedUnusable);
            }
            return 0;
        }
        // 旧条目里"没被顶替"的那些原样留下（顺序：先旧后新，与 put() 同口径）。
        // 凡是本次补进了一条可用 json 的 id，旧的（空壳/残的）都丢掉 ⇒ 一条 id 永远只有一份。
        final java.util.Set<String> freshIds = new java.util.HashSet<>();
        for (JsonElement e : kept) {
            if (e.isJsonObject() && e.getAsJsonObject().has("id")) {
                freshIds.add(e.getAsJsonObject().get("id").getAsString());
            }
        }
        final JsonArray merged = new JsonArray();
        for (JsonElement e : entries) {
            if (e.isJsonObject() && e.getAsJsonObject().has("id")
                    && freshIds.contains(e.getAsJsonObject().get("id").getAsString())) {
                continue;                         // 被新那份顶掉
            }
            merged.add(e);
        }
        for (JsonElement e : kept) {
            merged.add(e);
        }
        root.add("entries", merged);
        root.add("schema_version", new com.google.gson.JsonPrimitive(SCHEMA_VERSION));
        final boolean ok = write(root, "(backfill)", "batch:" + wrote + (fixed > 0 ? " replaced_unusable:" + fixed : ""));
        return ok ? wrote : 0;
    }

    /**
     * 把一条<b>活配方</b>编成 JSON 存下来（删除那一刻调）。
     *
     * <p>编码器 = {@code GTRecipeSerializer.CODEC} —— 与 {@link ShanhaiRecipeEditorOps#addRecipeFromJson}
     * 里那条 {@code SERIALIZER.fromJson} 是同一套形状 ⇒ 存下来的东西<b>一定能再解析回去</b>
     * （详情页就靠这一条把输入输出画出来）。编码失败 ⇒ 返回 false 并打 ERROR，<b>不编假数据</b>。
     */
    public static boolean putLive(GTRecipe live) {
        if (live == null || live.id == null) {
            return false;
        }
        final JsonObject json = jsonOf(live);
        if (json == null) {
            return false;
        }
        final String type = live.getType() == null || live.getType().registryName == null
                ? null : live.getType().registryName.toString();
        return put(live.id.toString(), type, json);
    }

    /** 活配方 ⇒ GT 配方 JSON（失败返回 {@code null}，并打 ERROR）。 */
    public static JsonObject jsonOf(GTRecipe live) {
        if (live == null) {
            return null;
        }
        try {
            final DataResult<JsonElement> dr = GTRecipeSerializer.CODEC.encodeStart(JsonOps.INSTANCE, live);
            final JsonElement el = dr.result().orElse(null);
            if (el == null || !el.isJsonObject()) {
                ShanhaiMod.LOGGER.error("{} snapshot_encode_failed id={} err={}",
                        PREFIX, live.id, dr.error().map(Object::toString).orElse("(no result)"));
                return null;
            }
            return el.getAsJsonObject();
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} snapshot_encode_threw id={} err={}", PREFIX, live.id, t.toString());
            return null;
        }
    }

    /** 删掉一条（恢复成功之后调：这条已经不是"已删除"了，留着一份只会让下一轮读到脏数据）。 */
    public static boolean remove(String recipeId) {
        if (recipeId == null) {
            return false;
        }
        final JsonObject root = loadRoot();
        if (!root.has("entries") || !root.get("entries").isJsonArray()) {
            return false;
        }
        final JsonArray kept = new JsonArray();
        int dropped = 0;
        for (JsonElement e : root.getAsJsonArray("entries")) {
            if (e.isJsonObject() && e.getAsJsonObject().has("id")
                    && recipeId.equals(e.getAsJsonObject().get("id").getAsString())) {
                dropped++;
                continue;
            }
            kept.add(e);
        }
        if (dropped == 0) {
            return false;
        }
        root.add("entries", kept);
        return write(root, recipeId, "removed:" + dropped);
    }

    private static boolean write(JsonObject root, String recipeId, String what) {
        final Path p = path();
        try {
            Files.createDirectories(p.getParent());
            final Path tmp = p.resolveSibling(p.getFileName().toString() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(root, w);
            }
            try {
                Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicFailed) {
                Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
            }
            ShanhaiMod.LOGGER.info("{} snapshot_write_ok path={} id={} {} entries={}",
                    PREFIX, p, recipeId, what, root.getAsJsonArray("entries").size());
            return true;
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} snapshot_write_failed path={} id={} err={}", PREFIX, p, recipeId, t.toString());
            return false;
        }
    }

    /** 一行读数（日志/自检用）。 */
    public static String statsLine() {
        final JsonObject root = loadRoot();
        final int n = root.has("entries") && root.get("entries").isJsonArray()
                ? root.getAsJsonArray("entries").size() : -1;
        return "path=" + RELATIVE_PATH + " entries=" + n + " exists=" + Files.isRegularFile(path());
    }

    /** 一份 json 的字符数（日志用；量不出来给 -1）。 */
    private static int lenOf(JsonObject json) {
        try {
            return json == null ? -1 : json.toString().length();
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 一份 json 的键名（日志用；截断到 8 个 —— 只为了看清"它到底是不是空壳"）。 */
    private static String keysOf(JsonObject json) {
        if (json == null) {
            return "(null)";
        }
        try {
            final StringBuilder sb = new StringBuilder("[");
            int n = 0;
            for (String k : json.keySet()) {
                if (n > 0) {
                    sb.append(',');
                }
                if (n >= 8) {
                    sb.append("…");
                    break;
                }
                sb.append(k);
                n++;
            }
            return sb.append(']').toString();
        } catch (Throwable t) {
            return "(?)";
        }
    }

    /**
     * 这个 id 是不是"覆盖层里被整条删掉"的那一条（开机补份那条路要在窗口里判它）。
     */
    public static boolean isRemovedInOverlay(String recipeId) {
        if (recipeId == null) {
            return false;
        }
        try {
            final ResourceLocation id = ResourceLocation.tryParse(recipeId);
            if (id == null) {
                return false;
            }
            final JsonObject e = ShanhaiRecipeOverrideStore.findEntry(id);
            if (e == null || !e.has("op")) {
                return false;
            }
            final String op = e.get("op").getAsString();
            return "remove".equals(op) || "disable".equals(op);
        } catch (Throwable t) {
            return false;
        }
    }
}
