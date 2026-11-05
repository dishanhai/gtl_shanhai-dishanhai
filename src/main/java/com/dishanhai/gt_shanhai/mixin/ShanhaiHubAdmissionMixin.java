package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.api.machine.part.ShanhaiHubIoPredicate;
import com.dishanhai.gt_shanhai.common.machine.part.ShanhaiHubStructure;
import com.gregtechceu.gtceu.api.pattern.MultiblockState;
import com.gregtechceu.gtceu.api.pattern.TraceabilityPredicate;
import com.gregtechceu.gtceu.api.pattern.error.SinglePredicateError;
import com.gregtechceu.gtceu.api.pattern.predicates.SimplePredicate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** 山海：只有限量普通 I/O 分支的仓位也能安装枢纽，原始方块及内部条件仍须匹配。 */
@Mixin(value = TraceabilityPredicate.class, remap = false, priority = 1500)
public class ShanhaiHubAdmissionMixin {
    @Shadow public List<SimplePredicate> limited;

    @Inject(method = "test", at = @At("RETURN"), cancellable = true, remap = false)
    private void gtShanhai$admitHubWithoutIoCount(MultiblockState state, CallbackInfoReturnable<Boolean> cir) {
        if (!ShanhaiHubStructure.isHub(state)) return;
        // 原实现会清掉被另一分支掩盖的超限错误；枢纽入场不能掩盖真实能力的上限。
        for (SimplePredicate simple : limited) {
            if (((ShanhaiHubIoPredicate) simple).gtShanhai$isIoAdmissionOnly()) continue;
            if (simple.maxCount != -1 && state.getGlobalCount().getOrDefault(simple, 0) > simple.maxCount) {
                state.setError(new SinglePredicateError(simple, 0));
                cir.setReturnValue(false);
                return;
            }
            if (simple.maxLayerCount != -1 && state.getLayerCount().getOrDefault(simple, 0) > simple.maxLayerCount) {
                state.setError(new SinglePredicateError(simple, 2));
                cir.setReturnValue(false);
                return;
            }
        }
        if (cir.getReturnValueZ()) return;
        for (SimplePredicate simple : limited) {
            if (((ShanhaiHubIoPredicate) simple).gtShanhai$isIoAdmissionOnly()
                    && simple.maxCount != 0 && simple.maxLayerCount != 0 && simple.test(state)) {
                state.setError(null);
                cir.setReturnValue(true);
                return;
            }
        }
    }
}
