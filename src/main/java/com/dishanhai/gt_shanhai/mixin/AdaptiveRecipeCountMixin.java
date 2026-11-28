package com.dishanhai.gt_shanhai.mixin;

// Author: dishanhai

import com.dishanhai.gt_shanhai.integration.jei.AdaptiveRecipeUI;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.lowdragmc.lowdraglib.gui.widget.SlotWidget;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = SlotWidget.class, remap = false)
abstract class AdaptiveRecipeCountMixin {
    @WrapOperation(method = "drawInBackground", at = @At(value = "INVOKE",
            target = "Lcom/lowdragmc/lowdraglib/gui/util/DrawerHelper;drawItemStack(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/world/item/ItemStack;IIILjava/lang/String;)V"))
    private void shanhai$fitCount(GuiGraphics graphics, ItemStack stack, int x, int y, int color, String label,
                                  Operation<Void> original) {
        if (label == null && stack.getCount() >= 10_000 && AdaptiveRecipeUI.usesCompactCount((SlotWidget) (Object) this)) {
            int count = stack.getCount();
            if (count >= 1_000_000_000) label = count / 1_000_000_000 + "G";
            else if (count >= 1_000_000) label = count / 1_000_000 + "M";
            else label = count / 1_000 + "k";
        }
        original.call(graphics, stack, x, y, color, label);
    }
}
