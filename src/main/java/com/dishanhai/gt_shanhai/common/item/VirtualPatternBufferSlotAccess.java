package com.dishanhai.gt_shanhai.common.item;

import appeng.api.stacks.AEKey;

public interface VirtualPatternBufferSlotAccess {

    void gtShanhai$addVirtualTarget(AEKey key, long amount);

    void gtShanhai$syncVirtualTargetsToCatalyst();

    void gtShanhai$stripVirtualTargetsFromCatalyst();

    void gtShanhai$stripVirtualTargets();
}
