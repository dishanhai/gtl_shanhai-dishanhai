package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.client.shop.ShopItemHotkey;
import com.llamalad7.mixinextras.sugar.Local;
import com.lowdragmc.lowdraglib.gui.widget.TankWidget;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** 机器流体槽悬停时，按住商店键跳到对应流体商品。 */
@Mixin(value = TankWidget.class, remap = false)
public class TankWidgetShopJumpMixin {

    @Shadow(remap = false)
    protected com.lowdragmc.lowdraglib.side.fluid.FluidStack lastFluidInTank;

    @Inject(method = "drawInForeground",
            at = @At(value = "INVOKE", target = "Lcom/lowdragmc/lowdraglib/gui/modular/ModularUIGuiContainer;setHoverTooltip(Ljava/util/List;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/client/gui/Font;Lnet/minecraft/world/inventory/tooltip/TooltipComponent;)V"),
            remap = false)
    private void gtShanhai$offerFluidShop(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks,
                                           CallbackInfo ci, @Local List<Component> tooltips) {
        if (lastFluidInTank == null || lastFluidInTank.isEmpty()) {
            ShopItemHotkey.offerFluid(tooltips, null);
            return;
        }
        ResourceLocation id = ForgeRegistries.FLUIDS.getKey(lastFluidInTank.getFluid());
        ShopItemHotkey.offerFluid(tooltips, id);
    }
}
