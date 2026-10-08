package com.dishanhai.gt_shanhai.mixin;

import appeng.api.networking.crafting.ICraftingPlan;
import appeng.crafting.CraftingCalculation;
import appeng.crafting.CraftingPlan;
import appeng.crafting.inv.CraftingSimulationState;

import com.dishanhai.gt_shanhai.common.ae2.FeasiblePatternDemotions;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 每次尝试开始时清掉上一轮的让位记录，计划生成后再按对象身份挂到这份计划上。
 */
public final class FeasiblePatternDemotionMixins {

    private FeasiblePatternDemotionMixins() {}

    @Mixin(value = CraftingCalculation.class, remap = false)
    public static class Calculation {

        @Inject(method = "runCraftAttempt", at = @At("HEAD"), remap = false)
        private void gtShanhai$resetDemotions(boolean simulate, long amount, CallbackInfo ci) {
            FeasiblePatternDemotions.beginAttempt();
        }
    }

    @Mixin(value = CraftingSimulationState.class, remap = false)
    public static class PlanBuild {

        @Inject(method = "buildCraftingPlan", at = @At("RETURN"), remap = false)
        private static void gtShanhai$attachDemotions(CraftingSimulationState state, CraftingCalculation calculation,
                long calculatedAmount, CallbackInfoReturnable<CraftingPlan> cir) {
            ICraftingPlan plan = cir.getReturnValue();
            FeasiblePatternDemotions.attach(plan);
        }
    }
}
