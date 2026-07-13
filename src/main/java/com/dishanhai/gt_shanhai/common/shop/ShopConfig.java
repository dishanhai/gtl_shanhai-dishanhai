package com.dishanhai.gt_shanhai.common.shop;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;

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

    private static volatile ShopCatalogSnapshot snapshot = ShopCatalogSnapshot.empty();
    private static volatile boolean loaded = false;
    private static long nextRevision = Math.max(1L, System.currentTimeMillis());

    private ShopConfig() {}

    /** 获取商品清单（首次访问时懒加载）。 */
    public static List<ShopEntry> getEntries() {
        if (!loaded) {
            reload();
        }
        return snapshot.entries();
    }

    /** 当前完整目录快照；结构只会整体替换，不暴露半加载列表。 */
    public static ShopCatalogSnapshot snapshot() {
        if (!loaded) reload();
        return snapshot;
    }

    /** 当前轻量目录清单，供打开商店与结构刷新包同步。 */
    public static ShopCatalogManifest manifest() {
        return snapshot().manifest();
    }

    /** 按服务端目录版本和条目身份精确解析；版本过期时拒绝猜测。 */
    public static ShopEntry resolve(long revision, long entryKey) {
        ShopCatalogSnapshot current = snapshot();
        return current.revision() != revision ? null : current.resolve(entryKey);
    }

    public static long keyOf(ShopEntry entry) {
        return snapshot().keyOf(entry);
    }

    public static List<ShopCatalogEntryPayload> chunk(long revision, int chunkId) {
        ShopCatalogSnapshot current = snapshot();
        return current.revision() != revision ? List.of() : current.chunk(chunkId);
    }

    /** 获取所有被商店接受的币种 ID 集合（= 各商品成本里出现过的 coins 键，去重、保序）。 */
    public static Set<ResourceLocation> getAcceptedCurrencies() {
        LinkedHashSet<ResourceLocation> set = new LinkedHashSet<>();
        for (ShopEntry entry : getEntries()) {
            set.addAll(entry.getCost().coins.keySet());
        }
        return set;
    }

    /** 获取所有分类名（= 商品清单里出现过的 category 全名，去重、保序；隐藏商品不计入）。 */
    public static List<String> getCategories() {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (ShopEntry entry : getEntries()) {
            if (entry.isStructurallyValid() && !entry.isHidden()) {
                set.add(entry.getCategory());
            }
        }
        return new ArrayList<>(set);
    }

    /** 按跳转别名查找条目（含隐藏商品，供「跳转」入口解析目标用；未找到返回 null）。 */
    public static ShopEntry findByLinkKey(String key) {
        return snapshot().findByLinkKey(key);
    }

    // ==================== 两级分类：主/子（约定 category = "主" 或 "主/子"）====================

    /** 取分类主名（"主/子" → "主"；无「/」→ 原样）。 */
    public static String catTop(String category) {
        if (category == null) return ShopEntry.DEFAULT_CATEGORY;
        int i = category.indexOf('/');
        return i < 0 ? category : category.substring(0, i);
    }

    /** 取分类子名（"主/子" → "子"；无「/」→ 空串）。 */
    public static String catSub(String category) {
        if (category == null) return "";
        int i = category.indexOf('/');
        return i < 0 ? "" : category.substring(i + 1);
    }

    /** 所有主分类（去重保序；隐藏商品不计入）。 */
    public static List<String> getTopCategories() {
        return snapshot().topCategories();
    }

    /** 某主分类下的子分类（去重保序，仅非空子名；隐藏商品不计入）。 */
    public static List<String> getSubCategories(String top) {
        return snapshot().subCategories(top);
    }

    /**
     * 取某「主 + 子」分组下的有效商品；sub 为空 → 该主分类全部（含无子的与各子的）。
     * 隐藏商品（{@link ShopEntry#isHidden}）不在此列，只能被其他条目的跳转入口（{@link ShopEntry#getLinkTo}）直达。
     */
    public static List<ShopEntry> getEntriesOfGroup(String top, String sub) {
        return snapshot().entriesOfGroup(top, sub);
    }

    /** 按分类分组的有效商品（保序；隐藏商品不计入）。 */
    public static Map<String, List<ShopEntry>> getEntriesByCategory() {
        LinkedHashMap<String, List<ShopEntry>> map = new LinkedHashMap<>();
        for (ShopEntry entry : getEntries()) {
            if (!entry.isStructurallyValid() || entry.isHidden()) continue;
            map.computeIfAbsent(entry.getCategory(), k -> new ArrayList<>()).add(entry);
        }
        return map;
    }

    /** 取某分类下的有效商品（隐藏商品不计入）。 */
    public static List<ShopEntry> getEntriesOf(String category) {
        List<ShopEntry> result = new ArrayList<>();
        for (ShopEntry entry : getEntries()) {
            if (entry.isStructurallyValid() && !entry.isHidden() && entry.getCategory().equals(category)) {
                result.add(entry);
            }
        }
        return result;
    }

    // ==================== 编辑模式：增 / 删 / 持久化 ====================

    /** 新增一个商品条目并写回 shop.json。 */
    public static synchronized void addEntry(ShopEntry entry) {
        if (entry == null) return;
        List<ShopEntry> updated = new ArrayList<>(getEntries());
        updated.add(entry);
        publish(updated);
        save();
    }

    // 误删防护：记住最近一次被删除的条目 + 原始位置，30 秒内可用 undoLastRemove() 撤销一次
    private static ShopEntry lastRemovedEntry;
    private static int lastRemovedIndex = -1;
    private static long lastRemovedAtMs;
    private static final long UNDO_WINDOW_MS = 30_000L;

    /** 删除一个商品条目（按对象引用）并写回 shop.json；返回是否删除成功。 */
    public static synchronized boolean removeEntry(ShopEntry entry) {
        if (entry == null) return false;
        List<ShopEntry> updated = new ArrayList<>(getEntries());
        int idx = updated.indexOf(entry);
        boolean removed = updated.remove(entry);
        if (removed) {
            lastRemovedEntry = entry;
            lastRemovedIndex = idx;
            lastRemovedAtMs = System.currentTimeMillis();
            publish(updated);
            save();
        }
        return removed;
    }

    /** 撤销最近一次删除（30 秒内有效，且只能撤销一次）；恢复成功返回该条目，否则 null（已超时/已撤销过）。 */
    public static synchronized ShopEntry undoLastRemove() {
        if (lastRemovedEntry == null) return null;
        if (System.currentTimeMillis() - lastRemovedAtMs > UNDO_WINDOW_MS) {
            lastRemovedEntry = null;
            return null;
        }
        List<ShopEntry> updated = new ArrayList<>(getEntries());
        ShopEntry restored = lastRemovedEntry;
        int idx = Math.max(0, Math.min(lastRemovedIndex, updated.size()));
        updated.add(idx, restored);
        publish(updated);
        save();
        lastRemovedEntry = null;
        lastRemovedIndex = -1;
        return restored;
    }

    /** 用新条目替换旧条目并写回；返回是否替换成功。 */
    public static synchronized boolean replaceEntry(ShopEntry oldEntry, ShopEntry newEntry) {
        if (oldEntry == null || newEntry == null) return false;
        List<ShopEntry> updated = new ArrayList<>(getEntries());
        int idx = updated.indexOf(oldEntry);
        if (idx < 0) return false;
        updated.set(idx, newEntry);
        publish(updated);
        save();
        return true;
    }

    private static void publish(List<ShopEntry> entries) {
        ShopCatalogSnapshot built = ShopCatalogSnapshot.build(nextRevision++, entries);
        snapshot = built;
        loaded = true;
    }

    private static final com.google.gson.Gson GSON =
            new com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** 把当前内存中的商品清单写回 shop.json（Gson 规范序列化，NBT 以 SNBT 字符串存 "nbt" 字段）。 */
    public static synchronized void save() {
        try {
            new File(CONFIG_DIR).mkdirs();
            JsonObject root = new JsonObject();
            JsonArray arr = new JsonArray();
            List<ShopEntry> entries = snapshot().entries();
            for (ShopEntry e : entries) {
                arr.add(ShopEntryJsonCodec.toJson(e));
            }
            root.add("entries", arr);
            try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(SHOP_FILE), StandardCharsets.UTF_8)) {
                w.write(GSON.toJson(root));
            }
            GTDishanhaiMod.LOGGER.info("[Shop] 已保存 {} 个商品到 shop.json", entries.size());
        } catch (Exception e) {
            GTDishanhaiMod.LOGGER.warn("[Shop] 保存 shop.json 失败: {}", e.getMessage());
        }
    }

    /** 从磁盘重新加载商品清单；文件缺失时生成默认文件。 */
    public static synchronized void reload() {
        if (!SHOP_FILE.exists()) {
            writeDefault();
        }
        List<ShopEntry> parsedEntries = new ArrayList<>();
        // 必须显式 UTF-8：FileReader 用系统默认编码（Windows 为 GBK），会把 UTF-8 中文读成乱码
        try (java.io.Reader r = new java.io.InputStreamReader(
                new java.io.FileInputStream(SHOP_FILE), StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
            JsonArray arr = root.has("entries") ? root.getAsJsonArray("entries") : new JsonArray();
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                ShopEntry entry = ShopEntryJsonCodec.fromJson(o);
                if (entry != null) {
                    parsedEntries.add(entry);
                }
            }
            publish(parsedEntries);
            GTDishanhaiMod.LOGGER.info("[Shop] 已加载 {} 个商品", parsedEntries.size());
        } catch (Exception e) {
            loaded = true; // 保留最后一个完整快照，避免每次读取都重复冲击损坏文件
            GTDishanhaiMod.LOGGER.warn("[Shop] 读取 shop.json 失败: {}", e.getMessage());
        }
    }

    private static ShopEntry parseEntry(JsonObject o) {
        try {
            String goods = o.get("goods").getAsString();
            int count = o.has("count") ? o.get("count").getAsInt() : 1;
            String category = o.has("category") ? o.get("category").getAsString() : ShopEntry.DEFAULT_CATEGORY;
            String description = o.has("description") ? o.get("description").getAsString() : "";
            long limit = o.has("limit") ? o.get("limit").getAsLong() : -1L; // 剩余次数，缺省 -1 = 不限
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
            // 成本：优先新格式 cost 对象；否则回退旧 currency+price 单币迁移
            ShopCost cost;
            if (o.has("cost") && o.get("cost").isJsonObject()) {
                cost = parseCost(o.getAsJsonObject("cost"));
            } else {
                String currency = o.has("currency") ? o.get("currency").getAsString() : "dishanhai:dog_coins";
                int price = o.has("price") ? o.get("price").getAsInt() : 1;
                cost = ShopCost.singleCoin(new ResourceLocation(currency), price);
            }
            // 商品清单：优先新格式 goodsList（组合商品，≥2 项）；否则回退单商品 goods/count/nbt（旧格式 100% 兼容）
            List<ShopEntry.GoodsStack> goodsList;
            if (o.has("goodsList") && o.get("goodsList").isJsonArray()) {
                goodsList = parseGoodsList(o.getAsJsonArray("goodsList"));
                if (goodsList.isEmpty()) throw new RuntimeException("goodsList 解析为空");
            } else {
                goodsList = List.of(ShopEntry.GoodsStack.of(new ResourceLocation(goods), count, nbt));
            }
            List<ShopEntry.DisplayIcon> icons = o.has("icons") && o.get("icons").isJsonArray()
                    ? parseIcons(o.getAsJsonArray("icons")) : null;
            ShopEntry.RewardMode rewardMode = ShopEntry.RewardMode.NONE;
            if (o.has("rewardMode")) {
                try { rewardMode = ShopEntry.RewardMode.valueOf(o.get("rewardMode").getAsString()); }
                catch (Exception ignored) {}
            }
            List<ShopEntry.RewardOption> rewardPool = o.has("rewardPool") && o.get("rewardPool").isJsonArray()
                    ? parseRewardPool(o.getAsJsonArray("rewardPool")) : null;
            boolean hidden = o.has("hidden") && o.get("hidden").getAsBoolean();
            String linkKey = o.has("linkKey") ? o.get("linkKey").getAsString() : null;
            String linkTo = o.has("linkTo") ? o.get("linkTo").getAsString() : null;
            String displayName = o.has("displayName") ? o.get("displayName").getAsString() : null;
            String ftbqTableId = o.has("ftbqTableId") ? o.get("ftbqTableId").getAsString() : null;
            ShopEntry.RewardMode ftbqSubMode = ShopEntry.RewardMode.RANDOM;
            if (o.has("ftbqSubMode")) {
                try { ftbqSubMode = ShopEntry.RewardMode.valueOf(o.get("ftbqSubMode").getAsString()); }
                catch (Exception ignored) {}
            }
            ShopEntry.TradeMode tradeMode = ShopEntry.TradeMode.BOTH;
            if (o.has("tradeMode")) {
                try { tradeMode = ShopEntry.TradeMode.valueOf(o.get("tradeMode").getAsString()); }
                catch (Exception ignored) {}
            }
            // 周期限购（每玩家独立计数，见 ShopPeriodLimiter）：两个字段必须同时存在才生效，缺省 -1/-1 = 不启用
            long periodTicks = o.has("periodTicks") ? o.get("periodTicks").getAsLong() : -1L;
            long periodLimit = o.has("periodLimit") ? o.get("periodLimit").getAsLong() : -1L;
            return new ShopEntry(goodsList, category, cost, description, limit,
                    icons, rewardMode, rewardPool, hidden, linkKey, linkTo, displayName, ftbqTableId, ftbqSubMode, tradeMode,
                    periodTicks, periodLimit);
        } catch (Exception e) {
            GTDishanhaiMod.LOGGER.warn("[Shop] 跳过非法商品条目: {}", e.getMessage());
            return null;
        }
    }

    /** 序列化多元成本：{ "spark":"N", "coins":{id:"N"}, "items":[{id,fluid,count}] }。 */
    private static JsonObject costToJson(ShopCost cost) {
        JsonObject co = new JsonObject();
        co.addProperty("spark", cost.spark.toString());
        JsonObject coins = new JsonObject();
        for (Map.Entry<ResourceLocation, java.math.BigInteger> e : cost.coins.entrySet()) {
            coins.addProperty(e.getKey().toString(), e.getValue().toString());
        }
        co.add("coins", coins);
        JsonArray items = new JsonArray();
        for (ExchangeEntry.Ingredient in : cost.physical) {
            JsonObject io = new JsonObject();
            io.addProperty("id", in.id == null ? "" : in.id.toString());
            io.addProperty("fluid", in.isFluid);
            io.addProperty("count", in.count);
            if (in.hasNbt()) io.addProperty("nbt", in.nbt().toString()); // 精确 NBT 匹配（仅物品；流体不支持）
            items.add(io);
        }
        co.add("items", items);
        return co;
    }

    /** 解析多元成本（缺失/空 → 全空成本）。 */
    private static ShopCost parseCost(JsonObject co) {
        java.math.BigInteger spark = java.math.BigInteger.ZERO;
        LinkedHashMap<ResourceLocation, java.math.BigInteger> coins = new LinkedHashMap<>();
        List<ExchangeEntry.Ingredient> physical = new ArrayList<>();
        if (co != null) {
            if (co.has("spark")) {
                try { spark = new java.math.BigInteger(co.get("spark").getAsString()); } catch (Exception ignored) {}
            }
            if (co.has("coins") && co.get("coins").isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : co.getAsJsonObject("coins").entrySet()) {
                    try {
                        java.math.BigInteger amt = new java.math.BigInteger(e.getValue().getAsString());
                        if (amt.signum() > 0) coins.put(new ResourceLocation(e.getKey()), amt);
                    } catch (Exception ignored) {}
                }
            }
            if (co.has("items")) {
                for (JsonElement el : co.getAsJsonArray("items")) {
                    if (!el.isJsonObject()) continue;
                    JsonObject io = el.getAsJsonObject();
                    String iid = io.has("id") ? io.get("id").getAsString() : "";
                    if (iid.isEmpty()) continue;
                    boolean fluid = io.has("fluid") && io.get("fluid").getAsBoolean();
                    long c = io.has("count") ? io.get("count").getAsLong() : 1L;
                    net.minecraft.nbt.CompoundTag inNbt = null;
                    if (io.has("nbt")) {
                        try {
                            String snbt = io.get("nbt").getAsString();
                            if (snbt != null && !snbt.isBlank()) inNbt = net.minecraft.nbt.TagParser.parseTag(snbt);
                        } catch (Exception ignored) {}
                    }
                    physical.add(new ExchangeEntry.Ingredient(new ResourceLocation(iid), fluid, c, inNbt));
                }
            }
        }
        return new ShopCost(spark, coins, physical);
    }

    /** 序列化商品清单（组合商品专用）：[{ "id":"...", "count":N, "nbt":"..." }]，顺序即购买时交付顺序。 */
    private static JsonArray goodsListToJson(List<ShopEntry.GoodsStack> list) {
        JsonArray arr = new JsonArray();
        for (ShopEntry.GoodsStack gs : list) {
            if (gs == null || gs.id() == null) continue;
            JsonObject io = new JsonObject();
            io.addProperty("id", gs.id().toString());
            io.addProperty("count", gs.count());
            net.minecraft.nbt.CompoundTag gnbt = gs.nbt();
            if (gnbt != null && !gnbt.isEmpty()) io.addProperty("nbt", gnbt.toString());
            arr.add(io);
        }
        return arr;
    }

    /** 解析商品清单（非法/缺失 id 的条目跳过，不影响其余项；解析失败的 NBT 按无 NBT 处理）。 */
    private static List<ShopEntry.GoodsStack> parseGoodsList(JsonArray arr) {
        List<ShopEntry.GoodsStack> list = new ArrayList<>();
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject io = el.getAsJsonObject();
            if (!io.has("id")) continue;
            ResourceLocation id = new ResourceLocation(io.get("id").getAsString());
            int count = io.has("count") ? Math.max(1, io.get("count").getAsInt()) : 1;
            net.minecraft.nbt.CompoundTag gnbt = null;
            if (io.has("nbt")) {
                try {
                    String snbt = io.get("nbt").getAsString();
                    if (snbt != null && !snbt.isBlank()) gnbt = net.minecraft.nbt.TagParser.parseTag(snbt);
                } catch (Exception ignored) {}
            }
            list.add(ShopEntry.GoodsStack.of(id, count, gnbt));
        }
        return list;
    }

    /**
     * 序列化自定义显示图标：物品用 { "id":"...", "nbt":"..." }，贴图用 { "texture":"..." }。
     * 顺序即主图标+角标顺序，最多 1 主 4 附属由渲染端限定。
     */
    private static JsonArray iconsToJson(List<ShopEntry.DisplayIcon> icons) {
        JsonArray arr = new JsonArray();
        for (ShopEntry.DisplayIcon d : icons) {
            if (d == null) continue;
            JsonObject io = new JsonObject();
            if (d.isTexture()) {
                io.addProperty("texture", d.texture().toString());
            } else {
                net.minecraft.world.item.ItemStack st = d.item();
                if (st == null || st.isEmpty()) continue;
                ResourceLocation id = ForgeRegistries.ITEMS.getKey(st.getItem());
                if (id == null) continue;
                io.addProperty("id", id.toString());
                if (st.hasTag()) io.addProperty("nbt", st.getTag().toString());
            }
            arr.add(io);
        }
        return arr;
    }

    /** 解析自定义显示图标（非法/缺失物品的条目跳过，不影响其余项）。 */
    private static List<ShopEntry.DisplayIcon> parseIcons(JsonArray arr) {
        List<ShopEntry.DisplayIcon> icons = new ArrayList<>();
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject io = el.getAsJsonObject();
            if (io.has("texture")) {
                icons.add(ShopEntry.DisplayIcon.ofTexture(new ResourceLocation(io.get("texture").getAsString())));
                continue;
            }
            if (!io.has("id")) continue;
            var item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(io.get("id").getAsString()));
            if (item == null) continue;
            net.minecraft.world.item.ItemStack st = new net.minecraft.world.item.ItemStack(item);
            if (io.has("nbt")) {
                try { st.setTag(net.minecraft.nbt.TagParser.parseTag(io.get("nbt").getAsString())); } catch (Exception ignored) {}
            }
            icons.add(ShopEntry.DisplayIcon.ofItem(st));
        }
        return icons;
    }

    /**
     * 序列化奖励池：[{ "id":"...", "count":N, "nbt":"...", "weight":N, "min":N, "max":N }]。
     * weight 供 RANDOM 模式按占比加权抽取；min/max 为每次交付的独立随机数量区间（相等即固定数量）。
     */
    private static JsonArray rewardPoolToJson(List<ShopEntry.RewardOption> pool) {
        JsonArray arr = new JsonArray();
        for (ShopEntry.RewardOption opt : pool) {
            if (opt == null) continue;
            net.minecraft.world.item.ItemStack st = opt.item();
            if (st == null || st.isEmpty()) continue;
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(st.getItem());
            if (id == null) continue;
            JsonObject io = new JsonObject();
            io.addProperty("id", id.toString());
            io.addProperty("count", st.getCount());
            if (st.hasTag()) io.addProperty("nbt", st.getTag().toString());
            io.addProperty("weight", opt.weight());
            io.addProperty("min", opt.minCount());
            io.addProperty("max", opt.maxCount());
            arr.add(io);
        }
        return arr;
    }

    /** 解析奖励池（非法/缺失物品的条目跳过，不影响其余项）。 */
    private static List<ShopEntry.RewardOption> parseRewardPool(JsonArray arr) {
        List<ShopEntry.RewardOption> pool = new ArrayList<>();
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject io = el.getAsJsonObject();
            if (!io.has("id")) continue;
            var item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(io.get("id").getAsString()));
            if (item == null) continue;
            int count = io.has("count") ? Math.max(1, io.get("count").getAsInt()) : 1;
            net.minecraft.world.item.ItemStack st = new net.minecraft.world.item.ItemStack(item, count);
            if (io.has("nbt")) {
                try { st.setTag(net.minecraft.nbt.TagParser.parseTag(io.get("nbt").getAsString())); } catch (Exception ignored) {}
            }
            int weight = io.has("weight") ? Math.max(1, io.get("weight").getAsInt()) : 1;
            int min = io.has("min") ? Math.max(1, io.get("min").getAsInt()) : count;
            int max = io.has("max") ? Math.max(1, io.get("max").getAsInt()) : count;
            pool.add(ShopEntry.RewardOption.of(st, weight, min, max));
        }
        return pool;
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
