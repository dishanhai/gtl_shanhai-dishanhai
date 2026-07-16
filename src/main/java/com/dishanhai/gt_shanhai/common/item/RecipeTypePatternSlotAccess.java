package com.dishanhai.gt_shanhai.common.item;

import com.gregtechceu.gtceu.api.recipe.GTRecipe;

import net.minecraft.world.item.ItemStack;

public interface RecipeTypePatternSlotAccess {

    String gtShanhai$getPatternRecipeTypeId(int slot);

    GTRecipe gtShanhai$getPatternRecipe(int slot);

    boolean gtShanhai$slotAllowsRecipe(int slot, GTRecipe recipe);

    int gtShanhai$getPatternSlotCount();

    ItemStack gtShanhai$getPatternStack(int slot);
}
