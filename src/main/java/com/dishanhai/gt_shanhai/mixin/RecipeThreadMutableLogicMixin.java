package com.dishanhai.gt_shanhai.mixin;

// Author: dishanhai
import com.dishanhai.gt_shanhai.common.machine.thread.NativeRecipeThreadRuntime;
import com.dishanhai.gt_shanhai.common.machine.thread.RecipeThreadPriority;
import com.dishanhai.gt_shanhai.common.machine.thread.RecipeThreadTiming;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gtladd.gtladditions.api.machine.logic.MutableRecipesLogic;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.math.BigInteger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = MutableRecipesLogic.class, remap = false)
public abstract class RecipeThreadMutableLogicMixin implements RecipeThreadTiming {
    @Shadow protected abstract double getEuMultiplier();
    @Shadow protected abstract long getRecipeEut(GTRecipe recipe);

    @Override public long shanhai$estimateRecipeTicks(GTRecipe recipe, long parallelLimit, long voltage) {
        RecipeLogic logic = (RecipeLogic) (Object) this;
        if (!((MutableRecipesLogic<?>) (Object) this).isMultipleRecipeMode()) {
            return RecipeThreadPriority.standardTicks(recipe.duration, getRecipeEut(recipe),
                    NativeRecipeThreadRuntime.admissionVoltage(logic.machine, voltage));
        }
        if (logic.machine instanceof com.gtladd.gtladditions.api.machine.IWirelessElectricMultiblockMachine wireless
                && wireless.getWirelessNetworkEnergyHandler() != null) return 20L;
        voltage = NativeRecipeThreadRuntime.admissionVoltage(logic.machine, voltage);
        long parallel = Math.max(1L, org.gtlcore.gtlcore.api.recipe.IParallelLogic.getMaxParallel(logic.machine, recipe, parallelLimit));
        return RecipeThreadPriority.energyTicks(getRecipeEut(recipe) * (double) recipe.duration * getEuMultiplier(),
                parallel, voltage, 20);
    }

    @WrapOperation(method = "calculateParallels", at = @At(value = "INVOKE",
            target = "Lcom/gtladd/gtladditions/api/machine/logic/MutableRecipesLogic;lookupRecipeSet()Ljava/util/Set;"))
    private java.util.Set<GTRecipe> shanhai$longRecipeFirst(MutableRecipesLogic<?> logic,
                                                           Operation<java.util.Set<GTRecipe>> lookup) {
        return RecipeThreadPriority.select(logic, () -> lookup.call(logic));
    }
    @ModifyVariable(method = "calculateParallel", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private long shanhai$limitNativeCandidate(long nativeLimit) {
        return NativeRecipeThreadRuntime.limitParallel(nativeLimit);
    }
    @ModifyExpressionValue(method = "buildFinalWirelessRecipe", at = @At(value = "INVOKE",
            target = "Lcom/gtladd/gtladditions/api/machine/trait/IWirelessNetworkEnergyHandler;getMaxAvailableEnergy()Ljava/math/BigInteger;"))
    private BigInteger shanhai$reserveWirelessBudget(BigInteger nativeBudget) {
        return NativeRecipeThreadRuntime.admissionWirelessBudget(nativeBudget, true);
    }
    @ModifyVariable(method = "calculateParallels", at = @At("STORE"), ordinal = 0)
    private long shanhai$partitionParallel(long nativeParallel) {
        return NativeRecipeThreadRuntime.limitParallel(nativeParallel);
    }
    @ModifyVariable(method = "buildFinalNormalRecipe", at = @At("STORE"), ordinal = 0)
    private long shanhai$partitionPower(long nativeVoltage) {
        return NativeRecipeThreadRuntime.admissionVoltage(((RecipeLogic) (Object) this).machine, nativeVoltage);
    }
    @Inject(method = "getRecipe", at = @At("HEAD"), cancellable = true)
    private void shanhai$blockLookup(CallbackInfoReturnable<GTRecipe> cir) {
        var runtime = NativeRecipeThreadRuntime.of((RecipeLogic) (Object) this);
        if (runtime != null && runtime.isFinishing()) cir.setReturnValue(null);
    }
    @Inject(method = "getRecipe", at = @At("RETURN"))
    private void shanhai$capturePaid(CallbackInfoReturnable<GTRecipe> cir) {
        var runtime = NativeRecipeThreadRuntime.of((RecipeLogic) (Object) this);
        if (runtime != null && ((MutableRecipesLogic<?>) (Object) this).isMultipleRecipeMode()) {
            runtime.recordPaidRecipe(cir.getReturnValue());
        }
    }
    @Redirect(method = "handleMultipleRecipeWorking", at = @At(value = "INVOKE",
            target = "Lcom/gregtechceu/gtceu/api/machine/feature/IRecipeLogicMachine;onWorking()Z"))
    private boolean shanhai$machineWorkingOnce(IRecipeLogicMachine machine) {
        var runtime = NativeRecipeThreadRuntime.of((RecipeLogic) (Object) this);
        return runtime == null ? machine.onWorking() : runtime.onWorking(machine);
    }
}
