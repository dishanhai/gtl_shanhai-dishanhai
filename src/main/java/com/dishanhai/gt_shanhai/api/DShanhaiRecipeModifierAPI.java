package com.dishanhai.gt_shanhai.api;

import com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient;
import com.gregtechceu.gtceu.api.recipe.ingredient.SizedIngredient;
import com.gregtechceu.gtceu.api.registry.GTRegistries;
import com.dishanhai.gt_shanhai.mixin.SlotCacheManagerAccessor;
import com.lowdragmc.lowdraglib.side.fluid.FluidStack;

import org.gtlcore.gtlcore.api.machine.trait.IRecipeCapabilityMachine;
import org.gtlcore.gtlcore.api.machine.trait.MEPatternRecipeHandlePart;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * 配方修饰器注册中心 — 供 KubeJS 注册 + 命令系统使用的运行时配方修改引擎。
 */
public class DShanhaiRecipeModifierAPI {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("RecipeModAPI");

    private static final List<WeakReference<Object>> PATTERN_CACHE_OWNERS = new CopyOnWriteArrayList<>();
    private static final AtomicLong PATTERN_CACHE_REVISION = new AtomicLong();
    private static final Object PATTERN_CACHE_INVALIDATION_BATCH_LOCK = new Object();
    private static int patternCacheInvalidationBatchDepth;
    private static boolean patternCacheInvalidationBatchDirty;
    private static String patternCacheInvalidationBatchReason;
    private static final Map<String, Field> FIELD_LOOKUP_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Method> METHOD_LOOKUP_CACHE = new ConcurrentHashMap<>();

    // ====== JS 回调 ======

    @FunctionalInterface
    public interface JSRecipeModifier {
        Object apply(Object machine, Object recipe);
    }

    private static final Map<String, List<JSRecipeModifier>> JS_MODIFIERS = new LinkedHashMap<>();

    public static void register(String recipeTypeId, JSRecipeModifier modifier) {
        if (recipeTypeId == null || modifier == null) return;
        JS_MODIFIERS.computeIfAbsent(recipeTypeId, k -> new CopyOnWriteArrayList<>()).add(modifier);
        DShanhaiRuntimeRecipeCache.clear();
        LOG.info("已注册 JS 修饰器: {}", recipeTypeId);
    }

    public static boolean hasRuntimeRecipeModifiers(String recipeTypeId) {
        return !JS_MODIFIERS.getOrDefault(recipeTypeId, Collections.emptyList()).isEmpty()
                || !STRIP_RULES.getOrDefault(recipeTypeId, Collections.emptyList()).isEmpty()
                || !REPLACE_RULES.getOrDefault(recipeTypeId, Collections.emptyList()).isEmpty();
    }

    public static boolean hasRuntimeStripRules(String recipeTypeId) {
        return !STRIP_RULES.getOrDefault(recipeTypeId, Collections.emptyList()).isEmpty();
    }

    public static boolean hasRuntimeStripOrReplaceRules(String recipeTypeId) {
        return !STRIP_RULES.getOrDefault(recipeTypeId, Collections.emptyList()).isEmpty()
                || !REPLACE_RULES.getOrDefault(recipeTypeId, Collections.emptyList()).isEmpty();
    }

    public static boolean canUseRuntimeRecipeCache(String recipeTypeId) {
        return JS_MODIFIERS.getOrDefault(recipeTypeId, Collections.emptyList()).isEmpty();
    }

    // ====== 内置替换器（命令系统用） ======

    public static class ReplaceEntry {
        public final String oldItem;
        public final String newItem;
        public final boolean oldIsFluid;
        public final boolean newIsFluid;
        public final String recipeId;
        public final int count;
        public final int circuitNumber; // -1=非电路, 0-32=电路号

        /** 物品→物品（无数量指定） */
        public ReplaceEntry(String oldItem, String newItem, boolean oldIsFluid, boolean newIsFluid, String recipeId) {
            this(oldItem, newItem, oldIsFluid, newIsFluid, recipeId, 0, -1);
        }

        /** 物品→物品（带数量） */
        public ReplaceEntry(String oldItem, String newItem, boolean oldIsFluid, boolean newIsFluid, String recipeId, int count) {
            this(oldItem, newItem, oldIsFluid, newIsFluid, recipeId, count, -1);
        }

        /** 物品→电路 */
        public ReplaceEntry(String oldItem, int circuitNumber, String recipeId) {
            this(oldItem, "gtceu:programmed_circuit", false, false, recipeId, 0, circuitNumber);
        }

        /** 完整构造器 */
        public ReplaceEntry(String oldItem, String newItem, boolean oldIsFluid, boolean newIsFluid, String recipeId, int count, int circuitNumber) {
            this.oldItem = oldItem;
            this.newItem = newItem;
            this.oldIsFluid = oldIsFluid;
            this.newIsFluid = newIsFluid;
            this.recipeId = recipeId;
            this.count = count;
            this.circuitNumber = circuitNumber;
        }
    }

    private static final Map<String, List<ReplaceEntry>> REPLACE_RULES = new LinkedHashMap<>();

    /** 注册替换规则 */
    public static void addReplaceRule(String recipeTypeId, ReplaceEntry rule) {
        REPLACE_RULES.computeIfAbsent(recipeTypeId, k -> new CopyOnWriteArrayList<>()).add(rule);
        if (!LOADING_REPLACE.get()) saveReplaceRules();
        LOG.info("已注册替换规则 [{}]: {}→{} ({}=>{})",
                recipeTypeId, rule.oldItem, rule.newItem,
                rule.oldIsFluid ? "流体" : "物品", rule.newIsFluid ? "流体" : "物品");
    }

    private static final ThreadLocal<Boolean> LOADING_REPLACE = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Boolean> LOADING_STRIP   = ThreadLocal.withInitial(() -> false);

    /** 获取替换规则列表 */
    public static List<ReplaceEntry> getReplaceRules(String recipeTypeId) {
        return REPLACE_RULES.getOrDefault(recipeTypeId, Collections.emptyList());
    }

    /** 移除指定配方类型的所有替换规则 */
    public static boolean removeReplaceRules(String recipeTypeId) {
        var old = REPLACE_RULES.remove(recipeTypeId);
        if (old != null) {
            saveReplaceRules();
            // 从原始缓存恢复配方（已缓存类型直接还原，未缓存类型重建模板）
            var stripRules = STRIP_RULES.getOrDefault(recipeTypeId, Collections.emptyList());
            updateLookupRecipes(recipeTypeId, stripRules);
        }
        return old != null;
    }

    // ====== 内置删除器-按ID（命令系统用） ======

    public static class DeleteEntry {
        public final String recipeRegex; // 配方 ID 正则
        public DeleteEntry(String recipeRegex) { this.recipeRegex = recipeRegex; }
    }

    private static final Map<String, List<DeleteEntry>> DELETE_RULES = new LinkedHashMap<>();

    /** 注册删除规则 */
    public static void addDeleteRule(String recipeTypeId, DeleteEntry rule) {
        DELETE_RULES.computeIfAbsent(recipeTypeId, k -> new CopyOnWriteArrayList<>()).add(rule);
        if (!LOADING_DELETE.get()) { saveDeleteRules(); rebuildLookup(recipeTypeId); }
    }

    /** 获取删除规则列表 */
    public static List<DeleteEntry> getDeleteRules(String recipeTypeId) {
        return DELETE_RULES.getOrDefault(recipeTypeId, Collections.emptyList());
    }

    /** 移除指定配方类型的所有删除规则 */
    public static boolean removeDeleteRules(String recipeTypeId) {
        var old = DELETE_RULES.remove(recipeTypeId);
        if (old != null) { saveDeleteRules(); rebuildLookup(recipeTypeId); }
        return old != null;
    }

    /**
     * 按配方 ID 正则删除配方并注册为持久化规则。
     * @return 删除数量
     */
    public static int deleteRecipesById(String recipeTypeId, String recipeRegex) {
        var type = GTRegistries.RECIPE_TYPES.get(new ResourceLocation(recipeTypeId));
        if (type == null) return 0;
        var lookup = type.getLookup();
        if (lookup == null) return 0;
        var branch = lookup.getLookup();
        if (branch == null) return 0;

        Pattern p = Pattern.compile(recipeRegex);
        List<GTRecipe> allRecipes = new ArrayList<>();
        branch.getRecipes(true).forEach(r -> { if (r != null) allRecipes.add(r); });
        List<GTRecipe> keep = new ArrayList<>();
        int deleted = 0;
        for (var r : allRecipes) {
            if (r == null) continue;
            String rid = r.getId() != null ? r.getId().toString() : "";
            if (p.matcher(rid).find()) { deleted++; }
            else { keep.add(r); }
        }

        if (deleted > 0) {
            addDeleteRule(recipeTypeId, new DeleteEntry(recipeRegex));
            lookup.removeAllRecipes();
            for (GTRecipe r : keep) lookup.addRecipe(r);
            invalidatePatternCaches("deleteById:" + recipeTypeId);
            com.dishanhai.gt_shanhai.network.RecipeSyncPacket.syncToAll();
        }
        LOG.info("[DeleteById] {} regex=[{}] → 删除 {} 个配方", recipeTypeId, recipeRegex, deleted);
        return deleted;
    }

    private static final ThreadLocal<Boolean> LOADING_DELETE = ThreadLocal.withInitial(() -> false);

    // ====== 内置剥离器（命令系统用） ======

    public static class StripEntry {
        public final String targetItem;  // 物品/流体 ID
        public final boolean isInput;    // true=输入, false=输出
        public final boolean isFluid;    // true=流体, false=物品
        public final String recipeId;    // 可选，为空则匹配所有

        public StripEntry(String targetItem, boolean isInput, boolean isFluid, String recipeId) {
            this.targetItem = targetItem;
            this.isInput = isInput;
            this.isFluid = isFluid;
            this.recipeId = recipeId;
        }
    }

    private static final Map<String, List<StripEntry>> STRIP_RULES = new LinkedHashMap<>();

    /** 临时禁用 BranchStripMixin，防止 updateLookupRecipes 缓存原始配方时被污染 */
    public static final ThreadLocal<Boolean> SUPPRESS_GET_RECIPES_STRIP = ThreadLocal.withInitial(() -> false);

