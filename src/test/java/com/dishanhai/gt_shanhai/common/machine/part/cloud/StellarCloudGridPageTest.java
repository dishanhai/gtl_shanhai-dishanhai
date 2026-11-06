package com.dishanhai.gt_shanhai.common.machine.part.cloud;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 山海：分頁及預覽僅改顯示索引，不取代原件 UUID。 */
class StellarCloudGridPageTest {
    @Test
    void pageTurnsAndFilteringSelectTheVisibleOriginalUuid() {
        List<UUID> originals = new ArrayList<>();
        for (int i = 0; i < 73; i++) originals.add(new UUID(0, i + 1));
        UUID secondPageFirst = originals.get(StellarCloudGridPage.index(1, 0, originals.size()));
        assertEquals(new UUID(0, 37), secondPageFirst);
        assertEquals(new UUID(0, 72), originals.get(StellarCloudGridPage.index(1, 35, originals.size())));
        List<UUID> filtered = List.of(originals.get(70), originals.get(3));
        assertEquals(0, StellarCloudGridPage.clamp(2, filtered.size()));
        assertEquals(originals.get(70), filtered.get(StellarCloudGridPage.index(2, 0, filtered.size())));
        assertEquals(originals.get(3), filtered.get(StellarCloudGridPage.index(2, 1, filtered.size())));
        assertEquals(-1, StellarCloudGridPage.index(0, 2, filtered.size()));
    }

    @Test
    void emptyListsNegativePagesAndOversizedCellIndexesCannotSelectAnOriginal() {
        assertEquals(-1, StellarCloudGridPage.index(0, 0, 0));
        assertEquals(-1, StellarCloudGridPage.index(0, -1, 50));
        assertEquals(-1, StellarCloudGridPage.index(0, 36, 50));
        assertEquals(0, StellarCloudGridPage.index(-5, 0, 50));
        assertEquals(72, StellarCloudGridPage.index(Integer.MAX_VALUE, 0, 73));
        assertEquals(-1, StellarCloudGridPage.index(Integer.MAX_VALUE, 1, 73));
        assertEquals(-1, StellarCloudGridPage.index(Integer.MAX_VALUE, 35, Integer.MAX_VALUE));
    }

    @Test
    void repeatedSuppliersShareSixteenSearchDecodesPerTick() {
        var budget = new StellarCloudPreviewBudget();
        for (int i = 0; i < 16; i++) assertTrue(budget.take(100));
        for (int i = 0; i < 100; i++) assertFalse(budget.take(100));
        assertTrue(budget.take(101));
        for (int i = 1; i < 16; i++) assertTrue(budget.take(101));
        assertFalse(budget.take(101));
    }
}
