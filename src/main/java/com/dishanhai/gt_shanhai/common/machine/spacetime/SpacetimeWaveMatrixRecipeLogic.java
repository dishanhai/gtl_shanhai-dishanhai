package com.dishanhai.gt_shanhai.common.machine.spacetime;

import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.content.ContentModifier;

import com.gtladd.gtladditions.api.machine.logic.GTLAddMultipleWirelessRecipesLogic;
import com.gtladd.gtladditions.api.machine.wireless.GTLAddWirelessWorkableElectricMultipleRecipesMachine;

import com.dishanhai.gt_shanhai.api.recipe.DShanhaiRecipeTypes;

import java.util.List;
import java.util.Map;

public class SpacetimeWaveMatrixRecipeLogic extends GTLAddMultipleWirelessRecipesLogic {

    private static final int OUTPUT_MULTIPLIER = 15;

    public SpacetimeWaveMatrixRecipeLogic(GTLAddWirelessWorkableElectricMultipleRecipesMachine machine) {
        super(machine);
    }

    @Override
    public int getMultipleThreads() {
        return 2147483647;
    }

    @Override
    protected boolean checkRecipe(GTRecipe recipe) {
        if (recipe.recipeType == DShanhaiRecipeTypes.SPACETIME_DISTORTION) {
            suppressChanceInputs(recipe.inputs);
            suppressChanceInputs(recipe.tickInputs);
        }
        return super.checkRecipe(recipe);
    }

    @Override
    public void setupRecipe(GTRecipe recipe) {
        GTRecipe modified = applySpacetimeEffects(recipe);
        super.setupRecipe(modified);
    }

    private static GTRecipe applySpacetimeEffects(GTRecipe recipe) {
        ContentModifier modifier = ContentModifier.multiplier(OUTPUT_MULTIPLIER);
        GTRecipe copy = recipe.copy(modifier, false);
        forceFullChance(copy.outputs);
        forceFullChance(copy.tickOutputs);
        return copy;
    }

    private static void suppressChanceInputs(Map<?, List<Content>> contentsMap) {
        for (List<Content> contents : contentsMap.values()) {
            for (Content content : contents) {
                if (content.chance > 0 && content.chance < 10000) {
                    content.chance = 0;
                }
            }
        }
    }

    private static void forceFullChance(Map<?, List<Content>> contentsMap) {
        for (List<Content> contents : contentsMap.values()) {
            for (Content content : contents) {
                content.chance = 10000;
                content.maxChance = 10000;
            }
        }
    }
}
