package com.dishanhai.gt_shanhai.common.machine.primordial.guixu;

import org.junit.jupiter.api.Test;

import static com.dishanhai.gt_shanhai.common.machine.primordial.guixu.GuixuContainmentField.Status.*;
import static org.junit.jupiter.api.Assertions.*;

class GuixuBlackHolePreviewTest {
    @Test
    void playsBothOrbitsThenTransitionsThroughTheContainmentStates() {
        long start = 10_000;
        var injection = GuixuBlackHolePreview.sample(start, start);
        var orbiting = GuixuBlackHolePreview.sample(start, start + 80);
        var recovery = GuixuBlackHolePreview.sample(start, start + 160);
        assertEquals(CONTAINED, injection.status());
        assertEquals(0, injection.outerOrbit());
        assertTrue(orbiting.spin() > injection.spin());
        assertTrue(orbiting.innerOrbit() > 1 && orbiting.outerOrbit() > 1);
        assertEquals(start + 120, orbiting.innerRecovery());
        assertEquals(start + 160, recovery.outerRecovery());
        assertEquals(HYPERSTABLE, GuixuBlackHolePreview.sample(start, start + 205).status());
        assertTrue(GuixuBlackHolePreview.sample(start, start + 300).stability() < 50);
        assertEquals(UNSTABLE, GuixuBlackHolePreview.sample(start, start + 350).status());
        var collapse = GuixuBlackHolePreview.sample(start, start + 391);
        assertEquals(CLOSED, collapse.status());
        assertEquals(start + 390, collapse.collapse());
    }

    @Test
    void waitsForTheStartAndReturnsToLiveDataAtTheEnd() {
        assertNull(GuixuBlackHolePreview.sample(-1, 20));
        assertNull(GuixuBlackHolePreview.sample(100, 99.9));
        assertNotNull(GuixuBlackHolePreview.sample(100, 539.9));
        assertNull(GuixuBlackHolePreview.sample(100, 540));
    }
}
