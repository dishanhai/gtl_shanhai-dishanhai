package com.dishanhai.gt_shanhai.client.recipe;

import com.dishanhai.gt_shanhai.mixin.JeiRecipeGuiLogicAccessor;
import com.dishanhai.gt_shanhai.mixin.JeiRecipesGuiAccessor;
import com.gregtechceu.gtceu.integration.jei.recipe.GTRecipeWrapper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import mezz.jei.gui.recipes.IRecipeGuiLogic;
import mezz.jei.gui.recipes.RecipeGuiLogic;
import mezz.jei.gui.recipes.RecipesGui;
import mezz.jei.gui.recipes.lookups.IFocusedRecipes;
import mezz.jei.gui.recipes.lookups.ILookupState;
import mezz.jei.gui.recipes.lookups.StaticFocusedRecipes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ShanhaiJeiRecipeViewSync {

    private ShanhaiJeiRecipeViewSync() {}

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void refreshCachedView(
            IJeiRuntime runtime,
            String recipeTypeId,
            Set<String> targetedRecipeIds,
            List<GTRecipeWrapper> replacements) {
        if (runtime == null || recipeTypeId == null || targetedRecipeIds == null
                || targetedRecipeIds.isEmpty()) return;
        if (!(runtime.getRecipesGui() instanceof RecipesGui recipesGui)) return;

        IRecipeGuiLogic logic = ((JeiRecipesGuiAccessor) (Object) recipesGui)
                .gtShanhai$getRecipeGuiLogic();
        if (!(logic instanceof RecipeGuiLogic recipeGuiLogic)) return;
        ILookupState state = ((JeiRecipeGuiLogicAccessor) (Object) recipeGuiLogic)
                .gtShanhai$getLookupState();
        if (state == null || !hasAffectedCategory(state.getRecipeCategories(), recipeTypeId)) return;

        IFocusGroup focuses = state.getFocuses();
        if (focuses != null && !focuses.isEmpty()) {
            if (!logic.showFocus(focuses)) {
                IRecipeCategory<?> affected = state.getRecipeCategories().stream()
                        .filter(category -> recipeTypeId.equals(category.getRecipeType().getUid().toString()))
                        .findFirst().orElse(null);
                if (affected != null) logic.showCategories(List.of(affected.getRecipeType()));
            }
            return;
        }

        IFocusedRecipes focused = state.getFocusedRecipes();
        if (focused == null || focused.getRecipeCategory() == null
                || !recipeTypeId.equals(focused.getRecipeCategory().getRecipeType().getUid().toString())) {
            return;
        }
        Map<String, GTRecipeWrapper> replacementsById = new LinkedHashMap<>();
        if (replacements != null) {
            for (GTRecipeWrapper wrapper : replacements) {
                if (wrapper != null && wrapper.recipe != null && wrapper.recipe.getId() != null) {
                    replacementsById.put(wrapper.recipe.getId().toString(), wrapper);
                }
            }
        }

        List<Object> updated = new ArrayList<>();
        boolean changed = false;
        for (Object value : focused.getRecipes()) {
            if (value instanceof GTRecipeWrapper wrapper && wrapper.recipe != null
                    && wrapper.recipe.getId() != null
                    && targetedRecipeIds.contains(wrapper.recipe.getId().toString())) {
                changed = true;
                GTRecipeWrapper replacement = replacementsById.get(wrapper.recipe.getId().toString());
                if (replacement != null) updated.add(replacement);
            } else {
                updated.add(value);
            }
        }
        if (changed) {
            logic.showRecipes(new StaticFocusedRecipes(focused.getRecipeCategory(), updated), focuses);
        }
    }

    private static boolean hasAffectedCategory(
            List<IRecipeCategory<?>> categories, String recipeTypeId) {
        if (categories == null || categories.isEmpty()) return false;
        return categories.stream().anyMatch(
                category -> recipeTypeId.equals(category.getRecipeType().getUid().toString()));
    }
}
