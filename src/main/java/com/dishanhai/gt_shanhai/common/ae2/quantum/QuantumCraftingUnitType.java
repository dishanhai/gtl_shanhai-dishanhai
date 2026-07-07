package com.dishanhai.gt_shanhai.common.ae2.quantum;

import appeng.block.crafting.ICraftingUnitType;

import net.minecraft.world.item.Item;

public final class QuantumCraftingUnitType implements ICraftingUnitType {

    private final long storageBytes;
    private final int acceleratorThreads;

    public QuantumCraftingUnitType(long storageBytes, int acceleratorThreads) {
        this.storageBytes = storageBytes;
        this.acceleratorThreads = acceleratorThreads;
    }

    @Override
    public long getStorageBytes() {
        return storageBytes;
    }

    @Override
    public int getAcceleratorThreads() {
        return acceleratorThreads;
    }

    @Override
    public Item getItemFromType() {
        return QuantumCraftingUnitTypes.getItemFromType(this);
    }
}
