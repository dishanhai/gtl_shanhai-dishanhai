package com.dishanhai.gt_shanhai.common.shop;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;

import java.io.File;
import java.io.OutputStreamWriter;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 商店商品清单加载器。
 * 读取 config/gt_shanhai/shop.json（可热改不重编译），格式：
 * <pre>
 * {
 *   "entries": [
 *     { "goods": "minecraft:diamond", "count": 1, "currency": "dishanhai:dog_coins", "price": 4, "category": "矿物" },
 *     { "goods": "minecraft:bread",   "count": 8, "currency": "dishanhai:dog_coins", "price": 1, "category": "食物" }
 *   ]
 * }
 * </pre>
 * category 可选，缺省为「杂货」。文件不存在时自动写出一份带示例的默认文件。
 */
public final class ShopConfig {

    private static final String CONFIG_DIR = "config/gt_shanhai";
    private static final File SHOP_FILE = new File(CONFIG_DIR, "shop.json");

    private static final List<ShopEntry> ENTRIES = new ArrayList<>();
    private static boolean loaded = false;

    private ShopConfig() {}

    /** 获取商品清单（首次访问时懒加载）。 */
    public static List<ShopEntry> getEntries() {
        if (!loaded) {
            reload();
        }
        return Collections.unmodifiableList(ENTRIES);
    }

    /** 获取所有被商店接受的货币 ID 集合（= 商品清单里出现过的 currency，去重、保序）。 */
    public static Set<ResourceLocation> getAcceptedCurrencies() {
        LinkedHashSet<ResourceLocation> set = new LinkedHashSet<>();
        for (ShopEntry entry : getEntries()) {
            set.add(entry.getCurrencyId());
        }
        return set;
    }

    /** 获取所有分类名（= 商品清单里出现过的 category，去重、保序）。 */
    public static List<String> getCategories() {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (ShopEntry entry : getEntries()) {
            if (entry.isValid()) {
                set.add(entry.getCategory());
            }
        }
        return new ArrayList<>(set);
    }

    /** 按分类分组的有效商品（保序）。 */
    public static Map<String, List<ShopEntry>> getEntriesByCategory() {
        LinkedHashMap<String, List<ShopEntry>> map = new LinkedHashMap<>();
        for (ShopEntry entry : getEntries()) {
            if (!entry.isValid()) continue;
            map.computeIfAbsent(entry.getCategory(), k -> new ArrayList<>()).add(entry);
        }
        return map;
    }

    /** 取某分类下的有效商品。 */
    public static List<ShopEntry> getEntriesOf(String category) {
        List<ShopEntry> result = new ArrayList<>();
        for (ShopEntry entry : getEntries()) {
            if (entry.isValid() && entry.getCategory().equals(category)) {
                result.add(entry);
            }
        }
        return result;
    }

    // ==================== 编辑模式：增 / 删 / 持久化 ====================

    /** 新增一个商品条目并写回 shop.json。 */
    public static synchronized void addEntry(ShopEntry entry) {
        if (entry == null) return;
        getEntries(); // 确保已加载
        ENTRIES.add(entry);
        save();
    }

    /** 删除一个商品条目（按对象引用）并写回 shop.json；返回是否删除成功。 */
    public static synchronized boolean removeEntry(ShopEntry entry) {
        if (entry == null) return false;
        getEntries();
        boolean removed = ENTRIES.remove(entry);
        if (removed) {
            save();
        }
        return removed;
    }

    /** 用新条目替换旧条目并写回；返回是否替换成功。 */
    public static synchronized boolean replaceEntry(ShopEntry oldEntry, ShopEntry newEntry) {
        if (oldEntry == null || newEntry == null) return false;
        getEntries();
        int idx = ENTRIES.indexOf(oldEntry);
        if (idx < 0) return false;
        ENTRIES.set(idx, newEntry);
        save();
        return true;
    }

