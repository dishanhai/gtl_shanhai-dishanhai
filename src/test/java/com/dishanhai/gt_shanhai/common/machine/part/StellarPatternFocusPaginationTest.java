package com.dishanhai.gt_shanhai.common.machine.part;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 山海：焦點使用全域槽號，頁碼與其他開啟中的分頁互不干擾。 */
class StellarPatternFocusPaginationTest {
    @Test
    void openingDistantSlotsSelectsTheirOwnPageWithoutMovingAnotherViewer() {
        var original = new CachedPatternPaginationUIManager(10, 10, 10, 196, 208,
                slot -> {}, slot -> false, slot -> false, null, () -> null);
        var first = original.copyForFocus(203);
        var second = original.copyForFocus(999);
        assertEquals(2, first.getCurrentPageIndex());
        assertEquals(9, second.getCurrentPageIndex());
        assertEquals(0, original.getCurrentPageIndex());
        assertEquals(0, original.copyForFocus(99).getCurrentPageIndex());
        assertEquals(1, original.copyForFocus(100).getCurrentPageIndex());
        assertNotSame(original, first);
        assertNotSame(first, second);
        assertThrows(IllegalArgumentException.class, () -> original.copyForFocus(-1));
        assertThrows(IllegalArgumentException.class, () -> original.copyForFocus(1000));
    }
}
