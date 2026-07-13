package com.dishanhai.gt_shanhai.common.misc;

import com.google.common.collect.ImmutableMap;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MekanismFurnaceRecipeStripper {
    private static final Logger LOG = LoggerFactory.getLogger("MekanismFurnaceRecipeStripper");

    public static void strip(MinecraftServer server) {
        if (server == null) return;
        if (!ModList.get().isLoaded("mekanism")) return;

        RecipeManager manager = server.getRecipeManager();
        RecipeManagerReflectionUtil.RecipeManagerMaps maps = RecipeManagerReflectionUtil.resolve(manager);
        if (maps == null) {
            LOG.warn("[山海] 未能定位 RecipeManager 配方表字段，跳过 Mekanism 熔炉/高炉配方移除");
            return;
        }

        Map<RecipeType<?>, Map<ResourceLocation, Recipe<?>>> recipesMap = maps.recipesMap();
        Map<ResourceLocation, Recipe<?>> byNameMap = maps.byNameMap();
        if (recipesMap == null || byNameMap == null || recipesMap.isEmpty() || byNameMap.isEmpty()) return;

        // 只有 SMELTING/BLASTING 会被 shouldRemove() 命中，只需要重建这两个子表，
        // 不必像之前那样无条件遍历+拷贝全部配方类型和整份 byNameMap（大型整合包配方上万条，进世界时同步阻塞明显）。
        Set<ResourceLocation> removedIds = new LinkedHashSet<>();
        Map<RecipeType<?>, Map<ResourceLocation, Recipe<?>>> filteredByType = new LinkedHashMap<>();
        for (RecipeType<?> type : List.of(RecipeType.SMELTING, RecipeType.BLASTING)) {
            Map<ResourceLocation, Recipe<?>> source = recipesMap.get(type);
            if (source == null || source.isEmpty()) continue;
            Map<ResourceLocation, Recipe<?>> filtered = new LinkedHashMap<>();
            for (Map.Entry<ResourceLocation, Recipe<?>> recipeEntry : source.entrySet()) {
                Recipe<?> recipe = recipeEntry.getValue();
                if (shouldRemove(type, recipe, server)) {
                    removedIds.add(recipeEntry.getKey());
                    continue;
                }
                filtered.put(recipeEntry.getKey(), recipe);
            }
            if (filtered.size() != source.size()) {
                filteredByType.put(type, ImmutableMap.copyOf(filtered));
            }
        }

        if (removedIds.isEmpty()) return;

        Map<RecipeType<?>, Map<ResourceLocation, Recipe<?>>> rewrittenRecipes = new LinkedHashMap<>(recipesMap);
        rewrittenRecipes.putAll(filteredByType);

        Map<ResourceLocation, Recipe<?>> rewrittenByName = new LinkedHashMap<>(byNameMap);
        rewrittenByName.keySet().removeAll(removedIds);

        try {
            maps.recipesField().set(manager, ImmutableMap.copyOf(rewrittenRecipes));
            maps.byNameField().set(manager, ImmutableMap.copyOf(rewrittenByName));
        } catch (IllegalAccessException e) {
            LOG.warn("[山海] 写回 RecipeManager 配方表失败，跳过 Mekanism 熔炉/高炉配方移除", e);
            return;
        }
        LOG.info("[山海] Java侧移除 {} 个 Mekanism 熔炉/高炉配方", removedIds.size());
    }

    private static boolean shouldRemove(RecipeType<?> type, Recipe<?> recipe, MinecraftServer server) {
        if (recipe == null) return false;
        if (type != RecipeType.SMELTING && type != RecipeType.BLASTING) return false;

        ItemStack result;
        try {
            result = recipe.getResultItem(server.registryAccess());
        } catch (Throwable ignored) {
            return false;
        }

        if (result.isEmpty()) return false;

        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(result.getItem());
        return itemId != null && "mekanism".equals(itemId.getNamespace());
    }
}
