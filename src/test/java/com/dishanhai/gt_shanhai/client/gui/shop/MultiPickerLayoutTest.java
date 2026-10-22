package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.gt_shanhai.client.gui.glass.GlassGeometry;
import com.dishanhai.gt_shanhai.client.gui.glass.GuiRect;
import com.dishanhai.gt_shanhai.client.gui.glass.PixelRect;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiPickerLayoutTest {

    private static final List<String> MODULES = List.of("minecraft", "a", "minecraft", "a", "minecraft");

    @Test
    void threeColumnsReserveSearchHeaderAndFooterSpace() {
        PickerGeometry geometry = MultiPickerLayout.geometry(600, 380);
        GuiRect sidebar = geometry.getSidebar();
        GuiRect grid = geometry.getGrid();
        GuiRect selection = geometry.getSelection();
        GuiRect search = geometry.getSearch();
        GuiRect footer = geometry.getFooter();

        assertEquals(104f, sidebar.getW());
        assertEquals(234f, selection.getW());
        assertTrue(sidebar.getX() + sidebar.getW() < grid.getX());
        assertTrue(grid.getX() + grid.getW() < selection.getX());
        assertTrue(selection.getX() + selection.getW() <= 588f);
        assertEquals(grid.getX(), search.getX());
        assertEquals(grid.getW(), search.getW());
        assertTrue(search.getY() >= 36f);
        assertTrue(search.getY() + search.getH() < grid.getY());
        for (GuiRect content : List.of(sidebar, grid, selection)) {
            assertTrue(content.getW() > 0f && content.getH() > 0f);
            assertTrue(content.getY() + content.getH() < footer.getY());
        }
        assertTrue(footer.getY() + footer.getH() <= 368f);
    }

    @Test
    void largerPanelsGiveExtraSpaceToTheGrid() {
        PickerGeometry base = MultiPickerLayout.geometry(600, 380);
        PickerGeometry larger = MultiPickerLayout.geometry(800, 500);

        assertEquals(base.getSidebar().getW(), larger.getSidebar().getW());
        assertEquals(base.getSelection().getW(), larger.getSelection().getW());
        assertEquals(base.getGrid().getW() + 200f, larger.getGrid().getW());
        assertEquals(base.getGrid().getH() + 120f, larger.getGrid().getH());
    }

    @Test
    void expandedTopHeaderMovesSearchAndContentWithoutMovingFooter() {
        PickerGeometry base = MultiPickerLayout.geometry(600, 380);

        assertEquals(base, MultiPickerLayout.geometry(600, 380, 0));
        for (int extraHeight : List.of(24, 500)) {
            PickerGeometry expanded = MultiPickerLayout.geometry(600, 380, extraHeight);
            GuiRect search = base.getSearch();

            assertEquals(new GuiRect(search.getX(), search.getY() + extraHeight,
                    search.getW(), search.getH()), expanded.getSearch());
            assertEquals(base.getFooter(), expanded.getFooter());
            List<GuiRect> originalContent = List.of(base.getSidebar(), base.getGrid(), base.getSelection());
            List<GuiRect> expandedContent = List.of(expanded.getSidebar(), expanded.getGrid(), expanded.getSelection());
            for (int column = 0; column < originalContent.size(); column++) {
                GuiRect original = originalContent.get(column);
                assertEquals(new GuiRect(original.getX(), original.getY() + extraHeight,
                        original.getW(), Math.max(0f, original.getH() - extraHeight)), expandedContent.get(column));
            }
        }
    }

    @Test
    void pixelMappingUsesTheExistingGlassGeometry() {
        GuiRect grid = MultiPickerLayout.geometry(600, 380).getGrid();
        GuiRect transformed = GlassGeometry.applyAffine(grid, 0.5f, 0.5f, 2f, 3f);
        PixelRect pixels = GlassGeometry.toPixels(transformed, 1600, 900, 800, 450);

        assertEquals(new PixelRect(Math.round(grid.getX() + 4f), Math.round(grid.getY() + 6f),
                Math.round(grid.getW()), Math.round(grid.getH())), pixels);
    }

    @Test
    void flatRowsPreserveFilteredSourceOrderAndIgnoreCollapse() {
        List<PickerGridRow> rows = MultiPickerLayout.gridRows(List.of(4, 1, 2, 0, 3), MODULES,
                List.of("a", "minecraft"), 2, false, Set.of("minecraft", "a"));

        assertEquals(List.of(new PickerGridRow(0, 22, "", false, List.of(4, 1)),
                new PickerGridRow(22, 22, "", false, List.of(2, 0)),
                new PickerGridRow(44, 22, "", false, List.of(3))), rows);
        assertEquals(66, MultiPickerLayout.totalHeight(rows));
    }

    @Test
    void groupedRowsResolveNamespacesBySourceIndexAndKeepOrderWithinGroups() {
        List<PickerGridRow> rows = MultiPickerLayout.gridRows(List.of(4, 1, 2, 0, 3), MODULES,
                List.of("minecraft", "a"), 2, true, Set.of());

        assertEquals(List.of(new PickerGridRow(0, 18, "minecraft", true, List.of()),
                new PickerGridRow(18, 22, "minecraft", false, List.of(4, 2)),
                new PickerGridRow(40, 22, "minecraft", false, List.of(0)),
                new PickerGridRow(62, 18, "a", true, List.of()),
                new PickerGridRow(80, 22, "a", false, List.of(1, 3))), rows);
        assertEquals(102, MultiPickerLayout.totalHeight(rows));
    }

    @Test
    void longSectionHeadersShiftRowsAndKeepHitTestingAndStickyHeadersAligned() {
        List<Integer> indices = List.of(4, 1, 2, 0, 3);
        List<String> moduleOrder = List.of("minecraft", "a");
        Map<String, Integer> headerHeights = Map.of("minecraft", 42, "a", 7);
        List<PickerGridRow> rows = MultiPickerLayout.gridRows(indices, MODULES,
                moduleOrder, 2, true, Set.of(), headerHeights);

        assertEquals(List.of(new PickerGridRow(0, 42, "minecraft", true, List.of()),
                new PickerGridRow(42, 22, "minecraft", false, List.of(4, 2)),
                new PickerGridRow(64, 22, "minecraft", false, List.of(0)),
                new PickerGridRow(86, 18, "a", true, List.of()),
                new PickerGridRow(104, 22, "a", false, List.of(1, 3))), rows);
        assertEquals(rows, MultiPickerLayout.gridRows(indices, MODULES,
                moduleOrder, 2, true, Set.of(), Map.of("minecraft", 42)));
        assertEquals(126, MultiPickerLayout.totalHeight(rows));
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            PickerGridRow row = rows.get(rowIndex);
            assertEquals(rowIndex, MultiPickerLayout.rowAtY(rows, row.getY()));
            assertEquals(rowIndex, MultiPickerLayout.rowAtY(rows, row.getY() + row.getHeight() - 1));
        }
        assertEquals(-1, MultiPickerLayout.rowAtY(rows, 126));
        assertEquals(0, MultiPickerLayout.stickyHeader(rows, 85));
        assertEquals(3, MultiPickerLayout.stickyHeader(rows, 86));
        assertEquals(3, MultiPickerLayout.stickyHeader(rows, 126));

        List<PickerGridRow> collapsed = MultiPickerLayout.gridRows(indices, MODULES,
                moduleOrder, 2, true, Set.of("minecraft"), headerHeights);
        assertEquals(List.of(new PickerGridRow(0, 42, "minecraft", true, List.of()),
                new PickerGridRow(42, 18, "a", true, List.of()),
                new PickerGridRow(60, 22, "a", false, List.of(1, 3))), collapsed);
        assertEquals(82, MultiPickerLayout.totalHeight(collapsed));
        assertEquals(1, MultiPickerLayout.stickyHeader(collapsed, 42));
    }

    @Test
    void collapsedGroupsKeepHeadersAndRemoveOnlyTheirItemRows() {
        List<PickerGridRow> rows = MultiPickerLayout.gridRows(List.of(4, 1, 2, 0, 3), MODULES,
                List.of("minecraft", "a"), 2, true, Set.of("minecraft"));

        assertEquals(List.of(new PickerGridRow(0, 18, "minecraft", true, List.of()),
                new PickerGridRow(18, 18, "a", true, List.of()),
                new PickerGridRow(36, 22, "a", false, List.of(1, 3))), rows);
        assertEquals(58, MultiPickerLayout.totalHeight(rows));
    }

    @Test
    void missingAndRepeatedModuleNamesDoNotLoseOrDuplicateFilteredEntries() {
        List<PickerGridRow> rows = MultiPickerLayout.gridRows(List.of(3, 4, 1), MODULES,
                List.of("empty", "minecraft", "minecraft"), 2, true, Set.of());

        assertEquals(List.of(new PickerGridRow(0, 18, "minecraft", true, List.of()),
                new PickerGridRow(18, 22, "minecraft", false, List.of(4)),
                new PickerGridRow(40, 18, "a", true, List.of()),
                new PickerGridRow(58, 22, "a", false, List.of(3, 1))), rows);
    }

    @Test
    void emptyResultsHaveNoHeadersOrHeight() {
        List<PickerGridRow> rows = MultiPickerLayout.gridRows(List.of(), MODULES,
                List.of("minecraft", "a"), 2, true, Set.of());

        assertTrue(rows.isEmpty());
        assertEquals(0, MultiPickerLayout.totalHeight(rows));
        assertEquals(-1, MultiPickerLayout.rowAtY(rows, 0));
        assertEquals(-1, MultiPickerLayout.stickyHeader(rows, 0));
    }

    @Test
    void nonPositiveColumnsStillProduceSingleItemRows() {
        for (int columns : List.of(0, -1)) {
            List<PickerGridRow> rows = MultiPickerLayout.gridRows(List.of(4, 1), MODULES,
                    List.of(), columns, false, Set.of());

            assertEquals(List.of(new PickerGridRow(0, 22, "", false, List.of(4)),
                    new PickerGridRow(22, 22, "", false, List.of(1))), rows);
        }
    }

    @Test
    void gridHitTestingUsesInclusiveStartsAndExclusiveBottoms() {
        List<PickerGridRow> rows = MultiPickerLayout.gridRows(List.of(4, 1, 2), MODULES,
                List.of("minecraft", "a"), 2, true, Set.of());

        assertEquals(-1, MultiPickerLayout.rowAtY(rows, -1));
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            PickerGridRow row = rows.get(rowIndex);
            assertEquals(rowIndex, MultiPickerLayout.rowAtY(rows, row.getY()));
            assertEquals(rowIndex, MultiPickerLayout.rowAtY(rows, row.getY() + row.getHeight() - 1));
        }
        assertEquals(-1, MultiPickerLayout.rowAtY(rows, MultiPickerLayout.totalHeight(rows)));
        assertEquals(-1, MultiPickerLayout.rowAtY(rows, Integer.MAX_VALUE));
    }

    @Test
    void stickyHeaderChangesExactlyAtTheNextHeader() {
        List<PickerGridRow> rows = MultiPickerLayout.gridRows(List.of(4, 1, 2, 0, 3), MODULES,
                List.of("minecraft", "a"), 2, true, Set.of());

        assertEquals(-1, MultiPickerLayout.stickyHeader(rows, -1));
        assertEquals(0, MultiPickerLayout.stickyHeader(rows, 0));
        assertEquals(0, MultiPickerLayout.stickyHeader(rows, 61));
        assertEquals(3, MultiPickerLayout.stickyHeader(rows, 62));
        assertEquals(3, MultiPickerLayout.stickyHeader(rows, 101));
        assertEquals(3, MultiPickerLayout.stickyHeader(rows, 102));
        List<PickerGridRow> flat = MultiPickerLayout.gridRows(List.of(4, 1), MODULES,
                List.of(), 2, false, Set.of());
        assertFalse(flat.getFirst().getHeader());
        assertEquals(-1, MultiPickerLayout.stickyHeader(flat, 0));
    }

    @Test
    void collapsedFinalGroupRemainsTheStickyHeader() {
        List<PickerGridRow> rows = MultiPickerLayout.gridRows(List.of(4, 1), MODULES,
                List.of("minecraft", "a"), 2, true, Set.of("a"));

        assertEquals(2, MultiPickerLayout.stickyHeader(rows, 40));
        assertEquals(2, MultiPickerLayout.rowAtY(rows, 57));
        assertEquals(-1, MultiPickerLayout.rowAtY(rows, 58));
    }

    @Test
    void selectionStartsAndHitTestingRespectVariableRowHeights() {
        List<Integer> heights = List.of(30, 70, 20);
        int[] starts = MultiPickerLayout.rowStarts(heights);

        assertArrayEquals(new int[]{0, 30, 100}, starts);
        assertEquals(-1, MultiPickerLayout.selectionRowAt(starts, heights, -1));
        assertEquals(0, MultiPickerLayout.selectionRowAt(starts, heights, 0));
        assertEquals(0, MultiPickerLayout.selectionRowAt(starts, heights, 29));
        assertEquals(1, MultiPickerLayout.selectionRowAt(starts, heights, 30));
        assertEquals(1, MultiPickerLayout.selectionRowAt(starts, heights, 99));
        assertEquals(2, MultiPickerLayout.selectionRowAt(starts, heights, 100));
        assertEquals(2, MultiPickerLayout.selectionRowAt(starts, heights, 119));
        assertEquals(-1, MultiPickerLayout.selectionRowAt(starts, heights, 120));
    }

    @Test
    void insertionTargetsTheNearestRowBoundaryAndClampsOutsideTheList() {
        List<Integer> heights = List.of(30, 70, 21);
        int[] starts = MultiPickerLayout.rowStarts(heights);

        assertEquals(0, MultiPickerLayout.insertionIndex(starts, heights, -100));
        assertEquals(0, MultiPickerLayout.insertionIndex(starts, heights, 14));
        assertEquals(1, MultiPickerLayout.insertionIndex(starts, heights, 15));
        assertEquals(1, MultiPickerLayout.insertionIndex(starts, heights, 64));
        assertEquals(2, MultiPickerLayout.insertionIndex(starts, heights, 65));
        assertEquals(2, MultiPickerLayout.insertionIndex(starts, heights, 110));
        assertEquals(3, MultiPickerLayout.insertionIndex(starts, heights, 111));
        assertEquals(3, MultiPickerLayout.insertionIndex(starts, heights, 121));
        assertEquals(3, MultiPickerLayout.insertionIndex(starts, heights, Integer.MAX_VALUE));
    }

    @Test
    void emptySelectionHasNoHitAndOnlyOneInsertionPosition() {
        int[] starts = MultiPickerLayout.rowStarts(List.of());

        assertArrayEquals(new int[0], starts);
        assertEquals(-1, MultiPickerLayout.selectionRowAt(starts, List.of(), 0));
        assertEquals(0, MultiPickerLayout.insertionIndex(starts, List.of(), -1));
        assertEquals(0, MultiPickerLayout.insertionIndex(starts, List.of(), 100));
    }
}