    /** 注册剥离规则 */
    public static void addStripRule(String recipeTypeId, StripEntry rule) {
        STRIP_RULES.computeIfAbsent(recipeTypeId, k -> new CopyOnWriteArrayList<>()).add(rule);
        if (!LOADING_STRIP.get()) {
            saveStripRules();
            rebuildLookup(recipeTypeId);
        }
        LOG.info("已注册剥离规则 [{}]: {} {} {} {}", recipeTypeId,
                rule.isFluid ? "流体" : "物品", rule.isInput ? "输入" : "输出",
                rule.targetItem, rule.recipeId.isEmpty() ? "(全部)" : rule.recipeId);
    }

    /** 移除剥离规则（按目标物品） */
    public static boolean removeStripRule(String recipeTypeId, String targetItem) {
        var list = STRIP_RULES.get(recipeTypeId);
        if (list == null) return false;
        boolean removed = list.removeIf(r -> r.targetItem.equals(targetItem));
        if (list.isEmpty()) STRIP_RULES.remove(recipeTypeId);
        if (removed) {
            saveStripRules();
            rebuildLookup(recipeTypeId);
        }
        return removed;
    }

    /** 移除指定配方类型的所有剥离规则 */
    public static boolean removeStripRules(String recipeTypeId) {
        var old = STRIP_RULES.remove(recipeTypeId);
        if (old != null) {
            saveStripRules();
            rebuildLookup(recipeTypeId);
        }
        return old != null;
    }

    /** 获取剥离规则列表 */
    public static List<StripEntry> getStripRules(String recipeTypeId) {
        return STRIP_RULES.getOrDefault(recipeTypeId, Collections.emptyList());
    }

    /** 获取所有剥离规则摘要 */
    public static Map<String, List<StripEntry>> getAllStripRules() {
        return Collections.unmodifiableMap(STRIP_RULES);
    }

    /** 触发模板重建（规则变更时调用） */
    private static void rebuildLookup(String recipeTypeId) {
        var list = STRIP_RULES.get(recipeTypeId);
        updateLookupRecipes(recipeTypeId, list != null ? list : Collections.emptyList());
    }

    // ====== 核心调用 ======

    /**
     * 仅应用剥离规则，不需要机器上下文。
     * 供 RecipeIterator.next() 等无机器场景使用。
     */
    public static void applyStripByType(GTRecipe recipe) {
        if (recipe == null || recipe.recipeType == null) return;
        String typeId = recipe.recipeType.registryName.toString();
        var stripList = STRIP_RULES.get(typeId);
        if (stripList == null) return;
        String recipeId = recipe.getId() != null ? recipe.getId().toString() : "";
        for (StripEntry rule : stripList) {
            if (!rule.recipeId.isEmpty()) {
                boolean idMatch = recipeId.equals(rule.recipeId) || recipeId.contains(rule.recipeId);
                if (!idMatch) continue;
            }
            applyStrip(recipe, rule);
        }
    }

    /** 供 BranchStripMixin 调用的实时替换——对单个配方应用替换规则 */
    public static void applyReplaceByType(GTRecipe recipe) {
        if (recipe == null || recipe.recipeType == null) return;
        String typeId = recipe.recipeType.registryName.toString();
        var replaceList = REPLACE_RULES.get(typeId);
        if (replaceList == null || replaceList.isEmpty()) return;
        String rid = recipe.getId() != null ? recipe.getId().toString() : "";
        for (ReplaceEntry rule : replaceList) {
            if (!rule.recipeId.isEmpty()) {
                if (!matchesRecipeId(rid, rule.recipeId)) continue;
            }
            replaceInMap(recipe.inputs, rule.oldItem, rule.newItem, rule.oldIsFluid, rule.count, rule.circuitNumber);
            replaceInMap(recipe.outputs, rule.oldItem, rule.newItem, rule.oldIsFluid, rule.count, rule.circuitNumber);
            replaceInMap(recipe.tickInputs, rule.oldItem, rule.newItem, rule.oldIsFluid, rule.count, rule.circuitNumber);
            replaceInMap(recipe.tickOutputs, rule.oldItem, rule.newItem, rule.oldIsFluid, rule.count, rule.circuitNumber);
        }
    }

    public static GTRecipe applyFromRecipe(MetaMachine machine, GTRecipe recipe) {
        if (recipe == null || recipe.recipeType == null) return recipe;
        String typeId = recipe.recipeType.registryName.toString();
        LOG.debug("[ModAPI] applyFromRecipe called: type={}, machine={}", typeId, machine.getClass().getSimpleName());
        return apply(typeId, machine, recipe);
    }

    public static GTRecipe apply(String recipeTypeId, MetaMachine machine, GTRecipe recipe) {
        if (recipe == null) return null;
        LOG.debug("[ModAPI] apply: type={}, rules={}", recipeTypeId,
                STRIP_RULES.getOrDefault(recipeTypeId, Collections.emptyList()).size());

        // 1. 应用 JS 修饰器
        var jsList = JS_MODIFIERS.get(recipeTypeId);
        if (jsList != null) {
            Object current = recipe;
            for (JSRecipeModifier mod : jsList) {
                try {
                    current = mod.apply(machine, current);
                    if (current == null) return null;
                } catch (Exception e) {
                    LOG.warn("JS 修饰器执行失败 [{}]: {}", recipeTypeId, e.getMessage());
                }
            }
            if (!(current instanceof GTRecipe r)) return recipe;
            recipe = r;
        }

        // 2. 应用剥离规则
        var stripList = STRIP_RULES.get(recipeTypeId);
        if (stripList != null) {
            for (StripEntry rule : stripList) {
                if (!rule.recipeId.isEmpty()) {
                    String id = recipe.getId() != null ? recipe.getId().toString() : "";
                    if (!id.equals(rule.recipeId) && !id.contains(rule.recipeId)) continue;
                }
                applyStrip(recipe, rule);
            }
        }

        // 3. 应用替换规则（运行时）
        var replaceList = REPLACE_RULES.get(recipeTypeId);
        if (replaceList != null) {
            String rid = recipe.getId() != null ? recipe.getId().toString() : "";
            for (ReplaceEntry rule : replaceList) {
                if (!rule.recipeId.isEmpty()) {
                    if (!matchesRecipeId(rid, rule.recipeId)) continue;
                }
                boolean found = false;
                found |= replaceInMap(recipe.inputs,     rule.oldItem, rule.newItem, rule.oldIsFluid, rule.count, rule.circuitNumber);
                found |= replaceInMap(recipe.outputs,    rule.oldItem, rule.newItem, rule.oldIsFluid, rule.count, rule.circuitNumber);
                found |= replaceInMap(recipe.tickInputs,  rule.oldItem, rule.newItem, rule.oldIsFluid, rule.count, rule.circuitNumber);
                found |= replaceInMap(recipe.tickOutputs, rule.oldItem, rule.newItem, rule.oldIsFluid, rule.count, rule.circuitNumber);
            }
        }

        return recipe;
    }

    private static void applyStrip(GTRecipe recipe, StripEntry rule) {
        RecipeCapability<?> cap = rule.isFluid ? FluidRecipeCapability.CAP : ItemRecipeCapability.CAP;
        Map<RecipeCapability<?>, List<Content>>[] targets;
        if (rule.isInput) {
            targets = new Map[]{recipe.inputs, recipe.tickInputs};
        } else {
            targets = new Map[]{recipe.outputs, recipe.tickOutputs};
        }
        for (Map<RecipeCapability<?>, List<Content>> target : targets) {
            var contents = target.get(cap);
            if (contents == null) continue;

        // 部分配方的 List 是 Guava ImmutableList，不可变
        List<Content> mutable;
        if (contents instanceof com.google.common.collect.ImmutableCollection) {
            mutable = new java.util.ArrayList<>(contents);
            target.put(cap, mutable);
        } else {
            mutable = contents;
        }

        int before = mutable.size();
        mutable.removeIf(content -> {
            if (rule.isFluid) {
                if (content.content instanceof com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient fi) {
                    return matchesFluidIngredient(fi, rule.targetItem);
                }
                if (content.content instanceof com.lowdragmc.lowdraglib.side.fluid.FluidStack fs) {
                    return matchesFluid(fs, rule.targetItem);
                }
                return false;
            }
            // 物品剥离
            if (content.content instanceof SizedIngredient si) {
                return matchesItemIngredient(si, rule.targetItem);
            }
            if (content.content instanceof ItemStack is) {
                return matchesItem(is, rule.targetItem);
            }
            return false;
        });
        int removed = before - mutable.size();
        if (removed > 0) {
            LOG.debug("[ModAPI] 已剥离 {} 个条目 [{}] from {} (recipeId={})",
                    removed, rule.targetItem, rule.isInput ? "输入" : "输出", rule.recipeId);
        } else if (before > 0) {
            LOG.debug("[ModAPI] 未匹配到剥离目标 [{}] in {} (items count={})",
                    rule.targetItem, rule.isInput ? "输入" : "输出", before);
        }
        }
    }

