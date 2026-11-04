package com.dishanhai.gt_shanhai.mixin;

// Author: dishanhai
import com.dishanhai.gt_shanhai.api.machine.IRecipeThreadDisplay;
import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialThreadedRecipeLogic;
import com.dishanhai.gt_shanhai.common.machine.thread.NativeRecipeThreadHost;
import com.dishanhai.gt_shanhai.common.machine.thread.NativeRecipeThreadRuntime;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Native execution adapter; priority also covers GTLCore's RecipeLogic overwrites. */
@Mixin(value = RecipeLogic.class, priority = 1600, remap = false)
public abstract class RecipeThreadLogicMixin implements NativeRecipeThreadHost, IRecipeThreadDisplay {
    @Unique private NativeRecipeThreadRuntime shanhai$threads;

    @Override public NativeRecipeThreadRuntime shanhai$recipeThreads() {
        if (shanhai$threads == null) shanhai$threads = new NativeRecipeThreadRuntime((RecipeLogic) (Object) this);
        return shanhai$threads;
    }
    @Unique private NativeRecipeThreadRuntime shanhai$nativeThreads() {
        return (Object) this instanceof PrimordialThreadedRecipeLogic ? null : shanhai$recipeThreads();
    }
    @Override public CompoundTag getRecipeThreadDisplay(int first, int maximum) {
        return shanhai$recipeThreads().getRecipeThreadDisplay(first, maximum);
    }
    @Override public int getActiveRecipeThreadCount() { return shanhai$recipeThreads().getActiveRecipeThreadCount(); }
    @Override public boolean isRecipeThreadDisplayAvailable() { return shanhai$recipeThreads().isRecipeThreadDisplayAvailable(); }
    @Override public void onThreadInputsChanged() { shanhai$recipeThreads().onThreadInputsChanged(); }

    @Inject(method = "serverTick", at = @At("HEAD"), cancellable = true)
    private void shanhai$tick(CallbackInfo ci) {
        var runtime = shanhai$nativeThreads();
        if (runtime != null && runtime.tick()) ci.cancel();
    }

    @Inject(method = "setStatus", at = @At("HEAD"), cancellable = true)
    private void shanhai$setStatus(RecipeLogic.Status status, CallbackInfo ci) {
        var runtime = shanhai$nativeThreads();
        if (runtime != null && runtime.interceptStatus(status)) ci.cancel();
    }

    @Inject(method = "interruptRecipe", at = @At("HEAD"), cancellable = true)
    private void shanhai$interrupt(CallbackInfo ci) {
        var runtime = shanhai$nativeThreads();
        if (runtime != null && runtime.interceptInterrupt()) ci.cancel();
    }

    @Inject(method = {"resetRecipeLogic", "inValid"}, at = @At("HEAD"), cancellable = true)
    private void shanhai$preserveInvalid(CallbackInfo ci) {
        var runtime = shanhai$nativeThreads();
        if (runtime != null && runtime.preserveInvalidation()) ci.cancel();
    }

    @Inject(method = "findAndHandleRecipe", at = @At("HEAD"), cancellable = true)
    private void shanhai$blockFinishLookup(CallbackInfo ci) {
        var runtime = shanhai$nativeThreads();
        if (runtime != null && runtime.isFinishing()) ci.cancel();
    }

    @Inject(method = "handleRecipeIO", at = @At("HEAD"), cancellable = true)
    private void shanhai$guardInput(GTRecipe recipe, IO io, CallbackInfoReturnable<Boolean> cir) {
        var runtime = shanhai$nativeThreads();
        if (runtime != null && io == IO.IN && runtime.skipPaidInput()) { cir.setReturnValue(true); return; }
        if (runtime != null && io == IO.IN && (runtime.isFinishing() || !runtime.canStart(recipe))) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "setupRecipe", at = @At("HEAD"), cancellable = true)
    private void shanhai$guardSetup(GTRecipe recipe, CallbackInfo ci) {
        var runtime = shanhai$nativeThreads();
        if (runtime != null && runtime.isFinishing()) ci.cancel();
    }

    @Inject(method = "setWorkingEnabled", at = @At("HEAD"))
    private void shanhai$setEnabled(boolean enabled, CallbackInfo ci) {
        var runtime = shanhai$nativeThreads();
        if (runtime != null) runtime.setEnabled(enabled);
    }

    @Inject(method = "updateTickSubscription", at = @At("HEAD"), cancellable = true)
    private void shanhai$subscription(CallbackInfo ci) {
        var runtime = shanhai$nativeThreads();
        if (runtime == null) return;
        if (runtime.isTicking()) ci.cancel();
        else runtime.retry();
    }

    @Redirect(method = "handleRecipeWorking", at = @At(value = "INVOKE",
            target = "Lcom/gregtechceu/gtceu/api/machine/feature/IRecipeLogicMachine;onWorking()Z"))
    private boolean shanhai$machineWorkingOnce(IRecipeLogicMachine machine) {
        var runtime = shanhai$nativeThreads();
        return runtime == null ? machine.onWorking() : runtime.onWorking(machine);
    }

    @Redirect(method = "setWaiting", at = @At(value = "INVOKE",
            target = "Lcom/gregtechceu/gtceu/api/machine/feature/IRecipeLogicMachine;onWaiting()V"))
    private void shanhai$machineWaitingOnce(IRecipeLogicMachine machine) {
        var runtime = shanhai$nativeThreads();
        if (runtime == null) machine.onWaiting(); else runtime.onWaiting(machine);
    }

    @Inject(method = "saveCustomPersistedData", at = @At("TAIL"))
    private void shanhai$save(CompoundTag tag, boolean forDrop, CallbackInfo ci) {
        var runtime = shanhai$nativeThreads();
        if (runtime != null) runtime.save(tag);
    }
    @Inject(method = "loadCustomPersistedData", at = @At("TAIL"))
    private void shanhai$load(CompoundTag tag, CallbackInfo ci) {
        var runtime = shanhai$nativeThreads();
        if (runtime != null) runtime.load(tag);
    }
}
