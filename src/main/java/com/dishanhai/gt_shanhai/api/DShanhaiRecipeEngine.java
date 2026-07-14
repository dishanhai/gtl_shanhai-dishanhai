package com.dishanhai.gt_shanhai.api;

import com.gregtechceu.gtceu.integration.kjs.recipe.GTRecipeSchema;
import dev.latvian.mods.kubejs.recipe.RecipeJS;
import dev.latvian.mods.kubejs.recipe.NamespaceFunction;
import dev.latvian.mods.kubejs.recipe.RecipeTypeFunction;
import dev.latvian.mods.kubejs.script.ScriptManager;
import dev.latvian.mods.kubejs.item.ItemStackJS;
import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.Scriptable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 山海配方引擎 — 动态查表 + 热缓存 + 并行预处理。
 *
 * <p>JS 用法:
 * <pre>
 * // 预热缓存（可选，在 ServerEvents.recipes 最前面调一次）
 * DShanhaiRecipeEngine.precache([recipeArray1, recipeArray2, ...]);
 * // 注册配方
     * DShanhaiRecipeEngine.safeAddRecipe(gtr, r);
 * // 查看缓存统计
 * DShanhaiRecipeEngine.printStats();
 * </pre>
 */
public class DShanhaiRecipeEngine {

    @FunctionalInterface
    private interface FieldHandler { void handle(Object machine, Object value); }

    // ====== 热缓存 ======
    private static final ConcurrentHashMap<String, net.minecraft.world.item.ItemStack> ITEM_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, com.lowdragmc.lowdraglib.side.fluid.FluidStack> FLUID_ID_CACHE = new ConcurrentHashMap<>();
    // 配方类型 → 该类型全部配方 的缓存。getRecipesOfType 遍历 GTRecipeType 的 lookup 树成本较高，
    // 故按类型 memoize。配方随世界加载确定，客户端每次 RecipesUpdatedEvent 后须 clearRecipeCache() 失效，
    // 否则缓存里的 GTRecipe 会指向旧 RecipeManager。
    private static final ConcurrentHashMap<String, java.util.List<com.gregtechceu.gtceu.api.recipe.GTRecipe>> RECIPES_OF_TYPE_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, TimerEntry> TIMERS = new ConcurrentHashMap<>();
    private static final AtomicLong CACHE_HIT = new AtomicLong(0);
    private static final AtomicLong CACHE_MISS = new AtomicLong(0);
    private static final AtomicLong TIMER_ID = new AtomicLong(0);
    private static final AtomicLong ERROR_ID = new AtomicLong(0);
    private static final AtomicLong RECEIPT_ID = new AtomicLong(0);
    private static final AtomicLong RECIPE_TOTAL = new AtomicLong(0);
    private static final AtomicLong RECIPE_SUCCESS = new AtomicLong(0);
    private static final AtomicLong RECIPE_FAILED = new AtomicLong(0);
    private static final AtomicLong RECIPE_DISABLED = new AtomicLong(0);
    private static final int MAX_ERROR_ENTRIES = 512;
    private static volatile Map<String, Object> LAST_RECEIPT = Collections.emptyMap();
    private static final Map<String, TypeStats> RECIPE_TYPE_STATS = Collections.synchronizedMap(new LinkedHashMap<>());
    // 配方ID去重记录（按 recipeId 全局去重，随 resetRecipeStats() 一起在每次 reload 时清空）
    private static final Set<String> REGISTERED_RECIPE_IDS = ConcurrentHashMap.newKeySet();

    private static final class TimerEntry {
        final String name;
        final long startedNanos;

        TimerEntry(String name, long startedNanos) {
            this.name = name;
            this.startedNanos = startedNanos;
        }
    }

    private static final class TypeStats {
        long total;
        long success;
        long failed;
        long disabled;
    }

    // 占位符 — 物品缺失时用 dishanhai:zwf 替代
    private static volatile net.minecraft.world.item.ItemStack ZW_PLACEHOLDER;
    private static final Set<String> MISSING_ITEMS = ConcurrentHashMap.newKeySet();

    private static net.minecraft.world.item.ItemStack getZWPlaceholder() {
        if (ZW_PLACEHOLDER != null) return ZW_PLACEHOLDER.copy();
        var item = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(
                new net.minecraft.resources.ResourceLocation("dishanhai", "zwf"));
        if (item != null) ZW_PLACEHOLDER = new net.minecraft.world.item.ItemStack(item, 1);
        else ZW_PLACEHOLDER = net.minecraft.world.item.ItemStack.EMPTY;
        return ZW_PLACEHOLDER.copy();
    }

    // ====== 字段处理器查表 ======
    private static final Map<String, FieldHandler> HANDLERS = new LinkedHashMap<>();
    private static final Set<String> SKIP_KEYS = new HashSet<>(Arrays.asList(
            "id", "type", "name", "defaultEnabled", "triggerJsError",
            "dy_cell", "dynamicOutputs", "addDataid", "addData"
    ));

    static {
        HANDLERS.put("itemInputs",        DShanhaiRecipeEngine::applyItemInputs);
        HANDLERS.put("itemOutputs",       DShanhaiRecipeEngine::applyItemOutputs);
        HANDLERS.put("inputFluids",       DShanhaiRecipeEngine::applyInputFluids);
        HANDLERS.put("outputFluids",      DShanhaiRecipeEngine::applyOutputFluids);
        HANDLERS.put("fluidInputs",       DShanhaiRecipeEngine::applyInputFluids);
        HANDLERS.put("fluidOutputs",      DShanhaiRecipeEngine::applyOutputFluids);
        HANDLERS.put("notConsumable",     DShanhaiRecipeEngine::applyNotConsumable);
        HANDLERS.put("notConsumableFluid",DShanhaiRecipeEngine::applyNotConsumableFluid);
        HANDLERS.put("duration",          DShanhaiRecipeEngine::setDuration);
        HANDLERS.put("EUt",               DShanhaiRecipeEngine::setEUt);
        HANDLERS.put("circuit",           DShanhaiRecipeEngine::setCircuit);
        HANDLERS.put("blastFurnaceTemp",  DShanhaiRecipeEngine::setBlastTemp);
        HANDLERS.put("chancedOutputs",    (m, v) -> applyChanced(m, v, false));
        HANDLERS.put("chancedInputs",     (m, v) -> applyChanced(m, v, true));
        HANDLERS.put("stationResearch",   DShanhaiRecipeEngine::applyResearch);
        HANDLERS.put("conditions",        DShanhaiRecipeEngine::applyConditions);
        HANDLERS.put("notes",             DShanhaiRecipeEngine::applyNotes);
    }

    // ====== 热缓存预热（并行） ======

