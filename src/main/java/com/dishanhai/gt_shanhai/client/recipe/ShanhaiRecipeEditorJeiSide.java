package com.dishanhai.gt_shanhai.client.recipe;

import com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeEditorWidget;
import com.lowdragmc.lowdraglib.gui.modular.ModularUIGuiContainer;
import mezz.jei.api.gui.handlers.IGuiProperties;
import mezz.jei.common.Internal;
import mezz.jei.common.config.HistoryDisplaySide;
import mezz.jei.common.config.IClientConfig;
import mezz.jei.common.util.ImmutableRect2i;
import net.minecraft.client.Minecraft;

/**
 * While the recipe editor is open, the search list stays in the right column
 * and bookmarks use the empty band under that list.
 * <p>JEI still subtracts the bookmark button row, the page-nav row, and any
 * lookup-history rows that share the bookmark side. The band includes those
 * pixels so the bookmark grid itself keeps three rows.
 */
public final class ShanhaiRecipeEditorJeiSide {

    /** Button row + page nav + border inset + three 18px bookmark rows. */
    private static final int BOOKMARK_BODY = 22 + 22 + 12 + 18 * 3;
    private static final int SLOT = 18;
    private static final int LIST_FLOOR = 96;

    private ShanhaiRecipeEditorJeiSide() {}

    public static boolean editorOpen() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.screen instanceof ModularUIGuiContainer gui)) return false;
        return gui.modularUI != null
                && gui.modularUI.getFirstWidgetById("shanhai_recipe_editor") != null;
    }

    public static int bookmarkBand(int screenHeight) {
        if (screenHeight <= 0) return 0;
        int wanted = BOOKMARK_BODY + historyPixels();
        int max = Math.max(0, screenHeight - LIST_FLOOR);
        if (wanted > max) wanted = Math.min(max, BOOKMARK_BODY);
        if (wanted < 80) wanted = Math.min(max, 80);
        return Math.max(0, wanted);
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

    /** Rows JEI will cut off the bottom of the bookmark area when history sits on the left. */
    private static int historyPixels() {
        try {
            IClientConfig config = Internal.getJeiClientConfigs().getClientConfig();
            if (!config.isLookupHistoryEnabled()) return 0;
            if (config.getLookupHistoryDisplaySide() != HistoryDisplaySide.LEFT) return 0;
            return Math.max(0, config.getMaxLookupHistoryRows()) * SLOT;
        } catch (Throwable ignored) {
            return 0;
        }
    }
}
