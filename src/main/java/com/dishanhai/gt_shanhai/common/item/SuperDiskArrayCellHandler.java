package com.dishanhai.gt_shanhai.common.item;

import appeng.api.storage.cells.ICellHandler;
import appeng.api.storage.cells.ISaveProvider;
import appeng.api.storage.cells.StorageCell;

import net.minecraft.world.item.ItemStack;

import org.jetbrains.annotations.Nullable;

public class SuperDiskArrayCellHandler implements ICellHandler {
    public static final SuperDiskArrayCellHandler INSTANCE = new SuperDiskArrayCellHandler();

    @Override
    public boolean isCell(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof SuperDiskArrayItem;
    }

    @Nullable
    @Override
    public StorageCell getCellInventory(ItemStack stack, @Nullable ISaveProvider saveProvider) {
        return SuperDiskArrayInventory.create(stack, saveProvider);
    }
}
