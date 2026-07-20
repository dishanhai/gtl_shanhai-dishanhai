package com.dishanhai.gt_shanhai.common.item;

import appeng.api.stacks.GenericStack;

import com.gregtechceu.gtceu.api.recipe.GTRecipe;

import net.minecraft.world.item.ItemStack;

public interface RecipeTypePatternSlotAccess {

    String gtShanhai$getPatternRecipeTypeId(int slot);

    GTRecipe gtShanhai$getPatternRecipe(int slot);

    default GTRecipe gtShanhai$getPatternRecipe(int slot, GenericStack[] availableCatalystInputs) {
        return gtShanhai$getPatternRecipe(slot);
    }

    default GenericStack[] gtShanhai$getPatternInferenceInputs() {
        return new GenericStack[0];
    }

    boolean gtShanhai$slotAllowsRecipe(int slot, GTRecipe recipe);

    int gtShanhai$getPatternSlotCount();

    ItemStack gtShanhai$getPatternStack(int slot);
}
