package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.common.machine.part.StellarPatternParallelState;
import com.dishanhai.gt_shanhai.common.machine.part.StellarPatternParallelCapture;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import org.gtlcore.gtlcore.common.machine.trait.MultipleRecipesLogic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** 山海：原版聚合配方不保存總並行；依原有成功扣料分支觀測，原呼叫各執行一次。 */
@Mixin(value = MultipleRecipesLogic.class, remap = false)
public abstract class StellarPatternCoreParallelObservationMixin implements StellarPatternParallelState {
    @Unique private long gtShanhai$observedAllocatedParallel = -1L;

    @Override public long gtShanhai$getObservedAllocatedParallel() {
        return gtShanhai$observedAllocatedParallel;
    }

    @Override public void gtShanhai$recordAllocatedParallel(long amount) {
        gtShanhai$observedAllocatedParallel = Math.max(0L, amount);
    }

    @WrapMethod(method = "findAndHandleRecipe", remap = false)
    private void gtShanhai$observeFindAndHandleRecipe(Operation<Void> original) {
        gtShanhai$runObserved(original);
    }

    @WrapMethod(method = "onRecipeFinish", remap = false)
    private void gtShanhai$observeRecipeFinish(Operation<Void> original) {
        gtShanhai$runObserved(original);
    }

    @Unique
    private void gtShanhai$runObserved(Operation<Void> original) {
        StellarPatternParallelCapture.Scope scope = StellarPatternParallelCapture.begin(
                this, (StellarPatternParallelState) this);
        try {
            original.call();
        } finally {
            scope.close();
        }
    }
}
