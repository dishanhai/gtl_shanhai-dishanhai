package com.dishanhai.gt_shanhai.mixin;

import mezz.jei.common.Internal;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * JEI 异步启动被取消时，覆盖层可能比 Runtime 多存活一个渲染帧。
 */
@Mixin(targets = "mezz.jei.gui.events.GuiEventHandler", remap = false)
public class JEIGuiRuntimeGuardMixin {

    @Inject(method = "onDrawScreenPost", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtShanhai$skipDrawWithoutRuntime(Screen screen, GuiGraphics graphics,
            int mouseX, int mouseY, CallbackInfo ci) {
        if (Internal.getOptionalJeiRuntime().isEmpty()) {
            ci.cancel();
        }
    }
}
