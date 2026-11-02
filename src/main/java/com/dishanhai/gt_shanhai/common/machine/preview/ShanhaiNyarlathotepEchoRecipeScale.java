package com.dishanhai.gt_shanhai.common.machine.preview;

import com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.content.ContentModifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 山海：只修改回響自身的消耗配方，保留催化劑與輸出，必須在輸入匹配前執行。 */
public final class ShanhaiNyarlathotepEchoRecipeScale {
    public static final String FACTOR_KEY = "sh_nyarlathotep_echo_cost_factor";
    private ShanhaiNyarlathotepEchoRecipeScale() {}

    public static GTRecipe apply(GTRecipe recipe, double factor) {
        if (recipe == null) return null;
        double bounded = Double.isFinite(factor) ? Math.max(1, Math.min(5, factor)) : 1;
        GTRecipe copy = recipe.copy();
        copy.data = recipe.data.copy();
        scale(copy.inputs, bounded);
        scale(copy.tickInputs, bounded);
        copy.duration = Math.max(1, (int) Math.ceil(Math.max(1, recipe.duration) / bounded));
        copy.data.putDouble(FACTOR_KEY, bounded);
        return copy;
    }

    private static void scale(Map<RecipeCapability<?>, List<Content>> inputs, double factor) {
        ContentModifier modifier = ContentModifier.multiplier(factor);
        inputs.replaceAll((capability, contents) -> {
            List<Content> scaled = new ArrayList<>(contents.size());
            for (Content content : contents) scaled.add(content.copy(capability, modifier));
            return scaled;
        });
    }
}
