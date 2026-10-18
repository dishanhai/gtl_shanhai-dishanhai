package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.common.item.terminal.ShanhaiPatternTerminalIntegration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// ae2wtlib 是可选依赖：用字符串 targets，避免运行环境缺该 mod 时在加载本类时解析不到类。
@Mixin(targets = "de.mari_023.ae2wtlib.AE2wtlib", remap = false)
public abstract class Ae2wtlibTerminalRegistrationMixin {

    @Inject(method = "onAe2Initialized", at = @At("TAIL"), remap = false)
    private static void gtShanhai$registerTerminalAfterAe2wtlibItems(CallbackInfo ci) {
        ShanhaiPatternTerminalIntegration.onAe2wtlibReady();
    }
}
