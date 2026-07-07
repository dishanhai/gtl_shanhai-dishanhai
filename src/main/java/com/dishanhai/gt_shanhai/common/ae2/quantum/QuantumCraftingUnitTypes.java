package com.dishanhai.gt_shanhai.common.ae2.quantum;

import com.dishanhai.gt_shanhai.common.block.DShanhaiAE2Blocks;

import net.minecraft.world.item.Item;

public final class QuantumCraftingUnitTypes {

    public static final QuantumCraftingUnitType COMPUTER_UNIT = new QuantumCraftingUnitType(268435456L, 256);
    public static final QuantumCraftingUnitType PARALLEL_PROCESSOR = new QuantumCraftingUnitType(0, 4096);
    public static final QuantumCraftingUnitType CRAFTING_STORAGE = new QuantumCraftingUnitType(Long.MAX_VALUE, 0);
    public static final QuantumCraftingUnitType STRUCTURE = new QuantumCraftingUnitType(0, 0);

    public static Item getItemFromType(QuantumCraftingUnitType type) {
        if (type == PARALLEL_PROCESSOR) return DShanhaiAE2Blocks.QUANTUM_PARALLEL_PROCESSOR_ITEM.get();
        if (type == CRAFTING_STORAGE) return DShanhaiAE2Blocks.QUANTUM_CRAFTING_STORAGE_ITEM.get();
        if (type == STRUCTURE) return DShanhaiAE2Blocks.QUANTUM_STRUCTURE_ITEM.get();
        return DShanhaiAE2Blocks.QUANTUM_COMPUTER_ITEM.get();
    }

    private QuantumCraftingUnitTypes() {}
}
