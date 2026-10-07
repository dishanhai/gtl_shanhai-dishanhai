package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.client.recipe.ShanhaiRecipeEditorJeiSide;
import mezz.jei.api.gui.handlers.IGuiProperties;
import mezz.jei.common.util.ImmutableRect2i;
import mezz.jei.gui.overlay.bookmarks.BookmarkOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Moves the bookmark list into the empty band under the recipe editor's search list. */
@Mixin(BookmarkOverlay.class)
public class RecipeEditorJeiBookmarkSideMixin {

    @Redirect(
            method = "updateBounds",
            at = @At(
                    value = "INVOKE",
                    target = "Lmezz/jei/gui/overlay/bookmarks/BookmarkOverlay;getDisplayArea(Lmezz/jei/api/gui/handlers/IGuiProperties;)Lmezz/jei/common/util/ImmutableRect2i;"),
            remap = false)
    private ImmutableRect2i gtShanhai$bookmarksOnRight(IGuiProperties guiProperties) {
        if (ShanhaiRecipeEditorJeiSide.editorOpen()) {
            return ShanhaiRecipeEditorJeiSide.bookmarkArea(guiProperties);
        }
        int width = guiProperties.getGuiLeft();
        if (width < 0) width = 0;
        return new ImmutableRect2i(0, 0, width, guiProperties.getScreenHeight());
    }
}
