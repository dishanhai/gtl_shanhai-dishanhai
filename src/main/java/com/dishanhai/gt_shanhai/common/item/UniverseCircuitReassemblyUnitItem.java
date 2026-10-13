package com.dishanhai.gt_shanhai.common.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * 寰宇电路重组单元。材质为帧序列动图，本身不附带机器逻辑。
 */
public final class UniverseCircuitReassemblyUnitItem extends Item {

    public UniverseCircuitReassemblyUnitItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.literal("断裂的走线会在掌心重新咬合").withStyle(ChatFormatting.GOLD));
        tooltip.add(Component.literal("每一次重组都改写一段宇宙电路").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("焊点未冷，时序仍在对齐").withStyle(ChatFormatting.AQUA));
    }
}
