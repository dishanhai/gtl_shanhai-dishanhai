package com.dishanhai.gt_shanhai.client.holo;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShanhaiHoloVisibilityTest {
    @Test
    void invisibleBackSideCannotActivateThePanel() {
        var boards = new ShanhaiHoloMenuLayout.Board[]{new ShanhaiHoloMenuLayout.Board(0, 0, 0, 0, 0, 0, 1)};
        assertEquals(1, ShanhaiHoloMenuHitTest.allHitsFor(boards, 0, 0, 0, 0,
                0, 0, 2, 0, 0, -1).size());
        assertTrue(ShanhaiHoloMenuHitTest.allHitsFor(boards, 0, 0, 0, 0,
                0, 0, -2, 0, 0, 1).isEmpty());
    }

    @Test
    void rotatedPanelUsesItsOwnVisibleFace() {
        var boards = new ShanhaiHoloMenuLayout.Board[]{new ShanhaiHoloMenuLayout.Board(0, 0, 0, 0, 0, 180, 1)};
        assertEquals(1, ShanhaiHoloMenuHitTest.allHitsFor(boards, 0, 0, 0, 0,
                0, 0, -2, 0, 0, 1).size());
        assertTrue(ShanhaiHoloMenuHitTest.allHitsFor(boards, 0, 0, 0, 0,
                0, 0, 2, 0, 0, -1).isEmpty());
    }

    @Test
    void existingLayoutRayContractsStillHold() {
        assertFalse(ShanhaiHoloMenuHitTest.selfTestLine().contains("FAILED"));
    }
}
