package com.dishanhai.gt_shanhai.common.recipe;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.api.recipe.DShanhaiRecipeTypes;
import com.dishanhai.gt_shanhai.common.misc.RecipeManagerReflectionUtil;
import com.dishanhai.gt_shanhai.config.DShanhaiConfig;
import com.google.common.collect.ImmutableMap;
import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.ingredient.SizedIngredient;
import com.gregtechceu.gtceu.api.registry.GTRegistries;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 禁用铸币工厂时，机器本身不注册；这里负责把已经进入数据包或 KubeJS 名表的对应配方拿掉。
 * 配方类型仍保留，避免缓存数据包报未知类型、KubeJS 脚本中断。
 */
public final class CoinForgeLoadGate {
    public static final ResourceLocation MACHINE_ID =
            new ResourceLocation("gt_shanhai", "primordial_coin_forge");
    private static final String TYPE_PATH = "coin_forge";

    private CoinForgeLoadGate() {}

    public static void stripNameMap(Map<ResourceLocation, Recipe<?>> recipes) {
        if (!DShanhaiConfig.isCoinForgeDisabled() || recipes == null || recipes.isEmpty()) {
            return;
        }
        int before = recipes.size();
        try {
            recipes.entrySet().removeIf(entry -> rejects(entry.getValue()));
        } catch (UnsupportedOperationException e) {
            GTDishanhaiMod.LOGGER.warn("[铸币工厂] 配方名表不可修改，改为加载后移除: {}", e.getMessage());
            return;
        }
        int removed = before - recipes.size();
        if (removed > 0) {
            GTDishanhaiMod.LOGGER.info("[铸币工厂] 已从配方名表移除 {} 条配方", removed);
        }
    }

    public static void stripLoaded(RecipeManager manager) {
        if (!DShanhaiConfig.isCoinForgeDisabled() || manager == null) {
            return;
        }
        int removedFromManager = stripManager(manager);
        int removedFromLookup = stripLookups();
        if (removedFromManager > 0) {
            GTDishanhaiMod.LOGGER.info("[铸币工厂] 已从配方表移除 {} 条配方", removedFromManager);
        }
        if (removedFromLookup > 0) {
            GTDishanhaiMod.LOGGER.info("[铸币工厂] 已从查找表移除 {} 条配方", removedFromLookup);
        }
    }

    static boolean rejects(Recipe<?> recipe) {
        if (!(recipe instanceof GTRecipe gt)) {
            return false;
        }
        return isCoinForgeType(gt.getType()) || outputsMachine(gt);
    }

    private static int stripManager(RecipeManager manager) {
        RecipeManagerReflectionUtil.RecipeManagerMaps maps = RecipeManagerReflectionUtil.resolve(manager);
        if (maps == null) {
            return 0;
        }
        List<ResourceLocation> removeIds = new ArrayList<>();
        for (Map.Entry<ResourceLocation, Recipe<?>> entry : maps.byNameMap().entrySet()) {
            if (rejects(entry.getValue())) {
                removeIds.add(entry.getKey());
            }
        }
        if (removeIds.isEmpty()) {
            return 0;
        }
        Map<RecipeType<?>, Map<ResourceLocation, Recipe<?>>> byType = new LinkedHashMap<>();
        for (Map.Entry<RecipeType<?>, Map<ResourceLocation, Recipe<?>>> entry : maps.recipesMap().entrySet()) {
            Map<ResourceLocation, Recipe<?>> next = new LinkedHashMap<>(entry.getValue());
            for (ResourceLocation id : removeIds) {
                next.remove(id);
            }
            byType.put(entry.getKey(), next);
        }
        Map<ResourceLocation, Recipe<?>> byName = new LinkedHashMap<>(maps.byNameMap());
        for (ResourceLocation id : removeIds) {
            byName.remove(id);
        }
        try {
            maps.recipesField().set(manager, ImmutableMap.copyOf(byType));
            maps.byNameField().set(manager, ImmutableMap.copyOf(byName));
        } catch (IllegalAccessException e) {
            GTDishanhaiMod.LOGGER.warn("[铸币工厂] 无法写回配方表: {}", e.getMessage());
            return 0;
        }
        return removeIds.size();
    }

    private static int stripLookups() {
        int removed = 0;
        for (GTRecipeType type : GTRegistries.RECIPE_TYPES) {
            if (type == null || type.getLookup() == null || type.getLookup().getLookup() == null) {
                continue;
            }
            List<GTRecipe> recipes = type.getLookup().getLookup().getRecipes(false).toList();
            if (recipes.isEmpty()) {
                continue;
            }
            if (isCoinForgeType(type)) {
                type.getLookup().removeAllRecipes();
                removed += recipes.size();
                continue;
            }
            List<GTRecipe> kept = new ArrayList<>();
            int rejected = 0;
            for (GTRecipe recipe : recipes) {
                if (outputsMachine(recipe)) {
                    rejected++;
                } else {
                    kept.add(recipe);
                }
            }
            if (rejected == 0) {
                continue;
            }
            type.getLookup().removeAllRecipes();
            for (GTRecipe recipe : kept) {
                type.getLookup().addRecipe(recipe);
            }
            removed += rejected;
        }
        if (DShanhaiRecipeTypes.COIN_FORGE != null
                && DShanhaiRecipeTypes.COIN_FORGE.getLookup() != null) {
            DShanhaiRecipeTypes.COIN_FORGE.getLookup().removeAllRecipes();
        }
        return removed;
    }

    private static boolean isCoinForgeType(RecipeType<?> type) {
        if (!(type instanceof GTRecipeType gt) || gt.registryName == null) {
            return false;
        }
        return TYPE_PATH.equals(gt.registryName.getPath());
    }

    private static boolean outputsMachine(GTRecipe recipe) {
        return containsMachine(recipe.outputs) || containsMachine(recipe.tickOutputs);
    }

    private static boolean containsMachine(Map<RecipeCapability<?>, List<Content>> contents) {
        if (contents == null) {
            return false;
        }
        List<Content> items = contents.get(ItemRecipeCapability.CAP);
        if (items == null) {
            return false;
        }
        for (Content content : items) {
            if (content == null || content.content == null) {
                continue;
            }
            Object value = content.content;
            if (value instanceof SizedIngredient sized) {
                for (ItemStack stack : sized.getItems()) {
                    if (isMachine(stack)) return true;
                }
            } else if (value instanceof Ingredient ingredient) {
                for (ItemStack stack : ingredient.getItems()) {
                    if (isMachine(stack)) return true;
                }
            } else if (value instanceof ItemStack stack && isMachine(stack)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isMachine(ItemStack stack) {
        return stack != null && !stack.isEmpty()
                && MACHINE_ID.equals(ForgeRegistries.ITEMS.getKey(stack.getItem()));
    }
}
