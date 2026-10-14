package com.dishanhai.gt_shanhai.client.recipe;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShanhaiRecipeEditorJeiSideTest {

    @Test
    void listStopsAboveBookmarksAndSearchClearsButtons() {
        int[] heights = {360, 400, 480, 640, 800};
        int[] history = {0, 18, 36, 36 + 14};
        for (int screen : heights) {
            for (int rows : history) {
                assertSplit(screen, rows, 0);
                assertSplit(screen, 0, rows);
            }
        }
    }

    @Test
    void threeBookmarkRowsSurviveOnTheEditorScreen() {
        assertTrue(ShanhaiRecipeEditorJeiSide.bookmarkRows(
                ShanhaiRecipeEditorJeiSide.bookmarkBand(400, 0, 0), 0) >= 3);
        assertTrue(ShanhaiRecipeEditorJeiSide.bookmarkRows(
                ShanhaiRecipeEditorJeiSide.bookmarkBand(400, 36, 0), 36) >= 3);
        assertTrue(ShanhaiRecipeEditorJeiSide.listRows(400, 36, 0) >= 4);
        assertTrue(ShanhaiRecipeEditorJeiSide.listRows(400, 0, 36) >= 4);
    }

    @Test
    void listCapIsAppliedInsideCreateDisplayArea() throws IOException {
        String source = Files.readString(Path.of("src", "main", "java", "com", "dishanhai",
                "gt_shanhai", "mixin", "RecipeEditorJeiListSideMixin.java"));
        assertTrue(source.contains("method = \"createDisplayArea\""));
        assertFalse(source.contains("onScreenPropertiesChanged"));
    }

    private static void assertSplit(int screen, int historyOnBookmarks, int historyOnList) {
        int band = ShanhaiRecipeEditorJeiSide.bookmarkBand(screen, historyOnBookmarks, historyOnList);
        int listHeight = screen - band;
        Rect list = new Rect(0, listHeight);
        Rect bookmarks = new Rect(listHeight, band);
        Rect search = new Rect(list.bottom() - 26, 20);
        Rect buttons = new Rect(bookmarks.bottom() - 26, 20);
        assertFalse(search.intersects(buttons),
                "search covers buttons at " + screen + " history " + historyOnBookmarks + "/" + historyOnList);
        assertFalse(list.intersects(bookmarks),
                "list overlaps bookmarks at " + screen);

        int listGrid = ShanhaiRecipeEditorJeiSide.listRows(screen, historyOnBookmarks, historyOnList) * 18;
        int bookmarkGrid = ShanhaiRecipeEditorJeiSide.bookmarkRows(band, historyOnBookmarks) * 18;
        if (listGrid > 0 && bookmarkGrid > 0) {
            Rect listSlots = new Rect(6 + 22, listGrid);
            Rect bookmarkSlots = new Rect(bookmarks.y + 6 + 22, bookmarkGrid);
            assertFalse(listSlots.intersects(bookmarkSlots),
                    "item grid overlaps bookmark grid at " + screen);
        }
        if (screen >= 400 && historyOnBookmarks <= 36 && historyOnList <= 36) {
            assertTrue(ShanhaiRecipeEditorJeiSide.bookmarkRows(band, historyOnBookmarks) >= 3,
                    "bookmarks lost a row at " + screen + " history " + historyOnBookmarks);
            assertTrue(listHeight < screen,
                    "item list was not capped at " + screen);
        }
    }

    private static final class Rect {
        final int y;
        final int height;

        Rect(int y, int height) {
            this.y = y;
            this.height = height;
        }

        int bottom() {
            return y + height;
        }

        boolean intersects(Rect other) {
            return y < other.bottom() && bottom() > other.y && height > 0 && other.height > 0;
        }
    }
}
