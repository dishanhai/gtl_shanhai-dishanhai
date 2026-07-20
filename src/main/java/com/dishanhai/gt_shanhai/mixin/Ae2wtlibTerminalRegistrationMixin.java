package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.common.item.terminal.ShanhaiPatternTerminalIntegration;
import de.mari_023.ae2wtlib.AE2wtlib;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = AE2wtlib.class, remap = false)
public abstract class Ae2wtlibTerminalRegistrationMixin {

    @Inject(method = "onAe2Initialized", at = @At("TAIL"), remap = false)
    private static void gtShanhai$registerTerminalAfterAe2wtlibItems(CallbackInfo ci) {
        ShanhaiPatternTerminalIntegration.onAe2wtlibReady();
    }
}
