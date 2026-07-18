package com.dishanhai.gt_shanhai.api.recipe;

import com.gregtechceu.gtceu.api.recipe.GTRecipeType;

import net.minecraft.resources.ResourceLocation;

public final class PrimordialMyriadRecipeTypes {

    public static final ResourceLocation TIER_2_ID =
            new ResourceLocation("gtceu:primordial_myriad_ascension_tier_2");
    public static final ResourceLocation TIER_1_ID =
            new ResourceLocation("gtceu:primordial_myriad_ascension_tier_1");

    public static GTRecipeType requireTier2() {
        return DShanhaiRecipeTypes.PRIMORDIAL_MYRIAD_ASCENSION_TIER_2;
    }

    public static GTRecipeType requireTier1() {
        return DShanhaiRecipeTypes.PRIMORDIAL_MYRIAD_ASCENSION_TIER_1;
    }

    private PrimordialMyriadRecipeTypes() {}
}
