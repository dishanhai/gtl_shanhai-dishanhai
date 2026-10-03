package com.dishanhai.gt_shanhai.common.recipe;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.registry.GTRegistries;
import com.gregtechceu.gtceu.data.recipe.builder.GTRecipeBuilder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Java-side equivalent of the dynamic star-core-stripper KubeJS recipes.
 * The item registry is scanned at server start so mod additions remain included.
 */
public final class DShanhaiDynamicStarCoreStripperRecipes {
    private static final ResourceLocation RECIPE_TYPE_ID = new ResourceLocation("gtceu", "star_core_stripper");
    private static final ResourceLocation TIME_REVERSAL_PROTOCOL = new ResourceLocation("dishanhai", "time_reversal_protocol");
    private static final int MAX_EU = Integer.MAX_VALUE;
    private static final int DURATION = 200;

    private DShanhaiDynamicStarCoreStripperRecipes() {}

    public static void register() {
        GTRecipeType type = GTRegistries.RECIPE_TYPES.get(RECIPE_TYPE_ID);
        Item protocol = ForgeRegistries.ITEMS.getValue(TIME_REVERSAL_PROTOCOL);
        if (type == null || protocol == null) {
            GTDishanhaiMod.LOGGER.warn("[StarCoreStripper] 配方类型或时间逆转协议不存在，跳过动态配方");
            return;
        }

        registerAllItems(type, protocol);
        registerWorldFragments(type, protocol);
        registerOreSelections(type, protocol);
    }

    private static void registerAllItems(GTRecipeType type, Item protocol) {
        List<ItemStack> outputs = new ArrayList<>();
        collectTagStacks("forge:raw_materials", 1024, outputs);
        collectTagStacks("forge:ores", 1024, outputs);
        if (outputs.isEmpty()) return;
        addRecipe(type, "star_core_stripper_infinite_minerals", protocol, 1, outputs, true);
    }

    private static void registerWorldFragments(GTRecipeType type, Item protocol) {
        List<ItemStack> outputs = new ArrayList<>();
        for (ResourceLocation id : ForgeRegistries.ITEMS.getKeys()) {
            if ("gtlcore".equals(id.getNamespace()) && id.getPath().startsWith("world_fragments_")) {
                Item item = ForgeRegistries.ITEMS.getValue(id);
                if (item != null) outputs.add(new ItemStack(item, 64));
            }
        }
        if (outputs.isEmpty()) return;
        addRecipe(type, "world_fragments", protocol, 2, outputs, true);
    }

    private static void registerOreSelections(GTRecipeType type, Item protocol) {
        if (!DShanhaiRecipeEnabledCondition.isEnabled(
                new ResourceLocation("dishanhai", "star_core_stripper_ores"), false,
                DShanhaiRecipeEnabledCondition.readConfig())) {
            return;
        }

        Map<String, List<ResourceLocation>> suffixBuckets = new LinkedHashMap<>();
        for (ResourceLocation id : ForgeRegistries.ITEMS.getKeys()) {
            String path = id.getPath();
            if (path.endsWith("_dust") || path.endsWith("_ingot")
                    || path.endsWith("_crystal") || path.endsWith("_gem")) {
                suffixBuckets.computeIfAbsent(id.getNamespace(), ignored -> new ArrayList<>()).add(id);
            }
        }

        List<ItemStack> outputs = new ArrayList<>();
        for (Item ore : ForgeRegistries.ITEMS.tags()
                .getTag(ItemTags.create(new ResourceLocation("forge", "ores")))) {
            ResourceLocation oreId = ForgeRegistries.ITEMS.getKey(ore);
            if (oreId == null || !oreId.getPath().endsWith("_ore")) continue;
            String mineral = oreId.getPath().substring(0, oreId.getPath().length() - 4);
            List<ResourceLocation> candidates = suffixBuckets.get(oreId.getNamespace());
            ResourceLocation selected = selectOreOutput(oreId, mineral, candidates);
            if (selected != null) {
                Item item = ForgeRegistries.ITEMS.getValue(selected);
                if (item != null) outputs.add(new ItemStack(item, 64));
            }
        }
        if (!outputs.isEmpty()) addRecipe(type, "star_core_stripper_ores", protocol, 3, outputs, false);
    }

    private static ResourceLocation selectOreOutput(ResourceLocation oreId, String mineral,
                                                    List<ResourceLocation> candidates) {
        if (candidates != null) {
            for (ResourceLocation id : candidates) {
                if (!id.toString().contains(mineral)) continue;
                if (isExcluded(id)) continue;
                String path = id.getPath();
                if (path.startsWith(mineral + "_")) return id;
                if (path.contains("_" + mineral) && !path.endsWith("_" + mineral + "_dust")) return id;
            }
        }
        ResourceLocation vanilla = new ResourceLocation(oreId.getNamespace(), mineral);
        if ("minecraft".equals(oreId.getNamespace())
                && ForgeRegistries.ITEMS.getValue(vanilla) != null) return vanilla;
        return null;
    }

    private static boolean isExcluded(ResourceLocation id) {
        String text = id.toString();
        if (text.contains("_raw") || text.contains("_ore")) return true;
        for (String prefix : new String[]{"pure_", "impure_", "small_", "tiny_", "refined_", "crushed_", "centrifuged_"}) {
            if (text.contains(prefix)) return true;
        }
        return false;
    }

    private static void collectTagStacks(String tagId, int count, List<ItemStack> output) {
        for (Item item : ForgeRegistries.ITEMS.tags()
                .getTag(ItemTags.create(new ResourceLocation(tagId)))) {
            if (item != null) output.add(new ItemStack(item, count));
        }
    }

    private static void addRecipe(GTRecipeType type, String path, Item protocol, int circuit,
                                  List<ItemStack> outputs, boolean enabledByDefault) {
        ResourceLocation recipeId = new ResourceLocation("dishanhai", path);
        if (!enabledByDefault && !DShanhaiRecipeEnabledCondition.isEnabled(
                recipeId, false, DShanhaiRecipeEnabledCondition.readConfig())) return;
        GTRecipeBuilder builder = type.recipeBuilder(recipeId)
                .notConsumable(protocol)
                .circuitMeta(circuit);
        for (ItemStack output : outputs) builder.outputItems(output);
        GTRecipe recipe = builder.EUt(MAX_EU).duration(DURATION).buildRawRecipe();
        type.getLookup().addRecipe(recipe);
        GTDishanhaiMod.LOGGER.info("[StarCoreStripper] 动态注册 {}，输出 {} 种物品", recipeId, outputs.size());
    }
}
