package com.shanhai.common.recipe.editor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.shanhai.ShanhaiMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 配方覆盖文件 {@code config/shanhai/recipe_overrides.json} 的读写。
 *
 * <h2>一、这份文件的格式由谁定</h2>
 * 🔴 <b>不是由本类定，而是由消费者定</b>。消费者是另一条线已经写好的 KubeJS 脚本
 * {@code kubejs/server_scripts/shanhai_recipe_overrides.js}（366 行，已冻结）。本类逐条对着它写：
 * <pre>
 *   顶层     : schema_version (必须 === 1, Number), entries (数组 / java List)
 *   entry    : uid(String) op("set"|"remove"|"add"|"disable") id(ns:path)
 *              type(String, 冗余存一份便于报错) fields(对象) base_fp(String) ts(ISO-8601 UTC)
 *   op=set   : fields 非空，逐个 recipe.set(name, value)
 *   op=add   : 额外需要 recipe 字段（整份配方 JSON），本编辑器【不产出】
 *   op=remove/disable : 不需要 fields
 *   base_fp  : 🔴 不是 sha256，是 {@code "v2." + src + ":" + 截断后的 JSON 串}，见
 *              {@link ShanhaiRecipeFingerprint}。对不上 ⇒ 脚本打 STALE 且【不套用】。
 * </pre>
 *
 * <h2>二、🔴 本类只改 entries，其余键一个字节都不动</h2>
 * 现有文件里有另一条线写下的 {@code _note} 与演示条目。覆盖层脚本读的是 {@code schema_version}
 * 与 {@code entries} 两个键，其余键<b>被读但不用</b>；把它们丢掉是"静默销毁别人的数据"，
 * 本工程红线（宁可缺，不可假）⇒ 本类走<b>读—改—写</b>：把整份 JSON 读成
 * {@link JsonObject}，只动 {@code entries}，其余键原样序列化回去。
 *
 * <h2>三、落盘是原子的</h2>
 * 先写同目录 {@code .tmp}，再 {@code ATOMIC_MOVE} 覆盖目标 ⇒ 掉电/崩在写一半时，
 * 文件要么是旧的、要么是新的，不会是半截 JSON（半截 JSON 会让覆盖层整份 disable）。
 */
public final class ShanhaiRecipeOverrideStore {

    /** 日志前缀（任务书要求统一用 {@code [SHANHAI-EDIT]}，再加一个子标记便于与覆盖层区分）。 */
    public static final String PREFIX = "[SHANHAI-EDIT] editor";

    /** 🔴 相对【游戏目录】的路径。口径依据 = KubeJS 脚本的注释：{@code UtilsJS.getPath} 走
     *  {@code KubeJS.getGameDirectory().resolve(str)}，即 gameDir 相对，不是 kubejs/ 相对。 */
    public static final String RELATIVE_PATH = "config/shanhai/recipe_overrides.json";

    public static final int SCHEMA_VERSION = 1;
    public static final String GENERATED_BY = "shanhai-0.1.0";

    /** 写盘用的 Gson：缩进 4 空格（与现有文件观感一致）；关掉 HTML 转义，避免 {@code >} 变 {@code \u003e}。 */
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private ShanhaiRecipeOverrideStore() {}

    // ------------------------------------------------------------------ 路径

    /** 绝对值：{@code <gameDir>/config/shanhai/recipe_overrides.json}。 */
    public static Path path() {
        return FMLPaths.GAMEDIR.get().resolve(RELATIVE_PATH).toAbsolutePath().normalize();
    }

    /** 覆盖层脚本自己算出来的那一条路径（用于自检里对两个口径）。 */
    public static Path pathViaServerDir(Path serverDirectory) {
        return serverDirectory == null ? null : serverDirectory.resolve(RELATIVE_PATH).toAbsolutePath().normalize();
    }

    public static String nowTs() {
        return TS_FMT.format(Instant.now());
    }

    // ------------------------------------------------------------------ 读

