package com.dishanhai.gt_shanhai.common.machine.thread;

// Author: dishanhai
import com.gregtechceu.gtceu.api.recipe.GTRecipe;

/** 山海：只估算加工 tick，不執行 modifier、概率、庫存或電網交易。 */
public interface RecipeThreadTiming {
    long shanhai$estimateRecipeTicks(GTRecipe recipe, long parallel, long voltage);
}
