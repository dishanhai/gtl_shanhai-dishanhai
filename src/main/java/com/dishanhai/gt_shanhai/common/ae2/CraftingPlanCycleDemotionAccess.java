package com.dishanhai.gt_shanhai.common.ae2;

import appeng.api.stacks.AEKey;

import java.util.Set;

/** 挂在 {@code CraftingPlan} 上，让确认界面能认出哪些产物换过样板。 */
public interface CraftingPlanCycleDemotionAccess {

    Set<AEKey> gtShanhai$getDemotedOutputs();

    void gtShanhai$setDemotedOutputs(Set<AEKey> keys);
}
