package com.dishanhai.gt_shanhai.common.item;

import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialOmegaEngineMachine;
import com.dishanhai.gt_shanhai.common.machine.primordial.guixu.GuixuEngineLink;
import com.dishanhai.gt_shanhai.common.machine.primordial.guixu.GuixuVisionCoreMachine;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MetaMachine;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.List;

/**
 * 归墟超维度链接模块：右键已成型归墟视界枢核记录，再右键原始终焉引擎完成绑定。
 * 绑定后归墟接管该引擎的模组（允许跨维度、不限距离，目标区块需已加载）。
 * 一台归墟最多绑一台引擎；生存模式绑定成功消耗物品，创造模式只清除记录。
 * 潜行右键归墟主机 = 解除它的绑定；潜行右键其他方块 = 清除物品上的记录。
 */
public class GuixuHyperdimensionalLinkModuleItem extends Item {

    private static final String KEY_ROOT = "sh_guixu_link_source";
    private static final String KEY_DIMENSION = "dimension";
    private static final String KEY_X = "x";
    private static final String KEY_Y = "y";
    private static final String KEY_Z = "z";

    public GuixuHyperdimensionalLinkModuleItem(Properties properties) {
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
        MetaMachine machine = getMachine(level, clickedPos);

        if (player.isShiftKeyDown()) {
            if (machine instanceof GuixuVisionCoreMachine guixu && guixu.hasEngineLink()) {
                if (!level.isClientSide) {
                    guixu.unlinkEngine();
                }
                send(player, level, "已解除归墟超维度链接，模组已归还原引擎", ChatFormatting.YELLOW);
            } else {
                clearBinding(stack);
                send(player, level, "已清除归墟链接记录", ChatFormatting.YELLOW);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        if (machine instanceof GuixuVisionCoreMachine guixu) {
            if (!guixu.isFormed()) {
                send(player, level, "目标归墟视界枢核未成型", ChatFormatting.RED);
                return InteractionResult.sidedSuccess(level.isClientSide);
            }
            storeBinding(stack, level, guixu.getPos());
            send(player, level, "已记录归墟视界枢核: " + guixu.getPos().toShortString(), ChatFormatting.GREEN);
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        if (machine instanceof PrimordialOmegaEngineMachine engine) {
            if (!GuixuEngineLink.isLinkableEngine(engine)) {
                return InteractionResult.PASS;
            }
            return bindEngine(level, player, stack, engine);
        }

        return InteractionResult.PASS;
    }

    private InteractionResult bindEngine(Level level, Player player, ItemStack stack, PrimordialOmegaEngineMachine engine) {
        InteractionResult result = InteractionResult.sidedSuccess(level.isClientSide);
        if (!engine.isFormed()) {
            send(player, level, "目标原始终焉引擎未成型", ChatFormatting.RED);
            return result;
        }
        CompoundTag binding = getBinding(stack);
        if (binding == null) {
            send(player, level, "未记录归墟主机，先右键已成型归墟视界枢核", ChatFormatting.RED);
            return result;
        }
        // 客户端没有其他维度的世界：校验与绑定都只在服务端做，客户端只负责吞掉这次点击
        if (level.isClientSide || level.getServer() == null) {
            return result;
        }
        BlockPos hostPos = new BlockPos(binding.getInt(KEY_X), binding.getInt(KEY_Y), binding.getInt(KEY_Z));
        ResourceLocation hostDim = ResourceLocation.tryParse(binding.getString(KEY_DIMENSION));
        ServerLevel hostLevel = hostDim == null ? null
                : level.getServer().getLevel(ResourceKey.create(Registries.DIMENSION, hostDim));
        if (hostLevel != null && !hostLevel.hasChunkAt(hostPos)) {
            send(player, level, "记录的归墟主机所在区块未加载", ChatFormatting.RED);
            return result;
        }
        if (hostLevel == null || !(getMachine(hostLevel, hostPos) instanceof GuixuVisionCoreMachine guixu)
                || !guixu.isFormed()) {
            send(player, level, "记录的归墟主机不存在或未成型", ChatFormatting.RED);
            return result;
        }
        if (guixu.isLinkedTo(GuixuEngineLink.dimensionOf(level), engine.getPos())) {
            send(player, level, "该引擎已绑定到这台归墟主机", ChatFormatting.YELLOW);
            return result;
        }
        if (guixu.hasEngineLink()) {
            send(player, level, "这台归墟主机已绑定了一台引擎，先潜行右键主机解除", ChatFormatting.RED);
            return result;
        }
        if (guixu.isEngineClaimedByOther(engine)) {
            send(player, level, "该引擎的模组已被另一台归墟主机接管", ChatFormatting.RED);
            return result;
        }
        guixu.linkEngine(engine);
        if (player.getAbilities().instabuild) {
            clearBinding(stack);
        } else {
            stack.shrink(1);
        }
        send(player, level, "已绑定原始终焉引擎 " + engine.getPos().toShortString() + " 到归墟主机", ChatFormatting.GREEN);
        return result;
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.literal("右键已成型归墟视界枢核记录绑定源").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("再右键原始终焉引擎，由归墟接管其全部模组").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("允许跨维度、不限距离（双方区块需已加载）").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("潜行右键归墟主机解除绑定，潜行右键其他方块清除记录").withStyle(ChatFormatting.DARK_GRAY));
        CompoundTag binding = getBinding(stack);
        if (binding != null) {
            tooltip.add(Component.literal("已记录: " + binding.getString(KEY_DIMENSION) + " ["
                    + binding.getInt(KEY_X) + ", " + binding.getInt(KEY_Y) + ", " + binding.getInt(KEY_Z) + "]")
                    .withStyle(ChatFormatting.LIGHT_PURPLE));
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
        root.putString(KEY_DIMENSION, GuixuEngineLink.dimensionOf(level));
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

    private static void send(Player player, Level level, String message, ChatFormatting color) {
        if (!level.isClientSide) {
            player.displayClientMessage(Component.literal(message).withStyle(color), true);
        }
    }
}
