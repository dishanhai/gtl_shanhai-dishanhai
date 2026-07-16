package com.dishanhai.gt_shanhai.common.item;

public interface VirtualPatternBufferMachineAccess {

    void gtShanhai$restoreVirtualTargetsFromPatterns(Iterable<appeng.api.crafting.IPatternDetails> patterns);

    boolean gtShanhai$stripVirtualTargetsInSlot(int slotIndex);
}
