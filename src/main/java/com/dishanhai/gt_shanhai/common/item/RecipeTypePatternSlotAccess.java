package com.dishanhai.gt_shanhai.common.item;

import com.gregtechceu.gtceu.api.recipe.GTRecipe;

public interface RecipeTypePatternSlotAccess {

    String gtShanhai$getPatternRecipeTypeId(int slot);

    GTRecipe gtShanhai$getPatternRecipe(int slot);

    boolean gtShanhai$slotAllowsRecipe(int slot, GTRecipe recipe);
}
