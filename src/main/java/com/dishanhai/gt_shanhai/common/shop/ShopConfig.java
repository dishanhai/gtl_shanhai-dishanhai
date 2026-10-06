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
import java.util.Collection;
import java.util.Comparator;
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

    /** 按 stableId 解析当前快照中的条目，去重并保持请求顺序。 */
    public static List<ShopEntry> resolveStableIds(Collection<String> stableIds) {
        if (stableIds == null || stableIds.isEmpty()) return List.of();
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String stableId : stableIds) {
            if (stableId != null && !stableId.isBlank()) unique.add(stableId);
        }
        List<ShopEntry> result = new ArrayList<>(unique.size());
        for (String stableId : unique) {
            ShopEntry entry = resolveByStableId(stableId);
            if (entry != null) result.add(entry);
        }
        return result;
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

    // 误删防护：记住最近一次被删除的条目集合 + 原始位置，30 秒内可用 undoLastRemove() 撤销一次
    private record RemovedEntry(ShopEntry entry, int index) {}
    private static List<RemovedEntry> lastRemovedEntries = List.of();
    private static long lastRemovedAtMs;
    private static final long UNDO_WINDOW_MS = 30_000L;

    /** 删除一个商品条目（按对象引用）并写回 shop.json；返回是否删除成功。 */
    public static synchronized boolean removeEntry(ShopEntry entry) {
        return removeEntries(entry == null ? List.of() : List.of(entry)) > 0;
    }

    /** 一次删除多个商品条目；按当前快照原子发布，供批量菜单使用。 */
    public static synchronized int removeEntries(Collection<ShopEntry> targets) {
        if (targets == null || targets.isEmpty()) return 0;
        List<ShopEntry> updated = new ArrayList<>(getEntries());
        Set<ShopEntry> wanted = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (ShopEntry target : targets) if (target != null) wanted.add(target);
        if (wanted.isEmpty()) return 0;
        List<RemovedEntry> removed = new ArrayList<>();
        for (int i = 0; i < updated.size(); i++) {
            ShopEntry entry = updated.get(i);
            if (wanted.contains(entry)) removed.add(new RemovedEntry(entry, i));
        }
        if (!removed.isEmpty()) {
            updated.removeIf(wanted::contains);
            lastRemovedEntries = List.copyOf(removed);
            lastRemovedAtMs = System.currentTimeMillis();
            publish(updated);
            save();
        }
        return removed.size();
    }

    /** 按 stableId 一次删除多个条目；找不到的 ID 静默跳过。 */
    public static synchronized int removeEntriesByStableIds(Collection<String> stableIds) {
        return removeEntries(resolveStableIds(stableIds));
    }

    /** 撤销最近一次删除（30 秒内有效，且只能撤销一次）；恢复成功返回首个条目，否则 null。 */
    public static synchronized ShopEntry undoLastRemove() {
        if (lastRemovedEntries.isEmpty()) return null;
        if (System.currentTimeMillis() - lastRemovedAtMs > UNDO_WINDOW_MS) {
            lastRemovedEntries = List.of();
            return null;
        }
        List<ShopEntry> updated = new ArrayList<>(getEntries());
        List<RemovedEntry> restoredEntries = new ArrayList<>(lastRemovedEntries);
        restoredEntries.sort(Comparator.comparingInt(RemovedEntry::index));
        int inserted = 0;
        for (RemovedEntry removed : restoredEntries) {
            if (updated.contains(removed.entry())) continue;
            int idx = Math.max(0, Math.min(removed.index() + inserted, updated.size()));
            updated.add(idx, removed.entry());
            inserted++;
        }
        ShopEntry restored = restoredEntries.isEmpty() ? null : restoredEntries.get(0).entry();
        publish(updated);
        save();
        lastRemovedEntries = List.of();
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

    /** 批量切换隐藏状态；一次发布目录，避免连续单项包造成 revision 冲突。 */
    public static synchronized int batchSetHidden(Collection<String> stableIds, boolean hidden) {
        List<ShopEntry> targets = resolveStableIds(stableIds);
        if (targets.isEmpty()) return 0;
        Set<String> wanted = new LinkedHashSet<>();
        for (ShopEntry entry : targets) wanted.add(entry.getStableId());
        List<ShopEntry> updated = new ArrayList<>(getEntries());
        int changed = 0;
        for (int i = 0; i < updated.size(); i++) {
            ShopEntry old = updated.get(i);
            if (!wanted.contains(old.getStableId()) || old.isHidden() == hidden) continue;
            updated.set(i, copyEntry(old, old.getCategory(), hidden));
            changed++;
        }
        if (changed > 0) {
            publish(updated);
            save();
        }
        return changed;
    }

    /** 批量快速分组；目标分类为空时回退到默认分类。 */
    public static synchronized int batchRegroup(Collection<String> stableIds, String category) {
        List<ShopEntry> targets = resolveStableIds(stableIds);
        if (targets.isEmpty()) return 0;
        String nextCategory = category == null || category.isBlank() ? ShopEntry.DEFAULT_CATEGORY : category.trim();
        Set<String> wanted = new LinkedHashSet<>();
        for (ShopEntry entry : targets) wanted.add(entry.getStableId());
        List<ShopEntry> updated = new ArrayList<>(getEntries());
        int changed = 0;
        for (int i = 0; i < updated.size(); i++) {
            ShopEntry old = updated.get(i);
            if (!wanted.contains(old.getStableId()) || nextCategory.equals(old.getCategory())) continue;
            updated.set(i, copyEntry(old, nextCategory, old.isHidden()));
            changed++;
        }
        if (changed > 0) {
            publish(updated);
            save();
        }
        return changed;
    }

    /** 批量设置交易方向；一次发布目录，保留每个条目的其他字段与 stableId。 */
    public static synchronized int batchSetTradeMode(Collection<String> stableIds, ShopEntry.TradeMode tradeMode) {
        List<ShopEntry> targets = resolveStableIds(stableIds);
        if (targets.isEmpty()) return 0;
        ShopEntry.TradeMode nextMode = tradeMode == null ? ShopEntry.TradeMode.BOTH : tradeMode;
        Set<String> wanted = new LinkedHashSet<>();
        for (ShopEntry entry : targets) wanted.add(entry.getStableId());
        List<ShopEntry> updated = new ArrayList<>(getEntries());
        int changed = 0;
        for (int i = 0; i < updated.size(); i++) {
            ShopEntry old = updated.get(i);
            if (!wanted.contains(old.getStableId()) || old.getTradeMode() == nextMode) continue;
            updated.set(i, copyEntry(old, old.getCategory(), old.isHidden(), nextMode));
            changed++;
        }
        if (changed > 0) {
            publish(updated);
            save();
        }
        return changed;
    }

    /**
     * 批量排序：同分类内每个选中条目移动一步，置顶则把选中条目移到该分类最前；
     * 其他分类的相对物理顺序不变。
     */
    public static synchronized int batchMove(Collection<String> stableIds, int direction) {
        List<ShopEntry> targets = resolveStableIds(stableIds);
        if (targets.isEmpty() || (direction != -1 && direction != 0 && direction != 1)) return 0;
        Set<String> wanted = new LinkedHashSet<>();
        for (ShopEntry entry : targets) wanted.add(entry.getStableId());
        List<ShopEntry> updated = new ArrayList<>(getEntries());
        int changed = 0;
        LinkedHashSet<String> categories = new LinkedHashSet<>();
        for (ShopEntry entry : targets) categories.add(entry.getCategory());
        for (String category : categories) {
            List<Integer> positions = new ArrayList<>();
            List<ShopEntry> local = new ArrayList<>();
            for (int i = 0; i < updated.size(); i++) {
                ShopEntry entry = updated.get(i);
                if (category.equals(entry.getCategory())) {
                    positions.add(i);
                    local.add(entry);
                }
            }
            if (direction < 0) {
                for (int i = 1; i < local.size(); i++) {
                    if (wanted.contains(local.get(i).getStableId())
                            && !wanted.contains(local.get(i - 1).getStableId())) {
                        Collections.swap(local, i, i - 1);
                        changed++;
                    }
                }
            } else if (direction > 0) {
                for (int i = local.size() - 2; i >= 0; i--) {
                    if (wanted.contains(local.get(i).getStableId())
                            && !wanted.contains(local.get(i + 1).getStableId())) {
                        Collections.swap(local, i, i + 1);
                        changed++;
                    }
                }
            } else {
                List<ShopEntry> selectedPart = new ArrayList<>();
                List<ShopEntry> otherPart = new ArrayList<>();
                for (ShopEntry entry : local) {
                    if (wanted.contains(entry.getStableId())) selectedPart.add(entry);
                    else otherPart.add(entry);
                }
                List<ShopEntry> reordered = new ArrayList<>(local.size());
                reordered.addAll(selectedPart);
                reordered.addAll(otherPart);
                if (!reordered.equals(local)) {
                    local = reordered;
                    changed += selectedPart.size();
                }
            }
            for (int i = 0; i < positions.size(); i++) updated.set(positions.get(i), local.get(i));
        }
        if (changed > 0) {
            publish(updated);
            save();
        }
        return changed;
    }

    /** 保留完整商品元数据复制一份，仅替换分类/隐藏状态。 */
    private static ShopEntry copyEntry(ShopEntry old, String category, boolean hidden) {
        return copyEntry(old, category, hidden, old.getTradeMode());
    }

    /** 保留完整商品元数据复制一份，按需替换分类/隐藏状态/交易方向。 */
    private static ShopEntry copyEntry(ShopEntry old, String category, boolean hidden, ShopEntry.TradeMode tradeMode) {
        ShopEntry copy = new ShopEntry(old.getGoodsList(), category, old.getCost(), old.getDescription(),
                old.getServerUses(), old.getDisplayIcons(), old.getRewardMode(), old.getRewardPool(),
                hidden, old.getLinkKey(), old.getLinkTo(), old.getDisplayName(), old.getFtbqTableId(),
                old.getFtbqSubMode(), tradeMode, old.getPeriodTicks(), old.getPeriodLimit(),
                old.getPrerequisiteQuestId(), old.getStableId(), old.getDiscountPercent(),
                old.getDiscountStartMs(), old.getDiscountEndMs(), old.getSubmissionItems());
        copy.overrideRemainingUses(old.getRemainingUses());
        return copy;
    }

    // 撤销上一次排序（前移/后移/置顶）：记住移动前"紧邻在它前面的那个条目"（身份锚点，不是数字下标），
    // 30 秒内可用 undoLastMove() 挪回去；只保留最近一次，跟 lastRemovedEntries 的删除撤销是两套独立状态。
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
     * 且世界已可用時呼叫（{@code ServerStartingEvent} / {@code /商店 reload}）。
     */
    public static synchronized void syncLimitsFromSave(net.minecraft.server.MinecraftServer server) {
        if (server == null) return;
        ShopLimitSavedData data = ShopLimitSavedData.get(server);
        for (ShopEntry entry : snapshot().entries()) {
            data.applyTo(entry);
        }
    }

    /** 僅重置當前存檔，重新發布目錄版本讓客戶端清除已耗盡的快取；不寫 shop.json。 */
    public static synchronized int resetSaveUses(net.minecraft.server.MinecraftServer server) {
        ShopLimitSavedData data = ShopLimitSavedData.get(server);
        List<ShopEntry> entries = new ArrayList<>(getEntries());
        for (ShopEntry entry : entries) data.reset(entry);
        publish(entries);
        return entries.size();
    }

    /** 从磁盘重新加载商品清单；文件缺失时生成默认文件。 */
    public static synchronized void reload() {
        loadCategoryOrder(); // 先加载分类排序，publish() 建 manifest 时才能一并带上
        ShopStageConfig.reload();
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
