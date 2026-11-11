package com.dishanhai.gt_shanhai.common.recipe;

import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.lookup.Branch;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Additional recipes for an input key already occupied in the GTCEu lookup.
 * Ordinary lookup keeps its preferred recipe and still allows explicit selection of the others.
 */
public final class RecipeLookupOverflow {
    private static final Map<Branch, Map<ResourceLocation, GTRecipe>> BY_ROOT = new WeakHashMap<>();

    private RecipeLookupOverflow() {}

    public static synchronized void add(Branch root, GTRecipe recipe) {
        BY_ROOT.computeIfAbsent(root, ignored -> new LinkedHashMap<>()).put(recipe.getId(), recipe);
    }

    public static synchronized List<GTRecipe> recipes(Branch root) {
        Map<ResourceLocation, GTRecipe> recipes = BY_ROOT.get(root);
        return recipes == null ? List.of() : List.copyOf(recipes.values());
    }

    public static synchronized boolean hasRecipes(Branch root) {
        return BY_ROOT.containsKey(root);
    }

    public static synchronized void remove(Branch root, Set<String> recipeIds) {
        Map<ResourceLocation, GTRecipe> recipes = BY_ROOT.get(root);
        if (recipes == null) return;
        recipes.keySet().removeIf(id -> recipeIds.contains(id.toString()));
        if (recipes.isEmpty()) BY_ROOT.remove(root);
    }

    public static synchronized void clear(Branch root) {
        BY_ROOT.remove(root);
    }
}
