package com.dishanhai.gt_shanhai.common.shop;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.network.ShopCatalogManifestPacket;
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
    private static final File CATEGORY_ORDER_FILE = new File(CONFIG_DIR, "shop_category_order.json");

    private static volatile ShopCatalogSnapshot snapshot = ShopCatalogSnapshot.empty();
    private static volatile boolean loaded = false;
    private static long nextRevision = Math.max(1L, System.currentTimeMillis());
    /**
     * 分类页签显式排序（拖拽排序页签后落地，见 {@link #moveCategoryTo}）：key=父路径（"/"拼接，""=顶级页签自身），
     * value=该层已知分类的排序结果，缺失的分类按发现顺序追加在末尾（见客户端 ClientShopCatalog#applyOrder）。
     * 随 shop_category_order.json 持久化，独立于 shop.json（不影响商品本身的排序/存档）。
     */
    private static volatile Map<String, List<String>> categoryOrder = new LinkedHashMap<>();

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
        if (current.revision() != revision) return List.of();
        List<ShopCatalogEntryPayload> frozen = current.chunk(chunkId);
        int limit = Math.min(frozen.size(), ShopCatalogSnapshot.DEFAULT_MAX_CHUNK_ENTRIES);
        List<ShopCatalogEntryPayload> live = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            ShopCatalogEntryPayload payload = frozen.get(i);
            ShopEntry entry = current.resolve(payload.entryKey());
            if (entry != null) {
                live.add(new ShopCatalogEntryPayload(
                        payload.entryKey(), ShopEntryJsonCodec.toPayload(entry)));
            }
        }
        return List.copyOf(live);
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

    /** 按稳定身份 ID 查找条目（跨快照/跨重登有效，供购物车等场景解析当前 entryKey；未找到返回 null）。 */
    public static ShopEntry resolveByStableId(String stableId) {
        return snapshot().resolveByStableId(stableId);
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

    // 撤销上一次排序（前移/后移/置顶）：记住移动前"紧邻在它前面的那个条目"（身份锚点，不是数字下标），
    // 30 秒内可用 undoLastMove() 挪回去；只保留最近一次，跟 lastRemovedEntry 的删除撤销是两套独立状态。
    // 用身份锚点而不是原始下标的原因：撤销窗口内如果有别的增删/排序动作把列表整体挪了位，记死的下标会失效
    // （复原到错误位置甚至越界），身份锚点会跟着锚点条目本身重新定位，天然不受这些中间变更影响；
    // 锚点条目如果在窗口内被删掉了，退化成"补到列表最后一位"（保底，见 undoLastMove 注释）。
    private static ShopEntry lastMovedEntry;
    private static ShopEntry lastMovedAnchorEntry; // 移动前紧邻在 lastMovedEntry 前面的条目；null=移动前它就是全局第一个
    private static long lastMovedAtMs;
    private static final long UNDO_MOVE_WINDOW_MS = 30_000L;

    /**
     * 商品展示顺序 = shop.json 数组的物理顺序（全服唯一，非玩家个人视图）。前移/后移只跟同分类
     * （{@link ShopEntry#getCategory} 完全相同）的最近相邻条目交换位置，不打扰其他分类条目的相对顺序；
     * 已经是同分类首/尾时返回 false（无法再移）。direction: -1=前移，+1=后移。
     */
    public static synchronized boolean moveEntry(ShopEntry entry, int direction) {
        if (entry == null) return false;
        List<ShopEntry> updated = new ArrayList<>(getEntries());
        int idx = updated.indexOf(entry);
        if (idx < 0) return false;
        String cat = entry.getCategory();
        int swapIdx = -1;
        if (direction < 0) {
            for (int i = idx - 1; i >= 0; i--) {
                if (cat.equals(updated.get(i).getCategory())) { swapIdx = i; break; }
            }
        } else {
            for (int i = idx + 1; i < updated.size(); i++) {
                if (cat.equals(updated.get(i).getCategory())) { swapIdx = i; break; }
            }
        }
        if (swapIdx < 0) return false;
        ShopEntry anchor = idx > 0 ? updated.get(idx - 1) : null;
        Collections.swap(updated, idx, swapIdx);
        publish(updated);
        save();
        rememberMove(entry, anchor);
        return true;
    }

    /** 挪到同分类最前（其余分类条目的相对顺序不变）；已经是同分类第一个时返回 false。 */
    public static synchronized boolean moveEntryToTop(ShopEntry entry) {
        if (entry == null) return false;
        List<ShopEntry> updated = new ArrayList<>(getEntries());
        int idx = updated.indexOf(entry);
        if (idx < 0) return false;
        String cat = entry.getCategory();
        int firstIdx = -1;
        for (int i = 0; i < updated.size(); i++) {
            if (cat.equals(updated.get(i).getCategory())) { firstIdx = i; break; }
        }
        if (firstIdx < 0 || firstIdx >= idx) return false;
        ShopEntry anchor = idx > 0 ? updated.get(idx - 1) : null;
        updated.remove(idx);
        updated.add(firstIdx, entry);
        publish(updated);
        save();
        rememberMove(entry, anchor);
        return true;
    }

    /**
     * 把条目拖拽挪到同分类下的第 newLocalIndex 位（0-based，只数同分类，跟其余分类条目的相对顺序不变）：
     * 先把该条目从物理数组里摘掉，在"摘掉后"的同分类子序列里定位插入点，再插回物理数组——这样目标下标
     * 天然就是"去掉被拖条目本身之后"的语义，跟客户端 {@link com.dishanhai.gt_shanhai.client.gui.shop.ShopScreen}
     * 里拖拽换算下标时的处理（按下标是否大于原下标决定要不要 -1）完全对齐。落点跟原位置一致时返回 false。
     */
    public static synchronized boolean moveEntryToIndex(ShopEntry entry, int newLocalIndex) {
        if (entry == null) return false;
        List<ShopEntry> updated = new ArrayList<>(getEntries());
        int idx = updated.indexOf(entry);
        if (idx < 0) return false;
        String cat = entry.getCategory();
        List<Integer> localIndices = new ArrayList<>();
        for (int i = 0; i < updated.size(); i++) {
            if (cat.equals(updated.get(i).getCategory())) localIndices.add(i);
        }
        int oldLocal = localIndices.indexOf(idx);
        int clampedNew = Math.max(0, Math.min(newLocalIndex, localIndices.size() - 1));
        if (clampedNew == oldLocal) return false;

        ShopEntry anchor = idx > 0 ? updated.get(idx - 1) : null;
        updated.remove(idx);
        List<Integer> remaining = new ArrayList<>();
        for (int i = 0; i < updated.size(); i++) {
            if (cat.equals(updated.get(i).getCategory())) remaining.add(i);
        }
        int insertAt;
        if (remaining.isEmpty()) insertAt = updated.size();
        else if (clampedNew <= 0) insertAt = remaining.get(0);
        else if (clampedNew >= remaining.size()) insertAt = remaining.get(remaining.size() - 1) + 1;
        else insertAt = remaining.get(clampedNew);
        updated.add(insertAt, entry);
        publish(updated);
        save();
        rememberMove(entry, anchor);
        return true;
    }

    private static void rememberMove(ShopEntry entry, ShopEntry anchor) {
        lastMovedEntry = entry;
        lastMovedAnchorEntry = anchor;
        lastMovedAtMs = System.currentTimeMillis();
    }

    /**
     * 撤销最近一次排序操作（前移/后移/置顶通用，30 秒内有效，只能撤销一次）：把条目插回"紧邻在锚点条目
     * 后面"的位置——锚点是移动前紧邻在它前面的那个条目本身（身份，不是下标），窗口期内哪怕锚点自己也被
     * 挪了位，重新定位一次锚点当前位置就能跟着复原，不受这段时间内其他增删/排序动作影响。
     * 锚点为 null（原本就是全局第一个）→ 插回最前；锚点在窗口期内被删掉、找不到了 → 退化成追加到列表
     * 最后一位（保底；因为分类归属只看 category 字段不看物理位置，追加到最后天然等价于"排到本分类末尾"）。
     */
    public static synchronized boolean undoLastMove() {
        if (lastMovedEntry == null) return false;
        if (System.currentTimeMillis() - lastMovedAtMs > UNDO_MOVE_WINDOW_MS) {
            lastMovedEntry = null;
            lastMovedAnchorEntry = null;
            return false;
        }
        List<ShopEntry> updated = new ArrayList<>(getEntries());
        int curIdx = updated.indexOf(lastMovedEntry);
        if (curIdx < 0) {
            lastMovedEntry = null;
            lastMovedAnchorEntry = null;
            return false;
        }
        updated.remove(curIdx);
        if (lastMovedAnchorEntry == null) {
            updated.add(0, lastMovedEntry);
        } else {
            int anchorIdx = updated.indexOf(lastMovedAnchorEntry);
            if (anchorIdx < 0) {
                updated.add(lastMovedEntry); // 保底：锚点已不在，追加到最后
            } else {
                updated.add(anchorIdx + 1, lastMovedEntry);
            }
        }
        publish(updated);
        save();
        lastMovedEntry = null;
        lastMovedAnchorEntry = null;
        return true;
    }

    private static void publish(List<ShopEntry> entries) {
        ShopCatalogSnapshot built = ShopCatalogSnapshot.build(nextRevision++, entries, categoryOrder);
        snapshot = built;
        loaded = true;
        ShopCatalogManifestPacket.broadcast(built.manifest());
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

    /** 读取 shop_category_order.json（缺失 = 尚未有任何拖拽排序，留空 Map，全部按发现顺序显示）。 */
    private static synchronized void loadCategoryOrder() {
        categoryOrder = new LinkedHashMap<>();
        if (!CATEGORY_ORDER_FILE.exists()) return;
        try (java.io.Reader r = new java.io.InputStreamReader(
                new java.io.FileInputStream(CATEGORY_ORDER_FILE), StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                if (!e.getValue().isJsonArray()) continue;
                List<String> values = new ArrayList<>();
                for (JsonElement el : e.getValue().getAsJsonArray()) values.add(el.getAsString());
                categoryOrder.put(e.getKey(), values);
            }
        } catch (Exception e) {
            GTDishanhaiMod.LOGGER.warn("[Shop] 读取 shop_category_order.json 失败: {}", e.getMessage());
        }
    }

    private static synchronized void saveCategoryOrder() {
        try {
            new File(CONFIG_DIR).mkdirs();
            JsonObject root = new JsonObject();
            for (Map.Entry<String, List<String>> e : categoryOrder.entrySet()) {
                JsonArray arr = new JsonArray();
                for (String v : e.getValue()) arr.add(v);
                root.add(e.getKey(), arr);
            }
            try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(CATEGORY_ORDER_FILE), StandardCharsets.UTF_8)) {
                w.write(GSON.toJson(root));
            }
        } catch (Exception e) {
            GTDishanhaiMod.LOGGER.warn("[Shop] 保存 shop_category_order.json 失败: {}", e.getMessage());
        }
    }

    /** 某父路径下当前实际存在的分类（按发现顺序；隐藏商品不计入），parentPath="" = 顶级页签自身。 */
    private static List<String> discoveredCategoriesAt(String parentPath) {
        if (parentPath == null || parentPath.isEmpty()) return snapshot().topCategories();
        String[] parts = parentPath.split("/", 3);
        if (parts.length == 1) return snapshot().subCategories(parts[0]);
        if (parts.length == 2) return snapshot().subCategories2(parts[0], parts[1]);
        return snapshot().subCategories3(parts[0], parts[1], parts[2]);
    }

    /**
     * 把 parentPath 下的 category 页签拖拽挪到新下标（0..size，含末尾）：先取当前排序（已持久化的排序
     * 优先，否则用发现顺序打底），跟当前实际存在的分类做一次"求交集+补新增"的自愈合并（避免陈旧持久化
     * 数据跟 shop.json 最新分类脱节——分类被删了就跟着从排序表里消失，新出现的分类追加到末尾），
     * 再把 category 挪到目标位置、持久化、重新发布 manifest 广播给所有客户端。
     */
    public static synchronized boolean moveCategoryTo(String parentPath, String category, int newIndex) {
        if (category == null || category.isEmpty()) return false;
        String key = parentPath == null ? "" : parentPath;
        List<String> discovered = discoveredCategoriesAt(key);
        if (!discovered.contains(category)) return false;
        List<String> persisted = categoryOrder.get(key);
        List<String> working = new ArrayList<>();
        if (persisted != null) {
            for (String c : persisted) if (discovered.contains(c) && !working.contains(c)) working.add(c);
        }
        for (String c : discovered) if (!working.contains(c)) working.add(c); // 新分类兜底追加到末尾

        int oldIndex = working.indexOf(category);
        working.remove(oldIndex);
        int clamped = Math.max(0, Math.min(newIndex, working.size()));
        working.add(clamped, category);
        if (working.equals(persisted)) return false; // 落点和原位置一致，视为无变化

        categoryOrder.put(key, List.copyOf(working));
        saveCategoryOrder();
        publish(new ArrayList<>(getEntries())); // 商品本身不变，只是要把新排序塞进新 manifest 广播出去
        return true;
    }

    /**
     * 限购总量剩余次数按存档隔离（见 {@link ShopLimitSavedData}）：从当前存档回填/初始化每个限购
     * 商品的剩余次数——存档里已经记过账（这个存档消费过）就用存档的值覆盖 shop.json 解析出的值；
     * 存档里没有记录（全新存档，或这个存档第一次见到这个 stableId）就拿 shop.json 里的配置值当
     * 起始配额，顺带把它写进存档，后续这个存档就一直认自己的记录。须在 {@link #reload()} 之后、
     * 且 server 已可用时调用（服务端启动 {@code ServerAboutToStartEvent} / {@code /商店 reload} 命令）。
     */
    public static synchronized void syncLimitsFromSave(net.minecraft.server.MinecraftServer server) {
        if (server == null) return;
        ShopLimitSavedData data = ShopLimitSavedData.get(server);
        for (ShopEntry entry : snapshot().entries()) {
            if (!entry.isLimited()) continue;
            Long saved = data.get(entry.getStableId());
            if (saved != null) {
                entry.overrideRemainingUses(saved);
            } else {
                data.set(entry.getStableId(), entry.getRemainingUses());
            }
        }
    }

    /** 从磁盘重新加载商品清单；文件缺失时生成默认文件。 */
    public static synchronized void reload() {
        loadCategoryOrder(); // 先加载分类排序，publish() 建 manifest 时才能一并带上
        if (!SHOP_FILE.exists()) {
            writeDefault();
        }
        List<ShopEntry> parsedEntries = new ArrayList<>();
        boolean needsStableIdMigration = false;
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
                    if (!ShopEntryJsonCodec.hasStableId(o)) needsStableIdMigration = true;
                }
            }
            publish(parsedEntries);
            GTDishanhaiMod.LOGGER.info("[Shop] 已加载 {} 个商品", parsedEntries.size());
            // 旧 shop.json 缺 stableId 的条目在上面 fromJson 时已由 ShopEntry 构造器补发新 UUID，
            // 这里立刻写回磁盘固化，否则下次重启又会各生成一个新的，购物车等跨重登引用就全部失效。
            if (needsStableIdMigration) {
                save();
                GTDishanhaiMod.LOGGER.info("[Shop] 已为缺失 stableId 的旧商品条目补发身份并写回 shop.json");
            }
        } catch (Exception e) {
            loaded = true; // 保留最后一个完整快照，避免每次读取都重复冲击损坏文件
            GTDishanhaiMod.LOGGER.warn("[Shop] 读取 shop.json 失败: {}", e.getMessage());
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
