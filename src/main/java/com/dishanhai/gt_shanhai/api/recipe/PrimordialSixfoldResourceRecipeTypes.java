package com.dishanhai.gt_shanhai.api.recipe;

import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.registry.GTRegistries;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;

import java.util.function.Function;

public final class PrimordialSixfoldResourceRecipeTypes {

    public static final ResourceLocation LARGE_VOID_PUMP_ID = new ResourceLocation("gtceu:large_void_pump");

    public static GTRecipeType findLargeVoidPumpIfExtendLoaded() {
        return findLargeVoidPumpIfExtendLoaded(
                ModList.get().isLoaded("gtl_extend"),
                id -> GTRegistries.RECIPE_TYPES.get(id));
    }

    static <T> T findLargeVoidPumpIfExtendLoaded(boolean extendLoaded, Function<ResourceLocation, T> lookup) {
        if (!extendLoaded) return null;

        T resolved = lookup.apply(LARGE_VOID_PUMP_ID);
        if (resolved == null) {
            throw new IllegalStateException("gtl_extend 已加载但缺少运行时配方类型: " + LARGE_VOID_PUMP_ID);
        }
        return resolved;
    }

    private PrimordialSixfoldResourceRecipeTypes() {}
}
