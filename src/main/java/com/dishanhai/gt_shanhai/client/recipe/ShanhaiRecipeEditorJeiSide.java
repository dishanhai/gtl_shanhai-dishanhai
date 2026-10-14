package com.dishanhai.gt_shanhai.client.recipe;

import com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeEditorWidget;
import com.lowdragmc.lowdraglib.gui.modular.ModularUIGuiContainer;
import mezz.jei.api.gui.handlers.IGuiProperties;
import mezz.jei.common.Internal;
import mezz.jei.common.config.HistoryDisplaySide;
import mezz.jei.common.config.IClientConfig;
import mezz.jei.common.config.IIngredientGridConfig;
import mezz.jei.common.util.ImmutableRect2i;
import net.minecraft.client.Minecraft;

/**
 * While the recipe editor is open, the ingredient list stays in the right
 * column and stops above the bookmark band. JEI anchors the search field to
 * the bottom of that list area, so the band also has to clear the bookmark
 * and history buttons.
 * <p>Heights follow JEI 15.49: a grid is inset by 6, page nav takes 22, and
 * the button row is inset 6 plus a 20px button. History, when it shares a
 * side, sits above that 26px row with a 4px gap.
 */
public final class ShanhaiRecipeEditorJeiSide {

    private static final int SLOT = 18;
    /** Three bookmark rows after the no-history chrome (inset + nav + button row). */
    private static final int BOOKMARK_BODY = 110;
    /** Same three rows once history has taken {@code historyHeight + 70} of chrome. */
    private static final int BOOKMARK_BODY_WITH_HISTORY = 124;
    private static final int LIST_FLOOR = 128;
    private static final int LIST_FLOOR_WITH_HISTORY = 142;
    /** {@link mezz.jei.gui.overlay.bookmarks.history.LookupHistoryOverlayLayout#getDisplayHeight}. */
    private static final int HISTORY_BACKGROUND = 14;

    private ShanhaiRecipeEditorJeiSide() {}

    public static boolean editorOpen() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.screen instanceof ModularUIGuiContainer gui)) return false;
        return gui.modularUI != null
                && gui.modularUI.getFirstWidgetById("shanhai_recipe_editor") != null;
    }

    public static int bookmarkBand(int screenHeight) {
        return bookmarkBand(screenHeight, historyPixels(true), historyPixels(false));
    }

    /**
     * Bottom strip reserved for bookmarks. The ingredient list, including its
     * search field, is limited to everything above this strip.
     */
    static int bookmarkBand(int screenHeight, int historyOnBookmarks, int historyOnList) {
        if (screenHeight <= 0) return 0;
        int wanted = bookmarkBody(historyOnBookmarks);
        int max = Math.max(0, screenHeight - listFloor(historyOnList));
        if (wanted > max) wanted = max;
        return Math.max(0, wanted);
    }

    static int bookmarkBody(int historyHeight) {
        if (historyHeight <= 0) return BOOKMARK_BODY;
        return BOOKMARK_BODY_WITH_HISTORY + historyHeight;
    }

    static int listFloor(int historyOnList) {
        if (historyOnList <= 0) return LIST_FLOOR;
        return LIST_FLOOR_WITH_HISTORY + historyOnList;
    }

    /** Right column, full height, capped at {@link ShanhaiRecipeEditorWidget#JEI_SIDE_WIDTH}. */
    public static ImmutableRect2i rightColumn(IGuiProperties gui) {
        int x = gui.getGuiLeft() + gui.getGuiXSize();
        int available = gui.getScreenWidth() - x;
        int width = available <= 0 ? 0 : Math.min(ShanhaiRecipeEditorWidget.JEI_SIDE_WIDTH, available);
        return new ImmutableRect2i(x, 0, width, Math.max(0, gui.getScreenHeight()));
    }

    public static ImmutableRect2i bookmarkArea(IGuiProperties gui) {
        ImmutableRect2i column = rightColumn(gui);
        int band = Math.min(bookmarkBand(column.getHeight()), column.getHeight());
        int y = column.getY() + column.getHeight() - band;
        return new ImmutableRect2i(column.getX(), y, column.getWidth(), band);
    }

    public static ImmutableRect2i searchArea(IGuiProperties gui) {
        return rightColumn(gui).cropBottom(bookmarkArea(gui).getHeight());
    }

    /** Rows of bookmarks that fit in {@code band} after JEI 15.49 takes its chrome. */
    static int bookmarkRows(int band, int historyHeight) {
        int grid = historyHeight <= 0 ? band - 56 : band - historyHeight - 70;
        if (grid <= 0) return 0;
        return grid / SLOT;
    }

    /** Rows of the ingredient list that fit above the bookmark band. */
    static int listRows(int screenHeight, int historyOnBookmarks, int historyOnList) {
        int band = bookmarkBand(screenHeight, historyOnBookmarks, historyOnList);
        int listHeight = Math.max(0, screenHeight - band);
        int grid = historyOnList <= 0 ? listHeight - 56 : listHeight - historyOnList - 70;
        if (grid <= 0) return 0;
        return grid / SLOT;
    }

    private static int historyPixels(boolean bookmarkSide) {
        try {
            IClientConfig config = Internal.getJeiClientConfigs().getClientConfig();
            if (!config.isLookupHistoryEnabled()) return 0;
            boolean onBookmarks = config.getLookupHistoryDisplaySide() == HistoryDisplaySide.LEFT;
            if (onBookmarks != bookmarkSide) return 0;
            IIngredientGridConfig grid = bookmarkSide
                    ? Internal.getJeiClientConfigs().getBookmarkListConfig()
                    : Internal.getJeiClientConfigs().getIngredientListConfig();
            int rows = Math.max(0, config.getMaxLookupHistoryRows());
            int height = rows * SLOT;
            if (grid.drawBackground()) height += HISTORY_BACKGROUND;
            return height;
        } catch (Throwable ignored) {
            return 0;
        }
    }
}
