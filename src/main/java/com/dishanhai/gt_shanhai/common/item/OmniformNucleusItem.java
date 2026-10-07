package com.dishanhai.gt_shanhai.common.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * 额外挂载槽用的万象原核。一个即可满足配方的炉温、恒星容器、超净间、重力和任意维度。
 */
public final class OmniformNucleusItem extends Item {

    public OmniformNucleusItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.literal("放入额外挂载槽，一个即可").withStyle(ChatFormatting.LIGHT_PURPLE));
        tooltip.add(Component.literal("同时满足炉温、恒星热力容器、超净间、重力和任意维度").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("不替代暗能量、湮灭核心或黑洞种子").withStyle(ChatFormatting.DARK_GRAY));
    }
}
