package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.client.recipe.ShanhaiRecipeEditorJeiSide;
import mezz.jei.api.gui.handlers.IGuiProperties;
import mezz.jei.common.config.IClientConfig;
import mezz.jei.common.util.ImmutableRect2i;
import mezz.jei.gui.GuiProperties;
import mezz.jei.gui.overlay.IngredientListOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Keeps the ingredient list and its search field above the bookmark band. */
@Mixin(IngredientListOverlay.class)
public class RecipeEditorJeiListSideMixin {

    @Redirect(
            method = "onScreenPropertiesChanged",
            at = @At(
                    value = "INVOKE",
                    target = "Lmezz/jei/gui/overlay/IngredientListOverlay;createDisplayArea(Lmezz/jei/api/gui/handlers/IGuiProperties;)Lmezz/jei/common/util/ImmutableRect2i;"),
            remap = false)
    private ImmutableRect2i gtShanhai$searchBelowBookmarks(IGuiProperties guiProperties) {
        if (ShanhaiRecipeEditorJeiSide.editorOpen()) {
            return ShanhaiRecipeEditorJeiSide.searchArea(guiProperties);
        }
        return GuiProperties.getScreenRectangle(guiProperties)
                .cropLeft(GuiProperties.getGuiRight(guiProperties));
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
