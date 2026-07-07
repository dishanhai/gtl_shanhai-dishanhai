package com.dishanhai.gt_shanhai.common.item;

import com.dishanhai.gt_shanhai.common.machine.misc.EternalGregTechWorkshopMachine;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;

import java.util.List;

/**
 * 引力子碎片物品。
 * 右键已成型永恒工坊主机时，将碎片回存为内部可用碎片。
 */
public class GravitonShardItem extends Item {

    public GravitonShardItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        ItemStack stack = context.getItemInHand();
        if (player == null) {
            return InteractionResult.PASS;
        }
        BlockPos pos = context.getClickedPos();
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof IMachineBlockEntity machineBE)) {
            return InteractionResult.PASS;
        }
        if (!(machineBE.getMetaMachine() instanceof EternalGregTechWorkshopMachine workshop)) {
            return InteractionResult.PASS;
        }
        if (!workshop.isFormed()) {
            if (!level.isClientSide) {
                player.displayClientMessage(Component.literal("目标工坊主机未成型").withStyle(ChatFormatting.RED), true);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        if (level.isClientSide) {
            return InteractionResult.sidedSuccess(true);
        }
        int count = stack.getCount();
        workshop.depositGravitonShards(count);
        stack.shrink(count);
        player.displayClientMessage(
                Component.literal("已回存 " + count + " 枚引力子碎片").withStyle(ChatFormatting.GREEN),
                true);
        return InteractionResult.sidedSuccess(false);
    }

    @Override
    public void appendHoverText(ItemStack stack, net.minecraft.world.level.Level level,
                                List<Component> tooltipComponents, TooltipFlag isAdvanced) {
        tooltipComponents.add(Component.literal("右键已成型永恒工坊主机可回存为内部碎片").withStyle(ChatFormatting.GRAY));
        tooltipComponents.add(Component.literal("用于解锁升级树节点").withStyle(ChatFormatting.DARK_GRAY));
    }
}