    private static boolean matchesItem(ItemStack stack, String target) {
        var id = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id == null) return false;
        String idStr = id.toString();
        if (idStr.equals(target) || idStr.contains(target)) return true;
        String name = stack.getHoverName().getString();
        return name.contains(target);
    }

    private static boolean matchesFluid(com.lowdragmc.lowdraglib.side.fluid.FluidStack stack, String target) {
        var id = net.minecraftforge.registries.ForgeRegistries.FLUIDS.getKey(stack.getFluid());
        if (id == null) return false;
        return id.toString().equals(target) || id.toString().contains(target);
    }

    /** 检查 FluidIngredient 是否匹配目标（支持 #tag 和精确流体ID） */
    private static boolean matchesFluidIngredient(
            com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient fi, String target) {
        if (target.startsWith("#")) {
            String tagId = target.substring(1); // "forge:gases"
            if (fi.values != null) {
                for (var v : fi.values) {
                    if (v instanceof com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient.TagValue tv) {
                        var tag = tv.getTag();
                        if (tag != null && tag.location().toString().equals(tagId)) return true;
                    }
                }
            }
            return false;
        }
        // Exact fluid ID match
        var stacks = fi.getStacks();
        return stacks.length > 0 && matchesFluid(stacks[0], target);
    }

    /** 检查 SizedIngredient 是否匹配目标（支持 #tag 和精确物品ID） */
    private static boolean matchesItemIngredient(SizedIngredient si, String target) {
        if (target.startsWith("#")) {
            String tagId = target.substring(1);
            var stacks = si.getItems();
            for (var stack : stacks) {
                for (var tag : stack.getTags().toList()) {
                    if (tag.location().toString().equals(tagId)) return true;
                }
            }
            return false;
        }
        var stacks = si.getItems();
        return stacks.length > 0 && matchesItem(stacks[0], target);
    }

    /** 配方ID匹配：支持从JEI复制的完整ID(gtceu:nano_forge/vibranium_nanoswarm)或仅配方名 */
    private static boolean matchesRecipeId(String actualRecipeId, String filter) {
        if (filter.isEmpty()) return true;
        if (actualRecipeId.equals(filter)) return true;
        if (actualRecipeId.endsWith("/" + filter)) return true;
        return actualRecipeId.contains(filter);
    }

    // ====== 配方删除（从 GTRecipeLookup 中移除并可选重加修改版） ======

    /**
     * 从指定配方类型中删除包含 targetItem 的配方，可选重加去掉了 targetItem 的版本。
     * @return 删除的配方数量
     */
    public static int deleteRecipes(String recipeTypeId, String targetItem, boolean reAdd) {
        var type = GTRegistries.RECIPE_TYPES.get(new ResourceLocation(recipeTypeId));
        if (type == null) return 0;
        var lookup = type.getLookup();
        if (lookup == null) return 0;
        var branch = lookup.getLookup();
        if (branch == null) return 0;

        // 1. 先收集所有配方
        java.util.List<GTRecipe> allRecipes = new ArrayList<>();
        branch.getRecipes(true).forEach(r -> { if (r != null) allRecipes.add(r); });

        // 2. 遍历，标记要删除的和备加重加的
        java.util.List<GTRecipe> toKeep = new ArrayList<>();
        java.util.List<GTRecipe> toReAdd = new ArrayList<>();
        int[] count = {0};

        for (GTRecipe recipe : allRecipes) {
            var items = recipe.inputs.get(ItemRecipeCapability.CAP);
            if (items == null) { toKeep.add(recipe); continue; }
            boolean hasTarget = items.stream().anyMatch(c -> {
                if (c.content instanceof SizedIngredient si) {
                    var stacks = si.getItems();
                    return stacks.length > 0 && matchesItem(stacks[0], targetItem);
                }
                if (c.content instanceof ItemStack is) return matchesItem(is, targetItem);
                return false;
            });
            if (!hasTarget) { toKeep.add(recipe); continue; }

            count[0]++;
            if (reAdd) {
                var copy = recipe.copy();
                var copyItems = copy.inputs.get(ItemRecipeCapability.CAP);
                if (copyItems != null) {
                    copyItems.removeIf(c -> {
                        if (c.content instanceof SizedIngredient si) {
                            var stacks = si.getItems();
                            return stacks.length > 0 && matchesItem(stacks[0], targetItem);
                        }
                        if (c.content instanceof ItemStack is) return matchesItem(is, targetItem);
                        return false;
                    });
                }
                toReAdd.add(copy);
            }
        }

        // 3. 删全部，重建
        lookup.removeAllRecipes();
        for (var r : toKeep) lookup.addRecipe(r);
        for (var r : toReAdd) lookup.addRecipe(r);

        if (count[0] > 0) invalidatePatternCaches("deleteRecipes:" + recipeTypeId);

        LOG.info("[ModAPI] 删除了 {} 个配方 [{}] from {}, 重加 {} 个修改版",
                count[0], targetItem, recipeTypeId, toReAdd.size());
        return count[0];
    }

    /** 在指定配方类型中替换物品/流体（forceNotConsumable: true=强制不消耗, false=强制消耗, null=自动检测） */
    public static int replaceInRecipes(String recipeTypeId, String oldItem, String newItem,
                                        boolean oldIsFluid, boolean newIsFluid, Boolean forceNotConsumable, int newItemCount, int circuitNumber,
                                        String filterRecipeId) {
        var type = GTRegistries.RECIPE_TYPES.get(new ResourceLocation(recipeTypeId));
        if (type == null) return 0;
        var lookup = type.getLookup();
        if (lookup == null) return 0;
        var branch = lookup.getLookup();
        if (branch == null) return 0;

        List<GTRecipe> allRecipes = new ArrayList<>();
        branch.getRecipes(true).forEach(r -> { if (r != null) allRecipes.add(r); });

        int count = 0;
        List<GTRecipe> result = new ArrayList<>();
        for (GTRecipe recipe : allRecipes) {
            // recipeId 过滤：非空时仅匹配指定配方
            if (!filterRecipeId.isEmpty()) {
                String rid = recipe.getId() != null ? recipe.getId().toString() : "";
                if (!matchesRecipeId(rid, filterRecipeId)) {
                    result.add(recipe);
                    continue;
                }
            }
            GTRecipe copy = recipe.copy();
            // 原地替换：匹配旧物品 → 直接改 Content 引用，保留 chance/amount 等属性
            boolean found = false;
            found |= replaceInMap(copy.inputs, oldItem, newItem, oldIsFluid, newItemCount, circuitNumber);
            found |= replaceInMap(copy.outputs, oldItem, newItem, oldIsFluid, newItemCount, circuitNumber);
            found |= replaceInMap(copy.tickInputs, oldItem, newItem, oldIsFluid, newItemCount, circuitNumber);
            found |= replaceInMap(copy.tickOutputs, oldItem, newItem, oldIsFluid, newItemCount, circuitNumber);
            if (!found) {
                result.add(recipe);
                continue;
            }
            result.add(copy);
            count++;
        }

        lookup.removeAllRecipes();
        for (GTRecipe r : result) lookup.addRecipe(r);

        if (count > 0) invalidatePatternCaches("replaceInRecipes:" + recipeTypeId);
        LOG.info("[ModAPI] 替换完成: {} -> {} in {} ({} 个配方)", oldItem, newItem, recipeTypeId, count);
        com.dishanhai.gt_shanhai.network.RecipeSyncPacket.syncToAll();
        return count;
    }

    /** 自动扫描注册表，配对 raw_X→X_ingot/dust，批量替换 */
    /** 批量精确替换——传入旧ID数组和新ID数组，一对一对改 */
    public static int replaceBatch(String recipeTypeId, String[] oldItems, String[] newItems) {
        if (oldItems == null || newItems == null || oldItems.length != newItems.length) return 0;
        var type = GTRegistries.RECIPE_TYPES.get(new ResourceLocation(recipeTypeId));
        if (type == null) return 0;
        var lookup = type.getLookup();
        if (lookup == null) return 0;
        var branch = lookup.getLookup();
        if (branch == null) return 0;

        java.util.Map<Pattern, String> patterns = new java.util.LinkedHashMap<>();
        for (int i = 0; i < oldItems.length; i++)
            patterns.put(Pattern.compile(Pattern.quote(oldItems[i])), newItems[i]);

        List<GTRecipe> allRecipes = new ArrayList<>();
        branch.getRecipes(true).forEach(r -> { if (r != null) allRecipes.add(r); });
        LOG.info("[RepBatch] {} 共{}配方 {}条映射", recipeTypeId, allRecipes.size(), patterns.size());

        int total = 0;
        List<GTRecipe> result = new ArrayList<>();
        for (GTRecipe recipe : allRecipes) {
            GTRecipe copy = recipe.copy();
            isolateContentList(copy.inputs, ItemRecipeCapability.CAP);
            isolateContentList(copy.inputs, FluidRecipeCapability.CAP);
            isolateContentList(copy.outputs, ItemRecipeCapability.CAP);
            isolateContentList(copy.outputs, FluidRecipeCapability.CAP);
            isolateContentList(copy.tickInputs, ItemRecipeCapability.CAP);
            isolateContentList(copy.tickInputs, FluidRecipeCapability.CAP);
            isolateContentList(copy.tickOutputs, ItemRecipeCapability.CAP);
            isolateContentList(copy.tickOutputs, FluidRecipeCapability.CAP);
            boolean found = false;
            for (var entry : patterns.entrySet()) {
                Pattern p = entry.getKey(); String newId = entry.getValue();
                found |= replacePatternInMap(copy.inputs, copy, p, newId);
                found |= replacePatternInMap(copy.outputs, copy, p, newId);
                found |= replacePatternInMap(copy.tickInputs, copy, p, newId);
                found |= replacePatternInMap(copy.tickOutputs, copy, p, newId);
            }
            if (found) { result.add(copy); total++; }
            else { result.add(recipe); }
        }

        if (total > 0 && result.size() == allRecipes.size()) {
            lookup.removeAllRecipes();
            for (GTRecipe r : result) lookup.addRecipe(r);
            invalidatePatternCaches("replaceBatch:" + recipeTypeId);
            com.dishanhai.gt_shanhai.network.RecipeSyncPacket.syncToAll();
        }
        LOG.info("[RepBatch] {} 完成: {}/{}配方已修改", recipeTypeId, total, allRecipes.size());
        return total;
    }

    public static int replaceAllRawOres(String recipeTypeId) {
        var type = GTRegistries.RECIPE_TYPES.get(new ResourceLocation(recipeTypeId));
        if (type == null) return 0;
        var lookup = type.getLookup();
        if (lookup == null) return 0;
        var branch = lookup.getLookup();
        if (branch == null) return 0;

        java.util.Map<String, String> map = new java.util.LinkedHashMap<>();
        String[] SUFFIXES = {"_ingot", "_dust", "_gem", ""}; // 递减：ingot>dust>gem>裸名
        for (var e : net.minecraftforge.registries.ForgeRegistries.ITEMS.getEntries()) {
            String key = e.getKey().location().toString();
            int slash = key.indexOf('/');
            if (slash < 0) continue;
            String ns = key.substring(0, slash);
            String path = key.substring(slash + 1);
            if (!path.startsWith("raw_")) continue;
            String base = path.substring(4); // 去掉 raw_ 前缀
            // 尝试每种后缀，找到存在的就停
            for (String suf : SUFFIXES) {
                String target = ns + ":" + base + suf;
                if (suf.isEmpty()) target = ns + ":" + base; // 裸名
                if (net.minecraftforge.registries.ForgeRegistries.ITEMS.containsKey(new ResourceLocation(target))) {
                    map.put(key, target);
                    break;
                }
            }
        }
        map.put("gtceu:raw_nether_quartz", "minecraft:quartz");
        if (map.isEmpty()) return 0;

        // 预编译所有映射的Pattern
        java.util.Map<Pattern, String> patterns = new java.util.LinkedHashMap<>();
        for (var entry : map.entrySet())
            patterns.put(Pattern.compile(Pattern.quote(entry.getKey())), entry.getValue());

        List<GTRecipe> allRecipes = new ArrayList<>();
        branch.getRecipes(true).forEach(r -> { if (r != null) allRecipes.add(r); });
        LOG.info("[RawOreRep] {} 共{}配方 {}条映射", recipeTypeId, allRecipes.size(), patterns.size());

        int total = 0;
        List<GTRecipe> result = new ArrayList<>();
        for (GTRecipe recipe : allRecipes) {
            GTRecipe copy = recipe.copy();
            isolateContentList(copy.inputs, ItemRecipeCapability.CAP);
            isolateContentList(copy.inputs, FluidRecipeCapability.CAP);
            isolateContentList(copy.outputs, ItemRecipeCapability.CAP);
            isolateContentList(copy.outputs, FluidRecipeCapability.CAP);
            isolateContentList(copy.tickInputs, ItemRecipeCapability.CAP);
            isolateContentList(copy.tickInputs, FluidRecipeCapability.CAP);
            isolateContentList(copy.tickOutputs, ItemRecipeCapability.CAP);
            isolateContentList(copy.tickOutputs, FluidRecipeCapability.CAP);
            boolean found = false;
            for (var entry : patterns.entrySet()) {
                Pattern p = entry.getKey(); String newId = entry.getValue();
                found |= replacePatternInMap(copy.inputs, copy, p, newId);
                found |= replacePatternInMap(copy.outputs, copy, p, newId);
                found |= replacePatternInMap(copy.tickInputs, copy, p, newId);
                found |= replacePatternInMap(copy.tickOutputs, copy, p, newId);
            }
            if (found) { result.add(copy); total++; }
            else { result.add(recipe); }
        }

        if (total > 0 && result.size() == allRecipes.size()) {
            lookup.removeAllRecipes();
            for (GTRecipe r : result) lookup.addRecipe(r);
            invalidatePatternCaches("replaceAllRawOres:" + recipeTypeId);
            com.dishanhai.gt_shanhai.network.RecipeSyncPacket.syncToAll();
        }
        LOG.info("[RawOreRep] {} 完成: {}/{}配方已修改", recipeTypeId, total, allRecipes.size());
        return total;
    }

    public static int replacePatternRecipes(String recipeTypeId, String oldRegex, String newPattern) {
        var type = GTRegistries.RECIPE_TYPES.get(new ResourceLocation(recipeTypeId));
        if (type == null) return 0;
        var lookup = type.getLookup();
        if (lookup == null) return 0;
        var branch = lookup.getLookup();
        if (branch == null) return 0;

        Pattern oldPat = Pattern.compile(oldRegex);
        List<GTRecipe> allRecipes = new ArrayList<>();
        branch.getRecipes(true).forEach(r -> { if (r != null) allRecipes.add(r); });
        LOG.info("[PatRep] {} 共{}配方 regex=[{}] new=[{}]", recipeTypeId, allRecipes.size(), oldRegex, newPattern);

        int count = 0;
        List<GTRecipe> result = new ArrayList<>();
        for (GTRecipe recipe : allRecipes) {
            GTRecipe copy = recipe.copy();
            // copy()可能是浅拷贝，原地替换每个map的Content列表为新列表，避免污染原始配方
            isolateContentList(copy.inputs, ItemRecipeCapability.CAP);
            isolateContentList(copy.inputs, FluidRecipeCapability.CAP);
            isolateContentList(copy.outputs, ItemRecipeCapability.CAP);
            isolateContentList(copy.outputs, FluidRecipeCapability.CAP);
            isolateContentList(copy.tickInputs, ItemRecipeCapability.CAP);
            isolateContentList(copy.tickInputs, FluidRecipeCapability.CAP);
            isolateContentList(copy.tickOutputs, ItemRecipeCapability.CAP);
            isolateContentList(copy.tickOutputs, FluidRecipeCapability.CAP);
            boolean found = false;
            found |= replacePatternInMap(copy.inputs,     copy, oldPat, newPattern);
            found |= replacePatternInMap(copy.outputs,    copy, oldPat, newPattern);
            found |= replacePatternInMap(copy.tickInputs,  copy, oldPat, newPattern);
            found |= replacePatternInMap(copy.tickOutputs, copy, oldPat, newPattern);
            if (found) { result.add(copy); count++; }
            else { result.add(recipe); }
        }

        if (count > 0 && result.size() == allRecipes.size()) {
            LOG.info("[PatRep] 应用: {}个修改 (共{}配方)", count, allRecipes.size());
            lookup.removeAllRecipes();
            for (GTRecipe r : result) lookup.addRecipe(r);
            invalidatePatternCaches("replacePatternRecipes:" + recipeTypeId);
            com.dishanhai.gt_shanhai.network.RecipeSyncPacket.syncToAll();
        } else if (count > 0) {
            LOG.error("[ModAPI] 模式替换中止: 结果数量不匹配 ({} vs {})", result.size(), allRecipes.size());
            return 0;
        }
        LOG.info("[ModAPI] 模式替换: {} regex=[{}]->[{}] ({} 个配方)", recipeTypeId, oldRegex, newPattern, count);
        return count;
    }

    private static void isolateContentList(Map<RecipeCapability<?>, List<Content>> map, RecipeCapability<?> cap) {
        var list = map.get(cap);
        if (list != null) map.put(cap, new ArrayList<>(list));
    }

    private static boolean replacePatternInMap(Map<RecipeCapability<?>, List<Content>> map, GTRecipe recipe,
                                                Pattern oldPat, String newPattern) {
        RecipeCapability<?> cap = ItemRecipeCapability.CAP;
        var contents = map.get(cap);
        boolean isFluid = false;
        if (contents == null) { cap = FluidRecipeCapability.CAP; contents = map.get(cap); isFluid = true; }
        if (contents == null) return false;

        boolean changed = false;
        List<Content> newList = new ArrayList<>();
        for (Content c : contents) {
            if (c == null || c.content == null) { newList.add(c); continue; }
            String id = isFluid ? getFluidId(c.content) : getItemId(c.content);
            if (id == null || id.isEmpty()) { newList.add(c); continue; }
            java.util.regex.Matcher m = oldPat.matcher(id);
            if (!m.find()) { newList.add(c); continue; }
            String newId = m.replaceAll(newPattern);
            if (newId.equals(id)) { newList.add(c); continue; }
            // 构建替换后的 Content
            Content newContent = buildReplacedContent(c, isFluid, newId);
            if (newContent != null) { newList.add(newContent); changed = true; }
            else { newList.add(c); }
        }
        if (changed) map.put(cap, newList);
        return changed;
    }

    private static String getItemId(Object content) {
        if (content instanceof SizedIngredient si) {
            var items = si.getItems();
            if (items.length > 0) {
                var key = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(items[0].getItem());
                return key != null ? key.toString() : null;
            }
        } else if (content instanceof ItemStack is) {
            var key = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(is.getItem());
            return key != null ? key.toString() : null;
        }
        return null;
    }

    private static String getFluidId(Object content) {
        if (content instanceof FluidIngredient fi) {
            var stacks = fi.getStacks();
            if (stacks.length > 0) {
                var key = net.minecraftforge.registries.ForgeRegistries.FLUIDS.getKey(stacks[0].getFluid());
                return key != null ? key.toString() : null;
            }
        }
        return null;
    }

    private static Content buildReplacedContent(Content original, boolean isFluid, String newId) {
        var res = new ResourceLocation(newId);
        if (isFluid) {
            if (original.content instanceof FluidIngredient fi) {
                var fluid = net.minecraftforge.registries.ForgeRegistries.FLUIDS.getValue(res);
                if (fluid != null) {
                    return new Content(FluidIngredient.of(FluidStack.create(fluid, fi.getAmount(), fi.getNbt())),
                            original.chance, original.maxChance, original.tierChanceBoost, original.slotName, original.uiName);
                }
            }
        } else {
            var item = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(res);
            if (item != null) {
                int cnt = 1;
                if (original.content instanceof SizedIngredient si) {
                    var items = si.getItems();
                    if (items.length > 0) cnt = items[0].getCount();
                } else if (original.content instanceof ItemStack is) {
                    cnt = is.getCount();
                }
                try {
                    return new Content(SizedIngredient.create(new ItemStack(item, cnt)),
                            original.chance, original.maxChance, original.tierChanceBoost, original.slotName, original.uiName);
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private static boolean replaceInMap(Map<RecipeCapability<?>, List<Content>> map,
                                         String oldId, String newId, boolean isFluid, int newItemCount, int circuitNumber) {
        RecipeCapability<?> cap = isFluid ? FluidRecipeCapability.CAP : ItemRecipeCapability.CAP;
        var contents = map.get(cap);
        if (contents == null) return false;
        boolean changed = false;
        for (Content c : contents) {
            if (isFluid) {
                if (c.content instanceof com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient fi) {
                    if (matchesFluidIngredient(fi, oldId)) {
                        var newFluid = net.minecraftforge.registries.ForgeRegistries.FLUIDS.getValue(
                                new ResourceLocation(newId));
                        if (newFluid != null) {
                            long amount = newItemCount > 0 ? (long) newItemCount * 1000 : fi.getAmount();
                            fi.stacks[0] = com.lowdragmc.lowdraglib.side.fluid.FluidStack.create(
                                    newFluid, amount, fi.getNbt());
                            changed = true;
                        }
                    }
                }
            } else {
                if (c.content instanceof SizedIngredient si) {
                    if (matchesItemIngredient(si, oldId)) {
                        int count = newItemCount > 0 ? newItemCount : (si.getItems().length > 0 ? si.getItems()[0].getCount() : 1);
                        net.minecraft.world.item.ItemStack newStack;
                        if (circuitNumber >= 0) {
                            newStack = com.gregtechceu.gtceu.common.item.IntCircuitBehaviour.stack(circuitNumber);
                            newStack.setCount(count);
                        } else {
                            var newItem = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(
                                    new ResourceLocation(newId));
                            if (newItem == null) continue;
                            newStack = new net.minecraft.world.item.ItemStack(newItem, count);
                        }
                        try {
                            c.content = SizedIngredient.create(newStack);
                            changed = true;
                        } catch (Exception ignored) {}
                    }
                }
                if (c.content instanceof ItemStack is && matchesItem(is, oldId)) {
                    int count = newItemCount > 0 ? newItemCount : is.getCount();
                    net.minecraft.world.item.ItemStack newStack;
                    if (circuitNumber >= 0) {
                        newStack = com.gregtechceu.gtceu.common.item.IntCircuitBehaviour.stack(circuitNumber);
                        newStack.setCount(count);
                    } else {
                        var newItem = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(
                                new ResourceLocation(newId));
                        if (newItem == null) continue;
                        newStack = new net.minecraft.world.item.ItemStack(newItem, count);
                    }
                    c.content = newStack;
                    changed = true;
                }
                // GTCEu 自定义 Ingredient（模具、IntCircuit 等）
                if (c.content instanceof net.minecraft.world.item.crafting.Ingredient ing) {
                    var stacks = ing.getItems();
                    if (stacks.length > 0 && matchesItem(stacks[0], oldId)) {
                        int count = newItemCount > 0 ? newItemCount : stacks[0].getCount();
                        net.minecraft.world.item.ItemStack newStack;
                        if (circuitNumber >= 0) {
                            newStack = com.gregtechceu.gtceu.common.item.IntCircuitBehaviour.stack(circuitNumber);
                            newStack.setCount(count);
                        } else {
                            var newItem = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(
                                    new ResourceLocation(newId));
                            if (newItem == null) continue;
                            newStack = new net.minecraft.world.item.ItemStack(newItem, count);
                        }
                        try {
                            c.content = SizedIngredient.create(newStack);
                            changed = true;
                        } catch (Exception ignored) {}
                    }
                }
            }
        }
        return changed;
    }

    private static void addItemToMap(Map<RecipeCapability<?>, List<Content>> map, String newItem) {
        var newStack = net.minecraft.world.item.ItemStack.EMPTY;
        try {
            var item = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(new ResourceLocation(newItem));
            if (item != null) newStack = new net.minecraft.world.item.ItemStack(item, 1);
        } catch (Exception ignored) {}
        if (newStack.isEmpty()) return;
        var content = new Content(SizedIngredient.create(newStack), 1, 10000, 0, null, null);
        map.computeIfAbsent(ItemRecipeCapability.CAP, k -> new ArrayList<>()).add(content);
    }

    private static void addFluidToMap(Map<RecipeCapability<?>, List<Content>> map, String newItem) {
        var fluid = net.minecraftforge.registries.ForgeRegistries.FLUIDS.getValue(new ResourceLocation(newItem));
        if (fluid == null) return;
        try {
            var fi = com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient.of(
                    com.lowdragmc.lowdraglib.side.fluid.FluidStack.create(fluid, 1000, null));
            var content = new Content(fi, 1, 10000, 0, null, null);
            map.computeIfAbsent(FluidRecipeCapability.CAP, k -> new ArrayList<>()).add(content);
        } catch (Exception ignored) {}
    }

    public static int count(String recipeTypeId) {
        return JS_MODIFIERS.getOrDefault(recipeTypeId, Collections.emptyList()).size()
             + STRIP_RULES.getOrDefault(recipeTypeId, Collections.emptyList()).size();
    }

    // ====== 剥离规则应用到 Lookup 模板（ServerStartedEvent 调用） ======

    /** 原始配方缓存：首次成功调用时从 lookup 深拷贝，之后始终从此还原 */
    private static final Map<String, List<GTRecipe>> RECIPE_ORIGINALS = new LinkedHashMap<>();

    /**
     * 用当前规则重建有规则的类型的模板。
     * 仅处理 STRIP_RULES + REPLACE_RULES 中出现的类型，不全量扫描缓存。
     */
    public static void updateAllLookupRecipes() {
        runPatternCacheInvalidationBatch("updateAllLookupRecipes", () -> {
            Set<String> processed = new LinkedHashSet<>();

            // 处理剥离类型
            for (var entry : STRIP_RULES.entrySet()) {
                String typeId = entry.getKey();
                updateLookupRecipes(typeId, entry.getValue());
                processed.add(typeId);
            }
            // 处理替换类型（仅还原原始，应用在 applyAllReplaceRules 中做）
            for (var entry : REPLACE_RULES.entrySet()) {
                String typeId = entry.getKey();
                if (!processed.contains(typeId)) {
                    updateLookupRecipes(typeId, Collections.emptyList());
                    processed.add(typeId);
                }
            }
            // 处理删除类型（从 lookup 中移除匹配 ID 的配方）
            for (var entry : DELETE_RULES.entrySet()) {
                String typeId = entry.getKey();
                if (!processed.contains(typeId)) {
                    updateLookupRecipes(typeId, Collections.emptyList());
                    processed.add(typeId);
                }
                applyDeleteRules(typeId, entry.getValue());
            }
            LOG.info("[ModAPI] 配方模板重建完成 (类型={}, 剥离={}, 替换={}, 删除={})",
                    processed.size(), STRIP_RULES.size(), REPLACE_RULES.size(), DELETE_RULES.size());
        });
    }

    private static void applyDeleteRules(String recipeTypeId, List<DeleteEntry> rules) {
        var type = GTRegistries.RECIPE_TYPES.get(new ResourceLocation(recipeTypeId));
        if (type == null) return;
        var lookup = type.getLookup();
        if (lookup == null) return;
        var branch = lookup.getLookup();
        if (branch == null) return;
        for (DeleteEntry rule : rules) {
            Pattern p = Pattern.compile(rule.recipeRegex);
            List<GTRecipe> allRecipes = new ArrayList<>();
            branch.getRecipes(true).forEach(r -> { if (r != null) allRecipes.add(r); });
            List<GTRecipe> keep = new ArrayList<>();
            int removed = 0;
            for (var r : allRecipes) {
                if (r == null) continue;
                String rid = r.getId() != null ? r.getId().toString() : "";
                if (p.matcher(rid).find()) { removed++; }
                else { keep.add(r); }
            }
            if (removed > 0) {
                lookup.removeAllRecipes();
                for (GTRecipe r : keep) lookup.addRecipe(r);
                invalidatePatternCaches("deleteRule:" + recipeTypeId);
                LOG.info("[ModAPI] 删除规则应用: {} regex=[{}] → {} 个配方", recipeTypeId, rule.recipeRegex, removed);
            }
        }
    }

    private static void updateLookupRecipes(String recipeTypeId, List<StripEntry> rules) {
        var type = GTRegistries.RECIPE_TYPES.get(new ResourceLocation(recipeTypeId));
        if (type == null) {
            LOG.warn("[ModAPI] 配方类型未找到，跳过模板重建: {}", recipeTypeId);
            return;
        }
        var lookup = type.getLookup();
        if (lookup == null) return;

        // 首次成功调用时缓存原始配方（此时 lookup 应为未修改状态）
        List<GTRecipe> originals = RECIPE_ORIGINALS.get(recipeTypeId);
        if (originals == null) {
            final List<GTRecipe> fresh = new ArrayList<>();
            SUPPRESS_GET_RECIPES_STRIP.set(true);
            try {
                lookup.getLookup().getRecipes(true).forEach(r -> { if (r != null) fresh.add(r.copy()); });
            } finally {
                SUPPRESS_GET_RECIPES_STRIP.set(false);
            }
            if (fresh.isEmpty()) {
                LOG.warn("[ModAPI] {} 无任何配方，跳过模板重建", recipeTypeId);
                return;
            }
            RECIPE_ORIGINALS.put(recipeTypeId, fresh);
            originals = fresh;
            LOG.info("[ModAPI] 已缓存原始配方: {} ({} 条)", recipeTypeId, originals.size());
        }

        // 始终从缓存原始配方副本应用规则（移除规则时自然恢复）
        int modified = 0;
        List<GTRecipe> result = new ArrayList<>();
        for (GTRecipe original : originals) {
            GTRecipe copy = original.copy();
            for (StripEntry rule : rules) {
                if (!rule.recipeId.isEmpty()) {
                    String id = original.getId() != null ? original.getId().toString() : "";
                    if (!id.equals(rule.recipeId) && !id.contains(rule.recipeId)) continue;
                }
                applyStrip(copy, rule);
            }
            result.add(copy);
            if (!contentsEqual(original.inputs, copy.inputs) || !contentsEqual(original.outputs, copy.outputs)) {
                modified++;
            }
        }

        lookup.removeAllRecipes();
        for (GTRecipe r : result) {
            lookup.addRecipe(r);
        }

        invalidatePatternCaches("updateLookupRecipes:" + recipeTypeId);

        LOG.info("[ModAPI] {}: {} 条规则 → {} 个配方模板已修改 (共 {} 配方)",
                recipeTypeId, rules.size(), modified, result.size());
    }

    private static boolean contentsEqual(Map<RecipeCapability<?>, List<Content>> a, Map<RecipeCapability<?>, List<Content>> b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a.size() != b.size()) return false;
        for (var entry : a.entrySet()) {
            List<Content> other = b.get(entry.getKey());
            if (other == null) return false;
            if (entry.getValue().size() != other.size()) return false;
        }
        return true;
    }

    // ====== 配置持久化 ======

    private static final String CONFIG_DIR = "config/gt_shanhai";
    private static final java.io.File STRIP_FILE = new java.io.File(CONFIG_DIR, "strip_rules.json");
    private static final java.io.File REPLACE_FILE = new java.io.File(CONFIG_DIR, "replace_rules.json");
    private static final java.io.File DELETE_FILE = new java.io.File(CONFIG_DIR, "delete_rules.json");
    private static final com.google.gson.Gson GSON = new com.google.gson.GsonBuilder().setPrettyPrinting().create();

    /** 保存剥离规则到 config/gt_shanhai/strip_rules.json */
    public static void saveStripRules() {
        try {
            new java.io.File(CONFIG_DIR).mkdirs();
            var obj = new com.google.gson.JsonObject();
            for (var entry : STRIP_RULES.entrySet()) {
                var arr = new com.google.gson.JsonArray();
                for (StripEntry rule : entry.getValue()) {
                    var r = new com.google.gson.JsonObject();
                    r.addProperty("item", rule.targetItem);
                    r.addProperty("input", rule.isInput);
                    r.addProperty("fluid", rule.isFluid);
                    r.addProperty("recipeId", rule.recipeId);
                    arr.add(r);
                }
                obj.add(entry.getKey(), arr);
            }
            try (var w = new java.io.FileWriter(STRIP_FILE)) {
                GSON.toJson(obj, w);
            }
        } catch (Exception e) {
            LOG.warn("保存剥离规则失败: {}", e.getMessage());
        }
    }

    /** 从 config/gt_shanhai/strip_rules.json 加载剥离规则 */
    public static void loadStripRules() {
        if (!STRIP_FILE.exists()) return;
        LOADING_STRIP.set(true);
        try (var r = new java.io.FileReader(STRIP_FILE)) {
            var obj = com.google.gson.JsonParser.parseReader(r).getAsJsonObject();
            for (var typeEntry : obj.entrySet()) {
                String typeId = typeEntry.getKey();
                var arr = typeEntry.getValue().getAsJsonArray();
                for (var el : arr) {
                    var ruleObj = el.getAsJsonObject();
                    String item = ruleObj.get("item").getAsString();
                    boolean input = ruleObj.get("input").getAsBoolean();
                    boolean fluid = ruleObj.get("fluid").getAsBoolean();
                    String recipeId = ruleObj.has("recipeId") ? ruleObj.get("recipeId").getAsString() : "";
                    addStripRule(typeId, new StripEntry(item, input, fluid, recipeId));
                }
            }
            LOG.info("已加载剥离规则: {} 个类型", obj.size());
        } catch (Exception e) {
            LOG.warn("加载剥离规则失败: {}", e.getMessage());
        } finally {
            LOADING_STRIP.set(false);
        }
    }

    /**
     * 从文件重载剥离 + 替换规则到内存。
     * 规则在运行时的 Mixin 路径（applyFromRecipe）中应用，不修改配方模板。
     * JEI 端由 BranchStripMixin + applyReplaceByType 覆盖。
     */
    public static void reloadStripRules() {
        STRIP_RULES.clear();
        REPLACE_RULES.clear();
        DELETE_RULES.clear();
        loadStripRules();
        loadReplaceRules();
        loadDeleteRules();
        com.dishanhai.gt_shanhai.network.RecipeSyncPacket.syncToAll();
        LOG.info("已从文件重新加载: 剥离={}, 替换={}, 删除={}", STRIP_RULES.size(), REPLACE_RULES.size(), DELETE_RULES.size());
    }

    /** 应用所有已持久化的替换规则（批量处理，一次重建配方表） */
    public static void applyAllReplaceRules() {
        runPatternCacheInvalidationBatch("applyAllReplaceRules", () -> {
            for (var entry : REPLACE_RULES.entrySet()) {
                String typeId = entry.getKey();
                var rules = entry.getValue();
                if (rules.isEmpty()) continue;

                var type = GTRegistries.RECIPE_TYPES.get(new ResourceLocation(typeId));
                if (type == null) continue;
                var lookup = type.getLookup();
                if (lookup == null) continue;
                var branch = lookup.getLookup();
                if (branch == null) continue;

                // 一次读取所有配方
                List<GTRecipe> allRecipes = new ArrayList<>();
                branch.getRecipes(true).forEach(r -> { if (r != null) allRecipes.add(r); });

                int modified = 0;
                List<GTRecipe> result = new ArrayList<>();
                for (GTRecipe recipe : allRecipes) {
                    String rid = recipe.getId() != null ? recipe.getId().toString() : "";
                    GTRecipe copy = recipe.copy();
                    boolean found = false;
                    for (ReplaceEntry rule : rules) {
                        if (!rule.recipeId.isEmpty()) {
                            if (!matchesRecipeId(rid, rule.recipeId)) continue;
                        }
                        found |= replaceInMap(copy.inputs,  rule.oldItem, rule.newItem, rule.oldIsFluid, rule.count, rule.circuitNumber);
                        found |= replaceInMap(copy.outputs, rule.oldItem, rule.newItem, rule.oldIsFluid, rule.count, rule.circuitNumber);
                        found |= replaceInMap(copy.tickInputs,  rule.oldItem, rule.newItem, rule.oldIsFluid, rule.count, rule.circuitNumber);
                        found |= replaceInMap(copy.tickOutputs, rule.oldItem, rule.newItem, rule.oldIsFluid, rule.count, rule.circuitNumber);
                    }
                    result.add(copy);
                    if (found) modified++;
                }

                // 一次性写回
                lookup.removeAllRecipes();
                for (GTRecipe r : result) lookup.addRecipe(r);
                if (modified > 0) invalidatePatternCaches("applyAllReplaceRules:" + typeId);
                LOG.info("[ModAPI] 批量替换完成: {} ({} 条规则, {} 配方已修改)", typeId, rules.size(), modified);
            }
        });
    }

    public static void registerPatternCacheOwner(Object owner) {
        if (owner == null) return;
        PATTERN_CACHE_OWNERS.removeIf(ref -> ref.get() == null);
        for (WeakReference<Object> ref : PATTERN_CACHE_OWNERS) {
            Object existing = ref.get();
            if (existing == owner) {
                preparePatternCacheOwnerOnRegister(owner, "register-existing");
                return;
            }
        }
        PATTERN_CACHE_OWNERS.add(new WeakReference<>(owner));
        preparePatternCacheOwnerOnRegister(owner, "register-new");
    }

    public static void runPatternCacheInvalidationBatch(String reason, Runnable action) {
        if (action == null) return;
        beginPatternCacheInvalidationBatch(reason);
        try {
            action.run();
        } finally {
            endPatternCacheInvalidationBatch(reason);
        }
    }

    private static void beginPatternCacheInvalidationBatch(String reason) {
        synchronized (PATTERN_CACHE_INVALIDATION_BATCH_LOCK) {
            if (patternCacheInvalidationBatchDepth == 0) {
                patternCacheInvalidationBatchReason = reason;
                patternCacheInvalidationBatchDirty = false;
            }
            patternCacheInvalidationBatchDepth++;
        }
    }

    private static void endPatternCacheInvalidationBatch(String reason) {
        boolean shouldFlush = false;
        String flushReason = null;
        synchronized (PATTERN_CACHE_INVALIDATION_BATCH_LOCK) {
            if (patternCacheInvalidationBatchDepth <= 0) return;
            patternCacheInvalidationBatchDepth--;
            if (patternCacheInvalidationBatchDepth == 0) {
                shouldFlush = patternCacheInvalidationBatchDirty;
                flushReason = patternCacheInvalidationBatchReason != null ? patternCacheInvalidationBatchReason : reason;
                patternCacheInvalidationBatchDirty = false;
                patternCacheInvalidationBatchReason = null;
            }
        }
        if (shouldFlush) {
            invalidatePatternCaches("batch:" + flushReason);
        }
    }

    private static boolean deferPatternCacheInvalidation(String reason) {
        synchronized (PATTERN_CACHE_INVALIDATION_BATCH_LOCK) {
            if (patternCacheInvalidationBatchDepth <= 0) return false;
            patternCacheInvalidationBatchDirty = true;
            if (patternCacheInvalidationBatchReason == null) {
                patternCacheInvalidationBatchReason = reason;
            }
            return true;
        }
    }

    public static long getPatternCacheRevision() {
        return PATTERN_CACHE_REVISION.get();
    }

    public static boolean invalidatePatternCacheOwner(Object owner, String reason) {
        if (owner == null) return false;
        boolean changed = clearPatternCacheOwner(owner);
        if (changed) {
            LOG.info("[ModAPI] 已失效样板总成缓存: 单个 owner ({}, rev={})", reason, getPatternCacheRevision());
        }
        return changed;
    }

    private static void preparePatternCacheOwnerOnRegister(Object owner, String reason) {
        if (!hasRecipeModificationRules()) return;
        boolean changed = clearPatternCacheOwnerState(owner);
        syncPatternCacheOwnerRevision(owner);
        if (changed) {
            LOG.info("[ModAPI] 已清理新加载样板总成持久缓存 ({})", reason);
        }
    }

    private static boolean hasRecipeModificationRules() {
        return !JS_MODIFIERS.isEmpty()
                || !STRIP_RULES.isEmpty()
                || !REPLACE_RULES.isEmpty()
                || !DELETE_RULES.isEmpty();
    }

    public static void unregisterPatternCacheOwner(Object owner) {
        if (owner == null) return;
        PATTERN_CACHE_OWNERS.removeIf(ref -> {
            Object existing = ref.get();
            return existing == null || existing == owner;
        });
    }

    public static void invalidatePatternCaches(String reason) {
        if (deferPatternCacheInvalidation(reason)) return;
        DShanhaiRuntimeRecipeCache.clear();
        long revision = PATTERN_CACHE_REVISION.incrementAndGet();
        int count = 0;
        PATTERN_CACHE_OWNERS.removeIf(ref -> ref.get() == null);
        for (WeakReference<Object> ref : PATTERN_CACHE_OWNERS) {
            Object owner = ref.get();
            if (owner == null) continue;
            if (clearPatternCacheOwner(owner)) count++;
        }
        if (count > 0) LOG.info("[ModAPI] 已失效样板总成缓存: {} 个 ({}, rev={})", count, reason, revision);
        else LOG.debug("[ModAPI] 配方缓存版本已更新: rev={} ({})", revision, reason);
    }

    private static boolean clearPatternCacheOwner(Object owner) {
        boolean changed = clearPatternCacheOwnerState(owner);
        try {
            Method method = findMethod(owner.getClass(), "refreshAllByProduct");
            method.invoke(owner);
        } catch (Exception ignored) {}
        syncPatternCacheOwnerRevision(owner);
        refreshPatternControllers(owner);
        return changed;
    }

    private static boolean clearPatternCacheOwnerState(Object owner) {
        boolean changed = false;
        try {
            Field cacheRecipe = findField(owner.getClass(), "cacheRecipe");
            boolean[] values = (boolean[]) cacheRecipe.get(owner);
            if (values != null) {
                Arrays.fill(values, false);
                changed = true;
            }
        } catch (Exception ignored) {}
        try {
            Object map = findField(owner.getClass(), "recipeMultipleCacheMap").get(owner);
            if (map != null) {
                findMethod(map.getClass(), "clear").invoke(map);
                changed = true;
            }
        } catch (Exception ignored) {}
        try {
            Object inventory = getInternalInventory(owner);
            if (inventory instanceof Object[]) {
                Object[] slots = (Object[]) inventory;
                for (Object slot : slots) clearSlotCache(slot);
                changed = true;
            }
        } catch (Exception ignored) {}
        return changed;
    }

    private static void syncPatternCacheOwnerRevision(Object owner) {
        if (owner == null) return;
        try {
            Field revisionField = findField(owner.getClass(), "gtShanhai$recipeCacheRevision");
            revisionField.setLong(owner, getPatternCacheRevision());
        } catch (Exception ignored) {}
    }

    private static void refreshPatternControllers(Object owner) {
        try {
            Object controllers = findMethod(owner.getClass(), "getControllers").invoke(owner);
            if (!(controllers instanceof Iterable<?>)) return;
            for (Object controller : (Iterable<?>) controllers) {
                if (controller instanceof com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialOmegaEngineModuleBase) {
                    continue;
                }
                if (controller instanceof IRecipeCapabilityMachine machine) {
                    machine.upDate();
                    List<MEPatternRecipeHandlePart> parts = machine.getMEPatternRecipeHandleParts();
                    if (parts != null) {
                        for (MEPatternRecipeHandlePart part : parts) {
                            if (part != null) part.restoreMachineCache(machine::tryAddAndActiveRhp);
                        }
                    }
                }
                if (controller instanceof IRecipeLogicMachine logicMachine && logicMachine.getRecipeLogic() != null) {
                    logicMachine.getRecipeLogic().markLastRecipeDirty();
                    logicMachine.getRecipeLogic().updateTickSubscription();
                }
            }
        } catch (Exception ignored) {}
    }

    private static void clearSlotCache(Object slot) {
        if (slot == null) return;
        try {
            Object cacheManager = findMethod(slot.getClass(), "getCacheManager").invoke(slot);
            if (cacheManager == null) return;
            try {
                findMethod(cacheManager.getClass(), "clearAllCaches").invoke(cacheManager);
            } catch (Exception ignored) {}
            try {
                if (cacheManager instanceof SlotCacheManagerAccessor accessor) {
                    accessor.gtShanhai$setCircuitCacheRaw(-1);
                    accessor.gtShanhai$setCircuitStackRaw(ItemStack.EMPTY);
                }
            } catch (Exception ignored) {}
        } catch (Exception ignored) {}
    }

    private static Object getInternalInventory(Object owner) throws ReflectiveOperationException {
        try {
            return findMethod(owner.getClass(), "getInternalInventory").invoke(owner);
        } catch (ReflectiveOperationException ignored) {
            return findField(owner.getClass(), "internalInventory").get(owner);
        }
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        String cacheKey = type.getName() + '#' + name;
        Field cached = FIELD_LOOKUP_CACHE.get(cacheKey);
        if (cached != null) return cached;
        Class<?> current = type;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                Field existing = FIELD_LOOKUP_CACHE.putIfAbsent(cacheKey, field);
                return existing != null ? existing : field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... parameterTypes) throws NoSuchMethodException {
        String cacheKey = type.getName() + '#' + name + Arrays.toString(parameterTypes);
        Method cached = METHOD_LOOKUP_CACHE.get(cacheKey);
        if (cached != null) return cached;
        Class<?> current = type;
        while (current != null) {
            try {
                Method method = current.getDeclaredMethod(name, parameterTypes);
                method.setAccessible(true);
                Method existing = METHOD_LOOKUP_CACHE.putIfAbsent(cacheKey, method);
                return existing != null ? existing : method;
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchMethodException(name);
    }

    /** 持久化删除规则 */
    public static void saveDeleteRules() {
        try {
            new java.io.File(CONFIG_DIR).mkdirs();
            var obj = new com.google.gson.JsonObject();
            for (var entry : DELETE_RULES.entrySet()) {
                var arr = new com.google.gson.JsonArray();
                for (DeleteEntry rule : entry.getValue()) {
                    arr.add(rule.recipeRegex);
                }
                obj.add(entry.getKey(), arr);
            }
            try (var w = new java.io.OutputStreamWriter(new java.io.FileOutputStream(DELETE_FILE), java.nio.charset.StandardCharsets.UTF_8)) {
                GSON.toJson(obj, w);
            }
        } catch (Exception e) {
            LOG.warn("保存删除规则失败: {}", e.getMessage());
        }
    }

    /** 加载删除规则 */
    public static void loadDeleteRules() {
        if (!DELETE_FILE.exists()) return;
        LOADING_DELETE.set(true);
        try (var r = new java.io.InputStreamReader(new java.io.FileInputStream(DELETE_FILE), java.nio.charset.StandardCharsets.UTF_8)) {
            var obj = com.google.gson.JsonParser.parseReader(r).getAsJsonObject();
            for (var typeEntry : obj.entrySet()) {
                String typeId = typeEntry.getKey();
                var arr = typeEntry.getValue().getAsJsonArray();
                for (var el : arr) {
                    addDeleteRule(typeId, new DeleteEntry(el.getAsString()));
                }
            }
            LOG.info("已加载删除规则: {} 个类型", obj.size());
        } catch (Exception e) {
            LOG.warn("加载删除规则失败: {}", e.getMessage());
        } finally {
            LOADING_DELETE.remove();
        }
    }
    public static void saveReplaceRules() {
        try {
            new java.io.File(CONFIG_DIR).mkdirs();
            var obj = new com.google.gson.JsonObject();
            for (var entry : REPLACE_RULES.entrySet()) {
                var arr = new com.google.gson.JsonArray();
                for (ReplaceEntry rule : entry.getValue()) {
                    var r = new com.google.gson.JsonObject();
                    r.addProperty("old", rule.oldItem);
                    r.addProperty("new", rule.newItem);
                    r.addProperty("oldFluid", rule.oldIsFluid);
                    r.addProperty("newFluid", rule.newIsFluid);
                    r.addProperty("recipeId", rule.recipeId);
                    if (rule.count > 0) r.addProperty("count", rule.count);
                    if (rule.circuitNumber >= 0) r.addProperty("circuit", rule.circuitNumber);
                    arr.add(r);
                }
                obj.add(entry.getKey(), arr);
            }
            try (var w = new java.io.FileWriter(REPLACE_FILE)) {
                GSON.toJson(obj, w);
            }
        } catch (Exception e) {
            LOG.warn("保存替换规则失败: {}", e.getMessage());
        }
    }

    /** 加载替换规则 */
    public static void loadReplaceRules() {
        if (!REPLACE_FILE.exists()) return;
        LOADING_REPLACE.set(true);
        try (var r = new java.io.FileReader(REPLACE_FILE)) {
            var obj = com.google.gson.JsonParser.parseReader(r).getAsJsonObject();
            for (var typeEntry : obj.entrySet()) {
                String typeId = typeEntry.getKey();
                var arr = typeEntry.getValue().getAsJsonArray();
                for (var el : arr) {
                    var ruleObj = el.getAsJsonObject();
                    String old = ruleObj.get("old").getAsString();
                    String newItem = ruleObj.get("new").getAsString();
                    boolean oldF = ruleObj.get("oldFluid").getAsBoolean();
                    boolean newF = ruleObj.get("newFluid").getAsBoolean();
                    String rid = ruleObj.has("recipeId") ? ruleObj.get("recipeId").getAsString() : "";
                    int cnt = ruleObj.has("count") ? ruleObj.get("count").getAsInt() : 0;
                    int circ = ruleObj.has("circuit") ? ruleObj.get("circuit").getAsInt() : -1;
                    if (circ >= 0) {
                        addReplaceRule(typeId, new ReplaceEntry(old, circ, rid));
                    } else {
                        addReplaceRule(typeId, new ReplaceEntry(old, newItem, oldF, newF, rid, cnt, -1));
                    }
                }
            }
            LOG.info("已加载替换规则: {} 个类型", obj.size());
        } catch (Exception e) {
            LOG.warn("加载替换规则失败: {}", e.getMessage());
        } finally {
            LOADING_REPLACE.remove();
        }
    }

    // ====== 预设系统 ======

    private static final java.io.File PRESET_DIR = new java.io.File(CONFIG_DIR, "presets");

    /** 列出所有预设文件 */
    public static String[] listPresets() {
        if (!PRESET_DIR.exists()) return new String[0];
        var files = PRESET_DIR.list((d, n) -> n.endsWith(".json"));
        if (files == null) return new String[0];
        String[] names = new String[files.length];
        for (int i = 0; i < files.length; i++) {
            names[i] = files[i].replace(".json", "");
        }
        return names;
    }

    /** 删除预设文件 */
    public static boolean deletePreset(String presetName) {
        var file = new java.io.File(PRESET_DIR, presetName + ".json");
        if (!file.exists()) return false;
        boolean ok = file.delete();
        if (ok) LOG.info("[Preset] 已删除预设: {}", presetName);
        else LOG.warn("[Preset] 删除预设失败: {}", presetName);
        return ok;
    }

    /** 将当前规则导出为预设文件。指定 recipeType 仅导出该类型。ruleType: "strip"/"replace"/""=全部。
     *  @param force 为 false 时，文件已存在则拒绝覆盖 */
    public static boolean savePreset(String presetName, String recipeType, String ruleType, boolean force) {
        if (!PRESET_DIR.exists()) PRESET_DIR.mkdirs();
        var file = new java.io.File(PRESET_DIR, presetName + ".json");
        if (!force && file.exists()) {
            LOG.warn("[Preset] 预设已存在，使用 -f 覆盖: {}", presetName);
            return false;
        }
        var root = new com.google.gson.JsonObject();
        boolean saveStrip = ruleType.isEmpty() || "strip".equals(ruleType);
        boolean saveReplace = ruleType.isEmpty() || "replace".equals(ruleType);
        boolean saveDelete = ruleType.isEmpty() || "delete".equals(ruleType);
        boolean filterType = !recipeType.isEmpty();

        if (saveStrip && !STRIP_RULES.isEmpty()) {
            var stripObj = new com.google.gson.JsonObject();
            for (var entry : STRIP_RULES.entrySet()) {
                if (filterType && !entry.getKey().equals(recipeType)) continue;
                var arr = new com.google.gson.JsonArray();
                for (StripEntry rule : entry.getValue()) {
                    var r = new com.google.gson.JsonObject();
                    r.addProperty("item", rule.targetItem);
                    r.addProperty("input", rule.isInput);
                    r.addProperty("fluid", rule.isFluid);
                    r.addProperty("recipeId", rule.recipeId);
                    arr.add(r);
                }
                stripObj.add(entry.getKey(), arr);
            }
            if (stripObj.size() > 0) root.add("strip", stripObj);
        }

        if (saveReplace && !REPLACE_RULES.isEmpty()) {
            var replaceObj = new com.google.gson.JsonObject();
            for (var entry : REPLACE_RULES.entrySet()) {
                if (filterType && !entry.getKey().equals(recipeType)) continue;
                var arr = new com.google.gson.JsonArray();
                for (ReplaceEntry rule : entry.getValue()) {
                    var r = new com.google.gson.JsonObject();
                    r.addProperty("old", rule.oldItem);
                    r.addProperty("new", rule.newItem);
                    r.addProperty("oldFluid", rule.oldIsFluid);
                    r.addProperty("newFluid", rule.newIsFluid);
                    r.addProperty("recipeId", rule.recipeId);
                    if (rule.count > 0) r.addProperty("count", rule.count);
                    if (rule.circuitNumber >= 0) r.addProperty("circuit", rule.circuitNumber);
                    arr.add(r);
                }
                replaceObj.add(entry.getKey(), arr);
            }
            if (replaceObj.size() > 0) root.add("replace", replaceObj);
        }

        if (saveDelete && !DELETE_RULES.isEmpty()) {
            var deleteObj = new com.google.gson.JsonObject();
            for (var entry : DELETE_RULES.entrySet()) {
                if (filterType && !entry.getKey().equals(recipeType)) continue;
                var arr = new com.google.gson.JsonArray();
                for (DeleteEntry rule : entry.getValue()) {
                    arr.add(rule.recipeRegex);
                }
                deleteObj.add(entry.getKey(), arr);
            }
            if (deleteObj.size() > 0) root.add("delete", deleteObj);
        }

        if (root.size() == 0) {
            LOG.warn("[Preset] 无匹配规则可导出: type={} ruleType={}", recipeType, ruleType);
            return false;
        }

        try (var w = new java.io.FileWriter(file)) {
            GSON.toJson(root, w);
            LOG.info("[Preset] 已保存预设: {} (type={}, ruleType={})", presetName, recipeType, ruleType);
            return true;
        } catch (Exception e) {
            LOG.warn("[Preset] 保存预设失败: {}", e.getMessage());
            return false;
        }
    }

    /** 将全部规则导出为预设（不覆盖已存在文件） */
    public static boolean savePreset(String presetName) {
        return savePreset(presetName, "", "", false);
    }

    /** 将全部规则导出为预设 */
    public static boolean savePreset(String presetName, String recipeType, String ruleType) {
        return savePreset(presetName, recipeType, ruleType, false);
    }

    /** 加载预设。若 replace=true 则先清除现有规则再加载，否则追加。支持指定类型过滤。 */
    public static boolean loadPreset(String presetName, boolean replace, String recipeType) {
        var file = new java.io.File(PRESET_DIR, presetName + ".json");
        if (!file.exists()) {
            LOG.warn("[Preset] 预设文件不存在: {}", file.getPath());
            return false;
        }

        if (replace) {
            if (recipeType.isEmpty()) {
                STRIP_RULES.clear();
                REPLACE_RULES.clear();
            } else {
                STRIP_RULES.remove(recipeType);
                REPLACE_RULES.remove(recipeType);
            }
        }

        boolean filterType = !recipeType.isEmpty();
        try (var r = new java.io.FileReader(file)) {
            var root = com.google.gson.JsonParser.parseReader(r).getAsJsonObject();

            if (root.has("strip")) {
                var stripObj = root.getAsJsonObject("strip");
                for (var typeEntry : stripObj.entrySet()) {
                    String typeId = typeEntry.getKey();
                    if (filterType && !typeId.equals(recipeType)) continue;
                    var arr = typeEntry.getValue().getAsJsonArray();
                    for (var el : arr) {
                        var ruleObj = el.getAsJsonObject();
                        String item = ruleObj.get("item").getAsString();
                        boolean input = ruleObj.get("input").getAsBoolean();
                        boolean fluid = ruleObj.get("fluid").getAsBoolean();
                        String rid = ruleObj.has("recipeId") ? ruleObj.get("recipeId").getAsString() : "";
                        addStripRule(typeId, new StripEntry(item, input, fluid, rid));
                    }
                }
            }

            if (root.has("replace")) {
                var replaceObj = root.getAsJsonObject("replace");
                for (var typeEntry : replaceObj.entrySet()) {
                    String typeId = typeEntry.getKey();
                    if (filterType && !typeId.equals(recipeType)) continue;
                    var arr = typeEntry.getValue().getAsJsonArray();
                    for (var el : arr) {
                        var ruleObj = el.getAsJsonObject();
                        String old = ruleObj.get("old").getAsString();
                        String newItem = ruleObj.get("new").getAsString();
                        boolean oldF = ruleObj.has("oldFluid") && ruleObj.get("oldFluid").getAsBoolean();
                        boolean newF = ruleObj.has("newFluid") && ruleObj.get("newFluid").getAsBoolean();
                        String rid = ruleObj.has("recipeId") ? ruleObj.get("recipeId").getAsString() : "";
                        if (ruleObj.has("circuit") && ruleObj.get("circuit").getAsInt() >= 0) {
                            addReplaceRule(typeId, new ReplaceEntry(old, ruleObj.get("circuit").getAsInt(), rid));
                        } else {
                            int cnt = ruleObj.has("count") ? ruleObj.get("count").getAsInt() : 0;
                            addReplaceRule(typeId, new ReplaceEntry(old, newItem, oldF, newF, rid, cnt, -1));
                        }
                    }
                }
            }

            if (root.has("delete")) {
                var deleteObj = root.getAsJsonObject("delete");
                for (var typeEntry : deleteObj.entrySet()) {
                    String typeId = typeEntry.getKey();
                    if (filterType && !typeId.equals(recipeType)) continue;
                    var arr = typeEntry.getValue().getAsJsonArray();
                    for (var el : arr) {
                        addDeleteRule(typeId, new DeleteEntry(el.getAsString()));
                    }
                }
            }

            LOG.info("[Preset] 已加载预设: {} (replace={}, type={})", presetName, replace, recipeType);
            return true;
        } catch (Exception e) {
            LOG.warn("[Preset] 加载预设失败 [{}]: {}", presetName, e.getMessage());
            return false;
        }
    }

    /** 追加式加载预设 */
    public static boolean loadPreset(String presetName) {
        return loadPreset(presetName, false, "");
    }

    // ====== 从 JS 侧同步配方修改到 GTCEu 注册表 ======

    /**
     * 从 GTCEu 注册表中移除指定配方并同步到客户端。
     * 配合 KubeJS 侧重新注册使用，确保 JS 侧的配方修改即时生效（样板总成等机器下次查找时看到新值）。
     *
     * @param recipeTypeId 配方类型 ID，如 "gtceu:assembler"
     * @param recipeId     配方 ID（完整命名空间形式），如 "dishanhai:my_recipe"
     * @return 是否成功找到并移除
     */
    public static boolean removeAndSync(String recipeTypeId, String recipeId) {
        try {
            var type = GTRegistries.RECIPE_TYPES.get(new ResourceLocation(recipeTypeId));
            if (type == null) return false;
            var lookup = type.getLookup();
            if (lookup == null) return false;
            var branch = lookup.getLookup();
            if (branch == null) return false;

            java.util.List<GTRecipe> allRecipes = new java.util.ArrayList<>();
            branch.getRecipes(true).forEach(r -> { if (r != null) allRecipes.add(r); });

            boolean removed = allRecipes.removeIf(r -> r.getId() != null && r.getId().toString().equals(recipeId));
            if (!removed) {
                LOG.warn("[ModAPI] removeAndSync: 未找到配方 {} (类型 {})", recipeId, recipeTypeId);
                return false;
            }

            lookup.removeAllRecipes();
            for (GTRecipe r : allRecipes) lookup.addRecipe(r);
            invalidatePatternCaches("removeAndSync:" + recipeTypeId);
            com.dishanhai.gt_shanhai.network.RecipeSyncPacket.syncToAll();
            LOG.info("[ModAPI] removeAndSync: 已移除并同步配方 {} (类型 {})", recipeId, recipeTypeId);
            return true;
        } catch (Exception e) {
            LOG.warn("[ModAPI] removeAndSync 失败: {}", e.getMessage());
            return false;
        }
    }

    public static void clearAll() {
        JS_MODIFIERS.clear();
        STRIP_RULES.clear();
        REPLACE_RULES.clear();
        DELETE_RULES.clear();
        saveStripRules();
        saveReplaceRules();
        saveDeleteRules();
        LOG.info("已清除所有配方修饰器、剥离规则、替换规则和删除规则");
    }

    private DShanhaiRecipeModifierAPI() {}
}
