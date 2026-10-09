package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.client.shop.ShopJeiModeButton;
import mezz.jei.api.gui.handlers.IGuiProperties;
import mezz.jei.common.util.ImmutableRect2i;
import mezz.jei.gui.bookmarks.BookmarkList;
import mezz.jei.gui.elements.GuiIconToggleButton;
import mezz.jei.gui.input.IUserInputHandler;
import mezz.jei.gui.input.handlers.CombinedInputHandler;
import mezz.jei.gui.overlay.bookmarks.BookmarkOverlay;
import mezz.jei.gui.overlay.bookmarks.history.LookupHistoryOverlay;
import mezz.jei.gui.overlay.ingredients.IngredientGridWithNavigation;
import mezz.jei.gui.overlay.ScreenPropertiesCache;
import mezz.jei.common.config.IClientConfig;
import mezz.jei.common.config.IClientToggleState;
import mezz.jei.common.config.IIngredientGridConfig;
import mezz.jei.common.input.IInternalKeyMappings;
import mezz.jei.api.runtime.IScreenHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = BookmarkOverlay.class, remap = false)
public abstract class JeiShopModeButtonMixin {

    @Shadow @Final private GuiIconToggleButton historyButton;
    @Shadow @Final private ScreenPropertiesCache screenPropertiesCache;

    @Unique private ShopJeiModeButton gtShanhai$shopModeButton;

    @Inject(method = "<init>", at = @At("TAIL"), remap = false)
    private void gtShanhai$createShopModeButton(BookmarkList bookmarkList,
                                               IngredientGridWithNavigation contents,
                                               LookupHistoryOverlay lookupHistoryOverlay,
                                               IClientToggleState toggleState,
                                               IClientConfig clientConfig,
                                               IIngredientGridConfig gridConfig,
                                               IScreenHelper screenHelper,
                                               IInternalKeyMappings keyMappings,
                                               CallbackInfo ci) {
        gtShanhai$shopModeButton = new ShopJeiModeButton();
    }

    @Inject(method = "updateBounds", at = @At("TAIL"), remap = false)
    private void gtShanhai$positionShopModeButton(IGuiProperties guiProperties, CallbackInfo ci) {
        if (gtShanhai$shopModeButton == null || !screenPropertiesCache.hasValidScreen()) return;
        ImmutableRect2i historyArea = ((JeiIconToggleButtonAccessor) historyButton).gtShanhai$getArea();
        if (historyArea != null) gtShanhai$shopModeButton.updateBounds(historyArea.moveRight(22));
    }

    @Inject(method = "drawScreen", at = @At("TAIL"), remap = false)
    private void gtShanhai$drawShopModeButton(Minecraft minecraft, GuiGraphics graphics,
                                              int mouseX, int mouseY, float partialTick,
                                              CallbackInfo ci) {
        if (gtShanhai$shopModeButton == null || !screenPropertiesCache.hasValidScreen()) return;
        gtShanhai$shopModeButton.draw(graphics, mouseX, mouseY, partialTick);
    }

    @Inject(method = "drawTooltips", at = @At("TAIL"), remap = false)
    private void gtShanhai$drawShopModeTooltip(Minecraft minecraft, GuiGraphics graphics,
                                               int mouseX, int mouseY, CallbackInfo ci) {
        if (gtShanhai$shopModeButton == null || !screenPropertiesCache.hasValidScreen()) return;
        gtShanhai$shopModeButton.drawTooltips(graphics, mouseX, mouseY);
    }

    @Inject(method = "createInputHandler", at = @At("RETURN"), cancellable = true, remap = false)
    private void gtShanhai$addShopModeInputHandler(CallbackInfoReturnable<IUserInputHandler> cir) {
        if (gtShanhai$shopModeButton == null) return;
        cir.setReturnValue(new CombinedInputHandler("ShanhaiShopModeButton",
                cir.getReturnValue(), gtShanhai$shopModeButton.createInputHandler()));
    }
}
