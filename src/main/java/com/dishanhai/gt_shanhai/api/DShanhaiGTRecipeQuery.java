package com.dishanhai.gt_shanhai.api;

import com.gregtechceu.gtceu.api.GTValues;
import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.recipe.RecipeHelper;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.ingredient.SizedIngredient;
import com.gregtechceu.gtceu.api.registry.GTRegistries;

import com.lowdragmc.lowdraglib.side.fluid.FluidStack;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.*;
import java.util.stream.Collectors;

/**
 * GT 配方查询引擎 — 直接从 GTRegistries.RECIPE_TYPES 读取运行时注册表。
 * 替代 KubeJS 版山海_gt配方查询API.js，消除反射 + Java.loadClass 的不稳定性。
 *
 * <p>KubeJS 使用:
 * <pre>
 * var results = DShanhaiGTRecipeQuery.findRecipesByInput('gtceu:iron_ingot');
 * var types = DShanhaiGTRecipeQuery.getRecipeTypes();
 * DShanhaiGTRecipeQuery.printToPlayer(player, 'gtceu:iron_ingot');
 * </pre>
 */
public class DShanhaiGTRecipeQuery {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("GTQuery");

    // ====== 缓存 ======
    private static volatile boolean cacheReady = false;
    private static final List<RecipeTypeEntry> TYPE_CACHE = new ArrayList<>();
    private static final Map<String, List<RecipeEntry>> RECIPE_CACHE = new LinkedHashMap<>();
    private static int totalRecipes = 0;

    /** 单个配方类型的摘要 */
    public static class RecipeTypeEntry {
        public final String fieldName;
        public final String recipeId;
        public final int inputItems;
        public final int outputItems;
        public final int inputFluids;
        public final int outputFluids;

        public RecipeTypeEntry(GTRecipeType type) {
            this.recipeId = type.registryName.toString();
            this.fieldName = type.registryName.getPath().toUpperCase(Locale.ROOT) + "_RECIPES";
            this.inputItems = type.getMaxInputs(ItemRecipeCapability.CAP);
            this.outputItems = type.getMaxOutputs(ItemRecipeCapability.CAP);
            this.inputFluids = type.getMaxInputs(FluidRecipeCapability.CAP);
            this.outputFluids = type.getMaxOutputs(FluidRecipeCapability.CAP);
        }
    }

    /** 单个配方的详细信息 */
    public static class RecipeEntry {
        public final String typeId;
        public final String recipeId;
        public final long eut;
        public final int duration;
        public final List<String> itemInputs;
        public final List<String> itemOutputs;
        public final List<String> fluidInputs;
        public final List<String> fluidOutputs;

        public RecipeEntry(String typeId, GTRecipe recipe) {
            this.typeId = typeId;
            var id = recipe.getId();
            this.recipeId = id != null ? id.toString() : "unknown";

            // EU/t
            long inputEu = RecipeHelper.getInputEUt(recipe);
            long outputEu = RecipeHelper.getOutputEUt(recipe);
            this.eut = inputEu != 0 ? inputEu : outputEu;
            this.duration = recipe.duration;

            // 物品输入
            this.itemInputs = extractItemStacks(recipe, true);
            this.itemOutputs = extractItemStacks(recipe, false);
            this.fluidInputs = extractFluidStacks(recipe, true);
            this.fluidOutputs = extractFluidStacks(recipe, false);
        }

        private static String itemId(ItemStack stack) {
            var id = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem());
            return id != null ? id.toString() : "unknown";
        }

        private static String itemDisplayName(ItemStack stack) {
            try { return stack.getHoverName().getString(); } catch (Exception e) { return ""; }
        }

        private static String formatItemWithName(ItemStack stack, int count) {
            return count + "x " + itemId(stack) + " (§f" + itemDisplayName(stack) + "§7)";
        }

        private static List<String> extractItemStacks(GTRecipe recipe, boolean isInput) {
            List<String> result = new ArrayList<>();
            Map<com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability<?>, List<Content>> contents = isInput ? recipe.inputs : recipe.outputs;
            var cap = ItemRecipeCapability.CAP;
            var list = contents.get(cap);
            if (list == null) return result;
            for (Content content : list) {
                if (content.content instanceof SizedIngredient si) {
                    var stacks = si.getItems();
                    if (stacks.length > 0) {
                        result.add(formatItemWithName(stacks[0], si.getAmount()));
                    }
                } else if (content.content instanceof ItemStack is) {
                    result.add(formatItemWithName(is, is.getCount()));
                }
            }
            return result;
        }

