package com.dishanhai.gt_shanhai.common.item;

import com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeEditorFactory;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * 专属开发者入口：右键打开与 /山海 配方 修改器相同的分层配方编辑器。
 */
public final class RecipeModifierDevItem extends Item {

    public RecipeModifierDevItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResultHolder.sidedSuccess(stack, true);
        }
        if (!serverPlayer.hasPermissions(2)) {
            serverPlayer.displayClientMessage(
                    Component.literal("配方修改器开发者工具需要权限等级 2").withStyle(ChatFormatting.RED),
                    true);
            return InteractionResultHolder.sidedSuccess(stack, false);
        }
        if (!ShanhaiRecipeEditorFactory.open(stack, serverPlayer)) {
            serverPlayer.displayClientMessage(
                    Component.literal("配方修改器 UI 当前不可用").withStyle(ChatFormatting.RED),
                    true);
        }
        return InteractionResultHolder.sidedSuccess(stack, false);
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.literal("右键打开分层配方修改器").withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.literal("仅限开发者使用，需要权限等级 2").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("等价入口：/山海 配方 修改器").withStyle(ChatFormatting.DARK_GRAY));
    }
}
