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

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MekanismFurnaceRecipeStripper {
    private static final Logger LOG = LoggerFactory.getLogger("MekanismFurnaceRecipeStripper");

    public static void strip(MinecraftServer server) {
        if (server == null) return;
        if (!ModList.get().isLoaded("mekanism")) return;

        RecipeManager manager = server.getRecipeManager();
        RecipeManagerMaps maps = resolveRecipeManagerMaps(manager);
        if (maps == null) {
            LOG.warn("[山海] 未能定位 RecipeManager 配方表字段，跳过 Mekanism 熔炉/高炉配方移除");
            return;
        }

        Map<RecipeType<?>, Map<ResourceLocation, Recipe<?>>> recipesMap = maps.recipesMap;
        Map<ResourceLocation, Recipe<?>> byNameMap = maps.byNameMap;
        if (recipesMap == null || byNameMap == null || recipesMap.isEmpty() || byNameMap.isEmpty()) return;

        Map<RecipeType<?>, Map<ResourceLocation, Recipe<?>>> rewrittenRecipes = new LinkedHashMap<>();
        Map<ResourceLocation, Recipe<?>> rewrittenByName = new LinkedHashMap<>();
        int removed = 0;

        for (Map.Entry<RecipeType<?>, Map<ResourceLocation, Recipe<?>>> typeEntry : recipesMap.entrySet()) {
            RecipeType<?> type = typeEntry.getKey();
            Map<ResourceLocation, Recipe<?>> source = typeEntry.getValue();
            Map<ResourceLocation, Recipe<?>> filtered = new LinkedHashMap<>();
            if (source != null && !source.isEmpty()) {
                for (Map.Entry<ResourceLocation, Recipe<?>> recipeEntry : source.entrySet()) {
                    Recipe<?> recipe = recipeEntry.getValue();
                    if (shouldRemove(type, recipe, server)) {
                        removed++;
                        continue;
                    }
                    filtered.put(recipeEntry.getKey(), recipe);
                }
            }
            rewrittenRecipes.put(type, ImmutableMap.copyOf(filtered));
        }

        for (Map.Entry<ResourceLocation, Recipe<?>> entry : byNameMap.entrySet()) {
            Recipe<?> recipe = entry.getValue();
            if (shouldRemove(recipe != null ? recipe.getType() : null, recipe, server)) continue;
            rewrittenByName.put(entry.getKey(), recipe);
        }

        if (removed <= 0) return;

        try {
            maps.recipesField.set(manager, ImmutableMap.copyOf(rewrittenRecipes));
            maps.byNameField.set(manager, ImmutableMap.copyOf(rewrittenByName));
        } catch (IllegalAccessException e) {
            LOG.warn("[山海] 写回 RecipeManager 配方表失败，跳过 Mekanism 熔炉/高炉配方移除", e);
            return;
        }
        LOG.info("[山海] Java侧移除 {} 个 Mekanism 熔炉/高炉配方", removed);
    }

    @SuppressWarnings("unchecked")
    private static RecipeManagerMaps resolveRecipeManagerMaps(RecipeManager manager) {
        if (manager == null) return null;

        List<Field> mapFields = new ArrayList<>();
        Field[] fields = RecipeManager.class.getDeclaredFields();
        for (Field field : fields) {
            if (!Map.class.isAssignableFrom(field.getType())) continue;
            field.setAccessible(true);
            mapFields.add(field);
        }

        Field recipesField = null;
        Field byNameField = null;
        Map<RecipeType<?>, Map<ResourceLocation, Recipe<?>>> recipesMap = null;
        Map<ResourceLocation, Recipe<?>> byNameMap = null;

        for (Field field : mapFields) {
            Object value = readField(field, manager);
            if (!(value instanceof Map<?, ?> map)) continue;
            if (recipesField == null && looksLikeRecipesMap(map)) {
                recipesField = field;
                recipesMap = (Map<RecipeType<?>, Map<ResourceLocation, Recipe<?>>>) value;
            } else if (byNameField == null && looksLikeByNameMap(map)) {
                byNameField = field;
                byNameMap = (Map<ResourceLocation, Recipe<?>>) value;
            }
        }

        if ((recipesField == null || byNameField == null) && mapFields.size() >= 2) {
            Field first = mapFields.get(0);
            Field second = mapFields.get(1);
            Object firstValue = readField(first, manager);
            Object secondValue = readField(second, manager);
            if (firstValue instanceof Map<?, ?> && secondValue instanceof Map<?, ?>) {
                if (recipesField == null) {
                    recipesField = first;
                    recipesMap = (Map<RecipeType<?>, Map<ResourceLocation, Recipe<?>>>) firstValue;
                }
                if (byNameField == null) {
                    byNameField = second;
                    byNameMap = (Map<ResourceLocation, Recipe<?>>) secondValue;
                }
            }
        }

        if (recipesField == null || byNameField == null || recipesMap == null || byNameMap == null) return null;
        return new RecipeManagerMaps(recipesField, byNameField, recipesMap, byNameMap);
    }

    private static Object readField(Field field, Object target) {
        try {
            return field.get(target);
        } catch (IllegalAccessException ignored) {
            return null;
        }
    }

    private static boolean looksLikeRecipesMap(Map<?, ?> map) {
        if (map.isEmpty()) return false;
        Map.Entry<?, ?> entry = map.entrySet().iterator().next();
        return entry.getKey() instanceof RecipeType<?> && entry.getValue() instanceof Map<?, ?>;
    }

    private static boolean looksLikeByNameMap(Map<?, ?> map) {
        if (map.isEmpty()) return false;
        Map.Entry<?, ?> entry = map.entrySet().iterator().next();
        return entry.getKey() instanceof ResourceLocation && entry.getValue() instanceof Recipe<?>;
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

    private record RecipeManagerMaps(Field recipesField, Field byNameField,
            Map<RecipeType<?>, Map<ResourceLocation, Recipe<?>>> recipesMap,
            Map<ResourceLocation, Recipe<?>> byNameMap) {
    }
}
