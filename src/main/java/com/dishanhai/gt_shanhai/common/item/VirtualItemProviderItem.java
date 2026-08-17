package com.dishanhai.gt_shanhai.common.item;

import com.dishanhai.gt_shanhai.client.renderer.item.VirtualItemProviderRenderer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;

import java.util.List;
import java.util.function.Consumer;

/**
 * Stores a virtual target item for programmable hatches.
 */
public class VirtualItemProviderItem extends Item {

    public static final String TARGET_CIRCUIT_KEY = VirtualItemProviderHelper.LEGACY_TARGET_CIRCUIT_KEY;

    public VirtualItemProviderItem(Properties properties) {
        super(properties);
    }

    @Override
    public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        consumer.accept(new IClientItemExtensions() {

            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                return VirtualItemProviderRenderer.getInstance();
            }
        });
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player.isShiftKeyDown()) {
            VirtualItemProviderHelper.clearTarget(stack);
            if (!level.isClientSide) {
                player.displayClientMessage(Component.literal("已清除虚拟物品提供器绑定").withStyle(ChatFormatting.YELLOW), true);
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }

        ItemStack target = player.getItemInHand(hand == InteractionHand.MAIN_HAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
        if (target.isEmpty() || VirtualItemProviderHelper.isProviderItem(target)) {
            if (!level.isClientSide) {
                player.displayClientMessage(Component.literal("另一只手需要拿待虚拟提供的物品").withStyle(ChatFormatting.RED), true);
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }

        VirtualItemProviderHelper.bindTarget(stack, target);
        if (!level.isClientSide) {
            ItemStack stored = VirtualItemProviderHelper.getTarget(stack);
            player.displayClientMessage(Component.literal("已绑定虚拟物品: ")
                    .append(stored.getHoverName()).withStyle(ChatFormatting.GREEN), true);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return !VirtualItemProviderHelper.getTarget(stack).isEmpty() || super.isFoil(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        ItemStack target = VirtualItemProviderHelper.getTarget(stack);
        tooltip.add(Component.literal("另一只手拿物品时右键绑定为虚拟目标").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("你并不需要手动绑定虚拟物品供应器，在编码时不消耗物品会主动绑定").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("在默认配置下，只检验AE中是否有对应且满足数量的虚拟物品实料").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("例：虚拟物品供应器在编码中绑定一个不消耗的「铁锭」，会在AE中查找是否有足够的「铁锭」实物").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("虚拟供应器只用于标识哪个物品需要虚拟，而不是虚拟供应器能作为实际物品供应").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("潜行右键清除绑定").withStyle(ChatFormatting.DARK_GRAY));
        if (target.isEmpty()) {
            tooltip.add(Component.literal("未绑定虚拟物品").withStyle(ChatFormatting.RED));
            return;
        }
        tooltip.add(Component.literal("已绑定: ").withStyle(ChatFormatting.AQUA)
                .append(Component.literal(target.getCount() + "x ").withStyle(ChatFormatting.WHITE))
                .append(target.getHoverName().copy().withStyle(ChatFormatting.WHITE)));
    }

    public static ItemStack getTarget(ItemStack stack) {
        return VirtualItemProviderHelper.getTarget(stack);
    }
}
