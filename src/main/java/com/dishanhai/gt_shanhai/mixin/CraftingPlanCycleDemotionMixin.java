package com.dishanhai.gt_shanhai.mixin;

import appeng.api.stacks.AEKey;
import appeng.crafting.CraftingPlan;

import com.dishanhai.gt_shanhai.common.ae2.CraftingPlanCycleDemotionAccess;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.Set;

@Mixin(value = CraftingPlan.class, remap = false)
public class CraftingPlanCycleDemotionMixin implements CraftingPlanCycleDemotionAccess {

    @Unique
    private Set<AEKey> gtShanhai$demotedOutputs;

    @Override
    public Set<AEKey> gtShanhai$getDemotedOutputs() {
        return gtShanhai$demotedOutputs == null ? Set.of() : gtShanhai$demotedOutputs;
    }

    @Override
    public void gtShanhai$setDemotedOutputs(Set<AEKey> keys) {
        gtShanhai$demotedOutputs = keys == null ? Set.of() : keys;
    }
}
