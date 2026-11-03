package com.dishanhai.gt_shanhai.common.item;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.fluids.capability.wrappers.FluidBucketWrapper;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/** 山海：奇蹟之流的 1000 mB 容器，可與儲槽交換，但不能倒入世界。 */
public final class MiracleFlowBucketItem extends BucketItem {
    public MiracleFlowBucketItem(Supplier<? extends Fluid> fluid, Properties properties) {
        super(fluid, properties);
    }

    @Override
    public ICapabilityProvider initCapabilities(ItemStack stack, @Nullable CompoundTag nbt) {
        // Forge 的 BucketItem 僅為原類型提供包裝，子類必須明確保留流體容器能力。
        return new FluidBucketWrapper(stack);
    }

    @Override
    public boolean emptyContents(@Nullable Player player, Level level, BlockPos pos,
                                 @Nullable BlockHitResult hit, @Nullable ItemStack container) {
        return false;
    }
}