    private static final com.google.gson.Gson GSON =
            new com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** 把当前内存中的商品清单写回 shop.json（Gson 规范序列化，NBT 以 SNBT 字符串存 "nbt" 字段）。 */
    public static synchronized void save() {
        try {
            new File(CONFIG_DIR).mkdirs();
            JsonObject root = new JsonObject();
            JsonArray arr = new JsonArray();
            for (ShopEntry e : ENTRIES) {
                JsonObject o = new JsonObject();
                o.addProperty("goods", e.getGoodsId().toString());
                o.addProperty("count", e.getGoodsCount());
                o.addProperty("currency", e.getCurrencyId().toString());
                o.addProperty("price", e.getPrice());
                o.addProperty("category", e.getCategory());
                net.minecraft.nbt.CompoundTag nbt = e.getGoodsNbt();
                if (nbt != null && !nbt.isEmpty()) {
                    o.addProperty("nbt", nbt.toString()); // SNBT 文本，Gson 负责转义
                }
                arr.add(o);
            }
            root.add("entries", arr);
            try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(SHOP_FILE), StandardCharsets.UTF_8)) {
                w.write(GSON.toJson(root));
            }
            GTDishanhaiMod.LOGGER.info("[Shop] 已保存 {} 个商品到 shop.json", ENTRIES.size());
        } catch (Exception e) {
            GTDishanhaiMod.LOGGER.warn("[Shop] 保存 shop.json 失败: {}", e.getMessage());
        }
    }

    /** 从磁盘重新加载商品清单；文件缺失时生成默认文件。 */
    public static synchronized void reload() {        ENTRIES.clear();
        loaded = true;
        if (!SHOP_FILE.exists()) {
            writeDefault();
        }
        // 必须显式 UTF-8：FileReader 用系统默认编码（Windows 为 GBK），会把 UTF-8 中文读成乱码
        try (java.io.Reader r = new java.io.InputStreamReader(
                new java.io.FileInputStream(SHOP_FILE), StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
            JsonArray arr = root.has("entries") ? root.getAsJsonArray("entries") : new JsonArray();
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                ShopEntry entry = parseEntry(o);
                if (entry != null) {
                    ENTRIES.add(entry);
                }
            }
            GTDishanhaiMod.LOGGER.info("[Shop] 已加载 {} 个商品", ENTRIES.size());
        } catch (Exception e) {
            GTDishanhaiMod.LOGGER.warn("[Shop] 读取 shop.json 失败: {}", e.getMessage());
        }
    }

    private static ShopEntry parseEntry(JsonObject o) {
        try {
            String goods = o.get("goods").getAsString();
            String currency = o.has("currency") ? o.get("currency").getAsString() : "dishanhai:dog_coins";
            int count = o.has("count") ? o.get("count").getAsInt() : 1;
            int price = o.has("price") ? o.get("price").getAsInt() : 1;
            String category = o.has("category") ? o.get("category").getAsString() : ShopEntry.DEFAULT_CATEGORY;
            // NBT（可选）：以 SNBT 文本存储，解析失败则忽略 NBT 而非丢弃整条
            net.minecraft.nbt.CompoundTag nbt = null;
            if (o.has("nbt")) {
                try {
                    String snbt = o.get("nbt").getAsString();
                    if (snbt != null && !snbt.isBlank()) {
                        nbt = net.minecraft.nbt.TagParser.parseTag(snbt);
                    }
                } catch (Exception ex) {
                    GTDishanhaiMod.LOGGER.warn("[Shop] 商品 {} 的 NBT 解析失败，按无 NBT 处理: {}", goods, ex.getMessage());
                }
            }
            return new ShopEntry(new ResourceLocation(goods), count, new ResourceLocation(currency), price, category, nbt);
        } catch (Exception e) {
            GTDishanhaiMod.LOGGER.warn("[Shop] 跳过非法商品条目: {}", e.getMessage());
            return null;
        }
    }

    private static void writeDefault() {
        try {
            new File(CONFIG_DIR).mkdirs();
            String def = "{\n"
                    + "  \"entries\": [\n"
                    + "    { \"goods\": \"minecraft:diamond\", \"count\": 1, \"currency\": \"dishanhai:dog_coins\", \"price\": 4, \"category\": \"矿物\" },\n"
                    + "    { \"goods\": \"minecraft:iron_ingot\", \"count\": 8, \"currency\": \"dishanhai:dog_coins\", \"price\": 1, \"category\": \"矿物\" },\n"
                    + "    { \"goods\": \"minecraft:gold_ingot\", \"count\": 4, \"currency\": \"dishanhai:dog_coins\", \"price\": 2, \"category\": \"矿物\" },\n"
                    + "    { \"goods\": \"minecraft:bread\", \"count\": 16, \"currency\": \"dishanhai:dog_coins\", \"price\": 1, \"category\": \"食物\" },\n"
                    + "    { \"goods\": \"minecraft:golden_apple\", \"count\": 1, \"currency\": \"dishanhai:dog_coins\", \"price\": 8, \"category\": \"食物\" },\n"
                    + "    { \"goods\": \"minecraft:torch\", \"count\": 64, \"currency\": \"dishanhai:dog_coins\", \"price\": 1, \"category\": \"杂货\" }\n"
                    + "  ]\n"
                    + "}\n";
            try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(SHOP_FILE), StandardCharsets.UTF_8)) {
                w.write(def);
            }
            GTDishanhaiMod.LOGGER.info("[Shop] 已生成默认 shop.json");
        } catch (Exception e) {
            GTDishanhaiMod.LOGGER.warn("[Shop] 写默认 shop.json 失败: {}", e.getMessage());
        }
    }
}
