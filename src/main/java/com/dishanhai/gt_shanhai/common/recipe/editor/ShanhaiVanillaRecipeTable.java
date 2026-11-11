package com.dishanhai.gt_shanhai.common.recipe.editor;

import com.dishanhai.gt_shanhai.common.misc.RecipeManagerReflectionUtil;
import com.gregtechceu.gtceu.api.registry.GTRegistries;
import com.google.common.collect.ImmutableMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class ShanhaiVanillaRecipeTable {

    private ShanhaiVanillaRecipeTable() {}

    public static Map<ResourceLocation, Recipe<?>> read(MinecraftServer server, String typeId) {
        RecipeManagerReflectionUtil.RecipeManagerMaps maps = resolve(server);
        RecipeType<?> targetType = resolveType(typeId);
        if (maps == null || targetType == null) return Map.of();
        return Collections.unmodifiableMap(new LinkedHashMap<>(
                maps.recipesMap().getOrDefault(targetType, Map.of())));
    }

    public static void replaceType(
            MinecraftServer server,
            String typeId,
            Iterable<? extends Recipe<?>> recipes) {
        RecipeManagerReflectionUtil.RecipeManagerMaps maps = resolve(server);
        if (maps == null) return;

        RecipeType<?> targetType = resolveType(typeId);
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
        updateByName(byName, targetType, old == null ? Set.of() : old.keySet(), replacement);
        install(server, maps, byType, byName);
    }

    /**
     * Replaces or deletes individual recipes inside one type. The rest of that
     * type stays in the existing map instead of being rebuilt from snapshots.
     */
    public static void replaceEntries(
            MinecraftServer server,
            String typeId,
            Map<ResourceLocation, Recipe<?>> upserts,
            Set<ResourceLocation> removals) {
        RecipeManagerReflectionUtil.RecipeManagerMaps maps = resolve(server);
        if (maps == null) return;

        RecipeType<?> targetType = resolveType(typeId);
        if (targetType == null) return;
        Map<ResourceLocation, Recipe<?>> existing = maps.recipesMap().getOrDefault(targetType, Map.of());

        Map<ResourceLocation, Recipe<?>> next = new LinkedHashMap<>(existing);
        if (removals != null) {
            for (ResourceLocation id : removals) next.remove(id);
        }
        if (upserts != null) next.putAll(upserts);

        Map<RecipeType<?>, Map<ResourceLocation, Recipe<?>>> byType =
                new LinkedHashMap<>(maps.recipesMap());
        byType.put(targetType, next);

        Map<ResourceLocation, Recipe<?>> byName = new LinkedHashMap<>(maps.byNameMap());
        updateByName(byName, targetType, removals, upserts);
        install(server, maps, byType, byName);
    }

    static void updateByName(
            Map<ResourceLocation, Recipe<?>> byName,
            RecipeType<?> targetType,
            Set<ResourceLocation> removals,
            Map<ResourceLocation, Recipe<?>> upserts) {
        // GTCEu proxy recipes share their source recipe's ID. Keep the original
        // type's globally indexed recipe while maintaining the GT copy by type.
        if (removals != null) {
            for (ResourceLocation id : removals) {
                byName.computeIfPresent(id, (key, current) -> current.getType() == targetType ? null : current);
            }
        }
        if (upserts != null) {
            upserts.forEach((id, recipe) -> byName.compute(id, (key, current) ->
                    current == null || current.getType() == targetType ? recipe : current));
        }
    }

    private static void install(
            MinecraftServer server,
            RecipeManagerReflectionUtil.RecipeManagerMaps maps,
            Map<RecipeType<?>, Map<ResourceLocation, Recipe<?>>> byType,
            Map<ResourceLocation, Recipe<?>> byName) {
        try {
            maps.recipesField().set(server.getRecipeManager(), ImmutableMap.copyOf(byType));
            maps.byNameField().set(server.getRecipeManager(), ImmutableMap.copyOf(byName));
            ShanhaiRecipeTableHook.invalidateCaches(server);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("unable to replace RecipeManager recipe tables", e);
        }
    }

    private static RecipeManagerReflectionUtil.RecipeManagerMaps resolve(MinecraftServer server) {
        if (server == null) return null;
        return RecipeManagerReflectionUtil.resolve(server.getRecipeManager());
    }

    private static RecipeType<?> resolveType(String typeId) {
        ResourceLocation id = typeId == null ? null : ResourceLocation.tryParse(typeId);
        return id == null ? null : GTRegistries.RECIPE_TYPES.get(id);
    }
}
