package com.dishanhai.gt_shanhai.mixin;

// Author: dishanhai
import com.dishanhai.gt_shanhai.common.machine.thread.NativeRecipeThreadRuntime;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import java.math.BigInteger;
import org.gtlcore.gtlcore.common.machine.trait.MultipleRecipesLogic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = MultipleRecipesLogic.class, priority = 1600, remap = false)
public abstract class RecipeThreadMultipleLogicMixin {
    @Dynamic("Added by GTLAdditions MultipleRecipesLogicMixin")
    @ModifyVariable(method = "gtladditions$getWirelessRecipe", at = @At("STORE"), ordinal = 0, remap = false)
    private long shanhai$partitionWirelessParallel(long nativeParallel) {
        var runtime = NativeRecipeThreadRuntime.of((RecipeLogic) (Object) this);
        // GTLAdd's wireless branch adds additionalThread as int before widening to long.
        if (nativeParallel < 0L && runtime != null && runtime.isAdmitting()) return runtime.allowance(Long.MAX_VALUE);
        return NativeRecipeThreadRuntime.limitParallel(nativeParallel);
    }

    @Dynamic("Added by GTLAdditions MultipleRecipesLogicMixin")
    @ModifyExpressionValue(method = "gtladditions$getWirelessRecipe", at = @At(value = "INVOKE",
            target = "Lcom/gtladd/gtladditions/api/machine/trait/IWirelessNetworkEnergyHandler;getMaxAvailableEnergy()Ljava/math/BigInteger;"), remap = false)
    private BigInteger shanhai$reserveWirelessBudget(BigInteger nativeBudget) {
        return NativeRecipeThreadRuntime.admissionWirelessBudget(nativeBudget, true);
    }

    @Dynamic("Added by GTLAdditions MultipleRecipesLogicMixin")
    @Redirect(method = "handleRecipeWorking", at = @At(value = "INVOKE",
            target = "Lcom/gregtechceu/gtceu/api/machine/feature/IRecipeLogicMachine;onWorking()Z"), remap = false)
    private boolean shanhai$machineWorkingOnce(IRecipeLogicMachine machine) {
        var runtime = NativeRecipeThreadRuntime.of((RecipeLogic) (Object) this);
        return runtime == null ? machine.onWorking() : runtime.onWorking(machine);
    }
    @Inject(method = "getRecipe", at = @At("HEAD"), cancellable = true)
    private void shanhai$blockPaidLookup(CallbackInfoReturnable<GTRecipe> cir) {
        var runtime = NativeRecipeThreadRuntime.of((RecipeLogic) (Object) this);
        if (runtime != null && runtime.isFinishing()) cir.setReturnValue(null);
    }
    @Inject(method = "getRecipe", at = @At("RETURN"))
    private void shanhai$capturePaid(CallbackInfoReturnable<GTRecipe> cir) {
        var runtime = NativeRecipeThreadRuntime.of((RecipeLogic) (Object) this);
        if (runtime != null) runtime.recordPaidRecipe(cir.getReturnValue());
    }
    @ModifyVariable(method = "getRecipe", at = @At("STORE"), ordinal = 1)
    private long shanhai$partitionParallel(long nativeParallel) {
        return NativeRecipeThreadRuntime.limitParallel(nativeParallel);
    }
    @ModifyVariable(method = "getRecipe", at = @At("STORE"), ordinal = 0)
    private long shanhai$partitionPower(long nativeVoltage) {
        return NativeRecipeThreadRuntime.admissionVoltage(((RecipeLogic) (Object) this).machine, nativeVoltage);
    }
}
