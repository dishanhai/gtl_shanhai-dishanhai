package com.dishanhai.gt_shanhai.mixin;

// Author: dishanhai
import com.dishanhai.gt_shanhai.common.machine.thread.NativeRecipeThreadRuntime;
import com.dishanhai.gt_shanhai.common.machine.thread.RecipeThreadPriority;
import com.dishanhai.gt_shanhai.common.machine.thread.RecipeThreadTiming;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gtladd.gtladditions.api.machine.logic.GTLAddMultipleRecipesLogic;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.math.BigInteger;
import org.gtlcore.gtlcore.api.machine.multiblock.ParallelMachine;
import org.gtlcore.gtlcore.common.machine.trait.MultipleRecipesLogic;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = GTLAddMultipleRecipesLogic.class, remap = false)
public abstract class RecipeThreadGTLAddLogicMixin extends MultipleRecipesLogic implements RecipeThreadTiming {
    @Shadow protected abstract boolean isEnergyConsumer();
    @Shadow protected abstract com.gtladd.gtladditions.api.machine.IGTLAddMultiRecipeMachine getLimited();

    protected RecipeThreadGTLAddLogicMixin(ParallelMachine machine) {
        super(machine);
    }

    @Override public long shanhai$estimateRecipeTicks(GTRecipe recipe, long parallelLimit, long voltage) {
        RecipeLogic logic = (RecipeLogic) (Object) this;
        if (logic.machine instanceof com.gtladd.gtladditions.api.machine.IWirelessElectricMultiblockMachine wireless
                && wireless.getWirelessNetworkEnergyHandler() != null) return Math.max(1, getLimited().getLimitedDuration());
        voltage = NativeRecipeThreadRuntime.admissionVoltage(logic.machine, voltage, isEnergyConsumer());
        long parallel = Math.max(1L, org.gtlcore.gtlcore.api.recipe.IParallelLogic.getMaxParallel(logic.machine, recipe, parallelLimit));
        return RecipeThreadPriority.energyTicks(getTotalEuOfRecipe(recipe) * getEuMultiplier(), parallel, voltage,
                getLimited().getLimitedDuration());
    }

    @WrapOperation(method = "calculateParallels", at = @At(value = "INVOKE",
            target = "Lcom/gtladd/gtladditions/api/machine/logic/GTLAddMultipleRecipesLogic;lookupRecipeIterator()Ljava/util/Set;"))
    private java.util.Set<GTRecipe> shanhai$longRecipeFirst(GTLAddMultipleRecipesLogic logic,
                                                           Operation<java.util.Set<GTRecipe>> lookup) {
        return RecipeThreadPriority.select(logic, () -> lookup.call(logic));
    }

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
