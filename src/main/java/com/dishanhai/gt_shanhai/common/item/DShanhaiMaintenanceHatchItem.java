package com.dishanhai.gt_shanhai.common.item;

import com.gregtechceu.gtceu.api.block.IMachineBlock;
import com.gregtechceu.gtceu.api.item.MetaMachineItem;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import com.dishanhai.gt_shanhai.api.TextFormatParser;

public class DShanhaiMaintenanceHatchItem extends MetaMachineItem {

    public DShanhaiMaintenanceHatchItem(IMachineBlock block, Properties properties) {
        super(block, properties);
    }

    @Override
    public Component getName(ItemStack stack) {
        return TextFormatParser.parse("&$ultimate-终焉聚合枢纽");
    }
}
