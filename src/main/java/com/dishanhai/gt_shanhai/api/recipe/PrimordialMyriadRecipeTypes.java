package com.dishanhai.gt_shanhai.api.recipe;

import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.registry.GTRegistries;

import net.minecraft.resources.ResourceLocation;

import java.util.function.Function;

public final class PrimordialMyriadRecipeTypes {

    public static final ResourceLocation TIER_2_ID =
            new ResourceLocation("gtceu:primordial_myriad_ascension_tier_2");
    public static final ResourceLocation TIER_1_ID =
            new ResourceLocation("gtceu:primordial_myriad_ascension_tier_1");

    public static GTRecipeType requireTier2() {
        return require(TIER_2_ID);
    }

    public static GTRecipeType requireTier1() {
        return require(TIER_1_ID);
    }

    private static GTRecipeType require(ResourceLocation id) {
        return requireResolved(id, recipeId -> GTRegistries.RECIPE_TYPES.get(recipeId));
    }

    static <T> T requireResolved(ResourceLocation id, Function<ResourceLocation, T> lookup) {
        T resolved = lookup.apply(id);
        if (resolved == null) {
            throw new IllegalStateException("缺少启动期配方类型: " + id);
        }
        return resolved;
    }

    private PrimordialMyriadRecipeTypes() {}
}
