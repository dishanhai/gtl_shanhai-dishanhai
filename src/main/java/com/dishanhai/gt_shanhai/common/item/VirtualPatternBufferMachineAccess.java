package com.dishanhai.gt_shanhai.common.item;

public interface VirtualPatternBufferMachineAccess {

    void gtShanhai$restoreVirtualTargetsFromPatterns(Iterable<appeng.api.crafting.IPatternDetails> patterns);

    boolean gtShanhai$stripVirtualTargetsInSlot(int slotIndex);

    void gtShanhai$indexRefundSlot(Object slot,
            it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap<appeng.api.stacks.AEItemKey> itemInventory,
            it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap<appeng.api.stacks.AEFluidKey> fluidInventory);

    void gtShanhai$invalidateRefundSlotIndex();
}
