package com.dishanhai.gt_shanhai.common.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * 创始演算器。材质为帧序列动图，本身不附带机器逻辑。
 */
public final class GenesisCalculatorItem extends Item {

    public GenesisCalculatorItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.literal("把开端按进掌心的一枚键").withStyle(ChatFormatting.GOLD));
        tooltip.add(Component.literal("屏幕上的原点每刻重写一次创世").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("演算未停，答案仍是起点").withStyle(ChatFormatting.RED));
    }
}
