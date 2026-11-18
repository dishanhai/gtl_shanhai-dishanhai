package com.dishanhai.gt_shanhai.api.recipe;

import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.data.recipe.builder.GTRecipeBuilder;

public final class PrimordialMyriadRecipeData {

    public static final String DISABLE_BONUS_KEY = "sh_disable_primordial_myriad_bonus";

    private PrimordialMyriadRecipeData() {}

    /** 在配方构建时写入万象增产免疫标记。 */
    public static GTRecipeBuilder disableBonus(GTRecipeBuilder builder) {
        return builder.addData(DISABLE_BONUS_KEY, true);
    }

    public static boolean isBonusDisabled(GTRecipe recipe) {
        return recipe.data != null && recipe.data.getBoolean(DISABLE_BONUS_KEY);
    }
}