    /**
     * 并行提取所有配方数据中涉及的物品/流体 ID，填充缓存。
     * 在 ServerEvents.recipes 最前面调一次，后续 buildRecipe 直接命中缓存。
     *
     * @param arraysList JS 传入的数组的数组: [recipeArray1, recipeArray2, ...]
     */
    @SuppressWarnings("unchecked")
    public static void precache(Object arraysList) {
        if (!(arraysList instanceof List<?> listOfArrays)) return;
        // 收集所有待解析的字符串
        Set<String> itemIds = ConcurrentHashMap.newKeySet();
        Set<String> fluidIds = ConcurrentHashMap.newKeySet();
        for (Object arrObj : listOfArrays) {
            if (arrObj instanceof List<?> arr) {
                for (Object recipeObj : arr) {
                    if (recipeObj instanceof Map<?, ?> recipe) {
                        collectIds(recipe, "itemInputs", itemIds);
                        collectIds(recipe, "itemOutputs", itemIds);
                        collectIds(recipe, "notConsumable", itemIds);
                        collectFluidIds(recipe, "inputFluids", fluidIds);
                        collectFluidIds(recipe, "outputFluids", fluidIds);
                        collectFluidIds(recipe, "fluidInputs", fluidIds);
                        collectFluidIds(recipe, "fluidOutputs", fluidIds);
                        // chanced 里的物品
                        Object co = recipe.get("chancedOutputs");
                        if (co instanceof List<?> col) { for (Object e : col) extractItemFromChanced(e, itemIds); }
                        Object ci = recipe.get("chancedInputs");
                        if (ci instanceof List<?> cil) { for (Object e : cil) extractItemFromChanced(e, itemIds); }
                        // research
                        Object sr = recipe.get("stationResearch");
                        if (sr instanceof Map<?, ?> sm) {
                            addIfStr(sm.get("researchStack"), itemIds);
                            addIfStr(sm.get("dataStack"), itemIds);
                        }
                    }
                }
            }
        }
        if (itemIds.isEmpty() && fluidIds.isEmpty()) return;
        // 顺序解析（parallelStream 可能触发 Forge 注册表线程安全问题）
        for (String id : itemIds) {
            if (!ITEM_CACHE.containsKey(id)) {
                net.minecraft.world.item.ItemStack stack = ItemStackJS.of(id);
                if (stack == null || stack.isEmpty()) {
                    MISSING_ITEMS.add(id);
                    ITEM_CACHE.put(id, getZWPlaceholder());
                } else {
                    ITEM_CACHE.put(id, stack);
                }
            }
        }
        for (String id : fluidIds) {
            if (!FLUID_ID_CACHE.containsKey(id)) {
                resolveAndCacheFluid(id);
            }
        }
        LOG.info("[DRE] 缓存预热完成: {} 物品 + {} 流体", itemIds.size(), fluidIds.size());
    }

    // ====== 并行构建一批配方 ======
    // 暂用顺序调用——并行注册需要 Rhino 内部 API，跨版本不稳定
    // 缓存带来的提升已足够大（重复物品解析减少 90%+）

    // ====== 统计 ======

    public static String getStats() {
        long hit = CACHE_HIT.get(), miss = CACHE_MISS.get();
        long total = hit + miss;
        double rate = total > 0 ? (hit * 100.0 / total) : 0;
        return String.format("缓存: %d 物品 + %d 流体 | 命中: %d/%d (%.1f%%)",
                ITEM_CACHE.size(), FLUID_ID_CACHE.size(), hit, total, rate);
    }

    public static void printStats() {
        LOG.info("[DRE] " + getStats());
    }

