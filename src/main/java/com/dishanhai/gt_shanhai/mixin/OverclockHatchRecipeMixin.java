package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.common.machine.part.DShanhaiOverclockHatchMachine;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
import com.gregtechceu.gtceu.api.machine.trait.MachineTrait;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = RecipeLogic.class, remap = false)
public abstract class OverclockHatchRecipeMixin {

    @Shadow
    protected int duration;

    @Inject(method = "setupRecipe", at = @At("RETURN"))
    private void gtShanhai$applyOverclockHatchDuration(GTRecipe recipe, CallbackInfo ci) {
        try {
            if (recipe == null || duration <= 1) return;
            DShanhaiOverclockHatchMachine hatch = gtShanhai$getBestOverclockHatch();
            if (hatch == null) return;

            int newDuration = DShanhaiOverclockHatchMachine.applyDurationDivisor(duration, hatch.getCurrentDivisor());
            if (newDuration >= duration) return;
            duration = newDuration;
            recipe.duration = newDuration;
        } catch (Exception ignored) {}
    }

    @Unique
    private DShanhaiOverclockHatchMachine gtShanhai$getBestOverclockHatch() {
        MetaMachine metaMachine = ((MachineTrait) (Object) this).getMachine();
        if (!(metaMachine instanceof IMultiController controller)) return null;
        if (!controller.isFormed()) return null;

        DShanhaiOverclockHatchMachine best = null;
        long bestDivisor = 1L;
        for (IMultiPart part : controller.getParts()) {
            if (part instanceof DShanhaiOverclockHatchMachine hatch) {
                long divisor = hatch.getCurrentDivisor();
                if (divisor > bestDivisor) {
                    best = hatch;
                    bestDivisor = divisor;
                }
            }
        }
        return best;
    }
}
