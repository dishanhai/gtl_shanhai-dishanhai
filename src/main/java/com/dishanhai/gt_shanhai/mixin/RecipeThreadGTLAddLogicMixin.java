package com.dishanhai.gt_shanhai.mixin;

// Author: dishanhai
import com.dishanhai.gt_shanhai.common.machine.thread.NativeRecipeThreadRuntime;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gtladd.gtladditions.api.machine.logic.GTLAddMultipleRecipesLogic;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import java.math.BigInteger;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = GTLAddMultipleRecipesLogic.class, remap = false)
public abstract class RecipeThreadGTLAddLogicMixin {
    @Shadow protected abstract boolean isEnergyConsumer();

    @ModifyVariable(method = "getMaxParallel", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private long shanhai$limitNativeCandidate(long nativeLimit) {
        return NativeRecipeThreadRuntime.limitParallel(nativeLimit);
    }

    @ModifyExpressionValue(method = "buildFinalWirelessRecipe", at = @At(value = "INVOKE",
            target = "Lcom/gtladd/gtladditions/api/machine/trait/IWirelessNetworkEnergyHandler;getMaxAvailableEnergy()Ljava/math/BigInteger;"))
    private BigInteger shanhai$reserveWirelessBudget(BigInteger nativeBudget) {
        return NativeRecipeThreadRuntime.admissionWirelessBudget(nativeBudget, isEnergyConsumer());
    }
    @ModifyVariable(method = "calculateParallels", at = @At("STORE"), ordinal = 0)
    private long shanhai$partitionParallel(long nativeParallel) {
        return NativeRecipeThreadRuntime.limitParallel(nativeParallel);
    }
    @ModifyVariable(method = "buildFinalNormalRecipe", at = @At("STORE"), ordinal = 0)
    private long shanhai$partitionPower(long nativeVoltage) {
        return NativeRecipeThreadRuntime.admissionVoltage(((RecipeLogic) (Object) this).machine, nativeVoltage, isEnergyConsumer());
    }
    @Inject(method = "getGTRecipe", at = @At("HEAD"), cancellable = true)
    private void shanhai$blockLookup(CallbackInfoReturnable<GTRecipe> cir) {
        var runtime = NativeRecipeThreadRuntime.of((RecipeLogic) (Object) this);
        if (runtime != null && runtime.isFinishing()) cir.setReturnValue(null);
    }
    @Inject(method = "getGTRecipe", at = @At("RETURN"))
    private void shanhai$capturePaid(CallbackInfoReturnable<GTRecipe> cir) {
        var runtime = NativeRecipeThreadRuntime.of((RecipeLogic) (Object) this);
        if (runtime != null) runtime.recordPaidRecipe(cir.getReturnValue());
    }
}
