package com.dishanhai.gt_shanhai.common.shop;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 兑换条目清单加载器（山海署名）。
 *
 * <p>读取 {@code config/gt_shanhai/exchanges.json}（可热改不重编译）。价格由玩家自定义，
 * <b>默认空清单</b>（不预设固定值），玩家通过界面编辑器自己写。格式：</p>
 * <pre>
 * {
 *   "entries": [
 *     { "id": "oak_log_spark", "category": "基础", "name": "原木换星火",
 *       "inputs": [ { "id": "minecraft:oak_log", "fluid": false, "count": 1 } ],
 *       "spark": "32" }
 *   ]
 * }
 * </pre>
 */
public final class ExchangeConfig {

    private static final String CONFIG_DIR = "config/gt_shanhai";
    private static final File EXCHANGE_FILE = new File(CONFIG_DIR, "exchanges.json");
    private static final com.google.gson.Gson GSON =
            new com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static final List<ExchangeEntry> ENTRIES = new ArrayList<>();
    private static boolean loaded = false;

    private ExchangeConfig() {}

    public static List<ExchangeEntry> getEntries() {
        if (!loaded) reload();
        return Collections.unmodifiableList(ENTRIES);
    }

    /** 分类名（有效条目里出现过的 category，去重保序）。 */
    public static List<String> getCategories() {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (ExchangeEntry e : getEntries()) {
            if (e.isValid()) set.add(e.getCategory());
        }
        return new ArrayList<>(set);
    }

    /** 按分类分组的有效条目（保序）。 */
    public static Map<String, List<ExchangeEntry>> getEntriesByCategory() {
        LinkedHashMap<String, List<ExchangeEntry>> map = new LinkedHashMap<>();
        for (ExchangeEntry e : getEntries()) {
            if (!e.isValid()) continue;
            map.computeIfAbsent(e.getCategory(), k -> new ArrayList<>()).add(e);
        }
        return map;
    }

    /** 按 ID 定位条目（packet 用）。 */
    public static ExchangeEntry byId(String id) {
        if (id == null || id.isEmpty()) return null;
        for (ExchangeEntry e : getEntries()) {
            if (id.equals(e.getId())) return e;
        }
        return null;
    }

    // ==================== 编辑：增 / 删 / 改 / 持久化 ====================

    public static synchronized void addEntry(ExchangeEntry entry) {
        if (entry == null) return;
        getEntries();
        ENTRIES.add(entry);
        save();
    }

    public static synchronized boolean removeEntry(ExchangeEntry entry) {
        if (entry == null) return false;
        getEntries();
        boolean removed = ENTRIES.remove(entry);
        if (removed) save();
        return removed;
    }

    public static synchronized boolean replaceEntry(ExchangeEntry oldEntry, ExchangeEntry newEntry) {
        if (oldEntry == null || newEntry == null) return false;
        getEntries();
        int idx = ENTRIES.indexOf(oldEntry);
        if (idx < 0) return false;
        ENTRIES.set(idx, newEntry);
        save();
        return true;
    }

