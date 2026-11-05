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

/** 山海：枢纽的普通 I/O 映射仅供入场，不能占用或补足材料仓数量。 */
@Mixin(value = SimplePredicate.class, remap = false)
public class ShanhaiHubIoPredicateMixin implements ShanhaiHubIoPredicate {
    @Shadow public int minCount;
    @Shadow public int maxCount;
    @Shadow public int minLayerCount;
    @Shadow public int maxLayerCount;
    @Unique private boolean gtShanhai$ioAdmissionOnly;

    @Override
    public boolean gtShanhai$isIoAdmissionOnly() { return gtShanhai$ioAdmissionOnly; }

    @Override
    public void gtShanhai$setIoAdmissionOnly(boolean admissionOnly) {
        gtShanhai$ioAdmissionOnly = admissionOnly;
    }

    @Inject(method = "testLimited", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtShanhai$skipHubIoCount(MultiblockState state, CallbackInfoReturnable<Boolean> cir) {
        if (!gtShanhai$ioAdmissionOnly || !ShanhaiHubStructure.isHub(state)) return;
        SimplePredicate self = (SimplePredicate) (Object) this;
        // 零贡献仍要登记，让 GTCEu/GTLCore 的整机与分层下限检查生效。
        if (minCount != -1 || maxCount != -1) state.getGlobalCount().putIfAbsent(self, 0);
        if (minLayerCount != -1 || maxLayerCount != -1) state.getLayerCount().putIfAbsent(self, 0);
        cir.setReturnValue(false);
    }
}
