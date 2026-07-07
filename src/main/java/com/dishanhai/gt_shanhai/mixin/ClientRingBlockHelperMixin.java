package com.dishanhai.gt_shanhai.mixin;

import net.minecraft.world.level.Level;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在 gtladditions ClientRingBlockHelper.hideRingsAtPosition 执行前记录位置
 */
@Mixin(targets = "com.gtladd.gtladditions.utils.antichrist.ClientRingBlockHelper", remap = false)
public class ClientRingBlockHelperMixin {

    @Inject(method = "hideRingsAtPosition", at = @At("HEAD"))
    private void logHideRings(Level level, long posLong, net.minecraft.core.Direction facing, CallbackInfo ci) {}

    @Inject(method = "restoreRingsAtPosition", at = @At("HEAD"))
    private void logRestoreRings(Level level, long posLong, net.minecraft.core.Direction facing, CallbackInfo ci) {}
}
