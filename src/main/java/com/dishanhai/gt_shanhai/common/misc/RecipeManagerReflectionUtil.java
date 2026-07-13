package com.dishanhai.gt_shanhai.common.misc;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * RecipeManager 内部两张配方表（按类型分组表 + 按 id 全量表）的反射定位工具。
 * 从 {@link MekanismFurnaceRecipeStripper} 抽出来复用，供"删配方"和"插配方"两类场景共享同一套反射逻辑。
 */
public final class RecipeManagerReflectionUtil {
    private RecipeManagerReflectionUtil() {}

    @SuppressWarnings("unchecked")
    public static RecipeManagerMaps resolve(RecipeManager manager) {
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

    public record RecipeManagerMaps(Field recipesField, Field byNameField,
            Map<RecipeType<?>, Map<ResourceLocation, Recipe<?>>> recipesMap,
            Map<ResourceLocation, Recipe<?>> byNameMap) {
    }
}