    public static synchronized void save() {
        try {
            new File(CONFIG_DIR).mkdirs();
            JsonObject root = new JsonObject();
            JsonArray arr = new JsonArray();
            for (ExchangeEntry e : ENTRIES) {
                JsonObject o = new JsonObject();
                o.addProperty("id", e.getId());
                o.addProperty("category", e.getCategory());
                o.addProperty("name", e.getName());
                o.add("cost", sideToJson(e.getCost()));
                o.add("result", sideToJson(e.getResult()));
                arr.add(o);
            }
            root.add("entries", arr);
            try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(EXCHANGE_FILE), StandardCharsets.UTF_8)) {
                w.write(GSON.toJson(root));
            }
            GTDishanhaiMod.LOGGER.info("[Exchange] 已保存 {} 个兑换条目到 exchanges.json", ENTRIES.size());
        } catch (Exception e) {
            GTDishanhaiMod.LOGGER.warn("[Exchange] 保存 exchanges.json 失败: {}", e.getMessage());
        }
    }

    public static synchronized void reload() {
        ENTRIES.clear();
        loaded = true;
        if (!EXCHANGE_FILE.exists()) writeDefault();
        try (java.io.Reader r = new java.io.InputStreamReader(
                new java.io.FileInputStream(EXCHANGE_FILE), StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
            JsonArray arr = root.has("entries") ? root.getAsJsonArray("entries") : new JsonArray();
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                ExchangeEntry entry = parseEntry(el.getAsJsonObject());
                if (entry != null) ENTRIES.add(entry);
            }
            GTDishanhaiMod.LOGGER.info("[Exchange] 已加载 {} 个兑换条目", ENTRIES.size());
        } catch (Exception e) {
            GTDishanhaiMod.LOGGER.warn("[Exchange] 读取 exchanges.json 失败: {}", e.getMessage());
        }
    }

    private static ExchangeEntry parseEntry(JsonObject o) {
        try {
            String id = o.has("id") ? o.get("id").getAsString() : "";
            String category = o.has("category") ? o.get("category").getAsString() : "杂项";
            String name = o.has("name") ? o.get("name").getAsString() : "";
            ExchangeEntry.Side cost = parseSide(o.has("cost") ? o.getAsJsonObject("cost") : null);
            ExchangeEntry.Side result = parseSide(o.has("result") ? o.getAsJsonObject("result") : null);
            return new ExchangeEntry(id, category, name, cost, result);
        } catch (Exception e) {
            GTDishanhaiMod.LOGGER.warn("[Exchange] 跳过非法兑换条目: {}", e.getMessage());
            return null;
        }
    }

    /** 序列化一侧：{ "spark":"N", "items":[{id,fluid,count}] }。 */
    private static JsonObject sideToJson(ExchangeEntry.Side side) {
        JsonObject so = new JsonObject();
        so.addProperty("spark", side.spark.toString());
        JsonArray items = new JsonArray();
        for (ExchangeEntry.Ingredient in : side.ingredients) {
            JsonObject io = new JsonObject();
            io.addProperty("id", in.id == null ? "" : in.id.toString());
            io.addProperty("fluid", in.isFluid);
            io.addProperty("count", in.count);
            items.add(io);
        }
        so.add("items", items);
        return so;
    }

    /** 解析一侧（缺失/空对象 → 全空侧）。 */
    private static ExchangeEntry.Side parseSide(JsonObject so) {
        BigInteger spark = BigInteger.ZERO;
        List<ExchangeEntry.Ingredient> items = new ArrayList<>();
        if (so != null) {
            if (so.has("spark")) {
                try { spark = new BigInteger(so.get("spark").getAsString()); } catch (Exception ignored) {}
            }
            if (so.has("items")) {
                for (JsonElement el : so.getAsJsonArray("items")) {
                    if (!el.isJsonObject()) continue;
                    JsonObject io = el.getAsJsonObject();
                    String iid = io.has("id") ? io.get("id").getAsString() : "";
                    if (iid.isEmpty()) continue;
                    boolean fluid = io.has("fluid") && io.get("fluid").getAsBoolean();
                    long count = io.has("count") ? io.get("count").getAsLong() : 1L;
                    items.add(new ExchangeEntry.Ingredient(new ResourceLocation(iid), fluid, count));
                }
            }
        }
        return new ExchangeEntry.Side(spark, items);
    }

    /** 默认写空清单——物品/流体兑换价由玩家自定义，不预设固定值。 */
    private static void writeDefault() {
        try {
            new File(CONFIG_DIR).mkdirs();
            String def = "{\n  \"entries\": []\n}\n";
            try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(EXCHANGE_FILE), StandardCharsets.UTF_8)) {
                w.write(def);
            }
            GTDishanhaiMod.LOGGER.info("[Exchange] 已生成默认（空）exchanges.json");
        } catch (Exception e) {
            GTDishanhaiMod.LOGGER.warn("[Exchange] 写默认 exchanges.json 失败: {}", e.getMessage());
        }
    }
}
