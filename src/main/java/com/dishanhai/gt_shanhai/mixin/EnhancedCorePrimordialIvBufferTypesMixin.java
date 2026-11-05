package com.dishanhai.gt_shanhai.mixin;

// Author: dishanhai
import com.dishanhai.gt_shanhai.integration.enhancedcore.EnhancedCorePrimordialRecipeTypes;
import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialThreadedRecipeLogic;
import com.dishanhai.gt_shanhai.common.machine.primordial.module.crafting.PrimordialMolecularAssemblerModuleLogic;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableElectricMultiblockMachine;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.llamalad7.mixinextras.sugar.Local;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "com.gtl.enhancedcore.common.recipe.iv.IvBuffers", remap = false)
public abstract class EnhancedCorePrimordialIvBufferTypesMixin {
    @Inject(method = "targetController", at = @At("HEAD"), cancellable = true)
    private static void shanhai$processingControllers(Object machine, CallbackInfoReturnable<Boolean> cir) {
        if (machine instanceof WorkableElectricMultiblockMachine workable
                && workable.getRecipeLogic() instanceof PrimordialMolecularAssemblerModuleLogic) cir.setReturnValue(false);
    }

    @Inject(method = "genericCloudMixedInputs", at = @At("HEAD"), cancellable = true)
    private static void shanhai$serialInputs(WorkableElectricMultiblockMachine machine,
            CallbackInfoReturnable<Boolean> cir) {
        if (machine.getRecipeLogic() instanceof PrimordialThreadedRecipeLogic logic
                && !(logic instanceof PrimordialMolecularAssemblerModuleLogic)) cir.setReturnValue(false);
    }

    @Redirect(method = {"push(Lorg/gtlcore/gtlcore/common/machine/multiblock/part/ae/MEPatternBufferPartMachine;Lappeng/api/crafting/IPatternDetails;[Lappeng/api/stacks/KeyCounter;)Z",
            "lambda$push$21", "lambda$cloudControllers$6"}, at = @At(value = "INVOKE", target =
            "Lcom/gregtechceu/gtceu/api/machine/MultiblockMachineDefinition;getRecipeTypes()[Lcom/gregtechceu/gtceu/api/recipe/GTRecipeType;"))
    private static GTRecipeType[] shanhai$selectedTypes(MultiblockMachineDefinition definition,
            @Local WorkableElectricMultiblockMachine machine) {
        return EnhancedCorePrimordialRecipeTypes.selected(machine, definition);
    }
}
