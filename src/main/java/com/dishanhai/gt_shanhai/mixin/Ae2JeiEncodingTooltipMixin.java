package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.integration.jei.JeiPatternQuickEncodeButtons;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** 山海：借用 AE2 的槽位高亮时，按钮文案交给 JEI 绘制。 */
@Pseudo
@Mixin(targets = "appeng.integration.modules.jei.JEIPlugin", remap = false)
public abstract class Ae2JeiEncodingTooltipMixin {

    @Inject(method = "drawHoveringText(Lnet/minecraft/client/gui/GuiGraphics;Ljava/util/List;II)V",
            at = @At("HEAD"), cancellable = true, require = 1)
    private static void gtShanhai$skipNativeTooltipWhileDrawingCustomButtonHighlights(
            GuiGraphics graphics, List<Component> text, int mouseX, int mouseY, CallbackInfo ci) {
        if (JeiPatternQuickEncodeButtons.isDrawingTransferHighlights(graphics)) {
            ci.cancel();
        }
    }
}
