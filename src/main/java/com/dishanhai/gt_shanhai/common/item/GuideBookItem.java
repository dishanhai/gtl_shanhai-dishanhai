package com.dishanhai.gt_shanhai.common.item;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import guideme.GuidesCommon;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public class GuideBookItem extends Item {

    private static final ResourceLocation GUIDE_ID = new ResourceLocation(GTDishanhaiMod.MOD_ID, "guide");

    public GuideBookItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide()) {
            GuidesCommon.openGuide(player, GUIDE_ID);
        }
        return new InteractionResultHolder<>(InteractionResult.FAIL, stack);
    }
}
