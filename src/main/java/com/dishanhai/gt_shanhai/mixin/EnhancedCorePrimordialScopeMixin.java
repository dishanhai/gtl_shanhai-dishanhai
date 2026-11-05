package com.dishanhai.gt_shanhai.mixin;

// Author: dishanhai
import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialThreadedRecipeLogic;
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableElectricMultiblockMachine;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "com.gtl.enhancedcore.common.recipe.iv.IvMachineScope", remap = false)
public abstract class EnhancedCorePrimordialScopeMixin {
    @Inject(method = "crossRecipeEnabled(Ljava/lang/Object;)Z", at = @At("HEAD"), cancellable = true)
    private static void shanhai$ownExecutor(Object machine, CallbackInfoReturnable<Boolean> cir) {
        if (machine instanceof WorkableElectricMultiblockMachine workable
                && workable.getRecipeLogic() instanceof PrimordialThreadedRecipeLogic) cir.setReturnValue(false);
    }

    @Inject(method = "crossRecipeEnabled(Lcom/gregtechceu/gtceu/api/machine/multiblock/WorkableElectricMultiblockMachine;)Z",
            at = @At("HEAD"), cancellable = true)
    private static void shanhai$ownWorkableExecutor(WorkableElectricMultiblockMachine machine,
            CallbackInfoReturnable<Boolean> cir) {
        if (machine.getRecipeLogic() instanceof PrimordialThreadedRecipeLogic) cir.setReturnValue(false);
    }
}
