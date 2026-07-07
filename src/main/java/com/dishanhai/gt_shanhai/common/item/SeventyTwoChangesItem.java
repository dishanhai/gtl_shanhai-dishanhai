package com.dishanhai.gt_shanhai.common.item;

import com.dishanhai.gt_shanhai.api.DShanhaiTextUtil;
import com.gregtechceu.gtceu.api.block.IMachineBlock;
import com.gregtechceu.gtceu.api.item.MetaMachineItem;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

public class SeventyTwoChangesItem extends MetaMachineItem {

    public SeventyTwoChangesItem(IMachineBlock block, net.minecraft.world.item.Item.Properties properties) {
        super(block, properties);
    }

    @Override
    public Component getName(ItemStack stack) {
        return DShanhaiTextUtil.createElectricText("七十二变");
    }
}
