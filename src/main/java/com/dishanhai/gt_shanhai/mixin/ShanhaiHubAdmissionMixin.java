package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.api.machine.part.ShanhaiHubIoPredicate;
import com.dishanhai.gt_shanhai.common.machine.part.ShanhaiHubStructure;
import com.gregtechceu.gtceu.api.pattern.MultiblockState;
import com.gregtechceu.gtceu.api.pattern.TraceabilityPredicate;
import com.gregtechceu.gtceu.api.pattern.error.SinglePredicateError;
import com.gregtechceu.gtceu.api.pattern.predicates.SimplePredicate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** 山海：允许借位仓进入限量仓位，同时保留真实能力的仓位条件和计数。 */
@Mixin(value = TraceabilityPredicate.class, remap = false, priority = 1500)
public class ShanhaiHubAdmissionMixin {
    @Shadow public List<SimplePredicate> limited;

    @Inject(method = "test", at = @At("RETURN"), cancellable = true, remap = false)
    private void gtShanhai$admitHubWithoutIoCount(MultiblockState state, CallbackInfoReturnable<Boolean> cir) {
        boolean borrowsMaterial = ShanhaiHubStructure.borrowsMaterialSlot(state);
        if (!borrowsMaterial) return;
        boolean recipeThread = ShanhaiHubStructure.isRecipeThreadHatch(state);
        // 原实现会清掉被另一分支掩盖的超限错误；借位不能掩盖真实能力的上限。
        for (SimplePredicate simple : limited) {
            if (gtShanhai$isBorrowedSlot(simple, recipeThread)) continue;
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
            if (gtShanhai$isBorrowedSlot(simple, recipeThread)
                    && simple.maxCount != 0 && simple.maxLayerCount != 0 && simple.test(state)) {
                state.setError(null);
                cir.setReturnValue(true);
                return;
            }
        }
        if (!ShanhaiHubStructure.isHub(state)) return;
        // 只有其他分支都无法接纳枢纽时，才占用可选能力的限额。
        for (SimplePredicate simple : limited) {
            if (((ShanhaiHubIoPredicate) simple).gtShanhai$defersHubCount()
                    && gtShanhai$admitCountedSlot(state, simple)) {
                state.setError(null);
                cir.setReturnValue(true);
                return;
            }
        }
    }

    @Unique
    private static boolean gtShanhai$isBorrowedSlot(SimplePredicate simple, boolean recipeThread) {
        ShanhaiHubIoPredicate ability = (ShanhaiHubIoPredicate) simple;
        return ability.gtShanhai$isIoAdmissionOnly() || (recipeThread && ability.gtShanhai$isParallelAbility());
    }

    @Unique
    private static boolean gtShanhai$admitCountedSlot(MultiblockState state, SimplePredicate simple) {
        int global = state.getGlobalCount().getOrDefault(simple, 0);
        int layer = state.getLayerCount().getOrDefault(simple, 0);
        if (simple.maxCount != -1 && global >= simple.maxCount) {
            state.setError(new SinglePredicateError(simple, 0));
            return false;
        }
        if (simple.maxLayerCount != -1 && layer >= simple.maxLayerCount) {
            state.setError(new SinglePredicateError(simple, 2));
            return false;
        }
        if (!simple.test(state)) return false;

        if (simple.maxCount != -1) state.getGlobalCount().put(simple, global + 1);
        if (simple.maxLayerCount != -1) state.getLayerCount().put(simple, layer + 1);
        return true;
    }
}
