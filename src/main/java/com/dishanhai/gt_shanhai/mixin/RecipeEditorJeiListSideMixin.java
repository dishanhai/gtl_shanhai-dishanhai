package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.client.recipe.ShanhaiRecipeEditorJeiSide;
import mezz.jei.api.gui.handlers.IGuiProperties;
import mezz.jei.common.config.IClientConfig;
import mezz.jei.common.util.ImmutableRect2i;
import mezz.jei.gui.GuiProperties;
import mezz.jei.gui.overlay.IngredientListOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Caps the ingredient list while the recipe editor is open.
 * JEI 15.49 builds the list area inside a lambda, so a redirect on the
 * screen-properties callback never runs. The list then keeps the full column,
 * draws over the bookmarks, and parks the search field on the buttons.
 */
@Mixin(IngredientListOverlay.class)
public class RecipeEditorJeiListSideMixin {

    @Inject(
            method = "createDisplayArea",
            at = @At("RETURN"),
            cancellable = true,
            require = 1,
            remap = false)
    private static void gtShanhai$capListArea(
            IGuiProperties guiProperties, CallbackInfoReturnable<ImmutableRect2i> cir) {
        if (!ShanhaiRecipeEditorJeiSide.editorOpen()) return;
        cir.setReturnValue(ShanhaiRecipeEditorJeiSide.searchArea(guiProperties));
    }

    @Redirect(
            method = "updateBounds",
            at = @At(
                    value = "INVOKE",
                    target = "Lmezz/jei/gui/overlay/IngredientListOverlay;isSearchBarCentered(Lmezz/jei/common/config/IClientConfig;Lmezz/jei/api/gui/handlers/IGuiProperties;)Z"),
            remap = false)
    private boolean gtShanhai$searchStaysInColumn(IClientConfig clientConfig, IGuiProperties guiProperties) {
        if (ShanhaiRecipeEditorJeiSide.editorOpen()) return false;
        return clientConfig.isCenterSearchBarEnabled()
                && GuiProperties.getGuiBottom(guiProperties) + 20 < guiProperties.getScreenHeight();
    }
}
