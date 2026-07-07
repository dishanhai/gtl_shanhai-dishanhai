package com.dishanhai.gt_shanhai.api;

import com.gregtechceu.gtceu.integration.jei.recipe.GTRecipeWrapper;
import mezz.jei.api.recipe.RecipeType;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JEI 配方包装器缓存——供 JEIRecipeListMixin 写入、RecipeSyncPacket 读取。
 */
public class JEIRecipeCache {

    private static final Map<RecipeType<?>, List<GTRecipeWrapper>> REGISTERED = new ConcurrentHashMap<>();

    public static List<GTRecipeWrapper> get(RecipeType<?> type) {
        return REGISTERED.getOrDefault(type, Collections.emptyList());
    }

    public static void put(RecipeType<?> type, List<GTRecipeWrapper> wrappers) {
        REGISTERED.put(type, List.copyOf(wrappers));
    }
}
