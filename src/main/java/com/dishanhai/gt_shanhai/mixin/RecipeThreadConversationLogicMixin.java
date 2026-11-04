package com.dishanhai.gt_shanhai.mixin;

// Author: dishanhai
import com.dishanhai.gt_shanhai.common.machine.thread.NativeRecipeThreadRuntime;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gtladd.gtladditions.common.machine.multiblock.controller.ConversationMachine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps the native conversion logic and its afterWorking payment timing. */
@Mixin(targets = "com.gtladd.gtladditions.common.machine.multiblock.controller.ConversationMachine$Companion$ConversationRecipeLogic", remap = false)
public abstract class RecipeThreadConversationLogicMixin {
    @Inject(method = "findAndHandleRecipe", at = @At("HEAD"), cancellable = true)
    private void shanhai$blockRenewal(CallbackInfo ci) {
        var runtime = NativeRecipeThreadRuntime.of((RecipeLogic) (Object) this);
        if (runtime != null && runtime.isFinishing()) ci.cancel();
    }
    @Inject(method = "setupRecipe", at = @At("HEAD"), cancellable = true)
    private void shanhai$blockRenewalSetup(GTRecipe recipe, CallbackInfo ci) {
        var runtime = NativeRecipeThreadRuntime.of((RecipeLogic) (Object) this);
        if (runtime != null && runtime.isFinishing()) ci.cancel();
    }
    @Redirect(method = "handleRecipeWorking", at = @At(value = "INVOKE",
            target = "Lcom/gtladd/gtladditions/common/machine/multiblock/controller/ConversationMachine;onWorking()Z"))
    private boolean shanhai$machineWorkingOnce(ConversationMachine machine) {
        var runtime = NativeRecipeThreadRuntime.of((RecipeLogic) (Object) this);
        return runtime == null ? machine.onWorking() : runtime.onWorking(machine);
    }
}
