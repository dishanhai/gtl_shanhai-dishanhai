package com.dishanhai.gt_shanhai.common.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * 寰宇模拟单元。材质为帧序列动图，本身不附带机器逻辑。
 */
public final class UniverseSimulationUnitItem extends Item {

    public UniverseSimulationUnitItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.literal("掌心展开的一段可折叠宇宙").withStyle(ChatFormatting.LIGHT_PURPLE));
        tooltip.add(Component.literal("内部星图每刻重算，与外界时钟不同步").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("模拟仍在运行，没有终态").withStyle(ChatFormatting.DARK_AQUA));
    }
}
