package com.dishanhai.gt_shanhai.common.machine.primordial;

import com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.chance.logic.ChanceLogic;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.content.ContentModifier;

import java.util.List;
import java.util.Map;

final class PrimordialRecipeOutputAmplifier {

    static GTRecipe apply(GTRecipe recipe, int multiplier) {
        if (recipe == null || multiplier <= 1) {
            return recipe;
        }

        GTRecipe copy = recipe.copy();
        copy.parallels = recipe.parallels;
        copy.ocTier = recipe.ocTier;
        // GTLCore 将 SizedIngredient 提升为 LongIngredient，FluidIngredient 数量原生为 long；
        // 这里必须保留 long 数量路径，不能把大配方输出钳回 Integer.MAX_VALUE。
        ContentModifier modifier = ContentModifier.multiplier(multiplier);
        int fullChance = ChanceLogic.getMaxChancedValue();
        ContentScaler scaler = (capability, content, contentModifier) ->
                content.copy(capability, contentModifier);
        amplifyAndForceFullChance(copy.outputs, fullChance, modifier, scaler);
        amplifyAndForceFullChance(copy.tickOutputs, fullChance, modifier, scaler);
        return copy;
    }

    static void amplifyAndForceFullChance(
            Map<RecipeCapability<?>, List<Content>> contentsMap,
            int fullChance,
            ContentModifier modifier,
            ContentScaler scaler) {
        for (Map.Entry<RecipeCapability<?>, List<Content>> entry : contentsMap.entrySet()) {
            List<Content> contents = entry.getValue();
            for (int i = 0; i < contents.size(); i++) {
                Content content = contents.get(i);
                content.chance = fullChance;
                content.maxChance = fullChance;
                contents.set(i, scaler.scale(entry.getKey(), content, modifier));
            }
        }
    }

    @FunctionalInterface
    interface ContentScaler {

        Content scale(RecipeCapability<?> capability, Content content, ContentModifier modifier);
    }

    private PrimordialRecipeOutputAmplifier() {}
}
