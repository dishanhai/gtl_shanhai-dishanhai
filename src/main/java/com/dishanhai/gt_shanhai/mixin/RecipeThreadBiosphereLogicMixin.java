package com.dishanhai.gt_shanhai.mixin;

// Author: dishanhai
import com.dishanhai.gt_shanhai.common.machine.thread.NativeRecipeThreadRuntime;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** The biosphere overrides working without invoking its native superclass. */
@Mixin(targets = "com.gtladd.gtladditions.common.machine.multiblock.controller.bs.BiosphereIIIModule$BiosphereModuleRecipeLogic", remap = false)
public abstract class RecipeThreadBiosphereLogicMixin {
    @Redirect(method = "handleRecipeWorking", at = @At(value = "INVOKE",
            target = "Lcom/gregtechceu/gtceu/api/machine/feature/IRecipeLogicMachine;onWorking()Z"))
    private boolean shanhai$machineWorkingOnce(IRecipeLogicMachine machine) {
        var runtime = NativeRecipeThreadRuntime.of((RecipeLogic) (Object) this);
        return runtime == null ? machine.onWorking() : runtime.onWorking(machine);
    }
}
