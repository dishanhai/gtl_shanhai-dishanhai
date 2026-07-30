package com.dishanhai.gt_shanhai.mixin;

import com.extendedae_plus.util.uploadPattern.ProviderUploadUtil;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = ProviderUploadUtil.class, remap = false)
public interface EaepProviderUploadPatternAccessor {

    @Invoker("getPendingCtrlQPattern")
    static ItemStack gtShanhai$getPendingCtrlQPattern(ServerPlayer player) {
        throw new AssertionError();
    }
}
