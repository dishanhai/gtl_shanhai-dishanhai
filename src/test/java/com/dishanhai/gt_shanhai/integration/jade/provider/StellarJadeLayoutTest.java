package com.dishanhai.gt_shanhai.integration.jade.provider;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StellarJadeLayoutTest {
    @Test
    void fitsExpandedPanelAndReservesSpaceForNativeRows() {
        assertEquals(216, StellarJadeProvider.visiblePanelHeight(255, 24, 240, 0, 0, 1));
        assertEquals(255, StellarJadeProvider.visiblePanelHeight(255, 24, 720, 0, 0, 1));
    }

    @Test
    void fitsWithinTheConfiguredAnchorAndActualJadeScale() {
        assertEquals(180, StellarJadeProvider.visiblePanelHeight(255, 20, 240, 200, 1, 1));
        assertEquals(100, StellarJadeProvider.visiblePanelHeight(255, 20, 240, 90, .5f, 1.5f));
        assertEquals(0, StellarJadeProvider.visiblePanelHeight(255, 24, 240, 230, 0, 1));
    }
}
