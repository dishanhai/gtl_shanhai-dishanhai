package com.dishanhai.gt_shanhai.mixin;

// Author: dishanhai
import com.dishanhai.gt_shanhai.integration.enhancedcore.EnhancedCorePrimordialContext;
import com.gregtechceu.gtceu.api.capability.recipe.IRecipeCapabilityHolder;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import org.gtlcore.gtlcore.api.recipe.IParallelLogic;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(value = IParallelLogic.class, remap = false, priority = 1300)
public interface EnhancedCorePrimordialParallelMixin {
    @Inject(method = "getMaxParallel", at = @At("HEAD"), cancellable = true)
    private static void shanhai$orderParallel(IRecipeCapabilityHolder holder, GTRecipe recipe,
            long limit, CallbackInfoReturnable<Long> cir) {
        var context = EnhancedCorePrimordialContext.forMachine(holder);
        if (context != null) cir.setReturnValue(context.maxParallel(recipe, limit));
    }

    @Inject(method = "getMinParallel", at = @At("HEAD"), cancellable = true)
    private static void shanhai$orderOutput(IRecipeCapabilityHolder holder, GTRecipe recipe,
            long limit, CallbackInfoReturnable<Long> cir) {
        if (EnhancedCorePrimordialContext.forMachine(holder) != null) cir.setReturnValue(limit);
    }
}
