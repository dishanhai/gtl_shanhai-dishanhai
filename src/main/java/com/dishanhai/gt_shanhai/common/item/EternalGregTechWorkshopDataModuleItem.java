package com.dishanhai.gt_shanhai.common.item;

import com.dishanhai.gt_shanhai.common.machine.misc.EternalGregTechWorkshopMachine;
import com.dishanhai.gt_shanhai.common.machine.misc.workshop.EternalGregTechWorkshopModuleMachine;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MetaMachine;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.List;

/** Two-step binder for Eternal GregTech Workshop host and module machines. */
public class EternalGregTechWorkshopDataModuleItem extends Item {

    private static final String KEY_ROOT = "sh_eternal_workshop_binding";
    private static final String KEY_DIMENSION = "dimension";
    private static final String KEY_X = "x";
    private static final String KEY_Y = "y";
    private static final String KEY_Z = "z";

    public EternalGregTechWorkshopDataModuleItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        return handleBlockUse(context.getLevel(), context.getPlayer(), context.getItemInHand(), context.getClickedPos());
    }

    public InteractionResult handleBlockUse(Level level, Player player, ItemStack stack, BlockPos clickedPos) {
        if (player == null) {
            return InteractionResult.PASS;
        }

        if (player.isShiftKeyDown()) {
            clearBinding(stack);
            if (!level.isClientSide) {
                player.displayClientMessage(Component.literal("已清除永恒格雷工坊绑定记录").withStyle(ChatFormatting.YELLOW), true);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        MetaMachine machine = getMachine(level, clickedPos);
        if (machine instanceof EternalGregTechWorkshopMachine workshop) {
            if (!workshop.isFormed()) {
                send(player, level, "目标工坊主机未成型", ChatFormatting.RED);
                return InteractionResult.sidedSuccess(level.isClientSide);
            }
            storeBinding(stack, level, workshop.getPos());
            send(player, level, "已记录永恒格雷工坊主机: " + workshop.getPos().toShortString(), ChatFormatting.GREEN);
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        if (machine instanceof EternalGregTechWorkshopModuleMachine module) {
            if (!module.isFormed()) {
                send(player, level, "目标工坊模块未成型", ChatFormatting.RED);
                return InteractionResult.sidedSuccess(level.isClientSide);
            }
            CompoundTag binding = getBinding(stack);
            if (binding == null) {
                send(player, level, "未记录工坊主机，先右键已成型主机", ChatFormatting.RED);
                return InteractionResult.sidedSuccess(level.isClientSide);
            }
            String dimension = binding.getString(KEY_DIMENSION);
            if (!level.dimension().location().toString().equals(dimension)) {
                send(player, level, "跨维度绑定被拒绝", ChatFormatting.RED);
                return InteractionResult.sidedSuccess(level.isClientSide);
            }
            BlockPos hostPos = new BlockPos(binding.getInt(KEY_X), binding.getInt(KEY_Y), binding.getInt(KEY_Z));
            MetaMachine hostMachine = getMachine(level, hostPos);
            if (!(hostMachine instanceof EternalGregTechWorkshopMachine workshop) || !workshop.isFormed()) {
                send(player, level, "记录的工坊主机不存在或未成型", ChatFormatting.RED);
                return InteractionResult.sidedSuccess(level.isClientSide);
            }
            if (!workshop.canAcceptModuleConnection(module.getWorkshopModuleType(), module.getWorkshopModuleId())) {
                send(player, level, "工坊主机模块槽已满", ChatFormatting.RED);
                return InteractionResult.sidedSuccess(level.isClientSide);
            }
            if (!level.isClientSide) {
                module.connectToHost(workshop);
                if (player.getAbilities().instabuild) {
                    clearBinding(stack);
                } else {
                    stack.shrink(1);
                }
            }
            send(player, level, "已绑定模块到永恒格雷工坊主机", ChatFormatting.GREEN);
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        return InteractionResult.PASS;
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.literal("右键已成型永恒格雷工坊主机记录绑定源").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("再右键同维度已成型工坊模块完成绑定").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("潜行右键清除记录").withStyle(ChatFormatting.DARK_GRAY));
        CompoundTag binding = getBinding(stack);
        if (binding != null) {
            tooltip.add(Component.literal("已记录: " + binding.getString(KEY_DIMENSION) + " ["
                    + binding.getInt(KEY_X) + ", " + binding.getInt(KEY_Y) + ", " + binding.getInt(KEY_Z) + "]")
                    .withStyle(ChatFormatting.AQUA));
        }
    }

    private static MetaMachine getMachine(Level level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof IMachineBlockEntity machineBlockEntity)) {
            return null;
        }
        return machineBlockEntity.getMetaMachine();
    }

    private static void storeBinding(ItemStack stack, Level level, BlockPos pos) {
        CompoundTag root = new CompoundTag();
        root.putString(KEY_DIMENSION, level.dimension().location().toString());
        root.putInt(KEY_X, pos.getX());
        root.putInt(KEY_Y, pos.getY());
        root.putInt(KEY_Z, pos.getZ());
        stack.getOrCreateTag().put(KEY_ROOT, root);
    }

    private static CompoundTag getBinding(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(KEY_ROOT)) {
            return null;
        }
        CompoundTag root = tag.getCompound(KEY_ROOT);
        if (!root.contains(KEY_DIMENSION) || !root.contains(KEY_X) || !root.contains(KEY_Y) || !root.contains(KEY_Z)) {
            return null;
        }
        return root;
    }

    private static void clearBinding(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag != null) {
            tag.remove(KEY_ROOT);
        }
    }

    private static void send(net.minecraft.world.entity.player.Player player, Level level, String message, ChatFormatting color) {
        if (!level.isClientSide) {
            player.displayClientMessage(Component.literal(message).withStyle(color), true);
        }
    }
}