    /** 读整份文件；不存在 / 解析失败时返回一份只有骨架的新对象（并如实打日志，不静默）。 */
    public static JsonObject loadRoot() {
        final Path p = path();
        if (!Files.isRegularFile(p)) {
            ShanhaiMod.LOGGER.info("{} store_missing path={} -> using fresh skeleton", PREFIX, p);
            return skeleton();
        }
        try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            final JsonElement el = JsonParser.parseReader(r);
            if (el == null || !el.isJsonObject()) {
                ShanhaiMod.LOGGER.error("{} store_not_object path={} -> using fresh skeleton (existing file NOT touched yet)",
                        PREFIX, p);
                return skeleton();
            }
            final JsonObject o = el.getAsJsonObject();
            if (!o.has("entries") || !o.get("entries").isJsonArray()) {
                o.add("entries", new JsonArray());
            }
            if (!o.has("schema_version")) {
                o.addProperty("schema_version", SCHEMA_VERSION);
            }
            return o;
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} store_read_failed path={} err={} -> using fresh skeleton", PREFIX, p, t.toString());
            return skeleton();
        }
    }

    private static JsonObject skeleton() {
        final JsonObject o = new JsonObject();
        o.addProperty("schema_version", SCHEMA_VERSION);
        o.addProperty("generated_by", GENERATED_BY);
        o.add("entries", new JsonArray());
        return o;
    }

    /** 当前 entries 条数（读不到就 0）。 */
    public static int entryCount() {
        final JsonObject root = loadRoot();
        return root.has("entries") && root.get("entries").isJsonArray() ? root.getAsJsonArray("entries").size() : 0;
    }

    /**
     * <b>这份账本里记着的全部 id</b>（覆盖层脚本会去套用的那些）。
     *
     * <p>用途：开面板时发给客户端做一次<b>对账</b>（{@link ShanhaiJeiBridge#sendReconcile}）——
     * 客户端只保留名单里的补丁，其余一律丢掉。修的就是用户那句
     * 「一重进存档就 KJS 报错／机器跑的是老配方／<b>但 JEI 里还看得见改过的</b>」里第三条那个假象。
     *
     * <p>⚠️ 这里给的是"账本上写了什么"，不是"服务端这一拍真的套用成功了什么"。
     * 两者在覆盖层正常工作时等价；不等价时（例如指纹 STALE）客户端会被清掉补丁，
     * 显示的是<b>真实</b>的那份 —— 这正是我们要的方向（宁可显示真的，也不显示假的）。
     */
    public static java.util.List<String> appliedIds() {
        final java.util.List<String> out = new java.util.ArrayList<>();
        final JsonObject root = loadRoot();
        if (!root.has("entries") || !root.get("entries").isJsonArray()) {
            return out;
        }
        for (JsonElement e : root.getAsJsonArray("entries")) {
            if (e.isJsonObject() && e.getAsJsonObject().has("id")) {
                final String id = e.getAsJsonObject().get("id").getAsString();
                if (!id.isEmpty() && !out.contains(id)) {
                    out.add(id);
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 写

    /**
     * 🆕 第 11 刀：这份账本里各类动作各有几条 ⇒ {@code [修改(set), 删除(remove), 新增(add)]}。
     *
     * <p>用途 = 横幅那一行「🛠 通过配方编辑器修改/删除/新增」。
     * 口径：<b>按条目数</b>（这个文件本身按 id 去重，见 {@link #upsert}）⇒ 同一条配方改十次仍然只算 1 条。
     * 读不出文件 ⇒ 返回 {@code null}（调用方印「(不可用)」，<b>绝不编一个 0</b>）。
     */
    public static int[] countsByOp() {
        try {
            final JsonObject root = loadRoot();
            if (root == null || !root.has("entries") || !root.get("entries").isJsonArray()) {
                return null;
            }
            int set = 0;
            int remove = 0;
            int add = 0;
            for (JsonElement e : root.getAsJsonArray("entries")) {
                if (!e.isJsonObject()) {
                    continue;
                }
                final JsonObject o = e.getAsJsonObject();
                if (!o.has("op")) {
                    continue;
                }
                switch (o.get("op").getAsString()) {
                    case "set" -> set++;
                    case "remove", "disable" -> remove++;
                    case "add" -> add++;
                    default -> {
                        // 未知 op：不算进任何一类（覆盖层会打 SKIPPED，这里不替它编）
                    }
                }
            }
            return new int[]{set, remove, add};
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} counts_by_op_failed err={}", PREFIX, t.toString());
            return null;
        }
    }

    /** 找到某条 id 现有的条目（没有就 null）。 */
    public static JsonObject findEntry(ResourceLocation id) {        if (id == null) {
            return null;
        }
        final JsonObject root = loadRoot();
        if (!root.has("entries") || !root.get("entries").isJsonArray()) {
            return null;
        }
        final String want = id.toString();
        for (JsonElement e : root.getAsJsonArray("entries")) {
            if (e.isJsonObject() && e.getAsJsonObject().has("id")
                    && want.equals(e.getAsJsonObject().get("id").getAsString())) {
                return e.getAsJsonObject();
            }
        }
        return null;
    }

    /** 归属标记键：本编辑器接管这份文件之后写进去。 */
    public static final String OWNED_KEY = "owned_by";

    /** 归属标记值。 */
    public static final String OWNED_VALUE = "shanhai-editor";

    /**
     * <b>首次接管</b>：如果这份文件还没有归属标记，就把不属于本编辑器的条目全丢掉，
     * 并打上 {@link #OWNED_KEY}。
     *
     * <h2>🔴 为什么必须有这一步（真事故，不是洁癖）</h2>
     * 文件里曾经存在覆盖层那条线留下的两条 DEMO（{@code e-0001} 把某条配方
     * {@code duration} 300→20、{@code e-0002} 删掉另一条）。那两条的 {@code base_fp} 是在
     * <b>冒烟装置那个实例</b>里取的，而用户实例装了 dgy 等宿主脚本 ⇒ 同一条配方在那边是
     * {@code 300} tick、在用户实例里是 {@code 1} tick ⇒ <b>指纹天然对不上</b> ⇒
     * 用户每次进游戏都会看到 {@code KubeJS errors found [8]!}。
     * 而这份文件的语义是"编辑器自己的账本" ⇒ 别人的测试靶子不该留在里面。
     *
     * <h2>为什么是"只做一次"而不是"每次写都清"</h2>
     * 每次写都清会把<b>用户自己手改进去的条目</b>也一起吃掉 —— 那是"静默销毁用户数据"。
     * 打上归属标记之后本方法就永远短路，用户后来加的条目一律原样保留。
     *
     * @return 丢掉的条数（0 = 不是首次接管，或本来就没有外来条目）
     */
    private static int dropForeignIfUnowned(JsonObject root) {
        if (root.has(OWNED_KEY) && OWNED_VALUE.equals(root.get(OWNED_KEY).getAsString())) {
            return 0;
        }
        final JsonArray entries = root.getAsJsonArray("entries");
        final JsonArray kept = new JsonArray();
        int dropped = 0;
        for (JsonElement e : entries) {
            final String uid = e.isJsonObject() && e.getAsJsonObject().has("uid")
                    ? e.getAsJsonObject().get("uid").getAsString() : "";
            if (uid.startsWith(UID_PREFIX)) {
                kept.add(e);
            } else {
                dropped++;
            }
        }
        root.add("entries", kept);
        root.addProperty(OWNED_KEY, OWNED_VALUE);
        // _note 在接管的那一刻必须【改写】—— 上一版 note 说的是"INTENTIONALLY EMPTY"，
        // 编辑器一旦写进条目它就变成了一句假话（本工程红线：宁可缺，不可假）。
        root.addProperty("_note", OWNED_NOTE);
        return dropped;
    }

    /** 接管后写进去的说明（准确描述"这份文件现在是谁在写、指纹口径是什么"）。 */
    private static final String OWNED_NOTE =
            "Maintained by the in-game recipe editor (/shanhai edit). Each entry's base_fp is computed "
                    + "in THIS instance, from the live KubeJS RecipeJS.json of that recipe, serialized on one line "
                    + "and truncated to a 400-char window (first 220 + ~~ + last 180), prefixed v2.json:. "
                    + "The overlay logs STALE and does NOT apply when base_fp does not match - that is the safety "
                    + "valve, not a bug. One entry per target id; later entries for the same id win.";

    /** 显式接管（幂等）：把外来条目清掉并打归属标记，随后落盘。用于运维清理。 */
    public static int takeover() {
        final JsonObject root = loadRoot();
        final int dropped = dropForeignIfUnowned(root);
        root.add("schema_version", new com.google.gson.JsonPrimitive(SCHEMA_VERSION));
        if (!root.has("generated_by")) {
            root.addProperty("generated_by", GENERATED_BY);
        }
        try {
            writeRoot(path(), root);
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} takeover_write_failed err={}", PREFIX, t.toString());
            return -1;
        }
        ShanhaiMod.LOGGER.info("{} takeover_ok path={} dropped={} entries={}",
                PREFIX, path(), dropped, root.getAsJsonArray("entries").size());
        return dropped;
    }

    /**
     * 插入 / 覆盖一条条目，然后整份落盘。
     *
     * <p><b>覆盖规则</b>：同一个 {@code id} 只保留一条 —— 后写的赢（先把同 id 的旧条目全删掉再追加）。
     * 为什么按 id 而不是 uid：覆盖层脚本是"逐条对目标 id 施加动作"，同 id 两条会得到
     * "先 set 后 remove" 这种自相矛盾的结果，而它们各自都合法 ⇒ 必须在写入口就消歧。
     *
     * @return 落盘后的 entries 条数；写失败返回 -1（并打 ERROR）
     */
    public static int upsert(JsonObject entry) {
        final String id = entry.has("id") ? entry.get("id").getAsString() : null;
        if (id == null || id.isEmpty()) {
            ShanhaiMod.LOGGER.error("{} upsert_refused reason=entry_has_no_id", PREFIX);
            return -1;
        }
        final JsonObject root = loadRoot();
        final int foreign = dropForeignIfUnowned(root);
        final JsonArray entries = root.getAsJsonArray("entries");

        final JsonArray kept = new JsonArray();
        int dropped = 0;
        for (JsonElement e : entries) {
            if (e.isJsonObject() && e.getAsJsonObject().has("id")
                    && id.equals(e.getAsJsonObject().get("id").getAsString())) {
                dropped++;
                continue;
            }
            kept.add(e);
        }
        kept.add(entry);

        root.add("schema_version", new com.google.gson.JsonPrimitive(SCHEMA_VERSION));
        if (!root.has("generated_by")) {
            root.addProperty("generated_by", GENERATED_BY);
        }
        root.add("entries", kept);

        final Path p = path();
        try {
            writeRoot(p, root);
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} upsert_write_failed path={} err={}", PREFIX, p, t.toString());
            return -1;
        }
        if (foreign > 0) {
            ShanhaiMod.LOGGER.warn("{} upsert_takeover dropped_foreign_entries={} "
                            + "(首次接管：这些条目不是本编辑器写的，指纹口径与本实例对不上 ⇒ 留着会让用户每次开局看到 KubeJS 报错)",
                    PREFIX, foreign);
        }
        ShanhaiMod.LOGGER.info("{} upsert_ok path={} id={} dropped_same_id={} entries={}",
                PREFIX, p, id, dropped, kept.size());
        return kept.size();
    }

    /**
     * <b>多字段版</b>（第二刀）：fields 用<b>调用方给的整份对象</b>。
     *
     * <p>为什么要这一版：{@code op=set} 的消费者是"逐个 {@code recipe.set(name, fields[name])}"，
     * 而第二刀要一次写 {@code inputs ＋ data}（电压必须同拍写 {@code data.euTier}）。
     * 用标量重载就没法表达"一次改两个字段"，分两条 entry 写又会让覆盖层对同一条配方
     * 先后套用两次 ⇒ 指纹对不上。
     */
    public static JsonObject makeSetEntry(String uid, String id, String type,
                                          JsonObject fields, String baseFp) {
        final JsonObject e = new JsonObject();
        e.addProperty("uid", uid);
        e.addProperty("op", "set");
        e.addProperty("id", id);
        if (type != null) {
            e.addProperty("type", type);
        }
        e.add("fields", fields == null ? new JsonObject() : fields);
        e.addProperty("base_fp", baseFp == null ? "" : baseFp);
        e.addProperty("ts", nowTs());
        return e;
    }

    /** 构造一条 {@code op=set} 条目。fields 由调用方填（本编辑器只做 duration）。 */
    public static JsonObject makeSetEntry(String uid, String id, String type, String field, Object value, String baseFp) {
        final JsonObject e = new JsonObject();
        e.addProperty("uid", uid);
        e.addProperty("op", "set");
        e.addProperty("id", id);
        if (type != null) {
            e.addProperty("type", type);
        }
        final JsonObject fields = new JsonObject();
        if (value instanceof Number n) {
            fields.addProperty(field, n);
        } else {
            fields.addProperty(field, String.valueOf(value));
        }
        e.add("fields", fields);
        e.addProperty("base_fp", baseFp == null ? "" : baseFp);
        e.addProperty("ts", nowTs());
        return e;
    }

    /** 构造一条 {@code op=remove} 条目。 */
    public static JsonObject makeRemoveEntry(String uid, String id, String type, String baseFp) {
        final JsonObject e = new JsonObject();
        e.addProperty("uid", uid);
        e.addProperty("op", "remove");
        e.addProperty("id", id);
        if (type != null) {
            e.addProperty("type", type);
        }
        e.add("fields", new JsonObject());
        e.addProperty("base_fp", baseFp == null ? "" : baseFp);
        e.addProperty("ts", nowTs());
        return e;
    }

    /**
     * 🆕 2026-10-05 第 7 轮（队列 #3「新建配方」）：构造一条 <b>{@code op=add}</b> 条目。
     *
     * <h4>为什么这条通道【已经存在】，本方法只是把入口接上</h4>
     * 覆盖层脚本 {@code kubejs/server_scripts/shanhai_recipe_overrides.js} 里早就有
     * {@code op === 'add'} 那一支（原始行，逐字）：
     * <pre>
     *   if (op !== 'add' && op !== 'set' && op !== 'remove' && op !== 'disable') { … unknown op … }
     *   if (op === 'add') {
     *       if (cnt &gt; 0) { … 'STALE op=add … id already exists; refusing to add' … }
     *       event.custom(JsonIO.of(e.recipe)).id(id);
     *       …
     *   }
     * </pre>
     * ⇒ 它读的就是本方法写出去的 {@code recipe} 字段（一份 GT 配方 JSON），
     * 并且<b>自带"id 已存在就拒收"的保护</b>（不会静默覆盖）。
     * 本轮之前编辑器从来没写过这个 op，所以这条通道<b>一次都没被真机走过</b> ——
     * 这一点在交付报告里如实交代。
     *
     * @param recipe 完整的 GT 配方 JSON（形状与 {@code local/kubejs/export/recipes/**} 里的一致）
     */
    public static JsonObject makeAddEntry(String uid, String id, String type, JsonObject recipe) {
        final JsonObject e = new JsonObject();
        e.addProperty("uid", uid);
        e.addProperty("op", "add");
        e.addProperty("id", id);
        if (type != null) {
            e.addProperty("type", type);
        }
        e.add("recipe", recipe == null ? new JsonObject() : recipe);
        e.addProperty("ts", nowTs());
        return e;
    }

    /** 生成一个不与现有条目冲突的 uid（{@code e-editor-YYMMDD-HHMMSS}）。 */
    public static String nextUid() {
        final String stamp = DateTimeFormatter.ofPattern("yyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now());
        return UID_PREFIX + stamp;
    }

    /**
     * 本编辑器写下的条目一律用这个 uid 前缀。
     *
     * <p>为什么要有前缀：覆盖层脚本只用 uid 打日志（不解析它），所以前缀任选；
     * 但"自检写进去的东西要能被干净地收回来"必须有个可判据 ⇒ 用前缀当回收判据。
     * 前缀 {@code e-editor-} 与覆盖层演示条目（{@code e-0001}）不会撞。
     */
    public static final String UID_PREFIX = "e-editor-";

    /**
     * 把 uid 以 {@code prefix} 开头的条目全删掉并落盘。
     *
     * @return 删掉的条数；写失败返回 -1
     */
    public static int removeEntriesByUidPrefix(String prefix) {
        final JsonObject root = loadRoot();
        final JsonArray entries = root.getAsJsonArray("entries");
        final JsonArray kept = new JsonArray();
        int dropped = 0;
        for (JsonElement e : entries) {
            if (e.isJsonObject() && e.getAsJsonObject().has("uid")
                    && e.getAsJsonObject().get("uid").getAsString().startsWith(prefix)) {
                dropped++;
                continue;
            }
            kept.add(e);
        }
        root.add("entries", kept);
        final Path p = path();
        try {
            writeRoot(p, root);
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} cleanup_write_failed path={} err={}", PREFIX, p, t.toString());
            return -1;
        }
        ShanhaiMod.LOGGER.info("{} cleanup_ok path={} prefix={} dropped={} entries={}",
                PREFIX, p, prefix, dropped, kept.size());
        return dropped;
    }

    /**
     * <b>只恢复一条</b>：把这条 id 的条目从 entries 里删掉并落盘。
     *
     * <p>只动 {@code entries}，其余键（{@code _note} / {@code owned_by} / 用户自己加的键）一个字节不动
     * —— 与 {@link #upsert} 同一条纪律。
     *
     * @return 删掉的条数；写失败返回 -1
     */
    public static int removeEntryById(ResourceLocation id) {
        if (id == null) {
            return -1;
        }
        final JsonObject root = loadRoot();
        if (!root.has("entries") || !root.get("entries").isJsonArray()) {
            return 0;
        }
        final String want = id.toString();
        final JsonArray kept = new JsonArray();
        int dropped = 0;
        for (JsonElement e : root.getAsJsonArray("entries")) {
            if (e.isJsonObject() && e.getAsJsonObject().has("id")
                    && want.equals(e.getAsJsonObject().get("id").getAsString())) {
                dropped++;
                continue;
            }
            kept.add(e);
        }
        root.add("entries", kept);
        final Path p = path();
        try {
            writeRoot(p, root);
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} restore_one_write_failed path={} err={}", PREFIX, p, t.toString());
            return -1;
        }
        ShanhaiMod.LOGGER.info("{} restore_one_ok path={} id={} dropped={} entries_left={}",
                PREFIX, p, id, dropped, kept.size());
        return dropped;
    }

    /**
     * <b>全部恢复</b>：把 entries 清空并落盘（其余键保持）。
     *
     * @return 被清掉的条数；写失败返回 -1
     */
    public static int clearAllEntries() {
        final JsonObject root = loadRoot();
        final int before = root.has("entries") && root.get("entries").isJsonArray()
                ? root.getAsJsonArray("entries").size() : 0;
        root.add("entries", new JsonArray());
        // 🔴 保留 owned_by 与 _note：它们描述的是"这份文件归谁维护"，不是"有没有编辑"。
        //    删掉它们会让下一次写盘再走一遍"首次接管"，把用户自己加的条目吃掉。
        final Path p = path();
        try {
            writeRoot(p, root);
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} restore_all_write_failed path={} err={}", PREFIX, p, t.toString());
            return -1;
        }
        ShanhaiMod.LOGGER.info("{} restore_all_ok path={} cleared={} entries_left={}",
                PREFIX, p, before, root.getAsJsonArray("entries").size());
        return before;
    }

    /** 把整份 root 原子写盘（供 upsert / cleanup / 自检共用）。 */
    public static void writeRoot(Path target, JsonObject root) throws IOException {
        final Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp");
        Files.createDirectories(target.getParent());
        try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            GSON.toJson(root, w);
        }
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailed) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 直接按字节把一份快照写回（自检的还原拍用它，保证逐字节回滚）。 */
    public static void restoreBytes(Path target, byte[] bytes) throws IOException {
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
    }
}
