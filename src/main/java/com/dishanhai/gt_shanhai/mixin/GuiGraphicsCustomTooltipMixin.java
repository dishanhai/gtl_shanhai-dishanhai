package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.client.renderer.HaloEndTooltipRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Optional;

/**
 * 拦截 GuiGraphics.renderTooltip 渲染，对终末之环走自定义 tooltip 路径。
 */
@Mixin(value = GuiGraphics.class, remap = false)
public class GuiGraphicsCustomTooltipMixin {

    @Inject(method = "renderTooltip",
            at = @At("HEAD"),
            remap = false,
            require = 0,
            cancellable = true)
    private void onRenderTooltip(Font font, List<Component> lines, Optional<Component> tooltipComponent, ItemStack stack, int x, int y,
                                  CallbackInfo ci) {
        if (stack == null || stack.isEmpty() || lines == null || lines.isEmpty()) return;
        if (!HaloEndTooltipRenderer.shouldRender(stack)) return;

        GuiGraphics self = (GuiGraphics) (Object) this;
        Font useFont = font != null ? font : Minecraft.getInstance().font;
        HaloEndTooltipRenderer.render(self, stack, x, y, lines, useFont);
        ci.cancel();
    }
}
