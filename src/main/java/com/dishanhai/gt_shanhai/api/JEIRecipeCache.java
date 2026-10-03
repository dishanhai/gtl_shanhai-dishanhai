package com.dishanhai.gt_shanhai.api;

import com.gregtechceu.gtceu.integration.jei.recipe.GTRecipeWrapper;
import mezz.jei.api.recipe.RecipeType;

import java.util.Collections;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    public static void clear(RecipeType<?> type) {
        if (type != null) REGISTERED.remove(type);
    }

    public static void clearAll() {
        REGISTERED.clear();
    }

    /**
     * GTCEu/gtlcore 可能分批向同一个 JEI 类型注册配方；按完整 recipe ID 只接收一次。
     * 配方对象本身仍保留在 JEI 中，源码 RecipeManager 不会被修改。
     */
    public static List<GTRecipeWrapper> filterUnseen(RecipeType<?> type, List<GTRecipeWrapper> wrappers) {
        if (wrappers == null || wrappers.isEmpty()) return Collections.emptyList();
        Set<String> seen = new LinkedHashSet<>();
        List<GTRecipeWrapper> registered = REGISTERED.get(type);
        if (registered != null) {
            for (GTRecipeWrapper wrapper : registered) {
                if (wrapper != null && wrapper.recipe != null && wrapper.recipe.getId() != null) {
                    seen.add(wrapper.recipe.getId().toString());
                }
            }
        }
        List<GTRecipeWrapper> result = new ArrayList<>();
        for (GTRecipeWrapper wrapper : wrappers) {
            if (wrapper == null || wrapper.recipe == null || wrapper.recipe.getId() == null
                    || seen.add(wrapper.recipe.getId().toString())) {
                result.add(wrapper);
            }
        }
        return result;
    }

    public static synchronized void append(RecipeType<?> type, List<GTRecipeWrapper> wrappers) {
        if (wrappers == null || wrappers.isEmpty()) return;
        List<GTRecipeWrapper> merged = new ArrayList<>(REGISTERED.getOrDefault(type, Collections.emptyList()));
        merged.addAll(filterUnseen(type, wrappers));
        REGISTERED.put(type, List.copyOf(merged));
    }
}
