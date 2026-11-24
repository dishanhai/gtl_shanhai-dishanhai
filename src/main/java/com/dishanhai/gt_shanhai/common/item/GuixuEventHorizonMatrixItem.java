package com.dishanhai.gt_shanhai.common.item;

// Author: dishanhai
import com.dishanhai.gt_shanhai.common.machine.primordial.guixu.GuixuVisionCoreMachine;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import java.util.List;

public final class GuixuEventHorizonMatrixItem extends Item {
    private static final String LANG = "gt_shanhai.item.guixu_event_horizon_matrix.";

    public GuixuEventHorizonMatrixItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        Player player = context.getPlayer();
        Level level = context.getLevel();
        if (player == null || !player.isShiftKeyDown()
                || !(MetaMachine.getMachine(level, context.getClickedPos()) instanceof GuixuVisionCoreMachine host)) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide) {
            if (host.activateEventHorizonMatrix()) {
                if (!player.getAbilities().instabuild) stack.shrink(1);
                player.displayClientMessage(Component.translatable(LANG + "activated").withStyle(ChatFormatting.AQUA), true);
            } else {
                player.displayClientMessage(Component.translatable(LANG + "unavailable").withStyle(ChatFormatting.RED), true);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable(LANG + "use").withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable(LANG + "reward").withStyle(ChatFormatting.AQUA));
        lines.add(Component.translatable(LANG + "risk").withStyle(ChatFormatting.RED));
    }
}
