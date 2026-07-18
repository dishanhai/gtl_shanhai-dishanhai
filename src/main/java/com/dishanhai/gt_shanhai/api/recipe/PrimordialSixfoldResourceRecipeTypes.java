package com.dishanhai.gt_shanhai.api.recipe;

import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.registry.GTRegistries;

import net.minecraft.resources.ResourceLocation;

import java.util.function.Function;

public final class PrimordialSixfoldResourceRecipeTypes {

    public static final ResourceLocation LARGE_VOID_PUMP_ID = new ResourceLocation("gtceu:large_void_pump");

    public static GTRecipeType requireLargeVoidPump() {
        return requireResolved(id -> GTRegistries.RECIPE_TYPES.get(id));
    }

    static <T> T requireResolved(Function<ResourceLocation, T> lookup) {
        T resolved = lookup.apply(LARGE_VOID_PUMP_ID);
        if (resolved == null) {
            throw new IllegalStateException("缺少运行时配方类型: " + LARGE_VOID_PUMP_ID);
        }
        return resolved;
    }

    private PrimordialSixfoldResourceRecipeTypes() {}
}
