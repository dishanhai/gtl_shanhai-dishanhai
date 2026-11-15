package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.api.machine.part.ShanhaiHubIoPredicate;
import com.dishanhai.gt_shanhai.common.machine.part.ShanhaiHubStructure;
import com.gregtechceu.gtceu.api.pattern.MultiblockState;
import com.gregtechceu.gtceu.api.pattern.predicates.SimplePredicate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 山海：借位仓不参与对应能力计数，枢纽的可选能力留给仓位匹配处理。 */
@Mixin(value = SimplePredicate.class, remap = false)
public class ShanhaiHubIoPredicateMixin implements ShanhaiHubIoPredicate {
    @Shadow public int minCount;
    @Shadow public int maxCount;
    @Shadow public int minLayerCount;
    @Shadow public int maxLayerCount;
    @Unique private boolean gtShanhai$ioAdmissionOnly;
    @Unique private boolean gtShanhai$hubAbility;
    @Unique private boolean gtShanhai$parallelAbility;

    @Override
    public boolean gtShanhai$isIoAdmissionOnly() { return gtShanhai$ioAdmissionOnly; }

    @Override
    public void gtShanhai$setIoAdmissionOnly(boolean admissionOnly) {
        gtShanhai$ioAdmissionOnly = admissionOnly;
    }

    @Override
    public void gtShanhai$markHubAbility() {
        gtShanhai$hubAbility = true;
    }

    @Override
    public boolean gtShanhai$defersHubCount() {
        return gtShanhai$hubAbility && minCount <= 0 && minLayerCount <= 0;
    }

    @Override
    public void gtShanhai$markParallelAbility() {
        gtShanhai$parallelAbility = true;
    }

    @Override
    public boolean gtShanhai$isParallelAbility() {
        return gtShanhai$parallelAbility;
    }

    @Inject(method = "testLimited", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtShanhai$skipHubIoCount(MultiblockState state, CallbackInfoReturnable<Boolean> cir) {
        boolean skipMaterial = gtShanhai$ioAdmissionOnly && ShanhaiHubStructure.borrowsMaterialSlot(state);
        boolean skipParallel = gtShanhai$parallelAbility && ShanhaiHubStructure.isRecipeThreadHatch(state);
        boolean deferHub = gtShanhai$defersHubCount() && ShanhaiHubStructure.isHub(state);
        if (!skipMaterial && !skipParallel && !deferHub) return;
        SimplePredicate self = (SimplePredicate) (Object) this;
        // 零贡献仍要登记，让 GTCEu/GTLCore 的整机与分层下限检查生效。
        if (minCount != -1 || maxCount != -1) state.getGlobalCount().putIfAbsent(self, 0);
        if (minLayerCount != -1 || maxLayerCount != -1) state.getLayerCount().putIfAbsent(self, 0);
        cir.setReturnValue(false);
    }
}