        private static List<String> extractFluidStacks(GTRecipe recipe, boolean isInput) {
            List<String> result = new ArrayList<>();
            Map<com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability<?>, List<Content>> contents = isInput ? recipe.inputs : recipe.outputs;
            var cap = FluidRecipeCapability.CAP;
            var list = contents.get(cap);
            if (list == null) return result;
            for (Content content : list) {
                if (content.content instanceof FluidStack fs) {
                    long amount = fs.getAmount();
                    var id = net.minecraftforge.registries.ForgeRegistries.FLUIDS.getKey(fs.getFluid());
                    result.add(amount + "mb " + (id != null ? id.toString() : "unknown"));
                }
            }
            return result;
        }
    }

    // ====== 构建缓存 ======

    /** 从 GTRegistries.RECIPE_TYPES 构建运行时缓存 */
    public static synchronized void buildCache() {
        if (cacheReady) return;
        long start = System.currentTimeMillis();
        TYPE_CACHE.clear();
        RECIPE_CACHE.clear();
        totalRecipes = 0;

        for (GTRecipeType type : GTRegistries.RECIPE_TYPES) {
            if (type == null) continue;
            String typeId = type.registryName.toString();
            TYPE_CACHE.add(new RecipeTypeEntry(type));

            List<RecipeEntry> recipes = new ArrayList<>();
            var lookup = type.getLookup();
            if (lookup != null) {
                try {
                    var branch = lookup.getLookup();
                    if (branch != null) {
                        branch.getRecipes(true).forEach(recipe -> {
                            if (recipe != null) {
                                recipes.add(new RecipeEntry(typeId, recipe));
                            }
                        });
                    }
                } catch (Exception e) {
                    LOG.warn("遍历配方类型 {} 失败: {}", typeId, e.getMessage());
                }
            }
            RECIPE_CACHE.put(typeId, recipes);
            totalRecipes += recipes.size();
            if (typeId.contains("assembler")) {
                LOG.info("[GTQuery] {}: {} 个配方", typeId, recipes.size());
                if (!recipes.isEmpty()) {
                    recipes.forEach(r -> LOG.info("  配方 {}: 输入={} 输出={}", r.recipeId, r.itemInputs, r.itemOutputs));
                }
            }
        }

        cacheReady = true;
        long elapsed = System.currentTimeMillis() - start;
        LOG.info("配方缓存构建完成: {} 类型, {} 配方 ({}ms)", TYPE_CACHE.size(), totalRecipes, elapsed);
    }

    /** 重置缓存（下次调用自动重建） */
    public static synchronized void resetCache() {
        cacheReady = false;
        TYPE_CACHE.clear();
        RECIPE_CACHE.clear();
        totalRecipes = 0;
    }

    // ====== 查询方法 ======

    /** 获取所有配方类型列表 */
    public static List<RecipeTypeEntry> getRecipeTypes() {
        ensureCache();
        return TYPE_CACHE;
    }

    /** 按配方类型 ID 查询配方（如 "gtceu:assembler"） */
    public static List<RecipeEntry> getRecipesByType(String typeId) {
        ensureCache();
        var result = RECIPE_CACHE.get(typeId);
        if (result != null) return result;
        // 模糊匹配
        for (var entry : RECIPE_CACHE.entrySet()) {
            if (entry.getKey().contains(typeId) || typeId.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return Collections.emptyList();
    }

    /** 按输入物品 ID 搜索配方 */
    public static List<RecipeEntry> findRecipesByInput(String itemId) {
        ensureCache();
        String search = itemId.toLowerCase(Locale.ROOT);
        List<RecipeEntry> results = new ArrayList<>();
        for (var entry : RECIPE_CACHE.entrySet()) {
            for (RecipeEntry recipe : entry.getValue()) {
                for (String input : recipe.itemInputs) {
                    if (input.toLowerCase(Locale.ROOT).contains(search)) {
                        results.add(recipe);
                        break;
                    }
                }
                if (itemId.length() > 0) {
                    for (String fi : recipe.fluidInputs) {
                        if (fi.toLowerCase(Locale.ROOT).contains(search)) {
                            if (!results.contains(recipe)) results.add(recipe);
                            break;
                        }
                    }
                }
            }
        }
        return results;
    }

    /** 按输出物品 ID 搜索配方 */
    public static List<RecipeEntry> findRecipesByOutput(String itemId) {
        ensureCache();
        String search = itemId.toLowerCase(Locale.ROOT);
        List<RecipeEntry> results = new ArrayList<>();
        for (var entry : RECIPE_CACHE.entrySet()) {
            for (RecipeEntry recipe : entry.getValue()) {
                boolean matched = false;
                for (String output : recipe.itemOutputs) {
                    if (output.toLowerCase(Locale.ROOT).contains(search)) {
                        matched = true;
                        break;
                    }
                }
                if (!matched) {
                    for (String fo : recipe.fluidOutputs) {
                        if (fo.toLowerCase(Locale.ROOT).contains(search)) {
                            matched = true;
                            break;
                        }
                    }
                }
                if (matched) results.add(recipe);
            }
        }
        return results;
    }

    /** 获取缓存统计 */
    public static Map<String, Object> getStats() {
        ensureCache();
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalTypes", TYPE_CACHE.size());
        stats.put("totalRecipes", totalRecipes);
        stats.put("cacheReady", cacheReady);
        Map<String, Integer> breakdown = new LinkedHashMap<>();
        for (var entry : RECIPE_CACHE.entrySet()) {
            breakdown.put(entry.getKey(), entry.getValue().size());
        }
        stats.put("typeBreakdown", breakdown);
        return stats;
    }

    // ====== 工具 ======

    private static void ensureCache() {
        if (!cacheReady) buildCache();
    }

    /** 向玩家发送格式化配方信息 */
    public static void printToPlayer(net.minecraft.server.level.ServerPlayer player, String itemId) {
        var byInput = findRecipesByInput(itemId);
        var byOutput = findRecipesByOutput(itemId);
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "§6===== 搜索: §f" + itemId + " §6====="));
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "§e作为输入: §f" + byInput.size() + " 个配方"));
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "§e作为输出: §f" + byOutput.size() + " 个配方"));

        int dc = Math.min(byOutput.size(), 10);
        for (int i = 0; i < dc; i++) {
            var r = byOutput.get(i);
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "§7" + (i + 1) + ". §7[" + r.typeId + "] §e" + r.eut + "EU/t §a" + r.duration + "t"));
        }
        if (byOutput.size() > 10) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "§e... 还有 " + (byOutput.size() - 10) + " 个配方未显示"));
        }
    }
}
