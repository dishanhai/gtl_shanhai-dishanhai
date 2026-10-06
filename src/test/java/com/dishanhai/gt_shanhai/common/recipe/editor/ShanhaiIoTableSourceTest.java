package com.dishanhai.gt_shanhai.common.recipe.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ShanhaiIoTableSourceTest {

    @Test
    void actualRecipeUseCanExceedDeclaredTypeCapacity() {
        assertArrayEquals(
                new int[] {9, 3, 19862, 3},
                ShanhaiIoTable.shapeFor(
                        new int[] {9, 1, 1, 3},
                        new int[] {3, 0, 19862, 0}));
    }

    @Test
    void visibleIndicesFollowIndependentInputAndOutputPages() {
        ShanhaiIoTable table = new ShanhaiIoTable(2, 1, 70, 1);

        assertArrayEquals(
                new int[] {0, 1, 2, 3, 4, 5, 6},
                table.visibleIndices(0, 0, 4));
        assertEquals(
                ShanhaiIoTable.Kind.ITEM_OUT,
                table.kindOf(table.outIndex(0)));
    }

    @Test
    void truncationRiskRejectsSilentLoss() {
        ShanhaiIoTable table = new ShanhaiIoTable(1, 0, 1, 0);
        String risk = ShanhaiIoTable.truncationRisk(new int[] {1, 0, 2, 0}, table);

        assertEquals("item-output requires 2 cells but table has 1", risk);
        assertNull(ShanhaiIoTable.truncationRisk(new int[] {1, 0, 1, 0}, table));
    }
}
