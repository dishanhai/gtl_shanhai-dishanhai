package com.dishanhai.gt_shanhai.common.item;

import com.gregtechceu.gtceu.api.recipe.GTRecipe;

import net.minecraft.resources.ResourceLocation;

public final class PatternRecipeExecutionGuard {

    private static final ResourceLocation AUXILIARY_IO_RECIPE_TYPE =
            new ResourceLocation("gtceu", "dummy");

    private PatternRecipeExecutionGuard() {
    }

    public static boolean isAuxiliaryIORecipe(GTRecipe recipe) {
        return recipe != null && recipe.recipeType != null
                && isAuxiliaryIORecipeTypeId(recipe.recipeType.registryName);
    }

    static boolean isAuxiliaryIORecipeTypeId(ResourceLocation recipeTypeId) {
        return AUXILIARY_IO_RECIPE_TYPE.equals(recipeTypeId);
    }
}
