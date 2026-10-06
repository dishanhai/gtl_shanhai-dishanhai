package com.dishanhai.gt_shanhai.common.recipe.editor;

import com.dishanhai.gt_shanhai.common.misc.RecipeManagerReflectionUtil;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.google.common.collect.ImmutableMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ShanhaiVanillaRecipeTable {

    private ShanhaiVanillaRecipeTable() {}

    public static Map<ResourceLocation, Recipe<?>> read(MinecraftServer server, String typeId) {
        RecipeManagerReflectionUtil.RecipeManagerMaps maps = resolve(server);
        if (maps == null) return Map.of();
        for (Map.Entry<RecipeType<?>, Map<ResourceLocation, Recipe<?>>> entry : maps.recipesMap().entrySet()) {
            if (matchesType(entry.getKey(), typeId, entry.getValue())) {
                return Map.copyOf(entry.getValue());
            }
        }
        return Map.of();
    }

    public static void replaceType(
            MinecraftServer server,
            String typeId,
            Iterable<? extends Recipe<?>> recipes) {
        RecipeManagerReflectionUtil.RecipeManagerMaps maps = resolve(server);
        if (maps == null) return;

        RecipeType<?> targetType = null;
        for (Map.Entry<RecipeType<?>, Map<ResourceLocation, Recipe<?>>> entry : maps.recipesMap().entrySet()) {
            if (matchesType(entry.getKey(), typeId, entry.getValue())) {
                targetType = entry.getKey();
                break;
            }
        }
        if (targetType == null) return;

        Map<ResourceLocation, Recipe<?>> replacement = new LinkedHashMap<>();
        for (Recipe<?> recipe : recipes) {
            if (recipe == null || recipe.getId() == null) continue;
            replacement.put(recipe.getId(), recipe);
        }

        Map<RecipeType<?>, Map<ResourceLocation, Recipe<?>>> byType =
                new LinkedHashMap<>(maps.recipesMap());
        Map<ResourceLocation, Recipe<?>> old = byType.put(targetType, replacement);

        Map<ResourceLocation, Recipe<?>> byName = new LinkedHashMap<>(maps.byNameMap());
        if (old != null) {
            for (ResourceLocation id : old.keySet()) byName.remove(id);
        }
        byName.putAll(replacement);

        try {
            maps.recipesField().set(server.getRecipeManager(), ImmutableMap.copyOf(byType));
            maps.byNameField().set(server.getRecipeManager(), ImmutableMap.copyOf(byName));
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("unable to replace RecipeManager recipe tables", e);
        }
    }

    private static RecipeManagerReflectionUtil.RecipeManagerMaps resolve(MinecraftServer server) {
        if (server == null) return null;
        return RecipeManagerReflectionUtil.resolve(server.getRecipeManager());
    }

    private static boolean matchesType(
            RecipeType<?> type,
            String typeId,
            Map<ResourceLocation, Recipe<?>> recipes) {
        if (typeId == null) return false;
        for (Recipe<?> recipe : recipes.values()) {
            if (recipe instanceof GTRecipe gt
                    && gt.recipeType != null
                    && gt.recipeType.registryName != null
                    && typeId.equals(gt.recipeType.registryName.toString())) {
                return true;
            }
        }
        return type.getClass().getName().equals(typeId);
    }
}
