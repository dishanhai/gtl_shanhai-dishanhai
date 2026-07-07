package com.dishanhai.gt_shanhai.common.ae2.quantum;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.KeyCounter;

public interface QuantumPatternBufferRefundAccess {

    String gtShanhai$getQuantumRefundId();

    boolean gtShanhai$refundQuantumPush(IPatternDetails patternDetails, KeyCounter inputs);
}
