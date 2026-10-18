package com.shanhai.common.itemedit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.shanhai.ShanhaiMod;
import net.minecraft.resources.ResourceLocation;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 山海重构 · <b>「删除/抹除物品」的台账</b>：{@code config/shanhai/removed_items.json}。
 *
 * <h2>1. 它与 {@code item_overrides.json} 的分工（用户规格）</h2>
 * <pre>
 *   item_overrides.json      = 【当前状态】（hidden / removed 两个布尔字段）—— 热路径只读它
 *   removed_items.json（本类）= 【怎么撤回】（被整条停用的配方原 JSON 快照 + 被摘产出的配方 id）
 * </pre>
 * 状态在表里、证据在台账里。回滚时「取消标志」看表、「恢复配方」看台账。
 *
 * <h2>2. 落盘格式</h2>
 * <pre>
 * {
 *   "schema_version": 1,
 *   "updated_at": "2026-…Z",
 *   "entries": [
 *     { "id": "minecraft:apple", "hidden": true, "removed": true, "ts": 1690000000000,
 *       "wanted_by": ["minecraft:crafting/…"],
 *       "stripped_recipes": [
 *          { "kind": "gt", "recipe": "gtceu:…", "type": "gtceu:chemical_reactor", "json": {…} },
 *          { "kind": "vanilla", "recipe": "minecraft:…", "json": {…} }
 *       ] }
 *   ]
 * }
 * </pre>
 * ⚠️ <b>第一版没有方块相关字段</b>（用户 2026-10-17 拍板：方块连带删整条拿掉，因为撤回不了）。
 *
 * <h2>3. 纪律（与 {@link ShanhaiItemOverrideTable} 逐条同构）</h2>
 * 原子写盘（.tmp + ATOMIC_MOVE）、volatile 整表快照、mtime+size 判变化、坏 JSON 整体忽略保留上一份。
 * 热路径<b>不读</b>本类（只有命令与 restore 读），所以这里不做节流。
 */
public final class ShanhaiRemovedItemsLedger {

    public static final String PREFIX = ShanhaiItemOverrideTable.PREFIX;

    /** 相对【游戏目录】。 */
    public static final String RELATIVE_PATH = "config/shanhai/removed_items.json";

    public static final int SCHEMA_VERSION = 1;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /**
     * 一条被摘掉的配方的原样快照。
     *
     * @param json 产出那一段（GT：{@code outputs} 表；原版：{@code {id,type,result}}）
     * @param full 🆕 <b>整条配方的完整 JSON</b>（GT：{@code GTRecipeSerializer.SERIALIZER.toJson(recipe)}）
     *             —— {@code restore} 撞上"整条停用后索引里已经没有它"时，靠它把配方重新造回来。
     *             {@code null} = 这次拿不到（旧台账 / 序列化失败）⇒ restore 打 ERROR，绝不静默。
     */
    public record Stripped(String kind, String recipeId, String typeId, JsonObject json, JsonObject full) {

        /** 兼容旧调用点（没有 full）。 */
        public Stripped(String kind, String recipeId, String typeId, JsonObject json) {
            this(kind, recipeId, typeId, json, null);
        }

        public JsonObject toJson() {
            final JsonObject o = new JsonObject();
            o.addProperty("kind", kind == null ? "vanilla" : kind);
            o.addProperty("recipe", recipeId);
            if (typeId != null && !typeId.isEmpty()) {
                o.addProperty("type", typeId);
            }
            if (json != null) {
                o.add("json", json);
            }
            if (full != null) {
                o.add("full", full);
            }
            return o;
        }

        static Stripped parse(JsonObject o) {
            final String kind = o.has("kind") ? o.get("kind").getAsString() : "vanilla";
            final String rid = o.has("recipe") ? o.get("recipe").getAsString() : "";
            final String tid = o.has("type") ? o.get("type").getAsString() : "";
            final JsonObject json = o.has("json") && o.get("json").isJsonObject()
                    ? o.getAsJsonObject("json") : null;
            final JsonObject full = o.has("full") && o.get("full").isJsonObject()
                    ? o.getAsJsonObject("full") : null;
            return new Stripped(kind, rid, tid, json, full);
        }
    }