    public static Map<String, Object> getRecipeStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("loaded", Boolean.TRUE);
        stats.put("total", Long.valueOf(RECIPE_TOTAL.get()));
        stats.put("success", Long.valueOf(RECIPE_SUCCESS.get()));
        stats.put("failed", Long.valueOf(RECIPE_FAILED.get()));
        stats.put("disabled", Long.valueOf(RECIPE_DISABLED.get()));
        stats.put("errors", Integer.valueOf(RECIPE_ERRORS.size()));
        Map<String, Object> byType = new LinkedHashMap<>();
        synchronized (RECIPE_TYPE_STATS) {
            for (Map.Entry<String, TypeStats> entry : RECIPE_TYPE_STATS.entrySet()) {
                TypeStats typeStats = entry.getValue();
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("total", Long.valueOf(typeStats.total));
                item.put("success", Long.valueOf(typeStats.success));
                item.put("failed", Long.valueOf(typeStats.failed));
                item.put("disabled", Long.valueOf(typeStats.disabled));
                byType.put(entry.getKey(), item);
            }
        }
        stats.put("byType", byType);
        return stats;
    }

    public static String getRecipeStatsString() {
        return getRecipeStats().toString();
    }

    /**
     * 返回「配方类型 → 成功注册的配方数」的有序映射（按注册出现顺序）。
     * <p>数据来源即 safeAddRecipe → recordRecipe 累积的 {@link #RECIPE_TYPE_STATS}，
     * 所有 dishanhai: 配方都必经 safeAddRecipe，因此这是零遗漏的机器类型清单。
     * 供 GuideME 的 &lt;RecipeTypeIndex /&gt; 标签在渲染时读取。</p>
     * <p>只计入 success（实际生效的配方），跳过 unknown 占位类型与零成功类型。</p>
     */
    public static Map<String, Integer> getRecipeTypeCounts() {
        Map<String, Integer> out = new LinkedHashMap<>();
        synchronized (RECIPE_TYPE_STATS) {
            for (Map.Entry<String, TypeStats> entry : RECIPE_TYPE_STATS.entrySet()) {
                String type = entry.getKey();
                if (type == null || type.isEmpty() || "unknown".equals(type)) continue;
                long success = entry.getValue().success;
                if (success <= 0) continue;
                out.put(type, Integer.valueOf((int) success));
            }
        }
        return out;
    }

    /**
     * getRecipeTypeCounts() 的兜底：缓存命中时 山海的配方库.js 从头部直接 return，safeAddRecipe
     * 根本没跑过，RECIPE_TYPE_STATS 是空的，GuideMe 配方索引页会误报"配方库尚未加载"。这里改用
     * DShanhaiRecipeCache 反射出的山海自定义类型清单，配合下面 getRecipesOfType 已有的实时
     * lookup 树枚举——不依赖 safeAddRecipe 是否执行过，缓存命中/未命中都能拿到准确数量。
     */
    public static Map<String, Integer> getRecipeTypeCountsLive() {
        Map<String, Integer> out = new LinkedHashMap<>();
        try {
            for (com.gregtechceu.gtceu.api.recipe.GTRecipeType type :
                    com.dishanhai.gt_shanhai.common.recipe.DShanhaiRecipeCache.ownedRecipeTypes()) {
                if (type == null || type.registryName == null) continue;
                String key = type.registryName.toString();
                int count = getRecipesOfType(key).size();
                if (count > 0) out.put(key, count);
            }
        } catch (Exception e) {
            LOG.warn("[DRE] getRecipeTypeCountsLive() 失败: {}", e.getMessage());
        }
        return out;
    }

    /**
     * 返回指定配方类型的所有配方（有序列表，只读）。
     * <p>仅在游戏运行时调用（客户端需加载 RecipeManager）。用于生成配方索引子页面。</p>
     * <p>结果按类型 memoize 到 {@link #RECIPES_OF_TYPE_CACHE}：首次遍历 lookup 树后缓存，
     * 后续同类型直接返回。<b>配方重载后必须调 {@link #clearRecipeCache()} 失效</b>，
     * 否则会返回指向旧 RecipeManager 的过期 GTRecipe。</p>
     *
     * @param recipeType 配方类型字符串（如 "primordial_matter_recombination" 或 "gtceu:xxx"）
     * @return 该类型的所有 GTRecipe 只读列表；找不到类型或无配方时返回空列表
     */
    public static java.util.List<com.gregtechceu.gtceu.api.recipe.GTRecipe> getRecipesOfType(String recipeType) {
        if (recipeType == null || recipeType.isEmpty()) return java.util.Collections.emptyList();

        java.util.List<com.gregtechceu.gtceu.api.recipe.GTRecipe> cached = RECIPES_OF_TYPE_CACHE.get(recipeType);
        if (cached != null) return cached;

        java.util.List<com.gregtechceu.gtceu.api.recipe.GTRecipe> out = new ArrayList<>();
        try {
            String namespace = "gtceu";
            String path = recipeType;
            int colon = recipeType.indexOf(':');
            if (colon >= 0) {
                namespace = recipeType.substring(0, colon);
                path = recipeType.substring(colon + 1);
            }

            // 从注册表查找类型；非 gtceu 命名空间取不到时兜底再试 gtceu:path（与 RecipeCard 的 findRecipeType 一致）。
            com.gregtechceu.gtceu.api.recipe.GTRecipeType gtrType =
                    com.gregtechceu.gtceu.api.registry.GTRegistries.RECIPE_TYPES.get(new net.minecraft.resources.ResourceLocation(namespace, path));
            if (gtrType == null && !"gtceu".equals(namespace)) {
                gtrType = com.gregtechceu.gtceu.api.registry.GTRegistries.RECIPE_TYPES.get(new net.minecraft.resources.ResourceLocation("gtceu", path));
            }
            if (gtrType == null) return java.util.Collections.emptyList();

            // 枚举该类型的所有配方
            var lookup = gtrType.getLookup();
            if (lookup == null) return java.util.Collections.emptyList();
            var branch = lookup.getLookup();
            if (branch == null) return java.util.Collections.emptyList();

            var recipes = branch.getRecipes(true);
            if (recipes != null) {
                recipes.filter(java.util.Objects::nonNull).forEach(out::add);
            }
        } catch (Exception e) {
            LOG.warn("[DRE] getRecipesOfType({}) 失败: {}", recipeType, e.getMessage());
            return java.util.Collections.emptyList();
        }

        java.util.List<com.gregtechceu.gtceu.api.recipe.GTRecipe> result = java.util.Collections.unmodifiableList(out);
        RECIPES_OF_TYPE_CACHE.put(recipeType, result);
        return result;
    }

    /**
     * 清空配方类型缓存。客户端每次 RecipesUpdatedEvent（配方同步/重载）后必须调用，
     * 避免 {@link #getRecipesOfType} 返回指向旧 RecipeManager 的过期配方对象。
     */
    public static void clearRecipeCache() {
        RECIPES_OF_TYPE_CACHE.clear();
    }

    public static void resetRecipeStats() {
        RECIPE_TOTAL.set(0);
        RECIPE_SUCCESS.set(0);
        RECIPE_FAILED.set(0);
        RECIPE_DISABLED.set(0);
        synchronized (RECIPE_TYPE_STATS) {
            RECIPE_TYPE_STATS.clear();
        }
        clearErrors();
        clearLastReceipt();
        REGISTERED_RECIPE_IDS.clear();
        synchronized (RECIPE_TYPE_BY_ID) {
            RECIPE_TYPE_BY_ID.clear();
        }
    }

    // 供"配方库缓存"导出用：记录本次 reload 里 safeAddRecipe 成功注册的 (原始id -> 配方类型)，
    // 随 resetRecipeStats() 清空。见 DShanhaiRecipeCache.exportIfNeeded()。
    private static final Map<String, String> RECIPE_TYPE_BY_ID = new LinkedHashMap<>();

    public static void trackRecipeForCache(String recipeType, String rawId) {
        if (recipeType == null || rawId == null || rawId.isEmpty()) return;
        synchronized (RECIPE_TYPE_BY_ID) {
            RECIPE_TYPE_BY_ID.put(rawId, recipeType);
        }
    }

    public static Map<String, String> getTrackedRecipesForCache() {
        synchronized (RECIPE_TYPE_BY_ID) {
            return new LinkedHashMap<>(RECIPE_TYPE_BY_ID);
        }
    }

    /**
     * 配方ID是否已注册过（本次 reload 周期内）。
     */
    public static boolean hasRegisteredRecipe(String recipeId) {
        return recipeId != null && REGISTERED_RECIPE_IDS.contains(recipeId);
    }

    /**
     * 记录一个配方ID为已注册。返回 true 表示这是首次注册，false 表示此前已注册过（本次调用未生效）。
     */
    public static boolean registerRecipe(String recipeId) {
        if (recipeId == null || recipeId.isEmpty()) return false;
        return REGISTERED_RECIPE_IDS.add(recipeId);
    }

    public static void printRecipeStats() {
        LOG.info("[DRE] 配方统计: total={}, success={}, failed={}, disabled={}, errors={}",
                RECIPE_TOTAL.get(), RECIPE_SUCCESS.get(), RECIPE_FAILED.get(), RECIPE_DISABLED.get(), RECIPE_ERRORS.size());
    }

    public static void recordRecipe(String recipeType, boolean ok, String recipeId, String detail) {
        String type = recipeType == null || recipeType.isEmpty() ? "unknown" : recipeType;
        String id = recipeId == null || recipeId.isEmpty() ? "unknown" : recipeId;
        if (ok) {
            recordRecipeStat(type, "success");
            setReceipt(true, id, type, "saved", "", detail == null ? "" : detail);
        } else {
            String uid = reportRecipeError(id, type, "", detail == null ? "手动记录失败" : detail, null);
            recordRecipeStat(type, "failed");
            setReceipt(false, id, type, "failed", uid, detail == null ? "" : detail);
        }
    }

    public static void recordSkippedRecipe(String recipeType, String recipeId, String detail) {
        String type = recipeType == null || recipeType.isEmpty() ? "unknown" : recipeType;
        String id = recipeId == null || recipeId.isEmpty() ? "unknown" : recipeId;
        recordRecipeStat(type, "disabled");
        setReceipt(true, id, type, "disabled", "", detail == null ? "配方加载已禁用，已跳过" : detail);
    }

    public static void sendRecipeStatsToPlayer(net.minecraft.server.level.ServerPlayer player, String scriptVersion, String apiVersion) {
        if (player == null) return;
        long total = RECIPE_TOTAL.get();
        long success = RECIPE_SUCCESS.get();
        long failed = RECIPE_FAILED.get();
        long disabled = RECIPE_DISABLED.get();
        String modVersion = getModVersion();

        player.sendSystemMessage(net.minecraft.network.chat.Component.literal("&$?body_golden-============= 山海私货配方统计 ============="));
        if (total == 0L && com.dishanhai.gt_shanhai.common.recipe.DShanhaiRecipeCache.isCacheValid()) {
            // 缓存命中时 山海的配方库.js 从头部直接 return，RECIPE_TOTAL 等计数器不会被跑到，
            // 是正常现象，不是加载异常——配方本身已经从缓存 json 数据包原生加载完毕。
            long cachedCount = com.dishanhai.gt_shanhai.common.recipe.DShanhaiRecipeCache.getCachedRecipeCount();
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§b🗃 配方库缓存命中，本次跳过 Rhino 注册统计"));
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§a共 §e" + cachedCount + "§a 条配方从缓存数据包原生加载，非异常"));
        } else if (total == 0L) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§e⚠ 配方统计为空，可能加载异常"));
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§e💡 请检查服务端日志"));
        } else if (failed == 0L) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("&$*body_moss-[OK] 配方库加载完成!"));
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§a📦 成功加载: §e" + success + "§a 个配方"));
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§e⚠ 已被禁用配方数量：" + disabled));
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§a😋 配方库检测无报错 祝领航员航行无阻!"));
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§a💽 当前神人私货版本:v" + safeVersion(scriptVersion)));
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§a💽 当前gt_shanhai模组版本:v" + modVersion));
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§a💽 当前API总控系统版本为" + safeVersion(apiVersion)));
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("👌 你可以在Guime中查看配方引索,其会显示目前shanhai有哪些配方！"));
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("&$body_aurora-欢迎来到GTL寰宇联合重工巨企"));
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("&$body_moss-此成功信息回执由JAVA侧: DShanhaiRecipeEngine 生成"));
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("&$body_silver-老大我们这样熬夜写私货心脏真的不会自己先休息吗"));
        } else {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§e⚠ 配方加载完成（部分失败）"));
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§a📦 总计: §e" + total + "§a 个配方"));
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§a✓ 成功: §e" + success + "§a 个"));
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c✗ 失败: §e" + failed + "§c 个"));
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c⚠ 警告: 配方库错误，反馈联系 qq:1982932217"));
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c⚠ 此错误回执由JAVA侧: DShanhaiRecipeEngine 生成，它通常表明是KJS配方错误，通常而言这不是JVAV错误"));
            List<String> recent = getRecentErrors(3);
            if (!recent.isEmpty()) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c❌ 最近失败详情:"));
                for (int i = 0; i < recent.size(); i++) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal("  §7" + (i + 1) + ". §c" + recent.get(i)));
                }
                if (RECIPE_ERRORS.size() > recent.size()) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal("  §7... 还有 " + (RECIPE_ERRORS.size() - recent.size()) + " 个错误"));
                }
            }
        }
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal("&$?body_golden-==========================================="));
    }

    private static void recordRecipeStat(String recipeType, String status) {
        String type = recipeType == null || recipeType.isEmpty() ? "unknown" : recipeType;
        RECIPE_TOTAL.incrementAndGet();
        synchronized (RECIPE_TYPE_STATS) {
            TypeStats stats = RECIPE_TYPE_STATS.get(type);
            if (stats == null) {
                stats = new TypeStats();
                RECIPE_TYPE_STATS.put(type, stats);
            }
            stats.total++;
            if ("success".equals(status)) {
                RECIPE_SUCCESS.incrementAndGet();
                stats.success++;
            } else if ("disabled".equals(status)) {
                RECIPE_DISABLED.incrementAndGet();
                stats.disabled++;
            } else {
                RECIPE_FAILED.incrementAndGet();
                stats.failed++;
            }
        }
    }

    private static String safeVersion(String value) {
        return value == null || value.trim().isEmpty() ? "unknown" : value.trim();
    }

    public static String startTimer(String name) {
        String timerName = name == null || name.trim().isEmpty() ? "unnamed" : name.trim();
        String id = timerName + "#" + TIMER_ID.incrementAndGet();
        TIMERS.put(id, new TimerEntry(timerName, System.nanoTime()));
        LOG.info("[DRE] ⏱️ 开始: {}", timerName);
        return id;
    }

    public static long endTimer(String id) {
        TimerEntry entry = id == null ? null : TIMERS.remove(id);
        if (entry == null) {
            kjsWarn("计时器不存在或已结束: " + id);
            return -1L;
        }
        long elapsedMs = Math.max(0L, (System.nanoTime() - entry.startedNanos) / 1_000_000L);
        LOG.info("[DRE] ⏱️ 完成: {} | 耗时 {}ms", entry.name, elapsedMs);
        return elapsedMs;
    }

    /** 获取 mod 版本号，供 KJS 调用: DShanhaiRecipeEngine.getModVersion() */
    public static String getModVersion() {
        return com.dishanhai.gt_shanhai.GTDishanhaiMod.getVersion();
    }

    public static void resetCache() {
        ITEM_CACHE.clear();
        FLUID_ID_CACHE.clear();
        CACHE_HIT.set(0);
        CACHE_MISS.set(0);
        MISSING_ITEMS.clear();
    }

    /** 获取缺失物品清单（已用 dishanhai:zwf 占位替代） */
    public static Set<String> getMissingItems() {
        return Collections.unmodifiableSet(MISSING_ITEMS);
    }

    public static void printMissingItems() {
        if (MISSING_ITEMS.isEmpty()) return;
        LOG.warn("[DRE] 以下物品缺失，已用 dishanhai:zwf 占位: {}", String.join(", ", MISSING_ITEMS));
        kjsWarn("配方物品缺失: " + String.join(", ", MISSING_ITEMS));
    }

    // ====== 错误报告 ======

    /** 单条配方错误详情 */
    public static final class ErrorEntry {
        public final String uid;
        public final String recipeId;
        public final String recipeType;
        public final String field;
        public final String exceptionType;
        public final String detail;
        public final long timestamp;

        public ErrorEntry(String uid, String recipeId, String recipeType, String detail) {
            this(uid, recipeId, recipeType, "", "", detail);
        }

        public ErrorEntry(String uid, String recipeId, String recipeType, String field, String exceptionType, String detail) {
            this.uid = uid;
            this.recipeId = recipeId == null || recipeId.isEmpty() ? "unknown" : recipeId;
            this.recipeType = recipeType == null || recipeType.isEmpty() ? "unknown" : recipeType;
            this.field = field == null ? "" : field;
            this.exceptionType = exceptionType == null ? "" : exceptionType;
            this.detail = detail == null ? "" : detail;
            this.timestamp = System.currentTimeMillis();
        }

        /** 格式化为 [配方id],[机器配方类],[字段],[uid],[异常类型],[详情] */
        @Override
        public String toString() {
            return String.format("[%s],[%s],[%s],[%s],[%s],[%s]", recipeId, recipeType, field, uid, exceptionType, detail);
        }
    }

    private static final Map<String, ErrorEntry> RECIPE_ERRORS = Collections.synchronizedMap(new LinkedHashMap<>());

    private static final class RecipeEngineException extends RuntimeException {
        final String uid;

        RecipeEngineException(String uid, String message, Throwable cause) {
            super(message, cause);
            this.uid = uid;
        }
    }

    /** 按 UID 解析错误详情，无此 UID 返回 null */
    public static ErrorEntry resolveError(String uid) {
        return RECIPE_ERRORS.get(uid);
    }

    /** 获取所有错误 */
    public static Collection<ErrorEntry> getErrorEntries() {
        synchronized (RECIPE_ERRORS) {
            return new ArrayList<>(RECIPE_ERRORS.values());
        }
    }

    /** 获取最近一次 safeAddRecipe 的回执，供 KJS 诊断使用。 */
    public static Map<String, Object> getLastReceipt() {
        return new LinkedHashMap<>(LAST_RECEIPT);
    }

    public static String getLastReceiptString() {
        Map<String, Object> receipt = LAST_RECEIPT;
        return receipt.isEmpty() ? "{}" : receipt.toString();
    }

    public static String getLastErrorUid() {
        Object uid = LAST_RECEIPT.get("uid");
        return uid == null ? "" : String.valueOf(uid);
    }

    public static String getErrorSummary() {
        synchronized (RECIPE_ERRORS) {
            String latest = "";
            for (String uid : RECIPE_ERRORS.keySet()) latest = uid;
            return "errors=" + RECIPE_ERRORS.size() + (latest.isEmpty() ? "" : ", latest=" + latest);
        }
    }

    public static List<String> getRecentErrors(int limit) {
        int max = Math.max(0, limit);
        synchronized (RECIPE_ERRORS) {
            List<ErrorEntry> entries = new ArrayList<>(RECIPE_ERRORS.values());
            int from = Math.max(0, entries.size() - max);
            List<String> result = new ArrayList<>();
            for (int i = from; i < entries.size(); i++) result.add(entries.get(i).toString());
            return result;
        }
    }

    // ====== 主入口 ======

    /** ThreadLocal 传递当前配方 ID，供 addOneCondition 注册静态条件表 */
    private static final ThreadLocal<String> CURRENT_RECIPE_ID = new ThreadLocal<>();

    @SuppressWarnings("unchecked")
    public static boolean safeAddRecipe(Object gtrObj, Object recipeObj) {
        return safeAddRecipe(gtrObj, recipeObj, null);
    }

    @SuppressWarnings("unchecked")
    public static boolean safeAddRecipe(Object gtrObj, Object recipeObj, Object loadConfigObj) {
        clearLastReceipt();
        if (!(recipeObj instanceof Map<?, ?> data)) {
            String uid = reportRecipeError("unknown", "unknown", "", "对象配方必须是 Map/JS object", null);
            recordRecipeStat("unknown", "failed");
            setReceipt(false, "unknown", "unknown", "invalid_object", uid, "对象配方必须是 Map/JS object");
            return false;
        }

        String recipeType = stringValue(data.get("type"));
        String rawId = stringValue(data.get("id"));
        String recipeId = normalizeRecipeId(rawId);
        Object defaultEnabledObj = data.get("defaultEnabled");

        if (recipeType == null || recipeType.isEmpty()) {
            String uid = reportRecipeError(recipeId, "unknown", "type", "缺少 type", null);
            recordRecipeStat("unknown", "failed");
            setReceipt(false, recipeId, "unknown", "missing_type", uid, "缺少 type");
            return false;
        }
        if (recipeId == null || recipeId.isEmpty()) {
            String uid = reportRecipeError("unknown", recipeType, "id", "缺少 id", null);
            recordRecipeStat(recipeType, "failed");
            setReceipt(false, "unknown", recipeType, "missing_id", uid, "缺少 id");
            return false;
        }

        try {
            ((Map) data).put("id", recipeId);
        } catch (Throwable ignored) {}

        if (!isRecipeEnabled(recipeId, defaultEnabledObj, loadConfigObj)) {
            LOG.info("[DRE] ⏭️ 配方加载已禁用，跳过: {} ({})", recipeId, recipeType);
            recordRecipeStat(recipeType, "disabled");
            setReceipt(true, recipeId, recipeType, "disabled", "", "配方加载已禁用，已跳过");
            return true;
        }

        try {
            Object machine = createRecipeBuilder(gtrObj, recipeType, recipeId);
            if (!(machine instanceof RecipeJS)) {
                String uid = reportRecipeError(recipeId, recipeType, "builder", "无法创建 GTCEu RecipeJS builder", null);
                recordRecipeStat(recipeType, "failed");
                setReceipt(false, recipeId, recipeType, "builder_failed", uid, "无法创建 GTCEu RecipeJS builder");
                return false;
            }
            applyAll(machine, data);
            ((RecipeJS) machine).save();
            recordRecipeStat(recipeType, "success");
            setReceipt(true, recipeId, recipeType, "saved", "", "");
            return true;
        } catch (RecipeEngineException t) {
            recordRecipeStat(recipeType, "failed");
            setReceipt(false, recipeId, recipeType, "failed", t.uid, t.getMessage());
            return false;
        } catch (Throwable t) {
            String detail = throwableDetail(t);
            String uid = reportRecipeError(recipeId, recipeType, "", detail, t);
            recordRecipeStat(recipeType, "failed");
            setReceipt(false, recipeId, recipeType, "failed", uid, detail);
            return false;
        }
    }

    public static boolean isRecipeEnabled(Object recipeObj, Object loadConfigObj) {
        if (!(recipeObj instanceof Map<?, ?> data)) return true;
        return isRecipeEnabled(stringValue(data.get("id")), data.get("defaultEnabled"), loadConfigObj);
    }

    public static boolean isRecipeEnabled(String recipeId, Object defaultEnabledObj, Object loadConfigObj) {
        String normalized = normalizeRecipeId(recipeId);
        Boolean configured = readRecipeConfig(loadConfigObj, normalized);
        if (configured != null) return configured.booleanValue();
        Boolean defaultEnabled = toBooleanObject(defaultEnabledObj);
        return defaultEnabled == null || defaultEnabled.booleanValue();
    }

    private static Boolean readRecipeConfig(Object configObj, String recipeId) {
        if (configObj == null || recipeId == null || recipeId.isEmpty()) return null;
        String[] keys = recipeConfigKeys(recipeId);
        for (String key : keys) {
            Boolean value = readBooleanConfig(configObj, key);
            if (value != null) return value;
        }
        return null;
    }

    private static String[] recipeConfigKeys(String recipeId) {
        String full = normalizeRecipeId(recipeId);
        if (full == null) full = recipeId;
        String shortId = full;
        if (shortId != null && shortId.startsWith("dishanhai:")) shortId = shortId.substring(10);
        return new String[]{recipeId, full, shortId};
    }

    private static Boolean readBooleanConfig(Object configObj, String key) {
        if (configObj == null || key == null || key.isEmpty()) return null;
        if (configObj instanceof Map<?, ?> map && map.containsKey(key)) {
            return toBooleanObject(map.get(key));
        }
        if (configObj instanceof Scriptable scriptable) {
            Context cx = ScriptManager.getCurrentContext();
            Object value = scriptable.get(cx, key, scriptable);
            if (value != Scriptable.NOT_FOUND) return toBooleanObject(value);
        }
        return null;
    }

    private static Boolean toBooleanObject(Object value) {
        if (value instanceof Boolean b) return b;
        if (value instanceof String s) {
            String normalized = s.trim().toLowerCase(Locale.ROOT);
            if ("true".equals(normalized)) return Boolean.TRUE;
            if ("false".equals(normalized)) return Boolean.FALSE;
        }
        return null;
    }

    private static Object createRecipeBuilder(Object gtrObj, String recipeType, String recipeId) {
        Context cx = ScriptManager.getCurrentContext();
        if (gtrObj instanceof NamespaceFunction namespace) {
            Object fn = namespace.get(cx, recipeType, namespace);
            if (fn instanceof RecipeTypeFunction recipeFn) {
                return recipeFn.createRecipe(new Object[]{recipeId});
            }
        }
        Object fn = getScriptableProperty(cx, gtrObj, recipeType);
        if (fn instanceof RecipeTypeFunction recipeFn) {
            return recipeFn.createRecipe(new Object[]{recipeId});
        }
        throw new IllegalArgumentException("未知机器类型: " + recipeType);
    }

    private static Object getScriptableProperty(Context cx, Object target, String name) {
        if (target instanceof Scriptable scriptable) {
            return scriptable.get(cx, name, scriptable);
        }
        return null;
    }

    private static String normalizeRecipeId(String id) {
        if (id == null) return null;
        String value = id.trim();
        if (value.isEmpty()) return value;
        return value.indexOf(':') >= 0 ? value : "dishanhai:" + value;
    }

    private static String stringValue(Object value) {
        if (value == null) return null;
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    private static String reportRecipeError(String recipeId, String recipeType, String detail) {
        return reportRecipeError(recipeId, recipeType, "", detail, null);
    }

    private static String reportRecipeError(String recipeId, String recipeType, String field, String detail, Throwable throwable) {
        String uid = "DRE-" + ERROR_ID.incrementAndGet();
        String exceptionType = throwable == null ? "" : throwable.getClass().getSimpleName();
        ErrorEntry entry = new ErrorEntry(uid, recipeId, recipeType, field, exceptionType, detail);
        synchronized (RECIPE_ERRORS) {
            RECIPE_ERRORS.put(uid, entry);
            trimErrorEntriesLocked();
        }
        String fieldText = field == null || field.isEmpty() ? "" : " field=" + field;
        String errorText = exceptionType.isEmpty() ? detail : exceptionType + ": " + detail;
        kjsWarn("配方注册失败 uid=" + uid + " [" + recipeType + "] " + recipeId + fieldText + " - " + errorText);
        return uid;
    }

    private static void trimErrorEntriesLocked() {
        while (RECIPE_ERRORS.size() > MAX_ERROR_ENTRIES) {
            Iterator<String> iterator = RECIPE_ERRORS.keySet().iterator();
            if (!iterator.hasNext()) return;
            iterator.next();
            iterator.remove();
        }
    }

    private static void clearLastReceipt() {
        LAST_RECEIPT = Collections.emptyMap();
    }

    private static void setReceipt(boolean ok, String recipeId, String recipeType, String status, String uid, String detail) {
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("receiptId", "DRR-" + RECEIPT_ID.incrementAndGet());
        receipt.put("ok", Boolean.valueOf(ok));
        receipt.put("status", status == null ? "" : status);
        receipt.put("recipeId", recipeId == null ? "unknown" : recipeId);
        receipt.put("recipeType", recipeType == null ? "unknown" : recipeType);
        receipt.put("uid", uid == null ? "" : uid);
        receipt.put("detail", detail == null ? "" : detail);
        receipt.put("timestamp", Long.valueOf(System.currentTimeMillis()));
        LAST_RECEIPT = Collections.unmodifiableMap(receipt);
    }

    private static String throwableDetail(Throwable throwable) {
        if (throwable == null) return "";
        String message = throwable.getMessage();
        return message == null || message.isEmpty() ? throwable.toString() : message;
    }

    @SuppressWarnings("unchecked")
    public static void applyAll(Object machineObj, Object dataObj) {
        if (!(dataObj instanceof Map<?, ?> data)) return;

        // 提取配方标识用于错误报告
        Object typeObj = data.get("type");
        String recipeType = typeObj != null ? typeObj.toString() : "unknown";
        String recipeId = "unknown";
        if (machineObj instanceof RecipeJS r) {
            var id = r.getOrCreateId();
            recipeId = id != null ? id.toString() : "unknown";
        }
        CURRENT_RECIPE_ID.set(recipeId);

        // 提取 uid
        Object uidObj = data.get("uid");
        String currentUid = uidObj != null ? uidObj.toString().trim() : "";

        // duration 兜底——直接调 setDuration 而非改 Map（Rhino Map 可能不可变）
        try {
            Object durVal = data.get("duration");
        if (durVal == null || (durVal instanceof Number n && n.longValue() <= 0)) {
            setDuration(machineObj, 20L);
            try { ((Map) data).put("duration", 20L); } catch (Throwable ignored) {}
            kjsWarn(recipeId + " [" + recipeType + "] duration 缺失/无效，已补默认 20tick");
        }
        if (durVal instanceof Number n && n.longValue() > 2147483647L) {
            setDuration(machineObj, 1200L);
            try { ((Map) data).put("duration", 1200L); } catch (Throwable ignored) {}
            kjsWarn(recipeId + " [" + recipeType + "] duration 溢出(" + n.longValue() + ")，已修复为 1200tick");
        }

        // EUt 兜底
        Object euVal = data.get("EUt");
        if (euVal == null && !"cosmos_simulation".equals(recipeType)) {
            setEUt(machineObj, 32L);
            try { ((Map) data).put("EUt", 32L); } catch (Throwable ignored) {}
            kjsWarn(recipeId + " [" + recipeType + "] EUt 缺失，已补默认 32(LV)");
        }

        // 处理各字段
        for (Map.Entry<?, ?> entry : data.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            if (value == null) continue;
            if (SKIP_KEYS.contains(key)) continue;
            if ("uid".equals(key)) continue; // 已处理
            FieldHandler handler = HANDLERS.get(key);
            if (handler != null) {
                try {
                    handler.handle(machineObj, value);
                } catch (Throwable t) {
                    String detail = throwableDetail(t);
                    String uid = reportRecipeError(recipeId, recipeType, key, detail, t);
                    throw new RecipeEngineException(uid, "字段处理失败 " + key + ": " + detail, t);
                }
            }
        }
        } finally {
            CURRENT_RECIPE_ID.remove();
        }
    }

    /** 是否有配方注册错误 */
    public static boolean hasErrors() {
        return !RECIPE_ERRORS.isEmpty();
    }

    /** 获取所有错误信息（字符串列表） */
    public static List<String> getErrors() {
        synchronized (RECIPE_ERRORS) {
            List<String> list = new ArrayList<>();
            for (ErrorEntry e : RECIPE_ERRORS.values()) list.add(e.toString());
            return list;
        }
    }

    /** 清除错误列表 */
    public static void clearErrors() {
        RECIPE_ERRORS.clear();
    }

    /** 向玩家发送配方错误报告 */
    public static void sendReportToPlayer(net.minecraft.server.level.ServerPlayer player) {
        if (RECIPE_ERRORS.isEmpty()) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "§a[山海引擎] 无配方错误"));
            return;
        }
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "§c§l[山海引擎] 以下 " + RECIPE_ERRORS.size() + " 个配方注册失败:"));
        int i = 0;
        synchronized (RECIPE_ERRORS) {
            for (ErrorEntry entry : RECIPE_ERRORS.values()) {
                if (i >= 100) break;
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "§7" + (++i) + ". " + entry));
            }
        }
        if (RECIPE_ERRORS.size() > 100) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "§7... 还有 " + (RECIPE_ERRORS.size() - 100) + " 条错误未显示"));
        }
    }

    // ==================== 字段处理器 ====================

    public static void setDuration(Object m, Object v) {
        if (v instanceof Number n && m instanceof RecipeJS r) r.setValue(GTRecipeSchema.DURATION, n.longValue());
    }
    public static void setEUt(Object m, Object v) {
        if (v instanceof Number n) callMethod(m, "EUt", n.longValue());
    }
    public static void setCircuit(Object m, Object v) {
        Integer circuit = toCircuitInt(v);
        if (circuit != null) callMethod(m, "circuit", circuit.intValue());
    }
    public static void setBlastTemp(Object m, Object v) {
        if (v instanceof Number n) callMethod(m, "blastFurnaceTemp", n.intValue());
    }
    public static void applyNotConsumable(Object m, Object v) {
        forEachArg(v, a -> {
            if (m instanceof dev.latvian.mods.kubejs.recipe.RecipeJS r) {
                callMethod(m, "notConsumable", r.readInputItem(a));
            } else {
                callMethod(m, "notConsumable", dev.latvian.mods.kubejs.item.InputItem.of(a));
            }
        });
    }
    public static void applyNotConsumableFluid(Object m, Object v) {
        if (m instanceof dev.latvian.mods.kubejs.recipe.RecipeJS) {
            forEachArg(v, a -> {
                var ingredient = com.gregtechceu.gtceu.integration.kjs.recipe.components.GTRecipeComponents.FluidIngredientJS.of(a);
                callMethod(m, "notConsumableFluid", ingredient);
            });
        } else {
            forEachArg(v, a -> callMethod(m, "notConsumableFluid", a));
        }
    }
    public static void applyChanced(Object m, Object list, boolean isInput) {
        if (!(list instanceof List<?> l)) return;
        String method = isInput ? "chancedInput" : "chancedOutput";
        for (Object entry : l) {
            Object stack; int chance = 0, boost = 0;
            if (entry instanceof List<?> a && a.size() >= 2) {
                stack = a.get(0); chance = toInt(a.get(1)); boost = a.size() > 2 ? toInt(a.get(2)) : 0;
            } else if (entry instanceof Map<?, ?> map) {
                stack = map.get("item"); chance = toInt(map.get("chance"));
                boost = map.containsKey("tierBonus") ? toInt(map.get("tierBonus")) : 0;
            } else continue;
            if (isInput) {
                callMethod(m, "chancedInput", dev.latvian.mods.kubejs.item.InputItem.of(stack), chance, boost);
            } else {
                callMethod(m, "chancedOutput", com.gregtechceu.gtceu.integration.kjs.recipe.components.ExtendedOutputItem.of(stack), chance, boost);
            }
        }
    }
    public static void applyResearch(Object m, Object v) {
        if (!(v instanceof Map<?, ?> map)) return;
        Object rs = map.get("researchStack"), ds = map.get("dataStack");
        Object eu = map.get("EUt"), cw = map.get("CWUt");
        if (rs == null || ds == null || eu == null || cw == null) return;
        var rsStack = toCachedItemStack(rs);
        var dsStack = toCachedItemStack(ds);
        int euVal = toInt(eu), cwVal = toInt(cw);
        callMethod(m, "stationResearch", (java.util.function.UnaryOperator<Object>) b -> {
            callMethod(b, "researchStack", rsStack);
            callMethod(b, "dataStack", dsStack);
            callMethod(b, "EUt", euVal);
            callMethod(b, "CWUt", cwVal);
            return b;
        });
    }
    public static void applyConditions(Object m, Object v) {
        forEachArg(v, item -> {
            if (item == null) return;
            if (item instanceof com.gregtechceu.gtceu.api.recipe.RecipeCondition) {
                callMethod(m, "addCondition", item);
            } else if (!addConditionText(m, item)) {
                callMethod(m, "addCondition", item);
            }
        });
    }

    private static boolean addConditionText(Object m, Object value) {
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) return true;
        if (text.length() >= 2 && text.charAt(0) == '[' && text.charAt(text.length() - 1) == ']') {
            String body = text.substring(1, text.length() - 1).trim();
            if (body.isEmpty()) return true;
            String[] parts = body.split(",");
            for (String part : parts) {
                String condition = part.trim();
                if (!condition.isEmpty()) addOneCondition(m, condition);
            }
        } else {
            addOneCondition(m, text);
        }
        return true;
    }

    private static void addOneCondition(Object m, String s) {
        // 解析 "Nx item_id" → ModuleLevelCondition，无 x 默认 count=1
        int count = 1;
        String itemId = s.trim();
        int xIdx = itemId.indexOf('x');
        if (xIdx > 0 && Character.isDigit(itemId.charAt(0))) {
            try { count = Integer.parseInt(itemId.substring(0, xIdx).trim()); } catch (NumberFormatException ignored) {}
            itemId = itemId.substring(xIdx + 1).trim();
        }
        // 含冒号且不是 KubeJS 条件前缀 → 模块条件
        if (itemId.contains(":") && !itemId.startsWith("not_") && !itemId.startsWith("loaded")
                && !itemId.startsWith("installed") && !itemId.startsWith("forge:")) {
            var cond = new com.dishanhai.gt_shanhai.api.ModuleLevelCondition(itemId, count);
            callMethod(m, "addCondition", cond);
            // 写入静态注册表，绕过 KubeJS 序列化/反序列化类型丢失
            String recipeId = CURRENT_RECIPE_ID.get();
            if (recipeId != null && !recipeId.equals("unknown")) {
                com.dishanhai.gt_shanhai.api.ModuleLevelCondition.register(recipeId, cond);
            }
        } else {
            callMethod(m, "addCondition", s);
        }
    }
    /** 配方备注：.notes("任意文本") 或 .notes(["第一行", "第二行"])，纯展示，不解析、不拆分逗号。 */
    public static void applyNotes(Object m, Object v) {
        forEachArg(v, item -> {
            if (item == null) return;
            String text = String.valueOf(item);
            if (text.isEmpty()) return;
            callMethod(m, "addCondition", new com.dishanhai.gt_shanhai.api.RecipeNoteCondition(text));
        });
    }
    public static void applyItemInputs(Object m, Object v) {
        if (m instanceof dev.latvian.mods.kubejs.recipe.RecipeJS r && v instanceof List<?> list) {
            var inputs = new dev.latvian.mods.kubejs.item.InputItem[list.size()];
            for (int i = 0; i < list.size(); i++) inputs[i] = r.readInputItem(list.get(i));
            callMethod(m, "inputItems", new Object[]{inputs});
        }
    }
    public static void applyItemOutputs(Object m, Object v) {
        if (m instanceof dev.latvian.mods.kubejs.recipe.RecipeJS r && v instanceof List<?> list) {
            var outputs = new com.gregtechceu.gtceu.integration.kjs.recipe.components.ExtendedOutputItem[list.size()];
            for (int i = 0; i < list.size(); i++) {
                var oi = r.readOutputItem(list.get(i));
                outputs[i] = com.gregtechceu.gtceu.integration.kjs.recipe.components.ExtendedOutputItem.fromOutputItem(oi);
            }
            callMethod(m, "itemOutputs", new Object[]{outputs});
        }
    }
    public static void applyInputFluids(Object m, Object v) {
        if (m instanceof dev.latvian.mods.kubejs.recipe.RecipeJS r && v instanceof List<?> list) {
            var fluids = new com.gregtechceu.gtceu.integration.kjs.recipe.components.GTRecipeComponents.FluidIngredientJS[list.size()];
            for (int i = 0; i < list.size(); i++) {
                fluids[i] = com.gregtechceu.gtceu.integration.kjs.recipe.components.GTRecipeComponents.FluidIngredientJS.of(list.get(i));
            }
            callMethod(m, "inputFluids", new Object[]{fluids});
        }
    }
    public static void applyOutputFluids(Object m, Object v) {
        if (m instanceof dev.latvian.mods.kubejs.recipe.RecipeJS r && v instanceof List<?> list) {
            var fluids = new dev.latvian.mods.kubejs.fluid.FluidStackJS[list.size()];
            for (int i = 0; i < list.size(); i++) fluids[i] = dev.latvian.mods.kubejs.fluid.FluidStackJS.of(list.get(i));
            callMethod(m, "outputFluids", new Object[]{fluids});
        }
    }

    // ==================== 缓存工具 ====================

    /** 按 item ID 缓存（不带数量前缀），返回时按需乘数量 */
    private static Object toCachedItemStack(Object v) {
        if (v instanceof net.minecraft.world.item.ItemStack s) return s;
        if (v instanceof String s) {
            int count = 1;
            String id = s.trim();
            int xIdx = id.indexOf('x');
            if (xIdx > 0 && Character.isDigit(id.charAt(0))) {
                try { count = Integer.parseInt(id.substring(0, xIdx).trim()); } catch (NumberFormatException ignored) {}
                id = id.substring(xIdx + 1).trim();
            }
            Object existing = ITEM_CACHE.get(id);
            if (existing instanceof net.minecraft.world.item.ItemStack base) {
                CACHE_HIT.incrementAndGet();
                if (count > 1) { var copy = base.copy(); copy.setCount(count); return copy; }
                return base;
            }
            CACHE_MISS.incrementAndGet();
            var stack = ItemStackJS.of(id);
            if (stack == null || stack.isEmpty()) {
                MISSING_ITEMS.add(id);
                kjsWarn("物品缺失: " + id + " — 已用 dishanhai:zwf 占位");
                var result = getZWPlaceholder();
                ITEM_CACHE.put(id, result);
                if (count > 1) { var copy = result.copy(); copy.setCount(count); return copy; }
                return result;
            }
            var result = stack;
            ITEM_CACHE.put(id, result);
            if (count > 1) { var copy = result.copy(); copy.setCount(count); return copy; }
            return result;
        }
        return v;
    }

    private static com.lowdragmc.lowdraglib.side.fluid.FluidStack resolveAndCacheFluid(String id) {
        return FLUID_ID_CACHE.computeIfAbsent(id, k -> {
            String[] parts = k.split(":", 2);
            var fluid = net.minecraftforge.registries.ForgeRegistries.FLUIDS.getValue(
                    new net.minecraft.resources.ResourceLocation(parts[0], parts.length > 1 ? parts[1] : ""));
            if (fluid == null || fluid.isSame(net.minecraft.world.level.material.Fluids.EMPTY)) return null;
            return com.lowdragmc.lowdraglib.side.fluid.FluidStack.create(fluid, 1);
        });
    }

    // ==================== 辅助：收集 ID ====================

    @SuppressWarnings("unchecked")
    private static void collectIds(Map<?, ?> recipe, String key, Set<String> out) {
        Object val = recipe.get(key);
        if (val instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String s && !s.startsWith("#")) {
                    out.add(s.trim());
                } else if (item instanceof Map<?, ?> map) {
                    Object iObj = map.get("item");
                    if (iObj instanceof String is && !is.startsWith("#")) out.add(is.trim());
                    Object tObj = map.get("tag");
                    if (tObj instanceof String ts) collectTagItems(ts, out);
                }
            }
        } else if (val instanceof String s && !s.startsWith("#")) {
            out.add(s.trim());
        } else if (val instanceof Map<?, ?> map) {
            Object iObj = map.get("item");
            if (iObj instanceof String is && !is.startsWith("#")) out.add(is.trim());
        }
    }

    /** 收集 tag 下的所有物品 ID */
    private static void collectTagItems(String tagId, Set<String> out) {
        try {
            var tag = net.minecraftforge.registries.ForgeRegistries.ITEMS.tags()
                    .getTag(net.minecraft.tags.ItemTags.create(new net.minecraft.resources.ResourceLocation(tagId)));
            if (tag != null) {
                for (var item : tag) {
                    var id = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(item);
                    if (id != null) out.add(id.toString());
                }
            }
        } catch (Exception ignored) {}
    }

    @SuppressWarnings("unchecked")
    private static void collectFluidIds(Map<?, ?> recipe, String key, Set<String> out) {
        Object val = recipe.get(key);
        if (val instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String s) {
                    int sp = s.lastIndexOf(' ');
                    out.add(sp > 0 ? s.substring(0, sp).trim() : s.trim());
                } else if (item instanceof Map<?, ?> m) {
                    // {fluid: "gtceu:water", amount: 1000}
                    Object fObj = m.get("fluid");
                    if (fObj != null) out.add(fObj.toString().trim());
                }
            }
        } else if (val instanceof String s) {
            int sp = s.lastIndexOf(' ');
            out.add(sp > 0 ? s.substring(0, sp).trim() : s.trim());
        } else if (val instanceof Map<?, ?> m) {
            Object fObj = m.get("fluid");
            if (fObj != null) out.add(fObj.toString().trim());
        }
    }

    @SuppressWarnings("unchecked")
    private static void extractItemFromChanced(Object entry, Set<String> out) {
        if (entry instanceof List<?> a && a.size() >= 1) addIfStr(a.get(0), out);
        else if (entry instanceof Map<?, ?> m) addIfStr(m.get("item"), out);
    }

    private static void addIfStr(Object v, Set<String> out) {
        if (v instanceof String s) out.add(s);
    }

    // ==================== 内部工具 ====================

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("DRE");

    /** 写入 KJS server.log，同时输出到 Mod 日志 */
    private static void kjsWarn(String msg) {
        LOG.warn("[DRE] {}", msg);
        try { dev.latvian.mods.kubejs.util.ConsoleJS.SERVER.warn("[DRE] " + msg); } catch (Throwable ignored) {}
    }

    private static void kjsInfo(String msg) {
        LOG.info("[DRE] {}", msg);
        try { dev.latvian.mods.kubejs.util.ConsoleJS.SERVER.info("[DRE] " + msg); } catch (Throwable ignored) {}
    }

    @FunctionalInterface
    private interface ArgConsumer { void accept(Object arg); }

    private static void forEachArg(Object value, ArgConsumer consumer) {
        if (value instanceof List<?> list) { for (Object item : list) consumer.accept(item); }
        else if (value instanceof Object[] array) { for (Object item : array) consumer.accept(item); }
        else if (value instanceof Scriptable scriptable && isScriptableArrayLike(scriptable)) {
            Context cx = ScriptManager.getCurrentContext();
            Object lenObj = scriptable.get(cx, "length", scriptable);
            int length = lenObj instanceof Number n ? n.intValue() : 0;
            for (int i = 0; i < length; i++) {
                Object item = scriptable.get(cx, i, scriptable);
                if (item != Scriptable.NOT_FOUND) consumer.accept(item);
            }
        }
        else if (value != null) consumer.accept(value);
    }

    private static boolean isScriptableArrayLike(Scriptable scriptable) {
        Context cx = ScriptManager.getCurrentContext();
        Object lenObj = scriptable.get(cx, "length", scriptable);
        return lenObj instanceof Number;
    }

    private static void callMethod(Object target, String name, Object... args) {
        try {
            if (target == null) throw new IllegalArgumentException("target is null");
            Class<?> clazz = target.getClass();
            Class<?>[] exactTypes = new Class<?>[args.length];
            boolean canUseExactTypes = true;
            for (int i = 0; i < args.length; i++) {
                if (args[i] == null) {
                    canUseExactTypes = false;
                    break;
                }
                exactTypes[i] = args[i].getClass();
            }
            if (canUseExactTypes) {
                try { clazz.getMethod(name, exactTypes).invoke(target, args); return; }
                catch (NoSuchMethodException ignored) {}
            }
            for (var m : clazz.getMethods()) {
                if (!m.getName().equals(name) || m.getParameterCount() != args.length) continue;
                boolean match = true;
                for (int i = 0; i < args.length; i++) {
                    Class<?> p = m.getParameterTypes()[i];
                    if (args[i] == null) {
                        if (p.isPrimitive()) match = false;
                        continue;
                    }
                    if (p.isPrimitive() && args[i] instanceof Number) continue;
                    if (p.isInstance(args[i])) continue;
                    match = false; break;
                }
                if (!match) continue;
                Object[] conv = new Object[args.length];
                for (int i = 0; i < args.length; i++) {
                    Class<?> p = m.getParameterTypes()[i];
                    if (p == int.class && args[i] instanceof Number n) conv[i] = n.intValue();
                    else if (p == long.class && args[i] instanceof Number n) conv[i] = n.longValue();
                    else conv[i] = args[i];
                }
                m.invoke(target, conv); return;
            }
            throw new NoSuchMethodException(target.getClass().getName() + "." + name + "(" + java.util.Arrays.toString(args) + ")");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            Throwable cause = e instanceof java.lang.reflect.InvocationTargetException ite && ite.getCause() != null ? ite.getCause() : e;
            throw new IllegalStateException("callMethod " + name + "(" + java.util.Arrays.toString(args) + ") failed: " + throwableDetail(cause), cause);
        }
    }

    private static int toInt(Object v) {
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) return Integer.parseInt(s, 10);
        return 0;
    }

    private static Integer toCircuitInt(Object v) {
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            String value = s.trim();
            if (value.isEmpty()) return null;
            try { return Integer.valueOf(Integer.parseInt(value, 10)); }
            catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }

    private DShanhaiRecipeEngine() {}
}