    /** 台账里的一条（不可变）。 */
    public record RemovedEntry(String id, boolean hidden, boolean removed, long ts,
                               List<String> wantedBy, List<Stripped> stripped) {}

    private static volatile Map<String, RemovedEntry> byId = Collections.emptyMap();
    private static volatile long loadedMtime = -1L;
    private static volatile long loadedSize = -1L;

    private ShanhaiRemovedItemsLedger() {}

    public static Path path() {
        return net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get()
                .resolve(RELATIVE_PATH).toAbsolutePath().normalize();
    }

    // ------------------------------------------------------------------ 读

    /** 强制重读（命令改台账前先对齐磁盘，避免把别人手改的内容覆盖掉）。 */
    public static void reloadFromDisk() {
        synchronized (ShanhaiRemovedItemsLedger.class) {
            final Path p = path();
            try {
                if (!Files.isRegularFile(p)) {
                    byId = Collections.emptyMap();
                    loadedMtime = -1L;
                    loadedSize = -1L;
                    return;
                }
                final long m = Files.getLastModifiedTime(p).toMillis();
                final long s = Files.size(p);
                if (m == loadedMtime && s == loadedSize) {
                    return;
                }
                final Map<String, RemovedEntry> parsed = new LinkedHashMap<>();
                try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                    final JsonElement el = JsonParser.parseReader(r);
                    if (el == null || !el.isJsonObject()) {
                        ShanhaiMod.LOGGER.error("{} ledger_not_object path={} -> 整体忽略（保留上一份台账）",
                                PREFIX, p);
                        return;
                    }
                    final JsonElement arr = el.getAsJsonObject().get("entries");
                    if (arr != null && arr.isJsonArray()) {
                        for (JsonElement e : arr.getAsJsonArray()) {
                            if (!e.isJsonObject()) {
                                continue;
                            }
                            final RemovedEntry re = parseEntry(e.getAsJsonObject());
                            if (re != null) {
                                parsed.put(re.id(), re);
                            }
                        }
                    }
                }
                byId = Collections.unmodifiableMap(parsed);
                loadedMtime = m;
                loadedSize = s;
                ShanhaiMod.LOGGER.info("{} ledger_loaded path={} entries={} mtime={} size={}",
                        PREFIX, p, parsed.size(), m, s);
            } catch (Throwable t) {
                ShanhaiMod.LOGGER.error("{} ledger_load_failed path={} err={} -> 保留上一份台账",
                        PREFIX, p, t.toString());
            }
        }
    }

    private static RemovedEntry parseEntry(JsonObject o) {
        final String rawId = o.has("id") ? o.get("id").getAsString() : "";
        if (ResourceLocation.tryParse(rawId) == null) {
            return null;
        }
        final boolean removed = o.has("removed") && o.get("removed").getAsBoolean();
        final boolean hidden = removed || (o.has("hidden") && o.get("hidden").getAsBoolean());
        long ts = 0L;
        try {
            if (o.has("ts")) {
                ts = o.get("ts").getAsLong();
            }
        } catch (Throwable ignored) {
            ts = 0L;
        }
        final List<String> wantedBy = new ArrayList<>();
        if (o.has("wanted_by") && o.get("wanted_by").isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray("wanted_by")) {
                wantedBy.add(e.getAsString());
            }
        }
        final List<Stripped> stripped = new ArrayList<>();
        if (o.has("stripped_recipes") && o.get("stripped_recipes").isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray("stripped_recipes")) {
                if (e.isJsonObject()) {
                    stripped.add(Stripped.parse(e.getAsJsonObject()));
                }
            }
        }
        return new RemovedEntry(rawId, hidden, removed, ts,
                Collections.unmodifiableList(wantedBy), Collections.unmodifiableList(stripped));
    }

    // ------------------------------------------------------------------ 查

    public static RemovedEntry get(ResourceLocation id) {
        return id == null ? null : byId.get(id.toString());
    }

    public static List<RemovedEntry> all() {
        final List<RemovedEntry> out = new ArrayList<>(byId.values());
        out.sort((a, b) -> a.id().compareTo(b.id()));
        return out;
    }

    public static int size() {
        return byId.size();
    }

    // ------------------------------------------------------------------ 写

    /**
     * 记一条（按 id 整条替换）。**先落盘、成功才换内存**（与覆盖表同一纪律）。
     */
    public static boolean upsert(ResourceLocation id, boolean hidden, boolean removed,
                                  List<String> wantedBy, List<Stripped> stripped) {
        synchronized (ShanhaiRemovedItemsLedger.class) {
            reloadFromDisk();
            if (id == null) {
                return false;
            }
            final Map<String, RemovedEntry> next = new LinkedHashMap<>(byId);
            next.put(id.toString(), new RemovedEntry(id.toString(), hidden || removed, removed,
                    System.currentTimeMillis(),
                    wantedBy == null ? List.of() : List.copyOf(wantedBy),
                    stripped == null ? List.of() : List.copyOf(stripped)));
            if (!write(next)) {
                return false;
            }
            byId = Collections.unmodifiableMap(next);
            return true;
        }
    }

    /** 删掉一条（restore 成功后调用）。 */
    public static boolean remove(ResourceLocation id) {
        synchronized (ShanhaiRemovedItemsLedger.class) {
            reloadFromDisk();
            if (id == null || !byId.containsKey(id.toString())) {
                return true;      // 本来就没有 = 无需删
            }
            final Map<String, RemovedEntry> next = new LinkedHashMap<>(byId);
            next.remove(id.toString());
            if (!write(next)) {
                return false;
            }
            byId = Collections.unmodifiableMap(next);
            ShanhaiMod.LOGGER.info("{} ledger_removed id={} left={}", PREFIX, id, next.size());
            return true;
        }
    }

    private static boolean write(Map<String, RemovedEntry> table) {
        final Path target = path();
        final JsonObject root = new JsonObject();
        root.addProperty("schema_version", SCHEMA_VERSION);
        root.addProperty("generated_by", ShanhaiItemOverrideTable.GENERATED_BY);
        root.addProperty("_note", "Removal ledger written by /shanhai item remove|restore. "
                + "'stripped_recipes' keeps the ORIGINAL json of every recipe that was disabled or had "
                + "this item stripped from its outputs, so /shanhai item restore can put it back. "
                + "Block-form items are NOT touched in the world (v1).");
        root.addProperty("updated_at", ShanhaiItemOverrideTable.nowTs());
        final JsonArray arr = new JsonArray();
        final List<RemovedEntry> sorted = new ArrayList<>(table.values());
        sorted.sort((a, b) -> a.id().compareTo(b.id()));
        for (RemovedEntry e : sorted) {
            final JsonObject o = new JsonObject();
            o.addProperty("id", e.id());
            o.addProperty("hidden", e.hidden());
            o.addProperty("removed", e.removed());
            o.addProperty("ts", e.ts());
            final JsonArray wb = new JsonArray();
            for (String s : e.wantedBy()) {
                wb.add(s);
            }
            o.add("wanted_by", wb);
            final JsonArray st = new JsonArray();
            for (Stripped s : e.stripped()) {
                st.add(s.toJson());
            }
            o.add("stripped_recipes", st);
            arr.add(o);
        }
        root.add("entries", arr);
        try {
            Files.createDirectories(target.getParent());
            final Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(root, w);
            }
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicFailed) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            syncStat(target);
            return true;
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} ledger_write_failed path={} err={}", PREFIX, target, t.toString());
            return false;
        }
    }

    private static void syncStat(Path p) {
        try {
            if (Files.isRegularFile(p)) {
                loadedMtime = Files.getLastModifiedTime(p).toMillis();
                loadedSize = Files.size(p);
            }
        } catch (Throwable ignored) {
            loadedMtime = -1L;
            loadedSize = -1L;
        }
    }

    public static String statsLine() {
        return "path=" + RELATIVE_PATH + " entries=" + size();
    }
}
